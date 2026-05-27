/*
 * Copyright 2026 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.binder;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

final class BinderPickFirstLoadBalancer extends LoadBalancer {
  private final Helper helper;
  private List<EquivalentAddressGroup> addresses;
  private int currentIndex = 0;
  private Subchannel subchannel;
  private ConnectivityState currentState = IDLE;

  BinderPickFirstLoadBalancer(Helper helper) {
    this.helper = checkNotNull(helper, "helper");
  }

  @Override
  public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    List<EquivalentAddressGroup> servers = resolvedAddresses.getAddresses();
    if (servers.isEmpty()) {
      Status unavailableStatus = Status.UNAVAILABLE.withDescription(
          "NameResolver returned no usable address. addrs=" + resolvedAddresses.getAddresses()
              + ", attrs=" + resolvedAddresses.getAttributes());
      handleNameResolutionError(unavailableStatus);
      return unavailableStatus;
    }
    if (this.addresses == null || !this.addresses.equals(servers)) {
      this.addresses = servers;
      currentIndex = 0;
      createAndStartSubchannel();
    }
    return Status.OK;
  }

  private void createAndStartSubchannel() {
    if (subchannel != null) {
      subchannel.shutdown();
    }
    subchannel = helper.createSubchannel(
        CreateSubchannelArgs.newBuilder()
            .setAddresses(addresses.get(currentIndex))
            .build());
    subchannel.start(stateInfo -> processSubchannelState(stateInfo));
    subchannel.requestConnection();
    updateBalancingState(CONNECTING, new FixedResultPicker(PickResult.withNoResult()));
  }

  private void processSubchannelState(ConnectivityStateInfo stateInfo) {
    ConnectivityState newState = stateInfo.getState();
    if (newState == SHUTDOWN) {
      return;
    }

    if (newState == TRANSIENT_FAILURE || newState == IDLE) {
      helper.refreshNameResolution();
    }

    if (currentState == TRANSIENT_FAILURE) {
      if (newState == CONNECTING) {
        return;
      } else if (newState == IDLE) {
        subchannel.requestConnection();
        return;
      }
    }

    SubchannelPicker picker;
    switch (newState) {
      case IDLE:
        picker = new RequestConnectionPicker(subchannel);
        break;
      case CONNECTING:
        picker = new FixedResultPicker(PickResult.withNoResult());
        break;
      case READY:
        picker = new FixedResultPicker(PickResult.withSubchannel(subchannel));
        break;
      case TRANSIENT_FAILURE:
        Status status = stateInfo.getStatus();
        if (status.getCause() instanceof UntrustedServerException) {
          currentIndex++;
          if (currentIndex < addresses.size()) {
            createAndStartSubchannel();
            return;
          }
        }
        picker = new FixedResultPicker(PickResult.withError(status));
        break;
      default:
        throw new IllegalArgumentException("Unsupported state:" + newState);
    }

    updateBalancingState(newState, picker);
  }

  private void updateBalancingState(ConnectivityState state, SubchannelPicker picker) {
    currentState = state;
    helper.updateBalancingState(state, picker);
  }

  @Override
  public void handleNameResolutionError(Status error) {
    if (subchannel != null) {
      subchannel.shutdown();
      subchannel = null;
    }
    updateBalancingState(TRANSIENT_FAILURE, new FixedResultPicker(PickResult.withError(error)));
  }

  @Override
  public void shutdown() {
    if (subchannel != null) {
      subchannel.shutdown();
    }
  }

  private static final class FixedResultPicker extends SubchannelPicker {
    private final PickResult result;

    FixedResultPicker(PickResult result) {
      this.result = result;
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return result;
    }
  }

  private static final class RequestConnectionPicker extends SubchannelPicker {
    private final Subchannel subchannel;
    private final AtomicBoolean connectionRequested = new AtomicBoolean();

    RequestConnectionPicker(Subchannel subchannel) {
      this.subchannel = subchannel;
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      if (connectionRequested.compareAndSet(false, true)) {
        subchannel.requestConnection();
      }
      return PickResult.withNoResult();
    }
  }
}

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

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class BinderPickFirstLoadBalancerTest {
  private BinderPickFirstLoadBalancer loadBalancer;
  private final List<EquivalentAddressGroup> servers = new ArrayList<>();
  private final List<SocketAddress> socketAddresses = new ArrayList<>();

  private final SynchronizationContext syncContext = new SynchronizationContext(
      (t, e) -> { throw new AssertionError(e); });

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  @Captor private ArgumentCaptor<SubchannelPicker> pickerCaptor;
  @Captor private ArgumentCaptor<CreateSubchannelArgs> createArgsCaptor;
  @Captor private ArgumentCaptor<SubchannelStateListener> stateListenerCaptor;

  @Mock private Helper mockHelper;
  @Mock private Subchannel mockSubchannel1;
  @Mock private Subchannel mockSubchannel2;
  @Mock private PickSubchannelArgs mockArgs;

  @Before
  public void setUp() {
    for (int i = 0; i < 3; i++) {
      SocketAddress addr = new FakeSocketAddress("server" + i);
      servers.add(new EquivalentAddressGroup(addr));
      socketAddresses.add(addr);
    }

    when(mockHelper.getSynchronizationContext()).thenReturn(syncContext);
    when(mockHelper.createSubchannel(any(CreateSubchannelArgs.class)))
        .thenReturn(mockSubchannel1, mockSubchannel2);

    loadBalancer = new BinderPickFirstLoadBalancer(mockHelper);
  }

  @After
  public void tearDown() {
    verifyNoMoreInteractions(mockArgs);
  }

  @Test
  public void pickAfterResolved() throws Exception {
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).build());

    verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    CreateSubchannelArgs args = createArgsCaptor.getValue();
    assertThat(args.getAddresses()).containsExactly(servers.get(0));
    verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verify(mockSubchannel1).requestConnection();

    verify(mockHelper, atLeast(0)).getSynchronizationContext();
    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void subchannelConnects() throws Exception {
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).build());

    verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();

    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    
    PickResult result = pickerCaptor.getValue().pickSubchannel(mockArgs);
    assertThat(result.getSubchannel()).isSameInstanceAs(mockSubchannel1);
  }

  @Test
  public void failoverOnUntrustedServerException() throws Exception {
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).build());

    verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener1 = stateListenerCaptor.getValue();

    Status untrustedError = Status.PERMISSION_DENIED
        .withCause(new UntrustedServerException("untrusted"))
        .withDescription("security policy reject");
    
    stateListener1.onSubchannelState(ConnectivityStateInfo.forTransientFailure(untrustedError));

    verify(mockSubchannel1).shutdown();

    verify(mockHelper, times(2)).createSubchannel(createArgsCaptor.capture());
    List<CreateSubchannelArgs> allArgs = createArgsCaptor.getAllValues();
    assertThat(allArgs.get(1).getAddresses()).containsExactly(servers.get(1));

    verify(mockSubchannel2).requestConnection();
  }

  @Test
  public void failFastOnOtherException() throws Exception {
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).build());

    verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener1 = stateListenerCaptor.getValue();

    Status securityError = Status.PERMISSION_DENIED
        .withCause(new SecurityException("generic"))
        .withDescription("bindService failed");

    stateListener1.onSubchannelState(ConnectivityStateInfo.forTransientFailure(securityError));

    verify(mockSubchannel1, never()).shutdown();
    verify(mockHelper, times(1)).createSubchannel(any(CreateSubchannelArgs.class));

    verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    PickResult result = pickerCaptor.getValue().pickSubchannel(mockArgs);
    assertThat(result.getStatus()).isSameInstanceAs(securityError);
  }

  @Test
  public void exhaustionOfAddresses() throws Exception {
    List<EquivalentAddressGroup> twoServers = servers.subList(0, 2);
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(twoServers).build());

    verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener1 = stateListenerCaptor.getValue();

    Status untrustedError = Status.PERMISSION_DENIED
        .withCause(new UntrustedServerException("untrusted"));

    stateListener1.onSubchannelState(ConnectivityStateInfo.forTransientFailure(untrustedError));
    
    verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();

    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(untrustedError));

    verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    PickResult result = pickerCaptor.getValue().pickSubchannel(mockArgs);
    assertThat(result.getStatus()).isSameInstanceAs(untrustedError);
  }

  @Test
  public void emptyAddresses() {
    Status status = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(Collections.emptyList()).build());

    assertThat(status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));
  }

  @Test
  public void nameResolutionError() {
    Status error = Status.UNAVAILABLE.withDescription("name resolution failed");
    loadBalancer.handleNameResolutionError(error);

    verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    PickResult result = pickerCaptor.getValue().pickSubchannel(mockArgs);
    assertThat(result.getStatus()).isSameInstanceAs(error);
  }

  @Test
  public void requestConnectionPicker() throws Exception {
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).build());

    InOrder inOrder = inOrder(mockHelper, mockSubchannel1);
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel1).requestConnection();
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), any(SubchannelPicker.class));

    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
    inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());

    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(picker.pickSubchannel(mockArgs), picker.pickSubchannel(mockArgs));
    inOrder.verify(mockSubchannel1).requestConnection();

    verify(mockSubchannel1, times(2)).requestConnection();
  }

  @Test
  public void refreshNameResolution() throws Exception {
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).build());

    verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();

    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
    verify(mockHelper).refreshNameResolution();

    Status error = Status.UNAVAILABLE.withDescription("transient failure");
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    verify(mockHelper, times(2)).refreshNameResolution();
  }

  @Test
  public void stickyTransientFailure() throws Exception {
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).build());

    verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();

    // Go to TRANSIENT_FAILURE (fail fast)
    Status error = Status.UNAVAILABLE.withDescription("boom").withCause(new SecurityException("generic"));
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    SubchannelPicker errorPicker = pickerCaptor.getValue();

    // Subsequent IDLE should be ignored (no balancing state update) but request connection
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
    verify(mockSubchannel1, times(2)).requestConnection(); // once at start, once now
    verify(mockHelper, never()).updateBalancingState(eq(IDLE), any(SubchannelPicker.class));
    
    // Picker should still return error
    assertThat(errorPicker.pickSubchannel(mockArgs).getStatus()).isSameInstanceAs(error);

    // Subsequent CONNECTING should also be ignored
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    verify(mockHelper, times(1)).updateBalancingState(eq(CONNECTING), any(SubchannelPicker.class));
    assertThat(errorPicker.pickSubchannel(mockArgs).getStatus()).isSameInstanceAs(error);
  }

  @SuppressWarnings("serial")
  private static class FakeSocketAddress extends SocketAddress {
    private final String name;

    FakeSocketAddress(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return name;
    }
  }
}

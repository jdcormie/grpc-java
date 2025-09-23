/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.binder.internal;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;

import com.google.common.collect.ImmutableList;
import io.grpc.Attributes;
import io.grpc.Status;
import io.grpc.internal.FixedObjectPool;
import io.grpc.internal.MockServerTransportListener;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

/**
 * Low-level server-side transport tests for binder channel. Like BinderChannelSmokeTest, this
 * convers edge cases not exercised by AbstractTransportTest, but it deals with the
 * binderTransport.BinderServerTransport directly.
 */
@RunWith(RobolectricTestRunner.class)
public final class BinderServerTransportTest {

  @Rule public MockitoRule mocks = MockitoJUnit.rule();

  private final ScheduledExecutorService executorService = new MainThreadScheduledExecutorService();
  private MockServerTransportListener transportListener;

  @Mock IBinder mockBinder;

  BinderServerTransport transport;

  @Before
  public void setUp() throws Exception {
    transport =
        new BinderServerTransport(
            new FixedObjectPool<>(executorService),
            Attributes.EMPTY,
            ImmutableList.of(),
            OneWayBinderProxy.IDENTITY_DECORATOR);
    transportListener = new MockServerTransportListener(transport);
  }

  @Test
  public void testSetupTransactionFailureCausesMultipleShutdowns_b153460678() throws Exception {
    // Make the binder fail the setup transaction.
    when(mockBinder.transact(anyInt(), any(Parcel.class), isNull(), anyInt())).thenReturn(false);
    transport.start(mockBinder, transportListener);

    // Now shut it down.
    transport.shutdownNow(Status.UNKNOWN.withDescription("reasons"));
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(transportListener.isTerminated()).isTrue();
  }

  @Test
  public void testClientBinderDeadOnArrival() throws Exception {
    RemoteException re = new RemoteException("No lieutenant, your men are already dead.");
    when(mockBinder.transact(anyInt(), any(Parcel.class), isNull(), anyInt())).thenThrow(re);
    doThrow(re).when(mockBinder).linkToDeath(any(), anyInt());

    transport.start(mockBinder, transportListener);
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(transportListener.isTerminated()).isTrue();
  }

  @Test
  public void testShutdownBeforeStart() throws Exception {
    transport.shutdownNow(Status.CANCELLED.withDescription("Handshake timeout exceeded"));
    shadowOf(Looper.getMainLooper()).idle();
    transport.start(mockBinder, transportListener);
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(transportListener.isTerminated()).isTrue();
  }
}

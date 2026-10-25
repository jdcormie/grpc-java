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

package io.grpc.stub;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertTrue;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServiceDescriptor;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.testing.GrpcCleanupRule;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * End-to-end tests for custom events at the {@link ServerCalls} layer, run over the in-process
 * transport with both a direct executor and a thread pool as the server executor.
 */
@RunWith(Parameterized.class)
public class ServerCallsEventsTest {
  private static final MethodDescriptor<Integer, Integer> SERVER_STREAMING_METHOD =
      MethodDescriptor.<Integer, Integer>newBuilder()
          .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
          .setFullMethodName("events/server_streaming")
          .setRequestMarshaller(new ServerCallsTest.IntegerMarshaller())
          .setResponseMarshaller(new ServerCallsTest.IntegerMarshaller())
          .build();

  /** How many RPCs to race per test, to widen the window in which cancellation can interleave. */
  private static final int RACE_ITERATIONS = 20;
  /** How many events to trigger after cancellation has been observed by the producer. */
  private static final int EVENTS_AFTER_CANCEL = 100;

  @Parameters(name = "{0}")
  public static Collection<Object[]> params() {
    return Arrays.asList(new Object[][] {{"directExecutor"}, {"threadPool"}});
  }

  @Rule public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  private final boolean direct;
  private ExecutorService executor;
  private Server server;
  private ManagedChannel channel;
  private EventDrivenService service;
  private CallbackProbe probe;

  public ServerCallsEventsTest(String executorName) {
    this.direct = executorName.equals("directExecutor");
  }

  @Before
  public void setUp() throws Exception {
    service = new EventDrivenService();
    probe = new CallbackProbe();
    ServerServiceDefinition definition = ServerInterceptors.intercept(
        ServerServiceDefinition.builder(new ServiceDescriptor("events", SERVER_STREAMING_METHOD))
            .addMethod(SERVER_STREAMING_METHOD, ServerCalls.asyncServerStreamingCall(service))
            .build(),
        probe);
    String name = InProcessServerBuilder.generateName();
    InProcessServerBuilder serverBuilder = InProcessServerBuilder.forName(name);
    InProcessChannelBuilder channelBuilder = InProcessChannelBuilder.forName(name);
    if (direct) {
      serverBuilder.directExecutor();
      channelBuilder.directExecutor();
    } else {
      executor = Executors.newFixedThreadPool(4);
      serverBuilder.executor(executor);
      channelBuilder.executor(executor);
    }
    server = grpcCleanup.register(serverBuilder.addService(definition).build().start());
    channel = grpcCleanup.register(channelBuilder.build());
  }

  @After
  public void tearDown() throws Exception {
    // Tear down the channel and server before the executor they run on; GrpcCleanupRule would
    // otherwise shut them down after @After, when the executor can no longer run their tasks.
    channel.shutdownNow();
    server.shutdownNow();
    assertTrue(server.awaitTermination(5, TimeUnit.SECONDS));
    assertTrue(channel.awaitTermination(5, TimeUnit.SECONDS));
    if (executor != null) {
      executor.shutdownNow();
    }
  }

  @Test
  public void eventFromForeignThreadIsDeliveredToHandlerAndCanSendResponse() throws Exception {
    RecordingClientObserver client = new RecordingClientObserver();
    ClientCalls.asyncServerStreamingCall(
        channel.newCall(SERVER_STREAMING_METHOD, CallOptions.DEFAULT), 1, client);
    ServerCallStreamObserver<Integer> observer = service.awaitInvocation();
    CallProbe callProbe = probe.awaitCall();

    Thread producer = new Thread(() -> observer.triggerEvent(new Send(42, /* last= */ true)));
    producer.start();
    producer.join();

    assertTrue(client.closed.await(5, TimeUnit.SECONDS));
    assertThat(client.messages).containsExactly(42);
    assertThat(client.status.get().getCode()).isEqualTo(Status.Code.OK);
    assertThat(service.handlerFailure.get()).isNull();
    assertThat(callProbe.overlappingCallbacks.get()).isFalse();
  }

  @Test
  public void onNextFromEventHandlerNeverThrowsWhenClientCancelsConcurrently() throws Exception {
    // Repeated to give the cancellation a realistic chance of interleaving with event delivery.
    for (int i = 0; i < RACE_ITERATIONS; i++) {
      CallProbe callProbe = raceEventsAgainstClientCancellation();
      assertThat(service.handlerFailure.get()).isNull();
      assertThat(callProbe.eventAfterCancel.get()).isFalse();
      assertThat(callProbe.overlappingCallbacks.get()).isFalse();
    }
  }

  /**
   * Runs one RPC in which a non-gRPC thread floods the call with events while the client cancels.
   * Returns once the server has processed the cancellation and the producer has exited.
   */
  private CallProbe raceEventsAgainstClientCancellation() throws Exception {
    service.reset();
    RecordingClientObserver client = new RecordingClientObserver();
    ClientCall<Integer, Integer> clientCall =
        channel.newCall(SERVER_STREAMING_METHOD, CallOptions.DEFAULT);
    ClientCalls.asyncServerStreamingCall(clientCall, 1, client);
    ServerCallStreamObserver<Integer> observer = service.awaitInvocation();
    CallProbe callProbe = probe.awaitCall();

    Thread producer = new Thread(() -> {
      int i = 0;
      while (!observer.isCancelled()) {
        observer.triggerEvent(new Send(i++, /* last= */ false));
      }
      for (int k = 0; k < EVENTS_AFTER_CANCEL; k++) {
        observer.triggerEvent(new Send(i++, /* last= */ false));
      }
    });
    producer.start();

    assertTrue(client.firstMessage.await(5, TimeUnit.SECONDS));
    clientCall.cancel("test cancel", null);
    assertTrue(callProbe.serverCancelled.await(5, TimeUnit.SECONDS));
    producer.join(TimeUnit.SECONDS.toMillis(5));
    assertThat(producer.isAlive()).isFalse();
    assertTrue(client.closed.await(5, TimeUnit.SECONDS));
    assertThat(client.status.get().getCode()).isEqualTo(Status.Code.CANCELLED);
    return callProbe;
  }

  /** The event payload: a response to send and whether to complete the call afterwards. */
  private static final class Send {
    final int value;
    final boolean last;

    Send(int value, boolean last) {
      this.value = value;
      this.last = last;
    }
  }

  /**
   * A server-streaming service that only sends from its event handler. It deliberately does not
   * register an {@code onCancelHandler}, so {@code onNext()} after cancellation would throw.
   */
  private static final class EventDrivenService
      implements ServerCalls.ServerStreamingMethod<Integer, Integer> {
    final AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
    private volatile CountDownLatch invoked = new CountDownLatch(1);
    private volatile ServerCallStreamObserver<Integer> observer;

    void reset() {
      invoked = new CountDownLatch(1);
      observer = null;
      handlerFailure.set(null);
    }

    ServerCallStreamObserver<Integer> awaitInvocation() throws InterruptedException {
      assertTrue(invoked.await(5, TimeUnit.SECONDS));
      return observer;
    }

    @Override
    public void invoke(Integer request, StreamObserver<Integer> responseObserver) {
      ServerCallStreamObserver<Integer> serverCallObserver =
          (ServerCallStreamObserver<Integer>) responseObserver;
      serverCallObserver.setOnEventHandler(new Consumer<Object>() {
        // Plain field: only accessed from the serialized event handler.
        private boolean done;

        @Override
        public void accept(Object event) {
          if (done || !(event instanceof Send)) {
            return;
          }
          Send send = (Send) event;
          try {
            serverCallObserver.onNext(send.value);
            if (send.last) {
              done = true;
              serverCallObserver.onCompleted();
            }
          } catch (Throwable t) {
            handlerFailure.compareAndSet(null, t);
          }
        }
      });
      observer = serverCallObserver;
      invoked.countDown();
    }
  }

  /**
   * Observes the raw listener callbacks ahead of the stub to detect concurrent callbacks and
   * events delivered after cancellation, and to learn when the server has processed the cancel.
   * State is kept per call so that consecutive RPCs cannot interfere with each other.
   */
  private static final class CallbackProbe implements ServerInterceptor {
    private final BlockingQueue<CallProbe> calls = new LinkedBlockingQueue<>();

    /** Returns the probe for the next intercepted call. */
    CallProbe awaitCall() throws InterruptedException {
      CallProbe callProbe = calls.poll(5, TimeUnit.SECONDS);
      assertThat(callProbe).isNotNull();
      return callProbe;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      CallProbe callProbe = new CallProbe();
      calls.add(callProbe);
      return callProbe.wrap(next.startCall(call, headers));
    }
  }

  private static final class CallProbe {
    final AtomicBoolean overlappingCallbacks = new AtomicBoolean();
    final AtomicBoolean eventAfterCancel = new AtomicBoolean();
    final CountDownLatch serverCancelled = new CountDownLatch(1);
    private final AtomicInteger activeCallbacks = new AtomicInteger();
    private volatile boolean cancelDelivered;

    private void enter() {
      if (activeCallbacks.incrementAndGet() != 1) {
        overlappingCallbacks.set(true);
      }
    }

    private void exit() {
      activeCallbacks.decrementAndGet();
    }

    <ReqT> ServerCall.Listener<ReqT> wrap(ServerCall.Listener<ReqT> delegate) {
      return new SimpleForwardingServerCallListener<ReqT>(delegate) {
        @Override
        public void onMessage(ReqT message) {
          enter();
          try {
            super.onMessage(message);
          } finally {
            exit();
          }
        }

        @Override
        public void onHalfClose() {
          enter();
          try {
            super.onHalfClose();
          } finally {
            exit();
          }
        }

        @Override
        public void onReady() {
          enter();
          try {
            super.onReady();
          } finally {
            exit();
          }
        }

        @Override
        public void onComplete() {
          enter();
          try {
            super.onComplete();
          } finally {
            exit();
          }
        }

        @Override
        public void onCancel() {
          enter();
          try {
            super.onCancel();
            cancelDelivered = true;
          } finally {
            exit();
          }
          // Signal only after exit() so a waiter cannot observe this callback as still active.
          serverCancelled.countDown();
        }

        @Override
        public void onEvent(Object event) {
          enter();
          try {
            if (cancelDelivered) {
              eventAfterCancel.set(true);
            }
            super.onEvent(event);
          } finally {
            exit();
          }
        }
      };
    }
  }

  private static final class RecordingClientObserver implements StreamObserver<Integer> {
    final List<Integer> messages =
        Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch firstMessage = new CountDownLatch(1);
    final CountDownLatch closed = new CountDownLatch(1);
    final AtomicReference<Status> status = new AtomicReference<>();

    @Override
    public void onNext(Integer value) {
      messages.add(value);
      firstMessage.countDown();
    }

    @Override
    public void onError(Throwable t) {
      status.set(Status.fromThrowable(t));
      closed.countDown();
    }

    @Override
    public void onCompleted() {
      status.set(Status.OK);
      closed.countDown();
    }
  }
}

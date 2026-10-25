/*
 * Copyright 2016 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkArgument;

import io.grpc.ExperimentalApi;
import java.util.function.Consumer;

/**
 * A refinement of {@link CallStreamObserver} to allows for interaction with call
 * cancellation and custom events on the server side. An instance of this class is obtained by
 * casting the {@code StreamObserver} passed as an argument to service implementations.
 *
 * <p>Like {@code StreamObserver}, implementations are not required to be thread-safe; if multiple
 * threads will be writing to an instance concurrently, the application must synchronize its calls.
 * The exceptions are the methods documented as safe to call from any thread, such as
 * {@link #isCancelled()}, {@link #request(int)} and {@link #triggerEvent(Object)}.
 *
 * <p>DO NOT MOCK: The API is too complex to reliably mock. Use InProcessChannelBuilder to create
 * "real" RPCs suitable for testing and interact with the server using a normal client stub.
 */
public abstract class ServerCallStreamObserver<RespT> extends CallStreamObserver<RespT> {

  /**
   * Returns {@code true} when the call is cancelled and the server is encouraged to abort
   * processing to save resources, since the client will not be processing any further methods.
   * Cancellations can be caused by timeouts, explicit cancellation by client, network errors, and
   * similar.
   *
   * <p>This method may safely be called concurrently from multiple threads.
   */
  public abstract boolean isCancelled();

  /**
   * Sets a {@link Runnable} to be called if the call is cancelled and the server is encouraged to
   * abort processing to save resources, since the client will not process any further messages.
   * Cancellations can be caused by timeouts, explicit cancellation by the client, network errors,
   * etc.
   *
   * <p>It is guaranteed that execution of the {@link Runnable} is serialized with calls to the
   * 'inbound' {@link StreamObserver}. That also means that the callback will be delayed if other
   * callbacks are running; if one of those other callbacks runs for a significant amount of time
   * it can poll {@link #isCancelled()}, which is not delayed.
   *
   * <p>This method may only be called during the initial call to the application, before the
   * service returns its {@code StreamObserver}.
   *
   * <p>Setting the onCancelHandler will suppress the on-cancel exception thrown by
   * {@link #onNext}. If the caller is already handling cancellation via polling or cannot
   * substantially benefit from observing cancellation, using a no-op {@code onCancelHandler} is
   * useful just to suppress the {@code onNext()} exception.
   *
   * <p>The on-cancel exception is most often encountered when {@link #onNext} is called from a
   * thread that is not serialized with the cancellation notification, for example from the
   * callback of an external event source. For such servers the preferred approach is to have the
   * external source {@link #triggerEvent trigger an event} and to call {@link #onNext} from the
   * handler registered with {@link #setOnEventHandler}, which is serialized with cancellation. The
   * exception then cannot occur regardless of whether an {@code onCancelHandler} is set.
   *
   * @param onCancelHandler to call when client has cancelled the call.
   */
  public abstract void setOnCancelHandler(Runnable onCancelHandler);

  /**
   * Sets a handler to be called with each custom event {@linkplain #triggerEvent triggered} on
   * this call.
   *
   * <p>It is guaranteed that execution of the handler is serialized with calls to the 'inbound'
   * {@link StreamObserver} and with the {@code onReadyHandler}, {@code onCancelHandler} and
   * {@code onCloseHandler}: no two of these callbacks run concurrently, and each sees the effects
   * of the callbacks that ran before it. Events triggered from one thread are delivered in the
   * order they were triggered. An event triggered before the call is cancelled is either delivered
   * before the cancellation is observed by this observer or discarded; it is never delivered after
   * the {@code onCancelHandler} has run. Consequently, calling {@link #onNext} from within the
   * handler never throws the on-cancel exception described in {@link #setOnCancelHandler}.
   *
   * <p>These guarantees make it possible to confine all per-call state to the call's executor. A
   * service fed by an external source (a sensor, a message bus, a callback from another library)
   * should have the source do nothing but call {@link #triggerEvent} with an immutable, or
   * otherwise safely published, payload, and should do all of its work, including calling
   * {@link #onNext} and registering or unregistering with the source, from this handler and the
   * other callbacks of the call. Such a service needs no locks for its per-call state. Note the
   * limits of this: the source's callback still runs on a foreign thread and must not touch call
   * state directly; anything the handler reads from the source must be thread-safe on the source's
   * side; and the handler must still track whether the application has already completed the
   * call, as described next.
   *
   * <p>Events are discarded if no handler is set and are never delivered after the call has been
   * cancelled or has completed. However, this observer does <em>not</em> filter events once the
   * application has called {@link #onCompleted()} or {@link #onError(Throwable)}: an event
   * triggered after that point but before the call completes is still delivered, and calling
   * {@link #onNext} from the handler in that state throws {@link IllegalStateException}. A handler
   * that sends messages should therefore consult a plain, unsynchronized "already closed" field,
   * which is sufficient because the handler and the code that completes the call are serialized.
   *
   * <p>Custom events share a single channel with {@link io.grpc.ServerInterceptor}s that call
   * {@link io.grpc.ServerCall#triggerEvent}. A handler should check the type of each event and
   * ignore those it does not recognize.
   *
   * <p>This method may only be called during the initial call to the application, before the
   * service returns its {@code StreamObserver}. For
   * {@link io.grpc.MethodDescriptor.MethodType#UNARY} and
   * {@link io.grpc.MethodDescriptor.MethodType#SERVER_STREAMING} methods, events triggered before
   * the service method is invoked (which can only come from interceptors) are discarded.
   *
   * <p>Example: a server-streaming method that pushes location updates. The location provider
   * invokes its listeners on an arbitrary thread; the listener only triggers an event, and all
   * call state is touched exclusively on the call's executor, so the fields need no
   * synchronization:
   *
   * <pre>{@code
   * public void watchLocation(WatchRequest req, StreamObserver<Location> responseObserver) {
   *   new LocationWatch((ServerCallStreamObserver<Location>) responseObserver).start();
   * }
   *
   * final class LocationWatch implements LocationListener {
   *   private final ServerCallStreamObserver<Location> observer;
   *   private boolean done;  // Plain field: only accessed on the call's executor.
   *
   *   LocationWatch(ServerCallStreamObserver<Location> observer) {
   *     this.observer = observer;
   *   }
   *
   *   void start() {  // Runs on the call's executor.
   *     observer.setOnEventHandler(this::onEvent);
   *     observer.setOnCancelHandler(this::stop);
   *     observer.setOnCloseHandler(this::stop);
   *     locationProvider.addListener(this);  // locationProvider is thread-safe.
   *   }
   *
   *   public void onLocationChanged(Location location) {  // Runs on the provider's thread.
   *     observer.triggerEvent(location);
   *   }
   *
   *   private void onEvent(Object event) {  // Runs on the call's executor.
   *     if (done || !(event instanceof Location)) {
   *       return;
   *     }
   *     Location location = (Location) event;
   *     observer.onNext(location);
   *     if (location.isFinal()) {
   *       done = true;
   *       observer.onCompleted();
   *     }
   *   }
   *
   *   private void stop() {  // Runs on the call's executor.
   *     done = true;
   *     locationProvider.removeListener(this);
   *   }
   * }
   * }</pre>
   *
   * @param onEventHandler to call with each event delivered to this call.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/12979")
  public void setOnEventHandler(Consumer<Object> onEventHandler) {
    throw new UnsupportedOperationException();
  }

  /**
   * Triggers a custom event on this call. The event is delivered to the handler registered with
   * {@link #setOnEventHandler} on the call's executor, serialized with the other callbacks of the
   * call; see {@link #setOnEventHandler} for the ordering guarantees.
   *
   * <p>Unlike most methods of this class, this method is safe to call from any thread without
   * external synchronization. It is the intended way for code that is not running in a callback
   * of this call, such as the callback of an external event source, to interact with the call.
   * The handoff to the call's executor establishes a happens-before relationship between the
   * caller and the handler, so an event payload that is not mutated after being triggered needs
   * no further synchronization.
   *
   * <p>This method never throws because of the state of the call. The event is discarded if no
   * handler is set, if the call has been cancelled, or if the call has completed. An event
   * triggered after the application has called {@link #onCompleted()} or {@link #onError} but
   * before the call has completed is still delivered to the handler; see
   * {@link #setOnEventHandler}.
   *
   * <p><strong>Deadlock avoidance:</strong> Do not hold application locks while calling this
   * method. Depending on the transport and the server's executor (in particular with
   * {@code directExecutor()} or the Binder transport), the handler may run synchronously on the
   * calling thread, possibly while transport locks are held. If the caller holds a lock that the
   * handler, or any other callback of this call, also needs, the result can be a deadlock.
   * Compute the event payload under lock if necessary, release the lock, and only then call
   * {@code triggerEvent}.
   *
   * @param event the event to deliver to the handler.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/12979")
  public void triggerEvent(Object event) {
    throw new UnsupportedOperationException();
  }

  /**
   * A hint to the call that specifies how many bytes must be queued before
   * {@link #isReady()} will return false. A call may ignore this property if
   * unsupported. This may only be set during stream initialization before
   * any messages are set.
   *
   * @param numBytes The number of bytes that must be queued. Must be a
   *                 positive integer.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/11021")
  public void setOnReadyThreshold(int numBytes) {
    checkArgument(numBytes > 0, "numBytes must be positive: %s", numBytes);
  }

  /**
   * Sets the compression algorithm to use for the call. May only be called before sending any
   * messages. Default gRPC servers support the "gzip" compressor.
   *
   * <p>It is safe to call this even if the client does not support the compression format chosen.
   * The implementation will handle negotiation with the client and may fall back to no compression.
   *
   * @param compression the compression algorithm to use.
   * @throws IllegalArgumentException if the compressor name can not be found.
   */
  public abstract void setCompression(String compression);

  /**
   * Swaps to manual flow control where no message will be delivered to {@link
   * StreamObserver#onNext(Object)} unless it is {@link #request request()}ed.
   *
   * <p>It may only be called during the initial call to the application, before the service returns
   * its {@code StreamObserver}.
   *
   * <p>Note that for cases where the message is received before the service handler is invoked,
   * this method will have no effect. This is true for:
   *
   * <ul>
   *   <li>{@link io.grpc.MethodDescriptor.MethodType#UNARY} operations.</li>
   *   <li>{@link io.grpc.MethodDescriptor.MethodType#SERVER_STREAMING} operations.</li>
   * </ul>
   * </p>
   */
  public void disableAutoRequest() {
    throw new UnsupportedOperationException();
  }


  /**
   * If {@code true}, indicates that the observer is capable of sending additional messages
   * without requiring excessive buffering internally. This value is just a suggestion and the
   * application is free to ignore it, however doing so may result in excessive buffering within the
   * observer.
   *
   * <p>If {@code false}, the runnable passed to {@link #setOnReadyHandler} will be called after
   * {@code isReady()} transitions to {@code true}.
   */
  @Override
  public abstract boolean isReady();

  /**
   * Set a {@link Runnable} that will be executed every time the stream {@link #isReady()} state
   * changes from {@code false} to {@code true}.  While it is not guaranteed that the same
   * thread will always be used to execute the {@link Runnable}, it is guaranteed that executions
   * are serialized with calls to the 'inbound' {@link StreamObserver}.
   *
   * <p>May only be called during the initial call to the application, before the service returns
   * its {@code StreamObserver}.
   *
   * <p>Because there is a processing delay to deliver this notification, it is possible for
   * concurrent writes to cause {@code isReady() == false} within this callback. Handle "spurious"
   * notifications by checking {@code isReady()}'s current value instead of assuming it is now
   * {@code true}. If {@code isReady() == false} the normal expectations apply, so there would be
   * <em>another</em> {@code onReadyHandler} callback.
   *
   * @param onReadyHandler to call when peer is ready to receive more messages.
   */
  @Override
  public abstract void setOnReadyHandler(Runnable onReadyHandler);

  /**
   * Requests that {@code count} more messages be delivered to the 'inbound' {@link StreamObserver}.
   *
   * <p>This method is safe to call from multiple threads without external synchronization.
   *
   * @param count more messages
   */
  @Override
  public abstract void request(int count);

  /**
   * Sets message compression for subsequent calls to {@link #onNext}.
   *
   * @param enable whether to enable compression.
   */
  @Override
  public abstract void setMessageCompression(boolean enable);

  /**
   * Sets a {@link Runnable} to be executed when the call is closed cleanly from the server's
   * point of view: either {@link #onCompleted()} or {@link #onError(Throwable)} has been called,
   * all the messages and trailing metadata have been sent and the stream has been closed. Note
   * however that the client still may have not received all the messages due to network delay,
   * client crashes, and cancellation races.
   *
   * <p>Exactly one of {@code onCloseHandler} and {@code onCancelHandler} is guaranteed to be called
   * when the RPC terminates.</p>
   *
   * <p>It is guaranteed that execution of {@code onCloseHandler} is serialized with calls to
   * the 'inbound' {@link StreamObserver}. That also means that the callback will be delayed if
   * other callbacks are running.</p>
   *
   * <p>This method may only be called during the initial call to the application, before the
   * service returns its {@link StreamObserver request observer}.</p>
   *
   * @param onCloseHandler to execute when the call has been closed cleanly.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/8467")
  public void setOnCloseHandler(Runnable onCloseHandler) {
    throw new UnsupportedOperationException();
  }
}

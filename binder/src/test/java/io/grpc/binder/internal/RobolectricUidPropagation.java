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

package io.grpc.binder.internal;

import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;
import org.robolectric.shadows.ShadowBinder;
import io.grpc.binder.internal.LeakSafeOneWayBinder.TransactionHandler;

/**
 * Helpers for Robolectric tests to propagate calling UID across in-process binder transactions.
 *
 * <p>Because Robolectric tests run client and server in a single process, Android's automatic
 * cross-process UID propagation does not occur. This class provides decorators to simulate this
 * propagation by passing the fake calling UID inside the transaction {@link Parcel}.
 *
 * <p>The outgoing decorator prepends the UID to the parcel, and the incoming decorator extracts it
 * and sets it as the calling UID for the duration of the transaction handling.
 */
public final class RobolectricUidPropagation {
  private RobolectricUidPropagation() {}

  /**
   * Returns a decorator that prepends the given UID to all outgoing transactions.
   *
   * <p>Use this to give a client or server BinderTransport a fake Android/Linux identity. The peer
   * *must* be configured with {@link #newUidRestoringBinderDecorator()} for this to work.
   */
  public static OneWayBinderProxy.Decorator newUidPassingBinderDecorator(int uid) {
    return input ->
        new OneWayBinderProxy(input.getDelegate()) {
          @Override
          public void transact(int code, ParcelHolder data) throws RemoteException {
            try (ParcelHolder newData = ParcelHolder.obtain()) {
              newData.get().writeInt(uid);
              Parcel srcParcel = data.get();
              newData.get().appendFrom(srcParcel, 0, srcParcel.dataSize());
              data.close(); // We consume the original data.
              input.transact(code, newData);
            }
          }
        };
  }

  /**
   * Returns a decorator that fakes the calling UID provided by the peer for every transaction.
   *
   * <p>The peer must be configured with {@link #newUidPassingBinderDecorator(int)} for this to
   * work.
   */
  public static TransactionHandler.Decorator newUidRestoringBinderDecorator() {
    return input ->
        (code, data) -> {
          int uid = data.readInt();
          int originalUid = Binder.getCallingUid();
          try {
            // TODO(jdcormie): Use the new thread-safe Robolectric API once it is available.
            // (https://github.com/robolectric/robolectric/commit/892a5f2)
            ShadowBinder.setCallingUid(uid);
            return input.handleTransaction(code, data);
          } finally {
            ShadowBinder.setCallingUid(originalUid);
          }
        };
  }
}

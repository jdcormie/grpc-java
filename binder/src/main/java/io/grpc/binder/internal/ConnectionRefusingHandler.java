package io.grpc.binder.internal;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import io.grpc.binder.internal.LeakSafeOneWayBinder.TransactionHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A {@link TransactionHandler} that implements just enough of the gRPC/Binder wire protocol to tell
 * clients to go away.
 */
class ConnectionRefusingHandler implements TransactionHandler {
  private static final Logger logger = Logger.getLogger(LeakSafeOneWayBinder.class.getName());

  @Override
  public boolean handleTransaction(int code, Parcel parcel) {
    if (code != BinderTransport.SETUP_TRANSPORT) {
      return false;
    }
    if (parcel.dataAvail() <= 8) {
      return false;
    }
    int version = parcel.readInt();
    if (version != 1) {
      return false;
    }

    // Unfortunately the gRPC/binder wire protocol has no "magic" numbers to identify itself.

    IBinder peer = parcel.readStrongBinder(); // TODO:how big is a strong binder?

    try (ParcelHolder reply = ParcelHolder.obtain()) {
      // Send empty flags to avoid a memory leak linked to empty parcels (b/207778694).
      reply.get().writeInt(0);

      // TODO(jdcormie): Switch to OneWayBinderProxy. Safe for now though as we hold no locks.
      peer.transact(BinderTransport.SHUTDOWN_TRANSPORT, reply.get(), null, IBinder.FLAG_ONEWAY);
    } catch (RemoteException e) {
      logger.log(Level.WARNING, "Failed to send SHUTDOWN_TRANSPORT reply", e);
    }
    return true;
  }
}

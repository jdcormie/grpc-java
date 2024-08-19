package io.grpc.binder;

import static android.content.Intent.URI_INTENT_SCHEME;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.UserHandle;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.NameResolver.Args;
import io.grpc.NameResolver.ResolutionResult;
import io.grpc.NameResolverProvider;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.SynchronizationContext;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;

/**
 * A {@link NameResolverProvider} that resolves an "intent:" target URI to a set of
 * AndroidComponentAddress'es
 */
final class IntentNameResolverProvider extends NameResolverProvider {

  /** The URI scheme created by {@link Intent#URI_INTENT_SCHEME}. */
  public static final String ANDROID_INTENT_SCHEME = "intent";

  private final Context context;

  /**
   * Creates a new AndroidIntentNameResolverProvider.
   *
   * @param context an Android {@link Context} that will outlive this instance
   */
  public IntentNameResolverProvider(Context context) {
    this.context = context;
  }

  @Override
  public String getDefaultScheme() {
    return ANDROID_INTENT_SCHEME;
  }

  @Nullable
  @Override
  public NameResolver newNameResolver(URI targetUri, final Args args) {
    if (Objects.equals(targetUri.getScheme(), ANDROID_INTENT_SCHEME)) {
      return new Resolver(targetUri, args);
    } else {
      return null;
    }
  }

  @Override
  public boolean isAvailable() {
    return true;
  }

  @Override
  public int priority() {
    return 5; // default.
  }

  @Override
  public ImmutableSet<Class<? extends SocketAddress>> getProducedSocketAddressTypes() {
    return ImmutableSet.of(AndroidComponentAddress.class);
  }

  private ResolutionResult lookupAndroidComponentAddress(URI targetUri, Args args)
      throws StatusException {
    Intent targetIntent = parseUri(targetUri);
    List<ResolveInfo> resolveInfoList = lookupServices(targetIntent);

    // Model each matching android.app.Service as an individual gRPC server with a single address.
    List<EquivalentAddressGroup> servers = new ArrayList<>();
    for (ResolveInfo resolveInfo : resolveInfoList) {
      // This is needed because using targetIntent directly causes UnsafeIntentLaunchViolation.
      Intent copyTargetIntent = new Intent();
      copyTargetIntent.setAction(targetIntent.getAction());
      copyTargetIntent.setData(targetIntent.getData());
      // If Intent has no categories, getCategories() returns Null instead of an empty set of
      // categories.
      if (targetIntent.getCategories() != null) {
        for (String category : targetIntent.getCategories()) {
          copyTargetIntent.addCategory(category);
        }
      }
      copyTargetIntent.setComponent(
          new ComponentName(resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name));
      servers.add(
          new EquivalentAddressGroup(AndroidComponentAddress.forBindIntent(copyTargetIntent)));
    }

    return ResolutionResult.newBuilder()
        .setAddresses(ImmutableList.copyOf(servers))
        // pick_first is the default load balancing (LB) policy if the service config does not
        // specify one.
        .setServiceConfig(args.getServiceConfigParser().parseServiceConfig(ImmutableMap.of()))
        .build();
  }

  private List<ResolveInfo> lookupServices(Intent intent) throws StatusException {
    PackageManager packageManager = context.getPackageManager();
    List<ResolveInfo> intentServices = packageManager.queryIntentServices(intent, 0);
    if (intentServices.isEmpty()) {
      throw Status.UNIMPLEMENTED
          .withDescription("Service not found for intent " + intent)
          .asException();
    }
    return intentServices;
  }

  private Intent parseUri(URI targetUri) throws StatusException {
    try {
      return Intent.parseUri(targetUri.toString(), URI_INTENT_SCHEME);
    } catch (URISyntaxException uriSyntaxException) {
      throw Status.INVALID_ARGUMENT
          .withCause(uriSyntaxException)
          .withDescription("Failed to parse target URI " + targetUri + " as intent")
          .asException();
    }
  }

  /** A single name resolver. */
  private final class Resolver extends NameResolver {

    private final URI targetUri;
    private final Args args;
    private final Executor offloadExecutor;
    private final Executor sequentialExecutor;

    // Accessed only on `sequentialExecutor`
    @Nullable private PackageChangeReceiver receiver;

    // Accessed only on Args#getSynchronizationContext.
    private boolean shutdown;
    @Nullable private Listener2 listener;

    private Resolver(URI targetUri, Args args) {
      this.targetUri = targetUri;
      this.args = args;
      // This Executor is nominally optional but all grpc-java Channels provide it since 1.25.
      this.offloadExecutor =
          checkNotNull(args.getOffloadExecutor(), "NameResolver.Args.getOffloadExecutor()");
      this.sequentialExecutor = MoreExecutors.newSequentialExecutor(offloadExecutor);
    }

    @Override
    public void start(Listener2 listener) {
      checkState(this.listener == null, "Already started!");
      checkState(!shutdown, "Resolver is shutdown");
      this.listener = checkNotNull(listener);
      resolve();
    }

    @Override
    public void refresh() {
      checkState(listener != null, "Not started!");
      resolve();
    }

    private void resolve() {
      if (shutdown) {
        return;
      }
      // Capture non-final `listener` here while we're on args.getSynchronizationContext().
      Listener2 listener = checkNotNull(this.listener);
      Futures.addCallback(
          Futures.submit(
              () -> {
                maybeRegisterReceiver();
                return lookupAndroidComponentAddress(targetUri, args);
              },
              sequentialExecutor),
          new FutureCallback<ResolutionResult>() {
            @Override
            public void onSuccess(ResolutionResult result) {
              listener.onResult(result);
            }

            @Override
            public void onFailure(Throwable t) {
              listener.onError(Status.fromThrowable(t));
            }
          },
          sequentialExecutor);
    }

    @Override
    public String getServiceAuthority() {
      return "localhost";
    }

    @Override
    public void shutdown() {
      if (!shutdown) {
        shutdown = true;
        sequentialExecutor.execute(this::maybeUnregisterReceiver);
      }
    }

    final class PackageChangeReceiver extends BroadcastReceiver {
      @Override
      public void onReceive(Context context, Intent intent) {
        // Get off the main thread and into the correct SynchronizationContext.
        SynchronizationContext syncContext = args.getSynchronizationContext();
        syncContext.executeLater(Resolver.this::resolve);
        offloadExecutor.execute(syncContext::drain);
      }
    }

    @SuppressLint("UnprotectedReceiver") // All of these are protected system broadcasts.
    private void maybeRegisterReceiver() {
      if (receiver == null) {
        receiver = new PackageChangeReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addDataScheme("package");
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        context.registerReceiver(receiver, filter);
      }
    }

    private void maybeUnregisterReceiver() {
      if (receiver != null) {
        context.unregisterReceiver(receiver);
        receiver = null;
      }
    }
  }
}

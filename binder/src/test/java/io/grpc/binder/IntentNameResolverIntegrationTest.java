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

import static android.content.Intent.URI_INTENT_SCHEME;
import static com.google.common.truth.Truth.assertThat;
import static io.grpc.binder.internal.RobolectricUidPropagation.newUidPassingBinderDecorator;
import static io.grpc.binder.internal.RobolectricUidPropagation.newUidRestoringBinderDecorator;
import static org.junit.Assert.assertThrows;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.collect.ImmutableSet;
import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.NameResolverRegistry;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.binder.AndroidComponentAddress;
import io.grpc.binder.BinderChannelBuilder;
import io.grpc.binder.BinderServerBuilder;
import io.grpc.binder.IBinderReceiver;
import io.grpc.binder.SecurityPolicies;
import io.grpc.binder.ServerSecurityPolicy;
import io.grpc.binder.UntrustedSecurityPolicies;
import io.grpc.binder.internal.IntentNameResolverProvider;
import io.grpc.binder.internal.OneWayBinderProxy;
import io.grpc.binder.internal.LeakSafeOneWayBinder.TransactionHandler;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.LooperMode.Mode;
import org.robolectric.shadows.ShadowPackageManager;

@RunWith(RobolectricTestRunner.class)
@LooperMode(Mode.INSTRUMENTATION_TEST)
public final class IntentNameResolverIntegrationTest {

  private static final String TEST_PERMISSION = "android.permission.EXPAND_STATUS_BAR";
  private static final String SERVICE_CLASS_NAME = "io.grpc.binder.internal.DummyService";

  private static final String PERMITTED_PACKAGE = "com.example.permitted";
  private static final String RESTRICTED_PACKAGE = "com.example.restricted";

  private static final int PERMITTED_UID = 11111;
  private static final int RESTRICTED_UID = 22222;
  private static final int CLIENT_UID = 33333;

  private final Application appContext = ApplicationProvider.getApplicationContext();
  private final ShadowPackageManager shadowPackageManager = shadowOf(appContext.getPackageManager());

  private final java.util.List<Server> serversToCleanup = new java.util.ArrayList<>();
  private ManagedChannel channel;

  @Before
  public void setUp() throws Exception {
    // Register the provider.
    NameResolverRegistry.getDefaultRegistry()
        .register(new IntentNameResolverProvider());
    LoadBalancerRegistry.getDefaultRegistry()
        .register(new BinderPickFirstLoadBalancerProvider());
  }

  @After
  public void tearDown() throws Exception {
    if (channel != null) {
      channel.shutdownNow();
      channel.awaitTermination(5, TimeUnit.SECONDS);
    }
    for (Server server : serversToCleanup) {
      server.shutdownNow();
    }
  }

  private static class SimpleServiceImpl extends SimpleServiceGrpc.SimpleServiceImplBase {
    private final String packageName;

    SimpleServiceImpl(String packageName) {
      this.packageName = packageName;
    }

    @Override
    public void unaryRpc(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
      responseObserver.onNext(
          SimpleResponse.newBuilder().setResponseMessage(packageName).build());
      responseObserver.onCompleted();
    }
  }

  private ComponentName installService(String packageName, int uid, String... permissions) {
    ApplicationInfo appInfo = new ApplicationInfo();
    appInfo.packageName = packageName;
    appInfo.uid = uid;
    PackageInfo pkgInfo = new PackageInfo();
    pkgInfo.packageName = packageName;
    pkgInfo.applicationInfo = appInfo;
    if (permissions.length > 0) {
      pkgInfo.requestedPermissions = permissions;
      pkgInfo.requestedPermissionsFlags = new int[permissions.length];
      Arrays.fill(pkgInfo.requestedPermissionsFlags, PackageInfo.REQUESTED_PERMISSION_GRANTED);
    }
    shadowPackageManager.installPackage(pkgInfo);

    ComponentName componentName = new ComponentName(packageName, SERVICE_CLASS_NAME);
    ServiceInfo serviceInfo = shadowPackageManager.addServiceIfNotPresent(componentName);
    serviceInfo.applicationInfo = appInfo;
    return componentName;
  }

  private Server startServer(
      ComponentName componentName, Intent intentPrototype, int uid, SimpleServiceImpl serviceImpl) throws Exception {
    Intent bindIntent = new Intent(intentPrototype);
    bindIntent.setComponent(componentName);
    AndroidComponentAddress addr = AndroidComponentAddress.forBindIntent(bindIntent);

    IBinderReceiver receiver = new IBinderReceiver();
    BinderServerBuilder builder = BinderServerBuilder.forAddress(addr, receiver);
    builder.addService(serviceImpl);
    builder.securityPolicy(
        ServerSecurityPolicy.newBuilder()
            .servicePolicy(SimpleServiceGrpc.SERVICE_NAME, UntrustedSecurityPolicies.untrustedPublic())
            .build());

    builder.clientBinderDecorator(newUidPassingBinderDecorator(uid));
    builder.txnHandlerDecorator(newUidRestoringBinderDecorator());

    Server server = builder.build().start();

    shadowOf(appContext)
        .setComponentNameAndServiceForBindServiceForIntent(
            bindIntent, componentName, receiver.get());

    serversToCleanup.add(server);
    return server;
  }

  private BinderChannelBuilder newChannelBuilder(Intent targetIntent, String... requiredPermissions) {
    return newChannelBuilder(targetIntent, appContext, requiredPermissions);
  }

  private BinderChannelBuilder newChannelBuilder(
      Intent targetIntent, Context context, String... requiredPermissions) {
    String targetUri = targetIntent.toUri(URI_INTENT_SCHEME);

    BinderChannelBuilder builder = BinderChannelBuilder.forTarget(targetUri, context);
    builder.securityPolicy(
        io.grpc.binder.SecurityPolicies.hasPermissions(appContext.getPackageManager(), ImmutableSet.copyOf(requiredPermissions)));

    builder.binderDecorator(newUidPassingBinderDecorator(CLIENT_UID));
    builder.txnHandlerDecorator(newUidRestoringBinderDecorator());

    return builder;
  }

  @Test
  public void permittedServerDisabled_restrictedServerEnabled_connectionRefused() throws Exception {
    ComponentName permittedComponent = installService(PERMITTED_PACKAGE, PERMITTED_UID, TEST_PERMISSION);
    ComponentName restrictedComponent = installService(RESTRICTED_PACKAGE, RESTRICTED_UID);

    Intent targetIntent = new Intent("io.grpc.action.BIND").setData(Uri.parse("grpc:///unused"));

    Server restrictedServer = startServer(
        restrictedComponent,
        targetIntent,
        RESTRICTED_UID,
        new SimpleServiceImpl(RESTRICTED_PACKAGE));

    // Register filters (priority doesn't matter here, but we set them for consistency)
    IntentFilter permittedFilter = new IntentFilter(targetIntent.getAction());
    permittedFilter.addDataScheme(targetIntent.getData().getScheme());
    permittedFilter.setPriority(0);

    IntentFilter restrictedFilter = new IntentFilter(targetIntent.getAction());
    restrictedFilter.addDataScheme(targetIntent.getData().getScheme());
    restrictedFilter.setPriority(1);

    shadowPackageManager.addIntentFilterForService(permittedComponent, permittedFilter);
    shadowPackageManager.addIntentFilterForService(restrictedComponent, restrictedFilter);

    // Disable permitted server in PM
    appContext.getPackageManager().setComponentEnabledSetting(
        permittedComponent,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP);

    channel = newChannelBuilder(targetIntent, TEST_PERMISSION).build();
    SimpleServiceGrpc.SimpleServiceBlockingStub stub = SimpleServiceGrpc.newBlockingStub(channel);

    StatusRuntimeException exception =
        assertThrows(
            StatusRuntimeException.class,
            () -> stub.unaryRpc(SimpleRequest.getDefaultInstance()));

    assertThat(exception.getStatus().getCode()).isEqualTo(Status.PERMISSION_DENIED.getCode());
    assertThat(exception.getCause()).isInstanceOf(UntrustedServerException.class);
  }

  @Test
  public void bothPermittedAndRestrictedServersEnabled_connectsToPermittedServer()
      throws Exception {
    ComponentName permittedComponent = installService(PERMITTED_PACKAGE, PERMITTED_UID, TEST_PERMISSION);
    ComponentName restrictedComponent = installService(RESTRICTED_PACKAGE, RESTRICTED_UID);

    Intent targetIntent = new Intent("io.grpc.action.BIND").setData(Uri.parse("grpc:///unused"));

    Server permittedServer = startServer(
        permittedComponent,
        targetIntent,
        PERMITTED_UID,
        new SimpleServiceImpl(PERMITTED_PACKAGE));

    Server restrictedServer = startServer(
        restrictedComponent,
        targetIntent,
        RESTRICTED_UID,
        new SimpleServiceImpl(RESTRICTED_PACKAGE));

    // Register filters with priorities to make the test scenario explicit.
    // Restricted server has higher priority to force it to be resolved first.
    IntentFilter permittedFilter = new IntentFilter(targetIntent.getAction());
    permittedFilter.addDataScheme(targetIntent.getData().getScheme());
    permittedFilter.setPriority(0);

    IntentFilter restrictedFilter = new IntentFilter(targetIntent.getAction());
    restrictedFilter.addDataScheme(targetIntent.getData().getScheme());
    restrictedFilter.setPriority(1);

    shadowPackageManager.addIntentFilterForService(permittedComponent, permittedFilter);
    shadowPackageManager.addIntentFilterForService(restrictedComponent, restrictedFilter);

    channel = newChannelBuilder(targetIntent, TEST_PERMISSION).build();
    SimpleServiceGrpc.SimpleServiceBlockingStub stub = SimpleServiceGrpc.newBlockingStub(channel);

    SimpleResponse response = stub.unaryRpc(SimpleRequest.getDefaultInstance());
    assertThat(response.getResponseMessage()).isEqualTo(PERMITTED_PACKAGE);
  }

  @Test
  public void highestPriorityServerThrowsSecurityExceptionInBind_noFailoverToLowerPriorityServer()
      throws Exception {
    String firstPriorityPackage = "com.example.first";
    String secondPriorityPackage = "com.example.second";
    int firstPriorityUid = 44444;
    int secondPriorityUid = 55555;

    ComponentName secondPriorityComponent = installService(secondPriorityPackage, secondPriorityUid, TEST_PERMISSION);
    ComponentName firstPriorityComponent = installService(firstPriorityPackage, firstPriorityUid, TEST_PERMISSION);

    Intent targetIntent = new Intent("io.grpc.action.BIND").setData(Uri.parse("grpc:///unused"));

    Server secondPriorityServer = startServer(
        secondPriorityComponent,
        targetIntent,
        secondPriorityUid,
        new SimpleServiceImpl(secondPriorityPackage));

    Server firstPriorityServer = startServer(
        firstPriorityComponent,
        targetIntent,
        firstPriorityUid,
        new SimpleServiceImpl(firstPriorityPackage));

    // First priority has higher priority (1), Second priority has lower (0).
    IntentFilter secondPriorityFilter = new IntentFilter(targetIntent.getAction());
    secondPriorityFilter.addDataScheme(targetIntent.getData().getScheme());
    secondPriorityFilter.setPriority(0);

    IntentFilter firstPriorityFilter = new IntentFilter(targetIntent.getAction());
    firstPriorityFilter.addDataScheme(targetIntent.getData().getScheme());
    firstPriorityFilter.setPriority(1);

    shadowPackageManager.addIntentFilterForService(secondPriorityComponent, secondPriorityFilter);
    shadowPackageManager.addIntentFilterForService(firstPriorityComponent, firstPriorityFilter);

    // Create a context wrapper that throws SecurityException when binding to first priority server
    Context contextWrapper = new ContextWrapper(appContext) {
      @Override
      public boolean bindService(Intent service, ServiceConnection conn, int flags) {
        if (firstPriorityComponent.equals(service.getComponent())) {
          throw new SecurityException("Simulated permission denied for first priority");
        }
        return super.bindService(service, conn, flags);
      }
    };

    channel = newChannelBuilder(targetIntent, contextWrapper, TEST_PERMISSION).build();
    SimpleServiceGrpc.SimpleServiceBlockingStub stub = SimpleServiceGrpc.newBlockingStub(channel);

    // RPC should fail because first priority throws SecurityException and we don't failover
    StatusRuntimeException exception =
        assertThrows(
            StatusRuntimeException.class,
            () -> stub.unaryRpc(SimpleRequest.getDefaultInstance()));

    assertThat(exception.getStatus().getCode()).isEqualTo(Status.PERMISSION_DENIED.getCode());
    assertThat(exception.getStatus().getDescription()).contains("SecurityException from bindService");
    assertThat(exception.getCause().getClass()).isEqualTo(SecurityException.class);
    assertThat(exception.getCause()).isNotInstanceOf(UntrustedServerException.class);
    assertThat(exception.getCause().getMessage()).contains("Simulated permission denied for first priority");
  }
}

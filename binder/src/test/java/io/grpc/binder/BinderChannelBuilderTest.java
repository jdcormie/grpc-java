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

package io.grpc.binder;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.Intent;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import io.grpc.EquivalentAddressGroup;
import io.grpc.ManagedChannel;
import io.grpc.NameResolver;
import io.grpc.NameResolver.Args;
import io.grpc.NameResolverProvider;
import io.grpc.NameResolverRegistry;
import io.grpc.Status;
import io.grpc.StatusOr;
import io.grpc.SynchronizationContext;
import io.grpc.testing.GrpcCleanupRule;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class BinderChannelBuilderTest {
  private final Context appContext = ApplicationProvider.getApplicationContext();
  private final AndroidComponentAddress addr = AndroidComponentAddress.forContext(appContext);

  final FakeNameResolverProvider fakeNameResolverProvider = new FakeNameResolverProvider();
  @Rule public GrpcCleanupRule cleanupRule = new GrpcCleanupRule();

  @Test
  public void strictLifecycleManagementForbidsIdleTimers() {
    BinderChannelBuilder builder = BinderChannelBuilder.forAddress(addr, appContext);
    builder.strictLifecycleManagement();
    try {
      builder.idleTimeout(10, TimeUnit.SECONDS);
      fail();
    } catch (IllegalStateException ise) {
      // Expected.
    }
  }

  @Test
  public void populatesSecurityPolicyNameResolverArg() throws InterruptedException {
    NameResolverRegistry.getDefaultRegistry().register(fakeNameResolverProvider);
    SecurityPolicy ouchPolicy = SecurityPolicies.permissionDenied("ouch");
    ManagedChannel channel = BinderChannelBuilder.forTarget("fake:///asdf", appContext)
        .securityPolicy(ouchPolicy)
        .build();
    cleanupRule.register(channel);
    FakeNameResolver fakeNameResolver = fakeNameResolverProvider.provided.poll(5, TimeUnit.SECONDS);
    assertThat(fakeNameResolver).isNotNull();
    assertThat(fakeNameResolver.args.getArg(NameResolverArgs.SECURITY_POLICY))
        .isSameInstanceAs(ouchPolicy);
    assertThat(fakeNameResolver.args.getArg(NameResolverArgs.SOURCE_CONTEXT))
        .isSameInstanceAs(appContext);
  }

  static final class FakeNameResolverProvider extends NameResolverProvider {
    final BlockingDeque<FakeNameResolver> provided = new LinkedBlockingDeque<>();

    @Override
    public NameResolver newNameResolver(URI targetUri, Args args) {
      FakeNameResolver result = new FakeNameResolver(targetUri, args);
      provided.add(result);
      return result;
    }

    @Override
    public String getDefaultScheme() {
      return "fake";
    }

    @Override
    protected boolean isAvailable() {
      return true;
    }

    @Override
    protected int priority() {
      return 5;
    }

    public Collection<Class<? extends SocketAddress>> getProducedSocketAddressTypes() {
      return Collections.singleton(AndroidComponentAddress.class);
    }
  }

  static final class FakeNameResolver extends NameResolver {

    final URI targetUri;
    final SynchronizationContext syncContext;
    final Args args;
    Listener2 listener;

    FakeNameResolver(URI targetUri, Args args) {
      this.syncContext = args.getSynchronizationContext();
      this.targetUri = targetUri;
      this.args = args;
    }

    @Override
    public String getServiceAuthority() {
      return "fake";
    }

    @Override
    public void start(Listener2 listener) {
      this.listener = listener;
      syncContext.execute(
          () ->
              listener.onResult2(
                  ResolutionResult.newBuilder()
                      .setAddressesOrError(
                          StatusOr.fromValue(
                              Lists.newArrayList(
                                  new EquivalentAddressGroup(
                                      AndroidComponentAddress.newBuilder()
                                          .setBindIntent(new Intent())
                                          .build()))))
                      .build()));
    }

    @Override
    public void shutdown() {}
  }
}

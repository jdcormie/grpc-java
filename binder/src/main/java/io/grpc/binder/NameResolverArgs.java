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

import android.content.Context;
import android.os.UserHandle;
import io.grpc.ExperimentalApi;
import io.grpc.NameResolver;

/** Constant parts of the gRPC binder transport public API. */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/8022")
public final class NameResolverArgs {
  private NameResolverArgs() {}

  /**
   * Identifies the {@link SecurityPolicy} the Channel will use to authorize the server.
   */
  public static final NameResolver.Args.Key<SecurityPolicy> SECURITY_POLICY =
      NameResolver.Args.Key.create("security-policy");

  /**
   * Identifies the {@link UserHandle} where addresses should be resolved.
   *
   * <p>Can be a specific user or {@link UserHandle#CURRENT} to address the currently active user of
   * the device (which can change)
   */
  public static final NameResolver.Args.Key<UserHandle> TARGET_USER =
      NameResolver.Args.Key.create("target-user");

  public static final NameResolver.Args.Key<Context> SOURCE_CONTEXT =
      NameResolver.Args.Key.create("source-context");
}

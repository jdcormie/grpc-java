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

import io.grpc.ExperimentalApi;

/**
 * Exception thrown (as a Status cause) when a connection is rejected because the server is not
 * trusted by the client's {@link SecurityPolicy}.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/00000")
public final class UntrustedServerException extends SecurityException {
  private static final long serialVersionUID = 1L;

  public UntrustedServerException(String message) {
    super(message);
  }

  public UntrustedServerException(String message, Throwable cause) {
    super(message, cause);
  }
}

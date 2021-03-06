/*
 * Copyright (c) 2019 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.client.core.msg.query;

import com.couchbase.client.core.msg.chunk.ChunkTrailer;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

public class QueryChunkTrailer implements ChunkTrailer {

  private final String status;
  private final Optional<byte[]> metrics;
  private final Optional<byte[]> warnings;
  private final Optional<byte[]> errors;
  private final Optional<byte[]> profile;

  public QueryChunkTrailer(String status, Optional<byte[]> metrics, Optional<byte[]> warnings,
                           Optional<byte[]> errors, Optional<byte[]> profile) {
    this.status = status;
    this.metrics = metrics;
    this.warnings = warnings;
    this.errors = errors;
    this.profile = profile;
  }

  public String status() {
    return status;
  }

  public Optional<byte[]> metrics() {
    return metrics;
  }

  public Optional<byte[]> warnings() {
    return warnings;
  }

  public Optional<byte[]> errors() {
    return errors;
  }

  public Optional<byte[]> profile() {
    return profile;
  }

  @Override
  public String toString() {
    return "QueryChunkTrailer{" +
      "status='" + status + '\'' +
      ", metrics=" + metrics.map(v -> new String(v, StandardCharsets.UTF_8)) +
      ", warnings=" + warnings.map(v -> new String(v, StandardCharsets.UTF_8)) +
      ", errors=" + errors.map(v -> new String(v, StandardCharsets.UTF_8)) +
      ", profile=" + profile.map(v -> new String(v, StandardCharsets.UTF_8)) +
      '}';
  }
}

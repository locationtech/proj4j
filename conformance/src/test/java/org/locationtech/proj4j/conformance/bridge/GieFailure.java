/*
 * Copyright 2026 The Proj4J Contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.locationtech.proj4j.conformance.bridge;

/**
 * A classified reason the bridge produced no coordinate.
 *
 * <p>Implementations are immutable and safe to share. Obtain instances from
 * {@link GieFailures}.
 */
public interface GieFailure {

    /** The classification. Never {@code null}. */
    GieFailureKind kind();

    /**
     * A human-readable explanation, intended to appear in a conformance report
     * verbatim. Never {@code null}, never empty, and should name the offending
     * token or value so a reader can act on it without re-running anything.
     */
    String message();

    /**
     * The originating exception, or {@code null} when the failure was decided by
     * the bridge's own classification rather than caught from proj4j.
     */
    Throwable cause();
}

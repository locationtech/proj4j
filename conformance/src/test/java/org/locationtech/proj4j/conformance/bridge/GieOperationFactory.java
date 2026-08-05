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
 * Turns a gie corpus operation definition into something proj4j can execute — or
 * into a precise statement of why it cannot.
 *
 * <p><b>Never throws.</b> Both methods always return a {@link GieOperation}; an
 * unusable one carries a {@link GieFailure}. A runner that has to wrap these
 * calls in {@code try/catch} would be turning classified information back into
 * noise.
 *
 * @see Proj4jGieOperationFactory
 */
public interface GieOperationFactory {

    /**
     * Build an operation from the arguments of a gie {@code operation} command.
     *
     * @param args the {@code operation} line's arguments, already through
     *             {@code pj_chomp}/{@code pj_shrink} (so {@code +} prefixes and
     *             redundant whitespace are gone) — though a raw
     *             {@code "+proj=merc +ellps=GRS80"} is accepted too. May be
     *             {@code null} or empty, which yields an unusable operation.
     * @return never {@code null}.
     */
    GieOperation create(String args);

    /**
     * Build an operation from a completed gie {@code crs_src} + {@code crs_dst}
     * pair.
     *
     * @param sourceCrs the {@code crs_src} argument: an authority code such as
     *                  {@code "EPSG:4258"}, or a full proj string.
     * @param targetCrs the {@code crs_dst} argument, same forms.
     * @return never {@code null}.
     */
    GieOperation createCrsToCrs(String sourceCrs, String targetCrs);
}

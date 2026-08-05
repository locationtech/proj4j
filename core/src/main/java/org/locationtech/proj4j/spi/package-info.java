/*******************************************************************************
 * Copyright 2026 Proj4J contributors
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
 *******************************************************************************/

/**
 * The optional-artifact seams. Interfaces and immutable value types only — <strong>no
 * implementation</strong>, no data, and nothing outside {@code java.*} and
 * {@code org.locationtech.proj4j.*}.
 * <p>
 * {@link org.locationtech.proj4j.spi.ProjDatabase} is the authority-database seam, answered by the
 * optional {@code proj4j-db} artifact. Core compiles, runs and passes its tests with no implementation
 * present; the facade then reports the absence honestly rather than substituting a plausible number.
 * <p>
 * Three properties hold across this package, and each one is here because its absence has cost
 * somebody real money:
 * <ul>
 *   <li><strong>Zero runtime dependencies.</strong> Nothing here references a driver, a parser, a
 *       logging framework or {@code org.opengis.*}. A build-time scan of core's constant pool for
 *       {@code org/opengis/} must stay at zero, because a consumer's whole reason for adopting this API
 *       is to delete Apache SIS and the {@code catch (LinkageError)} over a duplicate
 *       {@code org.opengis.util.CodeList} that kills their Spark executors.</li>
 *   <li><strong>Determinism.</strong> Every collection returned by an SPI method is totally ordered by
 *       a rule stated in its javadoc, and that rule is a function of the data alone — never of hash
 *       iteration order, {@code ServiceLoader} discovery order, or classpath layout. Two executors
 *       given the same inputs must produce the same bytes.</li>
 *   <li><strong>No ambient state.</strong> No environment variables, no system properties, no process
 *       working directory, no network. Files are located only through
 *       {@link org.locationtech.proj4j.resource.ResourceResolver}.</li>
 * </ul>
 *
 * @see org.locationtech.proj4j.spi.ProjDatabase
 * @see org.locationtech.proj4j.spi.ProjDatabaseProvider
 */
package org.locationtech.proj4j.spi;

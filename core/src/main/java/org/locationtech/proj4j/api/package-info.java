/*
 * Copyright 2026, PROJ4J contributors
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

/**
 * The public facade: {@link org.locationtech.proj4j.api.Proj} to create things,
 * {@link org.locationtech.proj4j.api.Crs} and {@link org.locationtech.proj4j.api.CrsOperation} to
 * use them, {@link org.locationtech.proj4j.api.ProjContext} to decide how.
 *
 * <h2>What this package is for</h2>
 *
 * <p>Three properties, each of which the 1.x API cannot provide without breaking somebody:
 *
 * <ol>
 * <li><b>It fails closed.</b> Every method that produces coordinates either returns all-finite
 * ordinates or throws {@link org.locationtech.proj4j.CrsTransformException} with a non-null
 * {@link org.locationtech.proj4j.Proj4jException#cause()}. No sentinels: not {@code NaN}, not the
 * input unchanged, not the false easting. And the checks that can be made once are made once, at
 * planning time, rather than on row 4,000,000.</li>
 * <li><b>It is introspectable.</b> {@link org.locationtech.proj4j.api.Proj#describe()},
 * {@link org.locationtech.proj4j.api.Crs#describe()} and
 * {@link org.locationtech.proj4j.api.CrsOperation#describe()} state what this deployment can do,
 * what it cannot, and &mdash; the part that matters &mdash; which grids it was asked for and could
 * not find.</li>
 * <li><b>It needs nothing.</b> Zero runtime dependencies. WKT1 in both OGC and ESRI dialects, WKT2
 * in both revisions, and PROJJSON are read and written here, in plain JDK code, so a consumer can
 * delete Apache SIS and the {@code catch (LinkageError)} they wrapped it in.</li>
 * </ol>
 *
 * <h2>Why it is a separate package, and why it is in this artifact</h2>
 *
 * <p><b>A separate package</b>, because {@code Crs} beside {@code CoordinateReferenceSystem} and
 * {@code CrsOperation} beside {@code CoordinateTransform} in one package would make
 * {@code import org.locationtech.proj4j.*} ambiguous for every existing caller. Nothing about the
 * root package changes.
 *
 * <p><b>In this artifact</b>, not a satellite one, because the entire reason a consumer wanted WKT2
 * support was to remove Apache SIS from a Spark classpath. Two incompatible copies of
 * {@code org.opengis.util.CodeList} &mdash; {@code geoapi-3.0.2} has {@code names()},
 * {@code gt-opengis-29.6} does not &mdash; make SIS's WKT parser throw {@code NoSuchMethodError},
 * an {@code Error} rather than an {@code Exception}, which passes local tests and kills executors.
 * Relocating the dependency would not have helped; removing the need for it does. Core therefore
 * contains <b>zero</b> references to {@code org.opengis.*}, and that is enforced mechanically by
 * a test which scans the compiled classes for the string, not by a comment.
 *
 * <h2>The 1.x API is frozen, not deprecated and not re-routed</h2>
 *
 * <p>{@link org.locationtech.proj4j.CRSFactory},
 * {@link org.locationtech.proj4j.CoordinateTransformFactory},
 * {@link org.locationtech.proj4j.CoordinateTransform},
 * {@link org.locationtech.proj4j.CoordinateReferenceSystem} and
 * {@link org.locationtech.proj4j.ProjCoordinate} behave exactly as they did, and nothing in this
 * package changes them. {@link org.locationtech.proj4j.api.LegacyAdapters} is the opt-in bridge, one
 * line at the call site, for a caller who wants the new behaviour behind the old interface.
 *
 * <p>Java 8 has no {@code @Deprecated(forRemoval=)}, so the promise has to be written down: the 1.x
 * classes <b>will not be removed</b>. Nobody should plan a migration they do not need.
 *
 * <h2>Thread safety, stated once</h2>
 *
 * <p>Immutable and shareable across any number of threads: {@link org.locationtech.proj4j.api.Proj}
 * (static and stateless), {@link org.locationtech.proj4j.api.Crs},
 * {@link org.locationtech.proj4j.api.CrsOperation},
 * {@link org.locationtech.proj4j.api.ProjContext},
 * {@link org.locationtech.proj4j.api.AreaOfUse}, {@link org.locationtech.proj4j.api.Accuracy},
 * {@link org.locationtech.proj4j.api.GridInfo}, {@link org.locationtech.proj4j.api.DatabaseInfo},
 * {@link org.locationtech.proj4j.api.ProjectionInfo}, and every enum.
 *
 * <p>Thread-confined: {@link org.locationtech.proj4j.api.ProjContext.Builder}, and
 * {@link org.locationtech.proj4j.ProjCoordinate} as it always was.
 *
 * <h2>What this package will not claim</h2>
 *
 * <p>Proj4J ships no CRS database. Rather than approximate what a database would have told it, this
 * package returns {@link java.util.Optional#empty()} and says why:
 * {@link org.locationtech.proj4j.api.Proj#databaseVersion()},
 * {@link org.locationtech.proj4j.api.Crs#areaOfUse()} for a CRS that did not declare one, and
 * {@link org.locationtech.proj4j.api.CrsOperation#accuracy()}. See
 * {@link org.locationtech.proj4j.api.DatabaseInfo} for the full inventory of what a PROJ.4
 * {@code +init=} dictionary can and cannot answer.
 *
 * @since 1.5.0
 */
package org.locationtech.proj4j.api;

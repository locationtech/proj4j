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
 * The original Proj4J API: {@link org.locationtech.proj4j.CRSFactory} to create coordinate
 * reference systems, {@link org.locationtech.proj4j.CoordinateTransformFactory} to create
 * transformations between them, and {@link org.locationtech.proj4j.ProjCoordinate} to carry the
 * coordinates through.
 *
 * <h2>The shape of a transformation</h2>
 *
 * <p>Four objects, in this order:
 *
 * <ol>
 * <li>{@link org.locationtech.proj4j.CRSFactory#createFromName(java.lang.String)} or
 * {@link org.locationtech.proj4j.CRSFactory#createFromParameters(java.lang.String,
 * java.lang.String)} produces a {@link org.locationtech.proj4j.CoordinateReferenceSystem} — an
 * ellipsoid, a datum, a projection method and a unit.</li>
 * <li>{@link org.locationtech.proj4j.CoordinateTransformFactory#createTransform(
 * org.locationtech.proj4j.CoordinateReferenceSystem,
 * org.locationtech.proj4j.CoordinateReferenceSystem)} produces a
 * {@link org.locationtech.proj4j.CoordinateTransform} for an ordered pair of CRSs.</li>
 * <li>{@link org.locationtech.proj4j.CoordinateTransform#transform(
 * org.locationtech.proj4j.ProjCoordinate, org.locationtech.proj4j.ProjCoordinate)} converts one
 * coordinate, writing into a destination you supply and returning it.</li>
 * <li>Anything that goes wrong throws {@link org.locationtech.proj4j.Proj4jException}, whose
 * {@link org.locationtech.proj4j.Proj4jException#cause()} is a machine-readable
 * {@link org.locationtech.proj4j.ErrorCause}.</li>
 * </ol>
 *
 * <p>{@link org.locationtech.proj4j.Registry} holds the built-in datums and ellipsoids that
 * {@code +datum=} and {@code +ellps=} resolve against.
 *
 * <h2>Thread safety</h2>
 *
 * <p>{@link org.locationtech.proj4j.CoordinateTransform} instances are immutable once built and can
 * be shared across threads. {@link org.locationtech.proj4j.ProjCoordinate} is mutable and is
 * written to by every transform, so each thread needs its own source and destination pair. Build
 * the transform once, per CRS pair, and reuse it; constructing one resolves everything that is
 * constant for its lifetime.
 *
 * <h2>Relationship to {@code org.locationtech.proj4j.api}</h2>
 *
 * <p>{@link org.locationtech.proj4j.api} is a newer facade over the same machinery, added in 1.5.0.
 * It differs in three ways that cannot be retrofitted here without changing behaviour callers may
 * rely on: it fails closed rather than returning a plausible coordinate on failure, it can be asked
 * what a given deployment supports, and it reads and writes WKT1, WKT2 and PROJJSON.
 *
 * <p>The classes in this package are <b>frozen, not deprecated</b>, and will not be removed. Java 8
 * has no {@code @Deprecated(forRemoval=)} to say so in code, so it is said here.
 * {@code api.LegacyAdapters} is the opt-in bridge for a caller who wants the newer behaviour behind
 * these interfaces.
 */
package org.locationtech.proj4j;

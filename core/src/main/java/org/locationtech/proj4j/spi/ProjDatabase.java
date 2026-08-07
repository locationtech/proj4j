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
package org.locationtech.proj4j.spi;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

/**
 * Read-only access to a PROJ authority database. The seam between {@code proj4j} core and the optional
 * {@code proj4j-db} artifact.
 *
 * <h2>Why this is an interface in core and not a class</h2>
 * Core has <strong>zero runtime dependencies</strong> and must keep them. A JDBC SQLite driver needs a
 * native library; extracting a database to a temporary file needs a filesystem we do not control. Both
 * are worse than the problem they solve for a consumer whose whole reason for being here is to delete
 * Apache SIS and the {@code catch (LinkageError)} that kills their Spark executors. So core declares
 * the questions, and an optional artifact answers them from a build-time-transcoded, read-only index.
 * With no implementation on the classpath, the facade reports honestly — {@code databaseVersion()}
 * empty, {@code areaOfUse()} empty — and never silently degrades to a plausible number.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li><strong>Absence is null, not an exception.</strong> Every single-object lookup returns
 *       {@code null} for "no such object", matching
 *       {@code org.locationtech.proj4j.resource.ResourceResolver#resolve}. A code that does not exist
 *       is data, not a programming error; it becomes {@code UNKNOWN_CRS} one layer up, where the
 *       caller's string is still in hand.</li>
 *   <li><strong>Every list is unmodifiable, non-null, and totally ordered.</strong> Never null for
 *       "none". The order is specified per method and is a pure function of the database bytes — never
 *       of hash iteration, insertion order, or which index a row was found through. Determinism is not
 *       a nicety here: identical inputs must produce bitwise-identical outputs across Spark
 *       executors.</li>
 *   <li><strong>Values are returned in the authority's own units, unconverted</strong>, each with its
 *       unit reference. See {@link DbUnit}.</li>
 *   <li><strong>Thread-safe.</strong> Every method must be safe for unsynchronised concurrent
 *       invocation, and must not hold a lock across I/O. Returned objects are immutable.</li>
 *   <li><strong>No network access, ever</strong>, and no consultation of environment variables or the
 *       process working directory. An implementation obtains its bytes through
 *       {@code org.locationtech.proj4j.resource.ResourceResolver}.</li>
 *   <li>{@code authName} and {@code code} comparisons are <strong>exact and case-sensitive</strong>.
 *       Authority names are upper-case upstream; normalising them here would make
 *       {@code "epsg"} work in some deployments and not others.</li>
 * </ul>
 *
 * <h2>What this does not do</h2>
 * Operation <em>selection</em> — ranking candidates, applying {@code BallparkPolicy}, deciding what to
 * throw — is the facade's job, not the database's. This interface reports what the authority published;
 * it has no policy. That split is deliberate: policy is testable without 10 MB of data, and the data is
 * testable without the policy.
 */
public interface ProjDatabase extends Closeable {

    /**
     * A short, stable identifier for this database, suitable for a log line and for
     * {@code describeResolution()}, e.g.
     * {@code "pjdx:classpath:/proj4j-data/db/proj4j-db.pjdx"}. Must name where the bytes came from,
     * because that is the single most useful datum when two executors disagree about a coordinate.
     */
    String name();

    /**
     * The database's own {@code metadata} table, verbatim: {@code PROJ.VERSION}, {@code EPSG.VERSION},
     * {@code EPSG.DATE}, {@code ESRI.VERSION}, {@code IGNF.VERSION}, {@code IAU.VERSION},
     * {@code NKG.VERSION}, {@code PROJ_DATA.VERSION},
     * {@code DATABASE.LAYOUT.VERSION.MAJOR}/{@code .MINOR}.
     * <p>
     * Unmodifiable and iterating in key order. This is one of the two independent sources
     * {@code DatabaseInfo} cross-checks; the other is the build-stamped sidecar shipped alongside the
     * index. They must agree.
     */
    Map<String, String> metadata();

    /**
     * Every authority that owns at least one object, e.g.
     * {@code [EPSG, ESRI, IAU_2015, IGNF, NKG, NRCAN, OGC, PROJ]}. Unmodifiable, sorted.
     */
    SortedSet<String> authorities();

    // ---------------------------------------------------------------- CRSs

    /**
     * Looks up a CRS across all five CRS tables in one search.
     * <p>
     * {@code (authName, code)} is unique across those tables — verified, zero collisions in the shipped
     * database — so no disambiguation is needed and none is offered.
     *
     * @return the CRS, or {@code null}. Deprecated CRSs <em>are</em> returned; see
     *         {@link DbCrs#deprecated()} and {@link #replacementsFor}.
     */
    DbCrs crs(String authName, String code);

    /**
     * Every CRS of the given authority, sorted by {@link DbObjectRef} order. Unmodifiable.
     * @param authName the authority, or {@code null} for all authorities
     */
    List<DbObjectRef> crsCodes(String authName);

    /** @return the coordinate system with its axes in authority order, or {@code null}. */
    DbCoordinateSystem coordinateSystem(String authName, String code);

    // ------------------------------------------------------- datums and their parts

    /**
     * @param type must be {@link DbObjectType#GEODETIC_DATUM} or {@link DbObjectType#VERTICAL_DATUM}
     * @return the datum, or {@code null}
     * @throws IllegalArgumentException if {@code type} is neither datum type
     */
    DbDatum datum(DbObjectType type, String authName, String code);

    /** @return the ellipsoid, or {@code null}. */
    DbEllipsoid ellipsoid(String authName, String code);

    /** @return the prime meridian, or {@code null}. */
    DbPrimeMeridian primeMeridian(String authName, String code);

    /** @return the unit of measure, or {@code null}. */
    DbUnit unit(String authName, String code);

    /** @return the celestial body, or {@code null}. */
    DbCelestialBody celestialBody(String authName, String code);

    /**
     * Every geodetic or vertical CRS built on a given datum, sorted by {@link DbObjectRef} order.
     * Unmodifiable.
     * <p>
     * This is the pivot query for operation search: the authority publishes transformations between
     * <em>CRSs</em>, so finding the operations that relate two datums means finding the CRSs on each
     * datum first. It is also how a bare {@code +datum=OSGB36} is resolved — and that is not a corner
     * case, because 1,962 lines of the shipped legacy dictionaries carry a {@code datum=} and PROJ 9.x
     * resolves every one of them through this database rather than through {@code datums.cpp}.
     */
    List<DbObjectRef> crsUsingDatum(DbObjectType datumType, String datumAuthName, String datumCode);

    // ---------------------------------------------------------------- conversions

    /**
     * The map projection of a projected CRS.
     *
     * @return the conversion, or {@code null}
     */
    DbConversion conversion(String authName, String code);

    // ---------------------------------------------------------------- operations

    /**
     * @return the operation, whichever of the four operation tables it lives in, or {@code null}
     */
    DbOperation operation(String authName, String code);

    /**
     * Operations the authority published <strong>in exactly this direction</strong>: rows whose
     * {@code source_crs} is {@code (srcAuth, srcCode)} and whose {@code target_crs} is
     * {@code (tgtAuth, tgtCode)}.
     * <p>
     * <strong>Call it twice to get both directions.</strong> Swapping the arguments yields the
     * operations that must be executed inverted, and the shipped index supports both orders at the
     * same cost. This SPI deliberately does not merge them: whether a candidate is being used forwards
     * or backwards changes its sign, its grid direction and whether an inverse exists at all, and that
     * is exactly the fact a caller must not lose. Merging would hand back a list in which the
     * distinction is implicit.
     * <p>
     * Sorted by {@link DbObjectRef} order, i.e. by {@code (kind, authName, code)} — <em>not</em> by
     * accuracy, and not by anything else policy-flavoured. Ranking is the facade's job. Includes
     * deprecated operations; filter on {@link DbOperation#deprecated()}. Unmodifiable.
     */
    List<DbOperation> operationsBetween(String srcAuthName, String srcCode,
                                        String tgtAuthName, String tgtCode);

    /**
     * Every operation whose {@code source_crs} is the given CRS, as references. Sorted by
     * {@link DbObjectRef} order, unmodifiable.
     * <p>
     * References rather than whole operations because this is the fan-out step of a pivot search and is
     * expected to be filtered hard before anything is materialised.
     */
    List<DbObjectRef> operationsWithSourceCrs(String authName, String code);

    /**
     * Every operation whose {@code target_crs} is the given CRS. See
     * {@link #operationsWithSourceCrs}.
     */
    List<DbObjectRef> operationsWithTargetCrs(String authName, String code);

    // ---------------------------------------------------------------- area of use

    /**
     * The extents declared for an object, through the {@code usage} table.
     * <p>
     * An object may have several: {@code EPSG:4326} has one, but transformations routinely have more
     * than one usage row. Sorted by {@link DbObjectRef} order of the extent, so the sequence is stable;
     * a caller that wants smallest-first should sort by {@link DbExtent#rankingArea()} and break ties
     * on the extent code. Unmodifiable, empty if the object declares none.
     *
     * @param object any object type that appears in {@code usage} — the CRS types, the datum types, and
     *               {@code conversion} plus the four operation types
     */
    List<DbExtent> extentsFor(DbObjectRef object);

    /** @return the extent, or {@code null}. */
    DbExtent extent(String authName, String code);

    // ---------------------------------------------------------------- names

    /**
     * Alternative names for an object, from {@code alias_name}. Sorted, unmodifiable.
     */
    List<String> aliases(DbObjectRef object);

    /**
     * CRSs whose name or alias matches {@code name}.
     * <p>
     * Matching ignores case, and ignores runs of whitespace, {@code _} and {@code -} — so
     * {@code "WGS 84 / UTM zone 31N"}, {@code "WGS_84___UTM_zone_31N"} and
     * {@code "wgs84/utmzone31n"} all find the same CRS. It does <strong>not</strong> do fuzzy or
     * substring matching: a caller that mistypes a name gets an empty list, not a nearby CRS.
     * <p>
     * Sorted by {@link DbObjectRef} order, so a name shared by several CRSs — and there are
     * duplicates upstream — yields a stable list rather than an arbitrary first hit. Unmodifiable.
     */
    List<DbObjectRef> findCrsByName(String name);

    // ------------------------------------------------- deprecation and supersession

    /**
     * {@code supersession} rows in which {@code object} is the superseded one. Sorted by the
     * replacement's {@link DbObjectRef} order, unmodifiable.
     */
    List<DbSupersession> supersededBy(DbObjectRef object);

    /**
     * {@code deprecation} rows for a deprecated object: what the authority says to use instead. Sorted
     * by {@link DbObjectRef} order, unmodifiable, and empty for an object that is not deprecated.
     * <p>
     * Kept separate from {@link #supersededBy} because the two mean different things — see
     * {@link DbSupersession}.
     */
    List<DbObjectRef> replacementsFor(DbObjectRef object);

    // ---------------------------------------------------------------- grids

    /**
     * @param originalGridName the name as an operation spells it, from
     *                         {@link DbOperation#gridNames()}
     * @return the mapping to the file PROJ would read, or {@code null} if the authority name is already
     *         the PROJ name or is simply unknown
     */
    DbGridAlternative gridAlternative(String originalGridName);

    /**
     * Every {@code grid_alternatives} row, sorted by {@link DbGridAlternative#originalGridName()}.
     * Unmodifiable. Used to cross-join the resolver chain's enumerable resolvers into
     * {@code Proj.availableGrids()}, so that "cannot enumerate" is never reported as "nothing
     * installed".
     */
    List<DbGridAlternative> gridAlternatives();

    /**
     * Releases any resources held. Implementations must be idempotent, and must tolerate being called
     * while other threads are still reading — a database handed to a {@code ProjContext} outlives the
     * caller's stack frame.
     */
    @Override
    void close() throws IOException;
}

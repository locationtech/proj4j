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
package org.locationtech.proj4j.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.datum.Datum;
import org.locationtech.proj4j.datum.Grid;
import org.locationtech.proj4j.io.projjson.ProjJsonWriter;
import org.locationtech.proj4j.io.wkt.AxisOrderPolicy;
import org.locationtech.proj4j.io.wkt.CrsDefinition;
import org.locationtech.proj4j.io.wkt.CrsDefinitions;
import org.locationtech.proj4j.io.wkt.Identifier;
import org.locationtech.proj4j.io.wkt.WktDialect;
import org.locationtech.proj4j.io.wkt.WktWriter;
import org.locationtech.proj4j.proj.GeocentProjection;
import org.locationtech.proj4j.proj.Projection;
import org.locationtech.proj4j.units.Unit;
import org.locationtech.proj4j.units.Units;

/**
 * A coordinate reference system, with the introspection a caller needs to decide whether to trust
 * it.
 *
 * <p>Built by {@link Proj#createCrs(String)}, which accepts an {@code authority:code} name, a
 * PROJ.4 parameter string, WKT in any of four dialects, or PROJJSON &mdash; all of it parsed inside
 * this artifact, with no GeoAPI, no Apache SIS, and no other dependency of any kind.
 *
 * <pre>{@code
 * Crs crs = Proj.createCrs("EPSG:4267");
 * if (!crs.missingGrids().isEmpty()) {
 *     log.warn(crs.describe());          // names each unreachable grid and what it means
 * }
 * }</pre>
 *
 * <h2>Immutable, shareable, and honest about what it does not know</h2>
 *
 * <p>Every field is final and every returned collection is unmodifiable, so one instance is safe to
 * share across any number of threads &mdash; which is the property that matters when a cached CRS is
 * reached by every executor thread in a Spark task. Grid reachability is resolved once, lazily, and
 * memoised; the memo is idempotent, so the race is benign.
 *
 * <p>Several accessors return {@link Optional#empty()} and will keep doing so until Proj4J ships a
 * CRS database: {@link #areaOfUse()} for anything but a document that declared a bounding box, and
 * every authority-derived fact behind it. They are empty rather than approximated. See
 * {@link DatabaseInfo}.
 *
 * <h2>Axis order</h2>
 *
 * <p>{@link #axisOrder()} reports what this CRS actually does, in PROJ's own {@code +axis=}
 * three-letter encoding: {@code "enu"} is longitude-then-latitude (or easting-then-northing), and
 * {@code "neu"} is latitude-first. The default is {@code "enu"} for everything, exactly as in
 * proj4j 1.4.3. {@link #isAxisOrderAuthoritative()} says whether that came from the CRS definition
 * or was inferred, and {@link #axisOrderNote()} says which rule was used. Read
 * {@link AxisOrderPolicy} before changing the policy: the difference is invisible near
 * (0,&nbsp;0), which is where fixtures live.
 *
 * @see Proj
 * @see CrsOperation
 * @see ProjContext
 * @since 1.5.0
 */
public final class Crs {

    /**
     * How a {@link Crs} was specified. Retained because it changes what can be known about the
     * CRS: a WKT document may declare axes and a bounding box, an {@code authority:code} name
     * cannot.
     */
    public enum Source {
        /** {@code "EPSG:4326"} &mdash; resolved against the legacy PROJ.4 {@code +init=} dictionary. */
        AUTHORITY_CODE,
        /** {@code "+proj=utm +zone=33 +datum=WGS84"}. */
        PROJ_STRING,
        /** WKT1 (OGC or ESRI) or WKT2 (2015 or 2019). */
        WKT,
        /** A PROJJSON document. */
        PROJJSON,
        /** Adapted from an existing {@link CoordinateReferenceSystem} via {@link LegacyAdapters}. */
        LEGACY_OBJECT
    }

    /**
     * The grid files PROJ's legacy datum table declares for a {@code +datum=} name, transcribed
     * from {@code 9.8.1:src/datums.cpp} &mdash; the same source
     * {@code org.locationtech.proj4j.datum.Datum}'s static initialiser used.
     *
     * <p>Only two of PROJ's built-in datums declare grids at all, and every token in both is
     * {@code @}-optional, which is precisely why they need reporting: a deployment with none of
     * these files present performs a NAD27 transformation that applies no shift and says nothing.
     */
    private static final Map<String, String> DATUM_GRID_DECLARATIONS;

    static {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("NAD27", "@conus,@alaska,@ntv2_0.gsb,@ntv1_can.dat");
        m.put("potsdam", "@BETA2007.gsb");
        DATUM_GRID_DECLARATIONS = Collections.unmodifiableMap(m);
    }

    /**
     * The {@code +datum=} names that declare grid files, and the token lists they declare.
     * Package-private, for {@link Proj#declaredGrids()}.
     *
     * @return an unmodifiable map; never null
     */
    static Map<String, String> datumGridDeclarations() {
        return DATUM_GRID_DECLARATIONS;
    }

    private final String definitionText;
    private final Source source;
    private final ProjContext context;
    private final CoordinateReferenceSystem legacy;
    private final String[] params;
    private final List<String> identifiers;
    private final AreaOfUse areaOfUse;
    private final WktDialect sourceDialect;
    private final boolean axisOrderAuthoritative;
    private final String axisOrderNote;

    /** The authority database row this CRS was built from, or null. */
    private final org.locationtech.proj4j.spi.DbCrs authorityRecord;

    /** declaring parameter -&gt; the {@code +nadgrids=}-style token list it implies. */
    private final Map<String, String> gridDeclarations;

    /** Lazily resolved, then never replaced. See the class javadoc on the benign race. */
    private volatile List<GridInfo> gridsMemo;

    Crs(String definitionText, Source source, ProjContext context,
        CoordinateReferenceSystem legacy, CrsDefinition definition,
        boolean axisOrderAuthoritative, String axisOrderNote) {
        this.definitionText = definitionText;
        this.source = source;
        this.context = context;
        this.legacy = legacy;
        String[] p = legacy.getParameters();
        this.params = p == null ? null : p.clone();
        this.axisOrderAuthoritative = axisOrderAuthoritative;
        this.axisOrderNote = axisOrderNote;
        this.identifiers = identifiersOf(definition, source, definitionText);
        this.areaOfUse = definition == null ? null
                : AreaOfUse.fromWktBbox(definition.getBoundingBox(),
                        definition.getAreaDescription());
        this.sourceDialect = definition == null ? null : definition.getSourceDialect();
        this.gridDeclarations = gridDeclarationsOf(this.params);
        this.authorityRecord = null;
    }

    private Crs(String definitionText, ProjContext context, CoordinateReferenceSystem legacy,
                org.locationtech.proj4j.spi.DbCrs record, AreaOfUse areaOfUse,
                boolean axisOrderAuthoritative, String axisOrderNote) {
        this.definitionText = definitionText;
        this.source = Source.AUTHORITY_CODE;
        this.context = context;
        this.legacy = legacy;
        String[] p = legacy.getParameters();
        this.params = p == null ? null : p.clone();
        this.axisOrderAuthoritative = axisOrderAuthoritative;
        this.axisOrderNote = axisOrderNote;
        this.identifiers = Collections.singletonList(record.ref().authorityCode());
        this.areaOfUse = areaOfUse;
        this.sourceDialect = null;
        this.gridDeclarations = gridDeclarationsOf(this.params);
        this.authorityRecord = record;
    }

    /**
     * Builds a CRS from an authority database row. Package-private; see {@link DatabaseCrsFactory}
     * for what it does and does not build.
     */
    static Crs fromDatabase(String definitionText, ProjContext context,
                            CoordinateReferenceSystem legacy,
                            org.locationtech.proj4j.spi.DbCrs record, AreaOfUse areaOfUse,
                            boolean axisOrderAuthoritative, String axisOrderNote) {
        return new Crs(definitionText, context, legacy, record, areaOfUse, axisOrderAuthoritative,
                axisOrderNote);
    }

    private static List<String> identifiersOf(CrsDefinition definition, Source source,
                                              String definitionText) {
        List<String> ids = new ArrayList<String>(2);
        if (definition != null) {
            List<Identifier> declared = definition.getIds();
            for (int i = 0; i < declared.size(); i++) {
                ids.add(declared.get(i).toString());
            }
        } else if (source == Source.AUTHORITY_CODE && definitionText != null) {
            ids.add(definitionText);
        }
        return Collections.unmodifiableList(ids);
    }

    /**
     * Works out which grid files this CRS's parameters declare, without resolving any of them.
     * A pure string operation: cheap enough to run on every CRS, including one built per row from
     * untrusted input.
     */
    private static Map<String, String> gridDeclarationsOf(String[] params) {
        if (params == null) {
            return Collections.emptyMap();
        }
        String nadgrids = value(params, "nadgrids");
        if (nadgrids != null) {
            // An explicit +nadgrids= replaces whatever the datum declared: Proj4Parser applies
            // +datum first and then overwrites the grid list (parseDatum, in that order).
            return Collections.singletonMap("+nadgrids=" + nadgrids, nadgrids);
        }
        String datum = value(params, "datum");
        if (datum != null) {
            String declared = DATUM_GRID_DECLARATIONS.get(datum);
            if (declared != null) {
                return Collections.singletonMap("+datum=" + datum, declared);
            }
        }
        return Collections.emptyMap();
    }

    /**
     * The value of a PROJ parameter in an already-parsed list, tolerating both the {@code +key=}
     * and the bare {@code key=} spelling. <b>The shipped dictionaries write parameters without the
     * leading {@code +}</b>, so matching only on {@code "+key="} silently finds nothing.
     */
    private static String value(String[] params, String key) {
        for (int i = 0; i < params.length; i++) {
            String p = params[i];
            if (p == null) {
                continue;
            }
            int start = p.startsWith("+") ? 1 : 0;
            if (p.regionMatches(start, key, 0, key.length())
                    && p.length() > start + key.length()
                    && p.charAt(start + key.length()) == '=') {
                return p.substring(start + key.length() + 1);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- identity and definition

    /**
     * The name of this CRS: the authority code it was created from, the name a WKT document gave
     * it, or a synthesised name for an anonymous PROJ string.
     *
     * @return the name; never null
     */
    public String name() {
        return legacy.getName();
    }

    /**
     * The text this CRS was created from, verbatim.
     *
     * @return the definition text; never null
     */
    public String definitionText() {
        return definitionText;
    }

    /**
     * How this CRS was specified.
     *
     * @return the source; never null
     */
    public Source source() {
        return source;
    }

    /**
     * Which WKT dialect the source document was written in.
     *
     * @return the dialect, or empty if this CRS did not come from WKT
     */
    public Optional<WktDialect> sourceDialect() {
        return Optional.ofNullable(sourceDialect);
    }

    /**
     * The authority identifiers this CRS carries &mdash; {@code "EPSG:4326"} &mdash; from the code
     * it was created from or from a document's {@code ID[]} / {@code AUTHORITY[]} clauses.
     *
     * <p>Never invented: a PROJ.4 parameter string that happens to be equivalent to EPSG:4326 is
     * <em>not</em> reported as EPSG:4326, because deciding that requires a database and getting it
     * wrong attributes an authority's name to a caller's parameters.
     *
     * @return an unmodifiable list, possibly empty; never null
     */
    public List<String> identifiers() {
        return identifiers;
    }

    /**
     * The context whose policies this CRS was built under.
     *
     * @return the context; never null
     */
    public ProjContext context() {
        return context;
    }

    // ------------------------------------------------------------------------------ rendering

    /**
     * This CRS as a PROJ.4 parameter string, each parameter {@code +}-prefixed.
     *
     * <p>Round-trips the {@code @} on an optional grid, so {@code +nadgrids=@conus} does not
     * quietly become {@code +nadgrids=conus}.
     *
     * @return the parameter string, or the empty string for a CRS with no parameter list
     */
    public String toProjString() {
        if (params == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(params[i]);
        }
        return sb.toString();
    }

    /**
     * This CRS as WKT2:2019.
     *
     * @return the WKT; never null
     * @throws org.locationtech.proj4j.io.wkt.WktParseException if this CRS cannot be expressed as
     *                                                          WKT
     */
    public String toWkt() {
        return toWkt(WktDialect.WKT2_2019);
    }

    /**
     * This CRS as WKT in a chosen WKT2 revision.
     *
     * <p><b>All four dialects are readable; only the two WKT2 revisions are writable.</b> That
     * asymmetry is deliberate rather than unfinished. Reading every dialect is what removes the need
     * for an Apache SIS fallback, and with it the duplicate {@code org.opengis.util.CodeList} hazard
     * that kills Spark executors, so reading is where the value is. Writing WKT1 is a different
     * proposition: WKT1 has no way to express several things a CRS here can carry, so a WKT1 writer
     * would have to drop them, and a lossy writer that does not say what it dropped is precisely the
     * kind of silent degradation this API exists to avoid. Writing WKT2 loses nothing.
     *
     * <p>{@link #toProjString()} is the lossless legacy interchange format, and every consumer that
     * reads WKT1 also reads a PROJ string.
     *
     * @param dialect {@link WktDialect#WKT2_2019} or {@link WktDialect#WKT2_2015}; null means
     *                {@link WktDialect#WKT2_2019}
     * @return the WKT; never null
     * @throws IllegalArgumentException                         if {@code dialect} is a WKT1 dialect
     * @throws org.locationtech.proj4j.io.wkt.WktParseException if this CRS cannot be expressed as
     *                                                          WKT
     */
    public String toWkt(WktDialect dialect) {
        WktDialect d = dialect == null ? WktDialect.WKT2_2019 : dialect;
        if (d.isWkt1()) {
            throw new IllegalArgumentException("WKT1 (" + d + ") can be read by this library but "
                    + "not written: WKT1 cannot express everything a CRS here carries, and a writer "
                    + "that silently dropped the remainder would be worse than no writer. Use "
                    + "WktDialect.WKT2_2019, or toProjString() for lossless legacy interchange.");
        }
        return new WktWriter(d).write(CrsDefinitions.fromCrs(legacy));
    }

    /**
     * This CRS as a PROJJSON document.
     *
     * @return the PROJJSON; never null
     * @throws org.locationtech.proj4j.io.wkt.WktParseException if this CRS cannot be expressed
     */
    public String toProjJson() {
        return new ProjJsonWriter().write(CrsDefinitions.fromCrs(legacy));
    }

    // -------------------------------------------------------------------------- classification

    /**
     * Whether this is a geographic (longitude/latitude) CRS.
     *
     * @return true for {@code +proj=longlat} and its equivalents
     */
    public boolean isGeographic() {
        Projection p = legacy.getProjection();
        return p != null && Boolean.TRUE.equals(p.isGeographic());
    }

    /**
     * Whether this is a projected CRS, i.e. neither geographic nor geocentric.
     *
     * @return true for a CRS whose coordinates are planar
     */
    public boolean isProjected() {
        return !isGeographic() && !isGeocentric();
    }

    /**
     * Whether this is a geocentric Cartesian CRS &mdash; {@code +proj=geocent}, whose coordinates
     * are earth-centred X, Y, Z in metres rather than a surface position.
     *
     * <p>One of the seven calls a downstream consumer asked for by name. A geocentric CRS is the
     * case where treating the third ordinate as an optional height is wrong: all three ordinates
     * are load-bearing, and dropping Z relocates the point to the centre of the earth.
     *
     * @return true iff the coordinates are geocentric Cartesian
     */
    public boolean isGeocentric() {
        Projection p = legacy.getProjection();
        return p instanceof GeocentProjection;
    }

    /**
     * Whether this CRS's first two ordinates are angles rather than lengths.
     *
     * <p>One of the seven calls a downstream consumer asked for by name, and the one that prevents
     * the most expensive class of mistake: applying a metre tolerance to a degree value, or a
     * degree-to-radian scale to a projected coordinate. The latter inflates a distance by about
     * 111,319&times;, and it has been done.
     *
     * <p>True for a geographic CRS, and for any CRS whose declared unit is one of PROJ's three
     * angular units. False for a geocentric CRS, whose ordinates are metres.
     *
     * @return true iff the horizontal ordinates are angular
     */
    public boolean isAngular() {
        if (isGeocentric()) {
            return false;
        }
        if (isGeographic()) {
            return true;
        }
        Projection p = legacy.getProjection();
        if (p == null) {
            return false;
        }
        Unit unit = p.getUnits();
        for (int i = 0; i < Units.ANGULAR_UNITS.length; i++) {
            if (Units.ANGULAR_UNITS[i] == unit || Units.ANGULAR_UNITS[i].equals(unit)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The unit of this CRS's horizontal ordinates.
     *
     * @return the unit, or empty for a CRS with no projection (the {@code CS_GEO} sentinel)
     */
    public Optional<Unit> unit() {
        Projection p = legacy.getProjection();
        return p == null ? Optional.<Unit>empty() : Optional.ofNullable(p.getUnits());
    }

    /**
     * The {@code +proj=} name of this CRS's projection method.
     *
     * @return the name, for example {@code "lcc"}, or empty for a CRS with no projection
     */
    public Optional<String> projectionName() {
        Projection p = legacy.getProjection();
        return p == null ? Optional.<String>empty() : Optional.ofNullable(p.getName());
    }

    /**
     * The human-readable name of this CRS's projection method, for example
     * {@code "Lambert Conformal Conic"}.
     *
     * <p>These come from {@link Registry}, which has carried them since 2009 with no accessor.
     *
     * @return the description, or empty if the registry has none
     */
    public Optional<String> projectionDescription() {
        Optional<String> name = projectionName();
        return name.isPresent() ? Proj.projectionDescription(name.get()) : Optional.<String>empty();
    }

    /**
     * The datum code, for example {@code "NAD27"}.
     *
     * @return the code, or empty for a CRS with no datum
     */
    public Optional<String> datumCode() {
        Datum d = legacy.getDatum();
        return d == null ? Optional.<String>empty() : Optional.ofNullable(d.getCode());
    }

    /**
     * The datum's human-readable name, for example {@code "North_American_Datum_1927"}.
     *
     * @return the name, or empty for a CRS with no datum
     */
    public Optional<String> datumName() {
        Datum d = legacy.getDatum();
        return d == null ? Optional.<String>empty() : Optional.ofNullable(d.getName());
    }

    /**
     * The ellipsoid's name.
     *
     * @return the name, or empty for a CRS with no projection
     */
    public Optional<String> ellipsoidName() {
        Projection p = legacy.getProjection();
        if (p == null || p.getEllipsoid() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(p.getEllipsoid().getName());
    }

    // -------------------------------------------------------------------------------- axis order

    /**
     * The axis order this CRS actually uses, in PROJ's {@code +axis=} three-letter encoding.
     *
     * <p>One of the seven calls a downstream consumer asked for by name. {@code "enu"} means
     * east&ndash;north&ndash;up: longitude first for a geographic CRS, easting first for a
     * projected one, and it is the default for everything, exactly as in proj4j 1.4.3.
     * {@code "neu"} means latitude first, which is what PROJ 6+ and {@code cs2cs} do for
     * {@code EPSG:4326}.
     *
     * <p>The encoding is PROJ's own and is stable: one character per axis from
     * {@code e w n s u d}. It is returned rather than a bespoke enum precisely so that the answer
     * can be pasted straight back into a PROJ string.
     *
     * @return three characters from {@code "ewnsud"}; never null
     */
    public String axisOrder() {
        String axis = params == null ? null : value(params, "axis");
        return axis == null ? "enu" : axis;
    }

    /**
     * Whether {@link #axisOrder()} is what the CRS definition said, as opposed to what Proj4J
     * inferred.
     *
     * <p>{@code true} when a WKT or PROJJSON document declared its axes, or when the PROJ string
     * carried an explicit {@code +axis=}. {@code false} when the order was inferred &mdash; which
     * is the case for every {@code authority:code} lookup, because authority axis order is
     * database metadata and there is no database. {@link #axisOrderNote()} states the rule that
     * was applied.
     *
     * @return true iff the axis order came from the definition
     */
    public boolean isAxisOrderAuthoritative() {
        return axisOrderAuthoritative;
    }

    /**
     * In one sentence, where {@link #axisOrder()} came from.
     *
     * @return the note; never null, never empty
     */
    public String axisOrderNote() {
        return axisOrderNote;
    }

    /**
     * Whether this CRS takes and returns latitude before longitude.
     *
     * @return true iff {@link #axisOrder()} puts a north/south axis first
     */
    public boolean isLatitudeFirst() {
        char first = axisOrder().charAt(0);
        return first == 'n' || first == 's' || first == 'N' || first == 'S';
    }

    /**
     * A copy of this CRS built under a different axis order policy.
     *
     * <p>Re-parses the original definition text, so nothing is transposed in place and the original
     * remains valid and unchanged.
     *
     * @param policy the policy; null means {@link AxisOrderPolicy#LEGACY}
     * @return a new CRS, or {@code this} if the policy is already in force
     */
    public Crs withAxisOrderPolicy(AxisOrderPolicy policy) {
        ProjContext next = context.withAxisOrderPolicy(policy);
        if (next == context || definitionText == null || definitionText.trim().isEmpty()) {
            return this;
        }
        return Proj.createCrs(definitionText, next);
    }

    /**
     * A copy of this CRS built under a different context.
     *
     * @param newContext the context; null means {@link Proj#defaultContext()}
     * @return a new CRS, or {@code this} if the context is already in force
     */
    public Crs withContext(ProjContext newContext) {
        ProjContext next = newContext == null ? Proj.defaultContext() : newContext;
        if (next.equals(context) || definitionText == null || definitionText.trim().isEmpty()) {
            return this;
        }
        return Proj.createCrs(definitionText, next);
    }

    // ------------------------------------------------------------------------------------ grids

    /**
     * Every grid file this CRS declares, whether or not it can be found.
     *
     * <p>Resolved once, lazily, and memoised, so a CRS created and never transformed does no grid
     * I/O and a CRS used for a million rows does it once.
     *
     * @return an unmodifiable list, in declaration order; never null
     */
    public List<GridInfo> grids() {
        List<GridInfo> memo = gridsMemo;
        if (memo == null) {
            memo = resolveGrids();
            gridsMemo = memo;
        }
        return memo;
    }

    private List<GridInfo> resolveGrids() {
        if (gridDeclarations.isEmpty()) {
            return Collections.emptyList();
        }
        List<GridInfo> out = new ArrayList<GridInfo>();
        for (Map.Entry<String, String> e : gridDeclarations.entrySet()) {
            List<Grid.GridRef> refs = Grid.describeNadGrids(e.getValue());
            for (int i = 0; i < refs.size(); i++) {
                out.add(GridInfo.fromGridRef(refs.get(i), e.getKey()));
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * The grid files this CRS declares, by name, including the {@code @} of an optional one.
     *
     * @return an unmodifiable list; never null
     */
    public List<String> requiredGrids() {
        List<GridInfo> all = grids();
        List<String> names = new ArrayList<String>(all.size());
        for (int i = 0; i < all.size(); i++) {
            names.add((all.get(i).isOptional() ? "@" : "") + all.get(i).name());
        }
        return Collections.unmodifiableList(names);
    }

    /**
     * The grid files this CRS declares and no configured resolver can find.
     *
     * <p><b>A non-empty result means a datum shift will not be applied.</b> Under PROJ's
     * {@code @}-optional semantics that is silent, and the coordinate that comes out is wrong by
     * the size of the shift and entirely plausible &mdash; 95.573&nbsp;m at San Francisco, measured.
     * This list, {@link #describe()} and {@link GridPolicy} exist so that it is not silent here.
     *
     * @return an unmodifiable list; never null, and empty is the good case
     */
    public List<GridInfo> missingGrids() {
        List<GridInfo> all = grids();
        List<GridInfo> missing = new ArrayList<GridInfo>();
        for (int i = 0; i < all.size(); i++) {
            if (!all.get(i).isAvailable()) {
                missing.add(all.get(i));
            }
        }
        return Collections.unmodifiableList(missing);
    }

    /**
     * Whether this CRS's datum declares a grid shift that will not be fully applied, because at
     * least one declared grid is unreachable.
     *
     * @return true iff {@link #missingGrids()} is non-empty
     */
    public boolean hasUnreachableGrids() {
        return !missingGrids().isEmpty();
    }

    // ----------------------------------------------------------------------------- area of use

    /**
     * The extent over which this CRS is declared valid.
     *
     * <p>Three sources, and {@link AreaOfUse#isDatabaseDerived()} distinguishes them:
     * <ul>
     * <li>the authority database's {@code extent} table, when this CRS was resolved from one
     *     &mdash; {@code isDatabaseDerived()} is true;</li>
     * <li>a WKT2 {@code BBOX[]} or a PROJJSON {@code bbox} the caller supplied &mdash;
     *     {@code isDatabaseDerived()} is false;</li>
     * <li>nothing, in which case this is empty.</li>
     * </ul>
     *
     * <p>Never guessed from projection parameters. A plausible bounding box invented from a
     * {@code +lat_0} would be exactly the kind of answer this API exists to avoid, and it would then
     * win every area-of-use ranking.
     *
     * <p>When the authority declares several usages this is the smallest by ranking area, ties broken
     * on the extent code; {@link #authorityExtents()} returns all of them.
     *
     * @return the area of use, or empty
     */
    public Optional<AreaOfUse> areaOfUse() {
        return Optional.ofNullable(areaOfUse);
    }

    /**
     * Every extent the authority declares for this CRS, smallest first.
     *
     * <p>An object may declare more than one usage. Empty when this CRS did not come from an authority
     * database, or when none of its extents publishes a bounding box.
     *
     * @return an unmodifiable list, smallest first; never null
     */
    public List<AreaOfUse> authorityExtents() {
        if (authorityRecord == null || !context.hasDatabase()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(
                DatabaseCrsFactory.extentsAsAreas(context.database(), authorityRecord.ref()));
    }

    /**
     * The authority database row this CRS was built from.
     *
     * <p>Present only for a CRS the <em>database</em> resolved &mdash; not for one the legacy PROJ.4
     * dictionary resolved under the same code, because those parameters come from the dictionary and
     * attributing them to the database would misstate where the numbers came from. The dictionary
     * stays authoritative for every code it knows, so that adding {@code proj4j-db} cannot move a
     * coordinate that already worked.
     *
     * @return the row, or empty
     */
    public Optional<org.locationtech.proj4j.spi.DbCrs> authorityRecord() {
        return Optional.ofNullable(authorityRecord);
    }

    /**
     * Whether this CRS's definition was read from an authority database rather than from the legacy
     * dictionary or from a caller's document.
     *
     * @return true iff {@link #authorityRecord()} is present
     */
    public boolean isDatabaseDerived() {
        return authorityRecord != null;
    }

    // --------------------------------------------------------------------------- interoperation

    /**
     * This CRS as the legacy {@link CoordinateReferenceSystem} the 1.x API uses.
     *
     * <p><b>A fresh instance each call</b>, rebuilt from the parameter list, never the object this
     * {@code Crs} holds. That is deliberate and not merely defensive: {@code proj4j-geoapi}'s
     * parameter wrappers write back into a live {@link Projection}, so handing out the internal one
     * would let a caller mutate a shared, supposedly immutable CRS. The one exception is a CRS with
     * no parameter list to rebuild from, where the held instance is returned and this javadoc is
     * the warning.
     *
     * @return a legacy CRS; never null
     */
    public CoordinateReferenceSystem asLegacy() {
        if (params == null) {
            return legacy;
        }
        return new CRSFactory().createFromParameters(legacy.getName(), params.clone());
    }

    /**
     * The internal legacy CRS, without copying. Package-private: only {@link CrsOperation} and
     * {@link LegacyAdapters} may see it, and neither mutates it.
     */
    CoordinateReferenceSystem legacy() {
        return legacy;
    }

    // ---------------------------------------------------------------------------------- describe

    /**
     * Everything this object knows about the CRS, and everything it deliberately does not, as
     * multi-line text meant for a log or an error report.
     *
     * <p>One of the seven calls a downstream consumer asked for by name. It states the axis order
     * and where that came from, the projection and datum, every declared grid with its
     * reachability, and the area of use or the reason there is none. A CRS with an unreachable grid
     * says so in words, because that is the failure a coordinate cannot reveal.
     *
     * @return the description, newline-terminated; never null
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("CRS ").append(name()).append('\n');
        sb.append("  source          = ").append(source);
        if (sourceDialect != null) {
            sb.append(" (").append(sourceDialect).append(')');
        }
        sb.append('\n');
        sb.append("  definition      = ").append(definitionText).append('\n');
        sb.append("  proj string     = ").append(toProjString()).append('\n');
        if (!identifiers.isEmpty()) {
            sb.append("  identifiers     = ").append(identifiers).append('\n');
        }
        sb.append("  kind            = ")
                .append(isGeocentric() ? "geocentric Cartesian"
                        : isGeographic() ? "geographic" : "projected")
                .append("   isAngular=").append(isAngular()).append('\n');
        if (projectionName().isPresent()) {
            sb.append("  projection      = +proj=").append(projectionName().get());
            if (projectionDescription().isPresent()) {
                sb.append("  (").append(projectionDescription().get()).append(')');
            }
            sb.append('\n');
        }
        if (datumCode().isPresent()) {
            sb.append("  datum           = ").append(datumCode().get());
            if (datumName().isPresent()) {
                sb.append("  (").append(datumName().get()).append(')');
            }
            sb.append('\n');
        }
        if (ellipsoidName().isPresent()) {
            sb.append("  ellipsoid       = ").append(ellipsoidName().get()).append('\n');
        }
        if (unit().isPresent()) {
            sb.append("  unit            = ").append(unit().get()).append('\n');
        }
        sb.append("  axisOrder       = ").append(axisOrder())
                .append(isLatitudeFirst() ? "  (latitude first)" : "  (longitude first)")
                .append(axisOrderAuthoritative ? "  [declared]" : "  [inferred]").append('\n');
        sb.append("                    ").append(axisOrderNote).append('\n');
        if (authorityRecord != null) {
            sb.append("  authorityRecord = ").append(authorityRecord).append('\n');
        }
        sb.append("  areaOfUse       = ").append(areaOfUse != null ? areaOfUse.toString()
                : context.hasDatabase()
                        ? "<none> -- the authority declares no usage for this object, or its extent "
                                + "publishes no bounding box; it is not guessed"
                        : "<none> -- area of use is authority metadata and no CRS database is "
                                + "configured (ProjContext.Builder.database(..)); it is not guessed")
                .append('\n');

        List<GridInfo> all = grids();
        if (all.isEmpty()) {
            sb.append("  grids           = none declared\n");
        } else {
            List<GridInfo> missing = missingGrids();
            sb.append("  grids           = ").append(all.size()).append(" declared, ")
                    .append(all.size() - missing.size()).append(" reachable, ")
                    .append(missing.size()).append(" UNREACHABLE\n");
            for (int i = 0; i < all.size(); i++) {
                sb.append("      ").append(all.get(i).describe()).append('\n');
            }
            if (!missing.isEmpty()) {
                sb.append("    WARNING: this CRS declares a datum shift that cannot be fully "
                        + "applied. The coordinates it produces will be wrong by the size of the "
                        + "missing shift -- finite, plausible, and undetectable downstream. Under "
                        + "GridPolicy.").append(context.gridPolicy())
                        .append(" the new API reports this; the legacy API skips the grid "
                                + "silently, exactly as 1.4.3 did.\n");
            }
        }
        sb.append("  context         = ").append(context).append('\n');
        return sb.toString();
    }

    @Override
    public String toString() {
        return "Crs[" + name() + ", axisOrder=" + axisOrder() + ", " + source + "]";
    }

    /**
     * Equal iff the underlying coordinate reference systems and the governing context are equal.
     * The definition <em>text</em> is not compared: the same CRS written as WKT and as a PROJ
     * string is the same CRS.
     *
     * @param o the other object
     * @return true if equal
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Crs)) {
            return false;
        }
        Crs that = (Crs) o;
        return legacy.equals(that.legacy) && context.equals(that.context);
    }

    @Override
    public int hashCode() {
        return 31 * legacy.hashCode() + context.hashCode();
    }
}

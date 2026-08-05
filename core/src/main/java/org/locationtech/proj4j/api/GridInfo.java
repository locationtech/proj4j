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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import org.locationtech.proj4j.resource.ChainedResourceResolver;
import org.locationtech.proj4j.resource.ResourceHandle;
import org.locationtech.proj4j.resource.ResourceResolver;
import org.locationtech.proj4j.resource.ResourceResolvers;
import org.locationtech.proj4j.spi.DbGridAlternative;

/**
 * One datum-shift or geoid grid file, and specifically whether it is <b>reachable</b> as opposed to
 * merely <b>declared</b>.
 *
 * <h2>Why that distinction is the whole point of this class</h2>
 *
 * <p>{@code +nadgrids=@conus} means "shift using {@code conus}, and if it is not there, don't".
 * PROJ implements that literally and silently. proj4j 1.4.3 did too. The result is a coordinate
 * that is wrong by the size of the datum shift, is entirely plausible, is finite, is in the right
 * units, and is accompanied by no exception, no warning and no log line. At San Francisco, with
 * every US grid absent, {@code EPSG:4267} to {@code EPSG:4326} came out <b>95.573&nbsp;m</b> wrong.
 * That is the worst measured defect in this library's history and it is a <em>reporting</em>
 * failure, not an arithmetic one.
 *
 * <p>So this class always answers three separate questions and never collapses them:
 * <ul>
 * <li>{@link #isDeclared()} &mdash; does some CRS on this classpath ask for this grid?</li>
 * <li>{@link #isAvailable()} &mdash; did the resolver chain actually find it, just now?</li>
 * <li>{@link #isOptional()} &mdash; did the declaration carry PROJ's {@code @}, i.e. is its absence
 *     supposed to pass unremarked?</li>
 * </ul>
 * The combination {@code isDeclared() && !isAvailable()} is the dangerous one, and
 * {@link #describe()} spells it out in words.
 *
 * <h2>What this class refuses to claim</h2>
 *
 * <ul>
 * <li><b>{@link #format()} is empty unless the file was actually read.</b> A grid's format is in
 * its header, and reporting a format from a file name would be a guess. Listing grids does not
 * read them.</li>
 * <li><b>{@link #knownUrl()} is information for a human, never an action.</b> Proj4J core contains
 * no network code at all, so a grid that exists only at that URL is reported by
 * {@link #isAvailable()} as {@code false}, full stop. The URL is there so a human knows what to
 * add to the classpath.</li>
 * <li><b>{@link #sizeBytes()} is empty when the resolver cannot say.</b> A jar entry read through
 * some class loaders has no content length.</li>
 * </ul>
 *
 * <h2>Legacy and modern names</h2>
 *
 * <p>PROJ 7 renamed every grid: {@code conus} became {@code us_noaa_conus.tif},
 * {@code ntv2_0.gsb} became {@code ca_nrc_ntv2_0.tif}. Both names identify the same shift, and a
 * PROJ.4 parameter string in the wild uses the old one, so {@link #name()} is whatever was asked
 * for and {@link #modernName()} is the PROJ 7+ spelling where it is known.
 *
 * <p><b>Only {@code conus} and {@code alaska} exist in the legacy CTABLE V2 form</b> that this
 * library's reader understands &mdash; those are the two in {@code 9.8.1:data/tests/}. The other
 * five US grids ({@code hawaii}, {@code prvi}, {@code stgeorge}, {@code stlrnc}, {@code stpaul})
 * exist only as GeoTIFF in {@code PROJ-data}, so they arrive with the GeoTIFF reader and not
 * before it. {@link #note()} says so on those five rather than leaving a caller to wonder why the
 * pack is short.
 *
 * <p>Immutable and safe to share between threads.
 *
 * @see Crs#missingGrids()
 * @see Proj#availableGrids()
 * @see GridPolicy
 * @since 1.5.0
 */
public final class GridInfo {

    /**
     * PROJ 7's grid rename table, restricted to the grids Proj4J can be asked for. Transcribed
     * from {@code proj.db}'s {@code grid_alternatives.old_proj_grid_name} /
     * {@code proj_grid_name} pairs; the full table has 472 rows and needs the database, so this is
     * the subset reachable from the legacy datum definitions and from {@code proj4j-epsg}.
     */
    private static final Map<String, String> MODERN_NAMES;

    /**
     * The five US grids with no legacy form to ship. See the class javadoc.
     */
    private static final String GEOTIFF_ONLY_NOTE =
            "no CTABLE V2 form exists upstream; this grid is GeoTIFF-only in PROJ-data and needs "
                    + "the GeoTIFF reader";

    private static final Map<String, String> NOTES;

    static {
        Map<String, String> m = new HashMap<String, String>();
        m.put("conus", "us_noaa_conus.tif");
        m.put("alaska", "us_noaa_alaska.tif");
        m.put("hawaii", "us_noaa_hawaii.tif");
        m.put("prvi", "us_noaa_prvi.tif");
        m.put("stgeorge", "us_noaa_stgeorge.tif");
        m.put("stlrnc", "us_noaa_stlrnc.tif");
        m.put("stpaul", "us_noaa_stpaul.tif");
        m.put("ntv1_can.dat", "ca_nrc_ntv1_can.tif");
        m.put("ntv2_0.gsb", "ca_nrc_ntv2_0.tif");
        m.put("BETA2007.gsb", "de_adv_BETA2007.tif");
        m.put("OSTN15_NTv2_OSGBtoETRS.gsb", "uk_os_OSTN15_NTv2_OSGBtoETRS.tif");
        m.put("egm96_15.gtx", "us_nga_egm96_15.tif");
        MODERN_NAMES = Collections.unmodifiableMap(m);

        Map<String, String> n = new HashMap<String, String>();
        n.put("hawaii", GEOTIFF_ONLY_NOTE);
        n.put("prvi", GEOTIFF_ONLY_NOTE);
        n.put("stgeorge", GEOTIFF_ONLY_NOTE);
        n.put("stlrnc", GEOTIFF_ONLY_NOTE);
        n.put("stpaul", GEOTIFF_ONLY_NOTE);
        n.put("null", "PROJ's built-in no-op grid; it exists so that +nadgrids=@null can assert "
                + "\"deliberately no datum shift\" rather than \"shift unavailable\"");
        NOTES = Collections.unmodifiableMap(n);
    }

    /** Where a human can see the grid, if they want to add it to the classpath deliberately. */
    private static final String CDN_BASE = "https://cdn.proj.org/";

    private final String name;
    private final boolean optional;
    private final boolean available;
    private final String resolverName;
    private final String origin;
    private final long sizeBytes;
    private final String format;
    private final String declaredBy;
    private final String skipReason;

    // --- authority-database facts. All null/0/false for a grid that came from a +nadgrids= token.
    private final int slot;
    private final String modernName;
    private final String projGridFormat;
    private final String projMethod;
    private final boolean inverseDirection;
    private final String satisfiedBy;
    private final List<String> probedNames;

    private GridInfo(String name, boolean optional, boolean available, String resolverName,
                     String origin, long sizeBytes, String format, String declaredBy,
                     String skipReason) {
        this(name, optional, available, resolverName, origin, sizeBytes, format, declaredBy,
                skipReason, 0, null, null, null, false, null, null);
    }

    private GridInfo(String name, boolean optional, boolean available, String resolverName,
                     String origin, long sizeBytes, String format, String declaredBy,
                     String skipReason, int slot, String modernName, String projGridFormat,
                     String projMethod, boolean inverseDirection, String satisfiedBy,
                     List<String> probedNames) {
        this.name = name;
        this.optional = optional;
        this.available = available;
        this.resolverName = resolverName;
        this.origin = origin;
        this.sizeBytes = sizeBytes;
        this.format = format;
        this.declaredBy = declaredBy;
        this.skipReason = skipReason;
        this.slot = slot;
        this.modernName = modernName;
        this.projGridFormat = projGridFormat;
        this.projMethod = projMethod;
        this.inverseDirection = inverseDirection;
        this.satisfiedBy = satisfiedBy;
        this.probedNames = probedNames == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(probedNames);
    }

    /**
     * Probes the configured resolver chain for {@code name} and reports what it found, without
     * reading or parsing the file.
     *
     * <p>This is a <em>probe</em>, not an enumeration: it works for a non-enumerable resolver too,
     * which is why {@link Proj#grid(String)} can answer for a grid that
     * {@link Proj#availableGrids()} cannot list.
     *
     * @param name       the grid file name, with any {@code @} already stripped
     * @param optional   whether the declaration carried PROJ's {@code @} prefix
     * @param declaredBy what declares this grid, for example {@code "+datum=NAD27"}, or null if it
     *                   was found by enumerating a resolver rather than declared by a CRS
     * @return the info; never null
     */
    static GridInfo probe(String name, boolean optional, String declaredBy) {
        ChainedResourceResolver chain = ResourceResolvers.resolver();
        ResourceHandle handle;
        try {
            handle = chain.resolve(name);
        } catch (IOException e) {
            return new GridInfo(name, optional, false, null, null, -1L, null, declaredBy,
                    "resolver chain threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (handle == null) {
            return new GridInfo(name, optional, false, null, null, -1L, null, declaredBy,
                    "not found by any configured resolver");
        }
        ResourceResolver found = chain.resolverOf(name);
        return new GridInfo(name, optional, true, found == null ? null : found.name(),
                handle.origin(), handle.sizeBytes(), null, declaredBy, null);
    }

    /**
     * The outcome of one {@code +nadgrids=} token that the grid layer has already resolved, so the
     * format is known because the file was read.
     *
     * @param ref        one token's resolution outcome
     * @param declaredBy what declared it
     * @return the info; never null
     */
    static GridInfo fromGridRef(org.locationtech.proj4j.datum.Grid.GridRef ref, String declaredBy) {
        if (!ref.isAvailable()) {
            String reason = ref.skipReason();
            return new GridInfo(ref.name(), ref.isOptional(), false, null, null, -1L, null,
                    declaredBy, reason == null ? "not found by any configured resolver" : reason);
        }
        org.locationtech.proj4j.datum.Grid grid = ref.grid();
        // Deliberately NOT grid.sizeBytes(): that is the parsed grid's accounted heap cost, which
        // for ntv1_can.dat is 2,225,952 B against a 1,113,184 B file. Reporting a heap figure under
        // a name that reads as a file size is the kind of small lie this class exists not to tell,
        // so the file length comes from a resolver probe instead.
        long fileBytes = probe(ref.name(), ref.isOptional(), declaredBy).sizeBytes;
        return new GridInfo(ref.name(), ref.isOptional(), true, grid.getResolverName(),
                grid.getOrigin(), fileBytes, grid.getFormat(), declaredBy, null);
    }

    /**
     * One grid slot of an authority coordinate operation: what the authority asked for, what PROJ's
     * {@code grid_alternatives} says to read instead, and whether this deployment can find either.
     *
     * <p><b>Called once per slot, so the second grid of a NADCON pair is never dropped.</b>
     * {@code EPSG:1241} needs {@code conus.las} and {@code conus.los}; reading only
     * {@code grid_name} would apply half the shift and report success.
     *
     * <p>Three names are probed, in PROJ's own preference order, and the first that resolves wins:
     * the modern {@code proj_grid_name} ({@code us_noaa_conus.tif}), the pre-PROJ-7
     * {@code old_proj_grid_name} ({@code conus} &mdash; which is what {@code proj4j-grids-us-legacy}
     * ships), and finally the authority's own spelling. That mirrors
     * {@code FileManager::open_resource_file}, which tries the legacy&rarr;modern and
     * modern&rarr;legacy renames after a direct open fails.
     *
     * @param authorityName the name the authority uses, from
     *                      {@link org.locationtech.proj4j.spi.DbOperation#gridNames()}
     * @param slot          1 for {@code grid_name}, 2 for {@code grid2_name}
     * @param alternative   the {@code grid_alternatives} row, or null if the authority name has none
     * @param declaredBy    what asks for this grid, for example {@code "EPSG:1241 grid_name"}
     * @return the info; never null
     */
    static GridInfo forDbGrid(String authorityName, int slot, DbGridAlternative alternative,
                              String declaredBy) {
        String modern = alternative == null ? null : alternative.projGridName();
        String legacy = alternative == null ? null : alternative.oldProjGridName();
        String format = alternative == null ? null : alternative.projGridFormat();
        String method = alternative == null ? null : alternative.projMethod();
        boolean inverse = alternative != null && alternative.inverseDirection();

        List<String> probed = new ArrayList<String>(3);
        addIfNew(probed, modern);
        addIfNew(probed, legacy);
        addIfNew(probed, authorityName);

        for (int i = 0; i < probed.size(); i++) {
            GridInfo probe = probe(probed.get(i), false, declaredBy);
            if (probe.available) {
                return new GridInfo(authorityName, false, true, probe.resolverName, probe.origin,
                        probe.sizeBytes, null, declaredBy, null, slot, modern, format, method,
                        inverse, probed.get(i), probed);
            }
        }
        StringBuilder why = new StringBuilder("no configured resolver has ");
        for (int i = 0; i < probed.size(); i++) {
            if (i > 0) {
                why.append(" or ");
            }
            why.append(probed.get(i));
        }
        if (alternative == null) {
            why.append(" (the authority name has no grid_alternatives row, so there is no modern "
                    + "GeoTIFF form to fall back to)");
        }
        return new GridInfo(authorityName, false, false, null, null, -1L, null, declaredBy,
                why.toString(), slot, modern, format, method, inverse, null, probed);
    }

    /**
     * A later grid slot whose shift is carried by the file an <em>earlier</em> slot already resolved.
     *
     * <p>This is PROJ's own {@code .las}/{@code .los} collapse, reported rather than performed
     * silently. {@code substitutePROJAlternativeGridNames}
     * ({@code 9.8.1:src/iso19111/operation/singleoperation.cpp}, the
     * {@code projGridFormat == "GTiff"} branch) looks up {@code grid_alternatives} for the
     * <em>latitude</em> file alone and replaces the whole pair with one file carrying a
     * <i>Latitude and longitude difference file</i> parameter. Note that upstream's
     * {@code grid_alternatives} has a row for {@code conus.las} and <b>none for {@code conus.los}</b>
     * &mdash; only 1 of the 85 distinct {@code grid2_name}s has one &mdash; which is precisely why the
     * second slot has to be resolved this way and not on its own.
     *
     * <p>The same collapse holds for the pre-PROJ-7 form: the CTABLE V2 {@code conus} that
     * {@code proj4j-grids-us-legacy} ships also carries both components. So the file recorded here is
     * <b>whatever the first slot actually resolved to</b>, not the modern name it might have.
     *
     * <p>Availability is copied from the first slot: two slots satisfied by one file cannot disagree
     * about whether that file exists.
     *
     * @param firstSlot     the already-resolved first slot
     * @param authorityName this slot's authority name, for example {@code conus.los}
     * @param slot          this slot's number
     * @param alternative   this slot's own {@code grid_alternatives} row, usually null
     * @param declaredBy    what asks for this grid
     * @param reason        why the first slot's file covers this one
     * @return the info; never null
     */
    static GridInfo sharedWithEarlierSlot(GridInfo firstSlot, String authorityName, int slot,
                                          DbGridAlternative alternative, String declaredBy,
                                          String reason) {
        List<String> probed = new ArrayList<String>(1);
        addIfNew(probed, firstSlot.satisfiedBy);
        addIfNew(probed, alternative == null ? null : alternative.projGridName());
        addIfNew(probed, authorityName);
        String satisfiedBy = firstSlot.satisfiedBy == null ? null
                : firstSlot.satisfiedBy + " -- " + reason;
        return new GridInfo(authorityName, false, firstSlot.available, firstSlot.resolverName,
                firstSlot.origin, firstSlot.sizeBytes, null, declaredBy,
                firstSlot.available ? null
                        : "carried by " + firstSlot.name() + "'s file, which is itself unreachable: "
                                + firstSlot.skipReason,
                slot, alternative == null ? null : alternative.projGridName(),
                firstSlot.projGridFormat, firstSlot.projMethod, firstSlot.inverseDirection,
                satisfiedBy, probed);
    }

    private static void addIfNew(List<String> into, String candidate) {
        if (candidate != null && !candidate.isEmpty() && !into.contains(candidate)) {
            into.add(candidate);
        }
    }

    /**
     * The grid file name as it was asked for, with any {@code @} prefix stripped. For a legacy
     * PROJ.4 string this is the pre-PROJ-7 name, for example {@code conus}; for an authority
     * operation slot it is the authority's own spelling, for example {@code conus.las}.
     *
     * @return the name; never null, never empty
     */
    public String name() {
        return name;
    }

    /**
     * The PROJ 7+ file name for the same shift, where it is known.
     *
     * <p>Read from the authority database's {@code grid_alternatives} when this grid came from an
     * operation slot; otherwise from the small rename table this class carries for the grids the
     * legacy datum definitions can ask for.
     *
     * @return the modern name, or empty if neither source knows one
     */
    public Optional<String> modernName() {
        return modernName != null ? Optional.of(modernName)
                : Optional.ofNullable(MODERN_NAMES.get(name));
    }

    /**
     * Which grid slot of the authority operation this is: {@code 1} for {@code grid_name},
     * {@code 2} for {@code grid2_name}.
     *
     * <p><b>{@code 2} is the trap.</b> NADCON splits the latitude and longitude shifts across a
     * {@code .las}/{@code .los} pair and 150 of the shipped database's 1,062 grid transformations have
     * a second grid, so a selector reading only slot 1 applies half the shift and reports success.
     *
     * @return the slot number, or empty for a grid that came from a {@code +nadgrids=} token rather
     *         than from an authority operation
     */
    public java.util.OptionalInt slot() {
        return slot > 0 ? java.util.OptionalInt.of(slot) : java.util.OptionalInt.empty();
    }

    /**
     * The file that actually satisfies this slot, and why, when it is not simply this grid's own
     * name.
     *
     * <p>For {@code conus.los} in a deployment with the GeoTIFF grid pack this reads
     * {@code "us_noaa_conus.tif -- the GeoTIFF form of conus.las carries both the latitude and the
     * longitude shift"}: two authority slots, one file. The authority's requirement is reported
     * unchanged and the substitution is reported next to it, so neither can be mistaken for the
     * other.
     *
     * @return the satisfying file and its reason, or empty if unavailable or if the name resolved
     *         directly
     */
    public Optional<String> satisfiedBy() {
        return Optional.ofNullable(satisfiedBy);
    }

    /**
     * Every file name that was probed for this slot, in the order they were tried.
     *
     * @return an unmodifiable list; empty for a grid that did not come from an authority operation
     */
    public List<String> probedNames() {
        return probedNames;
    }

    /**
     * The format {@code grid_alternatives} declares for the modern file: {@code GTiff}, {@code GTX},
     * {@code NTv2} or {@code JSON}.
     *
     * <p>Distinct from {@link #format()}, which is read from a file that was actually opened. This one
     * is authority metadata and is available without touching the file.
     *
     * @return the declared format, or empty
     */
    public Optional<String> declaredFormat() {
        return Optional.ofNullable(projGridFormat);
    }

    /**
     * The PROJ operator this grid feeds: {@code hgridshift}, {@code vgridshift}, {@code gridshift},
     * {@code geoid_like}, {@code geocentricoffset}, {@code tinshift}, {@code velocity_grid} or
     * {@code defmodel}.
     *
     * <p>Proj4J implements some of these and not others, and the difference is
     * {@link org.locationtech.proj4j.ErrorCause#UNSUPPORTED_OPERATION_METHOD} rather than a wrong
     * number.
     *
     * @return the operator name, or empty
     */
    public Optional<String> projMethod() {
        return Optional.ofNullable(projMethod);
    }

    /**
     * Whether the PROJ grid runs the opposite way from the authority's declaration.
     *
     * <p>Ignoring this applies the shift with the wrong sign: twice the error, still plausible.
     *
     * @return true iff the direction is reversed
     */
    public boolean isInverseDirection() {
        return inverseDirection;
    }

    /**
     * Whether the declaration carried PROJ's {@code @} prefix, which makes the grid optional and
     * its absence silent <em>in PROJ</em>. It is not silent here; see {@link GridPolicy}.
     *
     * @return true iff the token was {@code @}-prefixed
     */
    public boolean isOptional() {
        return optional;
    }

    /**
     * Whether the resolver chain found this file. Proved by a probe at the time this object was
     * created, not inferred from a name or a manifest.
     *
     * @return true iff the grid is reachable
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Whether something on this classpath asks for this grid, as opposed to this grid merely being
     * present.
     *
     * @return true iff a CRS or datum declares it
     */
    public boolean isDeclared() {
        return declaredBy != null;
    }

    /**
     * What declares this grid &mdash; {@code "+datum=NAD27"}, {@code "+nadgrids=@conus,@alaska"}, or
     * similar.
     *
     * @return the declaring parameter, or empty if this grid was found by enumeration and nothing
     *         here asks for it
     */
    public Optional<String> declaredBy() {
        return Optional.ofNullable(declaredBy);
    }

    /**
     * Which resolver in the chain found the file.
     *
     * @return the resolver name, or empty iff {@link #isAvailable()} is false
     */
    public Optional<String> resolverName() {
        return Optional.ofNullable(resolverName);
    }

    /**
     * Where the file came from, for example
     * {@code "classpath:proj4j-data/grids/conus"}. Note that the working directory is never
     * consulted, so this is never a bare relative path.
     *
     * @return the origin, or empty iff {@link #isAvailable()} is false
     */
    public Optional<String> origin() {
        return Optional.ofNullable(origin);
    }

    /**
     * The size of the file in bytes.
     *
     * @return the size, or empty if unavailable or if the resolver cannot report a length
     */
    public OptionalLong sizeBytes() {
        return sizeBytes >= 0 ? OptionalLong.of(sizeBytes) : OptionalLong.empty();
    }

    /**
     * The grid's on-disk format &mdash; {@code CTABLE V2}, {@code NTv1}, {@code NTv2} &mdash; read
     * from its header.
     *
     * @return the format, or empty if the file has not been read; <b>never</b> a guess from the
     *         file name
     */
    public Optional<String> format() {
        return Optional.ofNullable(format);
    }

    /**
     * Why the grid was skipped.
     *
     * @return the reason, or empty iff {@link #isAvailable()} is true
     */
    public Optional<String> skipReason() {
        return Optional.ofNullable(skipReason);
    }

    /**
     * Anything a caller ought to know about this particular grid that is not covered by the other
     * accessors &mdash; notably that five of the seven US grids have no legacy form to ship.
     *
     * @return the note, or empty
     */
    public Optional<String> note() {
        return Optional.ofNullable(NOTES.get(name));
    }

    /**
     * The public URL this grid can be seen at, <b>as information for a human</b>.
     *
     * <p>Proj4J core contains no network code, and reading this value does not and cannot cause a
     * fetch. A grid reachable only at this URL is reported as unavailable.
     *
     * @return the URL, or empty if the modern name is unknown
     */
    public Optional<String> knownUrl() {
        Optional<String> modern = modernName();
        return modern.isPresent() ? Optional.of(CDN_BASE + modern.get())
                : Optional.<String>empty();
    }

    /**
     * One line stating, in words, exactly which of reachable and declared this grid is.
     *
     * @return the description, without a trailing newline; never null
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(optional ? "@" : "").append(name);
        if (available) {
            sb.append(" -> REACHABLE from ").append(resolverName == null ? "?" : resolverName);
            if (origin != null) {
                sb.append(" (").append(origin).append(')');
            }
            if (sizeBytes >= 0) {
                sb.append(", ").append(sizeBytes).append(" B");
            }
            if (format != null) {
                sb.append(", format ").append(format);
            }
            if (projGridFormat != null) {
                sb.append(", declared ").append(projGridFormat);
            }
            if (projMethod != null) {
                sb.append(" for +proj=").append(projMethod);
            }
            if (inverseDirection) {
                sb.append(", INVERSE DIRECTION relative to the authority's declaration");
            }
            if (satisfiedBy != null && !satisfiedBy.equals(name)) {
                sb.append(", satisfied by ").append(satisfiedBy);
            }
        } else if (declaredBy != null) {
            sb.append(" -> DECLARED BUT UNREACHABLE (").append(skipReason).append(')');
            sb.append(optional
                    ? ". The @ prefix means PROJ and proj4j 1.4.3 skip it silently and apply no "
                            + "shift; the coordinate is then wrong by the size of the shift and "
                            + "entirely plausible"
                    : ". Required, so no transformation using it can be built");
        } else {
            sb.append(" -> not reachable and not declared");
        }
        if (declaredBy != null) {
            sb.append(" [declared by ").append(declaredBy).append(']');
        }
        String note = NOTES.get(name);
        if (note != null) {
            sb.append(" [").append(note).append(']');
        }
        Optional<String> modernSpelling = modernName();
        if (modernSpelling.isPresent() && !available) {
            sb.append(" [PROJ 7+ name ").append(modernSpelling.get()).append("; see ")
                    .append(CDN_BASE).append(modernSpelling.get())
                    .append(" -- information only, proj4j core performs no network I/O]");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return describe();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GridInfo)) {
            return false;
        }
        GridInfo that = (GridInfo) o;
        return name.equals(that.name) && optional == that.optional && available == that.available
                && sizeBytes == that.sizeBytes
                && eq(resolverName, that.resolverName) && eq(origin, that.origin)
                && eq(format, that.format) && eq(declaredBy, that.declaredBy)
                && eq(skipReason, that.skipReason);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    @Override
    public int hashCode() {
        int h = name.hashCode();
        h = 31 * h + (optional ? 1 : 0);
        h = 31 * h + (available ? 1 : 0);
        h = 31 * h + (declaredBy == null ? 0 : declaredBy.hashCode());
        return h;
    }
}

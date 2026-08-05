/*******************************************************************************
 * Copyright 2009, 2017 Martin Davis
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
package org.locationtech.proj4j.datum;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.locationtech.proj4j.ConvergenceFailureException;
import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.datum.tiff.GeoTiffDataset;
import org.locationtech.proj4j.resource.ChainedResourceResolver;
import org.locationtech.proj4j.resource.ResourceHandle;
import org.locationtech.proj4j.resource.ResourceNames;
import org.locationtech.proj4j.resource.ResourceResolver;
import org.locationtech.proj4j.resource.ResourceResolvers;
import org.locationtech.proj4j.resource.Resources;
import org.locationtech.proj4j.util.FloatPolarCoordinate;
import org.locationtech.proj4j.util.IntPolarCoordinate;
import org.locationtech.proj4j.util.PolarCoordinate;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * A Grid represents a geodetic datum defining some mapping between a
 * coordinate system referenced to the surface of the earth and spherical
 * coordinates.  Generally Grids are loaded from definition files resolved
 * through {@link ResourceResolvers}.
 *
 * <h2>Resolution</h2>
 * <p>Grid files are located by the single deterministic chain described on
 * {@link ResourceResolvers}. The <strong>process working directory is never consulted</strong>. In
 * 1.4.3 it was consulted <em>first</em>, which made the answer depend on whatever a cluster framework
 * had staged into a shared container work dir, and turned an untrusted per-row {@code +nadgrids=}
 * token into an arbitrary-file-open primitive.
 *
 * <h2>Caching</h2>
 * <p>Parsed grids are shared through {@link GridCache}. A {@code Grid} is therefore
 * <strong>deeply immutable after construction</strong> and safe to share between threads; nothing in
 * this class mutates a loaded grid, and {@link #shift} keeps all per-call state in locals.
 */
// Grid corresponds to the PJ_GRIDINFO struct in proj.4
public final class Grid implements Serializable, GridCache.Sized {

    private static final long serialVersionUID = 1L;

    /** Accounting cost of one grid node, for the byte-bounded {@link GridCache}: a
     * {@link FloatPolarCoordinate} is a 16-byte object (12-byte header + 2 floats, padded) plus a
     * 4-byte compressed reference in the backing array, plus slack. Deliberately an over-estimate;
     * a cache budget that under-counts is not a budget. */
    private static final long BYTES_PER_NODE = 32L;

    /**
     * Largest number of comma-separated tokens a {@code +nadgrids=} or {@code +geoidgrids=} list may
     * carry.
     *
     * <p>Every token is a full pass down the resolver chain, and the token list comes out of a CRS
     * string that this library's threat model treats as per-row untrusted input. Uncapped, one
     * pathological definition is an arbitrary multiplier on CRS-construction cost.
     *
     * <p>32 is chosen against a measurement, not a guess. The longest list anywhere in PROJ 9.8.1
     * ({@code git grep} over the tag, all of {@code src/}, {@code data/} and {@code test/}) is
     * <strong>6</strong> tokens, and the longest in this repository is the same 6
     * ({@code geoidgrids=g2012a_conus.gtx,…,g2012a_samoa.gtx}); the longest {@code +nadgrids=} in
     * either is 4, the NAD27 list. So the cap sits at more than five times the largest real list and
     * cannot be the binding constraint on a legitimate definition.
     */
    public static final int MAX_GRID_TOKENS = 32;

    /** Hard ceiling on a single grid file, so a mistaken or hostile {@code +nadgrids=} cannot be
     * turned into an OOM. Settable with {@code -Dproj4j.grids.maxFileBytes}, and read through
     * {@link GridExtents#maxFileBytes()} so that the ceiling the resolver applies to the file and
     * the ceiling the readers apply to a declared extent are the same number by construction —
     * they were separate literals, and {@link VerticalGrid} carried a third, four times larger. */
    private static long maxGridFileBytes() {
        return GridExtents.maxFileBytes();
    }

    /**
     * Identifying name for this Grid. eg "conus" or ntv2_0.gsb
     */
    private String gridName;

    /**
     * URI for accessing the grid definition file
     */
    private String fileName;

    /**
     * File format of the grid definition file, ie "ctable2", "ntv1", "ntv2", "gtiff" or "missing"
     */
    private String format;

    /** Name of the {@link ResourceResolver} that produced the bytes. Provenance, not identity. */
    private transient String resolverName;

    /** Accounted heap cost of the parsed node data, including subgrids. */
    private transient long sizeBytes;

    private int gridOffset; // Offset in file of the grid definition, for delayed loading

    final static int MAX_TRY = 9; // maximum number of iterations for nad conversion algorithm
    final static double TOL = 1e-12; // tolerance for nad conversion algorithm

    ConversionTable table;

    private Grid next;
    private Grid child;

    /**
     * PROJ's {@code isNullGrid()}. Only {@code +nadgrids=null} (or {@code @null}) produces one, and
     * it is <strong>not</strong> a grid that happens to be empty: upstream's
     * {@code HorizontalShiftGridSet::gridAt} ({@code 9.8.1:src/grids.cpp:2775-2779}) returns it for
     * <em>every</em> point without consulting an extent at all, and {@code pj_hgrid_apply}
     * ({@code :3522-3524}) then returns the input unchanged. So a null grid anywhere in the list
     * makes the whole list total, and no point can be outside it.
     */
    private boolean nullGrid;

    /**
     * Splits a {@code +nadgrids=} / {@code +geoidgrids=} list and refuses one that is too long.
     *
     * <p>Shared with {@link VerticalGrid} so the two lists cannot acquire different limits.
     *
     * @throws IOException if the list carries more than {@link #MAX_GRID_TOKENS} tokens
     */
    static String[] splitTokens(String parameter, String spec) throws IOException {
        String[] tokens = spec.split(",");
        if (tokens.length > MAX_GRID_TOKENS) {
            throw new IOException("+" + parameter + "= names " + tokens.length
                    + " grids, which exceeds the limit of " + MAX_GRID_TOKENS
                    + ". Every token is a full pass down the resolver chain, and the longest list in"
                    + " PROJ 9.8.1 or in this repository is 6.");
        }
        return tokens;
    }

    /**
     * Merge (append) a named grid into the given gridlist.
     */
    // This method corresponds to the pj_gridlist_merge_gridfile function in proj.4
    public static void mergeGridFile(
            String name,
            List<Grid> gridList)
            throws IOException {
        gridList.add(resolveAndLoad(name));
    }

    /**
     * Locates {@code name} through the resolver chain and returns the parsed grid, loading it at most
     * once per (resolver, name) pair for the life of the JVM.
     */
    static Grid resolveAndLoad(String name) throws IOException {
        if (name == null || name.isEmpty()) {
            throw new IOException("Empty grid name");
        }
        // PROJ's own special case: "null" is a valid grid that shifts nothing.
        if (name.equals("null")) {
            return nullGrid();
        }
        // Refused here, before the chain, not only inside each resolver. Every resolver applies
        // ResourceNames itself, so this changes no answer -- what it changes is the cost and the
        // retention: a name that cannot resolve under any resolver should not walk the chain and
        // should not earn an entry in the resolution memo. See ResourceNames for the rule.
        ResourceNames.Rule violation = ResourceNames.violation(name);
        if (violation != null) {
            throw new IOException("Refusing grid name \"" + name + "\": " + violation.description()
                    + " (" + violation + ")");
        }
        ChainedResourceResolver chain = ResourceResolvers.resolver();
        final ResourceHandle handle = chain.resolve(name);
        if (handle == null) {
            throw new IOException("Unknown grid: " + name + ". Resolution chain was " + chain.name()
                    + "; the working directory is deliberately not searched.");
        }
        ResourceResolver owner = chain.resolverOf(name);
        String resolverName = owner == null ? "unknown" : owner.name();
        final String requested = name;
        final String origin = handle.origin();
        final String resolver = resolverName;
        return GridCache.instance().get(resolverName, name, new GridCache.Loader<Grid>() {
            @Override
            public Grid load() throws IOException {
                return parse(requested, origin, resolver,
                        Resources.readAll(handle, maxGridFileBytes()));
            }
        });
    }

    private static Grid nullGrid() {
        Grid grid = new Grid();
        grid.gridName = "null";
        grid.format = "missing";
        grid.fileName = "builtin:null";
        grid.resolverName = "builtin";
        grid.gridOffset = 0;
        grid.sizeBytes = 0L;
        grid.nullGrid = true;
        return grid;
    }

    /**
     * Whether this is PROJ's built-in {@code null} grid — the one that covers the whole world and
     * shifts nothing. See {@link #nullGrid}.
     *
     * @return true only for the grid produced by {@code +nadgrids=null}
     */
    public boolean isNullGrid() {
        return nullGrid;
    }

    /**
     * Sniffs the format and parses. The dispatch order and the header tests are those of PROJ 9.8.1
     * {@code HorizontalShiftGridSet::open} ({@code src/grids.cpp}).
     *
     * <p><strong>An unrecognised file is an error.</strong> 1.4.3 returned a grid with
     * {@code format = "missing"} and {@code table == null}, which {@link #shift} then skipped — so
     * {@code +nadgrids=/etc/shadow} read the file, silently produced a no-op grid, and reported a
     * successful transform. That is a failure expressed as a plausible coordinate.
     */
    private static Grid parse(String gridName, String origin, String resolverName, byte[] bytes)
            throws IOException {
        Grid grid = new Grid();
        grid.gridName = gridName;
        grid.fileName = origin;
        grid.resolverName = resolverName;
        grid.format = "missing";
        grid.gridOffset = 0;

        byte[] header = new byte[160];
        int headerLength = Math.min(header.length, bytes.length);
        System.arraycopy(bytes, 0, header, 0, headerLength);

        if (NTV1.testHeader(header) && headerLength == header.length) {
            grid.format = "ntv1";
            DataInputStream in = Resources.asDataStream(bytes);
            grid.table = NTV1.init(in);
            NTV1.load(Resources.asDataStream(bytes), grid);
        } else if (CTABLEV2.testHeader(header)) {
            grid.format = "ctable2";
            grid.table = CTABLEV2.init(Resources.asDataStream(bytes));
            CTABLEV2.load(Resources.asDataStream(bytes), grid);
        } else if (NTV2.testHeader(header)) {
            grid.format = "ntv2";
            NTV2.loadAll(bytes, grid);
        } else if (isTiff(header, headerLength)) {
            grid.format = "gtiff";
            GeoTiffGrid.loadHorizontal(gridName, origin, resolverName, bytes, grid);
        } else {
            throw new IOException("Unrecognised horizontal grid format for grid " + gridName
                    + " at " + origin);
        }
        grid.sizeBytes = grid.computeSizeBytes();
        return grid;
    }

    /**
     * TIFF magic, per PROJ's {@code IsTIFF}: classic ({@code 42}) and BigTIFF ({@code 43}), in either
     * byte order. Delegated so that there is exactly one implementation of "is this a TIFF" in the
     * codebase and the horizontal and vertical dispatchers cannot drift apart.
     */
    private static boolean isTiff(byte[] header, int headerLength) {
        return GeoTiffDataset.isTiff(header, headerLength);
    }

    private long computeSizeBytes() {
        long total = table == null || table.cvs == null ? 0L : table.cvs.length * BYTES_PER_NODE;
        if (child != null) {
            total += child.computeSizeBytes();
        }
        if (next != null) {
            total += next.computeSizeBytes();
        }
        return total;
    }

    /** Accounted heap cost of this grid and its subgrids. */
    @Override
    public long sizeBytes() {
        return sizeBytes;
    }

    /** The name this grid was requested under, e.g. {@code "conus"}. */
    public String getGridName() {
        return gridName;
    }

    /** Where the bytes came from, e.g. {@code "classpath:proj4j-data/grids/conus"}. */
    public String getOrigin() {
        return fileName;
    }

    /** {@code "ctable2"}, {@code "ntv1"}, {@code "ntv2"}, {@code "gtiff"}, or {@code "missing"}. */
    public String getFormat() {
        return format;
    }

    /** Which {@link ResourceResolver} produced the bytes. Empty for the built-in null grid. */
    public String getResolverName() {
        return resolverName == null ? "" : resolverName;
    }

    /** Immutable list of this grid's direct subgrids, in file order. Empty for a single-grid file. */
    public List<Grid> getSubGrids() {
        if (child == null) {
            return Collections.emptyList();
        }
        List<Grid> out = new ArrayList<Grid>();
        for (Grid c = child; c != null; c = c.next) {
            out.add(c);
        }
        return Collections.unmodifiableList(out);
    }

    /** Total number of grids in this file, counting subgrids at any depth. */
    public int countGrids() {
        int n = 1;
        for (Grid c = child; c != null; c = c.next) {
            n += c.countGrids();
        }
        return n;
    }

    /** The grid's own extent, in radians, as {@code [west, south, east, north]}. */
    public double[] extentRadians() {
        if (table == null) {
            return new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        }
        return new double[]{
                table.ll.lam,
                table.ll.phi,
                table.ll.lam + (table.lim.lam - 1) * table.del.lam,
                table.ll.phi + (table.lim.phi - 1) * table.del.phi};
    }

    // Package-private wiring used by the multi-subgrid NTv2 reader.
    void describeAs(String gridName, String format, String origin, String resolverName) {
        this.gridName = gridName;
        this.format = format;
        this.fileName = origin;
        this.resolverName = resolverName;
    }

    void setChild(Grid c) {
        this.child = c;
    }

    void setNext(Grid n) {
        this.next = n;
    }

    Grid getChild() {
        return child;
    }

    Grid getNext() {
        return next;
    }

    /**
     * PROJ 9.8.1's {@code REL_TOLERANCE_HGRIDSHIFT} ({@code grids.cpp:2759}).
     *
     * <p>1.4.3 used {@code (|del.phi| + |del.lam|) / 10000}, i.e. {@code 1e-4} — <strong>ten times more
     * permissive than 9.8.1</strong>, inherited from PROJ 4's {@code pj_apply_gridshift.c}. Since the
     * containment test is exactly what decides <em>which grid in the list is used</em>, a tolerance ten
     * times too wide lets a point that PROJ hands to the next grid be extrapolated off the edge of this
     * one instead — silently, and by up to ten cell-widths' worth of tolerance.
     */
    private static final double REL_TOLERANCE_HGRIDSHIFT = 1e-5;

    /**
     * PROJ 9.8.1's grid-edge clamp window, {@code 10 * REL_TOLERANCE_HGRIDSHIFT}
     * ({@code pj_hgrid_interpolate}, {@code grids.cpp:3341-3364}).
     *
     * <p>A point in the outermost half-cell of a grid lands on cell index {@code -1} or {@code width-1}
     * and has no complete set of four surrounding nodes. Both PROJ and proj4j snap it onto the boundary
     * cell rather than refuse it — but 1.4.3's window was {@code 1e-11}, seven orders of magnitude
     * tighter than 9.8.1's {@code 1e-4}, so it refused points PROJ accepts. Measured on the shipped
     * {@code conus} (0.25&deg; cells, south edge 20&deg;N) with {@code cct +proj=hgridshift}: at
     * (100&deg;W, 19.999999&deg;N) &mdash; one micro-degree <em>below</em> the south edge, giving
     * {@code frct.phi = 0.999996} &mdash; PROJ returns {@code -100.000014233333 20.000382961118}, while
     * 1.4.3 returned {@code NaN}, which {@code shift} then turned into the input coordinate unchanged.
     * At (100&deg;W, 19.99998&deg;N), twenty micro-degrees out, PROJ refuses too.
     */
    private static final double EDGE_CLAMP = 10 * REL_TOLERANCE_HGRIDSHIFT;

    /**
     * Convert between this grid and WGS84, or vice versa if the <code>inverse</code> flag is set.
     *
     * <h4>Selection order</h4>
     * <p>The grid list is walked <strong>in {@code +nadgrids=} order and the first grid whose extent
     * contains the point wins</strong>, then the subgrid hierarchy is descended to the deepest child
     * that also contains it. That is precisely {@code findGrid} ({@code 9.8.1:src/grids.cpp:3251-3262})
     * over {@code HorizontalShiftGridSet::gridAt} ({@code :2775-2789}) over
     * {@code HorizontalShiftGrid::gridAt} ({@code :2761-2772}), so proj4j and PROJ pick the same grid
     * for the same point.
     *
     * <p><strong>Measured, because the ordering has been misdiagnosed before.</strong> With
     * {@code +nadgrids=@conus,@alaska,@ntv2_0.gsb,@ntv1_can.dat} — the {@code NAD27} list — at Ottawa
     * (45.4225&deg;N, 75.6972&deg;W), PROJ 9.8.1 uses {@code conus}, not {@code ntv1_can.dat}:
     * {@code cct +proj=hgridshift} with the full list returns {@code -75.6969038853 45.4225388843},
     * <em>bit-identical</em> to the same call with {@code +grids=conus} alone, while
     * {@code +grids=ntv1_can.dat} gives {@code -75.6968722104 45.4225511829} — 2.47 m away. So the
     * ~2.5 m is the {@code conus}-versus-{@code ntv1_can} <em>data</em> difference inside the overlap,
     * which upstream also has; it is not a proj4j selection defect, and reordering the list to put
     * {@code ntv1_can.dat} first would move proj4j <em>away</em> from PROJ. {@code conus} spans
     * 131&deg;W&ndash;63&deg;W, 20&deg;N&ndash;50&deg;N (read from the shipped bytes), which is why it
     * reaches Ottawa, Toronto and Montreal at all.
     *
     * <p>What <em>was</em> wrong, and is fixed here, is the containment predicate: the tolerance was
     * {@code 1e-4} instead of {@link #REL_TOLERANCE_HGRIDSHIFT}, and there was no antimeridian
     * handling. The latter is not academic — {@code us_noaa_alaska.tif} declares
     * {@code west = -194}&deg;, so every point in western Alaska needs the &plusmn;2&pi; wrap of
     * {@code isPointInExtent} ({@code grids.cpp:3689-3704}) to be found inside it at all.
     *
     * <h4>Outside every grid is an error, not a zero shift</h4>
     *
     * <p>When no grid in a non-empty list can supply a value, this method throws
     * {@link CrsTransformException} with {@link ErrorCause#COORDINATE_OUTSIDE_GRID}. 1.4.3 returned
     * the coordinate <em>unchanged</em>, which is bit-indistinguishable from "the shift was zero"
     * — a transform that reports success and returns its input. That is the single failure mode
     * this project exists to eliminate, and it is not a small one here: a NAD27 round trip
     * straddling the southern edge of {@code ntv1_can.dat} (40&deg;N) came back 30&ndash;110 m
     * displaced, because the forward leg found the point inside the grid and shifted it a few tens
     * of metres <em>south</em> of the edge, and the return leg then silently did nothing.
     *
     * <p>Upstream agrees in both shapes this covers, and they are different code paths:
     * <ul>
     *   <li><b>no grid contains the point</b> — {@code pj_hgrid_apply} ({@code grids.cpp:3517-3520})
     *       calls {@code findGrid}, gets {@code nullptr} and sets
     *       {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_GRID};</li>
     *   <li><b>a grid contains it but interpolation cannot produce a value</b> —
     *       {@code pj_hgrid_apply_internal} returns {@code HUGE_VAL} and {@code pj_hgrid_apply}
     *       ({@code :3534-3536}) sets the same error.</li>
     * </ul>
     *
     * <p><strong>The {@code @}-optional wart is untouched.</strong> "The grid file is absent" and
     * "the point is outside a grid that loaded" are different questions and only the first is
     * legitimately silent: a missing {@code @}-prefixed grid is still discarded by
     * {@link #fromNadGrids} (and reported through {@link #describeNadGrids}), and an
     * <em>empty</em> list is still a no-op here, exactly as every upstream operator's
     * {@code if (!grids.empty())} guard requires. What changes is only the case where grids
     * <em>were</em> loaded and none of them covers the point.
     *
     * <h4>The fall-through to the next grid is KEPT — deliberately, and it is now observable</h4>
     *
     * <p>PROJ commits to the grid {@code findGrid} selected: if interpolation inside it fails, that
     * is the answer, and the next grid in {@code +nadgrids=} is never consulted (the one exception
     * is upstream's mid-iteration {@code findGrid} in the <em>inverse</em> loop at
     * {@code :3452-3471}, which re-selects a grid for an iterate that has wandered out — a
     * different mechanism). proj4j instead continues its loop. That divergence was a conscious
     * choice, is documented in {@code packaging-and-data.md}, and is <strong>not reversed
     * here</strong>: reversing it is a separate behavioural change with its own row set, and it
     * would be wrong to smuggle it in under a fail-closed fix.
     *
     * <p>It is also strictly orthogonal to this fix, which is why keeping it is safe. The
     * fall-through can only ever return a value that <em>some grid the user listed</em> produced
     * for this point; it cannot invent one. The {@code else} branch could — and did. Keeping the
     * fall-through therefore makes this change as small as it can be: every row it still answers
     * is a row that does not move.
     *
     * @param grids   the resolved {@code +nadgrids=} list, in list order; an empty list is a no-op
     * @param inverse iterate the shift rather than applying it once
     * @param in      the coordinate, in radians, modified in place
     * @throws CrsTransformException {@link ErrorCause#COORDINATE_OUTSIDE_GRID} if the list is
     *                               non-empty and no grid in it can supply a value for this point
     */
    // This method corresponds to the pj_apply_gridshift function from proj.4
    public static void shift(List<Grid> grids, boolean inverse, ProjCoordinate in) {
        if (grids == null || grids.isEmpty()) {
            // Nothing was declared, or every token was @-optional and absent. Upstream's operators
            // all guard with `if (!grids.empty())` and pass the coordinate through; there is no
            // grid here to be outside of, so this is not COORDINATE_OUTSIDE_GRID.
            return;
        }
        if (nonFinite(in)) {
            return;
        }
        final double inLam = in.x;
        final double inPhi = in.y;
        boolean contained = false;
        boolean shifted = false;
        boolean total = false;
        try {
            // An indexed loop, not a for-each: the enhanced for over a List allocates an Iterator
            // per call, and this method runs once per coordinate per grid-shifted datum.
            for (int i = 0, n = grids.size(); i < n; i++) {
                int outcome = applyOne(grids.get(i), inLam, inPhi, inverse, in);
                if (outcome == TOTAL) {
                    total = true;
                    break;
                }
                if (outcome == SHIFTED) {
                    shifted = true;
                    break;
                }
                if (outcome == NO_VALUE) {
                    contained = true;
                }
            }
        } finally {
            restoreUnlessShifted(in, inLam, inPhi, shifted);
        }
        if (shifted || total) {
            return;
        }
        throw outsideGrid(names(grids), inLam, inPhi, inverse, contained);
    }

    /**
     * {@link #shift(List, boolean, ProjCoordinate)} over an array.
     *
     * <p>Same arithmetic, same selection order, same exceptions — the loop is the only difference.
     * It exists because a caller that applies one transform to a whole batch can hold the resolved
     * grids as a {@code Grid[]} and get an array load per iteration instead of an interface
     * dispatch through {@code List.get}; {@code BasicCoordinateTransform}'s bulk path does exactly
     * that. Neither form allocates.
     *
     * @param grids   the resolved grid list, in list order; null or empty is a no-op
     * @param inverse iterate the shift rather than applying it once
     * @param in      the coordinate, in radians, modified in place
     * @throws CrsTransformException {@link ErrorCause#COORDINATE_OUTSIDE_GRID} if the list is
     *                               non-empty and no grid in it can supply a value for this point
     * @since 1.5
     */
    public static void shift(Grid[] grids, boolean inverse, ProjCoordinate in) {
        if (grids == null || grids.length == 0) {
            return;
        }
        if (nonFinite(in)) {
            return;
        }
        final double inLam = in.x;
        final double inPhi = in.y;
        boolean contained = false;
        boolean shifted = false;
        boolean total = false;
        try {
            for (int i = 0; i < grids.length; i++) {
                int outcome = applyOne(grids[i], inLam, inPhi, inverse, in);
                if (outcome == TOTAL) {
                    total = true;
                    break;
                }
                if (outcome == SHIFTED) {
                    shifted = true;
                    break;
                }
                if (outcome == NO_VALUE) {
                    contained = true;
                }
            }
        } finally {
            restoreUnlessShifted(in, inLam, inPhi, shifted);
        }
        if (shifted || total) {
            return;
        }
        throw outsideGrid(names(grids), inLam, inPhi, inverse, contained);
    }

    /**
     * {@code grids.cpp:3411-3412}, {@code "if (in.lam == HUGE_VAL) return in"}. A non-finite
     * horizontal position has no cell; the non-finiteness travels rather than becoming an
     * exception, so a NaN in still gives a NaN out.
     */
    private static boolean nonFinite(ProjCoordinate in) {
        return Double.isNaN(in.x) || Double.isInfinite(in.x)
                || Double.isNaN(in.y) || Double.isInfinite(in.y);
    }

    /**
     * Puts the caller's coordinate back the way it was found unless a grid actually produced a
     * value.
     *
     * <p>{@code in} doubles as the scratch space {@link #nad_cvt} and {@link #nad_intr} write
     * their intermediates into — that is what makes this path allocation-free — so on any path
     * that does not end in a shift it holds arithmetic debris rather than the input. The previous
     * implementation could not have this problem because its scratch was three freshly allocated
     * {@link PolarCoordinate}s per call; the price of removing them is this one line, in a
     * {@code finally} so that it also covers {@link ConvergenceFailureException} unwinding out of
     * the inverse iteration. The observable contract is unchanged and is the 1.4.3 one: after a
     * failed shift the coordinate is exactly as it was passed in.
     */
    private static void restoreUnlessShifted(ProjCoordinate in, double inLam, double inPhi,
                                             boolean shifted) {
        if (!shifted) {
            in.x = inLam;
            in.y = inPhi;
        }
    }

    /** {@link #applyOne} found no table, or the point is outside this grid's extent. */
    private static final int MISS = 0;
    /** The point is inside this grid, but no value could be interpolated for it. */
    private static final int NO_VALUE = 1;
    /** {@code out.x} / {@code out.y} hold the shifted coordinate. */
    private static final int SHIFTED = 2;
    /** PROJ's built-in null grid: covers everything, shifts nothing. */
    private static final int TOTAL = 3;

    /**
     * One grid of the list: the extent test, the subgrid descent, and the conversion.
     *
     * <p>Factored out of the two {@code shift} overloads so that the arithmetic exists once. The
     * outcome is an {@code int} rather than a pair of booleans or an object because
     * <em>"contained but produced nothing"</em> and <em>"did not contain it"</em> are different
     * answers to {@link #outsideGrid} and this method must not allocate to say so.
     *
     * @param out receives the shifted coordinate when the result is {@link #SHIFTED}, and is used
     *            as scratch otherwise
     */
    private static int applyOne(Grid grid, double inLam, double inPhi, boolean inverse,
                                ProjCoordinate out) {
        if (grid.nullGrid) {
            // HorizontalShiftGridSet::gridAt returns the null grid for any point at all, and
            // pj_hgrid_apply then returns the input. It shifts nothing and covers everything,
            // so it must short-circuit rather than fall through to the outside-grid error.
            return TOTAL;
        }
        ConversionTable table = grid.table;
        // don't shift if the grid is invalid
        // https://github.com/OSGeo/PROJ/blob/5.2.0/src/pj_gridlist.c#L88
        if (table == null) return MISS;
        // Skip tables that don't match our point at all
        if (!isPointInExtent(inLam, inPhi, table)) return MISS;

        // If we have child nodes, check to see if any of them apply
        while (grid.child != null) {
            Grid child;
            for (child = grid.child; child != null; child = child.next) {
                ConversionTable t = child.table;
                if (t == null) continue;
                if (!isPointInExtent(inLam, inPhi, t)) continue;
                break;
            }

            if (child == null) break;

            grid = child;
        }

        // Use the table of whichever grid the descent above settled on. 1.4.3 kept using the
        // *parent* table here, so a matched NTv2 subgrid was located and then ignored -- the finer
        // child values were never applied.
        table = grid.table;
        if (table == null || table.cvs == null) {
            return NO_VALUE;
        }

        nad_cvt(inLam, inPhi, inverse, table, out);

        return Double.isNaN(out.x) ? NO_VALUE : SHIFTED;
    }

    /**
     * Builds the {@link ErrorCause#COORDINATE_OUTSIDE_GRID} report.
     *
     * <p>The message distinguishes the two upstream shapes, because they mean different things to
     * whoever reads it: "no grid covers this point" is a data-coverage problem, while "a grid
     * covers it but no value could be interpolated" is a grid-edge or hole-in-the-subgrids problem.
     * Both are the same {@link ErrorCause}, matching PROJ's single
     * {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_GRID}.
     *
     * <p>Angles are formatted with {@link ProjectionMath#radToDeg} rather than
     * {@code Math.toDegrees}, which is not bit-stable across Java versions.
     */
    private static String names(List<Grid> grids) {
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < grids.size(); i++) {
            if (i > 0) names.append(", ");
            names.append(grids.get(i).gridName);
        }
        return names.toString();
    }

    private static String names(Grid[] grids) {
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < grids.length; i++) {
            if (i > 0) names.append(", ");
            names.append(grids[i].gridName);
        }
        return names.toString();
    }

    private static CrsTransformException outsideGrid(String names, double inLam, double inPhi,
                                                     boolean inverse, boolean contained) {
        String lon = String.valueOf(
                ProjectionMath.radToDeg(ProjectionMath.normalizeLongitude(inLam)));
        String lat = String.valueOf(ProjectionMath.radToDeg(inPhi));
        return new CrsTransformException(ErrorCause.COORDINATE_OUTSIDE_GRID,
                (inverse ? "inverse " : "") + "grid shift: (" + lon + ", " + lat + ") is "
                        + (contained
                            ? "inside the extent of a grid of [" + names + "] but no value could be"
                                    + " interpolated for it"
                            : "outside every grid of [" + names + "]"));
    }

    /**
     * PROJ's {@code isPointInExtent} ({@code 9.8.1:src/grids.cpp:3689-3704}) with the epsilon
     * {@code HorizontalShiftGridSet::gridAt} passes it.
     *
     * <p>Three things this replaced a plain four-sided box test with, in upstream's order:
     * <ol>
     *   <li>latitude first, so a wrap of longitude is never attempted for a point at the wrong
     *       latitude;</li>
     *   <li>a short circuit for a grid spanning the whole world in longitude
     *       ({@code east - west + resX >= 2}&pi; within {@code 1e-10}), which is how a global grid
     *       such as {@code tests/test_hgrid_tiled.tif} accepts every meridian including its own
     *       seam;</li>
     *   <li>a &plusmn;2&pi; shift of the longitude before the east/west test, which is what lets a
     *       grid declared in a shifted longitude window — {@code us_noaa_alaska.tif} starts at
     *       {@code -194}&deg; — contain a point given in &minus;180&hellip;180.</li>
     * </ol>
     *
     * @param lam   longitude, radians
     * @param phi   latitude, radians
     * @param table the candidate grid's table
     * @return whether PROJ would consider the point inside this grid
     */
    private static boolean isPointInExtent(double lam, double phi, ConversionTable table) {
        double resX = Math.abs(table.del.lam);
        double resY = Math.abs(table.del.phi);
        double eps = (resX + resY) * REL_TOLERANCE_HGRIDSHIFT;
        double west = table.ll.lam;
        double south = table.ll.phi;
        double east = west + (table.lim.lam - 1) * table.del.lam;
        double north = south + (table.lim.phi - 1) * table.del.phi;
        if (!(phi + eps >= south && phi - eps <= north)) {
            return false;
        }
        // ExtentAndRes::fullWorldLongitude(). Every proj4j horizontal grid is geographic, so
        // upstream's isGeographic guard is unconditionally true here.
        if (east - west + resX >= 2 * Math.PI - 1e-10) {
            return true;
        }
        double x = lam;
        if (x + eps < west) {
            x += 2 * Math.PI;
        } else if (x - eps > east) {
            x -= 2 * Math.PI;
        }
        return x + eps >= west && x - eps <= east;
    }

    // This class corresponds to the CTABLE struct from proj.4
    public static final class ConversionTable implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * ASCII info
         */
        public String id;
        /**
         * Cell size
         */
        public PolarCoordinate del;
        /**
         * Lower left corner coordinates
         */
        public PolarCoordinate ll;
        /**
         * Size of conversion matrix (number of rows/columns)
         */
        public IntPolarCoordinate lim;
        /**
         * Conversion matrix
         */
        public FloatPolarCoordinate[] cvs;

        /**
         * Memoised {@link #hashCode()}. {@code 0} means "not computed yet", the
         * {@code String.hash} idiom: the race is benign because every thread computes the same
         * value from the same deeply-immutable state, and a value that genuinely hashes to zero
         * costs a recomputation rather than a wrong answer. {@code transient} so a deserialised
         * table re-derives rather than trusting a number written by another JVM.
         */
        private transient int hash;

        @Override
        public String toString() {
            return String.format("Grid: %s", id);
        }

        /**
         * {@inheritDoc}
         *
         * <p>Two defects fixed here, both of them measurable.
         *
         * <p><b>The combiner was {@code |}, not a hash.</b> Bitwise OR is monotone in every bit:
         * once a bit is set no later term can clear it, so combining four 32-bit terms this way
         * drives the result towards {@code 0xFFFFFFFF} and destroys most of the information each
         * term carried. Over the grids this repository ships the old expression collapses to a
         * handful of distinct values — {@code GridHashDistributionTest} measures it rather than
         * asserting it — while the {@code 31}-chain below, the same one
         * {@code Projection.hashCode} uses, separates them. A collapsing hash is not a correctness
         * bug on its own; it becomes one the moment a {@code HashMap} keyed on a grid degenerates
         * to a linked list, which on a million-node table is the difference between a lookup and a
         * scan.
         *
         * <p><b>It was O(number of nodes), every call.</b> {@link #equals} short-circuits on
         * reference identity — with {@link GridCache} interning parsed grids that is the common
         * case — but a hash has no argument to compare against and so paid
         * {@code Arrays.hashCode(cvs)} over every node on every call: about 1.06 million elements
         * for the shipped {@code ntv1_can.dat}. The value is memoised in {@link #hash} instead,
         * which is sound because a parsed table is never mutated afterwards.
         *
         * <p>The fields are exactly {@link #equals}'s fields — {@code id}, {@code del}, {@code ll},
         * {@code cvs}. {@code lim} is deliberately absent from both.
         */
        @Override
        public int hashCode() {
            int h = hash;
            if (h == 0) {
                h = 17;
                h = 31 * h + (id == null ? 0 : id.hashCode());
                h = 31 * h + (del == null ? 0 : del.hashCode());
                h = 31 * h + (ll == null ? 0 : ll.hashCode());
                h = 31 * h + Arrays.hashCode(cvs);
                hash = h;
            }
            return h;
        }

        @Override
        public boolean equals(Object that) {
            // Identity short-circuit. With GridCache interning the parsed grids, equal grid lists are
            // the *same* objects, so Datum.isEqual no longer descends into Arrays.equals over
            // millions of nodes on every point.
            if (this == that) return true;
            if (that instanceof ConversionTable) {
                ConversionTable ct = (ConversionTable) that;
                if (id == null && ct.id != null) return false;
                if (!id.equals(ct.id)) return false;
                if (del == null && ct.del != null) return false;
                if (!del.equals(ct.del)) return false;
                if (ll == null && ct.ll != null) return false;
                if (!ll.equals(ct.ll)) return false;
                if (!Arrays.equals(cvs, ct.cvs)) return false;
                return true;
            } else {
                return false;
            }
        }
    }

    /**
     * {@code nad_cvt}, rewritten to keep every intermediate in a local {@code double}.
     *
     * <p>The arithmetic is unchanged, statement for statement and in the same order — including
     * the compound assignments in the inverse iteration, whose left-hand side is read before the
     * right-hand side is evaluated, and including the {@code do/while (i-- > 0)} that gives
     * {@code MAX_TRY + 1 = 10} trips. What changed is the storage: it used to build a
     * {@link PolarCoordinate} for {@code tb}, one for {@code del}, one for {@code dif} and one per
     * {@link #nad_intr} call — up to about 49 objects per inverted point, i.e. 4.9 million for one
     * 100,000-vertex geometry, on a path the consumer runs per row inside a Spark executor.
     *
     * <p>{@code out} carries the result and doubles as scratch. It is the caller's own coordinate;
     * {@link #restoreUnlessShifted} puts it back if this returns without a value or throws.
     *
     * @param out receives the converted coordinate in {@code x}/{@code y}; {@code x} is
     *            {@code NaN} when no value could be produced
     */
    // This method corresponds to the nad_cvt function in proj.4
    private static void nad_cvt(double inLam, double inPhi, boolean inverse, ConversionTable table,
                                ProjCoordinate out) {
        if (Double.isNaN(inLam)) {
            out.x = inLam;
            out.y = inPhi;
            return;
        }

        double tbLam = inLam - table.ll.lam;
        final double tbPhi = inPhi - table.ll.phi;
        tbLam = ProjectionMath.normalizeLongitude(tbLam - Math.PI) + Math.PI;
        nad_intr(tbLam, tbPhi, table, out);
        double tLam = out.x;
        double tPhi = out.y;

        if (inverse) {
            double difLam = Double.NaN;
            double difPhi = Double.NaN;
            int i = MAX_TRY;
            boolean converged = false;
            boolean atGridEdge = false;

            // out already holds (NaN, NaN) from nad_intr's reject path, which is what the caller
            // reads as "no value".
            if (Double.isNaN(tLam)) return;
            tLam = tbLam + tLam;
            tPhi = tbPhi - tPhi;

            do {
                nad_intr(tLam, tPhi, table, out);
                final double delLam = out.x;
                final double delPhi = out.y;
                if (Double.isNaN(delLam)) {
                    // PROJ logs "Inverse grid shift iteration failed, presumably at grid edge.
                    // Using first approximation." and proceeds with the current iterate. Ported
                    // verbatim: this is a documented approximation, not a silent failure, and it is
                    // reachable at every grid boundary.
                    atGridEdge = true;
                    break;
                }
                difLam = tLam - delLam - tbLam;
                tLam -= difLam;
                difPhi = tPhi + delPhi - tbPhi;
                tPhi -= difPhi;
                // PROJ 9.8.1 tests the squared 2-norm of the residual (grids.cpp,
                // pj_hgrid_apply_internal): both components must be within tolerance. 1.4.3 tested
                // `|dif.lam| > TOL && |dif.phi| > TOL`, which stops as soon as *either* component
                // converges and therefore declares success with the other still unconverged.
                if (difLam * difLam + difPhi * difPhi <= TOL * TOL) {
                    converged = true;
                    break;
                }
            } while (i-- > 0);

            if (!converged && !atGridEdge) {
                // Fail closed. 1.4.3 set both ordinates to NaN here, which Grid.shift then treated as
                // "no table covered this point" and turned into the input coordinate, unchanged --
                // a numerical failure delivered as a plausible answer.
                throw new ConvergenceFailureException(
                        "Inverse grid shift did not converge for grid '" + table.id + "' at ("
                                + ProjectionMath.radToDeg(ProjectionMath.normalizeLongitude(inLam))
                                + ", " + ProjectionMath.radToDeg(inPhi) + ") after "
                                + (MAX_TRY + 1) + " iterations; last residual was ("
                                + difLam + ", " + difPhi + ") rad against a tolerance of " + TOL);
            }
            out.x = ProjectionMath.normalizeLongitude(tLam + table.ll.lam);
            out.y = tPhi + table.ll.phi;
        } else {
            if (Double.isNaN(tLam)) {
                // out already holds nad_intr's (NaN, NaN).
                return;
            }
            out.x = inLam - tLam;
            out.y = inPhi + tPhi;
        }
    }

    /**
     * {@code nad_intr}: bilinear interpolation of the four nodes around the point, with PROJ's
     * outermost-half-cell clamp.
     *
     * <p>Writes {@code (NaN, NaN)} into {@code out} for every reject path, which is exactly what
     * the returned {@code val} carried before — the NaN <em>is</em> the signal, and it must stay
     * one rather than becoming a boolean, because genuinely NaN node data has to reach the callers
     * by the same route it always did.
     */
    // This method corresponds to the nad_intr method in proj.4
    private static void nad_intr(double tLam, double tPhi, ConversionTable table,
                                 ProjCoordinate out) {
        out.x = Double.NaN;
        out.y = Double.NaN;
        final double scaledLam = tLam / table.del.lam;
        final double scaledPhi = tPhi / table.del.phi;
        int indxLam = (int) Math.floor(scaledLam);
        int indxPhi = (int) Math.floor(scaledPhi);
        double frctLam = scaledLam - indxLam;
        double frctPhi = scaledPhi - indxPhi;
        double m00, m10, m01, m11;
        FloatPolarCoordinate f00, f10, f01, f11;
        int index;
        int next;

        if (indxLam < 0) {
            if (indxLam == -1 && frctLam > 1 - EDGE_CLAMP) {
                ++indxLam;
                frctLam = 0d;
            } else {
                return;
            }
        } else if ((next = indxLam + 1) >= table.lim.lam) {
            if (next == table.lim.lam && frctLam < EDGE_CLAMP) {
                --indxLam;
                frctLam = 1d;
            } else {
                return;
            }
        }
        if (indxPhi < 0) {
            if (indxPhi == -1 && frctPhi > 1 - EDGE_CLAMP) {
                ++indxPhi;
                frctPhi = 0d;
            } else {
                return;
            }
        } else if ((next = indxPhi + 1) >= table.lim.phi) {
            if (next == table.lim.phi && frctPhi < EDGE_CLAMP) {
                --indxPhi;
                frctPhi = 1d;
            } else {
                return;
            }
        }
        index = indxPhi * table.lim.lam + indxLam;
        f00 = table.cvs[index++];
        f10 = table.cvs[index];
        index += table.lim.lam;
        f11 = table.cvs[index--];
        f01 = table.cvs[index];
        m11 = m10 = frctLam;
        m00 = m01 = 1d - frctLam;
        m11 *= frctPhi;
        m01 *= frctPhi;
        frctPhi = 1d - frctPhi;
        m00 *= frctPhi;
        m10 *= frctPhi;
        out.x = m00 * f00.lam + m10 * f10.lam + m01 * f01.lam + m11 * f11.lam;
        out.y = m00 * f00.phi + m10 * f10.phi + m01 * f01.phi + m11 * f11.phi;
    }


    // This method corresponds to the pj_gridlist_from_nadgrids function in proj.4
    public static List<Grid> fromNadGrids(String grids) throws IOException {
        List<Grid> gridlist = new ArrayList<Grid>();
        // No global lock. 1.4.3 wrapped this whole method -- including blocking I/O -- in
        // `synchronized (Grid.class)`, so every CRS construction in the JVM serialised on one
        // monitor to redo a parse it had already done. GridCache now handles the mutual exclusion,
        // per grid, without holding a lock across the read.
        for (String gridName : splitTokens("nadgrids", grids)) {
            boolean optional = gridName.startsWith("@");
            if (optional) gridName = gridName.substring(1);
            try {
                mergeGridFile(gridName, gridlist);
            } catch (IOException e) {
                // PROJ's `@` prefix means *optional*, and silently skipping is correct PROJ
                // behaviour (grids.cpp, getListOfGridSets: any failure on an @-prefixed grid clears
                // the errno and continues). Reproduced, not "fixed" -- but made visible through
                // describeNadGrids(), so a caller can ask which grids were skipped and why instead
                // of inferring it from a coordinate.
                if (!optional) throw e;
            }
        }
        return gridlist;
    }

    /**
     * What each token of a {@code +nadgrids=} string resolved to. One entry per token, in order.
     * <p>
     * This is the introspection channel for PROJ's {@code @}-optional wart. Given
     * {@code +nadgrids=@missing,conus}, {@link #fromNadGrids} returns one grid and says nothing about
     * the other; this method reports both, so "silently skipped" becomes "skipped, and here is the
     * reason".
     *
     * @throws InvalidValueException if the list exceeds {@link #MAX_GRID_TOKENS} tokens. Unchecked
     *                               rather than {@code IOException} only because this method does
     *                               not declare one; it is the same refusal
     *                               {@link #fromNadGrids} makes, at the same limit.
     */
    public static List<GridRef> describeNadGrids(String grids) {
        List<GridRef> refs = new ArrayList<GridRef>();
        if (grids == null) {
            return refs;
        }
        String[] tokens;
        try {
            tokens = splitTokens("nadgrids", grids);
        } catch (IOException e) {
            throw new InvalidValueException(e.getMessage());
        }
        for (String token : tokens) {
            boolean optional = token.startsWith("@");
            String name = optional ? token.substring(1) : token;
            try {
                Grid g = resolveAndLoad(name);
                refs.add(new GridRef(name, optional, g, null));
            } catch (IOException e) {
                refs.add(new GridRef(name, optional, null, e.getMessage()));
            } catch (RuntimeException e) {
                refs.add(new GridRef(name, optional, null, String.valueOf(e.getMessage())));
            }
        }
        return Collections.unmodifiableList(refs);
    }

    /** One {@code +nadgrids=} token and its resolution outcome. Immutable. */
    public static final class GridRef {
        private final String name;
        private final boolean optional;
        private final Grid grid;
        private final String skipReason;

        GridRef(String name, boolean optional, Grid grid, String skipReason) {
            this.name = name;
            this.optional = optional;
            this.grid = grid;
            this.skipReason = skipReason;
        }

        /** The grid name with any {@code @} stripped. */
        public String name() {
            return name;
        }

        /** True iff the token carried PROJ's {@code @} optional prefix. */
        public boolean isOptional() {
            return optional;
        }

        /** True iff the grid was found and parsed. */
        public boolean isAvailable() {
            return grid != null;
        }

        /** The parsed grid, or {@code null} if it was skipped. */
        public Grid grid() {
            return grid;
        }

        /** Why it was skipped, or {@code null} if it was not. */
        public String skipReason() {
            return skipReason;
        }

        @Override
        public String toString() {
            if (grid != null) {
                return (optional ? "@" : "") + name + " -> " + grid.format + " from "
                        + grid.getResolverName() + " (" + grid.fileName + ")";
            }
            return (optional ? "@" : "") + name + " -> SKIPPED"
                    + (optional ? " (optional)" : " (REQUIRED)") + ": " + skipReason;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Three defects fixed here; see {@link ConversionTable#hashCode()} for the two it shares.
     *
     * <p><b>{@code childHash} was computed from {@code next}, not from {@code child}.</b> The line
     * read {@code int childHash = next == null ? 0 : next.hashCode();}. So {@code next} was mixed
     * in twice, {@code child} was never mixed in at all, and two grids differing only in their
     * subgrid hierarchy — which is exactly how an NTv2 or GeoTIFF parent differs from a bare one —
     * hashed identically while {@link #equals} correctly reported them different.
     * {@code GridHashDistributionTest} pins that pair.
     *
     * <p><b>The combiner was {@code |}.</b> See {@link ConversionTable#hashCode()}.
     *
     * <p>This walks {@code child} and {@code next}, so it terminates only if the subgrid graph is
     * a tree. It is, and that is now enforced rather than accidental — see
     * {@code GeoTiffGrid.insertIntoHierarchy} and {@code GeoTiffSelfCycleTest}.
     */
    @Override
    public int hashCode() {
        int h = 17;
        h = 31 * h + (gridName == null ? 0 : gridName.hashCode());
        h = 31 * h + (fileName == null ? 0 : fileName.hashCode());
        h = 31 * h + (format == null ? 0 : format.hashCode());
        h = 31 * h + (table == null ? 0 : table.hashCode());
        h = 31 * h + (next == null ? 0 : next.hashCode());
        h = 31 * h + (child == null ? 0 : child.hashCode());
        return h;
    }

    @Override
    public boolean equals(Object that) {
        // Identity short-circuit: GridCache interns parsed grids, so this is the common case and it
        // keeps Datum.isEqual off the O(grid size) array comparison on the per-point path.
        if (this == that) return true;
        if (that instanceof Grid) {
            Grid g = (Grid) that;
            if (gridName == null && g.gridName != null) return false;
            if (gridName != null && !gridName.equals(g.gridName)) return false;
            if (fileName == null && g.fileName != null) return false;
            if (fileName != null && !fileName.equals(g.fileName)) return false;
            if (format == null && g.format != null) return false;
            if (format != null && !format.equals(g.format)) return false;
            if (table == null && g.table != null) return false;
            if (table != null && !table.equals(g.table)) return false;
            if (next == null && g.next != null) return false;
            if (next != null && !next.equals(g.next)) return false;
            if (child == null && g.child != null) return false;
            if (child != null && !child.equals(g.child)) return false;
            return true;
        } else {
            return false;
        }
    }

    @Override
    public String toString() {
        return "Grid[" + gridName + "; " + format + "]";
    }
}

/*
 * Copyright 2026 The Proj4J Contributors.
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
package org.locationtech.proj4j.pipeline;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.datum.Grid;

/**
 * A resolved {@code +grids=} / {@code +xy_grids=} list, plus the two things PROJ
 * does with one: {@code pj_hgrid_apply} and {@code pj_hgrid_value}
 * ({@code 9.8.1:src/grids.cpp:3401-3588}).
 *
 * <h2>The two operations are not the same, and the difference is a sign</h2>
 *
 * <p>Both walk the same grid list and both bilinearly interpolate the same two
 * channels, but they pass a different {@code compensateNTConvention} to
 * {@code valueAt}:
 *
 * <ul>
 * <li><b>{@code pj_hgrid_apply}</b> ({@code compensateNTConvention = true}) is the
 *     datum shift. It negates the stored longitude offset — the NTv1/NTv2/CTable2
 *     formats all store longitude <em>positive west</em> — and adds the result to
 *     the coordinate. The inverse direction iterates, because a grid shift has no
 *     closed-form inverse.</li>
 * <li><b>{@code pj_hgrid_value}</b> ({@code compensateNTConvention = false}) is a
 *     plain table read, used by {@code +proj=deformation} to fetch an east/north
 *     velocity pair out of a grid whose two channels are not longitude and latitude
 *     offsets at all. It must <em>not</em> negate, or every east velocity would come
 *     back with the wrong sign.</li>
 * </ul>
 *
 * <h2>How {@code pj_hgrid_value} is obtained here</h2>
 *
 * <p>{@link Grid#shift} is proj4j's port of {@code pj_hgrid_apply}, including the
 * subgrid descent and the iterative inverse, and it is exercised by the whole
 * {@code +nadgrids} population of the corpus. What it does <em>not</em> expose is the
 * interpolated offset pair, because {@code Grid.ConversionTable} is reachable only
 * from {@code org.locationtech.proj4j.datum}.
 *
 * <p>So the pair is recovered from the forward shift algebraically. For the raw,
 * un-negated interpolated offsets {@code (dlam, dphi)}, {@code Grid.shift}'s forward
 * branch computes
 *
 * <blockquote>{@code out.lam = in.lam - dlam}, &nbsp; {@code out.phi = in.phi + dphi}</blockquote>
 *
 * <p>({@code Grid.nad_cvt}'s {@code else} branch), so
 * {@code dlam = in.lam - out.lam} and {@code dphi = out.phi - in.phi} — which is
 * exactly {@code compensateNTConvention = false}. This is exact arithmetic for
 * {@code dphi} and loses at most one rounding of {@code in.lam} for {@code dlam};
 * on the corpus's alaska grid that is about 2e-16 rad, i.e. 1.6 nm on the ground,
 * against a 0.1 mm tolerance.
 *
 * <p><b>The recovery is only valid once the point is known to be inside a grid</b>,
 * because {@code Grid.shift} leaves the coordinate untouched when no table covers it
 * — which the subtraction would report as an offset of exactly zero. That is the
 * shape of the worst defect this project has on record: a failure delivered as a
 * plausible coordinate. So {@link #covers} is asked <em>first</em>, and a point no
 * grid covers throws {@link ErrorCause#COORDINATE_OUTSIDE_GRID}.
 *
 * <h2>One deliberate, reported deviation</h2>
 *
 * <p>PROJ's {@code findGrid} accepts a point up to
 * {@code (resX + resY) * REL_TOLERANCE_HGRIDSHIFT} outside a grid's declared extent,
 * and {@code Grid.shift}'s own skip test uses the equivalent
 * {@code (|del.lam| + |del.phi|) / 10000}. {@link #covers} cannot reproduce either,
 * because the cell size is not reachable from this package —
 * {@link Grid#extentRadians()} gives the corners but not the resolution. Containment
 * is therefore tested exactly. The consequence is narrow and stated rather than
 * hidden: a point within one ten-thousandth of a cell <em>outside</em> a grid edge is
 * refused here where PROJ would interpolate it. Reproducing PROJ exactly needs a
 * public accessor for {@code Grid.ConversionTable} — see the class comment's note in
 * the report.
 *
 * <p>Immutable after construction; {@link #apply} and {@link #value} keep all state
 * in locals, so one instance is safe to share.
 *
 * @since 1.5
 */
final class HorizontalGrids {

    private final String spec;
    private final List<Grid> grids;

    private HorizontalGrids(final String spec, final List<Grid> grids) {
        this.spec = spec;
        this.grids = grids;
    }

    /**
     * {@code pj_hgrid_init} ({@code grids.cpp:3294}) followed by upstream's own
     * error handling: a token that fails to resolve is fatal <b>unless</b> it carries
     * the {@code @} optional prefix, in which case
     * {@code getListOfGridSets} clears the errno and carries on. Both halves are
     * {@link Grid#fromNadGrids}'s behaviour already.
     *
     * <p>An <em>empty</em> resulting list is not an error: every operator that takes
     * a grid list guards with {@code if (!grids.empty())} and passes the coordinate
     * through unchanged otherwise. That is reachable whenever every token was
     * {@code @}-optional and absent.
     *
     * @param spec the comma-separated list as written
     * @param key  the parameter name, for the error message
     * @return the resolved list, possibly empty
     * @throws PipelineDefinitionException {@code FILE_NOT_FOUND} if a required grid is
     *                                     missing, unreadable or in a format proj4j
     *                                     cannot parse
     */
    static HorizontalGrids open(final String spec, final String key) {
        if (spec == null || spec.isEmpty()) {
            throw new PipelineDefinitionException(PipelineErrorCode.MISSING_ARG,
                    "+" + key + " parameter missing.");
        }
        final List<Grid> resolved;
        try {
            resolved = Grid.fromNadGrids(spec);
        } catch (final IOException e) {
            throw new PipelineDefinitionException(PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID,
                    "+" + key + "=" + spec + ": could not find required grid(s): "
                            + e.getMessage(), e);
        }
        return new HorizontalGrids(spec,
                Collections.unmodifiableList(new ArrayList<Grid>(resolved)));
    }

    /** @return whether no grid resolved at all, in which case every operation is the identity. */
    boolean isEmpty() {
        return grids.isEmpty();
    }

    /** @return the {@code +grids=} list as written. */
    String spec() {
        return spec;
    }

    /** @return the resolved grids, in list order; unmodifiable. */
    List<Grid> grids() {
        return grids;
    }

    /**
     * {@code findGrid} ({@code grids.cpp:3252-3263}), reduced to the question
     * {@code pj_hgrid_apply} and {@code pj_hgrid_value} both ask before doing
     * anything: is there a grid here at all?
     *
     * <p>Only the top-level grids are tested. A subgrid's extent is contained in its
     * parent's by construction in every format proj4j reads, so a point inside a
     * subgrid is inside its parent, and the descent to the finest covering subgrid is
     * {@link Grid#shift}'s job.
     *
     * @param lam longitude, radians
     * @param phi latitude, radians
     * @return whether some grid's extent contains the point
     */
    boolean covers(final double lam, final double phi) {
        if (Double.isNaN(lam) || Double.isNaN(phi)) {
            return false;
        }
        for (int i = 0; i < grids.size(); i++) {
            final double[] e = grids.get(i).extentRadians();
            if (Double.isNaN(e[0])) {
                // A null grid ("+nadgrids=null"): it shifts nothing but it does cover
                // everything, exactly as PROJ's isNullGrid() branch returns the input.
                return true;
            }
            if (lam >= e[0] && lam <= e[2] && phi >= e[1] && phi <= e[3]) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code pj_hgrid_apply} ({@code grids.cpp:3508}): the datum shift itself,
     * applied in place to {@code coord[0]} and {@code coord[1]}.
     *
     * @param coord   {@code {lam, phi, z, t}} in radians; only the first two change
     * @param inverse {@code PJ_INV} rather than {@code PJ_FWD}, i.e. iterate
     * @throws CrsTransformException {@link ErrorCause#COORDINATE_OUTSIDE_GRID} when no
     *                              grid covers the point
     */
    void apply(final double[] coord, final boolean inverse) {
        if (grids.isEmpty()) {
            return;
        }
        if (!isFinite(coord[0]) || !isFinite(coord[1])) {
            // grids.cpp:3413 - "if (in.lam == HUGE_VAL) return in". A non-finite
            // horizontal position has no grid cell; the non-finiteness travels rather
            // than becoming an exception, so that a NaN in gives a NaN out as a result.
            return;
        }
        if (!covers(coord[0], coord[1])) {
            throw outsideGrid(coord[0], coord[1]);
        }
        final ProjCoordinate pc = new ProjCoordinate(coord[0], coord[1]);
        Grid.shift(grids, inverse, pc);
        coord[0] = pc.x;
        coord[1] = pc.y;
    }

    /**
     * {@code pj_hgrid_value} ({@code grids.cpp:3545}): the interpolated offset pair,
     * <b>not</b> negated for the positive-west convention. See the class comment for
     * how it is recovered.
     *
     * @param lam longitude, radians
     * @param phi latitude, radians
     * @return {@code {dlam, dphi}} in the grid's own stored units — radians for a
     *         datum grid, millimetres per year for a velocity grid
     * @throws CrsTransformException {@link ErrorCause#COORDINATE_OUTSIDE_GRID} when no
     *                              grid covers the point
     */
    double[] value(final double lam, final double phi) {
        if (grids.isEmpty()) {
            return new double[] {0.0, 0.0};
        }
        if (Double.isNaN(lam) || Double.isNaN(phi)) {
            return new double[] {Double.NaN, Double.NaN};
        }
        if (!covers(lam, phi)) {
            throw outsideGrid(lam, phi);
        }
        final ProjCoordinate pc = new ProjCoordinate(lam, phi);
        Grid.shift(grids, false, pc);
        return new double[] {lam - pc.x, pc.y - phi};
    }

    private CrsTransformException outsideGrid(final double lam, final double phi) {
        return new CrsTransformException(ErrorCause.COORDINATE_OUTSIDE_GRID,
                "(" + Math.toDegrees(lam) + ", " + Math.toDegrees(phi)
                        + ") is outside every grid of +grids=" + spec);
    }

    private static boolean isFinite(final double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    @Override
    public String toString() {
        return "HorizontalGrids[" + spec + ", " + grids.size() + " resolved]";
    }
}

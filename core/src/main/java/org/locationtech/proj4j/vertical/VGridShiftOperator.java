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
package org.locationtech.proj4j.vertical;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.datum.VerticalGrid;
import org.locationtech.proj4j.gie.GieIoUnits;
import org.locationtech.proj4j.pipeline.PipelineOperator;

/**
 * {@code +proj=vgridshift} — a vertical grid shift, ported from
 * {@code 9.8.1:src/transformations/vgridshift.cpp}.
 *
 * <h2>What this is for</h2>
 *
 * <p>This is the step PROJ hides behind {@code +geoidgrids=}. {@code cs2cs_emulation_setup}
 * ({@code 9.8.1:src/create.cpp:88-105}) builds
 * <blockquote>{@code break_cs2cs_recursion proj=vgridshift grids=<list>}</blockquote>
 * and stores it as {@code P-&gt;vgridshift}; {@code fwd_prepare} then invokes it
 * <em>forward</em>, right after the datum shift and before the {@code lam0} subtraction
 * ({@code fwd.cpp:96-99}, comment "Go orthometric from geometric"), and
 * {@code inv_finalize} invokes it <em>inverse</em>, before the datum shift on the way back
 * ({@code inv.cpp:117-119}, "Go geometric from orthometric").
 *
 * <h2>The sign, which is the one thing easy to get backwards</h2>
 *
 * <p>{@code +multiplier} defaults to <b>{@code -1}</b>, not {@code +1}
 * ({@code vgridshift.cpp:206}, whose comment reads "historical: the forward direction
 * subtracts the grid offset"). The forward direction is
 * {@code z += value(lam, phi) * multiplier}, so with the default it is
 * {@code H = h - N}: an ellipsoidal height becomes an orthometric one. The inverse is
 * {@code z -= value * multiplier}. Both spellings are preserved verbatim rather than
 * folded into one signed add, because the multiplier also participates in the nodata test
 * and {@link VerticalGrid#valueAt(double, double, double)} applies it there.
 *
 * <p>PROJ's CRS-based path uses {@code +multiplier=1} for the same grid in the opposite
 * direction — {@code projinfo -s EPSG:4326+5773 -t EPSG:4979} emits
 * {@code +proj=vgridshift +grids=us_nga_egm96_15.tif +multiplier=1} — so the multiplier is
 * genuinely part of the operation's identity and is exposed here.
 *
 * <h2>An empty grid list is not an error</h2>
 *
 * <p>{@code pj_vgridshift_forward_3d} guards with {@code if (!Q-&gt;grids.empty())} and
 * otherwise "just pass the coordinate through unchanged". That is reachable: a
 * {@code +geoidgrids} list all of whose entries carry the {@code @} optional prefix
 * resolves to nothing at all, and PROJ builds a working, identity-valued operator from it.
 * {@link VerticalGrid#fromGeoidGrids(String)} already implements the same skip.
 *
 * <h2>Failure is a throw, never a sentinel</h2>
 *
 * <p>PROJ returns {@code HUGE_VAL} and sets {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_GRID} or
 * {@code _GRID_AT_NODATA}. {@link VerticalGrid#valueAt} returns {@code NaN} for both, and
 * cannot distinguish them — so the two are separated here the way PROJ separates them, by
 * asking {@link VerticalGrid#covers(double, double)} first. That is exactly upstream's
 * split between {@code VerticalShiftGridSet::gridAt} (the extent test) and
 * {@code read_vgrid_value} (the interpolation, which clamps rather than refuses).
 *
 * <p>Not thread-safe only in the sense that {@link #forward}/{@link #inverse} mutate the
 * array handed to them; the operator itself is immutable and the grids are immutable after
 * parsing, so one instance may be shared.
 *
 * @since 1.5
 */
public final class VGridShiftOperator implements PipelineOperator {

    /** {@code vgridshift.cpp:206} — {@code Q-&gt;forward_multiplier = -1.0}. */
    public static final double DEFAULT_MULTIPLIER = -1.0;

    private final String gridSpec;
    private final List<VerticalGrid> grids;
    private final double forwardMultiplier;

    private VGridShiftOperator(final String gridSpec, final List<VerticalGrid> grids,
                               final double forwardMultiplier) {
        this.gridSpec = gridSpec;
        this.grids = grids;
        this.forwardMultiplier = forwardMultiplier;
    }

    /**
     * Resolve a {@code +grids=} / {@code +geoidgrids=} list through the deterministic
     * resolver chain and build the operator.
     *
     * @param gridSpec   a comma-separated grid list, {@code @}-prefixed entries optional
     * @param multiplier {@code +multiplier}; pass {@link #DEFAULT_MULTIPLIER} for PROJ's default
     * @return the operator; never {@code null}
     * @throws CrsTransformException with {@link ErrorCause#MISSING_GRID} if a required grid
     *                              cannot be resolved or parsed
     */
    public static VGridShiftOperator fromGrids(final String gridSpec, final double multiplier) {
        if (gridSpec == null || gridSpec.isEmpty()) {
            throw new CrsTransformException(ErrorCause.MISSING_PARAM,
                    "+proj=vgridshift needs +grids=");
        }
        final List<VerticalGrid> resolved;
        try {
            resolved = VerticalGrid.fromGeoidGrids(gridSpec);
        } catch (final IOException e) {
            // vgridshift.cpp:228-232: "could not find required grid(s)" is
            // PROJ_ERR_INVALID_OP_FILE_NOT_FOUND_OR_INVALID, raised at *construction*, which is
            // the whole point - a missing grid must not become a per-row surprise.
            throw new CrsTransformException(ErrorCause.MISSING_GRID,
                    "+grids=" + gridSpec + ": " + e.getMessage(), e);
        }
        return new VGridShiftOperator(gridSpec,
                Collections.unmodifiableList(new ArrayList<VerticalGrid>(resolved)), multiplier);
    }

    /** @return the same operator with PROJ's default {@code +multiplier=-1}.
     *  @param gridSpec a comma-separated grid list */
    public static VGridShiftOperator fromGrids(final String gridSpec) {
        return fromGrids(gridSpec, DEFAULT_MULTIPLIER);
    }

    /**
     * The grids actually resolved, in list order.
     *
     * @return unmodifiable, possibly empty when every entry was {@code @}-optional and absent
     */
    public List<VerticalGrid> grids() {
        return grids;
    }

    /** @return the {@code +grids=} list as written. */
    public String gridSpec() {
        return gridSpec;
    }

    /** @return {@code +multiplier}. */
    public double multiplier() {
        return forwardMultiplier;
    }

    /**
     * {@code pj_vgridshift_forward_3d}: {@code z += value * multiplier}.
     *
     * @param coord {@code {lam, phi, z, t}} in radians and metres, mutated in place
     */
    @Override
    public void forward(final double[] coord) {
        if (grids.isEmpty()) {
            return;
        }
        coord[2] += valueAt(coord[0], coord[1]);
    }

    /**
     * {@code pj_vgridshift_reverse_3d}: {@code z -= value * multiplier}.
     *
     * @param coord {@code {lam, phi, z, t}} in radians and metres, mutated in place
     */
    @Override
    public void inverse(final double[] coord) {
        if (grids.isEmpty()) {
            return;
        }
        coord[2] -= valueAt(coord[0], coord[1]);
    }

    /**
     * {@code pj_vgrid_value}: the first grid whose extent contains the point wins, and a
     * point no grid contains is an error rather than a zero shift.
     *
     * @param lam longitude, radians
     * @param phi latitude, radians
     * @return the interpolated value, already multiplied by {@code +multiplier}
     */
    private double valueAt(final double lam, final double phi) {
        if (Double.isNaN(lam) || Double.isNaN(phi)) {
            // read_vgrid_value's first act: "do not deal with NaN coordinates". PROJ returns
            // HUGE_VAL, which fwd_prepare then propagates; a NaN horizontal position simply has
            // no height, so the NaN travels rather than becoming an exception.
            return Double.NaN;
        }
        for (int i = 0; i < grids.size(); i++) {
            final VerticalGrid grid = grids.get(i);
            if (!grid.covers(lam, phi)) {
                continue;
            }
            final double value = grid.valueAt(lam, phi, forwardMultiplier);
            if (Double.isNaN(value)) {
                throw new CrsTransformException(ErrorCause.GRID_NODATA,
                        "every node surrounding (" + Math.toDegrees(lam) + ", "
                                + Math.toDegrees(phi) + ") in vertical grid "
                                + grid.getGridName() + " is nodata");
            }
            return value;
        }
        throw new CrsTransformException(ErrorCause.COORDINATE_OUTSIDE_GRID,
                "(" + Math.toDegrees(lam) + ", " + Math.toDegrees(phi)
                        + ") is outside every grid of +grids=" + gridSpec);
    }

    /** {@code P-&gt;left = PJ_IO_UNITS_RADIANS} ({@code vgridshift.cpp:250}). */
    @Override
    public GieIoUnits declaredLeft() {
        return GieIoUnits.RADIANS;
    }

    /** {@code P-&gt;right = PJ_IO_UNITS_RADIANS} ({@code vgridshift.cpp:251}). */
    @Override
    public GieIoUnits declaredRight() {
        return GieIoUnits.RADIANS;
    }

    /** Never called: this operator declares {@code RADIANS} on both sides, not {@code WHATEVER}. */
    @Override
    public void overrideUnits(final GieIoUnits left, final GieIoUnits right) {
        // no-op, deliberately
    }

    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public String description() {
        return "vgridshift grids=" + gridSpec
                + (forwardMultiplier == DEFAULT_MULTIPLIER ? "" : " multiplier=" + forwardMultiplier);
    }

    @Override
    public String toString() {
        return "VGridShiftOperator[" + description() + ", " + grids.size() + " grid(s) resolved]";
    }
}

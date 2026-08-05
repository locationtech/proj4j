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

import org.locationtech.proj4j.gie.GieIoUnits;

/**
 * {@code +proj=hgridshift} — a horizontal grid shift, ported from
 * {@code 9.8.1:src/transformations/hgridshift.cpp}. The sibling of
 * {@link org.locationtech.proj4j.vertical.VGridShiftOperator}.
 *
 * <h2>What this is for</h2>
 *
 * <p>This is the step PROJ hides behind {@code +nadgrids=}.
 * {@code cs2cs_emulation_setup} ({@code 9.8.1:src/create.cpp:107-124}) builds
 * <blockquote>{@code break_cs2cs_recursion proj=hgridshift grids=<list>}</blockquote>
 * and stores it as {@code P-&gt;hgridshift}; the forward pass invokes it as part of the
 * datum shift, and — the detail that catches people — <b>{@code +nadgrids} suppresses
 * the {@code +towgs84} Helmert entirely</b>, because {@code pj_datum_set}'s
 * {@code towgs84} branch is an {@code else if}.
 *
 * <h2>Direction, which is the opposite of what the name suggests</h2>
 *
 * <p>{@code pj_hgridshift_forward_3d} calls {@code pj_hgrid_apply(..., PJ_FWD)}, and
 * {@code PJ_FWD} is the <em>closed-form</em> direction: interpolate once at the input
 * position and add. It is the <em>inverse</em> that iterates, because the shift is
 * defined at the source coordinates and solving for them is a fixed-point problem.
 * {@link org.locationtech.proj4j.datum.Grid#shift} already implements both halves,
 * including the 10-iteration limit and the "presumably at grid edge, using first
 * approximation" fallback that upstream documents.
 *
 * <h2>An empty grid list is not an error</h2>
 *
 * <p>{@code pj_hgridshift_forward_3d} guards with {@code if (!Q-&gt;grids.empty())} and
 * "just pass the coordinate through unchanged" otherwise — reachable whenever every
 * {@code +grids=} token carried the {@code @} optional prefix and none resolved.
 * A missing <em>required</em> grid, by contrast, is
 * {@code PROJ_ERR_INVALID_OP_FILE_NOT_FOUND_OR_INVALID} raised at construction:
 * a grid that cannot be found must never become a per-row no-op that reports success.
 *
 * <h2>{@code +t_epoch} / {@code +t_final}</h2>
 *
 * <p>Handled by {@link TimeGatedOperator}, which wraps this class rather than being
 * folded into it, because the bracket is character-for-character the same in
 * {@code vgridshift} and upstream's own comment asks for it to be shared.
 *
 * <p>Immutable after construction; safe to share.
 *
 * @since 1.5
 */
final class HGridShiftOperator implements PipelineOperator {

    private final HorizontalGrids grids;

    private HGridShiftOperator(final HorizontalGrids grids) {
        this.grids = grids;
    }

    /**
     * Build the operator, resolving {@code +grids=} through the deterministic
     * resolver chain.
     *
     * @param gridSpec a comma-separated grid list, {@code @}-prefixed entries optional
     * @return the operator; never {@code null}
     * @throws PipelineDefinitionException {@code MISSING_ARG} when {@code gridSpec} is
     *                                     absent, {@code FILE_NOT_FOUND_OR_INVALID}
     *                                     when a required grid cannot be resolved
     */
    static HGridShiftOperator fromGrids(final String gridSpec) {
        return new HGridShiftOperator(HorizontalGrids.open(gridSpec, "grids"));
    }

    /** @return the {@code +grids=} list as written. */
    String gridSpec() {
        return grids.spec();
    }

    /** {@code P-&gt;left = PJ_IO_UNITS_RADIANS} ({@code hgridshift.cpp:154}). */
    @Override
    public GieIoUnits declaredLeft() {
        return GieIoUnits.RADIANS;
    }

    /** {@code P-&gt;right = PJ_IO_UNITS_RADIANS} ({@code hgridshift.cpp:155}). */
    @Override
    public GieIoUnits declaredRight() {
        return GieIoUnits.RADIANS;
    }

    /** Never called: neither declared side is {@link GieIoUnits#WHATEVER}. */
    @Override
    public void overrideUnits(final GieIoUnits left, final GieIoUnits right) {
        // no-op, deliberately
    }

    /** {@code pj_hgrid_apply(..., PJ_FWD)}: interpolate once and add. */
    @Override
    public void forward(final double[] coord) {
        grids.apply(coord, false);
    }

    /** {@code pj_hgrid_apply(..., PJ_INV)}: iterate to the fixed point. */
    @Override
    public void inverse(final double[] coord) {
        grids.apply(coord, true);
    }

    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public String description() {
        return "hgridshift grids=" + grids.spec();
    }

    @Override
    public String toString() {
        return "HGridShiftOperator[" + description() + ", " + grids.grids().size()
                + " grid(s) resolved]";
    }
}

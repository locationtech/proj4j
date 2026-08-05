/*
 * Copyright 2026 The Proj4J Contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.locationtech.proj4j.conformance.bridge;

import org.locationtech.proj4j.gie.GieDirection;
import org.locationtech.proj4j.gie.GieIoUnits;

/**
 * One gie {@code operation} (or {@code crs_src}/{@code crs_dst} pair), reduced
 * to what a corpus runner needs: can it run, in what units, and what does it
 * produce.
 *
 * <p>Instances are <em>not</em> thread-safe. {@link #transform} mutates
 * {@link #lastFailure()}, and the underlying proj4j {@code Projection} objects
 * are mutable (and, for {@code +proj=cass}, mutated on the hot path). Create one
 * per runner thread.
 *
 * <p><b>Everything here is radians-and-metres, never degrees.</b> The runner owns
 * the degree conversion that {@code gie}'s {@code torad_coord}/{@code todeg_coord}
 * perform; this interface does not repeat it. Feeding degrees into
 * {@link #transform} on a {@link GieIoUnits#RADIANS} side produces silent
 * nonsense.
 */
public interface GieOperation {

    /**
     * {@code true} when {@link #transform} may be called. When {@code false},
     * {@link #failure()} says why not and {@link #transform} always returns
     * {@code null}.
     */
    boolean isUsable();

    /**
     * The construction-time classification, or {@code null} when
     * {@link #isUsable()}.
     */
    GieFailure failure();

    /**
     * PROJ's {@code P->left} — the declared unit domain of the operation's
     * left-hand (angular, for a projection) side, <em>before</em>
     * {@link GieIoUnits#folded() folding} and <em>before</em> the {@code +inv}
     * swap. Feed it, {@link #rightUnits()} and {@link #isInverted()} to
     * {@link GieIoUnits#outputUnits} to pick the comparison metric.
     *
     * <p>For every {@code PROJ_HEAD} projection this is
     * {@link GieIoUnits#RADIANS} ({@code proj_internal.h:882-883}). For
     * {@code +proj=longlat} it is {@code RADIANS} on both sides.
     *
     * <p>An unusable operation still reports units, so a runner can format a
     * report without branching; treat them as unspecified in that case.
     */
    GieIoUnits leftUnits();

    /**
     * PROJ's {@code P->right}. For every {@code PROJ_HEAD} projection this is
     * {@link GieIoUnits#CLASSIC}, which {@link GieIoUnits#folded() folds} to
     * {@link GieIoUnits#PROJECTED} and therefore selects the Euclidean metric.
     * That single fact is why every forward projection row in the corpus is
     * compared in metres and every {@code direction inverse} row geodesically.
     */
    GieIoUnits rightUnits();

    /**
     * {@code P->inverted}: {@code +inv} appeared in the definition. Two
     * independent consequences, both already handled here — the unit sides swap
     * (see {@link GieIoUnits#pjLeft}), and {@link #transform} runs the opposite
     * of the requested direction ({@code proj_trans} negates {@code dir} when
     * {@code P->inverted}).
     */
    boolean isInverted();

    /**
     * gie's {@code T.crs_dst_is_lat_lon_or_y_x}, which triggers the ordinate
     * swap in the {@code DEGREES} branch of the comparator.
     *
     * <p><b>Known limitation: this always returns {@code false}.</b> proj4j
     * carries no axis-order metadata on a {@code CoordinateReferenceSystem} —
     * {@code Projection.axes} defaults to {@code AxisOrder.ENU} and is only ever
     * populated from an explicit {@code +axis=}, never from the EPSG database, so
     * there is nothing to read. The cost is confined to the handful of
     * {@code epsg_no_grid.gie} rows whose target CRS is latitude-first (e.g.
     * {@code EPSG:2393}, "Finland YKJ Northing, Easting"); those will be reported
     * as failures with a deviation of roughly one degree of arc rather than as
     * skips. Fixing it needs axis metadata in the EPSG module, which is out of
     * this bridge's scope.
     */
    boolean crsDstIsLatLonOrYX();

    /**
     * Run the operation on one coordinate.
     *
     * @param in  {@code {x, y, z, t}}; shorter arrays are zero-extended. On a
     *            {@link GieIoUnits#RADIANS} side, {@code x} is longitude in
     *            <em>radians</em> and {@code y} is latitude in radians.
     * @param dir the direction as written on the gie {@code direction} line.
     *            {@code +inv} is applied on top of this, not instead of it.
     * @return a fresh 4-element array, or {@code null} if this point failed — in
     *         which case {@link #lastFailure()} is set. Never returns a
     *         partially-valid coordinate: a failure is never expressed as a
     *         plausible number.
     *
     *         <p>A non-finite output is a {@link GieFailureKind#NUMERICAL}
     *         failure, because proj4j returns {@code NaN} silently in 62
     *         enumerated places. The one carve-out is PROJ's documented
     *         "when given NaNs, return NaNs" behaviour
     *         ({@code more_builtins.gie:791}): if the corresponding input
     *         ordinate was itself {@code NaN}, a {@code NaN} output is a
     *         <em>result</em> and is returned, so the comparator's
     *         NaN-both-sides branch can fire. Infinities are always failures.
     */
    double[] transform(double[] in, GieDirection dir);

    /**
     * The failure from the most recent {@link #transform} call, or {@code null}
     * if that call succeeded. Undefined before the first call.
     */
    GieFailure lastFailure();
}

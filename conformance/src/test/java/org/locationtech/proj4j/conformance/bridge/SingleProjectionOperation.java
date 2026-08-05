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

import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.gie.GieDirection;
import org.locationtech.proj4j.gie.GieIoUnits;
import org.locationtech.proj4j.proj.Projection;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * A gie {@code operation} that resolved to exactly one proj4j
 * {@link Projection}.
 *
 * <p><b>Radians in, radians out on the angular side.</b> Forward runs go through
 * {@code Projection.projectRadians}, inverse runs through
 * {@code Projection.inverseProjectRadians}. The runner owns the degree conversion
 * that gie's {@code torad_coord}/{@code todeg_coord} perform, so nothing here
 * converts degrees.
 *
 * <p>Two pieces of PROJ's <em>host</em> layer are reproduced here, because they
 * decide pass versus fail and proj4j has no equivalent:
 *
 * <ol>
 * <li>{@code fwd_prepare}'s angular pre-check ({@code 9.8.1:src/fwd.cpp:40-80}),
 *     gated on {@code INPUT_UNITS == PJ_IO_UNITS_RADIANS} — which for a
 *     projection means the forward direction only, since {@code inv.cpp} defines
 *     {@code INPUT_UNITS} as {@code P->right} and that is {@code CLASSIC}. It
 *     rejects {@code |phi| - pi/2 > 1e-12} and {@code |lambda| > 10} radians with
 *     {@code PROJ_ERR_COORD_TRANSFM_INVALID_COORD}, then <em>clamps</em>
 *     {@code phi} into {@code [-pi/2, pi/2]}. <b>244 of the corpus's
 *     {@code expect failure} assertions come from this check and from no
 *     projection's own logic</b>, so omitting it would turn 244 correct failures
 *     into wrong answers. Note the bound is 10 <em>radians</em> (about +/-573
 *     degrees), not 180 degrees — rejecting +/-180 would be stricter than PROJ.</li>
 * <li>A non-finite result is a failure, not a coordinate. proj4j returns
 *     {@code NaN} silently in dozens of places, and the whole point of the
 *     exercise is that a failure must never be expressed as a plausible number.
 *     The one carve-out is PROJ's documented "when given NaNs, return NaNs"
 *     ({@code more_builtins.gie:791}): if the corresponding input ordinate was
 *     itself {@code NaN}, the {@code NaN} output is returned so the comparator's
 *     NaN-both-sides branch can fire. Infinities are always failures.</li>
 * </ol>
 *
 * <p>Not thread-safe: proj4j's {@code Projection} objects are mutable, and
 * {@code +proj=cass} writes 17 instance fields on the hot path.
 */
final class SingleProjectionOperation implements GieOperation {

    /** {@code PJ_EPS_LAT}, in radians. */
    static final double EPS_LAT = 1e-12;

    /** {@code M_HALFPI}. */
    static final double HALF_PI = Math.PI / 2.0;

    /** {@code fwd_prepare}'s longitude bound, in radians. */
    static final double MAX_LAM = 10.0;

    private final String definition;
    private final Projection projection;
    private final GieIoUnits left;
    private final GieIoUnits right;
    private final boolean inverted;
    /**
     * Whether proj4j's projection speaks degrees on its non-angular side, which is
     * true exactly for the {@code longlat} family: {@code LongLatProjection}
     * installs {@code Units.DEGREES}, so {@code projectRadians} multiplies its
     * output by {@code RTD} and {@code inverseProjectRadians} expects degrees in.
     * PROJ's {@code longlat} is {@code RADIANS} on both sides, so the extra
     * conversion has to be undone.
     */
    private final boolean degreeSided;

    private GieFailure lastFailure;

    SingleProjectionOperation(String definition, Projection projection, GieIoUnits left,
            GieIoUnits right, boolean inverted, boolean degreeSided) {
        this.definition = definition;
        this.projection = projection;
        this.left = left;
        this.right = right;
        this.inverted = inverted;
        this.degreeSided = degreeSided;
    }

    /** The proj4j projection, for tests and reports. */
    Projection projection() {
        return projection;
    }

    @Override
    public boolean isUsable() {
        return true;
    }

    @Override
    public GieFailure failure() {
        return null;
    }

    @Override
    public GieIoUnits leftUnits() {
        return left;
    }

    @Override
    public GieIoUnits rightUnits() {
        return right;
    }

    @Override
    public boolean isInverted() {
        return inverted;
    }

    @Override
    public boolean crsDstIsLatLonOrYX() {
        // See GieOperation#crsDstIsLatLonOrYX: proj4j has no axis metadata.
        return false;
    }

    @Override
    public GieFailure lastFailure() {
        return lastFailure;
    }

    @Override
    public double[] transform(double[] in, GieDirection dir) {
        lastFailure = null;
        if (in == null || in.length < 2) {
            lastFailure = GieFailures.of(GieFailureKind.INVALID_COORD,
                    "a coordinate needs at least two ordinates");
            return null;
        }
        // proj_trans() negates the direction when P->inverted (src/trans.cpp).
        GieDirection eff = inverted ? dir.opposite() : dir;

        double x = in[0];
        double y = in[1];
        double z = in.length > 2 ? in[2] : 0.0;
        double t = in.length > 3 ? in[3] : 0.0;

        // fwd.cpp defines INPUT_UNITS as P->left; inv.cpp as P->right. The check
        // is on the raw enum value, so CLASSIC never triggers it.
        GieIoUnits inputUnits = eff == GieDirection.FORWARD ? left : right;
        if (eff == GieDirection.FORWARD && inputUnits == GieIoUnits.RADIANS) {
            if (x == Double.POSITIVE_INFINITY || y == Double.POSITIVE_INFINITY
                    || z == Double.POSITIVE_INFINITY) {
                lastFailure = GieFailures.of(GieFailureKind.INVALID_COORD,
                        "HUGE_VAL ordinate: fwd_prepare returns proj_coord_error()");
                return null;
            }
            if (Math.abs(y) - HALF_PI > EPS_LAT) {
                lastFailure = GieFailures.of(GieFailureKind.INVALID_COORD,
                        "Invalid latitude: |phi| - pi/2 = " + (Math.abs(y) - HALF_PI)
                                + " rad exceeds PJ_EPS_LAT (fwd.cpp:62)");
                return null;
            }
            if (x > MAX_LAM || x < -MAX_LAM) {
                lastFailure = GieFailures.of(GieFailureKind.INVALID_COORD,
                        "Invalid longitude: " + x + " rad is outside +/-10 (fwd.cpp:68)");
                return null;
            }
            // Clamp latitude into [-pi/2, pi/2] (fwd.cpp:75-78).
            if (y > HALF_PI) {
                y = HALF_PI;
            } else if (y < -HALF_PI) {
                y = -HALF_PI;
            }
        }

        ProjCoordinate src = new ProjCoordinate(x, y, z);
        ProjCoordinate dst = new ProjCoordinate();
        // Poison the destination so a projection that never writes it cannot pass
        // the input through as a plausible result.
        dst.x = Double.NaN;
        dst.y = Double.NaN;
        dst.z = Double.NaN;

        try {
            if (eff == GieDirection.FORWARD) {
                projection.projectRadians(src, dst);
                if (degreeSided) {
                    // LongLatProjection emitted degrees; PROJ's longlat is RADIANS.
                    dst.x *= ProjectionMath.DTR;
                    dst.y *= ProjectionMath.DTR;
                }
            } else {
                if (degreeSided) {
                    src.x *= ProjectionMath.RTD;
                    src.y *= ProjectionMath.RTD;
                }
                projection.inverseProjectRadians(src, dst);
            }
        } catch (Throwable e) {
            GieFailure f = Proj4jGieOperationFactory.mapTransformThrowable(e);
            if (f == null) {
                // Not ours to swallow (OutOfMemoryError and friends).
                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                }
                throw (Error) e;
            }
            lastFailure = f;
            return null;
        }

        double ox = dst.x;
        double oy = dst.y;

        // z: use what the operation WROTE if it wrote anything, else pass the input
        // through. The two cases are distinguishable only because dst.z was poisoned to
        // NaN above - a 2D operator leaves that poison in place, a 3D one overwrites it.
        //
        // This used to be an unconditional `double oz = z;`, with the comment "proj4j is
        // 2D: z and t pass through untouched, as PROJ's own 2D operators do." That was
        // true when written and is not any more: `GeocentProjection` is 3D-native,
        // `vgridshift` writes a geoid-corrected height, and `CartConversion` is a full
        // 3D port. Substituting the *input* z for a 3D operator's output silently
        // converts honest skips into wrong answers - measured at 6.35-6.38e6 m on the
        // `more_builtins.gie` cart block, which is the radius of the Earth, i.e. the
        // difference between a geocentric Z and a height above the ellipsoid.
        //
        // A 3D operator that genuinely emits a NaN z from finite input falls back to
        // pass-through here rather than reporting NaN. That is a numerical failure the
        // finiteness postcondition should already have caught upstream of this point, so
        // it is not silently absorbed; if it ever shows up, fix it there, not here.
        double oz = Double.isNaN(dst.z) ? z : dst.z;
        double ot = t;

        GieFailure nf = nonFinite(ox, x, "x");
        if (nf == null) {
            nf = nonFinite(oy, y, "y");
        }
        if (nf != null) {
            lastFailure = nf;
            return null;
        }
        return new double[] {ox, oy, oz, ot};
    }

    /**
     * A non-finite output ordinate is a {@link GieFailureKind#NUMERICAL} failure,
     * unless the matching input ordinate was itself {@code NaN} — PROJ's
     * documented "when given NaNs, return NaNs".
     */
    private GieFailure nonFinite(double out, double in, String which) {
        if (!Double.isNaN(out) && !Double.isInfinite(out)) {
            return null;
        }
        if (Double.isNaN(out) && Double.isNaN(in)) {
            return null;
        }
        return GieFailures.of(GieFailureKind.NUMERICAL,
                "non-finite " + which + " = " + out + " from finite input " + in
                        + " (" + definition + ")");
    }

    @Override
    public String toString() {
        return "SingleProjectionOperation[" + definition + ", left=" + left + ", right=" + right
                + (inverted ? ", inverted" : "") + "]";
    }
}

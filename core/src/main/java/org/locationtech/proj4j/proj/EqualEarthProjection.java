/*******************************************************************************
 * Copyright 2026
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

package org.locationtech.proj4j.proj;

import org.locationtech.proj4j.ConvergenceFailureException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.util.AuthalicLat;

/**
 * Equal Earth ({@code +proj=eqearth}), a port of
 * {@code 9.8.1:src/projections/eqearth.cpp}. Spherical <b>and</b> ellipsoidal.
 *
 * <p>Savric, Patterson and Jenny (2018),
 * <a href="https://doi.org/10.1080/13658816.2018.1504949">doi:10.1080/13658816.2018.1504949</a>.
 * Designed to look like Robinson while being genuinely equal-area, which Robinson is not.
 * It is the highest-payoff single projection outside the adams family.
 *
 * <h2>Construction</h2>
 *
 * <p>The map is a polynomial in an intermediate angle {@code psi} derived from the
 * <b>authalic</b> latitude, which is what makes it equal-area:
 *
 * <pre>
 *   sbeta = sin(phi)                                     (sphere)
 *   sbeta = q(sin phi) / qp,  clamped into [-1, 1]        (ellipsoid)
 *   psi   = asin(M * sbeta)               where M = sqrt(3)/2
 *   x     = rqda * lam * cos(psi) / (M * (A1 + 3 A2 psi^2 + psi^6 (7 A3 + 9 A4 psi^2)))
 *   y     = rqda * psi * (A1 + A2 psi^2 + psi^6 (A3 + A4 psi^2))
 * </pre>
 *
 * <p>with {@code rqda = 1} on a sphere and {@code sqrt(qp/2)} on an ellipsoid — the authalic
 * radius divided by the semi-major axis, which is how the equal-area property survives the
 * flattening.
 *
 * <p>The easting denominator is {@code dy/dpsi}, so the easting is
 * {@code lam cos(psi) / (M y'(psi))} — that quotient is exactly what forces area to be
 * preserved, and it is also reused verbatim as the inverse's Newton derivative.
 *
 * <h2>The clamp is only in the ellipsoidal branch, and it is not cosmetic</h2>
 *
 * <p>{@code eqearth.cpp:56-58} clamps {@code |sbeta| &gt; 1} to {@code +/-1} with the comment
 * "Rounding error." — and only after the {@code q/qp} division. At exactly
 * {@code phi = +/-90} the quotient can land a few ulps above 1, and {@code asin} of that is
 * {@code NaN}, which on a fail-closed contract becomes a thrown exception rather than a
 * pole. The corpus feeds {@code +/-90} directly ({@code more_builtins.gie:582-589}), so
 * without the clamp three rows per operation fail. On a sphere {@code sin(phi)} cannot
 * exceed 1, so no clamp is needed and upstream does not apply one — reproduced.
 *
 * <h2>Fail-closed: upstream returns {@code (0, 0)} on non-convergence</h2>
 *
 * <p>{@code eqearth.cpp:113-117} sets
 * {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN} and then
 * {@code return lp;} where {@code lp} is still its zero initialiser — so an unconverged
 * inverse answers <b>null island</b>, the single most plausible-looking wrong coordinate
 * available. Proj4J throws. Unreachable from the corpus (12 Newton steps on a monotone
 * polynomial converge in three or four everywhere inside {@code MAX_Y}), so this costs
 * nothing and removes a trap.
 *
 * <h2>Authalic latitude comes from the numerical core</h2>
 *
 * <p>{@link AuthalicLat} is the 9.8.1 framework — Karney's coefficient form with the
 * {@code |n| &lt; 0.01} series-validity gate — not the older closed-form
 * {@code ProjectionMath.authset}/{@code authlat} pair, which is deprecated. It is
 * constructed once in {@link #initialize()} and held, because
 * {@code pj_authalic_lat_compute_coeffs} is upstream's setup-time work too and doing it per
 * point would be both slow and a departure.
 */
public class EqualEarthProjection extends PseudoCylindricalProjection {

    private static final long serialVersionUID = 7341488845468976373L;

    private static final double A1 = 1.340264;
    private static final double A2 = -0.081106;
    private static final double A3 = 0.000893;
    private static final double A4 = 0.003796;

    /** {@code M = sqrt(3)/2} ({@code eqearth.cpp:28}). */
    private static final double M = Math.sqrt(3.0) / 2.0;

    /** {@code eqearth.cpp:30} — "90 degree latitude on a sphere with radius 1". */
    private static final double MAX_Y = 1.3173627591574;

    private static final double EPS = 1e-11;

    /** {@code eqearth.cpp:32}. Twelve, not the 100 the Flex Projector family uses. */
    private static final int MAX_ITER = 12;

    /** Authalic radius divided by the semi-major axis; exactly 1 on a sphere. */
    private double rqda = 1.0;

    /** Null on a sphere, where no authalic conversion is performed at all. */
    private AuthalicLat authalic;

    @Override
    public void initialize() {
        super.initialize();
        if (es != 0.0) {
            authalic = new AuthalicLat(es);
            rqda = Math.sqrt(0.5 * authalic.qp());
        } else {
            authalic = null;
            rqda = 1.0;
        }
    }

    /** {@code eqearth_e_forward}, {@code eqearth.cpp:42-75}. */
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate dst) {
        double sbeta = StrictMath.sin(phi);
        if (authalic != null) {
            sbeta = authalic.q(sbeta) / authalic.qp();
            // eqearth.cpp:56-58 "Rounding error."
            if (Math.abs(sbeta) > 1.0) {
                sbeta = sbeta > 0 ? 1.0 : -1.0;
            }
        }
        final double psi = StrictMath.asin(M * sbeta);
        final double psi2 = psi * psi;
        final double psi6 = psi2 * psi2 * psi2;

        dst.x = lam * StrictMath.cos(psi)
                / (M * (A1 + 3.0 * A2 * psi2 + psi6 * (7.0 * A3 + 9.0 * A4 * psi2)));
        dst.y = psi * (A1 + A2 * psi2 + psi6 * (A3 + A4 * psi2));

        dst.x *= rqda;
        dst.y *= rqda;
        return dst;
    }

    /**
     * {@code eqearth_e_inverse}, {@code eqearth.cpp:77-134}.
     *
     * <p>Note {@code cos(yc)} in the longitude, not {@code cos(psi)} — {@code yc} <em>is</em>
     * the solved {@code psi}, so the two are the same thing under a different name. Reading
     * it as the northing would be a units error.
     *
     * @throws ConvergenceFailureException where upstream returns {@code (0, 0)}
     */
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate dst) {
        x /= rqda;
        y /= rqda;

        if (y > MAX_Y) {
            y = MAX_Y;
        } else if (y < -MAX_Y) {
            y = -MAX_Y;
        }

        double yc = y;
        int i = MAX_ITER;
        double correction = Double.NaN;
        for (; i > 0; --i) {
            final double y2 = yc * yc;
            final double y6 = y2 * y2 * y2;
            final double f = yc * (A1 + A2 * y2 + y6 * (A3 + A4 * y2)) - y;
            final double fder = A1 + 3.0 * A2 * y2 + y6 * (7.0 * A3 + 9.0 * A4 * y2);
            correction = f / fder;
            yc -= correction;
            if (Math.abs(correction) < EPS) {
                break;
            }
        }
        if (i == 0) {
            throw new ConvergenceFailureException(this,
                    "inverse psi iteration did not converge to " + EPS + " within "
                            + MAX_ITER + " iterations for y = " + y + " (last correction "
                            + correction + ")");
        }

        final double y2 = yc * yc;
        final double y6 = y2 * y2 * y2;
        dst.x = M * x * (A1 + 3.0 * A2 * y2 + y6 * (7.0 * A3 + 9.0 * A4 * y2))
                / StrictMath.cos(yc);

        double phi = StrictMath.asin(StrictMath.sin(yc) / M);
        if (authalic != null) {
            phi = authalic.inverse(phi);
        }
        dst.y = phi;
        return dst;
    }

    public boolean hasInverse() {
        return true;
    }

    public boolean isEqualArea() {
        return true;
    }

    public String toString() {
        return "Equal Earth";
    }
}

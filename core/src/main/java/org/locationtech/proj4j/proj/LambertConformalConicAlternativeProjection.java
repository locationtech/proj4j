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

import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.MeridianArc;

/**
 * Lambert Conformal Conic Alternative, {@code +proj=lcca} &mdash; a port of
 * {@code 9.8.1:src/projections/lcca.cpp}. Ellipsoidal; the inverse is a Newton solve on a
 * truncated cubic.
 *
 * <p>The French Army Truncated Cubic Lambert conic, only <em>partially</em> conformal &mdash; the
 * legal projection for France from the late 1800s until 1948, and later used in Algeria, Tunisia,
 * Morocco and Syria. Upstream's own header says it should not be used for new work; it exists for
 * interoperability with historical data. It is <b>not</b>
 * {@link LambertConformalConicProjection} with different parameters: the meridional distance is
 * replaced by the cubic {@code S(1 + S^2 C)} about {@code +lat_0}, which is where the "truncated
 * cubic" comes from.
 *
 * <h2>Only {@code +lat_0} and {@code +k_0} are read</h2>
 *
 * <p>Despite being a conic, {@code lcca} reads neither {@code +lat_1} nor {@code +lat_2} &mdash;
 * {@code PROJ_HEAD} lists {@code lat_0=} alone. The corpus's single block supplies
 * {@code +lat_1=0.5 +lat_2=2} anyway and expects them to be ignored, which is worth knowing
 * before "fixing" the setup to consume them.
 *
 * <p>{@code +lat_0 == 0} is rejected with {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE}: the cone
 * degenerates and {@code r0 = N0/tan(phi0)} is infinite. The test is an exact equality upstream
 * and is kept as one.
 *
 * <h2>{@code k_0} is applied here, twice</h2>
 *
 * <p>The forward multiplies both ordinates by {@code P->k0} explicitly and the inverse divides by
 * it, so {@link Projection#scaleFactor} must be used in the kernel and not left to the affine
 * tail (which does not apply it).
 *
 * <h2>The inverse's convergence test is upstream's C idiom</h2>
 *
 * <p>{@code for (i = MAX_ITER; i; --i) { ...; if (fabs(dif) < DEL_TOL) break; } if (!i) error;}
 * &mdash; ten trips, and the error fires only when the loop ran out, i.e. {@code i} reached 0
 * without the {@code break}. Note this is the <em>opposite</em> of the {@code lsat} case recorded
 * in the skill, where the C leaves {@code i == -1} and the error branch is unreachable: here the
 * counter stops at 0 and {@code !0} is true, so exhaustion <em>is</em> reported.
 *
 * @see <a href="https://github.com/OSGeo/PROJ/blob/9.8.1/src/projections/lcca.cpp">9.8.1
 *      lcca.cpp</a>
 */
public class LambertConformalConicAlternativeProjection extends ConicProjection {

    private static final long serialVersionUID = 7763022898047662660L;

    private static final int MAX_ITER = 10;
    private static final double DEL_TOL = 1e-12;

    private MeridianArc meridian;
    private double r0;
    private double l;
    private double m0;
    private double cCoef;

    /** {@code fS}, {@code lcca.cpp:70-73}: the truncated cubic. */
    private static double fS(double s, double c) {
        return s * (1. + s * s * c);
    }

    /** {@code fSp}, {@code lcca.cpp:75-78}: its derivative. */
    private static double fSp(double s, double c) {
        return 1. + 3. * s * s * c;
    }

    /**
     * Port of {@code PJ_PROJECTION(lcca)} ({@code lcca.cpp:131-165}).
     *
     * @throws InvalidValueException if {@code +lat_0} is exactly 0
     */
    @Override
    public void initialize() {
        super.initialize();
        if (projectionLatitude == 0.) {
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                    "+proj=lcca requires +lat_0 != 0: the cone degenerates and r0 = N0/tan(lat_0) "
                            + "is infinite at the equator (lcca.cpp:142-146)");
        }
        meridian = MeridianArc.fromEs(es);
        l = FastStrictTrig.sin(projectionLatitude);
        m0 = meridian.mlfn(projectionLatitude, l, FastStrictTrig.cos(projectionLatitude));
        final double s2p0 = l * l;
        double bigR0 = 1. / (1. - es * s2p0);
        final double n0 = Math.sqrt(bigR0);
        bigR0 *= one_es * n0;
        final double tan0 = FastStrictTrig.tan(projectionLatitude);
        r0 = n0 / tan0;
        cCoef = 1. / (6. * bigR0 * n0);
    }

    /** {@code lcca_e_forward}, {@code lcca.cpp:80-93}. */
    @Override
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate xy) {
        final double s = meridian.mlfn(phi, FastStrictTrig.sin(phi), FastStrictTrig.cos(phi)) - m0;
        final double dr = fS(s, cCoef);
        final double r = r0 - dr;
        final double lamMulL = lam * l;
        xy.x = scaleFactor * (r * FastStrictTrig.sin(lamMulL));
        xy.y = scaleFactor * (r0 - r * FastStrictTrig.cos(lamMulL));
        return xy;
    }

    /**
     * {@code lcca_e_inverse}, {@code lcca.cpp:95-119}.
     *
     * @throws ProjectionException if the ten-trip Newton solve on the cubic does not reach
     *         {@code 1e-12}; upstream sets
     *         {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN}, which is why this is a
     *         domain error rather than a {@code ConvergenceFailureException}
     */
    @Override
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
        final double xx = x / scaleFactor;
        final double yy = y / scaleFactor;
        final double theta = StrictMath.atan2(xx, r0 - yy);
        final double dr = yy - xx * FastStrictTrig.tan(0.5 * theta);
        lp.x = theta / l;
        double s = dr;
        int i = MAX_ITER;
        for (; i != 0; --i) {
            final double dif = (fS(s, cCoef) - dr) / fSp(s, cCoef);
            s -= dif;
            if (Math.abs(dif) < DEL_TOL) {
                break;
            }
        }
        if (i == 0) {
            throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                    "lcca inverse: the truncated-cubic Newton solve did not reach " + DEL_TOL
                            + " in " + MAX_ITER + " iterations at (" + x + ", " + y
                            + ") (lcca.cpp:113-116)");
        }
        lp.y = meridian.invMlfn(s + m0);
        return lp;
    }

    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public String toString() {
        return "Lambert Conformal Conic Alternative";
    }
}

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
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Laborde, {@code +proj=labrd} &mdash; a port of
 * {@code 9.8.1:src/projections/labrd.cpp}. Ellipsoidal; the inverse iterates.
 *
 * <p>The oblique Mercator variant adopted for Madagascar: a conformal map onto a sphere through
 * the isometric latitude, an odd-power series in longitude, and then a <em>complex cubic</em>
 * post-rotation about the origin, which is what the {@code Ca}/{@code Cb} terms are.
 *
 * <h2>The complex correction is a cubic forward and a quintic inverse</h2>
 *
 * <p>The forward applies {@code Ca*V1 + Cb*V2} with {@code V1 = 3xy^2 - x^3} and
 * {@code V2 = y^3 - 3x^2 y} &mdash; i.e. the real and imaginary parts of a cubic. The inverse
 * undoes it with the cubic <em>and</em> two quintic terms {@code Cc}, {@code Cd}, so the two are
 * not exact mutual inverses; the residual is fifth order and below the corpus's 0.1 mm bar.
 * Reproduced as written rather than symmetrised.
 *
 * <h2>{@code +lat_0 == 0} is refused, and one corpus row asserts it</h2>
 *
 * <p>{@code builtins.gie:3422} is {@code +proj=labrd +ellps=GRS80 +lat_0=0} with
 * {@code expect failure errno invalid_op_illegal_arg_value}. The test is an exact equality on
 * {@code P->phi0} upstream and is kept as one.
 *
 * <h2>{@code +azi} is read but not yet dispatched</h2>
 *
 * <p>{@code Az = pj_param(P->ctx, P->params, "razi").f} rotates the complex correction by
 * {@code 2*Az}. {@code Proj4Parser} sends {@code +azi} to {@link SpilhausProjection} alone, so
 * {@link #setAziRadians(double)} is unreachable from a proj-string and {@code Az} is 0 &mdash;
 * which is what both corpus blocks use, so nothing is masked. Real Madagascar grids do set it.
 *
 * <h2>Where {@code k_0} appears</h2>
 *
 * <p>Only inside {@code kRg = k0 * sqrt(N*R)} and, in the inverse, in {@code d = Re * k0 * kRg}.
 * The kernels themselves never multiply by {@code k0} again, and neither does
 * {@link Projection}'s affine tail, so the two agree.
 *
 * @see <a href="https://github.com/OSGeo/PROJ/blob/9.8.1/src/projections/labrd.cpp">9.8.1
 *      labrd.cpp</a>
 */
public class LabordeProjection extends Projection {

    private static final long serialVersionUID = 4187492139519402805L;

    private static final double EPS = 1.e-10;
    private static final int MAX_ITER = 20;

    private double kRg;
    private double p0s;
    private double aCoef;
    private double cCoef;
    private double ca;
    private double cb;
    private double cc;
    private double cd;

    /** {@code Az}, {@code +azi} in radians. Upstream's absent value is 0. */
    private double aziRadians = 0.0;

    /**
     * {@code +azi}, the grid azimuth the complex correction is rotated by (as {@code 2*azi}).
     *
     * @param aziRadians the azimuth, in radians
     */
    public void setAziRadians(double aziRadians) {
        this.aziRadians = aziRadians;
    }

    /**
     * The {@code +azi} in force.
     *
     * @return the azimuth, in radians
     */
    public double getAziRadians() {
        return aziRadians;
    }

    /**
     * Port of {@code PJ_PROJECTION(labrd)} ({@code labrd.cpp:98-135}).
     *
     * @throws InvalidValueException if {@code +lat_0} is exactly 0
     */
    @Override
    public void initialize() {
        super.initialize();
        if (projectionLatitude == 0.) {
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                    "+proj=labrd requires +lat_0 != 0 (labrd.cpp:107-112); it is a projection for "
                            + "Madagascar and the oblique frame is undefined on the equator");
        }
        final double sinp = FastStrictTrig.sin(projectionLatitude);
        double t = 1. - es * sinp * sinp;
        final double n = 1. / Math.sqrt(t);
        final double r = one_es * n / t;
        kRg = scaleFactor * Math.sqrt(n * r);
        p0s = StrictMath.atan(Math.sqrt(r / n) * FastStrictTrig.tan(projectionLatitude));
        aCoef = sinp / FastStrictTrig.sin(p0s);
        t = e * sinp;
        cCoef = .5 * e * aCoef * Math.log((1. + t) / (1. - t))
                + -aCoef * Math.log(FastStrictTrig.tan(
                        ProjectionMath.FORTPI + .5 * projectionLatitude))
                + Math.log(FastStrictTrig.tan(ProjectionMath.FORTPI + .5 * p0s));
        final double twoAz = aziRadians + aziRadians;
        cb = 1. / (12. * kRg * kRg);
        ca = (1. - FastStrictTrig.cos(twoAz)) * cb;
        cb *= FastStrictTrig.sin(twoAz);
        cc = 3. * (ca * ca - cb * cb);
        cd = 6. * ca * cb;
    }

    /** {@code labrd_e_forward}, {@code labrd.cpp:16-48}. */
    @Override
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate xy) {
        double v1 = aCoef * Math.log(FastStrictTrig.tan(ProjectionMath.FORTPI + .5 * phi));
        double t = e * FastStrictTrig.sin(phi);
        double v2 = .5 * e * aCoef * Math.log((1. + t) / (1. - t));
        final double ps = 2. * (StrictMath.atan(Math.exp(v1 - v2 + cCoef))
                - ProjectionMath.FORTPI);
        final double i1 = ps - p0s;
        final double cosps = FastStrictTrig.cos(ps);
        final double cosps2 = cosps * cosps;
        final double sinps = FastStrictTrig.sin(ps);
        final double sinps2 = sinps * sinps;
        final double i4 = aCoef * cosps;
        final double i2 = .5 * aCoef * i4 * sinps;
        final double i3 = i2 * aCoef * aCoef * (5. * cosps2 - sinps2) / 12.;
        double i6 = i4 * aCoef * aCoef;
        final double i5 = i6 * (cosps2 - sinps2) / 6.;
        i6 *= aCoef * aCoef
                * (5. * cosps2 * cosps2 + sinps2 * (sinps2 - 18. * cosps2)) / 120.;
        t = lam * lam;
        double x = kRg * lam * (i4 + t * (i5 + t * i6));
        double y = kRg * (i1 + t * (i2 + t * i3));
        final double x2 = x * x;
        final double y2 = y * y;
        v1 = 3. * x * y2 - x * x2;
        v2 = y * y2 - 3. * x2 * y;
        x += ca * v1 + cb * v2;
        y += ca * v2 - cb * v1;
        xy.x = x;
        xy.y = y;
        return xy;
    }

    /**
     * {@code labrd_e_inverse}, {@code labrd.cpp:50-96}.
     *
     * <p>The isometric-latitude loop is 20 trips at {@code 1e-10} with <b>no error on
     * exhaustion</b> &mdash; upstream simply uses whatever {@code pe} it has reached. That is a
     * deliberate reproduction, not an oversight: adding a rejection would fail rows upstream
     * passes.
     */
    @Override
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
        double x2 = x * x;
        final double y2 = y * y;
        double v1 = 3. * x * y2 - x * x2;
        double v2 = y * y2 - 3. * x2 * y;
        final double v3 = x * (5. * y2 * y2 + x2 * (-10. * y2 + x2));
        final double v4 = y * (5. * x2 * x2 + y2 * (-10. * x2 + y2));
        final double xc = x + (-ca * v1 - cb * v2 + cc * v3 + cd * v4);
        final double yc = y + (cb * v1 - ca * v2 - cd * v3 + cc * v4);
        final double ps = p0s + yc / kRg;
        double pe = ps + projectionLatitude - p0s;

        double t = 0.0;
        for (int i = MAX_ITER; i != 0; --i) {
            v1 = aCoef * Math.log(FastStrictTrig.tan(ProjectionMath.FORTPI + .5 * pe));
            final double tpe = e * FastStrictTrig.sin(pe);
            v2 = .5 * e * aCoef * Math.log((1. + tpe) / (1. - tpe));
            t = ps - 2. * (StrictMath.atan(Math.exp(v1 - v2 + cCoef)) - ProjectionMath.FORTPI);
            pe += t;
            if (Math.abs(t) < EPS) {
                break;
            }
        }

        t = e * FastStrictTrig.sin(pe);
        t = 1. - t * t;
        final double re = one_es / (t * Math.sqrt(t));
        t = FastStrictTrig.tan(ps);
        final double t2 = t * t;
        final double s = kRg * kRg;
        double d = re * scaleFactor * kRg;
        final double i7 = t / (2. * d);
        final double i8 = t * (5. + 3. * t2) / (24. * d * s);
        d = FastStrictTrig.cos(ps) * kRg * aCoef;
        final double i9 = 1. / d;
        d *= s;
        final double i10 = (1. + 2. * t2) / (6. * d);
        final double i11 = (5. + t2 * (28. + 24. * t2)) / (120. * d * s);
        x2 = xc * xc;
        lp.y = pe + x2 * (-i7 + i8 * x2);
        lp.x = xc * (i9 + x2 * (-i10 + x2 * i11));
        return lp;
    }

    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public boolean isConformal() {
        return true;
    }

    @Override
    public String toString() {
        return "Laborde";
    }
}

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
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * International Map of the World Polyconic, {@code +proj=imw_p} &mdash; a port of
 * {@code 9.8.1:src/projections/imw_p.cpp}. Ellipsoidal only; the inverse is iterative.
 *
 * <p>The modified polyconic adopted for the 1:1,000,000 IMW series. Each sheet's two bounding
 * parallels, {@code +lat_1} and {@code +lat_2}, are true-length circular arcs and the sheet is
 * closed by straight meridians through the arc ends; the forward solves the circle-line
 * intersection that puts an interior point on the right arc.
 *
 * <h2>Setup rejections</h2>
 *
 * <p>{@code phi12} ({@code imw_p.cpp:31-57}) reports four errors, but only two shapes reach the
 * arithmetic: {@code |lat_2 - lat_1| / 2 < 1e-10} and {@code |lat_2 + lat_1| / 2 < 1e-10}. Its
 * other two are {@code +lat_1} and {@code +lat_2} <em>absence</em> tests, and this class does not
 * need them: {@link Projection#projectionLatitude1} and {@link Projection#projectionLatitude2}
 * both default to 0, so an absent pair gives {@code del == sig == 0} and trips the first check
 * anyway. The corpus's second block spells {@code +lat_1=0} explicitly, which is legal and
 * selects {@code PHI_1_IS_ZERO}, so absence and zero must <em>not</em> be conflated in the mode
 * selection &mdash; and upstream decides the mode on the value, never on presence.
 *
 * <h2>{@code +lon_1} is a presence test, and its default is latitude-dependent</h2>
 *
 * <p>Absent {@code +lon_1}, upstream picks 2&deg;, 4&deg; or 8&deg; according to whether
 * {@code |(lat_1 + lat_2)/2|} is at most 60&deg;, at most 76&deg;, or more &mdash; the IMW
 * sheet widths. Because 0 is a legal {@code +lon_1}, presence cannot be recovered from the value,
 * so {@link #lon1} is {@code NaN} until {@link #setLon1(double)} is called. As with
 * {@link TwoPointEquidistantProjection}, {@code Proj4Parser} currently routes {@code +lon_1} to
 * {@link ObliqueMercatorProjection} alone, so the latitude-dependent default is what every
 * definition gets today &mdash; which is also what both corpus blocks exercise.
 *
 * <h2>{@code loc_for}'s {@code yc} out-parameter is loop-carried</h2>
 *
 * <p>{@code loc_for(lp, P, &amp;yc)} writes {@code *yc} in two of its three branches and leaves it
 * <em>untouched</em> when {@code lp.phi == 0}. The forward passes an uninitialised {@code yc} and
 * never reads it; the inverse initialises it to 0 and carries it across iterations, so a trial
 * latitude of exactly zero reuses the previous iteration's value. That is reproduced by
 * {@link #locFor} taking the incoming {@code yc} and returning the outgoing one, rather than by
 * an instance field &mdash; a field would also make the class thread-hostile.
 *
 * <h2>The inverse's iteration and its two failures</h2>
 *
 * <p>A secant step on latitude and a proportional step on longitude, up to
 * {@code N_MAX_ITER = 1000} ("arbitrarily chosen number", says the comment), tolerance
 * {@code 1e-10} on both projected ordinates. Two domain errors: a zero secant denominator, and
 * exhaustion. The exhaustion test is {@code i == N_MAX_ITER} <em>after</em> the loop, so a point
 * that converges on exactly the thousandth trip is still refused; that is upstream's behaviour
 * and is reproduced.
 *
 * @see <a href="https://github.com/OSGeo/PROJ/blob/9.8.1/src/projections/imw_p.cpp">9.8.1
 *      imw_p.cpp</a>
 */
public class InternationalMapOfTheWorldPolyconicProjection extends Projection {

    private static final long serialVersionUID = 7580529782044000460L;

    private static final double TOL = 1e-10;
    private static final double EPS = 1e-10;
    private static final int N_MAX_ITER = 1000;

    /** {@code phi_1} and {@code phi_2} both non-zero. */
    private static final int NONE_IS_ZERO = 0;
    /** {@code phi_1 == 0}. */
    private static final int PHI_1_IS_ZERO = 1;
    /** {@code phi_2 == 0}. */
    private static final int PHI_2_IS_ZERO = -1;

    private double pp;
    private double qp;
    private double pCoef;
    private double qCoef;
    private double r1;
    private double r2;
    private double sphi1;
    private double sphi2;
    private double c2;
    /** {@code Q->phi_1}, after the swap that makes it the more southerly. */
    private double phi1;
    /** {@code Q->phi_2}, after the swap. */
    private double phi2;
    private double lam1;
    private int mode;
    private MeridianArc meridian;

    /** {@code +lon_1} in radians, or {@code NaN} for "not given". */
    private double lon1 = Double.NaN;

    /**
     * {@code +lon_1}, the half-width of the sheet in longitude. Absent, upstream substitutes
     * 2&deg;, 4&deg; or 8&deg; from the mean parallel.
     *
     * @param lon1 the longitude, in radians
     */
    public void setLon1(double lon1) {
        this.lon1 = lon1;
    }

    /**
     * The {@code +lon_1} in force.
     *
     * @return the longitude in radians, or {@code NaN} if none was given
     */
    public double getLon1() {
        return lon1;
    }

    /**
     * Port of {@code PJ_PROJECTION(imw_p)} ({@code imw_p.cpp:175-240}) and {@code phi12}.
     *
     * @throws InvalidValueException if {@code |lat_1 - lat_2|} or {@code |lat_1 + lat_2|} is
     *         zero to within {@code 1e-10}, which includes both being absent
     */
    @Override
    public void initialize() {
        super.initialize();
        meridian = MeridianArc.fromEs(es);

        phi1 = projectionLatitude1;
        phi2 = projectionLatitude2;
        double del = 0.5 * (phi2 - phi1);
        double sig = 0.5 * (phi2 + phi1);
        if (Math.abs(del) < EPS || Math.abs(sig) < EPS) {
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                    "+proj=imw_p requires |lat_1 - lat_2| > 0 and |lat_1 + lat_2| > 0; got "
                            + "lat_1 = " + phi1 + " rad, lat_2 = " + phi2 + " rad "
                            + "(imw_p.cpp:46-55). Both default to 0, so a definition that omits "
                            + "them is this same error.");
        }
        if (phi2 < phi1) { /* make sure phi_1 is the most southerly */
            del = phi1;
            phi1 = phi2;
            phi2 = del;
        }
        if (!Double.isNaN(lon1)) {
            lam1 = lon1;
        } else {
            // imw_p.cpp:197-206. RAD_TO_DEG is a multiply upstream, and the result is one of
            // three exact small integers, so neither direction can lose a bit here.
            sig = Math.abs(sig * ProjectionMath.RTD);
            if (sig <= 60) {
                sig = 2.;
            } else if (sig <= 76) {
                sig = 4.;
            } else {
                sig = 8.;
            }
            lam1 = sig * ProjectionMath.DTR;
        }

        // Reset the four values `xy()` conditionally fills, so that a re-initialise with a
        // different lat_1/lat_2 cannot read the previous run's.
        sphi1 = 0.;
        sphi2 = 0.;
        r1 = 0.;
        r2 = 0.;

        mode = NONE_IS_ZERO;
        final double x1;
        final double y1;
        if (phi1 != 0.0) {
            sphi1 = FastStrictTrig.sin(phi1);
            r1 = 1. / (FastStrictTrig.tan(phi1) * Math.sqrt(1. - es * sphi1 * sphi1));
            final double f = lam1 * sphi1;
            y1 = r1 * (1 - FastStrictTrig.cos(f));
            x1 = r1 * FastStrictTrig.sin(f);
        } else {
            mode = PHI_1_IS_ZERO;
            y1 = 0.;
            x1 = lam1;
        }
        final double x2;
        final double t2;
        if (phi2 != 0.0) {
            sphi2 = FastStrictTrig.sin(phi2);
            r2 = 1. / (FastStrictTrig.tan(phi2) * Math.sqrt(1. - es * sphi2 * sphi2));
            final double f = lam1 * sphi2;
            t2 = r2 * (1 - FastStrictTrig.cos(f));
            x2 = r2 * FastStrictTrig.sin(f);
        } else {
            mode = PHI_2_IS_ZERO;
            t2 = 0.;
            x2 = lam1;
        }
        final double m1 = meridian.mlfn(phi1, sphi1, FastStrictTrig.cos(phi1));
        final double m2 = meridian.mlfn(phi2, sphi2, FastStrictTrig.cos(phi2));
        double t = m2 - m1;
        final double s = x2 - x1;
        final double y2 = Math.sqrt(t * t - s * s) + y1;
        c2 = y2 - t2;
        t = 1. / t;
        pCoef = (m2 * y1 - m1 * y2) * t;
        qCoef = (y2 - y1) * t;
        pp = (m2 * x1 - m1 * x2) * t;
        qp = (x2 - x1) * t;
    }

    /**
     * {@code loc_for}, {@code imw_p.cpp:59-104}: the shared kernel of the forward and of every
     * inverse iteration.
     *
     * @param lam longitude relative to {@code +lon_0}, radians
     * @param phi latitude, radians
     * @param xy filled with the projected point
     * @param ycIn the caller's current {@code yc}
     * @return the outgoing {@code yc}, which is {@code ycIn} unchanged when {@code phi == 0}
     */
    private double locFor(double lam, double phi, ProjCoordinate xy, double ycIn) {
        if (phi == 0.0) {
            xy.x = lam;
            xy.y = 0.;
            return ycIn;
        }
        double yc = ycIn;
        final double sp = FastStrictTrig.sin(phi);
        final double m = meridian.mlfn(phi, sp, FastStrictTrig.cos(phi));
        final double xa = pp + qp * m;
        final double ya = pCoef + qCoef * m;
        final double r = 1. / (FastStrictTrig.tan(phi) * Math.sqrt(1. - es * sp * sp));
        double cc = Math.sqrt(r * r - xa * xa);
        if (phi < 0.) {
            cc = -cc;
        }
        cc += ya - r;
        final double xb;
        final double yb;
        if (mode == PHI_2_IS_ZERO) {
            xb = lam;
            yb = c2;
        } else {
            final double t = lam * sphi2;
            xb = r2 * FastStrictTrig.sin(t);
            yb = c2 + r2 * (1. - FastStrictTrig.cos(t));
        }
        final double xc;
        if (mode == PHI_1_IS_ZERO) {
            xc = lam;
            yc = 0.;
        } else {
            final double t = lam * sphi1;
            xc = r1 * FastStrictTrig.sin(t);
            yc = r1 * (1. - FastStrictTrig.cos(t));
        }
        final double d = (xb - xc) / (yb - yc);
        final double b = xc + d * (cc + r - yc);
        double x = d * Math.sqrt(r * r * (1 + d * d) - b * b);
        if (phi > 0) {
            x = -x;
        }
        x = (b + x) / (1. + d * d);
        double y = Math.sqrt(r * r - x * x);
        if (phi > 0) {
            y = -y;
        }
        xy.x = x;
        xy.y = y + cc + r;
        return yc;
    }

    /** {@code imw_p_e_forward}, {@code imw_p.cpp:106-110}. */
    @Override
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate xy) {
        locFor(lam, phi, xy, 0.0);
        return xy;
    }

    /**
     * {@code imw_p_e_inverse}, {@code imw_p.cpp:112-145}.
     *
     * @throws ProjectionException if the secant denominator vanishes or the iteration does not
     *         converge in 1000 trips; upstream sets
     *         {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN} for both
     */
    @Override
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
        double phi = phi2;
        double lam = x / FastStrictTrig.cos(phi);
        final ProjCoordinate t = new ProjCoordinate();
        double yc = 0.0;
        int i = 0;
        do {
            yc = locFor(lam, phi, t, yc);
            final double denom = t.y - yc;
            if (denom != 0 || Math.abs(t.y - y) > TOL) {
                if (denom == 0) {
                    throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                            "imw_p inverse: the secant denominator (t.y - yc) is zero at "
                                    + "trial latitude " + phi + " rad, so the iteration cannot "
                                    + "step (imw_p.cpp:126-130)");
                }
                phi = ((phi - phi1) * (y - yc) / denom) + phi1;
            }
            if (t.x != 0 && Math.abs(t.x - x) > TOL) {
                lam = lam * x / t.x;
            }
            i++;
        } while (i < N_MAX_ITER && (Math.abs(t.x - x) > TOL || Math.abs(t.y - y) > TOL));

        if (i == N_MAX_ITER) {
            throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                    "imw_p inverse did not converge on (" + x + ", " + y + ") in " + N_MAX_ITER
                            + " iterations (imw_p.cpp:141-144)");
        }
        lp.x = lam;
        lp.y = phi;
        return lp;
    }

    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public String toString() {
        return "International Map of the World Polyconic";
    }
}

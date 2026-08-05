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

import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Oblique Cylindrical Equal Area, {@code +proj=ocea} &mdash; a port of
 * {@code 9.8.1:src/projections/ocea.cpp}. Spherical only; both directions closed form.
 *
 * <p>A Lambert cylindrical equal-area map whose equator is an arbitrary great circle. The great
 * circle is specified either as a point plus an azimuth ({@code +lat_0} {@code +lonc}
 * {@code +alpha}) or as two points ({@code +lat_1} {@code +lon_1} {@code +lat_2}
 * {@code +lon_2}), following Snyder's equations 9-1, 9-2, 9-7 and 9-8.
 *
 * <h2>Which form is used is decided by PRESENCE, not by value</h2>
 *
 * <p>{@code pj_param(P-&gt;ctx, P-&gt;params, "talpha").i} is the {@code 't'} sigil, which tests
 * whether the key appears at all. So {@code +alpha=0} selects the azimuth form &mdash; the
 * corpus relies on this at {@code builtins.gie:5114}, whose {@code +lat_0=45 +alpha=0} must give
 * the same point as the two-point block above it. {@link Projection#alpha} is {@code NaN} until
 * {@code Proj4Parser} assigns it, so {@code !Double.isNaN(alpha)} is exactly the presence test;
 * {@code +lonc}, read only inside that branch, is {@code NaN}-defaulted the same way and
 * upstream's absent value is 0.
 *
 * <h2>{@code +lon_0} is overwritten, and {@code +lat_0} is read only in the azimuth form</h2>
 *
 * <p>{@code P-&gt;lam0 = lam_p + M_HALFPI}: the central meridian is <em>derived</em>, so any
 * {@code +lon_0} the user gave is discarded. That is why {@code initialize()} assigns
 * {@link Projection#projectionLongitude} rather than reading it, and it is idempotent because
 * {@code lam_p} depends on none of the values being written.
 *
 * <h2>Upstream's two guards, reproduced</h2>
 *
 * <ul>
 * <li><b>{@code if (lam_1 == -M_HALFPI) lam_p = -lam_p;}</b> &mdash; an exact-equality test on a
 *   parsed double, commented "take care of P-&gt;lam0 wrap-around when +lam_1=-90". Kept as an
 *   exact comparison, because a tolerance would change which definitions it catches.</li>
 * <li><b>{@code if (tan(phi_1) == 0.0)}</b> &mdash; upstream's own comment says it is unsure
 *   whether the case should be supported and that the branch "gives the same result as the below
 *   atan()"; it substitutes {@code -pi/2} or {@code +pi/2} on the sign of
 *   {@code cos(lam_p - lam_1)}. Also an exact comparison upstream.</li>
 * </ul>
 *
 * <h2>Known parser gap: {@code +lon_1}/{@code +lon_2}</h2>
 *
 * <p>{@code Proj4Parser} dispatches {@code +lon_1}/{@code +lon_2} to
 * {@link ObliqueMercatorProjection} <b>and nothing else</b>, so the setters below are currently
 * unreachable from a proj-string and both longitudes stay 0. Six of the corpus's eleven
 * {@code ocea} operations pass anyway &mdash; four use the azimuth form and two spell
 * {@code +lon_1=0 +lon_2=0}, which is the default. The three that give {@code +lon_2} a non-zero
 * value ({@code 1e-8}, {@code -1e-8}, {@code 1e-5}) are wrong until an
 * {@code instanceof ObliqueCylindricalEqualAreaProjection} branch joins the {@code omerc} one.
 *
 * @see <a href="https://github.com/OSGeo/PROJ/blob/9.8.1/src/projections/ocea.cpp">9.8.1
 *      ocea.cpp</a>
 */
public class ObliqueCylindricalEqualAreaProjection extends CylindricalProjection {

    private static final long serialVersionUID = 653521617477232236L;

    /** {@code Q->rok}, {@code 1/k_0}. */
    private double rok;
    /** {@code Q->rtk}, {@code k_0}. */
    private double rtk;
    /** {@code Q->sinphi}, the sine of the derived pole latitude. */
    private double sinphip;
    /** {@code Q->cosphi}, the cosine of the derived pole latitude. */
    private double cosphip;

    /** {@code +lon_1}, in radians. Upstream's absent value is 0. */
    private double lon1 = 0.0;
    /** {@code +lon_2}, in radians. Upstream's absent value is 0. */
    private double lon2 = 0.0;

    /**
     * {@code +lon_1}, the longitude of the first defining point.
     *
     * @param lon1 the longitude, in radians
     */
    public void setLon1(double lon1) {
        this.lon1 = lon1;
    }

    /**
     * The {@code +lon_1} in force.
     *
     * @return the longitude, in radians
     */
    public double getLon1() {
        return lon1;
    }

    /**
     * {@code +lon_2}, the longitude of the second defining point.
     *
     * @param lon2 the longitude, in radians
     */
    public void setLon2(double lon2) {
        this.lon2 = lon2;
    }

    /**
     * The {@code +lon_2} in force.
     *
     * @return the longitude, in radians
     */
    public double getLon2() {
        return lon2;
    }

    /** Port of {@code PJ_PROJECTION(ocea)} ({@code ocea.cpp:47-110}). */
    @Override
    public void initialize() {
        super.initialize();
        rok = 1. / scaleFactor;
        rtk = scaleFactor;

        final double lamp;
        final double phip;
        if (!Double.isNaN(alpha)) {
            // One point plus one azimuth. The + M_PI is upstream's, so that alpha is measured
            // clockwise from north from point 1 towards point 2, consistent with omerc.
            final double alph = Math.PI + alpha;
            final double lonz = Double.isNaN(lonc) ? 0.0 : lonc;
            final double sinAlpha = FastStrictTrig.sin(alph);
            lamp = StrictMath.atan2(-FastStrictTrig.cos(alph),
                    -FastStrictTrig.sin(projectionLatitude) * sinAlpha) + lonz;
            phip = StrictMath.asin(FastStrictTrig.cos(projectionLatitude) * sinAlpha);
        } else {
            // Two points. Snyder 9-1 and 9-2.
            final double phi1 = projectionLatitude1;
            final double phi2 = projectionLatitude2;
            final double lam1 = lon1;
            final double lam2 = lon2;
            final double cosPhi1 = FastStrictTrig.cos(phi1);
            final double cosPhi2 = FastStrictTrig.cos(phi2);
            final double sinPhi1 = FastStrictTrig.sin(phi1);
            final double sinPhi2 = FastStrictTrig.sin(phi2);
            double lp = StrictMath.atan2(
                    cosPhi1 * sinPhi2 * FastStrictTrig.cos(lam1)
                            - sinPhi1 * cosPhi2 * FastStrictTrig.cos(lam2),
                    sinPhi1 * cosPhi2 * FastStrictTrig.sin(lam2)
                            - cosPhi1 * sinPhi2 * FastStrictTrig.sin(lam1));
            // ocea.cpp:88-89, verbatim including the exact equality.
            if (lam1 == -ProjectionMath.HALFPI) {
                lp = -lp;
            }
            lamp = lp;
            final double cosLampMinusLam1 = FastStrictTrig.cos(lamp - lam1);
            final double tanPhi1 = FastStrictTrig.tan(phi1);
            if (tanPhi1 == 0.0) {
                phip = (cosLampMinusLam1 >= 0.0) ? -ProjectionMath.HALFPI : ProjectionMath.HALFPI;
            } else {
                phip = StrictMath.atan(-cosLampMinusLam1 / tanPhi1);
            }
        }
        // ocea.cpp:105-109. The central meridian is derived, so +lon_0 is discarded.
        projectionLongitude = lamp + ProjectionMath.HALFPI;
        cosphip = FastStrictTrig.cos(phip);
        sinphip = FastStrictTrig.sin(phip);
        es = 0.;
    }

    /** {@code ocea_s_forward}, {@code ocea.cpp:21-32}. */
    @Override
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate xy) {
        final double sinlam = FastStrictTrig.sin(lam);
        final double t = FastStrictTrig.cos(lam);
        double x = StrictMath.atan(
                (FastStrictTrig.tan(phi) * cosphip + sinphip * sinlam) / t);
        if (t < 0.) {
            x += Math.PI;
        }
        xy.x = x * rtk;
        xy.y = rok * (sinphip * FastStrictTrig.sin(phi)
                - cosphip * FastStrictTrig.cos(phi) * sinlam);
        return xy;
    }

    /** {@code ocea_s_inverse}, {@code ocea.cpp:34-45}. */
    @Override
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
        final double yy = y / rok;
        final double xx = x / rtk;
        final double t = Math.sqrt(1. - yy * yy);
        final double s = FastStrictTrig.sin(xx);
        lp.y = StrictMath.asin(yy * sinphip + t * cosphip * s);
        lp.x = StrictMath.atan2(t * sinphip * s - yy * cosphip, t * FastStrictTrig.cos(xx));
        return lp;
    }

    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public boolean isEqualArea() {
        return true;
    }

    @Override
    public String toString() {
        return "Oblique Cylindrical Equal Area";
    }
}

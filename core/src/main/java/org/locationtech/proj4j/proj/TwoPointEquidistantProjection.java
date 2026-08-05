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
 * Two Point Equidistant, {@code +proj=tpeqd} &mdash; a port of
 * {@code 9.8.1:src/projections/tpeqd.cpp}. Spherical only; both directions closed form.
 *
 * <p>Distances from two nominated control points are both true. The map's own frame is the great
 * circle through the two points, so the setup rotates the sphere onto that frame and both
 * kernels work in it; {@code Q->lamc}, {@code Q->lp}, {@code Q->ca} and {@code Q->sa} are that
 * rotation.
 *
 * <h2>Two setup rejections, and they are not the same one</h2>
 *
 * <ul>
 * <li><b>The two points must be distinct</b> ({@code tpeqd.cpp:80-84}). Since {@code pj_param}
 *   answers 0 for every absent key, a bare {@code +proj=tpeqd} is this error.</li>
 * <li><b>{@code z02 == 0}</b> ({@code tpeqd.cpp:104-108}), whose own comment says it "actually
 *   happens when both lat_1 = lat_2 and |lat_1| = 90" &mdash; the two points are distinct in
 *   longitude but coincide on the sphere. {@code builtins.gie:7589} asserts it with
 *   {@code +lat_1=90 +lat_2=90 +lon_1=0 +lon_2=1}.</li>
 * </ul>
 *
 * <h2>{@code z02} is squared in place upstream, and {@code dlam2} is halved in place</h2>
 *
 * <p>Two of the classic in-place reassignments. {@code Q->z02} is the central angle while
 * {@code hz0}, {@code A12} and {@code r2z0} are derived from it and is <em>then</em> replaced by
 * its own square, which is the form both kernels read. {@code Q->dlam2} is the full longitude
 * difference while {@code z02} and {@code A12} are computed and is halved only afterwards, but
 * {@code Q->lp} is computed <em>before</em> the halving and {@code Q->lamc} <em>after</em> it. Get
 * that order wrong and the forward is still plausible. Locals here are named distinctly for the
 * two values so the ordering is visible rather than implied.
 *
 * <h2>{@code asqrt}, not {@code sqrtChecked}</h2>
 *
 * <p>The forward's radicand {@code 4*z02*z2 - t*t} is a cancellation quantity that goes slightly
 * negative at the map's edge, and upstream's {@code asqrt} <em>clamps</em> it to zero rather than
 * raising ({@code 9.8.1:src/aasincos.cpp:33}). So this is
 * {@code Math.sqrt(Math.max(0.0, ...))}, written at the call site exactly as
 * {@link ProjectionMath#sqrtChecked} advises, and deliberately not the checked form: raising here
 * would fail rows upstream passes.
 *
 * <h2>{@code +lon_0} is overwritten; {@code +lon_1}/{@code +lon_2} are not yet dispatched</h2>
 *
 * <p>{@code P->lam0 = adjlon(0.5 * (lam_1 + lam_2))} discards any {@code +lon_0}. And as with
 * {@link ObliqueCylindricalEqualAreaProjection}, {@code Proj4Parser} routes {@code +lon_1} and
 * {@code +lon_2} to {@link ObliqueMercatorProjection} alone, so the setters below are unreachable
 * from a proj-string today and both longitudes stay 0. Both corpus blocks that produce
 * coordinates use {@code +lat_1}/{@code +lat_2} only, so they are unaffected.
 *
 * @see <a href="https://github.com/OSGeo/PROJ/blob/9.8.1/src/projections/tpeqd.cpp">9.8.1
 *      tpeqd.cpp</a>
 */
public class TwoPointEquidistantProjection extends Projection {

    private static final long serialVersionUID = 476850482880862843L;

    private double cp1;
    private double sp1;
    private double cp2;
    private double sp2;
    private double ccs;
    private double cs;
    private double sc;
    private double r2z0;
    /** {@code Q->z02}, which by the end of setup holds the <em>square</em> of the central angle. */
    private double z02sq;
    /** {@code Q->dlam2}, which by the end of setup holds <em>half</em> the longitude difference. */
    private double halfDlam2;
    private double hz0;
    private double thz0;
    private double rhshz0;
    private double ca;
    private double sa;
    private double lp;
    private double lamc;

    /** {@code +lon_1}, in radians. Upstream's absent value is 0. */
    private double lon1 = 0.0;
    /** {@code +lon_2}, in radians. Upstream's absent value is 0. */
    private double lon2 = 0.0;

    /**
     * {@code +lon_1}, the longitude of the first control point.
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
     * {@code +lon_2}, the longitude of the second control point.
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

    /**
     * Port of {@code PJ_PROJECTION(tpeqd)} ({@code tpeqd.cpp:65-138}).
     *
     * @throws InvalidValueException if the two control points are equal, or if they coincide on
     *         the sphere ({@code z02 == 0}); upstream raises
     *         {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE} for both
     */
    @Override
    public void initialize() {
        super.initialize();
        final double phi1 = projectionLatitude1;
        final double phi2 = projectionLatitude2;
        final double lam1 = lon1;
        final double lam2 = lon2;

        if (phi1 == phi2 && lam1 == lam2) {
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                    "+proj=tpeqd needs two distinct control points, but +lat_1/+lon_1 and "
                            + "+lat_2/+lon_2 are both (" + phi1 + ", " + lam1 + ") rad "
                            + "(tpeqd.cpp:80-84). Note pj_param answers 0 for every absent key, "
                            + "so a bare +proj=tpeqd is this same error.");
        }

        projectionLongitude = ProjectionMath.adjlon(0.5 * (lam1 + lam2));
        final double dlam2 = ProjectionMath.adjlon(lam2 - lam1);

        cp1 = FastStrictTrig.cos(phi1);
        cp2 = FastStrictTrig.cos(phi2);
        sp1 = FastStrictTrig.sin(phi1);
        sp2 = FastStrictTrig.sin(phi2);
        cs = cp1 * sp2;
        sc = sp1 * cp2;
        final double sinDlam2 = FastStrictTrig.sin(dlam2);
        final double cosDlam2 = FastStrictTrig.cos(dlam2);
        ccs = cp1 * cp2 * sinDlam2;

        // Vincenty on the sphere, which is stable where the naive acos is not.
        final double csMinusScCosDlam = cs - sc * cosDlam2;
        final double cp2SinDlam2 = cp2 * sinDlam2;
        final double z02 = StrictMath.atan2(
                Math.sqrt(cp2SinDlam2 * cp2SinDlam2 + csMinusScCosDlam * csMinusScCosDlam),
                sp1 * sp2 + cp1 * cp2 * cosDlam2);
        if (z02 == 0.0) {
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                    "+proj=tpeqd: the two control points coincide on the sphere, so the central "
                            + "angle between them is zero. Upstream's own note is that this "
                            + "happens when lat_1 == lat_2 and |lat_1| == 90 (tpeqd.cpp:104-108).");
        }
        hz0 = .5 * z02;
        final double a12 = StrictMath.atan2(cp2SinDlam2, csMinusScCosDlam);
        final double pp = ProjectionMath.asinChecked(cp1 * FastStrictTrig.sin(a12));
        ca = FastStrictTrig.cos(pp);
        sa = FastStrictTrig.sin(pp);
        // tpeqd.cpp:130 -- computed with the FULL dlam2 still in hand, via hz0.
        lp = ProjectionMath.adjlon(
                StrictMath.atan2(cp1 * FastStrictTrig.cos(a12), sp1) - hz0);
        // tpeqd.cpp:131-132 -- and lamc with the HALVED one.
        halfDlam2 = dlam2 * .5;
        lamc = ProjectionMath.HALFPI
                - StrictMath.atan2(FastStrictTrig.sin(a12) * sp1, FastStrictTrig.cos(a12))
                - halfDlam2;
        thz0 = FastStrictTrig.tan(hz0);
        rhshz0 = .5 / FastStrictTrig.sin(hz0);
        r2z0 = 0.5 / z02;
        z02sq = z02 * z02;
        es = 0.;
    }

    /** {@code tpeqd_s_forward}, {@code tpeqd.cpp:18-40}. */
    @Override
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate xy) {
        final double sp = FastStrictTrig.sin(phi);
        final double cp = FastStrictTrig.cos(phi);
        final double dl1 = lam + halfDlam2;
        final double dl2 = lam - halfDlam2;
        double z1 = ProjectionMath.acosChecked(sp1 * sp + cp1 * cp * FastStrictTrig.cos(dl1));
        double z2 = ProjectionMath.acosChecked(sp2 * sp + cp2 * cp * FastStrictTrig.cos(dl2));
        z1 *= z1;
        z2 *= z2;

        double t = z1 - z2;
        xy.x = r2z0 * t;
        t = z02sq - t;
        // asqrt: clamp, do not raise. aasincos.cpp:33.
        xy.y = r2z0 * Math.sqrt(Math.max(0.0, 4. * z02sq * z2 - t * t));
        if ((ccs * sp - cp * (cs * FastStrictTrig.sin(dl1) - sc * FastStrictTrig.sin(dl2))) < 0.) {
            xy.y = -xy.y;
        }
        return xy;
    }

    /** {@code tpeqd_s_inverse}, {@code tpeqd.cpp:42-63}. */
    @Override
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate out) {
        final double cz1 = FastStrictTrig.cos(StrictMath.hypot(y, x + hz0));
        final double cz2 = FastStrictTrig.cos(StrictMath.hypot(y, x - hz0));
        final double s0 = cz1 + cz2;
        final double d = cz1 - cz2;
        double lam = -StrictMath.atan2(d, s0 * thz0);
        double phi = ProjectionMath.acosChecked(StrictMath.hypot(thz0 * s0, d) * rhshz0);
        if (y < 0.) {
            phi = -phi;
        }
        // lam--phi is now in the frame whose equator is the P1--P2 great circle.
        final double sp = FastStrictTrig.sin(phi);
        final double cp = FastStrictTrig.cos(phi);
        lam -= lp;
        final double s = FastStrictTrig.cos(lam);
        out.y = ProjectionMath.asinChecked(sa * sp + ca * cp * s);
        out.x = StrictMath.atan2(cp * FastStrictTrig.sin(lam), sa * cp * s - ca * sp) + lamc;
        return out;
    }

    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public String toString() {
        return "Two Point Equidistant";
    }
}

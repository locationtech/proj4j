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
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.ConformalLat;
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * CalCOFI line/station, {@code +proj=calcofi} &mdash; a port of
 * {@code 9.8.1:src/projections/calcofi.cpp}.
 *
 * <p>The California Cooperative Oceanic Fisheries Investigations survey grid: a Mercator
 * shear about the reference point O (line 80, station 60, at 121.15&deg;W 34.15&deg;N) rotated
 * 30&deg;, with one line unit equal to 1/5 of a degree of longitude at O and one station unit
 * equal to 1/15 of a degree. Both an ellipsoidal and a spherical kernel, dispatched on
 * {@code es == 0} exactly as upstream does &mdash; the sphere is not a special case of the
 * ellipsoid here, because upstream's spherical arm uses {@code log(tan(pi/4 + phi/2))} directly
 * rather than {@code pj_tsfn} with {@code e = 0}, and its inverse uses
 * {@code pi/2 - 2 atan(exp(-y))} rather than {@code pj_phi2}.
 *
 * <h2>The output is not metres, and the affine is deliberately disabled</h2>
 *
 * <p>{@code PJ_PROJECTION(calcofi)} ({@code calcofi.cpp:132-152}) overwrites five of the host's
 * own fields after they have been parsed:
 *
 * <pre>
 *   P-&gt;lam0 = 0;  P-&gt;ra = 1;  P-&gt;a = 1;  P-&gt;x0 = 0;  P-&gt;y0 = 0;  P-&gt;over = 1;
 * </pre>
 *
 * <p>with its own comment "if the user has specified +lon_0 or +k0 for some reason, we're going
 * to ignore it so that xy is consistent with point O". So {@code +lon_0}, {@code +x_0} and
 * {@code +y_0} are <b>silently discarded</b>, and the corpus asserts exactly that: the three
 * {@code +lon_0=50} blocks at {@code builtins.gie:835-849} expect the same numbers a bare
 * definition gives. What survives is {@code +to_meter}/{@code +fr_meter} (upstream never
 * touches them, so {@link Projection#fromMetres} is left alone here too) and the
 * <em>ellipsoid</em>, whose {@code e} both kernels still read even though {@code a} has been
 * replaced by 1.
 *
 * <p>{@code +R=400} therefore still changes the answer, not by scaling but by selecting the
 * spherical kernel &mdash; which is why {@code builtins.gie:845} differs from
 * {@code builtins.gie:840} in the fourth significant figure.
 *
 * <h2>{@code over = 1} is load-bearing</h2>
 *
 * <p>Four corpus rows in the inverse direction expect longitudes of
 * {@code -207.447024504} and {@code -62.486322854} degrees &mdash; the first is outside
 * &plusmn;180&deg;. {@code inv_finalize} only wraps when {@code P-&gt;over} is zero, and
 * {@code calcofi} sets it, so the revolution count is kept. Without
 * {@link Projection#setOver(boolean)} those rows come back at {@code 152.55}&deg;, wrong by
 * a full turn and with no error raised.
 *
 * <h2>{@code RAD_TO_DEG} is a multiply here, not a divide</h2>
 *
 * <p>Upstream writes {@code RAD_TO_DEG * (ry - PT_O_PHI)}, so this uses
 * {@link ProjectionMath#RTD} (a multiply) rather than {@link ProjectionMath#toDeg} (which
 * divides by {@code DTR}, matching {@code PJ_TODEG}). The two differ by an ulp on 46 of 721
 * whole-degree arguments. {@code ProjectionMath.RTD} and PROJ's {@code RAD_TO_DEG} literal
 * {@code 57.295779513082321} are the same {@code double}.
 *
 * @see <a href="https://github.com/OSGeo/PROJ/blob/9.8.1/src/projections/calcofi.cpp">9.8.1
 *      calcofi.cpp</a>
 */
public class CalCOFIProjection extends CylindricalProjection {

    private static final long serialVersionUID = -4437022909865140518L;

    private static final double EPS10 = 1.e-10;
    private static final double DEG_TO_LINE = 5;
    private static final double DEG_TO_STATION = 15;
    private static final double LINE_TO_RAD = 0.0034906585039886592;
    private static final double STATION_TO_RAD = 0.0011635528346628863;
    /** Reference point O is at line 80. */
    private static final double PT_O_LINE = 80;
    /** &hellip; station 60. */
    private static final double PT_O_STATION = 60;
    /** &hellip; longitude &minus;121.15&deg;. */
    private static final double PT_O_LAMBDA = -2.1144663887911301;
    /** &hellip; and latitude 34.15&deg;. */
    private static final double PT_O_PHI = 0.59602993955606354;
    /** The CalCOFI grid angle, 30&deg; in radians. */
    private static final double ROTATION_ANGLE = 0.52359877559829882;

    private static final double SIN_ROTATION = FastStrictTrig.sin(ROTATION_ANGLE);
    private static final double COS_ROTATION = FastStrictTrig.cos(ROTATION_ANGLE);
    private static final double TAN_ROTATION = FastStrictTrig.tan(ROTATION_ANGLE);
    private static final double SIN_PT_O_PHI = FastStrictTrig.sin(PT_O_PHI);

    /**
     * Port of {@code PJ_PROJECTION(calcofi)} ({@code calcofi.cpp:132-152}).
     * <p>
     * Idempotent: every assignment is a constant, so the second call the parser makes changes
     * nothing.
     */
    @Override
    public void initialize() {
        // calcofi.cpp:136-141, applied BEFORE super.initialize() so that totalScale,
        // totalFalseEasting and totalFalseNorthing are derived from the overridden values.
        projectionLongitude = 0;
        a = 1;
        falseEasting = 0;
        falseNorthing = 0;
        setOver(true);
        super.initialize();
    }

    /**
     * {@code calcofi_e_forward} ({@code calcofi.cpp:31-62}) and {@code calcofi_s_forward}
     * ({@code :64-85}), which differ only in how the Mercator ordinate is formed and inverted.
     *
     * @throws ProjectionException at the poles, where upstream sets
     *         {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN}
     */
    @Override
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate xy) {
        if (Math.abs(Math.abs(phi) - ProjectionMath.HALFPI) <= EPS10) {
            throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                    "calcofi is undefined at the poles: |phi| = " + Math.abs(phi)
                            + " rad is within 1e-10 of pi/2 (calcofi.cpp:40)");
        }
        final double y;
        final double oy;
        if (spherical) {
            y = Math.log(FastStrictTrig.tan(ProjectionMath.FORTPI + .5 * phi));
            oy = Math.log(FastStrictTrig.tan(ProjectionMath.FORTPI + .5 * PT_O_PHI));
        } else {
            y = -Math.log(ConformalLat.tsfn(phi, FastStrictTrig.sin(phi), e));
            oy = -Math.log(ConformalLat.tsfn(PT_O_PHI, SIN_PT_O_PHI, e));
        }
        final double l1 = (y - oy) * TAN_ROTATION;
        final double l2 = -lam - l1 + PT_O_LAMBDA;
        double ry = l2 * COS_ROTATION * SIN_ROTATION + y;
        // Inverse Mercator, back to a latitude.
        ry = spherical
                ? ProjectionMath.HALFPI - 2. * StrictMath.atan(Math.exp(-ry))
                : ConformalLat.phi2(Math.exp(-ry), e);
        xy.x = PT_O_LINE - ProjectionMath.RTD * (ry - PT_O_PHI) * DEG_TO_LINE / COS_ROTATION;
        xy.y = PT_O_STATION + ProjectionMath.RTD * (ry - phi) * DEG_TO_STATION / SIN_ROTATION;
        return xy;
    }

    /**
     * {@code calcofi_e_inverse} ({@code calcofi.cpp:87-108}) and {@code calcofi_s_inverse}
     * ({@code :110-130}). No domain check either side, and none upstream.
     */
    @Override
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
        final double ry = PT_O_PHI - LINE_TO_RAD * (x - PT_O_LINE) * COS_ROTATION;
        final double phi = ry - STATION_TO_RAD * (y - PT_O_STATION) * SIN_ROTATION;
        final double oymctr;
        final double rymctr;
        final double xymctr;
        if (spherical) {
            oymctr = Math.log(FastStrictTrig.tan(ProjectionMath.FORTPI + .5 * PT_O_PHI));
            rymctr = Math.log(FastStrictTrig.tan(ProjectionMath.FORTPI + .5 * ry));
            xymctr = Math.log(FastStrictTrig.tan(ProjectionMath.FORTPI + .5 * phi));
        } else {
            oymctr = -Math.log(ConformalLat.tsfn(PT_O_PHI, SIN_PT_O_PHI, e));
            rymctr = -Math.log(ConformalLat.tsfn(ry, FastStrictTrig.sin(ry), e));
            xymctr = -Math.log(ConformalLat.tsfn(phi, FastStrictTrig.sin(phi), e));
        }
        final double l1 = (xymctr - oymctr) * TAN_ROTATION;
        final double l2 = (rymctr - xymctr) / (COS_ROTATION * SIN_ROTATION);
        lp.y = phi;
        lp.x = PT_O_LAMBDA - (l1 + l2);
        return lp;
    }

    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public String toString() {
        return "Cal Coop Ocean Fish Invest Lines/Stations";
    }
}

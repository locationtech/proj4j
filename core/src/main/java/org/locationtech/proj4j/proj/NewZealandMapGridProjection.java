/*******************************************************************************
 * Copyright 2009, 2017 Martin Davis
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
package org.locationtech.proj4j.proj;

import static java.lang.Math.abs;
import static org.locationtech.proj4j.util.ProjectionMath.EPS10;
import static org.locationtech.proj4j.util.ProjectionMath.zpoly1;
import static org.locationtech.proj4j.util.ProjectionMath.zpoly1d;

import org.locationtech.proj4j.ConvergenceFailureException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.util.Complex;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * The New Zealand Map Grid projection, {@code 9.8.1:src/projections/nzmg.cpp}.
 *
 * <p><b>This projection does not use the auxiliary-latitude machinery, and upstream's does not
 * either.</b> It is a fixed-Earth fit: the two real series {@code tphi}/{@code tpsi} and the six
 * complex coefficients {@code bf} are hard-coded constants published with the grid definition
 * ({@code nzmg.cpp:41-51}), and there is no call to {@code pj_authalic_lat}, {@code pj_qsfn} or
 * {@code pj_mlfn} anywhere in it. proj4j nonetheless carried static imports of
 * {@code ProjectionMath.authlat}, {@code authset} and {@code qsfn} — together with {@code asin},
 * {@code sin} and {@code HALFPI} — none of which was ever referenced. They are removed rather than
 * converted: re-pointing them at {@link org.locationtech.proj4j.util.AuthalicLat} would have
 * created a dependency that upstream does not have and that no code path exercises.
 *
 * <p>(The numerics reference lists {@code nzmg} among the projections the authalic-latitude fix
 * moves. That is wrong for this port, and the unused imports are why it looked otherwise.)
 *
 * <h2>The enormous eastings far from New Zealand are upstream's answer, not a defect</h2>
 *
 * <p>A golden-master triage flagged {@code SYN proj/nzmg} probe 4 — {@code (31.2132034355964, 60)},
 * central Europe — returning {@code fx = -3.52e18 m}, and asked whether it is a failure expressed as
 * a coordinate. <b>It is not: PROJ 9.8.1 returns the same number.</b> Verified by transcribing
 * {@code nzmg_e_forward} and {@code pj_zpoly1} a second time, straight from the C and independently
 * of this class; the two agree to the last ulp at every probe, and the independent transcription also
 * reproduces {@code builtins.gie}'s own expected pair at {@code (2, 1)},
 * {@code 3352675144.747425100 / -7043205391.100243600}, which validates it against upstream rather
 * than against us.
 *
 * <p>The mechanism is that this is a <em>fixed-Earth polynomial fit</em>, not a projection with a
 * closed form. The forward composes a degree-10 real series in {@code (phi - phi0)} with a degree-6
 * complex one, so it is degree ~55 in the latitude offset, and {@code nzmg.cpp} contains <b>no domain
 * check in either direction</b>. 101 degrees north of the fit's origin the polynomial simply is that
 * large. The corpus is explicit about accepting it: its {@code +proj=nzmg +ellps=GRS80} block expects
 * eastings of 3.35e9 m — millions of kilometres — as the correct answer.
 *
 * <p>So the remedy is an <b>area-of-use</b> rejection, which PROJ 9.8.1 does not have either, and
 * <em>not</em> a finiteness or magnitude guard here: a guard would reject the four corpus rows that
 * pin those 3.35e9 m values. Same category as the note on {@code EPSG:32602 -> EPSG:32717} in the
 * defect register — "PROJ 9.8.1 also fails to reject; it needs area-of-use, not a finiteness check".
 *
 * <p>What <em>did</em> move at that probe is this class's semi-major axis, by the ratio
 * {@code 6378388/6378137 = 1 + 3.9353e-5}: {@code -3.5203836748401444e18} to
 * {@code -3.520522213147237e18}, a difference of {@code 1.3854e14}. That is the
 * {@code initialize()}-ordering fix below, and it is the correction, not the defect.
 */
public class NewZealandMapGridProjection extends Projection {

    private static final long serialVersionUID = -1889984720822872169L;

    private final static Complex bf[] = {
        new Complex(.7557853228, 0.0),
        new Complex(.249204646, .003371507),
        new Complex(-.001541739, .041058560),
        new Complex(-.10162907, .01727609),
        new Complex(-.26623489, -.36249218),
        new Complex(-.6870983, -1.1651967)
    };
                 
    private final static double tphi[] = { 1.5627014243, .5185406398, -.03333098, -.1052906, -.0368594, .007317, .01220, .00394, -.0013 };
                 
    private final static double tpsi[] = { .6399175073, -.1358797613, .063294409, -.02526853, .0117879, -.0055161, .0026906, -.001333, .00067, -.00034 };

    /**
     * {@code SEC5_TO_RAD}/{@code RAD_TO_SEC5} as {@code nzmg.cpp:36-37} writes them, rather than as
     * {@code 1e5 * DTR / 3600} and {@code 1e-5 * RTD * 3600}. The two agree here, but rule 2 of
     * this port is that a constant is taken digit-for-digit from upstream and not re-derived, so
     * that the question never has to be asked again.
     */
    private final static double SECS_TO_RAD = 0.4848136811095359935899141023;
    private final static double RAD_TO_SECS = 2.062648062470963551564733573;

	public NewZealandMapGridProjection() {
		initialize();
	}
	
    @Override
	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
        Complex p = new Complex(0, 0);

        lpphi = (lpphi - projectionLatitude) * RAD_TO_SECS;
        for (int i = tpsi.length - 1; i >= 0; --i) 
            p.r = tpsi[i] + lpphi * p.r;
        p.r *= lpphi;
        p.i = lplam;
        p = zpoly1(p, bf);
        out.x = p.i;
        out.y = p.r;
        return out;
    }

    @Override
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate dst) {
        int nn, i;
        Complex p = new Complex(y, x), f, fp = new Complex(0,0), dp = new Complex(0,0);
        double den;
        double[] C;

        for (nn = 20; nn > 0 ;--nn) {
            f = zpoly1d(p, bf, fp);
            f.r -= y;
            f.i -= x;
            den = fp.r * fp.r + fp.i * fp.i;
            p.r += dp.r = -(f.r * fp.r + f.i * fp.i) / den;
            p.i += dp.i = -(f.i * fp.r - f.r * fp.i) / den;
            if ((abs(dp.r) + abs(dp.i)) <= EPS10)
                break;
        }
        if (nn > 0) {
            dst.x = p.i;
            dst.y = tphi[tphi.length - 1];
            for (i = tphi.length - 1; i > 0; i--) {
                dst.y = tphi[i-1] + p.r * dst.y;
            }
            dst.y = projectionLatitude + p.r * dst.y * SECS_TO_RAD;
        } else {
            // nzmg.cpp:111 answers HUGE_VAL here. A NaN pair would be caught by
            // Projection.inverseProjectRadians' finiteness postcondition and reported as a
            // numerical failure with no clue what failed, so say it here instead.
            throw new ConvergenceFailureException(this,
                    "inverse complex-polynomial iteration did not converge to " + EPS10
                            + " within 20 trips for (" + x + ", " + y + ")");
        }
        return dst;
    }

    /**
     * {@code PJ_PROJECTION(nzmg)}: five fixed values, and <b>all five have to be assigned before
     * {@code super.initialize()}</b>.
     * <p>
     * They were assigned after it, and {@link Projection#initialize()} is where
     * {@code totalScale = a * fromMetres} and {@code totalFalseEasting = falseEasting * fromMetres}
     * are derived. So on the <em>first</em> call — the one this class's constructor makes — the
     * derived affine came from {@code a = 0} and {@code falseEasting = 0}; on the <em>second</em>,
     * the one {@code Proj4Parser} makes after {@code setEllipsoid}, the false easting was right
     * (the first call had left the field set) but {@code totalScale} came from whatever
     * {@code +ellps} supplied. That is the "{@code initialize()} runs twice, and writing a derived
     * value into a field it also reads is not idempotent" trap, and it is the <em>only</em> defect
     * this projection had.
     * <p>
     * It is measurable exactly because New Zealand Map Grid is a fixed-Earth fit: the corpus row is
     * {@code +proj=nzmg +ellps=GRS80}, which PROJ answers on the International 1924 axis
     * regardless, so proj4j's answer was short by the ratio {@code 6378137/6378388 = 1 - 3.935e-5}
     * — <b>307 km</b> at the corpus's test point, against a 0.1 mm bar. Subtracting the false
     * easting from both sides reproduces that ratio to eleven digits, which is what identifies the
     * scale rather than the series as the cause.
     */
    @Override
    public void initialize() {
        // Force to International 1924's major axis, and set lam0/phi0/x0/y0, all BEFORE
        // super.initialize() derives totalScale and the total false easting/northing from them.
        a = 6378388.0;
        projectionLongitude = ProjectionMath.DTR * 173d;
        projectionLatitude = ProjectionMath.DTR * -41.;
        falseEasting = 2510000d;
        falseNorthing = 6023150d;
        super.initialize();
    }
  
  	public String toString() {
  		return "New Zealand Map Grid";
  	}
}

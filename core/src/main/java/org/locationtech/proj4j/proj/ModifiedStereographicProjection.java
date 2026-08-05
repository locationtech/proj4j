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
import org.locationtech.proj4j.util.Complex;
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * The shared kernel of {@code 9.8.1:src/projections/mod_ster.cpp}, which serves five
 * {@code +proj=} names: {@code mil_os}, {@code lee_os}, {@code gs48}, {@code alsk} and
 * {@code gs50}.
 *
 * <p>A modified stereographic projection is an oblique stereographic whose complex plane
 * coordinate is post-multiplied by a low-order complex polynomial fitted to minimise scale error
 * over one particular region. The forward is closed form; the inverse is a 20-iteration complex
 * Newton solve of that polynomial followed by a 20-iteration {@code chi → phi} loop, both at
 * {@code EPSLN = 1e-12}.
 *
 * <h2>Each variant stomps the ellipsoid, and that is the contract</h2>
 *
 * <p>Every one of the five {@code PJ_PROJECTION} entry points overwrites part of the ellipsoid
 * <em>after</em> the generic ellipsoid setup has run:
 *
 * <table>
 * <caption>what each variant fixes</caption>
 * <tr><th>{@code +proj=}</th><th>{@code n}</th><th>{@code lam0}</th><th>{@code phi0}</th>
 *     <th>size/shape stomp</th></tr>
 * <tr><td>{@code mil_os}</td><td>2</td><td>20&deg;</td><td>18&deg;</td><td>{@code es = 0}</td></tr>
 * <tr><td>{@code lee_os}</td><td>2</td><td>&minus;165&deg;</td><td>&minus;10&deg;</td>
 *     <td>{@code es = 0}</td></tr>
 * <tr><td>{@code gs48}</td><td>4</td><td>&minus;96&deg;</td><td>39&deg;</td>
 *     <td>{@code es = 0}, {@code a = 6370997}</td></tr>
 * <tr><td>{@code alsk}</td><td>5</td><td>&minus;152&deg;</td><td>64&deg;</td>
 *     <td>ellipsoidal: {@code a = 6378206.4}, {@code es = 0.00676866};
 *         spherical: {@code a = 6370997}</td></tr>
 * <tr><td>{@code gs50}</td><td>9</td><td>&minus;120&deg;</td><td>45&deg;</td>
 *     <td>as {@code alsk}</td></tr>
 * </table>
 *
 * <p>Three consequences, all of them upstream's and all reproduced:
 *
 * <ul>
 * <li><b>{@code alsk} and {@code gs50} choose their coefficient table by testing the
 *     <em>requested</em> {@code es}</b>, then replace it. So {@code +proj=alsk +ellps=clrk66} runs
 *     the ellipsoidal table at {@code es = 0.00676866} — <b>not</b> at clrk66's own
 *     {@code 0.0067686579972...}. The two differ in the eighth significant figure and the
 *     literal is the one {@code builtins.gie} was generated against, so it is written out
 *     digit for digit rather than derived.</li>
 * <li><b>{@code mil_os}, {@code lee_os} and {@code gs48} zero {@code es} but leave {@code e}
 *     alone.</b> {@code mod_ster_setup} then takes the spherical branch ({@code chio = phi0})
 *     while the <em>forward</em> still evaluates {@code (1 − e sinφ)/(1 + e sinφ)} with the
 *     surviving eccentricity. It looks like a bug and may be one; the corpus only exercises these
 *     three with {@code +R=}, where {@code e} is zero anyway, so nothing pins it either way and
 *     non-negotiable 7 says reproduce it.</li>
 * <li><b>{@code P->ra} is not recomputed after {@code P->a} is stomped</b>, so upstream's inverse
 *     de-scales by the <em>original</em> semi-major axis. In all seven corpus operations the two
 *     are equal ({@code clrk66} is {@code a = 6378206.4}; the spherical rows all say
 *     {@code +R=6370997}), so the distinction is unobservable here and Proj4J's single
 *     {@code totalScale} is used for both directions.</li>
 * </ul>
 *
 * <h2>{@code ProjectionMath.zpoly1}/{@code zpoly1d} are used as-is</h2>
 *
 * <p>Both are faithful ports of {@code pj_zpoly1}/{@code pj_zpolyd1} and are <em>not</em> part of
 * the deprecated {@code tsfn}/{@code phi2}/{@code mlfn} group. Their Java signatures take the
 * coefficient array and infer {@code n = length − 1}, which matches upstream's convention that
 * {@code C[0]} is present but always {@code (0,0)} — so {@code gs50}'s ten-element table is
 * {@code n = 9}, exactly as {@code mod_ster.cpp} says.
 *
 * <p><b>This is not {@code ModStereoProjection.java}.</b> That file still sits in this package with
 * its class declaration inside a block comment, so it compiles to a bare {@code package}
 * statement. It is a PROJ-4-era transcription — {@code MAX_ITER = 10}, {@code LOOP_TOL = 1e-7} —
 * and both numbers are wrong for 9.8.1, which runs two 20-trip loops at {@code 1e-12}. It should
 * be deleted; it is left alone here only because it is a pre-existing file.
 *
 * @since 1.5.0
 */
public abstract class ModifiedStereographicProjection extends Projection {

    private static final long serialVersionUID = -5328346585275786686L;

    /** {@code mod_ster.cpp:15}, {@code #define EPSLN 1e-12}. */
    protected static final double EPSLN = 1e-12;

    /** {@code mod_ster.cpp:64,91} — both Newton loops run at most 20 trips. */
    protected static final int MAX_ITER = 20;

    /**
     * {@code alsk}/{@code gs50}'s fixed ellipsoid. <b>Upstream's literal, not clrk66's own value.</b>
     * {@code mod_ster.cpp:212,257}: {@code P->e = sqrt(P->es = 0.00676866);}
     */
    protected static final double FIXED_ES = 0.00676866;

    /** {@code alsk}/{@code gs50}'s fixed semi-major axis, {@code mod_ster.cpp:211,256}. */
    protected static final double FIXED_A = 6378206.4;

    /** The spherical radius {@code gs48}, {@code alsk} and {@code gs50} fall back to. */
    protected static final double SPHERE_A = 6370997.;

    /** {@code Q->zcoeff}; {@code zcoeff[0]} is the unused {@code C_0} slot. */
    private Complex[] zcoeff;

    private double cchio;
    private double schio;

    /** Scratch for {@link ProjectionMath#zpoly1d}'s derivative out-parameter. */
    private final Complex derivative = new Complex(0, 0);

    /**
     * The variant's {@code PJ_PROJECTION} body: assign {@code projectionLongitude},
     * {@code projectionLatitude}, the coefficient table, and whatever part of the ellipsoid the
     * variant fixes. Runs <em>before</em> {@link Projection#initialize()}, so an assignment to
     * {@code a}, {@code e} or {@code es} here is picked up by {@code one_es}, {@code rone_es} and
     * {@code totalScale}.
     */
    protected abstract void setupVariant();

    /** Assigns the coefficient table; called by {@link #setupVariant()}. */
    protected final void setCoefficients(Complex[] coefficients) {
        this.zcoeff = coefficients;
    }

    /**
     * {@code PJ_PROJECTION(<variant>)} followed by {@code mod_ster_setup}
     * ({@code mod_ster.cpp:112-131}).
     */
    @Override
    public void initialize() {
        setupVariant();
        super.initialize();
        final double chio;
        if (es != 0.0) {
            final double esphi = e * FastStrictTrig.sin(projectionLatitude);
            chio = 2. * Math.atan(
                    FastStrictTrig.tan((ProjectionMath.HALFPI + projectionLatitude) * .5)
                            * Math.pow((1. - esphi) / (1. + esphi), e * .5))
                    - ProjectionMath.HALFPI;
        } else {
            chio = projectionLatitude;
        }
        schio = FastStrictTrig.sin(chio);
        cchio = FastStrictTrig.cos(chio);
    }

    /**
     * {@code mod_ster_e_forward} ({@code mod_ster.cpp:25-54}).
     *
     * @throws ProjectionException {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN} where upstream sets
     *         {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN} — the point antipodal to
     *         the projection centre in conformal space, where the stereographic denominator
     *         vanishes
     */
    @Override
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate xy) {
        final double sinlon = FastStrictTrig.sin(lam);
        final double coslon = FastStrictTrig.cos(lam);
        final double esphi = e * FastStrictTrig.sin(phi);
        final double chi = 2. * Math.atan(
                FastStrictTrig.tan((ProjectionMath.HALFPI + phi) * .5)
                        * Math.pow((1. - esphi) / (1. + esphi), e * .5))
                - ProjectionMath.HALFPI;
        final double schi = FastStrictTrig.sin(chi);
        final double cchi = FastStrictTrig.cos(chi);
        final double denom = 1. + schio * schi + cchio * cchi * coslon;
        if (denom == 0) {
            throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                    "modified-stereographic forward of (" + lam + ", " + phi + ") rad is "
                            + "outside the projection domain: the stereographic denominator "
                            + "1 + sin(chi0)sin(chi) + cos(chi0)cos(chi)cos(lam) is exactly zero");
        }
        final double s = 2. / denom;
        Complex p = new Complex(s * cchi * sinlon, s * (cchio * schi - schio * cchi * coslon));
        p = ProjectionMath.zpoly1(p, zcoeff);
        xy.x = p.r;
        xy.y = p.i;
        return xy;
    }

    /**
     * {@code mod_ster_e_inverse} ({@code mod_ster.cpp:56-108}) — complex Newton on the
     * coefficient polynomial, then Snyder's inverse stereographic, then a fixed-point solve for
     * the geodetic latitude from the conformal one.
     *
     * <p>Upstream's {@code if (nn)} guards are reproduced by throwing rather than by
     * {@code HUGE_VAL}: both loops are 20 trips at {@code 1e-12} and exhausting either means the
     * answer is not known, which is not a coordinate.
     */
    @Override
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
        int nn;
        final Complex p = new Complex(x, y);
        for (nn = MAX_ITER; nn != 0; --nn) {
            final Complex fxy = ProjectionMath.zpoly1d(p, zcoeff, derivative);
            final double fpxyR = derivative.r;
            final double fpxyI = derivative.i;
            fxy.r -= x;
            fxy.i -= y;
            final double den = fpxyR * fpxyR + fpxyI * fpxyI;
            final double dpR = -(fxy.r * fpxyR + fxy.i * fpxyI) / den;
            final double dpI = -(fxy.i * fpxyR - fxy.r * fpxyI) / den;
            p.r += dpR;
            p.i += dpI;
            if ((Math.abs(dpR) + Math.abs(dpI)) <= EPSLN) {
                break;
            }
        }
        if (nn == 0) {
            throw new ProjectionException(ErrorCause.NUMERICAL_FAILURE, this,
                    "modified-stereographic inverse of (" + x + ", " + y + "): the complex "
                            + "Newton solve of the coefficient polynomial did not converge to "
                            + EPSLN + " within " + MAX_ITER + " iterations");
        }

        final double rh = ProjectionMath.hypot(p.r, p.i);
        final double z = 2. * Math.atan(.5 * rh);
        final double sinz = FastStrictTrig.sin(z);
        final double cosz = FastStrictTrig.cos(z);
        if (Math.abs(rh) <= EPSLN) {
            // mod_ster.cpp:82-89: the input was (0, 0). Returning lam = 0 lets inv_finalize add
            // lam0 and so land exactly on the projection centre.
            lp.x = 0.0;
            lp.y = projectionLatitude;
            return lp;
        }
        final double chi = ProjectionMath.asinChecked(cosz * schio + p.i * sinz * cchio / rh);
        double phi = chi;
        for (nn = MAX_ITER; nn != 0; --nn) {
            final double esphi = e * FastStrictTrig.sin(phi);
            final double dphi = 2. * Math.atan(
                    FastStrictTrig.tan((ProjectionMath.HALFPI + chi) * .5)
                            * Math.pow((1. + esphi) / (1. - esphi), e * .5))
                    - ProjectionMath.HALFPI - phi;
            phi += dphi;
            if (Math.abs(dphi) <= EPSLN) {
                break;
            }
        }
        if (nn == 0) {
            throw new ProjectionException(ErrorCause.NUMERICAL_FAILURE, this,
                    "modified-stereographic inverse of (" + x + ", " + y + "): the conformal "
                            + "latitude " + chi + " rad did not invert to a geodetic latitude "
                            + "within " + MAX_ITER + " iterations at " + EPSLN);
        }
        lp.y = phi;
        lp.x = Math.atan2(p.r * sinz, rh * cchio * cosz - p.i * schio * sinz);
        return lp;
    }

    /** {@code mod_ster_setup} assigns both {@code P->fwd} and {@code P->inv}. */
    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public boolean isConformal() {
        // Only approximately: the coefficient polynomial is fitted to reduce scale error, which
        // trades conformality away. Upstream classes the whole family as "Azi(mod)" and makes no
        // conformality claim, so neither does this.
        return false;
    }
}

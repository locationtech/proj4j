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
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Space Oblique Mercator, {@code +proj=som} — a port of {@code 9.8.1:src/projections/som.cpp}.
 *
 * <h2>One file, three {@code +proj=} names</h2>
 *
 * <p>{@code som.cpp} declares {@code PROJ_HEAD} for <b>{@code som}, {@code misrsom} and
 * {@code lsat}</b> and serves all three from a single {@code som_setup} / {@code som_e_forward} /
 * {@code som_e_inverse} triple. The three entry points differ in exactly four values:
 *
 * <table>
 * <caption>the only differences between the three operators</caption>
 * <tr><th></th><th>{@code lam0}</th><th>{@code alf} (inclination)</th>
 *     <th>{@code p22} (day/rev)</th><th>{@code rlm}</th></tr>
 * <tr><td>{@code som}</td><td>{@code +asc_lon}</td><td>{@code +inc_angle}</td>
 *     <td>{@code +ps_rev}</td><td>{@code 0}</td></tr>
 * <tr><td>{@code misrsom}</td><td>{@code 129.3056° − 360°/233 × path}</td><td>{@code 98.30382°}</td>
 *     <td>{@code 98.88/1440}</td><td>{@code 0}</td></tr>
 * <tr><td>{@code lsat}</td><td>{@code 128.87°|129.3° − 360°/251|233 × path}</td>
 *     <td>{@code 99.092°|98.2°}</td><td>{@code 103.2669323|98.8841202 / 1440}</td>
 *     <td>{@code π (1/248 + .5161290322580645)}</td></tr>
 * </table>
 *
 * <p><b>{@code lsat} is deliberately <em>not</em> refactored onto this class.</b>
 * {@link LandsatProjection} holds an independently verified copy of the same kernel and is owned by
 * another change; folding it in would edit a contended file for no numerical gain. The two are
 * therefore duplicates on purpose, and the intended end state is that {@code LandsatProjection}
 * becomes {@code extends SpaceObliqueMercatorProjection} with an {@code initialize()} that sets
 * {@code lam0}/{@code alf}/{@code p22}/{@code rlm} from {@code +lsat} and {@code +path} and
 * nothing else. Two differences to reconcile when that happens, both in {@code LandsatProjection}'s
 * favour of nothing:
 *
 * <ul>
 * <li>Its forward's inner loop is {@code for (l = 50; l > 0; --l)}, where 9.8.1 is
 *     {@code for (l = 50; l >= 0; --l)}. C leaves {@code l == -1} after exhausting the second
 *     form, and {@code !l} is <em>false</em> for {@code -1}, so <b>upstream's
 *     convergence-failure branch is unreachable by exhaustion</b> — it runs 51 trips and then
 *     uses the answer. The {@code l > 0} form runs 50 trips and answers {@code HUGE_VAL}. This
 *     class reproduces 9.8.1.</li>
 * <li>It carries {@code if (fabs(cl) &lt; TOL) lamtp -= TOL;}, a PROJ-4-era line that 9.8.1 does
 *     not have. It is a no-op either way — {@code lamtp} is dead after {@code cl} is taken from
 *     it — so it is simply omitted here.</li>
 * </ul>
 *
 * <h2>{@code +inc_angle}, {@code +ps_rev} and {@code +asc_lon}</h2>
 *
 * <p>All three now reach this class: {@code Proj4Parser.parseProjection} dispatches them on this
 * type, the two angular ones through its DMS-capable {@code parseAngle} because upstream reads
 * them with {@code pj_param}'s {@code r} sigil ({@code som.cpp:250,259}).
 *
 * <p>Until that dispatch landed this class carried <b>one deliberate divergence from
 * 9.8.1</b> — {@link #initialize()} refused when none of the three had been set. The reason was
 * that upstream defaults all three to {@code 0} and {@code 0} passes every one of upstream's own
 * range checks, so verbatim behaviour plus the parser gap would have answered a fully-specified
 * definition with the coordinates of a satellite in a zero-inclination zero-period orbit. That
 * guard is <b>gone</b>: the values arrive, and the class is now verbatim 9.8.1, defaults
 * included. {@link MisrSpaceObliqueMercatorProjection}'s equivalent refusal stays, because
 * {@code path <= 0} is upstream's own error ({@code som.cpp:288}).
 *
 * @see LandsatProjection
 * @see MisrSpaceObliqueMercatorProjection
 * @since 1.5.0
 */
public class SpaceObliqueMercatorProjection extends Projection {

    private static final long serialVersionUID = 2076131906654145940L;

    /** {@code som.cpp:57}, {@code #define TOL 1e-7}. */
    protected static final double TOL = 1e-7;

    /** {@code proj_internal.h}'s {@code M_PI_HALFPI}, i.e. 3&pi;/2. */
    private static final double PI_HALFPI = 4.71238898038468985766;

    /** {@code proj_internal.h}'s {@code M_TWOPI_HALFPI}, i.e. 5&pi;/2. */
    private static final double TWOPI_HALFPI = 7.85398163397448309610;

    // Derived by somSetup()/seraz0(); named exactly as struct pj_som_data.
    private double a2, a4, b, c1, c3;
    private double q, t, u, w, sa, ca, xj, rlm2;

    /** Inclination angle, radians. {@code +inc_angle} for {@code som}. */
    protected double alf;

    /** Period of revolution, day/rev. {@code +ps_rev} for {@code som}. */
    protected double p22;

    /**
     * {@code Q->rlm}. Zero for {@code som} and {@code misrsom}; the Landsat value for
     * {@code lsat}. See the class comment's table.
     */
    protected double rlm;

    /** {@code Q->alf}, in radians — {@code +inc_angle}. Must lie in {@code [0, pi]}. */
    public void setIncidenceAngle(double radians) {
        this.alf = radians;
    }

    /** {@code Q->alf} in degrees, the form {@code pj_param}'s {@code "r"} sigil accepts. */
    public void setIncidenceAngleDegrees(double degrees) {
        setIncidenceAngle(degrees * DTR);
    }

    /** {@code Q->p22} — {@code +ps_rev}, the period of revolution in day/rev. Must be &ge; 0. */
    public void setPeriodOfRevolution(double dayPerRev) {
        this.p22 = dayPerRev;
    }

    /**
     * {@code P->lam0} — {@code +asc_lon}, the ascending longitude in radians. Must lie in
     * {@code [-2pi, 2pi]}, which is <em>wider</em> than {@code +lon_0}'s usual range.
     */
    public void setAscendingLongitude(double radians) {
        this.projectionLongitude = radians;
    }

    /** {@code +asc_lon} in degrees, the form {@code pj_param}'s {@code "r"} sigil accepts. */
    public void setAscendingLongitudeDegrees(double degrees) {
        setAscendingLongitude(degrees * DTR);
    }

    public double getIncidenceAngle() {
        return alf;
    }

    public double getPeriodOfRevolution() {
        return p22;
    }

    /**
     * {@code PJ_PROJECTION(som)}'s three range checks, then {@code som_setup}.
     *
     * @throws InvalidValueException with {@link ErrorCause#INVALID_PARAM_VALUE} for each of
     *         upstream's three rejections
     */
    @Override
    public void initialize() {
        super.initialize();
        // PJ_PROJECTION(som), in upstream's order: asc_lon, then inc_angle, then ps_rev.
        if (projectionLongitude < -ProjectionMath.TWOPI
                || projectionLongitude > ProjectionMath.TWOPI) {
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                    "+asc_lon=" + (projectionLongitude * RTD) + " deg: Invalid value for "
                            + "ascending longitude: should be in [-2pi, 2pi] range");
        }
        if (alf < 0 || alf > Math.PI) {
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                    "+inc_angle=" + (alf * RTD) + " deg: Invalid value for inclination angle: "
                            + "should be in [0, pi] range");
        }
        if (p22 < 0) {
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                    "+ps_rev=" + p22 + ": Number of days per rotation should be positive");
        }
        somSetup();
    }

    /**
     * {@code Projection.initialize()} plus {@code som_setup}, and nothing else — the entry point
     * for a subclass such as {@link MisrSpaceObliqueMercatorProjection} that derives
     * {@code lam0}, {@code alf}, {@code p22} and {@code rlm} itself and therefore has none of
     * the generic operator's range checks to apply.
     */
    protected final void initializeShared() {
        super.initialize();
        somSetup();
    }

    /**
     * {@code som_setup} ({@code som.cpp:206-243}) — everything that depends only on
     * {@code alf}, {@code p22}, {@code rlm} and the ellipsoid.
     */
    protected final void somSetup() {
        sa = FastStrictTrig.sin(alf);
        ca = FastStrictTrig.cos(alf);
        if (Math.abs(ca) < 1e-9) {
            ca = 1e-9;
        }
        final double esc = es * ca * ca;
        final double ess = es * sa * sa;
        w = (1. - esc) * rone_es;
        w = w * w - 1.;
        q = ess * rone_es;
        t = ess * (2. - es) * rone_es * rone_es;
        u = esc * rone_es;
        xj = one_es * one_es * one_es;
        rlm2 = rlm + ProjectionMath.TWOPI;
        a2 = a4 = b = c1 = c3 = 0.;
        seraz0(0., 1.);
        for (double lam = 9.; lam <= 81.0001; lam += 18.) {
            seraz0(lam, 4.);
        }
        for (double lam = 18; lam <= 72.0001; lam += 18.) {
            seraz0(lam, 2.);
        }
        seraz0(90., 1.);
        a2 /= 30.;
        a4 /= 60.;
        b /= 30.;
        c1 /= 15.;
        c3 /= 45.;
    }

    /**
     * {@code seraz0} ({@code som.cpp:66-85}) — one Simpson's-rule sample of the Fourier
     * coefficients of the satellite ground track.
     *
     * @param lam the sample longitude, <b>degrees</b> (upstream multiplies by
     *            {@code DEG_TO_RAD} inside)
     * @param mult the Simpson weight, 1, 2 or 4
     */
    private void seraz0(double lam, double mult) {
        lam *= DTR;
        final double sd = FastStrictTrig.sin(lam);
        final double sdsq = sd * sd;
        final double s = p22 * sa * FastStrictTrig.cos(lam)
                * Math.sqrt((1. + t * sdsq) / ((1. + w * sdsq) * (1. + q * sdsq)));
        final double d1 = 1. + q * sdsq;
        final double h = Math.sqrt((1. + q * sdsq) / (1. + w * sdsq))
                * ((1. + w * sdsq) / (d1 * d1) - p22 * ca);
        final double sq = Math.sqrt(xj * xj + s * s);
        double fc = mult * (h * xj - s * s) / sq;
        b += fc;
        a2 += fc * FastStrictTrig.cos(lam + lam);
        a4 += fc * FastStrictTrig.cos(lam * 4.);
        fc = mult * s * (h + xj) / sq;
        c1 += fc * FastStrictTrig.cos(lam);
        c3 += fc * FastStrictTrig.cos(lam * 3.);
    }

    /**
     * {@code som_e_forward} ({@code som.cpp:87-156}), ported statement for statement.
     * <p>
     * The two nested loops solve for the along-track parameter {@code lamdp}: the inner one is a
     * 51-trip fixed-point iteration at {@code TOL}, the outer one retries at most three times
     * with the ascending-node guess {@code lampp} shifted by a half revolution when the answer
     * lands outside {@code (rlm, rlm2)}.
     */
    @Override
    protected ProjCoordinate project(double lplam, double lpphi, ProjCoordinate xy) {
        int l;
        int nn;
        double lamt = 0.0;
        double lamdp = 0.0;
        double lampp;
        double sav;

        if (lpphi > ProjectionMath.HALFPI) {
            lpphi = ProjectionMath.HALFPI;
        } else if (lpphi < -ProjectionMath.HALFPI) {
            lpphi = -ProjectionMath.HALFPI;
        }
        lampp = lpphi >= 0. ? ProjectionMath.HALFPI : PI_HALFPI;
        final double tanphi = FastStrictTrig.tan(lpphi);
        for (nn = 0;;) {
            final double fac;
            sav = lampp;
            final double lamtp = lplam + p22 * lampp;
            final double cl = FastStrictTrig.cos(lamtp);
            if (cl < 0) {
                fac = lampp + FastStrictTrig.sin(lampp) * ProjectionMath.HALFPI;
            } else {
                fac = lampp - FastStrictTrig.sin(lampp) * ProjectionMath.HALFPI;
            }
            for (l = 50; l >= 0; --l) {
                lamt = lplam + p22 * sav;
                final double c = FastStrictTrig.cos(lamt);
                if (Math.abs(c) < TOL) {
                    lamt -= TOL;
                }
                final double xlam = (one_es * tanphi * sa + FastStrictTrig.sin(lamt) * ca) / c;
                lamdp = Math.atan(xlam) + fac;
                if (Math.abs(Math.abs(sav) - Math.abs(lamdp)) < TOL) {
                    break;
                }
                sav = lamdp;
            }
            // `!l` in C, where the exhausted loop leaves l == -1 and therefore does NOT take
            // this branch. See the class comment.
            if (l == 0 || ++nn >= 3 || (lamdp > rlm && lamdp < rlm2)) {
                break;
            }
            if (lamdp <= rlm) {
                lampp = TWOPI_HALFPI;
            } else if (lamdp >= rlm2) {
                lampp = ProjectionMath.HALFPI;
            }
        }
        if (l != 0) {
            final double sp = FastStrictTrig.sin(lpphi);
            final double phidp = ProjectionMath.asinChecked(
                    (one_es * ca * sp - sa * FastStrictTrig.cos(lpphi) * FastStrictTrig.sin(lamt))
                            / Math.sqrt(1. - es * sp * sp));
            final double tanph = Math.log(
                    FastStrictTrig.tan(ProjectionMath.QUARTERPI + .5 * phidp));
            final double sd = FastStrictTrig.sin(lamdp);
            final double sdsq = sd * sd;
            final double s = p22 * sa * FastStrictTrig.cos(lamdp)
                    * Math.sqrt((1. + t * sdsq) / ((1. + w * sdsq) * (1. + q * sdsq)));
            final double d = Math.sqrt(xj * xj + s * s);
            xy.x = b * lamdp + a2 * FastStrictTrig.sin(2. * lamdp)
                    + a4 * FastStrictTrig.sin(lamdp * 4.) - tanph * s / d;
            xy.y = c1 * sd + c3 * FastStrictTrig.sin(lamdp * 3.) + tanph * xj / d;
        } else {
            // Upstream answers HUGE_VAL here, which fwd_finalize turns into an error. Proj4J's
            // forward funnel rejects a non-finite result, so raising directly says the same
            // thing with the reason attached. Unreachable by exhaustion -- see the class
            // comment -- so this fires only if the inner loop converges exactly on trip 51.
            throw new ProjectionException(ErrorCause.NUMERICAL_FAILURE, this,
                    "som forward of (" + lplam + ", " + lpphi + ") rad: the along-track "
                            + "fixed-point iteration did not settle within 51 trips at TOL = "
                            + TOL);
        }
        return xy;
    }

    /**
     * {@code som_e_inverse} ({@code som.cpp:158-204}), ported statement for statement.
     *
     * <p><b>No local {@code adjlon} is needed and none is applied.</b> This kernel accumulates
     * the satellite's along-track rotation in {@code lamt - p22 * lamdp} and legitimately
     * returns a longitude well past &pi; — {@code +proj=lsat +lsat=3 +path=120} reaches 533.2&deg;
     * at {@code (130, 45)}. {@link Projection#inverseProjectRadians} used to <em>clamp</em> to
     * &plusmn;&pi;, which silently discards the revolution count; it now applies
     * {@link ProjectionMath#adjlon}, exactly as {@code inv_finalize} does, so the raw value
     * composes correctly. {@link LandsatProjection} still carries a defensive local
     * {@code adjlon} from before that fix landed; it is a no-op now and this class deliberately
     * does not copy it.
     */
    @Override
    protected ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
        int nn;
        double s = 0.;
        double lamdp = xyx / b;
        double sav;

        nn = 50;
        do {
            sav = lamdp;
            final double sd = FastStrictTrig.sin(lamdp);
            final double sdsq = sd * sd;
            s = p22 * sa * FastStrictTrig.cos(lamdp)
                    * Math.sqrt((1. + t * sdsq) / ((1. + w * sdsq) * (1. + q * sdsq)));
            lamdp = xyx + xyy * s / xj - a2 * FastStrictTrig.sin(2. * lamdp)
                    - a4 * FastStrictTrig.sin(lamdp * 4.)
                    - s / xj * (c1 * FastStrictTrig.sin(lamdp)
                            + c3 * FastStrictTrig.sin(lamdp * 3.));
            lamdp /= b;
        } while (Math.abs(lamdp - sav) >= TOL && --nn != 0);

        double sl = FastStrictTrig.sin(lamdp);
        final double fac = Math.exp(Math.sqrt(1. + s * s / xj / xj)
                * (xyy - c1 * sl - c3 * FastStrictTrig.sin(lamdp * 3.)));
        final double phidp = 2. * (Math.atan(fac) - ProjectionMath.QUARTERPI);
        final double dd = sl * sl;
        if (Math.abs(FastStrictTrig.cos(lamdp)) < TOL) {
            lamdp -= TOL;
        }
        final double spp = FastStrictTrig.sin(phidp);
        final double sppsq = spp * spp;
        final double denom = 1. - sppsq * (1. + u);
        if (denom == 0.0) {
            throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                    "som inverse of (" + xyx + ", " + xyy + ") is outside the projection "
                            + "domain: 1 - sin^2(phi'')(1 + u) is exactly zero");
        }
        double lamt = Math.atan(((1. - sppsq * rone_es) * FastStrictTrig.tan(lamdp) * ca
                - spp * sa * Math.sqrt((1. + q * dd) * (1. - sppsq) - sppsq * u)
                        / FastStrictTrig.cos(lamdp)) / denom);
        sl = lamt >= 0. ? 1. : -1.;
        final double scl = FastStrictTrig.cos(lamdp) >= 0. ? 1. : -1;
        lamt -= ProjectionMath.HALFPI * (1. - scl) * sl;
        out.x = lamt - p22 * lamdp;
        if (Math.abs(sa) < TOL) {
            out.y = ProjectionMath.asinChecked(spp / Math.sqrt(one_es * one_es + es * sppsq));
        } else {
            out.y = Math.atan((FastStrictTrig.tan(lamdp) * FastStrictTrig.cos(lamt)
                    - ca * FastStrictTrig.sin(lamt)) / (one_es * sa));
        }
        return out;
    }

    /** {@code som_setup} assigns {@code P->inv} unconditionally, for all three operators. */
    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public String toString() {
        return "Space Oblique Mercator";
    }
}

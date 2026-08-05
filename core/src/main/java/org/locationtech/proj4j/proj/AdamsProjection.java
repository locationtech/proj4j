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
import org.locationtech.proj4j.util.EllipticIntegral;
import org.locationtech.proj4j.util.FastStrictTrig;

/**
 * Shared machinery for the five conformal projections that PROJ 9.8.1 implements in one
 * file, {@code src/projections/adams.cpp}: {@code guyou}, {@code peirce_q},
 * {@code adams_hemi}, {@code adams_ws1} and {@code adams_ws2}.
 *
 * <p>Upstream is a single operator with a {@code projection_type} discriminant; here it is
 * five classes over this base, so that {@link Projection#equals(Object)} — which compares
 * {@code getClass()} — keeps them distinct in the transform cache, and so that
 * {@link #hasInverse()} can differ per operator without a mode test.
 *
 * <h2>The common tail — {@code adams.cpp:193-202}</h2>
 *
 * <p>All five reduce their input to a pair of angles {@code (a, b)} plus two sign flags,
 * and then run the identical four lines implemented by
 * {@link #ellipticTail(double, double, boolean, boolean, ProjCoordinate)}. The
 * {@code min}/{@code max} clamps inside it are what keep both {@code sqrt} arguments in
 * {@code [0, 1]}, so {@link #aasin} cannot trip its own domain check there however the
 * cosines round.
 *
 * <h2>What each subclass supplies</h2>
 *
 * <table>
 * <caption>the five reductions, with {@code sp/cp = sin/cos(phi)} and
 * {@code sl/cl = sin/cos(lam)}</caption>
 * <tr><th>operator</th><th>{@code a}</th><th>{@code b}</th><th>{@code sm}</th>
 *     <th>{@code sn}</th><th>after the tail</th></tr>
 * <tr><td>{@code guyou}</td><td>{@code aacos((cp*sl - sp)*RSQRT2)}</td>
 *     <td>{@code aacos((cp*sl + sp)*RSQRT2)}</td><td>{@code lam < 0}</td>
 *     <td>{@code phi < 0}</td><td>—</td></tr>
 * <tr><td>{@code peirce_q}</td><td>{@code aacos(cp*(sl + cl)*RSQRT2)}</td>
 *     <td>{@code aacos(cp*(sl - cl)*RSQRT2)}</td><td>{@code sl < 0}</td>
 *     <td>{@code cl > 0}</td><td>quincuncial folding</td></tr>
 * <tr><td>{@code adams_hemi}</td><td>{@code aacos(cp*sl)}</td>
 *     <td>{@code pi/2 - phi}</td><td>{@code (sp + cp*sl) < 0}</td>
 *     <td>{@code (sp - cp*sl) < 0}</td><td>rotate 45&deg;</td></tr>
 * <tr><td>{@code adams_ws1}</td><td>{@code aacos((t - s)*RSQRT2)}</td>
 *     <td>{@code aacos((t + s)*RSQRT2)}</td><td>{@code lam < 0}</td>
 *     <td>{@code phi < 0}</td><td>—</td></tr>
 * <tr><td>{@code adams_ws2}</td><td>{@code aacos(t)}</td><td>{@code aacos(s)}</td>
 *     <td>{@code (s + t) < 0}</td><td>{@code (s - t) < 0}</td>
 *     <td>rotate 45&deg;</td></tr>
 * </table>
 *
 * <p>with {@code s = tan(phi/2)} and {@code t = cos(aasin(s)) * sin(lam/2)} for the two
 * world-in-a-square variants. {@code tan(phi/2)} is exactly {@code +/-1} at
 * {@code phi = +/-pi/2}, which is why neither of them needs a domain guard.
 *
 * <h2>Why the host layer is reproduced here</h2>
 *
 * <p>PROJ applies {@code fwd_prepare} ({@code 9.8.1:src/fwd.cpp:40-80}) before any forward
 * projection code runs, and for this family three of its steps change the answer rather
 * than merely tidying it:
 *
 * <ol>
 * <li><b>{@code adjlon} into {@code (-pi, pi]}</b> is load bearing, not cosmetic.
 *     {@code adams_ws1}/{@code adams_ws2} take {@code sin(lam/2)}, which has period
 *     {@code 4*pi}, and {@code peirce_q}'s fold branches compare {@code lam} against
 *     {@code +/-0.25*pi} and {@code +/-0.75*pi} directly. The corpus feeds longitudes up to
 *     {@code 180.96} degrees, so at {@code lam = 3.146} rad the un-wrapped and wrapped
 *     values give {@code sin(lam/2)} of {@code +0.99998} and {@code -0.99997} — opposite
 *     signs, not a rounding difference.
 * <li><b>The {@code |phi| - pi/2 > 1e-12} rejection</b>, followed by clamping {@code phi}
 *     into {@code [-pi/2, pi/2]} for anything inside that slop. 245 of the six files'
 *     {@code expect failure} rows are this check and no projection's own logic.
 * <li><b>The {@code |lam| > 10} radian rejection.</b>
 * </ol>
 *
 * <p>{@code Projection.projectRadians} has no equivalent and is owned elsewhere, so the
 * checks live in {@link #project(double, double, ProjCoordinate)} and apply to these six
 * operators only — no existing projection's behaviour changes. The raw map, without any of
 * it, is {@link #projectRaw}: that is the entry point the Newton inverse and
 * {@link SpilhausProjection} use, because upstream's {@code pj_generic_inverse_2d} and
 * {@code spilhaus_forward} both call {@code P->fwd} directly and bypass {@code fwd_prepare}
 * too.
 *
 * <h2>fdlibm trigonometry, and it is measurable here</h2>
 *
 * <p>Every {@code sin}, {@code cos} and {@code tan} in this family goes through
 * {@link FastStrictTrig}, not {@link Math}. Those three are the methods HotSpot replaces with
 * architecture-specific intrinsics, and they differ from fdlibm in the last bit.
 * {@code FastStrictTrig} is a bit-identical, allocation-free transcription of the same
 * fdlibm algorithm {@link StrictMath} uses; see its class documentation for why
 * {@code StrictMath} itself is not called.
 *
 * <p>Normally a last-bit difference is far below any tolerance. <b>Not here.</b> Near the
 * antimeridian this map's own conditioning amplifies it by about {@code 3e8}. At
 * {@code +proj=adams_ws2 +ellps=WGS84}, {@code (179.999, 0)}:
 *
 * <pre>
 *   Math.sin(lam/2)        = 0x1.ffffffffac448p-1   -&gt;  x = 16686159.3838 m
 *   StrictMath.sin(lam/2)  = 0x1.ffffffffac447p-1   -&gt;  x = 16686159.3563 m
 *   exact (60 digits)                                   x = 16686159.3639 m
 *   adams_ws2.gie:2139 expects                          x = 16686159.356 m, tolerance 1 mm
 * </pre>
 *
 * <p>One ulp of {@code sin} is <b>27.5 mm</b> of easting there, and the corpus value was
 * generated by an fdlibm-equivalent {@code sin}. {@code Math} misses the assertion by 27.8 mm;
 * {@code StrictMath} hits it to 0.35 mm. Neither is the mathematically better answer — the
 * exact value sits between them — so this is not accuracy, it is bit-fidelity to the same
 * library upstream used, and it is the same reason {@code ell_int_5} is ported verbatim.
 *
 * <p>{@code asin}, {@code acos} and {@code atan2} already delegate to {@code StrictMath} inside
 * the JDK and have no intrinsic today; they are named explicitly anyway, to insure against a
 * future one. {@link Math#sqrt}, {@link Math#abs}, {@link Math#min}, {@link Math#max} and
 * {@link Math#floor} are used directly: {@code sqrt} is exactly rounded by IEEE-754 and the rest
 * are exact by construction.
 */
public abstract class AdamsProjection extends Projection {

    private static final long serialVersionUID = 4925212907167288953L;

    /** {@code TOL} from {@code adams.cpp:80}. */
    protected static final double TOL = 1e-9;

    /** {@code RSQRT2} from {@code adams.cpp:81}. */
    protected static final double RSQRT2 = EllipticIntegral.RSQRT2;

    /** {@code M_HALFPI}. */
    protected static final double HALF_PI = Math.PI / 2.0;

    /** {@code M_TWOPI}. */
    private static final double TWO_PI = Math.PI * 2.0;

    /**
     * {@code ONE_TOL} from {@code 9.8.1:src/aasincos.cpp:13}. An argument may exceed one in
     * magnitude by up to this much and be silently saturated; beyond it, the coordinate is
     * out of domain.
     */
    protected static final double ONE_TOL = 1.00000000000001;

    /** {@code PJ_EPS_LAT} from {@code 9.8.1:src/proj_internal.h:99}, in radians. */
    protected static final double EPS_LAT = 1e-12;

    /** {@code fwd_prepare}'s longitude bound, in radians ({@code fwd.cpp:66}). */
    protected static final double MAX_LAM = 10.0;

    /**
     * {@code pj_adams_setup} assigns {@code P->es = 0} ({@code adams.cpp:395}): every
     * operator in this family is spherical whatever ellipsoid was requested, while keeping
     * the requested semi-major axis as the output scale. {@code adams_ws2.gie:2125} exercises
     * exactly that with {@code +proj=adams_ws2 +ellps=WGS84}, whose expected values are the
     * spherical formula scaled by WGS84's {@code a}.
     *
     * <p>{@code e} is zeroed as well as {@code es}. Upstream only clears {@code es} — it
     * never reads {@code e} in this file — but {@link Projection#initialize()} derives
     * {@code spherical} from {@code e}, and {@code spilhaus.cpp:126} clears the child's
     * {@code e} explicitly for the same belt-and-braces reason.
     */
    @Override
    public void initialize() {
        e = 0;
        es = 0;
        super.initialize();
    }

    /**
     * These are conformal projections; that is the whole point of the family.
     */
    @Override
    public boolean isConformal() {
        return true;
    }

    /**
     * The forward map with PROJ's host-level preparation applied: reject, clamp, wrap, then
     * project. See the class comment for why each step is here.
     */
    @Override
    public ProjCoordinate project(double lam, double phi, ProjCoordinate dst) {
        validateForwardInput(lam, phi);
        if (phi > HALF_PI) {
            phi = HALF_PI;
        } else if (phi < -HALF_PI) {
            phi = -HALF_PI;
        }
        return projectRaw(adjlon(lam), phi, dst);
    }

    /**
     * The projection proper: no range checks, no clamping, no longitude wrapping — the
     * equivalent of upstream's {@code P->fwd}.
     *
     * @param lam longitude relative to the central meridian, radians, assumed in
     *            {@code (-pi, pi]}
     * @param phi latitude, radians, assumed in {@code [-pi/2, pi/2]}
     * @param dst receives the unscaled projected coordinate
     * @return {@code dst}
     */
    protected abstract ProjCoordinate projectRaw(double lam, double phi, ProjCoordinate dst);

    /**
     * {@code fwd_prepare}'s angular validity checks, {@code fwd.cpp:54-71}.
     *
     * <p>The latitude bound is {@code 1e-12} <b>radians</b> on {@code |phi| - pi/2}, about
     * {@code 5.7e-11} degrees. Approximating it in degree space misclassifies dozens of
     * corpus points, several of which sit within a micro-degree of the pole.
     *
     * @throws ProjectionException if the coordinate is one PROJ answers with
     *         {@code PROJ_ERR_COORD_TRANSFM_INVALID_COORD}
     */
    protected static void validateForwardInput(double lam, double phi) {
        if (Double.isInfinite(lam) || Double.isInfinite(phi)) {
            throw new ProjectionException(ErrorCause.INVALID_COORDINATE,
                    "Invalid coordinate: non-finite ordinate");
        }
        if (Math.abs(phi) - HALF_PI > EPS_LAT) {
            throw new ProjectionException(ErrorCause.INVALID_COORDINATE,
                    "Invalid latitude: |phi| - pi/2 = " + (Math.abs(phi) - HALF_PI)
                            + " rad exceeds PJ_EPS_LAT");
        }
        if (lam > MAX_LAM || lam < -MAX_LAM) {
            throw new ProjectionException(ErrorCause.INVALID_COORDINATE,
                    "Invalid longitude: " + lam + " rad is outside +/-10");
        }
    }

    /**
     * The last four lines of {@code adams_forward} ({@code adams.cpp:193-202}), shared by all
     * five operators.
     *
     * <p>{@code Math.min(0.0, cos(a + b))} and {@code Math.max(0.0, cos(a - b))} are the
     * clamps that keep {@code 1 + min} and {@code |1 - max|} inside {@code [0, 1]}; the
     * {@code Math.abs} on the second is upstream's and guards the {@code cos(a - b)} slightly
     * above one case.
     *
     * @param a  first reduced angle, radians
     * @param b  second reduced angle, radians
     * @param sm negate the {@code x} amplitude
     * @param sn negate the {@code y} amplitude
     * @param dst receives {@code (ellInt5(m), ellInt5(n))}
     * @return {@code dst}
     */
    protected static ProjCoordinate ellipticTail(double a, double b, boolean sm, boolean sn,
            ProjCoordinate dst) {
        double m = aasin(Math.sqrt(1. + Math.min(0.0, FastStrictTrig.cos(a + b))));
        if (sm) {
            m = -m;
        }
        double n = aasin(Math.sqrt(Math.abs(1. - Math.max(0.0, FastStrictTrig.cos(a - b)))));
        if (sn) {
            n = -n;
        }
        dst.x = EllipticIntegral.ellInt5(m);
        dst.y = EllipticIntegral.ellInt5(n);
        return dst;
    }

    /**
     * Rotate by 45 degrees, {@code adams.cpp:287-291}. Applied by {@code adams_hemi},
     * {@code adams_ws2} and {@code peirce_q}'s {@code square} shape.
     */
    protected static void rotate45(ProjCoordinate dst) {
        final double temp = dst.x;
        dst.x = RSQRT2 * (dst.x - dst.y);
        dst.y = RSQRT2 * (temp + dst.y);
    }

    /**
     * {@code aasin} from {@code 9.8.1:src/aasincos.cpp:16-25}.
     *
     * <p><b>Not {@code ProjectionMath.asin}.</b> That one clamps at {@code |v| > 1} with no
     * tolerance band and no error signal, and because {@code Math.abs(NaN) > 1} is false it
     * passes {@code NaN} through to {@code Math.asin} and returns {@code NaN} — both of which
     * manufacture a plausible-looking result where PROJ signals failure. Existing projections
     * depend on that silent clamp, so it is left alone and this sits alongside it.
     *
     * @throws ProjectionException when {@code |v|} exceeds {@link #ONE_TOL}, where PROJ sets
     *         {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN}
     */
    protected static double aasin(double v) {
        final double av = Math.abs(v);
        if (av >= 1.0) {
            if (av > ONE_TOL) {
                throw new ProjectionException("aasin: argument " + v + " outside [-1, 1]");
            }
            return v < 0 ? -HALF_PI : HALF_PI;
        }
        return StrictMath.asin(v);
    }

    /**
     * {@code aacos} from {@code 9.8.1:src/aasincos.cpp:27-36}.
     *
     * @throws ProjectionException when {@code |v|} exceeds {@link #ONE_TOL}
     */
    protected static double aacos(double v) {
        final double av = Math.abs(v);
        if (av >= 1.0) {
            if (av > ONE_TOL) {
                throw new ProjectionException("aacos: argument " + v + " outside [-1, 1]");
            }
            return v < 0 ? Math.PI : 0.0;
        }
        return StrictMath.acos(v);
    }

    /**
     * {@code aatan2} from {@code 9.8.1:src/aasincos.cpp:38-42}: {@code atan2}, except that
     * two ordinates both below {@code 1e-50} in magnitude give exactly zero rather than
     * whatever {@code atan2} makes of a pair of denormals.
     */
    protected static double aatan2(double n, double d) {
        return (Math.abs(n) < 1e-50 && Math.abs(d) < 1e-50) ? 0.0 : StrictMath.atan2(n, d);
    }

    /**
     * {@code adjlon} from {@code 9.8.1:src/adjlon.cpp}: reduce to {@code (-pi, pi]}.
     *
     * <p><b>Not {@code ProjectionMath.normalizeLongitude}.</b> Upstream deliberately lets the
     * value overshoot while {@code |lon| < pi + 1e-12}, "to avoid spurious sign switching at
     * the date line"; {@code normalizeLongitude} wraps as soon as {@code lon > pi}. The two
     * differ over a {@code 1e-12} rad band straddling the antimeridian, and this family has
     * corpus points inside it.
     */
    protected static double adjlon(double longitude) {
        if (Math.abs(longitude) < Math.PI + 1e-12) {
            return longitude;
        }
        longitude += Math.PI;
        longitude -= TWO_PI * Math.floor(longitude / TWO_PI);
        longitude -= Math.PI;
        return longitude;
    }
}

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

package org.locationtech.proj4j.util;

import org.locationtech.proj4j.ConvergenceFailureException;

/**
 * The conformal latitude and its inverse: ports of PROJ 9.8.1's
 * {@code src/phi2.cpp} ({@code pj_sinhpsi2tanphi}, {@code pj_phi2}),
 * {@code src/tsfn.cpp} ({@code pj_tsfn}) and {@code src/latitudes.cpp:18-48}
 * ({@code pj_conformal_lat}, {@code pj_conformal_lat_inverse}).
 *
 * <p>The isometric latitude {@code psi} is defined by
 * <pre>
 *   psi = log(tan(pi/4 + phi/2) * ((1 - e sin phi)/(1 + e sin phi))^(e/2))
 *       = asinh(tan(phi)) - e * atanh(e * sin(phi))
 *       = asinh(tan(chi))
 * </pre>
 * where {@code chi} is the conformal latitude. {@link #tsfn} returns
 * {@code ts = exp(-psi)}; {@link #phi2} inverts it.
 *
 * <p>Two measured facts justify replacing proj4j's equivalents. On GRS80,
 * {@code ProjectionMath.phi2} — a 15-step Newton loop calling {@code pow} on every
 * trip — is up to 4,145 nm from the truth against the 50 nm bar that {@code merc}'s
 * inverse conformance assertions set; this Newton-on-tau formulation is about 2 nm and
 * converges in one or two trips. And {@code ProjectionMath.tsfn} returns
 * {@code 0.9999999999999999} at {@code phi = 0}, where the corpus asserts a result at
 * {@code tolerance 0 m}; {@link #tsfn} returns exactly {@code 1.0} there, because
 * {@code atanh(0)} is exactly zero, {@code exp(0)} exactly one, and the {@code phi <= 0}
 * branch reduces to {@code 1/1}.
 *
 * <p><b>Math vs StrictMath:</b> {@code exp}, {@code log}, {@code sin}, {@code cos},
 * {@code tan} and {@code pow} are the {@link Math} methods HotSpot replaces with
 * architecture-specific intrinsics on x86-64 and AArch64, and they differ at the last
 * bit between them; all such calls here go through fdlibm so that results are identical
 * on every platform — {@code exp} and {@code log} on {@link StrictMath}, and
 * {@code sin}/{@code cos}/{@code tan} on {@link FastStrictTrig}, which is bit-identical to
 * {@code StrictMath} but does not allocate the {@code double[2]} argument-reduction
 * carrier that {@code StrictMath.sin/cos/tan} have allocated since JDK 21 (21, not 17:
 * through 17 they are {@code native} JNI calls into compiled fdlibm and allocate nothing
 * — see {@link FastStrictTrig} for the evidence).
 * {@code sinh} and {@code atan} have no
 * intrinsic today and already delegate to {@code StrictMath}, so naming it explicitly is
 * free and insures against a future one. {@link Math#sqrt}, {@link Math#abs} and
 * {@link Math#max} are used directly: {@code sqrt} is exactly rounded by IEEE-754 and
 * the other two are exact by construction.
 *
 * @see MathHelpers
 */
public final strictfp class ConformalLat {

    /**
     * {@code sqrt(DBL_EPSILON)} = 2<sup>-26</sup>, written as a literal because it is an
     * exact power of two and must not depend on the platform's {@code sqrt} of
     * {@code 2^-52} (it does not, but a literal removes the question).
     */
    public static final double ROOTEPS = 1.4901161193847656E-8;

    /**
     * {@code 2 / ROOTEPS} = 2<sup>27</sup>. Above this magnitude of {@code tau} the
     * large-argument limit {@code tau = exp(e * atanh(e)) * taup} is exact to double
     * precision, so returning it directly both avoids overflow in {@code tau*tau} and
     * gives the right answer for {@code taup = +/-inf} and {@code NaN}.
     */
    public static final double TMAX = 1.34217728E8;

    /** The Newton convergence criterion, {@code ROOTEPS / 10}. */
    private static final double TOL = ROOTEPS / 10.0;

    /** Iteration cap. Upstream measures min 1, max 2, mean 1.954 for {@code |f| <= 1/150}. */
    private static final int NUMIT = 5;

    private static final double HALF_PI = Math.PI / 2.0;

    private ConformalLat() {
    }

    /**
     * Converts {@code tau' = sinh(psi) = tan(chi)} to {@code tau = tan(phi)}. Port of
     * {@code pj_sinhpsi2tanphi}, itself taken from
     * {@code GeographicLib::Math::tauf(taup, e)}.
     *
     * <p>Representing both latitudes by their tangents maintains full <em>relative</em>
     * accuracy at the equator and at the poles, which matters for quantities such as
     * {@code cos(phi)/cos(chi) * tan(phi)} — the transverse Mercator scale factor.
     *
     * <p>From Karney (2011), Eq. 7,
     * <pre>
     *   tau' = tau * sqrt(1 + sigma^2) - sqrt(1 + tau^2) * sigma
     *   sigma = sinh(e * atanh(e * tau / sqrt(1 + tau^2)))
     * </pre>
     * which for small {@code e} reduces to {@code tau' = (1 - e^2) tau}. Newton's method
     * on that relation uses
     * <pre>
     *   dtau'/dtau = (1 - e^2) sqrt(1 + tau'^2) sqrt(1 + tau^2) / (1 + (1 - e^2) tau^2)
     * </pre>
     *
     * <p><b>Three details are load bearing and must not be re-derived.</b>
     *
     * <ul>
     * <li>The initial guess branches on {@code |taup| > 70}, which is {@code chi = 89.18}
     * degrees. Above it, {@code tau = taup * exp(e * atanh(e))}; below it,
     * {@code taup / (1 - e^2)}. This is what makes the loop terminate in <em>one</em>
     * iteration near the poles rather than three or four.
     * <li>{@code !(Math.abs(tau) < TMAX)} rather than {@code Math.abs(tau) >= TMAX}. The
     * inverted test is true for {@code NaN}, so {@code NaN} and {@code +/-inf} return
     * immediately instead of entering a loop that would compute {@code inf - inf}.
     * <li>{@code !(Math.abs(dtau) >= stol)} rather than {@code Math.abs(dtau) < stol},
     * for the same reason: a {@code NaN} step must terminate the loop, not spin it.
     * </ul>
     *
     * <p><b>Deviation from upstream:</b> where PROJ records
     * {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE} on the context and returns the
     * unconverged value anyway, this throws {@link ConvergenceFailureException}. A
     * failure must not be expressed as a plausible coordinate. For any sane eccentricity
     * the cap is never reached — upstream measures a maximum of two iterations.
     *
     * @param taup {@code tan(chi)}, equivalently {@code sinh(psi)}
     * @param e    the first eccentricity
     * @return {@code tan(phi)}
     * @throws ConvergenceFailureException if Newton's method does not converge in
     *                                     {@value #NUMIT} iterations
     */
    public static double sinhpsi2tanphi(double taup, double e) {
        final double e2m = 1.0 - e * e;
        final double stol = TOL * Math.max(1.0, Math.abs(taup));
        // The initial guess. 70 corresponds to chi = 89.18 deg.
        double tau = Math.abs(taup) > 70.0
                ? taup * StrictMath.exp(e * MathHelpers.atanh(e))
                : taup / e2m;
        if (!(Math.abs(tau) < TMAX)) {
            // Handles +/-inf, NaN and e = 1. Inverted on purpose: NaN must exit here.
            return tau;
        }
        int i = NUMIT;
        for (; i != 0; --i) {
            final double tau1 = Math.sqrt(1.0 + tau * tau);
            final double sig = StrictMath.sinh(e * MathHelpers.atanh(e * tau / tau1));
            final double taupa = Math.sqrt(1.0 + sig * sig) * tau - sig * tau1;
            final double dtau = (taup - taupa) * (1.0 + e2m * (tau * tau))
                    / (e2m * tau1 * Math.sqrt(1.0 + taupa * taupa));
            tau += dtau;
            if (!(Math.abs(dtau) >= stol)) {
                // Backwards test to allow NaNs to succeed.
                break;
            }
        }
        if (i == 0) {
            throw new ConvergenceFailureException(
                    "sinhpsi2tanphi failed to converge after " + NUMIT
                            + " iterations for taup=" + taup + " e=" + e);
        }
        return tau;
    }

    /**
     * Determines the latitude angle phi-2 from {@code ts = exp(-psi)}. Port of
     * {@code pj_phi2}.
     *
     * <p>Converts {@code ts} to {@code tau' = tan(chi) = sinh(psi) = (1/ts - ts) / 2} and
     * returns {@code atan(sinhpsi2tanphi(tau'))}. The formulation is exactly odd in
     * {@code ts}, and note that {@code +0.0} and {@code -0.0} give different results
     * ({@code -pi/2} and {@code +pi/2} respectively) because {@code 1/(-0.0)} is
     * {@code -inf}.
     *
     * @param ts {@code exp(-psi)}, Snyder (1987) Eq. (7-10)
     * @param e  the first eccentricity
     * @return the geographic latitude, radians
     * @throws ConvergenceFailureException if the underlying Newton iteration fails
     */
    public static double phi2(double ts, double e) {
        return StrictMath.atan(sinhpsi2tanphi((1.0 / ts - ts) / 2.0, e));
    }

    /**
     * Determines {@code ts = exp(-psi)}, Snyder (1987) Eq. (7-10). Port of
     * {@code pj_tsfn}.
     *
     * <p>Since {@code exp(-asinh(tan(phi))) = 1 / (tan(phi) + sec(phi))}, this is
     * evaluated as {@code cos(phi) / (1 + sin(phi))} for {@code phi > 0} and
     * {@code (1 - sin(phi)) / cos(phi)} otherwise, each branch chosen so that no
     * cancellation occurs. Upstream's {@code tan} and {@code pow} are gone; at
     * {@code phi = 0} the result is exactly {@code 1.0}.
     *
     * @param phi    the geographic latitude, radians
     * @param sinphi {@code sin(phi)}
     * @param e      the first eccentricity
     * @return {@code exp(-psi)}
     */
    public static double tsfn(double phi, double sinphi, double e) {
        return tsfnSinCos(sinphi, FastStrictTrig.cos(phi), e);
    }

    /**
     * {@code ts = exp(-psi)} from the sine and cosine of the latitude, saving the
     * {@code cos} call for callers that already have both.
     *
     * <p>Identical arithmetic to {@link #tsfn}; the argument order differs so that the
     * two can coexist despite Java's erasure of primitive parameter names.
     * {@code tsfnSinCos(0.0, 1.0, e)} is exactly {@code 1.0} for every {@code e}.
     *
     * @param sinphi {@code sin(phi)}
     * @param cosphi {@code cos(phi)}
     * @param e      the first eccentricity
     * @return {@code exp(-psi)}
     */
    public static double tsfnSinCos(double sinphi, double cosphi, double e) {
        return StrictMath.exp(e * MathHelpers.atanh(e * sinphi))
                * (sinphi > 0.0 ? cosphi / (1.0 + sinphi) : (1.0 - sinphi) / cosphi);
    }

    /**
     * The conformal latitude {@code chi} in terms of the geographic latitude. Port of
     * {@code pj_conformal_lat}.
     *
     * <p>Uses {@code sin} and {@code cos} rather than {@code tan} and {@code sin} so a
     * compiler or JIT can pair them, and relies on {@link MathHelpers#asinh}, whose
     * {@code log1p} branch is what keeps this accurate at the equator.
     *
     * @param phi the geographic latitude, radians
     * @param e   the first eccentricity; {@code 0} returns {@code phi} unchanged
     * @return the conformal latitude, radians
     */
    public static double conformalLat(double phi, double e) {
        if (e == 0.0) {
            return phi;
        }
        final double sphi = FastStrictTrig.sin(phi);
        final double cphi = FastStrictTrig.cos(phi);
        return StrictMath.atan(StrictMath.sinh(
                MathHelpers.asinh(sphi / cphi) - e * MathHelpers.atanh(e * sphi)));
    }

    /**
     * The geographic latitude in terms of the conformal latitude {@code chi}. Port of
     * {@code pj_conformal_lat_inverse}.
     *
     * @param chi the conformal latitude, radians
     * @param e   the first eccentricity; {@code 0} returns {@code chi} unchanged
     * @return the geographic latitude, radians
     * @throws ConvergenceFailureException if the underlying Newton iteration fails
     */
    public static double conformalLatInverse(double chi, double e) {
        if (e == 0.0) {
            return chi;
        }
        return StrictMath.atan(sinhpsi2tanphi(FastStrictTrig.tan(chi), e));
    }

    /**
     * {@code pi/2}, exposed because {@link #phi2} is specified to return it exactly at
     * {@code ts = +0.0}.
     *
     * @return {@code Math.PI / 2}
     */
    public static double halfPi() {
        return HALF_PI;
    }
}

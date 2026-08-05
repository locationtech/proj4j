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

/**
 * The authalic (equal-area) latitude {@code xi} and its inverse, a port of PROJ 9.8.1's
 * {@code src/latitudes.cpp:55-155} ({@code pj_authalic_lat_q},
 * {@code pj_authalic_lat_compute_coeffs}, {@code pj_authalic_lat},
 * {@code pj_authalic_lat_inverse}).
 *
 * <p>This is the largest accuracy win in the numerical core. proj4j's
 * {@code ProjectionMath.authset}/{@code authlat} is the same idea but only third order,
 * and measured on GRS80 it is <b>1.58 mm</b> off at latitude 20.8 degrees against the
 * 0.1 mm bar that {@code aea}'s inverse conformance assertions set — sixteen times the
 * bar, and it moves {@code laea}, {@code aea}, {@code cea}, {@code eqearth},
 * {@code healpix} and {@code nzmg}. The order-6 series here is sub-nanometre.
 *
 * <p><b>The series is always used for real ellipsoids.</b>
 * {@code PROJ_AUTHALIC_SERIES_VALID(n)} is {@code |n| < 0.01}; every Earth ellipsoid has
 * {@code n} about 0.00168, so the series branch is always taken and <b>there is never
 * any iteration</b>. The Newton fallback exists only for very oblate bodies and the
 * synthetic conformance cases such as {@code +proj=aea +a=9999999 +b=.9}.
 *
 * <p><b>The forward direction changes semantics.</b> 9.8.1 no longer computes
 * {@code asin(q(sin phi) / qp)} when the series is valid; it applies the direct
 * {@code phi -> xi} series, because the {@code asin} form loses relative accuracy at the
 * poles. {@link #q} stays public because {@code laea} and {@code aea} still need the raw
 * value ({@code laea.cpp:39}: {@code q = sin(xi) * qp}).
 *
 * <p>Instances are immutable and thread safe. Construct one per ellipsoid.
 *
 * <p><b>Math vs StrictMath:</b> {@code exp}/{@code log} routes through
 * {@link MathHelpers#atanh}, which uses {@code StrictMath.log1p}; {@code sin} and
 * {@code cos} use {@link FastStrictTrig} because HotSpot's
 * {@code Math.sin}/{@code Math.cos} intrinsics differ between x86-64 and AArch64, and
 * because {@code StrictMath.sin}/{@code cos} — which {@code FastStrictTrig} reproduces
 * bit for bit — allocate a {@code double[2]} per call from JDK 21 onward. (21, not 17:
 * through 17 those two are still {@code native} JNI calls into compiled fdlibm and
 * allocate nothing — see {@link FastStrictTrig} for the evidence.)
 * {@code asin} has no intrinsic today but is named on {@code StrictMath} for the same
 * insurance. {@link Math#sqrt} and {@link Math#abs} are exact and used directly.
 *
 * @see AuxLat
 * @see Clenshaw6
 */
public final strictfp class AuthalicLat implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    /** {@code PROJ_AUTHALIC_SERIES_VALID(n)} is {@code fabs(n) < 0.01}. */
    private static final double SERIES_CUTOFF = 0.01;

    /** Below this eccentricity {@code q} degenerates to {@code 2 sin(phi)}. */
    private static final double E_EPSILON = 1e-7;

    /** Newton tolerance for the large-flattening fallback. */
    private static final double NEWTON_TOL = 1e-15;

    /** Newton iteration cap for the large-flattening fallback. */
    private static final int NEWTON_MAX_ITER = 10;

    private final double e;
    private final double es;
    private final double oneEs;
    private final double n;
    private final double qp;
    private final boolean seriesValid;

    /** {@code xi -> phi}; upstream's {@code APA[0..Lmax-1]}. Always present. */
    private final Clenshaw6 xiToPhi;

    /**
     * {@code phi -> xi}; upstream's {@code APA[Lmax..2*Lmax-1]}, allocated only when the
     * series is valid.
     */
    private final Clenshaw6 phiToXi;

    /**
     * Builds the authalic-latitude machinery for an ellipsoid.
     *
     * @param es the squared first eccentricity
     */
    public AuthalicLat(double es) {
        this.es = es;
        this.e = Math.sqrt(es);
        this.oneEs = 1.0 - es;
        this.n = AuxLat.thirdFlattening(es);
        this.seriesValid = Math.abs(n) < SERIES_CUTOFF;
        this.xiToPhi = Clenshaw6.forConversion(n, AuxLat.AUTHALIC, AuxLat.GEOGRAPHIC);
        this.phiToXi = seriesValid
                ? Clenshaw6.forConversion(n, AuxLat.GEOGRAPHIC, AuxLat.AUTHALIC)
                : null;
        this.qp = q(1.0);
    }

    /**
     * Builds the machinery from the semi-axes, using PROJ's two-step shape derivation
     * ({@code f = (a - b) / a} then {@code es = 2f - f^2}) rather than
     * {@code es = 1 - b^2/a^2}. The two are algebraically equal and differ by about
     * 1 ulp.
     *
     * @param a the semi-major axis
     * @param b the semi-minor axis
     * @return the machinery for that ellipsoid
     */
    public static AuthalicLat fromAxes(double a, double b) {
        final double f = (a - b) / a;
        return new AuthalicLat(2.0 * f - f * f);
    }

    /**
     * The coefficient {@code q} such that the authalic latitude is
     * {@code asin(q / qp)}. Snyder (3-11) and (3-12); port of
     * {@code pj_authalic_lat_q}.
     *
     * <p>Snyder writes the second term as {@code 0.5 * log((1 - e sin phi)/(1 + e sin phi))},
     * which is {@code -atanh(e sin phi)}. proj4j's {@code ProjectionMath.qsfn} uses the
     * logarithmic form and loses relative accuracy near {@code phi = 0} to cancellation;
     * {@link MathHelpers#atanh} does not.
     *
     * <p>Returns {@link Double#POSITIVE_INFINITY} rather than dividing by zero when
     * {@code 1 - (e sin phi)^2} underflows to exactly zero, matching upstream's
     * {@code HUGE_VAL}.
     *
     * @param sinphi {@code sin(phi)}
     * @return {@code q}
     */
    public double q(double sinphi) {
        if (e >= E_EPSILON) {
            final double eSinphi = e * sinphi;
            final double oneMinusESinphiSq = 1.0 - eSinphi * eSinphi;
            if (oneMinusESinphiSq == 0.0) {
                // Avoid zero division, fail gracefully, as upstream does.
                return Double.POSITIVE_INFINITY;
            }
            return oneEs * (sinphi / oneMinusESinphiSq + MathHelpers.atanh(eSinphi) / e);
        }
        return 2.0 * sinphi;
    }

    /**
     * {@code q} evaluated at {@code phi = 90} degrees, i.e. {@code q(1.0)}. Callers such
     * as {@code laea} and {@code aea} need it directly.
     *
     * @return {@code qp}
     */
    public double qp() {
        return qp;
    }

    /**
     * The authalic latitude {@code xi} from the geographic latitude. Port of
     * {@code pj_authalic_lat}.
     *
     * <p>When the series is valid — always, for Earth ellipsoids — this is the direct
     * {@code phi -> xi} Clenshaw sum. Otherwise it falls back to
     * {@code asin(q / qp)}, clamped for rounding error; that form is ill-conditioned
     * near the poles, which is precisely why it is not used when the series applies.
     *
     * @param phi    the geographic latitude, radians
     * @param sinphi {@code sin(phi)}
     * @param cosphi {@code cos(phi)}
     * @return the authalic latitude, radians
     */
    public double forward(double phi, double sinphi, double cosphi) {
        if (seriesValid) {
            return phiToXi.convert(phi, sinphi, cosphi);
        }
        double ratio = q(sinphi) / qp;
        if (Math.abs(ratio) > 1.0) {
            ratio = ratio > 0.0 ? 1.0 : -1.0; // Rounding error.
        }
        return StrictMath.asin(ratio);
    }

    /**
     * The authalic latitude {@code xi} from the geographic latitude, computing the sine
     * and cosine internally.
     *
     * @param phi the geographic latitude, radians
     * @return the authalic latitude, radians
     */
    public double forward(double phi) {
        return forward(phi, FastStrictTrig.sin(phi), FastStrictTrig.cos(phi));
    }

    /**
     * The geographic latitude from the authalic latitude {@code beta}. Port of
     * {@code pj_authalic_lat_inverse}.
     *
     * <p>The {@code xi -> phi} Clenshaw sum is the whole answer when {@code |n| < 0.01}.
     * Otherwise it seeds at most {@value #NEWTON_MAX_ITER} Newton steps on
     * <pre>
     *   f(phi) = qp sin(beta)/(1-e^2) - q(phi)/(1-e^2) = 0
     *   df/dphi = -2 (1-e^2) cos(phi) / (1 - e^2 sin^2 phi)^2
     * </pre>
     * with tolerance {@value #NEWTON_TOL}. That refinement is subject to large roundoff
     * near the poles, which is why it runs only when the series is not accurate.
     *
     * <p>The loop's exit test is written {@code !(Math.abs(dphi) >= NEWTON_TOL)}, as
     * upstream writes it, so that a {@code NaN} step terminates rather than exhausting
     * the cap.
     *
     * @param beta the authalic latitude, radians
     * @return the geographic latitude, radians
     */
    public double inverse(double beta) {
        double phi = xiToPhi.convert(beta);
        if (seriesValid) {
            return phi;
        }
        final double q = FastStrictTrig.sin(beta) * qp;
        final double qDivOneMinusEs = q / oneEs;
        for (int i = 0; i < NEWTON_MAX_ITER; ++i) {
            final double sinphi = FastStrictTrig.sin(phi);
            final double cosphi = FastStrictTrig.cos(phi);
            final double oneMinusEsSin2phi = 1.0 - es * (sinphi * sinphi);
            final double dphi = (oneMinusEsSin2phi * oneMinusEsSin2phi) / (2.0 * cosphi)
                    * (qDivOneMinusEs - sinphi / oneMinusEsSin2phi
                       - MathHelpers.atanh(e * sinphi) / e);
            if (!(Math.abs(dphi) >= NEWTON_TOL)) {
                break;
            }
            phi += dphi;
        }
        return phi;
    }

    /**
     * Whether the order-6 series is accurate enough to be used on its own, i.e.
     * {@code |n| < 0.01}. False implies {@link #inverse} iterates and {@link #forward}
     * uses the {@code asin} form.
     *
     * @return true if the series branch is taken
     */
    public boolean isSeriesValid() {
        return seriesValid;
    }

    /**
     * The first eccentricity.
     *
     * @return {@code e}
     */
    public double eccentricity() {
        return e;
    }

    /**
     * The squared first eccentricity.
     *
     * @return {@code es}
     */
    public double eccentricitySquared() {
        return es;
    }

    /**
     * The third flattening this instance was built for.
     *
     * @return {@code n}
     */
    public double thirdFlattening() {
        return n;
    }

    /**
     * The {@code phi -> xi} evaluator, or {@code null} when the series is not valid.
     *
     * @return the forward Clenshaw evaluator, possibly {@code null}
     */
    public Clenshaw6 forwardSeries() {
        return phiToXi;
    }

    /**
     * The {@code xi -> phi} evaluator. Always present; upstream uses it as the Newton
     * seed when the series alone is insufficient.
     *
     * @return the inverse Clenshaw evaluator
     */
    public Clenshaw6 inverseSeries() {
        return xiToPhi;
    }
}

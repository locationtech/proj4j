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
 * The incomplete elliptic integral of the first kind at {@code m = k^2 = 1/2}, as PROJ
 * computes it: a verbatim port of {@code ell_int_5} from
 * {@code 9.8.1:src/projections/adams.cpp:83-108}.
 *
 * <p><b>This is deliberately not an accurate elliptic integral, and must not be replaced
 * with one.</b> {@code ell_int_5} is a seven-term even Chebyshev series whose own comment
 * claims "precision good to better than 1e-7". Measured against a Carlson {@code RF}
 * duplication reference over {@code phi} in {@code [0, pi/2]}:
 *
 * <pre>
 *   max |ellInt5(phi) - F(phi | 1/2)|  =  6.61404e-08   at phi = 1.27699
 *       -&gt; 0.4214 m at +R=6370997
 *   ellInt5(pi/2) = 1.854074716833181
 *   true K(1/2)   = 1.854074677301372     (delta 3.95e-08 -&gt; 0.25 m)
 * </pre>
 *
 * <p>The {@code gie} tolerance for {@code guyou}, {@code adams_hemi}, {@code adams_ws1}
 * and {@code adams_ws2} is <b>1 mm</b>, so the series is 420x the tolerance away from the
 * true integral — and the ~3,400 expected values in PROJ's own corpus for that family
 * were generated <em>by this series</em>. A mathematically better integral fails every
 * one of them. Hence: the Clenshaw recurrence, the coefficient order and the
 * {@code 0.5 * C0} final term are reproduced exactly, and no Carlson symmetric form, AGM,
 * or complex elliptic function appears anywhere in this family in PROJ 9.8.1.
 *
 * <p>Three constants in the same upstream file are pinned here for the same reason, and
 * two of them contradict each other on purpose:
 *
 * <ul>
 * <li>{@link #K_HALF} is the <em>accurate</em> {@code K(1/2)} literal that
 *     {@code adams.cpp:209} uses to build {@code peirce_q}'s shift distance
 *     {@code shd = 1.8540746773013719 * 2}. It is <b>not</b> {@code ellInt5(pi/2)}.
 * <li>{@link #GUYOU_POLE} is the <em>truncated</em> literal {@code 1.85407} that
 *     {@code guyou}'s pole special case returns ({@code adams.cpp:126}) — 30.05 m from
 *     {@code ellInt5(pi/2)} at {@code +R=6370997}, and asserted at 1 mm by
 *     {@code guyou.gie}'s {@code +R=1} block.
 * <li>{@link #RSQRT2} is {@code 1/sqrt(2)} written to 28 digits as {@code adams.cpp:81}
 *     writes it. It is bit-equal to {@code Math.sqrt(0.5)} but <b>not</b> to
 *     {@code 1.0 / Math.sqrt(2.0)}, which is one ulp away.
 * </ul>
 *
 * <p>Arithmetic only — no {@link Math} transcendental is called, so there is nothing for a
 * platform intrinsic to differ on and {@code strictfp} would be a no-op on any JVM from 17
 * onwards. It is stated anyway, for the same reason the coefficients are: this class
 * exists to be bit-reproducible.
 */
public final strictfp class EllipticIntegral {

    /**
     * {@code RSQRT2} from {@code 9.8.1:src/projections/adams.cpp:81}, written as the same
     * 28-digit decimal the C writes.
     *
     * <p>The nearest {@code double} to it is {@code 0x3FE6A09E667F3BCD}, which is exactly
     * what {@code Math.sqrt(0.5)} returns and exactly one ulp below what
     * {@code 1.0 / Math.sqrt(2.0)} returns. The difference is 1.1e-16 relative — a tenth of
     * a micron on Earth radius, which is below every tolerance in the corpus, but the point
     * of this family is that upstream's exact bits are the specification.
     */
    public static final double RSQRT2 = 0.7071067811865475244008443620;

    /**
     * The complete elliptic integral {@code K(m = 1/2) = 1.8540746773013719}, accurate,
     * as {@code adams.cpp:209} spells it. {@code peirce_q}'s quincuncial fold distance is
     * {@code 2 * K_HALF}; see {@link #PEIRCE_SHIFT}.
     *
     * <p>Note that {@code ellInt5(Math.PI / 2)} is {@code 1.854074716833181}, which differs
     * in the eighth decimal. Both values coexist inside a single upstream function and
     * neither may be substituted for the other.
     */
    public static final double K_HALF = 1.8540746773013719;

    /**
     * {@code shd} from {@code adams.cpp:209} — {@code 1.8540746773013719 * 2}, the distance
     * by which {@code peirce_q} shifts the southern hemisphere when folding it out into the
     * four triangles of the quincunx.
     */
    public static final double PEIRCE_SHIFT = K_HALF * 2;

    /**
     * The truncated literal {@code guyou} returns at the poles ({@code adams.cpp:126}).
     *
     * <p>Not a rounding of {@link #K_HALF} and not {@code ellInt5(pi/2)}: it is 4.7e-06
     * below the former, i.e. 30.05 m at {@code +R=6370997}. {@code guyou.gie:2122-2131}
     * asserts {@code (0, +/-1.85407)} at {@code +R=1} with a 1 mm tolerance, so the
     * truncation is load-bearing.
     */
    public static final double GUYOU_POLE = 1.85407;

    /** {@code M_2_PI}: bit-equal to the C macro {@code 0.63661977236758134308}. */
    private static final double TWO_OVER_PI = 2.0 / Math.PI;

    /** {@code C0} from {@code adams.cpp:88}. Enters the sum halved. */
    private static final double C0 = 2.19174570831038;

    /**
     * The even Chebyshev coefficients, {@code adams.cpp:89-93}, in upstream's declaration
     * order. The Clenshaw recurrence is not symmetric in the coefficient order, so
     * reversing this array silently changes the answer.
     */
    private static final double[] C = {
            -8.58691003636495e-07,
            2.02692115653689e-07,
            3.12960480765314e-05,
            5.30394739921063e-05,
            -0.0012804644680613,
            -0.00575574836830288,
            0.0914203033408211,
    };

    private EllipticIntegral() {
    }

    /**
     * {@code F(phi | m = 1/2)} as PROJ computes it. Port of {@code ell_int_5}.
     *
     * <p>Odd in {@code phi} by construction: the Chebyshev argument depends on
     * {@code phi^2} and the whole series is scaled by {@code phi}, so
     * {@code ellInt5(-phi) == -ellInt5(phi)} exactly. Callers in {@code adams.cpp} rely on
     * that and pass signed amplitudes rather than taking absolute values.
     *
     * @param phi the amplitude, in radians; the callers in this family supply values in
     *            {@code [-pi/2, pi/2]}
     * @return the integral, in the same "unit sphere" length units the adams family works in
     */
    public static double ellInt5(double phi) {
        double y = phi * TWO_OVER_PI;
        y = 2. * y * y - 1.;
        final double y2 = 2. * y;
        double d1 = 0.0;
        double d2 = 0.0;
        for (int i = 0; i < C.length; i++) {
            final double temp = d1;
            d1 = y2 * d1 - d2 + C[i];
            d2 = temp;
        }
        return phi * (y * d1 - d2 + 0.5 * C0);
    }
}

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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link AuxLat}: which conversions exist, the n-versus-n-squared split, the
 * table's structural invariants, and the closed-form derivation of the third flattening.
 */
public class AuxLatTest {

    private static final double N_GRS80 = AuxLat.thirdFlattening(NumericAssert.GRS80_ES);

    /** The eight conversions upstream's Maxima run emits, and no others. */
    private static final int[][] SUPPORTED = {
        {AuxLat.RECTIFYING, AuxLat.GEOGRAPHIC},
        {AuxLat.CONFORMAL, AuxLat.GEOGRAPHIC},
        {AuxLat.AUTHALIC, AuxLat.GEOGRAPHIC},
        {AuxLat.GEOGRAPHIC, AuxLat.RECTIFYING},
        {AuxLat.CONFORMAL, AuxLat.RECTIFYING},
        {AuxLat.GEOGRAPHIC, AuxLat.CONFORMAL},
        {AuxLat.RECTIFYING, AuxLat.CONFORMAL},
        {AuxLat.GEOGRAPHIC, AuxLat.AUTHALIC},
    };

    @Test
    public void exactlyEightConversionsArePopulated() {
        int count = 0;
        for (int in = 0; in < AuxLat.NUMBER; in++) {
            for (int out = 0; out < AuxLat.NUMBER; out++) {
                if (AuxLat.isSupported(in, out)) {
                    count++;
                }
            }
        }
        assertEquals("only 8 of the 36 conversions are tabulated at 9.8.1", 8, count);

        for (int[] pair : SUPPORTED) {
            assertTrue("expected support for " + pair[0] + "->" + pair[1],
                    AuxLat.isSupported(pair[0], pair[1]));
            // And it must actually produce finite coefficients.
            double[] f = AuxLat.coeffs(N_GRS80, pair[0], pair[1]);
            assertEquals(AuxLat.ORDER, f.length);
            for (int i = 0; i < f.length; i++) {
                assertTrue("coefficient " + i + " must be finite", !Double.isNaN(f[i])
                        && !Double.isInfinite(f[i]));
            }
        }
    }

    @Test
    public void unsupportedConversionPairThrows() {
        // Parametric and geocentric latitudes have no tabulated matrices at all.
        assertUnsupported(AuxLat.GEOGRAPHIC, AuxLat.PARAMETRIC);
        assertUnsupported(AuxLat.PARAMETRIC, AuxLat.GEOGRAPHIC);
        assertUnsupported(AuxLat.GEOCENTRIC, AuxLat.CONFORMAL);
        // Nor do the identities, nor authalic <-> conformal, nor authalic <-> rectifying.
        assertUnsupported(AuxLat.GEOGRAPHIC, AuxLat.GEOGRAPHIC);
        assertUnsupported(AuxLat.CONFORMAL, AuxLat.AUTHALIC);
        assertUnsupported(AuxLat.AUTHALIC, AuxLat.CONFORMAL);
        assertUnsupported(AuxLat.AUTHALIC, AuxLat.RECTIFYING);
        assertUnsupported(AuxLat.RECTIFYING, AuxLat.AUTHALIC);

        assertFalse(AuxLat.isSupported(AuxLat.GEOGRAPHIC, AuxLat.PARAMETRIC));
        assertFalse(AuxLat.isSupported(-1, 0));
        assertFalse(AuxLat.isSupported(0, AuxLat.NUMBER));
    }

    @Test
    public void outOfRangeLatitudeIndexThrows() {
        assertUnsupported(-1, AuxLat.GEOGRAPHIC);
        assertUnsupported(AuxLat.GEOGRAPHIC, AuxLat.NUMBER);
        assertUnsupported(AuxLat.NUMBER, AuxLat.NUMBER);
        assertUnsupported(Integer.MIN_VALUE, 0);
    }

    private static void assertUnsupported(int auxin, int auxout) {
        try {
            AuxLat.coeffs(N_GRS80, auxin, auxout, new double[AuxLat.ORDER]);
            fail("expected IllegalArgumentException for " + auxin + " -> " + auxout);
        } catch (IllegalArgumentException expected) {
            assertTrue("message should name the problem: " + expected.getMessage(),
                    expected.getMessage() != null && !expected.getMessage().isEmpty());
        }
    }

    /**
     * The phi/mu pair expands in {@code n^2} and everything else in {@code n}; the split
     * is at {@code latitudes.cpp:342}. Detect it structurally: for a polynomial in
     * {@code n^2}, replacing {@code n} by {@code -n} must map {@code F[l]} to
     * {@code (-1)^(l+1) F[l]} exactly, because the only remaining odd factor is the
     * leading {@code d = n^(l+1)}. For the other pairs that symmetry fails.
     */
    @Test
    public void phiMuUsesPolynomialInNSquared() {
        final double n = 0.05;
        assertOddEvenSymmetry(AuxLat.GEOGRAPHIC, AuxLat.RECTIFYING, n, true);
        assertOddEvenSymmetry(AuxLat.RECTIFYING, AuxLat.GEOGRAPHIC, n, true);
        assertOddEvenSymmetry(AuxLat.GEOGRAPHIC, AuxLat.CONFORMAL, n, false);
        assertOddEvenSymmetry(AuxLat.CONFORMAL, AuxLat.GEOGRAPHIC, n, false);
        assertOddEvenSymmetry(AuxLat.GEOGRAPHIC, AuxLat.AUTHALIC, n, false);
        assertOddEvenSymmetry(AuxLat.AUTHALIC, AuxLat.GEOGRAPHIC, n, false);
        assertOddEvenSymmetry(AuxLat.RECTIFYING, AuxLat.CONFORMAL, n, false);
        assertOddEvenSymmetry(AuxLat.CONFORMAL, AuxLat.RECTIFYING, n, false);
    }

    private static void assertOddEvenSymmetry(int in, int out, double n, boolean expected) {
        double[] p = AuxLat.coeffs(n, in, out);
        double[] m = AuxLat.coeffs(-n, in, out);
        boolean symmetric = true;
        for (int l = 0; l < AuxLat.ORDER; l++) {
            double sign = ((l + 1) % 2 == 0) ? 1.0 : -1.0;
            if (m[l] != sign * p[l]) {
                symmetric = false;
                break;
            }
        }
        assertEquals("n^2 expansion for " + in + "->" + out, expected, symmetric);
    }

    /**
     * The first coefficient of each series has a known leading term in {@code n}; these
     * are the entries any transcription typo would disturb first.
     */
    @Test
    public void leadingCoefficientsMatchUpstreamRationals() {
        final double n = 0.05;
        assertLeading(AuxLat.GEOGRAPHIC, AuxLat.RECTIFYING, n, -3.0 / 2.0);
        assertLeading(AuxLat.RECTIFYING, AuxLat.GEOGRAPHIC, n, 3.0 / 2.0);
        assertLeading(AuxLat.GEOGRAPHIC, AuxLat.CONFORMAL, n, -2.0);
        assertLeading(AuxLat.CONFORMAL, AuxLat.GEOGRAPHIC, n, 2.0);
        assertLeading(AuxLat.GEOGRAPHIC, AuxLat.AUTHALIC, n, -4.0 / 3.0);
        assertLeading(AuxLat.AUTHALIC, AuxLat.GEOGRAPHIC, n, 4.0 / 3.0);
        assertLeading(AuxLat.RECTIFYING, AuxLat.CONFORMAL, n, -1.0 / 2.0);
        assertLeading(AuxLat.CONFORMAL, AuxLat.RECTIFYING, n, 1.0 / 2.0);
    }

    private static void assertLeading(int in, int out, double n, double expected) {
        // F[0] = n * (c0 + O(n)), so F[0]/n -> c0 as n -> 0.
        double small = 1.0e-8;
        double f0 = AuxLat.coeffs(small, in, out)[0];
        assertEquals(in + "->" + out, expected, f0 / small, 1.0e-7);
    }

    /**
     * Each conversion and its reverse must compose to the identity to within series
     * truncation, which is the strongest end-to-end check on the table that does not need
     * an external reference.
     */
    @Test
    public void forwardAndReverseSeriesCompose() {
        final double n = N_GRS80;
        assertComposesToIdentity(n, AuxLat.GEOGRAPHIC, AuxLat.RECTIFYING);
        assertComposesToIdentity(n, AuxLat.GEOGRAPHIC, AuxLat.CONFORMAL);
        assertComposesToIdentity(n, AuxLat.GEOGRAPHIC, AuxLat.AUTHALIC);
        assertComposesToIdentity(n, AuxLat.RECTIFYING, AuxLat.CONFORMAL);
    }

    private static void assertComposesToIdentity(double n, int a, int b) {
        Clenshaw6 ab = Clenshaw6.forConversion(n, a, b);
        Clenshaw6 ba = Clenshaw6.forConversion(n, b, a);
        double worst = 0.0;
        for (int i = -900; i <= 900; i++) {
            double zeta = Math.toRadians(i / 10.0);
            double back = ba.convert(ab.convert(zeta));
            worst = Math.max(worst, Math.abs(back - zeta) * NumericAssert.GRS80_A);
        }
        assertTrue(a + "<->" + b + " round trip " + worst + " m must be under 10 nm",
                worst < 10.0 * NumericAssert.NM);
    }

    /**
     * The closed form {@code n = es / (1 + sqrt(1 - es))^2} must agree with upstream's
     * {@code pow(tan(asin(e)/2), 2)} to within 2 ULP, which is the whole justification
     * for trading three platform-dependent libm calls for one exactly-rounded
     * {@code sqrt}.
     */
    @Test
    public void thirdFlatteningAgreesWithUpstreamToTwoUlp() {
        double[] esValues = {
            0.0,                                       // sphere
            0.00669437999014133,                       // WGS84
            NumericAssert.GRS80_ES,                    // GRS80
            0.0067394967422764350,                     // Bessel 1841
            0.006768657997291094,                      // Clarke 1866
            0.006722670022333331,                      // International 1924
            0.0818191908426215 * 0.0818191908426215,   // e given directly
            0.09,                                      // synthetic
            0.19,                                      // f = 0.1
            0.36,                                      // f = 0.2
        };
        for (double es : esValues) {
            double n = AuxLat.thirdFlattening(es);
            double reference = StrictMath.pow(StrictMath.tan(StrictMath.asin(Math.sqrt(es)) / 2.0), 2.0);
            long ulps = NumericAssert.ulpDistance(n, reference);
            assertTrue("es=" + es + ": n=" + n + " vs pow(tan(asin(e)/2),2)=" + reference
                    + " differ by " + ulps + " ulps", ulps <= 2);
        }
    }

    /**
     * {@code n = es / (1 + sqrt(1 - es))^2} must also be algebraically consistent with
     * {@code n = f / (2 - f)} and with {@code (a - b) / (a + b)}.
     */
    @Test
    public void thirdFlatteningIsConsistentWithFlattening() {
        for (double f = 0.0; f < 0.3; f += 0.0005) {
            double es = 2.0 * f - f * f;
            double n = AuxLat.thirdFlattening(es);
            assertEquals("f=" + f, f / (2.0 - f), n, 4.0e-16);
            double a = 1.0;
            double b = 1.0 - f;
            assertEquals("f=" + f, (a - b) / (a + b), n, 4.0e-16);
        }
    }

    /**
     * {@code pj_rectifying_radius} must reproduce the GRS80 quarter meridian, a widely
     * published number, and be exactly 1 for a sphere.
     */
    @Test
    public void rectifyingRadiusMatchesQuarterMeridian() {
        assertEquals(1.0, AuxLat.rectifyingRadius(0.0), 0.0);
        double rr = AuxLat.rectifyingRadius(N_GRS80);
        double quarterMeridian = rr * (Math.PI / 2.0) * NumericAssert.GRS80_A;
        // GRS80 quarter meridian = 10 001 965.729 m.
        assertEquals(10001965.729, quarterMeridian, 1.0e-3);
        // And it must equal the independent quadrature to a nanometre.
        double reference = NumericAssert.meridianArcReference(Math.PI / 2.0,
                NumericAssert.GRS80_ES) * NumericAssert.GRS80_A;
        assertEquals(reference, quarterMeridian, 5.0 * NumericAssert.NM);
    }

    @Test
    public void polyvalIsHornerAndHandlesNegativeDegree() {
        double[] p = {1.0, 2.0, 3.0, 4.0};
        assertEquals(1.0 + 2.0 * 2.0 + 3.0 * 4.0 + 4.0 * 8.0, AuxLat.polyval(2.0, p), 0.0);
        assertEquals(1.0, AuxLat.polyval(2.0, p, 0, 0), 0.0);
        assertEquals(0.0, AuxLat.polyval(2.0, p, 0, -1), 0.0);
        // Offset form: start at index 1, degree 1 -> 2 + 3x
        assertEquals(2.0 + 3.0 * 5.0, AuxLat.polyval(5.0, p, 1, 1), 0.0);
    }

    /** A sphere must produce all-zero coefficients, so every conversion is the identity. */
    @Test
    public void sphereGivesZeroCoefficients() {
        for (int[] pair : SUPPORTED) {
            double[] f = AuxLat.coeffs(0.0, pair[0], pair[1]);
            for (int i = 0; i < f.length; i++) {
                assertEquals(pair[0] + "->" + pair[1] + " [" + i + "]", 0.0, f[i], 0.0);
            }
        }
    }

    /** The coefficient table must be stateless: repeated calls give identical bits. */
    @Test
    public void coefficientsAreDeterministic() {
        for (int[] pair : SUPPORTED) {
            double[] a = AuxLat.coeffs(N_GRS80, pair[0], pair[1]);
            for (int rep = 0; rep < 3; rep++) {
                double[] b = AuxLat.coeffs(N_GRS80, pair[0], pair[1]);
                for (int i = 0; i < a.length; i++) {
                    NumericAssert.assertSameBits(pair[0] + "->" + pair[1] + "[" + i + "]",
                            a[i], b[i]);
                }
            }
        }
    }
}

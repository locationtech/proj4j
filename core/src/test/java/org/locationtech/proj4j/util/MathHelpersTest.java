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
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link MathHelpers}, concentrating on the two regimes the naive formulas get
 * wrong: tiny arguments (where the {@code log1p} branches matter) and huge ones (where
 * squaring would overflow).
 */
public class MathHelpersTest {

    private static final double LN2 = 0.6931471805599453094172321214581766;

    @Test
    public void asinhMatchesTheDefiningFormulaInTheMidRange() {
        for (int i = -400; i <= 400; i++) {
            double x = i / 4.0;
            // The defining formula must be evaluated on |x|: log(x + sqrt(x*x + 1))
            // cancels catastrophically for x < 0, which is exactly why the port restores
            // the sign afterwards instead.
            double y = Math.abs(x);
            double reference = StrictMath.log(y + Math.sqrt(y * y + 1.0));
            if (x < 0.0) {
                reference = -reference;
            }
            NumericAssert.assertWithinUlps("asinh(" + x + ")", reference,
                    MathHelpers.asinh(x), 4);
        }
    }

    /**
     * The {@code log1p} branch is what keeps {@code merc} correct at the equator. The
     * naive {@code log(x + sqrt(x*x + 1))} returns exactly zero for tiny {@code x},
     * destroying every significant digit; this must return {@code x} to full relative
     * accuracy.
     */
    @Test
    public void asinhKeepsRelativeAccuracyForTinyArguments() {
        assertEquals("naive form is expected to be degenerate here",
                0.0, StrictMath.log(1.0e-20 + Math.sqrt(1.0e-40 + 1.0)), 0.0);
        // asinh(x) = x - x^3/6 + ..., so from x = 1e-7 down the ratio is 1 to 1e-14.
        for (int p = 7; p <= 300; p++) {
            double x = StrictMath.pow(10.0, -p);
            double v = MathHelpers.asinh(x);
            assertTrue("asinh(1e-" + p + ") = " + v + " lost relative accuracy",
                    Math.abs(v / x - 1.0) < 1.0e-12);
        }
        assertEquals(0.0, MathHelpers.asinh(0.0), 0.0);
    }

    @Test
    public void asinhSurvivesTheOverflowThreshold() {
        // y*y would overflow above ~1.3e154; the branch switches at 1e150.
        assertEquals(StrictMath.log(1.0e300) + LN2, MathHelpers.asinh(1.0e300), 1.0e-12);
        assertEquals(-(StrictMath.log(1.0e300) + LN2), MathHelpers.asinh(-1.0e300), 1.0e-12);
        assertTrue(!Double.isNaN(MathHelpers.asinh(Double.MAX_VALUE))
                && !Double.isInfinite(MathHelpers.asinh(Double.MAX_VALUE)));
        // Continuity across the 1e150 branch boundary.
        double below = MathHelpers.asinh(1.0e150);
        double above = MathHelpers.asinh(Math.nextUp(1.0e150));
        assertEquals(below, above, 1.0e-12);
        // Continuity across the 1.0 branch boundary.
        assertEquals(MathHelpers.asinh(1.0), MathHelpers.asinh(Math.nextUp(1.0)), 1.0e-15);
    }

    @Test
    public void asinhIsOddAndMonotone() {
        double previous = Double.NEGATIVE_INFINITY;
        for (int i = -1000; i <= 1000; i++) {
            double x = i / 8.0;
            double v = MathHelpers.asinh(x);
            assertTrue("asinh must be increasing at " + x, v > previous || x == 0.0);
            previous = v;
            if (x != 0.0) {
                assertEquals("asinh must be odd at " + x, -v, MathHelpers.asinh(-x), 0.0);
            }
        }
        assertTrue(Double.isNaN(MathHelpers.asinh(Double.NaN)));
        assertEquals(Double.POSITIVE_INFINITY,
                MathHelpers.asinh(Double.POSITIVE_INFINITY), 0.0);
    }

    /**
     * Documents the one place this port deviates from C: the sign is restored with
     * {@code x < 0.0}, which is false for {@code -0.0}, so a negative zero comes back
     * positive. Pinned so the behaviour cannot change silently.
     */
    @Test
    public void negativeZeroComesBackPositive() {
        NumericAssert.assertSameBits("asinh(-0.0)", +0.0, MathHelpers.asinh(-0.0));
        NumericAssert.assertSameBits("atanh(-0.0)", +0.0, MathHelpers.atanh(-0.0));
        NumericAssert.assertSameBits("asinh(+0.0)", +0.0, MathHelpers.asinh(+0.0));
        NumericAssert.assertSameBits("atanh(+0.0)", +0.0, MathHelpers.atanh(+0.0));
    }

    @Test
    public void atanhMatchesTheDefiningFormulaInTheMidRange() {
        for (int i = -999; i <= 999; i++) {
            double x = i / 1000.0;
            double reference = 0.5 * StrictMath.log((1.0 + x) / (1.0 - x));
            assertEquals("atanh(" + x + ")", reference, MathHelpers.atanh(x),
                    4.0 * Math.ulp(Math.max(1.0, Math.abs(reference))));
        }
    }

    /**
     * The cancellation the {@code log1p} form exists to avoid: the quotient-of-logs form
     * returns exactly zero for {@code x = 1e-18} because {@code 1 + x} and {@code 1 - x}
     * both round to 1.
     */
    @Test
    public void atanhKeepsRelativeAccuracyForTinyArguments() {
        assertEquals("naive form is expected to be degenerate here",
                0.0, 0.5 * StrictMath.log((1.0 + 1.0e-18) / (1.0 - 1.0e-18)), 0.0);
        // atanh(x) = x + x^3/3 + ..., so from x = 1e-7 down the ratio is 1 to 1e-14.
        for (int p = 7; p <= 300; p++) {
            double x = StrictMath.pow(10.0, -p);
            double v = MathHelpers.atanh(x);
            assertTrue("atanh(1e-" + p + ") = " + v + " lost relative accuracy",
                    Math.abs(v / x - 1.0) < 1.0e-12);
        }
    }

    @Test
    public void atanhHandlesTheEndpointsAsInC() {
        assertEquals(Double.POSITIVE_INFINITY, MathHelpers.atanh(1.0), 0.0);
        assertEquals(Double.NEGATIVE_INFINITY, MathHelpers.atanh(-1.0), 0.0);
        assertTrue(Double.isNaN(MathHelpers.atanh(2.0)));
        assertTrue(Double.isNaN(MathHelpers.atanh(-2.0)));
        assertTrue(Double.isNaN(MathHelpers.atanh(Double.NaN)));
    }

    /**
     * Every {@code atanh} caller in the numerical core passes {@code e sin(phi)}, whose
     * magnitude stays under 0.09 for any Earth ellipsoid. Pin the accuracy over exactly
     * that band.
     */
    @Test
    public void atanhIsUniformlyAccurateOverTheEllipsoidBand() {
        double e = NumericAssert.GRS80_E;
        double worst = 0.0;
        for (int i = -9000; i <= 9000; i++) {
            double sinphi = StrictMath.sin(Math.toRadians(i / 100.0));
            double x = e * sinphi;
            assertTrue("|e sin phi| must stay under 0.09", Math.abs(x) < 0.09);
            if (x == 0.0) {
                assertEquals(0.0, MathHelpers.atanh(x), 0.0);
                continue;
            }
            // Convergent Taylor reference: atanh(x) = x + x^3/3 + x^5/5 + ...
            // |x| < 0.09, so 40 terms is far past the last representable one.
            double series = 0.0;
            double term = x;
            for (int k = 0; k < 40; k++) {
                series += term / (2 * k + 1);
                term *= x * x;
            }
            worst = Math.max(worst, Math.abs(MathHelpers.atanh(x) - series) / Math.abs(series));
        }
        // Machine precision; the Taylor reference itself accumulates a few tenths of an
        // ulp, so this pins "no worse than a couple of ulps".
        assertTrue("atanh relative error over the ellipsoid band was " + worst,
                worst < 1.0e-15);
    }

    @Test
    public void norm2IsExactForPythagoreanTriples() {
        assertEquals(5.0, MathHelpers.norm2(3.0, 4.0), 0.0);
        assertEquals(5.0, MathHelpers.norm2(-3.0, 4.0), 0.0);
        assertEquals(13.0, MathHelpers.norm2(5.0, 12.0), 0.0);
        assertEquals(1.0, MathHelpers.norm2(1.0, 0.0), 0.0);
        assertEquals(0.0, MathHelpers.norm2(0.0, 0.0), 0.0);
        assertEquals(Math.sqrt(2.0), MathHelpers.norm2(1.0, 1.0), 0.0);
    }

    /**
     * {@code norm2} must agree with {@code hypot} wherever the operands are bounded, which
     * is everywhere the numerical core uses it. The point of the class is that it does so
     * without {@code hypot}'s scaling machinery.
     */
    @Test
    public void norm2AgreesWithHypotForBoundedOperands() {
        double worst = 0.0;
        for (int i = -60; i <= 60; i++) {
            for (int j = -60; j <= 60; j++) {
                double a = i / 7.0;
                double b = j / 11.0;
                double h = StrictMath.hypot(a, b);
                if (h == 0.0) {
                    assertEquals(0.0, MathHelpers.norm2(a, b), 0.0);
                    continue;
                }
                worst = Math.max(worst, Math.abs(MathHelpers.norm2(a, b) - h) / h);
            }
        }
        assertTrue("norm2 vs hypot relative error " + worst, worst < 3.0e-16);
    }
}

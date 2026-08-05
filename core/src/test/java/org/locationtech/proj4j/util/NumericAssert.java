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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Shared numeric helpers for the {@code util} numerical-core tests: an ULP-based
 * comparison equivalent to googletest's {@code EXPECT_DOUBLE_EQ} (4 ULP), the GRS80
 * constants every sweep uses, and a compensated Gauss-Legendre meridian-arc reference
 * that is independent of the series under test.
 */
final class NumericAssert {

    /** GRS80 semi-major axis, metres. */
    static final double GRS80_A = 6378137.0;

    /** GRS80 squared first eccentricity, as PROJ derives it from {@code a} and {@code rf}. */
    static final double GRS80_ES = 0.006694380022900787;

    /** GRS80 first eccentricity. */
    static final double GRS80_E = Math.sqrt(GRS80_ES);

    /** One nanometre, in metres — the unit the numerics reference quotes. */
    static final double NM = 1.0e-9;

    /** googletest's {@code EXPECT_DOUBLE_EQ} tolerance. */
    private static final int GTEST_ULPS = 4;

    private NumericAssert() {
    }

    /**
     * The googletest {@code EXPECT_DOUBLE_EQ} predicate: equal, or within 4 ULP.
     */
    static void assertDoubleEq(double expected, double actual) {
        assertDoubleEq("", expected, actual);
    }

    static void assertDoubleEq(String message, double expected, double actual) {
        assertWithinUlps(message, expected, actual, GTEST_ULPS);
    }

    /**
     * Asserts that {@code actual} is within {@code ulps} representable doubles of
     * {@code expected}. Handles equal values (including signed zeros compared loosely)
     * and requires bitwise-equal NaN/infinity.
     */
    static void assertWithinUlps(String message, double expected, double actual, int ulps) {
        if (Double.isNaN(expected) || Double.isNaN(actual)
                || Double.isInfinite(expected) || Double.isInfinite(actual)) {
            assertEquals(message, expected, actual, 0.0);
            return;
        }
        final long d = ulpDistance(expected, actual);
        assertTrue(message + " expected " + expected + " actual " + actual
                + " differ by " + d + " ulps (limit " + ulps + ")", d <= ulps);
    }

    /** The number of representable doubles between {@code a} and {@code b}. */
    static long ulpDistance(double a, double b) {
        if (a == b) {
            return 0L;
        }
        return Math.abs(ordinal(a) - ordinal(b));
    }

    private static long ordinal(double v) {
        final long bits = Double.doubleToLongBits(v);
        // Map the sign-magnitude representation onto a monotone two's-complement ordering.
        return bits < 0 ? Long.MIN_VALUE - bits : bits;
    }

    /** Asserts strict bitwise equality, so that {@code +0.0 != -0.0}. */
    static void assertSameBits(String message, double expected, double actual) {
        assertEquals(message, Double.doubleToRawLongBits(expected),
                Double.doubleToRawLongBits(actual));
    }

    // ---- 10-point Gauss-Legendre nodes and weights on [-1, 1] --------------------

    private static final double[] GLX = {
        -0.9739065285171717, -0.8650633666889845, -0.6794095682990244,
        -0.4333953941292472, -0.1488743389816312, 0.1488743389816312,
        0.4333953941292472, 0.6794095682990244, 0.8650633666889845,
        0.9739065285171717,
    };

    private static final double[] GLW = {
        0.0666713443086881, 0.1494513491505806, 0.2190863625159820,
        0.2692667193099963, 0.2955242247147529, 0.2955242247147529,
        0.2692667193099963, 0.2190863625159820, 0.1494513491505806,
        0.0666713443086881,
    };

    /**
     * The meridian arc from the equator to {@code phi}, divided by the semi-major axis:
     * <pre>
     *   integral(0, phi) (1 - es) / (1 - es sin^2 t)^(3/2) dt
     * </pre>
     * evaluated by 64-panel 10-point Gauss-Legendre with Neumaier compensated summation.
     *
     * <p>The integrand is analytic and the panels are tiny, so the quadrature truncation
     * error is far below rounding; compensating the sum then leaves about 1 ULP of the
     * result, i.e. roughly 1 nm on the ground for GRS80. That is an <em>independent</em>
     * reference — it shares no series, no coefficient and no algorithm with
     * {@link MeridianArc} or with {@code ProjectionMath.mlfn} — which is what makes the
     * old-versus-new comparison meaningful.
     */
    static double meridianArcReference(double phi, double es) {
        final int panels = 64;
        final double h = phi / panels;
        final double hw = 0.5 * h;
        double sum = 0.0;
        double comp = 0.0;
        for (int p = 0; p < panels; p++) {
            final double c = p * h + hw;
            for (int i = 0; i < GLX.length; i++) {
                final double t = c + hw * GLX[i];
                final double st = Math.sin(t);
                final double d = 1.0 - es * st * st;
                final double term = hw * GLW[i] * (1.0 - es) / (d * Math.sqrt(d));
                final double s = sum + term;
                if (Math.abs(sum) >= Math.abs(term)) {
                    comp += (sum - s) + term;
                } else {
                    comp += (term - s) + sum;
                }
                sum = s;
            }
        }
        return sum + comp;
    }
}

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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link Clenshaw6}: that the recurrence really evaluates the Fourier sum, that
 * the sine/cosine-preserving form agrees with the angle form, that {@code X} is formed the
 * accurate way, and that the class holds no array.
 */
public class Clenshaw6Test {

    /**
     * Direct evaluation of {@code sum(F[k] sin((2k+2) zeta))}, the definition Clenshaw is
     * a rearrangement of. Slow and obviously correct.
     */
    private static double direct(double[] f, double zeta) {
        double sum = 0.0;
        for (int k = 0; k < f.length; k++) {
            sum += f[k] * StrictMath.sin((2 * k + 2) * zeta);
        }
        return sum;
    }

    @Test
    public void recurrenceEqualsTheDirectFourierSum() {
        double[] f = {0.5, -0.25, 0.125, -0.0625, 0.03125, -0.015625};
        Clenshaw6 c = new Clenshaw6(f);
        double worst = 0.0;
        for (int i = -3600; i <= 3600; i++) {
            double zeta = Math.toRadians(i / 10.0);
            double got = c.delta(StrictMath.sin(zeta), StrictMath.cos(zeta));
            worst = Math.max(worst, Math.abs(got - direct(f, zeta)));
        }
        assertTrue("Clenshaw vs direct sum differed by " + worst, worst < 1.0e-15);
    }

    @Test
    public void recurrenceEqualsTheDirectFourierSumForRealCoefficients() {
        double n = AuxLat.thirdFlattening(NumericAssert.GRS80_ES);
        int[][] pairs = {
            {AuxLat.GEOGRAPHIC, AuxLat.RECTIFYING},
            {AuxLat.RECTIFYING, AuxLat.GEOGRAPHIC},
            {AuxLat.GEOGRAPHIC, AuxLat.CONFORMAL},
            {AuxLat.CONFORMAL, AuxLat.GEOGRAPHIC},
            {AuxLat.GEOGRAPHIC, AuxLat.AUTHALIC},
            {AuxLat.AUTHALIC, AuxLat.GEOGRAPHIC},
            {AuxLat.RECTIFYING, AuxLat.CONFORMAL},
            {AuxLat.CONFORMAL, AuxLat.RECTIFYING},
        };
        for (int[] pair : pairs) {
            double[] f = AuxLat.coeffs(n, pair[0], pair[1]);
            Clenshaw6 c = new Clenshaw6(f);
            double worst = 0.0;
            for (int i = -900; i <= 900; i++) {
                double zeta = Math.toRadians(i / 10.0);
                double got = c.delta(StrictMath.sin(zeta), StrictMath.cos(zeta));
                worst = Math.max(worst, Math.abs(got - direct(f, zeta)));
            }
            assertTrue(pair[0] + "->" + pair[1] + " worst " + worst, worst < 1.0e-17);
        }
    }

    /**
     * {@code X = 2 (c - s)(c + s)} must equal {@code 2 cos(2 zeta)}, and must be at least
     * as accurate as {@code 2 (2 c^2 - 1)} near {@code zeta = pi/4} where that form
     * cancels. Probed through {@link Clenshaw6#delta} with a single non-zero coefficient,
     * for which {@code delta = F0 sin(2 zeta)} and the {@code X}-dependence vanishes — so
     * the check is done on {@code X} itself, reconstructed from a two-term series.
     */
    @Test
    public void xIsFormedTheAccurateWay() {
        // With F = {0, 1, 0, 0, 0, 0}: delta = sin(4 zeta) = sin(2 zeta) * X, since
        // sin(4z) = 2 sin(2z) cos(2z) and X = 2 cos(2z).
        Clenshaw6 c = new Clenshaw6(new double[]{0.0, 1.0, 0.0, 0.0, 0.0, 0.0});
        double worstNew = 0.0;
        double worstNaive = 0.0;
        for (int i = -100; i <= 100; i++) {
            // Cluster tightly around pi/4, where 2*c*c - 1 loses its leading digits.
            double zeta = Math.PI / 4.0 + i * 1.0e-9;
            double s = StrictMath.sin(zeta);
            double cc = StrictMath.cos(zeta);
            double reference = StrictMath.sin(4.0 * zeta);
            double got = c.delta(s, cc);
            double naive = 2.0 * s * cc * (2.0 * (2.0 * cc * cc - 1.0));
            worstNew = Math.max(worstNew, Math.abs(got - reference));
            worstNaive = Math.max(worstNaive, Math.abs(naive - reference));
        }
        assertTrue("(c-s)(c+s) form " + worstNew + " must beat 2c^2-1 form " + worstNaive,
                worstNew <= worstNaive);
        assertTrue("(c-s)(c+s) form should be near machine precision, was " + worstNew,
                worstNew < 1.0e-15);
    }

    @Test
    public void convertAddsDeltaToZeta() {
        Clenshaw6 c = Clenshaw6.forConversion(AuxLat.thirdFlattening(NumericAssert.GRS80_ES),
                AuxLat.GEOGRAPHIC, AuxLat.CONFORMAL);
        for (int i = -90; i <= 90; i += 3) {
            double zeta = Math.toRadians(i);
            double s = StrictMath.sin(zeta);
            double cc = StrictMath.cos(zeta);
            assertEquals(zeta + c.delta(s, cc), c.convert(zeta, s, cc), 0.0);
            // The one-argument form must compute the same sine and cosine internally.
            NumericAssert.assertSameBits("lat " + i, c.convert(zeta, s, cc), c.convert(zeta));
        }
    }

    /**
     * {@code convertSinCos} must return the sine and cosine of exactly what
     * {@code convert} returns, and must be a unit vector even where {@code cos} is tiny —
     * that relative accuracy near the poles is the reason 9.8.1's etmerc inverse uses it.
     */
    @Test
    public void convertSinCosAgreesWithConvertAndStaysNormalised() {
        Clenshaw6 c = Clenshaw6.forConversion(AuxLat.thirdFlattening(NumericAssert.GRS80_ES),
                AuxLat.CONFORMAL, AuxLat.GEOGRAPHIC);
        double[] out = new double[2];
        double worst = 0.0;
        double worstNorm = 0.0;
        for (int i = -9000; i <= 9000; i++) {
            double zeta = Math.toRadians(i / 100.0);
            double s = StrictMath.sin(zeta);
            double cc = StrictMath.cos(zeta);
            c.convertSinCos(s, cc, out);
            double eta = c.convert(zeta, s, cc);
            worst = Math.max(worst, Math.abs(out[0] - StrictMath.sin(eta)));
            worst = Math.max(worst, Math.abs(out[1] - StrictMath.cos(eta)));
            worstNorm = Math.max(worstNorm,
                    Math.abs(MathHelpers.norm2(out[0], out[1]) - 1.0));
        }
        assertTrue("convertSinCos vs sin/cos(convert): " + worst, worst < 1.0e-15);
        assertTrue("convertSinCos must stay on the unit circle: " + worstNorm,
                worstNorm < 1.0e-15);
    }

    /**
     * Exactly at the pole the sine/cosine form must give {@code (1, 0)} without any
     * cancellation, since the correction is zero there.
     */
    @Test
    public void convertSinCosIsExactAtThePole() {
        Clenshaw6 c = Clenshaw6.forConversion(AuxLat.thirdFlattening(NumericAssert.GRS80_ES),
                AuxLat.CONFORMAL, AuxLat.GEOGRAPHIC);
        double[] out = new double[2];
        c.convertSinCos(1.0, 0.0, out);
        assertEquals(1.0, out[0], 1.0e-16);
        assertEquals(0.0, out[1], 1.0e-16);
        c.convertSinCos(0.0, 1.0, out);
        assertEquals(0.0, out[0], 0.0);
        assertEquals(1.0, out[1], 0.0);
    }

    /**
     * The class must hold six scalar fields and <b>no array</b>: that is what lets the JIT
     * keep the coefficients in registers and elides a bounds check per row of a bulk
     * transform. Also asserted: the fields are final, so instances are immutable and
     * shareable across threads.
     */
    @Test
    public void holdsNoArrayAndIsImmutable() {
        Field[] fields = Clenshaw6.class.getDeclaredFields();
        int scalars = 0;
        for (Field f : fields) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            assertFalse("Clenshaw6 must not hold an array field: " + f.getName(),
                    f.getType().isArray());
            assertTrue("Clenshaw6 must not hold a reference field: " + f.getName(),
                    f.getType().isPrimitive());
            assertEquals("field " + f.getName() + " must be double", Double.TYPE, f.getType());
            assertTrue("field " + f.getName() + " must be final",
                    Modifier.isFinal(f.getModifiers()));
            scalars++;
        }
        assertEquals("exactly six coefficient fields", 6, scalars);
    }

    /** Repeated evaluation must return bit-identical results. */
    @Test
    public void isDeterministic() {
        Clenshaw6 c = Clenshaw6.forConversion(AuxLat.thirdFlattening(NumericAssert.GRS80_ES),
                AuxLat.GEOGRAPHIC, AuxLat.AUTHALIC);
        for (int i = -900; i <= 900; i += 37) {
            double zeta = Math.toRadians(i / 10.0);
            double s = StrictMath.sin(zeta);
            double cc = StrictMath.cos(zeta);
            double first = c.delta(s, cc);
            for (int rep = 0; rep < 1000; rep++) {
                NumericAssert.assertSameBits("delta at " + zeta, first, c.delta(s, cc));
            }
            // And a second instance built from the same n must agree bit for bit.
            Clenshaw6 c2 = Clenshaw6.forConversion(
                    AuxLat.thirdFlattening(NumericAssert.GRS80_ES),
                    AuxLat.GEOGRAPHIC, AuxLat.AUTHALIC);
            NumericAssert.assertSameBits("independent instance", first, c2.delta(s, cc));
        }
    }

    @Test
    public void coefficientAccessorsRoundTrip() {
        double[] f = {1.0, 2.0, 3.0, 4.0, 5.0, 6.0};
        Clenshaw6 c = new Clenshaw6(f);
        for (int k = 0; k < 6; k++) {
            assertEquals(f[k], c.coefficient(k), 0.0);
        }
        try {
            c.coefficient(6);
            fail("expected IndexOutOfBoundsException");
        } catch (IndexOutOfBoundsException expected) {
            // Expected.
        }
        // The offset constructor must read the requested window.
        double[] wide = {9.0, 9.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0};
        Clenshaw6 offset = new Clenshaw6(wide, 2);
        for (int k = 0; k < 6; k++) {
            assertEquals(f[k], offset.coefficient(k), 0.0);
        }
    }

    /** Zero coefficients must give exactly zero, so a sphere is an exact identity. */
    @Test
    public void zeroCoefficientsGiveExactlyZero() {
        Clenshaw6 c = new Clenshaw6(new double[6]);
        for (int i = -90; i <= 90; i += 5) {
            double zeta = Math.toRadians(i);
            assertEquals(0.0, c.delta(StrictMath.sin(zeta), StrictMath.cos(zeta)), 0.0);
            NumericAssert.assertSameBits("identity at " + i, zeta, c.convert(zeta));
        }
    }
}

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

package org.locationtech.proj4j.util.trig;

import org.junit.Test;
import org.locationtech.proj4j.util.FastStrictTrig;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Proves that {@link FastStrictTrig} is <strong>bit-identical</strong> to {@link StrictMath} for
 * {@code sin}, {@code cos} and {@code tan}.
 *
 * <p>Bit-identity, not a tolerance, is the whole point of the class. proj4j's reason for using
 * fdlibm trigonometry is fidelity to the implementation that generated PROJ's {@code gie} expected
 * values (in the adams family a 1-ulp {@code sin} difference is amplified by ~3e8 and moves a
 * result 27.8 mm outside a 1 mm bar), plus cross-architecture determinism for a downstream Spark
 * consumer. An "almost fdlibm" {@code sin} would silently forfeit both while looking like a win,
 * so every comparison here is on {@link Double#doubleToRawLongBits}: {@code +0.0} and
 * {@code -0.0} are distinguished, and NaN payloads are compared rather than collapsed.
 *
 * <p>Coverage, in order of the branches it is aimed at:
 *
 * <table>
 *   <caption>input classes and the code path each targets</caption>
 *   <tr><th>input class</th><th>path exercised</th></tr>
 *   <tr><td>the gie corpus's own latitude/longitude values, in degrees and radians</td>
 *       <td>the realistic workload</td></tr>
 *   <tr><td>every {@code k*pi/4} and {@code k*pi/2} for {@code |k| <= 2048}, each with three
 *       {@code nextUp} and three {@code nextDown} neighbours</td>
 *       <td>the {@code |x| ~< pi/4} short circuit, the {@code n = +/-1} special case, the
 *       {@code npio2_hw} cancellation check and the 2nd/3rd reduction rounds</td></tr>
 *   <tr><td>1e10, 1e100, {@code Double.MAX_VALUE}, {@code pi*2^k}, the classic
 *       {@code 6381956970095103 * 2^797} worst case, and the {@code 2^19*(pi/2)} boundary</td>
 *       <td>{@code __kernel_rem_pio2}'s multi-word limb arithmetic, including its recomputation
 *       loop</td></tr>
 *   <tr><td>subnormals from {@code Double.MIN_VALUE} upward</td>
 *       <td>the {@code |x| < 2^-27} / {@code 2^-28} inexact short circuits</td></tr>
 *   <tr><td>{@code +/-0.0}, {@code +/-Infinity}, quiet and signalling NaN with non-default
 *       payloads, negative NaN</td>
 *       <td>special-case returns</td></tr>
 *   <tr><td>3,000,000 pseudo-random doubles from four fixed-seed generators, one of which
 *       produces raw bit patterns</td>
 *       <td>everything, reproducibly</td></tr>
 * </table>
 */
public class FastStrictTrigIdentityTest {

    /** Fixed so that a failure is reproducible; do not change without recording why. */
    private static final long SEED = 0x50524f4a344aL; // "PROJ4J"

    // ------------------------------------------------------------------
    // Bitwise comparison helpers
    // ------------------------------------------------------------------

    private static void assertBitIdentical(String what, double x, double expected, double actual) {
        long e = Double.doubleToRawLongBits(expected);
        long a = Double.doubleToRawLongBits(actual);
        if (e != a) {
            throw new AssertionError(String.format(
                    "%s(%s) [bits 0x%016x]: StrictMath -> %s [0x%016x], FastStrictTrig -> %s [0x%016x]",
                    what, Double.toString(x), Double.doubleToRawLongBits(x),
                    Double.toString(expected), e, Double.toString(actual), a));
        }
    }

    /** Asserts all three functions agree bitwise with {@link StrictMath} at {@code x}. */
    private static void check(double x) {
        assertBitIdentical("sin", x, StrictMath.sin(x), FastStrictTrig.sin(x));
        assertBitIdentical("cos", x, StrictMath.cos(x), FastStrictTrig.cos(x));
        assertBitIdentical("tan", x, StrictMath.tan(x), FastStrictTrig.tan(x));
        // the caller-supplied-scratch overloads must be indistinguishable from the thread-local ones
        FastStrictTrig.Scratch s = new FastStrictTrig.Scratch();
        assertBitIdentical("sin(x,scratch)", x, StrictMath.sin(x), FastStrictTrig.sin(x, s));
        assertBitIdentical("cos(x,scratch)", x, StrictMath.cos(x), FastStrictTrig.cos(x, s));
        assertBitIdentical("tan(x,scratch)", x, StrictMath.tan(x), FastStrictTrig.tan(x, s));
    }

    /** As {@link #check(double)} but without the scratch overloads, for the bulk sweeps. */
    private static void checkFast(double x) {
        assertBitIdentical("sin", x, StrictMath.sin(x), FastStrictTrig.sin(x));
        assertBitIdentical("cos", x, StrictMath.cos(x), FastStrictTrig.cos(x));
        assertBitIdentical("tan", x, StrictMath.tan(x), FastStrictTrig.tan(x));
    }

    /** {@code x} and its three nearest neighbours in each direction. */
    private static void checkNeighbourhood(double x) {
        double up = x;
        double down = x;
        check(x);
        for (int i = 0; i < 3; i++) {
            up = Math.nextUp(up);
            down = Math.nextAfter(down, Double.NEGATIVE_INFINITY);
            check(up);
            check(down);
        }
    }

    // ------------------------------------------------------------------
    // 1. Signed zero, by raw bits
    // ------------------------------------------------------------------

    /**
     * {@code sin(+0.0)} must be {@code +0.0} and {@code sin(-0.0)} must be {@code -0.0}. The gie
     * comparator distinguishes the two at the equator and the antimeridian, so an implementation
     * that returned {@code +0.0} for both would be wrong in a way {@code assertEquals(a, b, 0.0)}
     * cannot see.
     */
    @Test
    public void signedZeroIsPreservedByRawBits() {
        assertEquals("sin(+0.0) must be +0.0",
                0x0000000000000000L, Double.doubleToRawLongBits(FastStrictTrig.sin(0.0)));
        assertEquals("sin(-0.0) must be -0.0",
                0x8000000000000000L, Double.doubleToRawLongBits(FastStrictTrig.sin(-0.0)));
        assertEquals("tan(+0.0) must be +0.0",
                0x0000000000000000L, Double.doubleToRawLongBits(FastStrictTrig.tan(0.0)));
        assertEquals("tan(-0.0) must be -0.0",
                0x8000000000000000L, Double.doubleToRawLongBits(FastStrictTrig.tan(-0.0)));
        assertEquals("cos(+0.0) must be +1.0",
                Double.doubleToRawLongBits(1.0), Double.doubleToRawLongBits(FastStrictTrig.cos(0.0)));
        assertEquals("cos(-0.0) must be +1.0",
                Double.doubleToRawLongBits(1.0), Double.doubleToRawLongBits(FastStrictTrig.cos(-0.0)));

        // and, redundantly, that StrictMath agrees -- if it ever did not, the premise is wrong
        check(0.0);
        check(-0.0);
    }

    // ------------------------------------------------------------------
    // 2. Special values
    // ------------------------------------------------------------------

    /**
     * Infinities, and NaNs carrying payloads other than the canonical {@code 0x7ff8...0}. Both
     * implementations return {@code x - x}, so the resulting NaN's bits must match exactly.
     */
    @Test
    public void infinitiesAndNaNPayloads() {
        check(Double.POSITIVE_INFINITY);
        check(Double.NEGATIVE_INFINITY);
        check(Double.NaN);
        check(Double.longBitsToDouble(0x7ff8_0000_dead_beefL)); // quiet NaN, non-default payload
        check(Double.longBitsToDouble(0xfff8_0000_0000_0001L)); // negative quiet NaN
        check(Double.longBitsToDouble(0x7ff0_0000_0000_0001L)); // signalling NaN
        check(Double.longBitsToDouble(0x7ff4_2424_2424_2424L));
        check(Double.longBitsToDouble(0xffff_ffff_ffff_ffffL));
    }

    /** Subnormals and the smallest normals, which take the {@code |x| < 2^-27} short circuits. */
    @Test
    public void subnormalsAndTinyNormals() {
        check(Double.MIN_VALUE);
        check(-Double.MIN_VALUE);
        check(Double.MIN_NORMAL);
        check(-Double.MIN_NORMAL);
        check(Math.nextAfter(Double.MIN_NORMAL, 0.0));
        for (int p = 0; p < 52; p++) {           // every subnormal power of two
            double v = Double.longBitsToDouble(1L << p);
            checkFast(v);
            checkFast(-v);
            checkFast(v * 3.0);
        }
        for (int e = -1022; e <= 0; e++) {       // every normal power of two below 1
            double v = Double.longBitsToDouble(((long) (1023 + e)) << 52);
            checkFast(v);
            checkFast(-v);
            checkFast(Math.nextUp(v));
        }
        // the exact 2^-27 and 2^-28 thresholds and their neighbours
        checkNeighbourhood(0x1.0p-27);
        checkNeighbourhood(-0x1.0p-27);
        checkNeighbourhood(0x1.0p-28);
        checkNeighbourhood(-0x1.0p-28);
        checkNeighbourhood(0x1.0p-29);
    }

    // ------------------------------------------------------------------
    // 3. Argument-reduction branch thresholds
    // ------------------------------------------------------------------

    /**
     * Every {@code k*pi/4} and {@code k*pi/2} up to {@code |k| = 2048}, with three neighbours
     * either side. These straddle the {@code pi/4} short circuit, the {@code 3pi/4} special case,
     * the 32-entry {@code npio2_hw} cancellation table, and the points where fdlibm needs its 2nd
     * and 3rd reduction rounds.
     */
    @Test
    public void quadrantBoundaryNeighbourhoods() {
        for (int k = -2048; k <= 2048; k++) {
            checkNeighbourhood(k * (Math.PI / 4.0));
            checkNeighbourhood(k * (Math.PI / 2.0));
            checkNeighbourhood(k * Math.PI);
            // computed the other way round, which lands on different doubles
            checkNeighbourhood(k * Math.PI / 4.0);
            checkNeighbourhood(k * Math.PI / 2.0);
        }
    }

    /** The exact high-word thresholds fdlibm branches on. */
    @Test
    public void exactBranchThresholds() {
        long[] highWords = {
                0x3fe921fbL, // |x| ~< pi/4 short circuit
                0x3fe921fcL,
                0x3fd33333L, // kernel_cos |x| < 0.3
                0x3fe90000L, // kernel_cos x > 0.78125
                0x3fe59428L, // kernel_tan |x| >= 0.6744
                0x3e400000L, // kernel_sin/cos |x| < 2^-27
                0x3e300000L, // kernel_tan |x| < 2^-28
                0x4002d97cL, // |x| < 3pi/4 special case
                0x3ff921fbL, // near pi/2, 33+33+53 bit pi
                0x41392000L,
                0x413921fbL, // |x| ~<= 2^19*(pi/2), the medium/huge boundary
                0x413921fcL,
                0x7fefffffL, // largest finite high word
        };
        for (long hw : highWords) {
            for (int lo = -3; lo <= 3; lo++) {
                double v = Double.longBitsToDouble((hw << 32) + lo);
                check(v);
                check(-v);
            }
            double v = Double.longBitsToDouble((hw << 32) | 0x5555_5555L);
            check(v);
            check(-v);
        }
    }

    // ------------------------------------------------------------------
    // 4. The multi-word (__kernel_rem_pio2) path
    // ------------------------------------------------------------------

    /**
     * Arguments large enough to force {@code __kernel_rem_pio2}'s 24-bit limb arithmetic. This is
     * the only path in the ported code that still uses arrays, so it is the one where a
     * transcription slip would hide.
     */
    @Test
    public void hugeArgumentsStressMultiWordReduction() {
        check(1e10);
        check(-1e10);
        check(1e100);
        check(-1e100);
        check(1e300);
        check(Double.MAX_VALUE);
        check(-Double.MAX_VALUE);
        check(Math.nextAfter(Double.MAX_VALUE, 0.0));
        check(0x1.0p1023);
        check(0x1.fffffffffffffp1023);

        // the textbook worst case for pi/2 argument reduction: 6381956970095103 * 2^797
        check(6381956970095103.0 * Math.pow(2.0, 797.0));
        check(Double.longBitsToDouble(0x6c6152e8f8f2af13L));

        // pi and 2/pi scaled across the whole exponent range: near-multiples of pi/2 at every
        // magnitude, which is what drives kernel_rem_pio2's recomputation loop
        for (int e = 20; e <= 1023; e++) {
            double scale = Double.longBitsToDouble(((long) (1023 + e)) << 52);
            checkFast(Math.PI * scale);
            checkFast(-Math.PI * scale);
            checkFast((Math.PI / 2.0) * scale);
            checkFast(0x1.45f306dc9c883p-1 * scale); // 2/pi
            checkFast(scale);
            checkFast(scale + 1.0);
            checkFast(Math.nextUp(scale));
            checkFast(Math.nextAfter(scale, 0.0));
        }

        // every power of two above the medium/huge boundary, plus neighbours
        for (int e = 19; e <= 1023; e++) {
            double v = Double.longBitsToDouble(((long) (1023 + e)) << 52);
            checkFast(v * (Math.PI / 2.0));
            checkFast(Math.nextUp(v * (Math.PI / 2.0)));
        }
    }

    // ------------------------------------------------------------------
    // 5. The actual workload: the gie corpus's own angles
    // ------------------------------------------------------------------

    /**
     * The 7,618 distinct angle-shaped literals from PROJ's {@code gie} corpus, in degrees and
     * converted to radians, plus the halved-longitude form that the adams family feeds to
     * {@code sin} -- the exact quantity whose 1-ulp difference decided
     * {@code adams_ws2.gie:2139}.
     */
    @Test
    public void gieCorpusAngles() {
        double[] deg = GieCorpusAngles.degrees();
        assertTrue("expected the embedded gie corpus to be populated, got " + deg.length,
                deg.length > 7000);
        for (int i = 0; i < deg.length; i++) {
            double d = deg[i];
            checkFast(d);                          // as written, treated as radians
            double rad = Math.toRadians(d);
            checkFast(rad);
            checkFast(rad / 2.0);                  // adams: sin(lam/2)
            checkFast(rad * 2.0);
            checkFast(Math.PI / 2.0 - rad);        // co-latitude
            checkFast(Math.nextUp(rad));
            checkFast(Math.nextAfter(rad, Double.NEGATIVE_INFINITY));
        }
    }

    // ------------------------------------------------------------------
    // 6. Pseudo-random sweeps, fixed seed
    // ------------------------------------------------------------------

    /** 1,000,000 uniform draws from {@code [-4pi, 4pi]} -- the range a transform actually sees. */
    @Test
    public void randomNearRange() {
        Random rnd = new Random(SEED);
        for (int i = 0; i < 1_000_000; i++) {
            checkFast((rnd.nextDouble() - 0.5) * 8.0 * Math.PI);
        }
    }

    /** 500,000 uniform draws from {@code [-1e6, 1e6]}, straddling the medium/huge boundary. */
    @Test
    public void randomMediumRange() {
        Random rnd = new Random(SEED + 1);
        for (int i = 0; i < 500_000; i++) {
            checkFast((rnd.nextDouble() - 0.5) * 2.0e6);
        }
    }

    /** 500,000 log-uniform magnitudes across the full binary exponent range. */
    @Test
    public void randomLogUniformMagnitude() {
        Random rnd = new Random(SEED + 2);
        for (int i = 0; i < 500_000; i++) {
            int exp = rnd.nextInt(2098) - 1074;             // subnormal floor to 2^1023
            double mant = 1.0 + rnd.nextDouble();
            double v = mant * Math.pow(2.0, exp);
            checkFast(rnd.nextBoolean() ? v : -v);
        }
    }

    /**
     * 1,000,000 raw 64-bit patterns reinterpreted as doubles. This is the harshest sweep: about
     * half the draws exceed {@code 2^19*(pi/2)} and take the multi-word path, and the sweep
     * naturally includes NaNs, infinities and subnormals.
     */
    @Test
    public void randomRawBitPatterns() {
        Random rnd = new Random(SEED + 3);
        for (int i = 0; i < 1_000_000; i++) {
            checkFast(Double.longBitsToDouble(rnd.nextLong()));
        }
    }

    /**
     * The scratch-taking overloads over the same raw-bit sweep, with one {@code Scratch} reused
     * throughout -- proving reuse leaves no stale state in {@code iq}/{@code f}/{@code fq}/
     * {@code q} that could perturb a later call.
     */
    @Test
    public void reusedScratchGivesIdenticalResults() {
        FastStrictTrig.Scratch s = new FastStrictTrig.Scratch();
        Random rnd = new Random(SEED + 3);
        for (int i = 0; i < 1_000_000; i++) {
            double x = Double.longBitsToDouble(rnd.nextLong());
            assertBitIdentical("sin(x,reused)", x, StrictMath.sin(x), FastStrictTrig.sin(x, s));
            assertBitIdentical("cos(x,reused)", x, StrictMath.cos(x), FastStrictTrig.cos(x, s));
            assertBitIdentical("tan(x,reused)", x, StrictMath.tan(x), FastStrictTrig.tan(x, s));
        }
    }
}

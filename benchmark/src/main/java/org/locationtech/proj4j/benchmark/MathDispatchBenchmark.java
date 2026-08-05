/*******************************************************************************
 * Copyright 2026 Proj4J contributors
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
 */
package org.locationtech.proj4j.benchmark;

import java.util.concurrent.TimeUnit;

import org.locationtech.proj4j.util.MathHelpers;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Quantifies the cost of {@code reference/numerics.md}'s {@code Math} vs {@code StrictMath} policy.
 * This benchmark exists <b>solely</b> to put a number on row 13 of that file's ranked-changes table,
 * which is the one change whose speed effect is a deliberate loss:
 *
 * <blockquote>13 | {@code StrictMath} policy + {@code strictfp} | none | <b>-1.5-3x on the 7
 * intrinsics</b> | yes, &lt;= 1 ulp - but this is what delivers determinism</blockquote>
 *
 * <p><b>THE CAVEAT THAT MAKES OR BREAKS THIS BENCHMARK: it must be run on both x86-64 and AArch64.</b>
 * The entire {@code StrictMath} question is about <i>cross-architecture divergence</i>. HotSpot ships
 * {@code @IntrinsicCandidate} implementations of {@code sin cos tan log log10 exp pow} on both
 * architectures, and they differ from each other and from fdlibm. A single-architecture run of this
 * file tells you the local tax and says <i>nothing</i> about the thing the policy is for. A result
 * from one architecture presented as "the cost of StrictMath" is a wrong answer, not an incomplete
 * one.
 *
 * <p>Three groups, matching the policy table exactly:
 * <ul>
 *   <li><b>Intrinsified</b> - {@code sin cos tan exp log log10 pow}. These are the ones that
 *       actually vary and the ones the policy switches to {@code StrictMath}. The
 *       {@code strict*}/{@code math*} pair is the tax.</li>
 *   <li><b>Free today</b> - {@code asin acos atan atan2 sinh cosh log1p}. {@code Math} already
 *       delegates to {@code StrictMath} for these, so the pair should measure as equal. If it does
 *       not, HotSpot has gained an intrinsic and the policy's "free today" claim has expired -
 *       which is precisely why these arms are here and not assumed.</li>
 *   <li><b>hypot</b> - the policy says use <i>neither</i>, because all core operands are bounded and
 *       {@code sqrt(x*x + y*y)} is both faster and more accurate in that range. Four arms so the
 *       claim is measured, not asserted.</li>
 * </ul>
 *
 * <p>Inputs come from fields, never from constants: a constant argument lets the JIT constant-fold
 * the whole call and the benchmark measures nothing. They are also deliberately not-quite-round
 * values, because some libm implementations special-case exact multiples of pi/2.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Fork(value = 3, jvmArgsAppend = {"-XX:+UseSerialGC"})
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
public class MathDispatchBenchmark {

    /** A mid-range latitude in radians; not a multiple of pi/2. */
    private double angle;
    /** In (-1, 1), so asin/acos/atanh are all in domain. */
    private double unit;
    /** Positive and not 1.0, so log/log10 are not trivially zero. */
    private double positive;
    /** A small exponent argument, as in the tsfn/phi2 rewrites. */
    private double exponent;
    private double hypotX;
    private double hypotY;

    @Setup(Level.Trial)
    public void setUp() {
        angle = 0.8273456;
        unit = 0.7364512;
        positive = 1.4356721;
        exponent = 0.3129847;
        hypotX = 0.6134527;
        hypotY = 0.7896431;
    }

    // ============================================================================================
    // Group 1: intrinsified on x86-64 and AArch64. This is the tax the policy chooses to pay.
    // ============================================================================================

    @Benchmark public double mathSin()   { return Math.sin(angle); }
    @Benchmark public double strictSin() { return StrictMath.sin(angle); }

    @Benchmark public double mathCos()   { return Math.cos(angle); }
    @Benchmark public double strictCos() { return StrictMath.cos(angle); }

    @Benchmark public double mathTan()   { return Math.tan(angle); }
    @Benchmark public double strictTan() { return StrictMath.tan(angle); }

    @Benchmark public double mathExp()   { return Math.exp(exponent); }
    @Benchmark public double strictExp() { return StrictMath.exp(exponent); }

    @Benchmark public double mathLog()   { return Math.log(positive); }
    @Benchmark public double strictLog() { return StrictMath.log(positive); }

    @Benchmark public double mathLog10()   { return Math.log10(positive); }
    @Benchmark public double strictLog10() { return StrictMath.log10(positive); }

    @Benchmark public double mathPow()   { return Math.pow(positive, exponent); }
    @Benchmark public double strictPow() { return StrictMath.pow(positive, exponent); }

    /**
     * The combined case, because {@code sin} and {@code cos} of the same argument are what every
     * projection actually needs. On some platforms HotSpot fuses the pair; if the {@code strict}
     * arm's ratio here is worse than the ratio of the individual arms, that fusion is what the
     * policy is giving up, and that is a distinct cost from two independent calls.
     */
    @Benchmark public double mathSinCos()   { return Math.sin(angle) + Math.cos(angle); }
    @Benchmark public double strictSinCos() { return StrictMath.sin(angle) + StrictMath.cos(angle); }

    // ============================================================================================
    // Group 2: no intrinsic today, so Math already delegates to StrictMath. These arms should
    // measure as equal. They are here to detect the day that stops being true.
    // ============================================================================================

    @Benchmark public double mathAsin()   { return Math.asin(unit); }
    @Benchmark public double strictAsin() { return StrictMath.asin(unit); }

    @Benchmark public double mathAcos()   { return Math.acos(unit); }
    @Benchmark public double strictAcos() { return StrictMath.acos(unit); }

    @Benchmark public double mathAtan()   { return Math.atan(unit); }
    @Benchmark public double strictAtan() { return StrictMath.atan(unit); }

    @Benchmark public double mathAtan2()   { return Math.atan2(hypotY, hypotX); }
    @Benchmark public double strictAtan2() { return StrictMath.atan2(hypotY, hypotX); }

    @Benchmark public double mathSinh()   { return Math.sinh(unit); }
    @Benchmark public double strictSinh() { return StrictMath.sinh(unit); }

    @Benchmark public double mathCosh()   { return Math.cosh(unit); }
    @Benchmark public double strictCosh() { return StrictMath.cosh(unit); }

    @Benchmark public double mathLog1p()   { return Math.log1p(unit); }
    @Benchmark public double strictLog1p() { return StrictMath.log1p(unit); }

    // ============================================================================================
    // Group 3: hypot. The policy says use neither. Four arms so the 10-30x claim is measured.
    // ============================================================================================

    @Benchmark public double mathHypot()   { return Math.hypot(hypotX, hypotY); }
    @Benchmark public double strictHypot() { return StrictMath.hypot(hypotX, hypotY); }

    /** What the policy prescribes at every core site. */
    @Benchmark public double sqrtOfSumOfSquares() {
        return Math.sqrt(hypotX * hypotX + hypotY * hypotY);
    }

    /** The named helper core actually calls, so the number is attributable to a real call site. */
    @Benchmark public double mathHelpersNorm2() {
        return MathHelpers.norm2(hypotX, hypotY);
    }

    /** {@code Math.sqrt} alone, as the denominator for the "hypot is 10-30x a sqrt" claim. */
    @Benchmark public double mathSqrt() {
        return Math.sqrt(positive);
    }
}

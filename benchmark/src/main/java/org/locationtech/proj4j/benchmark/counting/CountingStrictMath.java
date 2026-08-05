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
package org.locationtech.proj4j.benchmark.counting;

import org.locationtech.proj4j.benchmark.counting.OpCounters.Op;

/**
 * The {@code java.lang.StrictMath} counterpart of {@link CountingMath}: same counters, but values
 * delegated to {@code StrictMath} so a {@code StrictMath} call site keeps its exact result.
 *
 * <p><b>Why this is a separate class and not a flag on {@link CountingMath}.</b> The rewrite replaces
 * a class <i>name</i> in the constant pool, so by the time the call happens there is no way to know
 * which of the two the source wrote. Mapping both to one facade would force a single delegation
 * target, and delegating a {@code StrictMath.log1p} call to {@code Math.log1p} can change the result
 * by an ulp. That sounds harmless until it lands in one of core's fixed-point iterations, where an ulp
 * changes the <i>trip count</i> - and a trip count is exactly what Tier 2 measures. Two facades keeps
 * the instrumented run's arithmetic bit-identical to the uninstrumented run's, which is the only way
 * the counts describe the real code.
 *
 * <p>The counted methods delegate to {@code StrictMath}. The <b>uncounted</b> ones delegate to
 * {@code Math}, deliberately: {@code abs}, {@code floor}, {@code ceil}, {@code rint},
 * {@code copySign}, {@code max}, {@code min} and {@code sqrt} are exactly-rounded and therefore
 * bit-identical between the two ({@code reference/numerics.md}, "Guaranteed bit-reproducible
 * everywhere"), and routing them through {@code Math} avoids depending on which JDK version added
 * which {@code StrictMath} overload.
 *
 * <p>{@code sqrt} is an exception to that rule: it is exact, so its <i>value</i> is delegated to
 * {@code Math}, but it is still <b>counted</b>, because a change in the number of square roots per
 * transform is an algorithmic change worth catching.
 */
public final class CountingStrictMath {

    public static final double PI = StrictMath.PI;
    public static final double E = StrictMath.E;

    private CountingStrictMath() {
    }

    // ============================================================================================
    // Counted. Values from StrictMath, so the instrumented run is bit-identical to the real one.
    // ============================================================================================

    public static double sin(double a) {
        OpCounters.bump(Op.SIN);
        return StrictMath.sin(a);
    }

    public static double cos(double a) {
        OpCounters.bump(Op.COS);
        return StrictMath.cos(a);
    }

    public static double tan(double a) {
        OpCounters.bump(Op.TAN);
        return StrictMath.tan(a);
    }

    public static double pow(double a, double b) {
        OpCounters.bump(Op.POW);
        return StrictMath.pow(a, b);
    }

    public static double exp(double a) {
        OpCounters.bump(Op.EXP);
        return StrictMath.exp(a);
    }

    public static double log(double a) {
        OpCounters.bump(Op.LOG);
        return StrictMath.log(a);
    }

    public static double atan(double a) {
        OpCounters.bump(Op.ATAN);
        return StrictMath.atan(a);
    }

    public static double atan2(double y, double x) {
        OpCounters.bump(Op.ATAN2);
        return StrictMath.atan2(y, x);
    }

    /** Exact, so the value comes from {@code Math}; counted because the call count still matters. */
    public static double sqrt(double a) {
        OpCounters.bump(Op.SQRT);
        return Math.sqrt(a);
    }

    public static double hypot(double x, double y) {
        OpCounters.bump(Op.HYPOT);
        return StrictMath.hypot(x, y);
    }

    public static double asin(double a) {
        OpCounters.bump(Op.ASIN);
        return StrictMath.asin(a);
    }

    public static double acos(double a) {
        OpCounters.bump(Op.ACOS);
        return StrictMath.acos(a);
    }

    public static double log10(double a) {
        OpCounters.bump(Op.LOG10);
        return StrictMath.log10(a);
    }

    public static double sinh(double a) {
        OpCounters.bump(Op.SINH);
        return StrictMath.sinh(a);
    }

    public static double cosh(double a) {
        OpCounters.bump(Op.COSH);
        return StrictMath.cosh(a);
    }

    public static double log1p(double a) {
        OpCounters.bump(Op.LOG1P);
        return StrictMath.log1p(a);
    }

    public static double cbrt(double a) {
        OpCounters.bump(Op.CBRT);
        return StrictMath.cbrt(a);
    }

    public static double expm1(double a) {
        OpCounters.bump(Op.EXPM1);
        return StrictMath.expm1(a);
    }

    public static double tanh(double a) {
        OpCounters.bump(Op.TANH);
        return StrictMath.tanh(a);
    }

    // ============================================================================================
    // Uncounted pass-throughs, via Math: exact in both, and version-independent.
    // ============================================================================================

    public static double abs(double a) { return Math.abs(a); }
    public static float abs(float a) { return Math.abs(a); }
    public static int abs(int a) { return Math.abs(a); }
    public static long abs(long a) { return Math.abs(a); }

    public static double max(double a, double b) { return Math.max(a, b); }
    public static float max(float a, float b) { return Math.max(a, b); }
    public static int max(int a, int b) { return Math.max(a, b); }
    public static long max(long a, long b) { return Math.max(a, b); }

    public static double min(double a, double b) { return Math.min(a, b); }
    public static float min(float a, float b) { return Math.min(a, b); }
    public static int min(int a, int b) { return Math.min(a, b); }
    public static long min(long a, long b) { return Math.min(a, b); }

    public static double floor(double a) { return Math.floor(a); }
    public static double ceil(double a) { return Math.ceil(a); }
    public static double rint(double a) { return Math.rint(a); }
    public static long round(double a) { return Math.round(a); }
    public static int round(float a) { return Math.round(a); }

    public static double copySign(double magnitude, double sign) { return Math.copySign(magnitude, sign); }
    public static float copySign(float magnitude, float sign) { return Math.copySign(magnitude, sign); }
    public static double signum(double d) { return Math.signum(d); }
    public static float signum(float f) { return Math.signum(f); }

    /** {@code StrictMath.toRadians}/{@code toDegrees} are plain multiplies; identical to {@code Math}. */
    public static double toRadians(double angdeg) { return Math.toRadians(angdeg); }
    public static double toDegrees(double angrad) { return Math.toDegrees(angrad); }

    public static double IEEEremainder(double f1, double f2) { return StrictMath.IEEEremainder(f1, f2); }

    public static double ulp(double d) { return Math.ulp(d); }
    public static float ulp(float f) { return Math.ulp(f); }
    public static double nextUp(double d) { return Math.nextUp(d); }
    public static double nextDown(double d) { return Math.nextDown(d); }
    public static double nextAfter(double start, double direction) { return Math.nextAfter(start, direction); }
    public static double scalb(double d, int scaleFactor) { return Math.scalb(d, scaleFactor); }
    public static int getExponent(double d) { return Math.getExponent(d); }

    public static int floorDiv(int x, int y) { return Math.floorDiv(x, y); }
    public static int floorMod(int x, int y) { return Math.floorMod(x, y); }
    public static long floorDiv(long x, long y) { return Math.floorDiv(x, y); }
    public static long floorMod(long x, long y) { return Math.floorMod(x, y); }
    public static int toIntExact(long value) { return Math.toIntExact(value); }
    public static int addExact(int x, int y) { return Math.addExact(x, y); }
    public static int subtractExact(int x, int y) { return Math.subtractExact(x, y); }
    public static int multiplyExact(int x, int y) { return Math.multiplyExact(x, y); }

    /** See {@link CountingMath#fma}: present for linkage only; core must not call it. */
    public static double fma(double a, double b, double c) { return Math.fma(a, b, c); }

    public static double random() { return StrictMath.random(); }
}

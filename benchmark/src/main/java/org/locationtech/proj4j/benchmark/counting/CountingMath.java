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
 * A drop-in stand-in for {@code java.lang.Math} that tallies transcendental calls, used only by
 * {@link CountingClassLoader}.
 *
 * <p><b>How this is reached.</b> {@link CountingClassLoader} rewrites the string
 * {@code "java/lang/Math"} in the constant pool of every {@code org.locationtech.proj4j} class it
 * loads into this class's internal name. Nothing in {@code core} references this file, at compile time
 * or otherwise - which is the whole point. {@code reference/performance.md} calls for a
 * <b>test-only</b> facade, and a facade that lived in {@code core} would put a counter increment on
 * the hot path of a published library, which is exactly the kind of thing the rest of that document
 * exists to prevent.
 *
 * <p><b>Every method here must have the same name and descriptor as the {@code java.lang.Math} method
 * it stands in for</b>, because the rewrite changes only the class name; the {@code NameAndType}
 * entries are untouched. So the surface below is the union of what {@code core} calls today plus a
 * margin. If someone adds a call to a {@code Math} method that is not here, the counting run fails
 * with a {@code NoSuchMethodError} naming it - loudly, only in the gate, and never in production. The
 * fix is one delegating method here.
 *
 * <p><b>Values are delegated faithfully to {@code java.lang.Math}</b>, and that matters more than it
 * looks: several core routines are fixed-point iterations whose <i>trip count</i> depends on the value
 * returned, so a facade that quietly substituted {@code StrictMath} would change the counts it is
 * supposed to be measuring. {@link CountingStrictMath} exists as a separate class for the same reason,
 * so that a {@code StrictMath} call site is still counted <i>and</i> still evaluated by
 * {@code StrictMath}.
 *
 * <p><b>Timings taken under the counting loader are meaningless.</b> Routing {@code Math.sin} through
 * a static forwarder defeats the {@code @IntrinsicCandidate} substitution, so an instrumented run is
 * several times slower than the real thing. Tier 2 measures counts, Tier 1 and Tier 3 measure the
 * uninstrumented code. Never quote a number from an instrumented run as a timing.
 */
public final class CountingMath {

    public static final double PI = Math.PI;
    public static final double E = Math.E;

    private CountingMath() {
    }

    // ============================================================================================
    // Counted: the ten from reference/performance.md.
    // ============================================================================================

    public static double sin(double a) {
        OpCounters.bump(Op.SIN);
        return Math.sin(a);
    }

    public static double cos(double a) {
        OpCounters.bump(Op.COS);
        return Math.cos(a);
    }

    public static double tan(double a) {
        OpCounters.bump(Op.TAN);
        return Math.tan(a);
    }

    public static double pow(double a, double b) {
        OpCounters.bump(Op.POW);
        return Math.pow(a, b);
    }

    public static double exp(double a) {
        OpCounters.bump(Op.EXP);
        return Math.exp(a);
    }

    public static double log(double a) {
        OpCounters.bump(Op.LOG);
        return Math.log(a);
    }

    public static double atan(double a) {
        OpCounters.bump(Op.ATAN);
        return Math.atan(a);
    }

    public static double atan2(double y, double x) {
        OpCounters.bump(Op.ATAN2);
        return Math.atan2(y, x);
    }

    public static double sqrt(double a) {
        OpCounters.bump(Op.SQRT);
        return Math.sqrt(a);
    }

    public static double hypot(double x, double y) {
        OpCounters.bump(Op.HYPOT);
        return Math.hypot(x, y);
    }

    // ============================================================================================
    // Counted: the extras.
    // ============================================================================================

    public static double asin(double a) {
        OpCounters.bump(Op.ASIN);
        return Math.asin(a);
    }

    public static double acos(double a) {
        OpCounters.bump(Op.ACOS);
        return Math.acos(a);
    }

    public static double log10(double a) {
        OpCounters.bump(Op.LOG10);
        return Math.log10(a);
    }

    public static double sinh(double a) {
        OpCounters.bump(Op.SINH);
        return Math.sinh(a);
    }

    public static double cosh(double a) {
        OpCounters.bump(Op.COSH);
        return Math.cosh(a);
    }

    public static double log1p(double a) {
        OpCounters.bump(Op.LOG1P);
        return Math.log1p(a);
    }

    public static double cbrt(double a) {
        OpCounters.bump(Op.CBRT);
        return Math.cbrt(a);
    }

    public static double expm1(double a) {
        OpCounters.bump(Op.EXPM1);
        return Math.expm1(a);
    }

    public static double tanh(double a) {
        OpCounters.bump(Op.TANH);
        return Math.tanh(a);
    }

    // ============================================================================================
    // Uncounted pass-throughs. Exact operations (guaranteed bit-reproducible per
    // reference/numerics.md) plus the handful of integer helpers, present only so that a class that
    // also calls one of these still links after the rewrite.
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

    public static double toRadians(double angdeg) { return Math.toRadians(angdeg); }
    public static double toDegrees(double angrad) { return Math.toDegrees(angrad); }

    public static double IEEEremainder(double f1, double f2) { return Math.IEEEremainder(f1, f2); }

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

    /**
     * Present for linkage only. {@code reference/numerics.md} says <b>do not use
     * {@code Math.fma}</b> - on hardware without FMA it falls back to a {@code BigDecimal} path, and
     * it changes results via single rounding, which collides with the determinism requirement. If the
     * counting run ever reports a call reaching this method, that is a defect in {@code core}, not in
     * the gate.
     */
    public static double fma(double a, double b, double c) { return Math.fma(a, b, c); }

    public static double random() { return Math.random(); }
}

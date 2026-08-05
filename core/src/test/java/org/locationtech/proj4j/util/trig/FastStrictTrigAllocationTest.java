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

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.locationtech.proj4j.util.FastStrictTrig;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Modifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Measures allocation with {@code com.sun.management.ThreadMXBean.getThreadAllocatedBytes} -- the
 * same instrument that produced the original finding (32.41 / 32.00 / 32.00 B/op for
 * {@code StrictMath.sin} / {@code cos} / {@code tan} on Temurin 21 over 5e6 calls), so the test
 * proves the claim rather than a proxy for it.
 *
 * <p>Why {@code StrictMath} allocates at all, <em>on JDK 21 and later</em>: the JEP 306 line of work
 * replaced the JNI-into-fdlibm implementation of {@code sin}/{@code cos}/{@code tan}
 * with pure Java, and {@code java.lang.FdLibm.Sin/Cos/Tan.compute} each open with
 * {@code new double[2]} to receive the two-word result of {@code __ieee754_rem_pio2} -- a C
 * out-parameter idiom. The array does not escape, but the callee is far too large to inline, so
 * escape analysis cannot scalarise it. {@code exp}, {@code log}, {@code pow}, {@code atan},
 * {@code asin}, {@code log1p}, {@code sinh} and {@code hypot} allocate nothing: this is a
 * three-function problem.
 *
 * <p>Three kinds of check, and the distinction between them is the point of the class:
 *
 * <ol>
 *   <li><strong>Asserted unconditionally, on every JDK:</strong> {@link FastStrictTrig} allocates
 *       0 B/op, in isolation and in a loop shaped like a real projection kernel, for ordinary and
 *       for multi-word-reduction arguments. This is the property the library depends on and it is
 *       the only thing here that may ever turn a build red.</li>
 *   <li><strong>Asserted unconditionally, and what makes the 0.00 above mean something:</strong>
 *       the instrument itself. {@link #theInstrumentCanSeeAllocation} runs workloads that allocate
 *       a <em>known</em> amount and requires the counter to see them, and to scale with them; and
 *       {@link #theInstrumentDoesNotAllocate} requires the counter not to perturb what it measures.
 *       A "0 B/op" from a blind instrument is exactly as clean and exactly as worthless as a real
 *       one.</li>
 *   <li><strong>Conditional on the JVM's {@code StrictMath} being the pure-Java one, and reported
 *       as a skip otherwise:</strong> anything asserting what {@code StrictMath} costs. See the
 *       next section -- on JDK 8..17 the answer is structurally 0 and no assertion of that shape
 *       can hold.</li>
 * </ol>
 *
 * <h2>Why nothing here may assert that {@code StrictMath} allocates: it does not, before JDK 21</h2>
 *
 * <p>{@code StrictMath.sin}, {@code cos} and {@code tan} were still <strong>native JNI calls into
 * C fdlibm on JDK 17</strong>; the Java port that introduced the allocating {@code double[2]}
 * landed in <strong>JDK 21</strong>. Measured, not inferred, two independent ways:
 *
 * <table>
 *   <caption>{@code java.base}, Corretto 17.0.20 vs 21.0.12, aarch64</caption>
 *   <tr><th></th><th>JDK 17</th><th>JDK 21</th></tr>
 *   <tr><td>{@code Modifier.isNative(StrictMath.sin)}</td><td>{@code true}</td>
 *       <td>{@code false}</td></tr>
 *   <tr><td>{@code java.lang.FdLibm$Sin/$Cos/$Tan/$RemPio2}</td><td><em>absent from
 *       {@code lib/modules} entirely</em></td><td>present</td></tr>
 *   <tr><td>measured {@code StrictMath.sin} over {@code [-pi, pi]}</td><td>0.00 B/op</td>
 *       <td>~62 B/op</td></tr>
 * </table>
 *
 * <p>So a JDK 17 reading of 0.00 is not flake, not a vendor difference and not escape analysis --
 * the object whose absence is being measured <em>does not exist in that JDK's class library</em>.
 * Corretto 17.0.20 reads 0.00 every time; Corretto 21.0.12, Corretto 25.0.4 and Temurin 21.0.11
 * read non-zero every time.
 *
 * <p>The guard used below is therefore {@link java.lang.reflect.Modifier#isNative} on
 * {@code StrictMath.sin/cos/tan}, <strong>not</strong> "did we measure zero". That distinction is
 * load-bearing: a guard of the form <em>skip the assertion when the measurement is zero</em> is the
 * assertion's own negation and can never fail, whereas nativeness is an independent fact, so a
 * pure-Java {@code StrictMath} that stopped allocating would still be caught and reported.
 *
 * <p>Measurement method: warm up past C2 compilation, then read the thread's allocation counter,
 * run N operations, read it again. {@code getThreadAllocatedBytes} takes a primitive {@code long}
 * and returns a primitive {@code long}, so the instrument itself allocates nothing. Results are
 * reported as bytes per operation over N = 5,000,000; a per-op figure below 0.01 B is treated as
 * zero, which at that N absorbs up to 50 KB of one-off harness noise (class loading, the first
 * {@code Scratch}, JIT deoptimisation buffers).
 */
public class FastStrictTrigAllocationTest {

    private static final int N = 5_000_000;
    private static final int WARMUP = 200_000;

    /** Below this many bytes per operation, the operation allocates nothing. */
    private static final double ZERO_TOLERANCE = 0.01;

    private static com.sun.management.ThreadMXBean bean;

    /** Kept in a static so the JIT cannot prove the computed values are dead. */
    public static volatile double sink;

    /** Escape hatch for the instrument's positive control -- a static volatile cannot be elided. */
    public static volatile Object objectSink;

    // ------------------------------------------------------------------
    // Which StrictMath this JVM has. See the class javadoc: JNI-into-C fdlibm through JDK 17,
    // pure Java from JDK 21, and only the pure-Java one can allocate.
    // ------------------------------------------------------------------

    private static final boolean STRICT_TRIG_IS_PURE_JAVA = strictTrigIsPureJava();

    private static boolean strictTrigIsPureJava() {
        try {
            for (String name : new String[] {"sin", "cos", "tan"}) {
                if (Modifier.isNative(
                        StrictMath.class.getDeclaredMethod(name, double.class).getModifiers())) {
                    return false;
                }
            }
            return true;
        } catch (NoSuchMethodException e) {
            throw new AssertionError("StrictMath.sin/cos/tan(double) must exist", e);
        }
    }

    /**
     * Corroboration only, never asserted on: the presence of the nested class that holds the
     * allocating carrier. Printed alongside the nativeness verdict so the log records both signals
     * and a future disagreement between them is visible rather than silent. Not a guard, because
     * the name of a JDK-internal class is exactly the kind of third-party implementation detail
     * that must not be able to redden this build.
     */
    private static boolean fdLibmSinClassExists() {
        try {
            Class.forName("java.lang.FdLibm$Sin");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String strictMathImplementation() {
        return (STRICT_TRIG_IS_PURE_JAVA ? "pure Java (JDK 21+ FdLibm port)" : "native JNI fdlibm")
                + " on " + System.getProperty("java.vm.name") + " "
                + System.getProperty("java.version") + " / " + System.getProperty("os.arch")
                + "; java.lang.FdLibm$Sin " + (fdLibmSinClassExists() ? "present" : "absent");
    }

    /**
     * Skips, loudly, when this JVM's {@code StrictMath} cannot allocate because it is not written
     * in Java. The reason goes to stdout as well as into the JUnit assumption, so it is visible in
     * a build log and not only in the surefire XML. A skip is a skip, never a pass.
     */
    private static void requirePureJavaStrictMath(String whatIsBeingSkipped) {
        if (!STRICT_TRIG_IS_PURE_JAVA) {
            String why = whatIsBeingSkipped + ": this JVM's StrictMath.sin/cos/tan are "
                    + strictMathImplementation() + ". A native implementation allocates no Java "
                    + "object, so it necessarily measures 0 B/op and there is no premise to test. "
                    + "FastStrictTrig's own 0 B/op is asserted unconditionally elsewhere in this "
                    + "class, and its bit-identity to StrictMath in FastStrictTrigIdentityTest.";
            System.out.println("  [SKIP] " + why);
            Assume.assumeTrue(why, false);
        }
    }

    @BeforeClass
    public static void resolveBean() {
        ThreadMXBean plain = ManagementFactory.getThreadMXBean();
        Assume.assumeTrue("this JVM does not expose com.sun.management.ThreadMXBean; "
                        + "allocation cannot be measured here",
                plain instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean b = (com.sun.management.ThreadMXBean) plain;
        Assume.assumeTrue("this JVM does not support per-thread allocation counting",
                b.isThreadAllocatedMemorySupported());
        b.setThreadAllocatedMemoryEnabled(true);
        bean = b;
    }

    private static long allocated() {
        return bean.getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    // ------------------------------------------------------------------
    // Workloads. Each returns an accumulated double so nothing is dead code.
    // ------------------------------------------------------------------

    private interface Workload {
        /** Performs {@code n} operations and returns an accumulator. */
        double run(int n);
    }

    /**
     * Measures a workload's bytes per operation. The counter is read immediately before and after
     * the timed region; the accumulator is published to a volatile field afterwards so the whole
     * loop cannot be eliminated.
     */
    private static double bytesPerOp(String label, Workload w) {
        return bytesPerOp(label, N, w);
    }

    /** As {@link #bytesPerOp(String, Workload)}, over an explicit operation count. */
    private static double bytesPerOp(String label, int ops, Workload w) {
        sink += w.run(WARMUP);   // reach C2, and materialise anything lazily created
        sink += w.run(WARMUP);
        long before = allocated();
        double acc = w.run(ops);
        long after = allocated();
        sink += acc;
        double bpo = (after - before) / (double) ops;
        System.out.printf("  %-52s %10.4f B/op   (%d B total over %d ops)%n",
                label, bpo, after - before, ops);
        return bpo;
    }

    /**
     * An angle sequence that stays in the range a coordinate transform produces (radians of
     * latitude and longitude) and that the JIT cannot constant-fold.
     */
    private static double angle(int i) {
        return ((i & 0xffff) - 32768) * (Math.PI / 32768.0);
    }

    // ------------------------------------------------------------------
    // 1. Direct calls, the shape the original finding used
    // ------------------------------------------------------------------

    /**
     * 5e6 direct calls each, the shape that produced the original 32.41 / 32.00 / 32.00 B/op
     * finding — but over the full {@code [-pi, pi]} range a longitude actually spans, which
     * measures nearer 62 B/op. See {@link #strictMathAllocationTiersWithArgumentMagnitude} for
     * why the two numbers differ.
     */
    @Test
    public void directCallsAllocateNothing() {
        System.out.println("[FastStrictTrig] direct calls, " + N + " ops each:");

        double fastSin = bytesPerOp("FastStrictTrig.sin", new Workload() {
            public double run(int n) {
                double a = 0.0;
                for (int i = 0; i < n; i++) {
                    a += FastStrictTrig.sin(angle(i));
                }
                return a;
            }
        });
        double fastCos = bytesPerOp("FastStrictTrig.cos", new Workload() {
            public double run(int n) {
                double a = 0.0;
                for (int i = 0; i < n; i++) {
                    a += FastStrictTrig.cos(angle(i));
                }
                return a;
            }
        });
        double fastTan = bytesPerOp("FastStrictTrig.tan", new Workload() {
            public double run(int n) {
                double a = 0.0;
                for (int i = 0; i < n; i++) {
                    a += FastStrictTrig.tan(angle(i));
                }
                return a;
            }
        });
        double[] strict = measureStrictTrigDirectCalls();
        double mathSin = bytesPerOp("Math.sin (intrinsic, for reference)", new Workload() {
            public double run(int n) {
                double a = 0.0;
                for (int i = 0; i < n; i++) {
                    a += Math.sin(angle(i));
                }
                return a;
            }
        });

        assertZero("FastStrictTrig.sin", fastSin);
        assertZero("FastStrictTrig.cos", fastCos);
        assertZero("FastStrictTrig.tan", fastTan);
        assertZero("Math.sin", mathSin);

        // Observation, not an assertion. What StrictMath costs is a property of the JDK, and on
        // JDK 8..17 it is structurally zero -- see the class javadoc. The assertion that it costs
        // more lives in strictMathAllocatesWhereItsImplementationIsPureJava, which skips there.
        System.out.printf("  => StrictMath.sin/cos/tan measured %.2f / %.2f / %.2f B/op; "
                        + "implementation is %s.%n",
                strict[0], strict[1], strict[2], strictMathImplementation());
    }

    /** The three direct-call {@code StrictMath} figures, measured and printed, never asserted. */
    private static double[] measureStrictTrigDirectCalls() {
        double strictSin = bytesPerOp("StrictMath.sin", new Workload() {
            public double run(int n) {
                double a = 0.0;
                for (int i = 0; i < n; i++) {
                    a += StrictMath.sin(angle(i));
                }
                return a;
            }
        });
        double strictCos = bytesPerOp("StrictMath.cos", new Workload() {
            public double run(int n) {
                double a = 0.0;
                for (int i = 0; i < n; i++) {
                    a += StrictMath.cos(angle(i));
                }
                return a;
            }
        });
        double strictTan = bytesPerOp("StrictMath.tan", new Workload() {
            public double run(int n) {
                double a = 0.0;
                for (int i = 0; i < n; i++) {
                    a += StrictMath.tan(angle(i));
                }
                return a;
            }
        });
        return new double[] {strictSin, strictCos, strictTan};
    }

    /**
     * The reason {@link FastStrictTrig} was written, asserted where it can be: on a JVM whose
     * {@code StrictMath} is the pure-Java {@code FdLibm} port, at least one of
     * {@code sin}/{@code cos}/{@code tan} must allocate, or the class buys nothing on the
     * allocation axis and the re-point could be reconsidered.
     *
     * <p>Deliberately not pinned to 32 or 62 B/op: a JDK that fixes {@code FdLibm}, or gains the
     * escape analysis to scalarise the carrier, is a good outcome and must not be a red build.
     *
     * <p>Skipped, with the reason printed, on a JVM whose {@code StrictMath.sin/cos/tan} are
     * native -- every JDK up to and including 17. The guard is nativeness, not the measurement, so
     * on JDK 21+ this assertion is live and can genuinely fail.
     */
    @Test
    public void strictMathAllocatesWhereItsImplementationIsPureJava() {
        System.out.println("[FastStrictTrig] premise check -- does StrictMath allocate here?");
        requirePureJavaStrictMath("StrictMath allocation premise");

        double[] strict = measureStrictTrigDirectCalls();
        assertTrue("FastStrictTrig exists to remove StrictMath's per-call allocation, and this "
                        + "JVM's StrictMath is " + strictMathImplementation() + " -- yet "
                        + "sin/cos/tan allocate nothing (" + strict[0] + " / " + strict[1] + " / "
                        + strict[2] + " B/op). The class is then redundant for allocation purposes; "
                        + "it is still required for bit-identity, so this is a finding, not a bug.",
                strict[0] > ZERO_TOLERANCE || strict[1] > ZERO_TOLERANCE
                        || strict[2] > ZERO_TOLERANCE);
    }

    /**
     * Explains why {@code StrictMath}'s per-call allocation is not a single number, and reconciles
     * the 32 B/op headline with the ~62 B/op that {@link #directCallsAllocateNothing} measures.
     *
     * <p>{@code FdLibm.Sin.compute} allocates its {@code double[2] y} <em>unconditionally</em>, at
     * the top of the method, before the {@code |x| ~< pi/4} short circuit — so every call pays
     * 32 B. A call that needs argument reduction then enters
     * {@code RemPio2.__ieee754_rem_pio2}, which unconditionally allocates a {@code double[3] tx},
     * a further 40 B. A call large enough for the multi-word path additionally allocates
     * {@code __kernel_rem_pio2}'s {@code int[20]} plus three {@code double[20]}: 96 + 3*176 =
     * 624 B.
     *
     * <p>So the expected tiers are 32, 72 and 696 B/op, and the figure for a mixed workload is the
     * weighted mean. The original 32 B/op finding was therefore measured on arguments that stayed
     * inside {@code pi/4}. Longitude in radians spans {@code +/-pi}, so three quarters of a
     * realistic {@code sin(lam)} workload takes the reduction path and the true cost is nearer
     * <strong>62 B/op</strong> — the headline understated it by about 2x.
     *
     * <p>Every tier described above is a feature of the <em>Java</em> {@code FdLibm}, so this test
     * is skipped, with the reason printed, on a JVM whose {@code StrictMath.sin} is native -- there
     * is no {@code double[2]}, no {@code double[3]} and no {@code int[20]} to tier. The half of the
     * old test that is JDK-independent, {@link FastStrictTrig} costing nothing on any of the three
     * magnitudes, was split out into
     * {@link #fastStrictTrigAllocatesNothingAcrossAllMagnitudeTiers} so that it always runs.
     */
    @Test
    public void strictMathAllocationTiersWithArgumentMagnitude() {
        System.out.println("[FastStrictTrig] StrictMath.sin allocation by argument magnitude:");
        requirePureJavaStrictMath("StrictMath allocation tiers");

        double small = bytesPerOp("StrictMath.sin, |x| < pi/4  (expect ~32 B)", new Workload() {
            public double run(int n) {
                double a = 0.0;
                for (int i = 0; i < n; i++) {
                    a += StrictMath.sin(angle(i) * 0.24);
                }
                return a;
            }
        });
        double reduced = bytesPerOp("StrictMath.sin, pi/4 < |x| < 3pi/4 (expect ~72 B)",
                new Workload() {
                    public double run(int n) {
                        double a = 0.0;
                        for (int i = 0; i < n; i++) {
                            a += StrictMath.sin(1.2 + (i & 1023) * 1e-6);
                        }
                        return a;
                    }
                });
        double huge = bytesPerOp("StrictMath.sin, |x| > 2^19*(pi/2) (expect ~696 B)",
                new Workload() {
                    public double run(int n) {
                        double a = 0.0;
                        for (int i = 0; i < n; i++) {
                            a += StrictMath.sin(1e100 + i);
                        }
                        return a;
                    }
                });

        assertTrue("even inside pi/4, StrictMath.sin allocates the double[2] it never uses; "
                        + "measured " + small + " B/op", small > ZERO_TOLERANCE);
        assertTrue("the reduction path must cost more than the short circuit: "
                        + reduced + " vs " + small, reduced > small);
        assertTrue("the multi-word path must cost more again: " + huge + " vs " + reduced,
                huge > reduced);
    }

    /**
     * The JDK-independent half of {@link #strictMathAllocationTiersWithArgumentMagnitude}:
     * {@link FastStrictTrig} costs nothing on any argument magnitude, including the multi-word
     * reduction path. Unconditional -- this is a statement about proj4j's own code and it holds on
     * every JDK.
     */
    @Test
    public void fastStrictTrigAllocatesNothingAcrossAllMagnitudeTiers() {
        System.out.println("[FastStrictTrig] FastStrictTrig.sin across all magnitude tiers:");
        double fastAll = bytesPerOp("FastStrictTrig.sin, three ranges combined", new Workload() {
            public double run(int n) {
                double a = 0.0;
                for (int i = 0; i < n; i++) {
                    a += FastStrictTrig.sin(angle(i) * 0.24)
                            + FastStrictTrig.sin(1.2 + (i & 1023) * 1e-6)
                            + FastStrictTrig.sin(1e100 + i);
                }
                return a;
            }
        });
        assertZero("FastStrictTrig.sin across all three magnitude tiers", fastAll);
    }

    // ------------------------------------------------------------------
    // 2. The question that decides whether this class is worth having
    // ------------------------------------------------------------------

    /**
     * The original 32 B/op was measured over 5e6 direct calls. C2 might scalarise the
     * {@code double[2]} once {@code StrictMath.sin} is inlined into a larger method with other
     * work around it -- in which case the cost in situ would be smaller than the headline and this
     * class would be optional. This test runs both implementations inside a loop shaped like a
     * real projection kernel and reports what actually happens.
     *
     * <p>The kernel is modelled on an ellipsoidal transverse Mercator forward: four {@code sin}
     * and four {@code cos} of latitude, doubled latitude, longitude and doubled longitude, plus
     * the surrounding {@code sqrt}/{@code atan}/multiply-add arithmetic, over a strided coordinate
     * array. One "operation" is one point, so the reported figure is bytes per vertex.
     */
    @Test
    public void projectionKernelLoopAllocatesNothing() {
        final double[] xy = new double[2 * 4096];
        for (int i = 0; i < 4096; i++) {
            xy[2 * i] = ((i % 360) - 180) * (Math.PI / 180.0);         // lambda
            xy[2 * i + 1] = ((i % 170) - 85) * (Math.PI / 180.0);      // phi
        }

        System.out.println("[FastStrictTrig] etmerc-shaped kernel, one op = one point:");

        double fast = bytesPerOp("kernel using FastStrictTrig", new Workload() {
            public double run(int n) {
                double a = 0.0;
                for (int i = 0; i < n; i++) {
                    int k = (i & 4095) << 1;
                    a += fastKernel(xy[k], xy[k + 1]);
                }
                return a;
            }
        });
        double strict = bytesPerOp("kernel using StrictMath", new Workload() {
            public double run(int n) {
                double a = 0.0;
                for (int i = 0; i < n; i++) {
                    int k = (i & 4095) << 1;
                    a += strictKernel(xy[k], xy[k + 1]);
                }
                return a;
            }
        });
        double plain = bytesPerOp("kernel using Math (intrinsics, for reference)", new Workload() {
            public double run(int n) {
                double a = 0.0;
                for (int i = 0; i < n; i++) {
                    int k = (i & 4095) << 1;
                    a += mathKernel(xy[k], xy[k + 1]);
                }
                return a;
            }
        });

        System.out.printf("  => StrictMath costs %.2f B per point more than FastStrictTrig; "
                        + "at 8 trig calls/point that is %.2f B per trig call.%n",
                strict - fast, (strict - fast) / 8.0);
        System.out.printf("  => a 100k-vertex geometry: FastStrictTrig %.2f MB, StrictMath %.2f MB.%n",
                fast * 100_000.0 / (1024 * 1024), strict * 100_000.0 / (1024 * 1024));

        assertZero("etmerc-shaped kernel using FastStrictTrig", fast);
        assertZero("etmerc-shaped kernel using Math", plain);
    }

    /** Eight trig calls plus surrounding arithmetic, as an ellipsoidal tmerc forward would do. */
    private static double fastKernel(double lam, double phi) {
        double sp = FastStrictTrig.sin(phi);
        double cp = FastStrictTrig.cos(phi);
        double s2p = FastStrictTrig.sin(phi + phi);
        double c2p = FastStrictTrig.cos(phi + phi);
        double sl = FastStrictTrig.sin(lam);
        double cl = FastStrictTrig.cos(lam);
        double s2l = FastStrictTrig.sin(lam + lam);
        double c2l = FastStrictTrig.cos(lam + lam);
        return combine(sp, cp, s2p, c2p, sl, cl, s2l, c2l);
    }

    private static double strictKernel(double lam, double phi) {
        double sp = StrictMath.sin(phi);
        double cp = StrictMath.cos(phi);
        double s2p = StrictMath.sin(phi + phi);
        double c2p = StrictMath.cos(phi + phi);
        double sl = StrictMath.sin(lam);
        double cl = StrictMath.cos(lam);
        double s2l = StrictMath.sin(lam + lam);
        double c2l = StrictMath.cos(lam + lam);
        return combine(sp, cp, s2p, c2p, sl, cl, s2l, c2l);
    }

    private static double mathKernel(double lam, double phi) {
        double sp = Math.sin(phi);
        double cp = Math.cos(phi);
        double s2p = Math.sin(phi + phi);
        double c2p = Math.cos(phi + phi);
        double sl = Math.sin(lam);
        double cl = Math.cos(lam);
        double s2l = Math.sin(lam + lam);
        double c2l = Math.cos(lam + lam);
        return combine(sp, cp, s2p, c2p, sl, cl, s2l, c2l);
    }

    /** The non-trig half of the kernel, shared so the three variants differ only in dispatch. */
    private static double combine(double sp, double cp, double s2p, double c2p,
                                  double sl, double cl, double s2l, double c2l) {
        final double es = 0.00669437999014133;
        double n = Math.sqrt(1.0 - es * sp * sp);
        double t = sp / (cp + 1e-300);
        double eta = cp * sl;
        double x = 0.9996 * 6378137.0 * (eta + s2l * c2p * 0.0008 + s2p * 1e-6);
        double y = 0.9996 * 6378137.0 * (t / n + c2l * 0.0004 - s2p * 1e-7);
        return x + y + Math.atan2(sp, cl);
    }

    // ------------------------------------------------------------------
    // 3. The multi-word reduction path
    // ------------------------------------------------------------------

    /**
     * The only path in {@link FastStrictTrig} that still uses arrays is
     * {@code __kernel_rem_pio2}, entered for {@code |x| > 2^19*(pi/2)}. It takes them from a
     * reusable {@code Scratch}, so it must also be allocation-free -- with a caller-supplied
     * scratch unconditionally, and with the thread-local one after the first call on the thread.
     */
    @Test
    public void multiWordReductionPathAllocatesNothing() {
        System.out.println("[FastStrictTrig] huge arguments (multi-word reduction):");

        final FastStrictTrig.Scratch scratch = new FastStrictTrig.Scratch();
        double callerScratch = bytesPerOp("sin(x, callerScratch), |x| ~ 1e100", new Workload() {
            public double run(int n) {
                double a = 0.0;
                for (int i = 0; i < n; i++) {
                    a += FastStrictTrig.sin(1e100 + i, scratch);
                }
                return a;
            }
        });
        double threadLocal = bytesPerOp("sin(x) thread-local scratch, |x| ~ 1e100", new Workload() {
            public double run(int n) {
                double a = 0.0;
                for (int i = 0; i < n; i++) {
                    a += FastStrictTrig.sin(1e100 + i);
                }
                return a;
            }
        });
        double strictHuge = bytesPerOp("StrictMath.sin, |x| ~ 1e100", new Workload() {
            public double run(int n) {
                double a = 0.0;
                for (int i = 0; i < n; i++) {
                    a += StrictMath.sin(1e100 + i);
                }
                return a;
            }
        });
        System.out.printf("  => StrictMath allocates %.2f B/op more on the multi-word path.%n",
                strictHuge - threadLocal);

        assertZero("sin(x, callerScratch) on the multi-word path", callerScratch);
        assertZero("sin(x) on the multi-word path after warm-up", threadLocal);
    }

    /** Each thread gets its own {@code Scratch}, and none of them allocates per call. */
    @Test
    public void threadLocalScratchIsPerThreadAndFree() throws Exception {
        final double[] result = new double[1];
        final long[] bytes = new long[1];
        Thread t = new Thread(new Runnable() {
            public void run() {
                double a = 0.0;
                for (int i = 0; i < WARMUP; i++) {
                    a += FastStrictTrig.sin(1e100 + i) + FastStrictTrig.cos(1e100 + i);
                }
                long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
                for (int i = 0; i < N / 10; i++) {
                    a += FastStrictTrig.sin(1e100 + i) + FastStrictTrig.cos(1e100 + i);
                }
                long after = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
                bytes[0] = after - before;
                result[0] = a;
            }
        });
        t.start();
        t.join();
        sink += result[0];
        double bpo = bytes[0] / (double) (N / 10);
        System.out.printf("  %-52s %10.4f B/op%n", "second thread, thread-local scratch", bpo);
        assertZero("a second thread's thread-local scratch path", bpo);
    }

    // ------------------------------------------------------------------
    // 4. Sanity: the other transcendentals really are free
    // ------------------------------------------------------------------

    /**
     * Establishes which {@code StrictMath} functions allocate, so the scope of the fdlibm porting
     * problem is a measured fact rather than an assumption.
     *
     * <p><strong>Result, and a correction to the record.</strong> {@code exp}, {@code log},
     * {@code atan}, {@code atan2}, {@code asin}, {@code acos}, {@code log1p}, {@code sinh},
     * {@code cosh}, {@code hypot} and {@code sqrt} allocate nothing, as previously believed.
     * <strong>On Temurin 21.0.11 {@code pow} allocates 96 B/op</strong>, which the earlier survey
     * recorded as 0.00. It allocates 0 B/op on OpenJDK 26.0.2, so it has since been fixed
     * upstream -- {@code sin}, {@code cos} and {@code tan} have not, on either. The cause is not
     * subtle:
     * {@code FdLibm.Pow.compute} declares three {@code final double[]} locals in its general path,
     * {@code BP}, {@code DP_H} and {@code DP_L} (JDK 21 {@code FdLibm.java:2248-2253}), each of
     * length 2 — three 32-byte arrays. The earlier measurement must have used an exponent that
     * returns from one of {@code pow}'s special-value fast paths ({@code y == 0}, {@code 0.5},
     * {@code 1.0}, {@code 2.0}, {@code -1.0}, or an integral {@code y} with a power-of-two base),
     * all of which return before line 2248. This test therefore measures both a general
     * {@code pow} and a fast-path {@code pow} and reports them separately.
     *
     * <p>So this is a <em>four</em>-function problem. {@code pow} is not in this class's scope, but
     * it matters: today's {@code ProjectionMath.phi2} runs a 15-iteration Newton loop with a
     * {@code pow} per iteration, i.e. up to 1,440 B per inverse point, and {@code tsfn} uses
     * {@code pow} once per forward point. The numerics plan already removes both (a
     * {@code ConformalLat} port replaces {@code phi2}, and the {@code tsfn} rewrite trades
     * {@code tan}+{@code pow} for {@code exp}+{@code log1p}), which is the cheaper fix. If a
     * {@code pow} is still needed after that, the port is trivial — the three arrays are
     * {@code final} and never mutated, so lifting them to {@code static final} removes the
     * allocation with no change to the arithmetic.
     *
     * <p>Nothing here is asserted as zero except the functions that genuinely are on every JDK
     * tested. {@code pow}'s general path is version-dependent, so it is measured and printed
     * rather than asserted.
     */
    @Test
    public void whichStrictMathFunctionsAllocate() {
        System.out.println("[FastStrictTrig] other StrictMath functions, for scope confirmation:");

        double exp = bytesPerOp("StrictMath.exp", new Workload() {
            public double run(int n) {
                double a = 0.0;
                for (int i = 0; i < n; i++) {
                    a += StrictMath.exp(angle(i) * 0.01);
                }
                return a;
            }
        });
        double log = bytesPerOp("StrictMath.log", new Workload() {
            public double run(int n) {
                double a = 0.0;
                for (int i = 0; i < n; i++) {
                    a += StrictMath.log(2.0 + angle(i) * 0.1);
                }
                return a;
            }
        });
        double powGeneral = bytesPerOp("StrictMath.pow(base, 0.37) -- general path", new Workload() {
            public double run(int n) {
                double a = 0.0;
                for (int i = 0; i < n; i++) {
                    a += StrictMath.pow(2.0 + angle(i) * 0.1, 0.37);
                }
                return a;
            }
        });
        double powFastPath = bytesPerOp("StrictMath.pow(base, 0.5) -- special-value fast path",
                new Workload() {
                    public double run(int n) {
                        double a = 0.0;
                        for (int i = 0; i < n; i++) {
                            a += StrictMath.pow(2.0 + angle(i) * 0.1, 0.5);
                        }
                        return a;
                    }
                });
        double atan = bytesPerOp("StrictMath.atan", new Workload() {
            public double run(int n) {
                double a = 0.0;
                for (int i = 0; i < n; i++) {
                    a += StrictMath.atan(angle(i));
                }
                return a;
            }
        });
        double atan2 = bytesPerOp("StrictMath.atan2", new Workload() {
            public double run(int n) {
                double a = 0.0;
                for (int i = 0; i < n; i++) {
                    a += StrictMath.atan2(angle(i), 1.0);
                }
                return a;
            }
        });
        double asin = bytesPerOp("StrictMath.asin", new Workload() {
            public double run(int n) {
                double a = 0.0;
                for (int i = 0; i < n; i++) {
                    a += StrictMath.asin(angle(i) / 4.0);
                }
                return a;
            }
        });
        double acos = bytesPerOp("StrictMath.acos", new Workload() {
            public double run(int n) {
                double a = 0.0;
                for (int i = 0; i < n; i++) {
                    a += StrictMath.acos(angle(i) / 4.0);
                }
                return a;
            }
        });
        double log1p = bytesPerOp("StrictMath.log1p", new Workload() {
            public double run(int n) {
                double a = 0.0;
                for (int i = 0; i < n; i++) {
                    a += StrictMath.log1p(angle(i) * 0.1);
                }
                return a;
            }
        });
        double sinh = bytesPerOp("StrictMath.sinh", new Workload() {
            public double run(int n) {
                double a = 0.0;
                for (int i = 0; i < n; i++) {
                    a += StrictMath.sinh(angle(i) * 0.1);
                }
                return a;
            }
        });
        double cosh = bytesPerOp("StrictMath.cosh", new Workload() {
            public double run(int n) {
                double a = 0.0;
                for (int i = 0; i < n; i++) {
                    a += StrictMath.cosh(angle(i) * 0.1);
                }
                return a;
            }
        });
        double hypot = bytesPerOp("StrictMath.hypot", new Workload() {
            public double run(int n) {
                double a = 0.0;
                for (int i = 0; i < n; i++) {
                    a += StrictMath.hypot(angle(i), 1.0);
                }
                return a;
            }
        });
        double sqrt = bytesPerOp("StrictMath.sqrt", new Workload() {
            public double run(int n) {
                double a = 0.0;
                for (int i = 0; i < n; i++) {
                    a += StrictMath.sqrt(2.0 + angle(i) * 0.1);
                }
                return a;
            }
        });

        assertZero("StrictMath.exp", exp);
        assertZero("StrictMath.log", log);
        assertZero("StrictMath.atan", atan);
        assertZero("StrictMath.atan2", atan2);
        assertZero("StrictMath.asin", asin);
        assertZero("StrictMath.acos", acos);
        assertZero("StrictMath.log1p", log1p);
        assertZero("StrictMath.sinh", sinh);
        assertZero("StrictMath.cosh", cosh);
        assertZero("StrictMath.hypot", hypot);
        assertZero("StrictMath.sqrt", sqrt);
        assertZero("StrictMath.pow on a special-value fast path", powFastPath);

        // pow's general path is version-dependent: 96 B/op on Temurin 21, 0 B/op on OpenJDK 26.
        // Report it rather than assert it, and say which side of the fence this JVM is on.
        System.out.printf("  => StrictMath.pow general path allocates %.2f B/op on %s %s "
                        + "(measured: 96.00 on Temurin 21.0.11, 0.00 on OpenJDK 26.0.2 -- fixed "
                        + "upstream). sin/cos/tan are NOT fixed on either.%n",
                powGeneral, System.getProperty("java.vm.name"), System.getProperty("java.version"));
    }

    // ------------------------------------------------------------------

    private static void assertZero(String what, double bytesPerOp) {
        assertTrue(what + " must allocate nothing, measured " + bytesPerOp + " B/op",
                bytesPerOp < ZERO_TOLERANCE);
    }

    /**
     * <strong>The positive control, and the reason every {@code 0.00 B/op} above is worth
     * reading.</strong> An instrument that reports zero for everything reports exactly the result
     * this class hopes for, and the failure is silent. This test therefore runs two workloads whose
     * allocation is known by construction -- one {@code double[2]} per operation, and four -- and
     * requires the counter both to <em>see</em> them and to <em>scale</em> with them.
     *
     * <p>This replaces "{@code StrictMath} must allocate" as the non-vacuity guard. That premise
     * was scaffolding of the same intent but it was a claim about a third party's implementation,
     * and it is false on every JDK before 21 (see the class javadoc), so it failed on a supported
     * JDK while proving nothing about proj4j. This control is a claim about the measurement, which
     * is what actually needed proving, and it holds on every JDK.
     *
     * <p>Bounds are deliberately loose. The expected figure is 32 B per array -- a 16-byte array
     * header plus two doubles -- but header layout is a VM detail (compact object headers, compressed
     * class pointers), so the assertion is a floor of 16 B/op and a requirement that four arrays cost
     * at least three times one. Both are far below any plausible layout and far above the 0.01 B/op
     * that counts as zero, so the control discriminates without pinning a VM's object model.
     */
    @Test
    public void theInstrumentCanSeeAllocation() {
        final int ops = 500_000;
        System.out.println("[FastStrictTrig] positive control -- can the counter see a known "
                + "allocation?");

        double one = bytesPerOp("control: one double[2] per op (expect ~32 B)", ops,
                new Workload() {
                    public double run(int n) {
                        double a = 0.0;
                        for (int i = 0; i < n; i++) {
                            double[] carrier = new double[2];
                            carrier[0] = i;
                            carrier[1] = i + 1;
                            objectSink = carrier;      // escapes: C2 cannot scalar-replace it
                            a += carrier[0];
                        }
                        return a;
                    }
                });
        double four = bytesPerOp("control: four double[2] per op (expect ~128 B)", ops,
                new Workload() {
                    public double run(int n) {
                        double a = 0.0;
                        for (int i = 0; i < n; i++) {
                            for (int k = 0; k < 4; k++) {
                                double[] carrier = new double[2];
                                carrier[0] = i + k;
                                objectSink = carrier;
                                a += carrier[0];
                            }
                        }
                        return a;
                    }
                });

        assertTrue("the allocation counter cannot see a double[2] allocated on every one of "
                        + ops + " iterations -- it measured " + one + " B/op, so every 0.00 B/op "
                        + "reported by this class would be an artefact of a blind instrument "
                        + "rather than a property of the code",
                one >= 16.0);
        assertTrue("the allocation counter is not quantitative: four arrays per op measured "
                        + four + " B/op against " + one + " B/op for one, and must measure at "
                        + "least three times as much", four >= 3.0 * one);
        System.out.printf("  => the counter resolves %.2f B/op for one carrier and %.2f for four, "
                + "against a %.2f B/op zero threshold.%n", one, four, ZERO_TOLERANCE);
    }

    /** The instrument itself must not allocate, or every figure above is inflated. */
    @Test
    public void theInstrumentDoesNotAllocate() {
        for (int i = 0; i < 100_000; i++) {
            sink += allocated();
        }
        long before = allocated();
        long acc = 0;
        for (int i = 0; i < 1_000_000; i++) {
            acc += allocated();
        }
        long after = allocated();
        sink += acc;
        assertEquals("getThreadAllocatedBytes must not allocate; a boxed call would inflate every "
                        + "other measurement in this class",
                0L, (after - before) / 1_000_000L);
    }
}

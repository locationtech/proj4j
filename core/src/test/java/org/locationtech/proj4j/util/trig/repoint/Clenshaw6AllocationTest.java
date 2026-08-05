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

package org.locationtech.proj4j.util.trig.repoint;

import com.sun.management.ThreadMXBean;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.locationtech.proj4j.util.AuxLat;
import org.locationtech.proj4j.util.Clenshaw6;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Modifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link Clenshaw6#convert(double)} is the single highest-traffic trigonometric site in the
 * library — the auxiliary-latitude framework routes {@code utm tmerc lcc cass poly aea laea}
 * through it — and until this change it allocated on every call. This test measures that it no
 * longer does.
 *
 * <h2>The measurement</h2>
 *
 * <p>{@code com.sun.management.ThreadMXBean.getThreadAllocatedBytes} is used <strong>directly, not
 * reflectively</strong>: a reflective call would allocate an argument array and a boxed
 * {@code long} per invocation and so would measure the instrument. The cost of the direct call is
 * two {@code long} reads on either side of a loop of two million iterations, which is why the
 * figure resolves cleanly to {@code 0.00} rather than to a small positive number.
 *
 * <p>Four rounds run and the last is asserted, so the figure is a steady-state C2 measurement
 * rather than an interpreter or C1 one. This matters in both directions: escape analysis is the
 * thing that might have removed {@code StrictMath}'s array, and it only exists after the method
 * is compiled.
 *
 * <h2>Measured on Temurin 21.0.11, frozen {@code /tmp} A/B of {@code 7362c85} against this tree</h2>
 *
 * <p>Two builds compiled separately from two source trees whose only difference is the ten
 * re-pointed files, each measured three times, identical every time:
 *
 * <table>
 *   <caption>{@code Clenshaw6.convert(zeta)}, one {@code sin} + one {@code cos}, B/op</caption>
 *   <tr><th>argument domain</th><th>before ({@code StrictMath})</th><th>after</th></tr>
 *   <tr><td>{@code |zeta| <= pi/4}</td><td>64.00</td><td>0.00</td></tr>
 *   <tr><td>latitude, {@code [-pi/2, pi/2]} — what {@code etmerc}'s forward feeds it</td>
 *       <td>64.00</td><td>0.00</td></tr>
 *   <tr><td>full circle, {@code [-pi, pi]}</td><td>64.00</td><td>0.00</td></tr>
 *   <tr><td>medium reduction, {@code ~1e6}</td><td>64.00</td><td>0.00</td></tr>
 *   <tr><td>multi-word reduction, {@code |zeta| > 1647099}</td><td>1392.00</td><td>0.00</td></tr>
 * </table>
 *
 * <p>Three things in that table are worth stating explicitly, because each contradicts a figure
 * that could reasonably have been predicted instead of measured:
 *
 * <ul>
 *   <li><b>64.00, not 124.</b> A per-function figure of 62 B/op over {@code [-pi, pi]} would
 *       predict ~124 for two calls. In this in-situ shape C2 <em>does</em> scalar-replace the
 *       {@code double[3]} that {@code FdLibm.RemPio2.__ieee754_rem_pio2} declares, but not the
 *       {@code double[2]} that {@code FdLibm.Sin.compute} passes <em>into</em> it as an
 *       out-parameter. 64.00 is two 32-byte carriers and nothing else.</li>
 *   <li><b>Flat across four tiers.</b> The tiering that shows up in a direct-call microbenchmark
 *       (32 / 72 / 696) collapses to a flat 32 per call here for exactly the same reason, and
 *       reappears only on the multi-word path, where {@code __kernel_rem_pio2}'s
 *       {@code int[20] + 3 x double[20]} do escape: 1392.00 is two lots of 696.</li>
 *   <li><b>{@link #theStrictMathFormStillAllocates} reports ~112 B/op, not 64.</b> That loop calls
 *       {@code StrictMath} from the <em>test's</em> frame rather than from inside
 *       {@code Clenshaw6.convert}, which is a third inlining shape and gives a third number. The
 *       64.00 above is the one that describes the shipped code.</li>
 * </ul>
 *
 * <p>So the recorded lesson that an allocation measurement on one argument is a measurement of one
 * branch generalises: it is also a measurement of one <em>call shape</em>. Only the A/B of two real
 * builds settles what a change to the library actually removed.
 *
 * <p>The assertions are asymmetric on purpose. {@code 0 B/op} for {@link Clenshaw6} is pinned
 * exactly, because that is the deliverable. The {@code StrictMath} side is asserted only to be
 * <em>greater</em>, never equal to 64: a JDK that fixes {@code FdLibm} or gains the escape
 * analysis to scalarise the carrier is a good outcome, and pinning 64 would turn it into a red
 * build.
 *
 * <h2>And the {@code StrictMath} side is conditional on the JDK, because before 21 it is native</h2>
 *
 * <p>{@code StrictMath.sin/cos/tan} were <strong>JNI calls into C fdlibm through JDK 17</strong>;
 * the Java port that introduced the allocating {@code double[2]} landed in <strong>JDK 21</strong>.
 * Corretto 17.0.20 has {@code Modifier.isNative(StrictMath.sin) == true} and no
 * {@code java.lang.FdLibm$Sin} class at all in {@code lib/modules}, and measures a flat 0.00 B/op;
 * Corretto 21.0.12, Corretto 25.0.4 and Temurin 21.0.11 all measure non-zero. That is not flake,
 * not a vendor difference and not escape analysis -- the object is absent from the class library.
 *
 * <p>So {@link #theStrictMathFormStillAllocates} is guarded on nativeness and reported as a
 * <em>skip</em> where the premise cannot hold. The guard is nativeness rather than "did we measure
 * zero", because the latter is the assertion's own negation and could never fail.
 *
 * <p>What makes {@link Clenshaw6}'s {@code 0.00} meaningful on <em>every</em> JDK is not the
 * {@code StrictMath} comparison but {@link #theInstrumentCanSeeAllocation}, which requires the
 * counter to see and to scale with a known allocation. A blind instrument reports 0.00 for
 * everything, cleanly.
 */
public class Clenshaw6AllocationTest {

    private static ThreadMXBean bean;

    /** Number of calls per measured round. Large enough that a 32 B allocation is unmissable. */
    private static final int N = 2_000_000;

    /** Consumed so that nothing in the loop can be optimised away as dead. */
    private static double sink;

    /** Escape hatch for the positive control -- a static volatile cannot be scalar-replaced. */
    public static volatile Object objectSink;

    /**
     * Whether this JVM's {@code StrictMath.sin/cos/tan} are the pure-Java {@code FdLibm} port
     * (JDK 21+) rather than the JNI-into-C fdlibm of JDK 8..17. Only the former can allocate.
     */
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

    private static String strictMathImplementation() {
        return (STRICT_TRIG_IS_PURE_JAVA ? "pure Java (JDK 21+ FdLibm port)" : "native JNI fdlibm")
                + " on " + System.getProperty("java.vm.name") + " "
                + System.getProperty("java.version") + " / " + System.getProperty("os.arch");
    }

    @BeforeClass
    public static void resolveBean() {
        java.lang.management.ThreadMXBean plain = ManagementFactory.getThreadMXBean();
        Assume.assumeTrue("com.sun.management.ThreadMXBean is required to measure allocation",
                plain instanceof ThreadMXBean);
        bean = (ThreadMXBean) plain;
        Assume.assumeTrue("thread allocated memory is not supported on this JVM",
                bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);
    }

    @Test
    public void clenshaw6ConvertAllocatesNothing() {
        Clenshaw6 c = Clenshaw6.forConversion(0.0016792203863837047,
                AuxLat.GEOGRAPHIC, AuxLat.RECTIFYING);
        double perOp = 0.0;
        for (int round = 0; round < 4; round++) {
            perOp = measureConvert(c);
        }
        System.out.printf("Clenshaw6.convert(zeta), FastStrictTrig: %.2f B/op over %d calls%n",
                perOp, N);
        assertEquals("Clenshaw6.convert must not allocate; before this change it was 64.00 B/op, "
                + "two FdLibm double[2] argument-reduction carriers", 0.0, perOp, 0.0);
    }

    /**
     * The multi-word reduction path too, where {@code StrictMath} cost 1392.00 B/op — 2 x 696, the
     * {@code int[20]} plus three {@code double[20]} of {@code __kernel_rem_pio2}. Unreachable for
     * any angle a coordinate transform produces, but it is where a {@code Scratch}-less
     * implementation would have had to allocate, so it is the assertion that shows the
     * thread-local {@code Scratch} really is reused rather than re-created.
     */
    @Test
    public void theMultiWordReductionPathAllocatesNothingEither() {
        Clenshaw6 c = Clenshaw6.forConversion(0.0016792203863837047,
                AuxLat.GEOGRAPHIC, AuxLat.RECTIFYING);
        double perOp = 0.0;
        for (int round = 0; round < 4; round++) {
            perOp = measure(c, 1e10, 1e10 + 1e4);
        }
        System.out.printf("Clenshaw6.convert(zeta), |zeta| > 1647099: %.2f B/op%n", perOp);
        assertEquals("the multi-word path must reuse its Scratch; StrictMath cost 1392.00 B/op "
                + "here", 0.0, perOp, 0.0);
    }

    /**
     * The pre-change and post-change forms of the same loop, side by side. The
     * <em>post</em>-change figure is asserted, because that is proj4j's own code and the
     * deliverable; the {@code StrictMath} figure is measured and printed as an observation, since
     * what it costs is a property of the JDK. Runs on every JDK.
     */
    @Test
    public void theAbAgainstTheStrictMathFormIsMeasuredAndFastStrictTrigIsFree() {
        Clenshaw6 c = Clenshaw6.forConversion(0.0016792203863837047,
                AuxLat.GEOGRAPHIC, AuxLat.RECTIFYING);
        double fast = 0.0;
        double strict = 0.0;
        for (int round = 0; round < 4; round++) {
            fast = measureConvert(c);
            strict = measureStrictMathConvert(c);
        }
        System.out.printf("Clenshaw6.convert(zeta): FastStrictTrig %.2f B/op, StrictMath %.2f "
                + "B/op (= %.1f MB per 100k-vertex geometry); StrictMath here is %s%n",
                fast, strict, strict * 1e5 / 1e6, strictMathImplementation());
        assertEquals("Clenshaw6.convert must not allocate, whatever StrictMath happens to cost on "
                + "this JDK", 0.0, fast, 0.0);
    }

    /**
     * The comparison the 0 B/op figure is worth something against: the same loop with
     * {@code StrictMath}, which is what the pre-change source called.
     *
     * <p>Skipped, with the reason printed, on a JVM whose {@code StrictMath.sin/cos/tan} are
     * native -- every JDK up to and including 17, where there is no Java carrier to allocate and
     * so no premise to test. See the class javadoc. On JDK 21+ the assertion is live: the guard is
     * nativeness, not the measurement, so a pure-Java {@code StrictMath} that stopped allocating
     * would still be caught here.
     */
    @Test
    public void theStrictMathFormStillAllocates() {
        if (!STRICT_TRIG_IS_PURE_JAVA) {
            String why = "StrictMath allocation premise: this JVM's StrictMath.sin/cos/tan are "
                    + strictMathImplementation() + ". A native implementation allocates no Java "
                    + "object, so it necessarily measures 0 B/op. Clenshaw6.convert's own 0 B/op "
                    + "is asserted unconditionally by clenshaw6ConvertAllocatesNothing, and the "
                    + "instrument's ability to see an allocation at all by "
                    + "theInstrumentCanSeeAllocation.";
            System.out.println("[Clenshaw6] [SKIP] " + why);
            Assume.assumeTrue(why, false);
        }

        Clenshaw6 c = Clenshaw6.forConversion(0.0016792203863837047,
                AuxLat.GEOGRAPHIC, AuxLat.RECTIFYING);
        double fast = 0.0;
        double strict = 0.0;
        for (int round = 0; round < 4; round++) {
            fast = measureConvert(c);
            strict = measureStrictMathConvert(c);
        }
        assertTrue("StrictMath.sin/cos no longer allocate on this JDK (" + strict + " B/op) even "
                + "though its implementation is " + strictMathImplementation() + ", so "
                + "FastStrictTrig buys nothing here and the re-point can be reconsidered -- but "
                + "note it still buys determinism, which is a separate argument", strict > fast);
    }

    /**
     * <strong>The positive control.</strong> An allocation counter that reported zero for
     * everything would make every {@code 0.00 B/op} in this class clean, plausible and worthless.
     * This runs two loops whose allocation is known by construction -- one {@code double[2]} per
     * call and four -- through {@link #measureRaw}, the same read-loop-read shape the real
     * measurements use, and requires the counter to see them and to scale with them.
     *
     * <p>This, not "{@code StrictMath} must allocate", is what makes the 0.00 non-vacuous. The old
     * premise was a claim about a third party's implementation and is false on every JDK before 21;
     * this is a claim about the instrument, holds on every JDK, and is the thing that actually
     * needed proving.
     *
     * <p>The bounds are loose on purpose -- 32 B is a 16-byte array header plus two doubles, but
     * header layout is a VM detail, so the floor is 16 B/op and the scaling requirement is 3x
     * rather than exactly 4x.
     */
    @Test
    public void theInstrumentCanSeeAllocation() {
        double one = 0.0;
        double four = 0.0;
        for (int round = 0; round < 2; round++) {
            one = measureRaw(1);
            four = measureRaw(4);
        }
        System.out.printf("Clenshaw6 control: one double[2]/op = %.2f B/op, four = %.2f B/op%n",
                one, four);
        assertTrue("the allocation counter cannot see a double[2] allocated on every one of " + N
                + " iterations -- it measured " + one + " B/op, so the 0.00 B/op this class "
                + "reports elsewhere would be an artefact of a blind instrument", one >= 16.0);
        assertTrue("the allocation counter is not quantitative: four arrays per op measured "
                + four + " B/op against " + one + " for one", four >= 3.0 * one);
    }

    /** Allocates {@code arrays} escaping {@code double[2]} per iteration and measures the cost. */
    private static double measureRaw(int arrays) {
        long tid = Thread.currentThread().getId();
        long before = bean.getThreadAllocatedBytes(tid);
        double acc = 0.0;
        for (int i = 0; i < N; i++) {
            for (int k = 0; k < arrays; k++) {
                double[] carrier = new double[2];
                carrier[0] = i + k;
                objectSink = carrier;
                acc += carrier[0];
            }
        }
        long after = bean.getThreadAllocatedBytes(tid);
        sink += acc;
        return (after - before) / (double) N;
    }

    /** Steady-state B/op of {@code convert(zeta)} as this tree implements it. */
    private static double measureConvert(Clenshaw6 c) {
        long tid = Thread.currentThread().getId();
        long before = bean.getThreadAllocatedBytes(tid);
        double acc = 0.0;
        for (int i = 0; i < N; i++) {
            acc += c.convert(zeta(i));
        }
        long after = bean.getThreadAllocatedBytes(tid);
        sink += acc;
        return (after - before) / (double) N;
    }

    /** As {@link #measureConvert}, over an explicit argument range. */
    private static double measure(Clenshaw6 c, double lo, double hi) {
        long tid = Thread.currentThread().getId();
        double span = hi - lo;
        long before = bean.getThreadAllocatedBytes(tid);
        double acc = 0.0;
        for (int i = 0; i < N; i++) {
            acc += c.convert(lo + span * ((i % 1000003) / 1000003.0));
        }
        long after = bean.getThreadAllocatedBytes(tid);
        sink += acc;
        return (after - before) / (double) N;
    }

    /**
     * The pre-change body, verbatim: {@code convert(zeta, StrictMath.sin(zeta),
     * StrictMath.cos(zeta))}. Both loops do identical arithmetic, so the difference between them
     * is the allocation and nothing else.
     */
    private static double measureStrictMathConvert(Clenshaw6 c) {
        long tid = Thread.currentThread().getId();
        long before = bean.getThreadAllocatedBytes(tid);
        double acc = 0.0;
        for (int i = 0; i < N; i++) {
            double z = zeta(i);
            acc += c.convert(z, StrictMath.sin(z), StrictMath.cos(z));
        }
        long after = bean.getThreadAllocatedBytes(tid);
        sink += acc;
        return (after - before) / (double) N;
    }

    /**
     * Uniform on {@code [-pi, pi]}, so three quarters of the calls take fdlibm's argument
     * reduction. Confining the sweep to {@code |x| <= pi/4} would measure the cheapest tier and
     * report 32 B/op for a workload that really costs more.
     */
    private static double zeta(int i) {
        return (i % 3141593) * 2e-6 - Math.PI;
    }
}

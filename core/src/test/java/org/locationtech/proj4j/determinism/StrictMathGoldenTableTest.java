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

package org.locationtech.proj4j.determinism;

import org.junit.BeforeClass;
import org.junit.Test;
import org.locationtech.proj4j.util.FastStrictTrig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Turns proj4j's determinism guarantee from an assertion into a check that can fail.
 *
 * <p>The guarantee, as stated in the {@code proj4j-upgrade} notes: every transcendental on the
 * transform path goes through {@code StrictMath} (or {@link FastStrictTrig}, its allocation-free
 * transcription), so a coordinate transform produces <b>the same bits on every JVM and every
 * architecture</b>. A downstream Spark consumer depends on that across executors. It also decides a
 * conformance bar rather than only reproducibility: at
 * {@code +proj=adams_ws2 +ellps=WGS84} and {@code (179.999, 0)} the map's conditioning amplifies a
 * last-bit difference in {@code sin} by roughly {@code 3e8}, so {@code Math.sin} misses
 * {@code adams_ws2.gie:2139} by 27.8 mm against a 1 mm tolerance while {@code StrictMath.sin} hits
 * it at 0.35 mm. <b>Neither is more accurate</b> - the exact value sits between them - so what is
 * being preserved is <em>fidelity</em> to the fdlibm-equivalent {@code sin} that generated PROJ's
 * expected values, not accuracy.
 *
 * <p>Until this test existed the guarantee was only ever asserted. What makes it checkable is that
 * {@code StrictMath} is <em>specified</em> to a bit rather than to an ulp, so a table of expected raw
 * bit patterns is a legitimate thing to commit; {@code Math} is specified only to 1-2 ulp with
 * {@code @IntrinsicCandidate} substitution, so the same table would be meaningless for it. That
 * asymmetry is the entire mechanism.
 *
 * <h2>What each test does, and where the non-vacuity comes from</h2>
 *
 * <ol>
 *   <li><b>Every row matches the golden, bitwise.</b> On
 *       {@link Double#doubleToRawLongBits(double)}, so {@code +0.0} and {@code -0.0} are
 *       distinguished and NaN payloads are compared rather than collapsed. A failure names the
 *       function and prints the exact argument.</li>
 *   <li><b>{@link FastStrictTrig} matches the same golden</b> for {@code sin}/{@code cos}/{@code
 *       tan}. It is a transcription of the JDK's {@code FdLibm} kernels with the argument-reduction
 *       array removed, and the reason it exists is that {@code StrictMath.sin} allocates ~62 B per
 *       call on JDK 21 and later - on 8..17 it is native JNI and allocates nothing, so the figure
 *       there is a structural 0. Holding it to the same table as {@code StrictMath} is what makes
 *       "allocation-free" not quietly cost fidelity - and it is also <b>the reason this test is not
 *       vacuous</b>: an 800-line independent implementation agreeing with the table on 44,394
 *       raw-bit comparisons cannot happen if the table is garbage.</li>
 *   <li><b>The table cannot be misaligned or truncated.</b> Each section declares its row count
 *       <em>and a digest of its argument bits</em>, both asserted against the probe generator. The
 *       digest is not belt-and-braces: building the probes with {@code Math.toRadians} made the
 *       argument list differ on Java 8, which presented as a 64-ulp {@code StrictMath.sin}
 *       divergence and would have been reported as one.</li>
 *   <li><b>The {@code Math} divergence measurement</b> records how far the non-strict functions
 *       depart from the golden and writes it for CI. It deliberately asserts nothing about the
 *       divergence, because on Temurin 11/AArch64 there is none at all - see
 *       {@link #measureMathDivergenceFromTheGolden()}, which explains why the obvious in-leg
 *       assertion is wrong and where the assertion belongs instead.</li>
 * </ol>
 *
 * <h2>What this test does not prove</h2>
 *
 * <ul>
 *   <li><b>Not bit parity with PROJ.</b> {@code StrictMath} is fdlibm; PROJ links glibc or Apple
 *       libm. Bit parity with C is not achievable and is not the goal - 1 ulp of a radian is about
 *       2 pm, eight orders inside the tightest gie bar. Fidelity to fdlibm is the goal because
 *       fdlibm is what generated the corpus.</li>
 *   <li><b>Not a claim about {@code Math}.</b> Its results are allowed to differ, and on JDK 21 and
 *       26 they do; on JDK 11 they do not differ anywhere in this probe set. The separate rule that
 *       nothing on the transform path may call it is enforced by the benchmark module's
 *       {@code CountingMath} tier, not here.</li>
 *   <li><b>Not, on its own, a cross-architecture result.</b> A committed table pins the answer
 *       across time on whatever runner executed it. The cross-architecture claim needs the matrix in
 *       {@code .github/workflows/determinism.yaml}, which runs this same class on
 *       {@code ubuntu-latest} and {@code ubuntu-24.04-arm}.</li>
 * </ul>
 */
public class StrictMathGoldenTableTest {

    /** Golden results by function name, in probe order. */
    private static Map<String, long[]> golden;

    /** The committed digest of each function's argument bits. */
    private static Map<String, Long> argDigest;

    /** Total data rows loaded, for the "nothing was measured" guard. */
    private static int goldenRows;

    @BeforeClass
    public static void loadGolden() throws IOException {
        golden = new LinkedHashMap<String, long[]>();
        argDigest = new LinkedHashMap<String, Long>();
        goldenRows = 0;
        InputStream in = StrictMathGoldenTableTest.class.getClassLoader()
                .getResourceAsStream(GenerateStrictMathGoldenTable.RESOURCE);
        assertNotNull("golden table missing from the classpath at "
                + GenerateStrictMathGoldenTable.RESOURCE
                + " - regenerate it with GenerateStrictMathGoldenTable, and read that class's"
                + " javadoc before doing so", in);
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            String line;
            String fn = null;
            long[] buf = null;
            int at = 0;
            while ((line = r.readLine()) != null) {
                if (line.length() == 0 || line.charAt(0) == '#') {
                    continue;
                }
                if (line.charAt(0) == '@') {
                    if (fn != null && at != buf.length) {
                        fail("golden section @" + fn + " declares " + buf.length
                                + " results but only " + at + " were present before @-line: " + line);
                    }
                    String[] head = line.substring(1).trim().split("\\s+");
                    if (head.length != 3) {
                        fail("malformed golden section header, want '@fn count argdigest': " + line);
                    }
                    fn = head[0];
                    buf = new long[Integer.parseInt(head[1])];
                    argDigest.put(fn, Long.valueOf(new BigInteger(head[2], 16).longValue()));
                    at = 0;
                    if (golden.put(fn, buf) != null) {
                        fail("golden table declares @" + fn + " twice");
                    }
                    continue;
                }
                if (buf == null) {
                    fail("golden data line before any @-section: " + line);
                }
                if (at == buf.length) {
                    fail("golden section @" + fn + " has more results than its declared "
                            + buf.length);
                }
                // Long.parseLong rejects anything with the sign bit set, which is half of all
                // doubles - hence BigInteger rather than the obvious call.
                buf[at++] = new BigInteger(line.trim(), 16).longValue();
                goldenRows++;
            }
            if (fn != null && at != buf.length) {
                fail("golden section @" + fn + " declares " + buf.length + " results but the file"
                        + " ended after " + at + " - it is truncated");
            }
        } finally {
            r.close();
        }
        // A truncated or empty table must not read as a pass. Same guard the benchmark module's
        // recordAll() applies: refuse to report a result when nothing was measured.
        assertTrue("the golden table carries only " + goldenRows + " results, which is far too few"
                + " to be the real table - it was probably truncated", goldenRows > 30000);
    }

    // ------------------------------------------------------------------

    /**
     * Prints what the JVM under test actually is, including whether {@code StrictMath.sin} is a JNI
     * call into compiled fdlibm or the pure-Java {@code FdLibm} port.
     *
     * <p>Not decoration. <b>JDK 21</b> rewrote {@code StrictMath.sin/cos/tan} from JNI-into-fdlibm
     * to pure Java, so a pass on 21 says nothing on its own about 8, 11 or 17 - those exercise a
     * different implementation, in a different language, reached through a different mechanism.
     * Recording which one ran is what lets a green run be cited as evidence about a particular JDK
     * rather than about JDKs in general.
     *
     * <p>Measured with exactly this method: {@code sin}/{@code cos}/{@code tan} are
     * <b>native</b> on Temurin 8 ({@code os.arch=x86_64}), Temurin 11 ({@code os.arch=aarch64})
     * <b>and Temurin 17.0.20</b>, and <b>pure Java</b> on Temurin 21 and OpenJDK 26. Corroborated
     * by {@code jimage}: {@code java.lang.FdLibm$Sin} does not exist in a JDK 17 image, which ships
     * five {@code FdLibm} nested classes against 21's twenty-three.
     *
     * <p>Worth noting that the migration was not all-at-once, and the boundary is
     * <b>per function</b>: on 11, {@code pow} and {@code exp} are already pure Java while
     * {@code sin}, {@code cos}, {@code tan} and {@code log} are still native; on 17, {@code log} is
     * <em>still</em> native alongside the three trig functions. So there is no single "the JDK that
     * made {@code StrictMath} pure Java" - the trig boundary is 21 - which is the sort of detail
     * that makes inferring one JDK's behaviour, or one function's, from another's a bad idea.
     */
    @Test
    public void reportTheImplementationUnderTest() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("determinism: java.version=").append(System.getProperty("java.version"))
                .append(" vendor=").append(System.getProperty("java.vm.vendor"))
                .append(" os.arch=").append(System.getProperty("os.arch"))
                .append(" os.name=").append(System.getProperty("os.name"))
                .append('\n');
        String[] fns = {"sin", "cos", "tan", "pow", "exp", "log"};
        for (int i = 0; i < fns.length; i++) {
            Class<?>[] sig = "pow".equals(fns[i])
                    ? new Class<?>[]{double.class, double.class}
                    : new Class<?>[]{double.class};
            Method m = StrictMath.class.getDeclaredMethod(fns[i], sig);
            sb.append("  StrictMath.").append(fns[i]).append(": ")
                    .append(Modifier.isNative(m.getModifiers()) ? "native (JNI fdlibm)" : "pure Java")
                    .append('\n');
        }
        System.out.print(sb);
        // Not asserted either way: both implementations are legitimate and the golden covers both.
        // The printed output is the deliverable.
    }

    /**
     * Every function's declared row count matches what the probe generator produces.
     *
     * <p>Run before the value assertions in reading order, because a count mismatch explains a
     * value mismatch and not the other way round. If the probe set is edited without regenerating
     * the table, this is the failure that should be believed.
     */
    @Test
    public void everySectionMatchesTheProbeSetSoTheTableCannotBeMisaligned() {
        List<String> problems = new ArrayList<String>();
        for (int f = 0; f < TranscendentalProbes.UNARY.length; f++) {
            String fn = TranscendentalProbes.UNARY[f];
            long[] g = golden.get(fn);
            int want = TranscendentalProbes.unaryProbes(fn).length;
            if (g == null) {
                problems.add("@" + fn + " missing from the golden table");
            } else if (g.length != want) {
                problems.add("@" + fn + " has " + g.length + " results but the probe set now"
                        + " generates " + want + " arguments");
            } else {
                checkDigest(problems, fn, TranscendentalProbes.unaryProbes(fn));
            }
        }
        double[] allPairs = TranscendentalProbes.binaryProbes();
        int pairs = allPairs.length / 2;
        for (int f = 0; f < TranscendentalProbes.BINARY.length; f++) {
            String fn = TranscendentalProbes.BINARY[f];
            long[] g = golden.get(fn);
            if (g == null) {
                problems.add("@" + fn + " missing from the golden table");
            } else if (g.length != pairs) {
                problems.add("@" + fn + " has " + g.length + " results but the probe set now"
                        + " generates " + pairs + " argument pairs");
            } else {
                checkDigest(problems, fn, allPairs);
            }
        }
        int expectedSections = TranscendentalProbes.UNARY.length + TranscendentalProbes.BINARY.length;
        if (golden.size() != expectedSections) {
            problems.add("the golden table has " + golden.size() + " sections, expected "
                    + expectedSections + " - extra sections: " + golden.keySet());
        }
        if (!problems.isEmpty()) {
            fail("the committed golden table no longer describes the probe set. Regenerate it with"
                    + " GenerateStrictMathGoldenTable - and read that class's javadoc, because a"
                    + " regenerated table is a new claim that has to be re-verified on a native-"
                    + "StrictMath JDK and on both architectures. Problems:\n  "
                    + join(problems, "\n  "));
        }
        System.out.println("determinism: golden table has " + goldenRows + " results across "
                + golden.size() + " functions; every section's row count AND argument digest match"
                + " the probe set");
    }

    /**
     * Compares a probe list's argument digest against the committed one.
     *
     * <p>The distinct failure text matters. A count mismatch means somebody edited the probe set and
     * forgot to regenerate; a <em>digest</em> mismatch with a matching count means the same code
     * produced different arguments on this JVM, which is a far more interesting fact and points at a
     * platform-dependent expression in the generator rather than at an oversight.
     *
     * @param problems accumulator
     * @param fn       the function name
     * @param xs       the regenerated arguments
     */
    private static void checkDigest(List<String> problems, String fn, double[] xs) {
        long want = TranscendentalProbes.digest(xs);
        Long have = argDigest.get(fn);
        if (have == null) {
            problems.add("@" + fn + " has no committed argument digest");
        } else if (have.longValue() != want) {
            problems.add(String.format(
                    "@%s argument digest is 0x%016x in the table but this JVM generates 0x%016x"
                            + " from the same code. The row COUNT matches, so this is not a"
                            + " forgotten regeneration - some expression in TranscendentalProbes is"
                            + " platform-dependent. Math.toRadians was exactly this (1 ulp"
                            + " difference between Java 8 and 9+ on 25%% of whole degrees); check"
                            + " for anything similar before touching the golden.",
                    fn, have, Long.valueOf(want)));
        }
    }

    /**
     * Every {@code StrictMath} result matches the committed golden, bit for bit.
     */
    @Test
    public void strictMathIsBitIdenticalToTheGolden() {
        int checked = 0;
        int nanPayloadDiffs = 0;
        for (int f = 0; f < TranscendentalProbes.UNARY.length; f++) {
            String fn = TranscendentalProbes.UNARY[f];
            long[] g = golden.get(fn);
            double[] xs = TranscendentalProbes.unaryProbes(fn);
            for (int i = 0; i < xs.length && i < g.length; i++) {
                long got = Double.doubleToRawLongBits(
                        TranscendentalProbes.strictUnary(fn, xs[i]));
                if (got != g[i]) {
                    if (bothNaN(g[i], got)) {
                        nanPayloadDiffs++;
                    } else {
                        fail(describeUnary("StrictMath", fn, i, xs[i], g[i], got));
                    }
                }
                checked++;
            }
        }
        double[] pairs = TranscendentalProbes.binaryProbes();
        for (int f = 0; f < TranscendentalProbes.BINARY.length; f++) {
            String fn = TranscendentalProbes.BINARY[f];
            long[] g = golden.get(fn);
            for (int i = 0; i + 1 < pairs.length && i / 2 < g.length; i += 2) {
                long got = Double.doubleToRawLongBits(
                        TranscendentalProbes.strictBinary(fn, pairs[i], pairs[i + 1]));
                if (got != g[i / 2]) {
                    if (bothNaN(g[i / 2], got)) {
                        nanPayloadDiffs++;
                    } else {
                        fail(describeBinary("StrictMath", fn, i / 2, pairs[i], pairs[i + 1],
                                g[i / 2], got));
                    }
                }
                checked++;
            }
        }
        assertEquals("every golden result must have been evaluated", goldenRows, checked);
        System.out.println("determinism: " + checked
                + " StrictMath comparisons against the committed golden, zero value mismatches; "
                + nanPayloadDiffs + " rows differed in NaN payload only (see"
                + " NanBitPatternTest - that is architecture-dependent and outside the guarantee)");
    }

    /**
     * Whether two raw bit patterns are both NaN, and therefore differ only in payload.
     *
     * <h4>The one place a raw-bit comparison has to be relaxed, and why it is not a loophole</h4>
     *
     * <p>Java specifies that these functions <em>return NaN</em> for certain inputs. It does not
     * specify <em>which</em> NaN. Measured on Temurin 11.0.32, holding the JDK fixed and varying only
     * the instruction set: {@code Inf - Inf}, {@code Inf * 0.0}, {@code Inf / Inf} and
     * {@code sqrt(-1)} all produce {@code 0xfff8000000000000} on x86-64 and
     * {@code 0x7ff8000000000000} on AArch64 - the hardware's default NaN, whose sign bit differs
     * between the two. fdlibm computes {@code sin(Inf)} as {@code x - x}, so it inherits that
     * difference, and so does every faithful transcription of it including
     * {@link FastStrictTrig}. See {@link NanBitPatternTest} for the full table.
     *
     * <p>The relaxation is deliberately as narrow as it can be: both sides must be NaN, the count is
     * reported rather than hidden, and any pair where one side is a number still fails. So a
     * regression that turned a finite result into NaN - which is exactly the fail-closed sentinel and
     * therefore a real risk - is caught, while an unspecifiable payload difference is not reported as
     * a determinism defect it is not.
     *
     * @param a one raw bit pattern
     * @param b the other
     * @return true if both are NaN
     */
    private static boolean bothNaN(long a, long b) {
        return Double.isNaN(Double.longBitsToDouble(a)) && Double.isNaN(Double.longBitsToDouble(b));
    }

    /**
     * {@link FastStrictTrig} matches the same golden for the three functions it implements.
     *
     * <p>Both entry points are checked: the allocation-free {@code Scratch} overload as well as the
     * plain one. They share a kernel, but the whole reason the overload exists is to let a caller
     * hoist the carrier array out of a loop, and a divergence between the two would be exactly the
     * kind of defect that only shows up under reuse.
     */
    @Test
    public void fastStrictTrigIsBitIdenticalToTheGolden() {
        FastStrictTrig.Scratch scratch = new FastStrictTrig.Scratch();
        int checked = 0;
        String[] trig = {"sin", "cos", "tan"};
        for (int f = 0; f < trig.length; f++) {
            String fn = trig[f];
            long[] g = golden.get(fn);
            double[] xs = TranscendentalProbes.unaryProbes(fn);
            for (int i = 0; i < xs.length && i < g.length; i++) {
                double plain;
                double reused;
                if ("sin".equals(fn)) {
                    plain = FastStrictTrig.sin(xs[i]);
                    reused = FastStrictTrig.sin(xs[i], scratch);
                } else if ("cos".equals(fn)) {
                    plain = FastStrictTrig.cos(xs[i]);
                    reused = FastStrictTrig.cos(xs[i], scratch);
                } else {
                    plain = FastStrictTrig.tan(xs[i]);
                    reused = FastStrictTrig.tan(xs[i], scratch);
                }
                long gotPlain = Double.doubleToRawLongBits(plain);
                long gotReused = Double.doubleToRawLongBits(reused);
                if (gotPlain != g[i] && !bothNaN(g[i], gotPlain)) {
                    fail(describeUnary("FastStrictTrig", fn, i, xs[i], g[i], gotPlain));
                }
                if (gotReused != g[i] && !bothNaN(g[i], gotReused)) {
                    fail(describeUnary("FastStrictTrig(+Scratch)", fn, i, xs[i], g[i], gotReused));
                }
                // The two overloads must agree with EACH OTHER exactly, NaN payload included: they
                // run on the same hardware in the same JVM, so there is no architecture excuse here
                // and a payload difference between them would be a real defect in the Scratch path.
                assertEquals("FastStrictTrig." + fn + " and its Scratch overload disagree at probe "
                                + i + " (arg 0x" + Long.toHexString(
                                Double.doubleToRawLongBits(xs[i])) + ")",
                        gotPlain, gotReused);
                checked += 2;
            }
        }
        // If the trig sections ever vanish from the golden this loop would silently become a no-op.
        // Assert a floor rather than trusting that it found work.
        assertTrue("expected a substantial sin/cos/tan population in the golden, compared only "
                + checked, checked > 20000);
        System.out.println("determinism: " + checked
                + " FastStrictTrig raw-bit comparisons against the same golden, zero mismatches");
    }

    /**
     * The negative control: measures how far {@code Math} diverges from the golden, and writes the
     * tally where CI can assert on it across legs.
     *
     * <h4>Why this reports rather than asserts, which is a correction to the obvious design</h4>
     *
     * <p>The first version failed the build when {@code Math} and {@code StrictMath} agreed too
     * closely, on the reasoning that a golden they both satisfy cannot distinguish them and so proves
     * nothing. That reasoning is sound about the <em>matrix</em> and wrong about a single JVM, and
     * running it on four JDKs is what showed why. Measured over all 54,627 probes:
     *
     * <table>
     *   <caption>rows where {@code Math} differs from the {@code StrictMath} golden</caption>
     *   <tr><th>JDK / arch</th><th>divergent functions</th></tr>
     *   <tr><td>Temurin 11, AArch64</td>
     *       <td><b>none at all</b> - 0 of 54,627, every function</td></tr>
     *   <tr><td>Temurin 21, AArch64</td>
     *       <td>{@code sin} 158 (2.14%), {@code cos} 140 (1.89%); everything else 0</td></tr>
     *   <tr><td>OpenJDK 26, AArch64</td>
     *       <td>identical to 21</td></tr>
     * </table>
     *
     * <p>So on Temurin 11/AArch64 there is <em>no</em> threshold this method could assert without
     * failing, and the failure would be reporting a non-defect: {@code Math} being bit-identical to
     * {@code StrictMath} on some platform is a perfectly legitimate state of the world, and one the
     * policy has to tolerate because the policy's job is to be correct on the platforms where they
     * <em>do</em> differ. An in-leg assertion here would have shipped a permanently red JDK 11 leg,
     * and a permanently red gate is a disabled gate.
     *
     * <p>Non-vacuity therefore has to be established two other ways, and both are stronger:
     *
     * <ol>
     *   <li><b>Across the matrix, not within a leg.</b> The tally is written to
     *       {@code target/determinism/math-divergence.tsv} and
     *       {@code .github/workflows/determinism.yaml} asserts that at least one leg diverges. That
     *       is the assertion the original design wanted, placed where it is actually true.</li>
     *   <li><b>{@link FastStrictTrig} is an independent implementation.</b> An 800-line hand
     *       transcription agreeing with the same table on 44,394 raw-bit comparisons cannot happen if
     *       the table is garbage. This holds in every leg and does not depend on {@code Math} at
     *       all, which makes it the load-bearing non-vacuity argument rather than a backup.</li>
     * </ol>
     *
     * <p>One genuinely surprising row is worth keeping: {@code sin} and {@code cos} diverge on 21 and
     * 26 but not on 11, on the same hardware. HotSpot's {@code sin}/{@code cos} intrinsics changed
     * between those releases. That is precisely the "at the margin, across JIT tiers and releases"
     * variation the policy exists to be immune to - observed, not assumed.
     *
     * @throws IOException if the tally cannot be written
     */
    @Test
    public void measureMathDivergenceFromTheGolden() throws IOException {
        Map<String, int[]> tally = new LinkedHashMap<String, int[]>();  // fn -> {differing, total}
        for (int f = 0; f < TranscendentalProbes.UNARY.length; f++) {
            String fn = TranscendentalProbes.UNARY[f];
            long[] g = golden.get(fn);
            double[] xs = TranscendentalProbes.unaryProbes(fn);
            int[] t = new int[2];
            tally.put(fn, t);
            for (int i = 0; i < xs.length && i < g.length; i++) {
                t[1]++;
                if (Double.doubleToRawLongBits(TranscendentalProbes.mathUnary(fn, xs[i])) != g[i]) {
                    t[0]++;
                }
            }
        }
        double[] pairs = TranscendentalProbes.binaryProbes();
        for (int f = 0; f < TranscendentalProbes.BINARY.length; f++) {
            String fn = TranscendentalProbes.BINARY[f];
            long[] g = golden.get(fn);
            int[] t = new int[2];
            tally.put(fn, t);
            for (int i = 0; i + 1 < pairs.length && i / 2 < g.length; i += 2) {
                t[1]++;
                if (Double.doubleToRawLongBits(
                        TranscendentalProbes.mathBinary(fn, pairs[i], pairs[i + 1])) != g[i / 2]) {
                    t[0]++;
                }
            }
        }

        int totalDiff = 0;
        int totalRows = 0;
        StringBuilder human = new StringBuilder(
                "determinism: Math vs the StrictMath golden on this JVM:\n");
        StringBuilder tsv = new StringBuilder();
        tsv.append("# Math-vs-StrictMath divergence. Written per CI leg; the cross-leg job asserts\n");
        tsv.append("# that at least one leg diverges, which is the non-vacuity check that cannot be\n");
        tsv.append("# made inside a single leg - see measureMathDivergenceFromTheGolden's javadoc.\n");
        tsv.append("javaVersion\t").append(System.getProperty("java.version")).append('\n');
        tsv.append("osArch\t").append(System.getProperty("os.arch")).append('\n');
        tsv.append("vmVendor\t").append(System.getProperty("java.vm.vendor")).append('\n');
        tsv.append("fn\tdiffering\ttotal\tintrinsicExpected\n");
        for (Map.Entry<String, int[]> e : tally.entrySet()) {
            int[] t = e.getValue();
            totalDiff += t[0];
            totalRows += t[1];
            double pct = t[1] == 0 ? 0.0 : 100.0 * t[0] / t[1];
            boolean intrinsified = isIntrinsified(e.getKey());
            human.append(String.format("  %-6s %7d / %7d differ  (%6.2f%%)  %s%n",
                    e.getKey(), Integer.valueOf(t[0]), Integer.valueOf(t[1]),
                    Double.valueOf(pct), intrinsified ? "intrinsic expected" : "delegates today"));
            tsv.append(e.getKey()).append('\t').append(t[0]).append('\t').append(t[1])
                    .append('\t').append(intrinsified).append('\n');
        }
        human.append(String.format("  TOTAL  %7d / %7d differ  (%6.4f%%)%n",
                Integer.valueOf(totalDiff), Integer.valueOf(totalRows),
                Double.valueOf(totalRows == 0 ? 0.0 : 100.0 * totalDiff / totalRows)));
        tsv.append("TOTAL\t").append(totalDiff).append('\t').append(totalRows).append("\t-\n");
        System.out.print(human);

        File dir = new File(System.getProperty("proj4j.determinism.outDir", "target/determinism"));
        if (dir.isDirectory() || dir.mkdirs()) {
            Writer w = new OutputStreamWriter(
                    new FileOutputStream(new File(dir, "math-divergence.tsv")), "UTF-8");
            try {
                w.write(tsv.toString());
            } finally {
                w.close();
            }
        } else {
            // Not a failure. The measurement is in the log either way, and a test that cannot write
            // to the build directory is a build-layout problem, not a determinism finding.
            System.out.println("determinism: could not create " + dir
                    + "; the tally above is still the record");
        }

        // The only in-leg assertion, and it is about this method's own coverage rather than about
        // Math: every golden result must have been compared against something.
        assertEquals("the divergence measurement must cover every golden result",
                goldenRows, totalRows);
    }

    /**
     * The one case in the corpus where the {@code Math}-versus-{@code StrictMath} choice decides a
     * conformance verdict, pinned at the level of the {@code sin} call rather than the projection.
     *
     * <h4>Why pin it here and not only in the adams tests</h4>
     *
     * <p>Because this file is where the policy is defended and the projection tests are where it is
     * consumed. Pinned here, the value stays checkable while {@code proj/**} is being edited, and
     * the failure message can explain the amplification rather than just report a moved coordinate.
     */
    @Test
    public void theAdamsWs2WitnessIsPinnedToStrictMath() {
        double lamOverTwo = StrictMath.toRadians(179.999) / 2.0;
        double strict = StrictMath.sin(lamOverTwo);
        double fast = FastStrictTrig.sin(lamOverTwo);
        double loose = Math.sin(lamOverTwo);

        assertEquals("FastStrictTrig must agree with StrictMath at the adams_ws2 witness point,"
                        + " because this single value is a large part of why the class exists",
                Double.doubleToRawLongBits(strict), Double.doubleToRawLongBits(fast));

        long sBits = Double.doubleToRawLongBits(strict);
        long mBits = Double.doubleToRawLongBits(loose);
        System.out.printf(
                "determinism: adams_ws2 witness sin(%s)%n"
                        + "  StrictMath      0x%016x  %s%n"
                        + "  FastStrictTrig  0x%016x  (asserted identical)%n"
                        + "  Math            0x%016x  %s  (%d ulp away)%n",
                Double.toString(lamOverTwo),
                Long.valueOf(sBits), Double.toString(strict),
                Long.valueOf(Double.doubleToRawLongBits(fast)),
                Long.valueOf(mBits), Double.toString(loose),
                Long.valueOf(Math.abs(sBits - mBits)));

        // Deliberately NOT asserted as "Math differs here". Whether HotSpot's sin intrinsic differs
        // from fdlibm at this exact argument is a property of the JIT and the architecture - it is
        // precisely the thing that is allowed to vary, and on some platform the two may coincide at
        // this point while differing elsewhere. Asserting a difference would red the build on a
        // platform where the policy simply is not needed at that one argument, which is not a
        // defect. The population-level guarantee is the negative control's job; this method's job is
        // to pin FastStrictTrig to StrictMath at the argument the corpus made load-bearing, and to
        // print the divergence so a change is visible in the log.
        assertTrue("sanity: the witness argument must reach the reduction path, not the"
                + " |x| < pi/4 short circuit", Math.abs(lamOverTwo) > Math.PI / 4.0);
    }

    // ------------------------------------------------------------------

    /**
     * Whether HotSpot substitutes a platform-specific implementation for {@code Math.<fn>} on
     * mainstream architectures, and therefore whether {@code Math} is expected to diverge.
     *
     * @param fn the function name
     * @return true if an intrinsic exists on x86-64 or AArch64 today
     */
    private static boolean isIntrinsified(String fn) {
        // Per numerics.md's Math-vs-StrictMath table. tanh is excluded although it gained an
        // intrinsic in JDK 21: requiring divergence for it would fail this test on 8, 11 and 17 for
        // a reason that is not a defect.
        return "sin".equals(fn) || "cos".equals(fn) || "tan".equals(fn)
                || "log".equals(fn) || "log10".equals(fn) || "exp".equals(fn) || "pow".equals(fn);
    }

    private static String describeUnary(String who, String fn, int i, double x,
                                        long expected, long got) {
        return String.format(
                "%s.%s(%s) at probe index %d [arg 0x%016x]: golden 0x%016x (%s), got 0x%016x (%s)",
                who, fn, Double.toString(x), Integer.valueOf(i),
                Long.valueOf(Double.doubleToRawLongBits(x)),
                Long.valueOf(expected), Double.toString(Double.longBitsToDouble(expected)),
                Long.valueOf(got), Double.toString(Double.longBitsToDouble(got)));
    }

    private static String describeBinary(String who, String fn, int i, double x, double y,
                                         long expected, long got) {
        return String.format(
                "%s.%s(%s, %s) at pair index %d [args 0x%016x, 0x%016x]:"
                        + " golden 0x%016x (%s), got 0x%016x (%s)",
                who, fn, Double.toString(x), Double.toString(y), Integer.valueOf(i),
                Long.valueOf(Double.doubleToRawLongBits(x)),
                Long.valueOf(Double.doubleToRawLongBits(y)),
                Long.valueOf(expected), Double.toString(Double.longBitsToDouble(expected)),
                Long.valueOf(got), Double.toString(Double.longBitsToDouble(got)));
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}

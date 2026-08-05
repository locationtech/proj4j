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

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

/**
 * Records the one measured exception to proj4j's bit-for-bit determinism guarantee: <b>the sign and
 * payload of an arithmetically-produced NaN are architecture-dependent.</b>
 *
 * <h2>The measurement</h2>
 *
 * <p>Taken with the JDK held fixed at Temurin 11.0.32 and only the instruction set varied, so the
 * result is attributable to the architecture and not to a JDK difference:
 *
 * <table>
 *   <caption>raw bits of the result, Temurin 11.0.32</caption>
 *   <tr><th>expression</th><th>x86-64</th><th>AArch64</th></tr>
 *   <tr><td>{@code Inf - Inf}</td><td>{@code 0xfff8000000000000}</td><td>{@code 0x7ff8000000000000}</td></tr>
 *   <tr><td>{@code Inf * 0.0}</td><td>{@code 0xfff8000000000000}</td><td>{@code 0x7ff8000000000000}</td></tr>
 *   <tr><td>{@code Inf / Inf}</td><td>{@code 0xfff8000000000000}</td><td>{@code 0x7ff8000000000000}</td></tr>
 *   <tr><td>{@code Math.sqrt(-1.0)}</td><td>{@code 0xfff8000000000000}</td><td>{@code 0x7ff8000000000000}</td></tr>
 *   <tr><td>{@code StrictMath.sin(Inf)}</td><td>{@code 0xfff8000000000000}</td><td>{@code 0x7ff8000000000000}</td></tr>
 *   <tr><td>{@code StrictMath.asin(2.0)}</td><td>{@code 0xfff8000000000000}</td><td>{@code 0x7ff8000000000000}</td></tr>
 *   <tr><td>{@code 0.0 / 0.0}</td><td>{@code 0x7ff8000000000000}</td><td>{@code 0x7ff8000000000000}</td></tr>
 *   <tr><td>{@code Double.NaN} (the constant)</td><td>{@code 0x7ff8000000000000}</td><td>{@code 0x7ff8000000000000}</td></tr>
 * </table>
 *
 * <p>x86-64's default NaN - Intel calls it the "real indefinite" - has the sign bit set; AArch64's
 * does not. The JLS specifies only that these expressions yield <em>a</em> NaN. And note the two rows
 * that agree: {@code 0.0/0.0} is constant-folded by javac into the canonical
 * {@code Double.NaN}, so the divergence is not even uniform within one architecture, which is a good
 * reason not to reason about it from first principles.
 *
 * <p>One further observation, recorded because it is worse than the above rather than better: on
 * Temurin 21/AArch64 {@code Math.sin(Infinity)} returns {@code 0x7ff0000000000001} - a NaN that is
 * not the canonical quiet NaN at all. So the intrinsic produces a third distinct pattern. Nothing on
 * proj4j's transform path may call {@code Math.sin}, but it is worth knowing that "it is just the
 * sign bit" would be an understatement.
 *
 * <h2>Why this matters here rather than being a curiosity</h2>
 *
 * <p>Three places in this project state or rely on a raw-bit comparison, and each needs the carve-out
 * spelled out or it will produce a false failure the first time it runs on a second architecture:
 *
 * <ol>
 *   <li><b>The fail-closed sentinel policy.</b> A failed point has {@code NaN} written to
 *       <em>every</em> output ordinate, deliberately. So the outputs most likely to be NaN are
 *       exactly the ones a golden table records for error rows - and those cannot be compared on raw
 *       bits across architectures.</li>
 *   <li><b>Correctness gate B</b> ("fast-path vs general-path, bitwise, on
 *       {@code Double.doubleToRawLongBits}, so NaN payloads and {@code +/-0.0} are distinguished").
 *       Within one JVM that is exactly right and should not be weakened. Across architectures the
 *       NaN half of it is not satisfiable.</li>
 *   <li><b>The shared-transform concurrency test</b>, which asserts raw-bit identity precisely
 *       because a tolerance assertion would pass through a torn double. Also correct, and also
 *       single-JVM, so unaffected - but the reason it is unaffected should be on the record rather
 *       than left to luck.</li>
 * </ol>
 *
 * <p>The {@code +/-0.0} half of gate B is <b>not</b> affected and must not be relaxed along with the
 * NaN half: signed zero is fully specified by IEEE-754 and is architecture-independent. It is a real
 * distinction at the equator and the antimeridian, and this test asserts it stays one.
 *
 * <h2>What this test asserts</h2>
 *
 * <p>Not that the divergence exists - that would fail on whichever architecture happens to be the
 * majority. It asserts the properties proj4j actually depends on: that NaN is <em>recognisable</em> as
 * NaN however it was produced, that signed zero is exact, and that the {@code dx - dx != 0.0}
 * non-finite test used in the bulk inner loop behaves identically for every NaN payload. Then it
 * writes this JVM's table to {@code target/determinism/} so the CI matrix can diff the architectures
 * and show the difference rather than assert it from one side.
 */
public class NanBitPatternTest {

    /** The expressions whose NaN bit pattern is recorded. Names are stable; CI diffs on them. */
    private static String[] names() {
        return new String[]{
                "Double.NaN", "inf-inf", "(-inf)-(-inf)", "inf*0.0", "0.0/0.0", "inf/inf",
                "Math.sqrt(-1)", "StrictMath.sqrt(-1)",
                "StrictMath.sin(inf)", "StrictMath.cos(inf)", "StrictMath.tan(inf)",
                "StrictMath.asin(2)", "StrictMath.acos(2)", "StrictMath.log(-1)",
                "StrictMath.log1p(-2)", "StrictMath.pow(-1,0.5)",
                "org.locationtech.proj4j.util.FastStrictTrig.sin(inf)",
                "org.locationtech.proj4j.util.FastStrictTrig.cos(inf)",
                "org.locationtech.proj4j.util.FastStrictTrig.tan(inf)",
        };
    }

    private static double[] values() {
        double inf = Double.POSITIVE_INFINITY;
        return new double[]{
                Double.NaN, inf - inf, (-inf) - (-inf), inf * 0.0, 0.0 / 0.0, inf / inf,
                Math.sqrt(-1.0), StrictMath.sqrt(-1.0),
                StrictMath.sin(inf), StrictMath.cos(inf), StrictMath.tan(inf),
                StrictMath.asin(2.0), StrictMath.acos(2.0), StrictMath.log(-1.0),
                StrictMath.log1p(-2.0), StrictMath.pow(-1.0, 0.5),
                org.locationtech.proj4j.util.FastStrictTrig.sin(inf),
                org.locationtech.proj4j.util.FastStrictTrig.cos(inf),
                org.locationtech.proj4j.util.FastStrictTrig.tan(inf),
        };
    }

    /**
     * Prints and records this JVM's NaN bit patterns, and asserts every one of them really is NaN.
     *
     * @throws IOException if the table cannot be written
     */
    @Test
    public void recordThisArchitecturesNanBitPatterns() throws IOException {
        String[] names = names();
        double[] vs = values();
        assertEquals("names and values must line up", names.length, vs.length);

        StringBuilder human = new StringBuilder(String.format(
                "determinism NaN patterns on java.version=%s os.arch=%s:%n",
                System.getProperty("java.version"), System.getProperty("os.arch")));
        StringBuilder tsv = new StringBuilder();
        tsv.append("# NaN bit patterns. Architecture-dependent BY DESIGN of the hardware - the JLS\n");
        tsv.append("# specifies only that these yield a NaN, not which one. Recorded per CI leg so\n");
        tsv.append("# the difference can be shown rather than asserted from one architecture.\n");
        tsv.append("javaVersion\t").append(System.getProperty("java.version")).append('\n');
        tsv.append("osArch\t").append(System.getProperty("os.arch")).append('\n');
        tsv.append("expression\trawbits\n");
        int distinct = 0;
        long first = Double.doubleToRawLongBits(vs[0]);
        for (int i = 0; i < names.length; i++) {
            long bits = Double.doubleToRawLongBits(vs[i]);
            human.append(String.format("  %-52s 0x%016x%n", names[i], Long.valueOf(bits)));
            tsv.append(names[i]).append('\t').append(String.format("%016x", Long.valueOf(bits)))
                    .append('\n');
            if (bits != first) {
                distinct++;
            }
            assertTrue(names[i] + " must be NaN, got 0x" + Long.toHexString(bits),
                    Double.isNaN(vs[i]));
        }
        human.append("  (").append(distinct).append(" of ").append(names.length)
                .append(" differ from Double.NaN's canonical pattern on this architecture)\n");
        System.out.print(human);

        File dir = new File(System.getProperty("proj4j.determinism.outDir", "target/determinism"));
        if (dir.isDirectory() || dir.mkdirs()) {
            Writer w = new OutputStreamWriter(
                    new FileOutputStream(new File(dir, "nan-patterns.tsv")), "UTF-8");
            try {
                w.write(tsv.toString());
            } finally {
                w.close();
            }
        } else {
            System.out.println("determinism: could not create " + dir
                    + "; the table above is still the record");
        }
    }

    /**
     * Signed zero <b>is</b> architecture-independent, and must stay part of the raw-bit guarantee.
     *
     * <p>Stated separately from the NaN carve-out on purpose. The two look alike - both are "an
     * IEEE-754 special case that a naive {@code ==} comparison mishandles" - and the temptation when
     * relaxing one is to relax both. But signed zero is fully specified: {@code -0.0} has exactly one
     * bit pattern, {@code 0.0 == -0.0} is true while their raw bits differ, and every architecture
     * agrees. Losing that distinction would lose a real one: a point on the equator or the
     * antimeridian can legitimately carry {@code -0.0}, and correctness gate B exists partly to
     * notice when a refactor silently normalises it away.
     */
    @Test
    public void signedZeroIsExactAndMustNotBeRelaxedAlongWithNaN() {
        assertEquals("+0.0 has one canonical pattern",
                0x0000000000000000L, Double.doubleToRawLongBits(0.0));
        assertEquals("-0.0 has one canonical pattern",
                0x8000000000000000L, Double.doubleToRawLongBits(-0.0));
        assertTrue("0.0 == -0.0 under ==, which is why a raw-bit comparison is needed at all",
                0.0 == -0.0);
        assertTrue("their raw bits differ, which is the distinction being preserved",
                Double.doubleToRawLongBits(0.0) != Double.doubleToRawLongBits(-0.0));
        // Produced by arithmetic rather than written as a literal, since that is how a transform
        // would arrive at it - and unlike NaN, the sign here is specified.
        assertEquals("-1.0 * 0.0 must be -0.0 exactly",
                0x8000000000000000L, Double.doubleToRawLongBits(-1.0 * 0.0));
        assertEquals("0.0 / -1.0 must be -0.0 exactly",
                0x8000000000000000L, Double.doubleToRawLongBits(0.0 / -1.0));
        assertEquals("StrictMath.sin(-0.0) must preserve the sign",
                0x8000000000000000L, Double.doubleToRawLongBits(StrictMath.sin(-0.0)));
        assertEquals("FastStrictTrig.sin(-0.0) must preserve it too",
                0x8000000000000000L,
                Double.doubleToRawLongBits(org.locationtech.proj4j.util.FastStrictTrig.sin(-0.0)));
    }

    /**
     * The {@code dx - dx != 0.0} non-finite test behaves identically for every NaN payload.
     *
     * <p>This is the idiom the bulk inner loop uses instead of {@code !Double.isFinite(dx)} - one
     * floating-point operation, no method call, no bit extraction. Because the payload of a NaN
     * reaching that loop is architecture-dependent, it is worth having on the record that the test
     * itself is not: the guard is exact for a canonical quiet NaN, a signalling NaN, a negative NaN
     * and the x86 default NaN alike.
     */
    @Test
    public void theNonFiniteGuardIsPayloadIndependent() {
        long[] payloads = {
                0x7ff8000000000000L,   // canonical quiet NaN, and AArch64's default
                0xfff8000000000000L,   // x86-64's default NaN
                0x7ff0000000000001L,   // signalling NaN, minimum payload - JDK 21 Math.sin(inf)
                0xfff0000000000001L,   // negative signalling NaN
                0x7fffffffffffffffL,   // maximum payload
                0xffffffffffffffffL,
        };
        for (int i = 0; i < payloads.length; i++) {
            double dx = Double.longBitsToDouble(payloads[i]);
            assertTrue("0x" + Long.toHexString(payloads[i]) + " must be NaN", Double.isNaN(dx));
            assertTrue("the dx - dx != 0.0 guard must reject NaN payload 0x"
                    + Long.toHexString(payloads[i]), dx - dx != 0.0);
            assertEquals("the guard must agree with !isFinite for payload 0x"
                            + Long.toHexString(payloads[i]),
                    Boolean.valueOf(!isFinite(dx)), Boolean.valueOf(dx - dx != 0.0));
        }
        // And the finite/infinite cases, so the guard is checked in both directions.
        double[] finite = {0.0, -0.0, 1.0, -1.0, Double.MIN_VALUE, Double.MAX_VALUE, 6378137.0};
        for (int i = 0; i < finite.length; i++) {
            assertTrue(finite[i] + " must pass the guard", finite[i] - finite[i] == 0.0);
        }
        assertTrue("+Inf must be rejected",
                Double.POSITIVE_INFINITY - Double.POSITIVE_INFINITY != 0.0);
        assertTrue("-Inf must be rejected",
                Double.NEGATIVE_INFINITY - Double.NEGATIVE_INFINITY != 0.0);
    }

    /** {@code Double.isFinite} is Java 8+, but spelled out here so the intent is explicit. */
    private static boolean isFinite(double d) {
        return Math.abs(d) <= Double.MAX_VALUE;
    }
}

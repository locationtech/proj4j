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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;

/**
 * Writes the committed golden table that {@link StrictMathGoldenTableTest} asserts against.
 *
 * <p>Run only when the probe set in {@link TranscendentalProbes} changes, and <b>never</b> to make a
 * failing test pass. A regenerated table is a new claim about what the reference bits are, and the
 * only thing that makes that claim worth anything is that several independent implementations agree
 * on it. Regeneration procedure, in full:
 *
 * <pre>
 * mvn -B -Dmaven.repo.local=/tmp/m2-det -pl core -am install -DskipTests -Dmaven.javadoc.skip=true
 * java -cp core/target/classes:core/target/test-classes \
 *      org.locationtech.proj4j.determinism.GenerateStrictMathGoldenTable \
 *      core/src/test/resources/org/locationtech/proj4j/determinism/strictmath-golden.tsv
 * </pre>
 *
 * <p>Then re-run {@link StrictMathGoldenTableTest} on at least one JDK where
 * {@code StrictMath.sin} is <em>native</em> (8 or 11 - check with reflection, do not assume) and one
 * where it is pure Java (17+), and on both x86-64 and AArch64. If any of those disagree, the
 * regenerated table is wrong and the disagreement is the finding.
 *
 * <h2>Why the table is committed at all, rather than compared leg-to-leg in CI</h2>
 *
 * <p>Both, in fact - see {@code .github/workflows/determinism.yaml}. But the committed table is the
 * stronger half, for a reason worth stating: a leg-to-leg comparison proves only that two runners
 * agreed <em>today</em>. A committed table also pins the answer across time, so a JDK upgrade that
 * silently changes a transcendental is caught by the ordinary build rather than by a matrix that
 * someone has to remember to keep. It is affordable here only because
 * {@code StrictMath}'s results are specified rather than platform-dependent; the same trick would be
 * unsound for {@code Math}.
 */
public final class GenerateStrictMathGoldenTable {

    private GenerateStrictMathGoldenTable() {
    }

    /** The resource path the test loads, relative to the classpath root. */
    static final String RESOURCE = "org/locationtech/proj4j/determinism/strictmath-golden.tsv";

    /**
     * Writes the table.
     *
     * @param args a single element, the output file path
     * @throws IOException if the file cannot be written
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: GenerateStrictMathGoldenTable <output.tsv>");
            System.exit(2);
        }
        File out = new File(args[0]);
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        int rows;
        Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(out), "UTF-8"));
        try {
            rows = write(w);
        } finally {
            w.close();
        }
        System.out.printf("wrote %d rows to %s%n", Integer.valueOf(rows), out.getPath());
        System.out.printf("generated on java.version=%s os.arch=%s%n",
                System.getProperty("java.version"), System.getProperty("os.arch"));
    }

    /**
     * Writes every row.
     *
     * @param w the destination
     * @return the number of data rows written
     * @throws IOException if writing fails
     */
    static int write(Writer w) throws IOException {
        // No generation timestamp and no JDK version in the file: either would make the table churn
        // on every regeneration and turn a meaningful diff into noise. Provenance belongs in the
        // commit message, which is versioned anyway.
        //
        // FORMAT. One '@<fn> <count>' line, then <count> lines of 16 hex digits - the raw bits of
        // StrictMath.<fn> applied to TranscendentalProbes' i'th probe for that function, in order.
        //
        // Arguments are deliberately NOT stored. Storing them was the first design and it made the
        // file 10.5 MB, but the decisive objection is not size: a stored argument is a second source
        // of truth that can disagree with TranscendentalProbes, and if it ever did, the table would
        // describe inputs nobody evaluates while still passing. Regenerating the arguments from the
        // same code the test uses makes that class of drift impossible rather than merely detected.
        // The count line is what catches truncation and any change to the probe set.
        w.write("# proj4j StrictMath golden table.\n");
        w.write("#\n");
        w.write("# Each data line is the raw IEEE-754 bits of a StrictMath result, 16 lower-case hex\n");
        w.write("# digits, unsigned. Arguments are NOT stored: they are regenerated by\n");
        w.write("# TranscendentalProbes, which is the single source of truth for them. A '@fn n'\n");
        w.write("# line introduces n results for that function, in probe order, and carries a digest\n");
        w.write("# of that function's argument bits so a probe list that generates DIFFERENT\n");
        w.write("# arguments on some JDK fails once, saying so, instead of as a flood of result\n");
        w.write("# mismatches naming plausible-looking arguments. That is not hypothetical: building\n");
        w.write("# the probes with Math.toRadians made the list differ on Java 8, because Java 9\n");
        w.write("# changed that method by 1 ulp on 25% of whole degrees.\n");
        w.write("#\n");
        w.write("# This table is only meaningful because StrictMath is specified to a bit rather than\n");
        w.write("# to an ulp. The same table would be unsound for Math, whose results HotSpot may\n");
        w.write("# substitute per architecture - which is the divergence StrictMathGoldenTableTest's\n");
        w.write("# negative control measures.\n");
        w.write("#\n");
        w.write("# Regenerate with GenerateStrictMathGoldenTable. Read its javadoc first: a\n");
        w.write("# regenerated table is a new claim, and never a way to make a failing test pass.\n");
        int rows = 0;
        for (int f = 0; f < TranscendentalProbes.UNARY.length; f++) {
            String fn = TranscendentalProbes.UNARY[f];
            double[] xs = TranscendentalProbes.unaryProbes(fn);
            w.write("@" + fn + " " + xs.length + " "
                    + hex(TranscendentalProbes.digest(xs)) + "\n");
            for (int i = 0; i < xs.length; i++) {
                w.write(hex(Double.doubleToRawLongBits(
                        TranscendentalProbes.strictUnary(fn, xs[i]))));
                w.write('\n');
                rows++;
            }
        }
        double[] pairs = TranscendentalProbes.binaryProbes();
        for (int f = 0; f < TranscendentalProbes.BINARY.length; f++) {
            String fn = TranscendentalProbes.BINARY[f];
            w.write("@" + fn + " " + (pairs.length / 2) + " "
                    + hex(TranscendentalProbes.digest(pairs)) + "\n");
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                w.write(hex(Double.doubleToRawLongBits(
                        TranscendentalProbes.strictBinary(fn, pairs[i], pairs[i + 1]))));
                w.write('\n');
                rows++;
            }
        }
        return rows;
    }

    /**
     * Formats a raw bit pattern.
     *
     * @param bits the raw bits
     * @return a fixed-width unsigned 16-digit hex string
     */
    static String hex(long bits) {
        // Fixed width, no 0x prefix, lower case: the file is diffed and sorted by tools, so a
        // variable-width field would make column alignment depend on the value.
        String s = Long.toHexString(bits);
        if (s.length() == 16) {
            return s;
        }
        StringBuilder sb = new StringBuilder(16);
        for (int i = s.length(); i < 16; i++) {
            sb.append('0');
        }
        return sb.append(s).toString();
    }
}

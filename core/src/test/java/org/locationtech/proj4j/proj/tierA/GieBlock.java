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

package org.locationtech.proj4j.proj.tierA;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.locationtech.proj4j.gie.GieTolerance;

/**
 * One {@code operation} block of PROJ's {@code gie} corpus, read from the vendored
 * {@code .gie} file rather than transcribed.
 *
 * <p><b>Why the expected values are read from the file.</b> Transcribing them into Java
 * source is the failure mode this class exists to prevent: a transcription can be wrong in
 * a way that agrees with a wrong implementation, and then the test certifies the bug. The
 * corpus files are byte-identical vendored upstream data, so reading them makes the
 * assertion "proj4j agrees with PROJ 9.8.1" rather than "proj4j agrees with what somebody
 * typed".
 *
 * <p>This is deliberately <b>not</b> the {@code conformance} module's lexer. That one
 * implements all 19 gie verbs, four metric branches and the full failure taxonomy, and it
 * is the authority for the headline number. This reads the small subset the Tier A
 * pseudo-cylindricals actually use — {@code operation}, {@code tolerance}, {@code accept},
 * {@code expect}, {@code direction}, {@code roundtrip} — so that a Tier A projection can be
 * unit-tested in the {@code core} module, with no dependency on a sibling test module, and
 * so that <b>per-projection</b> counts are available. The aggregate harness reports
 * per-file totals only, which cannot attribute a delta to one of the twelve operators
 * sharing {@code builtins.gie}.
 *
 * <h2>Locating the corpus</h2>
 *
 * <p>The corpus lives in a sibling module, so the path is resolved by walking up from the
 * working directory looking for {@code conformance/src/test/resources/gie}. If it is not
 * found the tests using it are skipped rather than failed — but see
 * {@link #requireCorpus()}, which fails instead, because for this work a silent skip would
 * mean the whole suite passes vacuously.
 */
final class GieBlock {

    /** Repo-relative location of the vendored corpus. */
    private static final String CORPUS = "conformance/src/test/resources/gie";

    private final String operation;
    private final List<Row> rows;

    private GieBlock(String operation, List<Row> rows) {
        this.operation = operation;
        this.rows = rows;
    }

    /** One {@code accept}/{@code expect} pair, or one {@code roundtrip}. */
    static final class Row {
        final double[] accept;
        final double[] expect;
        /** Tolerance in metres, already converted from the row's unit. */
        final double toleranceMetres;
        final boolean inverse;
        /** {@code > 0} for a {@code roundtrip N} row, in which case {@code expect} is null. */
        final int roundtripTrips;
        final int lineNumber;

        Row(double[] accept, double[] expect, double toleranceMetres, boolean inverse,
                int roundtripTrips, int lineNumber) {
            this.accept = accept;
            this.expect = expect;
            this.toleranceMetres = toleranceMetres;
            this.inverse = inverse;
            this.roundtripTrips = roundtripTrips;
            this.lineNumber = lineNumber;
        }

        boolean isRoundtrip() {
            return roundtripTrips > 0;
        }

        @Override
        public String toString() {
            return "line " + lineNumber + (isRoundtrip()
                    ? " roundtrip " + roundtripTrips
                    : (inverse ? " inverse" : " forward"));
        }
    }

    String operation() {
        return operation;
    }

    List<Row> rows() {
        return rows;
    }

    /** The {@code expect} rows only, i.e. what the corpus counts as an {@code expect}. */
    List<Row> expectRows() {
        List<Row> out = new ArrayList<Row>();
        for (Row r : rows) {
            if (!r.isRoundtrip()) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * The directory holding the vendored {@code .gie} files.
     *
     * @return the directory, or {@code null} if the sibling module is not present
     */
    static Path corpusDirectory() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve(CORPUS);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The corpus directory, or a hard failure.
     *
     * <p>Skips are never passes (non-negotiable 6). These tests exist to prove Tier A
     * agrees with the corpus; if the corpus is missing, the honest outcome is a failure
     * naming the reason, not a green suite.
     */
    static Path requireCorpus() {
        Path dir = corpusDirectory();
        if (dir == null) {
            throw new IllegalStateException(
                    "vendored gie corpus not found: expected " + CORPUS
                            + " within six parents of " + Paths.get("").toAbsolutePath()
                            + ". These tests read expected values from the corpus rather "
                            + "than transcribing them, so a missing corpus is a failure, "
                            + "not a skip.");
        }
        return dir;
    }

    /**
     * Reads every {@code operation} block in {@code file} whose operation line contains
     * {@code +proj=<name>} as a whole token.
     *
     * @param file bare file name, e.g. {@code builtins.gie}
     * @param projName the {@code +proj=} value
     */
    static List<GieBlock> blocksFor(String file, String projName) {
        Path path = requireCorpus().resolve(file);
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }

        List<GieBlock> blocks = new ArrayList<GieBlock>();
        String operation = null;
        List<Row> rows = null;
        double tolerance = GieTolerance.DEFAULT_TOLERANCE;
        boolean inverse = false;
        double[] pendingAccept = null;
        // Rows are parsed only inside a block we actually want. Parsing eagerly would mean
        // parsing every other operation's coordinates too, and the corpus uses DMS forms
        // such as `83d10'W` that this deliberately-minimal reader does not implement --
        // producing a NumberFormatException from an unrelated block.
        boolean matching = false;

        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            int hash = raw.indexOf('#');
            String line = (hash >= 0 ? raw.substring(0, hash) : raw).trim();
            if (line.isEmpty() || line.startsWith("---") || line.startsWith("===")) {
                continue;
            }
            String[] tok = line.split("\\s+");
            String verb = tok[0].toLowerCase();

            if ("operation".equals(verb)) {
                if (matching) {
                    blocks.add(new GieBlock(operation, rows));
                }
                operation = line.substring("operation".length()).trim();
                matching = matches(operation, projName);
                rows = new ArrayList<Row>();
                // gie resets tolerance and direction at every new operation.
                tolerance = GieTolerance.DEFAULT_TOLERANCE;
                inverse = false;
                pendingAccept = null;
            } else if (!matching) {
                continue;
            } else if ("tolerance".equals(verb)) {
                tolerance = parseTolerance(line);
            } else if ("direction".equals(verb)) {
                inverse = tok.length > 1 && tok[1].toLowerCase().startsWith("inv");
            } else if ("accept".equals(verb)) {
                pendingAccept = parseOrdinates(tok);
            } else if ("expect".equals(verb)) {
                if (pendingAccept == null) {
                    continue;
                }
                if (tok.length > 1 && "failure".equals(tok[1].toLowerCase())) {
                    // Failure rows are the aggregate harness's business; the Tier A
                    // operators that have any are asserted directly in their own tests.
                    pendingAccept = null;
                    continue;
                }
                rows.add(new Row(pendingAccept, parseOrdinates(tok), tolerance, inverse,
                        0, i + 1));
                pendingAccept = null;
            } else if ("roundtrip".equals(verb)) {
                if (pendingAccept == null && !rows.isEmpty()) {
                    // `roundtrip N` with no fresh accept reuses the last accepted point.
                    Row last = rows.get(rows.size() - 1);
                    rows.add(new Row(last.accept, null, tolerance, inverse,
                            trips(tok), i + 1));
                } else if (pendingAccept != null) {
                    rows.add(new Row(pendingAccept, null, tolerance, inverse,
                            trips(tok), i + 1));
                    pendingAccept = null;
                }
            }
        }
        if (matching) {
            blocks.add(new GieBlock(operation, rows));
        }
        return blocks;
    }

    private static int trips(String[] tok) {
        return tok.length > 1 ? Integer.parseInt(tok[1]) : 1;
    }

    /**
     * Whole-token match, with two exclusions that a naive {@code contains} gets wrong.
     *
     * <p><b>Token, not substring</b>, so {@code putp3} does not also select {@code putp3p},
     * {@code natearth} does not select {@code natearth2}, and {@code eck3} does not select
     * {@code eck3x} were one to be added.
     *
     * <p><b>Pipelines are excluded.</b> {@code +proj=pipeline ... step proj=webmerc ...}
     * contains {@code proj=webmerc} as a whole token, but the operation is a pipeline and is
     * not constructible from a bare {@link org.locationtech.proj4j.proj.Projection}. Two of
     * {@code webmerc}'s four corpus rows are of this shape, which is why its reachable count
     * is 2 and not 4.
     */
    private static boolean matches(String operation, String projName) {
        boolean found = false;
        for (String t : operation.split("\\s+")) {
            String s = t.startsWith("+") ? t.substring(1) : t;
            if (s.equals("proj=pipeline")) {
                return false;
            }
            if (s.equals("proj=" + projName)) {
                found = true;
            }
        }
        return found;
    }

    /**
     * Parses the ordinates of an {@code accept}/{@code expect} line.
     *
     * <p><b>Underscores are digit group separators in the corpus</b> and must be stripped:
     * {@code builtins.gie} writes eastings such as {@code 10_018_754.1714}. Java only
     * accepts {@code _} in <em>source</em> literals, never in
     * {@link Double#parseDouble(String)}, so leaving them in throws
     * {@link NumberFormatException} on a row that is perfectly valid gie. PROJ's own lexer
     * drops them in {@code pj_shrink}.
     */
    private static double[] parseOrdinates(String[] tok) {
        List<Double> v = new ArrayList<Double>();
        for (int i = 1; i < tok.length; i++) {
            String s = tok[i];
            if (s.indexOf('_') >= 0) {
                s = s.replace("_", "");
            }
            v.add(Double.valueOf(s));
        }
        double[] out = new double[v.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = v.get(i).doubleValue();
        }
        return out;
    }

    /**
     * The {@code tolerance} verb's argument, in metres, delegated to
     * {@link GieTolerance#tolerance(String)}.
     *
     * <p><b>Do not reimplement this.</b> The obvious reading — "split the number from the
     * unit and scale" — is wrong, and wrong in the strict direction, which is the dangerous
     * one. {@code gie.cpp:502} takes the unit as {@code column(args, 2)}, the <b>second
     * whitespace-separated column</b>; for a fused token such as {@code 1cm} there is no
     * second column, so {@code column} returns the empty string, no unit matches, and the
     * value is multiplied by the default scale of 1. <b>{@code tolerance 1cm} therefore
     * means 1 metre in PROJ, and {@code tolerance 1mm} means 1 metre.</b> A parser that
     * "helpfully" reads the fused unit is 100&times; and 1000&times; stricter than the
     * corpus and manufactures failures that are not failures — which then sends someone
     * hunting a formula bug that does not exist.
     *
     * <p>These are upstream quirks, accidentally looser than their authors intended, and
     * non-negotiable 7 says reproduce them rather than correct them. {@code GieTolerance} is
     * the published, separately-tested implementation of exactly this, so routing through it
     * also means this file cannot drift from it.
     */
    private static double parseTolerance(String line) {
        // GieTolerance.tolerance() expects the verb's argument, not the whole line.
        return GieTolerance.tolerance(line.substring("tolerance".length()).trim());
    }
}

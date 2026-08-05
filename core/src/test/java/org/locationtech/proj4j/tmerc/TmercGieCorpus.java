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

package org.locationtech.proj4j.tmerc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the {@code tmerc}, {@code etmerc} and {@code utm} rows straight out of
 * {@code conformance/src/test/resources/gie/builtins.gie} — the byte-identical vendored copy of
 * {@code 9.8.1:test/gie/builtins.gie}.
 *
 * <p><b>Why read the file instead of transcribing the numbers.</b> A transcribed expectation
 * pins whatever this library did on the day it was typed. Reading the corpus means the assertions
 * are upstream's, that a row added or retightened upstream shows up as a failure here, and that
 * the tolerances — {@code 50 nm} for the ellipsoidal blocks, {@code 0.1 mm} for the spherical
 * ones, {@code 0.001 mm} for one UTM row — are the file's own rather than a comfortable choice.
 *
 * <p>The reader understands five verbs ({@code operation}, {@code tolerance}, {@code direction},
 * {@code accept}, {@code expect}, {@code roundtrip}) and is deliberately no more general than
 * these three families need. It duplicates about eighty lines of state machine already present in
 * {@code proj/adams/GieCorpus} because that one is package-private in a package owned by a
 * different change; copying it is the cheaper coupling.
 *
 * <p>Two behaviours of gie that are easy to get wrong and are load-bearing here:
 * <ul>
 * <li>{@code operation} resets the tolerance to 0.5 mm and the direction to forward, but
 *     <b>does not clear the pending {@code accept}</b> ({@code gie.cpp:646-651}). The
 *     {@code +proj=utm +a=6400000 +zone=30} block relies on that: its {@code expect failure} has
 *     no {@code accept} of its own.
 * <li>An {@code expect} that has no {@code accept} at all is a <b>setup</b> expectation, because
 *     gie defers reporting a failed {@code proj_create} to {@code expect} time.
 * </ul>
 */
final class TmercGieCorpus {

    /** An {@code expect} of numbers. */
    static final int NUMERIC = 1;

    /** An {@code expect failure}, with or without a named errno. */
    static final int FAILURE = 2;

    /** One assertion: an {@code accept}/{@code expect} pair, or a bare {@code roundtrip}. */
    static final class Row {

        final int line;
        /** The {@code operation} definition in force, whitespace-normalised. */
        final String operation;
        /** {@code true} when a {@code direction inverse} is in force. */
        final boolean inverse;
        /** The {@code tolerance} in force, in metres. */
        final double tolerance;
        /** {@code false} for a row that had no {@code accept} — a setup expectation. */
        final boolean hasInput;
        final double acceptX;
        final double acceptY;
        /** {@link #NUMERIC} or {@link #FAILURE}. */
        final int kind;
        final double expectX;
        final double expectY;
        /** The errno name on an {@code expect failure errno <name>} row, or null. */
        final String errno;
        /** The {@code roundtrip} count, or 0 when the row carries no {@code roundtrip}. */
        final int roundtrip;

        Row(int line, String operation, boolean inverse, double tolerance, boolean hasInput,
                double acceptX, double acceptY, int kind, double expectX, double expectY,
                String errno, int roundtrip) {
            this.line = line;
            this.operation = operation;
            this.inverse = inverse;
            this.tolerance = tolerance;
            this.hasInput = hasInput;
            this.acceptX = acceptX;
            this.acceptY = acceptY;
            this.kind = kind;
            this.expectX = expectX;
            this.expectY = expectY;
            this.errno = errno;
            this.roundtrip = roundtrip;
        }

        /** The bare projection name: {@code tmerc}, {@code etmerc} or {@code utm}. */
        String projection() {
            int at = operation.indexOf("+proj=");
            int end = operation.indexOf(' ', at);
            return operation.substring(at + "+proj=".length(), end < 0 ? operation.length() : end);
        }

        @Override
        public String toString() {
            return "builtins.gie:" + line + " [" + operation + "] "
                    + (inverse ? "inverse" : "forward")
                    + (hasInput ? " accept " + acceptX + " " + acceptY : " (no accept)")
                    + (kind == FAILURE ? " expect failure" + (errno == null ? "" : " " + errno)
                            : " expect " + expectX + " " + expectY)
                    + (roundtrip > 0 ? " roundtrip " + roundtrip : "")
                    + " tolerance " + tolerance + " m";
        }
    }

    private TmercGieCorpus() {
    }

    /**
     * Locates {@code conformance/src/test/resources/gie/builtins.gie}. Surefire runs with the
     * module base directory as the working directory, so {@code ../conformance/...} resolves from
     * {@code core/}; {@code basedir} and the current directory are tried too so the test also
     * works from the repository root and from an IDE. A missing file is a hard failure, never a
     * skip.
     */
    static Path file() {
        List<Path> candidates = new ArrayList<Path>();
        String basedir = System.getProperty("basedir");
        if (basedir != null) {
            candidates.add(Paths.get(basedir, "..", "conformance", "src", "test", "resources",
                    "gie", "builtins.gie"));
        }
        candidates.add(Paths.get("..", "conformance", "src", "test", "resources", "gie",
                "builtins.gie"));
        candidates.add(Paths.get("conformance", "src", "test", "resources", "gie",
                "builtins.gie"));
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        throw new IllegalStateException("cannot locate conformance/src/test/resources/gie/"
                + "builtins.gie from " + Paths.get(".").toAbsolutePath().normalize()
                + "; tried " + candidates);
    }

    /**
     * Every row belonging to one of the three transverse-Mercator operations.
     *
     * @return the rows in file order
     */
    static List<Row> transverseMercatorRows() {
        List<Row> all = new ArrayList<Row>();
        for (Row r : read()) {
            String op = r.operation;
            if (op != null && (op.contains("+proj=tmerc ") || op.endsWith("+proj=tmerc")
                    || op.contains("+proj=etmerc ") || op.endsWith("+proj=etmerc")
                    || op.contains("+proj=utm ") || op.endsWith("+proj=utm"))) {
                all.add(r);
            }
        }
        return all;
    }

    private static List<Row> read() {
        Path path = file();
        List<Row> rows = new ArrayList<Row>();

        String operation = null;
        boolean inverse = false;
        // gie resets the tolerance to 0.5 mm on every `operation` (gie.cpp:651).
        double tolerance = 0.0005;

        boolean havePending = false;
        int pendingLine = 0;
        double pendingX = 0;
        double pendingY = 0;

        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                Files.newInputStream(path), StandardCharsets.UTF_8))) {
            String raw;
            int lineNumber = 0;
            while ((raw = in.readLine()) != null) {
                lineNumber++;
                String line = strip(raw);
                if (line.isEmpty()) {
                    continue;
                }
                String[] token = line.split("\\s+");
                String verb = token[0];

                if ("operation".equals(verb)) {
                    operation = line.substring(verb.length()).trim().replaceAll("\\s+", " ");
                    inverse = false;
                    tolerance = 0.0005;
                    // Deliberately NOT clearing the pending accept: gie does not.
                } else if ("direction".equals(verb)) {
                    inverse = token.length > 1
                            && (token[1].charAt(0) == 'i' || token[1].charAt(0) == 'I'
                                    || token[1].charAt(0) == 'r' || token[1].charAt(0) == 'R');
                } else if ("tolerance".equals(verb)) {
                    tolerance = parseTolerance(line.substring(verb.length()).trim(), lineNumber);
                } else if ("accept".equals(verb)) {
                    havePending = true;
                    pendingLine = lineNumber;
                    pendingX = number(token[1]);
                    pendingY = number(token[2]);
                } else if ("expect".equals(verb)) {
                    int at = havePending ? pendingLine : lineNumber;
                    if ("failure".equals(token[1])) {
                        String errno = token.length > 3 && "errno".equals(token[2])
                                ? token[3] : null;
                        rows.add(new Row(at, operation, inverse, tolerance, havePending,
                                pendingX, pendingY, FAILURE, 0, 0, errno, 0));
                    } else {
                        if (!havePending) {
                            throw new IllegalStateException(path + ":" + lineNumber
                                    + ": numeric expect with no accept anywhere before it");
                        }
                        rows.add(new Row(at, operation, inverse, tolerance, true, pendingX,
                                pendingY, NUMERIC, number(token[1]), number(token[2]), null, 0));
                    }
                } else if ("roundtrip".equals(verb)) {
                    int n = token.length > 1 ? Integer.parseInt(token[1]) : 100;
                    if (rows.isEmpty()) {
                        throw new IllegalStateException(
                                path + ":" + lineNumber + ": roundtrip with no preceding expect");
                    }
                    // Every roundtrip in these blocks follows its own expect, so the count is
                    // attached to the row just recorded.
                    Row last = rows.remove(rows.size() - 1);
                    rows.add(new Row(last.line, last.operation, last.inverse, last.tolerance,
                            last.hasInput, last.acceptX, last.acceptY, last.kind, last.expectX,
                            last.expectY, last.errno, n));
                }
                // Everything else - <gie>, </gie>, decorative rules - is ignored.
            }
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + path, e);
        }
        return rows;
    }

    /**
     * One coordinate ordinate. {@code proj_strtod} ({@code src/apps/proj_strtod.cpp:338}) runs
     * {@code un_underscore} first, so <b>underscores are ignored anywhere in a numeric literal</b>:
     * {@code 10_018_754.1714} is {@code 10018754.1714}. The transverse-Mercator blocks do not use
     * the form, but the reader walks the whole file and {@code eqearth} does.
     */
    private static double number(String text) {
        return Double.parseDouble(text.indexOf('_') < 0 ? text : text.replace("_", ""));
    }

    /**
     * Strips a trailing {@code #} comment and surrounding whitespace, and drops the
     * {@code <gie...>} tags and the decorative rules. A commented-out verb — the corpus has
     * {@code #roundtrip 1} twice in the {@code +algo=evenden_snyder} block, with the note "Small
     * difference with poder_engsager" — therefore disappears, which is what upstream intends.
     */
    private static String strip(String raw) {
        int hash = raw.indexOf('#');
        String line = (hash >= 0 ? raw.substring(0, hash) : raw).trim();
        if (line.startsWith("<") || line.startsWith("-") || line.startsWith("=")) {
            return "";
        }
        return line;
    }

    /**
     * {@code <number> [unit]}; an unrecognised unit throws rather than being guessed at, because a
     * silently mis-scaled tolerance is the failure this harness exists to prevent.
     *
     * <p>The whitespace between number and unit is optional in the corpus — {@code tolerance 1cm}
     * occurs — so they are separated by shape, not by splitting on spaces.
     */
    private static double parseTolerance(String text, int line) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^([-+0-9.eE]+)\\s*([a-zA-Z]*)$").matcher(text.trim());
        if (!m.matches()) {
            throw new IllegalStateException(
                    "builtins.gie:" + line + ": unparseable tolerance '" + text + "'");
        }
        double value = Double.parseDouble(m.group(1));
        String unit = m.group(2).isEmpty() ? "m" : m.group(2);
        if ("m".equals(unit)) {
            return value;
        }
        if ("mm".equals(unit)) {
            return value / 1.0e3;
        }
        if ("cm".equals(unit)) {
            return value / 1.0e2;
        }
        if ("um".equals(unit)) {
            return value / 1.0e6;
        }
        if ("nm".equals(unit)) {
            return value / 1.0e9;
        }
        if ("km".equals(unit)) {
            return value * 1.0e3;
        }
        throw new IllegalStateException(
                "builtins.gie:" + line + ": unsupported tolerance unit '" + unit + "'");
    }
}

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

package org.locationtech.proj4j.proj.adams;

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
 * A deliberately minimal reader for the {@code accept}/{@code expect}/{@code roundtrip} rows of
 * PROJ's {@code .gie} files, sufficient for the six files of the adams family and no more.
 *
 * <p><b>Why not reuse the conformance module's lexer.</b> {@code core} must stay
 * dependency-free, and the full lexer lives in a downstream module that is owned and evolving
 * separately. Duplicating 90 lines of trivial state machine here is the cheaper coupling. This
 * reader understands exactly six verbs — {@code operation}, {@code tolerance},
 * {@code direction}, {@code accept}, {@code expect}, {@code roundtrip} — and asserts at the end
 * that it saw the number of rows the corpus is known to contain, so a silent parse regression
 * cannot pass as a clean run.
 *
 * <p>The corpus files are read from the sibling {@code conformance} module's resources rather
 * than copied. They are byte-identical to PROJ 9.8.1 and 8,900 lines long; a copy would rot.
 * A missing file is a hard failure, not a skip: it is in the repository, and a silently skipped
 * 3,443-assertion test is worse than no test.
 */
final class GieCorpus {

    /** {@code accept} without a following {@code expect} — the corpus has 49, all commented out. */
    static final int NO_EXPECTATION = 0;

    /** An {@code expect} of two numbers. */
    static final int NUMERIC = 1;

    /** An {@code expect failure}, with or without a named errno. */
    static final int FAILURE = 2;

    /** One {@code accept} plus whatever followed it. */
    static final class Row {

        final String file;
        final int line;
        /** The {@code operation} definition in force. */
        final String operation;
        /** {@code true} when a {@code direction inverse} is in force. */
        final boolean inverse;
        /** The {@code tolerance} in force, in metres. */
        final double toleranceMetres;
        final double acceptX;
        final double acceptY;
        /** One of {@link #NO_EXPECTATION}, {@link #NUMERIC}, {@link #FAILURE}. */
        final int kind;
        final double expectX;
        final double expectY;
        /** The errno name on an {@code expect failure errno <name>} row, or null. */
        final String errno;
        /** The {@code roundtrip} count, or 0 when the row has no {@code roundtrip}. */
        final int roundtrip;

        Row(String file, int line, String operation, boolean inverse, double toleranceMetres,
                double acceptX, double acceptY, int kind, double expectX, double expectY,
                String errno, int roundtrip) {
            this.file = file;
            this.line = line;
            this.operation = operation;
            this.inverse = inverse;
            this.toleranceMetres = toleranceMetres;
            this.acceptX = acceptX;
            this.acceptY = acceptY;
            this.kind = kind;
            this.expectX = expectX;
            this.expectY = expectY;
            this.errno = errno;
            this.roundtrip = roundtrip;
        }

        @Override
        public String toString() {
            return file + ":" + line + " [" + operation + "] accept " + acceptX + " " + acceptY;
        }
    }

    private GieCorpus() {
    }

    /**
     * Locates {@code conformance/src/test/resources/gie}. Surefire runs with the module base
     * directory as the working directory, so {@code ../conformance/...} resolves from
     * {@code core/}; the {@code basedir} system property and the current directory itself are
     * tried too, so the test also works from the repository root or from an IDE.
     */
    static Path directory() {
        List<Path> candidates = new ArrayList<Path>();
        String basedir = System.getProperty("basedir");
        if (basedir != null) {
            candidates.add(Paths.get(basedir, "..", "conformance", "src", "test", "resources",
                    "gie"));
        }
        candidates.add(Paths.get("..", "conformance", "src", "test", "resources", "gie"));
        candidates.add(Paths.get("conformance", "src", "test", "resources", "gie"));
        for (Path p : candidates) {
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        throw new IllegalStateException(
                "cannot locate conformance/src/test/resources/gie from " + Paths.get(".")
                        .toAbsolutePath().normalize() + "; tried " + candidates);
    }

    /**
     * Parses one {@code .gie} file.
     *
     * @param name the bare file name, e.g. {@code "guyou.gie"}
     */
    static List<Row> read(String name) {
        Path path = directory().resolve(name);
        List<Row> rows = new ArrayList<Row>();

        String operation = null;
        boolean inverse = false;
        // gie resets the tolerance to 0.5 mm on every `operation` (gie.cpp:651).
        double tolerance = 0.0005;
        boolean havePending = false;
        int pendingLine = 0;
        double pendingX = 0;
        double pendingY = 0;
        String pendingOperation = null;
        boolean pendingInverse = false;
        double pendingTolerance = 0;

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
                    // A pending accept does not survive a new operation.
                    havePending = false;
                    operation = line.substring(verb.length()).trim();
                    inverse = false;
                    tolerance = 0.0005;
                } else if ("direction".equals(verb)) {
                    inverse = token.length > 1 && "inverse".equals(token[1]);
                } else if ("tolerance".equals(verb)) {
                    tolerance = parseTolerance(line.substring(verb.length()).trim(), path,
                            lineNumber);
                } else if ("accept".equals(verb)) {
                    if (havePending) {
                        // The previous accept had no expect: record it so the row count
                        // reconciles, then replace it.
                        rows.add(new Row(name, pendingLine, pendingOperation, pendingInverse,
                                pendingTolerance, pendingX, pendingY, NO_EXPECTATION, 0, 0, null,
                                0));
                    }
                    havePending = true;
                    pendingLine = lineNumber;
                    pendingOperation = operation;
                    pendingInverse = inverse;
                    pendingTolerance = tolerance;
                    pendingX = Double.parseDouble(token[1]);
                    pendingY = Double.parseDouble(token[2]);
                } else if ("expect".equals(verb)) {
                    if (!havePending) {
                        throw new IllegalStateException(
                                path + ":" + lineNumber + ": expect without accept");
                    }
                    havePending = false;
                    if ("failure".equals(token[1])) {
                        String errno = token.length > 3 && "errno".equals(token[2])
                                ? token[3] : null;
                        rows.add(new Row(name, pendingLine, pendingOperation, pendingInverse,
                                pendingTolerance, pendingX, pendingY, FAILURE, 0, 0, errno, 0));
                    } else {
                        rows.add(new Row(name, pendingLine, pendingOperation, pendingInverse,
                                pendingTolerance, pendingX, pendingY, NUMERIC,
                                Double.parseDouble(token[1]), Double.parseDouble(token[2]), null,
                                0));
                    }
                } else if ("roundtrip".equals(verb)) {
                    // gie's `roundtrip` reads the last accepted coordinate, which may or may not
                    // have had an `expect` of its own: spilhaus.gie is 59 bare
                    // accept/roundtrip pairs with no expect at all, while adams_ws2.gie and
                    // peirce_q.gie put roundtrip after an expect. Both shapes occur, so an
                    // outstanding accept is turned into its own row and only otherwise is the
                    // count attached to the row just recorded.
                    int n = token.length > 1 ? Integer.parseInt(token[1]) : 100;
                    if (havePending) {
                        havePending = false;
                        rows.add(new Row(name, pendingLine, pendingOperation, pendingInverse,
                                pendingTolerance, pendingX, pendingY, NO_EXPECTATION, 0, 0, null,
                                n));
                    } else if (!rows.isEmpty()) {
                        Row last = rows.remove(rows.size() - 1);
                        rows.add(new Row(last.file, last.line, last.operation, last.inverse,
                                last.toleranceMetres, last.acceptX, last.acceptY, last.kind,
                                last.expectX, last.expectY, last.errno, n));
                    } else {
                        throw new IllegalStateException(
                                path + ":" + lineNumber + ": roundtrip without a preceding accept");
                    }
                }
                // Everything else - <gie-strict>, </gie-strict>, dashed separators - is ignored.
            }
            if (havePending) {
                rows.add(new Row(name, pendingLine, pendingOperation, pendingInverse,
                        pendingTolerance, pendingX, pendingY, NO_EXPECTATION, 0, 0, null, 0));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + path, e);
        }
        return rows;
    }

    /**
     * Strips a trailing {@code #} comment and surrounding whitespace, and drops the
     * {@code <gie...>} tags and the dashed separator lines.
     */
    private static String strip(String raw) {
        int hash = raw.indexOf('#');
        String line = (hash >= 0 ? raw.substring(0, hash) : raw).trim();
        if (line.startsWith("<") || line.startsWith("-")) {
            return "";
        }
        return line;
    }

    /**
     * {@code <number> <unit>}. Only the units the six files actually use are accepted; an
     * unrecognised one throws rather than being guessed at, because a silently mis-scaled
     * tolerance is the failure mode this whole exercise exists to avoid.
     */
    private static double parseTolerance(String text, Path path, int line) {
        String[] token = text.split("\\s+");
        double value = Double.parseDouble(token[0]);
        String unit = token.length > 1 ? token[1] : "m";
        if ("m".equals(unit)) {
            return value;
        }
        if ("mm".equals(unit)) {
            return value / 1000.0;
        }
        if ("cm".equals(unit)) {
            return value / 100.0;
        }
        if ("km".equals(unit)) {
            return value * 1000.0;
        }
        throw new IllegalStateException(
                path + ":" + line + ": unsupported tolerance unit '" + unit + "'");
    }
}

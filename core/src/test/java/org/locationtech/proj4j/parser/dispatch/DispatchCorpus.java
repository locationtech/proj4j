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

package org.locationtech.proj4j.parser.dispatch;

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
 * The {@code operation} blocks of PROJ's vendored {@code gie} corpus that exercise the
 * parameters {@code Proj4Parser} learned to dispatch, read from the {@code .gie} file rather
 * than transcribed into Java source.
 *
 * <h2>Why nothing here is transcribed</h2>
 *
 * <p>A transcribed expected value can be wrong in a way that agrees with a wrong
 * implementation, and then the test certifies the bug. The {@code .gie} files are
 * byte-identical vendored upstream data, so reading them makes the assertion "proj4j agrees
 * with PROJ 9.8.1" rather than "proj4j agrees with what somebody typed".
 *
 * <p>The corpus also happens to contain a ready-made test of the angular value grammar that
 * could not be written by hand without begging the question: {@code builtins.gie} carries
 * <b>four</b> {@code +proj=som} blocks which are two pairs, one pair written
 * {@code +inc_angle=1.7157253262878522r +asc_lon=2.2298420007209447r} and the other
 * {@code +inc_angle=98.30382 +asc_lon=127.7605356226}, with <em>byte-identical</em> expected
 * values. Running all four therefore proves that the {@code r}/{@code R} radian suffix and
 * decimal degrees reach the projection as the same angle — which is the specific reason these
 * keys must go through the DMS-capable parser and not {@code Double.parseDouble}.
 *
 * <h2>Relationship to the other two readers</h2>
 *
 * <p>This is deliberately not the {@code conformance} module's lexer, which implements all 19
 * gie verbs and the full failure taxonomy and is the authority for the headline number; and it
 * is not {@code proj.tierA.GieBlock}, which is package-private to that package. It reads the
 * six verbs these blocks use — {@code operation}, {@code tolerance}, {@code direction},
 * {@code accept}, {@code expect}, {@code roundtrip} — so a parser-dispatch regression can be
 * caught in {@code core}'s own build, with no dependency on a sibling test module.
 */
final class DispatchCorpus {

    /** Repo-relative location of the vendored corpus. */
    private static final String CORPUS = "conformance/src/test/resources/gie";

    private final String operation;
    private final int lineNumber;
    private final List<Row> rows;

    private DispatchCorpus(String operation, int lineNumber, List<Row> rows) {
        this.operation = operation;
        this.lineNumber = lineNumber;
        this.rows = rows;
    }

    /** One {@code accept}/{@code expect} pair, or one {@code roundtrip}. */
    static final class Row {
        final double[] accept;
        /** Null for a {@code roundtrip} row and for an {@code expect failure} row. */
        final double[] expect;
        /** Tolerance in metres, already converted from the row's unit by {@link GieTolerance}. */
        final double toleranceMetres;
        final boolean inverse;
        /** {@code > 0} for a {@code roundtrip N} row. */
        final int roundtripTrips;
        final boolean expectFailure;
        final int lineNumber;

        Row(double[] accept, double[] expect, double toleranceMetres, boolean inverse,
                int roundtripTrips, boolean expectFailure, int lineNumber) {
            this.accept = accept;
            this.expect = expect;
            this.toleranceMetres = toleranceMetres;
            this.inverse = inverse;
            this.roundtripTrips = roundtripTrips;
            this.expectFailure = expectFailure;
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

    int lineNumber() {
        return lineNumber;
    }

    List<Row> rows() {
        return rows;
    }

    /** The rows that assert a coordinate, i.e. excluding {@code expect failure}. */
    List<Row> coordinateRows() {
        List<Row> out = new ArrayList<Row>();
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).expectFailure) {
                out.add(rows.get(i));
            }
        }
        return out;
    }

    /**
     * The corpus directory, or a hard failure naming why.
     *
     * <p>Skips are never passes. These tests exist to prove the newly-dispatched parameters
     * agree with the corpus; a missing corpus must not produce a green suite.
     */
    static Path requireCorpus() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve(CORPUS);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "vendored gie corpus not found: expected " + CORPUS + " within six parents of "
                        + Paths.get("").toAbsolutePath() + ". These tests read expected values "
                        + "from the corpus rather than transcribing them, so a missing corpus "
                        + "is a failure, not a skip.");
    }

    /**
     * Every non-pipeline {@code operation} block in {@code file} whose definition names
     * {@code +proj=<projName>} as a whole token.
     *
     * <p>Whole token, not substring: {@code som} must not also select {@code somerc}, which
     * {@code builtins.gie:6646} has and which is an unrelated operator (Swiss Oblique
     * Mercator). Pipelines are excluded because a pipeline is not constructible from a bare
     * {@code Projection}.
     *
     * @param file bare file name, e.g. {@code builtins.gie}
     * @param projName the {@code +proj=} value
     */
    static List<DispatchCorpus> blocksFor(String file, String projName) {
        Path path = requireCorpus().resolve(file);
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }

        List<DispatchCorpus> blocks = new ArrayList<DispatchCorpus>();
        String operation = null;
        int operationLine = 0;
        List<Row> rows = null;
        double tolerance = GieTolerance.DEFAULT_TOLERANCE;
        boolean inverse = false;
        double[] pendingAccept = null;
        // Rows are parsed only inside a block we want: the corpus uses DMS ordinate forms such
        // as `83d10'W` elsewhere, which this deliberately-minimal reader does not implement.
        boolean matching = false;

        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            int hash = raw.indexOf('#');
            String line = (hash >= 0 ? raw.substring(0, hash) : raw).trim();
            if (line.isEmpty() || line.startsWith("---") || line.startsWith("===")
                    || line.startsWith("<")) {
                continue;
            }
            String[] tok = line.split("\\s+");
            String verb = tok[0].toLowerCase();

            if ("operation".equals(verb)) {
                if (matching) {
                    blocks.add(new DispatchCorpus(operation, operationLine, rows));
                }
                operation = line.substring("operation".length()).trim();
                operationLine = i + 1;
                matching = matches(operation, projName);
                rows = new ArrayList<Row>();
                // gie resets tolerance and direction at every new operation.
                tolerance = GieTolerance.DEFAULT_TOLERANCE;
                inverse = false;
                pendingAccept = null;
            } else if (!matching) {
                continue;
            } else if ("tolerance".equals(verb)) {
                tolerance = GieTolerance.tolerance(line.substring("tolerance".length()).trim());
            } else if ("direction".equals(verb)) {
                inverse = tok.length > 1 && tok[1].toLowerCase().startsWith("inv");
            } else if ("accept".equals(verb)) {
                pendingAccept = parseOrdinates(tok);
            } else if ("expect".equals(verb)) {
                boolean failure = tok.length > 1 && "failure".equals(tok[1].toLowerCase());
                rows.add(new Row(pendingAccept, failure ? null : parseOrdinates(tok), tolerance,
                        inverse, 0, failure, i + 1));
                pendingAccept = null;
            } else if ("roundtrip".equals(verb)) {
                double[] point = pendingAccept;
                if (point == null && !rows.isEmpty()) {
                    // `roundtrip N` with no fresh accept reuses the last accepted point.
                    point = rows.get(rows.size() - 1).accept;
                }
                if (point != null) {
                    rows.add(new Row(point, null, tolerance, inverse,
                            tok.length > 1 ? Integer.parseInt(tok[1]) : 1, false, i + 1));
                }
                pendingAccept = null;
            }
        }
        if (matching) {
            blocks.add(new DispatchCorpus(operation, operationLine, rows));
        }
        return blocks;
    }

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
     * <p>Underscores are digit group separators in the corpus and must be stripped;
     * {@link Double#parseDouble(String)} accepts them only in Java source literals, never at
     * run time, so leaving them in throws on a row that is perfectly valid gie. PROJ's own
     * lexer drops them in {@code pj_shrink}.
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
}

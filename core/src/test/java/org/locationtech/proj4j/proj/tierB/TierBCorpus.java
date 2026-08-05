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

package org.locationtech.proj4j.proj.tierB;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.gie.GieComparator;
import org.locationtech.proj4j.gie.GieDirection;
import org.locationtech.proj4j.gie.GieIoUnits;
import org.locationtech.proj4j.gie.GieTolerance;
import org.locationtech.proj4j.proj.MisrSpaceObliqueMercatorProjection;
import org.locationtech.proj4j.proj.ObliqueTransformationProjection;
import org.locationtech.proj4j.proj.Projection;
import org.locationtech.proj4j.proj.SpaceObliqueMercatorProjection;
import org.locationtech.proj4j.units.Angle;

/**
 * Reads {@code operation} blocks out of the vendored {@code gie} corpus and runs them, for the
 * Tier B / Tier C projections.
 *
 * <h2>Why this is a second copy of the Tier A reader</h2>
 *
 * <p>{@code proj.tierA.GieBlock} and {@code proj.tierA.GieCheck} are package-private in
 * {@code ...proj.tierA}, so they cannot be called from here, and the brief for this change scopes
 * it to {@code proj/tierB/**}. The lexing and the metric selection are therefore duplicated
 * deliberately rather than by oversight. Both defer to the same published, separately-tested
 * classes for the two things that are easy to get wrong — {@link GieTolerance} for the
 * {@code tolerance} verb and {@link GieComparator}/{@link GieIoUnits} for the metric — so the two
 * copies cannot disagree about anything numerical. If they are ever merged, merge them into
 * {@code src/main}'s {@code org.locationtech.proj4j.gie} package, not into one of the two test
 * packages.
 *
 * <p>Two capabilities this adds over the Tier A copy:
 * <ul>
 * <li><b>{@code expect failure} rows are retained</b> rather than skipped, because three of the
 *     four Tier B groups here have one and the assertion is that Proj4J <em>throws</em>. A skipped
 *     failure row is the "failure-to-implement scoring as conformance" trap that cost this project
 *     a retracted baseline once already.</li>
 * <li><b>Parameters {@code Proj4Parser} does not dispatch can be applied</b>, for the three
 *     operators whose corpus lines need them. See {@link #build(String)}.</li>
 * </ul>
 */
final class TierBCorpus {

    /** Repo-relative location of the vendored corpus. */
    private static final String CORPUS = "conformance/src/test/resources/gie";

    private TierBCorpus() {
    }

    // ------------------------------------------------------------------------------------------
    // Model
    // ------------------------------------------------------------------------------------------

    /** One {@code accept}/{@code expect} pair, one {@code roundtrip}, or one failure row. */
    static final class Row {
        final double[] accept;
        final double[] expect;
        final double toleranceMetres;
        final boolean inverse;
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
            return "line " + lineNumber + (isRoundtrip() ? " roundtrip " + roundtripTrips
                    : (inverse ? " inverse" : " forward")) + (expectFailure ? " expect-failure" : "");
        }
    }

    /** One {@code operation} block. */
    static final class Block {
        final String operation;
        final List<Row> rows;

        Block(String operation, List<Row> rows) {
            this.operation = operation;
            this.rows = rows;
        }

        /** Total assertions: every {@code expect} (including {@code expect failure}) and every
         * {@code roundtrip}. */
        int assertionCount() {
            return rows.size();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------------------------------

    static Path requireCorpus() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve(CORPUS);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("vendored gie corpus not found: expected " + CORPUS
                + " within six parents of " + Paths.get("").toAbsolutePath()
                + ". Expected values are read from the corpus rather than transcribed, so a "
                + "missing corpus is a failure, not a skip.");
    }

    /**
     * Every {@code operation} block in {@code file} whose operation line carries
     * {@code proj=<projName>} as a whole token, pipelines excluded.
     */
    static List<Block> blocksFor(String file, String projName) {
        Path path = requireCorpus().resolve(file);
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }

        List<Block> blocks = new ArrayList<Block>();
        String operation = null;
        List<Row> rows = null;
        double tolerance = GieTolerance.DEFAULT_TOLERANCE;
        boolean inverse = false;
        double[] pendingAccept = null;
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
                    blocks.add(new Block(operation, rows));
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
                // GieTolerance, never a local parser: `tolerance 1cm` means ONE METRE in PROJ,
                // because gie takes the unit from whitespace column 2 and a fused token has none.
                tolerance = GieTolerance.tolerance(line.substring("tolerance".length()).trim());
            } else if ("direction".equals(verb)) {
                inverse = tok.length > 1 && tok[1].toLowerCase().startsWith("inv");
            } else if ("accept".equals(verb)) {
                pendingAccept = parseOrdinates(tok);
            } else if ("expect".equals(verb)) {
                boolean failure = tok.length > 1 && "failure".equals(tok[1].toLowerCase());
                if (failure) {
                    // An `expect failure` with no preceding `accept` asserts that the OPERATION
                    // cannot be created at all; with one, that the transform of that point fails.
                    rows.add(new Row(pendingAccept, null, tolerance, inverse, 0, true, i + 1));
                } else if (pendingAccept != null) {
                    rows.add(new Row(pendingAccept, parseOrdinates(tok), tolerance, inverse, 0,
                            false, i + 1));
                }
                pendingAccept = null;
            } else if ("roundtrip".equals(verb)) {
                int trips = tok.length > 1 ? Integer.parseInt(tok[1]) : 1;
                if (pendingAccept != null) {
                    rows.add(new Row(pendingAccept, null, tolerance, inverse, trips, false,
                            i + 1));
                    pendingAccept = null;
                } else if (!rows.isEmpty()) {
                    rows.add(new Row(rows.get(rows.size() - 1).accept, null, tolerance, inverse,
                            trips, false, i + 1));
                }
            }
        }
        if (matching) {
            blocks.add(new Block(operation, rows));
        }
        return blocks;
    }

    /** Whole-token match; pipelines excluded because they are not a bare {@link Projection}. */
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
     * Ordinates of an {@code accept}/{@code expect} line. Underscores are digit-group separators
     * in the corpus ({@code 10_018_754.1714}) and {@link Double#parseDouble} rejects them, so they
     * are stripped exactly as PROJ's {@code pj_shrink} does.
     */
    private static double[] parseOrdinates(String[] tok) {
        List<Double> v = new ArrayList<Double>();
        for (int i = 1; i < tok.length; i++) {
            v.add(Double.valueOf(tok[i].replace("_", "")));
        }
        double[] out = new double[v.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = v.get(i).doubleValue();
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Building the operation
    // ------------------------------------------------------------------------------------------

    /**
     * Builds the {@link Projection} an operation line describes.
     *
     * <p>Most Tier B operation lines go straight through {@link CRSFactory}, which is the honest
     * path: it exercises the real parser. Three operators cannot, because
     * {@code Proj4Parser.parseProjection} has no dispatch for the parameters their corpus lines
     * carry, and Proj4J's parse mode is {@code PROJ_COMPATIBLE} — an unrecognised key is retained
     * and silently ignored, exactly as PROJ does. For those three the parser's job is done here,
     * explicitly:
     *
     * <table>
     * <caption>the undispatched keys, per operator</caption>
     * <tr><th>{@code +proj=}</th><th>keys the parser drops</th></tr>
     * <tr><td>{@code som}</td><td>{@code +inc_angle}, {@code +ps_rev}, {@code +asc_lon}</td></tr>
     * <tr><td>{@code misrsom}</td><td>{@code +path}</td></tr>
     * <tr><td>{@code ob_tran}</td><td>{@code +o_proj}, {@code +o_lat_p}, {@code +o_lon_p},
     *     {@code +o_alpha}, {@code +o_lon_c}, {@code +o_lat_c},
     *     {@code +o_lon_1/2}, {@code +o_lat_1/2}</td></tr>
     * </table>
     *
     * <p><b>This is not a private parser and must not grow into one.</b> The ellipsoid still comes
     * from the real {@code Proj4Parser}, obtained by parsing the same line with {@code proj=}
     * swapped for {@code merc} — which keeps {@code +ellps=}, {@code +R=}, {@code +a=} and the
     * whole shape/spherification precedence in the code that owns it. Only the keys tabulated
     * above, plus {@code +lon_0} (which one {@code ob_tran} line needs and which the parser
     * <em>does</em> handle, but which has to be applied before {@code initialize()} on the manual
     * path), are set here.
     */
    static Projection build(String operation) {
        String[] args = operation.split("\\s+");
        Map<String, String> params = parameterMap(args);
        String projName = params.get("proj");

        if (!needsUndispatchedParameters(projName)) {
            return new CRSFactory().createFromParameters("gie", args).getProjection();
        }

        Projection p = new Registry().getProjection(projName);
        p.setEllipsoid(ellipsoidOf(args));
        if (params.containsKey("lon_0")) {
            p.setProjectionLongitudeDegrees(angleDegrees(params.get("lon_0")));
        }
        if (p instanceof MisrSpaceObliqueMercatorProjection) {
            ((MisrSpaceObliqueMercatorProjection) p)
                    .setPath(Integer.parseInt(params.get("path").trim()));
        } else if (p instanceof SpaceObliqueMercatorProjection) {
            SpaceObliqueMercatorProjection som = (SpaceObliqueMercatorProjection) p;
            som.setIncidenceAngleDegrees(angleDegrees(params.get("inc_angle")));
            som.setPeriodOfRevolution(Double.parseDouble(params.get("ps_rev").trim()));
            som.setAscendingLongitudeDegrees(angleDegrees(params.get("asc_lon")));
        } else if (p instanceof ObliqueTransformationProjection) {
            ((ObliqueTransformationProjection) p).setParameters(args);
        }
        p.initialize();
        return p;
    }

    static boolean needsUndispatchedParameters(String projName) {
        return "som".equals(projName) || "misrsom".equals(projName) || "ob_tran".equals(projName);
    }

    /**
     * The ellipsoid the operation line declares, resolved by the real parser.
     * <p>
     * {@code merc} is used as the stand-in because it is registered, needs nothing but an
     * ellipsoid, and never stomps one — unlike {@code alsk}, {@code gs48} or {@code gs50}, which
     * replace {@code a} and {@code es} on purpose.
     */
    private static Ellipsoid ellipsoidOf(String[] args) {
        String[] copy = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            String bare = args[i].startsWith("+") ? args[i].substring(1) : args[i];
            copy[i] = bare.startsWith("proj=") ? "+proj=merc" : args[i];
        }
        return new CRSFactory().createFromParameters("gie-ellipsoid", copy)
                .getProjection().getEllipsoid();
    }

    /**
     * An angular parameter in degrees, accepting everything {@code pj_param}'s {@code "r"} sigil
     * does: decimal degrees, DMS, a trailing cardinal, and the {@code r}/{@code R} radian suffix.
     * The corpus uses two of those for {@code som} — {@code +inc_angle=98.30382} and
     * {@code +inc_angle=1.7157253262878522r} name the same angle in two blocks.
     */
    private static double angleDegrees(String value) {
        int length = value.length();
        if (length > 1) {
            char last = value.charAt(length - 1);
            if (last == 'r' || last == 'R') {
                return Math.toDegrees(Double.parseDouble(value.substring(0, length - 1)));
            }
        }
        return Angle.parse(value);
    }

    private static Map<String, String> parameterMap(String[] args) {
        Map<String, String> params = new LinkedHashMap<String, String>();
        for (String arg : args) {
            String bare = arg.startsWith("+") ? arg.substring(1) : arg;
            if (bare.isEmpty()) {
                continue;
            }
            int eq = bare.indexOf('=');
            String key = eq < 0 ? bare : bare.substring(0, eq);
            String value = eq < 0 ? null : bare.substring(eq + 1);
            if (!params.containsKey(key)) {
                params.put(key, value);
            }
        }
        return params;
    }

    // ------------------------------------------------------------------------------------------
    // Running
    // ------------------------------------------------------------------------------------------

    /**
     * Runs every row of every block for {@code projName} in {@code file} and asserts all of them.
     *
     * @param expectedAssertions the number of assertions the corpus is expected to contribute,
     *        asserted so that a silently-empty run cannot masquerade as a pass
     */
    static void assertAll(String file, String projName, int expectedAssertions) {
        List<Block> blocks = blocksFor(file, projName);
        assertTrue("no " + projName + " operation found in " + file
                + " -- the corpus moved or the whole-token match is wrong", !blocks.isEmpty());

        int total = 0;
        List<String> failures = new ArrayList<String>();
        for (Block block : blocks) {
            total += block.assertionCount();
            failures.addAll(run(block));
        }
        assertTrue(projName + ": expected " + expectedAssertions + " corpus assertions, found "
                + total + " -- a changed count means the corpus moved or the reader dropped rows",
                total == expectedAssertions);
        if (!failures.isEmpty()) {
            fail(projName + ": " + failures.size() + " of " + total + " corpus assertions fail:"
                    + System.lineSeparator() + String.join(System.lineSeparator(), failures));
        }
    }

    /** Runs one block, returning one message per failing assertion. */
    static List<String> run(Block block) {
        List<String> failures = new ArrayList<String>();
        Projection p;
        try {
            p = build(block.operation);
        } catch (RuntimeException e) {
            // The operation could not be created. Every row in the block is satisfied if and only
            // if it is an `expect failure`; anything else is a genuine failure, and reporting it
            // as such is what stops "not implemented" from scoring as conformance.
            for (Row row : block.rows) {
                if (!row.expectFailure) {
                    failures.add("  " + block.operation + " " + row
                            + ": operation could not be created: "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
            return failures;
        }

        GieComparator cmp = comparatorFor(p);
        for (Row row : block.rows) {
            if (row.expectFailure) {
                if (row.accept == null) {
                    failures.add("  " + block.operation + " " + row
                            + ": expected the operation to be rejected, but it was created as "
                            + p.getClass().getSimpleName());
                    continue;
                }
                try {
                    step(p, new ProjCoordinate(row.accept[0], row.accept[1]), row.inverse);
                    failures.add("  " + block.operation + " " + row
                            + ": expected failure, but the transform returned a coordinate");
                } catch (Proj4jException expected) {
                    // Pass: a failure was expressed as a failure.
                }
                continue;
            }
            double deviation;
            try {
                deviation = row.isRoundtrip() ? roundtripResidual(cmp, p, row)
                        : oneWayDeviation(cmp, p, row);
            } catch (RuntimeException e) {
                failures.add("  " + block.operation + " " + row + ": threw "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                continue;
            }
            if (!GieComparator.withinTolerance(deviation, row.toleranceMetres)) {
                failures.add("  " + block.operation + " " + row + ": deviation " + deviation
                        + " m exceeds tolerance " + row.toleranceMetres + " m");
            }
        }
        return failures;
    }

    /** The geodesic figure for the metric, read from the constructed projection. */
    private static GieComparator comparatorFor(Projection p) {
        double a = p.getEllipsoid().equatorRadius;
        double b = p.getEllipsoid().poleRadius;
        return GieComparator.forEllipsoid(a, a == 0.0 ? 0.0 : (a - b) / a);
    }

    /** A bare projection operation is {@code left = RADIANS, right = CLASSIC}. */
    private static GieIoUnits outputUnits(boolean inverse) {
        return GieIoUnits.outputUnits(GieIoUnits.RADIANS, GieIoUnits.CLASSIC, false,
                inverse ? GieDirection.INVERSE : GieDirection.FORWARD);
    }

    private static double oneWayDeviation(GieComparator cmp, Projection p, Row row) {
        ProjCoordinate in = new ProjCoordinate(row.accept[0], row.accept[1]);
        ProjCoordinate out = new ProjCoordinate();
        double[] expected;
        double[] got;
        if (row.inverse) {
            p.inverseProject(in, out);
            // gie's torad_coord on the expect line; PROJ's raw inverse returns radians.
            expected = new double[] {Math.toRadians(row.expect[0]), Math.toRadians(row.expect[1]),
                    0, 0};
            got = new double[] {Math.toRadians(out.x), Math.toRadians(out.y), 0, 0};
        } else {
            p.project(in, out);
            expected = new double[] {row.expect[0], row.expect[1], 0, 0};
            got = new double[] {out.x, out.y, 0, 0};
        }
        return cmp.compare(outputUnits(row.inverse), false, expected, got, row.expect.length,
                row.toleranceMetres).deviation();
    }

    /**
     * {@code roundtrip n}, phased as {@code src/trans.cpp:591}: one half-step out, {@code n-1}
     * full cycles, one half-step home, so the residual is measured in the input space.
     */
    private static double roundtripResidual(GieComparator cmp, Projection p, Row row) {
        final double x0 = row.accept[0];
        final double y0 = row.accept[1];
        ProjCoordinate t = step(p, new ProjCoordinate(x0, y0), row.inverse);
        for (int i = 1; i < row.roundtripTrips; i++) {
            t = step(p, step(p, t, !row.inverse), row.inverse);
        }
        t = step(p, t, !row.inverse);

        final boolean angularResidual = !row.inverse;
        double[] expected;
        double[] got;
        if (angularResidual) {
            expected = new double[] {Math.toRadians(x0), Math.toRadians(y0), 0, 0};
            got = new double[] {Math.toRadians(t.x), Math.toRadians(t.y), 0, 0};
        } else {
            expected = new double[] {x0, y0, 0, 0};
            got = new double[] {t.x, t.y, 0, 0};
        }
        return cmp.compare(outputUnits(!angularResidual), false, expected, got, 2,
                row.toleranceMetres).deviation();
    }

    private static ProjCoordinate step(Projection p, ProjCoordinate src, boolean inverse) {
        ProjCoordinate dst = new ProjCoordinate();
        if (inverse) {
            p.inverseProject(src, dst);
        } else {
            p.project(src, dst);
        }
        return dst;
    }
}

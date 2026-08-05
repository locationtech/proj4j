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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.parser.Proj4Keyword;
import org.locationtech.proj4j.proj.AdamsHemisphereProjection;
import org.locationtech.proj4j.proj.AdamsWorldInASquareIIProjection;
import org.locationtech.proj4j.proj.AdamsWorldInASquareIProjection;
import org.locationtech.proj4j.proj.GuyouProjection;
import org.locationtech.proj4j.proj.PeirceQuincuncialProjection;
import org.locationtech.proj4j.proj.Projection;
import org.locationtech.proj4j.proj.SpilhausProjection;

/**
 * Registration of the six new operators, and — since the parser gap was closed — the proof that
 * all six are drivable from a proj-string.
 *
 * <h2>What the gap was</h2>
 *
 * <p>{@code Proj4Parser.parseProjection} used to have no dispatch for {@code +shape},
 * {@code +scrollx}, {@code +scrolly}, {@code +azi} or {@code +rot}. Because
 * {@code ParseMode.PROJ_COMPATIBLE} retains and ignores an unrecognised key — which is
 * PROJ-faithful and correct — {@code +proj=peirce_q +shape=square} parsed cleanly and then
 * projected as a <b>diamond</b>: a silent wrong answer. All 29 {@code operation} lines in
 * {@code peirce_q.gie} carry {@code +shape}, so its 592 assertions were unreachable, as were 8 of
 * {@code spilhaus.gie}'s.
 *
 * <p>The assertions in this file used to pin that wrong-but-silent behaviour in place so that
 * closing the gap would fail a test rather than passing quietly. <b>They are now inverted</b>: the
 * five keys are dispatched by {@code Proj4Parser}, listed by
 * {@code Proj4Keyword.supportedParameters()}, and classified {@code HONOURED} by the conformance
 * bridge's {@code Proj4jCapabilities}. Those three files must move together — the bridge asserts
 * that every allow-list key is classified.
 *
 * <p>{@link #everyAssertionInPeirceQAndSpilhausHoldsThroughTheParser()} is the load-bearing test
 * of the three: it runs the whole of {@code peirce_q.gie} and {@code spilhaus.gie} through
 * {@link CRSFactory}, so a future regression in the dispatch shows up as 600 failing assertions
 * rather than as a quietly-defaulted parameter. {@link AdamsFamilyCorpusTest} runs the same rows
 * through {@link AdamsOperations}, which sets the parameters directly; the two together separate
 * "the arithmetic is wrong" from "the parser did not deliver the parameter".
 */
public class AdamsRegistrationAndParserGapTest {

    private final Registry registry = new Registry();
    private final CRSFactory factory = new CRSFactory();

    /** All six resolve by their PROJ {@code +proj=} name. */
    @Test
    public void allSixAreRegisteredUnderTheirProjNames() {
        assertEquals(GuyouProjection.class, registry.getProjection("guyou").getClass());
        assertEquals(PeirceQuincuncialProjection.class,
                registry.getProjection("peirce_q").getClass());
        assertEquals(AdamsHemisphereProjection.class,
                registry.getProjection("adams_hemi").getClass());
        assertEquals(AdamsWorldInASquareIProjection.class,
                registry.getProjection("adams_ws1").getClass());
        assertEquals(AdamsWorldInASquareIIProjection.class,
                registry.getProjection("adams_ws2").getClass());
        assertEquals(SpilhausProjection.class, registry.getProjection("spilhaus").getClass());
    }

    /** And through {@code CRSFactory}, which is how a caller actually reaches them. */
    @Test
    public void allSixParseAsProjStrings() {
        for (String name : new String[] {
                "guyou", "peirce_q", "adams_hemi", "adams_ws1", "adams_ws2", "spilhaus"}) {
            CoordinateReferenceSystem crs =
                    factory.createFromParameters(name, "+proj=" + name + " +R=6370997");
            assertNotNull(name, crs);
            assertEquals(name, name, crs.getProjection().getName());
        }
    }

    /**
     * {@code spilhaus}'s non-zero {@code lon_0}/{@code lat_0} defaults survive the parser, and an
     * explicit value overrides them.
     */
    @Test
    public void spilhausDefaultsSurviveTheParserAndAreOverridable() {
        Projection defaulted = factory
                .createFromParameters("s", "+proj=spilhaus +R=6378137").getProjection();
        assertEquals(SpilhausProjection.DEFAULT_LON_0_DEGREES,
                defaulted.getProjectionLongitudeDegrees(), 1e-9);
        assertEquals(SpilhausProjection.DEFAULT_LAT_0_DEGREES,
                defaulted.getProjectionLatitudeDegrees(), 1e-9);

        Projection overridden = factory.createFromParameters("s",
                "+proj=spilhaus +R=6378137 +lon_0=0 +lat_0=0").getProjection();
        assertEquals(0.0, overridden.getProjectionLongitudeDegrees(), 0.0);
        assertEquals(0.0, overridden.getProjectionLatitudeDegrees(), 0.0);
    }

    /**
     * <b>Inverted.</b> {@code +shape=square} now builds a square, and the absence of
     * {@code +shape} still means {@code diamond} ({@code adams.cpp:408-410}).
     */
    @Test
    public void shapeIsHonouredByTheParser() {
        PeirceQuincuncialProjection square = (PeirceQuincuncialProjection) factory
                .createFromParameters("p", "+proj=peirce_q +R=6370997 +shape=square")
                .getProjection();
        assertEquals(PeirceQuincuncialProjection.Shape.SQUARE, square.getShape());
        assertEquals(0.0, square.getScrollX(), 0.0);
        assertEquals(0.0, square.getScrollY(), 0.0);

        PeirceQuincuncialProjection defaulted = (PeirceQuincuncialProjection) factory
                .createFromParameters("p", "+proj=peirce_q +R=6370997").getProjection();
        assertEquals("no +shape still means diamond, not square",
                PeirceQuincuncialProjection.Shape.DIAMOND, defaulted.getShape());
    }

    /** <b>Inverted.</b> Likewise {@code +azi} and {@code +rot} on {@code spilhaus}. */
    @Test
    public void aziAndRotAreHonouredByTheParser() {
        SpilhausProjection p = (SpilhausProjection) factory
                .createFromParameters("s", "+proj=spilhaus +R=6378137 +azi=9.1 +rot=40.1")
                .getProjection();
        assertEquals(9.1 * Math.PI / 180.0, p.getAzi(), 1e-15);
        assertEquals(40.1 * Math.PI / 180.0, p.getRot(), 1e-15);

        SpilhausProjection defaulted = (SpilhausProjection) factory
                .createFromParameters("s", "+proj=spilhaus +R=6378137").getProjection();
        assertEquals(SpilhausProjection.DEFAULT_AZI_DEGREES * Math.PI / 180.0,
                defaulted.getAzi(), 1e-15);
        assertEquals(SpilhausProjection.DEFAULT_ROT_DEGREES * Math.PI / 180.0,
                defaulted.getRot(), 1e-15);
    }

    /**
     * <b>Inverted.</b> All five keys are in {@code Proj4Keyword.supportedParameters()}, which is
     * what {@code ParseMode.STRICT} enforces and what the conformance bridge's capability table is
     * asserted against.
     *
     * <p>{@code azi} was the interesting one: it was already declared as a {@code Proj4Keyword}
     * <em>constant</em> and was simply never added to the set and never read.
     */
    @Test
    public void theFiveKeysAreInTheAllowList() {
        for (String key : new String[] {"shape", "scrollx", "scrolly", "azi", "rot"}) {
            assertTrue("+" + key + " must be in the allow-list, or ParseMode.STRICT rejects a "
                            + "definition the parser now honours",
                    Proj4Keyword.supportedParameters().contains(key));
        }
    }

    /**
     * Retain-and-ignore is still the rule for keys nobody reads, which is what
     * {@code init.cpp} does and what {@code builtins.gie}'s non-strict block requires.
     */
    @Test
    public void genuinelyUnrecognisedKeysStillDoNotFailTheParse() {
        assertNotNull(factory.createFromParameters("p",
                "+proj=peirce_q +R=6370997 +shape=horizontal +unknown_keyword=1"));
    }

    // ------------------------------------------------------------------ the corpus, via the parser

    /**
     * The two files the gap made unreachable, run end to end through {@link CRSFactory}: 592
     * assertions in {@code peirce_q.gie} and 133 in {@code spilhaus.gie}.
     *
     * <p>The expected values are dense point grids read from the vendored corpus rather than
     * transcribed. The metric follows {@link AdamsFamilyCorpusTest}: Euclidean metres against a
     * projected target, great-circle metres against an angular one, and {@code !(d <= tol)} so
     * that {@code NaN} fails rather than passing.
     */
    @Test
    public void everyAssertionInPeirceQAndSpilhausHoldsThroughTheParser() {
        check("peirce_q.gie", 545, 47);
        check("spilhaus.gie", 74, 59);
    }

    /**
     * Every distinct {@code operation} in the two files is accepted by the parser, and the
     * {@code peirce_q} ones cover all six shapes plus both scroll variants — so
     * {@link #everyAssertionInPeirceQAndSpilhausHoldsThroughTheParser()} cannot pass while
     * silently exercising only the default arrangement.
     */
    @Test
    public void everyOperationInTheTwoFilesParsesAndTheShapesAreAllCovered() {
        Set<PeirceQuincuncialProjection.Shape> seen =
                new LinkedHashSet<PeirceQuincuncialProjection.Shape>();
        int scrolled = 0;
        for (String operation : operations("peirce_q.gie")) {
            PeirceQuincuncialProjection p =
                    (PeirceQuincuncialProjection) build(operation).getProjection();
            seen.add(p.getShape());
            if (p.getScrollX() != 0.0 || p.getScrollY() != 0.0) {
                scrolled++;
            }
        }
        assertEquals("all six +shape arrangements appear in peirce_q.gie and must all arrive",
                PeirceQuincuncialProjection.Shape.values().length, seen.size());
        assertEquals("peirce_q.gie has one +scrollx block and one +scrolly block", 2, scrolled);

        for (String operation : operations("spilhaus.gie")) {
            assertNotNull(operation, build(operation));
        }
    }

    private void check(String file, int expectedExpectRows, int expectedRoundtripRows) {
        List<GieCorpus.Row> rows = GieCorpus.read(file);
        Map<String, Projection> cache = new HashMap<String, Projection>();
        List<String> failures = new ArrayList<String>();
        int expectRows = 0;
        int roundtripRows = 0;

        for (GieCorpus.Row row : rows) {
            if (row.kind == GieCorpus.NO_EXPECTATION && row.roundtrip == 0) {
                continue;
            }
            Projection projection = cache.get(row.operation);
            if (projection == null) {
                projection = build(row.operation).getProjection();
                cache.put(row.operation, projection);
            }
            if (row.kind != GieCorpus.NO_EXPECTATION) {
                expectRows++;
                add(failures, checkExpect(projection, row));
            }
            if (row.roundtrip > 0) {
                roundtripRows++;
                add(failures, checkRoundtrip(projection, row));
            }
        }

        assertEquals(file + ": expect row count", expectedExpectRows, expectRows);
        assertEquals(file + ": roundtrip row count", expectedRoundtripRows, roundtripRows);
        if (!failures.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(file).append(": ").append(failures.size()).append(" of ")
                    .append(expectRows + roundtripRows)
                    .append(" assertions failed through Proj4Parser. First 25:\n");
            for (int i = 0; i < failures.size() && i < 25; i++) {
                sb.append("  ").append(failures.get(i)).append('\n');
            }
            throw new AssertionError(sb.toString());
        }
    }

    private static void add(List<String> failures, String failure) {
        if (failure != null) {
            failures.add(failure);
        }
    }

    /**
     * The whole point: the definition goes through {@code Proj4Parser} and nothing else.
     *
     * <p>The one addition is PROJ's own implicit {@code +ellps=GRS80}
     * ({@code init.cpp:317-360}), appended when the definition names no datum, ellipsoid, size or
     * shape. Six of {@code spilhaus.gie}'s thirteen blocks rely on it, and {@code spilhaus} is the
     * one operator in this family that keeps its eccentricity, so GRS80's {@code rf} versus
     * proj4j's own default is worth a few tenths of a millimetre against a 1.5 mm tolerance —
     * i.e. enough to matter. {@code Proj4Parser} does not model the implicit append itself; the
     * conformance bridge does, in {@code GieProjArgs.impliesGrs80()}.
     */
    private CoordinateReferenceSystem build(String operation) {
        return factory.createFromParameters("gie", withImplicitGrs80(operation));
    }

    /** {@code init.cpp:317-360}'s suppression list, verbatim. */
    private static final String[] SUPPRESSES_IMPLICIT_GRS80 = {
            "no_defs", "datum", "ellps", "a", "b", "rf", "f", "e", "es", "R"};

    private static String withImplicitGrs80(String operation) {
        for (String token : operation.trim().split("\\s+")) {
            String kv = token.startsWith("+") ? token.substring(1) : token;
            int eq = kv.indexOf('=');
            String key = eq < 0 ? kv : kv.substring(0, eq);
            for (String suppressor : SUPPRESSES_IMPLICIT_GRS80) {
                if (suppressor.equals(key)) {
                    return operation;
                }
            }
        }
        return operation + " +ellps=GRS80";
    }

    /** The distinct {@code operation} definitions of a {@code .gie} file, in order. */
    private static Set<String> operations(String file) {
        Set<String> operations = new LinkedHashSet<String>();
        for (GieCorpus.Row row : GieCorpus.read(file)) {
            operations.add(row.operation);
        }
        return operations;
    }

    private static String checkExpect(Projection projection, GieCorpus.Row row) {
        ProjCoordinate src = new ProjCoordinate(row.acceptX, row.acceptY);
        ProjCoordinate dst = new ProjCoordinate(Double.NaN, Double.NaN);
        RuntimeException raised = null;
        try {
            if (row.inverse) {
                projection.inverseProject(src, dst);
            } else {
                projection.project(src, dst);
            }
        } catch (RuntimeException e) {
            raised = e;
        }

        if (row.kind == GieCorpus.FAILURE) {
            return raised != null ? null
                    : row + ": expected failure but got " + dst.x + " " + dst.y;
        }
        if (raised != null) {
            return row + ": expected " + row.expectX + " " + row.expectY + " but raised "
                    + raised.getClass().getSimpleName() + ": " + raised.getMessage();
        }
        double d = row.inverse
                ? greatCircleMetres(projection, dst.x, dst.y, row.expectX, row.expectY)
                : Math.hypot(dst.x - row.expectX, dst.y - row.expectY);
        if (!(d <= row.toleranceMetres)) {
            return row + ": got " + dst.x + " " + dst.y + ", expected " + row.expectX + " "
                    + row.expectY + " - deviation " + (d * 1000) + " mm, tolerance "
                    + (row.toleranceMetres * 1000) + " mm";
        }
        return null;
    }

    private static String checkRoundtrip(Projection projection, GieCorpus.Row row) {
        boolean forward = !row.inverse;
        ProjCoordinate t = new ProjCoordinate(row.acceptX, row.acceptY);
        try {
            t = step(projection, t, forward);
            for (int i = 1; i < row.roundtrip; i++) {
                t = step(projection, t, !forward);
                t = step(projection, t, forward);
            }
            t = step(projection, t, !forward);
        } catch (RuntimeException e) {
            return row + ": roundtrip " + row.roundtrip + " raised "
                    + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        double d = forward
                ? greatCircleMetres(projection, row.acceptX, row.acceptY, t.x, t.y)
                : Math.hypot(t.x - row.acceptX, t.y - row.acceptY);
        if (!(d <= row.toleranceMetres)) {
            return row + ": roundtrip " + row.roundtrip + " returned " + t.x + " " + t.y
                    + ", deviation " + (d * 1000) + " mm, tolerance "
                    + (row.toleranceMetres * 1000) + " mm";
        }
        return null;
    }

    private static ProjCoordinate step(Projection projection, ProjCoordinate in, boolean forward) {
        ProjCoordinate out = new ProjCoordinate(Double.NaN, Double.NaN);
        if (forward) {
            projection.project(in, out);
        } else {
            projection.inverseProject(in, out);
        }
        return out;
    }

    private static double greatCircleMetres(Projection projection, double lon1, double lat1,
            double lon2, double lat2) {
        double r = projection.getEquatorRadius();
        double p1 = lat1 * Math.PI / 180.0;
        double p2 = lat2 * Math.PI / 180.0;
        double dp = p2 - p1;
        double dl = (lon2 - lon1) * Math.PI / 180.0;
        double s = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(s)));
    }
}

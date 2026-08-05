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
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.proj.Projection;

/**
 * Runs the whole of PROJ 9.8.1's own corpus for the six adams-family operators against this
 * implementation: 3,443 {@code expect} rows plus 115 {@code roundtrip} rows across
 * {@code adams_hemi.gie}, {@code adams_ws1.gie}, {@code adams_ws2.gie}, {@code guyou.gie},
 * {@code peirce_q.gie} and {@code spilhaus.gie} — 49% of every {@code expect} assertion in
 * {@code test/gie}.
 *
 * <p>The expected values are dense point grids, so they are read from the vendored
 * {@code .gie} files rather than transcribed; see {@link GieCorpus} for why this reader is
 * local rather than the conformance module's.
 *
 * <h2>The metric</h2>
 *
 * <p>{@code gie} chooses among four residual metrics and applying the wrong one inflates the
 * distance by up to 111,319x. Two of the four occur here:
 *
 * <ul>
 * <li><b>Euclidean, in metres</b>, for every {@code expect} row: the target of a forward
 *     projection is a projected coordinate, so the residual is {@code hypot(dx, dy)}.
 * <li><b>Geodesic, on the input side</b>, for every {@code roundtrip} row: {@code gie} picks
 *     the metric from {@code proj_angular_input}, which for a forward roundtrip is the
 *     <em>angular</em> lat/lon side. A great-circle distance on the operation's own radius
 *     stands in for the geodesic here; the two differ by at most 0.5% of the measured
 *     distance, and a converged roundtrip measures microns, so 0.5% of it is nowhere near any
 *     tolerance in these files.
 * </ul>
 *
 * <p>The comparison is written {@code !(d <= tol)} rather than {@code d > tol} so that
 * {@code NaN} and {@code +inf} both fail rather than silently passing.
 *
 * <h2>{@code roundtrip} phasing</h2>
 *
 * <p>{@code src/trans.cpp:591}: one forward, then {@code n-1} inverse-forward pairs, then one
 * final inverse — {@code n} of each, arranged so the result is comparable with the original
 * input. Half-stepping it the obvious way instead compares a projected coordinate with a
 * geographic one.
 *
 * <h2>What "pass" means here, and what it does not</h2>
 *
 * <p>An {@code expect failure} row passes when the transform raises. It does <em>not</em>
 * require a matching errno: proj4j's exception taxonomy is coarser than PROJ's 17 constants,
 * and the conformance harness makes the same allowance. What it does require is that the
 * failure is a raise and not a plausible-looking coordinate — a returned {@code NaN} counts
 * as a failed assertion, not a passed one.
 */
@RunWith(Parameterized.class)
public class AdamsFamilyCorpusTest {

    /** The six files, with the row counts the corpus is known to contain. */
    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> files() {
        return Arrays.asList(new Object[][] {
                // file, expect rows, roundtrip rows
                {"adams_hemi.gie", 703, 0},
                {"adams_ws1.gie", 703, 0},
                {"adams_ws2.gie", 713, 9},
                {"guyou.gie", 705, 0},
                {"peirce_q.gie", 545, 47},
                {"spilhaus.gie", 74, 59},
        });
    }

    private final String file;
    private final int expectedExpectRows;
    private final int expectedRoundtripRows;

    public AdamsFamilyCorpusTest(String file, int expectedExpectRows, int expectedRoundtripRows) {
        this.file = file;
        this.expectedExpectRows = expectedExpectRows;
        this.expectedRoundtripRows = expectedRoundtripRows;
    }

    @Test
    public void everyAssertionInTheCorpusHolds() {
        List<GieCorpus.Row> rows = GieCorpus.read(file);

        int expectRows = 0;
        int roundtripRows = 0;
        int passed = 0;
        List<String> failures = new ArrayList<String>();
        Map<String, Projection> cache = new HashMap<String, Projection>();

        for (GieCorpus.Row row : rows) {
            if (row.kind == GieCorpus.NO_EXPECTATION && row.roundtrip == 0) {
                // An accept whose expect is commented out: not an assertion. The corpus has 49.
                continue;
            }
            Projection projection = cache.get(row.operation);
            if (projection == null) {
                projection = AdamsOperations.build(row.operation);
                cache.put(row.operation, projection);
            }

            if (row.kind != GieCorpus.NO_EXPECTATION) {
                expectRows++;
                String failure = checkExpect(projection, row);
                if (failure == null) {
                    passed++;
                } else {
                    failures.add(failure);
                }
            }

            if (row.roundtrip > 0) {
                roundtripRows++;
                String rt = checkRoundtrip(projection, row);
                if (rt == null) {
                    passed++;
                } else {
                    failures.add(rt);
                }
            }
        }

        // A reader regression that silently dropped half the file would otherwise look like a
        // clean run.
        assertEquals(file + ": expect row count", expectedExpectRows, expectRows);
        assertEquals(file + ": roundtrip row count", expectedRoundtripRows, roundtripRows);

        if (!failures.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(file).append(": ").append(failures.size()).append(" of ")
                    .append(expectRows + roundtripRows).append(" assertions failed (")
                    .append(passed).append(" passed). First 25:\n");
            for (int i = 0; i < failures.size() && i < 25; i++) {
                sb.append("  ").append(failures.get(i)).append('\n');
            }
            throw new AssertionError(sb.toString());
        }
        assertEquals(file, expectRows + roundtripRows, passed);
    }

    /** The {@code expect} row: either a coordinate within tolerance, or a raise. */
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
            if (raised != null) {
                return null;
            }
            return row + ": expected failure"
                    + (row.errno == null ? "" : " (" + row.errno + ")")
                    + " but got " + dst.x + " " + dst.y;
        }

        if (raised != null) {
            return row + ": expected " + row.expectX + " " + row.expectY + " but raised "
                    + raised.getClass().getSimpleName() + ": " + raised.getMessage();
        }
        // A NaN ordinate is never a pass, even against a NaN expectation: the corpus rows here
        // are all finite.
        double d = residualMetres(row.inverse, projection, dst.x, dst.y, row.expectX, row.expectY);
        if (!(d <= row.toleranceMetres)) {
            return row + ": got " + dst.x + " " + dst.y + ", expected " + row.expectX + " "
                    + row.expectY + " - deviation " + (d * 1000) + " mm, tolerance "
                    + (row.toleranceMetres * 1000) + " mm";
        }
        return null;
    }

    /**
     * {@code roundtrip n}: {@code n} forwards and {@code n} inverses, phased as
     * {@code trans.cpp:591} phases them, then the input-side metric against the original
     * {@code accept} coordinate.
     */
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

        // proj_angular_input: a forward roundtrip starts and ends on the angular side.
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

    /**
     * The Euclidean branch for a projected target, the geodesic branch for an angular one.
     * {@code inverse} rows have a lat/lon target, so their residual is angular.
     */
    private static double residualMetres(boolean inverse, Projection projection,
            double gotX, double gotY, double wantX, double wantY) {
        if (inverse) {
            return greatCircleMetres(projection, gotX, gotY, wantX, wantY);
        }
        return Math.hypot(gotX - wantX, gotY - wantY);
    }

    /**
     * Great-circle distance in metres between two lon/lat pairs in degrees, on the operation's
     * own radius. Stands in for {@code gie}'s geodesic; see the class comment.
     */
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

    /** The corpus is where it is claimed to be, and it is the size it is claimed to be. */
    @Test
    public void corpusIsPresentAndWhole() {
        assertTrue(GieCorpus.directory().resolve(file).toString(),
                GieCorpus.directory().resolve(file).toFile().isFile());
    }
}

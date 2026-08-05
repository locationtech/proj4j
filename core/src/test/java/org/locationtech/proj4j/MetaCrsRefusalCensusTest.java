/*******************************************************************************
 * Copyright 2026 Proj4J contributors
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
package org.locationtech.proj4j;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.Test;
import org.locationtech.proj4j.io.MetaCRSTestCase;
import org.locationtech.proj4j.io.MetaCRSTestFileReader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Every row of {@code proj4-epsg.csv}, cross-tabulated: does Proj4J answer or refuse, and does
 * PROJ 9.8.1 answer or refuse?
 *
 * <h2>What this measures, and why a count rather than a coordinate</h2>
 *
 * <p>{@link MetaCRSTest#testPROJ4_Empirical()} asks whether each row hits its recorded
 * coordinate. It cannot ask the prior question &mdash; <em>should there be a coordinate at
 * all?</em> &mdash; because {@code failing} means both "wrong number" and "correctly refused".
 * This class asks only that prior question, over all 4,280 rows at once, and answers it against
 * PROJ.
 *
 * <p>The whole file is one probe: <b>every row transforms WGS 84 at (1&deg;E, 1&deg;S) into a
 * different target CRS</b>, one row per target, 4,280 distinct EPSG codes and no duplicates. That
 * point is in the Gulf of Guinea, so most targets are being exercised thousands of kilometres
 * outside their area of use &mdash; which is why the file is a good refusal census and a poor
 * accuracy census.
 *
 * <h2>The measurement</h2>
 *
 * <table>
 * <caption>4,280 rows, Proj4J in this working tree vs {@code cs2cs} 9.8.1</caption>
 * <tr><th></th><th>PROJ answers</th><th>PROJ refuses ({@code * * inf})</th></tr>
 * <tr><th>Proj4J answers</th><td>3,869</td><td><b>0</b></td></tr>
 * <tr><th>Proj4J refuses</th><td><b>131</b></td><td>280</td></tr>
 * </table>
 *
 * <p>By Proj4J's own {@link ErrorCause}, the 411 refusals are
 * <b>148 {@link ErrorCause#COORDINATE_OUTSIDE_GRID}</b> and
 * <b>263 {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN}</b>, and the 148 split
 * <b>131 + 17</b> across the two right-hand cells. All 148 are {@code +datum=NAD27}.
 *
 * <h2>How the PROJ column was taken</h2>
 *
 * <p>{@code cs2cs} <b>Rel. 9.8.1, April 10th, 2026</b>, one invocation per distinct target
 * definition (3,359 of them), fed <em>the strings Proj4J itself reads</em> out of
 * {@code proj4j-epsg}'s {@code proj4/nad/epsg} dictionary &mdash; never a bare {@code EPSG:}
 * code on the command line. That distinction is the difference between a measurement and a
 * category error:
 * {@code cs2cs} resolves codes through {@code proj.db} while Proj4J resolves them through an
 * EPSG v9.2-era init dictionary, and comparing the two resolutions compares two different CRSs.
 *
 * <pre>
 * echo "1.0 -1.0" | cs2cs -f "%.10f" +proj=longlat +datum=WGS84 +no_defs \
 *                          +to &lt;the target's dictionary string&gt;
 * </pre>
 *
 * <p>A line beginning with an asterisk (PROJ prints an asterisk, a tab, then {@code * inf}) is a
 * refusal; anything else is an answer. <b>The result is pinned here as {@link #PROJ_REFUSES},
 * a list of target codes, and no test in this repository shells out to {@code cs2cs}.</b>
 *
 * <h2>The 131, which are a layer difference and not a defect</h2>
 *
 * <p>All 131 are {@code +datum=NAD27} and all 131 are
 * {@link ErrorCause#COORDINATE_OUTSIDE_GRID}. Proj4J's legacy {@code +datum=}/{@code +nadgrids=}
 * path <em>is</em> PROJ's operator path, and at the operator level PROJ agrees:
 * {@code cct +proj=hgridshift +grids=conus} at a point no grid covers reports
 * {@code TRANSFORMATION ERROR (Coordinate to transform falls outside grid)}. At the <em>CRS</em>
 * level, with {@code proj.db} present, PROJ's operation factory notices the point is outside
 * NADCON's area of use and selects <i>"Ballpark geographic offset"</i> &mdash; a
 * <b>declared</b> no-op with a published accuracy, not a silent one. Proj4J has no such factory,
 * so it occupies the operator layer and refusing is faithful to that layer. Both statements are
 * true at once; neither is licence to return an undeclared coordinate.
 *
 * <h2>What each cell defends</h2>
 *
 * <ul>
 *   <li><b>(Proj4J answers, PROJ refuses) = 0</b> is the fail-open statement: nowhere does Proj4J
 *       invent a coordinate PROJ declines to produce. <b>It is not the guard on the grid fix.</b>
 *       Measured: with {@code Grid.shift}'s {@code throw outsideGrid(...)} short-circuited, this
 *       cell is <em>still</em> 0, because on 131 of those rows PROJ answers too and on the other
 *       17 the projection's own domain guard refuses downstream. Claiming this cell guards the
 *       grid fix would have been exactly the kind of assertion that reports a guarantee it never
 *       evaluates.</li>
 *   <li><b>(Proj4J refuses, PROJ answers) = 131, every one COORDINATE_OUTSIDE_GRID</b> is the
 *       guard on the grid fix, and it is checked by cause, not merely by count.</li>
 *   <li><b>148 / 263 by cause</b> pins which mechanism refused, so a refusal that migrated from
 *       one guard to another cannot hide inside a stable total.</li>
 * </ul>
 *
 * <h2>The positive control, measured rather than asserted</h2>
 *
 * <p>{@code Grid.shift}'s {@code throw outsideGrid(...)} was short-circuited in a scratch copy of
 * the tree &mdash; restoring the 1.4.3 fail-open &mdash; and the whole census re-run:
 *
 * <table>
 * <caption>census with the grid fail-open restored</caption>
 * <tr><th></th><th>fail-closed (this tree)</th><th>fail-open (1.4.3 behaviour)</th></tr>
 * <tr><td>rows answered</td><td>3,869</td><td><b>4,000</b></td></tr>
 * <tr><td>rows refused</td><td>411</td><td><b>280</b></td></tr>
 * <tr><td>{@code COORDINATE_OUTSIDE_GRID}</td><td>148</td><td><b>0</b></td></tr>
 * <tr><td>{@code COORDINATE_OUT_OF_DOMAIN}</td><td>263</td><td><b>280</b></td></tr>
 * <tr><td>(refuses, PROJ answers)</td><td>131</td><td><b>0</b></td></tr>
 * </table>
 *
 * <p>Five of the pinned numbers move, so the census fails five ways on a revert. The 17-row
 * difference between 148 and 131 is not slack: those 17 are {@code +proj=tmerc} zones roughly
 * 88&deg; from their central meridian, so with the grid guard removed they are refused by the
 * projection instead &mdash; which is why 263 becomes 280 and not 411.
 *
 * <p>{@link #theCensusDetectsAWrongCount()} and {@link #thePinnedProjColumnIsActuallyRead()} are
 * the in-repo halves of that control: they perturb the expectation and the pinned data
 * respectively and require the comparison to notice.
 *
 * @see MetaCRSTest for the per-row coordinate check and the {@code refuses} verdict it enforces
 * @see org.locationtech.proj4j.grids.OutsideGridFailsClosedTest for the operator-level fix itself
 */
public class MetaCrsRefusalCensusTest {

    // ------------------------------------------------------------------ the pinned expectation

    /** Rows in {@code proj4-epsg.csv}. */
    private static final int EXPECTED_ROWS = 4280;

    /** Proj4J returns a coordinate and {@code cs2cs} 9.8.1 returns a coordinate. */
    private static final int EXPECTED_BOTH_ANSWER = 3869;

    /**
     * Proj4J returns a coordinate and {@code cs2cs} 9.8.1 prints {@code * * inf}.
     *
     * <h4>Zero, and what that does and does not prove</h4>
     *
     * <p>Nowhere does Proj4J invent a coordinate PROJ declines to produce. See the class javadoc:
     * this cell is 0 under the 1.4.3 fail-open as well, so it is a standing fail-open statement
     * rather than the guard on the grid fix.
     */
    private static final int EXPECTED_PROJ4J_ANSWERS_PROJ_REFUSES = 0;

    /**
     * Proj4J refuses and {@code cs2cs} 9.8.1 answers: the declared-ballpark divergence.
     *
     * <h4>The guard on the grid fix</h4>
     *
     * <p>Every one of these is {@code +datum=NAD27} and
     * {@link ErrorCause#COORDINATE_OUTSIDE_GRID}; with the fail-open restored this cell measures
     * 0.
     */
    private static final int EXPECTED_PROJ4J_REFUSES_PROJ_ANSWERS = 131;

    /**
     * Both refuse. Proj4J's reasons: 263 out-of-domain, plus the 17 outside-grid rows PROJ
     * refuses too.
     */
    private static final int EXPECTED_BOTH_REFUSE = 280;

    /** Refusals attributed to the grid guard closed in Stage 9. */
    private static final int EXPECTED_OUTSIDE_GRID = 148;

    /** Refusals attributed to a projection's own domain guard. */
    private static final int EXPECTED_OUT_OF_DOMAIN = 263;

    /** The one source CRS every row uses. */
    private static final String SOURCE = "EPSG:4326";

    /** The one probe every row uses, and the point the pinned PROJ column was measured at. */
    private static final double PROBE_X = 1.0;
    private static final double PROBE_Y = -1.0;

    /**
     * The target EPSG codes for which {@code cs2cs} 9.8.1 refuses the probe.
     *
     * <h4>Pinned, not computed</h4>
     *
     * <p>280 codes, sorted. This is the only PROJ-derived data in the class and nothing here runs
     * {@code cs2cs}. It is keyed by target code because the file has exactly one row per target
     * and no duplicates &mdash; {@link #theFileIsStillTheProbeThisPinWasMeasuredAgainst()} checks
     * that, because the pin is void the moment the file stops being that probe.
     */
    private static final int[] PROJ_REFUSES = {
            2020, 2021, 2022, 2023, 2236, 2237, 2239, 2240, 2244, 2245, 2254, 2352, 2354, 2373,
            2375, 2404, 2406, 2425, 2427, 2544, 2546, 2604, 2606, 2662, 2664, 2720, 2722, 2759,
            2760, 2777, 2778, 2780, 2781, 2790, 2792, 2793, 2813, 2881, 2882, 2884, 2885, 2889,
            2890, 2899, 2965, 2966, 2967, 2968, 3106, 3435, 3443, 3465, 3466, 3511, 3512, 3516,
            3517, 3518, 3519, 3520, 3521, 3528, 3529, 3532, 3533, 3534, 3535, 3597, 3598, 4516,
            4518, 4537, 4539, 4655, 4766, 4785, 4787, 5266, 5292, 5293, 5294, 5295, 5296, 5299,
            5301, 5303, 5304, 5305, 5307, 5308, 5309, 5311, 5367, 5466, 5589, 5623, 5624, 5625,
            6355, 6356, 6437, 6438, 6442, 6443, 6444, 6445, 6446, 6447, 6454, 6455, 6458, 6459,
            6460, 6461, 6506, 6507, 7261, 7262, 7263, 7264, 7265, 7266, 7267, 7268, 7269, 7270,
            7271, 7272, 7273, 7274, 7275, 7276, 7277, 7278, 7279, 7280, 7281, 7282, 7283, 7284,
            7287, 7288, 7291, 7292, 7293, 7294, 7297, 7298, 7299, 7300, 7301, 7302, 7303, 7304,
            7305, 7306, 7307, 7308, 7309, 7310, 7311, 7312, 7313, 7314, 7315, 7316, 7317, 7318,
            7319, 7320, 7323, 7324, 7325, 7326, 7327, 7328, 7329, 7330, 7331, 7332, 7333, 7334,
            7335, 7336, 7337, 7338, 7339, 7340, 7341, 7342, 7343, 7344, 7345, 7346, 7347, 7348,
            7349, 7350, 7355, 7356, 7357, 7358, 7361, 7362, 7363, 7364, 7365, 7366, 7367, 7368,
            7532, 7535, 7541, 7542, 7546, 7547, 7554, 7555, 7560, 7561, 7563, 7574, 7582, 7583,
            7584, 7591, 7594, 7600, 7601, 7605, 7606, 7613, 7614, 7619, 7620, 7622, 7633, 7641,
            7642, 7643, 24891, 26729, 26730, 26758, 26759, 26766, 26767, 26771, 26773, 26774,
            26794, 26801, 26802, 26803, 26891, 26892, 26893, 26894, 26929, 26930, 26958, 26959,
            26966, 26967, 26971, 26973, 26974, 26994, 32066, 32067, 32076, 32077, 32166, 32167,
            32191, 32192, 32193, 32194, 32666, 32667
    };

    // ------------------------------------------------------------------------- the census

    /** One row's place in the 2&times;2. */
    private enum Cell {
        BOTH_ANSWER, PROJ4J_ANSWERS_PROJ_REFUSES, PROJ4J_REFUSES_PROJ_ANSWERS, BOTH_REFUSE
    }

    /** What the census measured, kept so several tests can share one 4,280-row run. */
    private static final class Census {
        final Map<Cell, List<String>> cells = new EnumMap<Cell, List<String>>(Cell.class);
        final Map<ErrorCause, List<String>> byCause =
                new EnumMap<ErrorCause, List<String>>(ErrorCause.class);
        final List<MetaCRSTestCase> rows;
        final List<MetaCRSTestCase.Result> results;

        Census(List<MetaCRSTestCase> rows, List<MetaCRSTestCase.Result> results) {
            this.rows = rows;
            this.results = results;
            for (Cell c : Cell.values()) {
                cells.put(c, new ArrayList<String>());
            }
        }

        int size(Cell c) {
            return cells.get(c).size();
        }

        int size(ErrorCause c) {
            List<String> l = byCause.get(c);
            return l == null ? 0 : l.size();
        }
    }

    private static Census census;

    /**
     * Runs all 4,280 rows once and cross-tabulates against {@code projRefuses}.
     *
     * @param projRefuses the set of target codes PROJ refuses; taken as a parameter rather than
     *                    read from the constant so a control can perturb it
     * @return the cross-tabulation
     */
    private static Census run(TreeSet<Integer> projRefuses) throws IOException {
        List<MetaCRSTestCase> rows = readRows();
        List<MetaCRSTestCase.Result> results = new ArrayList<MetaCRSTestCase.Result>(rows.size());
        CRSFactory factory = new CRSFactory();
        for (MetaCRSTestCase row : rows) {
            results.add(row.evaluate(factory));
        }
        return tabulate(rows, results, projRefuses);
    }

    private static Census tabulate(List<MetaCRSTestCase> rows,
                                   List<MetaCRSTestCase.Result> results,
                                   TreeSet<Integer> projRefuses) {
        Census out = new Census(rows, results);
        for (int i = 0; i < rows.size(); i++) {
            MetaCRSTestCase row = rows.get(i);
            MetaCRSTestCase.Result result = results.get(i);
            boolean proj4jRefused = result.outcome() == MetaCRSTestCase.Outcome.REFUSED;
            boolean projRefused = projRefuses.contains(targetCode(row));
            Cell cell = proj4jRefused
                    ? (projRefused ? Cell.BOTH_REFUSE : Cell.PROJ4J_REFUSES_PROJ_ANSWERS)
                    : (projRefused ? Cell.PROJ4J_ANSWERS_PROJ_REFUSES : Cell.BOTH_ANSWER);
            out.cells.get(cell).add(describe(row, result));
            if (proj4jRefused) {
                List<String> l = out.byCause.get(result.cause());
                if (l == null) {
                    l = new ArrayList<String>();
                    out.byCause.put(result.cause(), l);
                }
                l.add(describe(row, result));
            }
        }
        return out;
    }

    private static synchronized Census census() throws IOException {
        if (census == null) {
            census = run(pinnedProjRefusals());
        }
        return census;
    }

    private static TreeSet<Integer> pinnedProjRefusals() {
        TreeSet<Integer> set = new TreeSet<Integer>();
        for (int code : PROJ_REFUSES) {
            set.add(code);
        }
        return set;
    }

    // ------------------------------------------------------------------------- assertions

    /**
     * <b>The pin is only valid for the probe it was measured at.</b> Checked first and separately:
     * if {@code proj4-epsg.csv} stops being "WGS 84 at (1, -1) into 4,280 distinct targets", every
     * number in this class is stale, and the right outcome is a loud failure here rather than a
     * quiet comparison against the wrong thing.
     *
     * <p>That regeneration has since happened &mdash; column 1 and 898 expected coordinates were
     * rewritten against {@code cs2cs} 9.8.1 &mdash; and this assertion is the reason it could be
     * done without invalidating the class: the source CRS, the probe point, the row count and the
     * set of target codes are all unchanged, so none of the pinned numbers moved.
     */
    @Test
    public void theFileIsStillTheProbeThisPinWasMeasuredAgainst() throws IOException {
        List<MetaCRSTestCase> rows = readRows();
        assertEquals("row count", EXPECTED_ROWS, rows.size());

        TreeSet<Integer> targets = new TreeSet<Integer>();
        for (MetaCRSTestCase row : rows) {
            assertEquals("every row's source CRS", SOURCE, row.getSourceCrsName());
            ProjCoordinate src = row.getSourceCoordinate();
            assertEquals("every row's probe x", PROBE_X, src.x, 0.0);
            assertEquals("every row's probe y", PROBE_Y, src.y, 0.0);
            assertTrue("target codes must be unique -- the PROJ column is keyed by them: "
                    + row.getTargetCrsName(), targets.add(targetCode(row)));
        }
        assertEquals("distinct targets", EXPECTED_ROWS, targets.size());

        // ...and every pinned code is a code the file actually contains, so a stale or mistyped
        // entry cannot sit in PROJ_REFUSES contributing nothing and being counted as agreement.
        assertEquals("pinned PROJ refusals", EXPECTED_BOTH_REFUSE
                + EXPECTED_PROJ4J_ANSWERS_PROJ_REFUSES, PROJ_REFUSES.length);
        List<Integer> orphans = new ArrayList<Integer>();
        int previous = Integer.MIN_VALUE;
        for (int code : PROJ_REFUSES) {
            assertTrue("PROJ_REFUSES must be sorted and duplicate-free, at " + code,
                    code > previous);
            previous = code;
            if (!targets.contains(code)) {
                orphans.add(code);
            }
        }
        assertEquals("pinned codes not present in the CSV: " + orphans, 0, orphans.size());
    }

    /**
     * The census itself: the 2&times;2, the cause breakdown, and the identity of the 131.
     */
    @Test
    public void theRefusalCensusMatchesProj981() throws IOException {
        Census c = census();

        List<String> problems = compare(c);
        if (!problems.isEmpty()) {
            fail(report(c, problems));
        }

        // The 131 are a single, named phenomenon -- not an assorted 131. Checked by cause and by
        // definition string, so a different row drifting into the cell cannot keep the count.
        for (String row : c.cells.get(Cell.PROJ4J_REFUSES_PROJ_ANSWERS)) {
            assertTrue("every row where Proj4J refuses and PROJ answers must be the NAD27 "
                    + "declared-ballpark divergence, but this one is not: " + row,
                    row.contains(ErrorCause.COORDINATE_OUTSIDE_GRID.name()));
        }
        assertEquals("all 131 must be +datum=NAD27",
                EXPECTED_PROJ4J_REFUSES_PROJ_ANSWERS,
                countWithDatumNad27(c.cells.get(Cell.PROJ4J_REFUSES_PROJ_ANSWERS)));
        assertEquals("and so must the 17 in the both-refuse cell that came from the grid guard",
                EXPECTED_OUTSIDE_GRID,
                countWithDatumNad27(c.byCause.get(ErrorCause.COORDINATE_OUTSIDE_GRID)));
    }

    /**
     * No refusal is attributed to a cause this census has not accounted for. Without this, a new
     * failure mode could appear, displace an equal number of rows from an accounted cause, and
     * leave every total above unchanged.
     */
    @Test
    public void everyRefusalIsOneOfTheTwoAccountedCauses() throws IOException {
        Census c = census();
        Map<ErrorCause, Integer> unexpected = new LinkedHashMap<ErrorCause, Integer>();
        for (Map.Entry<ErrorCause, List<String>> e : c.byCause.entrySet()) {
            if (e.getKey() != ErrorCause.COORDINATE_OUTSIDE_GRID
                    && e.getKey() != ErrorCause.COORDINATE_OUT_OF_DOMAIN) {
                unexpected.put(e.getKey(), e.getValue().size());
            }
        }
        assertEquals("refusals with an unaccounted cause: " + unexpected, 0, unexpected.size());
        assertEquals(EXPECTED_OUTSIDE_GRID, c.size(ErrorCause.COORDINATE_OUTSIDE_GRID));
        assertEquals(EXPECTED_OUT_OF_DOMAIN, c.size(ErrorCause.COORDINATE_OUT_OF_DOMAIN));
        assertEquals("the two causes must account for every refusal",
                EXPECTED_PROJ4J_REFUSES_PROJ_ANSWERS + EXPECTED_BOTH_REFUSE,
                c.size(ErrorCause.COORDINATE_OUTSIDE_GRID)
                        + c.size(ErrorCause.COORDINATE_OUT_OF_DOMAIN));
    }

    // -------------------------------------------------------------------- positive controls

    /**
     * <b>Control 1: the census can fail.</b> A comparison that cannot reject anything reports
     * exactly what you hoped for, silently. Here the measured census is compared against an
     * expectation that is wrong by one in each cell in turn, and every one of those comparisons
     * must produce a complaint naming that cell.
     */
    @Test
    public void theCensusDetectsAWrongCount() throws IOException {
        Census c = census();
        assertTrue("the unperturbed comparison must be clean", compare(c).isEmpty());

        for (Cell cell : Cell.values()) {
            for (int delta : new int[]{-1, +1}) {
                List<String> problems = compare(c, expectationsWith(cell, delta));
                assertEquals("perturbing " + cell + " by " + delta
                        + " must produce exactly one complaint, got " + problems,
                        1, problems.size());
                assertTrue("and it must name the cell: " + problems.get(0),
                        problems.get(0).contains(cell.name()));
            }
        }
    }

    /**
     * <b>Control 2: the pinned PROJ column is actually consulted.</b> A pinned table that no code
     * path reads produces the right totals by construction. Moving one code out of
     * {@link #PROJ_REFUSES} must move exactly one row across the table, in the direction the code
     * belongs to &mdash; which also proves the join is by target code and lands on the row
     * intended.
     */
    @Test
    public void thePinnedProjColumnIsActuallyRead() throws IOException {
        Census c = census();

        // A code in the both-refuse cell: Proj4J refuses it too, so dropping it must move one row
        // from BOTH_REFUSE to PROJ4J_REFUSES_PROJ_ANSWERS.
        int bothRefuse = firstCodeIn(c, Cell.BOTH_REFUSE);
        TreeSet<Integer> without = pinnedProjRefusals();
        assertTrue("the control needs a code that is really pinned", without.remove(bothRefuse));
        Census perturbed = tabulate(c.rows, c.results, without);
        assertEquals(EXPECTED_BOTH_REFUSE - 1, perturbed.size(Cell.BOTH_REFUSE));
        assertEquals(EXPECTED_PROJ4J_REFUSES_PROJ_ANSWERS + 1,
                perturbed.size(Cell.PROJ4J_REFUSES_PROJ_ANSWERS));
        assertEquals("rows Proj4J answers must be untouched by a PROJ-side edit",
                EXPECTED_BOTH_ANSWER, perturbed.size(Cell.BOTH_ANSWER));

        // ...and the other direction: adding a code Proj4J answers must populate the cell that is
        // asserted to be empty, which is the proof that the zero is measured and not structural.
        int bothAnswer = firstCodeIn(c, Cell.BOTH_ANSWER);
        TreeSet<Integer> with = pinnedProjRefusals();
        assertTrue(with.add(bothAnswer));
        Census widened = tabulate(c.rows, c.results, with);
        assertEquals("the 'Proj4J answers, PROJ refuses' cell must be reachable, or its zero "
                        + "means nothing", 1,
                widened.size(Cell.PROJ4J_ANSWERS_PROJ_REFUSES));
        assertNotEquals(EXPECTED_BOTH_ANSWER, widened.size(Cell.BOTH_ANSWER));
    }

    /**
     * <b>Control 3: the Proj4J side discriminates.</b> Three named rows, one per column of the
     * table, each checked individually so that the aggregate cannot be produced by a classifier
     * that gives every row the same answer.
     */
    @Test
    public void theProj4jSideDiscriminatesRowByRow() throws IOException {
        Census c = census();
        assertOutcome(c, 3819, MetaCRSTestCase.Outcome.IN_TOLERANCE, null);
        assertOutcome(c, 4267, MetaCRSTestCase.Outcome.REFUSED, ErrorCause.COORDINATE_OUTSIDE_GRID);
        assertOutcome(c, 2020, MetaCRSTestCase.Outcome.REFUSED,
                ErrorCause.COORDINATE_OUT_OF_DOMAIN);

        // ...and each of those three sits in the cell the pinned PROJ column puts it in.
        assertEquals("EPSG:3819 -- both answer",
                Cell.BOTH_ANSWER, cellOf(c, 3819));
        assertEquals("EPSG:4267 -- Proj4J refuses on the grid, PROJ ballparks it",
                Cell.PROJ4J_REFUSES_PROJ_ANSWERS, cellOf(c, 4267));
        assertEquals("EPSG:2020 -- both refuse",
                Cell.BOTH_REFUSE, cellOf(c, 2020));
    }

    // ------------------------------------------------------------------------- machinery

    private static Map<Cell, Integer> expectations() {
        Map<Cell, Integer> m = new EnumMap<Cell, Integer>(Cell.class);
        m.put(Cell.BOTH_ANSWER, EXPECTED_BOTH_ANSWER);
        m.put(Cell.PROJ4J_ANSWERS_PROJ_REFUSES, EXPECTED_PROJ4J_ANSWERS_PROJ_REFUSES);
        m.put(Cell.PROJ4J_REFUSES_PROJ_ANSWERS, EXPECTED_PROJ4J_REFUSES_PROJ_ANSWERS);
        m.put(Cell.BOTH_REFUSE, EXPECTED_BOTH_REFUSE);
        return m;
    }

    private static Map<Cell, Integer> expectationsWith(Cell cell, int delta) {
        Map<Cell, Integer> m = expectations();
        m.put(cell, m.get(cell) + delta);
        return m;
    }

    private static List<String> compare(Census c) {
        return compare(c, expectations());
    }

    private static List<String> compare(Census c, Map<Cell, Integer> expected) {
        List<String> problems = new ArrayList<String>();
        for (Cell cell : Cell.values()) {
            int got = c.size(cell);
            int want = expected.get(cell);
            if (got != want) {
                problems.add(cell.name() + ": expected " + want + ", measured " + got);
            }
        }
        return problems;
    }

    private static String report(Census c, List<String> problems) {
        StringBuilder msg = new StringBuilder("refusal census disagrees with the pinned "
                + "cs2cs 9.8.1 measurement:\n");
        for (String p : problems) {
            msg.append("  ").append(p).append("\n");
        }
        msg.append("by ErrorCause:\n");
        for (Map.Entry<ErrorCause, List<String>> e : c.byCause.entrySet()) {
            msg.append("  ").append(e.getKey()).append(" = ").append(e.getValue().size())
               .append("\n");
        }
        for (Cell cell : Cell.values()) {
            List<String> rows = c.cells.get(cell);
            if (rows.isEmpty() || rows.size() == expectations().get(cell)) {
                continue;
            }
            msg.append(cell).append(" (").append(rows.size()).append("), first 20:\n");
            for (String row : rows.subList(0, Math.min(20, rows.size()))) {
                msg.append("    ").append(row).append("\n");
            }
        }
        return msg.toString();
    }

    private static void assertOutcome(Census c, int code, MetaCRSTestCase.Outcome outcome,
                                      ErrorCause cause) {
        for (int i = 0; i < c.rows.size(); i++) {
            if (targetCode(c.rows.get(i)) == code) {
                MetaCRSTestCase.Result r = c.results.get(i);
                assertEquals("EPSG:" + code + " outcome", outcome, r.outcome());
                assertEquals("EPSG:" + code + " cause", cause, r.cause());
                return;
            }
        }
        fail("EPSG:" + code + " is not in the file, so this control asserts nothing");
    }

    private static Cell cellOf(Census c, int code) {
        for (Map.Entry<Cell, List<String>> e : c.cells.entrySet()) {
            for (String row : e.getValue()) {
                if (row.startsWith(SOURCE + " -> EPSG:" + code + " ")) {
                    return e.getKey();
                }
            }
        }
        throw new AssertionError("EPSG:" + code + " is in no cell");
    }

    private static int firstCodeIn(Census c, Cell cell) {
        List<String> rows = c.cells.get(cell);
        assertTrue(cell + " must be non-empty for this control", !rows.isEmpty());
        String name = rows.get(0);
        int arrow = name.indexOf("-> EPSG:");
        return Integer.parseInt(name.substring(arrow + 8, name.indexOf(' ', arrow + 8)));
    }

    /**
     * How many of these rows target a {@code +datum=NAD27} CRS, asked of the resolved
     * {@link org.locationtech.proj4j.datum.Datum} rather than of a parameter string. The
     * dictionaries are split on the leading {@code +} &mdash; {@code proj4/nad/epsg} always writes
     * it, {@code proj4/nad/nad27} never does &mdash; so a text match on either spelling alone
     * scores a confident zero on part of the population. The datum's code has one spelling.
     */
    private static int countWithDatumNad27(List<String> rows) {
        assertNotNull("this control needs a non-empty list", rows);
        int n = 0;
        CRSFactory factory = new CRSFactory();
        for (String row : rows) {
            int arrow = row.indexOf("-> ");
            String target = row.substring(arrow + 3, row.indexOf(' ', arrow + 3));
            if ("NAD27".equals(factory.createFromName(target).getDatum().getCode())) {
                n++;
            }
        }
        return n;
    }

    private static int targetCode(MetaCRSTestCase row) {
        String name = row.getTargetCrsName();
        return Integer.parseInt(name.substring(name.indexOf(':') + 1));
    }

    private static String describe(MetaCRSTestCase row, MetaCRSTestCase.Result result) {
        return row.getSourceCrsName() + " -> " + row.getTargetCrsName() + " " + result;
    }

    private static List<MetaCRSTestCase> readRows() throws IOException {
        return new MetaCRSTestFileReader(getFile("proj4-epsg.csv")).readTests();
    }

    private static File getFile(String name) throws IOException {
        try {
            return new File(MetaCrsRefusalCensusTest.class.getResource("../../../" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IOException("cannot locate test resource " + name, e);
        }
    }
}

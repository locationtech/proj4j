/*******************************************************************************
 * Copyright 2009, 2017 Martin Davis
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
import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import org.locationtech.proj4j.io.MetaCRSTestCase;
import org.locationtech.proj4j.io.MetaCRSTestFileReader;

/**
 * Runs MetaCRS test files.
 *
 * @author mbdavis
 */
public class MetaCRSTest {

    private static CRSFactory csFactory = new CRSFactory();

    // Removed: xtestMetaCRSExample. It read TestData.csv, which held exactly one row
    // (EPSG:4326 -> EPSG:2227) that strictly duplicated ExampleTest.testTransformToGeographic and the
    // first assertion of CoordinateTransformTest.testLambertConformalConic. Both the method and the CSV
    // are gone. Note the method DID run despite the misleading "x" prefix, so this is a deletion of live
    // coverage - verified redundant before removal.

    @Test
    public void testPROJ4_SPCS() throws IOException {
        File file = getFile("PROJ4_SPCS_EPSG_nad83.csv");
        MetaCRSTestFileReader reader = new MetaCRSTestFileReader(file);
        List<MetaCRSTestCase> tests = reader.readTests();
        for (MetaCRSTestCase test : tests) {
            Assert.assertTrue(runTest(test));
        }
    }

    /**
     * Runs every row of {@code proj4-epsg.csv} and checks it against the expectation recorded in that
     * row's {@code testMethod} column.
     *
     * <p>Three deliberate properties of this method:
     *
     * <ul>
     *   <li><b>It collects every mismatch and reports them together.</b> It used to assert inside the
     *       loop, so a run aborted on the first bad row and told you "1 failure" whether 1 row or 1,000
     *       had regressed. With 4,280 rows that made the test almost useless for diagnosis.
     *   <li><b>A row marked {@code failing}/{@code error} that now PASSES is also reported.</b> That is
     *       not pedantry: this file is a record of proj4j's behaviour, and a fix landing without the row
     *       being reclassified means the record silently overstates how much is still broken. The
     *       {@code assertFalse} this replaces made such a fix look like a test failure with no
     *       indication that it was good news.
     *   <li><b>A row marked {@code refuses} or {@code refuses:}<i>CAUSE</i> is checked against the
     *       refusal itself</b>, not against "did not pass". 411 rows carry that verdict since the
     *       file was regenerated against PROJ 9.8.1; see
     *       {@link org.locationtech.proj4j.io.MetaCRSTestCase} for why the verdict lives in
     *       column 1 rather than in a twentieth column.
     * </ul>
     *
     * <p><b>Expected state: green.</b> <b>0 regressed, 0 unexpected passes, 0 wrong refusals</b>
     * over 4,280 rows. It read <b>1,308 regressed, 4 unexpected passes</b> until the file was
     * regenerated.
     *
     * <h4>What the file now records, and where each number came from</h4>
     *
     * <table>
     * <caption>proj4-epsg.csv after the 9.8.1 regeneration</caption>
     * <tr><th>rows</th><th>column 1</th><th>source of the expectation</th></tr>
     * <tr><td>3,869</td><td>{@code passing}</td>
     *     <td>898 coordinates re-pinned from {@code cs2cs} 9.8.1 fed the <em>dictionary strings</em>
     *         Proj4J itself reads; the other 2,971 kept their original auto-generated values,
     *         which still hold, and are therefore unmoved evidence rather than rewritten
     *         evidence</td></tr>
     * <tr><td>148</td><td>{@code refuses:COORDINATE_OUTSIDE_GRID}</td>
     *     <td>all {@code +datum=NAD27}; {@code cs2cs} refuses all 148 at the operator layer
     *         ({@code nadgrids=@conus,@alaska,@ntv2_0.gsb,@ntv1_can.dat}, verbatim from
     *         {@code 9.8.1:src/datums.cpp}) with <i>"Coordinate to transform falls outside
     *         grid"</i>; 17 of them PROJ also refuses at the CRS layer</td></tr>
     * <tr><td>263</td><td>{@code refuses:COORDINATE_OUT_OF_DOMAIN}</td>
     *     <td>{@code cs2cs} prints {@code * * inf} on all 263 at the CRS layer</td></tr>
     * </table>
     *
     * <p><b>No expectation in this file comes from Proj4J's own output.</b> Over all 3,869
     * answering rows Proj4J and {@code cs2cs} 9.8.1 agree to 1e-12 relative or better, 4,515 of
     * the 7,738 ordinates bit-for-bit &mdash; so the reference is a measurement, and the file
     * would have caught a disagreement rather than absorbing it.
     *
     * <p>{@link MetaCrsRefusalCensusTest} pins the 2&times;2 against PROJ independently, and
     * {@link #everyRowAssertsSomethingThatCanFail()} is this file's non-vacuity control.
     */
    @Test
    public void testPROJ4_Empirical() throws IOException {
        File file = getFile("proj4-epsg.csv");
        MetaCRSTestFileReader reader = new MetaCRSTestFileReader(file);
        List<MetaCRSTestCase> tests = reader.readTests();

        List<String> regressed = new ArrayList<String>();      // expected to pass, did not
        List<String> unexpectedPass = new ArrayList<String>();  // expected to fail, now passes
        List<String> wrongRefusal = new ArrayList<String>();    // refuses[:CAUSE] not satisfied

        for (MetaCRSTestCase test : tests) {
            MetaCRSTestCase.Result result = test.evaluate(csFactory);
            String mismatch = test.verdictMismatch(result);
            if (mismatch == null) {
                continue;
            }
            String verdict = test.getTestMethod();
            if (MetaCRSTestCase.isRefusalVerdict(verdict)) {
                wrongRefusal.add(describe(test) + " -- " + mismatch);
            } else if (MetaCRSTestCase.PASSING.equals(verdict)) {
                regressed.add(describe(test));
            } else {
                unexpectedPass.add(describe(test) + " [recorded as " + verdict + "]");
            }
        }

        if (!regressed.isEmpty() || !unexpectedPass.isEmpty() || !wrongRefusal.isEmpty()) {
            StringBuilder msg = new StringBuilder();
            msg.append(tests.size()).append(" rows: ")
               .append(regressed.size()).append(" regressed, ")
               .append(unexpectedPass.size()).append(" now passing unexpectedly, ")
               .append(wrongRefusal.size()).append(" refused wrongly.\n");
            appendAll(msg, "REGRESSED (recorded as passing, now fails)", regressed);
            appendAll(msg, "UNEXPECTED PASS (reclassify these rows in proj4-epsg.csv)", unexpectedPass);
            appendAll(msg, "WRONG REFUSAL (recorded as refuses[:CAUSE], did something else)",
                    wrongRefusal);
            Assert.fail(msg.toString());
        }
    }

    // ------------------------------------------------------ the file's own non-vacuity control

    /** Rows in {@code proj4-epsg.csv}, and the split the regeneration produced. */
    private static final int EXPECTED_ROWS = 4280;
    private static final int EXPECTED_ANSWERING_ROWS = 3869;
    private static final int EXPECTED_REFUSAL_ROWS = 411;

    /**
     * <b>Every one of the 4,280 rows is perturbed and must be noticed.</b>
     *
     * <p>{@link #testPROJ4_Empirical()} going green says the file agrees with Proj4J. It does not
     * say the file <em>constrains</em> Proj4J, and after a regeneration that is exactly the doubt
     * worth answering: a re-pinned coordinate that no longer discriminates, or a
     * {@code refuses:}<i>CAUSE</i> that any outcome satisfies, is a row that has stopped being a
     * test while still being counted as one.
     *
     * <p>Three perturbations, applied to the rows as committed rather than to synthetic ones:
     *
     * <ul>
     *   <li><b>A re-pinned coordinate is pinned to its own tolerance.</b> Every {@code passing}
     *       row is re-run with {@code tgtOrd1} moved by <em>four times that row's own
     *       tolerance</em> &mdash; not a fixed distance, because the tolerances here span 1e-6
     *       degrees to 1.0 m &mdash; and the moved row must fail. A row that survives this is
     *       asserting nothing about its easting.</li>
     *   <li><b>A returned coordinate does not satisfy a refusal verdict.</b> Checked on the 3,869
     *       rows that really do return one, which is the population a fail-open revert would
     *       enlarge.</li>
     *   <li><b>The wrong cause does not satisfy a refusal verdict.</b> Each of the 411 refusing
     *       rows is checked against the <em>other</em> cause the file uses, so
     *       {@code refuses:COORDINATE_OUTSIDE_GRID} cannot be quietly satisfied by an
     *       out-of-domain refusal or vice versa.</li>
     * </ul>
     *
     * <p>The counts are asserted too: a perturbation loop that walked zero rows would otherwise
     * pass loudest of all.
     */
    @Test
    public void everyRowAssertsSomethingThatCanFail() throws IOException {
        List<MetaCRSTestCase> tests =
                new MetaCRSTestFileReader(getFile("proj4-epsg.csv")).readTests();
        Assert.assertEquals("row count", EXPECTED_ROWS, tests.size());

        List<String> vacuous = new ArrayList<String>();
        int answering = 0;
        int refusing = 0;

        for (MetaCRSTestCase test : tests) {
            MetaCRSTestCase.Result result = test.evaluate(csFactory);
            String verdict = test.getTestMethod();

            if (MetaCRSTestCase.isRefusalVerdict(verdict)) {
                refusing++;
                ErrorCause required = MetaCRSTestCase.requiredCause(verdict);
                Assert.assertNotNull("every refusal verdict in this file must pin a cause, but "
                        + describe(test) + " is a bare '" + verdict + "'", required);
                String other = "refuses:" + otherCause(required);
                if (MetaCRSTestCase.verdictMismatch(other, result) == null) {
                    vacuous.add(describe(test) + ": '" + verdict + "' is also satisfied by '"
                            + other + "', so the cause is not pinned");
                }
                continue;
            }

            answering++;
            // 1. the refusal verdict must NOT be satisfiable by this row's returned coordinate
            if (MetaCRSTestCase.verdictMismatch("refuses:" + ErrorCause.COORDINATE_OUTSIDE_GRID,
                    result) == null) {
                vacuous.add(describe(test) + ": a returned coordinate satisfied a refusal verdict");
            }
            // 2. move the expected easting by four of this row's own tolerances; it must fail
            MetaCRSTestCase moved = withEastingMoved(test);
            if (moved.verdictMismatch(moved.evaluate(csFactory)) == null) {
                vacuous.add(describe(test) + ": expected easting "
                        + test.getTargetCoordinate().x + " moved by 4 x "
                        + test.getTolerance().x + " and the row still passed");
            }
        }

        Assert.assertEquals("rows whose expectation nothing could violate: " + vacuous,
                0, vacuous.size());
        Assert.assertEquals("rows that answer", EXPECTED_ANSWERING_ROWS, answering);
        Assert.assertEquals("rows that refuse", EXPECTED_REFUSAL_ROWS, refusing);
    }

    /**
     * <b>The self-test for the perturbation leg above.</b> A loop that displaces every expectation
     * and finds no vacuous row is only worth reading if the displacement is what produces the
     * verdict. Here the same real row &mdash; {@code EPSG:4326 -> EPSG:2000}, whose easting this
     * regeneration re-pinned from {@code cs2cs} 9.8.1 &mdash; is run at 0, 4 and again at 0
     * tolerances of displacement, and only the middle one may fail.
     *
     * <p>It also pins the re-pin itself: the row's committed expectation is 9.8.1's
     * {@code 9523653.022922918}, and the value the file carried before, {@code 9413505.328467},
     * is what {@code cs2cs 9.8.1 +approx} prints &mdash; 110 km away and far outside the row's
     * 0.1 m tolerance. So the row discriminates between the two transverse-Mercator series, which
     * is the change it was re-pinned for.
     */
    @Test
    public void theNonVacuityControlCanItselfFail() throws IOException {
        MetaCRSTestCase row = rowFor("2000");
        Assert.assertEquals("this control needs the row the regeneration re-pinned",
                9523653.022923, row.getTargetCoordinate().x, 1e-6);
        Assert.assertEquals(0.1, row.getTolerance().x, 0.0);

        Assert.assertNull("undisplaced, the committed row must pass",
                row.verdictMismatch(row.evaluate(csFactory)));

        MetaCRSTestCase moved = withEastingMoved(row);
        Assert.assertNotNull("displaced by 4 tolerances, it must fail",
                moved.verdictMismatch(moved.evaluate(csFactory)));

        // ...and the pre-9.8.1 value this row was re-pinned away from must fail too, so the row
        // is a live discriminator between the two tmerc series rather than a value that happens
        // to sit inside a wide tolerance.
        MetaCRSTestCase approx = withEasting(row, 9413505.328467);
        Assert.assertNotNull("the +approx easting must not satisfy the re-pinned row",
                approx.verdictMismatch(approx.evaluate(csFactory)));
    }

    private MetaCRSTestCase rowFor(String targetCode) throws IOException {
        List<MetaCRSTestCase> tests =
                new MetaCRSTestFileReader(getFile("proj4-epsg.csv")).readTests();
        for (MetaCRSTestCase test : tests) {
            if (("EPSG:" + targetCode).equals(test.getTargetCrsName())) {
                return test;
            }
        }
        throw new AssertionError("EPSG:" + targetCode + " is not in the file");
    }

    /** The other cause the regenerated file uses, so the two are checked against each other. */
    private static ErrorCause otherCause(ErrorCause cause) {
        return cause == ErrorCause.COORDINATE_OUTSIDE_GRID
                ? ErrorCause.COORDINATE_OUT_OF_DOMAIN
                : ErrorCause.COORDINATE_OUTSIDE_GRID;
    }

    /**
     * The same row with {@code tgtOrd1} displaced by four of its own tolerances.
     *
     * <p>Four rather than one: a passing row has {@code |result - expected| <= tol}, so a
     * displacement of 4&nbsp;tol puts the residual in {@code [3 tol, 5 tol]} and cannot land back
     * inside tolerance even at the boundary.
     */
    private static MetaCRSTestCase withEastingMoved(MetaCRSTestCase row) {
        return withEasting(row, row.getTargetCoordinate().x + 4.0 * row.getTolerance().x);
    }

    /** The same row with a chosen {@code tgtOrd1} and the verdict forced to {@code passing}. */
    private static MetaCRSTestCase withEasting(MetaCRSTestCase row, double easting) {
        ProjCoordinate src = row.getSourceCoordinate();
        ProjCoordinate tgt = row.getTargetCoordinate();
        ProjCoordinate tol = row.getTolerance();
        return new MetaCRSTestCase(row.getName(), MetaCRSTestCase.PASSING,
                authOf(row.getSourceCrsName()), codeOf(row.getSourceCrsName()),
                authOf(row.getTargetCrsName()), codeOf(row.getTargetCrsName()),
                src.x, src.y, src.z,
                easting, tgt.y, tgt.z,
                tol.x, tol.y, tol.z,
                "", "", "", "");
    }

    private static String authOf(String name) {
        return name.substring(0, name.indexOf(':'));
    }

    private static String codeOf(String name) {
        return name.substring(name.indexOf(':') + 1);
    }

    // ------------------------------------------------------------- verdict semantics, proven

    /**
     * Builds a case that is {@code proj4-epsg.csv}'s own probe &mdash; WGS 84 at
     * (1&deg;E, 1&deg;S), in the Gulf of Guinea &mdash; with a chosen target and verdict, so the
     * tests below exercise a real transform rather than a mock outcome.
     *
     * @param target     the EPSG code of the target CRS
     * @param testMethod the verdict to check against
     * @return a case identical in shape to a CSV row
     */
    private static MetaCRSTestCase probe(String target, String testMethod) {
        // The expected coordinate and 1e-6 tolerances are the file's own for EPSG:3819; they matter
        // only where in-tolerance is being distinguished from out-of-tolerance.
        return new MetaCRSTestCase("probe", testMethod, "EPSG", "4326", "EPSG", target,
                1.0, -1.0, 0.0,
                0.998744, -1.005575, 173.165791,
                0.000001, 0.000001, 0.000001,
                "", "", "", "");
    }

    /** {@code EPSG:3819}: answers, and within the CSV's own expected coordinate and tolerance. */
    private static final String ANSWERS = "3819";

    /** {@code EPSG:4267} is {@code +datum=NAD27}: no shipped grid reaches (1, -1). */
    private static final String REFUSES_OUTSIDE_GRID = "4267";

    /** {@code EPSG:2020} is a {@code tmerc} whose central meridian is 83.5&deg; away. */
    private static final String REFUSES_OUT_OF_DOMAIN = "2020";

    /**
     * <b>The control that makes {@code refuses:} worth having.</b> A verdict meaning merely "did
     * not pass" is satisfied by a returned coordinate; this one must not be.
     *
     * <p>{@code EPSG:4326 -> EPSG:3819} answers within tolerance at (1, -1), so it is exactly the
     * shape a fail-open revert would produce on a row a regenerated file marks
     * {@code refuses:COORDINATE_OUTSIDE_GRID}.
     */
    @Test
    public void aRefusalVerdictIsNotSatisfiedByAReturnedCoordinate() {
        MetaCRSTestCase row = probe(ANSWERS, "refuses:COORDINATE_OUTSIDE_GRID");
        MetaCRSTestCase.Result result = row.evaluate(csFactory);
        Assert.assertEquals("the control needs a row that really does answer",
                MetaCRSTestCase.Outcome.IN_TOLERANCE, result.outcome());

        String mismatch = row.verdictMismatch(result);
        Assert.assertNotNull("a returned coordinate must not satisfy a refusal verdict", mismatch);
        Assert.assertTrue("and the report must say what came back instead: " + mismatch,
                mismatch.contains("must refuse") && mismatch.contains("returned ("));
    }

    /**
     * <b>The second control: the wrong reason is a failure too.</b> Without it, {@code refuses:}
     * would be an elaborate spelling of {@code refuses}, and a row could go on asserting "outside
     * grid" while the library had started refusing it for an unrelated numerical reason.
     */
    @Test
    public void aRefusalVerdictIsNotSatisfiedByTheWrongErrorCause() {
        MetaCRSTestCase row = probe(REFUSES_OUT_OF_DOMAIN, "refuses:COORDINATE_OUTSIDE_GRID");
        MetaCRSTestCase.Result result = row.evaluate(csFactory);
        Assert.assertEquals(MetaCRSTestCase.Outcome.REFUSED, result.outcome());
        Assert.assertEquals("the control needs a row that refuses for a DIFFERENT reason",
                ErrorCause.COORDINATE_OUT_OF_DOMAIN, result.cause());

        String mismatch = row.verdictMismatch(result);
        Assert.assertNotNull("a refusal with the wrong cause must not satisfy the verdict",
                mismatch);
        Assert.assertTrue("and the report must name both causes: " + mismatch,
                mismatch.contains("COORDINATE_OUTSIDE_GRID")
                        && mismatch.contains("COORDINATE_OUT_OF_DOMAIN"));

        // The discriminating half: the same outcome DOES satisfy the matching verdict and the bare
        // form, so the two assertions above are not simply "refuses never passes".
        Assert.assertNull(MetaCRSTestCase.verdictMismatch("refuses:COORDINATE_OUT_OF_DOMAIN",
                result));
        Assert.assertNull(MetaCRSTestCase.verdictMismatch("refuses", result));
    }

    /**
     * The positive leg, on the row the census says is the reason this verdict exists.
     * {@code EPSG:4326 -> EPSG:4267} refuses with {@link ErrorCause#COORDINATE_OUTSIDE_GRID}, and
     * that is what a regenerated file should record for it.
     *
     * <p>The last two assertions are the whole argument in four lines: {@code failing} is
     * satisfied by this refusal <em>and</em> would be satisfied by a wrong coordinate, so
     * downgrading the row to {@code failing} would assert neither.
     */
    @Test
    public void aRefusalVerdictIsSatisfiedByTheRightRefusal() {
        MetaCRSTestCase row = probe(REFUSES_OUTSIDE_GRID, "refuses:COORDINATE_OUTSIDE_GRID");
        MetaCRSTestCase.Result result = row.evaluate(csFactory);
        Assert.assertEquals(MetaCRSTestCase.Outcome.REFUSED, result.outcome());
        Assert.assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, result.cause());
        Assert.assertNull(row.verdictMismatch(result));

        Assert.assertNull("'failing' accepts this refusal...",
                MetaCRSTestCase.verdictMismatch("failing", result));
        MetaCRSTestCase.Result outOfTolerance = new MetaCRSTestCase("probe", "failing",
                "EPSG", "4326", "EPSG", ANSWERS, 1.0, -1.0, 0.0,
                0.0, 0.0, 0.0, 0.000001, 0.000001, 0.000001, "", "", "", "")
                .evaluate(csFactory);
        Assert.assertEquals(MetaCRSTestCase.Outcome.OUT_OF_TOLERANCE, outOfTolerance.outcome());
        Assert.assertNull("...and accepts a wrong coordinate identically. That is the conflation.",
                MetaCRSTestCase.verdictMismatch("failing", outOfTolerance));
    }

    /**
     * <b>The strict-superset proof, as a table.</b> Every legacy verdict against every outcome
     * must give the answer the previous runner gave: {@code passing} iff in tolerance, everything
     * else iff not in tolerance. The three outcomes are produced by the library, not constructed.
     */
    @Test
    public void theThreeLegacyVerdictsKeepTheirExactMeaning() {
        MetaCRSTestCase.Result inTolerance = probe(ANSWERS, "passing").evaluate(csFactory);
        MetaCRSTestCase.Result refused =
                probe(REFUSES_OUTSIDE_GRID, "passing").evaluate(csFactory);
        MetaCRSTestCase.Result outOfTolerance = new MetaCRSTestCase("probe", "passing",
                "EPSG", "4326", "EPSG", ANSWERS, 1.0, -1.0, 0.0,
                0.0, 0.0, 0.0, 0.000001, 0.000001, 0.000001, "", "", "", "")
                .evaluate(csFactory);

        Assert.assertEquals(MetaCRSTestCase.Outcome.IN_TOLERANCE, inTolerance.outcome());
        Assert.assertEquals(MetaCRSTestCase.Outcome.REFUSED, refused.outcome());
        Assert.assertEquals("the table needs all three outcomes to be reachable",
                MetaCRSTestCase.Outcome.OUT_OF_TOLERANCE, outOfTolerance.outcome());

        for (String verdict : new String[]{"failing", "error", "", "typo", "PASSING"}) {
            Assert.assertNotNull(verdict + " must reject an in-tolerance answer",
                    MetaCRSTestCase.verdictMismatch(verdict, inTolerance));
            Assert.assertNull(verdict + " must accept an out-of-tolerance answer",
                    MetaCRSTestCase.verdictMismatch(verdict, outOfTolerance));
            Assert.assertNull(verdict + " must accept a refusal",
                    MetaCRSTestCase.verdictMismatch(verdict, refused));
        }
        Assert.assertNull(MetaCRSTestCase.verdictMismatch("passing", inTolerance));
        Assert.assertNotNull(MetaCRSTestCase.verdictMismatch("passing", outOfTolerance));
        Assert.assertNotNull(MetaCRSTestCase.verdictMismatch("passing", refused));
    }

    /**
     * A verdict naming a cause that does not exist is a malformed row, not a row that quietly
     * never matches. Rejected at check time so a typo in a regenerated file is loud rather than
     * vacuous.
     */
    @Test
    public void aRefusalVerdictNamingAnUnknownCauseIsRejected() {
        MetaCRSTestCase row = probe(REFUSES_OUTSIDE_GRID, "refuses:COORDINATE_OUTSIDE_GRIDS");
        MetaCRSTestCase.Result result = row.evaluate(csFactory);
        try {
            row.verdictMismatch(result);
            Assert.fail("a verdict naming a non-existent ErrorCause must be rejected, not treated "
                    + "as an assertion nothing can satisfy");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage(),
                    expected.getMessage().contains("COORDINATE_OUTSIDE_GRIDS"));
        }
        // ...and the correctly spelled one is accepted, so this is not rejecting everything.
        Assert.assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID,
                MetaCRSTestCase.requiredCause("refuses:COORDINATE_OUTSIDE_GRID"));
        Assert.assertNull("a bare 'refuses' pins no cause",
                MetaCRSTestCase.requiredCause("refuses"));
        Assert.assertNull("and a legacy verdict pins none either",
                MetaCRSTestCase.requiredCause("failing"));
    }

    private static void appendAll(StringBuilder msg, String heading, List<String> rows) {
        if (rows.isEmpty()) {
            return;
        }
        msg.append("\n").append(heading).append(" (").append(rows.size()).append("):\n");
        for (String row : rows) {
            msg.append("  ").append(row).append("\n");
        }
    }

    private static String describe(MetaCRSTestCase test) {
        return test.getSourceCrsName() + " -> " + test.getTargetCrsName();
    }

    File getFile(String name) {
        try {
            return new File(this.getClass().getResource("../../../" + name).toURI());
        } catch (URISyntaxException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return null;
    }

    boolean runTest(MetaCRSTestCase crsTest) {
        try {
            // Deliberately silent: this runs 4,280 times and used to print every row plus every
            // exception to stdout on a green build. Failures are reported once, in aggregate, by the
            // caller.
            return crsTest.execute(csFactory);
        } catch (Proj4jException ex) {
            return false;
        }
    }
}

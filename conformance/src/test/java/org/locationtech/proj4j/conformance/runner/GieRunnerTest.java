/*
 * Copyright 2026 The Proj4J Contributors.
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
 */
package org.locationtech.proj4j.conformance.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.proj4j.conformance.bridge.GieFailureKind;
import org.locationtech.proj4j.conformance.manifest.AssertionOutcome;
import org.locationtech.proj4j.conformance.manifest.ObservedRun;
import org.locationtech.proj4j.conformance.parse.GieFile;
import org.locationtech.proj4j.conformance.parse.GieLexer;
import org.locationtech.proj4j.conformance.parse.GieVerb;

/**
 * The state machine, exercised against small synthetic fixtures and a fake operation whose arithmetic
 * is simple enough to predict by hand.
 *
 * <p>These are tests of {@code gie.cpp}'s bookkeeping, not of any projection: every behaviour asserted
 * here is one that, if got wrong, silently changes thousands of corpus outcomes without producing an
 * error anywhere.
 */
class GieRunnerTest {

    private final FakeGieOperationFactory factory = new FakeGieOperationFactory();

    private GieFileResult run(String fixture) throws IOException {
        return run(fixture, GieGridAvailability.NoneAvailable.INSTANCE);
    }

    private GieFileResult run(String fixture, GieGridAvailability grids) throws IOException {
        String resource = "/runner-fixtures/" + fixture;
        InputStream in = GieRunnerTest.class.getResourceAsStream(resource);
        assertNotNull(in, "missing fixture " + resource);
        try {
            GieFile lexed = GieLexer.lex(resource, in);
            return GieRunner.using(factory, grids).run("runner-fixtures/" + fixture, lexed);
        } finally {
            in.close();
        }
    }

    private static List<AssertionOutcome> outcomes(GieFileResult result) {
        List<AssertionOutcome> out = new ArrayList<AssertionOutcome>();
        for (GieAssertionResult a : result.assertions()) {
            out.add(a.outcome());
        }
        return out;
    }

    // ------------------------------------------------------------------------------ reset & stickiness

    @Test
    @DisplayName("tolerance and direction are sticky within a block and reset by the next operation")
    void toleranceAndDirectionResetPerOperation() throws IOException {
        GieFileResult result = run("reset.gie");

        assertEquals(4, result.operationBlocks());
        assertEquals(5, result.total());

        // Block 0: `tolerance 100 m` + `direction inverse`. The fake shifts by 10, so an inverse
        // transform of (1,2) yields (-9,-8) against an expected (1,2): a deviation of hypot(10,10) =
        // 14.14, comfortably inside 100 m. Both pairs pass, which is the stickiness claim: the second
        // accept/expect has no tolerance or direction of its own.
        assertEquals(AssertionOutcome.PASS, result.assertions().get(0).outcome(), "block 0, first pair");
        assertEquals(AssertionOutcome.PASS, result.assertions().get(1).outcome(),
                "block 0, second pair: tolerance and direction must still be in force");
        assertEquals(0, result.assertions().get(0).key().operationBlockIndex());
        assertEquals(0, result.assertions().get(0).key().assertionIndex());
        assertEquals(1, result.assertions().get(1).key().assertionIndex());

        // Block 1: the identical deviation, now judged against the 0.5 mm default and computed in the
        // forward direction. If either had leaked this would pass.
        GieAssertionResult afterReset = result.assertions().get(2);
        assertEquals(AssertionOutcome.FAIL, afterReset.outcome(),
                "operation() must reset tolerance to 0.5 mm and direction to forward");
        assertEquals(1, afterReset.key().operationBlockIndex());
        assertEquals(0, afterReset.key().assertionIndex(), "the assertion index resets per block");
        assertTrue(afterReset.detail().contains("tolerance 0.500000 mm"),
                "the detail should quote the reset tolerance, was: " + afterReset.detail());
    }

    @Test
    @DisplayName("direction resets to forward on a new operation")
    void directionResetsPerOperation() throws IOException {
        GieFileResult result = run("reset.gie");
        GieAssertionResult afterReset = result.assertions().get(3);

        // Block 2 restores a 1 m tolerance but says nothing about the direction, and expects the
        // FORWARD answer. A leaked `direction inverse` would be 28.28 m out and fail.
        assertEquals(2, afterReset.key().operationBlockIndex());
        assertEquals(AssertionOutcome.PASS, afterReset.outcome(),
                "operation() must reset the direction to forward");
    }

    @Test
    @DisplayName("the accepted coordinate survives an operation boundary, as gie's does")
    void acceptIsNotResetByOperation() throws IOException {
        GieFileResult result = run("reset.gie");
        GieAssertionResult leaked = result.assertions().get(4);

        // Block 2 has no `accept` of its own. gie's operation() resets direction, tolerance, ignore and
        // skip_test -- and never touches T.a. 121 assertions in the 9.8.1 corpus depend on that, two of
        // them real coordinate comparisons (gie/epsg_no_grid.gie:29 and gie/more_builtins.gie:469), so
        // "tidying" the accept into the reset would break them with no diagnostic.
        assertEquals(3, leaked.key().operationBlockIndex());
        assertEquals(AssertionOutcome.PASS, leaked.outcome(),
                "block 3 must reuse block 2's accept of (1,2) and match its own expect of (1,2)");
    }

    @Test
    @DisplayName("each operation opens a new key block and restarts the assertion index")
    void blockAndAssertionIndicesAreDerivedFromTheResetPoints() throws IOException {
        GieFileResult result = run("reset.gie");
        assertEquals("[0:0, 0:1, 1:0, 2:0, 3:0]", indices(result));
    }

    private static String indices(GieFileResult result) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < result.assertions().size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(result.assertions().get(i).key().operationBlockIndex())
                    .append(':')
                    .append(result.assertions().get(i).key().assertionIndex());
        }
        return out.append(']').toString();
    }

    // ------------------------------------------------------------------------------ require_grid

    @Test
    @DisplayName("a missing require_grid skips every later expect and silently drops every other verb")
    void requireGridCascadesSkips() throws IOException {
        GieFileResult result = run("require-grid.gie", GieGridAvailability.NoneAvailable.INSTANCE);

        // Two expects become SKIP; the `roundtrip 5` between them produces NOTHING AT ALL, because
        // dispatch() returns 0 for every verb but `expect` while skip_test is set. That asymmetry is
        // upstream's and is why the corpus's roundtrip count and its skip count do not add up.
        assertEquals(3, result.total(), "2 skipped expects + 1 passing expect; the roundtrip is dropped");
        assertEquals(
                java.util.Arrays.asList(AssertionOutcome.SKIP, AssertionOutcome.SKIP, AssertionOutcome.PASS),
                outcomes(result));
        for (GieAssertionResult a : result.assertions()) {
            assertEquals(GieVerb.EXPECT, a.verb(), "the dropped verb must not appear as an assertion");
        }
        assertEquals(1, result.count(AssertionOutcome.PASS));
        assertEquals(2, result.count(AssertionOutcome.SKIP));
        assertEquals(0, result.count(AssertionOutcome.FAIL));
    }

    @Test
    @DisplayName("the skip cascade ends at the next operation")
    void requireGridCascadeEndsAtTheNextOperation() throws IOException {
        GieFileResult result = run("require-grid.gie", GieGridAvailability.NoneAvailable.INSTANCE);
        GieAssertionResult afterCascade = result.assertions().get(2);
        assertEquals(1, afterCascade.key().operationBlockIndex());
        assertEquals(AssertionOutcome.PASS, afterCascade.outcome());
    }

    @Test
    @DisplayName("a present grid skips nothing")
    void requireGridPassesWhenTheGridIsThere() throws IOException {
        GieFileResult result = run("require-grid.gie", GieGridAvailability.AllAvailable.INSTANCE);
        assertEquals(0, result.count(AssertionOutcome.SKIP));
        // With nothing dropped the roundtrip runs too, so there are four assertions rather than three.
        assertEquals(4, result.total());
    }

    @Test
    @DisplayName("a skip is never counted as a pass")
    void skipsAreNeverPasses() throws IOException {
        GieFileResult result = run("require-grid.gie", GieGridAvailability.NoneAvailable.INSTANCE);
        ObservedRun run = result.recordInto(ObservedRun.builder()).build();

        assertEquals(1, run.count(AssertionOutcome.PASS));
        assertEquals(2, run.count(AssertionOutcome.SKIP));
        assertEquals(3, run.total());
        // The one number that must never absorb the other two.
        assertFalse(run.count(AssertionOutcome.PASS) == run.total(),
                "passes must not include skips");
    }

    @Test
    @DisplayName("the vendored BETA2007.gsb resolves and the GeoTIFF grids do not")
    void classpathGridAvailabilityMatchesWhatIsVendored() {
        GieGridAvailability grids = GieGridAvailability.OnClasspath.INSTANCE;
        assertTrue(grids.isAvailable("BETA2007.gsb"), "BETA2007.gsb is vendored under proj-data/");
        assertFalse(grids.isAvailable("us_nga_egm08_25.tif"));
        assertFalse(grids.isAvailable("fr_ign_RAF20.tif"));
        assertFalse(grids.isAvailable(""));
        assertFalse(grids.isAvailable(null));
    }

    // ------------------------------------------------------------------------------ expect failure

    @Test
    @DisplayName("expect failure: all four combinations of expectation and outcome")
    void expectFailureInAllFourCombinations() throws IOException {
        GieFileResult result = run("expect-failure.gie");

        assertEquals(7, result.total());
        List<GieAssertionResult> a = result.assertions();

        // 1. creation failed, failure expected -> success.
        assertEquals(AssertionOutcome.PASS, a.get(0).outcome(), "creation failed as expected");
        // 2. creation failed, a coordinate expected -> a true failure. NB the operation itself being
        //    uncreatable is not an error at `operation` time; the verdict is deferred to here.
        assertEquals(AssertionOutcome.FAIL, a.get(1).outcome(), "uncreatable operation, coordinate expected");
        assertTrue(a.get(1).detail().contains("could not be created"), a.get(1).detail());
        // 3. creation worked, transform failed, failure expected -> success.
        assertEquals(AssertionOutcome.PASS, a.get(2).outcome(), "transform failed as expected");
        // 4. creation worked, transform worked, failure expected -> "failed to fail".
        assertEquals(AssertionOutcome.FAIL, a.get(3).outcome(), "failed to fail must be a failure");
        assertTrue(a.get(3).detail().contains("failed to fail"), a.get(3).detail());
        // 5. control: a plain comparison still works in the same file.
        assertEquals(AssertionOutcome.PASS, a.get(4).outcome());
    }

    @Test
    @DisplayName("every named errno degenerates to a bare expect failure, recognised or not")
    void namedErrnosDegenerate() throws IOException {
        GieFileResult result = run("expect-failure.gie");
        GieAssertionResult realName = result.assertions().get(5);
        GieAssertionResult legacyName = result.assertions().get(6);

        assertEquals(AssertionOutcome.PASS, realName.outcome());
        assertEquals(AssertionOutcome.PASS, legacyName.outcome());
        assertTrue(realName.detail().contains("coord_transfm_invalid_coord"),
                "the errno should be recorded for triage: " + realName.detail());
        assertTrue(legacyName.detail().contains("9999"),
                "a name PROJ itself cannot resolve should say so: " + legacyName.detail());
    }

    // -------------------------------------------------------------------- expect failure: the three-way split

    private static List<ExpectedFailureVerdict> verdicts(GieFileResult result) {
        List<ExpectedFailureVerdict> out = new ArrayList<ExpectedFailureVerdict>();
        for (GieAssertionResult a : result.assertions()) {
            out.add(a.expectedFailureVerdict());
        }
        return out;
    }

    @Test
    @DisplayName("a rejected COORDINATE against a built operation is a genuine pass")
    void expectFailureIsGenuineWhenTheOperationExistsAndTheCoordinateIsRefused() throws IOException {
        GieFileResult result = run("expect-failure-vacuity.gie");
        GieAssertionResult a = result.assertions().get(0);

        assertEquals(ExpectedFailureVerdict.PASS_EXPECTED_FAILURE, a.expectedFailureVerdict());
        assertEquals(AssertionOutcome.PASS, a.outcome());
        assertTrue(a.detail().contains("coordinate was rejected"), a.detail());
    }

    @Test
    @DisplayName("an operation that could not be created because it is NOT_IMPLEMENTED is VACUOUS")
    void expectFailureIsVacuousWhenTheOperationIsNotImplemented() throws IOException {
        GieFileResult result = run("expect-failure-vacuity.gie");
        GieAssertionResult a = result.assertions().get(1);

        // This is gie/adams_hemi.gie in miniature: gie scores it a pass, proj4j has demonstrated
        // nothing, and folding it into the headline is failure-to-implement reported as conformance.
        assertEquals(ExpectedFailureVerdict.VACUOUS_EXPECTED_FAILURE, a.expectedFailureVerdict());
        assertEquals(AssertionOutcome.VACUOUS_EXPECTED_FAILURE, a.outcome());
        assertFalse(a.outcome().isPass(), "a vacuous expected failure is never a pass");
        assertFalse(a.outcome().isMeasured(), "nor is it a measurement");
        assertTrue(a.detail().startsWith("VACUOUS"), a.detail());
    }

    @Test
    @DisplayName("a transform that succeeds under expect failure is still a FAIL")
    void expectFailureThatSucceedsIsAFail() throws IOException {
        GieFileResult result = run("expect-failure-vacuity.gie");
        GieAssertionResult a = result.assertions().get(2);

        assertEquals(ExpectedFailureVerdict.FAIL, a.expectedFailureVerdict());
        assertEquals(AssertionOutcome.FAIL, a.outcome());
        assertTrue(a.detail().contains("failed to fail"), a.detail());
    }

    @Test
    @DisplayName("an errno that asks for the DEFINITION to be refused makes a construction failure genuine")
    void aRefusedDefinitionUnderAnInvalidOpErrnoIsGenuine() throws IOException {
        GieFileResult result = run("expect-failure-vacuity.gie");
        GieAssertionResult a = result.assertions().get(3);

        // gie/ellipsoid.gie is 15 rows of exactly this and they are real: the row exists in order to
        // have `+a=-1` (and friends) refused, and proj4j refuses it as INVALID_DEFINITION, whose
        // documented contract is "PROJ 9.8.1 would refuse this too".
        assertEquals(ExpectedFailureVerdict.PASS_EXPECTED_FAILURE, a.expectedFailureVerdict());
        assertEquals(AssertionOutcome.PASS, a.outcome());
        assertTrue(a.detail().contains("definition was rejected"), a.detail());
    }

    @Test
    @DisplayName("a coord_transfm errno overrules an INVALID_DEFINITION: the corpus says PROJ built it")
    void aCoordTransfmErrnoMakesEvenAnInvalidDefinitionVacuous() throws IOException {
        GieFileResult result = run("expect-failure-vacuity.gie");
        GieAssertionResult a = result.assertions().get(4);

        // PROJ can only raise coord_transfm_* after proj_create returned an object, so the row asserts
        // that the definition was ACCEPTED and the coordinate refused. proj4j rejecting the definition
        // therefore contradicts INVALID_DEFINITION's own contract, and the corpus is the authority.
        assertEquals(ExpectedFailureVerdict.VACUOUS_EXPECTED_FAILURE, a.expectedFailureVerdict());
        assertEquals(AssertionOutcome.VACUOUS_EXPECTED_FAILURE, a.outcome());
    }

    @Test
    @DisplayName("NOT_IMPLEMENTED is vacuous even under an errno that wants the definition refused")
    void notImplementedIsNeverPromotedByAnErrnoName() throws IOException {
        GieFileResult result = run("expect-failure-vacuity.gie");
        GieAssertionResult a = result.assertions().get(5);

        // An operator we cannot build at all refuses valid and invalid definitions alike, so refusing
        // this one is not evidence about either.
        assertEquals(ExpectedFailureVerdict.VACUOUS_EXPECTED_FAILURE, a.expectedFailureVerdict());
    }

    @Test
    @DisplayName("a missing grid is genuine only when the row says PROJ could not find the file either")
    void missingGridIsGenuineOnlyUnderTheFileNotFoundErrno() throws IOException {
        GieFileResult result = run("expect-failure-vacuity.gie");

        assertEquals(ExpectedFailureVerdict.PASS_EXPECTED_FAILURE,
                result.assertions().get(6).expectedFailureVerdict(),
                "errno invalid_op_file_not_found_or_invalid: PROJ failed to build it too");
        assertEquals(ExpectedFailureVerdict.VACUOUS_EXPECTED_FAILURE,
                result.assertions().get(7).expectedFailureVerdict(),
                "a bare expect failure over a grid we simply do not ship measures nothing");
    }

    @Test
    @DisplayName("the whole fixture: 8 rows, 3 genuine, 4 vacuous, 1 fail -- gie would have said 7 passes")
    void theThreeWaySplitTalliedOverTheWholeFixture() throws IOException {
        GieFileResult result = run("expect-failure-vacuity.gie");

        assertEquals(8, result.total());
        assertEquals(8, result.expectedFailureRows(), "every row in the fixture is an expect failure");
        assertEquals(
                java.util.Arrays.asList(
                        ExpectedFailureVerdict.PASS_EXPECTED_FAILURE,
                        ExpectedFailureVerdict.VACUOUS_EXPECTED_FAILURE,
                        ExpectedFailureVerdict.FAIL,
                        ExpectedFailureVerdict.PASS_EXPECTED_FAILURE,
                        ExpectedFailureVerdict.VACUOUS_EXPECTED_FAILURE,
                        ExpectedFailureVerdict.VACUOUS_EXPECTED_FAILURE,
                        ExpectedFailureVerdict.PASS_EXPECTED_FAILURE,
                        ExpectedFailureVerdict.VACUOUS_EXPECTED_FAILURE),
                verdicts(result));

        assertEquals(3, result.count(AssertionOutcome.PASS));
        assertEquals(1, result.count(AssertionOutcome.FAIL));
        assertEquals(0, result.count(AssertionOutcome.SKIP));
        assertEquals(4, result.count(AssertionOutcome.VACUOUS_EXPECTED_FAILURE));
        // Upstream's tally for the same file: 7 successes and 1 failure. That gap is the defect.
        assertFalse(result.count(AssertionOutcome.PASS) == 7,
                "4 of gie's 7 successes here demonstrate nothing and must not be counted");
        assertTrue(result.summary().endsWith("3 pass, 1 fail, 0 skip, 4 vacuous"), result.summary());
    }

    @Test
    @DisplayName("a vacuous row is recorded distinctly in the observed run, not as a pass")
    void aVacuousRowSurvivesIntoTheObservedRunAsItsOwnOutcome() throws IOException {
        ObservedRun run = run("expect-failure-vacuity.gie").recordInto(ObservedRun.builder()).build();

        assertEquals(3, run.count(AssertionOutcome.PASS));
        assertEquals(4, run.count(AssertionOutcome.VACUOUS_EXPECTED_FAILURE));
        assertEquals(8, run.total());
    }

    @Test
    @DisplayName("the classification rule, stated as a table")
    void theClassificationRuleIsATableAndThisIsIt() {
        // Rows the corpus says PROJ BUILT: coord_transfm* and no_inverse_op. Nothing survives them.
        for (String errno : new String[] {
            "coord_transfm",
            "coord_transfm_invalid_coord",
            "coord_transfm_outside_projection_domain",
            "coord_transfm_outside_grid",
            "no_inverse_op",
        }) {
            assertTrue(ExpectedFailureVerdict.assertsProjBuiltTheOperation(errno), errno);
            for (GieFailureKind kind : GieFailureKind.values()) {
                assertEquals(ExpectedFailureVerdict.VACUOUS_EXPECTED_FAILURE,
                        ExpectedFailureVerdict.ofConstructionFailure(errno, kind),
                        errno + " + " + kind);
            }
        }

        // Rows that are (or may be) asking for a DEFINITION to be refused: the bridge's kind decides.
        for (String errno : new String[] {null, "invalid_op", "invalid_op_illegal_arg_value", "other"}) {
            assertFalse(ExpectedFailureVerdict.assertsProjBuiltTheOperation(errno), String.valueOf(errno));
            assertEquals(ExpectedFailureVerdict.PASS_EXPECTED_FAILURE,
                    ExpectedFailureVerdict.ofConstructionFailure(errno, GieFailureKind.INVALID_DEFINITION),
                    "INVALID_DEFINITION means PROJ would refuse this too: " + errno);
            assertEquals(ExpectedFailureVerdict.VACUOUS_EXPECTED_FAILURE,
                    ExpectedFailureVerdict.ofConstructionFailure(errno, GieFailureKind.NOT_IMPLEMENTED),
                    "NOT_IMPLEMENTED is a statement about proj4j, not about the definition: " + errno);
        }

        // MISSING_GRID is genuine only under the one errno that means "PROJ could not read it either".
        assertEquals(ExpectedFailureVerdict.PASS_EXPECTED_FAILURE,
                ExpectedFailureVerdict.ofConstructionFailure(
                        "invalid_op_file_not_found_or_invalid", GieFailureKind.MISSING_GRID));
        assertEquals(ExpectedFailureVerdict.VACUOUS_EXPECTED_FAILURE,
                ExpectedFailureVerdict.ofConstructionFailure(null, GieFailureKind.MISSING_GRID));

        // No operation at all: vacuous, by the same argument.
        assertEquals(ExpectedFailureVerdict.VACUOUS_EXPECTED_FAILURE,
                ExpectedFailureVerdict.ofConstructionFailure("invalid_op", null));

        // And the two constants.
        assertEquals(ExpectedFailureVerdict.PASS_EXPECTED_FAILURE, ExpectedFailureVerdict.ofTransformFailure());
        assertEquals(ExpectedFailureVerdict.FAIL, ExpectedFailureVerdict.ofFailureToFail());
        assertEquals(AssertionOutcome.VACUOUS_EXPECTED_FAILURE,
                ExpectedFailureVerdict.VACUOUS_EXPECTED_FAILURE.outcome());
        assertEquals(AssertionOutcome.PASS, ExpectedFailureVerdict.PASS_EXPECTED_FAILURE.outcome());
    }

    @Test
    @DisplayName("errno name resolution is a lower-cased prefix match, 9999 on no match")
    void errnoNamesResolveLikeTheC() {
        assertEquals("invalid_op", GieRunner.Errno.canonical("invalid_op"));
        assertEquals("invalid_op", GieRunner.Errno.canonical("INVALID_OP"));
        // A prefix resolves to the first table entry it matches, in declaration order.
        assertEquals("coord_transfm", GieRunner.Errno.canonical("coord_transfm"));
        assertEquals("coord_transfm_outside_grid", GieRunner.Errno.canonical("coord_transfm_outside_g"));
        // The legacy names still in the corpus prefix-match nothing, which upstream turns into 9999.
        assertEquals(null, GieRunner.Errno.canonical("pjd_err_axis"));
        assertEquals(null, GieRunner.Errno.canonical("pjd_err_malformed_pipeline"));
        assertEquals(null, GieRunner.Errno.canonical("pjd_err_dont_skip"));
        assertEquals(null, GieRunner.Errno.canonical(""));
        assertEquals(null, GieRunner.Errno.canonical(null));
    }

    // ------------------------------------------------------------------------------ roundtrip

    @Test
    @DisplayName("roundtrip reproduces proj_roundtrip's half-step phasing")
    void roundtripPhasing() throws IOException {
        GieFileResult result = run("roundtrip.gie");
        List<GieAssertionResult> a = result.assertions();
        assertEquals(6, result.total());

        // 7.07 mm residual: too big for 1 mm, small enough for 1 m.
        assertEquals(AssertionOutcome.FAIL, a.get(0).outcome(), "5 trips at 1 mm must fail");
        assertEquals(AssertionOutcome.PASS, a.get(1).outcome(), "5 trips at 1 m must pass");

        // The bracket. lossy = 0.002 gives 0.001 per inverse-of-forward cycle, so five trips leave
        // 0.005 in each of x and y and hypot(0.005, 0.005) = 7.0710678 mm. Round-tripping in the wrong
        // order would leave 14.14 mm and fail both; doing a single cycle would leave 1.41 mm and pass
        // both.
        assertEquals(AssertionOutcome.FAIL, a.get(2).outcome(), "the residual must exceed 7 mm");
        assertEquals(AssertionOutcome.PASS, a.get(3).outcome(), "the residual must be under 8 mm");
        assertTrue(a.get(2).detail().contains("7.07"),
                "the measured residual should be reported: " + a.get(2).detail());
    }

    @Test
    @DisplayName("the phasing is forward, then n-1 inverse/forward pairs, then inverse")
    void roundtripCallSequence() throws IOException {
        factory.reset();
        String resource = "/runner-fixtures/roundtrip-one-block.gie";
        // Build the single-block source inline rather than as a file: the assertion here is about the
        // exact call sequence, and mixing several blocks into one log would obscure it.
        String source = "<gie-strict>\n"
                + "operation fake lossy=0.002\n"
                + "accept 1000 2000\n"
                + "roundtrip 4 1 m\n"
                + "</gie-strict>\n";
        GieFile lexed = GieLexer.lex(resource, source);
        GieFileResult result = GieRunner.using(factory, GieGridAvailability.NoneAvailable.INSTANCE)
                .run("runner-fixtures/roundtrip-one-block.gie", lexed);

        assertEquals(1, result.total());
        // proj_roundtrip: one half-step forward, then (n-1) full (inverse, forward) cycles taken out of
        // phase, then one half-step back. For n = 4 that is F IF IF IF I -- four of each, alternating,
        // opening forward and closing inverse. Any other shape is a different function.
        assertEquals("FIFIFIFI", factory.callSequence());
    }

    @Test
    @DisplayName("roundtrip with no argument means 100 trips")
    void roundtripDefaultsToOneHundredTrips() throws IOException {
        factory.reset();
        String source = "<gie-strict>\n"
                + "operation fake lossy=0.002\n"
                + "accept 1000 2000\n"
                + "tolerance 1 m\n"
                + "roundtrip\n"
                + "</gie-strict>\n";
        GieFile lexed = GieLexer.lex("inline", source);
        GieFileResult result = GieRunner.using(factory, GieGridAvailability.NoneAvailable.INSTANCE)
                .run("runner-fixtures/inline.gie", lexed);

        assertEquals(1, result.total());
        assertEquals(AssertionOutcome.PASS, result.assertions().get(0).outcome());
        assertEquals(200, factory.callSequence().length(), "100 trips means 100 forward and 100 inverse");
    }

    @Test
    @DisplayName("the roundtrip residual metric is chosen by angular INPUT, not output")
    void roundtripMetricFollowsAngularInput() throws IOException {
        GieFileResult result = run("roundtrip.gie");
        GieAssertionResult angular = result.assertions().get(5);

        // Identical arithmetic to the passing Euclidean case, but with a RADIANS left-hand side the same
        // 0.005 residual is 0.005 radians and proj_lpz_dist turns it into tens of kilometres. Selecting
        // the metric from the output side instead would have measured a bare 0.00707 and passed.
        assertEquals(AssertionOutcome.FAIL, angular.outcome());
        assertTrue(angular.detail().contains("roundtrip deviation"), angular.detail());
    }

    @Test
    @DisplayName("roundtrip against an uncreatable operation is a failure, not a skip")
    void roundtripWithoutAnOperationFails() throws IOException {
        String source = "<gie-strict>\n"
                + "operation not-a-fake\n"
                + "accept 1 2\n"
                + "roundtrip 3\n"
                + "</gie-strict>\n";
        GieFileResult result = GieRunner.using(factory, GieGridAvailability.NoneAvailable.INSTANCE)
                .run("runner-fixtures/inline.gie", GieLexer.lex("inline", source));
        assertEquals(1, result.total());
        assertEquals(AssertionOutcome.FAIL, result.assertions().get(0).outcome());
    }

    @Test
    @DisplayName("an out-of-range trip count is a failure, not an exception")
    void roundtripRejectsAnImpossibleTripCount() throws IOException {
        String source = "<gie-strict>\n"
                + "operation fake shift=0\n"
                + "accept 1 2\n"
                + "roundtrip 0\n"
                + "roundtrip 2000000\n"
                + "</gie-strict>\n";
        GieFileResult result = GieRunner.using(factory, GieGridAvailability.NoneAvailable.INSTANCE)
                .run("runner-fixtures/inline.gie", GieLexer.lex("inline", source));
        assertEquals(2, result.total());
        assertEquals(AssertionOutcome.FAIL, result.assertions().get(0).outcome());
        assertEquals(AssertionOutcome.FAIL, result.assertions().get(1).outcome());
        assertTrue(result.assertions().get(0).detail().contains("invalid number of roundtrips"),
                result.assertions().get(0).detail());
    }

    // ------------------------------------------------------------------------------ misc

    @Test
    @DisplayName("direction only inspects the first non-space character")
    void directionParsesLikeTheC() throws IOException {
        // gie.cpp:595-616 switches on *endp after skipping whitespace: F/f forward, I/i/R/r inverse,
        // anything else an error that leaves the direction alone. "reverse" is therefore inverse, and
        // "forwards" is forward.
        String source = "<gie-strict>\n"
                + "operation fake shift=10\n"
                + "direction reverse\n"
                + "accept 1 2\n"
                + "expect -9 -8\n"
                + "direction forwards\n"
                + "accept 1 2\n"
                + "expect 11 12\n"
                + "direction sideways\n"
                + "accept 1 2\n"
                + "expect 11 12\n"
                + "</gie-strict>\n";
        GieFileResult result = GieRunner.using(factory, GieGridAvailability.NoneAvailable.INSTANCE)
                .run("runner-fixtures/inline.gie", GieLexer.lex("inline", source));
        assertEquals(
                java.util.Arrays.asList(AssertionOutcome.PASS, AssertionOutcome.PASS, AssertionOutcome.PASS),
                outcomes(result),
                "reverse -> inverse, forwards -> forward, sideways -> unchanged");
    }

    @Test
    @DisplayName("a display name carries file:block:index, the line, the assertion and the operation")
    void displayNamesAreDiagnosable() throws IOException {
        GieFileResult result = run("reset.gie");
        String name = result.assertions().get(2).displayName();
        assertTrue(name.startsWith("runner-fixtures/reset.gie:1:0 "), name);
        assertTrue(name.contains("expect 3 4"), name);
        assertTrue(name.contains("fake shift=10"), name);
    }

    @Test
    @DisplayName("no fixture leaks crs_dst_is_lat_lon_or_y_x into a plain operation")
    void theLatLonFlagDoesNotLeak() throws IOException {
        assertEquals(0, run("reset.gie").leakedCrsDstFlagAssertions());
        assertEquals(0, run("roundtrip.gie").leakedCrsDstFlagAssertions());
    }

    @Test
    @DisplayName("a completed crs_src + crs_dst pair opens a block and is then consumed")
    void crsSrcAndCrsDstPairsOpenBlocks() throws IOException {
        // gie.cpp:754-755 clears both after firing, so the second pair needs both verbs again. The
        // third crs_dst below therefore does NOT open a block on its own.
        String source = "<gie-strict>\n"
                + "crs_src EPSG:4326\n"
                + "crs_dst EPSG:25832\n"
                + "accept 1 2\n"
                + "expect 1 2\n"
                + "crs_dst EPSG:25833\n"
                + "expect 1 2\n"
                + "</gie-strict>\n";
        GieFileResult result = GieRunner.using(factory, GieGridAvailability.NoneAvailable.INSTANCE)
                .run("runner-fixtures/inline.gie", GieLexer.lex("inline", source));
        assertEquals(1, result.operationBlocks());
        assertEquals(2, result.total());
        assertEquals(0, result.assertions().get(0).key().operationBlockIndex());
        assertEquals(0, result.assertions().get(1).key().operationBlockIndex(),
                "the lone crs_dst must not open a new block");
        assertEquals(1, result.assertions().get(1).key().assertionIndex());
    }

    @Test
    @DisplayName("the ellipsoid resolver distinguishes the two no-ellipsoid defaults")
    void ellipsoidDefaultsDifferBetweenPipelineAndProjection() {
        assertEquals(1.0 / 298.257223563,
                GieEllipsoidResolver.comparatorFor("proj=merc").geodesic().Flattening(),
                0.0,
                "a bare projection gets WGS84");
        assertEquals(1.0 / 298.257222101,
                GieEllipsoidResolver.comparatorFor("proj=pipeline step proj=merc").geodesic().Flattening(),
                0.0,
                "a pipeline with no global ellipsoid gets GRS80");
        assertEquals(6400000.0,
                GieEllipsoidResolver.comparatorFor("proj=merc a=6400000 rf=297")
                        .geodesic()
                        .EquatorialRadius(),
                0.0);
        assertEquals(1.0 / 297.0,
                GieEllipsoidResolver.comparatorFor("proj=merc a=6400000 rf=297").geodesic().Flattening(),
                0.0);
        assertEquals(0.0,
                GieEllipsoidResolver.comparatorFor("proj=merc R=6400000").geodesic().Flattening(),
                0.0,
                "+R is a sphere");
        // A parameter that belongs to a step must not be mistaken for a global one.
        assertEquals(1.0 / 298.257222101,
                GieEllipsoidResolver.comparatorFor("proj=pipeline step proj=merc ellps=clrk66")
                        .geodesic()
                        .Flattening(),
                0.0);
    }

    @Test
    @DisplayName("a deliberately invalid ellipsoid falls back instead of aborting the file")
    void ellipsoidResolverSurvivesTheDefinitionsThatExistToBeRejected() {
        // Every one of these is a real operation in gie/ellipsoid.gie, written to be refused. Before
        // this guard existed, "+R_a +a=2 +f=2" made GeographicLib throw from its constructor and the
        // whole file -- 40 valid assertions included -- was reported as a lexer failure.
        String[] rejected = {
            "proj=merc a=-1",
            "proj=merc R=0",
            "proj=merc a=1 es=-1",
            "proj=merc R_a a=2 f=2",
            "proj=merc a=2 f=1",
            "proj=merc ellps=GRS80000000000",
            "proj=merc a=NaN rf=NaN",
        };
        for (String args : rejected) {
            assertNotNull(GieEllipsoidResolver.comparatorFor(args).geodesic(),
                    "no comparator for \"" + args + "\"");
        }
    }
}

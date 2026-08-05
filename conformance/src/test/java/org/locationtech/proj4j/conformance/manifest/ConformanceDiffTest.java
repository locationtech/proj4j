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
package org.locationtech.proj4j.conformance.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConformanceDiffTest {

    private static final AssertionKey PASSING = AssertionKey.of("gie/builtins.gie", 0, 0, "00000001");
    private static final AssertionKey FAILING = AssertionKey.of("gie/builtins.gie", 0, 1, "00000002");
    private static final AssertionKey SKIPPING = AssertionKey.of("gie/epsg_grid.gie", 0, 0, "00000003");

    private static ExpectedOutcomeManifest manifest(ManifestEntry... entries) {
        return ExpectedOutcomeManifest.of(Arrays.asList(entries));
    }

    private static ManifestEntry expectFail(AssertionKey key, String reason) {
        return ManifestEntry.of(key, AssertionOutcome.FAIL, reason);
    }

    private static ManifestEntry expectSkip(AssertionKey key, String reason) {
        return ManifestEntry.of(key, AssertionOutcome.SKIP, reason);
    }

    private static ManifestEntry expectVacuous(AssertionKey key, String reason) {
        return ManifestEntry.of(key, AssertionOutcome.VACUOUS_EXPECTED_FAILURE, reason);
    }

    private static ObservedRun run(AssertionKey key, AssertionOutcome outcome) {
        return ObservedRun.builder().record(key, outcome).build();
    }

    private static DiffEntry only(DiffResult diff, DiffClassification classification) {
        List<DiffEntry> entries = diff.entries(classification);
        assertEquals(1, entries.size(), "expected exactly one " + classification + " in " + diff);
        return entries.get(0);
    }

    // ------------------------------------------------------------------------------ classification

    @Test
    void expectedPassAndPassedIsUnchanged() {
        DiffResult diff = ConformanceDiff.compare(ExpectedOutcomeManifest.empty(), run(PASSING, AssertionOutcome.PASS));
        assertEquals(1, diff.count(DiffClassification.UNCHANGED));
        assertEquals(AssertionOutcome.PASS, only(diff, DiffClassification.UNCHANGED).expected());
        assertFalse(diff.shouldFailBuild());
    }

    @Test
    void expectedPassButFailedIsARegression() {
        DiffResult diff = ConformanceDiff.compare(ExpectedOutcomeManifest.empty(), run(PASSING, AssertionOutcome.FAIL));
        assertEquals(1, diff.count(DiffClassification.REGRESSED));
        assertEquals(PASSING, only(diff, DiffClassification.REGRESSED).key());
        assertTrue(diff.shouldFailBuild());
        assertTrue(diff.failureSummary().contains("REGRESSED"), diff.failureSummary());
    }

    @Test
    void expectedPassButSkippedIsAlsoARegressionBecauseASkipIsNotAPass() {
        DiffResult diff = ConformanceDiff.compare(ExpectedOutcomeManifest.empty(), run(PASSING, AssertionOutcome.SKIP));
        assertEquals(1, diff.count(DiffClassification.REGRESSED));
        assertEquals(AssertionOutcome.SKIP, only(diff, DiffClassification.REGRESSED).observed());
        assertTrue(diff.shouldFailBuild());
    }

    @Test
    void expectedFailAndFailedIsStillFailing() {
        DiffResult diff = ConformanceDiff.compare(
                manifest(expectFail(FAILING, "laea inverse drift")), run(FAILING, AssertionOutcome.FAIL));
        assertEquals(1, diff.count(DiffClassification.STILL_FAILING));
        assertEquals("laea inverse drift", only(diff, DiffClassification.STILL_FAILING).reason());
        assertFalse(diff.shouldFailBuild());
        assertEquals(0, diff.outcomeChangedKeys().size());
    }

    // ---------------------------------------------------------- vacuous -> measured is never a regression

    @Test
    void aVacuousAssertionThatStartsPassingIsNotARegression() {
        // The adams_hemi transition: 388 rows that measured nothing begin measuring something. Whichever
        // way they land, the one thing they must not be called is REGRESSED.
        DiffResult diff = ConformanceDiff.compare(
                manifest(expectVacuous(FAILING, "adams_hemi is not implemented, so nothing was measured")),
                run(FAILING, AssertionOutcome.PASS));

        assertEquals(0, diff.count(DiffClassification.REGRESSED),
                "measuring something for the first time is not a regression");
        assertEquals(1, diff.count(DiffClassification.UNEXPECTED_PASS));
        assertEquals(AssertionOutcome.VACUOUS_EXPECTED_FAILURE,
                only(diff, DiffClassification.UNEXPECTED_PASS).expected());
        // It does stop the build, as every UNEXPECTED_PASS does, so that the win is banked in the
        // manifest rather than left as a stale "unmeasured" claim.
        assertTrue(diff.shouldFailBuild());
        assertTrue(diff.failureSummary().contains("UNEXPECTED_PASS"), diff.failureSummary());
    }

    @Test
    void aVacuousAssertionThatStartsFailingIsNotARegressionEither() {
        DiffResult diff = ConformanceDiff.compare(
                manifest(expectVacuous(FAILING, "no implementation, so unmeasured")),
                run(FAILING, AssertionOutcome.FAIL));

        assertEquals(0, diff.count(DiffClassification.REGRESSED));
        assertEquals(1, diff.count(DiffClassification.STILL_FAILING));
        assertFalse(diff.shouldFailBuild(),
                "an unmeasured assertion becoming a measured failure is progress, not a break");
        assertEquals(1, diff.outcomeChangedKeys().size(),
                "the change of flavour is still worth surfacing");
    }

    @Test
    void aVacuousAssertionThatStaysVacuousIsStillFailing() {
        DiffResult diff = ConformanceDiff.compare(
                manifest(expectVacuous(FAILING, "still no implementation")),
                run(FAILING, AssertionOutcome.VACUOUS_EXPECTED_FAILURE));

        assertEquals(1, diff.count(DiffClassification.STILL_FAILING));
        assertEquals(0, diff.outcomeChangedKeys().size());
        assertFalse(diff.shouldFailBuild());
        assertEquals(1, diff.vacuous());
    }

    @Test
    void anAssertionThatUsedToPassAndIsNowVacuousIsARegression() {
        // The other direction, and it must break: an assertion that demonstrated something and now
        // demonstrates nothing has lost coverage exactly as a new skip would have.
        DiffResult diff = ConformanceDiff.compare(
                ExpectedOutcomeManifest.empty(), run(PASSING, AssertionOutcome.VACUOUS_EXPECTED_FAILURE));

        assertEquals(1, diff.count(DiffClassification.REGRESSED));
        assertEquals(AssertionOutcome.VACUOUS_EXPECTED_FAILURE,
                only(diff, DiffClassification.REGRESSED).observed());
        assertTrue(diff.shouldFailBuild());
    }

    @Test
    void expectedSkipAndSkippedIsStillFailing() {
        DiffResult diff = ConformanceDiff.compare(
                manifest(expectSkip(SKIPPING, "require_grid")), run(SKIPPING, AssertionOutcome.SKIP));
        assertEquals(1, diff.count(DiffClassification.STILL_FAILING));
        assertFalse(diff.shouldFailBuild());
    }

    @Test
    void expectedFailButSkippedIsStillFailingAndFlaggedAsAnOutcomeChange() {
        DiffResult diff = ConformanceDiff.compare(
                manifest(expectFail(SKIPPING, "was a real failure")), run(SKIPPING, AssertionOutcome.SKIP));
        assertEquals(1, diff.count(DiffClassification.STILL_FAILING));
        assertEquals(Collections.singleton(SKIPPING), diff.outcomeChangedKeys());
        assertFalse(diff.shouldFailBuild(), "a FAIL becoming a SKIP is a provisioning change, not a regression");
    }

    @Test
    void expectedFailButPassedIsAnUnexpectedPassAndFailsTheBuild() {
        DiffResult diff = ConformanceDiff.compare(
                manifest(expectFail(FAILING, "no inverse yet")), run(FAILING, AssertionOutcome.PASS));
        assertEquals(1, diff.count(DiffClassification.UNEXPECTED_PASS));
        DiffEntry entry = only(diff, DiffClassification.UNEXPECTED_PASS);
        assertEquals(AssertionOutcome.FAIL, entry.expected());
        assertEquals(AssertionOutcome.PASS, entry.observed());
        assertEquals("no inverse yet", entry.reason());
        assertTrue(diff.shouldFailBuild(), "a landed fix must force the manifest to be updated");
        assertTrue(diff.failureSummary().contains("UNEXPECTED_PASS"), diff.failureSummary());
    }

    @Test
    void expectedSkipButPassedIsAlsoAnUnexpectedPass() {
        DiffResult diff = ConformanceDiff.compare(
                manifest(expectSkip(SKIPPING, "grid missing")), run(SKIPPING, AssertionOutcome.PASS));
        assertEquals(1, diff.count(DiffClassification.UNEXPECTED_PASS));
        assertTrue(diff.shouldFailBuild());
    }

    @Test
    void anObservedKeyOutsideTheBaselineIsNew() {
        AssertionKey added = AssertionKey.of("gie/builtins.gie", 400, 0, "0000beef");
        DiffResult diff = ConformanceDiff.compare(
                ExpectedOutcomeManifest.empty(),
                run(added, AssertionOutcome.PASS),
                CorpusIndex.of(Collections.singletonList(PASSING)));
        assertEquals(1, diff.count(DiffClassification.NEW));
        assertEquals(added, only(diff, DiffClassification.NEW).key());
        assertEquals(1, diff.count(DiffClassification.DISAPPEARED), "the baseline key was not observed");
    }

    @Test
    void aNewAssertionDoesNotByItselfFailTheBuild() {
        AssertionKey added = AssertionKey.of("gie/builtins.gie", 400, 0, "0000beef");
        DiffResult diff = ConformanceDiff.compare(
                ExpectedOutcomeManifest.empty(), run(added, AssertionOutcome.PASS), CorpusIndex.empty());
        assertEquals(1, diff.count(DiffClassification.NEW));
        assertFalse(diff.shouldFailBuild());
    }

    @Test
    void aNewAssertionThatFailsIsStillNewAndStillDoesNotFailTheBuild() {
        // Pins what DiffClassification.NEW's javadoc now says. The classification is decided before the
        // expected outcome is consulted, so an unknown key that fails is NEW, not REGRESSED. The
        // javadoc used to claim the opposite and nothing tested it -- which also means that when EVERY
        // key is NEW (an absent or empty baseline) the gate cannot fail at all. See BaselineRequirement.
        AssertionKey added = AssertionKey.of("gie/builtins.gie", 400, 0, "0000beef");
        DiffResult diff = ConformanceDiff.compare(
                ExpectedOutcomeManifest.empty(), run(added, AssertionOutcome.FAIL), CorpusIndex.empty());
        assertEquals(1, diff.count(DiffClassification.NEW));
        assertEquals(0, diff.count(DiffClassification.REGRESSED));
        assertFalse(diff.shouldFailBuild());
    }

    @Test
    void aBaselineKeyThatWasNotObservedHasDisappearedAndFailsTheBuild() {
        DiffResult diff = ConformanceDiff.compare(
                ExpectedOutcomeManifest.empty(),
                ObservedRun.empty(),
                CorpusIndex.of(Collections.singletonList(PASSING)));
        assertEquals(1, diff.count(DiffClassification.DISAPPEARED));
        DiffEntry entry = only(diff, DiffClassification.DISAPPEARED);
        assertEquals(PASSING, entry.key());
        assertNull(entry.observed(), "a disappeared assertion has no outcome; null is not SKIP");
        assertTrue(diff.shouldFailBuild());
        assertTrue(diff.failureSummary().contains("DISAPPEARED"), diff.failureSummary());
    }

    @Test
    void aManifestEntryThatMatchesNothingHasDisappearedEvenWithoutABaseline() {
        DiffResult diff =
                ConformanceDiff.compare(manifest(expectFail(FAILING, "stale entry")), ObservedRun.empty());
        assertEquals(1, diff.count(DiffClassification.DISAPPEARED));
        assertEquals(0, diff.count(DiffClassification.NEW), "a manifest key is by definition not new");
        assertTrue(diff.shouldFailBuild());
    }

    @Test
    void everyClassificationIsCountedInOneRun() {
        AssertionKey unchanged = AssertionKey.of("gie/a.gie", 0, 0, "00000001");
        AssertionKey regressed = AssertionKey.of("gie/a.gie", 0, 1, "00000002");
        AssertionKey unexpectedPass = AssertionKey.of("gie/a.gie", 0, 2, "00000003");
        AssertionKey stillFailing = AssertionKey.of("gie/a.gie", 0, 3, "00000004");
        AssertionKey disappeared = AssertionKey.of("gie/a.gie", 0, 4, "00000005");
        AssertionKey fresh = AssertionKey.of("gie/a.gie", 0, 5, "00000006");

        ExpectedOutcomeManifest expected = manifest(
                expectFail(unexpectedPass, "fixed by the pipeline engine"), expectFail(stillFailing, "adams series"));
        ObservedRun observed = ObservedRun.builder()
                .record(unchanged, AssertionOutcome.PASS)
                .record(regressed, AssertionOutcome.FAIL, "12.4 m off")
                .record(unexpectedPass, AssertionOutcome.PASS)
                .record(stillFailing, AssertionOutcome.FAIL, "0.42 m off")
                .record(fresh, AssertionOutcome.PASS)
                .build();
        CorpusIndex baseline =
                CorpusIndex.of(Arrays.asList(unchanged, regressed, unexpectedPass, stillFailing, disappeared));

        DiffResult diff = ConformanceDiff.compare(expected, observed, baseline);

        assertEquals(1, diff.count(DiffClassification.UNCHANGED));
        assertEquals(1, diff.count(DiffClassification.REGRESSED));
        assertEquals(1, diff.count(DiffClassification.UNEXPECTED_PASS));
        assertEquals(1, diff.count(DiffClassification.STILL_FAILING));
        assertEquals(1, diff.count(DiffClassification.NEW));
        assertEquals(1, diff.count(DiffClassification.DISAPPEARED));
        assertEquals(6, diff.total());
        assertEquals(3, diff.passing());
        assertEquals(2, diff.failing());
        assertEquals(0, diff.skipped());
        assertTrue(diff.shouldFailBuild());
        assertEquals("12.4 m off", only(diff, DiffClassification.REGRESSED).detail());
    }

    @Test
    void countsCoverEveryClassificationIncludingZeroes() {
        DiffResult diff = ConformanceDiff.compare(ExpectedOutcomeManifest.empty(), ObservedRun.empty());
        assertEquals(DiffClassification.values().length, diff.counts().size());
        for (DiffClassification classification : DiffClassification.values()) {
            assertEquals(Integer.valueOf(0), diff.counts().get(classification));
        }
        assertFalse(diff.shouldFailBuild());
        assertEquals("", diff.failureSummary());
    }

    @Test
    void entriesAreReturnedInCanonicalKeyOrder() {
        AssertionKey first = AssertionKey.of("gie/a.gie", 0, 0, "00000001");
        AssertionKey second = AssertionKey.of("gie/a.gie", 2, 0, "00000002");
        AssertionKey third = AssertionKey.of("gie/b.gie", 0, 0, "00000003");
        ObservedRun observed = ObservedRun.builder()
                .record(third, AssertionOutcome.PASS)
                .record(first, AssertionOutcome.PASS)
                .record(second, AssertionOutcome.FAIL)
                .build();
        DiffResult diff = ConformanceDiff.compare(ExpectedOutcomeManifest.empty(), observed);
        List<AssertionKey> order = new ArrayList<AssertionKey>();
        for (DiffEntry entry : diff.entries()) {
            order.add(entry.key());
        }
        assertEquals(Arrays.asList(first, second, third), order);
    }

    @Test
    void skipsAreCountedSeparatelyFromPasses() {
        ObservedRun observed = ObservedRun.builder()
                .record(PASSING, AssertionOutcome.PASS)
                .record(SKIPPING, AssertionOutcome.SKIP)
                .build();
        DiffResult diff = ConformanceDiff.compare(
                manifest(expectSkip(SKIPPING, "require_grid us_nga_egm08_25.tif")), observed);
        assertEquals(1, diff.passing());
        assertEquals(1, diff.skipped());
        assertEquals(0, diff.failing());
    }

    @Test
    void classificationsDeclareTheirOwnBuildImpact() {
        assertFalse(DiffClassification.UNCHANGED.failsBuild());
        assertTrue(DiffClassification.REGRESSED.failsBuild());
        assertTrue(DiffClassification.UNEXPECTED_PASS.failsBuild());
        assertFalse(DiffClassification.STILL_FAILING.failsBuild());
        assertFalse(DiffClassification.NEW.failsBuild());
        assertTrue(DiffClassification.DISAPPEARED.failsBuild());
    }

    @Test
    void keysAccessorReturnsTheOffendingKeys() {
        DiffResult diff = ConformanceDiff.compare(ExpectedOutcomeManifest.empty(), run(PASSING, AssertionOutcome.FAIL));
        assertEquals(Collections.singleton(PASSING), diff.keys(DiffClassification.REGRESSED));
        assertTrue(diff.keys(DiffClassification.UNCHANGED).isEmpty());
    }
}

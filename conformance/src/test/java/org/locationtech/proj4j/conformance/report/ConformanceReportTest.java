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
package org.locationtech.proj4j.conformance.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.proj4j.conformance.manifest.AssertionKey;
import org.locationtech.proj4j.conformance.manifest.AssertionOutcome;
import org.locationtech.proj4j.conformance.manifest.ConformanceDiff;
import org.locationtech.proj4j.conformance.manifest.CorpusIndex;
import org.locationtech.proj4j.conformance.manifest.DiffResult;
import org.locationtech.proj4j.conformance.manifest.ExpectedOutcomeManifest;
import org.locationtech.proj4j.conformance.manifest.ManifestEntry;
import org.locationtech.proj4j.conformance.manifest.ObservedRun;

class ConformanceReportTest {

    private static final AssertionKey PASS_1 = AssertionKey.of("gie/builtins.gie", 0, 0, "00000001");
    private static final AssertionKey PASS_2 = AssertionKey.of("gie/builtins.gie", 0, 1, "00000002");
    private static final AssertionKey FAIL_1 = AssertionKey.of("gie/builtins.gie", 1, 0, "00000003");
    private static final AssertionKey SKIP_1 = AssertionKey.of("gie/epsg_grid.gie", 0, 0, "00000004");
    private static final AssertionKey LAEA = AssertionKey.of("gigs/5110.gie.failing", 2, 0, "00000005");
    private static final AssertionKey VACUOUS_1 = AssertionKey.of("gie/adams_hemi.gie", 0, 0, "00000006");
    private static final AssertionKey VACUOUS_2 = AssertionKey.of("gie/adams_hemi.gie", 0, 1, "00000007");

    private static ObservedRun sampleRun() {
        return ObservedRun.builder()
                .record(PASS_1, AssertionOutcome.PASS)
                .record(PASS_2, AssertionOutcome.PASS)
                .record(FAIL_1, AssertionOutcome.FAIL, "deviation 12.4 m")
                .record(SKIP_1, AssertionOutcome.SKIP, "require_grid us_nga_egm08_25.tif")
                .record(LAEA, AssertionOutcome.FAIL, "1312 mm at lat 30")
                .build();
    }

    /** {@link #sampleRun()} plus two vacuous expected failures, i.e. adams_hemi in miniature. */
    private static ObservedRun runWithVacuous() {
        return ObservedRun.builder()
                .record(PASS_1, AssertionOutcome.PASS)
                .record(PASS_2, AssertionOutcome.PASS)
                .record(FAIL_1, AssertionOutcome.FAIL, "deviation 12.4 m")
                .record(SKIP_1, AssertionOutcome.SKIP, "require_grid us_nga_egm08_25.tif")
                .record(LAEA, AssertionOutcome.FAIL, "1312 mm at lat 30")
                .record(VACUOUS_1, AssertionOutcome.VACUOUS_EXPECTED_FAILURE, "VACUOUS: adams_hemi absent")
                .record(VACUOUS_2, AssertionOutcome.VACUOUS_EXPECTED_FAILURE, "VACUOUS: adams_hemi absent")
                .build();
    }

    // ----------------------------------------------------------------------------------- headline

    @Test
    void headlineReportsSkipsSeparatelyFromPasses() {
        String headline = ConformanceReport.headline(sampleRun(), 5);
        assertEquals("2/5 genuine passes (2 failing, 1 skipped)", headline);
    }

    @Test
    void headlineNeverFoldsASkipIntoThePassCount() {
        ObservedRun allSkipped = ObservedRun.builder()
                .record(PASS_1, AssertionOutcome.SKIP)
                .record(PASS_2, AssertionOutcome.SKIP)
                .build();
        String headline = ConformanceReport.headline(allSkipped, 2);
        assertEquals("0/2 genuine passes (0 failing, 2 skipped)", headline);
        assertFalse(headline.startsWith("2/2"), "a corpus that skips everything is not a conformant corpus");
    }

    @Test
    void headlineExcludesVacuousFromBothTheNumeratorAndTheDenominator() {
        // 7 assertions: 2 pass, 2 fail, 1 skip, 2 vacuous. The honest ratio is 2/5, not 4/7 and not 2/7.
        String headline = ConformanceReport.headline(runWithVacuous(), 7);

        assertTrue(headline.startsWith("2/5 genuine passes (2 failing, 1 skipped)"), headline);
        assertFalse(headline.startsWith("4/"), "a vacuous expected failure is never a pass");
        assertFalse(headline.startsWith("2/7"), "nor is it remaining work; it is not in the denominator");
        assertTrue(
                headline.contains("2 vacuous expect failure = UNMEASURED, excluded from both numerator and denominator"),
                headline);
        assertEquals(5, ConformanceReport.measuredDenominator(runWithVacuous(), 7));
    }

    @Test
    void headlineSaysNothingAboutVacuityWhenThereIsNone() {
        assertFalse(ConformanceReport.headline(sampleRun(), 5).contains("vacuous"));
    }

    @Test
    void headlineNamesTheAssertionsThatWereNotRunAtAll() {
        assertEquals(
                "2/7923 genuine passes (2 failing, 1 skipped); 7918 not run",
                ConformanceReport.headline(sampleRun(), ConformanceReport.TOTAL_ASSERTIONS));
    }

    @Test
    void theDenominatorIsWhatGieActuallyEvaluatesAndTheRestIsNamed() {
        // 7,923 = 6,962 expect + 961 roundtrip. The 94 out-of-block lines of DHDN_ETRS89.gie are not
        // "not run": gie never reads them, so they are reported as excluded rather than as missing.
        assertEquals(7923, ConformanceReport.TOTAL_ASSERTIONS);
        assertEquals(94, ConformanceReport.EXCLUDED_OUT_OF_BLOCK);
        assertEquals(8017, ConformanceReport.TOTAL_ASSERTIONS + ConformanceReport.EXCLUDED_OUT_OF_BLOCK,
                "together they account for every `expect` line a grep would have counted");

        String headline = ConformanceReport.headline(sampleRun());
        assertTrue(headline.contains("/7923 genuine passes"), headline);
        assertTrue(headline.endsWith("; 94 excluded (out of block)"), headline);
    }

    @Test
    void headlineOfAnEmptyRunIsNotAPass() {
        assertEquals(
                "0/7923 genuine passes (0 failing, 0 skipped); 7923 not run; 94 excluded (out of block)",
                ConformanceReport.headline(ObservedRun.empty()));
    }

    // ------------------------------------------------------------------------------ per-file table

    @Test
    void perFileTableBreaksDownEveryFileWithSkipsInTheirOwnColumn() {
        String table = ConformanceReport.perFileTable(sampleRun());
        String[] lines = table.split("\n");
        assertTrue(lines[0].startsWith("file"), lines[0]);
        assertTrue(lines[0].contains("pass") && lines[0].contains("fail") && lines[0].contains("skip"), lines[0]);
        assertTrue(lines[0].contains("vacuous"), lines[0]);

        Map<String, String> rows = rowsByFirstToken(table);
        assertEquals("gie/builtins.gie 2 1 0 0 3", rows.get("gie/builtins.gie"));
        assertEquals("gie/epsg_grid.gie 0 0 1 0 1", rows.get("gie/epsg_grid.gie"));
        assertEquals("gigs/5110.gie.failing 0 1 0 0 1", rows.get("gigs/5110.gie.failing"));
        assertEquals("TOTAL 2 2 1 0 5", rows.get("TOTAL"));
    }

    @Test
    void perFileTableShowsGenuineAndVacuousSideBySide() {
        // The row this table exists for. Before the split it read "gie/adams_hemi.gie 2 0 0 2" and
        // looked like a file that passes everything.
        Map<String, String> rows = rowsByFirstToken(ConformanceReport.perFileTable(runWithVacuous()));
        assertEquals("gie/adams_hemi.gie 0 0 0 2 2", rows.get("gie/adams_hemi.gie"));
        assertEquals("TOTAL 2 2 1 2 7", rows.get("TOTAL"));
    }

    @Test
    void perFileTableOfAnEmptyRunIsJustTheHeader() {
        String table = ConformanceReport.perFileTable(ObservedRun.empty());
        assertTrue(table.startsWith("file"), table);
        assertFalse(table.contains("TOTAL"), table);
    }

    @Test
    void perFileTableIsDeterministicAndUsesUnixLineEndings() {
        String once = ConformanceReport.perFileTable(sampleRun());
        assertEquals(once, ConformanceReport.perFileTable(sampleRun()));
        assertFalse(once.contains("\r"), "line endings must be \\n so reports compare across machines");
    }

    // -------------------------------------------------------------------------------- differences

    @Test
    void differencesListRegressionsWithKeysAndReasons() {
        ExpectedOutcomeManifest manifest = ExpectedOutcomeManifest.of(Arrays.asList(
                ManifestEntry.of(LAEA, AssertionOutcome.FAIL, "laea ellipsoidal inverse drift"),
                ManifestEntry.of(SKIP_1, AssertionOutcome.SKIP, "require_grid, tier R4")));
        // FAIL_1 is not in the manifest, so it was expected to pass: a regression.
        DiffResult diff = ConformanceDiff.compare(manifest, sampleRun(), CorpusIndex.ofRun(sampleRun()));

        String text = ConformanceReport.differences(diff);
        assertTrue(text.contains("REGRESSED"), text);
        assertTrue(text.contains(FAIL_1.toString()), text);
        assertTrue(text.contains("deviation 12.4 m"), "the run detail belongs next to the regression");
        assertFalse(text.contains(LAEA.toString()), "a still-failing assertion is not a regression");
    }

    @Test
    void differencesListUnexpectedPassesWithTheirReasonSoTheReasonCanBeDeleted() {
        ExpectedOutcomeManifest manifest = ExpectedOutcomeManifest.of(Arrays.asList(
                ManifestEntry.of(PASS_1, AssertionOutcome.FAIL, "laea ellipsoidal inverse, commit 83c91dd2")));
        ObservedRun run = ObservedRun.builder().record(PASS_1, AssertionOutcome.PASS).build();
        DiffResult diff = ConformanceDiff.compare(manifest, run, CorpusIndex.ofRun(run));

        String text = ConformanceReport.differences(diff);
        assertTrue(text.contains("UNEXPECTED PASS"), text);
        assertTrue(text.contains(PASS_1.toString()), text);
        assertTrue(text.contains("laea ellipsoidal inverse, commit 83c91dd2"), text);
        assertTrue(text.contains("regenerate"), "tell the reader how to fix it");
    }

    @Test
    void differencesListDisappearedAssertionsAsNotRun() {
        DiffResult diff = ConformanceDiff.compare(
                ExpectedOutcomeManifest.empty(),
                ObservedRun.empty(),
                CorpusIndex.of(Arrays.asList(PASS_1)));
        String text = ConformanceReport.differences(diff);
        assertTrue(text.contains("DISAPPEARED"), text);
        assertTrue(text.contains("NOT-RUN"), text);
    }

    @Test
    void differencesSayNothingIsWrongWhenNothingIsWrong() {
        ObservedRun run = ObservedRun.builder().record(PASS_1, AssertionOutcome.PASS).build();
        DiffResult diff =
                ConformanceDiff.compare(ExpectedOutcomeManifest.empty(), run, CorpusIndex.ofRun(run));
        assertEquals("No regressions, unexpected passes or disappearances.\n", ConformanceReport.differences(diff));
    }

    // ----------------------------------------------------------------------------- machine summary

    @Test
    void machineSummaryIsTabSeparatedAndKeepsSkipsSeparate() {
        ObservedRun run = sampleRun();
        DiffResult diff = ConformanceDiff.compare(ExpectedOutcomeManifest.empty(), run, CorpusIndex.ofRun(run));
        Map<String, String> metrics = metrics(ConformanceReport.machineSummary(run, diff, 7923, 94));

        assertEquals("7923", metrics.get("conformance.corpus"));
        assertEquals("5", metrics.get("conformance.evaluated"));
        assertEquals("2", metrics.get("conformance.pass"));
        assertEquals("2", metrics.get("conformance.fail"));
        assertEquals("1", metrics.get("conformance.skip"));
        assertEquals("0", metrics.get("conformance.vacuous"));
        assertEquals("7918", metrics.get("conformance.notrun"));
        assertEquals("94", metrics.get("conformance.excluded_out_of_block"));
        assertEquals("2", metrics.get("diff.unchanged"));
        assertEquals("3", metrics.get("diff.regressed"));
        assertEquals("0", metrics.get("diff.unexpected_pass"));
        assertEquals("0", metrics.get("diff.disappeared"));
        assertEquals("true", metrics.get("build.shouldFail"));
        assertEquals("2", metrics.get("file.gie/builtins.gie.pass"));
        assertEquals("1", metrics.get("file.gie/epsg_grid.gie.skip"));
        assertEquals("0", metrics.get("file.gie/builtins.gie.vacuous"));
    }

    @Test
    void machineSummaryPublishesTheRatioAsTwoMetricsSoItCannotBeRebuiltWrongly() {
        ObservedRun run = runWithVacuous();
        DiffResult diff = ConformanceDiff.compare(ExpectedOutcomeManifest.empty(), run, CorpusIndex.ofRun(run));
        Map<String, String> metrics = metrics(ConformanceReport.machineSummary(run, diff, 7, 94));

        assertEquals("2", metrics.get("conformance.pass"));
        assertEquals("5", metrics.get("conformance.measured_denominator"));
        assertEquals("2", metrics.get("conformance.vacuous"));
        assertEquals("7", metrics.get("conformance.corpus"));
        assertEquals("2", metrics.get("file.gie/adams_hemi.gie.vacuous"));
        assertEquals("0", metrics.get("file.gie/adams_hemi.gie.pass"));
    }

    @Test
    void machineSummaryReportsAQuietBuildAsSuch() {
        ObservedRun run = ObservedRun.builder().record(PASS_1, AssertionOutcome.PASS).build();
        DiffResult diff = ConformanceDiff.compare(ExpectedOutcomeManifest.empty(), run, CorpusIndex.ofRun(run));
        assertEquals("false", metrics(ConformanceReport.machineSummary(run, diff, 1)).get("build.shouldFail"));
    }

    @Test
    void machineSummaryHasExactlyTwoColumnsOnEveryLine() {
        ObservedRun run = runWithVacuous();
        DiffResult diff = ConformanceDiff.compare(ExpectedOutcomeManifest.empty(), run, CorpusIndex.ofRun(run));
        String summary = ConformanceReport.machineSummary(run, diff, 7923, 94);
        for (String line : summary.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            assertEquals(2, line.split("\t", -1).length, "not two columns: \"" + line + "\"");
        }
    }

    // ------------------------------------------------------------------------------- full render

    @Test
    void fullReportLeadsWithTheHeadlineAndEndsWithTheVerdict() {
        ObservedRun run = sampleRun();
        DiffResult diff = ConformanceDiff.compare(ExpectedOutcomeManifest.empty(), run, CorpusIndex.ofRun(run));
        String report = ConformanceReport.render(run, diff, 7923, 94);

        assertTrue(report.startsWith("2/7923 genuine passes (2 failing, 1 skipped)"), report);
        assertTrue(report.contains("still failing 0"), report);
        assertTrue(report.contains("regressed 3"), report);
        assertTrue(report.contains("gie/builtins.gie"), report);
        assertTrue(report.contains("REGRESSED"), report);
        assertTrue(report.trim().endsWith("conformance gate failed: 3 REGRESSED"), report);
    }

    @Test
    void fullReportSpellsOutAllFourCategoriesAndTheDenominatorArithmetic() {
        ObservedRun run = runWithVacuous();
        DiffResult diff = ConformanceDiff.compare(ExpectedOutcomeManifest.empty(), run, CorpusIndex.ofRun(run));
        String report = ConformanceReport.render(run, diff, 7, 94);

        assertTrue(report.contains("PASS"), report);
        assertTrue(report.contains("FAIL"), report);
        assertTrue(report.contains("SKIP"), report);
        assertTrue(report.contains("VACUOUS"), report);
        assertTrue(report.contains("EXCLUDED"), report);
        assertTrue(report.contains("UNMEASURED"), report);
        assertTrue(report.contains("denominator = 7 corpus - 2 vacuous = 5"), report);
    }

    @Test
    void categoriesNeverAddVacuityToPassesOrFailures() {
        Map<String, String> rows = rowsByFirstToken(ConformanceReport.categories(runWithVacuous(), 7, 94));
        assertTrue(rows.get("PASS").startsWith("PASS 2 "), rows.get("PASS"));
        assertTrue(rows.get("FAIL").startsWith("FAIL 2 "), rows.get("FAIL"));
        assertTrue(rows.get("SKIP").startsWith("SKIP 1 "), rows.get("SKIP"));
        assertTrue(rows.get("VACUOUS").startsWith("VACUOUS 2 "), rows.get("VACUOUS"));
        assertTrue(rows.get("EXCLUDED").startsWith("EXCLUDED 94 "), rows.get("EXCLUDED"));
        assertTrue(rows.get("VACUOUS").contains("UNMEASURED"), rows.get("VACUOUS"));
    }

    @Test
    void fullReportIsAPureFunction() {
        ObservedRun run = runWithVacuous();
        DiffResult diff = ConformanceDiff.compare(ExpectedOutcomeManifest.empty(), run, CorpusIndex.ofRun(run));
        assertEquals(
                ConformanceReport.render(run, diff, 7923, 94), ConformanceReport.render(run, diff, 7923, 94));
    }

    private static Map<String, String> metrics(String summary) {
        Map<String, String> parsed = new LinkedHashMap<String, String>();
        for (String line : summary.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] columns = line.split("\t", -1);
            parsed.put(columns[0], columns[1]);
        }
        return parsed;
    }

    /** Collapses each table row to single-spaced tokens, keyed by its first token. */
    private static Map<String, String> rowsByFirstToken(String table) {
        Map<String, String> rows = new LinkedHashMap<String, String>();
        for (String line : table.split("\n")) {
            String collapsed = line.trim().replaceAll("\\s+", " ");
            if (collapsed.isEmpty() || collapsed.startsWith("-")) {
                continue;
            }
            rows.put(collapsed.split(" ")[0], collapsed);
        }
        return rows;
    }
}

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestRegeneratorTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static final AssertionKey A = AssertionKey.of("gie/builtins.gie", 0, 0, "00000001");
    private static final AssertionKey B = AssertionKey.of("gie/builtins.gie", 0, 1, "00000002");
    private static final AssertionKey C = AssertionKey.of("gigs/5110.gie.failing", 2, 0, "00000003");

    private static final String HAND_WRITTEN =
            "syntax artefact: no trailing \" \\\" continuation, +step swallowed as a comment";

    private static ExpectedOutcomeManifest manifest(ManifestEntry... entries) {
        return ExpectedOutcomeManifest.of(Arrays.asList(entries));
    }

    @Test
    void banksAVacuousExpectedFailureAsVacuousRatherThanAsAPassOrAFailure() {
        ObservedRun observed = ObservedRun.builder()
                .record(A, AssertionOutcome.PASS)
                .record(B, AssertionOutcome.VACUOUS_EXPECTED_FAILURE, "VACUOUS: adams_hemi is not implemented")
                .build();

        ExpectedOutcomeManifest regenerated =
                ManifestRegenerator.regenerate(ExpectedOutcomeManifest.empty(), observed);

        // Omitting it would claim it passes; writing FAIL would claim it was measured. Neither is true,
        // and both would make the eventual implementation read as a regression.
        assertEquals(1, regenerated.size(), "the pass is absent by convention, the vacuity is present");
        assertFalse(regenerated.contains(A));
        assertEquals(AssertionOutcome.VACUOUS_EXPECTED_FAILURE, regenerated.expectedOutcome(B));
        assertEquals("VACUOUS: adams_hemi is not implemented", regenerated.reason(B));
        assertTrue(regenerated.render().contains("\tVACUOUS_EXPECTED_FAILURE\t"), regenerated.render());
    }

    @Test
    void aBankedVacuityThatBecomesMeasuredIsNotARegressionOnTheNextRun() {
        // The full round trip: bank a vacuous row, then observe the run after the implementation lands.
        ExpectedOutcomeManifest banked = ManifestRegenerator.regenerate(
                ExpectedOutcomeManifest.empty(),
                ObservedRun.builder()
                        .record(A, AssertionOutcome.VACUOUS_EXPECTED_FAILURE, "no implementation")
                        .record(B, AssertionOutcome.VACUOUS_EXPECTED_FAILURE, "no implementation")
                        .build());

        ObservedRun afterTheFix = ObservedRun.builder()
                .record(A, AssertionOutcome.PASS)
                .record(B, AssertionOutcome.FAIL, "0.42 m off")
                .build();
        DiffResult diff = ConformanceDiff.compare(banked, afterTheFix, CorpusIndex.ofRun(afterTheFix));

        assertEquals(0, diff.count(DiffClassification.REGRESSED),
                "implementing the missing operator must never read as a regression");
        assertEquals(1, diff.count(DiffClassification.UNEXPECTED_PASS));
        assertEquals(1, diff.count(DiffClassification.STILL_FAILING));
    }

    @Test
    void preservesAHandWrittenReasonWhenTheOutcomeIsUnchanged() {
        ExpectedOutcomeManifest previous = manifest(ManifestEntry.of(C, AssertionOutcome.FAIL, HAND_WRITTEN));
        ObservedRun observed =
                ObservedRun.builder().record(C, AssertionOutcome.FAIL, "1312 mm off at lat 30").build();

        ExpectedOutcomeManifest regenerated = ManifestRegenerator.regenerate(previous, observed);

        assertEquals(HAND_WRITTEN, regenerated.reason(C), "the run's detail must not clobber a triaged reason");
        assertEquals(AssertionOutcome.FAIL, regenerated.expectedOutcome(C));
    }

    @Test
    void replacesTheReasonWhenTheOutcomeChanged() {
        ExpectedOutcomeManifest previous = manifest(ManifestEntry.of(C, AssertionOutcome.FAIL, HAND_WRITTEN));
        ObservedRun observed = ObservedRun.builder()
                .record(C, AssertionOutcome.SKIP, "require_grid fr_ign_RAF20.tif not resolvable")
                .build();

        ExpectedOutcomeManifest regenerated = ManifestRegenerator.regenerate(previous, observed);

        assertEquals(AssertionOutcome.SKIP, regenerated.expectedOutcome(C));
        assertEquals("require_grid fr_ign_RAF20.tif not resolvable", regenerated.reason(C));
    }

    @Test
    void seedsANewEntryFromTheRunDetail() {
        ObservedRun observed =
                ObservedRun.builder().record(A, AssertionOutcome.FAIL, "0.42 m off (adams ell_int_5)").build();
        ExpectedOutcomeManifest regenerated =
                ManifestRegenerator.regenerate(ExpectedOutcomeManifest.empty(), observed);
        assertEquals("0.42 m off (adams ell_int_5)", regenerated.reason(A));
    }

    @Test
    void leavesTheReasonEmptyWhenTheRunHasNoDetail() {
        ObservedRun observed = ObservedRun.builder().record(A, AssertionOutcome.FAIL).build();
        assertEquals("", ManifestRegenerator.regenerate(ExpectedOutcomeManifest.empty(), observed).reason(A));
    }

    @Test
    void dropsEntriesForAssertionsThatNowPass() {
        ExpectedOutcomeManifest previous = manifest(
                ManifestEntry.of(A, AssertionOutcome.FAIL, "was failing"),
                ManifestEntry.of(B, AssertionOutcome.FAIL, HAND_WRITTEN));
        ObservedRun observed = ObservedRun.builder()
                .record(A, AssertionOutcome.PASS)
                .record(B, AssertionOutcome.FAIL, "still off")
                .build();

        ExpectedOutcomeManifest regenerated = ManifestRegenerator.regenerate(previous, observed);

        assertEquals(1, regenerated.size());
        assertFalse(regenerated.contains(A), "a passing assertion must vanish from the manifest");
        assertEquals(HAND_WRITTEN, regenerated.reason(B));
    }

    @Test
    void dropsEntriesWhoseKeyIsNoLongerObserved() {
        ExpectedOutcomeManifest previous = manifest(ManifestEntry.of(A, AssertionOutcome.FAIL, "upstream edited this"));
        ObservedRun observed = ObservedRun.builder().record(B, AssertionOutcome.FAIL, "new key").build();

        ExpectedOutcomeManifest regenerated = ManifestRegenerator.regenerate(previous, observed);

        assertEquals(1, regenerated.size());
        assertFalse(regenerated.contains(A));
        assertTrue(regenerated.contains(B));
    }

    @Test
    void aRegeneratedManifestPassesItsOwnGate() {
        ObservedRun observed = ObservedRun.builder()
                .record(A, AssertionOutcome.PASS)
                .record(B, AssertionOutcome.FAIL, "off by 0.42 m")
                .record(C, AssertionOutcome.SKIP, "require_grid")
                .build();
        ExpectedOutcomeManifest regenerated =
                ManifestRegenerator.regenerate(ExpectedOutcomeManifest.empty(), observed);
        DiffResult diff = ConformanceDiff.compare(regenerated, observed, CorpusIndex.ofRun(observed));
        assertFalse(diff.shouldFailBuild(), diff.failureSummary());
        assertEquals(1, diff.count(DiffClassification.UNCHANGED));
        assertEquals(2, diff.count(DiffClassification.STILL_FAILING));
    }

    @Test
    void regenerationIsIdempotent() {
        ObservedRun observed = ObservedRun.builder()
                .record(A, AssertionOutcome.FAIL, "detail")
                .record(C, AssertionOutcome.SKIP, "grid")
                .build();
        ExpectedOutcomeManifest once = ManifestRegenerator.regenerate(ExpectedOutcomeManifest.empty(), observed);
        ExpectedOutcomeManifest twice = ManifestRegenerator.regenerate(once, observed);
        assertEquals(once.render(), twice.render());
    }

    @Test
    void reasonsSurviveAFullFileRoundTrip(@TempDir Path dir) throws IOException {
        Path manifestPath = dir.resolve("expected-failures.tsv");
        Path indexPath = dir.resolve("corpus-index.tsv");
        manifest(ManifestEntry.of(C, AssertionOutcome.FAIL, HAND_WRITTEN)).store(manifestPath);

        ObservedRun observed = ObservedRun.builder()
                .record(A, AssertionOutcome.PASS)
                .record(C, AssertionOutcome.FAIL, "1312 mm off at lat 30")
                .build();
        ExpectedOutcomeManifest written = ManifestRegenerator.regenerateInPlace(manifestPath, indexPath, observed);

        assertEquals(HAND_WRITTEN, written.reason(C));
        assertEquals(HAND_WRITTEN, ExpectedOutcomeManifest.load(manifestPath).reason(C));
        assertTrue(new String(Files.readAllBytes(manifestPath), UTF_8).contains(HAND_WRITTEN));

        CorpusIndex index = CorpusIndex.load(indexPath);
        assertEquals(2, index.size());
        assertTrue(index.contains(A), "the index must record passing keys too, or they look NEW next time");
        assertTrue(index.contains(C));
    }

    @Test
    void generatesFromScratchWhenNoManifestExists(@TempDir Path dir) throws IOException {
        Path manifestPath = dir.resolve("sub/expected-failures.tsv");
        ObservedRun observed = ObservedRun.builder().record(A, AssertionOutcome.FAIL, "first run").build();

        ExpectedOutcomeManifest written = ManifestRegenerator.regenerateInPlace(manifestPath, null, observed);

        assertEquals(1, written.size());
        assertTrue(Files.exists(manifestPath));
        assertEquals("first run", ExpectedOutcomeManifest.load(manifestPath).reason(A));
    }

    @Test
    void refusesToOverwriteAMalformedManifest(@TempDir Path dir) throws IOException {
        Path manifestPath = dir.resolve("expected-failures.tsv");
        Files.write(manifestPath, "this is not a manifest\n".getBytes(UTF_8));
        ObservedRun observed = ObservedRun.builder().record(A, AssertionOutcome.FAIL, "x").build();

        assertThrows(
                ManifestFormatException.class,
                () -> ManifestRegenerator.regenerateInPlace(manifestPath, null, observed));
        assertEquals("this is not a manifest\n", new String(Files.readAllBytes(manifestPath), UTF_8));
    }

    @Test
    void describesWhatChanged() {
        ExpectedOutcomeManifest previous = manifest(
                ManifestEntry.of(A, AssertionOutcome.FAIL, "fixed soon"),
                ManifestEntry.of(B, AssertionOutcome.FAIL, "still broken"));
        ObservedRun observed = ObservedRun.builder()
                .record(A, AssertionOutcome.PASS)
                .record(B, AssertionOutcome.FAIL, "still broken")
                .record(C, AssertionOutcome.FAIL, "newly failing")
                .build();
        ExpectedOutcomeManifest regenerated = ManifestRegenerator.regenerate(previous, observed);
        assertEquals("manifest: 2 -> 2 entries (1 removed, 1 added)", ManifestRegenerator.describeChange(previous, regenerated));
    }

    @Test
    void regenerationIsRequestedOnlyByTheDocumentedProperty() {
        assertEquals("gie.regenerate", ManifestRegenerator.REGENERATE_PROPERTY);
        String saved = System.getProperty(ManifestRegenerator.REGENERATE_PROPERTY);
        try {
            System.clearProperty(ManifestRegenerator.REGENERATE_PROPERTY);
            assertFalse(
                    ManifestRegenerator.isRegenerationRequested(),
                    "absent property must not rewrite the baseline: mvn -Pconformance verify is a gate");
            System.setProperty(ManifestRegenerator.REGENERATE_PROPERTY, "false");
            assertFalse(ManifestRegenerator.isRegenerationRequested());
            System.setProperty(ManifestRegenerator.REGENERATE_PROPERTY, "true");
            assertTrue(ManifestRegenerator.isRegenerationRequested());
        } finally {
            if (saved == null) {
                System.clearProperty(ManifestRegenerator.REGENERATE_PROPERTY);
            } else {
                System.setProperty(ManifestRegenerator.REGENERATE_PROPERTY, saved);
            }
        }
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ManifestRegenerator.regenerate(null, ObservedRun.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> ManifestRegenerator.regenerate(ExpectedOutcomeManifest.empty(), null));
    }
}

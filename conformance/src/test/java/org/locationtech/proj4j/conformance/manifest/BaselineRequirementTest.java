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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The guard, exercised in both directions.
 *
 * <p>{@link #anAbsentBaselineIsIndistinguishableFromAnAllNewCorpusInTheDiff()} is the reason the guard
 * exists and is written first: it demonstrates, through {@link ConformanceDiff} itself, that a run with
 * no baseline produces a diff that does not fail the build however many assertions failed. Everything
 * else here is the check that catches that state before the diff is reached.
 */
class BaselineRequirementTest {

    private static final String SEARCHED = "/tmp/example/src/test/resources";

    // ------------------------------------------------------- what an absent baseline does to the diff

    @Test
    void anAbsentBaselineIsIndistinguishableFromAnAllNewCorpusInTheDiff() {
        // Six assertions, five of them failing. With no manifest and no index this is a green build.
        ObservedRun.Builder builder = ObservedRun.builder();
        List<AssertionKey> keys = new ArrayList<AssertionKey>();
        for (int i = 0; i < 6; i++) {
            AssertionKey key = AssertionKey.of("gie/builtins.gie", 0, i, String.format("%08x", i + 1));
            keys.add(key);
            builder.record(key, i == 0 ? AssertionOutcome.PASS : AssertionOutcome.FAIL, "3400 km off");
        }
        ObservedRun run = builder.build();

        DiffResult withoutBaseline =
                ConformanceDiff.compare(ExpectedOutcomeManifest.empty(), run, CorpusIndex.empty());
        assertEquals(6, withoutBaseline.count(DiffClassification.NEW));
        assertEquals(0, withoutBaseline.count(DiffClassification.REGRESSED));
        assertFalse(
                withoutBaseline.shouldFailBuild(),
                "this is the defect: five failing assertions and the gate is green");

        // The same run against a baseline that knows those keys is five regressions. Nothing about the
        // run changed; only whether there was anything to compare it to.
        DiffResult withBaseline =
                ConformanceDiff.compare(ExpectedOutcomeManifest.empty(), run, CorpusIndex.of(keys));
        assertEquals(5, withBaseline.count(DiffClassification.REGRESSED));
        assertTrue(withBaseline.shouldFailBuild());

        // And the guard fires on precisely the first case and not the second.
        assertFalse(BaselineRequirement.diagnosePresence(false, false, SEARCHED).isEmpty());
        assertEquals("", BaselineRequirement.diagnosePresence(true, true, SEARCHED));
    }

    // ------------------------------------------------------------------------------------ presence

    @Test
    void bothFilesPresentIsTheOnlyCleanState() {
        assertEquals("", BaselineRequirement.diagnosePresence(true, true, SEARCHED));
        BaselineRequirement.requirePresence(true, true, SEARCHED);
    }

    @Test
    void bothFilesAbsentIsAHardFailureNamingBothFiles() {
        BaselineRequirement.MissingBaselineException thrown = assertThrows(
                BaselineRequirement.MissingBaselineException.class,
                () -> BaselineRequirement.requirePresence(false, false, SEARCHED));
        String message = thrown.getMessage();
        assertTrue(message.contains(BaselineRequirement.MANIFEST_FILE), message);
        assertTrue(message.contains(BaselineRequirement.CORPUS_INDEX_FILE), message);
        assertTrue(message.contains(SEARCHED), message);
        assertTrue(message.contains(BaselineRequirement.REGENERATE_COMMAND), message);
    }

    @Test
    void theDiagnosticSaysNothingRegressedBecauseNothingDid() {
        // A missing baseline reported as "5830 REGRESSED" sends the reader hunting for a regression
        // that did not happen. The message must name the real cause.
        String message = BaselineRequirement.diagnosePresence(false, false, SEARCHED);
        assertTrue(message.contains("BASELINE MISSING"), message);
        assertTrue(message.contains("NOTHING HAS REGRESSED"), message);
        assertFalse(message.contains("REGRESSED assertions"), message);
        assertTrue(message.contains("SUCCESS no matter how many assertions failed"), message);
    }

    @Test
    void oneFileWithoutTheOtherIsAlsoAHardFailureAndSaysWhichIsMissing() {
        String indexMissing = BaselineRequirement.diagnosePresence(true, false, SEARCHED);
        assertTrue(indexMissing.contains("BASELINE INCOMPLETE"), indexMissing);
        assertTrue(
                indexMissing.contains(BaselineRequirement.CORPUS_INDEX_FILE + " was not found"),
                indexMissing);
        assertTrue(indexMissing.contains("travel together"), indexMissing);

        String manifestMissing = BaselineRequirement.diagnosePresence(false, true, SEARCHED);
        assertTrue(
                manifestMissing.contains(BaselineRequirement.MANIFEST_FILE + " was not found"),
                manifestMissing);

        assertThrows(
                BaselineRequirement.MissingBaselineException.class,
                () -> BaselineRequirement.requirePresence(true, false, SEARCHED));
        assertThrows(
                BaselineRequirement.MissingBaselineException.class,
                () -> BaselineRequirement.requirePresence(false, true, SEARCHED));
    }

    @Test
    void aNullSearchPathIsToleratedAndOmitted() {
        String message = BaselineRequirement.diagnosePresence(false, false, null);
        assertFalse(message.contains("Searched:"), message);
        assertTrue(message.contains(BaselineRequirement.REGENERATE_COMMAND), message);
    }

    // ------------------------------------------------------------------------------------ coverage

    @Test
    void anIndexThatCoversNothingIsTreatedAsNoBaselineAtAll() {
        BaselineRequirement.MissingBaselineException thrown = assertThrows(
                BaselineRequirement.MissingBaselineException.class,
                () -> BaselineRequirement.requireCoverage(0, 7923, SEARCHED));
        String message = thrown.getMessage();
        assertTrue(message.contains("BASELINE EMPTY"), message);
        assertTrue(message.contains("7923"), message);
        assertTrue(message.contains("NOTHING HAS REGRESSED"), message);
    }

    @Test
    void anIndexWithKeysPasses() {
        assertEquals("", BaselineRequirement.diagnoseCoverage(7923, 7923, SEARCHED));
        BaselineRequirement.requireCoverage(1, 7923, SEARCHED);
    }

    @Test
    void anEmptyRunIsNotACoverageFailure() {
        // Nothing observed means nothing to gate; the empty-index complaint would be noise, and the
        // absent-file check has already run by this point.
        assertEquals("", BaselineRequirement.diagnoseCoverage(0, 0, SEARCHED));
    }

    // ---------------------------------------------------------------- the guard's own file names

    @Test
    void theFileNamesAreTheOnesTheRegeneratorWrites() {
        assertEquals(
                Arrays.asList("gie-expected-failures.tsv", "gie-corpus-index.tsv"),
                Arrays.asList(BaselineRequirement.MANIFEST_FILE, BaselineRequirement.CORPUS_INDEX_FILE));
        assertTrue(
                BaselineRequirement.REGENERATE_COMMAND.contains(ManifestRegenerator.REGENERATE_PROPERTY),
                BaselineRequirement.REGENERATE_COMMAND);
    }
}

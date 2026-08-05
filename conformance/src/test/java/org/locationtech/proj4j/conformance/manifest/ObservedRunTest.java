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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class ObservedRunTest {

    private static final AssertionKey A = AssertionKey.of("gie/builtins.gie", 0, 0, "00000001");
    private static final AssertionKey B = AssertionKey.of("gie/builtins.gie", 0, 1, "00000002");
    private static final AssertionKey C = AssertionKey.of("gigs/5110.gie.failing", 2, 0, "00000003");

    @Test
    void tabulatesTheThreeOutcomesIndependently() {
        ObservedRun run = ObservedRun.builder()
                .record(A, AssertionOutcome.PASS)
                .record(B, AssertionOutcome.FAIL)
                .record(C, AssertionOutcome.SKIP)
                .build();
        assertEquals(1, run.count(AssertionOutcome.PASS));
        assertEquals(1, run.count(AssertionOutcome.FAIL));
        assertEquals(1, run.count(AssertionOutcome.SKIP));
        assertEquals(3, run.total());
    }

    @Test
    void aSkipIsNeverAPass() {
        ObservedRun run = ObservedRun.builder().record(C, AssertionOutcome.SKIP).build();
        assertEquals(0, run.count(AssertionOutcome.PASS));
        assertEquals(1, run.count(AssertionOutcome.SKIP));
        assertFalse(AssertionOutcome.SKIP.isPass());
        assertTrue(AssertionOutcome.SKIP.isNotPass());
        assertTrue(AssertionOutcome.PASS.isPass());
        assertTrue(AssertionOutcome.FAIL.isNotPass());
    }

    @Test
    void anUnobservedKeyHasNoOutcomeRatherThanAPass() {
        ObservedRun run = ObservedRun.builder().record(A, AssertionOutcome.PASS).build();
        assertNull(run.outcome(B));
        assertNull(run.get(B));
        assertFalse(run.contains(B));
    }

    @Test
    void rejectsTheSameAssertionTwice() {
        ObservedRun.Builder builder = ObservedRun.builder().record(A, AssertionOutcome.PASS);
        assertThrows(IllegalArgumentException.class, () -> builder.record(A, AssertionOutcome.FAIL));
    }

    @Test
    void groupsByFile() {
        ObservedRun run = ObservedRun.builder()
                .record(A, AssertionOutcome.PASS)
                .record(B, AssertionOutcome.FAIL)
                .record(C, AssertionOutcome.SKIP)
                .build();
        assertEquals(
                Arrays.asList("gie/builtins.gie", "gigs/5110.gie.failing"),
                new ArrayList<String>(run.filePaths()));
        assertEquals(2, run.assertionsIn("gie/builtins.gie").size());
        assertEquals(1, run.countIn("gie/builtins.gie", AssertionOutcome.PASS));
        assertEquals(1, run.countIn("gie/builtins.gie", AssertionOutcome.FAIL));
        assertEquals(0, run.countIn("gie/builtins.gie", AssertionOutcome.SKIP));
        assertEquals(1, run.countIn("gigs/5110.gie.failing", AssertionOutcome.SKIP));
    }

    @Test
    void ordersAssertionsCanonically() {
        ObservedRun run = ObservedRun.builder()
                .record(C, AssertionOutcome.SKIP)
                .record(B, AssertionOutcome.FAIL)
                .record(A, AssertionOutcome.PASS)
                .build();
        assertEquals(Arrays.asList(A, B, C), new ArrayList<AssertionKey>(run.keys()));
        assertEquals(A, run.assertions().get(0).key());
    }

    @Test
    void flattensMultiLineDetailSoItIsAlwaysTsvSafe() {
        ObservedAssertion observed =
                ObservedAssertion.of(A, AssertionOutcome.FAIL, "expected 1 2\n\tgot   3 4  (deviation 12.4 m)");
        assertEquals("expected 1 2 got 3 4 (deviation 12.4 m)", observed.detail());
        assertEquals("", ObservedAssertion.of(A, AssertionOutcome.PASS, null).detail());
    }

    @Test
    void rejectsNullKeyOrOutcome() {
        assertThrows(IllegalArgumentException.class, () -> ObservedAssertion.of(null, AssertionOutcome.PASS));
        assertThrows(IllegalArgumentException.class, () -> ObservedAssertion.of(A, null));
    }

    @Test
    void outcomeNamesParseStrictly() {
        assertEquals(AssertionOutcome.PASS, AssertionOutcome.parse("PASS"));
        assertEquals(AssertionOutcome.FAIL, AssertionOutcome.parse("FAIL"));
        assertEquals(AssertionOutcome.SKIP, AssertionOutcome.parse("SKIP"));
        assertEquals(
                AssertionOutcome.VACUOUS_EXPECTED_FAILURE,
                AssertionOutcome.parse("VACUOUS_EXPECTED_FAILURE"));
        assertThrows(IllegalArgumentException.class, () -> AssertionOutcome.parse("pass"));
        assertThrows(IllegalArgumentException.class, () -> AssertionOutcome.parse("IGNORED"));
        assertThrows(IllegalArgumentException.class, () -> AssertionOutcome.parse("VACUOUS"));
        assertThrows(IllegalArgumentException.class, () -> AssertionOutcome.parse(null));
    }

    @Test
    void aVacuousExpectedFailureIsNeitherAPassNorAMeasurement() {
        AssertionOutcome vacuous = AssertionOutcome.VACUOUS_EXPECTED_FAILURE;
        assertFalse(vacuous.isPass(), "gie would score this a pass; we do not");
        assertTrue(vacuous.isNotPass());
        assertFalse(vacuous.isMeasured(), "the whole point: no judgement was made");
        assertTrue(vacuous.isVacuous());

        // Nothing else is vacuous, and the two measured outcomes are exactly PASS and FAIL.
        assertFalse(AssertionOutcome.SKIP.isVacuous(), "a skip and a vacuity are different problems");
        assertFalse(AssertionOutcome.SKIP.isMeasured());
        assertTrue(AssertionOutcome.PASS.isMeasured());
        assertTrue(AssertionOutcome.FAIL.isMeasured());
    }

    @Test
    void aVacuousExpectedFailureIsTalliedInItsOwnBucket() {
        ObservedRun run = ObservedRun.builder()
                .record(A, AssertionOutcome.PASS)
                .record(B, AssertionOutcome.VACUOUS_EXPECTED_FAILURE, "VACUOUS: adams_hemi not implemented")
                .record(C, AssertionOutcome.VACUOUS_EXPECTED_FAILURE)
                .build();

        assertEquals(1, run.count(AssertionOutcome.PASS));
        assertEquals(0, run.count(AssertionOutcome.FAIL));
        assertEquals(0, run.count(AssertionOutcome.SKIP));
        assertEquals(2, run.count(AssertionOutcome.VACUOUS_EXPECTED_FAILURE));
        assertEquals(3, run.total());
    }

    @Test
    void aVacuousExpectedFailureIsRecordedInTheManifestUnderItsOwnName() {
        // Requirement: distinct from PASS, so that implementing the missing operator reads as progress
        // rather than as several hundred regressions. Also has to survive a round trip through the TSV.
        ManifestEntry entry =
                ManifestEntry.of(B, AssertionOutcome.VACUOUS_EXPECTED_FAILURE, "adams_hemi is absent");
        assertEquals(B + "\tVACUOUS_EXPECTED_FAILURE\tadams_hemi is absent", entry.toTsvLine());
        assertEquals(AssertionOutcome.VACUOUS_EXPECTED_FAILURE, entry.expectedOutcome());
    }
}

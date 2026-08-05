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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Classifies an {@link ObservedRun} against an {@link ExpectedOutcomeManifest}.
 *
 * <p>The classification table, with {@code -} meaning "not present":
 *
 * <pre>
 *   expected \ observed |  PASS             FAIL             SKIP             VACUOUS          -
 *   --------------------+-----------------------------------------------------------------------------
 *   PASS  (absent)      |  UNCHANGED        REGRESSED        REGRESSED        REGRESSED        DISAPPEARED
 *   FAIL                |  UNEXPECTED_PASS  STILL_FAILING    STILL_FAILING    STILL_FAILING    DISAPPEARED
 *   SKIP                |  UNEXPECTED_PASS  STILL_FAILING    STILL_FAILING    STILL_FAILING    DISAPPEARED
 *   VACUOUS             |  UNEXPECTED_PASS  STILL_FAILING    STILL_FAILING    STILL_FAILING    DISAPPEARED
 *   not in corpus index |  NEW              NEW              NEW              NEW              (n/a)
 * </pre>
 *
 * <p>{@code UNCHANGED} therefore means "passed, as it should"; {@code STILL_FAILING} is the count of
 * remaining known work, and is the number that must fall each stage.
 *
 * <p>Two cells deserve a note. Expected {@code PASS} + observed {@code SKIP} is a
 * {@code REGRESSED}, not a benign skip: an assertion that used to be evaluated and now declines to be
 * is a loss of coverage, and if skips were tolerated here a missing grid could quietly retire a
 * hundred passing assertions. Expected {@code FAIL} + observed {@code SKIP} (and the reverse) is
 * {@code STILL_FAILING}, since neither is a pass and neither is a loss relative to the baseline; those
 * keys are separately visible via {@link DiffResult#outcomeChangedKeys()}.
 *
 * <h2>The {@code VACUOUS} row is the one to get right</h2>
 *
 * <p>{@link AssertionOutcome#VACUOUS_EXPECTED_FAILURE} means the assertion was never measured. When an
 * implementation lands — {@code adams_hemi} being the worked example, 388 rows of it — every one of
 * those rows flips to a genuine pass or a genuine failure. <strong>Neither flip may be
 * {@code REGRESSED}</strong>, because nothing has got worse: something has started being measured. So
 * the row reads {@code UNEXPECTED_PASS} for a genuine pass (bank the win, regenerate) and
 * {@code STILL_FAILING} for a genuine failure (known work, and the honest denominator has just grown by
 * one). That is progress being counted for the first time, and the diff says so.
 *
 * <p>The reverse direction, expected {@code PASS} + observed {@code VACUOUS}, <em>is</em>
 * {@code REGRESSED}, and deliberately: an assertion that used to demonstrate something and now
 * demonstrates nothing has lost coverage exactly as a new skip would have.
 *
 * <p>The "corpus the manifest was built from" is supplied as a {@link CorpusIndex}. Without it,
 * {@code NEW} cannot be distinguished from {@code UNCHANGED} — the manifest holds only non-passes, so
 * a passing key's absence from it is not evidence of anything.
 */
public final class ConformanceDiff {

    private ConformanceDiff() {}

    /**
     * @param expected the manifest
     * @param observed the run
     * @param baseline the corpus the manifest was generated against
     * @return the classified diff
     */
    public static DiffResult compare(ExpectedOutcomeManifest expected, ObservedRun observed, CorpusIndex baseline) {
        return compare(expected, observed, baseline.keys());
    }

    /**
     * Compares without a corpus index, treating the union of the manifest's keys and the run's keys as
     * the baseline.
     *
     * <p>This is the convenience form for unit tests and ad-hoc runs. It can never report
     * {@link DiffClassification#NEW} or {@link DiffClassification#DISAPPEARED} for a passing assertion,
     * because there is nothing to have disappeared <em>from</em>. Real gate runs should pass the index.
     *
     * @param expected the manifest
     * @param observed the run
     * @return the classified diff
     */
    public static DiffResult compare(ExpectedOutcomeManifest expected, ObservedRun observed) {
        SortedSet<AssertionKey> baseline = new TreeSet<AssertionKey>();
        baseline.addAll(expected.keys());
        baseline.addAll(observed.keys());
        return compare(expected, observed, baseline);
    }

    /**
     * @param expected the manifest
     * @param observed the run
     * @param baselineKeys every key that existed when the manifest was generated
     * @return the classified diff
     */
    public static DiffResult compare(
            ExpectedOutcomeManifest expected, ObservedRun observed, Collection<AssertionKey> baselineKeys) {
        if (expected == null || observed == null || baselineKeys == null) {
            throw new IllegalArgumentException("manifest, run and baseline are all required");
        }

        SortedSet<AssertionKey> known = new TreeSet<AssertionKey>(baselineKeys);
        // A manifest entry is itself evidence that the key was known at generation time, even if the
        // index is stale or absent; otherwise a manifest-only key would be misreported as NEW.
        known.addAll(expected.keys());

        SortedSet<AssertionKey> everyKey = new TreeSet<AssertionKey>(known);
        everyKey.addAll(observed.keys());

        List<DiffEntry> entries = new ArrayList<DiffEntry>(everyKey.size());
        for (AssertionKey key : everyKey) {
            AssertionOutcome expectedOutcome = expected.expectedOutcome(key);
            AssertionOutcome observedOutcome = observed.outcome(key);
            String reason = expected.reason(key);
            ObservedAssertion observation = observed.get(key);
            String detail = observation == null ? "" : observation.detail();

            DiffClassification classification;
            if (observedOutcome == null) {
                classification = DiffClassification.DISAPPEARED;
            } else if (!known.contains(key)) {
                classification = DiffClassification.NEW;
            } else if (expectedOutcome == AssertionOutcome.PASS) {
                classification = observedOutcome == AssertionOutcome.PASS
                        ? DiffClassification.UNCHANGED
                        : DiffClassification.REGRESSED;
            } else if (expectedOutcome == AssertionOutcome.VACUOUS_EXPECTED_FAILURE) {
                // Spelled out rather than left to the fall-through below, because the property being
                // asserted is that this can never be REGRESSED: an assertion that was measuring
                // nothing cannot have got worse. See the class comment.
                classification = observedOutcome == AssertionOutcome.PASS
                        ? DiffClassification.UNEXPECTED_PASS
                        : DiffClassification.STILL_FAILING;
            } else if (observedOutcome == AssertionOutcome.PASS) {
                classification = DiffClassification.UNEXPECTED_PASS;
            } else {
                classification = DiffClassification.STILL_FAILING;
            }

            entries.add(DiffEntry.of(key, classification, expectedOutcome, observedOutcome, reason, detail));
        }

        return new DiffResult(
                entries,
                observed.count(AssertionOutcome.PASS),
                observed.count(AssertionOutcome.FAIL),
                observed.count(AssertionOutcome.SKIP),
                observed.count(AssertionOutcome.VACUOUS_EXPECTED_FAILURE));
    }
}

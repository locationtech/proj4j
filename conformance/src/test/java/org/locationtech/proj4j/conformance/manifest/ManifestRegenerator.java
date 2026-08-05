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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites the expected-outcome manifest from an observed run.
 *
 * <h2>Invocation</h2>
 *
 * <p>The gate and the regenerator are the same run; only the last step differs. Intended usage:
 *
 * <pre>
 *   mvn -Pconformance verify                          # gate: fails on REGRESSED / UNEXPECTED_PASS / DISAPPEARED
 *   mvn -Pconformance verify -Dgie.regenerate=true    # accept the current state as the new baseline
 * </pre>
 *
 * <p>{@link #isRegenerationRequested()} reads the {@value #REGENERATE_PROPERTY} system property, which
 * the surefire/failsafe execution is expected to forward. <em>No {@code pom.xml} is touched by this
 * class</em>; wiring the profile is a separate concern.
 *
 * <p>Regeneration writes two files: the manifest itself and the sibling {@link CorpusIndex}, which
 * records every key that existed at generation time. Both must be regenerated together, or the next
 * diff will report phantom {@code NEW}/{@code DISAPPEARED} entries.
 *
 * <h2>Reason preservation</h2>
 *
 * <p>The single most valuable content in the manifest is the hand-written {@code reason} column — "no
 * {@code \} continuation, {@code +step} swallowed as a comment: syntax artefact, not maths" is the
 * difference between a five-minute triage and a five-hour one. Regeneration therefore
 * <strong>carries the previous reason over</strong> whenever the key's expected outcome is unchanged.
 * A reason is only replaced when the outcome itself changed (FAIL becoming SKIP, say), because the old
 * text then describes something that is no longer true.
 *
 * <p>For genuinely new entries the reason is seeded from the run's
 * {@linkplain ObservedAssertion#detail() detail} — the measured deviation or the missing grid — and
 * left empty if there is none. Empty reasons are legal and are the natural TODO list.
 *
 * <h2>Vacuous rows are banked as vacuous</h2>
 *
 * <p>Everything that is not a {@link AssertionOutcome#PASS} gets an entry, so a
 * {@link AssertionOutcome#VACUOUS_EXPECTED_FAILURE} is written to the manifest under its own name
 * rather than either omitted (which would silently claim it passes) or downgraded to {@code FAIL}
 * (which would claim it was measured). That is what makes the later transition legible: when the
 * missing implementation lands, the diff shows those keys as {@code UNEXPECTED_PASS} or
 * {@code STILL_FAILING}, never as {@code REGRESSED}.
 */
public final class ManifestRegenerator {

    /** System property that switches a gate run into a regeneration run. */
    public static final String REGENERATE_PROPERTY = "gie.regenerate";

    private ManifestRegenerator() {}

    /** @return {@code true} if {@code -Dgie.regenerate=true} was passed. */
    public static boolean isRegenerationRequested() {
        return Boolean.parseBoolean(System.getProperty(REGENERATE_PROPERTY, "false"));
    }

    /**
     * Builds the manifest that describes {@code observed}, preserving reasons from {@code previous}.
     *
     * <p>Every non-passing assertion in the run gets an entry; every passing one gets none, so entries
     * for assertions that have started passing simply vanish — that deletion <em>is</em> the record of
     * progress, and it is what makes the regeneration diff worth reading. Entries in {@code previous}
     * whose key is not in the run are dropped too: a manifest entry that matches nothing is dead
     * weight. (The gate reports those as {@link DiffClassification#DISAPPEARED} before anyone gets to
     * regenerate, so the drop is never silent.)
     *
     * @param previous the manifest being replaced; use {@link ExpectedOutcomeManifest#empty()} for a
     *     first generation
     * @param observed the run
     * @return the new manifest
     */
    public static ExpectedOutcomeManifest regenerate(ExpectedOutcomeManifest previous, ObservedRun observed) {
        if (previous == null || observed == null) {
            throw new IllegalArgumentException("previous manifest and observed run are both required");
        }
        List<ManifestEntry> entries = new ArrayList<ManifestEntry>();
        for (ObservedAssertion assertion : observed.assertions()) {
            if (assertion.outcome() == AssertionOutcome.PASS) {
                continue;
            }
            ManifestEntry existing = previous.entry(assertion.key());
            String reason;
            if (existing != null && existing.expectedOutcome() == assertion.outcome()) {
                reason = existing.reason();
            } else {
                reason = assertion.detail();
            }
            entries.add(ManifestEntry.of(assertion.key(), assertion.outcome(), reason));
        }
        return ExpectedOutcomeManifest.of(entries);
    }

    /**
     * Loads the manifest at {@code manifestPath} (or starts empty if it does not exist), regenerates it
     * from {@code observed}, and writes both the manifest and the corpus index.
     *
     * @param manifestPath the manifest file
     * @param corpusIndexPath the corpus index file; {@code null} to skip writing it
     * @param observed the run
     * @return the manifest that was written
     * @throws IOException if reading or writing fails
     * @throws ManifestFormatException if the existing manifest is malformed — a broken baseline is
     *     never silently overwritten, because the reasons in it are unrecoverable once lost
     */
    public static ExpectedOutcomeManifest regenerateInPlace(Path manifestPath, Path corpusIndexPath, ObservedRun observed)
            throws IOException {
        ExpectedOutcomeManifest previous =
                Files.exists(manifestPath) ? ExpectedOutcomeManifest.load(manifestPath) : ExpectedOutcomeManifest.empty();
        ExpectedOutcomeManifest regenerated = regenerate(previous, observed);
        regenerated.store(manifestPath);
        if (corpusIndexPath != null) {
            CorpusIndex.ofRun(observed).store(corpusIndexPath);
        }
        return regenerated;
    }

    /**
     * Describes what a regeneration would change, for logging before it happens.
     *
     * @param previous the manifest being replaced
     * @param regenerated the manifest produced by {@link #regenerate}
     * @return a one-line summary, e.g. {@code "manifest: 1204 -> 1189 entries (17 removed, 2 added)"}
     */
    public static String describeChange(ExpectedOutcomeManifest previous, ExpectedOutcomeManifest regenerated) {
        int removed = 0;
        for (AssertionKey key : previous.keys()) {
            if (!regenerated.contains(key)) {
                removed++;
            }
        }
        int added = 0;
        for (AssertionKey key : regenerated.keys()) {
            if (!previous.contains(key)) {
                added++;
            }
        }
        return "manifest: " + previous.size() + " -> " + regenerated.size() + " entries (" + removed + " removed, "
                + added + " added)";
    }
}

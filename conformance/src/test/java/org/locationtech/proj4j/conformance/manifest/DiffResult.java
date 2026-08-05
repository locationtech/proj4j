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
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The classified comparison of a run against the manifest: counts per {@link DiffClassification}, the
 * offending keys, and the build verdict.
 *
 * <p>Immutable; produced by {@link ConformanceDiff}.
 */
public final class DiffResult {

    private final List<DiffEntry> entries;
    private final Map<DiffClassification, List<DiffEntry>> byClassification;
    private final int passing;
    private final int failing;
    private final int skipped;
    private final int vacuous;

    DiffResult(List<DiffEntry> entries, int passing, int failing, int skipped, int vacuous) {
        List<DiffEntry> sorted = new ArrayList<DiffEntry>(entries);
        Collections.sort(sorted);
        this.entries = Collections.unmodifiableList(sorted);
        Map<DiffClassification, List<DiffEntry>> grouped =
                new EnumMap<DiffClassification, List<DiffEntry>>(DiffClassification.class);
        for (DiffClassification classification : DiffClassification.values()) {
            grouped.put(classification, new ArrayList<DiffEntry>());
        }
        for (DiffEntry entry : this.entries) {
            grouped.get(entry.classification()).add(entry);
        }
        for (DiffClassification classification : DiffClassification.values()) {
            grouped.put(classification, Collections.unmodifiableList(grouped.get(classification)));
        }
        this.byClassification = Collections.unmodifiableMap(grouped);
        this.passing = passing;
        this.failing = failing;
        this.skipped = skipped;
        this.vacuous = vacuous;
    }

    /** @return every entry, in canonical key order. */
    public List<DiffEntry> entries() {
        return entries;
    }

    /**
     * @param classification the class of interest
     * @return the entries in that class, in canonical key order
     */
    public List<DiffEntry> entries(DiffClassification classification) {
        return byClassification.get(classification);
    }

    /**
     * @param classification the class of interest
     * @return the keys in that class, in canonical order
     */
    public SortedSet<AssertionKey> keys(DiffClassification classification) {
        SortedSet<AssertionKey> selected = new TreeSet<AssertionKey>();
        for (DiffEntry entry : byClassification.get(classification)) {
            selected.add(entry.key());
        }
        return Collections.unmodifiableSortedSet(selected);
    }

    /**
     * @param classification the class of interest
     * @return how many assertions fell into it
     */
    public int count(DiffClassification classification) {
        return byClassification.get(classification).size();
    }

    /** @return counts for every classification, including zeroes, in declaration order. */
    public Map<DiffClassification, Integer> counts() {
        Map<DiffClassification, Integer> result =
                new EnumMap<DiffClassification, Integer>(DiffClassification.class);
        for (DiffClassification classification : DiffClassification.values()) {
            result.put(classification, Integer.valueOf(count(classification)));
        }
        return Collections.unmodifiableMap(result);
    }

    /** @return the number of assertions classified. */
    public int total() {
        return entries.size();
    }

    /** @return observed passes. */
    public int passing() {
        return passing;
    }

    /** @return observed failures. */
    public int failing() {
        return failing;
    }

    /** @return observed skips. Reported separately from passes, always. */
    public int skipped() {
        return skipped;
    }

    /**
     * @return observed {@link AssertionOutcome#VACUOUS_EXPECTED_FAILURE}s. Reported separately from
     *     both passes and failures, always, and excluded from the headline's denominator
     */
    public int vacuous() {
        return vacuous;
    }

    /**
     * Keys that are {@link DiffClassification#STILL_FAILING} but whose <em>kind</em> of non-pass
     * changed — expected {@code FAIL}, observed {@code SKIP}, or the reverse.
     *
     * <p>Informational, not build-failing. A FAIL becoming a SKIP is almost always a grid or resource
     * availability change rather than progress or regression, and treating it as either would be
     * misleading. It is surfaced so that a run in a differently-provisioned environment is visible
     * rather than invisible.
     *
     * @return the keys, in canonical order
     */
    public SortedSet<AssertionKey> outcomeChangedKeys() {
        SortedSet<AssertionKey> changed = new TreeSet<AssertionKey>();
        for (DiffEntry entry : byClassification.get(DiffClassification.STILL_FAILING)) {
            if (entry.observed() != null && entry.observed() != entry.expected()) {
                changed.add(entry.key());
            }
        }
        return Collections.unmodifiableSortedSet(changed);
    }

    /**
     * The build verdict: {@code true} if there is any {@link DiffClassification#REGRESSED},
     * {@link DiffClassification#UNEXPECTED_PASS} or {@link DiffClassification#DISAPPEARED} assertion.
     *
     * <p><strong>Why {@code REGRESSED} fails</strong> is obvious: something that worked no longer
     * works.
     *
     * <p><strong>Why {@code UNEXPECTED_PASS} fails</strong> is the part that gets argued about, so it
     * is worth stating plainly. An assertion that starts passing is good news, and the build stops
     * anyway — because the manifest is not a list of excuses, it is a claim about the state of the
     * world, and the claim has just become false. If a landed fix does not force the manifest to be
     * updated then:
     * <ul>
     *   <li>the baseline silently overstates the remaining work, so the headline metric
     *       and the burn-down both lie, and the next stage is planned against fiction;</li>
     *   <li>the entry keeps excusing that assertion forever, so if the fix is later reverted or eroded
     *       by an unrelated change, the re-failure is <em>expected</em> and nobody is told — a
     *       regression that can never be detected again;</li>
     *   <li>nobody notices that the fix landed, which is exactly the moment the reason text should be
     *       deleted and the win recorded.</li>
     * </ul>
     * The fix is one command ({@code -Dgie.regenerate=true}) and the resulting diff — lines removed
     * from the manifest — is the most useful review artefact this whole apparatus produces.
     *
     * <p><strong>Why {@code DISAPPEARED} fails:</strong> an assertion that stops running has stopped
     * being checked. Whether that is a lexer regression, a re-vendor, or a stale manifest entry, it
     * needs a human. {@link DiffClassification#NEW} does not fail, because a new assertion is already
     * held to the "must pass" default.
     *
     * @return {@code true} if the build should fail
     */
    public boolean shouldFailBuild() {
        for (DiffClassification classification : DiffClassification.values()) {
            if (classification.failsBuild() && count(classification) > 0) {
                return true;
            }
        }
        return false;
    }

    /** @return a one-line summary of why the build failed, or {@code ""} if it should not. */
    public String failureSummary() {
        if (!shouldFailBuild()) {
            return "";
        }
        StringBuilder out = new StringBuilder("conformance gate failed:");
        boolean first = true;
        for (DiffClassification classification : DiffClassification.values()) {
            if (classification.failsBuild() && count(classification) > 0) {
                out.append(first ? " " : ", ").append(count(classification)).append(' ').append(classification);
                first = false;
            }
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return "DiffResult" + counts();
    }
}

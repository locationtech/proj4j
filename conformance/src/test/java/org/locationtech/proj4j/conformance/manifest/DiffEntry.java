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

/**
 * One assertion's place in the diff: its key, how it was classified, what was expected, what happened,
 * and the manifest's reason text.
 *
 * <p>Self-contained on purpose — the report renderers take a {@link DiffResult} and nothing else, so
 * they stay pure functions of their argument.
 *
 * <p>Immutable.
 */
public final class DiffEntry implements Comparable<DiffEntry> {

    private final AssertionKey key;
    private final DiffClassification classification;
    private final AssertionOutcome expected;
    private final AssertionOutcome observed;
    private final String reason;
    private final String detail;

    private DiffEntry(
            AssertionKey key,
            DiffClassification classification,
            AssertionOutcome expected,
            AssertionOutcome observed,
            String reason,
            String detail) {
        this.key = key;
        this.classification = classification;
        this.expected = expected;
        this.observed = observed;
        this.reason = reason;
        this.detail = detail;
    }

    /**
     * @param key the assertion
     * @param classification how it relates to the manifest
     * @param expected the expectation ({@link AssertionOutcome#PASS} when the key is absent from the
     *     manifest)
     * @param observed the observed outcome, or {@code null} when the assertion did not run
     * @param reason the manifest's reason text, {@code ""} when there is none
     * @param detail the run's one-line detail, {@code ""} when there is none
     * @return the entry
     */
    public static DiffEntry of(
            AssertionKey key,
            DiffClassification classification,
            AssertionOutcome expected,
            AssertionOutcome observed,
            String reason,
            String detail) {
        if (key == null || classification == null || expected == null) {
            throw new IllegalArgumentException("key, classification and expected outcome are required");
        }
        return new DiffEntry(
                key, classification, expected, observed, reason == null ? "" : reason, detail == null ? "" : detail);
    }

    /** @return the assertion identity. */
    public AssertionKey key() {
        return key;
    }

    /** @return the classification. */
    public DiffClassification classification() {
        return classification;
    }

    /** @return the expected outcome; {@link AssertionOutcome#PASS} when the manifest is silent. */
    public AssertionOutcome expected() {
        return expected;
    }

    /**
     * @return the observed outcome, or {@code null} for {@link DiffClassification#DISAPPEARED}. Never
     *     conflate {@code null} with {@link AssertionOutcome#SKIP}: a skip was evaluated and declined,
     *     a {@code null} was never reached at all.
     */
    public AssertionOutcome observed() {
        return observed;
    }

    /** @return the manifest's reason text; possibly empty, never {@code null}. */
    public String reason() {
        return reason;
    }

    /** @return the run's one-line detail; possibly empty, never {@code null}. */
    public String detail() {
        return detail;
    }

    /** @return {@code true} when this entry alone is enough to fail the build. */
    public boolean failsBuild() {
        return classification.failsBuild();
    }

    @Override
    public int compareTo(DiffEntry other) {
        return key.compareTo(other.key);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DiffEntry)) {
            return false;
        }
        DiffEntry other = (DiffEntry) o;
        return key.equals(other.key)
                && classification == other.classification
                && expected == other.expected
                && observed == other.observed
                && reason.equals(other.reason)
                && detail.equals(other.detail);
    }

    @Override
    public int hashCode() {
        int result = key.hashCode();
        result = 31 * result + classification.hashCode();
        result = 31 * result + expected.hashCode();
        result = 31 * result + (observed == null ? 0 : observed.hashCode());
        result = 31 * result + reason.hashCode();
        return 31 * result + detail.hashCode();
    }

    @Override
    public String toString() {
        return classification + " " + key + " expected=" + expected + " observed=" + (observed == null ? "-" : observed);
    }
}

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
 * One line of the expected-outcome manifest: an assertion that is <em>not</em> expected to pass, the
 * outcome expected of it, and a human explanation.
 *
 * <p>The expected outcome may never be {@link AssertionOutcome#PASS} — see
 * {@link ExpectedOutcomeManifest} for why the manifest records only non-passes.
 *
 * <p>The reason is free text, but may not contain a tab or a newline, because the manifest is
 * line-oriented TSV. It is the field that survives regeneration
 * ({@link ManifestRegenerator}), so it is worth writing properly: "syntax artefact, no {@code \}
 * continuation, {@code +step} swallowed as a comment" is useful; "fails" is not.
 *
 * <p>Immutable.
 */
public final class ManifestEntry implements Comparable<ManifestEntry> {

    private final AssertionKey key;
    private final AssertionOutcome expectedOutcome;
    private final String reason;

    private ManifestEntry(AssertionKey key, AssertionOutcome expectedOutcome, String reason) {
        this.key = key;
        this.expectedOutcome = expectedOutcome;
        this.reason = reason;
    }

    /**
     * @param key the assertion this entry excuses
     * @param expectedOutcome {@link AssertionOutcome#FAIL}, {@link AssertionOutcome#SKIP} or
     *     {@link AssertionOutcome#VACUOUS_EXPECTED_FAILURE}
     * @param reason free text, may be empty but not {@code null}, and may not contain a tab or newline
     * @return the entry
     * @throws IllegalArgumentException if the outcome is {@code PASS} or the reason is not TSV-safe
     */
    public static ManifestEntry of(AssertionKey key, AssertionOutcome expectedOutcome, String reason) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (expectedOutcome == null) {
            throw new IllegalArgumentException("expected outcome must not be null");
        }
        if (expectedOutcome == AssertionOutcome.PASS) {
            throw new IllegalArgumentException(
                    "the manifest records only non-PASS expectations; an absent key already means "
                            + "\"expected to pass\" (offending key: " + key + ")");
        }
        if (reason == null) {
            throw new IllegalArgumentException("reason must not be null (use \"\" for none)");
        }
        if (reason.indexOf('\t') >= 0 || reason.indexOf('\n') >= 0 || reason.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(
                    "reason must not contain a tab or newline (the manifest is TSV): \"" + reason + "\"");
        }
        return new ManifestEntry(key, expectedOutcome, reason);
    }

    /** @return the assertion identity. */
    public AssertionKey key() {
        return key;
    }

    /** @return the expected non-pass outcome. */
    public AssertionOutcome expectedOutcome() {
        return expectedOutcome;
    }

    /** @return the human explanation; possibly empty, never {@code null}. */
    public String reason() {
        return reason;
    }

    /** @return a copy of this entry with a different reason. */
    public ManifestEntry withReason(String newReason) {
        return of(key, expectedOutcome, newReason);
    }

    /** @return the TSV line for this entry, without a line terminator. */
    public String toTsvLine() {
        return key + "\t" + expectedOutcome.name() + "\t" + reason;
    }

    /** Ordered by {@link AssertionKey}'s total order, then outcome, then reason. */
    @Override
    public int compareTo(ManifestEntry other) {
        int byKey = key.compareTo(other.key);
        if (byKey != 0) {
            return byKey;
        }
        int byOutcome = expectedOutcome.compareTo(other.expectedOutcome);
        return byOutcome != 0 ? byOutcome : reason.compareTo(other.reason);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ManifestEntry)) {
            return false;
        }
        ManifestEntry other = (ManifestEntry) o;
        return key.equals(other.key) && expectedOutcome == other.expectedOutcome && reason.equals(other.reason);
    }

    @Override
    public int hashCode() {
        return (31 * key.hashCode() + expectedOutcome.hashCode()) * 31 + reason.hashCode();
    }

    @Override
    public String toString() {
        return toTsvLine();
    }
}

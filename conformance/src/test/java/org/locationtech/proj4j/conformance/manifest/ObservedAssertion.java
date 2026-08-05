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
 * What actually happened to one assertion in one run: its identity, its outcome, and an optional
 * one-line detail (the measured deviation, the {@code errno} mismatch, the name of the missing grid).
 *
 * <p>The detail is free text and is used for two things: it appears in the report next to a
 * regression, and it seeds the {@code reason} column when {@link ManifestRegenerator} first records a
 * newly-failing assertion. Tabs and newlines are collapsed to spaces on construction so that it can
 * always be written to the TSV manifest.
 *
 * <p>Immutable.
 */
public final class ObservedAssertion implements Comparable<ObservedAssertion> {

    private final AssertionKey key;
    private final AssertionOutcome outcome;
    private final String detail;

    private ObservedAssertion(AssertionKey key, AssertionOutcome outcome, String detail) {
        this.key = key;
        this.outcome = outcome;
        this.detail = detail;
    }

    /**
     * @param key the assertion
     * @param outcome what happened
     * @return an observation with no detail
     */
    public static ObservedAssertion of(AssertionKey key, AssertionOutcome outcome) {
        return of(key, outcome, "");
    }

    /**
     * @param key the assertion
     * @param outcome what happened
     * @param detail one-line explanation; {@code null} is treated as empty, and tabs/newlines are
     *     collapsed to single spaces
     * @return the observation
     */
    public static ObservedAssertion of(AssertionKey key, AssertionOutcome outcome, String detail) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
        return new ObservedAssertion(key, outcome, flatten(detail));
    }

    /**
     * Collapses a possibly multi-line message into one TSV-safe line.
     *
     * @param text the raw detail, may be {@code null}
     * @return a single-line, tab-free string, never {@code null}
     */
    public static String flatten(String text) {
        if (text == null) {
            return "";
        }
        String flattened = text.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
        StringBuilder collapsed = new StringBuilder(flattened.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < flattened.length(); i++) {
            char c = flattened.charAt(i);
            if (c == ' ') {
                if (!lastWasSpace) {
                    collapsed.append(' ');
                }
                lastWasSpace = true;
            } else {
                collapsed.append(c);
                lastWasSpace = false;
            }
        }
        return collapsed.toString().trim();
    }

    /** @return the assertion identity. */
    public AssertionKey key() {
        return key;
    }

    /** @return the observed outcome. */
    public AssertionOutcome outcome() {
        return outcome;
    }

    /** @return the one-line detail; possibly empty, never {@code null}. */
    public String detail() {
        return detail;
    }

    @Override
    public int compareTo(ObservedAssertion other) {
        return key.compareTo(other.key);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ObservedAssertion)) {
            return false;
        }
        ObservedAssertion other = (ObservedAssertion) o;
        return key.equals(other.key) && outcome == other.outcome && detail.equals(other.detail);
    }

    @Override
    public int hashCode() {
        return (31 * key.hashCode() + outcome.hashCode()) * 31 + detail.hashCode();
    }

    @Override
    public String toString() {
        return key + " " + outcome + (detail.isEmpty() ? "" : " (" + detail + ")");
    }
}

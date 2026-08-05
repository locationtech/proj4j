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
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The result of one execution of the conformance corpus: every assertion that was evaluated, with its
 * outcome.
 *
 * <p>This is the runner's output and the input to {@link ConformanceDiff},
 * {@link ManifestRegenerator} and the report. It deliberately knows nothing about how assertions are
 * executed, so both live runs and hand-built fixtures can be diffed and rendered by the same code.
 *
 * <p>Tallies are kept as three independent counts. There is no "not failing" count, because
 * {@link AssertionOutcome#SKIP} is not a pass.
 *
 * <p>Immutable; build with {@link #builder()}.
 */
public final class ObservedRun {

    private final SortedMap<AssertionKey, ObservedAssertion> assertions;
    private final Map<AssertionOutcome, Integer> counts;

    private ObservedRun(SortedMap<AssertionKey, ObservedAssertion> assertions) {
        this.assertions = assertions;
        Map<AssertionOutcome, Integer> tally = new EnumMap<AssertionOutcome, Integer>(AssertionOutcome.class);
        for (AssertionOutcome outcome : AssertionOutcome.values()) {
            tally.put(outcome, Integer.valueOf(0));
        }
        for (ObservedAssertion observed : assertions.values()) {
            tally.put(observed.outcome(), Integer.valueOf(tally.get(observed.outcome()).intValue() + 1));
        }
        this.counts = Collections.unmodifiableMap(tally);
    }

    /** @return a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** @return an empty run. */
    public static ObservedRun empty() {
        return new ObservedRun(new TreeMap<AssertionKey, ObservedAssertion>());
    }

    /**
     * @param key the assertion
     * @return the observation, or {@code null} if the assertion was not part of this run
     */
    public ObservedAssertion get(AssertionKey key) {
        return assertions.get(key);
    }

    /**
     * @param key the assertion
     * @return the observed outcome, or {@code null} if the assertion was not part of this run.
     *     {@code null} is not "pass": an assertion that did not run has no outcome, and the diff
     *     reports it as {@code DISAPPEARED}.
     */
    public AssertionOutcome outcome(AssertionKey key) {
        ObservedAssertion observed = assertions.get(key);
        return observed == null ? null : observed.outcome();
    }

    /** @return {@code true} if the assertion was evaluated in this run. */
    public boolean contains(AssertionKey key) {
        return assertions.containsKey(key);
    }

    /** @return the observed keys, in canonical order. */
    public SortedSet<AssertionKey> keys() {
        return Collections.unmodifiableSortedSet(new TreeSet<AssertionKey>(assertions.keySet()));
    }

    /** @return the observations, in canonical order. */
    public List<ObservedAssertion> assertions() {
        return Collections.unmodifiableList(new ArrayList<ObservedAssertion>(assertions.values()));
    }

    /**
     * @param outcome the outcome to count
     * @return how many assertions had that outcome
     */
    public int count(AssertionOutcome outcome) {
        return counts.get(outcome).intValue();
    }

    /** @return the number of assertions evaluated (passes + failures + skips). */
    public int total() {
        return assertions.size();
    }

    /** @return the corpus-relative file paths seen in this run, alphabetically. */
    public SortedSet<String> filePaths() {
        SortedSet<String> paths = new TreeSet<String>();
        for (AssertionKey key : assertions.keySet()) {
            paths.add(key.filePath());
        }
        return Collections.unmodifiableSortedSet(paths);
    }

    /**
     * @param filePath a corpus-relative path
     * @return the observations for that file, in canonical order
     */
    public List<ObservedAssertion> assertionsIn(String filePath) {
        List<ObservedAssertion> selected = new ArrayList<ObservedAssertion>();
        for (ObservedAssertion observed : assertions.values()) {
            if (observed.key().filePath().equals(filePath)) {
                selected.add(observed);
            }
        }
        return Collections.unmodifiableList(selected);
    }

    /**
     * @param filePath a corpus-relative path
     * @param outcome the outcome to count
     * @return how many assertions in that file had that outcome
     */
    public int countIn(String filePath, AssertionOutcome outcome) {
        int n = 0;
        for (ObservedAssertion observed : assertions.values()) {
            if (observed.outcome() == outcome && observed.key().filePath().equals(filePath)) {
                n++;
            }
        }
        return n;
    }

    @Override
    public String toString() {
        return "ObservedRun[" + count(AssertionOutcome.PASS) + " pass, " + count(AssertionOutcome.FAIL) + " fail, "
                + count(AssertionOutcome.SKIP) + " skip]";
    }

    /** Accumulates observations. Not thread-safe; a run is assembled by one thread. */
    public static final class Builder {

        private final SortedMap<AssertionKey, ObservedAssertion> assertions =
                new TreeMap<AssertionKey, ObservedAssertion>();

        private Builder() {}

        /**
         * @param key the assertion
         * @param outcome what happened
         * @return this builder
         * @throws IllegalArgumentException if the key was already recorded
         */
        public Builder record(AssertionKey key, AssertionOutcome outcome) {
            return record(ObservedAssertion.of(key, outcome));
        }

        /**
         * @param key the assertion
         * @param outcome what happened
         * @param detail one-line explanation
         * @return this builder
         * @throws IllegalArgumentException if the key was already recorded
         */
        public Builder record(AssertionKey key, AssertionOutcome outcome, String detail) {
            return record(ObservedAssertion.of(key, outcome, detail));
        }

        /**
         * @param observed the observation
         * @return this builder
         * @throws IllegalArgumentException if the key was already recorded — a duplicate key means the
         *     key scheme has collided or the runner double-counted, and either way the tally would be
         *     wrong
         */
        public Builder record(ObservedAssertion observed) {
            ObservedAssertion previous = assertions.put(observed.key(), observed);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "assertion recorded twice in one run: " + observed.key() + " (was " + previous.outcome()
                                + ", now " + observed.outcome() + ")");
            }
            return this;
        }

        /** @return the immutable run. */
        public ObservedRun build() {
            return new ObservedRun(new TreeMap<AssertionKey, ObservedAssertion>(assertions));
        }
    }
}

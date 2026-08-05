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
package org.locationtech.proj4j.conformance.runner;

import org.locationtech.proj4j.conformance.manifest.AssertionKey;
import org.locationtech.proj4j.conformance.manifest.AssertionOutcome;
import org.locationtech.proj4j.conformance.manifest.ObservedAssertion;
import org.locationtech.proj4j.conformance.parse.GieVerb;

/**
 * One evaluated {@code expect} or {@code roundtrip}: its key, its outcome, and enough context to
 * diagnose it without opening the corpus file.
 *
 * <p>Distinct from {@link ObservedAssertion} on purpose. That type is the diffable record — key,
 * outcome, one line of detail — and is deliberately ignorant of {@code .gie} syntax. This one carries
 * the source line, the verb and the governing operation definition, which is what a JUnit display
 * name needs and what nobody wants serialised into the manifest.
 *
 * <p>Immutable.
 */
public final class GieAssertionResult {

    /** How much of an operation definition a display name shows before eliding. */
    static final int OPERATION_DISPLAY_LIMIT = 90;

    private final AssertionKey key;
    private final AssertionOutcome outcome;
    private final String detail;
    private final GieVerb verb;
    private final String assertionArgs;
    private final String operation;
    private final int line;
    private final ExpectedFailureVerdict expectedFailureVerdict;

    GieAssertionResult(
            AssertionKey key,
            AssertionOutcome outcome,
            String detail,
            GieVerb verb,
            String assertionArgs,
            String operation,
            int line,
            ExpectedFailureVerdict expectedFailureVerdict) {
        if (key == null || outcome == null || verb == null) {
            throw new IllegalArgumentException("key, outcome and verb are all required");
        }
        if (expectedFailureVerdict != null && expectedFailureVerdict.outcome() != outcome) {
            throw new IllegalArgumentException(
                    "verdict " + expectedFailureVerdict + " does not tally as " + outcome);
        }
        if (outcome == AssertionOutcome.VACUOUS_EXPECTED_FAILURE && expectedFailureVerdict == null) {
            throw new IllegalArgumentException(
                    "a VACUOUS_EXPECTED_FAILURE can only arise from an `expect failure` row, so it must"
                            + " carry the verdict that produced it: " + key);
        }
        this.key = key;
        this.outcome = outcome;
        this.detail = ObservedAssertion.flatten(detail == null ? "" : detail);
        this.verb = verb;
        this.assertionArgs = assertionArgs == null ? "" : assertionArgs;
        this.operation = operation == null ? "" : operation;
        this.line = line;
        this.expectedFailureVerdict = expectedFailureVerdict;
    }

    /** @return the manifest key. */
    public AssertionKey key() {
        return key;
    }

    /** @return PASS, FAIL, SKIP or VACUOUS_EXPECTED_FAILURE. */
    public AssertionOutcome outcome() {
        return outcome;
    }

    /**
     * The three-way verdict, for an {@code expect failure} row only.
     *
     * <p>Carried alongside the outcome rather than derived from it, because
     * {@link ExpectedFailureVerdict#PASS_EXPECTED_FAILURE} and an ordinary coordinate comparison are
     * both {@link AssertionOutcome#PASS} and a report has to be able to say how many of the corpus's
     * 1,187 {@code expect failure} rows are genuinely passing rather than merely counted.
     *
     * @return the verdict, or {@code null} if this assertion was not an {@code expect failure}
     */
    public ExpectedFailureVerdict expectedFailureVerdict() {
        return expectedFailureVerdict;
    }

    /** @return {@code true} if this was an {@code expect failure} row. */
    public boolean isExpectedFailureRow() {
        return expectedFailureVerdict != null;
    }

    /** @return a one-line explanation; {@code ""} for an unremarkable pass. */
    public String detail() {
        return detail;
    }

    /** @return {@link GieVerb#EXPECT} or {@link GieVerb#ROUNDTRIP}. */
    public GieVerb verb() {
        return verb;
    }

    /** @return the assertion's normalised argument text, e.g. {@code "failure errno invalid_op"}. */
    public String assertionArgs() {
        return assertionArgs;
    }

    /** @return the governing operation definition, normalised; {@code ""} if there was none. */
    public String operation() {
        return operation;
    }

    /** @return the 1-based source line of the assertion's verb. */
    public int line() {
        return line;
    }

    /** @return the diffable record for {@link org.locationtech.proj4j.conformance.manifest.ObservedRun}. */
    public ObservedAssertion observed() {
        return ObservedAssertion.of(key, outcome, detail);
    }

    /**
     * A JUnit display name: {@code file:block:index} — the coordinates a manifest entry is keyed by —
     * then the source line, the assertion as written, and the operation it is asserted against.
     *
     * @return the display name
     */
    public String displayName() {
        StringBuilder out = new StringBuilder(160);
        out.append(key.filePath())
                .append(':')
                .append(key.operationBlockIndex())
                .append(':')
                .append(key.assertionIndex())
                .append(" (line ")
                .append(line)
                .append(") ")
                .append(verb.token());
        if (!assertionArgs.isEmpty()) {
            out.append(' ').append(assertionArgs);
        }
        out.append("  <-  ").append(elide(operation, OPERATION_DISPLAY_LIMIT));
        return out.toString();
    }

    private static String elide(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit - 3) + "...";
    }

    @Override
    public String toString() {
        return outcome + " " + displayName() + (detail.isEmpty() ? "" : " -- " + detail);
    }
}

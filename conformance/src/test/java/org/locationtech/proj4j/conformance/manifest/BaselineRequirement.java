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
 * The gate's precondition: a baseline must exist before a run can be compared against one.
 *
 * <h2>The failure this exists to prevent</h2>
 *
 * <p>{@link ExpectedOutcomeManifest#empty()} and {@link CorpusIndex#empty()} are legitimate values —
 * they mean "nothing is excused" and "the corpus was empty last time". Handing them to
 * {@link ConformanceDiff} when the baseline files are simply <em>absent</em> is not legitimate, and it
 * produces the worst possible outcome: with an empty index no observed key is
 * {@code known}, so every assertion in the corpus classifies as {@link DiffClassification#NEW};
 * {@code NEW} does not fail the build; {@link DiffClassification#REGRESSED},
 * {@link DiffClassification#UNEXPECTED_PASS} and {@link DiffClassification#DISAPPEARED} are all
 * necessarily zero; and {@code mvn -Pconformance verify} reports success <em>however many assertions
 * failed</em>.
 *
 * <p>That is a green gate produced by an instrument that cannot see. It is indistinguishable, from the
 * outside, from a gate that examined 7,923 assertions and approved of all of them.
 *
 * <h2>What this class does and does not change</h2>
 *
 * <p>{@code NEW} remains non-build-failing: a genuinely new assertion — a re-vendor, or an upstream
 * edit that changes a content hash — should be held to the "must pass" default and reported, not
 * treated as a break. What must be distinguishable is <em>"the entire corpus is new"</em> from
 * <em>"there is no baseline"</em>. Those two produce identical diffs, so the distinction cannot live in
 * the diff; it has to be made before the comparison, from the presence of the files. That is all this
 * class is.
 *
 * <p>The diagnostics are deliberately <strong>not</strong> phrased as a count of regressions. A missing
 * baseline reported as "5830 REGRESSED" sends the reader hunting for a regression that did not happen.
 * Nothing regressed; the comparison never took place.
 *
 * <p>Every method is a pure function of its arguments, so the guard is unit-testable without a
 * filesystem — and, more to the point, so it can be shown to <em>fail</em> on demand. A precondition
 * check that has never been observed to fire is a claim, not a check.
 */
public final class BaselineRequirement {

    /** The checked-in baseline of non-passing assertions, relative to {@code src/test/resources}. */
    public static final String MANIFEST_FILE = "gie-expected-failures.tsv";

    /** The checked-in corpus index, which must always travel with the manifest. */
    public static final String CORPUS_INDEX_FILE = "gie-corpus-index.tsv";

    /** The one command that produces both files. */
    public static final String REGENERATE_COMMAND = "mvn -Pconformance verify -Dgie.regenerate=true";

    private static final String NEWLINE = "\n";

    private BaselineRequirement() {}

    /**
     * Diagnoses the presence of the two baseline files.
     *
     * @param manifestPresent whether {@value #MANIFEST_FILE} was found
     * @param indexPresent whether {@value #CORPUS_INDEX_FILE} was found
     * @param searchedIn where the search looked, for the message; may be {@code null}
     * @return {@code ""} if both files are present, otherwise the diagnostic
     */
    public static String diagnosePresence(boolean manifestPresent, boolean indexPresent, String searchedIn) {
        if (manifestPresent && indexPresent) {
            return "";
        }
        StringBuilder out = new StringBuilder(1024);
        if (!manifestPresent && !indexPresent) {
            out.append("CONFORMANCE BASELINE MISSING: neither ")
                    .append(MANIFEST_FILE)
                    .append(" nor ")
                    .append(CORPUS_INDEX_FILE)
                    .append(" was found.")
                    .append(NEWLINE);
        } else {
            String missing = manifestPresent ? CORPUS_INDEX_FILE : MANIFEST_FILE;
            String present = manifestPresent ? MANIFEST_FILE : CORPUS_INDEX_FILE;
            out.append("CONFORMANCE BASELINE INCOMPLETE: ")
                    .append(missing)
                    .append(" was not found, but ")
                    .append(present)
                    .append(" was.")
                    .append(NEWLINE)
                    .append("The two files are one baseline and must always travel together: the manifest")
                    .append(" records only the non-passing minority, so without the index a passing key's")
                    .append(" absence is not evidence of anything and the diff invents NEW/DISAPPEARED")
                    .append(" entries out of nothing.")
                    .append(NEWLINE);
        }
        appendWhereAndWhy(out, searchedIn);
        return out.toString();
    }

    /**
     * Diagnoses a baseline that is present but covers nothing — a truncated, hand-emptied or
     * wrong-corpus index file. Its effect is identical to an absent one: every observed assertion is
     * {@code NEW} and the gate cannot fail.
     *
     * @param indexKeyCount how many keys the loaded {@link CorpusIndex} holds
     * @param observedAssertions how many assertions the run actually evaluated
     * @param searchedIn where the baseline was read from, for the message; may be {@code null}
     * @return {@code ""} if the index covers something or the run observed nothing, otherwise the
     *     diagnostic
     */
    public static String diagnoseCoverage(int indexKeyCount, int observedAssertions, String searchedIn) {
        if (indexKeyCount > 0 || observedAssertions <= 0) {
            return "";
        }
        StringBuilder out = new StringBuilder(1024);
        out.append("CONFORMANCE BASELINE EMPTY: ")
                .append(CORPUS_INDEX_FILE)
                .append(" was found but holds 0 assertion keys, while this run evaluated ")
                .append(observedAssertions)
                .append(".")
                .append(NEWLINE)
                .append("An index that covers nothing gates nothing: it has exactly the effect of having no")
                .append(" baseline at all.")
                .append(NEWLINE);
        appendWhereAndWhy(out, searchedIn);
        return out.toString();
    }

    /**
     * @param manifestPresent whether {@value #MANIFEST_FILE} was found
     * @param indexPresent whether {@value #CORPUS_INDEX_FILE} was found
     * @param searchedIn where the search looked, for the message; may be {@code null}
     * @throws MissingBaselineException if either file is absent
     */
    public static void requirePresence(boolean manifestPresent, boolean indexPresent, String searchedIn) {
        String diagnostic = diagnosePresence(manifestPresent, indexPresent, searchedIn);
        if (!diagnostic.isEmpty()) {
            throw new MissingBaselineException(diagnostic);
        }
    }

    /**
     * @param indexKeyCount how many keys the loaded {@link CorpusIndex} holds
     * @param observedAssertions how many assertions the run evaluated
     * @param searchedIn where the baseline was read from, for the message; may be {@code null}
     * @throws MissingBaselineException if the index covers nothing while the run observed something
     */
    public static void requireCoverage(int indexKeyCount, int observedAssertions, String searchedIn) {
        String diagnostic = diagnoseCoverage(indexKeyCount, observedAssertions, searchedIn);
        if (!diagnostic.isEmpty()) {
            throw new MissingBaselineException(diagnostic);
        }
    }

    private static void appendWhereAndWhy(StringBuilder out, String searchedIn) {
        if (searchedIn != null && !searchedIn.trim().isEmpty()) {
            out.append("Searched: ").append(searchedIn).append(NEWLINE);
        }
        out.append(NEWLINE)
                .append("NOTHING HAS REGRESSED. This is not a conformance failure and no assertion is being")
                .append(" reported as broken: the comparison never happened. Without a baseline every")
                .append(" observed assertion classifies as NEW, NEW does not fail the build, and REGRESSED,")
                .append(" UNEXPECTED_PASS and DISAPPEARED are all necessarily zero -- so this build would")
                .append(" otherwise have reported SUCCESS no matter how many assertions failed.")
                .append(NEWLINE)
                .append(NEWLINE)
                .append("Generate the baseline (writes both files; commit them in the same commit):")
                .append(NEWLINE)
                .append("  ")
                .append(REGENERATE_COMMAND)
                .append(NEWLINE);
    }

    /**
     * Thrown when the gate is asked to compare a run against a baseline that is absent or empty.
     *
     * <p>An {@link IllegalStateException} rather than an {@code AssertionError}, because it is not an
     * assertion about proj4j's conformance: it is a statement that conformance was not measured.
     */
    public static final class MissingBaselineException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        /** @param message the diagnostic */
        public MissingBaselineException(String message) {
            super(message);
        }
    }
}

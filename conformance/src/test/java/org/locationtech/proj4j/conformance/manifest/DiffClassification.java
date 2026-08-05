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
 * How one assertion's observed outcome relates to its recorded expectation.
 *
 * <p>Three of the six break the build — see {@link #failsBuild()} and
 * {@link DiffResult#shouldFailBuild()}.
 */
public enum DiffClassification {

    /** Expected to pass and passed. The goal state for all 7,923 evaluated assertions. */
    UNCHANGED(false),

    /**
     * Expected {@link AssertionOutcome#PASS}, observed {@link AssertionOutcome#FAIL} or
     * {@link AssertionOutcome#SKIP}. A regression: work that was correct is no longer correct, or a
     * resource that was available no longer is. Always breaks the build.
     */
    REGRESSED(true),

    /**
     * Expected a non-pass, observed {@link AssertionOutcome#PASS}. Breaks the build <em>on purpose</em>
     * — see {@link DiffResult#shouldFailBuild()}.
     */
    UNEXPECTED_PASS(true),

    /**
     * Expected a non-pass and got one. The count of remaining known work — the number that must fall
     * each stage. Does not break the build. Includes the case where the flavour of non-pass changed
     * (FAIL to SKIP or back); those keys are additionally reported by
     * {@link DiffResult#outcomeChangedKeys()}.
     */
    STILL_FAILING(false),

    /**
     * Observed, but absent from the corpus index the manifest was generated against. A brand-new
     * assertion, or an existing one whose content hash changed because upstream edited it. Does not
     * break the build: there is no baseline for the key, so there is nothing it can be said to have
     * regressed from.
     *
     * <p><strong>Read that literally.</strong> {@link ConformanceDiff} tests this case <em>before</em>
     * it consults the expected outcome, so a new assertion that fails is {@code NEW}, not
     * {@code REGRESSED} — it is reported and counted, and the build stays green until the baseline is
     * regenerated, after which the key is in the index and any later failure is a genuine
     * {@code REGRESSED}. (An earlier version of this comment claimed the opposite, that a failing new
     * assertion "will show up as REGRESSED instead". It does not, and no test asserted that it did.)
     *
     * <p>The consequence worth knowing: if <em>every</em> key is {@code NEW}, nothing is being gated at
     * all. That is what an absent or empty baseline looks like from inside the diff, and it is why
     * {@link BaselineRequirement} refuses to let the gate run without one, rather than this
     * classification being made build-failing.
     */
    NEW(false),

    /**
     * Present in the corpus index or the manifest, but not observed. Always breaks the build, because
     * an assertion that silently stops running is indistinguishable from one that silently stops
     * being checked — and a manifest entry that matches nothing is dead weight that overstates the
     * remaining work.
     */
    DISAPPEARED(true);

    private final boolean failsBuild;

    DiffClassification(boolean failsBuild) {
        this.failsBuild = failsBuild;
    }

    /** @return {@code true} if the presence of any assertion in this class must fail the build. */
    public boolean failsBuild() {
        return failsBuild;
    }
}

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
 * The outcome of evaluating a single {@code .gie} assertion ({@code expect} or {@code roundtrip}).
 *
 * <p><strong>There are exactly four outcomes, and neither {@link #SKIP} nor
 * {@link #VACUOUS_EXPECTED_FAILURE} is ever counted as a {@link #PASS}.</strong> Upstream
 * {@code gie} exits with the number of failures and silently drops skips from the tally; that is
 * adequate for a CLI exit code but useless as a progress metric, because a corpus that skips
 * everything would report a clean run. Every tally in this package therefore reports the four as
 * four separate numbers, and the headline metric counts only {@link #PASS} — over a denominator
 * from which {@link #VACUOUS_EXPECTED_FAILURE} has been removed, because those assertions measured
 * nothing at all.
 *
 * <p>Sources of {@link #SKIP}, per {@code 9.8.1:src/apps/gie.cpp}:
 * <ul>
 *   <li>{@code require_grid <filename>} ({@code gie.cpp:566}) — when the named grid cannot be
 *       resolved, {@code skip_test} is set and <em>every</em> subsequent {@code expect} in the block
 *       becomes a skip, until the next {@code operation} or completed {@code crs_src}+{@code crs_dst}
 *       pair. Five uses in the corpus.</li>
 *   <li>{@code ignore <errno-const>} ({@code gie.cpp:561}) — when the operation's {@code errno}
 *       equals the ignored constant, the assertion is skipped. This check runs <em>before</em> the
 *       failure-expectation check in {@code expect}. Zero uses in the 9.8.1 corpus (superseded by
 *       {@code require_grid}), but the verb exists and must be honoured.</li>
 * </ul>
 *
 * <p>A skipped assertion is neither evidence of correctness nor evidence of a defect: it is evidence
 * that the run was not able to make a judgement. Folding it into either bucket falsifies the metric.
 * The same is true, for a different reason, of {@link #VACUOUS_EXPECTED_FAILURE}.
 */
public enum AssertionOutcome {

    /** The observed coordinate (or failure) matched the expectation within tolerance. */
    PASS,

    /**
     * The assertion was evaluated and did not match. Includes "failed to fail": an
     * {@code expect failure} whose operation unexpectedly succeeded.
     */
    FAIL,

    /**
     * The assertion could not be evaluated — a missing grid ({@code require_grid}) or an ignored
     * {@code errno} ({@code ignore}). <strong>Never a pass.</strong>
     */
    SKIP,

    /**
     * An {@code expect failure} row that "passed" without demonstrating anything: upstream {@code gie}
     * would score it a success, but proj4j failed for a reason unrelated to what the row asserts.
     * <strong>Never a pass, never a failure, and excluded from both sides of the headline ratio.</strong>
     *
     * <h2>Why this outcome has to exist</h2>
     *
     * <p>{@code expect} accepts a failed transform as success iff the operation could not be created or
     * the transform returned {@code HUGE_VAL} ({@code gie.cpp:1052-1072}). It cannot tell <em>why</em>
     * either happened. So when proj4j has no implementation of a projection at all, every
     * {@code expect failure} row in that projection's file "passes": PROJ built the operation and then
     * rejected the coordinate as out-of-domain, proj4j never built the operation, both "failed", and the
     * assertion is scored a success. {@code gie/adams_hemi.gie} is 388 rows of exactly this. Counting
     * them is <em>failure-to-implement being reported as conformance</em> — the same falsification as
     * counting a {@link #SKIP} as a {@link #PASS}, which is why the two live in the same enum with the
     * same rule.
     *
     * <h2>The classification rule</h2>
     *
     * <p>An {@code expect failure} row splits three ways. When the operation <em>was</em> created and
     * the coordinate was rejected, proj4j and PROJ agreed about the domain and the row is a real
     * {@link #PASS}. When the transform succeeded, the row is a real {@link #FAIL} ("failed to fail").
     * When the operation could <strong>not be created</strong>, the verdict depends on whether the row
     * is asking for a definition to be rejected or for a coordinate to be rejected:
     *
     * <ul>
     *   <li>The corpus named a <strong>{@code coord_transfm*}</strong> or {@code no_inverse_op} errno.
     *       Those errnos can only be raised after {@code proj_create} succeeded, so the row asserts
     *       that PROJ <em>built</em> the operation. A construction failure here is
     *       {@code VACUOUS_EXPECTED_FAILURE} whatever proj4j calls it — including
     *       {@link org.locationtech.proj4j.conformance.bridge.GieFailureKind#INVALID_DEFINITION}, whose
     *       contract is "PROJ would reject this too" and which the corpus has just contradicted. This
     *       is how "an {@code INVALID_DEFINITION} that PROJ would in fact have accepted" is detected:
     *       by the corpus, not by asking the bridge to grade its own homework.</li>
     *   <li>Otherwise — an {@code invalid_op*} errno, an unrecognised errno name, or no errno at all —
     *       the row is (or may be) asking for the definition itself to be refused, and the verdict is
     *       proj4j's own classification:
     *       <ul>
     *         <li>{@code INVALID_DEFINITION} &rarr; {@link #PASS}. The bridge's documented claim is that
     *             PROJ 9.8.1 would refuse this definition too, so refusing it <em>is</em> the
     *             conformance being asserted. {@code gie/ellipsoid.gie}'s
     *             {@code expect failure errno invalid_op_illegal_arg_value} rows are the canonical case
     *             and they are real passes.</li>
     *         <li>{@code MISSING_GRID} &rarr; {@link #PASS} only under
     *             {@code invalid_op_file_not_found_or_invalid}, where the row asserts that PROJ could
     *             not find or read the file either. Otherwise vacuous.</li>
     *         <li>{@code NOT_IMPLEMENTED}, or anything else, &rarr; {@code VACUOUS_EXPECTED_FAILURE}.
     *             {@code NOT_IMPLEMENTED} is by definition a statement about proj4j's gaps rather than
     *             about the definition: a projection we cannot construct at all would "reject" every
     *             definition of it, valid ones included, so nothing has been demonstrated.</li>
     *       </ul>
     *   </li>
     * </ul>
     *
     * <p>The boundary is drawn conservatively on purpose. Where a row's intent is ambiguous the outcome
     * is vacuous, because an assertion wrongly called vacuous understates progress and is visible in
     * the report, whereas one wrongly called a pass inflates the headline and is invisible.
     *
     * <p>One residual is known and deliberately left alone, because it is what upstream {@code gie}
     * scores: a row that names an {@code invalid_op*} errno, where proj4j <em>accepted</em> the
     * definition PROJ rejects and then happened to fail on the coordinate, is recorded as a
     * {@link #PASS}. proj4j is laxer than PROJ there; the row is nonetheless a genuine coordinate-level
     * agreement about that coordinate, and it is not a construction failure, which is what this
     * classification is about.
     *
     * <h2>Which files this reclassifies</h2>
     *
     * <p>Measured over the 9.8.1 corpus, the reclassified rows are concentrated in the files whose
     * projection or operator proj4j does not implement at all — {@code gie/adams_hemi.gie} (388),
     * {@code gie/guyou.gie} (386), {@code gie/peirce_q.gie} (80), {@code gie/adams_ws1.gie} and
     * {@code gie/adams_ws2.gie} (57 each), and the pipeline-, grid- and deformation-dependent files
     * {@code axisswap}, {@code defmodel}, {@code deformation}, {@code geotiff_grids}, {@code gridshift},
     * {@code tinshift}, {@code unitconvert} and {@code 4D-API_cs2cs-style}. {@code gie/ellipsoid.gie}
     * is the file that most clearly does <em>not</em> reclassify: its rows exist to have a definition
     * refused, proj4j refuses them as {@code INVALID_DEFINITION}, and they stay passes. See
     * {@code target/conformance/expect-failures.tsv} for the per-file split as measured by the run in
     * hand rather than as remembered here.
     *
     * @see org.locationtech.proj4j.conformance.runner.ExpectedFailureVerdict
     */
    VACUOUS_EXPECTED_FAILURE;

    /**
     * @return {@code true} only for {@link #PASS}; provided so callers cannot fumble the skip and
     *     vacuity rules
     */
    public boolean isPass() {
        return this == PASS;
    }

    /**
     * @return {@code true} for {@link #FAIL}, {@link #SKIP} and {@link #VACUOUS_EXPECTED_FAILURE} —
     *     i.e. "did not pass"
     */
    public boolean isNotPass() {
        return this != PASS;
    }

    /**
     * Whether this outcome represents a judgement about proj4j's conformance at all.
     *
     * <p>{@link #SKIP} and {@link #VACUOUS_EXPECTED_FAILURE} are both unmeasured, but they are not
     * interchangeable and are always tallied apart: a skip declined to run, whereas a vacuous
     * expected-failure ran and produced an answer that happens to carry no information.
     *
     * @return {@code false} for {@link #SKIP} and {@link #VACUOUS_EXPECTED_FAILURE}
     */
    public boolean isMeasured() {
        return this == PASS || this == FAIL;
    }

    /** @return {@code true} only for {@link #VACUOUS_EXPECTED_FAILURE}. */
    public boolean isVacuous() {
        return this == VACUOUS_EXPECTED_FAILURE;
    }

    /**
     * Parses an outcome name, strictly and case-sensitively.
     *
     * @param text the token, expected to be exactly {@code PASS}, {@code FAIL}, {@code SKIP} or
     *     {@code VACUOUS_EXPECTED_FAILURE}
     * @return the outcome
     * @throws IllegalArgumentException if the token is not one of the four names
     */
    public static AssertionOutcome parse(String text) {
        if (PASS.name().equals(text)) {
            return PASS;
        }
        if (FAIL.name().equals(text)) {
            return FAIL;
        }
        if (SKIP.name().equals(text)) {
            return SKIP;
        }
        if (VACUOUS_EXPECTED_FAILURE.name().equals(text)) {
            return VACUOUS_EXPECTED_FAILURE;
        }
        throw new IllegalArgumentException(
                "not an assertion outcome (expected PASS, FAIL, SKIP or VACUOUS_EXPECTED_FAILURE): \""
                        + text + "\"");
    }
}

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

import org.locationtech.proj4j.conformance.bridge.GieFailureKind;
import org.locationtech.proj4j.conformance.manifest.AssertionOutcome;

/**
 * The three-way split of an {@code expect failure} row.
 *
 * <p>Upstream {@code gie} has a two-way split: the transform failed (success) or it did not
 * ({@code "failed to fail"}). Two-way is not enough to measure anything, because "the transform
 * failed" includes "there was no transform" — see {@link AssertionOutcome#VACUOUS_EXPECTED_FAILURE},
 * which carries the full statement of the problem and of the rule this enum implements.
 *
 * <h2>The rule, as code reads it</h2>
 *
 * <p>{@link #ofTransformFailure} and {@link #ofFailureToFail} are constants: a rejected coordinate is
 * always genuine, and a transform that succeeded is always a failure. The whole decision lives in
 * {@link #ofConstructionFailure}, which asks two questions in this order:
 *
 * <ol>
 *   <li><strong>Does the row assert that PROJ got as far as a coordinate?</strong> A
 *       {@code coord_transfm*} or {@code no_inverse_op} errno can only be raised after
 *       {@code proj_create} returned an object, so it does. proj4j did not get that far, so nothing
 *       was compared: {@link #VACUOUS_EXPECTED_FAILURE}, regardless of how the bridge classified its
 *       own failure. 794 of the corpus's errno expectations are
 *       {@code coord_transfm_outside_projection_domain} alone, and they are the whole
 *       {@code adams_hemi}/{@code guyou}/{@code peirce_q} population.</li>
 *   <li><strong>Otherwise, does proj4j claim the <em>definition</em> is bad?</strong>
 *       {@link GieFailureKind#INVALID_DEFINITION} means, by its own documented contract, "PROJ 9.8.1
 *       would refuse this too" — which is exactly what such a row asserts, so it is a genuine
 *       {@link #PASS_EXPECTED_FAILURE}. {@link GieFailureKind#MISSING_GRID} is genuine only under
 *       {@code invalid_op_file_not_found_or_invalid}. {@link GieFailureKind#NOT_IMPLEMENTED} is never
 *       genuine: it is a statement about proj4j, not about the definition.</li>
 * </ol>
 *
 * <p>The asymmetry between the two questions is deliberate. Question 1 lets the <em>corpus</em>
 * overrule the bridge, because the corpus is upstream's own record of what PROJ does and the bridge's
 * {@code INVALID_DEFINITION} is a claim the bridge makes about PROJ. Question 2 does not let the
 * corpus promote a {@code NOT_IMPLEMENTED} into a pass, because "we cannot build this at all" refuses
 * valid and invalid definitions alike and so cannot be evidence about either.
 */
public enum ExpectedFailureVerdict {

    /**
     * A genuine demonstration of conformance: proj4j failed for the reason the row is about. Recorded
     * as {@link AssertionOutcome#PASS} and counted in the headline.
     */
    PASS_EXPECTED_FAILURE(AssertionOutcome.PASS),

    /**
     * The row scored a success without measuring anything. Recorded as
     * {@link AssertionOutcome#VACUOUS_EXPECTED_FAILURE} and excluded from both the numerator and the
     * denominator of the headline.
     */
    VACUOUS_EXPECTED_FAILURE(AssertionOutcome.VACUOUS_EXPECTED_FAILURE),

    /** "Failed to fail": the operation was created and the transform returned a coordinate. */
    FAIL(AssertionOutcome.FAIL);

    /** The canonical errno prefix shared by every errno PROJ can only raise post-construction. */
    private static final String COORD_TRANSFM_PREFIX = "coord_transfm";

    /** The one errno that is raised while <em>building</em> an operation from a grid file. */
    private static final String FILE_NOT_FOUND = "invalid_op_file_not_found_or_invalid";

    /** Raised by {@code proj_trans}, not by {@code proj_create}: the object exists, the inverse does not. */
    private static final String NO_INVERSE = "no_inverse_op";

    private final AssertionOutcome outcome;

    ExpectedFailureVerdict(AssertionOutcome outcome) {
        this.outcome = outcome;
    }

    /** @return how this verdict is tallied and recorded in the manifest. */
    public AssertionOutcome outcome() {
        return outcome;
    }

    /** @return {@code true} for {@link #VACUOUS_EXPECTED_FAILURE}. */
    public boolean isVacuous() {
        return this == VACUOUS_EXPECTED_FAILURE;
    }

    /**
     * The verdict when the operation was created and the transform rejected the coordinate.
     *
     * @return {@link #PASS_EXPECTED_FAILURE}, always — proj4j and PROJ agreed about the domain, which
     *     is the conformance the row asserts
     */
    public static ExpectedFailureVerdict ofTransformFailure() {
        return PASS_EXPECTED_FAILURE;
    }

    /**
     * The verdict when the operation was created and the transform returned a coordinate.
     *
     * @return {@link #FAIL}, always
     */
    public static ExpectedFailureVerdict ofFailureToFail() {
        return FAIL;
    }

    /**
     * The verdict when the operation could not be created.
     *
     * @param canonicalErrno the errno the row named, already resolved through
     *     {@code errno_from_err_const}'s prefix match; {@code null} for a bare {@code expect failure}
     *     or for a name PROJ itself would not have recognised (which upstream degenerates to 9999, an
     *     errno no operation can report, so it carries no information either way)
     * @param kind why the bridge could not build the operation; {@code null} when there was no
     *     operation to build at all, which is vacuous by the same argument
     * @return the verdict
     */
    public static ExpectedFailureVerdict ofConstructionFailure(String canonicalErrno, GieFailureKind kind) {
        if (kind == null) {
            return VACUOUS_EXPECTED_FAILURE;
        }
        if (assertsProjBuiltTheOperation(canonicalErrno)) {
            return VACUOUS_EXPECTED_FAILURE;
        }
        switch (kind) {
            case INVALID_DEFINITION:
                return PASS_EXPECTED_FAILURE;
            case MISSING_GRID:
                return FILE_NOT_FOUND.equals(canonicalErrno)
                        ? PASS_EXPECTED_FAILURE
                        : VACUOUS_EXPECTED_FAILURE;
            default:
                // NOT_IMPLEMENTED, and every coordinate-level kind, which cannot honestly arise at
                // construction time and is therefore not evidence about a definition either.
                return VACUOUS_EXPECTED_FAILURE;
        }
    }

    /**
     * Whether the errno the row named is one PROJ can only report from {@code proj_trans} — i.e. the row
     * is a statement that {@code proj_create} <em>succeeded</em>.
     *
     * @param canonicalErrno a canonical errno name, or {@code null}
     * @return {@code true} if the row asserts a built operation
     */
    static boolean assertsProjBuiltTheOperation(String canonicalErrno) {
        return canonicalErrno != null
                && (canonicalErrno.startsWith(COORD_TRANSFM_PREFIX) || canonicalErrno.equals(NO_INVERSE));
    }
}

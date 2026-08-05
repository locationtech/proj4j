/*
 * Copyright 2026 The Proj4J Contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.locationtech.proj4j.conformance.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.pipeline.PipelineDefinitionException;
import org.locationtech.proj4j.pipeline.PipelineErrorCode;

/**
 * The re-keying control for {@code Proj4jGieOperationFactory.pipelineKind}.
 *
 * <h2>What changed and why</h2>
 *
 * <p>{@code pipelineKind} used to key its one special case on <em>enum identity</em> —
 * {@code p.code() == PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID} — and it now keys on the
 * <em>cause</em>, {@code p.code().errorCause() == ErrorCause.MISSING_GRID}. The bridge should
 * reflect core's taxonomy rather than name a constant that can be renamed out from under it,
 * which is exactly what nearly happened: core's enum was split, {@code FILE_NOT_FOUND_OR_INVALID}
 * kept its name but changed its meaning ({@code rejectedByProj} {@code true} → {@code false},
 * cause {@code INVALID_PARAM_VALUE} → {@code MISSING_GRID}), and the {@code +init=} population
 * moved to the new {@code INVALID_INIT_KEY}.
 *
 * <h2>The special case is load-bearing, and this file pins that</h2>
 *
 * <p>It is tempting to read the branch as redundant. It is not. Because
 * {@code FILE_NOT_FOUND_OR_INVALID.isRejectedByProj()} is now {@code false}, deleting the branch
 * does not fall through to {@code INVALID_DEFINITION} — it falls through to
 * {@code NOT_IMPLEMENTED}, which {@code ExpectedFailureVerdict} never scores as genuine.
 * <b>Measured against the live gate on 2026-08-01, deletion cost 7 assertions</b>
 * (7378/7895 → 7371/7888, 7 {@code REGRESSED}, vacuous 28 → 35): {@code tinshift.gie#1:0} and
 * {@code #2:0}, {@code deformation.gie#6:0}/{@code #7:0}/{@code #8:0},
 * {@code geotiff_grids.gie#38:0} and {@code more_builtins.gie#25:0}.
 *
 * <h2>Non-vacuity</h2>
 *
 * <p>Per the skill's non-negotiable 5c, an equivalence assertion that ranges over nothing reports
 * the same clean pass as one that ranges over everything, so
 * {@link #theEquivalenceSweepCanActuallyFail()} re-runs the identical comparison against a mutant
 * legacy function and requires it to disagree. Each test that sweeps the enum also asserts what it
 * covered, not merely that it found no counterexample.
 */
class PipelineKindTest {

    /**
     * The implementation as it stood before the re-keying, written out so the two can be compared
     * over every enum constant rather than over the one the change was motivated by.
     */
    private static GieFailureKind legacyConstantIdentityKind(PipelineErrorCode code) {
        if (code == PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID) {
            return GieFailureKind.MISSING_GRID;
        }
        return code.isRejectedByProj()
                ? GieFailureKind.INVALID_DEFINITION
                : GieFailureKind.NOT_IMPLEMENTED;
    }

    /** What {@code pipelineKind} would return with the special case deleted altogether. */
    private static GieFailureKind fallbackOnly(PipelineErrorCode code) {
        return code.isRejectedByProj()
                ? GieFailureKind.INVALID_DEFINITION
                : GieFailureKind.NOT_IMPLEMENTED;
    }

    /**
     * The production path, exercised end to end: the same {@code mapPipelineThrowable} the factory
     * calls from {@code createFromPipelineEngine}. Reaching {@code pipelineKind} through it rather
     * than by reflection means a change to the dispatch above it is caught too.
     */
    private static GieFailureKind productionKind(PipelineErrorCode code) {
        GieFailure f = Proj4jGieOperationFactory.mapPipelineThrowable(
                new PipelineDefinitionException(code, "synthetic: " + code));
        assertNotNull(f, "mapPipelineThrowable must never ask for a PipelineDefinitionException"
                + " to be rethrown");
        return f.kind();
    }

    // ------------------------------------------------------ what core declares today

    @Test
    @DisplayName("FILE_NOT_FOUND_OR_INVALID: errno 1029, MISSING_GRID, and NOT rejected by PROJ")
    void fileNotFoundIsNotAnUpstreamRejection() {
        PipelineErrorCode c = PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID;
        assertEquals(1029, c.projErrno(),
                "PROJ_ERR_INVALID_OP_FILE_NOT_FOUND_OR_INVALID is 1029 (9.8.1:src/proj.h)");
        assertEquals(ErrorCause.MISSING_GRID, c.errorCause(),
                "the cause this bridge now keys on");
        assertFalse(c.isRejectedByProj(),
                "proj4j failing to READ a file is a statement about proj4j's readers, not about"
                        + " the definition. If this ever goes back to true, the fallback below"
                        + " changes meaning and pipelineKind must be re-read, not just re-run.");
    }

    @Test
    @DisplayName("INVALID_INIT_KEY took the +init= population: errno 1027, INVALID_PARAM_VALUE")
    void invalidInitKeyCarriesTheInitPopulation() {
        PipelineErrorCode c = PipelineErrorCode.INVALID_INIT_KEY;
        // get_init_string sets PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE at 9.8.1:src/init.cpp:105
        // ("Missing colon in +init"), :119 ("Cannot open %s") and :134 ("Invalid content for %s").
        assertEquals(1027, c.projErrno(), "9.8.1:src/init.cpp:105,119,134 - not 1029");
        assertEquals(ErrorCause.INVALID_PARAM_VALUE, c.errorCause());
        assertTrue(c.isRejectedByProj(), "an unresolvable +init= key really is an upstream refusal");
        assertNotEquals(ErrorCause.MISSING_GRID, c.errorCause(),
                "if the init population ever acquired the MISSING_GRID cause, the re-keyed check"
                        + " would silently start calling those rows MISSING_GRID");
    }

    // -------------------------------------------------- the re-keying is behaviour-neutral

    @Test
    @DisplayName("re-keying on ErrorCause.MISSING_GRID agrees with the old constant-identity check"
            + " over every PipelineErrorCode")
    void rekeyingIsBehaviourNeutralOverTheWholeEnum() {
        PipelineErrorCode[] all = PipelineErrorCode.values();
        assertTrue(all.length >= 8,
                "the sweep must range over the real enum; found only " + all.length + " constants");

        Map<GieFailureKind, Integer> produced = new EnumMap<GieFailureKind, Integer>(GieFailureKind.class);
        for (PipelineErrorCode code : all) {
            GieFailureKind actual = productionKind(code);
            assertEquals(legacyConstantIdentityKind(code), actual,
                    "the re-keyed check disagrees with the constant-identity check on " + code
                            + " (errno " + code.projErrno() + ", cause " + code.errorCause()
                            + ", rejectedByProj " + code.isRejectedByProj() + ").\n"
                            + "If this fires because core gave a SECOND code the MISSING_GRID"
                            + " cause, the re-keying is doing its job and the change is intended:"
                            + " delete the legacy arm, re-run the gate, and record the new"
                            + " headline. Do not 'fix' it by going back to enum identity.");
            Integer n = produced.get(actual);
            produced.put(actual, Integer.valueOf(n == null ? 1 : n.intValue() + 1));
        }

        // Coverage, not just absence of a counterexample: the sweep must have exercised all three
        // outcomes, or an agreement between two functions that only ever return one value would
        // look identical to this.
        assertTrue(produced.containsKey(GieFailureKind.MISSING_GRID),
                "no code produced MISSING_GRID, so the branch under test was never taken: " + produced);
        assertTrue(produced.containsKey(GieFailureKind.INVALID_DEFINITION),
                "no code produced INVALID_DEFINITION: " + produced);
        assertTrue(produced.containsKey(GieFailureKind.NOT_IMPLEMENTED),
                "no code produced NOT_IMPLEMENTED: " + produced);
    }

    @Test
    @DisplayName("positive control: the equivalence sweep can actually report a disagreement")
    void theEquivalenceSweepCanActuallyFail() {
        // Identical comparison, one deliberately wrong arm: key the special case on WRONG_SYNTAX
        // instead of the file code. If the sweep above is capable of detecting anything, this must
        // find at least two disagreements - WRONG_SYNTAX itself and FILE_NOT_FOUND_OR_INVALID.
        int disagreements = 0;
        for (PipelineErrorCode code : PipelineErrorCode.values()) {
            GieFailureKind mutant = code == PipelineErrorCode.WRONG_SYNTAX
                    ? GieFailureKind.MISSING_GRID
                    : fallbackOnly(code);
            if (mutant != productionKind(code)) {
                disagreements++;
            }
        }
        assertTrue(disagreements >= 2,
                "the comparison used by rekeyingIsBehaviourNeutralOverTheWholeEnum detected only "
                        + disagreements + " disagreements against a knowingly-wrong mapping, so its"
                        + " clean result means nothing");
    }

    // ------------------------------------------------------- the branch is load-bearing

    @Test
    @DisplayName("deleting the special case yields NOT_IMPLEMENTED, not INVALID_DEFINITION")
    void theSpecialCaseIsNotRedundant() {
        PipelineErrorCode c = PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID;
        assertEquals(GieFailureKind.MISSING_GRID, productionKind(c),
                "with the branch, the file code is MISSING_GRID");
        assertEquals(GieFailureKind.NOT_IMPLEMENTED, fallbackOnly(c),
                "without it, the fallback reads rejectedByProj=false and answers NOT_IMPLEMENTED."
                        + " ExpectedFailureVerdict never scores NOT_IMPLEMENTED as genuine, so the"
                        + " seven errno-named invalid_op_file_not_found_or_invalid passes are lost"
                        + " - measured 2026-08-01 as 7378/7895 -> 7371/7888.");
        assertNotEquals(productionKind(c), fallbackOnly(c),
                "if these ever agree, the branch really has become redundant and may be deleted -"
                        + " but re-measure the gate before believing it");
    }

    // ----------------------------------------------------------- end to end, via the corpus

    @Test
    @DisplayName("tinshift.gie:19 (+file=proj.ini) reaches MISSING_GRID through the real factory")
    void tinshiftProjIniIsMissingGrid() {
        // gie/tinshift.gie:19
        //   operation   +proj=tinshift +file=proj.ini
        //   expect failure errno invalid_op_file_not_found_or_invalid
        // This is the witness quoted in pipelineKind's javadoc: it is a genuine pass only because
        // the bridge answers MISSING_GRID and the row names that errno.
        GieOperation o = new Proj4jGieOperationFactory().create("proj=tinshift file=proj.ini");
        assertFalse(o.isUsable(), "proj4j has no tinshift JSON reader, so this cannot be built");
        assertNotNull(o.failure());
        assertEquals(GieFailureKind.MISSING_GRID, o.failure().kind(),
                "actual message: " + o.failure().message());
    }

    @Test
    @DisplayName("discrimination: a missing required argument is still INVALID_DEFINITION")
    void aMissingArgumentIsStillAnUpstreamRejection() {
        // gie/tinshift.gie:11 - `operation +proj=tinshift` with no +file, which PROJ itself
        // refuses (invalid_op_missing_arg). If this came back MISSING_GRID too, the classifier
        // would not be discriminating and the test above would prove nothing.
        GieOperation o = new Proj4jGieOperationFactory().create("proj=tinshift");
        assertFalse(o.isUsable());
        assertNotNull(o.failure());
        assertEquals(GieFailureKind.INVALID_DEFINITION, o.failure().kind(),
                "actual message: " + o.failure().message());
    }
}

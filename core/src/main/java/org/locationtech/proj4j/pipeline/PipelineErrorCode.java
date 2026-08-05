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
package org.locationtech.proj4j.pipeline;

import org.locationtech.proj4j.ErrorCause;

/**
 * The subset of PROJ 9.8.1's {@code PROJ_ERR_*} constants ({@code src/proj.h:709-766})
 * that the pipeline engine can raise at construction time.
 *
 * <p>The distinction the codes carry is the one a conformance harness needs and
 * cannot otherwise decide: <b>would PROJ have rejected this definition too?</b>
 * {@link #isRejectedByProj()} answers exactly that. A gie {@code expect failure}
 * row asserts upstream rejection, so a failure that upstream would <em>not</em>
 * have produced must never be reported as agreement.
 *
 * @since 1.5
 */
public enum PipelineErrorCode {

    /** {@code PROJ_ERR_INVALID_OP_WRONG_SYNTAX} (1025). Nested pipelines, no steps, misplaced {@code +step}. */
    WRONG_SYNTAX(1025, true, ErrorCause.INVALID_CRS_SYNTAX),

    /** {@code PROJ_ERR_INVALID_OP_MISSING_ARG} (1026). */
    MISSING_ARG(1026, true, ErrorCause.MISSING_PARAM),

    /** {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE} (1027). An unknown unit id, a bad {@code +order}. */
    ILLEGAL_ARG_VALUE(1027, true, ErrorCause.INVALID_PARAM_VALUE),

    /** {@code PROJ_ERR_INVALID_OP_MUTUALLY_EXCLUSIVE_ARGS} (1028). {@code axisswap} with both or neither of {@code +order}/{@code +axis}. */
    MUTUALLY_EXCLUSIVE_ARGS(1028, true, ErrorCause.CONTRADICTORY_PARAMS),

    /**
     * A malformed or unresolvable {@code +init=<file>:<section>} key: no colon, no such
     * init file, or no such section in it.
     *
     * <p><b>Errno 1027, not 1029.</b> {@code get_init_string} ({@code 9.8.1:src/init.cpp})
     * sets {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE} for all three of them —
     * {@code :105} "Missing colon in +init", {@code :119} "Cannot open %s",
     * {@code :134} "Invalid content for %s" — so the honest counterpart of an
     * {@code +init} key proj4j cannot resolve is the illegal-argument code, and the
     * caller-facing cause really is {@link ErrorCause#INVALID_PARAM_VALUE}: the
     * <em>parameter value</em> is what is wrong.
     *
     * <p>Kept distinct from {@link #ILLEGAL_ARG_VALUE} so a caller can tell an
     * unresolvable init key from a bad {@code +order} or unit id without parsing the
     * message; both report the same errno and the same {@link ErrorCause}.
     */
    INVALID_INIT_KEY(1027, true, ErrorCause.INVALID_PARAM_VALUE),

    /**
     * {@code PROJ_ERR_INVALID_OP_FILE_NOT_FOUND_OR_INVALID} (1029). A grid or model
     * <em>file</em> the operation needs could not be found, read, or parsed:
     * {@code +grids=}, {@code +xy_grids=}/{@code +z_grids=}, {@code tinshift}'s
     * {@code +file=} and the triangulation JSON inside it.
     *
     * <p><b>{@code rejectedByProj} is {@code false}, and that is the whole point of this
     * code.</b> proj4j failing to read a file is a statement about proj4j's readers, not
     * about the definition: the corpus follows
     * {@code +proj=hgridshift +grids=tests/test_hgrid.tif} with a coordinate assertion,
     * and PROJ reads that file perfectly well. Claiming upstream agreement here turns a
     * capability gap into apparent conformance. The caller-facing cause is
     * {@link ErrorCause#MISSING_GRID} (an <em>operation</em>-group cause), not
     * {@link ErrorCause#INVALID_PARAM_VALUE}: the {@code +grids=} value the caller wrote
     * may be perfectly valid and simply unreadable here.
     *
     * <p>PROJ raises this same 1029 when a grid genuinely is absent
     * ({@code grids.cpp}, {@code tinshift.cpp:92-127}, {@code deformation.cpp:377-391}),
     * so a gie row that <em>names</em> {@code invalid_op_file_not_found_or_invalid} is
     * still satisfied by it — the conformance runner resolves that from the corpus side,
     * where the evidence actually is.
     */
    FILE_NOT_FOUND_OR_INVALID(1029, false, ErrorCause.MISSING_GRID),

    /** {@code PROJ_ERR_OTHER_NO_INVERSE_OP} (4098). */
    NO_INVERSE_OP(4098, true, ErrorCause.NO_INVERSE_AVAILABLE),

    /**
     * Not a PROJ error at all: proj4j cannot execute a step that PROJ executes
     * perfectly well. Reporting this as an upstream rejection would turn a
     * capability gap into apparent conformance.
     */
    NOT_IMPLEMENTED_HERE(0, false, ErrorCause.PROJECTION_NOT_IMPLEMENTED);

    private final int projErrno;
    private final boolean rejectedByProj;
    private final ErrorCause errorCause;

    PipelineErrorCode(final int projErrno, final boolean rejectedByProj, final ErrorCause errorCause) {
        this.projErrno = projErrno;
        this.rejectedByProj = rejectedByProj;
        this.errorCause = errorCause;
    }

    /**
     * @return the numeric {@code PROJ_ERR_*} value, or {@code 0} when there is no
     *         PROJ counterpart.
     */
    public int projErrno() {
        return projErrno;
    }

    /**
     * @return {@code true} when PROJ 9.8.1 would refuse the same definition, so a
     *         gie {@code expect failure} row is genuinely satisfied by it.
     */
    public boolean isRejectedByProj() {
        return rejectedByProj;
    }

    /**
     * @return proj4j's own error taxonomy entry for this code; never {@code null}.
     */
    public ErrorCause errorCause() {
        return errorCause;
    }
}

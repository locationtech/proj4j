/*******************************************************************************
 * Copyright 2026 Proj4J contributors
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
package org.locationtech.proj4j;

/**
 * Signals that a CRS definition supplies two or more parameters that contradict each other, or
 * that specify the same quantity inconsistently — {@code +ellps=GRS80 +rf=300},
 * {@code +rf=298.257 +f=0.00335}, {@code +datum=NAD83 +towgs84=…}.
 *
 * <p>PROJ's equivalent is {@code PROJ_ERR_INVALID_OP_MUTUALLY_EXCLUSIVE_ARGS}. Note that PROJ
 * deliberately treats several combinations Proj4J might call contradictory as
 * <em>modifiers</em> instead — {@code ell_set.cpp} accepts {@code +ellps=GRS80 +rf=300} and lets
 * the later shape parameter win — so this exception is raised only where a definition cannot be
 * given a single coherent reading, and, for the PROJ-compatible parse mode, only where PROJ
 * itself refuses.
 *
 * <p><b>Why it extends {@link InvalidValueException}.</b> Code that already catches the natural
 * exception for a bad {@code +rf} keeps working when Proj4J starts rejecting contradictions
 * rather than silently computing a negative squared eccentricity from them. The narrower type is
 * additive: a caller that wants to distinguish "this value is out of range" from "these two
 * values disagree" can catch this class or switch on
 * {@link ErrorCause#CONTRADICTORY_PARAMS}; a caller that does not, does not have to change.
 *
 * @see ErrorCause#CONTRADICTORY_PARAMS
 * @since 1.5.0
 */
public class ContradictoryParameterException extends InvalidValueException {

    private static final long serialVersionUID = -532824264813230743L;

    /** The cause reported by every constructor. */
    private static final ErrorCause DEFAULT_CAUSE = ErrorCause.CONTRADICTORY_PARAMS;

    /**
     * Creates an exception reporting {@link ErrorCause#CONTRADICTORY_PARAMS}.
     *
     * @param message the human-readable detail message; it should name both of the parameters
     *                that disagree, and the value each implies
     */
    public ContradictoryParameterException(String message) {
        super(DEFAULT_CAUSE, message);
    }

    /**
     * Creates an exception reporting {@link ErrorCause#CONTRADICTORY_PARAMS} and wrapping another
     * throwable.
     *
     * @param message   the human-readable detail message
     * @param throwable the underlying throwable, or null
     */
    public ContradictoryParameterException(String message, Throwable throwable) {
        super(DEFAULT_CAUSE, message, throwable);
    }
}

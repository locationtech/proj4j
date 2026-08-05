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

import org.locationtech.proj4j.InvalidValueException;

/**
 * A {@code +proj=pipeline} definition could not be turned into an executable
 * operation.
 *
 * <p>Extends {@link InvalidValueException} so that every existing
 * {@code catch (Proj4jException)} and {@code catch (InvalidValueException)} still
 * fires, and carries a {@link PipelineErrorCode} so a caller can tell
 * "PROJ would reject this too" from "proj4j has not implemented it" — a
 * distinction that decides whether a conformance assertion has been demonstrated
 * or merely dodged.
 *
 * @since 1.5
 */
public class PipelineDefinitionException extends InvalidValueException {

    private static final long serialVersionUID = 1L;

    private final PipelineErrorCode code;

    /**
     * @param code    what kind of rejection this is
     * @param message a human-readable reason
     */
    public PipelineDefinitionException(final PipelineErrorCode code, final String message) {
        super(code.errorCause(), message);
        this.code = code;
    }

    /**
     * @param code    what kind of rejection this is
     * @param message a human-readable reason
     * @param cause   the underlying failure
     */
    public PipelineDefinitionException(final PipelineErrorCode code, final String message,
            final Throwable cause) {
        super(code.errorCause(), message, cause);
        this.code = code;
    }

    /**
     * @return the classification; never {@code null}.
     */
    public PipelineErrorCode code() {
        return code;
    }

    /**
     * @return {@code true} when PROJ 9.8.1 would refuse this definition as well.
     * @see PipelineErrorCode#isRejectedByProj()
     */
    public boolean isRejectedByProj() {
        return code.isRejectedByProj();
    }
}

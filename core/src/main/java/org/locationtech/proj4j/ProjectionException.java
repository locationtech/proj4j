/*******************************************************************************
 * Copyright 2006, 2017 Jerry Huxtable, Martin Davis
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

import org.locationtech.proj4j.proj.Projection;

/**
 * Signals that an erroneous situation has
 * occured during the computation of
 * a projected coordinate system value.
 * <p>
 * {@link #cause()} is {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN}: historically this is the
 * exception the projection kernels throw when a coordinate lies outside the domain on which the
 * projection is defined. Where the real reason is a failed iteration rather than an
 * out-of-domain input, throw {@link ConvergenceFailureException} instead, or pass
 * {@link ErrorCause#NUMERICAL_FAILURE} explicitly.
 *
 * @author mbdavis
 */
public class ProjectionException extends CrsTransformException {

    private static final long serialVersionUID = 5072367677711578814L;

    /** The cause reported by every constructor that does not take one explicitly. */
    private static final ErrorCause DEFAULT_CAUSE = ErrorCause.COORDINATE_OUT_OF_DOMAIN;

    /**
     * The message text for PROJ.4 error 17, carried over from the C original. Public and mutable
     * for historical reasons; a non-convergent iteration should now throw
     * {@link ConvergenceFailureException} instead.
     */
    public static String ERR_17 = "non-convergent inverse meridinal dist";

    /**
     * Creates an exception with no message, reporting
     * {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN}.
     */
    public ProjectionException() {
        super(DEFAULT_CAUSE, null);
    }

    /**
     * Creates an exception reporting {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN}.
     *
     * @param message the human-readable detail message
     */
    public ProjectionException(String message) {
        super(DEFAULT_CAUSE, message);
    }

    /**
     * Creates an exception reporting {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN}, with the
     * projection named in the message.
     *
     * @param proj    the projection that rejected the coordinate; its
     *                {@link Projection#toString()} is prepended to the message
     * @param message the human-readable detail message
     */
    public ProjectionException(Projection proj, String message) {
        this(proj.toString() + ": " + message);
    }

    /**
     * Creates an exception with a narrower cause than
     * {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN}.
     *
     * @param cause   a refinement of {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN}
     * @param message the human-readable detail message
     * @since 1.5.0
     */
    public ProjectionException(ErrorCause cause, String message) {
        super(cause, message);
    }

    /**
     * Creates an exception with a narrower cause than
     * {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN}, with the projection named in the message.
     *
     * @param cause   a refinement of {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN}
     * @param proj    the projection that rejected the coordinate; its
     *                {@link Projection#toString()} is prepended to the message
     * @param message the human-readable detail message
     * @since 1.5.0
     */
    public ProjectionException(ErrorCause cause, Projection proj, String message) {
        super(cause, proj.toString() + ": " + message);
    }
}

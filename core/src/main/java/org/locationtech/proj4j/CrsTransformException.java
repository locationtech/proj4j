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
 * The single exception type a caller has to catch to handle every Proj4J failure that has been
 * attributed to a {@link ErrorCause}.
 *
 * <p>This is the type named by the fail-closed contract:
 *
 * <blockquote>
 * For every method that produces coordinates, exactly one of two things happens: <b>(1)</b> it
 * returns normally, and every ordinate meaningful for the target CRS is finite and was produced
 * by an operation that actually applied every step it declared; or <b>(2)</b> it throws
 * {@code CrsTransformException} with a non-null {@link #cause()}. There is no sentinel.
 * {@code NaN}, {@code ±Infinity}, the input coordinate, and the projection's false
 * easting/northing are never used to signal failure.
 * </blockquote>
 *
 * <p>It sits between {@link Proj4jException} and the legacy exception classes, so
 * {@code catch (Proj4jException)} keeps working unchanged while new code can write:
 *
 * <pre>{@code
 * try {
 *     op.transform(src, dst);
 * } catch (CrsTransformException e) {
 *     switch (e.cause()) {
 *         case COORDINATE_OUT_OF_DOMAIN: ...
 *         default: ...            // required: ErrorCause may gain constants
 *     }
 * }
 * }</pre>
 *
 * <p>Because the legacy classes are its subtypes, that {@code switch} also sees legacy throws —
 * they simply report a coarser {@link ErrorCause}.
 *
 * <p>Unlike {@link Proj4jException}, whose {@link #cause()} defaults to
 * {@link ErrorCause#INTERNAL_ERROR}, every constructor here requires an explicit cause. That is
 * the mechanical reason the contract above can promise a meaningful {@code cause()}.
 *
 * @see ErrorCause
 * @since 1.5.0
 */
public class CrsTransformException extends Proj4jException {

    private static final long serialVersionUID = 1832822012296106468L;

    /**
     * Creates an exception with an explicit cause, which this class always requires.
     *
     * @param cause   the machine-readable reason; required
     * @param message the human-readable detail message
     */
    public CrsTransformException(ErrorCause cause, String message) {
        super(cause, message, null);
    }

    /**
     * Creates an exception with an explicit cause, wrapping another throwable.
     *
     * @param cause     the machine-readable reason; required
     * @param message   the human-readable detail message
     * @param throwable the underlying throwable, or null
     */
    public CrsTransformException(ErrorCause cause, String message, Throwable throwable) {
        super(cause, message, throwable);
    }
}

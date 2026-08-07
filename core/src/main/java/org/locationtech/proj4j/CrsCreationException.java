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
 * Signals that a CRS, or a coordinate operation between two CRSs, could not be created. Thrown
 * at construction or planning time — never per coordinate.
 *
 * <p>This is the distinction the fail-closed design turns on: a definition that cannot produce
 * correct coordinates must fail while it is still a <em>definition</em>. A physically impossible
 * ellipsoid never becomes a {@code Projection}, so no coordinate can ever be produced from it,
 * and a rejected operation fails at planning time rather than on row 4,000,000.
 *
 * <p>It extends {@link CrsTransformException} rather than sitting beside it so that a caller who
 * only wants "did anything go wrong in the Proj4J call I just made" needs exactly one catch
 * clause. The {@link #cause()} distinguishes the cases: {@link ErrorCause#isCrsError()} for a
 * bad definition, {@link ErrorCause#isOperationError()} for a definition that is fine but for
 * which no usable operation exists.
 *
 * @see ErrorCause#UNKNOWN_CRS
 * @see ErrorCause#BALLPARK_REJECTED
 * @since 1.5.0
 */
public class CrsCreationException extends CrsTransformException {

    private static final long serialVersionUID = 8952705490035737960L;

    /**
     * Creates an exception with an explicit cause.
     *
     * @param cause   the machine-readable reason; required
     * @param message the human-readable detail message
     */
    public CrsCreationException(ErrorCause cause, String message) {
        super(cause, message);
    }

    /**
     * Creates an exception with an explicit cause, wrapping another throwable.
     *
     * @param cause     the machine-readable reason; required
     * @param message   the human-readable detail message
     * @param throwable the underlying throwable, or null
     */
    public CrsCreationException(ErrorCause cause, String message, Throwable throwable) {
        super(cause, message, throwable);
    }
}

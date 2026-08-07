/*******************************************************************************
 * Copyright 2009, 2017 Martin Davis
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
 * Signals that an authority code is unknown
 * and cannot be mapped to a CRS definition.
 * <p>
 * {@link #cause()} is {@link ErrorCause#UNKNOWN_CRS}.
 *
 * @author mbdavis
 */
public class UnknownAuthorityCodeException extends CrsCreationException {

    private static final long serialVersionUID = -8058065898082079043L;

    /** The cause reported by every constructor that does not take one explicitly. */
    private static final ErrorCause DEFAULT_CAUSE = ErrorCause.UNKNOWN_CRS;

    /**
     * Creates an exception reporting {@link ErrorCause#UNKNOWN_CRS}.
     *
     * @param message the human-readable detail message; it should name the code that was not found
     */
    public UnknownAuthorityCodeException(String message) {
        super(DEFAULT_CAUSE, message);
    }

    /**
     * Creates an exception with a narrower cause than {@link ErrorCause#UNKNOWN_CRS}.
     *
     * @param cause   a refinement of {@link ErrorCause#UNKNOWN_CRS}
     * @param message the human-readable detail message
     * @since 1.5.0
     */
    public UnknownAuthorityCodeException(ErrorCause cause, String message) {
        super(cause, message);
    }
}

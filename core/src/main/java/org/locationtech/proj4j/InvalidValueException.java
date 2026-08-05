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
 * Signals that a parameter or computed internal variable
 * has a value which lies outside the
 * allowable bounds for the computation in which it is being used.
 * <p>
 * {@link #cause()} is {@link ErrorCause#INVALID_PARAM_VALUE}, refined to
 * {@link ErrorCause#CONTRADICTORY_PARAMS} by the subclass
 * {@link ContradictoryParameterException}.
 *
 * @author mbdavis
 *
 */
public class InvalidValueException extends CrsCreationException {

	private static final long serialVersionUID = -5817463739437408095L;

	/** The cause reported by every constructor that does not take one explicitly. */
	private static final ErrorCause DEFAULT_CAUSE = ErrorCause.INVALID_PARAM_VALUE;

	public InvalidValueException(String message) {
		super(DEFAULT_CAUSE, message);
	}

	public InvalidValueException(String message, Exception cause) {
		super(DEFAULT_CAUSE, message, cause);
	}

	/**
	 * @param cause   a refinement of {@link ErrorCause#INVALID_PARAM_VALUE}
	 * @param message the human-readable detail message
	 * @since 1.5.0
	 */
	public InvalidValueException(ErrorCause cause, String message) {
		super(cause, message);
	}

	/**
	 * @param cause     a refinement of {@link ErrorCause#INVALID_PARAM_VALUE}
	 * @param message   the human-readable detail message
	 * @param throwable the underlying throwable, or null
	 * @since 1.5.0
	 */
	public InvalidValueException(ErrorCause cause, String message, Throwable throwable) {
		super(cause, message, throwable);
	}
}

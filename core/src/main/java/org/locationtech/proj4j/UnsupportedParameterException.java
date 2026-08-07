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
 * Signals that a parameter in a CRS specification
 * is not currently supported, or unknown.
 * <p>
 * {@link #cause()} is {@link ErrorCause#PROJECTION_NOT_IMPLEMENTED}: this exception marks a
 * Proj4J capability boundary, not a defect in the definition. PROJ has no parameter allow-list,
 * so a definition Proj4J rejects here is usually one PROJ accepts and ignores.
 *
 * @author mbdavis
 *
 */
public class UnsupportedParameterException extends CrsCreationException
{

	private static final long serialVersionUID = -1273232907179554713L;

	/** The cause reported by every constructor that does not take one explicitly. */
	private static final ErrorCause DEFAULT_CAUSE = ErrorCause.PROJECTION_NOT_IMPLEMENTED;

	/**
	 * Creates an exception reporting {@link ErrorCause#PROJECTION_NOT_IMPLEMENTED}.
	 *
	 * @param message the human-readable detail message; it should name the unsupported parameter
	 */
	public UnsupportedParameterException(String message) {
		super(DEFAULT_CAUSE, message);
	}

	/**
	 * Creates an exception with a narrower cause than
	 * {@link ErrorCause#PROJECTION_NOT_IMPLEMENTED}.
	 *
	 * @param cause   a refinement of {@link ErrorCause#PROJECTION_NOT_IMPLEMENTED}
	 * @param message the human-readable detail message
	 * @since 1.5.0
	 */
	public UnsupportedParameterException(ErrorCause cause, String message) {
		super(cause, message);
	}

	/**
	 * Creates an exception with a narrower cause than
	 * {@link ErrorCause#PROJECTION_NOT_IMPLEMENTED}, wrapping another throwable.
	 *
	 * @param cause     a refinement of {@link ErrorCause#PROJECTION_NOT_IMPLEMENTED}
	 * @param message   the human-readable detail message
	 * @param throwable the underlying throwable, or null
	 * @since 1.5.0
	 */
	public UnsupportedParameterException(ErrorCause cause, String message, Throwable throwable) {
		super(cause, message, throwable);
	}
}

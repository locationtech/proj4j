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
 * 
 * @author mbdavis
 *
 */
public class InvalidValueException extends Proj4jException {

	public InvalidValueException(String message) {
		super(message);
	}

	public InvalidValueException(String message, Exception cause) {
		super(message, cause);
	}
}

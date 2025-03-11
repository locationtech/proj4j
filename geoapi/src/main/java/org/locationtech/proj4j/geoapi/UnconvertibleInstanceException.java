/*
 * Copyright 2025, PROJ4J contributors
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
package org.locationtech.proj4j.geoapi;

import org.locationtech.proj4j.Proj4jException;


/**
 * Thrown by {@code Wrapper.proj4j(…)} when a GeoAPI object cannot be unwrapped or copied to a PROJ4J implementation.
 * This exception is never thrown when the given GeoAPI object has been created by a {@code Wrapper.geoapi(…)} method.
 * This exception may be thrown for GeoAPI objects created by other libraries, depending on the characteristics of the
 * object. For example, it may depend on whether the coordinate system uses unsupported axis directions.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
@SuppressWarnings("serial")
public class UnconvertibleInstanceException extends Proj4jException {
    /**
     * Creates a new exception with no message.
     */
    public UnconvertibleInstanceException() {
    }

    /**
     * Creates a new exception with the given message.
     *
     * @param message the exception message, or {@code null}
     */
    public UnconvertibleInstanceException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with a message built for the given object name and type.
     *
     * @param  name  the name of the object that cannot be unwrapped
     * @param  type  the type of the object that cannot be unwrapped
     */
    UnconvertibleInstanceException(String name, String type) {
        super("Cannot unwrap the \"" + name + "\" " + type + " as a PROJ4J implementation.");
    }
}

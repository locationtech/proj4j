/*
 * Copyright 2026, PROJ4J contributors
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
package org.locationtech.proj4j.io.wkt;

import org.locationtech.proj4j.Proj4jException;

/**
 * Thrown when a WKT or PROJJSON definition cannot be parsed, or can be parsed but does not
 * describe a coordinate reference system this library can represent.
 * <p>
 * This is the analogue of PROJ's {@code ParsingException}. It is unchecked, like every other
 * exception in this library.
 */
public class WktParseException extends Proj4jException {

    private static final long serialVersionUID = 1L;

    public WktParseException(String message) {
        super(message);
    }

    public WktParseException(String message, Exception cause) {
        super(message, cause);
    }
}

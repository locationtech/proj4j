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
 *******************************************************************************/
package org.locationtech.proj4j.datum.tiff;

import java.io.IOException;

/**
 * Thrown when a TIFF is well-formed but uses a feature this reader deliberately does not implement.
 *
 * <p>This is <strong>not</strong> a general TIFF decoder: it reads the documented geodetic-grid
 * subset described in PROJ's {@code docs/source/specifications/geodetictiffgrids.rst} and read by
 * {@code 9.8.1:src/grids.cpp}. Anything outside that subset — a palette image, JPEG or LZW
 * compression, 1-bit or 8-bit samples, a rotated {@code GeoTransformationMatrix} — must produce an
 * exception <em>naming the feature</em>, never a plausible-looking coordinate. A separate type exists
 * so a caller can tell "this file needs something we do not have" from "these bytes are corrupt".
 *
 * @since 1.5
 */
public class UnsupportedTiffException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message must name the unsupported feature and its value
     */
    public UnsupportedTiffException(String message) {
        super(message);
    }
}

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

import java.util.Collections;
import java.util.Set;
import org.opengis.referencing.ReferenceIdentifier;


/**
 * A simple EPSG identifier made of only a code and a code space.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
final class IdentifierEPSG extends Wrapper {
    /**
     * The EPSG code.
     */
    private final int code;

    /**
     * Creates a new identifier for the given EPSG code.
     */
    private IdentifierEPSG(final int code) {
        this.code = code;
    }

    /**
     * Wraps the given EPSG code.
     *
     * @param  code the EPSG code, or 0 if none
     * @return the wrapper, or an empty set if the given EPSG code was 0
     */
    static Set<ReferenceIdentifier> wrap(final int code) {
        return (code != 0) ? Collections.singleton(new IdentifierEPSG(code)) : Collections.emptySet();
    }

    /**
     * {@return the EPSG code}.
     */
    @Override
    Object implementation() {
        return code;
    }

    /**
     * {@return the code space, which is fixed to "EPSG"}.
     */
    @Override
    public String getCodeSpace() {
        return "EPSG";
    }

    /**
     * {@return the string representation of the EPSG code}.
     */
    @Override
    public String getCode() {
        return Integer.toString(code);
    }

    /**
     * {@return the string representation of this identifier}.
     */
    @Override
    public String toString() {
        return getCodeSpace() + ':' + code;
    }
}

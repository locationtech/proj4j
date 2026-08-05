/*
 * Copyright 2026 The Proj4J Contributors.
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
package org.locationtech.proj4j.vertical;

import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.UnknownAuthorityCodeException;

/**
 * A compound CRS named a vertical code this library does not know.
 *
 * <p>Deliberately an {@link UnknownAuthorityCodeException}, so that every existing
 * {@code catch (UnknownAuthorityCodeException)} and {@code catch (Proj4jException)} around
 * {@code CRSFactory.createFromName} keeps firing for a compound name too. The alternative —
 * a new top-level exception type — would make {@code EPSG:4326+5773} fail in a way no
 * existing caller catches, which is the same class of surprise as returning a fabricated
 * height.
 *
 * @since 1.5
 */
public class UnknownVerticalCrsException extends UnknownAuthorityCodeException {

    private static final long serialVersionUID = 1L;

    private final String identifier;

    /**
     * @param identifier the {@code AUTH:CODE} that could not be resolved
     * @param message    what is missing and where it would have to come from
     */
    public UnknownVerticalCrsException(final String identifier, final String message) {
        super(ErrorCause.UNKNOWN_CRS, message);
        this.identifier = identifier;
    }

    /** @return the {@code AUTH:CODE} that could not be resolved. */
    public String getIdentifier() {
        return identifier;
    }
}

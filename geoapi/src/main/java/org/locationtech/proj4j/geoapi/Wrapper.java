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

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import org.opengis.metadata.citation.Citation;
import org.opengis.metadata.extent.Extent;
import org.opengis.referencing.IdentifiedObject;
import org.opengis.referencing.ReferenceIdentifier;
import org.opengis.util.GenericName;
import org.opengis.util.InternationalString;


/**
 * Base class for wrappers around PROJ4J implementations.
 * Subclasses should return the object name in the {@link #getCode()} method.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
abstract class Wrapper implements IdentifiedObject, ReferenceIdentifier {
    /**
     * Creates a new wrapper.
     */
    Wrapper() {
    }

    /**
     * {@return the wrapped implementation}.
     */
    abstract Object implementation();

    /**
     * {@return the authority that defines this object}.
     * The default implementation assumes that there is none.
     */
    @Override
    public Citation getAuthority() {
        return null;
    }

    /**
     * {@return a short name of the authority used as a code space}.
     * The default implementation returns "PROJ4J" on the assumption that the names are specific to PROJ4J.
     * This is not completely true since those names are often derived from EPSG, but we don't really have
     * a guarantee that they are exact or that PROJ4J didn't added their own definitions.
     */
    @Override
    public String getCodeSpace() {
        return "PROJ4J";
    }

    /**
     * {@return the version of the defined object}.
     * The default implementation assumes that there is none.
     */
    @Override
    public String getVersion() {
        return null;
    }

    /**
     * {@return the primary object name}.
     * In the EPSG database, this is usually the long name.
     */
    @Override
    public abstract String getCode();

    /**
     * {@return the primary object name}. This method returns {@code this},
     * with the expectation that users will follow with {@link #getCode()}.
     * Subclasses shall return the actual object name in {@code getCode()}.
     */
    @Override
    public final ReferenceIdentifier getName() {
        return this;
    }

    /**
     * {@return other names of this object}.
     * In the EPSG database, this is usually the short name.
     * The default implementation assumes that there is none.
     */
    @Override
    public Collection<GenericName> getAlias() {
        return Collections.emptyList();
    }

    /**
     * {@return all identifiers (usually EPSG codes) of this object}.
     * The default implementation assumes that there is none.
     */
    @Override
    public Set<ReferenceIdentifier> getIdentifiers() {
        return Collections.emptySet();
    }

    /**
     * {@return the scope of usage of this object}.
     * If unknown, ISO 19111 requires that we return "not known".
     */
    public InternationalString getScope() {
        return LocalizedString.UNKNOWN;
    }

    /**
     * {@return the domain of validity of this object}.
     * The default implementation assumes that there is none.
     */
    public Extent getDomainOfValidity() {
        return null;
    }

    /**
     * {@return optional remarks about this object}.
     * The default implementation assumes that there is none.
     */
    @Override
    public InternationalString getRemarks() {
        return null;
    }

    /**
     * {@return a WKT representation of this object}.
     * The default implementation assumes that there is none.
     */
    @Override
    public String toWKT() throws UnsupportedOperationException {
        throw new UnsupportedOperationException("Not supported.");
    }

    /**
     * {@return the string representation of the wrapped PROJ4J object}.
     */
    @Override
    public final String toString() {
        return implementation().toString();
    }

    /**
     * {@return a hash code value for this wrapper}.
     */
    @Override
    public final int hashCode() {
        return implementation().hashCode() ^ getClass().hashCode();
    }

    /**
     * Compares this wrapper with the given object for equality. This method returns {@code true}
     * if the two objects are wrappers of the same class wrapping equal PROJ4 implementations.
     */
    @Override
    public final boolean equals(final Object other) {
        if (other != null && other.getClass() == getClass()) {
            return implementation().equals(((Wrapper) other).implementation());
        }
        return false;
    }
}

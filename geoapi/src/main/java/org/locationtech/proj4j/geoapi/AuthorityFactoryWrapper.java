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

import java.io.Serializable;
import java.util.Set;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.UnknownAuthorityCodeException;
import org.opengis.metadata.citation.Citation;
import org.opengis.referencing.IdentifiedObject;
import org.opengis.referencing.NoSuchAuthorityCodeException;
import org.opengis.referencing.crs.*;
import org.opengis.util.FactoryException;
import org.opengis.util.InternationalString;


/**
 * Wraps a PROJ4J implementation behind the equivalent GeoAPI interface.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
@SuppressWarnings("serial")
final class AuthorityFactoryWrapper extends Wrapper implements CRSAuthorityFactory, Serializable {
    /**
     * The wrapped PROJ4 implementation.
     */
    final CRSFactory impl;

    /**
     * Creates a new wrapper for the given PROJ4J implementation.
     */
    private AuthorityFactoryWrapper(final CRSFactory impl) {
        this.impl = impl;
    }

    /**
     * Wraps the given implementation.
     *
     * @param  impl the implementation to wrap, or {@code null}
     * @return the wrapper, or {@code null} if the given implementation was null
     */
    static AuthorityFactoryWrapper wrap(final CRSFactory impl) {
        return (impl != null) ? new AuthorityFactoryWrapper(impl) : null;
    }

    /**
     * {@return the PROJ4J backing implementation}.
     */
    @Override
    Object implementation() {
        return impl;
    }

    /**
     * {@return the factory name}.
     */
    @Override
    public String getCode() {
        return "PROJ4J";
    }

    /**
     * {@return an identification of the softwware that provides the CRS definitions}.
     * This is not the authority (EPSG, ESRI, <i>etc</i>).
     */
    @Override
    public Citation getVendor() {
        return SimpleCitation.PROJ4J;
    }

    /**
     * Returns the name of the CRS for the given code. Usually, this method is for fetching the name without the
     * cost of creating the full <abbr>CRS</abbr>. However, this implementation is inefficient in this regard.
     */
    @Override
    public InternationalString getDescriptionText(String code) throws FactoryException {
        return LocalizedString.wrap(createCoordinateReferenceSystem(code).getName().getCode());
    }

    /**
     * Generic method defined in parent interface.
     */
    @Override
    public IdentifiedObject createObject(String code) throws FactoryException {
        return createCoordinateReferenceSystem(code);
    }

    /**
     * Creates a CRS from a code in the {@code "AUTHORITY:CODE"} syntax.
     * If the authority is unspecified, then {@code "EPSG"} is assumed.
     *
     * @param  code  the authority (optional) and code of the CRS to create
     * @return the CRS for the given code
     * @throws FactoryException if the CRS cannot be created
     */
    @Override
    public CoordinateReferenceSystem createCoordinateReferenceSystem(String code) throws FactoryException {
        try {
            return AbstractCRS.wrap(impl.createFromName(code), false);
        } catch (UnknownAuthorityCodeException e) {
            final int s = code.indexOf(':');
            throw (NoSuchAuthorityCodeException) new NoSuchAuthorityCodeException(
                    "No registered CRS for \"" + code + "\".",
                    (s >= 0) ? code.substring(0, s).trim() : null,
                    (s >= 0) ? code.substring(s).trim() : code, code).initCause(e);
        } catch (Proj4jException e) {
            throw new FactoryException("Cannot create a CRS for \"" + code + "\".", e);
        }
    }

    /**
     * Creates the CRS from the specified code and cast to a geographic CRS.
     *
     * @param  code  the authority (optional) and code of the CRS to create
     * @return the CRS for the given code
     * @throws FactoryException if the CRS cannot be created or is not geographic
     */
    @Override
    public GeographicCRS createGeographicCRS(String code) throws FactoryException {
        try {
            return (GeographicCRS) createCoordinateReferenceSystem(code);
        } catch (ClassCastException e) {
            throw new FactoryException("The CRS identified by \"" + code + "\" is not geographic.", e);
        }
    }

    /**
     * Creates the CRS from the specified code and cast to a projected CRS.
     *
     * @param  code  the authority (optional) and code of the CRS to create
     * @return the CRS for the given code
     * @throws FactoryException if the CRS cannot be created or is not projected
     */
    @Override
    public ProjectedCRS createProjectedCRS(String code) throws FactoryException {
        try {
            return (ProjectedCRS) createCoordinateReferenceSystem(code);
        } catch (ClassCastException e) {
            throw new FactoryException("The CRS identified by \"" + code + "\" is not projected.", e);
        }
    }

    @Override
    public GeocentricCRS createGeocentricCRS(String code) throws NoSuchAuthorityCodeException, FactoryException {
        throw new FactoryException("Not implemented.");
    }

    @Override
    public VerticalCRS createVerticalCRS(String code) throws NoSuchAuthorityCodeException, FactoryException {
        throw new FactoryException("Not implemented.");
    }

    @Override
    public TemporalCRS createTemporalCRS(String code) throws NoSuchAuthorityCodeException, FactoryException {
        throw new FactoryException("Not implemented.");
    }

    @Override
    public EngineeringCRS createEngineeringCRS(String code) throws NoSuchAuthorityCodeException, FactoryException {
        throw new FactoryException("Not implemented.");
    }

    @Override
    public ImageCRS createImageCRS(String code) throws NoSuchAuthorityCodeException, FactoryException {
        throw new FactoryException("Not implemented.");
    }

    @Override
    public DerivedCRS createDerivedCRS(String code) throws NoSuchAuthorityCodeException, FactoryException {
        throw new FactoryException("Not implemented.");
    }

    @Override
    public CompoundCRS createCompoundCRS(String code) throws FactoryException {
        throw new FactoryException("Not implemented.");
    }

    @Override
    public Set<String> getAuthorityCodes(Class<? extends IdentifiedObject> type) throws FactoryException {
        throw new FactoryException("Not implemented.");
    }
}

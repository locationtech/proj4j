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
package org.locationtech.proj4j.geoapi.spi;

import java.util.Set;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.geoapi.Wrappers;
import org.opengis.metadata.citation.Citation;
import org.opengis.referencing.IdentifiedObject;
import org.opengis.referencing.crs.*;
import org.opengis.util.FactoryException;
import org.opengis.util.InternationalString;


/**
 * Registers PROJ4J wrappers as a <abbr>CRS</abbr> authority factory.
 *
 * <h4>Future evolution</h4>
 * In a future version, it may not be possible anymore to instantiate this class.
 * For now, we have to allow instantiation for compatibility with Java 8 services.
 * If a future version of PROJ4J migrates to Java 9 module system, the only way to
 * get the factory will by invoking the {@link #provider()} static method.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
public final class AuthorityFactory implements CRSAuthorityFactory {
    /**
     * Where to delegate all operations.
     */
    private final CRSAuthorityFactory proxy;

    /**
     * Creates a new instance.
     * <b>WARNING:</b> this constructor may not be accessible anymore in a future version.
     * Do not invoke directly.
     */
    public AuthorityFactory() {
        proxy = provider();
    }

    /**
     * {@return the factory backed by PROJ4J}.
     */
    public static CRSAuthorityFactory provider() {
        return Wrappers.geoapi(new CRSFactory());
    }

    @Override
    public Citation getVendor() {
        return proxy.getVendor();
    }

    @Override
    public CoordinateReferenceSystem createCoordinateReferenceSystem(String code) throws FactoryException {
        return proxy.createCoordinateReferenceSystem(code);
    }

    @Override
    public CompoundCRS createCompoundCRS(String code) throws FactoryException {
        return proxy.createCompoundCRS(code);
    }

    @Override
    public DerivedCRS createDerivedCRS(String code) throws FactoryException {
        return proxy.createDerivedCRS(code);
    }

    @Override
    public EngineeringCRS createEngineeringCRS(String code) throws FactoryException {
        return proxy.createEngineeringCRS(code);
    }

    @Override
    public GeographicCRS createGeographicCRS(String code) throws FactoryException {
        return proxy.createGeographicCRS(code);
    }

    @Override
    public GeocentricCRS createGeocentricCRS(String code) throws FactoryException {
        return proxy.createGeocentricCRS(code);
    }

    @Override
    public ImageCRS createImageCRS(String code) throws FactoryException {
        return proxy.createImageCRS(code);
    }

    @Override
    public ProjectedCRS createProjectedCRS(String code) throws FactoryException {
        return proxy.createProjectedCRS(code);
    }

    @Override
    public TemporalCRS createTemporalCRS(String code) throws FactoryException {
        return proxy.createTemporalCRS(code);
    }

    @Override
    public VerticalCRS createVerticalCRS(String code) throws FactoryException {
        return proxy.createVerticalCRS(code);
    }

    @Override
    public Citation getAuthority() {
        return proxy.getAuthority();
    }

    @Override
    public Set<String> getAuthorityCodes(Class<? extends IdentifiedObject> type) throws FactoryException {
        return proxy.getAuthorityCodes(type);
    }

    @Override
    public InternationalString getDescriptionText(String code) throws FactoryException {
        return proxy.getDescriptionText(code);
    }

    @Override
    public IdentifiedObject createObject(String code) throws FactoryException {
        return proxy.createObject(code);
    }
}

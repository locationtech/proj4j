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

import org.locationtech.proj4j.geoapi.spi.AuthorityFactory;
import org.locationtech.proj4j.geoapi.spi.OperationFactory;
import org.opengis.referencing.NoSuchAuthorityCodeException;
import org.opengis.referencing.crs.CRSAuthorityFactory;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.CoordinateOperation;
import org.opengis.referencing.operation.CoordinateOperationFactory;
import org.opengis.util.FactoryException;


/**
 * Default implementations of referencing services backed by PROJ4J.
 * Those services are accessible by {@link java.util.ServiceLoader},
 * which should be used by implementation-neutral applications.
 * This class provides shortcuts for the convenience of applications
 * that do not need implementation neutrality.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
public final class Services {
    /**
     * Do not allows instantiation of this class.
     */
    private Services() {
    }

    /**
     * {@return the singleton factory for creating CRS from authority codes}.
     */
    public static CRSAuthorityFactory getAuthorityFactory() {
        return AuthorityFactory.provider();
    }

    /**
     * {@return the singleton factory for creating coordinate operations between a pair of CRS}.
     */
    public static CoordinateOperationFactory getOperationFactory() {
        return OperationFactory.provider();
    }

    /**
     * Creates a coordinate reference system from the given authority code.
     * The argument should be of the form {@code "AUTHORITY:CODE"}.
     * If the authority is unspecified, then {@code "EPSG"} is assumed.
     *
     * @param  code  the authority code
     * @return coordinate reference system for the given code
     * @throws NoSuchAuthorityCodeException if the specified {@code code} was not found
     * @throws FactoryException if the object creation failed for some other reason
     */
    public static CoordinateReferenceSystem createCRS(final String code) throws FactoryException {
        return getAuthorityFactory().createCoordinateReferenceSystem(code);
    }

    /**
     * Creates a coordinate operation between the given pair of coordinate reference systems.
     *
     * @param  source  input coordinate reference system
     * @param  target  output coordinate reference system
     * @return a coordinate operation from {@code source} to {@code target}
     * @throws FactoryException if the coordinate operation cannot be created
     */
    public static CoordinateOperation findOperation(CoordinateReferenceSystem source, CoordinateReferenceSystem target)
            throws FactoryException
    {
        return getOperationFactory().createOperation(source, target);
    }
}

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

import java.util.Iterator;
import java.util.ServiceLoader;
import org.junit.Test;
import org.opengis.referencing.crs.CRSAuthorityFactory;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.CoordinateOperationFactory;
import org.opengis.util.FactoryException;

import static org.locationtech.proj4j.CoordinateReferenceSystem.CS_GEO;
import static org.junit.Assert.*;


/**
 * Tests fetching factory instances as services.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
public class ServicesTest {
    /**
     * Creates a new test case.
     */
    public ServicesTest() {
    }

    /**
     * Returns the factory of the given type, making sure that there is exactly one instance.
     */
    private static <F> F getSingleton(final Class<F> service) {
        Iterator<F> it = ServiceLoader.load(service).iterator();
        assertTrue(it.hasNext());
        F factory = it.next();
        assertFalse(it.hasNext());
        return factory;
    }

    /**
     * Tests the <abbr>CRS</abbr> authority factory.
     * This method only checks that the object are non-null.
     * More detailed checks are performed by {@link WrappersTest}.
     *
     * @throws FactoryException if an error occurred while creating an object.
     */
    @Test
    public void testAuthorityFactory() throws FactoryException {
        final CRSAuthorityFactory factory = getSingleton(CRSAuthorityFactory.class);
        assertNotNull(factory.createGeographicCRS("EPSG:4326"));
        assertNotNull(factory.createProjectedCRS ("EPSG:2154"));
    }

    /**
     * Tests the operation authority factory.
     * This method only checks that the object are non-null.
     *
     * @throws FactoryException if an error occurred while creating an object.
     */
    @Test
    public void testOperationFactory() throws FactoryException {
        final CoordinateOperationFactory factory = getSingleton(CoordinateOperationFactory.class);
        final CoordinateReferenceSystem crs = Wrappers.geoapi(CS_GEO, false);
        assertTrue(factory.createOperation(crs, crs).getMathTransform().isIdentity());
    }
}

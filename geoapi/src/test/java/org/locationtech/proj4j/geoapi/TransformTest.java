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

import org.junit.Test;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;
import org.opengis.test.referencing.TransformTestCase;
import org.opengis.util.FactoryException;


/**
 * Tests some coordinate operations.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
public class TransformTest extends TransformTestCase {
    /**
     * Creates a new test case.
     */
    public TransformTest() {
    }

    /**
     * Creates a transform between the given pair of coordinate reference systems.
     *
     * @param  source  authority code of the input coordinate reference system
     * @param  target  authority code of the output coordinate reference system
     * @return a coordinate operation from {@code source} to {@code target}
     * @throws FactoryException if the coordinate operation cannot be created
     */
    private static MathTransform transform(String source, String target) throws FactoryException {
        return Services.findOperation(Services.createCRS(source), Services.createCRS(target)).getMathTransform();
    }

    /**
     * Tests a projection from a geographic CRS to a projected CRS.
     *
     * @throws FactoryException if a CRS cannot be created
     * @throws TransformException if an error occurred while testing the projection of a point
     */
    @Test
    public void testProjection() throws FactoryException, TransformException {
        transform = transform("EPSG:4326", "EPSG:2154");
        tolerance = 1E-3;
        verifyTransform(new double[] {3, 46.5},             // Coordinates to test (more can be added on this line).
                        new double[] {700000, 6600000});    // Expected result.

        // Random coordinates.
        final float[] coordinates = {
            3.0f, 46.5f,
            2.5f, 43.0f,
            3.5f, 46.0f,
            4.5f, 48.0f,
            1.5f, 41.0f,
            3.8f, 43.7f,
            3.1f, 42.1f,
        };
        verifyConsistency(coordinates);
        verifyInverse(coordinates);
    }
}

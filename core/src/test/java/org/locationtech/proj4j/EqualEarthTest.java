/*******************************************************************************
 * Copyright 2026 the original author or authors.
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
package org.locationtech.proj4j;

import org.junit.Test;
import org.locationtech.proj4j.proj.Projection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class EqualEarthTest extends BaseCoordinateTransformTest {

    private static final String SPHERE = "+proj=eqearth +a=6371000 +b=6371000 +units=m";

    // Reference coordinates computed with PROJ 9.1.0.
    @Test
    public void testEquatorAndCentralMeridians() {
        checkTransformAndInverse("EPSG:4326", 0, 0, "EPSG:8857", 0, 0, 1e-6, 1e-8);
        checkTransformAndInverse("EPSG:4326", -90, 0, "EPSG:8858", 0, 0, 1e-6, 1e-8);
        checkTransformAndInverse("EPSG:4326", 150, 0, "EPSG:8859", 0, 0, 1e-6, 1e-8);
    }

    @Test
    public void testSouthernHemisphere() {
        checkTransformAndInverse("EPSG:4326", -75, -45, "EPSG:8857",
                -6163531.145414837, -5466867.760213717, 1e-6, 1e-7);
    }

    @Test
    public void testAntimeridian() {
        checkTransformAndInverse("EPSG:4326", 180, 0, "EPSG:8857",
                17243959.06221695, 0, 1e-6, 1e-8);
        checkTransformAndInverse("EPSG:4326", -180, 0, "EPSG:8857",
                -17243959.06221695, 0, 1e-6, 1e-8);
        checkTransformAndInverse("EPSG:4326", 170, 30, "EPSG:8858",
                -8965339.938608864, 3764325.42689213, 1e-6, 1e-7);
        checkTransformAndInverse("EPSG:4326", -170, -30, "EPSG:8859",
                3586135.975443543, -3764325.4268921325, 1e-6, 1e-7);
    }

    @Test
    public void testPoles() {
        Projection projection = new CRSFactory().createFromName("EPSG:8857").getProjection();
        checkProjectionAndInverse(projection, 30, 90, 1702745.7989095072, 8392927.598466456);
        checkProjectionAndInverse(projection, -60, -90, -3405491.5978190177, -8392927.59846645);
    }

    @Test
    public void testSphericalProjection() {
        Projection projection = new CRSFactory().createFromParameters(null, SPHERE).getProjection();
        checkProjectionAndInverse(projection, 0, 0, 0, 0);
        checkProjectionAndInverse(projection, 10, 20, 930275.0031088601, 2553657.4610906118);
        checkProjectionAndInverse(projection, -75, -45, -6157860.844820924, -5480532.2458607275);
        checkProjectionAndInverse(projection, 30, 90, 1702743.8797029485, 8392918.13859188);
        checkProjectionAndInverse(projection, -60, -90, -3405487.759405897, -8392918.13859188);
    }

    @Test(expected = ProjectionException.class)
    public void testNorthingOutsideDomain() {
        Projection projection = new CRSFactory().createFromName("EPSG:8857").getProjection();
        projection.inverseProject(new ProjCoordinate(0, 9000000), new ProjCoordinate());
    }

    @Test(expected = ProjectionException.class)
    public void testSouthingOutsideDomain() {
        Projection projection = new CRSFactory().createFromName("EPSG:8857").getProjection();
        projection.inverseProject(new ProjCoordinate(0, -9000000), new ProjCoordinate());
    }

    @Test
    public void testProjectionProperties() {
        Projection projection = new CRSFactory().createFromName("EPSG:8857").getProjection();
        assertTrue(projection.hasInverse());
        assertTrue(projection.isEqualArea());
    }

    private void checkProjectionAndInverse(Projection projection, double lon, double lat, double x, double y) {
        ProjCoordinate projected = projection.project(new ProjCoordinate(lon, lat), new ProjCoordinate());
        assertEquals(x, projected.x, 1e-6);
        assertEquals(y, projected.y, 1e-6);
        ProjCoordinate geographic = projection.inverseProject(new ProjCoordinate(x, y), new ProjCoordinate());
        assertEquals(lon, geographic.x, 1e-8);
        assertEquals(lat, geographic.y, 2e-6);
    }
}

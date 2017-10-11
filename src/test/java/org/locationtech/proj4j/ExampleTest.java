/*******************************************************************************
 * Copyright 2009, 2017 Martin Davis
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
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

import static org.junit.Assert.assertTrue;


/**
 * Test which serves as an example of using Proj4J.
 *
 * @author mbdavis
 */
public class ExampleTest {

    @Test
    public void testTransformToGeographic() {
        assertTrue(checkTransform("EPSG:2227", -121.3128278, 37.95657778, 6327319.23, 2171792.15, 0.01));
    }

    private boolean checkTransform(String csName, double lon, double lat, double expectedX, double expectedY, double tolerance) {
        CoordinateTransformFactory ctFactory = new CoordinateTransformFactory();
        CRSFactory csFactory = new CRSFactory();
        /*
        * Create {@link CoordinateReferenceSystem} & CoordinateTransformation.
        * Normally this would be carried out once and reused for all transformations
        */
        CoordinateReferenceSystem crs = csFactory.createFromName(csName);

        final String WGS84_PARAM = "+title=long/lat:WGS84 +proj=longlat +ellps=WGS84 +datum=WGS84 +units=degrees";
        CoordinateReferenceSystem WGS84 = csFactory.createFromParameters("WGS84", WGS84_PARAM);

        CoordinateTransform trans = ctFactory.createTransform(WGS84, crs);
    
        /*
         * Create input and output points.
         * These can be constructed once per thread and reused.
         */
        ProjCoordinate p = new ProjCoordinate();
        ProjCoordinate p2 = new ProjCoordinate();
        p.x = lon;
        p.y = lat;
    
        /*
         * Transform point
         */
        trans.transform(p, p2);
        return isInTolerance(p2, expectedX, expectedY, tolerance);
    }

    @Test
    public void testExplicitTransform() {
        String csName1 = "EPSG:32636";
        String csName2 = "EPSG:4326";

        CoordinateTransformFactory ctFactory = new CoordinateTransformFactory();
        CRSFactory csFactory = new CRSFactory();
        /*
         * Create {@link CoordinateReferenceSystem} & CoordinateTransformation.
         * Normally this would be carried out once and reused for all transformations
         */
        CoordinateReferenceSystem crs1 = csFactory.createFromName(csName1);
        CoordinateReferenceSystem crs2 = csFactory.createFromName(csName2);

        CoordinateTransform trans = ctFactory.createTransform(crs1, crs2);
    
        /*
         * Create input and output points.
         * These can be constructed once per thread and reused.
         */
        ProjCoordinate p1 = new ProjCoordinate();
        ProjCoordinate p2 = new ProjCoordinate();
        p1.x = 500000;
        p1.y = 4649776.22482;
    
        /*
         * Transform point
         */
        trans.transform(p1, p2);

        assertTrue(isInTolerance(p2, 33, 42, 0.000001));
    }


    private boolean isInTolerance(ProjCoordinate p, double x, double y, double tolerance) {
        /*
         * Compare result to expected, for test purposes
         */
        double dx = Math.abs(p.x - x);
        double dy = Math.abs(p.y - y);
        boolean isInTol = dx <= tolerance && dy <= tolerance;
        return isInTol;
    }

}

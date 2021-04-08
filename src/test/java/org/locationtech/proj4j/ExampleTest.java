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

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;

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

    @Test
    public void lccToUtmBidirectionalTransform() {

        String sourceProjection = "+proj=lcc +lat_1=49 +lat_2=77 +lat_0=49 +lon_0=-95 +x_0=0 +y_0=0 +ellps=GRS80 +towgs84=0,0,0,0,0,0,0 +units=m +no_defs";
        String targetProjection = "+proj=utm +zone=13 +ellps=GRS80 +towgs84=0,0,0,0,0,0,0 +units=m +no_defs";

        CoordinateTransformFactory ctFactory = new CoordinateTransformFactory();
        CRSFactory csFactory = new CRSFactory();
        /*
         * Create {@link CoordinateReferenceSystem} & CoordinateTransformation.
         * Normally this would be carried out once and reused for all transformations
         */
        CoordinateReferenceSystem sourceCRS = csFactory.createFromParameters(null, sourceProjection);
        CoordinateReferenceSystem targetCRS = csFactory.createFromParameters(null, targetProjection);

        CoordinateTransform trans = ctFactory.createTransform(sourceCRS, targetCRS);
        CoordinateTransform inverse = ctFactory.createTransform(targetCRS, sourceCRS);

        /*
         * Create input and output points.
         * These can be constructed once per thread and reused.
         */
        ProjCoordinate p1 = new ProjCoordinate();
        ProjCoordinate p2 = new ProjCoordinate();

        p1.x = -2328478.0;
        p1.y = -732244.0;
        p2.x = 2641142.0;
        p2.y = 3898488.0;

        ProjCoordinate p1t = new ProjCoordinate();
        ProjCoordinate p2t = new ProjCoordinate();

        ProjCoordinate p1i = new ProjCoordinate();
        ProjCoordinate p2i = new ProjCoordinate();

        /*
         * Transform point
         */
        trans.transform(p1, p1t);
        trans.transform(p2, p2t);

        inverse.transform(p1t, p1i);
        inverse.transform(p2t, p2i);

        // TransverseMercator: -898112.8947364385 4366397.986532097
        // proj4js, ExtendedTransverseMercator: -898112.6757444271, 4366397.955450379
        assertTrue(isInTolerance(p1t, -898112.6757444271, 4366397.955450379, 0.000001));
        // TransverseMercator: 3168615.043479321 10060133.986247078
        // proj4js, ExtendedTransverseMercator: 3196914.503779556, 10104027.377988787
        assertTrue(isInTolerance(p2t, 3196914.503779556, 10104027.377988787, 0.000001));

        // TransverseMercator: -2328476.414958664 -732244.6234315771
        // proj4js, ExtendedTransverseMercator: -2328478.000000011, -732244.0000000233
        assertTrue(isInTolerance(p1i, p1.x, p1.y, 0.000001));
        // TransverseMercator: 0 4654175.264342441
        // proj4js, ExtendedTransverseMercator: 2641142.000000019, 3898487.999999993
        assertTrue(isInTolerance(p2i, p2.x, p2.y, 0.000001));
    }

    @Test
    public void latLonToLccBidirectionalTransform() {
        String sourceProjection = "+proj=longlat +datum=WGS84 +no_defs";
        String targetProjection = "+proj=lcc +lat_1=10.16666666666667 +lat_0=10.16666666666667 +lon_0=-71.60561777777777 +k_0=1 +x_0=0 +y_0=-52684.972 +ellps=intl +units=m +no_defs";

        CoordinateTransformFactory ctFactory = new CoordinateTransformFactory();
        CRSFactory csFactory = new CRSFactory();
        /*
         * Create {@link CoordinateReferenceSystem} & CoordinateTransformation.
         * Normally this would be carried out once and reused for all transformations
         */
        CoordinateReferenceSystem sourceCRS = csFactory.createFromParameters(null, sourceProjection);
        CoordinateReferenceSystem targetCRS = csFactory.createFromParameters(null, targetProjection);

        CoordinateTransform trans = ctFactory.createTransform(sourceCRS, targetCRS);
        CoordinateTransform inverse = ctFactory.createTransform(targetCRS, sourceCRS);

        /*
         * Create input and output points.
         * These can be constructed once per thread and reused.
         */
        ProjCoordinate p = new ProjCoordinate();

        p.x = 1;
        p.y = -1;

        ProjCoordinate pt = new ProjCoordinate();

        ProjCoordinate pi = new ProjCoordinate();

        /*
         * Transform point
         */
        trans.transform(p, pt);
        inverse.transform(pt, pi);

        assertTrue(isInTolerance(pt, 8166119.317682125, -378218.6293696874, 0.000001));
        assertTrue(isInTolerance(pi, p.x, p.y, 0.000001));
    }

    @Test
    public void latLonToStereBidirectionalTransform() {
        CRSFactory csFactory = new CRSFactory();
        CoordinateReferenceSystem STERE0 = csFactory.createFromParameters("STERE", "+proj=stere +lat_0=0.0 +lon_0=0.0 +k=1 +x_0=0 +y_0=0 +ellps=WGS84 +units=m +no_defs");
        CoordinateReferenceSystem STERE1 = csFactory.createFromParameters("STERE", "+proj=stere +lat_0=0.000001 +lon_0=0.0 +k=1 +x_0=0 +y_0=0 +ellps=WGS84 +units=m +no_defs");
        CoordinateReferenceSystem WGS84 = csFactory.createFromParameters("WGS84", "+proj=latlong +datum=WGS84 +ellps=WGS84 +no_defs");
        CoordinateTransform transformer0 = new CoordinateTransformFactory().createTransform(WGS84, STERE0);
        CoordinateTransform transformer1 = new CoordinateTransformFactory().createTransform(WGS84, STERE1);

        ProjCoordinate pc = new ProjCoordinate(1, 1);
        ProjCoordinate result0 = new ProjCoordinate();
        ProjCoordinate result1 = new ProjCoordinate();

        transformer0.transform(pc, result0);
        transformer1.transform(pc, result1);

        assertTrue(isInTolerance(result0, 111313.95106842462, 110585.61615828621, 0.000001));
        assertTrue(isInTolerance(result1, 111313.95105169504, 110585.50558411982, 0.000001));
    }

    @Test
    public void epsgWebMercatorLegacyTest() {
        CRSFactory csFactory = new CRSFactory();
        try {
            String code = csFactory.readEpsgFromParameters("+proj=merc +a=6378137 +b=6378137 +lat_ts=0.0 +lon_0=0.0 +x_0=0.0 +y_0=0 +k=1.0 +units=m +nadgrids=@null +wktext +no_defs");
            Assert.assertEquals(Integer.parseInt(code), 3857);
        } catch (IOException e) {
            e.printStackTrace();
        }
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

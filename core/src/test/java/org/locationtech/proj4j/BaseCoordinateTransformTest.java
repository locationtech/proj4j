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
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.units.Angle;

import static junit.framework.TestCase.assertTrue;


/**
 * Tests correctness and accuracy of Coordinate System transformations.
 *
 * @author Martin Davis
 */
public abstract class BaseCoordinateTransformTest {
    // ~= 1 / (2Pi * Earth radius)
    // in code: 1.0 / (2.0 * Math.PI * 6378137.0);
    static final double APPROX_METRE_IN_DEGREES = 2.0e-8;

    static boolean debug = true;

    private static CoordinateTransformTester tester = new CoordinateTransformTester(true);

    static ProjCoordinate p(String pstr) {
        String[] pord = pstr.split("\\s+");
        double p0 = Angle.parse(pord[0]);
        double p1 = Angle.parse(pord[1]);
        if (pord.length > 2) {
            double p2 = Double.parseDouble(pord[2]);
            return new ProjCoordinate(p0, p1, p2);
        }
        // TODO Auto-generated method stub
        return new ProjCoordinate(p0, p1);
    }

    void checkTransformFromWGS84(String code, double lon, double lat, double x, double y) {
        assertTrue(tester.checkTransformFromWGS84(code, lon, lat, x, y, 0.0001));
    }

    void checkTransformFromWGS84(String code, double lon, double lat, double x, double y, double tolerance) {
        assertTrue(tester.checkTransformFromWGS84(code, lon, lat, x, y, tolerance));
    }

    void checkTransformToWGS84(String code, double x, double y, double lon, double lat, double tolerance) {
        assertTrue(tester.checkTransformToWGS84(code, x, y, lon, lat, tolerance));
    }

    void checkTransformFromGeo(String code, double lon, double lat, double x, double y) {
        assertTrue(tester.checkTransformFromGeo(code, lon, lat, x, y, 0.0001));
    }

    void checkTransformFromGeo(String code, double lon, double lat, double x, double y, double tolerance) {
        assertTrue(tester.checkTransformFromGeo(code, lon, lat, x, y, tolerance));
    }

    void checkTransformToGeo(String code, double x, double y, double lon, double lat, double tolerance) {
        assertTrue(tester.checkTransformToGeo(code, x, y, lon, lat, tolerance));
    }

    void checkTransformFromAndToGeo(String code, double lon, double lat, double x, double y, double tolProj, double tolGeo) {
        assertTrue(tester.checkTransformFromGeo(code, lon, lat, x, y, tolProj));
        assertTrue(tester.checkTransformToGeo(code, x, y, lon, lat, tolGeo));
    }

    void checkTransform(
            String cs1, double x1, double y1,
            String cs2, double x2, double y2,
            double tolerance) {
        assertTrue(tester.checkTransform(cs1, x1, y1, cs2, x2, y2, tolerance));
    }

    void checkTransform(
            String cs1, ProjCoordinate p1,
            String cs2, ProjCoordinate p2,
            double tolerance) {
        assertTrue(tester.checkTransform(cs1, p1, cs2, p2, tolerance));
    }

    void checkTransformAndInverse(
            String cs1, double x1, double y1,
            String cs2, double x2, double y2,
            double tolerance,
            double inverseTolerance) {
        assertTrue(tester.checkTransform(cs1, x1, y1, cs2, x2, y2, tolerance, inverseTolerance, true));
    }

}

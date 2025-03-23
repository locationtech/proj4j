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

import junit.textui.TestRunner;
import org.junit.Test;
import org.junit.Ignore;

/**
 * Tests correctness and accuracy of Coordinate System transformations.
 *
 * @author Martin Davis
 */
public class CoordinateTransformTest extends BaseCoordinateTransformTest {

    public void testFirst() {
        checkTransformFromGeo("proj=lcc  datum=NAD83 lon_0=-100d30 lat_1=48d44 lat_2=47d26 lat_0=47 x_0=600000 y_0=0 units=us-ft", -98.76756444444445, 48.13707861111111, 2391470.474, 419526.909, 0.01);
        checkTransformAndInverse("+proj=stere +ellps=WGS84 +lon_0=21.00000000 +lat_0=52.00000000 +no_defs", 0, 0,
                "+proj=longlat +ellps=WGS84 +no_defs", 21, 52, 0.0000001, 0.000001);
        //checkTransform("EPSG:4230", 5, 58, "EPSG:2192", 764566.84, 3343948.93, 0.01 );
        //checkTransform("EPSG:4258", 5.0, 70.0,    "EPSG:3035", 4041548.12525335, 4109791.65987687, 0.1 );
    /*
    checkTransform("EPSG:4326", 3.8142776, 51.285914,    "EPSG:23031", 556878.9016076007, 5682145.166264554, 0.1 );
    checkTransformFromWGS84("+proj=sterea +lat_0=52.15616055555555 +lon_0=5.38763888888889 +k=0.9999079 +x_0=155000 +y_0=463000 +ellps=bessel +towgs84=565.237,50.0087,465.658,-0.406857,0.350733,-1.87035,4.0812 +units=m +no_defs",
        5.387638889, 52.156160556,    155029.78919920223, 463109.9541111593);
    //checkTransformFromWGS84("EPSG:3153",     -127.0, 52.11,  931625.9111828626, 789252.646454557 );
    //checkTransformToGeo("EPSG:28992",     148312.15,  457804.79,  5.29, 52.11,   0.01 );
    //checkTransformFromWGS84("EPSG:3785",     -76.640625, 49.921875,  -8531595.34908, 6432756.94421   );
  */
    }

    @Test
    public void testEPSG_27700() {
        checkTransform("EPSG:4326", -2.89, 55.4, "EPSG:27700", 343733.1404, 612144.530677, 0.1);
        checkTransformAndInverse(
                "EPSG:4326", -2.0301713578021983, 53.35168607080468,
                "EPSG:27700", 398089, 383867,
                0.001, 0.2 * APPROX_METRE_IN_DEGREES);
    }


    /**
     * Tests use of 3 param transform
     */
    @Test
    public void testEPSG_23031() {
        checkTransform("EPSG:4326", 3.8142776, 51.285914, "EPSG:23031", 556878.9016076007, 5682145.166264554, 0.1);
    }

    /**
     * Tests use of 7 param transform
     */
    @Test
    public void testAmersfoort_RD_New() {
        checkTransformFromWGS84("EPSG:28992", 5.387638889, 52.156160556, 155029.789189814, 463109.954032542, 2.0e-4);
    }

    @Test
    public void testPROJ4_SPCS_NAD27() {
        // AK 2
        checkTransform("+proj=longlat +datum=NAD27 +to_meter=0.3048006096012192", -142.0, 56.50833333333333, "ESRI:26732", 500000.000, 916085.508, 0.001);

        /*
         * EPSG:4267 is the CRS for NAD27 Geographics.
         * Even though ESRI:26732 is also NAD27,
         * the transform fails, because EPSG:4267 specifies datum transform params.
         * This causes a datum transformation to be attempted,
         * which fails because the target does not specify datum transform params
         * A more sophisticated check for datum equivalence might prevent this failure
         */
        //    checkTransform("EPSG:4267", -142.0, 56.50833333333333,    "ESRI:26732", 500000.000,    916085.508, 0.1 );
    }

    @Test
    public void testPROJ4_SPCS_NAD83() {
        checkTransform("EPSG:4269", -142.0, 56.50833333333333, "ESRI:102632", 1640416.667, 916074.825, 0.1);
        checkTransform("EPSG:4269", -146.0, 56.50833333333333, "ESRI:102633", 1640416.667, 916074.825, 0.1);
        checkTransform("EPSG:4269", -150.0, 56.50833333333333, "ESRI:102634", 1640416.667, 916074.825, 0.1);
        checkTransform("EPSG:4269", -152.48225944444445, 60.89132361111111, "ESRI:102635", 1910718.662, 2520810.68, 0.1);

        // AK 2 using us-ft
        checkTransform("EPSG:4269", -142.0, 56.50833333333333, "+proj=tmerc +datum=NAD83 +lon_0=-142 +lat_0=54 +k=.9999 +x_0=500000 +y_0=0 +units=us-ft", 1640416.667, 916074.825, 0.1);
    }

    @Test
    public void testLambertConformalConic() {
        // Landon's test pt
        checkTransformFromGeo("EPSG:2227", -121.3128278, 37.95657778, 6327319.23, 2171792.15, 0.01);

        // PROJ.4 NAD83 Test- 3301: north dakota north
        checkTransformFromGeo("proj=lcc  datum=NAD83 lon_0=-100d30 lat_1=48d44 lat_2=47d26 lat_0=47 x_0=600000 y_0=0 units=us-ft", -98.76756444444445, 48.13707861111111, 2391470.474, 419526.909, 0.01);

        // from GIGS Test Suite - seems to have a very large discrepancy
        //checkTransform("EPSG:4230", 5, 58, "EPSG:2192", 764566.84, 3343948.93, 0.01 );

    /*
     * Not sure why this one doesn't work
     *
    checkTransformFromGeo("+proj=lcc +lat_1=30.0 +lon_0=-50.0 +datum=WGS84 +units=m +no_defs",
        -123.1, 49.2166666666, -5287947.56661412, 3923289.38044914, 0.01 );
    */
    }

    // PROJ.4 #148
    public void testPconic() {
        // pconic does not currently work
        //checkTransformAndInverse("+proj=latlong +datum=WGS84", -70.4, -23.65, "+proj=pconic  +units=m +lat_1=20n +lat_2=60n +lon_0=60W +datum=WGS84", -2240096.40, -6940342.15, 2e-1, 1e-6 );
    }

    // PROJ.4 #133
    @Test
    public void testRobinson() {
        checkTransform("+proj=latlong +datum=WGS84", -30, 40, "+proj=robin +datum=WGS84", -2612095.95, 4276351.58, 2e-1);
        checkTransformFromWGS84("ESRI:54030", -30., 40., -2612095.954698802, 4276351.583838239);
        checkTransformToWGS84("ESRI:54030", -2612095.954698802, 4276351.583838239, -30., 40., 1E-4);
    }

    @Test
    public void testStereographicAzimuthal() {
        checkTransformAndInverse("EPSG:4326", 0, -75, "EPSG:3031", 0, 1638783.238407, 1e-6, 1e-6);
        checkTransformAndInverse("EPSG:4326", -57.65625, -79.21875, "EPSG:3031", -992481.633786, 628482.06328, 1e-6, 1e-6);
        checkTransformAndInverse("+proj=stere +ellps=WGS84 +lon_0=21.00000000 +lat_0=52.00000000 +no_defs", 0, 0,
                "+proj=longlat +ellps=WGS84 +no_defs", 21, 52, 1e-6, 1e-6);
    }

    @Test
    public void testUTM() {
        checkTransformFromGeo("EPSG:23030", -3, 49.95, 500000, 5533182.925903, 0.1);
        checkTransformFromWGS84("EPSG:32615", -93, 42, 500000, 4649776.22482);
        checkTransformFromWGS84("EPSG:32612", -113.109375, 60.28125, 383357.429537, 6684599.06392);
    }

    @Test
    public void testMercator() {
        // google CRS
        checkTransformFromWGS84("EPSG:3785", -76.640625, 49.921875, -8531595.34908, 6432756.94421);
    }

    @Test
    public void testSterea() {
        checkTransformToGeo("EPSG:28992", 148312.15, 457804.79, 5.29, 52.11, 0.001);
    }

    @Test
    public void testAlbersEqualArea() {
        checkTransformFromWGS84("EPSG:3005", -126.54, 54.15, 964813.103719, 1016486.305862);
        // # NAD83(CSRS) / BC Albers
        checkTransformFromWGS84("EPSG:3153", -127.0, 52.11, 931625.9111828626, 789252.646454557);
    }

    @Test
    public void testEquidistantAzimuthal() {
        checkTransformFromWGS84("ESRI:54032", 120., 40., 8995111.253396044, 8710143.05796729);
        checkTransformToWGS84("ESRI:54032", 8995111.253396044, 8710143.05796729, 120., 40., 1E-4);
    }

    @Test
    public void testLambertAzimuthalEqualArea() {
        checkTransformFromGeo("EPSG:3573", 9.84375, 61.875, 2923052.02009, 1054885.46559);
        // Proj4js
        checkTransform("EPSG:4258", 11.0, 53.0, "EPSG:3035", 4388138.60, 3321736.46, 0.1);
        checkTransformAndInverse("EPSG:4258", 11.0, 53.0, "EPSG:3035", 4388138.60, 3321736.46, 0.1, 2 * APPROX_METRE_IN_DEGREES);

        // test values from GIGS test suite - which are suspect
        // Proj4J actual values agree with PROJ4
        //checkTransform("EPSG:4258", 5.0, 50.0,    "EPSG:3035", 3892127.02, 1892578.96, 0.1 );
        //checkTransform("EPSG:4258", 5.0, 70.0,    "EPSG:3035", 4041548.12525335, 4109791.65987687, 0.1 );
    }

    @Test
    public void testSwissObliqueMercator() {
        // from PROJ.4
        checkTransformFromAndToGeo("EPSG:21781", 8.23, 46.82, 660309.34, 185586.30, 0.1, 2 * APPROX_METRE_IN_DEGREES);
    }

    @Test
    public void testEPSG_4326() {
        // this test is asjusted to match proj4s behavior
        checkTransformAndInverse(
                "EPSG:4326", -126.54, 54.15,
                "EPSG:3005", 964813.103719, 1016486.305862,
                0.0001, 0.2 * APPROX_METRE_IN_DEGREES);

        checkTransformAndInverse(
                "EPSG:32633", 249032.839239894, 7183612.30572229,
                "EPSG:4326", 9.735465995870696, 64.68347938261206,
                0.000001, 0.3 * APPROX_METRE_IN_DEGREES);

        checkTransformAndInverse(
                "EPSG:32636", 500000, 4649776.224819178,
                "EPSG:4326", 33, 42,
                0.000001, 20 * APPROX_METRE_IN_DEGREES);
    }

    @Test
    public void testParams() {
        checkTransformFromWGS84("+proj=aea +lat_1=50 +lat_2=58.5 +lat_0=45 +lon_0=-126 +x_0=1000000 +y_0=0 +ellps=GRS80 +units=m ",
                -127.0, 52.11, 931625.9111828626, 789252.646454557, 0.0001);
    }

    /**
     * Values confirmed with PROJ.4 (Rel. 4.4.6, 3 March 2003)
     */
    @Test
    public void testPROJ4() {
        checkTransformFromGeo("EPSG:27492", -7.84, 39.58, 25260.78, -9668.93, 0.1);
        checkTransformFromGeo("EPSG:27700", -2.89, 55.4, 343642.04, 612147.04, 0.1);
        checkTransformFromGeo("EPSG:31285", 13.33333333333, 47.5, 450000.00, 5262298.75, 0.1);
        checkTransformFromGeo("EPSG:31466", 6.685, 51.425, 2547638.72, 5699005.05, 0.1);
        checkTransformFromGeo("EPSG:2736", 34.0, -21.0, 603934.39, 7677664.39, 0.1);
        checkTransformFromGeo("EPSG:26916", -86.6056, 34.579, 536173.11, 3826428.04, 0.1);
        checkTransformFromGeo("EPSG:21781", 8.23, 46.82, 660309.34, 185586.30, 0.1);
        checkTransformFromWGS84("EPSG:27700", -8.82, 49.79, -90619.28789678006, 10097.131147458786, 1E-4);
        checkTransformToWGS84("EPSG:27700", 612435.55, 1234954.16, 1.9200000236235546, 60.93999999543101, 0.0);
        checkTransformToWGS84("EPSG:27700", 327420.988668, 690284.547110, -3.1683134533969364, 56.0998025292667, 0.0);
        checkTransformFromWGS84("EPSG:3857", -3.1683134533969364, 56.0998025292667, -352695.04030562507, 7578309.225014557, 0.0);
        checkTransform("EPSG:27700", 327420.988668, 690284.547110, "EPSG:3857", -352695.04030562507, 7578309.225014557, 0.0);
        checkTransform("EPSG:3857", -352695.04030562507, 7578309.225014557, "EPSG:27700", 327420.988668, 690284.547110, 0.001);
        checkTransform("EPSG:31469", 5439627.33, 5661628.09, "EPSG:3857", 1573657.37, 6636624.41, 0.01);
        checkTransform("EPSG:3857", 1573657.37, 6636624.41, "EPSG:31469", 5439627.33, 5661628.09, 0.01);
        checkTransform("EPSG:2056", 2600670.52, 1199667.32, "EPSG:3857", 829045.23, 5933605.15, 0.01);
        checkTransform("EPSG:3857", 829045.23, 5933605.15, "EPSG:2056", 2600670.52, 1199667.32, 0.01);
        checkTransform("EPSG:3857", -20037508.342789244, -20037366.780895382, "EPSG:4055", -180.0, -85.01794318500549, 0.001);
        checkTransform("EPSG:4055", -180.0, -85.01794318500549, "EPSG:3857", -20037508.342789244, -20037366.780895382, 0.0);
        checkTransform("EPSG:9054", 103.095703, 36.421282, "EPSG:3857", 11476561.160934567, 4358745.039558878, 0.0);
    }

    @Test
    public void testPROJ4_LargeDiscrepancy() {
        checkTransformFromGeo("EPSG:29100", -53.0, 5.0, 5110899.06, 10552971.67, 4000);
    }

    @Test
    public void testRadius() {
        checkTransformToWGS84("+title=long/lat:WGS84 +proj=eqc +R=57295779.5130823209", 1000000.0, 1000000.0, 1.0, 1.0, 0.01);
    }

    @Ignore("TODO: Should these expect UnknownAuthoriyCode exceptions?") @Test
    public void XtestUndefined() {
        //runInverseTransform("EPSG:27492",    25260.493584, -9579.245052,    -7.84, 39.58);
        //runInverseTransform("EPSG:27563",    653704.865208, 176887.660037,    3.005, 43.89);
        //runInverseTransform("EPSG:54003",    1223145.57,6491218.13,-6468.21,    11.0, 53.0);


//    runTransform("EPSG:31467",   9, 51.165,       3500072.082451, 5670004.744777   );

        checkTransformFromWGS84("EPSG:54008", 11.0, 53.0, 738509.49, 5874620.38);

        checkTransformFromWGS84("EPSG:102026", 40.0, 40.0, 3000242.40, 5092492.64);
        checkTransformFromWGS84("EPSG:54032", -127.0, 52.11, -4024426.19, 6432026.98);

        checkTransformFromWGS84("EPSG:42304", -99.84375, 48.515625, -358185.267976, -40271.099023);
        checkTransformFromWGS84("EPSG:42304", -99.84375, 48.515625, -358185.267976, -40271.099023);
//    runInverseTransform("EPSG:28992",    148312.15, 457804.79, 698.48,    5.29, 52.11);
    }

    @Test
    public void testEPSG_2065() {
        checkTransformAndInverse(
                "EPSG:4326", 14.3954134, 50.0596485,
                "EPSG:2065", -745064.3097223851, -1045825.2153938366,
                0.001, 0.6 * APPROX_METRE_IN_DEGREES);
    }

    @Test
    public void testEPSG_5514() {
        checkTransformAndInverse(
                "EPSG:4326", 14.42, 50.075,
                "EPSG:5514", -743093.7321490766, -1044381.7725184687,
                0.001, 0.4 * APPROX_METRE_IN_DEGREES);
    }

    @Test
    public void testEPSG_27250() {
        checkTransform(
                "+proj=latlong +datum=WGS84", 174.7772114, -41.2887953,
                "+proj=tmerc +lat_0=-36.87986527777778 +lon_0=174.7643393611111 +k=0.9999 +x_0=300000 +y_0=700000 +datum=nzgd49 +units=m +towgs84=59.47,-5.04,187.44,0.47,-0.1,1.024,-4.5993 +nadgrids=nzgd2kgrid0005.gsb +no_defs", 301062.2010778899, 210376.65974323952,
                0.001);
    }

    // https://github.com/locationtech/proj4j/issues/116
    @Test
    public void testEPSG_2994() {
        checkTransform(
                "EPSG:2994", new ProjCoordinate(635788, 850485, 81),
                "+proj=geocent +datum=WGS84",
                new ProjCoordinate(-2505627.3608, -3847384.25836, 4412472.6628),
                0.001);
    }
}

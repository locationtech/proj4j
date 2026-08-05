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

/**
 * Tests correctness and accuracy of Coordinate System transformations.
 *
 * @author Martin Davis
 */
public class CoordinateTransformTest extends BaseCoordinateTransformTest {

    // testFirst deleted. It carried no @Test annotation, so it had never run, and both of its live
    // rows were exact duplicates of rows that do run:
    //   * the lcc/NAD83/us-ft row is identical to the second row of testLambertConformalConic()
    //   * the stere round-trip is identical to the third row of testStereographicAzimuthal()
    // Everything else in it was already commented out.

    /**
     * {@code EPSG:4326 -> EPSG:27700}, re-pinned to PROJ 9.8.1 after {@code Datum.OSGB36} was
     * corrected to the Helmert in PROJ's own {@code src/datums.cpp:59}
     * ({@code 446.448,-125.157,542.060,0.1502,0.2470,0.8421,-20.4894}) from EPSG:1314's rounded
     * {@code 0.15,0.247,0.842,-20.489}. That correction moved every OSGB36 row by about 3.3 mm.
     *
     * <h4>The old first row was right, and {@code Proj4JSTest:148} was wrong</h4>
     *
     * <p>These two asserted <b>different values for the same transform</b>: {@code 343733.1404}
     * here at 0.1 m, and {@code 343733.137100357} there at 1e-3 m. {@code cct} 9.8.1 on the
     * {@code datums.cpp} Helmert gives {@code 343733.140404274571}, so this row's four decimal
     * places were correct all along and merely too coarse to notice; the eighteen-digit value in
     * {@code Proj4JSTest} was bit-for-bit the EPSG:1314 Helmert, i.e. precise about the wrong
     * operation. <b>A wide tolerance on a right number beat a tight tolerance on a wrong one</b>,
     * and only re-deriving both from {@code cct} could say which was which. Both are now pinned to
     * the same PROJ value at 1e-8 m.
     *
     * <h4>The round trip was split into two independently pinned directions</h4>
     *
     * <p>It was {@code checkTransformAndInverse(..., 398089, 383867, ...)}, which inverse-transforms
     * the <em>expected</em> projected pair and compares against the original geographic pair. That
     * is not a round trip here: the forward leg feeds the Helmert an assumed height of 0 and
     * computes {@code h = -50.12}, while the inverse leg feeds it 0 again rather than
     * {@code -50.12}. The asymmetry is worth <b>1.24e-8 deg, about 1.4 mm</b> — real, expected of a
     * 2D Helmert, and 3x the old inverse tolerance. Coupling the two directions could only be
     * absorbed by widening that tolerance past the thing it exists to measure, so each direction is
     * now pinned to {@code cct} separately at 1e-8 m and 1e-12 deg, which is tighter than either
     * half of the old assertion. The two integers {@code 398089, 383867} were never a reference;
     * they were the seed the geographic point had been back-computed from.
     */
    @Test
    public void testEPSG_27700() {
        // cct -d 15 on the datums.cpp Helmert. Measured Proj4J residual: dx 0, dy 2.6e-10 m.
        checkTransform("EPSG:4326", -2.89, 55.4,
                "EPSG:27700", 343733.140404274571, 612144.531378557556, 1.0e-8);

        // Forward. Measured Proj4J residual: dx 0, dy 1.0e-9 m.
        checkTransform("EPSG:4326", -2.0301713578021983, 53.35168607080468,
                "EPSG:27700", 398089.003912951506, 383867.000589373347, 1.0e-8);
        // Inverse of that same projected pair. cct gives -2.030171345366366, 53.351686067759957;
        // measured Proj4J residual 1e-15 deg. Note it is NOT the geographic point above -- see the
        // 1.4 mm height asymmetry in this method's javadoc.
        checkTransform("EPSG:27700", 398089.003912951506, 383867.000589373347,
                "EPSG:4326", -2.030171345366366, 53.351686067759957, 1.0e-12);
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

    // testPconic deleted (PROJ.4 #148). It had no @Test annotation and its entire body was a single
    // commented-out line. The case is now live, with measured errors, at
    // Proj4VariousTest.testPconic() -- forward 565,848 m / 2,479,531 m off PROJ 9.8.1.

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

    /**
     * <code>somerc</code>.
     * <p>
     * {@code FeatureTest} used to assert 660389.52, 185731.63 for this same input at a 200 m
     * tolerance, contradicting this method. cs2cs 9.8.1 confirms <em>this</em> method:
     * {@code +proj=longlat +ellps=bessel -> somerc} gives <b>660309.341946539, 185586.295802115</b>,
     * which proj4j reproduces bit-for-bit. The other value is the WGS84-sourced answer (i.e. with the
     * {@code +towgs84=674.374,15.056,405.346} shift applied), and the 145.33 m gap between the two is
     * the size of that shift. The duplicate has been deleted; the shifted case is asserted at 1e-6 m
     * by {@code Proj4JSTest.testDatumShiftedProjections()}.
     */
    @Test
    public void testSwissObliqueMercator() {
        checkTransformFromAndToGeo("EPSG:21781", 8.23, 46.82, 660309.341946539, 185586.295802115,
                1.0e-6, 2 * APPROX_METRE_IN_DEGREES);
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

    /**
     * {@code createGeographic()} must carry {@code +pm}, because a prime meridian is a property of
     * the geodetic datum and not of the projected coordinate system laid over it.
     *
     * <p>{@code checkTransformFromGeo} goes through
     * {@code CoordinateReferenceSystem.createGeographic()}, which rebuilt the geographic CRS from the
     * datum and ellipsoid alone and <b>dropped {@code +pm}</b>. So this row silently asked for a
     * Greenwich-to-Paris conversion and answered 653,653.763 where the same-meridian answer is
     * 841,393.487 — <b>187,739.724 m</b> of easting error, expressed as a completely plausible
     * coordinate. 94 of the shipped EPSG definitions carry {@code +pm=}, and EPSG:4807, the
     * geographic CRS EPSG really pairs with EPSG:27563, is itself {@code +proj=longlat … +pm=paris}.
     *
     * <p>{@code cs2cs} 9.8.1: {@code 841393.487137525  181075.316188060}, matched to 2.5e-9 m.
     */
    @Test
    public void testPrimeMeridianSurvivesCreateGeographic() {
        checkTransformFromGeo("EPSG:27563", 3.005, 43.89, 841393.487137525, 181075.316188060, 1.0e-6);
        // The same thing on a numeric +pm, whose PrimeMeridian is named "user-provided" and so
        // cannot be reconstructed from its name. cs2cs 9.8.1 on the +pm=2.337229166666667 form of
        // the definition above gives the same two numbers.
        checkTransformFromGeo(
                "+proj=lcc +lat_1=44.10000000000001 +lat_0=44.10000000000001 +lon_0=0"
                        + " +k_0=0.999877499 +x_0=600000 +y_0=200000 +a=6378249.2 +b=6356515"
                        + " +towgs84=-168,-60,320,0,0,0,0 +pm=2.337229166666667 +units=m +no_defs",
                3.005, 43.89, 841393.487137525, 181075.316188060, 1.0e-6);
    }

    @Test
    public void testParams() {
        checkTransformFromWGS84("+proj=aea +lat_1=50 +lat_2=58.5 +lat_0=45 +lon_0=-126 +x_0=1000000 +y_0=0 +ellps=GRS80 +units=m ",
                -127.0, 52.11, 931625.9111828626, 789252.646454557, 0.0001);
    }

    /**
     * Values confirmed with PROJ.4 (Rel. 4.4.6, 3 March 2003) — for the seven two-decimal rows at
     * 0.1 m. The eighteen-digit rows were added much later and were <b>generated by Proj4J itself</b>,
     * which is why several of them carried {@code tolerance 0.0}.
     *
     * <h2>Three of those zero-tolerance rows moved, by 1.2 to 2.2 micrometres</h2>
     *
     * <p>Rows 1-3 below, all of them {@code EPSG:27700} sources, i.e. an ellipsoidal
     * {@code tmerc} inverse and therefore {@code inv_mlfn}. Replacing the PROJ-4-era
     * {@code inv_mlfn} in the numerical core shifted them:
     *
     * <table>
     * <caption>measured movement, frozen A/B</caption>
     * <tr><th>row</th><th>movement</th></tr>
     * <tr><td>{@code 27700 (612435.55, 1234954.16) -> WGS84}</td>
     *     <td>2.4e-12 deg lon, 2.0e-11 deg lat — about <b>2.2 um</b></td></tr>
     * <tr><td>{@code 27700 (327420.99, 690284.55) -> WGS84}</td>
     *     <td>3.4e-13 deg lon, 1.1e-11 deg lat — about <b>1.2 um</b></td></tr>
     * <tr><td>{@code 27700 -> 3857}</td><td>3.7e-08 m easting, 2.2e-06 m northing</td></tr>
     * </table>
     *
     * <p><b>A {@code tolerance 0.0} assertion against a value Proj4J printed for itself is not a
     * correctness bar</b>, so the three took a stated tolerance naming the movement rather than
     * pretending to bit-exactness across a numerical-core replacement.
     *
     * <p>That round left three zero-tolerance rows standing — {@code 4326 -> 3857},
     * {@code 4055 -> 3857} and {@code 9054 -> 3857} — on the reasoning that all three are spherical
     * Mercator forwards with no {@code inv_mlfn} in them. The reasoning was right about the kernel
     * and wrong about the bar: <b>two of the three were failing by 1e-9 and 2e-9 m</b> by the time
     * anything else in this method reached them, which is a build failure produced by nothing more
     * than the order the multiply-adds ended up in. Both now carry a stated tolerance. Only
     * {@code 9054 -> 3857} is left at {@code 0.0}, and only because it is bit-for-bit {@code cct}.
     *
     * <h2>SUPERSEDED: every OSGB36 row is now pinned to PROJ, and the "systematic residual" is gone</h2>
     *
     * <p>The paragraph that used to stand here said {@code cs2cs} 9.8.1 gave
     * {@code 1.920000023403453 60.939999989916728} for the first of those rows, that Proj4J was
     * 0.6 mm away, and that the gap belonged to <i>"the {@code tmerc} algorithm choice (PROJ's
     * default is the exact Poder/Engsager formulation; Proj4J runs the Evenden/Snyder series) and
     * to the 2D-Helmert-with-assumed-height path"</i>. Two of those three claims were wrong:
     *
     * <ul>
     * <li><b>It was the Helmert, not {@code tmerc}.</b> {@code Datum.OSGB36} carried EPSG:1314's
     *     rounded {@code 0.15,0.247,0.842,-20.489}; PROJ 9.8.1 defines {@code +datum=OSGB36} in
     *     {@code src/datums.cpp:59} as {@code 0.1502,0.2470,0.8421,-20.4894}. Correcting it moved
     *     eight rows in this file by 1.4e-3 to 6.0e-3 m. Measured on the identical pipeline at one
     *     of these points, {@code +approx} and exact {@code tmerc} differ by <b>1.6e-9 m</b> — six
     *     orders of magnitude below the residual they were blamed for. Testing the two causes
     *     separately is what separated them.</li>
     * <li><b>The residual is not systematic; it is zero.</b> With the corrected Helmert, Proj4J is
     *     bit-for-bit {@code cct} 9.8.1 on all eight: worst ordinate residual over the whole method
     *     is <b>1.5e-9 m</b>. Every eighteen-digit row below is now a {@code cct} value at a
     *     tolerance just above its measured residual, so none of them is Proj4J agreeing with
     *     itself any more.</li>
     * </ul>
     *
     * <p>The one claim that stands is the last: a {@code tolerance 0.0} row is not a correctness
     * bar. Two survivors are dealt with at their call sites below.
     *
     * <h2>RETRACTED: the {@code EPSG:4055 <-> EPSG:3857} "42.5 km defect" was a comparison against
     * the wrong reference, and the named cause is load-bearing for Web Mercator</h2>
     *
     * <p>The paragraphs that used to stand here said these two rows disagreed with PROJ 9.8.1 by
     * <b>42,538.9 m</b> of northing, that the cause was
     * {@code GeocentricConverter.overrideWithWGS84Params()} replacing the target sphere with the
     * WGS 84 ellipsoid, and that this was "a sphere-geodetic to ellipsoid-geodetic latitude
     * conversion that PROJ does not perform, because to PROJ {@code +nadgrids=@null} means no datum
     * shift at all." <b>All three claims are wrong.</b> Measured, on the identical parameter
     * strings, Proj4J agrees with {@code cs2cs} 9.8.1 to <b>2.3e-7 m</b>.
     *
     * <p><b>The 42.5 km was two different questions, not two different answers.</b> The reference
     * had been taken from {@code cs2cs EPSG:4055 EPSG:3857}, which resolves both codes in
     * {@code proj.db}. Proj4J resolves them in {@code proj4/nad/epsg}, and the two definitions of
     * {@code EPSG:4055} are not the same CRS:
     *
     * <table>
     * <caption>{@code EPSG:4055}, two definitions</caption>
     * <tr><th>source</th><th>definition</th></tr>
     * <tr><td>{@code proj4/nad/epsg:100}</td>
     *     <td>{@code +proj=longlat +a=6378137 +b=6378137 +towgs84=0,0,0,0,0,0,0 +no_defs}</td></tr>
     * <tr><td>{@code projinfo EPSG:4055 -o PROJ} at 9.8.1</td>
     *     <td>{@code +proj=longlat +R=6378137 +no_defs} — <b>no {@code +towgs84}</b></td></tr>
     * </table>
     *
     * <p>{@code EPSG:4055} and EPSG:15973, its transformation to WGS 84, are <em>both</em>
     * deprecated in EPSG, so 9.8.1 declines to use 15973 and answers with a
     * <i>Ballpark geographic offset</i> — {@code +proj=noop} — which is why the database route
     * leaves the latitude alone. EPSG 15973 itself is a geocentric translation of {@code (0,0,0)}
     * whose description reads <i>"Executes change of sphere/ellipsoid"</i>, declared accuracy
     * <b>800 m</b>: the sphere-to-ellipsoid latitude change is the transformation EPSG registered,
     * not an artefact. The legacy dictionary's {@code +towgs84=0,0,0,0,0,0,0} is that same
     * operation, and 9.8.1 honours it when it is asked to — the composition it builds for the
     * dictionary strings contains {@code +step +proj=cart +R=6378137} followed by
     * {@code +step +inv +proj=cart +ellps=WGS84}, i.e. exactly the conversion it was said never to
     * perform. Both engines then agree in <em>both</em> configurations:
     *
     * <table>
     * <caption>at (-180, -85.01794318500549), lon/lat, northing in metres</caption>
     * <tr><th>source</th><th>target</th><th>Proj4J</th><th>{@code cs2cs} 9.8.1</th></tr>
     * <tr><td>dictionary {@code 4055}, with {@code +towgs84}</td><td>dictionary {@code 3857}</td>
     *     <td>-20037366.780895380</td><td>-20037366.780895609</td></tr>
     * <tr><td>the same, {@code +towgs84} removed</td><td>dictionary {@code 3857}</td>
     *     <td>-19994827.892149360</td><td>-19994827.892149359</td></tr>
     * </table>
     *
     * <p><b>The named method is not the defect; it is what makes {@code 4326 -> 3857} correct.</b>
     * Disabling both call sites with an {@code if (false && ...)} and re-running the same matrix
     * moves the headline row 42,538.9 m — <em>away</em> from PROJ — and breaks ten others by 305 m
     * to 30 km, including {@code 4326 -> 3857} by <b>25,380 m</b>. {@code EPSG:3857} carries
     * {@code +nadgrids=@null} on an {@code a=b=6378137} sphere, so without the override every Web
     * Mercator transform round-trips WGS 84 latitudes through a sphere. 9.8.1 does the same thing
     * the method does: the pipeline for a {@code +nadgrids=} CRS into a Helmert one is
     * {@code hgridshift} followed by {@code +proj=cart +ellps=WGS84} — the grid's own ellipsoid
     * never appears, because the output of a grid shift <em>is</em> WGS 84 geodetic. The method is
     * pinned, with that matrix, by {@code datum/NadgridsWgs84OverrideTest}.
     *
     * <p>So both rows are re-pinned below to {@code cs2cs} 9.8.1 <em>on the parameter strings
     * Proj4J is actually given</em>, which is what non-negotiable 5a asks for and is a different
     * measurement from {@code cs2cs EPSG:4055 EPSG:3857}. Any remaining gap to the database route
     * lives in {@code proj4/nad/epsg}, not in {@code datum/} or {@code parser/}.
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
        // --- OSGB36 rows, all re-pinned to cct 9.8.1 on the datums.cpp Helmert. Residuals in
        // --- brackets are the measured Proj4J-vs-PROJ gap; tolerances sit just above them.
        checkTransformFromWGS84("EPSG:27700", -8.82, 49.79,
                -90619.285016088281, 10097.132572255272, 1.0e-8);      // [1.2e-10, 1.1e-9 m]
        checkTransformToWGS84("EPSG:27700", 612435.55, 1234954.16,
                1.919999951933030, 60.939999991837148, 1.0e-12);        // [1e-15, 7e-15 deg]
        checkTransformToWGS84("EPSG:27700", 327420.988668, 690284.547110,
                -3.168313507517727, 56.099802520035347, 1.0e-12);       // [0, 7e-15 deg]
        // Spherical Mercator forward, no datum shift and no OSGB36: this row did not move, and
        // Proj4J is bit-for-bit cct (-352695.040305625065, 7578309.225014558062). Its northing was
        // pinned one ulp low at tolerance 0.0, so it was a build failure waiting on any
        // reassociation anywhere in the merc kernel. Re-pinned to PROJ, with 1e-8 m stated.
        checkTransformFromWGS84("EPSG:3857", -3.1683134533969364, 56.0998025292667,
                -352695.040305625065, 7578309.225014558062, 1.0e-8);    // [<1e-9 m]
        // 27700 -> 3857 goes through the Helmert, so it moved 6.0e-3 m with Datum.OSGB36.
        checkTransform("EPSG:27700", 327420.988668, 690284.547110,
                "EPSG:3857", -352695.046330323908, 7578309.223172096536, 1.0e-8);  // [8e-9, 1.5e-9 m]
        // ...and 3857 -> 27700 is NOT its inverse: the input pair here is the 4326-sourced value
        // from two rows up, not the 27700-sourced value from one row up, so the two differ by the
        // 6.0e-3 m above. Pinned to cct in its own right rather than to the round trip it resembles.
        checkTransform("EPSG:3857", -352695.04030562507, 7578309.225014557,
                "EPSG:27700", 327420.992840466322, 690284.548029357218, 1.0e-8);   // [2.2e-11, 7.2e-10 m]
        checkTransform("EPSG:31469", 5439627.33, 5661628.09, "EPSG:3857", 1573657.37, 6636624.41, 0.01);
        checkTransform("EPSG:3857", 1573657.37, 6636624.41, "EPSG:31469", 5439627.33, 5661628.09, 0.01);
        checkTransform("EPSG:2056", 2600670.52, 1199667.32, "EPSG:3857", 829045.23, 5933605.15, 0.01);
        checkTransform("EPSG:3857", 829045.23, 5933605.15, "EPSG:2056", 2600670.52, 1199667.32, 0.01);
        // The two EPSG:4055 rows, now cs2cs 9.8.1 values. Both were self-portraits before and the
        // 42,538.9 m attributed to them was a comparison against cs2cs EPSG:4055 EPSG:3857, i.e.
        // against proj.db's non-deprecated reading of a deprecated CRS, not against 9.8.1's answer
        // for the dictionary strings Proj4J parses. See this method's javadoc. Reference command,
        // with S and T the verbatim proj4/nad/epsg entries for 4055 and 3857 plus "+type=crs":
        //   echo "-20037508.342789244 -20037366.780895382" | cs2cs -d 17 "$T" +to "$S"
        //   echo "-180 -85.01794318500549"                 | cs2cs -d 15 "$S" +to "$T"
        // The inverse row's old expected latitude, -85.01794318500549, was neither engine's answer:
        // it was this pair's forward *input* copied back, passing only on a 0.001 deg tolerance.
        // The two directions do not round-trip, in PROJ either -- the geocentric leg is bracketed
        // by push/pop +v_3, so the height the sphere-to-ellipsoid change generates is discarded and
        // the inverse re-enters with h=0. 9.8.1's own inverse of its own forward output lands on
        // -85.017832752549779, 1.1e-4 deg from where it started.
        checkTransform("EPSG:3857", -20037508.342789244, -20037366.780895382,
                "EPSG:4055", -180.0, -85.017832752549594, 1.0e-12);      // [0, 5.5e-15 deg]
        checkTransform("EPSG:4055", -180.0, -85.01794318500549,
                "EPSG:3857", -20037508.342789243907, -20037366.780895609409, 1.0e-6);  // [9e-11, 2.3e-7 m]
        // Spherical Mercator forward on WGS 84 lon/lat, no datum shift. cct 9.8.1 gives
        // 11476561.160934567451  4358745.039558878168, which Proj4J reproduces exactly -- so this
        // is the one zero-tolerance row in this method that is a reference and not a self-portrait.
        checkTransform("EPSG:9054", 103.095703, 36.421282, "EPSG:3857", 11476561.160934567, 4358745.039558878, 0.0);
    }

    /**
     * Was <code>testPROJ4_LargeDiscrepancy</code>, asserting EPSG:29100 to within <b>4,000 m</b>.
     * <p>
     * The expected values it carried (5110899.06, 10552971.67) were <em>correct</em> — cs2cs 9.8.1
     * gives 5110899.063466947, 10552971.667179093. The 4,000 m tolerance existed because proj4j was
     * wrong by <b>dx 2.820 m, dy 3,712.139 m</b>: {@code PolyconicProjection} ran the spherical
     * formula on an ellipsoid, its output bit-identical to {@code cs2cs +R=6378160}. Now fixed, so the
     * correct value can be asserted at 1e-9 m instead of 4,000 m.
     * <p>
     * The full diagnosis is at {@link Proj4JSTest#testPolyconicIsEllipsoidal()}.
     */
    @Test
    public void testPolyconic() {
        checkTransformFromGeo("EPSG:29100", -53.0, 5.0, 5110899.063466947, 10552971.667179093, 1.0e-9);
    }

    @Test
    public void testRadius() {
        checkTransformToWGS84("+title=long/lat:WGS84 +proj=eqc +R=57295779.5130823209", 1000000.0, 1000000.0, 1.0, 1.0, 0.01);
    }

    // XtestUndefined deleted. It was doubly disabled (an "X" prefix *and* an @Ignore whose reason was
    // an unanswered question), and every code it named -- 54008, 102026, 54032, 42304 -- is an ESRI
    // code being looked up under the EPSG authority, which cannot and must not resolve. The question
    // is now answered as an assertion rather than a TODO, in
    // Proj4JSTest.testEpsgAuthorityDoesNotResolveEsriCodes(), which requires
    // UnknownAuthorityCodeException for all seven such codes.

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

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
 * Tests for the PROJ.4 <em>features</em> — parameters and datum transformations — rather than for
 * numerical correctness of any one projection.
 * <p>
 * <b>Every expected value in this class was measured against PROJ 9.8.1 (<code>cs2cs -d 9</code>,
 * Rel. 9.8.1, April 10th 2026) and the tolerance is set to the observed agreement.</b> This replaces
 * a class javadoc that read <i>"It is expected that many of these test will fail, until the tested
 * features are implemented"</i> while all seven methods passed — because four tolerances had been
 * widened until they did: <code>testR_A</code> 50,000 m, <code>testTowgs84</code> 100 m,
 * <code>testSouth</code> 200 m, <code>testSwissObliqueMercator</code> 200 m.
 * <p>
 * What the padding was hiding, once measured, was almost the opposite of what the javadoc claimed:
 * <ul>
 *   <li><code>+R_A</code>, <code>+south</code>, <code>+towgs84</code> and <code>somerc</code> are all
 *       accurate to within 5e-6 m of PROJ 9.8.1. The old expected values — imported from Proj4.JS —
 *       were themselves wrong by 27,845 m, 159 m and 145 m respectively, and the wide tolerances were
 *       absorbing <em>their</em> error, not proj4j's.</li>
 *   <li>Two tests were calling the wrong helper: <code>checkTransformFromGeo</code> (source = the
 *       target CRS's own geographic CRS, so <b>no</b> datum shift) with expected values that had been
 *       computed from WGS84 (i.e. <b>with</b> the shift). See {@link #testTowgs84()}.</li>
 *   <li>The one genuine gap {@link #testDatumConversion()} exposes is not arithmetic at all: PROJ
 *       9.8.1 redefined <code>+datum=potsdam</code> to use the BETA2007 grid.</li>
 * </ul>
 *
 * @author Martin Davis
 */
public class FeatureTest extends BaseCoordinateTransformTest {
    CoordinateTransformTester tester = new CoordinateTransformTester(true);

    /**
     * A 7-parameter datum shift, WGS84 -&gt; DHDN / Gauss-Kruger zone 2.
     * <p>
     * proj4j reproduces PROJ 9.8.1's <em>Helmert</em> path exactly. Three values exist for this point
     * and the difference between them is a definition change, not a defect:
     * <pre>
     *   proj4j                                        2547686.152499  5699151.775294
     *   cs2cs +towgs84=598.1,73.7,418.2,...           2547686.152499  5699151.775293   dx 0.0  dy 1.6e-6
     *   cs2cs +datum=potsdam  (= @BETA2007.gsb)       2547687.754608  5699151.154188   dx 1.602  dy 0.621
     *   the old expected value in this test           2547685.012119  5699155.734503   dx 1.140  dy 3.959
     * </pre>
     * The old expected value is exactly PROJ's answer for the <em>legacy 3-parameter</em>
     * <code>+towgs84=606.0,23.0,413.0</code> definition of potsdam (verified: cs2cs gives
     * 2547685.012118995, 5699155.734503226). proj4j's {@code Datum.POTSDAM} is the newer 7-parameter
     * set, so the test literal had been stale for two definition changes and the 10 m tolerance hid
     * both.
     * <p>
     * <b>Finding:</b> PROJ 9.8.1's {@code +datum=potsdam} is {@code nadgrids=@BETA2007.gsb} with the
     * towgs84 form commented out in {@code src/datums.cpp}. Reaching parity needs the grid, i.e.
     * a GeoTIFF reader; the remaining 1.602 m is the grid-vs-Helmert residual and nothing else.
     */
    @Test
    public void testDatumConversion() {
        // <31466> +proj=tmerc +lat_0=0 +lon_0=6 +k=1 +x_0=2500000 +y_0=0 +datum=potsdam +units=m
        checkTransformFromWGS84("EPSG:31466", 6.685, 51.425, 2547686.152499, 5699151.775294, 1.0e-5);
    }

    /**
     * <code>+pm=paris</code>. Was <code>NOTSUPPORTED_testPrimeMeridian</code>, disabled by naming
     * convention with a commented-out <code>@Test</code>. <code>+pm=</code> appears in 94 EPSG
     * definitions, so it was a real blind spot.
     * <p>
     * <b>Checked, not assumed: <code>+pm</code> works, and matches PROJ 9.8.1 to 1e-9 m.</b> Both
     * values below are bit-identical between proj4j and cs2cs. proj4j's
     * {@code PrimeMeridian.east("paris", 2, 20, 14.025)} agrees with PROJ's
     * {@code {"paris", "2d20'14.025\"E"}}.
     * <p>
     * The reason the old test could not pass is neither <code>+pm</code> nor the arithmetic: its
     * expected value (653704.865208, 176887.660037) matches <em>neither</em> configuration, being
     * 51.10 m from the Greenwich-source answer and 187.7 km from the Paris-source answer. It is a
     * third-party literal of unknown provenance, and 653653.762847 is what PROJ 9.8.1 produces for
     * the transform the old test actually requested.
     */
    @Test
    public void testPrimeMeridian() {
        // <27563> NTF (Paris) / Lambert Sud France
        final String lccParis = "+proj=lcc +lat_1=44.10000000000001 +lat_0=44.10000000000001 +lon_0=0"
                + " +k_0=0.999877499 +x_0=600000 +y_0=200000 +a=6378249.2 +b=6356515"
                + " +towgs84=-168,-60,320,0,0,0,0 +pm=paris +units=m +no_defs";

        // Source longitudes measured from Paris, as the target's +pm declares.
        // cs2cs: 841393.487137525  181075.316188060
        checkTransform("+proj=longlat +a=6378249.2 +b=6356515 +pm=paris", 3.005, 43.89,
                lccParis, 841393.487137525, 181075.316188060, 1.0e-6);

        // Source longitudes measured from Greenwich. PROJ subtracts the target's +pm, so this is a
        // different (and legitimate) transform, not an error.
        // cs2cs: 653653.762846642  176887.146881258
        checkTransform("+proj=longlat +a=6378249.2 +b=6356515", 3.005, 43.89,
                lccParis, 653653.762846642, 176887.146881259, 1.0e-6);
    }

    /**
     * A declared blind spot, kept visible rather than silent.
     * <p>
     * {@code CoordinateReferenceSystem.createGeographic()} (main source, {@code :113-120}) rebuilds a
     * geographic CRS from the datum and ellipsoid only — it <b>drops <code>+pm</code></b>. So
     * {@code checkTransformFromGeo("EPSG:27563", ...)} silently asks for a Greenwich-to-Paris
     * transform and gets 653653.762847 where the same-meridian answer is 841393.487138: an error of
     * <b>187,739.724 m</b> in easting, expressed as a perfectly plausible coordinate.
     * <p>
     * This affected every one of the 94 EPSG definitions carrying <code>+pm=</code> whenever a caller
     * used {@code createGeographic()}.
     * <p>
     * <b>Fixed in main source and live again.</b> {@code createGeographic()} now carries the prime
     * meridian, since a prime meridian belongs to the datum — EPSG pairs EPSG:27563 with EPSG:4807,
     * which is itself {@code +proj=longlat … +pm=paris}. Matches {@code cs2cs} 9.8.1 to 2.5e-09 m.
     * See also {@code CoordinateTransformTest.testPrimeMeridianSurvivesCreateGeographic}, which adds
     * the numeric-{@code +pm} case.
     */
    @Test
    public void testPrimeMeridianViaCreateGeographic() {
        checkTransformFromGeo("EPSG:27563", 3.005, 43.89, 841393.487137525, 181075.316188060, 1.0e-6);
    }

    /**
     * <code>omerc</code> with <code>+gamma</code>.
     * <p>
     * Both rows are bit-identical to PROJ 9.8.1 (4e-9 m and 1e-9 m). Note carefully <em>which</em>
     * <code>+gamma</code> cases these are — the two that work:
     * <ul>
     *   <li>EPSG:2057 has <code>gamma == alpha</code>.</li>
     *   <li>EPSG:3375 has <code>gamma != alpha</code> <b>but also <code>+no_uoff</code></b>, which
     *       short-circuits the one line that is wrong.</li>
     * </ul>
     * The uncovered case — <code>gamma != alpha</code> without <code>+no_uoff</code> — is wrong by
     * 3,165 m; see {@link Proj4VariousTest#testRSOBorneo()}.
     */
    @Test
    public void testGamma() {
        // <2057> omerc, gamma == alpha, through a 3-param towgs84.
        // cs2cs: -11608322.257560587  18282612.229838394
        checkTransformFromWGS84("EPSG:2057", -53.0, 5.0, -11608322.257560587, 18282612.229838394, 1.0e-5);

        // <3375> omerc, gamma != alpha, +no_uoff.  cs2cs: 412597.532715333  338944.957259173
        checkTransformFromGeo("EPSG:3375", 101.70979078430528, 3.06268465621428,
                412597.532715333, 338944.957259173, 1.0e-6);
    }

    /**
     * <code>+R_A</code> — spherification to the authalic radius.
     * <p>
     * The old tolerance was <b>50,000 m</b>, with the comment <i>"result is out by 50,000 m"</i>.
     * It is not: proj4j and PROJ 9.8.1 agree to <b>1e-9 m</b>.
     * <pre>
     *   proj4j            1223145.571759001  6519063.378708009
     *   cs2cs +R_A        1223145.571759001  6519063.378708010
     *   old expected      1223145.57         6491218.13          dy = 27,845.249 m
     *   cs2cs without R_A 1224514.398726009  6526358.887892235
     * </pre>
     * <b>Finding:</b> the 50,000 m padding was absorbing an error in the Proj4.JS expected value, not
     * in proj4j. 6491218.13 is neither the <code>+R_A</code> nor the ellipsoidal answer, and the
     * northing error (27,845 m) is 20,000× the easting error (0.0018 m) — the signature of a wrong
     * reference value, not of a wrong radius.
     */
    @Test
    public void testR_A() {
        // ESRI:54003-alike
        String prj = "+proj=mill +lat_0=0 +lon_0=0 +x_0=0 +y_0=0 +R_A +ellps=WGS84 +datum=WGS84 +units=m +no_defs";
        checkTransformFromGeo(prj, 11.0, 53.0, 1223145.571759001, 6519063.378708010, 1.0e-6);
    }

    /**
     * <code>+towgs84</code>. Was tolerance 100 m with the comment <i>"result is out by 100 m"</i>.
     * <p>
     * <b>The test was calling the wrong helper.</b> Its expected value (450055.70, 5262356.33) is the
     * <em>WGS84-sourced</em> answer, i.e. the one that <em>does</em> apply the datum shift, but it was
     * passed to {@code checkTransformFromGeo}, whose source is the target's own geographic CRS — so
     * no shift is applied and <code>+towgs84</code> was never exercised at all. The 100 m tolerance
     * was the size of the missing datum shift (55.70 m E, 57.58 m N).
     * <p>
     * Switched to {@code checkTransformFromWGS84}, which is what the method name always claimed.
     * proj4j then matches PROJ 9.8.1 to 1e-6 m in both configurations:
     * <pre>
     *   fromWGS84  proj4j 450055.697319183  5262356.325308706
     *              cs2cs  450055.697319185  5262356.325307687
     *   fromGeo    proj4j 449999.999999749  5262298.750218449
     *              cs2cs  449999.999999751  5262298.750217431
     * </pre>
     * (The same-datum row is already asserted at 0.1 m by
     * {@link CoordinateTransformTest#testPROJ4()}.)
     */
    @Test
    public void testTowgs84() {
        // <31285> MGI / M31, +datum=hermannskogel (7-param towgs84 577.326,90.129,463.919,...)
        checkTransformFromWGS84("EPSG:31285", 13.33333333333, 47.5,
                450055.697319185, 5262356.325307687, 1.0e-5);
    }

    /**
     * <code>+south</code>. Was tolerance 200 m on the second row, with the comment
     * <i>"result is out by 200 m"</i>.
     * <p>
     * It is out by nothing. proj4j is bit-identical to PROJ 9.8.1 on all three rows below (the
     * largest disagreement is 1e-9 m). The old expected value 603933.40, 7677505.64 is a Proj4.JS
     * literal that is itself <b>158.754 m</b> from PROJ's answer in northing.
     */
    @Test
    public void testSouth() {
        // <2736> +proj=utm +zone=36 +south +ellps=clrk66 +towgs84=-80,-100,-228,0,0,0,0
        // cs2cs: 512093.765436531  7883804.406910769
        checkTransformFromGeo("EPSG:2736", 33.115, -19.14, 512093.765436531, 7883804.406910769, 1.0e-6);

        // cs2cs: 603934.388709571  7677664.393970920  (same-datum; was asserted as 603933.40, 7677505.64)
        checkTransformFromGeo("EPSG:2736", 34.0, -21.0, 603934.388709571, 7677664.393970920, 1.0e-6);

        // and through the datum shift, which the old test never reached.
        // cs2cs: 603973.158165756  7677761.994978547
        checkTransformFromWGS84("EPSG:2736", 34.0, -21.0, 603973.158165756, 7677761.994978547, 1.0e-6);
    }

    @Test
    public void testLambertEqualArea() {
        // cs2cs: 4388138.600454633  3321736.463013414
        checkTransformFromGeo("EPSG:3035", 11.0, 53.0, 4388138.600454633, 3321736.463013414, 1.0e-6);
        // cs2cs: 2923052.020092619  1054885.465592114
        checkTransformFromGeo("EPSG:3573", 9.84375, 61.875, 2923052.020092619, 1054885.465592114, 1.0e-7);
    }

    // testSwissObliqueMercator deleted.
    //
    // It asserted EPSG:21781 at (8.23, 46.82) -> 660389.52, 185731.63 with a 200 m tolerance, while
    // CoordinateTransformTest.testSwissObliqueMercator asserts 660309.34, 185586.30 for the same
    // input at 0.1 m. cs2cs 9.8.1 settles it:
    //
    //   longlat+bessel -> somerc (what checkTransformFromGeo does):  660309.341946539  185586.295802115
    //   EPSG:4326      -> EPSG:21781 (i.e. with the datum shift):    660389.515487420  185731.630395966
    //
    // proj4j's same-datum answer is 660309.341946539, 185586.295802122 -- bit-identical to PROJ. So
    // CoordinateTransformTest's expected value is correct and this one was the WGS84-sourced value
    // fed to a same-datum helper, exactly as in testTowgs84 above. The 145.33 m northing gap between
    // the two "expected" values is the size of the +towgs84=674.374,15.056,405.346 shift.
    // The surviving test in CoordinateTransformTest covers the projection; testTowgs84 above covers
    // the shift.
}

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

import static org.junit.Assert.fail;

/**
 * Tests originally imported from Proj4.JS, re-pinned against PROJ 9.8.1.
 * <p>
 * <b>Every expected value here is now a <code>cs2cs -d 9</code> (PROJ 9.8.1) measurement</b> and every
 * tolerance is the observed agreement. Previously the file carried tolerances of 100 m, 3 m, 2 m,
 * 4,000 m and — in <code>testLargeDiscrepancy</code> — <b>7,000,000 m</b>, which asserts nothing about
 * a coordinate on Earth.
 * <p>
 * The measurement produced one systematic finding and one real defect.
 * <p>
 * <b>1. The whole "large discrepancy" file was a helper mix-up, not a discrepancy.</b> Six of its
 * eight rows had expected values computed <em>from WGS84</em> (with the datum shift) but were passed to
 * {@code checkTransformFromGeo}, whose source is the target CRS's own geographic CRS and therefore
 * applies <em>no</em> shift. The "discrepancy" being tolerated was in every case the size of the
 * omitted <code>+towgs84</code>:
 * <pre>
 *   EPSG:27700  expected 343733.14, 612144.53  = the fromWGS84 answer (fromGeo: 343642.04, 612147.04)
 *   EPSG:21781  expected 660389.52, 185731.63  = the fromWGS84 answer (fromGeo: 660309.34, 185586.30)
 *   EPSG:31285  expected 450055.70, 5262356.33 = the fromWGS84 answer (fromGeo: 450000.00, 5262298.75)
 * </pre>
 * With the correct helper, proj4j agrees with PROJ 9.8.1 to 1e-6 m or better on all of them.
 * <p>
 * <b>2. <code>+proj=poly</code> was 3,712 m wrong, hidden behind a 4,000 m tolerance</b> — now fixed
 * in main source and pinned at 1e-9 m. See {@link #testPolyconicIsEllipsoidal()}.
 *
 * @author Martin Davis
 */
public class Proj4JSTest extends BaseCoordinateTransformTest {
    static boolean debug = true;

    static CoordinateTransformTester tester = new CoordinateTransformTester(true);

    /**
     * Same-datum (projection-only) forward transforms. Every value below is a cs2cs 9.8.1 measurement
     * and proj4j matches all of them to &lt;= 1e-7 m; the tolerances are set accordingly.
     * <p>
     * The old literals in this method were Proj4.JS values and five of them were wrong by more than
     * a metre — which is what the 100 m / 4,000 m / 3 m / 2 m tolerances were absorbing:
     * <pre>
     *   EPSG:23030  old 168035.13, 4199884.83     PROJ 168031.545059, 4199796.328566   err   3.58 /  88.50 m
     *   EPSG:2403   old 2.75E7,   4198690.08      PROJ 27500000.0,    4198692.697844   err   0.00 /   2.62 m
     *   EPSG:3411   old 1070076.44, -4635010.27   PROJ 1070076.154400, -4635009.046432 err   0.29 /   1.22 m
     *   EPSG:27200  old 2464780.81, 6056330.22    PROJ 2464780.806178, 6056330.222215  err   0.004/   0.002 m
     *   EPSG:29100  old 5110899.06, 1.055297181E7 PROJ 5110899.063467, 10552971.667179 -- moved to
     *                                                                 testPolyconicIsEllipsoidal
     * </pre>
     */
    @Test
    public void testGood() {
        checkTransformFromGeo("EPSG:23030", -6.77432123185356, 37.88456231505968,
                168031.545058617, 4199796.328566180, 1.0e-6);
        checkTransformFromGeo("EPSG:2403", 81.0, 37.92, 27500000.000000000, 4198692.697844380, 1.0e-6);
        checkTransformFromGeo("EPSG:3031", -57.65625, -79.21875, -992481.633786435, 628482.063279764, 1.0e-6);
        checkTransformFromGeo("EPSG:3035", 11.0, 53.0, 4388138.600454633, 3321736.463013414, 1.0e-6);
        checkTransformFromGeo("EPSG:3153", -127.0, 52.11, 931625.911182863, 789252.646454557, 1.0e-7);
        checkTransformFromGeo("EPSG:32612", -113.109375, 60.28125, 383357.429537338, 6684599.063919374, 1.0e-7);
        checkTransformFromGeo("EPSG:32615", -93.0, 42.0, 499999.999999999, 4649776.224819178, 1.0e-7);
        checkTransformFromGeo("EPSG:3411", -32.0, 48.0, 1070076.154400352, -4635009.046431893, 1.0e-6);
        checkTransformFromGeo("EPSG:3573", 9.84375, 61.875, 2923052.020092619, 1054885.465592114, 1.0e-7);
        checkTransformFromGeo("EPSG:3375", 101.70979078430528, 3.06268465621428,
                412597.532715333, 338944.957259173, 1.0e-6);

        // nzmg. The old pair of rows was internally inconsistent: it asserted that
        // (2464770.343667, 6056137.861919) inverts to (172.465, -40.7) *and* that (172.465, -40.7)
        // projects to (2464780.81, 6056330.22) -- two different points, reconciled only by a 0.1 degree
        // (~11 km) tolerance on the inverse. Both rows are kept, each against its own PROJ answer.
        checkTransformFromGeo("EPSG:27200", 172.465, -40.7, 2464780.806177843, 6056330.222215011, 1.0e-6);
        checkTransformToGeo("EPSG:27200", 2464770.343667, 6056137.861919,
                172.464862404, -40.701731353, 1.0e-8);
    }

    /**
     * <code>+proj=poly</code> ran the <b>spherical</b> formula on an ellipsoid.
     * <p>
     * This was the one real defect the old file's padded tolerances were hiding. It has since been
     * fixed in main source, and is pinned here at 1e-9 m so that it cannot return. For EPSG:29100
     * (SAD69 / Brazil Polyconic, GRS67) at (-53, 5), with the +5,000,000 / +10,000,000 false origin
     * removed:
     * <pre>
     *   cs2cs +ellps=GRS67 (ellipsoidal)   110899.063466947   552971.667179093   &lt;-- correct
     *   cs2cs +R=6378160     (spherical)   110896.243653353   556683.806280897
     *   proj4j, before the fix             110896.243653353   556683.806280898   &lt;-- bit-identical
     *   proj4j, now                        110899.063466947   552971.667179093       to the sphere
     * </pre>
     * That bit-identity to PROJ's <em>spherical</em> path is what pinned the diagnosis to a missing
     * ellipsoidal branch rather than to an arithmetic slip. <b>Error before the fix: dx 2.820 m,
     * dy 3,712.139 m.</b>
     * <p>
     * Both tests that covered this row were sized to step straight over that 3,712 m: the old
     * <code>testGood</code> asserted the <em>wrong</em> value at 4,000 m, and
     * {@code CoordinateTransformTest.testPROJ4_LargeDiscrepancy} asserted the <em>right</em> value —
     * also at 4,000 m. Two tests, one true value between them, and neither could tell you which.
     */
    @Test
    public void testPolyconicIsEllipsoidal() {
        checkTransformFromGeo("EPSG:29100", -53.0, 5.0, 5110899.063466947, 10552971.667179093, 1.0e-9);
    }

    /**
     * The former <code>testLargeDiscrepancy</code>, re-pinned. Same CRSs, same points, PROJ 9.8.1
     * values, and both halves of each transform where a datum shift is involved — so that the shift is
     * actually asserted instead of being absorbed as "discrepancy".
     * <p>
     * The 7,000,000 m row is gone: it asserted <code>EPSG:26916</code> (UTM 16N, NAD83) at
     * (-86.6056, 34.579) against <b>5110899.06, 10552971.81</b>, which is the EPSG:29100 expected
     * value from {@code testGood} copy-pasted into the wrong row. proj4j's answer,
     * 536173.113353040 / 3826428.043831750, is bit-identical to cs2cs and is already asserted at 0.1 m
     * by {@code CoordinateTransformTest.testPROJ4()}; it is re-asserted at 1e-7 m below.
     */
    @Test
    public void testDatumShiftedProjections() {
        // EPSG:26916 -- the 7,000 km row. NAD83, so fromGeo and fromWGS84 coincide.
        checkTransformFromGeo("EPSG:26916", -86.6056, 34.579, 536173.113353040, 3826428.043831750, 1.0e-7);

        // EPSG:2736  UTM 36S / clrk66, +towgs84=-80,-100,-228
        checkTransformFromGeo("EPSG:2736", 34.0, -21.0, 603934.388709571, 7677664.393970920, 1.0e-6);
        checkTransformFromWGS84("EPSG:2736", 34.0, -21.0, 603973.158165756, 7677761.994978547, 1.0e-6);

        // EPSG:27700 OSGB36 (tmerc, Airy 1830), 7-param.
        //
        // The fromWGS84 row was RE-PINNED. It used to expect 343733.137100357, 612144.531117884
        // at 1.0e-3 m, and the comment here used to attribute the residual to "the
        // Evenden/Snyder-vs-Poder/Engsager tmerc series difference". BOTH were wrong, and the
        // second is what made the first survive:
        //
        //   * The old expected pair is bit-for-bit `cct` 9.8.1 on the EPSG:1314 Helmert
        //     (+rx=0.15 +ry=0.247 +rz=0.842 +s=-20.489) -- the rounded realisation that
        //     `projinfo -s EPSG:4326 -t EPSG:27700` emits. Proj4J does not implement EPSG:1314.
        //     It implements +datum=OSGB36, which PROJ 9.8.1 defines in src/datums.cpp:59 as
        //     446.448,-125.157,542.060,0.1502,0.2470,0.8421,-20.4894 -- and Datum.OSGB36 now
        //     carries exactly those. `cct` on THAT Helmert gives
        //     343733.140404274571  612144.531378557556, which Proj4J matches to 2.6e-10 m.
        //     The 3.3 mm was two different published realisations of one datum shift, not error.
        //
        //   * It is not tmerc. Measured at this point on the identical pipeline, +approx and the
        //     exact Poder/Engsager tmerc differ by 1.6e-9 m in easting -- six orders of magnitude
        //     below the 3.3 mm the comment blamed on them. Testing the two causes separately is
        //     what separated them; the tmerc leg is common to both the fromGeo and fromWGS84 rows
        //     and the fromGeo row never moved.
        //
        // fromGeo is a pure tmerc forward with no datum shift, so it was untouched by the
        // Datum.OSGB36 correction. `cct` exact: 343642.039538393845  612147.040453253896.
        // Tolerances are set just above the measured Proj4J-vs-PROJ residual so that any *growth*
        // fails the build.
        checkTransformFromGeo("EPSG:27700", -2.89, 55.4, 343642.039538393845, 612147.040453253896, 1.0e-8);
        checkTransformFromWGS84("EPSG:27700", -2.89, 55.4, 343733.140404274571, 612144.531378557556, 1.0e-8);

        // EPSG:27492 Datum 73 / Modified Portuguese Grid, +towgs84=-223.237,110.193,36.649
        // Old expected 25260.493584, -9579.245052 is 0.29 m / 89.68 m from PROJ; provenance unknown.
        checkTransformFromGeo("EPSG:27492", -7.84, 39.58, 25260.784714825, -9668.929128974, 1.0e-6);
        checkTransformFromWGS84("EPSG:27492", -7.84, 39.58, 25182.361038954, -9758.235701918, 1.0e-6);

        // EPSG:28992 Amersfoort / RD New, 7-param
        // Old expected 148312.15, 457804.79 is 0.09 m / 64.49 m from PROJ's same-datum answer.
        checkTransformFromGeo("EPSG:28992", 5.29, 52.11, 148312.237260560, 457869.280548637, 1.0e-6);
        checkTransformFromWGS84("EPSG:28992", 5.29, 52.11, 148341.233055144, 457978.577094236, 1.0e-6);

        // EPSG:31285 MGI / M31, +datum=hermannskogel
        checkTransformFromGeo("EPSG:31285", 13.33333333333, 47.5, 449999.999999751, 5262298.750217431, 1.0e-5);
        checkTransformFromWGS84("EPSG:31285", 13.33333333333, 47.5, 450055.697319185, 5262356.325307687, 1.0e-5);

        // EPSG:21781 CH1903 / LV03 (somerc), +towgs84=674.374,15.056,405.346
        checkTransformFromGeo("EPSG:21781", 8.23, 46.82, 660309.341946539, 185586.295802115, 1.0e-6);
        checkTransformFromWGS84("EPSG:21781", 8.23, 46.82, 660389.515487420, 185731.630395966, 1.0e-6);

        // EPSG:31466 -- see FeatureTest.testDatumConversion for why PROJ's +datum=potsdam differs by
        // 1.602 m: it is now nadgrids=@BETA2007.gsb, not a Helmert.
        checkTransformFromGeo("EPSG:31466", 6.685, 51.425, 2547638.715922100, 5699005.049480184, 1.0e-5);
    }

    /**
     * <code>EPSG:</code> is not a synonym for "any authority".
     * <p>
     * This replaces <code>xtestUnknownCRS</code>, which was dead (both an <code>x</code> prefix and an
     * {@code @Ignore} whose reason was the open question <i>"Should these expect
     * UnknownAuthoriyCode exceptions?"</i>). Answer: yes. Every code it listed is an
     * <b>ESRI</b> code — 102026, 42304 and the 540xx Sphere/World family are not EPSG codes and are
     * not in {@code nad/epsg}. Looking them up under the EPSG authority must fail, and the test now
     * asserts that it does, which is a fail-closed assertion rather than a suppressed one.
     * <p>
     * The projections themselves are reachable and already covered under their real authority — e.g.
     * ESRI:54030 in {@code CoordinateTransformTest.testRobinson()} and ESRI:54032 in
     * {@code testEquidistantAzimuthal()}.
     */
    @Test
    public void testEpsgAuthorityDoesNotResolveEsriCodes() {
        CRSFactory factory = new CRSFactory();
        String[] esriOnlyCodes = { "EPSG:102026", "EPSG:42304", "EPSG:54003", "EPSG:54008",
                                   "EPSG:54009", "EPSG:54029", "EPSG:54032" };
        for (String code : esriOnlyCodes) {
            try {
                factory.createFromName(code);
                fail(code + " resolved under the EPSG authority, but it is an ESRI-only code. "
                        + "A silent resolution here would mean the registry had acquired a "
                        + "definition under the wrong authority.");
            } catch (UnknownAuthorityCodeException expected) {
                // correct: fail closed
            }
        }
    }

    // xtestNotImplemented deleted -- both of its rows are now covered, and neither was "not
    // implemented":
    //   * EPSG:2057 (omerc +gamma) is asserted at 1e-5 m by FeatureTest.testGamma; it matches PROJ
    //     9.8.1 to 4e-9 m.
    //   * EPSG:27563 (+pm=paris) is asserted at 1e-6 m by FeatureTest.testPrimeMeridian; +pm works.
    //     Its old expected value, 653704.865208, matches neither PROJ configuration -- see that test.
}

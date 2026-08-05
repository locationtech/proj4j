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

import org.junit.Ignore;
import org.junit.Test;

/**
 * Tests from the PROJ4 testvarious file.
 * <p>
 * Two methods that were disabled by naming convention (<code>XXX_testRSOBorneo</code>,
 * <code>FAIL_testPconic</code>) are now {@code @Test @Ignore} with a measured reason, and
 * {@link #testInverseFlatteningParameter()} was added to keep the working half of RSO Borneo —
 * the repository's only use of <code>+rf=</code> — actually running.
 *
 * @author Martin Davis
 */
public class Proj4VariousTest extends BaseCoordinateTransformTest {

    @Test
    public void testRawEllipse() {
        checkTransform(
                "+proj=latlong +ellps=clrk66", p("79d58'00.000W 37d02'00.000N"),
                "+proj=latlong +ellps=bessel", p("79d58'W 37d2'N"), 0.01);
        checkTransform(
                "+proj=latlong +ellps=clrk66", p("79d58'00.000\"W 36d58'00.000\"N"),
                "+proj=latlong +ellps=bessel", p("79d58'W 36d58'N"), 0.01);
    }

    @Test
    public void testNAD27toRawEllipse() {
        checkTransform(
                "+proj=latlong +datum=NAD27", p("79d00'00.000\"W 35d00'00.000\"N"),
                "+proj=latlong +ellps=bessel", p("79dW 35dN"), 0.01);
    }

    @Test
    public void test3ParamApproxSameEllipsoid() {
        checkTransform(
                "+proj=latlong +ellps=bessel +towgs84=5,0,0", p("0d00'00.000W 0d00'00.000N"),
                "+proj=latlong +ellps=bessel +towgs84=1,0,0", p("0dE  0dN 4.000"), 1e-5);
        checkTransform(
                "+proj=latlong +ellps=bessel +towgs84=5,0,0", p("79d00'00.000W 45d00'00.000N 0.0"),
                "+proj=latlong +ellps=bessel +towgs84=1,0,0", p("78d59'59.821W  44d59'59.983N 0.540"), 1e-5);
    }

    @Test
    public void test3ParamToRawSameEllipsoid() {
        checkTransform(
                "+proj=latlong +ellps=bessel +towgs84=5,0,0", p("0d00'00.000W 0d00'00.000N"),
                "+proj=latlong +ellps=bessel", p("0dE  0dN 4.000"), 1e-5);
    }

    @Test
    public void test3ParamToRawSameEllipsoid2() {
        checkTransform(
                "+proj=latlong +ellps=bessel +towgs84=5,0,0", p("79d00'00.000W 45d00'00.000N 0.0"),
                "+proj=latlong +ellps=bessel", p("79dW  45dN 0.000"), 1e-5);
    }

    @Test
    public void testStere() {
        checkTransform(
                "+proj=latlong +datum=WGS84", p("105 40"),
                "+proj=stere +lat_0=90 +lon_0=0 +lat_ts=70 +datum=WGS84", p("5577808.93 1494569.40 0.00"), 1e-2);
    }

    @Test
    public void testStereWithout_lat_ts() {
        checkTransform(
                "+proj=latlong +datum=WGS84", p("20 45"),
                "+proj=stere +lat_0=40 +lon_0=10  +datum=WGS84", p("789468.08 602385.33 0.00"), 1e-2);
    }

    @Test
    public void testSTS() {
        checkTransform(
                "+proj=latlong +datum=WGS84", p("4.897000 52.371000"),
                "+proj=kav5 +ellps=WGS84 +units=m", p("383646.09  5997047.89"), 1e-2);
        checkTransform(
                "+proj=kav5 +ellps=WGS84 +units=m", p("383646.088858 5997047.888175"),
                "+proj=latlong +datum=WGS84", p("4d53'49.2E  52d22'15.6N"),
                1e-5);
    }

    /**
     * RSO Borneo — the repository's <b>sole witness</b> for both <code>+rf=</code> and a
     * <code>omerc +gamma</code> where <code>gamma != alpha</code>. Was
     * <code>XXX_testRSOBorneo</code>, disabled by naming convention with the comment "gamma param not
     * implemented".
     * <p>
     * <b>Checked, not assumed. Two separate answers, and the diagnosis is exact.</b>
     * <p>
     * <b><code>+rf</code> is fixed.</b> Substituting <code>tmerc</code> for <code>omerc</code> on the
     * identical ellipsoid gives 114756.998365773 / 653080.606676521 against cs2cs's
     * 114756.998365829 / 653080.606676522 — agreement to 5.6e-8 m. The transposed
     * <code>+rf</code>/<code>+f</code> setter no longer produces the −89400 eccentricity, and this
     * assertion is the only place in the repo where that is proved: <code>+rf=</code> appears in no
     * registry file and no other test.
     * <p>
     * <b><code>+gamma</code> is not.</b> Measured error at this point:
     * <pre>
     *   proj4j                             707102.683838318   655878.899422022
     *   cs2cs 9.8.1                        704570.396561578   653979.683964950
     *   error                                   2532.287 m         1899.215 m   (3165.598 m resultant)
     * </pre>
     * Root cause, isolated to one line and confirmed numerically:
     * {@code ObliqueMercatorProjection.initialize():161-162} computes
     * {@code u_0 = |al * atan(sqrt(d*d - 1) / cosrot) / bl} using
     * <code>cosrot = cos(Gamma)</code>, where PROJ 9.8.1's {@code omerc.cpp} uses
     * <code>cos(alpha)</code>. Evaluating both forms for these parameters gives
     * {@code u_0(cos alpha) = 0.115738049}, {@code u_0(cos Gamma) = 0.115241701}; the difference,
     * multiplied by <code>a</code> and resolved through the rotation, is
     * (−2532.29, −1899.22) — reproducing both observed components to better than 0.01 m.
     * <p>
     * That also explains why the two <code>+gamma</code> cases in {@link FeatureTest#testGamma()}
     * pass: EPSG:2057 has <code>gamma == alpha</code> (so the two forms coincide) and EPSG:3375 has
     * <code>+no_uoff</code> (so <code>u_0</code> is forced to 0 and the line never runs). This CRS is
     * the only configuration in the repository that reaches it.
     * <p>
     * <b>Second, independent defect found while isolating the first:</b>
     * {@code ObliqueMercatorProjection.java:36} declares {@code Gamma} as a plain {@code double},
     * so it defaults to <b>0.0, not NaN</b>. The absence test at {@code :76}
     * ({@code gzi = Double.isNaN(Gamma) ? 0 : 1}) is therefore <em>always</em> 1 and the
     * {@code Gamma = alpha} default at {@code :125} is dead code. Any {@code +proj=omerc +alpha=…}
     * with no {@code +gamma} gets zero rotation: on these parameters proj4j returns
     * 490035.358739 / 956682.251445 where PROJ returns 705254.125376 / 653608.753620 — an error of
     * <b>215,218.8 m E and 303,073.5 m N</b>. Proof that {@code Gamma} is simply never defaulted:
     * adding {@code +gamma=53d18'56.9537} (= alpha) explicitly yields 705254.125376386 /
     * 653608.753619929, bit-identical to PROJ's no-gamma answer.
     * <p>
     * <b>Both fixed, and this test is live again.</b> {@code Gamma} is {@code NaN} when
     * {@code +gamma} is absent and {@code u_0} uses {@code cos(alpha_c)}, so the whole row now
     * reproduces {@code cs2cs} 9.8.1 to <b>4.7e-10 m easting and 2.1e-09 m northing</b> — the
     * 3,165.598 m resultant above is gone. The {@code +alpha}-without-{@code +gamma} case that was
     * 215 km out now matches PROJ to 3.9e-07 m. Both are held down separately, with all four
     * gamma/alpha combinations, by
     * {@code org.locationtech.proj4j.omerc.ObliqueMercatorParameterTest}.
     */
    @Test
    public void testRSOBorneo() {
        checkTransform(
                "+proj=latlong +a=6377298.556 +rf=300.8017", p("116d2'11.12630 5d54'19.90183"),
                "+proj=omerc +a=6377298.556 +rf=300.8017 +lat_0=4 +lonc=115 +alpha=53d18'56.9537 +gamma=53d7'48.3685  +k_0=0.99984 +x_0=590476.87 +y_0=442857.65",
                p("704570.396561578  653979.683964950"), 1e-6);
    }

    /**
     * Proves the half of RSO Borneo that <em>does</em> work, so that {@code +rf=} — which occurs
     * nowhere else in this repository, in no registry file and no test CSV — is not left entirely
     * behind an {@code @Ignore}.
     * <p>
     * Same ellipsoid ({@code +a=6377298.556 +rf=300.8017}, i.e. Everest 1830 / RSO), same point,
     * {@code tmerc} instead of {@code omerc}. cs2cs 9.8.1: 114756.998365829, 653080.606676522.
     * A transposed {@code +rf} setter would give {@code es = 300.8017 * (2 - 300.8017) = -89880},
     * hence a non-finite projection and (per the historic failure mode) a return of {@code +x_0}
     * exactly — so this assertion fails loudly if the transposition ever returns.
     */
    @Test
    public void testInverseFlatteningParameter() {
        checkTransform(
                "+proj=latlong +a=6377298.556 +rf=300.8017", p("116d2'11.12630 5d54'19.90183"),
                "+proj=tmerc +a=6377298.556 +rf=300.8017 +lon_0=115 +k=1",
                p("114756.998365829  653080.606676522"), 1e-6);
    }

    /**
     * <code>+proj=pconic</code>. Was <code>FAIL_testPconic</code>, disabled by naming convention.
     * <p>
     * <b>Still broken, and comprehensively so.</b> Measured against cs2cs 9.8.1, which reproduces the
     * test's original expected values exactly (−2240096.398139611, −6940342.146955061), confirming
     * they were right and that it is proj4j that is wrong:
     * <pre>
     *   forward   proj4j  -2805943.988849225  -9419873.546192560
     *             cs2cs   -2240096.398139611  -6940342.146955061
     *             error         565,847.591 m       2,479,531.399 m
     *
     *   inverse   proj4j       120.000000000       -20.031648108
     *             cs2cs        -70.400000007       -23.650000006
     *             error            190.400 deg           3.618 deg
     * </pre>
     * The inverse returning exactly <b>120.000000000</b> is the tell: that is not a numerical error
     * but a clamp — {@code Projection.projectInverse} hard-clamps longitude to ±π rather than
     * signalling a domain error, so an out-of-domain inverse is returned as a plausible coordinate.
     * <p>
     * Consistent with {@code SimpleConicProjection}'s {@code FIXME}, which hard-codes
     * {@code p1 = 30}/{@code p2 = 60} and therefore ignores the {@code +lat_1=20n +lat_2=60n} given
     * here entirely.
     */
    @Ignore("pconic: forward 565,848 m E / 2,479,531 m N off PROJ 9.8.1; inverse returns "
            + "(120.0, -20.03) for a point whose true inverse is (-70.4, -23.65) -- the 120.0 is "
            + "Projection.projectInverse's +-pi longitude clamp, not arithmetic. SimpleConicProjection "
            + "hard-codes p1=30/p2=60 and ignores +lat_1/+lat_2.")
    @Test
    public void testPconic() {
        checkTransform(
                "+proj=latlong +datum=WGS84", p("-70.4 -23.65"),
                "+proj=pconic  +units=m +lat_1=20n +lat_2=60n +lon_0=60W +datum=WGS84",
                p("-2240096.398139611  -6940342.146955061"), 1e-6);
        checkTransform(
                "+proj=pconic  +units=m +lat_1=20n +lat_2=60n +lon_0=60W +datum=WGS84",
                p("-2240096.398139611  -6940342.146955061"),
                "+proj=latlong +datum=WGS84", p("-70.4 -23.65"), 1e-8);
    }


    @Test
    public void testExtendedTransverseMercator() {
        //checkTransform("+proj=etmerc +k=0.998 +lon_0=-20 +datum=WGS84 +x_0=10000 +y_0=20000", p("10000 20000"), "+proj=latlong +datum=WGS84", p("0dN 0.000"), 1e-3);
        checkTransform("+proj=etmerc +k=0.998 +lon_0=-20 +datum=WGS84 +x_0=10000 +y_0=20000", p("500000 2000000"), "+proj=latlong +datum=WGS84", p("15d22'16.108\"W 17d52'53.478\"N 0.000"), 1e-6);
        checkTransform("+proj=etmerc +k=0.998 +lon_0=-20 +datum=WGS84 +x_0=10000 +y_0=20000", p("1000000 2000000"), "+proj=latlong +datum=WGS84", p("10d40'55.532\"W 17d42'48.526\"N 0.000"), 1e-6);
        checkTransform("+proj=etmerc +k=0.998 +lon_0=-20 +datum=WGS84 +x_0=10000 +y_0=20000", p("2000000 2000000"), "+proj=latlong +datum=WGS84", p("1d32'21.33\"W 17d3'47.233\"N 0.000"), 1e-6);
        checkTransform("+proj=etmerc +k=0.998 +lon_0=-20 +datum=WGS84 +x_0=10000 +y_0=20000", p("4000000 2000000"), "+proj=latlong +datum=WGS84", p("15d4'42.357\"E 14d48'56.372\"N 0.000"), 1e-6);
        checkTransform("+proj=etmerc +k=0.9996 +lon_0=15 +datum=WGS84 +x_0=500000 +y_0=0", p("1096230.08 7876510.42"), "+proj=latlong +datum=WGS84", p("30.9967055 70.2838512 0.000"), 1e-6);
        
        //checkTransform("+proj=latlong +datum=WGS84", p("0dN 0.000"), "+proj=etmerc +k=0.998 +lon_0=-20 +datum=WGS84 +x_0=10000 +y_0=20000", p("10000 20000"), 50);
        checkTransform("+proj=latlong +datum=WGS84", p("15d22'16.108\"W 17d52'53.478\"N 0.000"), "+proj=etmerc +k=0.998 +lon_0=-20 +datum=WGS84 +x_0=10000 +y_0=20000", p("500000 2000000"), 0.1);
        checkTransform("+proj=latlong +datum=WGS84", p("10d40'55.532\"W 17d42'48.526\"N 0.000"), "+proj=etmerc +k=0.998 +lon_0=-20 +datum=WGS84 +x_0=10000 +y_0=20000", p("1000000 2000000"), 0.1);
        checkTransform("+proj=latlong +datum=WGS84", p("1d32'21.33\"W 17d3'47.233\"N 0.000"), "+proj=etmerc +k=0.998 +lon_0=-20 +datum=WGS84 +x_0=10000 +y_0=20000", p("2000000 2000000"), 0.1);
        checkTransform("+proj=latlong +datum=WGS84", p("15d4'42.357\"E 14d48'56.372\"N 0.000"), "+proj=etmerc +k=0.998 +lon_0=-20 +datum=WGS84 +x_0=10000 +y_0=20000", p("4000000 2000000"), 0.1);
        checkTransform("+proj=latlong +datum=WGS84", p("30.9967055 70.2838512 0.000"), "+proj=etmerc +k=0.9996 +lon_0=15 +datum=WGS84 +x_0=500000 +y_0=0", p("1096230.08 7876510.42"), 0.1);
    }


}
  
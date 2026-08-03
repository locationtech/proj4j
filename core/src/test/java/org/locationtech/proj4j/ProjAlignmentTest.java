/*******************************************************************************
 * Copyright 2026 Proj4J contributors
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
package org.locationtech.proj4j;

import org.junit.Test;

/**
 * Regression tests pinning proj4j output to PROJ.
 *
 * <p>Every expected value below was produced by running the exact definition string of the test
 * as a raw PROJ pipeline ({@code +proj=pipeline +step +proj=unitconvert +xy_in=deg +xy_out=rad
 * +step <definition>}), cross-checked on PROJ 9.5.1 and 9.6.0. Raw pipelines matter: feeding the
 * same string through PROJ's CRS machinery can rewrite it into an EPSG method with different
 * parameter semantics (Oblique Mercator with {@code +gamma} becomes "Hotine Oblique Mercator
 * variant B" with the azimuth dropped), which is not what {@code +proj=omerc} computes.
 *
 * <p>The cases cover the projections realigned with PROJ: parameters that used to be ignored or
 * hardcoded, iterations that ran on the wrong variable, and sign/scale typos.
 *
 * <p>The ellipsoidal Equidistant Cylindrical values come from PROJ's own gie suite
 * (test/gie/builtins.gie, EPSG:1028 method added in PROJ 9.8.0), because releases before 9.8
 * used the spherical formula for the ellipsoidal case.
 */
public class ProjAlignmentTest extends BaseCoordinateTransformTest {

    /** PROJ agreement is far tighter than this; the margin absorbs mlfn series rounding. */
    private static final double TOL = 1e-3;

    @Test
    public void testMercatorSphere() {
        final String DEF = "+proj=merc +lon_0=0 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-40.0000000000 -10"), DEF, p("-4447797.065782 -1117637.960712"), TOL);
        checkTransform(GEO, p("-10.0000000000 35"), DEF, p("-1111949.266446 4159221.849395"), TOL);
        checkTransform(GEO, p("0.0000000000 -60"), DEF, p("0.000000 -8390338.761308"), TOL);
        checkTransform(GEO, p("40.0000000000 10"), DEF, p("4447797.065782 1117637.960712"), TOL);
    }

    @Test
    public void testLambertAzimuthalEqualAreaSphere() {
        final String DEF = "+proj=laea +lat_0=40 +lon_0=-100 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-140.0000000000 -10"), DEF, p("-4710116.184521 -4597934.197378"), TOL);
        checkTransform(GEO, p("-110.0000000000 35"), DEF, p("-909275.352809 -505995.590365"), TOL);
        checkTransform(GEO, p("-100.0000000000 -60"), DEF, p("0.000000 -9760938.294222"), TOL);
        checkTransform(GEO, p("-60.0000000000 10"), DEF, p("4387921.872856 -2439272.668786"), TOL);
    }

    @Test
    public void testCylindricalEqualAreaSphere() {
        final String DEF = "+proj=cea +lat_ts=30 +lon_0=0 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-40.0000000000 -10"), DEF, p("-3851905.249845 -1277459.685457"), TOL);
        checkTransform(GEO, p("-10.0000000000 35"), DEF, p("-962976.312461 4219570.765504"), TOL);
        checkTransform(GEO, p("0.0000000000 -60"), DEF, p("0.000000 -6371000.000000"), TOL);
        checkTransform(GEO, p("40.0000000000 10"), DEF, p("3851905.249845 1277459.685457"), TOL);
    }

    @Test
    public void testStereographicSphere() {
        final String DEF = "+proj=stere +lat_0=40 +lon_0=-100 +k_0=1 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-140.0000000000 -10"), DEF, p("-5500937.101808 -5369919.939798"), TOL);
        checkTransform(GEO, p("-110.0000000000 35"), DEF, p("-912322.705734 -507691.388164"), TOL);
        checkTransform(GEO, p("-100.0000000000 -60"), DEF, p("0.000000 -15185324.276835"), TOL);
        checkTransform(GEO, p("-60.0000000000 10"), DEF, p("4774096.704593 -2653949.625150"), TOL);
    }

    @Test
    public void testLambertConformalConicSphere() {
        final String DEF = "+proj=lcc +lat_1=33 +lat_2=45 +lat_0=39 +lon_0=-96 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-136.0000000000 -10"), DEF, p("-5927621.631638 -4775898.419053"), TOL);
        checkTransform(GEO, p("-106.0000000000 35"), DEF, p("-906294.972284 -392804.797003"), TOL);
        checkTransform(GEO, p("-96.0000000000 -60"), DEF, p("0.000000 -20762875.225283"), TOL);
        checkTransform(GEO, p("-56.0000000000 10"), DEF, p("4751303.076780 -2278256.655533"), TOL);
    }

    @Test
    public void testObliqueStereographicAlternativeSphere() {
        final String DEF = "+proj=sterea +lat_0=52.15616055555555 +lon_0=5.38763888888889 +k_0=0.9999079 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-34.6123611111 -10"), DEF, p("-6083705.431847 -6749305.230594"), TOL);
        checkTransform(GEO, p("-4.6123611111 35"), DEF, p("-930406.055790 -1865136.102128"), TOL);
        checkTransform(GEO, p("5.3876388889 -60"), DEF, p("0.000000 -18944690.031437"), TOL);
        checkTransform(GEO, p("45.3876388889 10"), DEF, p("5040878.145904 -3895665.498632"), TOL);
    }

    @Test
    public void testSwissObliqueMercatorSphere() {
        final String DEF = "+proj=somerc +lat_0=46.95240555555556 +lon_0=7.439583333333333 +k_0=1 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-32.5604166667 -10"), DEF, p("-6503836.487245 -5163437.185046"), TOL);
        checkTransform(GEO, p("-2.5604166667 35"), DEF, p("-927819.273926 -1278372.353807"), TOL);
        checkTransform(GEO, p("7.4395833333 -60"), DEF, p("0.000000 -12128037.778315"), TOL);
        checkTransform(GEO, p("47.4395833333 10"), DEF, p("4959597.441457 -2951740.787030"), TOL);
    }

    @Test
    public void testAzimuthalEquidistantSphere() {
        final String DEF = "+proj=aeqd +lat_0=40 +lon_0=-100 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-140.0000000000 -10"), DEF, p("-4949660.436333 -4831773.165260"), TOL);
        checkTransform(GEO, p("-110.0000000000 35"), DEF, p("-910289.098761 -506559.721989"), TOL);
        checkTransform(GEO, p("-100.0000000000 -60"), DEF, p("0.000000 -11119492.664456"), TOL);
        checkTransform(GEO, p("-60.0000000000 10"), DEF, p("4510200.066531 -2507247.866263"), TOL);
    }

    @Test
    public void testGeostationarySatelliteSphere() {
        final String DEF = "+proj=geos +h=35785831 +lon_0=0 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-40.0000000000 -10"), DEF, p("-3849123.294923 -1053537.200487"), TOL);
        checkTransform(GEO, p("-10.0000000000 35"), DEF, p("-875915.061736 3520231.189118"), TOL);
        checkTransform(GEO, p("0.0000000000 -60"), DEF, p("0.000000 -5033004.582601"), TOL);
        checkTransform(GEO, p("40.0000000000 10"), DEF, p("3849123.294923 1053537.200487"), TOL);
    }

    @Test
    public void testObliqueMercatorSphere() {
        final String DEF = "+proj=omerc +lat_0=-20 +lonc=140 +alpha=30 +gamma=30 +k=1 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("100.0000000000 -10"), DEF, p("-4730117.199625 552755.718062"), TOL);
        checkTransform(GEO, p("130.0000000000 35"), DEF, p("-565722.835082 6551025.744486"), TOL);
        checkTransform(GEO, p("140.0000000000 -60"), DEF, p("-163381.153908 -4528627.712887"), TOL);
        checkTransform(GEO, p("180.0000000000 10"), DEF, p("4516712.276856 3345669.316040"), TOL);
    }

    @Test
    public void testCassiniEllipsoidal() {
        final String DEF = "+proj=cass +lat_0=40 +lon_0=-100 +units=m +no_defs +ellps=GRS80";
        final String GEO = "+proj=latlong +ellps=GRS80";
        checkTransform(GEO, p("-140.0000000000 -10"), DEF, p("-4372795.965252 -5853655.505405"), TOL);
        checkTransform(GEO, p("-110.0000000000 35"), DEF, p("-911345.180793 -508889.650875"), TOL);
        checkTransform(GEO, p("-100.0000000000 -60"), DEF, p("0.000000 -11083601.849604"), TOL);
        checkTransform(GEO, p("-60.0000000000 10"), DEF, p("4372795.965252 -3005402.555068"), TOL);
    }

    @Test
    public void testCassiniSphere() {
        final String DEF = "+proj=cass +lat_0=40 +lon_0=-100 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-140.0000000000 -10"), DEF, p("-4367008.414885 -5889158.967315"), TOL);
        checkTransform(GEO, p("-110.0000000000 35"), DEF, p("-909322.395016 -510030.729443"), TOL);
        checkTransform(GEO, p("-100.0000000000 -60"), DEF, p("0.000000 -11119492.664456"), TOL);
        checkTransform(GEO, p("-60.0000000000 10"), DEF, p("4367008.414885 -3006435.164250"), TOL);
    }

    @Test
    public void testPolyconicEllipsoidal() {
        final String DEF = "+proj=poly +lat_0=40 +lon_0=-100 +units=m +no_defs +ellps=GRS80";
        final String GEO = "+proj=latlong +ellps=GRS80";
        checkTransform(GEO, p("-140.0000000000 -10"), DEF, p("-4374840.323011 -5800888.519586"), TOL);
        checkTransform(GEO, p("-110.0000000000 35"), DEF, p("-911357.703482 -509280.907751"), TOL);
        checkTransform(GEO, p("-100.0000000000 -60"), DEF, p("0.000000 -11083601.849604"), TOL);
        checkTransform(GEO, p("-60.0000000000 10"), DEF, p("4374840.323011 -3058169.540887"), TOL);
    }

    @Test
    public void testRectangularPolyconic() {
        final String DEF = "+proj=rpoly +lat_ts=40 +lon_0=0 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-40.0000000000 -10"), DEF, p("-4438371.900094 -1385587.136331"), TOL);
        checkTransform(GEO, p("-10.0000000000 35"), DEF, p("-909528.535900 3937395.770822"), TOL);
        checkTransform(GEO, p("0.0000000000 -60"), DEF, p("0.000000 -6671695.598674"), TOL);
        checkTransform(GEO, p("40.0000000000 10"), DEF, p("4438371.900094 1385587.136331"), TOL);
    }

    @Test
    public void testEuler() {
        final String DEF = "+proj=euler +lat_1=20 +lat_2=60 +lon_0=0 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-40.0000000000 -10"), DEF, p("-5565588.554796 131726.310845"), TOL);
        checkTransform(GEO, p("-10.0000000000 35"), DEF, p("-885446.379475 3940537.024276"), TOL);
        checkTransform(GEO, p("0.0000000000 -60"), DEF, p("0.000000 -6671695.598674"), TOL);
        checkTransform(GEO, p("40.0000000000 10"), DEF, p("4618960.918382 2144093.276031"), TOL);
    }

    @Test
    public void testMurdoch1() {
        final String DEF = "+proj=murd1 +lat_1=20 +lat_2=60 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-40.0000000000 -10"), DEF, p("-5639553.289443 175097.959007"), TOL);
        checkTransform(GEO, p("-10.0000000000 35"), DEF, p("-895102.443414 3942084.859019"), TOL);
        checkTransform(GEO, p("0.0000000000 -60"), DEF, p("0.000000 -6671695.598674"), TOL);
        checkTransform(GEO, p("40.0000000000 10"), DEF, p("4674737.144164 2178808.140247"), TOL);
    }

    @Test
    public void testMurdoch2() {
        final String DEF = "+proj=murd2 +lat_1=20 +lat_2=60 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-40.0000000000 -10"), DEF, p("-6301388.378708 -854149.432091"), TOL);
        checkTransform(GEO, p("-10.0000000000 35"), DEF, p("-859353.683320 4835287.855980"), TOL);
        checkTransform(GEO, p("0.0000000000 -60"), DEF, p("0.000000 41477640.223015"), TOL);
        checkTransform(GEO, p("40.0000000000 10"), DEF, p("4651804.606758 2695655.571591"), TOL);
    }

    @Test
    public void testMurdoch3() {
        final String DEF = "+proj=murd3 +lat_1=20 +lat_2=60 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-40.0000000000 -10"), DEF, p("-5683305.299005 214163.430069"), TOL);
        checkTransform(GEO, p("-10.0000000000 35"), DEF, p("-896367.720028 3943248.073242"), TOL);
        checkTransform(GEO, p("0.0000000000 -60"), DEF, p("0.000000 -6671695.598674"), TOL);
        checkTransform(GEO, p("40.0000000000 10"), DEF, p("4699066.609065 2208404.861245"), TOL);
    }

    @Test
    public void testPerspectiveConic() {
        final String DEF = "+proj=pconic +lat_1=20 +lat_2=60 +lon_0=0 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-40.0000000000 -10"), DEF, p("-6190696.041636 -698434.389923"), TOL);
        checkTransform(GEO, p("-10.0000000000 35"), DEF, p("-857392.520808 4547875.689076"), TOL);
        checkTransform(GEO, p("0.0000000000 -60"), DEF, p("0.000000 38976232.445180"), TOL);
        checkTransform(GEO, p("40.0000000000 10"), DEF, p("4594902.982649 2615675.572982"), TOL);
    }

    @Test
    public void testVitkovsky1() {
        final String DEF = "+proj=vitk1 +lat_1=20 +lat_2=60 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-40.0000000000 -10"), DEF, p("-5791807.623849 268352.385725"), TOL);
        checkTransform(GEO, p("-10.0000000000 35"), DEF, p("-914749.689995 3945386.219458"), TOL);
        checkTransform(GEO, p("0.0000000000 -60"), DEF, p("0.000000 -6671695.598674"), TOL);
        checkTransform(GEO, p("40.0000000000 10"), DEF, p("4788778.427251 2253209.336583"), TOL);
    }

    @Test
    public void testPutninsP2() {
        final String DEF = "+proj=putp2 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-40.0000000000 -10"), DEF, p("-4165623.497010 -1172159.735265"), TOL);
        checkTransform(GEO, p("-10.0000000000 35"), DEF, p("-904441.893961 4044941.064788"), TOL);
        checkTransform(GEO, p("0.0000000000 -60"), DEF, p("0.000000 -6725129.373954"), TOL);
        checkTransform(GEO, p("40.0000000000 10"), DEF, p("4165623.497010 1172159.735265"), TOL);
    }

    @Test
    public void testNell() {
        final String DEF = "+proj=nell +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-40.0000000000 -10"), DEF, p("-4414182.970763 -1109109.386006"), TOL);
        checkTransform(GEO, p("-10.0000000000 35"), DEF, p("-1017822.743900 3761645.527075"), TOL);
        checkTransform(GEO, p("0.0000000000 -60"), DEF, p("0.000000 -5926748.099252"), TOL);
        checkTransform(GEO, p("40.0000000000 10"), DEF, p("4414182.970763 1109109.386006"), TOL);
    }

    @Test
    public void testWagner1() {
        final String DEF = "+proj=wag1 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-40.0000000000 -10"), DEF, p("-3858040.603872 -1265725.337175"), TOL);
        checkTransform(GEO, p("-10.0000000000 35"), DEF, p("-846731.613888 4358614.437275"), TOL);
        checkTransform(GEO, p("0.0000000000 -60"), DEF, p("0.000000 -7110752.505071"), TOL);
        checkTransform(GEO, p("40.0000000000 10"), DEF, p("3858040.603872 1265725.337175"), TOL);
    }

    @Test
    public void testWagner2() {
        final String DEF = "+proj=wag2 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-40.0000000000 -10"), DEF, p("-4075521.353202 -1201232.754217"), TOL);
        checkTransform(GEO, p("-10.0000000000 35"), DEF, p("-916667.697001 4157526.318258"), TOL);
        checkTransform(GEO, p("0.0000000000 -60"), DEF, p("0.000000 -6904914.035314"), TOL);
        checkTransform(GEO, p("40.0000000000 10"), DEF, p("4075521.353202 1201232.754217"), TOL);
    }

    @Test
    public void testLoximuthal() {
        final String DEF = "+proj=loxim +lat_1=40 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-40.0000000000 -10"), DEF, p("-4136511.129062 -5559746.332228"), TOL);
        checkTransform(GEO, p("-10.0000000000 35"), DEF, p("-881558.740973 -555974.633223"), TOL);
        checkTransform(GEO, p("0.0000000000 -60"), DEF, p("0.000000 -11119492.664456"), TOL);
        checkTransform(GEO, p("40.0000000000 10"), DEF, p("3964128.047273 -3335847.799337"), TOL);
    }

    @Test
    public void testMcBrydeThomasFlatPolarParabolic() {
        final String DEF = "+proj=mbtfpp +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-40.0000000000 -10"), DEF, p("-4067371.831192 -1199857.840082"), TOL);
        checkTransform(GEO, p("-10.0000000000 35"), DEF, p("-878473.500465 4149937.264394"), TOL);
        checkTransform(GEO, p("0.0000000000 -60"), DEF, p("0.000000 -6886729.497187"), TOL);
        checkTransform(GEO, p("40.0000000000 10"), DEF, p("4067371.831192 1199857.840082"), TOL);
    }

    @Test
    public void testMcBrydeThomasFlatPolarQuartic() {
        final String DEF = "+proj=mbtfpq +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-40.0000000000 -10"), DEF, p("-4128126.541582 -1184107.674879"), TOL);
        checkTransform(GEO, p("-10.0000000000 35"), DEF, p("-915603.706840 4059479.225723"), TOL);
        checkTransform(GEO, p("0.0000000000 -60"), DEF, p("0.000000 -6629240.189416"), TOL);
        checkTransform(GEO, p("40.0000000000 10"), DEF, p("4128126.541582 1184107.674879"), TOL);
    }

    @Test
    public void testMcBrydeThomasFlatPolarSine2() {
        final String DEF = "+proj=mbt_fps +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-40.0000000000 -10"), DEF, p("-3934458.340917 -1245615.425908"), TOL);
        checkTransform(GEO, p("-10.0000000000 35"), DEF, p("-912592.405417 4209222.112965"), TOL);
        checkTransform(GEO, p("0.0000000000 -60"), DEF, p("0.000000 -6684397.416439"), TOL);
        checkTransform(GEO, p("40.0000000000 10"), DEF, p("3934458.340917 1245615.425908"), TOL);
    }

    @Test
    public void testEquidistantCylindricalSphere() {
        final String DEF = "+proj=eqc +lat_ts=30 +lat_0=0 +lon_0=0 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-40.0000000000 -10"), DEF, p("-3851905.249845 -1111949.266446"), TOL);
        checkTransform(GEO, p("-10.0000000000 35"), DEF, p("-962976.312461 3891822.432560"), TOL);
        checkTransform(GEO, p("0.0000000000 -60"), DEF, p("0.000000 -6671695.598674"), TOL);
        checkTransform(GEO, p("40.0000000000 10"), DEF, p("3851905.249845 1111949.266446"), TOL);
    }

    @Test
    public void testEquidistantConicEllipsoidal() {
        final String DEF = "+proj=eqdc +lat_1=29.5 +lat_2=45.5 +lat_0=37.5 +lon_0=-96 +units=m +no_defs +ellps=GRS80";
        final String GEO = "+proj=latlong +ellps=GRS80";
        checkTransform(GEO, p("-136.0000000000 -10"), DEF, p("-5560356.875893 -4062094.031217"), TOL);
        checkTransform(GEO, p("-106.0000000000 35"), DEF, p("-903375.180874 -229525.027675"), TOL);
        checkTransform(GEO, p("-96.0000000000 -60"), DEF, p("0.000000 -10806074.961897"), TOL);
        checkTransform(GEO, p("-56.0000000000 10"), DEF, p("4651142.583965 -2045912.238870"), TOL);
    }

    @Test
    public void testBonneEllipsoidal() {
        final String DEF = "+proj=bonne +lat_1=40 +units=m +no_defs +ellps=GRS80";
        final String GEO = "+proj=latlong +ellps=GRS80";
        checkTransform(GEO, p("-40.0000000000 -10"), DEF, p("-4304692.418309 -4810676.579880"), TOL);
        checkTransform(GEO, p("-10.0000000000 35"), DEF, p("-910981.780849 -503967.442379"), TOL);
        checkTransform(GEO, p("0.0000000000 -60"), DEF, p("0.000000 -11083601.849604"), TOL);
        checkTransform(GEO, p("40.0000000000 10"), DEF, p("4268956.019146 -2455992.210456"), TOL);
    }

    @Test
    public void testWinkelTripel() {
        final String DEF = "+proj=wintri +lat_1=50 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-40.0000000000 -10"), DEF, p("-3630394.590084 -1123306.834304"), TOL);
        checkTransform(GEO, p("-10.0000000000 35"), DEF, p("-842344.439056 3894034.327492"), TOL);
        checkTransform(GEO, p("0.0000000000 -60"), DEF, p("0.000000 -6671695.598674"), TOL);
        checkTransform(GEO, p("40.0000000000 10"), DEF, p("3630394.590084 1123306.834304"), TOL);
    }

    @Test
    public void testRobinson() {
        final String DEF = "+proj=robin +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-40.0000000000 -10"), DEF, p("-3757481.124588 -1068322.391740"), TOL);
        checkTransform(GEO, p("-10.0000000000 35"), DEF, p("-889636.709331 3739128.306900"), TOL);
        checkTransform(GEO, p("0.0000000000 -60"), DEF, p("0.000000 -6328948.789089"), TOL);
        checkTransform(GEO, p("40.0000000000 10"), DEF, p("3757481.124588 1068322.391740"), TOL);
    }

    @Test
    public void testTransverseMercatorSphere() {
        final String DEF = "+proj=tmerc +lat_0=0 +lon_0=-100 +k_0=0.9996 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-140.0000000000 -10"), DEF, p("-4753687.403062 -1440785.356772"), TOL);
        checkTransform(GEO, p("-110.0000000000 35"), DEF, p("-912060.601365 3936191.229805"), TOL);
        checkTransform(GEO, p("-100.0000000000 -60"), DEF, p("0.000000 -6669026.920434"), TOL);
        checkTransform(GEO, p("-60.0000000000 10"), DEF, p("4753687.403062 1440785.356772"), TOL);
    }

    @Test
    public void testTransverseMercatorEllipsoidal() {
        final String DEF = "+proj=tmerc +lat_0=0 +lon_0=-100 +k_0=0.9996 +units=m +no_defs +ellps=GRS80";
        final String GEO = "+proj=latlong +ellps=GRS80";
        checkTransform(GEO, p("-140.0000000000 -10"), DEF, p("-4762571.089019 -1436121.199615"), TOL);
        checkTransform(GEO, p("-110.0000000000 35"), DEF, p("-914103.307216 3919073.949813"), TOL);
        checkTransform(GEO, p("-100.0000000000 -60"), DEF, p("0.000000 -6651411.190240"), TOL);
        checkTransform(GEO, p("-60.0000000000 10"), DEF, p("4762571.089019 1436121.199615"), TOL);
    }

    @Test
    public void testBipolar() {
        final String DEF = "+proj=bipc +lon_0=0 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-40.0000000000 -10"), DEF, p("-4584907.682439 -539386.863518"), TOL);
        checkTransform(GEO, p("-10.0000000000 35"), DEF, p("1335526.215700 -10385631.800272"), TOL);
        checkTransform(GEO, p("40.0000000000 10"), DEF, p("6513158.681324 -13541757.295967"), TOL);
    }

    @Test
    public void testNearSidedPerspective() {
        final String DEF = "+proj=nsper +h=10000000 +lat_0=40 +lon_0=-100 +units=m +no_defs +ellps=GRS80";
        final String GEO = "+proj=latlong +ellps=GRS80";
        checkTransform(GEO, p("-140.0000000000 -10"), DEF, p("-3012145.435203 -2940404.431956"), TOL);
        checkTransform(GEO, p("-110.0000000000 35"), DEF, p("-899600.024832 -500611.442124"), TOL);
        checkTransform(GEO, p("-60.0000000000 10"), DEF, p("3370136.507404 -1873479.544711"), TOL);
    }

    @Test
    public void testHammer() {
        final String DEF = "+proj=hammer +W=0.5 +M=1.0 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-40.0000000000 -10"), DEF, p("-4374147.147630 -1127536.162368"), TOL);
        checkTransform(GEO, p("-10.0000000000 35"), DEF, p("-954665.060687 3834880.243598"), TOL);
        checkTransform(GEO, p("0.0000000000 -60"), DEF, p("0.000000 -6371000.000000"), TOL);
        checkTransform(GEO, p("40.0000000000 10"), DEF, p("4374147.147630 1127536.162368"), TOL);
    }

    @Test
    public void testLagrange() {
        final String DEF = "+proj=lagrng +W=2 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-40.0000000000 -10"), DEF, p("-2242308.613879 -575789.816319"), TOL);
        checkTransform(GEO, p("-10.0000000000 35"), DEF, p("-541740.920927 2065167.005061"), TOL);
        checkTransform(GEO, p("0.0000000000 -60"), DEF, p("0.000000 -4049882.178285"), TOL);
        checkTransform(GEO, p("40.0000000000 10"), DEF, p("2242308.613879 575789.816319"), TOL);
    }

    @Test
    public void testUrmaevFlatPolarSinusoidal() {
        final String DEF = "+proj=urmfps +n=0.9 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-40.0000000000 -10"), DEF, p("-3854468.171004 -1266113.773309"), TOL);
        checkTransform(GEO, p("-10.0000000000 35"), DEF, p("-835563.502846 4376428.374441"), TOL);
        checkTransform(GEO, p("0.0000000000 -60"), DEF, p("0.000000 -7210895.774003"), TOL);
        checkTransform(GEO, p("40.0000000000 10"), DEF, p("3854468.171004 1266113.773309"), TOL);
    }

    @Test
    public void testLandsat() {
        final String DEF = "+proj=lsat +lsat=2 +path=2 +units=m +no_defs +ellps=GRS80";
        final String GEO = "+proj=latlong +ellps=GRS80";
        checkTransform(GEO, p("-40.0000000000 -10"), DEF, p("21109528.165580 3177843.238687"), TOL);
        checkTransform(GEO, p("-10.0000000000 35"), DEF, p("14338233.286565 4741896.597415"), TOL);
        checkTransform(GEO, p("0.0000000000 -60"), DEF, p("29064377.065379 3608232.532453"), TOL);
        checkTransform(GEO, p("40.0000000000 10"), DEF, p("43238230.915922 7412513.162265"), TOL);
    }

    @Test
    public void testLambertEqualAreaConic() {
        final String DEF = "+proj=leac +lat_1=40 +units=m +no_defs +R=6371000";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-40.0000000000 -10"), DEF, p("-5843005.724467 894160.771416"), TOL);
        checkTransform(GEO, p("-10.0000000000 35"), DEF, p("-927486.600110 3516146.182578"), TOL);
        checkTransform(GEO, p("0.0000000000 -60"), DEF, p("0.000000 -3638799.445252"), TOL);
        checkTransform(GEO, p("40.0000000000 10"), DEF, p("4902863.948367 2349862.480660"), TOL);
    }

    @Test
    public void testMercatorLatTs() {
        final String DEF = "+proj=merc +lon_0=0 +lat_ts=42 +ellps=krass +units=m +no_defs";
        final String GEO = "+proj=latlong +ellps=krass";
        checkTransform(GEO, p("-8.0000000000 40"), DEF, p("-662817.173242 3601148.955488"), TOL);
        checkTransform(GEO, p("-2.0000000000 -12"), DEF, p("-165704.293311 -994968.238345"), TOL);
        checkTransform(GEO, p("0.0000000000 0"), DEF, p("0.000000 0.000000"), TOL);
        checkTransform(GEO, p("8.0000000000 12"), DEF, p("662817.173242 994968.238345"), TOL);
    }

    @Test
    public void testMercatorLatTsSphere() {
        final String DEF = "+proj=merc +lon_0=0 +lat_ts=-41 +R=6371000 +units=m +no_defs";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-8.0000000000 40"), DEF, p("-671359.011287 3668263.947384"), TOL);
        checkTransform(GEO, p("-2.0000000000 -12"), DEF, p("-167839.752822 -1014482.571865"), TOL);
        checkTransform(GEO, p("0.0000000000 0"), DEF, p("0.000000 0.000000"), TOL);
        checkTransform(GEO, p("8.0000000000 12"), DEF, p("671359.011287 1014482.571865"), TOL);
    }

    @Test
    public void testMercatorScaleFactor() {
        final String DEF = "+proj=merc +lon_0=0 +k_0=0.9996 +ellps=GRS80 +units=m +no_defs";
        final String GEO = "+proj=latlong +ellps=GRS80";
        checkTransform(GEO, p("-8.0000000000 40"), DEF, p("-890199.703976 4836536.009367"), TOL);
        checkTransform(GEO, p("-2.0000000000 -12"), DEF, p("-222549.925994 -1336295.474500"), TOL);
        checkTransform(GEO, p("0.0000000000 0"), DEF, p("0.000000 0.000000"), TOL);
        checkTransform(GEO, p("8.0000000000 12"), DEF, p("890199.703976 1336295.474500"), TOL);
    }

    @Test
    public void testCylindricalEqualAreaScaleFactor() {
        final String DEF = "+proj=cea +lon_0=0 +k_0=0.99 +ellps=GRS80 +units=m +no_defs";
        final String GEO = "+proj=latlong +ellps=GRS80";
        checkTransform(GEO, p("-8.0000000000 40"), DEF, p("-881650.367083 4121080.731921"), TOL);
        checkTransform(GEO, p("-2.0000000000 -12"), DEF, p("-220412.591771 -1330773.823329"), TOL);
        checkTransform(GEO, p("0.0000000000 0"), DEF, p("0.000000 0.000000"), TOL);
        checkTransform(GEO, p("8.0000000000 12"), DEF, p("881650.367083 1330773.823329"), TOL);
    }

    @Test
    public void testCylindricalEqualAreaLatTs() {
        final String DEF = "+proj=cea +lon_0=0 +lat_ts=45 +ellps=GRS80 +units=m +no_defs";
        final String GEO = "+proj=latlong +ellps=GRS80";
        checkTransform(GEO, p("-8.0000000000 40"), DEF, p("-630774.680757 5760142.965338"), TOL);
        checkTransform(GEO, p("-2.0000000000 -12"), DEF, p("-157693.670189 -1860057.585752"), TOL);
        checkTransform(GEO, p("0.0000000000 0"), DEF, p("0.000000 0.000000"), TOL);
        checkTransform(GEO, p("8.0000000000 12"), DEF, p("630774.680757 1860057.585752"), TOL);
    }

    @Test
    public void testEquidistantCylindricalLatTs() {
        final String DEF = "+proj=eqc +lat_ts=45 +lat_0=10 +lon_0=0 +R=6371000 +units=m +no_defs";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-8.0000000000 40"), DEF, p("-629013.493311 3335847.799337"), TOL);
        checkTransform(GEO, p("-2.0000000000 -12"), DEF, p("-157253.373328 -2446288.386180"), TOL);
        checkTransform(GEO, p("0.0000000000 0"), DEF, p("0.000000 -1111949.266446"), TOL);
        checkTransform(GEO, p("8.0000000000 12"), DEF, p("629013.493311 222389.853289"), TOL);
    }

    @Test
    public void testObliqueMercatorAlphaOnly() {
        final String DEF = "+proj=omerc +lat_0=-20 +lonc=140 +alpha=30 +k=1 +ellps=GRS80 +units=m +no_defs";
        final String GEO = "+proj=latlong +ellps=GRS80";
        checkTransform(GEO, p("132.0000000000 40"), DEF, p("-181571.158681 7083423.012735"), TOL);
        checkTransform(GEO, p("138.0000000000 -12"), DEF, p("-217349.122501 885969.025378"), TOL);
        checkTransform(GEO, p("140.0000000000 0"), DEF, p("19538.822390 2223266.627685"), TOL);
        checkTransform(GEO, p("148.0000000000 12"), DEF, p("970515.884590 3528572.166731"), TOL);
    }

    @Test
    public void testObliqueMercatorGammaOnly() {
        final String DEF = "+proj=omerc +lat_0=-20 +lonc=140 +gamma=30 +k=1 +ellps=GRS80 +units=m +no_defs";
        final String GEO = "+proj=latlong +ellps=GRS80";
        checkTransform(GEO, p("132.0000000000 40"), DEF, p("-422662.981565 7120436.559295"), TOL);
        checkTransform(GEO, p("138.0000000000 -12"), DEF, p("-250223.893298 877372.339351"), TOL);
        checkTransform(GEO, p("140.0000000000 0"), DEF, p("-62492.365753 2223943.117701"), TOL);
        checkTransform(GEO, p("148.0000000000 12"), DEF, p("845514.753589 3564498.149713"), TOL);
    }

    @Test
    public void testObliqueMercatorNoUoff() {
        final String DEF = "+proj=omerc +lat_0=4 +lonc=115 +alpha=53.31580995 +gamma=53.13010236 +k=0.99984 +no_uoff +ellps=GRS80 +units=m +no_defs";
        final String GEO = "+proj=latlong +ellps=GRS80";
        checkTransform(GEO, p("107.0000000000 40"), DEF, p("-64271.131967 4696229.327760"), TOL);
        checkTransform(GEO, p("113.0000000000 -12"), DEF, p("362348.959094 -1337630.240063"), TOL);
        checkTransform(GEO, p("115.0000000000 0"), DEF, p("591784.038761 429.566246"), TOL);
        checkTransform(GEO, p("123.0000000000 12"), DEF, p("1462479.435461 1336743.543334"), TOL);
    }

    @Test
    public void testLambertEqualAreaConicSouth() {
        final String DEF = "+proj=leac +lat_1=40 +south +R=6371000 +units=m +no_defs";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-8.0000000000 40"), DEF, p("-681371.414832 5997441.823984"), TOL);
        checkTransform(GEO, p("-2.0000000000 -12"), DEF, p("-118293.862854 -2345639.351903"), TOL);
        checkTransform(GEO, p("0.0000000000 0"), DEF, p("0.000000 0.000000"), TOL);
        checkTransform(GEO, p("8.0000000000 12"), DEF, p("584266.240302 2104405.499609"), TOL);
    }

    @Test
    public void testTransverseMercatorSouth() {
        final String DEF = "+proj=tmerc +lat_0=0 +lon_0=15 +k_0=0.9996 +x_0=500000 +south +ellps=GRS80 +units=m +no_defs";
        final String GEO = "+proj=latlong +ellps=GRS80";
        checkTransform(GEO, p("7.0000000000 40"), DEF, p("-183262.578000 4458528.156743"), TOL);
        checkTransform(GEO, p("13.0000000000 -12"), DEF, p("282241.145312 -1327344.064148"), TOL);
        checkTransform(GEO, p("15.0000000000 0"), DEF, p("500000.000000 0.000000"), TOL);
        checkTransform(GEO, p("23.0000000000 12"), DEF, p("1373486.033052 1339293.568932"), TOL);
    }

    @Test
    public void testLandsatPath100() {
        final String DEF = "+proj=lsat +lsat=5 +path=100 +ellps=GRS80 +units=m +no_defs";
        final String GEO = "+proj=latlong +ellps=GRS80";
        checkTransform(GEO, p("-8.0000000000 40"), DEF, p("45591791.111054 -4081312.869184"), TOL);
        checkTransform(GEO, p("-2.0000000000 -12"), DEF, p("37872960.160127 -5523114.412502"), TOL);
        checkTransform(GEO, p("0.0000000000 0"), DEF, p("39632018.797881 -6337233.926139"), TOL);
        checkTransform(GEO, p("8.0000000000 12"), DEF, p("41875112.451760 -7982907.947467"), TOL);
    }

    @Test
    public void testHammerWM() {
        final String DEF = "+proj=hammer +W=0.4 +M=1.2 +R=6371000 +units=m +no_defs";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-8.0000000000 40"), DEF, p("-870052.513300 3632912.635772"), TOL);
        checkTransform(GEO, p("-2.0000000000 -12"), DEF, p("-262471.778875 -1109944.815563"), TOL);
        checkTransform(GEO, p("0.0000000000 0"), DEF, p("0.000000 0.000000"), TOL);
        checkTransform(GEO, p("8.0000000000 12"), DEF, p("1049754.952856 1110346.189871"), TOL);
    }

    @Test
    public void testLagrangeW() {
        final String DEF = "+proj=lagrng +W=1.5 +lat_1=30 +R=6371000 +units=m +no_defs";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-8.0000000000 40"), DEF, p("-590463.205390 907670.095531"), TOL);
        checkTransform(GEO, p("-2.0000000000 -12"), DEF, p("-139136.043616 -3162219.389496"), TOL);
        checkTransform(GEO, p("0.0000000000 0"), DEF, p("0.000000 -2307357.975765"), TOL);
        checkTransform(GEO, p("8.0000000000 12"), DEF, p("585968.136375 -1433955.295168"), TOL);
    }

    @Test
    public void testUrmaevN() {
        final String DEF = "+proj=urmfps +n=0.7 +R=6371000 +units=m +no_defs";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-8.0000000000 40"), DEF, p("-697013.655872 4841371.341430"), TOL);
        checkTransform(GEO, p("-2.0000000000 -12"), DEF, p("-193043.479958 -1515104.775840"), TOL);
        checkTransform(GEO, p("0.0000000000 0"), DEF, p("0.000000 0.000000"), TOL);
        checkTransform(GEO, p("8.0000000000 12"), DEF, p("772173.919832 1515104.775840"), TOL);
    }

    @Test
    public void testWinkelTripelLat1() {
        final String DEF = "+proj=wintri +lat_1=40 +R=6371000 +units=m +no_defs";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-8.0000000000 40"), DEF, p("-710736.164758 4449351.975557"), TOL);
        checkTransform(GEO, p("-2.0000000000 -12"), DEF, p("-194744.490592 -1334372.594105"), TOL);
        checkTransform(GEO, p("0.0000000000 0"), DEF, p("0.000000 0.000000"), TOL);
        checkTransform(GEO, p("8.0000000000 12"), DEF, p("778974.030898 1334874.989755"), TOL);
    }

    @Test
    public void testRectangularPolyconicLatTs() {
        final String DEF = "+proj=rpoly +lat_ts=30 +lat_0=10 +lon_0=0 +R=6371000 +units=m +no_defs";
        final String GEO = "+proj=latlong +R=6371000";
        checkTransform(GEO, p("-8.0000000000 40"), DEF, p("-680347.775253 3366390.839719"), TOL);
        checkTransform(GEO, p("-2.0000000000 -12"), DEF, p("-217532.758976 -2447077.776817"), TOL);
        checkTransform(GEO, p("0.0000000000 0"), DEF, p("0.000000 -1111949.266446"), TOL);
        checkTransform(GEO, p("8.0000000000 12"), DEF, p("870290.477390 235027.230314"), TOL);
    }

    @Test
    public void testNearSidedPerspectiveOblique() {
        final String DEF = "+proj=nsper +h=5000000 +lat_0=-30 +lon_0=20 +ellps=GRS80 +units=m +no_defs";
        final String GEO = "+proj=latlong +ellps=GRS80";
        checkTransform(GEO, p("18.0000000000 -12"), DEF, p("-204807.844732 1852194.021510"), TOL);
        checkTransform(GEO, p("20.0000000000 0"), DEF, p("0.000000 2723600.617411"), TOL);
        checkTransform(GEO, p("28.0000000000 12"), DEF, p("648848.054039 3166605.475805"), TOL);
    }

    @Test
    public void testEquidistantConicSouth() {
        final String DEF = "+proj=eqdc +lat_1=-20 +lat_2=-40 +lat_0=-30 +lon_0=130 +ellps=GRS80 +units=m +no_defs";
        final String GEO = "+proj=latlong +ellps=GRS80";
        checkTransform(GEO, p("122.0000000000 40"), DEF, p("-1297543.511494 7704554.255150"), TOL);
        checkTransform(GEO, p("128.0000000000 -12"), DEF, p("-224657.963509 1991078.011533"), TOL);
        checkTransform(GEO, p("130.0000000000 0"), DEF, p("0.000000 3320113.397845"), TOL);
        checkTransform(GEO, p("138.0000000000 12"), DEF, p("1082190.651007 4609592.963371"), TOL);
    }

    @Test
    public void testEquidistantCylindricalEllipsoidal() {
        // PROJ test/gie/builtins.gie, "Ellipsoidal case (EPSG:1028)"
        final String GEO = "+proj=latlong +ellps=WGS84";
        final String DEF0 = "+proj=eqc +ellps=WGS84 +lat_ts=0 +units=m +no_defs";
        checkTransform(GEO, p("10 55"), DEF0, p("1113194.91 6097230.31"), 0.01);
        checkTransform(GEO, p("10 -45"), DEF0, p("1113194.90793 -4984944.37798"), TOL);
        checkTransform(GEO, p("180 30"), DEF0, p("20037508.34279 3320113.39794"), TOL);
        checkTransform(GEO, p("0 89"), DEF0, p("0.0 9890271.86440"), TOL);
        checkTransform(GEO, p("-122.4194 37.7749"), DEF0, p("-13627665.27122 4182513.19136"), TOL);

        // the nu1 * cos(lat_ts) easting scale
        final String DEF45 = "+proj=eqc +ellps=WGS84 +lat_ts=45 +units=m +no_defs";
        checkTransform(GEO, p("2 49"), DEF45, p("157693.670 5429627.632"), 0.01);
        checkTransform(GEO, p("10 70"), DEF45, p("788468.351 7768980.728"), 0.01);

        // the meridional arc offset M0 taken at lat_0
        final String DEF30 = "+proj=eqc +ellps=WGS84 +lat_ts=30 +lat_0=45 +units=m +no_defs";
        checkTransform(GEO, p("0 45"), DEF30, p("0.0 0.0"), 0.01);
        checkTransform(GEO, p("0 60"), DEF30, p("0.0 1669128.442"), 0.01);
        checkTransform(GEO, p("0 30"), DEF30, p("0.0 -1664830.980"), 0.01);
    }

    @Test
    public void testKrovakDefaults() {
        // PROJ supplies Bessel 1841, lat_0=49d30', lon_0=42d30' of Ferro - 17d40' and k_0=0.9999
        final String GEO = "+proj=latlong +ellps=bessel";
        final String DEF = "+proj=krovak +ellps=bessel +units=m +no_defs";
        checkTransform(GEO, p("14.4378 50.0755"), DEF, p("-741907.1890 -1044567.9139"), TOL);
        checkTransform(GEO, p("17.1077 48.1486"), DEF, p("-573787.5518 -1280364.6891"), TOL);
        checkTransform(GEO, p("21.2611 48.7164"), DEF, p("-262721.0613 -1240072.1027"), TOL);
    }

    @Test
    public void testKrovakCzech() {
        // +czech keeps the native westing/southing orientation
        final String GEO = "+proj=latlong +ellps=bessel";
        final String DEF = "+proj=krovak +czech +ellps=bessel +units=m +no_defs";
        checkTransform(GEO, p("14.4378 50.0755"), DEF, p("741907.1890 1044567.9139"), TOL);
        checkTransform(GEO, p("17.1077 48.1486"), DEF, p("573787.5518 1280364.6891"), TOL);
    }

    @Test
    public void testNewZealandMapGrid() {
        // NZMG is a fit valid only around New Zealand, on the International 1924 ellipsoid
        final String GEO = "+proj=latlong +ellps=intl";
        final String DEF = "+proj=nzmg +ellps=intl +units=m +no_defs";
        checkTransform(GEO, p("174.7633 -36.8485"), DEF, p("2667665.9324 6482380.3138"), TOL);
        checkTransform(GEO, p("168.6626 -45.0312"), DEF, p("2168312.5018 5566390.4195"), TOL);
    }

    @Test
    public void testReciprocalFlattening() {
        // +rf is the reciprocal flattening and +f the flattening, as in PROJ
        checkTransform("+proj=latlong +a=6378137 +rf=298.257222101", p("9 50"),
                "+proj=tmerc +lat_0=0 +lon_0=9 +k_0=0.9996 +a=6378137 +rf=298.257222101 +units=m",
                p("0.0 5538630.7027"), 0.01);
        checkTransform("+proj=latlong +a=6378137 +f=0.0033528106811823", p("9 50"),
                "+proj=tmerc +lat_0=0 +lon_0=9 +k_0=0.9996 +a=6378137 +f=0.0033528106811823 +units=m",
                p("0.0 5538630.7027"), 0.01);
    }

    @Test
    public void testTransverseMercatorApprox() {
        // +approx selects the Evenden/Snyder series; far from the central meridian it differs
        // from the exact algorithm by kilometres, which is why PROJ made exact the default
        final String GEO = "+proj=latlong +ellps=GRS80";
        final String DEF = "+proj=tmerc +lat_0=0 +lon_0=-100 +k_0=0.9996 +ellps=GRS80 +units=m +no_defs";
        final String APPROX = "+proj=tmerc +approx +lat_0=0 +lon_0=-100 +k_0=0.9996 +ellps=GRS80 +units=m +no_defs";
        // near the central meridian the two agree to well under a millimetre
        checkTransform(GEO, p("-98 40"), DEF, p("170725.494272 4429672.973002"), 0.01);
        checkTransform(GEO, p("-98 40"), APPROX, p("170725.494273 4429672.973002"), 0.01);
        // 40 degrees out they do not
        checkTransform(GEO, p("-60 0"), DEF, p("4867577.937708 0.0"), 0.01);
        checkTransform(GEO, p("-60 0"), APPROX, p("4866119.167841 0.0"), 0.01);
    }
}

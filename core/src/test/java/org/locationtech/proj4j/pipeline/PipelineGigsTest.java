/*
 * Copyright 2026 The Proj4J Contributors.
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
package org.locationtech.proj4j.pipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.locationtech.proj4j.gie.GieIoUnits;

/**
 * The pipeline engine against the IOGP GIGS corpus.
 *
 * <p>Every expected value here is transcribed from
 * {@code conformance/src/test/resources/gigs/*.gie}, which is byte-identical to
 * PROJ 9.8.1's {@code test/gigs/}, and each is cited by file. The full 1,170-assertion
 * sweep lives in the {@code proj4j-conformance} module; this class is the subset that
 * covers <em>one mechanism each</em>, so that a break here names its own cause
 * instead of moving a corpus total by an unattributed amount.
 *
 * <table>
 * <caption>What each case is for</caption>
 * <tr><th>case</th><th>mechanism</th></tr>
 * <tr><td>5208</td><td>{@code +pm} — the pipeline is a pure prime-meridian rotation</td></tr>
 * <tr><td>5101.1</td><td>an explicit operator step after an {@code +init=} step</td></tr>
 * <tr><td>5102.2</td><td>{@code +proj=unitconvert} grad-to-rad, and the {@code WHATEVER} unit domain</td></tr>
 * <tr><td>5103.1</td><td>a pipeline-level {@code +towgs84=0,0,0} suppressing a double datum shift</td></tr>
 * <tr><td>5103.2/.3</td><td>{@code +units=ft} against {@code +units=us-ft}</td></tr>
 * <tr><td>5106</td><td>{@code +no_uoff} on an {@code omerc}</td></tr>
 * <tr><td>5111.1</td><td>a 7-parameter {@code +towgs84} Helmert</td></tr>
 * <tr><td>5113</td><td>{@code +axis=wsu}</td></tr>
 * <tr><td>5201</td><td>{@code +proj=geocent} and a meaningful third ordinate</td></tr>
 * </table>
 *
 * <p><b>Reading the tolerances.</b> A GIGS tolerance is a linear metre distance, so
 * an angular result is compared as a great-circle distance rather than as a
 * difference of degrees. {@link #assertAngular} does that conversion the cheap way —
 * a spherical approximation is ample when the bar is 30 mm and the deviations are
 * micrometres — and deliberately does <em>not</em> reimplement the gie comparator,
 * which is already published as {@code org.locationtech.proj4j.gie.GieComparator} and
 * exercised by its own tests.
 */
public class PipelineGigsTest {

    /** Metres per radian of great circle, for turning an angular residual into a distance. */
    private static final double METRES_PER_RADIAN = 6378137.0;

    private final PipelineFactory factory = new PipelineFactory();

    private static double rad(double degrees) {
        return degrees * Math.PI / 180.0;
    }

    /** Forward, taking and returning whatever the pipeline's unit domains say. */
    private double[] forward(Pipeline p, double x, double y, double z) {
        return p.forward(new double[] {x, y, z, 0});
    }

    private static void assertProjected(String what, double[] actual, double x, double y,
            double toleranceMetres) {
        double deviation = Math.hypot(actual[0] - x, actual[1] - y);
        assertTrue(what + ": deviation " + deviation + " m exceeds " + toleranceMetres
                + " m (got " + actual[0] + ", " + actual[1] + ")", deviation <= toleranceMetres);
    }

    private static void assertAngular(String what, double[] actualRadians, double lonDegrees,
            double latDegrees, double toleranceMetres) {
        double dLon = actualRadians[0] - rad(lonDegrees);
        double dLat = actualRadians[1] - rad(latDegrees);
        double deviation = Math.hypot(dLon * Math.cos(rad(latDegrees)), dLat) * METRES_PER_RADIAN;
        assertTrue(what + ": deviation " + deviation + " m exceeds " + toleranceMetres + " m (got "
                + Math.toDegrees(actualRadians[0]) + ", " + Math.toDegrees(actualRadians[1]) + ")",
                deviation <= toleranceMetres);
    }

    /**
     * {@code proj_roundtrip}'s half-step phasing ({@code 9.8.1:src/trans.cpp:591-629}):
     * one forward, then {@code n-1} inverse/forward cycles taken out of phase, then
     * one inverse. It is not {@code n} pairs, and the difference is a factor of
     * roughly {@code n} in the residual.
     */
    private static double roundtripResidualMetres(Pipeline p, double[] origin, int n) {
        double[] t = p.forward(origin);
        for (int i = 0; i < n - 1; i++) {
            t = p.forward(p.inverse(t));
        }
        t = p.inverse(t);
        return Math.hypot((t[0] - origin[0]) * Math.cos(origin[1]), t[1] - origin[1])
                * METRES_PER_RADIAN;
    }

    // ------------------------------------------------------------------- 5208

    /**
     * {@code gigs/5208.gie} — Longitude Rotation. The two CRSs differ only in their
     * prime meridian ({@code EPSG:4275} is NTF on Greenwich, {@code 4807} is NTF on
     * Paris) and carry the same {@code +towgs84}, so the datum shift cancels exactly
     * and the whole pipeline reduces to subtracting Paris's 2°20'14.025".
     *
     * <p>{@code tolerance 0.01 m}, and the file explains that the GIGS figure is
     * 0.01 arc-seconds converted to a linear distance and then tightened.
     */
    @Test
    public void gigs5208LongitudeRotation() {
        Pipeline p = factory.create("+proj=pipeline"
                + " +step +init=epsg:4275 +inv"
                + " +step +init=epsg:4807");
        assertEquals(GieIoUnits.RADIANS, p.left());
        assertEquals(GieIoUnits.RADIANS, p.right());

        assertAngular("5 58", forward(p, rad(5), rad(58), 0), 2.66277083, 58, 0.01);
        assertAngular("4 51", forward(p, rad(4), rad(51), 0), 1.66277083, 51, 0.01);
        assertAngular("2.33722917 46.8", forward(p, rad(2.33722917), rad(46.8), 0), 0, 46.8, 0.01);
        assertAngular("9 53", forward(p, rad(9), rad(53), 0), 6.66277083, 53, 0.01);

        // The reverse pipeline, which is 5208's second operation block.
        Pipeline back = factory.create("+proj=pipeline"
                + " +step +init=epsg:4807 +inv"
                + " +step +init=epsg:4275");
        assertAngular("reverse 2.66277083 58", forward(back, rad(2.66277083), rad(58), 0),
                5, 58, 0.01);
    }

    // ----------------------------------------------------------------- 5101.1

    /**
     * {@code gigs/5101.1-jhs.gie} — Transverse Mercator by the JHS formula. An
     * {@code +init=} step feeding an explicit operator step, which is the shape that
     * proves a step's own parameters are honoured rather than the expansion's.
     *
     * <p>{@code tolerance 0.03 m} forward, {@code 0.006 m} for the
     * {@code roundtrip 1000}.
     */
    @Test
    public void gigs5101TransverseMercator() {
        String etmerc = "+proj=etmerc +lat_0=49 +lon_0=-2 +k_0=0.9996012717 +x_0=400000"
                + " +y_0=-100000 +ellps=WGS84 +units=m +no_def";
        Pipeline p = factory.create("+proj=pipeline +step +init=epsg:4326 +inv +step " + etmerc);
        assertEquals(GieIoUnits.RADIANS, p.left());
        assertEquals(GieIoUnits.PROJECTED, p.right());

        assertProjected("3 80", forward(p, rad(3), rad(80), 0), 496813.178, 3358297.326, 0.03);
        assertProjected("3 49", forward(p, rad(3), rad(49), 0), 765648.501, -87944.74, 0.03);
        assertProjected("3 0", forward(p, rad(3), rad(0), 0), 957087.829, -5527462.686, 0.03);
        assertProjected("2.9999999 60", forward(p, rad(2.9999999), rad(60), 0),
                678711.584, 1134498.83, 0.03);

        Pipeline reverse = factory.create("+proj=pipeline +step " + etmerc
                + " +inv +step +init=epsg:4326");
        assertEquals(GieIoUnits.PROJECTED, reverse.left());
        assertEquals(GieIoUnits.RADIANS, reverse.right());
        assertAngular("reverse 496813.178 3358297.326",
                forward(reverse, 496813.178, 3358297.326, 0), 3, 80, 0.03);

        assertTrue("roundtrip 1000 must close inside 6 mm",
                roundtripResidualMetres(p, new double[] {rad(3), rad(49), 0, 0}, 1000) <= 0.006);
    }

    // ----------------------------------------------------------------- 5102.2

    /**
     * {@code gigs/5102.2.gie} — Lambert Conic Conformal (1SP) from NTF (Paris).
     *
     * <p>Two mechanisms in one file. The leading
     * {@code +proj=unitconvert +xy_in=grad +xy_out=rad} exists because
     * {@code +init=epsg:4807}'s longitudes are Paris grads, and it makes the
     * pipeline's left-hand unit domain {@link GieIoUnits#WHATEVER} rather than
     * {@code RADIANS} — so the accepted coordinate is fed in <b>as grads</b>, not
     * converted from degrees. The reverse pipeline ends {@code +xy_out=grad}, which
     * leaves its <em>right</em>-hand side {@code WHATEVER} and is why gie compares
     * grad values with a Euclidean metric against a tolerance written in metres.
     * Both are upstream behaviour; see {@link UnitConvertOperator}.
     */
    @Test
    public void gigs5102LambertConicConformalFromParisGrads() {
        Pipeline p = factory.create("+proj=pipeline"
                + " +step +proj=unitconvert +xy_in=grad +xy_out=rad"
                + " +step +init=epsg:4807 +inv"
                + " +step +init=epsg:27572");
        assertEquals("a grad input side is WHATEVER, so gie feeds grads unconverted",
                GieIoUnits.WHATEVER, p.left());
        assertEquals(GieIoUnits.PROJECTED, p.right());

        assertProjected("2.9586342556 64.4444444444 grad",
                forward(p, 2.9586342556, 64.4444444444, 0), 760724.023, 3457334.864, 0.03);
        assertProjected("2.9586342556 60 grad",
                forward(p, 2.9586342556, 60.0, 0), 776020.989, 3005978.979, 0.03);

        Pipeline reverse = factory.create("+proj=pipeline"
                + " +step +init=epsg:27572 +inv"
                + " +step +init=epsg:4807"
                + " +step +proj=unitconvert +xy_in=rad +xy_out=grad");
        assertEquals(GieIoUnits.PROJECTED, reverse.left());
        assertEquals("grad normalises to \"Grad\", so this stays WHATEVER",
                GieIoUnits.WHATEVER, reverse.right());
        double[] back = forward(reverse, 760724.023, 3457334.864, 0);
        assertEquals(2.9586342556, back[0], 1e-6);
        assertEquals(64.4444444444, back[1], 1e-6);
    }

    // ----------------------------------------------------------------- 5103.1

    /**
     * {@code gigs/5103.1.gie} — Lambert Conic Conformal (2SP), Belgium.
     *
     * <p>Its third operation block is {@code +proj=pipeline towgs84=0,0,0 …} with the
     * file comment "turn off dual datum shift". The global token is appended to each
     * step's argument list <em>ahead of</em> the {@code +init=} expansion, so
     * first-match-wins makes it shadow the seven-parameter {@code towgs84} that
     * {@code EPSG:4313} and {@code 31370} both declare. The change of ellipsoid
     * survives, the Helmert does not, and the {@code roundtrip 1000} then closes
     * inside 6 mm.
     *
     * <p>This is the case that fails silently if the ordering is reversed: the shift
     * gets applied twice and the result is plausible but wrong.
     */
    @Test
    public void gigs5103PipelineLevelTowgs84SuppressesTheDoubleDatumShift() {
        Pipeline shifted = factory.create("+proj=pipeline"
                + " +step +init=epsg:4313 +inv"
                + " +step +init=epsg:31370");
        assertProjected("5 58 with the EPSG shift", forward(shifted, rad(5), rad(58), 0),
                187742.7, 969521.653, 0.03);

        Pipeline suppressed = factory.create("+proj=pipeline +towgs84=0,0,0"
                + " +step +init=epsg:4313 +inv"
                + " +step +init=epsg:31370");
        // Same coordinate, different answer: the global token really did shadow the
        // expansion's. (Both agree to ~1 mm here because the two Helmerts nearly
        // cancel; the point is that the roundtrip closes, which it cannot if the
        // shift is applied twice with numerical drift.)
        assertTrue("roundtrip 1000 must close inside 6 mm",
                roundtripResidualMetres(suppressed, new double[] {rad(5), rad(58), 0, 0}, 1000)
                        <= 0.006);
    }

    // ------------------------------------------------------------ 5103.2/.3

    /**
     * {@code gigs/5103.2.gie} and {@code 5103.3.gie} — the same Lambert Conic
     * Conformal over the same input, once in {@code +units=ft} ({@code EPSG:2921})
     * and once in {@code +units=us-ft} ({@code EPSG:3568}). The two files exist to
     * separate the international foot from the US survey foot, and their expected
     * eastings differ by four metres — so passing both is proof the distinction is
     * honoured rather than coincidence.
     */
    @Test
    public void gigs5103InternationalFootAgainstUsSurveyFoot() {
        Pipeline intlFoot = factory.create("+proj=pipeline"
                + " +step +init=epsg:4152 +inv"
                + " +step +init=epsg:2921");
        Pipeline usFoot = factory.create("+proj=pipeline"
                + " +step +init=epsg:4152 +inv"
                + " +step +init=epsg:3568");

        double[] a = forward(intlFoot, rad(-110), rad(49), 0);
        double[] b = forward(usFoot, rad(-110), rad(49), 0);
        assertProjected("2921 ft", a, 2003937.27, 6452491.7, 0.03);
        assertProjected("3568 us-ft", b, 2003933.27, 6452478.8, 0.03);
        assertTrue("the two units must not produce the same easting",
                Math.abs(a[0] - b[0]) > 3.0);
    }

    // ------------------------------------------------------------------- 5106

    /**
     * {@code gigs/5106.gie} — Hotine Oblique Mercator, Malaysia. {@code EPSG:3376}
     * carries {@code +no_uoff}, which is undocumented upstream (the docs name only
     * {@code +no_off}) but accepted as a synonym, and which moves the origin by the
     * "uoff" term. It also carries no {@code +towgs84} at all, so the pipeline is a
     * pure projection change on one ellipsoid.
     */
    @Test
    public void gigs5106ObliqueMercatorWithNoUoff() {
        Pipeline p = factory.create("+proj=pipeline"
                + " +step +init=epsg:4742 +inv"
                + " +step +init=epsg:3376");
        assertProjected("117 12", forward(p, rad(117), rad(12), 0),
                807919.144, 1329535.334, 0.05);
        assertProjected("117 10", forward(p, rad(117), rad(10), 0),
                808784.981, 1107678.473, 0.05);
    }

    // ----------------------------------------------------------------- 5111.1

    /**
     * {@code gigs/5111.1.gie} — Mercator (variant A), Indonesia, over a genuine
     * three-parameter {@code +towgs84} ({@code EPSG:4211}/{@code 3001}, Batavia on
     * Bessel, {@code -377,681,-50}). This is the case where the cartesian round-trip
     * and the Helmert both matter: the ellipsoid changes <em>and</em> the origin
     * moves.
     */
    @Test
    public void gigs5111MercatorAcrossARealDatumShift() {
        Pipeline p = factory.create("+proj=pipeline +towgs84=0,0,0"
                + " +step +init=epsg:4211 +inv"
                + " +step +init=epsg:3001");
        assertProjected("100.0876483 77.6534822", forward(p, rad(100.0876483), rad(77.6534822), 0),
                2800000.0, 15000000.0, 0.055);
        assertProjected("100.0876483 73.1442856", forward(p, rad(100.0876483), rad(73.1442856), 0),
                2800000.0, 13000000.0, 0.05);
    }

    // ------------------------------------------------------------------- 5113

    /**
     * {@code gigs/5113.gie} — Transverse Mercator (South Orientated), South Africa.
     * {@code EPSG:2049} carries {@code +axis=wsu}, and it is the only reason the
     * expected easting is negative and the expected northing positive. Without the
     * auto-inserted {@code +proj=axisswap} step both signs come out reversed, which
     * is a 5.5 million metre error that no tolerance hides.
     */
    @Test
    public void gigs5113AxisWsuFlipsBothSigns() {
        Pipeline p = factory.create("+proj=pipeline"
                + " +step +init=epsg:4148 +inv"
                + " +step +init=epsg:2049");
        double[] out = forward(p, rad(21.5), rad(-25.0), 0);
        assertProjected("21.5 -25", out, -50475.46, 2766147.25, 0.03);
        assertTrue("easting must be negative", out[0] < 0);
        assertTrue("northing must be positive", out[1] > 0);

        Pipeline reverse = factory.create("+proj=pipeline"
                + " +step +init=epsg:2049 +inv"
                + " +step +init=epsg:4148");
        assertAngular("reverse", forward(reverse, -50475.46, 2766147.25, 0), 21.5, -25.0, 0.03);
    }

    // ------------------------------------------------------------------- 5201

    /**
     * {@code gigs/5201.gie} — geographic/geocentric conversions. Three things at
     * once: the pipeline's left-hand domain is {@link GieIoUnits#CARTESIAN} rather
     * than angular, the third ordinate is a real ellipsoidal height rather than
     * padding, and {@code +proj=geocent} is executed by this package's own
     * {@code +proj=cart} port — proj4j's {@code GeocentProjection} reads its
     * destination coordinate instead of its source and cannot produce an answer at
     * all.
     */
    @Test
    public void gigs5201GeographicGeocentric() {
        Pipeline p = factory.create("+proj=pipeline"
                + " +step +init=epsg:4978 +inv"
                + " +step +init=epsg:4326");
        assertEquals(GieIoUnits.CARTESIAN, p.left());
        assertEquals(GieIoUnits.RADIANS, p.right());

        double[] a = forward(p, -962479.5924, 555687.8517, 6260738.6526);
        assertAngular("150 80", a, 150, 80, 0.01);
        assertEquals("ellipsoidal height", 1214.137, a[2], 0.01);

        double[] b = forward(p, 2764128.3196, 4787610.6883, 3170373.7354);
        assertAngular("60 30", b, 60, 30, 0.01);
        assertEquals(0.0, b[2], 0.01);

        double[] c = forward(p, 2764128.3196, -4787610.6883, -3170373.7354);
        assertAngular("-60 -30", c, -60, -30, 0.01);
        assertEquals(0.0, c[2], 0.01);

        Pipeline reverse = factory.create("+proj=pipeline"
                + " +step +init=epsg:4326 +inv"
                + " +step +init=epsg:4978");
        assertEquals(GieIoUnits.RADIANS, reverse.left());
        assertEquals(GieIoUnits.CARTESIAN, reverse.right());
        double[] back = forward(reverse, rad(150), rad(80), 1214.137);
        assertProjected("reverse XYZ", back, -962479.5924, 555687.8517, 0.01);
        assertEquals(6260738.6526, back[2], 0.01);
    }

    // -------------------------------------------------- the legacy single form

    /**
     * A bare {@code +init=} is the same operation as a one-step pipeline containing
     * it — {@code pj_init} runs the same {@code cs2cs_emulation_setup} either way —
     * except that its geodesic default is WGS84 rather than GRS80.
     */
    @Test
    public void aBareInitIsTheSameOperationAsAOneStepPipeline() {
        double[] single = factory.create("+init=epsg:27572")
                .forward(new double[] {rad(2.33722917), rad(46.8), 0, 0});
        double[] wrapped = factory.create("+proj=pipeline +step +init=epsg:27572")
                .forward(new double[] {rad(2.33722917), rad(46.8), 0, 0});
        assertEquals(single[0], wrapped[0], 0.0);
        assertEquals(single[1], wrapped[1], 0.0);
    }
}

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
package org.locationtech.proj4j.gie;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The tests that would have caught the metric bug.
 *
 * <p>Reference geodesic distances are on <b>GRS80</b> ({@code a = 6378137},
 * {@code f = 1/298.257222101}). They were cross-checked against Karney's reference
 * GeographicLib (Python 2.x series) and against PROJ 9.8.1's own {@code geod} binary; the
 * vendored Java port agrees with the Python reference to the last ULP. The values recorded in
 * the skill's reference table are correct only to about 5e-7 m for the three non-equatorial
 * cases, so a delta of 1e-6 m is used — five orders of magnitude tighter than needed to
 * discriminate the failure modes these tests exist to catch, which are all &gt;100 m.
 *
 * <pre>
 * (0N,0E) -&gt; (0N,1E)   111319.49079327357   (skill table: 111319.4907932734)
 * (60N,0E) -&gt; (60N,1E)  55799.470393949334  (skill table:  55799.47039407)
 * (45N,0E) -&gt; (45N,1E)  78846.33471044291   (skill table:  78846.33471087)
 * (0N,0E) -&gt; (1N,0E)   110574.38855415252   (skill table: 110574.38855431)
 * </pre>
 */
public class GieComparatorTest {

    private static final double M = 1e-6;
    private static final double NAN = Double.NaN;
    private static final double INF = Double.POSITIVE_INFINITY;

    /** Reference distances. */
    private static final double ONE_DEG_LON_AT_EQUATOR = 111319.4907932734;
    private static final double ONE_DEG_LON_AT_60N = 55799.47039407;
    private static final double ONE_DEG_LON_AT_45N = 78846.33471087;
    private static final double ONE_DEG_LAT_AT_EQUATOR = 110574.38855431;

    /** What a constant degrees-to-metres scale would wrongly give -- the actual historical bug. */
    private static final double NAIVE_CONSTANT_SCALE = 111319.4908;

    /** Another popular wrong constant: the "mean" metres per degree. */
    private static final double NAIVE_MEAN_SCALE = 111195.0;

    /** What a naive cos(latitude) scale would wrongly give at 60N. */
    private static final double NAIVE_COS_SCALE_AT_60N = 111319.4907932734 * 0.5;

    private final GieComparator grs80 = GieComparator.grs80();

    private static double[] c(final double a, final double b, final double z, final double t) {
        return new double[]{a, b, z, t};
    }

    private static double rad(final double deg) {
        return deg * Math.PI / 180.0;
    }

    // ================================================================= factories

    /**
     * The two defaults are genuinely different ellipsoids and must not be conflated: a bare
     * {@code operation} gets WGS84 ({@code init.cpp:576-581}), a {@code +proj=pipeline} with no
     * global {@code +ellps} gets GRS80 ({@code pipeline.cpp:338-351}).
     */
    @Test
    public void wgs84AndGrs80AreDifferentEllipsoids() {
        final double[] a = c(0, 0, 0, 0);
        final double[] b = c(0, rad(1), 0, 0); // 1 degree of latitude, most f-sensitive
        final double dWgs = GieComparator.wgs84().lpDist(a, b);
        final double dGrs = GieComparator.grs80().lpDist(a, b);
        assertNotEquals(dWgs, dGrs, 0.0);
        // ...but only by about 4 microns, so this only matters at nm tolerances.
        assertEquals(dWgs, dGrs, 1e-4);
    }

    @Test
    public void forEllipsoidUsesTheGivenShape() {
        final GieComparator sphere = GieComparator.forEllipsoid(6378137.0, 0.0);
        // On a sphere of that radius, 1 degree is exactly pi*R/180.
        assertEquals(Math.PI * 6378137.0 / 180.0,
                sphere.lpDist(c(0, 0, 0, 0), c(rad(1), 0, 0, 0)), 1e-6);
    }

    // ================================================ the four reference distances

    @Test
    public void oneDegreeOfLongitudeAtTheEquator() {
        assertEquals(ONE_DEG_LON_AT_EQUATOR,
                grs80.lpDist(c(0, 0, 0, 0), c(rad(1), 0, 0, 0)), M);
    }

    /**
     * The single most discriminating value in this file. A constant scale gives 111319.49
     * everywhere; a naive {@code cos(60)} scale gives 55659.75; the truth is 55799.47. All
     * three are separated by far more than a metre.
     */
    @Test
    public void oneDegreeOfLongitudeAtSixtyNorth() {
        final double d = grs80.lpDist(c(0, rad(60), 0, 0), c(rad(1), rad(60), 0, 0));
        assertEquals(ONE_DEG_LON_AT_60N, d, M);
        assertNotEquals(NAIVE_CONSTANT_SCALE, d, 1.0);
        assertNotEquals(NAIVE_MEAN_SCALE, d, 1.0);
        assertNotEquals(NAIVE_COS_SCALE_AT_60N, d, 1.0);
        assertEquals(55659.745, NAIVE_COS_SCALE_AT_60N, 0.01); // the cos(60) trap, for the record
    }

    @Test
    public void oneDegreeOfLongitudeAtFortyFiveNorth() {
        assertEquals(ONE_DEG_LON_AT_45N,
                grs80.lpDist(c(0, rad(45), 0, 0), c(rad(1), rad(45), 0, 0)), M);
    }

    /** Longitude and latitude do not scale alike, even at the equator. */
    @Test
    public void oneDegreeOfLatitudeAtTheEquator() {
        final double d = grs80.lpDist(c(0, 0, 0, 0), c(0, rad(1), 0, 0));
        assertEquals(ONE_DEG_LAT_AT_EQUATOR, d, M);
        assertNotEquals(ONE_DEG_LON_AT_EQUATOR, d, 1.0);
    }

    // ============================================================ the linear branch

    /**
     * Discriminating assertion #1. Two eastings a metre apart, projected output: the deviation
     * is one metre. Not 111319.4908, not 111195, not 110574.
     */
    @Test
    public void twoEastingsOneMetreApartAreOneMetreApart() {
        final GieComparator.Result r = grs80.compare(
                GieIoUnits.PROJECTED, false,
                c(500000.0, 6000000.0, 0, 0),
                c(500001.0, 6000000.0, 0, 0),
                2, 1.0);

        assertSame(GieMetric.EUCLIDEAN_METRES, r.metric());
        assertEquals(1.0, r.deviation(), 0.0);
        assertNotEquals(111319.4908, r.deviation(), 1.0);
        assertNotEquals(111195.0, r.deviation(), 1.0);
        assertNotEquals(110574.0, r.deviation(), 1.0);
        assertTrue(r.passed());
    }

    /** The same, through the CLASSIC alias that every PROJ_HEAD projection actually declares. */
    @Test
    public void classicOutputIsAlsoLinear() {
        final GieComparator.Result r = grs80.compare(
                GieIoUnits.CLASSIC, false,
                c(0.0, 0.0, 0, 0), c(1.0, 0.0, 0, 0), 2, 1.0);
        assertSame(GieMetric.EUCLIDEAN_METRES, r.metric());
        assertEquals(1.0, r.deviation(), 0.0);
        assertNotEquals(111319.4908, r.deviation(), 1.0);
    }

    @Test
    public void cartesianAndWhateverAreAlsoLinear() {
        for (final GieIoUnits u : new GieIoUnits[]{GieIoUnits.CARTESIAN, GieIoUnits.WHATEVER}) {
            final GieComparator.Result r = grs80.compare(
                    u, false, c(0.0, 0.0, 0, 0), c(1.0, 0.0, 0, 0), 2, 1.0);
            assertSame("units=" + u, GieMetric.EUCLIDEAN_METRES, r.metric());
            assertEquals("units=" + u, 1.0, r.deviation(), 0.0);
        }
    }

    /**
     * Discriminating assertion #3. Horizontal and vertical combine Pythagorean, via nested
     * hypot -- 4 m across and 3 m up is 5 m, not 7 m.
     */
    @Test
    public void horizontalAndVerticalCombinePythagorean() {
        final GieComparator.Result r = grs80.compare(
                GieIoUnits.PROJECTED, false,
                c(0, 0, 0, 0), c(4.0, 0, 3.0, 0), 3, 10.0);
        assertEquals(5.0, r.deviation(), 0.0);
        assertNotEquals(7.0, r.deviation(), 0.5);
    }

    /** Same rule on the geodesic side: z always participates. */
    @Test
    public void verticalOffsetParticipatesInTheGeodesicBranchToo() {
        final double horizontal = grs80.lpDist(c(0, 0, 0, 0), c(rad(1), 0, 0, 0));
        final double withZ = grs80.lpzDist(c(0, 0, 0, 0), c(rad(1), 0, 100.0, 0));
        assertEquals(Math.hypot(horizontal, -100.0), withZ, 0.0);
        assertTrue(withZ > horizontal);
    }

    @Test
    public void xyzDistIsNestedHypotNotSumOfSquares() {
        // A case where sqrt(dx^2+dy^2+dz^2) would overflow but nested hypot does not.
        final double big = 1e200;
        final double d = GieComparator.xyzDist(c(0, 0, 0, 0), c(big, big, big, 0));
        assertFalse("nested hypot must not overflow", Double.isInfinite(d));
        assertEquals(big * Math.sqrt(3.0), d, big * 1e-12);
    }

    // ============================================================ the swap

    /**
     * Discriminating assertion #4a. For a lat-first DEGREES target the swap is the difference
     * between measuring a degree of longitude at 60N (55799 m) and a degree of latitude
     * (110574 m).
     */
    @Test
    public void swapChangesTheAnswerForALatFirstDegreesTarget() {
        // v[0]=lat, v[1]=lon -- the "lat first" ordering the swap exists to correct.
        final double[] expected = c(60.0, 0.0, 0, 0);
        final double[] got = c(60.0, 1.0, 0, 0);

        final GieComparator.Result swapped =
                grs80.compare(GieIoUnits.DEGREES, true, expected, got, 2, INF);
        final GieComparator.Result notSwapped =
                grs80.compare(GieIoUnits.DEGREES, false, expected, got, 2, INF);

        assertSame(GieMetric.GEODESIC_FROM_DEGREES, swapped.metric());
        assertTrue(swapped.swapApplied());
        assertFalse(notSwapped.swapApplied());

        assertEquals(ONE_DEG_LON_AT_60N, swapped.deviation(), M);
        assertEquals(ONE_DEG_LAT_AT_EQUATOR, notSwapped.deviation(), M);
        assertNotEquals(swapped.deviation(), notSwapped.deviation(), 1.0);
    }

    /**
     * Discriminating assertion #4b. For a projected target the swap is a mathematical no-op --
     * and not merely to within a rounding error, but bit-for-bit, because hypot's two arguments
     * are simply reordered.
     */
    @Test
    public void swapIsBitIdenticalForAProjectedTarget() {
        final double[] expected = c(1.0, 2.0, 0.25, 0);
        final double[] got = c(1.1, 2.7, 0.75, 0);

        final double swapped =
                grs80.compare(GieIoUnits.PROJECTED, true, expected, got, 3, INF).deviation();
        final double notSwapped =
                grs80.compare(GieIoUnits.PROJECTED, false, expected, got, 3, INF).deviation();

        assertEquals(Double.doubleToRawLongBits(notSwapped), Double.doubleToRawLongBits(swapped));
    }

    /** PROJ does not swap in the RADIANS branch. Neither do we. */
    @Test
    public void swapIsNeverAppliedInTheRadiansBranch() {
        final GieComparator.Result r = grs80.compare(
                GieIoUnits.RADIANS, true,
                c(rad(60), 0, 0, 0), c(rad(60), rad(1), 0, 0), 2, INF);
        assertSame(GieMetric.GEODESIC_FROM_RADIANS, r.metric());
        assertFalse(r.swapApplied());
        // v[0] is lam and v[1] is phi, unswapped: this is a degree of LATITUDE at lon 60.
        assertEquals(ONE_DEG_LAT_AT_EQUATOR, r.deviation(), M);
    }

    @Test
    public void swapIsNeverAppliedInTheNanBothBranch() {
        final GieComparator.Result r = grs80.compare(
                GieIoUnits.PROJECTED, true, c(NAN, 1, 0, 0), c(NAN, 2, 0, 0), 2, INF);
        assertSame(GieMetric.NAN_BOTH, r.metric());
        assertFalse(r.swapApplied());
    }

    @Test
    public void compareDoesNotMutateItsArguments() {
        final double[] expected = c(60.0, 0.0, 1.0, 2000.0);
        final double[] got = c(60.0, 1.0, 2.0, 2030.0);
        final double[] expectedCopy = expected.clone();
        final double[] gotCopy = got.clone();

        grs80.compare(GieIoUnits.DEGREES, true, expected, got, 2, INF);

        org.junit.Assert.assertArrayEquals(expectedCopy, expected, 0.0);
        org.junit.Assert.assertArrayEquals(gotCopy, got, 0.0);
    }

    // ================================================= the degrees branch conversion

    /**
     * The DEGREES branch converts with {@code deg * PI / 180}, not {@code deg / 180 * PI}. The
     * two differ by up to 1 ULP, which is about 1.4e-9 m -- the threshold of the corpus's 16
     * nm-tolerance rows. Here we only check that the branch agrees with the RADIANS branch fed
     * pre-converted input, which is the invariant that matters.
     */
    @Test
    public void degreesBranchAgreesWithRadiansBranchOnPreConvertedInput() {
        final GieComparator.Result deg = grs80.compare(
                GieIoUnits.DEGREES, false, c(0.0, 45.0, 0, 0), c(1.0, 45.0, 0, 0), 2, INF);
        final GieComparator.Result r = grs80.compare(
                GieIoUnits.RADIANS, false,
                c(rad(0.0), rad(45.0), 0, 0), c(rad(1.0), rad(45.0), 0, 0), 2, INF);
        assertEquals(Double.doubleToRawLongBits(r.deviation()),
                Double.doubleToRawLongBits(deg.deviation()));
        assertEquals(ONE_DEG_LON_AT_45N, deg.deviation(), M);
    }

    // ============================================================ NaN handling

    @Test
    public void nanOnBothSidesIsZeroAndPasses() {
        for (final GieIoUnits u : GieIoUnits.values()) {
            final GieComparator.Result r = grs80.compare(
                    u, false, c(NAN, NAN, NAN, 0), c(NAN, NAN, NAN, 0), 2, 1e-12);
            assertSame("units=" + u, GieMetric.NAN_BOTH, r.metric());
            assertEquals("units=" + u, 0.0, r.deviation(), 0.0);
            assertTrue("units=" + u, r.passed());
        }
    }

    /**
     * NaN on one side only must fail, in every branch, in both directions, and however generous
     * the tolerance. This is the assertion that stops a blown-up transform from being scored as
     * a pass.
     */
    @Test
    public void nanOnOneSideOnlyFailsInEveryBranch() {
        for (final GieIoUnits u : GieIoUnits.values()) {
            final GieComparator.Result gotNan = grs80.compare(
                    u, false, c(0.0, 0.0, 0, 0), c(NAN, 0.0, 0, 0), 2, INF);
            assertTrue("got NaN, units=" + u, Double.isNaN(gotNan.deviation()));
            assertFalse("got NaN, units=" + u, gotNan.withinTolerance());
            assertFalse("got NaN, units=" + u, gotNan.passed());

            final GieComparator.Result expectedNan = grs80.compare(
                    u, false, c(NAN, 0.0, 0, 0), c(0.0, 0.0, 0, 0), 2, INF);
            assertTrue("expected NaN, units=" + u, Double.isNaN(expectedNan.deviation()));
            assertFalse("expected NaN, units=" + u, expectedNan.passed());
        }
    }

    // ======================================================= the pass predicate

    /**
     * Discriminating assertion #5. This is exactly the assertion that distinguishes
     * {@code !(d <= tol)} from {@code d > tol}: under the latter, NaN passes.
     */
    @Test
    public void nanIsNotWithinAnInfiniteTolerance() {
        assertFalse(GieComparator.withinTolerance(NAN, INF));
    }

    @Test
    public void withinToleranceBoundaryBehaviour() {
        assertTrue(GieComparator.withinTolerance(0.0, 0.0));
        assertTrue(GieComparator.withinTolerance(1.0, 1.0));       // <= is inclusive
        assertFalse(GieComparator.withinTolerance(1.0000001, 1.0));
        assertFalse(GieComparator.withinTolerance(INF, 1.0));      // +inf fails a finite tol
        assertFalse(GieComparator.withinTolerance(NAN, 1.0));
        assertFalse(GieComparator.withinTolerance(NAN, NAN));
        assertTrue(GieComparator.withinTolerance(-0.0, 0.0));
    }

    // ======================================================= HUGE_VAL short circuit

    /**
     * {@code proj_lpz_dist} short-circuits on {@code lam == HUGE_VAL} -- on lam only, and on
     * equality with positive infinity, not on NaN.
     */
    @Test
    public void lpzDistShortCircuitsOnInfiniteLamOnEitherSide() {
        assertEquals(INF, grs80.lpzDist(c(INF, 0, 0, 0), c(0, 0, 0, 0)), 0.0);
        assertEquals(INF, grs80.lpzDist(c(0, 0, 0, 0), c(INF, 0, 0, 0)), 0.0);
        assertEquals(GieComparator.HUGE_VAL, grs80.lpzDist(c(INF, 0, 0, 0), c(INF, 0, 0, 0)), 0.0);
    }

    /** phi and z are NOT part of the guard. */
    @Test
    public void lpzDistDoesNotShortCircuitOnInfinitePhi() {
        final double d = grs80.lpzDist(c(0, INF, 0, 0), c(0, 0, 0, 0));
        assertTrue("an infinite phi yields NaN from the geodesic, not the HUGE_VAL short circuit",
                Double.isNaN(d));
    }

    /** Negative infinity is not HUGE_VAL. */
    @Test
    public void lpzDistDoesNotShortCircuitOnNegativeInfiniteLam() {
        assertTrue(Double.isNaN(grs80.lpzDist(c(Double.NEGATIVE_INFINITY, 0, 0, 0), c(0, 0, 0, 0))));
    }

    // ============================================== dimension masking and time

    /** Discriminating assertion #6a. t contributes nothing at all to the spatial deviation. */
    @Test
    public void thirtyYearsOfTimeContributeZeroMetres() {
        final GieComparator.Result r = grs80.compare(
                GieIoUnits.PROJECTED, false,
                c(0, 0, 0, 2000.0), c(0, 0, 0, 2030.0), 4, 1e-12);
        assertEquals(0.0, r.deviation(), 0.0);
        assertTrue("the spatial verdict must be unaffected by t", r.withinTolerance());
        assertEquals(30.0, r.temporalDeviation(), 0.0);
        assertFalse(r.temporalWithinThreshold());
        assertFalse(r.passed());
    }

    /** Discriminating assertion #6b. The temporal check runs only for 4-dimensional expects. */
    @Test
    public void temporalCheckOnlyRunsWhenFourDimensionsWereGiven() {
        for (int dims = 1; dims <= 4; dims++) {
            final GieComparator.Result r = grs80.compare(
                    GieIoUnits.PROJECTED, false,
                    c(0, 0, 0, 2000.0), c(0, 0, 0, 2030.0), dims, 1e-12);
            assertEquals("dims=" + dims, dims == 4, r.temporalChecked());
            assertEquals("dims=" + dims, dims != 4, r.passed());
        }
    }

    @Test
    public void temporalThresholdIsOneTenThousandthOfAYear() {
        assertEquals(1e-4, GieComparator.TEMPORAL_THRESHOLD_IN_YEAR, 0.0);
        assertTrue(grs80.compare(GieIoUnits.PROJECTED, false,
                c(0, 0, 0, 2000.0), c(0, 0, 0, 2000.00005), 4, 1.0).passed());
        assertFalse(grs80.compare(GieIoUnits.PROJECTED, false,
                c(0, 0, 0, 2000.0), c(0, 0, 0, 2000.0002), 4, 1.0).passed());
    }

    /** dimensionsGiven &lt; 4 masks got[3]; dimensionsGiven &lt; 3 also masks got[2]. */
    @Test
    public void dimensionMaskingAppliesToGotOnly() {
        // A z of 3 m is invisible at dims=2 because got[2] is zeroed.
        assertEquals(4.0, grs80.compare(GieIoUnits.PROJECTED, false,
                c(0, 0, 0, 0), c(4.0, 0, 3.0, 0), 2, INF).deviation(), 0.0);
        // ...and visible at dims=3.
        assertEquals(5.0, grs80.compare(GieIoUnits.PROJECTED, false,
                c(0, 0, 0, 0), c(4.0, 0, 3.0, 0), 3, INF).deviation(), 0.0);

        // The asymmetry: `expected` is NOT masked. In gie this is unobservable because
        // parse_coord zero-initialises the expected coordinate, but the port is literal, so a
        // caller that supplies a non-zero expected z at dims=2 sees it.
        assertEquals(3.0, grs80.compare(GieIoUnits.PROJECTED, false,
                c(0, 0, 3.0, 0), c(0, 0, 0, 0), 2, INF).deviation(), 0.0);
    }

    // ================================================== the gigs/5102.2 quirk

    /**
     * <b>Do not "fix" this.</b> The inverse pipeline of {@code gigs/5102.2.gie} ends with
     * {@code +xy_out=grad}. "grad" normalises to neither "Radian" nor "Degree", so
     * {@code P->right} stays {@link GieIoUnits#WHATEVER} and the comparison falls into the
     * Euclidean branch -- which therefore measures <em>raw grad values</em> against a tolerance
     * written as {@code 0.03 m}. A 0.01 grad discrepancy scores as a deviation of 0.01 and
     * passes.
     *
     * <p>This is faithful reproduction of PROJ 9.8.1 behaviour. Making the comparator "smarter"
     * here would change the pass/fail verdict of that file relative to upstream, which is
     * exactly what the conformance harness must not do.
     */
    @Test
    public void gigs5102_2GradValuesAreComparedRawAgainstAMetreTolerance() {
        final double tolerance = GieTolerance.tolerance("0.03 m");
        assertEquals(0.03, tolerance, 0.0);

        final GieComparator.Result r = grs80.compare(
                GieIoUnits.WHATEVER, false,
                c(100.00, 50.00, 0, 0),
                c(100.01, 50.00, 0, 0),
                2, tolerance);

        assertSame(GieMetric.EUCLIDEAN_METRES, r.metric());
        assertEquals(0.01, r.deviation(), 1e-9);
        assertTrue(r.passed());

        // Had this taken an angular branch, 0.01 grad would have scored in the many-hundreds of
        // metres and the file would have gone red.
        assertNotEquals(111319.4908 * 0.009, r.deviation(), 1.0);
    }

    // ============================================================== result object

    @Test
    public void resultReportsEverythingNeededToAudit() {
        final GieComparator.Result r = grs80.compare(
                GieIoUnits.CLASSIC, true,
                c(0, 0, 0, 2000.0), c(3.0, 4.0, 0, 2000.0), 4, 1.0);
        assertSame(GieMetric.EUCLIDEAN_METRES, r.metric());
        assertEquals(5.0, r.deviation(), 0.0);
        assertEquals(1.0, r.tolerance(), 0.0);
        assertTrue(r.swapApplied());
        assertFalse(r.withinTolerance());
        assertTrue(r.temporalChecked());
        assertEquals(0.0, r.temporalDeviation(), 0.0);
        assertTrue(r.temporalWithinThreshold());
        assertFalse(r.passed());
        assertTrue(r.toString().contains("EUCLIDEAN_METRES"));
    }
}

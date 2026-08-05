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
package org.locationtech.proj4j.builtins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.gie.GieComparator;
import org.locationtech.proj4j.proj.Projection;

/**
 * Pins the {@code gie/builtins.gie} rows that were failing because of a defect <em>inside a
 * projection Proj4J already shipped</em>, as opposed to a projection it does not have.
 *
 * <h2>Why this file exists at all</h2>
 *
 * <p>The published gap analysis ranks <em>missing</em> operators. Nothing ranked the operators that
 * were present and wrong, and a decomposition of {@code builtins.gie} found <b>269 of its 824
 * failing assertions in that second category</b> — projections that build, run, return a plausible
 * coordinate and are silently off by between 2.4 mm and 10,000 km. Every expected value below is
 * copied verbatim from {@code conformance/src/test/resources/gie/builtins.gie} with its own
 * {@code tolerance} line, and the line number is quoted so the row can be found.
 *
 * <p><b>Deliberately not a second conformance runner.</b> The corpus sweep lives in the
 * {@code conformance} module and owns the {@code .gie} lexer and the assertion state machine; this
 * file only reuses the published {@link GieComparator} so that the <em>metric</em> is the same one
 * that grades the corpus — Euclidean metres for a projected target, a geodesic for an angular one.
 * A local re-implementation of either would be a second source of truth.
 *
 * <h2>The three defect shapes</h2>
 *
 * <ol>
 * <li><b>A class-level default standing in for a PROJ parameter default.</b> {@code Proj4Parser}
 *     assigns {@code +lat_0} (and {@code +lat_ts}, and {@code +lat_1}) <em>only when the keyword is
 *     present</em>, so a constructor that pins the field is setting the effective default for every
 *     definition that omits the key. Four classes pinned a value PROJ defaults differently, and
 *     between them they gated 62 assertions.</li>
 * <li><b>An in-place reassignment lost in transcription.</b> Upstream repeatedly writes
 *     {@code lp.phi = f(lp.phi)} and then reads {@code lp.phi} twice more. Five classes computed
 *     {@code f(phi)} into the output slot, overwrote it, and used the original {@code phi} —
 *     leaving forward and inverse not mutual inverses.</li>
 * <li><b>A whole missing kernel.</b> {@code eqc}, {@code sinu} and {@code gnom} are all
 *     {@code Sph&Ell} upstream and had only the spherical arm; {@code eqc} in fact had no arm at
 *     all and fell through to the base class's identity.</li>
 * </ol>
 */
public class BuiltinsProjectionDefectTest {

    /** {@code builtins.gie}'s most common tolerance, in metres. */
    private static final double MM_0_1 = 0.0001;

    private static final CRSFactory FACTORY = new CRSFactory();

    // ------------------------------------------------------------------ shape 1: pinned defaults

    /**
     * {@code builtins.gie:6699-6706} — {@code +proj=stere +ellps=GRS80} with no {@code +lat_0}.
     * PROJ's {@code phi0} default is 0, so this is the <em>equatorial</em> aspect;
     * {@code StereographicAzimuthalProjection}'s no-argument constructor pinned 90&deg; and ran the
     * polar one, 6,375 km away.
     */
    @Test
    public void stereWithoutLat0IsEquatorialNotPolar() {
        assertForward("+proj=stere +ellps=GRS80", 2, 1, 222644.854550117, 110610.883474174, MM_0_1);
        assertForward("+proj=stere +ellps=GRS80", 2, -1, 222644.854550117, -110610.883474174, MM_0_1);
    }

    /**
     * {@code builtins.gie:6779-6783} — {@code +lat_ts} absent on a polar aspect means
     * <b>&pi;/2</b>, not 0 ({@code 9.8.1:stere.cpp:305-309}). With 0 the polar branch takes its
     * {@code cos(phits)/tsfn(phits)} arm and the whole map comes out scaled by 1.9335.
     */
    @Test
    public void stereWithoutLatTsUsesHalfPiNotZero() {
        assertForward("+proj=stere +ellps=GRS80 +lat_0=-90 +k_0=0.97", 20, -70,
                748424.7446, 2056280.0858, MM_0_1);
    }

    /**
     * {@code builtins.gie:2253-2262} — {@code +proj=gnom +R=1} is equatorial. The old default of
     * {@code lat_0=90} answered {@code (0.030619, -0.173648)} here, which is not a rounding
     * difference but a different projection.
     */
    @Test
    public void gnomWithoutLat0IsEquatorialNotPolar() {
        assertForward("+proj=gnom +R=1", 10, 80, 0.1763, 5.7588, MM_0_1);
        assertForward("+proj=gnom +R=1", 80, 10, 5.6713, 1.0154, MM_0_1);
    }

    /**
     * {@code builtins.gie:1805-1810} — {@code +proj=eqdc} with no {@code +lat_0}. A pinned 37.5&deg;
     * shifted {@code rho0 = c - M(phi0)} and therefore every northing by {@code M(37.5) =
     * 4,151,999 m}, identically in all 16 of the projection's assertions.
     */
    @Test
    public void eqdcWithoutLat0PutsTheOriginOnTheEquator() {
        assertForward("+proj=eqdc +ellps=GRS80 +lat_1=0.5 +lat_2=2", 2, 1,
                222588.440269286, 110659.134907347, MM_0_1);
    }

    /**
     * {@code builtins.gie:4028-4032} — {@code loxim}'s {@code +lat_1} was hard-coded to 40&deg;
     * behind a {@code //FIXME - param}. PROJ's default is 0 and the corpus passes 0.5.
     */
    @Test
    public void loximReadsLat1() {
        assertForward("+proj=loxim +a=6400000 +lat_1=0.5 +lat_2=2", 2, 1,
                223382.295791339, 55850.536063819, MM_0_1);
    }

    /**
     * {@code builtins.gie:3947-3952} — {@code leac} takes {@code +lat_1} as its <em>second</em>
     * standard parallel and a pole as its first, and never reads {@code +lat_2}. Reading the two
     * fields the way {@code aea} does made it a different cone.
     */
    @Test
    public void leacUsesLat1AsItsSecondParallelAndAPoleAsItsFirst() {
        assertForward("+proj=leac +ellps=GRS80 +lat_1=0 +lat_2=2", 2, 1,
                220685.140542979, 112983.500889396, MM_0_1);
    }

    // ------------------------------------------------------- shape 2: lost in-place reassignment

    /**
     * {@code builtins.gie:7977-7981} — {@code wag1} shares {@code urmfps.cpp}, whose forward
     * reassigns {@code lp.phi = aasin(n sin phi)} and then uses it twice. Using the original
     * {@code phi} made the northing {@code 1/n} too large: 19.7 km.
     */
    @Test
    public void wag1AppliesTheReassignedLatitudeToBothOrdinates() {
        assertForward("+proj=wag1 +a=6400000", 2, 1, 195986.781561158, 127310.075060660, MM_0_1);
    }

    /** {@code builtins.gie:7712-7716} — the same defect in {@code urmfps} itself, with {@code +n=0.5}. */
    @Test
    public void urmfpsAppliesTheReassignedLatitudeToBothOrdinates() {
        assertForward("+proj=urmfps +a=6400000 +n=0.5", 2, 1,
                196001.708134192, 127306.843329993, MM_0_1);
    }

    /** {@code builtins.gie:8006-8010} — the same defect in {@code wag2}: 34.2 km. */
    @Test
    public void wag2AppliesTheReassignedLatitudeToBothOrdinates() {
        assertForward("+proj=wag2 +a=6400000", 2, 1, 206589.888099962, 120778.040357547, MM_0_1);
    }

    /** {@code builtins.gie:4174-4178} — the same defect in {@code mbtfpp}: 6.0 km. */
    @Test
    public void mbtfppAppliesTheReassignedLatitudeToBothOrdinates() {
        assertForward("+proj=mbtfpp +a=6400000", 2, 1, 206804.786929820, 120649.762565793, MM_0_1);
    }

    /**
     * {@code builtins.gie:1100-1102} — {@code collg}'s inverse reassigns {@code lp.phi} twice and
     * {@code asin} applies to the second. The old code returned the first, so the answered latitude
     * was the raw scaled northing: &minus;57.295&deg;, i.e. &minus;1 radian printed as degrees.
     * That value is the tell for this whole defect family.
     */
    @Test
    public void collgInverseAppliesAsinToTheSecondReassignment() {
        assertInverse("+proj=collg +a=6400000", 200, 100, 0.001586797, 0.001010173, MM_0_1);
    }

    /**
     * {@code builtins.gie:1981-1983} — {@code fahey}'s inverse divided the caller's output slot
     * rather than the northing, and then took {@code sqrt} of the undivided northing.
     */
    @Test
    public void faheyInverseDividesTheNorthingBeforeUsingIt() {
        assertInverse("+proj=fahey +a=6400000", 200, 100, 0.002185789, 0.000984246, MM_0_1);
    }

    // ------------------------------------------------------------------ shape 3: missing kernels

    /**
     * {@code builtins.gie:1638-1646} — the spherical arm, which is the one case the old identity
     * implementation got right. Kept as a pass&rarr;pass guard: this is the row a naive
     * "{@code eqc} is Plate Carr&eacute;e" rewrite would break.
     */
    @Test
    public void eqcSphericalIsUnchanged() {
        assertForward("+proj=eqc +a=6400000", 2, 1, 223402.144255274, 111701.072127637, MM_0_1);
    }

    /**
     * {@code builtins.gie:1667-1673} — the ellipsoidal arm, EPSG:1028, whose reference values the
     * corpus takes from IOGP Guidance Note 7-2 &sect;3.2.5. There was no ellipsoidal arm at all.
     */
    @Test
    public void eqcEllipsoidalUsesTheMeridianArc() {
        assertForward("+proj=eqc +ellps=WGS84 +lat_ts=0", 10, 55, 1113194.91, 6097230.31, 0.01);
    }

    /** {@code builtins.gie:1742-1748} — {@code +lat_ts} scales the easting by {@code nu1 cos(lat_ts)}. */
    @Test
    public void eqcAppliesLatTs() {
        assertForward("+proj=eqc +ellps=WGS84 +lat_ts=45", 2, 49, 157693.670, 5429627.632, 0.01);
    }

    /**
     * {@code builtins.gie:1771-1777} — {@code +lat_0} enters as {@code M0}, so the northing is zero
     * <em>at the origin latitude</em>. Without it the whole map is offset by {@code M(45)}: 5,010 km.
     */
    @Test
    public void eqcAppliesLat0AsAMeridianArcOffset() {
        assertForward("+proj=eqc +ellps=WGS84 +lat_ts=30 +lat_0=45", 0, 45, 0.0, 0.0, 0.01);
        assertForward("+proj=eqc +ellps=WGS84 +lat_ts=30 +lat_0=45", 0, 60, 0.0, 1669128.442, 0.01);
    }

    /**
     * {@code builtins.gie:6593-6597} — {@code sinu} is {@code Sph&Ell} upstream and had only the
     * spherical arm, so an ellipsoidal {@code sinu} was 745 m out in northing.
     */
    @Test
    public void sinuEllipsoidalUsesTheMeridianArc() {
        assertForward("+proj=sinu +ellps=GRS80", 2, 1, 222605.299539466, 110574.388554153, MM_0_1);
    }

    /** {@code builtins.gie:6616-6620} — the spherical arm, unchanged. */
    @Test
    public void sinuSphericalIsUnchanged() {
        assertForward("+proj=sinu +R=6400000", 2, 1, 223368.119026632, 111701.072127637, MM_0_1);
    }

    /**
     * {@code builtins.gie:2296-2302} — {@code gnom} on an ellipsoid is Karney's geodesic gnomonic,
     * a different kernel from the spherical four-aspect closed form, and its answers differ from the
     * sphere's in the fourth decimal at {@code a=1}. Proj4J had only the spherical kernel.
     */
    @Test
    public void gnomEllipsoidalUsesTheGeodesicKernel() {
        assertForward("+proj=gnom +a=1 +rf=200", 10, 80, 0.1763, 5.7232, MM_0_1);
        assertForward("+proj=gnom +a=1 +rf=200", 80, 10, 5.8813, 1.0465, MM_0_1);
    }

    /**
     * {@code builtins.gie:2364-2370} versus {@code builtins.gie:2378-2384} — <b>the domain contract
     * differs between the two kernels, and this is why both are needed.</b> On the sphere the north
     * polar aspect cannot see the equator, so {@code (0, 0)} is an error; on the ellipsoid the same
     * point is finite. Three corpus rows assert each, so no single kernel satisfies the file.
     */
    @Test
    public void gnomPolarDomainContractDiffersBetweenSphereAndEllipsoid() {
        try {
            project("+proj=gnom +R=1 +lat_0=90", 0, 0);
            fail("the spherical north polar gnomonic must refuse the equator: builtins.gie:2372");
        } catch (ProjectionException expected) {
            assertTrue("expected a domain refusal, got: " + expected.getMessage(), true);
        }
        assertForward("+proj=gnom +a=1 +rf=200 +lat_0=90", 0, 0, 0, -127.4835, MM_0_1);
    }

    /**
     * {@code 9.8.1:gnom.cpp:50} tests {@code xy.y &lt;= EPS10}, not {@code fabs(xy.y) &lt;= EPS10}.
     * {@code xy.y} at that point is the cosine of the angular distance from the projection centre,
     * so a negative value is a point beyond the horizon. Taking the absolute value accepted the far
     * hemisphere and folded it, mirrored, onto the near one — a plausible coordinate for a point
     * that has none.
     */
    @Test
    public void gnomRefusesPointsBeyondTheHorizonRatherThanMirroringThem() {
        // The corpus row. Note it lands EXACTLY on the horizon (xy.y == 0), so both the old
        // `fabs(xy.y) <= EPS10` and upstream's `xy.y <= EPS10` refuse it -- it does not
        // discriminate between them, which is why the second case below exists.
        try {
            project("+proj=gnom +R=1 +lat_0=45", 0, -45);
            fail("gnom must refuse a point on the horizon: builtins.gie:2434");
        } catch (ProjectionException expected) {
            assertTrue(true);
        }
        // Strictly BEYOND the horizon: xy.y = sin45 sin(-60) + cos45 cos(-60) = -0.2589. Upstream
        // refuses (gnom.cpp:50); the absolute-value form accepted it and returned the antipodal
        // point mirrored through the origin -- a plausible coordinate for a point that has none.
        // Derived from the 9.8.1 source rather than from a corpus row, and stated as such.
        try {
            ProjCoordinate mirrored = project("+proj=gnom +R=1 +lat_0=45", 0, -60);
            fail("gnom must refuse a point beyond the horizon, not mirror it; got " + mirrored);
        } catch (ProjectionException expected) {
            assertTrue(true);
        }
    }

    // ---------------------------------------------------------------------------------- plumbing

    private static Projection projection(String def) {
        CoordinateReferenceSystem crs = FACTORY.createFromParameters("test", def);
        return crs.getProjection();
    }

    private static ProjCoordinate project(String def, double lonDeg, double latDeg) {
        return projection(def).project(new ProjCoordinate(lonDeg, latDeg), new ProjCoordinate());
    }

    /**
     * Asserts a forward row: the target is projected, so the corpus metric is Euclidean metres and
     * the tolerance is read as metres directly.
     */
    private static void assertForward(String def, double lonDeg, double latDeg,
            double expectX, double expectY, double toleranceMetres) {
        ProjCoordinate got = project(def, lonDeg, latDeg);
        double d = GieComparator.xyDist(
                new double[] {expectX, expectY, 0, 0}, new double[] {got.x, got.y, 0, 0});
        assertEquals(def + " at (" + lonDeg + ", " + latDeg + "): expected ("
                + expectX + ", " + expectY + ") got (" + got.x + ", " + got.y + ")",
                0.0, d, toleranceMetres);
    }

    /**
     * Asserts an inverse row. The target is angular, so the corpus metric is a geodesic distance on
     * the operation's own ellipsoid, computed here by {@link GieComparator#lpDist} after the
     * degrees-to-radians conversion and the latitude-first swap that {@code gie} applies.
     */
    private static void assertInverse(String def, double x, double y,
            double expectLonDeg, double expectLatDeg, double toleranceMetres) {
        Projection p = projection(def);
        ProjCoordinate got = p.inverseProject(new ProjCoordinate(x, y), new ProjCoordinate());
        GieComparator comparator =
                GieComparator.forEllipsoid(p.getEllipsoid().getA(), flattening(p));
        double d = comparator.lpDist(
                new double[] {Math.toRadians(expectLonDeg), Math.toRadians(expectLatDeg), 0, 0},
                new double[] {Math.toRadians(got.x), Math.toRadians(got.y), 0, 0});
        assertEquals(def + " inverse at (" + x + ", " + y + "): expected ("
                + expectLonDeg + ", " + expectLatDeg + ") got (" + got.x + ", " + got.y + ")",
                0.0, d, toleranceMetres);
    }

    private static double flattening(Projection p) {
        double a = p.getEllipsoid().getA();
        double b = p.getEllipsoid().getB();
        return a == 0 ? 0 : (a - b) / a;
    }
}

/*******************************************************************************
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
 *******************************************************************************/

package org.locationtech.proj4j.geocent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.proj.GeocentProjection;
import org.locationtech.proj4j.proj.Projection;

/**
 * {@code +proj=geocent}, which had <b>no test at all</b> before this file.
 *
 * <h2>The defect these tests would have caught</h2>
 *
 * <p>{@code GeocentProjection.projectRadians} and {@code inverseProjectRadians} both read
 * {@code dst} instead of {@code src}, in both directions. It survived because the one caller in
 * {@code core} aliases the arguments — {@code BasicCoordinateTransform.transformClosed} does
 * {@code tgt.setValue(src)} then {@code projectRadians(tgt, tgt)} — so with {@code src == dst}
 * reading {@code dst} happens to read the input. Every test here that passes <b>distinct</b>
 * {@code src} and {@code dst} objects fails against the old code; every test that aliases them
 * passes against both, and those are here on purpose, because the aliased path is what 53,430
 * golden-master rows exercise and it must not move.
 *
 * <h2>Where the expected numbers come from</h2>
 *
 * <p>Not from proj4j. They are {@code 9.8.1:src/conversions/cart.cpp}'s {@code cartesian()}
 * evaluated independently:
 *
 * <pre>
 *   N = a / sqrt(1 - es sin^2(phi))
 *   X = (N + h) cos(phi) cos(lam)
 *   Y = (N + h) cos(phi) sin(lam)
 *   Z = (N (1 - es) + h) sin(phi)
 * </pre>
 *
 * <p>with WGS84 {@code a = 6378137.0} and {@code es = 2f - f^2}, {@code f = 1/298.257223563}.
 * That is the same expression, in the same order, as
 * {@code GeocentricConverter.convertGeodeticToGeocentric}, which is the finding recorded in
 * {@code GeocentProjection}'s javadoc: the two implementations of the <em>forward</em> agree bit
 * for bit, and only the inverse differs (Bowring's closed form upstream, the Toms/Hannover
 * iteration here). The tolerance is 1e-6 m, which is looser than either algorithm's own error and
 * covers the last-bit difference between deriving {@code es} from {@code rf} and quoting it.
 */
public class GeocentProjectionTest {

    /** Tighter than any algorithm here: both kernels agree to well under a micrometre. */
    private static final double MM = 1.0e-6;

    /** 1e-9 rad is about 6 mm of latitude; the iteration's own budget is 1e-12 rad. */
    private static final double RAD = 1.0e-9;

    private static Projection wgs84Geocent() {
        CoordinateReferenceSystem crs =
                new CRSFactory().createFromParameters("geocent-wgs84",
                        "+proj=geocent +ellps=WGS84 +units=m +no_defs");
        return crs.getProjection();
    }

    // -----------------------------------------------------------------------------------------
    // The defect itself: src must be read, dst must be written, and the two may be different
    // objects. Every assertion in this section fails against the pre-1.5.0 body.
    // -----------------------------------------------------------------------------------------

    @Test
    public void forwardReadsSrcNotDstWhenTheyAreDistinctObjects() {
        Projection p = wgs84Geocent();
        ProjCoordinate src = new ProjCoordinate(Math.toRadians(9.5), Math.toRadians(55.5), 100.0);
        // Deliberately poisoned: the old code converted THIS and returned it.
        ProjCoordinate dst = new ProjCoordinate(-1.0, -1.0, -1.0);

        p.projectRadians(src, dst);

        assertNotSame(src, dst);
        assertEquals(3571255.4410952283, dst.x, MM);
        assertEquals(597623.2032090913, dst.y, MM);
        assertEquals(5233194.16771844, dst.z, MM);
    }

    @Test
    public void forwardLeavesSrcUntouched() {
        Projection p = wgs84Geocent();
        ProjCoordinate src = new ProjCoordinate(Math.toRadians(9.5), Math.toRadians(55.5), 100.0);
        ProjCoordinate dst = new ProjCoordinate();

        p.projectRadians(src, dst);

        assertEquals(Math.toRadians(9.5), src.x, 0.0);
        assertEquals(Math.toRadians(55.5), src.y, 0.0);
        assertEquals(100.0, src.z, 0.0);
    }

    @Test
    public void inverseReadsSrcNotDstWhenTheyAreDistinctObjects() {
        Projection p = wgs84Geocent();
        ProjCoordinate src =
                new ProjCoordinate(3571255.4410952283, 597623.2032090913, 5233194.16771844);
        ProjCoordinate dst = new ProjCoordinate(-1.0, -1.0, -1.0);

        p.inverseProjectRadians(src, dst);

        assertNotSame(src, dst);
        assertEquals(Math.toRadians(9.5), dst.x, RAD);
        assertEquals(Math.toRadians(55.5), dst.y, RAD);
        assertEquals(100.0, dst.z, 1.0e-4);
    }

    @Test
    public void inverseLeavesSrcUntouched() {
        Projection p = wgs84Geocent();
        ProjCoordinate src =
                new ProjCoordinate(3571255.4410952283, 597623.2032090913, 5233194.16771844);
        ProjCoordinate dst = new ProjCoordinate();

        p.inverseProjectRadians(src, dst);

        assertEquals(3571255.4410952283, src.x, 0.0);
        assertEquals(597623.2032090913, src.y, 0.0);
        assertEquals(5233194.16771844, src.z, 0.0);
    }

    /**
     * The degrees-in entry point is not virtual through {@code projectRadians(src, dst)} — the base
     * class routes it into a private two-ordinate funnel — so before 1.5.0 it never reached
     * {@code GeocentProjection} at all and returned the base identity plus the affine, i.e. the
     * input degrees back.
     */
    @Test
    public void degreesEntryPointReachesTheGeocentricConversion() {
        Projection p = wgs84Geocent();
        ProjCoordinate src = new ProjCoordinate(9.5, 55.5, 100.0);
        ProjCoordinate dst = new ProjCoordinate();

        p.project(src, dst);

        assertEquals(3571255.4410952283, dst.x, MM);
        assertEquals(597623.2032090913, dst.y, MM);
        assertEquals(5233194.16771844, dst.z, MM);
    }

    /** {@code inverseProject} is the radians inverse plus a RTD multiply on x and y only. */
    @Test
    public void degreesInverseEntryPointReturnsDegreesAndMetres() {
        Projection p = wgs84Geocent();
        ProjCoordinate src =
                new ProjCoordinate(3571255.4410952283, 597623.2032090913, 5233194.16771844);
        ProjCoordinate dst = new ProjCoordinate();

        p.inverseProject(src, dst);

        assertEquals(9.5, dst.x, 1.0e-7);
        assertEquals(55.5, dst.y, 1.0e-7);
        assertEquals(100.0, dst.z, 1.0e-4);
    }

    // -----------------------------------------------------------------------------------------
    // The aliased path. These pass against the old body too, and that is the point: 1,058
    // golden-master rows go through it and must not move.
    // -----------------------------------------------------------------------------------------

    @Test
    public void aliasedForwardStillWorks() {
        Projection p = wgs84Geocent();
        ProjCoordinate both =
                new ProjCoordinate(Math.toRadians(9.5), Math.toRadians(55.5), 100.0);

        p.projectRadians(both, both);

        assertEquals(3571255.4410952283, both.x, MM);
        assertEquals(597623.2032090913, both.y, MM);
        assertEquals(5233194.16771844, both.z, MM);
    }

    @Test
    public void aliasedInverseStillWorks() {
        Projection p = wgs84Geocent();
        ProjCoordinate both =
                new ProjCoordinate(3571255.4410952283, 597623.2032090913, 5233194.16771844);

        p.inverseProjectRadians(both, both);

        assertEquals(Math.toRadians(9.5), both.x, RAD);
        assertEquals(Math.toRadians(55.5), both.y, RAD);
        assertEquals(100.0, both.z, 1.0e-4);
    }

    // -----------------------------------------------------------------------------------------
    // cart.cpp agreement across the ellipsoid and the domain.
    // -----------------------------------------------------------------------------------------

    /** Prime meridian on the equator: X is exactly {@code a}, Y and Z exactly zero. */
    @Test
    public void originIsTheSemiMajorAxis() {
        Projection p = wgs84Geocent();
        ProjCoordinate dst = new ProjCoordinate();

        p.projectRadians(new ProjCoordinate(0.0, 0.0, 0.0), dst);

        assertEquals(6378137.0, dst.x, 0.0);
        assertEquals(0.0, dst.y, 0.0);
        assertEquals(0.0, dst.z, 0.0);
    }

    /** The north pole: Z is exactly the semi-minor axis, {@code a (1 - f)}. */
    @Test
    public void northPoleIsTheSemiMinorAxis() {
        Projection p = wgs84Geocent();
        ProjCoordinate dst = new ProjCoordinate();

        p.projectRadians(new ProjCoordinate(0.0, Math.PI / 2.0, 0.0), dst);

        assertEquals(0.0, dst.x, MM);
        assertEquals(0.0, dst.y, 0.0);
        assertEquals(6356752.314245179, dst.z, MM);
    }

    @Test
    public void southernHemisphereAndNegativeLongitudeWithHeight() {
        Projection p = wgs84Geocent();
        ProjCoordinate dst = new ProjCoordinate();

        p.projectRadians(
                new ProjCoordinate(Math.toRadians(-77.0), Math.toRadians(-38.0), 1234.5), dst);

        assertEquals(1132269.1140115347, dst.x, MM);
        assertEquals(-4904396.350538061, dst.y, MM);
        assertEquals(-3906204.0025103916, dst.z, MM);
    }

    /**
     * {@code cosphi < 1e-6} in {@code cart.cpp:225} — poleward of 89.99994 degrees the height
     * comes from the geocentric radius rather than from a division by a vanishing cosine. The
     * iteration reaches the same answer by a different route, so this asserts the round trip
     * rather than the branch.
     */
    @Test
    public void nearPoleRoundTrips() {
        Projection p = wgs84Geocent();
        ProjCoordinate xyz = new ProjCoordinate();
        ProjCoordinate back = new ProjCoordinate();

        p.projectRadians(new ProjCoordinate(Math.toRadians(120.0), Math.toRadians(89.9), 0.0), xyz);
        assertEquals(-5584.696085303042, xyz.x, MM);
        assertEquals(9672.977364575885, xyz.y, MM);
        assertEquals(6356742.567109314, xyz.z, MM);

        p.inverseProjectRadians(xyz, back);
        assertEquals(Math.toRadians(120.0), back.x, RAD);
        assertEquals(Math.toRadians(89.9), back.y, RAD);
        assertEquals(0.0, back.z, 1.0e-4);
    }

    /**
     * A declared sphere: {@code es == 0}, so {@code N == a} at every latitude. Spelled
     * {@code +a}/{@code +b} rather than {@code +R} on purpose — {@code +R}'s parse path is being
     * rewritten by another stream (see {@code PARSE-R-DECLARES-SPHERE} in
     * {@code golden/rules.yaml}) and this test is about the conversion, not about the parser.
     */
    @Test
    public void sphereUsesTheRadiusAtEveryLatitude() {
        CoordinateReferenceSystem crs = new CRSFactory().createFromParameters("geocent-sphere",
                "+proj=geocent +a=6371000 +b=6371000 +units=m +no_defs");
        ProjCoordinate dst = new ProjCoordinate();

        crs.getProjection().projectRadians(
                new ProjCoordinate(Math.toRadians(30.0), Math.toRadians(60.0), 0.0), dst);

        assertEquals(2758723.9237553305, dst.x, MM);
        assertEquals(1592750.0000000002, dst.y, MM);
        assertEquals(5517447.847510658, dst.z, MM);
    }

    @Test
    public void roundTripsOverAGridOfTheWholeEllipsoid() {
        Projection p = wgs84Geocent();
        ProjCoordinate xyz = new ProjCoordinate();
        ProjCoordinate back = new ProjCoordinate();
        for (double lon = -180.0; lon <= 180.0; lon += 15.0) {
            for (double lat = -89.0; lat <= 89.0; lat += 7.0) {
                for (double h : new double[] {-500.0, 0.0, 8848.0}) {
                    p.projectRadians(
                            new ProjCoordinate(Math.toRadians(lon), Math.toRadians(lat), h), xyz);
                    p.inverseProjectRadians(xyz, back);
                    String at = "(" + lon + ", " + lat + ", " + h + ")";
                    // atan2 answers -pi for a longitude of exactly 180 west; both are the
                    // antimeridian.
                    double dlon = Math.abs(back.x - Math.toRadians(lon)) % (2.0 * Math.PI);
                    assertEquals(at, 0.0, Math.min(dlon, 2.0 * Math.PI - dlon), RAD);
                    assertEquals(at, Math.toRadians(lat), back.y, RAD);
                    assertEquals(at, h, back.z, 1.0e-4);
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // +lon_0, which upstream honours for a cartesian right-hand side and the override skipped.
    // -----------------------------------------------------------------------------------------

    /**
     * {@code fwd_prepare} ends the angular branch with {@code lam = (lam - from_greenwich) - lam0}
     * ({@code 9.8.1:src/fwd.cpp:105-112}) whatever {@code P->right} is, and {@code inv_finalize}
     * adds it back ({@code inv.cpp:110-118}). So {@code +lon_0=10} at longitude 10 must give the
     * same triple as {@code +lon_0=0} at longitude 0 — Y exactly zero.
     */
    @Test
    public void lonZeroRotatesTheCartesianFrame() {
        CoordinateReferenceSystem shifted = new CRSFactory().createFromParameters("geocent-lon0",
                "+proj=geocent +ellps=GRS80 +lon_0=10 +units=m +no_defs");
        ProjCoordinate dst = new ProjCoordinate();

        shifted.getProjection().projectRadians(
                new ProjCoordinate(Math.toRadians(10.0), Math.toRadians(45.0), 0.0), dst);

        assertEquals(4517590.878886053, dst.x, MM);
        assertEquals(0.0, dst.y, MM);
        assertEquals(4487348.4087547995, dst.z, MM);
    }

    @Test
    public void lonZeroRoundTrips() {
        CoordinateReferenceSystem shifted = new CRSFactory().createFromParameters("geocent-lon0",
                "+proj=geocent +ellps=GRS80 +lon_0=10 +units=m +no_defs");
        Projection p = shifted.getProjection();
        ProjCoordinate xyz = new ProjCoordinate();
        ProjCoordinate back = new ProjCoordinate();

        p.projectRadians(new ProjCoordinate(Math.toRadians(-13.25), Math.toRadians(7.5), 42.0), xyz);
        p.inverseProjectRadians(xyz, back);

        assertEquals(Math.toRadians(-13.25), back.x, RAD);
        assertEquals(Math.toRadians(7.5), back.y, RAD);
        assertEquals(42.0, back.z, 1.0e-4);
    }

    /** Without {@code +lon_0} nothing may be added, because {@code x + 0.0} is not the identity. */
    @Test
    public void withoutLonZeroNegativeZeroSurvives() {
        Projection p = wgs84Geocent();
        ProjCoordinate dst = new ProjCoordinate();

        // Longitude exactly 180 west: atan2(-0.0, -a) is -pi, and the sign carries which side of
        // the antimeridian the point is on.
        p.inverseProjectRadians(new ProjCoordinate(-6378137.0, -0.0, 0.0), dst);

        assertTrue("expected -pi, got " + dst.x, dst.x < 0.0);
        assertEquals(-Math.PI, dst.x, RAD);
    }

    // -----------------------------------------------------------------------------------------
    // Contract: hasInverse, the name, and the fail-closed guards.
    // -----------------------------------------------------------------------------------------

    /**
     * {@code BasicCoordinateTransform.inverseAvailable} asks {@code hasInverse()} first and then
     * looks for a declared {@code projectInverse(double, double, ProjCoordinate)}. This class has
     * no such method — the two-ordinate signature cannot carry z — so without the declaration the
     * gate rejects every {@code +proj=geocent} CRS as a transformation source.
     */
    @Test
    public void declaresItsInverse() {
        assertTrue(new GeocentProjection().hasInverse());
        assertTrue(wgs84Geocent().hasInverse());
    }

    /** The base {@code toString()} is the literal {@code "None"}, which lands in error messages. */
    @Test
    public void hasAName() {
        assertEquals("Geocentric", new GeocentProjection().toString());
    }

    @Test
    public void forwardRejectsALatitudePastThePole() {
        Projection p = wgs84Geocent();
        try {
            p.projectRadians(new ProjCoordinate(0.0, Math.toRadians(91.0), 0.0),
                    new ProjCoordinate());
            fail("expected a ProjectionException for latitude 91 deg");
        } catch (ProjectionException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("latitude"));
        }
    }

    /** {@code fwd_prepare}'s slop band, {@code fwd.cpp:72-77}: clamp, do not reject. */
    @Test
    public void forwardClampsWithinTheSlopBand() {
        Projection p = wgs84Geocent();
        ProjCoordinate atPole = new ProjCoordinate();
        ProjCoordinate justPast = new ProjCoordinate();

        p.projectRadians(new ProjCoordinate(0.0, Math.PI / 2.0, 0.0), atPole);
        p.projectRadians(new ProjCoordinate(0.0, Math.PI / 2.0 + 1.0e-13, 0.0), justPast);

        assertEquals(atPole.x, justPast.x, 0.0);
        assertEquals(atPole.y, justPast.y, 0.0);
        assertEquals(atPole.z, justPast.z, 0.0);
    }

    @Test
    public void forwardRejectsNonFiniteInput() {
        Projection p = wgs84Geocent();
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY}) {
            try {
                p.projectRadians(new ProjCoordinate(0.0, bad, 0.0), new ProjCoordinate());
                fail("expected a ProjectionException for latitude " + bad);
            } catch (ProjectionException expected) {
                // the contract: non-finite in, exception out, never a plausible coordinate
            }
        }
    }

    /** {@code inv_prepare} rejects HUGE_VAL on all three ordinates ({@code inv.cpp:40-45}). */
    @Test
    public void inverseRejectsNonFiniteInput() {
        Projection p = wgs84Geocent();
        double[][] bad = {
                {Double.NaN, 0.0, 0.0},
                {0.0, Double.POSITIVE_INFINITY, 0.0},
                {4517590.0, 0.0, Double.NEGATIVE_INFINITY},
        };
        for (double[] xyz : bad) {
            try {
                p.inverseProjectRadians(new ProjCoordinate(xyz[0], xyz[1], xyz[2]),
                        new ProjCoordinate());
                fail("expected a ProjectionException for (" + xyz[0] + ", " + xyz[1] + ", "
                        + xyz[2] + ")");
            } catch (ProjectionException expected) {
                assertTrue(expected.getMessage(),
                        expected.getMessage().contains("non-finite"));
            }
        }
    }

    /**
     * An absent z is zero, not NaN. {@code ProjCoordinate}'s two-argument constructor leaves z as
     * {@code NaN}, and a geocentric triple with a NaN ordinate is exactly the shape a caller
     * cannot detect.
     */
    @Test
    public void absentHeightIsTreatedAsZero() {
        Projection p = wgs84Geocent();
        ProjCoordinate withoutZ = new ProjCoordinate();
        ProjCoordinate withZeroZ = new ProjCoordinate();

        p.projectRadians(new ProjCoordinate(Math.toRadians(9.5), Math.toRadians(55.5)), withoutZ);
        p.projectRadians(
                new ProjCoordinate(Math.toRadians(9.5), Math.toRadians(55.5), 0.0), withZeroZ);

        assertEquals(withZeroZ.x, withoutZ.x, 0.0);
        assertEquals(withZeroZ.y, withoutZ.y, 0.0);
        assertEquals(withZeroZ.z, withoutZ.z, 0.0);
    }

    /**
     * The ellipsoid may be replaced after construction, so the cached converter has to be keyed on
     * it. Getting this wrong would make the second call answer with the first ellipsoid.
     */
    @Test
    public void aReplacedEllipsoidIsHonoured() {
        GeocentProjection p = new GeocentProjection();
        ProjCoordinate onWgs84 = new ProjCoordinate();
        ProjCoordinate onSphere = new ProjCoordinate();

        p.setEllipsoid(org.locationtech.proj4j.datum.Ellipsoid.WGS84);
        p.initialize();
        p.projectRadians(new ProjCoordinate(0.0, Math.toRadians(45.0), 0.0), onWgs84);

        p.setEllipsoid(new org.locationtech.proj4j.datum.Ellipsoid(
                "test-sphere", 6371000.0, 6371000.0, 0.0, "test sphere"));
        p.initialize();
        p.projectRadians(new ProjCoordinate(0.0, Math.toRadians(45.0), 0.0), onSphere);

        assertEquals(4517590.878848932, onWgs84.x, MM);
        assertEquals(6371000.0 * Math.cos(Math.toRadians(45.0)), onSphere.x, MM);
        assertEquals(6371000.0 * Math.sin(Math.toRadians(45.0)), onSphere.z, MM);
    }
}

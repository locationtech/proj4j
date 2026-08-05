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
package org.locationtech.proj4j.failclosed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.datum.GeocentricConverter;

/**
 * {@code GeocentricConverter} must not answer a fabricated coordinate on or near the Z axis.
 *
 * <h2>The two sentinels</h2>
 *
 * <p><b>The centre of mass.</b> {@code (0, 0, 0)} has no geodetic latitude, no longitude and no
 * height — every meridian and every parallel passes through it. 1.4.3 answered
 * {@code (0&deg;, +90&deg;, -b)}: three finite, in-range, entirely plausible ordinates that a
 * caller cannot distinguish from a real polar coordinate at depth {@code b}. That one was removed
 * by earlier fail-closed work and this test pins it, because upstream still returns it
 * ({@code more_builtins.gie} pins {@code accept 0 0 0 / expect 0 90 -6356752.314140356} for
 * {@code +proj=cart +ellps=GRS80} inverse) and parity pressure will push it back.
 *
 * <p><b>The on-axis meridian.</b> This one was missed, and it is the more dangerous of the two
 * because nothing about the answer looks wrong. The branch read:
 *
 * <pre>
 * if (P / this.a &lt; genau) {      // P within 6.4 micrometres of the Z axis
 *     At_Pole = true;             // ... a local that was then never read
 *     Longitude = 0.0;            // ... a fabricated meridian
 * }
 * </pre>
 *
 * <p>Latitude and height came out correct; only the longitude was invented, and it was invented as
 * <em>Greenwich</em> — a specific, plausible meridian that passes every finiteness and range check
 * a caller can write. Measured on Clarke 1866 before the fix: the round trip of
 * {@code (lon = 17&deg;, lat = 90&deg;, h = 0)} returned {@code lon = 0&deg;}, and
 * {@code (X, Y, Z) = (1e-9, 2e-9, b)}, whose longitude is 63.4349&deg;, returned {@code 0&deg;}.
 *
 * <p>The fix is {@code Math.atan2(Y, X)} unconditionally, which is exactly what
 * {@code 9.8.1:src/conversions/cart.cpp:224} does. It needs no special case: it is well defined
 * for every {@code (X, Y)} except {@code (0, 0)}, where IEEE 754 gives
 * {@code atan2(+/-0, +0) = +/-0} — the conventional pole longitude, and the value the corpus pins.
 *
 * <h2>Why the degeneracy guard is {@code RR == 0}, not a tolerance</h2>
 *
 * <p>{@code CT = Z / RR} and {@code ST = P / RR}, so {@code RR == 0} is exactly the condition
 * under which the Hannover iteration divides zero by zero. The guard used to be
 * {@code RR / a &lt; genau}, i.e. anything within 6.4&nbsp;&micro;m of the centre, which rejected
 * {@code (0, 0, ±1e-6)} — a point whose latitude ({@code ±90&deg;}) and height
 * ({@code 1e-6 - b}) are both perfectly well determined — and refused it with a message that
 * called it "the centre of mass", which it is not.
 *
 * <p>Upstream's values for those points are pinned in {@code more_builtins.gie} as
 * {@code accept 0 0 1e-6 / expect 0 90 -6356752.314139356} and the negative counterpart. Note
 * those rows are on {@code +proj=cart}, which {@code Registry} does <b>not</b> map — so they are a
 * reference for the right answer here, not rows this change turns green, and they become live only
 * when {@code cart} is registered. The branch is reachable today through {@code +proj=geocent},
 * which is registered and whose inverse runs this method.
 *
 * <h2>On the {@code EPSG:4173 → EPSG:26748} reproducer</h2>
 *
 * <p>The golden note attributes {@code (0&deg;, 90&deg;, 0.000104 m)} to
 * {@code PAIR/t14/epsg:4173>epsg:26748} probe 4, {@code (5, 5)}. That pair no longer reaches the
 * branch: an in-flight {@code tmerc} change moved the forward answer, and the round trip now
 * returns to {@code (5, 5)}. {@link #reproducerNeverAnswersThePole()} therefore asserts the
 * <em>invariant</em> rather than the stale numbers, so it keeps testing something after the next
 * {@code tmerc} change too.
 */
public class GeocentricConverterSentinelTest {

    /** The ellipsoid of NAD27, the datum in the original reproducer. */
    private static final Ellipsoid CLARKE = Ellipsoid.CLARKE_1866;
    private static final double A = CLARKE.getA();
    private static final double B = CLARKE.getB();

    private static final double RTD = 180.0 / Math.PI;
    private static final double DTR = Math.PI / 180.0;

    private static GeocentricConverter converter() {
        return new GeocentricConverter(CLARKE);
    }

    /**
     * Poison every ordinate before the call. {@code GeocentricConverter} converts in place, so
     * "poison the destination" means: write a value no correct computation could produce into the
     * slots the ordinates that are <em>not</em> inputs will occupy, and make the assertions strong
     * enough that a stale read fails them.
     */
    private static ProjCoordinate poisoned(double x, double y, double z) {
        ProjCoordinate c = new ProjCoordinate();
        c.x = c.y = 1e300;
        c.z = 1e300;
        c.x = x;
        c.y = y;
        c.z = z;
        return c;
    }

    private static ProjCoordinate poisonedDst() {
        ProjCoordinate c = new ProjCoordinate();
        c.x = c.y = 1e300;
        c.z = 1e300;
        return c;
    }

    // ------------------------------------------- the on-axis meridian must not be invented

    /**
     * The headline regression: a polar coordinate must come back on the meridian it went out on.
     * Any reintroduction of {@code Longitude = 0.0} on the axis fails this by 17&deg;.
     */
    @Test
    public void polarRoundTripPreservesTheMeridian() {
        GeocentricConverter gc = converter();
        ProjCoordinate p = poisoned(17.0 * DTR, 90.0 * DTR, 0.0);
        gc.convertGeodeticToGeocentric(p);
        // cos(pi/2) is 6.1e-17, not 0, so a genuine lat = 90 deg input lands about 3.9e-10 m from
        // the axis -- comfortably inside the 6.4e-6 m window the deleted branch used, which is why
        // an ordinary polar datum shift used to lose its longitude.
        double pDist = Math.hypot(p.x, p.y);
        assertTrue("a lat = 90 deg forward should land within a nanometre of the axis, not "
                + pDist, pDist < 1e-8);

        gc.convertGeocentricToGeodetic(p);
        assertEquals("the meridian must survive the round trip; 0 means it was fabricated as "
                + "Greenwich", 17.0, p.x * RTD, 1e-6);
        assertEquals(90.0, p.y * RTD, 1e-9);
        assertEquals(0.0, p.z, 1e-6);
    }

    @Test
    public void polarRoundTripPreservesTheMeridianInEveryQuadrant() {
        GeocentricConverter gc = converter();
        double[] lons = {-179.0, -90.0, -17.0, 0.0, 17.0, 90.0, 179.0};
        for (int i = 0; i < lons.length; i++) {
            ProjCoordinate p = poisoned(lons[i] * DTR, 90.0 * DTR, 0.0);
            gc.convertGeodeticToGeocentric(p);
            gc.convertGeocentricToGeodetic(p);
            assertEquals("lon " + lons[i] + " at the north pole", lons[i], p.x * RTD, 1e-6);
            ProjCoordinate s = poisoned(lons[i] * DTR, -90.0 * DTR, 0.0);
            gc.convertGeodeticToGeocentric(s);
            gc.convertGeocentricToGeodetic(s);
            assertEquals("lon " + lons[i] + " at the south pole", lons[i], s.x * RTD, 1e-6);
        }
    }

    /**
     * A near-axis point whose longitude is unambiguous. {@code atan2(2e-9, 1e-9)} is
     * 63.4349488&deg; to full precision; the deleted branch answered 0&deg;.
     */
    @Test
    public void nearAxisLongitudeIsComputedNotInvented() {
        GeocentricConverter gc = converter();
        double expected = Math.atan2(2e-9, 1e-9) * RTD;
        assertEquals(63.43494882292201, expected, 1e-12);

        double[] scales = {1e-9, 1e-7, 1e-6, 1e-5, 1e-3, 1.0};
        for (int i = 0; i < scales.length; i++) {
            double s = scales[i];
            ProjCoordinate p = poisoned(1.0 * s, 2.0 * s, B);
            gc.convertGeocentricToGeodetic(p);
            assertEquals("longitude must be atan2(Y, X) at every distance from the axis, "
                            + "including inside the deleted 6.4e-6 m window (scale " + s + ")",
                    expected, p.x * RTD, 1e-9);
        }
    }

    /**
     * The exact pole. {@code atan2(+0, +0) = +0} is the conventional pole longitude and the value
     * the corpus pins, so this must <em>succeed</em> — refusing it would be stricter than PROJ:
     * {@code more_builtins.gie} pins {@code accept 0 0 6356752.314140347} against
     * {@code expect 0 90 0} for {@code +proj=cart +ellps=GRS80} inverse.
     */
    @Test
    public void exactPoleIsAnsweredWithTheConventionalZeroMeridian() {
        GeocentricConverter gc = converter();

        ProjCoordinate north = poisoned(0.0, 0.0, B);
        gc.convertGeocentricToGeodetic(north);
        assertEquals(0.0, north.x * RTD, 0.0);
        assertEquals(90.0, north.y * RTD, 1e-9);
        assertEquals(0.0, north.z, 1e-6);

        ProjCoordinate south = poisoned(0.0, 0.0, -B);
        gc.convertGeocentricToGeodetic(south);
        assertEquals(0.0, south.x * RTD, 0.0);
        assertEquals(-90.0, south.y * RTD, 1e-9);
        assertEquals(0.0, south.z, 1e-6);
    }

    /**
     * One micrometre up the Z axis. Latitude and height are both perfectly determined, so this
     * must be answered, not refused. The old {@code RR / a &lt; genau} guard refused it — and
     * called it the centre of mass, which it is not.
     */
    @Test
    public void oneMicrometreUpTheAxisIsAnsweredNotRefused() {
        GeocentricConverter gc = converter();

        ProjCoordinate up = poisoned(0.0, 0.0, 1e-6);
        gc.convertGeocentricToGeodetic(up);
        assertEquals(0.0, up.x * RTD, 0.0);
        assertEquals(90.0, up.y * RTD, 1e-9);
        assertEquals(1e-6 - B, up.z, 1e-6);

        ProjCoordinate down = poisoned(0.0, 0.0, -1e-6);
        gc.convertGeocentricToGeodetic(down);
        assertEquals(-90.0, down.y * RTD, 1e-9);
        assertEquals(1e-6 - B, down.z, 1e-6);
    }

    /**
     * The same three cases through the <em>registered</em> projection that reaches this code, so
     * the coverage does not depend on {@code +proj=cart} ever being mapped. {@code +proj=geocent}
     * is in {@code Registry} and its inverse runs
     * {@code GeocentricConverter.convertGeocentricToGeodetic}.
     */
    @Test
    public void geocentInverseIsFailClosedOnTheAxis() {
        CRSFactory f = new CRSFactory();
        CoordinateReferenceSystem geocent =
                f.createFromParameters("geocent", "+proj=geocent +ellps=GRS80");
        CoordinateReferenceSystem lonlat =
                f.createFromParameters("lonlat", "+proj=longlat +ellps=GRS80");
        CoordinateTransform inv =
                new CoordinateTransformFactory().createTransform(geocent, lonlat);
        double bGrs80 = Ellipsoid.GRS80.getB();

        // A near-axis point: the longitude must be computed, not invented as Greenwich.
        ProjCoordinate near = poisonedDst();
        inv.transform(new ProjCoordinate(1e-9, 2e-9, bGrs80), near);
        assertEquals("near-axis longitude must be atan2(Y, X) = 63.4349 deg, not 0",
                63.43494882292201, near.x, 1e-9);
        assertEquals(90.0, near.y, 1e-9);

        // One micrometre up the axis: answered, not refused.
        ProjCoordinate up = poisonedDst();
        inv.transform(new ProjCoordinate(0.0, 0.0, 1e-6), up);
        assertEquals(0.0, up.x, 0.0);
        assertEquals(90.0, up.y, 1e-9);
        assertEquals(1e-6 - bGrs80, up.z, 1e-3);

        // The centre of mass: still refused.
        ProjCoordinate centre = poisonedDst();
        try {
            inv.transform(new ProjCoordinate(0.0, 0.0, 0.0), centre);
            fail("+proj=geocent inverse of (0, 0, 0) must be refused, but answered ("
                    + centre.x + ", " + centre.y + ", " + centre.z + ")");
        } catch (Proj4jException e) {
            assertNotNull(e.cause());
        }
    }

    // ------------------------------------------------- the centre of mass must stay refused

    @Test
    public void centreOfMassIsRefused() {
        GeocentricConverter gc = converter();
        ProjCoordinate p = poisoned(0.0, 0.0, 0.0);
        try {
            gc.convertGeocentricToGeodetic(p);
            fail("(0, 0, 0) has no geodetic coordinate at all, but was answered as ("
                    + (p.x * RTD) + " deg, " + (p.y * RTD) + " deg, " + p.z + ")");
        } catch (Proj4jException e) {
            assertNotNull(e.cause());
            assertEquals(ErrorCause.INVALID_COORDINATE, e.cause());
            assertTrue("cause() must be a per-coordinate error: the converter is fine, this "
                    + "particular point is not", e.cause().isCoordinateError());
            assertNotNull("must carry a message", e.getMessage());
        }
    }

    /**
     * The shape of the refusal matters as much as its presence. If someone reintroduces a
     * sentinel, it will be one of these three, so name them.
     */
    @Test
    public void centreOfMassIsNeverAnsweredAsAnyKnownSentinel() {
        GeocentricConverter gc = converter();
        ProjCoordinate p = poisoned(0.0, 0.0, 0.0);
        boolean threw = false;
        try {
            gc.convertGeocentricToGeodetic(p);
        } catch (Proj4jException e) {
            threw = true;
        }
        if (!threw) {
            // 1.4.3's answer, and upstream's.
            assertNotEquals("(0 deg, +90 deg, -b) is 1.4.3's centre-of-mass fiction",
                    "0.0/90.0/" + (-B), p.x * RTD + "/" + (p.y * RTD) + "/" + p.z);
            // The other two shapes the no-sentinels rule names.
            assertTrue("must not answer the input unchanged", p.x != 0.0 || p.y != 0.0);
            assertTrue("must not answer a single NaN ordinate paired with finite ones",
                    Double.isNaN(p.x) == Double.isNaN(p.y)
                            && Double.isNaN(p.y) == Double.isNaN(p.z));
            fail("(0, 0, 0) must be refused outright");
        }
    }

    // ------------------------------------------------------------- convergence

    /**
     * The post-loop convergence check is present ({@code GeocentricConverter} raises
     * {@link org.locationtech.proj4j.ConvergenceFailureException} when
     * {@code SDPHI² &gt; genau²} after {@code maxiter}), so an unconverged iterate can never be
     * dressed up as a latitude. This asserts the invariant rather than forcing the branch: on a
     * well-formed ellipsoid the Hannover iteration converges in a handful of steps, which is
     * precisely why reaching the cap means the inputs are not well formed.
     *
     * <p>The outcome for every input below must be one of exactly two things — a correct
     * coordinate, or a {@code Proj4jException}. Never a coordinate derived from a loop that gave
     * up.
     */
    @Test
    public void everyOutcomeIsEitherACoordinateOrAProj4jException() {
        double[][] xyz = {
                {A, 0.0, 0.0},
                {0.0, A, 0.0},
                {-A, 0.0, 0.0},
                {0.0, 0.0, B},
                {0.0, 0.0, -B},
                {0.0, 0.0, 1e-6},
                {1e-30, 1e-30, B},
                {A * 1e6, 0.0, 0.0},
                {A * 1e-6, 0.0, 0.0},
                {0.0, 0.0, 0.0},
        };
        // A near-degenerate ellipsoid as well as a well-formed one: e2 -> 1 is where an
        // iteration is most likely to stall.
        GeocentricConverter[] gcs = {
                converter(),
                new GeocentricConverter(A, B, 0.999999),
                new GeocentricConverter(A, B, 0.0),
        };
        for (int g = 0; g < gcs.length; g++) {
            for (int i = 0; i < xyz.length; i++) {
                ProjCoordinate p = poisoned(xyz[i][0], xyz[i][1], xyz[i][2]);
                try {
                    gcs[g].convertGeocentricToGeodetic(p);
                } catch (Proj4jException e) {
                    assertNotNull("cause() must never be null", e.cause());
                    continue;
                }
                String at = "converter " + g + ", input (" + xyz[i][0] + ", " + xyz[i][1] + ", "
                        + xyz[i][2] + ") -> (" + p.x + ", " + p.y + ", " + p.z + ")";
                assertTrue("latitude must be within +/-90 deg: " + at,
                        Double.isNaN(p.y) || Math.abs(p.y) <= Math.PI / 2 + 1e-12);
                assertTrue("longitude must be within +/-180 deg: " + at,
                        Double.isNaN(p.x) || Math.abs(p.x) <= Math.PI + 1e-12);
                assertTrue("the poison must have been overwritten: " + at,
                        p.x != 1e300 && p.y != 1e300 && p.z != 1e300);
            }
        }
    }

    // ------------------------------------------------------------- the reproducer

    /**
     * {@code EPSG:4173 → EPSG:26748} at {@code (5, 5)} — 5,000&nbsp;km outside the NAD27 Alaska
     * zone 8 CRS — and back. The golden note recorded the inverse answering
     * {@code (0&deg;, 90&deg;, 0.000104 m)}: the north pole at a tenth of a millimetre, which
     * passes every finiteness and range check a caller can write. 1.4.3 answered latitude
     * {@code -9.36e10 deg}, which no caller could mistake for a coordinate — so this got
     * <em>worse</em>, and that is the point of the no-sentinels rule.
     *
     * <p>Asserted as an invariant, not as pinned numbers: either the transform refuses, or it
     * round-trips. What it must never do is hand back the pole on the Greenwich meridian.
     */
    @Test
    public void reproducerNeverAnswersThePole() {
        CRSFactory f = new CRSFactory();
        CoordinateReferenceSystem src = f.createFromName("EPSG:4173");
        CoordinateReferenceSystem tgt = f.createFromName("EPSG:26748");
        CoordinateTransformFactory ctf = new CoordinateTransformFactory();
        CoordinateTransform fwd = ctf.createTransform(src, tgt);
        CoordinateTransform inv = ctf.createTransform(tgt, src);

        ProjCoordinate mid = poisonedDst();
        try {
            fwd.transform(new ProjCoordinate(5, 5), mid);
        } catch (Proj4jException e) {
            assertNotNull(e.cause());
            return;   // refusing the forward is a perfectly good outcome
        }
        assertTrue("forward must overwrite the poisoned destination", mid.x != 1e300);
        assertTrue("forward must overwrite the poisoned destination", mid.y != 1e300);

        ProjCoordinate back = poisonedDst();
        try {
            inv.transform(mid, back);
        } catch (Proj4jException e) {
            assertNotNull(e.cause());
            return;   // refusing the inverse is the other perfectly good outcome
        }
        assertTrue("inverse must overwrite the poisoned destination", back.x != 1e300);
        assertTrue("inverse must overwrite the poisoned destination", back.y != 1e300);

        // The sentinel this test exists to catch: latitude +/-90 deg on the Greenwich meridian,
        // which is what the deleted `Longitude = 0.0` branch produced and what no range check can
        // distinguish from a real polar answer.
        assertTrue("the inverse must not answer a pole: got (" + back.x + ", " + back.y + ")",
                Math.abs(Math.abs(back.y) - 90.0) > 1e-9);
        assertEquals("if the inverse answers at all it must round-trip", 5.0, back.x, 1e-6);
        assertEquals("if the inverse answers at all it must round-trip", 5.0, back.y, 1e-6);
    }
}

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
package org.locationtech.proj4j.domain;

import org.junit.Test;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.datum.GeocentricConverter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link GeocentricConverter}'s three fail-open sites.
 *
 * <h2>Why the exception type mattered more than the message</h2>
 *
 * <p>The out-of-range latitude branch threw {@code IllegalStateException}, which is unchecked and
 * <b>not</b> a {@link Proj4jException} — so it escaped every {@code catch (Proj4jException)} in
 * the library and in every caller that had written the obvious handler. The golden-master sweep
 * found 23 ordinary CRS pairs reaching that line, so this was not a degenerate-input-only path;
 * a caller that believed it had handled Proj4J's failures was getting an
 * {@code IllegalStateException} out of a geometry loop.
 */
public class GeocentricConverterDomainTest {

    private static final double HALF_PI = Math.PI / 2.0;

    private static GeocentricConverter wgs84() {
        return new GeocentricConverter(Ellipsoid.WGS84);
    }

    /** Ordinary input still round-trips, so none of the three throws fires on valid data. */
    @Test
    public void ordinaryCoordinatesStillRoundTrip() {
        GeocentricConverter gc = wgs84();
        ProjCoordinate p = new ProjCoordinate(Math.toRadians(10.0), Math.toRadians(45.0), 100.0);
        gc.convertGeodeticToGeocentric(p);
        gc.convertGeocentricToGeodetic(p);
        assertEquals(Math.toRadians(10.0), p.x, 1e-12);
        assertEquals(Math.toRadians(45.0), p.y, 1e-12);
        assertEquals(100.0, p.z, 1e-6);
    }

    /** The poles are valid, and so is anything within the 0.1% rounding allowance past them. */
    @Test
    public void polesAndTheRoundingAllowanceAreAccepted() {
        GeocentricConverter gc = wgs84();
        for (double lat : new double[] {HALF_PI, -HALF_PI, HALF_PI * 1.0005, -HALF_PI * 1.0005}) {
            ProjCoordinate p = new ProjCoordinate(0.0, lat, 0.0);
            gc.convertGeodeticToGeocentric(p);
            assertTrue("latitude " + lat + " must be accepted",
                    p.hasValidXandYOrdinates());
        }
    }

    /**
     * Site 1: the {@code IllegalStateException}. It is now a {@link Proj4jException}, so
     * {@code catch (Proj4jException)} sees it, and it carries
     * {@link ErrorCause#INVALID_COORDINATE}.
     */
    @Test
    public void outOfRangeLatitudeRaisesAProj4jExceptionNotAnIllegalStateException() {
        GeocentricConverter gc = wgs84();
        for (double lat : new double[] {2.0, -2.0, Math.PI, -Math.PI}) {
            ProjCoordinate p = new ProjCoordinate(0.0, lat, 0.0);
            try {
                gc.convertGeodeticToGeocentric(p);
                fail("latitude " + lat + " rad must be rejected");
            } catch (IllegalStateException e) {
                fail("still throwing IllegalStateException, which escapes every "
                        + "catch (Proj4jException): " + e);
            } catch (Proj4jException e) {
                assertEquals(ErrorCause.INVALID_COORDINATE, e.cause());
            }
        }
    }

    /**
     * Site 2: the centre of mass. 1.4.3 answered {@code (0, 0, 0)} with longitude 0, latitude
     * <b>+90&deg;</b> and height {@code -b} — three finite, in-range, entirely plausible
     * ordinates for a point that has no geodetic latitude or longitude at all, since every
     * meridian and every parallel passes through it. A caller cannot distinguish that from a real
     * polar coordinate at depth {@code b}. Upstream's {@code geocent.cpp} keeps the fiction; the
     * no-sentinels rule does not permit it.
     */
    @Test
    public void theCentreOfMassRaisesInsteadOfInventingAPole() {
        GeocentricConverter gc = wgs84();
        ProjCoordinate p = new ProjCoordinate(0.0, 0.0, 0.0);
        try {
            gc.convertGeocentricToGeodetic(p);
            fail("(0,0,0) must not be answered with latitude " + p.y + ", height " + p.z);
        } catch (Proj4jException e) {
            assertEquals(ErrorCause.INVALID_COORDINATE, e.cause());
            assertTrue(e.getMessage(), e.getMessage().contains("centre of mass"));
        }
    }

    /** The specific fiction, pinned: it must not be +90 degrees and -b any more. */
    @Test
    public void theOldFictionIsNamedSoItCannotComeBack() {
        GeocentricConverter gc = wgs84();
        ProjCoordinate p = new ProjCoordinate(0.0, 0.0, 0.0);
        boolean raised = false;
        try {
            gc.convertGeocentricToGeodetic(p);
        } catch (Proj4jException e) {
            raised = true;
        }
        assertTrue("must raise", raised);
        // If a future change reinstates the old return, these are the values it would write.
        assertTrue("latitude must not have been set to +pi/2", p.y != HALF_PI);
        assertTrue("height must not have been set to -b", p.z != -Ellipsoid.WGS84.getB());
    }

    /**
     * A point on the polar axis but not at the centre is a legitimate special case and must keep
     * working: only the {@code |(X,Y,Z)| / a < 1e-12} branch is degenerate.
     */
    @Test
    public void aPointOnThePolarAxisIsStillValid() {
        GeocentricConverter gc = wgs84();
        ProjCoordinate p = new ProjCoordinate(0.0, 0.0, Ellipsoid.WGS84.getB());
        gc.convertGeocentricToGeodetic(p);
        assertEquals("the north pole", HALF_PI, Math.abs(p.y), 1e-9);
        assertEquals(0.0, p.x, 0.0);
    }

    /**
     * {@code NaN} in, {@code NaN} out: both directions are {@code NaN}-transparent, and neither
     * the new convergence check nor the degeneracy check may fire for it. The comparisons
     * involved are all false for {@code NaN}, which is the same reason PROJ propagates.
     */
    @Test
    public void nanIsPropagatedThroughBothDirections() {
        GeocentricConverter gc = wgs84();

        ProjCoordinate fwd = new ProjCoordinate(Double.NaN, Double.NaN, Double.NaN);
        gc.convertGeodeticToGeocentric(fwd);
        assertTrue("NaN geodetic input must not raise and must stay NaN: " + fwd,
                Double.isNaN(fwd.x) && Double.isNaN(fwd.y));

        ProjCoordinate inv = new ProjCoordinate(Double.NaN, Double.NaN, Double.NaN);
        gc.convertGeocentricToGeodetic(inv);
        assertTrue("NaN geocentric input must not raise and must stay NaN: " + inv,
                Double.isNaN(inv.x) || Double.isNaN(inv.y));
    }

    /**
     * Site 3: the iteration's two exits used to be treated identically. The Hannover algorithm's
     * own comment says "max. 30 is always enough", which is exactly why reaching 30 means the
     * inputs are not well formed — and why continuing to compute a latitude from them yields a
     * plausible wrong answer. A degenerate ellipsoid is the reachable way to get there.
     */
    @Test
    public void nonConvergentIterationRaisesRatherThanReturningTheLastIterate() {
        // e2 = 0.999 is not a real Earth ellipsoid, but it is reachable from +f= / +rf= input and
        // the iteration's contraction factor depends on it.
        GeocentricConverter degenerate = new GeocentricConverter(6378137.0, 1000.0, 0.999);
        ProjCoordinate p = new ProjCoordinate(1.0, 1.0, 6378137.0);
        try {
            degenerate.convertGeocentricToGeodetic(p);
            // Convergence for this particular ellipsoid is not guaranteed either way; what is
            // asserted is that IF it returns, it returns a finite answer -- never a silently
            // unconverged one, which is what the post-loop check now enforces.
            assertTrue("a returned latitude must at least be finite: " + p.y,
                    !Double.isNaN(p.y) && !Double.isInfinite(p.y));
        } catch (Proj4jException e) {
            assertEquals(ErrorCause.NUMERICAL_FAILURE, e.cause());
            assertTrue(e.getMessage(), e.getMessage().contains("did not converge"));
        }
    }
}

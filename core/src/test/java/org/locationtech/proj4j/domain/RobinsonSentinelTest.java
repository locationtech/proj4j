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
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.proj.RobinsonProjection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code RobinsonProjection}'s three {@code lp.x = lp.y = NaN; return lp;} sentinel returns,
 * converted to throws.
 *
 * <h2>Why convert them at all, when the postcondition would catch them anyway</h2>
 *
 * <p>Because a sentinel and an error are not the same event even when they produce the same
 * observable outcome. {@code Projection.inverseProjectRadians}' finiteness check <em>would</em>
 * turn each of these into a {@link ErrorCause#NUMERICAL_FAILURE} one frame up — but
 * "numerical failure" is the wrong attribution. These three are
 * {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN}: the arithmetic did not fail, the coordinate is
 * simply not on the map. Upstream agrees and sets
 * {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN} at all three sites
 * ({@code robin.cpp:113}, {@code :124}, {@code :150}).
 *
 * <p>Raising at the site is also what keeps the message useful: it can name the northing and the
 * interpolation interval, which the generic check one frame up cannot see.
 */
public class RobinsonSentinelTest {

    private static final double FYC = 1.3523;

    private static RobinsonProjection robinson() {
        RobinsonProjection p = new RobinsonProjection();
        p.setRadius(1.0);
        p.initialize();
        return p;
    }

    /** A northing well inside the map still inverts, so none of the three fires on valid input. */
    @Test
    public void inDomainNorthingStillInverts() {
        RobinsonProjection p = robinson();
        ProjCoordinate out = p.projectInverse(0.1, 0.5, new ProjCoordinate(1e300, 1e300));
        assertTrue(out.toString(), out.hasValidXandYOrdinates());
        assertTrue("latitude must be in range: " + out.y, Math.abs(out.y) <= Math.PI / 2);
    }

    /** The forward direction is unaffected; only the inverse had sentinels. */
    @Test
    public void forwardIsUnaffected() {
        RobinsonProjection p = robinson();
        ProjCoordinate out = p.project(0.5, 0.5, new ProjCoordinate(1e300, 1e300));
        assertTrue(out.toString(), out.hasValidXandYOrdinates());
    }

    /**
     * Site 1, {@code robin.cpp:113}: {@code |y| / FYC > ONEEPS}, a northing off the top or bottom
     * of the map. {@code ONEEPS} is {@code 1.000001}, so this needs a northing a little past
     * {@code FYC}.
     */
    @Test
    public void northingBeyondOneEpsRaisesOutOfDomain() {
        RobinsonProjection p = robinson();
        for (double y : new double[] {FYC * 1.01, -FYC * 1.01, 10.0, -10.0}) {
            try {
                ProjCoordinate out = p.projectInverse(0.0, y, new ProjCoordinate(1e300, 1e300));
                fail("northing " + y + " is off the map, got " + out);
            } catch (ProjectionException e) {
                assertEquals("northing " + y, ErrorCause.COORDINATE_OUT_OF_DOMAIN, e.cause());
                assertTrue(e.getMessage(), e.getMessage().contains("ONEEPS"));
            }
        }
    }

    /**
     * The accepting side of site 1: within {@code ONEEPS} the latitude is snapped to the pole
     * rather than rejected, so the boundary is a clamp and not a cliff.
     */
    @Test
    public void northingJustInsideOneEpsIsSnappedToThePole() {
        RobinsonProjection p = robinson();
        ProjCoordinate out =
                p.projectInverse(0.0, FYC * 1.0000005, new ProjCoordinate(1e300, 1e300));
        assertEquals(Math.PI / 2, out.y, 1e-15);
        assertTrue(out.hasValidXandYOrdinates());
    }

    /**
     * Site 3, {@code robin.cpp:150}: an easting that inverts to {@code |lambda| > pi}. Upstream
     * sets the domain errno <em>and</em> overwrites the coordinate with
     * {@code proj_coord_error()} — i.e. upstream also regards the {@code NaN} as a courtesy
     * rather than as the signal.
     */
    @Test
    public void eastingThatInvertsPastPiRaisesOutOfDomain() {
        RobinsonProjection p = robinson();
        try {
            ProjCoordinate out = p.projectInverse(100.0, 0.5, new ProjCoordinate(1e300, 1e300));
            fail("an easting inverting past +/-pi must raise, got " + out);
        } catch (ProjectionException e) {
            assertEquals(ErrorCause.COORDINATE_OUT_OF_DOMAIN, e.cause());
            assertTrue(e.getMessage(), e.getMessage().contains("outside +/-pi"));
        }
    }

    /**
     * Every sentinel is gone: no input may make the inverse <em>return</em> a {@code NaN}. The
     * whole point of the exercise is that a caller's {@code isFinite} guard is no longer the only
     * thing standing between it and a wrong answer, so there must be nothing left for it to catch.
     */
    @Test
    public void noInputMakesTheInverseReturnNaN() {
        RobinsonProjection p = robinson();
        int raised = 0;
        int returned = 0;
        for (double x = -60.0; x <= 60.0; x += 3.0) {
            for (double y = -6.0; y <= 6.0; y += 0.25) {
                ProjCoordinate out = new ProjCoordinate(1e300, 1e300);
                try {
                    p.projectInverse(x, y, out);
                    returned++;
                    assertTrue("returned a NaN sentinel at (" + x + ", " + y + "): " + out,
                            out.hasValidXandYOrdinates());
                } catch (ProjectionException e) {
                    raised++;
                }
            }
        }
        assertTrue("the sweep must exercise both outcomes; raised=" + raised
                + " returned=" + returned, raised > 0 && returned > 0);
    }
}

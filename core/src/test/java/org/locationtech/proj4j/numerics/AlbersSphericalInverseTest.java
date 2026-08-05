/*******************************************************************************
 * Copyright 2026
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

package org.locationtech.proj4j.numerics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;

/**
 * The Albers spherical inverse — "defect J" — and the authalic re-point of the ellipsoidal
 * branch.
 *
 * <p>{@code AlbersProjection.projectInverse} used to compute the authalic quantity PROJ names
 * {@code qs_div_2} into {@code out.y} as a scratch temp and then take {@code asin} of
 * {@code lpphi}, which was still {@code rho/dd} — <b>the radius</b>. The sign test in the pole
 * fallback was on the same variable, which is non-negative by construction, so that branch
 * could only ever return {@code +90}.
 *
 * <p>The defect was dormain-locked to the spherical branch, which only became reachable once
 * {@code +R=} started producing {@code spherical == true}. That is the hard coupling: shipping
 * the {@code +R} fix alone converts a 6e-06 degree source-side error into 89.96 degrees.
 *
 * <p>Reference rows: {@code builtins.gie:63-86} ({@code +proj=aea +R=6400000 +lat_1=0 +lat_2=2})
 * and {@code builtins.gie:36-60} ({@code +ellps=GRS80}), both at {@code tolerance 0.1 mm}.
 */
public class AlbersSphericalInverseTest {

    private static final String SPHERICAL = "+proj=aea +R=6400000 +lat_1=0 +lat_2=2";
    private static final String ELLIPSOIDAL = "+proj=aea +ellps=GRS80 +lat_1=0 +lat_2=2";
    private static final double RADIUS = 6400000.0;

    /**
     * {@code builtins.gie:81-88}. Before the fix the latitude came back as
     * <b>89.95769008830746</b> degrees instead of 0.000895246 — an 89.9568 degree error, i.e.
     * about 9,995 km on the ground.
     */
    @Test
    public void sphericalInverseMatchesGie() {
        GieRow row = GieRow.sphere(SPHERICAL, RADIUS);
        row.expectInverse(200, 100, 0.001790494, 0.000895246, 0.1 * GieRow.MM);
        row.expectInverse(200, -100, 0.001790493, -0.000895247, 0.1 * GieRow.MM);
        row.expectInverse(-200, 100, -0.001790494, 0.000895246, 0.1 * GieRow.MM);
        row.expectInverse(-200, -100, -0.001790493, -0.000895247, 0.1 * GieRow.MM);
    }

    /** {@code builtins.gie:69-76} — the forward direction was already correct; pin it. */
    @Test
    public void sphericalForwardMatchesGie() {
        GieRow row = GieRow.sphere(SPHERICAL, RADIUS);
        row.expectForward(2, 1, 223334.085170885, 111780.431884472, 0.1 * GieRow.MM);
        row.expectForward(2, -1, 223470.154991687, -111610.339430990, 0.1 * GieRow.MM);
        row.expectForward(-2, 1, -223334.085170885, 111780.431884472, 0.1 * GieRow.MM);
        row.expectForward(-2, -1, -223470.154991687, -111610.339430990, 0.1 * GieRow.MM);
    }

    /**
     * Round-tripping the forward of (2, 1) is the sharpest form of the defect: the inverse used
     * to return latitude <b>88.58582234725245</b> instead of 1, an 87.586 degree error.
     */
    @Test
    public void sphericalRoundTripCloses() {
        GieRow row = GieRow.sphere(SPHERICAL, RADIUS);
        ProjCoordinate lp = row.inverse(223334.085170885, 111780.431884472);
        assertEquals("longitude", 2.0, lp.x, 1e-11);
        assertEquals("latitude was 88.58582234725245 before the fix", 1.0, lp.y, 1e-11);
        row.expectRoundtrip(2, 1, 1, 0.1 * GieRow.MM);
        row.expectRoundtrip(-2, -1, 1, 0.1 * GieRow.MM);
    }

    /**
     * The other half of the defect: where {@code rho/dd > 1} the old code fed {@code asin} a
     * value greater than one and returned <b>NaN</b>. The corrected code clamps to the pole,
     * exactly as {@code 9.8.1:aea.cpp:116-118} does, and the sign now comes from
     * {@code qs_div_2} rather than from the radius — so the southern pole is reachable.
     */
    @Test
    public void sphericalPoleFallbackIsFiniteAndSigned() {
        GieRow row = GieRow.sphere(SPHERICAL, RADIUS);
        // Far enough out that (c - (rho/dd)^2)/n2 leaves [-1, 1].
        for (double northing : new double[] {-2.0e7, -1.5e7, 1.5e7, 2.0e7}) {
            ProjCoordinate lp = row.inverse(0.0, northing);
            assertTrue("latitude must be finite at northing " + northing + ", got " + lp.y,
                    !Double.isNaN(lp.y) && !Double.isInfinite(lp.y));
            assertEquals("must clamp to a pole at northing " + northing,
                    90.0, Math.abs(lp.y), 1e-9);
        }
        // The pole reached must depend on the input, not always be +90.
        double north = row.inverse(0.0, 1.5e7).y;
        double south = row.inverse(0.0, -2.0e7).y;
        assertTrue("the pole fallback must be able to return both poles; got "
                + north + " and " + south, north != south);
    }

    /** {@code builtins.gie:53-60} — the ellipsoidal inverse, now via {@code AuthalicLat}. */
    @Test
    public void ellipsoidalInverseMatchesGie() {
        GieRow row = GieRow.grs80(ELLIPSOIDAL);
        row.expectInverse(200, 100, 0.001796631, 0.000904369, 0.1 * GieRow.MM);
        row.expectInverse(200, -100, 0.001796630, -0.000904370, 0.1 * GieRow.MM);
        row.expectInverse(16468399.3582, 5275043.9815, 150, 50, 0.1 * GieRow.MM);
    }

    /** {@code builtins.gie:38-48} — the ellipsoidal forward, now via {@code AuthalicLat.q}. */
    @Test
    public void ellipsoidalForwardMatchesGie() {
        GieRow row = GieRow.grs80(ELLIPSOIDAL);
        row.expectForward(2, 1, 222571.608757106, 110653.326743030, 0.1 * GieRow.MM);
        row.expectForward(2, -1, 222706.306508391, -110484.267144400, 0.1 * GieRow.MM);
        row.expectForward(150, 50, 16468399.3582, 5275043.9815, 0.1 * GieRow.MM);
    }

    /**
     * {@code 9.8.1:aea.cpp:100-105} rejects the coordinate when {@code |qs| > 2} instead of
     * handing {@code asin} an out-of-range argument. proj4j had no such guard; the old
     * {@code phi1_} Newton loop was seeded with {@code asin(0.5 * qs)}, silently clamped.
     */
    @Test
    public void ellipsoidalDomainGuardRejectsRatherThanReturningGarbage() {
        GieRow row = GieRow.grs80(ELLIPSOIDAL);
        // n = sin(lat_1 = 0.5 deg) is tiny, so qs = (c - (rho/dd)^2)/n leaves [-2, 2] as soon
        // as the northing is far from the cone's apex.
        boolean threwAtLeastOnce = false;
        for (double northing : new double[] {-1.0e8, 1.0e8}) {
            try {
                ProjCoordinate lp = row.inverse(0.0, northing);
                assertTrue("must not return NaN at northing " + northing + ", got " + lp,
                        !Double.isNaN(lp.y));
            } catch (ProjectionException expected) {
                threwAtLeastOnce = true;
                assertTrue("the message should name the domain, was: " + expected.getMessage(),
                        expected.getMessage() != null
                                && expected.getMessage().contains("domain"));
            }
        }
        if (!threwAtLeastOnce) {
            fail("|qs| > 2 must be rejected somewhere in that range");
        }
    }
}

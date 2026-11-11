/*******************************************************************************
 * Copyright 2026 the original author or authors.
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

import org.junit.Test;
import org.locationtech.proj4j.proj.Projection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Aitoff, Winkel Tripel and Hammer had no inverse, and the inverse of a projection without one
 * returned its input unchanged, so projected metres came back as if they were degrees.
 */
public class InverseProjectionTest {

    private static final CRSFactory FACTORY = new CRSFactory();

    private static Projection projection(String params) {
        return FACTORY.createFromParameters(null, params).getProjection();
    }

    /** Forward against PROJ (cct), then the inverse of PROJ's x, y back to lon, lat. */
    private static void check(Projection p, double lon, double lat, double x, double y) {
        ProjCoordinate xy = p.project(new ProjCoordinate(lon, lat), new ProjCoordinate());
        assertEquals("x", x, xy.x, 1e-6);
        assertEquals("y", y, xy.y, 1e-6);
        ProjCoordinate lp = p.inverseProject(new ProjCoordinate(x, y), new ProjCoordinate());
        // 1e-9 degrees is about 0.1 mm on the Earth
        assertEquals("lon", lon, lp.x, 1e-9);
        assertEquals("lat", lat, lp.y, 1e-9);
    }

    // Reference coordinates computed with PROJ (cct -d 10).
    @Test
    public void testAitoff() {
        Projection p = projection("+proj=aitoff +R=6371000");
        assertTrue(p.hasInverse());
        check(p, 10, 20, 1066370.7418647551, 2226630.1479116683);
        check(p, -170, -60, -9702012.5141225830, -8434284.3032184821);
        check(p, 179, 89, 349264.2662302243, 10005049.1672776323);
        check(p, 0.001, -0.002, 111.1949265994, -222.3898532919);
        check(p, 120, 45, 10088571.0977981817, 5824639.2390524577);
    }

    @Test
    public void testWinkelTripel() {
        Projection p = projection("+proj=wintri +R=6371000");
        assertTrue(p.hasInverse());
        check(p, 10, 20, 887129.8153768220, 2225264.3404014213);
        check(p, -170, -60, -10868061.8126168493, -7552989.9509460032);
        check(p, 179, 89, 6510237.6886706697, 9950698.8193216808);
        check(p, 0.001, -0.002, 90.9919077441, -222.3898532905);
        check(p, 120, 45, 9291618.8822324239, 5414205.4690288007);
    }

    @Test
    public void testHammer() {
        Projection p = projection("+proj=hammer +R=6371000");
        assertTrue(p.hasInverse());
        check(p, 10, 20, 1060641.5733257541, 2214667.3859091224);
        check(p, -170, -60, -8786267.0317420792, -7638196.0961024538);
        check(p, 179, 89, 314454.8600056411, 9007896.4252587073);
        check(p, 0.001, -0.002, 111.1949265934, -222.3898532799);
        check(p, 120, 45, 9484851.5255769044, 5476081.5815154584);
    }

    @Test
    public void testHammerWithWAndM() {
        Projection p = projection("+proj=hammer +R=6371000 +W=0.25 +M=2");
        check(p, 10, 20, 2121835.2466776771, 1106567.6847706148);
        check(p, -170, -60, -20812369.9582104795, -3334869.4396382016);
        check(p, 179, 89, 880186.6441020884, 4476633.9144648956);
        check(p, 0.001, -0.002, 222.3898531873, -111.1949266392);
        check(p, 120, 45, 20069424.2599629648, 2508678.0324953706);
    }

    @Test
    public void testRoundTripGrid() {
        for (String params : new String[] {"+proj=aitoff +R=6371000", "+proj=wintri +R=6371000",
                "+proj=hammer +R=6371000", "+proj=hammer +R=6371000 +W=0.25 +M=2"}) {
            Projection p = projection(params);
            for (double lon = -180; lon <= 180; lon += 7.5) {
                for (double lat = -90; lat <= 90; lat += 7.5) {
                    ProjCoordinate xy = p.project(new ProjCoordinate(lon, lat), new ProjCoordinate());
                    ProjCoordinate lp = p.inverseProject(xy, new ProjCoordinate());
                    String at = params + " at " + lon + ", " + lat;
                    assertEquals(at, lat, lp.y, 1e-9);
                    if (Math.abs(lat) < 90) // the longitude of a pole is arbitrary
                        assertEquals(at, lon, lp.x, 1e-9);
                }
            }
        }
    }

    @Test
    public void testOutsideTheMap() {
        // The inverses reject points outside x^2 + 4 * y^2 = (2 * pi * R)^2 (Aitoff) and
        // x^2 / 16 + y^2 / 4 = R^2 (Hammer), about twice the world outline in x (pi * R and
        // 2 * sqrt(2) * R); points between the two come back with the longitude clamped to
        // +/-180, as for the other projections. (4e7, 4e7) m is outside both.
        for (String params : new String[] {"+proj=aitoff +R=6371000", "+proj=hammer +R=6371000"}) {
            Projection p = projection(params);
            try {
                p.inverseProject(new ProjCoordinate(4e7, 4e7), new ProjCoordinate());
                fail(params + ": a point far outside the map must not be inverted");
            } catch (ProjectionException expected) {
                // expected
            }
        }
    }

    @Test
    public void testNoInverseThrows() {
        // Airy has no inverse in PROJ either
        Projection p = projection("+proj=airy +R=6371000");
        assertFalse(p.hasInverse());
        try {
            ProjCoordinate lp = p.inverseProject(new ProjCoordinate(1000000, 2000000), new ProjCoordinate());
            fail("expected an exception, got " + lp);
        } catch (ProjectionException expected) {
            // expected
        }
    }
}

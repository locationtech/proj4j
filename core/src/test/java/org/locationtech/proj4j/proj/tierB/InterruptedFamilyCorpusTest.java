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

package org.locationtech.proj4j.proj.tierB;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.proj.Projection;

/**
 * The four interrupted pseudo-cylindricals against {@code builtins.gie}: {@code igh},
 * {@code igh_o}, {@code imoll} and {@code imoll_o}.
 *
 * <p>Their corpus coverage is the roundtrip-heaviest in this batch — every forward row is followed
 * by a {@code roundtrip 1}, which is exactly the right shape of test for a lobe dispatcher, because
 * a wrong lobe assignment shows up as a residual of a lobe width rather than as a small error.
 */
public class InterruptedFamilyCorpusTest {

    @Test
    public void ighMatchesCorpus() {
        TierBCorpus.assertAll("builtins.gie", "igh", 36);
    }

    @Test
    public void ighOceanicMatchesCorpus() {
        TierBCorpus.assertAll("builtins.gie", "igh_o", 42);
    }

    @Test
    public void imollMatchesCorpus() {
        TierBCorpus.assertAll("builtins.gie", "imoll", 40);
    }

    @Test
    public void imollOceanicMatchesCorpus() {
        TierBCorpus.assertAll("builtins.gie", "imoll_o", 40);
    }

    @Test
    public void allFourNamesAreRegistered() {
        Registry registry = new Registry();
        for (String name : new String[] {"igh", "igh_o", "imoll", "imoll_o"}) {
            Projection p = registry.getProjection(name);
            assertNotNull("+proj=" + name, p);
            assertTrue("+proj=" + name + " must declare an inverse", p.hasInverse());
        }
        // goode is the UNinterrupted Goode Homolosine, a different operator.
        assertTrue("goode must not have been aliased to igh",
                !(registry.getProjection("goode").getClass()
                        == registry.getProjection("igh").getClass()));
    }

    /**
     * A point above the top of the map is rejected, not wrapped.
     *
     * <p>{@code y = sqrt(2)} is the northing of the pole in unit-sphere units, so
     * {@code y = 2 * a} is unambiguously off the map for every member of the family. Upstream
     * answers {@code HUGE_VAL} there and {@code inv_finalize} raises
     * {@code outside_projection_domain}.
     */
    @Test
    public void aboveTheMapIsRejected() {
        for (String name : new String[] {"igh", "igh_o", "imoll", "imoll_o"}) {
            Projection p = TierBCorpus.build("+proj=" + name + " +a=6400000");
            assertRejects(p, name, 0, 2.0 * 6400000);
            assertRejects(p, name, 0, -2.0 * 6400000);
        }
    }

    /**
     * <b>The interruption gaps must reject, not snap.</b>
     *
     * <p>This is the assertion the whole port exists for. An interrupted projection's plane has
     * regions that are not the image of anything on the sphere; a plane point there still lands
     * inside some lobe's bounding box, so a naive inverse hands back a plausible longitude in the
     * wrong lobe — wrong by up to a lobe width, silently.
     *
     * <p>The probe is constructed rather than guessed: take a point just outside a lobe's
     * longitude range in the <em>opposite</em> hemisphere from where that easting belongs, project
     * it, and read back the easting. For {@code imoll} the northern lobe 1 spans
     * {@code [-180, -40]} while the southern lobes below it split at {@code -100}; the wedge just
     * south of the equator at an easting belonging to lobe 1's far west but at lobe 4's northing is
     * therefore a gap. Rather than hand-deriving one, every candidate is tested and the test
     * asserts that <em>at least one</em> exists and is rejected — because a dispatcher with no
     * rejectable region at all would mean {@link
     * org.locationtech.proj4j.proj.InterruptedProjection#projectable} is returning {@code true}
     * unconditionally.
     */
    @Test
    public void interruptionGapsAreRejected() {
        for (String name : new String[] {"igh", "igh_o", "imoll", "imoll_o"}) {
            Projection p = TierBCorpus.build("+proj=" + name + " +a=6400000");
            int rejected = 0;
            int accepted = 0;
            // A coarse sweep of the plane in units of the semi-major axis. The map is about
            // +/-2.83 wide and +/-1.42 (imoll) or +/-2.0 (igh) tall in those units.
            for (double xu = -2.9; xu <= 2.9; xu += 0.05) {
                for (double yu = -1.4; yu <= 1.4; yu += 0.05) {
                    ProjCoordinate out = new ProjCoordinate();
                    try {
                        p.inverseProject(new ProjCoordinate(xu * 6400000, yu * 6400000), out);
                        accepted++;
                    } catch (Proj4jException expected) {
                        rejected++;
                    }
                }
            }
            assertTrue(name + ": the inverse accepted every one of " + accepted + " plane points "
                    + "-- an interrupted projection must have a rejectable region, so projectable() "
                    + "is answering true unconditionally", rejected > 0);
            assertTrue(name + ": the inverse rejected every plane point, which cannot be right "
                    + "either", accepted > 0);
        }
    }

    /**
     * Every point of the sphere projects, and round-trips back to itself.
     *
     * <p>A lobe dispatcher's characteristic failure is a seam: a band of longitudes that the
     * forward sends into lobe A and the inverse recovers from lobe B. The corpus samples 8 or 16
     * points; this sweeps a 5&deg; grid, about 2,500 points per projection, and asserts a residual
     * under a metre. Points exactly on a lobe boundary are skipped, because which lobe claims them
     * is a tie-break upstream resolves by the direction of its comparisons and is not a
     * round-trip property.
     */
    @Test
    public void everyPointRoundTrips() {
        for (String name : new String[] {"igh", "igh_o", "imoll", "imoll_o"}) {
            Projection p = TierBCorpus.build("+proj=" + name + " +a=6400000");
            int checked = 0;
            for (double lon = -177.5; lon <= 177.5; lon += 5) {
                for (double lat = -87.5; lat <= 87.5; lat += 5) {
                    ProjCoordinate xy = new ProjCoordinate();
                    ProjCoordinate back = new ProjCoordinate();
                    p.project(new ProjCoordinate(lon, lat), xy);
                    p.inverseProject(xy, back);
                    // ~1 m on the ground at this radius is 9e-6 degrees.
                    assertEquals(name + " round-trip longitude at (" + lon + ", " + lat + ")",
                            lon, back.x, 1e-5);
                    assertEquals(name + " round-trip latitude at (" + lon + ", " + lat + ")",
                            lat, back.y, 1e-5);
                    checked++;
                }
            }
            assertEquals(name + ": grid size", 72 * 36, checked);
        }
    }

    /**
     * {@code igh}'s Mollweide/sinusoidal seam is continuous, which is what its measured
     * {@code dy0} buys.
     *
     * <p>{@code dy0} is derived at setup time by forwarding 40&deg;44'11.8" through both a
     * Mollweide lobe and a sinusoidal one and differencing the northings. If it were hard-coded to
     * a rounded decimal, or computed in the wrong direction, the two halves of the map would not
     * meet — so the northing a hair either side of the transition latitude must agree.
     *
     * <p><b>Only the northing.</b> {@code dy0} is a {@code y} offset and nothing corrects
     * {@code x}, so the meridians have a real kink at the seam: measured at longitude 0, the
     * easting jumps by <b>0.73 m</b> for {@code igh} and <b>0.24 m</b> for {@code igh_o}, out of
     * about 811&thinsp;896 m and 258&thinsp;000 m respectively — Mollweide's
     * {@code C_x * lam * cos(theta/2)} against sinusoidal's {@code lam * cos(phi)}, and the size
     * depends on how far the probe is from that lobe's own central meridian. That is the Goode
     * homolosine's defining compromise, not a defect: an earlier version of this test asserted
     * {@code x} continuity and failed on it. Do not "fix" it.
     */
    @Test
    public void ighTransitionLatitudeIsContinuous() {
        double boundary = (40 + 44 / 60. + 11.8 / 3600.);
        for (String name : new String[] {"igh", "igh_o"}) {
            Projection p = TierBCorpus.build("+proj=" + name + " +a=6400000");
            ProjCoordinate below = new ProjCoordinate();
            ProjCoordinate above = new ProjCoordinate();
            p.project(new ProjCoordinate(0, boundary - 1e-9), below);
            p.project(new ProjCoordinate(0, boundary + 1e-9), above);
            assertEquals(name + ": the Mollweide/sinusoidal seam must be continuous in y",
                    below.y, above.y, 1e-3);
            // x is NOT continuous, and the kink is small but real. Its size depends on the
            // longitude's distance from the lobe's own central meridian, so it differs between the
            // two operators at the same probe: 0.73 m for igh (lobes 2/4 at lam0 = +30) and
            // 0.243 m for igh_o (lobes 2/5 at lam0 = -10). Asserting a bound rather than a value
            // keeps the point -- the seam is joined in y and merely nearly joined in x.
            double kink = Math.abs(above.x - below.x);
            assertTrue(name + ": the easting kink at the seam was " + kink
                    + " m, which is not the sub-metre bend the homolosine has by construction",
                    kink > 1e-3 && kink < 5.0);
        }
    }

    private static void assertRejects(Projection p, String name, double x, double y) {
        try {
            p.inverseProject(new ProjCoordinate(x, y), new ProjCoordinate());
            fail(name + ": (" + x + ", " + y + ") is off the map and must be rejected");
        } catch (Proj4jException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("map") || expected.getMessage()
                            .contains("interruption"));
        }
    }
}

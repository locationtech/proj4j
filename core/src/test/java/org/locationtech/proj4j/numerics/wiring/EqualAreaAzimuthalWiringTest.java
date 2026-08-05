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

package org.locationtech.proj4j.numerics.wiring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.locationtech.proj4j.numerics.wiring.GieCase.GRS80_A;
import static org.locationtech.proj4j.numerics.wiring.GieCase.GRS80_ES;
import static org.locationtech.proj4j.numerics.wiring.GieCase.MM;

import org.junit.Test;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.proj.EqualAreaAzimuthalProjection;
import org.locationtech.proj4j.util.AuthalicLat;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * {@code EqualAreaAzimuthalProjection} re-pointed at {@link AuthalicLat}.
 *
 * <p>This class is <b>not registered under any proj name</b> — {@code +proj=laea} resolves to
 * {@code LambertAzimuthalEqualAreaProjection} — so it cannot be driven from a proj-string and no
 * corpus row reaches it. It is public API implementing the same algorithm, so it is asserted against
 * its registered twin, which the corpus does reach, and against the same helper-level measurement.
 *
 * <p>The reference bar is {@code builtins.gie:3512-3534}'s {@code tolerance 0.1 mm} for
 * {@code +proj=laea +ellps=GRS80}, and the movement is the one the numerics reference calls the
 * largest in the numerical core: {@code ProjectionMath.authset}/{@code authlat} is third order and
 * reaches <b>2.211 mm at latitude 18.01 degrees</b> on GRS80, 22 times that bar.
 */
public class EqualAreaAzimuthalWiringTest {

    /**
     * Both {@code lat_0} and {@code lon_0} are set explicitly. That used to be load-bearing:
     * {@code AzimuthalProjection}'s no-argument constructor defaulted both to <b>45 degrees</b>, so
     * without the explicit {@code lon_0 = 0} the comparison against {@code +proj=laea +lon_0=0} was
     * meaningless. The base now defaults both to 0, as PROJ does, and the explicit calls are kept
     * so this test does not depend on that.
     */
    private static EqualAreaAzimuthalProjection at(double lat0Deg) {
        EqualAreaAzimuthalProjection p = new EqualAreaAzimuthalProjection();
        p.setEllipsoid(Ellipsoid.GRS80);
        p.setProjectionLatitudeDegrees(lat0Deg);
        p.setProjectionLongitudeDegrees(0.0);
        p.initialize();
        return p;
    }

    private static ProjCoordinate forward(EqualAreaAzimuthalProjection p, double lon, double lat) {
        return p.project(new ProjCoordinate(lon, lat), new ProjCoordinate());
    }

    private static ProjCoordinate inverse(EqualAreaAzimuthalProjection p, double x, double y) {
        return p.inverseProject(new ProjCoordinate(x, y), new ProjCoordinate());
    }

    /**
     * The unregistered class must agree with the registered {@code laea} in both directions. Before
     * the re-point the two differed by up to 1.6 mm, because
     * {@code LambertAzimuthalEqualAreaProjection} had already been moved to the order-6 series and
     * this one had not — so this assertion is also the hand-off being closed.
     */
    @Test
    public void agreesWithTheRegisteredLaeaToNanometres() {
        for (double lat0 : new double[] {0.0, 45.0, 90.0, -90.0}) {
            EqualAreaAzimuthalProjection p = at(lat0);
            GieCase twin = GieCase.grs80("+proj=laea +ellps=GRS80 +lat_0=" + lat0);
            for (double[] lonlat : new double[][] {{0, 0}, {2, 1}, {-3, 40}, {10, 60}, {0, 18.01}}) {
                ProjCoordinate mine = forward(p, lonlat[0], lonlat[1]);
                ProjCoordinate theirs = twin.forward(lonlat[0], lonlat[1]);
                assertEquals("lat_0=" + lat0 + " forward easting at (" + lonlat[0] + ", "
                        + lonlat[1] + ")", theirs.x, mine.x, 1.0e-6);
                assertEquals("lat_0=" + lat0 + " forward northing at (" + lonlat[0] + ", "
                        + lonlat[1] + ")", theirs.y, mine.y, 1.0e-6);

                ProjCoordinate back = inverse(p, mine.x, mine.y);
                ProjCoordinate theirsBack = twin.inverse(theirs.x, theirs.y);
                double dev = twin.angularDeviation(theirsBack.x, theirsBack.y, back);
                assertTrue("lat_0=" + lat0 + " inverse must agree with laea to nanometres at ("
                        + lonlat[0] + ", " + lonlat[1] + "), differs " + dev + " m", dev < 1.0e-6);
            }
        }
    }

    /**
     * The measurement that motivates the change: the third-order series against the order-6 one,
     * both handed the authalic latitude from the direct {@code phi -> xi} series.
     *
     * <p>That is not self-consistency — {@code C[xi,phi]} and {@code C[phi,xi]} are two independent
     * blocks of the Maxima table in {@code 9.8.1:src/latitudes.cpp}. Deriving {@code beta} from
     * {@code asin(q/qp)} instead would measure the {@code asin} form's own ill-conditioning near the
     * poles rather than either series.
     */
    @Test
    public void thirdOrderAuthalicSeriesMissesTheBarAndTheOrderSixOneDoesNot() {
        AuthalicLat authalic = new AuthalicLat(GRS80_ES);
        double[] apa = ProjectionMath.authset(GRS80_ES);

        double worstBefore = 0.0;
        double worstNow = 0.0;
        double worstBeforeAt = Double.NaN;
        for (int i = 0; i <= 9000; i++) {
            double phi = Math.toRadians(i / 100.0);
            double beta = authalic.forward(phi, Math.sin(phi), Math.cos(phi));
            double before = Math.abs(ProjectionMath.authlat(beta, apa) - phi) * GRS80_A;
            double now = Math.abs(authalic.inverse(beta) - phi) * GRS80_A;
            if (before > worstBefore) {
                worstBefore = before;
                worstBeforeAt = i / 100.0;
            }
            worstNow = Math.max(worstNow, now);
        }
        assertEquals("the third-order error peaks at 18.01 degrees", 18.01, worstBeforeAt, 0.02);
        assertTrue("it should be 22 times the 0.1 mm bar, measured " + worstBefore + " m",
                worstBefore > 2.0e-3 && worstBefore < 2.5e-3);
        assertTrue("the order-6 series must be sub-nanometre, measured " + worstNow + " m",
                worstNow < 1.0e-8);
    }

    /**
     * A round trip through this class at the 0.1 mm bar the {@code laea} rows set, sampled at the
     * third-order error peak so that the old code would have failed it.
     */
    @Test
    public void roundTripClosesAtTheErrorPeak() {
        for (double lat0 : new double[] {0.0, 45.0, 90.0, -90.0}) {
            EqualAreaAzimuthalProjection p = at(lat0);
            for (double lat : new double[] {18.0, 18.01, 20.8, 45.0, -70.0}) {
                if (lat0 == 90.0 && lat < 0) {
                    continue;
                }
                if (lat0 == -90.0 && lat > 0) {
                    continue;
                }
                ProjCoordinate xy = forward(p, 1.0, lat);
                ProjCoordinate lp = inverse(p, xy.x, xy.y);
                double dev = Math.abs(lp.y - lat) * Math.PI / 180.0 * GRS80_A;
                assertTrue("lat_0=" + lat0 + " round trip at latitude " + lat + " deviates "
                        + dev + " m", dev < 0.1 * MM);
            }
        }
    }

    /**
     * {@code laea.cpp:75} guards the polar forward with {@code q >= 1e-15}, not {@code q >= 0}.
     * That matters only once {@code q} comes from {@code sin(xi) * qp}: at the pole {@code qp - q} is
     * then rounding noise rather than an exact zero, and the square root of a denormal would put the
     * point sub-millimetres off the origin instead of on it.
     */
    @Test
    public void polarForwardLandsExactlyOnTheOriginAtThePole() {
        ProjCoordinate north = forward(at(90.0), 0.0, 90.0);
        assertEquals("the north pole must map to the origin exactly", 0.0, north.x, 0.0);
        assertEquals("the north pole must map to the origin exactly", 0.0, north.y, 0.0);

        ProjCoordinate south = forward(at(-90.0), 0.0, -90.0);
        assertEquals(0.0, south.x, 0.0);
        assertEquals(0.0, south.y, 0.0);
    }

    /**
     * {@link AuthalicLat} is immutable, so {@code clone()} no longer needs to deep-copy anything;
     * the clone must still project bit-identically.
     */
    @Test
    public void cloneSharesTheImmutableMachineryAndProjectsIdentically() {
        EqualAreaAzimuthalProjection p = at(45.0);
        EqualAreaAzimuthalProjection q = (EqualAreaAzimuthalProjection) p.clone();
        ProjCoordinate a = forward(p, 2.0, 46.0);
        ProjCoordinate b = forward(q, 2.0, 46.0);
        assertEquals(Double.doubleToRawLongBits(a.x), Double.doubleToRawLongBits(b.x));
        assertEquals(Double.doubleToRawLongBits(a.y), Double.doubleToRawLongBits(b.y));
    }
}

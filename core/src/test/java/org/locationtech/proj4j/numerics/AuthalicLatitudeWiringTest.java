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

import org.junit.Test;
import org.locationtech.proj4j.util.AuthalicLat;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * {@link AuthalicLat} wired into the three equal-area projections in scope: {@code cea},
 * {@code laea} and {@code aea} (the last also covered by {@link AlbersSphericalInverseTest}).
 *
 * <p>This is the largest accuracy movement in the numerical core.
 * {@code ProjectionMath.authset}/{@code authlat} is the same idea at third order; on GRS80 its
 * error is a smooth single-peaked curve reaching <b>2.211 mm at latitude 18.01 degrees</b>
 * against the 0.1 mm bar that this family's conformance rows set. The order-6 series is
 * sub-nanometre.
 *
 * <p>Two semantic changes come with it:
 * <ul>
 * <li>{@code laea}'s forward now takes the authalic latitude {@code xi} from the <b>direct
 *     {@code phi -> xi} series</b> and recovers {@code q = sin(xi) * qp}
 *     ({@code 9.8.1:laea.cpp:39-46}), rather than computing {@code q} and then
 *     {@code sinb = q/qp}, {@code cosb = sqrt(1 - sinb^2)}. The {@code asin} form loses relative
 *     accuracy at the poles, which is why upstream stopped using it. The same applies to
 *     {@code sinb1}/{@code cosb1} at initialisation ({@code laea.cpp:283-285}).
 * <li>{@code cea} and {@code aea} keep the <b>raw {@code q}</b> in the forward direction —
 *     {@code cea.cpp:21} and {@code aea.cpp:70} genuinely want it — so only their inverses move.
 * </ul>
 */
public class AuthalicLatitudeWiringTest {

    private static final double GRS80_ES = 0.006694380022900787;

    /**
     * The measurement that motivates the whole change, reproduced end to end: a
     * {@code cea} round trip at the error peak. Before the re-point this closed to
     * <b>17.999999980138625</b> degrees, i.e. 2.211 mm short of 18; it now closes exactly.
     */
    @Test
    public void cylindricalEqualAreaRoundTripClosesAtTheErrorPeak() {
        GieRow row = GieRow.grs80("+proj=cea +ellps=GRS80");
        row.expectRoundtrip(0, 18.0, 1, 0.1 * GieRow.MM);
        row.expectRoundtrip(0, 18.01, 1, 0.1 * GieRow.MM);
        row.expectRoundtrip(0, 20.8, 1, 0.1 * GieRow.MM);
        row.expectRoundtrip(30, 45, 1, 0.1 * GieRow.MM);
        row.expectRoundtrip(-30, -70, 1, 0.1 * GieRow.MM);
    }

    /**
     * Where the peak is. {@code AuthalicLatTest} already pins the magnitudes; what this adds is
     * the <em>location</em>, 18.01 degrees, because that is the latitude the {@code cea} round
     * trip above is aimed at and the reason a corpus sweep that samples only round numbers can
     * miss the defect.
     *
     * <p>Both stacks are handed the authalic latitude from the direct {@code phi -> xi} series.
     * That is not self-consistency: {@code C[xi,phi]} and {@code C[phi,xi]} are two independent
     * blocks of the Maxima table. Deriving {@code beta} from {@code asin(q/qp)} instead would
     * measure the {@code asin} form's own ill-conditioning near the poles — about 9.8 um at 90
     * degrees — rather than either series.
     */
    @Test
    public void thirdOrderErrorPeaksAtEighteenDegrees() {
        AuthalicLat authalic = new AuthalicLat(GRS80_ES);
        double[] apa = ProjectionMath.authset(GRS80_ES);
        final double a = 6378137.0;

        double worstOld = 0.0;
        double worstNew = 0.0;
        double worstOldAt = Double.NaN;
        for (int i = 0; i <= 9000; i++) {
            double phi = Math.toRadians(i / 100.0);
            double beta = authalic.forward(phi, Math.sin(phi), Math.cos(phi));

            double old = Math.abs(ProjectionMath.authlat(beta, apa) - phi) * a;
            double now = Math.abs(authalic.inverse(beta) - phi) * a;
            if (old > worstOld) {
                worstOld = old;
                worstOldAt = i / 100.0;
            }
            worstNew = Math.max(worstNew, now);
        }
        assertEquals("the third-order error peaks at 18.01 degrees", 18.01, worstOldAt, 0.02);
        assertTrue("the third-order series should miss the 0.1 mm bar by about 22x, "
                + "measured " + worstOld + " m", worstOld > 2.0e-3 && worstOld < 2.5e-3);
        assertTrue("the order-6 series must be comfortably sub-nanometre, measured "
                + worstNew + " m", worstNew < 1.0e-8);
    }

    /** {@code builtins.gie:1014-1035}, {@code tolerance 0.1 mm}. */
    @Test
    public void cylindricalEqualAreaMatchesGie() {
        GieRow row = GieRow.grs80("+proj=cea +ellps=GRS80");
        row.expectForward(2, 1, 222638.981586547, 110568.812396267, 0.1 * GieRow.MM);
        row.expectForward(2, -1, 222638.981586547, -110568.812396266, 0.1 * GieRow.MM);
        row.expectForward(150, 50, 16697923.6190, 4865983.5552, 0.1 * GieRow.MM);
        row.expectInverse(200, 100, 0.001796631, 0.000904369, 0.1 * GieRow.MM);
        row.expectInverse(-200, -100, -0.001796631, -0.000904369, 0.1 * GieRow.MM);
        // Latitude 50: before the re-point this returned 50.000000002039194, 0.227 mm out.
        row.expectInverse(16697923.6190, 4865983.5552, 150, 50, 0.1 * GieRow.MM);
    }

    /** {@code builtins.gie:1041-1058} — the spherical branch bypasses the series entirely. */
    @Test
    public void cylindricalEqualAreaSphericalMatchesGie() {
        GieRow row = GieRow.sphere("+proj=cea +R=6400000", 6400000.0);
        row.expectForward(2, 1, 223402.144255274, 111695.401198614, 0.1 * GieRow.MM);
        row.expectForward(2, -1, 223402.144255274, -111695.401198614, 0.1 * GieRow.MM);
        row.expectInverse(200, 100, 0.001790493, 0.000895247, 0.1 * GieRow.MM);
    }

    /** {@code builtins.gie:3521-3536}, the oblique aspect at {@code +lat_0=45}. */
    @Test
    public void lambertAzimuthalObliqueMatchesGie() {
        GieRow row = GieRow.grs80("+proj=laea +ellps=GRS80 +lat_0=45");
        row.expectForward(0, 45, 0, 0, 0.1 * GieRow.MM);
        row.expectForward(0, 0, 0, -4860248.8602, 0.1 * GieRow.MM);
        row.expectForward(0, -45, 0, -8984728.0442, 0.1 * GieRow.MM);
        row.expectForward(45, 45, 3318800.8682, 968788.2336, 0.1 * GieRow.MM);
        // builtins.gie:3532 relaxes this one to 50 mm: "Passes 0.1 mm except on i386."
        row.expectForward(0, 90, 0, 4886594.2207, 50.0 * GieRow.MM);
        // builtins.gie:3543, the rho < EPS10 path.
        row.expectInverse(0, 0, 0, 45, 0.1 * GieRow.MM);
        // builtins.gie:3538-3539, "tolerance 10 cm; accept 45 45; roundtrip 100".
        row.expectRoundtrip(45, 45, 100, 100.0 * GieRow.MM);
    }

    /**
     * The polar aspect is where the {@code asin} form loses relative accuracy, and where the
     * {@code q >= 1e-15} guard of {@code laea.cpp:75} replaces proj4j's {@code q >= 0}. Before
     * the re-point the round trip of (20, 70) closed to 69.99999999369757, <b>0.701 mm</b> out.
     */
    @Test
    public void lambertAzimuthalPolarRoundTripCloses() {
        GieRow north = GieRow.grs80("+proj=laea +ellps=GRS80 +lat_0=90");
        north.expectRoundtrip(20, 70, 1, 0.1 * GieRow.MM);
        north.expectRoundtrip(20, 18.01, 1, 0.1 * GieRow.MM);
        north.expectForward(0, 90, 0, 0, 1e-9);

        GieRow south = GieRow.grs80("+proj=laea +ellps=GRS80 +lat_0=-90");
        south.expectRoundtrip(20, -70, 1, 0.1 * GieRow.MM);
        south.expectForward(0, -90, 0, 0, 1e-9);
    }

    /**
     * {@code AuthalicLat.q} must stay public and stay in use: {@code laea.cpp:39} recovers
     * {@code q = sin(xi) * qp} and {@code cea.cpp:21} and {@code aea.cpp:70} use {@code q}
     * directly. Assert the two routes to {@code q} agree, which is what makes the substitution
     * legitimate.
     */
    @Test
    public void directSeriesAndRawQAgree() {
        AuthalicLat authalic = new AuthalicLat(GRS80_ES);
        assertTrue("the order-6 series must be the branch taken for every Earth ellipsoid",
                authalic.isSeriesValid());
        for (int i = 0; i <= 90; i++) {
            double phi = Math.toRadians(i);
            double sinphi = Math.sin(phi);
            double viaSeries = Math.sin(authalic.forward(phi, sinphi, Math.cos(phi)))
                    * authalic.qp();
            double raw = authalic.q(sinphi);
            assertEquals("q at latitude " + i, raw, viaSeries, 1e-15 * authalic.qp());
        }
    }
}

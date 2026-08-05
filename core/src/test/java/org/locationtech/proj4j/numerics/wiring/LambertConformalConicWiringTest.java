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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.locationtech.proj4j.numerics.wiring.GieCase.GRS80_A;
import static org.locationtech.proj4j.numerics.wiring.GieCase.GRS80_ES;
import static org.locationtech.proj4j.numerics.wiring.GieCase.MM;
import static org.locationtech.proj4j.numerics.wiring.GieCase.NM;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.util.ConformalLat;

/**
 * {@code LambertConformalConicProjection} re-pointed at {@link ConformalLat}.
 *
 * <p><b>A note on what the corpus can and cannot show here.</b> {@code lcc}'s inverse expectations
 * are printed to nine decimal places of a degree, and one unit in that last place is
 * {@code 1.11e-4 m} — larger than the block's own {@code 0.1 mm} tolerance. So the residual against
 * an {@code lcc} inverse row is dominated by the corpus's print precision (23 um on
 * {@code builtins.gie:3767}) and both the old and the new path sit inside the bar. The corpus rows
 * are therefore asserted as no-regression checks, and the old-versus-new separation is made where
 * it is real: <b>round-trip closure</b>, which the old {@code phi2} cannot deliver because it stops
 * at {@code |dphi| <= 1e-10} radians — 0.64 mm on the ground — regardless of how much further it
 * would have to go.
 */
public class LambertConformalConicWiringTest {

    /** {@code builtins.gie:3750}. */
    private static final String OP = "+proj=lcc +ellps=GRS80 +lat_1=0.5 +lat_2=2";

    private static final GieCase TWO_SP = GieCase.grs80(OP);

    /** {@code builtins.gie:3755-3763}, {@code tolerance 0.1 mm}. */
    @Test
    public void twoStandardParallelForwardMatchesGie() {
        TWO_SP.expectForward(2, 1, 222588.439735968, 110660.533870800, 0.1 * MM);
        TWO_SP.expectForward(2, -1, 222756.879700279, -110532.797660827, 0.1 * MM);
        TWO_SP.expectForward(-2, 1, -222588.439735968, 110660.533870800, 0.1 * MM);
        TWO_SP.expectForward(-2, -1, -222756.879700279, -110532.797660827, 0.1 * MM);
    }

    /** {@code builtins.gie:3765-3773}, {@code tolerance 0.1 mm}. */
    @Test
    public void twoStandardParallelInverseMatchesGie() {
        TWO_SP.expectInverse(200, 100, 0.001796359, 0.000904232, 0.1 * MM);
        TWO_SP.expectInverse(200, -100, 0.001796358, -0.000904233, 0.1 * MM);
        TWO_SP.expectInverse(-200, 100, -0.001796359, 0.000904232, 0.1 * MM);
        TWO_SP.expectInverse(-200, -100, -0.001796358, -0.000904233, 0.1 * MM);
    }

    /**
     * {@code builtins.gie:3777-3800}, the Lambert Conformal Conic 2SP Michigan block with
     * {@code +k_0=1.0000382}. Included because {@code k0} multiplies the whole coordinate and so
     * would expose any misplacement of the scale factor introduced while touching this file.
     */
    @Test
    public void michiganVariantMatchesGie() {
        GieCase r = GieCase.grs80("+proj=lcc +ellps=GRS80 +lat_1=0.5 +lat_2=2 +k_0=1.0000382");
        r.expectForward(2, 1, 222596.942614366, 110664.761103214, 0.1 * MM);
        r.expectForward(2, -1, 222765.389013083, -110537.020013748, 0.1 * MM);
        r.expectForward(-2, 1, -222596.942614366, 110664.761103214, 0.1 * MM);
        r.expectForward(-2, -1, -222765.389013083, -110537.020013748, 0.1 * MM);
        r.expectInverse(200, 100, 0.001796291, 0.000904198, 0.1 * MM);
        r.expectInverse(200, -100, 0.001796290, -0.000904199, 0.1 * MM);
    }

    /** {@code builtins.gie:3829-3840}, a widely-separated pair of standard parallels on GRS80. */
    @Test
    public void widelySeparatedParallelsMatchGie() {
        GieCase r = GieCase.grs80("+proj=lcc +ellps=GRS80 +lat_1=30 +lat_2=45");
        r.expectForward(1, 2, 131833.493971117, 265456.213515346, 0.1 * MM);
        r.expectForward(1, -2, 137536.205750651, -269686.591917190, 0.1 * MM);
        r.expectForward(-1, 2, -131833.493971117, 265456.213515346, 0.1 * MM);
        r.expectForward(-1, -2, -137536.205750651, -269686.591917190, 0.1 * MM);
    }

    /**
     * {@code builtins.gie:3802-3809}: {@code accept 0 90 / expect 0 292411117.537843227} and
     * {@code accept 0 0 / expect 0 0}, both at {@code tolerance 0.1 mm}. The pole row takes the
     * {@code |phi| - pi/2 < 1e-10} branch that bypasses {@code tsfn} entirely, and the equator row
     * pins {@code tsfn} at the value where the old helper was one ulp short.
     */
    @Test
    public void poleAndEquatorRowsMatchGie() {
        TWO_SP.expectForward(0, 90, 0, 292411117.537843227, 0.1 * MM);
        TWO_SP.expectForward(0, 0, 0, 0, 0.1 * MM);
    }

    /**
     * The old-versus-new measurement, made where the corpus rows cannot make it: both paths are
     * driven at the corpus rows and their <b>round-trip residual</b> compared.
     *
     * <p>{@code ProjectionMath.phi2} exits on {@code |dphi| <= 1e-10} radians and returns whatever
     * it has, so composing the exact forward with it does not close; the Newton-on-{@code tau}
     * formulation converges to full double precision, and the composition closes to nanometres.
     */
    @Test
    public void roundTripClosureIsStrictlyBetterThanTheDeprecatedPath() {
        // lat_0 is 0, not 0.5: lcc.cpp:85-90 falls lat_0 back to lat_1 only when lat_2 is absent.
        Legacy.Lcc old = new Legacy.Lcc(GRS80_A, GRS80_ES, 0.5, 2.0, 0.0, 1.0);

        double worstBefore = 0.0;
        double worstNow = 0.0;
        for (double lat : new double[] {0.000904232, 0.5, 1, 2, 2.8, 10, 30, 60, 85}) {
            // The old path, one cycle: its own forward, then its own inverse.
            double[] xy = old.forward(2.0, lat);
            double[] lp = old.inverse(xy[0], xy[1]);
            double before = TWO_SP.angularDeviation(2.0, lat, new ProjCoordinate(lp[0], lp[1]));
            double now = TWO_SP.roundtripDeviation(2.0, lat, 1);
            worstBefore = Math.max(worstBefore, before);
            worstNow = Math.max(worstNow, now);
        }
        assertTrue("the deprecated phi2 stops at 1e-10 rad, so an lcc round trip should not close "
                        + "below a micrometre; measured " + worstBefore + " m",
                worstBefore > 1.0e-6);
        assertTrue("the Karney formulation must close to nanometres, measured " + worstNow + " m",
                worstNow < 100 * NM);
        GieCase.assertStrictlyBetter("lcc round-trip closure over 0.0009 to 85 degrees",
                worstBefore, worstNow, 0.1 * MM);
    }

    /**
     * {@code roundtrip 100} is not asserted for {@code lcc} anywhere in {@code builtins.gie}, so
     * this is proj4j's own bar: 100 cycles must not drift past the 0.1 mm the point rows use.
     */
    @Test
    public void hundredCycleRoundTripDoesNotDrift() {
        for (double lat : new double[] {0.000904232, 1, 2.8, 30, 60, 85}) {
            TWO_SP.expectRoundtrip(2, lat, 100, 0.1 * MM);
            TWO_SP.expectRoundtrip(-2, lat, 100, 0.1 * MM);
        }
    }

    /**
     * {@code builtins.gie:3812-3826} and {@code :3843-3862}, the spherical blocks. The spherical
     * branch does not call {@code tsfn} or {@code phi2} at all, so these are pure no-movement
     * guards on a file that was edited in five places.
     *
     * <p>Written with the corpus's literal {@code +ellps=sphere}, which only became usable once
     * {@code Ellipsoid.SPHERE} was corrected from {@code 6371008.7714} — the GRS80 <em>authalic</em>
     * radius — to {@code 6370997.0}, PROJ's "Normal Sphere" ({@code 9.8.1:src/ellps.cpp:55}). That
     * is 1.848 ppm, or <b>0.54 m</b> at the easting below, against a 0.1 mm bar: no projection
     * formula could absorb it, and it presented as a maths error in whatever projection was under
     * test. These rows are asserted here because they are among the six {@code +ellps=sphere}
     * operations in {@code builtins.gie} that belong to {@code lcc}.
     *
     * <p>The other two of those six, {@code :3901} and {@code :3906}, are {@code +lat_1=91} and
     * {@code +lat_2=91} <b>setup-rejection</b> rows. They are expected to fail and must keep
     * failing, so they are asserted as rejections rather than as coordinates.
     */
    @Test
    public void sphericalBranchIsUnmoved() {
        GieCase r = new GieCase("+proj=lcc +ellps=sphere +lat_1=30 +lat_2=40", "+ellps=sphere",
                6370997.0, 0.0);
        r.expectForward(1, 2, 129391.909521100, 262101.674176860, 0.1 * MM);
        r.expectForward(0, 0, 0, 0, 0.1 * MM);
        r.expectInverse(129391.909521100, 262101.674176860, 1, 2, 0.1 * MM);
        r.expectInverse(0, 0, 0, 0, 0.1 * MM);

        GieCase r2 = new GieCase("+proj=lcc +ellps=sphere +lat_1=30 +lat_2=45", "+ellps=sphere",
                6370997.0, 0.0);
        r2.expectForward(1, 2, 131824.206082557, 267239.875053699, 0.1 * MM);
        r2.expectForward(-1, -2, -137565.475967350, -271546.945608449, 0.1 * MM);
        r2.expectForward(1, -2, 137565.475967350, -271546.945608449, 0.1 * MM);
        r2.expectForward(-1, 2, -131824.206082557, 267239.875053699, 0.1 * MM);
        r2.expectInverse(131824.206082557, 267239.875053699, 1, 2, 0.1 * MM);
    }

    /**
     * The eight {@code expect failure errno invalid_op_illegal_arg_value} rows at
     * {@code builtins.gie:3862}, {@code :3869}, {@code :3876}, {@code :3881}, {@code :3886},
     * {@code :3891}, {@code :3896}, {@code :3901} and {@code :3906}.
     *
     * <p><b>proj4j accepted every one of them</b> and went on to produce coordinates from a cone
     * constant that was infinite, NaN or exactly zero — non-negotiably wrong, because a failure must
     * not be expressed as a plausible coordinate. {@code lcc.cpp:100-110} rejects a standard parallel
     * at or beyond the pole, and {@code :122-138}/{@code :154-161} reject a cone constant that comes
     * out exactly zero.
     *
     * <p>{@code errno} is checked, not just the exception type: gie names
     * {@code invalid_op_illegal_arg_value}, which is
     * {@link ErrorCause#INVALID_PARAM_VALUE}.
     */
    @Test
    public void degenerateSetupsAreRejectedWithTheErrnoGieNames() {
        String[] rows = {
            "+proj=lcc +a=9999999 +b=.9 +lat_2=1",                    // :3862
            "+proj=lcc +lat_1=2D32 +lat_2=0 +a=6378137 +b=0.2",       // :3869
            "+proj=lcc +ellps=GRS80 +lat_1=0 +lat_2=90",              // :3876
            "+proj=lcc +ellps=GRS80 +lat_1=90 +lat_2=0",              // :3881
            "+proj=lcc +ellps=GRS80 +lat_1=90 +lat_2=90",             // :3886
            "+proj=lcc +ellps=sphere +lat_1=0 +lat_2=90",             // :3891
            "+proj=lcc +ellps=sphere +lat_1=90 +lat_2=0",             // :3896
            "+proj=lcc +ellps=sphere +lat_1=91",                      // :3901
            "+proj=lcc +ellps=sphere +lat_2=91",                      // :3906
        };
        CRSFactory factory = new CRSFactory();
        for (String row : rows) {
            try {
                factory.createFromParameters("gie", row + " +no_defs");
                fail("gie expects this setup to fail: " + row);
            } catch (InvalidValueException e) {
                assertTrue(row + " must report invalid_op_illegal_arg_value, reported "
                                + e.cause(),
                        e.cause() == ErrorCause.INVALID_PARAM_VALUE);
            }
        }
    }

    /**
     * The counterpart of the previous test, and the reason it is safe: the parallels the corpus and
     * the bundled tables actually use must still be accepted. All 1,885 {@code +proj=lcc}
     * definitions in the EPSG, ESRI and NAD tables have {@code |lat_1| < 90} and
     * {@code |lat_2| < 90}, so the new guards cannot reach them; these are the extremes.
     */
    @Test
    public void parallelsShortOfThePoleAreStillAccepted() {
        GieCase.grs80("+proj=lcc +ellps=GRS80 +lat_1=89.999 +lat_2=1").expectRoundtrip(0, 45, 1,
                0.1 * MM);
        GieCase.grs80("+proj=lcc +ellps=GRS80 +lat_1=-89.999 +lat_2=-1").expectRoundtrip(0, -45, 1,
                0.1 * MM);
        GieCase.grs80("+proj=lcc +ellps=GRS80 +lat_1=1e-9 +lat_2=45").expectRoundtrip(0, 30, 1,
                0.1 * MM);
    }
}

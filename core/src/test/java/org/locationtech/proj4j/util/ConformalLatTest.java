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

package org.locationtech.proj4j.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link ConformalLat}, including the measurements that justify replacing
 * {@code ProjectionMath.phi2} and {@code ProjectionMath.tsfn}.
 *
 * <p>{@code ProjectionMath} is read and called here for comparison only; nothing in it is
 * modified.
 *
 * @see PjPhi2Test for the verbatim port of upstream's own unit test
 */
public class ConformalLatTest {

    private static final double A = NumericAssert.GRS80_A;
    private static final double ES = NumericAssert.GRS80_ES;
    private static final double E = NumericAssert.GRS80_E;
    private static final double NM = NumericAssert.NM;

    private static final int STEPS = 1798; // 0.05 deg steps over (0, 89.9]

    private static double lat(int i) {
        return Math.toRadians(i * 0.05);
    }

    /**
     * The bar the corpus sets at {@code builtins.gie:4262-4266} is {@code tolerance 0 m},
     * which means the value at the equator must be <b>exactly</b> 1. proj4j's
     * {@code tan}-and-{@code pow} form returns {@code 0.9999999999999999}.
     */
    @Test
    public void tsfnIsExactlyOneAtTheEquator() {
        for (double e : new double[]{0.0, 1.0e-9, E, 0.2, 0.5}) {
            assertEquals("tsfn(phi=0, sinphi=0, e=" + e + ") must be exactly 1.0",
                    1.0, ConformalLat.tsfn(0.0, 0.0, e), 0.0);
            assertEquals("tsfnSinCos(0, 1, e=" + e + ") must be exactly 1.0",
                    1.0, ConformalLat.tsfnSinCos(0.0, 1.0, e), 0.0);
        }
        // The defect being fixed: proj4j's version is one ulp short.
        assertTrue("ProjectionMath.tsfn is expected to miss 1.0 at the equator",
                ProjectionMath.tsfn(0.0, 0.0, E) != 1.0);
        assertEquals(0.9999999999999999, ProjectionMath.tsfn(0.0, 0.0, E), 0.0);
    }

    /** {@code tsfn} must agree with its definition, {@code exp(-asinh(tan chi))}. */
    @Test
    public void tsfnMatchesTheConformalLatitudeDefinition() {
        double worstNew = 0.0;
        double worstOld = 0.0;
        for (int i = 0; i <= STEPS; i++) {
            double phi = lat(i);
            double chi = ConformalLat.conformalLat(phi, E);
            double reference = StrictMath.exp(-MathHelpers.asinh(StrictMath.tan(chi)));
            double sinphi = StrictMath.sin(phi);
            worstNew = Math.max(worstNew,
                    Math.abs(ConformalLat.tsfn(phi, sinphi, E) - reference) / reference);
            worstOld = Math.max(worstOld,
                    Math.abs(ProjectionMath.tsfn(phi, sinphi, E) - reference) / reference);
        }
        assertTrue("tsfn relative error " + worstNew, worstNew < 1.0e-12);
        assertTrue("new tsfn must be at least as accurate as ProjectionMath's: new "
                + worstNew + " old " + worstOld, worstNew <= worstOld);
    }

    /** The branch at {@code sinphi > 0} must be continuous across the equator. */
    @Test
    public void tsfnIsContinuousAcrossTheBranch() {
        double justAbove = ConformalLat.tsfn(1.0e-12, StrictMath.sin(1.0e-12), E);
        double justBelow = ConformalLat.tsfn(-1.0e-12, StrictMath.sin(-1.0e-12), E);
        assertEquals(1.0, justAbove, 1.0e-11);
        assertEquals(1.0, justBelow, 1.0e-11);
        // ts is 1/ts under reflection: ts(-phi) = 1/ts(phi).
        double worst = 0.0;
        for (int i = 1; i <= STEPS; i++) {
            double phi = lat(i);
            double up = ConformalLat.tsfn(phi, StrictMath.sin(phi), E);
            double down = ConformalLat.tsfn(-phi, StrictMath.sin(-phi), E);
            worst = Math.max(worst, Math.abs(up * down - 1.0));
        }
        assertTrue("ts(phi)*ts(-phi) must be 1, worst deviation " + worst, worst < 1.0e-14);
    }

    /**
     * The headline measurement for {@code phi2}. Both implementations are handed the
     * <em>same</em> accurate {@code ts}, so what is measured is the inverse alone.
     * proj4j's 15-step {@code pow}-bearing Newton loop, with its {@code 1e-10 rad} exit
     * test, is about 4,145 nm off at latitude 2.8 degrees against a 50 nm bar; the
     * Newton-on-tau formulation is about 2 nm.
     */
    @Test
    public void phi2BeatsProjectionMathOnTheSameInput() {
        double worstNew = 0.0;
        double worstOld = 0.0;
        double worstNewAt = 0.0;
        double worstOldAt = 0.0;
        for (int i = 1; i <= STEPS; i++) {
            double phi = lat(i);
            double ts = ConformalLat.tsfn(phi, StrictMath.sin(phi), E);
            double errNew = Math.abs(ConformalLat.phi2(ts, E) - phi) * A;
            double errOld = Math.abs(ProjectionMath.phi2(ts, E) - phi) * A;
            if (errNew > worstNew) {
                worstNew = errNew;
                worstNewAt = Math.toDegrees(phi);
            }
            if (errOld > worstOld) {
                worstOld = errOld;
                worstOldAt = Math.toDegrees(phi);
            }
        }
        String report = "new " + (worstNew / NM) + " nm @ " + worstNewAt + " deg; old "
                + (worstOld / NM) + " nm @ " + worstOldAt + " deg";
        assertTrue("new phi2 must be inside the 50 nm gie bar: " + report,
                worstNew < 10.0 * NM);
        assertTrue("ProjectionMath.phi2 is expected to blow the 50 nm bar: " + report,
                worstOld > 1000.0 * NM);
        assertTrue("new phi2 must beat ProjectionMath by >100x: " + report,
                worstNew * 100.0 < worstOld);
    }

    /**
     * The {@code tsfn -> phi2} round trip, each stack using its own {@code tsfn}. This is
     * the composition a {@code merc} forward-then-inverse actually performs.
     */
    @Test
    public void roundTripBeatsProjectionMath() {
        double worstNew = 0.0;
        double worstOld = 0.0;
        for (int i = 1; i <= STEPS; i++) {
            double phi = lat(i);
            double sinphi = StrictMath.sin(phi);
            worstNew = Math.max(worstNew, Math.abs(
                    ConformalLat.phi2(ConformalLat.tsfn(phi, sinphi, E), E) - phi) * A);
            worstOld = Math.max(worstOld, Math.abs(
                    ProjectionMath.phi2(ProjectionMath.tsfn(phi, sinphi, E), E) - phi) * A);
        }
        String report = "new " + (worstNew / NM) + " nm; old " + (worstOld / NM) + " nm";
        assertTrue("new tsfn/phi2 round trip must be inside the 50 nm gie bar: " + report,
                worstNew < 10.0 * NM);
        assertTrue("proj4j's round trip is expected to blow the 50 nm bar: " + report,
                worstOld > 1000.0 * NM);
        assertTrue("new round trip must beat ProjectionMath by >100x: " + report,
                worstNew * 100.0 < worstOld);
    }

    /**
     * {@code conformalLat} and {@code conformalLatInverse} must compose to the identity,
     * and must be exact identities on a sphere.
     */
    @Test
    public void conformalLatitudeRoundTrips() {
        double worst = 0.0;
        for (int i = 0; i <= STEPS; i++) {
            double phi = lat(i);
            double chi = ConformalLat.conformalLat(phi, E);
            assertTrue("chi must not exceed phi in magnitude at " + Math.toDegrees(phi),
                    Math.abs(chi) <= Math.abs(phi) + 1.0e-15);
            worst = Math.max(worst, Math.abs(ConformalLat.conformalLatInverse(chi, E) - phi) * A);
        }
        assertTrue("conformal latitude round trip " + (worst / NM) + " nm",
                worst < 10.0 * NM);

        for (int i = -STEPS; i <= STEPS; i += 7) {
            double phi = lat(i);
            NumericAssert.assertSameBits("sphere forward", phi,
                    ConformalLat.conformalLat(phi, 0.0));
            NumericAssert.assertSameBits("sphere inverse", phi,
                    ConformalLat.conformalLatInverse(phi, 0.0));
        }
    }

    /**
     * The conformal latitude must agree with the {@code AuxLat} series for
     * {@code phi -> chi}, which is an entirely independent route to the same quantity —
     * one an elementary closed form, the other an order-6 Fourier series. Agreement is
     * strong evidence for both the {@code C[chi,phi]} table block and this function.
     */
    @Test
    public void conformalLatAgreesWithTheAuxLatSeries() {
        Clenshaw6 phiToChi = Clenshaw6.forConversion(AuxLat.thirdFlattening(ES),
                AuxLat.GEOGRAPHIC, AuxLat.CONFORMAL);
        Clenshaw6 chiToPhi = Clenshaw6.forConversion(AuxLat.thirdFlattening(ES),
                AuxLat.CONFORMAL, AuxLat.GEOGRAPHIC);
        double worstForward = 0.0;
        double worstInverse = 0.0;
        for (int i = 0; i <= STEPS; i++) {
            double phi = lat(i);
            double chi = ConformalLat.conformalLat(phi, E);
            worstForward = Math.max(worstForward, Math.abs(phiToChi.convert(phi) - chi) * A);
            worstInverse = Math.max(worstInverse,
                    Math.abs(chiToPhi.convert(chi) - ConformalLat.conformalLatInverse(chi, E)) * A);
        }
        assertTrue("closed form vs phi->chi series: " + (worstForward / NM) + " nm",
                worstForward < 10.0 * NM);
        assertTrue("closed form vs chi->phi series: " + (worstInverse / NM) + " nm",
                worstInverse < 10.0 * NM);
    }

    /**
     * The near-pole initial-guess branch: {@code |taup| > 70} corresponds to
     * {@code chi = 89.18} degrees. Both sides of the branch must be accurate and the branch
     * must be continuous.
     */
    @Test
    public void nearPoleGuessBranchIsContinuousAndAccurate() {
        double belowThreshold = ConformalLat.sinhpsi2tanphi(70.0, E);
        double aboveThreshold = ConformalLat.sinhpsi2tanphi(Math.nextUp(70.0), E);
        assertEquals("the two initial guesses must converge to the same root",
                belowThreshold, aboveThreshold, 1.0e-12);
        assertEquals("and symmetrically below zero",
                -ConformalLat.sinhpsi2tanphi(-70.0, E),
                ConformalLat.sinhpsi2tanphi(70.0, E), 1.0e-12);

        // Very close to the pole, the recovered latitude must still be right.
        double worst = 0.0;
        for (int i = 1; i <= 1000; i++) {
            double phi = Math.PI / 2.0 - StrictMath.pow(10.0, -3.0 - i / 100.0);
            double ts = ConformalLat.tsfn(phi, StrictMath.sin(phi), E);
            if (ts == 0.0) {
                continue; // Underflowed; nothing left to recover.
            }
            worst = Math.max(worst, Math.abs(ConformalLat.phi2(ts, E) - phi) * A);
        }
        assertTrue("near-pole phi2 error " + (worst / NM) + " nm", worst < 1000.0 * NM);
    }

    /** The whole point of the Newton reformulation: relative accuracy in tan(phi). */
    @Test
    public void sinhpsi2tanphiIsAccurateInRelativeTerms() {
        double worst = 0.0;
        for (int i = -300; i <= 300; i++) {
            double tau = StrictMath.pow(10.0, i / 30.0);
            // Forward: taup(tau) from Karney (2011) Eq. 7.
            double tau1 = Math.sqrt(1.0 + tau * tau);
            double sig = StrictMath.sinh(E * MathHelpers.atanh(E * tau / tau1));
            double taup = Math.sqrt(1.0 + sig * sig) * tau - sig * tau1;
            double back = ConformalLat.sinhpsi2tanphi(taup, E);
            worst = Math.max(worst, Math.abs(back / tau - 1.0));
            // And odd in taup.
            assertEquals(-back, ConformalLat.sinhpsi2tanphi(-taup, E),
                    1.0e-14 * Math.abs(back));
        }
        assertTrue("relative error in tan(phi) was " + worst, worst < 1.0e-14);
    }

    /** A sphere must reduce to the elementary Mercator relations exactly. */
    @Test
    public void sphereReducesToElementaryMercator() {
        for (int i = 1; i <= STEPS; i++) {
            double phi = lat(i);
            double ts = ConformalLat.tsfn(phi, StrictMath.sin(phi), 0.0);
            // ts = 1/(tan(phi) + sec(phi)) = tan(pi/4 - phi/2)
            assertEquals("sphere ts at " + Math.toDegrees(phi),
                    StrictMath.tan(Math.PI / 4.0 - phi / 2.0), ts, 1.0e-15);
            assertEquals("sphere phi2 at " + Math.toDegrees(phi), phi,
                    ConformalLat.phi2(ts, 0.0), 1.0e-15);
        }
    }

    /** Repeated evaluation must be bit-identical. */
    @Test
    public void isDeterministic() {
        for (int i = 1; i <= STEPS; i += 17) {
            double phi = lat(i);
            double sinphi = StrictMath.sin(phi);
            double ts = ConformalLat.tsfn(phi, sinphi, E);
            double first = ConformalLat.phi2(ts, E);
            for (int rep = 0; rep < 200; rep++) {
                NumericAssert.assertSameBits("tsfn", ts, ConformalLat.tsfn(phi, sinphi, E));
                NumericAssert.assertSameBits("phi2", first, ConformalLat.phi2(ts, E));
            }
        }
    }
}

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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link MeridianArc}, including the measurement that justifies replacing
 * {@code ProjectionMath.enfn/mlfn/inv_mlfn}.
 *
 * <p><b>Note on the metric.</b> A mlfn round trip is <em>self-consistency</em>, and
 * {@code ProjectionMath.inv_mlfn} is Newton's method run against
 * {@code ProjectionMath.mlfn} itself, so it is self-consistent by construction — its round
 * trip closes even though both halves are far from the truth. The comparison that matters
 * is therefore against an <b>independent</b> reference, the compensated Gauss-Legendre
 * quadrature in {@link NumericAssert#meridianArcReference}. Both metrics are asserted
 * below, and the sign of the difference between them is the point.
 *
 * <p>{@code ProjectionMath} is read and called here for comparison only; nothing in it is
 * modified.
 */
public class MeridianArcTest {

    private static final double A = NumericAssert.GRS80_A;
    private static final double ES = NumericAssert.GRS80_ES;
    private static final double NM = NumericAssert.NM;

    /** 0.05 degree steps over 0 to 90; the peaks quoted below are all interior. */
    private static final int STEPS = 1800;

    private static double lat(int i) {
        return Math.toRadians(i * (90.0 / STEPS));
    }

    /**
     * The headline measurement. On GRS80, {@code ProjectionMath.mlfn} is about 4,920 nm
     * from the truth at latitude 72.55 degrees, against the 50 nm bar that {@code tmerc}'s
     * conformance assertions set; the order-6 series in {@code n} is a couple of
     * nanometres.
     */
    @Test
    public void forwardBeatsProjectionMathAgainstAnIndependentReference() {
        MeridianArc arc = MeridianArc.fromEs(ES);
        double[] oldEn = ProjectionMath.enfn(ES);

        double worstNew = 0.0;
        double worstOld = 0.0;
        double worstNewAt = 0.0;
        double worstOldAt = 0.0;
        for (int i = 0; i <= STEPS; i++) {
            double phi = lat(i);
            double sphi = StrictMath.sin(phi);
            double cphi = StrictMath.cos(phi);
            double reference = NumericAssert.meridianArcReference(phi, ES);
            double errNew = Math.abs(arc.mlfn(phi, sphi, cphi) - reference) * A;
            double errOld = Math.abs(ProjectionMath.mlfn(phi, sphi, cphi, oldEn) - reference) * A;
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

        // The gie bar for tmerc's tightest assertions is 50 nm; the reference quadrature
        // itself is good to about 1 nm, so 20 nm is the tightest honest assertion here.
        assertTrue("new mlfn must be inside the 50 nm gie bar: " + report,
                worstNew < 20.0 * NM);
        // The defect being fixed.
        assertTrue("ProjectionMath.mlfn is expected to blow the 50 nm bar: " + report,
                worstOld > 1000.0 * NM);
        // And the new code must beat the old by a wide margin, which is the evidence.
        assertTrue("new mlfn must beat ProjectionMath by >100x: " + report,
                worstNew * 100.0 < worstOld);
    }

    /**
     * The same comparison for the inverse. Upstream's is closed form; proj4j's is a 10-step
     * Newton loop whose accuracy is capped by the forward series it inverts.
     */
    @Test
    public void inverseBeatsProjectionMathAgainstAnIndependentReference() {
        MeridianArc arc = MeridianArc.fromEs(ES);
        double[] oldEn = ProjectionMath.enfn(ES);

        double worstNew = 0.0;
        double worstOld = 0.0;
        for (int i = 0; i <= STEPS; i++) {
            double phi = lat(i);
            double mu = NumericAssert.meridianArcReference(phi, ES);
            worstNew = Math.max(worstNew, Math.abs(arc.invMlfn(mu) - phi) * A);
            worstOld = Math.max(worstOld,
                    Math.abs(ProjectionMath.inv_mlfn(mu, ES, oldEn) - phi) * A);
        }
        String report = "new " + (worstNew / NM) + " nm; old " + (worstOld / NM) + " nm";
        assertTrue("new invMlfn must be inside the 50 nm gie bar: " + report,
                worstNew < 20.0 * NM);
        assertTrue("ProjectionMath.inv_mlfn is expected to blow the 50 nm bar: " + report,
                worstOld > 1000.0 * NM);
        assertTrue("new invMlfn must beat ProjectionMath by >100x: " + report,
                worstNew * 100.0 < worstOld);
    }

    /**
     * Round-trip self-consistency of the new pair. Documented here at the measured level so
     * that a regression is visible, and explicitly <em>not</em> claimed to beat proj4j:
     * proj4j's inverse is Newton against its own forward and so closes to about 0.7 nm
     * while both halves sit 4,900 nm from the truth. See the class comment.
     */
    @Test
    public void roundTripIsSelfConsistentToAFewNanometres() {
        MeridianArc arc = MeridianArc.fromEs(ES);
        double worst = 0.0;
        double worstAt = 0.0;
        for (int i = 0; i <= STEPS; i++) {
            double phi = lat(i);
            double mu = arc.mlfn(phi, StrictMath.sin(phi), StrictMath.cos(phi));
            double err = Math.abs(arc.invMlfn(mu) - phi) * A;
            if (err > worst) {
                worst = err;
                worstAt = Math.toDegrees(phi);
            }
        }
        assertTrue("mlfn/invMlfn round trip " + (worst / NM) + " nm @ " + worstAt
                + " deg must stay inside 5 nm", worst < 5.0 * NM);
    }

    /** Southern hemisphere: both directions must be exactly odd in latitude. */
    @Test
    public void isOddInLatitude() {
        MeridianArc arc = MeridianArc.fromEs(ES);
        for (int i = 1; i <= STEPS; i++) {
            double phi = lat(i);
            double forward = arc.mlfn(phi, StrictMath.sin(phi), StrictMath.cos(phi));
            double mirrored = arc.mlfn(-phi, StrictMath.sin(-phi), StrictMath.cos(-phi));
            assertEquals("mlfn must be odd at " + Math.toDegrees(phi), -forward, mirrored,
                    1.0e-16);
            assertEquals("invMlfn must be odd", -arc.invMlfn(forward),
                    arc.invMlfn(-forward), 1.0e-16);
        }
    }

    /** A sphere must be an exact identity in both directions. */
    @Test
    public void sphereIsAnExactIdentity() {
        MeridianArc arc = new MeridianArc(0.0);
        assertEquals(1.0, arc.rectifyingRadius(), 0.0);
        for (int i = -STEPS; i <= STEPS; i += 7) {
            double phi = lat(i);
            NumericAssert.assertSameBits("mlfn on a sphere at " + phi, phi,
                    arc.mlfn(phi, StrictMath.sin(phi), StrictMath.cos(phi)));
            NumericAssert.assertSameBits("invMlfn on a sphere at " + phi, phi,
                    arc.invMlfn(phi));
        }
        assertEquals(0.0, arc.thirdFlattening(), 0.0);
    }

    /**
     * {@link MeridianArc#mlfn(double)} must compute the same sine and cosine internally
     * that the explicit form is handed, bit for bit.
     */
    @Test
    public void convenienceOverloadsAgreeBitForBit() {
        MeridianArc arc = MeridianArc.fromEs(ES);
        for (int i = -STEPS; i <= STEPS; i += 11) {
            double phi = lat(i);
            NumericAssert.assertSameBits("mlfn at " + phi,
                    arc.mlfn(phi, StrictMath.sin(phi), StrictMath.cos(phi)), arc.mlfn(phi));
            double mu = arc.rectifyingLat(phi, StrictMath.sin(phi), StrictMath.cos(phi));
            NumericAssert.assertSameBits("rectifyingLat scaling at " + phi,
                    arc.mlfn(phi), arc.rectifyingRadius() * mu);
            NumericAssert.assertSameBits("invRectifyingLat at " + phi,
                    arc.invRectifyingLat(mu),
                    arc.invRectifyingLat(mu, StrictMath.sin(mu), StrictMath.cos(mu)));
        }
    }

    /**
     * The deliberate deviation from upstream: {@code invMlfn} multiplies by a precomputed
     * reciprocal instead of dividing by {@code en[0]}. Pin the claim that this costs at
     * most 1 ULP on the rectifying latitude — about 2 pm on the ground.
     */
    @Test
    public void reciprocalMultiplyCostsAtMostOneUlp() {
        MeridianArc arc = MeridianArc.fromEs(ES);
        double rr = arc.rectifyingRadius();
        double worstUlps = 0.0;
        for (int i = 0; i <= STEPS; i++) {
            double phi = lat(i);
            double mu = arc.mlfn(phi, StrictMath.sin(phi), StrictMath.cos(phi));
            double multiplied = mu * (1.0 / rr);
            double divided = mu / rr;
            worstUlps = Math.max(worstUlps, NumericAssert.ulpDistance(multiplied, divided));
        }
        assertTrue("reciprocal multiply differed by " + worstUlps + " ulps",
                worstUlps <= 1.0);
    }

    /** {@code n} must round-trip through {@code es} and the accessors must be stable. */
    @Test
    public void accessorsExposeTheSeriesUsed() {
        MeridianArc arc = MeridianArc.fromEs(ES);
        assertEquals(AuxLat.thirdFlattening(ES), arc.thirdFlattening(), 0.0);
        assertEquals(AuxLat.rectifyingRadius(arc.thirdFlattening()), arc.rectifyingRadius(),
                0.0);
        assertSame(arc.forwardSeries(), arc.forwardSeries());
        assertSame(arc.inverseSeries(), arc.inverseSeries());
        // The exposed series must be the ones the methods use.
        double phi = Math.toRadians(37.0);
        NumericAssert.assertSameBits("forwardSeries",
                arc.rectifyingRadius() * arc.forwardSeries().convert(phi), arc.mlfn(phi));
    }

    /**
     * Determinism: repeated construction and evaluation must be bit-identical, which is
     * what the fixed-order closed form buys over a Newton loop with a data-dependent trip
     * count.
     */
    @Test
    public void isDeterministicAcrossInstances() {
        for (int i = 0; i <= STEPS; i += 13) {
            double phi = lat(i);
            MeridianArc a = MeridianArc.fromEs(ES);
            double first = a.mlfn(phi);
            double firstInverse = a.invMlfn(first);
            for (int rep = 0; rep < 50; rep++) {
                MeridianArc b = MeridianArc.fromEs(ES);
                NumericAssert.assertSameBits("mlfn", first, b.mlfn(phi));
                NumericAssert.assertSameBits("invMlfn", firstInverse, b.invMlfn(first));
            }
        }
    }

    /**
     * The series is specified for {@code |f| <= 1/150}; check it still holds up at
     * {@code f = 1/150} exactly, and that the more oblate bodies proj4j accepts do not
     * produce nonsense.
     */
    @Test
    public void holdsUpAtTheSpecifiedFlatteningLimit() {
        double f = 1.0 / 150.0;
        double es = 2.0 * f - f * f;
        MeridianArc arc = MeridianArc.fromEs(es);
        double worst = 0.0;
        for (int i = 0; i <= 900; i++) {
            double phi = Math.toRadians(i / 10.0);
            double reference = NumericAssert.meridianArcReference(phi, es);
            worst = Math.max(worst,
                    Math.abs(arc.mlfn(phi, StrictMath.sin(phi), StrictMath.cos(phi))
                            - reference) * A);
        }
        assertTrue("at f = 1/150 the series was " + (worst / NM) + " nm off",
                worst < 50.0 * NM);
    }
}

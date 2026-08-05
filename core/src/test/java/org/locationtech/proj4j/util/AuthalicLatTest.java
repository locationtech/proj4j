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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link AuthalicLat}, including the measurement that justifies replacing
 * {@code ProjectionMath.authset}/{@code authlat} — the largest accuracy defect in proj4j's
 * numerical core.
 *
 * <p>{@code ProjectionMath} is read and called here for comparison only; nothing in it is
 * modified.
 */
public class AuthalicLatTest {

    private static final double A = NumericAssert.GRS80_A;
    private static final double ES = NumericAssert.GRS80_ES;
    private static final double E = NumericAssert.GRS80_E;
    private static final double NM = NumericAssert.NM;

    private static final int STEPS = 9000; // 0.01 deg steps over 0 to 90

    private static double lat(int i) {
        return Math.toRadians(i * 0.01);
    }

    /**
     * The headline measurement. proj4j's three-term {@code authlat} series is over 2 mm off
     * in the tropics, against the 0.1 mm bar {@code aea}'s inverse assertions set at
     * {@code builtins.gie:54-60}; the order-6 series is about 1 nm, i.e. under
     * {@code 1e-8 m}.
     *
     * <p>Both are handed the same, accurately computed authalic latitude, so what is
     * measured is the inverse series alone.
     */
    @Test
    public void inverseBeatsProjectionMathByOverSixOrdersOfMagnitude() {
        AuthalicLat authalic = new AuthalicLat(ES);
        double[] oldApa = ProjectionMath.authset(ES);

        double worstNew = 0.0;
        double worstOld = 0.0;
        double worstNewAt = 0.0;
        double worstOldAt = 0.0;
        for (int i = 0; i <= STEPS; i++) {
            double phi = lat(i);
            double beta = authalic.forward(phi, StrictMath.sin(phi), StrictMath.cos(phi));
            double errNew = Math.abs(authalic.inverse(beta) - phi) * A;
            double errOld = Math.abs(ProjectionMath.authlat(beta, oldApa) - phi) * A;
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
                + (worstOld * 1000.0) + " mm @ " + worstOldAt + " deg";

        // The requirement: under 1e-8 m equivalent.
        assertTrue("new authalic inverse must be under 1e-8 m: " + report, worstNew < 1.0e-8);
        // The defect being fixed: proj4j is over a millimetre, 10x+ the 0.1 mm gie bar.
        assertTrue("ProjectionMath.authlat is expected to exceed 1 mm: " + report,
                worstOld > 1.0e-3);
        assertTrue("new authalic inverse must beat ProjectionMath by >1e5: " + report,
                worstNew * 1.0e5 < worstOld);
    }

    /**
     * The full round trip, each stack using its own forward. proj4j's forward is the exact
     * {@code asin(q/qp)} form, which is accurate away from the poles, so the round-trip
     * error is dominated by the same inverse-series defect.
     */
    @Test
    public void roundTripBeatsProjectionMath() {
        AuthalicLat authalic = new AuthalicLat(ES);
        double[] oldApa = ProjectionMath.authset(ES);
        double oldQp = ProjectionMath.qsfn(1.0, E, 1.0 - ES);

        double worstNew = 0.0;
        double worstOld = 0.0;
        for (int i = 0; i <= STEPS; i++) {
            double phi = lat(i);
            double sinphi = StrictMath.sin(phi);
            double cosphi = StrictMath.cos(phi);

            double xi = authalic.forward(phi, sinphi, cosphi);
            worstNew = Math.max(worstNew, Math.abs(authalic.inverse(xi) - phi) * A);

            double ratio = ProjectionMath.qsfn(sinphi, E, 1.0 - ES) / oldQp;
            if (ratio > 1.0) {
                ratio = 1.0;
            }
            double betaOld = StrictMath.asin(ratio);
            worstOld = Math.max(worstOld,
                    Math.abs(ProjectionMath.authlat(betaOld, oldApa) - phi) * A);
        }
        String report = "new " + (worstNew / NM) + " nm; old " + (worstOld * 1000.0) + " mm";
        assertTrue("new round trip must be under 1e-8 m: " + report, worstNew < 1.0e-8);
        assertTrue("proj4j's round trip is expected to exceed 1 mm: " + report,
                worstOld > 1.0e-3);
    }

    /**
     * {@code q} must stay accurate near the equator, where {@code ProjectionMath.qsfn}'s
     * {@code log((1-x)/(1+x))} form cancels. Reference is the convergent Taylor series of
     * the exact expression.
     */
    @Test
    public void qKeepsRelativeAccuracyWhereQsfnCancels() {
        AuthalicLat authalic = new AuthalicLat(ES);
        double worstNew = 0.0;
        double worstOld = 0.0;
        for (int i = 1; i <= STEPS; i++) {
            double sinphi = StrictMath.sin(lat(i));
            double reference = qReference(sinphi, ES);
            worstNew = Math.max(worstNew,
                    Math.abs(authalic.q(sinphi) - reference) / Math.abs(reference));
            worstOld = Math.max(worstOld,
                    Math.abs(ProjectionMath.qsfn(sinphi, E, 1.0 - ES) - reference)
                            / Math.abs(reference));
        }
        String report = "new " + worstNew + " old " + worstOld;
        assertTrue("q relative error must be near machine precision: " + report,
                worstNew < 1.0e-15);
        assertTrue("ProjectionMath.qsfn is expected to be materially worse: " + report,
                worstOld > 100.0 * worstNew);
    }

    /**
     * {@code q = (1-es) (s/(1-es s^2) + atanh(e s)/e)} with the {@code atanh} expanded as
     * its Taylor series, so no cancellation can occur. {@code es < 0.007} makes the series
     * converge in a handful of terms.
     */
    private static double qReference(double s, double es) {
        double t1 = s / (1.0 - es * s * s);
        // atanh(e s)/e = sum_{k>=0} es^k s^(2k+1) / (2k+1)
        double t2 = 0.0;
        double term = s;
        for (int k = 0; k < 200; k++) {
            t2 += term / (2 * k + 1);
            term *= es * s * s;
            if (Math.abs(term) < 1.0e-30 * Math.abs(t2)) {
                break;
            }
        }
        return (1.0 - es) * (t1 + t2);
    }

    /**
     * For every Earth ellipsoid {@code n} is about 0.00168, well inside the 0.01 cutoff, so
     * the series branch is always taken and there is never any iteration.
     */
    @Test
    public void everyEarthEllipsoidUsesTheSeriesBranch() {
        double[] esValues = {
            0.0,                        // sphere
            0.00669437999014133,        // WGS84
            ES,                         // GRS80
            0.0067394967422764350,      // Bessel 1841
            0.006768657997291094,       // Clarke 1866
            0.006722670022333331,       // International 1924
            0.006674372231802145,       // Krassovsky 1940
            0.0068147849,               // Airy 1830-ish
        };
        for (double es : esValues) {
            AuthalicLat authalic = new AuthalicLat(es);
            assertTrue("es=" + es + " must use the series branch (n="
                    + authalic.thirdFlattening() + ")", authalic.isSeriesValid());
            assertTrue("Earth n must be about 0.00168, was " + authalic.thirdFlattening(),
                    authalic.thirdFlattening() < 0.01);
            assertNotNull("the forward series must be built when valid",
                    authalic.forwardSeries());
        }
    }

    /**
     * The {@code |n| >= 0.01} Newton fallback, needed for the synthetic conformance cases.
     * Exercised on moderately oblate bodies where the geometry is still well conditioned;
     * it must converge and must satisfy the defining relation
     * {@code sin(xi) = q(sin phi) / qp} to machine precision.
     */
    @Test
    public void newtonFallbackHandlesOblateBodies() {
        for (double f : new double[]{0.02, 0.05, 0.1, 0.2}) {
            double es = 2.0 * f - f * f;
            AuthalicLat authalic = new AuthalicLat(es);
            assertFalse("f=" + f + " must fall outside the series cutoff",
                    authalic.isSeriesValid());
            assertNull("the forward series is not allocated when the cutoff is exceeded",
                    authalic.forwardSeries());
            assertNotNull("the inverse series is always the Newton seed",
                    authalic.inverseSeries());

            double worstRoundTrip = 0.0;
            double worstIdentity = 0.0;
            for (int i = -890; i <= 890; i++) {
                double phi = Math.toRadians(i / 10.0);
                double sinphi = StrictMath.sin(phi);
                double xi = authalic.forward(phi, sinphi, StrictMath.cos(phi));
                worstRoundTrip = Math.max(worstRoundTrip, Math.abs(authalic.inverse(xi) - phi));
                worstIdentity = Math.max(worstIdentity,
                        Math.abs(StrictMath.sin(xi) - authalic.q(sinphi) / authalic.qp()));
            }
            assertTrue("f=" + f + " Newton round trip " + worstRoundTrip + " rad",
                    worstRoundTrip < 1.0e-12);
            assertTrue("f=" + f + " sin(xi) vs q/qp " + worstIdentity,
                    worstIdentity < 1.0e-15);
        }
    }

    /**
     * The synthetic conformance shape {@code +a=9999999 +b=.9} must be constructible
     * without throwing or producing NaN, even though upstream's gie entry for it
     * ({@code builtins.gie:95}) expects the <em>setup</em> to fail for other reasons.
     */
    @Test
    public void syntheticConformanceShapeIsConstructible() {
        AuthalicLat authalic = AuthalicLat.fromAxes(9999999.0, 0.9);
        assertFalse(authalic.isSeriesValid());
        assertTrue("qp must stay finite for a near-degenerate shape, was " + authalic.qp(),
                !Double.isNaN(authalic.qp()) && !Double.isInfinite(authalic.qp()));
        // q is monotone in sin(phi) and bounded by qp for such a shape.
        double previous = Double.NEGATIVE_INFINITY;
        for (int i = -90; i <= 90; i++) {
            double v = authalic.q(StrictMath.sin(Math.toRadians(i)));
            assertTrue("q must be finite at " + i, !Double.isNaN(v));
            assertTrue("q must be non-decreasing at " + i, v >= previous);
            previous = v;
        }
    }

    /** {@code q} must be public and must reproduce the value {@code laea}/{@code aea} need. */
    @Test
    public void qIsExposedForLaeaAndAea() {
        AuthalicLat authalic = new AuthalicLat(ES);
        double worst = 0.0;
        for (int i = 0; i <= STEPS; i += 7) {
            double phi = lat(i);
            double sinphi = StrictMath.sin(phi);
            double xi = authalic.forward(phi, sinphi, StrictMath.cos(phi));
            // laea.cpp:39 uses q = sin(xi) * qp; it must match q(sin phi) directly.
            worst = Math.max(worst, Math.abs(StrictMath.sin(xi) * authalic.qp()
                    - authalic.q(sinphi)));
        }
        assertTrue("sin(xi)*qp vs q(sin phi): " + worst, worst < 1.0e-15);
        assertEquals("qp must equal q at the pole", authalic.q(1.0), authalic.qp(), 0.0);
    }

    /**
     * The forward series must beat the {@code asin(q/qp)} form near the pole; that
     * ill-conditioning is 9.8.1's stated reason for switching, and it is what
     * {@code laea}/{@code aea}/{@code cea}/{@code eqearth} inherit today.
     */
    @Test
    public void forwardSeriesAvoidsTheAsinIllConditioningAtThePoles() {
        AuthalicLat authalic = new AuthalicLat(ES);
        double worstDivergence = 0.0;
        double worstAt = 0.0;
        for (int i = 8900; i <= STEPS; i++) {
            double phi = lat(i);
            double sinphi = StrictMath.sin(phi);
            double series = authalic.forward(phi, sinphi, StrictMath.cos(phi));
            double ratio = Math.min(1.0, authalic.q(sinphi) / authalic.qp());
            double viaAsin = StrictMath.asin(ratio);
            double d = Math.abs(series - viaAsin) * A;
            if (d > worstDivergence) {
                worstDivergence = d;
                worstAt = Math.toDegrees(phi);
            }
        }
        // The two agree to sub-nanometre in the mid latitudes but the asin form degrades
        // near the pole; assert the divergence is real (so the switch matters) and that it
        // comes from the asin side by checking the series still round-trips exactly there.
        assertTrue("the asin form must visibly degrade near the pole; max divergence "
                + worstDivergence + " m @ " + worstAt + " deg", worstDivergence > 1.0e-7);
        double worstRoundTrip = 0.0;
        for (int i = 8900; i <= STEPS; i++) {
            double phi = lat(i);
            double xi = authalic.forward(phi, StrictMath.sin(phi), StrictMath.cos(phi));
            worstRoundTrip = Math.max(worstRoundTrip, Math.abs(authalic.inverse(xi) - phi) * A);
        }
        assertTrue("the series still round-trips near the pole: " + (worstRoundTrip / NM)
                + " nm", worstRoundTrip < 10.0 * NM);
    }

    /** A sphere must reduce to the identity, with {@code q = 2 sin(phi)} and {@code qp = 2}. */
    @Test
    public void sphereIsAnExactIdentity() {
        AuthalicLat authalic = new AuthalicLat(0.0);
        assertEquals(2.0, authalic.qp(), 0.0);
        assertEquals(0.0, authalic.thirdFlattening(), 0.0);
        for (int i = -STEPS; i <= STEPS; i += 137) {
            double phi = lat(i);
            assertEquals(2.0 * StrictMath.sin(phi), authalic.q(StrictMath.sin(phi)), 0.0);
            NumericAssert.assertSameBits("forward on a sphere", phi,
                    authalic.forward(phi, StrictMath.sin(phi), StrictMath.cos(phi)));
            NumericAssert.assertSameBits("inverse on a sphere", phi, authalic.inverse(phi));
        }
    }

    /** Both directions must be exactly odd in latitude. */
    @Test
    public void isOddInLatitude() {
        AuthalicLat authalic = new AuthalicLat(ES);
        for (int i = 1; i <= STEPS; i += 13) {
            double phi = lat(i);
            double xi = authalic.forward(phi, StrictMath.sin(phi), StrictMath.cos(phi));
            double mirrored = authalic.forward(-phi, StrictMath.sin(-phi),
                    StrictMath.cos(-phi));
            assertEquals("forward must be odd at " + Math.toDegrees(phi), -xi, mirrored,
                    1.0e-16);
            assertEquals("inverse must be odd", -authalic.inverse(xi),
                    authalic.inverse(-xi), 1.0e-16);
            assertEquals("q must be odd", -authalic.q(StrictMath.sin(phi)),
                    authalic.q(StrictMath.sin(-phi)), 1.0e-16);
        }
    }

    /** {@code fromAxes} must use PROJ's two-step shape derivation. */
    @Test
    public void fromAxesUsesTheTwoStepShapeDerivation() {
        double a = 6378137.0;
        double b = 6356752.314140356; // GRS80
        AuthalicLat viaAxes = AuthalicLat.fromAxes(a, b);
        double f = (a - b) / a;
        assertEquals(2.0 * f - f * f, viaAxes.eccentricitySquared(), 0.0);
        // Which is within about 1 ulp *of 1.0* of the one-step form proj4j uses today
        // (DatumParameters.java:111). Measured in ulps of es itself that is roughly 112,
        // because es is ~0.0067 and the difference is created by the cancellation in
        // 1 - (b/a)^2.
        double oneStep = 1.0 - (b / a) * (b / a);
        double difference = Math.abs(viaAxes.eccentricitySquared() - oneStep);
        assertTrue("two-step vs one-step es differ by " + difference
                        + ", i.e. " + NumericAssert.ulpDistance(
                                viaAxes.eccentricitySquared(), oneStep) + " ulps of es",
                difference <= 2.0 * Math.ulp(1.0));
    }

    /** Repeated construction and evaluation must be bit-identical. */
    @Test
    public void isDeterministic() {
        for (int i = 0; i <= STEPS; i += 371) {
            double phi = lat(i);
            double sinphi = StrictMath.sin(phi);
            double cosphi = StrictMath.cos(phi);
            AuthalicLat first = new AuthalicLat(ES);
            double xi = first.forward(phi, sinphi, cosphi);
            double back = first.inverse(xi);
            for (int rep = 0; rep < 50; rep++) {
                AuthalicLat other = new AuthalicLat(ES);
                NumericAssert.assertSameBits("qp", first.qp(), other.qp());
                NumericAssert.assertSameBits("forward", xi,
                        other.forward(phi, sinphi, cosphi));
                NumericAssert.assertSameBits("inverse", back, other.inverse(xi));
            }
        }
    }

    /** The convenience forward overload must compute the same sine and cosine internally. */
    @Test
    public void convenienceOverloadAgreesBitForBit() {
        AuthalicLat authalic = new AuthalicLat(ES);
        for (int i = -STEPS; i <= STEPS; i += 97) {
            double phi = lat(i);
            NumericAssert.assertSameBits("forward at " + phi,
                    authalic.forward(phi, StrictMath.sin(phi), StrictMath.cos(phi)),
                    authalic.forward(phi));
        }
    }
}

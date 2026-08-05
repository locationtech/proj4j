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
import org.locationtech.proj4j.proj.Projection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * PROJ's angular input contract, {@code 9.8.1:src/fwd.cpp:54-77}, as reproduced in the private
 * {@code Projection.projectRadians(double, double, ProjCoordinate)} funnel that every forward
 * projection passes through.
 *
 * <h2>Everything here projects through a radian identity, on purpose</h2>
 *
 * <p>{@link Radians} is {@code +R=1} with the base class's identity forward, so
 * {@code totalScale} is exactly {@code 1} and the projected output <em>is</em> the radian
 * coordinate the guard handed to the kernel. That makes the clamp and the sign of zero directly
 * observable, and it means no assertion here can be broken — or made to pass — by a change to a
 * real projection's numerics. Testing the guard through, say, Mercator would couple these
 * assertions to whatever that kernel does at the pole.
 *
 * <h2>The numbers are upstream's, and these tests exist so they cannot be "tidied"</h2>
 *
 * <ul>
 * <li><b>{@code PJ_EPS_LAT} is {@code 1e-12} radians</b>, on {@code |phi| - pi/2}: about
 *     {@code 5.73e-11} degrees, or 0.006 &micro;m on the ground. Restating it in degrees
 *     ("allow 90.000000001") is 17 orders of magnitude too generous and misclassifies points a
 *     micro-degree from the pole, which is where real corpus rows sit. Inside the band the
 *     latitude is <em>clamped</em> to exactly &plusmn;&pi;/2; outside it, rejected.</li>
 * <li><b>The only longitude bound is {@code |lambda| > 10} radians</b>, about
 *     &plusmn;573&deg;. A {@code [-180, 180]} rejection is the obvious thing to write and would
 *     be <em>stricter than PROJ</em>, breaking every caller legitimately passing 200&deg;.
 *     {@link #longitudeWellOutsidePlusMinus180IsAccepted()} is that constraint's only witness.
 *     Do not weaken it.</li>
 * </ul>
 */
public class ForwardDomainGuardTest {

    private static final double HALF_PI = Math.PI / 2.0;
    private static final double DTR = Math.PI / 180.0;
    private static final double RTD = 180.0 / Math.PI;

    /**
     * A radian identity that also <b>records what the guard handed it</b>.
     * <p>
     * The recorded values are the assertion target for the clamp and for the sign of zero,
     * because the affine post-multiply that follows would hide both: {@code totalScale * -0.0 +
     * totalFalseNorthing} is {@code -0.0 + 0.0}, which IEEE-754 defines as {@code +0.0}. So the
     * projected output cannot witness a preserved negative zero even when the guard preserved it
     * perfectly. {@link #lam} and {@link #phi} can.
     */
    private static class Capture extends Projection {
        double lam = Double.NaN;
        double phi = Double.NaN;

        @Override protected ProjCoordinate project(double x, double y, ProjCoordinate dst) {
            lam = x;
            phi = y;
            dst.x = x;
            dst.y = y;
            return dst;
        }

        @Override public boolean hasInverse() {
            return true;
        }

        @Override public String toString() {
            return "RadianIdentity";
        }
    }

    private static Capture radians() {
        Capture p = new Capture();
        p.setRadius(1.0);
        p.initialize();
        return p;
    }

    /**
     * {@code dst} is poisoned with {@code 1e300} so that a projection which fails to write both
     * ordinates cannot be mistaken for one that wrote zeros — the stale-read defect pattern.
     */
    private static ProjCoordinate forward(Projection p, double lonDeg, double latDeg) {
        return p.project(new ProjCoordinate(lonDeg, latDeg), new ProjCoordinate(1e300, 1e300));
    }

    /**
     * The radian entry point, for the {@code PJ_EPS_LAT} boundary tests specifically.
     * <p>
     * The bound is on {@code |phi| - pi/2} in <b>radians</b>, and a fixture built as
     * {@code (HALF_PI + 1e-12) * RTD} and then converted back by {@code DTR} does not round-trip
     * to the same overshoot — the two multiplications lose the low bits, which is precisely the
     * scale the bound operates at. Feeding radians in directly removes the round trip and lets
     * the boundary be pinned at the ulp the bound is actually written in.
     */
    private static ProjCoordinate forwardRadians(Projection p, double lam, double phi) {
        return p.projectRadians(new ProjCoordinate(lam, phi), new ProjCoordinate(1e300, 1e300));
    }

    private static ProjectionException expectRejection(double lonDeg, double latDeg) {
        try {
            ProjCoordinate out = forward(radians(), lonDeg, latDeg);
            fail("expected rejection of (" + lonDeg + ", " + latDeg + ") but got " + out);
            return null;
        } catch (ProjectionException e) {
            assertEquals("(" + lonDeg + ", " + latDeg + ") must be an invalid coordinate, not a "
                            + "domain or convergence failure",
                    ErrorCause.INVALID_COORDINATE, e.cause());
            return e;
        }
    }

    private static long bits(double v) {
        return Double.doubleToRawLongBits(v);
    }

    // ------------------------------------------------------------------------ latitude

    /**
     * The pole is inside the domain, and — load bearing for every other assertion here —
     * {@code 90 * DTR} is <em>bit-exactly</em> {@code pi/2}, so clamping a legitimate
     * &plusmn;90&deg; input is a no-op and cannot perturb a real coordinate by even one ulp.
     */
    @Test
    public void poleIsAcceptedAndClampingItIsABitExactNoOp() {
        assertEquals("90 * DTR must be exactly HALF_PI, or the clamp would move real coordinates",
                bits(HALF_PI), bits(90.0 * DTR));
        assertEquals(bits(-HALF_PI), bits(-90.0 * DTR));

        Capture north = radians();
        forward(north, 0.0, 90.0);
        assertEquals(bits(HALF_PI), bits(north.phi));

        Capture south = radians();
        forward(south, 0.0, -90.0);
        assertEquals(bits(-HALF_PI), bits(south.phi));
    }

    /**
     * The predicate is {@code |phi| - pi/2 > EPS_LAT}, strictly greater, so an overshoot of
     * exactly {@code 1e-12} rad is accepted and clamped. Pinned from both sides.
     */
    @Test
    public void latitudeOvershootIsAcceptedUpToEpsLatAndRejectedBeyond() {
        // The largest representable latitude still inside the band. It cannot be written as
        // HALF_PI + 1e-12: near pi/2 the double grid is ulp(pi/2) = 2.22e-16, so the nearest
        // representable overshoot above 1e-12 is 1.0000889e-12 -- which is *outside* the band.
        // The band therefore admits exactly floor(1e-12 / 2.22e-16) = 4503 representable
        // latitudes past each pole, and the boundary has to be walked to rather than written.
        double atBound = HALF_PI + 1e-12;
        while (Math.abs(atBound) - HALF_PI > 1e-12) {
            atBound = Math.nextDown(atBound);
        }
        double overshoot = Math.abs(atBound) - HALF_PI;
        assertTrue("fixture must overshoot at all, was " + overshoot, overshoot > 0.0);
        assertTrue("and stay inside the band, was " + overshoot, overshoot <= 1e-12);
        assertEquals("the boundary sits one ulp below the naive HALF_PI + 1e-12",
                Math.nextDown(HALF_PI + 1e-12), atBound, 0.0);

        Capture inside = radians();
        forwardRadians(inside, 0.2, atBound);
        assertEquals("an overshoot of exactly EPS_LAT is accepted and clamped",
                bits(HALF_PI), bits(inside.phi));

        Capture insideSouth = radians();
        forwardRadians(insideSouth, 0.2, -atBound);
        assertEquals(bits(-HALF_PI), bits(insideSouth.phi));

        // One decimal order past it: rejected.
        double pastBound = HALF_PI + 1e-11;
        assertTrue("fixture must land outside the band",
                Math.abs(pastBound) - HALF_PI > 1e-12);
        for (double phi : new double[] {pastBound, -pastBound}) {
            try {
                fail("phi = " + phi + " rad must be rejected, got "
                        + forwardRadians(radians(), 0.2, phi));
            } catch (ProjectionException e) {
                assertEquals(ErrorCause.INVALID_COORDINATE, e.cause());
            }
        }
    }

    /**
     * The motivating case from the defect register: latitude {@code 90.000001}&deg; overshoots by
     * {@code 1.745e-8} rad — four orders of magnitude past {@code PJ_EPS_LAT} — and used to be
     * answered with a plausible coordinate.
     */
    @Test
    public void latitude90Point000001IsRejected() {
        ProjectionException e = expectRejection(10.0, 90.000001);
        assertTrue(e.getMessage(), e.getMessage().contains("invalid latitude"));
        expectRejection(10.0, -90.000001);
        expectRejection(10.0, 91.0);
        expectRejection(10.0, 180.0);
    }

    /** A degree-space misreading of "1e-12" would accept this; the radian bound rejects it. */
    @Test
    public void theBandIsSubMicrodegreeWideNotMicrodegreeWide() {
        assertEquals(5.729577951308232e-11, 1e-12 * RTD, 1e-24);
        expectRejection(10.0, 90.0 + 1e-9);
    }

    /** Inside the band the latitude is clamped to exactly the pole, not passed through. */
    @Test
    public void insideTheBandTheLatitudeIsClampedToExactlyThePole() {
        double overshot = HALF_PI + 5e-13;
        assertTrue("fixture must overshoot at all", Math.abs(overshot) - HALF_PI > 0.0);
        assertTrue("but stay inside the band", Math.abs(overshot) - HALF_PI <= 1e-12);

        Capture north = radians();
        forwardRadians(north, 0.0, overshot);
        assertEquals("a latitude inside the slop band must be clamped to exactly +pi/2",
                bits(HALF_PI), bits(north.phi));
        assertTrue("and the un-clamped value must not have survived",
                north.phi != overshot);

        Capture south = radians();
        forwardRadians(south, 0.0, -overshot);
        assertEquals(bits(-HALF_PI), bits(south.phi));
    }

    // ----------------------------------------------------------------------- longitude

    /**
     * <b>Do not add a [&minus;180, 180] rejection.</b> PROJ wraps everything inside
     * {@code |lambda| <= 10} rad and rejects only beyond it, so 200&deg; and &minus;190&deg; are
     * ordinary valid input.
     */
    @Test
    public void longitudeWellOutsidePlusMinus180IsAccepted() {
        Projection p = radians();
        for (double lon : new double[] {181.0, 200.0, -190.0, 359.0, 400.0, 572.0, -572.0}) {
            ProjCoordinate out = forward(p, lon, 10.0);
            assertTrue("longitude " + lon + " deg must be accepted, got " + out,
                    out.hasValidXandYOrdinates());
        }
    }

    /** 10 radians is 572.96 degrees, and that is the only longitude bound there is. */
    @Test
    public void longitudeBeyond10RadiansIsRejected() {
        assertEquals(572.9577951308232, 10.0 * RTD, 1e-10);
        ProjectionException e = expectRejection(573.0, 10.0);
        assertTrue(e.getMessage(), e.getMessage().contains("invalid longitude"));
        expectRejection(-573.0, 10.0);
        expectRejection(1e6, 10.0);
    }

    // ------------------------------------------------------- non-finite: the three cases

    /**
     * Case 1 of 3. <b>{@code NaN} in, {@code NaN} out, as a result and not as an error.</b>
     * <p>
     * {@code fwd_prepare} compares against {@code HUGE_VAL} only and every range comparison it
     * makes is false for {@code NaN}, so upstream propagates. The {@code gie} comparator then
     * scores {@code isnan(got) && isnan(expected)} as deviation {@code 0} — a <em>pass</em> — so
     * the corpus contains rows that assert this, and {@code roundtrip} defines all-{@code NaN}-in
     * / all-{@code NaN}-out as residual {@code 0.0}. A guard that rejected {@code NaN} input
     * would trade silent wrong answers for a conformance regression, which is not a trade the
     * fail-closed policy asks for: the caller supplied the undefinedness, so handing it back is
     * an honest answer rather than an invented one.
     */
    @Test
    public void nanInputIsPropagatedNotRejected() {
        Projection p = radians();
        double[][] cases = {
                {Double.NaN, 10.0},
                {10.0, Double.NaN},
                {Double.NaN, Double.NaN},
        };
        for (double[] c : cases) {
            ProjCoordinate out = forward(p, c[0], c[1]);
            assertFalse("NaN input must not be rejected: (" + c[0] + ", " + c[1] + ")",
                    out.hasValidXandYOrdinates());
            assertTrue("NaN in must be NaN out, not the poisoned dst and not the false easting: "
                    + out, Double.isNaN(out.x) || Double.isNaN(out.y));
        }
        // And specifically: the poison must be gone, so the kernel really did run.
        ProjCoordinate both = forward(p, Double.NaN, Double.NaN);
        assertTrue("both ordinates NaN for both-NaN input, got " + both,
                Double.isNaN(both.x) && Double.isNaN(both.y));
    }

    /**
     * Case 2 of 3. &plusmn;{@code Infinity} is an error in PROJ too, so unlike {@code NaN} it is
     * not propagated: {@code +Infinity} trips {@code fwd_prepare}'s {@code HUGE_VAL} test at
     * {@code fwd.cpp:41}, and {@code -Infinity} trips the latitude range test at {@code :60}
     * because {@code inf - pi/2 > 1e-12}.
     */
    @Test
    public void infiniteInputIsRejected() {
        for (double v : new double[] {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            expectRejection(v, 10.0);
            expectRejection(10.0, v);
        }
    }

    /**
     * Case 3 of 3. Finite input that produces a non-finite result is a computation failure —
     * {@link ErrorCause#NUMERICAL_FAILURE}, not {@link ErrorCause#INVALID_COORDINATE} — because
     * nothing about the input excuses it. This is also the assertion that the false easting can
     * never be emitted in place of a failure: {@code x_0} here is 500000, and the exception is
     * raised before the affine runs.
     */
    @Test
    public void finiteInputWithNonFiniteOutputIsANumericalFailure() {
        Projection alwaysNaN = new Projection() {
            @Override protected ProjCoordinate project(double x, double y, ProjCoordinate dst) {
                dst.x = Double.NaN;
                dst.y = Double.NaN;
                return dst;
            }
        };
        alwaysNaN.setRadius(6378137.0);
        alwaysNaN.setFalseEasting(500000.0);
        alwaysNaN.setFalseNorthing(10000000.0);
        alwaysNaN.initialize();

        try {
            ProjCoordinate out = forward(alwaysNaN, 10.0, 10.0);
            fail("a NaN from the kernel on finite input must raise, got " + out);
        } catch (ProjectionException e) {
            assertEquals(ErrorCause.NUMERICAL_FAILURE, e.cause());
            assertTrue(e.getMessage(), e.getMessage().contains("non-finite"));
        }
    }

    /** The same, for an infinite kernel result: {@code Infinity} is not a coordinate either. */
    @Test
    public void finiteInputWithInfiniteOutputIsANumericalFailure() {
        Projection alwaysInf = new Projection() {
            @Override protected ProjCoordinate project(double x, double y, ProjCoordinate dst) {
                dst.x = Double.POSITIVE_INFINITY;
                dst.y = 1.0;
                return dst;
            }
        };
        alwaysInf.setRadius(6378137.0);
        alwaysInf.initialize();

        try {
            fail("must raise, got " + forward(alwaysInf, 10.0, 10.0));
        } catch (ProjectionException e) {
            assertEquals(ErrorCause.NUMERICAL_FAILURE, e.cause());
        }
    }

    // ----------------------------------------------------------------- signed zero

    /**
     * The {@code gie} comparator distinguishes {@code +0.0} from {@code -0.0} at the equator and
     * the antimeridian, so {@code ==} cannot express this assertion — {@code -0.0 == 0.0} is
     * {@code true}. Compared by raw bits for exactly that reason.
     */
    @Test
    public void signedZeroSurvivesTheGuardByRawBits() {
        // The two zeros are distinguishable, and == cannot see it. This is why the assertions
        // below are on bits.
        assertTrue(-0.0 == 0.0);
        assertTrue(bits(-0.0) != bits(0.0));

        Capture neg = radians();
        forward(neg, -0.0, -0.0);
        assertEquals("a -0.0 latitude must not become +0.0 on its way through the guard",
                bits(-0.0), bits(neg.phi));
        assertEquals("nor a -0.0 longitude", bits(-0.0), bits(neg.lam));

        Capture pos = radians();
        forward(pos, 0.0, 0.0);
        assertEquals(bits(0.0), bits(pos.phi));
        assertEquals(bits(0.0), bits(pos.lam));

        // -0.0 must not trip the clamp: |-0.0| - pi/2 is -pi/2, nowhere near the band.
        assertEquals(bits(-0.0), bits(-0.0 * DTR));

        // And the documented caveat: the affine post-multiply *does* normalise -0.0 to +0.0,
        // because -0.0 + 0.0 is +0.0. That is pre-existing behaviour of the scaling step, not of
        // the guard, and it is asserted here so the distinction is recorded rather than
        // rediscovered.
        assertEquals(bits(0.0), bits(1.0 * -0.0 + 0.0));
    }
}

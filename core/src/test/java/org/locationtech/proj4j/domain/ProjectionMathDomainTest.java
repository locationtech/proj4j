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
import org.locationtech.proj4j.ConvergenceFailureException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.ProjectionMath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link ProjectionMath}: the liveness fixes, the checked inverse-trig variants, and
 * {@code inv_mlfn}'s missing convergence check.
 *
 * <h2>Liveness is a security property here, not a performance one</h2>
 *
 * <p>{@code normalizeLongitude}, {@code normalizeLatitude} and {@code normalizeAngle} were
 * unbounded {@code while} loops stepping by &pi; or 2&pi;. For {@code 1e18} radians that is about
 * {@code 1.6e17} iterations — not a slow answer, a <b>hung thread</b>, and reachable from an
 * untrusted coordinate. Every test in the liveness section carries a {@code timeout}, because
 * without one a regression does not fail the build, it stops it.
 */
public class ProjectionMathDomainTest {

    private static final double TWO_PI = Math.PI * 2.0;
    private static final double HALF_PI = Math.PI / 2.0;

    private static long bits(double v) {
        return Double.doubleToRawLongBits(v);
    }

    // ------------------------------------------------------------------------- liveness

    /**
     * The exact input from the defect register: {@code 1e18} rad, ~1.6e17 loop trips. Termination
     * is the assertion — the old code did not terminate in any useful sense.
     *
     * <p><b>Range is deliberately not asserted at {@code 1e18}.</b> The one-{@code floor}
     * reduction is {@code lon - 2pi*floor(lon/2pi)}, and at {@code 1e18} the low bits of
     * {@code 2pi*floor(...)} are simply gone: the result is {@code 124.858}, not something in
     * {@code [-pi, pi]}. <b>PROJ's {@code adjlon} does exactly the same thing</b>, because this is
     * a verbatim port of it, and no conformance row is anywhere near that magnitude. Trading
     * bit-fidelity with upstream for a tidier range guarantee at {@code 1e18} would be the wrong
     * trade — see the port-verbatim rule. {@link #adjlonLosesLowBitsAboveAbout1e17JustAsPROJDoes}
     * pins where the boundary actually is.
     */
    @Test(timeout = 2000)
    public void normalizeLongitudeTerminatesForAbsurdInput() {
        for (double v : new double[] {1e18, -1e18, 1e300, -1e300, 1e15, Double.MAX_VALUE}) {
            double w = ProjectionMath.normalizeLongitude(v);
            assertTrue(v + " -> " + w + " must at least be a number", !Double.isNaN(w));
        }
    }

    /** Up to about {@code 1e17} the reduction is still exact enough to land in range. */
    @Test(timeout = 2000)
    public void normalizeLongitudeIsInRangeForEveryMagnitudeThatMatters() {
        for (double v : new double[] {4.0, 7.0, 100.0, 1e6, 1e9, 1e12, 1e15, 1e17}) {
            for (double s : new double[] {1.0, -1.0}) {
                double w = ProjectionMath.normalizeLongitude(s * v);
                assertTrue((s * v) + " -> " + w, w >= -Math.PI && w <= Math.PI);
            }
        }
    }

    @Test(timeout = 2000)
    public void normalizeLatitudeTerminatesForAbsurdInput() {
        for (double v : new double[] {1e18, -1e18, 1e300, -1e300}) {
            double w = ProjectionMath.normalizeLatitude(v);
            assertTrue(v + " -> " + w, w >= -HALF_PI && w <= HALF_PI);
        }
    }

    @Test(timeout = 2000)
    public void normalizeAngleTerminatesForAbsurdInput() {
        for (double v : new double[] {1e18, -1e18, 1e300, -1e300}) {
            double w = ProjectionMath.normalizeAngle(v);
            assertTrue(v + " -> " + w, w >= 0.0 && w <= TWO_PI);
        }
    }

    // --------------------------------------------------------------------------- adjlon

    /**
     * {@code normalizeLongitude} is now exactly {@code adjlon}
     * ({@code 9.8.1:src/adjlon.cpp:6-20}).
     */
    @Test
    public void normalizeLongitudeIsAdjlon() {
        for (double v : new double[] {0.0, 1.0, -1.0, Math.PI, -Math.PI, 3.5, -3.5, 100.0, 1e18}) {
            assertEquals("normalizeLongitude must delegate to adjlon at " + v,
                    bits(ProjectionMath.adjlon(v)), bits(ProjectionMath.normalizeLongitude(v)));
        }
    }

    /**
     * <b>The {@code 1e-12} overshoot window.</b> Upstream's comment is "let longitude slightly
     * overshoot, to avoid spurious sign switching at the date line", and it is load bearing: the
     * old {@code while} wrapped as soon as {@code lon > pi}, so a longitude a picoradian past the
     * antimeridian flipped to the far side of the world. Real corpus points sit in this band.
     */
    @Test
    public void adjlonLetsTheAntimeridianOvershootRatherThanFlipSign() {
        double justPast = Math.PI + 5e-13;
        assertEquals("inside the window the value is returned untouched",
                bits(justPast), bits(ProjectionMath.adjlon(justPast)));
        assertTrue("and it must NOT have flipped sign", ProjectionMath.adjlon(justPast) > 0.0);

        double justPastNegative = -Math.PI - 5e-13;
        assertEquals(bits(justPastNegative), bits(ProjectionMath.adjlon(justPastNegative)));

        // Outside the window, it wraps.
        double wellPast = Math.PI + 1e-9;
        assertTrue(ProjectionMath.adjlon(wellPast) < 0.0);
        assertEquals(-Math.PI + 1e-9, ProjectionMath.adjlon(wellPast), 1e-15);
    }

    /** &pi; itself, and &plusmn;0.0, pass through bit-for-bit. */
    @Test
    public void adjlonPreservesPiAndSignedZero() {
        assertEquals(bits(Math.PI), bits(ProjectionMath.adjlon(Math.PI)));
        assertEquals(bits(-Math.PI), bits(ProjectionMath.adjlon(-Math.PI)));
        assertEquals(bits(0.0), bits(ProjectionMath.adjlon(0.0)));
        assertEquals("a -0.0 longitude must stay -0.0: the gie comparator distinguishes the two "
                + "at the equator and the antimeridian", bits(-0.0),
                bits(ProjectionMath.adjlon(-0.0)));
    }

    /**
     * <b>{@code adjlon} is {@code NaN}-transparent, where the old {@code normalizeLongitude}
     * threw.</b> {@code Math.abs(NaN) < pi + 1e-12} is {@code false}, so {@code NaN} falls
     * through the arithmetic and comes out {@code NaN} — exactly as the C does. The old throw was
     * stricter than PROJ, and worse, it only fired when {@code lon_0 != 0}, which is why the same
     * defect used to throw for {@code +lon_0=15} and return garbage for {@code +lon_0=0}.
     */
    @Test
    public void adjlonIsNanTransparent() {
        assertTrue(Double.isNaN(ProjectionMath.adjlon(Double.NaN)));
        assertTrue(Double.isNaN(ProjectionMath.normalizeLongitude(Double.NaN)));
    }

    /**
     * The measured precision boundary of the one-{@code floor} reduction, recorded so that a
     * future reader does not mistake it for a defect introduced here. This is upstream's
     * arithmetic, unchanged; the old {@code while} loop would have been in range at {@code 1e18}
     * but only after {@code 1.6e17} iterations, which is not a trade anyone wants.
     */
    @Test(timeout = 2000)
    public void adjlonLosesLowBitsAboveAbout1e17JustAsPROJDoes() {
        assertTrue("1e17 still lands in range",
                Math.abs(ProjectionMath.adjlon(1e17)) <= Math.PI);
        assertEquals("1e18 does not, and this is the value PROJ produces too",
                124.858407346410, ProjectionMath.adjlon(1e18), 1e-9);
    }

    /** An infinity comes out {@code NaN}, also exactly as the C does ({@code inf - inf}). */
    @Test
    public void adjlonMapsInfinityToNanAsTheCDoes() {
        assertTrue(Double.isNaN(ProjectionMath.adjlon(Double.POSITIVE_INFINITY)));
        assertTrue(Double.isNaN(ProjectionMath.adjlon(Double.NEGATIVE_INFINITY)));
    }

    /** Ordinary wrapping is unchanged, so nothing that used to work has moved. */
    @Test
    public void ordinaryWrappingIsUnchanged() {
        assertEquals(0.0, ProjectionMath.normalizeLongitude(TWO_PI), 1e-15);
        assertEquals(-Math.PI + 0.5, ProjectionMath.normalizeLongitude(Math.PI + 0.5), 1e-15);
        assertEquals(1.0, ProjectionMath.normalizeLongitude(1.0), 0.0);
        assertEquals(-1.0, ProjectionMath.normalizeLongitude(-1.0), 0.0);
    }

    // ------------------------------------------------------------- checked inverse trig

    /** {@code ONE_TOL} is upstream's, to the last digit. */
    @Test
    public void oneTolIsUpstreams() {
        assertEquals(1.00000000000001, ProjectionMath.ONE_TOL, 0.0);
    }

    /**
     * Inside the band, clamp — that is what {@code aasin} is for, and a projection kernel reaches
     * {@code |v| >= 1} by rounding constantly.
     */
    @Test
    public void asinCheckedClampsInsideOneTol() {
        assertEquals(HALF_PI, ProjectionMath.asinChecked(1.0), 0.0);
        assertEquals(-HALF_PI, ProjectionMath.asinChecked(-1.0), 0.0);
        assertEquals(HALF_PI, ProjectionMath.asinChecked(1.0 + 1e-15), 0.0);
        assertEquals(0.0, ProjectionMath.acosChecked(1.0), 0.0);
        assertEquals(Math.PI, ProjectionMath.acosChecked(-1.0), 0.0);
    }

    /** Beyond the band, raise. {@code asin(1e9)} is not {@code pi/2}. */
    @Test
    public void asinCheckedRaisesBeyondOneTol() {
        for (double v : new double[] {1.1, -1.1, 1e9, -1e9, 2.0}) {
            try {
                fail("asinChecked(" + v + ") must raise, got " + ProjectionMath.asinChecked(v));
            } catch (ProjectionException e) {
                assertEquals(ErrorCause.COORDINATE_OUT_OF_DOMAIN, e.cause());
            }
            try {
                fail("acosChecked(" + v + ") must raise, got " + ProjectionMath.acosChecked(v));
            } catch (ProjectionException e) {
                assertEquals(ErrorCause.COORDINATE_OUT_OF_DOMAIN, e.cause());
            }
        }
    }

    /**
     * The deprecated originals let {@code NaN} through the clamp entirely, because
     * {@code Math.abs(NaN) > 1.} is {@code false}. Pinned so the difference between the two
     * families is documented by an assertion rather than by a comment.
     */
    @Test
    public void theDeprecatedAsinPassesNanThroughAndTheCheckedOneDoesNot() {
        assertTrue("the old asin passes NaN straight through",
                Double.isNaN(ProjectionMath.asin(Double.NaN)));
        assertTrue(Double.isNaN(ProjectionMath.acos(Double.NaN)));

        try {
            fail("asinChecked(NaN) must raise, got " + ProjectionMath.asinChecked(Double.NaN));
        } catch (ProjectionException e) {
            assertEquals(ErrorCause.NUMERICAL_FAILURE, e.cause());
        }
        try {
            fail("acosChecked(NaN) must raise, got " + ProjectionMath.acosChecked(Double.NaN));
        } catch (ProjectionException e) {
            assertEquals(ErrorCause.NUMERICAL_FAILURE, e.cause());
        }
    }

    /** And the old clamp has no band at all: {@code asin(1e9)} is silently {@code pi/2}. */
    @Test
    public void theDeprecatedAsinHasNoToleranceBand() {
        assertEquals(HALF_PI, ProjectionMath.asin(1e9), 0.0);
        assertEquals(-HALF_PI, ProjectionMath.asin(-1e9), 0.0);
    }

    /** {@code sqrt} returned 0 for a negative radicand; {@code sqrtChecked} raises. */
    @Test
    public void sqrtCheckedRaisesWhereSqrtReturnedZero() {
        assertEquals("the deprecated sqrt still answers 0 -- it is public API",
                0.0, ProjectionMath.sqrt(-1.0), 0.0);
        assertEquals(0.0, ProjectionMath.sqrt(-1e-300), 0.0);

        assertEquals(2.0, ProjectionMath.sqrtChecked(4.0), 0.0);
        assertEquals(0.0, ProjectionMath.sqrtChecked(0.0), 0.0);
        for (double v : new double[] {-1.0, -1e-300, Double.NaN}) {
            try {
                fail("sqrtChecked(" + v + ") must raise, got " + ProjectionMath.sqrtChecked(v));
            } catch (ProjectionException e) {
                assertEquals(ErrorCause.NUMERICAL_FAILURE, e.cause());
            }
        }
    }

    // ------------------------------------------------------------------------ inv_mlfn

    /**
     * {@code inv_mlfn} had a bare {@code return phi;} with no convergence test of any kind — the
     * most widely shared instance of the defect, because {@code tmerc}, {@code Bonne},
     * {@code EquidistantAzimuthal} and {@code Cassini} all inverse-project through it. It now
     * raises.
     *
     * <p>Ordinary arguments still converge, which is the other half of the assertion: a throw
     * that fires on valid input is not a fix.
     */
    @SuppressWarnings("deprecation")
    @Test
    public void invMlfnStillConvergesForEveryRealLatitude() {
        double es = 0.006694379990141316;   // GRS80
        double[] en = ProjectionMath.enfn(es);

        for (double phi : new double[] {0.0, 0.1, 0.5, 1.0, 1.5, -0.7, -1.5707, 1.5707}) {
            double mu = ProjectionMath.mlfn(phi, Math.sin(phi), Math.cos(phi), en);
            assertEquals("inv_mlfn must still round-trip at phi = " + phi,
                    phi, ProjectionMath.inv_mlfn(mu, es, en), 1e-9);
        }
    }

    /**
     * A genuinely non-convergent argument, found by sweep rather than by guesswork —
     * {@code 6.863037736488298e10} on GRS80 exhausts all ten Newton steps. The old code answered
     * it with its last iterate: finite, and wrong by an unbounded amount.
     *
     * <p>Note that a merely <em>absurd</em> argument is not enough to trip it. {@code inv_mlfn} is
     * Newton's method run against {@link ProjectionMath#mlfn}, and {@code mlfn} is monotone, so
     * {@code inv_mlfn(1e12)} converges perfectly well onto {@code 1.0017e12} radians — a real
     * solution of the equation it is actually solving, and a nonsensical latitude. That is worth
     * knowing: this throw catches iteration failure, not domain nonsense, and the domain guard is
     * a separate mechanism.
     */
    @SuppressWarnings("deprecation")
    @Test
    public void invMlfnRaisesWhenTheIterationGenuinelyFails() {
        double es = 0.006694379990141316;   // GRS80
        double[] en = ProjectionMath.enfn(es);

        for (double arg : new double[] {6.863037736488298e10, -6.863037736488298e10}) {
            try {
                double phi = ProjectionMath.inv_mlfn(arg, es, en);
                fail("inv_mlfn(" + arg + ") does not converge and must not answer " + phi);
            } catch (ConvergenceFailureException e) {
                assertEquals(ErrorCause.NUMERICAL_FAILURE, e.cause());
                assertTrue(e.getMessage(), e.getMessage().contains("inv_mlfn"));
            }
        }

        // The other reachable axis: a degenerate eccentricity, which +f=/+rf= can produce.
        double[] wild = ProjectionMath.enfn(0.9);
        try {
            double phi = ProjectionMath.inv_mlfn(1.0, 0.9, wild);
            fail("es=0.9 does not converge at arg=1 and must not answer " + phi);
        } catch (ConvergenceFailureException expected) {
            assertEquals(ErrorCause.NUMERICAL_FAILURE, expected.cause());
        }
    }

    /**
     * <b>{@code inv_mlfn} stays {@code NaN}-transparent.</b> The convergence test is written
     * inverted — {@code !(|t| >= tol)} rather than {@code |t| < tol} — so a {@code NaN} takes the
     * <em>return</em> branch instead of falling through to the throw. That is upstream's own
     * idiom, and it is what keeps NaN-in/NaN-out intact through {@code tmerc}, {@code Bonne},
     * {@code EquidistantAzimuthal} and {@code Cassini}, all four of which inverse-project through
     * here. Writing the test the natural way would have converted every NaN coordinate into a
     * convergence failure.
     */
    @SuppressWarnings("deprecation")
    @Test
    public void invMlfnIsNanTransparentRatherThanNonConvergent() {
        double es = 0.006694379990141316;
        double[] en = ProjectionMath.enfn(es);
        double phi = ProjectionMath.inv_mlfn(Double.NaN, es, en);
        assertTrue("a NaN arc length must come back as NaN, not as a ConvergenceFailure: " + phi,
                Double.isNaN(phi));
    }
}

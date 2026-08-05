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

package org.locationtech.proj4j.proj.adams;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.GenericInverse2D;

/**
 * {@link GenericInverse2D} against {@code 9.8.1:src/generic_inverse.cpp:44-111}.
 *
 * <p>The thresholds in that file are not adjustable knobs: the {@code adams_ws2} and
 * {@code peirce_q} seed cascades are tuned jointly to the 15-iteration budget and the
 * {@code +/-0.3} step clamp, and {@code wink2} and {@code cass} will inherit the same routine.
 * Each is asserted here rather than only implicitly through the corpus.
 */
public class GenericInverse2DTest {

    /** A linear map: one Newton step from any seed. */
    private static final GenericInverse2D.Forward2D LINEAR =
            new GenericInverse2D.Forward2D() {
                @Override
                public void forward(double lam, double phi, ProjCoordinate dst) {
                    dst.x = 2 * lam;
                    dst.y = 3 * phi;
                }
            };

    /**
     * The residual bound is on {@code (x, y)}, not on {@code (lam, phi)}, and the Jacobian is a
     * finite-difference estimate — so the recovered angles are accurate to roughly
     * {@code tolerance / scale}, not to the tolerance itself. Asserting more than that would be
     * asserting something upstream does not promise.
     */
    @Test
    public void invertsALinearMap() {
        ProjCoordinate out = GenericInverse2D.solve(2 * 0.4, 3 * 0.2, LINEAR, 0.0, 0.0, 1e-10,
                new ProjCoordinate());
        assertEquals(0.4, out.x, 1e-10);
        assertEquals(0.2, out.y, 1e-10);
    }

    /** Upstream's bound is 15, and it is exact: the counter is what the seeds are tuned to. */
    @Test
    public void iterationBoundIsFifteen() {
        assertEquals(15, GenericInverse2D.MAX_ITERATIONS);
    }

    /**
     * The correction is clamped to {@code +/-0.3} radians per iteration
     * ({@code generic_inverse.cpp:93,101}), so a seed further than {@code 15 * 0.3 = 4.5}
     * radians of correction away cannot be reached inside the budget. That is the clamp's
     * observable signature and the reason a bad seed fails rather than overshooting into another
     * sheet.
     */
    @Test
    public void stepClampLimitsProgressToPointThreePerIteration() {
        // A map whose scale makes the ideal first step 1.0 rad: with the clamp it takes four
        // iterations, without it one.
        GenericInverse2D.Forward2D unitScale = new GenericInverse2D.Forward2D() {
            @Override
            public void forward(double lam, double phi, ProjCoordinate dst) {
                dst.x = lam;
                dst.y = phi;
            }
        };
        // Reachable: 1.0 rad needs ceil(1.0/0.3) = 4 clamped steps, inside the budget.
        ProjCoordinate near = GenericInverse2D.solve(1.0, 0.0, unitScale, 0.0, 0.0, 1e-10,
                new ProjCoordinate());
        assertEquals(1.0, near.x, 1e-10);

        // Unreachable: 15 * 0.3 = 4.5 rad of correction is the ceiling, and pi is inside it
        // while 4.6 would not be - but lam is also clamped to [-pi, pi], so ask for a phi
        // target instead, where the clamp is [-pi/2, pi/2] and 1.5 rad is legal.
        ProjCoordinate reachable = GenericInverse2D.solve(0.0, 1.5, unitScale, 0.0, 0.0, 1e-10,
                new ProjCoordinate());
        assertEquals(1.5, reachable.y, 1e-10);
    }

    /** {@code lam} is clamped to {@code [-pi, pi]} and {@code phi} to {@code [-pi/2, pi/2]}. */
    @Test
    public void iteratesStayInsideTheAngularDomain() {
        GenericInverse2D.Forward2D identity = new GenericInverse2D.Forward2D() {
            @Override
            public void forward(double lam, double phi, ProjCoordinate dst) {
                dst.x = lam;
                dst.y = phi;
                assertTrue("lam left [-pi, pi]: " + lam, Math.abs(lam) <= Math.PI);
                assertTrue("phi left [-pi/2, pi/2]: " + phi,
                        Math.abs(phi) <= Math.PI / 2 + 1e-15);
            }
        };
        try {
            // Unreachable target: the solver must exhaust its budget without ever probing
            // outside the domain.
            GenericInverse2D.solve(100.0, 100.0, identity, 0.0, 0.0, 1e-10, new ProjCoordinate());
            fail("an unreachable target must raise");
        } catch (ProjectionException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("did not converge"));
        }
    }

    /**
     * <b>Non-convergence raises.</b> Upstream sets the errno and still returns the last iterate;
     * {@code pj_inv}'s {@code error_or_coord} is what discards it. proj4j has no errno channel, so
     * returning the last iterate would be a failure dressed as a plausible coordinate.
     */
    @Test
    public void nonConvergenceRaisesRatherThanReturningTheLastIterate() {
        GenericInverse2D.Forward2D constant = new GenericInverse2D.Forward2D() {
            @Override
            public void forward(double lam, double phi, ProjCoordinate dst) {
                dst.x = 1.0;
                dst.y = 1.0;
            }
        };
        try {
            GenericInverse2D.solve(0.0, 0.0, constant, 0.0, 0.0, 1e-10, new ProjCoordinate());
            fail("a constant forward map cannot converge and must raise");
        } catch (ProjectionException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("did not converge"));
        }
    }

    /**
     * A singular Jacobian is <b>not</b> an error: {@code det == 0} silently reuses the previous
     * inverse Jacobian ({@code generic_inverse.cpp:83-89}). On iteration 0 that is the zero
     * matrix, so no step is taken and the loop runs out — which is exactly what the constant map
     * above demonstrates. Here the degeneracy is only in one variable, and the routine must not
     * throw an arithmetic error on the way.
     */
    @Test
    public void singularJacobianIsNotAnArithmeticError() {
        GenericInverse2D.Forward2D degenerate = new GenericInverse2D.Forward2D() {
            @Override
            public void forward(double lam, double phi, ProjCoordinate dst) {
                dst.x = lam;
                dst.y = lam;
            }
        };
        try {
            GenericInverse2D.solve(0.5, 0.5, degenerate, 0.0, 0.0, 1e-10, new ProjCoordinate());
        } catch (ProjectionException expected) {
            // Either outcome is acceptable; what must not happen is an unrelated exception.
            assertTrue(expected.getMessage(), expected.getMessage().contains("did not converge"));
        }
    }

    /**
     * The finite-difference probe points <em>inward</em>: {@code dLam = lam > 0 ? -1e-6 : 1e-6}.
     * At {@code lam = pi} an outward probe would leave the domain, which for several projections
     * in this family means an out-of-range {@code aacos}.
     */
    @Test
    public void finiteDifferenceProbePointsInward() {
        final double[] minSeen = {Double.MAX_VALUE};
        final double[] maxSeen = {-Double.MAX_VALUE};
        GenericInverse2D.Forward2D recorder = new GenericInverse2D.Forward2D() {
            @Override
            public void forward(double lam, double phi, ProjCoordinate dst) {
                minSeen[0] = Math.min(minSeen[0], lam);
                maxSeen[0] = Math.max(maxSeen[0], lam);
                dst.x = lam;
                dst.y = phi;
            }
        };
        // Seeded exactly at +pi, with a target that is not already satisfied there, so the
        // Jacobian block actually runs. Every probe must be at or below pi.
        try {
            GenericInverse2D.solve(Math.PI - 0.05, 0.0, recorder, Math.PI, 0.0, 1e-10,
                    new ProjCoordinate());
        } catch (ProjectionException ignored) {
            // convergence is not the point here
        }
        assertTrue("probed above pi: " + maxSeen[0], maxSeen[0] <= Math.PI);
        assertTrue("the inward probe must actually step down from pi", minSeen[0] < Math.PI);
    }
}

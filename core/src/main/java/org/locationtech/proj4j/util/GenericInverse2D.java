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

import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;

/**
 * Inverts a forward projection numerically: a port of {@code pj_generic_inverse_2d} from
 * {@code 9.8.1:src/generic_inverse.cpp:44-111}.
 *
 * <p>Two-dimensional Newton-Raphson on the pair
 * <pre>
 *   f_x(lam, phi) = forward(lam, phi).x - x
 *   f_y(lam, phi) = forward(lam, phi).y - y
 * </pre>
 * with the Jacobian estimated by one-sided finite differences of the forward map itself.
 *
 * <p><b>Every threshold here is upstream's and none of them is arbitrary.</b> PROJ's own
 * comment says they "have been verified to work with adams_ws2 and wink2", and the callers
 * pair them with hand-tuned initial guesses; the {@code gie} inverse blocks for
 * {@code peirce_q} carry a 150 mm tolerance precisely because the combination is fragile.
 * In particular:
 *
 * <ul>
 * <li><b>15 iterations, no more.</b> Not "iterate until converged" — the seeds are chosen
 *     against this budget.
 * <li><b>The Jacobian is refreshed on iteration 0 and then only while
 *     {@code |dx| > 1e-6 || |dy| > 1e-6}.</b> Once close, the last inverse Jacobian is
 *     reused; this is upstream's speed optimisation and it changes the iterates.
 * <li><b>The finite-difference step points inward:</b> {@code dLam = lam > 0 ? -1e-6 : 1e-6},
 *     likewise for {@code phi}. The sign flip keeps the probe inside the domain at
 *     {@code lam = +/-pi} and {@code phi = +/-pi/2}, where a step outward would push the
 *     forward map out of range.
 * <li><b>A singular Jacobian is not an error.</b> When {@code det == 0} the previous inverse
 *     Jacobian is silently reused (on iteration 0 that means the zero matrix, i.e. no step).
 * <li><b>Each correction is clamped to {@code [-0.3, 0.3]} radians</b> before it is applied,
 *     then {@code lam} is clamped to {@code [-pi, pi]} and {@code phi} to
 *     {@code [-pi/2, pi/2]}. The step clamp is what stops a bad seed from overshooting into
 *     a different sheet of the projection, and removing it breaks {@code peirce_q}'s
 *     quadrant heuristics.
 * </ul>
 *
 * <p><b>Non-convergence throws.</b> Upstream sets
 * {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN} on the context and then
 * <em>still returns the last iterate</em> ({@code generic_inverse.cpp:108-110}); the caller,
 * {@code pj_inv}'s {@code error_or_coord}, is what discards it. proj4j has no errno channel,
 * so the discarding has to happen here — returning the last iterate would be exactly the
 * "failure expressed as a plausible coordinate" that this port exists to avoid.
 *
 * <p>Not adams-specific: {@code 9.8.1:src/projections/wink2.cpp:51} and
 * {@code 9.8.1:src/projections/cass.cpp:81} call the same routine.
 */
public final class GenericInverse2D {

    /** {@code M_HALFPI}. */
    private static final double HALF_PI = Math.PI / 2.0;

    /** Upstream's iteration bound, {@code generic_inverse.cpp:51}. */
    public static final int MAX_ITERATIONS = 15;

    /**
     * Above this residual the Jacobian is re-estimated every iteration
     * ({@code generic_inverse.cpp:60}).
     */
    private static final double JACOBIAN_REFRESH = 1e-6;

    /** Finite-difference step magnitude ({@code generic_inverse.cpp:65,72}). */
    private static final double FD_STEP = 1e-6;

    /** Correction clamp, in radians ({@code generic_inverse.cpp:93,101}). */
    private static final double MAX_STEP = 0.3;

    /**
     * The raw forward map of a projection: radians in, unscaled projected units out, with
     * no false origin, no {@code totalScale} and no unit conversion.
     *
     * <p>Deliberately not {@code Projection} itself. {@code Projection.project(double,
     * double, ProjCoordinate)} is {@code protected}, and widening it to satisfy a utility
     * class would change proj4j's public surface for every projection at once.
     */
    public interface Forward2D {

        /**
         * @param lam longitude relative to the central meridian, in radians
         * @param phi latitude, in radians
         * @param dst receives the projected coordinate; must be written, not returned
         */
        void forward(double lam, double phi, ProjCoordinate dst);
    }

    private GenericInverse2D() {
    }

    /**
     * Solves {@code forward(lam, phi) == (x, y)}.
     *
     * @param x                 target easting, in the forward map's raw units
     * @param y                 target northing, in the forward map's raw units
     * @param forward           the map to invert
     * @param lamInitial        seed longitude, radians
     * @param phiInitial        seed latitude, radians
     * @param deltaXYTolerance  convergence bound on both residuals; every caller in the
     *                          adams family passes {@code 1e-10}
     * @param dst               receives {@code (lam, phi)} in radians, as {@code (x, y)}
     * @return {@code dst}
     * @throws ProjectionException if 15 iterations do not converge
     */
    public static ProjCoordinate solve(double x, double y, Forward2D forward,
            double lamInitial, double phiInitial, double deltaXYTolerance,
            ProjCoordinate dst) {
        double lam = lamInitial;
        double phi = phiInitial;
        double derivLamX = 0;
        double derivLamY = 0;
        double derivPhiX = 0;
        double derivPhiY = 0;

        // One scratch coordinate for the iterate and one for the finite-difference probe.
        // Allocating inside the loop would cost 45 short-lived objects per inverse call on
        // a path that peirce_q's roundtrips hit 47 times per gie block.
        final ProjCoordinate approx = new ProjCoordinate();
        final ProjCoordinate probe = new ProjCoordinate();

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            forward.forward(lam, phi, approx);
            final double deltaX = approx.x - x;
            final double deltaY = approx.y - y;
            if (Math.abs(deltaX) < deltaXYTolerance && Math.abs(deltaY) < deltaXYTolerance) {
                dst.x = lam;
                dst.y = phi;
                return dst;
            }

            if (i == 0 || Math.abs(deltaX) > JACOBIAN_REFRESH
                    || Math.abs(deltaY) > JACOBIAN_REFRESH) {
                final double dLam = lam > 0 ? -FD_STEP : FD_STEP;
                forward.forward(lam + dLam, phi, probe);
                final double derivXLam = (probe.x - approx.x) / dLam;
                final double derivYLam = (probe.y - approx.y) / dLam;

                final double dPhi = phi > 0 ? -FD_STEP : FD_STEP;
                forward.forward(lam, phi + dPhi, probe);
                final double derivXPhi = (probe.x - approx.x) / dPhi;
                final double derivYPhi = (probe.y - approx.y) / dPhi;

                final double det = derivXLam * derivYPhi - derivXPhi * derivYLam;
                if (det != 0) {
                    derivLamX = derivYPhi / det;
                    derivLamY = -derivXPhi / det;
                    derivPhiX = -derivYLam / det;
                    derivPhiY = derivXLam / det;
                }
                // det == 0: reuse the previous inverse Jacobian, exactly as upstream does.
            }

            final double deltaLam = clamp(deltaX * derivLamX + deltaY * derivLamY);
            lam -= deltaLam;
            if (lam < -Math.PI) {
                lam = -Math.PI;
            } else if (lam > Math.PI) {
                lam = Math.PI;
            }

            final double deltaPhi = clamp(deltaX * derivPhiX + deltaY * derivPhiY);
            phi -= deltaPhi;
            if (phi < -HALF_PI) {
                phi = -HALF_PI;
            } else if (phi > HALF_PI) {
                phi = HALF_PI;
            }
        }

        throw new ProjectionException(
                "generic 2D inverse did not converge in " + MAX_ITERATIONS
                        + " iterations for (" + x + ", " + y + ")");
    }

    /**
     * {@code std::max(std::min(v, 0.3), -0.3)}. Written with the same nesting as upstream:
     * for {@code v = NaN} both {@code Math.min} and {@code Math.max} propagate the NaN, so a
     * NaN step stays NaN, {@code lam} becomes NaN, the residual test stays false, and the
     * loop runs out and throws — which is the correct outcome and the one C reaches by a
     * different route.
     */
    private static double clamp(double v) {
        return Math.max(Math.min(v, MAX_STEP), -MAX_STEP);
    }
}

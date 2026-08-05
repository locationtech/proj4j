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

package org.locationtech.proj4j.proj;

import org.locationtech.proj4j.ConvergenceFailureException;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * Shared machinery for the four Flex&nbsp;Projector-derived pseudo-cylindricals that PROJ
 * 9.8.1 implements as four separate files with one algorithm:
 * {@code natearth.cpp}, {@code natearth2.cpp}, {@code patterson.cpp} and
 * {@code comill.cpp}.
 *
 * <p>All four were designed interactively in Flex Projector by Tom Patterson and then
 * fitted with odd polynomials by Bojan Savric and Bernhard Jenny. The shape is therefore
 * <em>defined</em> by its polynomial — there is no closed-form geometry behind it to
 * appeal to — which is why the coefficients must be transcribed exactly and the evaluation
 * order preserved (see below).
 *
 * <h2>The common form</h2>
 *
 * <pre>
 *   forward:   x = lam * E(phi)          y = N(phi)
 *   inverse:   clamp y into [-MAX_Y, MAX_Y]
 *              solve N(yc) = y for yc by Newton-Raphson, N'(.) supplied
 *              phi = yc                  lam = x / E(yc)
 * </pre>
 *
 * <p>{@code N} is odd in {@code phi} and {@code E} is even, so all four maps are symmetric
 * about both axes. {@code E} is identically 1 for {@code patterson} and {@code comill},
 * whose easting is simply {@code lam} — they are "compact Miller"-style maps that only
 * reshape the parallels.
 *
 * <h2>Why the derivative is a separate polynomial and not {@code N'} recomputed</h2>
 *
 * <p>Upstream hard-codes the derivative's coefficients as small integer multiples of the
 * northing's ({@code C1 = 3*B1}, {@code C2 = 7*B2}, and so on) rather than differentiating
 * at run time, and the multipliers differ per projection because the powers of
 * {@code phi} that each polynomial actually carries differ. {@code natearth}'s northing is
 * {@code phi*(B0 + phi^2*(B1 + phi^4*(B2 + B3 phi^2 + B4 phi^4)))}, i.e. terms in
 * {@code phi^1, phi^3, phi^7, phi^9, phi^11} — hence {@code 1, 3, 7, 9, 11}. Getting these
 * wrong does not break convergence, it just slows it, so a transcription error here hides
 * rather than announcing itself. {@link #northingDerivative(double)} is therefore
 * specified per subclass and unit-tested against a finite difference.
 *
 * <h2>Fail-closed, where upstream returns a coordinate anyway</h2>
 *
 * <p>On Newton exhaustion all four upstream inverses call
 * {@code proj_context_errno_set(P-&gt;ctx, PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN)}
 * and then <b>carry on and return {@code lp} anyway</b>, with {@code phi} set to the
 * unconverged {@code yc}. A caller that does not check {@code proj_errno} receives a
 * plausible latitude. Proj4J throws instead — non-negotiable 3. No corpus row reaches this
 * path (100 iterations of Newton on a monotone odd polynomial converges in a handful of
 * steps everywhere inside the clamp), so this cannot cost an assertion, and it removes a
 * silent-wrong-answer path.
 *
 * @see NaturalEarthProjection
 * @see NaturalEarth2Projection
 * @see PattersonProjection
 * @see CompactMillerProjection
 */
abstract class PolynomialPseudoCylindricalProjection extends CylindricalProjection {

    private static final long serialVersionUID = 3588132591005670796L;

    /** Upstream's {@code EPS}/{@code EPS11}: {@code 1e-11}, identical in all four files. */
    protected static final double EPS = 1e-11;

    /**
     * Upstream's {@code MAX_ITER}, identical in all four files. Its own comment is "Not
     * sure at all of the appropriate number for MAX_ITER..." — it is a safety net, not a
     * tuned budget.
     */
    protected static final int MAX_ITER = 100;

    /** {@code N(phi)}: the northing, before the {@code a} scaling. Odd in {@code phi}. */
    protected abstract double northing(double phi);

    /** {@code N'(phi)}, as upstream's separately-tabulated {@code C} coefficients. */
    protected abstract double northingDerivative(double phi);

    /**
     * {@code E(phi)}: the factor multiplying {@code lam} in the easting. Even in
     * {@code phi}, and never zero anywhere inside {@code [-MAX_Y, MAX_Y]}.
     */
    protected abstract double eastingScale(double phi);

    /** The northing clamp applied by the inverse before solving. */
    protected abstract double maxY();

    /**
     * Whether the Newton seed is captured <em>before</em> the northing is clamped.
     *
     * <p>{@code true} only for {@code patterson}, and it is an upstream quirk rather than a
     * design choice: {@code patterson.cpp:80} assigns {@code yc = xy.y} and only then
     * {@code :83-87} clamps {@code xy.y}, so for a northing beyond {@code MAX_Y} the seed
     * and the target disagree. {@code natearth}, {@code natearth2} and {@code comill} all
     * clamp first ({@code natearth.cpp:63-70}). The two orders give the same answer
     * whenever {@code |y| &lt;= MAX_Y}, which is every corpus row; outside it they differ
     * only in how many Newton steps are spent walking back. Reproduced rather than
     * normalised, because it is observable and costs nothing.
     */
    protected boolean seedBeforeClamp() {
        return false;
    }

    protected ProjCoordinate project(double lam, double phi, ProjCoordinate dst) {
        dst.x = lam * eastingScale(phi);
        dst.y = northing(phi);
        return dst;
    }

    /**
     * The shared Newton inverse. Transcribed from {@code natearth.cpp:56-93}, including the
     * post-decrement convergence test — the correction is applied <em>before</em> it is
     * tested, so a step that lands exactly on the answer still counts as converged.
     *
     * @throws ConvergenceFailureException where upstream sets
     *         {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN} and returns a
     *         coordinate regardless
     */
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate dst) {
        final double limit = maxY();
        double yc = seedBeforeClamp() ? y : 0.0;
        double target = y;
        if (target > limit) {
            target = limit;
        } else if (target < -limit) {
            target = -limit;
        }
        if (!seedBeforeClamp()) {
            yc = target;
        }

        int i = MAX_ITER;
        double correction = Double.NaN;
        for (; i > 0; --i) {
            correction = (northing(yc) - target) / northingDerivative(yc);
            yc -= correction;
            if (Math.abs(correction) < EPS) {
                break;
            }
        }
        if (i == 0) {
            throw new ConvergenceFailureException(this,
                    "inverse northing iteration did not converge to " + EPS + " within "
                            + MAX_ITER + " iterations for y = " + y + " (last correction "
                            + correction + ")");
        }

        dst.y = yc;
        dst.x = x / eastingScale(yc);
        return dst;
    }

    public boolean hasInverse() {
        return true;
    }
}

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

import java.util.Objects;

import org.locationtech.proj4j.ConvergenceFailureException;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;

/**
 * The spherical general sinusoidal series, shared by {@code gn_sinu} and {@code mbtfps} in
 * {@code 9.8.1:src/projections/gn_sinu.cpp}. That file also holds {@code sinu} and
 * {@code eck6}, which proj4j already implements as separate classes.
 *
 * <p>Two shape parameters {@code m} and {@code n} define the whole family:
 *
 * <pre>
 *   setup:   C_y = sqrt((m + 1) / n)
 *            C_x = C_y / (m + 1)
 *   forward: solve  m theta + sin(theta) = n sin(phi)   for theta   (8 steps, 1e-7)
 *            x = C_x lam (m + cos(theta))
 *            y = C_y theta
 *   inverse: theta = y / C_y
 *            phi = asin((m theta + sin(theta)) / n)
 *            lam = x / (C_x (m + cos(theta)))
 * </pre>
 *
 * <p>with a shortcut when {@code m == 0}: no iteration is needed, since
 * {@code sin(theta) = n sin(phi)} solves directly, and when additionally {@code n == 1} the
 * map degenerates to {@code theta = phi} and even the {@code asin} is skipped. Both shortcuts
 * are reproduced because they are not merely optimisations — {@code aasin} would clamp and
 * possibly raise where the direct assignment cannot.
 *
 * <table>
 * <caption>the two members ported here</caption>
 * <tr><th>{@code +proj=}</th><th>{@code m}</th><th>{@code n}</th></tr>
 * <tr><td>{@code gn_sinu}</td><td>{@code +m}</td><td>{@code +n}</td></tr>
 * <tr><td>{@code mbtfps}</td><td>0.5</td><td>1.785398163397448309615660845</td></tr>
 * </table>
 *
 * <p>For reference, the two members proj4j already has are {@code sinu} at
 * {@code (m, n) = (0, 1)} — the degenerate case — and {@code eck6} at
 * {@code (1, 2.570796326794896619231321691)}. Both of those constants are
 * {@code (pi + 2)/2} and {@code (pi + 4)/4} written out to 28 digits.
 *
 * <h2>{@code gn_sinu}'s {@code +m} and {@code +n} are required and undocumented</h2>
 *
 * <p>{@code gn_sinu.cpp:178-198} rejects a missing {@code +n} with
 * {@code PROJ_ERR_INVALID_OP_MISSING_ARG}, then a missing {@code +m}, then {@code n <= 0} and
 * {@code m < 0} with {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE}. The prose documentation
 * does not mention that either is mandatory. Both validations are reproduced in
 * {@link #initialize()}.
 *
 * <p>{@code Proj4Keyword} now defines both {@code m} and {@code n}, and {@code Proj4Parser}
 * dispatches them — on {@link GeneralSinusoidalProjection}, the concrete {@code gn_sinu} class,
 * and deliberately <em>not</em> on this base: {@code sinu}, {@code eck6} and {@code mbtfps} share
 * this kernel but hard-code their own {@code m} and {@code n} and read neither key upstream, so
 * they must not receive them. Both keys are read with {@code pj_param}'s {@code 'd'} sigil, so they
 * are plain numbers rather than angles.
 */
abstract class GeneralSinusoidalSeriesProjection extends PseudoCylindricalProjection {

    private static final long serialVersionUID = -1825839845450890734L;

    private static final int MAX_ITER = 8;
    private static final double LOOP_TOL = 1e-7;
    private static final double HALF_PI = Math.PI / 2.0;
    private static final double ONE_TOL = 1.00000000000001;

    private double m;
    private double n;
    private double cx;
    private double cy;

    /**
     * <b>Deliberately does not call {@link #initialize()}.</b> {@code gn_sinu} has no
     * defaults for {@code m} and {@code n} — upstream <em>requires</em> both — so a
     * no-argument construction necessarily starts invalid, and validating here would make
     * {@code Registry.getProjection("gn_sinu")} throw before the caller ever had a chance to
     * supply them. {@code Proj4Parser.parseProjection} calls {@code initialize()} after the
     * setters ({@code Proj4Parser.java:283}), which is where the validation belongs and
     * where upstream performs it too.
     */
    protected GeneralSinusoidalSeriesProjection(double m, double n) {
        this.m = m;
        this.n = n;
        es = 0.0;
    }

    /**
     * Sets {@code +m}. This is what {@code Proj4Parser} calls for a {@code gn_sinu} definition; see
     * the class comment for why only the concrete subclass receives it. Call before
     * {@link #initialize()}.
     */
    public void setM(double m) {
        this.m = m;
    }

    /** Sets {@code +n}. See {@link #setM(double)}. */
    public void setN(double n) {
        this.n = n;
    }

    public double getM() {
        return m;
    }

    public double getN() {
        return n;
    }

    /** {@code pj_gn_sinu_setup} plus {@code gn_sinu}'s validations ({@code gn_sinu.cpp:108-198}). */
    @Override
    public void initialize() {
        super.initialize();
        if (n <= 0.0) {
            throw new InvalidValueException(
                    "Invalid value for n: it should be > 0, but was " + n);
        }
        if (m < 0.0) {
            throw new InvalidValueException(
                    "Invalid value for m: it should be >= 0, but was " + m);
        }
        es = 0.0;
        cy = Math.sqrt((m + 1.0) / n);
        cx = cy / (m + 1.0);
    }

    /**
     * {@code gn_sinu_s_forward}, {@code gn_sinu.cpp:55-82}.
     *
     * @throws ConvergenceFailureException where upstream sets
     *         {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN} and returns
     *         {@code (0, 0)}
     */
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate dst) {
        double theta;
        if (m == 0.0) {
            theta = n != 1.0 ? aasin(n * StrictMath.sin(phi)) : phi;
        } else {
            final double k = n * StrictMath.sin(phi);
            theta = phi;
            int i = MAX_ITER;
            double v = Double.NaN;
            for (; i > 0; --i) {
                v = (m * theta + StrictMath.sin(theta) - k) / (m + StrictMath.cos(theta));
                theta -= v;
                if (Math.abs(v) < LOOP_TOL) {
                    break;
                }
            }
            if (i == 0) {
                // gn_sinu.cpp:73-76 sets the errno and returns the zero-initialised xy,
                // i.e. the map origin -- a plausible coordinate for a failure.
                throw new ConvergenceFailureException(this,
                        "forward auxiliary-angle iteration did not converge to " + LOOP_TOL
                                + " within " + MAX_ITER + " iterations for latitude " + phi
                                + " rad (last correction " + v + ")");
            }
        }
        dst.x = cx * lam * (m + StrictMath.cos(theta));
        dst.y = cy * theta;
        return dst;
    }

    /**
     * {@code gn_sinu_s_inverse}, {@code gn_sinu.cpp:84-96}.
     *
     * <p>Note the longitude divides by {@code m + cos(theta)} where {@code theta} is the
     * <em>scaled</em> northing {@code y / C_y} — upstream reuses the mutated {@code xy.y} for
     * both, so the same value serves as the auxiliary angle in the latitude and in the
     * longitude denominator.
     */
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate dst) {
        final double theta = y / cy;
        if (m != 0.0) {
            dst.y = aasin((m * theta + StrictMath.sin(theta)) / n);
        } else {
            dst.y = n != 1.0 ? aasin(StrictMath.sin(theta) / n) : theta;
        }
        dst.x = x / (cx * (m + StrictMath.cos(theta)));
        return dst;
    }

    /**
     * {@code aasin} from {@code 9.8.1:src/aasincos.cpp:16-25}. Not
     * {@code ProjectionMath.asin}, which clamps silently at any magnitude and therefore
     * cannot report the domain failure upstream reports.
     */
    private double aasin(double v) {
        final double av = Math.abs(v);
        if (av >= 1.0) {
            if (av > ONE_TOL) {
                throw new ProjectionException(this,
                        "asin argument " + v + " is outside [-1, 1]");
            }
            return v < 0 ? -HALF_PI : HALF_PI;
        }
        return StrictMath.asin(v);
    }

    public boolean hasInverse() {
        return true;
    }

    @Override
    public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (that != null && getClass() == that.getClass()) {
            GeneralSinusoidalSeriesProjection p = (GeneralSinusoidalSeriesProjection) that;
            return m == p.m && n == p.n && super.equals(that);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(m, n, super.hashCode());
    }
}

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

import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;

/**
 * Urmaev V ({@code +proj=urm5}), a port of {@code 9.8.1:src/projections/urm5.cpp}.
 * Forward only.
 *
 * <pre>
 *   setup:   m   = cos(alpha) / sqrt(1 - (n sin(alpha))^2)
 *            rmn = 1 / (m n)
 *            q3  = q / 3
 *   forward: t = theta = aasin(n sin(phi))
 *            x = m lam cos(theta)
 *            y = theta (1 + theta^2 q3) rmn
 * </pre>
 *
 * <h2>Three parameters, two of them undocumented</h2>
 *
 * <p>The doc string is {@code "\n\tPCyl, Sph, no inv\n\tn= q= alpha="}.
 *
 * <ul>
 * <li><b>{@code +n} is required</b> and must lie in {@code (0, 1]}
 *     ({@code urm5.cpp:37-47}, two distinct errors: missing, then out of range).
 *     {@code Proj4Keyword} defines {@code n} and {@code Proj4Parser} dispatches it to
 *     {@link #setN(double)} on this concrete class.
 * <li><b>{@code +q} is optional</b>, defaulting to 0, and is dispatched to
 *     {@link #setQ(double)}. With {@code q = 0} the northing is linear in {@code theta}.
 * <li><b>{@code +alpha} is optional</b>, defaulting to 0, and <em>is</em> already dispatched
 *     by the parser into {@link Projection#alpha}. Note that {@code Projection} initialises
 *     {@code alpha} to {@code Double.NaN} rather than 0, so this class must treat NaN as
 *     "absent" and substitute zero — otherwise {@code cos(NaN)} poisons {@code m} and every
 *     output becomes NaN, which the forward contract then reports as a numerical failure. The
 *     corpus exercises both: {@code +n=0.5} with no {@code alpha}, and
 *     {@code +n=1 +alpha=90}.
 * </ul>
 *
 * <h2>{@code +n=1 +alpha=90} is the guarded case, and the guard is on the denominator</h2>
 *
 * <p>{@code urm5.cpp:51-58} computes {@code t = n sin(alpha)} and rejects
 * {@code sqrt(1 - t*t) == 0} — an exact zero comparison, not a tolerance. At {@code n = 1},
 * {@code alpha = 90} degrees, {@code sin(alpha)} is exactly 1 in double precision only if the
 * radian conversion of 90 degrees lands exactly on {@code pi/2}, which it does not:
 * {@code sin(pi/2 as a double)} is {@code 1.0} exactly, so {@code t = 1.0} and the
 * denominator <em>is</em> exactly zero. So the corpus's second operation sits precisely on
 * this rejection — which is why that block's rows are {@code expect failure} at setup rather
 * than coordinates.
 *
 * <p>Reported as {@link InvalidValueException}, matching upstream's
 * {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE}, which is a setup error and not a coordinate
 * error.
 */
public class Urmaev5Projection extends PseudoCylindricalProjection {

    private static final long serialVersionUID = 1417823052394470576L;

    private static final double HALF_PI = Math.PI / 2.0;
    private static final double ONE_TOL = 1.00000000000001;

    private double n = Double.NaN;
    private double q = 0.0;

    private double m;
    private double rmn;
    private double q3;

    /**
     * Sets {@code +n}, required, in {@code (0, 1]}. Present because {@code parser/**} does not
     * dispatch {@code n}.
     */
    public void setN(double n) {
        this.n = n;
    }

    /** Sets {@code +q}, optional, default 0. Not dispatched by {@code parser/**} either. */
    public void setQ(double q) {
        this.q = q;
    }

    public double getN() {
        return n;
    }

    public double getQ() {
        return q;
    }

    /** {@code PJ_PROJECTION(urm5)}, {@code urm5.cpp:29-67}. */
    @Override
    public void initialize() {
        super.initialize();
        if (Double.isNaN(n)) {
            throw new InvalidValueException(
                    "Missing parameter n: +proj=urm5 requires +n in (0, 1]");
        }
        if (n <= 0.0 || n > 1.0) {
            throw new InvalidValueException(
                    "Invalid value for n: it should be in ]0,1] range, but was " + n);
        }
        q3 = q / 3.0;
        // Projection.alpha defaults to NaN, not 0; urm5's +alpha defaults to 0.
        final double alphaRad = Double.isNaN(alpha) ? 0.0 : alpha;
        final double t = n * StrictMath.sin(alphaRad);
        final double denom = Math.sqrt(1.0 - t * t);
        if (denom == 0.0) {
            throw new InvalidValueException(
                    "Invalid value for n / alpha: n * sin(|alpha|) should be < 1, but n = "
                            + n + " and alpha = " + Math.toDegrees(alphaRad)
                            + " deg give n * sin(alpha) = " + t);
        }
        m = StrictMath.cos(alphaRad) / denom;
        rmn = 1.0 / (m * n);
        es = 0.0;
    }

    /** {@code urm5_s_forward}, {@code urm5.cpp:17-27}. */
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate dst) {
        final double theta = aasin(n * StrictMath.sin(phi));
        dst.x = m * lam * StrictMath.cos(theta);
        dst.y = theta * (1.0 + theta * theta * q3) * rmn;
        return dst;
    }

    /** {@code aasin}, {@code 9.8.1:src/aasincos.cpp:16-25}. */
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

    /** {@code false}: {@code urm5.cpp:63} assigns {@code P->inv = nullptr} explicitly. */
    public boolean hasInverse() {
        return false;
    }

    public String toString() {
        return "Urmaev V";
    }
}

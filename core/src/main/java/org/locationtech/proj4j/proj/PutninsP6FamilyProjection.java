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

import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;

/**
 * Putnins P6 and P6&prime; ({@code +proj=putp6}, {@code +proj=putp6p}), a port of
 * {@code 9.8.1:src/projections/putp6.cpp}.
 *
 * <p>Unlike the P3 pair these are not closed form: the forward must solve
 *
 * <pre>
 *   (A - r) t - ln(t + r) = B sin(phi),      r = sqrt(1 + t^2)
 * </pre>
 *
 * <p>for the auxiliary {@code t}, by ten Newton steps at {@code 1e-10}, seeded with
 * {@code t = 1.10265779 * phi}. Then
 *
 * <pre>
 *   x = C_x * lam * (D - sqrt(1 + t^2))
 *   y = C_y * t
 * </pre>
 *
 * <p>The inverse <b>is</b> closed form, because {@code y} determines {@code t} directly — it
 * simply evaluates the same expression forwards and takes an {@code asin}.
 *
 * <table>
 * <caption>{@code putp6.cpp:67-105}</caption>
 * <tr><th>{@code +proj=}</th><th>{@code C_x}</th><th>{@code C_y}</th><th>{@code A}</th>
 *     <th>{@code B}</th><th>{@code D}</th></tr>
 * <tr><td>{@code putp6}</td><td>1.01346</td><td>0.91910</td><td>4</td>
 *     <td>2.1471437182129378784</td><td>2</td></tr>
 * <tr><td>{@code putp6p}</td><td>0.44329</td><td>0.80404</td><td>6</td>
 *     <td>5.61125</td><td>3</td></tr>
 * </table>
 *
 * <h2>The non-convergence branch is a <em>success</em> path, not an error</h2>
 *
 * <p>This is the one thing here that is easy to get wrong. {@code putp6.cpp:38-44}, on
 * exhausting the ten steps, does <b>not</b> set an errno: it snaps {@code t} to
 * {@code +/-CON_POLE} where {@code CON_POLE = 1.732050807568877} (that is
 * {@code sqrt(3)}, spelled out) and hard-sets {@code sqrt(1 + t^2) = 2}, then carries on to
 * produce a perfectly good pole coordinate. Upstream's own comment says the case "is rarely
 * reached as from experimenting, i seems to be &gt;= 6", and that the {@code = 2} is written
 * explicitly only to quiet cppcheck — it is what {@code sqrt(1 + 3)} would give anyway.
 *
 * <p>So this must <b>not</b> be turned into a thrown exception: doing so would convert a
 * documented, deliberate pole-clamp into a failure and lose rows. The fail-closed rule is
 * about failures dressed up as coordinates, and this is the opposite — a coordinate that
 * upstream means.
 *
 * <p>Note the constant is spelled {@code sqrt(3)} but only for {@code putp6}'s {@code D = 2}
 * does {@code sqrt(1 + 3) = 2} hold; for {@code putp6p}, {@code D = 3} and the hard-coded
 * {@code 2} still applies, which makes the pole easting {@code C_x * lam * 1} rather than
 * {@code C_x * lam * (3 - 2)} — the same thing. The two agree, so the shared constant is
 * correct for both, but it is a coincidence of the parameter sets rather than an identity.
 */
abstract class PutninsP6FamilyProjection extends PseudoCylindricalProjection {

    private static final long serialVersionUID = -5672610553089134697L;

    private static final double EPS = 1e-10;
    private static final int NITER = 10;

    /** {@code putp6.cpp:20} — {@code sqrt(3)}, written out. */
    private static final double CON_POLE = 1.732050807568877;

    private final double cx;
    private final double cy;
    private final double aa;
    private final double bb;
    private final double dd;

    protected PutninsP6FamilyProjection(double cx, double cy, double aa, double bb,
            double dd) {
        this.cx = cx;
        this.cy = cy;
        this.aa = aa;
        this.bb = bb;
        this.dd = dd;
        es = 0.0;
        initialize();
    }

    /** {@code putp6_s_forward}, {@code putp6.cpp:22-52}. */
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate dst) {
        final double p = bb * StrictMath.sin(phi);
        double t = phi * 1.10265779;

        int i = NITER;
        for (; i > 0; --i) {
            final double r = Math.sqrt(1.0 + t * t);
            final double v = ((aa - r) * t - StrictMath.log(t + r) - p) / (aa - 2.0 * r);
            t -= v;
            if (Math.abs(v) < EPS) {
                break;
            }
        }

        final double sqrt1PlusT2;
        if (i == 0) {
            // putp6.cpp:38-44. Deliberately NOT an error: snap to the pole.
            t = p < 0.0 ? -CON_POLE : CON_POLE;
            sqrt1PlusT2 = 2.0;
        } else {
            sqrt1PlusT2 = Math.sqrt(1.0 + t * t);
        }

        dst.x = cx * lam * (dd - sqrt1PlusT2);
        dst.y = cy * t;
        return dst;
    }

    /**
     * {@code putp6_s_inverse}, {@code putp6.cpp:54-65}.
     *
     * <p>{@code aasin} rather than a bare {@code asin}: upstream calls
     * {@code aasin(P-&gt;ctx, ...)}, which raises
     * {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN} for an argument more than
     * {@code 1.00000000000001} outside {@code [-1, 1]} and clamps within that band. A bare
     * {@code Math.asin} would answer {@code NaN}, and
     * {@code ProjectionMath.asin} would clamp silently with no error signal at any
     * magnitude — so neither is a substitute. Reachable: a northing well past the pole line
     * drives the argument above 1.
     */
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate dst) {
        final double t = y / cy;
        final double r = Math.sqrt(1.0 + t * t);
        dst.x = x / (cx * (dd - r));
        dst.y = aasin(((aa - r) * t - StrictMath.log(t + r)) / bb);
        return dst;
    }

    /**
     * {@code aasin} from {@code 9.8.1:src/aasincos.cpp:16-25}, with {@code ONE_TOL =
     * 1.00000000000001}.
     *
     * @throws ProjectionException where PROJ sets
     *         {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN}
     */
    private double aasin(double v) {
        final double av = Math.abs(v);
        if (av >= 1.0) {
            if (av > 1.00000000000001) {
                throw new ProjectionException(this,
                        "inverse: asin argument " + v + " is outside [-1, 1], so the "
                                + "northing lies beyond the pole line");
            }
            return v < 0 ? -Math.PI / 2.0 : Math.PI / 2.0;
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
            PutninsP6FamilyProjection p = (PutninsP6FamilyProjection) that;
            return cx == p.cx && cy == p.cy && aa == p.aa && bb == p.bb && dd == p.dd
                    && super.equals(that);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(cx, cy, aa, bb, dd, super.hashCode());
    }
}

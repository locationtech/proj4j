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

import org.locationtech.proj4j.ProjCoordinate;

/**
 * van der Grinten IV ({@code +proj=vandg4}), a port of
 * {@code 9.8.1:src/projections/vandg4.cpp}. Forward only.
 *
 * <p><b>Its own file, not part of {@code vandg2.cpp}.</b> {@code vandg2.cpp} covers II and
 * III; IV has a 56-line file of its own and an unrelated construction — a quartic in
 * {@code bt} for the parallel curvature and a hyperbolic-looking {@code dt = d + 1/d} for the
 * meridians, resolved through a quadratic whose discriminant is the long {@code ft}
 * expression.
 *
 * <pre>
 *   bt  = |2 phi / pi|
 *   ct  = (bt (8 - bt (2 + bt^2)) - 5) / (2 bt^2 (bt - 1))
 *   dt  = 2 lam / pi ;  dt = dt + 1/dt ;  dt = sqrt(dt^2 - 4) ;  negated inside |lam| &lt; pi/2
 *   x1  = (bt + ct)^2
 *   ft  = x1 (bt^2 + ct^2 dt^2 - 1)
 *         + (1 - bt^2) (bt^2 ((bt + 3 ct)^2 + 4 ct^2) + ct^2 (12 bt ct + 4 ct^4/ct^2))
 *   x1  = (dt (x1 + ct^2 - 1) + 2 sqrt(ft)) / (4 x1 + dt^2)
 *   x   = (pi/2) x1
 *   y   = (pi/2) sqrt(1 + dt |x1| - x1^2)
 * </pre>
 *
 * <h2>Three guarded special cases, in upstream's order</h2>
 *
 * <p>The general formula divides by {@code bt^2 (bt - 1)} and by {@code dt}, so it is
 * singular on the equator, on the central meridian and at the poles. Upstream handles them
 * as a three-way cascade at {@code vandg4.cpp:17-23}, and the <b>order matters</b>: the
 * equator test comes first, so the origin {@code (0, 0)} takes the equator branch and yields
 * {@code (lam, 0) = (0, 0)} rather than the meridian branch's {@code (0, phi)} — the same
 * answer here, but not for a point on the equator away from the central meridian, where the
 * two branches differ ({@code x = lam} versus {@code x = 0}).
 *
 * <ul>
 * <li>{@code |phi| &lt; 1e-10} &rarr; {@code (lam, 0)} — the equator maps to a straight line
 *     at true scale.
 * <li>{@code |lam| &lt; 1e-10} or {@code | |phi| - pi/2 | &lt; 1e-10} &rarr; {@code (0, phi)}
 *     — the central meridian and both poles.
 * <li>otherwise the general form.
 * </ul>
 *
 * <p><b>{@code dt}'s sign flip is inside the strict inequality</b>
 * ({@code vandg4.cpp:31-32}): {@code (|lam| - pi/2) < 0} negates {@code dt}. At exactly
 * {@code |lam| = pi/2} it is not negated. Since {@code dt} then enters {@code ft} squared but
 * {@code x1} linearly, this is a real discontinuity in the formula's sign convention and not
 * a tolerance question.
 *
 * <p><b>{@code y} uses {@code |x1|}, not {@code x1}</b> ({@code vandg4.cpp:42}). The sign of
 * {@code x} is reapplied from {@code lam} afterwards, so taking the magnitude here is what
 * keeps the northing symmetric about the central meridian.
 */
public class VanDerGrinten4Projection extends Projection {

    private static final long serialVersionUID = 4596603329576866630L;

    private static final double TOL = 1e-10;

    /** {@code M_TWO_D_PI}: {@code 2/pi}. */
    private static final double TWO_D_PI = 2.0 / Math.PI;

    private static final double HALF_PI = Math.PI / 2.0;

    public VanDerGrinten4Projection() {
        es = 0.0;
        initialize();
    }

    /** {@code vandg4_s_forward}, {@code vandg4.cpp:12-49}. */
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate dst) {
        if (Math.abs(phi) < TOL) {
            dst.x = lam;
            dst.y = 0.0;
            return dst;
        }
        if (Math.abs(lam) < TOL || Math.abs(Math.abs(phi) - HALF_PI) < TOL) {
            dst.x = 0.0;
            dst.y = phi;
            return dst;
        }

        final double bt = Math.abs(TWO_D_PI * phi);
        final double bt2 = bt * bt;
        final double ct = 0.5 * (bt * (8.0 - bt * (2.0 + bt2)) - 5.0) / (bt2 * (bt - 1.0));
        final double ct2 = ct * ct;

        double dt = TWO_D_PI * lam;
        dt = dt + 1.0 / dt;
        dt = Math.sqrt(dt * dt - 4.0);
        if ((Math.abs(lam) - HALF_PI) < 0.0) {
            dt = -dt;
        }
        final double dt2 = dt * dt;

        double x1 = bt + ct;
        x1 *= x1;
        final double t = bt + 3.0 * ct;
        final double ft = x1 * (bt2 + ct2 * dt2 - 1.0)
                + (1.0 - bt2) * (bt2 * (t * t + 4.0 * ct2)
                        + ct2 * (12.0 * bt * ct + 4.0 * ct2));
        x1 = (dt * (x1 + ct2 - 1.0) + 2.0 * Math.sqrt(ft)) / (4.0 * x1 + dt2);

        dst.x = HALF_PI * x1;
        dst.y = HALF_PI * Math.sqrt(1.0 + dt * Math.abs(x1) - x1 * x1);
        if (lam < 0.0) {
            dst.x = -dst.x;
        }
        if (phi < 0.0) {
            dst.y = -dst.y;
        }
        return dst;
    }

    /** {@code false}: {@code P->inv} is never assigned ({@code vandg4.cpp:51-56}). */
    public boolean hasInverse() {
        return false;
    }

    public String toString() {
        return "van der Grinten IV";
    }
}

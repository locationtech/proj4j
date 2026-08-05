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

/**
 * van der Grinten II and III ({@code +proj=vandg2}, {@code +proj=vandg3}), a port of
 * {@code 9.8.1:src/projections/vandg2.cpp}. Forward only.
 *
 * <p>{@code vandg4} is <b>not</b> here: it has its own 56-line file and a completely
 * different construction. This file covers II and III only.
 *
 * <p>Both share the setup {@code bt = |2 phi / pi|}, {@code ct = sqrt(1 - bt^2)} clamped at
 * zero, and both special-case the central meridian; they then diverge:
 *
 * <pre>
 *   at = |pi/lam - lam/pi| / 2
 *   III:  x1 = bt / (1 + ct)
 *         x  = pi (sqrt(at^2 + 1 - x1^2) - at)
 *         y  = pi x1
 *   II:   x1 = (ct sqrt(1 + at^2) - at ct^2) / (1 + at^2 bt^2)
 *         x  = pi x1
 *         y  = pi sqrt(1 - x1 (x1 + 2 at) + TOL)
 * </pre>
 *
 * <p>with the signs of {@code lam} and {@code phi} reapplied afterwards.
 *
 * <h2>Three things to transcribe literally</h2>
 *
 * <p><b>The {@code + TOL} is inside the radicand</b> ({@code vandg2.cpp:44}), with
 * {@code TOL = 1e-10}, exactly as in {@code bacon.cpp}: it guards a quantity that is
 * analytically zero at the map boundary against rounding to a small negative. Moving it
 * outside, or dropping it, changes the answer at every point and not only at the edge.
 *
 * <p><b>The central-meridian branch has no sign reapplication.</b> When
 * {@code |lam| &lt; TOL} the code sets {@code y = pi (phi &lt; 0 ? -bt : bt) / (1 + ct)} and
 * returns without entering the {@code if (lp.lam &lt; 0)} / {@code if (lp.phi &lt; 0)} block
 * at {@code :46-49} — because it has already applied the latitude's sign itself. Hoisting
 * those two sign flips out of the {@code else} would double-negate the northing there.
 *
 * <p><b>{@code vandg2} does not zero {@code es}; {@code vandg3} does.</b>
 * {@code vandg2.cpp:55-66} omits {@code P->es = 0.} where {@code :68-80} includes it. Since
 * neither forward reads {@code es} the maps are identical either way, so this is latent
 * rather than live — but it means {@code +proj=vandg2 +ellps=WGS84} keeps a non-zero
 * eccentricity on the {@code PJ} and would report itself as ellipsoidal to anything that
 * asked. Reproduced, with {@code es} left alone for II and cleared for III, so that
 * {@link Projection#spherical} tells the same story upstream does.
 */
abstract class VanDerGrinten2FamilyProjection extends Projection {

    private static final long serialVersionUID = -459213700553365664L;

    private static final double TOL = 1e-10;

    /** {@code M_TWO_D_PI}: {@code 2/pi}. */
    private static final double TWO_D_PI = 2.0 / Math.PI;

    private final boolean vdg3;

    protected VanDerGrinten2FamilyProjection(boolean vdg3) {
        this.vdg3 = vdg3;
        if (vdg3) {
            // vandg2.cpp:76. vandg2's constructor deliberately omits this; see the class doc.
            es = 0.0;
        }
        initialize();
    }

    /** {@code vandg2_s_forward}, {@code vandg2.cpp:20-53}. */
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate dst) {
        final double bt = Math.abs(TWO_D_PI * phi);
        double ct = 1.0 - bt * bt;
        if (ct < 0.0) {
            ct = 0.0;
        } else {
            ct = Math.sqrt(ct);
        }

        if (Math.abs(lam) < TOL) {
            // vandg2.cpp:31-33. Applies phi's sign here and returns; no outer sign block.
            dst.x = 0.0;
            dst.y = Math.PI * (phi < 0.0 ? -bt : bt) / (1.0 + ct);
            return dst;
        }

        final double at = 0.5 * Math.abs(Math.PI / lam - lam / Math.PI);
        double x1;
        if (vdg3) {
            x1 = bt / (1.0 + ct);
            dst.x = Math.PI * (Math.sqrt(at * at + 1.0 - x1 * x1) - at);
            dst.y = Math.PI * x1;
        } else {
            x1 = (ct * Math.sqrt(1.0 + at * at) - at * ct * ct)
                    / (1.0 + at * at * bt * bt);
            dst.x = Math.PI * x1;
            dst.y = Math.PI * Math.sqrt(1.0 - x1 * (x1 + 2.0 * at) + TOL);
        }
        if (lam < 0.0) {
            dst.x = -dst.x;
        }
        if (phi < 0.0) {
            dst.y = -dst.y;
        }
        return dst;
    }

    /** {@code false}: {@code P->inv} is never assigned, and the doc string says {@code no inv}. */
    public boolean hasInverse() {
        return false;
    }

    @Override
    public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (that != null && getClass() == that.getClass()) {
            return vdg3 == ((VanDerGrinten2FamilyProjection) that).vdg3 && super.equals(that);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(vdg3, super.hashCode());
    }
}

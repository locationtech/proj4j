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
import org.locationtech.proj4j.util.GenericInverse2D;

/**
 * Winkel II ({@code +proj=wink2}), a port of {@code 9.8.1:src/projections/wink2.cpp}.
 *
 * <p>The forward averages an equal-area-style northing with an equirectangular one, using a
 * Mollweide-like auxiliary angle solved by Newton:
 *
 * <pre>
 *   theta seeded at 1.8 phi ; solve  theta + sin(theta) = pi sin(phi)  (10 steps, 1e-7)
 *   theta /= 2   (or snap to +/-pi/2 on non-convergence)
 *   x = lam (cos(theta) + cos(lat_1)) / 2
 *   y = (pi/4) (sin(theta) + 2 phi / pi)
 * </pre>
 *
 * <h2>The non-convergence branch is a success path</h2>
 *
 * <p>{@code wink2.cpp:33-36}: on exhausting the ten steps it sets
 * {@code phi = (phi < 0) ? -pi/2 : pi/2} and — note — <b>skips the halving</b> that the
 * converged path applies. So the pole case uses {@code theta = +/-pi/2} where the normal path
 * would use {@code theta/2}. It sets no errno and returns a valid coordinate. This must not
 * be converted into a throw: it is a deliberate pole clamp, not a failure. Reproduced,
 * including the asymmetry about the halving, which is easy to lose by hoisting
 * {@code phi *= 0.5} out of the {@code else}.
 *
 * <p>Note also that the sign test in that branch reads the <em>iterate</em>, not the original
 * latitude — by then {@code lp.phi} has been overwritten by the Newton loop. Transcribed as
 * upstream has it.
 *
 * <h2>The inverse is the generic 2-D Newton, not a formula</h2>
 *
 * <p>{@code wink2.cpp:44-52} has no closed form: it calls {@code pj_generic_inverse_2d} with
 * the seed {@code (lam, phi) = (x, y)} — the projected coordinates used directly as an
 * angular guess, which is crude but adequate because the map is close to the identity near
 * the origin — and {@code deltaXYTolerance = 1e-10}. So {@code wink2} came free once
 * {@link GenericInverse2D} existed for {@code adams_ws2}; that is the whole reason §9 rates
 * it as difficulty 2 rather than 4.
 *
 * <p>The Newton must invert the <b>raw</b> map, without {@code totalScale} or the false
 * origin, because upstream passes {@code P->fwd} and that is the raw layer. {@link #project}
 * is exactly that layer, so it is handed to {@code solve} directly — reachable because
 * {@code project} is {@code protected} and this class is in the same package.
 */
public class Winkel2Projection extends PseudoCylindricalProjection {

    private static final long serialVersionUID = -655558692830007073L;

    private static final int MAX_ITER = 10;
    private static final double LOOP_TOL = 1e-7;
    private static final double HALF_PI = Math.PI / 2.0;

    /** {@code M_FORTPI}: {@code pi/4}. */
    private static final double FORT_PI = Math.PI / 4.0;

    /** {@code M_TWO_D_PI}: {@code 2/pi}. */
    private static final double TWO_D_PI = 2.0 / Math.PI;

    /** {@code cos(lat_1)}, from {@code wink2.cpp:61-62}. */
    private double cosphi1 = 1.0;

    @Override
    public void initialize() {
        super.initialize();
        cosphi1 = StrictMath.cos(projectionLatitude1);
    }

    /** {@code wink2_s_forward}, {@code wink2.cpp:20-42}. */
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate dst) {
        final double yPart = phi * TWO_D_PI;
        final double k = Math.PI * StrictMath.sin(phi);
        double theta = phi * 1.8;

        int i = MAX_ITER;
        for (; i > 0; --i) {
            final double v = (theta + StrictMath.sin(theta) - k)
                    / (1.0 + StrictMath.cos(theta));
            theta -= v;
            if (Math.abs(v) < LOOP_TOL) {
                break;
            }
        }
        if (i == 0) {
            // wink2.cpp:33-34. Note: no halving on this path, and the sign is taken from
            // the iterate rather than the original latitude.
            theta = theta < 0.0 ? -HALF_PI : HALF_PI;
        } else {
            theta *= 0.5;
        }

        dst.x = 0.5 * lam * (StrictMath.cos(theta) + cosphi1);
        dst.y = FORT_PI * (StrictMath.sin(theta) + yPart);
        return dst;
    }

    /**
     * {@code wink2_s_inverse}, {@code wink2.cpp:44-52}: {@code pj_generic_inverse_2d} seeded
     * with the projected coordinate itself.
     */
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate dst) {
        return GenericInverse2D.solve(x, y, new GenericInverse2D.Forward2D() {
            public void forward(double lam, double phi, ProjCoordinate out) {
                project(lam, phi, out);
            }
        }, x, y, 1e-10, dst);
    }

    public boolean hasInverse() {
        return true;
    }

    public String toString() {
        return "Winkel II";
    }
}

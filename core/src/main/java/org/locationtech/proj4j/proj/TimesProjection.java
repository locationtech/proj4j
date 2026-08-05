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
 * The Times projection ({@code +proj=times}), a port of
 * {@code 9.8.1:src/projections/times.cpp}. Spherical only, closed form both ways, no
 * parameters and no domain restrictions.
 *
 * <p>From Snyder, <i>Flattening the Earth</i> (1993) pp. 213-214:
 *
 * <pre>
 *   T = tan(phi/2)
 *   S = sin(pi/4 * T)
 *   x = lam * (0.74482 - 0.34588 * S^2)
 *   y = 1.70711 * T
 * </pre>
 *
 * <p>and the inverse is the same four lines read upwards, with {@code T = y / 1.70711}
 * and {@code phi = 2 * atan(T)}.
 *
 * <h2>Two things not to "improve"</h2>
 *
 * <p><b>{@code sin(M_FORTPI * T)} really is {@code sin(pi/4 * T)}</b>
 * ({@code times.cpp:45}), not {@code sin(pi/4) * T} and not a degree conversion. It reads
 * like a typo and is not one.
 *
 * <p><b>{@code S} is recomputed from {@code T} in the inverse</b>
 * ({@code times.cpp:59-63}) rather than solved for, because {@code y} determines
 * {@code T} exactly — the northing is linear in {@code T}. So the inverse is exact rather
 * than iterative, and the corpus's five inverse rows are the forward rows read backwards
 * to the same 0.1 mm tolerance.
 *
 * <p>{@code phi = 2*atan(T)} lands strictly inside {@code (-pi, pi)}, so a wildly
 * out-of-range northing yields a latitude outside {@code [-pi/2, pi/2]} rather than an
 * error. That is upstream's behaviour; the inverse has no domain check at all.
 */
public class TimesProjection extends CylindricalProjection {

    private static final long serialVersionUID = -7338174566683790141L;

    /** {@code M_FORTPI}: {@code pi/4}, bit-identical to C's constant. */
    private static final double FORT_PI = Math.PI / 4.0;

    /** {@code times_s_forward}, {@code times.cpp:39-52}. */
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate dst) {
        final double t = StrictMath.tan(phi / 2.0);
        final double s = StrictMath.sin(FORT_PI * t);
        final double s2 = s * s;
        dst.x = lam * (0.74482 - 0.34588 * s2);
        dst.y = 1.70711 * t;
        return dst;
    }

    /** {@code times_s_inverse}, {@code times.cpp:54-67}. */
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate dst) {
        final double t = y / 1.70711;
        final double s = StrictMath.sin(FORT_PI * t);
        final double s2 = s * s;
        dst.x = x / (0.74482 - 0.34588 * s2);
        dst.y = 2.0 * StrictMath.atan(t);
        return dst;
    }

    public boolean hasInverse() {
        return true;
    }

    public String toString() {
        return "Times";
    }
}

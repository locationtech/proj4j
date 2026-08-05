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
 * Putnins P3 and P3&prime; ({@code +proj=putp3}, {@code +proj=putp3p}), a port of
 * {@code 9.8.1:src/projections/putp3.cpp}. The whole pair differs by <b>one</b> coefficient.
 *
 * <pre>
 *   x = C * lam * (1 - A * phi^2)
 *   y = C * phi
 * </pre>
 *
 * <p>with {@code C = 0.79788456} and {@code A = 4/pi^2} for {@code putp3},
 * {@code A = 2/pi^2} for {@code putp3p}. Upstream writes the coefficient as a multiple of
 * {@code RPISQ = 0.1013211836} ({@code putp3.cpp:17}) rather than computing
 * {@code 1/(pi*pi) = 0.10132118364233778}, so the constant is <b>truncated at ten
 * digits</b>. Kept truncated: {@code 4 * 0.1013211836} and {@code 4/(pi*pi)} differ in the
 * eleventh significant digit, and the corpus values were generated from the former.
 *
 * <p>{@code C = 0.79788456} is likewise a truncation, of {@code sqrt(2/pi) =
 * 0.7978845608028654}.
 *
 * <p>Parallels are straight and equally spaced; the bounding meridian is a parabola. Neither
 * member has a domain restriction, and the inverse is exact — the easting denominator
 * {@code C(1 - A phi^2)} vanishes only at {@code |phi| = pi/2 * sqrt(1/...)}, which for both
 * {@code A} values lies outside the valid latitude range ({@code putp3} reaches zero at
 * {@code |phi| = 1.5708} rad, i.e. a hair beyond the pole, and {@code putp3p} at
 * {@code 2.221} rad), so no guard is needed and upstream has none.
 */
abstract class PutninsP3FamilyProjection extends PseudoCylindricalProjection {

    private static final long serialVersionUID = -7581918266175029405L;

    /** {@code putp3.cpp:16} — truncated {@code sqrt(2/pi)}. */
    protected static final double C = 0.79788456;

    /** {@code putp3.cpp:17} — truncated {@code 1/pi^2}. Do not recompute. */
    protected static final double RPISQ = 0.1013211836;

    private final double aa;

    protected PutninsP3FamilyProjection(double aa) {
        this.aa = aa;
        es = 0.0;
        initialize();
    }

    /** {@code putp3_s_forward}, {@code putp3.cpp:19-28}. */
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate dst) {
        dst.x = C * lam * (1.0 - aa * phi * phi);
        dst.y = C * phi;
        return dst;
    }

    /** {@code putp3_s_inverse}, {@code putp3.cpp:30-39}. */
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate dst) {
        final double phi = y / C;
        dst.y = phi;
        dst.x = x / (C * (1.0 - aa * phi * phi));
        return dst;
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
            return aa == ((PutninsP3FamilyProjection) that).aa && super.equals(that);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(aa, super.hashCode());
    }
}

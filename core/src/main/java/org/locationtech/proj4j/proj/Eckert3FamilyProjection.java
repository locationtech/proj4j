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
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * The four pseudo-cylindricals PROJ 9.8.1 implements in one file,
 * {@code src/projections/eck3.cpp}: {@code eck3} (Eckert III), {@code kav7}
 * (Kavrayskiy VII), {@code wag6} (Wagner VI) and {@code putp1} (Putnins P1).
 *
 * <p>They differ only in four constants. The whole family is
 *
 * <pre>
 *   y = C_y * phi
 *   x = C_x * lam * (A + sqrt(1 - B * phi^2))
 * </pre>
 *
 * <p>with the inverse the same two lines rearranged. The parallels are straight and evenly
 * spaced; the meridians are the ellipse or circle arc that {@code sqrt(1 - B phi^2)} traces.
 * {@code A = 0} makes the bounding meridian a semi-ellipse ({@code kav7}, {@code wag6}),
 * {@code A = 1} makes it a circle-plus-straight-segment ({@code eck3}), and {@code A = -0.5}
 * gives {@code putp1} its characteristic pinch.
 *
 * <table>
 * <caption>{@code eck3.cpp:50-111}</caption>
 * <tr><th>{@code +proj=}</th><th>{@code C_x}</th><th>{@code C_y}</th><th>{@code A}</th>
 *     <th>{@code B}</th></tr>
 * <tr><td>{@code eck3}</td><td>0.42223820031577120149</td>
 *     <td>0.84447640063154240298</td><td>1.0</td>
 *     <td>0.4052847345693510857755</td></tr>
 * <tr><td>{@code kav7}</td><td>0.8660254037844</td><td>1.0</td><td>0.0</td>
 *     <td>0.30396355092701331433</td></tr>
 * <tr><td>{@code wag6}</td><td>1.0</td><td>1.0</td><td>0.0</td>
 *     <td>0.30396355092701331433</td></tr>
 * <tr><td>{@code putp1}</td><td>1.89490</td><td>0.94745</td><td>-0.5</td>
 *     <td>0.30396355092701331433</td></tr>
 * </table>
 *
 * <p><b>{@code kav7}'s {@code C_x} is truncated upstream and must stay truncated.</b>
 * {@code eck3.cpp:75} is {@code 0.8660254037844}, thirteen digits, where
 * {@code sqrt(3)/2 = 0.8660254037844386}. The file carries a comment noting the constant was
 * "defined twice in original code" and that the other value
 * ({@code 0.2632401569273184856851}) is retained as a safety measure; the truncated one is
 * the live one. Substituting the exact {@code sqrt(3)/2} shifts every {@code kav7} easting by
 * 4.5e-14 relative, which is 0.29 &micro;m on {@code +a=6400000} — under the corpus's 0.1 mm
 * bar, but it is a gratuitous divergence from the constant that generated the expected
 * values. Non-negotiable 7.
 *
 * <p><b>{@code putp1} lives here, not with the other {@code putp*}.</b> The commonly-cited
 * file map puts it with {@code putp3}/{@code putp5}/{@code putp6}; it does not — it is
 * {@code eck3.cpp}'s fourth {@code PROJ_HEAD}, and it inherits this forward/inverse pair
 * rather than any Putnins-specific one.
 *
 * <p>{@code asqrt} is upstream's clamped square root ({@code 0} for a non-positive
 * argument), which proj4j has as {@link ProjectionMath#sqrt(double)}. It matters only past
 * {@code |phi| = 1/sqrt(B)}, i.e. beyond 90&deg; for all four parameter sets, so it is
 * unreachable through the host latitude check — but it is kept because it is what upstream
 * writes.
 */
abstract class Eckert3FamilyProjection extends PseudoCylindricalProjection {

    private static final long serialVersionUID = 3409839341771454682L;

    private final double cx;
    private final double cy;
    private final double aa;
    private final double bb;

    protected Eckert3FamilyProjection(double cx, double cy, double aa, double bb) {
        this.cx = cx;
        this.cy = cy;
        this.aa = aa;
        this.bb = bb;
        es = 0.0;
        initialize();
    }

    /** {@code eck3_s_forward}, {@code eck3.cpp:20-27}. */
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate dst) {
        dst.y = cy * phi;
        dst.x = cx * lam * (aa + ProjectionMath.sqrt(1.0 - bb * phi * phi));
        return dst;
    }

    /**
     * {@code eck3_s_inverse}, {@code eck3.cpp:29-41}.
     *
     * <p>Upstream answers a zero denominator with {@code lp.lam = HUGE_VAL}, i.e. an
     * infinite longitude, and reports no error. Proj4J throws: an infinite ordinate is
     * exactly the "failure expressed as a coordinate" that non-negotiable 3 forbids, and
     * {@link Projection}'s finiteness check would reject it a moment later anyway with a
     * less specific message. The denominator can only vanish for {@code putp1}, where
     * {@code A = -0.5} and so {@code sqrt(1 - B phi^2) = 0.5} at
     * {@code |phi| = 1.5307} rad = 87.7&deg; — inside the valid latitude range, so this is
     * reachable, unlike the {@code asqrt} clamp. No corpus row probes it.
     */
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate dst) {
        final double phi = y / cy;
        final double denominator = cx * (aa + ProjectionMath.sqrt(1.0 - bb * phi * phi));
        if (denominator == 0.0) {
            throw new ProjectionException(this,
                    "inverse is singular at y = " + y + " (phi = " + phi
                            + " rad): the easting scale vanishes there (eck3.cpp:36)");
        }
        dst.y = phi;
        dst.x = x / denominator;
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
            Eckert3FamilyProjection p = (Eckert3FamilyProjection) that;
            return cx == p.cx && cy == p.cy && aa == p.aa && bb == p.bb
                    && super.equals(that);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(cx, cy, aa, bb, super.hashCode());
    }
}

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
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Bertin 1953 ({@code +proj=bertin1953}), a port of
 * {@code 9.8.1:src/projections/bertin1953.cpp}. Forward only.
 *
 * <p>Jacques Bertin's 1953 world map, the French cartographic school's standard choice for
 * global phenomena. The formula is Philippe Rivière's 2017 reconstruction
 * (<a href="https://visionscarto.net/bertin-projection-1953">visionscarto.net</a>) — Bertin
 * drew the original by hand, so there is no analytic original to be faithful to and the
 * reconstruction <em>is</em> the definition.
 *
 * <p>Five stages, in order:
 *
 * <ol>
 * <li><b>Rotate</b> to a pole at {@code lat_0 = -42} degrees, via a Cartesian intermediate.
 * <li><b>{@code adjlon}</b> the rotated longitude into {@code (-pi, pi]}.
 * <li><b>A piecewise pre-warp</b> applied only where {@code lam + phi &lt; -1.4}, which
 *     stretches the South Pacific so the continents do not crowd.
 * <li><b>Hammer(1.68, 2)</b>, i.e. Hammer with the easting stretched by {@code w = 1.68}.
 * <li><b>A piecewise post-warp</b> with different formulas for {@code y &lt; 0} and
 *     {@code y &gt; 0}.
 * </ol>
 *
 * <h2>It hard-codes its own origin and ignores {@code +lat_0} / {@code +lon_0}</h2>
 *
 * <p>{@code bertin1953.cpp:81-82} sets {@code P->lam0 = 0} and
 * {@code P->phi0 = PJ_TORAD(-42.)} <b>unconditionally</b>, after the generic parameter pass
 * has already read {@code +lat_0} and {@code +lon_0}. So {@code +proj=bertin1953 +lat_0=30}
 * silently behaves as {@code +lat_0=-42}. The rotation constants are then derived from that
 * fixed {@code phi0}, and a further {@code -16.5} degrees is added to the longitude inside the
 * forward ({@code :36}).
 *
 * <p>This class therefore reads neither {@link Projection#projectionLatitude} nor
 * {@link Projection#projectionLongitude}, and computes the rotation constants from the fixed
 * value. Since {@code Projection.project} subtracts {@code projectionLongitude} before
 * calling {@link #project}, a caller passing {@code +lon_0} would still see a shifted map —
 * that is a pre-existing difference in where the two libraries apply the central meridian,
 * not something this class can correct without changing the shared forward path.
 *
 * <h2>{@code cos_delta_gamma} and {@code sin_delta_gamma} are constants</h2>
 *
 * <p>They are set to {@code 1} and {@code 0} ({@code bertin1953.cpp:86-87}) and never
 * changed, so the {@code delta_gamma} rotation is the identity. The terms are retained in the
 * transcription because removing them would obscure the correspondence with upstream and with
 * the Snyder oblique-rotation form the code is an instance of — but note that
 * {@code z0 * cos_delta_gamma + y * sin_delta_gamma} is just {@code z0}, and the
 * {@code atan2}'s first argument reduces to {@code y}.
 *
 * <h2>The two warps are asymmetric and the tests are strict inequalities</h2>
 *
 * <p>The post-warp's two branches ({@code :64-69}) are {@code y < 0} and {@code y > 0}, so
 * {@code y == 0} is left alone by both — not an accident, since the {@code y < 0} branch
 * scales {@code x} and the {@code y > 0} branch scales {@code y}, and at the equator neither
 * is wanted. And the {@code y > 0} branch multiplies by {@code 1 + d / 1.5 * x^2} using the
 * <em>already-warped</em> {@code x}: C evaluates the {@code y < 0} branch first, but since the
 * two conditions are disjoint no sequencing issue arises. Transcribed as two independent
 * {@code if}s.
 */
public class Bertin1953Projection extends Projection {

    private static final long serialVersionUID = 5480943740504988035L;

    /** {@code phi0}, hard-coded at {@code bertin1953.cpp:82}. */
    private static final double PHI_0 = ProjectionMath.toRad(-42.0);

    /** The extra longitude offset applied inside the forward, {@code bertin1953.cpp:36}. */
    private static final double DELTA_LAMBDA = ProjectionMath.toRad(-16.5);

    private static final double FU = 1.4;
    private static final double K = 12.0;
    private static final double W = 1.68;

    private final double cosDeltaPhi = StrictMath.cos(PHI_0);
    private final double sinDeltaPhi = StrictMath.sin(PHI_0);
    // bertin1953.cpp:86-87 -- the delta_gamma rotation is the identity.
    private static final double COS_DELTA_GAMMA = 1.0;
    private static final double SIN_DELTA_GAMMA = 0.0;

    public Bertin1953Projection() {
        es = 0.0;
        initialize();
    }

    /** {@code bertin1953_s_forward}, {@code bertin1953.cpp:28-72}. */
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate dst) {
        double d;

        /* Rotate -- bertin1953.cpp:34-47 */
        lam += DELTA_LAMBDA;
        double cosphi = StrictMath.cos(phi);
        final double x = StrictMath.cos(lam) * cosphi;
        final double y = StrictMath.sin(lam) * cosphi;
        final double z = StrictMath.sin(phi);
        double z0 = z * cosDeltaPhi + x * sinDeltaPhi;
        lam = StrictMath.atan2(y * COS_DELTA_GAMMA - z0 * SIN_DELTA_GAMMA,
                x * cosDeltaPhi - z * sinDeltaPhi);
        z0 = z0 * COS_DELTA_GAMMA + y * SIN_DELTA_GAMMA;
        phi = StrictMath.asin(z0);

        lam = ProjectionMath.adjlon(lam);

        /* Adjust pre-projection -- bertin1953.cpp:50-54 */
        if (lam + phi < -FU) {
            d = (lam - phi + 1.6) * (lam + phi + FU) / 8.0;
            lam += d;
            phi -= 0.8 * d * StrictMath.sin(phi + Math.PI / 2.0);
        }

        /* Project with Hammer(1.68, 2) -- bertin1953.cpp:57-60 */
        cosphi = StrictMath.cos(phi);
        d = Math.sqrt(2.0 / (1.0 + cosphi * StrictMath.cos(lam / 2.0)));
        dst.x = W * d * cosphi * StrictMath.sin(lam / 2.0);
        dst.y = d * StrictMath.sin(phi);

        /* Adjust post-projection -- bertin1953.cpp:63-69 */
        d = (1.0 - StrictMath.cos(lam * phi)) / K;
        if (dst.y < 0.0) {
            dst.x *= 1.0 + d;
        }
        if (dst.y > 0.0) {
            dst.y *= 1.0 + d / 1.5 * dst.x * dst.x;
        }
        return dst;
    }

    /** {@code false}: {@code P->inv} is never assigned, and the doc string says {@code no inv}. */
    public boolean hasInverse() {
        return false;
    }

    public String toString() {
        return "Bertin 1953";
    }
}

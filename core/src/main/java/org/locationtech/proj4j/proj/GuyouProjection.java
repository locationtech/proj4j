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
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.EllipticIntegral;
import org.locationtech.proj4j.util.FastStrictTrig;

/**
 * Guyou hemisphere-in-a-square. The {@code GUYOU} branch of
 * {@code 9.8.1:src/projections/adams.cpp:112-133}.
 *
 * <p>Maps one hemisphere — the {@code 180} degrees of longitude centred on the central
 * meridian — conformally onto a square. Longitudes outside that hemisphere are
 * <b>rejected</b>, not wrapped: {@code guyou.gie} spends 333 of its 705 assertions proving
 * that, which is 47% of the file and the single largest block of {@code expect failure} rows
 * attributable to projection logic anywhere in the corpus.
 *
 * <h2>The pole case returns a truncated constant, on purpose</h2>
 *
 * <p>{@code adams.cpp:124-127} short-circuits {@code | |phi| - pi/2 | < 1e-9} to
 * {@code (0, +/-1.85407)} — {@link EllipticIntegral#GUYOU_POLE}, five decimal places. Every
 * other point in this family goes through the seven-term Chebyshev series, whose value at
 * {@code pi/2} is {@code 1.854074716833181}; the truncated literal is 4.7e-06 below that,
 * i.e. <b>30.05 m</b> at {@code +R=6370997}. {@code guyou.gie:2122-2131} asserts
 * {@code (0, +/-1.85407)} at {@code +R=1} with a 1 mm tolerance, so "improving" the constant
 * fails those two assertions by 30 m of unit-sphere-scaled error and gains nothing.
 *
 * <p>Note also that the pole test comes <em>second</em>, after the longitude rejection: a
 * pole given with {@code |lam| > pi/2} is a domain failure, not a pole.
 *
 * <p>No inverse — {@code pj_adams_setup} installs one only for {@code adams_ws2}
 * ({@code adams.cpp:399-400}).
 */
public class GuyouProjection extends AdamsProjection {

    private static final long serialVersionUID = -7326365330345576836L;

    @Override
    protected ProjCoordinate projectRaw(double lam, double phi, ProjCoordinate dst) {
        if ((Math.abs(lam) - TOL) > HALF_PI) {
            throw new ProjectionException(
                    "guyou: |lam| exceeds pi/2; the projection covers one hemisphere only");
        }

        if (Math.abs(Math.abs(phi) - HALF_PI) < TOL) {
            dst.x = 0;
            dst.y = phi < 0 ? -EllipticIntegral.GUYOU_POLE : EllipticIntegral.GUYOU_POLE;
            return dst;
        }

        final double sl = FastStrictTrig.sin(lam);
        final double sp = FastStrictTrig.sin(phi);
        final double cp = FastStrictTrig.cos(phi);
        final double a = aacos((cp * sl - sp) * RSQRT2);
        final double b = aacos((cp * sl + sp) * RSQRT2);
        return ellipticTail(a, b, lam < 0., phi < 0., dst);
    }

    @Override
    public boolean hasInverse() {
        return false;
    }

    @Override
    public String toString() {
        return "Guyou";
    }
}

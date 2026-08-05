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
import org.locationtech.proj4j.util.FastStrictTrig;

/**
 * Adams hemisphere-in-a-square. The {@code ADAMS_HEMI} branch of
 * {@code 9.8.1:src/projections/adams.cpp:157-172}, plus the 45-degree rotation at
 * {@code adams.cpp:287-291}.
 *
 * <p>Same hemisphere-only domain as {@link GuyouProjection} — {@code |lam| - 1e-9 > pi/2} is
 * rejected — and again 333 of {@code adams_hemi.gie}'s 703 assertions exist to prove it.
 *
 * <p>Two details separate it from the other four reductions:
 *
 * <ul>
 * <li>Its second reduced angle is <b>not</b> an {@code aacos} result: {@code b = pi/2 - phi}
 *     directly. Nothing clamps it, and nothing needs to.
 * <li>The sign flags are computed from {@code a} <em>before</em> {@code a} is replaced by its
 *     arc-cosine ({@code adams.cpp:167-169} reuses the variable). Reading them after would
 *     compare {@code sin(phi)} against an angle rather than against a cosine, which is a
 *     different predicate on roughly half the sphere.
 * </ul>
 *
 * <p>The result is rotated 45 degrees, which is what turns the diamond the elliptic tail
 * produces into an axis-aligned square. No inverse.
 */
public class AdamsHemisphereProjection extends AdamsProjection {

    private static final long serialVersionUID = 5078643111573894094L;

    @Override
    protected ProjCoordinate projectRaw(double lam, double phi, ProjCoordinate dst) {
        final double sp = FastStrictTrig.sin(phi);
        if ((Math.abs(lam) - TOL) > HALF_PI) {
            throw new ProjectionException(
                    "adams_hemi: |lam| exceeds pi/2; the projection covers one hemisphere only");
        }
        double a = FastStrictTrig.cos(phi) * FastStrictTrig.sin(lam);
        final boolean sm = (sp + a) < 0.;
        final boolean sn = (sp - a) < 0.;
        a = aacos(a);
        final double b = HALF_PI - phi;

        ellipticTail(a, b, sm, sn, dst);
        rotate45(dst);
        return dst;
    }

    @Override
    public boolean hasInverse() {
        return false;
    }

    @Override
    public String toString() {
        return "Adams Hemisphere in a Square";
    }
}

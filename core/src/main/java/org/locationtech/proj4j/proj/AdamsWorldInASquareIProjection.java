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
import org.locationtech.proj4j.util.FastStrictTrig;

/**
 * Adams world-in-a-square I. The {@code ADAMS_WS1} branch of
 * {@code 9.8.1:src/projections/adams.cpp:174-181}.
 *
 * <p>Unlike {@link GuyouProjection} and {@link AdamsHemisphereProjection}, this one covers the
 * whole sphere and has <b>no projection-level domain check whatsoever</b>. All 57
 * {@code expect failure} rows in {@code adams_ws1.gie} come from PROJ's host-level
 * {@code fwd_prepare} pre-check on latitude and longitude range, and none from this code.
 * Do not add a guard here to make a failing row pass — it would be the wrong guard in the
 * wrong place.
 *
 * <p>{@code tan(phi/2)} is exactly {@code +/-1} at {@code phi = +/-pi/2}, so {@link #aasin}
 * saturates there rather than erroring, and {@code cos(aasin(+/-1)) = 0} kills the
 * {@code sin(lam/2)} term at the poles. That is why no guard is needed and also why the two
 * arc-cosine arguments, {@code (t -/+ s) * RSQRT2}, stay inside {@code [-1, 1]}: at worst
 * {@code |t| + |s| = 1}.
 *
 * <p>Upstream reuses one variable for {@code sp}, then for {@code t}, then for {@code b}; the
 * three roles are given distinct names here. No rotation, and no inverse.
 */
public class AdamsWorldInASquareIProjection extends AdamsProjection {

    private static final long serialVersionUID = 283429704440687713L;

    @Override
    protected ProjCoordinate projectRaw(double lam, double phi, ProjCoordinate dst) {
        final double s = FastStrictTrig.tan(0.5 * phi);
        final double t = FastStrictTrig.cos(aasin(s)) * FastStrictTrig.sin(0.5 * lam);
        final double a = aacos((t - s) * RSQRT2);
        final double b = aacos((t + s) * RSQRT2);
        return ellipticTail(a, b, lam < 0., phi < 0., dst);
    }

    @Override
    public boolean hasInverse() {
        return false;
    }

    @Override
    public String toString() {
        return "Adams World in a Square I";
    }
}

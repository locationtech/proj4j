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
 * Winkel I ({@code +proj=wink1}), a port of {@code 9.8.1:src/projections/wink1.cpp}.
 * Four arithmetic lines each way.
 *
 * <pre>
 *   x = lam (cos(lat_ts) + cos(phi)) / 2
 *   y = phi
 * </pre>
 *
 * <p>The arithmetic mean of Sanson-Flamsteed's easting and equirectangular's, with
 * {@code +lat_ts} choosing the parallel whose length is preserved. {@code +lat_ts=0} — the
 * default, since {@code cos(0) = 1} — gives the classic Winkel I.
 *
 * <p><b>{@code +lat_ts} is read through the {@code "r"} prefix</b>
 * ({@code wink1.cpp:42-43}: {@code cos(pj_param(..., "rlat_ts").f)}), so it is an angle and
 * is converted to radians by the parameter machinery. Proj4J holds it in
 * {@link Projection#trueScaleLatitude}, which {@code Proj4Parser} already populates from
 * {@code +lat_ts} in radians — so no parser work was needed here, unlike {@code gn_sinu}'s
 * {@code +m}/{@code +n}.
 *
 * <p>Note {@code cosphi1} is computed <b>once at setup</b> from {@code lat_ts}, not per
 * point. Recomputing it in {@link #project} would give the same answer but would be a
 * departure, and it is on the hot path.
 *
 * <p>{@code wink1} <b>has</b> an inverse, and the doc mark agrees: {@code wink1.cpp:9} says
 * {@code "\n\tPCyl, Sph\n\tlat_ts="} with no {@code no inv}, and {@code :45} assigns
 * {@code P->inv}. Worth stating because five of its neighbours in this batch
 * ({@code vandg2}, {@code vandg3}, {@code vandg4}, and all three of {@code bacon.cpp}) are
 * marked {@code no inv} and genuinely have none.
 */
public class Winkel1Projection extends PseudoCylindricalProjection {

    private static final long serialVersionUID = 551609772579677021L;

    /** {@code cos(lat_ts)}, from {@code wink1.cpp:42-43}. */
    private double cosphi1 = 1.0;

    @Override
    public void initialize() {
        super.initialize();
        cosphi1 = StrictMath.cos(trueScaleLatitude);
    }

    /** {@code wink1_s_forward}, {@code wink1.cpp:17-24}. */
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate dst) {
        dst.x = 0.5 * lam * (cosphi1 + StrictMath.cos(phi));
        dst.y = phi;
        return dst;
    }

    /** {@code wink1_s_inverse}, {@code wink1.cpp:26-33}. */
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate dst) {
        dst.y = y;
        dst.x = 2.0 * x / (cosphi1 + StrictMath.cos(y));
        return dst;
    }

    public boolean hasInverse() {
        return true;
    }

    public String toString() {
        return "Winkel I";
    }
}

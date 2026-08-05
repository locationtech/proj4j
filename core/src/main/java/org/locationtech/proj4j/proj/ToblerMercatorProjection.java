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
import org.locationtech.proj4j.util.MathHelpers;

/**
 * Tobler-Mercator ({@code +proj=tobmerc}), a port of
 * {@code 9.8.1:src/projections/tobmerc.cpp}. Spherical only; both directions are closed
 * form.
 *
 * <p>An equal-area companion to {@code merc}: the northing is Mercator's, but the easting
 * carries {@code cos^2(phi)} rather than being independent of latitude, which is what
 * converts the conformal map into an equal-area one.
 *
 * <pre>
 *   x = k0 * lam * cos(phi)^2
 *   y = k0 * asinh(tan(phi))
 * </pre>
 *
 * <h2>The pole rejection is deliberate, and upstream says so</h2>
 *
 * <p>{@code |phi| &gt;= pi/2} is rejected with
 * {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN}
 * ({@code tobmerc.cpp:16-25}). Upstream's own comment records that it is not obviously
 * necessary — {@code M_HALFPI} is strictly below the true {@code pi/2} in double
 * precision, so {@code tan} would merely return a large finite number and
 * {@code asinh} of it about 38.025 — but {@code builtins.gie} asserts the failure, so the
 * check stays. Two corpus rows depend on it.
 *
 * <p>The interaction with {@link Projection#checkForwardDomain} matters: that method
 * <em>clamps</em> a latitude within {@code 1e-12} rad of the pole to exactly
 * {@code Math.PI/2}, and {@code Math.PI/2} is the same double as C's {@code M_HALFPI}. So
 * a corpus row at exactly 90&deg; arrives here as exactly {@code Math.PI/2} and the
 * {@code >=} fires, which is what upstream does too.
 *
 * <h2>{@code k0}</h2>
 *
 * <p>{@code P-&gt;k0} is Proj4J's {@code scaleFactor} ({@code +k_0}, default 1). It divides
 * out again in the inverse, so a non-default {@code +k_0} is exercised by the roundtrip
 * as well as the forward.
 *
 * @see MercatorProjection
 */
public class ToblerMercatorProjection extends CylindricalProjection {

    private static final long serialVersionUID = 5807501652865754998L;

    private static final double HALF_PI = Math.PI / 2.0;

    /**
     * {@code tobmerc_s_forward}, {@code tobmerc.cpp:12-31}.
     *
     * @throws ProjectionException at {@code |phi| >= pi/2}, where upstream sets
     *         {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN}
     */
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate dst) {
        if (Math.abs(phi) >= HALF_PI) {
            throw new ProjectionException(this,
                    "tobmerc is undefined at the poles: |phi| = " + Math.abs(phi)
                            + " rad is at or beyond pi/2 (tobmerc.cpp:16)");
        }
        final double cosphi = StrictMath.cos(phi);
        dst.x = scaleFactor * lam * cosphi * cosphi;
        dst.y = scaleFactor * MathHelpers.asinh(StrictMath.tan(phi));
        return dst;
    }

    /**
     * {@code tobmerc_s_inverse}, {@code tobmerc.cpp:33-41}.
     *
     * <p>No domain check and no guard on {@code cosphi}: {@code atan(sinh(.))} lands
     * strictly inside {@code (-pi/2, pi/2)} for every finite argument, so
     * {@code cos(phi)} is strictly positive and the division is safe. The corpus probes
     * this deliberately with {@code +proj=tobmerc +R=1} at {@code y = 1e-15} against a
     * {@code 1e-15 m} tolerance, which only holds because {@code asinh}/{@code sinh} are
     * both accurate near zero — see {@link MathHelpers#asinh}'s {@code log1p} branch.
     */
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate dst) {
        final double phi = StrictMath.atan(StrictMath.sinh(y / scaleFactor));
        final double cosphi = StrictMath.cos(phi);
        dst.y = phi;
        dst.x = x / scaleFactor / (cosphi * cosphi);
        return dst;
    }

    public boolean hasInverse() {
        return true;
    }

    public boolean isEqualArea() {
        return true;
    }

    public String toString() {
        return "Tobler-Mercator";
    }
}

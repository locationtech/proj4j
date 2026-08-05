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
import org.locationtech.proj4j.util.MathHelpers;

/**
 * Web Mercator / Pseudo-Mercator ({@code +proj=webmerc}, EPSG:3857), a port of
 * {@code 9.8.1:src/projections/merc.cpp}'s second {@code PROJ_HEAD}
 * ({@code merc.cpp:76-84}).
 *
 * <h2>What makes it "pseudo"</h2>
 *
 * <p>It applies the <b>spherical</b> Mercator formulas to <b>ellipsoidal</b> geographic
 * coordinates, scaled by the ellipsoid's semi-major axis:
 *
 * <pre>
 *   x = a * lam
 *   y = a * asinh(tan(phi))
 * </pre>
 *
 * <p>That is not Mercator on a sphere of radius {@code a} — the input latitudes are
 * geodetic, so the result is conformal for neither figure. It is a deliberate
 * incompatibility standardised because every web map tile scheme was already doing it.
 * The northing differs from true ellipsoidal Mercator by up to about 20 km at mid
 * latitudes, which is why EPSG gives it its own code rather than treating it as a variant.
 *
 * <p>Consequently this class does <b>not</b> extend {@link MercatorProjection}: that class
 * dispatches on {@link Projection#spherical}, and {@code webmerc} needs the spherical
 * branch on an ellipsoid, which is the one thing the dispatch cannot express.
 *
 * <h2>{@code +k_0} is ignored, and that is upstream's choice</h2>
 *
 * <p>{@code merc.cpp:79} sets {@code P-&gt;k0 = 1.0} unconditionally, with the comment
 * "Overriding k_0 with fixed parameter", <em>after</em> the generic parameter pass has
 * already read {@code +k_0}. So {@code +proj=webmerc +k_0=2} silently behaves as
 * {@code +k_0=1}. This class therefore never reads {@link Projection#scaleFactor}.
 * {@code +lat_ts} is likewise not consulted — the {@code webmerc} constructor does not run
 * {@code merc}'s {@code lat_ts} block at all.
 *
 * <h2>Coverage</h2>
 *
 * <p>Contrary to a widely-cited gap analysis, {@code webmerc} has <b>no coverage in
 * {@code builtins.gie}</b>. Its only gie rows are three operations in
 * {@code gie/4D-API_cs2cs-style.gie}: two inside {@code +proj=pipeline} steps (reachable
 * only once the pipeline engine lands) and one standalone
 * {@code operation proj=webmerc +ellps=WGS84} carrying two {@code expect} rows from EPSG
 * Guidance Note 7-2 p. 44, at {@code tolerance 1 cm}.
 */
public class WebMercatorProjection extends CylindricalProjection {

    private static final long serialVersionUID = 6205783088039603982L;

    /**
     * {@code merc_s_forward} with {@code k0 = 1} ({@code merc.cpp:24-29}). The {@code a}
     * factor is applied by {@link Projection}'s {@code totalScale}.
     */
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate dst) {
        dst.x = lam;
        dst.y = MathHelpers.asinh(StrictMath.tan(phi));
        return dst;
    }

    /** {@code merc_s_inverse} with {@code k0 = 1} ({@code merc.cpp:38-43}). */
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate dst) {
        dst.x = x;
        dst.y = StrictMath.atan(StrictMath.sinh(y));
        return dst;
    }

    public boolean hasInverse() {
        return true;
    }

    /**
     * {@code false}. Web Mercator is conformal for neither the sphere nor the ellipsoid it
     * is fed: it borrows the sphere's formulas and the ellipsoid's latitudes, so the scale
     * factor is direction-dependent. Reporting {@code true} here — as would be inherited
     * from a {@code merc} base class — would be a substantive false claim about the map,
     * not a cosmetic one.
     */
    public boolean isConformal() {
        return false;
    }

    public String toString() {
        return "Web Mercator / Pseudo Mercator";
    }
}

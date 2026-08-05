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

import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Universal Polar Stereographic, {@code +proj=ups} —
 * {@code PJ_PROJECTION(ups)}, {@code 9.8.1:src/projections/stere.cpp:308-330}.
 *
 * <p>A fixed parameterisation of polar {@code stere}, and nothing else:
 *
 * <pre>
 *   phi0 = +south ? -pi/2 : +pi/2
 *   k0   = 0.994
 *   x_0  = y_0 = 2 000 000
 *   lam0 = 0
 *   phits (lat_ts) = pi/2
 * </pre>
 *
 * <p>followed by the shared {@code stere_setup}. {@link StereographicAzimuthalProjection#setupUPS}
 * already assigned exactly those five values before this class existed; all this adds is the
 * registered {@code +proj=} name, {@code +south} dispatch, and upstream's one rejection.
 *
 * <h2>The rejection is the ninth assertion</h2>
 *
 * <p>{@code stere.cpp:317-322} refuses a sphere outright:
 *
 * <pre>
 *   if (P-&gt;es == 0.0) {
 *       proj_log_error(P, _("Invalid value for es: only ellipsoidal formulation supported"));
 *       return pj_default_destructor(P, PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE);
 *   }
 * </pre>
 *
 * <p>and {@code builtins.gie:7678} asserts it:
 *
 * <pre>
 *   operation +proj=ups   +a=6400000
 *   # ups not possible on sphere
 *   expect failure errno invalid_op_illegal_arg_value
 * </pre>
 *
 * <p>Note {@code +a=6400000} alone declares a <em>sphere</em> in 9.8.1 — {@code ellps_shape}'s
 * "not giving a shape parameter means selecting a sphere" — which is why that row fails rather
 * than projecting on a default GRS80.
 *
 * <p><b>The ellipsoidal branch is the only one reachable</b>, so the conformal latitude is
 * unavoidable. It arrives through {@code util/ConformalLat}, which the parent class already uses:
 * {@code tsfn} in the forward and {@code phi2} in the inverse, never the deprecated
 * {@code ProjectionMath} spellings. The parent's class comment records the one deliberate
 * numerical divergence that brings — Karney's fixed-trip {@code phi2} rather than upstream's
 * eight-trip {@code pow} loop, worth about 4&thinsp;&micro;m, 25&thinsp;000&times; inside the
 * corpus's 0.1&thinsp;mm bar.
 *
 * @since 1.5.0
 */
public class UniversalPolarStereographicProjection extends StereographicAzimuthalProjection {

    private static final long serialVersionUID = 7186760270389707980L;

    /** {@code stere.cpp:324} — UPS is always at scale 0.994 on the pole. */
    public static final double UPS_SCALE_FACTOR = 0.994;

    /** {@code stere.cpp:325-326} — 2 000 000 m on both axes, so northings stay positive. */
    public static final double UPS_FALSE_ORIGIN = 2000000.0;

    private boolean south;

    /**
     * Whether the constructor has finished.
     *
     * <p><b>Why this is needed.</b> {@link AzimuthalProjection#AzimuthalProjection} calls
     * {@code initialize()} from its own constructor ({@code AzimuthalProjection.java:44}, reached
     * through {@link StereographicAzimuthalProjection}'s two constructors), so the override below
     * runs <em>before</em> any caller has had a chance to call {@link #setEllipsoid}. At that point
     * the ellipsoid is still {@code Projection}'s default {@code Ellipsoid.SPHERE}, whose
     * {@code es} is zero — so an unguarded rejection makes {@code new
     * UniversalPolarStereographicProjection()} throw, which in turn makes
     * {@code Registry.getProjection("ups")} throw and the name unusable.
     *
     * <p>A {@code boolean} field defaults to {@code false} and its initialiser runs only after
     * {@code super()} returns, so this is {@code false} for exactly the duration of the
     * superclass's eager {@code initialize()} and {@code true} for every later call. Eager
     * initialisation from a constructor is a pre-existing wart in this hierarchy, not something
     * introduced here; this is the narrowest way to work around it without editing a file this
     * change does not own.
     */
    private boolean constructed;

    public UniversalPolarStereographicProjection() {
        // super() has already run the eager initialize(); from here on the check is live.
        constructed = true;
    }

    /**
     * {@code +south} ({@code pj_param(..., "bsouth")}), which selects the south polar aspect.
     * <p>
     * Overridden because {@link Projection#setSouthernHemisphere(boolean)} refuses on the base
     * class, and neither {@code stere} nor {@link AzimuthalProjection} reads the flag. Without
     * this override {@code +proj=ups +south} would raise
     * {@link org.locationtech.proj4j.UnsupportedParameterException} — the honest answer for
     * {@code stere}, and the wrong one here.
     */
    @Override
    public void setSouthernHemisphere(boolean isSouth) {
        this.south = isSouth;
    }

    @Override
    public boolean getSouthernHemisphere() {
        return south;
    }

    /**
     * {@code PJ_PROJECTION(ups)}: reject a sphere, fix the five parameters, then
     * {@code stere_setup}.
     *
     * @throws InvalidValueException with {@link ErrorCause#INVALID_PARAM_VALUE} for
     *         {@code es == 0}, which is {@code builtins.gie:7678}'s
     *         {@code expect failure errno invalid_op_illegal_arg_value}
     */
    @Override
    public void initialize() {
        // Ordered as upstream: phi0 comes from +south first, then es is checked. The order does
        // not affect the outcome but keeps the two files line-comparable.
        projectionLatitude = south ? -ProjectionMath.HALFPI : ProjectionMath.HALFPI;
        if (constructed && es == 0.0) {
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                    "+proj=ups: Invalid value for es: only ellipsoidal formulation supported. "
                            + "UPS is defined on an ellipsoid; note that +a= or +R= without a "
                            + "shape parameter declares a sphere (ell_set.cpp's \"not giving a "
                            + "shape parameter means selecting a sphere\"), so +proj=ups "
                            + "+a=6400000 is rejected. Use +proj=stere for a sphere.");
        }
        scaleFactor = UPS_SCALE_FACTOR;
        falseEasting = UPS_FALSE_ORIGIN;
        falseNorthing = UPS_FALSE_ORIGIN;
        projectionLongitude = 0.0;
        trueScaleLatitude = ProjectionMath.HALFPI;
        super.initialize();
    }

    @Override
    public String toString() {
        return "Universal Polar Stereographic";
    }
}

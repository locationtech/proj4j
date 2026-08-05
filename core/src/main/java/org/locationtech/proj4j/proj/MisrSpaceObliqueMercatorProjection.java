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
 * Space Oblique Mercator for MISR, {@code +proj=misrsom} — {@code PJ_PROJECTION(misrsom)} of
 * {@code 9.8.1:src/projections/som.cpp:271-297}.
 *
 * <p>A fixed parameterisation of {@link SpaceObliqueMercatorProjection} for the Multi-angle
 * Imaging SpectroRadiometer aboard Terra. The whole operator is four assignments derived from
 * {@code +path}:
 *
 * <pre>
 *   lam0 = 129.3056° − (360°/233) × path
 *   alf  = 98.30382°
 *   p22  = 98.88 / 1440
 *   rlm  = 0
 * </pre>
 *
 * <p><b>The constants are upstream's digits exactly.</b> {@code 98.30382} and {@code 129.3056} are
 * not roundings of anything and must not be re-derived from an orbital model; {@code 98.88 / 1440}
 * is written as a division rather than as {@code 0.06866666666666667} so that the quotient is the
 * one the compiler produces, which is what {@code builtins.gie} was generated against.
 *
 * <h2>{@code +path} is required, and an absent one is upstream's error too</h2>
 *
 * <p>{@code Proj4Parser} dispatches {@code +path} to this class (and deliberately only to this class:
 * {@code LandsatProjection} hard-codes {@code path = 120}, so the bridge treats the key as
 * conditional). A definition that omits it arrives with {@code path = 0}, which upstream also
 * rejects — {@code pj_param(..., "ipath").i} yields {@code 0} and {@code path <= 0} is an error — so
 * refusing an unset path is upstream's own behaviour, not a divergence.
 *
 * @see SpaceObliqueMercatorProjection
 * @since 1.5.0
 */
public class MisrSpaceObliqueMercatorProjection extends SpaceObliqueMercatorProjection {

    private static final long serialVersionUID = -1805488731274182124L;

    /** {@code som.cpp:281} — the highest MISR path number. */
    public static final int MAX_PATH = 233;

    /** {@code som.cpp:288} — the ascending longitude at path 0, in degrees. */
    public static final double ASCENDING_LONGITUDE_AT_PATH_0_DEGREES = 129.3056;

    /** {@code som.cpp:289} — the orbital inclination, in degrees. */
    public static final double INCLINATION_DEGREES = 98.30382;

    /** {@code som.cpp:290} — the period of revolution, in minutes. */
    public static final double PERIOD_MINUTES = 98.88;

    /** 0 means "not set"; upstream rejects it, so it is a usable sentinel. */
    private int path;

    /**
     * {@code +path} — the MISR orbital path number, in {@code [1, 233]}.
     *
     * @param path the path number
     */
    public void setPath(int path) {
        this.path = path;
    }

    public int getPath() {
        return path;
    }

    /**
     * {@code PJ_PROJECTION(misrsom)}: range-check {@code +path}, derive the four values, then run
     * the shared {@code som_setup}.
     */
    @Override
    public void initialize() {
        if (path <= 0 || path > MAX_PATH) {
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                    "+path=" + path + ": Invalid value for path: path should be in [1, "
                            + MAX_PATH + "] range"
                            + (path == 0 ? ". +path is required for +proj=misrsom." : ""));
        }
        projectionLongitude = DTR * ASCENDING_LONGITUDE_AT_PATH_0_DEGREES
                - ProjectionMath.TWOPI / 233. * path;
        alf = INCLINATION_DEGREES * DTR;
        p22 = PERIOD_MINUTES / 1440.0;
        rlm = 0;
        // Projection.initialize() for one_es/rone_es/totalScale, then som_setup. The generic
        // som's [-2pi,2pi] / [0,pi] / >=0 checks are skipped deliberately: these three values are
        // constants, upstream does not re-check them, and the derived lam0 for path 233 is
        // -3.99 rad, which is inside [-2pi, 2pi] but would read oddly in an error message.
        initializeShared();
    }

    @Override
    public String toString() {
        return "Space Oblique for MISR";
    }
}

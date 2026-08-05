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

import org.locationtech.proj4j.util.Complex;

/**
 * Lee Oblated Stereographic, {@code +proj=lee_os} —
 * {@code PJ_PROJECTION(lee_os)}, {@code 9.8.1:src/projections/mod_ster.cpp:151-168}.
 *
 * <p>Centred on (165&deg;W, 10&deg;S) for the Pacific. Unlike the other four variants its
 * second coefficient is genuinely complex, {@code (-0.0088162, -0.00617325)}, which is what
 * rotates the oblate lobe onto the Pacific basin.
 *
 * @since 1.5.0
 */
public class LeeOblatedStereographicProjection extends ModifiedStereographicProjection {

    private static final long serialVersionUID = 8998821589821671482L;

    /** {@code mod_ster.cpp:153-154}, digit for digit. */
    private static final Complex[] AB = {
            new Complex(0.721316, 0.),
            new Complex(0., 0.),
            new Complex(-0.0088162, -0.00617325),
    };

    @Override
    protected void setupVariant() {
        setCoefficients(AB);
        projectionLongitude = DTR * -165.;
        projectionLatitude = DTR * -10.;
        es = 0.;
    }

    @Override
    public String toString() {
        return "Lee Oblated Stereographic";
    }
}

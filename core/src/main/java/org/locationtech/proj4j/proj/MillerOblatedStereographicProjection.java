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
 * Miller Oblated Stereographic, {@code +proj=mil_os} —
 * {@code PJ_PROJECTION(mil_os)}, {@code 9.8.1:src/projections/mod_ster.cpp:133-149}.
 *
 * <p>Centred on (20&deg;E, 18&deg;N) for Europe and Africa, with a two-term coefficient table.
 * {@code es} is forced to zero while {@code e} is left alone — see
 * {@link ModifiedStereographicProjection}'s class comment for why that is reproduced rather
 * than tidied.
 *
 * @since 1.5.0
 */
public class MillerOblatedStereographicProjection extends ModifiedStereographicProjection {

    private static final long serialVersionUID = 4801588175232969176L;

    /** {@code mod_ster.cpp:135}, digit for digit. */
    private static final Complex[] AB = {
            new Complex(0.924500, 0.),
            new Complex(0., 0.),
            new Complex(0.019430, 0.),
    };

    @Override
    protected void setupVariant() {
        setCoefficients(AB);
        projectionLongitude = DTR * 20.;
        projectionLatitude = DTR * 18.;
        es = 0.;
    }

    @Override
    public String toString() {
        return "Miller Oblated Stereographic";
    }
}

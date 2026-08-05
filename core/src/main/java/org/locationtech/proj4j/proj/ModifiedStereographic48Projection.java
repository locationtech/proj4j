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
 * Modified Stereographic of the 48 conterminous United States, {@code +proj=gs48} —
 * {@code PJ_PROJECTION(gs48)}, {@code 9.8.1:src/projections/mod_ster.cpp:170-187}.
 *
 * <p>Centred on (96&deg;W, 39&deg;N), four coefficients, all purely real. It is the only variant
 * that fixes the semi-major axis <em>and</em> zeroes {@code es} without offering an ellipsoidal
 * table, so a definition giving any {@code +ellps} still projects on the sphere of radius
 * 6&thinsp;370&thinsp;997 m — with {@code e} surviving into the conformal-latitude expression, as
 * described in {@link ModifiedStereographicProjection}.
 *
 * @since 1.5.0
 */
public class ModifiedStereographic48Projection extends ModifiedStereographicProjection {

    private static final long serialVersionUID = 1164249510041101502L;

    /** {@code mod_ster.cpp:172-174}, digit for digit. */
    private static final Complex[] AB = {
            new Complex(0.98879, 0.),
            new Complex(0., 0.),
            new Complex(-0.050909, 0.),
            new Complex(0., 0.),
            new Complex(0.075528, 0.),
    };

    @Override
    protected void setupVariant() {
        setCoefficients(AB);
        projectionLongitude = DTR * -96.;
        projectionLatitude = DTR * 39.;
        es = 0.;
        a = SPHERE_A;
    }

    @Override
    public String toString() {
        return "Mod. Stereographic of 48 U.S.";
    }
}

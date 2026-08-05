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
 * Modified Stereographic of Alaska, {@code +proj=alsk} —
 * {@code PJ_PROJECTION(alsk)}, {@code 9.8.1:src/projections/mod_ster.cpp:189-219}.
 *
 * <p>Centred on (152&deg;W, 64&deg;N), five coefficients, and <b>two</b> tables: the requested
 * {@code es} selects between them and is then replaced.
 *
 * <p><b>This class is what makes {@code +proj=alsk} work.</b> {@code Registry} bound the name to
 * the abstract {@link Projection} base class itself, so {@code Registry.getProjection("alsk")}
 * raised {@code PROJECTION_NOT_IMPLEMENTED} — honest, and a deliberate improvement over the
 * previous {@code "Unknown projection: alsk"}, which lied about a name that was in the registry.
 * The 16 {@code builtins.gie} assertions were unreachable rather than wrong.
 *
 * @since 1.5.0
 */
public class AlaskaModifiedStereographicProjection extends ModifiedStereographicProjection {

    private static final long serialVersionUID = 5347283055141823524L;

    /** {@code mod_ster.cpp:191-195} — the Alaska <b>ellipsoid</b> table, digit for digit. */
    private static final Complex[] ABE = {
            new Complex(.9945303, 0.),
            new Complex(.0052083, -.0027404),
            new Complex(.0072721, .0048181),
            new Complex(-.0151089, -.1932526),
            new Complex(.0642675, -.1381226),
            new Complex(.3582802, -.2884586),
    };

    /** {@code mod_ster.cpp:197-201} — the Alaska <b>sphere</b> table, digit for digit. */
    private static final Complex[] ABS = {
            new Complex(.9972523, 0.),
            new Complex(.0052513, -.0041175),
            new Complex(.0074606, .0048125),
            new Complex(-.0153783, -.1968253),
            new Complex(.0636871, -.1408027),
            new Complex(.3660976, -.2937382),
    };

    @Override
    protected void setupVariant() {
        projectionLongitude = DTR * -152.;
        projectionLatitude = DTR * 64.;
        // mod_ster.cpp:209-216. The test is on the *requested* es; the assignment replaces it.
        if (es != 0.0) {
            setCoefficients(ABE);
            a = FIXED_A;
            es = FIXED_ES;
            e = Math.sqrt(es);
        } else {
            setCoefficients(ABS);
            a = SPHERE_A;
        }
    }

    @Override
    public String toString() {
        return "Mod. Stereographic of Alaska";
    }
}

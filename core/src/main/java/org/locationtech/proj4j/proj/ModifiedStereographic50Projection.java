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
 * Modified Stereographic of the 50 United States, {@code +proj=gs50} —
 * {@code PJ_PROJECTION(gs50)}, {@code 9.8.1:src/projections/mod_ster.cpp:221-262}.
 *
 * <p>Centred on (120&deg;W, 45&deg;N) with a <b>ninth-order</b> table — the largest in the family —
 * because it has to cover the conterminous states, Alaska and Hawaii at once. Like {@code alsk} it
 * carries separate ellipsoidal and spherical tables, selects on the requested {@code es}, and then
 * replaces the ellipsoid with {@code a = 6378206.4}, {@code es = 0.00676866}.
 *
 * <p>The commented-out corpus row is worth knowing about: {@code builtins.gie} has
 *
 * <pre>
 *   # For some reason, does not fail on MacOSX
 *   #accept  60 -45
 *   #expect  failure errno coord_transfm_outside_projection_domain
 * </pre>
 *
 * so the antipodal-ish point at (60&deg;E, 45&deg;S) is a platform-dependent failure upstream and
 * is asserted by nobody. It is <em>not</em> counted in this projection's 16 assertions.
 *
 * @since 1.5.0
 */
public class ModifiedStereographic50Projection extends ModifiedStereographicProjection {

    private static final long serialVersionUID = -5108252161217020877L;

    /** {@code mod_ster.cpp:223-229} — the GS50 <b>ellipsoid</b> table, digit for digit. */
    private static final Complex[] ABE = {
            new Complex(.9827497, 0.),
            new Complex(.0210669, .0053804),
            new Complex(-.1031415, -.0571664),
            new Complex(-.0323337, -.0322847),
            new Complex(.0502303, .1211983),
            new Complex(.0251805, .0895678),
            new Complex(-.0012315, -.1416121),
            new Complex(.0072202, -.1317091),
            new Complex(-.0194029, .0759677),
            new Complex(-.0210072, .0834037),
    };

    /** {@code mod_ster.cpp:231-238} — the GS50 <b>sphere</b> table, digit for digit. */
    private static final Complex[] ABS = {
            new Complex(.9842990, 0.),
            new Complex(.0211642, .0037608),
            new Complex(-.1036018, -.0575102),
            new Complex(-.0329095, -.0320119),
            new Complex(.0499471, .1223335),
            new Complex(.0260460, .0899805),
            new Complex(.0007388, -.1435792),
            new Complex(.0075848, -.1334108),
            new Complex(-.0216473, .0776645),
            new Complex(-.0225161, .0853673),
    };

    @Override
    protected void setupVariant() {
        projectionLongitude = DTR * -120.;
        projectionLatitude = DTR * 45.;
        // mod_ster.cpp:252-259. As alsk: test the requested es, then replace it.
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
        return "Mod. Stereographic of 50 U.S.";
    }
}

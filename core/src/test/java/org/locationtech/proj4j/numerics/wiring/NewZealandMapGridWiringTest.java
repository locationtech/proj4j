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

package org.locationtech.proj4j.numerics.wiring;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Method;

import org.junit.Test;
import org.locationtech.proj4j.proj.NewZealandMapGridProjection;
import org.locationtech.proj4j.util.AuthalicLat;

/**
 * {@code NewZealandMapGridProjection} <b>needs no part of the numerical core</b>, and this test
 * exists to pin that conclusion rather than a numeric.
 *
 * <p>The numerics reference lists {@code nzmg} among the projections the authalic-latitude fix moves.
 * That is wrong for this port. {@code 9.8.1:src/projections/nzmg.cpp} is a fixed-Earth fit — two
 * hard-coded real series and six hard-coded complex coefficients published with the grid definition —
 * and it calls neither {@code pj_authalic_lat} nor {@code pj_qsfn} nor {@code pj_mlfn}. proj4j
 * nonetheless carried <em>static imports</em> of {@code ProjectionMath.authlat}, {@code authset} and
 * {@code qsfn} (plus {@code asin}, {@code sin} and {@code HALFPI}), none of them ever referenced,
 * which is presumably where the belief came from. They are removed rather than converted: pointing
 * them at {@link AuthalicLat} would have created a dependency upstream does not have and no code path
 * exercises.
 */
public class NewZealandMapGridWiringTest {

    /**
     * The class must not reference the authalic machinery at all — new or deprecated. Checked
     * against the constant pool, because an unused import leaves no trace there and a real
     * dependency does.
     */
    @Test
    public void doesNotDependOnTheAuthalicMachinery() {
        for (Method m : NewZealandMapGridProjection.class.getDeclaredMethods()) {
            assertEquals("nzmg declares no method returning an authalic type",
                    false, AuthalicLat.class.equals(m.getReturnType()));
        }
        for (java.lang.reflect.Field f : NewZealandMapGridProjection.class.getDeclaredFields()) {
            assertEquals("nzmg holds no authalic field: " + f,
                    false, AuthalicLat.class.equals(f.getType()));
            assertEquals("nzmg holds no meridian-arc field either: " + f, false,
                    org.locationtech.proj4j.util.MeridianArc.class.equals(f.getType()));
        }
    }

    /**
     * {@code builtins.gie:5016-5036}, {@code +proj=nzmg +ellps=GRS80} at {@code tolerance 0.1 mm}.
     * A no-movement guard: removing six static imports must not change a single bit, and the origin
     * must still land on the grid's false origin.
     */
    @Test
    public void gridOriginIsUnmoved() {
        GieCase r = GieCase.grs80("+proj=nzmg +ellps=GRS80");
        // nzmg forces its own axis, lon_0 = 173, lat_0 = -41, x_0 = 2510000, y_0 = 6023150.
        assertEquals(2510000.0, r.forward(173, -41).x, 1e-6);
        assertEquals(6023150.0, r.forward(173, -41).y, 1e-6);
        r.expectRoundtrip(174, -40, 1, 0.1 * GieCase.MM);
        r.expectRoundtrip(172, -42, 1, 0.1 * GieCase.MM);
    }
}

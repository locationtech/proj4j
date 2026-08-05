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

package org.locationtech.proj4j.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.locationtech.proj4j.util.NumericAssert.assertDoubleEq;

/**
 * A verbatim port of {@code 9.8.1:test/unit/pj_phi2_test.cpp} (Kurt Schwehr, Google Inc.,
 * 2018), the upstream unit test for {@code pj_phi2}.
 *
 * <p>Every {@code EXPECT_EQ} below is transcribed as an exact-equality assertion: the
 * expectation is that only sane values of {@code e} (and {@code NaN}, which upstream
 * reckons sane) are passed, and that limits at {@code ts = 0}, {@code 1} and
 * {@code infinity} are reproduced <em>bit for bit</em> rather than to a tolerance. This is
 * the cheapest regression net available for this function.
 *
 * <p>Each {@code EXPECT_DOUBLE_EQ} is transcribed through
 * {@link NumericAssert#assertDoubleEq}, which applies googletest's own 4-ULP rule.
 */
public class PjPhi2Test {

    private static final double M_PI_2 = Math.PI / 2.0;
    private static final double INF = Double.POSITIVE_INFINITY;
    private static final double NAN = Double.NaN;

    @Test
    public void basic() {
        // Expectation is that only sane values of e (and nan is here reckoned to
        // be sane) are passed to pj_phi2.  Thus the return value with other values
        // of e is "implementation dependent".

        // Strict equality is demanded here.
        assertEquals(M_PI_2, ConformalLat.phi2(+0.0, 0.0), 0.0);
        assertEquals(0.0, ConformalLat.phi2(1.0, 0.0), 0.0);
        assertEquals(-M_PI_2, ConformalLat.phi2(INF, 0.0), 0.0);
        // We don't expect pj_phi2 to be called with negative ts (since ts =
        // exp(-psi)).  However, in the current implementation it is odd in ts.
        // N.B. ts = +0.0 and ts = -0.0 return different results.
        assertEquals(-M_PI_2, ConformalLat.phi2(-0.0, 0.0), 0.0);
        assertEquals(0.0, ConformalLat.phi2(-1.0, 0.0), 0.0);
        assertEquals(+M_PI_2, ConformalLat.phi2(-INF, 0.0), 0.0);

        final double e = 0.2;
        assertEquals(M_PI_2, ConformalLat.phi2(+0.0, e), 0.0);
        assertEquals(0.0, ConformalLat.phi2(1.0, e), 0.0);
        assertEquals(-M_PI_2, ConformalLat.phi2(INF, e), 0.0);
        assertEquals(-M_PI_2, ConformalLat.phi2(-0.0, e), 0.0);
        assertEquals(0.0, ConformalLat.phi2(-1.0, e), 0.0);
        assertEquals(+M_PI_2, ConformalLat.phi2(-INF, e), 0.0);

        assertTrue(Double.isNaN(ConformalLat.phi2(NAN, 0.0)));
        assertTrue(Double.isNaN(ConformalLat.phi2(NAN, e)));
        assertTrue(Double.isNaN(ConformalLat.phi2(+0.0, NAN)));
        assertTrue(Double.isNaN(ConformalLat.phi2(1.0, NAN)));
        assertTrue(Double.isNaN(ConformalLat.phi2(INF, NAN)));
        assertTrue(Double.isNaN(ConformalLat.phi2(-0.0, NAN)));
        assertTrue(Double.isNaN(ConformalLat.phi2(-1.0, NAN)));
        assertTrue(Double.isNaN(ConformalLat.phi2(-INF, NAN)));
        assertTrue(Double.isNaN(ConformalLat.phi2(NAN, NAN)));

        assertDoubleEq(Math.PI / 3, ConformalLat.phi2(1 / (Math.sqrt(3.0) + 2), 0.0));
        assertDoubleEq(Math.PI / 4, ConformalLat.phi2(1 / (Math.sqrt(2.0) + 1), 0.0));
        assertDoubleEq(Math.PI / 6, ConformalLat.phi2(1 / Math.sqrt(3.0), 0.0));
        assertDoubleEq(-Math.PI / 3, ConformalLat.phi2(Math.sqrt(3.0) + 2, 0.0));
        assertDoubleEq(-Math.PI / 4, ConformalLat.phi2(Math.sqrt(2.0) + 1, 0.0));
        assertDoubleEq(-Math.PI / 6, ConformalLat.phi2(Math.sqrt(3.0), 0.0));

        // Generated with exp(e * atanh(e * sin(phi))) / (tan(phi) + sec(phi))
        assertDoubleEq(Math.PI / 3, ConformalLat.phi2(0.27749174377027023413, e));
        assertDoubleEq(Math.PI / 4, ConformalLat.phi2(0.42617788119104192995, e));
        assertDoubleEq(Math.PI / 6, ConformalLat.phi2(0.58905302448626726064, e));
        assertDoubleEq(-Math.PI / 3, ConformalLat.phi2(3.6037108218537833089, e));
        assertDoubleEq(-Math.PI / 4, ConformalLat.phi2(2.3464380582241712935, e));
        assertDoubleEq(-Math.PI / 6, ConformalLat.phi2(1.6976400399134411849, e));
    }

    /**
     * The signed-zero asymmetry that upstream's comment calls out explicitly: it comes
     * from {@code 1 / (-0.0) == -infinity} inside {@code (1/ts - ts) / 2}, and any
     * "tidying" of that expression would silently break it.
     */
    @Test
    public void signedZeroIsAsymmetric() {
        assertTrue(ConformalLat.phi2(+0.0, 0.2) != ConformalLat.phi2(-0.0, 0.2));
        assertEquals(+M_PI_2, ConformalLat.phi2(+0.0, 0.2), 0.0);
        assertEquals(-M_PI_2, ConformalLat.phi2(-0.0, 0.2), 0.0);
    }

    /**
     * {@code TMAX} and {@code ROOTEPS} are exact powers of two; the literals in
     * {@link ConformalLat} must match what {@code phi2.cpp} derives at runtime.
     */
    @Test
    public void thresholdsAreExactPowersOfTwo() {
        assertEquals(Math.sqrt(Math.ulp(1.0)), ConformalLat.ROOTEPS, 0.0);
        assertEquals(StrictMath.pow(2.0, -26), ConformalLat.ROOTEPS, 0.0);
        assertEquals(2.0 / ConformalLat.ROOTEPS, ConformalLat.TMAX, 0.0);
        assertEquals(StrictMath.pow(2.0, 27), ConformalLat.TMAX, 0.0);
    }

    /**
     * Above {@code TMAX} the large-argument limit is returned directly, without entering
     * the Newton loop, so {@code sinhpsi2tanphi} is exactly linear there.
     */
    @Test
    public void largeArgumentLimitIsExact() {
        final double e = 0.2;
        final double scale = StrictMath.exp(e * MathHelpers.atanh(e));
        final double taup = 1.0e10; // well above TMAX / scale
        assertEquals(taup * scale, ConformalLat.sinhpsi2tanphi(taup, e), 0.0);
        assertEquals(INF, ConformalLat.sinhpsi2tanphi(INF, e), 0.0);
        assertEquals(-INF, ConformalLat.sinhpsi2tanphi(-INF, e), 0.0);
        assertTrue(Double.isNaN(ConformalLat.sinhpsi2tanphi(NAN, e)));
    }
}

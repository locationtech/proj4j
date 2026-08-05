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
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.GenericInverse2D;

/**
 * Adams world-in-a-square II. The {@code ADAMS_WS2} branch of
 * {@code 9.8.1:src/projections/adams.cpp:183-190}, the 45-degree rotation at
 * {@code adams.cpp:287-291}, and the seeded Newton inverse at {@code adams.cpp:296-317}.
 *
 * <p>Whole-sphere, and like {@link AdamsWorldInASquareIProjection} it has <b>no
 * projection-level domain check</b>: all 56 {@code expect failure} rows in
 * {@code adams_ws2.gie}'s forward blocks are PROJ's host-level pre-check, and the 57th is the
 * inverse failing to converge.
 *
 * <p>It differs from world-in-a-square I in two ways that are easy to conflate: the sign flags
 * are taken from {@code s +/- t} rather than from the signs of {@code lam} and {@code phi}, and
 * only one of the two reduced angles gets the {@code RSQRT2} factor — {@code b = aacos(s)}
 * takes none at all.
 *
 * <h2>The inverse</h2>
 *
 * <p>This is the only member of the family with an inverse of its own, and it is
 * {@link GenericInverse2D} seeded by a deliberately rough guess
 * ({@code adams.cpp:308-313}):
 *
 * <pre>
 *   phi0 = clamp(y / 2.62181347, -1, 1) * pi/2
 *   lam0 = |phi0| &gt;= pi/2 ? 0 : clamp(x / 2.62205760 / cos(phi0), -1, 1) * pi
 * </pre>
 *
 * <p>The two magic divisors are this projection's own images of {@code (0, 90)} and
 * {@code (180, 0)} at unit radius; PROJ's comment records the two {@code src/proj} invocations
 * that produced them. They are not interchangeable and not equal — {@code 2.62181347} against
 * {@code 2.62205760} — because the forward map's northern extent and eastern extent differ in
 * the fourth decimal.
 *
 * <p>{@link SpilhausProjection} drives this class through {@link #projectRaw} and
 * {@link #projectInverse}, i.e. below the {@code totalScale}/false-origin layer, exactly as
 * {@code spilhaus.cpp} calls the child {@code PJ}'s {@code fwd}/{@code inv} directly.
 */
public class AdamsWorldInASquareIIProjection extends AdamsProjection {

    private static final long serialVersionUID = -578708846864253423L;

    /** {@code adams_ws2}'s unit-radius northing at {@code (0, 90)} ({@code adams.cpp:310}). */
    private static final double SEED_Y = 2.62181347;

    /** {@code adams_ws2}'s unit-radius easting at {@code (180, 0)} ({@code adams.cpp:313}). */
    private static final double SEED_X = 2.62205760;

    /** Every adams-family caller of the generic inverse passes this ({@code adams.cpp:316}). */
    private static final double DELTA_XY_TOLERANCE = 1e-10;

    /**
     * The forward map as {@link GenericInverse2D} needs it. Held as a field rather than
     * allocated per call: {@code spilhaus}'s 59 roundtrips each drive one inverse, and each
     * inverse evaluates the forward up to 45 times.
     */
    private final GenericInverse2D.Forward2D rawForward = new GenericInverse2D.Forward2D() {
        @Override
        public void forward(double lam, double phi, ProjCoordinate dst) {
            projectRaw(lam, phi, dst);
        }
    };

    @Override
    protected ProjCoordinate projectRaw(double lam, double phi, ProjCoordinate dst) {
        final double s = FastStrictTrig.tan(0.5 * phi);
        final double t = FastStrictTrig.cos(aasin(s)) * FastStrictTrig.sin(0.5 * lam);
        final boolean sm = (s + t) < 0.;
        final boolean sn = (s - t) < 0.;
        final double b = aacos(s);
        final double a = aacos(t);

        ellipticTail(a, b, sm, sn, dst);
        rotate45(dst);
        return dst;
    }

    @Override
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate dst) {
        final double phi0 = clampUnit(y / SEED_Y) * HALF_PI;
        final double lam0 = Math.abs(phi0) >= HALF_PI
                ? 0
                : clampUnit(x / SEED_X / FastStrictTrig.cos(phi0)) * Math.PI;
        return GenericInverse2D.solve(x, y, rawForward, lam0, phi0, DELTA_XY_TOLERANCE, dst);
    }

    /** {@code std::max(std::min(v, 1.0), -1.0)}. */
    private static double clampUnit(double v) {
        return Math.max(Math.min(v, 1.0), -1.0);
    }

    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public String toString() {
        return "Adams World in a Square II";
    }
}

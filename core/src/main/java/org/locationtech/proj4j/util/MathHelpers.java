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

/**
 * The floating-point primitives Java lacks but PROJ's numerical core needs:
 * {@code asinh}, {@code atanh}, and a cheap two-argument norm.
 *
 * <p><b>Math vs StrictMath:</b> this class uses {@link StrictMath#log} and
 * {@link StrictMath#log1p} rather than {@link Math}. HotSpot ships an
 * architecture-specific intrinsic for {@code Math.log} on both x86-64 and AArch64, and
 * they differ at the last bit; {@code StrictMath} is fdlibm and identical everywhere,
 * which is what makes per-CRS derived constants bit-reproducible. {@code Math.sqrt} and
 * {@code Math.abs} <em>are</em> used, because IEEE-754 specifies {@code sqrt} as exactly
 * rounded and {@code abs} is a sign-bit mask — there is nothing for a platform to vary.
 * ({@code Math.log1p} has no intrinsic today and already delegates to
 * {@code StrictMath}, so naming {@code StrictMath} explicitly costs nothing and insures
 * against a future one.)
 *
 * <p>{@link Math#hypot} is deliberately absent: it is the fdlibm implementation,
 * roughly ten times the cost of a {@code sqrt}, and every operand in the numerical core
 * is bounded well away from overflow. Use {@link #norm2} instead.
 */
public final strictfp class MathHelpers {

    /** {@code log(2)}, needed by {@link #asinh}'s overflow branch. */
    private static final double LN2 = 0.6931471805599453094172321214581766;

    private MathHelpers() {
    }

    /**
     * The inverse hyperbolic sine, {@code log(x + sqrt(x*x + 1))}, computed in three
     * regimes so that it is accurate over the whole range.
     *
     * <p>The {@code y <= 1} branch uses {@code log1p} on the algebraically rearranged
     * argument {@code y + y*y/(1 + sqrt(1 + y*y))}. That is what keeps {@code merc}
     * correct at the equator: the naive form evaluates {@code log(1 + something tiny)},
     * whose leading 1 destroys every significant bit of the small term. The
     * {@code y > 1e150} branch avoids squaring into overflow by using
     * {@code asinh(y) ~ log(y) + log(2)}.
     *
     * <p><b>Signed zero:</b> the sign is restored with {@code x < 0.0}, which is false
     * for {@code -0.0}, so {@code asinh(-0.0)} returns {@code +0.0} where C's
     * {@code asinh} returns {@code -0.0}. This matches the reference Java form specified
     * for this port and is retained deliberately rather than "fixed" with
     * {@link Math#copySign}; the only consumer that could observe it is a false-easting
     * of {@code -0.0} in {@code etmerc}'s forward at exactly the central meridian.
     *
     * @param x the argument
     * @return {@code asinh(x)}
     */
    public static double asinh(double x) {
        final double y = Math.abs(x);
        final double r;
        if (y > 1.0e150) {
            r = StrictMath.log(y) + LN2; // y * y would overflow
        } else if (y > 1.0) {
            r = StrictMath.log(y + Math.sqrt(y * y + 1.0));
        } else {
            r = StrictMath.log1p(y + y * y / (1.0 + Math.sqrt(1.0 + y * y)));
        }
        return x < 0.0 ? -r : r;
    }

    /**
     * The inverse hyperbolic tangent, {@code 0.5 * log((1 + x) / (1 - x))}, evaluated as
     * {@code 0.5 * log1p(2*y / (1 - y))}.
     *
     * <p>The {@code log1p} form avoids the cancellation that the direct quotient-of-logs
     * form suffers for small {@code |x|}. proj4j's existing {@code qsfn}
     * ({@code ProjectionMath.java:436-445}) computes
     * {@code 0.5 * log((1 - x) / (1 + x))} and loses relative accuracy near
     * {@code phi = 0} for exactly that reason. Every caller in the numerical core passes
     * {@code e * sin(phi)}, whose magnitude is below 0.09 for any Earth ellipsoid, so
     * this form is uniformly accurate in practice.
     *
     * <p>{@code atanh(1)} is {@code +Infinity}, {@code atanh(-1)} is {@code -Infinity},
     * and {@code |x| > 1} yields {@code NaN}, as in C. As with {@link #asinh},
     * {@code atanh(-0.0)} returns {@code +0.0} rather than {@code -0.0}.
     *
     * @param x the argument
     * @return {@code atanh(x)}
     */
    public static double atanh(double x) {
        final double y = Math.abs(x);
        final double r = 0.5 * StrictMath.log1p(2.0 * y / (1.0 - y));
        return x < 0.0 ? -r : r;
    }

    /**
     * The Euclidean norm {@code sqrt(a*a + b*b)}, without {@code hypot}'s scaling
     * machinery.
     *
     * <p>Callers must keep operands inside about {@code 1e150} in magnitude and outside
     * about {@code 1e-150}; every use in the numerical core is a trigonometric ratio or
     * a coordinate normalised by the semi-major axis, so this holds by construction.
     *
     * @param a first component
     * @param b second component
     * @return {@code sqrt(a*a + b*b)}
     */
    public static double norm2(double a, double b) {
        return Math.sqrt(a * a + b * b);
    }
}

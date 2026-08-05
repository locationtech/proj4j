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

/**
 * Compact Miller ({@code +proj=comill}), a port of
 * {@code 9.8.1:src/projections/comill.cpp}.
 *
 * <p>Tom Patterson's 2014 variant of {@link MillerProjection}, fitted by Bojan Savric and
 * Bernhard Jenny. It compresses Miller Cylindrical's height so the map is less tall,
 * trading a little more polar area exaggeration for a more usable aspect ratio. The
 * simplest member of the family — a cubic in {@code phi^2}, with the easting untouched.
 *
 * <pre>
 *   x = lam
 *   y = phi * (K1 + phi^2 (K2 + K3 phi^2))
 * </pre>
 *
 * <p>Derivative multipliers {@code 1, 3, 5} ({@code comill.cpp:19-21}) for the powers
 * {@code phi^1, phi^3, phi^5}.
 *
 * <p>Not to be confused with {@code mill}, which proj4j already has as
 * {@link MillerProjection} and which is {@code y = asinh(tan(0.8 phi)) / 0.8} —
 * a different construction entirely, not a polynomial fit.
 */
public class CompactMillerProjection extends PolynomialPseudoCylindricalProjection {

    private static final long serialVersionUID = -4839864283547468294L;

    private static final double K1 = 0.9902;
    private static final double K2 = 0.1604;
    private static final double K3 = -0.03054;

    // comill.cpp:19-21.
    private static final double C1 = K1;
    private static final double C2 = 3.0 * K2;
    private static final double C3 = 5.0 * K3;

    /** {@code comill.cpp:23} — {@code 0.6000207669862655 * pi}. */
    private static final double MAX_Y = 0.6000207669862655 * Math.PI;

    protected double northing(double phi) {
        final double phi2 = phi * phi;
        return phi * (K1 + phi2 * (K2 + K3 * phi2));
    }

    protected double northingDerivative(double phi) {
        final double y2 = phi * phi;
        return C1 + y2 * (C2 + C3 * y2);
    }

    /** {@code comill.cpp:34} — the easting is {@code lam} exactly. */
    protected double eastingScale(double phi) {
        return 1.0;
    }

    protected double maxY() {
        return MAX_Y;
    }

    public String toString() {
        return "Compact Miller";
    }
}

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
 * Patterson Cylindrical ({@code +proj=patterson}), a port of
 * {@code 9.8.1:src/projections/patterson.cpp}.
 *
 * <p>Tom Patterson's 2014 design, equations by Bojan Savric; described in Patterson, Savric
 * and Jenny, <i>Cartographic Perspectives</i> 78 (2015),
 * <a href="https://doi.org/10.14714/CP78.1270">doi:10.14714/CP78.1270</a>. A true
 * cylindrical map — the easting is {@code lam} untouched — that only reshapes the spacing of
 * the parallels.
 *
 * <pre>
 *   x = lam
 *   y = phi * (K1 + phi^4 (K2 + phi^2 (K3 + K4 phi^2)))
 * </pre>
 *
 * <p>Derivative multipliers {@code 1, 5, 7, 9} ({@code patterson.cpp:53-56}) for the powers
 * {@code phi^1, phi^5, phi^7, phi^9}.
 *
 * <h2>{@code MAX_Y} is a bare literal here, not a multiple of pi</h2>
 *
 * <p>{@code patterson.cpp:58} is {@code #define MAX_Y 1.790857183} — a decimal constant,
 * where its three siblings all write theirs as {@code c * M_PI}. Do not "tidy" it into
 * {@code 0.5700...* Math.PI}; the literal is what upstream compares against.
 *
 * <h2>The seed-before-clamp quirk</h2>
 *
 * <p>This is the one member of the family that captures the Newton seed
 * <em>before</em> clamping the target northing ({@code patterson.cpp:80} then
 * {@code :83-87}). See {@link #seedBeforeClamp()}. Harmless for every in-range point, and
 * reproduced rather than normalised.
 */
public class PattersonProjection extends PolynomialPseudoCylindricalProjection {

    private static final long serialVersionUID = 8018382564219045354L;

    private static final double K1 = 1.0148;
    private static final double K2 = 0.23185;
    private static final double K3 = -0.14499;
    private static final double K4 = 0.02406;

    // patterson.cpp:53-56.
    private static final double C1 = K1;
    private static final double C2 = 5.0 * K2;
    private static final double C3 = 7.0 * K3;
    private static final double C4 = 9.0 * K4;

    /** {@code patterson.cpp:58}, verbatim. Not a multiple of pi upstream. */
    private static final double MAX_Y = 1.790857183;

    protected double northing(double phi) {
        final double phi2 = phi * phi;
        return phi * (K1 + phi2 * phi2 * (K2 + phi2 * (K3 + K4 * phi2)));
    }

    protected double northingDerivative(double phi) {
        final double y2 = phi * phi;
        return C1 + y2 * y2 * (C2 + y2 * (C3 + C4 * y2));
    }

    /** {@code patterson.cpp:68} — the easting is {@code lam} exactly. */
    protected double eastingScale(double phi) {
        return 1.0;
    }

    protected double maxY() {
        return MAX_Y;
    }

    @Override
    protected boolean seedBeforeClamp() {
        return true;
    }

    public String toString() {
        return "Patterson Cylindrical";
    }
}

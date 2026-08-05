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
 * Natural Earth II ({@code +proj=natearth2}), a port of
 * {@code 9.8.1:src/projections/natearth2.cpp}.
 *
 * <p>Tom Patterson's 2012 redesign, again fitted by Bojan Savric and Bernhard Jenny. Its
 * meridians bend in much more sharply towards the poles than Natural Earth I's, which shows
 * up in the polynomial as a very high-order onset: the northing's correction terms start at
 * {@code phi^9} and the easting's at {@code phi^13}.
 *
 * <pre>
 *   x = lam * (A0 + A1 phi^2 + phi^12 (A2 + A3 phi^2 + A4 phi^4 + A5 phi^6))
 *   y = phi  * (B0 + phi^8 (B1 + B2 phi^2 + B3 phi^4))
 * </pre>
 *
 * <p>{@code phi6 * phi6} and {@code phi4 * phi4} ({@code natearth2.cpp:45-46}) are written
 * that way upstream rather than as {@code pow}, and the grouping is load-bearing for
 * reproducibility: {@code phi^12} formed as {@code (phi^6)^2} is not always the same double
 * as {@code (phi^4)^3}. This transcription keeps upstream's factorisation exactly.
 *
 * <p>The derivative multipliers are {@code 1, 9, 11, 13} ({@code natearth2.cpp:26-29}) —
 * the powers {@code phi^1, phi^9, phi^11, phi^13} that the four northing terms carry.
 */
public class NaturalEarth2Projection extends PolynomialPseudoCylindricalProjection {

    private static final long serialVersionUID = 74586258349547032L;

    private static final double A0 = 0.84719;
    private static final double A1 = -0.13063;
    private static final double A2 = -0.04515;
    private static final double A3 = 0.05494;
    private static final double A4 = -0.02326;
    private static final double A5 = 0.00331;

    private static final double B0 = 1.01183;
    private static final double B1 = -0.02625;
    private static final double B2 = 0.01926;
    private static final double B3 = -0.00396;

    // natearth2.cpp:26-29.
    private static final double C0 = B0;
    private static final double C1 = 9.0 * B1;
    private static final double C2 = 11.0 * B2;
    private static final double C3 = 13.0 * B3;

    /** {@code natearth2.cpp:31} — {@code 0.84719 * 0.535117535153096 * pi}. */
    private static final double MAX_Y = 0.84719 * 0.535117535153096 * Math.PI;

    protected double northing(double phi) {
        final double phi2 = phi * phi;
        final double phi4 = phi2 * phi2;
        return phi * (B0 + phi4 * phi4 * (B1 + B2 * phi2 + B3 * phi4));
    }

    protected double northingDerivative(double phi) {
        final double y2 = phi * phi;
        final double y4 = y2 * y2;
        return C0 + y4 * y4 * (C1 + C2 * y2 + C3 * y4);
    }

    protected double eastingScale(double phi) {
        final double phi2 = phi * phi;
        final double phi4 = phi2 * phi2;
        final double phi6 = phi2 * phi4;
        return A0 + A1 * phi2 + phi6 * phi6 * (A2 + A3 * phi2 + A4 * phi4 + A5 * phi6);
    }

    protected double maxY() {
        return MAX_Y;
    }

    public String toString() {
        return "Natural Earth 2";
    }
}

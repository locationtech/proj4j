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
 * Natural Earth ({@code +proj=natearth}), a port of
 * {@code 9.8.1:src/projections/natearth.cpp}.
 *
 * <p>Designed by Tom Patterson (US National Park Service, 2007) in Flex Projector, where
 * the graticule was specified every 5&deg; and interpolated with cubic splines. The
 * polynomial fit ported here is Bojan Savric's, and upstream's header notes that it
 * <b>deliberately deviates</b> from Patterson's original by adding curvature to the
 * meridians where they meet the horizontal pole line. So this is not an approximation to
 * something more exact — it is the definition.
 *
 * <pre>
 *   x = lam * (A0 + phi^2 (A1 + phi^2 (A2 + phi^6 (A3 + phi^2 A4))))
 *   y = phi * (B0 + phi^2 (B1 + phi^4 (B2 + B3 phi^2 + B4 phi^4)))
 * </pre>
 *
 * <p>Note the {@code phi4 * phi2} in the easting ({@code natearth.cpp:51}): the {@code A3}
 * and {@code A4} terms enter at {@code phi^8} and {@code phi^10}, not {@code phi^6} and
 * {@code phi^8} as a uniform Horner nesting would give. The gap is intentional and is what
 * the pole-line curvature costs.
 */
public class NaturalEarthProjection extends PolynomialPseudoCylindricalProjection {

    private static final long serialVersionUID = 4877781967558540161L;

    private static final double A0 = 0.8707;
    private static final double A1 = -0.131979;
    private static final double A2 = -0.013791;
    private static final double A3 = 0.003971;
    private static final double A4 = -0.001529;

    private static final double B0 = 1.007226;
    private static final double B1 = 0.015085;
    private static final double B2 = -0.044475;
    private static final double B3 = 0.028874;
    private static final double B4 = -0.005916;

    // natearth.cpp:33-37. Multipliers are the powers of phi each B term carries:
    // phi^1, phi^3, phi^7, phi^9, phi^11.
    private static final double C0 = B0;
    private static final double C1 = 3.0 * B1;
    private static final double C2 = 7.0 * B2;
    private static final double C3 = 9.0 * B3;
    private static final double C4 = 11.0 * B4;

    /** {@code natearth.cpp:39} — {@code 0.8707 * 0.52 * pi}. */
    private static final double MAX_Y = 0.8707 * 0.52 * Math.PI;

    protected double northing(double phi) {
        final double phi2 = phi * phi;
        final double phi4 = phi2 * phi2;
        return phi * (B0 + phi2 * (B1 + phi4 * (B2 + B3 * phi2 + B4 * phi4)));
    }

    protected double northingDerivative(double phi) {
        final double y2 = phi * phi;
        final double y4 = y2 * y2;
        return C0 + y2 * (C1 + y4 * (C2 + C3 * y2 + C4 * y4));
    }

    protected double eastingScale(double phi) {
        final double phi2 = phi * phi;
        final double phi4 = phi2 * phi2;
        return A0 + phi2 * (A1 + phi2 * (A2 + phi4 * phi2 * (A3 + phi2 * A4)));
    }

    protected double maxY() {
        return MAX_Y;
    }

    public String toString() {
        return "Natural Earth";
    }
}

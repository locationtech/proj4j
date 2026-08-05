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

/**
 * Interrupted Goode Homolosine, {@code +proj=igh} — a port of
 * {@code 9.8.1:src/projections/igh.cpp}.
 *
 * <p>Twelve lobes. Sinusoidal near the equator, Mollweide above 40&deg;44'11.8"
 * ({@link InterruptedProjection#IGH_PHI_BOUNDARY}), with the interruptions placed to keep the
 * continents unbroken:
 *
 * <pre>
 *     -180            -40                       180
 *       +--------------+-------------------------+    Zones 1,2,9,10,11 &amp; 12:
 *       |1             |2                        |      Mollweide
 *       +--------------+-------------------------+    Zones 3,4,5,6,7 &amp; 8:
 *       |3             |4                        |      Sinusoidal
 *     0 +-------+------+-+-----------+-----------+
 *       |5      |6       |7          |8          |
 *       +-------+--------+-----------+-----------+
 *       |9      |10      |11         |12         |
 *       +-------+--------+-----------+-----------+
 *     -180    -100      -20         80          180
 * </pre>
 *
 * <h2>{@code dy0} is measured, not tabulated</h2>
 *
 * <p>The Mollweide and sinusoidal halves have to meet at the transition latitude, and the offset
 * that makes them meet is <b>computed at setup time by running both children</b>
 * ({@code igh.cpp:270-275}): forward {@code (0, 40°44'11.8")} through lobe 1 (Mollweide) and lobe 3
 * (sinusoidal) and take the difference of the northings. Hard-coding a decimal for it would be a
 * re-derived constant of exactly the kind that has cost this project four defects.
 *
 * <p><b>The order of setup therefore matters.</b> Lobes 3-8 and lobe 1 are built first, then
 * {@code dy0} is measured, then lobe 1's {@code y0} is assigned and lobes 2, 9, 10, 11 and 12 are
 * built with {@code ±dy0}.
 *
 * <h2>Zones 1 and 2 have extension lobes</h2>
 *
 * <p>Their {@code projectable} tests are not simple longitude ranges: zone 1 also accepts
 * {@code [-40°, -10°]} above 60&deg;N and zone 2 also accepts {@code [-180°, -160°]} above 50&deg;N
 * and {@code [-50°, -40°]} above 60&deg;N. Those are the deliberate overlaps that keep Greenland
 * and eastern Siberia whole. Dropping any of them turns real land into an interruption gap.
 *
 * @since 1.5.0
 */
public class InterruptedGoodeHomolosineProjection extends InterruptedProjection {

    private static final long serialVersionUID = 7757517234335770966L;

    private double dy0;

    @Override
    protected void setupZones() {
        allocateZones(12);
        /* sinusoidal zones */
        setupZone(3, sinusoidal(), -D100, 0, -D100);
        setupZone(4, sinusoidal(), D30, 0, D30);
        setupZone(5, sinusoidal(), -D160, 0, -D160);
        setupZone(6, sinusoidal(), -D60, 0, -D60);
        setupZone(7, sinusoidal(), D20, 0, D20);
        setupZone(8, sinusoidal(), D140, 0, D140);

        /* mollweide zones */
        setupZone(1, mollweide(), -D100, 0, -D100);

        /* y0 ? -- igh.cpp:270-275. y0 + xy1.y = xy3.y at lat = 40d44'11.8". */
        ProjCoordinate xy1 = zoneForward(1, 0, IGH_PHI_BOUNDARY, new ProjCoordinate());
        ProjCoordinate xy3 = zoneForward(3, 0, IGH_PHI_BOUNDARY, new ProjCoordinate());
        dy0 = xy3.y - xy1.y;
        setZoneY0(1, dy0);

        /* mollweide zones (cont'd) */
        setupZone(2, mollweide(), D30, dy0, D30);
        setupZone(9, mollweide(), -D160, -dy0, -D160);
        setupZone(10, mollweide(), -D60, -dy0, -D60);
        setupZone(11, mollweide(), D20, -dy0, D20);
        setupZone(12, mollweide(), D140, -dy0, D140);
    }

    /** {@code lt=90} corresponds to {@code y = y0 + sqrt(2)} ({@code igh.cpp:101-102}). */
    @Override
    protected double y90() {
        return dy0 + Math.sqrt(2.0);
    }

    @Override
    protected int forwardZone(double lam, double phi) {
        if (phi >= IGH_PHI_BOUNDARY) { /* 1|2 */
            return lam <= -D40 ? 1 : 2;
        }
        if (phi >= 0) { /* 3|4 */
            return lam <= -D40 ? 3 : 4;
        }
        if (phi >= -IGH_PHI_BOUNDARY) { /* 5|6|7|8 */
            if (lam <= -D100) {
                return 5;
            }
            if (lam <= -D20) {
                return 6;
            }
            return lam <= D80 ? 7 : 8;
        }
        /* 9|10|11|12 */
        if (lam <= -D100) {
            return 9;
        }
        if (lam <= -D20) {
            return 10;
        }
        return lam <= D80 ? 11 : 12;
    }

    /**
     * {@code igh_s_inverse}'s lobe selection ({@code igh.cpp:100-130}).
     *
     * <p>Note it compares the plane {@code y} against a latitude and the plane {@code x} against
     * longitudes; see {@link InterruptedProjection}'s class comment. And note the asymmetric
     * out-of-map band, {@code xy.y < -y90 + EPSLN} rather than {@code < -(y90 + EPSLN)}.
     */
    @Override
    protected int inverseZone(double x, double y) {
        final double y90 = y90();
        if (y > y90 + EPSLN || y < -y90 + EPSLN) {
            return 0;
        }
        if (y >= IGH_PHI_BOUNDARY) { /* 1|2 */
            return x <= -D40 ? 1 : 2;
        }
        if (y >= 0) { /* 3|4 */
            return x <= -D40 ? 3 : 4;
        }
        if (y >= -IGH_PHI_BOUNDARY) { /* 5|6|7|8 */
            if (x <= -D100) {
                return 5;
            }
            if (x <= -D20) {
                return 6;
            }
            return x <= D80 ? 7 : 8;
        }
        /* 9|10|11|12 */
        if (x <= -D100) {
            return 9;
        }
        if (x <= -D20) {
            return 10;
        }
        return x <= D80 ? 11 : 12;
    }

    /** {@code igh.cpp:141-176}. Zones 1 and 2 carry the extension lobes. */
    @Override
    protected boolean projectable(int zone, double lam, double phi) {
        switch (zone) {
            case 1:
                return (lam >= -D180 - EPSLN && lam <= -D40 + EPSLN)
                        || ((lam >= -D40 - EPSLN && lam <= -D10 + EPSLN)
                                && (phi >= D60 - EPSLN && phi <= D90 + EPSLN));
            case 2:
                return (lam >= -D40 - EPSLN && lam <= D180 + EPSLN)
                        || ((lam >= -D180 - EPSLN && lam <= -D160 + EPSLN)
                                && (phi >= D50 - EPSLN && phi <= D90 + EPSLN))
                        || ((lam >= -D50 - EPSLN && lam <= -D40 + EPSLN)
                                && (phi >= D60 - EPSLN && phi <= D90 + EPSLN));
            case 3:
                return lam >= -D180 - EPSLN && lam <= -D40 + EPSLN;
            case 4:
                return lam >= -D40 - EPSLN && lam <= D180 + EPSLN;
            case 5:
            case 9:
                return lam >= -D180 - EPSLN && lam <= -D100 + EPSLN;
            case 6:
            case 10:
                return lam >= -D100 - EPSLN && lam <= -D20 + EPSLN;
            case 7:
            case 11:
                return lam >= -D20 - EPSLN && lam <= D80 + EPSLN;
            case 8:
            case 12:
                return lam >= D80 - EPSLN && lam <= D180 + EPSLN;
            default:
                return false;
        }
    }

    @Override
    public boolean isEqualArea() {
        // Both children are equal-area and the dispatch is a rigid translation of each lobe.
        return true;
    }

    @Override
    public String toString() {
        return "Interrupted Goode Homolosine";
    }
}

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
 * Interrupted Goode Homolosine, oceanic view, {@code +proj=igh_o} — a port of
 * {@code 9.8.1:src/projections/igh_o.cpp}.
 *
 * <p>The same twelve-lobe machine as {@link InterruptedGoodeHomolosineProjection}, with the
 * interruptions moved so that the <em>oceans</em> stay unbroken instead of the continents:
 *
 * <pre>
 *     -180       -90               60           180
 *       +---------+----------------+-------------+    Zones 1,2,3,10,11 &amp; 12:
 *       |1        |2               |3            |      Mollweide
 *       +---------+----------------+-------------+    Zones 4,5,6,7,8 &amp; 9:
 *       |4        |5               |6            |      Sinusoidal
 *     0 +---------+--+-------------+--+----------+
 *       |7           |8               |9         |
 *       +------------+----------------+----------+
 *       |10          |11              |12        |
 *       +------------+----------------+----------+
 *     -180          -60               90        180
 * </pre>
 *
 * <p><b>The lobe numbering is not a permutation of {@code igh}'s.</b> {@code igh} splits each
 * latitude band in two above the equator and in four below; {@code igh_o} splits every band in
 * three. So the two files' {@code switch (z)} bodies are unrelated and are not shareable, which is
 * why upstream duplicated the file and why this class does too — the base class holds the machinery
 * and each subclass holds only its own geometry.
 *
 * <p>Three lobes carry extension lobes here rather than two: 1 accepts {@code [160°, 180°]} above
 * 50&deg;N, 3 accepts {@code [-180°, -160°]} above 50&deg;N, and 11 accepts {@code [90°, 100°]}
 * below 40&deg;S ({@code igh_o.cpp:165-206}).
 *
 * <p>One cosmetic note, in case a future diff against upstream looks alarming: {@code igh_o.cpp}
 * declares {@code d130} <em>after</em> {@code d160} in its constant block, and its inverse's
 * first two lobe branches omit their braces. Neither affects behaviour.
 *
 * @since 1.5.0
 */
public class InterruptedGoodeHomolosineOceanicProjection extends InterruptedProjection {

    private static final long serialVersionUID = -5362289811929065978L;

    private double dy0;

    @Override
    protected void setupZones() {
        allocateZones(12);
        /* sinusoidal zones */
        setupZone(4, sinusoidal(), -D140, 0, -D140);
        setupZone(5, sinusoidal(), -D10, 0, -D10);
        setupZone(6, sinusoidal(), D130, 0, D130);
        setupZone(7, sinusoidal(), -D110, 0, -D110);
        setupZone(8, sinusoidal(), D20, 0, D20);
        setupZone(9, sinusoidal(), D150, 0, D150);

        /* mollweide zones */
        setupZone(1, mollweide(), -D140, 0, -D140);

        /* y0 ? -- igh_o.cpp:298-303. Measured between lobe 1 (moll) and lobe 4 (sinu). */
        ProjCoordinate xy1 = zoneForward(1, 0, IGH_PHI_BOUNDARY, new ProjCoordinate());
        ProjCoordinate xy4 = zoneForward(4, 0, IGH_PHI_BOUNDARY, new ProjCoordinate());
        dy0 = xy4.y - xy1.y;
        setZoneY0(1, dy0);

        /* mollweide zones (cont'd) */
        setupZone(2, mollweide(), -D10, dy0, -D10);
        setupZone(3, mollweide(), D130, dy0, D130);
        setupZone(10, mollweide(), -D110, -dy0, -D110);
        setupZone(11, mollweide(), D20, -dy0, D20);
        setupZone(12, mollweide(), D150, -dy0, D150);
    }

    @Override
    protected double y90() {
        return dy0 + Math.sqrt(2.0);
    }

    @Override
    protected int forwardZone(double lam, double phi) {
        if (phi >= IGH_PHI_BOUNDARY) {
            if (lam <= -D90) {
                return 1;
            }
            return lam >= D60 ? 3 : 2;
        }
        if (phi >= 0) {
            if (lam <= -D90) {
                return 4;
            }
            return lam >= D60 ? 6 : 5;
        }
        if (phi >= -IGH_PHI_BOUNDARY) {
            if (lam <= -D60) {
                return 7;
            }
            return lam >= D90 ? 9 : 8;
        }
        if (lam <= -D60) {
            return 10;
        }
        return lam >= D90 ? 12 : 11;
    }

    @Override
    protected int inverseZone(double x, double y) {
        final double y90 = y90();
        if (y > y90 + EPSLN || y < -y90 + EPSLN) {
            return 0;
        }
        if (y >= IGH_PHI_BOUNDARY) {
            if (x <= -D90) {
                return 1;
            }
            return x >= D60 ? 3 : 2;
        }
        if (y >= 0) {
            if (x <= -D90) {
                return 4;
            }
            return x >= D60 ? 6 : 5;
        }
        if (y >= -IGH_PHI_BOUNDARY) {
            if (x <= -D60) {
                return 7;
            }
            return x >= D90 ? 9 : 8;
        }
        if (x <= -D60) {
            return 10;
        }
        return x >= D90 ? 12 : 11;
    }

    /** {@code igh_o.cpp:165-208}. Zones 1, 3 and 11 carry the extension lobes. */
    @Override
    protected boolean projectable(int zone, double lam, double phi) {
        switch (zone) {
            case 1:
                return (lam >= -D180 - EPSLN && lam <= -D90 + EPSLN)
                        || ((lam >= D160 - EPSLN && lam <= D180 + EPSLN)
                                && (phi >= D50 - EPSLN && phi <= D90 + EPSLN));
            case 2:
                return lam >= -D90 - EPSLN && lam <= D60 + EPSLN;
            case 3:
                return (lam >= D60 - EPSLN && lam <= D180 + EPSLN)
                        || ((lam >= -D180 - EPSLN && lam <= -D160 + EPSLN)
                                && (phi >= D50 - EPSLN && phi <= D90 + EPSLN));
            case 4:
                return lam >= -D180 - EPSLN && lam <= -D90 + EPSLN;
            case 5:
                return lam >= -D90 - EPSLN && lam <= D60 + EPSLN;
            case 6:
                return lam >= D60 - EPSLN && lam <= D180 + EPSLN;
            case 7:
            case 10:
                return lam >= -D180 - EPSLN && lam <= -D60 + EPSLN;
            case 8:
                return lam >= -D60 - EPSLN && lam <= D90 + EPSLN;
            case 9:
            case 12:
                return lam >= D90 - EPSLN && lam <= D180 + EPSLN;
            case 11:
                return (lam >= -D60 - EPSLN && lam <= D90 + EPSLN)
                        || ((lam >= D90 - EPSLN && lam <= D100 + EPSLN)
                                && (phi >= -D90 - EPSLN && phi <= -D40 + EPSLN));
            default:
                return false;
        }
    }

    @Override
    public boolean isEqualArea() {
        return true;
    }

    @Override
    public String toString() {
        return "Interrupted Goode Homolosine Oceanic View";
    }
}

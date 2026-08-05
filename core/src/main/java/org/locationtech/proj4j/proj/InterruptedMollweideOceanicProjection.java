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
 * Interrupted Mollweide, oceanic view, {@code +proj=imoll_o} — a port of
 * {@code 9.8.1:src/projections/imoll_o.cpp}.
 *
 * <p>Six lobes, three per hemisphere, placed to keep the oceans unbroken:
 *
 * <pre>
 *     -180       -90               60           180
 *       +---------+----------------+-------------+
 *       |1        |2               |3            |
 *     0 +---------+--+-------------+--+----------+
 *       |4           |5               |6         |
 *       +------------+----------------+----------+
 *     -180          -60               90        180
 * </pre>
 *
 * <p>Two differences from {@link InterruptedMollweideProjection} beyond the geometry, both easy to
 * transcribe wrongly:
 *
 * <ul>
 * <li><b>The seam abscissae are a different set.</b> {@code imoll} measures
 *     {@code boundary12/34/45/56}; {@code imoll_o} measures {@code boundary12/23/45/56} — the
 *     northern hemisphere has two seams here rather than one, and the {@code 34} seam does not
 *     exist because 3 and 4 are in different hemispheres.</li>
 * <li><b>The lobe selection uses {@code >=} on the upper boundaries, not {@code <=} on the
 *     lower.</b> {@code if (x <= boundary12) 1; else if (x >= boundary23) 3; else 2;} — a
 *     three-way split written as two tests against opposite ends, so the middle lobe is the
 *     fall-through. Rewriting it as a monotone cascade changes which lobe claims a point sitting
 *     exactly on a seam.</li>
 * </ul>
 *
 * <p>And the five {@code x_0} corrections chain differently: 2 to 1, then <b>3 to 2</b>, then 4 to
 * 1, 5 to 2 and 6 to <b>3</b> — so lobe 6 depends on lobe 3, which depends on lobe 2. Reordering
 * them silently shifts a lobe by the width of an ocean.
 *
 * @since 1.5.0
 */
public class InterruptedMollweideOceanicProjection extends InterruptedProjection {

    private static final long serialVersionUID = -7814818453353921143L;

    private double boundary12;
    private double boundary23;
    private double boundary45;
    private double boundary56;

    @Override
    protected void setupZones() {
        allocateZones(6);
        setupZone(1, mollweide(), -D140, 0, -D140);
        setupZone(2, mollweide(), -D10, 0, -D10);
        setupZone(3, mollweide(), D130, 0, D130);
        setupZone(4, mollweide(), -D110, 0, -D110);
        setupZone(5, mollweide(), D20, 0, D20);
        setupZone(6, mollweide(), D150, 0, D150);

        /* Adjust zones -- imoll_o.cpp:274-293, in this order. */
        /* Match 2 (center) to 1 (west) */
        addZoneX0(2, zoneOffset(2, 1, -D90, 0.0 + EPSLN, 0.0 + EPSLN));
        /* Match 3 (east) to 2 (center) -- after 2 has been corrected. */
        addZoneX0(3, zoneOffset(3, 2, D60, 0.0 + EPSLN, 0.0 + EPSLN));
        /* Match 4 (south) to 1 (north). NOTE the meridian is -180, not -110. */
        addZoneX0(4, zoneOffset(4, 1, -D180, 0.0 - EPSLN, 0.0 + EPSLN));
        /* Match 5 (south) to 2 (north) */
        addZoneX0(5, zoneOffset(5, 2, -D60, 0.0 - EPSLN, 0.0 + EPSLN));
        /* Match 6 (south) to 3 (north) -- to 3, not to 2. */
        addZoneX0(6, zoneOffset(6, 3, D90, 0.0 - EPSLN, 0.0 + EPSLN));

        /* imoll_o.cpp:300-303 -- boundary23 where imoll has boundary34. */
        boundary12 = zoneXBoundary(-D90, 0.0 + EPSLN);
        boundary23 = zoneXBoundary(D60, 0.0 + EPSLN);
        boundary45 = zoneXBoundary(-D60, 0.0 - EPSLN);
        boundary56 = zoneXBoundary(D90, 0.0 - EPSLN);
    }

    private double zoneOffset(int zone1, int zone2, double lam, double phi1, double phi2) {
        ProjCoordinate xy1 = zoneForward(zone1, lam, phi1, new ProjCoordinate());
        ProjCoordinate xy2 = zoneForward(zone2, lam, phi2, new ProjCoordinate());
        return xy2.x - xy1.x;
    }

    private double zoneXBoundary(double lam, double phi) {
        ProjCoordinate xy1 = dispatchForward(lam - EPSLN, phi, new ProjCoordinate());
        ProjCoordinate xy2 = dispatchForward(lam + EPSLN, phi, new ProjCoordinate());
        return (xy1.x + xy2.x) / 2.;
    }

    @Override
    protected double y90() {
        return Math.sqrt(2.0);
    }

    @Override
    protected int forwardZone(double lam, double phi) {
        if (phi >= 0) { /* 1|2|3 */
            if (lam <= -D90) {
                return 1;
            }
            return lam >= D60 ? 3 : 2;
        }
        /* 4|5|6 */
        if (lam <= -D60) {
            return 4;
        }
        return lam >= D90 ? 6 : 5;
    }

    @Override
    protected int inverseZone(double x, double y) {
        final double y90 = y90();
        if (y > y90 + EPSLN || y < -y90 + EPSLN) {
            return 0;
        }
        if (y >= 0) {
            if (x <= boundary12) {
                return 1;
            }
            return x >= boundary23 ? 3 : 2;
        }
        if (x <= boundary45) {
            return 4;
        }
        return x >= boundary56 ? 6 : 5;
    }

    /** {@code imoll_o.cpp:129-157}. As {@code imoll}, every lobe tests the hemisphere too. */
    @Override
    protected boolean projectable(int zone, double lam, double phi) {
        switch (zone) {
            case 1:
                return lam >= -D180 - EPSLN && lam <= -D90 + EPSLN && phi >= 0.0 - EPSLN;
            case 2:
                return lam >= -D90 - EPSLN && lam <= D60 + EPSLN && phi >= 0.0 - EPSLN;
            case 3:
                return lam >= D60 - EPSLN && lam <= D180 + EPSLN && phi >= 0.0 - EPSLN;
            case 4:
                return lam >= -D180 - EPSLN && lam <= -D60 + EPSLN && phi <= 0.0 + EPSLN;
            case 5:
                return lam >= -D60 - EPSLN && lam <= D90 + EPSLN && phi <= 0.0 + EPSLN;
            case 6:
                return lam >= D90 - EPSLN && lam <= D180 + EPSLN && phi <= 0.0 + EPSLN;
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
        return "Interrupted Mollweide Oceanic View";
    }
}

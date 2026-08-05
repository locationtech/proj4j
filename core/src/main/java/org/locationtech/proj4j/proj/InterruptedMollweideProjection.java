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
 * Interrupted Mollweide, {@code +proj=imoll} — a port of
 * {@code 9.8.1:src/projections/imoll.cpp}.
 *
 * <p>Six lobes, Mollweide everywhere:
 *
 * <pre>
 *     -180            -40                       180
 *       +--------------+-------------------------+
 *       |1             |2                        |
 *     0 +-------+------+-+-----------+-----------+
 *       |3      |4       |5          |6          |
 *       +-------+--------+-----------+-----------+
 *     -180    -100      -20         80          180
 * </pre>
 *
 * <h2>{@code imoll} is the better-engineered member of the family</h2>
 *
 * <p>Where {@code igh}'s inverse compares plane coordinates against radian angles, {@code imoll}
 * <b>measures</b> everything at setup time by running its own forward:
 *
 * <ul>
 * <li><b>Five {@code x_0} corrections</b> ({@code compute_zone_offset},
 *     {@code imoll.cpp:211-224}): each lobe's easting is nudged so that its edge coincides with its
 *     neighbour's at the seam, evaluated at {@code phi = ±EPSLN} on either side of the equator.
 *     The order is load-bearing — 3 to 1, then 2 to 1, then 4 to 1, then 5 to 2, then 6 to 2 —
 *     because 5 and 6 are matched to lobe 2 <em>after</em> lobe 2 has itself been corrected.</li>
 * <li><b>Four seam abscissae</b> ({@code compute_zone_x_boundary}, {@code imoll.cpp:226-237}): the
 *     forward is evaluated at {@code lam ± EPSLN} either side of each seam and the two eastings
 *     averaged. Those are the values the inverse's lobe selection compares against — real plane
 *     coordinates, not angles.</li>
 * </ul>
 *
 * <p>All nine numbers are therefore derived, never tabulated. Writing any of them out as a decimal
 * would be re-deriving a constant, which is the failure mode that has cost this project four
 * separate defects.
 *
 * <p><b>{@code compute_zone_x_boundary} calls the whole forward, not one lobe</b>, so it depends on
 * {@link #forwardZone} already being correct and on the five {@code x_0} corrections already having
 * been applied. Hence the two phases in {@link #setupZones()} and their order.
 *
 * @since 1.5.0
 */
public class InterruptedMollweideProjection extends InterruptedProjection {

    private static final long serialVersionUID = 3526944502198214489L;

    private double boundary12;
    private double boundary34;
    private double boundary45;
    private double boundary56;

    @Override
    protected void setupZones() {
        allocateZones(6);
        setupZone(1, mollweide(), -D100, 0, -D100);
        setupZone(2, mollweide(), D30, 0, D30);
        setupZone(3, mollweide(), -D160, 0, -D160);
        setupZone(4, mollweide(), -D60, 0, -D60);
        setupZone(5, mollweide(), D20, 0, D20);
        setupZone(6, mollweide(), D140, 0, D140);

        /* Adjust zones -- imoll.cpp:258-277, in this order. */
        /* Match 3 (south) to 1 (north) */
        addZoneX0(3, zoneOffset(3, 1, -D160, 0.0 - EPSLN, 0.0 + EPSLN));
        /* Match 2 (north-east) to 1 (north-west) */
        addZoneX0(2, zoneOffset(2, 1, -D40, 0.0 + EPSLN, 0.0 + EPSLN));
        /* Match 4 (south) to 1 (north) */
        addZoneX0(4, zoneOffset(4, 1, -D100, 0.0 - EPSLN, 0.0 + EPSLN));
        /* Match 5 (south) to 2 (north) */
        addZoneX0(5, zoneOffset(5, 2, -D20, 0.0 - EPSLN, 0.0 + EPSLN));
        /* Match 6 (south) to 2 (north) */
        addZoneX0(6, zoneOffset(6, 2, D80, 0.0 - EPSLN, 0.0 + EPSLN));

        /* imoll.cpp:284-287 -- the seams, measured through the whole forward. */
        boundary12 = zoneXBoundary(-D40, 0.0 + EPSLN);
        boundary34 = zoneXBoundary(-D100, 0.0 - EPSLN);
        boundary45 = zoneXBoundary(-D20, 0.0 - EPSLN);
        boundary56 = zoneXBoundary(D80, 0.0 - EPSLN);
    }

    /**
     * {@code compute_zone_offset}: how far lobe {@code zone2}'s easting is from lobe
     * {@code zone1}'s at the shared meridian {@code lam}, each evaluated at its own side of the
     * seam.
     */
    private double zoneOffset(int zone1, int zone2, double lam, double phi1, double phi2) {
        ProjCoordinate xy1 = zoneForward(zone1, lam, phi1, new ProjCoordinate());
        ProjCoordinate xy2 = zoneForward(zone2, lam, phi2, new ProjCoordinate());
        return xy2.x - xy1.x;
    }

    /**
     * {@code compute_zone_x_boundary}: the easting of a seam, as the mean of the whole forward
     * evaluated a hair either side of it.
     */
    private double zoneXBoundary(double lam, double phi) {
        ProjCoordinate xy1 = dispatchForward(lam - EPSLN, phi, new ProjCoordinate());
        ProjCoordinate xy2 = dispatchForward(lam + EPSLN, phi, new ProjCoordinate());
        return (xy1.x + xy2.x) / 2.;
    }

    /** {@code lt=90} corresponds to {@code y = sqrt(2)}; unlike {@code igh} there is no
     * {@code dy0} to add, because every lobe is Mollweide. */
    @Override
    protected double y90() {
        return Math.sqrt(2.0);
    }

    @Override
    protected int forwardZone(double lam, double phi) {
        if (phi >= 0) { /* 1|2 */
            return lam <= -D40 ? 1 : 2;
        }
        /* 3|4|5|6 */
        if (lam <= -D100) {
            return 3;
        }
        if (lam <= -D20) {
            return 4;
        }
        return lam <= D80 ? 5 : 6;
    }

    @Override
    protected int inverseZone(double x, double y) {
        final double y90 = y90();
        // The asymmetric band is upstream's; see InterruptedProjection's class comment.
        if (y > y90 + EPSLN || y < -y90 + EPSLN) {
            return 0;
        }
        if (y >= 0) { /* 1|2 */
            return x <= boundary12 ? 1 : 2;
        }
        if (x <= boundary34) {
            return 3;
        }
        if (x <= boundary45) {
            return 4;
        }
        return x <= boundary56 ? 5 : 6;
    }

    /**
     * {@code imoll.cpp:118-146}. Every lobe tests <b>both</b> a longitude range and the sign of the
     * latitude, which {@code igh}'s does not — {@code imoll} has only one latitude band per
     * hemisphere, so the hemisphere itself is part of the lobe's identity.
     */
    @Override
    protected boolean projectable(int zone, double lam, double phi) {
        switch (zone) {
            case 1:
                return lam >= -D180 - EPSLN && lam <= -D40 + EPSLN && phi >= 0.0 - EPSLN;
            case 2:
                return lam >= -D40 - EPSLN && lam <= D180 + EPSLN && phi >= 0.0 - EPSLN;
            case 3:
                return lam >= -D180 - EPSLN && lam <= -D100 + EPSLN && phi <= 0.0 + EPSLN;
            case 4:
                return lam >= -D100 - EPSLN && lam <= -D20 + EPSLN && phi <= 0.0 + EPSLN;
            case 5:
                return lam >= -D20 - EPSLN && lam <= D80 + EPSLN && phi <= 0.0 + EPSLN;
            case 6:
                return lam >= D80 - EPSLN && lam <= D180 + EPSLN && phi <= 0.0 + EPSLN;
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
        return "Interrupted Mollweide";
    }
}

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

import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;

/**
 * The shared machinery of the four interrupted pseudo-cylindricals —
 * {@code 9.8.1:src/projections/igh.cpp}, {@code igh_o.cpp}, {@code imoll.cpp} and
 * {@code imoll_o.cpp}.
 *
 * <p>All four are <b>lobe dispatchers</b>. Each holds an array of child projections — Mollweide,
 * and for the {@code igh} pair also sinusoidal — each pinned to its own central meridian and false
 * origin. The forward picks a lobe from the input {@code (lam, phi)}, shifts to that lobe's central
 * meridian, calls the child's <em>raw</em> forward, and adds the lobe's offset. The inverse does the
 * reverse and then <b>re-validates</b>: it checks that the longitude it recovered actually belongs
 * to the lobe it came out of, and rejects the point if not.
 *
 * <h2>That re-validation is the whole point, and it must reject rather than snap</h2>
 *
 * <p>An interrupted projection has gaps — regions of the plane that are not the image of any point
 * on the sphere. The plane coordinate still lands inside some lobe's bounding box, so the naive
 * inverse produces a perfectly plausible longitude in the wrong lobe. Upstream answers
 * {@code HUGE_VAL}, which {@code inv_finalize} turns into
 * {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN}; this class throws
 * {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN}. Snapping the point into the nearest lobe would be a
 * silent wrong answer of up to a full lobe width — thousands of kilometres.
 *
 * <h2>Two upstream quirks reproduced verbatim</h2>
 *
 * <ul>
 * <li><b>The inverse's lobe selection compares projected coordinates against <em>angles</em>.</b>
 *     {@code igh.cpp:104-107} tests {@code xy.y >= igh_phi_boundary} and {@code xy.x <= -d40},
 *     where the left-hand sides are unit-sphere plane coordinates and the right-hand sides are
 *     radian latitudes and longitudes. That happens to work because the plane coordinates are in
 *     units of the semi-major axis and are numerically the same order, but it is not a coordinate
 *     comparison in any meaningful sense. It is what generated the corpus, so it is what is
 *     written here. ({@code imoll} is the tidier design: it computes its {@code boundary*} values
 *     by actually running its own forward at the seams.)</li>
 * <li><b>The out-of-map test is asymmetric.</b> {@code if (xy.y > y90 + EPSLN || xy.y < -y90 +
 *     EPSLN)} — the second term is {@code (-y90) + EPSLN}, not {@code -(y90 + EPSLN)}, so the
 *     band is {@code 1e-10} wider at the top than at the bottom and southern points inside
 *     {@code [-y90, -y90 + 1e-10)} are rejected. Almost certainly an upstream typo; it is
 *     reproduced, not corrected.</li>
 * </ul>
 *
 * <h2>The children are called raw, and need no hook</h2>
 *
 * <p>{@code Q->pj[z]->fwd(lp, Q->pj[z])} is the child's raw kernel, so its {@code lam0},
 * {@code a} and {@code x_0} are never applied — the dispatcher applies its own. {@code project} and
 * {@code projectInverse} are {@code protected}, hence package-visible, so
 * {@link MolleweideProjection} and {@link SinusoidalProjection} can be driven directly, exactly as
 * {@link SpilhausProjection} and {@link ObliqueTransformationProjection} do with their children.
 *
 * @since 1.5.0
 */
abstract class InterruptedProjection extends Projection {

    private static final long serialVersionUID = -7383231914775336093L;

    /** {@code EPSLN}: "allow a little 'slack' on zone edge positions". All four files, {@code 1e-10}. */
    protected static final double EPSLN = 1.e-10;

    protected static final double D10 = 10 * DTR;
    protected static final double D20 = 20 * DTR;
    protected static final double D30 = 30 * DTR;
    protected static final double D40 = 40 * DTR;
    protected static final double D50 = 50 * DTR;
    protected static final double D60 = 60 * DTR;
    protected static final double D80 = 80 * DTR;
    protected static final double D90 = 90 * DTR;
    protected static final double D100 = 100 * DTR;
    protected static final double D110 = 110 * DTR;
    protected static final double D130 = 130 * DTR;
    protected static final double D140 = 140 * DTR;
    protected static final double D150 = 150 * DTR;
    protected static final double D160 = 160 * DTR;
    protected static final double D180 = 180 * DTR;

    /**
     * The Sinusoidal-to-Mollweide transition latitude of the {@code igh} pair,
     * <b>40&deg;44'11.8"</b> ({@code igh.cpp:32}).
     * <p>
     * Written as upstream writes it — {@code (40 + 44/60. + 11.8/3600.) * DEG_TO_RAD} — rather than
     * as a decimal, so the value is whatever that expression evaluates to and not a rounding of it.
     */
    protected static final double IGH_PHI_BOUNDARY =
            (40 + 44 / 60. + 11.8 / 3600.) * DTR;

    private Projection[] zoneProjection;
    private double[] zoneX0;
    private double[] zoneY0;
    private double[] zoneLam0;

    /** Scratch for the child's result. */
    private final ProjCoordinate child = new ProjCoordinate();

    // ------------------------------------------------------------------------------------------
    // Contract for the four dispatchers
    // ------------------------------------------------------------------------------------------

    /** Allocates and configures every zone. Called from {@link #initialize()}. */
    protected abstract void setupZones();

    /**
     * The lobe a geographic point belongs to, 1-based. Never 0: every point on the sphere is in
     * some lobe.
     */
    protected abstract int forwardZone(double lam, double phi);

    /**
     * The lobe a plane point provisionally belongs to, 1-based, or <b>0</b> for "off the map",
     * which is the out-of-{@code y90} band.
     */
    protected abstract int inverseZone(double x, double y);

    /**
     * Whether the longitude and latitude recovered from lobe {@code zone} really belong to it — the
     * {@code switch (z)} of each file's inverse. {@code false} means the plane point was in an
     * interruption gap.
     */
    protected abstract boolean projectable(int zone, double lam, double phi);

    // ------------------------------------------------------------------------------------------
    // Zone bookkeeping
    // ------------------------------------------------------------------------------------------

    protected final void allocateZones(int count) {
        zoneProjection = new Projection[count];
        zoneX0 = new double[count];
        zoneY0 = new double[count];
        zoneLam0 = new double[count];
    }

    /**
     * {@code setup_zone}: install a child for lobe {@code zone} (1-based) with its own false origin
     * and central meridian.
     */
    protected final void setupZone(int zone, Projection projection, double x0, double y0,
            double lam0) {
        projection.initialize();
        zoneProjection[zone - 1] = projection;
        zoneX0[zone - 1] = x0;
        zoneY0[zone - 1] = y0;
        zoneLam0[zone - 1] = lam0;
    }

    protected final double zoneX0(int zone) {
        return zoneX0[zone - 1];
    }

    protected final double zoneY0(int zone) {
        return zoneY0[zone - 1];
    }

    protected final double zoneLam0(int zone) {
        return zoneLam0[zone - 1];
    }

    protected final void addZoneX0(int zone, double delta) {
        zoneX0[zone - 1] += delta;
    }

    protected final void setZoneY0(int zone, double y0) {
        zoneY0[zone - 1] = y0;
    }

    /** A fresh {@code moll} child: {@code pj_moll} is {@code moll_setup(P, M_HALFPI)}. */
    protected static Projection mollweide() {
        return new MolleweideProjection();
    }

    /** A fresh {@code sinu} child. */
    protected static Projection sinusoidal() {
        return new SinusoidalProjection();
    }

    /**
     * One lobe's raw forward including its false origin, in unit-sphere units — the body of each
     * file's forward once the lobe is known, and also the primitive {@code imoll}'s
     * {@code compute_zone_offset}/{@code compute_zone_x_boundary} are built from.
     */
    protected final ProjCoordinate zoneForward(int zone, double lam, double phi,
            ProjCoordinate xy) {
        zoneProjection[zone - 1].project(lam - zoneLam0[zone - 1], phi, xy);
        xy.x += zoneX0[zone - 1];
        xy.y += zoneY0[zone - 1];
        return xy;
    }

    /**
     * The full forward including lobe selection, in unit-sphere units. {@code imoll}'s
     * {@code compute_zone_x_boundary} calls the whole forward, not one lobe, so this is separate
     * from {@link #zoneForward}.
     */
    protected final ProjCoordinate dispatchForward(double lam, double phi, ProjCoordinate xy) {
        return zoneForward(forwardZone(lam, phi), lam, phi, xy);
    }

    /**
     * The upper edge of the map in unit-sphere units: {@code lat = 90} maps to
     * {@code y = sqrt(2)} for Mollweide, offset by the lobe's own {@code y0} for the
     * {@code igh} pair.
     */
    protected abstract double y90();

    // ------------------------------------------------------------------------------------------
    // Projection
    // ------------------------------------------------------------------------------------------

    /** All four force {@code P->es = 0.}: these are spherical projections only. */
    @Override
    public void initialize() {
        es = 0.;
        e = 0.;
        super.initialize();
        setupZones();
    }

    @Override
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate xy) {
        return dispatchForward(lam, phi, xy);
    }

    /**
     * The shared inverse: select a lobe, undo its offsets, invert the child, restore its central
     * meridian, then re-validate.
     *
     * @throws ProjectionException {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN} where upstream
     *         returns {@code HUGE_VAL} — off the top or bottom of the map, or inside an
     *         interruption gap
     */
    @Override
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
        final int zone = inverseZone(x, y);
        if (zone == 0) {
            throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                    "(" + x + ", " + y + ") is above or below the map of " + toString()
                            + ": |y| exceeds " + y90() + " in units of the semi-major axis");
        }
        zoneProjection[zone - 1].projectInverse(x - zoneX0[zone - 1], y - zoneY0[zone - 1],
                child);
        final double lam = child.x + zoneLam0[zone - 1];
        final double phi = child.y;
        if (!projectable(zone, lam, phi)) {
            throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                    "(" + x + ", " + y + ") lies in an interruption gap of " + toString()
                            + ": lobe " + zone + " inverts it to (" + (lam * RTD) + ", "
                            + (phi * RTD) + ") deg, which is outside that lobe. Snapping it to "
                            + "the nearest lobe would be wrong by up to a lobe width.");
        }
        lp.x = lam;
        lp.y = phi;
        return lp;
    }

    @Override
    public boolean hasInverse() {
        return true;
    }
}

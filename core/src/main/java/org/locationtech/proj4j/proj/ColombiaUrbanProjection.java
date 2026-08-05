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
 * Colombia Urban ({@code +proj=col_urban}), a port of
 * {@code 9.8.1:src/projections/col_urban.cpp}. Ellipsoidal, closed form both ways.
 *
 * <p>Notation and formulas are IOGP Publication 373-7-2 (Geomatics Guidance Note 7 part 2,
 * March 2020) method 1052. It is a local projection for Colombian city grids: an
 * equidistant-cylindrical-like map with a second-order easting correction to the northing
 * and, unusually, an explicit <b>height of the projection origin</b> that scales the whole
 * grid so that distances are true at working elevation rather than at the ellipsoid.
 *
 * <h2>Everything is divided by {@code a}</h2>
 *
 * <p>Upstream deliberately departs from the Guidance Note by holding {@code h0},
 * {@code rho0}, {@code B} and {@code D} <b>adimensionally</b> — {@code h0} is the origin
 * height divided by the semi-major axis, and {@code rho0}/{@code nu0} are the radii of
 * curvature divided by it too ({@code col_urban.cpp:18-23} says so in as many words). The
 * {@code a} factor is reintroduced once, by {@link Projection}'s {@code totalScale}. Do not
 * "restore" the dimensional forms: the intermediate {@code lam * nu * cosphi} would then be
 * in metres and {@code B}'s units would no longer match it.
 *
 * <pre>
 *   setup:  h0   = h_0 / a
 *           nu0  = 1 / sqrt(1 - es sin^2 phi0)
 *           A    = 1 + h0 / nu0
 *           rho0 = (1 - es) / (1 - es sin^2 phi0)^1.5
 *           B    = tan(phi0) / (2 rho0 nu0)
 *           C    = 1 + h0
 *           D    = rho0 (1 + h0 / (1 - es))
 * </pre>
 *
 * <h2>The forward is not the algebraic inverse of the inverse</h2>
 *
 * <p>This is the one thing to be careful about, and it is upstream's design, not a defect.
 * The forward ({@code col_urban.cpp:27-44}) recomputes a <b>mid-latitude</b> radius of
 * curvature from {@code sin(0.5 * (phi + phi0))} and forms {@code G = 1 + h0 / rho_m},
 * using {@code G * rho0} as the northing scale. The inverse ({@code :46-56}) uses the
 * constant {@code D} instead, which is the same quantity evaluated at {@code phi = phi0}.
 * So forward-then-inverse is not exactly the identity away from the origin latitude; the
 * discrepancy is of order {@code h0 * (phi - phi0)^2} and is sub-millimetre over the
 * few tens of kilometres the method is scoped to. The corpus asserts
 * {@code roundtrip 1} at {@code tolerance 1 mm} for a point 0.12&deg; from the origin,
 * which this satisfies. <b>Do not symmetrise the two directions</b> — using {@code D} in
 * the forward, or {@code G} in the inverse, moves the forward off the expected easting and
 * northing.
 *
 * <h2>{@code +h_0}</h2>
 *
 * <p>{@code Proj4Keyword} defines {@code h_0} separately from {@code h} (the
 * {@code geos}/{@code nsper} orbit height) and {@code Proj4Parser} dispatches it on this concrete
 * class, because {@code col_urban} is the only operator that reads it. Leaving it out gives
 * {@code h0 = 0}, which is a well-defined but different projection — the ellipsoid-level variant,
 * out by about 40 cm per 100 km — so a definition that means the surface variant must say so.
 *
 * <p>{@code h_0} is read by upstream as {@code "dh_0"} — the {@code d} prefix meaning a
 * plain number, <em>not</em> an angle and <em>not</em> unit-converted — so it is always
 * metres regardless of {@code +units}.
 */
public class ColombiaUrbanProjection extends Projection {

    private static final long serialVersionUID = -4338060266902769790L;

    /** {@code +h_0}: height of the projection origin, in metres. Default 0. */
    private double h0Metres = 0.0;

    // Setup-derived, all adimensional. col_urban.cpp:65-73.
    private double h0;
    private double A;
    private double rho0;
    private double B;
    private double C;
    private double D;

    /**
     * Sets {@code +h_0}, the height of the projection origin in metres.
     *
     * <p>This is what {@code Proj4Parser} calls for {@code +h_0}. Call before
     * {@link #initialize()}.
     *
     * @param h0Metres origin height in metres
     */
    public void setH0(double h0Metres) {
        this.h0Metres = h0Metres;
    }

    /** @return {@code +h_0} in metres */
    public double getH0() {
        return h0Metres;
    }

    /** {@code PJ_PROJECTION(col_urban)}, {@code col_urban.cpp:58-79}. */
    @Override
    public void initialize() {
        super.initialize();
        h0 = h0Metres / a;
        final double sinphi0 = StrictMath.sin(projectionLatitude);
        final double nu0 = 1.0 / Math.sqrt(1.0 - es * sinphi0 * sinphi0);
        A = 1.0 + h0 / nu0;
        rho0 = (1.0 - es) / StrictMath.pow(1.0 - es * sinphi0 * sinphi0, 1.5);
        B = StrictMath.tan(projectionLatitude) / (2.0 * rho0 * nu0);
        C = 1.0 + h0;
        D = rho0 * (1.0 + h0 / (1.0 - es));
    }

    /** {@code col_urban_forward}, {@code col_urban.cpp:27-44}. */
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate dst) {
        final double cosphi = StrictMath.cos(phi);
        final double sinphi = StrictMath.sin(phi);
        final double nu = 1.0 / Math.sqrt(1.0 - es * sinphi * sinphi);
        final double lamNuCosphi = lam * nu * cosphi;
        dst.x = A * lamNuCosphi;
        final double sinphiM = StrictMath.sin(0.5 * (phi + projectionLatitude));
        final double rhoM = (1.0 - es) / StrictMath.pow(1.0 - es * sinphiM * sinphiM, 1.5);
        final double g = 1.0 + h0 / rhoM;
        dst.y = g * rho0 * ((phi - projectionLatitude) + B * lamNuCosphi * lamNuCosphi);
        return dst;
    }

    /** {@code col_urban_inverse}, {@code col_urban.cpp:46-56}. */
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate dst) {
        final double xOverC = x / C;
        final double phi = projectionLatitude + y / D - B * xOverC * xOverC;
        final double sinphi = StrictMath.sin(phi);
        final double nu = 1.0 / Math.sqrt(1.0 - es * sinphi * sinphi);
        dst.y = phi;
        dst.x = x / (C * nu * StrictMath.cos(phi));
        return dst;
    }

    public boolean hasInverse() {
        return true;
    }

    public String toString() {
        return "Colombia Urban";
    }
}

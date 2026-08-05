/*******************************************************************************
 * Copyright 2006, 2017 Jerry Huxtable, Martin Davis
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
 */

/*
 * This file was semi-automatically converted from the public-domain USGS PROJ source.
 */
package org.locationtech.proj4j.proj;

import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.util.MeridianArc;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * The Equidistant Conic projection, {@code +proj=eqdc}.
 *
 * <h2>Why this class was rewritten</h2>
 *
 * <p>Until 1.5.0 {@code +proj=eqdc} was <b>a pure identity in both directions</b>, while
 * {@link #hasInverse()} reported {@code true}. The cause was a signature mismatch, not a formula
 * error: the class overrode the <em>public, degrees-in/degrees-out</em>
 * {@code project(ProjCoordinate, ProjCoordinate)} and
 * {@code inverseProject(ProjCoordinate, ProjCoordinate)} rather than the <em>protected radian
 * hooks</em> {@link Projection#project(double, double, ProjCoordinate)} and
 * {@link Projection#projectInverse(double, double, ProjCoordinate)} that
 * {@code BasicCoordinateTransform} actually calls. The overrides were therefore dead code, and
 * every {@code eqdc} coordinate fell through to {@code Projection}'s base implementations, which
 * are {@code dst.x = x; dst.y = y}.
 *
 * <p>The dead code was also wrong twice over, which is why it is replaced rather than merely
 * re-signed:
 * <ul>
 * <li>it read {@code in.y} as radians while the public {@code project} contract is degrees, and
 *     it subtracted {@code projectionLongitude} a second time on top of the subtraction the base
 *     class had already performed;</li>
 * <li>its formula was Lambert Conformal Conic's — {@code tan(pi/4 - phi/2)} raised to
 *     {@code n} — not Equidistant Conic's meridian arc; its eccentricity was hard-coded to
 *     {@code 0.822719} (a misplaced decimal point: Clarke 1866's {@code e} is
 *     {@code 0.0822719}); its radius was hard-coded to 1; and {@code +lat_1}/{@code +lat_2} were
 *     ignored, because the derivation ran only from the constructor and never from
 *     {@link #initialize()}, which is what {@code Proj4Parser} calls after assigning
 *     parameters.</li>
 * </ul>
 *
 * <p>This is now a port of PROJ 9.8.1 {@code src/projections/eqdc.cpp}, meridian arc included.
 * The non-convergence fail-open it used to carry — a Newton loop whose accumulator {@code phi}
 * was initialised to {@code 0}, so a zero-iteration exit returned <b>latitude 0</b>: on the
 * equator, plausible, and undetectable — is gone by construction. Upstream's inverse uses
 * {@code pj_inv_mlfn}, and {@link MeridianArc#invMlfn} is a <b>closed-form</b> Clenshaw
 * evaluation with no iteration that can fail.
 *
 * @see <a href="https://github.com/OSGeo/PROJ/blob/9.8.1/src/projections/eqdc.cpp">9.8.1
 *      eqdc.cpp</a>
 */
public class EquidistantConicProjection extends ConicProjection {

    private static final long serialVersionUID = 2859752487579111302L;

    /** Upstream's {@code EPS10}. */
    private static final double EPS10 = 1.e-10;

    /** The cone constant, upstream's {@code Q->n}. */
    private double n;
    /** Upstream's {@code Q->c}. */
    private double c;
    /** Upstream's {@code Q->rho0}. */
    private double rho0;
    /** Upstream's {@code Q->ellips}. */
    private boolean ellips;
    /** Upstream's {@code Q->en}, i.e. what {@code pj_enfn} builds. */
    private MeridianArc meridian;

    /**
     * Legacy defaults, preserved from the 1.4.3 constructor so that a bare {@code +proj=eqdc}
     * still yields a usable cone rather than failing the {@code |lat_1 + lat_2| > 0} check
     * upstream applies. {@code Proj4Parser} assigns {@code +lat_0}/{@code +lat_1}/{@code +lat_2}
     * only when the keyword is present, so an explicit value always wins.
     *
     * <p>The 1.4.3 constructor set the standard parallels with {@code Math.toDegrees(60)} and
     * {@code Math.toDegrees(20)} — 3437.75 and 1145.92 — and fed them to {@code sin} and
     * {@code tan} as though they were radians. That was inert only because the whole derivation
     * was dead code.
     */
    public EquidistantConicProjection() {
        minLatitude = ProjectionMath.degToRad(10);
        maxLatitude = ProjectionMath.degToRad(70);
        minLongitude = ProjectionMath.degToRad(-90);
        maxLongitude = ProjectionMath.degToRad(90);

        // NOT a legacy default: PROJ's phi0 is 0 when +lat_0 is absent, and rho0 is
        // c - mlfn(phi0), so a 37.5 default displaced every northing by M(37.5 deg) =
        // 4,151,999 m. All 16 builtins.gie eqdc assertions missed by exactly that.
        projectionLatitude = 0.0;
        projectionLatitude1 = ProjectionMath.degToRad(60);
        projectionLatitude2 = ProjectionMath.degToRad(20);
        initialize();
    }

    /**
     * Derives the cone from {@code +lat_1}, {@code +lat_2}, {@code +lat_0} and the ellipsoid.
     * Port of {@code PJ_PROJECTION(eqdc)}, including all four of its parameter rejections —
     * which is the fail-closed half of this class: an {@code eqdc} definition that cannot
     * describe a cone never becomes a usable {@code Projection}, so it can never emit a
     * coordinate at all.
     *
     * @throws InvalidValueException if {@code |lat_1|} or {@code |lat_2|} exceeds 90 degrees, if
     *                               {@code |lat_1 + lat_2|} is zero (the cone degenerates), or
     *                               if the eccentricity is so close to 1 that the cone constant
     *                               cannot be formed
     */
    @Override
    public void initialize() {
        super.initialize();

        final double phi1 = projectionLatitude1;
        final double phi2 = projectionLatitude2;

        if (Math.abs(phi1) > ProjectionMath.HALFPI) {
            throw new InvalidValueException(
                    "Invalid value for +lat_1: |lat_1| should be <= 90 degrees, but is "
                            + Math.toDegrees(phi1));
        }
        if (Math.abs(phi2) > ProjectionMath.HALFPI) {
            throw new InvalidValueException(
                    "Invalid value for +lat_2: |lat_2| should be <= 90 degrees, but is "
                            + Math.toDegrees(phi2));
        }
        if (Math.abs(phi1 + phi2) < EPS10) {
            throw new InvalidValueException(
                    "Invalid values for +lat_1 and +lat_2: |lat_1 + lat_2| should be > 0, but "
                            + Math.toDegrees(phi1) + " + " + Math.toDegrees(phi2)
                            + " degrees degenerates the cone");
        }

        double sinphi = Math.sin(phi1);
        double cosphi = Math.cos(phi1);
        n = sinphi;
        final boolean secant = Math.abs(phi1 - phi2) >= EPS10;
        ellips = es > 0.;

        if (ellips) {
            meridian = MeridianArc.fromEs(es);
            final double m1 = ProjectionMath.msfn(sinphi, cosphi, es);
            final double ml1 = meridian.mlfn(phi1, sinphi, cosphi);
            if (secant) {
                sinphi = Math.sin(phi2);
                cosphi = Math.cos(phi2);
                final double ml2 = meridian.mlfn(phi2, sinphi, cosphi);
                if (ml1 == ml2) {
                    throw new InvalidValueException(
                            "Eccentricity is too close to 1: the meridian arc is identical at "
                                    + "+lat_1 and +lat_2, so the cone constant is undefined");
                }
                n = (m1 - ProjectionMath.msfn(sinphi, cosphi, es)) / (ml2 - ml1);
                if (n == 0) {
                    throw new InvalidValueException(
                            "Invalid value for eccentricity: the cone constant evaluates to 0");
                }
            }
            c = ml1 + m1 / n;
            rho0 = c - meridian.mlfn(projectionLatitude);
        } else {
            meridian = null;
            if (secant) {
                n = (cosphi - Math.cos(phi2)) / (phi2 - phi1);
            }
            if (n == 0) {
                throw new InvalidValueException(
                        "Invalid values for +lat_1 and +lat_2: the cone constant evaluates to 0");
            }
            c = phi1 + Math.cos(phi1) / n;
            rho0 = c - projectionLatitude;
        }
    }

    /**
     * Forward projection. Port of {@code eqdc_e_forward}.
     * <p>
     * {@code lam} arrives with {@code +lon_0} already subtracted and normalised by
     * {@link Projection#project(ProjCoordinate, ProjCoordinate)}, which is exactly why this hook
     * must not subtract it again — the replaced code did, and that was one of its two errors.
     *
     * @param lam the longitude relative to the central meridian, radians
     * @param phi the geographic latitude, radians
     * @param out the destination
     * @return {@code out}
     */
    @Override
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate out) {
        final double rho = c - (ellips ? meridian.mlfn(phi, Math.sin(phi), Math.cos(phi)) : phi);
        final double lamMulN = lam * n;
        out.x = rho * Math.sin(lamMulN);
        out.y = rho0 - rho * Math.cos(lamMulN);
        return out;
    }

    /**
     * Inverse projection. Port of {@code eqdc_e_inverse}.
     * <p>
     * Upstream mutates {@code xy.y} inside the {@code hypot} call and reads it back; this uses a
     * local instead, so it cannot fall into the read-the-destination-rather-than-the-mutated-
     * parameter trap the 2006 conversion left in six other projections. {@code out} is never
     * read, only written.
     *
     * @param x   the easting relative to the false easting, in projection units
     * @param y   the northing relative to the false northing, in projection units
     * @param out the destination; its longitude is relative to the central meridian, radians
     * @return {@code out}
     */
    @Override
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate out) {
        double dy = rho0 - y;
        double rho = ProjectionMath.hypot(x, dy);
        if (rho != 0.0) {
            if (n < 0.) {
                rho = -rho;
                x = -x;
                dy = -dy;
            }
            double phi = c - rho;
            if (ellips) {
                phi = meridian.invMlfn(phi);
            }
            out.y = phi;
            out.x = Math.atan2(x, dy) / n;
        } else {
            out.x = 0.;
            out.y = n > 0. ? ProjectionMath.HALFPI : -ProjectionMath.HALFPI;
        }
        return out;
    }

    public boolean hasInverse() {
        return true;
    }

    public String toString() {
        return "Equidistant Conic";
    }

}

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

import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.util.MeridianArc;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * {@code +proj=tmerc} — the transverse Mercator, with both of PROJ's algorithms and PROJ's own
 * choice between them.
 *
 * <h2>Which algorithm runs, and why that is a breaking change</h2>
 *
 * <p>PROJ 9.8.1 ships <b>Poder/Engsager</b> as the built-in default for {@code tmerc}
 * ({@code proj_internal.h:840-841}) and its shipped {@code data/proj.ini} sets
 * {@code tmerc_default_algo = poder_engsager}. Proj4J up to 1.4.3 always ran the older
 * <b>Evenden/Snyder</b> series. The two differ by:
 *
 * <table>
 * <caption>Evenden/Snyder minus Poder/Engsager on GRS80, {@code k_0 = 1}</caption>
 * <tr><th>from the central meridian</th><th>easting difference</th></tr>
 * <tr><td>2&deg;</td><td>about 2 &micro;m</td></tr>
 * <tr><td>6&deg;</td><td>about 0.9 mm</td></tr>
 * <tr><td>20&deg;</td><td>metres</td></tr>
 * <tr><td>45&deg;</td><td>kilometres</td></tr>
 * </table>
 *
 * <p>Poder/Engsager is right — it is about 1 nm anywhere inside 150&deg; — which is why
 * {@code builtins.gie:7095-7127} asserts {@code +proj=tmerc +ellps=GRS80} at
 * {@code tolerance 50 nm} against the <em>same</em> numbers as {@code +proj=etmerc}, including a
 * row 3,900 km out that the Evenden/Snyder series misses by about 1.5 km. <b>Every existing
 * Proj4J user's UTM and State-Plane output moves.</b> That is the fix, not an accident, and the
 * escape hatches below exist so that it can be opted out of rather than discovered.
 *
 * <h2>The escape hatches</h2>
 *
 * <ul>
 * <li>{@link #setApprox(boolean) +approx} — PROJ's documented legacy switch
 *     ({@code tmerc.cpp:558-561}). Checked <em>before</em> {@code +algo} and wins over it.
 * <li>{@link #setAlgorithm(String) +algo=evenden_snyder|poder_engsager|auto}
 *     ({@code tmerc.cpp:563-579}). Any other value is an error, as upstream.
 * <li>A <b>sphere</b> ({@code es == 0}) forces Evenden/Snyder whatever was asked for
 *     ({@code tmerc.cpp:518-519}), because Poder/Engsager is not defined there.
 * </ul>
 *
 * <p><b>{@code +algo=auto} is accepted and resolves to Poder/Engsager.</b> This is a deliberate
 * divergence. Upstream's AUTO picks the approximate series when {@code |lam| <= 3}&deg; forward,
 * or when {@code |x| <= 0.053 - 0.022 y^2} inverse — a data-dependent branch that introduces a
 * discontinuity of up to about 0.1 mm <em>in the output field</em> at the switch boundary. For a
 * consumer doing geometry on the results that is worse than uniform slowness, and
 * {@code builtins.gie:7379-7407} itself only asserts that AUTO agrees with Poder/Engsager to
 * 0.1 mm. Upstream also downgrades AUTO to Poder/Engsager whenever {@code es > 0.1},
 * {@code phi0 != 0} or {@code |k0 - 1| > 0.01} ({@code tmerc.cpp:591-594}).
 *
 * <h2>Layout</h2>
 *
 * <p>The Evenden/Snyder series ({@code approx_e_fwd}, {@code approx_e_inv},
 * {@code tmerc_spherical_fwd}, {@code tmerc_spherical_inv}) is implemented here. Poder/Engsager
 * is not duplicated: it is {@link ExtendedTransverseMercatorProjection}, held as a delegate and
 * driven through its raw kernel, so {@code tmerc} and {@code etmerc} cannot drift apart.
 */
public class TransverseMercatorProjection extends CylindricalProjection {

    private static final long serialVersionUID = -4387042293999017377L;

    /** The choice of algorithm, {@code TMercAlgo} in {@code proj_internal.h:836-842}. */
    public enum Algorithm {

        /**
         * Upstream's data-dependent heuristic. Accepted for parity and <b>resolved to
         * {@link #PODER_ENGSAGER}</b>; see the class comment.
         */
        AUTO,

        /** The approximate series, {@code +approx}. Also forced on a sphere. */
        EVENDEN_SNYDER,

        /** The exact series. PROJ 9.8.1's built-in default, and this class's. */
        PODER_ENGSAGER
    }

    private final static double FC1 = 1.0;
    private final static double FC2 = 0.5;
    private final static double FC3 = 0.16666666666666666666;
    private final static double FC4 = 0.08333333333333333333;
    private final static double FC5 = 0.05;
    private final static double FC6 = 0.03333333333333333333;
    private final static double FC7 = 0.02380952380952380952;
    private final static double FC8 = 0.01785714285714285714;

    /** {@code EPS10} in {@code tmerc.cpp:52}, the spherical forward's slop band. */
    private final static double EPS10 = 1.0e-10;

    /**
     * Indicates whether a Southern Hemisphere UTM zone
     */
    protected boolean isSouth = false;
    private int utmZone = -1;
    private double esp;
    private double ml0;

    /**
     * The order-6 meridional-arc series in the third flattening,
     * {@code 9.8.1:src/mlfn.cpp}. Replaces {@code ProjectionMath.enfn}/{@code mlfn}/
     * {@code inv_mlfn}: the forward gains about three decimal digits and the inverse
     * becomes closed form.
     */
    private MeridianArc meridian;

    /**
     * The Poder/Engsager kernel, or {@code null} on a sphere. Immutable once
     * {@link #initialize()} has run, and built even when {@code +approx} was asked for, so that
     * {@link #setApprox} and {@link #setAlgorithm} can be called afterwards without a re-setup.
     */
    private ExtendedTransverseMercatorProjection exact;

    /** {@code +algo}. */
    private Algorithm algorithm = Algorithm.PODER_ENGSAGER;

    /** {@code +approx}, which {@code getAlgoFromParams} tests before {@code +algo}. */
    private boolean approx = false;

    public TransverseMercatorProjection() {
        // setEllipsoid, not an assignment: Projection's constructor has already run
        // setEllipsoid(Ellipsoid.SPHERE), so assigning the field alone left a/e/es describing that
        // sphere while the field claimed GRS80 -- an un-parsed instance therefore ran the
        // *spherical* series on a radius nobody asked for.
        setEllipsoid(Ellipsoid.GRS80);
        projectionLatitude = ProjectionMath.toRad(0);
        projectionLongitude = ProjectionMath.toRad(0);
        minLongitude = ProjectionMath.toRad(-90);
        maxLongitude = ProjectionMath.toRad(90);
        initialize();
    }

    /**
     * Set up a projection suitable for State Plane Coordinates.
     */
    public TransverseMercatorProjection(Ellipsoid ellipsoid, double lon_0, double lat_0, double k, double x_0, double y_0) {
        setEllipsoid(ellipsoid);
        projectionLongitude = lon_0;
        projectionLatitude = lat_0;
        scaleFactor = k;
        falseEasting = x_0;
        falseNorthing = y_0;
        initialize();
    }

    @Override
    public void setSouthernHemisphere(boolean isSouth) {
        this.isSouth = isSouth;
    }

    @Override
    public boolean getSouthernHemisphere() {
        return isSouth;
    }

    /**
     * {@code +approx}: run the Evenden/Snyder series, reproducing Proj4J 1.4.3's numbers.
     *
     * <p>Takes effect immediately and needs no re-{@link #initialize()}; both algorithms are set
     * up whenever the figure of the Earth admits both.
     *
     * @param approx true to select Evenden/Snyder regardless of {@link #setAlgorithm}
     */
    public void setApprox(boolean approx) {
        this.approx = approx;
    }

    /** @return true if {@code +approx} was requested */
    public boolean isApprox() {
        return approx;
    }

    /**
     * {@code +algo}.
     *
     * @param algorithm one of {@code evenden_snyder}, {@code poder_engsager}, {@code auto},
     *                  case-insensitively
     * @throws InvalidValueException with {@link ErrorCause#INVALID_PARAM_VALUE} for any other
     *         value — {@code tmerc.cpp:575-578} logs "unknown value for +algo" and fails setup
     */
    public void setAlgorithm(String algorithm) {
        if (algorithm == null) {
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                    "unknown value for +algo: null");
        }
        String name = algorithm.trim().toLowerCase(java.util.Locale.ROOT);
        if ("evenden_snyder".equals(name)) {
            this.algorithm = Algorithm.EVENDEN_SNYDER;
        } else if ("poder_engsager".equals(name)) {
            this.algorithm = Algorithm.PODER_ENGSAGER;
        } else if ("auto".equals(name)) {
            this.algorithm = Algorithm.AUTO;
        } else {
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                    "unknown value for +algo: '" + algorithm + "'; expected one of "
                            + "evenden_snyder, poder_engsager, auto");
        }
    }

    /** @param algorithm the algorithm to use; {@link Algorithm#AUTO} resolves to Poder/Engsager */
    public void setAlgorithm(Algorithm algorithm) {
        if (algorithm == null) {
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                    "unknown value for +algo: null");
        }
        this.algorithm = algorithm;
    }

    /** @return the requested algorithm, before the sphere and {@code +approx} overrides */
    public Algorithm getAlgorithm() {
        return algorithm;
    }

    /**
     * The algorithm that will actually run, after {@code es == 0} and {@code +approx} are
     * applied and {@link Algorithm#AUTO} is resolved.
     *
     * @return {@link Algorithm#EVENDEN_SNYDER} or {@link Algorithm#PODER_ENGSAGER}
     */
    public Algorithm getEffectiveAlgorithm() {
        return useApprox() ? Algorithm.EVENDEN_SNYDER : Algorithm.PODER_ENGSAGER;
    }

    /**
     * {@code setup()} at {@code tmerc.cpp:519} forces the approximate series on a sphere;
     * {@code getAlgoFromParams} tests {@code +approx} before it looks at {@code +algo}, so a
     * definition carrying both gets Evenden/Snyder.
     */
    private boolean useApprox() {
        return spherical || exact == null || approx || algorithm == Algorithm.EVENDEN_SNYDER;
    }

    public Object clone() {
        // MeridianArc is immutable, so the shallow copy from super.clone() is safe. The
        // Poder/Engsager delegate is cloned because it is a mutable Projection, even though
        // nothing here mutates it after initialize().
        TransverseMercatorProjection p = (TransverseMercatorProjection) super.clone();
        if (exact != null) {
            p.exact = (ExtendedTransverseMercatorProjection) exact.clone();
        }
        return p;
    }

    public boolean isRectilinear() {
        return false;
    }

    public void initialize() {
        super.initialize();
        if (spherical) {
            // tmerc.cpp:526-528: esp = k0, ml0 = .5*esp. Note ml0 means something entirely
            // different in the two branches.
            esp = scaleFactor;
            ml0 = .5 * esp;
            meridian = null;
            exact = null;
        } else {
            meridian = MeridianArc.fromEs(es);
            ml0 = meridian.mlfn(projectionLatitude, Math.sin(projectionLatitude), Math.cos(projectionLatitude));
            esp = es / (1. - es);
            // Both algorithms are set up whenever both are defined, exactly as upstream's AUTO
            // case does (tmerc.cpp:539-547). It costs one coefficient build per CRS and makes
            // setApprox/setAlgorithm order-independent with respect to initialize().
            exact = ExtendedTransverseMercatorProjection.forDelegation(this);
        }
    }

    public static int getRowFromNearestParallel(double latitude) {
        int degrees = (int) ProjectionMath.radToDeg(ProjectionMath.normalizeLatitude(latitude));
        if (degrees < -80 || degrees > 84)
            return 0;
        if (degrees > 80)
            return 24;
        return (degrees + 80) / 8 + 3;
    }

    public static int getZoneFromNearestMeridian(double longitude) {
        int zone = (int) Math.floor((ProjectionMath.normalizeLongitude(longitude) + Math.PI) * 30.0 / Math.PI) + 1;
        if (zone < 1)
            zone = 1;
        else if (zone > 60)
            zone = 60;
        return zone;
    }

    public void setUTMZone(int zone) {
        utmZone = zone;
        zone--;
        projectionLongitude = (zone + .5) * Math.PI / 30. - Math.PI;
        projectionLatitude = 0.0;
        scaleFactor = 0.9996;
        falseEasting = 500000;
        falseNorthing = isSouth ? 10000000.0 : 0.0;
        initialize();
    }

    public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate xy) {
        if (spherical) {
            return sphericalProject(lplam, lpphi, xy);
        }
        if (useApprox()) {
            return approxProject(lplam, lpphi, xy);
        }
        return exact.project(lplam, lpphi, xy);
    }

    public ProjCoordinate projectInverse(double x, double y, ProjCoordinate out) {
        if (spherical) {
            return sphericalProjectInverse(x, y, out);
        }
        if (useApprox()) {
            return approxProjectInverse(x, y, out);
        }
        return exact.projectInverse(x, y, out);
    }

    // ------------------------------------------------------------------ Evenden/Snyder, sphere

    /**
     * {@code tmerc_spherical_fwd}, {@code tmerc.cpp:117-160}.
     *
     * <p>Three things here are not cosmetic:
     * <ul>
     * <li>{@code |b| = 1} is an error, not a clamp: the point is at infinity.
     * <li>{@code cosphi == 1.0} exactly — the equator — is special-cased so that a longitude
     *     more than 90&deg; from the central meridian still round-trips, mapping to
     *     {@code y = pi} rather than to {@code acos} of a value that has lost all its
     *     significance. That is what {@code builtins.gie:7202} ({@code accept 150 0}) tests.
     * <li>Outside the equator, {@code |y| >= 1} within {@code EPS10} collapses to zero and
     *     anything further out is an error. Proj4J used {@code ProjectionMath.acos}, which
     *     clamps silently and can never report a domain failure.
     * </ul>
     */
    private ProjCoordinate sphericalProject(double lplam, double lpphi, ProjCoordinate xy) {
        double cosphi = Math.cos(lpphi);
        double b = cosphi * Math.sin(lplam);
        if (Math.abs(Math.abs(b) - 1.) <= EPS10) {
            throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                    "the point (" + Math.toDegrees(lplam) + ", " + Math.toDegrees(lpphi)
                            + ") deg from the central meridian projects to infinity on a sphere");
        }

        // 9.8.1:tmerc.cpp:130 is xy.x = Q->ml0 * log((1+b)/(1-b)) with ml0 = .5*esp
        // and esp = k0, i.e. 0.5*k0*log(...). proj4j multiplied by scaleFactor a
        // second time, giving 0.5*k0*k0*log(...) -- wrong for every k_0 != 1, so
        // spherical tmerc fwd was off by a factor of k_0 in easting.
        xy.x = ml0 * Math.log((1. + b) / (1. - b));

        double ty;
        if (cosphi == 1.0) {
            /* Helps to be able to roundtrip |longitudes| > 90 at lat=0 */
            ty = (lplam < -ProjectionMath.HALFPI || lplam > ProjectionMath.HALFPI)
                    ? Math.PI : 0.0;
        } else {
            ty = cosphi * Math.cos(lplam) / Math.sqrt(1. - b * b);
            double m = Math.abs(ty);
            if (m >= 1.) {
                if ((m - 1.) > EPS10) {
                    throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                            "the point (" + Math.toDegrees(lplam) + ", " + Math.toDegrees(lpphi)
                                    + ") deg is outside the spherical transverse Mercator "
                                    + "domain: cos(phi) cos(lam) / sqrt(1 - b^2) = " + ty);
                }
                ty = 0.;
            } else {
                ty = Math.acos(ty);
            }
        }

        if (lpphi < 0.0)
            ty = -ty;
        xy.y = esp * (ty - projectionLatitude);
        return xy;
    }

    /** {@code tmerc_spherical_inv}, {@code tmerc.cpp:196-220}. */
    private ProjCoordinate sphericalProjectInverse(double x, double y, ProjCoordinate out) {
        double h = Math.exp(x / esp);
        if (h == 0.)
            throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                    "tmerc spherical inverse: easting underflows, outside the projection domain");
        double g = .5 * (h - 1. / h);
        // D, as in equation 8-8 of USGS "Map Projections - A Working Manual"
        // (9.8.1:tmerc.cpp:211-218). proj4j negated on the sign of the *northing*
        // where PROJ takes copysign(lp.phi, D); the two differ for every lat_0 != 0.
        final double D = projectionLatitude + y / esp;
        h = Math.cos(D);
        // Math.asin, not the deprecated ProjectionMath.asin: |h| <= 1 so the numerator is in
        // [0, 1] and the denominator is >= 1, hence the radicand and its root are both in
        // [0, 1] by construction. tmerc.cpp:214 likewise calls the bare libm asin, not aasin.
        out.y = Math.asin(Math.sqrt((1. - h * h) / (1. + g * g)));
        out.y = Math.copySign(out.y, D);
        out.x = (g != 0.0 || h != 0.0) ? Math.atan2(g, h) : 0.;
        return out;
    }

    // -------------------------------------------------------------- Evenden/Snyder, ellipsoid

    /** {@code approx_e_fwd}, {@code tmerc.cpp:71-115}. */
    private ProjCoordinate approxProject(double lplam, double lpphi, ProjCoordinate xy) {
        /*
         * Fail if our longitude is more than 90 degrees from the central meridian since the
         * results are essentially garbage (tmerc.cpp:79-88, http://trac.osgeo.org/proj/ticket/5).
         * proj4j had no such test: the series simply diverged and returned a finite number.
         */
        if (lplam < -ProjectionMath.HALFPI || lplam > ProjectionMath.HALFPI) {
            throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                    "longitude " + Math.toDegrees(lplam) + " deg from the central meridian is "
                            + "more than 90 deg away; the Evenden/Snyder series is garbage "
                            + "there. Use the default Poder/Engsager algorithm, which is valid "
                            + "to 150 deg.");
        }

        double al, als, n, t;
        double sinphi = Math.sin(lpphi);
        double cosphi = Math.cos(lpphi);
        t = Math.abs(cosphi) > 1e-10 ? sinphi / cosphi : 0.0;
        t *= t;
        al = cosphi * lplam;
        als = al * al;
        al /= Math.sqrt(1. - es * sinphi * sinphi);
        n = esp * cosphi * cosphi;
        xy.x = scaleFactor * al * (FC1 +
                FC3 * als * (1. - t + n +
                        FC5 * als * (5. + t * (t - 18.) + n * (14. - 58. * t)
                                + FC7 * als * (61. + t * (t * (179. - t) - 479.))
                        )));
        xy.y = scaleFactor * (meridian.mlfn(lpphi, sinphi, cosphi) - ml0 +
                sinphi * al * lplam * FC2 * (1. +
                        FC4 * als * (5. - t + n * (9. + 4. * n) +
                                FC6 * als * (61. + t * (t - 58.) + n * (270. - 330 * t)
                                        + FC8 * als * (1385. + t * (t * (543. - t) - 3111.))
                                ))));
        return xy;
    }

    /** {@code approx_e_inv}, {@code tmerc.cpp:162-194}. */
    private ProjCoordinate approxProjectInverse(double x, double y, ProjCoordinate out) {
        double n, con, cosphi, d, ds, sinphi, t;

        out.y = meridian.invMlfn(ml0 + y / scaleFactor);
        // 9.8.1:tmerc.cpp:165 tests the *latitude just computed*, not the northing.
        // Because mlfn(pi/2) = 1.568164141 < pi/2 for GRS80 there is always a band
        // where the old |y| test is silent but the footpoint is already past the pole.
        if (Math.abs(out.y) >= ProjectionMath.HALFPI) {
            out.y = y < 0. ? -ProjectionMath.HALFPI : ProjectionMath.HALFPI;
            out.x = 0.;
        } else {
            sinphi = Math.sin(out.y);
            cosphi = Math.cos(out.y);
            t = Math.abs(cosphi) > 1e-10 ? sinphi / cosphi : 0.;
            n = esp * cosphi * cosphi;
            d = x * Math.sqrt(con = 1. - es * sinphi * sinphi) / scaleFactor;
            con *= t;
            t *= t;
            ds = d * d;
            out.y -= (con * ds / (1. - es)) * FC2 * (1. -
                    ds * FC4 * (5. + t * (3. - 9. * n) + n * (1. - 4 * n) -
                            ds * FC6 * (61. + t * (90. - 252. * n +
                                    45. * t) + 46. * n
                                    // 1575, not 1574 -- 9.8.1:tmerc.cpp:185.
                                    - ds * FC8 * (1385. + t * (3633. + t * (4095. + 1575. * t)))
                            )));
            out.x = d * (FC1 -
                    ds * FC3 * (1. + 2. * t + n -
                            ds * FC5 * (5. + t * (28. + 24. * t + 8. * n) + 6. * n
                                    - ds * FC7 * (61. + t * (662. + t * (1320. + 720. * t)))
                            ))) / cosphi;
        }
        return out;
    }

    public boolean hasInverse() {
        return true;
    }

    /**
     * Narrows {@link Projection#equals} by the algorithm selection: two otherwise identical
     * {@code tmerc} definitions that differ by {@code +approx} project differently, so they must
     * not compare equal or the transform cache will hand one out for the other.
     */
    @Override
    public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (!super.equals(that)) {
            return false;
        }
        TransverseMercatorProjection p = (TransverseMercatorProjection) that;
        return approx == p.approx && getEffectiveAlgorithm() == p.getEffectiveAlgorithm();
    }

    @Override
    public int hashCode() {
        int h = super.hashCode();
        h = 31 * h + (approx ? 1 : 0);
        // ordinal(), not hashCode(): Enum.hashCode is identity-based, and Projection.hashCode is
        // documented to agree with equals rather than to be stable across JVMs, but there is no
        // reason to be gratuitously unstable.
        h = 31 * h + getEffectiveAlgorithm().ordinal();
        return h;
    }

    public String toString() {
        if (utmZone >= 0)
            return "Universal Tranverse Mercator";
        return "Transverse Mercator";
    }

}

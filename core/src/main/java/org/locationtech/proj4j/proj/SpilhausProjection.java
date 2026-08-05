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
import org.locationtech.proj4j.util.ConformalLat;
import org.locationtech.proj4j.util.FastStrictTrig;

/**
 * Spilhaus. A port of {@code 9.8.1:src/projections/spilhaus.cpp}, added upstream in 2025 and
 * derived from the discussion in <a
 * href="https://github.com/OSGeo/PROJ/issues/1851">PROJ issue #1851</a>.
 *
 * <p><b>It is {@link AdamsWorldInASquareIIProjection} on a sphere, wrapped in an oblique
 * rotation.</b> {@code spilhaus.cpp:123-128} builds a child {@code adams_ws2} {@code PJ},
 * clears its eccentricity, and calls its forward and inverse directly — below the
 * {@code a}/{@code x_0}/{@code k_0} layer. This class does the same by holding a child
 * instance and calling its {@code projectRaw}/{@code projectInverse}, which are reachable
 * because both classes live in this package. The child's own
 * {@link AdamsProjection#initialize()} already forces {@code e = es = 0}.
 *
 * <p>The point of the oblique framing is to put the Southern Ocean in the middle of the map
 * with the continents unbroken around the edge. The <b>two leading minus signs</b> in the
 * forward's final two lines are what achieve it; dropping them mirrors the world.
 *
 * <h2>Defaults, and why they are not zero</h2>
 *
 * <p>{@code spilhaus} is the only operator in proj4j whose {@code lon_0} and {@code lat_0}
 * default to something other than the equator and Greenwich. {@code spilhaus.cpp:138-147}
 * applies them only when the parameter is <em>absent</em>, so they are set in the constructor
 * here and any {@code +lon_0}/{@code +lat_0} the parser sees overwrites them:
 *
 * <table>
 * <caption>defaults</caption>
 * <tr><th>parameter</th><th>default</th></tr>
 * <tr><td>{@code +lon_0}</td><td>{@code 66.94970198} degrees</td></tr>
 * <tr><td>{@code +lat_0}</td><td>{@code -49.56371678} degrees</td></tr>
 * <tr><td>{@code +azi}</td><td>{@code 40.17823482} degrees</td></tr>
 * <tr><td>{@code +rot}</td><td>{@code 45} degrees</td></tr>
 * <tr><td>{@code +k_0}</td><td>{@code 1.0}; {@code sqrt(2)} reproduces {@code ESRI:54099}</td></tr>
 * </table>
 *
 * <p><b>{@code +azi} and {@code +rot} do not reach this class through {@code Proj4Parser}
 * yet</b> — the parser has no dispatch for either key, so a definition carrying them parses
 * cleanly and then projects with the defaults. {@link #setAziDegrees} and
 * {@link #setRotDegrees} exist so that wiring them up is a two-line change in the parser, and
 * so that a caller constructing the projection programmatically can set them today.
 *
 * <h2>Setup</h2>
 *
 * <pre>
 *   chi0     = conformalLat(phi0)
 *   sinalpha = -cos(chi0) * cos(azi)
 *   cosalpha = sqrt(1 - sinalpha^2)
 *   lambda_0 = atan2(tan(azi), -sin(chi0))
 *   beta     = pi + atan2(-sin(azi), -tan(chi0))
 *   conformal_distortion = cos(phi0) / sqrt(1 - es sin^2 phi0) / cos(chi0)
 * </pre>
 *
 * <p>On a sphere {@code chi0 == phi0} and {@code conformal_distortion == 1}. Unlike the rest of
 * this family, {@code spilhaus} itself is {@code Sph&amp;Ell} — it keeps the requested
 * eccentricity and uses it for the conformal latitude; only the child is spherical.
 */
public class SpilhausProjection extends Projection {

    private static final long serialVersionUID = -643336112054248619L;

    /** {@code spilhaus.cpp:140} — {@code +lon_0} when none is given, in degrees. */
    public static final double DEFAULT_LON_0_DEGREES = 66.94970198;

    /** {@code spilhaus.cpp:143} — {@code +lat_0} when none is given, in degrees. */
    public static final double DEFAULT_LAT_0_DEGREES = -49.56371678;

    /** {@code spilhaus.cpp:145} — {@code +azi} when none is given, in degrees. */
    public static final double DEFAULT_AZI_DEGREES = 40.17823482;

    /** {@code spilhaus.cpp:147} — {@code +rot} when none is given, in degrees. */
    public static final double DEFAULT_ROT_DEGREES = 45;

    private double azimuth = DEFAULT_AZI_DEGREES * DTR;
    private double rotation = DEFAULT_ROT_DEGREES * DTR;

    private double cosalpha;
    private double sinalpha;
    private double beta;
    private double lambda0;
    private double conformalDistortion;
    private double cosrot;
    private double sinrot;

    /**
     * The child {@code adams_ws2}. Its {@code a}, false origin and scale are never used —
     * only {@link AdamsProjection#projectRaw} and
     * {@link AdamsWorldInASquareIIProjection#projectInverse}, both of which work in unit-sphere
     * units.
     */
    private final AdamsWorldInASquareIIProjection adamsWs2 =
            new AdamsWorldInASquareIIProjection();

    /**
     * One scratch coordinate for the child's result. {@code Projection} is documented as not
     * thread-safe and mutated on the hot path by several existing operators, so this follows
     * the same contract rather than allocating per call.
     */
    private final ProjCoordinate child = new ProjCoordinate();

    public SpilhausProjection() {
        projectionLongitude = DEFAULT_LON_0_DEGREES * DTR;
        projectionLatitude = DEFAULT_LAT_0_DEGREES * DTR;
    }

    /** {@code +azi}, in degrees. */
    public void setAziDegrees(double azi) {
        this.azimuth = azi * DTR;
    }

    /** {@code +azi}, in radians. */
    public void setAzi(double azi) {
        this.azimuth = azi;
    }

    /** {@code +azi}, in radians. */
    public double getAzi() {
        return azimuth;
    }

    /** {@code +rot}, in degrees. */
    public void setRotDegrees(double rot) {
        this.rotation = rot * DTR;
    }

    /** {@code +rot}, in radians. */
    public void setRot(double rot) {
        this.rotation = rot;
    }

    /** {@code +rot}, in radians. */
    public double getRot() {
        return rotation;
    }

    @Override
    public void initialize() {
        super.initialize();

        adamsWs2.setEllipsoid(ellipsoid);
        adamsWs2.initialize();

        cosrot = FastStrictTrig.cos(rotation);
        sinrot = FastStrictTrig.sin(rotation);

        final double chi0 = ConformalLat.conformalLat(projectionLatitude, e);
        sinalpha = -FastStrictTrig.cos(chi0) * FastStrictTrig.cos(azimuth);
        cosalpha = Math.sqrt(1 - sinalpha * sinalpha);
        lambda0 = StrictMath.atan2(FastStrictTrig.tan(azimuth), -FastStrictTrig.sin(chi0));
        beta = Math.PI + StrictMath.atan2(-FastStrictTrig.sin(azimuth), -FastStrictTrig.tan(chi0));

        final double sinphi0 = FastStrictTrig.sin(projectionLatitude);
        conformalDistortion = FastStrictTrig.cos(projectionLatitude)
                / Math.sqrt(1 - es * sinphi0 * sinphi0)
                / FastStrictTrig.cos(chi0);
    }

    /**
     * {@code spilhaus_forward}, {@code spilhaus.cpp:42-75}. Snyder's <i>A working manual</i>
     * formulas (5-7) and (5-8b) rotate the conformal sphere, and the child {@code adams_ws2}
     * squares the result.
     *
     * <p>{@code lam} arrives with {@code lon_0} already removed by
     * {@link Projection#projectRadians(ProjCoordinate, ProjCoordinate)}, matching
     * {@code fwd_prepare}.
     */
    @Override
    public ProjCoordinate project(double lam, double phi, ProjCoordinate dst) {
        AdamsProjection.validateForwardInput(lam, phi);

        final double chi = ConformalLat.conformalLat(phi, e);
        final double cosChi = FastStrictTrig.cos(chi);
        final double sinChi = FastStrictTrig.sin(chi);

        final double coslam = FastStrictTrig.cos(lam - lambda0);
        final double sinlam = FastStrictTrig.sin(lam - lambda0);

        // Snyder (5-7)
        final double phiAdams =
                AdamsProjection.aasin(sinalpha * sinChi - cosalpha * cosChi * coslam);

        // Snyder (5-8b)
        double lamAdams = beta + StrictMath.atan2(cosChi * sinlam,
                sinalpha * cosChi * coslam + cosalpha * sinChi);
        // Upstream's explicit while-loops, not adjlon: beta can push the sum past pi by up to
        // beta itself, so a single subtraction is not always enough.
        while (lamAdams > Math.PI) {
            lamAdams -= Math.PI * 2;
        }
        while (lamAdams < -Math.PI) {
            lamAdams += Math.PI * 2;
        }

        adamsWs2.projectRaw(lamAdams, phiAdams, child);

        final double factor = conformalDistortion * scaleFactor;
        dst.x = -(child.x * cosrot + child.y * sinrot) * factor;
        dst.y = -(child.x * -sinrot + child.y * cosrot) * factor;
        return dst;
    }

    /**
     * {@code spilhaus_inverse}, {@code spilhaus.cpp:77-103} — the exact mirror of the forward,
     * ending in the inverse conformal latitude.
     *
     * <p>The returned longitude is wrapped into {@code (-pi, pi]} here rather than being left
     * to the caller. {@code inverseProjectRadians} <em>clamps</em> rather than wraps before it
     * re-adds {@code lon_0}, and {@code lambda_0 + atan2(..)} genuinely exceeds {@code pi} for
     * part of the map with the default {@code azi} and {@code lat_0} — clamping there would
     * pin a band of the Pacific to the antimeridian.
     */
    @Override
    public ProjCoordinate projectInverse(double x, double y, ProjCoordinate dst) {
        final double factor = 1.0 / (conformalDistortion * scaleFactor);
        final double xa = -(x * cosrot + y * -sinrot) * factor;
        final double ya = -(x * sinrot + y * cosrot) * factor;
        adamsWs2.projectInverse(xa, ya, child);

        final double cosphiS = FastStrictTrig.cos(child.y);
        final double sinphiS = FastStrictTrig.sin(child.y);
        final double coslamS = FastStrictTrig.cos(child.x - beta);
        final double sinlamS = FastStrictTrig.sin(child.x - beta);

        final double chi =
                AdamsProjection.aasin(sinalpha * sinphiS + cosalpha * cosphiS * coslamS);

        dst.x = AdamsProjection.adjlon(lambda0 + AdamsProjection.aatan2(cosphiS * sinlamS,
                sinalpha * cosphiS * coslamS - cosalpha * sinphiS));
        dst.y = ConformalLat.conformalLatInverse(chi, e);
        return dst;
    }

    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public boolean isConformal() {
        return true;
    }

    /**
     * {@code azi} and {@code rot} change the projection, so they belong in equality; the base
     * class already covers {@code lat_0}, {@code lon_0}, {@code k_0} and the ellipsoid.
     */
    @Override
    public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (!(that instanceof SpilhausProjection) || !super.equals(that)) {
            return false;
        }
        SpilhausProjection p = (SpilhausProjection) that;
        return azimuth == p.azimuth && rotation == p.rotation;
    }

    @Override
    public int hashCode() {
        int h = super.hashCode();
        h = 31 * h + Double.hashCode(azimuth == 0.0 ? 0.0 : azimuth);
        h = 31 * h + Double.hashCode(rotation == 0.0 ? 0.0 : rotation);
        return h;
    }

    @Override
    public String toString() {
        return "Spilhaus";
    }
}

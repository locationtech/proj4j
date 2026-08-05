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
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Orthographic ({@code +proj=ortho}), ported from {@code 9.8.1:src/projections/ortho.cpp}.
 *
 * <p>Upstream is declared {@code "Azi, Sph&amp;Ell"} and has <b>four</b> kernels — a spherical
 * forward/inverse pair and an ellipsoidal pair from EPSG guidance note 7.2 &sect;3.3.5 — plus a
 * {@code +alpha}/{@code +k_0} rotation-and-scale applied to both. Proj4J had only the spherical arm,
 * no rotation, and no scale.
 *
 * <h2>The domain contract, and the evidence that settles it</h2>
 *
 * <p>{@code builtins.gie}'s {@code ortho} block mixes rows that expect a coordinate with rows that
 * expect {@code errno coord_transfm_outside_projection_domain}, and Proj4J 1.4.3 got <em>both</em>
 * kinds wrong in the <em>same</em> forward function. The reason was a single line:
 *
 * <pre>
 *   switch (mode) { ... xy.x = xy.y = Double.NaN; ... }
 *   xy.x = cosphi * Math.sin(lam);          // &lt;-- unconditional, AFTER the switch
 * </pre>
 *
 * <p>The visibility guard poisoned both ordinates and the very next statement overwrote
 * {@code xy.x} with a perfectly finite number, so a rejected point came back as exactly one
 * non-finite ordinate — which defeats any "is x plausible" check by construction. Upstream writes
 * {@code xy.x = xy.y = HUGE_VAL} <em>before</em> the switch and returns from inside it, so its
 * {@code xy.x} assignment is only reachable on the success path.
 *
 * <p>That asymmetry is what settles the contract, and it settles it without guessing: every row in
 * the block that expects a coordinate corresponds to a path that reaches the end of upstream's
 * function, and every row that expects the domain errno corresponds to a call upstream makes to
 * {@code forward_error()} or {@code proj_errno_set(..., OUTSIDE_PROJECTION_DOMAIN)}. There is no
 * third category and no row where the two overlap. So the port is line-for-line, with one
 * substitution: <b>where upstream poisons the output and sets errno, this throws</b>
 * {@link ProjectionException} carrying {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN}. In PROJ the
 * {@code HUGE_VAL} is a sentinel that {@code fwd_finalize} reads together with errno, never an
 * answer handed to a caller; an exception at the same point is the same statement with no sentinel
 * left over to be mistaken for data.
 *
 * <p>The predicates, all of them:
 *
 * <table>
 * <caption>every domain rejection in {@code ortho.cpp}</caption>
 * <tr><th>direction / mode</th><th>predicate</th></tr>
 * <tr><td>fwd, spherical equatorial</td><td>{@code cos(phi)*cos(lam) &lt; -1e-10}</td></tr>
 * <tr><td>fwd, spherical oblique</td>
 *     <td>{@code sinph0*sin(phi) + cosph0*cos(phi)*cos(lam) &lt; -1e-10}</td></tr>
 * <tr><td>fwd, spherical polar</td><td>{@code |phi - phi0| - 1e-10 &gt; pi/2}</td></tr>
 * <tr><td>fwd, ellipsoidal, <em>all</em> modes</td><td>the same dot product as the oblique case</td></tr>
 * <tr><td>inv, spherical</td><td>{@code hypot(x,y) - 1 &gt; 1e-10}</td></tr>
 * <tr><td>inv, ellipsoidal polar</td><td>{@code x^2 + y^2 - 1 &gt; 1e-10}</td></tr>
 * <tr><td>inv, ellipsoidal equatorial</td><td>{@code x^2 + (y*a/b)^2 &gt; 1 + 1e-11}</td></tr>
 * <tr><td>inv, ellipsoidal oblique</td>
 *     <td>{@code x^2 + ((y - y_shift)/y_scale)^2 &gt; 1 + 1e-11}</td></tr>
 * <tr><td>inv, ellipsoidal oblique</td>
 *     <td>20 Newton trips without {@code |dphi|, |dlam| &lt; 1e-12}</td></tr>
 * </table>
 *
 * <p>The ellipsoidal-inverse guards are deliberately <em>not</em> all the same number:
 * {@code 1e-10} on {@code rho^2 - 1} at the poles, {@code 1 + 1e-11} on the ellipse elsewhere. The
 * equatorial one is load-bearing at the corpus's 0.1 mm bar. {@code builtins.gie}'s
 * {@code accept 0 6356752.3143} sits 0.1 mm past the WGS84 semi-minor axis, which makes
 * {@code (y*a/b)^2 = 1 + 1.72e-11} — <b>1.7&times; the threshold</b>, and the row expects a
 * failure. Widening that guard to {@code 1e-10} would make it return the pole instead.
 *
 * <h2>Two things ported verbatim that look like defects</h2>
 *
 * <ul>
 * <li><b>The ellipsoidal oblique inverse seeds itself from the spherical inverse, which un-rotates
 *     and un-scales a second time.</b> Upstream calls {@code ortho_s_inverse(xy_recentered, P)} on
 *     coordinates from which {@code +alpha} and {@code +k_0} have <em>already</em> been removed, and
 *     {@code ortho_s_inverse} removes them again on entry. It is only the starting point of a
 *     20-trip Newton iteration whose residual is measured against the <em>un</em>-seeded target, so
 *     the answer is unaffected; reproducing it costs nothing and diverging from it would be an
 *     unmeasurable difference dressed up as a fix. Non-negotiable 7.</li>
 * <li><b>{@code a/b} is computed as {@code 1/sqrt(1 - es)} rather than from a stored {@code b}.</b>
 *     Proj4J's {@link Projection} carries {@code es} and {@code one_es}, not {@code b}, and a
 *     definition written {@code +a= +es=} has no {@code b} to read. The two agree to an ulp
 *     (2.2e-16 relative), and the only place the value is used is the {@code &gt; 1 + 1e-11}
 *     comparison above, where the margin on the tightest corpus row is 7e-12 — four orders of
 *     magnitude above an ulp. This is the one place this port does not take a constant
 *     digit-for-digit, and it is recorded here so the exception is visible rather than inferred.</li>
 * </ul>
 *
 * <h2>{@code +lat_0} used to default to 45 degrees</h2>
 *
 * <p>See {@link AzimuthalProjection#AzimuthalProjection()}. Every {@code +proj=ortho +ellps=WGS84}
 * row in the corpus was measuring that, not the arithmetic here — the reported
 * {@code (170, 10) -> (5145289.58, NaN)} is the <em>oblique</em> branch at
 * {@code lat_0 = lon_0 = 45}, not the equatorial one.
 *
 * @see AzimuthalProjection#AzimuthalProjection()
 */
public class OrthographicAzimuthalProjection extends AzimuthalProjection {

    private static final long serialVersionUID = 299556558720804920L;

    /** {@code ortho.cpp}'s file-local {@code EPS10}. */
    private static final double ORTHO_EPS10 = 1.e-10;

    /** {@code Q->nu0}: the prime-vertical radius at {@code lat_0}. Ellipsoidal only. */
    private double nu0;

    /** {@code Q->y_shift}, {@code Q->y_scale}: the ellipsoidal oblique inverse's recentring. */
    private double y_shift;
    private double y_scale = 1.0;

    /** {@code Q->sinalpha}, {@code Q->cosalpha}: {@code +alpha}, defaulting to 0. */
    private double sinalpha;
    private double cosalpha = 1.0;

    public OrthographicAzimuthalProjection() {
        initialize();
    }

    /**
     * {@code PJ_PROJECTION(ortho)}.
     * <p>
     * Every assignment here derives from {@link #projectionLatitude}, {@link #es} and
     * {@link #alpha}, so the second call {@code Proj4Parser} always makes sees the same inputs and
     * writes the same values. Nothing derived is read back — non-negotiable 4.
     */
    @Override
    public void initialize() {
        super.initialize();
        // Upstream sets sinph0/cosph0 UNCONDITIONALLY; AzimuthalProjection.initialize() sets them
        // only in its oblique branch. The ellipsoidal forward's visibility test and all three of
        // nu0/y_shift/y_scale read them at the poles and on the equator too.
        sinphi0 = FastStrictTrig.sin(projectionLatitude);
        cosphi0 = FastStrictTrig.cos(projectionLatitude);

        if (es != 0.0) {
            nu0 = 1.0 / Math.sqrt(1.0 - es * sinphi0 * sinphi0);
            y_shift = es * nu0 * sinphi0 * cosphi0;
            y_scale = 1.0 / Math.sqrt(1.0 - es * cosphi0 * cosphi0);
        } else {
            nu0 = 0.0;
            y_shift = 0.0;
            y_scale = 1.0;
        }

        // pj_param's "ralpha" is 0 for an absent key; Projection leaves the field NaN so that
        // omerc can tell absent from an explicit zero, so translate that here.
        double alphaRad = Double.isNaN(alpha) ? 0.0 : alpha;
        sinalpha = FastStrictTrig.sin(alphaRad);
        cosalpha = FastStrictTrig.cos(alphaRad);
    }

    @Override
    public ProjCoordinate project(double lam, double phi, ProjCoordinate xy) {
        return es == 0.0 ? sphericalForward(lam, phi, xy) : ellipsoidalForward(lam, phi, xy);
    }

    /** {@code ortho_s_forward}. */
    private ProjCoordinate sphericalForward(double lam, double phi, ProjCoordinate xy) {
        double sinphi;
        double cosphi = FastStrictTrig.cos(phi);
        double coslam = FastStrictTrig.cos(lam);
        double yp;

        switch (mode) {
        case EQUATOR:
            if (cosphi * coslam < -ORTHO_EPS10) {
                throw notVisible(lam, phi);
            }
            yp = FastStrictTrig.sin(phi);
            break;
        case OBLIQUE:
            sinphi = FastStrictTrig.sin(phi);
            if (sinphi0 * sinphi + cosphi0 * cosphi * coslam < -ORTHO_EPS10) {
                throw notVisible(lam, phi);
            }
            yp = cosphi0 * sinphi - sinphi0 * cosphi * coslam;
            break;
        case NORTH_POLE:
            coslam = -coslam;
            // falls through, exactly as upstream's PROJ_FALLTHROUGH does
        case SOUTH_POLE:
            if (Math.abs(phi - projectionLatitude) - ORTHO_EPS10 > ProjectionMath.HALFPI) {
                throw notVisible(lam, phi);
            }
            yp = cosphi * coslam;
            break;
        default:
            throw unreachableMode();
        }
        double xp = cosphi * FastStrictTrig.sin(lam);

        // The +alpha rotation and the +k_0 scale, applied to both kernels' output.
        xy.x = (xp * cosalpha - yp * sinalpha) * scaleFactor;
        xy.y = (xp * sinalpha + yp * cosalpha) * scaleFactor;
        return xy;
    }

    /** {@code ortho_e_forward}, from EPSG guidance note 7.2 &sect;3.3.5. */
    private ProjCoordinate ellipsoidalForward(double lam, double phi, ProjCoordinate xy) {
        final double cosphi = FastStrictTrig.cos(phi);
        final double sinphi = FastStrictTrig.sin(phi);
        final double coslam = FastStrictTrig.cos(lam);
        final double sinlam = FastStrictTrig.sin(lam);

        // The same visibility condition as the spherical oblique case, in EVERY mode:
        // ortho_e_forward has no mode switch at all.
        if (sinphi0 * sinphi + cosphi0 * cosphi * coslam < -ORTHO_EPS10) {
            throw notVisible(lam, phi);
        }

        final double nu = 1.0 / Math.sqrt(1.0 - es * sinphi * sinphi);
        final double xp = nu * cosphi * sinlam;
        final double yp = nu * (sinphi * cosphi0 - cosphi * sinphi0 * coslam)
                + es * (nu0 * sinphi0 - nu * sinphi) * cosphi0;
        xy.x = (cosalpha * xp - sinalpha * yp) * scaleFactor;
        xy.y = (sinalpha * xp + cosalpha * yp) * scaleFactor;
        return xy;
    }

    @Override
    public ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
        return es == 0.0 ? sphericalInverse(x, y, lp) : ellipsoidalInverse(x, y, lp);
    }

    /** {@code ortho_s_inverse}. */
    private ProjCoordinate sphericalInverse(double x, double y, ProjCoordinate lp) {
        final double xf = x;
        final double yf = y;
        double xx = (cosalpha * xf + sinalpha * yf) / scaleFactor;
        double yy = (-sinalpha * xf + cosalpha * yf) / scaleFactor;

        final double rh = ProjectionMath.distance(xx, yy);
        double sinc = rh;
        if (sinc > 1.) {
            if ((sinc - 1.) > ORTHO_EPS10) {
                throw offMap(x, y, rh);
            }
            sinc = 1.;
        }
        final double cosc = Math.sqrt(1. - sinc * sinc); /* in this range OK */
        if (Math.abs(rh) <= ORTHO_EPS10) {
            // Upstream sets BOTH ordinates here and returns; the old translation set only the
            // latitude and fell through to the atan2 below.
            lp.y = projectionLatitude;
            lp.x = 0.0;
            return lp;
        }
        switch (mode) {
        case NORTH_POLE:
            yy = -yy;
            lp.y = Math.acos(sinc);
            break;
        case SOUTH_POLE:
            lp.y = -Math.acos(sinc);
            break;
        case EQUATOR:
            lp.y = yy * sinc / rh;
            xx *= sinc;
            yy = cosc * rh;
            lp.y = clampAsin(lp.y);
            break;
        case OBLIQUE:
            lp.y = cosc * sinphi0 + yy * sinc * cosphi0 / rh;
            yy = (cosc - sinphi0 * lp.y) * rh;
            xx *= sinc * cosphi0;
            lp.y = clampAsin(lp.y);
            break;
        default:
            throw unreachableMode();
        }
        lp.x = (yy == 0. && (mode == OBLIQUE || mode == EQUATOR))
                ? (xx == 0. ? 0. : xx < 0. ? -ProjectionMath.HALFPI : ProjectionMath.HALFPI)
                : Math.atan2(xx, yy);
        return lp;
    }

    /** Upstream's shared {@code sinchk} label, reached from both the equatorial and oblique arms. */
    private static double clampAsin(double v) {
        if (Math.abs(v) >= 1.) {
            return v < 0. ? -ProjectionMath.HALFPI : ProjectionMath.HALFPI;
        }
        return Math.asin(v);
    }

    /** {@code ortho_e_inverse}. */
    private ProjCoordinate ellipsoidalInverse(double x, double y, ProjCoordinate lp) {
        final double xf = x;
        final double yf = y;
        final double xx = (cosalpha * xf + sinalpha * yf) / scaleFactor;
        final double yy = (-sinalpha * xf + cosalpha * yf) / scaleFactor;

        if (mode == NORTH_POLE || mode == SOUTH_POLE) {
            // The forward simplifies to rho^2 = cos(phi)^2 / (1 - es*sin(phi)^2), hence
            // cos(phi)^2 = rho^2 * (1 - es) / (1 - es*rho^2).
            final double rh2 = xx * xx + yy * yy;
            if (rh2 >= 1. - 1e-15) {
                if ((rh2 - 1.) > ORTHO_EPS10) {
                    throw offMap(x, y, Math.sqrt(rh2));
                }
                lp.y = 0;
            } else {
                lp.y = Math.acos(Math.sqrt(rh2 * one_es / (1 - es * rh2)))
                        * (mode == NORTH_POLE ? 1 : -1);
            }
            lp.x = Math.atan2(xx, yy * (mode == NORTH_POLE ? -1 : 1));
            return lp;
        }

        if (mode == EQUATOR) {
            // x^2 * (1 - es*sin(phi)^2) = (1 - sin(phi)^2) * sin(lam)^2, and
            // y^2 / ((1 - es)^2 + y^2*es) = sin(phi)^2.
            final double yOverB = yy * aOverB();
            if (xx * xx + yOverB * yOverB > 1 + 1e-11) {
                throw offEllipse(x, y);
            }
            final double sinphi2 = yy == 0 ? 0 : 1.0 / (sq((1 - es) / yy) + es);
            if (sinphi2 > 1 - 1e-11) {
                lp.y = ProjectionMath.HALFPI * (yy > 0 ? 1 : -1);
                lp.x = 0;
                return lp;
            }
            lp.y = Math.asin(Math.sqrt(sinphi2)) * (yy > 0 ? 1 : -1);
            final double sinlam = xx * Math.sqrt((1 - es * sinphi2) / (1 - sinphi2));
            if (Math.abs(sinlam) - 1 > -1e-15) {
                lp.x = ProjectionMath.HALFPI * (xx > 0 ? 1 : -1);
            } else {
                lp.x = Math.asin(sinlam);
            }
            return lp;
        }

        // Oblique: Newton-Raphson on the 2x2 Jacobian of the forward, obtained by substituting the
        // forward case's visibility condition into the forward equations.
        final double recentredY = (yy - y_shift) / y_scale;
        if (xx * xx + recentredY * recentredY > 1 + 1e-11) {
            throw offEllipse(x, y);
        }

        // EPSG GN 7.2 suggests (0, phi0) as the first guess; upstream records in a comment that it
        // does not converge well near the poles and uses the spherical inverse instead. See the
        // class javadoc for the deliberate double un-rotation this carries.
        sphericalInverse(xx, recentredY, lp);

        for (int i = 0; i < 20; i++) {
            final double cosphi = FastStrictTrig.cos(lp.y);
            final double sinphi = FastStrictTrig.sin(lp.y);
            final double coslam = FastStrictTrig.cos(lp.x);
            final double sinlam = FastStrictTrig.sin(lp.x);
            final double oneMinusEsSinphi2 = 1.0 - es * sinphi * sinphi;
            final double nu = 1.0 / Math.sqrt(oneMinusEsSinphi2);
            final double xNew = nu * cosphi * sinlam;
            final double yNew = nu * (sinphi * cosphi0 - cosphi * sinphi0 * coslam)
                    + es * (nu0 * sinphi0 - nu * sinphi) * cosphi0;
            final double rho = (1.0 - es) * nu / oneMinusEsSinphi2;
            final double j11 = -rho * sinphi * sinlam;
            final double j12 = nu * cosphi * coslam;
            final double j21 = rho * (cosphi * cosphi0 + sinphi * sinphi0 * coslam);
            final double j22 = nu * sinphi0 * cosphi * sinlam;
            final double d = j11 * j22 - j12 * j21;
            final double dx = xx - xNew;
            final double dy = yy - yNew;
            final double dphi = (j22 * dx - j12 * dy) / d;
            final double dlam = (-j21 * dx + j11 * dy) / d;
            lp.y += dphi;
            if (lp.y > ProjectionMath.HALFPI) {
                lp.y = ProjectionMath.HALFPI - (lp.y - ProjectionMath.HALFPI);
                lp.x = ProjectionMath.adjlon(lp.x + Math.PI);
            } else if (lp.y < -ProjectionMath.HALFPI) {
                lp.y = -ProjectionMath.HALFPI + (-ProjectionMath.HALFPI - lp.y);
                lp.x = ProjectionMath.adjlon(lp.x + Math.PI);
            }
            lp.x += dlam;
            if (Math.abs(dphi) < 1e-12 && Math.abs(dlam) < 1e-12) {
                return lp;
            }
        }
        // Upstream sets the domain errno and returns the last iterate. There is no "nearly
        // converged" answer worth handing back, and the corpus asserts this case as a failure: the
        // row is a point sitting exactly ON the visibility boundary, commented upstream as
        // "Just on it, but fails to converge".
        throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                "ellipsoidal oblique inverse did not converge to 1e-12 rad within 20 iterations "
                        + "for (" + x + ", " + y + "); the point is on or past the visibility "
                        + "boundary of the projection plane");
    }

    /**
     * {@code P->a / P->b}, derived from {@code es}. See the class javadoc for why an ulp cannot
     * matter at the single place this is used.
     *
     * @return {@code a/b}
     */
    private double aOverB() {
        return 1.0 / Math.sqrt(one_es);
    }

    private static double sq(double v) {
        return v * v;
    }

    private ProjectionException notVisible(double lam, double phi) {
        return new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                "(" + Math.toDegrees(lam) + ", " + Math.toDegrees(phi) + ") deg is on the "
                        + "unprojected hemisphere: the dot product of the ellipsoid normals at the "
                        + "projection centre and at the point is negative");
    }

    private ProjectionException offMap(double x, double y, double rho) {
        return new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                "(" + x + ", " + y + ") is " + rho + " unit radii from the projection centre, "
                        + "outside the disc the orthographic projection fills");
    }

    private ProjectionException offEllipse(double x, double y) {
        return new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                "(" + x + ", " + y + ") is outside the ellipse bounding the ellipsoidal "
                        + "orthographic projection");
    }

    private ProjectionException unreachableMode() {
        return new ProjectionException(ErrorCause.NUMERICAL_FAILURE, this,
                "unreachable azimuthal mode " + mode);
    }

    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public String toString() {
        return "Orthographic Azimuthal";
    }
}

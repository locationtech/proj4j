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
package org.locationtech.proj4j.proj;

import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.FastStrictTrig;

/**
 * Near-sided perspective, {@code +proj=nsper} &mdash; a port of
 * {@code 9.8.1:src/projections/nsper.cpp}. Spherical only; both directions are closed form.
 *
 * <p>The view from a point {@code +h} metres above the centre of projection, cast onto the
 * plane tangent there. As {@code h} grows the map tends to
 * {@link OrthographicAzimuthalProjection orthographic}. The sibling {@code +proj=tpers}
 * ({@link TiltedPerspectiveProjection}) adds a tilted image plane; it is the same upstream
 * file and shares the whole setup.
 *
 * <h2>What this class used to be</h2>
 *
 * <p>Before this rewrite it was a PROJ-4-era half-transcription with its setup commented
 * out. Three consequences, all silent:
 *
 * <ul>
 * <li>{@code mode} was hard-assigned {@code EQUIT} in {@code initialize()}, so <em>every</em>
 *     aspect answered the equatorial formulas &mdash; {@code +lat_0=90} projected as if
 *     {@code +lat_0=0}.</li>
 * <li>{@code height} was hard-assigned {@code a}, one Earth radius, and
 *     {@link Projection#setHeightOfOrbit(double)} was not overridden, so
 *     {@code +proj=nsper +h=1000000} <em>threw</em> rather than answering from the wrong
 *     orbit. That refusal was the honest outcome, and it is why the 20 {@code builtins.gie}
 *     rows scored as unimplemented rather than as wrong numbers.</li>
 * <li>{@code if (xy.y &lt; rp)} &mdash; the far-hemisphere rejection &mdash; was commented
 *     out, and there was no inverse at all ({@code hasInverse()} answered false) even though
 *     upstream has one.</li>
 * </ul>
 *
 * <h2>Fidelity notes</h2>
 *
 * <p><b>{@code es} is zeroed, {@code e} is not.</b> {@code nsper_setup} ends with
 * {@code P-&gt;es = 0.} and leaves {@code P-&gt;e}, {@code P-&gt;one_es} and
 * {@code P-&gt;rone_es} at whatever the ellipsoid gave them. That is reproduced literally:
 * the assignment happens <em>after</em> {@code super.initialize()}, which is where the C
 * ordering puts it (ellipsoid init, then the projection's own setup). It is unobservable in
 * the corpus, whose six {@code nsper} and two {@code tpers} operations all use {@code +a=} or
 * {@code +R=}, but regularising it would be a deviation rather than a fix. Compare the
 * identical upstream quirk in {@code mod_ster}'s {@code mil_os}/{@code lee_os}/{@code gs48}.
 *
 * <p><b>{@code pn1} is validated, not {@code h}.</b> Upstream rejects
 * {@code pn1 = h/a &lt;= 0 || pn1 &gt; 1e10} with
 * {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE}, so the bound is on the <em>ratio</em>: at
 * {@code +R=1} a height of {@code 1e11} is refused, and at {@code +a=6378137} the same height
 * is accepted. Two corpus rows assert the two rejections.
 *
 * <p><b>{@code initialize()} runs twice</b> &mdash; once from the constructor and once from
 * {@code Proj4Parser}. Every derived quantity here is a pure function of {@code height},
 * {@code a} and {@code projectionLatitude}, and none is read while being written, so the
 * method is idempotent. {@code height} itself is only ever written by
 * {@link #setHeightOfOrbit(double)}.
 *
 * @see TiltedPerspectiveProjection
 * @see <a href="https://github.com/OSGeo/PROJ/blob/9.8.1/src/projections/nsper.cpp">9.8.1
 *      nsper.cpp</a>
 */
public class PerspectiveProjection extends Projection {

    private static final long serialVersionUID = 4270222224128526324L;

    private static final double EPS10 = 1.e-10;

    private static final int N_POLE = 0;
    private static final int S_POLE = 1;
    private static final int EQUIT = 2;
    private static final int OBLIQ = 3;

    /**
     * {@code +h}, the height of the viewpoint above the centre of projection, in metres.
     * <p>
     * <b>No default.</b> {@code pj_param(..., "dh").f} answers 0 for an absent {@code +h} and
     * {@code nsper_setup} then rejects {@code pn1 == 0}, so a bare {@code +proj=nsper} is an
     * error upstream. Initialised to 0 for exactly that reason, rather than to some plausible
     * orbit.
     */
    private double height = 0.0;

    private double sinph0;
    private double cosph0;
    private double p;
    private double rp;
    private double pn1;
    private double pfact;
    private double hRecip;
    private int mode;

    /** {@code Q->cg}, {@code cos(gamma)}. Only {@code tpers} moves it off the identity. */
    double cg = 1.0;
    /** {@code Q->sg}, {@code sin(gamma)}. */
    double sg = 0.0;
    /** {@code Q->sw}, {@code sin(omega)}. */
    double sw = 0.0;
    /** {@code Q->cw}, {@code cos(omega)}. */
    double cw = 1.0;
    /**
     * {@code Q->tilt}. False for {@code nsper} and true for {@code tpers}; the two forwards
     * and the two inverses differ only by the block this flag guards.
     */
    boolean tilt = false;

    /**
     * {@code +h}, the height of the perspective viewpoint above the surface, in metres.
     * <p>
     * Overriding this is the whole reason {@code +proj=nsper +h=1000000} now builds: the base
     * class refuses, deliberately, because it cannot tell an inapplicable {@code +h} from an
     * unimplemented one (see {@link Projection#setHeightOfOrbit(double)}).
     * <p>
     * The value is <em>not</em> validated here. Upstream validates {@code h/a}, not {@code h},
     * and cannot do so until the ellipsoid is known &mdash; so the check lives in
     * {@link #initialize()}.
     *
     * @param h height of the viewpoint above the centre of projection, in metres
     */
    @Override
    public void setHeightOfOrbit(double h) {
        this.height = h;
    }

    /**
     * The {@code +h} in force.
     *
     * @return the height of the viewpoint above the centre of projection, in metres
     */
    @Override
    public double getHeightOfOrbit() {
        return height;
    }

    /**
     * Port of {@code nsper_setup} ({@code nsper.cpp:139-166}).
     *
     * @throws InvalidValueException if {@code h/a} is non-positive or exceeds {@code 1e10},
     *         where upstream raises {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE}
     */
    @Override
    public void initialize() {
        super.initialize();

        if (Math.abs(Math.abs(projectionLatitude) - Math.PI / 2.0) < EPS10) {
            mode = projectionLatitude < 0.0 ? S_POLE : N_POLE;
            sinph0 = 0.0;
            cosph0 = 0.0;
        } else if (Math.abs(projectionLatitude) < EPS10) {
            mode = EQUIT;
            sinph0 = 0.0;
            cosph0 = 0.0;
        } else {
            mode = OBLIQ;
            sinph0 = FastStrictTrig.sin(projectionLatitude);
            cosph0 = FastStrictTrig.cos(projectionLatitude);
        }

        pn1 = height / a; /* normalize by radius */
        if (pn1 <= 0 || pn1 > 1e10) {
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                    "+h=" + height + " gives h/a = " + pn1 + ", which " + getName()
                            + " rejects: upstream requires 0 < h/a <= 1e10 "
                            + "(nsper.cpp:155-159). The bound is on the ratio, not on h.");
        }
        p = 1.0 + pn1;
        rp = 1.0 / p;
        hRecip = 1.0 / pn1;
        pfact = (p + 1.0) * hRecip;

        // nsper_setup:164, P->es = 0. Deliberately after super.initialize(), and deliberately
        // not touching e/one_es/rone_es: see the class javadoc.
        es = 0.0;
    }

    /**
     * {@code nsper_s_forward}, {@code nsper.cpp:38-87}.
     *
     * @throws ProjectionException at {@code xy.y &lt; rp}, the far side of the globe as seen
     *         from the viewpoint, where upstream sets
     *         {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN}
     */
    @Override
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate xy) {
        final double sinphi = FastStrictTrig.sin(phi);
        final double cosphi = FastStrictTrig.cos(phi);
        double coslam = FastStrictTrig.cos(lam);

        switch (mode) {
            case OBLIQ:
                xy.y = sinph0 * sinphi + cosph0 * cosphi * coslam;
                break;
            case EQUIT:
                xy.y = cosphi * coslam;
                break;
            case S_POLE:
                xy.y = -sinphi;
                break;
            default: // N_POLE
                xy.y = sinphi;
                break;
        }
        if (xy.y < rp) {
            throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                    "the point is on the far side of the globe as seen from a viewpoint "
                            + height + " m up: cos(angular distance) = " + xy.y
                            + " < 1/(1 + h/a) = " + rp + " (nsper.cpp:61)");
        }
        xy.y = pn1 / (p - xy.y);
        xy.x = xy.y * cosphi * FastStrictTrig.sin(lam);
        switch (mode) {
            case OBLIQ:
                xy.y *= (cosph0 * sinphi - sinph0 * cosphi * coslam);
                break;
            case EQUIT:
                xy.y *= sinphi;
                break;
            case N_POLE:
                coslam = -coslam;
                xy.y *= cosphi * coslam; // PROJ_FALLTHROUGH into S_POLE, nsper.cpp:75
                break;
            default: // S_POLE
                xy.y *= cosphi * coslam;
                break;
        }
        if (tilt) {
            final double yt = xy.y * cg + xy.x * sg;
            final double ba = 1.0 / (yt * sw * hRecip + cw);
            xy.x = (xy.x * cg - xy.y * sg) * cw * ba;
            xy.y = yt * ba;
        }
        return xy;
    }

    /**
     * {@code nsper_s_inverse}, {@code nsper.cpp:89-137}.
     *
     * <p>The {@code rh &lt;= EPS10} short circuit answers {@code (0, phi0)} and therefore
     * discards the tilt un-warp it has just performed. Upstream does the same, and it is
     * consistent, because at the image centre the tilt is a no-op.
     *
     * @throws ProjectionException when the point lies off the visible disc, i.e.
     *         {@code 1 - rh*rh*pfact &lt; 0}
     */
    @Override
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
        if (tilt) {
            final double yt = 1.0 / (pn1 - y * sw);
            final double bm = pn1 * x * yt;
            final double bq = pn1 * y * cw * yt;
            x = bm * cg + bq * sg;
            y = bq * cg - bm * sg;
        }
        final double rh = StrictMath.hypot(x, y);
        if (Math.abs(rh) <= EPS10) {
            lp.x = 0.0;
            lp.y = projectionLatitude;
            return lp;
        }
        double sinz = 1.0 - rh * rh * pfact;
        if (sinz < 0.0) {
            throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                    "the projected point (" + x + ", " + y + ") is outside the visible disc: "
                            + "1 - rh^2*pfact = " + sinz + " < 0 (nsper.cpp:111)");
        }
        sinz = (p - Math.sqrt(sinz)) / (pn1 / rh + rh / pn1);
        final double cosz = Math.sqrt(1.0 - sinz * sinz);
        switch (mode) {
            case OBLIQ:
                lp.y = StrictMath.asin(cosz * sinph0 + y * sinz * cosph0 / rh);
                y = (cosz - sinph0 * FastStrictTrig.sin(lp.y)) * rh;
                x *= sinz * cosph0;
                break;
            case EQUIT:
                lp.y = StrictMath.asin(y * sinz / rh);
                y = cosz * rh;
                x *= sinz;
                break;
            case N_POLE:
                lp.y = StrictMath.asin(cosz);
                y = -y;
                break;
            default: // S_POLE
                lp.y = -StrictMath.asin(cosz);
                break;
        }
        lp.x = StrictMath.atan2(x, y);
        return lp;
    }

    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public String toString() {
        return "Near-sided perspective";
    }
}

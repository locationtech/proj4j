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

import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.MeridianArc;

/**
 * Equidistant Cylindrical, {@code +proj=eqc} — Plate Carr&eacute;e when {@code +lat_ts=0}.
 *
 * <p>Port of {@code 9.8.1:src/projections/eqc.cpp}, which implements <b>both</b> EPSG:1029
 * (spherical) and EPSG:1028 (ellipsoidal, from IOGP Guidance Note 7-2 &sect;3.2.5) and dispatches
 * on {@code P-&gt;es != 0}.
 *
 * <pre>
 * spherical:    x = cos(lat_ts) * lam                 y = phi - phi0
 * ellipsoidal:  x = nu1 * cos(lat_ts) * lam           y = M(phi) - M(phi0)
 *               nu1 = 1 / sqrt(1 - es sin^2(lat_ts))
 * </pre>
 *
 * <h2>What this class used to be</h2>
 *
 * <p><b>An identity.</b> It declared {@code hasInverse()} and {@code isRectilinear()} and nothing
 * else, so every {@code +proj=eqc} coordinate fell through to {@code Projection}'s base
 * {@code dst.x = x; dst.y = y}. That happens to be right for {@code +proj=eqc +a=R} with no other
 * parameters, which is why 12 of the corpus's 34 {@code eqc} assertions passed — and it is why the
 * other 22 failed by between 17 km and 5,010 km: <b>{@code +lat_ts} was not applied,
 * {@code +lat_0} was not applied, and there was no ellipsoidal branch at all</b>. The 5,010 km
 * rows are the {@code +lat_0=45} block, where a missing {@code M0} leaves the whole northing
 * offset by the meridian arc to 45&deg;.
 *
 * @see <a href="https://github.com/OSGeo/PROJ/blob/9.8.1/src/projections/eqc.cpp">9.8.1 eqc.cpp</a>
 */
public class PlateCarreeProjection extends CylindricalProjection {

    private static final long serialVersionUID = -4585080606120086184L;

    /**
     * Upstream's {@code Q-&gt;rc}: {@code cos(lat_ts)} spherically, {@code nu1 * cos(lat_ts)} on
     * the ellipsoid. Note it is <em>dimensionless</em> in both cases — {@code nu1} is normalised
     * by {@code a}, and the {@code a} multiply happens in {@code Projection}'s affine tail.
     */
    private double rc = 1.0;

    /** Upstream's {@code Q-&gt;M0}: the meridian arc at {@code +lat_0}, 0 on a sphere. */
    private double m0;

    /** Upstream's {@code Q-&gt;en}. Null in the spherical case, where upstream leaves it null too. */
    private MeridianArc meridian;

    /**
     * Derives {@code rc}, {@code M0} and the meridian-arc series. Port of
     * {@code PJ_PROJECTION(eqc)}.
     *
     * @throws InvalidValueException if {@code cos(lat_ts) &lt;= 0}, i.e. {@code |lat_ts| &gt; 90}
     *                               degrees, which upstream rejects with
     *                               {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE}. Note the test
     *                               is on the cosine and not on the angle, so it accepts
     *                               {@code lat_ts} exactly at a pole and rejects past it.
     */
    @Override
    public void initialize() {
        super.initialize();
        final double phi1 = trueScaleLatitude;
        final double cosPhi1 = FastStrictTrig.cos(phi1);
        if (cosPhi1 <= 0.) {
            throw new InvalidValueException(
                    "Invalid value for +lat_ts: |lat_ts| should be <= 90 degrees, but is "
                            + Math.toDegrees(phi1));
        }
        if (es != 0.0) {
            final double sinPhi1 = FastStrictTrig.sin(phi1);
            // nu1 normalised by a, exactly as upstream writes it.
            final double nu1 = 1.0 / Math.sqrt(1.0 - es * sinPhi1 * sinPhi1);
            rc = nu1 * cosPhi1;
            meridian = MeridianArc.fromEs(es);
            m0 = meridian.mlfn(projectionLatitude);
        } else {
            rc = cosPhi1;
            meridian = null;
            m0 = 0.0;
        }
    }

    /** Port of {@code eqc_s_forward}/{@code eqc_e_forward}. */
    @Override
    public ProjCoordinate project(double lam, double phi, ProjCoordinate xy) {
        xy.x = rc * lam;
        xy.y = meridian == null ? phi - projectionLatitude : meridian.mlfn(phi) - m0;
        return xy;
    }

    /** Port of {@code eqc_s_inverse}/{@code eqc_e_inverse}. */
    @Override
    public ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
        lp.x = x / rc;
        lp.y = meridian == null ? y + projectionLatitude : meridian.invMlfn(y + m0);
        return lp;
    }

    public boolean hasInverse() {
        return true;
    }

    public boolean isRectilinear() {
        return true;
    }

    public String toString() {
        return "Plate Carr\u00e9e";
    }

}

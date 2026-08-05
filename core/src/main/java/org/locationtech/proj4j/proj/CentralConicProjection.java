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
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.util.FastStrictTrig;

/**
 * Central Conic, {@code +proj=ccon} &mdash; a port of
 * {@code 9.8.1:src/projections/ccon.cpp}. Spherical only; both directions closed form and
 * four lines each.
 *
 * <p>A cone tangent at {@code +lat_1}, on which distances along the <em>central</em> meridian
 * are true and the parallels are circles of radius {@code cot(lat_1) - tan(phi - lat_1)}. The
 * projection has no standard parallel other than {@code lat_1} and no ellipsoidal form.
 *
 * <pre>
 *   r = cot(phi_1) - tan(phi - phi_1)
 *   x = r sin(lam sin phi_1)
 *   y = cot(phi_1) - r cos(lam sin phi_1)
 * </pre>
 *
 * <h2>Two things upstream does that look like mistakes</h2>
 *
 * <p><b>{@code Q-&gt;en} is computed and never read.</b> {@code PJ_PROJECTION(ccon)} calls
 * {@code pj_enfn(P-&gt;n)} and stores the meridian-arc coefficients, and the destructor frees
 * them, but neither the forward nor the inverse touches them &mdash; the projection is
 * spherical. Nothing is ported for it.
 *
 * <p><b>The inverse's radius is {@code hypot(x, y) - cot(phi_1)}, not the other way round,</b>
 * and it is fed to {@code atan} without a sign correction. It is exactly the algebraic inverse
 * of the forward for {@code phi_1 &gt; 0}; for {@code phi_1 &lt; 0} the cone opens the other
 * way and the pair is no longer mutually inverse everywhere. Reproduced verbatim; the corpus's
 * only {@code ccon} block is at {@code +lat_1=52}.
 *
 * <h2>{@code +lat_1} is mandatory</h2>
 *
 * <p>{@code |lat_1| &lt; 1e-10} is rejected with
 * {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE} &mdash; {@code cot} would be infinite. Since
 * {@code pj_param}'s absent-key value is 0, that check also makes a bare {@code +proj=ccon} an
 * error, which is why the field's initialiser is 0 rather than any plausible parallel.
 *
 * <p>{@code initialize()} runs twice (constructor and parser). {@code phi1} is only ever
 * written by {@link Projection#setProjectionLatitude1(double)}; everything else here is
 * derived from it on each call.
 *
 * @see <a href="https://github.com/OSGeo/PROJ/blob/9.8.1/src/projections/ccon.cpp">9.8.1
 *      ccon.cpp</a>
 */
public class CentralConicProjection extends ConicProjection {

    private static final long serialVersionUID = -8732698340965281734L;

    private static final double EPS10 = 1e-10;

    private double sinphi1;
    private double ctgphi1;

    /**
     * Port of {@code PJ_PROJECTION(ccon)} ({@code ccon.cpp:76-101}).
     *
     * @throws InvalidValueException if {@code |lat_1| < 1e-10}, including the absent case
     */
    @Override
    public void initialize() {
        super.initialize();
        if (Math.abs(projectionLatitude1) < EPS10) {
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                    "+proj=ccon requires +lat_1 and |lat_1| must be > 0; got "
                            + projectionLatitude1 + " rad. The cone's cot(lat_1) is infinite at "
                            + "the equator (ccon.cpp:86-89). Note pj_param answers 0 for an "
                            + "absent +lat_1, so a bare +proj=ccon is this same error.");
        }
        sinphi1 = FastStrictTrig.sin(projectionLatitude1);
        ctgphi1 = FastStrictTrig.cos(projectionLatitude1) / sinphi1;
    }

    /** {@code ccon_forward}, {@code ccon.cpp:42-52}. */
    @Override
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate xy) {
        final double r = ctgphi1 - FastStrictTrig.tan(phi - projectionLatitude1);
        xy.x = r * FastStrictTrig.sin(lam * sinphi1);
        xy.y = ctgphi1 - r * FastStrictTrig.cos(lam * sinphi1);
        return xy;
    }

    /** {@code ccon_inverse}, {@code ccon.cpp:54-63}. */
    @Override
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
        final double yy = ctgphi1 - y;
        lp.y = projectionLatitude1 - StrictMath.atan(StrictMath.hypot(x, yy) - ctgphi1);
        lp.x = StrictMath.atan2(x, yy) / sinphi1;
        return lp;
    }

    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public String toString() {
        return "Central Conic";
    }
}

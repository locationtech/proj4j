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
 * Gauss-Schreiber Transverse Mercator, {@code +proj=gstmerc} (also known as
 * Gauss-Laborde R&eacute;union) &mdash; a port of
 * {@code 9.8.1:src/projections/gstmerc.cpp}.
 *
 * <p>A double-projection transverse Mercator: the ellipsoid is first mapped conformally onto a
 * sphere of radius {@code n2} (the Gauss-Schreiber sphere), then a spherical transverse
 * Mercator is applied on that sphere. Upstream marks it {@code Sph&Ell} but there is only one
 * kernel &mdash; the spherical case simply has {@code es == 0}, which collapses {@code n1} to 1
 * and {@code phic} to {@code phi0}.
 *
 * <h2>Where the scaling lives</h2>
 *
 * <p>This is the detail that makes or breaks the port. Upstream's forward ends with
 * {@code * P-&gt;ra} ({@code = 1/a}) and the {@code a} multiply comes back in {@code fwd.cpp}'s
 * affine tail &mdash; and {@code Q-&gt;n2} already contains {@code P-&gt;k0 * P-&gt;a}. So
 * {@code +k_0} is applied <em>inside</em> {@code n2} and must not be applied again;
 * {@link Projection} does not scale by {@code scaleFactor} on its own, so the two agree.
 *
 * <p>The inverse's mirror image is {@code xy.x * P-&gt;a}. {@link Projection}'s inverse funnel
 * has already divided by {@code totalScale = a * fromMetres}, so multiplying by {@code a} here
 * restores exactly upstream's operand.
 *
 * <h2>{@code pj_tsfn} is called three ways</h2>
 *
 * <p>All three calls pass <em>negated</em> arguments, which is upstream's way of asking for
 * {@code tan(pi/4 + phi/2)} rather than {@code tan(pi/4 - phi/2)}, and two of them pass
 * {@code e = 0} to get the spherical form on the Gauss-Schreiber sphere while the third passes
 * the real {@code e}. Getting either the sign or the eccentricity wrong is a silent, plausible
 * wrong answer, so they are transcribed one for one. {@link ConformalLat#tsfn} and
 * {@link ConformalLat#phi2} are the ports; the deprecated {@code ProjectionMath} twins are
 * deliberately not used.
 *
 * <p>{@code initialize()} runs twice; every field below is a pure function of
 * {@code projectionLatitude}, {@code scaleFactor}, {@code a} and {@code es}.
 *
 * @see <a href="https://github.com/OSGeo/PROJ/blob/9.8.1/src/projections/gstmerc.cpp">9.8.1
 *      gstmerc.cpp</a>
 */
public class GaussSchreiberTransverseMercatorProjection extends CylindricalProjection {

    private static final long serialVersionUID = -6228068182629941304L;

    /** {@code Q->c}. */
    private double c;
    /** {@code Q->n1}, the ellipsoid-to-sphere latitude ratio. */
    private double n1;
    /** {@code Q->n2}, the Gauss-Schreiber sphere's radius times {@code k_0}. */
    private double n2;
    /** {@code Q->XS}, always zero upstream, retained for legibility. */
    private double xs;
    /** {@code Q->YS}. */
    private double ys;

    /** Port of {@code PJ_PROJECTION(gstmerc)} ({@code gstmerc.cpp:56-80}). */
    @Override
    public void initialize() {
        super.initialize();
        final double phi0 = projectionLatitude;
        final double sinphi0 = FastStrictTrig.sin(phi0);
        final double cosphi0 = FastStrictTrig.cos(phi0);

        n1 = Math.sqrt(1 + es * Math.pow(cosphi0, 4.0) / (1 - es));
        final double phic = StrictMath.asin(sinphi0 / n1);
        c = Math.log(ConformalLat.tsfn(-phic, -sinphi0 / n1, 0.0))
                - n1 * Math.log(ConformalLat.tsfn(-phi0, -sinphi0, e));
        n2 = scaleFactor * a * Math.sqrt(1 - es) / (1 - es * sinphi0 * sinphi0);
        xs = 0;
        ys = -n2 * phic;
    }

    /** {@code gstmerc_s_forward}, {@code gstmerc.cpp:24-37}. */
    @Override
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate xy) {
        final double l = n1 * lam;
        final double ls = c + n1 * Math.log(ConformalLat.tsfn(-phi, -FastStrictTrig.sin(phi), e));
        final double sinLs1 = FastStrictTrig.sin(l) / StrictMath.cosh(ls);
        final double ls1 = Math.log(ConformalLat.tsfn(-StrictMath.asin(sinLs1), -sinLs1, 0.0));
        // * P->ra: Projection's affine tail multiplies by a again, and n2 carries k_0 * a.
        xy.x = (xs + n2 * ls1) / a;
        xy.y = (ys + n2 * StrictMath.atan(StrictMath.sinh(ls) / FastStrictTrig.cos(l))) / a;
        return xy;
    }

    /** {@code gstmerc_s_inverse}, {@code gstmerc.cpp:39-54}. */
    @Override
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
        final double xa = x * a;
        final double ya = y * a;
        final double l = StrictMath.atan(
                StrictMath.sinh((xa - xs) / n2) / FastStrictTrig.cos((ya - ys) / n2));
        final double sinC = FastStrictTrig.sin((ya - ys) / n2) / StrictMath.cosh((xa - xs) / n2);
        final double lc = Math.log(ConformalLat.tsfn(-StrictMath.asin(sinC), -sinC, 0.0));
        lp.x = l / n1;
        lp.y = -ConformalLat.phi2(Math.exp((lc - c) / n1), e);
        return lp;
    }

    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public boolean isConformal() {
        return true;
    }

    @Override
    public String toString() {
        return "Gauss-Schreiber Transverse Mercator";
    }
}

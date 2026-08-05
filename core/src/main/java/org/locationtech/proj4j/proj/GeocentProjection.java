/*
 * Copyright 2026 The Proj4J Contributors.
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
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.datum.GeocentricConverter;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * {@code +proj=geocent}: geodetic {@code (lambda, phi, h)} to geocentric cartesian
 * {@code (X, Y, Z)} in metres, and back.
 *
 * <p>Not a projection. Its right-hand side is three-dimensional and cartesian, which is why it
 * overrides {@link #projectRadians(ProjCoordinate, ProjCoordinate)} and
 * {@link #inverseProjectRadians(ProjCoordinate, ProjCoordinate)} wholesale rather than supplying a
 * {@code project(double, double, ProjCoordinate)} kernel: the two-ordinate kernel signature cannot
 * carry {@code z}, and the affine post-multiply the base funnel applies to a projected result is
 * meaningless on a geocentric triple. Upstream draws the same line —
 * {@code 9.8.1:src/conversions/geocent.cpp} declares {@code P->right = PJ_IO_UNITS_CARTESIAN} and
 * its own {@code fwd}/{@code inv} are the identity, with the real work done by
 * {@code fwd_finalize}/{@code inv_prepare} calling {@code +proj=cart}.
 *
 * <h2>What was wrong before 1.5.0</h2>
 *
 * <p>Both directions <b>read {@code dst} instead of {@code src}</b>:
 *
 * <pre>{@code
 * public ProjCoordinate projectRadians(ProjCoordinate src, ProjCoordinate dst) {
 *     new GeocentricConverter(this.ellipsoid).convertGeodeticToGeocentric(dst);   // dst!
 *     return dst;
 * }
 * }</pre>
 *
 * <p>So the input was ignored and the *output* buffer was converted in place. It survived because
 * the only caller in {@code core} aliases the two arguments:
 * {@code BasicCoordinateTransform.transformClosed} does {@code tgt.setValue(src)} and then calls
 * {@code projectRadians(tgt, tgt)} and {@code inverseProjectRadians(tgt, tgt)}. With
 * {@code src == dst} reading {@code dst} happens to read the input, so every CRS transform through
 * {@code +proj=geocent} was accidentally correct and no test — there were none for this class —
 * could see the defect. It bites the moment the two arguments differ, which is the documented
 * contract of the public {@link #project(ProjCoordinate, ProjCoordinate)},
 * {@link #projectRadians(ProjCoordinate, ProjCoordinate)},
 * {@link #inverseProject(ProjCoordinate, ProjCoordinate)} and
 * {@link #inverseProjectRadians(ProjCoordinate, ProjCoordinate)}, and is what
 * {@code pipeline.Cs2csOperator.projectForward} does (fresh {@code src} and {@code dst}) for every
 * other projection — it special-cases {@code geocent} onto its own {@code CartConversion} and so
 * never exercised this code either.
 *
 * <p>{@link #project(ProjCoordinate, ProjCoordinate)} was worse than wrong: the degrees-in entry
 * point is not virtual through {@code projectRadians(src, dst)} — the base class routes it into the
 * *private* two-ordinate funnel — so it never reached this class at all and returned the base
 * identity plus the affine. It is overridden here for that reason.
 *
 * <h2>Why {@code hasInverse()} has to be declared</h2>
 *
 * <p>{@code BasicCoordinateTransform.inverseAvailable} answers "is there an implementation" by
 * {@code hasInverse() || isGeographic()}, then by looking for a declared
 * {@code projectInverse(double, double, ProjCoordinate)} up the class hierarchy. This class has no
 * such method — it cannot, see above — so before {@code hasInverse()} was declared here the gate
 * classified every {@code +proj=geocent} CRS as non-invertible and refused it as a transformation
 * <em>source</em>. That is 330 CRS in the registry dictionaries (181 {@code epsg} defs plus the
 * pairs the MetaCRS CSVs reference), all of which round-tripped in 1.4.3.
 *
 * <h2>Iterative or closed form?</h2>
 *
 * <p>PROJ 9.8.1 has two geocentric-to-geodetic implementations and {@code +proj=geocent} uses the
 * <b>closed form</b>: {@code geocent.cpp} builds {@code P->cart} ({@code create.cpp:167-175}) and
 * {@code inv_prepare} ({@code inv.cpp:65-70}) runs it, and {@code cart.cpp:156-230} is Bowring's
 * closed form. proj4j's {@link GeocentricConverter} is the other one — the 1996
 * Toms/Hannover iteration ported from PROJ.4's {@code geocent.c}, converging on {@code sin(phi)}
 * to {@code 1e-12} (about 6 um of latitude).
 *
 * <p>This class delegates to {@link GeocentricConverter} anyway, deliberately:
 *
 * <ul>
 * <li>The <b>forward</b> is not a choice: {@code GeocentricConverter.convertGeodeticToGeocentric}
 *     and {@code cart.cpp}'s {@code cartesian()} are the same expression in the same order —
 *     {@code (N + h) * cos(phi) * cos(lam)}, {@code (N + h) * cos(phi) * sin(lam)},
 *     {@code (N * (1 - es) + h) * sin(phi)} with {@code N = a / sqrt(1 - es * sin^2(phi))} — so
 *     they agree bit for bit on the same {@code a} and {@code es}.</li>
 * <li>The <b>inverse</b> differs, and the legacy engine this class serves already uses
 *     {@code GeocentricConverter} for its datum stage
 *     ({@code BasicCoordinateTransform.datumTransform}, a port of PROJ 5.2.0's
 *     {@code pj_transform.c}). Using Bowring here and the iteration one stage later would make a
 *     {@code geocent}-to-{@code geocent} transform disagree with itself by which stage ran. One
 *     kernel per engine is the property worth having: the legacy engine is iterative throughout,
 *     the pipeline engine is Bowring throughout ({@code pipeline.CartConversion}, which is
 *     package-private and so not reachable from here in any case).</li>
 * </ul>
 *
 * <p>The residual ~6 um divergence from 9.8.1 is therefore a property of the <em>engine</em>, not
 * of this class, and it is the pipeline engine's job to retire it by taking over
 * {@code +proj=geocent} — which it already does.
 *
 * <h2>Known divergences from 9.8.1, left in place on purpose</h2>
 *
 * <ul>
 * <li><b>{@code +to_meter} / {@code +units} are ignored.</b> {@code fwd_finalize}
 *     ({@code fwd.cpp:127-137}) multiplies all three cartesian ordinates by {@code fr_meter} and
 *     {@code inv_prepare} ({@code inv.cpp:64-70}) by {@code to_meter}. Every {@code geocent} def
 *     in the five registry dictionaries is {@code +units=m}, so honouring it would move no
 *     observable row; it is recorded rather than added silently.</li>
 * <li><b>{@code +x_0} / {@code +y_0} are ignored, and that is correct.</b>
 *     {@code geocent.cpp:53-54} forces {@code P->x0 = P->y0 = 0}, and {@code fwd_finalize}'s
 *     {@code PJ_IO_UNITS_CARTESIAN} branch never adds them.</li>
 * <li><b>{@code +lon_0} <em>is</em> honoured</b>, because upstream honours it:
 *     {@code fwd_prepare} does {@code lam = (lam - from_greenwich) - lam0} then {@code adjlon}
 *     ({@code fwd.cpp:105-112}) and {@code inv_finalize} adds it back
 *     ({@code inv.cpp:110-118}), for cartesian right-hand sides as much as for projected ones.
 *     {@code pipeline.Cs2csOperator} ports exactly that for its {@code GEOCENT} kernel. The
 *     override used to skip it.</li>
 * </ul>
 *
 * @see GeocentricConverter
 */
public class GeocentProjection extends Projection {

    private static final long serialVersionUID = 6460444409174128890L;

    /**
     * The converter and the ellipsoid it was derived from, as one object so that the pair is
     * published atomically. 1.4.3 allocated a {@link GeocentricConverter} on every single
     * coordinate; the ellipsoid can still be replaced after construction
     * ({@code Projection.setEllipsoid}), so the cache is keyed on it rather than built once.
     *
     * <p>The race is benign: two threads may each build an equivalent converter and one write
     * wins. Neither can observe a partially built one, because the field is {@code volatile}.
     */
    private static final class Cached {
        private final Ellipsoid ellipsoid;
        private final GeocentricConverter converter;

        Cached(Ellipsoid ellipsoid) {
            this.ellipsoid = ellipsoid;
            this.converter = new GeocentricConverter(ellipsoid);
        }
    }

    private volatile Cached cached;

    private GeocentricConverter converter() {
        Ellipsoid e = getEllipsoid();
        Cached c = cached;
        if (c == null || c.ellipsoid != e) {
            c = new Cached(e);
            cached = c;
        }
        return c.converter;
    }

    /**
     * The inverse is real and it is {@link #inverseProjectRadians(ProjCoordinate, ProjCoordinate)}.
     * Declared because there is no {@code projectInverse(double, double, ProjCoordinate)} for
     * {@code BasicCoordinateTransform.inverseAvailable} to find; see the class javadoc.
     */
    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public String toString() {
        return "Geocentric";
    }

    /**
     * Geodetic degrees to geocentric metres.
     *
     * <p>Overridden because the base class routes this entry point into its private two-ordinate
     * funnel and so would never reach
     * {@link #projectRadians(ProjCoordinate, ProjCoordinate)}: the degrees-in caller would get the
     * base identity, in degrees, with the false easting added.
     *
     * @param src geodetic {@code (lambda, phi)} in degrees, {@code z} in metres
     * @param dst geocentric {@code (X, Y, Z)} in metres; may be the same object as {@code src}
     * @return {@code dst}
     */
    @Override
    public ProjCoordinate project(ProjCoordinate src, ProjCoordinate dst) {
        double h = src.hasValidZOrdinate() ? src.z : 0.0;
        return forward(src.x * ProjectionMath.DTR, src.y * ProjectionMath.DTR, h, dst);
    }

    /**
     * Geodetic radians to geocentric metres.
     *
     * @param src geodetic {@code (lambda, phi)} in radians, {@code z} in metres
     * @param dst geocentric {@code (X, Y, Z)} in metres; may be the same object as {@code src}
     * @return {@code dst}
     */
    @Override
    public ProjCoordinate projectRadians(ProjCoordinate src, ProjCoordinate dst) {
        double h = src.hasValidZOrdinate() ? src.z : 0.0;
        return forward(src.x, src.y, h, dst);
    }

    /**
     * Geocentric metres to geodetic radians.
     *
     * @param src geocentric {@code (X, Y, Z)} in metres
     * @param dst geodetic {@code (lambda, phi)} in radians and {@code z} in metres; may be the
     *            same object as {@code src}
     * @return {@code dst}
     */
    @Override
    public ProjCoordinate inverseProjectRadians(ProjCoordinate src, ProjCoordinate dst) {
        double x = src.x;
        double y = src.y;
        double z = src.hasValidZOrdinate() ? src.z : 0.0;
        if (!isFinite(x) || !isFinite(y) || !isFinite(z)) {
            // inv_prepare (9.8.1:src/inv.cpp:40-45) rejects HUGE_VAL on all three ordinates,
            // cartesian input included. NaN is rejected too, for the reason
            // Projection.checkForwardDomain gives: a NaN that was asked for is indistinguishable
            // downstream from one the kernel invented.
            throw new ProjectionException(ErrorCause.INVALID_COORDINATE, this,
                    "non-finite geocentric input (" + x + ", " + y + ", " + z + ") m");
        }

        // Read src, write dst -- and write all three before converting, so that the conversion
        // reads the input whether or not the caller aliased the two arguments.
        dst.x = x;
        dst.y = y;
        dst.z = z;
        converter().convertGeocentricToGeodetic(dst);
        checkFinite(dst, "inverse", x, y, z);

        // inv_finalize's PJ_IO_UNITS_RADIANS branch (inv.cpp:110-118). Guarded on != 0 exactly as
        // Projection.inverseProjectRadians guards it, so that a definition without +lon_0 is
        // bit-for-bit unchanged -- `x + 0.0` is not the identity on -0.0.
        if (projectionLongitude != 0) {
            dst.x = ProjectionMath.normalizeLongitude(dst.x + projectionLongitude);
        }
        return dst;
    }

    /**
     * The single forward body. {@code lam}/{@code phi} in radians, {@code h} in metres.
     */
    private ProjCoordinate forward(double lam, double phi, double h, ProjCoordinate dst) {
        // fwd_prepare's angular input contract (9.8.1:src/fwd.cpp:54-77) applies to geocent as
        // much as to any projection: geocent.cpp:56 declares P->left = PJ_IO_UNITS_RADIANS.
        phi = checkForwardDomain(lam, phi);
        if (!isFinite(h)) {
            throw new ProjectionException(ErrorCause.INVALID_COORDINATE, this,
                    "non-finite geodetic height " + h + " m");
        }
        // fwd_prepare's lam0 subtraction, fwd.cpp:105-112. Guarded on != 0 for the -0.0 reason
        // given in inverseProjectRadians.
        if (projectionLongitude != 0) {
            lam = ProjectionMath.normalizeLongitude(lam - projectionLongitude);
        }

        dst.x = lam;
        dst.y = phi;
        dst.z = h;
        converter().convertGeodeticToGeocentric(dst);
        checkFinite(dst, "forward", lam, phi, h);
        return dst;
    }

    /**
     * The output postcondition. This class bypasses the base funnel, so it owns the funnel's
     * promise that returning normally implies a finite result — and it owns it for {@code z} too,
     * which the two-ordinate funnel never checked.
     */
    private void checkFinite(ProjCoordinate p, String direction, double a, double b, double c) {
        if (!isFinite(p.x) || !isFinite(p.y) || !isFinite(p.z)) {
            throw new ProjectionException(ErrorCause.NUMERICAL_FAILURE, this,
                    "geocentric " + direction + " conversion of (" + a + ", " + b + ", " + c
                            + ") returned a non-finite coordinate (" + p.x + ", " + p.y + ", "
                            + p.z + ")");
        }
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }
}

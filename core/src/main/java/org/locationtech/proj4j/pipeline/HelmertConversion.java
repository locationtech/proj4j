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
package org.locationtech.proj4j.pipeline;

/**
 * The 3- or 7-parameter Helmert transformation PROJ builds behind a
 * {@code +towgs84}, i.e. {@code +proj=helmert +exact <params> +convention=position_vector}
 * ({@code 9.8.1:src/create.cpp:145-156}, {@code src/transformations/helmert.cpp}).
 *
 * <h2>Two things here are easy to get wrong</h2>
 *
 * <p><b>{@code +exact}.</b> The cs2cs-emulation helper is built with {@code exact},
 * so the rotation matrix is the full trigonometric one, <em>not</em> the linearised
 * small-angle approximation that {@code datum.Datum.transformFromGeocentricToWgs84}
 * uses. For the rotation magnitudes in the EPSG init file the two differ by well
 * under a millimetre, so this is not why GIGS passes or fails — but it is a real
 * difference, and reproducing upstream costs three cosines.
 *
 * <p><b>{@code position_vector}.</b> PROJ derives the matrix in the
 * <em>coordinate frame</em> convention and then transposes it, and hard-errors if a
 * {@code +towgs84} is combined with anything else
 * ({@code helmert.cpp:540-549}: "towgs84 should only be used with
 * convention=position_vector"). Getting the convention backwards flips the sign of
 * every rotation, which is invisible at the equator and on the prime meridian —
 * precisely the region test fixtures tend to live in.
 *
 * <h2>Parameter representation</h2>
 *
 * <p>The array handed in is PROJ's {@code P->datum_params} <em>after</em>
 * {@code pj_datum_set} has normalised it: translations in metres, rotations in
 * <b>radians</b>, and element 6 holding {@code 1 + s/1e6} rather than {@code s} in
 * ppm. That is also exactly what proj4j's {@code datum.Datum} stores, so a
 * {@code +datum=} lookup and a {@code +towgs84=} parse can share one path.
 *
 * <p>Immutable and thread-safe.
 */
final class HelmertConversion {

    private final double tx;
    private final double ty;
    private final double tz;
    private final double scale;
    private final boolean pureTranslation;
    private final double r00;
    private final double r01;
    private final double r02;
    private final double r10;
    private final double r11;
    private final double r12;
    private final double r20;
    private final double r21;
    private final double r22;

    /**
     * @param datumParams 3 or 7 elements in {@code pj_datum_set} form: {@code dx, dy, dz}
     *                    in metres, then {@code rx, ry, rz} in radians and
     *                    {@code 1 + s/1e6}
     */
    HelmertConversion(final double[] datumParams) {
        this.tx = datumParams[0];
        this.ty = datumParams[1];
        this.tz = datumParams[2];

        final double rx = datumParams.length > 3 ? datumParams[3] : 0.0;
        final double ry = datumParams.length > 4 ? datumParams[4] : 0.0;
        final double rz = datumParams.length > 5 ? datumParams[5] : 0.0;

        // helmert.cpp:597-601 undoes pj_datum_set's conversion to absolute scale,
        // then :423 redoes it. Reproduced literally so the rounding matches.
        final double absoluteScale = datumParams.length > 6 ? datumParams[6] : 0.0;
        final double scalePpm = absoluteScale == 0.0 ? 0.0 : (absoluteScale - 1.0) * 1e6;

        final boolean noRotation = rx == 0 && ry == 0 && rz == 0;
        // helmert_forward_3d:388 - "no_rotation && scale == 0" takes the fast path,
        // where scale is the ppm value, not the multiplier.
        this.pureTranslation = noRotation && scalePpm == 0.0;
        this.scale = 1.0 + scalePpm * 1e-6;

        // build_rot_matrix, exact branch, in the coordinate-frame convention.
        final double cf = Math.cos(rx);
        final double sf = Math.sin(rx);
        final double ct = Math.cos(ry);
        final double st = Math.sin(ry);
        final double cp = Math.cos(rz);
        final double sp = Math.sin(rz);

        final double m00 = ct * cp;
        final double m01 = cf * sp + sf * st * cp;
        final double m02 = sf * sp - cf * st * cp;
        final double m10 = -ct * sp;
        final double m11 = cf * cp - sf * st * sp;
        final double m12 = sf * cp + cf * st * sp;
        final double m20 = st;
        final double m21 = -sf * ct;
        final double m22 = cf * ct;

        // ...then transposed, which is what convention=position_vector means.
        this.r00 = m00;
        this.r01 = m10;
        this.r02 = m20;
        this.r10 = m01;
        this.r11 = m11;
        this.r12 = m21;
        this.r20 = m02;
        this.r21 = m12;
        this.r22 = m22;
    }

    /**
     * {@code helmert_forward_3d} ({@code helmert.cpp:370-416}): local frame to WGS84.
     *
     * @param coord {@code {X, Y, Z, t}} in metres, mutated in place
     */
    void forward(final double[] coord) {
        final double x = coord[0];
        final double y = coord[1];
        final double z = coord[2];
        if (pureTranslation) {
            coord[0] = x + tx;
            coord[1] = y + ty;
            coord[2] = z + tz;
            return;
        }
        coord[0] = scale * (r00 * x + r01 * y + r02 * z) + tx;
        coord[1] = scale * (r10 * x + r11 * y + r12 * z) + ty;
        coord[2] = scale * (r20 * x + r21 * y + r22 * z) + tz;
    }

    /**
     * {@code helmert_reverse_3d} ({@code helmert.cpp:419-436}): WGS84 to local frame.
     *
     * <p>Note this is <em>not</em> the algebraic inverse of {@link #forward} in
     * general — upstream unscales and de-offsets, then rotates by the transpose,
     * which for a non-orthogonal linearised matrix would differ. With
     * {@code +exact} the matrix is orthogonal and the two agree; the ordering is
     * kept as upstream writes it regardless.
     *
     * @param coord {@code {X, Y, Z, t}} in metres, mutated in place
     */
    void inverse(final double[] coord) {
        final double x = coord[0];
        final double y = coord[1];
        final double z = coord[2];
        if (pureTranslation) {
            coord[0] = x - tx;
            coord[1] = y - ty;
            coord[2] = z - tz;
            return;
        }
        final double dx = (x - tx) / scale;
        final double dy = (y - ty) / scale;
        final double dz = (z - tz) / scale;
        coord[0] = r00 * dx + r10 * dy + r20 * dz;
        coord[1] = r01 * dx + r11 * dy + r21 * dz;
        coord[2] = r02 * dx + r12 * dy + r22 * dz;
    }
}

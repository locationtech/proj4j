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
 * PROJ's {@code +proj=cart} ({@code 9.8.1:src/conversions/cart.cpp}): geodetic
 * {@code (lam, phi, z)} in radians and metres to geocentric cartesian
 * {@code (X, Y, Z)} in metres, and back.
 *
 * <h2>Why a fresh implementation rather than {@code datum.GeocentricConverter}</h2>
 *
 * <p>{@code GeocentricConverter} is the 1996 Toms iterative algorithm ported from
 * PROJ.4, converging on {@code sin(phi)} to {@code 1e-12} — about 6 µm of latitude.
 * PROJ 9.8.1 uses Bowring's closed form instead. Six micrometres is comfortably
 * inside the GIGS point tolerances, but the pipeline uses this conversion up to
 * <b>four times per coordinate</b> (WGS84 forward, local inverse, and the mirror
 * pair on the way back) inside a {@code roundtrip 1000} block whose budget is
 * 6 µm <em>total</em>. An iteration limit is also a fail-open site: it returns its
 * last estimate rather than reporting non-convergence. Bowring is closed-form, so
 * neither concern arises, and matching upstream removes a whole class of
 * "which algorithm produced this residual" question.
 *
 * <p>Two details are transcribed rather than simplified, because they are what
 * make the conversion well conditioned:
 * <ul>
 * <li>the normalisation by {@code 1/a} before forming the auxiliary angle, so that
 *     {@code norm} and {@code norm_phi} are computed on quantities of order 1;</li>
 * <li>the {@code cosphi < 1e-6} branch, which takes the height from the
 *     <em>geocentric</em> radius rather than dividing by a vanishing cosine.</li>
 * </ul>
 *
 * <p>{@code e2s} is derived as {@code tan(asin(e))^2} rather than the algebraically
 * equal {@code es / (1 - es)} because that is what
 * {@code pj_calc_ellipsoid_params} ({@code ell_set.cpp:586-588}) does, and the two
 * differ in the last bit.
 *
 * <p>Immutable and thread-safe.
 */
final class CartConversion {

    private static final double HALF_PI = Math.PI / 2.0;

    private final double a;
    private final double es;
    private final double ra;
    private final double f;
    private final double bDivA;
    private final double e2s;

    /**
     * @param a  semi-major axis in metres
     * @param es first eccentricity squared
     */
    CartConversion(final double a, final double es) {
        this.a = a;
        this.es = es;
        this.ra = 1.0 / a;
        final double e = Math.sqrt(es);
        final double alpha = Math.asin(e);
        final double e2 = Math.tan(alpha);
        this.e2s = e2 * e2;
        this.f = 1.0 - Math.cos(alpha);
        this.bDivA = 1.0 - f;
    }

    /** @return the semi-major axis this conversion was built with. */
    double a() {
        return a;
    }

    /** @return the first eccentricity squared this conversion was built with. */
    double es() {
        return es;
    }

    /** {@code normal_radius_of_curvature} ({@code cart.cpp:105-111}). */
    private double normalRadiusOfCurvature(final double sinphi) {
        if (es == 0) {
            return a;
        }
        return a / Math.sqrt(1 - es * sinphi * sinphi);
    }

    /** {@code geocentric_radius} ({@code cart.cpp:114-136}), the {@code hypot}-free optimised form. */
    private double geocentricRadius(final double cosphi, final double sinphi) {
        final double cosphi2 = cosphi * cosphi;
        final double sinphi2 = sinphi * sinphi;
        final double bda2 = bDivA * bDivA;
        final double bda2SinPhi2 = bda2 * sinphi2;
        return a * Math.sqrt((cosphi2 + bda2 * bda2SinPhi2) / (cosphi2 + bda2SinPhi2));
    }

    /**
     * {@code cartesian()} ({@code cart.cpp:139-153}). Geodetic to geocentric.
     *
     * @param coord {@code {lam, phi, z, t}} in place; becomes {@code {X, Y, Z, t}}
     */
    void forward(final double[] coord) {
        final double lam = coord[0];
        final double phi = coord[1];
        final double z = coord[2];
        final double cosphi = Math.cos(phi);
        final double sinphi = Math.sin(phi);
        final double n = normalRadiusOfCurvature(sinphi);
        coord[0] = (n + z) * cosphi * Math.cos(lam);
        coord[1] = (n + z) * cosphi * Math.sin(lam);
        coord[2] = (n * (1 - es) + z) * sinphi;
    }

    /**
     * {@code geodetic()} ({@code cart.cpp:156-230}). Geocentric to geodetic.
     *
     * @param coord {@code {X, Y, Z, t}} in place; becomes {@code {lam, phi, z, t}}
     */
    void inverse(final double[] coord) {
        final double x = coord[0];
        final double y = coord[1];
        final double z = coord[2];

        final double xDivA = x * ra;
        final double yDivA = y * ra;
        final double zDivA = z * ra;
        final double pDivA = Math.sqrt(xDivA * xDivA + yDivA * yDivA);

        final double pDivABDivA = pDivA * bDivA;
        final double norm = Math.sqrt(zDivA * zDivA + pDivABDivA * pDivABDivA);
        double c;
        double s;
        if (norm != 0) {
            final double invNorm = 1.0 / norm;
            c = pDivABDivA * invNorm;
            s = zDivA * invNorm;
        } else {
            c = 1;
            s = 0;
        }

        final double yPhi = zDivA + e2s * bDivA * s * s * s;
        final double xPhi = pDivA - es * c * c * c;
        final double normPhi = Math.sqrt(yPhi * yPhi + xPhi * xPhi);
        double cosphi;
        double sinphi;
        if (normPhi != 0) {
            final double invNormPhi = 1.0 / normPhi;
            cosphi = xPhi * invNormPhi;
            sinphi = yPhi * invNormPhi;
        } else {
            cosphi = 1;
            sinphi = 0;
        }

        final double phi;
        if (xPhi <= 0) {
            // Very close to the geocentre there is no single solution; PROJ clamps
            // to a pole rather than leaving a discontinuity.
            phi = z >= 0 ? HALF_PI : -HALF_PI;
            cosphi = 0;
            sinphi = z >= 0 ? 1 : -1;
        } else {
            phi = Math.atan(yPhi / xPhi);
        }

        final double lam = Math.atan2(yDivA, xDivA);

        final double height;
        if (cosphi < 1e-6) {
            // Poleward of 89.99994 degrees: take the height from the geocentric
            // radius instead of dividing by a vanishing cosine.
            height = Math.abs(z) - geocentricRadius(cosphi, sinphi);
        } else {
            height = a * pDivA / cosphi - normalRadiusOfCurvature(sinphi);
        }

        coord[0] = lam;
        coord[1] = phi;
        coord[2] = height;
    }
}

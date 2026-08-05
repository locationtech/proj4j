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

package org.locationtech.proj4j.util;

/**
 * Meridional distance and its inverse, a port of PROJ 9.8.1's {@code src/mlfn.cpp:9-31}
 * ({@code pj_enfn}, {@code pj_mlfn}, {@code pj_inv_mlfn}).
 *
 * <p>Both directions are 6th-order expansions in the <b>third flattening {@code n}</b>,
 * <em>not</em> in {@code es} as PROJ 4 and proj4j's
 * {@code ProjectionMath.enfn/mlfn/inv_mlfn} do. That is the whole point: the pair
 * {@code phi -> mu} and {@code mu -> phi} are both closed-form Clenshaw sums, so
 *
 * <ul>
 * <li>{@link #invMlfn} has <b>no iteration</b>, where proj4j's {@code inv_mlfn} is a
 * 10-step Newton loop with a data-dependent trip count — and therefore a
 * platform-dependent <em>answer</em>, not merely a platform-dependent latency; and
 * <li>the forward direction gains about three decimal digits. Measured on GRS80 over
 * 0-90 degrees, proj4j's {@code mlfn} is up to 4,920 nm from the truth against the
 * 50 nm bar that {@code tmerc}'s conformance assertions set; this series is under 1 nm.
 * </ul>
 *
 * <p>Because the two directions and the multiplier {@code en[0]} come from one series
 * family, they must be ported and used as a set — mixing an {@code n}-series forward
 * with an {@code es}-series inverse is incoherent.
 *
 * <p>Instances are immutable and thread safe. Construct one per ellipsoid and reuse it;
 * construction costs one {@code sqrt} and two coefficient evaluations.
 *
 * <p><b>One deliberate deviation from upstream.</b> {@link #invMlfn} multiplies by a
 * precomputed reciprocal of the rectifying radius where PROJ divides
 * ({@code mu / en[0]}). The difference is at most 1 ulp on the rectifying latitude,
 * i.e. 3e-16 rad, about 2 pm on the ground — eight orders of magnitude inside the
 * tightest conformance bar — and it takes a division out of the per-vertex path.
 *
 * <p><b>Math vs StrictMath:</b> construction is {@code sqrt} and exact arithmetic only.
 * The evaluation methods that take {@code sin} and {@code cos} as arguments use no
 * transcendentals at all; the convenience overloads that do not
 * ({@link #mlfn(double)}, {@link #invMlfn(double)}) go through {@link Clenshaw6}, which
 * uses {@code StrictMath.sin/cos} for cross-architecture determinism.
 *
 * @see AuxLat
 * @see Clenshaw6
 */
public final strictfp class MeridianArc implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    private final double n;
    private final double rectifyingRadius;
    private final double invRectifyingRadius;
    private final Clenshaw6 phiToMu;
    private final Clenshaw6 muToPhi;

    /**
     * Builds the series for a given third flattening.
     *
     * <p>Equivalent to {@code pj_enfn(n)}: {@code en[0]} becomes
     * {@link #rectifyingRadius()}, {@code en[1..6]} the {@code phi -> mu} coefficients
     * and {@code en[7..12]} the {@code mu -> phi} coefficients.
     *
     * @param n the third flattening, {@code (a - b) / (a + b)}; full double precision
     *          requires {@code |f| <= 1/150}
     */
    public MeridianArc(double n) {
        this.n = n;
        this.rectifyingRadius = AuxLat.rectifyingRadius(n);
        this.invRectifyingRadius = 1.0 / this.rectifyingRadius;
        this.phiToMu = Clenshaw6.forConversion(n, AuxLat.GEOGRAPHIC, AuxLat.RECTIFYING);
        this.muToPhi = Clenshaw6.forConversion(n, AuxLat.RECTIFYING, AuxLat.GEOGRAPHIC);
    }

    /**
     * Builds the series from the squared first eccentricity, deriving {@code n} through
     * {@link AuxLat#thirdFlattening}.
     *
     * @param es the squared first eccentricity
     * @return the series for that ellipsoid
     */
    public static MeridianArc fromEs(double es) {
        return new MeridianArc(AuxLat.thirdFlattening(es));
    }

    /**
     * The third flattening this instance was built for.
     *
     * @return {@code n}
     */
    public double thirdFlattening() {
        return n;
    }

    /**
     * The rectifying radius, {@code (quarter meridian) / ((pi/2) * a)} — upstream's
     * {@code en[0]}.
     *
     * @return the rectifying radius, in units of the semi-major axis
     */
    public double rectifyingRadius() {
        return rectifyingRadius;
    }

    /**
     * Meridional distance from the equator, in units of the semi-major axis. Port of
     * {@code pj_mlfn}: {@code en[0] * (phi + clenshaw(sin phi, cos phi, en + 1))}.
     *
     * @param phi  the geographic latitude, radians
     * @param sphi {@code sin(phi)}
     * @param cphi {@code cos(phi)}
     * @return the meridional arc length, divided by the semi-major axis
     */
    public double mlfn(double phi, double sphi, double cphi) {
        return rectifyingRadius * phiToMu.convert(phi, sphi, cphi);
    }

    /**
     * Meridional distance from the equator, computing the sine and cosine internally.
     *
     * @param phi the geographic latitude, radians
     * @return the meridional arc length, divided by the semi-major axis
     */
    public double mlfn(double phi) {
        return rectifyingRadius * phiToMu.convert(phi);
    }

    /**
     * The rectifying latitude {@code mu} corresponding to a geographic latitude — the
     * meridional distance rescaled to radians.
     *
     * @param phi  the geographic latitude, radians
     * @param sphi {@code sin(phi)}
     * @param cphi {@code cos(phi)}
     * @return the rectifying latitude, radians
     */
    public double rectifyingLat(double phi, double sphi, double cphi) {
        return phiToMu.convert(phi, sphi, cphi);
    }

    /**
     * Geographic latitude from meridional distance. Port of {@code pj_inv_mlfn}:
     * <b>closed form</b>, {@code clenshaw} applied to {@code mu / en[0]}, with no
     * iteration whatsoever. proj4j's equivalent is a 10-step Newton loop that calls
     * {@code sin}, {@code cos}, {@code mlfn} and a {@code sqrt} per trip.
     *
     * @param mu the meridional arc length, divided by the semi-major axis
     * @return the geographic latitude, radians
     */
    public double invMlfn(double mu) {
        return muToPhi.convert(mu * invRectifyingRadius);
    }

    /**
     * Geographic latitude from rectifying latitude, closed form.
     *
     * @param mu the rectifying latitude, radians
     * @return the geographic latitude, radians
     */
    public double invRectifyingLat(double mu) {
        return muToPhi.convert(mu);
    }

    /**
     * Geographic latitude from rectifying latitude, given the latter's sine and cosine.
     *
     * @param mu  the rectifying latitude, radians
     * @param smu {@code sin(mu)}
     * @param cmu {@code cos(mu)}
     * @return the geographic latitude, radians
     */
    public double invRectifyingLat(double mu, double smu, double cmu) {
        return muToPhi.convert(mu, smu, cmu);
    }

    /**
     * The {@code phi -> mu} evaluator, for callers that need the sine/cosine-preserving
     * form.
     *
     * @return the forward Clenshaw evaluator
     */
    public Clenshaw6 forwardSeries() {
        return phiToMu;
    }

    /**
     * The {@code mu -> phi} evaluator, for callers that need the sine/cosine-preserving
     * form.
     *
     * @return the inverse Clenshaw evaluator
     */
    public Clenshaw6 inverseSeries() {
        return muToPhi;
    }
}

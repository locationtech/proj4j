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

package org.locationtech.proj4j.numerics.wiring;

import org.locationtech.proj4j.util.ProjectionMath;

/**
 * The projection arithmetic <b>as it was before this change</b>, transcribed verbatim from the
 * seven files re-pointed at the Karney numerical core.
 *
 * <p>It exists so that the old-versus-new claims in this package are <em>measurements</em> against
 * the same corpus rows the new code is asserted on, rather than assertions about a state of the tree
 * that no longer exists. Each method names the file and the site it reproduces, and each calls the
 * deprecated {@code ProjectionMath} helper the old code called — those helpers are still present and
 * still public, which is exactly what makes this possible.
 *
 * <p>Nothing here is a re-derivation. Where the old code had a bug (Cassini's {@code C1 -}, Bonne's
 * hard-wired {@code phi1}), the bug is reproduced, because the point is to measure what users got.
 */
final class Legacy {

    private Legacy() {
    }

    private static final double HALFPI = ProjectionMath.HALFPI;

    // ---- merc (MercatorProjection.java:38,48 before the change) -------------------------

    /**
     * The old ellipsoidal Mercator forward northing, in metres:
     * {@code -k0 * log(ProjectionMath.tsfn(phi, sin phi, e))} scaled by {@code a}.
     *
     * <p>{@code k0} was whatever {@code +k_0} said, because {@code +lat_ts} was read into
     * {@link org.locationtech.proj4j.proj.Projection#trueScaleLatitude} and then never used.
     */
    static double mercNorthing(double phiDeg, double a, double es, double k0) {
        double phi = Math.toRadians(phiDeg);
        double e = Math.sqrt(es);
        return -k0 * a * Math.log(ProjectionMath.tsfn(phi, Math.sin(phi), e));
    }

    /** The old ellipsoidal Mercator forward easting, in metres. */
    static double mercEasting(double lamDeg, double a, double k0) {
        return k0 * a * Math.toRadians(lamDeg);
    }

    /**
     * The old ellipsoidal Mercator inverse latitude, in degrees:
     * {@code ProjectionMath.phi2(exp(-y / k0), e)} on the northing normalised by {@code a}.
     */
    static double mercLatitude(double yMetres, double a, double es, double k0) {
        double e = Math.sqrt(es);
        return Math.toDegrees(ProjectionMath.phi2(Math.exp(-yMetres / (k0 * a)), e));
    }

    // ---- lcc (LambertConformalConicProjection.java:63,85,123,127,131) -------------------

    /**
     * The old {@code lcc}, as a whole: {@code initialize()} plus {@code project()} plus
     * {@code projectInverse()}, every {@code tsfn} and {@code phi2} being the deprecated one.
     *
     * <p>The cone constant has to be recomputed with the old {@code tsfn} too — {@code n} comes
     * from {@code log(ml1/ml2)} of two of its values — or the comparison would mix a new
     * initialisation with an old inverse and measure neither.
     */
    static final class Lcc {
        private final double n;
        private final double c;
        private final double rho0;
        private final double e;
        private final double es;
        private final double a;
        private final double k0;

        /**
         * @param lat0Deg the latitude of origin <em>after</em> PROJ's defaulting rule
         *                ({@code lcc.cpp:85-90}): {@code lat_0} falls back to {@code lat_1} only
         *                when {@code lat_2} is absent. For
         *                {@code +lat_1=0.5 +lat_2=2} with no {@code +lat_0} it is therefore
         *                <b>0</b>, not 0.5, and getting that wrong moves {@code rho0} and with it
         *                every coordinate.
         */
        Lcc(double a, double es, double lat1Deg, double lat2Deg, double lat0Deg, double k0) {
            this.a = a;
            this.es = es;
            this.e = Math.sqrt(es);
            this.k0 = k0;
            double phi1 = Math.toRadians(lat1Deg);
            double phi2 = Math.toRadians(lat2Deg);
            double phi0 = Math.toRadians(lat0Deg);
            double sinphi = Math.sin(phi1);
            double cosphi = Math.cos(phi1);
            double nn = sinphi;
            boolean secant = Math.abs(phi1 - phi2) >= 1e-10;
            double m1 = ProjectionMath.msfn(sinphi, cosphi, es);
            double ml1 = ProjectionMath.tsfn(phi1, sinphi, e);
            if (secant) {
                sinphi = Math.sin(phi2);
                nn = Math.log(m1 / ProjectionMath.msfn(sinphi, Math.cos(phi2), es));
                nn /= Math.log(ml1 / ProjectionMath.tsfn(phi2, sinphi, e));
            }
            this.n = nn;
            double r0 = m1 * Math.pow(ml1, -nn) / nn;
            this.c = r0;
            this.rho0 = r0 * ((Math.abs(Math.abs(phi0) - HALFPI) < 1e-10) ? 0.
                    : Math.pow(ProjectionMath.tsfn(phi0, Math.sin(phi0), e), nn));
        }

        /** {@code (easting, northing)} in metres for a longitude offset and latitude in degrees. */
        double[] forward(double lamDeg, double phiDeg) {
            double lam = Math.toRadians(lamDeg);
            double phi = Math.toRadians(phiDeg);
            double rho;
            if (Math.abs(Math.abs(phi) - HALFPI) < 1e-10) {
                rho = 0.0;
            } else {
                rho = c * Math.pow(ProjectionMath.tsfn(phi, Math.sin(phi), e), n);
            }
            lam *= n;
            return new double[] {a * k0 * (rho * Math.sin(lam)),
                    a * k0 * (rho0 - rho * Math.cos(lam))};
        }

        /** {@code (longitude offset, latitude)} in degrees for an easting and northing in metres. */
        double[] inverse(double xMetres, double yMetres) {
            double x = xMetres / a / k0;
            double y = rho0 - yMetres / a / k0;
            double rho = ProjectionMath.distance(x, y);
            if (rho == 0.0) {
                return new double[] {0.0, n > 0.0 ? 90.0 : -90.0};
            }
            if (n < 0.0) {
                rho = -rho;
                x = -x;
                y = -y;
            }
            double phi = ProjectionMath.phi2(Math.pow(rho / c, 1.0 / n), e);
            return new double[] {Math.toDegrees(Math.atan2(x, y) / n), Math.toDegrees(phi)};
        }
    }

    // ---- cass (CassiniProjection.java:62,84,103,105) ------------------------------------

    private static final double C1 = .16666666666666666666;
    private static final double C2 = .00833333333333333333;
    private static final double C3 = .04166666666666666666;
    private static final double C4 = .33333333333333333333;
    private static final double C5 = .06666666666666666666;

    /**
     * The old ellipsoidal Cassini: {@code enfn}/{@code mlfn}/{@code inv_mlfn}, the {@code C1 -}
     * easting sign, and no {@code pj_generic_inverse_2d} refinement.
     */
    static final class Cass {
        private final double[] en;
        private final double m0;
        private final double es;
        private final double a;

        Cass(double a, double es, double lat0Deg) {
            this.a = a;
            this.es = es;
            this.en = ProjectionMath.enfn(es);
            double phi0 = Math.toRadians(lat0Deg);
            this.m0 = ProjectionMath.mlfn(phi0, Math.sin(phi0), Math.cos(phi0), en);
        }

        double[] forward(double lamDeg, double phiDeg) {
            double lplam = Math.toRadians(lamDeg);
            double lpphi = Math.toRadians(phiDeg);
            double n = Math.sin(lpphi);
            double c = Math.cos(lpphi);
            double y = ProjectionMath.mlfn(lpphi, n, c, en);
            n = 1. / Math.sqrt(1. - es * n * n);
            double tn = Math.tan(lpphi);
            double t = tn * tn;
            double a1 = lplam * c;
            c *= es * c / (1 - es);
            double a2 = a1 * a1;
            // The sign upstream fixed in 78d89828.
            double x = n * a1 * (1. - a2 * t * (C1 - (8. - t + 8. * c) * a2 * C2));
            y -= m0 - n * tn * a2 * (.5 + (5. - t + 6. * c) * a2 * C3);
            return new double[] {a * x, a * y};
        }

        double[] inverse(double xMetres, double yMetres) {
            double xyx = xMetres / a;
            double xyy = yMetres / a;
            double ph1 = ProjectionMath.inv_mlfn(m0 + xyy, es, en);
            double tn = Math.tan(ph1);
            double t = tn * tn;
            double n = Math.sin(ph1);
            double r = 1. / (1. - es * n * n);
            n = Math.sqrt(r);
            r *= (1. - es) * n;
            double dd = xyx / n;
            double d2 = dd * dd;
            double phi = ph1 - (n * tn / r) * d2 * (.5 - (1. + 3. * t) * d2 * C3);
            double lam = dd * (1. + t * d2 * (-C4 + (1. + 3. * t) * d2 * C5)) / Math.cos(ph1);
            return new double[] {Math.toDegrees(lam), Math.toDegrees(phi)};
        }
    }

    // ---- bonne (BonneProjection.java:46,69,102,103) -------------------------------------

    /**
     * The old ellipsoidal Bonne. {@code phi1} was hard-wired to {@code pi/2} — the line reading
     * {@code +lat_1} was commented out — so this takes no {@code lat_1} argument, which is the
     * whole point: whatever the user asked for, this is what they got.
     */
    static final class Bonne {
        private final double[] en;
        private final double m1;
        private final double am1;
        private final double es;
        private final double a;

        Bonne(double a, double es) {
            this.a = a;
            this.es = es;
            this.en = ProjectionMath.enfn(es);
            double phi1 = HALFPI;
            double s = Math.sin(phi1);
            double c = Math.cos(phi1);
            this.m1 = ProjectionMath.mlfn(phi1, s, c, en);
            this.am1 = c / (Math.sqrt(1. - es * s * s) * s);
        }

        double[] forward(double lamDeg, double phiDeg) {
            double lplam = Math.toRadians(lamDeg);
            double lpphi = Math.toRadians(phiDeg);
            double E = Math.sin(lpphi);
            double c = Math.cos(lpphi);
            double rh = am1 + m1 - ProjectionMath.mlfn(lpphi, E, c, en);
            // No |rh| > EPS10 guard, exactly as before: at the pole this is 0/0.
            E = c * lplam / (rh * Math.sqrt(1. - es * E * E));
            return new double[] {a * rh * Math.sin(E), a * (am1 - rh * Math.cos(E))};
        }
    }

    // ---- aeqd (EquidistantAzimuthalProjection.java:74,77,80,141,222) --------------------

    /** The old ellipsoidal azimuthal-equidistant polar aspects. */
    static final class AeqdPolar {
        private final double[] en;
        private final double mp;
        private final boolean north;
        private final double es;
        private final double a;

        AeqdPolar(double a, double es, boolean north) {
            this.a = a;
            this.es = es;
            this.north = north;
            this.en = ProjectionMath.enfn(es);
            this.mp = north
                    ? ProjectionMath.mlfn(HALFPI, 1., 0., en)
                    : ProjectionMath.mlfn(-HALFPI, -1., 0., en);
        }

        double[] forward(double lamDeg, double phiDeg) {
            double lam = Math.toRadians(lamDeg);
            double phi = Math.toRadians(phiDeg);
            double coslam = north ? -Math.cos(lam) : Math.cos(lam);
            double rho = Math.abs(mp
                    - ProjectionMath.mlfn(phi, Math.sin(phi), Math.cos(phi), en));
            return new double[] {a * rho * Math.sin(lam), a * rho * coslam};
        }

        double latitude(double xMetres, double yMetres) {
            double c = ProjectionMath.distance(xMetres / a, yMetres / a);
            return Math.toDegrees(ProjectionMath.inv_mlfn(north ? mp - c : mp + c, es, en));
        }
    }
}

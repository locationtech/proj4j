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

import static org.junit.Assert.assertTrue;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.gie.GieComparator;

/**
 * Evaluates one {@code accept}/{@code expect} pair from the vendored conformance corpus against
 * proj4j, using <b>gie's own metric</b>.
 *
 * <p>Every expectation in this package is read out of
 * {@code conformance/src/test/resources/gie/builtins.gie} or
 * {@code conformance/src/test/resources/gigs/{5108,5112}.gie} — byte-identical copies of
 * {@code 9.8.1:test/gie/builtins.gie} and {@code 9.8.1:test/gigs/*.gie} — and every test method
 * names the line it came from. A value that traces to upstream is evidence; a value that traces to
 * a previous proj4j run is not.
 *
 * <p><b>The metric matters</b> ({@code 9.8.1:src/apps/gie.cpp:1120-1165} picks among four):
 * a forward row's output is projected metres, so the deviation is Euclidean
 * ({@link GieComparator#xyDist}); an inverse row's output is degrees, so it is the geodesic
 * distance on the ellipsoid under test ({@link GieComparator#lpDist} on radians). Applying the
 * angular scale to a projected target would inflate the number by about 111,319.
 *
 * <p>This is a near-duplicate of {@code org.locationtech.proj4j.numerics.GieRow}, which is
 * package-private in its own package and therefore not reachable from here.
 */
final class GieCase {

    /** One nanometre in metres — the {@code tolerance 50 nm} rows. */
    static final double NM = 1.0e-9;

    /** One millimetre in metres — the common {@code tolerance 0.1 mm} rows. */
    static final double MM = 1.0e-3;

    /** GRS80, as PROJ derives it from {@code a} and {@code rf}. */
    static final double GRS80_A = 6378137.0;
    static final double GRS80_RF = 298.257222101;
    static final double GRS80_ES = 0.006694380022900787;
    static final double GRS80_E = Math.sqrt(GRS80_ES);

    private static final CRSFactory CRS = new CRSFactory();
    private static final CoordinateTransformFactory TRANSFORMS = new CoordinateTransformFactory();

    private final CoordinateReferenceSystem geographic;
    private final CoordinateReferenceSystem projected;
    private final GieComparator comparator;
    private final String operation;

    /**
     * @param operation the {@code operation} line's proj-string, minus the keyword
     * @param ellipsoid the same ellipsoid or radius parameters, for the paired
     *                  {@code +proj=longlat} side and for the geodesic metric
     * @param a         the semi-major axis the metric should use
     * @param f         the flattening the metric should use; {@code 0} for a sphere
     */
    GieCase(String operation, String ellipsoid, double a, double f) {
        this.operation = operation;
        this.geographic = CRS.createFromParameters("gie-geog",
                "+proj=longlat " + ellipsoid + " +no_defs");
        this.projected = CRS.createFromParameters("gie-proj", operation + " +no_defs");
        this.comparator = GieComparator.forEllipsoid(a, f);
    }

    /** A row on {@code +ellps=GRS80}. */
    static GieCase grs80(String operation) {
        return new GieCase(operation, "+ellps=GRS80", GRS80_A, 1.0 / GRS80_RF);
    }

    /** A row on a sphere, {@code +R=<radius>}. */
    static GieCase sphere(String operation, double radius) {
        return new GieCase(operation, "+R=" + radius, radius, 0.0);
    }

    /** A row on an arbitrary named ellipsoid, e.g. {@code +ellps=krass}. */
    static GieCase ellipsoid(String operation, String ellps, double a, double rf) {
        return new GieCase(operation, "+ellps=" + ellps, a, 1.0 / rf);
    }

    /** The forward direction: degrees in, projected units out. */
    ProjCoordinate forward(double lon, double lat) {
        ProjCoordinate out = new ProjCoordinate();
        TRANSFORMS.createTransform(geographic, projected)
                .transform(new ProjCoordinate(lon, lat), out);
        return out;
    }

    /** The inverse direction: projected units in, degrees out. */
    ProjCoordinate inverse(double x, double y) {
        ProjCoordinate out = new ProjCoordinate();
        TRANSFORMS.createTransform(projected, geographic)
                .transform(new ProjCoordinate(x, y), out);
        return out;
    }

    /** The Euclidean deviation of a forward result, matching gie's {@code proj_xy_dist} branch. */
    double forwardDeviation(double lon, double lat, double x, double y) {
        ProjCoordinate got = forward(lon, lat);
        return GieComparator.xyDist(new double[] {x, y, 0, 0},
                new double[] {got.x, got.y, 0, 0});
    }

    /** The geodesic deviation of an inverse result, matching gie's {@code proj_lp_dist} branch. */
    double inverseDeviation(double x, double y, double lon, double lat) {
        return angularDeviation(lon, lat, inverse(x, y));
    }

    /** Asserts a forward {@code accept}/{@code expect} pair. */
    void expectForward(double lon, double lat, double x, double y, double toleranceMetres) {
        ProjCoordinate got = forward(lon, lat);
        double d = GieComparator.xyDist(new double[] {x, y, 0, 0},
                new double[] {got.x, got.y, 0, 0});
        assertTrue(describe("forward", lon, lat, x, y, got, d, toleranceMetres),
                GieComparator.withinTolerance(d, toleranceMetres));
    }

    /** Asserts an inverse {@code accept}/{@code expect} pair. */
    void expectInverse(double x, double y, double lon, double lat, double toleranceMetres) {
        ProjCoordinate got = inverse(x, y);
        double d = angularDeviation(lon, lat, got);
        assertTrue(describe("inverse", x, y, lon, lat, got, d, toleranceMetres),
                GieComparator.withinTolerance(d, toleranceMetres));
    }

    /** The geodesic deviation, in metres, between an expected lon/lat in degrees and a result. */
    double angularDeviation(double lon, double lat, ProjCoordinate got) {
        return comparator.lpDist(
                new double[] {Math.toRadians(lon), Math.toRadians(lat), 0, 0},
                new double[] {Math.toRadians(got.x), Math.toRadians(got.y), 0, 0});
    }

    /** gie's {@code roundtrip n} residual, in metres: n forward/inverse cycles from the input. */
    double roundtripDeviation(double lon, double lat, int trips) {
        double curLon = lon;
        double curLat = lat;
        for (int i = 0; i < trips; i++) {
            ProjCoordinate xy = forward(curLon, curLat);
            ProjCoordinate lp = inverse(xy.x, xy.y);
            curLon = lp.x;
            curLat = lp.y;
        }
        return angularDeviation(lon, lat, new ProjCoordinate(curLon, curLat));
    }

    /** gie's {@code roundtrip n}, asserted. */
    void expectRoundtrip(double lon, double lat, int trips, double toleranceMetres) {
        double d = roundtripDeviation(lon, lat, trips);
        assertTrue(operation + " roundtrip " + trips + " from (" + lon + ", " + lat
                        + ") deviates " + d + " m against a tolerance of " + toleranceMetres + " m",
                GieComparator.withinTolerance(d, toleranceMetres));
    }

    /**
     * Asserts that the new code path is inside {@code toleranceMetres} of the corpus value
     * <em>and</em> strictly closer to it than the code path it replaced, so that the improvement is
     * evidenced rather than described.
     *
     * @param label       what is being measured, for the failure message
     * @param corpusValue the deviation the pre-change path produced against the same row
     * @param nowValue    the deviation the current path produces
     */
    static void assertStrictlyBetter(String label, double corpusValue, double nowValue,
            double toleranceMetres) {
        assertTrue(label + ": the current path deviates " + nowValue + " m, outside the corpus "
                        + "tolerance of " + toleranceMetres + " m",
                GieComparator.withinTolerance(nowValue, toleranceMetres));
        assertTrue(label + ": the current path deviates " + nowValue + " m but the path it "
                        + "replaced deviated " + corpusValue + " m -- no improvement, so there was "
                        + "nothing to fix or the measurement is wrong",
                nowValue < corpusValue);
    }

    private String describe(String direction, double in0, double in1, double e0, double e1,
                            ProjCoordinate got, double d, double tol) {
        return operation + " " + direction + " of (" + in0 + ", " + in1 + "): expected ("
                + e0 + ", " + e1 + ") got (" + got.x + ", " + got.y + "), deviation " + d
                + " m against a tolerance of " + tol + " m";
    }

    // ---- an independent meridian-arc reference ------------------------------------------

    /** 10-point Gauss-Legendre nodes on [-1, 1]. */
    private static final double[] GLX = {
        -0.9739065285171717, -0.8650633666889845, -0.6794095682990244,
        -0.4333953941292472, -0.1488743389816312, 0.1488743389816312,
        0.4333953941292472, 0.6794095682990244, 0.8650633666889845,
        0.9739065285171717,
    };

    /** The matching weights. */
    private static final double[] GLW = {
        0.0666713443086881, 0.1494513491505806, 0.2190863625159820,
        0.2692667193099963, 0.2955242247147529, 0.2955242247147529,
        0.2692667193099963, 0.2190863625159820, 0.1494513491505806,
        0.0666713443086881,
    };

    /**
     * The meridian arc from the equator to {@code phi}, divided by the semi-major axis:
     * {@code integral(0, phi) (1 - es) / (1 - es sin^2 t)^(3/2) dt}, by 64-panel 10-point
     * Gauss-Legendre with Neumaier compensated summation.
     *
     * <p><b>Why this and not a round trip.</b> {@code ProjectionMath.inv_mlfn} is Newton's method
     * run against {@code ProjectionMath.mlfn} itself, so the pair is self-consistent <em>by
     * construction</em>: on GRS80 its round trip closes to 0.7 nm while both halves sit 4,920 nm
     * from the truth. Any old-versus-new claim about the meridian arc has to be made against
     * something that shares no series, no coefficient and no algorithm with either
     * implementation. This quadrature is good to about 1 nm on the ground, which separates 1 nm
     * from 4,920 nm but is not itself evidence for "under 1 nm".
     *
     * <p>It is duplicated from {@code org.locationtech.proj4j.util.NumericAssert}, which is
     * package-private in that package.
     */
    static double meridianArcReference(double phi, double es) {
        final int panels = 64;
        final double h = phi / panels;
        final double hw = 0.5 * h;
        double sum = 0.0;
        double comp = 0.0;
        for (int p = 0; p < panels; p++) {
            final double c = p * h + hw;
            for (int i = 0; i < GLX.length; i++) {
                final double t = c + hw * GLX[i];
                final double st = Math.sin(t);
                final double d = 1.0 - es * st * st;
                final double term = hw * GLW[i] * (1.0 - es) / (d * Math.sqrt(d));
                final double s = sum + term;
                if (Math.abs(sum) >= Math.abs(term)) {
                    comp += (sum - s) + term;
                } else {
                    comp += (term - s) + sum;
                }
                sum = s;
            }
        }
        return sum + comp;
    }
}

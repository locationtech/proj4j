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

package org.locationtech.proj4j.numerics;

import static org.junit.Assert.assertTrue;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.gie.GieComparator;

/**
 * Evaluates a single {@code accept}/{@code expect} pair out of
 * {@code conformance/src/test/resources/gie/builtins.gie} — the byte-identical vendored copy
 * of {@code 9.8.1:test/gie/builtins.gie} — against proj4j, using <b>gie's own metric</b>.
 *
 * <p>The tests in this package are unit tests of the numerical core's <em>wiring</em>: each
 * one names the {@code builtins.gie} line it reproduces so that a value can be traced back to
 * upstream rather than to a previous proj4j run. They are deliberately independent of the
 * {@code conformance} module, which is a separate Maven module behind a profile.
 *
 * <p><b>The metric matters.</b> gie chooses among four
 * ({@code 9.8.1:src/apps/gie.cpp:1120-1165}); applying an angular scale to a projected target
 * inflates the deviation by about 111,319. Here:
 *
 * <ul>
 * <li>a <b>forward</b> row's output is projected metres, so the deviation is
 *     {@code GieComparator.xyDist} — plain Euclidean;
 * <li>an <b>inverse</b> row's output is degrees, so the deviation is
 *     {@code GieComparator.lpDist} on the radian values — geodesic distance on the very
 *     ellipsoid under test.
 * </ul>
 *
 * <p>gie's pass predicate is reused verbatim through
 * {@link GieComparator#withinTolerance(double, double)}, which is written {@code !(d <= tol)}
 * so that a NaN deviation <em>fails</em>.
 */
final class GieRow {

    /** One nanometre in metres — {@code tolerance 50 nm} rows. */
    static final double NM = 1.0e-9;

    /** One millimetre in metres — the common {@code tolerance 0.1 mm} rows. */
    static final double MM = 1.0e-3;

    private static final CRSFactory CRS = new CRSFactory();
    private static final CoordinateTransformFactory TRANSFORMS = new CoordinateTransformFactory();

    private final CoordinateReferenceSystem geographic;
    private final CoordinateReferenceSystem projected;
    private final GieComparator comparator;
    private final String operation;

    /**
     * @param operation the {@code operation} line's proj-string, minus the leading
     *                  {@code operation} keyword
     * @param ellipsoid the same ellipsoid or radius parameters, for the paired
     *                  {@code +proj=longlat} side and for the geodesic metric
     * @param a         the semi-major axis the metric should use
     * @param f         the flattening the metric should use; {@code 0} for a sphere
     */
    GieRow(String operation, String ellipsoid, double a, double f) {
        this.operation = operation;
        this.geographic = CRS.createFromParameters("gie-geog", "+proj=longlat " + ellipsoid + " +no_defs");
        this.projected = CRS.createFromParameters("gie-proj", operation + " +no_defs");
        this.comparator = GieComparator.forEllipsoid(a, f);
    }

    /** A row on GRS80, {@code +ellps=GRS80}. */
    static GieRow grs80(String operation) {
        return new GieRow(operation, "+ellps=GRS80", 6378137.0, 1.0 / 298.257222101);
    }

    /** A row on a sphere, {@code +R=<radius>}. */
    static GieRow sphere(String operation, double radius) {
        return new GieRow(operation, "+R=" + radius, radius, 0.0);
    }

    /** The forward direction: degrees in, projected units out. */
    ProjCoordinate forward(double lon, double lat) {
        ProjCoordinate out = new ProjCoordinate();
        TRANSFORMS.createTransform(geographic, projected).transform(new ProjCoordinate(lon, lat), out);
        return out;
    }

    /** The inverse direction: projected units in, degrees out. */
    ProjCoordinate inverse(double x, double y) {
        ProjCoordinate out = new ProjCoordinate();
        TRANSFORMS.createTransform(projected, geographic).transform(new ProjCoordinate(x, y), out);
        return out;
    }

    /**
     * Asserts a forward {@code accept}/{@code expect} pair. Deviation is Euclidean in the
     * projected units, matching gie's {@code proj_xy_dist} branch.
     */
    void expectForward(double lon, double lat, double x, double y, double toleranceMetres) {
        ProjCoordinate got = forward(lon, lat);
        double d = GieComparator.xyDist(new double[] {x, y, 0, 0},
                new double[] {got.x, got.y, 0, 0});
        assertTrue(describe("forward", lon, lat, x, y, got, d, toleranceMetres),
                GieComparator.withinTolerance(d, toleranceMetres));
    }

    /**
     * Asserts an inverse {@code accept}/{@code expect} pair. Deviation is the geodesic
     * distance, matching gie's {@code proj_lp_dist} branch for degree output.
     */
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

    /** gie's {@code roundtrip n}, forward then inverse, measured against the original input. */
    void expectRoundtrip(double lon, double lat, int trips, double toleranceMetres) {
        double curLon = lon;
        double curLat = lat;
        for (int i = 0; i < trips; i++) {
            ProjCoordinate xy = forward(curLon, curLat);
            ProjCoordinate lp = inverse(xy.x, xy.y);
            curLon = lp.x;
            curLat = lp.y;
        }
        ProjCoordinate got = new ProjCoordinate(curLon, curLat);
        double d = angularDeviation(lon, lat, got);
        assertTrue(operation + " roundtrip " + trips + " from (" + lon + ", " + lat
                        + ") deviates " + d + " m, tolerance " + toleranceMetres + " m; got " + got,
                GieComparator.withinTolerance(d, toleranceMetres));
    }

    private String describe(String direction, double in0, double in1, double e0, double e1,
                            ProjCoordinate got, double d, double tol) {
        return operation + " " + direction + " of (" + in0 + ", " + in1 + "): expected ("
                + e0 + ", " + e1 + ") got (" + got.x + ", " + got.y + "), deviation " + d
                + " m against a tolerance of " + tol + " m";
    }
}

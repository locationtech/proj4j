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
package org.locationtech.proj4j.numerical;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.gie.GieComparator;

/**
 * One {@code accept}/{@code expect} pair from the vendored conformance corpus, evaluated against
 * proj4j with <b>gie's own metric</b>.
 *
 * <p>Every expectation in this package is copied out of
 * {@code conformance/src/test/resources/gie/builtins.gie} — a byte-identical copy of
 * {@code 9.8.1:test/gie/builtins.gie} — and every test method names the projection and the block it
 * came from. A value that traces to upstream is evidence; a value that traces to a previous proj4j
 * run is not.
 *
 * <p><b>The metric branch matters.</b> gie picks among four ({@code gie.cpp}); the two that arise
 * here are Euclidean metres for a forward row, whose expected value is projected, and the geodesic
 * distance on the ellipsoid under test for an inverse row, whose expected value is degrees. Applying
 * the angular scale to a projected target inflates the number by about 111,319.
 *
 * <p>A near-duplicate of {@code numerics.wiring.GieCase}, which is package-private in its own
 * package and so not reachable from here. The duplication is deliberate: making either one public
 * would put a test helper on the library's API surface.
 */
final class GieAssertion {

    /** One millimetre in metres — the common {@code tolerance 0.1 mm} rows. */
    static final double MM = 1.0e-3;

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
    private GieAssertion(String operation, String ellipsoid, double a, double f) {
        this.operation = operation;
        this.geographic = CRS.createFromParameters("gie-geog",
                "+proj=longlat " + ellipsoid + " +no_defs");
        this.projected = CRS.createFromParameters("gie-proj", operation + " +no_defs");
        this.comparator = GieComparator.forEllipsoid(a, f);
    }

    /** A row on {@code +ellps=WGS84}. */
    static GieAssertion wgs84(String operation) {
        return new GieAssertion(operation, "+ellps=WGS84", 6378137.0, 1.0 / 298.257223563);
    }

    /** A row on {@code +ellps=GRS80}. */
    static GieAssertion grs80(String operation) {
        return new GieAssertion(operation, "+ellps=GRS80", 6378137.0, 1.0 / 298.257222101);
    }

    /** A row on a sphere declared with {@code +R=}. */
    static GieAssertion sphere(String operation, double radius) {
        return new GieAssertion(operation, "+R=" + radius, radius, 0.0);
    }

    /**
     * A row on a sphere declared with {@code +a=} alone, which 9.8.1's {@code ellps_shape} also
     * reads as a sphere ("not giving a shape parameter means selecting a sphere").
     */
    static GieAssertion sphereFromA(String operation, double radius) {
        return new GieAssertion(operation, "+a=" + radius, radius, 0.0);
    }

    ProjCoordinate forward(double lon, double lat) {
        ProjCoordinate out = new ProjCoordinate();
        TRANSFORMS.createTransform(geographic, projected)
                .transform(new ProjCoordinate(lon, lat), out);
        return out;
    }

    ProjCoordinate inverse(double x, double y) {
        ProjCoordinate out = new ProjCoordinate();
        TRANSFORMS.createTransform(projected, geographic)
                .transform(new ProjCoordinate(x, y), out);
        return out;
    }

    /** Asserts a forward {@code accept}/{@code expect} pair. */
    void expectForward(double lon, double lat, double x, double y, double toleranceMetres) {
        ProjCoordinate got = forward(lon, lat);
        double d = GieComparator.xyDist(new double[] {x, y, 0, 0},
                new double[] {got.x, got.y, 0, 0});
        assertTrue(operation + ": forward (" + lon + ", " + lat + ") expected (" + x + ", " + y
                        + ") got (" + got.x + ", " + got.y + "), deviation " + d
                        + " m against " + toleranceMetres + " m",
                GieComparator.withinTolerance(d, toleranceMetres));
    }

    /** Asserts an inverse {@code accept}/{@code expect} pair. */
    void expectInverse(double x, double y, double lon, double lat, double toleranceMetres) {
        ProjCoordinate got = inverse(x, y);
        double d = comparator.lpDist(
                new double[] {Math.toRadians(lon), Math.toRadians(lat), 0, 0},
                new double[] {Math.toRadians(got.x), Math.toRadians(got.y), 0, 0});
        assertTrue(operation + ": inverse (" + x + ", " + y + ") expected (" + lon + ", " + lat
                        + ") got (" + got.x + ", " + got.y + "), deviation " + d
                        + " m against " + toleranceMetres + " m",
                GieComparator.withinTolerance(d, toleranceMetres));
    }

    /** Asserts gie's {@code roundtrip n}: n forward/inverse cycles, residual in metres. */
    void expectRoundtrip(double lon, double lat, int trips, double toleranceMetres) {
        double curLon = lon;
        double curLat = lat;
        for (int i = 0; i < trips; i++) {
            ProjCoordinate xy = forward(curLon, curLat);
            ProjCoordinate lp = inverse(xy.x, xy.y);
            curLon = lp.x;
            curLat = lp.y;
        }
        double d = comparator.lpDist(
                new double[] {Math.toRadians(lon), Math.toRadians(lat), 0, 0},
                new double[] {Math.toRadians(curLon), Math.toRadians(curLat), 0, 0});
        assertTrue(operation + ": roundtrip " + trips + " from (" + lon + ", " + lat
                        + ") deviates " + d + " m against " + toleranceMetres + " m",
                GieComparator.withinTolerance(d, toleranceMetres));
    }

    /**
     * Asserts a corpus row of the form
     * {@code expect failure errno coord_transfm_outside_projection_domain} in the forward direction.
     * <p>
     * The assertion is on the {@link ErrorCause}, not merely on "something was thrown": a
     * projection that answered with the input unchanged, the false easting, or a single NaN ordinate
     * would be the specific failure shape this whole class of fix exists to remove.
     */
    void expectForwardRejected(double lon, double lat) {
        try {
            ProjCoordinate got = forward(lon, lat);
            fail(operation + ": forward (" + lon + ", " + lat + ") should have been rejected as "
                    + "outside the projection domain, but returned (" + got.x + ", " + got.y + ")");
        } catch (Proj4jException e) {
            assertTrue(operation + ": forward (" + lon + ", " + lat + ") was rejected with "
                            + e.cause() + " rather than COORDINATE_OUT_OF_DOMAIN: " + e.getMessage(),
                    e.cause() == ErrorCause.COORDINATE_OUT_OF_DOMAIN);
        }
    }

    /** The inverse counterpart of {@link #expectForwardRejected}. */
    void expectInverseRejected(double x, double y) {
        try {
            ProjCoordinate got = inverse(x, y);
            fail(operation + ": inverse (" + x + ", " + y + ") should have been rejected as "
                    + "outside the projection domain, but returned (" + got.x + ", " + got.y + ")");
        } catch (Proj4jException e) {
            assertTrue(operation + ": inverse (" + x + ", " + y + ") was rejected with "
                            + e.cause() + " rather than COORDINATE_OUT_OF_DOMAIN: " + e.getMessage(),
                    e.cause() == ErrorCause.COORDINATE_OUT_OF_DOMAIN);
        }
    }
}

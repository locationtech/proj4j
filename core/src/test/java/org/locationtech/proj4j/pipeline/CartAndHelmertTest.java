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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * The two hidden helper conversions: {@link CartConversion} against
 * {@code 9.8.1:src/conversions/cart.cpp} and {@link HelmertConversion} against
 * {@code src/transformations/helmert.cpp}.
 */
public class CartAndHelmertTest {

    /** WGS84, as {@code create.cpp:131-132} spells it. */
    private static final CartConversion WGS84 = new CartConversion(6378137.0, 0.0066943799901413);

    private static double rad(double degrees) {
        return degrees * Math.PI / 180.0;
    }

    // ------------------------------------------------------------------- cart

    @Test
    public void geodeticToGeocentricAtTheEquatorAndPrimeMeridianIsTheSemiMajorAxis() {
        double[] c = {0, 0, 0, 0};
        WGS84.forward(c);
        assertEquals(6378137.0, c[0], 1e-9);
        assertEquals(0.0, c[1], 1e-9);
        assertEquals(0.0, c[2], 1e-9);
    }

    @Test
    public void geodeticToGeocentricAtTheNorthPoleIsTheSemiMinorAxis() {
        double[] c = {0, Math.PI / 2, 0, 0};
        WGS84.forward(c);
        double b = 6378137.0 * Math.sqrt(1 - 0.0066943799901413);
        assertEquals(0.0, Math.hypot(c[0], c[1]), 1e-6);
        assertEquals(b, c[2], 1e-6);
    }

    /**
     * The values {@code gigs/5201.gie} asserts, which is where this conversion's
     * accuracy actually has to hold: {@code EPSG:4978} to {@code 4326} at 80°N.
     */
    @Test
    public void geocentricToGeodeticMatchesTheGigs5201Row() {
        double[] c = {-962479.5924, 555687.8517, 6260738.6526, 0};
        WGS84.inverse(c);
        assertEquals(150.0, Math.toDegrees(c[0]), 1e-8);
        assertEquals(80.0, Math.toDegrees(c[1]), 1e-8);
        assertEquals(1214.137, c[2], 0.001);
    }

    /**
     * A geodetic/cartesian round trip closes to a micrometre everywhere.
     *
     * <p>Stated in metres rather than radians because that is the quantity the GIGS
     * budget is written in, and because latitude and height are recovered together:
     * both are differences of quantities of order {@code a}, so neither can do better
     * than a few ULP of 6.4e6 m. A micrometre is three orders inside the tightest
     * budget in the corpus (6 mm over a {@code roundtrip 1000}).
     *
     * <p>Closed-form Bowring, so this is a statement about conditioning and not about
     * an iteration limit — which is exactly why {@code datum.GeocentricConverter},
     * whose criterion is {@code 1e-12} on {@code sin(phi)} and which returns its last
     * estimate on non-convergence, is not reused here.
     */
    @Test
    public void cartRoundTripsToAMicrometre() {
        for (double lat = -89; lat <= 89; lat += 7) {
            for (double h : new double[] {-500, 0, 8848}) {
                double[] origin = {rad(37.5), rad(lat), h, 0};
                double[] c = origin.clone();
                WGS84.forward(c);
                WGS84.inverse(c);
                double horizontal = Math.hypot((c[0] - origin[0]) * Math.cos(origin[1]),
                        c[1] - origin[1]) * 6378137.0;
                assertTrue("horizontal residual " + horizontal + " m at lat " + lat,
                        horizontal <= 1e-6);
                assertEquals("height at lat " + lat, origin[2], c[2], 1e-6);
            }
        }
    }

    @Test
    public void nearThePoleTheHeightComesFromTheGeocentricRadius() {
        // cart.cpp:218-227: poleward of 89.99994 degrees the normal-radius formula
        // divides by a vanishing cosine, so upstream switches branch. Exercise it.
        double[] origin = {0, rad(89.999999), 1000, 0};
        double[] c = origin.clone();
        WGS84.forward(c);
        WGS84.inverse(c);
        assertEquals(origin[1], c[1], 1e-9);
        assertEquals(1000.0, c[2], 1e-3);
    }

    @Test
    public void aSphereTakesTheEsEqualsZeroShortcut() {
        CartConversion sphere = new CartConversion(6371000.0, 0.0);
        double[] c = {0, rad(45), 0, 0};
        sphere.forward(c);
        assertEquals("on a sphere every radius is the semi-major axis",
                6371000.0, Math.sqrt(c[0] * c[0] + c[1] * c[1] + c[2] * c[2]), 1e-6);
    }

    // ---------------------------------------------------------------- helmert

    @Test
    public void aThreeParameterShiftIsAPureTranslation() {
        // EPSG:4275's towgs84, as the epsg init dictionary carries it.
        HelmertConversion h = new HelmertConversion(new double[] {-168, -60, 320});
        double[] c = {1000, 2000, 3000, 0};
        h.forward(c);
        assertEquals(832.0, c[0], 0.0);
        assertEquals(1940.0, c[1], 0.0);
        assertEquals(3320.0, c[2], 0.0);
        h.inverse(c);
        assertEquals(1000.0, c[0], 0.0);
        assertEquals(2000.0, c[1], 0.0);
        assertEquals(3000.0, c[2], 0.0);
    }

    @Test
    public void anAllZeroSevenParameterShiftIsIndistinguishableFromAThreeParameterOne() {
        // datum_set.cpp:113-140 only converts slots 3..6 when one of them is
        // non-zero, so an all-zero seven-value towgs84 leaves datum_params[6] at 0 -
        // which is what create.cpp's all-zero test relies on.
        HelmertConversion h = new HelmertConversion(new double[] {0, 0, 0, 0, 0, 0, 0});
        double[] c = {1000, 2000, 3000, 0};
        h.forward(c);
        assertEquals(1000.0, c[0], 0.0);
        assertEquals(2000.0, c[1], 0.0);
        assertEquals(3000.0, c[2], 0.0);
    }

    /**
     * {@code EPSG:4313}'s seven-parameter shift, in the representation
     * {@code pj_datum_set} produces: rotations already in radians, and element 6
     * holding {@code 1 + s/1e6}.
     *
     * <p>The convention is {@code position_vector}, which upstream reaches by
     * deriving the coordinate-frame matrix and transposing it. Getting it backwards
     * flips every rotation's sign, which is invisible on the axes and on the prime
     * meridian and worth about a metre at the edge of a national grid — so the
     * discriminating assertion is that a rotation about Z moves a point on the X axis
     * <em>towards</em> +Y for a positive {@code rz}, i.e. that the matrix is
     * {@code [[1, -rz, ry], [rz, 1, -rx], [-ry, rx, 1]]} to first order.
     */
    @Test
    public void sevenParameterUsesThePositionVectorConvention() {
        double rz = 1.8422 * ProjectionMath.SECONDS_TO_RAD;
        // Rotation only, so the translation does not mask the sign under test.
        HelmertConversion rotation = new HelmertConversion(new double[] {0, 0, 0, 0, 0, rz, 0});
        double[] c = {6378137.0, 0, 0, 0};
        rotation.forward(c);
        assertTrue("position_vector: +rz rotates +X towards +Y", c[1] > 0);
        assertEquals(6378137.0 * rz, c[1], 1e-3);

        // EPSG:4313's full seven parameters, in pj_datum_set's representation.
        HelmertConversion full = new HelmertConversion(new double[] {-106.8686, 52.2978, -103.7239,
                0.3366 * ProjectionMath.SECONDS_TO_RAD,
                -0.457 * ProjectionMath.SECONDS_TO_RAD,
                rz,
                -1.2747 / ProjectionMath.MILLION + 1.0});
        double[] d = {6378137.0, 0, 0, 0};
        full.forward(d);
        assertEquals("rotation then scale then translation",
                6378137.0 * rz + 52.2978, d[1], 1e-2);
        full.inverse(d);
        assertEquals(6378137.0, d[0], 1e-6);
        assertEquals(0.0, d[1], 1e-6);
        assertEquals(0.0, d[2], 1e-6);
    }

    @Test
    public void theExactRotationDiffersFromTheLinearisedOneButOnlyAtSecondOrder() {
        // create.cpp builds the helper with +exact, so the matrix is the full
        // trigonometric one - not the small-angle approximation that
        // datum.Datum.transformFromGeocentricToWgs84 uses. The difference is real
        // but sub-millimetre for EPSG-sized rotations, which is why it is not the
        // reason any GIGS file passes or fails.
        double rx = 5.137 * ProjectionMath.SECONDS_TO_RAD;
        HelmertConversion exact = new HelmertConversion(new double[] {0, 0, 0, rx, 0, 0, 0});
        double[] c = {0, 6378137.0, 0, 0};
        exact.forward(c);
        // Position vector, third row [-ry, rx, 1]: z' = rx * y to first order.
        double linearised = 6378137.0 * rx;
        assertEquals(linearised, c[2], 0.001);
        assertTrue("but not bit-identical", c[2] != linearised);
    }

    @Test
    public void scaleIsAppliedBeforeTheTranslation() {
        // helmert_forward_3d:405-414 - scale * (R * X), then += translation.
        double[] params = {100, 0, 0, 0, 0, 0, 1.0 + 1e-6};
        HelmertConversion h = new HelmertConversion(params);
        double[] c = {1000000, 0, 0, 0};
        h.forward(c);
        assertEquals(1000000 * (1 + 1e-6) + 100, c[0], 1e-9);
    }
}

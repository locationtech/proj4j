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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code +proj=tinshift}'s numerics, against PROJ 9.8.1's own expected values.
 *
 * <h2>Where the numbers come from</h2>
 *
 * <p>Every model here is the vertex and triangle data of a file in
 * {@code 9.8.1:data/tests/}, and every expected coordinate is the {@code expect} row of
 * the corresponding block of {@code 9.8.1:test/gie/tinshift.gie}. Neither is derived
 * from proj4j's output: a reference file re-pinned from the implementation under test
 * agrees with it by construction, forever, and proves nothing.
 *
 * <p>The models are inlined as text rather than vendored as resources so that these
 * assertions need no classpath fixture and no second copy of upstream data with its own
 * licence note — the conformance module already vendors the files themselves and runs
 * {@code tinshift.gie} against them.
 *
 * <p>{@code tinshift_simplified_kkj_etrs.json} is the one that matters most: its
 * expected value is annotated in the corpus as "Verified with
 * https://kartta.paikkatietoikkuna.fi/ with EPSG:2393 to EPSG:3067", so it is a
 * third-party check on PROJ as well as a check on us, and its tolerance is
 * <b>0.1 mm</b>.
 */
public class TriangulationTest {

    /** {@code 9.8.1:data/tests/tinshift_simplified_kkj_etrs.json}, metadata elided. */
    private static final String KKJ_ETRS = "{"
            + "\"file_type\": \"triangulation_file\","
            + "\"format_version\": \"1.0\","
            + "\"input_crs\": \"EPSG:2393\","
            + "\"output_crs\": \"EPSG:3067\","
            + "\"transformed_components\": [ \"horizontal\" ],"
            + "\"vertices_columns\": [ \"source_x\", \"source_y\", \"target_x\", \"target_y\" ],"
            + "\"triangles_columns\": [ \"idx_vertex1\", \"idx_vertex2\", \"idx_vertex3\" ],"
            + "\"vertices\": [ [3244102.707, 6693710.937, 244037.137, 6690900.686],"
            + "                [3205290.722, 6715311.822, 205240.895, 6712492.577],"
            + "                [3218328.492, 6649538.429, 218273.648, 6646745.973] ],"
            + "\"triangles\": [ [0, 1, 2] ]"
            + "}";

    /**
     * {@code 9.8.1:data/tests/tinshift_simplified_n60_n2000.json}, metadata elided.
     *
     * <p>Note that it declares {@code source_z} and {@code target_z} rather than
     * {@code offset_z}, so this fixture also exercises the {@code targetZ - sourceZ}
     * differencing branch of the reader.
     */
    private static final String N60_N2000 = "{"
            + "\"file_type\": \"triangulation_file\","
            + "\"format_version\": \"1.0\","
            + "\"input_crs\": \"EPSG:2393+5717\","
            + "\"output_crs\": \"EPSG:2393+5941\","
            + "\"transformed_components\": [ \"vertical\" ],"
            + "\"vertices_columns\": [ \"source_x\", \"source_y\", \"source_z\", \"target_z\" ],"
            + "\"triangles_columns\": [ \"idx_vertex1\", \"idx_vertex2\", \"idx_vertex3\" ],"
            + "\"vertices\": [ [3188607.0, 6688748.0, 23.123, 23.4133],"
            + "                [3184981.0, 6725255.0, 8.044, 8.34499],"
            + "                [3220912.0, 6699508.0, 1.724, 2.0101] ],"
            + "\"triangles\": [ [0, 1, 2] ]"
            + "}";

    /** {@code 9.8.1:data/tests/tinshift_fallback_nearest_side.json}, verbatim. */
    private static final String NEAREST_SIDE = "{"
            + "\"file_type\": \"triangulation_file\","
            + "\"format_version\": \"1.1\","
            + "\"fallback_strategy\": \"nearest_side\","
            + "\"transformed_components\": [ \"horizontal\" ],"
            + "\"vertices_columns\": [ \"source_x\", \"source_y\", \"target_x\", \"target_y\" ],"
            + "\"triangles_columns\": [ \"idx_vertex1\", \"idx_vertex2\", \"idx_vertex3\" ],"
            + "\"vertices\": [ [0, 0, 0, 0], [1, 0, 2, 0], [1, 1, 2, 2], [0, 1, 0, 2] ],"
            + "\"triangles\": [ [0, 1, 2], [0, 2, 3] ]"
            + "}";

    /** {@code 9.8.1:data/tests/tinshift_fallback_nearest_centroid.json}, verbatim. */
    private static final String NEAREST_CENTROID = "{"
            + "\"file_type\": \"triangulation_file\","
            + "\"format_version\": \"1.1\","
            + "\"fallback_strategy\": \"nearest_centroid\","
            + "\"transformed_components\": [ \"horizontal\" ],"
            + "\"vertices_columns\": [ \"source_x\", \"source_y\", \"target_x\", \"target_y\" ],"
            + "\"triangles_columns\": [ \"idx_vertex1\", \"idx_vertex2\", \"idx_vertex3\" ],"
            + "\"vertices\": [ [0, 0, 0, 0], [1, 0, 1, 0], [1, 1, 1, 1],"
            + "                [4, 0, 100, 0], [100, 0, 100, 1], [100, 1, 4, 0] ],"
            + "\"triangles\": [ [0, 1, 2], [3, 4, 5] ]"
            + "}";

    /** {@code 9.8.1:data/tests/tinshift_crs_implicit.json}, metadata elided. */
    private static final String CRS_IMPLICIT = "{"
            + "\"file_type\": \"triangulation_file\","
            + "\"format_version\": \"1.0\","
            + "\"transformed_components\": [ \"horizontal\" ],"
            + "\"vertices_columns\": [ \"source_x\", \"source_y\", \"target_x\", \"target_y\" ],"
            + "\"triangles_columns\": [ \"idx_vertex1\", \"idx_vertex2\", \"idx_vertex3\" ],"
            + "\"vertices\": [ [2,49,2.1,49.1], [3,50,3.1,50.1], [2, 50, 2.1,50.1] ],"
            + "\"triangles\": [ [0, 1, 2] ]"
            + "}";

    private static double[] coord(double x, double y, double z) {
        return new double[] {x, y, z, 0.0};
    }

    /**
     * {@code tinshift.gie:35-41}. Tolerance 0.1 mm, and the expected value is
     * independently verified against the Finnish national service.
     */
    @Test
    public void kkjToEtrsMatchesUpstreamToATenthOfAMillimetre() {
        Triangulation t = Triangulation.parse(KKJ_ETRS);
        assertTrue(t.transformsHorizontal());
        assertFalse(t.transformsVertical());
        assertEquals("EPSG:2393", t.inputCrs());
        assertEquals("EPSG:3067", t.outputCrs());

        double[] c = coord(3210000.0, 6700000.0, 0);
        assertTrue(t.forward(c));
        assertEquals(209948.3217, c[0], 1e-4);
        assertEquals(6697187.0009, c[1], 1e-4);

        // roundtrip 1: the inverse locates the point in the target geometry and
        // interpolates the source columns, so it must land back on the input.
        assertTrue(t.inverse(c));
        assertEquals(3210000.0, c[0], 1e-4);
        assertEquals(6700000.0, c[1], 1e-4);
    }

    /**
     * {@code tinshift.gie:43-48}: a vertical-only file. The horizontal ordinates are
     * carried through untouched, and z gains the interpolated offset.
     */
    @Test
    public void verticalOnlyFileShiftsOnlyZ() {
        Triangulation t = Triangulation.parse(N60_N2000);
        assertFalse(t.transformsHorizontal());
        assertTrue(t.transformsVertical());

        double[] c = coord(3210000.0, 6700000.0, 10.0);
        assertTrue(t.forward(c));
        assertEquals(3210000.0, c[0], 0.0);
        assertEquals(6700000.0, c[1], 0.0);
        assertEquals(10.2886, c[2], 1e-4);

        assertTrue(t.inverse(c));
        assertEquals(10.0, c[2], 1e-9);
    }

    /** {@code tinshift.gie:50-55}: {@code (2, 3)} is outside both triangles. */
    @Test
    public void nearestSideExtrapolates() {
        Triangulation t = Triangulation.parse(NEAREST_SIDE);
        assertEquals(Triangulation.FALLBACK_NEAREST_SIDE, t.fallbackStrategy());

        double[] c = coord(2, 3, 0);
        assertTrue(t.forward(c));
        assertEquals(4.0, c[0], 1e-9);
        assertEquals(6.0, c[1], 1e-9);

        assertTrue(t.inverse(c));
        assertEquals(2.0, c[0], 1e-9);
        assertEquals(3.0, c[1], 1e-9);
    }

    /**
     * {@code tinshift.gie:57-62}: {@code (3, 0)} is outside both triangles, and the
     * nearest <em>centroid</em> belongs to the first triangle, whose interpolation there
     * happens to be the identity. Picking the nearest <em>side</em> instead would select
     * the second triangle and give a wildly different answer, so this row is what
     * separates the two strategies.
     */
    @Test
    public void nearestCentroidExtrapolates() {
        Triangulation t = Triangulation.parse(NEAREST_CENTROID);
        assertEquals(Triangulation.FALLBACK_NEAREST_CENTROID, t.fallbackStrategy());

        double[] c = coord(3, 0, 0);
        assertTrue(t.forward(c));
        assertEquals(3.0, c[0], 1e-9);
        assertEquals(0.0, c[1], 1e-9);
    }

    /**
     * {@code tinshift.gie:23-33}: the single triangle is
     * {@code (2,49) (3,50) (2,50)} and the shift is {@code +0.1} in both ordinates.
     * {@code (0, 0)} is far outside it, and with {@code fallback_strategy} unset that is
     * a failure in <em>both</em> directions — the corpus asserts both, and the inverse
     * matters separately because it locates the point in the target geometry.
     */
    @Test
    public void noFallbackMeansOutsideIsAFailure() {
        Triangulation t = Triangulation.parse(CRS_IMPLICIT);
        assertEquals(Triangulation.FALLBACK_NONE, t.fallbackStrategy());

        double[] inside = coord(2, 49, 0);
        assertTrue(t.forward(inside));
        assertEquals(2.1, inside[0], 1e-9);
        assertEquals(49.1, inside[1], 1e-9);
        assertTrue(t.inverse(inside));
        assertEquals(2.0, inside[0], 1e-9);
        assertEquals(49.0, inside[1], 1e-9);

        assertFalse("forward of (0,0) is outside the source triangulation",
                t.forward(coord(0, 0, 0)));
        assertFalse("inverse of (0,0) is outside the target triangulation",
                t.inverse(coord(0, 0, 0)));
    }

    // ------------------------------------------------------------------ rejections

    @Test
    public void fallbackStrategyRequiresFormatVersionOnePointOne() {
        String bad = NEAREST_SIDE.replace("\"format_version\": \"1.1\"",
                "\"format_version\": \"1.0\"");
        assertRejected(bad, "fallback_strategy needs format_version 1.1");
    }

    @Test
    public void unknownFallbackStrategyIsRejected() {
        String bad = NEAREST_SIDE.replace("nearest_side", "nearest_unicorn");
        assertRejected(bad, "invalid fallback_strategy");
    }

    @Test
    public void unknownTransformedComponentIsRejected() {
        String bad = KKJ_ETRS.replace("\"horizontal\"", "\"diagonal\"");
        assertRejected(bad, "is not handled");
    }

    @Test
    public void missingTargetColumnIsRejected() {
        String bad = KKJ_ETRS.replace("\"target_x\", ", "");
        assertRejected(bad, "target_x must be specified");
    }

    /**
     * {@code tinshift_impl.hpp} tests {@code type() != number_unsigned} for a vertex
     * index, which is stricter than "is a number": a fractional or negative index is a
     * parse error, not a truncation.
     */
    @Test
    public void fractionalVertexIndexIsRejected() {
        String bad = KKJ_ETRS.replace("[0, 1, 2]", "[0, 1.5, 2]");
        assertRejected(bad, "is not an integer");
    }

    @Test
    public void outOfRangeVertexIndexIsRejected() {
        String bad = KKJ_ETRS.replace("\"triangles\": [ [0, 1, 2] ]",
                "\"triangles\": [ [0, 1, 3] ]");
        assertRejected(bad, "Invalid value for a vertex index");
    }

    @Test
    public void wrongVertexRowWidthIsRejected() {
        String bad = KKJ_ETRS.replace("[3218328.492, 6649538.429, 218273.648, 6646745.973]",
                "[3218328.492, 6649538.429, 218273.648]");
        assertRejected(bad, "not expected number of elements");
    }

    @Test
    public void nonTriangulationFileIsRejected() {
        assertRejected("{\"file_type\": \"deformation_model_master_file\","
                + "\"format_version\": \"1.0\"}", "not \"triangulation_file\"");
    }

    @Test
    public void notJsonAtAllIsRejected() {
        assertRejected("[general]\nkey = value\n", "");
    }

    private static void assertRejected(String model, String expectedFragment) {
        try {
            Triangulation.parse(model);
            fail("expected a PipelineDefinitionException for: " + model);
        } catch (PipelineDefinitionException e) {
            assertEquals("a model PROJ also refuses is a definition error",
                    PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID, e.code());
            assertTrue("message was: " + e.getMessage(),
                    e.getMessage().contains(expectedFragment));
        }
    }
}

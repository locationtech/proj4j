/*******************************************************************************
 * Copyright 2026 Proj4J contributors
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
package org.locationtech.proj4j.grids;

import java.io.IOException;

import org.junit.Test;
import org.locationtech.proj4j.datum.VerticalGrid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The GTX reader, against PROJ 9.8.1's {@code cct +proj=vgridshift} reading the same file.
 *
 * <p>GTX is what {@code +geoidgrids=} needs and it is the simplest format PROJ supports: a 40-byte
 * big-endian header then {@code rows * columns} big-endian {@code float}s, south to north and west to
 * east. It has <strong>no magic bytes at all</strong>, which is why PROJ dispatches on the
 * {@code .gtx}/{@code .GTX} filename suffix, and why this reader does the same rather than guessing.
 *
 * <p>The fixture is PROJ's own {@code data/tests/egm96_15_downsampled.gtx}: 180 &times; 360 nodes over
 * the whole world, so the wrap-around and edge-clamping branches of the interpolation are reachable.
 */
public class GtxVerticalGridTest {

    private static VerticalGrid egm96() throws IOException {
        return VerticalGrid.fromName("egm96_15_downsampled.gtx");
    }

    @Test
    public void headerIsParsedAsPro981ParsesIt() throws IOException {
        VerticalGrid g = egm96();
        assertEquals("gtx", g.getFormat());
        assertEquals(360, g.getWidth());
        assertEquals(180, g.getHeight());

        double[] extent = g.extentRadians();
        assertEquals("west", -179.625, Math.toDegrees(extent[0]), 1e-9);
        assertEquals("south", -89.62430555555557, Math.toDegrees(extent[1]), 1e-9);

        double[] res = g.resolutionRadians();
        assertEquals("xstep", 1.0, Math.toDegrees(res[0]), 1e-12);
        assertEquals("ystep", 1.0013888888888889, Math.toDegrees(res[1]), 1e-12);
    }

    @Test
    public void firstNodeMatchesTheRawFile() throws IOException {
        // Big-endian float at byte 40 of the fixture.
        assertEquals(-30.167558670043945f, egm96().nodeAt(0, 0), 0.0f);
    }

    @Test
    public void interpolatedValuesMatchProj981() throws IOException {
        VerticalGrid g = egm96();
        for (int i = 0; i < GridReferenceValues.GTX_POINTS.length; i++) {
            double lon = GridReferenceValues.GTX_POINTS[i][0];
            double lat = GridReferenceValues.GTX_POINTS[i][1];
            double got = g.valueAt(Math.toRadians(lon), Math.toRadians(lat));
            assertEquals("geoid undulation at " + lon + ", " + lat,
                    GridReferenceValues.GTX_EXPECTED[i], got, GridReferenceValues.GTX_TOL);
        }
    }

    @Test
    public void theMultiplierScalesTheResult() throws IOException {
        VerticalGrid g = egm96();
        double one = g.valueAt(0.0, 0.0, 1.0);
        double half = g.valueAt(0.0, 0.0, 0.5);
        assertEquals(one * 0.5, half, 1e-12);
    }

    /**
     * The extent test and the interpolation are two separate gates in PROJ, and this reader keeps them
     * separate for the same reason.
     *
     * <p>PROJ's {@code VerticalShiftGridSet::gridAt} performs the extent test and only then calls
     * {@code read_vgrid_value}, which itself <em>clamps</em> the upper index at the last row rather than
     * refusing. So {@code covers()} is the gate a caller must consult; {@code valueAt} on its own will
     * happily extrapolate the top half-cell, exactly as PROJ does. Asserting otherwise would be a
     * divergence from upstream dressed up as strictness.
     */
    @Test
    public void coversIsTheExtentGateAndValueAtClampsAtTheEdgeLikeProj() throws IOException {
        VerticalGrid g = egm96();
        assertTrue("(0,0) is inside", g.covers(0.0, 0.0));

        double[] extent = g.extentRadians();
        double justBeyondNorth = Math.toDegrees(extent[3]) + 0.2;
        assertFalse("beyond the last row centre, covers() says no",
                g.covers(0.0, Math.toRadians(justBeyondNorth)));

        assertTrue("NaN input is rejected outright",
                Double.isNaN(g.valueAt(Double.NaN, 0.0)));
    }

    /** A regional grid does have points that fall outside the index range entirely. */
    @Test
    public void aPointOutsideTheIndexRangeYieldsNoValue() throws IOException {
        VerticalGrid g = egm96();
        // Far south of the first row: grid_y is negative, so there is no cell at all.
        double belowSouth = Math.toDegrees(g.extentRadians()[1]) - 5.0;
        assertTrue("well south of the grid there is nothing to interpolate from",
                Double.isNaN(g.valueAt(0.0, Math.toRadians(belowSouth))));
    }

    @Test
    public void geoidGridsHonoursTheOptionalPrefix() throws IOException {
        assertEquals("a missing optional vertical grid is skipped, like PROJ",
                1, VerticalGrid.fromGeoidGrids("@nope.gtx,egm96_15_downsampled.gtx").size());
        try {
            VerticalGrid.fromGeoidGrids("nope.gtx");
            fail("a required missing vertical grid must throw");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Unknown vertical grid"));
        }
    }

    /**
     * The suffix rule is a rule, not a heuristic: a horizontal grid handed to the vertical reader is
     * rejected with a message that says why, rather than being interpreted as 40 bytes of header plus
     * noise.
     *
     * <p>The vertical reader now has two dispatch arms, matching
     * {@code VerticalShiftGridSet::open} ({@code 9.8.1:src/grids.cpp:1613-1671}): the
     * {@code .gtx}/{@code .GTX} filename suffix, because GTX has no magic bytes, and then the TIFF
     * signature. {@code conus} is CTABLE V2, so it matches neither and is refused — which is the
     * invariant this test is about. The message wording changed when the GeoTIFF arm landed; the
     * behaviour did not.
     */
    @Test
    public void aNonGtxNameIsRejectedRatherThanGuessedAt() {
        try {
            VerticalGrid.fromName("conus");
            fail("conus is CTABLE V2: neither GTX nor GeoTIFF");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("Unrecognised vertical grid format"));
            assertTrue("the message must name what it does read: " + expected.getMessage(),
                    expected.getMessage().contains("GTX")
                            && expected.getMessage().contains("GeoTIFF"));
        }
    }
}

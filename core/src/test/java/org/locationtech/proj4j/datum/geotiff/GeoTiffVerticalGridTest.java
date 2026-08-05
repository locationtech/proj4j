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
package org.locationtech.proj4j.datum.geotiff;

import java.io.IOException;

import org.junit.Test;
import org.locationtech.proj4j.datum.VerticalGrid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Vertical GeoTIFF grids: sixteen encodings of one number, plus nodata, subgrids and the antimeridian.
 *
 * <p>Upstream's fixture set is built so that every {@code test_vgrid_*.tif} encodes the <em>same</em>
 * surface. Sixteen assertions all reading {@link GeoTiffFixtures#PROBE_VALUE} is therefore a
 * sixteen-way cross-check of the container reader — endianness, classic versus BigTIFF, strip layout,
 * DEFLATE, the floating-point predictor, the six sample types, scale/offset, and the
 * {@code PixelIsArea}/{@code PixelIsPoint} half-pixel shift — with the interpolation held constant. A
 * bug in any one of those paths shows up as exactly one failing row and names itself.
 *
 * <p>See {@link GeoTiffFixtures} for the provenance of both the bytes and the numbers.
 */
public class GeoTiffVerticalGridTest {

    private static final double D2R = Math.PI / 180.0;

    private static double valueAt(String name, double lonDeg, double latDeg) throws IOException {
        VerticalGrid grid = VerticalGrid.fromName(name);
        assertTrue(name + " must report that it covers (" + lonDeg + ", " + latDeg + ")",
                grid.covers(lonDeg * D2R, latDeg * D2R));
        return grid.valueAt(lonDeg * D2R, latDeg * D2R, 1.0);
    }

    private static void probe(String name) throws IOException {
        assertEquals(name + " must interpolate to PROJ 9.8.1's value",
                GeoTiffFixtures.PROBE_VALUE,
                valueAt(name, GeoTiffFixtures.PROBE_LON, GeoTiffFixtures.PROBE_LAT),
                GeoTiffFixtures.TOL);
    }

    /** Baseline: uncompressed float32, a single strip, {@code RasterPixelIsPoint}. */
    @Test
    public void pixelIsPoint() throws Exception {
        probe("test_vgrid_pixelispoint.tif");
    }

    /**
     * {@code RasterPixelIsArea} shifts the tie point by half a pixel
     * ({@code west += 0.5 * hRes; north -= 0.5 * vRes}). The two files declare different tie points
     * (4/55 versus 3.5/55.5) precisely so that only a reader applying the shift gets the same answer
     * from both.
     */
    @Test
    public void pixelIsArea() throws Exception {
        probe("test_vgrid_pixelisarea.tif");
    }

    /**
     * DEFLATE plus {@code Predictor=3}. Not an exotic case: all seven of PROJ's US grids are encoded
     * this way, so this row failing means none of them can be read.
     */
    @Test
    public void deflateWithFloatingPointPredictor() throws Exception {
        probe("test_vgrid_deflate_floatingpointpredictor.tif");
    }

    /** {@code uint16} raised through {@code role="scale"} 0.5 and {@code role="offset"} &minus;5. */
    @Test
    public void uint16WithScaleAndOffset() throws Exception {
        probe("test_vgrid_uint16_with_scale_offset.tif");
    }

    @Test
    public void int16() throws Exception {
        probe("test_vgrid_int16.tif");
    }

    /** {@code float64}, which PROJ narrows to {@code float} on the way out — and so must we. */
    @Test
    public void float64() throws Exception {
        probe("test_vgrid_float64.tif");
    }

    @Test
    public void bigEndianClassicTiff() throws Exception {
        probe("test_vgrid_bigendian.tif");
    }

    @Test
    public void littleEndianBigTiff() throws Exception {
        probe("test_vgrid_bigtiff.tif");
    }

    @Test
    public void bigEndianBigTiff() throws Exception {
        probe("test_vgrid_bigendian_bigtiff.tif");
    }

    /** A negative {@code GeoPixelScale[1]}: the first image row is the southernmost. */
    @Test
    public void bottomUpFromNegativeScale() throws Exception {
        probe("test_vgrid_bottomup_with_scale.tif");
    }

    /**
     * The same bottom-up geometry expressed as a {@code GeoTransformationMatrix}, which is what GDAL
     * emits because negative pixel scales have historically been treated as writer bugs.
     */
    @Test
    public void bottomUpFromTransformationMatrix() throws Exception {
        probe("test_vgrid_bottomup_with_matrix.tif");
    }

    /**
     * A second IFD carrying a reduced-resolution overview ({@code SubfileType=1}). It must be ignored;
     * interpolating it instead would give a plausible, wrong answer from half-resolution data.
     */
    @Test
    public void overviewIfdIsIgnored() throws Exception {
        probe("test_vgrid_with_overview.tif");
    }

    /** The shift lives in band 1, identified by {@code DESCRIPTION="geoid_undulation"}. */
    @Test
    public void shiftInSecondChannel() throws Exception {
        probe("test_vgrid_in_second_channel.tif");
    }

    /**
     * {@code GDAL_NODATA = -88.8888}. The interesting part is that the GeoTIFF nodata rule is
     * <em>only</em> the declared sentinel plus NaN, whereas GTX also treats any magnitude beyond 1000
     * as nodata. Both rules would pass this row; only the GeoTIFF rule is correct for a GeoTIFF, and
     * {@link #nodataRuleIsGeoTiffsNotGtxs} pins the difference.
     */
    @Test
    public void nodataRenormalisation() throws Exception {
        assertEquals(10.0, valueAt("test_vgrid_nodata.tif", 4.05, 52.1), GeoTiffFixtures.TOL);
    }

    /**
     * A GeoTIFF value of 1500 m is <strong>data</strong>, not nodata. GTX's rule
     * ({@code |value| > 1000}) would discard it; {@code GTiffGrid::isNodata} does not.
     *
     * <p>Asserted through the grid the reader actually builds, rather than by reflecting on a flag:
     * {@code test_vgrid_with_subgrid.tif}'s subgrid holds values around 110 with one node at 1100, and
     * the value PROJ reports at (5.5, 53.5) is exactly 110 — which it could not be if a magnitude
     * heuristic were voiding nodes. The direct statement of the rule is the node read below: nothing in
     * the grid is treated as nodata, because the file declares no {@code GDAL_NODATA} at all.
     */
    @Test
    public void nodataRuleIsGeoTiffsNotGtxs() throws Exception {
        VerticalGrid grid = VerticalGrid.fromName("test_vgrid_with_subgrid.tif");
        assertEquals("gtiff", grid.getFormat());
        assertEquals("the file declares no GDAL_NODATA, so every node is data",
                110.0, grid.valueAt(5.5 * D2R, 53.5 * D2R, 1.0), GeoTiffFixtures.TOL);
    }

    /**
     * A subgrid in a second IFD, discovered purely from spatial extent (no {@code grid_name}
     * metadata). At (4.5, 52.5) the parent applies; at (5.5, 53.5) the finer child does, and the two
     * differ by an order of magnitude, so using the parent everywhere is not a near miss.
     */
    @Test
    public void subgridIsSelectedByExtent() throws Exception {
        VerticalGrid grid = VerticalGrid.fromName("test_vgrid_with_subgrid.tif");
        assertEquals("the file has one top grid with one child", 2, grid.countGrids());
        assertEquals(1, grid.getSubGrids().size());
        assertEquals(11.5, grid.valueAt(4.5 * D2R, 52.5 * D2R, 1.0), GeoTiffFixtures.TOL);
        assertEquals(110.0, grid.valueAt(5.5 * D2R, 53.5 * D2R, 1.0), GeoTiffFixtures.TOL);
    }

    /**
     * {@code DESCRIPTION="hydroid_height"} — one of the four band descriptions upstream accepts, and
     * the only one of them exercised by a real fixture. The value is pinned to twelve decimals because
     * it is a genuine interpolation rather than a round number.
     */
    @Test
    public void hydroidHeightDescription() throws Exception {
        assertEquals(44.643493652344, valueAt("test_hydroid_height.tif", 2.0, 49.0),
                GeoTiffFixtures.TOL);
    }

    /**
     * The antimeridian, which is where every extent test that looks fine on paper breaks.
     *
     * <p>{@code us_noaa_geoid06_ak_subset_at_antimeridian.tif} declares {@code west = 179.8}&deg; and
     * {@code east = 180.1833}&deg; — deliberately past &pi; in radians, which PROJ's extent validation
     * permits (it bounds {@code |west|} and {@code |east|} by 4&pi;). A point given as
     * {@code -179.99}&deg; is inside the grid only after a &plusmn;2&pi; shift, so both
     * {@code covers()} and the interpolation have to wrap, and they have to wrap consistently: the two
     * spellings of the same meridian, {@code 180.1833333} and {@code -179.8166667}, must give the
     * <em>same</em> value.
     *
     * <p>All six values are {@code cct -d 12} output on these bytes.
     */
    @Test
    public void antimeridianWrap() throws Exception {
        String ak = "us_noaa_geoid06_ak_subset_at_antimeridian.tif";
        assertEquals(-2.222574615478, valueAt(ak, 179.99, 54.5), GeoTiffFixtures.TOL);
        assertEquals(-2.348757028579, valueAt(ak, -179.99, 54.5), GeoTiffFixtures.TOL);
        assertEquals(-2.287227840366, valueAt(ak, 179.999999, 54.5), GeoTiffFixtures.TOL);
        assertEquals(-2.287240458607, valueAt(ak, -179.999999, 54.5), GeoTiffFixtures.TOL);
        assertEquals(-0.701101899147, valueAt(ak, 179.8, 54.5), GeoTiffFixtures.TOL);
        assertEquals(-3.193286294634, valueAt(ak, 180.1833333, 54.5), GeoTiffFixtures.TOL);
        assertEquals("the same meridian written two ways must give one value",
                valueAt(ak, 180.1833333, 54.5), valueAt(ak, -179.8166667, 54.5), 0.0);
    }

    /**
     * Just outside the same grid, on both edges and in both longitude conventions.
     *
     * <p>These are {@code expect failure errno coord_transfm_outside_grid} rows in
     * {@code geotiff_grids.gie}. They are the reason the wrap in {@code covers()} cannot simply be
     * "always try both": 179.799&deg; is 0.001&deg; west of the western edge and 180.184&deg; is
     * 0.00067&deg; east of the eastern one, both far outside the {@code (resX + resY) * 1e-5}
     * tolerance, and a reader that accepted them would extrapolate off the edge of the data and report
     * success.
     */
    @Test
    public void justOutsideTheAntimeridianGridIsNotCovered() throws Exception {
        VerticalGrid grid =
                VerticalGrid.fromName("us_noaa_geoid06_ak_subset_at_antimeridian.tif");
        assertFalse("179.799 is west of west=179.8", grid.covers(179.799 * D2R, 54.5 * D2R));
        assertFalse("180.184 is east of east=180.18333", grid.covers(180.184 * D2R, 54.5 * D2R));
        assertFalse("-179.816 is the same meridian as 180.184",
                grid.covers(-179.816 * D2R, 54.5 * D2R));
    }

    /**
     * GTX must be completely unaffected by any of this. The nodata rule, the subgrid descent and the
     * longitude wrap are all carried per grid, and a GTX grid takes the same branch it always took.
     */
    @Test
    public void gtxIsUnchanged() throws Exception {
        VerticalGrid gtx = VerticalGrid.fromName("egm96_15_downsampled.gtx");
        assertEquals("gtx", gtx.getFormat());
        assertEquals("a GTX file has no subgrids", 1, gtx.countGrids());
        assertTrue(gtx.getSubGrids().isEmpty());
        // cct -d 10 +proj=vgridshift +grids=egm96_15_downsampled.gtx +multiplier=1
        assertEquals(17.2340171337, gtx.valueAt(0.0, 0.0, 1.0), 1e-9);
    }
}

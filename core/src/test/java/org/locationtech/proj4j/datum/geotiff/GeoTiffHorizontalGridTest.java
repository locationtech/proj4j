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
import java.util.List;

import org.junit.Test;
import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.datum.Grid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Horizontal shift GeoTIFF grids: eleven encodings of one shift, plus subgrid hierarchies and the
 * real NADCON 5 grids.
 *
 * <p>As with the vertical fixtures, upstream builds every {@code test_hgrid_*.tif} to encode the
 * <em>same</em> displacement field, so eleven rows asserting the one answer cross-check strips against
 * tiles, {@code CONTIG} against {@code SEPARATE}, arc-seconds against radians against degrees, and
 * positive-east against positive-west — with the interpolation held constant.
 *
 * <p>The band-order and sign cases deserve naming, because they are where a reader produces a
 * <em>finite, plausible</em> wrong coordinate rather than an error:
 * <ul>
 *   <li>{@code test_hgrid_lon_shift_first.tif} puts the longitude offset in band 0, so a reader that
 *       trusts the NTv2-inspired default order instead of the {@code DESCRIPTION} metadata swaps the two
 *       components. That is exactly the defect {@code NTV1.java} carried for its whole history — ~8 m of
 *       longitude and ~10 m of latitude, never looking like anything in particular.</li>
 *   <li>{@code test_hgrid_positive_west.tif} declares {@code positive_value="west"}, so a reader that
 *       ignores it gets the longitude shift with the wrong sign: double the error, in the right
 *       units, at a plausible location.</li>
 * </ul>
 *
 * <p>See {@link GeoTiffFixtures} for the provenance of both the bytes and the numbers.
 */
public class GeoTiffHorizontalGridTest {

    private static void probe(String name) throws IOException {
        List<Grid> grids = GeoTiffFixtures.horizontal(name);
        assertEquals("format", "gtiff", grids.get(0).getFormat());
        double[] got = GeoTiffFixtures.shiftDegrees(grids, false,
                GeoTiffFixtures.PROBE_LON, GeoTiffFixtures.PROBE_LAT);
        assertEquals(name + " longitude", GeoTiffFixtures.HGRID_EXPECTED[0], got[0], 1e-8);
        assertEquals(name + " latitude", GeoTiffFixtures.HGRID_EXPECTED[1], got[1], 1e-8);
    }

    /** Baseline: uncompressed float32, {@code CONTIG}, one strip, two bands, no metadata at all. */
    @Test
    public void baseline() throws Exception {
        probe("test_hgrid.tif");
    }

    /** {@code PLANARCONFIG_SEPARATE}: one strip per band, so the block index gains {@code sample * blocks}. */
    @Test
    public void planarSeparate() throws Exception {
        probe("test_hgrid_separate.tif");
    }

    /**
     * Several strips, with {@code RowsPerStrip=3} over a 4-row image — so the last strip is short, and
     * {@code StripByteCounts} is typed {@code SHORT} rather than {@code LONG}, which a reader that
     * assumes one width for offsets and counts mis-parses.
     */
    @Test
    public void multipleStripsWithShortByteCounts() throws Exception {
        probe("test_hgrid_strip.tif");
    }

    /** Tiled, 16x32 tiles over 360x180, DEFLATE: 138 tiles, and a full-world longitude extent. */
    @Test
    public void tiled() throws Exception {
        probe("test_hgrid_tiled.tif");
    }

    /** Tiled <em>and</em> {@code SEPARATE}: 18 tiles per band, 36 in the offset array. */
    @Test
    public void tiledSeparate() throws Exception {
        probe("test_hgrid_tiled_separate.tif");
    }

    /** {@code positive_value="west"}: the longitude offset's sign must be flipped. */
    @Test
    public void positiveWest() throws Exception {
        probe("test_hgrid_positive_west.tif");
    }

    /** Longitude offset in band 0, latitude in band 1, stated by {@code DESCRIPTION}. */
    @Test
    public void longitudeShiftInFirstBand() throws Exception {
        probe("test_hgrid_lon_shift_first.tif");
    }

    /** {@code UNITTYPE="radian"}: no conversion factor at all. */
    @Test
    public void unitRadian() throws Exception {
        probe("test_hgrid_radian.tif");
    }

    /** {@code UNITTYPE="degree"}. */
    @Test
    public void unitDegree() throws Exception {
        probe("test_hgrid_degree.tif");
    }

    /** A reduced-resolution overview in IFD 1 must be ignored, not used. */
    @Test
    public void overviewIfdIsIgnored() throws Exception {
        probe("test_hgrid_with_overview.tif");
    }

    /**
     * IFD 1 holds {@code latitude_offset_accuracy} / {@code longitude_offset_accuracy} bands. It has
     * valid georeferencing and a valid {@code SubfileType}, so nothing structural rejects it — only the
     * band descriptions do. Upstream skips such an IFD and carries on.
     */
    @Test
    public void extraIfdWithAccuracyBandsIsIgnored() throws Exception {
        probe("test_hgrid_extra_ifd_with_other_info.tif");
    }

    /**
     * A four-IFD subgrid hierarchy declared by {@code grid_name} / {@code parent_grid_name}: two roots
     * ({@code CAwest}, {@code CAeast}) each with one child ({@code ALbanff}, {@code ONtronto}).
     *
     * <p>Both probe points sit inside a child. The parent covers them too and would give a different,
     * entirely plausible answer — this is the 1.4.3 NTv2 defect in a new format, where a matched subgrid
     * was located and then ignored.
     *
     * <p>The expected coordinates are upstream's own, from {@code geotiff_grids.gie}.
     */
    @Test
    public void namedSubgridHierarchy() throws Exception {
        assertSubgrids("test_hgrid_with_subgrid.tif");
    }

    /**
     * The same four images with the {@code grid_name}/{@code parent_grid_name} metadata stripped, so the
     * hierarchy has to be recovered from spatial extents alone — upstream's fallback path.
     *
     * <p>It must give the <em>identical</em> answer, which is the strongest available statement that the
     * two code paths agree.
     */
    @Test
    public void subgridHierarchyFromExtentsAlone() throws Exception {
        assertSubgrids("test_hgrid_with_subgrid_no_grid_name.tif");
    }

    /**
     * Both spellings of the four-image hierarchy resolve to <strong>three</strong> top-level grids, not
     * two, and that is upstream's answer rather than a defect here.
     *
     * <p>{@code ONtronto} declares {@code parent_grid_name="CAeast"}, but its western edge
     * (80.5417&deg;W) is 0.0417&deg; <em>outside</em> {@code CAeast}'s (80.5&deg;W), so the declared
     * parent does not contain it. {@code insertIntoHierarchy} ({@code grids.cpp:1398-1415}) treats the
     * declared hierarchy as advisory and the extents as authoritative: it falls back to the
     * bounding-box method, finds no top grid that contains {@code ONtronto} either, and promotes it to a
     * top-level grid. PROJ 9.8.1 says so itself, with {@code PROJ_DEBUG=3}:
     *
     * <pre>
     * Grid ONtronto refers to parent CAeast, but its extent is not included in it.
     * Using bounding-box method.
     * </pre>
     *
     * <p>The result is still correct for the probe point, because {@code findGrid} then reaches
     * {@code ONtronto} as the third entry in the list — which is why the coordinate assertions below
     * matter more than the shape assertions above.
     */
    private static void assertSubgrids(String name) throws IOException {
        List<Grid> grids = GeoTiffFixtures.horizontal(name);
        Grid root = grids.get(0);
        assertEquals(name + " has three top-level grids plus one child, under a synthetic bounding grid",
                5, root.countGrids());
        assertEquals("CAwest, CAeast and the promoted ONtronto", 3, root.getSubGrids().size());

        double[] banff = GeoTiffFixtures.shiftDegrees(grids, false, -115.5416667, 51.1666667);
        assertEquals(-115.5427092888, banff[0], 1e-9);
        assertEquals(51.1666899972, banff[1], 1e-9);

        double[] toronto = GeoTiffFixtures.shiftDegrees(grids, false, -80.5041667, 44.5458333);
        assertEquals(-80.50401615833, toronto[0], 1e-9);
        assertEquals(44.5458827236, toronto[1], 1e-9);
    }

    /**
     * A real NOAA <strong>NADCON 5</strong> grid, in GeoTIFF, which is the format NADCON 5 is published
     * in and the reason no {@code .las}/{@code .los} reader is needed or wanted.
     *
     * <p>Pinned to twelve decimals against
     * {@code cct -d 12 +proj=hgridshift +grids=tests/us_noaa_nadcon5_..._sanfrancisco.tif} on these
     * bytes. The shift is sub-millimetre, which is what NAD83(1986)&rarr;NAD83(HARN) is at San
     * Francisco — so this row also proves the reader is not quietly scaling anything: a factor-of-3600
     * unit slip would show as ~1.4 m.
     */
    @Test
    public void nadcon5ConusExtract() throws Exception {
        List<Grid> grids = GeoTiffFixtures.horizontal(
                "us_noaa_nadcon5_nad83_1986_nad83_harn_conus_extract_sanfrancisco.tif");
        double[] got = GeoTiffFixtures.shiftDegrees(grids, false, -122.4194, 37.7749);
        assertEquals(-122.419400376354, got[0], 1e-11);
        assertEquals(37.774901566552, got[1], 1e-11);
    }

    /**
     * The NADCON 5 Alaska extract, whose tie point is {@code 201.583}&deg; — a longitude in the
     * 0&hellip;360 convention, past &pi; in radians. It is here because it is the small, shippable
     * stand-in for {@code us_noaa_alaska.tif}, which declares {@code west = -194}&deg;: both need
     * {@code isPointInExtent}'s &plusmn;2&pi; shift to match a point given in &minus;180&hellip;180.
     *
     * <p>It also carries a second IFD with one band ({@code ellipsoidal_height_offset} auxiliary data),
     * which must be skipped rather than mistaken for a second horizontal grid.
     */
    @Test
    public void nadcon5AlaskaExtractWithShiftedLongitudeWindow() throws Exception {
        List<Grid> grids = GeoTiffFixtures.horizontal(
                "us_noaa_nadcon5_nad83_2007_nad83_2011_alaska_extract.tif");
        assertEquals("the 1-band auxiliary IFD must not become a second grid",
                1, grids.get(0).countGrids());
        double[] got = GeoTiffFixtures.shiftDegrees(grids, false, -158.0, 61.5);
        assertEquals(-157.999999611484, got[0], 1e-11);
        assertEquals(61.499999564269, got[1], 1e-11);
    }

    /**
     * The inverse shift, whose iterative fixed-point solver is the only data-dependent loop on the grid
     * path — and the only place where a divergent iteration could be delivered as a plausible
     * coordinate rather than as a failure.
     *
     * <p>Pinned against {@code cct -d 12 +inv +proj=hgridshift +grids=tests/test_hgrid.tif} on these
     * bytes, which returns {@code 3.124999997311 49.625000008931} at (4.5, 52.5). Note this is
     * <em>not</em> the inverse of the forward answer: the forward shift moves (4.5, 52.5) to
     * (5.875, 55.375), which is outside the grid's 4&hellip;7&deg;E by 52&hellip;55&deg;N extent
     * entirely, so a forward-then-inverse round trip has nowhere to land. That asymmetry is a property
     * of upstream's deliberately violent test displacement field, not of the solver.
     */
    @Test
    public void inverseMatchesUpstream() throws Exception {
        List<Grid> grids = GeoTiffFixtures.horizontal("test_hgrid.tif");
        double[] back = GeoTiffFixtures.shiftDegrees(grids, true,
                GeoTiffFixtures.PROBE_LON, GeoTiffFixtures.PROBE_LAT);
        assertEquals(3.124999997311, back[0], 1e-9);
        assertEquals(49.625000008931, back[1], 1e-9);
    }

    /**
     * A point outside the grid must be refused, and the grid's declared extent must be the one the
     * file's tags imply. {@code test_hgrid.tif} spans 4&deg;&hellip;7&deg; E, 52&deg;&hellip;55&deg; N.
     *
     * <p>This used to assert that the point was "left alone by the legacy API". It is now
     * {@link ErrorCause#COORDINATE_OUTSIDE_GRID}, which is what
     * {@code pj_hgrid_apply} ({@code grids.cpp:3517-3520}) reports when {@code findGrid} finds
     * nothing — returning the input unchanged is indistinguishable from a zero shift. See
     * {@code grids/OutsideGridFailsClosedTest}.
     */
    @Test
    public void extentMatchesTheTags() throws Exception {
        List<Grid> grids = GeoTiffFixtures.horizontal("test_hgrid.tif");
        double[] e = grids.get(0).extentRadians();
        assertEquals(4.0, Math.toDegrees(e[0]), 1e-12);
        assertEquals(52.0, Math.toDegrees(e[1]), 1e-12);
        assertEquals(7.0, Math.toDegrees(e[2]), 1e-12);
        assertEquals(55.0, Math.toDegrees(e[3]), 1e-12);

        try {
            double[] outside = GeoTiffFixtures.shiftDegrees(grids, false, -40.0, 35.0);
            fail("a point no grid covers must be refused, not echoed; got ("
                    + outside[0] + ", " + outside[1] + ")");
        } catch (CrsTransformException expected) {
            assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, expected.cause());
        }
    }
}

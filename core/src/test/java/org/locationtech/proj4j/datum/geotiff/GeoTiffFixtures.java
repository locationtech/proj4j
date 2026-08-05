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
import java.util.ArrayList;
import java.util.List;

import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.datum.Grid;

/**
 * The GeoTIFF grid fixtures, where every byte of them came from, and where every expected number came
 * from.
 *
 * <h2>Fixture provenance</h2>
 *
 * <p>All 35 {@code .tif} files under {@code core/src/test/resources/proj4j-data/grids/} are
 * <strong>byte-for-byte copies of PROJ 9.8.1's own {@code data/tests/} files</strong>, i.e. of
 * {@code git -C PROJ show 9.8.1:data/tests/<name>}. They were not generated here, downsampled here, or
 * regenerated from proj4j output — which matters, because a fixture written by the code under test
 * agrees with it by construction, forever. Total <strong>49,758 bytes across 35 files</strong>; the
 * largest is 6,943 B. Upstream blob SHAs (first 12 hex digits of the git object at tag {@code 9.8.1},
 * {@code f08fa86c478c4bbbf003b1ec751dd84aa6eca486}) and sizes:
 *
 * <pre>
 * test_hgrid.tif                                     94718c21d817    506
 * test_hgrid_degree.tif                              d06782ec3475    680
 * test_hgrid_extra_ifd_with_other_info.tif           b7e67af723b7   1708
 * test_hgrid_lon_shift_first.tif                     395f0743f9ec    764
 * test_hgrid_positive_west.tif                       4ebc17cc0467    764
 * test_hgrid_radian.tif                              30219ccdf78e    680
 * test_hgrid_separate.tif                            ef2ca575b09a    514
 * test_hgrid_strip.tif                               e38fc609f491    514
 * test_hgrid_tiled.tif                               b0d5dd8b2cfb   4920
 * test_hgrid_tiled_separate.tif                      d7e0934fc461   2256
 * test_hgrid_with_overview.tif                       d7453b49b926    864
 * test_hgrid_with_subgrid.tif                        46a8f2f4f722   6943
 * test_hgrid_with_subgrid_no_grid_name.tif           974699b56173   6943
 * test_hydroid_height.tif                            4b8db2e162d4   1002
 * test_vgrid_bigendian.tif                           5cf4a0392f8b    430
 * test_vgrid_bigendian_bigtiff.tif                   a586b85fad34    568
 * test_vgrid_bigtiff.tif                             2a01893a5180    568
 * test_vgrid_bottomup_with_matrix.tif                90f637dcf50d    474
 * test_vgrid_bottomup_with_scale.tif                 636b7dc77596    430
 * test_vgrid_deflate_floatingpointpredictor.tif      5fd7b9fabf6c    422
 * test_vgrid_float64.tif                             16b3e7902568    494
 * test_vgrid_in_second_channel.tif                   d377f8b74ce2    632
 * test_vgrid_int16.tif                               1c69b5d61d2b    398
 * test_vgrid_invalid_channel_type.tif                ec9e641f741a    560
 * test_vgrid_nodata.tif                              65ec53432653    464
 * test_vgrid_pixelisarea.tif                         a5409f6609aa    430
 * test_vgrid_pixelispoint.tif                        cfeb598f4940    430
 * test_vgrid_single_strip_truncated.tif              9a0030f66878    550
 * test_vgrid_uint16_with_scale_offset.tif            b08fa4a330dd    556
 * test_vgrid_unsupported_byte.tif                    ccf03fc8988f    382
 * test_vgrid_with_overview.tif                       aa15aa1d7228    707
 * test_vgrid_with_subgrid.tif                        5c7584c4b77b    756
 * us_noaa_geoid06_ak_subset_at_antimeridian.tif      2c01759cde7d   6617
 * us_noaa_nadcon5_nad83_1986_nad83_harn_conus_extract_sanfrancisco.tif  c2acae8a2e0d  1285
 * us_noaa_nadcon5_nad83_2007_nad83_2011_alaska_extract.tif              67a8d9af9e01  3547
 * </pre>
 *
 * <p>The last three are real published NOAA data, not synthetic: a NAVD88 geoid subset straddling the
 * antimeridian, and two <strong>NADCON 5</strong> extracts. Those two are the reason there is no NADCON
 * reader anywhere in this codebase and should not be one — {@code 9.8.1:src/grids.cpp} dispatches on
 * exactly four things (NTv1, CTABLE V2, NTv2, TIFF), there is no {@code .las}/{@code .los} handling in
 * {@code src/} at all, and {@code us_noaa_nadcon5_*.tif} is NADCON 5 <em>data in GeoTIFF</em>, read by
 * the TIFF path. This reader is therefore also the NADCON answer.
 *
 * <h2>Expected-value provenance</h2>
 *
 * <p>Every expected number in this package was produced by <strong>PROJ 9.8.1 itself reading the same
 * bytes</strong> — the Homebrew build reporting {@code Rel. 9.8.1, April 10th, 2026}. That is the only
 * reference that isolates reader-plus-interpolation from a data difference. Commands, verbatim:
 *
 * <pre>
 * export PROJ_DATA=&lt;PROJ&gt;/data          # so tests/&lt;name&gt; resolves
 *
 * cct -d 12 +proj=vgridshift +grids=tests/&lt;name&gt; +multiplier=1   &lt;&lt;&lt; "&lt;lon&gt; &lt;lat&gt; 0 0"
 * cct -d 12 +proj=hgridshift +grids=tests/&lt;name&gt;                 &lt;&lt;&lt; "&lt;lon&gt; &lt;lat&gt; 0 0"
 * </pre>
 *
 * <p><strong>{@code cct}, not {@code cs2cs}.</strong> {@code cs2cs} promotes both datum-less sides of a
 * geographic-to-geographic call to full CRSs and then picks its own operation from {@code proj.db}: it
 * ignores {@code +nadgrids=} entirely in that shape. Verified while pinning these values —
 * {@code cs2cs +proj=longlat +ellps=clrk66 +nadgrids=conus +to +proj=longlat +ellps=GRS80} returns the
 * input unchanged, which would have looked like a proj4j bug or, worse, been pinned as truth.
 *
 * <p>{@code cct} prints the value in the operation's own units, so the vertical assertions compare
 * metres against metres and the horizontal ones degrees against degrees, both at 12 decimals.
 */
final class GeoTiffFixtures {

    private GeoTiffFixtures() {
    }

    /** {@code cct -d 12} prints 12 decimals; 1e-9&deg; is ~0.1 mm and 1e-9 m is a nanometre. */
    static final double TOL = 1e-9;

    /** The point almost every synthetic fixture is probed at, chosen by upstream's own gie file. */
    static final double PROBE_LON = 4.5;
    static final double PROBE_LAT = 52.5;

    /**
     * The value every one of the sixteen equivalent {@code test_vgrid_*} spellings must produce at
     * {@link #PROBE_LON}/{@link #PROBE_LAT}. Sixteen files, one number: that is the whole point of the
     * upstream fixture set — they differ only in encoding.
     */
    static final double PROBE_VALUE = 11.5;

    /** The shift every equivalent {@code test_hgrid_*} spelling must produce, in degrees. */
    static final double[] HGRID_EXPECTED = {5.875, 55.375};

    static List<Grid> horizontal(String name) throws IOException {
        List<Grid> list = new ArrayList<Grid>();
        Grid.mergeGridFile(name, list);
        return list;
    }

    /**
     * Applies {@link Grid#shift} to a lon/lat pair in degrees and returns degrees, so assertions read
     * in the same units as the {@code cct} output they are pinned against.
     */
    static double[] shiftDegrees(List<Grid> grids, boolean inverse, double lonDeg, double latDeg) {
        ProjCoordinate c = new ProjCoordinate(Math.toRadians(lonDeg), Math.toRadians(latDeg));
        Grid.shift(grids, inverse, c);
        return new double[]{Math.toDegrees(c.x), Math.toDegrees(c.y)};
    }
}

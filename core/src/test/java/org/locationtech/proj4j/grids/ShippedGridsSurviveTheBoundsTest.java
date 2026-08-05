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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.After;
import org.junit.Test;
import org.locationtech.proj4j.datum.Grid;
import org.locationtech.proj4j.datum.GridCache;
import org.locationtech.proj4j.datum.GridExtents;
import org.locationtech.proj4j.datum.VerticalGrid;
import org.locationtech.proj4j.resource.ChainedResourceResolver;
import org.locationtech.proj4j.resource.ResourceHandle;
import org.locationtech.proj4j.resource.ResourceResolvers;
import org.locationtech.proj4j.resource.Resources;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The positive control for every bound added to the resource and grid layers.
 *
 * <p>A guard that refuses everything passes every hostile test written against it. This is the other
 * half: each shipped grid format is read through the <em>bounded</em> classpath path and must come
 * back byte-identical to the file on disk, then parse, then produce the coordinate PROJ 9.8.1
 * produces from the same bytes. Provenance of the expected values is in
 * {@link GridReferenceValues}; they are {@code cs2cs} and {@code cct} output, not proj4j's.
 *
 * <p>The byte-identity leg is the one that is specific to this workstream. The rewritten reader has
 * three paths — an exactly sized array when the entry declares its length, a truncating copy when it
 * turns out to be shorter, and a bounded stream when it turns out to be longer or declines to say —
 * and a slip in any of them would hand a grid reader a subtly wrong buffer, which is a wrong
 * coordinate rather than an error. Real classpath entries of five formats and four orders of
 * magnitude in size are what pins it.
 */
public class ShippedGridsSurviveTheBoundsTest {

    @After
    public void reset() {
        ResourceResolvers.clearResolvers();
        GridCache.instance().clear();
        GridCache.vertical().clear();
        System.clearProperty("proj4j.grids.maxFileBytes");
    }

    /** Every grid resource in the test grid pack, plus the two that ship in {@code proj4j-epsg}. */
    private static final String[] CLASSPATH_GRIDS = {
            "conus",                                  // CTABLE v2, 273 x 121
            "ntv2_0_downsampled.gsb",                 // NTv2, multi-subgrid
            "ntv1_can.dat",                           // NTv1, 1,113,184 bytes -- the largest here
            "egm96_15_downsampled.gtx",               // GTX, vertical
            "test_hgrid.tif",                         // GeoTIFF, horizontal
            "test_vgrid.tif",                         // GeoTIFF, vertical
            "test_hgrid_tiled.tif",                   // GeoTIFF, tiled
            "test_vgrid_bigendian_bigtiff.tif",       // BigTIFF, byte-swapped
            "us_noaa_geoid06_ak_subset_at_antimeridian.tif",
    };

    /**
     * The bounded read returns exactly the bytes on disk, for every shipped grid.
     *
     * <p>Sizes span 624 bytes to 1,113,184, which exercises both the single-buffer case and the
     * multi-read loop.
     */
    @Test
    public void everyShippedGridReadsBackByteIdenticalThroughTheBoundedPath() throws IOException {
        ChainedResourceResolver chain = ResourceResolvers.resolver();
        int checked = 0;
        long largest = 0;
        for (String name : CLASSPATH_GRIDS) {
            ResourceHandle handle = chain.resolve(name);
            if (handle == null) {
                continue;   // not every name is on every module's test classpath
            }
            Path onDisk = onDisk(name);
            if (onDisk == null) {
                continue;
            }
            byte[] expected = Files.readAllBytes(onDisk);
            byte[] got = Resources.readAll(handle, GridExtents.maxFileBytes());
            assertEquals(name + " came back at the wrong length", expected.length, got.length);
            assertArrayEquals(name + " came back with different bytes", expected, got);
            largest = Math.max(largest, expected.length);
            checked++;
        }
        assertTrue("this test is only evidence if it actually read some grids; checked " + checked,
                checked >= 5);
        assertTrue("and it must have read something big enough to need more than one buffer; "
                + "largest was " + largest, largest > 64 * 1024);
    }

    /** CTABLE v2, NTv1 and NTv2, against PROJ 9.8.1's own output for the same bytes. */
    @Test
    public void theHorizontalFormatsStillShiftCorrectly() throws IOException {
        List<Grid> conus = GridReferenceValues.singleton("conus");
        assertEquals("ctable2", conus.get(0).getFormat());
        assertShift(conus, GridReferenceValues.SAN_FRANCISCO,
                GridReferenceValues.CONUS_FWD_SAN_FRANCISCO, false);
        assertShift(conus, GridReferenceValues.SAN_FRANCISCO,
                GridReferenceValues.CONUS_INV_SAN_FRANCISCO, true);
        assertShift(conus, GridReferenceValues.CHICAGO, GridReferenceValues.CONUS_FWD_CHICAGO, false);

        List<Grid> ntv1 = GridReferenceValues.singleton("ntv1_can.dat");
        assertEquals("ntv1", ntv1.get(0).getFormat());
        assertShift(ntv1, GridReferenceValues.CHICAGO, GridReferenceValues.NTV1_FWD_CHICAGO, false);
        assertShift(ntv1, GridReferenceValues.BOSTON, GridReferenceValues.NTV1_FWD_BOSTON, false);

        List<Grid> ntv2 = GridReferenceValues.singleton("ntv2_0_downsampled.gsb");
        assertEquals("ntv2", ntv2.get(0).getFormat());
        assertShift(ntv2, GridReferenceValues.NTV2_ONWINSOR,
                GridReferenceValues.NTV2_FWD_ONWINSOR, false);
        assertShift(ntv2, GridReferenceValues.NTV2_ALBANFF,
                GridReferenceValues.NTV2_FWD_ALBANFF, false);
        assertShift(ntv2, GridReferenceValues.NTV2_CAWEST,
                GridReferenceValues.NTV2_FWD_CAWEST, false);
    }

    /** GeoTIFF, the fourth horizontal format, through the same bounded read. */
    @Test
    public void theGeoTiffFormatStillLoads() throws IOException {
        List<Grid> tif = GridReferenceValues.singleton("test_hgrid.tif");
        assertEquals("gtiff", tif.get(0).getFormat());
        double[] extent = tif.get(0).extentRadians();
        assertEquals(4.0, Math.toDegrees(extent[0]), 1e-12);
        assertEquals(52.0, Math.toDegrees(extent[1]), 1e-12);
        assertEquals(7.0, Math.toDegrees(extent[2]), 1e-12);
        assertEquals(55.0, Math.toDegrees(extent[3]), 1e-12);
        double[] shifted = GridReferenceValues.shiftDegrees(tif, false, 5.0, 53.0);
        assertTrue("the GeoTIFF grid must actually shift the point", shifted[0] != 5.0);
    }

    /** GTX, the vertical format, against {@code cct}'s output for the same bytes. */
    @Test
    public void theVerticalGtxStillInterpolates() throws IOException {
        VerticalGrid gtx = VerticalGrid.fromName("egm96_15_downsampled.gtx");
        assertNotNull(gtx);
        for (int i = 0; i < GridReferenceValues.GTX_POINTS.length; i++) {
            double lon = Math.toRadians(GridReferenceValues.GTX_POINTS[i][0]);
            double lat = Math.toRadians(GridReferenceValues.GTX_POINTS[i][1]);
            assertEquals("GTX point " + i, GridReferenceValues.GTX_EXPECTED[i],
                    gtx.valueAt(lon, lat), GridReferenceValues.GTX_TOL);
        }
    }

    /**
     * The unification, proved from both sides.
     *
     * <p>{@code VerticalGrid.fromName} carried a hardcoded 512 MiB literal where
     * {@code Grid.resolveAndLoad} used {@code proj4j.grids.maxFileBytes} at 128 MiB, so the one
     * documented knob for "how large may a grid file be" governed only half the grids. Setting the
     * property must now bite on a vertical grid as well as a horizontal one — and the default must
     * still admit every grid that ships.
     */
    @Test
    public void oneCeilingGovernsBothKindsOfGrid() throws IOException {
        // Default: everything shipped loads. This is the direction that must not regress, since
        // unifying moved the vertical ceiling DOWN by a factor of four.
        assertNotNull(VerticalGrid.fromName("egm96_15_downsampled.gtx"));
        assertEquals(1, GridReferenceValues.singleton("ntv1_can.dat").size());

        System.setProperty("proj4j.grids.maxFileBytes", "512");
        GridCache.instance().clear();
        GridCache.vertical().clear();
        try {
            try {
                VerticalGrid.fromName("egm96_15_downsampled.gtx");
                fail("a 512-byte ceiling must refuse the vertical grid too; before the unification "
                        + "this path used its own 512 MiB literal and ignored the property");
            } catch (IOException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("exceeds"));
            }
            try {
                GridReferenceValues.singleton("conus");
                fail("and the horizontal path, at the same ceiling");
            } catch (IOException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("exceeds"));
            }
        } finally {
            System.clearProperty("proj4j.grids.maxFileBytes");
            GridCache.instance().clear();
            GridCache.vertical().clear();
        }

        // ...and it comes back. A ceiling that latched would be worse than one that never fired.
        assertNotNull(VerticalGrid.fromName("egm96_15_downsampled.gtx"));
        assertEquals(1, GridReferenceValues.singleton("conus").size());
    }

    private static void assertShift(List<Grid> grids, double[] point, double[] expected,
                                    boolean inverse) {
        double[] got = GridReferenceValues.shiftDegrees(grids, inverse, point[0], point[1]);
        assertEquals("longitude", expected[0], got[0], GridReferenceValues.TOL_DEG);
        assertEquals("latitude", expected[1], got[1], GridReferenceValues.TOL_DEG);
    }

    private static Path onDisk(String name) {
        for (String prefix : new String[]{ResourceResolvers.GRID_PREFIX,
                ResourceResolvers.LEGACY_GRID_PREFIX}) {
            java.net.URL url = ShippedGridsSurviveTheBoundsTest.class.getResource(
                    "/" + prefix + "/" + name);
            if (url == null || !"file".equals(url.getProtocol())) {
                continue;
            }
            try {
                return Paths.get(url.toURI());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}

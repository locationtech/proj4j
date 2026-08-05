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
import java.util.List;

import org.junit.Test;
import org.locationtech.proj4j.datum.Grid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Multi-subgrid NTv2.
 *
 * <p>The fixture is PROJ 9.8.1's own {@code data/tests/ntv2_0_downsampled.gsb}: seven subgrids in a
 * two-level hierarchy — four regional roots ({@code CAeast}, {@code CAwest}, {@code CAnorth},
 * {@code CAarctic}) and three high-resolution children ({@code ONwinsor} under {@code CAeast},
 * {@code ALraymnd} and {@code ALbanff} under {@code CAwest}). This is the ordinary shape of a real
 * NTv2 file, not an edge case: {@code ca_nrc_ntv2_0.gsb} and
 * {@code uk_os_OSTN15_NTv2_OSGBtoETRS.gsb} are both like this.
 *
 * <p>1.4.3's reader was documented as <em>"only files with 1 subfile are supported"</em>, but it did not
 * <em>reject</em> a multi-subgrid file — it read the first subgrid and used it for the whole file. So
 * {@code CAeast} silently became authoritative for all of Canada: a point in Alberta fell outside it and
 * got no shift at all while the transform reported success, and a point in the {@code ONwinsor}
 * high-resolution window got {@code CAeast}'s coarse value instead.
 *
 * <p>A second, independent defect had to be fixed for subgrids to work even once they were parsed:
 * {@code Grid.shift} captured {@code table = grid.table} <em>before</em> descending into children and
 * then interpolated from that captured parent table, so a matched child was located and then ignored.
 */
public class Ntv2MultiSubgridTest {

    private static List<Grid> ntv2() throws IOException {
        return GridReferenceValues.singleton("ntv2_0_downsampled.gsb");
    }

    @Test
    public void allSevenSubgridsAreReadAndTheHierarchyIsRebuilt() throws IOException {
        List<Grid> grids = ntv2();
        assertEquals(1, grids.size());
        Grid file = grids.get(0);
        assertEquals("ntv2", file.getFormat());
        assertEquals("seven subgrids plus the synthetic bounding parent", 8, file.countGrids());

        List<Grid> roots = file.getSubGrids();
        assertEquals("four top-level regional grids", 4, roots.size());

        int children = 0;
        for (Grid root : roots) {
            children += root.getSubGrids().size();
        }
        assertEquals("three high-resolution children", 3, children);
    }

    @Test
    public void aPointInACaWestChildUsesTheChildAndMatchesProj981() throws IOException {
        assertShift(GridReferenceValues.NTV2_ALRAYMND, GridReferenceValues.NTV2_FWD_ALRAYMND,
                "ALraymnd (child of CAwest)");
        assertShift(GridReferenceValues.NTV2_ALBANFF, GridReferenceValues.NTV2_FWD_ALBANFF,
                "ALbanff (child of CAwest)");
    }

    @Test
    public void aPointInTheCaEastChildUsesTheChildAndMatchesProj981() throws IOException {
        assertShift(GridReferenceValues.NTV2_ONWINSOR, GridReferenceValues.NTV2_FWD_ONWINSOR,
                "ONwinsor (child of CAeast)");
    }

    /**
     * The decisive case for the "first subgrid only" defect: this point is in {@code CAwest}, which is
     * the <em>second</em> subgrid in the file, and outside {@code CAeast}. 1.4.3 returned it unchanged.
     */
    @Test
    public void aPointOnlyInTheSecondRootSubgridIsStillShifted() throws IOException {
        double[] got = GridReferenceValues.shiftDegrees(ntv2(), false,
                GridReferenceValues.NTV2_CAWEST[0], GridReferenceValues.NTV2_CAWEST[1]);
        assertTrue("a point in CAwest must not be returned unchanged",
                Math.abs(got[0] - GridReferenceValues.NTV2_CAWEST[0]) > 1e-6);
        assertEquals("CAwest longitude", GridReferenceValues.NTV2_FWD_CAWEST[0], got[0],
                GridReferenceValues.TOL_DEG);
        assertEquals("CAwest latitude", GridReferenceValues.NTV2_FWD_CAWEST[1], got[1],
                GridReferenceValues.TOL_DEG);
    }

    /**
     * The descent must actually change the answer, otherwise every assertion above would still pass with
     * a broken descent.
     *
     * <p>The comparison value is PROJ 9.8.1 reading a deliberately truncated copy of the same fixture —
     * {@code NUM_FILE} forced to 1 and everything after {@code CAeast}'s data removed, i.e. exactly what
     * 1.4.3 effectively read. PROJ returns {@code (-82.9999230409, 42.1000442803)} for that file and
     * {@code (-82.9999212472, 42.1000428139)} for the full one: about 0.22 m apart. Small, and entirely
     * invisible to a caller.
     */
    @Test
    public void theChildValueDiffersFromWhatCaEastAloneWouldGive() throws IOException {
        double[] caEastOnly = {-82.9999230409, 42.1000442803};
        double[] fine = GridReferenceValues.shiftDegrees(ntv2(), false,
                GridReferenceValues.NTV2_ONWINSOR[0], GridReferenceValues.NTV2_ONWINSOR[1]);

        assertEquals("must equal the full-hierarchy PROJ answer",
                GridReferenceValues.NTV2_FWD_ONWINSOR[0], fine[0], GridReferenceValues.TOL_DEG);
        assertTrue("must NOT equal the CAeast-only answer that 1.4.3 produced; got "
                        + fine[0] + "," + fine[1],
                Math.abs(fine[0] - caEastOnly[0]) > 1e-7 || Math.abs(fine[1] - caEastOnly[1]) > 1e-7);
    }

    @Test
    public void bytesReadAreAccountedForInTheCacheBudget() throws IOException {
        Grid file = ntv2().get(0);
        // 1248 + 960 + 1044 + 290 + 10431 + 1071 + 231 = 15275 nodes.
        assertEquals("accounted node bytes", 15275L * 32L, file.sizeBytes());
    }

    private static void assertShift(double[] in, double[] expected, String label) throws IOException {
        double[] got = GridReferenceValues.shiftDegrees(ntv2(), false, in[0], in[1]);
        assertEquals(label + " longitude", expected[0], got[0], GridReferenceValues.TOL_DEG);
        assertEquals(label + " latitude", expected[1], got[1], GridReferenceValues.TOL_DEG);
    }
}

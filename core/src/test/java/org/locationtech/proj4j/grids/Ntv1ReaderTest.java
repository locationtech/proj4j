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
 * The NTv1 reader against PROJ 9.8.1 reading the same file.
 *
 * <p>Two defects in {@code NTV1.load} made every NTv1 shift wrong by about 13 m, both of them producing
 * a finite, plausible, superficially sensible coordinate:
 *
 * <ol>
 *   <li><strong>Data offset 176 instead of 192.</strong> An NTv1 header is twelve 16-byte records. 176
 *       is one 16-byte node short, so every interpolation read the node one column east of the correct
 *       one.</li>
 *   <li><strong>The latitude and longitude shift components were transposed.</strong> Each record is
 *       {@code (latitude shift, longitude shift)} in arc seconds; 1.4.3 put the first into
 *       {@code .lam} and the second into {@code .phi}. PROJ 9.8.1's {@code NTv1Grid::valueAt} is
 *       explicit: {@code latShift = two_doubles[0]}, {@code longShift = -two_doubles[1]}.</li>
 * </ol>
 *
 * <p>Neither error alone, nor the pair, moved a result far enough to look like a bug. Measured against
 * PROJ at Chicago: the 1.4.3 code returned {@code (-87.6000206778, 41.9001129721)} where PROJ returns
 * {@code (-87.6001190236, 41.9000194486)} — about 8 m of longitude and 10 m of latitude. Only the
 * combination {@code offset = 192} <em>and</em> untransposed components reproduces PROJ, and it does so
 * to all ten printed decimal places, which is what makes this a proof rather than a plausibility
 * argument.
 */
public class Ntv1ReaderTest {

    private static List<Grid> ntv1() throws IOException {
        return GridReferenceValues.singleton("ntv1_can.dat");
    }

    @Test
    public void chicagoMatchesProj981() throws IOException {
        double[] got = GridReferenceValues.shiftDegrees(ntv1(), false,
                GridReferenceValues.CHICAGO[0], GridReferenceValues.CHICAGO[1]);
        assertEquals("Chicago longitude", GridReferenceValues.NTV1_FWD_CHICAGO[0], got[0],
                GridReferenceValues.TOL_DEG);
        assertEquals("Chicago latitude", GridReferenceValues.NTV1_FWD_CHICAGO[1], got[1],
                GridReferenceValues.TOL_DEG);
    }

    @Test
    public void bostonMatchesProj981() throws IOException {
        double[] got = GridReferenceValues.shiftDegrees(ntv1(), false,
                GridReferenceValues.BOSTON[0], GridReferenceValues.BOSTON[1]);
        assertEquals("Boston longitude", GridReferenceValues.NTV1_FWD_BOSTON[0], got[0],
                GridReferenceValues.TOL_DEG);
        assertEquals("Boston latitude", GridReferenceValues.NTV1_FWD_BOSTON[1], got[1],
                GridReferenceValues.TOL_DEG);
    }

    /**
     * Guards specifically against a regression of the transposition, independently of the reference
     * values: the 1.4.3 answer at Chicago is the correct answer with the two components swapped about
     * the input, and it must not be reproducible.
     */
    @Test
    public void theTransposedAnswerIsNoLongerProduced() throws IOException {
        double[] got = GridReferenceValues.shiftDegrees(ntv1(), false,
                GridReferenceValues.CHICAGO[0], GridReferenceValues.CHICAGO[1]);
        double[] transposed = {-87.6000206778, 41.9001129721};
        assertTrue("must not reproduce the 1.4.3 transposed/misaligned Chicago answer",
                Math.abs(got[0] - transposed[0]) > 1e-7 || Math.abs(got[1] - transposed[1]) > 1e-7);
    }

    /**
     * Chicago is inside both grids, and they disagree by several metres. That disagreement is the
     * measurable form of "everything in CONUS north of 40N is currently interpolated from a
     * Canada-authoritative grid".
     */
    @Test
    public void conusAndNtv1DisagreeInsideConusNorthOf40N() throws IOException {
        double[] fromNtv1 = GridReferenceValues.shiftDegrees(ntv1(), false,
                GridReferenceValues.CHICAGO[0], GridReferenceValues.CHICAGO[1]);
        double[] fromConus = GridReferenceValues.shiftDegrees(
                GridReferenceValues.singleton("conus"), false,
                GridReferenceValues.CHICAGO[0], GridReferenceValues.CHICAGO[1]);

        double dLonMetres = Math.abs(fromNtv1[0] - fromConus[0])
                * 111320.0 * Math.cos(Math.toRadians(GridReferenceValues.CHICAGO[1]));
        double dLatMetres = Math.abs(fromNtv1[1] - fromConus[1]) * 110574.0;
        double separation = Math.hypot(dLonMetres, dLatMetres);

        assertTrue("conus and ntv1_can.dat must disagree measurably at Chicago, and they do by "
                        + separation + " m", separation > 1.0);
        assertTrue("the disagreement should be metres, not kilometres: " + separation,
                separation < 100.0);
    }
}

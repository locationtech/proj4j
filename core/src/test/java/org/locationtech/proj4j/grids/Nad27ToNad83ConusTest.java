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
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.datum.Datum;
import org.locationtech.proj4j.datum.Grid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The headline defect: NAD27 &rarr; NAD83 in the conterminous United States.
 *
 * <p>Downstream, {@code EPSG:4267 -> EPSG:4269} returned the input coordinate unchanged, giving
 * 95.573 m of error at San Francisco and 23.091 m in Kansas — finite, plausible, and unflagged. It was
 * diagnosed as missing data. <strong>That diagnosis was half right, and both halves are now
 * closed.</strong> There are two independent
 * halves:
 *
 * <ol>
 *   <li><strong>Data.</strong> {@code proj4j-epsg} shipped only {@code ntv1_can.dat}, whose footprint is
 *       Canada (see {@link Ntv1CanHeaderTest}), so no grid covered San Francisco or Kansas at all. Cost
 *       to fix: <strong>264,424 bytes</strong>. PROJ ships {@code data/tests/conus} in-tree, it is
 *       CTABLE V2, and {@code datum/CTABLEV2.java} already reads that format — zero new parsing code.
 *       <em>This half is fixed and is what this class asserts.</em></li>
 *   <li><strong>Code.</strong> {@code parser/Proj4Parser.java:53} calls {@code setGrids(null)} on the
 *       static {@code Datum.NAD27} singleton, destroying JVM-wide whatever grids it did have.
 *       this has since been fixed by
 *       the parser workstream. {@link #nad27SingletonSurvivesParsingAnEpsgCode()} is the joint that
 *       fails if either half regresses.</li>
 * </ol>
 *
 * <p>All expected values come from PROJ 9.8.1 reading the identical {@code conus} bytes; see
 * {@link GridReferenceValues} for the exact commands.
 */
public class Nad27ToNad83ConusTest {

    private static final CRSFactory CRS_FACTORY = new CRSFactory();
    private static final CoordinateTransformFactory CT_FACTORY = new CoordinateTransformFactory();

    private static List<Grid> conus() throws IOException {
        return GridReferenceValues.singleton("conus");
    }

    // --- The data half, at full precision, directly on Grid.shift ----------------------------

    @Test
    public void conusIsResolvableAndIsCtable2() throws IOException {
        List<Grid> grids = conus();
        assertEquals(1, grids.size());
        Grid g = grids.get(0);
        assertEquals("ctable2", g.getFormat());
        assertTrue("origin should name where the bytes came from, got " + g.getOrigin(),
                g.getOrigin().contains("conus"));

        double[] extent = g.extentRadians();
        assertEquals("west", -131.0, Math.toDegrees(extent[0]), 1e-9);
        assertEquals("south", 20.0, Math.toDegrees(extent[1]), 1e-9);
        assertEquals("east", -63.0, Math.toDegrees(extent[2]), 1e-9);
        assertEquals("north", 50.0, Math.toDegrees(extent[3]), 1e-9);
    }

    @Test
    public void forwardShiftMatchesProj981AtAllFourPoints() throws IOException {
        List<Grid> grids = conus();
        assertShift(grids, false, GridReferenceValues.SAN_FRANCISCO,
                GridReferenceValues.CONUS_FWD_SAN_FRANCISCO, "San Francisco");
        assertShift(grids, false, GridReferenceValues.KANSAS,
                GridReferenceValues.CONUS_FWD_KANSAS, "Kansas");
        assertShift(grids, false, GridReferenceValues.CHICAGO,
                GridReferenceValues.CONUS_FWD_CHICAGO, "Chicago");
        assertShift(grids, false, GridReferenceValues.BOSTON,
                GridReferenceValues.CONUS_FWD_BOSTON, "Boston");
    }

    /**
     * The inverse is the iterative branch of {@code nad_cvt}. It also exercises the corrected
     * convergence predicate: 1.4.3 stopped as soon as <em>either</em> residual component was within
     * tolerance, where PROJ 9.8.1 requires the squared 2-norm of both to be.
     */
    @Test
    public void inverseShiftMatchesProj981AtAllFourPoints() throws IOException {
        List<Grid> grids = conus();
        assertShift(grids, true, GridReferenceValues.SAN_FRANCISCO,
                GridReferenceValues.CONUS_INV_SAN_FRANCISCO, "San Francisco (inverse)");
        assertShift(grids, true, GridReferenceValues.KANSAS,
                GridReferenceValues.CONUS_INV_KANSAS, "Kansas (inverse)");
        assertShift(grids, true, GridReferenceValues.CHICAGO,
                GridReferenceValues.CONUS_INV_CHICAGO, "Chicago (inverse)");
        assertShift(grids, true, GridReferenceValues.BOSTON,
                GridReferenceValues.CONUS_INV_BOSTON, "Boston (inverse)");
    }

    @Test
    public void forwardThenInverseRoundTripsToSubMillimetre() throws IOException {
        List<Grid> grids = conus();
        double[][] points = {
                GridReferenceValues.SAN_FRANCISCO, GridReferenceValues.KANSAS,
                GridReferenceValues.CHICAGO, GridReferenceValues.BOSTON};
        for (double[] p : points) {
            double[] fwd = GridReferenceValues.shiftDegrees(grids, false, p[0], p[1]);
            double[] back = GridReferenceValues.shiftDegrees(grids, true, fwd[0], fwd[1]);
            assertEquals("round-trip longitude at " + p[0] + "," + p[1], p[0], back[0], 1e-9);
            assertEquals("round-trip latitude at " + p[0] + "," + p[1], p[1], back[1], 1e-9);
        }
    }

    /** The magnitudes the downstream report measured, reproduced from the grid data. */
    @Test
    public void theShiftMagnitudesAreTheReportedOnes() throws IOException {
        List<Grid> grids = conus();

        double sf = metres(GridReferenceValues.SAN_FRANCISCO,
                GridReferenceValues.shiftDegrees(grids, false,
                        GridReferenceValues.SAN_FRANCISCO[0], GridReferenceValues.SAN_FRANCISCO[1]));
        assertEquals("San Francisco NAD27->NAD83 shift, metres", 95.5, sf, 1.5);

        double ks = metres(GridReferenceValues.KANSAS,
                GridReferenceValues.shiftDegrees(grids, false,
                        GridReferenceValues.KANSAS[0], GridReferenceValues.KANSAS[1]));
        assertTrue("Kansas NAD27->NAD83 shift should be tens of metres, got " + ks,
                ks > 15.0 && ks < 40.0);
    }

    /**
     * A point covered by no grid in the list is an error.
     *
     * <p>1.4.3 returned it unchanged, and this test asserted that. It no longer does:
     * {@code cct +proj=hgridshift +grids=conus} at (40&deg;W, 35&deg;N) is
     * {@code TRANSFORMATION ERROR (Coordinate to transform falls outside grid)}, and an unchanged
     * coordinate is indistinguishable from a zero shift. See {@link OutsideGridFailsClosedTest} for
     * the full account, including the control that proves the guard discriminates.
     */
    @Test
    public void aPointOutsideEveryGridIsRefusedRatherThanEchoed() throws IOException {
        List<Grid> grids = conus();
        try {
            double[] got = GridReferenceValues.shiftDegrees(grids, false,
                    GridReferenceValues.OPEN_OCEAN[0], GridReferenceValues.OPEN_OCEAN[1]);
            fail("PROJ 9.8.1 refuses this point; shift returned (" + got[0] + ", " + got[1] + ")");
        } catch (CrsTransformException expected) {
            assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, expected.cause());
        }
    }

    // --- The CRS-level path, with the grid supplied explicitly --------------------------------

    /**
     * The whole transform, end to end, with {@code +nadgrids=conus} written into the source CRS so the
     * result does not depend on the {@code Datum.NAD27} singleton. This is the assertion that says the
     * data half works today.
     *
     * <p>The tolerance is looser than {@link GridReferenceValues#TOL_DEG} on purpose: Proj4J's CRS path
     * additionally routes through geocentric coordinates to change ellipsoid, which perturbs latitude
     * by a fraction of a millimetre that has nothing to do with the grid. It is still four orders of
     * magnitude tighter than the defect.
     */
    @Test
    public void crsLevelNad27ToNad83ProducesTheRealShiftWhenTheGridIsNamed() {
        CoordinateReferenceSystem src = CRS_FACTORY.createFromParameters("nad27+conus",
                "+proj=longlat +ellps=clrk66 +nadgrids=conus +no_defs");
        CoordinateReferenceSystem tgt = CRS_FACTORY.createFromParameters("nad83",
                "+proj=longlat +datum=NAD83 +no_defs");
        CoordinateTransform t = CT_FACTORY.createTransform(src, tgt);

        assertNear("San Francisco", t, GridReferenceValues.SAN_FRANCISCO,
                GridReferenceValues.CONUS_FWD_SAN_FRANCISCO, 1e-7);
        assertNear("Kansas", t, GridReferenceValues.KANSAS,
                GridReferenceValues.CONUS_FWD_KANSAS, 1e-7);
        assertNear("Chicago", t, GridReferenceValues.CHICAGO,
                GridReferenceValues.CONUS_FWD_CHICAGO, 1e-7);
        assertNear("Boston", t, GridReferenceValues.BOSTON,
                GridReferenceValues.CONUS_FWD_BOSTON, 1e-7);
    }

    // --- The code half, documented as still broken --------------------------------------------

    // --- the code half: joined, and verified end to end --------------------------------------

    /**
     * <strong>The two halves are joined.</strong>
     *
     * <p>{@code parser/Proj4Parser.java:53} used to call {@code setGrids(null)} on the <em>static</em>
     * {@code Datum.NAD27} singleton, which destroyed JVM-wide whatever grids it had:
     * <pre>
     *   Datum datum = datumParam.getDatum();     // the STATIC Datum.NAD27 singleton
     *   datum.setGrids(datumParam.getGrids());   // mutates it, process-wide
     * </pre>
     * {@code EPSG:4267} expands to {@code +proj=longlat +datum=NAD27 +no_defs}, which carries no
     * {@code +nadgrids} token, so {@code getGrids()} was {@code null} and
     * {@code Datum.NAD27.setGrids(null)} executed permanently. {@code getTransformType()} then flipped
     * from {@code TYPE_GRIDSHIFT} to {@code TYPE_UNKNOWN} and every NAD27 transform in the process
     * silently stopped shifting; 205 EPSG codes reached the same path.
     *
     * <p>That mutation has since been removed by the parser workstream. This test is the joint: it fails
     * if either half regresses -- the parser reintroducing the mutation, or {@code conus} leaving the
     * classpath.
     */
    @Test
    public void nad27SingletonSurvivesParsingAnEpsgCode() {
        CRS_FACTORY.createFromName("EPSG:4267");
        assertEquals(
                "Datum.NAD27 must still declare a grid shift after EPSG:4267 has been parsed. A value "
                        + "of TYPE_UNKNOWN (0) means the setGrids(null) mutation at Proj4Parser.java:53 "
                        + "has come back.",
                Datum.TYPE_GRIDSHIFT, Datum.NAD27.getTransformType());
    }

    /**
     * The defect, retired: {@code EPSG:4267 -> EPSG:4269} at San Francisco.
     *
     * <p>1.4.3 returned the input unchanged -- 95.573 m of error, finite, plausible and unflagged. With
     * {@code conus} reachable through the resolver chain and the singleton mutation gone, the answer now
     * agrees with PROJ 9.8.1 to about 1e-10 of a degree.
     */
    @Test
    public void epsg4267ToEpsg4269NowProducesTheRealShift() throws IOException {
        CoordinateTransform t = CT_FACTORY.createTransform(
                CRS_FACTORY.createFromName("EPSG:4267"),
                CRS_FACTORY.createFromName("EPSG:4269"));

        ProjCoordinate out = new ProjCoordinate();
        t.transform(new ProjCoordinate(GridReferenceValues.SAN_FRANCISCO[0],
                GridReferenceValues.SAN_FRANCISCO[1]), out);

        double moved = metres(GridReferenceValues.SAN_FRANCISCO, new double[]{out.x, out.y});
        assertTrue("the coordinate must actually move, and by about 95.5 m; measured " + moved,
                moved > 90.0 && moved < 100.0);
        assertEquals("longitude vs PROJ 9.8.1 reading the same conus bytes",
                GridReferenceValues.CONUS_FWD_SAN_FRANCISCO[0], out.x, 1e-7);
        assertEquals("latitude vs PROJ 9.8.1 reading the same conus bytes",
                GridReferenceValues.CONUS_FWD_SAN_FRANCISCO[1], out.y, 1e-7);
        assertFalse("and conus is what made it possible", conus().isEmpty());
    }

    /** The same, at all four points and in both directions, through the EPSG codes. */
    @Test
    public void epsg4267ToEpsg4269MatchesProj981AtEveryPoint() {
        CoordinateTransform fwd = CT_FACTORY.createTransform(
                CRS_FACTORY.createFromName("EPSG:4267"),
                CRS_FACTORY.createFromName("EPSG:4269"));
        assertNear("SF via EPSG codes", fwd, GridReferenceValues.SAN_FRANCISCO,
                GridReferenceValues.CONUS_FWD_SAN_FRANCISCO, 1e-7);
        assertNear("Kansas via EPSG codes", fwd, GridReferenceValues.KANSAS,
                GridReferenceValues.CONUS_FWD_KANSAS, 1e-7);
        assertNear("Chicago via EPSG codes", fwd, GridReferenceValues.CHICAGO,
                GridReferenceValues.CONUS_FWD_CHICAGO, 1e-7);
        assertNear("Boston via EPSG codes", fwd, GridReferenceValues.BOSTON,
                GridReferenceValues.CONUS_FWD_BOSTON, 1e-7);

        CoordinateTransform inv = CT_FACTORY.createTransform(
                CRS_FACTORY.createFromName("EPSG:4269"),
                CRS_FACTORY.createFromName("EPSG:4267"));
        assertNear("SF inverse via EPSG codes", inv, GridReferenceValues.SAN_FRANCISCO,
                GridReferenceValues.CONUS_INV_SAN_FRANCISCO, 1e-7);
        assertNear("Kansas inverse via EPSG codes", inv, GridReferenceValues.KANSAS,
                GridReferenceValues.CONUS_INV_KANSAS, 1e-7);
    }

    // --- helpers ------------------------------------------------------------------------------

    private static void assertShift(List<Grid> grids, boolean inverse, double[] in, double[] expected,
                                    String label) {
        double[] got = GridReferenceValues.shiftDegrees(grids, inverse, in[0], in[1]);
        assertEquals(label + " longitude", expected[0], got[0], GridReferenceValues.TOL_DEG);
        assertEquals(label + " latitude", expected[1], got[1], GridReferenceValues.TOL_DEG);
    }

    private static void assertNear(String label, CoordinateTransform t, double[] in, double[] expected,
                                   double tolDeg) {
        ProjCoordinate out = new ProjCoordinate();
        t.transform(new ProjCoordinate(in[0], in[1]), out);
        assertEquals(label + " longitude", expected[0], out.x, tolDeg);
        assertEquals(label + " latitude", expected[1], out.y, tolDeg);
    }

    /** Rough local-plane separation in metres. Only ever used to sanity-check a magnitude. */
    private static double metres(double[] a, double[] b) {
        double lat = Math.toRadians((a[1] + b[1]) / 2);
        double dx = (b[0] - a[0]) * 111320.0 * Math.cos(lat);
        double dy = (b[1] - a[1]) * 110574.0;
        return Math.hypot(dx, dy);
    }
}

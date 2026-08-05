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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.datum.Grid;
import org.locationtech.proj4j.datum.GridCache;
import org.locationtech.proj4j.resource.DirectoryResourceResolver;
import org.locationtech.proj4j.resource.ResourceResolvers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code Grid.shift} outside every grid: an error, not the input coordinate.
 *
 * <h2>The defect</h2>
 *
 * <p>1.4.3's {@code shift} ended in an {@code else} branch that did <em>nothing</em>:
 *
 * <pre>
 *   if (!Double.isNaN(output.lam)) { in.x = output.lam; in.y = output.phi; }
 *   else { /&#42; no table covered this point &#42;/ }
 * </pre>
 *
 * <p>The coordinate came back bit-identical to the input, which is indistinguishable from "the
 * shift was zero", and the transform reported success. The comment on that branch already named
 * {@link ErrorCause#COORDINATE_OUTSIDE_GRID} as the intended signal; it simply never raised it.
 *
 * <h2>Upstream, measured rather than assumed</h2>
 *
 * <p>Every expected outcome below was taken from <b>PROJ 9.8.1</b> ({@code cct}/{@code cs2cs}
 * reporting {@code Rel. 9.8.1, April 10th, 2026}) reading <em>the same grid bytes this repository
 * ships</em>, with {@code PROJ_DATA} pointed at a directory holding only those files:
 *
 * <pre>
 * printf -- "-40 35 0 0\n"             | cct -d 10 +proj=hgridshift +grids=conus
 *   # Record 0 TRANSFORMATION ERROR (Coordinate to transform falls outside grid)
 *
 * printf -- "-100 19.999999 0 0\n"     | cct -d 12 +proj=hgridshift +grids=conus
 *   -100.000014233333  20.000382961118
 * printf -- "-100 19.99998 0 0\n"      | cct -d 12 +proj=hgridshift +grids=conus
 *   # Record 0 TRANSFORMATION ERROR (Coordinate to transform falls outside grid)
 *
 * printf -- "-40 35 0 0\n"             | cct -d 10 +proj=hgridshift +grids=null
 *   -40.0000000000   35.0000000000
 *
 * echo "1 -1" | cs2cs -f "%.10f" +proj=longlat +datum=WGS84 \
 *          +to +proj=longlat +ellps=clrk66 +nadgrids=&#64;conus,&#64;alaska,&#64;ntv2_0.gsb,&#64;ntv1_can.dat
 *   *	* inf
 * </pre>
 *
 * <p>The last one is the whole ticket in one line: <b>the exact {@code +datum=NAD27} grid list,
 * with every grid present, refuses a point outside all of them.</b> proj4j answered
 * {@code 1, -1} — the input, unshifted, reported as a success.
 *
 * <h2>Why the assertions are mostly on {@code Grid.shift} and not on CRS pairs</h2>
 *
 * <p>Because {@code cct +proj=hgridshift} is the operator this class ports, and comparing operator
 * to operator holds the datum semantics, the ellipsoid change and the operation-selection layer
 * fixed. That last one matters here and is <b>not</b> a divergence:
 * {@code cs2cs +to +proj=longlat +datum=NAD27} at (1, -1) returns the point unchanged, because with
 * {@code proj.db} present PROJ's operation factory sees the coordinate is outside NADCON's area of
 * use and selects <em>"Ballpark geographic offset"</em> (verified with {@code PROJ_DEBUG=2}) — a
 * declared no-op operation, not a silent one. proj4j's legacy {@code +datum=}/{@code +nadgrids=}
 * path has no such factory; it is the operator path, and the operator path errors.
 *
 * @see Nad27EdgeRoundTripTest for the 40&deg;N round-trip regression this same fix retires
 */
public class OutsideGridFailsClosedTest {

    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final double DEG = Math.PI / 180.0;

    private static final CRSFactory CRS_FACTORY = new CRSFactory();
    private static final CoordinateTransformFactory CT_FACTORY = new CoordinateTransformFactory();

    private Path root;

    @After
    public void cleanUp() throws IOException {
        ResourceResolvers.clearResolvers();
        GridCache.instance().clear();
        if (root != null) {
            for (String name : new String[]{"anisotropic", "plain"}) {
                Files.deleteIfExists(root.resolve(name));
            }
            Files.deleteIfExists(root);
            root = null;
        }
    }

    // ---------------------------------------------------------------- the defect, retired

    /**
     * The headline: a point no grid covers is {@link ErrorCause#COORDINATE_OUTSIDE_GRID}.
     * {@code cct +proj=hgridshift +grids=conus} at (40&deg;W, 35&deg;N) is
     * {@code TRANSFORMATION ERROR (Coordinate to transform falls outside grid)}.
     */
    @Test
    public void aPointOutsideEveryGridIsAnErrorAndNotTheInputCoordinate() throws IOException {
        List<Grid> conus = GridReferenceValues.singleton("conus");
        try {
            double[] got = GridReferenceValues.shiftDegrees(conus, false,
                    GridReferenceValues.OPEN_OCEAN[0], GridReferenceValues.OPEN_OCEAN[1]);
            fail("no grid covers (" + GridReferenceValues.OPEN_OCEAN[0] + ", "
                    + GridReferenceValues.OPEN_OCEAN[1] + ") and PROJ 9.8.1 refuses it, yet shift "
                    + "returned (" + got[0] + ", " + got[1] + ")");
        } catch (CrsTransformException expected) {
            assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, expected.cause());
            assertTrue("the message must name the grids that were searched: "
                    + expected.getMessage(), expected.getMessage().contains("conus"));
            assertTrue("and must say the point was outside all of them: " + expected.getMessage(),
                    expected.getMessage().contains("outside every grid"));
        }
    }

    /**
     * The same for the inverse direction, which is a different branch of {@code nad_cvt} and used
     * to have its own way of producing {@code NaN}.
     */
    @Test
    public void theInverseDirectionAlsoRefusesRatherThanEchoing() throws IOException {
        List<Grid> conus = GridReferenceValues.singleton("conus");
        try {
            GridReferenceValues.shiftDegrees(conus, true,
                    GridReferenceValues.OPEN_OCEAN[0], GridReferenceValues.OPEN_OCEAN[1]);
            fail("expected COORDINATE_OUTSIDE_GRID from the inverse direction too");
        } catch (CrsTransformException expected) {
            assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, expected.cause());
            assertTrue("the inverse direction should say so: " + expected.getMessage(),
                    expected.getMessage().startsWith("inverse grid shift"));
        }
    }

    /**
     * <b>What silence looked like.</b> This is the shape of the defect, asserted directly: with the
     * exception swallowed, the coordinate the caller is holding is <em>bit-identical</em> to the one
     * it passed in. Every digit of it is the answer 1.4.3 returned, and no caller could have
     * distinguished it from a grid whose shift happened to be zero.
     *
     * <p>It is here so that a future change which downgrades the throw back to a no-op cannot look
     * harmless: the assertion below would still pass, and the two above would be the only thing
     * standing between this library and a 95 m error reported as success.
     */
    @Test
    public void withTheExceptionSwallowedTheCallerHoldsExactlyTheInputAgain() throws IOException {
        List<Grid> conus = GridReferenceValues.singleton("conus");
        ProjCoordinate c = new ProjCoordinate(
                GridReferenceValues.OPEN_OCEAN[0] * DEG, GridReferenceValues.OPEN_OCEAN[1] * DEG);
        double x = c.x;
        double y = c.y;
        try {
            Grid.shift(conus, false, c);
            fail("expected COORDINATE_OUTSIDE_GRID");
        } catch (CrsTransformException expected) {
            assertEquals(Double.doubleToLongBits(x), Double.doubleToLongBits(c.x));
            assertEquals(Double.doubleToLongBits(y), Double.doubleToLongBits(c.y));
        }
    }

    // --------------------------------------------- the positive control: it discriminates

    /**
     * <b>The control that makes every assertion above mean something.</b> A guard that threw
     * unconditionally would pass all of them. This one pins the exact latitude at which the guard
     * changes its mind, and both sides of it are PROJ 9.8.1's own answers on the same bytes.
     *
     * <p>{@code conus} has 0.25&deg; cells, so the containment epsilon
     * {@code (resX + resY) * REL_TOLERANCE_HGRIDSHIFT} is {@code 5e-6}&deg; and its south edge is
     * 20&deg;N. <b>The two probes are 1.9e-5&deg; apart — about two metres</b>:
     *
     * <ul>
     *   <li>{@code (100W, 19.999999N)}, one micro-degree out: PROJ shifts it to
     *       {@code -100.000014233333 20.000382961118}, and so must proj4j — <em>silently</em>;</li>
     *   <li>{@code (100W, 19.99998N)}, twenty micro-degrees out: PROJ reports
     *       {@code TRANSFORMATION ERROR}, and so must proj4j.</li>
     * </ul>
     *
     * <p>So the guard cannot be firing on everything, and it cannot be firing on nothing.
     */
    @Test
    public void theGuardDiscriminatesAtTheContainmentToleranceAndNotBefore() throws IOException {
        List<Grid> conus = GridReferenceValues.singleton("conus");

        double[] justInside = GridReferenceValues.shiftDegrees(conus, false, -100.0, 19.999999);
        assertEquals("1e-6 deg below the south edge is inside 9.8.1's tolerance and must NOT throw",
                -100.000014233333, justInside[0], GridReferenceValues.TOL_DEG);
        assertEquals(20.000382961118, justInside[1], GridReferenceValues.TOL_DEG);

        try {
            double[] got = GridReferenceValues.shiftDegrees(conus, false, -100.0, 19.99998);
            fail("2e-5 deg below the south edge is outside 9.8.1's tolerance -- PROJ reports "
                    + "TRANSFORMATION ERROR -- yet shift returned (" + got[0] + ", " + got[1] + ")");
        } catch (CrsTransformException expected) {
            assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, expected.cause());
        }
    }

    /**
     * The other half of the control, on real data at scale rather than at one edge: the four points
     * the grid tests use inside {@code conus} still shift, and still agree with PROJ 9.8.1. If the
     * guard had become indiscriminate this would be the loudest failure in the module.
     */
    @Test
    public void everyPointInsideTheGridStillShiftsAndStillMatchesProj981() throws IOException {
        List<Grid> conus = GridReferenceValues.singleton("conus");
        double[][] points = {
                GridReferenceValues.SAN_FRANCISCO, GridReferenceValues.KANSAS,
                GridReferenceValues.CHICAGO, GridReferenceValues.BOSTON};
        double[][] expected = {
                GridReferenceValues.CONUS_FWD_SAN_FRANCISCO, GridReferenceValues.CONUS_FWD_KANSAS,
                GridReferenceValues.CONUS_FWD_CHICAGO, GridReferenceValues.CONUS_FWD_BOSTON};
        for (int i = 0; i < points.length; i++) {
            double[] got = GridReferenceValues.shiftDegrees(conus, false, points[i][0], points[i][1]);
            assertEquals(expected[i][0], got[0], GridReferenceValues.TOL_DEG);
            assertEquals(expected[i][1], got[1], GridReferenceValues.TOL_DEG);
        }
    }

    // ------------------------------------------------- the three things that must stay silent

    /**
     * <b>PROJ's {@code @} prefix means <em>optional</em>, and skipping an absent grid is correct
     * PROJ behaviour.</b> The fix must not turn "the file is not there" into an error: those are
     * different questions and only "outside a grid we loaded" is the one being closed.
     *
     * <p>With every token {@code @}-optional and every file absent, {@link Grid#fromNadGrids}
     * returns an empty list, {@code Datum.getTransformType()} never reports
     * {@code TYPE_GRIDSHIFT}, and {@code shift} is a no-op — exactly as every upstream operator's
     * {@code if (!grids.empty())} guard requires.
     */
    @Test
    public void anAbsentOptionalGridIsStillSkippedSilentlyAndLeavesAnEmptyNoOpList()
            throws IOException {
        List<Grid> none = Grid.fromNadGrids("@no_such_grid_at_all,@nor_this_one");
        assertTrue("an all-optional, all-missing list must resolve to nothing", none.isEmpty());

        ProjCoordinate c = new ProjCoordinate(-40.0 * DEG, 35.0 * DEG);
        Grid.shift(none, false, c);
        assertEquals(-40.0 * DEG, c.x, 0.0);
        assertEquals(35.0 * DEG, c.y, 0.0);
    }

    /**
     * {@code +nadgrids=null} is PROJ's built-in grid that covers the whole world and shifts nothing.
     * {@code HorizontalShiftGridSet::gridAt} ({@code 9.8.1:src/grids.cpp:2775-2779}) returns it for
     * <em>any</em> point without consulting an extent, and {@code pj_hgrid_apply} then returns the
     * input. Measured: {@code cct +proj=hgridshift +grids=null} at (40&deg;W, 35&deg;N) prints
     * {@code -40.0000000000 35.0000000000}.
     *
     * <p>So no point is ever outside it, and it must short-circuit the new error. This is the one
     * case where returning the input unchanged is the specified answer rather than a fail-open.
     */
    @Test
    public void theNullGridCoversEverythingAndIsNotAnOutsideGridError() throws IOException {
        List<Grid> nul = Grid.fromNadGrids("@null");
        assertEquals(1, nul.size());
        assertTrue("the null grid must identify itself", nul.get(0).isNullGrid());

        ProjCoordinate c = new ProjCoordinate(-40.0 * DEG, 35.0 * DEG);
        Grid.shift(nul, false, c);
        assertEquals(-40.0 * DEG, c.x, 0.0);
        assertEquals(35.0 * DEG, c.y, 0.0);

        // ...and it wins even when it follows a grid that does not contain the point, because
        // upstream's gridAt never looks at an extent for it.
        List<Grid> conusThenNull = Grid.fromNadGrids("conus,null");
        ProjCoordinate d = new ProjCoordinate(-40.0 * DEG, 35.0 * DEG);
        Grid.shift(conusThenNull, false, d);
        assertEquals(-40.0 * DEG, d.x, 0.0);
    }

    /**
     * A non-finite horizontal position has no grid cell. {@code grids.cpp:3411-3412} returns it as
     * it stands ({@code if (in.lam == HUGE_VAL) return in}); turning it into an exception here
     * would make a NaN funnelled from an earlier stage surface as an outside-grid error, which is
     * a false attribution.
     */
    @Test
    public void aNonFiniteInputTravelsRatherThanBecomingAnOutsideGridError() throws IOException {
        List<Grid> conus = GridReferenceValues.singleton("conus");
        ProjCoordinate c = new ProjCoordinate(Double.NaN, Double.NaN);
        Grid.shift(conus, false, c);
        assertTrue("NaN in, NaN out", Double.isNaN(c.x) && Double.isNaN(c.y));

        ProjCoordinate d = new ProjCoordinate(Double.POSITIVE_INFINITY, 0.5);
        Grid.shift(conus, false, d);
        assertEquals(Double.POSITIVE_INFINITY, d.x, 0.0);
    }

    // ------------------------------------- the deliberate divergence, kept and now observable

    /**
     * <b>The recorded deliberate divergence is unchanged.</b>
     *
     * <p>PROJ commits to the grid {@code findGrid} selected: if the interpolation inside it refuses
     * the point, {@code pj_hgrid_apply} reports outside-grid and the next entry of
     * {@code +nadgrids=} is never consulted. {@code Grid.shift} instead continues its loop. That
     * was a conscious choice, is documented, and this fix does not reverse it — reversing it is a
     * separate behavioural change with its own row set.
     *
     * <p>Reaching the divergence needs a grid whose <em>containment epsilon exceeds its
     * interpolation clamp</em>, and for a square grid it never does: the epsilon is
     * {@code (resX + resY) * 1e-5} degrees while the clamp is {@code 1e-4} of a cell, so on
     * {@code conus} (0.25&deg; square) the epsilon is 5e-6&deg; and the clamp 2.5e-5&deg; — every
     * admitted point is clamped. The window opens only when {@code resX > 9 * resY}. The
     * {@code anisotropic} fixture below is 10&deg; &times; 0.1&deg;, giving an epsilon of
     * 1.01e-4&deg; against a clamp of 1e-5&deg;, so the band 1e-5&deg; &lt; d &lt; 1.01e-4&deg;
     * below its south edge is <em>inside the extent and refused by the interpolation</em> — the
     * exact state PROJ and proj4j disagree about.
     */
    @Test
    public void interpolationFailureStillFallsThroughToTheNextGrid() throws IOException {
        List<Grid> both = twoGrids("anisotropic,plain");
        assertEquals(2, both.size());

        // 5e-5 deg below the anisotropic grid's south edge: inside its extent (eps 1.01e-4) and
        // past its clamp (1e-5).
        double[] got = GridReferenceValues.shiftDegrees(both, false, 0.0, -5e-5);
        assertEquals("the second grid must have answered, as it did before this change",
                PLAIN_SHIFT_DEG, 0.0 - got[0], 1e-9);
        assertEquals(-5e-5 + PLAIN_SHIFT_DEG, got[1], 1e-9);
    }

    /**
     * The two controls that make the test above a statement about the fall-through rather than
     * about arithmetic: the first grid alone <em>refuses</em> that point (so the fall-through was
     * genuinely needed), and the second grid alone gives the same answer (so the fall-through
     * genuinely reached it).
     *
     * <p>Note the message: this is the second of the two shapes the new guard reports, and the one
     * that only exists <em>because</em> the fall-through is kept — with a single grid there is
     * nothing to fall through to, and the loop ends where PROJ ends.
     */
    @Test
    public void andTheFallThroughWasNeeded_eachGridAloneProvesIt() throws IOException {
        List<Grid> anisotropic = twoGrids("anisotropic");
        try {
            double[] got = GridReferenceValues.shiftDegrees(anisotropic, false, 0.0, -5e-5);
            fail("the anisotropic grid must contain this point and refuse to interpolate it, yet "
                    + "it returned (" + got[0] + ", " + got[1] + ")");
        } catch (CrsTransformException expected) {
            assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, expected.cause());
            assertTrue("this is the contained-but-uninterpolable shape, and the message says so: "
                            + expected.getMessage(),
                    expected.getMessage().contains("inside the extent of a grid"));
        }

        List<Grid> plain = twoGrids("plain");
        double[] alone = GridReferenceValues.shiftDegrees(plain, false, 0.0, -5e-5);
        assertEquals(PLAIN_SHIFT_DEG, 0.0 - alone[0], 1e-9);
    }

    // ------------------------------------------------------------------------- CRS-level

    /**
     * End to end, with the grid list written into the CRS so the outcome does not depend on which
     * optional grid packs happen to be on the classpath.
     *
     * <p>{@code cs2cs +proj=longlat +datum=WGS84 +to +proj=longlat +ellps=clrk66
     * +nadgrids=&#64;ntv1_can.dat} at (1, -1) prints {@code * * inf}. proj4j returned {@code 1, -1}.
     */
    @Test
    public void aCrsPairWhoseGridDoesNotReachThePointNowFailsClosed() {
        CoordinateReferenceSystem wgs84 = CRS_FACTORY.createFromParameters("wgs84",
                "+proj=longlat +datum=WGS84 +no_defs");
        CoordinateReferenceSystem nad27 = CRS_FACTORY.createFromParameters("nad27+ntv1",
                "+proj=longlat +ellps=clrk66 +nadgrids=@ntv1_can.dat +no_defs");
        CoordinateTransform t = CT_FACTORY.createTransform(wgs84, nad27);

        try {
            ProjCoordinate out = new ProjCoordinate();
            t.transform(new ProjCoordinate(1.0, -1.0), out);
            fail("PROJ 9.8.1 answers '* * inf' here; proj4j returned (" + out.x + ", " + out.y + ")");
        } catch (CrsTransformException expected) {
            assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, expected.cause());
        }
    }

    /** And the same pair inside the grid still works, so the CRS path is not simply broken. */
    @Test
    public void theSameCrsPairStillWorksWhereTheGridReaches() {
        CoordinateReferenceSystem wgs84 = CRS_FACTORY.createFromParameters("wgs84",
                "+proj=longlat +datum=WGS84 +no_defs");
        CoordinateReferenceSystem nad27 = CRS_FACTORY.createFromParameters("nad27+ntv1",
                "+proj=longlat +ellps=clrk66 +nadgrids=@ntv1_can.dat +no_defs");
        CoordinateTransform t = CT_FACTORY.createTransform(wgs84, nad27);

        ProjCoordinate out = new ProjCoordinate();
        t.transform(new ProjCoordinate(-75.6972, 45.4225), out);   // Ottawa, well inside
        assertNotEquals("the point must actually move", -75.6972, out.x, 1e-9);
        assertTrue("and by a plausible NAD27 amount", Math.abs(out.x + 75.6972) < 1e-3);
    }

    // ------------------------------------------------------------------------- fixtures

    /** The plain fixture's longitude shift, in degrees, stored positive-west as the formats do. */
    private static final double PLAIN_SHIFT_DEG = 0.125;

    private List<Grid> twoGrids(String spec) throws IOException {
        if (root == null) {
            root = Files.createTempDirectory("proj4j-outside-grid");
            Files.write(root.resolve("anisotropic"), anisotropicCtable2());
            Files.write(root.resolve("plain"), plainCtable2());
            ResourceResolvers.addResolver(new DirectoryResourceResolver(root));
            GridCache.instance().clear();
        }
        List<Grid> out = new ArrayList<Grid>();
        for (String name : Arrays.asList(spec.split(","))) {
            Grid.mergeGridFile(name, out);
        }
        return out;
    }

    /**
     * 3&times;11 CTABLE V2 over 10&deg;W&ndash;10&deg;E, 0&deg;&ndash;1&deg;N, cells
     * 10&deg;&times;0.1&deg;. The aspect ratio is the whole point: see
     * {@link #interpolationFailureStillFallsThroughToTheNextGrid()}. Its shift values are large and
     * distinctive so that a test which accidentally used <em>this</em> grid could not be mistaken
     * for one that used {@code plain}.
     */
    private static byte[] anisotropicCtable2() {
        return ctable2("anisotropic (eps > clamp by construction)",
                -10.0, 0.0, 10.0, 0.1, 3, 11, 9.0);
    }

    /** 21&times;21 CTABLE V2 over 10&deg;W&ndash;10&deg;E, 10&deg;S&ndash;10&deg;N at 1&deg;. */
    private static byte[] plainCtable2() {
        return ctable2("plain", -10.0, -10.0, 1.0, 1.0, 21, 21, PLAIN_SHIFT_DEG);
    }

    private static byte[] ctable2(String id, double llLamDeg, double llPhiDeg,
                                  double delLamDeg, double delPhiDeg, int cols, int rows,
                                  double shiftDeg) {
        byte[] b = new byte[160 + cols * rows * 8];
        System.arraycopy("CTABLE V2.0     ".getBytes(ASCII), 0, b, 0, 16);
        byte[] name = id.getBytes(ASCII);
        System.arraycopy(name, 0, b, 16, Math.min(name.length, 79));

        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        buf.putDouble(96, llLamDeg * DEG);
        buf.putDouble(104, llPhiDeg * DEG);
        buf.putDouble(112, delLamDeg * DEG);
        buf.putDouble(120, delPhiDeg * DEG);
        buf.putInt(128, cols);
        buf.putInt(132, rows);
        for (int i = 0; i < cols * rows; i++) {
            buf.putFloat(160 + i * 8, (float) (shiftDeg * DEG));
            buf.putFloat(160 + i * 8 + 4, (float) (shiftDeg * DEG));
        }
        return b;
    }
}

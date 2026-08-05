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
package org.locationtech.proj4j.datum;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.util.PolarCoordinate;

/**
 * {@link Grid#shift} after the allocation rewrite: the same answers, and no garbage.
 *
 * <h2>What was rewritten</h2>
 *
 * <p>{@code shift} built two {@link PolarCoordinate}s and an {@code Iterator}; {@code nad_cvt}
 * built three more; {@code nad_intr} built four <em>per call</em> and is called up to eleven times
 * per inverted point. {@code reference/performance.md} priced the inverse path at <b>up to 49
 * allocations per vertex</b> — 4.9 million objects for one 100,000-vertex geometry, on the path the
 * consumer runs per row in a Spark executor. Everything is now a local {@code double}, and the
 * caller's own {@link ProjCoordinate} is the only scratch.
 *
 * <h2>The two risks the rewrite carries, and the test for each</h2>
 *
 * <ol>
 *   <li><b>A changed number.</b> Guarded by the whole existing grid suite, which pins values
 *       produced by PROJ 9.8.1 itself, plus {@link #theTwoOverloadsAgreeBitForBit()} here — the
 *       {@code List} and {@code Grid[]} loops share one body, and this proves they have not
 *       diverged, over thousands of points spanning inside, outside, and both grid edges.</li>
 *   <li><b>Scratch leaking into the caller's coordinate.</b> The caller's {@code ProjCoordinate}
 *       is now written during the computation, so a path that ends without a value must put it
 *       back. {@link #aRefusedShiftLeavesTheCoordinateExactlyAsItWasPassedIn()} is that test, and
 *       it matters: the ordinate a caller sees after catching the refusal used to be, and must
 *       remain, the input.</li>
 * </ol>
 */
public class GridShiftRewriteTest {

    private static List<Grid> conus;
    private static Grid[] conusArray;

    @BeforeClass
    public static void loadGrid() throws IOException {
        List<Grid> loaded = new ArrayList<Grid>();
        Grid.mergeGridFile("conus", loaded);
        conus = loaded;
        conusArray = loaded.toArray(new Grid[0]);
        assertEquals(1, conus.size());
    }

    /**
     * The two overloads must be indistinguishable, bit for bit, on every outcome: a shift, a
     * refusal, and the exception's own message.
     *
     * <p>The sample deliberately runs off the edge of {@code conus} in both axes, so it covers the
     * inside case, the outside case and the half-cell clamp window, forward and inverse.
     */
    @Test
    public void theTwoOverloadsAgreeBitForBit() {
        int shifts = 0;
        int refusals = 0;
        for (int i = 0; i <= 80; i++) {
            for (int j = 0; j <= 40; j++) {
                double lon = -135.0 + i * 1.0;   // -135 .. -55, conus spans -131 .. -63
                double lat = 15.0 + j * 1.0;     //   15 ..  55, conus spans   20 ..  50
                for (int k = 0; k < 2; k++) {
                    boolean inverse = k == 1;
                    Outcome viaList = runList(conus, inverse, lon, lat);
                    Outcome viaArray = runArray(conusArray, inverse, lon, lat);
                    assertEquals("(" + lon + ", " + lat + ") inverse=" + inverse,
                            viaList.toString(), viaArray.toString());
                    if (viaList.threw) {
                        refusals++;
                    } else {
                        shifts++;
                    }
                }
            }
        }
        // Both regimes must actually occur, or the comparison above is a comparison of one case.
        assertTrue("no point in the sample was inside the grid: " + shifts, shifts > 200);
        assertTrue("no point in the sample was outside the grid: " + refusals, refusals > 200);
    }

    /**
     * After a refusal the coordinate must be exactly what was passed in — not scratch, not NaN, not
     * a partially-applied shift.
     *
     * <p>This is the invariant the {@code finally} in {@code shift} exists for. Before the rewrite
     * it held for free, because the scratch was three freshly allocated objects; now it is a
     * property that has to be tested, and the failure mode if it were wrong is the worst kind this
     * library has — a caller that catches the refusal, keeps going, and reads a plausible number
     * out of the coordinate it handed in.
     */
    @Test
    public void aRefusedShiftLeavesTheCoordinateExactlyAsItWasPassedIn() {
        // Inside conus in longitude, well south of it in latitude: the extent test misses.
        double[][] outside = {
                {Math.toRadians(-100.0), Math.toRadians(5.0)},
                {Math.toRadians(20.0), Math.toRadians(45.0)},
                {Math.toRadians(-100.0), Math.toRadians(80.0)},
                {Math.toRadians(-179.0), Math.toRadians(-45.0)},
        };
        for (double[] p : outside) {
            for (int k = 0; k < 2; k++) {
                ProjCoordinate c = new ProjCoordinate(p[0], p[1], 1234.5);
                try {
                    Grid.shift(conus, k == 1, c);
                    fail("expected a refusal at (" + p[0] + ", " + p[1] + ")");
                } catch (CrsTransformException expected) {
                    assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, expected.cause());
                }
                assertEquals("x was left as scratch after a refusal", p[0], c.x, 0.0);
                assertEquals("y was left as scratch after a refusal", p[1], c.y, 0.0);
                assertEquals("z must never be touched by a horizontal shift", 1234.5, c.z, 0.0);

                ProjCoordinate viaArray = new ProjCoordinate(p[0], p[1], 1234.5);
                try {
                    Grid.shift(conusArray, k == 1, viaArray);
                    fail("expected a refusal from the array overload too");
                } catch (CrsTransformException expected) {
                    assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, expected.cause());
                }
                assertEquals(p[0], viaArray.x, 0.0);
                assertEquals(p[1], viaArray.y, 0.0);
            }
        }
    }

    /**
     * The case that actually exercises the scratch: a grid that <em>contains</em> the point and
     * still cannot produce a value.
     *
     * <h4>Why this needs a crafted grid</h4>
     *
     * <p>Measured, by deleting the restore and re-running this class: the four
     * outside-every-grid points above <b>do not detect it</b>, because they fail
     * {@code isPointInExtent} and never reach {@code nad_cvt}, so nothing is written. The leak is
     * only observable on the {@code NO_VALUE} path — inside the extent, no interpolated value —
     * where {@code nad_intr} has already stored its {@code (NaN, NaN)} sentinel in the caller's
     * coordinate. With real grids that path needs NaN node data, which no shipped fixture has, so
     * the table here is built with {@code Float.NaN} shifts.
     *
     * <p>That makes the {@code finally} defensive against reachable-but-unshipped data rather than
     * against anything the current corpus produces — and defensive is the right posture, because
     * the failure it prevents is a caller catching the refusal and reading {@code NaN} out of the
     * coordinate it passed in, which is exactly the "failure expressed as a plausible coordinate"
     * shape this project exists to remove.
     */
    @Test
    public void aGridThatContainsThePointButCannotAnswerAlsoLeavesItAlone() {
        List<Grid> nanGrid = new ArrayList<Grid>();
        nanGrid.add(gridOfNaNs());
        Grid[] nanArray = nanGrid.toArray(new Grid[0]);

        for (int k = 0; k < 2; k++) {
            ProjCoordinate c = new ProjCoordinate(0.15, 0.15, 7.0);
            try {
                Grid.shift(nanGrid, k == 1, c);
                fail("a table of NaN nodes produced a value");
            } catch (CrsTransformException e) {
                assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, e.cause());
                // The `contained` flag must say the point WAS inside a grid; that is the branch
                // this fixture exists to reach, and it distinguishes it from the tests above.
                assertTrue("this fixture is meant to reach the CONTAINED-but-no-value branch,"
                                + " and did not: " + e.getMessage(),
                        e.getMessage().contains("inside the extent"));
            }
            assertEquals("nad_intr's NaN sentinel was left in the caller's coordinate",
                    0.15, c.x, 0.0);
            assertEquals("nad_intr's NaN sentinel was left in the caller's coordinate",
                    0.15, c.y, 0.0);
            assertEquals(7.0, c.z, 0.0);

            ProjCoordinate d = new ProjCoordinate(0.15, 0.15, 7.0);
            try {
                Grid.shift(nanArray, k == 1, d);
                fail("a table of NaN nodes produced a value");
            } catch (CrsTransformException expected) {
                assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, expected.cause());
            }
            assertEquals(0.15, d.x, 0.0);
            assertEquals(0.15, d.y, 0.0);
        }
    }

    /** A 4x4 grid over [0,0.3]x[0,0.3] radians whose every node shift is {@code NaN}. */
    private static Grid gridOfNaNs() {
        Grid g = new Grid();
        g.describeAs("all-nan", "ctable2", "test:all-nan", "test");
        Grid.ConversionTable t = new Grid.ConversionTable();
        t.id = "all-nan";
        t.ll = new PolarCoordinate(0.0, 0.0);
        t.del = new PolarCoordinate(0.1, 0.1);
        t.lim = new org.locationtech.proj4j.util.IntPolarCoordinate(4, 4);
        t.cvs = new org.locationtech.proj4j.util.FloatPolarCoordinate[16];
        for (int i = 0; i < t.cvs.length; i++) {
            t.cvs[i] = new org.locationtech.proj4j.util.FloatPolarCoordinate(Float.NaN, Float.NaN);
        }
        g.table = t;
        return g;
    }

    /** A successful shift must still write the answer, and must still not touch {@code z}. */
    @Test
    public void aSuccessfulShiftWritesXAndYAndNothingElse() {
        ProjCoordinate c = new ProjCoordinate(Math.toRadians(-100.0), Math.toRadians(40.0), 99.0);
        Grid.shift(conus, false, c);
        assertTrue("conus produced no shift at all at (100W, 40N)",
                Math.abs(Math.toDegrees(c.x) + 100.0) > 1e-12);
        assertEquals("z must never be touched by a horizontal shift", 99.0, c.z, 0.0);
    }

    /** A null or empty list is a no-op, in both overloads, and must not throw. */
    @Test
    public void anEmptyListIsANoOp() {
        for (int k = 0; k < 2; k++) {
            ProjCoordinate c = new ProjCoordinate(0.1, 0.2, 0.3);
            Grid.shift(new ArrayList<Grid>(), k == 1, c);
            assertEquals(0.1, c.x, 0.0);
            Grid.shift((List<Grid>) null, k == 1, c);
            assertEquals(0.1, c.x, 0.0);
            Grid.shift(new Grid[0], k == 1, c);
            assertEquals(0.1, c.x, 0.0);
            Grid.shift((Grid[]) null, k == 1, c);
            assertEquals(0.1, c.x, 0.0);
            assertEquals(0.2, c.y, 0.0);
            assertEquals(0.3, c.z, 0.0);
        }
    }

    /** A non-finite input travels rather than throwing, and is not restored into something finite. */
    @Test
    public void nonFiniteInputPassesThrough() {
        double[] bad = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (double v : bad) {
            ProjCoordinate c = new ProjCoordinate(v, Math.toRadians(40.0));
            Grid.shift(conus, false, c);
            assertEquals(Double.doubleToRawLongBits(v), Double.doubleToRawLongBits(c.x));
            ProjCoordinate d = new ProjCoordinate(Math.toRadians(-100.0), v);
            Grid.shift(conusArray, true, d);
            assertEquals(Double.doubleToRawLongBits(v), Double.doubleToRawLongBits(d.y));
        }
    }

    // ============================================================================================
    // The allocation claim, measured in-process
    // ============================================================================================

    /**
     * The point of the rewrite: {@code Grid.shift} allocates nothing.
     *
     * <p>Measured with {@code com.sun.management.ThreadMXBean.getThreadAllocatedBytes}, which
     * counts real TLAB allocation and is not defeated by escape analysis the way a
     * {@code System.gc()}-and-diff estimate would be. JMH's {@code gc.alloc.rate.norm} is the
     * authoritative instrument and {@code GridShiftBenchmark} owns it; this exists so the property
     * is enforced by the ordinary test run too, in seconds rather than in a sixteen-minute gate.
     *
     * <p><b>Positive control included.</b> The same measurement is taken around a loop that
     * allocates one {@link PolarCoordinate} per iteration — exactly what {@code nad_intr} used to
     * do four times over — and is required to see it. Without that, a meter reading zero because
     * it is broken and a meter reading zero because the code is clean are indistinguishable, and
     * this project has already shipped three instruments that could not fail.
     */
    @Test
    public void theShiftPathAllocatesNothing() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        Assume.assumeTrue("getThreadAllocatedBytes is a HotSpot extension",
                bean instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean hotspot = (com.sun.management.ThreadMXBean) bean;
        final long threadId = Thread.currentThread().getId();
        Assume.assumeTrue("thread allocation measurement is disabled",
                hotspot.isThreadAllocatedMemorySupported()
                        && hotspot.isThreadAllocatedMemoryEnabled());

        final int n = 200_000;
        final double lon = Math.toRadians(-100.0);
        final double lat = Math.toRadians(40.0);
        final ProjCoordinate scratch = new ProjCoordinate();

        // CONTROL FIRST, so a broken meter fails before it can report a clean result.
        long controlBefore = hotspot.getThreadAllocatedBytes(threadId);
        long sink = 0;
        for (int i = 0; i < n; i++) {
            PolarCoordinate p = new PolarCoordinate(lon + i * 1e-12, lat);
            sink += Double.doubleToRawLongBits(p.lam);
        }
        long controlBytes = hotspot.getThreadAllocatedBytes(threadId) - controlBefore;
        assertTrue("CONTROL FAILED - the allocation meter did not see " + n + " PolarCoordinates"
                        + " (" + controlBytes + " bytes). Every zero it reports below is"
                        + " meaningless. sink=" + sink,
                controlBytes > n * 8L);

        // Warm up, so class loading and first-call resolution are not counted as allocation.
        for (int i = 0; i < 20_000; i++) {
            scratch.setValue(lon, lat);
            Grid.shift(conus, false, scratch);
            scratch.setValue(lon, lat);
            Grid.shift(conusArray, true, scratch);
        }

        long before = hotspot.getThreadAllocatedBytes(threadId);
        for (int i = 0; i < n; i++) {
            scratch.setValue(lon, lat);
            Grid.shift(conus, false, scratch);
        }
        long forwardList = hotspot.getThreadAllocatedBytes(threadId) - before;

        before = hotspot.getThreadAllocatedBytes(threadId);
        for (int i = 0; i < n; i++) {
            scratch.setValue(lon, lat);
            Grid.shift(conusArray, true, scratch);
        }
        long inverseArray = hotspot.getThreadAllocatedBytes(threadId) - before;

        // Not exactly zero: the JVM samples and the measurement itself is not free. One byte per
        // call would be 200,000; the pre-rewrite inverse path was tens of bytes per call, so a
        // 2,048-byte total over 200,000 calls discriminates by three orders of magnitude.
        assertTrue("Grid.shift(List) allocated " + forwardList + " bytes over " + n
                + " forward shifts", forwardList < 2048);
        assertTrue("Grid.shift(Grid[]) allocated " + inverseArray + " bytes over " + n
                + " inverse shifts", inverseArray < 2048);
    }

    // ============================================================================================

    private static final class Outcome {
        final boolean threw;
        final long xBits;
        final long yBits;
        final String message;

        Outcome(boolean threw, double x, double y, String message) {
            this.threw = threw;
            this.xBits = Double.doubleToRawLongBits(x);
            this.yBits = Double.doubleToRawLongBits(y);
            this.message = message;
        }

        @Override
        public String toString() {
            return (threw ? "THREW " : "OK ") + xBits + " " + yBits + " " + message;
        }
    }

    private static Outcome runList(List<Grid> grids, boolean inverse, double lonDeg, double latDeg) {
        ProjCoordinate c = new ProjCoordinate(Math.toRadians(lonDeg), Math.toRadians(latDeg));
        try {
            Grid.shift(grids, inverse, c);
            return new Outcome(false, c.x, c.y, "");
        } catch (CrsTransformException e) {
            return new Outcome(true, c.x, c.y, e.getMessage());
        }
    }

    private static Outcome runArray(Grid[] grids, boolean inverse, double lonDeg, double latDeg) {
        ProjCoordinate c = new ProjCoordinate(Math.toRadians(lonDeg), Math.toRadians(latDeg));
        try {
            Grid.shift(grids, inverse, c);
            return new Outcome(false, c.x, c.y, "");
        } catch (CrsTransformException e) {
            return new Outcome(true, c.x, c.y, e.getMessage());
        }
    }
}

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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.datum.Grid;
import org.locationtech.proj4j.datum.VerticalGrid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * GeoTIFF grids in the same 36-thread, {@code doubleToRawLongBits} regime as
 * {@code org.locationtech.proj4j.grids.GridShiftConcurrencyTest}.
 *
 * <p>The bitwise assertion is not decoration. A reader that kept a decode buffer or a
 * {@code ByteBuffer} position in an instance field would produce <em>finite, plausible</em> coordinates
 * when two threads interleave inside one block decode, and a tolerance-based assertion would pass
 * straight through such a torn value. Grid interpolation reads {@code float} nodes into {@code double}
 * arithmetic, so tearing lands in the low bits and only a raw-bits comparison sees it.
 *
 * <p>Three claims, each with its own hazard:
 * <ul>
 *   <li><strong>Concurrent first use.</strong> 36 threads racing to load the same never-before-loaded
 *       grid must produce exactly one parse and one shared instance, with no lock held across the read —
 *       that is {@code GridCache}'s contract, and a GeoTIFF is the heaviest thing to put through it
 *       because parsing inflates every strip.</li>
 *   <li><strong>Concurrent reads of one parsed grid.</strong> The grid is deeply immutable after
 *       construction, so many readers must be indistinguishable from each owning a copy.</li>
 *   <li><strong>The inverse shift.</strong> The only data-dependent loop on the grid path, and therefore
 *       the only place a shared mutable iterate could hide.</li>
 * </ul>
 */
public class GeoTiffConcurrencyTest {

    private static final int THREADS = Math.max(36, Runtime.getRuntime().availableProcessors() * 2);
    private static final int ITERATIONS = 200;
    private static final double D2R = Math.PI / 180.0;

    private static final double[][] HGRID_POINTS = {
            {4.5, 52.5}, {4.1, 52.1}, {6.9, 54.9}, {5.0, 53.0}, {4.75, 52.25},
    };

    private static final double[][] SUBGRID_POINTS = {
            {-115.5416667, 51.1666667}, {-80.5041667, 44.5458333},
            {-115.7, 51.6}, {-80.4, 44.9},
    };

    /** 36 threads racing to load one grid for the first time must all get the same instance. */
    @Test(timeout = 180_000)
    public void concurrentFirstLoadYieldsOneSharedInstance() throws Exception {
        final String name = "test_hgrid_tiled.tif";
        final CyclicBarrier barrier = new CyclicBarrier(THREADS);
        final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
        final Grid[] seen = new Grid[THREADS];
        Thread[] threads = new Thread[THREADS];
        for (int t = 0; t < THREADS; t++) {
            final int index = t;
            threads[t] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        barrier.await();
                        List<Grid> list = new ArrayList<Grid>();
                        Grid.mergeGridFile(name, list);
                        seen[index] = list.get(0);
                    } catch (Throwable e) {
                        failures.add(e);
                    }
                }
            }, "geotiff-load-" + t);
        }
        run(threads, failures);
        for (int t = 1; t < THREADS; t++) {
            assertSame("every thread must observe the one cached parse", seen[0], seen[t]);
        }
        assertEquals("gtiff", seen[0].getFormat());
    }

    /** Many readers of one parsed horizontal grid, forward and inverse, bit for bit. */
    @Test(timeout = 180_000)
    public void horizontalShiftsAreBitwiseIdenticalAcrossThreads() throws Exception {
        assertBitwiseStable("test_hgrid.tif", HGRID_POINTS, false);
        assertBitwiseStable("test_hgrid.tif", HGRID_POINTS, true);
        assertBitwiseStable("test_hgrid_tiled_separate.tif", HGRID_POINTS, false);
    }

    /**
     * The subgrid hierarchy under concurrency. {@code Grid.shift} reassigns its {@code grid} local while
     * descending, which is exactly the shape of code that would break if the tree were mutable.
     */
    @Test(timeout = 180_000)
    public void subgridDescentIsBitwiseIdenticalAcrossThreads() throws Exception {
        assertBitwiseStable("test_hgrid_with_subgrid.tif", SUBGRID_POINTS, false);
        assertBitwiseStable("test_hgrid_with_subgrid_no_grid_name.tif", SUBGRID_POINTS, false);
    }

    private static void assertBitwiseStable(final String name, final double[][] points,
                                            final boolean inverse) throws Exception {
        final List<Grid> grids = GeoTiffFixtures.horizontal(name);
        final long[][] expected = new long[points.length][2];
        for (int i = 0; i < points.length; i++) {
            double[] out = GeoTiffFixtures.shiftDegrees(grids, inverse, points[i][0], points[i][1]);
            assertTrue(name + " point " + i + " must be finite", Double.isFinite(out[0]));
            expected[i][0] = Double.doubleToRawLongBits(out[0]);
            expected[i][1] = Double.doubleToRawLongBits(out[1]);
        }
        final CyclicBarrier barrier = new CyclicBarrier(THREADS);
        final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
        Thread[] threads = new Thread[THREADS];
        for (int t = 0; t < THREADS; t++) {
            threads[t] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        barrier.await();
                        for (int n = 0; n < ITERATIONS; n++) {
                            for (int i = 0; i < points.length; i++) {
                                ProjCoordinate c = new ProjCoordinate(
                                        points[i][0] * D2R, points[i][1] * D2R);
                                Grid.shift(grids, inverse, c);
                                double lon = Math.toDegrees(c.x);
                                double lat = Math.toDegrees(c.y);
                                if (Double.doubleToRawLongBits(lon) != expected[i][0]
                                        || Double.doubleToRawLongBits(lat) != expected[i][1]) {
                                    throw new AssertionError(name + (inverse ? " inverse" : " forward")
                                            + " point " + i + " diverged: got (" + lon + ", " + lat
                                            + ") expected bits " + expected[i][0] + "/"
                                            + expected[i][1]);
                                }
                            }
                        }
                    } catch (Throwable e) {
                        failures.add(e);
                    }
                }
            }, "geotiff-shift-" + t);
        }
        run(threads, failures);
    }

    /**
     * The vertical path, including the subgrid descent that {@code valueAt} performs before
     * interpolating.
     */
    @Test(timeout = 180_000)
    public void verticalValuesAreBitwiseIdenticalAcrossThreads() throws Exception {
        final VerticalGrid grid = VerticalGrid.fromName("test_vgrid_with_subgrid.tif");
        final double[][] points = {{4.5, 52.5}, {5.5, 53.5}, {4.2, 52.2}, {6.0, 54.0}};
        final long[] expected = new long[points.length];
        for (int i = 0; i < points.length; i++) {
            expected[i] = Double.doubleToRawLongBits(
                    grid.valueAt(points[i][0] * D2R, points[i][1] * D2R, 1.0));
        }
        final CyclicBarrier barrier = new CyclicBarrier(THREADS);
        final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
        final AtomicInteger done = new AtomicInteger();
        Thread[] threads = new Thread[THREADS];
        for (int t = 0; t < THREADS; t++) {
            threads[t] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        barrier.await();
                        for (int n = 0; n < ITERATIONS; n++) {
                            for (int i = 0; i < points.length; i++) {
                                double v = grid.valueAt(points[i][0] * D2R, points[i][1] * D2R, 1.0);
                                if (Double.doubleToRawLongBits(v) != expected[i]) {
                                    throw new AssertionError("vertical point " + i + " diverged: got "
                                            + v);
                                }
                            }
                        }
                        done.incrementAndGet();
                    } catch (Throwable e) {
                        failures.add(e);
                    }
                }
            }, "geotiff-vshift-" + t);
        }
        run(threads, failures);
        assertEquals(THREADS, done.get());
    }

    private static void run(Thread[] threads, List<Throwable> failures) throws InterruptedException {
        for (int t = 0; t < threads.length; t++) {
            threads[t].start();
        }
        for (int t = 0; t < threads.length; t++) {
            threads[t].join(150_000);
            if (threads[t].isAlive()) {
                fail(threads[t].getName() + " did not finish; suspect a lock held across grid I/O");
            }
        }
        if (!failures.isEmpty()) {
            AssertionError e = new AssertionError(failures.size() + " thread(s) failed; first: "
                    + failures.get(0));
            e.initCause(failures.get(0));
            throw e;
        }
    }
}

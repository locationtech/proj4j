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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.datum.Grid;
import org.locationtech.proj4j.datum.GridCache;
import org.locationtech.proj4j.datum.VerticalGrid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Grid-bearing fixtures for the bitwise-identity concurrency regime.
 *
 * <p>{@code org.locationtech.proj4j.concurrent.SharedTransformConcurrencyTest} proves that one
 * {@code CoordinateTransform} can be shared across threads, asserting on
 * {@code Double.doubleToRawLongBits} rather than with a tolerance — because a projection keeping
 * per-call scratch state in instance fields produces <em>finite, plausible</em> coordinates when two
 * threads interleave, and a tolerance-based assertion passes straight through such a torn value. That
 * fixture list contains <strong>no grid-shift transform</strong>, so the grid path was outside its
 * reach. This class adds the missing fixtures in the same shape and with the same assertion, plus the
 * two grid-specific hazards it does not cover:
 *
 * <ul>
 *   <li>the shared parsed grid handed out by {@link GridCache} — deeply immutable after construction, so
 *       many threads reading one instance must be indistinguishable from each thread owning a copy;</li>
 *   <li>the <em>inverse</em> grid shift, whose iterative solver is the only data-dependent loop on the
 *       grid path and therefore the only place a shared mutable iterate could hide.</li>
 * </ul>
 *
 * <p>Grid shifts are also the strongest possible test of the bitwise claim, because the interpolation
 * reads {@code float} node values into {@code double} arithmetic: any torn read shows up in the low
 * bits, and only a raw-bits comparison can see it.
 */
public class GridShiftConcurrencyTest {

    private static final int ITERATIONS = 300;

    private static final CRSFactory CRS_FACTORY = new CRSFactory();
    private static final CoordinateTransformFactory CT_FACTORY = new CoordinateTransformFactory();

    /** A grid fixture: a shared grid list, a direction, points, and the single-threaded answer's bits. */
    private static final class GridFixture {
        final String name;
        final List<Grid> grids;
        final boolean inverse;
        final double[][] points;
        final long[][] expected;

        GridFixture(String name, List<Grid> grids, boolean inverse, double[][] points) {
            this.name = name;
            this.grids = grids;
            this.inverse = inverse;
            this.points = points;
            this.expected = new long[points.length][2];
            for (int i = 0; i < points.length; i++) {
                double[] out = GridReferenceValues.shiftDegrees(grids, inverse, points[i][0], points[i][1]);
                expected[i][0] = Double.doubleToRawLongBits(out[0]);
                expected[i][1] = Double.doubleToRawLongBits(out[1]);
                assertTrue(name + " fixture must produce a finite longitude at point " + i,
                        Double.isFinite(out[0]));
            }
        }
    }

    private static double[][] conusPoints() {
        return new double[][]{
                GridReferenceValues.SAN_FRANCISCO,
                GridReferenceValues.KANSAS,
                GridReferenceValues.CHICAGO,
                GridReferenceValues.BOSTON,
                {-104.99, 39.74},   // Denver
                {-95.37, 29.76},    // Houston
        };
    }

    private static double[][] canadaPoints() {
        return new double[][]{
                GridReferenceValues.NTV2_ONWINSOR,
                GridReferenceValues.NTV2_ALRAYMND,
                GridReferenceValues.NTV2_ALBANFF,
                GridReferenceValues.NTV2_CAWEST,
        };
    }

    private static List<GridFixture> gridFixtures() throws IOException {
        List<GridFixture> f = new ArrayList<GridFixture>();
        f.add(new GridFixture("conus forward", GridReferenceValues.singleton("conus"), false,
                conusPoints()));
        f.add(new GridFixture("conus inverse", GridReferenceValues.singleton("conus"), true,
                conusPoints()));
        f.add(new GridFixture("ntv1_can.dat forward",
                GridReferenceValues.singleton("ntv1_can.dat"), false,
                new double[][]{GridReferenceValues.CHICAGO, GridReferenceValues.BOSTON,
                        {-113.5, 53.5}, {-79.4, 43.7}}));
        f.add(new GridFixture("ntv2 multi-subgrid forward",
                GridReferenceValues.singleton("ntv2_0_downsampled.gsb"), false, canadaPoints()));
        f.add(new GridFixture("ntv2 multi-subgrid inverse",
                GridReferenceValues.singleton("ntv2_0_downsampled.gsb"), true, canadaPoints()));
        // The @-optional list, so the skip path is exercised concurrently too.
        f.add(new GridFixture("@missing,conus forward",
                Grid.fromNadGrids("@definitely_not_a_grid,conus"), false, conusPoints()));
        return Collections.unmodifiableList(f);
    }

    @Test(timeout = 180_000)
    public void sharedGridsGiveBitwiseIdenticalResultsAcrossManyThreads() throws Exception {
        final List<GridFixture> fixtures = gridFixtures();
        final int threadCount = Math.max(36, Runtime.getRuntime().availableProcessors() * 2);
        final CyclicBarrier start = new CyclicBarrier(threadCount);
        final List<String> problems = new CopyOnWriteArrayList<String>();

        List<Thread> threads = new ArrayList<Thread>(threadCount);
        for (int t = 0; t < threadCount; t++) {
            final int threadIndex = t;
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        for (int iter = 0; iter < ITERATIONS && problems.isEmpty(); iter++) {
                            for (GridFixture f : fixtures) {
                                for (int k = 0; k < f.points.length; k++) {
                                    // Stagger per thread so threads are never in lock-step.
                                    int i = (k + threadIndex) % f.points.length;
                                    double[] out = GridReferenceValues.shiftDegrees(
                                            f.grids, f.inverse, f.points[i][0], f.points[i][1]);
                                    if (Double.doubleToRawLongBits(out[0]) != f.expected[i][0]
                                            || Double.doubleToRawLongBits(out[1]) != f.expected[i][1]) {
                                        problems.add(f.name + " point " + i + " thread " + threadIndex
                                                + " iteration " + iter + ": expected ("
                                                + Double.longBitsToDouble(f.expected[i][0]) + ", "
                                                + Double.longBitsToDouble(f.expected[i][1])
                                                + ") but got (" + out[0] + ", " + out[1] + ")");
                                        return;
                                    }
                                }
                            }
                        }
                    } catch (Throwable e) {
                        problems.add("thread " + threadIndex + " threw " + e);
                    }
                }
            }, "grid-shift-" + t);
            thread.setDaemon(true);
            threads.add(thread);
        }
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        if (!problems.isEmpty()) {
            fail(problems.size() + " concurrency problem(s) sharing grids across " + threadCount
                    + " threads:\n  " + problems.get(0));
        }
    }

    /**
     * The same claim one level up: a single {@code CoordinateTransform} whose source datum carries a
     * grid, shared across threads. This is the shape a Spark job actually uses — one cached transform,
     * every executor thread calling it.
     */
    @Test(timeout = 180_000)
    public void oneSharedGridShiftTransformIsThreadSafe() throws Exception {
        CoordinateReferenceSystem src = CRS_FACTORY.createFromParameters("nad27+conus",
                "+proj=longlat +ellps=clrk66 +nadgrids=conus +no_defs");
        CoordinateReferenceSystem tgt = CRS_FACTORY.createFromParameters("nad83",
                "+proj=longlat +datum=NAD83 +no_defs");
        final CoordinateTransform transform = CT_FACTORY.createTransform(src, tgt);

        final double[][] points = conusPoints();
        final long[][] expected = new long[points.length][2];
        ProjCoordinate scratch = new ProjCoordinate();
        for (int i = 0; i < points.length; i++) {
            transform.transform(new ProjCoordinate(points[i][0], points[i][1]), scratch);
            expected[i][0] = Double.doubleToRawLongBits(scratch.x);
            expected[i][1] = Double.doubleToRawLongBits(scratch.y);
        }

        final int threadCount = Math.max(36, Runtime.getRuntime().availableProcessors() * 2);
        final CyclicBarrier start = new CyclicBarrier(threadCount);
        final List<String> problems = new CopyOnWriteArrayList<String>();
        List<Thread> threads = new ArrayList<Thread>(threadCount);
        for (int t = 0; t < threadCount; t++) {
            final int threadIndex = t;
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    ProjCoordinate in = new ProjCoordinate();
                    ProjCoordinate out = new ProjCoordinate();
                    try {
                        start.await();
                        for (int iter = 0; iter < ITERATIONS && problems.isEmpty(); iter++) {
                            for (int k = 0; k < points.length; k++) {
                                int i = (k + threadIndex) % points.length;
                                in.setValue(points[i][0], points[i][1]);
                                transform.transform(in, out);
                                if (Double.doubleToRawLongBits(out.x) != expected[i][0]
                                        || Double.doubleToRawLongBits(out.y) != expected[i][1]) {
                                    problems.add("point " + i + " thread " + threadIndex
                                            + " iteration " + iter + ": expected ("
                                            + Double.longBitsToDouble(expected[i][0]) + ", "
                                            + Double.longBitsToDouble(expected[i][1]) + ") but got ("
                                            + out.x + ", " + out.y + ")");
                                    return;
                                }
                            }
                        }
                    } catch (Throwable e) {
                        problems.add("thread " + threadIndex + " threw " + e);
                    }
                }
            }, "shared-gridshift-transform-" + t);
            thread.setDaemon(true);
            threads.add(thread);
        }
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        if (!problems.isEmpty()) {
            fail(problems.size() + " concurrency problem(s) sharing one grid-shift transform across "
                    + threadCount + " threads:\n  " + problems.get(0));
        }
    }

    /** The vertical grid path, same regime. */
    @Test(timeout = 120_000)
    public void sharedVerticalGridIsThreadSafe() throws Exception {
        GridCache.vertical().clear();
        final long missesBefore = GridCache.vertical().missCount();
        final VerticalGrid grid = VerticalGrid.fromName("egm96_15_downsampled.gtx");
        final double[][] points = GridReferenceValues.GTX_POINTS;
        final long[] expected = new long[points.length];
        for (int i = 0; i < points.length; i++) {
            expected[i] = Double.doubleToRawLongBits(
                    grid.valueAt(Math.toRadians(points[i][0]), Math.toRadians(points[i][1])));
        }

        final int threadCount = Math.max(36, Runtime.getRuntime().availableProcessors() * 2);
        final CyclicBarrier start = new CyclicBarrier(threadCount);
        final List<String> problems = new CopyOnWriteArrayList<String>();
        List<Thread> threads = new ArrayList<Thread>(threadCount);
        for (int t = 0; t < threadCount; t++) {
            final int threadIndex = t;
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        for (int iter = 0; iter < ITERATIONS && problems.isEmpty(); iter++) {
                            for (int k = 0; k < points.length; k++) {
                                int i = (k + threadIndex) % points.length;
                                double v = grid.valueAt(Math.toRadians(points[i][0]),
                                        Math.toRadians(points[i][1]));
                                if (Double.doubleToRawLongBits(v) != expected[i]) {
                                    problems.add("vertical point " + i + " thread " + threadIndex
                                            + ": expected " + Double.longBitsToDouble(expected[i])
                                            + " but got " + v);
                                    return;
                                }
                            }
                        }
                    } catch (Throwable e) {
                        problems.add("thread " + threadIndex + " threw " + e);
                    }
                }
            }, "shared-vgrid-" + t);
            thread.setDaemon(true);
            threads.add(thread);
        }
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertTrue(problems.toString(), problems.isEmpty());
        assertEquals("one parse", 1L, GridCache.vertical().missCount() - missesBefore);
    }
}

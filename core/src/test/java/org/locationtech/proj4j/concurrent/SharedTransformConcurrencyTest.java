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
package org.locationtech.proj4j.concurrent;

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Proves that a single {@link CoordinateTransform} instance may be shared across threads.
 * <p>
 * Assertions are made on {@link Double#doubleToRawLongBits(double)} rather than with a
 * tolerance: a projection that keeps per-call scratch state in instance fields produces
 * <em>finite, plausible</em> coordinates when two threads interleave, and a tolerance-based
 * assertion would pass straight through such a torn value. Bitwise identity is the only
 * assertion that actually detects the defect.
 * <p>
 * The fixture deliberately includes {@code +proj=cass} (Cassini-Soldner, reachable from 31
 * shipped EPSG codes) in both directions, because Cassini was the one projection in the tree
 * that wrote scratch state to instance fields inside {@code project()}/{@code projectInverse()}.
 */
public class SharedTransformConcurrencyTest {

    /** Iterations of the whole point list, per thread, per fixture. */
    private static final int ITERATIONS = 400;

    private static final CRSFactory CRS_FACTORY = new CRSFactory();
    private static final CoordinateTransformFactory CT_FACTORY = new CoordinateTransformFactory();

    private static final String WGS84 = "+proj=longlat +datum=WGS84 +no_defs";

    /** Cassini-Soldner over Johor, Malaysia -- the same geometry as EPSG:3377. */
    private static final String CASS_SYNTHETIC =
            "+proj=cass +ellps=GRS80 +lat_0=2.121679744444 +lon_0=103.427936236111 "
                    + "+x_0=-14810.562 +y_0=8758.32 +units=m +no_defs";

    /** A spherical Cassini, to cover the {@code spherical} branch of the projection. */
    private static final String CASS_SPHERICAL =
            "+proj=cass +R=6371000 +lat_0=2.5 +lon_0=103.5 +units=m +no_defs";

    private static final class Fixture {
        final String name;
        final CoordinateTransform transform;
        final double[][] points;
        /** Expected results as raw bits: [point][x, y, z]. */
        final long[][] expected;

        Fixture(String name, CoordinateReferenceSystem src, CoordinateReferenceSystem tgt, double[][] points) {
            this.name = name;
            this.transform = CT_FACTORY.createTransform(src, tgt);
            this.points = points;
            this.expected = new long[points.length][3];

            // Record the single-threaded answer once, up front, on this thread only.
            ProjCoordinate in = new ProjCoordinate();
            ProjCoordinate out = new ProjCoordinate();
            for (int i = 0; i < points.length; i++) {
                in.setValue(points[i][0], points[i][1]);
                transform.transform(in, out);
                expected[i][0] = Double.doubleToRawLongBits(out.x);
                expected[i][1] = Double.doubleToRawLongBits(out.y);
                expected[i][2] = Double.doubleToRawLongBits(out.z);
                assertTrue(name + " fixture must produce a finite easting at point " + i,
                        Double.isFinite(out.x));
                assertTrue(name + " fixture must produce a finite northing at point " + i,
                        Double.isFinite(out.y));
            }
        }
    }

    private static CoordinateReferenceSystem crs(String params) {
        return CRS_FACTORY.createFromParameters(null, params);
    }

    private static CoordinateReferenceSystem named(String name) {
        return CRS_FACTORY.createFromName(name);
    }

    private static List<Fixture> buildFixtures() {
        // Points spread over the Cassini domain (peninsular Malaysia) and over the
        // wider domains of the other projections, so that interleaved scratch state
        // between two threads yields a different number rather than the same one.
        double[][] malaysiaLonLat = {
                {103.00, 1.60}, {103.43, 2.12}, {104.10, 2.80}, {102.60, 1.20}, {104.50, 3.40},
        };
        double[][] malaysiaGrid = {
                {-60000.0, -50000.0}, {-14810.562, 8758.32}, {60000.0, 80000.0}, {20000.0, -30000.0},
        };
        double[][] worldLonLat = {
                {12.50, 41.90}, {8.55, 47.37}, {-73.99, 40.73}, {151.21, -33.87}, {2.35, 48.86},
        };
        double[][] europeLonLat = {
                {5.00, 45.00}, {9.19, 45.46}, {13.40, 52.52}, {-3.70, 40.42}, {19.04, 47.50},
        };
        double[][] polarLonLat = {
                {0.00, 75.00}, {45.00, 80.00}, {-120.00, 70.00}, {170.00, 82.00},
        };

        List<Fixture> fixtures = new ArrayList<Fixture>();

        // --- Cassini, forward and inverse, from real EPSG codes and from a synthetic CRS.
        fixtures.add(new Fixture("EPSG:4326 -> EPSG:3377 (cass)",
                named("EPSG:4326"), named("EPSG:3377"), malaysiaLonLat));
        fixtures.add(new Fixture("EPSG:3377 -> EPSG:4326 (cass inverse)",
                named("EPSG:3377"), named("EPSG:4326"), malaysiaGrid));
        fixtures.add(new Fixture("EPSG:3377 -> EPSG:3378 (cass both ways)",
                named("EPSG:3377"), named("EPSG:3378"), malaysiaGrid));
        fixtures.add(new Fixture("wgs84 -> synthetic cass",
                crs(WGS84), crs(CASS_SYNTHETIC), malaysiaLonLat));
        fixtures.add(new Fixture("synthetic cass -> wgs84",
                crs(CASS_SYNTHETIC), crs(WGS84), malaysiaGrid));
        fixtures.add(new Fixture("wgs84 -> spherical cass",
                crs(WGS84), crs(CASS_SPHERICAL), malaysiaLonLat));
        fixtures.add(new Fixture("spherical cass -> wgs84",
                crs(CASS_SPHERICAL), crs(WGS84), malaysiaGrid));

        // --- A few unrelated families, so the test also guards against regressions there.
        fixtures.add(new Fixture("wgs84 -> utm 33N",
                crs(WGS84), crs("+proj=utm +zone=33 +datum=WGS84 +units=m +no_defs"), europeLonLat));
        fixtures.add(new Fixture("wgs84 -> lcc",
                crs(WGS84),
                crs("+proj=lcc +lat_1=45 +lat_2=50 +lat_0=47 +lon_0=10 +ellps=GRS80 +units=m +no_defs"),
                europeLonLat));
        fixtures.add(new Fixture("wgs84 -> merc",
                crs(WGS84), crs("+proj=merc +lon_0=0 +ellps=WGS84 +units=m +no_defs"), worldLonLat));
        fixtures.add(new Fixture("wgs84 -> stere",
                crs(WGS84),
                crs("+proj=stere +lat_0=90 +lat_ts=70 +lon_0=0 +ellps=WGS84 +units=m +no_defs"),
                polarLonLat));
        fixtures.add(new Fixture("EPSG:4326 -> EPSG:3068 (cass, Berlin, +towgs84)",
                named("EPSG:4326"), named("EPSG:3068"),
                new double[][]{{13.40, 52.52}, {13.63, 52.42}, {13.10, 52.30}, {13.90, 52.70}}));

        return Collections.unmodifiableList(fixtures);
    }

    @Test(timeout = 120_000)
    public void sharedTransformIsThreadSafe() throws Exception {
        final List<Fixture> fixtures = buildFixtures();
        final int threadCount = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);

        final CyclicBarrier start = new CyclicBarrier(threadCount);
        final List<String> problems = new CopyOnWriteArrayList<String>();

        List<Thread> threads = new ArrayList<Thread>(threadCount);
        for (int t = 0; t < threadCount; t++) {
            final int threadIndex = t;
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    // Every thread owns its own coordinate objects; only the transforms are shared.
                    ProjCoordinate in = new ProjCoordinate();
                    ProjCoordinate out = new ProjCoordinate();
                    try {
                        start.await();
                        for (int iter = 0; iter < ITERATIONS; iter++) {
                            for (Fixture f : fixtures) {
                                // Stagger the starting point per thread so that threads are
                                // never in lock-step on the same input.
                                for (int k = 0; k < f.points.length; k++) {
                                    int i = (k + threadIndex) % f.points.length;
                                    in.setValue(f.points[i][0], f.points[i][1]);
                                    f.transform.transform(in, out);
                                    checkBits(problems, f, i, iter, threadIndex, out);
                                    if (!problems.isEmpty()) {
                                        return;
                                    }
                                }
                            }
                        }
                    } catch (Throwable e) {
                        problems.add("thread " + threadIndex + " threw " + e);
                    }
                }
            }, "shared-transform-" + t);
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
            StringBuilder sb = new StringBuilder();
            sb.append(problems.size()).append(" concurrency problem(s) sharing one CoordinateTransform");
            sb.append(" across ").append(threadCount).append(" threads:");
            int shown = 0;
            for (String p : problems) {
                sb.append("\n  ").append(p);
                if (++shown == 10) {
                    sb.append("\n  ...");
                    break;
                }
            }
            fail(sb.toString());
        }
    }

    private static void checkBits(List<String> problems, Fixture f, int i, int iter,
                                  int threadIndex, ProjCoordinate out) {
        long[] want = f.expected[i];
        if (Double.doubleToRawLongBits(out.x) != want[0]
                || Double.doubleToRawLongBits(out.y) != want[1]
                || Double.doubleToRawLongBits(out.z) != want[2]) {
            problems.add(f.name + " point " + i + " (" + f.points[i][0] + ", " + f.points[i][1] + ")"
                    + " thread " + threadIndex + " iteration " + iter
                    + ": expected (" + Double.longBitsToDouble(want[0]) + ", "
                    + Double.longBitsToDouble(want[1]) + ", " + Double.longBitsToDouble(want[2]) + ")"
                    + " but got (" + out.x + ", " + out.y + ", " + out.z + ")");
        }
    }

    /**
     * {@code BasicCoordinateTransform.transform} copies {@code src} into {@code tgt} first and
     * then uses {@code tgt} as both the source and the destination of every subsequent step, so
     * a caller passing the same object twice must get the same answer as a caller passing two.
     * A projection that keeps scratch state in instance fields, or one that reads back from the
     * destination object, can break exactly here.
     */
    @Test(timeout = 120_000)
    public void aliasedSourceAndTargetMatchDistinctSourceAndTarget() {
        for (Fixture f : buildFixtures()) {
            for (int i = 0; i < f.points.length; i++) {
                ProjCoordinate aliased = new ProjCoordinate(f.points[i][0], f.points[i][1]);
                f.transform.transform(aliased, aliased);

                ProjCoordinate src = new ProjCoordinate(f.points[i][0], f.points[i][1]);
                ProjCoordinate dst = new ProjCoordinate();
                f.transform.transform(src, dst);

                String where = f.name + " point " + i;
                assertEquals(where + " x", Double.doubleToRawLongBits(dst.x),
                        Double.doubleToRawLongBits(aliased.x));
                assertEquals(where + " y", Double.doubleToRawLongBits(dst.y),
                        Double.doubleToRawLongBits(aliased.y));
                assertEquals(where + " z", Double.doubleToRawLongBits(dst.z),
                        Double.doubleToRawLongBits(aliased.z));
            }
        }
    }
}

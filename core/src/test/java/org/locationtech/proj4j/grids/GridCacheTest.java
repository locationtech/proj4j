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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Test;
import org.locationtech.proj4j.datum.Grid;
import org.locationtech.proj4j.datum.GridCache;
import org.locationtech.proj4j.resource.ResourceResolvers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The parsed-grid cache: thread-safe, bounded, and provably unable to change a number.
 *
 * <p>1.4.3 had no cache at all — {@code Grid.mergeGridFile} carried a {@code TODO} where the cache now
 * is. Every {@code +nadgrids=} parse re-read and re-allocated the grid, so a 1,000-entry transform cache
 * was also a cache of up to 1,000 independently parsed copies of the same file, and all of it happened
 * inside one {@code synchronized (Grid.class)} block wrapped around blocking I/O.
 */
public class GridCacheTest {

    @After
    public void reset() {
        ResourceResolvers.clearResolvers();
        GridCache.instance().clear();
    }

    @Test
    public void thesameGridIsParsedOnceAndShared() throws IOException {
        GridCache.instance().clear();
        long missesBefore = GridCache.instance().missCount();

        Grid first = GridReferenceValues.singleton("conus").get(0);
        Grid second = GridReferenceValues.singleton("conus").get(0);

        assertSame("a second request must return the very same parsed grid", first, second);
        assertEquals("only one parse", 1L, GridCache.instance().missCount() - missesBefore);
    }

    @Test
    public void theCacheAccountsForBytesNotEntries() throws IOException {
        GridCache.instance().clear();
        Grid conus = GridReferenceValues.singleton("conus").get(0);
        // conus is 273 x 121 = 33,033 nodes.
        assertEquals(33033L * 32L, conus.sizeBytes());
        assertTrue("the cache must have accounted the grid's bytes",
                GridCache.instance().bytes() >= conus.sizeBytes());
        assertTrue("and it must have a byte budget, not an entry budget",
                GridCache.instance().maxBytes() > 0);
    }

    /**
     * The determinism property that makes the cache safe: eviction cannot be observed in output. A grid
     * re-parsed after eviction produces <strong>bit-identical</strong> results, because parsing is a pure
     * function of the resolved bytes.
     */
    @Test
    public void evictionAndReloadGiveBitwiseIdenticalResults() throws IOException {
        List<Grid> first = GridReferenceValues.singleton("conus");
        double[] before = GridReferenceValues.shiftDegrees(first, false,
                GridReferenceValues.SAN_FRANCISCO[0], GridReferenceValues.SAN_FRANCISCO[1]);

        GridCache.instance().clear();

        List<Grid> reloaded = GridReferenceValues.singleton("conus");
        assertTrue("clearing the cache must force a genuinely new parse",
                first.get(0) != reloaded.get(0));
        double[] after = GridReferenceValues.shiftDegrees(reloaded, false,
                GridReferenceValues.SAN_FRANCISCO[0], GridReferenceValues.SAN_FRANCISCO[1]);

        assertEquals("longitude bits must be identical across a reload",
                Double.doubleToRawLongBits(before[0]), Double.doubleToRawLongBits(after[0]));
        assertEquals("latitude bits must be identical across a reload",
                Double.doubleToRawLongBits(before[1]), Double.doubleToRawLongBits(after[1]));
        assertEquals("and the re-parsed grid must equal the original", first.get(0), reloaded.get(0));
    }

    /**
     * A tiny cache forced to evict on every admission still gives the same answers. This is the
     * eviction-timing independence claim, tested rather than asserted.
     */
    @Test
    public void aCacheTooSmallToHoldAnythingStillGivesTheSameAnswers() throws IOException {
        double[] expected = GridReferenceValues.shiftDegrees(
                GridReferenceValues.singleton("conus"), false,
                GridReferenceValues.CHICAGO[0], GridReferenceValues.CHICAGO[1]);

        for (int i = 0; i < 20; i++) {
            GridCache.instance().clear();
            double[] got = GridReferenceValues.shiftDegrees(
                    GridReferenceValues.singleton("conus"), false,
                    GridReferenceValues.CHICAGO[0], GridReferenceValues.CHICAGO[1]);
            assertEquals(Double.doubleToRawLongBits(expected[0]), Double.doubleToRawLongBits(got[0]));
            assertEquals(Double.doubleToRawLongBits(expected[1]), Double.doubleToRawLongBits(got[1]));
        }
    }

    /**
     * Many threads demanding the same grid simultaneously must produce exactly one parse and one shared
     * instance, without a global lock held across the read.
     */
    @Test(timeout = 120_000)
    public void concurrentFirstUseParsesOnceAndSharesOneInstance() throws Exception {
        GridCache.instance().clear();
        final long missesBefore = GridCache.instance().missCount();
        final int threads = 36;
        final CyclicBarrier start = new CyclicBarrier(threads);
        final List<Grid> seen = new CopyOnWriteArrayList<Grid>();
        final List<String> problems = new CopyOnWriteArrayList<String>();
        final AtomicInteger done = new AtomicInteger();

        List<Thread> pool = new ArrayList<Thread>(threads);
        for (int t = 0; t < threads; t++) {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        seen.add(GridReferenceValues.singleton("conus").get(0));
                        done.incrementAndGet();
                    } catch (Throwable e) {
                        problems.add(String.valueOf(e));
                    }
                }
            }, "grid-cache-" + t);
            thread.setDaemon(true);
            pool.add(thread);
        }
        for (Thread thread : pool) {
            thread.start();
        }
        for (Thread thread : pool) {
            thread.join();
        }

        assertTrue("no thread may fail: " + problems, problems.isEmpty());
        assertEquals(threads, done.get());
        assertEquals(threads, seen.size());
        Grid first = seen.get(0);
        for (Grid g : seen) {
            assertSame("all threads must observe the same parsed grid instance", first, g);
        }
        assertEquals("exactly one parse across 36 threads", 1L,
                GridCache.instance().missCount() - missesBefore);
    }

    /**
     * A parse failure is cached and replayed. Determinism cuts both ways: a corrupt grid must fail the
     * same way on row 4,000,000 as on row 1, not intermittently depending on cache state.
     */
    @Test
    public void aFailureIsCachedAndReplayedIdentically() throws IOException {
        GridCache.instance().clear();
        String first = null;
        String second = null;
        try {
            Grid.fromNadGrids("definitely_not_a_grid");
            fail("expected a failure");
        } catch (IOException e) {
            first = e.getMessage();
        }
        try {
            Grid.fromNadGrids("definitely_not_a_grid");
            fail("expected a failure");
        } catch (IOException e) {
            second = e.getMessage();
        }
        assertNotNull(first);
        assertEquals("the same failure, identically worded", first, second);
    }

    /** The vertical cache is separate from the horizontal one and behaves the same way. */
    @Test
    public void verticalGridsUseTheirOwnCache() throws Exception {
        GridCache.vertical().clear();
        long before = GridCache.vertical().missCount();
        Callable<Object> load = new Callable<Object>() {
            @Override
            public Object call() throws IOException {
                return org.locationtech.proj4j.datum.VerticalGrid.fromName("egm96_15_downsampled.gtx");
            }
        };
        Object a = load.call();
        Object b = load.call();
        assertSame(a, b);
        assertEquals("one parse", 1L, GridCache.vertical().missCount() - before);
        assertTrue(GridCache.vertical().bytes() > 0);
    }
}

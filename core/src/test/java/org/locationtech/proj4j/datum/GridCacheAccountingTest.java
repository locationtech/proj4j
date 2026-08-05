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

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The three ways {@code GridCache}'s stated bound was not the real bound.
 *
 * <h2>1. A failed load was retained forever and counted as nothing</h2>
 *
 * <p>{@code get()} put a {@code FutureTask} into {@code entries} <em>before</em> running it, and
 * every exception path returned or threw without reaching {@code admit}. The task therefore stayed
 * in the entry map with no LRU record at all: not counted in {@code bytes()}, and unreachable by
 * eviction, which only ever removes keys the LRU knows. The key is
 * {@code resolverName + ' ' + gridName} and the grid name is a {@code +nadgrids=} token, so a job
 * feeding fresh bad names retained one entry per name for the life of the JVM inside a cache
 * documented as bounded by bytes.
 *
 * <h2>2. Two caches, one documented budget, taken twice</h2>
 *
 * <p>{@code instance()} and {@code vertical()} were each constructed with
 * {@code configuredMaxBytes()} in full, so {@code -Dproj4j.grids.cacheBytes=64m} bought a 128 MiB
 * ceiling.
 *
 * <h2>3. An entry larger than the whole budget was admitted and exempted from eviction</h2>
 *
 * <p>"Never evict what was just admitted" is right for the ordinary case, but with an entry bigger
 * than the budget it meant the cache sat permanently over its ceiling with no admission able to
 * bring it back down below.
 *
 * <h2>The controls</h2>
 *
 * <p>Each test measures a specific counter and is paired with the opposite arm in the same method —
 * a success next to the failure, a small grid next to the oversized one — so a counter wired to
 * nothing, or a cache that has simply stopped caching, fails rather than passes. A cache that
 * retained nothing at all would satisfy every bound here and be useless, which is why
 * {@link #anOrdinaryEntryIsStillRetainedAndShared()} runs.
 */
public class GridCacheAccountingTest {

    /** A grid-shaped payload the cache can account for, with a size the test chooses. */
    private static final class Sized implements GridCache.Sized {
        private final long bytes;

        Sized(long bytes) {
            this.bytes = bytes;
        }

        @Override
        public long sizeBytes() {
            return bytes;
        }
    }

    private static GridCache.Loader<Sized> ok(final long bytes) {
        return new GridCache.Loader<Sized>() {
            @Override
            public Sized load() {
                return new Sized(bytes);
            }
        };
    }

    private static GridCache.Loader<Sized> boom(final AtomicInteger calls) {
        return new GridCache.Loader<Sized>() {
            @Override
            public Sized load() throws IOException {
                calls.incrementAndGet();
                throw new IOException("this grid is corrupt");
            }
        };
    }

    // --- 1. failures are counted, and evictable ------------------------------------------------

    @Test
    public void aFailedLoadIsChargedAndCanBeEvicted() throws IOException {
        GridCache<Sized> cache = new GridCache<Sized>(10 * GridCache.FAILURE_WEIGHT_BYTES);
        AtomicInteger calls = new AtomicInteger();

        for (int i = 0; i < 6; i++) {
            try {
                cache.get("r", "bad-" + i, boom(calls));
                fail("the loader throws");
            } catch (IOException expected) {
                assertEquals("this grid is corrupt", expected.getMessage());
            }
        }
        assertEquals("six distinct bad names, six loads", 6, calls.get());
        assertEquals("every failure must be accounted", 6 * GridCache.FAILURE_WEIGHT_BYTES,
                cache.bytes());
        assertEquals(6, cache.size());

        // Past the budget, failures evict like anything else. Before the fix this loop grew the
        // entry map without limit and bytes() stayed at zero throughout.
        for (int i = 6; i < 40; i++) {
            try {
                cache.get("r", "bad-" + i, boom(calls));
                fail("the loader throws");
            } catch (IOException expected) {
                // expected
            }
        }
        assertTrue("34 more failures must not push the cache past its budget; bytes=" + cache.bytes()
                + " max=" + cache.maxBytes(), cache.bytes() <= cache.maxBytes());
        assertTrue("and the entry map must have been trimmed with the LRU, not left behind; size="
                + cache.size(), cache.size() <= 10);
        assertTrue("evictions must have been counted", cache.evictionCount() > 0);
    }

    /**
     * Failures are still <em>cached</em>. That is deliberate and is the determinism property: a
     * corrupt grid must fail identically on row 4,000,000 and on row 1. Charging them must not have
     * turned them into re-loads.
     */
    @Test
    public void aFailureIsStillReplayedWithoutReloading() throws IOException {
        GridCache<Sized> cache = new GridCache<Sized>(1024 * 1024);
        AtomicInteger calls = new AtomicInteger();
        String first = null;
        for (int i = 0; i < 20; i++) {
            try {
                cache.get("r", "bad", boom(calls));
                fail("the loader throws");
            } catch (IOException expected) {
                if (first == null) {
                    first = expected.getMessage();
                }
                assertEquals("identically worded every time", first, expected.getMessage());
            }
        }
        assertEquals("20 requests, exactly one load", 1, calls.get());
    }

    // --- 2. one shared budget ------------------------------------------------------------------

    /**
     * The two <em>singletons</em> — not two caches a test built over a budget it chose — must draw
     * on one budget.
     *
     * <p>The obvious assertions here are vacuous and were written that way first: with two separate
     * budgets of equal size, {@code instance().maxBytes() == vertical().maxBytes()} still holds, and
     * so does {@code instance().sharedBytes() == vertical().sharedBytes()} while both are empty. A
     * mutation restoring {@code new GridCache<>(configuredMaxBytes())} on each line passed all of
     * them. What discriminates is loading a grid into <em>one</em> cache and requiring the
     * <em>other</em> to see it against the shared total.
     */
    @Test
    public void theHorizontalAndVerticalCachesShareOneBudget() throws IOException {
        GridCache.instance().clear();
        GridCache.vertical().clear();

        assertEquals("both caches must report the same ceiling",
                GridCache.instance().maxBytes(), GridCache.vertical().maxBytes());
        assertEquals("nothing is cached yet", 0L, GridCache.vertical().sharedBytes());

        java.util.List<Grid> conus = Grid.fromNadGrids("conus");
        long loaded = conus.get(0).sizeBytes();
        assertTrue("conus must have a non-zero accounted size for this to measure anything",
                loaded > 0);

        assertEquals("the horizontal cache must account for it", loaded,
                GridCache.instance().bytes());
        assertEquals("the vertical cache holds nothing of its own", 0L,
                GridCache.vertical().bytes());
        assertEquals("but it must see the horizontal grid against the SHARED budget -- this is the"
                + " assertion that fails when the two caches each take the budget in full", loaded,
                GridCache.vertical().sharedBytes());
        assertEquals("and both sides must report the same shared total",
                GridCache.instance().sharedBytes(), GridCache.vertical().sharedBytes());
        assertTrue(GridCache.instance().sharedBytes() <= GridCache.instance().maxBytes());

        GridCache.instance().clear();
        assertEquals("clearing one cache must release its share of the shared budget", 0L,
                GridCache.vertical().sharedBytes());
    }

    /**
     * Two caches on one budget: filling the first must reduce what the second can hold, and the sum
     * must stay under the ceiling. Before the fix each instance had its own {@code currentBytes} and
     * the sum could reach twice the configured value.
     */
    @Test
    public void fillingOneCacheEvictsFromTheOther() throws IOException {
        GridCache.Budget shared = new GridCache.Budget(10_000L);
        GridCache<Sized> a = GridCache.forBudget(shared);
        GridCache<Sized> b = GridCache.forBudget(shared);

        for (int i = 0; i < 5; i++) {
            assertNotNull(b.get("r", "b-" + i, ok(1000L)));
        }
        assertEquals(5000L, shared.currentBytes());
        assertEquals(5, b.size());

        for (int i = 0; i < 20; i++) {
            assertNotNull(a.get("r", "a-" + i, ok(1000L)));
        }

        assertTrue("the shared total must honour the one budget; got " + shared.currentBytes(),
                shared.currentBytes() <= 10_000L);
        assertTrue("filling a must have evicted from b, not merely from a; b still holds "
                + b.size(), b.size() < 5);
        assertEquals("and the two must agree on the total", shared.currentBytes(),
                a.sharedBytes());
    }

    // --- 3. an entry larger than the whole budget is served but not retained -------------------

    @Test
    public void anEntryLargerThanTheBudgetIsServedButNotRetained() throws IOException {
        GridCache<Sized> cache = new GridCache<Sized>(1000L);

        Sized huge = cache.get("r", "huge", ok(5000L));
        assertNotNull("the caller still gets its grid", huge);
        assertEquals(5000L, huge.sizeBytes());
        assertEquals("nothing may be retained for it", 0, cache.size());
        assertEquals("and it must not be counted", 0L, cache.bytes());
        assertTrue("the cache must not be parked over its ceiling",
                cache.bytes() <= cache.maxBytes());

        // CONTROL: an entry that fits IS retained, so "0" above is the oversize rule and not the
        // cache having stopped working.
        Sized small = cache.get("r", "small", ok(400L));
        assertNotNull(small);
        assertEquals(1, cache.size());
        assertEquals(400L, cache.bytes());
        assertSame("and it is shared on the next request", small,
                cache.get("r", "small", ok(400L)));
    }

    // --- the accept side -----------------------------------------------------------------------

    @Test
    public void anOrdinaryEntryIsStillRetainedAndShared() throws IOException {
        GridCache<Sized> cache = new GridCache<Sized>(1024 * 1024);
        final AtomicInteger loads = new AtomicInteger();
        GridCache.Loader<Sized> counting = new GridCache.Loader<Sized>() {
            @Override
            public Sized load() {
                loads.incrementAndGet();
                return new Sized(2048L);
            }
        };

        long hitsBefore = cache.hitCount();
        Sized first = cache.get("r", "good", counting);
        for (int i = 0; i < 50; i++) {
            assertSame("every later request must return the same instance", first,
                    cache.get("r", "good", counting));
        }
        assertEquals("exactly one load across 51 requests", 1, loads.get());
        assertEquals(2048L, cache.bytes());
        assertEquals("50 of the 51 requests were hits", 50L, cache.hitCount() - hitsBefore);
        assertEquals(1L, cache.missCount());
    }

    /**
     * Interrupting a <em>waiter</em> must not discard the load it was waiting on.
     *
     * <h4>This test replaced one that could not fail, and the replacement changed the code</h4>
     *
     * <p>The first version simply set the calling thread's interrupt flag and called {@code get}.
     * That never reaches the {@code InterruptedException} branch at all: the owning thread runs the
     * {@code FutureTask} synchronously, so its {@code get()} finds the task already done and returns
     * without blocking. The test passed with the branch written either way — a mutation flipping it
     * went undetected — which is what exposed that the branch had been written <em>wrongly</em>:
     * it removed the cache entry, throwing away a load that was about to succeed and leaving the
     * owner to admit a key no longer present in the entry map, i.e. a byte charge against the budget
     * with nothing behind it.
     *
     * <p>So this drives the branch for real. A loader blocks on a latch; the owner is stuck inside
     * it; a second thread blocks in {@code task.get()} and is interrupted there. The load then
     * completes, and the assertions are that it was <em>not</em> discarded: one load, one cached
     * entry, correct accounting.
     */
    @Test(timeout = 30_000)
    public void interruptingAWaiterDoesNotDiscardTheLoad() throws Exception {
        final GridCache<Sized> cache = new GridCache<Sized>(1024 * 1024);
        final AtomicInteger loads = new AtomicInteger();
        final java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch loading =
                new java.util.concurrent.CountDownLatch(1);
        final GridCache.Loader<Sized> blocking = new GridCache.Loader<Sized>() {
            @Override
            public Sized load() throws IOException {
                loads.incrementAndGet();
                loading.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    throw new IOException("owner interrupted", e);
                }
                return new Sized(64L);
            }
        };

        final java.util.concurrent.atomic.AtomicReference<Object> ownerResult =
                new java.util.concurrent.atomic.AtomicReference<Object>();
        Thread owner = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ownerResult.set(cache.get("r", "blocked", blocking));
                } catch (Throwable t) {
                    ownerResult.set(t);
                }
            }
        }, "grid-cache-owner");
        owner.setDaemon(true);
        owner.start();
        assertTrue("the owner must actually be inside the loader", loading.await(20, SECONDS));

        final java.util.concurrent.atomic.AtomicReference<Object> waiterResult =
                new java.util.concurrent.atomic.AtomicReference<Object>();
        final java.util.concurrent.CountDownLatch waiting =
                new java.util.concurrent.CountDownLatch(1);
        Thread waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                waiting.countDown();
                try {
                    waiterResult.set(cache.get("r", "blocked", blocking));
                } catch (Throwable t) {
                    waiterResult.set(t);
                }
            }
        }, "grid-cache-waiter");
        waiter.setDaemon(true);
        waiter.start();
        assertTrue(waiting.await(20, SECONDS));
        // Give the waiter time to reach task.get() and block there, then interrupt it there.
        Thread.sleep(200);
        waiter.interrupt();
        waiter.join(20_000);

        assertTrue("the waiter must have been interrupted inside get(); got " + waiterResult.get(),
                waiterResult.get() instanceof IOException
                        && ((IOException) waiterResult.get()).getCause()
                                instanceof InterruptedException);

        release.countDown();
        owner.join(20_000);
        assertTrue("the owner's load must have succeeded", ownerResult.get() instanceof Sized);

        assertEquals("exactly one load; the waiter's interrupt must not have caused a second", 1,
                loads.get());
        assertEquals("the successfully loaded grid must still be cached", 1, cache.size());
        assertEquals("and accounted -- an entry admitted after its key was removed would leave a "
                + "charge with nothing behind it", 64L, cache.bytes());
        assertSame("a later caller gets the very grid the owner loaded", ownerResult.get(),
                cache.get("r", "blocked", blocking));
        assertEquals(1, loads.get());
    }

    /** A loader returning null must not install a free, unevictable entry. */
    @Test
    public void aNullLoadIsChargedRatherThanFree() throws IOException {
        GridCache<Sized> cache = new GridCache<Sized>(1024 * 1024);
        Sized nothing = cache.get("r", "null", new GridCache.Loader<Sized>() {
            @Override
            public Sized load() {
                return null;
            }
        });
        assertEquals(null, nothing);
        assertEquals("a null must be charged, not admitted at zero bytes",
                GridCache.FAILURE_WEIGHT_BYTES, cache.bytes());
    }
}

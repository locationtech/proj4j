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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A thread-safe, byte-bounded cache of <em>parsed</em> grids.
 *
 * <p>Proj4J 1.4.3 had none at all — {@code Grid.mergeGridFile} carried a {@code TODO} where this
 * class now is, and every {@code +nadgrids=} parse re-read and re-allocated the whole grid. The
 * shipped {@code ntv1_can.dat} expands to 393&times;177 = 69,561 nodes; a 1,000-entry transform cache
 * was therefore also a cache of up to 1,000 independently parsed copies of the same grid. Worse, all
 * of {@code fromNadGrids} ran inside a single {@code synchronized (Grid.class)} block wrapped around
 * blocking I/O, so the whole JVM serialised on one lock to redo work it had already done.
 *
 * <h2>Determinism</h2>
 * <p>This cache <strong>cannot</strong> change a numeric answer. Two properties give that:
 * <ul>
 *   <li>A parsed {@link Grid} is <em>deeply immutable after construction</em>. Nothing hands out a
 *       mutable view and no transform mutates one, so sharing one instance across threads is
 *       observationally identical to each thread owning a copy.</li>
 *   <li>Loading is a pure function of the resolved bytes. An evicted grid re-parsed later produces
 *       bit-identical {@code float} node values, hence bit-identical interpolation. Cache state and
 *       eviction timing are therefore not observable in output — which is what lets a
 *       bitwise-identity concurrency test be meaningful rather than accidental.</li>
 * </ul>
 * Failures are cached too, and for the same reason: a corrupt grid must fail the same way on row
 * 4,000,000 as on row 1.
 *
 * <h2>Bounded by bytes, not by entries</h2>
 * <p>Grids range from a 624-byte test fixture to an 80 MB geoid, so an entry count is not a bound on
 * anything. The budget is bytes of parsed node data, default 64 MiB, settable with
 * {@code -Dproj4j.grids.cacheBytes}. Eviction is least-recently-used. Entries are held
 * <strong>strongly</strong>: a soft or weak reference makes eviction depend on GC timing, which
 * reintroduces exactly the run-to-run variability being removed here (it would still be numerically
 * deterministic, but it would make the cache's <em>behaviour</em> untestable).
 *
 * <h2>One budget, shared, and it counts failures</h2>
 * <p>Three holes made the stated bound not the real one, and all three are closed here.
 * <ul>
 *   <li><strong>The budget was per-instance and there are two instances.</strong> The horizontal and
 *       vertical caches each took {@code configuredMaxBytes()} in full, so a 64 MiB setting bought a
 *       128 MiB ceiling. They now share one {@link Budget}, so the property means what it says. It
 *       is shared rather than halved because halving would cut a horizontal-only workload's cache in
 *       two to bound a vertical one it never populates.</li>
 *   <li><strong>A failed load was retained forever and counted as nothing.</strong> Every exception
 *       path returned without reaching {@code admit}, leaving the {@link FutureTask} in the entry
 *       map with no LRU record — so it could never be evicted and never appeared in
 *       {@link #bytes()}. Since the key contains a {@code +nadgrids=} token, that is unbounded
 *       retention driven by untrusted input. Failures are still cached, for the determinism reason
 *       below, but they are now charged {@value #FAILURE_WEIGHT_BYTES} bytes and evict like anything
 *       else.</li>
 *   <li><strong>A grid larger than the whole budget was admitted and then exempted from
 *       eviction.</strong> "Never evict what was just admitted" is right for the ordinary case and
 *       is kept, but combined with an entry that alone exceeds the budget it meant the cache sat
 *       permanently over its ceiling. Such a grid is now served to its caller and <em>not
 *       retained</em> — the same decision {@code InitFileCache} already makes for an oversized
 *       dictionary — so the exemption can only ever defer a trim by one admission.</li>
 * </ul>
 * <p>An interrupted or cancelled load is the one failure that is <em>not</em> cached: it is a
 * property of the calling thread, not of the file, and caching it would let one thread's interrupt
 * poison a grid for every other thread for the life of the JVM.
 *
 * <h2>No lock is held across I/O</h2>
 * <p>Loading uses a {@link FutureTask} published with {@code putIfAbsent}: concurrent requests for the
 * same grid produce exactly one parse, and the loser waits on the {@code Future} rather than on a
 * global monitor. The LRU bookkeeping is guarded by a separate short critical section that touches
 * only a {@link LinkedHashMap} of sizes, never the file.
 */
public final class GridCache<T extends GridCache.Sized> {

    /** Anything the cache can account for. Implemented by {@link Grid} and {@link VerticalGrid}. */
    public interface Sized {
        /** Accounted heap cost of the parsed node data. */
        long sizeBytes();
    }

    /** Loads a grid. Separate from the cache so the cache does no I/O of its own. */
    public interface Loader<T> {
        T load() throws IOException;
    }

    private static final String SIZE_PROPERTY = "proj4j.grids.cacheBytes";
    private static final long DEFAULT_MAX_BYTES = 64L * 1024L * 1024L;

    /**
     * What a cached failure is charged.
     *
     * <p>A failure retains a key, a completed {@link FutureTask} holding a throwable with its stack,
     * and two map nodes — a few hundred bytes in practice. Charging 1 KiB over-states it
     * deliberately: under-charging a miss is exactly how a "bounded" cache comes to retain hundreds
     * of thousands of them, and over-charging only makes the bound conservative. At this weight a
     * 64 MiB budget holds at most ~65,536 cached failures, which is a real bound on a real number.
     * The same constant and the same reasoning as {@code InitFileCache.MISS_WEIGHT_BYTES}.
     */
    static final long FAILURE_WEIGHT_BYTES = 1024L;

    /** The one budget both caches draw on. See the class javadoc. */
    private static final Budget BUDGET = new Budget(configuredMaxBytes());

    private static final GridCache<Grid> HORIZONTAL = new GridCache<Grid>(BUDGET);
    private static final GridCache<VerticalGrid> VERTICAL = new GridCache<VerticalGrid>(BUDGET);

    private final Budget budget;

    /** key -> the single in-flight or completed load for that key. */
    private final ConcurrentMap<String, FutureTask<T>> entries =
            new ConcurrentHashMap<String, FutureTask<T>>();

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();

    GridCache(long maxBytes) {
        this(new Budget(maxBytes));
    }

    private GridCache(Budget budget) {
        this.budget = budget;
    }

    /**
     * A cache drawing on an existing budget. Exists so a test can build two caches over one budget
     * and observe that they really do share it — the property that makes
     * {@code proj4j.grids.cacheBytes} a bound on the process rather than on an instance.
     */
    static <S extends Sized> GridCache<S> forBudget(Budget budget) {
        return new GridCache<S>(budget);
    }

    /** The cache of parsed horizontal (datum-shift) grids. */
    public static GridCache<Grid> instance() {
        return HORIZONTAL;
    }

    /** The cache of parsed vertical (geoid / height) grids. */
    public static GridCache<VerticalGrid> vertical() {
        return VERTICAL;
    }

    private static long configuredMaxBytes() {
        String raw = System.getProperty(SIZE_PROPERTY);
        if (raw == null) {
            return DEFAULT_MAX_BYTES;
        }
        try {
            long v = Long.parseLong(raw.trim());
            return v > 0 ? v : DEFAULT_MAX_BYTES;
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_BYTES;
        }
    }

    /**
     * The cache key. Includes the resolver name, so a grid served by a different resolver is a
     * different cache entry: two resolvers may legitimately hold different files under the same name,
     * and conflating them is how a cache turns a resolution question into a wrong answer.
     *
     * <p>The separator is NUL, and it is spelled {@code '\0'} rather than written as a raw byte.
     * NUL is the right separator: {@code ResourceNames} refuses any character {@code <= ' '} in a
     * grid name, so it is the one character that cannot appear in either half, and a printable
     * separator such as a space would let {@code ("a b", "c")} and {@code ("a", "b c")} collide on
     * one entry — two different files under one key, which is the wrong-answer shape this key
     * exists to prevent. But it was written as a <em>literal NUL byte in the source</em>, which
     * renders as a space in most viewers and made this entire file binary to {@code grep}: a plain
     * {@code grep} for any identifier in it returned nothing at all. That is the exact failure this
     * project has been bitten by three times, so the character is now an escape and the file is
     * ASCII again. Same char, same key, same behaviour.
     */
    static String key(String resolverName, String gridName) {
        return resolverName + '\0' + gridName;
    }

    /**
     * Returns the cached grid for {@code (resolverName, gridName)}, loading it at most once even
     * under concurrent demand.
     *
     * @throws IOException the loader's failure, replayed identically on every subsequent call for the
     *                     same key.
     */
    public T get(String resolverName, String gridName, Loader<T> loader) throws IOException {
        final String k = key(resolverName, gridName);
        FutureTask<T> task = entries.get(k);
        boolean mine = false;
        if (task == null) {
            final Loader<T> l = loader;
            FutureTask<T> created = new FutureTask<T>(new java.util.concurrent.Callable<T>() {
                @Override
                public T call() throws IOException {
                    return l.load();
                }
            });
            FutureTask<T> existing = entries.putIfAbsent(k, created);
            if (existing == null) {
                task = created;
                mine = true;
                misses.incrementAndGet();
                task.run();
            } else {
                task = existing;
                hits.incrementAndGet();
            }
        } else {
            hits.incrementAndGet();
        }

        T grid;
        try {
            grid = task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // The entry is deliberately LEFT IN PLACE, and this is the second attempt at this line.
            //
            // Only a waiter can land here: the owning thread runs the FutureTask synchronously, so
            // its get() never blocks. The waiter's interrupt says nothing about the file, and the
            // load it was waiting on is about to finish successfully on the owner's thread.
            // Removing the mapping here -- which InitFileCache does, and which this class briefly
            // did -- throws away a good load AND races the owner: the owner then admits a key that
            // is no longer in `entries`, leaving a byte charge in the budget with nothing behind it
            // that can only be released by eviction. So the interrupt propagates and the cache is
            // untouched; the next caller gets the grid the owner cached.
            throw new IOException("Interrupted while loading grid " + gridName, e);
        } catch (CancellationException e) {
            // Unreachable today -- nothing in proj4j cancels these tasks -- and removed rather than
            // retained if it ever becomes reachable, because a cancelled FutureTask never completes
            // and would otherwise replay CancellationException for every later caller, forever.
            // Distinct from the interrupt above precisely because there is no pending success to
            // race. Untested, for want of a way to reach it that is not itself a fiction.
            entries.remove(k, task);
            throw new IOException("Grid load cancelled for " + gridName, e);
        } catch (ExecutionException e) {
            // Cached, and now accounted for. A corrupt grid must fail the same way on row
            // 4,000,000 as on row 1, so the failure is retained -- but it is charged, and therefore
            // evictable, because the key contains an untrusted +nadgrids= token.
            if (mine) {
                admit(k, FAILURE_WEIGHT_BYTES);
            } else {
                touch(k);
            }
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                // Rethrow a fresh instance: the cached one would accumulate every caller's stack.
                throw new IOException(cause.getMessage(), cause);
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IOException("Failed to load grid " + gridName, cause);
        }

        if (mine) {
            // A loader is not permitted to return null, but charging a null the failure weight
            // rather than zero means a misbehaving one cannot install a free, unevictable entry.
            admit(k, grid == null ? FAILURE_WEIGHT_BYTES : grid.sizeBytes());
        } else {
            touch(k);
        }
        return grid;
    }

    private void admit(String k, long bytes) {
        if (bytes > budget.maxBytes()) {
            // Larger than the entire budget. Serve it, do not retain it: retaining it would park
            // the cache permanently over its ceiling, because the just-admitted entry is exempt
            // from eviction. InitFileCache makes the same call for an oversized dictionary.
            entries.remove(k);
            return;
        }
        List<Budget.Key> evict = budget.admit(new Budget.Key(this, k), bytes);
        for (int i = 0; i < evict.size(); i++) {
            Budget.Key gone = evict.get(i);
            gone.cache.entries.remove(gone.name);
            gone.cache.evictions.incrementAndGet();
        }
    }

    private void touch(String k) {
        budget.touch(new Budget.Key(this, k));
    }

    /** Number of cached grids, including cached failures. */
    public int size() {
        return entries.size();
    }

    /**
     * Bytes currently retained by <strong>this</strong> cache. The budget is shared with the other
     * one, so this can be below {@link #maxBytes()} while the shared total is at it.
     */
    public long bytes() {
        return budget.bytesOf(this);
    }

    /** Bytes retained across every cache sharing this budget; never above {@link #maxBytes()}. */
    public long sharedBytes() {
        return budget.currentBytes();
    }

    /** The shared ceiling. Both {@link #instance()} and {@link #vertical()} report the same value. */
    public long maxBytes() {
        return budget.maxBytes();
    }

    public long hitCount() {
        return hits.get();
    }

    public long missCount() {
        return misses.get();
    }

    public long evictionCount() {
        return evictions.get();
    }

    /**
     * Empties the cache. Intended for tests and for an application that has deliberately changed the
     * resolver chain; a running pipeline should never need it.
     */
    public void clear() {
        entries.clear();
        budget.forget(this);
    }

    @Override
    public String toString() {
        return "GridCache[" + size() + " grids, " + bytes() + " of " + budget.currentBytes()
                + " shared / " + budget.maxBytes() + " bytes, " + hits.get() + " hits, "
                + misses.get() + " misses, " + evictions.get() + " evictions]";
    }

    /**
     * The byte budget, shared by every cache that draws on it.
     *
     * <p>Extracted from {@code GridCache} for one reason: there are two caches and there was one
     * documented ceiling, and before this the two each took that ceiling in full. Keeping the
     * accounting in an object they share is what makes {@code proj4j.grids.cacheBytes} a bound on
     * the process rather than on an instance.
     *
     * <p>The monitor is never held across I/O, and {@link #admit} returns the keys to drop instead
     * of dropping them, so no cache's entry map is ever touched under this lock. That is the same
     * shape the per-instance version had, and it is what keeps the two locks from nesting.
     */
    static final class Budget {

        /** Which cache an accounted entry belongs to, so eviction can reach the right entry map. */
        static final class Key {
            final GridCache<?> cache;
            final String name;

            Key(GridCache<?> cache, String name) {
                this.cache = cache;
                this.name = name;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) {
                    return true;
                }
                if (!(o instanceof Key)) {
                    return false;
                }
                Key k = (Key) o;
                return cache == k.cache && name.equals(k.name);
            }

            @Override
            public int hashCode() {
                return System.identityHashCode(cache) * 31 + name.hashCode();
            }
        }

        private final long maxBytes;

        /** Access-ordered LRU bookkeeping. Guarded by {@code this}; never held across I/O. */
        private final LinkedHashMap<Key, Long> lru = new LinkedHashMap<Key, Long>(16, 0.75f, true);

        private long currentBytes;

        Budget(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        long maxBytes() {
            return maxBytes;
        }

        synchronized long currentBytes() {
            return currentBytes;
        }

        synchronized long bytesOf(GridCache<?> cache) {
            long total = 0L;
            for (Map.Entry<Key, Long> e : lru.entrySet()) {
                if (e.getKey().cache == cache) {
                    total += e.getValue().longValue();
                }
            }
            return total;
        }

        /** @return the entries to drop, in eviction order; never null, usually empty */
        List<Key> admit(Key k, long bytes) {
            List<Key> evict = Collections.emptyList();
            synchronized (this) {
                Long prior = lru.put(k, Long.valueOf(bytes));
                currentBytes += bytes - (prior == null ? 0L : prior.longValue());
                if (currentBytes > maxBytes) {
                    evict = new ArrayList<Key>();
                    Iterator<Map.Entry<Key, Long>> it = lru.entrySet().iterator();
                    while (it.hasNext() && currentBytes > maxBytes) {
                        Map.Entry<Key, Long> oldest = it.next();
                        if (oldest.getKey().equals(k)) {
                            // Never evict what was just admitted. GridCache.admit refuses anything
                            // larger than the whole budget outright, so this can only ever defer a
                            // trim to the next admission -- it cannot leave the cache over budget
                            // indefinitely, which is what it used to do.
                            continue;
                        }
                        currentBytes -= oldest.getValue().longValue();
                        evict.add(oldest.getKey());
                        it.remove();
                    }
                }
            }
            return evict;
        }

        synchronized void touch(Key k) {
            lru.get(k);
        }

        /** Drops every accounted entry belonging to {@code cache}. */
        synchronized void forget(GridCache<?> cache) {
            Iterator<Map.Entry<Key, Long>> it = lru.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Key, Long> e = it.next();
                if (e.getKey().cache == cache) {
                    currentBytes -= e.getValue().longValue();
                    it.remove();
                }
            }
        }
    }
}

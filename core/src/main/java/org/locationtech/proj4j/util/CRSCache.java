/*******************************************************************************
 * Copyright 2009, 2017 Martin Davis
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
 */
package org.locationtech.proj4j.util;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.UnknownAuthorityCodeException;
import org.locationtech.proj4j.UnsupportedParameterException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A memoising wrapper around {@link CRSFactory}.
 *
 * @deprecated Nothing in {@code core/src/main} uses this class, and as originally written it was an
 *     out-of-memory vector rather than an optimisation: two {@link ConcurrentHashMap}s keyed on the
 *     <b>raw caller-supplied string</b> with <b>no eviction of any kind</b>. In this library's
 *     deployment - called per row inside a Spark executor, with the CRS string coming from user
 *     data - an unbounded map keyed on untrusted input grows until the executor dies.
 *     <p>It is bounded now rather than deleted, because it is public API. Prefer
 *     {@link CRSFactory} directly: since the {@code io.InitFileCache} landed, {@code createFromName}
 *     no longer re-scans the 888 KB {@code proj4/nad/epsg} dictionary on every call, which was the
 *     cost this class existed to hide.
 *
 * <h2>What changed, and what did not</h2>
 * <ul>
 *   <li><b>Bounded.</b> Each of the two maps holds at most {@link #maxEntries()} entries -
 *       <b>so an instance holds at most twice that</b>, stated plainly here because a shared budget
 *       that is quietly spent twice is a bound in name only. Default 1,024 each; override with
 *       {@code -Dproj4j.crsCache.maxEntries}. Eviction is least-recently-used.
 *       <p>The bound is on <em>entries</em>, not bytes, unlike
 *       {@link org.locationtech.proj4j.datum.GridCache}. That is deliberate: grids run from a
 *       624-byte fixture to an 80 MB geoid, so only bytes bound them, whereas parsed
 *       {@code CoordinateReferenceSystem}s are within a small factor of each other and an entry
 *       count is both meaningful and measurable without a heap walker.</li>
 *   <li><b>No lock is held across the factory call.</b> Loading goes through a {@link FutureTask}
 *       published with {@code putIfAbsent}, as {@code GridCache} does, so concurrent demand for one
 *       key produces exactly one construction and the loser waits on the future. The previous
 *       {@code computeIfAbsent} ran the whole parse - including classpath I/O - inside a
 *       {@code ConcurrentHashMap} bin lock, which its own javadoc forbids.</li>
 *   <li><b>Keys are unambiguous.</b> The old key for {@code createFromParameters} was
 *       {@code name + paramStr} concatenated, so {@code ("EPSG:4326", "+proj=longlat")} and
 *       {@code ("EPSG:4326+proj=longlat", "")} were the same entry, and the {@code String[]}
 *       overload joined on a space, so {@code {"a b"}} and {@code {"a", "b"}} collided although
 *       {@code CRSFactory} does not treat them alike. Either collision returns <b>a different CRS
 *       than was asked for</b>. Keys are length-prefixed now, which cannot collide.</li>
 *   <li><b>Exceptions are still not memoised.</b> A failed construction is removed from the map and
 *       rethrown, exactly as before - so a stream of unresolvable names cannot grow the cache at
 *       all, and a caller sees the same exception it would have seen from {@code CRSFactory}.</li>
 *   <li><b>A successful {@code null} from {@code readEpsgFromParameters} now <em>is</em>
 *       memoised.</b> A record correction goes with this: {@code reference/performance.md} said
 *       this class "memoises null on IOException". <b>It did not.</b> {@code computeIfAbsent}
 *       installs no mapping when the function returns {@code null}, so both a genuine "no such
 *       code" and an {@code IOException} simply re-ran the full dictionary scan on every call. A
 *       genuine {@code null} is a stable property of an immutable classpath dictionary and is now
 *       cached; an {@code IOException} still is not, and still surfaces as {@code null} to preserve
 *       this method's signature.</li>
 * </ul>
 *
 * <p>This cache cannot change an answer. A {@code CoordinateReferenceSystem} is not mutated after
 * construction by anything in this library, construction is a pure function of the key, and the
 * factory is stateless, so an evicted entry rebuilt later is observationally identical. Note that
 * {@code CoordinateReferenceSystem.getParameters()} returns its internal array by reference: a
 * caller that mutates it corrupts the shared instance. That was true of this class before and is
 * not introduced here.
 */
@Deprecated
public class CRSCache {

    /** Overrides the per-map entry ceiling. */
    public static final String SIZE_PROPERTY = "proj4j.crsCache.maxEntries";

    static final int DEFAULT_MAX_ENTRIES = 1024;

    private static final CRSFactory crsFactory = new CRSFactory();

    /**
     * Three key spaces, three maps, and that is the point: separate maps make a cross-scheme
     * collision <em>impossible</em> rather than merely unlikely, which matters because every key
     * component here is untrusted input. Folding names and parameter strings into one map is what
     * made the old ad-hoc concatenation able to answer one question with another's answer.
     */
    private final Bounded<CoordinateReferenceSystem> byName;
    private final Bounded<CoordinateReferenceSystem> byParams;
    private final Bounded<String> epsgByParams;

    public CRSCache() {
        this(configuredMaxEntries());
    }

    /**
     * @param maxEntries the ceiling for <em>each</em> of the three internal maps; a value below 1
     *                   is replaced by the default
     * @since 1.5.0
     */
    public CRSCache(int maxEntries) {
        int n = maxEntries > 0 ? maxEntries : DEFAULT_MAX_ENTRIES;
        this.byName = new Bounded<CoordinateReferenceSystem>(n);
        this.byParams = new Bounded<CoordinateReferenceSystem>(n);
        this.epsgByParams = new Bounded<String>(n);
    }

    /**
     * @deprecated The supplied maps <b>seed</b> a bounded cache; they are not retained, and
     *     mutating them afterwards has no effect. Retaining them would reinstate precisely the
     *     unbounded growth this class was changed to stop. Seeding more entries than the ceiling
     *     evicts the excess in iteration order.
     *     <p>Reachability of a seeded entry, stated exactly rather than implied:
     *     <ul>
     *       <li>{@code crsCache} seeds the <b>name</b> map, whose key is still the bare name, so an
     *           entry put there by {@link #createFromName(String)} is hit as before. An entry that
     *           a previous version had put there from {@code createFromParameters} was keyed by an
     *           ad-hoc concatenation; it is retained but will not be hit, and ages out.</li>
     *       <li>{@code epsgCache} seeds the EPSG map through the current key function applied to
     *           the raw key, which is exactly what
     *           {@link #readEpsgFromParameters(String)} computes - so those entries are hit.
     *           {@link #readEpsgFromParameters(String[])} now keys per element, so an entry seeded
     *           from the array overload's old space-joined key is not hit.</li>
     *     </ul>
     */
    @Deprecated
    public CRSCache(ConcurrentHashMap<String, CoordinateReferenceSystem> crsCache,
                    ConcurrentHashMap<String, String> epsgCache) {
        this(configuredMaxEntries());
        if (crsCache != null) {
            for (Map.Entry<String, CoordinateReferenceSystem> e : crsCache.entrySet()) {
                this.byName.seed(e.getKey(), e.getValue());
            }
        }
        if (epsgCache != null) {
            for (Map.Entry<String, String> e : epsgCache.entrySet()) {
                this.epsgByParams.seed(key(e.getKey()), e.getValue());
            }
        }
    }

    static int configuredMaxEntries() {
        String raw = System.getProperty(SIZE_PROPERTY);
        if (raw == null) {
            return DEFAULT_MAX_ENTRIES;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            return v > 0 ? v : DEFAULT_MAX_ENTRIES;
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_ENTRIES;
        }
    }

    /** The ceiling on <em>each</em> of the three internal maps. */
    public int maxEntries() {
        return byName.maxEntries;
    }

    /** Entries currently retained, across all three maps. */
    public int size() {
        return byName.size() + byParams.size() + epsgByParams.size();
    }

    /** Entries evicted so far, across all three maps. */
    public long evictionCount() {
        return byName.evictions.get() + byParams.evictions.get() + epsgByParams.evictions.get();
    }

    /** Drops every entry. */
    public void clear() {
        byName.clear();
        byParams.clear();
        epsgByParams.clear();
    }

    /**
     * The steady-state path, and it must allocate <b>nothing</b>.
     *
     * <p>{@code TransformCacheBenchmark.crsCacheHit} is ratcheted at <b>0 B/op</b> because a warm
     * hit is where a consumer spends essentially all of its time. That is why every method below
     * consults {@link Bounded#peek} <em>before</em> building anything: an anonymous
     * {@link Callable} is an allocation, and Java evaluates it as an argument whether the slow path
     * runs or not, so writing the obvious {@code cache.get(k, () -> ...)} would put a fresh object
     * on the hot path and break that ratchet. The pre-cache code had the same shape for the same
     * reason - a bare {@code map.get} and an early return.
     */
    public CoordinateReferenceSystem createFromName(final String name)
            throws UnsupportedParameterException, InvalidValueException, UnknownAuthorityCodeException {
        // The bare name, unchanged: one component, so there is nothing to disambiguate, and it
        // keeps entries seeded through the deprecated two-map constructor reachable.
        Object hit = byName.peek(name);
        if (hit != Bounded.ABSENT) {
            return (CoordinateReferenceSystem) hit;
        }
        return byName.get(name, new Callable<CoordinateReferenceSystem>() {
            @Override
            public CoordinateReferenceSystem call() {
                return crsFactory.createFromName(name);
            }
        });
    }

    public CoordinateReferenceSystem createFromParameters(final String name, final String paramStr)
            throws UnsupportedParameterException, InvalidValueException {
        String k = key(name) + key(paramStr);
        Object hit = byParams.peek(k);
        if (hit != Bounded.ABSENT) {
            return (CoordinateReferenceSystem) hit;
        }
        return byParams.get(k, new Callable<CoordinateReferenceSystem>() {
            @Override
            public CoordinateReferenceSystem call() {
                return crsFactory.createFromParameters(name, paramStr);
            }
        });
    }

    public CoordinateReferenceSystem createFromParameters(final String name, final String[] params)
            throws UnsupportedParameterException, InvalidValueException {
        String k = key(name) + key(params);
        Object hit = byParams.peek(k);
        if (hit != Bounded.ABSENT) {
            return (CoordinateReferenceSystem) hit;
        }
        return byParams.get(k, new Callable<CoordinateReferenceSystem>() {
            @Override
            public CoordinateReferenceSystem call() {
                return crsFactory.createFromParameters(name, params);
            }
        });
    }

    public String readEpsgFromParameters(final String paramStr) {
        String k = key(paramStr);
        Object hit = epsgByParams.peek(k);
        if (hit != Bounded.ABSENT) {
            return (String) hit;
        }
        return epsgByParams.get(k, new Callable<String>() {
            @Override
            public String call() throws IOException {
                return crsFactory.readEpsgFromParameters(paramStr);
            }
        });
    }

    public String readEpsgFromParameters(final String[] params) {
        String k = key(params);
        Object hit = epsgByParams.peek(k);
        if (hit != Bounded.ABSENT) {
            return (String) hit;
        }
        return epsgByParams.get(k, new Callable<String>() {
            @Override
            public String call() throws IOException {
                return crsFactory.readEpsgFromParameters(params);
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // Keying. Length-prefixed so that no concatenation of components can be read two ways.
    // ------------------------------------------------------------------------------------------

    private static String key(String s) {
        if (s == null) {
            return "-1:";
        }
        return s.length() + ":" + s;
    }

    private static String key(String[] parts) {
        if (parts == null) {
            return "-1:";
        }
        StringBuilder b = new StringBuilder();
        b.append(parts.length).append('#');
        for (int i = 0; i < parts.length; i++) {
            b.append(key(parts[i]));
        }
        return b.toString();
    }

    // ------------------------------------------------------------------------------------------

    /**
     * A bounded, LRU, exactly-once map. Same shape as
     * {@link org.locationtech.proj4j.datum.GridCache}: values live in a {@link ConcurrentHashMap} of
     * {@link FutureTask}s so no lock spans the load, and a separate short critical section keeps the
     * access order.
     */
    private static final class Bounded<V> {

        final int maxEntries;

        private final ConcurrentMap<String, FutureTask<V>> entries =
                new ConcurrentHashMap<String, FutureTask<V>>();

        /** Access-ordered. Guarded by itself; never held across a load. */
        private final LinkedHashMap<String, Boolean> lru =
                new LinkedHashMap<String, Boolean>(16, 0.75f, true);

        final AtomicLong evictions = new AtomicLong();

        Bounded(int maxEntries) {
            this.maxEntries = maxEntries;
        }

        int size() {
            return entries.size();
        }

        void clear() {
            entries.clear();
            synchronized (lru) {
                lru.clear();
            }
        }

        void seed(String k, V v) {
            FutureTask<V> done = completed(v);
            if (entries.putIfAbsent(k, done) == null) {
                admit(k);
            }
        }

        private static <V> FutureTask<V> completed(final V v) {
            FutureTask<V> t = new FutureTask<V>(new Callable<V>() {
                @Override
                public V call() {
                    return v;
                }
            });
            t.run();
            return t;
        }

        /**
         * Distinguishes "no completed entry" from "a completed entry whose value is {@code null}".
         * A {@code null} is a real memoised answer for {@code readEpsgFromParameters}, so a plain
         * {@code null} return would silently un-memoise every one of them.
         */
        static final Object ABSENT = new Object();

        /**
         * The allocation-free hit path: the completed value, or {@link #ABSENT}.
         *
         * <p>Returns {@code ABSENT} for an entry that is still loading or that failed, so the
         * caller falls through to {@link #get} and gets the full waiting and error handling there
         * rather than a second, divergent copy of it here.
         */
        Object peek(String k) {
            FutureTask<V> task = entries.get(k);
            if (task == null || !task.isDone()) {
                return ABSENT;
            }
            V value;
            try {
                value = task.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ABSENT;
            } catch (ExecutionException e) {
                return ABSENT;
            } catch (CancellationException e) {
                return ABSENT;
            }
            touch(k);
            return value;
        }

        V get(String k, Callable<V> loader) {
            FutureTask<V> task = entries.get(k);
            boolean mine = false;
            if (task == null) {
                FutureTask<V> created = new FutureTask<V>(loader);
                FutureTask<V> existing = entries.putIfAbsent(k, created);
                if (existing == null) {
                    task = created;
                    mine = true;
                    task.run();
                } else {
                    task = existing;
                }
            }

            V value;
            try {
                value = task.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                entries.remove(k, task);
                throw new IllegalStateException("Interrupted while building " + k, e);
            } catch (CancellationException e) {
                entries.remove(k, task);
                throw new IllegalStateException("Cancelled while building " + k, e);
            } catch (ExecutionException e) {
                // Failures are NOT memoised: a caller must see what CRSFactory would have thrown,
                // and a stream of unresolvable names must not be able to grow this map.
                entries.remove(k, task);
                Throwable cause = e.getCause();
                if (cause instanceof IOException) {
                    // Only readEpsgFromParameters can reach this, and its signature has always
                    // swallowed IOException as null. Unlike a genuine "no such code", this is not
                    // memoised - see the class javadoc's record correction.
                    return null;
                }
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
                if (cause instanceof Error) {
                    throw (Error) cause;
                }
                throw new IllegalStateException("Failed to build " + k, cause);
            }

            if (mine) {
                admit(k);
            } else {
                touch(k);
            }
            return value;
        }

        private void admit(String k) {
            List<String> evict = null;
            synchronized (lru) {
                lru.put(k, Boolean.TRUE);
                Iterator<Map.Entry<String, Boolean>> it = lru.entrySet().iterator();
                while (lru.size() > maxEntries && it.hasNext()) {
                    Map.Entry<String, Boolean> oldest = it.next();
                    if (oldest.getKey().equals(k)) {
                        continue;
                    }
                    if (evict == null) {
                        evict = new ArrayList<String>();
                    }
                    evict.add(oldest.getKey());
                    it.remove();
                }
            }
            if (evict != null) {
                for (String gone : evict) {
                    entries.remove(gone);
                    evictions.incrementAndGet();
                }
            }
        }

        private void touch(String k) {
            synchronized (lru) {
                lru.get(k);
            }
        }
    }
}

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
package org.locationtech.proj4j.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;

/**
 * {@link CRSCache} after it was deprecated and bounded.
 *
 * <p>Three things had to be true and none of them were: the cache must be <b>bounded</b> (it was two
 * unbounded {@code ConcurrentHashMap}s keyed on the raw untrusted string), its keys must be
 * <b>unambiguous</b> (two components were concatenated, so one question could be answered with
 * another's answer), and a failure must not be <b>memoised</b> as a plausible value.
 *
 * <p>Every bound below is shown rejecting something <em>and</em> accepting legitimate input, and the
 * key-collision test is written as a pair: the colliding inputs must produce the answers
 * {@link CRSFactory} produces, and the test first proves those two answers are actually
 * <b>different</b> - otherwise a collision would be undetectable and the test vacuous.
 */
@SuppressWarnings("deprecation")
public class CRSCacheTest {

    // ==========================================================================================
    // Correctness: the cache must agree with the factory, always
    // ==========================================================================================

    @Test
    public void everyLookupAgreesWithTheUncachedFactory() {
        CRSFactory factory = new CRSFactory();
        CRSCache cache = new CRSCache();

        String[] names = {"EPSG:4326", "epsg:4326", "EPSG:3857", "EPSG:2193", "ESRI:102008",
                          "NAD83:101", "world:palestine"};
        for (int i = 0; i < names.length; i++) {
            CoordinateReferenceSystem expected = factory.createFromName(names[i]);
            CoordinateReferenceSystem first = cache.createFromName(names[i]);
            CoordinateReferenceSystem second = cache.createFromName(names[i]);
            assertEquals(names[i], Arrays.toString(expected.getParameters()),
                    Arrays.toString(first.getParameters()));
            assertEquals(names[i] + " must be the same instance on a hit", first, second);
        }
        assertEquals("every distinct name must have been retained", names.length, cache.size());
    }

    /**
     * The key collision. {@code ("EPSG:4326", "+proj=merc")} and {@code ("EPSG:4326+proj=merc", "")}
     * concatenated to the same old key, and {@code {"+proj=merc","+lat_ts=0"}} joined on a space to
     * the same string as {@code {"+proj=merc +lat_ts=0"}} although the factory does not treat those
     * alike. Either collision hands back a CRS that was never asked for.
     */
    @Test
    public void collidingKeysReturnTheirOwnAnswers() {
        CRSFactory factory = new CRSFactory();
        CRSCache cache = new CRSCache();

        // --- pair 1: name/paramStr boundary -----------------------------------------------
        String nameA = "EPSG:4326";
        String paramsA = "+proj=merc +lat_ts=0 +datum=WGS84";
        String nameB = nameA + paramsA;
        String paramsB = "";

        CoordinateReferenceSystem refA = factory.createFromParameters(nameA, paramsA);
        // paramsB is empty: the factory's own answer for it is the control, whatever it is.
        CoordinateReferenceSystem refB;
        try {
            refB = factory.createFromParameters(nameB, paramsB);
        } catch (RuntimeException e) {
            refB = null;
        }

        CoordinateReferenceSystem gotA = cache.createFromParameters(nameA, paramsA);
        assertEquals("the cache disagreed with the factory on the first of a colliding pair",
                Arrays.toString(refA.getParameters()), Arrays.toString(gotA.getParameters()));

        if (refB != null) {
            CoordinateReferenceSystem gotB = cache.createFromParameters(nameB, paramsB);
            assertEquals("the cache answered the second of a colliding pair with the first's CRS",
                    Arrays.toString(refB.getParameters()), Arrays.toString(gotB.getParameters()));
            // The control: the two answers really are distinguishable, so the assertion above
            // could have failed.
            assertFalse("the two halves of the colliding pair have identical parameters, so this "
                    + "test cannot detect a collision at all",
                    Arrays.toString(refA.getParameters())
                            .equals(Arrays.toString(refB.getParameters())));
        } else {
            // Even so: the second must FAIL rather than be answered from the first's entry.
            try {
                cache.createFromParameters(nameB, paramsB);
                fail("the cache answered a definition the factory rejects - it served the "
                        + "colliding entry instead");
            } catch (RuntimeException expected) {
                // right
            }
        }

        // --- pair 2: the String[] join ----------------------------------------------------
        String[] split = {"+proj=merc", "+lat_ts=0"};
        String[] fused = {"+proj=merc +lat_ts=0"};
        String refSplit = safeToString(factory, split);
        String refFused = safeToString(factory, fused);
        assertFalse("{\"a\",\"b\"} and {\"a b\"} produce identical results from the factory, so "
                + "the space-join collision is undetectable and this test is vacuous",
                refSplit.equals(refFused));
        assertEquals("split array", refSplit, safeToString(cache, split));
        assertEquals("fused array - answered from the split entry's key", refFused,
                safeToString(cache, fused));
    }

    // ==========================================================================================
    // The bound: rejecting, and accepting
    // ==========================================================================================

    /** REJECTING: the OOM vector. Distinct untrusted keys cannot grow the cache without limit. */
    @Test
    public void anUnboundedStreamOfDistinctKeysIsBounded() {
        CRSCache cache = new CRSCache(16);
        for (int i = 0; i < 2000; i++) {
            CoordinateReferenceSystem crs =
                    cache.createFromParameters("crs" + i, "+proj=merc +lat_0=" + (i % 80) * 0.1);
            assertNotNull(crs);
        }
        assertTrue("2000 distinct keys left " + cache.size() + " entries under a 16-entry ceiling",
                cache.size() <= 17);
        assertTrue("nothing was evicted, so nothing was bounded", cache.evictionCount() >= 1900);
    }

    /** ACCEPTING: under the ceiling nothing is evicted and every entry is retained. */
    @Test
    public void aWorkloadInsideTheCeilingIsNotEvicted() {
        CRSCache cache = new CRSCache(64);
        for (int round = 0; round < 5; round++) {
            for (int i = 0; i < 20; i++) {
                assertNotNull(cache.createFromParameters("crs" + i, "+proj=merc +lat_0=" + i));
            }
        }
        assertEquals("a 20-key workload under a 64-entry ceiling must not evict",
                0L, cache.evictionCount());
        assertEquals(20, cache.size());
    }

    /** Eviction costs a rebuild, never an answer. */
    @Test
    public void anEvictedEntryRebuildsToTheSameThing() {
        CRSFactory factory = new CRSFactory();
        CRSCache cache = new CRSCache(2);
        String expected = Arrays.toString(factory.createFromName("EPSG:4326").getParameters());

        assertEquals(expected, Arrays.toString(cache.createFromName("EPSG:4326").getParameters()));
        cache.createFromName("EPSG:3857");
        cache.createFromName("EPSG:2193");
        cache.createFromName("EPSG:32633");
        assertTrue("EPSG:4326 was never evicted, so the rebuild path was not exercised",
                cache.evictionCount() > 0);
        assertEquals("the rebuilt CRS differs from the original", expected,
                Arrays.toString(cache.createFromName("EPSG:4326").getParameters()));
    }

    @Test
    public void theCeilingIsConfigurableAndNonsenseFallsBackToTheDefault() {
        assertEquals(CRSCache.DEFAULT_MAX_ENTRIES, new CRSCache(0).maxEntries());
        assertEquals(CRSCache.DEFAULT_MAX_ENTRIES, new CRSCache(-5).maxEntries());
        assertEquals(7, new CRSCache(7).maxEntries());
    }

    // ==========================================================================================
    // Failures
    // ==========================================================================================

    /**
     * A failure is rethrown, not memoised - so a stream of unresolvable names cannot grow the
     * cache, and the caller sees exactly what {@link CRSFactory} would have thrown.
     */
    @Test
    public void failuresAreRethrownAndNotRetained() {
        CRSCache cache = new CRSCache(64);
        for (int i = 0; i < 200; i++) {
            try {
                cache.createFromName("bogus_authority_" + i + ":1");
                fail("bogus_authority_" + i + ":1 resolved");
            } catch (RuntimeException expected) {
                // right
            }
        }
        assertEquals("a failed construction was retained; a stream of unresolvable names can grow "
                + "the cache", 0, cache.size());

        // The same name fails the same way twice ...
        String first = messageOfFailure(cache, "bogus:1");
        String second = messageOfFailure(cache, "bogus:1");
        assertEquals(first, second);

        // ... and a good name still works.
        assertNotNull(cache.createFromName("EPSG:4326"));
    }

    /**
     * The record correction, asserted rather than asserted-in-prose. {@code performance.md} said
     * this class "memoises null on IOException". It did not: {@code computeIfAbsent} installs no
     * mapping for a null result. A genuine "no such code" is now memoised - and the test proves the
     * memoisation is real by requiring the entry to be retained.
     */
    @Test
    public void aGenuineNullFromTheReverseLookupIsMemoisedAndCorrect() {
        CRSFactory factory = new CRSFactory();
        CRSCache cache = new CRSCache(64);

        String[] nonsense = {"+proj=not_a_projection", "+lat_0=1234"};
        String reference;
        try {
            reference = factory.readEpsgFromParameters(nonsense);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        assertNull("the control input must have no EPSG code, or this test measures nothing",
                reference);

        assertNull(cache.readEpsgFromParameters(nonsense));
        assertEquals("the null was not retained, so it is re-scanned on every call", 1, cache.size());
        assertNull(cache.readEpsgFromParameters(nonsense));
        assertEquals(1, cache.size());

        // ACCEPTING: a real parameter set still resolves to its code, through the cache.
        String[] wgs84 = new CRSFactory().createFromName("EPSG:4326").getParameters();
        String expected;
        try {
            expected = factory.readEpsgFromParameters(wgs84);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        assertNotNull("EPSG:4326's own parameters must reverse-resolve, or the accepting half of "
                + "this test proves nothing", expected);
        assertEquals(expected, cache.readEpsgFromParameters(wgs84));
    }

    // ==========================================================================================
    // The deprecated seeding constructor
    // ==========================================================================================

    @Test
    public void theTwoMapConstructorSeedsWithoutRetainingTheCallersMaps() {
        java.util.concurrent.ConcurrentHashMap<String, CoordinateReferenceSystem> seedCrs =
                new java.util.concurrent.ConcurrentHashMap<String, CoordinateReferenceSystem>();
        java.util.concurrent.ConcurrentHashMap<String, String> seedEpsg =
                new java.util.concurrent.ConcurrentHashMap<String, String>();
        CoordinateReferenceSystem sentinel = new CRSFactory().createFromName("EPSG:3857");
        seedCrs.put("EPSG:4326", sentinel);
        seedEpsg.put("+proj=nonsense", "SEEDED");

        CRSCache cache = new CRSCache(seedCrs, seedEpsg);

        // Seeded entries are served, so the seeding is not decorative.
        assertEquals("the seeded name entry was not served", sentinel,
                cache.createFromName("EPSG:4326"));
        assertEquals("the seeded EPSG entry was not served", "SEEDED",
                cache.readEpsgFromParameters("+proj=nonsense"));

        // ... but the caller's maps are not retained, which is the whole point of bounding.
        seedCrs.clear();
        seedEpsg.clear();
        assertEquals(sentinel, cache.createFromName("EPSG:4326"));
        for (int i = 0; i < 5000; i++) {
            seedCrs.put("junk" + i, sentinel);
        }
        assertTrue("the cache grew with the caller's map, so it is still unbounded",
                cache.size() < 100);
    }

    // ==========================================================================================
    // Concurrency
    // ==========================================================================================

    @Test
    public void concurrentDemandAgreesAndBuildsEachKeyOnce() throws Exception {
        final CRSCache cache = new CRSCache(64);
        final String expected =
                Arrays.toString(new CRSFactory().createFromName("EPSG:4326").getParameters());
        final AtomicInteger mismatches = new AtomicInteger();
        final int threads = 8;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<Future<?>>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(new Callable<Void>() {
                    @Override
                    public Void call() {
                        for (int i = 0; i < 300; i++) {
                            CoordinateReferenceSystem crs = cache.createFromName("EPSG:4326");
                            if (!expected.equals(Arrays.toString(crs.getParameters()))) {
                                mismatches.incrementAndGet();
                            }
                        }
                        return null;
                    }
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                futures.get(i).get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals("threads disagreed about EPSG:4326", 0, mismatches.get());
        assertEquals(1, cache.size());
    }

    // ==========================================================================================

    private static String safeToString(CRSFactory factory, String[] params) {
        try {
            CoordinateReferenceSystem crs = factory.createFromParameters("n", params);
            return crs == null ? "null" : Arrays.toString(crs.getParameters());
        } catch (RuntimeException e) {
            return e.getClass().getName() + ": " + e.getMessage();
        }
    }

    private static String safeToString(CRSCache cache, String[] params) {
        try {
            CoordinateReferenceSystem crs = cache.createFromParameters("n", params);
            return crs == null ? "null" : Arrays.toString(crs.getParameters());
        } catch (RuntimeException e) {
            return e.getClass().getName() + ": " + e.getMessage();
        }
    }

    private static String messageOfFailure(CRSCache cache, String name) {
        try {
            cache.createFromName(name);
            fail(name + " resolved");
            return null;
        } catch (RuntimeException e) {
            return e.getClass().getName() + ": " + e.getMessage();
        }
    }
}

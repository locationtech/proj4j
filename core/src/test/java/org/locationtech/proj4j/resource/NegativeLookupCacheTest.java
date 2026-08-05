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
package org.locationtech.proj4j.resource;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The resolution memo retains failed lookups, and the set of failed lookups is chosen by whoever
 * writes the CRS string.
 *
 * <h2>Why the two halves are bounded differently</h2>
 *
 * <p>A <b>hit</b> can only be a name that exists in the classpath or the data directory. The
 * population is whatever is installed, an attacker cannot enlarge it by asking, and evicting one
 * costs a real re-probe on a real grid — so hits are retained without a limit.
 *
 * <p>A <b>miss</b> is a name, and names are free. {@code +nadgrids=<anything>} on a per-row CRS
 * string mints a fresh key every row, and every one of them used to be retained forever in the same
 * unbounded {@code ConcurrentHashMap} as the hits. That is a retained-memory primitive driven by
 * untrusted input.
 *
 * <h2>Stop admitting, do not evict</h2>
 *
 * <p>Eviction under a flood would discard exactly the legitimate {@code @}-optional misses the memo
 * exists to keep cheap, and would make which ones survive depend on arrival order. Declining to
 * admit degrades to re-probing — the 1.4.x cost, on the attack path only — and cannot change an
 * answer, because this cache is a pure memo over a pure function. {@link #answersDoNotDependOnWhetherAMissWasCached()}
 * is the test of that claim rather than the comment being the test of it.
 */
public class NegativeLookupCacheTest {

    /** Counts probes, and holds exactly one resource so hits and misses can both be exercised. */
    private static final class CountingResolver implements ResourceResolver {
        final AtomicInteger probes = new AtomicInteger();
        private final String present;

        CountingResolver(String present) {
            this.present = present;
        }

        @Override
        public String name() {
            return "counting";
        }

        @Override
        public ResourceHandle resolve(String resourceName) throws IOException {
            probes.incrementAndGet();
            if (!present.equals(resourceName)) {
                return null;
            }
            return new ByteArrayHandle(resourceName);
        }

        @Override
        public Collection<String> listAvailable() {
            return Collections.singletonList(present);
        }
    }

    private static final class ByteArrayHandle implements ResourceHandle {
        private final String name;

        ByteArrayHandle(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String origin() {
            return "test:" + name;
        }

        @Override
        public long sizeBytes() {
            return 3;
        }

        @Override
        public SeekableByteReader open() {
            return new ByteArrayByteReader(new byte[]{1, 2, 3});
        }
    }

    @Test
    public void missesStopBeingAdmittedAtTheCap() throws IOException {
        CountingResolver delegate = new CountingResolver("real");
        CachingResourceResolver cache = new CachingResourceResolver(delegate, 8);

        for (int i = 0; i < 500; i++) {
            assertNull(cache.resolve("absent-" + i));
        }
        assertEquals("only the first few misses may be retained", 8, cache.cachedMisses());
        assertEquals("and every one of the 500 must have been probed, cached or not", 500,
                delegate.probes.get());
    }

    /**
     * The control for the test above: with the cap out of the way the very same loop fills the map,
     * so 8 is the cap firing rather than the map being broken or the loop never running.
     */
    @Test
    public void withoutTheCapTheSameLoopFillsTheMap() throws IOException {
        CountingResolver delegate = new CountingResolver("real");
        CachingResourceResolver cache = new CachingResourceResolver(delegate, 100000);

        for (int i = 0; i < 500; i++) {
            assertNull(cache.resolve("absent-" + i));
        }
        assertEquals("CONTROL: uncapped, all 500 misses are memoised", 500, cache.cachedMisses());
    }

    /** A cached miss must still be a miss without re-probing, or the memo buys nothing. */
    @Test
    public void aCachedMissIsNotReprobed() throws IOException {
        CountingResolver delegate = new CountingResolver("real");
        CachingResourceResolver cache = new CachingResourceResolver(delegate, 8);

        assertNull(cache.resolve("absent"));
        assertEquals(1, delegate.probes.get());
        for (int i = 0; i < 100; i++) {
            assertNull(cache.resolve("absent"));
        }
        assertEquals("100 repeats of a cached miss must cost no further probes", 1,
                delegate.probes.get());
    }

    /** Hits are unbounded, and a flood of misses must not displace one. */
    @Test
    public void hitsAreNeverEvictedByAFloodOfMisses() throws IOException {
        CountingResolver delegate = new CountingResolver("real");
        CachingResourceResolver cache = new CachingResourceResolver(delegate, 8);

        ResourceHandle first = cache.resolve("real");
        assertNotNull(first);
        int afterFirst = delegate.probes.get();

        for (int i = 0; i < 500; i++) {
            cache.resolve("absent-" + i);
        }

        ResourceHandle again = cache.resolve("real");
        assertSame("the memoised handle must survive the flood", first, again);
        assertEquals("and answering from it must have cost no extra probe", afterFirst + 500,
                delegate.probes.get());
        assertEquals(1, cache.cachedHits());
    }

    /**
     * The determinism claim, tested. Past the cap a miss is re-probed instead of memoised, and the
     * answer must be identical either way — otherwise the bound would be a behaviour change rather
     * than a memory bound.
     */
    @Test
    public void answersDoNotDependOnWhetherAMissWasCached() throws IOException {
        CountingResolver delegate = new CountingResolver("real");
        CachingResourceResolver capped = new CachingResourceResolver(delegate, 0);
        CachingResourceResolver uncapped = new CachingResourceResolver(delegate, 100000);

        for (int i = 0; i < 50; i++) {
            String name = "absent-" + i;
            assertEquals("caching a miss must not change what a miss is", capped.resolve(name),
                    uncapped.resolve(name));
        }
        assertEquals("with a cap of 0, nothing is memoised", 0, capped.cachedMisses());
        assertEquals(50, uncapped.cachedMisses());

        assertNotNull(capped.resolve("real"));
        assertNotNull(uncapped.resolve("real"));
    }

    /** {@code invalidate()} must clear both halves, not just the one it used to know about. */
    @Test
    public void invalidateClearsBothHalves() throws IOException {
        CountingResolver delegate = new CountingResolver("real");
        CachingResourceResolver cache = new CachingResourceResolver(delegate, 8);

        assertNotNull(cache.resolve("real"));
        assertNull(cache.resolve("absent"));
        assertEquals(1, cache.cachedHits());
        assertEquals(1, cache.cachedMisses());

        cache.invalidate();
        assertEquals(0, cache.cachedHits());
        assertEquals(0, cache.cachedMisses());

        int before = delegate.probes.get();
        assertNotNull(cache.resolve("real"));
        assertEquals("after invalidate the delegate must be consulted again", before + 1,
                delegate.probes.get());
    }

    /** The shipped default, so a typo in the property plumbing cannot silently uncap it. */
    @Test
    public void theDefaultCapIsFinite() throws IOException {
        CachingResourceResolver cache =
                new CachingResourceResolver(new CountingResolver("real"));
        for (int i = 0; i < CachingResourceResolver.MAX_ABSENT + 100; i++) {
            cache.resolve("absent-" + i);
        }
        assertTrue("the default cap must bound retention; got " + cache.cachedMisses(),
                cache.cachedMisses() <= CachingResourceResolver.MAX_ABSENT);
        assertTrue("...and must not be so small it caches nothing", cache.cachedMisses() > 0);
    }
}

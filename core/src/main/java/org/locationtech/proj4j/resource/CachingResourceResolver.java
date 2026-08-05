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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Memoises lookup <em>results</em> — including negative ones.
 * <p>
 * The negative half is the point. {@code +nadgrids=@conus} on a classpath without the grid pack is a
 * per-row classpath scan in 1.4.x; here it is probed once. Caching only lookups, never bytes, keeps
 * this cheap: the parsed-grid cache is separate and bounded by bytes.
 * <p>
 * Because a cached miss is not re-probed, a resource added to the classpath at runtime stays
 * invisible until {@link #invalidate()}. That is deliberate: a resolution result that changes
 * mid-job is exactly the nondeterminism this package exists to eliminate.
 *
 * <h2>The two halves are bounded differently, because only one of them is attacker-sized</h2>
 *
 * <p>A <strong>hit</strong> can only be a name that actually exists in the classpath or the data
 * directory, so the set of them is bounded by what is installed. An attacker cannot enlarge it by
 * asking, and evicting one costs a real re-probe on a real grid, so hits are retained without a
 * limit.
 *
 * <p>A <strong>miss</strong> is different in kind: the name comes from a {@code +nadgrids=} token in
 * a per-row CRS string, so a caller can mint an unbounded number of distinct ones. Memoising every
 * one of them — which is what a single map did — turns a resolution cache into a retained-memory
 * primitive driven by untrusted input, at one map entry plus one string per hostile token, for the
 * life of the JVM. Misses are therefore capped at {@value #MAX_ABSENT} names
 * ({@code -Dproj4j.resources.maxCachedMisses}).
 *
 * <p>The cap <em>stops admitting</em> rather than evicting. Eviction under a flood would throw away
 * the legitimate {@code @}-optional misses that the cap exists to keep cheap, and would make which
 * ones survive depend on arrival order. Declining to admit degrades, at worst, to the 1.4.x cost of
 * re-probing — a bounded slowdown on the attack path — while the memory stays bounded and the
 * answers stay identical: this cache is a pure memo over a pure function, so not caching a result
 * cannot change it.
 */
public final class CachingResourceResolver implements ResourceResolver {

    // The ABSENT sentinel this class used to carry is gone with the single map that needed it: a
    // ConcurrentMap cannot hold a null value, so "resolved to nothing" had to be a stand-in handle
    // sharing the map with the real ones. Splitting hits from misses removes both the sentinel and
    // the `cached == ABSENT` test on the resolution path.

    /**
     * Largest number of distinct <em>failed</em> lookups memoised per resolver. Every real grid name
     * in PROJ 9.8.1's {@code grid_alternatives} plus every {@code @}-optional token any shipped
     * definition names is a three-digit population, so this is roughly an order of magnitude above
     * anything legitimate while capping retention at a few hundred kilobytes of strings.
     */
    static final int MAX_ABSENT = 4096;

    private final ResourceResolver delegate;
    private final int maxAbsent;

    /** Successful lookups. Unbounded, because the population is bounded by what is installed. */
    private final ConcurrentMap<String, ResourceHandle> present =
            new ConcurrentHashMap<String, ResourceHandle>();

    /** Failed lookups. Bounded, because the population is whatever a caller cares to invent. */
    private final ConcurrentMap<String, Boolean> absent = new ConcurrentHashMap<String, Boolean>();

    public CachingResourceResolver(ResourceResolver delegate) {
        this(delegate, configuredMaxAbsent());
    }

    /** For tests, which need a cap small enough to reach without minting four thousand names. */
    CachingResourceResolver(ResourceResolver delegate, int maxAbsent) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate");
        }
        this.delegate = delegate;
        this.maxAbsent = maxAbsent;
    }

    private static int configuredMaxAbsent() {
        String raw = System.getProperty("proj4j.resources.maxCachedMisses");
        if (raw != null) {
            try {
                int v = Integer.parseInt(raw.trim());
                if (v >= 0) {
                    return v;
                }
            } catch (NumberFormatException e) {
                // fall through to the default
            }
        }
        return MAX_ABSENT;
    }

    public ResourceResolver delegate() {
        return delegate;
    }

    @Override
    public String name() {
        return "cached(" + delegate.name() + ")";
    }

    @Override
    public boolean isNetworkBacked() {
        return delegate.isNetworkBacked();
    }

    @Override
    public int priority() {
        return delegate.priority();
    }

    @Override
    public ResourceHandle resolve(String resourceName) throws IOException {
        if (resourceName == null) {
            return null;
        }
        // Hits first, and lock-free: a legitimate grid never touches the bounded half.
        ResourceHandle cached = present.get(resourceName);
        if (cached != null) {
            return cached;
        }
        if (absent.containsKey(resourceName)) {
            return null;
        }
        // Deliberately not under a lock: two threads may both probe, and both get the same answer
        // because the delegates are pure functions of the classpath/filesystem.
        ResourceHandle found = delegate.resolve(resourceName);
        if (found != null) {
            present.putIfAbsent(resourceName, found);
        } else if (absent.size() < maxAbsent) {
            // Racing threads may push this a few over the cap; the bound is on retention, not on
            // an exact count, and paying for a lock on the resolution path to make it exact would
            // cost more than the handful of entries it saves.
            absent.putIfAbsent(resourceName, Boolean.TRUE);
        }
        return found;
    }

    /** Drops all memoised results, positive and negative. */
    public void invalidate() {
        present.clear();
        absent.clear();
    }

    /** Memoised successful lookups. For tests and diagnostics. */
    int cachedHits() {
        return present.size();
    }

    /** Memoised failed lookups; never more than the cap, modulo an admission race. */
    int cachedMisses() {
        return absent.size();
    }

    @Override
    public Collection<String> listAvailable() {
        return delegate.listAvailable();
    }

    @Override
    public boolean isEnumerable() {
        return delegate.isEnumerable();
    }
}

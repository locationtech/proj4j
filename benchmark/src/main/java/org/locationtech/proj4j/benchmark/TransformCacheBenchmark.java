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
 */
package org.locationtech.proj4j.benchmark;

import java.util.concurrent.TimeUnit;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.util.CRSCache;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The caching layer: what a hit costs, what a miss costs, and what the two candidate cache keys
 * cost.
 *
 * <p>The consumer caches one {@code CoordinateTransform} and shares it across executor threads, so
 * in steady state it is on the hit path essentially always. The numbers that matter are therefore
 * the <b>ratio</b> of hit to miss (which is how much the cache is worth) and the <b>absolute</b>
 * cost of the key comparison (which is pure overhead on every hit).
 *
 * <p>{@code reference/performance.md} prescribes keying the transform cache on the <b>canonical
 * string pair</b> rather than on CRS object equality, for two reasons: {@code String.hashCode} is
 * cached in the object so a repeat lookup is a field read, and it sidesteps the
 * {@code Projection.equals}/{@code hashCode} defects entirely. {@link #objectKeyLookup} and
 * {@link #stringPairKeyLookup} are the head-to-head for that decision, run in the same fork so the
 * ratio is meaningful.
 *
 * <p><b>Two things are deliberately not benchmarked, and the reasons are the findings:</b>
 * <ol>
 *   <li><b>An unbounded miss stream.</b> {@code util/CRSCache} <i>was</i> an unbounded
 *       {@code ConcurrentHashMap} keyed on user-supplied strings, so a benchmark that fed it a
 *       distinct key per invocation would grow the map until the fork died. That is exactly the OOM
 *       vector {@code performance.md} identifies for untrusted per-row input; a benchmark that
 *       demonstrates it by crashing is not a benchmark, and the fix is a correctness change, not a
 *       performance one. <b>The bound landed in Stage D</b> - access-ordered LRU, default 1,024
 *       entries per key space via {@code -Dproj4j.crsCache.maxEntries} (note: not the
 *       {@code proj4j.crsCacheSize} the old text here proposed) - so an eviction-pressure arm is
 *       now possible and is the obvious next addition.</li>
 *   <li><b>A failing lookup. THE OLD TEXT HERE WAS WRONG AND IS CORRECTED.</b> It said
 *       {@code CRSCache} "memoises {@code null} on {@code IOException}", making the second lookup of
 *       a transiently failed code "a fast wrong answer". <b>It did not memoise anything.</b>
 *       {@code ConcurrentHashMap.computeIfAbsent} installs <b>no mapping</b> when its function
 *       returns {@code null}, so every failing lookup re-ran the entire 888 KB dictionary scan, for
 *       ever - the opposite defect from the one described, and the claim was wrong in the direction
 *       that makes a defect sound handled. Today: an {@code IOException} is still not memoised, a
 *       <i>successful</i> {@code null} is, and construction failures are removed from the map and
 *       rethrown.</li>
 * </ol>
 *
 * <p>There is <b>no transform-level cache in proj4j today</b> - {@code CoordinateTransformFactory}
 * constructs a new {@code BasicCoordinateTransform} on every call. {@link #createTransformUncached}
 * is what a transform cache would eliminate, and is the target for the {@code TransformCache.get}
 * that {@code performance.md}'s serialisation design already assumes exists.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Fork(value = 3, jvmArgsAppend = {"-XX:+UseSerialGC"})
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
public class TransformCacheBenchmark {

    private static final String SRC = "EPSG:4326";
    private static final String TGT = "EPSG:32633";

    private CRSCache crsCache;
    private CRSFactory crsFactory;
    private CoordinateTransformFactory transformFactory;

    private CoordinateReferenceSystem src;
    private CoordinateReferenceSystem tgt;

    /** A structurally equal but distinct instance, so equality cannot short-circuit on identity. */
    private CoordinateReferenceSystem srcCopy;
    private CoordinateReferenceSystem tgtCopy;

    private java.util.Map<String, CoordinateTransform> stringKeyedCache;
    private java.util.Map<CrsKey, CoordinateTransform> objectKeyedCache;
    private String stringKey;
    private CrsKey objectKey;

    @Setup(Level.Trial)
    public void setUp() {
        crsFactory = new CRSFactory();
        transformFactory = new CoordinateTransformFactory();
        crsCache = new CRSCache();

        src = crsFactory.createFromName(SRC);
        tgt = crsFactory.createFromName(TGT);
        srcCopy = crsFactory.createFromName(SRC);
        tgtCopy = crsFactory.createFromName(TGT);

        // Warm the CRS cache so cacheHit really is a hit.
        crsCache.createFromName(SRC);
        crsCache.createFromName(TGT);

        CoordinateTransform transform = transformFactory.createTransform(src, tgt);

        stringKey = SRC + '|' + TGT;
        stringKeyedCache = new java.util.HashMap<>();
        stringKeyedCache.put(stringKey, transform);

        objectKey = new CrsKey(src, tgt);
        objectKeyedCache = new java.util.HashMap<>();
        objectKeyedCache.put(objectKey, transform);
    }

    /** Steady state: the cache hit the consumer is on essentially always. */
    @Benchmark
    public CoordinateReferenceSystem crsCacheHit() {
        return crsCache.createFromName(SRC);
    }

    /**
     * The miss, i.e. the full 888 KB init-file scan and parse. The ratio against
     * {@link #crsCacheHit} is what the CRS cache is worth; {@code performance.md} estimates
     * 10-1000x, and this is where that number comes from.
     */
    @Benchmark
    public CoordinateReferenceSystem crsCacheMissEquivalent() {
        return crsFactory.createFromName(SRC);
    }

    /** What a transform cache would remove. There is no such cache today. */
    @Benchmark
    public CoordinateTransform createTransformUncached() {
        return transformFactory.createTransform(src, tgt);
    }

    /**
     * Object-keyed lookup: a {@code hashCode} over both CRSs' projections, datums and ellipsoids
     * followed by a full {@code equals}. Uses the distinct-but-equal copies so the comparison cannot
     * exit on reference identity - which is the realistic case, because a caller who had the
     * identical instances would already have the transform.
     */
    @Benchmark
    public CoordinateTransform objectKeyLookup() {
        return objectKeyedCache.get(new CrsKey(srcCopy, tgtCopy));
    }

    /**
     * String-pair-keyed lookup, as prescribed. {@code String.hashCode} is memoised in the object, so
     * a repeat lookup on the same key is a field read plus a length-prefixed compare.
     */
    @Benchmark
    public CoordinateTransform stringPairKeyLookup() {
        return stringKeyedCache.get(stringKey);
    }

    /**
     * The key construction cost alone, separated out because in the string design the key is built
     * once per geometry and in the object design it is the CRS pair the caller already holds.
     */
    @Benchmark
    public String buildStringKey() {
        return SRC + '|' + TGT;
    }

    /** Isolates {@code CoordinateReferenceSystem.hashCode}, which descends into the projection. */
    @Benchmark
    public int crsHashCode() {
        return src.hashCode() * 31 + tgt.hashCode();
    }

    /** Isolates {@code CoordinateReferenceSystem.equals} on distinct-but-equal instances. */
    @Benchmark
    public boolean crsEquals() {
        return src.equals(srcCopy) && tgt.equals(tgtCopy);
    }

    /**
     * A cache key over a CRS pair, kept here rather than in core: this is the shape being evaluated,
     * not the shape being shipped.
     */
    private static final class CrsKey {
        private final CoordinateReferenceSystem source;
        private final CoordinateReferenceSystem target;
        private final int hash;

        CrsKey(CoordinateReferenceSystem source, CoordinateReferenceSystem target) {
            this.source = source;
            this.target = target;
            this.hash = source.hashCode() * 31 + target.hashCode();
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof CrsKey)) {
                return false;
            }
            CrsKey other = (CrsKey) o;
            return source.equals(other.source) && target.equals(other.target);
        }
    }
}

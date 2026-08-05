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

import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.ProjCoordinate;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * <b>The consumer's actual model:</b> N threads through <b>one shared</b>
 * {@code CoordinateTransform}, cached once per executor.
 *
 * <p>{@code @State(Scope.Benchmark)} on {@link Shared} is the whole point of the file - one
 * transform instance for every thread. {@code Scope.Thread} would make this a boring linear-scaling
 * benchmark and would measure a model nobody runs.
 *
 * <p><b>A note on the annotation.</b> {@code reference/performance.md} writes
 * {@code @Threads({1,2,4,8,16})}, but {@code org.openjdk.jmh.annotations.Threads} declares
 * {@code int value()}, not {@code int[]} - JMH has no thread-count sweep annotation, and {@code -t}
 * takes one value per run. The intent is realised here as five methods with identical bodies and
 * different {@code @Threads}, which produces the whole sweep in a single JMH invocation and one JSON
 * file, which is what the gate consumes. The alternative - five separate runs with {@code -t} - would
 * put each point in a different fork and make the scaling curve incomparable.
 *
 * <p><b>This benchmark cannot prove thread safety and must not be read as doing so.</b> Sharing a
 * transform across threads is <i>unsafe today</i> for two reasons
 * ({@code reference/performance.md}, "Thread safety: the verdict"): {@code CassiniProjection} writes
 * 17 instance fields inside {@code project()}/{@code projectInverse()} and reads them back 1-4 lines
 * later, and {@code Proj4Parser.java:53} mutates the global static {@code Datum} singletons. The
 * Cassini failure mode is <b>finite, plausible, wrong</b> coordinates, which no throughput number
 * will ever reveal. Correctness is {@code SharedTransformConcurrencyTest}'s job - bitwise identity
 * against a single-threaded recording, because a tolerance assertion would pass through a torn
 * double. This file only answers "does it scale".
 *
 * <p>Scaling is expected to be near-linear once the invariant re-derivation is hoisted, because
 * nothing on the path is synchronised. Two things would show up as sub-linear scaling and are worth
 * looking for: {@code Grid.fromNadGrids} running entirely inside a global
 * {@code synchronized (Grid.class)} around blocking I/O ({@code Grid.java:318}) - only on a cold
 * path, but a cold path a Spark stage hits on every executor at once - and false sharing on the
 * non-final {@code srcGeoConv}/{@code tgtGeoConv} fields.
 *
 * <p>Uses {@code Mode.Throughput}, not {@code AverageTime}: with contention the question is
 * aggregate ops/s, and an average latency across contending threads hides whether the total went up.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 3, jvmArgsAppend = {"-XX:+UseSerialGC"})
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
public class ConcurrentThroughputBenchmark {

    /**
     * The pair to sweep. Deliberately <i>not</i> a {@code @Param} over all eight: five thread counts
     * times eight pairs times three forks is 120 forks, which is a nightly-only cost and this class
     * is in the default set. UTM is chosen because it is the most expensive projection and the one
     * with per-call allocation, so it is where allocation-induced scaling loss would appear first.
     * For a full sweep, run this class alone with {@code -p} overrides.
     */
    private static final CrsPair PAIR = CrsPair.WGS84_TO_UTM33N;

    /**
     * <b>One transform for all threads.</b> {@code Scope.Benchmark} is load-bearing; do not change it
     * to {@code Scope.Thread} to "fix" a flaky result - a flaky result here is a finding.
     */
    @State(Scope.Benchmark)
    public static class Shared {
        CoordinateTransform transform;
        double x;
        double y;
        double z;

        @Setup(Level.Trial)
        public void setUp() {
            transform = PAIR.createTransform();
            x = PAIR.x();
            y = PAIR.y();
            z = PAIR.z();
        }
    }

    /**
     * Per-thread scratch. The output coordinate must not be shared: two threads writing one
     * {@code ProjCoordinate} would tear it, and the benchmark would be measuring a data race rather
     * than the transform.
     */
    @State(Scope.Thread)
    public static class PerThread {
        ProjCoordinate in;
        ProjCoordinate out;

        @Setup(Level.Trial)
        public void setUp() {
            in = new ProjCoordinate();
            out = new ProjCoordinate();
        }
    }

    private static ProjCoordinate run(Shared shared, PerThread local) {
        local.in.setValue(shared.x, shared.y, shared.z);
        return shared.transform.transform(local.in, local.out);
    }

    @Benchmark
    @Threads(1)
    public ProjCoordinate threads01(Shared shared, PerThread local) {
        return run(shared, local);
    }

    @Benchmark
    @Threads(2)
    public ProjCoordinate threads02(Shared shared, PerThread local) {
        return run(shared, local);
    }

    @Benchmark
    @Threads(4)
    public ProjCoordinate threads04(Shared shared, PerThread local) {
        return run(shared, local);
    }

    @Benchmark
    @Threads(8)
    public ProjCoordinate threads08(Shared shared, PerThread local) {
        return run(shared, local);
    }

    @Benchmark
    @Threads(16)
    public ProjCoordinate threads16(Shared shared, PerThread local) {
        return run(shared, local);
    }
}

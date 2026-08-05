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
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * <b>The regression baseline.</b> One {@code CoordinateTransform.transform} call in the exact shape
 * the consumer uses.
 *
 * <p>"Exact shape" is load-bearing and is why {@link #transform} allocates. The consumer runs
 * per-vertex inside a Spark executor: a fresh {@link ProjCoordinate} is constructed for each
 * vertex read out of the geometry, and one output coordinate is reused across the whole geometry.
 * A benchmark that reused the input too would measure a shape nobody runs and would report 0 B/op,
 * hiding the single largest allocation on the path.
 *
 * <p>So the Tier 1 expectation for this class is <b>{@code <= 40 B/op}</b>: exactly one
 * {@code ProjCoordinate} (12-byte header + three doubles, padded to 40 under compressed oops) and
 * nothing else. Anything above 40 is proj4j allocating internally - the two {@code new double[1]}
 * out-params in {@code ExtendedTransverseMercatorProjection}, the per-call
 * {@code GeocentricConverter} in {@code GeocentProjection}, a {@code Grid.shift} iterator. That is
 * the regression class Tier 1 exists to catch, and the reason the gate ratchets rather than
 * asserting 40 outright today.
 *
 * <p>{@link #transformReusedInput} is the control: identical work, no per-call allocation. The
 * difference between the two is the consumer's coordinate-object tax, and it is worth knowing
 * separately from proj4j's own.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Fork(value = 3, jvmArgsAppend = {"-XX:+UseSerialGC"})
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
public class SinglePointTransformBenchmark {

    /** Bare {@code @Param}: JMH expands this to all eight {@link CrsPair} constants. */
    @Param
    public CrsPair pair;

    private CoordinateTransform transform;
    private double x;
    private double y;
    private double z;

    /** Reused across calls, as the consumer reuses one output coordinate per geometry. */
    private ProjCoordinate out;

    /** Reused input, for the {@link #transformReusedInput} control only. */
    private ProjCoordinate reusedIn;

    @Setup(Level.Trial)
    public void setUp() {
        transform = pair.createTransform();
        x = pair.x();
        y = pair.y();
        z = pair.z();
        out = new ProjCoordinate();
        reusedIn = new ProjCoordinate(x, y, z);

        // Fail fast, in setup rather than in a measurement iteration: a throwing benchmark reports
        // as a JMH error with no usable diagnostic, and an exception on the hot path would be
        // measuring fillInStackTrace (1-10 us) instead of the transform.
        transform.transform(reusedIn, out);
    }

    /**
     * The gated shape. Fresh input, reused output.
     *
     * <p>Returning {@code out} rather than consuming it with a {@code Blackhole} is intentional:
     * JMH treats a returned reference as consumed, and a {@code Blackhole} parameter would add its
     * own (small, but nonzero and version-dependent) cost to a number that Tier 1 compares against
     * a checked-in byte count.
     */
    @Benchmark
    public ProjCoordinate transform() {
        ProjCoordinate in = new ProjCoordinate(x, y, z);
        return transform.transform(in, out);
    }

    /** Control: the same transform with no caller-side allocation at all. */
    @Benchmark
    public ProjCoordinate transformReusedInput() {
        return transform.transform(reusedIn, out);
    }
}

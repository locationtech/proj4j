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

import org.locationtech.proj4j.BulkCoordinateTransform;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.ProjCoordinate;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The bulk API, and the single-point loop it is meant to replace, measured in the same fork.
 *
 * <p><b>Formerly staged, now live.</b> This class used to sit in {@code ...benchmark.staged} and
 * reach the bulk methods through a reflective bridge, because
 * {@link org.locationtech.proj4j.BulkCoordinateTransform} did not exist. It does; the bridge and
 * the whole staged package are gone, the import is the real interface, and {@code run-gate.sh} no
 * longer passes {@code -e '\.staged\.'}. The consequence is the point of the change: <b>the bulk
 * path had no allocation ratchets at all</b> while it was excluded, so the contract that a bulk
 * method allocates zero bytes per point was normative and unmeasured. It is now gated.
 *
 * <h2>Why the control lives in this class</h2>
 *
 * <p>{@link #loopOfSinglePoint} is <b>the method that proves any batch win is real</b>. It needs no
 * bulk API - it only calls today's {@code CoordinateTransform} - but it belongs here rather than in
 * {@code SinglePointTransformBenchmark} because the claim being tested is a <i>ratio</i>: batch
 * ops/s divided by loop ops/s, measured in the <b>same JMH fork</b>, so that machine speed, turbo
 * state and noisy neighbours cancel. A control measured in a different fork on a different day
 * answers a different question.
 *
 * <h2>What the arms are for</h2>
 *
 * <p>{@code reference/performance.md} attributes the estimated 2-4x to four separable mechanisms, and
 * the arms are arranged to attribute the measured win to them rather than reporting one aggregate:
 * <ul>
 *   <li>{@link #loopOfSinglePoint} vs {@link #interleaved2DInPlace} - hoisting loop invariants and
 *       megamorphic-to-monomorphic dispatch, together. This is the headline ratio.</li>
 *   <li>{@link #interleaved2DInPlace} vs {@link #structOfArrays} - memory layout alone. Same work,
 *       same invariant hoisting, different array shape. {@code performance.md} calls SoA "the
 *       vectorisation-friendly shape and the fastest variant" and estimates 5-15% at 1e5 points; this
 *       pair is where that estimate becomes a measurement.</li>
 *   <li>{@link #interleaved2DInPlace} vs {@link #interleaved3DInPlace} - the cost of carrying a
 *       height, which for the Helmert pairs is not free.</li>
 *   <li>{@link #interleaved2DSrcToDst} vs {@link #interleaved2DInPlace} - whether aliasing
 *       {@code src == dst} is actually cheaper, or whether the in-place variant just forces a
 *       read-modify-write the separate-array form avoids.</li>
 *   <li>{@link #interleaved2DNullStatus} vs {@link #interleaved2DInPlace} - the cost of the status
 *       array. The contract says a non-null status must be <b>zero allocation</b>, so this pair
 *       should differ by one byte store per point and nothing else. If it differs by more, the status
 *       write is not on the fast path it is supposed to be on.</li>
 *   <li>{@link #nonUnitStride} - a stride of 4 over a buffer that also carries M and a flag, which is
 *       the shape a real geometry library hands over. Confirms the loop does not silently depend on
 *       {@code stride == 2}.</li>
 * </ul>
 *
 * <p><b>{@code @OperationsPerInvocation}</b> normalises every arm to per-point, which is what makes
 * the ratios directly readable and what makes {@code gc.alloc.rate.norm} come out as
 * <i>bytes per point</i>. Tier 1 asserts <b>every bulk method at 0 B/op</b>; without this annotation
 * the assertion would be "0 bytes per batch", which is the same thing only by luck.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Fork(value = 3, jvmArgsAppend = {"-XX:+UseSerialGC"})
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@OperationsPerInvocation(BulkTransformBenchmark.NUM_POINTS)
public class BulkTransformBenchmark {

    /**
     * The consumer's shape: "up to 100,000 {@code transform} calls for one geometry". 100,000 points
     * of interleaved XY is 1.6 MB, which is past L2 on most runners and therefore includes the memory
     * behaviour that the layout arms exist to measure. A batch small enough to sit in L1 would report
     * a layout win of zero.
     */
    public static final int NUM_POINTS = 100_000;

    @Param
    public CrsPair pair;

    private CoordinateTransform singlePoint;
    private BulkCoordinateTransform bulk;

    /** Interleaved XY, stride 2. */
    private double[] xy;
    /** Interleaved XYZ, stride 3. */
    private double[] xyz;
    /** Interleaved XY with two extra ordinates per point, stride 4. */
    private double[] xyStride4;
    /** Destination for the src-to-dst arm. */
    private double[] xyDst;

    /** Struct-of-arrays. */
    private double[] xs;
    private double[] ys;
    private double[] zs;

    /** Caller-owned, reused. Never reallocated - that is the contract. */
    private byte[] status;

    /** For the control only. */
    private ProjCoordinate in;
    private ProjCoordinate out;

    @Setup(Level.Trial)
    public void setUp() {
        singlePoint = pair.createTransform();
        // A cast, not a reflective bridge: BasicCoordinateTransform implements the interface. The
        // check is kept so that a factory returning some other CoordinateTransform fails in
        // @Setup, which JMH reports as an error with the message attached, rather than inside a
        // measured method where it would be reported as a timing of fillInStackTrace.
        if (!(singlePoint instanceof BulkCoordinateTransform)) {
            throw new IllegalStateException(pair + ": " + singlePoint.getClass().getName()
                    + " does not implement BulkCoordinateTransform, so the bulk arms would measure"
                    + " nothing.");
        }
        bulk = (BulkCoordinateTransform) singlePoint;

        xy = new double[NUM_POINTS * 2];
        xyz = new double[NUM_POINTS * 3];
        xyStride4 = new double[NUM_POINTS * 4];
        xyDst = new double[NUM_POINTS * 2];
        xs = new double[NUM_POINTS];
        ys = new double[NUM_POINTS];
        zs = new double[NUM_POINTS];
        status = new byte[NUM_POINTS];

        in = new ProjCoordinate();
        out = new ProjCoordinate();

        fill();
    }

    /**
     * Refills the buffers before each measured invocation.
     *
     * <p>Necessary because the in-place arms overwrite their input, and transforming already-projected
     * coordinates would either measure a different regime or fail. {@code Level.Invocation} setup has
     * a documented accuracy cost in JMH, which is exactly why {@code NUM_POINTS} is 100,000: the fill
     * is a linear pass over memory the transform is about to walk anyway, so it is a small fraction of
     * a batch this size. Do <b>not</b> reduce {@code NUM_POINTS} without moving the fill.
     */
    @Setup(Level.Invocation)
    public void fill() {
        // A small deterministic spread rather than one repeated point: a single value would let the
        // branch predictor and any future per-point cache see an unrealistically easy input, and for
        // the iterative-inverse pairs it would fix the trip count at one value.
        final double baseX = pair.x();
        final double baseY = pair.y();
        final double spreadX = pair.sourceCode().equals("EPSG:32633") ? 100.0 : 1.0e-3;
        final double spreadY = pair.sourceCode().equals("EPSG:32633") ? 100.0 : 1.0e-3;
        for (int i = 0; i < NUM_POINTS; i++) {
            double px = baseX + ((i % 101) - 50) * spreadX;
            double py = baseY + ((i % 97) - 48) * spreadY;
            double pz = pair.z();
            xy[2 * i] = px;
            xy[2 * i + 1] = py;
            xyz[3 * i] = px;
            xyz[3 * i + 1] = py;
            xyz[3 * i + 2] = pz;
            xyStride4[4 * i] = px;
            xyStride4[4 * i + 1] = py;
            xyStride4[4 * i + 2] = i;      // an M ordinate the transform must not touch
            xyStride4[4 * i + 3] = 0.0;    // a flag the transform must not touch
            xs[i] = px;
            ys[i] = py;
            zs[i] = pz;
        }
    }

    /**
     * <b>The control.</b> N calls to today's single-point API over the same buffer, so the batch arms
     * are compared against the thing they are meant to replace, in the same fork.
     *
     * <p>Reuses one input and one output coordinate, so this arm measures proj4j's per-call cost with
     * the caller's allocation removed. The caller's allocation is measured separately by
     * {@code SinglePointTransformBenchmark}; adding it here would fold two effects into the ratio.
     */
    @Benchmark
    public double loopOfSinglePoint() {
        double sink = 0.0;
        for (int i = 0; i < NUM_POINTS; i++) {
            in.setValue(xy[2 * i], xy[2 * i + 1]);
            singlePoint.transform(in, out);
            xy[2 * i] = out.x;
            xy[2 * i + 1] = out.y;
            sink += out.x;
        }
        return sink;
    }

    /** The headline arm: interleaved, in place, stride 2, status recorded. */
    @Benchmark
    public int interleaved2DInPlace() {
        return bulk.transform2D(xy, 0, NUM_POINTS, 2, status);
    }

    /** Fail-fast variant. Isolates the cost of the status byte store. */
    @Benchmark
    public int interleaved2DNullStatus() {
        return bulk.transform2D(xy, 0, NUM_POINTS, 2, null);
    }

    /** Stride 3, carrying a real height through the datum step. */
    @Benchmark
    public int interleaved3DInPlace() {
        return bulk.transform3D(xyz, 0, NUM_POINTS, 3, status);
    }

    /** Separate source and destination, both stride 2. */
    @Benchmark
    public int interleaved2DSrcToDst() {
        return bulk.transform2D(xy, 0, 2, xyDst, 0, 2, NUM_POINTS, status);
    }

    /**
     * Stride 4 over a buffer carrying M and a flag. Confirms the loop honours the stride and does not
     * clobber the untouched ordinates - correctness that gate D asserts, measured here because a
     * stride-agnostic loop and a stride-2-specialised one have very different memory behaviour.
     */
    @Benchmark
    public int nonUnitStride() {
        return bulk.transform2D(xyStride4, 0, NUM_POINTS, 4, status);
    }

    /** Struct-of-arrays: the layout arm. */
    @Benchmark
    public int structOfArrays() {
        return bulk.transform(xs, ys, zs, 0, NUM_POINTS, status);
    }

    /** SoA with no height, which is the common 2D case and should be strictly cheaper. */
    @Benchmark
    public int structOfArraysNoZ() {
        return bulk.transform(xs, ys, null, 0, NUM_POINTS, status);
    }
}

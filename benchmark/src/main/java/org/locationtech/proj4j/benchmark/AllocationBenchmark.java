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
import org.openjdk.jmh.annotations.Warmup;

/**
 * <b>The Tier 1 subject.</b> One method per known or historical allocation hot spot, each with a
 * pinned {@code gc.alloc.rate.norm} entry in {@code baseline/allocation-baseline.json}.
 *
 * <p>These methods exist to be gated, not to be fast. Their ns/op numbers are close to
 * meaningless in isolation - what matters is that {@code -prof gc} reports a byte count that is a
 * property of the bytecode and therefore does not flake on a shared CI runner. Each method names
 * the specific regression it is a tripwire for; if a method here stops corresponding to a real
 * hazard, delete it rather than leaving a baseline entry that nothing can violate.
 *
 * <p>Every method reuses its output coordinate, so a nonzero reading is proj4j's allocation and
 * not the caller's. That is the opposite convention to {@link SinglePointTransformBenchmark}, which
 * deliberately includes the caller's {@code ProjCoordinate}; the two together separate the two
 * sources.
 *
 * <p><b>Run with a serial collector.</b> {@code gc.alloc.rate.norm} is derived from thread
 * allocation counters and is collector-independent, but TLAB sizing under a concurrent collector
 * adds sampling jitter to the profiler's own bookkeeping. {@code -XX:+UseSerialGC} in
 * {@code jvmArgsAppend} makes the number bit-stable run to run, which is what a blocking gate
 * needs.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgsAppend = {"-XX:+UseSerialGC"})
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
public class AllocationBenchmark {

    private CoordinateTransform identity;
    private CoordinateTransform webMercator;
    private CoordinateTransform utm33n;
    private CoordinateTransform utmToWebMercator;
    private CoordinateTransform geocentric;
    private CoordinateTransform osgb36;
    private CoordinateTransform nadGridShift;

    private CoordinateReferenceSystem wgs84;
    private CoordinateReferenceSystem utm33nCrs;

    private ProjCoordinate in;
    private ProjCoordinate inUtm;
    private ProjCoordinate inNad;
    private ProjCoordinate out;

    @Setup(Level.Trial)
    public void setUp() {
        identity = CrsPair.WGS84_TO_WGS84.createTransform();
        webMercator = CrsPair.WGS84_TO_WEBMERCATOR.createTransform();
        utm33n = CrsPair.WGS84_TO_UTM33N.createTransform();
        utmToWebMercator = CrsPair.UTM33N_TO_WEBMERCATOR.createTransform();
        geocentric = CrsPair.WGS84_TO_GEOCENTRIC.createTransform();
        osgb36 = CrsPair.WGS84_TO_OSGB36.createTransform();
        nadGridShift = CrsPair.NAD27_TO_NAD83.createTransform();

        CRSFactory crsFactory = new CRSFactory();
        wgs84 = crsFactory.createFromName("EPSG:4326");
        utm33nCrs = crsFactory.createFromName("EPSG:32633");

        in = new ProjCoordinate(15.0, 47.4, 100.0);
        inUtm = new ProjCoordinate(500000.0, 5250000.0, 100.0);
        // gridShiftDispatch needs the NAD27 pair's own sample point. Sharing `in` fed 15E 47.4N -
        // Austria - into a NAD27 -> NAD83 grid shift, which its javadoc never claimed and which no
        // North American grid covers. It was silently a no-op until the fail-closed API turned it
        // into "grid shift: (14.999999999999998, 47.4) is outside every grid of
        // [conus, alaska, ntv1_can.dat]" and killed the arm.
        inNad = new ProjCoordinate(
                CrsPair.NAD27_TO_NAD83.x(), CrsPair.NAD27_TO_NAD83.y(), CrsPair.NAD27_TO_NAD83.z());
        out = new ProjCoordinate();
    }

    /**
     * The envelope. Target 0 B/op: {@code BasicCoordinateTransform.transform} on an identity pair
     * writes into {@code tgt} and touches nothing else.
     *
     * <p>A nonzero reading here means the <i>envelope itself</i> started allocating - a lambda
     * capture, an autoboxed key, a stream. That is the cheapest possible regression to fix and the
     * most expensive to notice any other way.
     */
    @Benchmark
    public ProjCoordinate identityEnvelope() {
        return identity.transform(in, out);
    }

    /** Cheapest real projection. Target 0 B/op. */
    @Benchmark
    public ProjCoordinate webMercatorForward() {
        return webMercator.transform(in, out);
    }

    /**
     * The etmerc scratch-array tripwire. <b>Target reached: the ratchet is 0 B/op.</b>
     *
     * <p><b>Two things this javadoc used to say are no longer true, and both are recorded because a
     * tripwire whose stated subject has moved is a tripwire nobody re-reads.</b> It read:
     * <em>"Tripwire for {@code ExtendedTransverseMercatorProjection}'s two {@code new double[1]}
     * out-params per {@code project} and per {@code projectInverse} ({@code :114-115},
     * {@code :157-158}). Target 0 B/op once those become fields or locals; the ratchet records
     * whatever it is today."</em>
     *
     * <ol>
     * <li><b>The {@code double[1]} out-params are gone.</b> What is left is one
     *     {@code final double[2] dC} per direction ({@code :262}, {@code :314}), which C2 scalar
     *     replaces — this arm and
     *     {@code SinglePointTransformBenchmark.transformReusedInput[WGS84_TO_UTM33N]} both ratchet
     *     at <b>0</b>.
     * <li><b>This arm no longer names its class correctly.</b> The fixture is
     *     {@code EPSG:32633} = {@code +proj=utm +zone=33}, and {@code Registry:499} binds
     *     {@code utm} to {@code TransverseMercatorProjection}, not to the extended class. etmerc
     *     still runs — {@code TransverseMercatorProjection} holds an
     *     {@code ExtendedTransverseMercatorProjection exact} ({@code :137}) and defaults to
     *     {@code Algorithm.PODER_ENGSAGER} ({@code :140}) — but <b>through a delegate</b>. A
     *     regression that moved {@code utm} onto {@code +approx} would change what this arm
     *     measures without changing its name.
     * </ol>
     *
     * <p>Kept as a tripwire, not deleted: 0 is the contract now, and a reintroduced per-call array
     * on either side of the delegation shows up here first.
     */
    @Benchmark
    public ProjCoordinate utmOutParamArrays() {
        return utm33n.transform(in, out);
    }

    /**
     * The geocentric-converter tripwire. <b>Target reached: the ratchet is 0 B/op.</b>
     *
     * <p>This javadoc used to read <em>"Tripwire for {@code GeocentProjection.java:10,17}, which
     * constructs a <b>new {@code GeocentricConverter} per call</b>. Target 0 B/op once the
     * converter is hoisted to a final field."</em> <b>It is fixed, and not in the way the note
     * proposed</b> — a plain {@code final} field would have been wrong, because
     * {@code Projection.setEllipsoid} can change the ellipsoid after construction. What landed is
     * {@code private volatile Cached cached} keyed on the ellipsoid
     * ({@code GeocentProjection.java:145-165}), where {@code Cached} is immutable and the write is
     * idempotent: two threads may each build an equivalent converter and one wins, and neither can
     * observe a partially built one. It is the single remaining self-field write anywhere on the
     * projection hot path.
     */
    @Benchmark
    public ProjCoordinate geocentricConverterPerCall() {
        return geocentric.transform(in, out);
    }

    /**
     * The 7-parameter Helmert path, which round-trips through {@code cart} forward and inverse.
     * Target 0 B/op. This is also where a reintroduced intermediate coordinate object would show
     * up, because the datum step is the one place the envelope legitimately needs scratch space.
     */
    @Benchmark
    public ProjCoordinate helmertCartRoundTrip() {
        return osgb36.transform(in, out);
    }

    /**
     * Tripwire for the {@code Grid.shift} iterator. {@code Grid.java:89} does
     * {@code for (Grid g : grids)} over a {@code List} field, and escape analysis will not reliably
     * remove that iterator allocation - {@code reference/performance.md} calls for a {@code Grid[]}.
     * Target 0 B/op.
     *
     * <p>This runs the {@link CrsPair#NAD27_TO_NAD83} sample point, 96W 39N, which is inside CTABLE
     * V2 {@code conus}, so it covers the iterator <i>and</i> the interpolation. It used to be
     * documented as dispatch-only on the grounds that the point was outside {@code ntv1_can.dat};
     * two things changed - {@code grids-us-legacy} vendored {@code conus}, and this arm was in fact
     * passing the shared {@code in} field (15E 47.4N, Austria) rather than the NAD27 point at all.
     * {@link GridShiftBenchmark#noGridHit()} is where dispatch is isolated from interpolation.
     */
    @Benchmark
    public ProjCoordinate gridShiftDispatch() {
        return nadGridShift.transform(inNad, out);
    }

    /**
     * The transform-cache lookup path. Target 0 B/op.
     *
     * <p>Guards a fix that has already landed: {@code Projection.hashCode} used to call
     * {@code Objects.hash}, which allocates an {@code Object[18]} and boxes twelve doubles on every
     * cache lookup, and is now a manual 31-chain. {@code PrimeMeridian.hashCode} still does
     * {@code new Double(offsetFromGreenwich).hashCode()} ({@code PrimeMeridian.java:92-93}), so
     * expect a small nonzero reading until that is also fixed - the ratchet records it and will not
     * let it grow.
     */
    @Benchmark
    public int crsHashCode() {
        return wgs84.hashCode() + utm33nCrs.hashCode();
    }

    /**
     * Projected-to-projected, no datum work: an inverse and a forward projection back to back.
     * Target 0 B/op. Input is a UTM 33N easting/northing, not degrees.
     */
    @Benchmark
    public ProjCoordinate projToProj() {
        return utmToWebMercator.transform(inUtm, out);
    }
}

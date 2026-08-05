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
package org.locationtech.proj4j.benchmark.ab;

import java.util.concurrent.TimeUnit;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
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
 * <b>The whole of the A/B module.</b> Three arms covering the three things a consumer of proj4j
 * actually spends time in, parameterised over seven CRS pairs, using only API that exists in both
 * this fork and released {@code org.locationtech.proj4j:proj4j:1.4.3}.
 *
 * <p>Everything here is deliberately dull. The interesting benchmarks - allocation profiling, the
 * bulk API, grid-shift interpolation - all need fork-only types or a grids artifact that has no
 * 1.4.3 counterpart, so they stay in {@code benchmark/}. This class exists so that a number from
 * the fork and a number from 1.4.3 are comparable at all, and that requires the same source file to
 * compile against both. If a change here stops compiling under {@code -Pbench-ab-baseline}, the
 * benchmark is wrong, not the profile.
 *
 * <p><b>Why these seven pairs and not {@code benchmark/}'s eight.</b> They are the same EPSG codes
 * as {@code org.locationtech.proj4j.benchmark.CrsPair}, minus {@code NAD27_TO_NAD83}. That pair
 * needs a reachable NADCON grid; this module has no grids dependency because there is no
 * {@code proj4j-grids-us-legacy} at 1.4.3 to swap to, and with no grid the fork's fail-closed API
 * throws rather than quietly returning an unshifted coordinate. An arm that throws on one side of an
 * A/B is worse than an absent arm, because JMH reports it as an error with no usable number and the
 * two runs stop enumerating the same set.
 *
 * <p><b>Reading the results.</b> The fork changed algorithms, not only implementations:
 * {@code tmerc} now defaults to Poder/Engsager rather than the truncated series, and Karney
 * auxiliary-latitude machinery is wired into {@code tmerc poly laea cea aea etmerc stere}. The
 * {@code UTM33N} and {@code ALBERS_CONUS} arms therefore do <i>different</i> work on the two sides,
 * and a larger ns/op there is not on its own a regression. Report per arm; a single blended figure
 * over this class would be meaningless.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Fork(value = 3, jvmArgsAppend = {"-XX:+UseSerialGC"})
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
public class AbBenchmark {

    /**
     * The seven pairs, as a nested enum so that a bare {@code @Param} expands to all of them and the
     * two builds cannot drift into enumerating different arms.
     *
     * <p>Codes and sample points are copied verbatim from
     * {@code org.locationtech.proj4j.benchmark.CrsPair} so that a figure here can be placed
     * alongside a Tier 1/Tier 2 figure from {@code benchmark/} without re-deriving anything. The
     * per-pair rationale lives there; only what differs is repeated here.
     */
    public enum Pair {

        /** The floor: transform envelope only, no projection and an identity datum shift. */
        WGS84_TO_WGS84("EPSG:4326", "EPSG:4326", 8.5, 47.4, 100.0),

        /** Cheapest real projection - spherical {@code merc}, no datum shift. */
        WGS84_TO_WEBMERCATOR("EPSG:4326", "EPSG:3857", 8.5, 47.4, 100.0),

        /** {@code +proj=utm}. One of the two arms whose <i>algorithm</i> differs between the sides. */
        WGS84_TO_UTM33N("EPSG:4326", "EPSG:32633", 15.0, 47.4, 100.0),

        /** Projected to projected, same datum: inverse then forward, no datum work. Input is metres. */
        UTM33N_TO_WEBMERCATOR("EPSG:32633", "EPSG:3857", 500000.0, 5250000.0, 100.0),

        /** OSGB36 - a real 7-parameter Helmert, so a full geocentric round trip. */
        WGS84_TO_OSGB36("EPSG:4326", "EPSG:27700", -2.0, 52.0, 100.0),

        /** Albers. The other changed-algorithm arm: iterative {@code authlat} vs a Clenshaw series. */
        WGS84_TO_ALBERS_CONUS("EPSG:4326", "EPSG:5070", -96.0, 39.0, 100.0),

        /** {@code +proj=geocent}. */
        WGS84_TO_GEOCENTRIC("EPSG:4326", "EPSG:4978", 8.5, 47.4, 100.0);

        private final String sourceCode;
        private final String targetCode;
        private final double x;
        private final double y;
        private final double z;

        Pair(String sourceCode, String targetCode, double x, double y, double z) {
            this.sourceCode = sourceCode;
            this.targetCode = targetCode;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public String sourceCode() {
            return sourceCode;
        }

        public String targetCode() {
            return targetCode;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        public double z() {
            return z;
        }
    }

    /** Bare {@code @Param}: JMH expands this to all seven {@link Pair} constants. */
    @Param
    public Pair pair;

    /**
     * Fresh per trial, not per invocation. {@code CRSFactory} holds a process-wide {@code CRSCache}
     * in both versions, so {@link #createCrs} measures a warm cache lookup either way; constructing
     * a new factory per call would only add an object allocation on top of the same lookup.
     */
    private CRSFactory crsFactory;
    private CoordinateTransformFactory transformFactory;

    private CoordinateReferenceSystem source;
    private CoordinateReferenceSystem target;
    private CoordinateTransform transform;

    private double x;
    private double y;
    private double z;

    /** Reused across calls, as a consumer reuses one output coordinate per geometry. */
    private ProjCoordinate out;

    @Setup(Level.Trial)
    public void setUp() {
        crsFactory = new CRSFactory();
        transformFactory = new CoordinateTransformFactory();

        source = crsFactory.createFromName(pair.sourceCode());
        target = crsFactory.createFromName(pair.targetCode());
        transform = transformFactory.createTransform(source, target);

        x = pair.x();
        y = pair.y();
        z = pair.z();
        out = new ProjCoordinate();

        // Fail fast here rather than in a measurement iteration: a throwing benchmark reports as a
        // JMH error with no usable number, and an exception on the hot path would be measuring
        // fillInStackTrace instead of the transform.
        transform.transform(new ProjCoordinate(x, y, z), out);
    }

    /**
     * CRS resolution by EPSG code - what a consumer pays once per distinct code, and the arm most
     * sensitive to how the registry is indexed and cached.
     *
     * <p>The <i>target</i> code, not the source: five of the seven pairs share {@code EPSG:4326} as
     * their source, so measuring that would collapse most of the parameterisation into one number.
     */
    @Benchmark
    public CoordinateReferenceSystem createCrs() {
        return crsFactory.createFromName(pair.targetCode());
    }

    /**
     * Transform construction from two already-resolved CRSs: datum comparison, projection wiring,
     * and whatever plan the implementation builds up front. Paid once per source/target combination.
     */
    @Benchmark
    public CoordinateTransform createTransform() {
        return transformFactory.createTransform(source, target);
    }

    /**
     * <b>The hot path.</b> One point through a transform built in {@link #setUp}, in the shape a
     * consumer runs it: a fresh input coordinate per vertex, one output coordinate reused for the
     * whole geometry.
     *
     * <p>The input allocation is deliberate and is not noise to be optimised away - it is the single
     * largest allocation on the consumer's per-vertex path, and a version that reused the input
     * would measure a shape nobody runs. Returning {@code out} rather than taking a
     * {@code Blackhole} keeps the measurement free of the sink's own cost.
     */
    @Benchmark
    public ProjCoordinate transform() {
        ProjCoordinate in = new ProjCoordinate(x, y, z);
        return transform.transform(in, out);
    }
}

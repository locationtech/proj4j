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

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.datum.Grid;
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
 * Datum grid shift: the dispatch overhead, the real NTv1 interpolation, and the iterative inverse.
 *
 * <p><b>The state of the world, measured, because it determines what these numbers mean.</b> Only
 * one grid ships: {@code proj4/nad/ntv1_can.dat} (1.06 MB, Canada). {@code Datum.NAD27} is declared
 * with {@code Grid.fromNadGrids("@conus,@alaska,@ntv2_0.gsb,@ntv1_can.dat")} and the three optional
 * {@code @} entries resolve to nothing, so NAD27 is typed {@code TYPE_GRIDSHIFT} carrying exactly
 * one grid. Consequences:
 * <ul>
 *   <li>At a CONUS point (96W 39N) the shift is a <b>near-no-op</b>: the full dispatch runs - list
 *       iterator, per-grid extent test, {@code nad_cvt} entry - and then falls out because the point
 *       is outside the Canadian grid. Measured residual 9.2e-10 deg, the identity to 0.1 mm. That is
 *       {@link #noGridHit}, and it is what {@link CrsPair#NAD27_TO_NAD83} measures too.</li>
 *   <li>At a Canadian point (75W 45N) the grid <b>does</b> apply: measured shift
 *       (-4.68e-05, -3.63e-04) deg, about 40 m. That is {@link #forwardShift} and
 *       {@link #inverseShift}, and it is the only real grid interpolation available in this
 *       repository today.</li>
 * </ul>
 * So {@code noGridHit} is the overhead floor and {@code forwardShift} minus it is the interpolation.
 * <b>Do not quote {@code noGridHit} as the cost of a grid shift.</b> When real CONUS grids ship, add
 * a NAD27-to-NAD83 CONUS arm and this comment becomes wrong - fix it then.
 *
 * <p><b>Tier 1 pins {@link #inverseShift} at 0 B/op</b>, and it is the sharpest allocation target in
 * the library: {@code performance.md} measures the inverse grid-shift path at <b>up to 49
 * allocations per vertex</b>, which is 4.9 million objects for one 100,000-vertex geometry. Two
 * distinct causes, both fixable without touching the arithmetic: {@code Grid.shift} iterating a
 * {@code List} field with a for-each ({@code Grid.java:89}), and {@code nad_cvt}/{@code nad_intr}
 * returning fresh {@code PolarCoordinate} objects per trip of a {@code MAX_TRY = 9} loop.
 *
 * <p>{@link #inverseShift} is also the only arm here whose cost is genuinely data-dependent: the
 * inverse is a fixed-point iteration bounded at {@code MAX_TRY = 9} with {@code TOL = 1e-12}, so its
 * trip count varies with the local gradient of the shift field. The sample point is pinned for that
 * reason.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Fork(value = 3, jvmArgsAppend = {"-XX:+UseSerialGC"})
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
public class GridShiftBenchmark {

    /** Inside {@code ntv1_can.dat}: Ottawa-ish. Verified to produce a ~40 m shift. */
    private static final double IN_GRID_LON_DEG = -75.0;
    private static final double IN_GRID_LAT_DEG = 45.0;

    /** Outside {@code ntv1_can.dat}: Kansas. Verified to fall through as the identity. */
    private static final double OUT_OF_GRID_LON_DEG = -96.0;
    private static final double OUT_OF_GRID_LAT_DEG = 39.0;

    private List<Grid> grids;

    private CoordinateTransform nad27ToNad83;
    private ProjCoordinate outOfGridIn;
    private ProjCoordinate transformOut;

    /**
     * {@code Grid.shift} mutates its argument in place, so each invocation must start from a fresh
     * value. Writing the two doubles into a reused coordinate is the cheapest way to do that and
     * keeps the arm at zero caller-side allocation, which Tier 1 depends on.
     */
    private ProjCoordinate scratch;

    private double inGridLonRad;
    private double inGridLatRad;
    private double outOfGridLonRad;
    private double outOfGridLatRad;

    @Setup(Level.Trial)
    public void setUp() throws IOException {
        // Not "@ntv1_can.dat": the leading @ makes the grid optional, and an optional grid that
        // fails to load yields an empty list and a silently-identity benchmark. Loading it as
        // mandatory means a packaging regression fails setup instead of quietly measuring nothing.
        grids = Grid.fromNadGrids("ntv1_can.dat");
        if (grids == null || grids.isEmpty()) {
            throw new IllegalStateException(
                    "ntv1_can.dat did not load; proj4j-epsg is missing from the runtime classpath.");
        }

        inGridLonRad = Math.toRadians(IN_GRID_LON_DEG);
        inGridLatRad = Math.toRadians(IN_GRID_LAT_DEG);
        outOfGridLonRad = Math.toRadians(OUT_OF_GRID_LON_DEG);
        outOfGridLatRad = Math.toRadians(OUT_OF_GRID_LAT_DEG);

        scratch = new ProjCoordinate();

        nad27ToNad83 = CrsPair.NAD27_TO_NAD83.createTransform();
        outOfGridIn = new ProjCoordinate(OUT_OF_GRID_LON_DEG, OUT_OF_GRID_LAT_DEG, 0.0);
        transformOut = new ProjCoordinate();

        // Assert the two regimes really are the two regimes, so a data change cannot turn this file
        // into two copies of the same measurement without anyone noticing.
        scratch.setValue(inGridLonRad, inGridLatRad);
        Grid.shift(grids, false, scratch);
        if (Math.abs(scratch.x - inGridLonRad) < 1e-12) {
            throw new IllegalStateException("forwardShift's sample point no longer hits the grid.");
        }
        // The miss regime is now asserted by the throw, not by an unchanged coordinate. Grid.shift
        // fails closed (Grid.java:518 -> outsideGrid), so a point outside every grid raises
        // CrsTransformException instead of falling through as a silent identity. Asserting the throw
        // is strictly the stronger check: the old form passed both when the point missed the grid
        // AND when the grid failed to load and the shift was vacuous.
        scratch.setValue(outOfGridLonRad, outOfGridLatRad);
        try {
            Grid.shift(grids, false, scratch);
            throw new IllegalStateException("noGridHit's sample point is now inside the grid.");
        } catch (CrsTransformException expected) {
            // Correct: 96W 39N is outside ntv1_can.dat.
        }
    }

    /** Real NTv1 bilinear interpolation: extent test, cell index, four-corner blend. */
    @Benchmark
    public ProjCoordinate forwardShift() {
        scratch.setValue(inGridLonRad, inGridLatRad);
        Grid.shift(grids, false, scratch);
        return scratch;
    }

    /**
     * The fixed-point inverse, {@code MAX_TRY = 9} / {@code TOL = 1e-12}. <b>Tier 1 pins this arm at
     * 0 B/op.</b>
     */
    @Benchmark
    public ProjCoordinate inverseShift() {
        scratch.setValue(inGridLonRad, inGridLatRad);
        Grid.shift(grids, true, scratch);
        return scratch;
    }

    /**
     * The dispatch path with no interpolation: extent test, iterator, and then the miss.
     *
     * <p><b>Under the fail-closed API this arm measures the THROW, not a cheap fall-through</b>, and
     * that reframes what its number means. It used to be the floor - dispatch overhead and nothing
     * else. {@code Grid.shift} now raises {@code CrsTransformException} on a miss (correctly: a
     * failure must never be expressed as a plausible coordinate), so what is timed here is dispatch
     * plus exception construction, and {@code reference/performance.md} prices a
     * {@code fillInStackTrace} at <b>1-10 microseconds</b> - two to three orders above the dispatch
     * it was meant to isolate.
     *
     * <p>That makes this the direct measurement of performance.md's sharpest rule ("No exceptions on
     * the hot path"), and the number to watch when {@code Proj4jException.fillInStackTrace} is
     * overridden to return {@code this}. <b>Its Tier 1 rule still carries {@code targetBytesPerOp:
     * 0}, which is now the target for the errno-style rewrite rather than a description of the
     * current path</b>; expect the ratchet to sit far above it until that lands.
     */
    @Benchmark
    public ProjCoordinate noGridHit() {
        scratch.setValue(outOfGridLonRad, outOfGridLatRad);
        try {
            Grid.shift(grids, false, scratch);
        } catch (CrsTransformException expected) {
            // The measured path. See the javadoc: this is now an exception benchmark.
        }
        return scratch;
    }

    /**
     * The full public API over the same 96W 39N point, so the {@code BasicCoordinateTransform}
     * envelope and CRS-driven grid resolution are included.
     *
     * <p><b>This arm is no longer a no-hit path.</b> It was, while {@code ntv1_can.dat} was the only
     * grid on the classpath; now that {@code benchmark/pom.xml} pulls in
     * {@code proj4j-grids-us-legacy}, 96W 39N resolves into CTABLE V2 {@code conus} and this does a
     * real NADCON interpolation. {@link #noGridHit()} is the arm that still isolates dispatch
     * without interpolation - it queries an explicitly-loaded {@code ntv1_can.dat} list, which 96W
     * 39N really is outside of, and {@link #setUp()} asserts that.
     *
     * <p>So the gap between this and {@link #forwardShift} is the envelope plus CRS-driven grid
     * resolution, and it is where {@code Datum.isEqual}'s latent {@code Arrays.equals(cvs)} over
     * every grid node would appear - that comparison is masked today only because
     * {@code setGrids(null)} empties the list on some paths, so <b>fixing {@code Proj4Parser:53} is
     * expected to make this arm blow up</b>. If it does, intern the resolved grid list so the
     * comparison short-circuits on identity.
     */
    @Benchmark
    public ProjCoordinate transformThroughEnvelope() {
        return nad27ToNad83.transform(outOfGridIn, transformOut);
    }
}

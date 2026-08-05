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

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;

/**
 * The eight representative CRS pairs, as one enum so that every benchmark and the Tier 2 gate
 * cover exactly the same set.
 *
 * <p>Written as a single {@code @Param} enum on purpose. JMH expands a bare {@code @Param} on an
 * enum-typed field to all of its constants, so adding a ninth pair here extends every
 * parameterised benchmark and the op-count baseline at once, with no other edit. If the pairs were
 * eight string literals per benchmark they would drift apart within a release.
 *
 * <p><b>Each pair is here because it exercises a distinct cost mechanism</b>, not for coverage.
 * The sample point is pinned per pair because the Tier 2 op counts are only deterministic for a
 * fixed input - iterative inverses take a data-dependent number of trips, which is the whole
 * reason {@code reference/numerics.md} insists on fixed iteration counts.
 *
 * @see reference/performance.md "The 8 representative CRS pairs"
 */
public enum CrsPair {

    /**
     * The floor. Nothing but the {@code BasicCoordinateTransform} envelope: {@code setValue}, six
     * {@code AxisOrder} enum dispatches, two prime-meridian calls, an identity datum transform.
     * Whatever this costs is the per-call overhead every other pair also pays, so it is the
     * denominator for reading all the others.
     */
    WGS84_TO_WGS84("EPSG:4326", "EPSG:4326", 8.5, 47.4, 100.0),

    /** Web Mercator. The cheapest real projection: spherical {@code merc}, no datum shift. */
    WGS84_TO_WEBMERCATOR("EPSG:4326", "EPSG:3857", 8.5, 47.4, 100.0),

    /**
     * UTM zone 33N - <b>proj4j's most expensive projection</b>.
     * {@code Registry.java:274} maps {@code +proj=utm} to {@code ExtendedTransverseMercatorProjection},
     * which allocates two {@code new double[1]} out-params per call and invokes the
     * non-intrinsified {@code Math.hypot} twice. Sample longitude is the zone's central meridian
     * region so the series converges normally rather than in its degraded far-from-CM regime.
     */
    WGS84_TO_UTM33N("EPSG:4326", "EPSG:32633", 15.0, 47.4, 100.0),

    /**
     * Projected to projected, same datum. Exercises the inverse-then-forward pair with no datum
     * work at all, and is the pair a {@code ProjToProjSameDatumKernel} would have to beat.
     * Input is an easting/northing in metres, not degrees.
     */
    UTM33N_TO_WEBMERCATOR("EPSG:32633", "EPSG:3857", 500000.0, 5250000.0, 100.0),

    /**
     * OSGB36. A genuine 7-parameter Helmert, which means a full {@code cart} forward and inverse
     * round trip through geocentric coordinates. Also the invented-height case: the Helmert
     * consumes {@code z}, so a 2D caller's absent height is silently treated as a real one.
     */
    WGS84_TO_OSGB36("EPSG:4326", "EPSG:27700", -2.0, 52.0, 100.0),

    /**
     * NAD27 to NAD83, the grid-shift pair. 96W 39N is in Kansas, inside CTABLE V2 {@code conus}.
     *
     * <p><b>This measures a real NADCON interpolation</b>: extent test, cell index, four-corner
     * bilinear blend, through the full {@code BasicCoordinateTransform} envelope. The shift is
     * about 23 m of longitude - {@code (-96, 39)} goes to
     * {@code (-96.00026791361263, 39.000000955367376)}.
     *
     * <p><b>Superseded description, kept because it explains a live trap.</b> This pair used to be
     * documented here as a near-no-op that "pays the full grid dispatch and then falls out because
     * 96W 39N is outside the Canadian grid", with a measured residual of 9.2e-10 deg. That was true
     * when {@code ntv1_can.dat} was the only grid that shipped and {@code @conus} resolved to
     * nothing. Two things changed since: the {@code grids-us-legacy} module vendored {@code conus},
     * and the fail-closed API turned the fall-through into a {@code CrsTransformException}. The
     * combination is why {@code benchmark/pom.xml} must depend on {@code proj4j-grids-us-legacy} -
     * without it this pair does not measure a cheap dispatch, it throws, and it takes all of Tier 2
     * down with it.
     *
     * <p>Grid-shift overhead <i>without</i> interpolation is still measured, by
     * {@link GridShiftBenchmark#noGridHit()}, which queries an explicitly-loaded
     * {@code ntv1_can.dat} list directly rather than going through CRS resolution.
     */
    NAD27_TO_NAD83("EPSG:4267", "EPSG:4269", -96.0, 39.0, 100.0),

    /**
     * Albers Equal Area CONUS. The iterative inverse: {@code authlat}/{@code qsfn} today, an
     * {@code AuthalicLat} Clenshaw series after {@code reference/numerics.md} row 4. This is the
     * pair whose op counts should <i>drop sharply</i> when that lands - a Tier 2 baseline refresh
     * with a smaller count is the expected, correct outcome, not a breach.
     */
    WGS84_TO_ALBERS_CONUS("EPSG:4326", "EPSG:5070", -96.0, 39.0, 100.0),

    /**
     * Geocentric target. {@code +proj=geocent}, where {@code GeocentProjection} constructs a
     * <b>new {@code GeocentricConverter} per call</b>, so this is the pair Tier 1 watches for the
     * allocation fix.
     */
    WGS84_TO_GEOCENTRIC("EPSG:4326", "EPSG:4978", 8.5, 47.4, 100.0);

    private final String sourceCode;
    private final String targetCode;
    private final double x;
    private final double y;
    private final double z;

    CrsPair(String sourceCode, String targetCode, double x, double y, double z) {
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

    /** Sample easting or longitude, in the source CRS's own units. */
    public double x() {
        return x;
    }

    /** Sample northing or latitude, in the source CRS's own units. */
    public double y() {
        return y;
    }

    /** Sample height in metres. Never NaN, so the Helmert pairs exercise a real {@code z}. */
    public double z() {
        return z;
    }

    /** {@code "EPSG:4326 -> EPSG:32633"}, for gate messages. */
    public String describe() {
        return sourceCode + " -> " + targetCode;
    }

    /**
     * Builds the transform with fresh factories. Deliberately not cached in a static: a benchmark
     * that shares one transform across parameterisations would also share whatever per-transform
     * state a future kernel caches, and that would quietly change what is being measured.
     */
    public CoordinateTransform createTransform() {
        CRSFactory crsFactory = new CRSFactory();
        CoordinateTransformFactory transformFactory = new CoordinateTransformFactory();
        return transformFactory.createTransform(
                crsFactory.createFromName(sourceCode),
                crsFactory.createFromName(targetCode));
    }
}

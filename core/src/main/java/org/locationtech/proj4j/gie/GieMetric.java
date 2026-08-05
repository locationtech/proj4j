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
package org.locationtech.proj4j.gie;

/**
 * The four — and only four — ways gie can measure the deviation between an expected and an
 * observed coordinate, in the source order of the {@code if}/{@code else if} chain at
 * PROJ 9.8.1 {@code src/apps/gie.cpp:1136-1166}.
 *
 * <p>This is an enum rather than an inline {@code if}-chain on purpose. Choosing the wrong
 * branch does not produce a slightly wrong number; applying an angular metric to a projected
 * target inflates the deviation by a factor of roughly 111,319, and applying a linear metric
 * to an angular target deflates it by the same. Both mistakes have been made in the wild and
 * both required re-pinning large numbers of expected values. Making the selection an explicit,
 * named, testable value makes the mistake visible in a diff.
 *
 * <h2>Which {@code pj_io_units} selects which branch</h2>
 *
 * The unit inspected is the <em>output-side</em> unit, i.e.
 * {@link GieIoUnits#outputUnits(GieIoUnits, GieIoUnits, boolean, GieDirection)}, already
 * {@linkplain GieIoUnits#folded() folded}.
 *
 * <table border="1">
 * <caption>Branch selection by output-side {@code pj_io_units}</caption>
 * <tr><th>{@code pj_io_units}</th><th>branch</th></tr>
 * <tr><td>{@link GieIoUnits#WHATEVER} (0)</td><td>{@link #EUCLIDEAN_METRES}</td></tr>
 * <tr><td>{@link GieIoUnits#CLASSIC} (1)</td><td>{@link #EUCLIDEAN_METRES} (folded to {@code PROJECTED})</td></tr>
 * <tr><td>{@link GieIoUnits#PROJECTED} (2)</td><td>{@link #EUCLIDEAN_METRES}</td></tr>
 * <tr><td>{@link GieIoUnits#CARTESIAN} (3)</td><td>{@link #EUCLIDEAN_METRES}</td></tr>
 * <tr><td>{@link GieIoUnits#RADIANS} (4)</td><td>{@link #GEODESIC_FROM_RADIANS}</td></tr>
 * <tr><td>{@link GieIoUnits#DEGREES} (5)</td><td>{@link #GEODESIC_FROM_DEGREES}</td></tr>
 * </table>
 *
 * <p>{@link #NAN_BOTH} pre-empts all of the above: it is tested first and depends only on the
 * coordinates, not on the units.
 *
 * <p>{@link GieIoUnits#DEGREES} arises from exactly two places in PROJ: a {@code unitconvert}
 * whose normalised unit name is {@code "Degree"} ({@code unitconvert.cpp:491-493, 514-516}),
 * and pipelines built by {@code proj_create_crs_to_crs} for geographic CRSs, which end in such
 * a {@code unitconvert}.
 */
public enum GieMetric {

    /**
     * {@code isnan(co.v[0]) && isnan(ce.v[0])} — both sides are NaN in their first ordinate, so
     * the deviation is defined to be exactly {@code 0.0} and the assertion passes.
     *
     * <p>Note that this is an <em>and</em>: NaN on one side only does not take this branch, and
     * will fail in whichever branch is then selected (a NaN deviation fails
     * {@link GieComparator#withinTolerance(double, double)}).
     */
    NAN_BOTH,

    /**
     * {@code proj_angular_output(T.P, T.dir)} — the output side is
     * {@link GieIoUnits#RADIANS}. Deviation is {@code proj_lpz_dist}: a geodesic distance on
     * the operation's ellipsoid, combined by {@code hypot} with the difference in {@code z}.
     *
     * <p>No degrees-to-radians conversion and <em>no</em> lat/lon swap in this branch.
     */
    GEODESIC_FROM_RADIANS,

    /**
     * {@code proj_degree_output(T.P, T.dir)} — the output side is
     * {@link GieIoUnits#DEGREES}. Both coordinates have their first two ordinates converted
     * degrees-to-radians, then optionally swapped when the destination CRS is lat/lon or y/x
     * ordered, and are then measured with {@code proj_lpz_dist} exactly as above.
     */
    GEODESIC_FROM_DEGREES,

    /**
     * The {@code else} branch — {@link GieIoUnits#WHATEVER}, {@link GieIoUnits#CLASSIC},
     * {@link GieIoUnits#PROJECTED} or {@link GieIoUnits#CARTESIAN}. Deviation is
     * {@code proj_xyz_dist}: a plain Euclidean distance in whatever units the coordinates are
     * already in, nominally metres.
     *
     * <p>"Nominally" because {@link GieIoUnits#WHATEVER} really does mean whatever: see the
     * {@code gigs/5102.2.gie} case, where grad values are compared with this metric against a
     * tolerance written in metres. That is faithful behaviour, not a bug.
     */
    EUCLIDEAN_METRES;

    /**
     * Reproduces the branch selection of {@code gie.cpp:1136-1166} exactly, in source order.
     *
     * <pre>
     * if (isnan(co.v[0]) &amp;&amp; isnan(ce.v[0]))    d = 0.0;
     * else if (proj_angular_output(T.P, T.dir)) d = proj_lpz_dist(T.P, ce, co);
     * else if (proj_degree_output(T.P, T.dir))  { ...torad, ...swap; d = proj_lpz_dist(T.P, ce, co); }
     * else                                      { ...swap; d = proj_xyz_dist(ce, co); }
     * </pre>
     *
     * @param outputUnits the output-side {@code pj_io_units}; folded internally, so passing
     *                    {@link GieIoUnits#CLASSIC} is safe and yields {@link #EUCLIDEAN_METRES}.
     * @param expected0   {@code ce.v[0]}, the expected coordinate's first ordinate.
     * @param got0        {@code co.v[0]}, the observed coordinate's first ordinate, already
     *                    dimension-masked.
     * @return the metric gie would use.
     */
    public static GieMetric select(final GieIoUnits outputUnits,
                                   final double expected0,
                                   final double got0) {
        if (Double.isNaN(got0) && Double.isNaN(expected0)) {
            return NAN_BOTH;
        }
        final GieIoUnits folded = outputUnits.folded();
        if (folded == GieIoUnits.RADIANS) {
            return GEODESIC_FROM_RADIANS;
        }
        if (folded == GieIoUnits.DEGREES) {
            return GEODESIC_FROM_DEGREES;
        }
        return EUCLIDEAN_METRES;
    }

    /**
     * Whether this metric measures an angular quantity on the ellipsoid, i.e. whether the
     * deviation it produces is a geodesic distance rather than a coordinate-space distance.
     *
     * <p>{@link #NAN_BOTH} is not angular: it produces a bare {@code 0.0} and consults neither
     * the ellipsoid nor the units.
     *
     * @return {@code true} for {@link #GEODESIC_FROM_RADIANS} and {@link #GEODESIC_FROM_DEGREES}.
     */
    public boolean isAngular() {
        return this == GEODESIC_FROM_RADIANS || this == GEODESIC_FROM_DEGREES;
    }
}

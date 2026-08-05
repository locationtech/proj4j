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

import org.locationtech.proj4j.geodesic.Geodesic;
import org.locationtech.proj4j.geodesic.GeodesicMask;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * The gie comparison metric: given an expected coordinate, an observed coordinate and the unit
 * domain of the operation's output side, produce the deviation gie would produce and the
 * pass/fail gie would report.
 *
 * <p>A 1:1 port of PROJ 9.8.1 {@code src/apps/gie.cpp:1120-1177} and {@code src/dist.cpp:69-96}.
 *
 * <h2>Why this is published API</h2>
 *
 * Consumers re-derive this metric and get it wrong. The specific failure mode is applying a
 * constant degrees-to-metres scale where a geodesic solution is required, or applying an
 * angular metric to a projected target; both are order-of-magnitude errors, not rounding
 * errors, and both have caused large-scale re-pinning of expected values downstream. The
 * metric selection is therefore exposed as an explicit {@link GieMetric} rather than buried in
 * an {@code if}-chain, and {@link Result} reports which branch was taken.
 *
 * <h2>Coordinate convention</h2>
 *
 * Coordinates are {@code double[4]} in PROJ's {@code PJ_COORD} order, which for the angular
 * interpretation ({@code PJ_LPZT}) is:
 *
 * <pre>
 * v[0] = lam  (longitude, radians in the RADIANS branch, degrees in the DEGREES branch)
 * v[1] = phi  (latitude,  ditto)
 * v[2] = z    (metres — always participates in the deviation)
 * v[3] = t    (decimal years — never participates in the spatial deviation)
 * </pre>
 *
 * and for the linear interpretation ({@code PJ_XYZT}) is {@code x, y, z, t}. Note in particular
 * that {@code v[0]} is <em>longitude</em>, i.e. easting-like, and {@code v[1]} is latitude,
 * whereas {@link Geodesic#Inverse} takes {@code (lat, lon)}. The port swaps them at the call
 * site, as PROJ does.
 *
 * <h2>Thread safety</h2>
 *
 * Instances are immutable and hold only an immutable {@link Geodesic}. A single instance is
 * safe to share across parallel test runners. {@link #compare} never mutates its arguments; it
 * copies both coordinates before masking, converting or swapping.
 */
public final class GieComparator {

    /**
     * PROJ's {@code HUGE_VAL}. {@code proj_lpz_dist} compares against this by equality, not by
     * {@link Double#isNaN}, and only on {@code lam}.
     */
    public static final double HUGE_VAL = Double.POSITIVE_INFINITY;

    /**
     * The temporal threshold from {@code gie.cpp:1169},
     * {@code constexpr double TEMPORAL_THRESHOLD_IN_YEAR = 1e-4}. Described in the source as
     * "somewhat arbitrary".
     */
    public static final double TEMPORAL_THRESHOLD_IN_YEAR = 1e-4;

    private final Geodesic geodesic;

    private GieComparator(final Geodesic geodesic) {
        if (geodesic == null) {
            throw new IllegalArgumentException("geodesic");
        }
        this.geodesic = geodesic;
    }

    /**
     * A comparator whose geodesic distances are computed on the given ellipsoid — the analogue
     * of PROJ's {@code geod_init(P->geod, P->a, P->f)}.
     *
     * @param a equatorial radius, metres.
     * @param f flattening.
     * @return a new comparator.
     */
    public static GieComparator forEllipsoid(final double a, final double f) {
        return new GieComparator(new Geodesic(a, f));
    }

    /**
     * A comparator on <b>WGS84</b> ({@code a = 6378137.0, f = 1/298.257223563}).
     *
     * <p>This is the ellipsoid PROJ gives a plain {@code operation +proj=X} that names no
     * ellipsoid: {@code pj_init_ctx} substitutes WGS84 when no shape parameters were supplied
     * ({@code src/init.cpp:576-581}).
     *
     * <p><b>It differs from {@link #grs80()}</b>, which is what a {@code +proj=pipeline} with no
     * global {@code +ellps} gets ({@code src/pipeline.cpp:338-351}). The two flattenings differ
     * in the eighth significant figure, worth about 1e-7 m over a 1° line — irrelevant against a
     * {@code mm} tolerance, material against the corpus's 16 {@code nm} rows, and material
     * whenever the operation names an ellipsoid explicitly (in which case use
     * {@link #forEllipsoid} and neither of these).
     *
     * @return a new comparator on WGS84.
     */
    public static GieComparator wgs84() {
        return forEllipsoid(6378137.0, 1.0 / 298.257223563);
    }

    /**
     * A comparator on <b>GRS80</b> ({@code a = 6378137.0, f = 1/298.257222101}).
     *
     * <p>This is the ellipsoid PROJ gives a {@code +proj=pipeline} whose global parameters
     * contain no ellipsoid specification ({@code src/pipeline.cpp:334-351}, whose own comment
     * reads "If not, use GRS80 as default"). See {@link #wgs84()} for why the distinction is
     * not cosmetic.
     *
     * @return a new comparator on GRS80.
     */
    public static GieComparator grs80() {
        return forEllipsoid(6378137.0, 1.0 / 298.257222101);
    }

    /**
     * @return the ellipsoid model geodesic distances are computed on.
     */
    public Geodesic geodesic() {
        return geodesic;
    }

    /**
     * {@code proj_lp_dist(P, a, b)} ({@code dist.cpp:69-79}) — the geodesic distance in metres
     * between two angular 2D coordinates given in <b>radians</b>.
     *
     * <p>PROJ converts to degrees and calls {@code geod_inverse} in {@code (lat, lon)} order:
     * <pre>
     * geod_inverse(P-&gt;geod, PJ_TODEG(a.lpz.phi), PJ_TODEG(a.lpz.lam),
     *                       PJ_TODEG(b.lpz.phi), PJ_TODEG(b.lpz.lam), &amp;s12, ...);
     * </pre>
     *
     * @param a first coordinate, {@code {lam, phi, z, t}} in radians.
     * @param b second coordinate, likewise.
     * @return {@code s12} in metres.
     */
    public double lpDist(final double[] a, final double[] b) {
        // PJ_TODEG is bit-identical to Math.toDegrees; PJ_TORAD is NOT bit-identical to
        // Math.toRadians, hence the private toRad() below.
        return geodesic.Inverse(
                ProjectionMath.toDeg(a[1]), ProjectionMath.toDeg(a[0]),
                ProjectionMath.toDeg(b[1]), ProjectionMath.toDeg(b[0]),
                GeodesicMask.DISTANCE).s12;
    }

    /**
     * {@code proj_lpz_dist(P, a, b)} ({@code dist.cpp:81-86}) — the geodesic distance and the
     * vertical offset, combined by {@code hypot}.
     *
     * <pre>
     * if (HUGE_VAL == a.lpz.lam || HUGE_VAL == b.lpz.lam) return HUGE_VAL;
     * return hypot(proj_lp_dist(P, a, b), a.lpz.z - b.lpz.z);
     * </pre>
     *
     * <p>Note that the guard tests {@code lam} only — never {@code phi}, never {@code z} — and
     * tests equality with {@code HUGE_VAL} (positive infinity), <em>not</em> {@code isnan}. A
     * coordinate with a NaN {@code lam} falls through to the geodesic, which yields NaN, which
     * then fails {@link #withinTolerance(double, double)}.
     *
     * @param a first coordinate, radians.
     * @param b second coordinate, radians.
     * @return the combined deviation in metres, or {@link #HUGE_VAL}.
     */
    public double lpzDist(final double[] a, final double[] b) {
        if (HUGE_VAL == a[0] || HUGE_VAL == b[0]) {
            return HUGE_VAL;
        }
        return Math.hypot(lpDist(a, b), a[2] - b[2]);
    }

    /**
     * {@code proj_xy_dist(a, b)} ({@code dist.cpp:88-91}) — {@code hypot(a.x - b.x, a.y - b.y)}.
     *
     * @param a first coordinate.
     * @param b second coordinate.
     * @return the planar Euclidean distance.
     */
    public static double xyDist(final double[] a, final double[] b) {
        return Math.hypot(a[0] - b[0], a[1] - b[1]);
    }

    /**
     * {@code proj_xyz_dist(a, b)} ({@code dist.cpp:93-96}) —
     * {@code hypot(proj_xy_dist(a, b), a.z - b.z)}.
     *
     * <p>This is a <b>nested</b> {@code hypot}, not {@code sqrt(dx*dx + dy*dy + dz*dz)}. The two
     * agree mathematically but not bitwise, and the nesting is what protects against overflow.
     * Keep the nesting.
     *
     * @param a first coordinate.
     * @param b second coordinate.
     * @return the 3D Euclidean distance.
     */
    public static double xyzDist(final double[] a, final double[] b) {
        return Math.hypot(xyDist(a, b), a[2] - b[2]);
    }

    /**
     * gie's pass predicate, {@code gie.cpp:1163-1165}.
     *
     * <p>The C source is:
     * <pre>
     * // Test written like that to handle NaN
     * if (!(d &lt;= T.tolerance)) return expect_message(d, args);
     * </pre>
     *
     * <p>The inverted form is <b>not</b> stylistic. Every comparison with NaN is false, so
     * {@code d &gt; tol} is false for a NaN deviation and a NaN would silently <em>pass</em> —
     * which is the difference between "the transform blew up" and "the transform is correct".
     * {@code !(d &lt;= tol)} is true for NaN against any tolerance including
     * {@link Double#POSITIVE_INFINITY}, and true for {@code d == +inf} against any finite
     * tolerance. Do not rewrite this as {@code d > tol}.
     *
     * @param d   the deviation.
     * @param tol the tolerance.
     * @return {@code true} if the assertion passes.
     */
    public static boolean withinTolerance(final double d, final double tol) {
        final boolean fails = !(d <= tol); // literal transcription; do not rewrite as d > tol
        return !fails;
    }

    /**
     * Compare an expected against an observed coordinate exactly as gie's {@code expect()} does.
     *
     * @param outputUnits          the output-side {@code pj_io_units}; see
     *                             {@link GieIoUnits#outputUnits(GieIoUnits, GieIoUnits, boolean, GieDirection)}.
     *                             Folded internally.
     * @param crsDstIsLatLonOrYX   gie's {@code T.crs_dst_is_lat_lon_or_y_x}: swap {@code v[0]}
     *                             and {@code v[1]} on both sides before measuring. Applies in the
     *                             {@link GieMetric#GEODESIC_FROM_DEGREES} and
     *                             {@link GieMetric#EUCLIDEAN_METRES} branches only.
     * @param expected             {@code ce}, the expected coordinate, length 4. Not mutated.
     * @param got                  {@code co}, the observed coordinate, length 4. Not mutated.
     * @param dimensionsGiven      {@code T.dimensions_given} — the number of ordinates on the
     *                             {@code expect} line, <em>not</em> the {@code accept} line.
     * @param tolerance            the tolerance in metres, from {@link GieTolerance#tolerance}.
     * @return an immutable description of the comparison.
     */
    public Result compare(final GieIoUnits outputUnits,
                          final boolean crsDstIsLatLonOrYX,
                          final double[] expected,
                          final double[] got,
                          final int dimensionsGiven,
                          final double tolerance) {
        if (expected == null || expected.length != 4) {
            throw new IllegalArgumentException("expected must be double[4]");
        }
        if (got == null || got.length != 4) {
            throw new IllegalArgumentException("got must be double[4]");
        }

        final double[] ce = expected.clone();
        final double[] co = got.clone();

        // gie.cpp:1120-1123. Only `co` is masked. `ce` comes from parse_coord, which
        // zero-initialises, so unstated components are already 0 on the expected side --
        // equivalent in effect, but the asymmetry is reproduced literally rather than
        // "tidied up" into a symmetric mask.
        if (dimensionsGiven < 4) {
            co[3] = 0;
        }
        if (dimensionsGiven < 3) {
            co[2] = 0;
        }

        final GieMetric metric = GieMetric.select(outputUnits, ce[0], co[0]);

        final double d;
        boolean swapApplied = false;
        switch (metric) {
            case NAN_BOTH:
                d = 0.0;
                break;
            case GEODESIC_FROM_RADIANS:
                // No conversion and no swap in this branch -- gie.cpp:1139-1140.
                d = lpzDist(ce, co);
                break;
            case GEODESIC_FROM_DEGREES:
                co[0] = toRad(co[0]);
                co[1] = toRad(co[1]);
                ce[0] = toRad(ce[0]);
                ce[1] = toRad(ce[1]);
                if (crsDstIsLatLonOrYX) {
                    swap(co);
                    swap(ce);
                    swapApplied = true;
                }
                d = lpzDist(ce, co);
                break;
            default:
                if (crsDstIsLatLonOrYX) {
                    swap(co);
                    swap(ce);
                    swapApplied = true;
                }
                // The swap is a mathematical no-op here: hypot is symmetric in its two
                // arguments' roles, so exchanging v[0] and v[1] on BOTH operands merely
                // reorders them. Ported for fidelity.
                d = xyzDist(ce, co);
                break;
        }

        final boolean within = withinTolerance(d, tolerance);

        // gie.cpp:1167-1176. t contributes nothing to d; it has its own check, and only when
        // the expect line actually carried a 4th ordinate.
        final boolean temporalChecked = dimensionsGiven == 4;
        final double temporalDeviation = Math.abs(ce[3] - co[3]);
        final boolean temporalFails =
                temporalChecked && temporalDeviation > TEMPORAL_THRESHOLD_IN_YEAR;

        return new Result(metric, d, tolerance, swapApplied, within,
                temporalChecked, temporalDeviation, !temporalFails);
    }

    /**
     * {@code PJ_TORAD(deg)}, i.e. {@code deg * M_PI / 180.0}.
     *
     * <p><b>Not {@link Math#toRadians}</b>, which computes {@code angdeg / 180.0 * PI} — a
     * different association that differs by up to 1 ULP. 1 ULP of a radian is about 1.4e-9 m on
     * the ellipsoid, which is exactly the threshold of the 16 {@code nm}-tolerance rows in the
     * gie corpus. {@code PJ_TODEG} <em>is</em> bit-identical to {@link Math#toDegrees}, so that
     * one is used directly.
     */
    private static double toRad(final double deg) {
        return deg * Math.PI / 180.0;
    }

    private static void swap(final double[] v) {
        final double t = v[0];
        v[0] = v[1];
        v[1] = t;
    }

    /**
     * The outcome of one {@link GieComparator#compare} call. Immutable.
     */
    public static final class Result {

        private final GieMetric metric;
        private final double deviation;
        private final double tolerance;
        private final boolean swapApplied;
        private final boolean withinTolerance;
        private final boolean temporalChecked;
        private final double temporalDeviation;
        private final boolean temporalWithinThreshold;

        Result(final GieMetric metric,
               final double deviation,
               final double tolerance,
               final boolean swapApplied,
               final boolean withinTolerance,
               final boolean temporalChecked,
               final double temporalDeviation,
               final boolean temporalWithinThreshold) {
            this.metric = metric;
            this.deviation = deviation;
            this.tolerance = tolerance;
            this.swapApplied = swapApplied;
            this.withinTolerance = withinTolerance;
            this.temporalChecked = temporalChecked;
            this.temporalDeviation = temporalDeviation;
            this.temporalWithinThreshold = temporalWithinThreshold;
        }

        /**
         * @return which of the four branches was taken.
         */
        public GieMetric metric() {
            return metric;
        }

        /**
         * @return {@code d} — metres for every branch except
         *         {@link GieMetric#EUCLIDEAN_METRES} applied to a
         *         {@link GieIoUnits#WHATEVER} output, where it is in whatever units the
         *         coordinates happened to be.
         */
        public double deviation() {
            return deviation;
        }

        /**
         * @return the tolerance the deviation was tested against.
         */
        public double tolerance() {
            return tolerance;
        }

        /**
         * @return whether {@code v[0]}/{@code v[1]} were exchanged. Never true in the
         *         {@link GieMetric#NAN_BOTH} or {@link GieMetric#GEODESIC_FROM_RADIANS}
         *         branches, because PROJ does not swap there.
         */
        public boolean swapApplied() {
            return swapApplied;
        }

        /**
         * @return the spatial verdict, {@code !(d <= tolerance)} negated.
         */
        public boolean withinTolerance() {
            return withinTolerance;
        }

        /**
         * @return whether the 4th-dimension check ran at all, i.e. whether
         *         {@code dimensionsGiven == 4}.
         */
        public boolean temporalChecked() {
            return temporalChecked;
        }

        /**
         * @return {@code |ce.v[3] - co.v[3]|} in decimal years. Reported even when
         *         {@link #temporalChecked()} is false, in which case {@code co.v[3]} has been
         *         masked to 0 and this is just {@code |ce.v[3]|}.
         */
        public double temporalDeviation() {
            return temporalDeviation;
        }

        /**
         * @return {@code true} unless the check ran and the deviation exceeded
         *         {@link GieComparator#TEMPORAL_THRESHOLD_IN_YEAR}.
         */
        public boolean temporalWithinThreshold() {
            return temporalWithinThreshold;
        }

        /**
         * @return {@link #withinTolerance()} and {@link #temporalWithinThreshold()}.
         */
        public boolean passed() {
            return withinTolerance && temporalWithinThreshold;
        }

        @Override
        public String toString() {
            return "GieComparator.Result[metric=" + metric
                    + ", deviation=" + deviation
                    + ", tolerance=" + tolerance
                    + ", swapApplied=" + swapApplied
                    + ", withinTolerance=" + withinTolerance
                    + ", temporalChecked=" + temporalChecked
                    + ", temporalDeviation=" + temporalDeviation
                    + ", temporalWithinThreshold=" + temporalWithinThreshold
                    + ", passed=" + passed() + "]";
        }
    }
}

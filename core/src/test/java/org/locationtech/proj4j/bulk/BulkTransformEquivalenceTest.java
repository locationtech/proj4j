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
package org.locationtech.proj4j.bulk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.locationtech.proj4j.BulkCoordinateTransform;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * <b>Gate D: the bulk path equals N single-point calls, bitwise.</b>
 *
 * <h2>Why bitwise and not a tolerance</h2>
 *
 * <p>Because every mechanism the bulk path uses is <em>pure fusion</em> — hoisting loop invariants,
 * removing allocation, skipping an identity axis permutation — none of which changes the arithmetic
 * or its order. If the result is not bit-identical then the arithmetic <em>did</em> change, and that
 * is a defect rather than an optimisation. A tolerance assertion cannot tell those two apart, which
 * makes it exactly the wrong instrument.
 *
 * <p>Comparison is on {@link Double#doubleToRawLongBits(double)}, which distinguishes two things a
 * tolerance and even {@code assertEquals(double, double, 0.0)} both hide:
 *
 * <ul>
 * <li><b>{@code +0.0} versus {@code -0.0}.</b> {@code assertEquals(0.0, -0.0, 0.0)} passes. The
 *     distinction is real on real data: it occurs at the equator and at the prime meridian, and it
 *     survives into output because a false easting of zero and a negation are both sign-preserving.
 *     The fixture pins all four sign combinations of zero for exactly this reason. It is also the
 *     specific hazard behind not eliding the prime-meridian addition when the offset is zero:
 *     {@code -0.0 + 0.0} is {@code +0.0}.</li>
 * <li><b>{@code NaN} payloads.</b> {@code assertEquals} treats every {@code NaN} as equal to every
 *     other, and {@code raw} bits (rather than {@code doubleToLongBits}) additionally decline to
 *     canonicalise signalling {@code NaN}s. Since {@code NaN} in / {@code NaN} out is a contract
 *     here rather than an accident, the propagation path is worth pinning precisely.</li>
 * </ul>
 *
 * <h2>What the fixture covers, and why each is a separate axis</h2>
 *
 * <ul>
 * <li><b>Eight CRS pairs</b>, so the datum stage is exercised in all of its shapes: no datum work,
 *     a 7-parameter Helmert through geocentric coordinates, a grid-shift-typed transform, a
 *     geocentric target, and projected-to-projected where both a forward and an inverse run.</li>
 * <li><b>Partial batches</b> — a batch shorter than the buffer, so an off-by-one in the loop bound
 *     shows up as an untransformed tail rather than as an exception.</li>
 * <li><b>Non-zero offsets</b> and <b>non-unit strides</b> (3, 4, 5, 7), including strides wider
 *     than the coordinate, with sentinel values in the ordinates the transform does not own. A
 *     stride-2-specialised loop passes every stride-2 test and corrupts every real geometry
 *     buffer.</li>
 * <li><b>Aliased {@code src == dst}</b> in all three overlap arrangements: coincident (pure
 *     in-place), destination above source (needs a descending loop), destination below source
 *     (needs an ascending one). Getting the direction wrong does not throw — it silently
 *     transforms some points twice, which is the failure mode a bitwise test catches and a
 *     round-trip test does not.</li>
 * <li><b>Awkward ordinates</b>: signed zeros, the antimeridian from both sides, longitudes outside
 *     [-180, 180] (which PROJ does <em>not</em> reject, so neither may we), both poles, a latitude
 *     a hair past the pole (which is rejected), and {@code NaN}.</li>
 * </ul>
 *
 * @see BulkCoordinateTransform
 */
public class BulkTransformEquivalenceTest {

    private static final CRSFactory CRS_FACTORY = new CRSFactory();
    private static final CoordinateTransformFactory TRANSFORM_FACTORY =
            new CoordinateTransformFactory();

    /**
     * The eight representative pairs from {@code reference/performance.md}. Each is here because it
     * exercises a distinct stage of the pipeline, not for coverage.
     */
    private static final String[][] PAIRS = {
        {"EPSG:4326", "EPSG:4326"},     // the floor: envelope only
        {"EPSG:4326", "EPSG:3857"},     // spherical merc, no datum work
        {"EPSG:4326", "EPSG:32633"},    // etmerc, the most expensive projection
        {"EPSG:32633", "EPSG:3857"},    // projected to projected, same datum
        {"EPSG:4326", "EPSG:27700"},    // 7-parameter Helmert, geocentric round trip
        {"EPSG:4267", "EPSG:4269"},     // grid-shift-typed
        {"EPSG:4326", "EPSG:5070"},     // Albers
        {"EPSG:4326", "EPSG:4978"},     // geocentric target
    };

    /** Geographic sample ordinates, degrees. */
    private static final double[][] GEOGRAPHIC = {
        {8.5, 47.4},
        {15.0, 47.4},
        {-96.0, 39.0},
        {-2.0, 52.0},
        {0.0, 0.0},
        {-0.0, 0.0},
        {0.0, -0.0},
        {-0.0, -0.0},
        {180.0, 0.0},
        {-180.0, 0.0},
        {179.999999999, 45.0},
        {-179.999999999, -45.0},
        {200.0, 10.0},          // legal: PROJ's only longitude bound is |lambda| > 10 rad
        {-190.0, -10.0},
        {0.0, 90.0},
        {0.0, -90.0},
        {8.5, 90.0000001},      // rejected: overshoots PJ_EPS_LAT
        {Double.NaN, 47.4},
        {8.5, Double.NaN},
    };

    /** Projected sample ordinates, metres, for a projected source CRS. */
    private static final double[][] PROJECTED = {
        {500000.0, 5250000.0},
        {166021.44, 0.0},
        {0.0, 0.0},
        {-0.0, 0.0},
        {0.0, -0.0},
        {1000000.0, 6000000.0},
        {Double.NaN, 5250000.0},
    };

    /** Heights, including both signed zeros and the absent-height sentinel. */
    private static final double[] HEIGHTS = {100.0, 0.0, -0.0, -50.5, Double.NaN};

    // ==============================================================================================
    // The batch shapes
    // ==============================================================================================

    @Test
    public void interleaved2DInPlaceMatchesSinglePointBitwise() {
        for (String[] pair : PAIRS) {
            Fixture f = new Fixture(pair);
            for (int stride : new int[] {2, 3, 4, 7}) {
                for (int offset : new int[] {0, 1, 5}) {
                    f.check2DInPlace(offset, stride);
                }
            }
        }
    }

    @Test
    public void interleaved3DInPlaceMatchesSinglePointBitwise() {
        for (String[] pair : PAIRS) {
            Fixture f = new Fixture(pair);
            for (int stride : new int[] {3, 4, 7}) {
                for (int offset : new int[] {0, 2}) {
                    f.check3DInPlace(offset, stride);
                }
            }
        }
    }

    @Test
    public void separateSourceAndDestinationMatchesSinglePointBitwise() {
        for (String[] pair : PAIRS) {
            Fixture f = new Fixture(pair);
            f.checkSrcToDst(0, 2, 0, 2);
            f.checkSrcToDst(3, 2, 1, 4);
            f.checkSrcToDst(1, 5, 4, 2);
        }
    }

    @Test
    public void structOfArraysMatchesSinglePointBitwise() {
        for (String[] pair : PAIRS) {
            Fixture f = new Fixture(pair);
            for (int offset : new int[] {0, 1, 6}) {
                f.checkSoa(offset, true);
                f.checkSoa(offset, false);
            }
        }
    }

    /**
     * A batch shorter than the buffer. The tail must be left exactly as it was: an off-by-one in
     * the loop bound is otherwise invisible until a caller notices one stale vertex per geometry.
     */
    @Test
    public void partialBatchLeavesTheTailUntouched() {
        for (String[] pair : PAIRS) {
            Fixture f = new Fixture(pair);
            f.checkPartialBatch();
        }
    }

    // ==============================================================================================
    // Aliasing
    // ==============================================================================================

    /**
     * {@code src == dst} with coincident ranges: the pure in-place case, reached through the
     * source-to-destination signature rather than through {@code transform2D(xy, ...)}.
     */
    @Test
    public void aliasedCoincidentRangesMatchInPlace() {
        for (String[] pair : PAIRS) {
            Fixture f = new Fixture(pair);
            f.checkAliased(0, 0, 2);
            f.checkAliased(4, 4, 2);
        }
    }

    /**
     * {@code src == dst} with the destination <em>above</em> the source, overlapping. Requires a
     * descending loop; an ascending one overwrites source ordinates before they are read, which
     * transforms some points twice and produces a plausible, wrong answer.
     */
    @Test
    public void aliasedDestinationAboveSourceMatchesSinglePointBitwise() {
        for (String[] pair : PAIRS) {
            Fixture f = new Fixture(pair);
            f.checkAliased(0, 2, 2);
            f.checkAliased(1, 7, 4);
        }
    }

    /** {@code src == dst} with the destination <em>below</em> the source, overlapping. */
    @Test
    public void aliasedDestinationBelowSourceMatchesSinglePointBitwise() {
        for (String[] pair : PAIRS) {
            Fixture f = new Fixture(pair);
            f.checkAliased(2, 0, 2);
            f.checkAliased(9, 3, 4);
        }
    }

    /**
     * Overlapping ranges in one array at different strides. The write index crosses the read index
     * part way through the batch, so no single direction is safe — and returning a plausible
     * coordinate for the points that got clobbered would be worse than refusing.
     */
    @Test
    public void aliasedOverlapAtDifferentStridesIsRejected() {
        Fixture f = new Fixture(PAIRS[1]);
        double[] buf = new double[400];
        try {
            f.bulk.transform2D(buf, 0, 2, buf, 1, 4, 50, new byte[50]);
            fail("expected API_MISUSE for an overlapping alias at mismatched strides");
        } catch (Proj4jException e) {
            assertEquals(ErrorCause.API_MISUSE, e.cause());
        }
    }

    /** Disjoint ranges within one array are not an overlap and must be accepted. */
    @Test
    public void aliasedDisjointRangesAreAccepted() {
        Fixture f = new Fixture(PAIRS[1]);
        f.checkAliasedDisjoint();
    }

    // ==============================================================================================
    // Fixture
    // ==============================================================================================

    /**
     * One CRS pair, its two transforms (they are the same object, reached through two interfaces),
     * its sample points and the per-point reference results.
     */
    private static final class Fixture {
        final String name;
        final CoordinateTransform single;
        final BulkCoordinateTransform bulk;
        /** Sample points, x/y/z triples; z is drawn from {@link #HEIGHTS}. */
        final double[][] points;
        /** Reference for a 2D call, i.e. with z = NaN, one per sample point. */
        final Ref[] ref2D;
        /** Reference for a 3D call, i.e. with the sample's own z. */
        final Ref[] ref3D;

        Fixture(String[] pair) {
            this.name = pair[0] + " -> " + pair[1];
            CoordinateReferenceSystem src = CRS_FACTORY.createFromName(pair[0]);
            CoordinateReferenceSystem tgt = CRS_FACTORY.createFromName(pair[1]);
            this.single = TRANSFORM_FACTORY.createTransform(src, tgt);
            this.bulk = TRANSFORM_FACTORY.createBulkTransform(src, tgt);

            double[][] base = pair[0].equals("EPSG:32633") ? PROJECTED : GEOGRAPHIC;
            List<double[]> pts = new ArrayList<double[]>();
            for (int i = 0; i < base.length; i++) {
                pts.add(new double[] {base[i][0], base[i][1], HEIGHTS[i % HEIGHTS.length]});
            }
            this.points = pts.toArray(new double[pts.size()][]);

            this.ref2D = new Ref[points.length];
            this.ref3D = new Ref[points.length];
            for (int i = 0; i < points.length; i++) {
                ref2D[i] = reference(single, points[i][0], points[i][1], Double.NaN);
                ref3D[i] = reference(single, points[i][0], points[i][1], points[i][2]);
            }
        }

        /**
         * The sample points whose reference outcome the batch API can express: a success, or a
         * per-coordinate failure. A non-per-coordinate failure abandons the whole batch by design,
         * so such a point cannot appear in a batch that is being compared point by point; those are
         * asserted individually by {@link #eachNonCoordinateErrorAbandonsTheBatch()}.
         */
        int[] batchable(Ref[] ref) {
            int n = 0;
            for (Ref r : ref) {
                if (r.thrown == null || r.thrown.isCoordinateError()) {
                    n++;
                }
            }
            int[] idx = new int[n];
            int j = 0;
            for (int i = 0; i < ref.length; i++) {
                if (ref[i].thrown == null || ref[i].thrown.isCoordinateError()) {
                    idx[j++] = i;
                }
            }
            return idx;
        }

        // ---------------------------------------------------------------------------------- 2D
        void check2DInPlace(int offset, int stride) {
            int[] idx = batchable(ref2D);
            int n = idx.length;
            double[] buf = filled(offset + n * stride + 4);
            for (int i = 0; i < n; i++) {
                buf[offset + i * stride] = points[idx[i]][0];
                buf[offset + i * stride + 1] = points[idx[i]][1];
            }
            double[] before = buf.clone();
            byte[] status = new byte[n + 3];
            java.util.Arrays.fill(status, (byte) 99);

            int failures = bulk.transform2D(buf, offset, n, stride, status);

            String where = name + " transform2D in place offset=" + offset + " stride=" + stride;
            assertOutcome(where, idx, ref2D, buf, offset, stride, 2, status, failures);
            assertUntouched(where, before, buf, offset, stride, 2, n);
        }

        // ---------------------------------------------------------------------------------- 3D
        void check3DInPlace(int offset, int stride) {
            int[] idx = batchable(ref3D);
            int n = idx.length;
            double[] buf = filled(offset + n * stride + 4);
            for (int i = 0; i < n; i++) {
                buf[offset + i * stride] = points[idx[i]][0];
                buf[offset + i * stride + 1] = points[idx[i]][1];
                buf[offset + i * stride + 2] = points[idx[i]][2];
            }
            double[] before = buf.clone();
            byte[] status = new byte[n];

            int failures = bulk.transform3D(buf, offset, n, stride, status);

            String where = name + " transform3D in place offset=" + offset + " stride=" + stride;
            assertOutcome(where, idx, ref3D, buf, offset, stride, 3, status, failures);
            assertUntouched(where, before, buf, offset, stride, 3, n);
        }

        // ------------------------------------------------------------------------- src -> dst
        void checkSrcToDst(int srcOff, int srcStride, int dstOff, int dstStride) {
            int[] idx = batchable(ref2D);
            int n = idx.length;
            double[] src = filled(srcOff + n * srcStride + 4);
            for (int i = 0; i < n; i++) {
                src[srcOff + i * srcStride] = points[idx[i]][0];
                src[srcOff + i * srcStride + 1] = points[idx[i]][1];
            }
            double[] srcBefore = src.clone();
            double[] dst = filled(dstOff + n * dstStride + 4);
            double[] dstBefore = dst.clone();
            byte[] status = new byte[n];

            int failures =
                    bulk.transform2D(src, srcOff, srcStride, dst, dstOff, dstStride, n, status);

            String where = name + " transform2D src->dst srcOff=" + srcOff + "/" + srcStride
                    + " dstOff=" + dstOff + "/" + dstStride;
            assertOutcome(where, idx, ref2D, dst, dstOff, dstStride, 2, status, failures);
            assertUntouched(where + " (dst)", dstBefore, dst, dstOff, dstStride, 2, n);
            assertArrayBitwise(where + " (src must be read-only)", srcBefore, src);
        }

        // ------------------------------------------------------------------------------ aliased
        void checkAliased(int srcOff, int dstOff, int stride) {
            int[] idx = batchable(ref2D);
            int n = idx.length;
            int len = Math.max(srcOff, dstOff) + n * stride + 4;
            double[] buf = filled(len);
            for (int i = 0; i < n; i++) {
                buf[srcOff + i * stride] = points[idx[i]][0];
                buf[srcOff + i * stride + 1] = points[idx[i]][1];
            }
            byte[] status = new byte[n];

            int failures = bulk.transform2D(buf, srcOff, stride, buf, dstOff, stride, n, status);

            String where = name + " aliased srcOff=" + srcOff + " dstOff=" + dstOff
                    + " stride=" + stride;
            assertOutcome(where, idx, ref2D, buf, dstOff, stride, 2, status, failures);
        }

        void checkAliasedDisjoint() {
            int[] idx = batchable(ref2D);
            int n = idx.length;
            int gap = 2 * n * 2 + 8;
            double[] buf = filled(gap + n * 2 + 4);
            for (int i = 0; i < n; i++) {
                buf[i * 2] = points[idx[i]][0];
                buf[i * 2 + 1] = points[idx[i]][1];
            }
            byte[] status = new byte[n];
            int failures = bulk.transform2D(buf, 0, 2, buf, gap, 2, n, status);
            assertOutcome(name + " aliased disjoint", idx, ref2D, buf, gap, 2, 2, status, failures);
        }

        // ---------------------------------------------------------------------------------- SoA
        void checkSoa(int offset, boolean withZ) {
            Ref[] ref = withZ ? ref3D : ref2D;
            int[] idx = batchable(ref);
            int n = idx.length;
            double[] xs = filled(offset + n + 3);
            double[] ys = filled(offset + n + 3);
            double[] zs = filled(offset + n + 3);
            for (int i = 0; i < n; i++) {
                xs[offset + i] = points[idx[i]][0];
                ys[offset + i] = points[idx[i]][1];
                zs[offset + i] = points[idx[i]][2];
            }
            double[] zsBefore = zs.clone();
            byte[] status = new byte[n];

            int failures = bulk.transform(xs, ys, withZ ? zs : null, offset, n, status);

            String where = name + " transform SoA offset=" + offset + " withZ=" + withZ;
            int failed = 0;
            for (int i = 0; i < n; i++) {
                Ref r = ref[idx[i]];
                String at = where + " point " + i + " " + describe(points[idx[i]]);
                if (r.thrown == null) {
                    assertEquals(at + " status", TransformStatus.OK, status[i]);
                    assertBits(at + " x", r.xb, xs[offset + i]);
                    assertBits(at + " y", r.yb, ys[offset + i]);
                    if (withZ) {
                        assertBits(at + " z", r.zb, zs[offset + i]);
                    }
                } else {
                    failed++;
                    assertEquals(at + " status", TransformStatus.forCause(r.thrown), status[i]);
                    assertTrue(at + " x sentinel", Double.isNaN(xs[offset + i]));
                    assertTrue(at + " y sentinel", Double.isNaN(ys[offset + i]));
                    if (withZ) {
                        assertTrue(at + " z sentinel", Double.isNaN(zs[offset + i]));
                    }
                }
            }
            assertEquals(where + " failure count", failed, failures);
            if (!withZ) {
                assertArrayBitwise(where + " (z must be untouched when null)", zsBefore, zs);
            }
        }

        // ------------------------------------------------------------------------ partial batch
        void checkPartialBatch() {
            int[] idx = batchable(ref2D);
            int n = idx.length;
            int batch = n / 2;
            double[] buf = filled(n * 2);
            for (int i = 0; i < n; i++) {
                buf[i * 2] = points[idx[i]][0];
                buf[i * 2 + 1] = points[idx[i]][1];
            }
            double[] before = buf.clone();
            byte[] status = new byte[n];
            java.util.Arrays.fill(status, (byte) 99);

            bulk.transform2D(buf, 0, batch, 2, status);

            for (int i = batch; i < n; i++) {
                assertBits(name + " partial batch: tail point " + i + " x must be untouched",
                        Double.doubleToRawLongBits(before[i * 2]), buf[i * 2]);
                assertBits(name + " partial batch: tail point " + i + " y must be untouched",
                        Double.doubleToRawLongBits(before[i * 2 + 1]), buf[i * 2 + 1]);
                assertEquals(name + " partial batch: status beyond numPts must be untouched",
                        (byte) 99, status[i]);
            }
        }

        // ------------------------------------------------------------------------------ helpers
        private void assertOutcome(String where, int[] idx, Ref[] ref, double[] buf, int offset,
                                   int stride, int width, byte[] status, int failures) {
            int failed = 0;
            for (int i = 0; i < idx.length; i++) {
                Ref r = ref[idx[i]];
                int k = offset + i * stride;
                String at = where + " point " + i + " " + describe(points[idx[i]]);
                if (r.thrown == null) {
                    assertEquals(at + " status", TransformStatus.OK, status[i]);
                    assertBits(at + " x", r.xb, buf[k]);
                    assertBits(at + " y", r.yb, buf[k + 1]);
                    if (width >= 3) {
                        assertBits(at + " z", r.zb, buf[k + 2]);
                    }
                } else {
                    failed++;
                    assertEquals(at + " status", TransformStatus.forCause(r.thrown), status[i]);
                    for (int w = 0; w < width; w++) {
                        assertTrue(at + " ordinate " + w + " must be the NaN sentinel",
                                Double.isNaN(buf[k + w]));
                    }
                }
            }
            assertEquals(where + " failure count", failed, failures);
        }

        /** Every ordinate inside the range but outside the coordinate width must be untouched. */
        private void assertUntouched(String where, double[] before, double[] after, int offset,
                                     int stride, int width, int numPts) {
            for (int i = 0; i < before.length; i++) {
                boolean owned = false;
                if (i >= offset) {
                    int rel = i - offset;
                    int point = rel / stride;
                    if (point < numPts && rel % stride < width) {
                        owned = true;
                    }
                }
                if (!owned) {
                    assertBits(where + " index " + i + " is not the transform's to write",
                            Double.doubleToRawLongBits(before[i]), after[i]);
                }
            }
        }
    }

    // ==============================================================================================
    // Non-coordinate failures abandon the batch
    // ==============================================================================================

    /**
     * A cause that is not per-coordinate is a property of the operation, so it must throw rather
     * than be squeezed into a status byte — recording it once per row would report a planning-time
     * defect once per vertex. Asserted against the single-point path, which throws the same cause.
     *
     * <h2>The eight-pair fixture reaches none of these, and that is a finding worth writing down</h2>
     *
     * <p>The expectation going in was that a {@code NaN} longitude would reach
     * {@code Projection.inverseProjectRadians}' trailing {@code normalizeLongitude} whenever the
     * source projection has {@code +lon_0 != 0} — EPSG:32633 does — and raise
     * {@code INVALID_PARAM_VALUE}, a CRS-definition cause rather than a coordinate one. It does not:
     * that call goes to {@code ProjectionMath.normalizeLongitude}, which is upstream's
     * {@code adjlon} and is {@code NaN}-transparent, <b>not</b> to
     * {@code Projection.normalizeLongitudeRadians}, which is the method that rejects {@code NaN}.
     * Two similarly named methods, one of which throws. So {@code NaN} propagates cleanly through
     * every pair here, and the sweep below finds nothing to check.
     *
     * <p>Rather than leave a vacuous loop, the case is constructed explicitly: a forward-only source
     * projection gives {@code NO_INVERSE_AVAILABLE}, which is an operation cause by definition.
     */
    @Test
    public void eachNonCoordinateErrorAbandonsTheBatch() {
        for (String[] pair : PAIRS) {
            Fixture f = new Fixture(pair);
            for (int i = 0; i < f.points.length; i++) {
                Ref r = f.ref2D[i];
                if (r.thrown == null || r.thrown.isCoordinateError()) {
                    continue;
                }
                double[] buf = {f.points[i][0], f.points[i][1]};
                try {
                    f.bulk.transform2D(buf, 0, 1, 2, new byte[1]);
                    fail(f.name + " " + describe(f.points[i]) + ": single-point path threw "
                            + r.thrown + " but the bulk path returned normally");
                } catch (Proj4jException e) {
                    assertEquals(f.name + " " + describe(f.points[i]) + ": cause must match the "
                            + "single-point path", r.thrown, e.cause());
                }
            }
        }

        // The constructed case, so this test is not vacuous.
        // +proj=august, not +proj=wintri: wintri gained an inverse when AitoffProjection picked up
        // aitoff.cpp's Newton-Raphson solver. August Epicycloidal is "Misc Sph, no inv" upstream.
        CoordinateReferenceSystem forwardOnly =
                CRS_FACTORY.createFromParameters("august", "+proj=august +datum=WGS84");
        CoordinateReferenceSystem wgs84 = CRS_FACTORY.createFromName("EPSG:4326");
        CoordinateTransform single = TRANSFORM_FACTORY.createTransform(forwardOnly, wgs84);
        BulkCoordinateTransform batch = TRANSFORM_FACTORY.createBulkTransform(forwardOnly, wgs84);

        Ref r = reference(single, 100000.0, 200000.0, Double.NaN);
        assertTrue("a forward-only source must fail at the single point too", r.thrown != null);
        assertTrue(r.thrown + " must not be a coordinate cause", !r.thrown.isCoordinateError());

        byte[] status = new byte[4];
        try {
            batch.transform2D(new double[8], 0, 4, 2, status);
            fail("a forward-only source must abandon the batch, not fill the status array");
        } catch (Proj4jException e) {
            assertEquals(r.thrown, e.cause());
        }
        assertEquals("nothing may have been recorded", TransformStatus.OK, status[0]);
    }

    /**
     * The per-coordinate causes the fixture reaches are the ones the classification table promises.
     * Guards against the whole equivalence suite passing vacuously because every point succeeded.
     */
    @Test
    public void fixtureActuallyReachesCoordinateFailures() {
        boolean sawInvalidInput = false;
        boolean sawOk = false;
        for (String[] pair : PAIRS) {
            Fixture f = new Fixture(pair);
            for (Ref r : f.ref2D) {
                if (r.thrown == null) {
                    sawOk = true;
                } else if (r.thrown == ErrorCause.INVALID_COORDINATE) {
                    sawInvalidInput = true;
                    assertEquals(TransformStatus.ERR_INVALID_INPUT,
                            TransformStatus.forCause(r.thrown));
                }
            }
        }
        assertTrue("fixture never succeeded anywhere", sawOk);
        assertTrue("fixture never produced an INVALID_COORDINATE; the latitude past the pole "
                + "should have", sawInvalidInput);
    }

    /**
     * {@code NaN} in, {@code NaN} out, status {@link TransformStatus#OK} — a result, not a failure.
     * Pinned separately from the sweep because it is the one classification rule that looks like a
     * bug until you know the corpus asserts it.
     */
    @Test
    public void nanInputPropagatesAsAResultNotAFailure() {
        Fixture f = new Fixture(PAIRS[1]);        // EPSG:4326 -> EPSG:3857, +lon_0 = 0
        double[][] nans = {
            {Double.NaN, 47.4},
            {8.5, Double.NaN},
            {Double.NaN, Double.NaN},
        };
        double[] buf = new double[nans.length * 2];
        for (int i = 0; i < nans.length; i++) {
            buf[i * 2] = nans[i][0];
            buf[i * 2 + 1] = nans[i][1];
        }
        byte[] status = new byte[nans.length];

        int failures = f.bulk.transform2D(buf, 0, nans.length, 2, status);

        assertEquals("NaN input is not a failure", 0, failures);
        for (int i = 0; i < nans.length; i++) {
            assertEquals("point " + i + " status", TransformStatus.OK, status[i]);
            // And bit-identically to the single-point path, NaN payload included.
            Ref r = reference(f.single, nans[i][0], nans[i][1], Double.NaN);
            assertEquals("point " + i + " must not have thrown", null, r.thrown);
            assertBits("NaN propagation x, point " + i, r.xb, buf[i * 2]);
            assertBits("NaN propagation y, point " + i, r.yb, buf[i * 2 + 1]);
        }
    }

    /** A zero-length batch is legal, does nothing, and touches nothing. */
    @Test
    public void emptyBatchIsANoOp() {
        Fixture f = new Fixture(PAIRS[2]);
        double[] buf = {1.0, 2.0, 3.0, 4.0};
        double[] before = buf.clone();
        byte[] status = {(byte) 42, (byte) 42};
        assertEquals(0, f.bulk.transform2D(buf, 0, 0, 2, status));
        assertEquals(0, f.bulk.transform3D(buf, 0, 0, 3, status));
        assertEquals(0, f.bulk.transform2D(buf, 0, 2, buf, 0, 2, 0, status));
        assertEquals(0, f.bulk.transform(buf, buf, null, 0, 0, status));
        assertArrayBitwise("an empty batch must not write", before, buf);
        assertEquals((byte) 42, status[0]);
        assertEquals((byte) 42, status[1]);
    }

    // ==============================================================================================
    // Reference and assertions
    // ==============================================================================================

    /** One single-point outcome: either the raw bits of the result, or the cause it threw. */
    private static final class Ref {
        final long xb;
        final long yb;
        final long zb;
        final ErrorCause thrown;

        Ref(long xb, long yb, long zb) {
            this.xb = xb;
            this.yb = yb;
            this.zb = zb;
            this.thrown = null;
        }

        Ref(ErrorCause thrown) {
            this.xb = 0;
            this.yb = 0;
            this.zb = 0;
            this.thrown = thrown;
        }
    }

    /**
     * The reference: one call through the single-point API, in the exact shape a caller uses.
     *
     * @param z {@code NaN} to reproduce a two-argument {@code setValue}, i.e. a 2D caller
     */
    private static Ref reference(CoordinateTransform t, double x, double y, double z) {
        ProjCoordinate in = new ProjCoordinate();
        ProjCoordinate out = new ProjCoordinate();
        in.setValue(x, y, z);
        try {
            t.transform(in, out);
            return new Ref(Double.doubleToRawLongBits(out.x),
                    Double.doubleToRawLongBits(out.y),
                    Double.doubleToRawLongBits(out.z));
        } catch (Proj4jException e) {
            return new Ref(e.cause());
        }
    }

    private static void assertBits(String message, long expectedBits, double actual) {
        long actualBits = Double.doubleToRawLongBits(actual);
        if (expectedBits != actualBits) {
            fail(message + ": expected " + Double.longBitsToDouble(expectedBits) + " (0x"
                    + Long.toHexString(expectedBits) + ") but was " + actual + " (0x"
                    + Long.toHexString(actualBits) + ")");
        }
    }

    private static void assertArrayBitwise(String message, double[] expected, double[] actual) {
        assertEquals(message + ": length", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertBits(message + " at " + i, Double.doubleToRawLongBits(expected[i]), actual[i]);
        }
    }

    /**
     * A buffer pre-loaded with distinguishable sentinels, so that anything the transform writes
     * where it should not is visible rather than coincidentally correct. Deliberately not zeros:
     * zero is a plausible output.
     */
    private static double[] filled(int length) {
        double[] a = new double[length];
        for (int i = 0; i < length; i++) {
            a[i] = -1.0e300 - i;
        }
        return a;
    }

    private static String describe(double[] p) {
        return "(" + p[0] + ", " + p[1] + ", " + p[2] + ")";
    }
}

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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.locationtech.proj4j.BasicCoordinateTransform;
import org.locationtech.proj4j.BulkCoordinateTransform;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.DomainErrorPolicy;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.Proj4jException;

/**
 * The normative contract of {@link BulkCoordinateTransform}, clause by clause: the status array,
 * the fail-fast mode, the redundant {@code NaN} sentinel, the failure count, argument validation,
 * and the interaction with {@link DomainErrorPolicy}.
 *
 * <p>{@link BulkTransformEquivalenceTest} owns the bitwise equality with the single-point path. This
 * class owns the behaviours that have no single-point counterpart to compare against.
 */
public class BulkTransformContractTest {

    private static final CRSFactory CRS_FACTORY = new CRSFactory();

    /** A latitude past the pole by more than {@code PJ_EPS_LAT}: rejected, deterministically. */
    private static final double BAD_LATITUDE = 90.0000001;

    private static BulkCoordinateTransform bulk(DomainErrorPolicy policy) {
        CoordinateTransformFactory f = new CoordinateTransformFactory(policy);
        return f.createBulkTransform(CRS_FACTORY.createFromName("EPSG:4326"),
                CRS_FACTORY.createFromName("EPSG:3857"));
    }

    private static BulkCoordinateTransform bulk() {
        return bulk(DomainErrorPolicy.THROW);
    }

    // ==============================================================================================
    // The two modes
    // ==============================================================================================

    /**
     * A non-null status array means the batch completes: every point gets a byte, every failure gets
     * {@code NaN}, and the return value is the count. This is the mode the consumer uses, because it
     * turns fail-closed into one branch per geometry.
     */
    @Test
    public void nonNullStatusCompletesTheBatchAndCountsFailures() {
        double[] xy = {
            8.5, 47.4,
            0.0, BAD_LATITUDE,
            9.0, 48.0,
            1.0, BAD_LATITUDE,
            10.0, 49.0,
        };
        byte[] status = new byte[5];

        int failures = bulk().transform2D(xy, 0, 5, 2, status);

        assertEquals("two points are out of contract", 2, failures);
        assertEquals(TransformStatus.OK, status[0]);
        assertEquals(TransformStatus.ERR_INVALID_INPUT, status[1]);
        assertEquals(TransformStatus.OK, status[2]);
        assertEquals(TransformStatus.ERR_INVALID_INPUT, status[3]);
        assertEquals(TransformStatus.OK, status[4]);

        // The points after a failure were still transformed: a batch is not abandoned in this mode.
        assertTrue("point 4 must have been transformed", xy[8] > 1.0e6);

        // The sentinel is redundant with the status on purpose: a caller who ignores the array is no
        // worse off than today, because NaN is what a failing vertex already looks like downstream.
        assertTrue(Double.isNaN(xy[2]));
        assertTrue(Double.isNaN(xy[3]));
        assertTrue(Double.isNaN(xy[6]));
        assertTrue(Double.isNaN(xy[7]));
    }

    /**
     * A null status array means fail-fast: the batch is abandoned at the first failure and the
     * exception carries the reason. It is constructed once, when the loop is abandoned — the reason
     * the status array exists at all is that constructing one per point costs 1-10 microseconds
     * each.
     */
    @Test
    public void nullStatusFailsFastWithTheCause() {
        double[] xy = {8.5, 47.4, 0.0, BAD_LATITUDE, 9.0, 48.0};
        try {
            bulk().transform2D(xy, 0, 3, 2, null);
            fail("expected a throw for the out-of-contract point");
        } catch (Proj4jException e) {
            assertEquals(ErrorCause.INVALID_COORDINATE, e.cause());
        }
        // The point before the failure was transformed; the one after was not reached.
        assertTrue("point 0 was transformed before the batch was abandoned", xy[0] > 1.0e5);
        assertEquals("point 2 must not have been reached", 9.0, xy[4], 0.0);
    }

    /**
     * {@link DomainErrorPolicy#RETURN_NAN} is a caller who has explicitly asked never to be thrown
     * at for a per-coordinate failure, and that request outranks a null status array: they get the
     * {@code NaN} sentinel and the count, with no exception.
     */
    @Test
    public void returnNanPolicyDoesNotThrowEvenWithoutAStatusArray() {
        double[] xy = {8.5, 47.4, 0.0, BAD_LATITUDE, 9.0, 48.0};
        int failures = bulk(DomainErrorPolicy.RETURN_NAN).transform2D(xy, 0, 3, 2, null);
        assertEquals(1, failures);
        assertTrue(Double.isNaN(xy[2]));
        assertTrue(Double.isNaN(xy[3]));
        assertTrue("the batch completed", xy[4] > 1.0e6);
    }

    /** With a status array the policy makes no difference: the array is the reporting channel. */
    @Test
    public void statusArrayBehavesTheSameUnderBothPolicies() {
        for (DomainErrorPolicy policy : DomainErrorPolicy.values()) {
            double[] xy = {8.5, 47.4, 0.0, BAD_LATITUDE};
            byte[] status = new byte[2];
            int failures = bulk(policy).transform2D(xy, 0, 2, 2, status);
            assertEquals(policy.name(), 1, failures);
            assertEquals(policy.name(), TransformStatus.OK, status[0]);
            assertEquals(policy.name(), TransformStatus.ERR_INVALID_INPUT, status[1]);
        }
    }

    // ==============================================================================================
    // Ordinates the transform does not own
    // ==============================================================================================

    /**
     * A stride of 4 over a buffer that also carries M and a flag — the shape a real geometry library
     * hands over. Those ordinates must survive both a success and a failure, or the bulk API cannot
     * be used on a live buffer at all.
     */
    @Test
    public void extraOrdinatesSurviveSuccessAndFailure() {
        double[] buf = {
            8.5, 47.4, 111.0, 222.0,
            0.0, BAD_LATITUDE, 333.0, 444.0,
            9.0, 48.0, 555.0, 666.0,
        };
        byte[] status = new byte[3];
        assertEquals(1, bulk().transform2D(buf, 0, 3, 4, status));

        assertEquals(111.0, buf[2], 0.0);
        assertEquals(222.0, buf[3], 0.0);
        assertEquals("M must survive a FAILED point too", 333.0, buf[6], 0.0);
        assertEquals(444.0, buf[7], 0.0);
        assertEquals(555.0, buf[10], 0.0);
        assertEquals(666.0, buf[11], 0.0);
    }

    /** A status array longer than the batch must not have its excess written. */
    @Test
    public void statusBeyondNumPtsIsUntouched() {
        double[] xy = {8.5, 47.4};
        byte[] status = {(byte) 77, (byte) 77, (byte) 77};
        bulk().transform2D(xy, 0, 1, 2, status);
        assertEquals(TransformStatus.OK, status[0]);
        assertEquals((byte) 77, status[1]);
        assertEquals((byte) 77, status[2]);
    }

    // ==============================================================================================
    // Height handling
    // ==============================================================================================

    /**
     * {@code transform2D} treats every point as having no height, exactly as
     * {@code ProjCoordinate.setValue(x, y)} does; {@code transform3D} carries one. For a target that
     * consumes the height — here a 7-parameter Helmert — the two therefore differ, and that
     * difference is the pre-existing "invented height" behaviour of the single-point path rather
     * than a bulk-specific quirk.
     */
    @Test
    public void heightIsAbsentIn2DAndCarriedIn3D() {
        CoordinateTransformFactory f = new CoordinateTransformFactory();
        BulkCoordinateTransform op = f.createBulkTransform(
                CRS_FACTORY.createFromName("EPSG:4326"),
                CRS_FACTORY.createFromName("EPSG:27700"));

        double[] xy = {-2.0, 52.0};
        double[] xyz = {-2.0, 52.0, 100.0};
        assertEquals(0, op.transform2D(xy, 0, 1, 2, null));
        assertEquals(0, op.transform3D(xyz, 0, 1, 3, null));

        assertTrue("a Helmert consumes z, so the eastings must differ",
                xy[0] != xyz[0] || xy[1] != xyz[1]);
        assertTrue("3D must write a height back", !Double.isNaN(xyz[2]));
    }

    /** A {@code NaN} z in a 3D batch means "no height", and comes back as {@code NaN}. */
    @Test
    public void nanHeightMeansNoHeightAndIsPreserved() {
        double[] xyz = {8.5, 47.4, Double.NaN};
        assertEquals(0, bulk().transform3D(xyz, 0, 1, 3, null));
        assertTrue("an absent height must stay absent", Double.isNaN(xyz[2]));
    }

    /** A null {@code z} array in the struct-of-arrays form is the 2D case, and writes no height. */
    @Test
    public void structOfArraysAcceptsANullHeightArray() {
        double[] xs = {8.5, 9.0};
        double[] ys = {47.4, 48.0};
        assertEquals(0, bulk().transform(xs, ys, null, 0, 2, null));
        assertTrue(xs[0] > 1.0e5);
        assertTrue(ys[1] > 1.0e6);
    }

    // ==============================================================================================
    // Argument validation: once per batch, never per point
    // ==============================================================================================

    @Test
    public void nullBufferIsApiMisuse() {
        assertMisuse(new Runnable() {
            public void run() {
                bulk().transform2D(null, 0, 1, 2, null);
            }
        });
        assertMisuse(new Runnable() {
            public void run() {
                bulk().transform(null, new double[1], null, 0, 1, null);
            }
        });
        assertMisuse(new Runnable() {
            public void run() {
                bulk().transform(new double[1], null, null, 0, 1, null);
            }
        });
    }

    @Test
    public void strideNarrowerThanTheCoordinateIsApiMisuse() {
        assertMisuse(new Runnable() {
            public void run() {
                bulk().transform2D(new double[10], 0, 2, 1, null);
            }
        });
        assertMisuse(new Runnable() {
            public void run() {
                bulk().transform3D(new double[10], 0, 2, 2, null);
            }
        });
    }

    @Test
    public void negativeOffsetOrCountIsApiMisuse() {
        assertMisuse(new Runnable() {
            public void run() {
                bulk().transform2D(new double[10], -1, 1, 2, null);
            }
        });
        assertMisuse(new Runnable() {
            public void run() {
                bulk().transform2D(new double[10], 0, -1, 2, null);
            }
        });
    }

    /**
     * A buffer too short is rejected <b>before</b> anything is written. The alternative — an
     * {@code ArrayIndexOutOfBoundsException} from inside the loop — leaves the caller's buffer half
     * transformed, which is the one outcome worse than a clean refusal.
     */
    @Test
    public void tooShortBufferIsRejectedBeforeAnythingIsWritten() {
        double[] xy = {8.5, 47.4, 9.0, 48.0};
        double[] before = xy.clone();
        try {
            bulk().transform2D(xy, 0, 3, 2, null);
            fail("expected API_MISUSE: 3 points do not fit in 4 ordinates");
        } catch (Proj4jException e) {
            assertEquals(ErrorCause.API_MISUSE, e.cause());
        }
        for (int i = 0; i < xy.length; i++) {
            assertEquals("index " + i + " must not have been written", before[i], xy[i], 0.0);
        }
    }

    /**
     * A status array shorter than the batch is misuse, not a truncated report: a caller who read the
     * first {@code status.length} entries would conclude the rest of the geometry succeeded.
     */
    @Test
    public void shortStatusArrayIsApiMisuse() {
        assertMisuse(new Runnable() {
            public void run() {
                bulk().transform2D(new double[10], 0, 5, 2, new byte[4]);
            }
        });
    }

    /** The overflow guard: a range whose last index overflows {@code int} must still be rejected. */
    @Test
    public void aRangeThatOverflowsIntIsRejectedRatherThanWrapping() {
        assertMisuse(new Runnable() {
            public void run() {
                bulk().transform2D(new double[16], 0, 1 << 30, 4, null);
            }
        });
    }

    // ==============================================================================================
    // Operation-level failures
    // ==============================================================================================

    /**
     * A source CRS with no inverse is an <em>operation</em> failure, so it throws once per batch
     * rather than filling the status array with a per-point code. Recording it per point would
     * report a planning-time defect once per vertex.
     */
    @Test
    public void aSourceWithNoInverseThrowsOnceRatherThanPerPoint() {
        // +proj=august, not +proj=wintri. wintri USED to be forward-only here, which made it the
        // obvious example; it is not any more - aitoff.cpp has had a Newton-Raphson inverse since
        // 2015 and AitoffProjection now ports it, which closed 16 builtins.gie assertions. August
        // Epicycloidal is declared "Misc Sph, no inv" upstream and has no projectInverse here.
        CoordinateReferenceSystem forwardOnly =
                CRS_FACTORY.createFromParameters("august", "+proj=august +datum=WGS84");
        CoordinateReferenceSystem wgs84 = CRS_FACTORY.createFromName("EPSG:4326");
        BulkCoordinateTransform op =
                new CoordinateTransformFactory().createBulkTransform(forwardOnly, wgs84);

        double[] xy = new double[20];
        byte[] status = new byte[10];
        try {
            op.transform2D(xy, 0, 10, 2, status);
            fail("expected NO_INVERSE_AVAILABLE");
        } catch (Proj4jException e) {
            assertEquals(ErrorCause.NO_INVERSE_AVAILABLE, e.cause());
        }
        assertEquals("nothing may have been recorded", TransformStatus.OK, status[0]);
    }

    // ==============================================================================================
    // The API surface
    // ==============================================================================================

    /**
     * {@code StagedApis} in the benchmark module discovers
     * {@code org.locationtech.proj4j.BulkCoordinateTransform} by name and requires the transform the
     * factory returns to be an instance of it. Pinned here so that renaming or unhooking the
     * interface fails a core test rather than silently reverting the staged benchmark to throwing
     * from {@code @Setup}.
     */
    @Test
    public void theTransformFactoryReturnsSomethingTheBulkApiCanAdapt() throws Exception {
        Class<?> byName = Class.forName("org.locationtech.proj4j.BulkCoordinateTransform");
        assertSame(BulkCoordinateTransform.class, byName);

        CoordinateTransformFactory f = new CoordinateTransformFactory();
        CoordinateReferenceSystem src = CRS_FACTORY.createFromName("EPSG:4326");
        CoordinateReferenceSystem tgt = CRS_FACTORY.createFromName("EPSG:32633");

        CoordinateTransform single = f.createTransform(src, tgt);
        assertTrue("a CoordinateTransform must be castable to the bulk API",
                byName.isInstance(single));
        assertTrue(single instanceof BasicCoordinateTransform);

        // The four signatures, resolved exactly as StagedApis resolves them.
        byName.getMethod("transform2D", double[].class, int.class, int.class, int.class,
                byte[].class);
        byName.getMethod("transform3D", double[].class, int.class, int.class, int.class,
                byte[].class);
        byName.getMethod("transform2D", double[].class, int.class, int.class, double[].class,
                int.class, int.class, int.class, byte[].class);
        byName.getMethod("transform", double[].class, double[].class, double[].class, int.class,
                int.class, byte[].class);

        assertNotNull(f.createBulkTransform(src, tgt));
    }

    /** The status-code taxonomy round trips to {@link ErrorCause} and back where it is total. */
    @Test
    public void statusCodesMapToTheErrorTaxonomy() {
        assertNull(TransformStatus.cause(TransformStatus.OK));
        assertTrue(TransformStatus.isOk(TransformStatus.OK));

        byte[] codes = {
            TransformStatus.ERR_COORD_OUT_OF_DOMAIN,
            TransformStatus.ERR_NUMERICAL_FAILURE,
            TransformStatus.ERR_MISSING_GRID,
            TransformStatus.ERR_OUTSIDE_GRID_EXTENT,
            TransformStatus.ERR_OUTSIDE_AREA_OF_USE,
            TransformStatus.ERR_NO_OPERATION,
            TransformStatus.ERR_INVALID_INPUT,
        };
        for (byte code : codes) {
            ErrorCause cause = TransformStatus.cause(code);
            assertNotNull(TransformStatus.name(code), cause);
            assertTrue(TransformStatus.name(code) + " must not be OK",
                    !TransformStatus.isOk(code));
        }

        // Every per-coordinate cause has a code; nothing else does.
        for (ErrorCause cause : ErrorCause.values()) {
            byte code = TransformStatus.forCause(cause);
            if (cause.isCoordinateError()) {
                assertTrue(cause + " is per-coordinate and must have a status code",
                        code != TransformStatus.NOT_A_COORDINATE_ERROR);
            } else {
                assertEquals(cause + " is not per-coordinate and must not have a status code",
                        TransformStatus.NOT_A_COORDINATE_ERROR, code);
            }
        }
    }

    // ==============================================================================================
    // Sharing across threads
    // ==============================================================================================

    /**
     * The pooled scratch coordinate is the one piece of per-call state the bulk path holds, so the
     * pool is the one place a shared transform could tear. Ten threads, one transform, a barrier
     * start, and the assertion is <b>bitwise</b> against a single-threaded reference — a tolerance
     * assertion would pass through a torn double, which defeats the purpose.
     *
     * @throws Exception if a worker fails
     */
    @Test(timeout = 60000)
    public void oneTransformIsSafeToShareAcrossThreads() throws Exception {
        final BulkCoordinateTransform op = bulk();
        final int points = 500;
        final double[] template = new double[points * 2];
        for (int i = 0; i < points; i++) {
            template[i * 2] = -170.0 + i * 0.5;
            template[i * 2 + 1] = -80.0 + i * 0.3;
        }

        double[] expected = template.clone();
        assertEquals(0, op.transform2D(expected, 0, points, 2, new byte[points]));

        final int threads = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        final CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            Future<?>[] futures = new Future<?>[threads];
            for (int t = 0; t < threads; t++) {
                futures[t] = pool.submit(new Callable<double[]>() {
                    public double[] call() throws Exception {
                        barrier.await();
                        double[] buf = template.clone();
                        byte[] status = new byte[points];
                        for (int rep = 0; rep < 20; rep++) {
                            System.arraycopy(template, 0, buf, 0, template.length);
                            if (op.transform2D(buf, 0, points, 2, status) != 0) {
                                throw new IllegalStateException("unexpected failure");
                            }
                        }
                        return buf;
                    }
                });
            }
            for (int t = 0; t < threads; t++) {
                double[] got = (double[]) futures[t].get(50, TimeUnit.SECONDS);
                for (int i = 0; i < got.length; i++) {
                    assertEquals("thread " + t + " ordinate " + i + " is not bit-identical to the "
                                    + "single-threaded result",
                            Double.doubleToRawLongBits(expected[i]),
                            Double.doubleToRawLongBits(got[i]));
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ==============================================================================================
    // Helpers
    // ==============================================================================================

    private static void assertMisuse(Runnable call) {
        try {
            call.run();
            fail("expected CrsTransformException with API_MISUSE");
        } catch (Proj4jException e) {
            assertEquals(ErrorCause.API_MISUSE, e.cause());
        }
    }
}

/*******************************************************************************
 * Copyright 2009, 2017 Martin Davis
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
 *******************************************************************************/
package org.locationtech.proj4j;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Proves that invoking one {@link CoordinateTransform} repeatedly on a single thread is
 * <em>bitwise</em> idempotent, including the {@code z} ordinate and including the paths that carry
 * data-dependent iteration.
 * <p>
 * <b>What this class used to assert, and why that was nothing.</b> It made two sequential calls on one
 * thread and compared them with {@code assertTrue(destPt.equals(destPt2))}.
 * {@link ProjCoordinate#equals(Object)} ({@code ProjCoordinate.java:303-315}) compares only {@code x}
 * and {@code y} — <b>{@code z} is not compared, and neither is it in {@code hashCode()}</b> — so a
 * transform that returned a different height on the second call passed silently. Since both calls
 * shared one thread and no state was mutated between them, the assertion could only have failed if
 * {@code transform} were non-deterministic in {@code x}/{@code y} alone.
 * <p>
 * Thread-safety of a shared transform is covered separately and properly by
 * {@code org.locationtech.proj4j.concurrent.SharedTransformConcurrencyTest}, which also covers the
 * aliased {@code src == dst} case. This class therefore keeps only the question that is genuinely
 * about <em>repetition</em> rather than about <em>concurrency</em>, and asserts it in the one way that
 * can detect the failure:
 * <ul>
 *   <li><b>Bitwise</b>, via {@link Double#doubleToRawLongBits(double)}, not with a tolerance. A
 *       transform that drifts by a fraction of a nanometre per call still produces a finite, plausible
 *       coordinate, and a tolerance-based assertion cannot see it. Raw bits also distinguish
 *       {@code -0.0} from {@code 0.0} and NaN payloads, both of which are real distinctions at the
 *       equator and the antimeridian.</li>
 *   <li><b>On all three ordinates</b>, so the {@code equals()} blind spot cannot hide a drifting
 *       height — which matters here because a 2D datum shift routed through geocentric coordinates
 *       invents a {@code z} (EPSG:4326 -&gt; EPSG:27700 with no input height returns
 *       {@code z ~= -49.85}).</li>
 *   <li><b>Over the grid-shift path.</b> {@code Grid.nad_cvt} iterates up to {@code MAX_TRY = 9} times
 *       with a data-dependent trip count, and {@code Grid.java:77} has no cache, so a
 *       {@code +nadgrids=} transform re-resolves and re-allocates on some paths. That combination —
 *       data-dependent iteration plus per-call allocation — is the only place in the tree where
 *       repeated invocation could plausibly diverge, and it was untested.</li>
 * </ul>
 */
public class RepeatedTransformTest {

    /** Enough repetitions to catch per-call accumulation, cheap enough to stay in the fast suite. */
    private static final int REPEATS = 200;

    private static final CRSFactory CRS_FACTORY = new CRSFactory();
    private static final CoordinateTransformFactory CT_FACTORY = new CoordinateTransformFactory();

    /** WGS84 -&gt; OSGB36 (7-parameter Helmert; invents a z from a 2D input). */
    @Test
    public void repeatedInvocationIsBitwiseIdempotent() {
        CoordinateReferenceSystem src = CRS_FACTORY.createFromName("epsg:4326");
        CoordinateReferenceSystem dest = CRS_FACTORY.createFromName("epsg:27700");
        assertRepeatable(CT_FACTORY.createTransform(src, dest),
                new ProjCoordinate(0.899167, 51.357216));
    }

    /**
     * The grid-shift path: {@code +nadgrids=nzgd2kgrid0005.gsb}, i.e. {@code Grid.nad_cvt}'s
     * data-dependent iteration. Same CRS pair as {@code CoordinateTransformTest.testEPSG_27250}.
     */
    @Test
    public void repeatedInvocationThroughAGridShiftIsBitwiseIdempotent() {
        CoordinateReferenceSystem src =
                CRS_FACTORY.createFromParameters("wgs84", "+proj=latlong +datum=WGS84");
        CoordinateReferenceSystem dest = CRS_FACTORY.createFromParameters("nzgd49",
                "+proj=tmerc +lat_0=-36.87986527777778 +lon_0=174.7643393611111 +k=0.9999"
                        + " +x_0=300000 +y_0=700000 +datum=nzgd49 +units=m"
                        + " +towgs84=59.47,-5.04,187.44,0.47,-0.1,1.024,-4.5993"
                        + " +nadgrids=nzgd2kgrid0005.gsb +no_defs");
        assertRepeatable(CT_FACTORY.createTransform(src, dest),
                new ProjCoordinate(174.7772114, -41.2887953));
    }

    /** A 3D input, so that a drifting {@code z} has somewhere to drift from. */
    @Test
    public void repeatedInvocationPreservesTheThirdOrdinateBitwise() {
        CoordinateReferenceSystem src = CRS_FACTORY.createFromName("epsg:2994");
        CoordinateReferenceSystem dest =
                CRS_FACTORY.createFromParameters("geocent", "+proj=geocent +datum=WGS84");
        assertRepeatable(CT_FACTORY.createTransform(src, dest),
                new ProjCoordinate(635788, 850485, 81));
    }

    /**
     * Invokes {@code transform} {@link #REPEATS} times with the same input and requires every result
     * to be bit-identical to the first, on x, y and z.
     * <p>
     * A fresh output object is used on every call so that the assertion is about the transform's own
     * state rather than about whatever happened to be left in a reused buffer; the reused-buffer case
     * is then checked separately at the end.
     */
    private static void assertRepeatable(CoordinateTransform transform, ProjCoordinate in) {
        ProjCoordinate first = new ProjCoordinate();
        transform.transform(copyOf(in), first);

        for (int i = 1; i < REPEATS; i++) {
            ProjCoordinate out = new ProjCoordinate();
            transform.transform(copyOf(in), out);
            assertBitwiseEqual("call " + i, first, out);
        }

        // And with a single output object reused across calls, which is the pattern callers actually
        // use in a per-vertex loop.
        ProjCoordinate reused = new ProjCoordinate();
        for (int i = 0; i < REPEATS; i++) {
            transform.transform(copyOf(in), reused);
        }
        assertBitwiseEqual("reused output buffer", first, reused);
    }

    /**
     * Defends against a transform that mutates its input: every call gets its own copy, so an
     * in-place modification cannot make the second call's input differ from the first's.
     */
    private static ProjCoordinate copyOf(ProjCoordinate p) {
        return new ProjCoordinate(p.x, p.y, p.z);
    }

    private static void assertBitwiseEqual(String what, ProjCoordinate expected, ProjCoordinate actual) {
        assertEquals(what + ": x differs (" + expected.x + " vs " + actual.x + ")",
                Double.doubleToRawLongBits(expected.x), Double.doubleToRawLongBits(actual.x));
        assertEquals(what + ": y differs (" + expected.y + " vs " + actual.y + ")",
                Double.doubleToRawLongBits(expected.y), Double.doubleToRawLongBits(actual.y));
        assertEquals(what + ": z differs (" + expected.z + " vs " + actual.z + ")",
                Double.doubleToRawLongBits(expected.z), Double.doubleToRawLongBits(actual.z));
    }
}

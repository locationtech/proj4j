/*
 * Copyright 2026, PROJ4J contributors
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
package org.locationtech.proj4j.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CrsCreationException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * The contract of {@link BallparkPolicy}: a datum change that would not actually be performed is
 * refused <b>when the operation is created</b>, not discovered per row.
 *
 * <p>{@code EPSG:4267} to {@code EPSG:4269} is the case that matters. NAD27 declares four grid
 * files, all {@code @}-optional, and a stock deployment has one of them (the Canadian
 * {@code ntv1_can.dat}, whose extent starts at 40&deg;N and so covers neither Kansas nor San
 * Francisco). PROJ's {@code @} semantics make the other three vanish silently, the shift does not
 * happen, and the coordinate that comes out is finite, plausible, and 95.573&nbsp;m wrong at San
 * Francisco &mdash; measured. There is no downstream check that can catch that, so the check has to
 * be here.
 */
public class BallparkPolicyTest {

    /** San Francisco. Deliberately not near (0, 0) and deliberately inside CONUS. */
    private static final double LON = -122.4;
    private static final double LAT = 37.8;

    @Test
    public void rejectIsTheDefault() {
        assertEquals(BallparkPolicy.REJECT, ProjContext.DEFAULT.ballparkPolicy());
        assertEquals(BallparkPolicy.REJECT, Proj.defaultContext().ballparkPolicy());
    }

    /**
     * The headline assertion: it throws at <b>creation</b>. If this ever starts throwing from
     * {@code transform} instead, the whole point has been lost -- the consumer's constraint is that
     * a four-million-row job must fail on its first line, not four million times.
     */
    @Test
    public void ballparkIsRejectedWhenTheOperationIsCreated() {
        try {
            CrsOperation op = Proj.createCrsToCrs("EPSG:4267", "EPSG:4269");
            fail("expected BALLPARK_REJECTED at creation time, but got an operation: "
                    + op.describe());
        } catch (CrsCreationException expected) {
            assertEquals(ErrorCause.BALLPARK_REJECTED, expected.cause());
            // The message has to be actionable: it must name the grids and say what to do.
            String m = expected.getMessage();
            assertTrue("message must name the CRSs: " + m, m.contains("EPSG:4267"));
            assertTrue("message must name the datums: " + m, m.contains("NAD27"));
            assertTrue("message must name each unreachable grid: " + m,
                    m.contains("ntv2_0.gsb"));
            assertTrue("message must say the grid shift cannot be performed: " + m,
                    m.contains("declared grid shift cannot be performed"));
            assertTrue("message must offer the opt-in: " + m, m.contains("BallparkPolicy.ALLOW"));
            assertTrue("message must point at the unchanged legacy path: " + m,
                    m.contains("CoordinateTransformFactory"));
        }
    }

    /**
     * The middle path: {@link GridPolicy#WARN} is "I accept incomplete grid coverage and want it on
     * the record", and it must be enough on its own -- a caller should not have to reach for
     * {@link BallparkPolicy#ALLOW}, which is a much broader concession.
     */
    @Test
    public void gridPolicyWarnIsEnoughOnItsOwn() {
        ProjContext warn = ProjContext.builder().gridPolicy(GridPolicy.WARN).build();
        CrsOperation op = Proj.createCrsToCrs("EPSG:4267", "EPSG:4269", warn);

        assertEquals("REJECT is still in force; it simply has nothing to reject",
                BallparkPolicy.REJECT, op.context().ballparkPolicy());
        assertFalse(op.isBallparkTransformation());
        assertFalse("the unreachable grids must still be listed", op.missingGrids().isEmpty());
        assertFalse("and still warned about", op.warnings().isEmpty());
        assertTrue(op.transform(new ProjCoordinate(LON, LAT)).hasValidXandYOrdinates());
    }

    /** The rejection is a property of the ErrorCause taxonomy, not just of a message. */
    @Test
    public void ballparkRejectedIsAnOperationSelectionError() {
        assertTrue(ErrorCause.BALLPARK_REJECTED.isOperationError());
        assertTrue(ErrorCause.BALLPARK_REJECTED.isNoUsableOperation());
        assertFalse("a ballpark rejection is not a per-coordinate error: it must not be caught by "
                + "a per-vertex handler and turned into a skipped row",
                ErrorCause.BALLPARK_REJECTED.isCoordinateError());
        assertEquals("crs.ballpark_rejected", ErrorCause.BALLPARK_REJECTED.metricKey());
    }

    /** Opting in is possible, is explicit, and is recorded on the resulting operation. */
    @Test
    public void allowBuildsTheOperationAndFlagsIt() {
        ProjContext allow = ProjContext.builder().ballparkPolicy(BallparkPolicy.ALLOW).build();
        CrsOperation op = Proj.createCrsToCrs("EPSG:4267", "EPSG:4269", allow);

        assertTrue("an allowed ballpark operation must say so", op.isBallparkTransformation());
        assertTrue(op.ballparkReason().isPresent());
        assertFalse("a ballpark operation never has a stated accuracy, in PROJ either",
                op.accuracy().isPresent());
        assertFalse("the missing grids must be listed", op.missingGrids().isEmpty());
        assertFalse("the reason must be in warnings(), not only in a log line",
                op.warnings().isEmpty());

        // And it still produces an all-finite coordinate rather than a sentinel.
        ProjCoordinate out = op.transform(new ProjCoordinate(LON, LAT));
        assertNotNull(out);
        assertTrue(out.hasValidXandYOrdinates());
    }

    /**
     * A pair with no datum change at all must not be caught by any of this. If the ballpark rule
     * ever became over-eager, this is what would notice.
     */
    @Test
    public void sameDatumIsNotBallpark() {
        CrsOperation op = Proj.createCrsToCrs("EPSG:4326", "EPSG:32633");
        assertFalse(op.isBallparkTransformation());
        assertTrue(op.missingGrids().isEmpty());
        ProjCoordinate out = op.transform(new ProjCoordinate(15.0, 50.0));
        assertTrue(out.hasValidXandYOrdinates());
    }

    /**
     * {@code EPSG:3857} declares {@code +nadgrids=@null} -- PROJ's deliberate no-shift marker,
     * which <em>is</em> shipped. It must not be mistaken for a missing grid, or the single most
     * common transformation in the world would stop working.
     */
    @Test
    public void theNullGridIsNotAMissingGrid() {
        Crs merc = Proj.createCrs("EPSG:3857");
        assertTrue("+nadgrids=@null must resolve: " + merc.describe(),
                merc.missingGrids().isEmpty());
        CrsOperation op = Proj.createCrsToCrs("EPSG:4326", "EPSG:3857");
        assertFalse(op.isBallparkTransformation());
    }

    /** A bare {@code +ellps=} on one side is the legacy engine's silent no-op, i.e. rule (c). */
    @Test
    public void anUnknownDatumOnOneSideIsBallpark() {
        try {
            Proj.createCrsToCrs("+proj=longlat +ellps=clrk66", "EPSG:4326");
            fail("expected BALLPARK_REJECTED: a bare +ellps= has no transformation to WGS 84, so "
                    + "the engine would apply no datum shift and say nothing");
        } catch (CrsCreationException expected) {
            assertEquals(ErrorCause.BALLPARK_REJECTED, expected.cause());
            assertTrue(expected.getMessage().contains("no datum shift"));
        }
    }
}

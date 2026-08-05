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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

/**
 * Tests for {@link GieIoUnits} against PROJ 9.8.1 {@code src/proj_internal.h:192-201},
 * {@code src/internal.cpp:49-61} and {@code src/coordinates.cpp:52-93}.
 */
public class GieIoUnitsTest {

    @Test
    public void codesMatchPjIoUnits() {
        assertEquals(0, GieIoUnits.WHATEVER.code());
        assertEquals(1, GieIoUnits.CLASSIC.code());
        assertEquals(2, GieIoUnits.PROJECTED.code());
        assertEquals(3, GieIoUnits.CARTESIAN.code());
        assertEquals(4, GieIoUnits.RADIANS.code());
        assertEquals(5, GieIoUnits.DEGREES.code());
        assertEquals(6, GieIoUnits.values().length);
    }

    @Test
    public void fromCodeRoundTrips() {
        for (final GieIoUnits u : GieIoUnits.values()) {
            assertSame(u, GieIoUnits.fromCode(u.code()));
        }
        try {
            GieIoUnits.fromCode(6);
            fail("expected IllegalArgumentException");
        } catch (final IllegalArgumentException expected) {
            // ok
        }
    }

    /** The one non-obvious rule: pj_left/pj_right fold CLASSIC to PROJECTED on every read. */
    @Test
    public void classicFoldsToProjected() {
        assertSame(GieIoUnits.PROJECTED, GieIoUnits.CLASSIC.folded());
    }

    @Test
    public void everythingElseFoldsToItself() {
        for (final GieIoUnits u : GieIoUnits.values()) {
            if (u != GieIoUnits.CLASSIC) {
                assertSame(u, u.folded());
            }
        }
    }

    @Test
    public void foldingIsIdempotent() {
        for (final GieIoUnits u : GieIoUnits.values()) {
            assertSame(u.folded(), u.folded().folded());
        }
    }

    @Test
    public void pjLeftAndPjRightSwapWhenInverted() {
        assertSame(GieIoUnits.RADIANS,
                GieIoUnits.pjLeft(GieIoUnits.RADIANS, GieIoUnits.CARTESIAN, false));
        assertSame(GieIoUnits.CARTESIAN,
                GieIoUnits.pjRight(GieIoUnits.RADIANS, GieIoUnits.CARTESIAN, false));
        assertSame(GieIoUnits.CARTESIAN,
                GieIoUnits.pjLeft(GieIoUnits.RADIANS, GieIoUnits.CARTESIAN, true));
        assertSame(GieIoUnits.RADIANS,
                GieIoUnits.pjRight(GieIoUnits.RADIANS, GieIoUnits.CARTESIAN, true));
    }

    @Test
    public void pjLeftAndPjRightAlsoFold() {
        assertSame(GieIoUnits.PROJECTED,
                GieIoUnits.pjRight(GieIoUnits.RADIANS, GieIoUnits.CLASSIC, false));
        assertSame(GieIoUnits.PROJECTED,
                GieIoUnits.pjLeft(GieIoUnits.RADIANS, GieIoUnits.CLASSIC, true));
    }

    /** output(FORWARD) reads pj_right; output(INVERSE) reads pj_left. */
    @Test
    public void outputUnitsPicksTheOppositeSide() {
        assertSame(GieIoUnits.CARTESIAN, GieIoUnits.outputUnits(
                GieIoUnits.RADIANS, GieIoUnits.CARTESIAN, false, GieDirection.FORWARD));
        assertSame(GieIoUnits.RADIANS, GieIoUnits.outputUnits(
                GieIoUnits.RADIANS, GieIoUnits.CARTESIAN, false, GieDirection.INVERSE));
    }

    /**
     * The default for every {@code PROJ_HEAD} projection is
     * {@code left = RADIANS, right = CLASSIC} ({@code proj_internal.h:882-883}). This single
     * fact is why the corpus is shaped the way it is: forward projection tests are Euclidean,
     * {@code direction inverse} projection tests are geodesic-from-radians.
     */
    @Test
    public void defaultProjHeadUnitsExplainTheCorpusShape() {
        final GieIoUnits left = GieIoUnits.RADIANS;
        final GieIoUnits right = GieIoUnits.CLASSIC;

        final GieIoUnits fwdOut = GieIoUnits.outputUnits(left, right, false, GieDirection.FORWARD);
        final GieIoUnits invOut = GieIoUnits.outputUnits(left, right, false, GieDirection.INVERSE);

        assertSame(GieIoUnits.PROJECTED, fwdOut);
        assertSame(GieIoUnits.RADIANS, invOut);
        assertSame(GieMetric.EUCLIDEAN_METRES, GieMetric.select(fwdOut, 0.0, 0.0));
        assertSame(GieMetric.GEODESIC_FROM_RADIANS, GieMetric.select(invOut, 0.0, 0.0));
    }

    /** {@code +inv} on the operation exchanges the sides, and so exchanges the metrics. */
    @Test
    public void invertedProjHeadExchangesTheMetrics() {
        final GieIoUnits left = GieIoUnits.RADIANS;
        final GieIoUnits right = GieIoUnits.CLASSIC;

        assertSame(GieIoUnits.RADIANS,
                GieIoUnits.outputUnits(left, right, true, GieDirection.FORWARD));
        assertSame(GieIoUnits.PROJECTED,
                GieIoUnits.outputUnits(left, right, true, GieDirection.INVERSE));
    }
}

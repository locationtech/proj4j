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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link GieMetric#select} against the branch chain at
 * PROJ 9.8.1 {@code src/apps/gie.cpp:1136-1166}.
 */
public class GieMetricTest {

    private static final double NAN = Double.NaN;

    /**
     * The declaration order is the source order of the {@code if}/{@code else if} chain, and is
     * part of the contract: a reader comparing this enum with {@code gie.cpp} must be able to
     * do so top to bottom.
     */
    @Test
    public void constantsAreInSourceOrder() {
        assertArrayEquals(
                new GieMetric[]{
                        GieMetric.NAN_BOTH,
                        GieMetric.GEODESIC_FROM_RADIANS,
                        GieMetric.GEODESIC_FROM_DEGREES,
                        GieMetric.EUCLIDEAN_METRES},
                GieMetric.values());
    }

    /**
     * The load-bearing case. Four of the six unit values choose the linear metric; getting one
     * of these wrong inflates the deviation by a factor of about 111 thousand.
     */
    @Test
    public void linearUnitsAllSelectEuclidean() {
        assertSame(GieMetric.EUCLIDEAN_METRES, GieMetric.select(GieIoUnits.WHATEVER, 1.0, 2.0));
        assertSame(GieMetric.EUCLIDEAN_METRES, GieMetric.select(GieIoUnits.CLASSIC, 1.0, 2.0));
        assertSame(GieMetric.EUCLIDEAN_METRES, GieMetric.select(GieIoUnits.PROJECTED, 1.0, 2.0));
        assertSame(GieMetric.EUCLIDEAN_METRES, GieMetric.select(GieIoUnits.CARTESIAN, 1.0, 2.0));
    }

    @Test
    public void radiansSelectsGeodesicFromRadians() {
        assertSame(GieMetric.GEODESIC_FROM_RADIANS, GieMetric.select(GieIoUnits.RADIANS, 1.0, 2.0));
    }

    @Test
    public void degreesSelectsGeodesicFromDegrees() {
        assertSame(GieMetric.GEODESIC_FROM_DEGREES, GieMetric.select(GieIoUnits.DEGREES, 1.0, 2.0));
    }

    /** CLASSIC must not fall through unrecognised; select() folds it. */
    @Test
    public void classicIsFoldedNotIgnored() {
        assertSame(GieMetric.select(GieIoUnits.PROJECTED, 1.0, 2.0),
                GieMetric.select(GieIoUnits.CLASSIC, 1.0, 2.0));
    }

    @Test
    public void nanOnBothSidesPreemptsEveryUnit() {
        for (final GieIoUnits u : GieIoUnits.values()) {
            assertSame("units=" + u, GieMetric.NAN_BOTH, GieMetric.select(u, NAN, NAN));
        }
    }

    /** NaN on one side only is NOT the NAN_BOTH branch -- it must fall through and fail. */
    @Test
    public void nanOnOneSideOnlyDoesNotPreempt() {
        for (final GieIoUnits u : GieIoUnits.values()) {
            assertSame("expected NaN, units=" + u,
                    GieMetric.select(u, 1.0, 2.0), GieMetric.select(u, NAN, 2.0));
            assertSame("got NaN, units=" + u,
                    GieMetric.select(u, 1.0, 2.0), GieMetric.select(u, 1.0, NAN));
        }
    }

    @Test
    public void isAngularIsExactlyTheTwoGeodesicBranches() {
        assertFalse(GieMetric.NAN_BOTH.isAngular());
        assertTrue(GieMetric.GEODESIC_FROM_RADIANS.isAngular());
        assertTrue(GieMetric.GEODESIC_FROM_DEGREES.isAngular());
        assertFalse(GieMetric.EUCLIDEAN_METRES.isAngular());
    }
}

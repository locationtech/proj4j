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
 * Tests for {@link GieDirection} against {@code PJ_DIRECTION} and
 * {@code pj_opposite_direction()} ({@code src/coordinates.cpp:48-50}).
 */
public class GieDirectionTest {

    @Test
    public void codesMatchPjDirection() {
        assertEquals(1, GieDirection.FORWARD.code());
        assertEquals(-1, GieDirection.INVERSE.code());
        assertEquals(2, GieDirection.values().length);
    }

    @Test
    public void oppositeNegatesTheCode() {
        for (final GieDirection d : GieDirection.values()) {
            assertEquals(-d.code(), d.opposite().code());
        }
    }

    @Test
    public void oppositeIsAnInvolution() {
        for (final GieDirection d : GieDirection.values()) {
            assertSame(d, d.opposite().opposite());
        }
    }

    @Test
    public void fromCodeRoundTrips() {
        assertSame(GieDirection.FORWARD, GieDirection.fromCode(1));
        assertSame(GieDirection.INVERSE, GieDirection.fromCode(-1));
        try {
            GieDirection.fromCode(0); // PJ_IDENT -- never reaches the comparator
            fail("expected IllegalArgumentException");
        } catch (final IllegalArgumentException expected) {
            // ok
        }
    }
}

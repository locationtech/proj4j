/*
 * Copyright 2026 The Proj4J Contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.locationtech.proj4j.conformance.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GieCoordParserTest {

    private static final double EPS = 1e-12;

    @Test
    @DisplayName("dimensionsGiven is 2, 3 or 4 as the line dictates")
    void dimensionsGiven() {
        assertEquals(2, GieCoordParser.parseCoord("1 2").dimensionsGiven());
        assertEquals(3, GieCoordParser.parseCoord("1 2 3").dimensionsGiven());
        assertEquals(4, GieCoordParser.parseCoord("1 2 3 4").dimensionsGiven());
        // Whitespace has already been collapsed by pj_shrink, but be robust.
        assertEquals(3, GieCoordParser.parseCoord("  1   2   3  ").dimensionsGiven());
    }

    @Test
    @DisplayName("a fifth number is ignored: at most four are read")
    void atMostFour() {
        GieCoord c = GieCoordParser.parseCoord("1 2 3 4 5");
        assertEquals(4, c.dimensionsGiven());
        assertEquals(4.0, c.t());
    }

    @Test
    @DisplayName("unparsed ordinates are zero, not garbage")
    void unparsedOrdinatesAreZero() {
        GieCoord c = GieCoordParser.parseCoord("12 55");
        assertEquals(12.0, c.x());
        assertEquals(55.0, c.y());
        assertEquals(0.0, c.z());
        assertEquals(0.0, c.t());
    }

    @Test
    @DisplayName("fewer than two numbers is an error coordinate, all HUGE_VAL")
    void fewerThanTwoIsAnError() {
        for (String s : new String[] {"", "1", "   ", "abc", "1 abc"}) {
            GieCoord c = GieCoordParser.parseCoord(s);
            assertTrue(c.isError(), "expected an error coordinate for [" + s + "]");
            assertEquals(GieCoord.HUGE_VAL, c.x());
            assertEquals(GieCoord.HUGE_VAL, c.y());
            assertEquals(GieCoord.HUGE_VAL, c.z());
            assertEquals(GieCoord.HUGE_VAL, c.t());
        }
    }

    @Test
    @DisplayName("trailing junk after two good numbers is not an error")
    void trailingJunkIsTolerated() {
        // The real case: builtins.gie's non-strict block leaves the word
        // `unknown_keyword` appended to an expect line.
        GieCoord c = GieCoordParser.parseCoord(
                "222571.608757106 110653.326743030 unknown_keyword");
        assertFalse(c.isError());
        assertEquals(2, c.dimensionsGiven());
        assertEquals(222571.608757106, c.x());
        assertEquals(110653.326743030, c.y());
    }

    @Test
    @DisplayName("the literal token HUGE_VAL is a valid component")
    void hugeValToken() {
        GieCoord c = GieCoordParser.parseCoord("HUGE_VAL 1 2 HUGE_VAL");
        assertFalse(c.isError());
        assertEquals(4, c.dimensionsGiven());
        assertEquals(GieCoord.HUGE_VAL, c.x());
        assertEquals(1.0, c.y());
        assertEquals(2.0, c.z());
        assertEquals(GieCoord.HUGE_VAL, c.t());

        // deformation.gie / defmodel.gie use it in the time slot.
        GieCoord t = GieCoordParser.parseCoord("2 49 0 HUGE_VAL");
        assertEquals(4, t.dimensionsGiven());
        assertEquals(GieCoord.HUGE_VAL, t.t());
    }

    @Test
    @DisplayName("underscore-separated digits parse as one number")
    void underscoreDigits() {
        GieCoord c = GieCoordParser.parseCoord("6_098_907.825_05 1_000");
        assertEquals(2, c.dimensionsGiven());
        assertEquals(6098907.82505, c.x());
        assertEquals(1000.0, c.y());
    }

    @Test
    @DisplayName("DMS components fall back to proj_dmstor")
    void dmsFallback() {
        GieCoord a = GieCoordParser.parseCoord("83d10'W 43d45'N");
        assertEquals(2, a.dimensionsGiven());
        assertEquals(-(83 + 10 / 60.0), a.x(), EPS);
        assertEquals(43 + 45 / 60.0, a.y(), EPS);

        GieCoord b = GieCoordParser.parseCoord("-64d43'75.34 79d58'00.000\"W");
        assertEquals(2, b.dimensionsGiven());
        assertEquals(-(64 + 43 / 60.0 + 75.34 / 3600.0), b.x(), EPS);
        assertEquals(-(79 + 58 / 60.0), b.y(), EPS);
    }

    @Test
    @DisplayName("the DMS reading is only preferred when |d| < |dms| < |d| + 1")
    void dmsFallbackIsGuarded() {
        // -81d00'00.000: both parsers agree on -81, so the plain value stands,
        // but the end pointer must come from proj_dmstor or the next component
        // would start mid-token.
        GieCoord c = GieCoordParser.parseCoord("-81d00'00.000 42");
        assertFalse(c.isError());
        assertEquals(2, c.dimensionsGiven());
        assertEquals(-81.0, c.x(), EPS);
        assertEquals(42.0, c.y());
    }

    @Test
    @DisplayName("large projected coordinates are not mangled by the DMS retry")
    void projectedCoordinatesSurvive() {
        // 1501000.0 must stay 1501000.0; a blanket radians round-trip would
        // give 1501000.000000000233.
        GieCoord c = GieCoordParser.parseCoord("1501000.0 6098907.825");
        assertEquals(1501000.0, c.x());
        assertEquals(6098907.825, c.y());
    }

    @Test
    @DisplayName("a null argument string yields an error coordinate, not an exception")
    void nullIsAnError() {
        assertTrue(GieCoordParser.parseCoord(null).isError());
    }
}

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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProjDmsToRTest {

    private static final double EPS = 1e-12;

    private static double degrees(String s) {
        return Math.toDegrees(ProjDmsToR.dmstor(s).radians);
    }

    @Test
    @DisplayName("a trailing hemisphere letter sets the sign: W and S are negative")
    void hemisphereLetters() {
        assertEquals(-(83 + 10 / 60.0), degrees("83d10'W"), EPS);
        assertEquals(43 + 45 / 60.0, degrees("43d45'N"), EPS);
        assertEquals(-(79 + 58 / 60.0), degrees("79d58'00.000\"W"), EPS);
        assertEquals(12.5, degrees("12d30'E"), EPS);
        assertEquals(-12.5, degrees("12d30'S"), EPS);
    }

    @Test
    @DisplayName("a leading sign works, and seconds may exceed 60")
    void leadingSignAndOversizeSeconds() {
        // -64d43'75.34 : the corpus really does write 75.34 seconds.
        assertEquals(-(64 + 43 / 60.0 + 75.34 / 3600.0), degrees("-64d43'75.34"), EPS);
    }

    @Test
    @DisplayName("the end position covers the whole DMS token")
    void endPosition() {
        assertEquals(7, ProjDmsToR.dmstor("83d10'W").end);
        assertEquals(7, ProjDmsToR.dmstor("43d45'N").end);
        assertEquals(12, ProjDmsToR.dmstor("-64d43'75.34").end);
        assertEquals(14, ProjDmsToR.dmstor("79d58'00.000\"W").end);
        // -81d00'00.000 parses to -81 under both parsers, but only this one
        // reports the correct end - which is why parse_coord has a special case.
        assertEquals(13, ProjDmsToR.dmstor("-81d00'00.000").end);
        assertEquals(-81.0, degrees("-81d00'00.000"), EPS);
    }

    @Test
    @DisplayName("parsing stops at the first non-printable character")
    void stopsAtWhitespace() {
        ProjDmsToR.Result r = ProjDmsToR.dmstor("83d10'W 43d45'N");
        assertEquals(7, r.end);
        assertEquals(-(83 + 10 / 60.0), Math.toDegrees(r.radians), EPS);
    }

    @Test
    @DisplayName("leading whitespace is skipped but counted in the end position")
    void leadingWhitespace() {
        ProjDmsToR.Result r = ProjDmsToR.dmstor("   43d45'N");
        assertEquals(10, r.end);
        assertEquals(43.75, Math.toDegrees(r.radians), EPS);
    }

    @Test
    @DisplayName("a trailing 'r' means the value is already in radians")
    void radiansSuffix() {
        assertEquals(0.5, ProjDmsToR.dmstor("0.5r").radians, EPS);
    }

    @Test
    @DisplayName("units out of order is an error: HUGE_VAL, consuming nothing")
    void unitsOutOfOrder() {
        ProjDmsToR.Result r = ProjDmsToR.dmstor("12'34d");
        assertEquals(Double.POSITIVE_INFINITY, r.radians);
        assertEquals(0, r.end);
    }

    @Test
    @DisplayName("a plain decimal is read as degrees")
    void plainDegrees() {
        assertEquals(53.5, degrees("53.5"), EPS);
        assertEquals(-7.25, degrees("-7.25"), EPS);
    }
}

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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProjStrtodTest {

    @Test
    @DisplayName("underscores are ignored anywhere in a numeric literal")
    void underscoresAreIgnored() {
        ProjStrtod.Result r = ProjStrtod.strtod("6_098_907.825_05");
        assertEquals(6098907.82505, r.value);
        assertEquals(16, r.end);

        assertEquals(1000.0, ProjStrtod.atof("1_000"));
        assertEquals(1.0, ProjStrtod.atof("1_______"));
        assertEquals(123456789.101112, ProjStrtod.atof("__123_456_789_._10_11_12"));
        assertEquals(-0.01, ProjStrtod.atof("-1e__-_2__rest"));
    }

    @Test
    @DisplayName("'.' is the decimal separator regardless of the default locale")
    void decimalSeparatorIsLocaleIndependent() {
        Locale saved = Locale.getDefault();
        try {
            // GERMANY writes 0,1 for one tenth. A NumberFormat-based parser
            // would silently change answers here; this one must not.
            Locale.setDefault(Locale.GERMANY);
            assertEquals(0.1, ProjStrtod.atof("0.1"));
            assertEquals(6098907.82505, ProjStrtod.atof("6_098_907.825_05"));
            // A comma is a terminator, not a decimal point.
            ProjStrtod.Result r = ProjStrtod.strtod("0,1");
            assertEquals(0.0, r.value);
            assertEquals(1, r.end);
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    @DisplayName("plain decimals round-trip bit-exactly against the Java literal")
    void plainDecimalsAreExact() {
        assertEquals(222571.608757106, ProjStrtod.atof("222571.608757106"));
        assertEquals(110653.326743030, ProjStrtod.atof("110653.326743030"));
        assertEquals(1335833.88951928, ProjStrtod.atof("1335833.88951928"));
        assertEquals(7326837.71424703, ProjStrtod.atof("7326837.71424703"));
        assertEquals(691875.632, ProjStrtod.atof("691875.632"));
    }

    @Test
    @DisplayName("exponents, with or without a sign and with underscores")
    void exponents() {
        assertEquals(1000.0, ProjStrtod.atof("1e3"));
        assertEquals(1.23e8, ProjStrtod.atof("1.23e+08"));
        assertEquals(-1e-7, ProjStrtod.atof("-0.00001e-2"));
        assertEquals(2.77777777778e-07, ProjStrtod.atof("2.77777777778e-07"));
    }

    @Test
    @DisplayName("the end position is where parsing stopped, not the end of the string")
    void endPosition() {
        assertEquals(3, ProjStrtod.strtod("100elephants").end);
        assertEquals(100.0, ProjStrtod.strtod("100elephants").value);
        assertEquals(1, ProjStrtod.strtod("0 66").end);
        assertEquals(1, ProjStrtod.strtod("1 ").end);
    }

    @Test
    @DisplayName("nothing parseable leaves the end position at the start")
    void nothingParsed() {
        assertEquals(0, ProjStrtod.strtod("abc").end);
        assertEquals(0.0, ProjStrtod.strtod("abc").value);
        assertEquals(0, ProjStrtod.strtod("").end);
        assertEquals(0, ProjStrtod.strtod("+").end);
        assertEquals(0, ProjStrtod.strtod("-").end);
        // ... but from a non-zero offset it is that offset, not 0
        assertEquals(4, ProjStrtod.strtod("1 2 abc", 4).end);
    }

    @Test
    @DisplayName("NaN is recognised case-insensitively")
    void nan() {
        assertTrue(Double.isNaN(ProjStrtod.strtod("NaN").value));
        assertTrue(Double.isNaN(ProjStrtod.strtod("nan").value));
        assertEquals(3, ProjStrtod.strtod("NaN").end);
    }

    @Test
    @DisplayName("a decimal exponent out of range sets ERANGE and returns HUGE_VAL")
    void overflowSetsErange() {
        ProjStrtod.Result r = ProjStrtod.strtod("1e9999");
        assertEquals(Double.POSITIVE_INFINITY, r.value);
        assertEquals(ProjStrtod.ERANGE, r.errno);
    }

    @Test
    @DisplayName("signed zero is preserved")
    void signedZero() {
        assertEquals(Double.doubleToLongBits(-0.0),
                Double.doubleToLongBits(ProjStrtod.atof("-0 ")));
        assertEquals(Double.doubleToLongBits(0.0),
                Double.doubleToLongBits(ProjStrtod.atof("0 ")));
    }

    @Test
    @DisplayName("degenerate but legal forms: '0.' and '.5'")
    void degenerateForms() {
        assertEquals(0.0, ProjStrtod.atof("0."));
        assertEquals(2, ProjStrtod.strtod("0.").end);
        assertEquals(0.5, ProjStrtod.atof(".5"));
        assertEquals(2, ProjStrtod.strtod(".5").end);
    }
}

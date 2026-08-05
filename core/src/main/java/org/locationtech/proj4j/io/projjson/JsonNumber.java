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
package org.locationtech.proj4j.io.projjson;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

import org.locationtech.proj4j.io.wkt.WktParseException;

/**
 * How a number is spelled in PROJJSON: integral values without a decimal point, everything else to
 * fifteen significant digits with no exponent — the same rule the WKT writer uses, because it is
 * PROJ's rule and round-trip comparisons are byte-for-byte.
 */
final class JsonNumber {

    private JsonNumber() {
    }

    private static final MathContext SIGNIFICANT_15 = new MathContext(15, RoundingMode.HALF_UP);

    static String format(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new WktParseException("cannot write the non-finite value " + v + " as JSON");
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return new BigDecimal(v, SIGNIFICANT_15).stripTrailingZeros().toPlainString();
    }
}

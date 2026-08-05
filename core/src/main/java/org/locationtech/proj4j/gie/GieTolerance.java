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

import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Parses gie {@code tolerance} arguments — a port of {@code strtod_scaled},
 * {@code tolerance} and {@code column} from PROJ 9.8.1
 * ({@code src/apps/gie.cpp:470-497, 502-554}).
 *
 * <p>A gie tolerance is a number optionally followed by a unit, e.g. {@code "0.5 mm"}. The
 * unit token is obtained with {@code column(args, 2)}, which is <b>the remainder of the string
 * starting at the second whitespace-delimited token</b> — not just that token. It is then
 * compared with {@code strcmp}, so the remainder must equal the unit name <em>exactly</em>.
 * {@code "0.5 mm"} scales by 1/1000; {@code "0.5 mm please"} does not, because
 * {@code "mm please" != "mm"}, and falls through to the default scale.
 *
 * <p>This works in practice because gie has already run the line through {@code pj_chomp}
 * (strip {@code #}-comments, trim) and {@code pj_shrink} (collapse runs of whitespace, drop
 * whitespace-preceded {@code +} and {@code ;}, make {@code =} and {@code ,} greedy).
 *
 * <p>Anything the unit table does not recognise — <b>including no unit at all</b> — multiplies
 * by the caller's default scale, which for {@link #tolerance(String)} is 1, i.e. metres. A
 * string with no parseable leading number resets the tolerance to {@value #FALLBACK_TOLERANCE}.
 *
 * <p>This class is stateless; it is not instantiable.
 */
public final class GieTolerance {

    /**
     * Degrees-to-metres at the equator of GRS80, {@code 111319.4908}.
     *
     * <p><b>This constant exists for one purpose only: converting a tolerance written in
     * {@code deg} or {@code rad} into the metres that the comparator works in. It must never
     * be applied to a coordinate delta.</b> Multiplying a coordinate difference by a constant
     * degrees-per-metre factor is precisely the error that this package exists to prevent — the
     * true figure varies from 111319.49 m at the equator to 55799.47 m at 60°N to 0 at the
     * poles, and the comparator obtains it from a geodesic solution, never from a scale factor.
     *
     * <p>Accordingly this literal is declared here and <b>nowhere else in the codebase</b>;
     * {@code GieSourceHygieneTest} enforces that. In the PROJ 9.8.1 gie corpus no {@code .gie}
     * file uses a {@code deg} or {@code rad} tolerance at all (the census is
     * {@code m}&times;1827, {@code mm}&times;434, {@code cm}&times;26, bare&times;22,
     * {@code nm}&times;16, {@code um}&times;5), so in practice this value is never even read.
     * It is ported for fidelity.
     */
    public static final double GRS80_DEG = 111319.4908;

    /**
     * gie's initial tolerance, {@code 5e-4} m ({@code gie.cpp:284}). Every {@code operation}
     * verb and every completed {@code crs_src}/{@code crs_dst} pair resets the tolerance by
     * calling {@code tolerance("0.5 mm")} ({@code gie.cpp:651, 736}), which yields the same
     * number.
     */
    public static final double DEFAULT_TOLERANCE = 5e-4;

    /**
     * The value {@code tolerance()} falls back to when {@code strtod_scaled} cannot parse a
     * number, {@code 0.0005} ({@code gie.cpp:549-552}). Numerically equal to
     * {@link #DEFAULT_TOLERANCE}, but a distinct literal in the C source and kept distinct here.
     */
    public static final double FALLBACK_TOLERANCE = 0.0005;

    private GieTolerance() {
        throw new AssertionError("no instances");
    }

    /**
     * {@code column(buf, n)} — a pointer to the {@code n}'th whitespace-delimited column of
     * {@code buf}, columns numbered from 0, <em>as a suffix of the whole string</em>.
     *
     * <pre>
     * const char *column(const char *buf, int n) {
     *     if (n &lt;= 0) return buf;
     *     for (i = 0; i &lt; n; i++) {
     *         while (isspace(*buf)) buf++;
     *         if (i == n - 1) break;
     *         while ((0 != *buf) &amp;&amp; !isspace(*buf)) buf++;
     *     }
     *     return buf;
     * }
     * </pre>
     *
     * @param buf the string.
     * @param n   the column index; {@code n <= 0} returns {@code buf} unchanged.
     * @return the tail of {@code buf} beginning at column {@code n}, or {@code ""} if the
     *         string runs out first.
     */
    public static String column(final String buf, final int n) {
        if (n <= 0) {
            return buf;
        }
        final int len = buf.length();
        int p = 0;
        for (int i = 0; i < n; i++) {
            while (p < len && isCSpace(buf.charAt(p))) {
                p++;
            }
            if (i == n - 1) {
                break;
            }
            while (p < len && !isCSpace(buf.charAt(p))) {
                p++;
            }
        }
        return buf.substring(p);
    }

    /**
     * {@code strtod_scaled(args, default_scale)} — interpret {@code args} as a number followed
     * by a linear decadal prefix and return the scaled value.
     *
     * <pre>
     * km  &times;1000      m   &times;1        dm  /10      cm  /100
     * mm  /1000       um  /1e6      nm  /1e9
     * rad &rarr; GRS80_DEG * todeg(s)          deg &rarr; GRS80_DEG * s
     * anything else, INCLUDING absent &rarr; s * default_scale
     * </pre>
     *
     * @param args         the argument string.
     * @param defaultScale the multiplier applied when the unit is unrecognised or absent.
     * @return the scaled value, or {@link Double#POSITIVE_INFINITY} ({@code HUGE_VAL}) if no
     *         number could be read at the head of {@code args}.
     */
    public static double strtodScaled(final String args, final double defaultScale) {
        final int endp = projStrtodEnd(args);
        if (endp == 0) {
            // args == endp: nothing consumed, no conversion performed.
            return Double.POSITIVE_INFINITY;
        }
        double s = projStrtodValue(args, endp);

        // NOTE: the unit is taken from the ORIGINAL string, independently of where the number
        // ended. "0.5mm" therefore has unit "" (one token only) and is 0.5 * default_scale.
        final String units = column(args, 2);

        if ("km".equals(units)) {
            s *= 1000;
        } else if ("m".equals(units)) {
            s *= 1;
        } else if ("dm".equals(units)) {
            s /= 10;
        } else if ("cm".equals(units)) {
            s /= 100;
        } else if ("mm".equals(units)) {
            s /= 1000;
        } else if ("um".equals(units)) {
            s /= 1e6;
        } else if ("nm".equals(units)) {
            s /= 1e9;
        } else if ("rad".equals(units)) {
            s = GRS80_DEG * ProjectionMath.toDeg(s); // PJ_TODEG is bit-identical to Math.toDegrees.
        } else if ("deg".equals(units)) {
            s = GRS80_DEG * s;
        } else {
            s *= defaultScale;
        }
        return s;
    }

    /**
     * {@code tolerance(args)} — {@code strtod_scaled(args, 1)}, i.e. a bare number is metres,
     * with {@code HUGE_VAL} mapped to {@link #FALLBACK_TOLERANCE}.
     *
     * @param args the argument of a gie {@code tolerance} verb.
     * @return the tolerance in metres.
     */
    public static double tolerance(final String args) {
        final double t = strtodScaled(args, 1);
        if (Double.POSITIVE_INFINITY == t) {
            return FALLBACK_TOLERANCE;
        }
        return t;
    }

    /** The C {@code isspace} character class in the "C" locale. */
    private static boolean isCSpace(final char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == 0x0b || c == '\f' || c == '\r';
    }

    /**
     * How many characters {@code proj_strtod} would consume from the head of {@code s}
     * ({@code src/apps/proj_strtod.cpp:105}), i.e. {@code endptr - str}. Zero means "no
     * conversion performed", which is the condition {@code strtod_scaled} tests.
     *
     * <p>{@code proj_strtod} differs from C's {@code strtod} in accepting {@code _} as a digit
     * group separator and in a handful of early-out paths; the leading-whitespace,
     * empty-string, {@code NaN} and non-numeric-first-character rules are reproduced here
     * because they decide the {@code HUGE_VAL} outcome. It does not accept hexadecimal or
     * {@code INF}.
     */
    private static int projStrtodEnd(final String s) {
        if (s == null) {
            return 0;
        }
        final int len = s.length();
        int p = 0;
        while (p < len && isCSpace(s.charAt(p))) {
            p++;
        }
        if (p == len) {
            return 0; // empty after whitespace: *endptr = str
        }
        if (regionMatchesIgnoreCase(s, p, "nan")) {
            return p + 3;
        }
        if ("0123456789+-._".indexOf(s.charAt(p)) < 0) {
            return 0; // non-numeric: *endptr = str
        }
        final int afterSign = (s.charAt(p) == '+' || s.charAt(p) == '-') ? p + 1 : p;
        if (afterSign != p && (afterSign == len || "0123456789._".indexOf(s.charAt(afterSign)) < 0)) {
            return 0; // stray sign, as in "+/-"
        }
        int q = afterSign;
        int digits = 0;
        while (q < len && (isDigit(s.charAt(q)) || s.charAt(q) == '_')) {
            if (isDigit(s.charAt(q))) {
                digits++;
            }
            q++;
        }
        if (q < len && s.charAt(q) == '.') {
            q++;
            while (q < len && (isDigit(s.charAt(q)) || s.charAt(q) == '_')) {
                if (isDigit(s.charAt(q))) {
                    digits++;
                }
                q++;
            }
        }
        if (digits == 0) {
            return 0;
        }
        if (q < len && (s.charAt(q) == 'e' || s.charAt(q) == 'E')) {
            int e = q + 1;
            if (e < len && (s.charAt(e) == '+' || s.charAt(e) == '-')) {
                e++;
            }
            int expDigits = 0;
            while (e < len && isDigit(s.charAt(e))) {
                e++;
                expDigits++;
            }
            if (expDigits > 0) {
                q = e;
            }
        }
        return q;
    }

    /** The numeric value of the prefix {@code s[0, endp)}, with {@code _} separators removed. */
    private static double projStrtodValue(final String s, final int endp) {
        final String head = s.substring(0, endp).trim();
        if (head.length() == 3 && regionMatchesIgnoreCase(head, 0, "nan")) {
            return Double.NaN;
        }
        final StringBuilder sb = new StringBuilder(head.length());
        for (int i = 0; i < head.length(); i++) {
            final char c = head.charAt(i);
            if (c != '_') {
                sb.append(c);
            }
        }
        try {
            return Double.parseDouble(sb.toString());
        } catch (final NumberFormatException e) {
            return 0.0;
        }
    }

    private static boolean isDigit(final char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean regionMatchesIgnoreCase(final String s, final int off, final String what) {
        return s.regionMatches(true, off, what, 0, what.length());
    }
}

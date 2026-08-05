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

import static org.locationtech.proj4j.conformance.parse.PjText.charAt;
import static org.locationtech.proj4j.conformance.parse.PjText.isDigit;
import static org.locationtech.proj4j.conformance.parse.PjText.isSpace;

/**
 * Port of {@code proj_strtod} from {@code 9.8.1:src/apps/proj_strtod.cpp}.
 *
 * <p>Two things make this not-{@link Double#parseDouble}:
 *
 * <ul>
 *   <li><strong>Underscores are ignored anywhere in a numeric literal</strong>,
 *       so {@code 6_098_907.825_05} is {@code 6098907.82505}. The corpus uses
 *       this to break up ten-significant-digit northings.</li>
 *   <li><strong>{@code '.'} is hardcoded as the decimal separator.</strong>
 *       PROJ behaves as if {@code LC_ALL=C} always. Nothing here may reach for
 *       {@code NumberFormat} or {@code DecimalFormat}, which would make the
 *       conformance result depend on the JVM's default locale.</li>
 * </ul>
 *
 * <p>The accumulate-then-scale strategy (build an integer mantissa, then apply
 * a power of ten) is upstream's, and it is reproduced rather than improved:
 * a "better" parse would disagree with gie's expected values in the last
 * ulps.
 */
public final class ProjStrtod {

    /** C {@code EINVAL}. */
    public static final int EINVAL = 22;
    /** C {@code ERANGE}. */
    public static final int ERANGE = 34;

    /* C <float.h>: these really are binary radix exponents, and upstream
     * really does compare them against a decimal exponent. Ported as-is. */
    private static final int DBL_MIN_EXP = -1021;
    private static final int DBL_MAX_EXP = 1024;

    private ProjStrtod() {
    }

    /** The {@code (double, endptr, errno)} triple that C returns by side effect. */
    public static final class Result {
        /** Parsed value. */
        public final double value;
        /** Index just past the consumed text; equals the start when nothing parsed. */
        public final int end;
        /** 0, {@link #EINVAL} or {@link #ERANGE}. */
        public final int errno;

        Result(double value, int end, int errno) {
            this.value = value;
            this.end = end;
            this.errno = errno;
        }

        @Override
        public String toString() {
            return value + "@" + end + (errno == 0 ? "" : " errno=" + errno);
        }
    }

    /** Parse a number at the start of {@code s}. */
    public static Result strtod(String s) {
        return strtod(s, 0);
    }

    /** Parse a number starting at index {@code from} of {@code s}. */
    public static Result strtod(String s, int from) {
        if (s == null) {
            return new Result(Double.POSITIVE_INFINITY, from, 0);
        }
        final int str = from;
        double number = 0;
        double integralPart;
        int exponent = 0;
        boolean fractionIsNonzero = false;
        int sign = 0;
        int p = from;
        int n;
        int numDigitsTotal = 0;
        int numDigitsAfterComma = 0;
        int numPrefixedZeros = 0;

        /* First skip leading whitespace */
        while (isSpace(charAt(s, p))) {
            p++;
        }

        /* Empty string? */
        if (charAt(s, p) == '\0') {
            return new Result(0, str, 0);
        }

        /* NaN */
        if (ciStartsWith(s, p, "NaN")) {
            return new Result(Double.NaN, p + 3, 0);
        }

        /* non-numeric? */
        if ("0123456789+-._".indexOf(charAt(s, p)) < 0) {
            return new Result(0, str, 0);
        }

        /* Then handle optional prefixed sign and skip prefix zeros */
        char c = charAt(s, p);
        if (c == '-') {
            sign = -1;
            p++;
        } else if (c == '+') {
            sign = 1;
            p++;
        } else if (!(isDigit(c) || '_' == c || '.' == c)) {
            return new Result(0, str, 0);
        }

        /* stray sign, as in "+/-"? */
        if (0 != sign
                && ("0123456789._".indexOf(charAt(s, p)) < 0 || '\0' == charAt(s, p))) {
            return new Result(0, str, 0);
        }

        /* skip prefixed zeros before '.' */
        while ('0' == charAt(s, p) || '_' == charAt(s, p)) {
            p++;
        }

        /* zero? */
        if ('\0' == charAt(s, p) || "0123456789eE.".indexOf(charAt(s, p)) < 0
                || isSpace(charAt(s, p))) {
            return new Result(sign == -1 ? -number : number, p, 0);
        }

        /* Now expect a (potentially zero-length) string of digits */
        while (isDigit(charAt(s, p)) || '_' == charAt(s, p)) {
            if ('_' == charAt(s, p)) {
                p++;
                continue;
            }
            number = number * 10. + (charAt(s, p) - '0');
            p++;
            numDigitsTotal++;
        }
        integralPart = number;

        /* Done? */
        if ('\0' == charAt(s, p)) {
            return new Result(sign == -1 ? -number : number, p, 0);
        }

        /* Do we have a fractional part? */
        if ('.' == charAt(s, p)) {
            p++;

            /* keep on skipping prefixed zeros (i.e. allow writing 1e-20 */
            /* as 0.00000000000000000001 without losing precision) */
            if (0 == integralPart) {
                while ('0' == charAt(s, p) || '_' == charAt(s, p)) {
                    if ('0' == charAt(s, p)) {
                        numPrefixedZeros++;
                    }
                    p++;
                }
            }

            /* if the next character is nonnumeric, we have reached the end */
            if ('\0' == charAt(s, p) || "_0123456789eE+-".indexOf(charAt(s, p)) < 0) {
                return new Result(sign == -1 ? -number : number, p, 0);
            }

            while (isDigit(charAt(s, p)) || '_' == charAt(s, p)) {
                /* Don't let pathologically long fractions destroy precision */
                if ('_' == charAt(s, p) || numDigitsTotal > 17) {
                    p++;
                    continue;
                }
                number = number * 10. + (charAt(s, p) - '0');
                if (charAt(s, p) != '0') {
                    fractionIsNonzero = true;
                }
                p++;
                numDigitsTotal++;
                numDigitsAfterComma++;
            }

            /* Avoid having long zero-tails (4321.000...000) destroy precision */
            if (fractionIsNonzero) {
                exponent = -(numDigitsAfterComma + numPrefixedZeros);
            } else {
                number = integralPart;
            }
        } /* end of fractional part */

        /* non-digit */
        if (0 == numDigitsTotal) {
            return new Result(Double.POSITIVE_INFINITY, p, EINVAL);
        }

        if (sign == -1) {
            number = -number;
        }

        /* Do we have an exponent part? */
        while (charAt(s, p) == 'e' || charAt(s, p) == 'E') {
            p++;

            /* Just a stray "e", as in 100elephants? */
            if ('\0' == charAt(s, p) || "0123456789+-_".indexOf(charAt(s, p)) < 0) {
                p--;
                break;
            }

            while ('_' == charAt(s, p)) {
                p++;
            }
            /* Does it have a sign? */
            sign = 0;
            if ('-' == charAt(s, p)) {
                sign = -1;
                p++;
            } else if ('+' == charAt(s, p)) {
                sign = 1;
                p++;
            } else if (!isDigit(charAt(s, p))) {
                return new Result(Double.POSITIVE_INFINITY, p, 0);
            }

            /* Go on and read the exponent */
            n = 0;
            while (isDigit(charAt(s, p)) || '_' == charAt(s, p)) {
                if ('_' == charAt(s, p)) {
                    p++;
                    continue;
                }
                n = n * 10 + (charAt(s, p) - '0');
                p++;
            }

            if (-1 == sign) {
                n = -n;
            }
            exponent += n;
            break;
        }

        if (exponent < DBL_MIN_EXP || exponent > DBL_MAX_EXP) {
            return new Result(Double.POSITIVE_INFINITY, p, ERANGE);
        }

        /* on some platforms pow() is very slow - so don't call it if exponent
         * is close to 0 */
        if (0 == exponent) {
            return new Result(number, p, 0);
        }
        if (Math.abs(exponent) < 20) {
            double ex = 1;
            int absexp = exponent < 0 ? -exponent : exponent;
            while (absexp-- > 0) {
                ex *= 10;
            }
            number = exponent < 0 ? number / ex : number * ex;
        } else {
            number *= Math.pow(10.0, (double) exponent);
        }
        return new Result(number, p, 0);
    }

    /** Convenience: value only, as {@code proj_atof} does. */
    public static double atof(String s) {
        return strtod(s, 0).value;
    }

    /** ASCII case-insensitive prefix test, matching PROJ's {@code ci_starts_with}. */
    private static boolean ciStartsWith(String s, int from, String prefix) {
        if (from + prefix.length() > s.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (lower(s.charAt(from + i)) != lower(prefix.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static char lower(char c) {
        return (c >= 'A' && c <= 'Z') ? (char) (c + 32) : c;
    }
}

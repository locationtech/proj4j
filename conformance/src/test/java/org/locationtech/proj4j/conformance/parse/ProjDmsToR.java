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
import static org.locationtech.proj4j.conformance.parse.PjText.isGraph;
import static org.locationtech.proj4j.conformance.parse.PjText.isSpace;

/**
 * Port of {@code dmstor_ctx} from {@code 9.8.1:src/dmstor.cpp}, reached from
 * gie as {@code proj_dmstor} ({@code src/coordinates.cpp:103}).
 *
 * <p>Converts a degrees/minutes/seconds string to <strong>radians</strong>.
 * Handles the corpus forms {@code 83d10'W}, {@code 43d45'N},
 * {@code -64d43'75.34} and {@code 79d58'00.000"W}, plus a trailing {@code r}
 * for a value already in radians.
 *
 * <p>Note that this routine does <em>not</em> use {@link ProjStrtod} — it uses
 * its own private helper that truncates the string at the first {@code d} or
 * {@code D} and calls the platform {@code strtod}. That means underscores are
 * <em>not</em> accepted in DMS literals, which is upstream's behaviour and the
 * reason {@code parse_coord} tries both parsers. The C comment in
 * {@code gie.cpp} calls this out as a wart to be fixed "when projects.h is
 * removed"; until then, faithfulness beats tidiness.
 */
public final class ProjDmsToR {

    private static final String SYM = "NnEeSsWw";

    private static final double DEG_TO_RAD = 0.017453292519943296;

    /** {@code vm[]}: degrees, minutes, seconds, as radians per unit. */
    private static final double[] VM = {
        DEG_TO_RAD, .0002908882086657216, .0000048481368110953599
    };

    /** Bytes of U+00B0 DEGREE SIGN in UTF-8, treated one byte at a time. */
    private static final char DEG_SIGN1 = 0x00C2;
    private static final char DEG_SIGN2 = 0x00B0;

    private static final int MAX_WORK = 64;

    private ProjDmsToR() {
    }

    /** The {@code (radians, endptr)} pair that C returns by side effect. */
    public static final class Result {
        /** Value in radians; {@code HUGE_VAL} on a malformed unit sequence. */
        public final double radians;
        /** Index just past the consumed text. */
        public final int end;

        Result(double radians, int end) {
            this.radians = radians;
            this.end = end;
        }

        @Override
        public String toString() {
            return radians + "@" + end;
        }
    }

    public static Result dmstor(String is) {
        return dmstor(is, 0);
    }

    /** Parse a DMS literal starting at index {@code from}. */
    public static Result dmstor(String is, int from) {
        if (is == null) {
            return new Result(Double.POSITIVE_INFINITY, from);
        }
        /* rs is set to the *original* pointer before whitespace is skipped, so
         * every early error return reports "consumed nothing". */
        final int origin = from;

        int p = from;
        while (isSpace(charAt(is, p))) {
            p++;
        }
        final int isStart = p;

        /*
         * Copy characters into work until we hit a non-printable character or
         * run out of space in the buffer. Make a special exception for the
         * bytes of the Degree Sign in UTF-8.
         */
        StringBuilder workBuf = new StringBuilder();
        int n = MAX_WORK;
        while (true) {
            char c = charAt(is, p);
            if (!(isGraph(c) || c == DEG_SIGN1 || c == DEG_SIGN2)) {
                break;
            }
            if (--n == 0) {
                break;
            }
            workBuf.append(c);
            p++;
        }
        final String work = workBuf.toString();

        int s = 0;
        int sign = 1;
        char signCh = charAt(work, s);
        if (signCh == '+' || signCh == '-') {
            if (signCh == '-') {
                sign = -1;
            }
            s++;
        }

        double v = 0.;
        for (int nl = 0; nl < 3; nl = n + 1) {
            char c0 = charAt(work, s);
            if (!(isDigit(c0) || c0 == '.')) {
                break;
            }
            InnerStrtod.Num r = InnerStrtod.parse(work, s);
            double tv = r.value;
            if (tv == Double.POSITIVE_INFINITY) {
                return new Result(tv, origin);
            }
            s = r.end;
            int adv = 1;

            char cs = charAt(work, s);
            if (cs == 'D' || cs == 'd' || cs == DEG_SIGN2) {
                /* \xb0 as a single-byte degree symbol */
                n = 0;
            } else if (cs == '\'') {
                n = 1;
            } else if (cs == '"') {
                n = 2;
            } else if (cs == DEG_SIGN1 && charAt(work, s + 1) == DEG_SIGN2) {
                /* degree symbol in UTF-8 */
                n = 0;
                adv = 2;
            } else if (cs == 'r' || cs == 'R') {
                if (nl != 0) {
                    /* PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE */
                    return new Result(Double.POSITIVE_INFINITY, origin);
                }
                ++s;
                v = tv;
                n = 4;
                continue;
            } else {
                v += tv * VM[nl];
                n = 4;
                continue;
            }

            if (n < nl) {
                /* units out of order, e.g. 12'34d */
                return new Result(Double.POSITIVE_INFINITY, origin);
            }
            v += tv * VM[n];
            s += adv;
        }

        /* postfix sign */
        char post = charAt(work, s);
        if (post != '\0') {
            int idx = SYM.indexOf(post);
            if (idx >= 0) {
                sign = idx >= 4 ? -1 : 1;
                ++s;
            }
        }
        if (sign == -1) {
            v = -v;
        }
        return new Result(v, isStart + s);
    }

    /**
     * The file-static {@code proj_strtod} inside {@code dmstor.cpp} — not the
     * underscore-aware {@link ProjStrtod}. It NUL-terminates the buffer at the
     * first {@code d}/{@code D} so the platform {@code strtod} cannot read the
     * Fortran-style {@code D} exponent, then parses plain C decimal syntax.
     */
    static final class InnerStrtod {

        static final class Num {
            final double value;
            final int end;

            Num(double value, int end) {
                this.value = value;
                this.end = end;
            }
        }

        private InnerStrtod() {
        }

        static Num parse(String s, int from) {
            int limit = s.length();
            for (int cp = from; cp < s.length(); cp++) {
                char c = s.charAt(cp);
                if (c == 'd' || c == 'D') {
                    limit = cp;
                    break;
                }
            }
            return cStrtod(s, from, limit);
        }

        /** Plain C {@code strtod} over {@code s[from, limit)}, "C" locale. */
        static Num cStrtod(String s, int from, int limit) {
            int p = from;
            while (p < limit && isSpace(s.charAt(p))) {
                p++;
            }
            int mantissaStart = p;
            if (p < limit && (s.charAt(p) == '+' || s.charAt(p) == '-')) {
                p++;
            }
            int intStart = p;
            while (p < limit && isDigit(s.charAt(p))) {
                p++;
            }
            int intEnd = p;
            int fracStart = p;
            int fracEnd = p;
            if (p < limit && s.charAt(p) == '.') {
                p++;
                fracStart = p;
                while (p < limit && isDigit(s.charAt(p))) {
                    p++;
                }
                fracEnd = p;
            }
            if (intEnd == intStart && fracEnd == fracStart) {
                /* no conversion performed: endptr := nptr */
                return new Num(0.0, from);
            }
            int end = p;
            if (p < limit && (s.charAt(p) == 'e' || s.charAt(p) == 'E')) {
                int q = p + 1;
                if (q < limit && (s.charAt(q) == '+' || s.charAt(q) == '-')) {
                    q++;
                }
                if (q < limit && isDigit(s.charAt(q))) {
                    while (q < limit && isDigit(s.charAt(q))) {
                        q++;
                    }
                    end = q;
                }
            }
            double value = Double.parseDouble(s.substring(mantissaStart, end));
            return new Num(value, end);
        }
    }
}

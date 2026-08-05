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

/**
 * Faithful Java ports of PROJ's {@code pj_chomp} and {@code pj_shrink} text
 * normalisers, from {@code 9.8.1:src/internal.cpp} lines 153 and 192.
 *
 * <p>These two functions define what a {@code .gie} argument string
 * <em>means</em>. Getting them wrong silently changes
 * {@code +proj=aea +lat_1=0} into something else, so this is a
 * character-for-character transliteration of the C rather than a rewrite.
 *
 * <p>The C operates on {@code char*} buffers in place; the Java operates on
 * {@link String}. Wherever the C reads one past the logical end of the buffer
 * (which is always legal there because of the NUL terminator) the Java uses
 * {@link #charAt(String, int)}, which returns {@code '\0'} out of range.
 */
public final class PjText {

    private PjText() {
    }

    /** C {@code isspace} in the {@code "C"} locale. */
    public static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r';
    }

    /** C {@code isdigit} in the {@code "C"} locale. */
    public static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** C {@code isgraph} in the {@code "C"} locale: printable, not space. */
    public static boolean isGraph(char c) {
        return c > 0x20 && c < 0x7F;
    }

    /**
     * NUL-terminated-buffer indexing: returns {@code '\0'} rather than throwing
     * when {@code i} is outside {@code s}. This mirrors reading {@code c[i]} in
     * C where {@code c} is NUL terminated.
     */
    public static char charAt(String s, int i) {
        return (i >= 0 && i < s.length()) ? s.charAt(i) : '\0';
    }

    /**
     * Port of {@code pj_chomp}: strip pre- and postfix whitespace; an inline
     * {@code '#'} comment counts as whitespace, so everything from the first
     * {@code '#'} onwards is discarded. {@code ';'} is treated as whitespace
     * at both ends.
     */
    public static String chomp(String c) {
        if (c == null) {
            return null;
        }
        int comment = c.indexOf('#');
        if (comment >= 0) {
            c = c.substring(0, comment);
        }
        int n = c.length();
        if (n == 0) {
            return c;
        }

        /* Eliminate postfix whitespace: for (i = n-1; (i > 0) && ...; i--) */
        int end = n;
        for (int i = n - 1; i > 0 && (isSpace(c.charAt(i)) || ';' == c.charAt(i)); i--) {
            end = i;
        }

        /* Find start of non-whitespace */
        int start = 0;
        while (start < end && (';' == c.charAt(start) || isSpace(c.charAt(start)))) {
            start++;
        }

        return c.substring(start, end);
    }

    /**
     * Port of {@code pj_shrink}: "Collapse repeated whitespace. Remove
     * {@code '+'} and {@code ';'}. Make {@code ','} and {@code '='} greedy,
     * consuming their surrounding whitespace." Calls {@link #chomp} first, as
     * the C does.
     *
     * <p>A {@code '+'} is only removed when it opens a token, so the {@code +}
     * in {@code 1.23e+08} survives. Double-quoted strings opened immediately
     * after an {@code '='} are copied verbatim in the first pass, with
     * {@code ""} escaping a quote. Note that the second pass (the greedy
     * {@code ','}/{@code '='} pass) is <em>not</em> string aware in the C
     * either; that is reproduced here.
     */
    public static String shrink(String in) {
        if (in == null) {
            return null;
        }
        String chomped = chomp(in);
        int n = chomped.length();
        if (n == 0) {
            return chomped;
        }

        char[] c = chomped.toCharArray();

        /* First collapse repeated whitespace (including +/;) */
        int i = 0;
        boolean ws = false;
        boolean inString = false;
        for (int j = 0; j < n; j++) {

            if (inString) {
                if (c[j] == '"' && charAt(c, j + 1, n) == '"') {
                    c[i++] = c[j];
                    j++;
                } else if (c[j] == '"') {
                    inString = false;
                }
                c[i++] = c[j];
                continue;
            }

            /* Eliminate prefix '+', only if preceded by whitespace */
            /* (i.e. keep it in 1.23e+08) */
            if (i > 0 && '+' == c[j] && ws) {
                c[j] = ' ';
            }
            if (i == 0 && '+' == c[j]) {
                c[j] = ' ';
            }

            /* Detect a string beginning after '=' */
            if (c[j] == '"' && i > 0 && c[i - 1] == '=') {
                inString = true;
                ws = false;
                c[i++] = c[j];
                continue;
            }

            if (isSpace(c[j]) || ';' == c[j]) {
                if (!ws && i > 0) {
                    c[i++] = ' ';
                }
                ws = true;
                continue;
            } else {
                ws = false;
                c[i++] = c[j];
            }
        }
        n = i;

        /* Then make ',' and '=' greedy */
        i = 0;
        for (int j = 0; j < n; j++) {
            if (i == 0) {
                c[i++] = c[j];
                continue;
            }

            /* Skip space before '='/',' */
            if ('=' == c[j] || ',' == c[j]) {
                if (c[i - 1] == ' ') {
                    c[i - 1] = c[j];
                } else {
                    c[i++] = c[j];
                }
                continue;
            }

            if (' ' == c[j] && ('=' == c[i - 1] || ',' == c[i - 1])) {
                continue;
            }

            c[i++] = c[j];
        }

        return new String(c, 0, i);
    }

    private static char charAt(char[] c, int i, int n) {
        return (i >= 0 && i < n) ? c[i] : '\0';
    }
}

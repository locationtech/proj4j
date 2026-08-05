/*
 * Copyright 2026 The Proj4J Contributors.
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
package org.locationtech.proj4j.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A dependency-free JSON reader for the model files {@code +proj=tinshift} and
 * {@code +proj=defmodel} take.
 *
 * <h2>Why this is not {@code org.locationtech.proj4j.io.projjson.Json}</h2>
 *
 * <p>Because that class is <b>package-private</b> — {@code final class Json} with
 * {@code static Object parse(String)} — so nothing outside
 * {@code org.locationtech.proj4j.io.projjson} can call it. It is the right class to
 * use and this one should be deleted the moment {@code Json} and {@code Json.parse}
 * are made {@code public} (or a public facade is added next to
 * {@code ProjJsonReader}); that visibility change is the one item of plumbing this
 * operator family needs and is reported rather than made here, because
 * {@code io/**} is owned elsewhere.
 *
 * <p>Deliberately <em>not</em> a JSON library. PROJ reads these files with
 * {@code nlohmann::json} and proj4j's core has zero runtime dependencies on purpose:
 * a downstream consumer is removing Apache SIS specifically to remove a
 * {@code LinkageError} over a duplicated class, and adding a JSON dependency would
 * relocate that hazard rather than remove it.
 *
 * <h2>Scope, and the two things upstream's reader distinguishes that this one must too</h2>
 *
 * <p>RFC 8259 as these files use it: objects (member order preserved), arrays,
 * strings with the six short escapes and {@code \\uXXXX}, numbers, {@code true},
 * {@code false}, {@code null}. Numbers are {@code Double}, which is what
 * {@code nlohmann}'s {@code is_number()} means at every call site in
 * {@code tinshift_impl.hpp}.
 *
 * <p>{@code tinshift_impl.hpp} does, however, test
 * {@code type() != json::value_t::number_unsigned} for a triangle's vertex indices —
 * a stricter test than {@code is_number()}, rejecting {@code 1.5} and {@code -1}
 * where a plain number check would accept them. {@link #asIndex} reproduces that on
 * the parsed {@code Double}: the value must be integral and non-negative. A reader
 * that returned every number as a {@code double} and lost the distinction would
 * silently accept a malformed triangulation.
 *
 * <p>Depth is bounded, because these files are named by a user-supplied proj-string
 * and unbounded nesting is a stack-overflow shaped denial of service on a parser
 * that recurses.
 *
 * <p>Stateless; not instantiable.
 *
 * @since 1.5
 */
final class PipelineJson {

    /** Nesting limit. Upstream's own files are three deep; 64 is far past any real model. */
    private static final int MAX_DEPTH = 64;

    private PipelineJson() {
        throw new AssertionError("no instances");
    }

    /**
     * Parse JSON text.
     *
     * @param text the document
     * @return {@link Map}, {@link List}, {@link String}, {@link Double}, {@link Boolean}
     *         or {@code null}
     * @throws PipelineDefinitionException {@code FILE_NOT_FOUND_OR_INVALID} if the text
     *                                     is not well-formed JSON — which is the error
     *                                     PROJ raises for it, because "the file exists
     *                                     but is not a model" and "the file is missing"
     *                                     are the same {@code
     *                                     PROJ_ERR_INVALID_OP_FILE_NOT_FOUND_OR_INVALID}
     */
    static Object parse(final String text) {
        if (text == null) {
            throw invalid("JSON text is null");
        }
        final Cursor c = new Cursor(text);
        c.skipWhitespace();
        final Object value = c.value(0);
        c.skipWhitespace();
        if (c.pos < text.length()) {
            throw invalid("unexpected trailing text at offset " + c.pos);
        }
        return value;
    }

    // ------------------------------------------------------------- typed access

    /**
     * @param node any parsed node
     * @return it as an object
     * @throws PipelineDefinitionException if it is not one
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> asObject(final Object node, final String what) {
        if (!(node instanceof Map)) {
            throw invalid(what + " is not an object");
        }
        return (Map<String, Object>) node;
    }

    /**
     * @param node any parsed node
     * @return it as an array
     * @throws PipelineDefinitionException if it is not one
     */
    @SuppressWarnings("unchecked")
    static List<Object> asArray(final Object node, final String what) {
        if (!(node instanceof List)) {
            throw invalid(what + " is not an array");
        }
        return (List<Object>) node;
    }

    /**
     * {@code getArrayMember} ({@code tinshift_impl.hpp:64-74}): the key must be present
     * and its value must be an array.
     *
     * @param object the containing object
     * @param key    the member name
     * @return the array
     */
    static List<Object> requiredArray(final Map<String, Object> object, final String key) {
        if (!object.containsKey(key)) {
            throw invalid("Missing \"" + key + "\" key");
        }
        return asArray(object.get(key), "The value of \"" + key + "\"");
    }

    /** {@code getReqString}: present, and a string. */
    static String requiredString(final Map<String, Object> object, final String key) {
        if (!object.containsKey(key)) {
            throw invalid("Missing \"" + key + "\" key");
        }
        return string(object, key);
    }

    /** {@code getOptString}: absent yields the empty string, present must be a string. */
    static String optionalString(final Map<String, Object> object, final String key) {
        if (!object.containsKey(key)) {
            return "";
        }
        return string(object, key);
    }

    private static String string(final Map<String, Object> object, final String key) {
        final Object v = object.get(key);
        if (!(v instanceof String)) {
            throw invalid("The value of \"" + key + "\" should be a string");
        }
        return (String) v;
    }

    /** {@code is_number()} followed by {@code get<double>()}. */
    static double asNumber(final Object node, final String what) {
        if (!(node instanceof Double)) {
            throw invalid(what + " is not a number");
        }
        return ((Double) node).doubleValue();
    }

    /**
     * {@code type() == json::value_t::number_unsigned} followed by {@code get<unsigned>()}
     * — integral and non-negative, not merely numeric.
     *
     * @param node the array element
     * @param what a description for the message
     * @return the index
     */
    static int asIndex(final Object node, final String what) {
        if (!(node instanceof Double)) {
            throw invalid(what + " is not an integer");
        }
        final double d = ((Double) node).doubleValue();
        if (d < 0 || d != Math.floor(d) || Double.isInfinite(d) || d > Integer.MAX_VALUE) {
            throw invalid(what + " is not an integer");
        }
        return (int) d;
    }

    static PipelineDefinitionException invalid(final String message) {
        return new PipelineDefinitionException(PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID,
                "invalid model: " + message);
    }

    // ------------------------------------------------------------------- parser

    private static final class Cursor {

        private final String s;
        private int pos;

        Cursor(final String s) {
            this.s = s;
        }

        void skipWhitespace() {
            while (pos < s.length()) {
                final char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object value(final int depth) {
            if (depth > MAX_DEPTH) {
                throw invalid("JSON nested more than " + MAX_DEPTH + " deep");
            }
            if (pos >= s.length()) {
                throw invalid("unexpected end of JSON");
            }
            final char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return object(depth);
                case '[':
                    return array(depth);
                case '"':
                    return string();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return number();
            }
        }

        private Map<String, Object> object(final int depth) {
            pos++;
            final Map<String, Object> out = new LinkedHashMap<String, Object>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return Collections.unmodifiableMap(out);
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw invalid("expected a member name at offset " + pos);
                }
                final String key = string();
                skipWhitespace();
                if (peek() != ':') {
                    throw invalid("expected ':' at offset " + pos);
                }
                pos++;
                skipWhitespace();
                out.put(key, value(depth + 1));
                skipWhitespace();
                final char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return Collections.unmodifiableMap(out);
                }
                throw invalid("expected ',' or '}' at offset " + pos);
            }
        }

        private List<Object> array(final int depth) {
            pos++;
            final List<Object> out = new ArrayList<Object>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return Collections.unmodifiableList(out);
            }
            while (true) {
                skipWhitespace();
                out.add(value(depth + 1));
                skipWhitespace();
                final char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return Collections.unmodifiableList(out);
                }
                throw invalid("expected ',' or ']' at offset " + pos);
            }
        }

        private String string() {
            pos++;
            final StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw invalid("unterminated string");
                }
                final char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= s.length()) {
                    throw invalid("unterminated escape");
                }
                final char e = s.charAt(pos++);
                switch (e) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (pos + 4 > s.length()) {
                            throw invalid("truncated \\u escape");
                        }
                        try {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        } catch (final NumberFormatException nfe) {
                            throw invalid("bad \\u escape at offset " + pos);
                        }
                        pos += 4;
                        break;
                    default:
                        throw invalid("unknown escape '\\" + e + "' at offset " + (pos - 1));
                }
            }
        }

        private Double number() {
            final int start = pos;
            if (peek() == '-' || peek() == '+') {
                pos++;
            }
            while (pos < s.length()) {
                final char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E'
                        || c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            if (pos == start) {
                throw invalid("expected a value at offset " + start);
            }
            final String text = s.substring(start, pos);
            try {
                return Double.valueOf(text);
            } catch (final NumberFormatException e) {
                throw invalid("not a number: '" + text + "' at offset " + start);
            }
        }

        private void expect(final String literal) {
            if (!s.startsWith(literal, pos)) {
                throw invalid("expected '" + literal + "' at offset " + pos);
            }
            pos += literal.length();
        }

        private char peek() {
            if (pos >= s.length()) {
                throw invalid("unexpected end of JSON");
            }
            return s.charAt(pos);
        }
    }
}

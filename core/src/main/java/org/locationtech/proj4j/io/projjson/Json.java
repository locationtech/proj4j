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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.proj4j.io.wkt.WktParseException;

/**
 * A minimal, dependency-free JSON reader and writer — the whole reason PROJJSON support can live
 * in core.
 * <p>
 * There is no JSON library on the classpath and there will not be one: the point of this work is
 * that a consumer can delete Apache SIS <em>and</em> the {@code catch (LinkageError)} around a
 * duplicated GeoAPI class, and adding a Jackson or Gson dependency to achieve that would simply
 * move the hazard rather than remove it.
 * <p>
 * Scope is exactly RFC 8259 as PROJJSON uses it: objects, arrays, strings, numbers, booleans and
 * null. Objects preserve member order, because PROJJSON round-trip equality is byte-for-byte and
 * member order is part of it. Numbers are read as {@code Double}, which is what the schema means by
 * "number" everywhere.
 * <p>
 * Both directions are depth-bounded by {@link JsonLimits#MAX_DEPTH}: {@code value()} recurses into
 * objects and arrays on the way in, and {@code write()} recurses on the way out, so an unbounded
 * reader would hand an unbounded writer a tree that overflows the stack on the return leg of a
 * round trip. The document is untrusted, and a {@link StackOverflowError} is an {@code Error} that
 * escapes every {@code catch} in this library.
 */
final class Json {

    private Json() {
    }

    /**
     * Parses JSON text into {@link Map}, {@link List}, {@link String}, {@link Double},
     * {@link Boolean} and {@code null}.
     *
     * @throws WktParseException if the text is not well-formed JSON
     */
    static Object parse(String text) {
        if (text == null) {
            throw new WktParseException("JSON text is null");
        }
        Parser p = new Parser(text);
        p.skipWhitespace();
        Object value = p.value(1);
        p.skipWhitespace();
        if (p.pos < text.length()) {
            throw new WktParseException("unexpected trailing text at offset " + p.pos
                    + " in JSON");
        }
        return value;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        /**
         * Reads one value. {@code depth} is its level in the document, the root value being 1.
         */
        Object value(int depth) {
            if (depth > JsonLimits.MAX_DEPTH) {
                throw new WktParseException("JSON nested more than " + JsonLimits.MAX_DEPTH
                        + " deep at offset " + pos + "; refusing to recurse further");
            }
            skipWhitespace();
            if (pos >= s.length()) {
                throw new WktParseException("unexpected end of JSON text");
            }
            char c = s.charAt(pos);
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

        Map<String, Object> object(int depth) {
            pos++;
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            skipWhitespace();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (pos >= s.length() || s.charAt(pos) != '"') {
                    throw new WktParseException("expected a JSON member name at offset " + pos);
                }
                String key = string();
                skipWhitespace();
                if (pos >= s.length() || s.charAt(pos) != ':') {
                    throw new WktParseException("expected ':' after member \"" + key + "\"");
                }
                pos++;
                map.put(key, value(depth + 1));
                skipWhitespace();
                if (pos >= s.length()) {
                    throw new WktParseException("unterminated JSON object");
                }
                char d = s.charAt(pos);
                if (d == ',') {
                    pos++;
                    continue;
                }
                if (d == '}') {
                    pos++;
                    return map;
                }
                throw new WktParseException("expected ',' or '}' at offset " + pos + " in JSON");
            }
        }

        List<Object> array(int depth) {
            pos++;
            List<Object> list = new ArrayList<Object>();
            skipWhitespace();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(value(depth + 1));
                skipWhitespace();
                if (pos >= s.length()) {
                    throw new WktParseException("unterminated JSON array");
                }
                char d = s.charAt(pos);
                if (d == ',') {
                    pos++;
                    continue;
                }
                if (d == ']') {
                    pos++;
                    return list;
                }
                throw new WktParseException("expected ',' or ']' at offset " + pos + " in JSON");
            }
        }

        String string() {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw new WktParseException("unterminated JSON string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= s.length()) {
                    throw new WktParseException("unterminated JSON escape");
                }
                char e = s.charAt(pos++);
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
                            throw new WktParseException("truncated \\u escape in JSON");
                        }
                        try {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw new WktParseException("bad \\u escape in JSON", ex);
                        }
                        pos += 4;
                        break;
                    default:
                        throw new WktParseException("unknown JSON escape \\" + e);
                }
            }
        }

        Double number() {
            int start = pos;
            if (pos < s.length() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) {
                pos++;
            }
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+'
                        || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            String text = s.substring(start, pos);
            try {
                return Double.valueOf(text);
            } catch (NumberFormatException e) {
                throw new WktParseException("not a JSON value: \"" + text + "\" at offset "
                        + start, e);
            }
        }

        void expect(String literal) {
            if (!s.regionMatches(pos, literal, 0, literal.length())) {
                throw new WktParseException("expected \"" + literal + "\" at offset " + pos
                        + " in JSON");
            }
            pos += literal.length();
        }

        void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------- writing

    /**
     * Writes a value tree as JSON, indented two spaces per level — PROJ's own indentation, and
     * therefore what a round-trip comparison expects.
     */
    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb, 0);
        return sb.toString();
    }

    /**
     * {@code depth} is the value's indentation level, the root being 0 — one less than
     * {@link Parser#value(int)}'s convention, so {@code depth >= MAX_DEPTH} here refuses exactly
     * the trees {@link #parse} refuses. Anything that parsed can therefore be written back, and the
     * guard still fires on a tree assembled some other way.
     */
    private static void write(Object value, StringBuilder sb, int depth) {
        if (depth >= JsonLimits.MAX_DEPTH) {
            throw new WktParseException("JSON nested more than " + JsonLimits.MAX_DEPTH
                    + " deep; refusing to recurse further");
        }
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map) {
            writeObject((Map<?, ?>) value, sb, depth);
        } else if (value instanceof List) {
            writeArray((List<?>) value, sb, depth);
        } else if (value instanceof String) {
            writeString((String) value, sb);
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value).booleanValue() ? "true" : "false");
        } else if (value instanceof Double || value instanceof Float) {
            sb.append(JsonNumber.format(((Number) value).doubleValue()));
        } else if (value instanceof Number) {
            sb.append(value.toString());
        } else {
            throw new WktParseException("cannot write a " + value.getClass().getName()
                    + " as JSON");
        }
    }

    private static void writeObject(Map<?, ?> map, StringBuilder sb, int depth) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            indent(sb, depth + 1);
            writeString(String.valueOf(e.getKey()), sb);
            sb.append(": ");
            write(e.getValue(), sb, depth + 1);
        }
        sb.append('\n');
        indent(sb, depth);
        sb.append('}');
    }

    private static void writeArray(List<?> list, StringBuilder sb, int depth) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(",\n");
            }
            indent(sb, depth + 1);
            write(list.get(i), sb, depth + 1);
        }
        sb.append('\n');
        indent(sb, depth);
        sb.append(']');
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", Integer.valueOf(c)));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static void indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
    }
}

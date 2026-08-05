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
package org.locationtech.proj4j.benchmark.gate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader and writer, sufficient for JMH's result format and for the checked-in
 * baselines.
 *
 * <p><b>Why hand-rolled rather than Jackson.</b> The gate has to run in CI on every PR, so its own
 * dependency footprint is a liability: a JSON library here means a download, a shaded copy in
 * {@code benchmarks.jar}, and a CVE feed to watch for a tool that reads two files this repository
 * produces itself. JMH's output uses objects, arrays, strings and numbers and nothing else. The parser
 * below is about 120 lines and is strict enough to fail loudly on anything it does not understand,
 * which is the property that matters - a lenient parser that silently returns null for a malformed
 * baseline would turn a gate breach into a pass.
 *
 * <p>Numbers are always {@code Double}. That is fine for byte counts and ns/op, and it is fine for the
 * op counts, which are small integers: a {@code double} is exact for every integer below 2^53 and no
 * transform performs 9 quadrillion sines. The writer emits integral doubles without a decimal point so
 * that {@code op-counts.json} diffs as {@code "sin": 8} rather than {@code "sin": 8.0}.
 */
final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    // ============================================================================================
    // Reading
    // ============================================================================================

    /** @return a {@code Map<String,Object>}, {@code List<Object>}, {@code String}, {@code Double}, {@code Boolean} or {@code null} */
    static Object parse(String text) {
        Json p = new Json(text);
        p.skipWhitespace();
        Object value = p.readValue();
        p.skipWhitespace();
        if (p.pos != p.src.length()) {
            throw p.error("trailing content after the top-level value");
        }
        return value;
    }

    private Object readValue() {
        if (pos >= src.length()) {
            throw error("unexpected end of input");
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{': return readObject();
            case '[': return readArray();
            case '"': return readString();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return readNumber();
                }
                throw error("unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // '{'
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("expected a quoted object key");
            }
            String key = readString();
            skipWhitespace();
            if (peek() != ':') {
                throw error("expected ':' after key \"" + key + "\"");
            }
            pos++;
            skipWhitespace();
            map.put(key, readValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return map;
            }
            throw error("expected ',' or '}' in object");
        }
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        pos++; // '['
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(readValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return list;
            }
            throw error("expected ',' or ']' in array");
        }
    }

    private String readString() {
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw error("unterminated string");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char esc = src.charAt(pos++);
            switch (esc) {
                case '"':  sb.append('"');  break;
                case '\\': sb.append('\\'); break;
                case '/':  sb.append('/');  break;
                case 'b':  sb.append('\b'); break;
                case 'f':  sb.append('\f'); break;
                case 'n':  sb.append('\n'); break;
                case 'r':  sb.append('\r'); break;
                case 't':  sb.append('\t'); break;
                case 'u':
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default:
                    throw error("unknown escape '\\" + esc + "'");
            }
        }
    }

    private Double readNumber() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                pos++;
            } else {
                break;
            }
        }
        String text = src.substring(start, pos);
        try {
            return Double.valueOf(text);
        } catch (NumberFormatException e) {
            throw error("malformed number '" + text + "'");
        }
    }

    private char peek() {
        if (pos >= src.length()) {
            throw error("unexpected end of input");
        }
        return src.charAt(pos);
    }

    private void expect(String literal) {
        if (!src.startsWith(literal, pos)) {
            throw error("expected '" + literal + "'");
        }
        pos += literal.length();
    }

    private void skipWhitespace() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private IllegalArgumentException error(String message) {
        int line = 1;
        for (int i = 0; i < Math.min(pos, src.length()); i++) {
            if (src.charAt(i) == '\n') {
                line++;
            }
        }
        return new IllegalArgumentException("Malformed JSON at line " + line + ": " + message);
    }

    // ============================================================================================
    // Convenience accessors. Each names the offending key on failure, because these are used against
    // hand-edited baseline files where a typo is the likely error.
    // ============================================================================================

    @SuppressWarnings("unchecked")
    static Map<String, Object> asObject(Object o, String what) {
        if (!(o instanceof Map)) {
            throw new IllegalArgumentException(what + ": expected a JSON object, got "
                    + (o == null ? "null" : o.getClass().getSimpleName()));
        }
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    static List<Object> asArray(Object o, String what) {
        if (!(o instanceof List)) {
            throw new IllegalArgumentException(what + ": expected a JSON array, got "
                    + (o == null ? "null" : o.getClass().getSimpleName()));
        }
        return (List<Object>) o;
    }

    static String asString(Object o, String what) {
        if (!(o instanceof String)) {
            throw new IllegalArgumentException(what + ": expected a string, got "
                    + (o == null ? "null" : o.getClass().getSimpleName()));
        }
        return (String) o;
    }

    // ============================================================================================
    // Writing. Deliberately pretty-printed with one entry per line: the baselines are reviewed as
    // diffs, and a compact single-line JSON would make every refresh an unreadable one-line change.
    // ============================================================================================

    static String write(Object value) {
        StringBuilder sb = new StringBuilder(4096);
        writeValue(sb, value, 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value, int depth) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map) {
            writeObject(sb, Json.asObject(value, "value"), depth);
        } else if (value instanceof List) {
            writeArray(sb, Json.asArray(value, "value"), depth);
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Number) {
            writeNumber(sb, ((Number) value).doubleValue());
        } else {
            throw new IllegalArgumentException("Cannot serialise " + value.getClass());
        }
    }

    private static void writeObject(StringBuilder sb, Map<String, Object> map, int depth) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            indent(sb, depth + 1);
            writeString(sb, e.getKey());
            sb.append(": ");
            writeValue(sb, e.getValue(), depth + 1);
            if (++i < map.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, depth);
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<Object> list, int depth) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        // A flat array of scalars stays on one line; anything containing an object or array goes
        // vertical. Keeps "why" prose arrays readable without exploding a list of numbers.
        boolean scalarsOnly = list.stream().noneMatch(v -> v instanceof Map || v instanceof List);
        boolean shortEnough = scalarsOnly && list.size() <= 8
                && list.stream().noneMatch(v -> v instanceof String && ((String) v).length() > 24);
        if (shortEnough) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                writeValue(sb, list.get(i), depth);
            }
            sb.append(']');
            return;
        }
        sb.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            indent(sb, depth + 1);
            writeValue(sb, list.get(i), depth + 1);
            if (i < list.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, depth);
        sb.append(']');
    }

    private static void writeNumber(StringBuilder sb, double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            sb.append((long) d);
        } else {
            sb.append(d);
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
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

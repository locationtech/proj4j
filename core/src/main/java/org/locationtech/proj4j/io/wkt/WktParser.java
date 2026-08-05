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
package org.locationtech.proj4j.io.wkt;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns WKT text into a {@link WktNode} tree. Purely syntactic: it knows about brackets, quoted
 * strings and tokens, and nothing at all about coordinate reference systems.
 * <p>
 * Accepts both bracket flavours ({@code [ ]} and {@code ( )}), like PROJ, and both quote
 * conventions: WKT2 doubles an embedded quote ({@code ""}), which is collapsed to a single one.
 * A trailing comma before a closing bracket is rejected, as are unbalanced brackets and
 * unterminated strings.
 * <p>
 * Nesting is bounded by {@link WktLimits#MAX_DEPTH}. The recursion costs one stack frame per two
 * bytes of input, so on an untrusted document an unbounded parser is a {@link StackOverflowError}
 * away from taking down the thread that called it.
 */
public final class WktParser {

    private final String text;
    private int pos;

    private WktParser(String text) {
        this.text = text;
        this.pos = 0;
    }

    /**
     * Parses {@code wkt} into a tree.
     *
     * @throws WktParseException if the text is not syntactically well-formed WKT
     */
    public static WktNode parse(String wkt) {
        if (wkt == null) {
            throw new WktParseException("WKT text is null");
        }
        WktParser p = new WktParser(wkt);
        p.skipWhitespace();
        if (p.pos >= p.text.length()) {
            throw new WktParseException("WKT text is empty");
        }
        WktNode root = p.parseNode(1);
        p.skipWhitespace();
        if (p.pos < p.text.length()) {
            throw new WktParseException("unexpected trailing text at offset " + p.pos + ": \""
                    + p.snippet(p.pos) + "\"");
        }
        if (root.isLeaf()) {
            throw new WktParseException("not a WKT element: \"" + root.value() + "\"");
        }
        return root;
    }

    /**
     * Parses one node. {@code depth} is the node's own level in the tree, the root being 1 and a
     * leaf counting as a level of its own, so the guard bounds the recursion itself rather than
     * some quantity derived from it.
     */
    private WktNode parseNode(int depth) {
        if (depth > WktLimits.MAX_DEPTH) {
            throw new WktParseException("WKT nested more than " + WktLimits.MAX_DEPTH
                    + " deep at offset " + pos + "; refusing to recurse further");
        }
        skipWhitespace();
        if (pos >= text.length()) {
            throw new WktParseException("unexpected end of WKT text");
        }
        char c = text.charAt(pos);
        if (c == '"') {
            return WktNode.quoted(parseQuotedString());
        }
        String token = parseToken();
        skipWhitespace();
        if (pos < text.length() && (text.charAt(pos) == '[' || text.charAt(pos) == '(')) {
            char open = text.charAt(pos);
            char close = open == '[' ? ']' : ')';
            pos++;
            List<WktNode> children = new ArrayList<WktNode>();
            skipWhitespace();
            if (pos < text.length() && text.charAt(pos) == close) {
                pos++;
                return WktNode.of(token, children);
            }
            while (true) {
                children.add(parseNode(depth + 1));
                skipWhitespace();
                if (pos >= text.length()) {
                    throw new WktParseException("unbalanced '" + open + "' in WKT: missing '"
                            + close + "' for " + token);
                }
                char d = text.charAt(pos);
                if (d == ',') {
                    pos++;
                    skipWhitespace();
                    if (pos < text.length() && (text.charAt(pos) == ']' || text.charAt(pos) == ')')) {
                        throw new WktParseException("trailing comma in " + token + "[] at offset "
                                + pos);
                    }
                    continue;
                }
                if (d == ']' || d == ')') {
                    // PROJ tolerates a mismatched flavour of closing bracket; so do we, since
                    // real-world WKT1 from some producers mixes them.
                    pos++;
                    break;
                }
                throw new WktParseException("expected ',' or '" + close + "' at offset " + pos
                        + " in " + token + "[], found \"" + snippet(pos) + "\"");
            }
            return WktNode.of(token, children);
        }
        if (token.length() == 0) {
            throw new WktParseException("unexpected character '" + c + "' at offset " + pos);
        }
        return WktNode.literal(token);
    }

    private String parseQuotedString() {
        // text.charAt(pos) == '"'
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= text.length()) {
                throw new WktParseException("unterminated quoted string in WKT");
            }
            char c = text.charAt(pos);
            if (c == '"') {
                if (pos + 1 < text.length() && text.charAt(pos + 1) == '"') {
                    sb.append('"');
                    pos += 2;
                    continue;
                }
                pos++;
                return sb.toString();
            }
            sb.append(c);
            pos++;
        }
    }

    private String parseToken() {
        int start = pos;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == ',' || c == '[' || c == ']' || c == '(' || c == ')' || c == '"'
                    || Character.isWhitespace(c)) {
                break;
            }
            pos++;
        }
        return text.substring(start, pos);
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private String snippet(int from) {
        int to = Math.min(text.length(), from + 20);
        return text.substring(from, to);
    }
}

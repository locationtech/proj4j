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
import java.util.Collections;
import java.util.List;

/**
 * A node of a parsed WKT tree.
 * <p>
 * The representation follows PROJ's own {@code WKTNode} (9.8.1 {@code src/iso19111/io.cpp}):
 * every element is a node carrying a textual value plus a list of child nodes, and a leaf value
 * such as a number, a quoted name or a bare direction keyword is simply a node with no children.
 * So {@code AXIS["Easting",east]} is a node {@code AXIS} with two childless children,
 * {@code "Easting"} (quoted) and {@code east} (not quoted).
 * <p>
 * There is no builder: instances are created fully formed by {@link #quoted(String)},
 * {@link #literal(String)}, {@link #number(double)} and {@link #of(String, List)}, and the
 * constructor defensively copies the child list and wraps it unmodifiable. An instance is therefore
 * immutable from the moment it exists, and a tree is freely shareable across threads.
 */
public final class WktNode {

    private final String value;
    private final boolean quoted;
    private final boolean leaf;
    private final List<WktNode> children;

    WktNode(String value, boolean quoted, boolean leaf, List<WktNode> children) {
        this.value = value;
        this.quoted = quoted;
        this.leaf = leaf;
        this.children = children == null || children.isEmpty()
                ? Collections.<WktNode>emptyList()
                : Collections.unmodifiableList(new ArrayList<WktNode>(children));
    }

    /**
     * Creates a childless leaf node holding a quoted string.
     */
    public static WktNode quoted(String text) {
        return new WktNode(text, true, true, null);
    }

    /**
     * Creates a childless leaf node holding an unquoted token: a number, a direction keyword or
     * a date-time literal.
     */
    public static WktNode literal(String text) {
        return new WktNode(text, false, true, null);
    }

    /**
     * Creates a childless leaf node holding a number, formatted the way WKT expects.
     */
    public static WktNode number(double v) {
        return new WktNode(WktFormat.number(v), false, true, null);
    }

    /**
     * Creates a keyword node with the given children, which may be empty: {@code MYNODE[]} is a
     * node, not a leaf.
     */
    public static WktNode of(String keyword, List<WktNode> children) {
        return new WktNode(keyword, false, false, children);
    }

    /**
     * The keyword of this node, or the literal text if this is a leaf.
     */
    public String value() {
        return value;
    }

    /**
     * Same as {@link #value()}, named for readability at call sites that expect a keyword.
     */
    public String keyword() {
        return value;
    }

    /**
     * Whether this node's value appeared inside double quotes in the source text. Only leaves
     * can be quoted.
     */
    public boolean isQuoted() {
        return quoted;
    }

    /**
     * Whether this is a value token rather than a bracketed element. An element with no children
     * ({@code MYNODE[]}) is not a leaf.
     */
    public boolean isLeaf() {
        return leaf;
    }

    public List<WktNode> children() {
        return children;
    }

    public int childCount() {
        return children.size();
    }

    /**
     * The child at {@code index}, or {@code null} if there is none.
     */
    public WktNode child(int index) {
        return index >= 0 && index < children.size() ? children.get(index) : null;
    }

    /**
     * The first child whose keyword equals (ignoring case) any of {@code keywords}, or
     * {@code null}.
     */
    public WktNode find(String... keywords) {
        for (int i = 0; i < children.size(); i++) {
            WktNode c = children.get(i);
            if (c.isLeaf() && c.quoted) {
                continue;
            }
            for (int k = 0; k < keywords.length; k++) {
                if (c.value.equalsIgnoreCase(keywords[k])) {
                    return c;
                }
            }
        }
        return null;
    }

    /**
     * Every child whose keyword equals (ignoring case) any of {@code keywords}, in order.
     */
    public List<WktNode> findAll(String... keywords) {
        List<WktNode> out = new ArrayList<WktNode>();
        for (int i = 0; i < children.size(); i++) {
            WktNode c = children.get(i);
            if (c.isLeaf() && c.quoted) {
                continue;
            }
            for (int k = 0; k < keywords.length; k++) {
                if (c.value.equalsIgnoreCase(keywords[k])) {
                    out.add(c);
                    break;
                }
            }
        }
        return out;
    }

    public boolean is(String... keywords) {
        for (int k = 0; k < keywords.length; k++) {
            if (value.equalsIgnoreCase(keywords[k])) {
                return true;
            }
        }
        return false;
    }

    /**
     * The text of child {@code index}, which must exist.
     *
     * @throws WktParseException if there is no such child
     */
    public String textAt(int index) {
        WktNode c = child(index);
        if (c == null) {
            throw new WktParseException("missing value #" + (index + 1) + " in " + value + "[]");
        }
        return c.value;
    }

    /**
     * The numeric value of child {@code index}, which must exist and must parse as a number.
     *
     * @throws WktParseException if there is no such child, or it is not numeric
     */
    public double doubleAt(int index) {
        WktNode c = child(index);
        if (c == null) {
            throw new WktParseException("missing numeric value #" + (index + 1)
                    + " in " + value + "[]");
        }
        return c.asDouble();
    }

    /**
     * This node's value as a number.
     *
     * @throws WktParseException if the value is not numeric
     */
    public double asDouble() {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new WktParseException("expected a number but found \"" + value + "\"", e);
        }
    }

    /**
     * Formats this node as single-line WKT.
     */
    public String toString() {
        StringBuilder sb = new StringBuilder();
        WktFormat.append(this, sb, null, 0);
        return sb.toString();
    }
}

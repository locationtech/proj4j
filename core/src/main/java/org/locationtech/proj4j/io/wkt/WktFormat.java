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

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Serialisation helpers shared by the WKT and PROJJSON writers: how a {@code double} is spelled,
 * and how a {@link WktNode} tree becomes text.
 */
final class WktFormat {

    private WktFormat() {
    }

    /**
     * Formats a number the way PROJ's WKT exporter does: integral values without a decimal point,
     * everything else with the shortest representation that round-trips, and never in a form Java
     * would print but WKT cannot express ({@code 1.0E-5}, {@code NaN}, {@code Infinity}).
     */
    static String number(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new WktParseException("cannot write the non-finite value " + v + " as WKT");
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        // Fifteen significant digits, no exponent, no trailing zeros — which is what PROJ emits,
        // and why its WKT says ANGLEUNIT["degree",0.0174532925199433] rather than Java's
        // seventeen-digit 0.017453292519943295. Byte equality with upstream depends on this.
        BigDecimal bd = new BigDecimal(v, SIGNIFICANT_15).stripTrailingZeros();
        return bd.toPlainString();
    }

    private static final MathContext SIGNIFICANT_15 = new MathContext(15, RoundingMode.HALF_UP);

    /**
     * Appends {@code node} to {@code sb}. When {@code indent} is null the output is a single
     * line with no spaces after commas, exactly as PROJ's non-multiline WKT export.
     * <p>
     * The writer recurses too, so it is bounded too: {@link WktNode#of} is public, a caller can
     * hand this method a tree of any depth, and {@link WktNode#toString()} reaches it. {@code depth}
     * is the node's indentation level with the root at 0, one less than {@link WktParser}'s
     * convention, so {@code depth >= MAX_DEPTH} here refuses exactly the trees the parser refuses —
     * a document that parsed can always be written back.
     *
     * @throws WktParseException if the tree is nested deeper than {@link WktLimits#MAX_DEPTH}
     */
    static void append(WktNode node, StringBuilder sb, String indent, int depth) {
        if (depth >= WktLimits.MAX_DEPTH) {
            throw new WktParseException("WKT tree nested more than " + WktLimits.MAX_DEPTH
                    + " deep; refusing to recurse further");
        }
        if (node.isLeaf()) {
            if (node.isQuoted()) {
                sb.append('"');
                String v = node.value();
                for (int i = 0; i < v.length(); i++) {
                    char c = v.charAt(i);
                    if (c == '"') {
                        sb.append('"');
                    }
                    sb.append(c);
                }
                sb.append('"');
            } else {
                sb.append(node.value());
            }
            return;
        }
        sb.append(node.keyword()).append('[');
        for (int i = 0; i < node.childCount(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            WktNode c = node.child(i);
            if (indent != null && !c.isLeaf()) {
                sb.append('\n');
                for (int d = 0; d <= depth; d++) {
                    sb.append(indent);
                }
            }
            append(c, sb, indent, depth + 1);
        }
        sb.append(']');
    }
}

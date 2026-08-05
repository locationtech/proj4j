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

/**
 * Nesting limits for the WKT reader and writer.
 * <p>
 * Both grammars here are recursive descent, at roughly two bytes of input per stack frame, and the
 * document is untrusted: a consumer calls this library per row with CRS strings supplied by the
 * caller. Unbounded recursion is therefore a denial of service that arrives as a
 * {@link StackOverflowError} — an {@code Error}, so it escapes every {@code catch} in this library
 * and can leave a shared, cached {@code CoordinateTransform} half-built. The limits below make it
 * unreachable rather than catchable; nothing in {@code core/src/main} catches {@code Error}, by
 * design.
 * <p>
 * Measured, on JDK 21 with the default stack: {@code A[A[...b...]]} overflows at a bracket nesting
 * of about <b>6,150</b>, from about 12 KB of input; with {@code -Xss256k} it overflows at about
 * <b>390</b>, from under 800 bytes.
 * <p>
 * This mirrors {@code pipeline.PipelineJson}'s {@code MAX_DEPTH = 64}, which has guarded the
 * <em>trusted</em> classpath deformation models since 1.5 while the untrusted readers had no limit
 * at all.
 * <p>
 * Package-private: these are an implementation invariant, not API. The numbers are pinned by
 * {@code security.parsers.WktDepthLimitTest} and cross-checked against
 * {@code io.projjson.JsonLimits} there, so the two formats cannot drift apart unnoticed.
 *
 * @since 1.5
 */
final class WktLimits {

    /**
     * Maximum <em>syntactic</em> nesting: the depth of the {@link WktNode} tree, counting the root
     * element as 1 and counting leaves. Enforced by {@link WktParser} on the way in and by
     * {@link WktFormat} on the way out, so a tree that parsed can always be written back.
     * <p>
     * Measured over the whole of the shipped {@code proj4/wkt/epsg.properties} — all <b>5,671</b>
     * definitions, of which 72 are {@code COMPD_CS} — the deepest real tree is <b>7</b> on the way
     * in and <b>8</b> on the way out:
     * <table>
     *   <caption>deepest real document, by direction</caption>
     *   <tr><td>WKT1 read</td><td><b>7</b></td>
     *       <td>{@code EPSG:4100} "ETRS89 / DKTM4 + DVR90 height"</td></tr>
     *   <tr><td>WKT2:2019 written</td><td><b>8</b></td><td>{@code EPSG:4100}</td></tr>
     *   <tr><td>WKT2:2015 written</td><td><b>8</b></td><td>—</td></tr>
     * </table>
     * The output is one deeper than the input because WKT2 wraps the projected CRS's base in
     * {@code BASEGEOGCRS}. 64 is eight times the deepest document this library either accepts or
     * emits; {@code security.parsers.RealCorpusDepthHeadroomTest} pins all three numbers.
     */
    static final int MAX_DEPTH = 64;

    /**
     * Maximum <em>semantic</em> nesting: how many coordinate reference systems may be nested inside
     * one another, counting the outermost as 1. A {@code COMPOUNDCRS} inside a {@code COMPOUNDCRS}
     * is 2; a {@code BOUNDCRS[SOURCECRS[COMPOUNDCRS[…]]]} is 3.
     * <p>
     * The deepest real value is <b>3</b>, not 2: over the 5,671 shipped definitions the histogram
     * is <b>1,240</b> at depth 1, <b>4,367</b> at depth 2 and <b>64</b> at depth 3 — the last being
     * {@code COMPD_CS[PROJCS[GEOGCS[…]]]}, where a projected CRS's base geographic CRS is a third
     * nested CRS in its own right. 24 is eight times that.
     * <p>
     * Deliberately well below {@link #MAX_DEPTH}: a semantic limit equal to the syntactic one could
     * never fire, because every semantic level costs at least one bracket, and a guard that cannot
     * fire is not a guard.
     */
    static final int MAX_CRS_DEPTH = 24;

    private WktLimits() {
        throw new AssertionError("no instances");
    }
}

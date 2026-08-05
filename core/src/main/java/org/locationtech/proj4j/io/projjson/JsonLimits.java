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

/**
 * Nesting limits for the PROJJSON reader and writer — the JSON counterpart of
 * {@code io.wkt.WktLimits}, whose javadoc carries the reasoning and the measurements. The values
 * are deliberately identical so that the same hostile document is refused at the same depth in
 * either notation; {@code security.parsers.ProjJsonDepthLimitTest} pins that.
 * <p>
 * A separate class rather than a shared one because {@code WktLimits} is package-private in
 * {@code io.wkt}, and a nesting limit is an implementation invariant that should not become public
 * API just to be shared across two packages.
 *
 * @since 1.5
 */
final class JsonLimits {

    /**
     * Maximum <em>syntactic</em> JSON nesting, counting the root value as 1. Enforced by
     * {@link Json#parse} on the way in and by {@link Json#write} on the way out, so a document that
     * parsed can always be written back — the round trip cannot be made to overflow by a document
     * the reader accepted.
     * <p>
     * The deepest real PROJJSON measured in this repository is <b>8</b> — this library's own
     * output for {@code EPSG:7402} "NTF (Paris) / France II + NGF IGN69", the deepest of the 5,671
     * shipped EPSG definitions when each is read as WKT1 and written as PROJJSON. There is no
     * PROJJSON <em>input</em> corpus in the repository, so the emitted depth is the measurement
     * that matters; {@code security.parsers.RealCorpusDepthHeadroomTest} pins it.
     */
    static final int MAX_DEPTH = 64;

    /**
     * Maximum <em>semantic</em> nesting: how many coordinate reference systems may be nested inside
     * one another, counting the outermost as 1. Enforced by {@link ProjJsonReader} and
     * {@link ProjJsonWriter}.
     * <p>
     * Below {@link #MAX_DEPTH} on purpose — see {@code io.wkt.WktLimits#MAX_CRS_DEPTH}. In PROJJSON
     * one {@code CompoundCRS} level costs two JSON levels, so at 24 the semantic guard fires with
     * the syntactic one still far away, which is what makes it testable.
     * <p>
     * The deepest real value is <b>3</b>, measured over the 5,671 shipped EPSG definitions, so 24
     * is eight times real data. The number is kept identical to {@code WktLimits.MAX_CRS_DEPTH} so
     * that the same CRS graph is refused at the same nesting in either notation.
     */
    static final int MAX_CRS_DEPTH = 24;

    private JsonLimits() {
        throw new AssertionError("no instances");
    }
}

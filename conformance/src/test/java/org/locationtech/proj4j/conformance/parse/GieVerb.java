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
 * The 19 {@code .gie} command verbs, in the exact order of the
 * {@code gie_tags[]} array at {@code 9.8.1:src/apps/gie.cpp:158-178}.
 *
 * <p><strong>Declaration order is load bearing.</strong> {@code at_tag()}
 * matches with {@code strncmp(line, tag, strlen(tag))} — an unanchored prefix
 * test at column 0 — and returns the <em>first</em> array entry that matches.
 * So {@code operationfoo} is an {@code operation} command whose arguments are
 * {@code foo}, and reordering these constants could change which verb a line
 * resolves to. Do not sort them.
 */
public enum GieVerb {

    OPEN_GIE("<gie>"),
    OPERATION("operation"),
    CRS_SRC("crs_src"),
    CRS_DST("crs_dst"),
    USE_PROJ4_INIT_RULES("use_proj4_init_rules"),
    ACCEPT("accept"),
    EXPECT("expect"),
    ROUNDTRIP("roundtrip"),
    BANNER("banner"),
    VERBOSE("verbose"),
    DIRECTION("direction"),
    TOLERANCE("tolerance"),
    IGNORE("ignore"),
    REQUIRE_GRID("require_grid"),
    ECHO("echo"),
    SKIP("skip"),
    CLOSE_GIE("</gie>"),
    OPEN_GIE_STRICT("<gie-strict>"),
    CLOSE_GIE_STRICT("</gie-strict>");

    private static final GieVerb[] IN_TAG_ORDER = values();

    private final String token;

    GieVerb(String token) {
        this.token = token;
    }

    /** The literal text matched at column 0, e.g. {@code "require_grid"}. */
    public String token() {
        return token;
    }

    /**
     * Port of {@code at_tag()}: the first verb, in declared order, whose token
     * is a prefix of {@code line}, or {@code null} if the line opens no
     * command. {@code line} is expected to have already been through
     * {@link PjText#chomp}, as {@code nextline()} does in the C.
     */
    public static GieVerb matchPrefix(String line) {
        if (line == null) {
            return null;
        }
        for (GieVerb v : IN_TAG_ORDER) {
            if (line.startsWith(v.token)) {
                return v;
            }
        }
        return null;
    }

    /**
     * True for the four block-delimiter pseudo-verbs, which structure the file
     * rather than assert anything.
     */
    public boolean isBlockDelimiter() {
        return this == OPEN_GIE || this == CLOSE_GIE
                || this == OPEN_GIE_STRICT || this == CLOSE_GIE_STRICT;
    }
}

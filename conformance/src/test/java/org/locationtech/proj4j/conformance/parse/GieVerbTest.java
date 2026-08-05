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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GieVerbTest {

    @Test
    @DisplayName("there are exactly 19 verbs, in gie.cpp:158-178 array order")
    void nineteenVerbsInTagOrder() {
        assertEquals(19, GieVerb.values().length);

        List<String> tokens = new ArrayList<String>();
        for (GieVerb v : GieVerb.values()) {
            tokens.add(v.token());
        }
        assertEquals(Arrays.asList(
                "<gie>", "operation", "crs_src", "crs_dst", "use_proj4_init_rules",
                "accept", "expect", "roundtrip", "banner", "verbose", "direction",
                "tolerance", "ignore", "require_grid", "echo", "skip", "</gie>",
                "<gie-strict>", "</gie-strict>"), tokens);
    }

    @Test
    @DisplayName("matching is strncmp prefix matching, so 'operationfoo' is an operation")
    void prefixMatching() {
        assertEquals(GieVerb.OPERATION, GieVerb.matchPrefix("operationfoo"));
        assertEquals(GieVerb.OPERATION, GieVerb.matchPrefix("operation +proj=merc"));
        assertEquals(GieVerb.EXPECT, GieVerb.matchPrefix("expectfailure"));
        assertEquals(GieVerb.REQUIRE_GRID, GieVerb.matchPrefix("require_grid BETA2007.gsb"));
    }

    @Test
    @DisplayName("a non-verb line matches nothing")
    void nonVerbsDoNotMatch() {
        assertNull(GieVerb.matchPrefix("unknown_keyword"));
        assertNull(GieVerb.matchPrefix(""));
        assertNull(GieVerb.matchPrefix("+step +init=epsg:31370"));
        assertNull(GieVerb.matchPrefix("-----"));
        // Prefix matching is anchored: a verb in the middle of a line is not one.
        assertNull(GieVerb.matchPrefix(" operation +proj=merc"));
        assertNull(GieVerb.matchPrefix("the operation failed"));
    }

    @Test
    @DisplayName("the strict and non-strict delimiters are distinguished, not confused")
    void delimitersAreDistinct() {
        assertEquals(GieVerb.OPEN_GIE, GieVerb.matchPrefix("<gie>"));
        assertEquals(GieVerb.CLOSE_GIE, GieVerb.matchPrefix("</gie>"));
        assertEquals(GieVerb.OPEN_GIE_STRICT, GieVerb.matchPrefix("<gie-strict>"));
        assertEquals(GieVerb.CLOSE_GIE_STRICT, GieVerb.matchPrefix("</gie-strict>"));
    }
}

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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PjTextTest {

    // ------------------------------------------------------------- pj_chomp

    @Test
    @DisplayName("chomp strips an inline # comment, and everything after it")
    void chompStripsComments() {
        assertEquals("expect 1 2", PjText.chomp("expect 1 2  # DE_DHDN_Lat-Lon"));
        assertEquals("", PjText.chomp("# a whole-line comment"));
        assertEquals("", PjText.chomp("#####"));
    }

    @Test
    @DisplayName("chomp trims whitespace and ';' at both ends")
    void chompTrimsBothEnds() {
        assertEquals("a b", PjText.chomp("   a b \t\r\n"));
        assertEquals("a b", PjText.chomp(";;a b;;"));
        assertEquals("", PjText.chomp("     "));
        assertEquals("", PjText.chomp(";"));
        assertEquals("", PjText.chomp(""));
    }

    @Test
    @DisplayName("chomp left-trims, so an indented verb still starts at column 0")
    void chompEnablesIndentedVerbs() {
        // peirce_q.gie really does indent 6 of its 10 `operation` lines by one
        // space, and gie accepts them, because nextline() chomps before at_tag().
        assertEquals("operation  +proj=peirce_q +R=6370997 +shape=diamond",
                PjText.chomp(" operation  +proj=peirce_q +R=6370997 +shape=diamond"));
    }

    @Test
    @DisplayName("chomp keeps a trailing backslash, so ' \\' continuation survives")
    void chompKeepsTrailingBackslash() {
        String chomped = PjText.chomp("operation +proj=pipeline \\  # turn off dual datum shift");
        assertEquals("operation +proj=pipeline \\", chomped);
        assertEquals('\\', chomped.charAt(chomped.length() - 1));
    }

    // ------------------------------------------------------------ pj_shrink

    @Test
    @DisplayName("'+' prefixes are optional: both spellings normalise identically")
    void plusPrefixesAreOptional() {
        String withPlus = PjText.shrink("+proj=aea +ellps=GRS80");
        String withoutPlus = PjText.shrink("proj=aea ellps=GRS80");
        assertEquals("proj=aea ellps=GRS80", withPlus);
        assertEquals(withPlus, withoutPlus);
    }

    @Test
    @DisplayName("shrink keeps the '+' inside an exponent")
    void shrinkKeepsExponentSign() {
        assertEquals("1.23e+08", PjText.shrink("1.23e+08"));
        assertEquals("expect 1.23e+08 2", PjText.shrink("expect  1.23e+08   2"));
    }

    @Test
    @DisplayName("shrink strips a trailing comment and collapses repeated whitespace")
    void shrinkStripsCommentAndCollapsesWhitespace() {
        assertEquals("expect 1 2", PjText.shrink("expect   1    2  # comment"));
        assertEquals("tolerance 0.01 m", PjText.shrink("tolerance  0.01  m"));
    }

    @Test
    @DisplayName("',' and '=' are greedy, eating the whitespace around them")
    void commaAndEqualsAreGreedy() {
        assertEquals("a,b", PjText.shrink("a , b"));
        assertEquals("x=1", PjText.shrink("x = 1"));
        assertEquals("towgs84=0,0,0", PjText.shrink("+towgs84 = 0 , 0 , 0"));
    }

    @Test
    @DisplayName("shrink removes ';' as whitespace")
    void shrinkRemovesSemicolons() {
        assertEquals("a b", PjText.shrink("a;b"));
    }

    @Test
    @DisplayName("a double-quoted string after '=' is copied verbatim, '\"\"' escaping a quote")
    void shrinkRespectsQuotedStrings() {
        assertEquals("name=\"a  b\"", PjText.shrink("+name=\"a  b\""));
        assertEquals("name=\"say \"\"hi\"\"\"", PjText.shrink("+name=\"say \"\"hi\"\"\""));
    }

    @Test
    @DisplayName("shrink of empty / all-whitespace input is empty")
    void shrinkOfEmpty() {
        assertEquals("", PjText.shrink(""));
        assertEquals("", PjText.shrink("    "));
        assertEquals("", PjText.shrink("# only a comment"));
    }
}

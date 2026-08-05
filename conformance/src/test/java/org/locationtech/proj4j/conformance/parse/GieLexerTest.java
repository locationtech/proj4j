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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GieLexerTest {

    private static GieFile fixture(String name) throws IOException {
        InputStream in = GieLexerTest.class.getResourceAsStream("/parse-fixtures/" + name);
        assertNotNull(in, "missing fixture /parse-fixtures/" + name);
        try {
            return GieLexer.lex(name, in);
        } finally {
            in.close();
        }
    }

    // ------------------------------------------------- decorative elements

    @Test
    @DisplayName("a decorative element is five IDENTICAL chars, whatever they are")
    void decorativeElementIsFiveIdenticalChars() {
        assertTrue(GieLexer.isDecorativeElement("-----"));
        assertTrue(GieLexer.isDecorativeElement("====="));
        assertTrue(GieLexer.isDecorativeElement("#####"));
        assertTrue(GieLexer.isDecorativeElement("*****"));
        assertTrue(GieLexer.isDecorativeElement("aaaaa"));
        // longer runs are fine; only the first five characters are examined
        assertTrue(GieLexer.isDecorativeElement(
                "-------------------------------------------------------------"));
        assertTrue(GieLexer.isDecorativeElement("====== not a comment"));
    }

    @Test
    @DisplayName("it is not 'starts with dashes': mixed or short runs are not decorative")
    void nonDecorativeElements() {
        assertFalse(GieLexer.isDecorativeElement("----x"));
        assertFalse(GieLexer.isDecorativeElement("--"));
        assertFalse(GieLexer.isDecorativeElement("----"));
        assertFalse(GieLexer.isDecorativeElement("-=-=-"));
        assertFalse(GieLexer.isDecorativeElement("-"));
        assertFalse(GieLexer.isDecorativeElement(""));
        assertFalse(GieLexer.isDecorativeElement(null));
    }

    // ------------------------------------------------------------- strict

    @Test
    @DisplayName("strict mode: ' \\' assembles a multi-line operation")
    void strictContinuationAssembles() throws IOException {
        GieFile f = fixture("strict-continuation.gie");
        List<GieCommand> ops = f.commands(GieVerb.OPERATION);
        assertEquals(1, ops.size());
        GieCommand op = ops.get(0);
        assertEquals("proj=pipeline step init=epsg:4313 inv step init=epsg:31370", op.args());
        // Reported line is the verb's FIRST line, even though the command ends on line 9.
        assertEquals(7, op.line());
        assertEquals(9, op.lastLine());
        assertEquals(3, op.raw().split("\n").length);
    }

    @Test
    @DisplayName("strict mode: blanks, comments and decorative elements are skipped")
    void strictSkipsNoise() throws IOException {
        GieFile f = fixture("strict-continuation.gie");
        assertEquals(7, f.size());
        assertEquals(GieVerb.USE_PROJ4_INIT_RULES, f.commands().get(0).verb());
        assertEquals("true", f.commands().get(0).args());
        assertEquals(GieVerb.CLOSE_GIE_STRICT, f.commands().get(6).verb());
    }

    @Test
    @DisplayName("strict mode: an indented verb is still a verb, because chomp left-trims")
    void strictAcceptsIndentedVerbs() throws IOException {
        GieFile f = fixture("strict-continuation.gie");
        List<GieCommand> dir = f.commands(GieVerb.DIRECTION);
        assertEquals(1, dir.size());
        assertEquals("inverse", dir.get(0).args());
        assertEquals(13, dir.get(0).line());
    }

    @Test
    @DisplayName("strict mode: a non-verb, non-comment, non-decorative line is fatal")
    void strictRejectsUnknownLine() {
        GieSyntaxException e = assertThrows(GieSyntaxException.class,
                () -> fixture("strict-bad-line.gie"));
        assertEquals(4, e.line());
        assertTrue(e.getMessage().contains("unsupported command"), e.getMessage());
        assertTrue(e.getMessage().contains("unknown_keyword"), e.getMessage());
    }

    @Test
    @DisplayName("strict mode: 'tolerance  0.03  m' keeps both value and unit")
    void strictShrinksIrregularWhitespace() throws IOException {
        GieFile f = fixture("strict-continuation.gie");
        assertEquals("0.03 m", f.commands(GieVerb.TOLERANCE).get(0).args());
    }

    // --------------------------------------------------------- non-strict

    @Test
    @DisplayName("non-strict: text outside the block is ignored entirely")
    void nonStrictIgnoresOutsideText() throws IOException {
        GieFile f = fixture("nonstrict-terminators.gie");
        // Four commands only; the leading prose (which begins with the word
        // "expect") and the trailing prose (which contains "accept 9 9") are
        // outside the <gie> block and never seen.
        assertEquals(4, f.size());
        assertEquals(0, f.commands(GieVerb.CLOSE_GIE).size());
        assertEquals(0, f.commands(GieVerb.OPEN_GIE).size());
    }

    @Test
    @DisplayName("non-strict: continuation ends at the next verb")
    void nonStrictContinuationEndsAtNextVerb() throws IOException {
        GieFile f = fixture("nonstrict-terminators.gie");
        GieCommand op = f.commands(GieVerb.OPERATION).get(0);
        // Two physical lines joined with a single space, stopping at `tolerance`.
        assertEquals("proj=aea ellps=GRS80 lat_1=0 lat_2=2", op.args());
        assertEquals(6, op.line());
        assertEquals(7, op.lastLine());
        assertEquals("0.1 mm", f.commands(GieVerb.TOLERANCE).get(0).args());
    }

    @Test
    @DisplayName("non-strict: continuation ends at a decorative element")
    void nonStrictContinuationEndsAtDecorativeElement() throws IOException {
        GieFile f = fixture("nonstrict-terminators.gie");
        GieCommand expect = f.commands(GieVerb.EXPECT).get(0);
        // The '=====' on the following line terminates it, so none of the prose
        // after the decorative element is swallowed.
        assertEquals("222571.608757106 110653.326743030", expect.args());
        assertEquals(10, expect.line());
        assertEquals(10, expect.lastLine());
    }

    @Test
    @DisplayName("non-strict: a bare unknown keyword is argument text, not an error")
    void nonStrictToleratesUnknownKeyword() throws IOException {
        // builtins.gie relies on exactly this: its <gie> block ends with a bare
        // `unknown_keyword` line, which becomes trailing junk on the preceding
        // `expect` and is then ignored by parse_coord.
        GieFile f = fixture("nonstrict-unknown-keyword.gie");
        assertEquals(3, f.size());
        GieCommand expect = f.commands(GieVerb.EXPECT).get(0);
        assertEquals("222571.608757106 110653.326743030 unknown_keyword", expect.args());

        GieCoord c = GieCoordParser.parseCoord(expect.args());
        assertFalse(c.isError());
        assertEquals(2, c.dimensionsGiven());
        assertEquals(222571.608757106, c.x());
        assertEquals(110653.326743030, c.y());
    }

    // -------------------------------------------------------------- blocks

    @Test
    @DisplayName("multiple blocks per file, mixed modes, prose between them")
    void multipleBlocks() throws IOException {
        GieFile f = fixture("two-blocks.gie");
        assertEquals(7, f.size());
        assertEquals(2, f.commands(GieVerb.OPERATION).size());
        assertEquals("proj=merc", f.commands(GieVerb.OPERATION).get(0).args());
        assertEquals("proj=utm zone=32", f.commands(GieVerb.OPERATION).get(1).args());
        // 'tolerance 1 km' sits between the blocks and must not be picked up.
        assertEquals(0, f.commands(GieVerb.TOLERANCE).size());
        assertEquals(1, f.commands(GieVerb.CLOSE_GIE_STRICT).size());
    }

    @Test
    @DisplayName("an odd delimiter count at EOF is fatal: Missing '</gie>'")
    void missingCloseIsFatal() {
        GieSyntaxException e = assertThrows(GieSyntaxException.class,
                () -> fixture("missing-close.gie"));
        assertTrue(e.getMessage().contains("Missing '</gie>'"), e.getMessage());
    }

    @Test
    @DisplayName("an unterminated strict block names '</gie-strict>' instead")
    void missingStrictCloseIsFatal() {
        GieSyntaxException e = assertThrows(GieSyntaxException.class,
                () -> GieLexer.lex("t.gie", "<gie-strict>\noperation +proj=merc\n"));
        assertTrue(e.getMessage().contains("Missing '</gie-strict>'"), e.getMessage());
    }

    @Test
    @DisplayName("a file with no block at all is fatal: Missing '<gie>'")
    void missingOpenIsFatal() {
        GieSyntaxException e = assertThrows(GieSyntaxException.class,
                () -> GieLexer.lex("t.gie", "operation +proj=merc\naccept 1 2\n"));
        assertTrue(e.getMessage().contains("Missing '<gie>'"), e.getMessage());
    }

    @Test
    @DisplayName("prefix matching means 'operationfoo' lexes as operation with args 'foo'")
    void prefixMatchingInTheLexer() {
        GieFile f = GieLexer.lex("t.gie", "<gie-strict>\noperationfoo bar\n</gie-strict>\n");
        assertEquals(2, f.size());
        assertEquals(GieVerb.OPERATION, f.commands().get(0).verb());
        assertEquals("foo bar", f.commands().get(0).args());
    }

    @Test
    @DisplayName("the 'skip' verb abandons the rest of the file without a missing-tag error")
    void skipAbandonsFile() {
        GieFile f = GieLexer.lex("t.gie",
                "<gie-strict>\noperation +proj=merc\nskip\naccept 1 2\n");
        assertEquals(2, f.size());
        assertEquals(GieVerb.SKIP, f.commands().get(1).verb());
    }
}

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * The tokenizer and tree builder, ported from {@code io.wkt_parsing} in PROJ 9.8.1's
 * {@code test/unit/test_io.cpp} (corpus CASE 1 and CASE 2).
 */
public class WktParserTest {

    @Test
    public void emptyNode() {
        WktNode n = WktParser.parse("MYNODE[]");
        assertEquals("MYNODE", n.keyword());
        assertEquals(0, n.childCount());
        assertFalse("an element with no children is still an element, not a value", n.isLeaf());
    }

    @Test
    public void whitespaceIsSkipped() {
        WktNode n = WktParser.parse("  MYNODE  [  ]  ");
        assertEquals("MYNODE", n.keyword());
        assertEquals(0, n.childCount());
    }

    @Test
    public void quotedChild() {
        WktNode n = WktParser.parse("MYNODE[\"x\"]");
        assertEquals(1, n.childCount());
        assertEquals("x", n.child(0).value());
        assertTrue(n.child(0).isQuoted());
        assertEquals("MYNODE[\"x\"]", n.toString());
    }

    @Test
    public void whitespaceAroundChild() {
        WktNode n = WktParser.parse("MYNODE[  \"x\"   ]");
        assertEquals(1, n.childCount());
        assertEquals("x", n.child(0).value());
    }

    @Test
    public void bracketInsideAQuotedString() {
        WktNode n = WktParser.parse("MYNODE[\"x[\",1]");
        assertEquals(2, n.childCount());
        assertEquals("x[", n.child(0).value());
        assertEquals(1.0, n.child(1).asDouble(), 0.0);
        assertEquals("MYNODE[\"x[\",1]", n.toString());
    }

    @Test
    public void nesting() {
        assertEquals("A[B[y]]", WktParser.parse("A[B[y]]").toString());
        assertEquals("A[\"a\",B[\"b\",C[\"c\"]],D[\"d\"]]",
                WktParser.parse("A[\"a\",B[\"b\",C[\"c\"]],D[\"d\"]]").toString());
    }

    @Test
    public void parenthesesAreAccepted() {
        assertEquals("A[\"x\",B[\"y\"]]", WktParser.parse("A(\"x\",B(\"y\"))").toString());
    }

    @Test
    public void doubledQuoteIsAnEscapedQuote() {
        WktNode n = WktParser.parse("A[\"xy\"\"z\"]");
        assertEquals("xy\"z", n.child(0).value());
        assertEquals("A[\"xy\"\"z\"]", n.toString());
    }

    @Test
    public void malformedInputIsRejected() {
        String[] invalid = {
                "",
                "x",
                "x,",
                "x[",
                "[",
                "MYNODE[\"x\"",
                "MYNODE[\"x\",",
                "A[B[",
                "MYNODE[\"unterminated",
                "MYNODE[1,]",
        };
        for (int i = 0; i < invalid.length; i++) {
            try {
                WktParser.parse(invalid[i]);
                fail("expected a WktParseException for \"" + invalid[i] + "\"");
            } catch (WktParseException expected) {
                // the point
            }
        }
    }

    @Test
    public void nullIsRejected() {
        try {
            WktParser.parse(null);
            fail("expected a WktParseException");
        } catch (WktParseException expected) {
            // the point
        }
    }

    @Test
    public void findIsCaseInsensitiveAndSkipsQuotedLeaves() {
        WktNode n = WktParser.parse("GEOGCS[\"UNIT\",UNIT[\"degree\",0.0174532925199433]]");
        WktNode unit = n.find("unit");
        assertEquals("UNIT", unit.keyword());
        assertEquals("degree", unit.child(0).value());
    }
}

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
package org.locationtech.proj4j.security.parsers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.locationtech.proj4j.CrsCreationException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.api.Proj;
import org.locationtech.proj4j.io.projjson.ProjJsonReader;
import org.locationtech.proj4j.io.projjson.ProjJsonWriter;
import org.locationtech.proj4j.io.wkt.CrsDefinition;
import org.locationtech.proj4j.io.wkt.WktParseException;
import org.locationtech.proj4j.io.wkt.WktReader;

/**
 * The PROJJSON reader and writer refuse a document that would recurse the stack away.
 *
 * <p>The WKT sibling of this file, {@link WktDepthLimitTest}, carries the threat model. The shape
 * is the same in both notations and so are the numbers: 64 levels of syntax, 24 nested coordinate
 * reference systems. What differs is that a PROJJSON {@code CompoundCRS} costs <em>two</em> JSON
 * levels per CRS level, so the syntactic guard and the semantic one are reached by quite different
 * documents — which is exactly why both are asserted.
 *
 * <p><b>Measured before the fix</b>, on JDK 21 with the default stack:
 * {@code ProjJsonReader.readDefinition}, {@code Proj.createCrsFromProjJson} and
 * {@code ProjJsonWriter.write} all threw {@link StackOverflowError} on these inputs.
 */
public class ProjJsonDepthLimitTest {

    private static final int HOSTILE = 100000;

    private static final String INNER_JSON = ""
            + "{\"type\":\"GeographicCRS\",\"name\":\"WGS 84\","
            + "\"datum\":{\"type\":\"GeodeticReferenceFrame\",\"name\":\"WGS 84\","
            + "\"ellipsoid\":{\"name\":\"WGS 84\",\"semi_major_axis\":6378137,"
            + "\"inverse_flattening\":298.257223563}},"
            + "\"coordinate_system\":{\"subtype\":\"ellipsoidal\",\"axis\":["
            + "{\"name\":\"Latitude\",\"abbreviation\":\"lat\",\"direction\":\"north\","
            + "\"unit\":\"degree\"},"
            + "{\"name\":\"Longitude\",\"abbreviation\":\"lon\",\"direction\":\"east\","
            + "\"unit\":\"degree\"}]}}";

    private static final String INNER_WKT =
            "GEOGCS[\"WGS 84\",DATUM[\"WGS_1984\",SPHEROID[\"WGS 84\",6378137,298.257223563]],"
                    + "PRIMEM[\"Greenwich\",0],UNIT[\"degree\",0.0174532925199433]]";

    // ------------------------------------------------------------------- syntax: Json.parse

    /**
     * The JSON value limit counts the scalar at the bottom, so {@code n} arrays around a number is
     * a document of depth {@code n + 1}: 63 arrays is the deepest that parses.
     */
    @Test
    public void jsonNestingIsBoundedAndTheBoundIsWhereItIsClaimed() {
        // 63 arrays parses -- it is refused later, by the type check, which proves Json.parse itself
        // accepted it rather than the depth guard rejecting it.
        assertEquals("PROJJSON must be a JSON object", rejectionOf(arrays(63)));
        assertEquals("JSON nested more than 64 deep", rejectionOf(arrays(64)));
        assertEquals("JSON nested more than 64 deep", rejectionOf(arrays(65)));
        assertEquals("JSON nested more than 64 deep", rejectionOf(arrays(HOSTILE)));
        assertEquals("JSON nested more than 64 deep", rejectionOf(objects(HOSTILE)));
    }

    // ------------------------------------------------ semantics: the CompoundCRS double walk

    /**
     * {@code CompoundCRS} inside {@code CompoundCRS} recurses twice over one document: once in
     * {@code Json.parse} and once in {@code ProjJsonReader.crs -> compound -> crs}. 23 wrappers
     * around a real CRS is 24 CRSs and reads; 24 wrappers is refused, with the syntactic guard still
     * far away.
     */
    @Test
    public void nestedCompoundCrsIsBoundedBySemanticDepthNotOnlyByJsonNesting() {
        CrsDefinition ok = new ProjJsonReader().readDefinition(nestedCompound(23));
        assertEquals(CrsDefinition.Kind.COMPOUND, ok.getKind());
        assertEquals("the whole chain must actually have been built", 24, chainLength(ok));

        assertEquals("CRSs nested more than 24 deep in PROJJSON", rejectionOf(nestedCompound(24)));
        assertEquals("CRSs nested more than 24 deep in PROJJSON", rejectionOf(nestedCompound(25)));
    }

    /** {@code BoundCRS.source_crs} is the other way back into {@code crs()}. */
    @Test
    public void nestedBoundCrsIsBounded() {
        StringBuilder open = new StringBuilder();
        StringBuilder close = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            open.append("{\"type\":\"BoundCRS\",\"source_crs\":");
            close.append("}");
        }
        assertEquals("CRSs nested more than 24 deep in PROJJSON",
                rejectionOf(open + INNER_JSON + close));
    }

    // ---------------------------------------------------------------- the writer recurses too

    /**
     * {@link ProjJsonWriter#write(CrsDefinition)} walks a caller-supplied {@link CrsDefinition}
     * graph and then hands the resulting {@code Map} tree to a second recursion, {@code Json.write}.
     * Both are bounded; this asserts the first, and asserts the legitimate case still writes.
     */
    @Test
    public void theProjJsonWriterIsBoundedOnSemanticDepth() {
        String written = new ProjJsonWriter().write(compoundChain(23));
        assertNotNull(written);
        assertTrue(written.contains("\"CompoundCRS\""));

        for (int n : new int[]{24, 25, HOSTILE}) {
            try {
                new ProjJsonWriter().write(compoundChain(n));
                fail("expected a WktParseException at " + n);
            } catch (WktParseException e) {
                assertTrue(e.getMessage(),
                        e.getMessage().startsWith("CRSs nested more than 24 deep"));
            }
        }
    }

    /**
     * The round trip the plan calls out: {@code Json.write} recurses, so a document accepted by the
     * reader must be writable by the writer and re-readable afterwards. This is the acceptance half
     * of the writer's guard — the pair of limits is chosen so the parse ceiling can never produce a
     * tree the writer refuses.
     */
    @Test
    public void aDeepButLegalDocumentSurvivesAFullRoundTrip() {
        String json = nestedCompound(23);
        CrsDefinition read = new ProjJsonReader().readDefinition(json);
        String out = new ProjJsonWriter().write(read);
        CrsDefinition again = new ProjJsonReader().readDefinition(out);
        assertEquals(24, chainLength(again));
        assertEquals(out, new ProjJsonWriter().write(again));
    }

    // ------------------------------------------------------------------- the caller's view

    @Test
    public void theFacadeReportsARefusalNotAnError() {
        try {
            Proj.createCrsFromProjJson(nestedCompound(HOSTILE));
            fail("expected a CrsCreationException");
        } catch (CrsCreationException e) {
            assertSame(ErrorCause.INVALID_CRS_SYNTAX, e.cause());
            assertTrue(e.getMessage(), e.getMessage().contains("nested more than 64 deep"));
            assertTrue("the cause must be the reader's own exception, not an Error",
                    e.getCause() instanceof WktParseException);
        }
        assertNotNull(Proj.createCrsFromProjJson(INNER_JSON));
    }

    // ------------------------------------------------------- the control: is the input hostile?

    /**
     * The same control as the WKT side: on a 128 KiB stack an unguarded recursive descent over the
     * same document must still die, or none of the assertions above are measuring anything.
     */
    @Test
    public void theControl_anUnguardedParserDiesOnTheSameInputAndTheSameStack() {
        final String hostile = arrays(HOSTILE);

        Throwable unguarded = WktDepthLimitTest.onSmallStack(new Runnable() {
            public void run() {
                naiveDepth(hostile, 0);
            }
        });
        assertTrue("CONTROL FAILED: the hostile document no longer overflows an unguarded parser, "
                        + "so nothing above is a measurement -- got " + unguarded,
                unguarded instanceof StackOverflowError);

        Throwable guarded = WktDepthLimitTest.onSmallStack(new Runnable() {
            public void run() {
                new ProjJsonReader().readDefinition(hostile);
            }
        });
        assertTrue("production read must refuse, not overflow -- got " + guarded,
                guarded instanceof WktParseException);
    }

    /** And the guard is not simply refusing everything. */
    @Test
    public void legitimateProjJsonStillParsesOnASmallStack() {
        final AtomicReference<CrsDefinition> out = new AtomicReference<CrsDefinition>();
        Throwable t = WktDepthLimitTest.onSmallStack(new Runnable() {
            public void run() {
                out.set(new ProjJsonReader().readDefinition(INNER_JSON));
            }
        });
        assertNull("a legitimate PROJJSON document must still parse: " + t, t);
        assertNotNull(out.get());
        assertEquals(CrsDefinition.Kind.GEOGRAPHIC, out.get().getKind());
    }

    // ------------------------------------------------------------------------------- helpers

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    private static String arrays(int n) {
        return repeat("[", n) + "1" + repeat("]", n);
    }

    private static String objects(int n) {
        return repeat("{\"a\":", n) + "1" + repeat("}", n);
    }

    private static String nestedCompound(int n) {
        return repeat("{\"type\":\"CompoundCRS\",\"name\":\"c\",\"components\":[", n)
                + INNER_JSON + repeat("]}", n);
    }

    private static CrsDefinition compoundChain(int wrappers) {
        CrsDefinition cur = new WktReader().readDefinition(INNER_WKT);
        for (int i = 0; i < wrappers; i++) {
            CrsDefinition c = new CrsDefinition();
            c.setKind(CrsDefinition.Kind.COMPOUND);
            c.setName("c" + i);
            c.addComponent(cur);
            cur = c;
        }
        return cur;
    }

    private static int chainLength(CrsDefinition def) {
        int n = 1;
        while (!def.getComponents().isEmpty()) {
            def = def.getComponents().get(0);
            n++;
        }
        return n;
    }

    private static String rejectionOf(String json) {
        try {
            new ProjJsonReader().readDefinition(json);
            return "NOT REJECTED";
        } catch (WktParseException e) {
            String m = e.getMessage();
            int at = m.indexOf(" at ");
            int semi = m.indexOf(';');
            int end = at < 0 ? semi : semi < 0 ? at : Math.min(at, semi);
            return end < 0 ? m : m.substring(0, end);
        }
    }

    /**
     * A deliberately unguarded recursive descent over the same nesting: the control that the input
     * is genuinely hostile rather than merely long.
     */
    private static int naiveDepth(String s, int pos) {
        while (pos < s.length() && s.charAt(pos) != '[' && s.charAt(pos) != ']') {
            pos++;
        }
        if (pos < s.length() && s.charAt(pos) == '[') {
            pos = naiveDepth(s, pos + 1);
            while (pos < s.length() && s.charAt(pos) != ']') {
                pos++;
            }
            return pos + 1;
        }
        return pos;
    }
}

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

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CrsCreationException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.api.Proj;
import org.locationtech.proj4j.io.wkt.CrsDefinition;
import org.locationtech.proj4j.io.wkt.WktNode;
import org.locationtech.proj4j.io.wkt.WktParseException;
import org.locationtech.proj4j.io.wkt.WktParser;
import org.locationtech.proj4j.io.wkt.WktReader;
import org.locationtech.proj4j.io.wkt.WktWriter;

/**
 * The WKT reader and writer refuse a document that would recurse the stack away.
 *
 * <p><b>Threat model.</b> This library is called per row inside Spark executors, the CRS string is
 * untrusted user input, and one {@code CoordinateTransform} is shared between threads. Both WKT
 * layers are recursive descent at roughly two bytes of input per stack frame, so an unbounded
 * parser turns about 12 KB of {@code "A[A[A[…"} into a {@link StackOverflowError} — an
 * {@code Error}, which escapes {@code catch (WktParseException)} at {@code Proj.java:898-905} and
 * {@code catch (RuntimeException)} at {@code :916-923} alike, and can leave a cached transform
 * half-built. Nothing in {@code core/src/main} catches {@code Throwable} or {@code Error}, on
 * purpose; the fix is to make the overflow unreachable, not to catch it.
 *
 * <p><b>Measured before the fix</b>, on JDK 21 with the default stack: {@code WktParser.parse},
 * {@code WktReader.readDefinition}, {@code Proj.createCrsFromWkt}, {@code WktNode.toString} and
 * {@code WktWriter.toNode} all threw {@code StackOverflowError} on these same inputs. The parser
 * overflowed at a bracket nesting of about 6,150 with the default stack and about 390 under
 * {@code -Xss256k}.
 *
 * <p><b>Every assertion here has both halves.</b> A guard that refuses everything passes every
 * hostile test and breaks the library, so each limit is pinned by a rejection <em>and</em> by an
 * acceptance one level below it, and {@link #legitimateWktStillParsesOnASmallStack()} keeps a real
 * EPSG definition working on the same 128 KiB stack that makes the hostile input fatal to an
 * unguarded parser.
 */
public class WktDepthLimitTest {

    /**
     * Deep enough to overflow any stack this library will ever run on: about 200 KB of brackets.
     */
    private static final int HOSTILE = 100000;

    /** Small enough that an unguarded recursive descent over {@link #HOSTILE} certainly dies. */
    private static final int SMALL_STACK_BYTES = 128 * 1024;

    private static final String INNER_WKT =
            "GEOGCS[\"WGS 84\",DATUM[\"WGS_1984\",SPHEROID[\"WGS 84\",6378137,298.257223563]],"
                    + "PRIMEM[\"Greenwich\",0],UNIT[\"degree\",0.0174532925199433]]";

    // ------------------------------------------------------------------ syntax: WktParser

    /**
     * The tree limit counts leaves, so {@code n} brackets around a token is a tree of depth
     * {@code n + 1}: 63 brackets is the deepest that parses, 64 is the first refused.
     */
    @Test
    public void bracketNestingIsBoundedAndTheBoundIsWhereItIsClaimed() {
        assertNotNull("63 brackets is a tree of depth 64 and must still parse",
                WktParser.parse(brackets(63)));
        assertEquals("WKT nested more than 64 deep",
                messagePrefixOfRejection(brackets(64)));
        assertEquals("WKT nested more than 64 deep",
                messagePrefixOfRejection(brackets(65)));
        assertEquals("WKT nested more than 64 deep",
                messagePrefixOfRejection(brackets(HOSTILE)));
    }

    /** {@code (…)} is the other bracket flavour PROJ accepts, and it must be bounded too. */
    @Test
    public void theOtherBracketFlavourIsBoundedToo() {
        String hostile = repeat("A(", HOSTILE) + "b" + repeat(")", HOSTILE);
        try {
            WktParser.parse(hostile);
            fail("expected a WktParseException");
        } catch (WktParseException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("WKT nested more than 64 deep"));
        }
    }

    // ------------------------------------------------- semantics: the COMPOUNDCRS double walk

    /**
     * {@code COMPOUNDCRS[COMPOUNDCRS[…]]} recurses twice over the same document: once building the
     * tree ({@code WktParser}) and once walking it ({@code WktReader.crs -> compound -> crs}). The
     * semantic limit is 24 nested CRSs and the innermost {@code GEOGCS} is itself one, so 23
     * wrappers is the deepest that reads.
     */
    @Test
    public void nestedCompoundCrsIsBoundedBySemanticDepthNotOnlyByBrackets() {
        CrsDefinition ok = new WktReader().readDefinition(nestedCompound(23));
        assertNotNull(ok);
        assertEquals(CrsDefinition.Kind.COMPOUND, ok.getKind());
        assertEquals("the whole chain must actually have been built", 24, chainLength(ok));

        // 24 wrappers is 25 CRSs, and is refused by the SEMANTIC guard -- the tree is only 27 deep,
        // far inside the syntactic limit, which is what makes this a test of the second layer.
        assertTrue("the syntactic guard must not be the one firing here",
                depthOf(WktParser.parse(nestedCompound(24))) < 64);
        assertEquals("CRSs nested more than 24 deep in WKT",
                messagePrefixOfCrsRejection(nestedCompound(24)));
        assertEquals("CRSs nested more than 24 deep in WKT",
                messagePrefixOfCrsRejection(nestedCompound(25)));
    }

    /** {@code BOUNDCRS[SOURCECRS[…]]} is the other way back into {@code crs()}. */
    @Test
    public void nestedBoundCrsIsBounded() {
        StringBuilder open = new StringBuilder();
        StringBuilder close = new StringBuilder();
        // 25 wrappers is 26 CRSs -- past the semantic limit -- but only 50 brackets, so the tree
        // is about 54 deep and the syntactic guard is not the one that fires.
        for (int i = 0; i < 25; i++) {
            open.append("BOUNDCRS[SOURCECRS[");
            close.append("]]");
        }
        String wkt = open + INNER_WKT + close;
        assertTrue("two brackets per CRS level, so the tree stays inside the syntactic limit",
                depthOf(WktParser.parse(wkt)) < 64);
        assertEquals("CRSs nested more than 24 deep in WKT", messagePrefixOfCrsRejection(wkt));
    }

    // ---------------------------------------------------------------- the writers recurse too

    /**
     * {@link WktNode#of} is public, so a caller can hand the formatter a tree of any depth;
     * {@link WktNode#toString()} reaches it with no parser involved. The writer's bound is the
     * parser's, so a tree that parsed can always be written back.
     */
    @Test
    public void theWktFormatterIsBoundedOnTheSameTreeDepthAsTheParser() {
        assertNotNull("a tree of depth 64 parses, so it must also write", deepNode(63).toString());
        try {
            deepNode(64).toString();
            fail("expected a WktParseException");
        } catch (WktParseException e) {
            assertTrue(e.getMessage(),
                    e.getMessage().startsWith("WKT tree nested more than 64 deep"));
        }
        try {
            deepNode(HOSTILE).toString();
            fail("expected a WktParseException");
        } catch (WktParseException e) {
            assertTrue(e.getMessage(),
                    e.getMessage().startsWith("WKT tree nested more than 64 deep"));
        }
    }

    /**
     * {@link CrsDefinition} is public and mutable, so {@link WktWriter#toNode} takes a graph a
     * caller assembled, not only one a reader produced.
     */
    @Test
    public void theWktWriterIsBoundedOnSemanticDepth() {
        assertNotNull(new WktWriter().toNode(compoundChain(23)));
        for (int n : new int[]{24, 25, HOSTILE}) {
            try {
                new WktWriter().toNode(compoundChain(n));
                fail("expected a WktParseException at " + n);
            } catch (WktParseException e) {
                assertTrue(e.getMessage(),
                        e.getMessage().startsWith("CRSs nested more than 24 deep"));
            }
        }
    }

    /**
     * A definition that contains itself is the degenerate case of the same recursion, and used to
     * be an unbounded one. Assert the guard converts it into a refusal.
     */
    @Test
    public void aSelfReferentialDefinitionIsRefusedRatherThanRecursedForever() {
        CrsDefinition loop = new CrsDefinition();
        loop.setKind(CrsDefinition.Kind.COMPOUND);
        loop.setName("ouroboros");
        loop.addComponent(loop);
        try {
            loop.horizontalComponent();
            fail("expected a WktParseException");
        } catch (WktParseException e) {
            assertTrue(e.getMessage(),
                    e.getMessage().startsWith("CRSs nested more than 24 deep"));
        }
        try {
            loop.resolveDatum();
            fail("expected a WktParseException");
        } catch (WktParseException e) {
            assertTrue(e.getMessage(),
                    e.getMessage().startsWith("CRSs nested more than 24 deep"));
        }
        try {
            loop.resolveToWgs84();
            fail("expected a WktParseException");
        } catch (WktParseException e) {
            assertTrue(e.getMessage(),
                    e.getMessage().startsWith("CRSs nested more than 24 deep"));
        }
    }

    // ------------------------------------------------------------------- the caller's view

    /**
     * What a caller of the facade actually sees. Both halves matter: the refusal is a
     * {@link Proj4jException} with a cause, and the same call on a legitimate document still
     * builds a CRS.
     */
    @Test
    public void theFacadeReportsARefusalNotAnError() {
        try {
            Proj.createCrsFromWkt(nestedCompound(HOSTILE));
            fail("expected a CrsCreationException");
        } catch (CrsCreationException e) {
            assertSame(ErrorCause.INVALID_CRS_SYNTAX, e.cause());
            assertTrue(e.getMessage(), e.getMessage().contains("nested more than 64 deep"));
            assertTrue("the cause must be the parser's own exception, not an Error",
                    e.getCause() instanceof WktParseException);
            assertTrue(e instanceof Proj4jException);
        }
        assertNotNull(Proj.createCrsFromWkt(INNER_WKT));
    }

    // ------------------------------------------------------- the control: is the input hostile?

    /**
     * The positive control for every assertion above.
     *
     * <p>An assertion that production code throws {@code WktParseException} proves nothing on its
     * own — it would also hold if the input were harmless. So the same string is fed, on the same
     * 128 KiB thread, to a deliberately unguarded recursive descent over the same bracket language.
     * That one must die with {@link StackOverflowError}. If it ever stops doing so, this file has
     * stopped testing anything and the assertion below says so.
     */
    @Test
    public void theControl_anUnguardedParserDiesOnTheSameInputAndTheSameStack() {
        final String hostile = brackets(HOSTILE);

        Throwable unguarded = onSmallStack(new Runnable() {
            public void run() {
                naiveDepth(hostile, 0);
            }
        });
        assertTrue("CONTROL FAILED: the hostile input no longer overflows an unguarded parser on a "
                        + SMALL_STACK_BYTES + "-byte stack, so nothing below is a measurement -- got "
                        + unguarded,
                unguarded instanceof StackOverflowError);

        Throwable guarded = onSmallStack(new Runnable() {
            public void run() {
                WktParser.parse(hostile);
            }
        });
        assertTrue("production parse must refuse, not overflow -- got " + guarded,
                guarded instanceof WktParseException);

        Throwable guardedReader = onSmallStack(new Runnable() {
            public void run() {
                new WktReader().readDefinition(nestedCompound(HOSTILE));
            }
        });
        assertTrue("production read must refuse, not overflow -- got " + guardedReader,
                guardedReader instanceof WktParseException);
    }

    /**
     * The other half of the control: the guard is not simply refusing everything. A real EPSG
     * definition still reads, and still produces a usable CRS, on that same small stack.
     */
    @Test
    public void legitimateWktStillParsesOnASmallStack() {
        final AtomicReference<CoordinateReferenceSystem> out =
                new AtomicReference<CoordinateReferenceSystem>();
        Throwable t = onSmallStack(new Runnable() {
            public void run() {
                out.set(new WktReader().read(INNER_WKT));
            }
        });
        assertNull("a legitimate WKT document must still parse: " + t, t);
        assertNotNull(out.get());
    }

    // ------------------------------------------------------------------------------- helpers

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    private static String brackets(int n) {
        return repeat("A[", n) + "b" + repeat("]", n);
    }

    private static String nestedCompound(int n) {
        return repeat("COMPOUNDCRS[\"c\",", n) + INNER_WKT + repeat("]", n);
    }

    private static WktNode deepNode(int n) {
        WktNode node = WktNode.of("A", Arrays.asList(WktNode.literal("x")));
        for (int i = 1; i < n; i++) {
            node = WktNode.of("A", Arrays.asList(node));
        }
        return node;
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

    /** Tree depth of a parsed node, root 1, leaves counted — the parser's own convention. */
    private static int depthOf(WktNode node) {
        int best = 0;
        for (int i = 0; i < node.childCount(); i++) {
            best = Math.max(best, depthOf(node.child(i)));
        }
        return best + 1;
    }

    private static String messagePrefixOfRejection(String wkt) {
        try {
            WktParser.parse(wkt);
            return "NOT REJECTED";
        } catch (WktParseException e) {
            return firstClause(e.getMessage());
        }
    }

    private static String messagePrefixOfCrsRejection(String wkt) {
        try {
            new WktReader().readDefinition(wkt);
            return "NOT REJECTED";
        } catch (WktParseException e) {
            return firstClause(e.getMessage());
        }
    }

    /** The message up to its first {@code " at "} or {@code ";"}, so offsets do not over-pin. */
    private static String firstClause(String message) {
        int at = message.indexOf(" at ");
        int semi = message.indexOf(';');
        int end = at < 0 ? semi : semi < 0 ? at : Math.min(at, semi);
        return end < 0 ? message : message.substring(0, end);
    }

    /**
     * A deliberately unguarded recursive descent over the same bracket language: one frame per
     * bracket, exactly the shape {@code WktParser.parseNode} had before this workstream. Used only
     * as the control that the input is genuinely hostile.
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

    /**
     * Runs {@code body} on a thread with a deliberately small stack and returns whatever it threw,
     * or {@code null} if it returned normally.
     */
    static Throwable onSmallStack(final Runnable body) {
        final AtomicReference<Throwable> thrown = new AtomicReference<Throwable>();
        Runnable wrapper = new Runnable() {
            public void run() {
                try {
                    body.run();
                } catch (Throwable t) {
                    thrown.set(t);
                }
            }
        };
        Thread t = new Thread(null, wrapper, "depth-probe", SMALL_STACK_BYTES);
        t.start();
        try {
            t.join(120000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for the probe thread");
        }
        if (t.isAlive()) {
            throw new AssertionError("the probe thread did not finish");
        }
        return thrown.get();
    }
}

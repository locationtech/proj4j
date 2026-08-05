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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.locationtech.proj4j.io.wkt.WktParseException;

/**
 * {@code Json.write} recurses, and it is bounded.
 *
 * <p>This test lives in {@code io.projjson} rather than beside its siblings in
 * {@code security.parsers} for one reason: {@link Json} is package-private, and the writer's guard
 * cannot be reached from outside the package. {@code ProjJsonWriter} caps CRS nesting at 24, which
 * costs about 50 JSON levels — comfortably inside the writer's 64 — so no public call can make
 * {@code Json.write} recurse to its limit. The guard is still worth having, because it is what
 * makes the round trip safe by construction rather than by arithmetic that a later change could
 * quietly invalidate; and a guard nobody has ever seen fire is not a guard, so it is exercised here
 * directly. {@code ProjJsonTest} already reaches {@code Json.parse} from this package, so the
 * precedent is the file's own.
 *
 * <p><b>Before the fix</b> the tree below overflowed the stack in {@code Json.write}.
 */
public class JsonDepthLimitTest {

    /** Both halves: 63 nested arrays writes, 64 does not. */
    @Test
    public void theWriterIsBoundedAtTheSameDepthAsTheReader() {
        assertNotNull(Json.write(nestedList(63)));

        for (int n : new int[]{64, 65, 20000}) {
            try {
                Json.write(nestedList(n));
                fail("expected a WktParseException at " + n);
            } catch (WktParseException e) {
                assertTrue(e.getMessage(),
                        e.getMessage().startsWith("JSON nested more than 64 deep"));
            }
        }
    }

    /**
     * The reader's boundary, asserted from inside the package so it is the parser being measured
     * and not {@code ProjJsonReader}'s type checks.
     */
    @Test
    public void theReaderIsBoundedAtTheDepthItClaims() {
        assertNotNull(Json.parse(arrays(63)));
        for (int n : new int[]{64, 65, 20000}) {
            try {
                Json.parse(arrays(n));
                fail("expected a WktParseException at " + n);
            } catch (WktParseException e) {
                assertTrue(e.getMessage(),
                        e.getMessage().startsWith("JSON nested more than 64 deep"));
            }
        }
    }

    /**
     * The invariant that makes the round trip safe: <b>anything the reader accepts, the writer can
     * write</b>. The two conventions differ by one — the reader counts the root value as 1, the
     * writer as indentation level 0 — and getting that off by one wrong would turn the deepest
     * legal document into an unwritable one. Assert it at the exact boundary rather than trusting
     * the arithmetic.
     */
    @Test
    public void everythingTheReaderAcceptsTheWriterCanWrite() {
        Object deepest = Json.parse(arrays(63));
        String text = Json.write(deepest);
        assertNotNull(text);
        assertEquals("and it reads back", 63, listDepth(Json.parse(text)));
    }

    // ------------------------------------------------------------------------------- helpers

    private static Object nestedList(int n) {
        Object cur = Double.valueOf(1);
        for (int i = 0; i < n; i++) {
            List<Object> outer = new ArrayList<Object>(1);
            outer.add(cur);
            cur = outer;
        }
        return cur;
    }

    private static String arrays(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append('[');
        }
        sb.append('1');
        for (int i = 0; i < n; i++) {
            sb.append(']');
        }
        return sb.toString();
    }

    private static int listDepth(Object value) {
        int n = 0;
        while (value instanceof List) {
            List<?> l = (List<?>) value;
            value = l.isEmpty() ? null : l.get(0);
            n++;
        }
        return n;
    }
}

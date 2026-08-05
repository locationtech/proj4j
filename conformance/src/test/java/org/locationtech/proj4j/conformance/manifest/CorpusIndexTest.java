/*
 * Copyright 2026 The Proj4J Contributors.
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
package org.locationtech.proj4j.conformance.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CorpusIndexTest {

    private static final AssertionKey A = AssertionKey.of("gie/adams_ws1.gie", 0, 0, "00000001");
    private static final AssertionKey B = AssertionKey.of("gie/builtins.gie", 3, 1, "00000002");
    private static final AssertionKey C = AssertionKey.of("gigs/5110.gie.failing", 2, 0, "00000003");

    @Test
    void roundTripsThroughItsRenderedForm() throws IOException {
        CorpusIndex index = CorpusIndex.of(Arrays.asList(C, A, B));
        CorpusIndex reloaded = CorpusIndex.load(new StringReader(index.render()));
        assertEquals(index.keys(), reloaded.keys());
        assertEquals(index.render(), reloaded.render());
    }

    @Test
    void rendersInCanonicalOrder() {
        List<String> lines = new ArrayList<String>();
        for (String line : CorpusIndex.of(Arrays.asList(C, B, A)).render().split("\n")) {
            if (!line.startsWith("#") && !line.isEmpty()) {
                lines.add(line);
            }
        }
        assertEquals(Arrays.asList(A.toString(), B.toString(), C.toString()), lines);
    }

    @Test
    void isBuiltFromARunIncludingPassingAssertions() {
        ObservedRun run = ObservedRun.builder()
                .record(A, AssertionOutcome.PASS)
                .record(B, AssertionOutcome.FAIL)
                .record(C, AssertionOutcome.SKIP)
                .build();
        CorpusIndex index = CorpusIndex.ofRun(run);
        assertEquals(3, index.size());
        assertTrue(index.contains(A));
        assertTrue(index.contains(B));
        assertTrue(index.contains(C));
    }

    @Test
    void failsLoudlyOnAMalformedLine() {
        ManifestFormatException e = assertThrows(
                ManifestFormatException.class,
                () -> CorpusIndex.load(new StringReader("# c\n" + A + "\nnot-a-key\n"), "index.tsv"));
        assertEquals(3, e.lineNumber());
        assertEquals("index.tsv", e.source());
    }

    @Test
    void failsOnADuplicateKey() {
        ManifestFormatException e = assertThrows(
                ManifestFormatException.class, () -> CorpusIndex.load(new StringReader(A + "\n" + A + "\n")));
        assertEquals(2, e.lineNumber());
        assertTrue(e.getMessage().contains("duplicate"), e.getMessage());
    }

    @Test
    void anEmptyIndexHoldsNothing() throws IOException {
        assertTrue(CorpusIndex.empty().isEmpty());
        assertTrue(CorpusIndex.load(new StringReader("# only comments\n\n")).isEmpty());
    }
}

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class AssertionKeyTest {

    private static final String OPERATION = "+proj=utm +zone=32 +ellps=GRS80";
    private static final String ASSERTION = "accept 12 55; expect 691875.632 6098907.825";

    @Test
    void roundTripsThroughToStringAndParse() {
        AssertionKey key = AssertionKey.of("gie/builtins.gie", 137, 4, "3f9c1ab0");
        assertEquals("gie/builtins.gie#137:4@3f9c1ab0", key.toString());
        AssertionKey reparsed = AssertionKey.parse(key.toString());
        assertEquals(key, reparsed);
        assertEquals(key.hashCode(), reparsed.hashCode());
        assertEquals(key.toString(), reparsed.toString());
    }

    @Test
    void roundTripsAComputedKey() {
        AssertionKey key = AssertionKey.compute("gigs/5110.gie.failing", 2, 0, OPERATION, ASSERTION);
        assertEquals(key, AssertionKey.parse(key.toString()));
    }

    @Test
    void roundTripsAPathContainingDotsDashesAndSpaces() {
        AssertionKey key = AssertionKey.of("gie/dir with space/5101.4-jhs.gie.failing", 0, 0, "00000000");
        assertEquals(key, AssertionKey.parse(key.toString()));
        assertEquals("gie/dir with space/5101.4-jhs.gie.failing", AssertionKey.parse(key.toString()).filePath());
    }

    @Test
    void exposesItsComponents() {
        AssertionKey key = AssertionKey.parse("gie/more_builtins.gie#75:11@deadbeef");
        assertEquals("gie/more_builtins.gie", key.filePath());
        assertEquals(75, key.operationBlockIndex());
        assertEquals(11, key.assertionIndex());
        assertEquals("deadbeef", key.contentHash());
    }

    @Test
    void rejectsMalformedRenderings() {
        String[] malformed = {
            "gie/builtins.gie#0:0", // no hash
            "gie/builtins.gie#0:0@abcd", // short hash
            "gie/builtins.gie#0:0@ABCDEF01", // upper-case hash
            "gie/builtins.gie#0:0@zzzzzzzz", // non-hex hash
            "gie/builtins.gie#:0@abcdef01", // no block index
            "gie/builtins.gie#0:@abcdef01", // no assertion index
            "gie/builtins.gie#-1:0@abcdef01", // negative block index
            "gie/builtins.gie#0.5:0@abcdef01", // non-integer block index
            "#0:0@abcdef01", // no path
            "gie/builtins.gie@abcdef01", // no separators
            "gie/builtins.gie#0:0@abcdef01 ", // trailing space
            "",
            null,
        };
        for (int i = 0; i < malformed.length; i++) {
            final String rendered = malformed[i];
            assertThrows(
                    IllegalArgumentException.class,
                    () -> AssertionKey.parse(rendered),
                    "expected rejection of \"" + rendered + "\"");
        }
    }

    @Test
    void rejectsMalformedComponents() {
        assertThrows(IllegalArgumentException.class, () -> AssertionKey.of("", 0, 0, "abcdef01"));
        assertThrows(IllegalArgumentException.class, () -> AssertionKey.of(null, 0, 0, "abcdef01"));
        assertThrows(IllegalArgumentException.class, () -> AssertionKey.of("a#b", 0, 0, "abcdef01"));
        assertThrows(IllegalArgumentException.class, () -> AssertionKey.of("a\tb", 0, 0, "abcdef01"));
        assertThrows(IllegalArgumentException.class, () -> AssertionKey.of("gie/x.gie", -1, 0, "abcdef01"));
        assertThrows(IllegalArgumentException.class, () -> AssertionKey.of("gie/x.gie", 0, -1, "abcdef01"));
        assertThrows(IllegalArgumentException.class, () -> AssertionKey.of("gie/x.gie", 0, 0, "abcdefg1"));
    }

    @Test
    void ordersByPathThenBlockThenAssertionIndex() {
        AssertionKey a = AssertionKey.of("gie/adams_ws1.gie", 0, 0, "00000001");
        AssertionKey b = AssertionKey.of("gie/builtins.gie", 0, 0, "00000002");
        AssertionKey c = AssertionKey.of("gie/builtins.gie", 0, 1, "00000003");
        AssertionKey d = AssertionKey.of("gie/builtins.gie", 2, 0, "00000004");
        AssertionKey e = AssertionKey.of("gigs/5110.gie.failing", 0, 0, "00000005");

        List<AssertionKey> shuffled = new ArrayList<AssertionKey>(Arrays.asList(d, b, e, a, c));
        Collections.sort(shuffled);
        assertEquals(Arrays.asList(a, b, c, d, e), shuffled);
    }

    @Test
    void ordersNumericallyNotLexicographicallyOnIndices() {
        AssertionKey nine = AssertionKey.of("gie/builtins.gie", 9, 0, "00000001");
        AssertionKey ten = AssertionKey.of("gie/builtins.gie", 10, 0, "00000002");
        assertTrue(nine.compareTo(ten) < 0, "block 9 must sort before block 10, not after");
        AssertionKey a9 = AssertionKey.of("gie/builtins.gie", 0, 9, "00000001");
        AssertionKey a10 = AssertionKey.of("gie/builtins.gie", 0, 10, "00000002");
        assertTrue(a9.compareTo(a10) < 0, "assertion 9 must sort before assertion 10");
    }

    @Test
    void orderIsATotalOrderConsistentWithEquals() {
        AssertionKey a = AssertionKey.of("gie/builtins.gie", 1, 2, "0000000a");
        AssertionKey b = AssertionKey.of("gie/builtins.gie", 1, 2, "0000000b");
        AssertionKey aAgain = AssertionKey.of("gie/builtins.gie", 1, 2, "0000000a");
        assertEquals(0, a.compareTo(aAgain));
        assertEquals(a, aAgain);
        assertTrue(a.compareTo(b) < 0);
        assertTrue(b.compareTo(a) > 0);
        assertNotEquals(a, b);
    }

    @Test
    void equalityConsidersEveryComponent() {
        AssertionKey base = AssertionKey.of("gie/builtins.gie", 1, 2, "0000000a");
        assertNotEquals(base, AssertionKey.of("gie/other.gie", 1, 2, "0000000a"));
        assertNotEquals(base, AssertionKey.of("gie/builtins.gie", 9, 2, "0000000a"));
        assertNotEquals(base, AssertionKey.of("gie/builtins.gie", 1, 9, "0000000a"));
        assertNotEquals(base, AssertionKey.of("gie/builtins.gie", 1, 2, "0000000b"));
        assertNotEquals(base, "not a key");
        assertSame(base, base);
    }

    @Test
    void hashIsEightLowerCaseHexDigits() {
        String hash = AssertionKey.contentHash(OPERATION, ASSERTION);
        assertEquals(8, hash.length());
        assertTrue(hash.matches("[0-9a-f]{8}"), "hash was \"" + hash + "\"");
    }

    @Test
    void hashIsStableAcrossCalls() {
        assertEquals(AssertionKey.contentHash(OPERATION, ASSERTION), AssertionKey.contentHash(OPERATION, ASSERTION));
    }

    @Test
    void hashChangesWhenTheAssertionTextChanges() {
        String edited = "accept 12 55; expect 691875.632 6098907.826";
        assertNotEquals(AssertionKey.contentHash(OPERATION, ASSERTION), AssertionKey.contentHash(OPERATION, edited));
    }

    @Test
    void hashChangesWhenTheOperationDefinitionChanges() {
        String edited = "+proj=utm +zone=33 +ellps=GRS80";
        assertNotEquals(AssertionKey.contentHash(OPERATION, ASSERTION), AssertionKey.contentHash(edited, ASSERTION));
    }

    @Test
    void hashDistinguishesIdenticalAssertionsUnderDifferentOperations() {
        AssertionKey underUtm32 = AssertionKey.compute("gie/builtins.gie", 0, 0, "+proj=utm +zone=32", ASSERTION);
        AssertionKey underUtm33 = AssertionKey.compute("gie/builtins.gie", 0, 0, "+proj=utm +zone=33", ASSERTION);
        assertNotEquals(underUtm32, underUtm33);
    }

    @Test
    void hashIsNotConfusedByFieldConcatenation() {
        // "ab" + "c" must not hash the same as "a" + "bc".
        assertNotEquals(AssertionKey.contentHash("ab", "c"), AssertionKey.contentHash("a", "bc"));
    }

    @Test
    void hashToleratesNullText() {
        assertEquals(8, AssertionKey.contentHash(null, null).length());
        assertEquals(AssertionKey.contentHash(null, null), AssertionKey.contentHash("", ""));
    }

    @Test
    void normalisationCollapsesWhitespaceAndDropsPlusAndSemicolon() {
        assertEquals("proj=aea ellps=GRS80", AssertionKey.normalise("  +proj=aea   +ellps=GRS80  "));
        assertEquals("proj=aea ellps=GRS80", AssertionKey.normalise("proj = aea\tellps =\tGRS80"));
        assertEquals("accept 12 55 expect 1 2", AssertionKey.normalise("accept 12 55; expect 1 2"));
    }

    @Test
    void normalisationStripsCommentsAndJoinsContinuations() {
        String continued = "operation +proj=pipeline \\  # turn off dual datum shift\n  +step +proj=utm +zone=32";
        assertEquals("operation proj=pipeline step proj=utm zone=32", AssertionKey.normalise(continued));
    }

    @Test
    void normalisationMakesCommaGreedy() {
        assertEquals("towgs84=0,0,0", AssertionKey.normalise("+towgs84 = 0 , 0 , 0"));
    }

    @Test
    void normalisationPreservesCaseBecauseAnUpstreamCaseChangeIsARealChange() {
        assertNotEquals(AssertionKey.normalise("proj=UTM"), AssertionKey.normalise("proj=utm"));
    }

    @Test
    void cosmeticReflowDoesNotChangeTheKey() {
        String original = "operation +proj=utm +zone=32 +ellps=GRS80";
        String reflowed = "operation   +proj=utm \\\n   +zone=32   +ellps=GRS80   # unchanged";
        assertEquals(
                AssertionKey.compute("gie/builtins.gie", 0, 0, original, ASSERTION),
                AssertionKey.compute("gie/builtins.gie", 0, 0, reflowed, ASSERTION));
    }

}

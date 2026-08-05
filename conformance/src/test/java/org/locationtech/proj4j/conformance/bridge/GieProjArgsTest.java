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
package org.locationtech.proj4j.conformance.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.proj4j.conformance.parse.PjText;

class GieProjArgsTest {

    // ------------------------------------------------------ tokenisation

    @Test
    @DisplayName("tokenises a pj_shrink-normalised string and preserves order")
    void tokenisesShrunkString() {
        // What GieLexer hands the runner: no '+' prefixes, single spaces.
        String shrunk = PjText.shrink("+proj=merc  +ellps=GRS80   +lat_ts=0");
        assertEquals("proj=merc ellps=GRS80 lat_ts=0", shrunk);

        GieProjArgs a = GieProjArgs.parse(shrunk);
        assertEquals(Arrays.asList("proj", "ellps", "lat_ts"), a.keys());
        assertEquals("merc", a.peek("proj"));
        assertEquals("GRS80", a.peek("ellps"));
        assertEquals("0", a.peek("lat_ts"));
    }

    @Test
    @DisplayName("accepts a raw '+'-prefixed string too, so fixtures and corpus share one entry point")
    void tokenisesRawString() {
        GieProjArgs a = GieProjArgs.parse("+proj=merc +ellps=GRS80");
        assertEquals(Arrays.asList("proj", "ellps"), a.keys());
    }

    @Test
    @DisplayName("a flag token has no value, and 'key=' has an empty one - they are distinguishable")
    void flagVersusEmptyValue() {
        GieProjArgs a = GieProjArgs.parse("proj=utm south zone=");
        assertFalse(a.find("south").hasValue());
        assertNull(a.find("south").value());
        assertTrue(a.find("zone").hasValue());
        assertEquals("", a.find("zone").value());
    }

    @Test
    @DisplayName("prefix matching is exact: key 'a' never matches 'axis'")
    void prefixMatchingIsExact() {
        GieProjArgs a = GieProjArgs.parse("proj=merc axis=enu");
        assertNull(a.find("a"));
        assertNotNull(a.find("axis"));
    }

    // ------------------------------------------- first-occurrence-wins

    @Test
    @DisplayName("a duplicated key keeps the FIRST occurrence, as pj_param_exists does")
    void firstOccurrenceWins() {
        // PROJ walks the paralist front-to-back and returns the first match, which
        // is why +init=/+datum= expansions are appended and can be shadowed.
        // Proj4Parser's original HashMap kept the LAST occurrence - exactly
        // inverted.
        GieProjArgs a = GieProjArgs.parse("proj=merc lat_0=10 lat_0=20 lat_0=30");
        assertEquals("10", a.peek("lat_0"));
        assertEquals(Arrays.asList("lat_0"), a.duplicateKeys());
        assertEquals(4, a.size(), "every duplicate is retained, not collapsed");
    }

    @Test
    @DisplayName("toProj4Args feeds Proj4Parser the first occurrence only")
    void toProj4ArgsUsesFirstOccurrence() {
        GieProjArgs a = GieProjArgs.parse("proj=merc lat_0=10 lat_0=20");
        assertEquals(Arrays.asList("+proj=merc", "+lat_0=10"), Arrays.asList(a.toProj4Args()));
    }

    // ------------------------------------------------------ step scoping

    @Test
    @DisplayName("+step splits the definition and lookup stops at the first step")
    void stepSplittingAndScoping() {
        GieProjArgs a = GieProjArgs.parse(
                "proj=pipeline ellps=GRS80 step proj=axisswap order=2,1 step proj=merc lat_0=45");

        assertTrue(a.isPipeline());
        assertEquals(3, a.stepCount(), "global head plus two steps");

        // pj_param_exists stops at the first `step` token unless the key sought is
        // `step` (param.cpp:72-105). So the globally scoped +proj is `pipeline`,
        // and lat_0 - which lives in step 2 - is invisible from the global scope.
        assertEquals("pipeline", a.peek("proj"));
        assertEquals("GRS80", a.peek("ellps"));
        assertNull(a.peek("lat_0"), "lookup must not see past the first step");
        assertNull(a.peek("order"));

        List<GieProjArgs> steps = a.steps();
        assertEquals(3, steps.size());
        assertEquals("pipeline", steps.get(0).peek("proj"));
        assertEquals("axisswap", steps.get(1).peek("proj"));
        assertEquals("2,1", steps.get(1).peek("order"));
        assertEquals("merc", steps.get(2).peek("proj"));
        assertEquals("45", steps.get(2).peek("lat_0"));
        assertNull(steps.get(2).peek("order"), "step 2 must not see step 1's parameters");
    }

    @Test
    @DisplayName("a bare '+step' first token scopes away the global +proj, as in more_builtins.gie:232")
    void leadingStepHidesGlobalProj() {
        GieProjArgs a = GieProjArgs.parse("step proj=pipeline step proj=merc");
        assertNull(a.peek("proj"), "the first token is `step`, so no +proj is globally visible");
        assertEquals(3, a.stepCount());
        assertEquals("pipeline", a.steps().get(1).peek("proj"));
    }

    // ----------------------------------------------- unknown keys, `used`

    @Test
    @DisplayName("an unknown key is retained and ignored, never fatal")
    void unknownKeyIsIgnoredNotFatal() {
        // PROJ never errors on an unrecognised +key: init.cpp retains every token
        // and recognition is pull-based, so anything nobody asks for keeps
        // used == 0 and has no effect.
        // `approx` and `algo` used to be on the outside of the allow-list and are
        // asserted here no longer: core now registers and dispatches both
        // (Proj4Keyword:315,323 - tmerc/utm's escape back to the Evenden/Snyder series),
        // so this pin was inverted rather than relaxed. The invariant under test is
        // unchanged: a key core does not know is retained in the paralist, never marked
        // used, and filtered out before Proj4Parser can throw on it.
        GieProjArgs a = GieProjArgs.parse("proj=merc ellps=GRS80 approx algo=auto totally_made_up=7");

        assertEquals(5, a.size(), "unknown tokens are retained verbatim");
        assertEquals("auto", a.peek("algo"));
        assertEquals(Arrays.asList("totally_made_up"), a.keysOutsideAllowList());

        // ...and the unknown one is filtered out before Proj4Parser sees it, so its
        // allow-list cannot throw.
        List<String> forParser = Arrays.asList(a.toProj4Args());
        assertEquals(Arrays.asList("+proj=merc", "+ellps=GRS80", "+approx", "+algo=auto"),
                forParser);
    }

    @Test
    @DisplayName("builtins.gie's literal `unknown_keyword` is tokenised, retained and ignored")
    void builtinsUnknownKeyword() {
        // builtins.gie's non-strict <gie> block (line 23) contains a bare
        // `unknown_keyword` line and expects it ignored. At the command level that
        // is the lexer's business; here we assert the parameter level behaves the
        // same way, because the two must not disagree: the token survives, it is
        // never used, and nothing throws.
        GieProjArgs a = GieProjArgs.parse("proj=aea ellps=GRS80 lat_1=0 lat_2=2 unknown_keyword");

        assertEquals(5, a.size());
        GieToken t = a.find("unknown_keyword");
        assertNotNull(t, "the token must be retained, as PROJ's paralist retains it");
        assertFalse(t.hasValue());
        assertFalse(t.used());
        assertEquals(Arrays.asList("unknown_keyword"), a.keysOutsideAllowList());
        assertFalse(Arrays.asList(a.toProj4Args()).contains("+unknown_keyword"));
    }

    @Test
    @DisplayName("prList is null only when every token was looked up")
    void prListNullWhenAllUsed() {
        GieProjArgs a = GieProjArgs.parse("proj=merc ellps=GRS80");
        assertNotNull(a.prList(), "nothing looked up yet, so both tokens are unused");
        a.value("proj");
        a.value("ellps");
        assertNull(a.prList());
    }

    @Test
    @DisplayName("prList lists exactly the tokens nobody looked up")
    void prListReportsUnused() {
        GieProjArgs a = GieProjArgs.parse("proj=merc ellps=GRS80 nonsense=1");
        a.value("proj");
        a.value("ellps");
        assertEquals(Arrays.asList("nonsense"), a.unusedKeys());
        String report = a.prList();
        assertNotNull(report);
        assertTrue(report.startsWith("#--- following specified but NOT used"), report);
        assertTrue(report.contains("+nonsense=1"), report);
    }

    @Test
    @DisplayName("peek does not mark, value and exists do")
    void markingSemantics() {
        GieProjArgs a = GieProjArgs.parse("proj=merc south");
        a.peek("proj");
        assertFalse(a.find("proj").used());
        a.value("proj");
        assertTrue(a.find("proj").used());
        assertFalse(a.find("south").used());
        assertTrue(a.exists("south"));
        assertTrue(a.find("south").used());
    }

    // ------------------------------------------ the implicit +ellps=GRS80

    @Test
    @DisplayName("PROJ appends +ellps=GRS80 when nothing else gives a shape")
    void implicitGrs80() {
        // Verified against the installed PROJ 9.8.1: `proj +proj=merc` on "2 1"
        // gives 222638.981586547 110579.965218250, identical to +ellps=GRS80 and
        // different from +ellps=WGS84.
        assertTrue(GieProjArgs.parse("proj=merc").impliesGrs80());
        GieProjArgs effective = GieProjArgs.parse("proj=merc").withImplicitDefaults();
        assertTrue(effective.implicitEllipsoidAppended());
        assertEquals("GRS80", effective.peek("ellps"));
    }

    @Test
    @DisplayName("the implicit ellipsoid is APPENDED, so a user token still shadows it")
    void implicitGrs80IsAppended() {
        GieProjArgs a = GieProjArgs.parse("proj=merc").withImplicitDefaults();
        // The appended token is last, so first-match-wins keeps a user value.
        GieProjArgs withUser = GieProjArgs.parse("proj=merc ellps=intl");
        assertFalse(withUser.impliesGrs80());
        assertEquals("intl", withUser.peek("ellps"));
        assertEquals("+proj=merc +ellps=GRS80", a.toString());
    }

    @Test
    @DisplayName("+no_defs, or any shape parameter, suppresses the implicit ellipsoid")
    void implicitGrs80Suppressed() {
        assertFalse(GieProjArgs.parse("proj=merc no_defs").impliesGrs80());
        String[] shapes = {"ellps=intl", "datum=WGS84", "a=6400000", "b=6300000",
                "rf=300", "f=0.003", "e=0.08", "es=0.006"};
        for (int i = 0; i < shapes.length; i++) {
            assertFalse(GieProjArgs.parse("proj=merc " + shapes[i]).impliesGrs80(),
                    shapes[i] + " must suppress the implicit +ellps=GRS80");
        }
    }

    @Test
    @DisplayName("+R does NOT suppress the implicit ellipsoid - it overrules it later")
    void radiusDoesNotSuppress() {
        // init.cpp's suppression list is datum ellps a b rf f e es. +R is absent
        // from it, so GRS80 is appended and then ell_set.cpp's +R branch
        // short-circuits it.
        assertTrue(GieProjArgs.parse("proj=merc R=6400000").impliesGrs80());
    }

    @Test
    @DisplayName("an empty or pipeline +proj suppresses the implicit ellipsoid")
    void emptyOrPipelineProjSuppresses() {
        assertFalse(GieProjArgs.parse("proj=").impliesGrs80(),
                "\"proj=\" is 5 characters, below init.cpp's strlen < 6 bar");
        assertFalse(GieProjArgs.parse("proj=pipeline step proj=merc").impliesGrs80());
        assertFalse(GieProjArgs.parse("ellps=GRS80").impliesGrs80(), "no +proj at all");
    }

    @Test
    @DisplayName("hasEllipsoidSize sees the five size-bearing keys")
    void hasEllipsoidSize() {
        assertTrue(GieProjArgs.parse("proj=merc ellps=intl").hasEllipsoidSize());
        assertTrue(GieProjArgs.parse("proj=merc datum=WGS84").hasEllipsoidSize());
        assertTrue(GieProjArgs.parse("proj=merc a=1").hasEllipsoidSize());
        assertTrue(GieProjArgs.parse("proj=merc b=1").hasEllipsoidSize());
        assertTrue(GieProjArgs.parse("proj=merc R=1").hasEllipsoidSize());
        assertFalse(GieProjArgs.parse("proj=merc rf=300").hasEllipsoidSize());
    }

    // ----------------------------------------------------------- edge cases

    @Test
    @DisplayName("null, empty and whitespace-only definitions tokenise to nothing")
    void degenerateInput() {
        assertTrue(GieProjArgs.parse(null).isEmpty());
        assertTrue(GieProjArgs.parse("").isEmpty());
        assertTrue(GieProjArgs.parse("   \t ").isEmpty());
    }

    @Test
    @DisplayName("a value containing '=' keeps everything after the first one")
    void valueMayContainEquals() {
        GieProjArgs a = GieProjArgs.parse("proj=horner fwd_c=1=2");
        assertEquals("1=2", a.peek("fwd_c"));
    }

    @Test
    @DisplayName("a trailing continuation backslash is dropped")
    void continuationBackslashDropped() {
        GieProjArgs a = GieProjArgs.parse("proj=pipeline \\ step proj=merc");
        assertEquals(Arrays.asList("proj", "step", "proj"), a.keys());
    }

    @Test
    @DisplayName("';' separates tokens, as pj_shrink treats it")
    void semicolonSeparates() {
        GieProjArgs a = GieProjArgs.parse("proj=merc;ellps=GRS80");
        assertEquals(Arrays.asList("proj", "ellps"), a.keys());
    }
}

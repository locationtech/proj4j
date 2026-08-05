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
package org.locationtech.proj4j.pipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * {@link ProjParams} against {@code 9.8.1:src/param.cpp} and {@code src/init.cpp}.
 *
 * <p>The three lookup rules tested here are not conveniences: every one of them
 * changes an answer somewhere in the GIGS corpus. First-match-wins is what lets a
 * pipeline-level {@code +towgs84=0,0,0} suppress the one an {@code +init=} expansion
 * declares; stop-at-{@code step} is the only thing scoping a step's parameters;
 * exact key matching is what stops {@code +a} being read out of {@code +axis=wsu}.
 */
public class ProjParamsTest {

    // ------------------------------------------------------------- tokenising

    @Test
    public void stripsPlusPrefixesAndSplitsOnWhitespaceAndSemicolons() {
        ProjParams p = ProjParams.parse("+proj=merc  +lat_ts=42;+ellps=krass");
        assertEquals(Arrays.asList("proj=merc", "lat_ts=42", "ellps=krass"), p.tokens());
    }

    @Test
    public void acceptsAStringWithNoPlusPrefixes() {
        // gigs/5112.gie and 5103.1.gie write their pipelines without '+'.
        ProjParams p = ProjParams.parse("proj=pipeline step init=epsg:4284 inv");
        assertEquals(Arrays.asList("proj=pipeline", "step", "init=epsg:4284", "inv"), p.tokens());
    }

    @Test
    public void stripsTheGieLineContinuationBackslash() {
        assertEquals(Arrays.asList("proj=pipeline", "step"),
                ProjParams.parse("+proj=pipeline\\ +step\\").tokens());
    }

    @Test
    public void nullAndBlankAreEmptyRatherThanAnError() {
        assertTrue(ProjParams.parse(null).isEmpty());
        assertTrue(ProjParams.parse("   ").isEmpty());
    }

    @Test
    public void aValuelessTokenIsPresentButHasNoValue() {
        ProjParams p = ProjParams.parse("+proj=utm +south +no_defs");
        assertTrue(p.has("south"));
        assertNull(p.value("south"));
    }

    // --------------------------------------------------------- first match wins

    @Test
    public void firstOccurrenceWinsBecauseExpansionsAreAppended() {
        // pj_param_exists walks front to back. proj4j's old HashMap kept the LAST
        // occurrence, which inverts the precedence of every +init= expansion.
        ProjParams p = ProjParams.parse("+towgs84=0,0,0 +proj=lcc +towgs84=-106.8686,52.2978,-103.7239");
        assertEquals("0,0,0", p.value("towgs84"));
    }

    @Test
    public void keyMatchingIsExactSoAneverMatchesAxis() {
        ProjParams p = ProjParams.parse("+proj=tmerc +axis=wsu");
        assertFalse("+a must not be read out of +axis", p.has("a"));
        assertEquals("wsu", p.value("axis"));
    }

    @Test
    public void keyMatchingRequiresAnEqualsOrEndOfToken() {
        ProjParams p = ProjParams.parse("+lat_ts=42");
        assertFalse(p.has("lat"));
        assertTrue(p.has("lat_ts"));
    }

    // ------------------------------------------------------- stop at the step

    @Test
    public void lookupStopsAtTheFirstStepToken() {
        // param.cpp:72-105. This single rule is what scopes a pipeline step.
        ProjParams p = ProjParams.parse("+proj=pipeline +step +proj=merc +ellps=krass");
        assertEquals("pipeline", p.value("proj"));
        assertFalse("+ellps lives in a step and must be invisible globally", p.has("ellps"));
    }

    @Test
    public void lookingForStepItselfDoesNotStopAtIt() {
        ProjParams p = ProjParams.parse("+proj=pipeline +step +proj=merc");
        assertTrue(p.has("step"));
        assertEquals(1, p.countExact("step"));
    }

    @Test
    public void splitOnStepPutsTheGlobalsFirst() {
        List<ProjParams> slices = ProjParams
                .parse("+proj=pipeline +towgs84=0,0,0 +step +init=epsg:4313 +inv +step +init=epsg:31370")
                .splitOnStep();
        assertEquals(3, slices.size());
        assertEquals(Arrays.asList("proj=pipeline", "towgs84=0,0,0"), slices.get(0).tokens());
        assertEquals(Arrays.asList("init=epsg:4313", "inv"), slices.get(1).tokens());
        assertEquals(Arrays.asList("init=epsg:31370"), slices.get(2).tokens());
    }

    @Test
    public void countExactIgnoresTokensThatMerelyStartWithTheText() {
        // pipeline.cpp:519 compares with strcmp, so "+inv=T" is not an "inv" token.
        ProjParams p = ProjParams.parse("+inv +inv=T +invalid");
        assertEquals(1, p.countExact("inv"));
    }

    // ------------------------------------------------------------ typed access

    @Test
    public void doubleValueFallsBackToTheDefaultAndRejectsGarbage() {
        ProjParams p = ProjParams.parse("+k_0=0.9996012717 +x_0=");
        assertEquals(0.9996012717, p.doubleValue("k_0", 1.0), 0.0);
        assertEquals(1.0, p.doubleValue("absent", 1.0), 0.0);
        assertEquals("an empty value falls back rather than throwing",
                7.0, p.doubleValue("x_0", 7.0), 0.0);
        try {
            ProjParams.parse("+k_0=nine").doubleValue("k_0", 1.0);
            fail("expected a rejection");
        } catch (PipelineDefinitionException e) {
            assertEquals(PipelineErrorCode.ILLEGAL_ARG_VALUE, e.code());
        }
    }

    @Test
    public void booleanValueFollowsPjParamTypeB() {
        // '' / T / t true; F / f false; anything else an error.
        assertTrue(ProjParams.parse("+over").booleanValue("over"));
        assertTrue(ProjParams.parse("+over=").booleanValue("over"));
        assertTrue(ProjParams.parse("+over=T").booleanValue("over"));
        assertTrue(ProjParams.parse("+over=t").booleanValue("over"));
        assertFalse(ProjParams.parse("+over=F").booleanValue("over"));
        assertFalse(ProjParams.parse("+over=f").booleanValue("over"));
        assertFalse("absent is false", ProjParams.parse("+proj=merc").booleanValue("over"));
        try {
            ProjParams.parse("+over=yes").booleanValue("over");
            fail("expected a rejection");
        } catch (PipelineDefinitionException e) {
            assertEquals(PipelineErrorCode.ILLEGAL_ARG_VALUE, e.code());
        }
    }

    // ------------------------------------------------------------------ append

    @Test
    public void appendPutsTheExpansionLastSoItIsShadowed() {
        ProjParams p = ProjParams.parse("+init=epsg:4275 +ellps=intl")
                .append(Arrays.asList("+proj=longlat", "+a=6378249.2", "+ellps=clrk80"));
        assertEquals("the user token still wins", "intl", p.value("ellps"));
        assertEquals("longlat", p.value("proj"));
    }

    @Test
    public void toProj4ArgsRestoresThePlusPrefixesInOrder() {
        assertArrayEqualsStrings(new String[] {"+proj=merc", "+lat_ts=42"},
                ProjParams.parse("proj=merc lat_ts=42").toProj4Args());
    }

    private static void assertArrayEqualsStrings(String[] expected, String[] actual) {
        assertEquals(Arrays.asList(expected), Arrays.asList(actual));
    }
}

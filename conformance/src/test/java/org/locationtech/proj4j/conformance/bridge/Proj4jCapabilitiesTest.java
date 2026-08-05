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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.proj4j.parser.Proj4Keyword;

class Proj4jCapabilitiesTest {

    /**
     * <b>The drift tripwire.</b> The bridge deliberately does not trust
     * {@code Proj4Keyword.supportedParameters()} to mean "proj4j acts on this" —
     * three keys in that list are read and then ignored on the single-projection
     * path. So it keeps its own three-way classification, and this test fails the
     * moment core's allow-list grows a key the bridge has no opinion about.
     *
     * <p>If you are reading this because the test failed: put the new key in
     * {@link Proj4jCapabilities#HONOURED} (proj4j applies it),
     * {@link Proj4jCapabilities#INERT} (no numeric effect) or
     * {@link Proj4jCapabilities#CONDITIONAL} (the value decides) and extend
     * {@link Proj4jCapabilities#conditionalFailure} if you chose the third. Do not
     * "fix" it by deleting the assertion: an unclassified key flows straight
     * through and produces a wrong number that looks like a pass.
     */
    @Test
    @DisplayName("every key in core's allow-list is classified by the bridge")
    void allowListIsFullyClassified() {
        List<String> unclassified = new ArrayList<String>();
        Iterator<?> it = Proj4Keyword.supportedParameters().iterator();
        while (it.hasNext()) {
            String key = (String) it.next();
            if (!Proj4jCapabilities.classified().contains(key)) {
                unclassified.add(key);
            }
        }
        Collections.sort(unclassified);
        assertEquals(Collections.<String>emptyList(), unclassified,
                "core's Proj4Keyword allow-list has grown keys the bridge does not classify. "
                        + "Add each to Proj4jCapabilities.HONOURED, INERT or CONDITIONAL - see "
                        + "this test's javadoc. Allow-list size is now "
                        + Proj4Keyword.supportedParameters().size() + ".");
    }

    @Test
    @DisplayName("the three sets are disjoint")
    void setsAreDisjoint() {
        for (String k : Proj4jCapabilities.HONOURED) {
            assertTrue(!Proj4jCapabilities.INERT.contains(k)
                    && !Proj4jCapabilities.CONDITIONAL.contains(k), k + " is in two sets");
        }
        for (String k : Proj4jCapabilities.INERT) {
            assertTrue(!Proj4jCapabilities.CONDITIONAL.contains(k), k + " is in two sets");
        }
    }

    @Test
    @DisplayName("the conditional rules encode PROJ's cs2cs_emulation_setup triggers")
    void conditionalRules() {
        // +axis: PROJ inserts an axisswap step only when the order is not "enu".
        assertNull(Proj4jCapabilities.conditionalFailure("axis", "enu", null));
        assertNotNull(Proj4jCapabilities.conditionalFailure("axis", "neu", null));

        // +pm: only Greenwich, by name or by angle, is a no-op.
        assertNull(Proj4jCapabilities.conditionalFailure("pm", "greenwich", null));
        assertNull(Proj4jCapabilities.conditionalFailure("pm", "0", null));
        assertNotNull(Proj4jCapabilities.conditionalFailure("pm", "ferro", null));

        // +towgs84: PROJ ignores an all-zero Helmert.
        assertNull(Proj4jCapabilities.conditionalFailure("towgs84", "0,0,0", null));
        assertNull(Proj4jCapabilities.conditionalFailure("towgs84", "0,0,0,0,0,0,0", null));
        assertNotNull(Proj4jCapabilities.conditionalFailure("towgs84", "1,2,3", null));

        // +datum: inert only when its own defn is a null towgs84.
        assertNull(Proj4jCapabilities.conditionalFailure("datum", "WGS84", null));
        assertNull(Proj4jCapabilities.conditionalFailure("datum", "NAD83", null));
        assertNotNull(Proj4jCapabilities.conditionalFailure("datum", "NAD27", null));
        assertNotNull(Proj4jCapabilities.conditionalFailure("datum", "potsdam", null));

        // +nadgrids: always a hgridshift step upstream.
        assertNotNull(Proj4jCapabilities.conditionalFailure("nadgrids", "@conus", null));
    }

    @Test
    @DisplayName("isNullHelmert accepts only well-formed all-zero lists")
    void nullHelmert() {
        assertTrue(Proj4jCapabilities.isNullHelmert("0,0,0"));
        assertTrue(Proj4jCapabilities.isNullHelmert("0.0,0,-0.0"));
        assertTrue(!Proj4jCapabilities.isNullHelmert("0,0"));
        assertTrue(!Proj4jCapabilities.isNullHelmert("0,0,1"));
        assertTrue(!Proj4jCapabilities.isNullHelmert(null));
    }

    @Test
    @DisplayName("the value-grammar check catches every form PROJ has and proj4j lacks")
    void valueGrammar() {
        // Agreement: plain decimals and DMS that Angle.parse also understands.
        assertNull(Proj4jCapabilities.valueGrammarFailure("lat_0", "45"));
        assertNull(Proj4jCapabilities.valueGrammarFailure("lat_0", "-45.5"));
        assertNull(Proj4jCapabilities.valueGrammarFailure("x_0", "500000"));
        assertNull(Proj4jCapabilities.valueGrammarFailure("x_0", "1e5"));
        assertNull(Proj4jCapabilities.valueGrammarFailure("k_0", "0.9996"));
        assertNull(Proj4jCapabilities.valueGrammarFailure("lat_0", null));

        // Divergence: a num/den ratio, which only +to_meter accepts.
        assertNotNull(Proj4jCapabilities.valueGrammarFailure("to_meter", "1/0.3048"));

        // strtod stops at the first invalid character; Double.parseDouble throws.
        assertNotNull(Proj4jCapabilities.valueGrammarFailure("x_0", "500000junk"));
    }

    @Test
    @DisplayName("the 1e-12 bar absorbs the toRadians ULP difference but nothing larger")
    void closeEnoughBar() {
        double deg = 55.0;
        // PROJ computes deg * M_PI / 180; the JDK computes deg / 180 * PI. Up to 1
        // ULP apart, and 1 ULP of a radian is about 1.4e-9 m on the ellipsoid -
        // exactly the threshold of the corpus's 16 nanometre-tolerance rows.
        assertTrue(Proj4jCapabilities.closeEnough(deg * Math.PI / 180.0, Math.toRadians(deg)));
        // A radian-versus-degree confusion is 57x, nowhere near the bar.
        assertTrue(!Proj4jCapabilities.closeEnough(1.0, Math.toRadians(1.0)));
        assertTrue(Proj4jCapabilities.closeEnough(Double.NaN, Double.NaN));
        assertTrue(!Proj4jCapabilities.closeEnough(0.0, 1e-6));
    }
}

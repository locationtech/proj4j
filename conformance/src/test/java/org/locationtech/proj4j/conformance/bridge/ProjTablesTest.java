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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.proj4j.Registry;

class ProjTablesTest {

    /**
     * Table sizes, pinned. A vendored table that has quietly lost an entry is worse
     * than no table: it would silently reclassify a legal definition as
     * {@link GieFailureKind#INVALID_DEFINITION} and make an {@code expect failure}
     * row pass for the wrong reason.
     */
    @Test
    @DisplayName("the vendored PROJ 9.8.1 tables have their measured sizes")
    void tableSizes() {
        assertEquals(186, ProjTables.OPERATORS.size(),
                "PROJ_HEAD entries in 9.8.1:src/pj_list.h");
        assertEquals(46, ProjTables.ELLIPSOIDS.size(), "9.8.1:src/ellps.cpp");
        assertEquals(10, ProjTables.DATUMS.size(), "9.8.1:src/datums.cpp");
        // 21, not 24: rad/deg/grad appear in units.cpp but +units resolves against
        // pj_list_linear_units() only, so all three are "Invalid value for units" upstream.
        // Verified against PROJ 9.8.1 rather than inferred from the table they sit in.
        assertEquals(21, ProjTables.UNITS.size(), "9.8.1:src/units.cpp, linear units only");
        assertEquals(14, ProjTables.PRIME_MERIDIANS.size(), "9.8.1:src/datums.cpp pj_prime_meridians");
    }

    @Test
    @DisplayName("spot checks against upstream, including the names only 9.x has")
    void spotChecks() {
        assertTrue(ProjTables.isProjOperator("pipeline"));
        assertTrue(ProjTables.isProjOperator("spilhaus"), "added in 9.x");
        assertTrue(ProjTables.isProjOperator("s2"), "added in 9.x");
        assertTrue(ProjTables.isProjOperator("mod_krovak"));
        assertTrue(ProjTables.isProjOperator("etmerc"), "live operator with no doc page");
        assertTrue(ProjTables.isProjOperator("geocent"), "live operator with no doc page");
        assertFalse(ProjTables.isProjOperator("not_a_projection"));
        assertFalse(ProjTables.isProjOperator(null));

        assertTrue(ProjTables.ELLIPSOIDS.contains("GSK2011"), "9.x addition");
        assertTrue(ProjTables.ELLIPSOIDS.contains("danish"));
        assertFalse(ProjTables.ELLIPSOIDS.contains("NAD83"),
                "NAD83 is a proj4j-only ellipsoid alias; PROJ has it only as a datum");
        assertTrue(ProjTables.DATUMS.contains("NAD83"));
    }

    @Test
    @DisplayName("proj4j resolves 93 of PROJ's 186 operators")
    void registryCoverage() {
        Registry registry = new Registry();
        List<String> resolvable = new ArrayList<String>();
        List<String> missing = new ArrayList<String>();
        for (String name : ProjTables.OPERATORS) {
            if (Proj4jCapabilities.resolvable(registry, name)) {
                resolvable.add(name);
            } else {
                missing.add(name);
            }
        }
        System.out.println("PROJ 9.8.1 operators resolvable by proj4j's Registry: "
                + resolvable.size() + " of " + ProjTables.OPERATORS.size());
        System.out.println("  missing (" + missing.size() + "): " + missing);
        assertTrue(resolvable.size() >= 80,
                "only " + resolvable.size() + " operators resolve; expected around 90");
        // Was: all three of alsk/apian/bacon were registered against the ABSTRACT Projection
        // class, whose project() is the identity - so they returned lon/lat as though it were
        // projected metres. alsk alone was 16 silently-wrong builtins.gie assertions.
        //
        // apian and bacon are now real implementations (bacon.cpp, shared base), so they must
        // resolve. Asserting they do NOT would re-pin the defect.
        assertTrue(resolvable.contains("apian"), "apian is implemented now");
        assertTrue(resolvable.contains("bacon"), "bacon is implemented now");
        // alsk resolves too, as of the mod_ster port: Registry now binds it to
        // AlaskaModifiedStereographicProjection. It spent one stage failing closed with an
        // honest "registered but not implemented" message - which was already an improvement
        // on the original false "Unknown projection: alsk" - and is now implemented.
        assertTrue(resolvable.contains("alsk"), "alsk is implemented now (mod_ster ported)");
    }

    @Test
    @DisplayName("Units.findUnits' metres fallback is detected, not trusted")
    void unitResolutionDetectsTheMetresFallback() {
        assertTrue(ProjTables.proj4jResolvesUnit("m"));
        assertTrue(ProjTables.proj4jResolvesUnit("km"));
        assertTrue(ProjTables.proj4jResolvesUnit("us-ft"));
        // findUnits returns METRES rather than null for anything it does not know,
        // so absence has to be detected by asking whether the unit it handed back
        // actually answers to the name requested.
        assertFalse(ProjTables.proj4jResolvesUnit("furlong"));
        assertFalse(ProjTables.proj4jResolvesUnit(null));
        // Was: assertFalse(..."ind-yd"..., "proj4j has no Indian units"). It has them now -
        // fath, ch, link, us-ch, ind-yd, ind-ft and ind-ch were all added to core's Units
        // table, where every one of them had previously resolved silently to METRES.
        assertTrue(ProjTables.proj4jResolvesUnit("ind-yd"), "the Indian units were added");

        // The gap has closed: proj4j now resolves all 21 linear unit ids +units accepts.
        // This assertion is inverted from what it was. It used to say "if proj4j now
        // resolves every PROJ unit, delete the +units gap check in
        // Proj4jGieOperationFactory.classifyTokens rather than leaving dead code" - i.e. it
        // was written to fire exactly once, on the day the gap closed, and be replaced.
        // That day is today.
        //
        // The gap check is retained deliberately rather than deleted, because UNITS is a
        // fact about PROJ and could grow: PROJ adding a unit must show up as a classified
        // gap, not as a silent metres fallback. Units.findUnits() returns METRES for ANY
        // unrecognised name, so the fallback is still live and still dangerous - which is
        // why proj4jResolvesUnit asks whether the returned unit answers to the requested
        // name rather than trusting a non-null result.
        List<String> unresolvable = new ArrayList<String>();
        for (String id : ProjTables.UNITS) {
            if (!ProjTables.proj4jResolvesUnit(id)) {
                unresolvable.add(id);
            }
        }
        assertEquals(Collections.<String>emptyList(), unresolvable,
                "proj4j should resolve all " + ProjTables.UNITS.size() + " linear unit ids "
                        + "+units accepts. If PROJ has gained one, add it to core's Units "
                        + "table - do NOT let it fall back to METRES.");
    }

    @Test
    @DisplayName("every ellipsoid PROJ has is either in proj4j's Registry or knowably absent")
    void ellipsoidCoverage() {
        Registry registry = new Registry();
        List<String> missing = new ArrayList<String>();
        for (String name : ProjTables.ELLIPSOIDS) {
            if (registry.getEllipsoid(name) == null) {
                missing.add(name);
            }
        }
        System.out.println("PROJ ellipsoids absent from proj4j's Registry ("
                + missing.size() + "): " + missing);
        // This is a report, not a bar: the point is that the gap is enumerable, so
        // an unknown +ellps can be classified rather than guessed at.
        assertTrue(missing.size() < ProjTables.ELLIPSOIDS.size());
    }
}

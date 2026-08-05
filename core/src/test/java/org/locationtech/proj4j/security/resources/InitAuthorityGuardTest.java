/*******************************************************************************
 * Copyright 2026 Proj4J contributors
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
 *******************************************************************************/
package org.locationtech.proj4j.security.resources;

import java.io.IOException;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.io.Proj4FileReader;
import org.locationtech.proj4j.resource.ResourceNames;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code +init=<authority>:<code>} builds a classpath resource name out of untrusted input, and was
 * the one path into the resource layer with no validation on it at all.
 *
 * <h2>The asymmetry this closes</h2>
 *
 * <p>Every grid path is guarded: {@code Grid.resolveAndLoad} applies {@link ResourceNames} before the
 * chain, and both resolvers apply it again. The init path did
 * {@code "proj4/nad/" + authority.toLowerCase(Locale.ROOT)} and handed the result to
 * {@code ClassLoader.getResourceAsStream}, so {@code +init=../../foo:bar} arrived as
 * {@code "proj4/nad/../../foo"} unexamined. Whether a particular classloader collapses that is a
 * property of the deployment; the point of the resource layer is that it should not have to be.
 *
 * <h2>Both directions, because a guard that refuses everything is worse than none</h2>
 *
 * <p>The dictionary authorities that must keep working are enumerated by name and asserted to
 * resolve a real CRS — {@code epsg}, {@code esri}, {@code world}, {@code nad83}, {@code nad27} — in
 * every case spelling of each, because the authority is lowercased with {@code Locale.ROOT} before
 * the rule sees it and a guard applied to the wrong string would fail on {@code EPSG} and pass on
 * {@code epsg}.
 */
public class InitAuthorityGuardTest {

    private final CRSFactory factory = new CRSFactory();
    private final Proj4FileReader reader = new Proj4FileReader();

    /** The name from the brief, plus every other shape the rule is meant to catch. */
    @Test
    public void aTraversingAuthorityIsRefusedByRuleAndByName() {
        String[][] hostile = {
                {"../../foo", "DOT_SEGMENT"},
                {"..", "DOT_SEGMENT"},
                {".", "DOT_SEGMENT"},
                {"tests/../../etc/passwd", "DOT_SEGMENT"},
                {"/etc/passwd", "ABSOLUTE"},
                {"\\windows\\win.ini", "ABSOLUTE"},
                {"a\\b", "BACKSLASH"},
                {"epsg ", "WHITESPACE"},
                {"ep sg", "WHITESPACE"},
                {"%2e%2e%2fepsg", "PERCENT"},
                {"C:/windows/win.ini", "COLON"},
                {"a//b", "EMPTY_SEGMENT"},
                {"epsg/", "EMPTY_SEGMENT"},
                {"", "EMPTY"},
        };
        for (String[] each : hostile) {
            String authority = each[0];
            String expectedRule = each[1];

            // The rule really is broken by this name -- otherwise the loop asserts nothing.
            ResourceNames.Rule rule = ResourceNames.violation(authority.toLowerCase(java.util.Locale.ROOT));
            assertNotNull("'" + authority + "' must break the rule for this case to be evidence",
                    rule);
            assertEquals("'" + authority + "'", expectedRule, rule.toString());

            try {
                reader.readParametersFromFile(authority, "4326");
                fail("+init=" + authority + ":4326 must be refused");
            } catch (IllegalStateException expected) {
                assertTrue("the refusal must name the authority: " + expected.getMessage(),
                        expected.getMessage().contains("Refusing +init= authority"));
                assertTrue("the refusal must name the clause that fired (" + expectedRule + "): "
                        + expected.getMessage(), expected.getMessage().contains(expectedRule));
            } catch (IOException e) {
                fail("+init=" + authority + ":4326 must be refused by name, not by I/O: " + e);
            }
        }
    }

    /** {@code CRSFactory.createFromName} is the public door onto the same code. */
    @Test
    public void theSameNameIsRefusedThroughTheFactory() {
        try {
            factory.createFromName("../../foo:bar");
            fail("createFromName(\"../../foo:bar\") must not reach the classloader");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("Refusing +init= authority"));
        }
    }

    /**
     * The accept side. Every authority the shipped dictionaries actually use, in three spellings
     * each, must still resolve a real CRS.
     */
    @Test
    public void everyShippedAuthorityStillResolves() throws IOException {
        // Codes read out of the shipped files, not guessed: the first entry of each
        // epsg/src/main/resources/proj4/nad/<authority>. epsg has 5,755 entries, esri 2,954,
        // world 47, nad83 123 and nad27 134.
        String[][] cases = {
                {"epsg", "4326"},
                {"EPSG", "4326"},
                {"EpSg", "4326"},
                {"esri", "2000"},
                {"ESRI", "2000"},
                {"world", "CH1903"},
                {"WORLD", "CH1903"},
                {"nad83", "101"},
                {"NAD83", "101"},
                {"nad27", "101"},
                {"NAD27", "101"},
        };
        for (String[] each : cases) {
            String[] params = reader.readParametersFromFile(each[0], each[1]);
            assertNotNull("+init=" + each[0] + ":" + each[1] + " must still resolve", params);
            assertTrue("+init=" + each[0] + ":" + each[1] + " resolved to an empty definition",
                    params.length > 0);
        }
    }

    /** And end to end, through the factory, so the guard is proven not to break CRS construction. */
    @Test
    public void aLegitimateCrsStillBuilds() {
        CoordinateReferenceSystem crs = factory.createFromName("EPSG:4326");
        assertNotNull(crs);
        assertNotNull(crs.getProjection());
        CoordinateReferenceSystem esri = factory.createFromName("ESRI:37001");
        assertNotNull(esri);
    }

    /**
     * An authority that is a <em>legal</em> name and simply is not installed must still fail the way
     * it always did — "unable to access", not "refusing". Without this the hostile list above could
     * be passing because nothing resolves.
     */
    @Test
    public void alegalButAbsentAuthorityFailsForTheOtherReason() throws IOException {
        assertNotNull("this name must be legal, or the case proves nothing",
                ResourceNames.violation("no-such-authority") == null ? "legal" : null);
        try {
            reader.readParametersFromFile("no-such-authority", "4326");
            fail("there is no such init file");
        } catch (IllegalStateException expected) {
            assertTrue("a legal-but-absent authority must NOT be reported as a rule violation: "
                    + expected.getMessage(),
                    expected.getMessage().contains("Unable to access CRS file"));
            assertTrue(expected.getMessage(),
                    !expected.getMessage().contains("Refusing +init= authority"));
        }
    }
}

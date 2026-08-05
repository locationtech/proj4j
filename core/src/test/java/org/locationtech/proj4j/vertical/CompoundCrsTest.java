/*
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
 */
package org.locationtech.proj4j.vertical;

import java.util.List;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.UnknownAuthorityCodeException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code EPSG:4326+5773} — the compound form, and an honest account of what the shipped
 * dictionary does and does not contain.
 *
 * <h2>What is actually missing, verified rather than taken on report</h2>
 *
 * <p>{@code epsg/src/main/resources/proj4/nad/epsg} has <b>5,755</b> entries. Grepping it for
 * each code in turn:
 *
 * <table>
 * <caption>presence in the shipped dictionary</caption>
 * <tr><th>code</th><th>what it is</th><th>present?</th></tr>
 * <tr><td>{@code 4326}</td><td>WGS 84, geographic 2D</td><td>yes</td></tr>
 * <tr><td>{@code 27700}</td><td>British National Grid</td><td>yes</td></tr>
 * <tr><td>{@code 4936}, {@code 4978}</td><td>ETRS89 / WGS 84 geocentric</td><td>yes</td></tr>
 * <tr><td><b>{@code 4979}</b></td><td><b>WGS 84, geographic 3D</b></td><td><b>no</b></td></tr>
 * <tr><td>{@code 4937}</td><td>ETRS89, geographic 3D</td><td>no</td></tr>
 * <tr><td>{@code 5773}</td><td>EGM96 height</td><td>no</td></tr>
 * <tr><td>{@code 3855}</td><td>EGM2008 height</td><td>no</td></tr>
 * <tr><td>{@code 5714}, {@code 5715}</td><td>MSL height / depth</td><td>no</td></tr>
 * <tr><td>{@code 5703}</td><td>NAVD88 height</td><td>no</td></tr>
 * </table>
 *
 * <p>So the report was right that {@code EPSG:4979} is absent — and the reason is
 * structural, not an omission: {@code projinfo EPSG:4979 -o PROJ} is
 * {@code +proj=longlat +datum=WGS84 +no_defs}, <em>byte-identical</em> to {@code EPSG:4326}'s
 * entry. A PROJ.4 {@code +init=} dictionary has no way to say "and this one has a third
 * axis", so adding a {@code <4979>} line would create an entry indistinguishable from
 * {@code <4326>}. Vertical CRSs are absent for the same reason, one level worse: a
 * proj-string cannot denote a standalone vertical CRS at all.
 *
 * <h2>What a compound CRS reduces to</h2>
 *
 * <p>{@code projinfo EPSG:4326+5773 -o PROJ}, verbatim:
 *
 * <pre>
 * +proj=longlat +datum=WGS84 +geoidgrids=us_nga_egm96_15.tif +geoid_crs=WGS84 +vunits=m
 *   +no_defs +type=crs</pre>
 *
 * <p>which is {@code <4326>}'s own entry plus three tokens. That is why compound support and
 * {@code +geoidgrids} support are the same feature, and why {@link CompoundCrs} composes
 * rather than reimplements.
 */
public class CompoundCrsTest {

    private final CRSFactory crsFactory = new CRSFactory();

    // ------------------------------------------------------------------ the name syntax

    @Test
    public void theCompoundFormIsRecognised() {
        assertTrue(CRSFactory.isCompoundName("EPSG:4326+5773"));
        assertTrue(CRSFactory.isCompoundName("EPSG:4326+EPSG:5773"));
        assertTrue(CRSFactory.isCompoundName("  EPSG:27700+5701  "));
    }

    /**
     * The one thing that makes this non-trivial: {@code '+'} starts every proj-string
     * parameter. A proj-string misread as a compound name would be truncated at its first
     * parameter and quietly become a different CRS, so the test is deliberately conservative.
     */
    @Test
    public void aProjStringIsNeverMistakenForACompoundName() {
        assertFalse(CRSFactory.isCompoundName("+proj=longlat +datum=WGS84"));
        assertFalse(CRSFactory.isCompoundName("+proj=merc"));
        assertFalse(CRSFactory.isCompoundName("proj=longlat datum=WGS84"));
        assertFalse(CRSFactory.isCompoundName("EPSG:4326"));
        assertFalse(CRSFactory.isCompoundName("EPSG:4326+"));
        assertFalse(CRSFactory.isCompoundName("+5773"));
        assertFalse(CRSFactory.isCompoundName("EPSG:4326+5773+5714"));
        assertFalse(CRSFactory.isCompoundName(null));
        assertFalse(CRSFactory.isCompoundName(""));
    }

    @Test
    public void theBareVerticalCodeInheritsTheHorizontalAuthority() {
        CompoundCrsName n = CompoundCrsName.parse("EPSG:4326+5773");
        assertEquals("EPSG:4326", n.horizontal());
        assertEquals("EPSG", n.verticalAuthority());
        assertEquals("5773", n.verticalCode());

        CompoundCrsName q = CompoundCrsName.parse("EPSG:4326+EPSG:5773");
        assertEquals("EPSG:5773", q.verticalIdentifier());

        // An unqualified horizontal code defaults to EPSG, exactly as createFromName does.
        assertEquals("EPSG", CompoundCrsName.parse("4326+5773").verticalAuthority());
    }

    @Test
    public void aNonCompoundNameIsRejectedBytheParser() {
        try {
            CompoundCrsName.parse("EPSG:4326");
            fail("EPSG:4326 is not a compound name");
        } catch (InvalidValueException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("EPSG:4326+5773"));
        }
    }

    // ------------------------------------------------------------------ composition

    /**
     * The composed proj-string, token for token against {@code projinfo}, minus its
     * {@code +type=crs} marker.
     *
     * <p>Token <em>order</em> differs from {@code projinfo}'s in one respect and deliberately
     * so: this appends the vertical tokens after everything the dictionary entry carries,
     * including its trailing {@code +no_defs}, because appending is what gives the horizontal
     * CRS's own tokens precedence under {@code pj_param}'s first-match-wins. {@code projinfo}
     * re-serialises from a parsed object and has no ordering constraint to honour.
     */
    @Test
    public void epsg4326Plus5773ComposesProjsOwnDefinition() {
        CompoundCrs c = crsFactory.createCompound("EPSG:4326+5773");

        assertEquals("EPSG:4326", c.getHorizontal().getName());
        assertEquals("EPSG:5773", c.getVertical().getIdentifier());
        assertEquals("EGM96 height", c.getVertical().getName());
        assertTrue("EGM96 is a real geoid model", c.appliesVerticalShift());

        assertTrue(c.toProjString(), c.toProjString().startsWith("+proj=longlat +datum=WGS84"));
        assertTrue("PROJ names the GeoTIFF: " + c.toProjString(),
                c.toProjString().contains("+geoidgrids=us_nga_egm96_15.tif"));
        assertTrue(c.toProjString().contains("+geoid_crs=WGS84"));
        assertTrue(c.toProjString().contains("+vunits=m"));

        // The executable form names the file this library can open. proj.db's
        // grid_alternatives row is
        //   WW15MGH.GRD | us_nga_egm96_15.tif | egm96_15.gtx | GTiff | geoid_like
        // so egm96_15.gtx is upstream's own legacy spelling of the same data, not a guess.
        assertTrue("the pipeline form names the GTX: " + c.pipelineDefinition(),
                c.pipelineDefinition().contains("+geoidgrids=egm96_15.gtx"));
        assertFalse(c.pipelineDefinition().contains(".tif"));
    }

    /** A projected horizontal half composes the same way. */
    @Test
    public void aProjectedHorizontalHalfComposesToo() {
        CompoundCrs c = crsFactory.createCompound("EPSG:27700+5701");
        assertTrue(c.toProjString(), c.toProjString().startsWith("+proj=tmerc"));
        assertTrue(c.toProjString().contains("+geoidgrids=uk_os_OSGM15_GB.tif"));
        assertEquals("ODN height", c.getVertical().getName());
    }

    /**
     * A vertical CRS with no geoid model composes to {@code +vunits=m} and nothing else, which
     * is exactly what PROJ emits for it — {@code projinfo EPSG:4326+5714 -o PROJ} is
     * {@code +proj=longlat +datum=WGS84 +vunits=m +no_defs +type=crs}, with no
     * {@code +geoidgrids} at all.
     *
     * <p>{@link CompoundCrs#appliesVerticalShift()} reports that, rather than letting the
     * caller discover it by getting their input height back.
     */
    @Test
    public void aModelFreeVerticalCrsSaysSo() {
        CompoundCrs c = crsFactory.createCompound("EPSG:4326+5714");
        assertFalse("MSL height has no realisation, so no correction is available",
                c.appliesVerticalShift());
        assertFalse(c.toProjString(), c.toProjString().contains("geoidgrids"));
        assertTrue(c.toProjString().contains("+vunits=m"));
    }

    /**
     * A down-positive axis is recorded even though a legacy proj-string cannot express it, so
     * a caller can refuse rather than be handed a height where a depth was asked for. PROJ's
     * own PROJ.4 export loses the distinction: {@code EPSG:4326+5715} and
     * {@code EPSG:4326+5714} serialise identically.
     */
    @Test
    public void aDepthIsFlaggedBecauseTheProjStringCannotCarryIt() {
        assertTrue(crsFactory.createCompound("EPSG:4326+5715").getVertical().isDepth());
        assertFalse(crsFactory.createCompound("EPSG:4326+5714").getVertical().isDepth());
        assertEquals("the two serialise identically, which is why isDepth() exists",
                crsFactory.createCompound("EPSG:4326+5714").toProjString(),
                crsFactory.createCompound("EPSG:4326+5715").toProjString());
    }

    // ------------------------------------------------------------------ the composed pipeline

    /**
     * The whole point: the composition is executable, and it produces the geoid height.
     *
     * <p>Run against the test fixture rather than the real {@code egm96_15.gtx}, which is 55 MB
     * and ships nowhere, by substituting the grid name. The arithmetic exercised is identical
     * and is pinned against {@code cct} in {@link GeoidGridsAutoStepTest}.
     */
    @Test
    public void theCompositionIsExecutable() {
        CompoundCrs c = crsFactory.createCompound("EPSG:4326+5773");
        String definition = c.pipelineDefinition()
                .replace("egm96_15.gtx", "egm96_15_downsampled.gtx");

        double[] out = new org.locationtech.proj4j.pipeline.PipelineFactory().create(definition)
                .forward(new double[] {Math.toRadians(12.5), Math.toRadians(55.5), 0.0, 0.0});

        assertEquals("longitude-first, unchanged: proj4j is AxisOrderPolicy.LEGACY",
                12.5, Math.toDegrees(out[0]), 1e-12);
        assertEquals(55.5, Math.toDegrees(out[1]), 1e-12);
        assertEquals("an ellipsoidal height of 0 is -36.394 m of EGM96 orthometric height",
                -36.394090697107, out[2], 1e-9);
    }

    // ------------------------------------------------------------------ what is missing

    /**
     * {@code EPSG:4979} is not in the shipped dictionary and this test says so out loud, with
     * the reason. If a later change adds it, this test fails and the reason has to be revisited
     * rather than the absence being rediscovered.
     */
    @Test
    public void epsg4979IsAbsentFromTheShippedDictionary() {
        try {
            crsFactory.createFromName("EPSG:4979");
            fail("EPSG:4979 resolved. It is absent from proj4/nad/epsg, and its PROJ.4 form "
                    + "(+proj=longlat +datum=WGS84 +no_defs) is byte-identical to EPSG:4326's - "
                    + "so if it now resolves, check what it resolves TO before celebrating.");
        } catch (UnknownAuthorityCodeException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("4979"));
        }
    }

    /** An unknown vertical code names itself and says where the data has to come from. */
    @Test
    public void anUnknownVerticalCodeIsNamedRatherThanGuessed() {
        try {
            crsFactory.createCompound("EPSG:4326+9999");
            fail("EPSG:9999 is not a vertical CRS this library knows");
        } catch (UnknownVerticalCrsException expected) {
            assertEquals("EPSG:9999", expected.getIdentifier());
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("contains no vertical CRS"));
            assertTrue("the message must list what IS available",
                    expected.getMessage().contains("EPSG:5773"));
        }
    }

    /**
     * {@code createFromName} must not silently answer a compound question with a 2D CRS. It
     * points at {@code createCompound} instead — and stays an
     * {@link UnknownAuthorityCodeException}, so every existing {@code catch} still fires.
     */
    @Test
    public void createFromNameRefusesACompoundNameAndSaysWhichMethodToUse() {
        try {
            crsFactory.createFromName("EPSG:4326+5773");
            fail("createFromName returns a 2D CoordinateReferenceSystem and must not drop the "
                    + "vertical half");
        } catch (UnknownAuthorityCodeException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("createCompound"));
        }
    }

    /** A plain unknown code keeps its old, plain message. */
    @Test
    public void aPlainUnknownCodeIsUnaffected() {
        try {
            crsFactory.createFromName("EPSG:999999");
            fail("EPSG:999999 does not exist");
        } catch (UnknownAuthorityCodeException expected) {
            assertFalse(expected.getMessage(), expected.getMessage().contains("createCompound"));
        }
    }

    // ------------------------------------------------------------------ the registry seam

    @Test
    public void theBuiltInTableIsSmallAndEnumerable() {
        List<String> known = VerticalCrsRegistry.knownCodes();
        assertTrue(known.toString(), known.contains("EPSG:5773"));
        assertTrue(known.toString(), known.contains("EPSG:3855"));
        assertTrue(known.toString(), known.contains("EPSG:5714"));
        assertNull("anything outside the table is null, not a fabricated entry",
                VerticalCrsRegistry.find("EPSG", "9999"));
    }

    @Test
    public void aRegisteredVerticalCrsWinsAndCanBeCleared() {
        try {
            VerticalCrsRegistry.register(new VerticalCrs("EPSG", "9999", "Test height",
                    "test.tif", "test.gtx", "WGS84", "ft", false));
            CompoundCrs c = crsFactory.createCompound("EPSG:4326+9999");
            assertEquals("Test height", c.getVertical().getName());
            assertTrue(c.pipelineDefinition(), c.pipelineDefinition().contains("+vunits=ft"));
            assertTrue(c.pipelineDefinition().contains("+geoidgrids=test.gtx"));
        } finally {
            VerticalCrsRegistry.clearRegistered();
        }
        assertNull(VerticalCrsRegistry.find("EPSG", "9999"));
    }

    @Test
    public void aRegistrationWithoutAnIdentityIsRefused() {
        try {
            VerticalCrsRegistry.register(VerticalCrsRegistry.ellipsoidalHeight());
            fail("an anonymous VerticalCrs has no key to register under");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("authority"));
        }
    }
}

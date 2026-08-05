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
package org.locationtech.proj4j.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.UnsupportedParameterException;
import org.locationtech.proj4j.io.Proj4FileReader;
import org.locationtech.proj4j.parser.Proj4Parser.ParseMode;
import org.locationtech.proj4j.units.Units;

/**
 * {@link ParseMode#STRICT} reached through the {@link Proj} facade.
 *
 * <p>Before this, neither of the library's two documented defences against a hostile CRS string
 * protected production. {@code ProjDefinitionValidator} is test-scope, and the
 * {@code Proj4Keyword} allow-list runs only in {@code ParseMode.STRICT}, which
 * {@link org.locationtech.proj4j.CRSFactory} never selects and the facade could not select at all.
 * A consumer parsing untrusted PROJ.4 strings therefore got {@code PROJ_COMPATIBLE}
 * unconditionally: unrecognised keys retained and ignored, unknown {@code +units} silently metres.
 *
 * <h2>What is measured here</h2>
 *
 * <p>Every claim on {@link ProjContext#parseMode()} has a test on this page, and each is an
 * <b>A/B against one frozen input</b> rather than a one-sided pass:
 *
 * <ul>
 * <li>the same definition accepted under the default and refused under {@code STRICT}, the
 *     offending key named &mdash; {@link #theSameDefinitionParsesByDefaultAndIsRefusedByName()};</li>
 * <li>an unresolvable {@code +units} silently becoming metres by default and refused under
 *     {@code STRICT} &mdash; {@link #strictAlsoGatesAnUnresolvableUnitsName()};</li>
 * <li><b>duplicate-key precedence is NOT gated</b>, measured rather than assumed &mdash;
 *     {@link #duplicateKeyPrecedenceIsFirstWinsInBothModes()};</li>
 * <li>the legacy 1.x API not re-routed by any context &mdash;
 *     {@link #theLegacyApiIsNotReRouted()};</li>
 * <li>the default facade path still bit-identical to {@code CRSFactory}, coordinate for coordinate
 *     &mdash; {@link #theDefaultPathIsBitIdenticalToCrsFactory()}.</li>
 * </ul>
 *
 * <h2>The positive control</h2>
 *
 * <p>A mode that refused everything would pass every hostile test above and break the library, so
 * the load-bearing test here is {@link #strictAcceptsTheWholeShippedDictionaryBarOne()}: all
 * <b>9,013</b> definitions in the shipped {@code proj4/nad} dictionaries, parsed twice, once per
 * mode. Measured at the time of writing: 8,969 accepted by both modes, 43 refused by both for
 * reasons that have nothing to do with the mode (unimplemented {@code +proj=} names), <b>exactly
 * one</b> accepted by default and refused by {@code STRICT}, and zero the other way round. That one
 * is {@code world:malay}, which carries {@code +rot_conv} &mdash; a token that is in PROJ 9.8.1's
 * own {@code data/world} and is read nowhere in its {@code src/}, so PROJ retains and ignores it
 * too. It is enumerated by name below, not tolerated by a threshold.
 *
 * <p>That census would be worthless if its classifier could not tell the three outcomes apart, so
 * {@link #theCensusClassifierDiscriminates()} drives it with one hand-made definition per outcome
 * and requires the right label for each.
 */
public class StrictParseModeTest {

    /** The five dictionaries in {@code proj4j-epsg}, which is on core's test classpath. */
    private static final String[] DICTIONARIES = {"epsg", "esri", "world", "nad83", "nad27"};

    /** Pinned: the population these dictionaries have had throughout this work. */
    private static final int DICTIONARY_DEFINITIONS = 9013;

    private static final ProjContext STRICT =
            ProjContext.builder().parseMode(ParseMode.STRICT).build();

    // ------------------------------------------------------------------ the option itself

    @Test
    public void theDefaultIsProjCompatibleAndStaysThatWay() {
        assertEquals("the conformance runner and every existing caller depend on this",
                ParseMode.PROJ_COMPATIBLE, ProjContext.DEFAULT.parseMode());
        assertEquals(ParseMode.PROJ_COMPATIBLE, ProjContext.builder().build().parseMode());
        assertEquals(ParseMode.PROJ_COMPATIBLE, Proj.defaultContext().parseMode());
        assertSame("null means leave the built-in default, as every other setter here does",
                ProjContext.DEFAULT, ProjContext.builder().parseMode(null).build());
        assertSame(ProjContext.DEFAULT,
                ProjContext.DEFAULT.withParseMode(ParseMode.PROJ_COMPATIBLE));
    }

    @Test
    public void theOptionRoundTripsThroughTheBuilderAndTheWither() {
        assertEquals(ParseMode.STRICT, STRICT.parseMode());
        assertEquals(ParseMode.STRICT,
                ProjContext.DEFAULT.withParseMode(ParseMode.STRICT).parseMode());
        assertEquals(STRICT, STRICT.toBuilder().build());
        assertFalse("a context differing only in parse mode must not equal the default",
                ProjContext.DEFAULT.equals(STRICT));
        assertEquals("the original must be untouched", ParseMode.PROJ_COMPATIBLE,
                ProjContext.DEFAULT.parseMode());
        assertSame("PROJ_COMPATIBLE plus otherwise-default values is the canonical instance",
                ProjContext.DEFAULT, STRICT.withParseMode(ParseMode.PROJ_COMPATIBLE));
    }

    /**
     * {@code describe()} is the one line a job logs to say what could make two executors disagree,
     * so a policy missing from it is a policy nobody will know was set.
     */
    @Test
    public void describeAndToStringStateTheParseMode() {
        String lax = ProjContext.DEFAULT.describe();
        assertTrue(lax, lax.contains("parseMode           = PROJ_COMPATIBLE"));
        assertTrue("must say what the default tolerates: " + lax,
                lax.contains("retained and ignored"));

        String strict = STRICT.describe();
        assertTrue(strict, strict.contains("parseMode           = STRICT"));
        assertTrue("must not overstate the scope: " + strict,
                strict.contains("not to authority codes, WKT, PROJJSON or the 1.x CRSFactory"));

        assertTrue(STRICT.toString(), STRICT.toString().contains("parseMode=STRICT"));
        assertTrue(ProjContext.DEFAULT.toString(),
                ProjContext.DEFAULT.toString().contains("parseMode=PROJ_COMPATIBLE"));
    }

    // ------------------------------------------------------- the two behaviours STRICT changes

    /**
     * The headline: one definition, two modes, and the refusal names the key.
     */
    @Test
    public void theSameDefinitionParsesByDefaultAndIsRefusedByName() {
        String definition = "+proj=merc +ellps=GRS80 +lon_0=0 +unknown_keyword=1";

        Crs lax = Proj.createCrs(definition);
        assertNotNull("the default must stay PROJ-compatible: an unrecognised key is retained "
                + "and ignored, which is what builtins.gie relies on", lax);
        assertEquals("merc", lax.asLegacy().getProjection().getName());

        try {
            Proj.createCrs(definition, STRICT);
            fail("STRICT must refuse a key outside Proj4Keyword.supportedParameters()");
        } catch (UnsupportedParameterException expected) {
            assertTrue("the refusal must name the offending key, or the caller cannot fix it: "
                    + expected.getMessage(), expected.getMessage().contains("unknown_keyword"));
            assertTrue("must be in-family", expected instanceof Proj4jException);
            assertTrue("must be a CrsCreationException, which is what createCrs documents",
                    expected instanceof org.locationtech.proj4j.CrsCreationException);
        }
    }

    /**
     * {@code Units.findUnits} substitutes {@code METRES} for any name it does not know and never
     * returns null, so by default {@code +units=bananas} is a working CRS in metres. That is a
     * plausible wrong answer, which is the failure mode this library exists to remove.
     */
    @Test
    public void strictAlsoGatesAnUnresolvableUnitsName() {
        String definition = "+proj=merc +ellps=GRS80 +units=bananas";

        assertSame("by default an unknown +units silently falls back to metres", Units.METRES,
                Proj.createCrs(definition).asLegacy().getProjection().getUnits());

        try {
            Proj.createCrs(definition, STRICT);
            fail("STRICT must refuse a +units name this library cannot resolve");
        } catch (InvalidValueException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("bananas"));
            assertTrue("must be in-family", expected instanceof Proj4jException);
        }

        // ...and must still resolve the ones it does know, including a non-metre one.
        assertSame(Units.US_FEET, Proj.createCrs("+proj=merc +ellps=GRS80 +units=us-ft", STRICT)
                .asLegacy().getProjection().getUnits());
        assertSame(Units.METRES, Proj.createCrs("+proj=merc +ellps=GRS80 +units=m", STRICT)
                .asLegacy().getProjection().getUnits());
    }

    /**
     * <b>Measured, because the plausible guess is wrong.</b> A duplicated key is resolved
     * first-occurrence-wins by {@code Proj4Parser.createParameterMap}, which is PROJ's rule
     * ({@code pj_param_exists} walks the list front-to-back). That code is outside the
     * {@code mode == STRICT} branch, so {@code STRICT} neither changes the winner nor reports the
     * duplicate. This test exists so the javadoc's claim to that effect is checked rather than
     * asserted.
     */
    @Test
    public void duplicateKeyPrecedenceIsFirstWinsInBothModes() {
        String definition = "+proj=merc +ellps=GRS80 +lon_0=11 +lon_0=22";

        double lax = Proj.createCrs(definition).asLegacy()
                .getProjection().getProjectionLongitudeDegrees();
        double strict = Proj.createCrs(definition, STRICT).asLegacy()
                .getProjection().getProjectionLongitudeDegrees();

        assertEquals("first occurrence wins, as in PROJ", 11.0, lax, 0.0);
        assertEquals("STRICT must not change which occurrence wins", 11.0, strict, 0.0);

        // And the duplicate is not itself an error in either mode.
        assertNotNull(Proj.createCrs(definition, STRICT));
    }

    /**
     * The small, readable half of the positive control: real definitions of several shapes, all of
     * which must survive {@code STRICT}. The large half is
     * {@link #strictAcceptsTheWholeShippedDictionaryBarOne()}.
     */
    @Test
    public void fullyValidDefinitionsParseUnderStrict() {
        String[] valid = {
                "+proj=merc +ellps=GRS80 +units=m +no_defs",
                "+proj=utm +zone=33 +ellps=WGS84 +datum=WGS84 +units=m +no_defs",
                "+proj=lcc +lat_1=49 +lat_2=77 +lat_0=49 +lon_0=-95 +x_0=0 +y_0=0 "
                        + "+ellps=GRS80 +units=m +no_defs",
                "+proj=omerc +lat_0=10 +lonc=20 +alpha=30 +k_0=1 +ellps=WGS84 +no_uoff +no_off",
                "+proj=longlat +datum=NAD83 +no_defs",
                "+proj=aea +lat_1=50 +lat_2=58.5 +lat_0=45 +lon_0=-126 +x_0=1000000 +y_0=0 "
                        + "+ellps=GRS80 +units=m",
                "+proj=stere +lat_0=90 +lat_ts=70 +lon_0=-45 +k_0=1 +x_0=0 +y_0=0 +ellps=WGS84 "
                        + "+towgs84=0,0,0,0,0,0,0 +units=m +no_defs",
                "+proj=tmerc +lat_0=0 +lon_0=9 +k=0.9996 +x_0=500000 +y_0=0 +ellps=GRS80 "
                        + "+approx +units=m",
                "+proj=ob_tran +o_proj=moll +o_lon_p=40 +o_lat_p=50 +lon_0=60 +ellps=WGS84",
                "+proj=peirce_q +lon_0=25 +shape=square +ellps=WGS84",
        };
        for (String definition : valid) {
            assertNotNull(definition, Proj.createCrs(definition, STRICT));
        }
    }

    // ---------------------------------------------------------- reachability through the facade

    /**
     * Every facade entry point that takes a context must honour it, or the option is decorative on
     * whichever one a caller happens to use.
     */
    @Test
    public void strictIsReachableThroughEveryEntryPointThatTakesAContext() {
        String hostile = "+proj=merc +ellps=GRS80 +unknown_keyword=1";
        String benign = "+proj=longlat +ellps=GRS80 +no_defs";

        refused(hostile, "Proj.createCrs(String, ProjContext)");

        try {
            Proj.createCrsToCrs(benign, hostile, STRICT);
            fail("createCrsToCrs must apply the context's parse mode to the target too");
        } catch (UnsupportedParameterException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("unknown_keyword"));
        }
        try {
            Proj.createCrsToCrs(hostile, benign, STRICT);
            fail("createCrsToCrs must apply the context's parse mode to the source");
        } catch (UnsupportedParameterException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("unknown_keyword"));
        }

        Crs lax = Proj.createCrs(hostile);
        try {
            lax.withContext(STRICT);
            fail("Crs.withContext re-parses the original definition, so it must apply the "
                    + "new context's parse mode");
        } catch (UnsupportedParameterException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("unknown_keyword"));
        }

        // ...and the benign pair must still build an operation under STRICT.
        assertNotNull(Proj.createCrsToCrs(benign, "+proj=merc +ellps=GRS80 +units=m", STRICT));
    }

    /**
     * The three notations {@code STRICT} deliberately does not cover. This is a scope test, not an
     * aspiration: the javadoc says these are untouched, so they are pinned untouched.
     */
    @Test
    public void strictDoesNotReachAuthorityCodesWktOrProjJson() {
        // An authority code resolves to a definition this library ships, and one of those 9,013
        // carries +rot_conv. Gating this path would refuse a CRS that is correct.
        assertNotNull("world:malay must keep resolving under STRICT",
                Proj.createCrs("world:malay", STRICT));
        assertNotNull(Proj.createCrs("EPSG:4326", STRICT));

        // WKT and PROJJSON are different grammars with their own readers; the allow-list is a list
        // of PROJ.4 keys. The WKT below is deliberately one this library writes itself.
        String wkt = "GEOGCS[\"WGS 84\",DATUM[\"WGS_1984\",SPHEROID[\"WGS 84\",6378137,"
                + "298.257223563]],PRIMEM[\"Greenwich\",0],UNIT[\"degree\",0.0174532925199433]]";
        assertNotNull(Proj.createCrsFromWkt(wkt, STRICT));
    }

    // ------------------------------------------------------------------- the legacy API is frozen

    /**
     * {@code CRSFactory} and {@code CoordinateTransformFactory} are not re-routed by any context.
     * Re-routing them would make fifteen-year-old GeoTools, GeoServer and geomesa code start
     * throwing on definitions it has always accepted.
     */
    @Test
    public void theLegacyApiIsNotReRouted() {
        String hostile = "+proj=merc +ellps=GRS80 +unknown_keyword=1";

        CRSFactory factory = new CRSFactory();
        CoordinateReferenceSystem viaFactory = factory.createFromParameters(null, hostile);
        assertNotNull("CRSFactory must keep retaining and ignoring an unrecognised key",
                viaFactory);
        assertEquals("merc", viaFactory.getProjection().getName());
        assertArrayEquals("the parameter list must be kept verbatim, unknown key included",
                hostile.split(" "), viaFactory.getParameters());

        assertNotNull("createFromName must keep resolving the one definition STRICT would refuse",
                factory.createFromName("world:malay"));

        CoordinateTransform t = new CoordinateTransformFactory().createTransform(
                factory.createFromParameters(null, "+proj=longlat +ellps=GRS80"), viaFactory);
        ProjCoordinate out = t.transform(new ProjCoordinate(12, 56), new ProjCoordinate());
        assertTrue("the legacy transform must still produce a finite coordinate",
                !Double.isNaN(out.x) && !Double.isNaN(out.y));
    }

    /**
     * {@link Proj} no longer goes through {@code CRSFactory} for a PROJ.4 string &mdash; it builds
     * a {@code Proj4Parser} with the context's mode. Under the default mode that must be the same
     * two statements {@code CRSFactory.createFromParameters} runs, so this compares the two paths
     * bit for bit: the retained parameter list, and a forward transform of a probe point with
     * {@code assertEquals(.., 0.0)} rather than a tolerance.
     */
    @Test
    public void theDefaultPathIsBitIdenticalToCrsFactory() {
        String[] definitions = {
                "+proj=merc +ellps=GRS80 +units=m +no_defs",
                "+proj=utm +zone=33 +ellps=WGS84 +datum=WGS84 +units=m",
                "+proj=lcc +lat_1=49 +lat_2=77 +lat_0=49 +lon_0=-95 +ellps=GRS80 +units=m",
                "+proj=tmerc +lat_0=0 +lon_0=9 +k=0.9996 +x_0=500000 +ellps=GRS80 +units=m",
                "+proj=stere +lat_0=90 +lat_ts=70 +lon_0=-45 +ellps=WGS84 +units=m",
                "+proj=merc +ellps=GRS80 +unknown_keyword=1",
                "+proj=merc +ellps=GRS80 +units=bananas",
                "+proj=aea +lat_1=50 +lat_2=58.5 +lat_0=45 +lon_0=-126 +ellps=GRS80 +units=us-ft",
        };
        CRSFactory factory = new CRSFactory();
        CoordinateTransformFactory transforms = new CoordinateTransformFactory();
        CoordinateReferenceSystem wgs84 =
                factory.createFromParameters(null, "+proj=longlat +ellps=WGS84 +datum=WGS84");

        for (String definition : definitions) {
            CoordinateReferenceSystem legacy = factory.createFromParameters(null, definition);
            CoordinateReferenceSystem facade = Proj.createCrs(definition).asLegacy();

            assertArrayEquals(definition, legacy.getParameters(), facade.getParameters());
            assertEquals(definition, legacy.getProjection().getName(),
                    facade.getProjection().getName());

            ProjCoordinate viaLegacy = transforms.createTransform(wgs84, legacy)
                    .transform(new ProjCoordinate(12.5, 56.25), new ProjCoordinate());
            ProjCoordinate viaFacade = transforms.createTransform(wgs84, facade)
                    .transform(new ProjCoordinate(12.5, 56.25), new ProjCoordinate());
            assertEquals(definition + " easting", viaLegacy.x, viaFacade.x, 0.0);
            assertEquals(definition + " northing", viaLegacy.y, viaFacade.y, 0.0);
        }
    }

    // ------------------------------------------------------------------------- the census

    /** How one definition behaves under the two modes. */
    private enum Outcome { BOTH_OK, BOTH_REFUSED, DEFAULT_OK_STRICT_REFUSED, DEFAULT_REFUSED_STRICT_OK }

    /**
     * <b>The control that makes every other test on this page mean something.</b> A parse mode that
     * refused everything would satisfy each hostile assertion above and destroy the library, so
     * {@code STRICT} is driven over every definition the project ships and required to accept
     * essentially all of them.
     *
     * <p>Measured when this landed: 9,013 definitions, <b>8,969 accepted by both modes</b>, 43
     * refused by both for reasons unrelated to the mode (a {@code +proj=} name this library has not
     * implemented), <b>1</b> accepted by default and refused by {@code STRICT}, 0 the other way.
     *
     * <p>The 8,969/43 split is asserted as a floor and a sum rather than pinned exactly, because
     * implementing a missing projection legitimately moves a definition from the second bucket to
     * the first and must not fail this test. The bucket that would indicate a defect &mdash;
     * {@code DEFAULT_OK_STRICT_REFUSED} &mdash; is <b>enumerated by name</b>, never counted.
     *
     * <p>Runtime is dominated by {@link Proj4FileReader}, not by parsing: 0.28 s with
     * {@code InitFileCache} in place, 21 s against the pre-cache reader that re-scans the whole
     * dictionary per lookup. 18,026 parses account for none of that.
     */
    @Test
    public void strictAcceptsTheWholeShippedDictionaryBarOne() {
        List<String> names = allDictionaryDefinitions();
        assertEquals("the shipped dictionary population has moved; if that is intended, re-pin it "
                + "here deliberately", DICTIONARY_DEFINITIONS, names.size());

        Proj4FileReader reader = new Proj4FileReader();
        int bothOk = 0;
        int bothRefused = 0;
        TreeSet<String> strictOnlyRefusals = new TreeSet<String>();
        TreeSet<String> impossible = new TreeSet<String>();

        for (String name : names) {
            String[] params = reader.getParameters(name);
            assertNotNull(name + " must resolve", params);
            String definition = join(params);
            switch (classify(definition)) {
                case BOTH_OK: bothOk++; break;
                case BOTH_REFUSED: bothRefused++; break;
                case DEFAULT_OK_STRICT_REFUSED:
                    strictOnlyRefusals.add(name + " (" + strictRefusalKey(definition) + ")");
                    break;
                default: impossible.add(name); break;
            }
        }

        assertEquals("STRICT only ADDS checks, so nothing can be refused by the default and "
                + "accepted by STRICT", new TreeSet<String>(), impossible);
        assertEquals("the one definition in the shipped dictionaries that STRICT refuses, "
                        + "enumerated rather than counted",
                new TreeSet<String>(Arrays.asList("world:malay (rot_conv)")), strictOnlyRefusals);
        assertEquals("every definition must land in exactly one bucket",
                DICTIONARY_DEFINITIONS, bothOk + bothRefused + strictOnlyRefusals.size());
        assertTrue("STRICT must accept the overwhelming majority of real definitions -- a mode "
                        + "that refuses everything passes every hostile test and breaks the "
                        + "library. Accepted by both modes: " + bothOk,
                bothOk >= 8900);
    }

    /**
     * The census above is only evidence if its classifier can produce all three of the outcomes it
     * looks for. One hand-made definition per outcome, and the right label required for each.
     */
    @Test
    public void theCensusClassifierDiscriminates() {
        assertEquals("a valid definition", Outcome.BOTH_OK,
                classify("+proj=merc +ellps=GRS80 +units=m"));
        assertEquals("an unknown key is exactly what the census looks for",
                Outcome.DEFAULT_OK_STRICT_REFUSED,
                classify("+proj=merc +ellps=GRS80 +unknown_keyword=1"));
        assertEquals("+rot_conv is the real one, reproduced standalone",
                Outcome.DEFAULT_OK_STRICT_REFUSED,
                classify("+proj=omerc +a=6377295.66402 +rf=300.8017 +alpha=30 +no_uoff "
                        + "+rot_conv +lonc=102 +lat_0=4 +k_0=0.99984"));
        assertEquals("a failure that has nothing to do with the mode", Outcome.BOTH_REFUSED,
                classify("+proj=no_such_projection_name +ellps=GRS80"));

        assertEquals("and the key the census reports must be the offending one",
                "rot_conv", strictRefusalKey("+proj=omerc +a=6377295.66402 +rf=300.8017 "
                        + "+alpha=30 +no_uoff +rot_conv +lonc=102 +lat_0=4 +k_0=0.99984"));
    }

    // --------------------------------------------------------------------------------- helpers

    private static Outcome classify(String definition) {
        boolean laxOk = parses(definition, null);
        boolean strictOk = parses(definition, STRICT);
        if (laxOk && strictOk) {
            return Outcome.BOTH_OK;
        }
        if (!laxOk && !strictOk) {
            return Outcome.BOTH_REFUSED;
        }
        return laxOk ? Outcome.DEFAULT_OK_STRICT_REFUSED : Outcome.DEFAULT_REFUSED_STRICT_OK;
    }

    private static boolean parses(String definition, ProjContext context) {
        try {
            Proj.createCrs(definition, context);
            return true;
        } catch (Proj4jException refused) {
            return false;
        } catch (RuntimeException refused) {
            return false;
        }
    }

    /** The key {@code STRICT} refuses {@code definition} for, or null if it does not refuse it. */
    private static String strictRefusalKey(String definition) {
        try {
            Proj.createCrs(definition, STRICT);
            return null;
        } catch (UnsupportedParameterException refused) {
            // "<key> parameter is not supported"
            String message = refused.getMessage();
            int space = message.indexOf(' ');
            return space < 0 ? message : message.substring(0, space);
        }
    }

    private static void refused(String definition, String what) {
        try {
            Proj.createCrs(definition, STRICT);
            fail(what + " must apply the context's parse mode");
        } catch (UnsupportedParameterException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("unknown_keyword"));
        }
    }

    private static String join(String[] params) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(params[i]);
        }
        return sb.toString();
    }

    /**
     * Every {@code authority:code} in the shipped dictionaries, read straight off the classpath
     * resources. Only the {@code <code>} headers are needed here; the parameters themselves come
     * from {@link Proj4FileReader}, which is the production reader and handles the comment and
     * continuation rules this loop deliberately does not.
     */
    private static List<String> allDictionaryDefinitions() {
        List<String> out = new ArrayList<String>();
        for (String dictionary : DICTIONARIES) {
            InputStream in = StrictParseModeTest.class.getClassLoader()
                    .getResourceAsStream("proj4/nad/" + dictionary);
            assertNotNull("proj4j-epsg must be on the test classpath: proj4/nad/" + dictionary, in);
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String trimmed = line.trim();
                        if (trimmed.indexOf('<') != 0) {
                            continue;
                        }
                        int close = trimmed.indexOf('>');
                        if (close > 1) {
                            out.add(dictionary + ":" + trimmed.substring(1, close));
                        }
                    }
                } finally {
                    reader.close();
                }
            } catch (IOException e) {
                throw new AssertionError("cannot read proj4/nad/" + dictionary, e);
            }
        }
        return out;
    }

    private static void assertArrayEquals(String message, String[] expected, String[] actual) {
        org.junit.Assert.assertEquals(message + "\nexpected " + Arrays.toString(expected)
                + "\nactual   " + Arrays.toString(actual), Arrays.toString(expected),
                Arrays.toString(actual));
    }
}

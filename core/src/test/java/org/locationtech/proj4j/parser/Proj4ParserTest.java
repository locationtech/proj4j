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
 */
package org.locationtech.proj4j.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.UnsupportedParameterException;
import org.locationtech.proj4j.datum.Datum;
import org.locationtech.proj4j.units.Units;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Parameter-list semantics: duplicate-key precedence, angular value syntax,
 * strictness, and the requirement that parsing never mutate a shared
 * {@link Datum}.
 */
public class Proj4ParserTest {

    private final CRSFactory crsFactory = new CRSFactory();

    private CoordinateReferenceSystem crs(String def) {
        return crsFactory.createFromParameters("test", def);
    }

    private static CoordinateReferenceSystem parse(Proj4Parser parser, String def) {
        return parser.parse("test", def.trim().split("\\s+"));
    }

    // ------------------------------------------------------------------
    // Duplicate keys: the FIRST occurrence wins
    // ------------------------------------------------------------------

    /**
     * {@code pj_param_exists} walks the parameter list front-to-back and
     * returns the first match; {@code +init=}/{@code +datum=} expansions are
     * appended so user tokens shadow them. A {@code HashMap} kept the last.
     */
    @Test
    public void firstOccurrenceOfADuplicatedKeyWins() {
        assertEquals(6400000.0,
                crs("+proj=merc +a=6400000 +a=1 +rf=297").getDatum().getEllipsoid().getEquatorRadius(), 0.0);

        double rf297 = crs("+proj=merc +a=6400000 +rf=297").getDatum().getEllipsoid().getEccentricitySquared();
        assertEquals(rf297,
                crs("+proj=merc +a=6400000 +rf=297 +rf=500").getDatum().getEllipsoid().getEccentricitySquared(),
                0.0);

        assertEquals(9.0,
                crs("+proj=tmerc +ellps=GRS80 +lon_0=9 +lon_0=15").getProjection().getProjectionLongitudeDegrees(),
                0.0);

        assertEquals("merc",
                crs("+proj=merc +proj=utm +zone=32 +ellps=GRS80").getProjection().getName());
    }

    /** A second occurrence must not even be validated, since it is never read. */
    @Test
    public void aShadowedDuplicateIsNotValidated() {
        assertNotNull(crs("+proj=merc +a=6400000 +a=-1 +rf=297"));
    }

    // ------------------------------------------------------------------
    // Angular value syntax on every angle parameter
    // ------------------------------------------------------------------

    /**
     * {@code +alpha}, {@code +lonc} and {@code +gamma} used bare
     * {@code Double.parseDouble}, so a DMS value threw.
     */
    @Test
    public void dmsIsAcceptedOnAlphaLoncAndGamma() {
        CoordinateReferenceSystem c = crs("+proj=omerc +a=6377298.556 +rf=300.8017 +lat_0=4"
                + " +lonc=115 +alpha=53d18'56.9537 +gamma=53d7'48.3685 +k_0=0.99984");
        assertEquals(53.31582047222222, c.getProjection().getAlpha() * ProjectionMath.RTD, 1e-12);
        assertEquals(115.0, c.getProjection().getLonC() * ProjectionMath.RTD, 1e-12);
    }

    /** A trailing cardinal is a sign, on every angle. */
    @Test
    public void cardinalSuffixIsAcceptedOnAngles() {
        // the accessor round-trips through radians, hence the 1e-12 slack
        assertEquals(-60.0,
                crs("+proj=pconic +ellps=GRS80 +lat_1=20n +lat_2=60n +lon_0=60W")
                        .getProjection().getProjectionLongitudeDegrees(), 1e-12);
        assertEquals(12.5,
                crs("+proj=tmerc +ellps=GRS80 +lat_0=12d30'N").getProjection().getProjectionLatitudeDegrees(),
                1e-12);
    }

    /** PROJ's {@code r}/{@code R} radian suffix, previously rejected outright. */
    @Test
    public void radianSuffixIsAcceptedOnAngles() {
        assertEquals(0.5 * ProjectionMath.RTD,
                crs("+proj=merc +ellps=GRS80 +lon_0=0.5r").getProjection().getProjectionLongitudeDegrees(),
                1e-12);
        assertEquals(-0.25 * ProjectionMath.RTD,
                crs("+proj=tmerc +ellps=GRS80 +lat_0=-0.25R").getProjection().getProjectionLatitudeDegrees(),
                1e-12);
    }

    /** {@code +pm} used bare {@code Double.parseDouble} too. */
    @Test
    public void dmsIsAcceptedOnPrimeMeridian() {
        assertEquals("+pm=74d04'51.3\"W must equal the named bogota meridian",
                crs("+proj=merc +ellps=GRS80 +pm=bogota").getProjection().getPrimeMeridian(),
                crs("+proj=merc +ellps=GRS80 +pm=74d04'51.3\"W").getProjection().getPrimeMeridian());

        // Degree-minute without quotes, as in PROJ's own +pm=17d40W
        assertEquals(crs("+proj=merc +ellps=GRS80 +pm=ferro").getProjection().getPrimeMeridian(),
                crs("+proj=merc +ellps=GRS80 +pm=17d40W").getProjection().getPrimeMeridian());
    }

    /** A named meridian must still resolve by name, including names containing 'd'. */
    @Test
    public void namedPrimeMeridiansStillResolve() {
        assertEquals("madrid",
                crs("+proj=merc +ellps=GRS80 +pm=madrid").getProjection().getPrimeMeridian().getName());
        assertEquals("lisbon",
                crs("+proj=merc +ellps=GRS80 +pm=lisbon").getProjection().getPrimeMeridian().getName());
        assertEquals("greenwich",
                crs("+proj=merc +ellps=GRS80 +pm=greenwich").getProjection().getPrimeMeridian().getName());
    }

    // ------------------------------------------------------------------
    // Strictness
    // ------------------------------------------------------------------

    /**
     * PROJ never errors on an unrecognised key - {@code init.cpp} retains every
     * token and recognition is pull-based - and {@code builtins.gie} relies on
     * it. The 36-key allow-list is therefore opt-in.
     */
    @Test
    public void unknownKeysAreIgnoredByDefault() {
        assertNotNull(crs("+proj=merc +ellps=GRS80 +unknown_keyword=1"));
        assertNotNull(crs("+proj=merc +ellps=GRS80 +vunits=m +geoidgrids=foo.gtx"));
        assertEquals(Proj4Parser.ParseMode.PROJ_COMPATIBLE, new Proj4Parser(new Registry()).getParseMode());
    }

    @Test
    public void strictModeRejectsUnknownKeys() {
        Proj4Parser strict = new Proj4Parser(new Registry(), Proj4Parser.ParseMode.STRICT);
        try {
            parse(strict, "+proj=merc +ellps=GRS80 +unknown_keyword=1");
            fail("STRICT must reject an unknown key");
        } catch (UnsupportedParameterException expected) {
            assertTrue(expected.getMessage().contains("unknown_keyword"));
        }
        assertNotNull(parse(strict, "+proj=merc +ellps=GRS80 +units=m"));
    }

    /**
     * {@code Units.findUnits} returns METRES for anything it does not know and
     * never returns null, so the parser's own null guard was dead code.
     */
    @Test
    public void unknownUnitsSilentlyBecomeMetresInProjCompatibleMode() {
        assertSame(Units.METRES, crs("+proj=merc +ellps=GRS80 +units=bananas").getProjection().getUnits());
    }

    @Test
    public void strictModeRejectsUnknownUnits() {
        Proj4Parser strict = new Proj4Parser(new Registry(), Proj4Parser.ParseMode.STRICT);
        try {
            parse(strict, "+proj=merc +ellps=GRS80 +units=bananas");
            fail("STRICT must reject an unknown +units name");
        } catch (InvalidValueException expected) {
            assertTrue(expected.getMessage().contains("bananas"));
        }
        // ...but must not reject the ones it does know
        assertSame(Units.US_FEET, parse(strict, "+proj=merc +ellps=GRS80 +units=us-ft").getProjection().getUnits());
        assertSame(Units.METRES, parse(strict, "+proj=merc +ellps=GRS80 +units=m").getProjection().getUnits());
        assertSame(Units.FEET, parse(strict, "+proj=merc +ellps=GRS80 +units=ft").getProjection().getUnits());
    }

    // ------------------------------------------------------------------
    // Parsing must never mutate a shared Datum
    // ------------------------------------------------------------------

    /**
     * {@code Proj4Parser.parse} used to call {@code datum.setGrids(...)} on
     * whatever {@code DatumParameters.getDatum()} returned - which for
     * {@code +datum=NAD27} is the {@code Datum.NAD27} <i>singleton</i>, with a
     * null grid list. The first parse of EPSG:4267 therefore destroyed NAD27's
     * grids for the whole process, and did so unsynchronised.
     */
    @Test
    public void parsingEpsg4267TwiceLeavesTheNad27SingletonUntouched() {
        assertEquals("fixture: Datum.NAD27 must start out grid-shifted",
                Datum.TYPE_GRIDSHIFT, Datum.NAD27.getTransformType());

        CoordinateReferenceSystem first = crsFactory.createFromName("EPSG:4267");
        assertEquals(Datum.TYPE_GRIDSHIFT, Datum.NAD27.getTransformType());

        CoordinateReferenceSystem second = crsFactory.createFromName("EPSG:4267");
        assertEquals(Datum.TYPE_GRIDSHIFT, Datum.NAD27.getTransformType());

        assertSame("no +nadgrids given, so the singleton itself is fine to reuse",
                Datum.NAD27, first.getDatum());
        assertEquals(Datum.TYPE_GRIDSHIFT, first.getDatum().getTransformType());
        assertEquals(Datum.TYPE_GRIDSHIFT, second.getDatum().getTransformType());
    }

    @Test
    public void parsingDatumNad27DirectlyLeavesTheSingletonUntouched() {
        for (int i = 0; i < 3; i++) {
            crs("+proj=longlat +datum=NAD27 +no_defs");
            assertEquals(Datum.TYPE_GRIDSHIFT, Datum.NAD27.getTransformType());
        }
    }

    /** {@code +nadgrids} alongside {@code +datum=} must derive, not mutate. */
    @Test
    public void nadgridsWithANamedDatumDerivesANewDatum() {
        CoordinateReferenceSystem c = crs("+proj=longlat +datum=NAD27 +nadgrids=@ntv1_can.dat +no_defs");
        assertEquals(Datum.TYPE_GRIDSHIFT, c.getDatum().getTransformType());
        assertTrue("must not be the shared singleton", c.getDatum() != Datum.NAD27);
        assertEquals(Datum.TYPE_GRIDSHIFT, Datum.NAD27.getTransformType());
        assertEquals("the derived datum must keep NAD27's ellipsoid",
                Datum.NAD27.getEllipsoid(), c.getDatum().getEllipsoid());
    }

    /** The same must hold for the WGS84 singleton reached through the default path. */
    @Test
    public void nadgridsWithoutADatumDoesNotMutateWgs84() {
        assertEquals(Datum.TYPE_WGS84, Datum.WGS84.getTransformType());
        crs("+proj=longlat +nadgrids=@ntv1_can.dat +no_defs");
        assertEquals(Datum.TYPE_WGS84, Datum.WGS84.getTransformType());
    }

    // ------------------------------------------------------------------
    // Misc
    // ------------------------------------------------------------------

    /** {@code +k_0} is checked after {@code +k}, so {@code +k_0} wins. */
    @Test
    public void scaleFactorZeroOutranksK() {
        assertEquals(0.9996,
                crs("+proj=tmerc +ellps=GRS80 +k=1 +k_0=0.9996").getProjection().getScaleFactor(), 0.0);
    }

    /** A repeated parse of the same definition must be deterministic. */
    @Test
    public void repeatedParsesAgree() {
        String def = "+proj=tmerc +lat_0=0 +lon_0=9 +k=1 +x_0=500000 +y_0=0"
                + " +datum=potsdam +towgs84=598.1,73.7,418.2,0.202,0.045,-2.455,6.7 +units=m +no_defs";
        double[] first = crs(def).getDatum().getTransformToWGS84();
        for (int i = 0; i < 3; i++) {
            double[] again = crs(def).getDatum().getTransformToWGS84();
            for (int j = 0; j < first.length; j++) {
                assertEquals("towgs84[" + j + "] must not drift between parses",
                        first[j], again[j], 0.0);
            }
        }
    }
}

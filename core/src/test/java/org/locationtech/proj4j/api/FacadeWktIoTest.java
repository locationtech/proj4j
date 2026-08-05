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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CrsCreationException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.io.wkt.WktDialect;

/**
 * WKT and PROJJSON reach the caller <b>through the facade</b>, with no second artifact and no
 * dependency.
 *
 * <p>This is the capability that removes the need for an Apache SIS fallback, and with it the
 * duplicate {@code org.opengis.util.CodeList} hazard: two incompatible copies on one classpath make
 * SIS's WKT parser throw {@code NoSuchMethodError}, which is an {@code Error} rather than an
 * {@code Exception}, so it passes local tests and kills Spark executors. The readers and writers
 * already existed; this file asserts that a caller can get at them from
 * {@link Proj#createCrs(String)} without knowing which of four notations they hold.
 */
public class FacadeWktIoTest {

    private static final String WKT1_GDAL =
            "PROJCS[\"WGS 84 / UTM zone 33N\",GEOGCS[\"WGS 84\",DATUM[\"WGS_1984\","
                    + "SPHEROID[\"WGS 84\",6378137,298.257223563,AUTHORITY[\"EPSG\",\"7030\"]],"
                    + "AUTHORITY[\"EPSG\",\"6326\"]],PRIMEM[\"Greenwich\",0],"
                    + "UNIT[\"degree\",0.0174532925199433],AUTHORITY[\"EPSG\",\"4326\"]],"
                    + "PROJECTION[\"Transverse_Mercator\"],PARAMETER[\"latitude_of_origin\",0],"
                    + "PARAMETER[\"central_meridian\",15],PARAMETER[\"scale_factor\",0.9996],"
                    + "PARAMETER[\"false_easting\",500000],PARAMETER[\"false_northing\",0],"
                    + "UNIT[\"metre\",1],AXIS[\"Easting\",EAST],AXIS[\"Northing\",NORTH],"
                    + "AUTHORITY[\"EPSG\",\"32633\"]]";

    private static final String WKT1_ESRI =
            "PROJCS[\"WGS_1984_UTM_Zone_33N\",GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\","
                    + "SPHEROID[\"WGS_1984\",6378137.0,298.257223563]],PRIMEM[\"Greenwich\",0.0],"
                    + "UNIT[\"Degree\",0.0174532925199433]],PROJECTION[\"Transverse_Mercator\"],"
                    + "PARAMETER[\"False_Easting\",500000.0],PARAMETER[\"False_Northing\",0.0],"
                    + "PARAMETER[\"Central_Meridian\",15.0],PARAMETER[\"Scale_Factor\",0.9996],"
                    + "PARAMETER[\"Latitude_Of_Origin\",0.0],UNIT[\"Meter\",1.0]]";

    private static final String WKT2 =
            "GEOGCRS[\"WGS 84\",DATUM[\"World Geodetic System 1984\","
                    + "ELLIPSOID[\"WGS 84\",6378137,298.257223563,LENGTHUNIT[\"metre\",1]]],"
                    + "PRIMEM[\"Greenwich\",0,ANGLEUNIT[\"degree\",0.0174532925199433]],"
                    + "CS[ellipsoidal,2],AXIS[\"geodetic latitude (Lat)\",north,ORDER[1],"
                    + "ANGLEUNIT[\"degree\",0.0174532925199433]],"
                    + "AXIS[\"geodetic longitude (Lon)\",east,ORDER[2],"
                    + "ANGLEUNIT[\"degree\",0.0174532925199433]],"
                    + "USAGE[SCOPE[\"Horizontal component of 3D system.\"],"
                    + "AREA[\"World.\"],BBOX[-90,-180,90,180]],ID[\"EPSG\",4326]]";

    // ---------------------------------------------------------------------------- notation sniffing

    @Test
    public void allFourNotationsAreDetectedWithoutBeingDeclared() {
        assertEquals(Crs.Source.AUTHORITY_CODE, Proj.createCrs("EPSG:4326").source());
        assertEquals(Crs.Source.PROJ_STRING,
                Proj.createCrs("+proj=utm +zone=33 +datum=WGS84").source());
        assertEquals(Crs.Source.WKT, Proj.createCrs(WKT1_GDAL).source());
        assertEquals(Crs.Source.WKT, Proj.createCrs(WKT2).source());
        assertEquals(Crs.Source.PROJJSON,
                Proj.createCrs(Proj.createCrs("EPSG:4326").toProjJson()).source());
    }

    @Test
    public void theWktDialectIsDetectedAndReported() {
        assertEquals(WktDialect.WKT1_GDAL, Proj.createCrs(WKT1_GDAL).sourceDialect().get());
        assertEquals(WktDialect.WKT1_ESRI, Proj.createCrs(WKT1_ESRI).sourceDialect().get());
        assertEquals(WktDialect.WKT2_2019, Proj.createCrs(WKT2).sourceDialect().get());
        assertFalse("a CRS from a code did not come from WKT",
                Proj.createCrs("EPSG:4326").sourceDialect().isPresent());
    }

    /** Both WKT1 dialects describe the same projection, so both must produce the same numbers. */
    @Test
    public void bothWkt1DialectsProduceTheSameTransformation() {
        ProjCoordinate viaGdal = Proj.createCrsToCrs("EPSG:4326", WKT1_GDAL)
                .transform(new ProjCoordinate(15.0, 50.0));
        ProjCoordinate viaEsri = Proj.createCrsToCrs("EPSG:4326", WKT1_ESRI)
                .transform(new ProjCoordinate(15.0, 50.0));
        assertEquals(viaGdal.x, viaEsri.x, 1e-9);
        assertEquals(viaGdal.y, viaEsri.y, 1e-9);
    }

    /** And so does the authority code for the same CRS. */
    @Test
    public void wktAgreesWithTheAuthorityCodeForTheSameCrs() {
        ProjCoordinate viaWkt = Proj.createCrsToCrs("EPSG:4326", WKT1_GDAL)
                .transform(new ProjCoordinate(15.0, 50.0));
        ProjCoordinate viaCode = Proj.createCrsToCrs("EPSG:4326", "EPSG:32633")
                .transform(new ProjCoordinate(15.0, 50.0));
        assertEquals(viaCode.x, viaWkt.x, 1e-6);
        assertEquals(viaCode.y, viaWkt.y, 1e-6);
    }

    // ---------------------------------------------------------------------------------- writing

    /**
     * Every WKT2 revision round-trips. WKT1 is read but deliberately not written; a lossy writer
     * that did not say what it dropped would be worse than none, so it refuses by name.
     */
    @Test
    public void aCrsCanBeWrittenAsWkt2AndReadBack() {
        Crs crs = Proj.createCrs("EPSG:32633");
        for (WktDialect dialect : WktDialect.values()) {
            if (dialect.isWkt1()) {
                try {
                    crs.toWkt(dialect);
                    fail("WKT1 is not writable and must say so: " + dialect);
                } catch (IllegalArgumentException expected) {
                    assertTrue(expected.getMessage(),
                            expected.getMessage().contains("toProjString()"));
                }
                continue;
            }
            String wkt = crs.toWkt(dialect);
            assertNotNull(dialect.toString(), wkt);
            assertFalse(dialect.toString(), wkt.isEmpty());
            Crs reread = Proj.createCrs(wkt);
            ProjCoordinate a = Proj.createCrsToCrs("EPSG:4326", "EPSG:32633")
                    .transform(new ProjCoordinate(15.0, 50.0));
            ProjCoordinate b = Proj.createCrsToCrs(Proj.createCrs("EPSG:4326"), reread)
                    .transform(new ProjCoordinate(15.0, 50.0));
            assertEquals(dialect + " easting", a.x, b.x, 1e-6);
            assertEquals(dialect + " northing", a.y, b.y, 1e-6);
        }
        assertEquals("the no-argument form is WKT2:2019", crs.toWkt(WktDialect.WKT2_2019),
                crs.toWkt());
    }

    @Test
    public void aCrsCanBeWrittenAsProjJsonAndReadBack() {
        Crs crs = Proj.createCrs("EPSG:32633");
        String json = crs.toProjJson();
        assertTrue(json, json.trim().startsWith("{"));
        Crs reread = Proj.createCrs(json);
        assertEquals(Crs.Source.PROJJSON, reread.source());
        ProjCoordinate a = Proj.createCrsToCrs("EPSG:4326", "EPSG:32633")
                .transform(new ProjCoordinate(15.0, 50.0));
        ProjCoordinate b = Proj.createCrsToCrs(Proj.createCrs("EPSG:4326"), reread)
                .transform(new ProjCoordinate(15.0, 50.0));
        assertEquals(a.x, b.x, 1e-6);
        assertEquals(a.y, b.y, 1e-6);
    }

    @Test
    public void theProjStringRoundTripsIncludingAnOptionalGridMarker() {
        Crs crs = Proj.createCrs("+proj=longlat +ellps=clrk66 +nadgrids=@nosuchgrid.gsb");
        assertTrue("the @ must survive: dropping it turns \"optional\" into \"required\": "
                + crs.toProjString(), crs.toProjString().contains("@nosuchgrid.gsb"));
    }

    // ----------------------------------------------------------------- metadata a document carries

    /**
     * A bounding box a document declared <em>is</em> reportable, because it is a fact the caller
     * supplied rather than one this library invented. It is also flagged as not database-derived.
     */
    @Test
    public void aBoundingBoxDeclaredByADocumentBecomesAnAreaOfUse() {
        Crs crs = Proj.createCrs(WKT2);
        assertTrue("WKT2 BBOX[] must be surfaced: " + crs.describe(), crs.areaOfUse().isPresent());
        AreaOfUse area = crs.areaOfUse().get();
        assertEquals(-180.0, area.westLongitude(), 0.0);
        assertEquals(-90.0, area.southLatitude(), 0.0);
        assertEquals(180.0, area.eastLongitude(), 0.0);
        assertEquals(90.0, area.northLatitude(), 0.0);
        assertFalse("a document's claim is not an authority's", area.isDatabaseDerived());
        assertTrue(area.contains(-122.4, 37.8));
        assertFalse(area.crossesAntimeridian());
    }

    @Test
    public void anAntimeridianCrossingBoxIsPreservedNotNormalised() {
        AreaOfUse fiji = new AreaOfUse(176.8, -20.7, -178.4, -12.4, "Fiji", false);
        assertTrue(fiji.crossesAntimeridian());
        assertTrue(fiji.contains(179.0, -18.0));
        assertTrue(fiji.contains(-179.0, -18.0));
        assertFalse("normalising this away would turn a 5-degree box into a 355-degree one",
                fiji.contains(0.0, -18.0));
    }

    @Test
    public void anIdDeclaredByADocumentIsReportedAndOneThatIsNotIsNotInvented() {
        assertTrue(Proj.createCrs(WKT2).identifiers().contains("EPSG:4326"));
        assertTrue("a PROJ string equivalent to EPSG:4326 must NOT be attributed to EPSG",
                Proj.createCrs("+proj=longlat +datum=WGS84").identifiers().isEmpty());
    }

    // ---------------------------------------------------------------------------------- failures

    @Test
    public void malformedInputFailsWithACauseRatherThanAStackTraceFromTheParser() {
        try {
            Proj.createCrs("PROJCS[\"broken\"");
            fail("expected a CrsCreationException");
        } catch (CrsCreationException e) {
            assertEquals(ErrorCause.INVALID_CRS_SYNTAX, e.cause());
        }
        try {
            Proj.createCrs("{\"type\": \"nonsense\"}");
            fail("expected a CrsCreationException");
        } catch (CrsCreationException e) {
            assertEquals(ErrorCause.INVALID_CRS_SYNTAX, e.cause());
        }
    }

    @Test
    public void anUnknownCodeIsDistinguishedFromMalformedInput() {
        try {
            Proj.createCrs("EPSG:99999");
            fail("expected a CrsCreationException");
        } catch (CrsCreationException e) {
            assertEquals(ErrorCause.UNKNOWN_CRS, e.cause());
        }
    }

    @Test
    public void aCompoundNameIsRefusedByNameRatherThanSilentlyFlattened() {
        try {
            Proj.createCrs("EPSG:4326+5773");
            fail("expected a CrsCreationException");
        } catch (CrsCreationException e) {
            assertEquals(ErrorCause.CRS_TYPE_NOT_SUPPORTED, e.cause());
            assertTrue("the message must point at the method that does work: " + e.getMessage(),
                    e.getMessage().contains("createCompound"));
        }
    }

    @Test
    public void nullAndEmptyAreApiMisuseNotSyntaxErrors() {
        try {
            Proj.createCrs(null);
            fail("expected a CrsCreationException");
        } catch (CrsCreationException e) {
            assertEquals(ErrorCause.API_MISUSE, e.cause());
        }
        try {
            Proj.createCrs("   ");
            fail("expected a CrsCreationException");
        } catch (CrsCreationException e) {
            assertEquals(ErrorCause.INVALID_CRS_SYNTAX, e.cause());
        }
    }

    // -------------------------------------------------------------------------------- the bulk API

    /** The batch path is the consumer's main performance need, so the facade must surface it. */
    @Test
    public void theBulkApiIsReachableFromTheFacadeAndAgreesWithTheSinglePointPath() {
        CrsOperation op = Proj.createCrsToCrs("EPSG:4326", "EPSG:32633");
        double[] xy = {15.0, 50.0, 15.5, 50.5, 16.0, 51.0};
        byte[] status = new byte[3];
        assertEquals(0, op.bulk().transform2D(xy, 0, 3, 2, status));

        double[] expected = new double[6];
        double[] input = {15.0, 50.0, 15.5, 50.5, 16.0, 51.0};
        for (int i = 0; i < 3; i++) {
            ProjCoordinate out = op.transform(new ProjCoordinate(input[2 * i], input[2 * i + 1]));
            expected[2 * i] = out.x;
            expected[2 * i + 1] = out.y;
        }
        for (int i = 0; i < 6; i++) {
            assertEquals("bulk and single-point must be bit-identical at ordinate " + i,
                    Double.doubleToLongBits(expected[i]), Double.doubleToLongBits(xy[i]));
        }
    }

    /** The reverse direction is a CRS swap and is subject to the same checks. */
    @Test
    public void inverseIsSymmetricAndRoundTrips() {
        CrsOperation forward = Proj.createCrsToCrs("EPSG:4326", "EPSG:32633");
        CrsOperation back = forward.inverse();
        assertEquals(forward.source(), back.target());
        assertEquals(forward.target(), back.source());

        ProjCoordinate projected = forward.transform(new ProjCoordinate(15.0, 50.0));
        ProjCoordinate returned = back.transform(projected);
        assertEquals(15.0, returned.x, 1e-9);
        assertEquals(50.0, returned.y, 1e-9);
    }
}

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
package org.locationtech.proj4j.io.wkt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.BasicCoordinateTransform;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * The WKT1 reader, in the OGC/GDAL dialect.
 * <p>
 * Inputs are taken verbatim from PROJ 9.8.1's {@code test/unit/test_io.cpp} — the corpus case
 * numbers in the comments refer to the extraction of that file. Expectations are either upstream's
 * own, or, where upstream asserts a PROJ string containing operations proj4j spells differently
 * ({@code +k} versus {@code +k_0}, no {@code +type=crs}), the equivalent this library produces,
 * with the difference stated.
 */
public class Wkt1ReaderTest {

    static final String WGS84_GEOGCS =
            "GEOGCS[\"WGS 84\",DATUM[\"WGS_1984\",SPHEROID[\"WGS 84\",6378137,298.257223563,"
                    + "AUTHORITY[\"EPSG\",\"7030\"]],AUTHORITY[\"EPSG\",\"6326\"]],"
                    + "PRIMEM[\"Greenwich\",0,AUTHORITY[\"EPSG\",\"8901\"]],"
                    + "UNIT[\"degree\",0.0174532925199433,AUTHORITY[\"EPSG\",\"9122\"]],"
                    + "AUTHORITY[\"EPSG\",\"4326\"]]";

    private static String proj(String wkt) {
        return CrsDefinitions.toProjParameterString(new WktReader().readDefinition(wkt),
                AxisOrderPolicy.LEGACY);
    }

    /** CASE 3: EPSG:4326 as WKT1, with no AXIS clauses at all. */
    @Test
    public void geographicWgs84() {
        CrsDefinition def = new WktReader().readDefinition(WGS84_GEOGCS);
        assertEquals(CrsDefinition.Kind.GEOGRAPHIC, def.getKind());
        assertEquals("WGS 84", def.getName());
        assertEquals(new Identifier("EPSG", "4326"), def.getId());
        assertEquals("WGS_1984", def.getDatum().getName());
        assertEquals(new Identifier("EPSG", "6326"), def.getDatum().getId());
        EllipsoidDefinition e = def.getDatum().getEllipsoid();
        assertEquals(6378137.0, e.getSemiMajorAxis(), 0.0);
        assertEquals(298.257223563, e.getInverseFlattening(), 0.0);
        assertEquals(new Identifier("EPSG", "7030"), e.getId());
        assertEquals("+proj=longlat +datum=WGS84 +no_defs", proj(WGS84_GEOGCS));
        assertEquals(WktDialect.WKT1_GDAL, WktDialect.guess(WGS84_GEOGCS));
    }

    /** CASE 4: the WKT1 PRIMEM value is in degrees even when the GEOGCS unit is grad. */
    @Test
    public void primeMeridianIsInDegreesInWkt1() {
        String wkt = "GEOGCS[\"NTF (Paris)\",DATUM[\"Nouvelle_Triangulation_Francaise_Paris\","
                + "SPHEROID[\"Clarke 1880 (IGN)\",6378249.2,293.466021293627,"
                + "AUTHORITY[\"EPSG\",\"6807\"]],AUTHORITY[\"EPSG\",\"6807\"]],"
                + "PRIMEM[\"Paris\",2.33722917,AUTHORITY[\"EPSG\",\"8903\"]],"
                + "UNIT[\"grad\",0.015707963267949,AUTHORITY[\"EPSG\",\"9105\"]],"
                + "AXIS[\"latitude\",NORTH],AXIS[\"longitude\",EAST],"
                + "AUTHORITY[\"EPSG\",\"4807\"]]";
        CrsDefinition def = new WktReader().readDefinition(wkt);
        PrimeMeridianDefinition pm = def.getDatum().getPrimeMeridian();
        assertEquals("Paris", pm.getName());
        assertEquals(2.33722917, pm.getLongitudeDegrees(), 1e-12);
        // The CRS's angular unit is grad, and the axes were parsed and retained; neither changes
        // the prime meridian's own unit.
        assertEquals(UnitDefinition.GRAD.getConversionFactor(),
                def.getCoordinateSystem().getUnit().getConversionFactor(), 1e-15);
        assertTrue(proj(wkt).contains("+pm=paris"));
    }

    /** CASE 15: a PROJCS with no AXIS clauses; also the UTM zone 31N parameters. */
    @Test
    public void projectedUtm31N() {
        String wkt = "PROJCS[\"WGS 84 / UTM zone 31N\"," + WGS84_GEOGCS.replace("AUTHORITY[\"EPSG\",\"4326\"]",
                "AXIS[\"latitude\",NORTH],AXIS[\"longitude\",EAST],AUTHORITY[\"EPSG\",\"4326\"]")
                + ",PROJECTION[\"Transverse_Mercator\"],PARAMETER[\"latitude_of_origin\",0],"
                + "PARAMETER[\"central_meridian\",3],PARAMETER[\"scale_factor\",0.9996],"
                + "PARAMETER[\"false_easting\",500000],PARAMETER[\"false_northing\",0],"
                + "UNIT[\"metre\",1,AUTHORITY[\"EPSG\",\"9001\"]],AUTHORITY[\"EPSG\",\"32631\"]]";
        // Upstream: +proj=tmerc +lat_0=0 +lon_0=3 +k=0.9996 +x_0=500000 +y_0=0 +datum=WGS84
        //           +units=m +no_defs +type=crs
        // This library writes the equivalent +k_0 (proj4j accepts both, preferring +k_0) and does
        // not write +type=crs, which proj4j's parser rejects as an unsupported parameter.
        assertEquals("+proj=tmerc +lat_0=0 +lon_0=3 +k_0=0.9996 +x_0=500000 +y_0=0 +datum=WGS84 "
                + "+units=m +no_defs", proj(wkt));
        CoordinateReferenceSystem crs = new WktReader().read(wkt);
        assertEquals("EPSG:32631", crs.getName());
    }

    /** The projected CRS actually transforms: the zone 31 central meridian lands on 500000. */
    @Test
    public void projectedUtm31NTransforms() {
        String wkt = "PROJCS[\"WGS 84 / UTM zone 31N\"," + WGS84_GEOGCS
                + ",PROJECTION[\"Transverse_Mercator\"],PARAMETER[\"latitude_of_origin\",0],"
                + "PARAMETER[\"central_meridian\",3],PARAMETER[\"scale_factor\",0.9996],"
                + "PARAMETER[\"false_easting\",500000],PARAMETER[\"false_northing\",0],"
                + "UNIT[\"metre\",1]]";
        CoordinateReferenceSystem crs = new WktReader().read(wkt);
        CoordinateReferenceSystem wgs84 =
                new CRSFactory().createFromParameters("wgs84", "+proj=longlat +datum=WGS84");
        ProjCoordinate out = new BasicCoordinateTransform(wgs84, crs)
                .transform(new ProjCoordinate(3.0, 0.0), new ProjCoordinate());
        assertEquals(500000.0, out.x, 1e-6);
        assertEquals(0.0, out.y, 1e-6);
    }

    /** CASE 20: Mercator_1SP with a zero latitude of origin stays Mercator variant A. */
    @Test
    public void mercator1SpWithZeroLatitudeOfOrigin() {
        String wkt = "PROJCS[\"unnamed\",GEOGCS[\"WGS 84\",DATUM[\"unknown\","
                + "SPHEROID[\"WGS84\",6378137,298.257223563]],PRIMEM[\"Greenwich\",0],"
                + "UNIT[\"degree\",0.0174532925199433]],PROJECTION[\"Mercator_1SP\"],"
                + "PARAMETER[\"latitude_of_origin\",0],PARAMETER[\"central_meridian\",0],"
                + "PARAMETER[\"scale_factor\",1],PARAMETER[\"false_easting\",0],"
                + "PARAMETER[\"false_northing\",0],UNIT[\"Meter\",1],AXIS[\"Easting\",EAST],"
                + "AXIS[\"Northing\",NORTH]]";
        // The datum is named "unknown", so no +datum= can be inferred and the ellipsoid is named
        // instead. Emitting +datum=WGS84 here would assert a datum the document did not.
        assertEquals("+proj=merc +lon_0=0 +k_0=1 +x_0=0 +y_0=0 +ellps=WGS84 +units=m +no_defs",
                proj(wkt));
    }

    /**
     * CASE 20's sibling, GDAL ticket #3026: a Mercator_1SP with a non-zero latitude of origin is
     * really variant B, and its standard parallel becomes an equivalent scale factor because
     * proj4j's Mercator ignores {@code +lat_ts} entirely.
     */
    @Test
    public void mercator1SpWithNonZeroLatitudeOfOriginBecomesVariantB() {
        String wkt = "PROJCS[\"unnamed\",GEOGCS[\"WGS 84\",DATUM[\"unknown\","
                + "SPHEROID[\"WGS84\",6378137,298.257223563]],PRIMEM[\"Greenwich\",0],"
                + "UNIT[\"degree\",0.0174532925199433]],PROJECTION[\"Mercator_1SP\"],"
                + "PARAMETER[\"latitude_of_origin\",30],PARAMETER[\"central_meridian\",0],"
                + "PARAMETER[\"scale_factor\",1],PARAMETER[\"false_easting\",0],"
                + "PARAMETER[\"false_northing\",0],UNIT[\"Meter\",1]]";
        String p = proj(wkt);
        assertTrue(p, p.startsWith("+proj=merc "));
        // k0 = cos(30) / sqrt(1 - e^2 sin^2(30)), EPSG Guidance Note 7-2.
        double es = 2 / 298.257223563 - 1 / (298.257223563 * 298.257223563);
        double expected = Math.cos(Math.toRadians(30))
                / Math.sqrt(1 - es * Math.pow(Math.sin(Math.toRadians(30)), 2));
        assertTrue(p, p.contains("+k_0=" + WktFormatTestAccess.number(expected)));
        assertTrue("no +lat_ts may survive, proj4j's Mercator ignores it", !p.contains("+lat_ts"));
    }

    /** CASE 22: Krovak with south/west axes is EPSG 9819 and needs {@code +axis=swu}. */
    @Test
    public void krovakSouthWest() {
        assertEquals("+proj=krovak +axis=swu +lat_0=49.5 +lon_0=24.8333333333333 "
                        + "+alpha=30.2881397527778 +k_0=0.9999 +x_0=0 +y_0=0 +ellps=bessel "
                        + "+units=m +no_defs",
                proj(krovak("AXIS[\"X\",SOUTH],AXIS[\"Y\",WEST],")));
    }

    /** CASE 23: the same WKT with east/north axes is EPSG 1041 and must not be flipped. */
    @Test
    public void krovakEastNorth() {
        assertEquals("+proj=krovak +lat_0=49.5 +lon_0=24.8333333333333 +alpha=30.2881397527778 "
                        + "+k_0=0.9999 +x_0=0 +y_0=0 +ellps=bessel +units=m +no_defs",
                proj(krovak("AXIS[\"X\",EAST],AXIS[\"Y\",NORTH],")));
    }

    private static String krovak(String axes) {
        return "PROJCS[\"S-JTSK / Krovak\",GEOGCS[\"S-JTSK\","
                + "DATUM[\"System_Jednotne_Trigonometricke_Site_Katastralni\","
                + "SPHEROID[\"Bessel 1841\",6377397.155,299.1528128,AUTHORITY[\"EPSG\",\"7004\"]],"
                + "AUTHORITY[\"EPSG\",\"6156\"]],PRIMEM[\"Greenwich\",0,"
                + "AUTHORITY[\"EPSG\",\"8901\"]],UNIT[\"degree\",0.0174532925199433,"
                + "AUTHORITY[\"EPSG\",\"9122\"]],AUTHORITY[\"EPSG\",\"4156\"]],"
                + "PROJECTION[\"Krovak\"],PARAMETER[\"latitude_of_center\",49.5],"
                + "PARAMETER[\"longitude_of_center\",24.83333333333333],"
                + "PARAMETER[\"azimuth\",30.2881397527778],"
                + "PARAMETER[\"pseudo_standard_parallel_1\",78.5],"
                + "PARAMETER[\"scale_factor\",0.9999],PARAMETER[\"false_easting\",0],"
                + "PARAMETER[\"false_northing\",0],UNIT[\"metre\",1,"
                + "AUTHORITY[\"EPSG\",\"9001\"]]," + axes + "AUTHORITY[\"EPSG\",\"5513\"]]";
    }

    /** CASE 43: a GEOGCS with a three-term TOWGS84 becomes a bound CRS. */
    @Test
    public void geogcsWithThreeTermTowgs84() {
        String wkt = "GEOGCS[\"my GCS\",DATUM[\"my datum\","
                + "SPHEROID[\"WGS 84\",6378137,298.257223563],TOWGS84[1,2,3]],"
                + "PRIMEM[\"Greenwich\",0],UNIT[\"degree\",0.0174532925199433]]";
        CrsDefinition def = new WktReader().readDefinition(wkt);
        assertNotNull(def.getToWgs84());
        assertEquals(3, def.getToWgs84().length);
        assertEquals("+proj=longlat +ellps=WGS84 +towgs84=1,2,3 +no_defs", proj(wkt));
    }

    /** CASE 44: a PROJCS whose base carries a seven-term TOWGS84. */
    @Test
    public void projcsWithSevenTermTowgs84() {
        String wkt = "PROJCS[\"my PCS\",GEOGCS[\"my GCS\",DATUM[\"my datum\","
                + "SPHEROID[\"Bessel 1841\",6377397.155,299.1528128],"
                + "TOWGS84[1,2,3,4,5,6,7]],PRIMEM[\"Greenwich\",0],"
                + "UNIT[\"degree\",0.0174532925199433]],PROJECTION[\"Transverse_Mercator\"],"
                + "PARAMETER[\"latitude_of_origin\",0],PARAMETER[\"central_meridian\",9],"
                + "PARAMETER[\"scale_factor\",1],PARAMETER[\"false_easting\",0],"
                + "PARAMETER[\"false_northing\",0],UNIT[\"metre\",1]]";
        assertEquals("+proj=tmerc +lat_0=0 +lon_0=9 +k_0=1 +x_0=0 +y_0=0 +ellps=bessel "
                + "+towgs84=1,2,3,4,5,6,7 +units=m +no_defs", proj(wkt));
    }

    /** A US survey foot state plane: the unit is honoured by its factor. */
    @Test
    public void projectedInUsSurveyFeet() {
        String wkt = "PROJCS[\"NAD83 / Texas Central (ftUS)\",GEOGCS[\"NAD83\","
                + "DATUM[\"North_American_Datum_1983\",SPHEROID[\"GRS 1980\",6378137,"
                + "298.257222101]],PRIMEM[\"Greenwich\",0],UNIT[\"degree\",0.0174532925199433]],"
                + "PROJECTION[\"Lambert_Conformal_Conic_2SP\"],"
                + "PARAMETER[\"standard_parallel_1\",31.88333333333333],"
                + "PARAMETER[\"standard_parallel_2\",30.11666666666667],"
                + "PARAMETER[\"latitude_of_origin\",29.66666666666667],"
                + "PARAMETER[\"central_meridian\",-100.3333333333333],"
                + "PARAMETER[\"false_easting\",2296583.333],"
                + "PARAMETER[\"false_northing\",9842500],"
                + "UNIT[\"US survey foot\",0.3048006096012192]]";
        String p = proj(wkt);
        assertTrue(p, p.contains("+units=us-ft"));
        assertTrue(p, p.contains("+datum=NAD83"));
        assertTrue(p, p.contains("+lat_1=31.8833333333333"));
        assertTrue(p, p.contains("+lat_2=30.1166666666667"));
    }

    /** A Lambert Conic Conformal 1SP must produce both {@code +lat_1} and {@code +lat_0}. */
    @Test
    public void lambertConformalConic1SpDerivesLat1() {
        String wkt = "PROJCS[\"unnamed\",GEOGCS[\"WGS 84\",DATUM[\"WGS_1984\","
                + "SPHEROID[\"WGS 84\",6378137,298.257223563]],PRIMEM[\"Greenwich\",0],"
                + "UNIT[\"degree\",0.0174532925199433]],"
                + "PROJECTION[\"Lambert_Conformal_Conic_1SP\"],"
                + "PARAMETER[\"latitude_of_origin\",25],PARAMETER[\"central_meridian\",-95],"
                + "PARAMETER[\"scale_factor\",1],PARAMETER[\"false_easting\",0],"
                + "PARAMETER[\"false_northing\",0],UNIT[\"metre\",1]]";
        assertEquals("+proj=lcc +lat_0=25 +lon_0=-95 +lat_1=25 +k_0=1 +x_0=0 +y_0=0 "
                + "+datum=WGS84 +units=m +no_defs", proj(wkt));
    }

    /** An unnamed WKT1 projection method is refused, not guessed at. */
    @Test
    public void unknownProjectionMethodIsRefused() {
        String wkt = "PROJCS[\"unnamed\",GEOGCS[\"WGS 84\",DATUM[\"WGS_1984\","
                + "SPHEROID[\"WGS 84\",6378137,298.257223563]],PRIMEM[\"Greenwich\",0],"
                + "UNIT[\"degree\",0.0174532925199433]],PROJECTION[\"Not_A_Projection\"],"
                + "UNIT[\"metre\",1]]";
        try {
            proj(wkt);
            fail("expected a WktParseException for an unknown method");
        } catch (WktParseException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Not_A_Projection"));
        }
    }

    /** CASE 63-67: structurally invalid WKT1 is rejected rather than half-read. */
    @Test
    public void invalidWkt1IsRejected() {
        String[] invalid = {
                "GEOGCS[\"foo\"]",
                "GEOGCS[\"foo\",DATUM[\"bar\"]]",
                "GEOGCS[\"foo\",DATUM[\"bar\",SPHEROID[\"x\"]]]",
                "PROJCS[\"foo\"]",
                "PROJCS[\"foo\",GEOGCS[\"bar\",DATUM[\"baz\","
                        + "SPHEROID[\"x\",6378137,298.257223563]],PRIMEM[\"Greenwich\",0],"
                        + "UNIT[\"degree\",0.0174532925199433]]]",
                "SPHEROID[\"x\",6378137,298.257223563]",
        };
        for (int i = 0; i < invalid.length; i++) {
            try {
                new WktReader().readDefinition(invalid[i]);
                fail("expected a WktParseException for \"" + invalid[i] + "\"");
            } catch (WktParseException expected) {
                // the point
            }
        }
    }

    /** CASE 69: a TOWGS84 with the wrong number of terms is an error, not a truncation. */
    @Test
    public void invalidTowgs84IsRejected() {
        String wkt = "GEOGCS[\"foo\",DATUM[\"bar\",SPHEROID[\"WGS 84\",6378137,298.257223563],"
                + "TOWGS84[1,2]],PRIMEM[\"Greenwich\",0],UNIT[\"degree\",0.0174532925199433]]";
        try {
            new WktReader().readDefinition(wkt);
            fail("expected a WktParseException");
        } catch (WktParseException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("TOWGS84"));
        }
    }

    /** CASE 33: a WKT1 VERT_CS is parsed, retained, and refused as a proj4j CRS. */
    @Test
    public void verticalCrsIsParsedButCannotBecomeACrs() {
        String wkt = "VERT_CS[\"ODN height\",VERT_DATUM[\"Ordnance Datum Newlyn\",2005,"
                + "AUTHORITY[\"EPSG\",\"5101\"]],UNIT[\"metre\",1,AUTHORITY[\"EPSG\",\"9001\"]],"
                + "AXIS[\"Gravity-related height\",UP],AUTHORITY[\"EPSG\",\"5701\"]]";
        CrsDefinition def = new WktReader().readDefinition(wkt);
        assertEquals(CrsDefinition.Kind.VERTICAL, def.getKind());
        assertEquals("ODN height", def.getName());
        assertEquals("Ordnance Datum Newlyn", def.getDatum().getName());
        assertEquals(1, def.getCoordinateSystem().getAxes().size());
        assertTrue(def.getCoordinateSystem().getAxes().get(0).isVertical());
        assertNull(def.horizontalComponent());
        try {
            CrsDefinitions.toCrs(def, AxisOrderPolicy.LEGACY);
            fail("a vertical-only CRS must be refused, not silently made horizontal");
        } catch (WktParseException expected) {
            // the point
        }
    }

    /** CASE 37: a COMPD_CS yields its horizontal component, and says so. */
    @Test
    public void compoundCrsUsesItsHorizontalComponent() {
        String wkt = "COMPD_CS[\"WGS 84 + ODN height\"," + WGS84_GEOGCS
                + ",VERT_CS[\"ODN height\",VERT_DATUM[\"Ordnance Datum Newlyn\",2005],"
                + "UNIT[\"metre\",1],AXIS[\"Gravity-related height\",UP]]]";
        CrsDefinition def = new WktReader().readDefinition(wkt);
        assertEquals(CrsDefinition.Kind.COMPOUND, def.getKind());
        assertEquals(2, def.getComponents().size());
        assertEquals(CrsDefinition.Kind.GEOGRAPHIC, def.horizontalComponent().getKind());
        assertEquals("+proj=longlat +datum=WGS84 +no_defs", proj(wkt));
    }

    /** A BOUNDCRS's abridged transformation becomes {@code +towgs84}. */
    @Test
    public void boundCrsAbridgedTransformation() {
        String wkt = "BOUNDCRS[SOURCECRS[GEOGCRS[\"NTF\",DATUM[\"Nouvelle Triangulation "
                + "Francaise\",ELLIPSOID[\"Clarke 1880 (IGN)\",6378249.2,293.4660212936269,"
                + "LENGTHUNIT[\"metre\",1]]],PRIMEM[\"Greenwich\",0,"
                + "ANGLEUNIT[\"degree\",0.0174532925199433]],CS[ellipsoidal,2],"
                + "AXIS[\"latitude\",north,ORDER[1],ANGLEUNIT[\"degree\",0.0174532925199433]],"
                + "AXIS[\"longitude\",east,ORDER[2],ANGLEUNIT[\"degree\",0.0174532925199433]]]],"
                + "TARGETCRS[GEOGCRS[\"WGS 84\",DATUM[\"World Geodetic System 1984\","
                + "ELLIPSOID[\"WGS 84\",6378137,298.257223563,LENGTHUNIT[\"metre\",1]]],"
                + "PRIMEM[\"Greenwich\",0,ANGLEUNIT[\"degree\",0.0174532925199433]],"
                + "CS[ellipsoidal,2],AXIS[\"latitude\",north,ORDER[1],"
                + "ANGLEUNIT[\"degree\",0.0174532925199433]],AXIS[\"longitude\",east,ORDER[2],"
                + "ANGLEUNIT[\"degree\",0.0174532925199433]],ID[\"EPSG\",4326]]],"
                + "ABRIDGEDTRANSFORMATION[\"NTF to WGS 84 (1)\","
                + "METHOD[\"Geocentric translations (geog2D domain)\",ID[\"EPSG\",9603]],"
                + "PARAMETER[\"X-axis translation\",-168,ID[\"EPSG\",8605]],"
                + "PARAMETER[\"Y-axis translation\",-60,ID[\"EPSG\",8606]],"
                + "PARAMETER[\"Z-axis translation\",320,ID[\"EPSG\",8607]]]]";
        CrsDefinition def = new WktReader().readDefinition(wkt);
        assertEquals(CrsDefinition.Kind.BOUND, def.getKind());
        assertEquals(3, def.getToWgs84().length);
        assertEquals(-168.0, def.getToWgs84()[0], 0.0);
        // Clarke 1880 (IGN) is not one of proj4j's built-in ellipsoids, so the axes are given
        // explicitly — and as +a and +b, never +rf, because the semi-minor axis is exact and
        // needs no reciprocal.
        assertEquals("+proj=longlat +ellps=clrk80ign +towgs84=-168,-60,320 +no_defs",
                proj(wkt));
    }

    /** Exposes {@link WktFormat} to tests in this package without widening its visibility. */
    static final class WktFormatTestAccess {
        static String number(double v) {
            return WktFormat.number(v);
        }
    }
}

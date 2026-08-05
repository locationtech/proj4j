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
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;

/**
 * The WKT2 writer: what was read is written back, and what is written can be read again.
 */
public class WktWriterTest {

    /**
     * The canonical form this writer produces for EPSG:4326 — one line, unit identifiers included,
     * per-axis units, exactly as PROJ's own non-simplified WKT2:2019 export.
     */
    private static final String WGS84_WKT2 =
            "GEOGCRS[\"WGS 84\","
                    + "DATUM[\"World Geodetic System 1984\","
                    + "ELLIPSOID[\"WGS 84\",6378137,298.257223563,"
                    + "LENGTHUNIT[\"metre\",1,ID[\"EPSG\",9001]],ID[\"EPSG\",7030]],"
                    + "ID[\"EPSG\",6326]],"
                    + "PRIMEM[\"Greenwich\",0,"
                    + "ANGLEUNIT[\"degree\",0.0174532925199433,ID[\"EPSG\",9122]],"
                    + "ID[\"EPSG\",8901]],"
                    + "CS[ellipsoidal,2],"
                    + "AXIS[\"latitude\",north,ORDER[1],"
                    + "ANGLEUNIT[\"degree\",0.0174532925199433,ID[\"EPSG\",9122]]],"
                    + "AXIS[\"longitude\",east,ORDER[2],"
                    + "ANGLEUNIT[\"degree\",0.0174532925199433,ID[\"EPSG\",9122]]],"
                    + "ID[\"EPSG\",4326]]";

    /** Reading the canonical form and writing it again is the identity. */
    @Test
    public void geographicRoundTripsExactly() {
        CrsDefinition def = new WktReader().readDefinition(WGS84_WKT2);
        assertEquals(WGS84_WKT2, new WktWriter().write(def));
    }

    /** The multi-line form parses back to the same text when written single-line. */
    @Test
    public void multilineIsReadableAgain() {
        CrsDefinition def = new WktReader().readDefinition(WGS84_WKT2);
        String multiline = new WktWriter().multiline().write(def);
        assertTrue(multiline.contains("\n"));
        assertEquals(WGS84_WKT2, new WktWriter().write(
                new WktReader().readDefinition(multiline)));
    }

    /** A WKT1 input becomes WKT2 that reads back to the same PROJ string. */
    @Test
    public void wkt1ProjectedBecomesEquivalentWkt2() {
        String wkt1 = "PROJCS[\"WGS 84 / UTM zone 31N\"," + Wkt1ReaderTest.WGS84_GEOGCS
                + ",PROJECTION[\"Transverse_Mercator\"],PARAMETER[\"latitude_of_origin\",0],"
                + "PARAMETER[\"central_meridian\",3],PARAMETER[\"scale_factor\",0.9996],"
                + "PARAMETER[\"false_easting\",500000],PARAMETER[\"false_northing\",0],"
                + "UNIT[\"metre\",1,AUTHORITY[\"EPSG\",\"9001\"]],AUTHORITY[\"EPSG\",\"32631\"]]";
        CrsDefinition fromWkt1 = new WktReader().readDefinition(wkt1);
        String wkt2 = new WktWriter().write(fromWkt1);

        // The WKT1 method and parameter names are translated to their EPSG spellings.
        assertTrue(wkt2, wkt2.contains("METHOD[\"Transverse Mercator\",ID[\"EPSG\",9807]]"));
        assertTrue(wkt2, wkt2.contains("PARAMETER[\"Latitude of natural origin\",0,"));
        assertTrue(wkt2, wkt2.contains("PARAMETER[\"Scale factor at natural origin\",0.9996,"));
        assertTrue(wkt2, wkt2.startsWith("PROJCRS[\"WGS 84 / UTM zone 31N\",BASEGEOGCRS["));
        assertTrue(wkt2, wkt2.endsWith("ID[\"EPSG\",32631]]"));

        CrsDefinition fromWkt2 = new WktReader().readDefinition(wkt2);
        assertEquals(CrsDefinitions.toProjParameterString(fromWkt1, AxisOrderPolicy.LEGACY),
                CrsDefinitions.toProjParameterString(fromWkt2, AxisOrderPolicy.LEGACY));
        // And writing the re-read definition is now stable.
        assertEquals(wkt2, new WktWriter().write(fromWkt2));
    }

    /** A projected CRS in WKT2 round-trips exactly. */
    @Test
    public void projectedRoundTripsExactly() {
        String wkt2 = "PROJCRS[\"WGS 84 / UTM zone 31N\","
                + "BASEGEOGCRS[\"WGS 84\",DATUM[\"World Geodetic System 1984\","
                + "ELLIPSOID[\"WGS 84\",6378137,298.257223563,"
                + "LENGTHUNIT[\"metre\",1,ID[\"EPSG\",9001]]]],"
                + "PRIMEM[\"Greenwich\",0,"
                + "ANGLEUNIT[\"degree\",0.0174532925199433,ID[\"EPSG\",9122]]],"
                + "ID[\"EPSG\",4326]],"
                + "CONVERSION[\"UTM zone 31N\","
                + "METHOD[\"Transverse Mercator\",ID[\"EPSG\",9807]],"
                + "PARAMETER[\"Latitude of natural origin\",0,"
                + "ANGLEUNIT[\"degree\",0.0174532925199433],ID[\"EPSG\",8801]],"
                + "PARAMETER[\"Longitude of natural origin\",3,"
                + "ANGLEUNIT[\"degree\",0.0174532925199433],ID[\"EPSG\",8802]],"
                + "PARAMETER[\"Scale factor at natural origin\",0.9996,"
                + "SCALEUNIT[\"unity\",1],ID[\"EPSG\",8805]],"
                + "PARAMETER[\"False easting\",500000,LENGTHUNIT[\"metre\",1],ID[\"EPSG\",8806]],"
                + "PARAMETER[\"False northing\",0,LENGTHUNIT[\"metre\",1],ID[\"EPSG\",8807]]],"
                + "CS[Cartesian,2],"
                + "AXIS[\"(E)\",east,ORDER[1],LENGTHUNIT[\"metre\",1,ID[\"EPSG\",9001]]],"
                + "AXIS[\"(N)\",north,ORDER[2],LENGTHUNIT[\"metre\",1,ID[\"EPSG\",9001]]],"
                + "ID[\"EPSG\",32631]]";
        assertEquals(wkt2, new WktWriter().write(new WktReader().readDefinition(wkt2)));
    }

    /** WKT2:2015 uses GEODCRS and writes SCOPE/AREA/BBOX without a USAGE wrapper. */
    @Test
    public void wkt2015Differences() {
        CrsDefinition def = new WktReader().readDefinition(WGS84_WKT2);
        def.setScope("Horizontal component of 3D system.");
        def.setAreaDescription("World.");
        def.setBoundingBox(new double[]{-90, -180, 90, 180});
        String wkt2019 = new WktWriter(WktDialect.WKT2_2019).write(def);
        String wkt2015 = new WktWriter(WktDialect.WKT2_2015).write(def);
        assertTrue(wkt2019, wkt2019.contains("USAGE[SCOPE["));
        assertTrue(wkt2015, wkt2015.startsWith("GEODCRS["));
        assertTrue(wkt2015, !wkt2015.contains("USAGE["));
        assertTrue(wkt2015, wkt2015.contains("SCOPE[\"Horizontal component of 3D system.\"]"));
        // Both are readable, and describe the same thing.
        assertEquals(CrsDefinitions.toProjParameterString(
                        new WktReader().readDefinition(wkt2015), AxisOrderPolicy.LEGACY),
                CrsDefinitions.toProjParameterString(
                        new WktReader().readDefinition(wkt2019), AxisOrderPolicy.LEGACY));
    }

    /** Writing a bare proj4j CRS describes what such a CRS actually knows. */
    @Test
    public void writesAProj4jCrs() {
        CoordinateReferenceSystem crs = new CRSFactory().createFromParameters("utm31",
                "+proj=tmerc +lat_0=0 +lon_0=3 +k_0=0.9996 +x_0=500000 +y_0=0 +datum=WGS84 "
                        + "+units=m");
        String wkt2 = new WktWriter().write(crs);
        assertTrue(wkt2, wkt2.startsWith("PROJCRS[\"utm31\","));
        assertTrue(wkt2, wkt2.contains("METHOD[\"Transverse Mercator\",ID[\"EPSG\",9807]]"));
        assertTrue(wkt2, wkt2.contains("PARAMETER[\"Longitude of natural origin\",3,"));
        assertTrue(wkt2, wkt2.contains("PARAMETER[\"False easting\",500000,"));
        // Read it back and the PROJ parameters agree with what we started from.
        String proj = CrsDefinitions.toProjParameterString(
                new WktReader().readDefinition(wkt2), AxisOrderPolicy.LEGACY);
        assertEquals("+proj=tmerc +lat_0=0 +lon_0=3 +k_0=0.9996 +x_0=500000 +y_0=0 +datum=WGS84 "
                + "+units=m +no_defs", proj);
    }

    /** A geographic proj4j CRS is written as a GEOGCRS. */
    @Test
    public void writesAGeographicProj4jCrs() {
        CoordinateReferenceSystem crs = new CRSFactory().createFromParameters("wgs84",
                "+proj=longlat +datum=WGS84");
        String wkt2 = new WktWriter().write(crs);
        assertTrue(wkt2, wkt2.startsWith("GEOGCRS[\"WGS 84\","));
        assertTrue(wkt2, wkt2.contains("ELLIPSOID[\"WGS 84\",6378137,298.257223563"));
        assertEquals("+proj=longlat +datum=WGS84 +no_defs",
                CrsDefinitions.toProjParameterString(new WktReader().readDefinition(wkt2),
                        AxisOrderPolicy.LEGACY));
    }

    /** A bound CRS is written with its abridged transformation and reads back identically. */
    @Test
    public void boundCrsRoundTrips() {
        String wkt1 = "GEOGCS[\"my GCS\",DATUM[\"my datum\","
                + "SPHEROID[\"Bessel 1841\",6377397.155,299.1528128],"
                + "TOWGS84[1,2,3,4,5,6,7]],PRIMEM[\"Greenwich\",0],"
                + "UNIT[\"degree\",0.0174532925199433]]";
        CrsDefinition def = new WktReader().readDefinition(wkt1);
        // The definition read from WKT1 is geographic with Helmert parameters; asked to write it
        // as WKT2 the bound form is what carries them, so the caller states that intent.
        CrsDefinition bound = new CrsDefinition();
        bound.setKind(CrsDefinition.Kind.BOUND);
        bound.setName(def.getName());
        bound.setBaseCrs(def);
        bound.setToWgs84(def.getToWgs84());
        String wkt2 = new WktWriter().write(bound);
        assertTrue(wkt2, wkt2.startsWith("BOUNDCRS[SOURCECRS[GEOGCRS[\"my GCS\","));
        assertTrue(wkt2, wkt2.contains("ABRIDGEDTRANSFORMATION[\"Transformation to WGS84\""));
        // 1 + 7e-6 is how the abridged form spells 7 ppm.
        assertTrue(wkt2, wkt2.contains("PARAMETER[\"Scale difference\",1.000007,"));

        CrsDefinition reread = new WktReader().readDefinition(wkt2);
        assertEquals(CrsDefinition.Kind.BOUND, reread.getKind());
        // 7 ppm survives to nine digits: the abridged form stores 1 + s*1e-6, and recovering s
        // from it costs a few bits. PROJ loses the same bits for the same reason.
        assertEquals(7.0, reread.getToWgs84()[6], 1e-7);
        String proj = CrsDefinitions.toProjParameterString(reread, AxisOrderPolicy.LEGACY);
        assertTrue(proj, proj.startsWith("+proj=longlat +ellps=bessel +towgs84=1,2,3,4,5,6,7"));
    }

    /** The writer refuses WKT1: this package reads that dialect but does not produce it. */
    @Test(expected = IllegalArgumentException.class)
    public void refusesToWriteWkt1() {
        new WktWriter(WktDialect.WKT1_GDAL);
    }

    /** Numbers are spelled as PROJ spells them: no exponents, fifteen significant digits. */
    @Test
    public void numberFormatting() {
        assertEquals("0", WktFormat.number(0.0));
        assertEquals("1", WktFormat.number(1.0));
        assertEquals("-180", WktFormat.number(-180.0));
        assertEquals("6378137", WktFormat.number(6378137.0));
        assertEquals("0.9996", WktFormat.number(0.9996));
        assertEquals("298.257223563", WktFormat.number(298.257223563));
        assertEquals("0.0174532925199433", WktFormat.number(Math.PI / 180));
        assertEquals("0.304800609601219", WktFormat.number(12.0 / 39.37));
        assertEquals("0.00001", WktFormat.number(1e-5));
        assertEquals("49.00000000001", WktFormat.number(49.00000000001));
    }
}

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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * The WKT2 reader, over inputs taken from PROJ 9.8.1's {@code test/unit/test_io.cpp}
 * ({@code wkt_parse.wkt2_*}). Corpus case numbers in the comments refer to the extraction of that
 * file.
 */
public class Wkt2ReaderTest {

    /** CASE 111: EPSG:4326 in canonical, fully-decorated WKT2:2015. */
    static final String GEODCRS_4326 =
            "GEODCRS[\"WGS 84\",\n"
                    + "    DATUM[\"World Geodetic System 1984\",\n"
                    + "        ELLIPSOID[\"WGS 84\",6378137,298.257223563,\n"
                    + "            LENGTHUNIT[\"metre\",1,\n"
                    + "                ID[\"EPSG\",9001]],\n"
                    + "            ID[\"EPSG\",7030]],\n"
                    + "        ID[\"EPSG\",6326]],\n"
                    + "    PRIMEM[\"Greenwich\",0,\n"
                    + "        ANGLEUNIT[\"degree\",0.0174532925199433,\n"
                    + "            ID[\"EPSG\",9122]],\n"
                    + "        ID[\"EPSG\",8901]],\n"
                    + "    CS[ellipsoidal,2],\n"
                    + "        AXIS[\"latitude\",north,\n"
                    + "            ORDER[1],\n"
                    + "            ANGLEUNIT[\"degree\",0.0174532925199433,\n"
                    + "                ID[\"EPSG\",9122]]],\n"
                    + "        AXIS[\"longitude\",east,\n"
                    + "            ORDER[2],\n"
                    + "            ANGLEUNIT[\"degree\",0.0174532925199433,\n"
                    + "                ID[\"EPSG\",9122]]],\n"
                    + "    ID[\"EPSG\",4326]]";

    /** A source CRS on a datum proj4j has no built-in code for, so its Helmert terms matter. */
    private static final String DATUM_73 =
            "GEOGCRS[\"Datum 73\",DATUM[\"Datum 73\","
                    + "ELLIPSOID[\"International 1924\",6378388,297,LENGTHUNIT[\"metre\",1]]],"
                    + "PRIMEM[\"Greenwich\",0,ANGLEUNIT[\"degree\",0.0174532925199433]],"
                    + "CS[ellipsoidal,2],AXIS[\"latitude\",north,ORDER[1],"
                    + "ANGLEUNIT[\"degree\",0.0174532925199433]],AXIS[\"longitude\",east,"
                    + "ORDER[2],ANGLEUNIT[\"degree\",0.0174532925199433]]]";

    private static String proj(String wkt) {
        return CrsDefinitions.toProjParameterString(new WktReader().readDefinition(wkt),
                AxisOrderPolicy.LEGACY);
    }

    @Test
    public void geodcrs4326() {
        CrsDefinition def = new WktReader().readDefinition(GEODCRS_4326);
        assertEquals(CrsDefinition.Kind.GEOGRAPHIC, def.getKind());
        assertEquals("WGS 84", def.getName());
        assertEquals(new Identifier("EPSG", "4326"), def.getId());
        assertEquals("World Geodetic System 1984", def.getDatum().getName());
        assertEquals(new Identifier("EPSG", "6326"), def.getDatum().getId());
        assertEquals(new Identifier("EPSG", "7030"), def.getDatum().getEllipsoid().getId());
        // The declared axis order is latitude first, and it is retained verbatim.
        assertEquals(2, def.getCoordinateSystem().getAxes().size());
        assertEquals(AxisDefinition.NORTH,
                def.getCoordinateSystem().getAxes().get(0).getDirection());
        assertEquals(AxisDefinition.EAST,
                def.getCoordinateSystem().getAxes().get(1).getDirection());
        assertEquals(WktDialect.WKT2_2015, WktDialect.guess(GEODCRS_4326));
        assertEquals("+proj=longlat +datum=WGS84 +no_defs", proj(GEODCRS_4326));
    }

    /** CASE 112-114: the long and 2019 keywords describe the same thing. */
    @Test
    public void keywordVariants() {
        String[] variants = {
                GEODCRS_4326,
                GEODCRS_4326.replaceFirst("GEODCRS", "GEODETICCRS"),
                GEODCRS_4326.replaceFirst("GEODCRS", "GEOGCRS"),
                GEODCRS_4326.replaceFirst("GEODCRS", "GEOGRAPHICCRS"),
        };
        for (int i = 0; i < variants.length; i++) {
            CrsDefinition def = new WktReader().readDefinition(variants[i]);
            assertEquals(CrsDefinition.Kind.GEOGRAPHIC, def.getKind());
            assertEquals("WGS 84", def.getName());
            assertEquals("+proj=longlat +datum=WGS84 +no_defs", proj(variants[i]));
        }
        assertEquals(WktDialect.WKT2_2019,
                WktDialect.guess(GEODCRS_4326.replaceFirst("GEODCRS", "GEOGCRS")));
    }

    /** CASE 115: the simplified form — abbreviations in the axis name, one CS-level unit. */
    @Test
    public void simplifiedForm() {
        String wkt = "GEODCRS[\"WGS 84\",DATUM[\"World Geodetic System 1984\","
                + "ELLIPSOID[\"WGS 84\",6378137,298.257223563]],CS[ellipsoidal,2],"
                + "AXIS[\"latitude (lat)\",north],AXIS[\"longitude (lon)\",east],"
                + "UNIT[\"degree\",0.0174532925199433],ID[\"EPSG\",4326]]";
        CrsDefinition def = new WktReader().readDefinition(wkt);
        AxisDefinition lat = def.getCoordinateSystem().getAxes().get(0);
        assertEquals("latitude", lat.getName());
        assertEquals("lat", lat.getAbbreviation());
        assertEquals(UnitDefinition.DEGREE.getConversionFactor(),
                def.getCoordinateSystem().unitOf(0).getConversionFactor(), 0.0);
        // No PRIMEM at all: Greenwich is the default.
        assertTrue(def.getDatum().getPrimeMeridian().isGreenwich());
        assertEquals("+proj=longlat +datum=WGS84 +no_defs", proj(wkt));
    }

    /** CASE 108: EPSG:4326's WKT2:2019 form uses ENSEMBLE rather than DATUM. */
    @Test
    public void datumEnsemble() {
        String wkt = "GEOGCRS[\"WGS 84\",ENSEMBLE[\"World Geodetic System 1984 ensemble\","
                + "MEMBER[\"World Geodetic System 1984 (Transit)\"],"
                + "MEMBER[\"World Geodetic System 1984 (G730)\"],"
                + "ELLIPSOID[\"WGS 84\",6378137,298.257223563,LENGTHUNIT[\"metre\",1]],"
                + "ENSEMBLEACCURACY[2.0]],"
                + "PRIMEM[\"Greenwich\",0,ANGLEUNIT[\"degree\",0.0174532925199433]],"
                + "CS[ellipsoidal,2],"
                + "AXIS[\"geodetic latitude (Lat)\",north,ORDER[1],"
                + "ANGLEUNIT[\"degree\",0.0174532925199433]],"
                + "AXIS[\"geodetic longitude (Lon)\",east,ORDER[2],"
                + "ANGLEUNIT[\"degree\",0.0174532925199433]],ID[\"EPSG\",4326]]";
        CrsDefinition def = new WktReader().readDefinition(wkt);
        assertEquals("World Geodetic System 1984 ensemble", def.getDatum().getName());
        assertNotNull(def.getDatum().getEllipsoid());
        assertEquals(WktDialect.WKT2_2019, WktDialect.guess(wkt));
        // The ensemble name is not one proj4j has a +datum= for, so the ellipsoid is named.
        assertEquals("+proj=longlat +ellps=WGS84 +no_defs", proj(wkt));
    }

    /** CASE 123: a WKT2 PROJCRS whose parameters omit their units, inheriting the CRS's. */
    @Test
    public void projectedWithOmittedParameterUnits() {
        String wkt = "PROJCRS[\"WGS 84 / UTM zone 31N\",BASEGEODCRS[\"WGS 84\","
                + "DATUM[\"World Geodetic System 1984\","
                + "ELLIPSOID[\"WGS 84\",6378137,298.257223563]],"
                + "PRIMEM[\"Greenwich\",0]],CONVERSION[\"UTM zone 31N\","
                + "METHOD[\"Transverse Mercator\",ID[\"EPSG\",9807]],"
                + "PARAMETER[\"Latitude of natural origin\",0],"
                + "PARAMETER[\"Longitude of natural origin\",3],"
                + "PARAMETER[\"Scale factor at natural origin\",0.9996],"
                + "PARAMETER[\"False easting\",500000],"
                + "PARAMETER[\"False northing\",0]],CS[Cartesian,2],"
                + "AXIS[\"(E)\",east],AXIS[\"(N)\",north],LENGTHUNIT[\"metre\",1],"
                + "ID[\"EPSG\",32631]]";
        assertEquals("+proj=tmerc +lat_0=0 +lon_0=3 +k_0=0.9996 +x_0=500000 +y_0=0 +datum=WGS84 "
                + "+units=m +no_defs", proj(wkt));
    }

    /** CASE 131: the base CRS's angular unit comes from its PRIMEM's ANGLEUNIT. */
    @Test
    public void baseAngularUnitFromPrimeMeridian() {
        String wkt = "PROJCRS[\"NTF (Paris) / Lambert zone II\","
                + "BASEGEODCRS[\"NTF (Paris)\","
                + "DATUM[\"Nouvelle Triangulation Francaise (Paris)\","
                + "ELLIPSOID[\"Clarke 1880 (IGN)\",6378249.2,293.4660212936269,"
                + "LENGTHUNIT[\"metre\",1]]],"
                + "PRIMEM[\"Paris\",2.5969213,ANGLEUNIT[\"grad\",0.015707963267949]]],"
                + "CONVERSION[\"Lambert zone II\","
                + "METHOD[\"Lambert Conic Conformal (1SP)\",ID[\"EPSG\",9801]],"
                + "PARAMETER[\"Latitude of natural origin\",52,"
                + "ANGLEUNIT[\"grad\",0.015707963267949]],"
                + "PARAMETER[\"Longitude of natural origin\",0,"
                + "ANGLEUNIT[\"grad\",0.015707963267949]],"
                + "PARAMETER[\"Scale factor at natural origin\",0.99987742,"
                + "SCALEUNIT[\"unity\",1]],"
                + "PARAMETER[\"False easting\",600000,LENGTHUNIT[\"metre\",1]],"
                + "PARAMETER[\"False northing\",2200000,LENGTHUNIT[\"metre\",1]]],"
                + "CS[Cartesian,2],AXIS[\"easting (X)\",east,ORDER[1],"
                + "LENGTHUNIT[\"metre\",1]],AXIS[\"northing (Y)\",north,ORDER[2],"
                + "LENGTHUNIT[\"metre\",1]]]";
        String p = proj(wkt);
        // 52 grad is 46.8 degrees; a reader that assumed degrees would be 5.2 degrees out.
        assertTrue(p, p.contains("+lat_0=46.8"));
        assertTrue(p, p.contains("+lat_1=46.8"));
        // Paris is one of proj4j's built-in meridians, so it is named rather than given as a
        // number — its own value is exact, where the WKT's is a rounded grad figure.
        assertTrue(p, p.contains("+pm=paris"));
        // Emitted as +ellps=clrk80ign, not +a=/+b=. WktNames matches the ellipsoid
        // NUMERICALLY against Ellipsoid.ellipsoids, and clrk80ign was added to that array
        // to fix +ellps=clrk80ign throwing "Unknown ellipsoid". The substitution is
        // exactly lossless - 6378249.2 * (1 - 1/293.4660212936269) = 6356515.0 - and the
        // nearest other entry (NAD27) is 0.055 m away, well outside A_TOLERANCE=1e-4, so
        // there is no shadowing risk.
        assertTrue(p, p.contains("+ellps=clrk80ign"));
    }

    /** CASE 152: a COMPOUNDCRS yields its horizontal component. */
    @Test
    public void compoundCrs() {
        String wkt = "COMPOUNDCRS[\"WGS 84 + EGM96 height\"," + GEODCRS_4326 + ","
                + "VERTCRS[\"EGM96 height\",VDATUM[\"EGM96 geoid\"],CS[vertical,1],"
                + "AXIS[\"gravity-related height (H)\",up,LENGTHUNIT[\"metre\",1]]]]";
        CrsDefinition def = new WktReader().readDefinition(wkt);
        assertEquals(CrsDefinition.Kind.COMPOUND, def.getKind());
        assertEquals(2, def.getComponents().size());
        assertEquals(CrsDefinition.Kind.VERTICAL, def.getComponents().get(1).getKind());
        assertEquals("+proj=longlat +datum=WGS84 +no_defs", proj(wkt));
    }

    /** CASE 159/160: a BOUNDCRS's seven-parameter abridged transformation, by name and by code. */
    @Test
    public void boundCrsSevenParameterTransformation() {
        String wkt = "BOUNDCRS[SOURCECRS[" + DATUM_73 + "],TARGETCRS[" + GEODCRS_4326 + "],"
                + "ABRIDGEDTRANSFORMATION[\"Datum 73 to WGS 84 (2)\","
                + "METHOD[\"Position Vector transformation (geog2D domain)\",ID[\"EPSG\",9606]],"
                + "PARAMETER[\"X-axis translation\",-223.237,ID[\"EPSG\",8605]],"
                + "PARAMETER[\"Y-axis translation\",110.193,ID[\"EPSG\",8606]],"
                + "PARAMETER[\"Z-axis translation\",36.649,ID[\"EPSG\",8607]],"
                + "PARAMETER[\"X-axis rotation\",-6.878,ID[\"EPSG\",8608]],"
                + "PARAMETER[\"Y-axis rotation\",-1.393,ID[\"EPSG\",8609]],"
                + "PARAMETER[\"Z-axis rotation\",-3.593,ID[\"EPSG\",8610]],"
                + "PARAMETER[\"Scale difference\",1.0000012435,ID[\"EPSG\",8611]]]]";
        CrsDefinition def = new WktReader().readDefinition(wkt);
        double[] t = def.getToWgs84();
        assertEquals(7, t.length);
        assertEquals(-223.237, t[0], 0.0);
        assertEquals(-6.878, t[3], 0.0);
        // The abridged form carries the scale as 1 + s*1e-6, and +towgs84 wants s in ppm.
        assertEquals(1.2435, t[6], 1e-9);
        assertTrue(proj(wkt),
                proj(wkt).contains("+towgs84=-223.237,110.193,36.649,-6.878,-1.393,-3.593,1.2435"));
    }

    /** A Coordinate Frame rotation is the opposite sign convention from +towgs84's. */
    @Test
    public void coordinateFrameRotationIsNegated() {
        String wkt = "BOUNDCRS[SOURCECRS[" + DATUM_73 + "],TARGETCRS[" + GEODCRS_4326 + "],"
                + "ABRIDGEDTRANSFORMATION[\"x\","
                + "METHOD[\"Coordinate Frame rotation (geog2D domain)\",ID[\"EPSG\",9607]],"
                + "PARAMETER[\"X-axis translation\",1,ID[\"EPSG\",8605]],"
                + "PARAMETER[\"Y-axis translation\",2,ID[\"EPSG\",8606]],"
                + "PARAMETER[\"Z-axis translation\",3,ID[\"EPSG\",8607]],"
                + "PARAMETER[\"X-axis rotation\",4,ID[\"EPSG\",8608]],"
                + "PARAMETER[\"Y-axis rotation\",5,ID[\"EPSG\",8609]],"
                + "PARAMETER[\"Z-axis rotation\",6,ID[\"EPSG\",8610]],"
                + "PARAMETER[\"Scale difference\",1.000007,ID[\"EPSG\",8611]]]]";
        double[] t = new WktReader().readDefinition(wkt).getToWgs84();
        assertEquals(-4.0, t[3], 1e-12);
        assertEquals(-5.0, t[4], 1e-12);
        assertEquals(-6.0, t[5], 1e-12);
        assertEquals(7.0, t[6], 1e-9);
    }

    /** CASE 144: a VERTCRS is read and retained. */
    @Test
    public void verticalCrs() {
        String wkt = "VERTCRS[\"ODN height\",VDATUM[\"Ordnance Datum Newlyn\"],CS[vertical,1],"
                + "AXIS[\"gravity-related height (H)\",up,LENGTHUNIT[\"metre\",1]]]";
        CrsDefinition def = new WktReader().readDefinition(wkt);
        assertEquals(CrsDefinition.Kind.VERTICAL, def.getKind());
        assertEquals("Ordnance Datum Newlyn", def.getDatum().getName());
        assertEquals("gravity-related height", def.getCoordinateSystem().getAxes().get(0)
                .getName());
        assertEquals("H", def.getCoordinateSystem().getAxes().get(0).getAbbreviation());
    }

    /** CASE 119: a spherical planetocentric GEODCRS is geocentric, not geographic. */
    @Test
    public void sphericalPlanetocentric() {
        String wkt = "GEODCRS[\"Mercury (2015) / Ographic\","
                + "DATUM[\"Mercury (2015)\",ELLIPSOID[\"Mercury (2015)\",2440530,1075.12334801762,"
                + "LENGTHUNIT[\"metre\",1]],ANCHOR[\"Hun Kal: 20 W\"]],"
                + "PRIMEM[\"Reference Meridian\",0,ANGLEUNIT[\"degree\",0.0174532925199433]],"
                + "CS[spherical,2],"
                + "AXIS[\"planetocentric latitude (U)\",north,ORDER[1],"
                + "ANGLEUNIT[\"degree\",0.0174532925199433]],"
                + "AXIS[\"planetocentric longitude (V)\",east,ORDER[2],"
                + "ANGLEUNIT[\"degree\",0.0174532925199433]]]";
        CrsDefinition def = new WktReader().readDefinition(wkt);
        assertEquals("Hun Kal: 20 W", def.getDatum().getAnchor());
        assertEquals("spherical", def.getCoordinateSystem().getType());
    }

    /** CASE 105: a DYNAMIC frame's epoch is retained for round-tripping. */
    @Test
    public void dynamicReferenceFrame() {
        String wkt = "GEOGCRS[\"WGS 84 (G1762)\",DYNAMIC[FRAMEEPOCH[2005.0]],"
                + "TRF[\"World Geodetic System 1984 (G1762)\","
                + "ELLIPSOID[\"WGS 84\",6378137,298.257223563,LENGTHUNIT[\"metre\",1]]],"
                + "PRIMEM[\"Greenwich\",0,ANGLEUNIT[\"degree\",0.0174532925199433]],"
                + "CS[ellipsoidal,2],AXIS[\"latitude\",north,ORDER[1],"
                + "ANGLEUNIT[\"degree\",0.0174532925199433]],AXIS[\"longitude\",east,ORDER[2],"
                + "ANGLEUNIT[\"degree\",0.0174532925199433]]]";
        CrsDefinition def = new WktReader().readDefinition(wkt);
        assertTrue(def.getDatum().isDynamic());
        assertEquals(2005.0, def.getDatum().getFrameEpoch(), 0.0);
        assertEquals(WktDialect.WKT2_2019, WktDialect.guess(wkt));
    }

    /** CASE 181: a BBOX must have four values; a malformed USAGE is an error. */
    @Test
    public void usageAndBoundingBox() {
        String wkt = GEODCRS_4326.replace("    ID[\"EPSG\",4326]]",
                "    USAGE[SCOPE[\"Horizontal component of 3D system.\"],AREA[\"World.\"],"
                        + "BBOX[-90,-180,90,180]],ID[\"EPSG\",4326]]");
        CrsDefinition def = new WktReader().readDefinition(wkt);
        assertEquals("World.", def.getAreaDescription());
        assertEquals("Horizontal component of 3D system.", def.getScope());
        assertEquals(-90.0, def.getBoundingBox()[0], 0.0);
        assertEquals(180.0, def.getBoundingBox()[3], 0.0);
        assertEquals(new Identifier("EPSG", "4326"), def.getId());
    }

    /** CASE 186: a PROJCRS with no CONVERSION, or no base CRS, is rejected. */
    @Test
    public void invalidWkt2IsRejected() {
        String[] invalid = {
                "PROJCRS[\"x\"]",
                "PROJCRS[\"x\",BASEGEOGCRS[\"y\",DATUM[\"z\",ELLIPSOID[\"e\",6378137,"
                        + "298.257223563]]],CS[Cartesian,2],AXIS[\"(E)\",east],"
                        + "AXIS[\"(N)\",north],LENGTHUNIT[\"metre\",1]]",
                "GEOGCRS[\"x\",CS[ellipsoidal,2],AXIS[\"latitude\",north],"
                        + "AXIS[\"longitude\",east],ANGLEUNIT[\"degree\",0.0174532925199433]]",
                "TIMECRS[\"t\",TDATUM[\"Gregorian\"],CS[temporal,1],AXIS[\"time\",future]]",
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

    /** An Equidistant Cylindrical with a real standard parallel is refused, not mis-projected. */
    @Test
    public void equidistantCylindricalWithStandardParallelIsRefused() {
        String wkt = "PROJCRS[\"x\",BASEGEOGCRS[\"WGS 84\","
                + "DATUM[\"World Geodetic System 1984\","
                + "ELLIPSOID[\"WGS 84\",6378137,298.257223563]],PRIMEM[\"Greenwich\",0]],"
                + "CONVERSION[\"c\",METHOD[\"Equidistant Cylindrical\",ID[\"EPSG\",1028]],"
                + "PARAMETER[\"Latitude of 1st standard parallel\",30,"
                + "ANGLEUNIT[\"degree\",0.0174532925199433]],"
                + "PARAMETER[\"Longitude of natural origin\",0,"
                + "ANGLEUNIT[\"degree\",0.0174532925199433]],"
                + "PARAMETER[\"False easting\",0,LENGTHUNIT[\"metre\",1]],"
                + "PARAMETER[\"False northing\",0,LENGTHUNIT[\"metre\",1]]],"
                + "CS[Cartesian,2],AXIS[\"(E)\",east],AXIS[\"(N)\",north],"
                + "LENGTHUNIT[\"metre\",1]]";
        try {
            proj(wkt);
            fail("expected a refusal: proj4j's eqc ignores the standard parallel");
        } catch (WktParseException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("ignores"));
        }
    }

    /** Both bracket flavours and mixed case keywords are accepted. */
    @Test
    public void bracketsAndCaseAreFlexible() {
        String wkt = "geodcrs(\"WGS 84\",datum(\"World Geodetic System 1984\","
                + "ellipsoid(\"WGS 84\",6378137,298.257223563)),cs(ellipsoidal,2),"
                + "axis(\"latitude\",north),axis(\"longitude\",east),"
                + "unit(\"degree\",0.0174532925199433))";
        assertEquals("+proj=longlat +datum=WGS84 +no_defs", proj(wkt));
    }
}

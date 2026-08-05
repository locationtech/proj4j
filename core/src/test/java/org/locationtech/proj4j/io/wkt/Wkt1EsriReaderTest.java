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
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.BasicCoordinateTransform;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * The WKT1 reader, in the ESRI dialect — which is what a consumer receiving CRS definitions from
 * ArcGIS, from a shapefile {@code .prj} or from an Oracle column actually gets.
 * <p>
 * Dialect detection follows PROJ's {@code WKTParser::guessDialect} exactly (9.8.1
 * {@code src/iso19111/io.cpp}), including its carve-out for {@code rectified_grid_angle}.
 * Projection and parameter names come from PROJ's {@code esriparammappings.cpp}.
 */
public class Wkt1EsriReaderTest {

    private static String proj(String wkt) {
        return CrsDefinitions.toProjParameterString(new WktReader().readDefinition(wkt),
                AxisOrderPolicy.LEGACY);
    }

    /**
     * PROJ's rule: a WKT1 root with a {@code GEOGCS["GCS_...]} is ESRI; so is one with neither
     * {@code AXIS[} nor {@code AUTHORITY[}. A GDAL string with axes or authorities is not.
     */
    @Test
    public void dialectDetection() {
        assertEquals(WktDialect.WKT1_ESRI, WktDialect.guess(esriUtm11()));
        assertEquals(WktDialect.WKT1_GDAL, WktDialect.guess(Wkt1ReaderTest.WGS84_GEOGCS));
        // A leading VERTCS is always ESRI.
        assertEquals(WktDialect.WKT1_ESRI, WktDialect.guess(
                "VERTCS[\"NAVD_1988\",VDATUM[\"North_American_Vertical_Datum_1988\"],"
                        + "PARAMETER[\"Vertical_Shift\",0.0],PARAMETER[\"Direction\",1.0],"
                        + "UNIT[\"Meter\",1.0]]"));
        // PROJ issue #3279: rectified_grid_angle forces GDAL even with no AXIS or AUTHORITY,
        // because Hotine_Oblique_Mercator_Azimuth_Center exists in both dialects and the ESRI
        // reading of it would drop that parameter.
        assertEquals(WktDialect.WKT1_GDAL, WktDialect.guess(
                "PROJCS[\"x\",GEOGCS[\"y\",DATUM[\"z\",SPHEROID[\"s\",6378137,298.257223563]],"
                        + "PRIMEM[\"Greenwich\",0],UNIT[\"degree\",0.0174532925199433]],"
                        + "PROJECTION[\"Hotine_Oblique_Mercator_Azimuth_Center\"],"
                        + "PARAMETER[\"rectified_grid_angle\",30],UNIT[\"metre\",1]]"));
    }

    /**
     * An ESRI UTM zone: {@code D_}-prefixed datum, {@code Degree} unit, Title_Case parameters. The
     * PROJ string is identical to the one the GDAL spelling of the same CRS produces, because
     * parameters are emitted in a canonical order rather than the document's.
     */
    @Test
    public void esriUtmZone() {
        assertEquals("+proj=tmerc +lat_0=0 +lon_0=-117 +k_0=0.9996 +x_0=500000 +y_0=0 "
                + "+datum=NAD83 +units=m +no_defs", proj(esriUtm11()));
    }

    private static String esriUtm11() {
        return "PROJCS[\"NAD_1983_UTM_Zone_11N\",GEOGCS[\"GCS_North_American_1983\","
                + "DATUM[\"D_North_American_1983\",SPHEROID[\"GRS_1980\",6378137.0,"
                + "298.257222101]],PRIMEM[\"Greenwich\",0.0],"
                + "UNIT[\"Degree\",0.0174532925199433]],PROJECTION[\"Transverse_Mercator\"],"
                + "PARAMETER[\"False_Easting\",500000.0],PARAMETER[\"False_Northing\",0.0],"
                + "PARAMETER[\"Central_Meridian\",-117.0],PARAMETER[\"Scale_Factor\",0.9996],"
                + "PARAMETER[\"Latitude_Of_Origin\",0.0],UNIT[\"Meter\",1.0]]";
    }

    /** CASE 81: ESRI projection and parameter names are matched case-insensitively. */
    @Test
    public void namesAreCaseInsensitive() {
        String wkt = "PROJCS[\"WGS_1984_UTM_Zone_31N\",GEOGCS[\"GCS_WGS_1984\","
                + "DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\",6378137.0,298.257223563]],"
                + "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]],"
                + "PROJECTION[\"transverse_mercator\"],PARAMETER[\"false_easting\",500000.0],"
                + "PARAMETER[\"FALSE_NORTHING\",0.0],PARAMETER[\"Central_meridian\",3.0],"
                + "PARAMETER[\"Scale_factor\",0.9996],PARAMETER[\"latitude_of_origin\",0.0],"
                + "UNIT[\"Meter\",1.0]]";
        assertEquals("+proj=tmerc +lat_0=0 +lon_0=3 +k_0=0.9996 +x_0=500000 +y_0=0 +datum=WGS84 "
                + "+units=m +no_defs", proj(wkt));
    }

    /**
     * The web Mercator every tile server uses. ESRI spells it {@code Mercator_Auxiliary_Sphere}
     * with {@code Auxiliary_Sphere_Type} 0, meaning "project onto a sphere of the semi-major
     * axis"; that must become an explicit spherical ellipsoid, because keeping the WGS 84
     * flattening would put every coordinate up to 20 km out.
     */
    @Test
    public void mercatorAuxiliarySphereIsSpherical() {
        String wkt = "PROJCS[\"WGS_1984_Web_Mercator_Auxiliary_Sphere\",GEOGCS[\"GCS_WGS_1984\","
                + "DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\",6378137.0,298.257223563]],"
                + "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]],"
                + "PROJECTION[\"Mercator_Auxiliary_Sphere\"],"
                + "PARAMETER[\"False_Easting\",0.0],PARAMETER[\"False_Northing\",0.0],"
                + "PARAMETER[\"Central_Meridian\",0.0],PARAMETER[\"Standard_Parallel_1\",0.0],"
                + "PARAMETER[\"Auxiliary_Sphere_Type\",0.0],UNIT[\"Meter\",1.0]]";
        assertEquals("+proj=merc +lon_0=0 +x_0=0 +y_0=0 +a=6378137 +b=6378137 +units=m +no_defs",
                proj(wkt));

        // And it really is EPSG:3857: the result must match the spherical Mercator formula on a
        // sphere of radius 6378137, not the ellipsoidal one. At this latitude the two differ by
        // about 21 km, so this is not a tolerance question.
        CoordinateReferenceSystem crs = new WktReader().read(wkt);
        CoordinateReferenceSystem wgs84 =
                new CRSFactory().createFromParameters("wgs84", "+proj=longlat +datum=WGS84");
        ProjCoordinate out = new BasicCoordinateTransform(wgs84, crs)
                .transform(new ProjCoordinate(-122.4, 37.8), new ProjCoordinate());
        double r = 6378137.0;
        double expectedX = r * Math.toRadians(-122.4);
        double expectedY = r * Math.log(Math.tan(Math.PI / 4 + Math.toRadians(37.8) / 2));
        assertEquals(expectedX, out.x, 0.001);
        assertEquals(expectedY, out.y, 0.001);
    }

    /** An Auxiliary_Sphere_Type this library has no equivalent for is refused, not approximated. */
    @Test
    public void unsupportedAuxiliarySphereTypeIsRefused() {
        String wkt = "PROJCS[\"x\",GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\","
                + "SPHEROID[\"WGS_1984\",6378137.0,298.257223563]],PRIMEM[\"Greenwich\",0.0],"
                + "UNIT[\"Degree\",0.0174532925199433]],"
                + "PROJECTION[\"Mercator_Auxiliary_Sphere\"],"
                + "PARAMETER[\"Auxiliary_Sphere_Type\",2.0],UNIT[\"Meter\",1.0]]";
        try {
            proj(wkt);
            fail("expected a WktParseException");
        } catch (WktParseException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("Auxiliary_Sphere_Type"));
        }
    }

    /** ESRI's {@code Stereographic_North_Pole} is Polar Stereographic variant B. */
    @Test
    public void stereographicNorthPoleIsPolarVariantB() {
        String wkt = "PROJCS[\"WGS_1984_Arctic_Polar_Stereographic\",GEOGCS[\"GCS_WGS_1984\","
                + "DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\",6378137.0,298.257223563]],"
                + "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]],"
                + "PROJECTION[\"Stereographic_North_Pole\"],"
                + "PARAMETER[\"False_Easting\",0.0],PARAMETER[\"False_Northing\",0.0],"
                + "PARAMETER[\"Central_Meridian\",0.0],"
                + "PARAMETER[\"Standard_Parallel_1\",71.0],UNIT[\"Meter\",1.0]]";
        // +lat_0=90 is derived from the sign of the standard parallel, exactly as PROJ derives it;
        // without it +proj=stere would not be polar at all.
        assertEquals("+proj=stere +lat_0=90 +lon_0=0 +lat_ts=71 +x_0=0 +y_0=0 +datum=WGS84 "
                + "+units=m +no_defs", proj(wkt));
    }

    /** ESRI's southern sibling derives {@code +lat_0=-90}. */
    @Test
    public void stereographicSouthPole() {
        String wkt = "PROJCS[\"Antarctic_Polar_Stereographic\",GEOGCS[\"GCS_WGS_1984\","
                + "DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\",6378137.0,298.257223563]],"
                + "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]],"
                + "PROJECTION[\"Stereographic_South_Pole\"],"
                + "PARAMETER[\"False_Easting\",0.0],PARAMETER[\"False_Northing\",0.0],"
                + "PARAMETER[\"Central_Meridian\",0.0],"
                + "PARAMETER[\"Standard_Parallel_1\",-71.0],UNIT[\"Meter\",1.0]]";
        assertTrue(proj(wkt), proj(wkt).contains("+lat_0=-90"));
    }

    /** ESRI's {@code Gauss_Kruger} is Transverse Mercator under another name. */
    @Test
    public void gaussKrugerIsTransverseMercator() {
        String wkt = "PROJCS[\"Pulkovo_1942_GK_Zone_4\",GEOGCS[\"GCS_Pulkovo_1942\","
                + "DATUM[\"D_Pulkovo_1942\",SPHEROID[\"Krasovsky_1940\",6378245.0,298.3]],"
                + "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]],"
                + "PROJECTION[\"Gauss_Kruger\"],PARAMETER[\"False_Easting\",4500000.0],"
                + "PARAMETER[\"False_Northing\",0.0],PARAMETER[\"Central_Meridian\",21.0],"
                + "PARAMETER[\"Scale_Factor\",1.0],PARAMETER[\"Latitude_Of_Origin\",0.0],"
                + "UNIT[\"Meter\",1.0]]";
        assertEquals("+proj=tmerc +lat_0=0 +lon_0=21 +k_0=1 +x_0=4500000 +y_0=0 +ellps=krass "
                + "+units=m +no_defs", proj(wkt));
    }

    /** ESRI's {@code Double_Stereographic} is Oblique Stereographic, {@code +proj=sterea}. */
    @Test
    public void doubleStereographicIsSterea() {
        String wkt = "PROJCS[\"RD_New\",GEOGCS[\"GCS_Amersfoort\",DATUM[\"D_Amersfoort\","
                + "SPHEROID[\"Bessel_1841\",6377397.155,299.1528128]],PRIMEM[\"Greenwich\",0.0],"
                + "UNIT[\"Degree\",0.0174532925199433]],PROJECTION[\"Double_Stereographic\"],"
                + "PARAMETER[\"False_Easting\",155000.0],"
                + "PARAMETER[\"False_Northing\",463000.0],"
                + "PARAMETER[\"Central_Meridian\",5.387638888888889],"
                + "PARAMETER[\"Scale_Factor\",0.9999079],"
                + "PARAMETER[\"Latitude_Of_Origin\",52.15616055555555],UNIT[\"Meter\",1.0]]";
        String p = proj(wkt);
        assertTrue(p, p.startsWith("+proj=sterea "));
        assertTrue(p, p.contains("+k_0=0.9999079"));
        assertTrue(p, p.contains("+ellps=bessel"));
    }

    /** ESRI's Swiss CRS degenerates to {@code +proj=somerc}, as PROJ's exporter does. */
    @Test
    public void swissObliqueMercatorDegeneratesToSomerc() {
        String wkt = "PROJCS[\"CH1903_LV03\",GEOGCS[\"GCS_CH1903\",DATUM[\"D_CH1903\","
                + "SPHEROID[\"Bessel_1841\",6377397.155,299.1528128]],PRIMEM[\"Greenwich\",0.0],"
                + "UNIT[\"Degree\",0.0174532925199433]],"
                + "PROJECTION[\"Hotine_Oblique_Mercator_Azimuth_Center\"],"
                + "PARAMETER[\"False_Easting\",600000.0],"
                + "PARAMETER[\"False_Northing\",200000.0],"
                + "PARAMETER[\"Scale_Factor\",1.0],PARAMETER[\"Azimuth\",90.0],"
                + "PARAMETER[\"Longitude_Of_Center\",7.439583333333333],"
                + "PARAMETER[\"Latitude_Of_Center\",46.95240555555556],UNIT[\"Meter\",1.0]]";
        String p = proj(wkt);
        assertTrue(p, p.startsWith("+proj=somerc "));
        assertTrue(p, p.contains("+lat_0=46.9524055555556"));
        assertTrue(p, p.contains("+lon_0=7.43958333333333"));
        assertTrue("alpha and gamma are absorbed by somerc", !p.contains("+alpha"));

        // The projection centre must land on the false origin, to the millimetre.
        CoordinateReferenceSystem crs = new WktReader().read(wkt);
        CoordinateReferenceSystem ch1903 = new CRSFactory().createFromParameters("ch1903geog",
                "+proj=longlat +ellps=bessel");
        ProjCoordinate out = new BasicCoordinateTransform(ch1903, crs).transform(
                new ProjCoordinate(7.439583333333333, 46.95240555555556), new ProjCoordinate());
        assertEquals(600000.0, out.x, 0.001);
        assertEquals(200000.0, out.y, 0.001);
    }

    /** ESRI's Lambert_Conformal_Conic is resolved to 1SP or 2SP by which parameters it carries. */
    @Test
    public void lambertConformalConicVariantFromParameters() {
        String twoParallels = "PROJCS[\"x\",GEOGCS[\"GCS_North_American_1983\","
                + "DATUM[\"D_North_American_1983\",SPHEROID[\"GRS_1980\",6378137.0,"
                + "298.257222101]],PRIMEM[\"Greenwich\",0.0],"
                + "UNIT[\"Degree\",0.0174532925199433]],"
                + "PROJECTION[\"Lambert_Conformal_Conic\"],"
                + "PARAMETER[\"False_Easting\",0.0],PARAMETER[\"False_Northing\",0.0],"
                + "PARAMETER[\"Central_Meridian\",-96.0],"
                + "PARAMETER[\"Standard_Parallel_1\",33.0],"
                + "PARAMETER[\"Standard_Parallel_2\",45.0],"
                + "PARAMETER[\"Latitude_Of_Origin\",39.0],UNIT[\"Meter\",1.0]]";
        assertEquals("+proj=lcc +lat_0=39 +lon_0=-96 +lat_1=33 +lat_2=45 +x_0=0 +y_0=0 "
                + "+datum=NAD83 +units=m +no_defs", proj(twoParallels));

        String oneParallel = "PROJCS[\"x\",GEOGCS[\"GCS_North_American_1983\","
                + "DATUM[\"D_North_American_1983\",SPHEROID[\"GRS_1980\",6378137.0,"
                + "298.257222101]],PRIMEM[\"Greenwich\",0.0],"
                + "UNIT[\"Degree\",0.0174532925199433]],"
                + "PROJECTION[\"Lambert_Conformal_Conic\"],"
                + "PARAMETER[\"False_Easting\",0.0],PARAMETER[\"False_Northing\",0.0],"
                + "PARAMETER[\"Central_Meridian\",-96.0],"
                + "PARAMETER[\"Standard_Parallel_1\",33.0],"
                + "PARAMETER[\"Scale_Factor\",1.0],UNIT[\"Meter\",1.0]]";
        // 1SP: the single standard parallel is also the latitude of origin.
        assertEquals("+proj=lcc +lat_0=33 +lon_0=-96 +lat_1=33 +k_0=1 +x_0=0 +y_0=0 "
                + "+datum=NAD83 +units=m +no_defs", proj(oneParallel));
    }

    /** An ESRI foot unit is honoured by its factor even though the name is ESRI's own. */
    @Test
    public void esriFootUnit() {
        String wkt = "PROJCS[\"x\",GEOGCS[\"GCS_North_American_1983\","
                + "DATUM[\"D_North_American_1983\",SPHEROID[\"GRS_1980\",6378137.0,"
                + "298.257222101]],PRIMEM[\"Greenwich\",0.0],"
                + "UNIT[\"Degree\",0.0174532925199433]],PROJECTION[\"Transverse_Mercator\"],"
                + "PARAMETER[\"False_Easting\",984250.0],PARAMETER[\"False_Northing\",0.0],"
                + "PARAMETER[\"Central_Meridian\",-90.0],PARAMETER[\"Scale_Factor\",0.9999],"
                + "PARAMETER[\"Latitude_Of_Origin\",0.0],"
                + "UNIT[\"Foot_US\",0.3048006096012192]]";
        assertTrue(proj(wkt), proj(wkt).contains("+units=us-ft"));
    }
}

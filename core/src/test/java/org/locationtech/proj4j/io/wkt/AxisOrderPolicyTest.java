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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.locationtech.proj4j.BasicCoordinateTransform;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * The axis-order trap, and the policy that governs it.
 * <p>
 * PROJ 6 and later honour authority axis order, so {@code EPSG:4326} is latitude-first there.
 * proj4j is longitude-first throughout and so is GeoJSON. A WKT reader that honoured every
 * {@code AXIS[]} clause would therefore silently transpose every existing caller's coordinates —
 * invisibly near the equator and the prime meridian, which is where fixtures live. So the default
 * is {@link AxisOrderPolicy#LEGACY}, the declared axes are retained rather than discarded, and the
 * policy is applied once, where a CRS is built.
 * <p>
 * There is deliberately no system property and no environment variable to change this: the tests
 * below would be meaningless if the answer depended on who launched the JVM.
 */
public class AxisOrderPolicyTest {

    /** EPSG:4326 as PROJ writes it: latitude first. */
    private static final String LAT_FIRST_4326 =
            "GEOGCRS[\"WGS 84\",DATUM[\"World Geodetic System 1984\","
                    + "ELLIPSOID[\"WGS 84\",6378137,298.257223563,LENGTHUNIT[\"metre\",1]]],"
                    + "PRIMEM[\"Greenwich\",0,ANGLEUNIT[\"degree\",0.0174532925199433]],"
                    + "CS[ellipsoidal,2],"
                    + "AXIS[\"geodetic latitude (Lat)\",north,ORDER[1],"
                    + "ANGLEUNIT[\"degree\",0.0174532925199433]],"
                    + "AXIS[\"geodetic longitude (Lon)\",east,ORDER[2],"
                    + "ANGLEUNIT[\"degree\",0.0174532925199433]],ID[\"EPSG\",4326]]";

    /** The default is longitude-first, exactly as proj4j 1.4.3 behaves. */
    @Test
    public void defaultIsLegacyLongitudeFirst() {
        assertEquals(AxisOrderPolicy.LEGACY, new WktReader().getAxisOrderPolicy());
        assertEquals("+proj=longlat +datum=WGS84 +no_defs",
                CrsDefinitions.toProjParameterString(
                        new WktReader().readDefinition(LAT_FIRST_4326), AxisOrderPolicy.LEGACY));
    }

    /** The declared order is retained whatever the policy: nothing is discarded. */
    @Test
    public void declaredAxesAreAlwaysRetained() {
        AxisOrderPolicy[] policies = AxisOrderPolicy.values();
        for (int i = 0; i < policies.length; i++) {
            CrsDefinition def = new WktReader(policies[i]).readDefinition(LAT_FIRST_4326);
            CoordinateSystemDefinition cs = def.getCoordinateSystem();
            assertEquals(2, cs.getAxes().size());
            assertEquals("geodetic latitude", cs.getAxes().get(0).getName());
            assertEquals("Lat", cs.getAxes().get(0).getAbbreviation());
            assertEquals(AxisDefinition.NORTH, cs.getAxes().get(0).getDirection());
            assertEquals(AxisDefinition.EAST, cs.getAxes().get(1).getDirection());
            assertFalse("the document declared latitude first, and says so",
                    cs.isXBeforeY());
        }
    }

    /** AUTHORITY applies the declared order, and says so in the PROJ string. */
    @Test
    public void authorityPolicyEmitsAxisOrder() {
        String proj = CrsDefinitions.toProjParameterString(
                new WktReader().readDefinition(LAT_FIRST_4326), AxisOrderPolicy.AUTHORITY);
        assertEquals("+proj=longlat +datum=WGS84 +axis=neu +no_defs", proj);
    }

    /** VISUALISATION forces east/north/up, which is proj4j's own default. */
    @Test
    public void visualisationPolicyForcesEastNorth() {
        String proj = CrsDefinitions.toProjParameterString(
                new WktReader().readDefinition(LAT_FIRST_4326), AxisOrderPolicy.VISUALISATION);
        assertTrue(proj, !proj.contains("+axis="));
    }

    /**
     * The consumer's own reproduction case, and the reason the default is what it is: reading
     * EPSG:4326 from WKT and transforming to web Mercator must consume {@code (longitude,
     * latitude)}. The literal coordinate is from that script.
     */
    @Test
    public void legacyPolicyConsumesLongitudeFirst() {
        CoordinateReferenceSystem source = new WktReader().read(LAT_FIRST_4326);
        CoordinateReferenceSystem target = new CRSFactory().createFromParameters("webmerc",
                "+proj=merc +a=6378137 +b=6378137 +lat_ts=0 +lon_0=0 +x_0=0 +y_0=0 +units=m");
        ProjCoordinate out = new BasicCoordinateTransform(source, target)
                .transform(new ProjCoordinate(-122.4, 37.8), new ProjCoordinate());
        assertEquals(6378137.0 * Math.toRadians(-122.4), out.x, 0.001);
        assertEquals(6378137.0 * Math.log(Math.tan(Math.PI / 4 + Math.toRadians(37.8) / 2)),
                out.y, 0.001);
    }

    /** Under AUTHORITY the same CRS consumes (latitude, longitude), which is the whole point. */
    @Test
    public void authorityPolicyConsumesLatitudeFirst() {
        CoordinateReferenceSystem source =
                new WktReader(AxisOrderPolicy.AUTHORITY).read(LAT_FIRST_4326);
        CoordinateReferenceSystem target = new CRSFactory().createFromParameters("webmerc",
                "+proj=merc +a=6378137 +b=6378137 +lat_ts=0 +lon_0=0 +x_0=0 +y_0=0 +units=m");
        ProjCoordinate out = new BasicCoordinateTransform(source, target)
                .transform(new ProjCoordinate(37.8, -122.4), new ProjCoordinate());
        assertEquals(6378137.0 * Math.toRadians(-122.4), out.x, 0.001);
        assertEquals(6378137.0 * Math.log(Math.tan(Math.PI / 4 + Math.toRadians(37.8) / 2)),
                out.y, 0.001);
    }

    /** A longitude-first document needs no reordering under any policy. */
    @Test
    public void longitudeFirstDocumentIsUnaffected() {
        String lonFirst = "GEOGCRS[\"WGS 84\",DATUM[\"World Geodetic System 1984\","
                + "ELLIPSOID[\"WGS 84\",6378137,298.257223563,LENGTHUNIT[\"metre\",1]]],"
                + "PRIMEM[\"Greenwich\",0,ANGLEUNIT[\"degree\",0.0174532925199433]],"
                + "CS[ellipsoidal,2],"
                + "AXIS[\"geodetic longitude (Lon)\",east,ORDER[1],"
                + "ANGLEUNIT[\"degree\",0.0174532925199433]],"
                + "AXIS[\"geodetic latitude (Lat)\",north,ORDER[2],"
                + "ANGLEUNIT[\"degree\",0.0174532925199433]],ID[\"EPSG\",4326]]";
        CrsDefinition def = new WktReader().readDefinition(lonFirst);
        assertTrue(def.getCoordinateSystem().isXBeforeY());
        assertEquals("+proj=longlat +datum=WGS84 +no_defs",
                CrsDefinitions.toProjParameterString(def, AxisOrderPolicy.AUTHORITY));
    }

    /**
     * A WKT1 document with no {@code AXIS[]} at all is longitude-first by the WKT1 convention, so
     * even AUTHORITY leaves it alone.
     */
    @Test
    public void wkt1WithoutAxesIsLongitudeFirst() {
        CrsDefinition def = new WktReader().readDefinition(Wkt1ReaderTest.WGS84_GEOGCS);
        // The WKT1 default order is made explicit rather than left as an absence.
        assertEquals(2, def.getCoordinateSystem().getAxes().size());
        assertEquals(AxisDefinition.EAST, def.getCoordinateSystem().getAxes().get(0)
                .getDirection());
        assertTrue(def.getCoordinateSystem().isXBeforeY());
        assertEquals("+proj=longlat +datum=WGS84 +no_defs",
                CrsDefinitions.toProjParameterString(def, AxisOrderPolicy.AUTHORITY));
    }

    /** A south/west projected CRS is expressible under AUTHORITY. */
    @Test
    public void southWestAxesUnderAuthority() {
        String wkt = "PROJCRS[\"S-JTSK / Krovak\",BASEGEOGCRS[\"S-JTSK\",DATUM[\"S-JTSK\","
                + "ELLIPSOID[\"Bessel 1841\",6377397.155,299.1528128,LENGTHUNIT[\"metre\",1]]],"
                + "PRIMEM[\"Greenwich\",0,ANGLEUNIT[\"degree\",0.0174532925199433]]],"
                + "CONVERSION[\"Krovak\",METHOD[\"Krovak\",ID[\"EPSG\",9819]],"
                + "PARAMETER[\"Latitude of projection centre\",49.5,"
                + "ANGLEUNIT[\"degree\",0.0174532925199433]],"
                + "PARAMETER[\"Longitude of origin\",24.8333333333333,"
                + "ANGLEUNIT[\"degree\",0.0174532925199433]],"
                + "PARAMETER[\"Co-latitude of cone axis\",30.2881397527778,"
                + "ANGLEUNIT[\"degree\",0.0174532925199433]],"
                + "PARAMETER[\"Latitude of pseudo standard parallel\",78.5,"
                + "ANGLEUNIT[\"degree\",0.0174532925199433]],"
                + "PARAMETER[\"Scale factor on pseudo standard parallel\",0.9999,"
                + "SCALEUNIT[\"unity\",1]],"
                + "PARAMETER[\"False easting\",0,LENGTHUNIT[\"metre\",1]],"
                + "PARAMETER[\"False northing\",0,LENGTHUNIT[\"metre\",1]]],"
                + "CS[Cartesian,2],AXIS[\"x\",south,ORDER[1],LENGTHUNIT[\"metre\",1]],"
                + "AXIS[\"y\",west,ORDER[2],LENGTHUNIT[\"metre\",1]],ID[\"EPSG\",5513]]";
        // The Czech convention is part of the projection here, not of the policy: +axis=swu comes
        // from the south/west axes whatever the policy is.
        String legacy = CrsDefinitions.toProjParameterString(
                new WktReader().readDefinition(wkt), AxisOrderPolicy.LEGACY);
        assertTrue(legacy, legacy.contains("+axis=swu"));
    }
}

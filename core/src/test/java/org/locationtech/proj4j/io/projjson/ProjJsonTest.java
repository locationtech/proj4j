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
package org.locationtech.proj4j.io.projjson;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.io.wkt.AxisOrderPolicy;
import org.locationtech.proj4j.io.wkt.CrsDefinition;
import org.locationtech.proj4j.io.wkt.CrsDefinitions;
import org.locationtech.proj4j.io.wkt.Identifier;
import org.locationtech.proj4j.io.wkt.WktParseException;
import org.locationtech.proj4j.io.wkt.WktReader;
import org.locationtech.proj4j.io.wkt.WktWriter;

/**
 * PROJJSON reading and writing, over inputs taken from the {@code json_import} tests in PROJ
 * 9.8.1's {@code test/unit/test_io.cpp}. Those tests assert byte-for-byte round-trip equality with
 * a placeholder {@code $schema}; so do these.
 */
public class ProjJsonTest {

    /** CASE 213: a GeographicCRS with area, bbox, id and remarks. */
    private static final String GEOGRAPHIC_CRS = ""
            + "{\n"
            + "  \"$schema\": \"foo\",\n"
            + "  \"type\": \"GeographicCRS\",\n"
            + "  \"name\": \"WGS 84\",\n"
            + "  \"datum\": {\n"
            + "    \"type\": \"GeodeticReferenceFrame\",\n"
            + "    \"name\": \"World Geodetic System 1984\",\n"
            + "    \"ellipsoid\": {\n"
            + "      \"name\": \"WGS 84\",\n"
            + "      \"semi_major_axis\": 6378137,\n"
            + "      \"inverse_flattening\": 298.257223563\n"
            + "    }\n"
            + "  },\n"
            + "  \"coordinate_system\": {\n"
            + "    \"subtype\": \"ellipsoidal\",\n"
            + "    \"axis\": [\n"
            + "      {\n"
            + "        \"name\": \"Geodetic latitude\",\n"
            + "        \"abbreviation\": \"Lat\",\n"
            + "        \"direction\": \"north\",\n"
            + "        \"unit\": \"degree\"\n"
            + "      },\n"
            + "      {\n"
            + "        \"name\": \"Geodetic longitude\",\n"
            + "        \"abbreviation\": \"Lon\",\n"
            + "        \"direction\": \"east\",\n"
            + "        \"unit\": \"degree\"\n"
            + "      }\n"
            + "    ]\n"
            + "  },\n"
            + "  \"area\": \"World\",\n"
            + "  \"bbox\": {\n"
            + "    \"south_latitude\": -90,\n"
            + "    \"west_longitude\": -180,\n"
            + "    \"north_latitude\": 90,\n"
            + "    \"east_longitude\": 180\n"
            + "  },\n"
            + "  \"id\": {\n"
            + "    \"authority\": \"EPSG\",\n"
            + "    \"code\": 4326\n"
            + "  },\n"
            + "  \"remarks\": \"my_remarks\"\n"
            + "}";

    private static ProjJsonWriter writer() {
        return new ProjJsonWriter().withSchema("foo");
    }

    @Test
    public void geographicCrsRoundTripsByteForByte() {
        CrsDefinition def = new ProjJsonReader().readDefinition(GEOGRAPHIC_CRS);
        assertEquals(GEOGRAPHIC_CRS, writer().write(def));
    }

    @Test
    public void geographicCrsContent() {
        CrsDefinition def = new ProjJsonReader().readDefinition(GEOGRAPHIC_CRS);
        assertEquals(CrsDefinition.Kind.GEOGRAPHIC, def.getKind());
        assertEquals("WGS 84", def.getName());
        assertEquals(new Identifier("EPSG", "4326"), def.getId());
        assertEquals("World", def.getAreaDescription());
        assertEquals("my_remarks", def.getRemark());
        assertEquals(-90.0, def.getBoundingBox()[0], 0.0);
        // Latitude first, retained; the policy decides what that means, not the reader.
        assertEquals("north", def.getCoordinateSystem().getAxes().get(0).getDirection());
        assertEquals("+proj=longlat +datum=WGS84 +no_defs",
                CrsDefinitions.toProjParameterString(def, AxisOrderPolicy.LEGACY));
        assertEquals("+proj=longlat +datum=WGS84 +axis=neu +no_defs",
                CrsDefinitions.toProjParameterString(def, AxisOrderPolicy.AUTHORITY));
    }

    /** CASE 218: a ProjectedCRS, UTM zone 31N. */
    private static final String PROJECTED_CRS = ""
            + "{\n"
            + "  \"$schema\": \"foo\",\n"
            + "  \"type\": \"ProjectedCRS\",\n"
            + "  \"name\": \"WGS 84 / UTM zone 31N\",\n"
            + "  \"base_crs\": {\n"
            + "    \"type\": \"GeographicCRS\",\n"
            + "    \"name\": \"WGS 84\",\n"
            + "    \"datum\": {\n"
            + "      \"type\": \"GeodeticReferenceFrame\",\n"
            + "      \"name\": \"World Geodetic System 1984\",\n"
            + "      \"ellipsoid\": {\n"
            + "        \"name\": \"WGS 84\",\n"
            + "        \"semi_major_axis\": 6378137,\n"
            + "        \"inverse_flattening\": 298.257223563\n"
            + "      }\n"
            + "    },\n"
            + "    \"coordinate_system\": {\n"
            + "      \"subtype\": \"ellipsoidal\",\n"
            + "      \"axis\": [\n"
            + "        {\n"
            + "          \"name\": \"Geodetic latitude\",\n"
            + "          \"abbreviation\": \"Lat\",\n"
            + "          \"direction\": \"north\",\n"
            + "          \"unit\": \"degree\"\n"
            + "        },\n"
            + "        {\n"
            + "          \"name\": \"Geodetic longitude\",\n"
            + "          \"abbreviation\": \"Lon\",\n"
            + "          \"direction\": \"east\",\n"
            + "          \"unit\": \"degree\"\n"
            + "        }\n"
            + "      ]\n"
            + "    },\n"
            + "    \"id\": {\n"
            + "      \"authority\": \"EPSG\",\n"
            + "      \"code\": 4326\n"
            + "    }\n"
            + "  },\n"
            + "  \"conversion\": {\n"
            + "    \"name\": \"UTM zone 31N\",\n"
            + "    \"method\": {\n"
            + "      \"name\": \"Transverse Mercator\",\n"
            + "      \"id\": {\n"
            + "        \"authority\": \"EPSG\",\n"
            + "        \"code\": 9807\n"
            + "      }\n"
            + "    },\n"
            + "    \"parameters\": [\n"
            + "      {\n"
            + "        \"name\": \"Latitude of natural origin\",\n"
            + "        \"value\": 0,\n"
            + "        \"unit\": \"degree\",\n"
            + "        \"id\": {\n"
            + "          \"authority\": \"EPSG\",\n"
            + "          \"code\": 8801\n"
            + "        }\n"
            + "      },\n"
            + "      {\n"
            + "        \"name\": \"Longitude of natural origin\",\n"
            + "        \"value\": 3,\n"
            + "        \"unit\": \"degree\",\n"
            + "        \"id\": {\n"
            + "          \"authority\": \"EPSG\",\n"
            + "          \"code\": 8802\n"
            + "        }\n"
            + "      },\n"
            + "      {\n"
            + "        \"name\": \"Scale factor at natural origin\",\n"
            + "        \"value\": 0.9996,\n"
            + "        \"unit\": \"unity\",\n"
            + "        \"id\": {\n"
            + "          \"authority\": \"EPSG\",\n"
            + "          \"code\": 8805\n"
            + "        }\n"
            + "      },\n"
            + "      {\n"
            + "        \"name\": \"False easting\",\n"
            + "        \"value\": 500000,\n"
            + "        \"unit\": \"metre\",\n"
            + "        \"id\": {\n"
            + "          \"authority\": \"EPSG\",\n"
            + "          \"code\": 8806\n"
            + "        }\n"
            + "      },\n"
            + "      {\n"
            + "        \"name\": \"False northing\",\n"
            + "        \"value\": 0,\n"
            + "        \"unit\": \"metre\",\n"
            + "        \"id\": {\n"
            + "          \"authority\": \"EPSG\",\n"
            + "          \"code\": 8807\n"
            + "        }\n"
            + "      }\n"
            + "    ]\n"
            + "  },\n"
            + "  \"coordinate_system\": {\n"
            + "    \"subtype\": \"Cartesian\",\n"
            + "    \"axis\": [\n"
            + "      {\n"
            + "        \"name\": \"Easting\",\n"
            + "        \"abbreviation\": \"E\",\n"
            + "        \"direction\": \"east\",\n"
            + "        \"unit\": \"metre\"\n"
            + "      },\n"
            + "      {\n"
            + "        \"name\": \"Northing\",\n"
            + "        \"abbreviation\": \"N\",\n"
            + "        \"direction\": \"north\",\n"
            + "        \"unit\": \"metre\"\n"
            + "      }\n"
            + "    ]\n"
            + "  }\n"
            + "}";

    @Test
    public void projectedCrsRoundTripsByteForByte() {
        CrsDefinition def = new ProjJsonReader().readDefinition(PROJECTED_CRS);
        assertEquals(PROJECTED_CRS, writer().write(def));
    }

    @Test
    public void projectedCrsBecomesTheRightProjString() {
        CrsDefinition def = new ProjJsonReader().readDefinition(PROJECTED_CRS);
        assertEquals("+proj=tmerc +lat_0=0 +lon_0=3 +k_0=0.9996 +x_0=500000 +y_0=0 +datum=WGS84 "
                        + "+units=m +no_defs",
                CrsDefinitions.toProjParameterString(def, AxisOrderPolicy.LEGACY));
        // No id on the projected CRS in this document, so the CRS keeps its name.
        CoordinateReferenceSystem crs = new ProjJsonReader().read(PROJECTED_CRS);
        assertEquals("WGS 84 / UTM zone 31N", crs.getName());
    }

    /** The same content in WKT2 and in PROJJSON produces the same definition. */
    @Test
    public void jsonAndWkt2Agree() {
        CrsDefinition fromJson = new ProjJsonReader().readDefinition(PROJECTED_CRS);
        String wkt2 = new WktWriter().write(fromJson);
        CrsDefinition fromWkt = new WktReader().readDefinition(wkt2);
        assertEquals(CrsDefinitions.toProjParameterString(fromJson, AxisOrderPolicy.LEGACY),
                CrsDefinitions.toProjParameterString(fromWkt, AxisOrderPolicy.LEGACY));
        // And back to JSON: the two writers agree on everything the model holds.
        assertEquals(writer().write(fromJson), writer().write(fromWkt));
    }

    /** CASE 190: a sphere is spelled with "radius". */
    @Test
    public void sphereByRadius() {
        String json = "{\n"
                + "  \"$schema\": \"foo\",\n"
                + "  \"type\": \"GeographicCRS\",\n"
                + "  \"name\": \"unknown\",\n"
                + "  \"datum\": {\n"
                + "    \"type\": \"GeodeticReferenceFrame\",\n"
                + "    \"name\": \"unknown\",\n"
                + "    \"ellipsoid\": {\n"
                + "      \"name\": \"Sphere\",\n"
                + "      \"radius\": 6371008.7714\n"
                + "    }\n"
                + "  },\n"
                + "  \"coordinate_system\": {\n"
                + "    \"subtype\": \"ellipsoidal\",\n"
                + "    \"axis\": [\n"
                + "      {\n"
                + "        \"name\": \"Longitude\",\n"
                + "        \"abbreviation\": \"lon\",\n"
                + "        \"direction\": \"east\",\n"
                + "        \"unit\": \"degree\"\n"
                + "      },\n"
                + "      {\n"
                + "        \"name\": \"Latitude\",\n"
                + "        \"abbreviation\": \"lat\",\n"
                + "        \"direction\": \"north\",\n"
                + "        \"unit\": \"degree\"\n"
                + "      }\n"
                + "    ]\n"
                + "  }\n"
                + "}";
        CrsDefinition def = new ProjJsonReader().readDefinition(json);
        assertTrue(def.getDatum().getEllipsoid().isSphere());
        assertEquals(json, writer().write(def));
        assertEquals("+proj=longlat +a=6371008.7714 +b=6371008.7714 +no_defs",
                CrsDefinitions.toProjParameterString(def, AxisOrderPolicy.LEGACY));
    }

    /** CASE 199 and 202: a prime meridian in grad, as an object with its own unit. */
    @Test
    public void primeMeridianWithNonDegreeUnit() {
        String json = "{\n"
                + "  \"$schema\": \"foo\",\n"
                + "  \"type\": \"GeographicCRS\",\n"
                + "  \"name\": \"NTF (Paris)\",\n"
                + "  \"datum\": {\n"
                + "    \"type\": \"GeodeticReferenceFrame\",\n"
                + "    \"name\": \"Nouvelle Triangulation Francaise (Paris)\",\n"
                + "    \"ellipsoid\": {\n"
                + "      \"name\": \"Clarke 1880 (IGN)\",\n"
                + "      \"semi_major_axis\": 6378249.2,\n"
                + "      \"semi_minor_axis\": 6356515\n"
                + "    },\n"
                + "    \"prime_meridian\": {\n"
                + "      \"name\": \"Paris\",\n"
                + "      \"longitude\": {\n"
                + "        \"value\": 2.5969213,\n"
                + "        \"unit\": {\n"
                + "          \"type\": \"AngularUnit\",\n"
                + "          \"name\": \"grad\",\n"
                + "          \"conversion_factor\": 0.015707963267949\n"
                + "        }\n"
                + "      }\n"
                + "    }\n"
                + "  },\n"
                + "  \"coordinate_system\": {\n"
                + "    \"subtype\": \"ellipsoidal\",\n"
                + "    \"axis\": [\n"
                + "      {\n"
                + "        \"name\": \"Longitude\",\n"
                + "        \"abbreviation\": \"lon\",\n"
                + "        \"direction\": \"east\",\n"
                + "        \"unit\": \"degree\"\n"
                + "      },\n"
                + "      {\n"
                + "        \"name\": \"Latitude\",\n"
                + "        \"abbreviation\": \"lat\",\n"
                + "        \"direction\": \"north\",\n"
                + "        \"unit\": \"degree\"\n"
                + "      }\n"
                + "    ]\n"
                + "  }\n"
                + "}";
        CrsDefinition def = new ProjJsonReader().readDefinition(json);
        assertEquals(2.33722917, def.getDatum().getPrimeMeridian().getLongitudeDegrees(), 1e-9);
        assertEquals(json, writer().write(def));
        String proj = CrsDefinitions.toProjParameterString(def, AxisOrderPolicy.LEGACY);
        assertTrue(proj, proj.contains("+pm=paris"));
        // See Wkt2ReaderTest.baseAngularUnitFromPrimeMeridian: now named rather than
        // given as axes, because clrk80ign is in Ellipsoid.ellipsoids and WktNames
        // matches numerically. Lossless.
        assertTrue(proj, proj.contains("+ellps=clrk80ign"));
    }

    /** CASE 220: a VerticalCRS is read, and refused as a proj4j CRS rather than flattened. */
    @Test
    public void verticalCrs() {
        String json = "{\n"
                + "  \"$schema\": \"foo\",\n"
                + "  \"type\": \"VerticalCRS\",\n"
                + "  \"name\": \"ODN height\",\n"
                + "  \"datum\": {\n"
                + "    \"type\": \"VerticalReferenceFrame\",\n"
                + "    \"name\": \"Ordnance Datum Newlyn\"\n"
                + "  },\n"
                + "  \"coordinate_system\": {\n"
                + "    \"subtype\": \"vertical\",\n"
                + "    \"axis\": [\n"
                + "      {\n"
                + "        \"name\": \"Gravity-related height\",\n"
                + "        \"abbreviation\": \"H\",\n"
                + "        \"direction\": \"up\",\n"
                + "        \"unit\": \"metre\"\n"
                + "      }\n"
                + "    ]\n"
                + "  }\n"
                + "}";
        CrsDefinition def = new ProjJsonReader().readDefinition(json);
        assertEquals(CrsDefinition.Kind.VERTICAL, def.getKind());
        assertEquals(json, writer().write(def));
        try {
            new ProjJsonReader().read(json);
            fail("a vertical-only CRS must be refused");
        } catch (WktParseException expected) {
            // the point
        }
    }

    /** CASE 227/230: identifiers, singular and plural. */
    @Test
    public void multipleIdentifiers() {
        String json = GEOGRAPHIC_CRS.replace(""
                        + "  \"id\": {\n"
                        + "    \"authority\": \"EPSG\",\n"
                        + "    \"code\": 4326\n"
                        + "  },\n",
                ""
                        + "  \"ids\": [\n"
                        + "    {\n"
                        + "      \"authority\": \"EPSG\",\n"
                        + "      \"code\": 4326\n"
                        + "    },\n"
                        + "    {\n"
                        + "      \"authority\": \"FOO\",\n"
                        + "      \"code\": \"BAR\"\n"
                        + "    }\n"
                        + "  ],\n");
        CrsDefinition def = new ProjJsonReader().readDefinition(json);
        assertEquals(2, def.getIds().size());
        assertEquals(new Identifier("FOO", "BAR"), def.getIds().get(1));
        assertEquals(json, writer().write(def));
    }

    /** A default writer emits the schema PROJ 9.8.1 emits. */
    @Test
    public void defaultSchemaIsEmitted() {
        CrsDefinition def = new ProjJsonReader().readDefinition(GEOGRAPHIC_CRS);
        String json = new ProjJsonWriter().write(def);
        assertTrue(json, json.startsWith("{\n  \"$schema\": \""
                + ProjJsonWriter.DEFAULT_SCHEMA + "\",\n"));
        // And with no schema at all, "type" comes first.
        assertTrue(new ProjJsonWriter().withSchema(null).write(def)
                .startsWith("{\n  \"type\": \"GeographicCRS\","));
    }

    /** Malformed or unrepresentable JSON is refused with a message, never half-read. */
    @Test
    public void invalidJsonIsRejected() {
        String[] invalid = {
                "",
                "{",
                "[]",
                "{\"type\": \"Ellipsoid\"}",
                "{\"type\": \"PrimeMeridian\", \"name\": \"foo\"}",
                "{\"type\": \"GeographicCRS\", \"name\": \"x\"}",
                "{\"type\": \"GeographicCRS\", \"name\": \"x\", \"datum\": {"
                        + "\"type\": \"GeodeticReferenceFrame\", \"name\": \"y\"}}",
                "{\"type\": \"NotACrs\", \"name\": \"x\"}",
                "{\"type\": \"GeographicCRS\", \"name\": 42}",
        };
        for (int i = 0; i < invalid.length; i++) {
            try {
                new ProjJsonReader().readDefinition(invalid[i]);
                fail("expected a WktParseException for \"" + invalid[i] + "\"");
            } catch (WktParseException expected) {
                // the point
            }
        }
    }

    /** The JSON parser handles escapes, nesting and numeric forms. */
    @Test
    public void jsonParserBasics() {
        assertEquals("a\"b\\c\nd", ((java.util.Map<?, ?>) Json.parse("{\"k\":\"a\\\"b\\\\c\\nd\"}"))
                .get("k"));
        assertEquals(Double.valueOf(-1.5e10), ((java.util.Map<?, ?>) Json.parse("{\"k\":-1.5e10}"))
                .get("k"));
        assertEquals(Boolean.TRUE, ((java.util.Map<?, ?>) Json.parse("{\"k\":true}")).get("k"));
        assertEquals(null, ((java.util.Map<?, ?>) Json.parse("{\"k\":null}")).get("k"));
        assertEquals(3, ((java.util.List<?>) Json.parse("[1,2,3]")).size());
        assertEquals("\u00e9", ((java.util.Map<?, ?>) Json.parse("{\"k\":\"\\u00e9\"}")).get("k"));
    }

    /** Numbers are written as PROJ writes them: integral values without a decimal point. */
    @Test
    public void numberFormatting() {
        assertEquals("0", JsonNumber.format(0.0));
        assertEquals("6378137", JsonNumber.format(6378137.0));
        assertEquals("298.257223563", JsonNumber.format(298.257223563));
        assertEquals("0.9996", JsonNumber.format(0.9996));
        assertEquals("-180", JsonNumber.format(-180.0));
    }

    /** Writing a bare proj4j CRS produces valid PROJJSON. */
    @Test
    public void writesAProj4jCrs() {
        CoordinateReferenceSystem crs = new org.locationtech.proj4j.CRSFactory()
                .createFromParameters("utm31", "+proj=tmerc +lat_0=0 +lon_0=3 +k_0=0.9996 "
                        + "+x_0=500000 +y_0=0 +datum=WGS84 +units=m");
        String json = new ProjJsonWriter().withSchema("foo").write(crs);
        CrsDefinition reread = new ProjJsonReader().readDefinition(json);
        assertEquals("+proj=tmerc +lat_0=0 +lon_0=3 +k_0=0.9996 +x_0=500000 +y_0=0 +datum=WGS84 "
                        + "+units=m +no_defs",
                CrsDefinitions.toProjParameterString(reread, AxisOrderPolicy.LEGACY));
        assertEquals(json, new ProjJsonWriter().withSchema("foo").write(reread));
    }
}

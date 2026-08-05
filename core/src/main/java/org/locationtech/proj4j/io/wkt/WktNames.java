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

import java.util.HashMap;
import java.util.Map;

import org.locationtech.proj4j.datum.Datum;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.units.Unit;
import org.locationtech.proj4j.units.Units;

/**
 * Name matching and the small amount of vocabulary this package needs: how loosely two names are
 * compared, which unit a name denotes, and which of proj4j's built-in ellipsoids and datums a
 * document is describing.
 * <p>
 * PROJ resolves all of this against {@code proj.db}. This library has no database, so the rules
 * here are deliberately conservative: units are resolved by their declared <em>conversion
 * factor</em> first and by name only as a fallback, and an ellipsoid is matched
 * <em>numerically</em> against {@link Ellipsoid#ellipsoids} rather than by name. A document
 * describing GRS 1980 therefore yields {@code +ellps=GRS80} whatever it calls it, and a document
 * describing something proj4j has no name for yields explicit {@code +a=} / {@code +rf=} rather
 * than a wrong name.
 */
final class WktNames {

    private WktNames() {
    }

    /** Tolerance for matching a semi-major axis, in metres. */
    private static final double A_TOLERANCE = 1e-4;
    /**
     * Tolerance for matching an inverse flattening, absolute. Deliberately tight: GRS 1980
     * (298.257222101) and WGS 84 (298.257223563) differ by only 1.5e-6 and must not be conflated,
     * because the datum inferred from the ellipsoid name differs. A document which rounds the
     * value to seven decimal places still matches.
     */
    private static final double RF_TOLERANCE = 1e-7;

    /**
     * Compares two names ignoring case, spaces, underscores and hyphens. This is how WKT1 and
     * ESRI spellings of the same thing are reconciled: {@code false_easting},
     * {@code False_Easting} and {@code "False easting"} are one name.
     */
    static boolean equalsRelaxed(String a, String b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return normalize(a).equals(normalize(b));
    }

    /**
     * Lower-cases and removes spaces, underscores, hyphens, apostrophes and full stops.
     */
    static String normalize(String s) {
        if (s == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '_' || c == '-' || c == '\'' || c == '.' || c == '(' || c == ')') {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ units

    private static final Map<String, Double> LINEAR_BY_NAME = new HashMap<String, Double>();
    private static final Map<String, Double> ANGULAR_BY_NAME = new HashMap<String, Double>();

    static {
        // Names as WKT1, ESRI WKT and WKT2 spell them; factors as PROJ's UnitOfMeasure defines
        // them (9.8.1 src/iso19111/common.cpp).
        linear("metre", 1.0);
        linear("meter", 1.0);
        linear("m", 1.0);
        linear("kilometre", 1000.0);
        linear("kilometer", 1000.0);
        linear("km", 1000.0);
        linear("decimetre", 0.1);
        linear("centimetre", 0.01);
        linear("millimetre", 0.001);
        linear("foot", 0.3048);
        linear("feet", 0.3048);
        linear("ft", 0.3048);
        linear("international foot", 0.3048);
        linear("US survey foot", 12.0 / 39.37);
        linear("Foot_US", 12.0 / 39.37);
        linear("us survey feet", 12.0 / 39.37);
        linear("usft", 12.0 / 39.37);
        linear("Clarke's foot", 0.3047972654);
        linear("Foot_Clarke", 0.3047972654);
        linear("Indian yard", 0.9143985307444408);
        linear("Yard_Indian", 0.9143985307444408);
        linear("yard", 0.9144);
        linear("Foot_Gold_Coast", 0.3047997101815088);
        linear("Gold Coast foot", 0.3047997101815088);
        linear("British chain (Sears 1922 truncated)", 20.116756);
        linear("Chain", 20.1168);
        linear("chain", 20.1168);
        linear("link", 0.201168);
        linear("fathom", 1.8288);
        linear("nautical mile", 1852.0);
        linear("Statute Mile", 1609.344);
        linear("mile", 1609.344);
        linear("inch", 0.0254);

        angular("radian", 1.0);
        angular("rad", 1.0);
        angular("degree", Math.PI / 180.0);
        angular("degrees", Math.PI / 180.0);
        angular("Degree", Math.PI / 180.0);
        angular("deg", Math.PI / 180.0);
        angular("arc-second", Math.PI / 648000.0);
        angular("arc-minute", Math.PI / 10800.0);
        angular("grad", Math.PI / 200.0);
        angular("gradian", Math.PI / 200.0);
        angular("Gon", Math.PI / 200.0);
        angular("microradian", 1e-6);
        angular("sexagesimal DMS", Math.PI / 180.0);
    }

    /**
     * Repairs the rounding in a declared conversion factor, exactly as PROJ's
     * {@code WKTParser::Private::buildUnit} does (9.8.1 {@code src/iso19111/io.cpp}) with the same
     * relative tolerance of 1e-10: the ubiquitous {@code 0.0174532925199433} becomes pi/180
     * exactly, and {@code 0.30480060960121924} becomes 12/39.37 exactly.
     * <p>
     * This is not cosmetic. Without it, converting 3 degrees to radians and back yields
     * 3.0000000000000004, so a PROJ string built from WKT would differ from the same string built
     * from an EPSG code, and every round-trip comparison would fail on the last digit.
     */
    private static double repair(double factor) {
        double degree = Math.PI / 180.0;
        if (Math.abs(factor - degree) < 1e-10 * degree) {
            return degree;
        }
        double usFoot = 12.0 / 39.37;
        if (Math.abs(factor - usFoot) < 1e-10 * usFoot) {
            return usFoot;
        }
        return factor;
    }

    private static void linear(String name, double factor) {
        LINEAR_BY_NAME.put(normalize(name), Double.valueOf(factor));
    }

    private static void angular(String name, double factor) {
        ANGULAR_BY_NAME.put(normalize(name), Double.valueOf(factor));
    }

    /**
     * Builds a unit from a declared name and factor. The factor wins when it is present and
     * positive; when it is not, the name is looked up; when neither works this throws, because a
     * silently-assumed unit is a coordinate wrong by a factor of 3.28 or 57.3.
     */
    static UnitDefinition unit(String name, double factor, int type, Identifier id) {
        if (!Double.isNaN(factor) && factor > 0) {
            return new UnitDefinition(name, repair(factor), type, id);
        }
        Map<String, Double> table = type == UnitDefinition.ANGULAR ? ANGULAR_BY_NAME
                : type == UnitDefinition.LINEAR ? LINEAR_BY_NAME : null;
        if (table != null && name != null) {
            Double f = table.get(normalize(name));
            if (f != null) {
                return new UnitDefinition(name, f.doubleValue(), type, id);
            }
        }
        if (type == UnitDefinition.SCALE) {
            if (name == null || normalize(name).equals("unity")) {
                return UnitDefinition.UNITY;
            }
            if (normalize(name).startsWith("partspermillion")) {
                return UnitDefinition.PPM;
            }
        }
        throw new WktParseException("unit \"" + name + "\" has no conversion factor and is not a "
                + "unit this library knows; give it an explicit factor");
    }

    /**
     * The PROJ {@code +units=} code for a linear unit, or {@code null} if proj4j has no code for
     * it and {@code +to_meter=} must be used instead.
     */
    static String projUnitsCode(UnitDefinition unit) {
        if (unit == null) {
            return null;
        }
        double f = unit.getConversionFactor();
        Unit[] candidates = Units.units;
        for (int i = 0; i < candidates.length; i++) {
            Unit u = candidates[i];
            if (u == Units.DEGREES) {
                continue;
            }
            if (Math.abs(u.value - f) <= 1e-12 * Math.max(1.0, Math.abs(f))) {
                return u.abbreviation;
            }
        }
        return null;
    }

    /**
     * The linear unit denoting a PROJ {@code +units=} code, or {@code null}.
     */
    static UnitDefinition unitFromProjCode(String code) {
        Unit[] candidates = Units.units;
        for (int i = 0; i < candidates.length; i++) {
            if (candidates[i].abbreviation.equals(code)) {
                Unit u = candidates[i];
                return new UnitDefinition(wktNameOfProjUnit(u), u.value, UnitDefinition.LINEAR,
                        wktIdOfProjUnit(u));
            }
        }
        return null;
    }

    private static String wktNameOfProjUnit(Unit u) {
        if (u == Units.METRES) {
            return "metre";
        }
        if (u == Units.FEET) {
            return "foot";
        }
        if (u == Units.US_FEET) {
            return "US survey foot";
        }
        return u.name;
    }

    private static Identifier wktIdOfProjUnit(Unit u) {
        if (u == Units.METRES) {
            return new Identifier("EPSG", "9001");
        }
        if (u == Units.FEET) {
            return new Identifier("EPSG", "9002");
        }
        if (u == Units.US_FEET) {
            return new Identifier("EPSG", "9003");
        }
        return null;
    }

    // ------------------------------------------------------------- ellipsoids

    /**
     * The PROJ {@code +ellps=} code for an ellipsoid, matched numerically, or {@code null} if
     * none of proj4j's built-in ellipsoids matches and explicit {@code +a=}/{@code +rf=} must be
     * emitted.
     */
    static String projEllipsoidCode(EllipsoidDefinition e) {
        if (e == null || Double.isNaN(e.getSemiMajorAxis())) {
            return null;
        }
        double a = e.getSemiMajorAxisMetres();
        double rf = inverseFlatteningOf(e);
        Ellipsoid[] all = Ellipsoid.ellipsoids;
        String best = null;
        double bestDelta = Double.MAX_VALUE;
        for (int i = 0; i < all.length; i++) {
            Ellipsoid c = all[i];
            if (Math.abs(c.equatorRadius - a) > A_TOLERANCE) {
                continue;
            }
            boolean cSphere = c.eccentricity2 == 0.0;
            if ((rf == 0.0) != cSphere) {
                continue;
            }
            double crf = cSphere ? 0.0 : 1.0 / (1.0 - Math.sqrt(1.0 - c.eccentricity2));
            double delta = Math.abs(crf - rf);
            if (delta <= RF_TOLERANCE && delta < bestDelta) {
                best = c.shortName;
                bestDelta = delta;
            }
        }
        return best;
    }

    /**
     * The inverse flattening of a declared ellipsoid, computed from the semi-minor axis when that
     * is what the document carried. Zero denotes a sphere.
     */
    static double inverseFlatteningOf(EllipsoidDefinition e) {
        double rf = e.getInverseFlattening();
        if (!Double.isNaN(rf)) {
            return rf;
        }
        double a = e.getSemiMajorAxis();
        double b = e.getSemiMinorAxis();
        if (Double.isNaN(b) || a == b) {
            return 0.0;
        }
        return a / (a - b);
    }

    /**
     * A definition describing one of proj4j's ellipsoids, for the writers.
     */
    static EllipsoidDefinition definitionOf(Ellipsoid e) {
        EllipsoidDefinition d = new EllipsoidDefinition();
        d.setName(wktEllipsoidName(e));
        d.setSemiMajorAxis(e.equatorRadius);
        d.setUnit(UnitDefinition.METRE);
        if (e.eccentricity2 == 0.0) {
            d.setInverseFlattening(0.0);
            d.setSemiMinorAxis(e.equatorRadius);
        } else {
            d.setInverseFlattening(1.0 / (1.0 - Math.sqrt(1.0 - e.eccentricity2)));
        }
        Identifier id = ELLIPSOID_IDS.get(e.shortName);
        if (id != null) {
            d.setId(id);
        }
        return d;
    }

    private static final Map<String, String> ELLIPSOID_WKT_NAMES = new HashMap<String, String>();
    private static final Map<String, Identifier> ELLIPSOID_IDS = new HashMap<String, Identifier>();

    static {
        ellipsoid("WGS84", "WGS 84", "7030");
        ellipsoid("GRS80", "GRS 1980", "7019");
        ellipsoid("clrk66", "Clarke 1866", "7008");
        ellipsoid("clrk80", "Clarke 1880 (RGS)", "7012");
        ellipsoid("bessel", "Bessel 1841", "7004");
        ellipsoid("airy", "Airy 1830", "7001");
        ellipsoid("mod_airy", "Airy Modified 1849", "7002");
        ellipsoid("intl", "International 1924", "7022");
        ellipsoid("krass", "Krassowsky 1940", "7024");
        ellipsoid("WGS72", "WGS 72", "7043");
        ellipsoid("WGS66", "WGS 66", "7025");
        ellipsoid("evrst30", "Everest 1830 (1937 Adjustment)", "7015");
        ellipsoid("aust_SA", "Australian National Spheroid", "7003");
        ellipsoid("sphere", "Sphere", "7035");
        ellipsoid("GRS67", "GRS 1967", "7036");
        ellipsoid("helmert", "Helmert 1906", "7020");
        ellipsoid("hough", "Hough 1960", null);
        ellipsoid("NAD27", "Clarke 1880 mod.", null);
        ellipsoid("NAD83", "GRS 1980", "7019");
    }

    private static void ellipsoid(String projCode, String wktName, String epsgCode) {
        ELLIPSOID_WKT_NAMES.put(projCode, wktName);
        if (epsgCode != null) {
            ELLIPSOID_IDS.put(projCode, new Identifier("EPSG", epsgCode));
        }
    }

    static String wktEllipsoidName(Ellipsoid e) {
        String n = ELLIPSOID_WKT_NAMES.get(e.shortName);
        if (n != null) {
            return n;
        }
        return e.name != null ? e.name : e.shortName;
    }

    // ----------------------------------------------------------------- datums

    private static final Map<String, String> DATUM_CODES = new HashMap<String, String>();

    static {
        // WKT1, ESRI and WKT2 spellings of the datums proj4j has a +datum= code for. ESRI
        // prefixes geodetic datum names with "D_", which normalize() does not remove, so both
        // spellings are listed.
        datum("WGS84", "WGS_1984", "WGS 1984", "WGS84", "World Geodetic System 1984",
                "D_WGS_1984", "WGS_1984_(G873)");
        datum("NAD83", "North_American_Datum_1983", "North American Datum 1983", "NAD83",
                "D_North_American_1983", "NAD_1983");
        datum("NAD27", "North_American_Datum_1927", "North American Datum 1927", "NAD27",
                "D_North_American_1927", "NAD_1927");
        datum("OSGB36", "OSGB_1936", "OSGB 1936", "D_OSGB_1936", "Ordnance Survey of Great "
                + "Britain 1936");
        datum("potsdam", "Deutsches_Hauptdreiecksnetz", "Deutsches Hauptdreiecksnetz",
                "D_Deutsches_Hauptdreiecksnetz", "Potsdam Rauenberg 1950 DHDN");
        datum("carthage", "Carthage", "D_Carthage", "Carthage 1934 Tunisia");
        datum("hermannskogel", "Militar_Geographische_Institut",
                "Militar-Geographische Institut", "D_MGI", "Hermannskogel");
        datum("ire65", "TM65", "D_TM65", "Ireland 1965");
        datum("nzgd49", "New_Zealand_Geodetic_Datum_1949", "New Zealand Geodetic Datum 1949",
                "D_New_Zealand_1949", "NZGD49");
        datum("GGRS87", "Greek_Geodetic_Reference_System_1987",
                "Greek Geodetic Reference System 1987", "D_GGRS_1987", "GGRS87");
    }

    private static void datum(String projCode, String... names) {
        for (int i = 0; i < names.length; i++) {
            DATUM_CODES.put(normalize(names[i]), projCode);
        }
    }

    /**
     * The PROJ {@code +datum=} code a declared datum name denotes, or {@code null} if proj4j has
     * no built-in datum for it. Only names are consulted: two datums can share an ellipsoid, so
     * a numeric match would be wrong here.
     */
    static String projDatumCode(String datumName) {
        if (datumName == null) {
            return null;
        }
        return DATUM_CODES.get(normalize(datumName));
    }

    /**
     * The WKT datum name for one of proj4j's built-in datums, for the writers.
     */
    static String wktDatumName(Datum datum) {
        if (datum == null) {
            return "Unknown datum";
        }
        String code = datum.getCode();
        if ("WGS84".equals(code)) {
            return "World Geodetic System 1984";
        }
        if ("NAD83".equals(code)) {
            return "North American Datum 1983";
        }
        if ("NAD27".equals(code)) {
            return "North American Datum 1927";
        }
        if ("OSGB36".equals(code)) {
            return "OSGB 1936";
        }
        String name = datum.getName();
        return name != null ? name : code;
    }
}

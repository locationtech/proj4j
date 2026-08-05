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

/**
 * The WKT flavours this package reads, and the flavours it writes.
 * <p>
 * The distinction that matters in practice is {@link #WKT1_GDAL} versus {@link #WKT1_ESRI}: they
 * share a grammar but not a vocabulary. ESRI omits {@code AUTHORITY}, prefixes datum names with
 * {@code D_} and geographic CRS names with {@code GCS_}, spells the projection method and its
 * parameters differently ({@code Stereographic_North_Pole} rather than {@code Polar_Stereographic},
 * {@code Central_Meridian} rather than {@code central_meridian}), writes {@code Degree} rather than
 * {@code degree}, and collapses several EPSG methods into one name to be disambiguated by which
 * parameters are present. A reader which assumed one dialect would mis-read the other, so both
 * vocabularies are always consulted; the detected dialect decides only the few genuinely ambiguous
 * cases.
 */
public enum WktDialect {

    /** ISO 19162:2019, the current WKT2 revision. Written by {@link WktWriter} by default. */
    WKT2_2019,

    /** ISO 19162:2015, the first WKT2 revision. */
    WKT2_2015,

    /** WKT1 as OGC 01-009 defines it and as GDAL and PROJ emit it. */
    WKT1_GDAL,

    /** WKT1 as ESRI's ArcGIS products emit it. */
    WKT1_ESRI;

    public boolean isWkt1() {
        return this == WKT1_GDAL || this == WKT1_ESRI;
    }

    public boolean isWkt2() {
        return this == WKT2_2019 || this == WKT2_2015;
    }

    /**
     * Every keyword PROJ's WKT parser recognises, in its registration order.
     * <p>
     * Ported from {@code WKTConstants} (9.8.1 {@code src/iso19111/static.cpp} and
     * {@code include/proj/internal/io_internal.hpp}), 105 keywords. The order is load-bearing:
     * {@link #guess} falls back to returning {@link #WKT2_2015} for the first keyword the string
     * starts with.
     */
    static final String[] KEYWORDS = {
            // WKT1
            "GEOCCS", "GEOGCS", "DATUM", "UNIT", "SPHEROID", "AXIS", "PRIMEM", "AUTHORITY",
            "PROJCS", "PROJECTION", "PARAMETER", "VERT_CS", "VERTCS", "VERT_DATUM", "COMPD_CS",
            "TOWGS84", "EXTENSION", "LOCAL_CS", "LOCAL_DATUM", "LINUNIT",
            // WKT2, preferred keywords
            "GEODCRS", "LENGTHUNIT", "ANGLEUNIT", "SCALEUNIT", "TIMEUNIT", "ELLIPSOID", "CS", "ID",
            "PROJCRS", "BASEGEODCRS", "MERIDIAN", "ORDER", "ANCHOR", "ANCHOREPOCH", "CONVERSION",
            "METHOD", "REMARK", "GEOGCRS", "BASEGEOGCRS", "SCOPE", "AREA", "BBOX", "CITATION",
            "URI", "VERTCRS", "VDATUM", "COMPOUNDCRS", "PARAMETERFILE", "COORDINATEOPERATION",
            "SOURCECRS", "TARGETCRS", "INTERPOLATIONCRS", "OPERATIONACCURACY",
            "CONCATENATEDOPERATION", "STEP", "BOUNDCRS", "ABRIDGEDTRANSFORMATION",
            "DERIVINGCONVERSION", "TDATUM", "CALENDAR", "TIMEORIGIN", "TIMECRS", "VERTICALEXTENT",
            "TIMEEXTENT", "USAGE", "DYNAMIC", "FRAMEEPOCH", "MODEL", "VELOCITYGRID", "ENSEMBLE",
            "MEMBER", "ENSEMBLEACCURACY", "DERIVEDPROJCRS", "BASEPROJCRS", "EDATUM", "ENGCRS",
            "PDATUM", "PARAMETRICCRS", "PARAMETRICUNIT", "BASEVERTCRS", "BASEENGCRS",
            "BASEPARAMCRS", "BASETIMECRS", "VERSION", "GEOIDMODEL", "COORDINATEMETADATA", "EPOCH",
            "AXISMINVALUE", "AXISMAXVALUE", "RANGEMEANING", "POINTMOTIONOPERATION",
            // WKT2, alternate keywords
            "GEODETICCRS", "GEODETICDATUM", "PROJECTEDCRS", "PRIMEMERIDIAN", "GEOGRAPHICCRS",
            "TRF", "VERTICALCRS", "VERTICALDATUM", "VRF", "TIMEDATUM", "TEMPORALQUANTITY",
            "ENGINEERINGDATUM", "ENGINEERINGCRS", "PARAMETRICDATUM",
    };

    private static final String[] WKT1_ROOTS = {
            "GEOCCS", "GEOGCS", "COMPD_CS", "PROJCS", "VERT_CS", "LOCAL_CS",
    };

    private static final String[] WKT2_2019_ONLY = {
            "GEOGCRS", "CONCATENATEDOPERATION", "USAGE", "DYNAMIC", "FRAMEEPOCH", "MODEL",
            "VELOCITYGRID", "ENSEMBLE", "DERIVEDPROJCRS", "BASEPROJCRS", "GEOGRAPHICCRS", "TRF",
            "VRF", "POINTMOTIONOPERATION",
    };

    private static final String[] WKT2_2019_SUBSTRINGS = {
            "CS[TemporalDateTime,", "CS[TemporalCount,", "CS[TemporalMeasure,",
    };

    /**
     * Guesses which dialect a WKT string is written in.
     * <p>
     * A faithful port of PROJ's {@code WKTParser::guessDialect} (9.8.1
     * {@code src/iso19111/io.cpp}), including its two carve-outs, both of which exist because of
     * real-world files:
     * <ul>
     * <li>A leading {@code VERTCS} is always ESRI.</li>
     * <li>A WKT1 string with no {@code AXIS[} and no {@code AUTHORITY[} is taken for ESRI — unless
     * it contains {@code PARAMETER["rectified_grid_angle}, because
     * {@code Hotine_Oblique_Mercator_Azimuth_Center} exists in both dialects and GDAL WKT1 without
     * axes would otherwise be misread, silently dropping that parameter (PROJ issue #3279).</li>
     * </ul>
     *
     * @throws WktParseException if the string is not WKT at all
     */
    public static WktDialect guess(String wkt) {
        if (wkt == null) {
            throw new WktParseException("WKT text is null");
        }
        String s = stripLeadingWhitespace(wkt);
        if (startsWithIgnoreCase(s, "VERTCS")) {
            return WKT1_ESRI;
        }
        for (int i = 0; i < WKT1_ROOTS.length; i++) {
            if (startsWithIgnoreCase(s, WKT1_ROOTS[i])) {
                boolean looksEsri = containsIgnoreCase(s, "GEOGCS[\"GCS_")
                        || (!startsWithIgnoreCase(s, "LOCAL_CS")
                        && !containsIgnoreCase(s, "AXIS[")
                        && !containsIgnoreCase(s, "AUTHORITY["));
                if (looksEsri && !containsIgnoreCase(s, "PARAMETER[\"rectified_grid_angle")) {
                    return WKT1_ESRI;
                }
                return WKT1_GDAL;
            }
        }
        for (int i = 0; i < WKT2_2019_ONLY.length; i++) {
            int pos = indexOfIgnoreCase(s, WKT2_2019_ONLY[i]);
            if (pos >= 0) {
                int after = pos + WKT2_2019_ONLY[i].length();
                if (after < s.length() && s.charAt(after) == '[') {
                    return WKT2_2019;
                }
            }
        }
        for (int i = 0; i < WKT2_2019_SUBSTRINGS.length; i++) {
            if (containsIgnoreCase(s, WKT2_2019_SUBSTRINGS[i])) {
                return WKT2_2019;
            }
        }
        for (int i = 0; i < KEYWORDS.length; i++) {
            if (startsWithIgnoreCase(s, KEYWORDS[i])) {
                for (int j = KEYWORDS[i].length(); j < s.length(); j++) {
                    char c = s.charAt(j);
                    if (Character.isWhitespace(c)) {
                        continue;
                    }
                    if (c == '[' || c == '(') {
                        return WKT2_2015;
                    }
                    break;
                }
            }
        }
        throw new WktParseException("not a WKT string: \""
                + s.substring(0, Math.min(40, s.length())) + "\"");
    }

    private static String stripLeadingWhitespace(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i == 0 ? s : s.substring(i);
    }

    private static boolean startsWithIgnoreCase(String s, String prefix) {
        return s.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static boolean containsIgnoreCase(String s, String needle) {
        return indexOfIgnoreCase(s, needle) >= 0;
    }

    private static int indexOfIgnoreCase(String s, String needle) {
        int last = s.length() - needle.length();
        for (int i = 0; i <= last; i++) {
            if (s.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }
}

/*
 * Copyright 2026 The Proj4J Contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.locationtech.proj4j.conformance.bridge;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * PROJ 9.8.1's own name tables, vendored verbatim.
 *
 * <p><b>Why these exist.</b> Without them, "proj4j rejected this name" is
 * ambiguous: it could mean the definition is bad (which some {@code expect failure}
 * rows assert) or that proj4j's table is smaller than PROJ's (which is a gap). The
 * distinction is not guessable, and getting it wrong makes
 * {@link GieFailureKind#INVALID_DEFINITION} meaningless. With these tables it is
 * decidable by lookup:
 *
 * <ul>
 * <li>name absent from PROJ's table → PROJ errors too →
 *     {@link GieFailureKind#INVALID_DEFINITION};</li>
 * <li>name present in PROJ's table but not resolvable by proj4j →
 *     {@link GieFailureKind#NOT_IMPLEMENTED}.</li>
 * </ul>
 *
 * <p>Extracted at rev {@code 9.8.1} =
 * {@code f08fa86c478c4bbbf003b1ec751dd84aa6eca486} with
 * {@code git -C /Volumes/git/PROJ show 9.8.1:<path>}. Counts are asserted by
 * {@code ProjTablesTest} so a silent transcription slip cannot pass.
 */
public final class ProjTables {

    private ProjTables() {
    }

    /**
     * All 186 operator names {@code +proj=} accepts, from
     * {@code src/pj_list.h}'s {@code PROJ_HEAD(...)} entries. proj4j's
     * {@code Registry} maps 93 of them.
     */
    public static final Set<String> OPERATORS = set(
            "adams_hemi", "adams_ws1", "adams_ws2", "aea", "aeqd", "affine", "airocean", "airy",
            "aitoff", "alsk", "apian", "august", "axisswap", "bacon", "bertin1953", "bipc",
            "boggs", "bonne", "calcofi", "cart", "cass", "cc", "ccon", "cea", "chamb",
            "col_urban", "collg", "comill", "crast", "defmodel", "deformation", "denoy",
            "eck1", "eck2", "eck3", "eck4", "eck5", "eck6", "eqc", "eqdc", "eqearth", "etmerc",
            "euler", "fahey", "fouc", "fouc_s", "gall", "geoc", "geocent", "geogoffset", "geos",
            "gins8", "gn_sinu", "gnom", "goode", "gridshift", "gs48", "gs50", "gstmerc", "guyou",
            "hammer", "hatano", "healpix", "helmert", "hgridshift", "horner", "igh", "igh_o",
            "imoll", "imoll_o", "imw_p", "isea", "kav5", "kav7", "krovak", "labrd", "laea",
            "lagrng", "larr", "lask", "latlon", "latlong", "lcc", "lcca", "leac", "lee_os",
            "longlat", "lonlat", "loxim", "lsat", "mbt_fps", "mbt_s", "mbtfpp", "mbtfpq",
            "mbtfps", "merc", "mil_os", "mill", "misrsom", "mod_krovak", "moll", "molobadekas",
            "molodensky", "murd1", "murd2", "murd3", "natearth", "natearth2", "nell", "nell_h",
            "nicol", "noop", "nsper", "nzmg", "ob_tran", "ocea", "oea", "omerc", "ortel",
            "ortho", "patterson", "pconic", "peirce_q", "pipeline", "poly", "pop", "push",
            "putp1", "putp2", "putp3", "putp3p", "putp4p", "putp5", "putp5p", "putp6", "putp6p",
            "qsc", "qua_aut", "rhealpix", "robin", "rouss", "rpoly", "s2", "sch", "set", "sinu",
            "som", "somerc", "spilhaus", "stere", "sterea", "tcc", "tcea", "times", "tinshift",
            "tissot", "tmerc", "tobmerc", "topocentric", "tpeqd", "tpers", "unitconvert", "ups",
            "urm5", "urmfps", "utm", "vandg", "vandg2", "vandg3", "vandg4", "vertoffset",
            "vgridshift", "vitk1", "wag1", "wag2", "wag3", "wag4", "wag5", "wag6", "wag7",
            "webmerc", "weren", "wink1", "wink2", "wintri", "xyzgridshift");

    /**
     * The 46 built-in ellipsoid ids, from {@code src/ellps.cpp}. proj4j's
     * {@code Registry} carries 44 of these plus none of its own beyond
     * {@code NAD27}/{@code NAD83}, which PROJ does not have as ellipsoid names.
     */
    public static final Set<String> ELLIPSOIDS = set(
            "airy", "andrae", "APL4.9", "aust_SA", "bess_nam", "bessel", "clrk66", "clrk80",
            "clrk80ign", "CPM", "danish", "delmbr", "engelis", "evrst30", "evrst48", "evrst56",
            "evrst69", "evrstSS", "fschr60", "fschr60m", "fschr68", "GRS67", "GRS80", "GSK2011",
            "helmert", "hough", "IAU76", "intl", "kaula", "krass", "lerch", "MERIT", "mod_airy",
            "mprts", "new_intl", "NWL9D", "plessis", "PZ90", "SEasia", "SGS85", "sphere",
            "walbeck", "WGS60", "WGS66", "WGS72", "WGS84");

    /**
     * The 10 built-in datum ids, from {@code src/datums.cpp}. proj4j's
     * {@code Registry} carries exactly the same ten, so an unknown {@code +datum}
     * is unambiguously an {@link GieFailureKind#INVALID_DEFINITION}.
     */
    public static final Set<String> DATUMS = set(
            "WGS84", "GGRS87", "NAD83", "NAD27", "potsdam", "carthage", "hermannskogel",
            "ire65", "nzgd49", "OSGB36");

    /**
     * The <b>21</b> unit ids {@code +units}/{@code +vunits} accept, from
     * {@code src/units.cpp}. Anything else is an error in PROJ
     * ({@code "unknown units"}).
     *
     * <p><b>Corrected from 24.</b> This set previously included {@code rad}, {@code deg} and
     * {@code grad}, on the reasoning that {@code units.cpp} lists them. It does, but
     * {@code +units} resolves against {@code pj_list_linear_units()} <em>only</em>, so all three
     * are {@code "Invalid value for units"} upstream — verified against PROJ 9.8.1, not inferred
     * from the table they appear in. They are angular units, reachable through {@code +xy_in}/
     * {@code +xy_out} on {@code unitconvert}, never through {@code +units}.
     *
     * <p>proj4j's {@code Units.findUnits} is a trap here: it returns
     * {@link org.locationtech.proj4j.units.Units#METRES} for <em>any</em>
     * unrecognised name, so {@code +units=cm} silently became metres. The seven linear units it
     * omitted ({@code fath ch link us-ch ind-yd ind-ft ind-ch}) have since been added, so
     * {@link #proj4jResolvesUnit(String)} now resolves all 21.
     */
    public static final Set<String> UNITS = set(
            "km", "m", "dm", "cm", "mm", "kmi", "in", "ft", "yd", "mi", "fath", "ch", "link",
            "us-in", "us-ft", "us-yd", "us-ch", "us-mi", "ind-yd", "ind-ft", "ind-ch");

    /**
     * The 14 named prime meridians, from {@code pj_prime_meridians} in
     * {@code src/datums.cpp}. Anything else must parse as a DMS or decimal angle, or
     * PROJ errors.
     */
    public static final Set<String> PRIME_MERIDIANS = set(
            "greenwich", "lisbon", "paris", "bogota", "madrid", "rome", "bern", "jakarta",
            "ferro", "brussels", "stockholm", "athens", "oslo", "copenhagen");

    /**
     * The operators whose {@code pj_io_units} are {@code RADIANS} on <em>both</em>
     * sides, so a forward run is compared geodesically rather than in metres.
     * Every other {@code PROJ_HEAD} projection defaults to
     * {@code left = RADIANS, right = CLASSIC} ({@code proj_internal.h:882-883}).
     */
    public static final Set<String> ANGULAR_BOTH_SIDES = set(
            "longlat", "latlong", "lonlat", "latlon");

    /** Whether {@code name} is an operator PROJ 9.8.1 knows. */
    public static boolean isProjOperator(String name) {
        return name != null && OPERATORS.contains(name);
    }

    /**
     * Whether proj4j's {@code Units} table really resolves {@code id}, as opposed
     * to silently substituting metres. {@code Units.findUnits} returns
     * {@code METRES} rather than {@code null} for an unknown name, so presence has
     * to be established by checking that the returned unit actually answers to the
     * name asked for.
     */
    public static boolean proj4jResolvesUnit(String id) {
        if (id == null) {
            return false;
        }
        org.locationtech.proj4j.units.Unit u = org.locationtech.proj4j.units.Units.findUnits(id);
        if (u == null) {
            return false;
        }
        return id.equals(u.abbreviation) || id.equals(u.name) || id.equals(u.plural);
    }

    private static Set<String> set(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(values)));
    }
}

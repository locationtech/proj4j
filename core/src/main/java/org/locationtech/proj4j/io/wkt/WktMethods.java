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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.locationtech.proj4j.proj.Projection;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * The vocabulary of map projections: which PROJ {@code +proj=} a WKT operation method denotes, and
 * which {@code +param=} each of its parameters denotes, in the EPSG/WKT2, WKT1/GDAL and ESRI
 * spellings.
 * <p>
 * Ported from PROJ's own tables at 9.8.1 — {@code src/iso19111/operation/parammappings.cpp},
 * {@code esriparammappings.cpp}, and the special cases in {@code io.cpp} and
 * {@code conversion.cpp}. Three properties of that design are reproduced deliberately:
 * <ul>
 * <li>Names are compared loosely (case, spaces, underscores and hyphens ignored), so
 * {@code Transverse_Mercator}, {@code Transverse Mercator} and {@code transversemercator} are one
 * name.</li>
 * <li>A parameter is resolved <em>method-scoped first</em>, then against a shared table. This
 * matters because ESRI's parameter vocabulary is fixed and reused: {@code False_Easting} is EPSG
 * 8806, 8826 or 8816 depending on the method, and {@code Standard_Parallel_1} is 8823, 8801 or
 * 8832.</li>
 * <li>Several methods need a <em>derived</em> PROJ parameter that no WKT parameter carries:
 * Lambert Conic Conformal (1SP) emits {@code +lat_1} and {@code +lat_0} from one value, and Polar
 * Stereographic (variant B) emits {@code +lat_0=±90} from the sign of its standard parallel.
 * Without those the PROJ string is not merely differently spelled, it is a different
 * projection.</li>
 * </ul>
 * Where proj4j's projection would <em>silently ignore</em> a parameter the method defines, this
 * class converts it or refuses. Mercator variant B is the clearest case: proj4j's
 * {@code MercatorProjection} never reads {@code +lat_ts}, so passing it through would return
 * plausible coordinates at the wrong scale; it is converted to the equivalent {@code +k_0}
 * instead. Equidistant Cylindrical with a non-zero standard parallel has no such equivalent and is
 * refused.
 */
final class WktMethods {

    private WktMethods() {
    }

    /** No special handling. */
    private static final int PLAIN = 0;
    /**
     * The projection applies to a sphere whose radius is the base ellipsoid's semi-major axis.
     * PROJ spells this {@code +proj=webmerc} for EPSG 1024 and {@code +f=0} for the "(Spherical)"
     * methods; proj4j has neither, so the caller emits {@code +a=} and {@code +b=} equal instead,
     * which is arithmetically the same thing and is what GDAL's own PROJ.4 export of EPSG:3857
     * does.
     */
    static final int FLAG_SPHERE_FROM_A = 1;
    /** Mercator variant A: EPSG 8801 must be zero and is never written. */
    private static final int FLAG_MERCATOR_A = 2;
    /** Mercator variant B: the standard parallel becomes an equivalent scale factor. */
    private static final int FLAG_MERCATOR_B = 4;
    /** Lambert Conic Conformal (1SP): EPSG 8801 becomes both {@code +lat_1} and {@code +lat_0}. */
    private static final int FLAG_LCC_1SP = 8;
    /** Polar Stereographic (variant B): {@code +lat_0} is ±90, from the sign of EPSG 8832. */
    private static final int FLAG_POLAR_STEREO_B = 16;
    /** Equidistant Cylindrical: proj4j ignores both of its origin parameters. */
    private static final int FLAG_EQC = 32;
    /** Hotine Oblique Mercator: degenerates to {@code +proj=somerc} at azimuth 90. */
    private static final int FLAG_HOTINE = 64;

    // ------------------------------------------------------------------- model

    private static final class Param {
        final String projKey;
        final String epsgName;
        final String epsgCode;
        final String wkt1Name;
        final String esriName;
        final int unitType;

        Param(String projKey, String epsgName, String epsgCode, String wkt1Name, String esriName,
              int unitType) {
            this.projKey = projKey;
            this.epsgName = epsgName;
            this.epsgCode = epsgCode;
            this.wkt1Name = wkt1Name;
            this.esriName = esriName;
            this.unitType = unitType;
        }

        boolean matches(String name, String code) {
            if (code != null && code.equals(epsgCode)) {
                return true;
            }
            return WktNames.equalsRelaxed(name, epsgName) || WktNames.equalsRelaxed(name, wkt1Name)
                    || WktNames.equalsRelaxed(name, esriName);
        }
    }

    private static final class Method {
        final String projName;
        final String epsgName;
        final String epsgCode;
        final String wkt1Name;
        final String[] esriNames;
        final Param[] params;
        final String[] auxParams;
        final int flags;

        Method(String projName, String epsgName, String epsgCode, String wkt1Name,
               String[] esriNames, Param[] params, String[] auxParams, int flags) {
            this.projName = projName;
            this.epsgName = epsgName;
            this.epsgCode = epsgCode;
            this.wkt1Name = wkt1Name;
            this.esriNames = esriNames == null ? new String[0] : esriNames;
            this.params = params == null ? new Param[0] : params;
            this.auxParams = auxParams == null ? new String[0] : auxParams;
            this.flags = flags;
        }

        boolean matches(String name) {
            if (WktNames.equalsRelaxed(name, epsgName) || WktNames.equalsRelaxed(name, wkt1Name)) {
                return true;
            }
            for (int i = 0; i < esriNames.length; i++) {
                if (WktNames.equalsRelaxed(name, esriNames[i])) {
                    return true;
                }
            }
            return false;
        }
    }

    private static Param a(String projKey, String epsgName, String epsgCode, String wkt1Name,
                           String esriName) {
        return new Param(projKey, epsgName, epsgCode, wkt1Name, esriName, UnitDefinition.ANGULAR);
    }

    private static Param l(String projKey, String epsgName, String epsgCode, String wkt1Name,
                           String esriName) {
        return new Param(projKey, epsgName, epsgCode, wkt1Name, esriName, UnitDefinition.LINEAR);
    }

    private static Param s(String projKey, String epsgName, String epsgCode, String wkt1Name,
                           String esriName) {
        return new Param(projKey, epsgName, epsgCode, wkt1Name, esriName, UnitDefinition.SCALE);
    }

    // ------------------------------------------------------------- param sets

    private static final Param LAT_NATURAL =
            a("lat_0", "Latitude of natural origin", "8801", "latitude_of_origin",
                    "Latitude_Of_Origin");
    private static final Param LON_NATURAL =
            a("lon_0", "Longitude of natural origin", "8802", "central_meridian",
                    "Central_Meridian");
    private static final Param K_NATURAL =
            s("k_0", "Scale factor at natural origin", "8805", "scale_factor", "Scale_Factor");
    private static final Param FALSE_EASTING =
            l("x_0", "False easting", "8806", "false_easting", "False_Easting");
    private static final Param FALSE_NORTHING =
            l("y_0", "False northing", "8807", "false_northing", "False_Northing");
    private static final Param LAT_CENTRE =
            a("lat_0", "Latitude of projection centre", "8811", "latitude_of_center",
                    "Latitude_Of_Center");
    private static final Param LON_CENTRE_AS_LON0 =
            a("lon_0", "Longitude of projection centre", "8812", "longitude_of_center",
                    "Longitude_Of_Center");
    private static final Param LAT_FALSE_ORIGIN =
            a("lat_0", "Latitude of false origin", "8821", "latitude_of_origin",
                    "Latitude_Of_Origin");
    private static final Param LON_FALSE_ORIGIN =
            a("lon_0", "Longitude of false origin", "8822", "central_meridian",
                    "Central_Meridian");
    private static final Param SP1 =
            a("lat_1", "Latitude of 1st standard parallel", "8823", "standard_parallel_1",
                    "Standard_Parallel_1");
    private static final Param SP2 =
            a("lat_2", "Latitude of 2nd standard parallel", "8824", "standard_parallel_2",
                    "Standard_Parallel_2");
    private static final Param EASTING_FALSE_ORIGIN =
            l("x_0", "Easting at false origin", "8826", "false_easting", "False_Easting");
    private static final Param NORTHING_FALSE_ORIGIN =
            l("y_0", "Northing at false origin", "8827", "false_northing", "False_Northing");

    /** The five parameters most projections have: EPSG 8801, 8802, 8805, 8806, 8807. */
    private static final Param[] P_NATURAL = {
            LAT_NATURAL, LON_NATURAL, K_NATURAL, FALSE_EASTING, FALSE_NORTHING,
    };

    /** Azimuthal and world projections, whose WKT1 origin is spelled {@code _of_center}. */
    private static final Param[] P_CENTRE = {
            a("lat_0", "Latitude of natural origin", "8801", "latitude_of_center",
                    "Latitude_Of_Center"),
            a("lon_0", "Longitude of natural origin", "8802", "longitude_of_center",
                    "Longitude_Of_Center"),
            FALSE_EASTING, FALSE_NORTHING,
    };

    /** Lambert Conic Conformal (2SP), Albers Equal Area: EPSG 8821-8827. */
    private static final Param[] P_FALSE_ORIGIN_2SP = {
            LAT_FALSE_ORIGIN, LON_FALSE_ORIGIN, SP1, SP2, EASTING_FALSE_ORIGIN,
            NORTHING_FALSE_ORIGIN,
    };

    /** Albers and Equidistant Conic, whose WKT1 origin is spelled {@code _of_center}. */
    private static final Param[] P_ALBERS = {
            a("lat_0", "Latitude of false origin", "8821", "latitude_of_center",
                    "Latitude_Of_Origin"),
            a("lon_0", "Longitude of false origin", "8822", "longitude_of_center",
                    "Central_Meridian"),
            SP1, SP2, EASTING_FALSE_ORIGIN, NORTHING_FALSE_ORIGIN,
    };

    private static final Param[] P_LCC_1SP = {
            a("lat_1", "Latitude of natural origin", "8801", "latitude_of_origin",
                    "Standard_Parallel_1"),
            LON_NATURAL, K_NATURAL, FALSE_EASTING, FALSE_NORTHING,
    };

    private static final Param[] P_MERCATOR_A = {
            LAT_NATURAL, LON_NATURAL, K_NATURAL, FALSE_EASTING, FALSE_NORTHING,
    };

    private static final Param[] P_MERCATOR_B = {
            a("lat_ts", "Latitude of 1st standard parallel", "8823", "standard_parallel_1",
                    "Standard_Parallel_1"),
            LAT_NATURAL, LON_NATURAL, FALSE_EASTING, FALSE_NORTHING,
    };

    private static final Param[] P_EQC = {
            a("lat_ts", "Latitude of 1st standard parallel", "8823", "standard_parallel_1",
                    "Standard_Parallel_1"),
            LAT_NATURAL, LON_NATURAL, FALSE_EASTING, FALSE_NORTHING,
    };

    private static final Param[] P_CEA = {
            a("lat_ts", "Latitude of 1st standard parallel", "8823", "standard_parallel_1",
                    "Standard_Parallel_1"),
            LON_NATURAL, FALSE_EASTING, FALSE_NORTHING,
    };

    private static final Param[] P_POLAR_STEREO_B = {
            a("lat_ts", "Latitude of standard parallel", "8832", "latitude_of_origin",
                    "Standard_Parallel_1"),
            a("lon_0", "Longitude of origin", "8833", "central_meridian", "Central_Meridian"),
            FALSE_EASTING, FALSE_NORTHING,
    };

    private static final Param[] P_OMERC_A = {
            LAT_CENTRE,
            a("lonc", "Longitude of projection centre", "8812", "longitude_of_center",
                    "Longitude_Of_Center"),
            a("alpha", "Azimuth of initial line", "8813", "azimuth", "Azimuth"),
            a("gamma", "Angle from Rectified to Skew Grid", "8814", "rectified_grid_angle",
                    "XY_Plane_Rotation"),
            s("k_0", "Scale factor on initial line", "8815", "scale_factor", "Scale_Factor"),
            FALSE_EASTING, FALSE_NORTHING,
    };

    private static final Param[] P_OMERC_B = {
            LAT_CENTRE,
            a("lonc", "Longitude of projection centre", "8812", "longitude_of_center",
                    "Longitude_Of_Center"),
            a("alpha", "Azimuth of initial line", "8813", "azimuth", "Azimuth"),
            a("gamma", "Angle from Rectified to Skew Grid", "8814", "rectified_grid_angle",
                    "XY_Plane_Rotation"),
            s("k_0", "Scale factor on initial line", "8815", "scale_factor", "Scale_Factor"),
            l("x_0", "Easting at projection centre", "8816", "false_easting", "False_Easting"),
            l("y_0", "Northing at projection centre", "8817", "false_northing", "False_Northing"),
    };

    private static final Param[] P_KROVAK = {
            LAT_CENTRE,
            a("lon_0", "Longitude of origin", "8833", "longitude_of_center", "Longitude_Of_Center"),
            a("alpha", "Co-latitude of cone axis", "1036", "azimuth", "Azimuth"),
            a(null, "Latitude of pseudo standard parallel", "8818", "pseudo_standard_parallel_1",
                    "Pseudo_Standard_Parallel_1"),
            s("k_0", "Scale factor on pseudo standard parallel", "8819", "scale_factor",
                    "Scale_Factor"),
            FALSE_EASTING, FALSE_NORTHING,
    };

    private static final Param[] P_GEOS = {
            LON_NATURAL,
            new Param("h", "Satellite Height", null, "satellite_height", "Height",
                    UnitDefinition.LINEAR),
            FALSE_EASTING, FALSE_NORTHING,
    };

    private static final Param[] P_VERTICAL_PERSPECTIVE = {
            a("lat_0", "Latitude of topocentric origin", "8834", null, "Latitude_Of_Center"),
            a("lon_0", "Longitude of topocentric origin", "8835", null, "Longitude_Of_Center"),
            new Param("h", "Viewpoint height", "8840", null, "Height", UnitDefinition.LINEAR),
            FALSE_EASTING, FALSE_NORTHING,
    };

    /**
     * Parameters which mean the same thing in most methods. Consulted only after the identified
     * method's own list, and covering the aliases PROJ's {@code areEquivalentParameters} groups
     * together.
     */
    private static final Param[] GENERIC = {
            LAT_NATURAL, LON_NATURAL, K_NATURAL, FALSE_EASTING, FALSE_NORTHING,
            LAT_FALSE_ORIGIN, LON_FALSE_ORIGIN, SP1, SP2, EASTING_FALSE_ORIGIN,
            NORTHING_FALSE_ORIGIN, LAT_CENTRE, LON_CENTRE_AS_LON0,
            a("lat_ts", "Latitude of standard parallel", "8832", "latitude_of_origin",
                    "Standard_Parallel_1"),
            a("lon_0", "Longitude of origin", "8833", "central_meridian", "Longitude_Of_Origin"),
            l("x_0", "Easting at projection centre", "8816", "false_easting", "False_Easting"),
            l("y_0", "Northing at projection centre", "8817", "false_northing", "False_Northing"),
            a("alpha", "Azimuth of initial line", "8813", "azimuth", "Azimuth"),
            a("gamma", "Angle from Rectified to Skew Grid", "8814", "rectified_grid_angle",
                    "XY_Plane_Rotation"),
            s("k_0", "Scale factor on initial line", "8815", "scale_factor", "Scale_Factor"),
            s("k_0", "Scale factor at projection centre", "8815", "scale_factor", "Scale_Factor"),
            a("lat_ts", "Latitude of pseudo standard parallel", "8818",
                    "pseudo_standard_parallel_1", "Pseudo_Standard_Parallel_1"),
            s("k_0", "Scale factor on pseudo standard parallel", "8819", "scale_factor",
                    "Scale_Factor"),
            s("k_0", "Ellipsoid scaling factor", "1038", null, "Scale_Factor"),
            new Param("h", "Satellite Height", null, "satellite_height", "Height",
                    UnitDefinition.LINEAR),
            new Param("h", "Viewpoint height", "8840", null, "Height", UnitDefinition.LINEAR),
    };

    // ----------------------------------------------------------------- methods

    /** The order projection parameters are emitted in. */
    private static final String[] CANONICAL_ORDER = {
            "lat_0", "lon_0", "lat_1", "lat_2", "lat_ts", "lonc", "alpha", "gamma", "k_0", "x_0",
            "y_0", "h",
    };

    private static final List<Method> METHODS = new ArrayList<Method>();
    private static final Map<String, Method> BY_PROJ = new HashMap<String, Method>();

    private static void method(String projName, String epsgName, String epsgCode, String wkt1Name,
                               String[] esriNames, Param[] params, String[] auxParams, int flags) {
        Method m = new Method(projName, epsgName, epsgCode, wkt1Name, esriNames, params, auxParams,
                flags);
        METHODS.add(m);
        if (!BY_PROJ.containsKey(projName)) {
            BY_PROJ.put(projName, m);
        }
    }

    private static void method(String projName, String epsgName, String epsgCode, String wkt1Name,
                               Param[] params, String... esriNames) {
        method(projName, epsgName, epsgCode, wkt1Name, esriNames, params, null, PLAIN);
    }

    static {
        // --- Transverse Mercator family
        method("tmerc", "Transverse Mercator", "9807", "Transverse_Mercator", P_NATURAL,
                "Transverse_Mercator", "Gauss_Kruger", "Transverse_Mercator_Complex");
        method("tmerc", "Transverse Mercator (3D)", "1111", "Transverse_Mercator", P_NATURAL);
        method("tmerc", "Transverse Mercator (South Orientated)", "9808",
                "Transverse_Mercator_South_Orientated", new String[0], P_NATURAL,
                new String[]{"+axis=wsu"}, PLAIN);
        method("gstmerc", "Gauss Schreiber Transverse Mercator", null,
                "Gauss_Schreiber_Transverse_Mercator", P_NATURAL);

        // --- Mercator family. ESRI's plain "Mercator" is variant B, not variant A.
        method("merc", "Mercator (variant A)", "9804", "Mercator_1SP", new String[]{
                "Mercator_Variant_A"}, P_MERCATOR_A, null, FLAG_MERCATOR_A);
        method("merc", "Mercator (variant B)", "9805", "Mercator_2SP", new String[]{
                "Mercator", "Mercator_Variant_C"}, P_MERCATOR_B, null, FLAG_MERCATOR_B);
        // ESRI's Mercator_Auxiliary_Sphere spells the (mandatory zero) latitude of natural origin
        // Standard_Parallel_1, so it needs its own parameter set: matched by the shared table it
        // would become +lat_1 and silently bend the projection into a two-parallel one.
        method("merc", "Popular Visualisation Pseudo Mercator", "1024",
                "Popular_Visualisation_Pseudo_Mercator",
                new String[]{"Mercator_Auxiliary_Sphere"}, new Param[]{
                        a("lat_0", "Latitude of natural origin", "8801", "latitude_of_origin",
                                "Standard_Parallel_1"),
                        LON_NATURAL, K_NATURAL, FALSE_EASTING, FALSE_NORTHING,
                }, null, FLAG_SPHERE_FROM_A | FLAG_MERCATOR_A);

        // --- Lambert Conic Conformal. The bare name is ambiguous and resolved by parameters.
        method("lcc", "Lambert Conic Conformal (1SP)", "9801", "Lambert_Conformal_Conic_1SP",
                new String[0], P_LCC_1SP, null, FLAG_LCC_1SP);
        method("lcc", "Lambert Conic Conformal (2SP)", "9802", "Lambert_Conformal_Conic_2SP",
                P_FALSE_ORIGIN_2SP, "Lambert_Conformal_Conic");
        method("lcc", "Lambert Conic Conformal (2SP Belgium)", "9803",
                "Lambert_Conformal_Conic_2SP_Belgium", P_FALSE_ORIGIN_2SP);
        method("lcc", "Lambert Conic Conformal (2SP Michigan)", "1051", null,
                P_FALSE_ORIGIN_2SP);
        method("lcc", "Lambert Conic Conformal (1SP variant B)", "1102", null, new String[0],
                new Param[]{
                        a("lat_1", "Latitude of natural origin", "8801", "latitude_of_origin",
                                "Standard_Parallel_1"),
                        K_NATURAL, LAT_FALSE_ORIGIN, LON_FALSE_ORIGIN, EASTING_FALSE_ORIGIN,
                        NORTHING_FALSE_ORIGIN,
                }, null, PLAIN);

        // --- Other conics
        method("aea", "Albers Equal Area", "9822", "Albers_Conic_Equal_Area", P_ALBERS, "Albers");
        method("aea", "Albers Equal Area", "9822", "Albers_Conical_Equal_Area", P_ALBERS,
                "Albers_Equal_Area_Conic");
        method("eqdc", "Equidistant Conic", "1119", "Equidistant_Conic", P_ALBERS,
                "Equidistant_Conic");
        method("leac", "Lambert Equal Area Conic", null, "Lambert_Equal_Area_Conic", P_ALBERS);
        method("bonne", "Bonne", "9827", "Bonne", new Param[]{
                a("lat_1", "Latitude of natural origin", "8801", "standard_parallel_1",
                        "Standard_Parallel_1"),
                LON_NATURAL, FALSE_EASTING, FALSE_NORTHING,
        }, "Bonne");
        method("poly", "American Polyconic", "9818", "Polyconic", P_NATURAL, "Polyconic");
        method("rpoly", "Rectangular Polyconic", null, "Rectangular_Polyconic", P_NATURAL);
        method("cass", "Cassini-Soldner", "9806", "Cassini_Soldner", P_NATURAL, "Cassini");
        method("krovak", "Krovak (North Orientated)", "1041", "Krovak", new String[]{"Krovak"},
                P_KROVAK, null, PLAIN);
        method("krovak", "Krovak", "9819", "Krovak", new String[]{"Krovak"}, P_KROVAK,
                new String[]{"+axis=swu"}, PLAIN);

        // --- Cylindrical
        method("cea", "Lambert Cylindrical Equal Area", "9835", "Cylindrical_Equal_Area", P_CEA,
                "Cylindrical_Equal_Area", "Behrmann");
        method("cea", "Lambert Cylindrical Equal Area (Spherical)", "9834",
                "Cylindrical_Equal_Area", new String[0], P_CEA, new String[]{"+R_A"}, PLAIN);
        method("eqc", "Equidistant Cylindrical", "1028", "Equirectangular", new String[]{
                "Equidistant_Cylindrical", "Plate_Carree",
                "Equidistant_Cylindrical_Ellipsoidal"}, P_EQC, null, FLAG_EQC);
        method("eqc", "Equidistant Cylindrical (Spherical)", "1029", "Equirectangular",
                new String[]{"Plate_Carree"}, P_EQC, null, FLAG_EQC);
        method("mill", "Miller Cylindrical", null, "Miller_Cylindrical", new String[]{
                "Miller_Cylindrical"}, P_CENTRE, new String[]{"+R_A"}, PLAIN);
        method("cc", "Central Cylindrical", null, "Central_Cylindrical", P_NATURAL);
        method("gall", "Gall Stereographic", null, "Gall_Stereographic", P_NATURAL,
                "Gall_Stereographic");
        method("tcea", "Transverse Cylindrical Equal Area", null,
                "Transverse_Cylindrical_Equal_Area", P_NATURAL,
                "Transverse_Cylindrical_Equal_Area");

        // --- Oblique Mercator. somerc is the alpha == gamma == 90 degenerate case of variant B,
        // not a separate WKT method, so it is registered under its GDAL WKT1 name only.
        method("omerc", "Hotine Oblique Mercator (variant A)", "9812", "Hotine_Oblique_Mercator",
                new String[]{"Hotine_Oblique_Mercator_Azimuth_Natural_Origin",
                        "Rectified_Skew_Orthomorphic_Natural_Origin"},
                P_OMERC_A, new String[]{"+no_uoff"}, FLAG_HOTINE);
        method("omerc", "Hotine Oblique Mercator (variant B)", "9815",
                "Hotine_Oblique_Mercator_Azimuth_Center",
                new String[]{"Hotine_Oblique_Mercator_Azimuth_Center",
                        "Rectified_Skew_Orthomorphic_Center"},
                P_OMERC_B, null, FLAG_HOTINE);
        method("somerc", "Swiss Oblique Mercator", null, "Swiss_Oblique_Cylindrical", P_NATURAL,
                "Swiss_Oblique_Cylindrical", "Hotine_Oblique_Mercator_Azimuth_Center_Swiss");

        // --- Azimuthal
        method("stere", "Polar Stereographic (variant A)", "9810", "Polar_Stereographic",
                new String[]{"Polar_Stereographic_Variant_A"}, P_NATURAL, null, PLAIN);
        method("stere", "Polar Stereographic (variant B)", "9829", "Polar_Stereographic",
                new String[]{"Stereographic_North_Pole", "Stereographic_South_Pole"},
                P_POLAR_STEREO_B, null, FLAG_POLAR_STEREO_B);
        method("stere", "Stereographic", null, "Stereographic", P_NATURAL, "Stereographic");
        method("sterea", "Oblique Stereographic", "9809", "Oblique_Stereographic", P_NATURAL,
                "Double_Stereographic", "Oblique_Stereographic");
        method("laea", "Lambert Azimuthal Equal Area", "9820", "Lambert_Azimuthal_Equal_Area",
                P_CENTRE, "Lambert_Azimuthal_Equal_Area");
        method("laea", "Lambert Azimuthal Equal Area (Spherical)", "1027",
                "Lambert_Azimuthal_Equal_Area", new String[0], P_CENTRE, new String[]{"+R_A"},
                PLAIN);
        method("aeqd", "Azimuthal Equidistant", "1125", "Azimuthal_Equidistant", P_CENTRE,
                "Azimuthal_Equidistant");
        method("aeqd", "Modified Azimuthal Equidistant", "9832", "Azimuthal_Equidistant",
                P_CENTRE);
        method("gnom", "Gnomonic", null, "Gnomonic", P_CENTRE, "Gnomonic", "Gnomonic_Ellipsoidal");
        method("ortho", "Orthographic", "9840", "Orthographic", P_CENTRE, "Orthographic");
        method("ortho", "Orthographic (Spherical)", null, "Orthographic", new String[]{
                "Orthographic"}, P_CENTRE, null, FLAG_SPHERE_FROM_A);
        method("nsper", "Vertical Perspective", "9838", null, new String[]{
                "Vertical_Near_Side_Perspective"}, P_VERTICAL_PERSPECTIVE, null, PLAIN);
        method("geos", "Geostationary Satellite (Sweep Y)", null, "Geostationary_Satellite",
                P_GEOS, "Geostationary_Satellite");

        // --- Pseudo-cylindrical and world projections
        method("moll", "Mollweide", null, "Mollweide", P_CENTRE, "Mollweide");
        method("sinu", "Sinusoidal", null, "Sinusoidal", P_CENTRE, "Sinusoidal");
        method("robin", "Robinson", null, "Robinson", P_CENTRE, "Robinson");
        method("vandg", "Van Der Grinten", null, "VanDerGrinten", new String[]{
                "Van_der_Grinten_I"}, P_CENTRE, new String[]{"+R_A"}, PLAIN);
        method("aitoff", "Aitoff", null, "Aitoff", P_CENTRE, "Aitoff");
        method("hammer", "Hammer Aitoff", null, "Hammer_Aitoff", P_CENTRE, "Hammer_Aitoff");
        method("wintri", "Winkel Tripel", null, "Winkel_Tripel", P_CENTRE, "Winkel_Tripel");
        method("eck1", "Eckert I", null, "Eckert_I", P_CENTRE, "Eckert_I");
        method("eck2", "Eckert II", null, "Eckert_II", P_CENTRE, "Eckert_II");
        method("eck4", "Eckert IV", null, "Eckert_IV", P_CENTRE, "Eckert_IV");
        method("eck5", "Eckert V", null, "Eckert_V", P_CENTRE, "Eckert_V");
        method("eck6", "Eckert VI", null, "Eckert_VI", P_CENTRE, "Eckert_VI");
        method("goode", "Goode Homolosine", null, "Goode_Homolosine", P_CENTRE);
        method("loxim", "Loximuthal", null, "Loximuthal", new Param[]{
                a("lat_1", "Latitude of natural origin", "8801", "latitude_of_origin",
                        "Central_Parallel"),
                LON_NATURAL, FALSE_EASTING, FALSE_NORTHING,
        }, "Loximuthal");
        method("crast", "Craster Parabolic", null, "Craster_Parabolic", P_CENTRE,
                "Craster_Parabolic");
        method("qua_aut", "Quartic Authalic", null, "Quartic_Authalic", P_CENTRE,
                "Quartic_Authalic");
        method("mbtfpq", "Flat Polar Quartic", null, "Flat_Polar_Quartic", P_CENTRE,
                "Flat_Polar_Quartic");
        method("nzmg", "New Zealand Map Grid", "9811", "New_Zealand_Map_Grid", P_NATURAL,
                "New_Zealand_Map_Grid");
        method("wag4", "Wagner IV", null, "Wagner_IV", P_CENTRE, "Wagner_IV");
        method("wag5", "Wagner V", null, "Wagner_V", P_CENTRE, "Wagner_V");
        method("wag7", "Wagner VII", null, "Wagner_VII", P_CENTRE, "Wagner_VII");
    }

    // ----------------------------------------------------------------- lookups

    private static Method find(ConversionDefinition conv) {
        Identifier id = conv.getMethodId();
        if (id != null && "EPSG".equalsIgnoreCase(id.getAuthority()) && id.getCode() != null) {
            for (int i = 0; i < METHODS.size(); i++) {
                if (id.getCode().equals(METHODS.get(i).epsgCode)) {
                    return METHODS.get(i);
                }
            }
        }
        Method m = findByName(conv.getMethodName());
        if (m == null) {
            return null;
        }
        return disambiguate(m, conv);
    }

    /**
     * Resolves the variants several dialects collapse into one name, exactly as PROJ does:
     * {@code Lambert_Conformal_Conic} by which parameters are present, {@code Krovak} and
     * {@code Polar_Stereographic} likewise.
     */
    private static Method disambiguate(Method m, ConversionDefinition conv) {
        if ("lcc".equals(m.projName) && (m.flags & FLAG_LCC_1SP) == 0
                && WktNames.equalsRelaxed(conv.getMethodName(), "Lambert_Conformal_Conic")) {
            boolean hasSp2 = conv.getParameter("standard_parallel_2") != null;
            boolean hasScale = conv.getParameter("scale_factor") != null;
            if (!hasSp2 && hasScale) {
                return byEpsgCode("9801");
            }
            if (hasSp2 && hasScale) {
                return byEpsgCode("1051");
            }
            return byEpsgCode("9802");
        }
        if ("stere".equals(m.projName) && WktNames.equalsRelaxed(conv.getMethodName(),
                "Polar_Stereographic")) {
            // PROJ's WKT1 rule: a standard parallel with a unit scale factor is variant B; a
            // latitude of origin of +/-90 with a scale factor is variant A.
            ParameterDefinition scale = conv.getParameter("scale_factor");
            ParameterDefinition lat = conv.getParameter("latitude_of_origin");
            boolean unitScale = scale == null || scale.getValue() == 1.0;
            if (lat != null && unitScale && Math.abs(Math.abs(lat.getValueDegrees()) - 90) > 1e-10) {
                return byEpsgCode("9829");
            }
            return byEpsgCode("9810");
        }
        if ("stere".equals(m.projName) && m.epsgCode == null) {
            // ESRI "Stereographic" with a polar latitude of origin is really polar variant A.
            ParameterDefinition lat = conv.getParameter("Latitude_Of_Origin");
            if (lat != null && Math.abs(Math.abs(lat.getValueDegrees()) - 90) < 1e-10) {
                return byEpsgCode("9810");
            }
        }
        return m;
    }

    private static Method byEpsgCode(String code) {
        for (int i = 0; i < METHODS.size(); i++) {
            if (code.equals(METHODS.get(i).epsgCode)) {
                return METHODS.get(i);
            }
        }
        return null;
    }

    private static Method findByName(String name) {
        if (name == null) {
            return null;
        }
        // OGC 12-063r5 C.4.2, reproduced by PROJ's getMappingFromWKT1: a WKT1 projection name
        // beginning "UTM zone" is Transverse Mercator.
        if (name.length() >= 8 && name.substring(0, 8).equalsIgnoreCase("UTM zone")) {
            return byEpsgCode("9807");
        }
        for (int i = 0; i < METHODS.size(); i++) {
            if (METHODS.get(i).matches(name)) {
                return METHODS.get(i);
            }
        }
        return null;
    }

    /**
     * The unit type of a WKT1 parameter, which carries no unit of its own.
     * <p>
     * PROJ's {@code guessUnitForParameter} (9.8.1 {@code src/iso19111/io.cpp}), in its exact
     * order: {@code scale} first — precisely because "Scale factor on pseudo standard parallel"
     * also contains "parallel" — then the angular words, then the linear ones.
     *
     * @return {@link UnitDefinition#SCALE}, {@link UnitDefinition#ANGULAR},
     *         {@link UnitDefinition#LINEAR}, or -1 for "no idea"
     */
    static int guessUnitType(String parameterName) {
        if (parameterName == null) {
            return -1;
        }
        // Locale.ROOT: an ASCII parameter name, matched against ASCII substrings below.
        String n = parameterName.toLowerCase(Locale.ROOT);
        if (n.contains("scale") || n.contains("scaling factor")) {
            return UnitDefinition.SCALE;
        }
        if (n.contains("latitude") || n.contains("longitude") || n.contains("meridian")
                || n.contains("parallel") || n.contains("azimuth") || n.contains("angle")
                || n.contains("heading") || n.contains("rotation")) {
            return UnitDefinition.ANGULAR;
        }
        if (n.contains("easting") || n.contains("northing") || n.contains("height")) {
            return UnitDefinition.LINEAR;
        }
        return -1;
    }

    static boolean isAngularParameter(String name) {
        return guessUnitType(name) == UnitDefinition.ANGULAR;
    }

    static boolean isScaleParameter(String name) {
        return guessUnitType(name) == UnitDefinition.SCALE;
    }

    private static Param findGeneric(String name, String code) {
        for (int i = 0; i < GENERIC.length; i++) {
            if (GENERIC[i].matches(name, code)) {
                return GENERIC[i];
            }
        }
        return null;
    }

    // ----------------------------------------------------------------- forward

    /**
     * Appends {@code +proj=} and the projection's parameters for {@code conv}.
     *
     * @return flags the caller must act on, currently only {@link #FLAG_SPHERE_FROM_A}
     * @throws WktParseException if the method is unknown, or needs a parameter proj4j's
     *                           implementation would silently ignore
     */
    static int appendProjection(ConversionDefinition conv, CrsDefinition crs,
                                List<String> params) {
        Method method = find(conv);
        if (method == null) {
            throw new WktParseException("operation method \"" + conv.getMethodName()
                    + "\" is not a projection this library can map to PROJ");
        }

        Map<String, String> values = new LinkedHashMap<String, String>();
        List<ParameterDefinition> declared = conv.getParameters();
        for (int i = 0; i < declared.size(); i++) {
            ParameterDefinition p = declared.get(i);
            String code = p.getId() != null && "EPSG".equalsIgnoreCase(p.getId().getAuthority())
                    ? p.getId().getCode() : null;
            Param mapping = null;
            for (int j = 0; j < method.params.length; j++) {
                if (method.params[j].matches(p.getName(), code)) {
                    mapping = method.params[j];
                    break;
                }
            }
            if (mapping == null) {
                mapping = findGeneric(p.getName(), code);
            }
            if (mapping == null) {
                if (isIgnorableParameter(p.getName())) {
                    continue;
                }
                throw new WktParseException("parameter \"" + p.getName() + "\" of method \""
                        + conv.getMethodName() + "\" has no PROJ equivalent");
            }
            if (mapping.projKey == null) {
                // PROJ drops this parameter too: Krovak's pseudo standard parallel, which is
                // hard-coded in every implementation.
                continue;
            }
            values.put(mapping.projKey, WktFormat.number(valueOf(p, mapping)));
        }

        String projName = method.projName;

        if ((method.flags & FLAG_MERCATOR_A) != 0) {
            String lat0 = values.remove("lat_0");
            if (lat0 != null && Double.parseDouble(lat0) != 0.0) {
                // GDAL ticket #3026: a Mercator_1SP with a non-zero latitude of origin is really
                // variant B. PROJ re-reads it that way rather than rejecting it, and so do we.
                String k = values.get("k_0");
                if (k == null || Double.parseDouble(k) == 1.0) {
                    values.remove("k_0");
                    values.put("lat_ts", lat0);
                    mercatorVariantB(values, crs, conv);
                } else {
                    throw new WktParseException("Mercator (variant A) with latitude of natural "
                            + "origin " + lat0 + " and scale factor " + k + " is not a projection "
                            + "PROJ or this library can express");
                }
            }
        }
        if ((method.flags & FLAG_MERCATOR_B) != 0) {
            values.remove("lat_0");
            mercatorVariantB(values, crs, conv);
        }
        if ((method.flags & FLAG_LCC_1SP) != 0 && values.containsKey("lat_1")
                && !values.containsKey("lat_0")) {
            // PROJ emits both from the single EPSG 8801 value; without +lat_0 the projection is
            // centred on the equator.
            values.put("lat_0", values.get("lat_1"));
        }
        if ((method.flags & FLAG_POLAR_STEREO_B) != 0) {
            String latTs = values.get("lat_ts");
            if (latTs == null) {
                throw new WktParseException("Polar Stereographic (variant B) has no latitude of "
                        + "standard parallel");
            }
            values.put("lat_0", Double.parseDouble(latTs) >= 0 ? "90" : "-90");
        }
        if ((method.flags & FLAG_EQC) != 0) {
            refuseNonZero(values, "lat_ts", conv, "standard parallel");
            refuseNonZero(values, "lat_0", conv, "latitude of natural origin");
        }
        if ((method.flags & FLAG_HOTINE) != 0) {
            String alpha = values.get("alpha");
            String gamma = values.get("gamma");
            if (alpha != null && gamma == null) {
                // PROJ synthesises gamma from alpha when WKT1 omits rectified_grid_angle.
                gamma = alpha;
                values.put("gamma", gamma);
            }
            if (alpha != null && gamma != null
                    && Math.abs(Double.parseDouble(alpha) - 90) < 1e-4
                    && Math.abs(Double.parseDouble(gamma) - 90) < 1e-4) {
                // The Swiss degenerate case: PROJ exports +proj=somerc, and proj4j's
                // SwissObliqueMercatorProjection is the implementation that matches.
                projName = "somerc";
                values.remove("alpha");
                values.remove("gamma");
                String lonc = values.remove("lonc");
                if (lonc != null && !values.containsKey("lon_0")) {
                    values.put("lon_0", lonc);
                }
            }
        }
        if ((method.flags & FLAG_SPHERE_FROM_A) != 0) {
            ParameterDefinition auxiliary = conv.getParameter("Auxiliary_Sphere_Type");
            if (auxiliary != null && auxiliary.getValue() != 0.0) {
                throw new WktParseException("Auxiliary_Sphere_Type="
                        + WktFormat.number(auxiliary.getValue()) + " is not supported; only 0, "
                        + "\"use the semi-major axis as the sphere radius\", has an equivalent "
                        + "here");
            }
        }

        params.add("+proj=" + projName);
        if ("krovak".equals(projName) && isSouthWest(crs)) {
            // WKT1 and ESRI both spell EPSG 9819 and 1041 "Krovak"; PROJ tells them apart by
            // whether the projected axes are south then west, and only 9819 gets +axis=swu.
            // Emitting it unconditionally would negate both ordinates of every EPSG:5514
            // coordinate.
            params.add("+axis=swu");
        }
        // Emitted in a fixed order rather than the document's, so that the same CRS expressed in
        // WKT1, ESRI WKT, WKT2 and PROJJSON yields one identical PROJ string.
        for (int i = 0; i < CANONICAL_ORDER.length; i++) {
            String v = values.remove(CANONICAL_ORDER[i]);
            if (v != null) {
                params.add("+" + CANONICAL_ORDER[i] + "=" + v);
            }
        }
        for (java.util.Iterator<Map.Entry<String, String>> it = values.entrySet().iterator();
             it.hasNext(); ) {
            Map.Entry<String, String> e = it.next();
            params.add("+" + e.getKey() + "=" + e.getValue());
        }
        if (!"somerc".equals(projName)) {
            for (int i = 0; i < method.auxParams.length; i++) {
                if ("+axis=swu".equals(method.auxParams[i])) {
                    continue;   // handled above, from the axes rather than from the method name
                }
                params.add(method.auxParams[i]);
            }
        }
        return method.flags & FLAG_SPHERE_FROM_A;
    }

    /**
     * Whether a CRS's first two axes are south then west — the Czech Krovak convention, EPSG 9819.
     */
    private static boolean isSouthWest(CrsDefinition crs) {
        CoordinateSystemDefinition cs = crs == null ? null : crs.getCoordinateSystem();
        if (cs == null || cs.getAxes().size() < 2) {
            return false;
        }
        return AxisDefinition.SOUTH.equals(cs.getAxes().get(0).getDirection())
                && AxisDefinition.WEST.equals(cs.getAxes().get(1).getDirection());
    }

    private static void refuseNonZero(Map<String, String> values, String key,
                                      ConversionDefinition conv, String what) {
        String v = values.remove(key);
        if (v != null && Double.parseDouble(v) != 0.0) {
            throw new WktParseException("method \"" + conv.getMethodName() + "\" has " + what + " "
                    + v + ", which proj4j's implementation ignores; refusing rather than "
                    + "returning coordinates at the wrong scale or offset");
        }
    }

    /**
     * ESRI parameters which select a method variant rather than describing the geometry, and which
     * this library has already accounted for by the time they are seen.
     */
    private static boolean isIgnorableParameter(String name) {
        String n = WktNames.normalize(name);
        return n.equals("auxiliaryspheretype") || n.equals("xscale") || n.equals("yscale")
                || n.equals("option");
    }

    private static double valueOf(ParameterDefinition p, Param mapping) {
        switch (mapping.unitType) {
            case UnitDefinition.ANGULAR:
                return p.getValueDegrees();
            case UnitDefinition.SCALE:
                return p.getValueScale();
            default:
                return p.getValueMetres();
        }
    }

    /**
     * Converts Mercator variant B's standard parallel to variant A's scale factor, per EPSG
     * Guidance Note 7-2 and PROJ's {@code Conversion::convertToOtherMethod}:
     * {@code k0 = cos(phi1) / sqrt(1 - e^2 sin^2(phi1))}.
     * <p>
     * Necessary because proj4j's {@code MercatorProjection} never reads {@code +lat_ts}: passing
     * the standard parallel through would leave the scale silently at 1, which is a coordinate
     * error growing with latitude — 20 km at 60 degrees.
     */
    private static void mercatorVariantB(Map<String, String> values, CrsDefinition crs,
                                         ConversionDefinition conv) {
        String latTs = values.remove("lat_ts");
        if (latTs == null) {
            latTs = values.remove("lat_1");
        }
        if (latTs == null) {
            return;
        }
        double phi1 = ProjectionMath.toRad(Double.parseDouble(latTs));
        if (Math.abs(phi1) >= Math.PI / 2) {
            throw new WktParseException("Mercator (variant B) standard parallel " + latTs
                    + " is out of range");
        }
        String existing = values.get("k_0");
        if (existing != null && Double.parseDouble(existing) != 1.0) {
            throw new WktParseException("Mercator (variant B) cannot carry both a standard "
                    + "parallel and a scale factor of " + existing);
        }
        double es = eccentricitySquared(crs);
        double sin = Math.sin(phi1);
        double k0 = Math.cos(phi1) / Math.sqrt(1.0 - es * sin * sin);
        values.put("k_0", WktFormat.number(k0));
    }

    private static double eccentricitySquared(CrsDefinition crs) {
        DatumDefinition datum = crs == null ? null : crs.resolveDatum();
        EllipsoidDefinition e = datum == null ? null : datum.getEllipsoid();
        if (e == null) {
            return 0.0;
        }
        double rf = WktNames.inverseFlatteningOf(e);
        if (rf == 0.0) {
            return 0.0;
        }
        double f = 1.0 / rf;
        return 2 * f - f * f;
    }

    // ----------------------------------------------------------------- reverse

    /**
     * The EPSG/WKT2 name of a conversion's method, whichever dialect it is spelled in.
     */
    static String wkt2MethodName(ConversionDefinition conv) {
        Method m = find(conv);
        if (m != null && m.epsgName != null) {
            return m.epsgName;
        }
        return conv.getMethodName() == null ? "unknown" : conv.getMethodName();
    }

    /**
     * The EPSG identifier of a method named in any dialect, or {@code null}.
     */
    static Identifier methodId(String methodName) {
        Method m = findByName(methodName);
        return m == null || m.epsgCode == null ? null : new Identifier("EPSG", m.epsgCode);
    }

    /**
     * The EPSG/WKT2 name of a parameter, resolved against the method that uses it so that
     * {@code false_easting} becomes "Easting at false origin" for Lambert Conic Conformal (2SP)
     * and "False easting" for Transverse Mercator.
     */
    static String wkt2ParameterName(ConversionDefinition conv, String name) {
        Param p = findFor(conv, name);
        return p != null && p.epsgName != null ? p.epsgName : name;
    }

    /**
     * The EPSG identifier of a parameter, resolved against its method, or {@code null}.
     */
    static Identifier parameterId(ConversionDefinition conv, String name) {
        Param p = findFor(conv, name);
        return p == null || p.epsgCode == null ? null : new Identifier("EPSG", p.epsgCode);
    }

    private static Param findFor(ConversionDefinition conv, String name) {
        Method m = conv == null ? null : find(conv);
        if (m != null) {
            for (int i = 0; i < m.params.length; i++) {
                if (m.params[i].matches(name, null)) {
                    return m.params[i];
                }
            }
        }
        return findGeneric(name, null);
    }

    /**
     * Describes a proj4j projection as a WKT2 conversion, for the writers.
     *
     * @throws WktParseException if the projection has no WKT operation method
     */
    static ConversionDefinition conversionOf(Projection proj) {
        String projName = proj.getName();
        Method m = BY_PROJ.get(projName);
        if (m == null && "utm".equals(projName)) {
            m = byEpsgCode("9807");
        }
        if (m == null && ("longlat".equals(projName) || "latlong".equals(projName))) {
            throw new WktParseException("a geographic CRS has no projection conversion");
        }
        if (m == null) {
            throw new WktParseException("PROJ projection \"" + projName
                    + "\" has no WKT operation method in this library's table");
        }
        ConversionDefinition conv = new ConversionDefinition();
        conv.setName("unnamed");
        conv.setMethodName(m.epsgName);
        if (m.epsgCode != null) {
            conv.setMethodId(new Identifier("EPSG", m.epsgCode));
        }
        Param[] params = m.params;
        for (int i = 0; i < params.length; i++) {
            Param p = params[i];
            if (p.projKey == null) {
                continue;
            }
            double value = projValue(proj, p.projKey);
            if (Double.isNaN(value)) {
                continue;
            }
            ParameterDefinition pd = new ParameterDefinition();
            pd.setName(p.epsgName);
            pd.setValue(value);
            pd.setUnit(p.unitType == UnitDefinition.ANGULAR ? UnitDefinition.DEGREE
                    : p.unitType == UnitDefinition.SCALE ? UnitDefinition.UNITY
                    : UnitDefinition.METRE);
            if (p.epsgCode != null) {
                pd.setId(new Identifier("EPSG", p.epsgCode));
            }
            conv.addParameter(pd);
        }
        return conv;
    }

    private static double projValue(Projection proj, String projKey) {
        if ("lat_0".equals(projKey)) {
            return proj.getProjectionLatitudeDegrees();
        }
        if ("lon_0".equals(projKey)) {
            return proj.getProjectionLongitudeDegrees();
        }
        if ("lat_1".equals(projKey)) {
            return proj.getProjectionLatitude1Degrees();
        }
        if ("lat_2".equals(projKey)) {
            return proj.getProjectionLatitude2Degrees();
        }
        if ("lat_ts".equals(projKey)) {
            return proj.getTrueScaleLatitudeDegrees();
        }
        if ("k_0".equals(projKey)) {
            return proj.getScaleFactor();
        }
        if ("x_0".equals(projKey)) {
            return proj.getFalseEasting();
        }
        if ("y_0".equals(projKey)) {
            return proj.getFalseNorthing();
        }
        if ("alpha".equals(projKey)) {
            return ProjectionMath.toDeg(proj.getAlpha());
        }
        if ("lonc".equals(projKey)) {
            return ProjectionMath.toDeg(proj.getLonC());
        }
        if ("h".equals(projKey)) {
            return proj.getHeightOfOrbit();
        }
        return Double.NaN;
    }
}

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

import java.util.List;

import org.locationtech.proj4j.conformance.parse.ProjDmsToR;
import org.locationtech.proj4j.conformance.parse.ProjStrtod;

/**
 * Decides whether <em>PROJ 9.8.1 itself</em> would reject a definition.
 *
 * <p>This class is the load-bearing half of the {@link GieFailureKind#INVALID_DEFINITION}
 * versus {@link GieFailureKind#NOT_IMPLEMENTED} split. It knows nothing about
 * proj4j's capabilities and must not: its only question is "does upstream error
 * here?", because that is what gie's {@code expect failure} rows assert.
 *
 * <p>The checks are the complete set reachable from PROJ's global parsing
 * ({@code src/init.cpp}, {@code src/param.cpp}, {@code src/ell_set.cpp},
 * {@code src/datum_set.cpp}, {@code src/pipeline.cpp}):
 *
 * <ul>
 * <li>missing, empty or unknown {@code +proj}; nested pipelines;</li>
 * <li>{@code |lat_0| > 90}; {@code k}/{@code k_0 <= 0};</li>
 * <li>unknown {@code +units}/{@code +vunits}; {@code to_meter}/{@code vto_meter}
 *     non-positive or with a zero denominator;</li>
 * <li>unknown or unparseable {@code +pm};</li>
 * <li>{@code +axis} not exactly 3 characters, or containing a character outside
 *     {@code ewnsud};</li>
 * <li>{@code |lon_wrap| >= 20*pi} or NaN;</li>
 * <li>unknown {@code +datum}; unknown {@code +ellps};</li>
 * <li>ellipsoid errors: {@code a <= 0}, {@code rf <= 0}, {@code f < 0},
 *     {@code es}/{@code e} outside {@code [0,1)}, {@code b <= 0};</li>
 * <li>{@code +towgs84} with a count other than 3 or 7;</li>
 * <li>{@code pj_param} type {@code i} on a value containing a non-digit, and type
 *     {@code b} on anything but empty/{@code t}/{@code T}/{@code f}/{@code F};</li>
 * <li>{@code +no_defs} with no ellipsoid size at all — verified against the
 *     installed 9.8.1: {@code proj +proj=merc +no_defs} fails with
 *     {@code "Must specify ellipsoid or sphere"} (error 1026);</li>
 * <li>a resolved flattening outside {@code [0,1)}, which {@code ellps_shape}'s own
 *     bounds do not catch — {@code +f=1}, {@code +rf=0.5} and {@code +a=1 +b=2} all
 *     pass it and are rejected by {@code pj_calc_ellipsoid_params}
 *     ({@code ell_set.cpp:598-600}) instead.</li>
 * </ul>
 *
 * <p>Beyond global parsing, {@link ProjOperatorSetup} adds the per-operator
 * construction guards — {@code lcc}'s parallels, {@code helmert}'s
 * {@code +convention}, {@code nsper}'s {@code +h}, and eighteen more — each
 * transcribed from that operator's own setup function at rev {@code 9.8.1} and each
 * probed against the installed binary in both directions.
 *
 * <p><b>Two things this deliberately does not do</b>, because both would be
 * stricter than PROJ and would wrongly convert passes into
 * {@code INVALID_DEFINITION}:
 *
 * <ol>
 * <li>It does not treat {@code +ellps} plus a shape parameter as a contradiction.
 *     {@code ell_set.cpp}'s own comment says later shape and size parameters are
 *     deliberately <em>modifiers</em> for a built-in ellipsoid, citing
 *     {@code +ellps=xxx +a=1} as the intended use. So {@code +ellps=GRS80 +rf=300}
 *     is valid and yields {@code f = 1/300}.</li>
 * <li>It does not reject longitudes outside {@code [-180,180]}. PROJ's bound is
 *     {@code |lambda| > 10} <em>radians</em>, about +/-573 degrees, and anything
 *     inside is wrapped.</li>
 * </ol>
 */
final class ProjDefinitionValidator {

    private static final double HALF_PI = Math.PI / 2.0;
    private static final double LON_WRAP_LIMIT = 10.0 * 2.0 * Math.PI;
    private static final String AXIS_CHARS = "ewnsud";

    private ProjDefinitionValidator() {
    }

    /**
     * @return an {@link GieFailureKind#INVALID_DEFINITION} failure if PROJ 9.8.1
     *         would reject {@code a}, otherwise {@code null}.
     */
    static GieFailure validate(GieProjArgs a) {
        GieFailure f;
        if (a.isPipeline()) {
            // Structure is the ONLY thing checked for a pipeline, and in
            // particular {@link #validateProjName} is deliberately not run.
            //
            // A definition may begin with a bare `+step` and have no global
            // `+proj` at all - more_builtins.gie:535 is
            // `+step +proj=latlong +ellps=WGS84`, and the corpus asserts a real
            // coordinate for it, not a failure. Confirmed against the installed
            // 9.8.1: `projinfo "+step +proj=latlong +ellps=WGS84"` resolves it to
            // `+proj=latlong +ellps=WGS84`. So an implicit pipeline is valid
            // upstream and must be reported NOT_IMPLEMENTED (we have no pipeline
            // engine), never INVALID_DEFINITION.
            //
            // The per-step operator parameters are also left alone: they belong to
            // operators we have not implemented, so validating them would be
            // inventing upstream behaviour rather than reproducing it.
            return validatePipelineStructure(a);
        }
        f = validateProjName(a);
        if (f != null) {
            return f;
        }
        f = validateEllipsoid(a);
        if (f != null) {
            return f;
        }
        f = validateFrame(a);
        if (f != null) {
            return f;
        }
        f = validateDatum(a);
        if (f != null) {
            return f;
        }
        return ProjOperatorSetup.validate(a);
    }

    // ---------------------------------------------------------------- +proj

    private static GieFailure validateProjName(GieProjArgs a) {
        GieToken proj = a.find("proj");
        if (proj == null) {
            return GieFailures.invalidDefinition(
                    "no +proj: PROJ 9.8.1 fails with \"Missing argument\" (proj_errno 1026)");
        }
        String name = proj.value();
        if (name == null || name.isEmpty()) {
            return GieFailures.invalidDefinition("+proj= is empty");
        }
        if (!ProjTables.isProjOperator(name)) {
            return GieFailures.invalidDefinition(
                    "+proj=" + name + " is not one of PROJ 9.8.1's " + ProjTables.OPERATORS.size()
                            + " operators, so PROJ rejects this definition too");
        }
        return null;
    }

    private static GieFailure validatePipelineStructure(GieProjArgs a) {
        List<GieProjArgs> steps = a.steps();
        if (steps.size() < 2) {
            // more_builtins.gie:235 - "operation proj=pipeline" alone, which
            // gie asserts fails with pjd_err_malformed_pipeline.
            return GieFailures.invalidDefinition(
                    "+proj=pipeline with no +step: PROJ 9.8.1 reports a malformed pipeline");
        }
        // steps.get(0) is the global scope, which carries +proj=pipeline itself.
        for (int i = 1; i < steps.size(); i++) {
            GieProjArgs step = steps.get(i);
            String name = step.peek("proj");
            if (name == null) {
                if (step.contains("init")) {
                    // +init= expands to a whole definition; PROJ accepts it.
                    continue;
                }
                return GieFailures.invalidDefinition(
                        "pipeline step " + i + " has no +proj");
            }
            if ("pipeline".equals(name)) {
                return GieFailures.invalidDefinition(
                        "nested pipeline at step " + i + ": PROJ 9.8.1 rejects this");
            }
            if (!ProjTables.isProjOperator(name)) {
                return GieFailures.invalidDefinition(
                        "pipeline step " + i + " uses +proj=" + name
                                + ", not a PROJ 9.8.1 operator");
            }
        }
        return null;
    }

    // ------------------------------------------------------------- ellipsoid

    private static GieFailure validateEllipsoid(GieProjArgs a) {
        String ellps = a.peek("ellps");
        if (ellps != null && !ProjTables.ELLIPSOIDS.contains(ellps)) {
            return GieFailures.invalidDefinition(
                    "+ellps=" + ellps + " is not one of PROJ 9.8.1's "
                            + ProjTables.ELLIPSOIDS.size() + " built-in ellipsoids");
        }

        // +R short-circuits every shape and spherification parameter
        // (ell_set.cpp:92-133), so nothing after it can be invalid.
        if (a.contains("R")) {
            Double r = projDouble(a.peek("R"));
            if (r != null && !(r.doubleValue() > 0)) {
                return GieFailures.invalidDefinition("+R=" + a.peek("R") + " must be > 0");
            }
            return null;
        }

        if (a.contains("a")) {
            Double v = projDouble(a.peek("a"));
            if (v != null && !(v.doubleValue() > 0)) {
                return GieFailures.invalidDefinition("+a=" + a.peek("a") + " must be > 0");
            }
        }
        if (!a.hasEllipsoidSize() && !a.impliesGrs80()) {
            return GieFailures.invalidDefinition(
                    "+no_defs with no +ellps/+datum/+a/+b/+R: PROJ 9.8.1 fails with "
                            + "\"pj_init_ctx: Must specify ellipsoid or sphere\" (error 1026)");
        }

        // ellps_shape(): the FIRST present of rf, f, es, e, b wins.
        //
        // Each branch checks ellps_shape's own bound AND the flattening bound that
        // pj_calc_ellipsoid_params applies afterwards to whatever shape came out:
        //
        //     if (!(P->f >= 0.0 && P->f < 1.0)) { "Invalid eccentricity" }
        //         -- ell_set.cpp:598-600
        //
        // ellps_shape only rejects `rf <= 0`, `f < 0` and `b <= 0`, so `+f=1`,
        // `+rf=0.5` and `+a=1 +b=2` all get through it and are caught there instead.
        // Verified against the installed 9.8.1 in both directions: `+proj=merc +a=1
        // +f=1`, `+rf=1`, `+rf=0.5` and `+b=2` are refused, while `+f=0.5`,
        // `+rf=1.0000001`, `+b=1` and `+b=0.5` are accepted. (`proj` surfaces it as
        // pj_init_ctx's "Must specify ellipsoid or sphere", because pj_ellipsoid
        // returns non-zero; the rejection is the same one.)
        if (a.contains("rf")) {
            Double v = projDouble(a.peek("rf"));
            if (v != null && !(v.doubleValue() > 0)) {
                return GieFailures.invalidDefinition("+rf=" + a.peek("rf") + " must be > 0");
            }
            if (v != null && !isProjFlattening(1.0 / v.doubleValue())) {
                return GieFailures.invalidDefinition(
                        "+rf=" + a.peek("rf") + " gives f = " + (1.0 / v.doubleValue())
                                + ", outside [0,1): \"Invalid eccentricity\"");
            }
        } else if (a.contains("f")) {
            Double v = projDouble(a.peek("f"));
            if (v != null && v.doubleValue() < 0) {
                return GieFailures.invalidDefinition("+f=" + a.peek("f") + " must be >= 0");
            }
            if (v != null && !isProjFlattening(v.doubleValue())) {
                return GieFailures.invalidDefinition(
                        "+f=" + a.peek("f") + " is outside [0,1): \"Invalid eccentricity\"");
            }
        } else if (a.contains("es")) {
            Double v = projDouble(a.peek("es"));
            if (v != null && (v.doubleValue() < 0 || v.doubleValue() >= 1 || v.isNaN())) {
                return GieFailures.invalidDefinition(
                        "+es=" + a.peek("es") + " is outside [0,1): \"Invalid eccentricity\"");
            }
        } else if (a.contains("e")) {
            Double v = projDouble(a.peek("e"));
            if (v != null && (v.doubleValue() < 0 || v.doubleValue() >= 1 || v.isNaN())) {
                return GieFailures.invalidDefinition(
                        "+e=" + a.peek("e") + " is outside [0,1): \"Invalid eccentricity\"");
            }
        } else if (a.contains("b")) {
            Double v = projDouble(a.peek("b"));
            if (v != null && !(v.doubleValue() > 0)) {
                return GieFailures.invalidDefinition("+b=" + a.peek("b") + " must be > 0");
            }
            // f = (a - b) / a, so b > a is a negative flattening.
            Double major = projDouble(a.peek("a"));
            if (v != null && major != null && major.doubleValue() > 0
                    && !isProjFlattening(
                            (major.doubleValue() - v.doubleValue()) / major.doubleValue())) {
                return GieFailures.invalidDefinition(
                        "+b=" + a.peek("b") + " with +a=" + a.peek("a")
                                + " gives a flattening outside [0,1): \"Invalid eccentricity\"");
            }
        }

        // R_lat_a / R_lat_g require |lat| <= pi/2 (ell_set.cpp:356-467).
        String[] latSpher = {"R_lat_a", "R_lat_g"};
        for (int i = 0; i < latSpher.length; i++) {
            String v = a.peek(latSpher[i]);
            if (v != null) {
                Double rad = projAngleRadians(v);
                if (rad != null && Math.abs(rad.doubleValue()) > HALF_PI) {
                    return GieFailures.invalidDefinition(
                            "+" + latSpher[i] + "=" + v + " exceeds 90 degrees");
                }
            }
        }
        return null;
    }

    // --------------------------------------------------------- frame params

    private static GieFailure validateFrame(GieProjArgs a) {
        String latZero = a.peek("lat_0");
        if (latZero != null) {
            Double rad = projAngleRadians(latZero);
            if (rad != null && Math.abs(rad.doubleValue()) > HALF_PI) {
                return GieFailures.invalidDefinition(
                        "+lat_0=" + latZero + " exceeds 90 degrees "
                                + "(PROJ: \"Invalid value for lat_0\")");
            }
        }

        // k is read first, then k_0, so k_0 wins; both are validated.
        String[] scaleKeys = {"k", "k_0"};
        for (int i = 0; i < scaleKeys.length; i++) {
            String v = a.peek(scaleKeys[i]);
            if (v != null) {
                Double d = projDouble(v);
                if (d != null && !(d.doubleValue() > 0)) {
                    return GieFailures.invalidDefinition(
                            "+" + scaleKeys[i] + "=" + v + " must be > 0");
                }
            }
        }

        String[] unitKeys = {"units", "vunits"};
        for (int i = 0; i < unitKeys.length; i++) {
            String v = a.peek(unitKeys[i]);
            if (v != null && !ProjTables.UNITS.contains(v)) {
                return GieFailures.invalidDefinition(
                        "+" + unitKeys[i] + "=" + v + " is not one of PROJ 9.8.1's "
                                + ProjTables.UNITS.size() + " unit ids");
            }
        }

        String[] toMeterKeys = {"to_meter", "vto_meter"};
        for (int i = 0; i < toMeterKeys.length; i++) {
            String v = a.peek(toMeterKeys[i]);
            if (v != null) {
                Double d = projRatio(v);
                if (d == null) {
                    return GieFailures.invalidDefinition(
                            "+" + toMeterKeys[i] + "=" + v + " has a zero denominator");
                }
                if (!(d.doubleValue() > 0)) {
                    return GieFailures.invalidDefinition(
                            "+" + toMeterKeys[i] + "=" + v + " must be > 0");
                }
            }
        }

        String pm = a.peek("pm");
        if (pm != null && !ProjTables.PRIME_MERIDIANS.contains(pm)) {
            ProjDmsToR.Result r = ProjDmsToR.dmstor(pm);
            if (r.end == 0) {
                return GieFailures.invalidDefinition(
                        "+pm=" + pm + " is neither a named meridian nor a parseable angle");
            }
        }

        String axis = a.peek("axis");
        if (axis != null) {
            if (axis.length() != 3) {
                return GieFailures.invalidDefinition(
                        "+axis=" + axis + " must be exactly 3 characters");
            }
            for (int i = 0; i < 3; i++) {
                if (AXIS_CHARS.indexOf(axis.charAt(i)) < 0) {
                    return GieFailures.invalidDefinition(
                            "+axis=" + axis + " contains '" + axis.charAt(i)
                                    + "', outside \"ewnsud\"");
                }
            }
        }

        String lonWrap = a.peek("lon_wrap");
        if (lonWrap != null) {
            Double rad = projAngleRadians(lonWrap);
            if (rad != null && !(Math.abs(rad.doubleValue()) < LON_WRAP_LIMIT)) {
                return GieFailures.invalidDefinition(
                        "+lon_wrap=" + lonWrap + " is NaN or at least 20*pi radians");
            }
        }

        String zone = a.peek("zone");
        if (zone != null && !isAllDigits(zone)) {
            return GieFailures.invalidDefinition(
                    "+zone=" + zone + " contains a non-digit; PROJ's pj_param type 'i' "
                            + "raises PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE");
        }

        for (int i = 0; i < BOOLEAN_KEYS.length; i++) {
            GieToken t = a.find(BOOLEAN_KEYS[i]);
            if (t != null && !isProjBoolean(t.value())) {
                return GieFailures.invalidDefinition(
                        "+" + BOOLEAN_KEYS[i] + "=" + t.value()
                                + " is not a PROJ boolean (empty, t, T, f or F)");
            }
        }
        return null;
    }

    private static GieFailure validateDatum(GieProjArgs a) {
        String datum = a.peek("datum");
        if (datum != null && !ProjTables.DATUMS.contains(datum)) {
            return GieFailures.invalidDefinition(
                    "+datum=" + datum + " is not one of PROJ 9.8.1's "
                            + ProjTables.DATUMS.size() + " built-in datums");
        }
        String towgs84 = a.peek("towgs84");
        if (towgs84 != null) {
            int n = towgs84.split(",", -1).length;
            if (n != 3 && n != 7) {
                return GieFailures.invalidDefinition(
                        "+towgs84 has " + n + " values; PROJ accepts 3 or 7");
            }
        }
        return null;
    }

    // --------------------------------------------------- PROJ value grammar

    /**
     * The keys PROJ reads with {@code pj_param} type {@code 'b'}. A value other
     * than empty, {@code t}, {@code T}, {@code f} or {@code F} is a hard error;
     * {@code f}/{@code F} means <em>false</em>, which proj4j cannot express
     * because {@code Proj4Parser} tests {@code Map.containsKey}.
     */
    static final String[] BOOLEAN_KEYS = {
            "south", "no_uoff", "no_off", "no_defs", "wktext", "over", "geoc", "no_rot",
            "no_cut", "guam", "hyperbolic", "czech", "approx", "exact", "inv",
            "R_A", "R_V", "R_a", "R_g", "R_h"
    };

    /** {@code pj_param} type {@code 'b'} acceptance. */
    static boolean isProjBoolean(String v) {
        if (v == null || v.isEmpty()) {
            return true;
        }
        return "t".equals(v) || "T".equals(v) || "f".equals(v) || "F".equals(v);
    }

    /** {@code pj_param} type {@code 'b'} value. */
    static boolean projBooleanValue(String v) {
        return !("f".equals(v) || "F".equals(v));
    }

    /**
     * {@code pj_calc_ellipsoid_params}' flattening bound
     * ({@code 9.8.1:src/ell_set.cpp:598-600}): {@code f >= 0.0 && f < 1.0}, written
     * that way so {@code NaN} fails.
     */
    static boolean isProjFlattening(double f) {
        return f >= 0.0 && f < 1.0;
    }

    /**
     * {@code pj_param} type {@code 'r'} with PROJ's own default-on-absent: 0 radians
     * when the key is missing, and 0 when it is present but unparseable, because
     * {@code dmstor_ctx} returns 0 in that case.
     *
     * @param dflt what PROJ substitutes when the operator explicitly tests
     *     {@code pj_param("t<key>").i} first; pass 0 for the plain
     *     {@code pj_param("r<key>").f} idiom
     */
    static double radians(GieProjArgs a, String key, double dflt) {
        GieToken t = a.find(key);
        if (t == null) {
            return dflt;
        }
        Double r = projAngleRadians(t.value());
        return r == null ? 0.0 : r.doubleValue();
    }

    /** {@code pj_param} type {@code 'd'} with a default-on-absent. */
    static double number(GieProjArgs a, String key, double dflt) {
        GieToken t = a.find(key);
        if (t == null) {
            return dflt;
        }
        Double d = projDouble(t.value());
        return d == null ? 0.0 : d.doubleValue();
    }

    static boolean isAllDigits(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code pj_param} type {@code 'd'} — {@code pj_atof}, i.e. C locale
     * {@code strtod}. Trailing garbage is silently ignored, exactly as in C.
     *
     * @return the value, or {@code null} if nothing numeric was there at all.
     */
    static Double projDouble(String v) {
        if (v == null) {
            return null;
        }
        ProjStrtod.Result r = ProjStrtod.strtod(v);
        if (r.end == 0) {
            return null;
        }
        return Double.valueOf(r.value);
    }

    /**
     * {@code pj_param} type {@code 'r'} — {@code dmstor_ctx}, which yields
     * <em>radians</em>. Handles decimal degrees, the {@code d}/{@code D}/degree-sign
     * suffix, the {@code r}/{@code R} radian suffix, DMS with {@code '} and
     * {@code "}, and a trailing cardinal ({@code e}/{@code n} positive,
     * {@code w}/{@code s} negative).
     *
     * @return radians, or {@code null} if nothing was parseable.
     */
    static Double projAngleRadians(String v) {
        if (v == null) {
            return null;
        }
        ProjDmsToR.Result r = ProjDmsToR.dmstor(v);
        if (r.end == 0) {
            return null;
        }
        return Double.valueOf(r.radians);
    }

    /**
     * {@code +to_meter}/{@code +vto_meter}, which accept a {@code num/den} ratio
     * as well as a plain double ({@code init.cpp}).
     *
     * @return the value; {@code null} means a zero denominator, which PROJ treats
     *         as an error.
     */
    static Double projRatio(String v) {
        if (v == null) {
            return null;
        }
        int slash = v.indexOf('/');
        if (slash < 0) {
            return projDouble(v);
        }
        Double num = projDouble(v.substring(0, slash));
        Double den = projDouble(v.substring(slash + 1));
        if (num == null || den == null || den.doubleValue() == 0.0) {
            return null;
        }
        return Double.valueOf(num.doubleValue() / den.doubleValue());
    }
}

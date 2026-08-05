/*******************************************************************************
 * Copyright 2009, 2017 Martin Davis
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
package org.locationtech.proj4j.parser;

import java.util.*;

import org.locationtech.proj4j.*;

public class Proj4Keyword {

    public static final String a = "a";
    public static final String b = "b";
    public static final String e = "e";
    public static final String f = "f";
    public static final String alpha = "alpha";
    public static final String datum = "datum";
    public static final String ellps = "ellps";
    public static final String es = "es";
    public static final String axis = "axis";

    public static final String azi = "azi";
    public static final String gamma = "gamma";
    public static final String k = "k";
    public static final String k_0 = "k_0";
    public static final String lat_ts = "lat_ts";
    public static final String lat_0 = "lat_0";
    public static final String lat_1 = "lat_1";
    public static final String lat_2 = "lat_2";
    public static final String lon_0 = "lon_0";
    public static final String lonc = "lonc";
    public static final String pm = "pm";

    public static final String proj = "proj";

    public static final String R = "R";
    public static final String R_A = "R_A";
    public static final String R_a = "R_a";
    public static final String R_V = "R_V";
    public static final String R_g = "R_g";
    public static final String R_h = "R_h";
    public static final String R_lat_a = "R_lat_a";
    public static final String R_lat_g = "R_lat_g";
    public static final String R_C = "R_C";
    public static final String rf = "rf";
    public static final String h = "h";

    /**
     * {@code +h_0} - the "average height of the terrain" of {@code +proj=col_urban}
     * ({@code col_urban.cpp:65}, {@code pj_param(ctx, P-&gt;params, "dh_0").f}). It is
     * that operator's only mandatory parameter beyond the ellipsoid, so the single
     * {@code builtins.gie} {@code col_urban} row is unreachable without it.
     * <p>
     * Distinct from {@link #h}, the satellite height of {@code geos}/{@code nsper}/
     * {@code tpers}, and from the {@code h}/{@code h_0} that {@code io.cpp:12520}
     * lists as metre-valued in the CRS parser.
     * <p>
     * Dispatched on {@code ColombiaUrbanProjection} by
     * {@code Proj4Parser.parseProjection}. It is read with {@code pj_param}'s
     * {@code d} sigil, i.e. a plain double, and is always metres regardless of
     * {@code +units}.
     */
    public static final String h_0 = "h_0";

    /**
     * Shape parameters in the fixed precedence order used by PROJ's
     * {@code ellps_shape} (<code>ell_set.cpp</code>). Exactly one of these takes
     * effect: the loop breaks on the first one present.
     */
    public static final String[] SHAPE_PARAMS = {rf, f, es, e, b};

    /**
     * Spherification parameters in the fixed precedence order used by PROJ's
     * {@code ellps_spherification}. Exactly one of these takes effect.
     */
    public static final String[] SPHERIFICATION_PARAMS = {
            R_A, R_V, R_a, R_g, R_h, R_lat_a, R_lat_g, R_C
    };

    public static final String south = "south";
    public static final String to_meter = "to_meter";
    public static final String towgs84 = "towgs84";
    public static final String units = "units";
    public static final String x_0 = "x_0";
    public static final String y_0 = "y_0";
    public static final String zone = "zone";

    public static final String title = "title";
    public static final String nadgrids = "nadgrids";
    public static final String no_defs = "no_defs";
    public static final String wktext = "wktext";
    public static final String no_uoff = "no_uoff";

    /**
     * {@code +rot}, {@code +shape}, {@code +scrollx}, {@code +scrolly} - the four
     * operator-scoped keys of the {@code adams.cpp}/{@code spilhaus.cpp} family, alongside
     * {@link #azi} which was already declared above.
     * <p>
     * <b>{@code +shape} is read by {@code peirce_q} alone</b>
     * ({@code adams.cpp:405}), {@code +scrollx}/{@code +scrolly} only inside its
     * {@code horizontal}/{@code vertical} branches ({@code adams.cpp:420-447}), and
     * {@code +rot} by {@code spilhaus} alone. {@code +azi} is read upstream by
     * {@code spilhaus}, {@code tpers} ({@code nsper.cpp:196}), {@code labrd}
     * ({@code labrd.cpp:117}) and {@code isea} ({@code isea.cpp:1024}).
     * <p>
     * <b>{@code +azi} is now dispatched per class to three of those four</b> -
     * {@code SpilhausProjection}, {@code TiltedPerspectiveProjection} and
     * {@code LabordeProjection} - which is exactly the set that is registered in
     * {@code Registry}. {@code isea} is not ported, so its {@code +proj=} name is
     * refused before {@code +azi} can matter.
     * <p>
     * That per-class fan-out is <b>not optional</b>, and it is why {@code tpers} was
     * left unregistered for a stage. {@code Proj4Parser} used to send {@code +azi} to
     * {@code SpilhausProjection} and nowhere else, so registering {@code tpers} on its
     * own would have made {@code +proj=tpers +azi=20} parse cleanly, pass the
     * allow-list, drop the azimuth and return a <b>silently wrong map</b> - the same
     * defect as {@code +proj=peirce_q +shape=square} projecting as a diamond.
     * {@code labrd} had already been registered with the same hole open:
     * {@code labrd.cpp:117} rotates its complex correction by {@code 2*Az} and
     * {@code LabordeProjection.setAziRadians} was reachable only from Java. No
     * corpus row exercises {@code labrd +azi}, which is precisely why it survived.
     */
    public static final String rot = "rot";
    public static final String shape = "shape";
    public static final String scrollx = "scrollx";
    public static final String scrolly = "scrolly";

    /**
     * {@code +tilt} - {@code tpers}'s rotation of the image plane out of the tangent
     * plane, upstream's {@code omega} ({@code nsper.cpp:186},
     * {@code pj_param(ctx, P-&gt;params, "rtilt").f}). Read with the {@code r} sigil,
     * so it takes every angular syntax {@code dmstor} accepts.
     * <p>
     * Read by {@code tpers} and by nothing else, and dispatched on
     * {@code TiltedPerspectiveProjection} alone. Registering it is what made
     * {@code +proj=tpers} safe to add to {@code Registry}: before that,
     * {@code +tilt} was not a keyword at all, so that half already failed closed
     * while {@link #azi} did not.
     */
    public static final String tilt = "tilt";

    /**
     * {@code +over} - suppress the &plusmn;&pi; reduction of longitude
     * ({@code init.cpp:601}, {@code pj_param(ctx, start, "bover").i}), so it is a
     * {@code b} sigil: a bare {@code +over} is true and {@code +over=f} is
     * explicitly false.
     * <p>
     * <b>Global, not operator-scoped.</b> {@code fwd_prepare} skips both of its
     * {@code adjlon} calls when it is set ({@code fwd.cpp:82-83}, {@code :110-111})
     * and {@code inv_finalize} skips its one ({@code inv.cpp:115-116}), which is why
     * it is dispatched through {@code Projection.setOver} rather than on a concrete
     * class. It does <b>not</b> disable {@code +lon_wrap}.
     * <p>
     * Upstream also has a context-level {@code forceOver} that ORs in over the
     * parameter ({@code init.cpp:602-603}); it is settable only through
     * {@code proj_context_set_enable_area_of_use}-style C API calls and has no
     * proj-string spelling, so it is deliberately not modelled.
     */
    public static final String over = "over";

    /**
     * {@code +W} - read by <b>two</b> operators: {@code lagrng}, where it is the
     * fraction of a hemisphere the boundary circle spans ({@code lagrng.cpp:79-85},
     * default {@code 2}), and {@code hammer}, where it is the longitudinal
     * compression ({@code hammer.cpp:63-70}, default {@code .5}, and
     * {@code W = 1} is the Lambert azimuthal). <b>Capital W</b>; PROJ's parameter
     * names are case sensitive and there is no lower-case synonym.
     * <p>
     * Both test presence with {@code t} and only then read the value with {@code d},
     * so it is a plain double and not an angle, and both reject {@code W &lt;= 0} -
     * though {@code hammer} takes {@code fabs} first and {@code lagrng} does not.
     * Each class's {@code initialize()} raises the equivalent, so nothing is
     * validated twice in the parser.
     * <p>
     * <b>{@code hammer} is the reason this key must be dispatched to both.</b>
     * Registering it for {@code lagrng} alone made
     * {@code builtins.gie:2596}'s {@code +proj=hammer +a=6400000 +W=1} executable
     * with {@code W} dropped, and that row is an {@code expect failure}: at
     * {@code (-180, 0)} the true {@code W = 1} forward is singular, while
     * {@code W = .5} returns a perfectly plausible {@code -18101933.598}. Measured -
     * one "failed to fail" appeared in the corpus and this is how it was found.
     */
    public static final String W = "W";

    /**
     * {@code +M} - {@code hammer}'s aspect factor and {@code hammer}'s alone
     * ({@code hammer.cpp:72-79}), default {@code 1}. A plain {@code d} behind a
     * {@code t} presence test, {@code fabs}'d, and {@code M &lt;= 0} is a hard
     * error.
     * <p>
     * <b>Capital M, and unrelated to {@link #m}</b>, the lower-case shape parameter
     * of {@code gn_sinu}. Two different keys that differ only in case is exactly the
     * kind of thing a case-insensitive parameter map would silently merge.
     * <p>
     * No corpus row uses it; it is dispatched because {@code +W} is, and shipping one
     * half of {@code hammer}'s parameter pair is the drift that creates the next
     * silent wrong answer.
     */
    public static final String M = "M";

    /**
     * {@code +no_cut}, {@code +lat_b} - {@code airy}'s two parameters, and
     * {@code airy}'s alone ({@code airy.cpp:119-120}).
     * <p>
     * {@code +no_cut} is a {@code b} sigil, so {@code +no_cut=f} really is off;
     * {@code +lat_b} is an {@code r}, so it is an angle in radians once parsed.
     * Both are dispatched on {@code AiryProjection}, whose setters already existed
     * and were reachable only from Java.
     */
    public static final String no_cut = "no_cut";
    public static final String lat_b = "lat_b";

    /**
     * {@code +guam} - {@code aeqd}'s Guam variant ({@code aeqd.cpp:301},
     * {@code pj_param(ctx, P-&gt;params, "bguam").i}), a {@code b} sigil.
     * <p>
     * It replaces both the forward and the inverse with
     * {@code e_guam_fwd}/{@code e_guam_inv}, and <b>only on the ellipsoidal
     * branch</b>: {@code PJ_PROJECTION(aeqd)} tests it inside the {@code es != 0}
     * arm, so on a declared sphere it is read, marked used, and has no effect.
     * {@code EquidistantAzimuthalProjection} reproduces that placement exactly.
     */
    public static final String guam = "guam";

    /**
     * {@code +hyperbolic} - {@code cass}'s Vanua Levu variant ({@code cass.cpp:127}).
     * <p>
     * <b>Read with {@code pj_param_exists}, not with the {@code b} sigil</b> - which
     * makes it the one flag in this file where {@code +hyperbolic=f} is
     * <i>true</i>, because presence is all that is tested. Reproduced with
     * {@code containsKey} rather than {@code parseBoolean} for exactly that reason.
     * <p>
     * Like {@link #guam} it is tested inside the ellipsoidal arm only
     * ({@code PJ_PROJECTION(cass)} returns early for {@code es == 0}), so on a
     * sphere it has no effect.
     */
    public static final String hyperbolic = "hyperbolic";

    /**
     * {@code +lsat} - the Landsat vehicle number, 1 to 5 ({@code som.cpp:307-313}).
     * Read with {@code pj_param}'s <b>{@code i}</b> sigil, so its grammar is decimal
     * digits and nothing else; see {@code Proj4Parser.parseIntStrict}.
     * <p>
     * <b>Upstream has no default and 0 is out of range</b>, so a bare
     * {@code +proj=lsat} is an error there. {@code LandsatProjection} keeps 1 and
     * {@link #path} 120 as its defaults instead - see that class for why, and for
     * what has to be re-pinned before they can become 0.
     */
    public static final String lsat = "lsat";

    /**
     * {@code +no_off} - the documented spelling of {@code omerc}'s "no offset" switch.
     * Upstream tests <b>either</b> spelling ({@code omerc.cpp:139-144},
     * {@code pj_param(..., "tno_off").i || pj_param(..., "tno_uoff").i}); Proj4J
     * recognised only the backwards-compatible {@link #no_uoff}, so the spelling the
     * documentation and most definitions use was silently dropped.
     */
    public static final String no_off = "no_off";

    /**
     * {@code +lon_1}, {@code +lon_2} - the longitudes of {@code omerc}'s two-point
     * form ({@code omerc.cpp:152-155}, read through {@code pj_param}'s {@code "r"}
     * sigil, so they take every angular syntax). {@code +lat_1}/{@code +lat_2} were
     * already dispatched, so a two-point definition could previously supply only its
     * latitudes and silently kept {@code lon_1 = lon_2 = 0}.
     */
    public static final String lon_1 = "lon_1";
    public static final String lon_2 = "lon_2";

    /**
     * {@code +n} - required by {@code urmfps} ({@code urmfps.cpp:56-66}: absent is
     * "Missing parameter n.", and it must lie in {@code ]0,1]}), by {@code urm5}
     * ({@code urm5.cpp:36-47}, same range) and by {@code gn_sinu}
     * ({@code gn_sinu.cpp:180-198}, which requires only {@code n > 0}).
     * Undocumented upstream in all three cases; all three read it with
     * {@code pj_param}'s {@code d} sigil.
     */
    public static final String n = "n";

    /**
     * {@code +m} - required by {@code gn_sinu} alongside {@link #n}
     * ({@code gn_sinu.cpp:184-187} is "Missing parameter m.", {@code :195-198} is
     * {@code m >= 0}). <b>Undocumented upstream</b>, and the sole
     * {@code builtins.gie} {@code gn_sinu} row is {@code +m=1 +n=2}, so the operator
     * is unreachable without it.
     * <p>
     * Dispatched on {@code GeneralSinusoidalProjection} only. {@code sinu},
     * {@code eck6} and {@code mbtfps} share {@code gn_sinu.cpp}'s kernel but hard-code
     * their own {@code m}/{@code n} and never read either key - which is why
     * {@code McBrydeThomasFlatPolarSinusoidalProjection}, a sibling subclass of the
     * same base, must not receive it.
     */
    public static final String m = "m";

    /**
     * {@code +q} - {@code urm5}'s optional cubic term, default 0
     * ({@code urm5.cpp:49}, {@code pj_param(..., "dq").f / 3.}). Undocumented
     * upstream. Read with the {@code d} sigil, and unrelated to the {@code q} that
     * {@code io.cpp:12520} lists as a unitless scale in the CRS parser.
     */
    public static final String q = "q";

    /**
     * {@code +inc_angle}, {@code +ps_rev}, {@code +asc_lon} - the three orbital
     * parameters of {@code +proj=som} ({@code som.cpp:250-270}).
     * <p>
     * {@code +inc_angle} and {@code +asc_lon} are read with {@code pj_param}'s
     * <b>{@code r}</b> sigil, so they take every angular syntax {@code dmstor}
     * accepts - and {@code builtins.gie} exercises exactly that, giving two of its
     * four {@code som} blocks {@code +inc_angle=1.7157253262878522r} with the radian
     * suffix. {@code +ps_rev} is a plain {@code d}, in days per revolution.
     * <p>
     * Upstream defaults all three to 0 and 0 passes all three of its own range
     * checks, so before these keys were dispatched a fully-specified {@code som}
     * definition would have projected from a zero-inclination zero-period orbit.
     */
    public static final String inc_angle = "inc_angle";
    public static final String ps_rev = "ps_rev";
    public static final String asc_lon = "asc_lon";

    /**
     * {@code +path} - the orbital path number of {@code misrsom} (and, upstream, of
     * {@code lsat}), {@code som.cpp:287}. Read with {@code pj_param}'s <b>{@code i}</b>
     * sigil, whose grammar is digits and nothing else: {@code param.cpp:180-187} runs
     * {@code atoi} and then rejects the value if <i>any</i> character is outside
     * {@code 0-9}, so {@code +path=12a} and {@code +path=-5} are both errors rather
     * than 12 and -5. See {@code Proj4Parser}'s {@code parseIntStrict}.
     * <p>
     * Dispatched on {@code MisrSpaceObliqueMercatorProjection} <b>and</b> on
     * {@code LandsatProjection}, which is the same pair of operators that reads it
     * upstream ({@code som.cpp:287} for {@code misrsom}, {@code :318} for
     * {@code lsat}). It used to reach {@code misrsom} only, while
     * {@code LandsatProjection.initialize()} hard-coded {@code path = 120} behind a
     * {@code //FIXME} - so {@code +proj=lsat +path=2} returned path 120's map with no
     * error. That is why the conformance bridge classified this key
     * {@code CONDITIONAL} rather than {@code HONOURED}; with both dispatches in place
     * the two sets coincide and it can move.
     */
    public static final String path = "path";

    /**
     * {@code +no_rot} - {@code omerc}'s "do not rotate the (u,v) frame" switch
     * ({@code omerc.cpp:145}, {@code pj_param(..., "tno_rot").i}).
     * <p>
     * <b>Deliberately NOT registered in {@link #supportedParameters()}.</b>
     * {@code ObliqueMercatorProjection.rot} is a private field assigned
     * {@code true} unconditionally in that class's {@code initialize()} and there is
     * no setter, so {@code Proj4Parser} has nothing to hand the flag to. Registering
     * the key without dispatching it is the dangerous direction: it would make
     * {@code builtins.gie:5246} and {@code :5269} look executable and return a
     * rotated answer where PROJ returns an unrotated one. Register it here <i>and</i>
     * in the conformance bridge's {@code HONOURED} in the same change that gives
     * that class a setter.
     */
    public static final String no_rot = "no_rot";

    /**
     * The ten {@code +o_}-prefixed parameters of {@code +proj=ob_tran}
     * ({@code ob_tran.cpp:130-281}).
     * <p>
     * <b>These are not dispatched value by value.</b> {@code ob_tran_target_params}
     * rewrites {@code o_proj=xxx} into {@code proj=xxx} by advancing the token pointer
     * two characters ({@code ob_tran.cpp:159}, {@code args.argv[i] += 2}) and hands the
     * whole rewritten list to the child's initialiser, so it operates on the raw
     * argument <i>list</i> and not on a parsed map. {@code Proj4Parser.parse} therefore
     * passes {@code args} down to {@code parseProjection}, which makes one call to
     * {@code ObliqueTransformationProjection.setParameters(String[])}.
     * <p>
     * They are registered individually all the same, for two reasons: STRICT mode
     * checks the key set, and the conformance bridge's {@code toProj4Args()} filters
     * the definition through {@link #isSupported(String)} - so an unregistered
     * {@code +o_lat_p} would be dropped from the array before {@code setParameters}
     * ever saw it.
     * <p>
     * All nine angular ones are read through {@code pj_param}'s {@code r} sigil;
     * {@code +o_proj} is an {@code s}. {@code +o_alpha} beats {@code +o_lat_p} beats
     * the two-point {@code +o_lon_1}/{@code +o_lat_1}/{@code +o_lon_2}/{@code +o_lat_2}
     * form, chosen by presence and never by value.
     */
    public static final String o_proj = "o_proj";
    public static final String o_lat_p = "o_lat_p";
    public static final String o_lon_p = "o_lon_p";
    public static final String o_alpha = "o_alpha";
    public static final String o_lon_c = "o_lon_c";
    public static final String o_lat_c = "o_lat_c";
    public static final String o_lon_1 = "o_lon_1";
    public static final String o_lon_2 = "o_lon_2";
    public static final String o_lat_1 = "o_lat_1";
    public static final String o_lat_2 = "o_lat_2";

    /** Every key {@code ob_tran} reads, in {@code ob_tran.cpp}'s own order. */
    public static final String[] OB_TRAN_PARAMS = {
            o_proj, o_alpha, o_lon_c, o_lat_c, o_lat_p, o_lon_p,
            o_lon_1, o_lat_1, o_lon_2, o_lat_2
    };

    /**
     * The six vertical-axis keys. <b>None of them is dispatched by
     * {@code Proj4Parser}, and none of them needs to be</b> - the vertical stack
     * reads them straight off {@code ProjParams} in the pipeline layer
     * ({@code pipeline/Cs2csOperator} for {@code geoidgrids}/{@code vunits}/
     * {@code vto_meter}/{@code z_0}, {@code pipeline/PipelineFactory} for
     * {@code multiplier}). Registering them here does exactly one thing: it stops
     * STRICT mode rejecting a definition PROJ accepts.
     *
     * <table>
     * <caption>where each one is read upstream</caption>
     * <tr><th>key</th><th>upstream</th></tr>
     * <tr><td>{@code geoidgrids}</td><td>{@code datum_set.cpp} sets
     *     {@code has_geoid_vgrids}; {@code create.cpp:88-105} inserts
     *     {@code +proj=vgridshift +grids=}</td></tr>
     * <tr><td>{@code vunits}</td><td>{@code init.cpp:715-750}; looked up in
     *     {@code pj_list_linear_units()} and <b>wins over {@code vto_meter}</b>,
     *     which is then never read</td></tr>
     * <tr><td>{@code vto_meter}</td><td>{@code init.cpp:715-750}, same
     *     {@code num/den} ratio grammar as {@code +to_meter} -
     *     {@code Proj4Parser.parseToMeter} already implements it and its javadoc
     *     already names this key</td></tr>
     * <tr><td>{@code z_0}</td><td>{@code init.cpp}; always metres, and
     *     <b>undocumented</b></td></tr>
     * <tr><td>{@code geoid_crs}</td><td><b>not read by {@code pj_init} at all</b> -
     *     only the CRS parser honours it, and only when {@code +geoidgrids} is also
     *     present ({@code io.cpp:12750-12800} force-marks it used). To an operation
     *     it is an inert token</td></tr>
     * <tr><td>{@code multiplier}</td><td>{@code vgridshift}/{@code deformation}
     *     only; the auto-inserted {@code vgridshift} step deliberately does not
     *     inherit it and always runs at the default of {@code -1}</td></tr>
     * </table>
     */
    public static final String geoidgrids = "geoidgrids";
    public static final String vunits = "vunits";
    public static final String vto_meter = "vto_meter";
    public static final String z_0 = "z_0";
    public static final String geoid_crs = "geoid_crs";
    public static final String multiplier = "multiplier";

    /**
     * {@code +approx} - {@code tmerc}/{@code utm}'s documented escape hatch back to the
     * Evenden/Snyder series ({@code tmerc.cpp:558-561}). Read through {@code pj_param}'s
     * <b>{@code b}</b> sigil, so a bare {@code +approx} is true and {@code +approx=f} is
     * explicitly false; it is tested <i>before</i> {@link #algo} and wins over it.
     * <p>
     * PROJ 9.8.1 ships Poder/Engsager as the built-in {@code tmerc} algorithm and this
     * library now matches, so without this key there is no way to ask for the previous
     * behaviour - and the difference is unbounded away from the central meridian
     * (~0.8 mm at 6&deg;, metres at 20&deg;, kilometres beyond 45&deg;).
     */
    public static final String approx = "approx";

    /**
     * {@code +algo=evenden_snyder|poder_engsager|auto} - {@code tmerc}/{@code utm} only
     * ({@code tmerc.cpp:563-578}). An unrecognised value is "unknown value for +algo"
     * upstream; {@code TransverseMercatorProjection.setAlgorithm} raises the equivalent,
     * so no validation is duplicated here.
     */
    public static final String algo = "algo";


    private static Set<String> supportedParams = null;

    /**
     * The set of PROJ.4 keys this library recognises.
     * <p>
     * <b>Note this allow-list is stricter than PROJ.</b> PROJ has no
     * enumeration of valid keys anywhere: {@code init.cpp} retains every token
     * verbatim and recognition is pull-based, so an unrecognised {@code +key}
     * is silently ignored and never an error. Enforcement of this list is
     * therefore opt-in — see {@link Proj4Parser.ParseMode#STRICT}.
     *
     * @return the recognised keys
     */
    public static synchronized Set supportedParameters() {
        if (supportedParams == null) {
            supportedParams = new TreeSet<String>();

            supportedParams.add(a);
            supportedParams.add(rf);
            supportedParams.add(f);
            supportedParams.add(alpha);
            supportedParams.add(es);
            supportedParams.add(e);
            supportedParams.add(b);
            supportedParams.add(datum);
            supportedParams.add(ellps);
            supportedParams.add(h);
            supportedParams.add(h_0);      // Just for col_urban

            supportedParams.add(R);
            for (String key : SPHERIFICATION_PARAMS) {
                supportedParams.add(key);
            }

            supportedParams.add(k);
            supportedParams.add(k_0);
            supportedParams.add(lat_ts);
            supportedParams.add(lat_0);
            supportedParams.add(lat_1);
            supportedParams.add(lat_2);
            supportedParams.add(lon_0);
            supportedParams.add(lonc);

            supportedParams.add(x_0);
            supportedParams.add(y_0);

            supportedParams.add(proj);
            supportedParams.add(south);
            supportedParams.add(towgs84);
            supportedParams.add(to_meter);
            supportedParams.add(units);
            supportedParams.add(nadgrids);
            supportedParams.add(pm);
            supportedParams.add(axis);

            supportedParams.add(gamma);       // Just for Oblique Mercator projection
            supportedParams.add(no_uoff);     // Just for Oblique Mercator projection
            supportedParams.add(no_off);      // Just for Oblique Mercator projection
            supportedParams.add(lon_1);       // Just for Oblique Mercator, two-point form
            supportedParams.add(lon_2);       // Just for Oblique Mercator, two-point form
            // Registered only now that ObliqueMercatorProjection.setNoRot exists AND
            // Proj4Parser dispatches it. Registering it earlier would have been the
            // dangerous direction: the conformance bridge would have called
            // builtins.gie:5246/:5269 executable and they would have returned a ROTATED
            // answer where PROJ returns an unrotated one - a plausible wrong number
            // instead of an honest NOT_IMPLEMENTED. Add to Proj4jCapabilities.HONOURED in
            // the same change, never separately.
            supportedParams.add(no_rot);      // Just for Oblique Mercator projection
            supportedParams.add(n);           // urmfps, urm5 and gn_sinu
            supportedParams.add(m);           // Just for gn_sinu
            supportedParams.add(q);           // Just for urm5
            supportedParams.add(approx);      // Just for tmerc / utm
            supportedParams.add(algo);        // Just for tmerc / utm
            supportedParams.add(zone);        // Just for Transverse Mercator projection

            supportedParams.add(shape);       // Just for Peirce Quincuncial
            supportedParams.add(scrollx);     // Just for Peirce Quincuncial, +shape=horizontal
            supportedParams.add(scrolly);     // Just for Peirce Quincuncial, +shape=vertical
            // +azi reaches spilhaus, tpers AND labrd - the whole registered set of the
            // four operators that read it upstream. Registering it while it reached only
            // Spilhaus is what kept tpers out of Registry: it would have made
            // "+proj=tpers +azi=20" a silently unrotated map. Never widen Registry for an
            // +azi reader without widening the dispatch in the same change.
            supportedParams.add(azi);         // spilhaus, tpers, labrd (upstream: also isea)
            supportedParams.add(rot);         // Just for Spilhaus
            supportedParams.add(tilt);        // Just for tpers

            supportedParams.add(inc_angle);   // Just for som
            supportedParams.add(ps_rev);      // Just for som
            supportedParams.add(asc_lon);     // Just for som
            supportedParams.add(path);        // misrsom and lsat
            supportedParams.add(lsat);        // Just for lsat

            supportedParams.add(over);        // global: fwd_prepare and inv_finalize
            supportedParams.add(W);           // lagrng AND hammer - both, never one
            supportedParams.add(M);           // Just for hammer
            supportedParams.add(no_cut);      // Just for airy
            supportedParams.add(lat_b);       // Just for airy
            supportedParams.add(guam);        // Just for aeqd, ellipsoidal branch only
            supportedParams.add(hyperbolic);  // Just for cass, ellipsoidal branch only

            // ob_tran: registered so that STRICT accepts them and so that a filtering
            // caller does not strip them out of the argument array before
            // ObliqueTransformationProjection.setParameters(String[]) sees it. Not
            // dispatched individually - see OB_TRAN_PARAMS.
            for (String key : OB_TRAN_PARAMS) {
                supportedParams.add(key);
            }

            // Vertical axis. Read in the pipeline layer, never by Proj4Parser.
            supportedParams.add(geoidgrids);
            supportedParams.add(vunits);
            supportedParams.add(vto_meter);
            supportedParams.add(z_0);
            supportedParams.add(geoid_crs);
            supportedParams.add(multiplier);

            supportedParams.add(title);       // no-op
            supportedParams.add(no_defs);     // no-op
            supportedParams.add(wktext);      // no-op
        }
        return supportedParams;
    }

    public static boolean isSupported(String paramKey) {
        return supportedParameters().contains(paramKey);
    }

    public static void checkUnsupported(String paramKey) {
        if (!isSupported(paramKey)) {
            throw new UnsupportedParameterException(paramKey + " parameter is not supported");
        }
    }

    public static void checkUnsupported(Collection params) {
        for (Object s : params) {
            checkUnsupported((String) s);
        }
    }
}

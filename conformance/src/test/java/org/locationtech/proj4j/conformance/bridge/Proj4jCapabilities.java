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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.proj.ExtendedTransverseMercatorProjection;
import org.locationtech.proj4j.proj.Projection;
import org.locationtech.proj4j.proj.TransverseMercatorProjection;
import org.locationtech.proj4j.units.Angle;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * What the bridge is willing to assert proj4j actually <em>does</em> with a
 * parameter, on the single-projection {@code operation} path.
 *
 * <p><b>Why this is a hand-maintained table and not a read of
 * {@code Proj4Keyword.supportedParameters()}.</b> That allow-list answers "will
 * {@code Proj4Parser} refuse this key?", which is a different question from "will
 * proj4j act on it?". Three keys are in the allow-list and are read by the parser
 * yet have no effect on {@code Projection.projectRadians}:
 *
 * <ul>
 * <li>{@code +axis} — {@code Projection.axes} is only ever consulted by
 *     {@code BasicCoordinateTransform.java:152,177}, i.e. the CRS-to-CRS path.
 *     A bare {@code operation +proj=X +axis=neu} stores the order and then
 *     projects as though it were {@code enu}. PROJ, by contrast, inserts a real
 *     {@code +proj=axisswap} step ({@code create.cpp}
 *     {@code cs2cs_emulation_setup}).</li>
 * <li>{@code +pm} — likewise, only {@code BasicCoordinateTransform.java:160,170}
 *     reads {@code Projection.primeMeridian}.</li>
 * <li>{@code +zone} — applied only when the projection is a
 *     {@code TransverseMercatorProjection} or
 *     {@code ExtendedTransverseMercatorProjection}; silently dropped otherwise.</li>
 * </ul>
 *
 * <p>Trusting the allow-list would therefore silently mis-execute those, which is
 * the one outcome that must never happen: a wrong number that looks like a pass.
 * So the trust relation is inverted — the bridge enumerates what it vouches for,
 * and <em>everything else is {@link GieFailureKind#NOT_IMPLEMENTED}</em>, whether
 * the allow-list contains it or not.
 *
 * <p>{@code Proj4jCapabilitiesTest} asserts that every key in
 * {@code Proj4Keyword.supportedParameters()} appears in exactly one of the three
 * sets below. That test is the tripwire: when someone widens core's allow-list,
 * it fails and forces a decision here rather than letting an unclassified key
 * flow through.
 */
final class Proj4jCapabilities {

    private Proj4jCapabilities() {
    }

    /**
     * Keys proj4j reads and applies to the projection regardless of their value.
     *
     * <p>The ellipsoid group ({@code ellps a b es e rf f R} and the eight
     * spherification keys) mirrors {@code Proj4Parser.parseEllipsoid}, which
     * follows {@code ell_set.cpp:80-133} step for step: {@code +R} short-circuits,
     * then {@code +ellps} seeds, then {@code +a} resizes, then the first present
     * of {@code rf f es e b} reshapes, then the first present of the
     * {@code R_*} family spherifies.
     *
     * <p><b>The adams-family group is here rather than in {@link #CONDITIONAL}
     * because the set of proj4j classes that apply each key is exactly the set of
     * PROJ operators that read it.</b> {@code Proj4Parser} dispatches
     * {@code +shape}/{@code +scrollx}/{@code +scrolly} on
     * {@code PeirceQuincuncialProjection} and {@code +azi}/{@code +rot} on
     * {@code SpilhausProjection}, matching {@code adams.cpp:405-453} and
     * {@code spilhaus.cpp:133-136}, so no {@code +proj=}/value combination diverges
     * the way {@code +zone} does. {@code +scrollx} off {@code +shape=horizontal} is
     * ignored by both, unvalidated by both.
     *
     * <p><b>{@code +azi} was the one drift risk, and it has been resolved by widening the
     * dispatch rather than by weakening this table.</b> Upstream reads it in four operators:
     * {@code spilhaus} ({@code spilhaus.cpp:133}), {@code tpers} ({@code nsper.cpp:196}),
     * {@code labrd} ({@code labrd.cpp:117}) and {@code isea} ({@code isea.cpp:1024}). It used
     * to reach {@code SpilhausProjection} alone, which was sound only for as long as none of
     * the other three was registered - and {@code labrd} had already been registered, so it
     * was silently unsound. No corpus row exercises {@code labrd +azi}, which is exactly why
     * it survived. {@code Proj4Parser} now dispatches {@code +azi} to
     * {@code SpilhausProjection}, {@code TiltedPerspectiveProjection} and
     * {@code LabordeProjection} - every registered reader - so the two sets coincide again.
     * {@code isea} is unported, so its {@code +proj=} name is refused long before
     * {@code +azi} could matter.
     *
     * <p><b>The standing rule, which now has a precedent in both directions: never register a
     * {@code +proj=} name in {@code Registry} without checking every key its upstream operator
     * reads against {@code Proj4Parser}'s dispatch.</b> A missing name is
     * {@link GieFailureKind#NOT_IMPLEMENTED}, which is honest; a registered name with a
     * dropped parameter is a plausible wrong map. {@code tpers} was deliberately held out of
     * {@code Registry} for a stage for exactly this reason.
     */
    static final Set<String> HONOURED = set(
            // operator selection
            "proj",
            // ellipsoid: size, shape, spherification
            "ellps", "a", "b", "es", "e", "rf", "f", "R",
            "R_A", "R_V", "R_a", "R_g", "R_h", "R_lat_a", "R_lat_g", "R_C",
            // coordinate frame
            "lat_0", "lon_0", "lat_1", "lat_2", "lat_ts", "x_0", "y_0", "k", "k_0",
            // operator parameters proj4j's Projection base carries
            "alpha", "lonc", "gamma", "no_uoff", "h", "south",
            // peirce_q arrangement, and spilhaus's oblique framing
            "shape", "scrollx", "scrolly", "azi", "rot",
            // +tilt, tpers's rotation of the image plane out of the tangent plane
            // (nsper.cpp:186). tpers is registered as of this change, and +azi is now
            // dispatched per class to all three REGISTERED readers - spilhaus, tpers and
            // labrd - which is what made registering it safe, and which is why +azi can
            // stay here rather than moving to CONDITIONAL. isea is still unported, so its
            // +proj= name is refused before +azi could matter.
            "tilt",
            // +over. Global in PROJ (init.cpp:601, "bover"): fwd_prepare skips both of its
            // adjlon calls (fwd.cpp:82-83, :109-111) and inv_finalize skips its one
            // (inv.cpp:115-116). Proj4Parser dispatches it to Projection.setOver, and both
            // of Projection's forward funnels as well as inverseProjectRadians branch on
            // it, so there is no +proj=/value combination where it is silently dropped.
            "over",
            // +W and +M. +W is read by BOTH lagrng (lagrng.cpp:79-85, default 2) and hammer
            // (hammer.cpp:63-70, default .5); +M by hammer alone (:72-79, default 1).
            // Proj4Parser dispatches to both classes, which is the whole set of readers.
            // Registering +W for lagrng only was MEASURED to break builtins.gie:2596,
            // "+proj=hammer +a=6400000 +W=1", whose expect-failure row at (-180, 0) started
            // returning a plausible -18101933.598 from the dropped W. Capital W and M; +M is
            // NOT the lower-case +m of gn_sinu.
            "W", "M",
            // airy's two parameters, and airy's alone (airy.cpp:119-120). +no_cut is a 'b'
            // sigil, +lat_b an 'r'.
            "no_cut", "lat_b",
            // aeqd's Guam variant and cass's Vanua Levu variant. Both are read upstream
            // inside the `es != 0` arm only, and both classes reproduce that placement, so
            // on a declared sphere each is consumed and ignored exactly as upstream does.
            // NOTE +hyperbolic is pj_param_exists, NOT the 'b' sigil (cass.cpp:127), so
            // +hyperbolic=f is TRUE - which is why it appears in neither ANGLE_KEYS nor
            // DOUBLE_KEYS and why Proj4Parser tests it with containsKey.
            "guam", "hyperbolic",
            // +lsat, the Landsat vehicle number (som.cpp:307). An 'i' sigil like +path.
            "lsat",
            // +path, MOVED here from CONDITIONAL. Upstream som.cpp reads "ipath" for
            // misrsom (:287) and lsat (:318); Proj4Parser now dispatches it to BOTH
            // MisrSpaceObliqueMercatorProjection and LandsatProjection, and
            // LandsatProjection no longer hard-codes 120, so the set of classes that apply
            // it is exactly the set of operators that read it. The conditionalFailure()
            // branch for it is therefore gone.
            "path",
            // col_urban's reference height; omerc's two-point form and its off-switch
            // synonym. Upstream accepts +no_off OR +no_uoff (omerc.cpp:139-143) - proj4j
            // had only the latter, so a definition using the documented spelling was
            // silently ignored rather than rejected. urm5 also reads +n.
            //
            // +m and +q are now dispatched and appear below. They were deliberately withheld
            // for one stage while core's Proj4Keyword did not dispatch them, because claiming
            // to honour a key we ignore is the dangerous direction: the bridge would classify
            // a +m= operation as executable and it would return a plausible wrong answer
            // instead of NOT_IMPLEMENTED. The sequencing rule that follows from it: add a key
            // to BOTH sides in one change, and if it must be split, add it to
            // supportedParameters() FIRST and to this table SECOND.
            "h_0", "lon_1", "lon_2", "no_off", "n",
            // +no_rot, added in the SAME change as its Proj4Keyword registration and its
            // Proj4Parser dispatch. It could not be registered before that: `rot` was a
            // private field written unconditionally inside initialize(), which runs twice,
            // so a setter's value was discarded on the second pass. Registering it inert
            // would have made the bridge call builtins.gie:5246/:5269 executable and they
            // would have returned a ROTATED answer where PROJ returns an unrotated one.
            "no_rot",
            // som's three orbital parameters; ob_tran's child selector and its ten pole
            // parameters; gn_sinu's +m and urm5's +q.
            "inc_angle", "ps_rev", "asc_lon",
            "o_proj", "o_alpha", "o_lon_c", "o_lat_c", "o_lat_p", "o_lon_p",
            "o_lon_1", "o_lat_1", "o_lon_2", "o_lat_2",
            "m", "q",
            // tmerc's algorithm selection. PROJ 9.8.1 ships Poder/Engsager as the built-in
            // algorithm and proj4j now matches, so these two are the documented escape back
            // to the Evenden/Snyder series - without them there is no way to ask for the
            // previous behaviour, and the movement is unbounded far from the central
            // meridian (0.83 mm at 6 deg, 4 m at 20 deg, kilometres beyond 45 deg).
            // +approx is read with pj_param's 'b' sigil, so "+approx=f" means false rather
            // than being a bare presence flag; +algo is a string whose invalid values the
            // setter rejects with INVALID_PARAM_VALUE, as upstream does.
            "approx", "algo",
            // linear scaling of the projected side
            "units", "to_meter");

    /**
     * Keys with no numeric effect in PROJ either, so dropping them is free.
     *
     * <p>{@code +no_defs} is subtler than it looks and is <em>not</em> a
     * strictness switch: its only effect in {@code init.cpp:327} is suppressing
     * the implicit {@code +ellps=GRS80}. It is inert here because
     * {@link GieProjArgs#impliesGrs80()} already models that suppression, and
     * {@link ProjDefinitionValidator} already reports the resulting
     * "no ellipsoid at all" case as {@link GieFailureKind#INVALID_DEFINITION}.
     */
    static final Set<String> INERT = set("title", "wktext", "no_defs", "inv",
            // +geoid_crs is not read by pj_init at all: only the CRS parser honours it, and
            // only when +geoidgrids is also present (io.cpp:12750-12800 force-marks it used).
            // To an `operation` it is an inert token upstream too, so dropping it is free.
            "geoid_crs");

    /**
     * Keys whose value decides whether proj4j's behaviour matches PROJ's. See
     * {@link #conditionalFailure}.
     */
    static final Set<String> CONDITIONAL = set(
            "axis", "pm", "zone", "towgs84", "datum", "nadgrids",
            // +path is no longer here: see HONOURED. It moved in the same change that gave
            // LandsatProjection setLandsat/setPath and removed its hard-coded path = 120.
            // The vertical axis. proj4j reads all four in the pipeline layer
            // (pipeline/Cs2csOperator, pipeline/PipelineFactory) and never on the
            // single-projection operation path, so each is safe only at its identity value.
            "geoidgrids", "vunits", "vto_meter", "z_0", "multiplier",
            // +geoc is not in Proj4Keyword's allow-list at all, so it is not one of the
            // keys Proj4jCapabilitiesTest's sweep can reach - it is classified here
            // because the bridge still has to decide what to do with it, and because the
            // answer is now "route it": pipeline/Cs2csOperator implements
            // pj_geocentric_latitude, Projection.projectRadians does not.
            "geoc");

    /** Every key the bridge has a considered opinion about. */
    static Set<String> classified() {
        Set<String> all = new LinkedHashSet<String>();
        all.addAll(HONOURED);
        all.addAll(INERT);
        all.addAll(CONDITIONAL);
        return Collections.unmodifiableSet(all);
    }

    // ------------------------------------------------- the cs2cs-emulation route

    /**
     * The {@link #CONDITIONAL} keys that are {@link #conditionalFailure}s on the
     * single-projection path <em>only</em> because that path is the wrong path: PROJ
     * turns each of them into a hidden sub-operation
     * ({@code create.cpp cs2cs_emulation_setup}, plus the {@code vto_meter}/{@code z0}
     * lines of {@code fwd_finalize}/{@code inv_prepare}), and
     * {@code pipeline.Cs2csOperator} builds every one of them.
     *
     * <p>Deliberately <b>not</b> here: {@code zone}, which is ordinary parser dispatch and
     * not emulation at all, and {@code multiplier}, which belongs to
     * the {@code vgridshift}/{@code deformation} operators rather than to a projection.
     */
    private static final Set<String> EMULATION_KEYS = set(
            "axis", "pm", "towgs84", "datum", "nadgrids",
            "geoidgrids", "vunits", "vto_meter", "z_0", "geoc");

    /**
     * Whether this definition needs the hidden helper steps, and therefore belongs to
     * the pipeline engine rather than to {@code CRSFactory}.
     *
     * <h4>Why this is a routing question and not a capability question</h4>
     *
     * <p>Every key in {@link #EMULATION_KEYS} is implemented — in
     * {@code pipeline.Cs2csOperator}, which is where PROJ implements it too. What was
     * missing was a way to <em>reach</em> it: the bridge routed only
     * {@code +proj=pipeline}, {@code +init=} and the seven bare pipeline-only operators
     * to that engine, so a plain legacy proj-string such as
     * {@code proj=latlong datum=potsdam ellps=bessel} went to the single-projection path
     * and was correctly, but unnecessarily, reported {@code NOT_IMPLEMENTED}. That is
     * the same defect that had {@code axisswap.gie} at 2/27 with a complete
     * {@code AxisSwapOperator}, and it accounted for every one of
     * {@code DHDN_ETRS89.gie}'s 64 assertions.
     *
     * <p>The test is asked <em>per token, at its value</em>, by delegating to
     * {@link #conditionalFailure}: an inert value ({@code +axis=enu},
     * {@code +towgs84=0,0,0}, {@code +datum=WGS84}, {@code +vunits=m}) does not divert
     * anything, so a definition that used to take the single-projection path and pass
     * still takes it. Only definitions that were failing can change route.
     *
     * <p>Routing here is safe in the one direction that matters: {@code Cs2csOperator}
     * builds its projection with the same {@code Proj4Parser} on the same token list, so
     * it honours everything the single-projection path honours and refuses everything it
     * refuses — the emulation steps are strictly additional.
     *
     * <p>It is <b>not</b> safe on its own, and that is why
     * {@code Proj4jGieOperationFactory} still runs the token check before routing.
     * {@code Cs2csOperator} constructs its {@code Proj4Parser} in
     * {@code ParseMode.PROJ_COMPATIBLE}, which <em>retains and ignores</em> a key outside
     * the allow-list rather than refusing it. So rerouting a definition that also carries
     * a key proj4j drops would execute it and return a plausible wrong answer instead of
     * an honest refusal.
     *
     * @param a the definition
     * @return whether to route it to the pipeline engine
     */
    /**
     * @param key a parameter name
     * @return whether {@code pipeline.Cs2csOperator} rather than
     *         {@code Projection.projectRadians} is where this key takes effect
     */
    static boolean isEmulationKey(String key) {
        return EMULATION_KEYS.contains(key);
    }

    static boolean requiresCs2csEmulation(GieProjArgs a) {
        List<GieToken> tokens = a.tokens();
        for (int i = 0; i < tokens.size(); i++) {
            GieToken t = tokens.get(i);
            if (!EMULATION_KEYS.contains(t.key())) {
                continue;
            }
            // peek semantics: this is a routing decision, so it must not mark the token
            // used and thereby change what pr_list() reports.
            if (conditionalFailure(t.key(), t.value(), null) != null) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------- conditional rules

    /**
     * Whether a conditionally-honoured key, at this value and on this projection,
     * would make proj4j diverge from PROJ.
     *
     * @param key        one of {@link #CONDITIONAL}.
     * @param value      the token's value, possibly {@code null}.
     * @param projection the already-constructed projection, or {@code null} if it
     *                   is not available yet.
     * @return a {@link GieFailureKind#NOT_IMPLEMENTED} failure, or {@code null} if
     *         this key is safe to pass through.
     */
    static GieFailure conditionalFailure(String key, String value, Projection projection) {
        if ("axis".equals(key)) {
            // PROJ only inserts an axisswap step when the order is not "enu"
            // (create.cpp: `if (p && (0 != strcmp("enu", p->param)))`).
            if (!"enu".equals(value)) {
                return GieFailures.notImplemented(
                        "+axis=" + value + ": proj4j stores the axis order but applies it only in "
                                + "BasicCoordinateTransform, never in Projection.projectRadians, "
                                + "so a single-projection operation would silently ignore it "
                                + "(PROJ inserts a +proj=axisswap step)");
            }
            return null;
        }
        if ("pm".equals(key)) {
            if (value == null) {
                return GieFailures.notImplemented("+pm with no value");
            }
            if ("greenwich".equals(value)) {
                return null;
            }
            Double rad = ProjDefinitionValidator.projAngleRadians(value);
            if (rad != null && rad.doubleValue() == 0.0) {
                return null;
            }
            return GieFailures.notImplemented(
                    "+pm=" + value + ": proj4j applies the prime meridian only in "
                            + "BasicCoordinateTransform, not in Projection.projectRadians");
        }
        if ("zone".equals(key)) {
            if (projection instanceof TransverseMercatorProjection
                    || projection instanceof ExtendedTransverseMercatorProjection) {
                return null;
            }
            return GieFailures.notImplemented(
                    "+zone=" + value + " on +proj="
                            + (projection == null ? "?" : projection.getName())
                            + ": Proj4Parser applies +zone only to the two transverse Mercator "
                            + "classes and silently drops it otherwise");
        }
        if ("towgs84".equals(key)) {
            // A null Helmert is ignored by PROJ too. A non-null one makes PROJ
            // insert +proj=helmert plus a cart round-trip; proj4j's operation
            // path does neither.
            if (isNullHelmert(value)) {
                return null;
            }
            return GieFailures.notImplemented(
                    "+towgs84=" + value + ": PROJ inserts a +proj=helmert step "
                            + "(create.cpp cs2cs_emulation_setup); proj4j applies the datum shift "
                            + "only in BasicCoordinateTransform");
        }
        if ("datum".equals(key)) {
            // datum_set.cpp appends the datum's own defn to the paralist, so
            // +datum is only inert when that defn is a null towgs84.
            if ("WGS84".equals(value) || "NAD83".equals(value)) {
                return null;
            }
            if ("NAD27".equals(value)) {
                return GieFailures.notImplemented(
                        "+datum=NAD27 expands to +nadgrids=@conus,@alaska,@ntv2_0.gsb,"
                                + "@ntv1_can.dat, which PROJ turns into a +proj=hgridshift step");
            }
            return GieFailures.notImplemented(
                    "+datum=" + value + " expands to a non-null +towgs84, which PROJ turns into "
                            + "a +proj=helmert step");
        }
        if ("geoidgrids".equals(key)) {
            return GieFailures.notImplemented(
                    "+geoidgrids=" + value + ": PROJ turns this into a +proj=vgridshift step "
                            + "(create.cpp:88-105); proj4j builds it in Cs2csOperator, which the "
                            + "single-projection operation path does not go through");
        }
        if ("multiplier".equals(key)) {
            return GieFailures.notImplemented(
                    "+multiplier=" + value + ": read upstream by vgridshift and deformation, "
                            + "neither of which is a Registry projection here");
        }
        if ("vunits".equals(key)) {
            // The vertical unit is inert only when it is metres, i.e. the same as the default.
            if ("m".equals(value)) {
                return null;
            }
            return GieFailures.notImplemented(
                    "+vunits=" + value + ": proj4j scales the vertical axis only in "
                            + "Cs2csOperator, not in Projection.projectRadians");
        }
        if ("vto_meter".equals(key)) {
            Double v = ProjDefinitionValidator.projDouble(value);
            if (v != null && v.doubleValue() == 1.0) {
                return null;
            }
            return GieFailures.notImplemented(
                    "+vto_meter=" + value + ": proj4j scales the vertical axis only in "
                            + "Cs2csOperator, not in Projection.projectRadians");
        }
        if ("z_0".equals(key)) {
            Double v = ProjDefinitionValidator.projDouble(value);
            if (v != null && v.doubleValue() == 0.0) {
                return null;
            }
            return GieFailures.notImplemented(
                    "+z_0=" + value + ": proj4j offsets the vertical axis only in "
                            + "Cs2csOperator, not in Projection.projectRadians");
        }
        if ("nadgrids".equals(key)) {
            return GieFailures.notImplemented(
                    "+nadgrids=" + value + ": PROJ turns this into a +proj=hgridshift step "
                            + "(create.cpp cs2cs_emulation_setup); proj4j builds it in "
                            + "Cs2csOperator, which the single-projection operation path does "
                            + "not go through");
        }
        if ("geoc".equals(key)) {
            // Read with pj_param's 'b' sigil, so +geoc=F really is off and inert.
            if (!ProjDefinitionValidator.projBooleanValue(value)) {
                return null;
            }
            return GieFailures.notImplemented(
                    "+geoc: geocentric latitude is applied by fwd_prepare/inv_finalize "
                            + "(fwd.cpp:80-81, inv.cpp:139-140), which proj4j implements in "
                            + "Cs2csOperator and not in Projection.projectRadians");
        }
        throw new IllegalArgumentException("not a conditional key: " + key);
    }

    /** Whether a {@code +towgs84} list is all zeros, which PROJ ignores. */
    static boolean isNullHelmert(String value) {
        if (value == null) {
            return false;
        }
        String[] parts = value.split(",", -1);
        if (parts.length != 3 && parts.length != 7) {
            return false;
        }
        for (int i = 0; i < parts.length; i++) {
            Double d = ProjDefinitionValidator.projDouble(parts[i]);
            if (d == null || d.doubleValue() != 0.0) {
                return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------- value grammar

    /**
     * The keys PROJ reads with {@code pj_param} type {@code 'r'}, i.e. as an angle
     * converted to radians by {@code dmstor}. {@code +alpha}, {@code +lonc} and
     * {@code +gamma} are on this list: the docs are silent but {@code init.cpp}
     * and the {@code omerc}/{@code ocea} setups read them as angles.
     * {@code +azi} and {@code +rot} likewise — {@code spilhaus.cpp:133-136} builds
     * both accessors with an {@code "r"} prefix.
     */
    static final Set<String> ANGLE_KEYS = set(
            "lat_0", "lon_0", "lat_1", "lat_2", "lat_ts", "alpha", "lonc", "gamma",
            "R_lat_a", "R_lat_g", "azi", "rot",
            // tpers reads +tilt and +azi with the "r" sigil (nsper.cpp:186-187), and airy
            // reads +lat_b with it (airy.cpp:120).
            "tilt", "lat_b",
            // som reads +inc_angle and +asc_lon with the "r" sigil (som.cpp:250,259), and every
            // one of ob_tran's nine angular parameters likewise (ob_tran.cpp:230-281).
            "inc_angle", "asc_lon",
            "o_alpha", "o_lon_c", "o_lat_c", "o_lat_p", "o_lon_p",
            "o_lon_1", "o_lat_1", "o_lon_2", "o_lat_2",
            // omerc's two-point form: the latitudes were reachable via +lat_1/+lat_2 but
            // the longitudes had no keyword at all, so only half the form could be given.
            "lon_1", "lon_2");

    /** The keys PROJ reads with {@code pj_param} type {@code 'd'}. */
    static final Set<String> DOUBLE_KEYS = set(
            "x_0", "y_0", "k", "k_0", "a", "b", "es", "e", "rf", "f", "R", "h",
            "scrollx", "scrolly",
            // som's period of revolution; gn_sinu's and urm5's shape parameters; col_urban's
            // reference height. All plain 'd' sigils upstream.
            "ps_rev", "m", "n", "q", "h_0",
            // lagrng's and hammer's +W and hammer's +M: presence is tested with 't' and the
            // value then read with 'd' (lagrng.cpp:79-80, hammer.cpp:63-73), so both are
            // plain doubles and NOT angles.
            "W", "M");

    /**
     * Whether proj4j's value grammar can represent this value the way PROJ reads
     * it.
     *
     * <p>PROJ's grammar is strictly wider: {@code pj_atof} is C {@code strtod}
     * and stops at the first invalid character rather than failing, angles accept
     * DMS with {@code '}/{@code "}, a {@code d}/{@code D}/degree-sign suffix, an
     * {@code r}/{@code R} <em>radian</em> suffix and a trailing cardinal, and
     * {@code +to_meter} accepts a {@code num/den} ratio. Any value proj4j reads
     * differently is a silent wrong answer, so it is reported
     * {@link GieFailureKind#NOT_IMPLEMENTED} instead.
     *
     * <p>This mirrors {@code Proj4Parser}'s private {@code parseAngle}/
     * {@code parseDouble} rather than calling them, since they are not visible.
     * The mirror is six lines and it fails <em>closed</em>: if core widens its
     * grammar and this lags, the result is a conservative
     * {@code NOT_IMPLEMENTED}, never a wrong pass.
     *
     * @return a failure, or {@code null} when the two grammars agree.
     */
    static GieFailure valueGrammarFailure(String key, String value) {
        if (value == null) {
            return null;
        }
        if ("to_meter".equals(key) && value.indexOf('/') >= 0) {
            return GieFailures.notImplemented(
                    "+to_meter=" + value + ": PROJ accepts a num/den ratio, proj4j does not");
        }
        if (ANGLE_KEYS.contains(key)) {
            Double proj = ProjDefinitionValidator.projAngleRadians(value);
            if (proj == null) {
                return null;
            }
            Double ours = proj4jAngleRadians(value);
            if (ours == null) {
                return GieFailures.notImplemented(
                        "+" + key + "=" + value + ": PROJ reads " + proj
                                + " rad, proj4j cannot parse it");
            }
            if (!closeEnough(proj.doubleValue(), ours.doubleValue())) {
                return GieFailures.notImplemented(
                        "+" + key + "=" + value + ": PROJ reads " + proj
                                + " rad, proj4j reads " + ours + " rad");
            }
            return null;
        }
        if (DOUBLE_KEYS.contains(key) || "to_meter".equals(key)) {
            Double proj = ProjDefinitionValidator.projDouble(value);
            if (proj == null) {
                return null;
            }
            Double ours = proj4jDouble(value);
            if (ours == null) {
                return GieFailures.notImplemented(
                        "+" + key + "=" + value + ": PROJ reads " + proj
                                + ", proj4j cannot parse it");
            }
            if (!closeEnough(proj.doubleValue(), ours.doubleValue())) {
                return GieFailures.notImplemented(
                        "+" + key + "=" + value + ": PROJ reads " + proj
                                + ", proj4j reads " + ours);
            }
        }
        return null;
    }

    /**
     * A mirror of {@code Proj4Parser.parseAngle}: {@code Angle.parse} plus the
     * {@code r}/{@code R} radian suffix.
     *
     * @return radians, or {@code null} if proj4j would throw.
     */
    static Double proj4jAngleRadians(String s) {
        try {
            int length = s.length();
            if (length > 1) {
                char last = s.charAt(length - 1);
                if (last == 'r' || last == 'R') {
                    return Double.valueOf(Double.parseDouble(s.substring(0, length - 1)));
                }
            }
            return Double.valueOf(Angle.parse(s) * ProjectionMath.DTR);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A mirror of {@code Proj4Parser.parseDouble}. */
    static Double proj4jDouble(String s) {
        try {
            return Double.valueOf(Double.parseDouble(s.trim()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Agreement to 1e-12 relative. Loose enough to absorb the up-to-1-ULP
     * difference between {@code Math.toRadians} (which computes
     * {@code deg / 180 * PI}) and PROJ's {@code deg * M_PI / 180}, tight enough
     * that any real grammar divergence — DMS, a radian suffix, a ratio, a
     * cardinal — is orders of magnitude larger.
     */
    static boolean closeEnough(double a, double b) {
        if (a == b) {
            return true;
        }
        if (Double.isNaN(a) && Double.isNaN(b)) {
            return true;
        }
        double scale = Math.max(Math.abs(a), Math.abs(b));
        return Math.abs(a - b) <= 1e-12 * Math.max(1.0, scale);
    }

    // ----------------------------------------------------- Registry probing

    private static final Map<String, Boolean> RESOLVABLE = new ConcurrentHashMap<String, Boolean>();
    private static final Object STDERR_LOCK = new Object();

    /**
     * Whether {@code Registry.getProjection(name)} yields a usable projection.
     *
     * <p>Three names — {@code alsk}, {@code apian} and {@code bacon} — are
     * registered against the <b>abstract</b> {@code Projection} class, so
     * {@code Registry.getProjection} catches the {@code InstantiationException},
     * <b>prints a stack trace to {@code System.err}</b> and returns {@code null}
     * ({@code Registry.java:124-142}). Left alone that is 3 stack traces per
     * corpus sweep for something we already know and have classified, which
     * makes a conformance run look broken.
     *
     * <p>So the probe swaps {@code System.err} for a sink. That mutates a JVM
     * global, so it is done under a lock, once per name, and the answer is cached
     * — at most three suppressions per JVM no matter how large the corpus.
     */
    static boolean resolvable(Registry registry, String name) {
        if (name == null) {
            return false;
        }
        Boolean cached = RESOLVABLE.get(name);
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean ok;
        synchronized (STDERR_LOCK) {
            PrintStream saved = System.err;
            try {
                System.setErr(new PrintStream(new ByteArrayOutputStream(), true));
                ok = registry.getProjection(name) != null;
            } catch (RuntimeException e) {
                ok = false;
            } finally {
                System.setErr(saved);
            }
        }
        RESOLVABLE.put(name, Boolean.valueOf(ok));
        return ok;
    }

    private static Set<String> set(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(values)));
    }
}

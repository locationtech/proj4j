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

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.ConvergenceFailureException;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.UnsupportedParameterException;
import org.locationtech.proj4j.gie.GieIoUnits;
import org.locationtech.proj4j.pipeline.PipelineDefinitionException;
import org.locationtech.proj4j.pipeline.PipelineErrorCode;
import org.locationtech.proj4j.pipeline.PipelineFactory;
import org.locationtech.proj4j.resource.ClasspathResourceResolver;
import org.locationtech.proj4j.resource.ResourceResolvers;
import org.locationtech.proj4j.proj.Projection;

/**
 * The bridge from a gie operation definition to something proj4j can execute.
 *
 * <h2>What this is for</h2>
 *
 * <p>The gie corpus feeds proj4j operation strings that proj4j mostly cannot run:
 * 75 of its 780 {@code operation} commands are {@code +proj=pipeline}, another 340
 * name an operator outside proj4j's 93-entry {@code Registry}, and the parameter
 * vocabulary is far wider than proj4j's allow-list. So this factory's job is
 * <b>not</b> to make everything work. It is to be an honest, precise boundary: for
 * each operation, either produce something executable, or say exactly why not, so
 * that the conformance number distinguishes "we got the wrong answer" from "we have
 * not implemented this".
 *
 * <h2>The classification rule</h2>
 *
 * <blockquote>
 * <b>{@link GieFailureKind#INVALID_DEFINITION} is decided only by
 * {@link ProjDefinitionValidator}</b>, from PROJ 9.8.1's own name tables and
 * validation rules — <em>never</em> from a proj4j exception or its message. It
 * means "upstream would reject this too", which is what a gie
 * {@code expect failure} row asserts.
 * <br><br>
 * <b>Any exception proj4j raises that the validator did not predict proves only
 * that proj4j is stricter or narrower than PROJ</b>, so it is
 * {@link GieFailureKind#NOT_IMPLEMENTED} — or
 * {@link GieFailureKind#MISSING_GRID}/{@link GieFailureKind#NUMERICAL} when the
 * exception type itself is more specific. In particular
 * {@code UnsupportedParameterException} is <em>always</em>
 * {@code NOT_IMPLEMENTED}: PROJ has no allow-list at all, so refusing a key can
 * never be a statement about the definition.
 * <br><br>
 * <b>PROJ's verdict wins.</b> The validator runs first, so a definition that is
 * both invalid upstream and unimplemented here is reported
 * {@code INVALID_DEFINITION}.
 * </blockquote>
 *
 * <p>Two consequences worth stating explicitly, because they are the reason the
 * rule is shaped this way:
 *
 * <ul>
 * <li>An unknown {@code +proj=} name is <em>not</em> automatically
 *     {@code NOT_IMPLEMENTED}. It is checked against
 *     {@link ProjTables#OPERATORS}, PROJ's own 186-entry list: unknown to PROJ
 *     means {@code INVALID_DEFINITION}, known to PROJ but missing from
 *     {@code Registry} means {@code NOT_IMPLEMENTED}. Without that table the
 *     distinction is not decidable and {@code expect failure} rows become
 *     meaningless.</li>
 * <li>Dropping a token is never free. {@link GieProjArgs#toProj4Args()} filters the
 *     definition down to what {@code Proj4Parser} accepts so that an unknown key is
 *     <em>ignored</em> rather than fatal, exactly as PROJ ignores it — but if PROJ
 *     would have acted on the dropped token, this factory reports
 *     {@code NOT_IMPLEMENTED} rather than executing without it. See
 *     {@link Proj4jCapabilities}, which inverts the trust relation: the bridge
 *     enumerates what it vouches for and everything else fails closed.</li>
 * </ul>
 *
 * <h2>Never throws</h2>
 *
 * <p>Both factory methods always return a {@link GieOperation}. Besides
 * {@code Proj4jException}, four non-{@code Proj4jException} escapes are caught and
 * mapped — see {@link #mapConstructionThrowable}.
 */
public final class Proj4jGieOperationFactory implements GieOperationFactory {

    private final CRSFactory crsFactory;
    private final CoordinateTransformFactory transformFactory;
    private final Registry registry;
    private final PipelineFactory pipelineFactory;

    /**
     * Makes the vendored {@code src/test/resources/proj-data/} tree visible to core's
     * deterministic resolver chain, at a priority ahead of the two built-in prefixes.
     *
     * <p>Without this, no {@code +grids=}, {@code +xy_grids=}, {@code +z_grids=},
     * {@code +geoidgrids=} or {@code +nadgrids=} token in the corpus can resolve at all:
     * core searches {@code classpath:proj4j-data/grids/} and {@code classpath:proj4/nad/}
     * and, deliberately, never the working directory, while
     * {@link org.locationtech.proj4j.conformance.runner.GieGridAvailability.OnClasspath}
     * already answers {@code require_grid} from {@code /proj-data/}. The two were
     * disagreeing: the runner said the grid was present and core could not find it.
     *
     * <p>Registered once per classloader, in a static initialiser rather than the
     * constructor, because {@code ResourceResolvers.addResolver} appends and a second call
     * would put a duplicate in the chain.
     */
    static {
        ResourceResolvers.addResolver(new ClasspathResourceResolver(
                Proj4jGieOperationFactory.class.getClassLoader(), "proj-data", null, 10));
    }

    public Proj4jGieOperationFactory() {
        this.crsFactory = new CRSFactory();
        this.transformFactory = new CoordinateTransformFactory();
        this.registry = crsFactory.getRegistry();
        this.pipelineFactory = new PipelineFactory(registry);
    }

    // ------------------------------------------------------------- operation

    @Override
    public GieOperation create(String args) {
        GieProjArgs a;
        try {
            a = GieProjArgs.parse(args);
        } catch (RuntimeException e) {
            // GieProjArgs.parse is total, so this is unreachable; it is here
            // because "the factory never throws" must be true unconditionally.
            return new UnusableOperation(GieFailures.of(GieFailureKind.OTHER,
                    "could not tokenise the definition: " + args, e));
        }
        if (a.isEmpty()) {
            return new UnusableOperation(
                    GieFailures.invalidDefinition("empty operation definition"));
        }

        // 0. Not a proj-string at all. proj_create() also accepts an authority
        //    code, an OGC URN, WKT and PROJJSON, resolving them through proj.db -
        //    nkg.gie's 26 operations are all
        //    "urn:ogc:def:coordinateOperation:NKG::...". Those are perfectly valid
        //    upstream, so they are a database gap in proj4j and must not be
        //    mistaken for a malformed definition.
        GieFailure identifier = databaseIdentifierFailure(a);
        if (identifier != null) {
            return new UnusableOperation(identifier);
        }

        // 1. PROJ's own verdict first, so INVALID_DEFINITION wins over any
        //    proj4j gap that would also apply.
        //
        //    Skipped for a bare (non-pipeline) `+init=`, and only for that case:
        //    validateProjName would report a missing `+proj`, which is wrong -
        //    `+init=` supplies one through the expansion, and PROJ accepts it. The
        //    validator is still the authority for a pipeline, where it checks
        //    structure and whether each step names an operator PROJ knows at all;
        //    PipelineFactory cannot make that last call, since "unknown to PROJ"
        //    and "unimplemented here" are indistinguishable from inside proj4j.
        boolean legacyInit = a.contains("init");
        // A bare `+proj=<conversion or transformation>` - `+proj=axisswap order=2,1`,
        // `+proj=unitconvert xy_in=m xy_out=dm`, `+proj=hgridshift grids=...` - is a
        // complete PROJ operation with neither `+step` nor `+init=`, and it belongs to the
        // pipeline engine: those operators live there, not in Registry. Routing only
        // pipelines and `+init=` here left axisswap.gie at 2/27 and unitconvert.gie at
        // 0/16 with both operators complete and passing their own unit tests, and reported
        // them as "+proj=X is a PROJ operator but Registry does not resolve it".
        boolean bareOperator = PipelineFactory.handlesOperator(a.peek("proj"));
        // A plain legacy proj-string carrying `+towgs84`, `+datum=`, `+nadgrids`,
        // `+geoidgrids`, `+axis`, `+pm` or a vertical unit is a cs2cs-style operation,
        // and PROJ runs it through exactly the same cs2cs_emulation_setup it runs an
        // `+init=` through. It therefore belongs to the pipeline engine too, and routing
        // it here rather than to CRSFactory is what unlocks it - see
        // Proj4jCapabilities.requiresCs2csEmulation for why this is a routing decision
        // and not a widening of what the bridge vouches for.
        boolean cs2csEmulation = Proj4jCapabilities.requiresCs2csEmulation(a);
        if (a.isPipeline() || !legacyInit) {
            GieFailure upstream = ProjDefinitionValidator.validate(a);
            if (upstream != null) {
                return new UnusableOperation(upstream);
            }
        }

        // 2. Pipelines, legacy `+init=` definitions, bare pipeline-only operators, and
        //    anything carrying a cs2cs-emulation key go to the pipeline engine. It
        //    expands `+init=` out of the PROJ.4 init dictionaries under
        //    `use_proj4_init_rules` semantics - the only semantics available here,
        //    since there is no proj.db - and builds the hidden cs2cs-emulation steps
        //    that make `+towgs84`, `+nadgrids`, `+geoidgrids`, `+pm` and `+axis` mean
        //    anything.
        if (a.isPipeline() || legacyInit || bareOperator) {
            return createFromPipelineEngine(args);
        }
        if (cs2csEmulation) {
            //    Rerouting must not smuggle a token past the token-level check.
            //    Cs2csOperator parses with Proj4Parser in PROJ_COMPATIBLE mode, which
            //    *retains and ignores* a key outside the allow-list rather than refusing
            //    it - so without this gate a definition combining `+towgs84` with a key
            //    proj4j silently drops would be executed and would answer plausibly and
            //    wrongly, which is the one outcome the classification contract exists to
            //    prevent. Every non-emulation token must therefore still be vouched for;
            //    only the emulation keys are exempted, and only because the engine we are
            //    routing to is where they are implemented.
            //    `+zone` and `+path` still need a projection to judge, and the pipeline
            //    engine does not hand one back - so it is resolved from the `+proj=` name
            //    alone, which is all the two rules ask (`instanceof` on the two transverse
            //    Mercator classes, and on misrsom). A name Registry cannot resolve leaves
            //    it null, and the rule then refuses, which is the fail-closed direction.
            Projection byName = registry.getProjection(a.peek("proj"));
            List<GieToken> conditionals = new ArrayList<GieToken>();
            GieFailure gap = classifyTokens(a, conditionals, true);
            if (gap != null) {
                return new UnusableOperation(gap);
            }
            for (int i = 0; i < conditionals.size(); i++) {
                GieToken t = conditionals.get(i);
                GieFailure f = Proj4jCapabilities.conditionalFailure(t.key(), t.value(), byName);
                if (f != null) {
                    return new UnusableOperation(f);
                }
            }
            return createFromPipelineEngine(args);
        }

        String projName = a.peek("proj");
        if (!Proj4jCapabilities.resolvable(registry, projName)) {
            return new UnusableOperation(GieFailures.notImplemented(
                    "+proj=" + projName + " is a PROJ 9.8.1 operator but proj4j's Registry "
                            + "does not resolve it (it is either unregistered or, like alsk, "
                            + "apian and bacon, registered against the abstract Projection "
                            + "class, in which case Registry.getProjection returns null)"));
        }

        // 3. Token-level gaps: anything the bridge does not vouch for.
        List<GieToken> conditionals = new ArrayList<GieToken>();
        GieFailure tokenGap = classifyTokens(a, conditionals, false);
        if (tokenGap != null) {
            return new UnusableOperation(tokenGap);
        }

        // 4. Build it. The implicit +ellps=GRS80 is appended per
        //    init.cpp:317-360; without it DatumParameters leaves a and es at NaN
        //    and every ellipsoid-less operation would return NaN, which would be
        //    scored as a numerical defect rather than the parser gap it is.
        GieProjArgs effective = a.withImplicitDefaults();
        String[] proj4Args = effective.toProj4Args();
        CoordinateReferenceSystem crs;
        try {
            crs = crsFactory.createFromParameters(null, proj4Args);
        } catch (Throwable e) {
            GieFailure f = mapConstructionThrowable(e);
            if (f == null) {
                rethrow(e);
            }
            return new UnusableOperation(f);
        }
        if (crs == null || crs.getProjection() == null) {
            return new UnusableOperation(GieFailures.notImplemented(
                    "CRSFactory produced no projection for " + effective));
        }
        Projection projection = crs.getProjection();

        // 5. Conditional keys whose verdict needs the constructed projection
        //    (+zone is applied only to the two transverse Mercator classes).
        for (int i = 0; i < conditionals.size(); i++) {
            GieToken t = conditionals.get(i);
            GieFailure f = Proj4jCapabilities.conditionalFailure(t.key(), t.value(), projection);
            if (f != null) {
                return new UnusableOperation(f);
            }
        }

        boolean angular = ProjTables.ANGULAR_BOTH_SIDES.contains(projName);
        GieIoUnits left = GieIoUnits.RADIANS;
        GieIoUnits right = angular ? GieIoUnits.RADIANS : GieIoUnits.CLASSIC;
        boolean inverted = invertedFlag(a);

        return new SingleProjectionOperation(effective.toString(), projection, left, right,
                inverted, angular);
    }

    // ------------------------------------------------------------ the pipeline

    /**
     * Build a {@code +proj=pipeline} (or a legacy {@code +init=} operation, which
     * PROJ treats as a one-step pipeline because {@code pj_init} runs the same
     * {@code cs2cs_emulation_setup} either way).
     *
     * @param args the definition as written in the corpus
     * @return an executable operation, or an {@link UnusableOperation} saying why not
     */
    private GieOperation createFromPipelineEngine(String args) {
        try {
            return new PipelineGieOperation(args, pipelineFactory.create(args));
        } catch (Throwable e) {
            GieFailure f = mapPipelineThrowable(e);
            if (f == null) {
                rethrow(e);
            }
            return new UnusableOperation(f);
        }
    }

    /**
     * Classify a pipeline construction failure.
     *
     * <p>{@link PipelineDefinitionException} is the only throwable that can say
     * whether <em>upstream</em> would have rejected the definition, and it is
     * therefore checked before the generic {@code Proj4jException} mapping — which
     * would otherwise report every pipeline rejection as
     * {@link GieFailureKind#NOT_IMPLEMENTED} and lose the distinction an
     * {@code expect failure} row depends on.
     *
     * @return the failure, or {@code null} if the throwable must be rethrown
     */
    static GieFailure mapPipelineThrowable(Throwable e) {
        if (e instanceof PipelineDefinitionException) {
            PipelineDefinitionException p = (PipelineDefinitionException) e;
            return GieFailures.of(pipelineKind(p), "pipeline: " + describe(e), e);
        }
        return mapConstructionThrowable(e);
    }

    /**
     * Which {@link GieFailureKind} a pipeline rejection is, given its
     * {@link PipelineErrorCode}.
     *
     * <p><b>A code whose {@link ErrorCause} is {@link ErrorCause#MISSING_GRID} is
     * {@link GieFailureKind#MISSING_GRID}, not {@link GieFailureKind#NOT_IMPLEMENTED}</b>.
     * The check keys on the <em>cause</em> rather than on a constant's identity so that the
     * bridge reflects core's taxonomy instead of naming a constant that can be renamed out
     * from under it — which is exactly what happened once already, when the enum was split
     * and {@code FILE_NOT_FOUND_OR_INVALID} kept its name while changing its meaning. The
     * classification here is the one {@link BridgeVerdictCrossTabTest} keeps honest; its
     * bottom-left cell is measured at 0 and must stay there.
     *
     * <h3>What core declares today</h3>
     *
     * <p>{@code FILE_NOT_FOUND_OR_INVALID} models
     * {@code PROJ_ERR_INVALID_OP_FILE_NOT_FOUND_OR_INVALID} (1029) and is declared
     * <b>{@code rejectedByProj = false}</b> with {@link ErrorCause#MISSING_GRID}
     * ({@code core/.../pipeline/PipelineErrorCode.java:86}). It no longer carries the
     * {@code +init=} population: an {@code +init=<file>:<section>} key that cannot be
     * resolved is {@code INVALID_INIT_KEY}, errno <b>1027</b> per
     * {@code 9.8.1:src/init.cpp:105,119,134}, with {@link ErrorCause#INVALID_PARAM_VALUE}
     * and {@code rejectedByProj = true}.
     *
     * <p><b>This branch is therefore load-bearing, not redundant.</b> Because
     * {@code rejectedByProj} is now {@code false}, deleting it does not fall through to
     * {@code INVALID_DEFINITION} — it falls through to {@code NOT_IMPLEMENTED}, which
     * {@code ExpectedFailureVerdict} never scores as genuine, so the errno-named passes
     * are lost. <b>Measured against the live gate on 2026-08-01: removing the branch turns
     * 7 assertions from {@code PASS} into {@code VACUOUS_EXPECTED_FAILURE}</b>, taking the
     * headline from {@code 7378/7895} to {@code 7371/7888} and failing the build with 7
     * {@code REGRESSED}. The seven are {@code tinshift.gie#1:0} and {@code #2:0} — the
     * {@code +file=i_do_not_exist} and {@code +file=proj.ini} operations at
     * {@code tinshift.gie:14} and {@code :18}, whose assertions are the two
     * {@code expect failure errno invalid_op_file_not_found_or_invalid} rows at {@code :15}
     * and {@code :19} — plus {@code deformation.gie#6:0}/{@code #7:0}/{@code #8:0},
     * {@code geotiff_grids.gie#38:0} and {@code more_builtins.gie#25:0}. Every one of them
     * names {@code errno invalid_op_file_not_found_or_invalid}, which is exactly the errno
     * {@code ExpectedFailureVerdict} requires before it will score a {@code MISSING_GRID}
     * genuine. So this is a re-keying, not a candidate for removal.
     *
     * <h3>Why {@code MISSING_GRID} rather than {@code INVALID_DEFINITION}</h3>
     *
     * <p>The engine raises this code when a grid or JSON file cannot be <em>read</em>,
     * and there the claim "PROJ 9.8.1 would reject this too" is simply false: the corpus
     * follows {@code +proj=hgridshift +grids=tests/test_hgrid.tif}
     * (geotiff_grids.gie:206) with {@code expect 5.875 55.375 0}, and the file is
     * vendored under {@code src/test/resources/proj-data/tests/}. PROJ reads it; proj4j
     * has no GeoTIFF reader. Thirteen more {@code .tif} {@code hgridshift} operations,
     * two {@code .gsb} ones, five {@code tinshift} {@code .json} ones and one
     * {@code deformation} {@code .ct2} one are the same story.
     *
     * <p>The bridge cannot tell "unreadable here" from "genuinely absent" from inside —
     * but it does not have to, because {@code ExpectedFailureVerdict} already resolves
     * exactly this ambiguity from the corpus side: {@code MISSING_GRID} is a genuine
     * pass under {@code invalid_op_file_not_found_or_invalid} and vacuous under anything
     * else. So {@code tinshift.gie:19} stays a pass, while the bare
     * {@code expect failure} rows in the blocks above are not passes. That is the honest
     * direction: it lowers the headline.
     *
     * @param p a pipeline construction failure
     * @return the kind; never {@code null}
     */
    private static GieFailureKind pipelineKind(PipelineDefinitionException p) {
        // code() is documented never-null and the constructor dereferences it, so no guard here.
        if (p.code().errorCause() == ErrorCause.MISSING_GRID) {
            return GieFailureKind.MISSING_GRID;
        }
        return p.isRejectedByProj()
                ? GieFailureKind.INVALID_DEFINITION
                : GieFailureKind.NOT_IMPLEMENTED;
    }

    /**
     * WKT and PROJJSON openers, plus the authority/URN forms.
     * {@code proj_create()} dispatches on these before it ever reaches
     * {@code pj_init}, so a definition in one of these shapes is a
     * <em>database</em> gap in proj4j, not a malformed definition.
     */
    private static final String[] WKT_KEYWORDS = {
            "PROJCS", "GEOGCS", "GEOCCS", "VERT_CS", "COMPD_CS", "LOCAL_CS", "FITTED_CS",
            "PROJCRS", "GEOGCRS", "GEODCRS", "GEODETICCRS", "VERTCRS", "COMPOUNDCRS",
            "BOUNDCRS", "ENGCRS", "PARAMETRICCRS", "TIMECRS", "DERIVEDPROJCRS",
            "COORDINATEOPERATION", "CONCATENATEDOPERATION", "COORDINATEMETADATA"
    };

    /**
     * @return a {@link GieFailureKind#NOT_IMPLEMENTED} failure when the definition
     *         is an authority code, an OGC URN, WKT or PROJJSON rather than a
     *         proj-string; {@code null} when it is a proj-string (or unrecognisable,
     *         in which case {@link ProjDefinitionValidator} gets to call it invalid).
     */
    private static GieFailure databaseIdentifierFailure(GieProjArgs a) {
        String raw = a.raw().trim();
        if (raw.startsWith("{")) {
            return GieFailures.notImplemented(
                    "PROJJSON definition: PROJ resolves this through its own JSON reader, "
                            + "which proj4j has no equivalent of");
        }
        int paren = raw.indexOf('[');
        if (paren > 0) {
            String head = raw.substring(0, paren).trim().toUpperCase();
            for (int i = 0; i < WKT_KEYWORDS.length; i++) {
                if (head.equals(WKT_KEYWORDS[i])) {
                    return GieFailures.notImplemented(
                            "WKT definition (" + head + "): proj4j's CRSFactory reads no WKT");
                }
            }
        }
        // A single token with a ':' and no '=': "EPSG:4326", or an OGC URN such as
        // "urn:ogc:def:coordinateOperation:NKG::ITRF2000_TO_DK".
        if (a.size() == 1) {
            GieToken t = a.tokens().get(0);
            if (!t.hasValue() && t.key().indexOf(':') >= 0) {
                return GieFailures.notImplemented(
                        "\"" + t.key() + "\" is an authority code or OGC URN, which "
                                + "proj_create() resolves through proj.db; proj4j has no "
                                + "operation database");
            }
        }
        return null;
    }

    /**
     * {@code +inv}, read with {@code pj_param} type {@code 'b'} so that
     * {@code +inv=F} correctly means "not inverted".
     */
    private static boolean invertedFlag(GieProjArgs a) {
        GieToken t = a.find("inv");
        if (t == null) {
            return false;
        }
        t.markUsed();
        return ProjDefinitionValidator.projBooleanValue(t.value());
    }

    /**
     * Walk the definition's tokens and report the first that proj4j cannot honour.
     *
     * <p>Conditional keys are collected rather than judged, because some of them
     * ({@code +zone}) need the constructed projection.
     *
     * @param forEmulationRoute when {@code true} the cs2cs-emulation keys are exempted,
     *                          because the definition is on its way to the pipeline
     *                          engine, which implements them. Everything else is judged
     *                          exactly as on the single-projection path — including
     *                          {@code +zone} and {@code +path}, whose verdict is
     *                          {@code Proj4Parser}'s dispatch table and is therefore the
     *                          same on both paths.
     */
    private GieFailure classifyTokens(GieProjArgs a, List<GieToken> conditionals,
            boolean forEmulationRoute) {
        List<GieToken> tokens = a.tokens();
        for (int i = 0; i < tokens.size(); i++) {
            GieToken t = tokens.get(i);
            String key = t.key();

            if (Proj4jCapabilities.INERT.contains(key)) {
                t.markUsed();
                continue;
            }
            if (forEmulationRoute && Proj4jCapabilities.isEmulationKey(key)) {
                continue;
            }
            if (Proj4jCapabilities.CONDITIONAL.contains(key)) {
                t.markUsed();
                if ("zone".equals(key) || "path".equals(key)) {
                    // The two conditionals whose verdict needs the constructed
                    // projection: Proj4Parser applies +zone to the two transverse
                    // Mercator classes and no others, and +path to misrsom and
                    // nothing else (upstream som.cpp reads it for lsat too).
                    conditionals.add(t);
                } else {
                    // Judged now, before construction, so that the reported reason
                    // is the primary one. Otherwise
                    // `+proj=latlong +nadgrids=ntf_r93.gsb` would be reported
                    // MISSING_GRID (Grid.fromNadGrids failing on a file we do not
                    // ship) when the real reason is that PROJ turns +nadgrids into
                    // a +proj=hgridshift step that proj4j's operation path has no
                    // notion of - a missing file would be the least of it.
                    GieFailure f = Proj4jCapabilities.conditionalFailure(key, t.value(), null);
                    if (f != null) {
                        return f;
                    }
                }
                continue;
            }
            if (!Proj4jCapabilities.HONOURED.contains(key)) {
                return GieFailures.notImplemented(
                        "+" + key + (t.hasValue() ? "=" + t.value() : "")
                                + ": proj4j does not implement this parameter, and PROJ 9.8.1 "
                                + "would have acted on it, so executing without it would "
                                + "produce a wrong answer rather than a skip");
            }
            t.markUsed();

            // A boolean key whose value says false: Proj4Parser tests
            // Map.containsKey, so it would switch the feature ON.
            if (contains(ProjDefinitionValidator.BOOLEAN_KEYS, key)
                    && !ProjDefinitionValidator.projBooleanValue(t.value())) {
                return GieFailures.notImplemented(
                        "+" + key + "=" + t.value() + " is PROJ-false, but Proj4Parser tests "
                                + "Map.containsKey and would enable the feature");
            }

            GieFailure grammar = Proj4jCapabilities.valueGrammarFailure(key, t.value());
            if (grammar != null) {
                return grammar;
            }

            if ("units".equals(key) && !ProjTables.proj4jResolvesUnit(t.value())) {
                return GieFailures.notImplemented(
                        "+units=" + t.value() + " is a PROJ unit id that proj4j's Units table "
                                + "does not carry; Units.findUnits silently returns METRES for "
                                + "it, which would scale the result wrongly");
            }
        }
        return null;
    }

    private static boolean contains(String[] haystack, String needle) {
        for (int i = 0; i < haystack.length; i++) {
            if (haystack[i].equals(needle)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------- crs_src/crs_dst

    @Override
    public GieOperation createCrsToCrs(String sourceCrs, String targetCrs) {
        if (sourceCrs == null || sourceCrs.trim().isEmpty()
                || targetCrs == null || targetCrs.trim().isEmpty()) {
            return new UnusableOperation(GieFailures.invalidDefinition(
                    "crs_src/crs_dst pair is incomplete: " + sourceCrs + " -> " + targetCrs));
        }
        String src = sourceCrs.trim();
        String dst = targetCrs.trim();

        CoordinateReferenceSystem source;
        CoordinateReferenceSystem target;
        try {
            source = createCrs(src);
        } catch (Throwable e) {
            GieFailure f = mapConstructionThrowable(e);
            if (f == null) {
                rethrow(e);
            }
            return new UnusableOperation(prefix(f, "crs_src " + src + ": "));
        }
        try {
            target = createCrs(dst);
        } catch (Throwable e) {
            GieFailure f = mapConstructionThrowable(e);
            if (f == null) {
                rethrow(e);
            }
            return new UnusableOperation(prefix(f, "crs_dst " + dst + ": "));
        }
        if (source == null || target == null) {
            return new UnusableOperation(GieFailures.notImplemented(
                    "proj4j resolved no CRS for " + (source == null ? src : dst)));
        }

        CoordinateTransform forward;
        CoordinateTransform inverse;
        try {
            forward = transformFactory.createTransform(source, target);
            inverse = transformFactory.createTransform(target, source);
        } catch (Throwable e) {
            GieFailure f = mapConstructionThrowable(e);
            if (f == null) {
                rethrow(e);
            }
            return new UnusableOperation(prefix(f, src + " -> " + dst + ": "));
        }

        // proj_create_crs_to_crs ends a geographic side in a +proj=unitconvert to
        // Degree, so that side's io units are DEGREES; a projected side is
        // PROJECTED. proj4j's BasicCoordinateTransform matches: it takes and
        // returns degrees for a geographic CRS.
        GieIoUnits left = crsUnits(source);
        GieIoUnits right = crsUnits(target);
        return new CrsToCrsOperation(src, dst, forward, inverse, left, right);
    }

    private CoordinateReferenceSystem createCrs(String spec) {
        if (spec.startsWith("+") || spec.startsWith("proj=")) {
            GieProjArgs a = GieProjArgs.parse(spec);
            return crsFactory.createFromParameters(null, a.withImplicitDefaults().toProj4Args());
        }
        return crsFactory.createFromName(spec);
    }

    private static GieIoUnits crsUnits(CoordinateReferenceSystem crs) {
        Projection p = crs.getProjection();
        if (p != null && ProjTables.ANGULAR_BOTH_SIDES.contains(p.getName())) {
            return GieIoUnits.DEGREES;
        }
        return GieIoUnits.PROJECTED;
    }

    private static GieFailure prefix(GieFailure f, String text) {
        return GieFailures.of(f.kind(), text + f.message(), f.cause());
    }

    // ------------------------------------------------------ exception mapping

    /**
     * Map a construction-time throwable to a failure kind.
     *
     * <p>{@code Proj4jException} is a {@code RuntimeException}, so
     * {@code catch (Proj4jException)} is not enough: four routes escape it
     * entirely, all confirmed in 1.4.3 and all reachable from corpus input.
     *
     * <ol>
     * <li><b>{@code NoSuchElementException}</b> — <b>this branch is now dead and is kept
     *     only as a backstop.</b> {@code Projection.setSouthernHemisphere} and
     *     {@code setHeightOfOrbit} used to throw a bare, message-less
     *     {@code NoSuchElementException} — unchecked, and <em>not</em> a
     *     {@code Proj4jException}, so it escaped every {@code catch (Proj4jException)} in
     *     the library. A behavioural sweep found 50 rows doing exactly that, in the 1.4.3
     *     baseline <em>and</em> in the then-current build; because it was unchanged since
     *     1.4.3, the differential golden regime could not see it at all. They now throw
     *     {@code UnsupportedParameterException}, handled above. Retained because a
     *     regression here must not go back to being invisible →
     *     {@link GieFailureKind#NOT_IMPLEMENTED}.</li>
     * <li><b>a bare {@code new Error()}</b> — {@code AxisOrder.fromString} throws
     *     it when {@code +axis} is not exactly 3 characters. Only an exactly-
     *     {@code java.lang.Error} instance is caught; a real
     *     {@code OutOfMemoryError} or {@code StackOverflowError} must never be
     *     swallowed, so anything else is rethrown.</li>
     * <li><b>{@code IllegalStateException}</b> — {@code GeocentricConverter}
     *     ({@code :122-125}) → {@link GieFailureKind#NUMERICAL}.</li>
     * <li><b>{@code NumberFormatException}</b> — historically raw from
     *     {@code Double.parseDouble}. proj4j now wraps most of these in
     *     {@code InvalidValueException}, but the mapping stays because
     *     {@code Integer.parseInt} on {@code +zone} and
     *     {@code PrimeMeridian.forName} can still surface it. PROJ's
     *     {@code pj_atof} never fails on garbage, so a parse failure is a
     *     narrower value grammar, not a bad definition →
     *     {@link GieFailureKind#NOT_IMPLEMENTED}.</li>
     * </ol>
     *
     * @return the failure, or {@code null} if the throwable must be rethrown.
     */
    static GieFailure mapConstructionThrowable(Throwable e) {
        if (e instanceof UnsupportedParameterException) {
            // PROJ has no allow-list and never errors on an unrecognised key, so this can
            // never be a statement about the definition - only about proj4j. That much is
            // safe to assert. The *mechanism* is not: this exception now has at least three
            // distinct sources, and all of them default to PROJECTION_NOT_IMPLEMENTED, so
            // cause() cannot discriminate:
            //
            //   Proj4Keyword:258        - the keyword allow-list rejected the key
            //   Projection:1018,:1035,  - +south / +h reached a projection that does not
            //             :1140,:1168     override the setter (e.g. +proj=nsper +h=..., where
            //                             PROJ *does* read +h, so ignoring it would return a
            //                             plausible wrong coordinate from a default orbit)
            //   Registry:162,:170       - a name registered but deliberately not implemented
            //
            // This message used to hard-code the allow-list explanation, which became wrong
            // for the Projection cases when they stopped throwing a bare
            // NoSuchElementException. Misreporting the reason is the specific defect the
            // error taxonomy exists to fix, so state only what is known and let the
            // exception's own message supply the mechanism.
            return GieFailures.of(GieFailureKind.NOT_IMPLEMENTED,
                    "proj4j refused a definition PROJ accepts (a proj4j gap, not a bad "
                            + "definition): " + e.getMessage(), e);
        }
        if (e instanceof ConvergenceFailureException) {
            return GieFailures.of(GieFailureKind.NUMERICAL, describe(e), e);
        }
        if (e instanceof Proj4jException) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (m.startsWith("Unknown nadgrid")) {
                return GieFailures.of(GieFailureKind.MISSING_GRID, describe(e), e);
            }
            // Everything else: the validator already established that PROJ
            // accepts this definition, so proj4j refusing it is a gap.
            return GieFailures.of(GieFailureKind.NOT_IMPLEMENTED, describe(e), e);
        }
        if (e instanceof NoSuchElementException) {
            return GieFailures.of(GieFailureKind.NOT_IMPLEMENTED,
                    "proj4j's Projection base throws NoSuchElementException for this parameter "
                            + "(setSouthernHemisphere/setHeightOfOrbit are not overridden by "
                            + "this projection)", e);
        }
        if (e instanceof IllegalStateException) {
            return GieFailures.of(GieFailureKind.NUMERICAL, describe(e), e);
        }
        if (e instanceof NumberFormatException) {
            return GieFailures.of(GieFailureKind.NOT_IMPLEMENTED,
                    "proj4j's value grammar is narrower than PROJ's pj_atof: " + describe(e), e);
        }
        if (e instanceof IllegalArgumentException) {
            // AxisOrder.Axis.fromChar for a character outside "ewnsud"; the
            // validator normally catches that first.
            return GieFailures.of(GieFailureKind.OTHER, describe(e), e);
        }
        if (e != null && e.getClass() == Error.class) {
            return GieFailures.of(GieFailureKind.OTHER,
                    "proj4j threw a bare java.lang.Error (AxisOrder.fromString does this for an "
                            + "+axis value that is not exactly 3 characters)", e);
        }
        if (e instanceof RuntimeException) {
            return GieFailures.of(GieFailureKind.OTHER, describe(e), e);
        }
        return null;
    }

    /**
     * Map a per-point throwable. Same routes as
     * {@link #mapConstructionThrowable}, but a {@code ProjectionException} at this
     * point means the coordinate was rejected, not the definition.
     *
     * @return the failure, or {@code null} if the throwable must be rethrown.
     */
    static GieFailure mapTransformThrowable(Throwable e) {
        if (e instanceof ConvergenceFailureException) {
            return GieFailures.of(GieFailureKind.NUMERICAL, describe(e), e);
        }
        if (e instanceof ProjectionException) {
            return GieFailures.of(GieFailureKind.COORD_OUT_OF_DOMAIN, describe(e), e);
        }
        if (e instanceof IllegalStateException) {
            // GeocentricConverter.java:122-125
            return GieFailures.of(GieFailureKind.NUMERICAL, describe(e), e);
        }
        if (e instanceof InvalidValueException) {
            // By this point the definition has already been built, so an
            // InvalidValueException is about the coordinate, not the definition -
            // e.g. ProjectionMath.normalizeLongitude rejecting a non-finite
            // longitude. Note PROJ does NOT reject a NaN longitude: fwd_prepare
            // tests for HUGE_VAL and its range checks are false for NaN, so NaN
            // flows through and comes back out. This is therefore a real behaviour
            // difference and the row should fail, not be skipped.
            return GieFailures.of(GieFailureKind.INVALID_COORD, describe(e), e);
        }
        if (e instanceof Proj4jException) {
            return GieFailures.of(GieFailureKind.OTHER, describe(e), e);
        }
        return mapConstructionThrowable(e);
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m);
    }

    private static void rethrow(Throwable e) {
        if (e instanceof RuntimeException) {
            throw (RuntimeException) e;
        }
        if (e instanceof Error) {
            throw (Error) e;
        }
        throw new IllegalStateException("checked exception from proj4j", e);
    }
}

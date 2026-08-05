/*
 * Copyright 2026 The Proj4J Contributors.
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
package org.locationtech.proj4j.pipeline;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.gie.GieIoUnits;
import org.locationtech.proj4j.vertical.VGridShiftOperator;

/**
 * Builds a {@link Pipeline} from a {@code +proj=pipeline} string, and a single
 * legacy operation from a {@code +init=} string.
 *
 * <h2>How a step's argument list is built — the part everything else rests on</h2>
 *
 * <p>{@code pipeline.cpp:479-489} builds each step's {@code argv} as
 *
 * <blockquote><b>the step's own tokens, then the pipeline's global tokens.</b></blockquote>
 *
 * <p>Appended, not prepended. Combined with {@code pj_param}'s first-match-wins that
 * makes a global token <em>lower</em> precedence than a step token, and a
 * {@code +init=} expansion — which is appended after both
 * ({@code init.cpp:352-397}) — lower precedence than either.
 *
 * <p>That three-level ordering is exactly what
 * {@code +proj=pipeline +towgs84=0,0,0 +step +init=epsg:4313 +inv …} exploits:
 * the global {@code towgs84=0,0,0} lands ahead of the {@code towgs84} the
 * {@code 4313} section declares, so the datum shift is switched off while the change
 * of ellipsoid is kept. {@code gigs/5103.1}, {@code 5111.1} and {@code 5112} all do
 * this, and the file comments say why: "turn off dual datum shift". Get the order
 * backwards and the shift is applied twice.
 *
 * <h2>What is rejected, and with which PROJ error</h2>
 *
 * <ul>
 * <li>a second {@code proj=pipeline} token — {@code WRONG_SYNTAX}. PROJ allows
 *     nesting only when the child is wrapped in an {@code +init}, which is why the
 *     check counts tokens rather than recursing;</li>
 * <li>a {@code +step} before {@code +proj=pipeline}, or no steps at all —
 *     {@code WRONG_SYNTAX};</li>
 * <li>a {@code proj=} or {@code o_proj=} token among the globals —
 *     {@code WRONG_SYNTAX} ({@code pipeline.cpp:438-452}, added against a fuzzer
 *     case);</li>
 * <li>more than one {@code +init=} in a non-pipeline operation —
 *     {@code WRONG_SYNTAX} ({@code init.cpp:477-482});</li>
 * <li>mismatched units between adjacent steps — {@code WRONG_SYNTAX};</li>
 * <li>a step whose forward direction does not exist — {@code WRONG_SYNTAX}; a
 *     missing inverse only disables the pipeline's reverse pass.</li>
 * </ul>
 *
 * <h2>Scope</h2>
 *
 * <p>The operator set is {@code longlat} (and its three aliases), {@code geocent},
 * {@code unitconvert}, {@code axisswap}, {@code cart}, {@code vgridshift},
 * {@code hgridshift}, {@code deformation}, {@code tinshift}, {@code affine},
 * {@code push}, {@code pop}, {@code set}, and every projection in {@link Registry}.
 * The eleven that are not projections are named by {@link #handlesOperator}, which is
 * how a caller knows that {@code +proj=axisswap order=2,1} — a complete operation with
 * no {@code +step} and no {@code +init=} — belongs here rather than on the
 * {@code CRSFactory} path.
 *
 * <p>Deliberately absent, each a refusal rather than a silent omission:
 * {@code helmert} as a <em>user-facing</em> operator (it exists only as the hidden
 * helper {@link Cs2csOperator} builds, which is the {@code +exact
 * +convention=position_vector} static form; the user-facing operator additionally has
 * {@code convention=coordinate_frame}, {@code transpose} and the seven time-dependent
 * rates, and implementing a subset of those would silently ignore a token PROJ acts
 * on); {@code gridshift} and {@code defmodel}, both of which need the GeoTIFF grid
 * reader to be wired into the pipeline layer; and {@code +proj=deformation +grids=},
 * the single-file three-channel Geodetic TIFF Grid form of an operator whose two-grid
 * form works.
 *
 * <p>{@code +t_epoch}/{@code +t_final} on {@code hgridshift} and {@code vgridshift} are
 * honoured through {@link TimeGatedOperator}. The time <em>dimension</em> is still not
 * transformed — no {@code +proj=unitconvert +t_in}, no {@code +proj=set +t} — but a
 * time-<em>gated</em> grid shift now behaves as upstream's does.
 *
 * <p>Instances hold an {@code +init=} expansion cache and a {@link Registry}; they
 * are cheap to create and safe to reuse from one thread.
 *
 * @since 1.5
 */
public final class PipelineFactory {

    private final Registry registry;
    private final InitFileExpander initFiles = new InitFileExpander();

    /** Uses a fresh {@link Registry}. */
    public PipelineFactory() {
        this(new Registry());
    }

    /**
     * @param registry resolves {@code +proj=} names and {@code +datum=} codes
     */
    public PipelineFactory(final Registry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("registry");
        }
        this.registry = registry;
    }

    /**
     * The operators this factory executes that are <b>not</b> in {@link Registry} —
     * PROJ's conversions and transformations, as opposed to its projections.
     *
     * <p>This list is the routing contract with anything that has to decide whether a
     * definition belongs to the pipeline engine or to the legacy
     * {@code CRSFactory}/{@code Projection} path. It deliberately excludes every name
     * {@link Cs2csOperator} reaches through {@code Registry} ({@code longlat} and its
     * aliases, {@code geocent}, and the projections), because those two paths must keep
     * agreeing about who owns a projection.
     *
     * <p>Sorted, so {@link #handlesOperator} can binary-search and so a reader can see
     * at a glance what is claimed.
     */
    private static final String[] PIPELINE_ONLY_OPERATORS = {
        "affine", "axisswap", "cart", "deformation", "hgridshift", "pop", "push", "set",
        "tinshift", "unitconvert", "vgridshift",
    };

    /**
     * Whether {@code +proj=<projName>} names an operator this factory executes itself
     * rather than delegating to {@link Registry}.
     *
     * <p>A bare {@code +proj=axisswap order=2,1} is a perfectly ordinary PROJ operation
     * — {@code axisswap.gie} and {@code unitconvert.gie} are made almost entirely of
     * them — but it is not a projection, so a caller that routes only
     * {@code +proj=pipeline} and {@code +init=} here will never reach the operator that
     * implements it, and will report the operator as unimplemented instead. That was
     * measured: 25 of {@code axisswap.gie}'s 27 assertions and all 16 of
     * {@code unitconvert.gie}'s failed for exactly that reason while both operators were
     * complete and passing their own unit tests.
     *
     * @param projName the value of {@code +proj=}, may be {@code null}
     * @return whether {@link #create} can build a one-step pipeline for it
     */
    public static boolean handlesOperator(final String projName) {
        if (projName == null) {
            return false;
        }
        for (int i = 0; i < PIPELINE_ONLY_OPERATORS.length; i++) {
            if (PIPELINE_ONLY_OPERATORS[i].equals(projName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param definition a proj-string
     * @return whether {@link #create} will accept it: a {@code +proj=pipeline}, a
     *         definition carrying an {@code +init=}, or a bare operator named by
     *         {@link #handlesOperator}
     */
    public static boolean isSupportedShape(final String definition) {
        final ProjParams params = ProjParams.parse(definition);
        return isPipeline(params) || params.has("init")
                || handlesOperator(params.value("proj"));
    }

    private static boolean isPipeline(final ProjParams params) {
        return "pipeline".equals(params.value("proj")) || params.countExact(ProjParams.STEP) > 0;
    }

    /**
     * Build an executable pipeline.
     *
     * <p>A definition that is <em>not</em> a pipeline is wrapped in a one-step one,
     * so that {@code +init=epsg:27572} and
     * {@code +proj=pipeline +step +init=epsg:27572} take the same path. That is not
     * merely convenient: it is what PROJ does, since a non-pipeline
     * {@code pj_init} runs the same {@code cs2cs_emulation_setup}.
     *
     * @param definition a proj-string, with or without {@code '+'} prefixes
     * @return the pipeline; never {@code null}
     * @throws PipelineDefinitionException if the definition cannot be built
     */
    public Pipeline create(final String definition) {
        final ProjParams all = ProjParams.parse(definition);
        if (all.isEmpty()) {
            throw new PipelineDefinitionException(PipelineErrorCode.MISSING_ARG,
                    "empty operation definition");
        }
        return isPipeline(all) ? createPipeline(definition, all)
                : createSingle(definition, all);
    }

    // ---------------------------------------------------------------- pipeline

    private Pipeline createPipeline(final String definition, final ProjParams all) {
        int pipelineTokens = 0;
        for (final String token : all.tokens()) {
            if ("proj=pipeline".equals(token)) {
                pipelineTokens++;
            }
        }
        if (pipelineTokens > 1) {
            throw new PipelineDefinitionException(PipelineErrorCode.WRONG_SYNTAX,
                    "nested pipelines are only allowed when the child pipeline is wrapped in an "
                            + "+init; found " + pipelineTokens + " +proj=pipeline tokens");
        }

        final List<ProjParams> slices = all.splitOnStep();
        final ProjParams globals = slices.get(0);

        // An <em>implicit</em> pipeline: +step tokens with no +proj=pipeline at all.
        // PROJ accepts this, but not through pj_init - pj_param_exists stops at the
        // first `step`, so pj_init would report a missing +proj. It is proj_create's
        // PROJStringParser that reads a bare +step as a one-step pipeline, and
        // more_builtins.gie:535 (`operation +step +proj=latlong +ellps=WGS84`)
        // asserts real coordinates for exactly that shape. Rejecting it would turn an
        // operation PROJ runs into one we claim PROJ refuses.
        if (pipelineTokens == 1 && !"proj=pipeline".equals(firstProjToken(globals))) {
            throw new PipelineDefinitionException(PipelineErrorCode.WRONG_SYNTAX,
                    "+step before +proj=pipeline");
        }
        if (pipelineTokens == 1) {
            for (final String token : globals.tokens()) {
                if (token.startsWith("o_proj=")) {
                    throw new PipelineDefinitionException(PipelineErrorCode.WRONG_SYNTAX,
                            "o_proj= operator before first step not allowed");
                }
                if (token.startsWith("proj=") && !"proj=pipeline".equals(token)) {
                    throw new PipelineDefinitionException(PipelineErrorCode.WRONG_SYNTAX,
                            "proj= operator before first step not allowed: +" + token);
                }
            }
        }
        if (slices.size() < 2) {
            throw new PipelineDefinitionException(PipelineErrorCode.WRONG_SYNTAX,
                    "+proj=pipeline with no +step");
        }

        // set_ellipsoid (pipeline.cpp:316-354): only the globals are consulted, and
        // the fallback is GRS80 rather than pj_init's WGS84.
        final double[] globalEllipsoid = globalEllipsoid(globals);

        // pipeline.cpp:487-488 appends the globals starting at i_pipeline + 1, i.e.
        // from just after the +proj=pipeline token - so that token itself is never
        // passed to a step (which would look like a nested pipeline), and neither is
        // anything written before it.
        final List<String> inherited = tokensAfterPipeline(globals);

        // Pipeline::stack (pipeline.cpp:139). One stack per pipeline, shared by every
        // push/pop step in it, reached upstream through P->parent->opaque.
        final CoordinateStack stack = new CoordinateStack();

        final List<PipelineStep> steps = new ArrayList<PipelineStep>(slices.size() - 1);
        for (int i = 1; i < slices.size(); i++) {
            // The step's own tokens first, the inherited globals after: appended, so
            // a step token shadows a global one.
            final ProjParams argv = slices.get(i).append(inherited);
            steps.add(buildStep(argv, stack));
        }

        propagateWhateverUnits(steps);
        checkUnitContinuity(steps);
        for (int i = 0; i < steps.size(); i++) {
            if (!steps.get(i).canRunForward()) {
                throw new PipelineDefinitionException(PipelineErrorCode.NO_INVERSE_OP,
                        "pipeline: inverse operation for step " + (i + 1) + " ("
                                + steps.get(i).description() + ") is not available");
            }
        }
        return new Pipeline(definition, steps, globalEllipsoid[0], globalEllipsoid[1]);
    }

    private static String firstProjToken(final ProjParams globals) {
        final int i = globals.find("proj");
        return i < 0 ? null : globals.tokens().get(i);
    }

    /**
     * The global tokens a step inherits: everything strictly after the
     * {@code proj=pipeline} token, or all of them when there is no such token (an
     * implicit pipeline).
     *
     * <p>Skipping the token itself is not cosmetic — upstream copies the globals
     * starting at {@code i_pipeline + 1} ({@code pipeline.cpp:487}), and a step that
     * inherited {@code proj=pipeline} would look like a nested pipeline to its own
     * initialiser.
     *
     * @param globals the slice before the first {@code step}
     * @return possibly empty, never {@code null}
     */
    private static List<String> tokensAfterPipeline(final ProjParams globals) {
        final List<String> all = globals.tokens();
        int at = -1;
        for (int i = 0; i < all.size(); i++) {
            if ("proj=pipeline".equals(all.get(i))) {
                at = i;
                break;
            }
        }
        return new ArrayList<String>(all.subList(at + 1, all.size()));
    }

    // ------------------------------------------------------------------ single

    private Pipeline createSingle(final String definition, final ProjParams all) {
        int inits = 0;
        for (final String token : all.tokens()) {
            if (token.startsWith("init=")) {
                inits++;
            }
        }
        if (inits > 1) {
            throw new PipelineDefinitionException(PipelineErrorCode.WRONG_SYNTAX,
                    "too many inits: " + inits);
        }
        final List<PipelineStep> steps = new ArrayList<PipelineStep>(1);
        // No stack: this wrapper is not a +proj=pipeline, so a push/pop step has no
        // parent and is the identity (pipeline.cpp:641-643).
        steps.add(buildStep(all, null));
        // init.cpp:576-581: a non-pipeline operation with no ellipsoid gets WGS84.
        return new Pipeline(definition, steps, Ellipsoid.WGS84.getEquatorRadius(),
                1.0 - Ellipsoid.WGS84.getB() / Ellipsoid.WGS84.getEquatorRadius());
    }

    // -------------------------------------------------------------------- step

    /**
     * One step, from its combined argument list.
     *
     * <p>Order of expansion follows {@code pj_init_ctx}: {@code +init=} first
     * ({@code init.cpp:506-513}), then the implicit {@code +ellps=GRS80}
     * ({@code init.cpp:540}). {@code +datum=} expansion is handled inside
     * {@link Cs2csOperator}, which reads the datum object directly rather than
     * splicing its definition string into the token list.
     *
     * @param argv  the step's combined argument list
     * @param stack the enclosing pipeline's coordinate stack, or {@code null} when this
     *              step is a one-step wrapper around a bare operation and therefore has
     *              no parent pipeline
     */
    private PipelineStep buildStep(final ProjParams argv, final CoordinateStack stack) {
        // pipeline.cpp:517-523: one toggle per exact "inv" token, over the combined
        // list, so a global +inv and a step +inv cancel.
        final boolean inverted = (argv.countExact("inv") & 1) == 1;

        ProjParams expanded = argv;
        final int initIndex = argv.find("init");
        if (initIndex >= 0) {
            expanded = expanded.append(initFiles.expand(argv.tokens().get(initIndex)));
        }
        expanded = appendImplicitEllipsoid(expanded);

        final String projName = expanded.value("proj");
        if (projName == null || projName.isEmpty()) {
            throw new PipelineDefinitionException(PipelineErrorCode.MISSING_ARG,
                    "missing +proj in step: " + argv);
        }
        if ("pipeline".equals(projName)) {
            throw new PipelineDefinitionException(PipelineErrorCode.WRONG_SYNTAX,
                    "nested pipelines are only allowed when the child pipeline is wrapped in an +init");
        }

        final PipelineOperator operator;
        if ("unitconvert".equals(projName)) {
            operator = new UnitConvertOperator(expanded);
        } else if ("axisswap".equals(projName)) {
            operator = new AxisSwapOperator(expanded);
        } else if ("vgridshift".equals(projName)) {
            // A user-written +proj=vgridshift step, distinct from the hidden one
            // Cs2csOperator builds for +geoidgrids: this one honours +multiplier, because it
            // is the operator's own parameter rather than an inherited token
            // (vgridshift.cpp:205-209).
            final String grids = expanded.value("grids");
            if (grids == null || grids.isEmpty()) {
                throw new PipelineDefinitionException(PipelineErrorCode.MISSING_ARG,
                        "+proj=vgridshift: +grids parameter missing.");
            }
            // vgridshift shares hgridshift's +t_epoch/+t_final bracket; upstream's own
            // comment in hgridshift.cpp asks for the two to be factored together, and
            // TimeGatedOperator is that factoring.
            operator = TimeGatedOperator.wrap(VGridShiftOperator.fromGrids(grids,
                    expanded.doubleValue("multiplier", VGridShiftOperator.DEFAULT_MULTIPLIER)),
                    expanded);
        } else if ("hgridshift".equals(projName)) {
            operator = TimeGatedOperator.wrap(
                    HGridShiftOperator.fromGrids(expanded.value("grids")), expanded);
        } else if ("deformation".equals(projName)) {
            operator = new DeformationOperator(registry, expanded);
        } else if ("cart".equals(projName)) {
            operator = new CartOperator(registry, expanded);
        } else if ("tinshift".equals(projName)) {
            operator = TinShiftOperator.fromFile(expanded.value("file"));
        } else if ("affine".equals(projName)) {
            operator = new AffineOperator(expanded);
        } else if ("set".equals(projName)) {
            operator = new SetOperator(expanded);
        } else if ("push".equals(projName) || "pop".equals(projName)) {
            operator = new PushPopOperator("push".equals(projName), expanded, stack);
        } else {
            operator = new Cs2csOperator(registry, expanded);
        }
        // pipeline.cpp:525-526, read with pj_param's 'b' sigil over the step's own
        // paralist - which by this point includes the inherited globals and the +init=
        // expansion, exactly as next_step->params does upstream.
        return new PipelineStep(operator, inverted,
                expanded.booleanValue("omit_fwd"), expanded.booleanValue("omit_inv"));
    }

    /**
     * {@code append_default_ellipsoid_to_paralist} ({@code init.cpp:313-350}):
     * {@code ellps=GRS80} is appended unless {@code +no_defs}, or there is no usable
     * {@code +proj}, or {@code +proj=pipeline}, or any of
     * {@code datum ellps a b rf f e es} is already present.
     *
     * <p>Note it is <b>not</b> suppressed by {@code +R}, and note "appended" —
     * a user token shadows it.
     */
    private static ProjParams appendImplicitEllipsoid(final ProjParams params) {
        if (params.has("no_defs")) {
            return params;
        }
        final int projIndex = params.find("proj");
        if (projIndex < 0) {
            return params;
        }
        final String projToken = params.tokens().get(projIndex);
        if (projToken.length() < 6 || "proj=pipeline".equals(projToken)) {
            return params;
        }
        final String[] shape = {"datum", "ellps", "a", "b", "rf", "f", "e", "es"};
        for (int i = 0; i < shape.length; i++) {
            if (params.has(shape[i])) {
                return params;
            }
        }
        return params.append("ellps=GRS80");
    }

    // ------------------------------------------------------------------- units

    /**
     * {@code pipeline.cpp:583-618}. A step declaring {@link GieIoUnits#WHATEVER} on
     * both sides adopts a neighbour's units so the continuity check below has
     * something to compare — right-to-left first, then left-to-right.
     *
     * <p>Upstream's comment names the case this must <em>not</em> break:
     * {@code proj=pipeline step proj=unitconvert xy_in=deg xy_out=rad step …}, whose
     * first step must keep a non-radian left-hand side or cs2cs would insert a
     * second degree-to-radian conversion. That is handled by the both-sides
     * precondition, not by a special case.
     */
    private static void propagateWhateverUnits(final List<PipelineStep> steps) {
        final int n = steps.size();
        for (int i = n - 2; i >= 0; i--) {
            final PipelineStep step = steps.get(i);
            if (step.left() == GieIoUnits.WHATEVER && step.right() == GieIoUnits.WHATEVER) {
                final PipelineStep rightStep = steps.get(i + 1);
                final GieIoUnits rl = rightStep.left();
                final GieIoUnits rr = rightStep.right();
                if (rl != rr || rl != GieIoUnits.WHATEVER) {
                    step.operator().overrideUnits(rl, rl);
                }
            }
        }
        for (int i = 1; i < n; i++) {
            final PipelineStep step = steps.get(i);
            if (step.left() == GieIoUnits.WHATEVER && step.right() == GieIoUnits.WHATEVER) {
                final PipelineStep leftStep = steps.get(i - 1);
                final GieIoUnits ll = leftStep.left();
                final GieIoUnits lr = leftStep.right();
                if (ll != lr || lr != GieIoUnits.WHATEVER) {
                    step.operator().overrideUnits(lr, lr);
                }
            }
        }
    }

    /** {@code pipeline.cpp:620-636}. {@code WHATEVER} on either side is compatible with anything. */
    private static void checkUnitContinuity(final List<PipelineStep> steps) {
        for (int i = 0; i + 1 < steps.size(); i++) {
            final GieIoUnits out = steps.get(i).right();
            final GieIoUnits in = steps.get(i + 1).left();
            if (out == GieIoUnits.WHATEVER || in == GieIoUnits.WHATEVER) {
                continue;
            }
            if (out != in) {
                throw new PipelineDefinitionException(PipelineErrorCode.WRONG_SYNTAX,
                        "pipeline: mismatched units between step " + (i + 1) + " (" + out
                                + ") and step " + (i + 2) + " (" + in + ")");
            }
        }
    }

    // --------------------------------------------------------------- ellipsoid

    /**
     * The pipeline's global ellipsoid, used for nothing but {@code P->geod} — the
     * geodesic a conformance comparator measures angular deviations with.
     *
     * <p>{@code set_ellipsoid} breaks the parameter list at the first {@code step}
     * before asking, so a step's {@code +ellps} is invisible here, and falls back to
     * <b>GRS80</b> rather than {@code pj_init}'s WGS84. About 1e-7 m at one degree:
     * irrelevant against a millimetre tolerance, material against an explicit
     * {@code +ellps=clrk66}.
     *
     * @return {@code {a, f}}
     */
    private double[] globalEllipsoid(final ProjParams globals) {
        final String name = globals.value("ellps");
        if (name != null) {
            final Ellipsoid e = registry.getEllipsoid(name);
            if (e != null) {
                return new double[] {e.getEquatorRadius(), 1.0 - e.getB() / e.getEquatorRadius()};
            }
        }
        final double a = globals.doubleValue("a", Double.NaN);
        if (!Double.isNaN(a) && a > 0) {
            final double rf = globals.doubleValue("rf", Double.NaN);
            if (!Double.isNaN(rf) && rf != 0) {
                return new double[] {a, 1.0 / rf};
            }
            final double f = globals.doubleValue("f", Double.NaN);
            if (!Double.isNaN(f)) {
                return new double[] {a, f};
            }
            final double b = globals.doubleValue("b", Double.NaN);
            if (!Double.isNaN(b) && b > 0) {
                return new double[] {a, 1.0 - b / a};
            }
        }
        return new double[] {Pipeline.GRS80_A, Pipeline.GRS80_F};
    }
}

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
package org.locationtech.proj4j.conformance.runner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.locationtech.proj4j.conformance.bridge.GieFailure;
import org.locationtech.proj4j.conformance.bridge.GieFailureKind;
import org.locationtech.proj4j.conformance.bridge.GieOperation;
import org.locationtech.proj4j.conformance.bridge.GieOperationFactory;
import org.locationtech.proj4j.conformance.manifest.AssertionKey;
import org.locationtech.proj4j.conformance.manifest.AssertionOutcome;
import org.locationtech.proj4j.conformance.parse.GieCommand;
import org.locationtech.proj4j.conformance.parse.GieCoord;
import org.locationtech.proj4j.conformance.parse.GieCoordParser;
import org.locationtech.proj4j.conformance.parse.GieFile;
import org.locationtech.proj4j.conformance.parse.GieVerb;
import org.locationtech.proj4j.conformance.parse.ProjStrtod;
import org.locationtech.proj4j.gie.GieComparator;
import org.locationtech.proj4j.gie.GieDirection;
import org.locationtech.proj4j.gie.GieIoUnits;
import org.locationtech.proj4j.gie.GieTolerance;

/**
 * Executes a lexed {@code .gie} file: the state machine of {@code 9.8.1:src/apps/gie.cpp}'s
 * {@code dispatch()} and its verb handlers, emitting one {@link GieAssertionResult} per
 * {@code expect} and {@code roundtrip}.
 *
 * <p>The lexer ({@code parse/}) decides <em>what the commands are</em>; the comparator
 * ({@code org.locationtech.proj4j.gie}) decides <em>whether a coordinate is close enough</em>; the
 * bridge decides <em>what a definition means</em>. This class owns only the state machine that joins
 * them, which is nonetheless where most of gie's surprises live.
 *
 * <h2>The state, and what resets it</h2>
 *
 * <p>Per operation block, reset by {@code operation()} ({@code gie.cpp:646-651}) and by
 * {@code crs_to_crs_operation()} ({@code gie.cpp:731-736}), which are the same five lines:
 *
 * <ul>
 *   <li>{@code direction} &rarr; forward</li>
 *   <li>{@code tolerance} &rarr; {@code tolerance("0.5 mm")}, i.e. {@code 5e-4} m</li>
 *   <li>{@code ignore} &rarr; nothing (the C sets 9999, an errno that cannot occur)</li>
 *   <li>{@code skip_test} &rarr; 0</li>
 * </ul>
 *
 * <p>Between {@code accept}/{@code expect} pairs inside a block, {@code direction} and
 * {@code tolerance} are therefore <strong>sticky</strong>: one {@code tolerance 1 cm} governs every
 * assertion until the next {@code operation}.
 *
 * <h3>Two pieces of state that are deliberately <em>not</em> reset per operation</h3>
 *
 * <p><strong>The accepted coordinate.</strong> {@code operation()} does not touch {@code T.a}. That
 * looks like an oversight and is load bearing: 121 assertions in the 9.8.1 corpus are evaluated with
 * no {@code accept} in their own block, and two of those are real coordinate comparisons rather than
 * {@code expect failure} — {@code gie/epsg_no_grid.gie:29}, where the {@code accept} precedes the
 * {@code crs_dst} that opens the block, and {@code gie/more_builtins.gie:469}, a {@code roundtrip 1}
 * written above its own {@code accept}. Clearing the coordinate would silently break both. It is
 * therefore carried across block boundaries here too, exactly as the C does.
 *
 * <p><strong>{@code crs_dst_is_lat_lon_or_y_x}.</strong> Also untouched by {@code operation()}, so it
 * leaks out of a {@code crs_src}/{@code crs_dst} block into any following plain {@code operation}. No
 * 9.8.1 file mixes the styles; {@link GieFileResult#leakedCrsDstFlagAssertions()} counts it rather
 * than assuming it.
 *
 * <h2>{@code require_grid} and the skip cascade</h2>
 *
 * <p>{@code dispatch()} ({@code gie.cpp:1234-1273}) tests {@code skip_test} <em>after</em>
 * {@code operation}, {@code crs_src} and {@code crs_dst} but <em>before</em> everything else:
 *
 * <pre>
 * if (T.skip_test) {
 *     if (0 == strcmp(cmnd, "expect")) return another_skip();
 *     return 0;
 * }
 * </pre>
 *
 * <p>So a missing grid turns every subsequent {@code expect} into a SKIP and <em>drops every other
 * verb</em> — including {@code accept}, {@code tolerance}, {@code direction} and, notably,
 * {@code roundtrip}, which is silently discarded rather than skipped. Both details are reproduced.
 *
 * <h2>{@code expect failure errno <const>}</h2>
 *
 * <p>Every named errno degenerates to a bare {@code expect failure}. The C resolves the name through
 * a table of {@code PROJ_ERR_*} constants and falls back to {@code 9999} for anything unrecognised
 * ({@code gie.cpp:1329}); legacy names still in the corpus, such as {@code pjd_err_axis} in
 * {@code axisswap.gie}, already hit that fallback upstream. proj4j's error taxonomy
 * ({@link GieFailureKind}) is coarser than PROJ's 17 constants, so insisting on an identity match
 * would manufacture failures for transforms that failed correctly for an adjacently-named reason —
 * 794 of the corpus's errno expectations are {@code coord_transfm_outside_projection_domain} alone.
 * The errno name is retained in the result detail for triage.
 *
 * <p><strong>It is not, however, used for nothing else.</strong> The errno <em>family</em> —
 * {@code invalid_op*} versus {@code coord_transfm*} — is the only reliable record of whether the row
 * asserts that PROJ refused a <em>definition</em> or refused a <em>coordinate</em>, and that
 * distinction decides whether a construction failure here is a genuine pass or a vacuous one. See
 * {@link ExpectedFailureVerdict}. Family, never identity: the identity match is still refused, for
 * the reason above.
 *
 * <h2>Thread safety</h2>
 *
 * <p>A {@code GieRunner} is immutable; all mutable state lives in a {@link Walk} created per
 * {@link #run}. Whether two runs may proceed concurrently depends on the
 * {@link GieOperationFactory}, not on this class.
 */
public final class GieRunner {

    /**
     * {@code T.tolerance = 5e-4} from {@code main()} ({@code gie.cpp:284}), and what every
     * {@code operation} resets to.
     */
    public static final double DEFAULT_TOLERANCE = GieTolerance.DEFAULT_TOLERANCE;

    /** {@code roundtrip} with no argument. */
    public static final int DEFAULT_ROUNDTRIPS = 100;

    /** {@code proj_roundtrip}'s accepted range for {@code n} ({@code gie.cpp:906}). */
    public static final int MIN_ROUNDTRIPS = 1;

    /** @see #MIN_ROUNDTRIPS */
    public static final int MAX_ROUNDTRIPS = 1000000;

    /** {@code torad_coord}'s default axis string ({@code gie.cpp:786}). */
    static final String DEFAULT_AXIS = "enut";

    /** The letters {@code torad_coord}/{@code todeg_coord} convert ({@code gie.cpp:792}). */
    private static final String ANGULAR_AXIS_LETTERS = "news";

    private static final double HUGE_VAL = GieCoord.HUGE_VAL;

    private final GieOperationFactory factory;
    private final GieGridAvailability grids;

    private GieRunner(GieOperationFactory factory, GieGridAvailability grids) {
        if (factory == null) {
            throw new IllegalArgumentException("factory");
        }
        this.factory = factory;
        this.grids = grids == null ? GieGridAvailability.NoneAvailable.INSTANCE : grids;
    }

    /**
     * @param factory resolves {@code operation} and {@code crs_src}/{@code crs_dst} definitions
     * @param grids answers {@code require_grid}
     * @return a runner
     */
    public static GieRunner using(GieOperationFactory factory, GieGridAvailability grids) {
        return new GieRunner(factory, grids);
    }

    /**
     * A runner that resolves grids against the vendored {@code proj-data/} directory.
     *
     * @param factory resolves definitions
     * @return a runner
     */
    public static GieRunner using(GieOperationFactory factory) {
        return new GieRunner(factory, GieGridAvailability.OnClasspath.INSTANCE);
    }

    /**
     * Walks one file.
     *
     * @param corpusPath the corpus-relative path used in every {@link AssertionKey}, e.g.
     *     {@code gie/builtins.gie}
     * @param file the lexed file
     * @return its assertions, in source order
     */
    public GieFileResult run(String corpusPath, GieFile file) {
        if (corpusPath == null || corpusPath.isEmpty()) {
            throw new IllegalArgumentException("corpusPath");
        }
        if (file == null) {
            throw new IllegalArgumentException("file");
        }
        return new Walk(corpusPath, file).go();
    }

    // ------------------------------------------------------------------------------ the state machine

    /** One pass over one file. Not reusable, not shared. */
    private final class Walk {

        private final String corpusPath;
        private final GieFile file;
        private final List<GieAssertionResult> results = new ArrayList<GieAssertionResult>();

        // ---- file-scope

        /** gie's {@code T.op_id}, less one: the 0-based operation block index of an AssertionKey. */
        private int blockIndex = -1;

        private int assertionIndex;
        private int blocks;
        private int leakedCrsDstFlag;

        /** Sticky across operations: applied to the next {@code operation}. */
        private boolean useProj4InitRules;

        /** Sticky across operations by omission, not by design. See the class comment. */
        private boolean crsDstIsLatLonOrYX;

        /**
         * Sticky across operations by omission: {@code operation()} never clears {@code T.a}.
         *
         * <p>Initialised to {@code proj_coord_error()} — gie's {@code T} is {@code memset} to zero, so
         * an {@code expect} before any {@code accept} reads {@code (0,0,0,0)} rather than an error
         * coordinate. Parsing the empty string is the supported way to obtain the error coordinate; the
         * distinction is invisible to the corpus, every file of which accepts before it expects.
         */
        private GieCoord accepted = GieCoordParser.parseCoord("");

        /** Raw source text of the {@code accept} in force, for the assertion content hash. */
        private String acceptedRaw = "";

        private String crsSrcArgs = "";
        private String crsDstArgs = "";
        private String crsSrcRaw = "";
        private String crsDstRaw = "";

        // ---- operation-scope

        private GieOperation operation;
        private String operationArgs = "";
        private String operationRaw = "";
        private String axisSpec;
        private GieComparator comparator = GieComparator.wgs84();
        private GieDirection direction = GieDirection.FORWARD;
        private double tolerance = DEFAULT_TOLERANCE;
        private String ignoreErrno;
        private String errnoName;
        private boolean skipTest;

        /** True when the block was opened by a {@code crs_src}/{@code crs_dst} pair. */
        private boolean crsToCrsBlock;

        Walk(String corpusPath, GieFile file) {
            this.corpusPath = corpusPath;
            this.file = file;
        }

        GieFileResult go() {
            List<GieCommand> commands = file.commands();
            for (int i = 0; i < commands.size(); i++) {
                dispatch(commands.get(i));
            }
            return new GieFileResult(corpusPath, results, blocks, leakedCrsDstFlag);
        }

        /** Port of {@code dispatch()}, including the order of its three pre-skip_test cases. */
        private void dispatch(GieCommand command) {
            switch (command.verb()) {
                case OPERATION:
                    operation(command);
                    return;
                case CRS_SRC:
                    crsSrcArgs = command.args();
                    crsSrcRaw = command.raw();
                    maybeCrsToCrs();
                    return;
                case CRS_DST:
                    crsDstArgs = command.args();
                    crsDstRaw = command.raw();
                    maybeCrsToCrs();
                    return;
                default:
                    break;
            }

            if (skipTest) {
                // gie.cpp:1243-1247. Only `expect` is even counted; every other verb, roundtrip
                // included, is dropped without trace until the next operation.
                if (command.verb() == GieVerb.EXPECT) {
                    record(command, AssertionOutcome.SKIP, "skipped: require_grid could not resolve a grid");
                }
                return;
            }

            switch (command.verb()) {
                case ACCEPT:
                    accepted = GieCoordParser.parseCoord(command.args());
                    acceptedRaw = command.raw();
                    return;
                case EXPECT:
                    expect(command);
                    return;
                case ROUNDTRIP:
                    roundtrip(command);
                    return;
                case DIRECTION:
                    direction(command.args());
                    return;
                case TOLERANCE:
                    tolerance = GieTolerance.tolerance(command.args());
                    return;
                case IGNORE:
                    ignoreErrno = Errno.canonical(GieTolerance.column(command.args(), 1));
                    return;
                case REQUIRE_GRID:
                    requireGrid(command.args());
                    return;
                case USE_PROJ4_INIT_RULES:
                    // gie.cpp:556 -- an exact string compare, so "TRUE" is false.
                    useProj4InitRules = "true".equals(command.args());
                    return;
                default:
                    // banner, verbose, echo, skip and the four block delimiters change no state that
                    // affects an outcome. `skip` has already stopped the lexer.
                    return;
            }
        }

        // ------------------------------------------------------------------------------ verbs

        /** Port of {@code operation()} ({@code gie.cpp:627-660}). */
        private void operation(GieCommand command) {
            beginBlock();
            crsToCrsBlock = false;
            operationArgs = command.args();
            operationRaw = command.raw();
            axisSpec = GieEllipsoidResolver.text(operationArgs, "axis");
            comparator = GieEllipsoidResolver.comparatorFor(operationArgs);
            operation = factory.create(operationArgs);
            // Creation failure is NOT reported here: gie defers it to expect() so that
            // `expect failure` can succeed. gie.cpp:657-658 says so in as many words.
            errnoName = creationErrno(operation);
        }

        /** Port of {@code crs_src()}/{@code crs_dst()} ({@code gie.cpp:761-782}). */
        private void maybeCrsToCrs() {
            if (crsSrcArgs.isEmpty() || crsDstArgs.isEmpty()) {
                return;
            }
            beginBlock();
            crsToCrsBlock = true;
            operationArgs = crsSrcArgs + " -> " + crsDstArgs;
            operationRaw = crsSrcRaw + "\n" + crsDstRaw;
            axisSpec = null;
            // A crs_to_crs pipeline has no argument text to read an ellipsoid out of; PROJ builds it
            // from the CRS definitions and it ends up on GRS80 unless the CRSs say otherwise.
            comparator = GieComparator.grs80();
            operation = factory.createCrsToCrs(crsSrcArgs, crsDstArgs);
            crsDstIsLatLonOrYX = operation.crsDstIsLatLonOrYX();
            errnoName = creationErrno(operation);
            // gie.cpp:754-755: each pair is consumed, so every test needs a fresh one.
            crsSrcArgs = "";
            crsDstArgs = "";
            crsSrcRaw = "";
            crsDstRaw = "";
        }

        /** The five reset lines shared by {@code operation()} and {@code crs_to_crs_operation()}. */
        private void beginBlock() {
            blockIndex++;
            blocks++;
            assertionIndex = 0;
            skipTest = false;
            direction = GieDirection.FORWARD;
            tolerance = GieTolerance.tolerance("0.5 mm");
            ignoreErrno = null;
            // NB: `accepted` and `crsDstIsLatLonOrYX` are deliberately untouched. See the class
            // comment; removing this comment and "tidying" them into the reset breaks 2 assertions
            // outright and changes the metric of any file that mixes crs_src with operation.
        }

        /** Port of {@code direction()} ({@code gie.cpp:595-616}): only the first non-space char counts. */
        private void direction(String args) {
            int i = 0;
            while (i < args.length() && isSpace(args.charAt(i))) {
                i++;
            }
            char c = i < args.length() ? args.charAt(i) : '\0';
            switch (c) {
                case 'F':
                case 'f':
                    direction = GieDirection.FORWARD;
                    return;
                case 'I':
                case 'i':
                case 'R':
                case 'r':
                    direction = GieDirection.INVERSE;
                    return;
                default:
                    // The C returns 1, which process_file ignores; the direction is left as it was.
                    return;
            }
        }

        /** Port of {@code require_grid()} ({@code gie.cpp:566-593}). May be repeated. */
        private void requireGrid(String args) {
            String filename = GieTolerance.column(args, 1);
            if (!grids.isAvailable(filename)) {
                skipTest = true;
            }
        }

        /** Port of {@code expect()} ({@code gie.cpp:1009-1191}). */
        private void expect(GieCommand command) {
            String args = command.args();

            // gie.cpp:1018-1025: strncmp(args, "failure", 7), then column(args,2) against "errno".
            boolean expectFailure = args.startsWith("failure");
            String namedErrno = null;
            if (expectFailure && GieTolerance.column(args, 2).startsWith("errno")) {
                namedErrno = firstToken(GieTolerance.column(args, 3));
            }

            // gie.cpp:1027-1028 -- the ignore test runs first, ahead of even the null-P check.
            if (ignoreMatches()) {
                recordSkip(command, "ignored errno " + errnoName);
                return;
            }

            if (operation == null || !operation.isUsable()) {
                if (expectFailure) {
                    // The three-way split. gie stops at "the operation is null, so the failure was
                    // expected, so this is a success"; that is where 1,187 rows of the corpus turn
                    // failure-to-implement into apparent conformance. See ExpectedFailureVerdict.
                    ExpectedFailureVerdict verdict = ExpectedFailureVerdict.ofConstructionFailure(
                            Errno.canonical(namedErrno), creationKind());
                    recordExpectedFailure(
                            command,
                            verdict,
                            verdict.isVacuous()
                                    ? "VACUOUS: the operation could not be created, so no conformance was"
                                            + " demonstrated -- gie would have scored this a pass"
                                            + errnoSuffix(namedErrno) + creationSuffix()
                                    : "the definition was rejected, as expected"
                                            + errnoSuffix(namedErrno) + creationSuffix());
                } else {
                    recordFail(command, "operation could not be created: " + creationMessage());
                }
                return;
            }

            if (expectFailure) {
                // gie.cpp:1052-1072. Reset, transform, and accept HUGE_VAL as the success signal.
                errnoName = null;
                double[] ci = inputCoord();
                double[] co = transform(ci, direction);
                if (co[0] == HUGE_VAL) {
                    // The operation existed and the coordinate was refused: proj4j agreed with PROJ
                    // about the domain, which is the one thing an `expect failure` row can prove.
                    recordExpectedFailure(
                            command,
                            ExpectedFailureVerdict.ofTransformFailure(),
                            "the coordinate was rejected, as expected" + errnoSuffix(namedErrno)
                                    + transformSuffix());
                } else {
                    recordExpectedFailure(
                            command,
                            ExpectedFailureVerdict.ofFailureToFail(),
                            "failed to fail: got " + show(co, 2) + errnoSuffix(namedErrno));
                }
                return;
            }

            GieCoord expected = GieCoordParser.parseCoord(args);
            if (expected.x() == HUGE_VAL) {
                // gie.cpp:1096-1098, expect_message_cannot_parse. Note this also catches a literal
                // leading HUGE_VAL, exactly as the C does.
                recordFail(command, "too few args: cannot parse an expected coordinate from \"" + args + "\"");
                return;
            }

            GieIoUnits outUnits = outputUnits(direction);
            boolean angularOut = outUnits == GieIoUnits.RADIANS;

            double[] ce = angularOut ? toradCoord(expected.toArray(), direction) : expected.toArray();
            double[] ci = inputCoord();
            double[] co = transform(ci, direction);

            if (crsDstIsLatLonOrYX && !crsToCrsBlock) {
                leakedCrsDstFlag++;
            }

            GieComparator.Result result = comparator.compare(
                    outUnits, crsDstIsLatLonOrYX, ce, co, expected.dimensionsGiven(), tolerance);

            if (result.passed()) {
                recordPass(command, "");
                return;
            }
            double[] shown = angularOut ? todegCoord(co, direction) : co;
            if (!result.withinTolerance()) {
                recordFail(command, "deviation " + mm(result.deviation()) + " mm, tolerance "
                        + mm(tolerance) + " mm, metric " + result.metric()
                        + "; got " + show(shown, expected.dimensionsGiven())
                        + transformSuffix());
            } else {
                recordFail(command, "time deviation " + fmt(result.temporalDeviation())
                        + " year, maximum " + fmt(GieComparator.TEMPORAL_THRESHOLD_IN_YEAR)
                        + " year; got " + show(shown, 4));
            }
        }

        /** Port of {@code roundtrip()} ({@code gie.cpp:886-1006}). */
        private void roundtrip(GieCommand command) {
            String args = command.args();

            if (operation == null || !operation.isUsable()) {
                // gie.cpp:896-901: for roundtrip the ignore test lives inside the null-P branch.
                if (ignoreMatches()) {
                    recordSkip(command, "ignored errno " + errnoName);
                } else {
                    recordFail(command, "operation could not be created: " + creationMessage());
                }
                return;
            }

            ProjStrtod.Result n = ProjStrtod.strtod(args, 0);
            int ntrips;
            if (n.end == 0) {
                ntrips = DEFAULT_ROUNDTRIPS;
            } else if (n.value < MIN_ROUNDTRIPS || n.value > MAX_ROUNDTRIPS) {
                recordFail(command, "invalid number of roundtrips: " + fmt(n.value));
                return;
            } else {
                ntrips = (int) n.value;
            }

            double d = GieTolerance.strtodScaled(args.substring(n.end), 1);
            if (d == HUGE_VAL) {
                d = tolerance;
            }

            double[] coo = inputCoord();
            double r = projRoundtrip(ntrips, coo);

            if ((Double.isNaN(r) && Double.isNaN(d)) || r <= d) {
                recordPass(command, "");
            } else {
                recordFail(command, "roundtrip deviation " + mm(r) + " mm, expected " + mm(d)
                        + " mm over " + ntrips + " trips" + transformSuffix());
            }
        }

        /**
         * Port of {@code proj_roundtrip()} ({@code 9.8.1:src/trans.cpp:591-629}).
         *
         * <p>The half-step phasing is the whole point and is copied literally: one step forward, then
         * {@code n-1} full inverse/forward cycles taken <em>out of phase</em>, then one step back. It
         * is not {@code n} pairs of {@code (fwd, inv)}, and the difference shows up as a factor of
         * roughly {@code n} in the residual.
         *
         * <p>The residual metric branches on {@code proj_angular_}<strong>{@code input}</strong> — the
         * comment in the C is "checking for angular *input* since we do a roundtrip, and end where we
         * begin". There is no degrees branch: a {@code DEGREES} input side lands in the Euclidean
         * case, which is correct because both operands are then in degrees.
         */
        private double projRoundtrip(int n, double[] coord) {
            double[] org = coord.clone();

            // In the first half-step, we generate the output value.
            double[] t = transform(org, direction);

            // Now n-1 full steps in the opposite direction: we are out of phase due to the half step
            // already taken.
            for (int i = 0; i < n - 1; i++) {
                t = transform(transform(t, direction.opposite()), direction);
            }

            // Finally, the last half-step.
            t = transform(t, direction.opposite());

            // If we start with any NaN, we expect all NaN as output.
            if (hasNans(org) && allNans(t)) {
                return 0.0;
            }

            if (inputUnits(direction) == GieIoUnits.RADIANS) {
                return comparator.lpzDist(org, t);
            }
            return GieComparator.xyzDist(org, t);
        }

        // ------------------------------------------------------------------------------ plumbing

        /** {@code ci = proj_angular_input(P, dir) ? torad_coord(P, dir, T.a) : T.a}. */
        private double[] inputCoord() {
            double[] a = accepted.toArray();
            return inputUnits(direction) == GieIoUnits.RADIANS ? toradCoord(a, direction) : a;
        }

        private GieIoUnits outputUnits(GieDirection dir) {
            return GieIoUnits.outputUnits(
                    operation.leftUnits(), operation.rightUnits(), operation.isInverted(), dir);
        }

        /**
         * {@code proj_*_input(P, dir)}, which {@code coordinates.cpp:52-93} defines as
         * {@code *_output(P, opposite(dir))}.
         */
        private GieIoUnits inputUnits(GieDirection dir) {
            return outputUnits(dir.opposite());
        }

        /**
         * {@code proj_trans}, with {@code proj_coord_error()} substituted for the bridge's
         * {@code null}. PROJ signals a failed transform by returning all-{@code HUGE_VAL}, and
         * {@code expect}'s success test for {@code expect failure} is literally
         * {@code co.xyz.x == HUGE_VAL}, so the substitution is not a convenience — it is the contract.
         */
        private double[] transform(double[] in, GieDirection dir) {
            double[] out = operation.transform(in, dir);
            if (out == null || out.length != 4) {
                GieFailure failure = operation.lastFailure();
                if (failure != null) {
                    errnoName = Errno.of(failure);
                }
                return new double[] {HUGE_VAL, HUGE_VAL, HUGE_VAL, HUGE_VAL};
            }
            GieFailure failure = operation.lastFailure();
            if (failure != null) {
                errnoName = Errno.of(failure);
            }
            return out;
        }

        /**
         * Port of {@code torad_coord()} ({@code gie.cpp:785-796}).
         *
         * <p>The default axis string is {@code "enut"}, so by default only {@code v[0]} and
         * {@code v[1]} convert. A {@code +axis} override is honoured <strong>only in the inverse
         * direction</strong>, because {@code +axis} describes the projected side, which is an
         * inverse's input. {@code todeg_coord} has the mirror-image asymmetry.
         *
         * <p>Never exercised by the active 9.8.1 corpus: the angular {@code +axis} blocks in
         * {@code 4D-API_cs2cs-style.gie} have their assertions commented out and
         * {@code axisswap.gie}'s are {@code WHATEVER}-unit. Implemented for fidelity.
         */
        private double[] toradCoord(double[] a, GieDirection dir) {
            return convertAxes(a, dir == GieDirection.INVERSE, true);
        }

        /** Port of {@code todeg_coord()} ({@code gie.cpp:798-809}). */
        private double[] todegCoord(double[] a, GieDirection dir) {
            return convertAxes(a, dir == GieDirection.FORWARD, false);
        }

        private double[] convertAxes(double[] a, boolean honourAxisOverride, boolean toRadians) {
            String axis = DEFAULT_AXIS;
            if (axisSpec != null && !axisSpec.isEmpty() && honourAxisOverride) {
                axis = axisSpec;
            }
            double[] out = a.clone();
            int n = Math.min(axis.length(), 4);
            for (int i = 0; i < n; i++) {
                if (ANGULAR_AXIS_LETTERS.indexOf(axis.charAt(i)) >= 0) {
                    out[i] = toRadians ? toRad(out[i]) : Math.toDegrees(out[i]);
                }
            }
            return out;
        }

        // ------------------------------------------------------------------------------ errno

        private boolean ignoreMatches() {
            return ignoreErrno != null && ignoreErrno.equals(errnoName);
        }

        private String creationErrno(GieOperation op) {
            if (op == null) {
                return null;
            }
            return op.isUsable() ? null : Errno.of(op.failure());
        }

        /**
         * The bridge's classification of the construction failure in force, or {@code null} when there
         * is no operation at all — which {@link ExpectedFailureVerdict} treats as vacuous, since an
         * {@code expect failure} evaluated before any {@code operation} demonstrates nothing.
         */
        private GieFailureKind creationKind() {
            if (operation == null) {
                return null;
            }
            GieFailure failure = operation.failure();
            return failure == null ? null : failure.kind();
        }

        private String creationMessage() {
            if (operation == null) {
                return "no operation in force";
            }
            GieFailure failure = operation.failure();
            if (failure == null) {
                return "unknown reason";
            }
            return failure.kind() + ": " + failure.message();
        }

        private String creationSuffix() {
            if (operation == null) {
                return "";
            }
            GieFailure failure = operation.failure();
            return failure == null ? "" : " (" + failure.kind() + ": " + failure.message() + ")";
        }

        private String transformSuffix() {
            if (operation == null) {
                return "";
            }
            GieFailure failure = operation.lastFailure();
            return failure == null ? "" : " (" + failure.kind() + ": " + failure.message() + ")";
        }

        // ------------------------------------------------------------------------------ recording

        private void recordPass(GieCommand command, String detail) {
            record(command, AssertionOutcome.PASS, detail);
            errnoName = null; // another_success() -> proj_errno_reset(T.P)
        }

        private void recordFail(GieCommand command, String detail) {
            record(command, AssertionOutcome.FAIL, detail);
            errnoName = null; // another_failure() -> proj_errno_reset(T.P)
        }

        /** another_skip() does not reset the errno, so neither does this. */
        private void recordSkip(GieCommand command, String detail) {
            record(command, AssertionOutcome.SKIP, detail);
        }

        /**
         * Records an {@code expect failure} row together with the verdict that produced its outcome, so
         * that a report can say how many of the corpus's 1,187 such rows are genuine without having to
         * re-derive it from the detail text.
         *
         * <p>The errno is reset for all three verdicts: upstream reaches {@code another_success()} for
         * two of them and {@code another_failure()} for the third, and both call
         * {@code proj_errno_reset}.
         */
        private void recordExpectedFailure(
                GieCommand command, ExpectedFailureVerdict verdict, String detail) {
            record(command, verdict.outcome(), detail, verdict);
            errnoName = null;
        }

        private void record(GieCommand command, AssertionOutcome outcome, String detail) {
            record(command, outcome, detail, null);
        }

        private void record(
                GieCommand command,
                AssertionOutcome outcome,
                String detail,
                ExpectedFailureVerdict verdict) {
            int block = blockIndex < 0 ? 0 : blockIndex;
            String assertionText = assertionText(command);
            AssertionKey key =
                    AssertionKey.compute(corpusPath, block, assertionIndex, operationRaw, assertionText);
            results.add(new GieAssertionResult(
                    key,
                    outcome,
                    detail,
                    command.verb(),
                    command.args(),
                    operationArgs,
                    command.line(),
                    verdict));
            assertionIndex++;
        }

        /**
         * The text an {@link AssertionKey}'s content hash is taken over: the {@code accept} that is in
         * force followed by the assertion itself, both as they appear <em>in the corpus file</em>.
         *
         * <p>Raw source text rather than lexer output, deliberately — {@link AssertionKey#normalise}
         * exists so that identity tracks upstream edits and nothing else. Keying off the lexer's
         * normalised form would make every future refinement of our own parser rewrite every key in
         * the manifest.
         */
        private String assertionText(GieCommand command) {
            return acceptedRaw.isEmpty() ? command.raw() : acceptedRaw + "\n" + command.raw();
        }
    }

    // ---------------------------------------------------------------------------------- statics

    /**
     * {@code PJ_TORAD(deg)} = {@code deg * M_PI / 180.0}.
     *
     * <p>Not {@link Math#toRadians}, which associates as {@code deg / 180.0 * PI} and differs by up
     * to 1 ULP — about 1.4e-9 m on the ellipsoid, which is exactly the threshold of the corpus's 16
     * {@code nm}-tolerance rows. ({@code PJ_TODEG} <em>is</em> bit-identical to
     * {@link Math#toDegrees}, so that direction uses the JDK.)
     */
    static double toRad(double deg) {
        return deg * Math.PI / 180.0;
    }

    /**
     * The bracketed note a detail line carries when the corpus named an errno. It records that the
     * name was <em>not</em> matched, and whether PROJ itself would have recognised it, so that a
     * triage never has to wonder whether the runner tried and failed or never tried.
     */
    private static String errnoSuffix(String namedErrno) {
        if (namedErrno == null || namedErrno.isEmpty()) {
            return "";
        }
        return Errno.canonical(namedErrno) == null
                ? " [errno " + namedErrno + ": not a PROJ error constant even upstream (9999), so a bare failure]"
                : " [errno " + namedErrno + ": not matched; every named errno degenerates, see GieRunner]";
    }

    private static boolean hasNans(double[] v) {
        return Double.isNaN(v[0]) || Double.isNaN(v[1]) || Double.isNaN(v[2]) || Double.isNaN(v[3]);
    }

    private static boolean allNans(double[] v) {
        return Double.isNaN(v[0]) && Double.isNaN(v[1]) && Double.isNaN(v[2]) && Double.isNaN(v[3]);
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == 0x0b || c == '\f' || c == '\r';
    }

    private static String firstToken(String s) {
        int i = 0;
        while (i < s.length() && isSpace(s.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < s.length() && !isSpace(s.charAt(i))) {
            i++;
        }
        return s.substring(start, i);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.6f", Double.valueOf(v));
    }

    private static String mm(double metres) {
        if (Double.isNaN(metres) || Double.isInfinite(metres)) {
            return String.valueOf(metres);
        }
        return String.format(Locale.ROOT, "%.6f", Double.valueOf(1000 * metres));
    }

    private static String show(double[] v, int dimensions) {
        StringBuilder out = new StringBuilder(48);
        int n = Math.max(2, Math.min(4, dimensions));
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                out.append(' ');
            }
            out.append(String.format(Locale.ROOT, "%.9f", Double.valueOf(v[i])));
        }
        return out.toString();
    }

    /**
     * The {@code err_const} names of {@code gie.cpp:1284-1303}, used for {@code ignore} matching and
     * for saying in a detail line whether an {@code expect failure errno <const>} named something
     * PROJ itself would have recognised.
     *
     * <p>Names only. The numeric {@code PROJ_ERR_*} values are deliberately absent: nothing here may
     * turn into an identity test against a proj4j failure, for the reason set out in
     * {@link GieRunner}'s class comment.
     */
    static final class Errno {

        private static final String[] NAMES = {
            "invalid_op",
            "invalid_op_wrong_syntax",
            "invalid_op_missing_arg",
            "invalid_op_illegal_arg_value",
            "invalid_op_mutually_exclusive_args",
            "invalid_op_file_not_found_or_invalid",
            "coord_transfm",
            "coord_transfm_invalid_coord",
            "coord_transfm_outside_projection_domain",
            "coord_transfm_no_operation",
            "coord_transfm_outside_grid",
            "coord_transfm_grid_at_nodata",
            "coord_transfm_missing_time",
            "other",
            "api_misuse",
            "no_inverse_op",
            "network_error",
        };

        private Errno() {}

        /**
         * Port of {@code errno_from_err_const()}'s name resolution ({@code gie.cpp:1329-1348}):
         * lower-case the input, then <em>prefix</em>-match the table in declared order.
         *
         * @param name a name as written in the corpus
         * @return the canonical name, or {@code null} where the C would have fallen back to 9999 —
         *     an errno that no operation can report, so "matches nothing"
         */
        static String canonical(String name) {
            if (name == null) {
                return null;
            }
            String needle = name.trim().toLowerCase(Locale.ROOT);
            if (needle.isEmpty()) {
                return null;
            }
            for (int i = 0; i < NAMES.length; i++) {
                if (NAMES[i].startsWith(needle)) {
                    return NAMES[i];
                }
            }
            return null;
        }

        /**
         * The nearest {@code err_const} name for one of our failures. Used only by {@code ignore},
         * which has zero uses in the 9.8.1 corpus but is a real verb.
         *
         * @param failure the failure, may be {@code null}
         * @return a canonical name, or {@code null} when there is no failure
         */
        static String of(GieFailure failure) {
            if (failure == null) {
                return null;
            }
            switch (failure.kind()) {
                case NOT_IMPLEMENTED:
                    return "invalid_op";
                case INVALID_DEFINITION:
                    return "invalid_op_illegal_arg_value";
                case COORD_OUT_OF_DOMAIN:
                    return "coord_transfm_outside_projection_domain";
                case INVALID_COORD:
                    return "coord_transfm_invalid_coord";
                case NO_INVERSE:
                    return "no_inverse_op";
                case NUMERICAL:
                    return "coord_transfm";
                case MISSING_GRID:
                    return "invalid_op_file_not_found_or_invalid";
                default:
                    return "other";
            }
        }
    }
}

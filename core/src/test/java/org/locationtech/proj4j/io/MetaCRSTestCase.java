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
 *******************************************************************************/
package org.locationtech.proj4j.io;

import java.io.PrintStream;

import org.locationtech.proj4j.*;
import org.locationtech.proj4j.util.CRSCache;
import org.locationtech.proj4j.util.ProjectionUtil;

/**
 * One row of a MetaCRS-format CSV, and the verdict that row asserts.
 *
 * <h2>The four verdicts</h2>
 *
 * <p>The {@code testMethod} column (column 1) records what the row claims about Proj4J. Three
 * values predate this class and are unchanged in meaning:
 *
 * <table>
 * <caption>the verdict vocabulary</caption>
 * <tr><th>{@code testMethod}</th><th>satisfied when</th><th>note</th></tr>
 * <tr><td>{@code passing}</td><td>a coordinate came back, within the row's tolerance</td>
 *     <td></td></tr>
 * <tr><td>{@code failing}</td><td><b>anything other than</b> the above</td>
 *     <td>a wrong number <em>or</em> a refusal &mdash; it does not distinguish them</td></tr>
 * <tr><td>{@code error}</td><td>identical to {@code failing}</td>
 *     <td>never given a separate meaning by any runner</td></tr>
 * <tr><td>{@code refuses:}<i>CAUSE</i></td>
 *     <td>the transform threw, and {@link Proj4jException#cause()} was exactly <i>CAUSE</i></td>
 *     <td><b>new</b></td></tr>
 * <tr><td>{@code refuses}</td><td>the transform threw, with any cause</td>
 *     <td><b>new</b>; use only where the cause is genuinely not pinned</td></tr>
 * </table>
 *
 * <h2>Why a fourth verdict rather than a twentieth column</h2>
 *
 * <p>{@code failing} conflates <em>"Proj4J correctly refuses this row"</em> with <em>"Proj4J
 * answers a wrong number"</em>, and those are the two outcomes the fail-closed work exists to
 * separate. Reclassifying a row that must now throw from {@code passing} to {@code failing} turns
 * a red build green <b>while asserting nothing at all about the refusal</b>: the same row stays
 * green if a future change reverts to returning a plausible coordinate, because a wrong
 * coordinate is also "not passing".
 *
 * <p>The distinction is added to the existing verdict column rather than as a new column, for
 * three reasons in order of weight:
 *
 * <ol>
 *   <li><b>A new column cannot be added without editing the committed CSVs.</b>
 *       {@link MetaCRSTestFileReader#COL_COUNT} is 19 and every shipped file has exactly 19
 *       columns; a twentieth would require rewriting {@code proj4-epsg.csv}'s header, and that
 *       file is a committed <em>input</em> whose regeneration is a pending decision and which the
 *       golden baseline references row for row. Extending the vocabulary of column 1 changes no
 *       existing byte of any file.</li>
 *   <li><b>It is a strict superset.</b> Any string not recognised as a refusal verdict is routed
 *       through the identical "must not be in tolerance" branch that {@code failing} and
 *       {@code error} always took, so a file containing none of the new values behaves exactly as
 *       before &mdash; including files this repository does not own.</li>
 *   <li><b>The cause travels with the verdict.</b> A separate {@code expectedCause} column could
 *       be filled in on a row whose verdict is {@code passing} and nothing would read it. Here the
 *       assertion and its qualifier are one token and cannot drift apart.</li>
 * </ol>
 *
 * <p>The MetaCRS CSV format is external (see {@code trac.osgeo.org/metacrs}) and its
 * {@code testMethod} column is a free-form string, so a new value stays within the format while a
 * twentieth column forks it.
 *
 * @see #evaluate(CRSFactory)
 * @see #verdictMismatch(Result)
 */
public class MetaCRSTestCase {

    public static String FAILING = "failing";
    public static String PASSING = "passing";
    public static String ERROR = "error";

    /**
     * The bare refusal verdict: the row asserts that the transform throws, without pinning which
     * {@link ErrorCause}.
     *
     * <h4>Prefer the qualified form</h4>
     *
     * <p>A bare {@code refuses} is satisfied by a refusal for the wrong reason, which is only
     * marginally better than {@code failing}. Use {@link #REFUSES_PREFIX} unless the cause is
     * genuinely not pinned.
     */
    public static final String REFUSES = "refuses";

    /**
     * The refusal verdict that pins a cause: {@code "refuses:COORDINATE_OUTSIDE_GRID"}.
     *
     * <h4>Matching</h4>
     *
     * <p>The remainder is an {@link ErrorCause} constant name, matched exactly and
     * case-sensitively. An unrecognised name is a <em>malformed row</em> and raises
     * {@link IllegalArgumentException} &mdash; it is deliberately not "a row that never matches",
     * which would be a silently vacuous assertion.
     */
    public static final String REFUSES_PREFIX = "refuses:";

    private static final CoordinateTransformFactory ctFactory = new CoordinateTransformFactory();

    private boolean verbose = true;

    String testName;
    String testMethod;

    String srcCrsAuth;
    String srcCrs;

    String tgtCrsAuth;
    String tgtCrs;

    double srcOrd1;
    double srcOrd2;
    double srcOrd3;

    double tgtOrd1;
    double tgtOrd2;
    double tgtOrd3;

    double tolOrd1;
    double tolOrd2;
    double tolOrd3;

    String using;
    String dataSource;
    String dataCmnts;
    String maintenanceCmnts;

    CoordinateReferenceSystem srcCS;
    CoordinateReferenceSystem tgtCS;

    ProjCoordinate srcPt = new ProjCoordinate();
    ProjCoordinate resultPt = new ProjCoordinate();

    private boolean isInTol;
    private CRSCache crsCache = null;

    public MetaCRSTestCase(
            String testName,
            String testMethod,
            String srcCrsAuth,
            String srcCrs,
            String tgtCrsAuth,
            String tgtCrs,
            double srcOrd1,
            double srcOrd2,
            double srcOrd3,
            double tgtOrd1,
            double tgtOrd2,
            double tgtOrd3,
            double tolOrd1,
            double tolOrd2,
            double tolOrd3,
            String using,
            String dataSource,
            String dataCmnts,
            String maintenanceCmnts
    ) {
        this.testName = testName;
        this.testMethod = testMethod;
        this.srcCrsAuth = srcCrsAuth;
        this.srcCrs = srcCrs;
        this.tgtCrsAuth = tgtCrsAuth;
        this.tgtCrs = tgtCrs;
        this.srcOrd1 = srcOrd1;
        this.srcOrd2 = srcOrd2;
        this.srcOrd3 = srcOrd3;
        this.tgtOrd1 = tgtOrd1;
        this.tgtOrd2 = tgtOrd2;
        this.tgtOrd3 = tgtOrd3;
        this.tolOrd1 = tolOrd1;
        this.tolOrd2 = tolOrd2;
        this.tolOrd3 = tolOrd3;
        this.using = using;
        this.dataSource = dataSource;
        this.dataCmnts = dataCmnts;
        this.maintenanceCmnts = maintenanceCmnts;
    }

    public String getName() {
        return testName;
    }

    public String getSourceCrsName() {
        return csName(srcCrsAuth, srcCrs);
    }

    public String getTargetCrsName() {
        return csName(tgtCrsAuth, tgtCrs);
    }

    public CoordinateReferenceSystem getSourceCS() {
        return srcCS;
    }

    public CoordinateReferenceSystem getTargetCS() {
        return tgtCS;
    }

    public ProjCoordinate getSourceCoordinate() {
        return new ProjCoordinate(srcOrd1, srcOrd2, srcOrd3);
    }

    public ProjCoordinate getTargetCoordinate() {
        return new ProjCoordinate(tgtOrd1, tgtOrd2, tgtOrd3);
    }

    /**
     * The row's own per-ordinate tolerance, as three ordinates.
     *
     * <p>Exposed so that a caller can perturb the expected coordinate <em>by the row's own
     * tolerance</em> and require the check to notice &mdash; a non-vacuity control that a fixed
     * perturbation cannot express, because this file's tolerances span 1e-6 (degrees) to 1.0 (m).
     *
     * @return {@code (tolOrd1, tolOrd2, tolOrd3)}
     */
    public ProjCoordinate getTolerance() {
        return new ProjCoordinate(tolOrd1, tolOrd2, tolOrd3);
    }

    public ProjCoordinate getResultCoordinate() {
        return new ProjCoordinate(resultPt.x, resultPt.y);
    }

    public void setCache(CRSCache crsCache) {
        this.crsCache = crsCache;
    }

    public String getTestMethod() {
        return this.testMethod;
    }

    public boolean execute(CRSFactory csFactory) {
        boolean isOK = false;
        srcCS = createCS(csFactory, srcCrsAuth, srcCrs);
        tgtCS = createCS(csFactory, tgtCrsAuth, tgtCrs);
        isOK = executeTransform(srcCS, tgtCS);
        return isOK;
    }

    // ------------------------------------------------------------------ the three outcomes

    /**
     * What actually happened when a row was run. Three states, because {@code boolean} has two
     * and the missing one is the whole point of {@link #REFUSES}.
     */
    public enum Outcome {

        /** A coordinate came back and every ordinate was within the row's tolerance. */
        IN_TOLERANCE,

        /**
         * A coordinate came back and was outside the row's tolerance. Proj4J reported success and
         * was wrong &mdash; the outcome that must never be conflated with {@link #REFUSED}.
         */
        OUT_OF_TOLERANCE,

        /**
         * No coordinate came back: a {@link Proj4jException} was thrown, carrying an
         * {@link ErrorCause}. Whether this is <em>correct</em> is a property of the row's verdict,
         * never of the outcome.
         */
        REFUSED
    }

    /**
     * The outcome of running one row, with enough detail to say why a verdict was not met.
     *
     * <h4>Immutability</h4>
     *
     * <p>Deliberately a value: {@link MetaCRSTestCase} reuses {@code resultPt} across runs, so a
     * caller collecting outcomes over 4,280 rows would otherwise be holding 4,280 references to
     * one mutable point.
     */
    public static final class Result {

        private final Outcome outcome;
        private final ErrorCause cause;
        private final Proj4jException refusal;
        private final double x;
        private final double y;

        private Result(Outcome outcome, ErrorCause cause, Proj4jException refusal,
                       double x, double y) {
            this.outcome = outcome;
            this.cause = cause;
            this.refusal = refusal;
            this.x = x;
            this.y = y;
        }

        static Result answered(boolean inTolerance, double x, double y) {
            return new Result(inTolerance ? Outcome.IN_TOLERANCE : Outcome.OUT_OF_TOLERANCE,
                    null, null, x, y);
        }

        static Result refused(Proj4jException ex) {
            return new Result(Outcome.REFUSED, ex.cause(), ex, Double.NaN, Double.NaN);
        }

        /** @return which of the three things happened; never null */
        public Outcome outcome() {
            return outcome;
        }

        /**
         * @return the machine-readable refusal reason, or null when a coordinate came back. Never
         *         null when {@link #outcome()} is {@link Outcome#REFUSED}, because
         *         {@link Proj4jException#cause()} is never null.
         */
        public ErrorCause cause() {
            return cause;
        }

        /** @return the exception that refused the row, or null when a coordinate came back */
        public Proj4jException refusal() {
            return refusal;
        }

        /** @return the transformed x, or {@link Double#NaN} when the row was refused */
        public double x() {
            return x;
        }

        /** @return the transformed y, or {@link Double#NaN} when the row was refused */
        public double y() {
            return y;
        }

        @Override
        public String toString() {
            if (outcome == Outcome.REFUSED) {
                return "REFUSED(" + cause + "): " + refusal.getMessage();
            }
            return outcome + "(" + x + ", " + y + ")";
        }
    }

    /**
     * Runs the row and reports which of the three outcomes occurred, instead of collapsing two of
     * them into {@code false}.
     *
     * <h4>Relationship to {@link #execute(CRSFactory)}</h4>
     *
     * <p>{@code execute} is unchanged and still propagates {@link Proj4jException}; this method
     * wraps it. For a row that answers, {@code evaluate(f).outcome() == IN_TOLERANCE} is exactly
     * {@code execute(f)}.
     *
     * @param csFactory the factory used to resolve both CRSs
     * @return the outcome; never null, never throwing {@link Proj4jException}
     */
    public Result evaluate(CRSFactory csFactory) {
        try {
            boolean inTolerance = execute(csFactory);
            return Result.answered(inTolerance, resultPt.x, resultPt.y);
        } catch (Proj4jException ex) {
            return Result.refused(ex);
        }
    }

    // ------------------------------------------------------------------------- the verdict

    /**
     * Whether a {@code testMethod} value asks for a refusal.
     *
     * @param testMethod the raw column-1 value
     * @return true for {@code refuses} and {@code refuses:}<i>CAUSE</i>, false for everything else
     *         including null
     */
    public static boolean isRefusalVerdict(String testMethod) {
        return REFUSES.equals(testMethod)
                || (testMethod != null && testMethod.startsWith(REFUSES_PREFIX));
    }

    /**
     * The {@link ErrorCause} a refusal verdict pins, if it pins one.
     *
     * <h4>Malformed rows are rejected, not ignored</h4>
     *
     * <p>{@code refuses:NOT_A_CAUSE} throws rather than returning null, because a verdict no
     * outcome can satisfy is an assertion that silently never holds.
     *
     * @param testMethod the raw column-1 value
     * @return the required cause, or null for a bare {@code refuses} or a non-refusal verdict
     * @throws IllegalArgumentException if the text after {@code refuses:} is not an
     *                                  {@link ErrorCause} constant name
     */
    public static ErrorCause requiredCause(String testMethod) {
        if (testMethod == null || !testMethod.startsWith(REFUSES_PREFIX)) {
            return null;
        }
        String name = testMethod.substring(REFUSES_PREFIX.length()).trim();
        try {
            return ErrorCause.valueOf(name);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("verdict '" + testMethod + "' names '" + name
                    + "', which is not an ErrorCause constant. A verdict no outcome can satisfy "
                    + "is a vacuous assertion, so this is a malformed row rather than a row that "
                    + "never matches.", ex);
        }
    }

    /**
     * Checks an outcome against this row's recorded verdict.
     *
     * <h4>Semantics, and why the default branch matters</h4>
     *
     * <p>{@code passing} requires {@link Outcome#IN_TOLERANCE}. A refusal verdict requires
     * {@link Outcome#REFUSED}, and the qualified form additionally requires the exact cause.
     * <b>Every other value &mdash; {@code failing}, {@code error}, an empty column, a typo &mdash;
     * requires only "not {@code IN_TOLERANCE}", which is bit-for-bit the rule that has always
     * applied.</b> That default is what makes this a strict superset rather than a new dialect.
     *
     * @param result the outcome from {@link #evaluate(CRSFactory)}
     * @return null when the verdict is satisfied, otherwise a one-line description of the
     *         disagreement, naming what was asserted and what happened
     */
    public String verdictMismatch(Result result) {
        return verdictMismatch(testMethod, result);
    }

    /**
     * The row-independent form of {@link #verdictMismatch(Result)}, for callers checking a verdict
     * string against an outcome they obtained some other way.
     *
     * @param testMethod the raw column-1 value
     * @param result     the outcome
     * @return null when the verdict is satisfied, otherwise a description of the disagreement
     */
    public static String verdictMismatch(String testMethod, Result result) {
        if (isRefusalVerdict(testMethod)) {
            ErrorCause required = requiredCause(testMethod);
            if (result.outcome() != Outcome.REFUSED) {
                return "'" + testMethod + "' asserts the transform must refuse"
                        + (required == null ? "" : " with " + required)
                        + ", but it returned (" + result.x() + ", " + result.y() + ")"
                        + (result.outcome() == Outcome.IN_TOLERANCE
                           ? " within tolerance" : " outside tolerance");
            }
            if (required != null && required != result.cause()) {
                return "'" + testMethod + "' asserts the refusal cause is " + required
                        + ", but the transform refused with " + result.cause() + ": "
                        + result.refusal().getMessage();
            }
            return null;
        }
        if (PASSING.equals(testMethod)) {
            return result.outcome() == Outcome.IN_TOLERANCE ? null
                    : "'" + PASSING + "' asserts a coordinate within tolerance, but got " + result;
        }
        // failing / error / anything else: the pre-existing rule, unchanged.
        return result.outcome() == Outcome.IN_TOLERANCE
                ? "'" + testMethod + "' asserts this row does not pass, but it now does -- "
                  + "reclassify it"
                : null;
    }

    public static String csName(String auth, String code) {
        return auth + ":" + code;
    }

    public CoordinateReferenceSystem createCS(CRSFactory csFactory, String auth, String code) {
        String name = csName(auth, code);

        if (crsCache != null) {
            return crsCache.createFromName(name);
        }
        CoordinateReferenceSystem cs = csFactory.createFromName(name);
        return cs;
    }

    private boolean executeTransform(
            CoordinateReferenceSystem srcCS,
            CoordinateReferenceSystem tgtCS) {
        srcPt.x = srcOrd1;
        srcPt.y = srcOrd2;
        // Testing: flip axis order to test SS sample file
        //srcPt.x = srcOrd2;
        //srcPt.y = srcOrd1;

        CoordinateTransform trans = ctFactory.createTransform(srcCS, tgtCS);

        trans.transform(srcPt, resultPt);

        double dx = Math.abs(resultPt.x - tgtOrd1);
        double dy = Math.abs(resultPt.y - tgtOrd2);

        isInTol = dx <= tolOrd1 && dy <= tolOrd2;

        return isInTol;
    }

    public void print(PrintStream os) {
        System.out.println(testName);
        System.out.println(ProjectionUtil.toString(srcPt)
                + " -> " + ProjectionUtil.toString(resultPt)
                + " ( expected: " + tgtOrd1 + ", " + tgtOrd2 + " )"
        );


        if (!isInTol) {
            System.out.println("FAIL");
            System.out.println("Src CRS: ("
                    + srcCrsAuth + ":" + srcCrs + ") "
                    + srcCS.getParameterString());
            System.out.println("Tgt CRS: ("
                    + tgtCrsAuth + ":" + tgtCrs + ") "
                    + tgtCS.getParameterString());
        }
    }
}

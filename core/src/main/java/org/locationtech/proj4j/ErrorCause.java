/*******************************************************************************
 * Copyright 2026 Proj4J contributors
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
package org.locationtech.proj4j;

/**
 * The machine-readable reason a Proj4J operation failed.
 *
 * <h2>Stability contract</h2>
 *
 * <p><b>The constant names of this enum are API.</b> Within a major version no constant is
 * renamed, removed, or given a different meaning, and {@link #metricKey()} is stable for the
 * same span — it is intended to be used directly as a metrics dimension, a log field, or a
 * database column value, so it must survive a library upgrade without re-baselining a
 * dashboard.
 *
 * <p><b>New constants may be added in a minor version.</b> A caller must therefore always
 * provide a default branch:
 *
 * <pre>{@code
 * try {
 *     transform.transform(src, dst);
 * } catch (CrsTransformException e) {
 *     switch (e.cause()) {
 *         case COORDINATE_OUT_OF_DOMAIN:  metrics.increment(e.cause().metricKey()); break;
 *         case NUMERICAL_FAILURE:         metrics.increment(e.cause().metricKey()); break;
 *         default:                        // REQUIRED: a future version may add constants
 *                                         metrics.increment(e.cause().metricKey());
 *     }
 * }
 * }</pre>
 *
 * <p>Switching on {@code ErrorCause} without a {@code default} is a forward-compatibility bug.
 * The recommended pattern is to branch only on the constants you act on differently and to
 * route everything else through {@link #metricKey()} and the four group predicates
 * ({@link #isCrsError()}, {@link #isOperationError()}, {@link #isCoordinateError()},
 * {@link #isEnvironmentError()}), which are total: exactly one of the four is {@code true}
 * for every constant, now and in every future version.
 *
 * <h2>Groups</h2>
 *
 * <table>
 * <caption>the four groups and their {@link #metricKey()} prefixes</caption>
 * <tr><th>group</th><th>predicate</th><th>prefix</th><th>meaning</th></tr>
 * <tr><td>CRS definition</td><td>{@link #isCrsError()}</td><td>{@code crs.}</td>
 *     <td>the CRS could not be built from its definition</td></tr>
 * <tr><td>operation selection</td><td>{@link #isOperationError()}</td><td>{@code crs.}</td>
 *     <td>the CRSs are fine but no operation between them is usable</td></tr>
 * <tr><td>per-coordinate</td><td>{@link #isCoordinateError()}</td><td>{@code coord.}</td>
 *     <td>the operation is fine; this particular coordinate cannot be transformed</td></tr>
 * <tr><td>environment</td><td>{@link #isEnvironmentError()}</td><td>{@code env.}</td>
 *     <td>a resource, a policy, or the caller — not the data</td></tr>
 * </table>
 *
 * <p>Both definition-time groups share the {@code crs.} prefix because both are raised while
 * <em>creating</em> a CRS or an operation, never per row; a metrics consumer that only wants
 * "did planning fail" can therefore match on the prefix alone.
 *
 * <h2>Relationship to PROJ's {@code PROJ_ERR_*} codes</h2>
 *
 * <p>Most constants correspond to a {@code PROJ_ERR_*} code from PROJ 9.8.1
 * ({@code src/proj.h}). Six have no PROJ counterpart, because Proj4J has boundaries PROJ does
 * not: {@link #UNKNOWN_CRS} (PROJ returns a null {@code PJ} with a generic invalid-operation
 * code; a caller needs "we do not recognise EPSG:99999" distinct from "malformed"),
 * {@link #PROJECTION_NOT_IMPLEMENTED}, {@link #CRS_TYPE_NOT_SUPPORTED},
 * {@link #UNSUPPORTED_OPERATION_METHOD}, {@link #COORDINATE_OUTSIDE_AREA_OF_USE} (PROJ's
 * {@code proj_trans} does not enforce area of use), {@link #MISSING_GRID} <em>at operation
 * level</em> (PROJ folds it into its per-coordinate codes), and {@link #DATABASE_UNAVAILABLE}
 * (Proj4J can run with no CRS database at all; PROJ cannot run without {@code proj.db}).
 *
 * @see Proj4jException#cause()
 * @see CrsTransformException
 * @since 1.5.0
 */
public enum ErrorCause {

    // ------------------------------------------------------------------ CRS definition

    /**
     * An authority code, or a CRS name, that is syntactically well formed but not known to
     * any configured authority. {@code EPSG:99999}.
     */
    UNKNOWN_CRS("crs.unknown_crs", Group.CRS),

    /**
     * A CRS definition could not be parsed: a malformed PROJ string, WKT, or PROJJSON
     * document. Distinct from {@link #UNKNOWN_CRS}, which is about a name that parses.
     * PROJ's {@code PROJ_ERR_INVALID_OP_WRONG_SYNTAX}.
     */
    INVALID_CRS_SYNTAX("crs.invalid_crs_syntax", Group.CRS),

    /**
     * Two or more parameters in one definition are mutually exclusive, or specify the same
     * quantity inconsistently — {@code +ellps=GRS80 +rf=300}, {@code +rf=298.257 +f=0.00335}.
     * PROJ's {@code PROJ_ERR_INVALID_OP_MUTUALLY_EXCLUSIVE_ARGS}.
     *
     * @see ContradictoryParameterException
     */
    CONTRADICTORY_PARAMS("crs.contradictory_params", Group.CRS),

    /**
     * A parameter the projection or operation requires was not supplied.
     * PROJ's {@code PROJ_ERR_INVALID_OP_MISSING_ARG}.
     */
    MISSING_PARAM("crs.missing_param", Group.CRS),

    /**
     * A parameter was supplied with a value outside its allowable range, or of the wrong
     * kind — a negative squared eccentricity, {@code +lat_1=100}, {@code +zone=0}.
     * PROJ's {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE}.
     *
     * @see InvalidValueException
     */
    INVALID_PARAM_VALUE("crs.invalid_param_value", Group.CRS),

    /**
     * The definition names a projection Proj4J does not implement — a capability boundary
     * that PROJ does not have. Includes names that are registered but not backed by a
     * concrete implementation.
     *
     * @see UnsupportedParameterException
     */
    PROJECTION_NOT_IMPLEMENTED("crs.projection_not_implemented", Group.CRS),

    /**
     * The definition describes a kind of CRS Proj4J cannot represent — a compound CRS, a
     * vertical CRS, an engineering CRS, a dynamic CRS with an epoch.
     */
    CRS_TYPE_NOT_SUPPORTED("crs.crs_type_not_supported", Group.CRS),

    // ------------------------------------------------------------- operation selection

    /**
     * No coordinate operation between the two CRSs exists at all — not one was even a
     * candidate. PROJ's {@code PROJ_ERR_INVALID_OP} and
     * {@code PROJ_ERR_COORD_TRANSFM_NO_OPERATION}.
     */
    NO_OPERATION_AVAILABLE("crs.no_operation_available", Group.OPERATION),

    /**
     * Candidate operations existed but every one was a ballpark (no-parameter, accuracy-free)
     * datum change, and the request rejects ballpark transformations. Raised at planning time,
     * never on row 4,000,000.
     */
    BALLPARK_REJECTED("crs.ballpark_rejected", Group.OPERATION),

    /**
     * Candidate operations existed and at least one was not ballpark, but the best of them
     * could not be instantiated — typically because a grid it declares is absent — and the
     * request requires the best operation rather than a degraded one.
     */
    BEST_OPERATION_UNAVAILABLE("crs.best_operation_unavailable", Group.OPERATION),

    /**
     * A grid or model file the operation needs could not be obtained: not present in any
     * configured grid source, or present but unreadable or not parseable here. PROJ's own name
     * for the code says both halves — {@code FILE_NOT_FOUND_OR_INVALID} — and a caller can act
     * on them identically: the operation cannot be planned until the file is supplied in a form
     * this build can read.
     *
     * <p>Raised at planning time; the per-coordinate counterparts are
     * {@link #COORDINATE_OUTSIDE_GRID} and {@link #GRID_NODATA}. PROJ folds all three into
     * {@code PROJ_ERR_INVALID_OP_FILE_NOT_FOUND_OR_INVALID} / {@code _OUTSIDE_GRID}.
     *
     * <p>Not {@link #INVALID_PARAM_VALUE}: the {@code +grids=} or {@code +file=} value the
     * caller wrote may be entirely correct and simply unresolvable in this environment, which is
     * an operation-planning fact rather than a defect in the CRS definition.
     */
    MISSING_GRID("crs.missing_grid", Group.OPERATION),

    /**
     * The operation exists in the forward direction only and an inverse was requested.
     * PROJ's {@code PROJ_ERR_OTHER_NO_INVERSE_OP}.
     */
    NO_INVERSE_AVAILABLE("crs.no_inverse_available", Group.OPERATION),

    /**
     * Candidate operations existed, but every one of them uses a coordinate-operation method
     * Proj4J cannot execute — a time-dependent Helmert, a Molodensky-Badekas transformation,
     * a deformation model. Distinct from {@link #NO_OPERATION_AVAILABLE} (nothing was a
     * candidate) and from {@link #PROJECTION_NOT_IMPLEMENTED} (a <em>projection</em>, not an
     * operation between datums). No PROJ counterpart.
     */
    UNSUPPORTED_OPERATION_METHOD("crs.unsupported_operation_method", Group.OPERATION),

    // ---------------------------------------------------------------------- per-coordinate

    /**
     * The input coordinate is not a valid input for this operation at all: a non-finite
     * ordinate, or an angular input outside PROJ's input contract (|latitude| beyond
     * 90&deg; by more than {@code PJ_EPS_LAT = 1e-12} radians, or |longitude| beyond 10
     * radians). PROJ's {@code PROJ_ERR_COORD_TRANSFM_INVALID_COORD}.
     */
    INVALID_COORDINATE("coord.invalid_coordinate", Group.COORDINATE),

    /**
     * The coordinate is well formed but lies outside the domain on which this projection is
     * defined — beyond the visible hemisphere of an azimuthal projection, past the pole of a
     * conic. PROJ's {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN}.
     *
     * @see ProjectionException
     */
    COORDINATE_OUT_OF_DOMAIN("coord.coordinate_out_of_domain", Group.COORDINATE),

    /**
     * The coordinate lies outside the declared area of use of the operation. PROJ's
     * {@code proj_trans} does not enforce this; Proj4J does so only when asked, because the
     * result is usually finite and plausible rather than mathematically undefined.
     */
    COORDINATE_OUTSIDE_AREA_OF_USE("coord.coordinate_outside_area_of_use", Group.COORDINATE),

    /**
     * The coordinate falls outside the extent of the grid the operation uses. PROJ's
     * {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_GRID}. In 1.4.3 this was a silent no-op: the
     * coordinate was returned untouched.
     */
    COORDINATE_OUTSIDE_GRID("coord.coordinate_outside_grid", Group.COORDINATE),

    /**
     * The coordinate falls inside the grid but on a no-data node. PROJ's
     * {@code PROJ_ERR_COORD_TRANSFM_GRID_AT_NODATA}.
     */
    GRID_NODATA("coord.grid_nodata", Group.COORDINATE),

    /**
     * The computation failed numerically: an iteration did not converge within its cap, or a
     * result was not finite. PROJ's {@code PROJ_ERR_COORD_TRANSFM} and
     * {@code PROJ_ERR_COORD_TRANSFM_NO_CONVERGENCE}.
     *
     * <p>This is the cause behind the fail-closed rule that a non-convergent iteration must
     * never return its last unconverged value, and must never clamp to a pole: a pole is a
     * specific, plausible coordinate, and a caller's {@code isFinite} check cannot see it.
     *
     * @see ConvergenceFailureException
     */
    NUMERICAL_FAILURE("coord.numerical_failure", Group.COORDINATE),

    /**
     * The operation is time-dependent and the coordinate carries no epoch. PROJ's
     * {@code PROJ_ERR_COORD_TRANSFM_MISSING_TIME}.
     */
    MISSING_TIME("coord.missing_time", Group.COORDINATE),

    // ------------------------------------------------------------------------ environment

    /**
     * The CRS database is absent or unreadable. Proj4J degrades to its built-in definitions
     * rather than failing, so this is raised only for a capability that genuinely requires the
     * database. PROJ cannot run without {@code proj.db} and so has no equivalent.
     */
    DATABASE_UNAVAILABLE("env.database_unavailable", Group.ENVIRONMENT),

    /**
     * A resource would have to be fetched over the network. Proj4J never enables networking
     * implicitly, so this is always the result of a policy, never of a failed request.
     * PROJ's {@code PROJ_ERR_OTHER_NETWORK_ERROR}.
     */
    NETWORK_DISABLED("env.network_disabled", Group.ENVIRONMENT),

    /**
     * The caller used the API incorrectly: a null argument, an operation used after its
     * context was replaced, a bulk call with mismatched array lengths. PROJ's
     * {@code PROJ_ERR_OTHER_API_MISUSE}.
     */
    API_MISUSE("env.api_misuse", Group.ENVIRONMENT),

    /**
     * An internal invariant was violated — a bug in Proj4J. Also the default
     * {@link Proj4jException#cause()} for a legacy exception that was thrown before the
     * taxonomy existed and has not yet been attributed to a more specific cause. PROJ's
     * {@code PROJ_ERR_OTHER}.
     */
    INTERNAL_ERROR("env.internal_error", Group.ENVIRONMENT);

    /**
     * The four groups. Private: the public contract is the four predicates, so that adding a
     * group later cannot break a caller's {@code switch}.
     */
    private enum Group {
        CRS, OPERATION, COORDINATE, ENVIRONMENT
    }

    private final String metricKey;
    private final Group group;

    ErrorCause(String metricKey, Group group) {
        this.metricKey = metricKey;
        this.group = group;
    }

    /**
     * A stable, lower-case, dotted identifier for this cause, suitable for use as a metrics
     * dimension, a log field, or a persisted value: {@code "crs.ballpark_rejected"},
     * {@code "coord.numerical_failure"}.
     *
     * <p>The prefix is the group ({@code crs.}, {@code coord.}, {@code env.}) and the
     * remainder is the constant name lower-cased. Both are part of the stability contract in
     * exactly the same way the constant names are: never changed within a major version.
     * Unlike {@link #name()}, this is safe to embed in a dashboard query.
     *
     * @return the stable metric key; never null, never empty
     */
    public String metricKey() {
        return metricKey;
    }

    /**
     * Whether this cause means a CRS could not be built from its definition — the problem is
     * in the definition, and no coordinate was involved.
     *
     * @return true for the CRS-definition group
     */
    public boolean isCrsError() {
        return group == Group.CRS;
    }

    /**
     * Whether this cause means both CRSs are fine but no usable coordinate operation between
     * them could be selected.
     *
     * @return true for the operation-selection group
     */
    public boolean isOperationError() {
        return group == Group.OPERATION;
    }

    /**
     * Whether this cause is about one particular coordinate rather than about the CRSs or the
     * operation. A caller processing a geometry can skip or record the offending vertex and
     * keep going; the operation itself remains valid.
     *
     * @return true for the per-coordinate group
     */
    public boolean isCoordinateError() {
        return group == Group.COORDINATE;
    }

    /**
     * Whether this cause is about a resource, a policy, or the caller, rather than about the
     * data. Retrying with different data will not help.
     *
     * @return true for the environment group
     */
    public boolean isEnvironmentError() {
        return group == Group.ENVIRONMENT;
    }

    /**
     * Whether this cause means "there is no operation I am willing and able to use", spanning
     * every reason the candidate list can end up empty. Convenience for the common case where
     * a caller wants to fall back to another library rather than distinguish
     * {@link #BALLPARK_REJECTED} from {@link #MISSING_GRID}.
     *
     * <p>Equivalent to {@link #isOperationError()} today; it is a separate method because the
     * operation-selection group may later gain a member that is not a "no usable operation"
     * outcome.
     *
     * @return true if no usable coordinate operation could be selected
     */
    public boolean isNoUsableOperation() {
        return group == Group.OPERATION;
    }
}

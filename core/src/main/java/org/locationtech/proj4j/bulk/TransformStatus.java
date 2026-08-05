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
package org.locationtech.proj4j.bulk;

import org.locationtech.proj4j.ErrorCause;

/**
 * The per-point outcome codes written into the {@code byte[] status} array of
 * {@link org.locationtech.proj4j.BulkCoordinateTransform}.
 *
 * <h2>Why a byte per point and not an exception per point</h2>
 *
 * <p>Constructing an exception fills in a stack trace, which costs roughly 1-10&nbsp;&micro;s. A
 * 100,000-vertex geometry with 10% out-of-domain vertices would spend <b>hundreds of
 * milliseconds purely in {@code fillInStackTrace}</b> — more than the entire transform, and
 * invisible in a profile that only shows the caller's own frames. The status array is how the
 * error taxonomy is delivered at no per-point cost: one byte store per point, no allocation, and
 * the array is owned and reused by the caller.
 *
 * <h2>Why a byte array and not a bitset</h2>
 *
 * <p><b>A bitset was considered and rejected</b>, and the decision is recorded here so it is not
 * relitigated: it saves 7/8 of a byte against 16&nbsp;bytes per point of coordinate data — under
 * 6% — and it throws away the <em>reason</em> code, which is the thing that was actually asked
 * for. "This vertex failed" without "why" turns a diagnosable data problem into a support ticket.
 *
 * <h2>These are {@code byte} constants, not an enum</h2>
 *
 * <p>Deliberate. An {@code enum[]} would be a reference array, so a batch of 100,000 points would
 * either need 100,000 pre-interned references loaded per point (a pointer chase and a cache miss
 * against 8 bytes of payload) or box the ordinal. The whole point of the bulk API is that the
 * per-point path touches nothing but primitives. {@link #cause(byte)} converts to the
 * {@link ErrorCause} taxonomy on the <em>failure</em> path, where a reference is affordable.
 *
 * <h2>Relationship to {@link ErrorCause}</h2>
 *
 * <p>{@link ErrorCause} is the full 24-value taxonomy and is what the single-point path throws.
 * These eight codes are the subset a <em>per-coordinate</em> failure can take, so the mapping is
 * many-to-one in one direction and total in the other:
 *
 * <table>
 * <caption>status code to {@link ErrorCause}</caption>
 * <tr><th>status</th><th>{@link ErrorCause}</th><th>note</th></tr>
 * <tr><td>{@link #OK}</td><td>—</td><td>the point transformed</td></tr>
 * <tr><td>{@link #ERR_INVALID_INPUT}</td><td>{@link ErrorCause#INVALID_COORDINATE}</td>
 *     <td>also {@link ErrorCause#MISSING_TIME}, which has no code of its own</td></tr>
 * <tr><td>{@link #ERR_COORD_OUT_OF_DOMAIN}</td>
 *     <td>{@link ErrorCause#COORDINATE_OUT_OF_DOMAIN}</td><td></td></tr>
 * <tr><td>{@link #ERR_NUMERICAL_FAILURE}</td><td>{@link ErrorCause#NUMERICAL_FAILURE}</td>
 *     <td>non-convergence, or finite input yielding a non-finite result</td></tr>
 * <tr><td>{@link #ERR_OUTSIDE_GRID_EXTENT}</td>
 *     <td>{@link ErrorCause#COORDINATE_OUTSIDE_GRID}</td>
 *     <td>also {@link ErrorCause#GRID_NODATA}, which has no code of its own</td></tr>
 * <tr><td>{@link #ERR_OUTSIDE_AREA_OF_USE}</td>
 *     <td>{@link ErrorCause#COORDINATE_OUTSIDE_AREA_OF_USE}</td><td></td></tr>
 * <tr><td>{@link #ERR_MISSING_GRID}</td><td>{@link ErrorCause#MISSING_GRID}</td>
 *     <td>reserved: today a missing grid is an <em>operation</em>-level failure and therefore
 *         throws rather than being recorded per point</td></tr>
 * <tr><td>{@link #ERR_NO_OPERATION}</td><td>{@link ErrorCause#NO_OPERATION_AVAILABLE}</td>
 *     <td>reserved for per-coordinate operation selection, which the legacy engine does not
 *         do</td></tr>
 * </table>
 *
 * <p><b>Only per-coordinate causes are ever recorded as a status.</b> A CRS that cannot be built,
 * an operation with no inverse, and an environment failure are properties of the
 * <em>operation</em>, not of the coordinate, so they abandon the batch and throw — recording one
 * of those once per row would report a planning-time defect four million times. The predicate is
 * {@link ErrorCause#isCoordinateError()}, and {@link #forCause(ErrorCause)} returns
 * {@link #NOT_A_COORDINATE_ERROR} for everything else.
 *
 * @see org.locationtech.proj4j.BulkCoordinateTransform
 * @since 1.5.0
 */
public final class TransformStatus {

    /** The point transformed; its output ordinates are finite (or {@code NaN} because the input was). */
    public static final byte OK = 0;

    /**
     * The coordinate is well formed but lies outside the domain on which the projection is
     * defined — beyond the visible hemisphere of an azimuthal projection, past the pole of a
     * conic. PROJ's {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN} (2050).
     */
    public static final byte ERR_COORD_OUT_OF_DOMAIN = 1;

    /**
     * The arithmetic failed: an iteration did not converge within its cap, or finite input
     * produced a non-finite result. PROJ's {@code PROJ_ERR_COORD_TRANSFM} (2048) and
     * {@code PROJ_ERR_COORD_TRANSFM_NO_CONVERGENCE} (2054).
     */
    public static final byte ERR_NUMERICAL_FAILURE = 2;

    /**
     * A grid the operation needs is absent from every configured source.
     * <p>
     * Reserved. In the legacy engine a missing grid is discovered while <em>planning</em>, not
     * per point, so it throws {@link ErrorCause#MISSING_GRID} and abandons the batch.
     */
    public static final byte ERR_MISSING_GRID = 3;

    /**
     * The coordinate falls outside the extent of every grid the operation carries, or on a
     * no-data node. PROJ's {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_GRID} (2052) and
     * {@code PROJ_ERR_COORD_TRANSFM_GRID_AT_NODATA} (2053).
     */
    public static final byte ERR_OUTSIDE_GRID_EXTENT = 4;

    /**
     * The coordinate lies outside the declared area of use of the operation. Enforced only when
     * asked, because the result is usually finite and plausible rather than undefined.
     */
    public static final byte ERR_OUTSIDE_AREA_OF_USE = 5;

    /**
     * No usable operation applies to this particular coordinate.
     * <p>
     * Reserved for per-coordinate operation selection, which the legacy engine does not perform:
     * it selects once, at construction, so this is an operation-level failure today.
     */
    public static final byte ERR_NO_OPERATION = 6;

    /**
     * The <em>input</em> was not a valid input for this operation at all: an infinite ordinate,
     * or an angular input outside PROJ's input contract (|latitude| past the pole by more than
     * {@code PJ_EPS_LAT = 1e-12} radians, or |longitude| beyond 10 radians). PROJ's
     * {@code PROJ_ERR_COORD_TRANSFM_INVALID_COORD} (2049).
     *
     * <p>Deliberately distinct from {@link #ERR_NUMERICAL_FAILURE}: one says the caller asked an
     * unanswerable question, the other says Proj4J failed to answer an answerable one.
     *
     * <p><b>A {@code NaN} input is not this.</b> {@code NaN} in, {@code NaN} out, status
     * {@link #OK} — the caller supplied the undefinedness and gets it back, which is what PROJ
     * does and what the {@code gie} corpus asserts. Only a <em>finite</em> input outside the
     * contract, or an infinity, is an error.
     */
    public static final byte ERR_INVALID_INPUT = 7;

    /**
     * Returned by {@link #forCause(ErrorCause)} for a cause that is not a per-coordinate
     * failure and therefore must not be squeezed into a status byte. Negative so that it can
     * never be confused with a real code, all of which are non-negative.
     */
    public static final byte NOT_A_COORDINATE_ERROR = -1;

    private TransformStatus() {
    }

    /**
     * Whether a status byte means the point transformed.
     *
     * @param status a status byte
     * @return true if {@code status} is {@link #OK}
     */
    public static boolean isOk(byte status) {
        return status == OK;
    }

    /**
     * The status byte for a per-coordinate {@link ErrorCause}.
     *
     * @param cause the cause; may be null
     * @return the status byte, or {@link #NOT_A_COORDINATE_ERROR} if {@code cause} is null or is
     *         not a per-coordinate failure
     */
    public static byte forCause(ErrorCause cause) {
        if (cause == null) {
            return NOT_A_COORDINATE_ERROR;
        }
        switch (cause) {
            case INVALID_COORDINATE:
                return ERR_INVALID_INPUT;
            case COORDINATE_OUT_OF_DOMAIN:
                return ERR_COORD_OUT_OF_DOMAIN;
            case NUMERICAL_FAILURE:
                return ERR_NUMERICAL_FAILURE;
            case COORDINATE_OUTSIDE_GRID:
            case GRID_NODATA:
                return ERR_OUTSIDE_GRID_EXTENT;
            case COORDINATE_OUTSIDE_AREA_OF_USE:
                return ERR_OUTSIDE_AREA_OF_USE;
            case MISSING_TIME:
                // No code of its own; a coordinate with no epoch is an unusable input.
                return ERR_INVALID_INPUT;
            default:
                return NOT_A_COORDINATE_ERROR;
        }
    }

    /**
     * The {@link ErrorCause} a status byte stands for, so a caller that reads the status array
     * can route through the same taxonomy, {@code metricKey()} and group predicates as a caller
     * that catches.
     *
     * @param status a status byte
     * @return the cause, or null for {@link #OK}
     * @throws IllegalArgumentException if {@code status} is not one of the defined codes
     */
    public static ErrorCause cause(byte status) {
        switch (status) {
            case OK:
                return null;
            case ERR_COORD_OUT_OF_DOMAIN:
                return ErrorCause.COORDINATE_OUT_OF_DOMAIN;
            case ERR_NUMERICAL_FAILURE:
                return ErrorCause.NUMERICAL_FAILURE;
            case ERR_MISSING_GRID:
                return ErrorCause.MISSING_GRID;
            case ERR_OUTSIDE_GRID_EXTENT:
                return ErrorCause.COORDINATE_OUTSIDE_GRID;
            case ERR_OUTSIDE_AREA_OF_USE:
                return ErrorCause.COORDINATE_OUTSIDE_AREA_OF_USE;
            case ERR_NO_OPERATION:
                return ErrorCause.NO_OPERATION_AVAILABLE;
            case ERR_INVALID_INPUT:
                return ErrorCause.INVALID_COORDINATE;
            default:
                throw new IllegalArgumentException("not a TransformStatus code: " + status);
        }
    }

    /**
     * The constant name of a status byte, for log lines and assertion messages.
     *
     * @param status a status byte
     * @return the name, or {@code "UNKNOWN(<n>)"} for an undefined code
     */
    public static String name(byte status) {
        switch (status) {
            case OK:                        return "OK";
            case ERR_COORD_OUT_OF_DOMAIN:   return "ERR_COORD_OUT_OF_DOMAIN";
            case ERR_NUMERICAL_FAILURE:     return "ERR_NUMERICAL_FAILURE";
            case ERR_MISSING_GRID:          return "ERR_MISSING_GRID";
            case ERR_OUTSIDE_GRID_EXTENT:   return "ERR_OUTSIDE_GRID_EXTENT";
            case ERR_OUTSIDE_AREA_OF_USE:   return "ERR_OUTSIDE_AREA_OF_USE";
            case ERR_NO_OPERATION:          return "ERR_NO_OPERATION";
            case ERR_INVALID_INPUT:         return "ERR_INVALID_INPUT";
            case NOT_A_COORDINATE_ERROR:    return "NOT_A_COORDINATE_ERROR";
            default:                        return "UNKNOWN(" + status + ")";
        }
    }
}

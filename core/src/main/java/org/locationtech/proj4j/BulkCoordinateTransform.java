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

import org.locationtech.proj4j.bulk.TransformStatus;

/**
 * Transforms many coordinates in one call, over {@code double[]} buffers the caller owns, with
 * <b>no allocation per point</b>.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The access pattern this library is actually used in is <em>per vertex</em>: up to 100,000
 * {@link CoordinateTransform#transform(ProjCoordinate, ProjCoordinate)} calls for one geometry,
 * across millions of rows, inside Spark executors, through one cached transform shared by every
 * executor thread. At that shape the cost is not the mathematics — it is the <b>per-call
 * re-derivation of facts that are constant for the transform's whole lifetime</b>, plus the
 * caller's coordinate object.
 *
 * <p>Measured and counted, one single-point call re-does all of this per vertex:
 *
 * <table>
 * <caption>work that is invariant for the transform but repeated per point</caption>
 * <tr><th>invariant</th><th>per-point cost</th></tr>
 * <tr><td>{@code Datum.getTransformType()}, <b>six times</b></td>
 *     <td>each: two {@code Ellipsoid.isEqual} plus an {@code isIdentity} array scan</td></tr>
 * <tr><td>{@code Datum.isEqual} once more</td>
 *     <td>two further {@code getTransformType()} and a full array compare — and on the gridshift
 *         branch it descends to {@code Arrays.equals(cvs)} over <b>millions of grid nodes</b>,
 *         i.e. O(grid size) <em>per point</em></td></tr>
 * <tr><td>{@code unit.equals(Units.DEGREES)}, twice</td>
 *     <td>a virtual call and a {@code String.equals} each</td></tr>
 * <tr><td>~10 getter chains off non-final fields</td>
 *     <td>reloads the JIT cannot hoist</td></tr>
 * <tr><td>six {@code AxisOrder} enum dispatches</td>
 *     <td>abstract calls implementing, for the default axis order, an identity copy</td></tr>
 * <tr><td>the caller's {@link ProjCoordinate}</td><td>40 bytes of garbage per vertex</td></tr>
 * </table>
 *
 * <p>A batch amortises every row of that table to <b>once per geometry</b>. That is the entire
 * design: this is not a loop around the single-point method, it is a different shape in which the
 * per-point work is arithmetic and array access.
 *
 * <h2>The contract, normative</h2>
 *
 * <p>Every method takes a {@code byte[] status} that may be null, and the two cases are
 * genuinely different modes rather than a convenience:
 *
 * <ul>
 * <li><b>{@code status} non-null</b> — one byte per point, from
 *     {@link TransformStatus}. <b>Zero allocation.</b> The caller owns the array and reuses it
 *     across batches; it is never reallocated, never grown, and never replaced. This is how the
 *     error taxonomy is delivered at no per-point cost. The array must have length at least
 *     {@code numPts}; any excess is left untouched.</li>
 * <li><b>{@code status} null</b> — <b>fail-fast</b>. The batch is abandoned at the first failing
 *     point and an exception is thrown. The exception is constructed <b>once, after the loop is
 *     abandoned</b> — never per point. (Under {@link DomainErrorPolicy#RETURN_NAN} nothing is
 *     thrown; see below.)</li>
 * </ul>
 *
 * <p><b>Sentinel policy, deliberately redundant.</b> A failed point gets {@code NaN} written to
 * <em>every</em> output ordinate <em>and</em> a status code. The redundancy is the point: a caller
 * who ignores the status array is <b>no worse off than today</b>, because {@code NaN} is what a
 * failing vertex already looks like to a downstream {@code isFinite} guard; a caller who reads the
 * array additionally gets the reason. Ordinates the transform does not own — an M value or a flag
 * living in the same interleaved buffer at a stride greater than the coordinate width — are
 * <b>never</b> touched, on success or on failure.
 *
 * <p><b>A non-finite input is {@link TransformStatus#ERR_INVALID_INPUT}</b>, distinct from a
 * computation failure, with one exception that is not negotiable because the conformance corpus
 * asserts it: <b>{@code NaN} in propagates as {@code NaN} out with status
 * {@link TransformStatus#OK}</b>. The caller supplied the undefinedness and gets it back. Only an
 * infinity, or a finite input outside PROJ's angular contract, is an invalid input; only a
 * <em>finite</em> input that yields a non-finite output is a
 * {@link TransformStatus#ERR_NUMERICAL_FAILURE}. This is exactly the classification the
 * single-point path makes, and it is required to be identical — see below.
 *
 * <p><b>The return value is the failure count</b>, not a boolean and not the number of successes.
 * That makes fail-closed <em>one branch per geometry</em>:
 *
 * <pre>{@code
 * byte[] status = scratch.status(n);            // caller-owned, reused
 * if (op.transform2D(xy, 0, n, 2, status) != 0) {
 *     return emptyGeometry();                   // or inspect status[i] per vertex
 * }
 * }</pre>
 *
 * <p>instead of two {@code isFinite} calls per vertex.
 *
 * <h2>What is <em>not</em> recorded in the status array</h2>
 *
 * <p>Only per-coordinate causes — those for which {@link ErrorCause#isCoordinateError()} is true.
 * A CRS that cannot be built, an operation that has no inverse, an absent grid discovered at
 * planning time, and an API misuse are all properties of the <em>operation</em>, so they throw
 * {@link CrsTransformException} and abandon the batch whether or not a status array was supplied.
 * Recording one of those once per row would report a planning-time defect four million times.
 *
 * <h2>Interaction with {@link DomainErrorPolicy}</h2>
 *
 * <table>
 * <caption>policy by status array</caption>
 * <tr><th></th><th>{@code status} non-null</th><th>{@code status} null</th></tr>
 * <tr><td>{@link DomainErrorPolicy#THROW}</td>
 *     <td>status byte + {@code NaN} ordinates; the batch completes</td>
 *     <td>throws at the first failure</td></tr>
 * <tr><td>{@link DomainErrorPolicy#RETURN_NAN}</td>
 *     <td>status byte + {@code NaN} ordinates; the batch completes</td>
 *     <td>{@code NaN} ordinates; the batch completes, and the count is the only signal</td></tr>
 * </table>
 *
 * <p>Non-coordinate causes throw under both policies, as they do for the single-point path.
 *
 * <h2>Equivalence to the single-point path is bitwise, and is tested</h2>
 *
 * <p>For every point, the result of a bulk call is <b>bit-for-bit identical</b> to the result of
 * the corresponding {@link CoordinateTransform#transform(ProjCoordinate, ProjCoordinate)} call,
 * compared with {@link Double#doubleToRawLongBits(double)} rather than with a tolerance. That
 * matters for two reasons that are real rather than pedantic: {@code NaN} payloads survive, and
 * {@code +0.0} is distinguished from {@code -0.0} — a distinction that occurs at the equator and
 * at the antimeridian, on data every consumer has. A bulk path equal to the single-point path only
 * <em>to within a tolerance</em> has changed the semantics of the library and is a defect, not an
 * optimisation.
 *
 * <p>The same guarantee holds for the failure classification: a point that throws
 * {@code COORDINATE_OUT_OF_DOMAIN} through the single-point path gets
 * {@link TransformStatus#ERR_COORD_OUT_OF_DOMAIN} through the bulk path, never a different code
 * and never a success.
 *
 * <h2>Thread safety</h2>
 *
 * <p>An implementation must be safe to share across threads for the same reasons the single-point
 * path is, and must not hold per-call state in a field. Callers must not share the coordinate
 * buffers or the status array between threads. (Note that a handful of projection kernels are
 * <em>not</em> yet safe to share — {@code CassiniProjection} writes 17 instance fields inside
 * {@code project()} — which is a defect of those kernels and applies equally to both paths.)
 *
 * <h2>Argument checking</h2>
 *
 * <p>Checked <b>once per batch, before the loop</b>, never per point: null buffers, a negative
 * {@code offset} or {@code numPts}, a {@code stride} narrower than the coordinate width, a status
 * array shorter than {@code numPts}, and a buffer too short for the requested range all raise
 * {@link CrsTransformException} with {@link ErrorCause#API_MISUSE}. A batch either has a valid
 * shape or does no work at all; there is no partially-validated batch.
 *
 * @see TransformStatus
 * @see CoordinateTransformFactory#createBulkTransform(CoordinateReferenceSystem,
 *      CoordinateReferenceSystem)
 * @since 1.5.0
 */
public interface BulkCoordinateTransform {

    /**
     * Transforms interleaved coordinates in place.
     *
     * <p>Point {@code i} occupies {@code xy[offset + i * stride]} and
     * {@code xy[offset + i * stride + 1]}. Any further ordinates within the stride are the
     * caller's and are not read or written — pass {@code stride = 4} for a buffer that also
     * carries M and a flag and they will survive untouched.
     *
     * <p>There is no height. Each point is transformed as though its {@code z} were
     * {@code Double.NaN}, exactly as {@link ProjCoordinate#setValue(double, double)} does, so this
     * is bitwise equivalent to the single-point path fed from a two-argument {@code setValue}.
     * <b>For a target that consumes a height — a 7-parameter Helmert, a geocentric target — that
     * absent height is still consumed</b>, and the result differs from
     * {@link #transform3D(double[], int, int, int, byte[])} with a real height. That is the
     * pre-existing behaviour of the single-point path, reproduced rather than corrected.
     *
     * @param xy      the interleaved buffer, read and written
     * @param offset  index of the first point's x ordinate
     * @param numPts  number of points to transform; 0 is legal and does nothing
     * @param stride  ordinates per point; must be &ge; 2
     * @param status  one byte per point, or null for fail-fast; see the class javadoc
     * @return the number of points that failed
     * @throws CrsTransformException with {@link ErrorCause#API_MISUSE} if the arguments do not
     *         describe a valid range, or with a non-per-coordinate cause if the operation itself
     *         cannot run, or — when {@code status} is null — with the per-coordinate cause of the
     *         first failing point
     */
    int transform2D(double[] xy, int offset, int numPts, int stride, byte[] status);

    /**
     * Transforms interleaved coordinates in place, carrying a height.
     *
     * <p>Point {@code i} occupies {@code xyz[offset + i * stride]} through
     * {@code offset + i * stride + 2}. A {@code NaN} z means <b>"no height supplied"</b>, exactly
     * as {@link ProjCoordinate#z} does today, and is written back as {@code NaN}; a finite z is
     * carried through the datum stage and written back, which for a Helmert or geocentric step
     * means it changes.
     *
     * @param xyz     the interleaved buffer, read and written
     * @param offset  index of the first point's x ordinate
     * @param numPts  number of points to transform; 0 is legal and does nothing
     * @param stride  ordinates per point; must be &ge; 3
     * @param status  one byte per point, or null for fail-fast; see the class javadoc
     * @return the number of points that failed
     * @throws CrsTransformException as {@link #transform2D(double[], int, int, int, byte[])}
     */
    int transform3D(double[] xyz, int offset, int numPts, int stride, byte[] status);

    /**
     * Transforms interleaved coordinates from one buffer into another, with independent offsets
     * and strides.
     *
     * <p>Two ordinates per point, as {@link #transform2D(double[], int, int, int, byte[])}:
     * source point {@code i} is read from {@code src[srcOff + i * srcStride]} and the next
     * ordinate, and written to {@code dst[dstOff + i * dstStride]} and the next. Ordinates of
     * {@code dst} outside those two per point are not written.
     *
     * <p><b>Aliasing is supported.</b> {@code src} and {@code dst} may be the same array. Each
     * point's ordinates are read into locals before anything is written, and when the two ranges
     * overlap the iteration direction is chosen so that no point is read after its source has
     * been overwritten. The one shape that is rejected rather than silently mis-transformed is an
     * overlap within a single array at <em>different</em> strides, where no single direction is
     * safe; that raises {@link ErrorCause#API_MISUSE}.
     *
     * @param src       the source buffer, read only (unless it is also {@code dst})
     * @param srcOff    index of the first source point's x ordinate
     * @param srcStride ordinates per source point; must be &ge; 2
     * @param dst       the destination buffer
     * @param dstOff    index of the first destination point's x ordinate
     * @param dstStride ordinates per destination point; must be &ge; 2
     * @param numPts    number of points to transform; 0 is legal and does nothing
     * @param status    one byte per point, or null for fail-fast; see the class javadoc
     * @return the number of points that failed
     * @throws CrsTransformException as {@link #transform2D(double[], int, int, int, byte[])}
     */
    int transform2D(double[] src, int srcOff, int srcStride,
                    double[] dst, int dstOff, int dstStride, int numPts, byte[] status);

    /**
     * Transforms struct-of-arrays coordinates in place: one array per ordinate.
     *
     * <p>The vectorisation-friendly shape, and the one with the least address arithmetic per
     * point. Point {@code i} is {@code (x[offset + i], y[offset + i], z[offset + i])}.
     *
     * <p><b>{@code z} may be null</b>, in which case every point is transformed as though its
     * height were {@code Double.NaN} — identical to
     * {@link #transform2D(double[], int, int, int, byte[])} — and no height is written anywhere.
     *
     * @param x      the x (or longitude, or easting) ordinates, read and written
     * @param y      the y (or latitude, or northing) ordinates, read and written
     * @param z      the heights, read and written, or null for "no height"
     * @param offset index of the first point in all three arrays
     * @param numPts number of points to transform; 0 is legal and does nothing
     * @param status one byte per point, or null for fail-fast; see the class javadoc
     * @return the number of points that failed
     * @throws CrsTransformException as {@link #transform2D(double[], int, int, int, byte[])}
     */
    int transform(double[] x, double[] y, double[] z, int offset, int numPts, byte[] status);
}

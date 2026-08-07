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
package org.locationtech.proj4j;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.locationtech.proj4j.bulk.TransformStatus;
import org.locationtech.proj4j.datum.*;
import org.locationtech.proj4j.proj.Projection;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Represents the operation of transforming
 * a {@link ProjCoordinate} from one {@link CoordinateReferenceSystem}
 * into a different one, using reprojection and datum conversion
 * as required.
 * <p>
 * Computing the transform involves the following steps:
 * <ul>
 * <li>If the source coordinate is in a projected coordinate system,
 * it is inverse-projected into a geographic coordinate system
 * based on the source datum
 * <li>If the source and target {@link Datum}s are different,
 * the source geographic coordinate is converted
 * from the source to the target datum
 * as accurately as possible
 * <li>If the target coordinate system is a projected coordinate system,
 * the converted geographic coordinate is projected into a projected coordinate.
 * </ul>
 * Symbolically this can be presented as:
 * <pre>
 * [ SrcProjCRS {InverseProjection} ] SrcGeoCRS [ {Datum Conversion} ] TgtGeoCRS [ {Projection} TgtProjCRS ]
 * </pre>
 * <p>
 * Information about the transformation procedure is pre-computed
 * and cached in this object for efficient computation.
 *
 * <h2>The bulk path</h2>
 *
 * <p>This class also implements {@link BulkCoordinateTransform}, which transforms many points per
 * call over {@code double[]} buffers with <b>no allocation per point</b>. The bulk methods are not
 * a loop around {@link #transform(ProjCoordinate, ProjCoordinate)}: everything that is constant
 * for the transform's lifetime is resolved in the constructor and read from a {@code final} field,
 * so the per-point work is arithmetic, two projection calls and the array access. Results are
 * <b>bit-for-bit identical</b> to N single-point calls, and the per-point failure classification is
 * identical because the same code raises it. See {@link BulkCoordinateTransform} for the contract
 * and {@code org.locationtech.proj4j.bulk.TransformStatus} for the per-point codes.
 *
 * @author Martin Davis
 * @see CoordinateTransformFactory
 * @see BulkCoordinateTransform
 */
public class BasicCoordinateTransform implements CoordinateTransform, BulkCoordinateTransform {

    private static final long serialVersionUID = 2134853818131680020L;

    /** The CRS input coordinates are referenced to; returned by {@link #getSourceCRS()}. */
    private final CoordinateReferenceSystem srcCRS;
    /** The CRS output coordinates are produced in; returned by {@link #getTargetCRS()}. */
    private final CoordinateReferenceSystem tgtCRS;

    // precomputed information

    /** Whether the source CRS is projected, so its projection must be inverted to reach lon/lat. */
    private final boolean doInverseProjection;
    /** Whether the target CRS is projected, so a forward projection is applied at the end. */
    private final boolean doForwardProjection;
    /** Whether the two CRSs sit on different datums, so a datum shift is needed between them. */
    private final boolean doDatumTransform;
    /** Whether that datum shift goes via geocentric XYZ rather than a grid or a no-op. */
    private final boolean transformViaGeocentric;

    /**
     * The geocentric converters.
     *
     * <h4>{@code final} on purpose, and it was not before</h4>
     *
     * <p>They are written exactly once, in the constructor, but the constructor used to assign
     * them and then possibly null them again, which forced them to be non-final — and a non-final
     * field on a path called once per vertex is a field the JIT must <em>reload</em> after every
     * call it cannot prove side-effect-free, which on this path is all of them. Deciding the
     * values in locals and assigning once removes ~2 reloads per point at no behavioural cost.
     */
    private final GeocentricConverter srcGeoConv;
    /** The target-side geocentric converter; see {@link #srcGeoConv}. */
    private final GeocentricConverter tgtGeoConv;

    /** What to do with a per-coordinate failure; never null. */
    private final DomainErrorPolicy domainErrorPolicy;

    /**
     * Whether the source projection can actually be inverted. Precomputed because it is a
     * property of the CRS pair, not of the coordinate, and {@link #transform} is called once per
     * row.
     */
    private final boolean srcInverseAvailable;

    // ------------------------------------------------------------------------------------------
    // Hoisted invariants: everything reference/performance.md's cost table lists as recomputed
    // per point although constant for the transform's lifetime. Read by the bulk path only, so
    // the single-point path stays byte-for-byte the 1.4.3 body plus its fail-closed checks.
    //
    // Hoisting is arithmetically neutral: each field below is the value of a *pure predicate or
    // accessor* the per-point path would otherwise re-evaluate, so bulk output is bitwise equal to
    // the single-point output. What it is NOT neutral to is mutation of the CRSs after this
    // constructor runs -- notably parser/Proj4Parser's mutation of the global Datum singletons,
    // and Datum.setGrids. Those are defects being fixed elsewhere; a transform is not documented
    // as tracking edits to the CRSs it was built from, and the single-point path is unchanged for
    // any caller who depends on that.
    // ------------------------------------------------------------------------------------------

    /** {@code srcCRS.getProjection()}. Null only for the unusable {@code CS_GEO} sentinel. */
    private final Projection srcProj;
    /** {@code tgtCRS.getProjection()}. Null only for the unusable {@code CS_GEO} sentinel. */
    private final Projection tgtProj;

    /** {@code srcCRS}'s axis order, hoisted so the bulk path does not re-read it per point. */
    private final AxisOrder srcAxes;
    /** {@code tgtCRS}'s axis order, hoisted so the bulk path does not re-read it per point. */
    private final AxisOrder tgtAxes;

    /**
     * Whether the axis order is the default east/north/up, in which case {@code toENU} and
     * {@code fromENU} are an identity copy of all three ordinates and can be skipped.
     * <p>
     * Skipping is bitwise-safe precisely because the default is a <em>copy</em>: {@code Easting},
     * {@code Northing} and {@code Up} return the ordinate unchanged, with no arithmetic, so
     * {@code -0.0} and every {@code NaN} payload survive. It would not be safe for a reversed axis,
     * where {@code fromENU} negates — and negation is exactly what turns {@code +0.0} into
     * {@code -0.0}. Hence the check, rather than an unconditional skip.
     */
    private final boolean srcAxesEnu;
    /** The target-side counterpart; see {@link #srcAxesEnu}. */
    private final boolean tgtAxesEnu;

    /**
     * The prime-meridian offsets, in radians, inlined out of
     * {@link PrimeMeridian#toGreenwich(ProjCoordinate)} and
     * {@link PrimeMeridian#fromGreenwich(ProjCoordinate)}.
     * <p>
     * <b>The add is never elided when the offset is zero</b>, and that is not an oversight:
     * {@code -0.0 + 0.0} is {@code +0.0}, so skipping the addition would change the sign of zero
     * for a longitude of exactly {@code -0.0} — a value that occurs at the prime meridian on real
     * data, and one that {@link Double#doubleToRawLongBits(double)} distinguishes. The single-point
     * path performs the addition unconditionally, so the bulk path must too.
     */
    private final double srcPmOffset;
    /** The target-side offset, subtracted rather than added; see {@link #srcPmOffset}. */
    private final double tgtPmOffset;

    /** {@code srcCRS.getDatum()}, hoisted out of the per-point loop. */
    private final Datum srcDatum;
    /** {@code tgtCRS.getDatum()}, hoisted out of the per-point loop. */
    private final Datum tgtDatum;

    /**
     * Whether {@code datumTransform} would return from one of its early exits for every point.
     * <p>
     * Collapses the two {@code getTransformType()} calls and the {@code Datum.isEqual} at the top
     * of {@link #datumTransform(ProjCoordinate)} into one boolean. {@code Datum.isEqual} is the
     * expensive one and is about to become far more expensive: on the gridshift branch it descends
     * to {@code Arrays.equals} over the grid's node array — O(grid size) <b>per point</b>, tens of
     * millions of comparisons for a CONUS-sized grid. That is masked today only because
     * {@code setGrids(null)} leaves the grid list empty, so it is a latent disaster that fixing the
     * grid resolution will expose. Evaluating it once per transform is this file's half of the fix;
     * interning the resolved grid list so the comparison short-circuits on reference identity is
     * the other half and belongs in {@code datum/}.
     */
    private final boolean datumTransformIsNoOp;

    /** Whether the source datum shifts via a {@code +nadgrids=} grid rather than a parameter set. */
    private final boolean srcGridShift;
    /** Whether the target datum shifts via a {@code +nadgrids=} grid rather than a parameter set. */
    private final boolean tgtGridShift;
    /** Whether the source datum declares a {@code +towgs84=} parameter set. */
    private final boolean srcHasToWgs84;
    /** Whether the target datum declares a {@code +towgs84=} parameter set. */
    private final boolean tgtHasToWgs84;

    /**
     * The resolved grid lists, hoisted out of the per-point loop. <b>Read only by
     * {@link #datumStage(ProjCoordinate)}, i.e. only by the bulk API.</b>
     *
     * <p>{@code Datum.shift} resolves its grid list on every call. For {@code NAD27} and
     * {@code potsdam} — the datums that declare their shift as a {@code +nadgrids=} string rather
     * than carrying a resolved list — that resolution is two {@code volatile} reads, so it is two
     * acquire loads per point in the innermost loop of a batch. {@link Datum#gridArray()} does it
     * once, here.
     *
     * <p><b>This freezes the grid list at construction, and that is a tightening, not a
     * loosening.</b> {@link #srcGridShift} and {@link #tgtGridShift} — the booleans that decide
     * whether a grid shift happens at all — have always been frozen here, four lines below. So a
     * transform constructed while a resolver was registered and used after it was removed already
     * decided "shift", and would then have resolved an <em>empty</em> list and shifted nothing,
     * silently. Freezing both halves together removes that disagreement. The single-point path
     * ({@link #datumTransform(ProjCoordinate)}) is untouched and still resolves per point,
     * deliberately: it is kept byte-identical to 1.4.3.
     *
     * <p>{@code null} whenever the corresponding {@code …GridShift} flag is false, and
     * {@code Grid.shift(Grid[], …)} treats null as a no-op, so a stale flag cannot dereference it.
     */
    private final Grid[] srcGrids;
    /** The target-side resolved grid list; see {@link #srcGrids}. */
    private final Grid[] tgtGrids;

    /**
     * The bulk path's scratch coordinate, pooled so that a batch allocates <b>nothing</b>.
     *
     * <h4>Why a pool and not a field, a {@code ThreadLocal}, or an allocation per batch</h4>
     *
     * <p>A plain field would make this class unsafe to share across threads, which is the one
     * property the consumer most depends on — a single cached transform is used by every executor
     * thread. A {@code ThreadLocal} would be thread-safe but leaves a live entry in every thread
     * that ever touched the transform, which is a poor trade for 40 bytes. Allocating one per batch
     * would be correct and nearly free (40 bytes amortised over 100,000 points is 4e-4 B/point),
     * but the bulk allocation contract is normative and stated as zero, and "nearly zero" is how a
     * zero-allocation guarantee stops being checkable.
     *
     * <p>So: a one-slot lock-free pool. A batch takes the scratch, uses it, and puts it back. Two
     * concurrent batches simply mean the second allocates and one of the two returns are dropped —
     * correct, bounded (the field holds at most one object) and self-healing, with no cleanup hook.
     * Steady state on any number of threads is zero allocation per batch.
     */
    private final AtomicReference<ProjCoordinate> scratchPool =
            new AtomicReference<ProjCoordinate>();

    /**
     * Creates a transformation from a source {@link CoordinateReferenceSystem}
     * to a target one, failing closed on a per-coordinate error.
     *
     * @param srcCRS the source CRS to transform from
     * @param tgtCRS the target CRS to transform to
     */
    public BasicCoordinateTransform(CoordinateReferenceSystem srcCRS,
                                    CoordinateReferenceSystem tgtCRS) {
        this(srcCRS, tgtCRS, DomainErrorPolicy.THROW);
    }

    /**
     * Creates a transformation from a source {@link CoordinateReferenceSystem}
     * to a target one.
     *
     * @param srcCRS            the source CRS to transform from
     * @param tgtCRS            the target CRS to transform to
     * @param domainErrorPolicy what {@link #transform} does with a per-coordinate failure; null
     *                          is treated as {@link DomainErrorPolicy#THROW}
     * @since 1.5.0
     */
    public BasicCoordinateTransform(CoordinateReferenceSystem srcCRS,
                                    CoordinateReferenceSystem tgtCRS,
                                    DomainErrorPolicy domainErrorPolicy) {
        this.srcCRS = srcCRS;
        this.tgtCRS = tgtCRS;
        this.domainErrorPolicy =
                domainErrorPolicy == null ? DomainErrorPolicy.THROW : domainErrorPolicy;

        // compute strategy for transformation at initialization time, to make transformation more efficient
        // this may include precomputing sets of parameters

        doInverseProjection = (srcCRS != CoordinateReferenceSystem.CS_GEO);
        doForwardProjection = (tgtCRS != CoordinateReferenceSystem.CS_GEO);

        srcInverseAvailable = !doInverseProjection
                || inverseAvailable(srcCRS.getProjection());

        doDatumTransform = doInverseProjection && doForwardProjection
                && srcCRS.getDatum() != tgtCRS.getDatum();

        boolean geocentric = false;
        // Decided in locals so the two fields can be final. Behaviour is unchanged; see the
        // fields' javadoc for why final matters on a per-vertex path.
        GeocentricConverter srcConv = null;
        GeocentricConverter tgtConv = null;

        if (doDatumTransform) {

            boolean isEllipsoidEqual = srcCRS.getDatum().getEllipsoid().isEqual(tgtCRS.getDatum().getEllipsoid());
            geocentric = ! isEllipsoidEqual || srcCRS.getDatum().hasTransformToWGS84()
                    || tgtCRS.getDatum().hasTransformToWGS84();

            if (geocentric) {
                srcConv = new GeocentricConverter(srcCRS.getDatum().getEllipsoid());
                tgtConv = new GeocentricConverter(tgtCRS.getDatum().getEllipsoid());

                int srcTransformType = srcCRS.getDatum().getTransformType();
                int tgtTransformType = tgtCRS.getDatum().getTransformType();

                if (srcTransformType == Datum.TYPE_GRIDSHIFT || tgtTransformType == Datum.TYPE_GRIDSHIFT) {

	                if (srcTransformType == Datum.TYPE_GRIDSHIFT) {
	                    srcConv.overrideWithWGS84Params();
	                }

	                if (tgtTransformType == Datum.TYPE_GRIDSHIFT) {
	                    tgtConv.overrideWithWGS84Params();
	                }

	                // After WGS84 params override, check if geocentric transform is still required
	                // https://github.com/OSGeo/PROJ/blob/5.2.0/src/pj_transform.c#L892
	                if(srcConv.isEqual(tgtConv)) {
	                    geocentric = false;
	                    srcConv = null;
	                    tgtConv = null;
	                }

                }
            }

        }

        transformViaGeocentric = geocentric;
        srcGeoConv = srcConv;
        tgtGeoConv = tgtConv;

        // ---------------------------------------------------------------------------------------
        // Hoist the invariants. Every assignment below is an accessor result or a pure predicate
        // over the two CRSs, so reading it in a loop instead of recomputing it per point cannot
        // change a single bit of output.
        //
        // Tolerant of the CoordinateReferenceSystem.CS_GEO sentinel, whose projection and datum are
        // both null: construction with it has always succeeded and transform() has always failed on
        // it, and neither may change here.
        // ---------------------------------------------------------------------------------------
        srcProj = srcCRS.getProjection();
        tgtProj = tgtCRS.getProjection();

        srcAxes = srcProj == null ? null : srcProj.getAxisOrder();
        tgtAxes = tgtProj == null ? null : tgtProj.getAxisOrder();
        srcAxesEnu = srcAxes != null && AxisOrder.ENU.equals(srcAxes);
        tgtAxesEnu = tgtAxes != null && AxisOrder.ENU.equals(tgtAxes);

        srcPmOffset = srcProj == null ? 0.0
                : srcProj.getPrimeMeridian().getOffsetFromGreenwich();
        tgtPmOffset = tgtProj == null ? 0.0
                : tgtProj.getPrimeMeridian().getOffsetFromGreenwich();

        srcDatum = srcCRS.getDatum();
        tgtDatum = tgtCRS.getDatum();

        if (doDatumTransform) {
            final int srcType = srcDatum.getTransformType();
            final int tgtType = tgtDatum.getTransformType();

            // The two early exits at the top of datumTransform, in the order it applies them: the
            // unknown-datum check first (PROJ checks it unconditionally and before the
            // identical-datums short cut), then the identical-datums short cut.
            datumTransformIsNoOp = srcType == Datum.TYPE_UNKNOWN
                    || tgtType == Datum.TYPE_UNKNOWN
                    || srcDatum.isEqual(tgtDatum);

            srcGridShift = srcType == Datum.TYPE_GRIDSHIFT;
            tgtGridShift = tgtType == Datum.TYPE_GRIDSHIFT;
            srcHasToWgs84 = srcDatum.hasTransformToWGS84();
            tgtHasToWgs84 = tgtDatum.hasTransformToWGS84();
            srcGrids = srcGridShift ? srcDatum.gridArray() : null;
            tgtGrids = tgtGridShift ? tgtDatum.gridArray() : null;
        } else {
            // datumTransform is never called, so these are never read. Give them the values that
            // make the bulk datum stage a no-op regardless.
            datumTransformIsNoOp = true;
            srcGridShift = false;
            tgtGridShift = false;
            srcHasToWgs84 = false;
            tgtHasToWgs84 = false;
            srcGrids = null;
            tgtGrids = null;
        }
    }

    /**
     * Whether a projection can actually be inverted.
     *
     * <h4>Why this does not simply return {@code hasInverse()}</h4>
     *
     * <p>Because {@code hasInverse()} is a hand-maintained <em>declaration</em>, it was read
     * nowhere in {@code core/src/main} before 1.5.0, and an unread boolean declared 66 times
     * across 102 classes drifts. Gating on it alone is wrong in <b>both</b> directions, and each
     * direction was found by a test rather than by reading:
     *
     * <table>
     * <caption>where {@code hasInverse()} disagrees with reality</caption>
     * <tr><th>class</th><th>{@code hasInverse()}</th><th>{@code projectInverse} override</th>
     *     <th>truth</th></tr>
     * <tr><td>{@code KrovakProjection}</td><td>absent, so {@code false}</td><td>yes</td>
     *     <td>invertible — EPSG:2065 and EPSG:5514 round-trip</td></tr>
     * <tr><td>{@code NewZealandMapGridProjection}</td><td>absent, so {@code false}</td>
     *     <td>yes</td><td>invertible — EPSG:27200 round-trips</td></tr>
     * <tr><td>{@code LandsatProjection}</td><td>{@code true}</td>
     *     <td>no: its method takes {@code Point2D.Double} and overrides nothing</td>
     *     <td>not invertible</td></tr>
     * <tr><td>{@code LongLatProjection}</td><td>absent, so {@code false}</td><td>no</td>
     *     <td>invertible — its inverse is the DTR multiply in
     *         {@code Projection.inverseProjectRadians}</td></tr>
     * <tr><td>{@code PlateCarreeProjection}, {@code LinearProjection}</td><td>{@code true}</td>
     *     <td>no</td><td>invertible — the base identity really is their inverse, because their
     *         forward is the base identity too</td></tr>
     * </table>
     *
     * <p>So the question asked is "is there an implementation", answered against the class
     * hierarchy, with {@code hasInverse()} and {@link Projection#isGeographic()} kept as the
     * affirmative shortcuts they are reliable for. Had this gate keyed on {@code hasInverse()}
     * alone it would have rejected three working CRSs — Krovak twice and NZMG once — which is
     * the same class of mistake as the defect it exists to fix, only louder.
     *
     * <p>Reflection, once per {@code BasicCoordinateTransform}, never per coordinate. The result
     * is cached in {@link #srcInverseAvailable}.
     *
     * @param p the projection to interrogate
     * @return true if inverse-projecting through {@code p} computes something
     */
    private static boolean inverseAvailable(Projection p) {
        if (p.hasInverse() || p.isGeographic()) {
            return true;
        }
        for (Class<?> c = p.getClass(); c != null && c != Projection.class;
                c = c.getSuperclass()) {
            try {
                c.getDeclaredMethod("projectInverse",
                        double.class, double.class, ProjCoordinate.class);
                return true;
            } catch (NoSuchMethodException notHere) {
                // keep walking up
            }
        }
        return false;
    }

    @Override
	public CoordinateReferenceSystem getSourceCRS() {
        return srcCRS;
    }

    @Override
	public CoordinateReferenceSystem getTargetCRS() {
        return tgtCRS;
    }


    /**
     * The policy this transform applies to a per-coordinate failure.
     *
     * @return the policy; never null
     * @since 1.5.0
     */
    public DomainErrorPolicy getDomainErrorPolicy() {
        return domainErrorPolicy;
    }

    /**
     * Transforms a coordinate from the source {@link CoordinateReferenceSystem}
     * to the target one.
     * <p>
     * <b>The {@code @throws} below has been on this method since 1.0 and was not honoured.</b>
     * A computation error was reported as a coordinate: the input unchanged, a {@code NaN}
     * ordinate, a pole, or the target projection's false easting/northing. It is honoured now,
     * under {@link DomainErrorPolicy#THROW}; a caller that needs the old silence can ask for
     * {@link DomainErrorPolicy#RETURN_NAN} and get {@code NaN} in every ordinate instead —
     * one detectable sentinel in place of four undetectable ones.
     *
     * @param src the input coordinate to be transformed
     * @param tgt the transformed coordinate
     * @return the target coordinate which was passed in
     * @throws Proj4jException if a computation error is encountered
     */
    // transform corresponds to the pj_transform function in proj.4
    @Override
	public ProjCoordinate transform(ProjCoordinate src, ProjCoordinate tgt)
            throws Proj4jException {
        if (domainErrorPolicy == DomainErrorPolicy.THROW) {
            return transformClosed(src, tgt);
        }
        try {
            return transformClosed(src, tgt);
        } catch (CrsTransformException e) {
            // Only the per-coordinate group is eligible: a CRS that cannot be built, an
            // operation with no inverse, and an environment failure are all properties of the
            // operation, and reporting one of those as NaN on every row of a four-million-row
            // dataset hides a planning-time defect four million times.
            if (!e.cause().isCoordinateError()) {
                throw e;
            }
            tgt.x = Double.NaN;
            tgt.y = Double.NaN;
            tgt.z = Double.NaN;
            return tgt;
        }
    }

    /**
     * The fail-closed transform: the 1.4.3 body, plus the inverse-availability gate and the
     * output postcondition.
     */
    private ProjCoordinate transformClosed(ProjCoordinate src, ProjCoordinate tgt) {
        // Read before setValue: callers are allowed to pass the same object as both arguments --
        // this method does that internally -- so by the time the postcondition runs, src may
        // already have been overwritten. Needed for the failure message and for nanIn.
        final double srcX = src.x;
        final double srcY = src.y;
        // NaN in, NaN out, as a *result*. See Projection.projectRadians' javadoc: the gie
        // comparator scores NaN-got against NaN-expected as deviation 0, i.e. a pass, so rows
        // exist that assert this. Only a non-finite result that arose from *finite* input is a
        // computation failure.
        final boolean nanIn = Double.isNaN(srcX) || Double.isNaN(srcY);
        // The absent-height sentinel, captured before setValue for the same aliasing reason. See
        // the restore below the datum stage, and vertical/InventedHeightTest for the measurement.
        final boolean noHeightIn = Double.isNaN(src.z);
    	tgt.setValue(src);
        srcCRS.getProjection().getAxisOrder().toENU(tgt);

        // NOTE: this method may be called many times, so needs to be as efficient as possible
        if (doInverseProjection) {
            if (!srcInverseAvailable) {
                // 1.4.3 called inverseProjectRadians unconditionally, and the base
                // Projection.projectInverse was an identity, so 33 forward-only projections used
                // as a source CRS returned the projected input as lon/lat radians -- a plausible
                // wrong answer, then datum-shifted and re-projected as if it were geographic.
                throw new CrsTransformException(ErrorCause.NO_INVERSE_AVAILABLE,
                        "source CRS " + srcCRS.getName() + " uses projection "
                                + srcCRS.getProjection() + ", which has no inverse; it can be a "
                                + "transformation target but not a source");
            }
            // inverse project to geographic
            srcCRS.getProjection().inverseProjectRadians(tgt, tgt);
        }

        srcCRS.getProjection().getPrimeMeridian().toGreenwich(tgt);

        // 'fix' commented out, see https://github.com/locationtech/proj4j/issues/116
        // fixes bug where computed Z value sticks around
        // tgt.clearZ();

        if (doDatumTransform) {
            datumTransform(tgt);
            if (noHeightIn) {
                // +proj=push +v_3 / +proj=pop +v_3. The geocentric leg is the only stage that can
                // invent a height: GeocentricConverter substitutes 0 for an absent one going in
                // (faithful to PROJ's fwd.cpp:47-51, which has no absent state to preserve) and
                // writes the computed height back out. PROJ's own pipeline for a 2D operation
                // brackets exactly this leg with push/pop and returns the caller's third ordinate
                // byte for byte -- so for ProjCoordinate's NaN sentinel, "leave it untouched" and
                // "propagate NaN" are the same instruction. Restoring here is sufficient: the
                // remaining stages are PrimeMeridian (x only), Projection (does not read or write
                // z at all) and AxisOrder (copies or negates it).
                tgt.z = Double.NaN;
            }
        }

        tgtCRS.getProjection().getPrimeMeridian().fromGreenwich(tgt);

        if (doForwardProjection) {
            // project from geographic to planar
            tgtCRS.getProjection().projectRadians(tgt, tgt);
        }

        tgtCRS.getProjection().getAxisOrder().fromENU(tgt);

        // The end-to-end postcondition. Projection.projectRadians and inverseProjectRadians each
        // check their own kernel, but the datum stage between them does not: a grid shift that
        // falls outside its extent, a geocentric round trip on a degenerate ellipsoid, or a
        // +towgs84 with a NaN parameter can all inject a non-finite value after the last
        // projection check. This is the single place that covers all of them, and it is the
        // method whose javadoc promised it.
        if (!nanIn && !tgt.hasValidXandYOrdinates()) {
            throw new CrsTransformException(ErrorCause.NUMERICAL_FAILURE,
                    "transform " + srcCRS.getName() + " -> " + tgtCRS.getName()
                            + " of (" + srcX + ", " + srcY + ") returned a non-finite "
                            + "coordinate (" + tgt.x + ", " + tgt.y + ")");
        }

        return tgt;
    }

    /**
     * Input:  long/lat/z coordinates in radians in the source datum
     * Output: long/lat/z coordinates in radians in the target datum
     *
     * @param pt the point containing the input and output values
     */
    private void datumTransform(ProjCoordinate pt) {
        int srcCrsDatumTransformType = srcCRS.getDatum().getTransformType();
        int tgtCrsDatumTransformType = tgtCRS.getDatum().getTransformType();

        /* -------------------------------------------------------------------- */
        /*      We cannot do any meaningful datum transformation if either      */
        /*      the source or destination are of an unknown datum type          */
        /*      (ie. only a +ellps declaration, no +datum).                     */
        /*                                                                     */
        /*      https://github.com/OSGeo/PROJ/blob/5.2.0/src/pj_transform.c#L835 */
        /*      checks this UNCONDITIONALLY and BEFORE the identical-datums     */
        /*      short cut. proj4j used to nest it inside an ellipsoid-equality  */
        /*      guard and put it after that short cut, so a bare-+ellps pair    */
        /*      with *differing* ellipsoids fell through to a geocentric round  */
        /*      trip that PROJ skips entirely. That injected a spurious         */
        /*      latitude shift and a spurious z; it was masked for years by the */
        /*      Datum.isEqual self-comparison typo, one bug cancelling another.  */
        /*      The two TYPE_WGS84/TYPE_UNKNOWN pair tests that used to follow  */
        /*      the short cut are subsumed by this check and have been removed. */
        /* -------------------------------------------------------------------- */
        if (srcCrsDatumTransformType == Datum.TYPE_UNKNOWN
                || tgtCrsDatumTransformType == Datum.TYPE_UNKNOWN) {
          return;
        }

        /* -------------------------------------------------------------------- */
        /*      Short cut if the datums are identical.                          */
        /* -------------------------------------------------------------------- */
        if (srcCRS.getDatum().isEqual(tgtCRS.getDatum())) {
          return;
        }

        /* -------------------------------------------------------------------- */
        /*	If this datum requires grid shifts, then apply it to geodetic    */
        /*      coordinates.                                                    */
        /* -------------------------------------------------------------------- */
        if (srcCrsDatumTransformType == Datum.TYPE_GRIDSHIFT) {
            srcCRS.getDatum().shift(pt);
        }

        /* ==================================================================== */
        /*      Do we need to go through geocentric coordinates?                */
        /* ==================================================================== */
        if (transformViaGeocentric) {
            /* -------------------------------------------------------------------- */
            /*      Convert to geocentric coordinates.                              */
            /* -------------------------------------------------------------------- */
            srcGeoConv.convertGeodeticToGeocentric(pt);

            /* -------------------------------------------------------------------- */
            /*      Convert between datums.                                         */
            /* -------------------------------------------------------------------- */
            if (srcCRS.getDatum().hasTransformToWGS84()) {
                srcCRS.getDatum().transformFromGeocentricToWgs84(pt);
            }

            if (tgtCRS.getDatum().hasTransformToWGS84()) {
                tgtCRS.getDatum().transformToGeocentricFromWgs84(pt);
            }

            /* -------------------------------------------------------------------- */
            /*      Convert back to geodetic coordinates.                           */
            /* -------------------------------------------------------------------- */
            tgtGeoConv.convertGeocentricToGeodetic(pt);
        }

        /* -------------------------------------------------------------------- */
        /*      Apply grid shift to destination if required.                    */
        /* -------------------------------------------------------------------- */
        if (tgtCrsDatumTransformType == Datum.TYPE_GRIDSHIFT) {
            tgtCRS.getDatum().inverseShift(pt);
        }
    }

    // ==============================================================================================
    // BulkCoordinateTransform
    //
    // The batch shape. Every method here is: validate once, take the pooled scratch once, then a
    // loop whose body is address arithmetic, two projection calls and the hoisted stages. Nothing
    // in the loop allocates, nothing in the loop calls a getter, and nothing in the loop constructs
    // an exception unless a point actually fails.
    // ==============================================================================================

    @Override
    public int transform2D(double[] xy, int offset, int numPts, int stride, byte[] status) {
        checkRange(xy, "xy", offset, numPts, stride, 2);
        checkStatus(status, numPts);
        if (numPts == 0) {
            return 0;
        }
        requireRunnableBatch();

        final boolean failFast = failFast(status);
        final ProjCoordinate c = acquireScratch();
        int failures = 0;
        try {
            for (int i = 0, k = offset; i < numPts; i++, k += stride) {
                final byte st = transformPoint(xy[k], xy[k + 1], Double.NaN, c, failFast);
                if (st == TransformStatus.OK) {
                    xy[k] = c.x;
                    xy[k + 1] = c.y;
                } else {
                    xy[k] = Double.NaN;
                    xy[k + 1] = Double.NaN;
                    failures++;
                }
                if (status != null) {
                    status[i] = st;
                }
            }
        } finally {
            releaseScratch(c);
        }
        return failures;
    }

    @Override
    public int transform3D(double[] xyz, int offset, int numPts, int stride, byte[] status) {
        checkRange(xyz, "xyz", offset, numPts, stride, 3);
        checkStatus(status, numPts);
        if (numPts == 0) {
            return 0;
        }
        requireRunnableBatch();

        final boolean failFast = failFast(status);
        final ProjCoordinate c = acquireScratch();
        int failures = 0;
        try {
            for (int i = 0, k = offset; i < numPts; i++, k += stride) {
                final byte st = transformPoint(xyz[k], xyz[k + 1], xyz[k + 2], c, failFast);
                if (st == TransformStatus.OK) {
                    xyz[k] = c.x;
                    xyz[k + 1] = c.y;
                    xyz[k + 2] = c.z;
                } else {
                    xyz[k] = Double.NaN;
                    xyz[k + 1] = Double.NaN;
                    xyz[k + 2] = Double.NaN;
                    failures++;
                }
                if (status != null) {
                    status[i] = st;
                }
            }
        } finally {
            releaseScratch(c);
        }
        return failures;
    }

    @Override
    public int transform2D(double[] src, int srcOff, int srcStride,
                           double[] dst, int dstOff, int dstStride, int numPts, byte[] status) {
        checkRange(src, "src", srcOff, numPts, srcStride, 2);
        checkRange(dst, "dst", dstOff, numPts, dstStride, 2);
        checkStatus(status, numPts);
        if (numPts == 0) {
            return 0;
        }
        requireRunnableBatch();

        final boolean failFast = failFast(status);
        // Aliasing: read-before-write makes a point safe against itself, so the only hazard is
        // overwriting a point that has not been read yet. Direction is chosen once, per batch.
        final boolean backwards =
                iterateBackwards(src, srcOff, srcStride, dst, dstOff, dstStride, numPts);
        final int from = backwards ? numPts - 1 : 0;
        final int end = backwards ? -1 : numPts;
        final int step = backwards ? -1 : 1;

        final ProjCoordinate c = acquireScratch();
        int failures = 0;
        try {
            for (int i = from; i != end; i += step) {
                final int sk = srcOff + i * srcStride;
                final int dk = dstOff + i * dstStride;
                final byte st = transformPoint(src[sk], src[sk + 1], Double.NaN, c, failFast);
                if (st == TransformStatus.OK) {
                    dst[dk] = c.x;
                    dst[dk + 1] = c.y;
                } else {
                    dst[dk] = Double.NaN;
                    dst[dk + 1] = Double.NaN;
                    failures++;
                }
                if (status != null) {
                    status[i] = st;
                }
            }
        } finally {
            releaseScratch(c);
        }
        return failures;
    }

    @Override
    public int transform(double[] x, double[] y, double[] z, int offset, int numPts,
                         byte[] status) {
        // Struct-of-arrays is stride 1 in three arrays, so the range check is the interleaved one
        // with a width of one ordinate.
        checkRange(x, "x", offset, numPts, 1, 1);
        checkRange(y, "y", offset, numPts, 1, 1);
        if (z != null) {
            checkRange(z, "z", offset, numPts, 1, 1);
        }
        checkStatus(status, numPts);
        if (numPts == 0) {
            return 0;
        }
        requireRunnableBatch();

        final boolean failFast = failFast(status);
        final boolean hasZ = z != null;
        final ProjCoordinate c = acquireScratch();
        int failures = 0;
        try {
            for (int i = 0, k = offset; i < numPts; i++, k++) {
                final byte st =
                        transformPoint(x[k], y[k], hasZ ? z[k] : Double.NaN, c, failFast);
                if (st == TransformStatus.OK) {
                    x[k] = c.x;
                    y[k] = c.y;
                    if (hasZ) {
                        z[k] = c.z;
                    }
                } else {
                    x[k] = Double.NaN;
                    y[k] = Double.NaN;
                    if (hasZ) {
                        z[k] = Double.NaN;
                    }
                    failures++;
                }
                if (status != null) {
                    status[i] = st;
                }
            }
        } finally {
            releaseScratch(c);
        }
        return failures;
    }

    /**
     * The whole per-point pipeline, on one scratch coordinate, with every invariant read from a
     * field instead of recomputed.
     *
     * <h4>Why this is bit-for-bit the single-point path</h4>
     *
     * <p>Compare against {@link #transformClosed(ProjCoordinate, ProjCoordinate)} stage by stage.
     * The arithmetic is not merely equivalent, it is the same expressions in the same order on the
     * same object; what differs is only how the operands were <em>found</em>:
     *
     * <table>
     * <caption>single-point stage to bulk stage</caption>
     * <tr><th>single point</th><th>here</th><th>why bitwise</th></tr>
     * <tr><td>{@code tgt.setValue(src)}</td><td>three field stores</td>
     *     <td>a copy either way</td></tr>
     * <tr><td>{@code srcCRS.getProjection().getAxisOrder().toENU(tgt)}</td>
     *     <td>skipped when the order is ENU, else the same call</td>
     *     <td>ENU's three axes return the ordinate unchanged with no arithmetic, so the call is an
     *         identity copy. Not skipped for a reversed axis, where it negates.</td></tr>
     * <tr><td>{@code inverseProjectRadians(tgt, tgt)}</td><td>the same call</td>
     *     <td>same method, same object</td></tr>
     * <tr><td>{@code getPrimeMeridian().toGreenwich(tgt)}</td><td>{@code c.x += srcPmOffset}</td>
     *     <td>that method <em>is</em> {@code coord.x += offset}. Never elided at offset 0, because
     *         {@code -0.0 + 0.0} is {@code +0.0}.</td></tr>
     * <tr><td>{@code datumTransform(tgt)}</td><td>{@link #datumStage(ProjCoordinate)}</td>
     *     <td>same operations, same order; only the predicates guarding them are hoisted</td></tr>
     * <tr><td>{@code getPrimeMeridian().fromGreenwich(tgt)}</td><td>{@code c.x -= tgtPmOffset}</td>
     *     <td>as above</td></tr>
     * <tr><td>{@code projectRadians(tgt, tgt)}</td><td>the same call</td><td>same method</td></tr>
     * <tr><td>{@code getAxisOrder().fromENU(tgt)}</td><td>skipped when ENU, else the same call</td>
     *     <td>as above</td></tr>
     * <tr><td>{@code !tgt.hasValidXandYOrdinates()}</td><td>{@code (c.x - c.x) != 0.0}</td>
     *     <td>{@code NaN-NaN}, {@code Inf-Inf} and {@code -Inf+Inf} are all {@code NaN};
     *         {@code finite-finite} is {@code 0.0}. Exactly {@code !isFinite}, in one FP op with no
     *         call and no bit extraction.</td></tr>
     * </table>
     *
     * <h4>Failure classification is identical because the same code raises it</h4>
     *
     * <p>Nothing here re-derives what counts as a failure. The projections' own domain checks and
     * finiteness postconditions run untouched and throw exactly what they throw for a single point;
     * this method catches, reads {@link Proj4jException#cause()} and maps it to a status byte. So
     * {@code NaN} input still propagates as a result, a finite out-of-domain input is still an
     * error, and finite input yielding a non-finite output is still an error — because those three
     * decisions are made in {@code Projection} and in the postcondition below, not here.
     *
     * <p>A cause that is <b>not</b> per-coordinate is rethrown: it is a property of the operation,
     * and answering it with a status byte on every row would report a planning-time defect once per
     * vertex.
     *
     * <p>The catch is free on the success path — a JVM exception table costs nothing when nothing
     * throws — but the <em>throw</em> is not: a kernel that rejects a coordinate still fills in a
     * stack trace, at roughly 1-10&nbsp;&micro;s. Removing that needs errno-style returns inside the
     * ~100 projection classes, which is a separate change to {@code proj/}.
     *
     * @param sx       the source x ordinate, as the caller supplied it
     * @param sy       the source y ordinate
     * @param sz       the source height, or {@code NaN} for "no height"
     * @param c        the scratch coordinate; holds the result on return
     * @param failFast whether to throw rather than return a status byte
     * @return {@link TransformStatus#OK}, or the per-coordinate failure code
     */
    private byte transformPoint(double sx, double sy, double sz, ProjCoordinate c,
                                boolean failFast) {
        // NaN in, NaN out, as a *result*: only a non-finite result that arose from finite input is
        // a computation failure. Same test, same place, as transformClosed.
        final boolean nanIn = Double.isNaN(sx) || Double.isNaN(sy);
        // The absent-height sentinel; see the restore after datumStage below.
        final boolean noHeightIn = Double.isNaN(sz);
        c.x = sx;
        c.y = sy;
        c.z = sz;

        if (!srcAxesEnu) {
            srcAxes.toENU(c);
        }
        try {
            if (doInverseProjection) {
                srcProj.inverseProjectRadians(c, c);
            }
            c.x += srcPmOffset;

            if (!datumTransformIsNoOp) {
                datumStage(c);
                if (noHeightIn) {
                    // push/pop +v_3, exactly as transformClosed does it. Placed inside the
                    // no-op guard rather than beside it because when datumStage is skipped
                    // nothing has touched z, so the two placements are indistinguishable.
                    c.z = Double.NaN;
                }
            }

            c.x -= tgtPmOffset;

            if (doForwardProjection) {
                tgtProj.projectRadians(c, c);
            }
            if (!tgtAxesEnu) {
                tgtAxes.fromENU(c);
            }
        } catch (Proj4jException e) {
            final byte st = TransformStatus.forCause(e.cause());
            if (st == TransformStatus.NOT_A_COORDINATE_ERROR || failFast) {
                throw e;
            }
            return st;
        }

        // The end-to-end postcondition, covering the datum stage between the two projection checks:
        // a grid shift outside its extent, a geocentric round trip on a degenerate ellipsoid, or a
        // +towgs84 with a NaN parameter can each inject a non-finite value after the last
        // projection check.
        if (!nanIn && ((c.x - c.x) != 0.0 || (c.y - c.y) != 0.0)) {
            if (failFast) {
                throw new CrsTransformException(ErrorCause.NUMERICAL_FAILURE,
                        "transform " + srcCRS.getName() + " -> " + tgtCRS.getName()
                                + " of (" + sx + ", " + sy + ") returned a non-finite "
                                + "coordinate (" + c.x + ", " + c.y + ")");
            }
            return TransformStatus.ERR_NUMERICAL_FAILURE;
        }
        return TransformStatus.OK;
    }

    /**
     * {@link #datumTransform(ProjCoordinate)} with its predicates hoisted.
     *
     * <p>Only reached when {@link #datumTransformIsNoOp} is false, which already accounts for both
     * of that method's early exits — including the {@code Datum.isEqual} whose gridshift branch is
     * O(grid size).
     *
     * @param pt the point, in radians in the source datum on entry and the target datum on return
     */
    private void datumStage(ProjCoordinate pt) {
        if (srcGridShift) {
            // Grid.shift(Grid[], ...) rather than srcDatum.shift(pt): same arithmetic, same
            // selection order, but the grid list was resolved once in the constructor. See
            // srcGrids.
            Grid.shift(srcGrids, false, pt);
        }
        if (transformViaGeocentric) {
            srcGeoConv.convertGeodeticToGeocentric(pt);
            if (srcHasToWgs84) {
                srcDatum.transformFromGeocentricToWgs84(pt);
            }
            if (tgtHasToWgs84) {
                tgtDatum.transformToGeocentricFromWgs84(pt);
            }
            tgtGeoConv.convertGeocentricToGeodetic(pt);
        }
        if (tgtGridShift) {
            Grid.shift(tgtGrids, true, pt);
        }
    }

    /**
     * Whether a failing point throws rather than being recorded.
     *
     * <p>Fail-fast is what a null status array asks for, but {@link DomainErrorPolicy#RETURN_NAN}
     * is a caller who has explicitly asked never to be thrown at for a per-coordinate failure, and
     * that request outranks the absent array: they still get {@code NaN} in every ordinate and the
     * failure count as the return value.
     *
     * @param status the caller's status array, or null
     * @return true if a per-coordinate failure should abandon the batch with an exception
     */
    private boolean failFast(byte[] status) {
        return status == null && domainErrorPolicy == DomainErrorPolicy.THROW;
    }

    /**
     * Batch-level preconditions that are properties of the <em>operation</em> rather than of any
     * coordinate, so they are checked once and throw whether or not a status array was supplied.
     */
    private void requireRunnableBatch() {
        if (srcProj == null || tgtProj == null) {
            throw new CrsTransformException(ErrorCause.API_MISUSE,
                    "the bulk API needs both CRSs to carry a projection; "
                            + (srcProj == null ? "source" : "target") + " CRS "
                            + (srcProj == null ? srcCRS.getName() : tgtCRS.getName())
                            + " has none (CoordinateReferenceSystem.CS_GEO cannot be transformed)");
        }
        if (!srcInverseAvailable) {
            throw new CrsTransformException(ErrorCause.NO_INVERSE_AVAILABLE,
                    "source CRS " + srcCRS.getName() + " uses projection " + srcProj
                            + ", which has no inverse; it can be a transformation target but not "
                            + "a source");
        }
    }

    /**
     * Takes the pooled scratch coordinate, or makes one if another thread holds it.
     *
     * @return a scratch coordinate owned by this call
     */
    private ProjCoordinate acquireScratch() {
        ProjCoordinate c = scratchPool.getAndSet(null);
        return c != null ? c : new ProjCoordinate();
    }

    /**
     * Returns the scratch coordinate to the pool. If a concurrent batch has already put one back,
     * this one is simply dropped — the pool holds at most one, by construction.
     *
     * @param c the scratch coordinate to return
     */
    private void releaseScratch(ProjCoordinate c) {
        scratchPool.set(c);
    }

    /**
     * Validates one strided range, once per batch.
     *
     * <p>Arithmetic in {@code long} on purpose: {@code offset + (numPts - 1) * stride} overflows
     * {@code int} for large batches, and an overflowed bound check is worse than none — it turns a
     * rejected call into an {@code ArrayIndexOutOfBoundsException} from inside the loop, after part
     * of the caller's buffer has already been overwritten.
     *
     * @param buf    the buffer
     * @param name   its parameter name, for the message
     * @param offset index of the first point's first ordinate
     * @param numPts number of points
     * @param stride ordinates per point
     * @param width  ordinates per point this method actually reads and writes
     */
    private static void checkRange(double[] buf, String name, int offset, int numPts, int stride,
                                   int width) {
        if (buf == null) {
            throw new CrsTransformException(ErrorCause.API_MISUSE, name + " must not be null");
        }
        if (numPts < 0) {
            throw new CrsTransformException(ErrorCause.API_MISUSE,
                    "numPts must not be negative, was " + numPts);
        }
        if (offset < 0) {
            throw new CrsTransformException(ErrorCause.API_MISUSE,
                    "offset into " + name + " must not be negative, was " + offset);
        }
        if (stride < width) {
            throw new CrsTransformException(ErrorCause.API_MISUSE,
                    "stride for " + name + " must be at least " + width + ", was " + stride);
        }
        if (numPts == 0) {
            return;
        }
        long last = (long) offset + (long) (numPts - 1) * (long) stride + (width - 1);
        if (last >= buf.length) {
            throw new CrsTransformException(ErrorCause.API_MISUSE,
                    name + " is too short for " + numPts + " points at offset " + offset
                            + " stride " + stride + ": needs index " + last + ", length is "
                            + buf.length);
        }
    }

    /**
     * Validates the status array, once per batch. A status array shorter than the batch is API
     * misuse rather than a truncated report, because a caller who reads only the first
     * {@code status.length} entries would conclude the rest of the geometry succeeded.
     *
     * @param status the caller's status array, or null
     * @param numPts number of points in the batch
     */
    private static void checkStatus(byte[] status, int numPts) {
        if (status != null && status.length < numPts) {
            throw new CrsTransformException(ErrorCause.API_MISUSE,
                    "status must have room for " + numPts + " points, length is " + status.length);
        }
    }

    /**
     * Chooses the iteration direction for the source-to-destination form.
     *
     * <p>Each point's ordinates are read into locals before anything is written, so a point can
     * never clobber itself. The only hazard is clobbering a point that has <em>not yet been
     * read</em>, and within one array that is decided by whether the destination sits above or
     * below the source:
     *
     * <ul>
     * <li>different arrays, or ranges that do not overlap — either direction is safe;</li>
     * <li>exactly coincident ({@code srcOff == dstOff}, equal strides) — a pure in-place batch,
     *     where every write lands on the ordinates just read;</li>
     * <li>overlapping at equal strides — the write index sits a fixed distance from the read
     *     index, so descending is safe when the destination is above the source and ascending when
     *     it is below;</li>
     * <li>overlapping at <em>different</em> strides — the write index crosses the read index part
     *     way through the batch, so <b>no single direction is safe</b>. Rejected rather than
     *     silently mis-transformed: this is the one shape where returning a plausible answer would
     *     be worse than refusing.</li>
     * </ul>
     *
     * @return true to iterate from the last point to the first
     */
    private static boolean iterateBackwards(double[] src, int srcOff, int srcStride,
                                            double[] dst, int dstOff, int dstStride, int numPts) {
        if (src != dst) {
            return false;
        }
        long srcLo = srcOff;
        long srcHi = (long) srcOff + (long) (numPts - 1) * srcStride + 1;
        long dstLo = dstOff;
        long dstHi = (long) dstOff + (long) (numPts - 1) * dstStride + 1;
        if (srcHi < dstLo || dstHi < srcLo) {
            return false;
        }
        if (srcOff == dstOff && srcStride == dstStride) {
            return false;
        }
        if (srcStride != dstStride) {
            throw new CrsTransformException(ErrorCause.API_MISUSE,
                    "overlapping source and destination ranges in one array at different strides ("
                            + srcStride + " and " + dstStride + ") cannot be transformed in any "
                            + "single direction without losing points; copy the source first, or "
                            + "use matching strides");
        }
        return dstOff > srcOff;
    }

}

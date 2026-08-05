/*
 * Copyright 2026, PROJ4J contributors
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
package org.locationtech.proj4j.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.locationtech.proj4j.BasicCoordinateTransform;
import org.locationtech.proj4j.BulkCoordinateTransform;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CrsCreationException;
import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.datum.Datum;

/**
 * A ready-to-use transformation from one {@link Crs} to another, together with everything a caller
 * needs to decide whether to trust its output.
 *
 * <p>Built by {@link Proj#createCrsToCrs(String, String)}, which <b>refuses to build one it cannot
 * vouch for</b>: if the only available datum change is a ballpark one, creation throws
 * {@link CrsCreationException} with {@link ErrorCause#BALLPARK_REJECTED}. That is the single most
 * important behavioural difference from {@link org.locationtech.proj4j.CoordinateTransformFactory},
 * and the reason is arithmetic rather than aesthetic: a wrong answer here is finite, plausible,
 * in the right units and in the right part of the world, so the only place it can be caught is
 * before the first row.
 *
 * <pre>{@code
 * // Planning: throws now, on this line, if the answer would be untrustworthy.
 * CrsOperation op = Proj.createCrsToCrs("EPSG:4326", "EPSG:32633");
 *
 * // Per row: thread-safe, allocation-free in the bulk form.
 * BulkCoordinateTransform bulk = op.bulk();
 * bulk.transform2D(xy, 0, numVertices, 2, status);
 * }</pre>
 *
 * <h2>Fail-closed</h2>
 *
 * <p>For every method here that produces coordinates, exactly one of two things happens: it returns
 * normally and every meaningful ordinate is finite, or it throws {@link CrsTransformException} with
 * a non-null {@link CrsTransformException#cause()}. There is no sentinel. {@code NaN},
 * &plusmn;{@code Infinity}, the input coordinate and the projection's false easting are never used
 * to signal failure. The one documented exception is
 * {@link org.locationtech.proj4j.DomainErrorPolicy#RETURN_NAN}, which a caller must ask for by name
 * and which substitutes <em>one</em> detectable sentinel for the four undetectable ones 1.4.3 used.
 *
 * <h2>Thread safety</h2>
 *
 * <p>This object is immutable and <b>safe to share across any number of threads</b>, which is the
 * property a per-vertex consumer depends on most: one cached operation, reached by every executor
 * thread. {@link #transform(ProjCoordinate, ProjCoordinate)} is reentrant provided the caller does
 * not share the {@code src} and {@code dst} objects between threads &mdash; those are the caller's,
 * and {@link ProjCoordinate} is thread-confined as it always was. There is no
 * last-used-operation cache on this object, so candidate selection cannot leak state between
 * threads.
 *
 * @see Proj#createCrsToCrs(String, String)
 * @see BulkCoordinateTransform
 * @see BallparkPolicy
 * @since 1.5.0
 */
public final class CrsOperation {

    private final Crs source;
    private final Crs target;
    private final ProjContext context;
    private final BasicCoordinateTransform transform;
    private final boolean ballpark;
    private final String ballparkReason;
    private final List<String> warnings;
    private final List<GridInfo> missingGrids;
    private final CrsOperationCandidate selected;
    private final List<CrsOperationCandidate> candidates;

    private CrsOperation(Crs source, Crs target, ProjContext context,
                         BasicCoordinateTransform transform, boolean ballpark,
                         String ballparkReason, List<String> warnings,
                         List<GridInfo> missingGrids, CrsOperationCandidate selected,
                         List<CrsOperationCandidate> candidates) {
        this.source = source;
        this.target = target;
        this.context = context;
        this.transform = transform;
        this.ballpark = ballpark;
        this.ballparkReason = ballparkReason;
        this.warnings = Collections.unmodifiableList(warnings);
        this.missingGrids = Collections.unmodifiableList(missingGrids);
        this.selected = selected;
        this.candidates = Collections.unmodifiableList(candidates);
    }

    /**
     * Analyses the CRS pair, selects an operation, applies the policies, and either builds the
     * operation or refuses to.
     *
     * <p>Two routes, and which one is taken is a property of the {@link ProjContext}, not of the
     * classpath:
     * <ul>
     * <li><b>With an authority database</b> ({@link ProjContext#database()} non-null), the operations
     * the authority actually published are enumerated in <em>both</em> directions, ranked, and
     * filtered by policy &mdash; see {@link Proj#candidateOperations(Crs, Crs)}. {@code EPSG:4267} to
     * {@code EPSG:4269} has <b>nine</b> of them, not one of which is ballpark, and it selects
     * {@code EPSG:1241} "NAD27 to NAD83 (1)" at 0.15&nbsp;m.</li>
     * <li><b>Without one</b>, there is nothing to enumerate, and the legacy datum model's single
     * synthesised operation is assessed by the two ballpark rules that need no database. For
     * {@code EPSG:4267 -> EPSG:4269} that is still {@link ErrorCause#BALLPARK_REJECTED}, which
     * remains the right answer when the authority's offer cannot be seen.</li>
     * </ul>
     *
     * @param source  the source CRS
     * @param target  the target CRS
     * @param context the governing policies
     * @return the operation; never null
     * @throws CrsCreationException with {@link ErrorCause#BALLPARK_REJECTED},
     *                              {@link ErrorCause#BEST_OPERATION_UNAVAILABLE},
     *                              {@link ErrorCause#UNSUPPORTED_OPERATION_METHOD},
     *                              {@link ErrorCause#MISSING_GRID} or
     *                              {@link ErrorCause#NO_OPERATION_AVAILABLE}, according to why no
     *                              candidate could be selected
     */
    static CrsOperation create(Crs source, Crs target, ProjContext context) {
        List<String> warnings = new ArrayList<String>();
        List<GridInfo> missing = new ArrayList<GridInfo>();
        collectMissing(source, "source", missing, warnings);
        collectMissing(target, "target", missing, warnings);

        if (context.axisOrderPolicy() != org.locationtech.proj4j.io.wkt.AxisOrderPolicy.LEGACY) {
            if (!source.isAxisOrderAuthoritative() || !target.isAxisOrderAuthoritative()) {
                warnings.add("axis order is not declared by at least one of these CRS definitions, "
                        + "so it was inferred: source " + source.axisOrder() + " ("
                        + source.axisOrderNote() + "), target " + target.axisOrder() + " ("
                        + target.axisOrderNote() + ")");
            }
        }

        if (context.hasDatabase()) {
            OperationSelector.Selection selection = OperationSelector.select(
                    context.database(), source, target, context);
            if (!selection.databaseCannotSeeThisPair()) {
                return fromSelection(source, target, context, selection, warnings, missing);
            }
            warnings.add("the authority database has no entry for at least one of these CRSs ("
                    + describeIdentity(source) + " -> " + describeIdentity(target) + "), so real "
                    + "operation selection was not possible and the legacy datum model was used "
                    + "instead. Accuracy and area of use are reported as absent rather than "
                    + "estimated.");
        }
        return fromLegacyDatumModel(source, target, context, warnings, missing);
    }

    private static String describeIdentity(Crs crs) {
        if (!crs.identifiers().isEmpty()) {
            return crs.identifiers().toString();
        }
        return crs.source() + " \"" + crs.name() + "\"";
    }

    /**
     * Builds from a database-backed selection, or throws with the cause the selector determined.
     *
     * <p><b>The consistency gate is the important part of this method.</b> Selection says which
     * published operation applies; the engine that actually moves coordinates is
     * {@link BasicCoordinateTransform}, which reaches a grid shift through the datum model's own
     * {@code +nadgrids=} list. If those two disagree about whether any shift happens at all, this
     * class would report 0.15&nbsp;m accuracy over a coordinate that had no shift applied &mdash; the
     * 95.573&nbsp;m defect with a confident number attached to it, which is strictly worse than the
     * original. So the agreement is checked, and a disagreement refuses or warns.
     */
    private static CrsOperation fromSelection(Crs source, Crs target, ProjContext context,
                                              OperationSelector.Selection selection,
                                              List<String> warnings, List<GridInfo> missing) {
        warnings.addAll(selection.warnings());

        if (selection.failureCause() != null) {
            throw new CrsCreationException(selection.failureCause(), selection.failureMessage());
        }
        if (selection.noDatumChange()) {
            BasicCoordinateTransform identityDatum = new BasicCoordinateTransform(source.legacy(),
                    target.legacy(), context.domainErrorPolicy());
            return new CrsOperation(source, target, context, identityDatum, false, null, warnings,
                    missing, null, selection.candidates());
        }
        CrsOperationCandidate chosen = selection.selected();

        List<GridInfo> allMissing = new ArrayList<GridInfo>(missing);
        allMissing.addAll(chosen.missingGrids());

        String engineMismatch = engineDisagreement(source, target, chosen);
        if (engineMismatch != null) {
            if (context.gridPolicy() == GridPolicy.REQUIRE_ALL) {
                throw new CrsCreationException(ErrorCause.MISSING_GRID, engineMismatch);
            }
            warnings.add(engineMismatch);
        }

        boolean isBallpark = chosen.isBallpark();
        String reason = isBallpark ? chosen.rejectionReason().orElse(null) : null;
        BasicCoordinateTransform bct = new BasicCoordinateTransform(source.legacy(),
                target.legacy(), context.domainErrorPolicy());
        return new CrsOperation(source, target, context, bct, isBallpark, reason, warnings,
                allMissing, chosen, selection.candidates());
    }

    /**
     * Whether the transformation engine would fail to apply the shift the selected candidate
     * promises, and if so exactly how &mdash; naming both what selection chose and what the engine
     * can reach.
     *
     * @return the disagreement, or null when selection and execution agree
     */
    private static String engineDisagreement(Crs source, Crs target,
                                             CrsOperationCandidate chosen) {
        if (chosen.grids().isEmpty()) {
            // A parameterised operation: the engine applies the datum model's own +towgs84. Whether
            // its parameters equal the selected candidate's is a separate question, reported by
            // accuracy() and by describe(), not by this gate.
            return null;
        }
        List<String> reachable = new ArrayList<String>();
        collectReachable(source, reachable);
        collectReachable(target, reachable);
        if (reachable.isEmpty()) {
            return "selection chose " + chosen.authorityCode() + " (" + chosen.name()
                    + "), whose grid file resolves, but the transformation engine reaches a grid "
                    + "shift through the datum model's own +nadgrids= list and not one of those "
                    + "files is reachable. It would apply NO shift at all while this operation "
                    + "reported "
                    + (chosen.accuracy().isPresent()
                            ? chosen.accuracy().get().metres() + " m accuracy"
                            : "a published operation")
                    + " -- the same silent metre-scale error as before, with a confident number "
                    + "attached to it. Refusing.\n"
                    + "  selected operation needs : " + describeGridNames(chosen) + "\n"
                    + "  engine can reach         : nothing\n"
                    + "Add a grid file under one of the names the datum model uses, or set "
                    + "GridPolicy.WARN to proceed with this stated in warnings().";
        }
        for (int i = 0; i < chosen.grids().size(); i++) {
            List<String> probed = chosen.grids().get(i).probedNames();
            for (int j = 0; j < probed.size(); j++) {
                if (reachable.contains(probed.get(j))) {
                    return null;
                }
            }
        }
        return "selection chose " + chosen.authorityCode() + " (" + chosen.name()
                + "), but the transformation engine will use a different grid: the datum model's "
                + "reachable files are " + reachable + " and the selected operation's are "
                + describeGridNames(chosen) + ". A shift IS applied, so the coordinate is not left "
                + "unshifted, but it is the shift of a different published operation than the one "
                + "named here.";
    }

    private static String describeGridNames(CrsOperationCandidate chosen) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < chosen.grids().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(chosen.grids().get(i).name()).append(" (tried ")
                    .append(chosen.grids().get(i).probedNames()).append(')');
        }
        return sb.append(']').toString();
    }

    private static void collectReachable(Crs crs, List<String> into) {
        List<GridInfo> grids = crs.grids();
        for (int i = 0; i < grids.size(); i++) {
            if (grids.get(i).isAvailable()) {
                into.add(grids.get(i).name());
            }
        }
    }

    /**
     * The pre-database path, unchanged: the legacy datum model synthesises one operation and the two
     * database-free ballpark rules decide whether to vouch for it.
     */
    private static CrsOperation fromLegacyDatumModel(Crs source, Crs target, ProjContext context,
                                                     List<String> warnings,
                                                     List<GridInfo> missing) {
        String ballparkReason = ballparkReason(source, target, context, missing);
        boolean ballpark = ballparkReason != null;

        if (ballpark && context.ballparkPolicy() == BallparkPolicy.REJECT) {
            throw new CrsCreationException(ErrorCause.BALLPARK_REJECTED, message(source, target,
                    ballparkReason, missing));
        }
        if (ballpark) {
            warnings.add("BALLPARK: " + ballparkReason);
        }
        BasicCoordinateTransform bct = new BasicCoordinateTransform(source.legacy(),
                target.legacy(), context.domainErrorPolicy());
        return new CrsOperation(source, target, context, bct, ballpark, ballparkReason, warnings,
                missing, null, Collections.<CrsOperationCandidate>emptyList());
    }

    private static void collectMissing(Crs crs, String role, List<GridInfo> missing,
                                       List<String> warnings) {
        List<GridInfo> m = crs.missingGrids();
        for (int i = 0; i < m.size(); i++) {
            missing.add(m.get(i));
            warnings.add("the " + role + " CRS declares grid " + m.get(i).name()
                    + " and no configured resolver can find it: " + m.get(i).describe());
        }
        Datum d = crs.legacy().getDatum();
        if (!m.isEmpty() && d != null && d.hasTransformToWGS84()) {
            warnings.add("the " + role + " CRS falls back from its declared grid shift to a "
                    + "parameterised Helmert transformation, which is what PROJ does too, but it is "
                    + "less accurate than the grid it replaces");
        }
    }

    /**
     * Why this pair is ballpark, or {@code null} if it is not.
     *
     * <p>Two of the three rules from the design apply without a CRS database. The third
     * &mdash; PROJ's synthesised no-parameter operations, read from {@code proj.db} &mdash; cannot
     * be evaluated here and is not pretended to be.
     */
    private static String ballparkReason(Crs source, Crs target, ProjContext context,
                                         List<GridInfo> missing) {
        Datum s = source.legacy().getDatum();
        Datum t = target.legacy().getDatum();
        if (s == null || t == null) {
            return null;
        }
        if (s == t || s.isEqual(t)) {
            // Same datum: there is no datum change to be ballpark about.
            return null;
        }
        int st = s.getTransformType();
        int tt = t.getTransformType();

        // (c) legacy-engine-derived: the engine's datum stage returns from an early exit for every
        // coordinate, so the datums differ and nothing is done about it. In the shipped engine that
        // is the unknown-datum exit, i.e. a bare +ellps= with no +datum and no +towgs84.
        if (st == Datum.TYPE_UNKNOWN || tt == Datum.TYPE_UNKNOWN) {
            return "the datums differ (" + s.getCode() + " -> " + t.getCode() + ") but "
                    + (st == Datum.TYPE_UNKNOWN ? "the source" : "the target")
                    + " datum has no transformation to WGS 84 and no grid, so the engine would "
                    + "apply no datum shift at all. The coordinate would be out by the size of "
                    + "the shift, and would look perfectly reasonable.";
        }

        // (b) grid-derived: a declared grid is unreachable and there is no non-grid fallback, so
        // the shift silently does not happen. This is the 95.573 m at San Francisco.
        if (!missing.isEmpty() && context.gridPolicy() == GridPolicy.REQUIRE_ALL) {
            boolean sourceCanFallBack = st != Datum.TYPE_GRIDSHIFT || s.hasTransformToWGS84();
            boolean targetCanFallBack = tt != Datum.TYPE_GRIDSHIFT || t.hasTransformToWGS84();
            boolean sourceMissing = !source.missingGrids().isEmpty() && !sourceCanFallBack;
            boolean targetMissing = !target.missingGrids().isEmpty() && !targetCanFallBack;
            if (sourceMissing || targetMissing) {
                StringBuilder sb = new StringBuilder();
                sb.append("the datums differ (").append(s.getCode()).append(" -> ")
                        .append(t.getCode()).append(") and the declared grid shift cannot be "
                                + "performed: ");
                for (int i = 0; i < missing.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(missing.get(i).name());
                }
                sb.append(" unreachable, with no parameterised fallback. PROJ's @ prefix makes "
                        + "this silent; here it is not.");
                return sb.toString();
            }
        }
        return null;
    }

    private static String message(Crs source, Crs target, String reason, List<GridInfo> missing) {
        StringBuilder sb = new StringBuilder();
        sb.append("refusing to build ").append(source.name()).append(" -> ").append(target.name())
                .append(": it would be a ballpark transformation. ").append(reason);
        if (!missing.isEmpty()) {
            sb.append("\nUnreachable grids:");
            for (int i = 0; i < missing.size(); i++) {
                sb.append("\n  ").append(missing.get(i).describe());
            }
        }
        sb.append("\nTo proceed anyway, and record that you did: ProjContext.builder()"
                + ".ballparkPolicy(BallparkPolicy.ALLOW).build(). To fix it, put the grid on the "
                + "classpath. To reproduce proj4j 1.4.3 exactly, use "
                + "CoordinateTransformFactory, which is unchanged and will not be changed.");
        return sb.toString();
    }

    // ------------------------------------------------------------------------------- accessors

    /**
     * The source CRS.
     *
     * @return the source; never null
     */
    public Crs source() {
        return source;
    }

    /**
     * The target CRS.
     *
     * @return the target; never null
     */
    public Crs target() {
        return target;
    }

    /**
     * The context whose policies this operation was built under.
     *
     * @return the context; never null
     */
    public ProjContext context() {
        return context;
    }

    /**
     * Whether this operation performs a datum change it cannot actually carry out.
     *
     * <p>Under the default {@link BallparkPolicy#REJECT} this is <b>always {@code false}</b>,
     * because such an operation is refused at creation rather than returned flagged. It can be
     * {@code true} only for an operation the caller explicitly asked for with
     * {@link BallparkPolicy#ALLOW}.
     *
     * @return true iff this is a ballpark transformation
     */
    public boolean isBallparkTransformation() {
        return ballpark;
    }

    /**
     * Why this operation is ballpark.
     *
     * @return the reason, or empty if it is not ballpark
     */
    public Optional<String> ballparkReason() {
        return Optional.ofNullable(ballparkReason);
    }

    /**
     * The stated accuracy of this operation, in metres.
     *
     * <p>Present when an authority database selected a published operation that carries one: 0.15&nbsp;m
     * for {@code EPSG:1241}, 1.0&nbsp;m for {@code EPSG:7710}, 2.0&nbsp;m for the WGS 84 ensemble
     * offsets. Empty in three cases, all of them meaning "the figure does not exist", never "zero":
     *
     * <ul>
     * <li>no {@link ProjContext#database()}, so there is no authority metadata to read;</li>
     * <li>the pair needs no datum change at all, so there is no operation whose accuracy could be
     *     quoted;</li>
     * <li>the operation is ballpark, for which PROJ assigns no accuracy either &mdash; that absence
     *     is permanent and structural, not a gap.</li>
     * </ul>
     *
     * <p>Never {@code 0.0} and never estimated: "we do not know" and "sub-metre" are different
     * claims, and conflating them is how an unshifted datum comes to look like an exact one.
     *
     * @return the accuracy, or empty
     * @see Accuracy
     * @see #selectedOperation()
     */
    public Optional<Accuracy> accuracy() {
        return selected == null ? Optional.<Accuracy>empty() : selected.accuracy();
    }

    /**
     * The published operation this transformation was selected from.
     *
     * <p>{@code EPSG:1241}, "NAD27 to NAD83 (1)", 0.15&nbsp;m &mdash; the answer to the question the
     * consumer actually asked, which is not "did it work" but "which of the authority's nine
     * transformations am I getting". Empty when no authority database was configured, or when the
     * pair needs no datum change.
     *
     * @return the selected candidate, or empty
     * @see #candidates()
     */
    public Optional<CrsOperationCandidate> selectedOperation() {
        return Optional.ofNullable(selected);
    }

    /**
     * Every operation the authority publishes between these two CRSs, ranked best first, including
     * the ones that were rejected and why.
     *
     * <p>Empty without an authority database, because the legacy datum model synthesises exactly one
     * operation per CRS pair and there is nothing to enumerate. With one, this is nine entries plus a
     * synthesised ballpark for {@code EPSG:4267 -> EPSG:4269} &mdash; the offer that Proj4J
     * historically could not see.
     *
     * @return an unmodifiable list in ranking order; never null
     * @see Proj#candidateOperations(Crs, Crs)
     */
    public List<CrsOperationCandidate> candidates() {
        return candidates;
    }

    /**
     * The extent over which the authority declares the selected operation valid.
     *
     * <p>{@link AreaOfUse#isDatabaseDerived()} is {@code true} for this, unlike a bounding box a WKT
     * document asserted. Empty without a database, and empty for an operation whose extent publishes
     * no bounding box &mdash; 18 of the shipped database's extents do not, and they are reported as
     * absent rather than as the whole world.
     *
     * @return the area of use, or empty
     */
    public Optional<AreaOfUse> areaOfUse() {
        return selected == null ? Optional.<AreaOfUse>empty() : selected.areaOfUse();
    }

    /**
     * The grid files either CRS declares and no configured resolver can find.
     *
     * @return an unmodifiable list; never null, and empty is the good case
     */
    public List<GridInfo> missingGrids() {
        return missingGrids;
    }

    /**
     * Everything about this operation that a caller ought to know but that no coordinate will
     * reveal: unreachable grids, a fallback from a grid to a Helmert, a ballpark datum change that
     * was allowed, an axis order that had to be inferred.
     *
     * <p>Fixed at creation and never appended to, so reading it does not race with using the
     * operation.
     *
     * @return an unmodifiable list; never null, and empty is the good case
     */
    public List<String> warnings() {
        return warnings;
    }

    // ------------------------------------------------------------------------------ transforming

    /**
     * Transforms one coordinate.
     *
     * <p>{@code src} and {@code dst} may be the same object. Every meaningful ordinate of a
     * successful result is finite; see the class javadoc on fail-closed.
     *
     * @param src the input coordinate
     * @param dst the output coordinate, which is also the return value
     * @return {@code dst}
     * @throws CrsTransformException with a non-null {@link CrsTransformException#cause()} if this
     *                               coordinate cannot be transformed
     */
    public ProjCoordinate transform(ProjCoordinate src, ProjCoordinate dst) {
        return transform.transform(src, dst);
    }

    /**
     * Transforms one coordinate into a fresh {@link ProjCoordinate}.
     *
     * <p>Convenient, and allocating; {@link #bulk()} is the per-vertex path.
     *
     * @param src the input coordinate
     * @return a new coordinate in the target CRS
     * @throws CrsTransformException if this coordinate cannot be transformed
     */
    public ProjCoordinate transform(ProjCoordinate src) {
        return transform.transform(src, new ProjCoordinate());
    }

    /**
     * This same operation, typed as the allocation-free batch API.
     *
     * <p>The batch path transforms many points per call out of a caller-owned {@code double[]},
     * allocating <b>nothing</b> in steady state on any number of threads &mdash; measured at
     * 0.001&nbsp;B/op and verified bitwise identical to the single-point path. It is the same
     * object, so there is no second engine to keep in step and no second set of results to
     * reconcile.
     *
     * <pre>{@code
     * BulkCoordinateTransform bulk = op.bulk();
     * byte[] status = new byte[maxVertices];        // caller-owned, reused per geometry
     * if (bulk.transform2D(xy, 0, numVertices, 2, status) != 0) {
     *     return emptyGeometry();
     * }
     * }</pre>
     *
     * @return the bulk view of this operation; never null
     * @see BulkCoordinateTransform
     */
    public BulkCoordinateTransform bulk() {
        return transform;
    }

    /**
     * The reverse operation, target to source.
     *
     * <p><b>This is a CRS swap, not an analytic inverse.</b> It is built by asking for an operation
     * from {@link #target()} to {@link #source()} under the same context, so it is subject to the
     * same ballpark and grid checks and may throw for the same reasons &mdash; which is the correct
     * behaviour, and is why this is a method rather than a cached field.
     *
     * @return the reverse operation; never null
     * @throws CrsCreationException if the reverse direction is not one this context will vouch for
     */
    public CrsOperation inverse() {
        return Proj.createCrsToCrs(target, source, context);
    }

    /**
     * This operation as the legacy {@link CoordinateTransform} the 1.x API uses, for handing to code
     * that already implements or consumes that interface &mdash; {@code proj4j-geoapi}, GeoTools.
     *
     * <p>The returned object is the same engine, so its numbers are identical. What it does
     * <em>not</em> carry is this class's introspection: a caller holding only the interface cannot
     * ask whether a grid was missing. Keep this object too if that matters.
     *
     * @return the legacy transform; never null
     */
    public CoordinateTransform asLegacy() {
        return transform;
    }

    // ---------------------------------------------------------------------------------- describe

    /**
     * Everything this operation knows and everything it deliberately does not, as multi-line text
     * for a log or an error report.
     *
     * @return the description, newline-terminated; never null
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("CrsOperation ").append(source.name()).append(" -> ").append(target.name())
                .append('\n');
        sb.append("  ballpark        = ").append(ballpark);
        if (ballparkReason != null) {
            sb.append("   (").append(ballparkReason).append(')');
        }
        sb.append('\n');
        if (selected != null) {
            sb.append("  selected        = ").append(selected.authorityCode()).append("  ")
                    .append(selected.name()).append('\n');
            if (selected.isInverted()) {
                sb.append("                    INVERTED: the authority publishes this in the "
                        + "opposite direction\n");
            }
            if (selected.methodNote().isPresent()) {
                sb.append("                    ").append(selected.methodNote().get()).append('\n');
            }
        } else if (context.hasDatabase()) {
            sb.append("  selected        = <none> -- these CRSs share a datum, so there is no "
                    + "datum-change operation to select\n");
        } else {
            sb.append("  selected        = <none> -- no authority database is configured, so the "
                    + "legacy datum model's single synthesised operation was used. Set "
                    + "ProjContext.Builder.database(..) for real selection.\n");
        }
        if (accuracy().isPresent()) {
            sb.append("  accuracy        = ").append(accuracy().get()).append('\n');
        } else {
            sb.append("  accuracy        = <empty> -- ").append(context.hasDatabase()
                    ? "the selected operation carries no published accuracy, which for a ballpark "
                            + "operation is permanent; it is never reported as 0.0"
                    : "operation accuracy is authority metadata and no CRS database is configured; "
                            + "it is not estimated").append('\n');
        }
        if (areaOfUse().isPresent()) {
            sb.append("  areaOfUse       = ").append(areaOfUse().get()).append('\n');
        }
        if (!candidates.isEmpty()) {
            sb.append("  candidates      = ").append(candidates.size())
                    .append(" published by the authority, ranked best first\n");
            for (int i = 0; i < candidates.size(); i++) {
                sb.append("      ").append(candidates.get(i).describe()).append('\n');
            }
        }
        sb.append("  axisOrder       = source ").append(source.axisOrder())
                .append(", target ").append(target.axisOrder()).append('\n');
        sb.append("  domainError     = ").append(context.domainErrorPolicy()).append('\n');
        if (missingGrids.isEmpty()) {
            sb.append("  missing grids   = none\n");
        } else {
            sb.append("  missing grids   = ").append(missingGrids.size()).append('\n');
            for (int i = 0; i < missingGrids.size(); i++) {
                sb.append("      ").append(missingGrids.get(i).describe()).append('\n');
            }
        }
        if (warnings.isEmpty()) {
            sb.append("  warnings        = none\n");
        } else {
            sb.append("  warnings        = ").append(warnings.size()).append('\n');
            for (int i = 0; i < warnings.size(); i++) {
                sb.append("      ").append(warnings.get(i)).append('\n');
            }
        }
        sb.append("  thread safety   = this operation is immutable and shareable; the "
                + "ProjCoordinate arguments are not\n");
        sb.append('\n').append(source.describe()).append('\n').append(target.describe());
        return sb.toString();
    }

    @Override
    public String toString() {
        return "CrsOperation[" + source.name() + " -> " + target.name()
                + (ballpark ? ", BALLPARK" : "") + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CrsOperation)) {
            return false;
        }
        CrsOperation that = (CrsOperation) o;
        return source.equals(that.source) && target.equals(that.target)
                && context.equals(that.context);
    }

    @Override
    public int hashCode() {
        int h = source.hashCode();
        h = 31 * h + target.hashCode();
        return 31 * h + context.hashCode();
    }
}

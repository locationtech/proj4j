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

import org.locationtech.proj4j.spi.DbExtent;
import org.locationtech.proj4j.spi.DbObjectRef;
import org.locationtech.proj4j.spi.DbOperation;

/**
 * One coordinate operation the authority publishes between two CRSs, with everything needed to
 * decide whether it can be used and how it compares with the others.
 *
 * <p>Produced by {@link Proj#candidateOperations(Crs, Crs)}, which requires a
 * {@link org.locationtech.proj4j.spi.ProjDatabase} on the {@link ProjContext}: without one there is
 * nothing to enumerate, because the legacy engine synthesises exactly one operation per CRS pair
 * from its datum model.
 *
 * <h2>Why this type exists at all</h2>
 *
 * <p>{@code EPSG:4267} to {@code EPSG:4269} has <b>nine</b> published grid transformations, with
 * accuracies from 0.15&nbsp;m to 2.0&nbsp;m, and not one of them is ballpark. The historic defect was
 * never that the authority offered nothing; it was that Proj4J could not see the offer, so it applied
 * no shift and reported success &mdash; 95.573&nbsp;m at San Francisco, finite and plausible. This
 * class is the offer, made visible: which operations exist, which of them this deployment can
 * actually execute, which grid files each needs, and what each one claims for accuracy.
 *
 * <h2>Ranking is this library's policy, not the database's</h2>
 *
 * <p>{@link org.locationtech.proj4j.spi.ProjDatabase#operationsBetween} returns rows in
 * {@code (kind, authority, code)} order and <em>never</em> by accuracy: the database has no policy.
 * The ranking is {@link Proj#candidateOperations(Crs, Crs)}'s, it is a total order so that ties are
 * never left to chance, and it is documented on that method. {@link #rank()} is this candidate's
 * position in it, and {@link #rejectionReason()} says why a candidate that was not selected could
 * not be.
 *
 * <h2>Direction</h2>
 *
 * <p>The authority publishes an operation in exactly one direction. A candidate whose
 * {@link #isInverted()} is {@code true} was published the other way round and would have to be
 * executed inverted &mdash; which changes parameter signs, grid direction, and whether an inverse
 * exists at all. That fact is carried explicitly rather than folded into the operation, because
 * losing it is how a shift gets applied with the wrong sign: twice the error, still plausible.
 *
 * <p>Immutable and safe to share between threads.
 *
 * @see Proj#candidateOperations(Crs, Crs)
 * @see CrsOperation#selectedOperation()
 * @since 2.0.0
 */
public final class CrsOperationCandidate implements Comparable<CrsOperationCandidate> {

    /**
     * Why a candidate cannot be used by this deployment. {@link #NONE} is the good case, and the
     * order of the remaining constants is <b>not</b> the ranking order &mdash; see
     * {@link Proj#candidateOperations(Crs, Crs)} for that.
     */
    public enum Rejection {

        /** Usable: the method is implemented, every grid resolves, and it is not ballpark. */
        NONE,

        /**
         * The authority's method maps to a PROJ operator Proj4J does not implement &mdash;
         * {@code gridshift}, {@code xyzgridshift}, {@code tinshift}, {@code velocity_grid},
         * {@code defmodel}, a deformation pipeline needing a time dimension, or a concatenated
         * operation. Becomes {@link org.locationtech.proj4j.ErrorCause#UNSUPPORTED_OPERATION_METHOD}.
         */
        UNSUPPORTED_METHOD,

        /**
         * The method is implemented but at least one grid file it needs is not reachable through any
         * configured resolver. Becomes
         * {@link org.locationtech.proj4j.ErrorCause#BEST_OPERATION_UNAVAILABLE}, and
         * {@link #missingGrids()} names the files &mdash; <b>all</b> of them, including the second
         * grid of a NADCON {@code .las}/{@code .los} pair.
         */
        MISSING_GRID,

        /**
         * A datum change performed with no parameters and no stated accuracy, i.e. one that is not
         * actually performed. Becomes
         * {@link org.locationtech.proj4j.ErrorCause#BALLPARK_REJECTED} under
         * {@link BallparkPolicy#REJECT}.
         */
        BALLPARK,

        /** The authority has deprecated this operation. */
        DEPRECATED,

        /**
         * A {@code supersession} row names a replacement that connects the <em>same</em> CRS pair and
         * is itself a candidate here, so this one is not a substitute for anything.
         */
        SUPERSEDED
    }

    private final DbOperation operation;
    private final boolean inverted;
    private final boolean synthesisedBallpark;
    private final Accuracy accuracy;
    private final List<GridInfo> grids;
    private final AreaOfUse areaOfUse;
    private final Rejection rejection;
    private final String rejectionReason;
    private final String methodNote;
    private final int rank;

    CrsOperationCandidate(DbOperation operation, boolean inverted, boolean synthesisedBallpark,
                          Accuracy accuracy, List<GridInfo> grids, AreaOfUse areaOfUse,
                          Rejection rejection, String rejectionReason, String methodNote, int rank) {
        this.operation = operation;
        this.inverted = inverted;
        this.synthesisedBallpark = synthesisedBallpark;
        this.accuracy = accuracy;
        this.grids = Collections.unmodifiableList(grids);
        this.areaOfUse = areaOfUse;
        this.rejection = rejection;
        this.rejectionReason = rejectionReason;
        this.methodNote = methodNote;
        this.rank = rank;
    }

    /** A copy of this candidate with a rank assigned. Used once, after sorting. */
    CrsOperationCandidate withRank(int newRank) {
        return new CrsOperationCandidate(operation, inverted, synthesisedBallpark, accuracy,
                new ArrayList<GridInfo>(grids), areaOfUse, rejection, rejectionReason, methodNote,
                newRank);
    }

    // ------------------------------------------------------------------------------- identity

    /**
     * The authority's own row, verbatim and unconverted &mdash; method, parameters in the
     * authority's units, grid names as the authority spells them, steps.
     *
     * <p>For the one candidate Proj4J synthesises rather than reads (see
     * {@link #isSynthesisedBallpark()}) this is a stand-in row with authority {@code PROJ} and no
     * parameters, exactly as PROJ's own {@code projinfo} reports {@code unknown id} for it.
     *
     * @return the operation; never null
     */
    public DbOperation operation() {
        return operation;
    }

    /**
     * {@code "EPSG:1241"}.
     *
     * @return the authority-qualified code; never null
     */
    public String authorityCode() {
        return operation.authName() + ":" + operation.code();
    }

    /**
     * {@code "NAD27 to NAD83 (1)"} &mdash; the name a caller needs in order to know what was chosen,
     * and to look it up in the EPSG registry.
     *
     * @return the operation name; never null
     */
    public String name() {
        return operation.name();
    }

    /**
     * Whether this operation was published in the opposite direction and would have to be executed
     * inverted. See the class javadoc.
     *
     * @return true iff the authority publishes this as target-to-source
     */
    public boolean isInverted() {
        return inverted;
    }

    /**
     * Whether this candidate is the ballpark offset Proj4J synthesised because the datums differ and
     * the authority publishes no operation, rather than a row read from the database.
     *
     * <p>PROJ synthesises the same thing, and stores it no more than Proj4J does: there is not one
     * {@code Ballpark geographic offset} row anywhere in the shipped database. So a synthesised
     * ballpark is not a gap in the data; it is what "no published operation" looks like once it has
     * been made visible instead of silent.
     *
     * @return true iff this candidate was synthesised
     */
    public boolean isSynthesisedBallpark() {
        return synthesisedBallpark;
    }

    /**
     * Whether this is a ballpark transformation, i.e. a datum change with no parameters and no
     * stated accuracy.
     *
     * @return true iff ballpark
     */
    public boolean isBallpark() {
        return rejection == Rejection.BALLPARK || synthesisedBallpark;
    }

    // ------------------------------------------------------------------------------- quality

    /**
     * The accuracy the authority published, in metres.
     *
     * <p>Empty means the authority published none, which for a ballpark operation is permanent and
     * structural. It is never substituted with {@code 0.0} or an estimate: an invented accuracy is
     * exactly what lets a ballpark candidate win a ranking.
     *
     * @return the accuracy, or empty
     */
    public Optional<Accuracy> accuracy() {
        return Optional.ofNullable(accuracy);
    }

    /**
     * The extent over which the authority declares this operation valid, <b>database-derived</b>, so
     * {@link AreaOfUse#isDatabaseDerived()} is true.
     *
     * <p>When an operation declares several usages this is the smallest by
     * {@link org.locationtech.proj4j.spi.DbExtent#rankingArea()}, with ties broken on the extent
     * code, because that is the one used for ranking. Empty when the operation declares no usage or
     * when its extent publishes no bounding box &mdash; 18 upstream extents do not, and they are
     * reported as absent rather than as the whole world.
     *
     * @return the area of use, or empty
     */
    public Optional<AreaOfUse> areaOfUse() {
        return Optional.ofNullable(areaOfUse);
    }

    // ------------------------------------------------------------------------------- grids

    /**
     * Every grid file this operation needs, one entry per authority grid slot, in slot order.
     *
     * <p><b>Both slots.</b> NADCON splits the latitude and longitude shifts across a
     * {@code .las}/{@code .los} pair, and 150 of the 1,062 grid transformations in the shipped
     * database have a second grid. {@code EPSG:1241}, the most important transformation in the
     * consumer's workload, is one of them: it needs {@code conus.las} <em>and</em> {@code conus.los}.
     * A selector that reads only the first slot applies half the shift and reports success.
     *
     * <p>What a slot resolves <em>to</em> is a separate question, and the answer is often one file for
     * two slots: PROJ's {@code grid_alternatives} maps {@code conus.las} to the GeoTIFF
     * {@code us_noaa_conus.tif}, which carries both shifts, so the pair collapses. That collapse is
     * reported by {@link GridInfo#satisfiedBy()} rather than by dropping the second slot, so the
     * authority's requirement and this deployment's substitution are both visible.
     *
     * @return an unmodifiable list in slot order; never null, and empty for a parameterised operation
     */
    public List<GridInfo> grids() {
        return grids;
    }

    /**
     * The grid files this operation needs and no configured resolver can find.
     *
     * @return an unmodifiable list; never null, and empty is the good case
     */
    public List<GridInfo> missingGrids() {
        List<GridInfo> missing = new ArrayList<GridInfo>(grids.size());
        for (int i = 0; i < grids.size(); i++) {
            if (!grids.get(i).isAvailable()) {
                missing.add(grids.get(i));
            }
        }
        return Collections.unmodifiableList(missing);
    }

    // ------------------------------------------------------------------------------- usability

    /**
     * Whether this deployment can execute this operation right now.
     *
     * @return true iff {@link #rejection()} is {@link Rejection#NONE}
     */
    public boolean isUsable() {
        return rejection == Rejection.NONE;
    }

    /**
     * Why this candidate cannot be used, or {@link Rejection#NONE}.
     *
     * @return the rejection category; never null
     */
    public Rejection rejection() {
        return rejection;
    }

    /**
     * Why this candidate cannot be used, in words, naming the method or the files.
     *
     * @return the reason, or empty iff {@link #isUsable()}
     */
    public Optional<String> rejectionReason() {
        return Optional.ofNullable(rejectionReason);
    }

    /**
     * What the authority's method maps to here &mdash; the PROJ operator name for a grid operation,
     * or the reason there is no mapping.
     *
     * @return the note, or empty
     */
    public Optional<String> methodNote() {
        return Optional.ofNullable(methodNote);
    }

    /**
     * This candidate's position in the ranking, {@code 0} being best.
     *
     * @return a zero-based rank
     */
    public int rank() {
        return rank;
    }

    // ------------------------------------------------------------------------------- ordering

    /**
     * The total order described on {@link Proj#candidateOperations(Crs, Crs)}. Consistent with
     * {@link #equals(Object)} only in the sense that no two distinct candidates ever compare equal:
     * the final tiebreak is the authority reference and the direction, so the sort is stable
     * regardless of input order.
     *
     * @param other the candidate to compare against
     * @return a negative number if this candidate ranks better
     */
    @Override
    public int compareTo(CrsOperationCandidate other) {
        // 1. Usability tier. See usabilityPenalty() for the order and why it is that order.
        int c = Integer.compare(usabilityPenalty(), other.usabilityPenalty());
        if (c != 0) {
            return c;
        }
        // 2. Accuracy ascending; unknown after every known figure, never treated as zero.
        c = compareAccuracy(accuracy, other.accuracy);
        if (c != 0) {
            return c;
        }
        // 3. The tighter area of use, because a continental grid claiming the world is not more
        //    specific than a national one. No bounding box sorts last, never as the whole world.
        c = compareArea(areaOfUse, other.areaOfUse);
        if (c != 0) {
            return c;
        }
        // 4. The authority reference, which is a total order, then direction. Nothing is left tied,
        //    so the result cannot depend on the order the database returned rows in.
        c = ref().compareTo(other.ref());
        if (c != 0) {
            return c;
        }
        return Boolean.compare(inverted, other.inverted);
    }

    /**
     * The usability tier, lower being better. Ordered by <b>what a caller can do about it</b>, which
     * is the only ordering that makes a ranked list actionable:
     *
     * <ol start="0">
     * <li>{@link Rejection#NONE} &mdash; nothing to do.</li>
     * <li>{@link Rejection#MISSING_GRID} &mdash; add a file to the classpath and it works. The best
     *     kind of failure.</li>
     * <li>{@link Rejection#UNSUPPORTED_METHOD} &mdash; a capability boundary; nothing you add will
     *     change it.</li>
     * <li>{@link Rejection#BALLPARK} &mdash; executable, and useless: it applies no shift.</li>
     * <li>{@link Rejection#SUPERSEDED} &mdash; the authority has a better one for the same job.</li>
     * <li>{@link Rejection#DEPRECATED} &mdash; the authority has withdrawn it.</li>
     * </ol>
     *
     * <p>Note that this tier is <b>not</b> what {@link BestOperationPolicy#REQUIRE_BEST} compares. A
     * usable 2.0&nbsp;m operation ranks above an unavailable 0.15&nbsp;m one, because it is the one you
     * can have; whether choosing it is a <em>degradation</em> is a question about accuracy, answered
     * separately by {@link #isDegradedRelativeTo}. Conflating the two would either make the policy
     * unfireable or make the ranked list unreadable.
     */
    private int usabilityPenalty() {
        switch (rejection) {
            case NONE:
                return 0;
            case MISSING_GRID:
                return 1;
            case UNSUPPORTED_METHOD:
                return 2;
            case BALLPARK:
                return 3;
            case SUPERSEDED:
                return 4;
            default:
                return 5;
        }
    }

    private DbObjectRef ref() {
        return operation.ref();
    }

    private static int compareAccuracy(Accuracy a, Accuracy b) {
        if (a == null) {
            return b == null ? 0 : 1;
        }
        if (b == null) {
            return -1;
        }
        return Double.compare(a.metres(), b.metres());
    }

    private static int compareArea(AreaOfUse a, AreaOfUse b) {
        if (a == null) {
            return b == null ? 0 : 1;
        }
        if (b == null) {
            return -1;
        }
        return Double.compare(rankingArea(a), rankingArea(b));
    }

    private static double rankingArea(AreaOfUse a) {
        double lonSpan = a.crossesAntimeridian()
                ? 360.0 - (a.westLongitude() - a.eastLongitude())
                : a.eastLongitude() - a.westLongitude();
        return lonSpan * (a.northLatitude() - a.southLatitude());
    }

    /**
     * Whether this candidate is strictly less accurate than {@code other}, which is the only thing
     * {@link BestOperationPolicy#REQUIRE_BEST} refuses.
     *
     * <p>Deliberately <em>strictly</em>. Two operations tied on accuracy are not a degradation of one
     * another, and refusing a tie would make the default policy reject
     * {@code EPSG:4267 -> EPSG:4269} outright: {@code EPSG:1241} (NADCON, 0.15&nbsp;m, executable
     * here) and {@code EPSG:8555} (NADCON5, 0.15&nbsp;m, not executable here) are tied, and rejecting
     * both would be a worse answer than either. An unknown accuracy on this side against a known one
     * on the other <em>is</em> a degradation, because "we do not know" cannot be shown to be as good.
     *
     * @param other the better-ranked candidate to compare against
     * @return true iff choosing this one over {@code other} loses accuracy
     */
    /**
     * Whether this candidate has a strictly better published accuracy than {@code other}, ties broken
     * on the authority reference so the comparison is a total order.
     *
     * @param other the candidate to compare against
     * @return true iff this one should win an accuracy-only comparison
     */
    boolean isBetterAccuracyThan(CrsOperationCandidate other) {
        int c = compareAccuracy(accuracy, other.accuracy);
        if (c != 0) {
            return c < 0;
        }
        return ref().compareTo(other.ref()) < 0;
    }

    boolean isDegradedRelativeTo(CrsOperationCandidate other) {
        if (other == null) {
            return false;
        }
        if (accuracy == null) {
            return other.accuracy != null;
        }
        if (other.accuracy == null) {
            return false;
        }
        return accuracy.metres() > other.accuracy.metres();
    }

    // ------------------------------------------------------------------------------- describe

    /**
     * One line: code, name, accuracy, direction, usability, and the files.
     *
     * @return the description, without a trailing newline; never null
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append('#').append(rank).append(' ').append(authorityCode()).append(", ").append(name());
        sb.append(", ").append(accuracy == null ? "accuracy unknown" : accuracy.metres() + " m");
        if (inverted) {
            sb.append(", INVERTED (published as ")
                    .append(operation.sourceCrs() == null ? "?" : operation.sourceCrs().authorityCode())
                    .append(" -> ")
                    .append(operation.targetCrs() == null ? "?" : operation.targetCrs().authorityCode())
                    .append(')');
        }
        if (methodNote != null) {
            sb.append(", ").append(methodNote);
        }
        if (areaOfUse != null && areaOfUse.description() != null) {
            sb.append(", ").append(areaOfUse.description());
        }
        sb.append(isUsable() ? ", USABLE" : ", " + rejection + ": " + rejectionReason);
        if (!grids.isEmpty()) {
            sb.append("\n        grids required by the authority (").append(grids.size())
                    .append(") --");
            for (int i = 0; i < grids.size(); i++) {
                sb.append("\n          slot ").append(i + 1).append(": ")
                        .append(grids.get(i).describe());
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "CrsOperationCandidate[" + authorityCode() + ", " + name()
                + (accuracy == null ? ", accuracy unknown" : ", " + accuracy.metres() + " m")
                + (inverted ? ", inverted" : "")
                + (isUsable() ? "" : ", " + rejection) + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CrsOperationCandidate)) {
            return false;
        }
        CrsOperationCandidate that = (CrsOperationCandidate) o;
        return inverted == that.inverted && ref().equals(that.ref());
    }

    @Override
    public int hashCode() {
        return 31 * ref().hashCode() + (inverted ? 1 : 0);
    }

    /**
     * The smallest extent an operation declares, by ranking area, ties broken on the extent code so
     * the choice is deterministic. Package-private: {@link OperationSelector} builds candidates.
     *
     * @param extents the extents the database returned, in its own order
     * @return the chosen extent, or null if none has a bounding box
     */
    static DbExtent smallestExtent(List<DbExtent> extents) {
        DbExtent best = null;
        for (int i = 0; i < extents.size(); i++) {
            DbExtent e = extents.get(i);
            if (!e.hasBoundingBox()) {
                continue;
            }
            if (best == null) {
                best = e;
                continue;
            }
            int c = Double.compare(e.rankingArea(), best.rankingArea());
            if (c < 0 || (c == 0 && e.ref().compareTo(best.ref()) < 0)) {
                best = e;
            }
        }
        return best;
    }
}

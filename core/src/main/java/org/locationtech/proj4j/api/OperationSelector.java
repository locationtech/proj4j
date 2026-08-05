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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.spi.DbCrs;
import org.locationtech.proj4j.spi.DbExtent;
import org.locationtech.proj4j.spi.DbGridAlternative;
import org.locationtech.proj4j.spi.DbObjectRef;
import org.locationtech.proj4j.spi.DbObjectType;
import org.locationtech.proj4j.spi.DbOperation;
import org.locationtech.proj4j.spi.DbParam;
import org.locationtech.proj4j.spi.DbSupersession;
import org.locationtech.proj4j.spi.ProjDatabase;

/**
 * Real coordinate-operation selection: enumerate what the authority published between two CRSs, rank
 * it, apply {@link BallparkPolicy}, {@link GridPolicy} and {@link BestOperationPolicy}, and either
 * choose one or say exactly why none can be chosen.
 *
 * <p>Package-private and stateless. Every method is a pure function of its arguments and of the
 * resolver chain, so two executors given the same database and the same classpath reach the same
 * answer &mdash; which is the whole reason the ordering below is total.
 *
 * <h2>The split with {@code spi}</h2>
 *
 * <p>{@link ProjDatabase} reports what the authority published, in {@code (kind, authority, code)}
 * order and <b>never</b> by accuracy. It has no policy. Everything here &mdash; the ranking, the
 * three policies, and which {@link ErrorCause} to throw &mdash; is policy, and lives here so that it
 * is testable against a handful of transcribed rows rather than against 6.7&nbsp;MB of data.
 *
 * @see Proj#candidateOperations(Crs, Crs)
 */
final class OperationSelector {

    /**
     * PROJ's three synthesised no-parameter datum changes, from
     * {@code 9.8.1:src/iso19111/operation/oputils.cpp:61-66}. PROJ matches them by name prefix and so
     * does this, because that is the only handle there is: <b>not one of them is stored as a row</b>
     * in the shipped database, which is why the ballpark candidate below has to be synthesised.
     */
    private static final String[] BALLPARK_NAME_PREFIXES = {
        "Ballpark geocentric translation",
        "Ballpark geographic offset",
        "Ballpark vertical transformation",
    };

    /**
     * The PROJ grid operators this library can actually apply to a two-dimensional CRS-to-CRS datum
     * change. Sorted, for a binary search and so a reader can see the whole claim at a glance.
     *
     * <p>Exactly one entry, and that is not an oversight. {@code hgridshift} is what a NADCON, NTv1 or
     * NTv2 horizontal shift becomes, and all four grid readers &mdash; CTABLE V2, NTv1, NTv2 and
     * GeoTIFF &mdash; feed it. The absent ones are absent for different reasons, and
     * {@link #unsupportedMethodReason} states which, per operator, rather than reporting one
     * undifferentiated failure.
     */
    private static final String[] EXECUTABLE_GRID_OPERATORS = {
        "hgridshift",
    };

    /**
     * EPSG parameter codes of the time-dependent terms of a Helmert transformation: the six rates,
     * the scale rate, and the reference epoch. Their presence means the operation needs a coordinate
     * epoch, and a coordinate with no time cannot be transformed by one &mdash; which is
     * {@link ErrorCause#MISSING_TIME}, not a number that is merely a bit off.
     */
    private static final String[] TIME_DEPENDENT_PARAM_CODES = {
        "1040", // Transformation reference epoch
        "1041", "1042", "1043", // rates of translation
        "1044", "1045", "1046", // rates of rotation
        "1047", // rate of scale difference
    };

    private OperationSelector() {
    }

    // ------------------------------------------------------------------ the result

    /**
     * What selection concluded. Either a candidate was chosen, or a cause and a message say why not,
     * or {@link #databaseCannotSeeThisPair} is true and the caller must fall back to the legacy
     * datum model.
     */
    static final class Selection {

        static final Selection UNRESOLVED = new Selection(true, false, null,
                Collections.<CrsOperationCandidate>emptyList(),
                Collections.<String>emptyList(), null, null);

        private final boolean databaseCannotSeeThisPair;
        private final boolean noDatumChange;
        private final CrsOperationCandidate selected;
        private final List<CrsOperationCandidate> candidates;
        private final List<String> warnings;
        private final ErrorCause failureCause;
        private final String failureMessage;

        private Selection(boolean databaseCannotSeeThisPair, boolean noDatumChange,
                          CrsOperationCandidate selected, List<CrsOperationCandidate> candidates,
                          List<String> warnings, ErrorCause failureCause, String failureMessage) {
            this.databaseCannotSeeThisPair = databaseCannotSeeThisPair;
            this.noDatumChange = noDatumChange;
            this.selected = selected;
            this.candidates = Collections.unmodifiableList(candidates);
            this.warnings = Collections.unmodifiableList(warnings);
            this.failureCause = failureCause;
            this.failureMessage = failureMessage;
        }

        boolean databaseCannotSeeThisPair() {
            return databaseCannotSeeThisPair;
        }

        boolean noDatumChange() {
            return noDatumChange;
        }

        CrsOperationCandidate selected() {
            return selected;
        }

        List<CrsOperationCandidate> candidates() {
            return candidates;
        }

        List<String> warnings() {
            return warnings;
        }

        ErrorCause failureCause() {
            return failureCause;
        }

        String failureMessage() {
            return failureMessage;
        }
    }

    // ------------------------------------------------------------------ CRS resolution

    /**
     * The authority reference of a {@link Crs}, or null when there is nothing to look up.
     *
     * <p>Resolved from what the caller actually stated, and never guessed:
     * <ul>
     * <li>an {@code authority:code} name, split on the last colon;</li>
     * <li>a WKT or PROJJSON {@code ID[]} / {@code AUTHORITY[]} clause;</li>
     * <li>a PROJ string's {@code +datum=}, through PROJ's own ten-entry table (see
     *     {@link #geographicCrsForProjDatum}).</li>
     * </ul>
     * A PROJ string that merely happens to be equivalent to an EPSG code is <b>not</b> resolved to
     * it: deciding that needs a comparison this class will not make, and getting it wrong attributes
     * an authority's accuracy figure to a caller's parameters.
     */
    static DbObjectRef referenceFor(ProjDatabase db, Crs crs) {
        List<String> ids = new ArrayList<String>(crs.identifiers());
        if (crs.source() == Crs.Source.PROJ_STRING || crs.source() == Crs.Source.LEGACY_OBJECT) {
            String datum = crs.datumCode().isPresent() ? crs.datumCode().get() : null;
            String code = geographicCrsForProjDatum(datum);
            if (code != null) {
                ids.add("EPSG:" + code);
            }
        }
        for (int i = 0; i < ids.size(); i++) {
            DbObjectRef ref = resolveOne(db, ids.get(i));
            if (ref != null) {
                return ref;
            }
        }
        return null;
    }

    private static DbObjectRef resolveOne(ProjDatabase db, String identifier) {
        if (identifier == null) {
            return null;
        }
        int colon = identifier.lastIndexOf(':');
        if (colon <= 0 || colon == identifier.length() - 1) {
            return null;
        }
        String auth = identifier.substring(0, colon).trim();
        String code = identifier.substring(colon + 1).trim();
        if (auth.isEmpty() || code.isEmpty()) {
            return null;
        }
        DbCrs found = db.crs(auth, code);
        return found == null ? null : found.ref();
    }

    /**
     * PROJ's {@code +datum=} name to EPSG geographic-CRS code table, transcribed verbatim from
     * {@code 9.8.1:src/iso19111/io.cpp} &mdash; the three special cases at {@code :11130-11136} and the
     * seven-row {@code datumDescs[]} at {@code :10931-10944}. Ten names, which is all of them.
     *
     * <p>This is the bridge that makes the <b>1,962 lines of the shipped legacy dictionaries carrying a
     * {@code datum=}</b> visible to the authority database, and it is why {@code +datum=OSGB36} can
     * reach {@code EPSG:4277} and from there {@code EPSG:7710} "OSGB36 to WGS 84 (9)" at 1.0&nbsp;m
     * rather than the parameterised Helmert it is 1.784&nbsp;m away from.
     *
     * @param projDatumName the {@code +datum=} value, case-sensitive as PROJ compares it
     * @return the EPSG geographic 2D CRS code, or null for a name PROJ does not map either
     */
    static String geographicCrsForProjDatum(String projDatumName) {
        if (projDatumName == null) {
            return null;
        }
        if ("WGS84".equals(projDatumName)) {
            return "4326";
        }
        if ("NAD83".equals(projDatumName)) {
            return "4269";
        }
        if ("NAD27".equals(projDatumName)) {
            return "4267";
        }
        if ("GGRS87".equals(projDatumName)) {
            return "4121";
        }
        if ("potsdam".equals(projDatumName)) {
            return "4314";
        }
        if ("carthage".equals(projDatumName)) {
            return "4223";
        }
        if ("hermannskogel".equals(projDatumName)) {
            return "4312";
        }
        if ("ire65".equals(projDatumName)) {
            return "4299";
        }
        if ("nzgd49".equals(projDatumName)) {
            return "4272";
        }
        if ("OSGB36".equals(projDatumName)) {
            return "4277";
        }
        return null;
    }

    /**
     * The geodetic CRS a datum change actually runs between: a projected CRS's base, or the CRS
     * itself.
     *
     * <p>The authority publishes transformations between <em>geodetic</em> CRSs, so
     * {@code EPSG:27700 -> EPSG:4326} is really {@code EPSG:4277 -> EPSG:4326} with a map projection
     * on one end. Returns null for a compound, vertical or engineering CRS, where this two-dimensional
     * facade has nothing to say and says nothing.
     */
    private static DbObjectRef geodeticBase(ProjDatabase db, DbObjectRef ref) {
        DbCrs crs = db.crs(ref.authName(), ref.code());
        if (crs == null) {
            return null;
        }
        switch (crs.type()) {
            case GEOGRAPHIC_2D:
            case GEOGRAPHIC_3D:
            case GEOCENTRIC:
            case GEODETIC_OTHER:
                return crs.ref();
            case PROJECTED:
                return crs.baseCrs();
            default:
                return null;
        }
    }

    private static DbObjectRef datumOf(ProjDatabase db, DbObjectRef geodeticRef) {
        DbCrs crs = db.crs(geodeticRef.authName(), geodeticRef.code());
        return crs == null ? null : crs.datum();
    }

    // ------------------------------------------------------------------ selection

    /**
     * Enumerates, ranks and chooses.
     *
     * @param db      the database; never null
     * @param source  the source CRS
     * @param target  the target CRS
     * @param context the governing policies
     * @return the selection; {@link Selection#UNRESOLVED} when the database cannot see this pair, in
     *         which case the caller must fall back to the legacy datum model rather than pretend
     */
    static Selection select(ProjDatabase db, Crs source, Crs target, ProjContext context) {
        DbObjectRef srcRef = referenceFor(db, source);
        DbObjectRef tgtRef = referenceFor(db, target);
        if (srcRef == null || tgtRef == null) {
            return Selection.UNRESOLVED;
        }
        DbObjectRef srcBase = geodeticBase(db, srcRef);
        DbObjectRef tgtBase = geodeticBase(db, tgtRef);
        if (srcBase == null || tgtBase == null) {
            return Selection.UNRESOLVED;
        }

        DbObjectRef srcDatum = datumOf(db, srcBase);
        DbObjectRef tgtDatum = datumOf(db, tgtBase);
        if (srcDatum != null && srcDatum.equals(tgtDatum)) {
            // Same datum: EPSG:4326 -> EPSG:32633 is a conversion, not a transformation, and there is
            // no datum change to select an operation for. Reporting NO_OPERATION_AVAILABLE here would
            // break the single most common transformation in the world.
            return new Selection(false, true, null,
                    Collections.<CrsOperationCandidate>emptyList(),
                    Collections.<String>emptyList(), null, null);
        }

        List<CrsOperationCandidate> candidates = enumerate(db, srcBase, tgtBase, srcDatum, tgtDatum);
        return decide(candidates, context, srcBase, tgtBase);
    }

    private static String describe(DbObjectRef datum) {
        return datum == null ? "unknown" : datum.authorityCode();
    }

    // ------------------------------------------------------------------ enumeration

    /**
     * Every candidate, ranked, with {@link CrsOperationCandidate#rank()} assigned.
     *
     * <p>Both directions, from two calls to
     * {@link ProjDatabase#operationsBetween(String, String, String, String)} with the arguments
     * swapped &mdash; because the SPI returns only the stored direction, deliberately, and merging
     * them there would make "is this candidate inverted?" implicit.
     */
    private static List<CrsOperationCandidate> enumerate(ProjDatabase db, DbObjectRef srcBase,
                                                         DbObjectRef tgtBase, DbObjectRef srcDatum,
                                                         DbObjectRef tgtDatum) {
        List<DbOperation> forward = db.operationsBetween(srcBase.authName(), srcBase.code(),
                tgtBase.authName(), tgtBase.code());
        List<DbOperation> reverse = db.operationsBetween(tgtBase.authName(), tgtBase.code(),
                srcBase.authName(), srcBase.code());

        List<DbObjectRef> present = new ArrayList<DbObjectRef>(forward.size() + reverse.size());
        for (int i = 0; i < forward.size(); i++) {
            present.add(forward.get(i).ref());
        }
        for (int i = 0; i < reverse.size(); i++) {
            present.add(reverse.get(i).ref());
        }

        List<CrsOperationCandidate> out =
                new ArrayList<CrsOperationCandidate>(forward.size() + reverse.size() + 1);
        for (int i = 0; i < forward.size(); i++) {
            out.add(build(db, forward.get(i), false, present));
        }
        for (int i = 0; i < reverse.size(); i++) {
            out.add(build(db, reverse.get(i), true, present));
        }
        out.add(ballpark(srcBase, tgtBase, srcDatum, tgtDatum));

        Collections.sort(out);
        List<CrsOperationCandidate> ranked = new ArrayList<CrsOperationCandidate>(out.size());
        for (int i = 0; i < out.size(); i++) {
            ranked.add(out.get(i).withRank(i));
        }
        return ranked;
    }

    /**
     * The ballpark candidate PROJ synthesises and stores nowhere. Ranked last by construction, since
     * {@link CrsOperationCandidate#compareTo} puts ballpark below everything executable, and present so
     * that {@link BallparkPolicy#ALLOW} means something with a database and so that the candidate count
     * matches {@code projinfo}'s.
     */
    private static CrsOperationCandidate ballpark(DbObjectRef srcBase, DbObjectRef tgtBase,
                                                  DbObjectRef srcDatum, DbObjectRef tgtDatum) {
        String name = "Ballpark geographic offset from " + describe(srcDatum) + " to "
                + describe(tgtDatum);
        DbOperation synthetic = new DbOperation(DbObjectType.OTHER_TRANSFORMATION, "PROJ",
                "BALLPARK_" + srcBase.code() + "_TO_" + tgtBase.code(), name, "PROJ", "PROJString",
                "+proj=noop", srcBase, tgtBase, Double.NaN, null, null, null, null, null, false);
        return new CrsOperationCandidate(synthetic, false, true, null,
                new ArrayList<GridInfo>(0), null, CrsOperationCandidate.Rejection.BALLPARK,
                "a ballpark offset applies no datum shift at all. The coordinate it produces is out "
                        + "by the size of the shift -- tens to hundreds of metres -- and is finite, "
                        + "in the right units and in the right part of the world, so nothing "
                        + "downstream can detect it. PROJ synthesises the same operation and assigns "
                        + "it no accuracy, which is the authority saying this is a guess.",
                "synthesised, not published: the shipped database contains no ballpark row at all",
                0);
    }

    /** Turns one authority row into a candidate: accuracy, grids, extent, and why it can or cannot run. */
    private static CrsOperationCandidate build(ProjDatabase db, DbOperation op, boolean inverted,
                                               List<DbObjectRef> siblings) {
        Accuracy accuracy = op.hasAccuracy()
                ? new Accuracy(op.accuracy(), op.authName() + ":" + op.code())
                : null;

        List<GridInfo> grids = resolveGrids(db, op);

        DbExtent extent = CrsOperationCandidate.smallestExtent(db.extentsFor(op.ref()));
        AreaOfUse area = AreaOfUse.fromDbExtent(extent);

        String methodNote = methodNote(op, grids);

        CrsOperationCandidate.Rejection rejection = CrsOperationCandidate.Rejection.NONE;
        String reason = null;

        if (op.deprecated()) {
            rejection = CrsOperationCandidate.Rejection.DEPRECATED;
            reason = "the authority has deprecated this operation";
        }
        if (rejection == CrsOperationCandidate.Rejection.NONE) {
            DbObjectRef replacement = supersedingSibling(db, op, siblings);
            if (replacement != null) {
                rejection = CrsOperationCandidate.Rejection.SUPERSEDED;
                reason = "superseded by " + replacement.authorityCode()
                        + ", which connects the same CRS pair and is also a candidate here";
            }
        }
        if (rejection == CrsOperationCandidate.Rejection.NONE && isBallparkByName(op.name())) {
            rejection = CrsOperationCandidate.Rejection.BALLPARK;
            reason = "the authority's own name marks this a ballpark operation, so no datum shift is "
                    + "actually applied";
        }
        if (rejection == CrsOperationCandidate.Rejection.NONE) {
            String unsupported = unsupportedMethodReason(op, grids, inverted);
            if (unsupported != null) {
                rejection = CrsOperationCandidate.Rejection.UNSUPPORTED_METHOD;
                reason = unsupported;
            }
        }
        if (rejection == CrsOperationCandidate.Rejection.NONE) {
            StringBuilder missing = null;
            for (int i = 0; i < grids.size(); i++) {
                if (!grids.get(i).isAvailable()) {
                    if (missing == null) {
                        missing = new StringBuilder();
                    } else {
                        missing.append(" and ");
                    }
                    missing.append(grids.get(i).name());
                }
            }
            if (missing != null) {
                rejection = CrsOperationCandidate.Rejection.MISSING_GRID;
                reason = "the grid file" + (grids.size() > 1 ? "s " : " ") + missing
                        + " cannot be found by any configured resolver";
            }
        }
        return new CrsOperationCandidate(op, inverted, false, accuracy, grids, area, rejection,
                reason, methodNote, 0);
    }

    /**
     * One {@link GridInfo} per authority grid slot, <b>including the second</b>, with PROJ's
     * {@code .las}/{@code .los} collapse reported rather than performed silently.
     */
    private static List<GridInfo> resolveGrids(ProjDatabase db, DbOperation op) {
        List<String> names = op.gridNames();
        if (names.isEmpty()) {
            return Collections.emptyList();
        }
        List<GridInfo> out = new ArrayList<GridInfo>(names.size());
        DbGridAlternative firstAlt = db.gridAlternative(names.get(0));
        GridInfo firstSlot = GridInfo.forDbGrid(names.get(0), 1, firstAlt,
                op.authName() + ":" + op.code() + " grid_name");
        out.add(firstSlot);

        // PROJ looks up grid_alternatives for the LATITUDE file alone and, when the modern form is a
        // GeoTIFF, replaces the whole .las/.los pair with that one file. Note the collapse is a
        // property of the pair, not of a policy: conus.los has no grid_alternatives row of its own --
        // only 1 of the 85 distinct grid2_names does -- so resolving slot 2 independently would report
        // every NADCON operation unusable.
        boolean collapsesOntoFirst = firstAlt != null && "GTiff".equals(firstAlt.projGridFormat());
        for (int i = 1; i < names.size(); i++) {
            String declaredBy = op.authName() + ":" + op.code() + " grid" + (i + 1) + "_name";
            DbGridAlternative alt = db.gridAlternative(names.get(i));
            if (collapsesOntoFirst) {
                out.add(GridInfo.sharedWithEarlierSlot(firstSlot, names.get(i), i + 1, alt,
                        declaredBy, "one file carries both the latitude and the longitude shift, so "
                                + "it satisfies both authority slots "
                                + "(9.8.1:src/iso19111/operation/singleoperation.cpp, the "
                                + "projGridFormat == \"GTiff\" branch of "
                                + "substitutePROJAlternativeGridNames)"));
            } else {
                out.add(GridInfo.forDbGrid(names.get(i), i + 1, alt, declaredBy));
            }
        }
        return out;
    }

    private static DbObjectRef supersedingSibling(ProjDatabase db, DbOperation op,
                                                  List<DbObjectRef> siblings) {
        List<DbSupersession> rows = db.supersededBy(op.ref());
        for (int i = 0; i < rows.size(); i++) {
            DbSupersession row = rows.get(i);
            // sameSourceTargetCrs is the whole point: a replacement connecting a different CRS pair
            // is not a substitute for this operation and must not knock it out of the ranking.
            if (row.sameSourceTargetCrs() && siblings.contains(row.replacement())) {
                return row.replacement();
            }
        }
        return null;
    }

    static boolean isBallparkByName(String name) {
        if (name == null) {
            return false;
        }
        for (int i = 0; i < BALLPARK_NAME_PREFIXES.length; i++) {
            if (name.startsWith(BALLPARK_NAME_PREFIXES[i])) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ capability

    private static String methodNote(DbOperation op, List<GridInfo> grids) {
        if (op.kind() == DbObjectType.CONCATENATED_OPERATION) {
            return "concatenated operation, " + op.steps().size() + " step(s)";
        }
        if (op.isProjStringMethod()) {
            return "method is a literal PROJ pipeline: " + op.methodName();
        }
        if (op.isWktMethod()) {
            return "method is a literal WKT CoordinateOperation";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("method ").append(op.methodAuthName()).append(':').append(op.methodCode())
                .append(' ').append(op.methodName());
        for (int i = 0; i < grids.size(); i++) {
            if (grids.get(i).projMethod().isPresent()) {
                sb.append(" -> +proj=").append(grids.get(i).projMethod().get());
                break;
            }
        }
        return sb.toString();
    }

    /**
     * Why this library cannot execute an operation, or null if it can.
     *
     * <p>Per-operator rather than one undifferentiated failure, because "Proj4J has not implemented
     * the unified {@code gridshift} operator" and "this operation needs a coordinate epoch" lead a
     * caller to entirely different actions.
     */
    private static String unsupportedMethodReason(DbOperation op, List<GridInfo> grids,
                                                  boolean inverted) {
        switch (op.kind()) {
            case CONCATENATED_OPERATION:
                return "a concatenated operation of " + op.steps().size() + " steps. Proj4J can see "
                        + "and rank it but not yet execute it: the facade builds a single "
                        + "transformation, and chaining authority steps -- each with its own "
                        + "direction, and note that step numbers are not indices, two upstream "
                        + "operations number their steps 2 and 3 with no step 1 -- belongs to the "
                        + "pipeline engine.";
            case HELMERT_TRANSFORMATION:
                return helmertReason(op);
            case GRID_TRANSFORMATION:
                return gridReason(op, grids, inverted);
            case OTHER_TRANSFORMATION:
                return otherReason(op);
            default:
                return "unrecognised operation kind " + op.kind();
        }
    }

    private static String helmertReason(DbOperation op) {
        List<DbParam> params = op.parameters();
        for (int i = 0; i < params.size(); i++) {
            String code = params.get(i).code();
            if (Arrays.asList(TIME_DEPENDENT_PARAM_CODES).contains(code)) {
                return "a time-dependent Helmert transformation: parameter " + params.get(i).authName()
                        + ":" + code + " (" + params.get(i).name() + ") makes the result a function "
                        + "of the coordinate's epoch, and this facade's coordinates carry no time. "
                        + "Executing it with an assumed epoch would produce a plausible coordinate "
                        + "wrong by the accumulated plate motion.";
            }
        }
        return null;
    }

    private static String gridReason(DbOperation op, List<GridInfo> grids, boolean inverted) {
        if (grids.isEmpty()) {
            return "a grid transformation that names no grid file";
        }
        GridInfo first = grids.get(0);
        if (!first.projMethod().isPresent()) {
            return "the authority grid " + first.name() + " has no grid_alternatives row, so there "
                    + "is no PROJ operator and no modern file name to map it to. Proj4J will not "
                    + "guess which operator a grid feeds from its file extension.";
        }
        String operator = first.projMethod().get();
        if (Arrays.binarySearch(EXECUTABLE_GRID_OPERATORS, operator) < 0) {
            return "the grid feeds PROJ's +proj=" + operator + " operator, which this library does "
                    + "not apply to a two-dimensional CRS-to-CRS datum change" + operatorNote(operator)
                    + ".";
        }
        if (inverted && first.isInverseDirection()) {
            return "this operation would have to be run inverted, and grid_alternatives already "
                    + "declares " + first.name() + "'s PROJ form to run in the inverse direction. "
                    + "Composing the two inversions is exactly where a shift gets applied with the "
                    + "wrong sign -- twice the error, still plausible -- so it is refused rather "
                    + "than attempted.";
        }
        return null;
    }

    private static String operatorNote(String operator) {
        if ("gridshift".equals(operator)) {
            return " (the unified NADCON 5 / general-shift operator; only +proj=hgridshift is "
                    + "implemented)";
        }
        if ("xyzgridshift".equals(operator) || "geocentricoffset".equals(operator)) {
            return " (a geocentric shift by grid; not implemented)";
        }
        if ("vgridshift".equals(operator) || "geoid_like".equals(operator)) {
            return " (a vertical shift. Proj4J does have a vertical grid operator, but a Crs here is "
                    + "two-dimensional by construction -- see Proj.createCrs on compound CRSs -- so "
                    + "there is no height for it to shift)";
        }
        if ("velocity_grid".equals(operator) || "defmodel".equals(operator)) {
            return " (a deformation model; it needs a coordinate epoch, and this facade's "
                    + "coordinates carry no time)";
        }
        if ("tinshift".equals(operator)) {
            return " (triangulated shift; the operator exists in the pipeline engine but is not "
                    + "reachable from this facade)";
        }
        return "";
    }

    private static String otherReason(DbOperation op) {
        if (op.isProjStringMethod()) {
            String pipeline = op.methodName() == null ? "" : op.methodName();
            if (pipeline.contains("+proj=noop")) {
                return null;
            }
            return "the authority expresses this operation as a literal PROJ pipeline, which this "
                    + "facade does not build: " + pipeline + ". This is how all 19 NKG "
                    + "other_transformation rows are written, and finishing them needs +proj="
                    + "deformation plus a time dimension on the coordinate, not more data.";
        }
        if (op.isWktMethod()) {
            return "the authority expresses this operation as a literal WKT CoordinateOperation, "
                    + "which this facade does not build.";
        }
        return "the authority method " + op.methodAuthName() + ":" + op.methodCode() + " "
                + op.methodName() + " is not one this library implements.";
    }

    // ------------------------------------------------------------------ decision

    /**
     * Applies the three policies to a ranked list. This is the decision table from the architecture
     * note, made executable.
     */
    private static Selection decide(List<CrsOperationCandidate> candidates, ProjContext context,
                                    DbObjectRef srcBase, DbObjectRef tgtBase) {
        List<String> warnings = new ArrayList<String>();

        CrsOperationCandidate usable = null;
        CrsOperationCandidate bestRejectedForGrid = null;
        CrsOperationCandidate bestRejectedForMethod = null;
        CrsOperationCandidate ballparkCandidate = null;

        for (int i = 0; i < candidates.size(); i++) {
            CrsOperationCandidate c = candidates.get(i);
            switch (c.rejection()) {
                case NONE:
                    if (usable == null) {
                        usable = c;
                    }
                    break;
                case MISSING_GRID:
                    if (bestRejectedForGrid == null) {
                        bestRejectedForGrid = c;
                    }
                    break;
                case UNSUPPORTED_METHOD:
                    if (bestRejectedForMethod == null) {
                        bestRejectedForMethod = c;
                    }
                    break;
                case BALLPARK:
                    if (ballparkCandidate == null) {
                        ballparkCandidate = c;
                    }
                    break;
                default:
                    break;
            }
        }

        // GridPolicy.WARN / PROJ4_COMPAT: a missing grid stops being disqualifying. It is still
        // reported -- the introspection channel is never switched off, whatever the policy.
        if (usable == null && bestRejectedForGrid != null
                && context.gridPolicy() != GridPolicy.REQUIRE_ALL) {
            usable = bestRejectedForGrid;
            bestRejectedForGrid = null;
            for (int i = 0; i < usable.missingGrids().size(); i++) {
                warnings.add("GridPolicy." + context.gridPolicy() + ": proceeding with "
                        + usable.authorityCode() + " although " + usable.missingGrids().get(i).name()
                        + " is unreachable, so that part of the shift will not be applied: "
                        + usable.missingGrids().get(i).describe());
            }
        }

        if (usable != null) {
            // Deliberately the most accurate REJECTED candidate, not the highest-ranked one. Rank
            // puts every usable candidate above every unusable one, which is right for a list a human
            // reads and useless for the degradation question -- so that question is asked directly.
            CrsOperationCandidate better = mostAccurateRejected(candidates);
            if (better != null) {
                if (usable.isDegradedRelativeTo(better)
                        && context.bestOperationPolicy() == BestOperationPolicy.REQUIRE_BEST) {
                    return failure(candidates, warnings, ErrorCause.BEST_OPERATION_UNAVAILABLE,
                            degradedMessage(usable, better, srcBase, tgtBase, context));
                }
                warnings.add(betterButUnavailableNote(usable, better, context));
            }
            return new Selection(false, false, usable, candidates, warnings, null, null);
        }

        // Nothing usable. There are three reasons that can be true of, and they lead a caller to three
        // entirely different actions -- add a file / there is nothing you can add / the authority
        // publishes nothing at all -- so they are three different ErrorCauses and not one.
        //
        // A real operation exists and only its file or its operator is missing. That is a strictly
        // more informative failure than "ballpark", so it wins even when BallparkPolicy.ALLOW would
        // have accepted the ballpark -- UNLESS the caller has ALSO said, through
        // BestOperationPolicy.ALLOW_DEGRADED, that it accepts losing accuracy. Dropping from a
        // published 0.15 m to an unbounded offset is the largest degradation there is, so
        // REQUIRE_BEST refusing it is the same rule as everywhere else, not a special case.
        boolean mayDegradeToBallpark = context.ballparkPolicy() == BallparkPolicy.ALLOW
                && context.bestOperationPolicy() == BestOperationPolicy.ALLOW_DEGRADED;

        // The missing-grid branch comes first even when the most accurate rejected candidate was
        // rejected for its method, because a missing file is something a caller can fix and an
        // unimplemented operator is not. Reporting UNSUPPORTED_OPERATION_METHOD when a grid would also
        // have worked would be true and useless.
        if (!mayDegradeToBallpark && bestRejectedForGrid != null) {
            return failure(candidates, warnings, ErrorCause.BEST_OPERATION_UNAVAILABLE,
                    missingGridMessage(bestRejectedForGrid, candidates, srcBase, tgtBase, context));
        }
        if (!mayDegradeToBallpark && bestRejectedForMethod != null) {
            return failure(candidates, warnings, ErrorCause.UNSUPPORTED_OPERATION_METHOD,
                    unsupportedMessage(bestRejectedForMethod, candidates, srcBase, tgtBase));
        }
        if (ballparkCandidate != null) {
            if (context.ballparkPolicy() == BallparkPolicy.ALLOW) {
                CrsOperationCandidate skipped = bestRejectedForGrid != null ? bestRejectedForGrid
                        : bestRejectedForMethod;
                if (skipped != null) {
                    warnings.add("degraded to a ballpark offset, which applies no shift at all, "
                            + "although the authority does publish " + skipped.authorityCode() + " ("
                            + skipped.name() + (skipped.accuracy().isPresent()
                                    ? ", " + skipped.accuracy().get().metres() + " m" : "")
                            + "): " + skipped.rejectionReason().orElse("")
                            + " This is the largest degradation there is and it was allowed only "
                            + "because BallparkPolicy.ALLOW and "
                            + "BestOperationPolicy.ALLOW_DEGRADED were both set.");
                }
                warnings.add("BALLPARK: " + ballparkCandidate.rejectionReason().orElse(""));
                return new Selection(false, false, ballparkCandidate, candidates, warnings, null,
                        null);
            }
            return failure(candidates, warnings, ErrorCause.BALLPARK_REJECTED,
                    ballparkMessage(ballparkCandidate, candidates, srcBase, tgtBase));
        }
        // Reachable only when every candidate, including the ballpark, is deprecated or superseded.
        return failure(candidates, warnings, ErrorCause.NO_OPERATION_AVAILABLE,
                "every operation the authority publishes between " + srcBase.authorityCode()
                        + " and " + tgtBase.authorityCode() + " is deprecated or superseded:\n"
                        + list(candidates));
    }

    /**
     * The most accurate candidate that <em>cannot</em> be used because of a missing grid or an
     * unimplemented method, or null if there is none.
     *
     * <p>Deprecated, superseded and ballpark candidates are excluded: an operation the authority has
     * withdrawn is not evidence that a better answer was available, and a ballpark one has no accuracy
     * to compare. Ties are broken on the authority reference, so the choice is deterministic.
     */
    private static CrsOperationCandidate mostAccurateRejected(
            List<CrsOperationCandidate> candidates) {
        CrsOperationCandidate best = null;
        for (int i = 0; i < candidates.size(); i++) {
            CrsOperationCandidate c = candidates.get(i);
            if (c.rejection() != CrsOperationCandidate.Rejection.MISSING_GRID
                    && c.rejection() != CrsOperationCandidate.Rejection.UNSUPPORTED_METHOD) {
                continue;
            }
            if (best == null || c.isBetterAccuracyThan(best)) {
                best = c;
            }
        }
        return best;
    }

    private static Selection failure(List<CrsOperationCandidate> candidates, List<String> warnings,
                                     ErrorCause cause, String message) {
        return new Selection(false, false, null, candidates, warnings, cause, message);
    }

    // ------------------------------------------------------------------ messages

    private static String betterButUnavailableNote(CrsOperationCandidate selected,
                                                   CrsOperationCandidate better,
                                                   ProjContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("a better-ranked candidate was skipped: ").append(better.authorityCode())
                .append(", ").append(better.name()).append(", ")
                .append(better.accuracy().isPresent()
                        ? better.accuracy().get().metres() + " m" : "accuracy unknown")
                .append(" -- ").append(better.rejectionReason().orElse("")).append(' ');
        if (selected.isDegradedRelativeTo(better)) {
            sb.append("BestOperationPolicy.").append(context.bestOperationPolicy())
                    .append(" allowed the degradation to ");
        } else {
            sb.append("It is no more accurate than the operation that was selected, so this is not a "
                    + "degradation and BestOperationPolicy.REQUIRE_BEST has nothing to refuse. "
                    + "Selected instead: ");
        }
        sb.append(selected.authorityCode()).append(", ").append(selected.name()).append(", ")
                .append(selected.accuracy().isPresent()
                        ? selected.accuracy().get().metres() + " m" : "accuracy unknown")
                .append('.');
        return sb.toString();
    }

    private static String degradedMessage(CrsOperationCandidate selected,
                                          CrsOperationCandidate better, DbObjectRef srcBase,
                                          DbObjectRef tgtBase, ProjContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("refusing to build ").append(srcBase.authorityCode()).append(" -> ")
                .append(tgtBase.authorityCode())
                .append(": the best operation the authority publishes cannot be executed here, and "
                        + "the best one that can is less accurate.\n");
        sb.append("  best      : ").append(better.describe()).append('\n');
        sb.append("  available : ").append(selected.describe()).append('\n');
        appendMissingFiles(sb, better);
        sb.append("BestOperationPolicy.").append(context.bestOperationPolicy())
                .append(" refuses this rather than quietly returning coordinates that are ")
                .append(accuracyGap(selected, better))
                .append(" worse than you asked for. To accept the degradation, and record that you "
                        + "did: ProjContext.builder().bestOperationPolicy("
                        + "BestOperationPolicy.ALLOW_DEGRADED).build().");
        return sb.toString();
    }

    private static String accuracyGap(CrsOperationCandidate selected, CrsOperationCandidate better) {
        if (!selected.accuracy().isPresent() || !better.accuracy().isPresent()) {
            return "of unquantifiable accuracy";
        }
        return (selected.accuracy().get().metres() - better.accuracy().get().metres()) + " m";
    }

    private static String missingGridMessage(CrsOperationCandidate best,
                                             List<CrsOperationCandidate> candidates,
                                             DbObjectRef srcBase, DbObjectRef tgtBase,
                                             ProjContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("refusing to build ").append(srcBase.authorityCode()).append(" -> ")
                .append(tgtBase.authorityCode()).append(": the authority publishes an operation for "
                        + "this pair and not one of the executable ones has its grid files.\n");
        sb.append("  best executable candidate: ").append(best.describe()).append('\n');
        appendMissingFiles(sb, best);
        sb.append("This is NOT a ballpark rejection. The authority does publish an operation -- ")
                .append(best.authorityCode()).append(", ").append(best.name());
        if (best.accuracy().isPresent()) {
            sb.append(", ").append(best.accuracy().get().metres()).append(" m");
        }
        sb.append(" -- and it is a real one; what is missing is the data file. Add the grid and this "
                + "call succeeds.\n");
        sb.append("All candidates, best first:\n").append(list(candidates));
        sb.append("To proceed with the best operation that can be executed, and record that you "
                + "did: ProjContext.builder().gridPolicy(GridPolicy.WARN).build(), or "
                + ".bestOperationPolicy(BestOperationPolicy.ALLOW_DEGRADED). GridPolicy is currently ")
                .append(context.gridPolicy()).append('.');
        return sb.toString();
    }

    /**
     * Names every file the candidate needs and cannot find, one line each, <b>including the second
     * grid of a NADCON pair</b> and every alternative spelling that was probed. A message that named
     * only {@code conus.las} would send a reader looking for one file when the authority requires two.
     */
    private static void appendMissingFiles(StringBuilder sb, CrsOperationCandidate candidate) {
        List<GridInfo> missing = candidate.missingGrids();
        if (missing.isEmpty()) {
            return;
        }
        sb.append("  missing grid files (").append(missing.size()).append(" of ")
                .append(candidate.grids().size()).append(" the authority requires):\n");
        for (int i = 0; i < missing.size(); i++) {
            GridInfo g = missing.get(i);
            sb.append("      ").append(g.name());
            if (g.slot().isPresent()) {
                sb.append("  [slot ").append(g.slot().getAsInt()).append(']');
            }
            if (!g.probedNames().isEmpty()) {
                sb.append("  tried ").append(g.probedNames());
            }
            if (g.knownUrl().isPresent()) {
                sb.append("  see ").append(g.knownUrl().get())
                        .append(" -- information only, proj4j core performs no network I/O");
            }
            sb.append('\n');
        }
        if (candidate.grids().size() > 1) {
            sb.append("  NOTE: this operation needs ").append(candidate.grids().size())
                    .append(" grid files, not one. NADCON splits the latitude and longitude shifts "
                            + "across a .las/.los pair (150 of the 1,062 grid transformations in the "
                            + "shipped database have a second grid), and applying only the first "
                            + "would shift by half and report success.\n");
        }
    }

    private static String unsupportedMessage(CrsOperationCandidate best,
                                             List<CrsOperationCandidate> candidates,
                                             DbObjectRef srcBase, DbObjectRef tgtBase) {
        StringBuilder sb = new StringBuilder();
        sb.append("refusing to build ").append(srcBase.authorityCode()).append(" -> ")
                .append(tgtBase.authorityCode()).append(": the authority publishes ")
                .append(candidates.size() - 1)
                .append(" operation(s) for this pair, and this library cannot execute any of them.\n");
        sb.append("  best candidate: ").append(best.describe()).append('\n');
        sb.append("All candidates, best first:\n").append(list(candidates));
        sb.append("This is a capability boundary, not missing data: the operations are visible and "
                + "their accuracies are known. Nothing you add to the classpath will change it.");
        return sb.toString();
    }

    private static String ballparkMessage(CrsOperationCandidate ballpark,
                                          List<CrsOperationCandidate> candidates,
                                          DbObjectRef srcBase, DbObjectRef tgtBase) {
        StringBuilder sb = new StringBuilder();
        sb.append("refusing to build ").append(srcBase.authorityCode()).append(" -> ")
                .append(tgtBase.authorityCode())
                .append(": the only operation available is a ballpark one, ")
                .append(ballpark.name()).append(". ")
                .append(ballpark.rejectionReason().orElse("")).append('\n');
        if (candidates.size() == 1) {
            sb.append("The authority publishes no coordinate operation between these CRSs in either "
                    + "direction, so this offset is synthesised rather than read -- PROJ synthesises "
                    + "the same one, and stores it no more than this does.\n");
        } else {
            sb.append("All candidates, best first:\n").append(list(candidates));
        }
        sb.append("To proceed anyway, and record that you did: ProjContext.builder()"
                + ".ballparkPolicy(BallparkPolicy.ALLOW).build(). To reproduce proj4j 1.4.3 exactly, "
                + "use CoordinateTransformFactory, which is unchanged and will not be changed.");
        return sb.toString();
    }

    private static String list(List<CrsOperationCandidate> candidates) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            sb.append("      ").append(candidates.get(i).describe()).append('\n');
        }
        return sb.toString();
    }
}

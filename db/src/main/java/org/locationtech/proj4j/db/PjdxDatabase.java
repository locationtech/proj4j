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
 *******************************************************************************/
package org.locationtech.proj4j.db;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import org.locationtech.proj4j.resource.ResourceHandle;
import org.locationtech.proj4j.resource.SeekableByteReader;
import org.locationtech.proj4j.spi.DbAxis;
import org.locationtech.proj4j.spi.DbCelestialBody;
import org.locationtech.proj4j.spi.DbConversion;
import org.locationtech.proj4j.spi.DbCoordinateSystem;
import org.locationtech.proj4j.spi.DbCrs;
import org.locationtech.proj4j.spi.DbCrsType;
import org.locationtech.proj4j.spi.DbDatum;
import org.locationtech.proj4j.spi.DbEllipsoid;
import org.locationtech.proj4j.spi.DbExtent;
import org.locationtech.proj4j.spi.DbGridAlternative;
import org.locationtech.proj4j.spi.DbObjectRef;
import org.locationtech.proj4j.spi.DbObjectType;
import org.locationtech.proj4j.spi.DbOperation;
import org.locationtech.proj4j.spi.DbOperationStep;
import org.locationtech.proj4j.spi.DbParam;
import org.locationtech.proj4j.spi.DbPrimeMeridian;
import org.locationtech.proj4j.spi.DbSupersession;
import org.locationtech.proj4j.spi.DbUnit;
import org.locationtech.proj4j.spi.ProjDatabase;

/**
 * {@link ProjDatabase} over a {@code .pjdx} index. Pure JDK; the only proj4j packages it touches are
 * {@code spi} and {@code resource}.
 * <p>
 * Every lookup is a binary search over a sorted key array followed by decoding exactly one row. No
 * table is loaded eagerly, nothing is memoised except the immutable key arrays and the string pool, and
 * no query allocates a hash map — so two executors that ask the same question in different orders get
 * the same answer, in the same order, from the same bytes.
 */
public final class PjdxDatabase implements ProjDatabase {

    private final PjdxFile file;
    private final String name;
    private volatile Map<String, String> metadata;
    private volatile SortedSet<String> authorities;

    PjdxDatabase(PjdxFile file, String name) {
        this.file = file;
        this.name = name;
    }

    /**
     * Opens the index behind a {@link ResourceHandle}, verifying its embedded SHA-256.
     */
    public static PjdxDatabase open(final ResourceHandle handle) throws IOException {
        if (handle == null) {
            throw new IllegalArgumentException("handle");
        }
        PjdxFile f = new PjdxFile(handle.origin(), new PjdxFile.ReaderSource() {
            @Override
            public SeekableByteReader open() throws IOException {
                return handle.open();
            }
        }, true);
        return new PjdxDatabase(f, "pjdx:" + handle.origin());
    }

    /**
     * Opens the index over an already-obtained reader source. Used by tests and by callers that hold
     * the bytes themselves.
     *
     * @param verifyChecksum whether to hash the content on open. Leave this on in production: it is the
     *                       check that proves every executor read the same bytes.
     */
    public static PjdxDatabase open(String origin, PjdxFile.ReaderSource source,
                                    boolean verifyChecksum) throws IOException {
        return new PjdxDatabase(new PjdxFile(origin, source, verifyChecksum), "pjdx:" + origin);
    }

    @Override
    public String name() {
        return name;
    }

    /** The SHA-256 recorded in the index header, hex. The per-executor provenance stamp. */
    public String contentSha256() {
        return file.contentSha256();
    }

    /** Total size of the index in bytes. */
    public long sizeBytes() {
        return file.fileLength();
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

    // ---------------------------------------------------------------- metadata

    @Override
    public Map<String, String> metadata() {
        Map<String, String> m = metadata;
        if (m == null) {
            PjdxFile.Table t = file.table(PjdxFormat.S_METADATA);
            TreeMap<String, String> built = new TreeMap<String, String>();
            for (int i = 0; i < t.rowCount; i++) {
                built.put(file.string(t.key(i, 0)), t.row(i).str());
            }
            m = Collections.unmodifiableMap(built);
            metadata = m;
        }
        return m;
    }

    @Override
    public SortedSet<String> authorities() {
        SortedSet<String> a = authorities;
        if (a == null) {
            PjdxFile.Index x = file.index(PjdxFormat.X_AUTHORITIES);
            TreeSet<String> built = new TreeSet<String>();
            for (int i = 0; i < x.entryCount; i++) {
                built.add(file.string(x.field(i, 0)));
            }
            a = Collections.unmodifiableSortedSet(built);
            authorities = a;
        }
        return a;
    }

    // ---------------------------------------------------------------- CRSs

    @Override
    public DbCrs crs(String authName, String code) {
        int a = file.stringId(authName);
        int c = file.stringId(code);
        if (a < 0 || c < 0) {
            return null;
        }
        PjdxFile.Index x = file.index(PjdxFormat.X_CRS_BY_CODE);
        int[] prefix = {a, c};
        int e = x.lowerBound(prefix);
        if (!x.matches(e, prefix)) {
            return null;
        }
        return decodeCrs(x.field(e, 2), x.field(e, 3), authName, code);
    }

    private DbCrs decodeCrs(int tag, int row, String authName, String code) {
        switch (tag) {
            case PjdxFormat.TAG_GEODETIC_CRS: {
                PjdxFile.RowCursor r = file.table(PjdxFormat.S_GEODETIC_CRS).row(row);
                String nm = r.str();
                DbCrsType type = DbCrsType.fromDbValue(r.str());
                DbObjectRef cs = ref(DbObjectType.COORDINATE_SYSTEM, r);
                DbObjectRef datum = ref(DbObjectType.GEODETIC_DATUM, r);
                String textDef = r.str();
                boolean dep = r.bool();
                return new DbCrs(type, authName, code, nm, dep, cs, datum, null, null, null, null,
                        textDef);
            }
            case PjdxFormat.TAG_PROJECTED_CRS: {
                PjdxFile.RowCursor r = file.table(PjdxFormat.S_PROJECTED_CRS).row(row);
                String nm = r.str();
                DbObjectRef cs = ref(DbObjectType.COORDINATE_SYSTEM, r);
                DbObjectRef base = ref(DbObjectType.GEODETIC_CRS, r);
                DbObjectRef conv = ref(DbObjectType.CONVERSION, r);
                String textDef = r.str();
                boolean dep = r.bool();
                return new DbCrs(DbCrsType.PROJECTED, authName, code, nm, dep, cs, null, base, conv,
                        null, null, textDef);
            }
            case PjdxFormat.TAG_VERTICAL_CRS: {
                PjdxFile.RowCursor r = file.table(PjdxFormat.S_VERTICAL_CRS).row(row);
                String nm = r.str();
                DbObjectRef cs = ref(DbObjectType.COORDINATE_SYSTEM, r);
                DbObjectRef datum = ref(DbObjectType.VERTICAL_DATUM, r);
                boolean dep = r.bool();
                return new DbCrs(DbCrsType.VERTICAL, authName, code, nm, dep, cs, datum, null, null,
                        null, null, null);
            }
            case PjdxFormat.TAG_COMPOUND_CRS: {
                PjdxFile.RowCursor r = file.table(PjdxFormat.S_COMPOUND_CRS).row(row);
                String nm = r.str();
                // The horizontal component may be geodetic or projected; the type is resolved by the
                // caller looking it up, so the reference carries the most useful guess and callers are
                // told to check. Storing the resolved type here would mean a second lookup at build
                // time for no gain.
                String hAuth = r.str();
                String hCode = r.str();
                String vAuth = r.str();
                String vCode = r.str();
                boolean dep = r.bool();
                DbObjectRef horiz = horizontalRef(hAuth, hCode);
                DbObjectRef vert = vAuth == null ? null
                        : new DbObjectRef(DbObjectType.VERTICAL_CRS, vAuth, vCode);
                return new DbCrs(DbCrsType.COMPOUND, authName, code, nm, dep, null, null, null, null,
                        horiz, vert, null);
            }
            case PjdxFormat.TAG_ENGINEERING_CRS: {
                PjdxFile.RowCursor r = file.table(PjdxFormat.S_ENGINEERING_CRS).row(row);
                String nm = r.str();
                boolean dep = r.bool();
                return new DbCrs(DbCrsType.ENGINEERING, authName, code, nm, dep, null, null, null,
                        null, null, null, null);
            }
            default:
                throw new IllegalStateException("table tag " + tag + " is not a CRS table");
        }
    }

    /**
     * A compound CRS's horizontal component is a geodetic or a projected CRS. Which one is resolved
     * here by asking the CRS index, so the reference a caller receives has the right type rather than a
     * plausible-looking wrong one.
     */
    private DbObjectRef horizontalRef(String authName, String code) {
        if (authName == null || code == null) {
            return null;
        }
        int a = file.stringId(authName);
        int c = file.stringId(code);
        if (a < 0 || c < 0) {
            return null;
        }
        PjdxFile.Index x = file.index(PjdxFormat.X_CRS_BY_CODE);
        int[] prefix = {a, c};
        int e = x.lowerBound(prefix);
        if (!x.matches(e, prefix)) {
            return null;
        }
        return new DbObjectRef(tagToType(x.field(e, 2)), authName, code);
    }

    @Override
    public List<DbObjectRef> crsCodes(String authName) {
        PjdxFile.Index x = file.index(PjdxFormat.X_CRS_BY_CODE);
        List<DbObjectRef> out = new ArrayList<DbObjectRef>();
        if (authName == null) {
            for (int i = 0; i < x.entryCount; i++) {
                out.add(new DbObjectRef(tagToType(x.field(i, 2)), file.string(x.field(i, 0)),
                        file.string(x.field(i, 1))));
            }
        } else {
            int a = file.stringId(authName);
            if (a < 0) {
                return Collections.emptyList();
            }
            int[] prefix = {a};
            for (int i = x.lowerBound(prefix); x.matches(i, prefix); i++) {
                out.add(new DbObjectRef(tagToType(x.field(i, 2)), authName, file.string(x.field(i, 1))));
            }
        }
        Collections.sort(out);
        return Collections.unmodifiableList(out);
    }

    @Override
    public DbCoordinateSystem coordinateSystem(String authName, String code) {
        int a = file.stringId(authName);
        int c = file.stringId(code);
        if (a < 0 || c < 0) {
            return null;
        }
        PjdxFile.Table t = file.table(PjdxFormat.S_COORDINATE_SYSTEM);
        int row = t.find(a, c);
        if (row < 0) {
            return null;
        }
        PjdxFile.RowCursor r = t.row(row);
        String type = r.str();
        int dimension = r.uint();

        // Axes are keyed (cs authority, cs code, order, axis authority, axis code), so this prefix
        // scan yields them in (order, axis code) order -- the same order PROJ's own
        // `ORDER BY coordinate_system_order` produces. That matters for exactly one upstream
        // coordinate system, PROJ:ENh, whose three axes are numbered 1, 2 and 2; see the generator.
        PjdxFile.Table ax = file.table(PjdxFormat.S_AXIS);
        int[] prefix = {a, c};
        List<DbAxis> axes = new ArrayList<DbAxis>(dimension);
        for (int i = ax.lowerBound(prefix); i < ax.rowCount && ax.compareKey(i, prefix) == 0; i++) {
            int order = ax.key(i, 2);
            PjdxFile.RowCursor ar = ax.row(i);
            String nm = ar.str();
            String abbrev = ar.str();
            String orientation = ar.str();
            DbObjectRef uom = ref(DbObjectType.UNIT_OF_MEASURE, ar);
            axes.add(new DbAxis(nm, abbrev, orientation, order, uom));
        }
        return new DbCoordinateSystem(authName, code, type, dimension, axes);
    }

    // ---------------------------------------------------------------- datums

    @Override
    public DbDatum datum(DbObjectType type, String authName, String code) {
        int section;
        int memberSection;
        if (type == DbObjectType.GEODETIC_DATUM) {
            section = PjdxFormat.S_GEODETIC_DATUM;
            memberSection = PjdxFormat.S_GEODETIC_ENSEMBLE_MEMBER;
        } else if (type == DbObjectType.VERTICAL_DATUM) {
            section = PjdxFormat.S_VERTICAL_DATUM;
            memberSection = PjdxFormat.S_VERTICAL_ENSEMBLE_MEMBER;
        } else {
            throw new IllegalArgumentException(
                    "datum(): type must be GEODETIC_DATUM or VERTICAL_DATUM, not " + type);
        }
        int a = file.stringId(authName);
        int c = file.stringId(code);
        if (a < 0 || c < 0) {
            return null;
        }
        PjdxFile.Table t = file.table(section);
        int row = t.find(a, c);
        if (row < 0) {
            return null;
        }
        PjdxFile.RowCursor r = t.row(row);
        String nm = r.str();
        DbObjectRef ell = null;
        DbObjectRef pm = null;
        if (type == DbObjectType.GEODETIC_DATUM) {
            ell = ref(DbObjectType.ELLIPSOID, r);
            pm = ref(DbObjectType.PRIME_MERIDIAN, r);
        }
        String pubDate = r.str();
        double frameEpoch = r.dbl();
        double ensembleAccuracy = r.dbl();
        boolean dep = r.bool();

        PjdxFile.Table mt = file.table(memberSection);
        int[] prefix = {a, c};
        List<DbObjectRef> members = new ArrayList<DbObjectRef>();
        for (int i = mt.lowerBound(prefix); i < mt.rowCount && mt.compareKey(i, prefix) == 0; i++) {
            PjdxFile.RowCursor mr = mt.row(i);
            members.add(new DbObjectRef(type, mr.str(), mr.str()));
        }
        return new DbDatum(type, authName, code, nm, ell, pm, pubDate, frameEpoch, ensembleAccuracy,
                members, dep);
    }

    @Override
    public DbEllipsoid ellipsoid(String authName, String code) {
        int a = file.stringId(authName);
        int c = file.stringId(code);
        if (a < 0 || c < 0) {
            return null;
        }
        PjdxFile.Table t = file.table(PjdxFormat.S_ELLIPSOID);
        int row = t.find(a, c);
        if (row < 0) {
            return null;
        }
        PjdxFile.RowCursor r = t.row(row);
        String nm = r.str();
        DbObjectRef body = ref(DbObjectType.CELESTIAL_BODY, r);
        double semiMajor = r.dbl();
        DbObjectRef uom = ref(DbObjectType.UNIT_OF_MEASURE, r);
        double invF = r.dbl();
        double semiMinor = r.dbl();
        boolean dep = r.bool();
        return new DbEllipsoid(authName, code, nm, body, semiMajor, uom, invF, semiMinor, dep);
    }

    @Override
    public DbPrimeMeridian primeMeridian(String authName, String code) {
        int a = file.stringId(authName);
        int c = file.stringId(code);
        if (a < 0 || c < 0) {
            return null;
        }
        PjdxFile.Table t = file.table(PjdxFormat.S_PRIME_MERIDIAN);
        int row = t.find(a, c);
        if (row < 0) {
            return null;
        }
        PjdxFile.RowCursor r = t.row(row);
        String nm = r.str();
        double lon = r.dbl();
        DbObjectRef uom = ref(DbObjectType.UNIT_OF_MEASURE, r);
        boolean dep = r.bool();
        return new DbPrimeMeridian(authName, code, nm, lon, uom, dep);
    }

    @Override
    public DbUnit unit(String authName, String code) {
        int a = file.stringId(authName);
        int c = file.stringId(code);
        if (a < 0 || c < 0) {
            return null;
        }
        PjdxFile.Table t = file.table(PjdxFormat.S_UNIT);
        int row = t.find(a, c);
        if (row < 0) {
            return null;
        }
        PjdxFile.RowCursor r = t.row(row);
        String nm = r.str();
        DbUnit.Type type = DbUnit.Type.fromDbValue(r.str());
        double factor = r.dbl();
        String shortName = r.str();
        boolean dep = r.bool();
        return new DbUnit(authName, code, nm, type, factor, shortName, dep);
    }

    @Override
    public DbCelestialBody celestialBody(String authName, String code) {
        int a = file.stringId(authName);
        int c = file.stringId(code);
        if (a < 0 || c < 0) {
            return null;
        }
        PjdxFile.Table t = file.table(PjdxFormat.S_CELESTIAL_BODY);
        int row = t.find(a, c);
        if (row < 0) {
            return null;
        }
        PjdxFile.RowCursor r = t.row(row);
        return new DbCelestialBody(authName, code, r.str(), r.dbl());
    }

    @Override
    public List<DbObjectRef> crsUsingDatum(DbObjectType datumType, String datumAuthName,
                                           String datumCode) {
        int tag = typeToTag(datumType);
        int a = file.stringId(datumAuthName);
        int c = file.stringId(datumCode);
        if (a < 0 || c < 0) {
            return Collections.emptyList();
        }
        PjdxFile.Index x = file.index(PjdxFormat.X_CRS_BY_DATUM);
        int[] prefix = {tag, a, c};
        List<DbObjectRef> out = new ArrayList<DbObjectRef>();
        for (int i = x.lowerBound(prefix); x.matches(i, prefix); i++) {
            out.add(new DbObjectRef(tagToType(x.field(i, 3)), file.string(x.field(i, 4)),
                    file.string(x.field(i, 5))));
        }
        Collections.sort(out);
        return Collections.unmodifiableList(out);
    }

    // ---------------------------------------------------------------- conversions

    @Override
    public DbConversion conversion(String authName, String code) {
        int a = file.stringId(authName);
        int c = file.stringId(code);
        if (a < 0 || c < 0) {
            return null;
        }
        PjdxFile.Table t = file.table(PjdxFormat.S_CONVERSION);
        int row = t.find(a, c);
        if (row < 0) {
            return null;
        }
        PjdxFile.RowCursor r = t.row(row);
        String nm = r.str();
        String methodAuth = r.str();
        String methodCode = r.str();
        String methodName = r.str();
        List<DbParam> params = params(r);
        boolean dep = r.bool();
        return new DbConversion(authName, code, nm, methodAuth, methodCode, methodName, params, dep);
    }

    // ---------------------------------------------------------------- operations

    private static final int[] OPERATION_SECTIONS = {
            PjdxFormat.S_HELMERT_TRANSFORMATION, PjdxFormat.S_GRID_TRANSFORMATION,
            PjdxFormat.S_OTHER_TRANSFORMATION, PjdxFormat.S_CONCATENATED_OPERATION};

    private static final int[] OPERATION_TAGS = {
            PjdxFormat.TAG_HELMERT_TRANSFORMATION, PjdxFormat.TAG_GRID_TRANSFORMATION,
            PjdxFormat.TAG_OTHER_TRANSFORMATION, PjdxFormat.TAG_CONCATENATED_OPERATION};

    @Override
    public DbOperation operation(String authName, String code) {
        int a = file.stringId(authName);
        int c = file.stringId(code);
        if (a < 0 || c < 0) {
            return null;
        }
        for (int i = 0; i < OPERATION_SECTIONS.length; i++) {
            PjdxFile.Table t = file.table(OPERATION_SECTIONS[i]);
            int row = t.find(a, c);
            if (row >= 0) {
                return decodeOperation(OPERATION_TAGS[i], row, authName, code);
            }
        }
        return null;
    }

    private DbOperation decodeOperation(int tag, int row, String authName, String code) {
        if (tag == PjdxFormat.TAG_CONCATENATED_OPERATION) {
            PjdxFile.RowCursor r = file.table(PjdxFormat.S_CONCATENATED_OPERATION).row(row);
            String nm = r.str();
            DbObjectRef src = crsRef(r);
            DbObjectRef tgt = crsRef(r);
            double accuracy = r.dbl();
            String version = r.str();
            boolean dep = r.bool();
            return new DbOperation(DbObjectType.CONCATENATED_OPERATION, authName, code, nm, null, null,
                    null, src, tgt, accuracy, null, null, null, steps(authName, code), version, dep);
        }
        int section = tag == PjdxFormat.TAG_HELMERT_TRANSFORMATION ? PjdxFormat.S_HELMERT_TRANSFORMATION
                : tag == PjdxFormat.TAG_GRID_TRANSFORMATION ? PjdxFormat.S_GRID_TRANSFORMATION
                : PjdxFormat.S_OTHER_TRANSFORMATION;
        PjdxFile.RowCursor r = file.table(section).row(row);
        String nm = r.str();
        String methodAuth = r.str();
        String methodCode = r.str();
        String methodName = r.str();
        DbObjectRef src = crsRef(r);
        DbObjectRef tgt = crsRef(r);
        double accuracy = r.dbl();
        List<DbParam> params = params(r);
        List<String> grids;
        DbObjectRef interp;
        if (tag == PjdxFormat.TAG_HELMERT_TRANSFORMATION) {
            grids = Collections.emptyList();
            interp = null;
        } else {
            int n = r.uint();
            if (n == 0) {
                grids = Collections.emptyList();
            } else {
                List<String> g = new ArrayList<String>(n);
                for (int i = 0; i < n; i++) {
                    g.add(r.str());
                }
                grids = g;
            }
            interp = crsRef(r);
        }
        String version = r.str();
        boolean dep = r.bool();
        return new DbOperation(tagToType(tag), authName, code, nm, methodAuth, methodCode, methodName,
                src, tgt, accuracy, params, grids, interp, null, version, dep);
    }

    private List<DbOperationStep> steps(String authName, String code) {
        int a = file.stringId(authName);
        int c = file.stringId(code);
        PjdxFile.Table t = file.table(PjdxFormat.S_CONCATENATED_STEP);
        int[] prefix = {a, c};
        List<DbOperationStep> out = new ArrayList<DbOperationStep>();
        for (int i = t.lowerBound(prefix); i < t.rowCount && t.compareKey(i, prefix) == 0; i++) {
            int stepNumber = t.key(i, 2);
            PjdxFile.RowCursor r = t.row(i);
            int stepTag = r.uint();
            String sa = r.str();
            String sc = r.str();
            int dir = r.uint();
            out.add(new DbOperationStep(stepNumber, new DbObjectRef(tagToType(stepTag), sa, sc),
                    dir == PjdxFormat.DIRECTION_FORWARD ? DbOperationStep.Direction.FORWARD
                            : dir == PjdxFormat.DIRECTION_REVERSE ? DbOperationStep.Direction.REVERSE
                            : DbOperationStep.Direction.UNSPECIFIED));
        }
        return out;
    }

    @Override
    public List<DbOperation> operationsBetween(String srcAuthName, String srcCode,
                                               String tgtAuthName, String tgtCode) {
        int sa = file.stringId(srcAuthName);
        int sc = file.stringId(srcCode);
        int ta = file.stringId(tgtAuthName);
        int tc = file.stringId(tgtCode);
        if (sa < 0 || sc < 0 || ta < 0 || tc < 0) {
            return Collections.emptyList();
        }
        PjdxFile.Index x = file.index(PjdxFormat.X_OP_BY_SOURCE_TARGET);
        int[] prefix = {sa, sc, ta, tc};
        List<DbOperation> out = new ArrayList<DbOperation>();
        for (int i = x.lowerBound(prefix); x.matches(i, prefix); i++) {
            int tag = x.field(i, 4);
            int row = x.field(i, 5);
            out.add(decodeOperation(tag, row, opAuth(tag, row), opCode(tag, row)));
        }
        // The index is already in (kind, auth, code) order because it was built that way, but sorting
        // here makes the guarantee a property of this method rather than of a generator invariant a
        // future change could break silently.
        Collections.sort(out, new java.util.Comparator<DbOperation>() {
            @Override
            public int compare(DbOperation a, DbOperation b) {
                return a.ref().compareTo(b.ref());
            }
        });
        return Collections.unmodifiableList(out);
    }

    private String opAuth(int tag, int row) {
        return file.string(file.table(operationSection(tag)).key(row, 0));
    }

    private String opCode(int tag, int row) {
        return file.string(file.table(operationSection(tag)).key(row, 1));
    }

    private static int operationSection(int tag) {
        switch (tag) {
            case PjdxFormat.TAG_HELMERT_TRANSFORMATION:
                return PjdxFormat.S_HELMERT_TRANSFORMATION;
            case PjdxFormat.TAG_GRID_TRANSFORMATION:
                return PjdxFormat.S_GRID_TRANSFORMATION;
            case PjdxFormat.TAG_OTHER_TRANSFORMATION:
                return PjdxFormat.S_OTHER_TRANSFORMATION;
            case PjdxFormat.TAG_CONCATENATED_OPERATION:
                return PjdxFormat.S_CONCATENATED_OPERATION;
            default:
                throw new IllegalStateException("table tag " + tag + " is not an operation table");
        }
    }

    @Override
    public List<DbObjectRef> operationsWithSourceCrs(String authName, String code) {
        return operationRefs(PjdxFormat.X_OP_BY_SOURCE_TARGET, authName, code);
    }

    @Override
    public List<DbObjectRef> operationsWithTargetCrs(String authName, String code) {
        return operationRefs(PjdxFormat.X_OP_BY_TARGET_SOURCE, authName, code);
    }

    private List<DbObjectRef> operationRefs(int indexId, String authName, String code) {
        int a = file.stringId(authName);
        int c = file.stringId(code);
        if (a < 0 || c < 0) {
            return Collections.emptyList();
        }
        PjdxFile.Index x = file.index(indexId);
        int[] prefix = {a, c};
        List<DbObjectRef> out = new ArrayList<DbObjectRef>();
        for (int i = x.lowerBound(prefix); x.matches(i, prefix); i++) {
            int tag = x.field(i, 4);
            int row = x.field(i, 5);
            out.add(new DbObjectRef(tagToType(tag), opAuth(tag, row), opCode(tag, row)));
        }
        Collections.sort(out);
        return Collections.unmodifiableList(out);
    }

    // ---------------------------------------------------------------- area of use

    @Override
    public List<DbExtent> extentsFor(DbObjectRef object) {
        if (object == null) {
            return Collections.emptyList();
        }
        int tag = typeToTag(object.type());
        int a = file.stringId(object.authName());
        int c = file.stringId(object.code());
        if (a < 0 || c < 0) {
            return Collections.emptyList();
        }
        PjdxFile.Index x = file.index(PjdxFormat.X_USAGE_BY_OBJECT);
        int[] prefix = {tag, a, c};
        // A LinkedHashMap keyed by the extent reference removes the duplicates that upstream's usage
        // table does contain, without letting map order reach the result: the list is sorted below.
        LinkedHashMap<DbObjectRef, DbExtent> found = new LinkedHashMap<DbObjectRef, DbExtent>();
        for (int i = x.lowerBound(prefix); x.matches(i, prefix); i++) {
            String ea = file.string(x.field(i, 3));
            String ec = file.string(x.field(i, 4));
            DbExtent e = extent(ea, ec);
            if (e != null) {
                found.put(e.ref(), e);
            }
        }
        List<DbExtent> out = new ArrayList<DbExtent>(found.values());
        Collections.sort(out, new java.util.Comparator<DbExtent>() {
            @Override
            public int compare(DbExtent p, DbExtent q) {
                return p.ref().compareTo(q.ref());
            }
        });
        return Collections.unmodifiableList(out);
    }

    @Override
    public DbExtent extent(String authName, String code) {
        int a = file.stringId(authName);
        int c = file.stringId(code);
        if (a < 0 || c < 0) {
            return null;
        }
        PjdxFile.Table t = file.table(PjdxFormat.S_EXTENT);
        int row = t.find(a, c);
        if (row < 0) {
            return null;
        }
        PjdxFile.RowCursor r = t.row(row);
        String nm = r.str();
        String description = r.str();
        double west = r.dbl();
        double south = r.dbl();
        double east = r.dbl();
        double north = r.dbl();
        boolean dep = r.bool();
        return new DbExtent(authName, code, nm, description, west, south, east, north, dep);
    }

    // ---------------------------------------------------------------- names

    @Override
    public List<String> aliases(DbObjectRef object) {
        if (object == null) {
            return Collections.emptyList();
        }
        int tag = typeToTag(object.type());
        int a = file.stringId(object.authName());
        int c = file.stringId(object.code());
        if (a < 0 || c < 0) {
            return Collections.emptyList();
        }
        PjdxFile.Table t = file.table(PjdxFormat.S_ALIAS);
        int[] prefix = {tag, a, c};
        TreeSet<String> out = new TreeSet<String>();
        for (int i = t.lowerBound(prefix); i < t.rowCount && t.compareKey(i, prefix) == 0; i++) {
            out.add(file.string(t.key(i, 3)));
        }
        return Collections.unmodifiableList(new ArrayList<String>(out));
    }

    @Override
    public List<DbObjectRef> findCrsByName(String name) {
        String normalized = PjdxFormat.normalizeName(name);
        if (normalized == null || normalized.isEmpty()) {
            return Collections.emptyList();
        }
        int n = file.stringId(normalized);
        if (n < 0) {
            return Collections.emptyList();
        }
        PjdxFile.Index x = file.index(PjdxFormat.X_CRS_BY_NAME);
        int[] prefix = {n};
        TreeSet<DbObjectRef> out = new TreeSet<DbObjectRef>();
        for (int i = x.lowerBound(prefix); x.matches(i, prefix); i++) {
            out.add(new DbObjectRef(tagToType(x.field(i, 1)), file.string(x.field(i, 2)),
                    file.string(x.field(i, 3))));
        }
        return Collections.unmodifiableList(new ArrayList<DbObjectRef>(out));
    }

    // ------------------------------------------------- deprecation and supersession

    @Override
    public List<DbSupersession> supersededBy(DbObjectRef object) {
        if (object == null) {
            return Collections.emptyList();
        }
        int tag = typeToTag(object.type());
        int a = file.stringId(object.authName());
        int c = file.stringId(object.code());
        if (a < 0 || c < 0) {
            return Collections.emptyList();
        }
        PjdxFile.Table t = file.table(PjdxFormat.S_SUPERSESSION);
        int[] prefix = {tag, a, c};
        List<DbSupersession> out = new ArrayList<DbSupersession>();
        for (int i = t.lowerBound(prefix); i < t.rowCount && t.compareKey(i, prefix) == 0; i++) {
            PjdxFile.RowCursor r = t.row(i);
            int replTag = r.uint();
            String ra = r.str();
            String rc = r.str();
            String source = r.str();
            boolean same = r.bool();
            out.add(new DbSupersession(object, new DbObjectRef(tagToType(replTag), ra, rc), source,
                    same));
        }
        Collections.sort(out, new java.util.Comparator<DbSupersession>() {
            @Override
            public int compare(DbSupersession p, DbSupersession q) {
                return p.replacement().compareTo(q.replacement());
            }
        });
        return Collections.unmodifiableList(out);
    }

    @Override
    public List<DbObjectRef> replacementsFor(DbObjectRef object) {
        if (object == null) {
            return Collections.emptyList();
        }
        int tag = typeToTag(object.type());
        int a = file.stringId(object.authName());
        int c = file.stringId(object.code());
        if (a < 0 || c < 0) {
            return Collections.emptyList();
        }
        PjdxFile.Table t = file.table(PjdxFormat.S_DEPRECATION);
        int[] prefix = {tag, a, c};
        TreeSet<DbObjectRef> out = new TreeSet<DbObjectRef>();
        for (int i = t.lowerBound(prefix); i < t.rowCount && t.compareKey(i, prefix) == 0; i++) {
            PjdxFile.RowCursor r = t.row(i);
            // deprecation carries no replacement_table_name: the replacement is of the same kind as the
            // deprecated object, which is upstream's own model.
            out.add(new DbObjectRef(object.type(), r.str(), r.str()));
        }
        return Collections.unmodifiableList(new ArrayList<DbObjectRef>(out));
    }

    // ---------------------------------------------------------------- grids

    @Override
    public DbGridAlternative gridAlternative(String originalGridName) {
        int n = file.stringId(originalGridName);
        if (n < 0) {
            return null;
        }
        PjdxFile.Table t = file.table(PjdxFormat.S_GRID_ALTERNATIVE);
        int row = t.find(n);
        return row < 0 ? null : decodeGridAlternative(t, row);
    }

    @Override
    public List<DbGridAlternative> gridAlternatives() {
        PjdxFile.Table t = file.table(PjdxFormat.S_GRID_ALTERNATIVE);
        List<DbGridAlternative> out = new ArrayList<DbGridAlternative>(t.rowCount);
        for (int i = 0; i < t.rowCount; i++) {
            out.add(decodeGridAlternative(t, i));
        }
        // Row order is already the string-id order of the original name, which is byte order of the
        // UTF-8; sorting by the String makes it Java's collation-free natural order instead, so the
        // documented guarantee holds regardless of how the file was built.
        Collections.sort(out, new java.util.Comparator<DbGridAlternative>() {
            @Override
            public int compare(DbGridAlternative p, DbGridAlternative q) {
                return p.originalGridName().compareTo(q.originalGridName());
            }
        });
        return Collections.unmodifiableList(out);
    }

    private DbGridAlternative decodeGridAlternative(PjdxFile.Table t, int row) {
        String original = file.string(t.key(row, 0));
        PjdxFile.RowCursor r = t.row(row);
        String projName = r.str();
        String oldName = r.str();
        String format = r.str();
        String method = r.str();
        boolean inverse = r.bool();
        String url = r.str();
        Boolean directDownload = r.tri();
        Boolean openLicense = r.tri();
        String directory = r.str();
        return new DbGridAlternative(original, projName, oldName, format, method, inverse, url,
                directDownload, openLicense, directory);
    }

    // ---------------------------------------------------------------- helpers

    private List<DbParam> params(PjdxFile.RowCursor r) {
        int n = r.uint();
        if (n == 0) {
            return Collections.emptyList();
        }
        List<DbParam> out = new ArrayList<DbParam>(n);
        for (int i = 0; i < n; i++) {
            String pa = r.str();
            String pc = r.str();
            String pn = r.str();
            double v = r.dbl();
            String ua = r.str();
            String uc = r.str();
            DbObjectRef uom = ua == null || uc == null ? null
                    : new DbObjectRef(DbObjectType.UNIT_OF_MEASURE, ua, uc);
            out.add(new DbParam(pa, pc, pn, v, uom));
        }
        return out;
    }

    /** Reads an (authName, code) pair as a reference of the given type; null if the pair is null. */
    private DbObjectRef ref(DbObjectType type, PjdxFile.RowCursor r) {
        String a = r.str();
        String c = r.str();
        return a == null || c == null ? null : new DbObjectRef(type, a, c);
    }

    /**
     * A source/target CRS reference. The concrete CRS type is resolved through the CRS index, because
     * an operation row records only the authority and code, and a caller that has to guess between
     * geodetic, projected, vertical and compound will guess wrong for the compound cases.
     */
    private DbObjectRef crsRef(PjdxFile.RowCursor r) {
        String a = r.str();
        String c = r.str();
        if (a == null || c == null) {
            return null;
        }
        DbObjectRef resolved = horizontalRef(a, c);
        return resolved != null ? resolved : new DbObjectRef(DbObjectType.GEODETIC_CRS, a, c);
    }

    static DbObjectType tagToType(int tag) {
        DbObjectType t = DbObjectType.fromDbName(PjdxFormat.TAG_TABLE_NAMES[tag]);
        if (t == null) {
            throw new IllegalStateException("unmapped table tag " + tag);
        }
        return t;
    }

    static int typeToTag(DbObjectType type) {
        for (int i = 0; i < PjdxFormat.TAG_TABLE_NAMES.length; i++) {
            if (PjdxFormat.TAG_TABLE_NAMES[i].equals(type.dbName())) {
                return i;
            }
        }
        throw new IllegalArgumentException("no table tag for " + type);
    }

    @Override
    public String toString() {
        return name + " [" + file.fileLength() + " bytes]";
    }
}

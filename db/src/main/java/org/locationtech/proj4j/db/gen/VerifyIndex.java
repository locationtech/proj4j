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
package org.locationtech.proj4j.db.gen;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.locationtech.proj4j.db.PjdxDatabase;
import org.locationtech.proj4j.db.Proj4jDb;
import org.locationtech.proj4j.db.gen.QuoteDump.Table;
import org.locationtech.proj4j.resource.DirectoryResourceResolver;
import org.locationtech.proj4j.spi.DbAxis;
import org.locationtech.proj4j.spi.DbConversion;
import org.locationtech.proj4j.spi.DbCoordinateSystem;
import org.locationtech.proj4j.spi.DbCrs;
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

/**
 * Exhaustive round-trip proof: reads the {@code sqlite3} dump and the generated {@code .pjdx}, and
 * compares <strong>every row of every transcoded table</strong> field by field. Build-time only; run by
 * {@code -Pregen-db} straight after {@link GenerateIndex}, and excluded from the published jar.
 * <p>
 * This is what makes the transcode trustworthy rather than plausible. A transcoder can be wrong in two
 * ways that no size measurement and no spot check will catch: a field read in the wrong order, and a
 * double that survives as something almost right. Both are checked here — doubles are compared with
 * {@link Double#compare}, so a value that round-tripped to a neighbouring representable number fails,
 * and every reference is compared as a whole {@code (authority, code)} pair.
 * <p>
 * Usage: {@code VerifyIndex <dump> <dataDir>}
 */
public final class VerifyIndex {

    private VerifyIndex() {
    }

    private static final int MAX_REPORTED = 25;
    private static final List<String> failures = new ArrayList<String>();
    private static long checks;
    private static long failureCount;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: VerifyIndex <dump> <dataDir>");
            System.exit(2);
        }
        Path dump = Paths.get(args[0]);
        Path dataDir = Paths.get(args[1]);

        QuoteDump d = QuoteDump.read(dump);
        PjdxDatabase db = Proj4jDb.open(new DirectoryResourceResolver(dataDir));
        if (db == null) {
            throw new IllegalStateException("no index in " + dataDir);
        }
        try {
            long t0 = System.currentTimeMillis();
            verifyMetadata(d, db);
            verifyUnits(d, db);
            verifyCelestialBodies(d, db);
            verifyEllipsoids(d, db);
            verifyPrimeMeridians(d, db);
            verifyDatums(d, db);
            verifyCoordinateSystems(d, db);
            verifyCrs(d, db);
            verifyConversions(d, db);
            verifyOperations(d, db);
            verifyConcatenatedSteps(d, db);
            verifyExtents(d, db);
            verifyUsages(d, db);
            verifyGridAlternatives(d, db);
            verifyAliases(d, db);
            verifySupersessions(d, db);
            verifyDeprecations(d, db);
            verifyNameIndex(d, db);
            verifyCrsByDatum(d, db);
            verifyAuthorities(d, db);
            long ms = System.currentTimeMillis() - t0;

            System.out.println();
            System.out.println("VerifyIndex: " + checks + " field comparisons in " + ms + " ms");
            if (failureCount == 0) {
                System.out.println("VerifyIndex: OK -- every transcoded row matches the SQLite source");
            } else {
                System.out.println("VerifyIndex: " + failureCount + " MISMATCHES");
                for (String f : failures) {
                    System.out.println("  " + f);
                }
                System.exit(1);
            }
        } finally {
            db.close();
        }
    }

    // ------------------------------------------------------------------ assertions

    private static void eq(String what, Object expected, Object actual) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            fail(what + ": expected " + show(expected) + ", got " + show(actual));
        }
    }

    /** Bit-exact, so a double that came back as a neighbouring representable value is a failure. */
    private static void eqDouble(String what, double expected, double actual) {
        checks++;
        if (Double.compare(expected, actual) != 0) {
            fail(what + ": expected " + expected + " (bits "
                    + Long.toHexString(Double.doubleToRawLongBits(expected)) + "), got " + actual
                    + " (bits " + Long.toHexString(Double.doubleToRawLongBits(actual)) + ")");
        }
    }

    private static void isTrue(String what, boolean condition) {
        checks++;
        if (!condition) {
            fail(what);
        }
    }

    private static void fail(String message) {
        failureCount++;
        if (failures.size() < MAX_REPORTED) {
            failures.add(message);
        }
    }

    private static String show(Object o) {
        return o == null ? "null" : "'" + o + "'";
    }

    private static String ref(Table t, Object[] row) {
        return t.name + " " + t.text(row, "auth_name") + ":" + t.text(row, "code");
    }

    private static void refEq(String what, String expectedAuth, String expectedCode,
                              DbObjectRef actual) {
        if (expectedAuth == null || expectedCode == null) {
            checks++;
            if (actual != null) {
                fail(what + ": expected no reference, got " + actual);
            }
            return;
        }
        checks++;
        if (actual == null) {
            fail(what + ": expected " + expectedAuth + ":" + expectedCode + ", got null");
        } else if (!expectedAuth.equals(actual.authName()) || !expectedCode.equals(actual.code())) {
            fail(what + ": expected " + expectedAuth + ":" + expectedCode + ", got "
                    + actual.authorityCode());
        }
    }

    // ------------------------------------------------------------------ per table

    private static void verifyMetadata(QuoteDump d, PjdxDatabase db) {
        Table t = d.table("metadata");
        Map<String, String> m = db.metadata();
        eq("metadata size", Integer.valueOf(t.rows.size()), Integer.valueOf(m.size()));
        for (Object[] row : t.rows) {
            eq("metadata " + t.text(row, "key"), t.text(row, "value"), m.get(t.text(row, "key")));
        }
    }

    private static void verifyUnits(QuoteDump d, PjdxDatabase db) {
        Table t = d.table("unit_of_measure");
        for (Object[] row : t.rows) {
            String a = t.text(row, "auth_name");
            String c = t.text(row, "code");
            DbUnit u = db.unit(a, c);
            if (u == null) {
                fail(ref(t, row) + " missing");
                continue;
            }
            eq(ref(t, row) + ".name", t.text(row, "name"), u.name());
            eq(ref(t, row) + ".type", t.text(row, "type"),
                    u.type() == null ? null : u.type().dbValue());
            eqDouble(ref(t, row) + ".conv_factor", t.real(row, "conv_factor"), u.conversionFactor());
            eq(ref(t, row) + ".proj_short_name", t.text(row, "proj_short_name"), u.projShortName());
            eq(ref(t, row) + ".deprecated", Boolean.valueOf(t.bool(row, "deprecated")),
                    Boolean.valueOf(u.deprecated()));
        }
    }

    private static void verifyCelestialBodies(QuoteDump d, PjdxDatabase db) {
        Table t = d.table("celestial_body");
        for (Object[] row : t.rows) {
            org.locationtech.proj4j.spi.DbCelestialBody b =
                    db.celestialBody(t.text(row, "auth_name"), t.text(row, "code"));
            if (b == null) {
                fail(ref(t, row) + " missing");
                continue;
            }
            eq(ref(t, row) + ".name", t.text(row, "name"), b.name());
            eqDouble(ref(t, row) + ".semi_major_axis", t.real(row, "semi_major_axis"),
                    b.semiMajorAxis());
        }
    }

    private static void verifyEllipsoids(QuoteDump d, PjdxDatabase db) {
        Table t = d.table("ellipsoid");
        for (Object[] row : t.rows) {
            DbEllipsoid e = db.ellipsoid(t.text(row, "auth_name"), t.text(row, "code"));
            if (e == null) {
                fail(ref(t, row) + " missing");
                continue;
            }
            eq(ref(t, row) + ".name", t.text(row, "name"), e.name());
            refEq(ref(t, row) + ".celestial_body", t.text(row, "celestial_body_auth_name"),
                    t.text(row, "celestial_body_code"), e.celestialBody());
            eqDouble(ref(t, row) + ".semi_major_axis", t.real(row, "semi_major_axis"),
                    e.semiMajorAxis());
            refEq(ref(t, row) + ".uom", t.text(row, "uom_auth_name"), t.text(row, "uom_code"),
                    e.unit());
            eqDouble(ref(t, row) + ".inv_flattening", t.real(row, "inv_flattening"),
                    e.inverseFlattening());
            eqDouble(ref(t, row) + ".semi_minor_axis", t.real(row, "semi_minor_axis"),
                    e.semiMinorAxis());
            eq(ref(t, row) + ".deprecated", Boolean.valueOf(t.bool(row, "deprecated")),
                    Boolean.valueOf(e.deprecated()));
            // Upstream's CHECK: exactly one of the two shape parameters. If the transcode ever loses
            // one, this catches it independently of the field comparisons above.
            isTrue(ref(t, row) + ": exactly one of inv_flattening / semi_minor_axis",
                    Double.isNaN(e.inverseFlattening()) != Double.isNaN(e.semiMinorAxis()));
        }
    }

    private static void verifyPrimeMeridians(QuoteDump d, PjdxDatabase db) {
        Table t = d.table("prime_meridian");
        for (Object[] row : t.rows) {
            DbPrimeMeridian p = db.primeMeridian(t.text(row, "auth_name"), t.text(row, "code"));
            if (p == null) {
                fail(ref(t, row) + " missing");
                continue;
            }
            eq(ref(t, row) + ".name", t.text(row, "name"), p.name());
            eqDouble(ref(t, row) + ".longitude", t.real(row, "longitude"), p.longitude());
            refEq(ref(t, row) + ".uom", t.text(row, "uom_auth_name"), t.text(row, "uom_code"),
                    p.unit());
            eq(ref(t, row) + ".deprecated", Boolean.valueOf(t.bool(row, "deprecated")),
                    Boolean.valueOf(p.deprecated()));
        }
    }

    private static void verifyDatums(QuoteDump d, PjdxDatabase db) {
        Table g = d.table("geodetic_datum");
        for (Object[] row : g.rows) {
            DbDatum dm = db.datum(DbObjectType.GEODETIC_DATUM, g.text(row, "auth_name"),
                    g.text(row, "code"));
            if (dm == null) {
                fail(ref(g, row) + " missing");
                continue;
            }
            eq(ref(g, row) + ".name", g.text(row, "name"), dm.name());
            refEq(ref(g, row) + ".ellipsoid", g.text(row, "ellipsoid_auth_name"),
                    g.text(row, "ellipsoid_code"), dm.ellipsoid());
            refEq(ref(g, row) + ".prime_meridian", g.text(row, "prime_meridian_auth_name"),
                    g.text(row, "prime_meridian_code"), dm.primeMeridian());
            eq(ref(g, row) + ".publication_date", g.text(row, "publication_date"),
                    dm.publicationDate());
            eqDouble(ref(g, row) + ".frame_reference_epoch", g.real(row, "frame_reference_epoch"),
                    dm.frameReferenceEpoch());
            eqDouble(ref(g, row) + ".ensemble_accuracy", g.real(row, "ensemble_accuracy"),
                    dm.ensembleAccuracy());
            eq(ref(g, row) + ".deprecated", Boolean.valueOf(g.bool(row, "deprecated")),
                    Boolean.valueOf(dm.deprecated()));
        }
        Table v = d.table("vertical_datum");
        for (Object[] row : v.rows) {
            DbDatum dm = db.datum(DbObjectType.VERTICAL_DATUM, v.text(row, "auth_name"),
                    v.text(row, "code"));
            if (dm == null) {
                fail(ref(v, row) + " missing");
                continue;
            }
            eq(ref(v, row) + ".name", v.text(row, "name"), dm.name());
            eq(ref(v, row) + ".ellipsoid is null", Boolean.TRUE,
                    Boolean.valueOf(dm.ellipsoid() == null));
            eqDouble(ref(v, row) + ".ensemble_accuracy", v.real(row, "ensemble_accuracy"),
                    dm.ensembleAccuracy());
            eq(ref(v, row) + ".deprecated", Boolean.valueOf(v.bool(row, "deprecated")),
                    Boolean.valueOf(dm.deprecated()));
        }
        verifyEnsembleMembers(d.table("geodetic_datum_ensemble_member"), db,
                DbObjectType.GEODETIC_DATUM);
        verifyEnsembleMembers(d.table("vertical_datum_ensemble_member"), db,
                DbObjectType.VERTICAL_DATUM);
    }

    private static void verifyEnsembleMembers(Table t, PjdxDatabase db, DbObjectType type) {
        for (Object[] row : t.rows) {
            DbDatum dm = db.datum(type, t.text(row, "ensemble_auth_name"),
                    t.text(row, "ensemble_code"));
            if (dm == null) {
                fail(t.name + " ensemble " + t.text(row, "ensemble_code") + " missing");
                continue;
            }
            int sequence = t.integer(row, "sequence");
            String what = t.name + " " + t.text(row, "ensemble_code") + " member " + sequence;
            if (sequence > dm.ensembleMembers().size()) {
                fail(what + ": only " + dm.ensembleMembers().size() + " members present");
                continue;
            }
            // Members are stored in sequence order, so position sequence-1 must be this member.
            refEq(what, t.text(row, "member_auth_name"), t.text(row, "member_code"),
                    dm.ensembleMembers().get(sequence - 1));
        }
    }

    private static void verifyCoordinateSystems(QuoteDump d, PjdxDatabase db) {
        Table t = d.table("coordinate_system");
        for (Object[] row : t.rows) {
            DbCoordinateSystem cs = db.coordinateSystem(t.text(row, "auth_name"), t.text(row, "code"));
            if (cs == null) {
                fail(ref(t, row) + " missing");
                continue;
            }
            eq(ref(t, row) + ".type", t.text(row, "type"), cs.type());
            eq(ref(t, row) + ".dimension", Integer.valueOf(t.integer(row, "dimension")),
                    Integer.valueOf(cs.dimension()));
        }
        // Axes are compared in the generator's documented order -- (coordinate_system_order, axis
        // authority, axis code) -- not by position within the order. PROJ:ENh has two axes at order 2,
        // so "the axis at order 2" is not a unique row upstream and a positional lookup by order would
        // be asserting something the source does not say.
        Table ax = d.table("axis");
        java.util.Map<String, List<Object[]>> axesByCs =
                new java.util.LinkedHashMap<String, List<Object[]>>();
        for (Object[] row : ax.rows) {
            String key = ax.text(row, "coordinate_system_auth_name") + ' '
                    + ax.text(row, "coordinate_system_code");
            List<Object[]> list = axesByCs.get(key);
            if (list == null) {
                list = new ArrayList<Object[]>();
                axesByCs.put(key, list);
            }
            list.add(row);
        }
        final Table axFinal = ax;
        for (Map.Entry<String, List<Object[]>> entry : axesByCs.entrySet()) {
            String[] parts = entry.getKey().split(" ", 2);
            DbCoordinateSystem cs = db.coordinateSystem(parts[0], parts[1]);
            List<Object[]> expected = entry.getValue();
            java.util.Collections.sort(expected, new java.util.Comparator<Object[]>() {
                @Override
                public int compare(Object[] p, Object[] q) {
                    int c = Integer.compare(axFinal.integer(p, "coordinate_system_order"),
                            axFinal.integer(q, "coordinate_system_order"));
                    if (c != 0) {
                        return c;
                    }
                    c = axFinal.text(p, "auth_name").compareTo(axFinal.text(q, "auth_name"));
                    return c != 0 ? c : axFinal.text(p, "code").compareTo(axFinal.text(q, "code"));
                }
            });
            if (cs == null) {
                fail("coordinate_system " + entry.getKey() + " missing");
                continue;
            }
            eq("axes of " + entry.getKey() + " count", Integer.valueOf(expected.size()),
                    Integer.valueOf(cs.axes().size()));
            for (int i = 0; i < expected.size() && i < cs.axes().size(); i++) {
                Object[] row = expected.get(i);
                DbAxis a = cs.axes().get(i);
                String what = "axis " + ax.text(row, "auth_name") + ":" + ax.text(row, "code")
                        + " of " + entry.getKey();
                eq(what + ".order",
                        Integer.valueOf(ax.integer(row, "coordinate_system_order")),
                        Integer.valueOf(a.order()));
                eq(what + ".name", ax.text(row, "name"), a.name());
                eq(what + ".abbrev", ax.text(row, "abbrev"), a.abbreviation());
                eq(what + ".orientation", ax.text(row, "orientation"), a.orientation());
                refEq(what + ".uom", ax.text(row, "uom_auth_name"), ax.text(row, "uom_code"),
                        a.unit());
            }
        }
    }

    private static void verifyCrs(QuoteDump d, PjdxDatabase db) {
        Table g = d.table("geodetic_crs");
        for (Object[] row : g.rows) {
            DbCrs crs = db.crs(g.text(row, "auth_name"), g.text(row, "code"));
            if (crs == null) {
                fail(ref(g, row) + " missing");
                continue;
            }
            eq(ref(g, row) + ".name", g.text(row, "name"), crs.name());
            eq(ref(g, row) + ".type", g.text(row, "type"), crs.type().dbValue());
            refEq(ref(g, row) + ".cs", g.text(row, "coordinate_system_auth_name"),
                    g.text(row, "coordinate_system_code"), crs.coordinateSystem());
            refEq(ref(g, row) + ".datum", g.text(row, "datum_auth_name"), g.text(row, "datum_code"),
                    crs.datum());
            eq(ref(g, row) + ".text_definition", g.text(row, "text_definition"),
                    crs.textDefinition());
            eq(ref(g, row) + ".deprecated", Boolean.valueOf(g.bool(row, "deprecated")),
                    Boolean.valueOf(crs.deprecated()));
        }
        Table p = d.table("projected_crs");
        for (Object[] row : p.rows) {
            DbCrs crs = db.crs(p.text(row, "auth_name"), p.text(row, "code"));
            if (crs == null) {
                fail(ref(p, row) + " missing");
                continue;
            }
            eq(ref(p, row) + ".name", p.text(row, "name"), crs.name());
            eq(ref(p, row) + ".type", "projected", crs.type().dbValue());
            refEq(ref(p, row) + ".cs", p.text(row, "coordinate_system_auth_name"),
                    p.text(row, "coordinate_system_code"), crs.coordinateSystem());
            refEq(ref(p, row) + ".base", p.text(row, "geodetic_crs_auth_name"),
                    p.text(row, "geodetic_crs_code"), crs.baseCrs());
            refEq(ref(p, row) + ".conversion", p.text(row, "conversion_auth_name"),
                    p.text(row, "conversion_code"), crs.conversion());
            eq(ref(p, row) + ".text_definition", p.text(row, "text_definition"),
                    crs.textDefinition());
            eq(ref(p, row) + ".deprecated", Boolean.valueOf(p.bool(row, "deprecated")),
                    Boolean.valueOf(crs.deprecated()));
        }
        Table v = d.table("vertical_crs");
        for (Object[] row : v.rows) {
            DbCrs crs = db.crs(v.text(row, "auth_name"), v.text(row, "code"));
            if (crs == null) {
                fail(ref(v, row) + " missing");
                continue;
            }
            eq(ref(v, row) + ".name", v.text(row, "name"), crs.name());
            eq(ref(v, row) + ".type", "vertical", crs.type().dbValue());
            refEq(ref(v, row) + ".datum", v.text(row, "datum_auth_name"), v.text(row, "datum_code"),
                    crs.datum());
            eq(ref(v, row) + ".datum type", DbObjectType.VERTICAL_DATUM,
                    crs.datum() == null ? null : crs.datum().type());
        }
        Table c = d.table("compound_crs");
        for (Object[] row : c.rows) {
            DbCrs crs = db.crs(c.text(row, "auth_name"), c.text(row, "code"));
            if (crs == null) {
                fail(ref(c, row) + " missing");
                continue;
            }
            eq(ref(c, row) + ".name", c.text(row, "name"), crs.name());
            eq(ref(c, row) + ".type", "compound", crs.type().dbValue());
            refEq(ref(c, row) + ".horiz", c.text(row, "horiz_crs_auth_name"),
                    c.text(row, "horiz_crs_code"), crs.horizontalCrs());
            refEq(ref(c, row) + ".vertical", c.text(row, "vertical_crs_auth_name"),
                    c.text(row, "vertical_crs_code"), crs.verticalCrs());
        }
        Table e = d.table("engineering_crs");
        for (Object[] row : e.rows) {
            DbCrs crs = db.crs(e.text(row, "auth_name"), e.text(row, "code"));
            if (crs == null) {
                fail(ref(e, row) + " missing");
                continue;
            }
            eq(ref(e, row) + ".name", e.text(row, "name"), crs.name());
            eq(ref(e, row) + ".type", "engineering", crs.type().dbValue());
        }
    }

    private static void verifyConversions(QuoteDump d, PjdxDatabase db) {
        Table t = d.table("conversion_table");
        Map<String, String> paramNames = new java.util.HashMap<String, String>();
        Table cp = d.table("conversion_param");
        for (Object[] row : cp.rows) {
            paramNames.put(cp.text(row, "auth_name") + ' ' + cp.text(row, "code"),
                    cp.text(row, "name"));
        }
        Map<String, String> methodNames = new java.util.HashMap<String, String>();
        Table cm = d.table("conversion_method");
        for (Object[] row : cm.rows) {
            methodNames.put(cm.text(row, "auth_name") + ' ' + cm.text(row, "code"),
                    cm.text(row, "name"));
        }
        for (Object[] row : t.rows) {
            DbConversion cv = db.conversion(t.text(row, "auth_name"), t.text(row, "code"));
            if (cv == null) {
                fail(ref(t, row) + " missing");
                continue;
            }
            eq(ref(t, row) + ".name", t.text(row, "name"), cv.name());
            String ma = t.text(row, "method_auth_name");
            String mc = t.text(row, "method_code");
            eq(ref(t, row) + ".method_auth_name", ma, cv.methodAuthName());
            eq(ref(t, row) + ".method_code", mc, cv.methodCode());
            eq(ref(t, row) + ".method_name",
                    ma == null || mc == null ? null : methodNames.get(ma + ' ' + mc),
                    cv.methodName());
            int expected = 0;
            for (int i = 1; i <= 7; i++) {
                String pa = t.text(row, "param" + i + "_auth_name");
                String pc = t.text(row, "param" + i + "_code");
                if (pa == null || pc == null) {
                    continue;
                }
                if (expected >= cv.parameters().size()) {
                    fail(ref(t, row) + ": param" + i + " missing from the index");
                    expected++;
                    continue;
                }
                DbParam p = cv.parameters().get(expected);
                String what = ref(t, row) + ".param" + i;
                eq(what + ".auth", pa, p.authName());
                eq(what + ".code", pc, p.code());
                eq(what + ".name", paramNames.get(pa + ' ' + pc), p.name());
                eqDouble(what + ".value", t.real(row, "param" + i + "_value"), p.value());
                refEq(what + ".uom", t.text(row, "param" + i + "_uom_auth_name"),
                        t.text(row, "param" + i + "_uom_code"), p.unit());
                expected++;
            }
            eq(ref(t, row) + " param count", Integer.valueOf(expected),
                    Integer.valueOf(cv.parameters().size()));
        }
    }

    private static void verifyOperations(QuoteDump d, PjdxDatabase db) {
        verifyTransformation(d.table("grid_transformation"), db, DbObjectType.GRID_TRANSFORMATION, 2,
                new String[]{"grid_name", "grid2_name"});
        verifyTransformation(d.table("other_transformation"), db, DbObjectType.OTHER_TRANSFORMATION, 9,
                new String[]{"grid_name"});
        verifyHelmert(d, db);
        verifyConcatenated(d, db);
    }

    private static void verifyTransformation(Table t, PjdxDatabase db, DbObjectType kind,
                                             int maxParams, String[] gridColumns) {
        for (Object[] row : t.rows) {
            DbOperation op = db.operation(t.text(row, "auth_name"), t.text(row, "code"));
            if (op == null) {
                fail(ref(t, row) + " missing");
                continue;
            }
            eq(ref(t, row) + ".kind", kind, op.kind());
            eq(ref(t, row) + ".name", t.text(row, "name"), op.name());
            eq(ref(t, row) + ".method_auth_name", t.text(row, "method_auth_name"),
                    op.methodAuthName());
            eq(ref(t, row) + ".method_code", t.text(row, "method_code"), op.methodCode());
            eq(ref(t, row) + ".method_name", t.text(row, "method_name"), op.methodName());
            refEq(ref(t, row) + ".source_crs", t.text(row, "source_crs_auth_name"),
                    t.text(row, "source_crs_code"), op.sourceCrs());
            refEq(ref(t, row) + ".target_crs", t.text(row, "target_crs_auth_name"),
                    t.text(row, "target_crs_code"), op.targetCrs());
            eqDouble(ref(t, row) + ".accuracy", t.real(row, "accuracy"), op.accuracy());
            eq(ref(t, row) + ".operation_version", t.text(row, "operation_version"),
                    op.operationVersion());
            eq(ref(t, row) + ".deprecated", Boolean.valueOf(t.bool(row, "deprecated")),
                    Boolean.valueOf(op.deprecated()));
            refEq(ref(t, row) + ".interpolation_crs", t.text(row, "interpolation_crs_auth_name"),
                    t.text(row, "interpolation_crs_code"), op.interpolationCrs());

            List<String> expectedGrids = new ArrayList<String>();
            for (String col : gridColumns) {
                String g = t.text(row, col);
                if (g != null && !g.isEmpty()) {
                    expectedGrids.add(g);
                }
            }
            eq(ref(t, row) + ".grids", expectedGrids, op.gridNames());

            int n = 0;
            for (int i = 1; i <= maxParams; i++) {
                String pa = t.text(row, "param" + i + "_auth_name");
                String pc = t.text(row, "param" + i + "_code");
                if (pa == null || pc == null) {
                    continue;
                }
                if (n >= op.parameters().size()) {
                    fail(ref(t, row) + ": param" + i + " missing");
                    n++;
                    continue;
                }
                DbParam p = op.parameters().get(n);
                String what = ref(t, row) + ".param" + i;
                eq(what + ".auth", pa, p.authName());
                eq(what + ".code", pc, p.code());
                eq(what + ".name", t.text(row, "param" + i + "_name"), p.name());
                eqDouble(what + ".value", t.real(row, "param" + i + "_value"), p.value());
                refEq(what + ".uom", t.text(row, "param" + i + "_uom_auth_name"),
                        t.text(row, "param" + i + "_uom_code"), p.unit());
                n++;
            }
            eq(ref(t, row) + " param count", Integer.valueOf(n),
                    Integer.valueOf(op.parameters().size()));

            verifyFoundBetween(db, t, row, op);
        }
    }

    /**
     * Helmert rows are checked two ways: the head fields directly, and then the parameter list against
     * the source columns <em>by parameter code</em> rather than by position. Checking by code is the
     * point — a positional check would pass even if the branch structure emitted the wrong parameter.
     */
    private static void verifyHelmert(QuoteDump d, PjdxDatabase db) {
        Table t = d.table("helmert_transformation_table");
        Map<String, String> methodNames = new java.util.HashMap<String, String>();
        Table m = d.table("coordinate_operation_method");
        for (Object[] row : m.rows) {
            methodNames.put(m.text(row, "auth_name") + ' ' + m.text(row, "code"),
                    m.text(row, "name"));
        }
        String[][] byCode = {
                {"8605", "tx", "translation"}, {"8606", "ty", "translation"},
                {"8607", "tz", "translation"},
                {"8608", "rx", "rotation"}, {"8609", "ry", "rotation"}, {"8610", "rz", "rotation"},
                {"8611", "scale_difference", "scale_difference"},
                {"1040", "rate_tx", "rate_translation"}, {"1041", "rate_ty", "rate_translation"},
                {"1042", "rate_tz", "rate_translation"},
                {"1043", "rate_rx", "rate_rotation"}, {"1044", "rate_ry", "rate_rotation"},
                {"1045", "rate_rz", "rate_rotation"},
                {"1046", "rate_scale_difference", "rate_scale_difference"},
                {"1047", "epoch", "epoch"}, {"1049", "epoch", "epoch"},
                {"8617", "px", "pivot"}, {"8618", "py", "pivot"}, {"8667", "pz", "pivot"}};
        Map<String, String[]> spec = new java.util.HashMap<String, String[]>();
        for (String[] s : byCode) {
            spec.put(s[0], s);
        }
        for (Object[] row : t.rows) {
            DbOperation op = db.operation(t.text(row, "auth_name"), t.text(row, "code"));
            if (op == null) {
                fail(ref(t, row) + " missing");
                continue;
            }
            eq(ref(t, row) + ".kind", DbObjectType.HELMERT_TRANSFORMATION, op.kind());
            eq(ref(t, row) + ".name", t.text(row, "name"), op.name());
            String ma = t.text(row, "method_auth_name");
            String mc = t.text(row, "method_code");
            eq(ref(t, row) + ".method_name",
                    ma == null || mc == null ? null : methodNames.get(ma + ' ' + mc),
                    op.methodName());
            refEq(ref(t, row) + ".source_crs", t.text(row, "source_crs_auth_name"),
                    t.text(row, "source_crs_code"), op.sourceCrs());
            refEq(ref(t, row) + ".target_crs", t.text(row, "target_crs_auth_name"),
                    t.text(row, "target_crs_code"), op.targetCrs());
            eqDouble(ref(t, row) + ".accuracy", t.real(row, "accuracy"), op.accuracy());
            eq(ref(t, row) + ".deprecated", Boolean.valueOf(t.bool(row, "deprecated")),
                    Boolean.valueOf(op.deprecated()));
            isTrue(ref(t, row) + " must have at least tx/ty/tz", op.parameters().size() >= 3);
            Set<String> seen = new HashSet<String>();
            for (DbParam p : op.parameters()) {
                eq(ref(t, row) + " param authority", "EPSG", p.authName());
                String[] s = spec.get(p.code());
                if (s == null) {
                    fail(ref(t, row) + ": unexpected Helmert parameter code " + p.code());
                    continue;
                }
                isTrue(ref(t, row) + ": duplicate parameter " + p.code(), seen.add(p.code()));
                eqDouble(ref(t, row) + " param " + p.code() + " (" + s[1] + ")",
                        t.real(row, s[1]), p.value());
                refEq(ref(t, row) + " param " + p.code() + " uom",
                        t.text(row, s[2] + "_uom_auth_name"), t.text(row, s[2] + "_uom_code"),
                        p.unit());
                // A parameter PROJ would emit always has a value. A NaN here means the branch
                // structure emitted a slot the source does not populate.
                isTrue(ref(t, row) + " param " + p.code() + " has a value",
                        !Double.isNaN(p.value()));
            }
            verifyFoundBetween(db, t, row, op);
        }
    }

    private static void verifyConcatenated(QuoteDump d, PjdxDatabase db) {
        Table t = d.table("concatenated_operation");
        for (Object[] row : t.rows) {
            DbOperation op = db.operation(t.text(row, "auth_name"), t.text(row, "code"));
            if (op == null) {
                fail(ref(t, row) + " missing");
                continue;
            }
            eq(ref(t, row) + ".kind", DbObjectType.CONCATENATED_OPERATION, op.kind());
            eq(ref(t, row) + ".name", t.text(row, "name"), op.name());
            refEq(ref(t, row) + ".source_crs", t.text(row, "source_crs_auth_name"),
                    t.text(row, "source_crs_code"), op.sourceCrs());
            refEq(ref(t, row) + ".target_crs", t.text(row, "target_crs_auth_name"),
                    t.text(row, "target_crs_code"), op.targetCrs());
            eqDouble(ref(t, row) + ".accuracy", t.real(row, "accuracy"), op.accuracy());
            eq(ref(t, row) + ".operation_version", t.text(row, "operation_version"),
                    op.operationVersion());
            eq(ref(t, row) + ".method is null", Boolean.TRUE,
                    Boolean.valueOf(op.methodName() == null));
            isTrue(ref(t, row) + " must have steps", !op.steps().isEmpty());
            verifyFoundBetween(db, t, row, op);
        }
    }

    private static void verifyConcatenatedSteps(QuoteDump d, PjdxDatabase db) {
        Table s = d.table("concatenated_operation_step");
        for (Object[] row : s.rows) {
            String oa = s.text(row, "operation_auth_name");
            String oc = s.text(row, "operation_code");
            int number = s.integer(row, "step_number");
            DbOperation op = db.operation(oa, oc);
            String what = "step " + number + " of " + oa + ":" + oc;
            if (op == null) {
                fail(what + ": operation missing");
                continue;
            }
            // Located by step_number, not by position. Two upstream operations --
            // NKG:ITRF2000_TO_NKG_ETRF00 and NKG:ITRF2014_TO_NKG_ETRF14 -- number their two steps 2
            // and 3, with no step 1. So step_number is NOT a 1-based index into the list, and a
            // consumer that treats it as one reads the wrong step for those two.
            DbOperationStep st = null;
            for (DbOperationStep candidate : op.steps()) {
                if (candidate.stepNumber() == number) {
                    st = candidate;
                    break;
                }
            }
            if (st == null) {
                fail(what + " missing");
                continue;
            }
            eq(what + ".step_number", Integer.valueOf(number), Integer.valueOf(st.stepNumber()));
            refEq(what + ".step", s.text(row, "step_auth_name"), s.text(row, "step_code"), st.step());
            String dir = s.text(row, "step_direction");
            eq(what + ".direction",
                    dir == null ? DbOperationStep.Direction.UNSPECIFIED
                            : DbOperationStep.Direction.fromDbValue(dir),
                    st.direction());
            // The step's table tag was resolved at build time; check it points at something real.
            isTrue(what + ": referenced object exists",
                    st.step().type() == DbObjectType.CONVERSION
                            ? db.conversion(st.step().authName(), st.step().code()) != null
                            : db.operation(st.step().authName(), st.step().code()) != null);
        }
    }

    /** Every operation must be findable through the source-to-target index. */
    private static void verifyFoundBetween(PjdxDatabase db, Table t, Object[] row, DbOperation op) {
        String sa = t.text(row, "source_crs_auth_name");
        String sc = t.text(row, "source_crs_code");
        String ta = t.text(row, "target_crs_auth_name");
        String tc = t.text(row, "target_crs_code");
        List<DbOperation> found = db.operationsBetween(sa, sc, ta, tc);
        boolean present = false;
        for (DbOperation o : found) {
            if (o.ref().equals(op.ref())) {
                present = true;
                break;
            }
        }
        isTrue(ref(t, row) + " not returned by operationsBetween(" + sa + ":" + sc + ", " + ta + ":"
                + tc + ")", present);
        List<DbObjectRef> fromSource = db.operationsWithSourceCrs(sa, sc);
        isTrue(ref(t, row) + " not in operationsWithSourceCrs", fromSource.contains(op.ref()));
        List<DbObjectRef> toTarget = db.operationsWithTargetCrs(ta, tc);
        isTrue(ref(t, row) + " not in operationsWithTargetCrs", toTarget.contains(op.ref()));
    }

    private static void verifyExtents(QuoteDump d, PjdxDatabase db) {
        Table t = d.table("extent");
        for (Object[] row : t.rows) {
            DbExtent e = db.extent(t.text(row, "auth_name"), t.text(row, "code"));
            if (e == null) {
                fail(ref(t, row) + " missing");
                continue;
            }
            eq(ref(t, row) + ".name", t.text(row, "name"), e.name());
            eq(ref(t, row) + ".description", t.text(row, "description"), e.description());
            eqDouble(ref(t, row) + ".west_lon", t.real(row, "west_lon"), e.westLongitude());
            eqDouble(ref(t, row) + ".south_lat", t.real(row, "south_lat"), e.southLatitude());
            eqDouble(ref(t, row) + ".east_lon", t.real(row, "east_lon"), e.eastLongitude());
            eqDouble(ref(t, row) + ".north_lat", t.real(row, "north_lat"), e.northLatitude());
            eq(ref(t, row) + ".deprecated", Boolean.valueOf(t.bool(row, "deprecated")),
                    Boolean.valueOf(e.deprecated()));
        }
    }

    private static void verifyUsages(QuoteDump d, PjdxDatabase db) {
        Table t = d.table("usage");
        for (Object[] row : t.rows) {
            DbObjectType type = DbObjectType.fromDbName(t.text(row, "object_table_name"));
            if (type == null) {
                fail("usage references unknown table " + t.text(row, "object_table_name"));
                continue;
            }
            DbObjectRef object = new DbObjectRef(type, t.text(row, "object_auth_name"),
                    t.text(row, "object_code"));
            List<DbExtent> extents = db.extentsFor(object);
            String wantAuth = t.text(row, "extent_auth_name");
            String wantCode = t.text(row, "extent_code");
            boolean found = false;
            for (DbExtent e : extents) {
                if (wantAuth.equals(e.authName()) && wantCode.equals(e.code())) {
                    found = true;
                    break;
                }
            }
            isTrue("extentsFor(" + object + ") is missing " + wantAuth + ":" + wantCode, found);
        }
    }

    private static void verifyGridAlternatives(QuoteDump d, PjdxDatabase db) {
        Table t = d.table("grid_alternatives");
        for (Object[] row : t.rows) {
            String original = t.text(row, "original_grid_name");
            DbGridAlternative g = db.gridAlternative(original);
            if (g == null) {
                fail("grid_alternatives " + original + " missing");
                continue;
            }
            eq(original + ".proj_grid_name", t.text(row, "proj_grid_name"), g.projGridName());
            eq(original + ".old_proj_grid_name", t.text(row, "old_proj_grid_name"),
                    g.oldProjGridName());
            eq(original + ".proj_grid_format", t.text(row, "proj_grid_format"), g.projGridFormat());
            eq(original + ".proj_method", t.text(row, "proj_method"), g.projMethod());
            eq(original + ".inverse_direction", Boolean.valueOf(t.bool(row, "inverse_direction")),
                    Boolean.valueOf(g.inverseDirection()));
            eq(original + ".url", t.text(row, "url"), g.url());
            eq(original + ".direct_download", t.tri(row, "direct_download"), g.directDownload());
            eq(original + ".open_license", t.tri(row, "open_license"), g.openLicense());
            eq(original + ".directory", t.text(row, "directory"), g.directory());
        }
        eq("gridAlternatives() size", Integer.valueOf(t.rows.size()),
                Integer.valueOf(db.gridAlternatives().size()));
    }

    private static void verifyAliases(QuoteDump d, PjdxDatabase db) {
        Table t = d.table("alias_name");
        for (Object[] row : t.rows) {
            DbObjectType type = DbObjectType.fromDbName(t.text(row, "table_name"));
            if (type == null) {
                fail("alias_name references unknown table " + t.text(row, "table_name"));
                continue;
            }
            DbObjectRef object = new DbObjectRef(type, t.text(row, "auth_name"), t.text(row, "code"));
            isTrue("aliases(" + object + ") is missing '" + t.text(row, "alt_name") + "'",
                    db.aliases(object).contains(t.text(row, "alt_name")));
        }
    }

    private static void verifySupersessions(QuoteDump d, PjdxDatabase db) {
        Table t = d.table("supersession");
        for (Object[] row : t.rows) {
            DbObjectType type = DbObjectType.fromDbName(t.text(row, "superseded_table_name"));
            DbObjectType replType = DbObjectType.fromDbName(t.text(row, "replacement_table_name"));
            if (type == null || replType == null) {
                fail("supersession references unknown table");
                continue;
            }
            DbObjectRef object = new DbObjectRef(type, t.text(row, "superseded_auth_name"),
                    t.text(row, "superseded_code"));
            DbObjectRef expected = new DbObjectRef(replType, t.text(row, "replacement_auth_name"),
                    t.text(row, "replacement_code"));
            boolean found = false;
            for (DbSupersession s : db.supersededBy(object)) {
                if (s.replacement().equals(expected)) {
                    found = true;
                    eq("supersession " + object + " -> " + expected + " .source",
                            t.text(row, "source"), s.source());
                    eq("supersession " + object + " -> " + expected + " .same_source_target_crs",
                            Boolean.valueOf(t.bool(row, "same_source_target_crs")),
                            Boolean.valueOf(s.sameSourceTargetCrs()));
                    break;
                }
            }
            isTrue("supersededBy(" + object + ") is missing " + expected, found);
        }
    }

    private static void verifyDeprecations(QuoteDump d, PjdxDatabase db) {
        Table t = d.table("deprecation");
        for (Object[] row : t.rows) {
            DbObjectType type = DbObjectType.fromDbName(t.text(row, "table_name"));
            if (type == null) {
                fail("deprecation references unknown table " + t.text(row, "table_name"));
                continue;
            }
            DbObjectRef object = new DbObjectRef(type, t.text(row, "deprecated_auth_name"),
                    t.text(row, "deprecated_code"));
            DbObjectRef expected = new DbObjectRef(type, t.text(row, "replacement_auth_name"),
                    t.text(row, "replacement_code"));
            isTrue("replacementsFor(" + object + ") is missing " + expected,
                    db.replacementsFor(object).contains(expected));
        }
    }

    private static void verifyNameIndex(QuoteDump d, PjdxDatabase db) {
        String[] tables = {"geodetic_crs", "projected_crs", "vertical_crs", "compound_crs",
                "engineering_crs"};
        DbObjectType[] types = {DbObjectType.GEODETIC_CRS, DbObjectType.PROJECTED_CRS,
                DbObjectType.VERTICAL_CRS, DbObjectType.COMPOUND_CRS, DbObjectType.ENGINEERING_CRS};
        for (int i = 0; i < tables.length; i++) {
            Table t = d.table(tables[i]);
            for (Object[] row : t.rows) {
                DbObjectRef expected = new DbObjectRef(types[i], t.text(row, "auth_name"),
                        t.text(row, "code"));
                isTrue("findCrsByName('" + t.text(row, "name") + "') is missing " + expected,
                        db.findCrsByName(t.text(row, "name")).contains(expected));
            }
        }
    }

    private static void verifyCrsByDatum(QuoteDump d, PjdxDatabase db) {
        Table g = d.table("geodetic_crs");
        for (Object[] row : g.rows) {
            String da = g.text(row, "datum_auth_name");
            String dc = g.text(row, "datum_code");
            if (da == null || dc == null) {
                continue;
            }
            DbObjectRef expected = new DbObjectRef(DbObjectType.GEODETIC_CRS,
                    g.text(row, "auth_name"), g.text(row, "code"));
            isTrue("crsUsingDatum(GEODETIC_DATUM, " + da + ":" + dc + ") is missing " + expected,
                    db.crsUsingDatum(DbObjectType.GEODETIC_DATUM, da, dc).contains(expected));
        }
        Table v = d.table("vertical_crs");
        for (Object[] row : v.rows) {
            String da = v.text(row, "datum_auth_name");
            String dc = v.text(row, "datum_code");
            DbObjectRef expected = new DbObjectRef(DbObjectType.VERTICAL_CRS,
                    v.text(row, "auth_name"), v.text(row, "code"));
            isTrue("crsUsingDatum(VERTICAL_DATUM, " + da + ":" + dc + ") is missing " + expected,
                    db.crsUsingDatum(DbObjectType.VERTICAL_DATUM, da, dc).contains(expected));
        }
    }

    private static void verifyAuthorities(QuoteDump d, PjdxDatabase db) {
        Set<String> expected = new java.util.TreeSet<String>();
        String[] tables = {"geodetic_crs", "projected_crs", "vertical_crs", "compound_crs",
                "engineering_crs", "helmert_transformation_table", "grid_transformation",
                "other_transformation", "concatenated_operation"};
        for (String name : tables) {
            Table t = d.table(name);
            for (Object[] row : t.rows) {
                expected.add(t.text(row, "auth_name"));
            }
        }
        for (String a : expected) {
            isTrue("authorities() is missing " + a, db.authorities().contains(a));
        }
    }
}

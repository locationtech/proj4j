/*
 * Copyright 2026 the Proj4J contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.locationtech.proj4j.golden;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Self-tests for the merge join and the rules engine, on hand-built two-row tables.
 *
 * <p>Every one of these asserts a gate that the suite's usefulness depends on. If the rules engine can
 * be made to say "explained" when it should not, the whole regime is decoration — so the tests that
 * matter most here are the ones proving a rule <em>cannot</em> claim a row it did not describe.
 */
public class GoldenDiffTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ---------------------------------------------------------------------------- fixtures

    /** {@code status,fx,fy,fz,ix,iy,iz,inside} for one row, defaulting to a plain OK zero row. */
    private static String row(String key, int probe, String status, double fy, String inside) {
        return "REG\t" + key + "\t" + probe + "\t" + status
                + "\t" + GoldenFormat.hex(1.0)
                + "\t" + GoldenFormat.hex(fy)
                + "\t" + GoldenFormat.hex(0.0)
                + "\t" + GoldenFormat.hex(2.0)
                + "\t" + GoldenFormat.hex(3.0)
                + "\t" + GoldenFormat.hex(0.0)
                + "\t" + inside;
    }

    private File table(String name, String... rows) throws IOException {
        File dir = tmp.newFolder(name);
        Writer w = GoldenFormat.writer(new File(dir, GoldenFormat.GOLDEN_FILE));
        try {
            w.write(GoldenFormat.HEADER_GOLDEN);
            w.write('\n');
            for (int i = 0; i < rows.length; i++) {
                w.write(rows[i]);
                w.write('\n');
            }
        } finally {
            w.close();
        }
        Writer idx = GoldenFormat.writer(new File(dir, GoldenFormat.INDEX_FILE));
        try {
            idx.write(GoldenFormat.HEADER_INDEX);
            idx.write('\n');
            GoldenFormat.writeRow(idx, "REG", "epsg:1000", "longlat", "merc", "ellps,proj,rf", "");
            GoldenFormat.writeRow(idx, "REG", "epsg:2000", "longlat", "tmerc",
                    "datum,lat_0,proj", "NAD27");
        } finally {
            idx.close();
        }
        return dir;
    }

    private File rules(String body) throws IOException {
        File f = tmp.newFile("rules-" + System.nanoTime() + ".yaml");
        Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(f), "UTF-8");
        try {
            w.write(body);
        } finally {
            w.close();
        }
        return f;
    }

    private GoldenDiff.Result diff(File base, File cur, File rulesFile) throws IOException {
        return GoldenDiff.run(base, cur, rulesFile,
                new File(tmp.getRoot(), "report-" + System.nanoTime() + ".tsv"), new Date());
    }

    private static final String NO_RULES = "version: 1\nrules: []\n";

    // ------------------------------------------------------------------------ classification

    @Test
    public void classifiesUnchangedChangedAddedRemoved() throws Exception {
        File base = table("b",
                row("epsg:1000", 0, "OK", 10.0, "T"),
                row("epsg:2000", 0, "OK", 20.0, "T"),
                row("epsg:3000", 0, "OK", 30.0, "T"));
        File cur = table("c",
                row("epsg:1000", 0, "OK", 10.0, "T"),      // UNCHANGED
                row("epsg:2000", 0, "OK", 20.5, "T"),      // CHANGED
                row("epsg:4000", 0, "OK", 40.0, "T"));     // ADDED (and epsg:3000 REMOVED)
        GoldenDiff.Result r = diff(base, cur, rules(NO_RULES));
        assertEquals(1, r.unchanged);
        assertEquals(1, r.changed);
        assertEquals(1, r.added);
        assertEquals(1, r.removed);
        assertEquals(3, r.unexplained);
        assertFalse(r.ok());
    }

    /**
     * A one-ULP move is a change. There is deliberately no epsilon anywhere in this comparison: the
     * suite measures change, and gie measures correctness.
     */
    @Test
    public void oneUlpIsAChange() throws Exception {
        File base = table("b", row("epsg:1000", 0, "OK", 1234.5, "T"));
        File cur = table("c", row("epsg:1000", 0, "OK", Math.nextUp(1234.5), "T"));
        GoldenDiff.Result r = diff(base, cur, rules(NO_RULES));
        assertEquals(1, r.changed);
        assertEquals(0, r.unchanged);
    }

    @Test
    public void signedZeroIsAChange() throws Exception {
        File base = table("b", row("epsg:1000", 0, "OK", 0.0, "T"));
        File cur = table("c", row("epsg:1000", 0, "OK", -0.0, "T"));
        assertEquals(1, diff(base, cur, rules(NO_RULES)).changed);
    }

    @Test
    public void noChangesAndNoRulesIsGreen() throws Exception {
        File base = table("b", row("epsg:1000", 0, "OK", 10.0, "T"));
        File cur = table("c", row("epsg:1000", 0, "OK", 10.0, "T"));
        GoldenDiff.Result r = diff(base, cur, rules(NO_RULES));
        assertTrue(r.summary(), r.ok());
        assertEquals(0, r.exitCode());
    }

    /** A mis-sorted table must be a hard error, not thousands of phantom ADDED plus REMOVED. */
    @Test
    public void outOfOrderRowsAreRejected() throws Exception {
        File base = table("b",
                row("epsg:2000", 0, "OK", 10.0, "T"),
                row("epsg:1000", 0, "OK", 10.0, "T"));
        File cur = table("c", row("epsg:1000", 0, "OK", 10.0, "T"));
        try {
            diff(base, cur, rules(NO_RULES));
            fail("expected an out-of-order failure");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("out of order"));
        }
    }

    @Test
    public void duplicateRowKeysAreRejected() throws Exception {
        File base = table("b",
                row("epsg:1000", 0, "OK", 10.0, "T"),
                row("epsg:1000", 0, "OK", 11.0, "T"));
        File cur = table("c", row("epsg:1000", 0, "OK", 10.0, "T"));
        try {
            diff(base, cur, rules(NO_RULES));
            fail("expected a duplicate-key failure");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("duplicate"));
        }
    }

    // ------------------------------------------------------------------------- rule gating

    private static String rule(String extra) {
        return "version: 1\nrules:\n"
                + "  - id: TEST-RULE\n"
                + "    reason: a test\n"
                + "    expires: 2099-01-01\n"
                + extra;
    }

    @Test
    public void aMatchingRuleExplainsTheRow() throws Exception {
        File base = table("b", row("epsg:2000", 0, "OK", 20.0, "T"));
        File cur = table("c", row("epsg:2000", 0, "OK", 20.5, "T"));
        GoldenDiff.Result r = diff(base, cur, rules(rule(
                "    expected_rows: 1\n"
                        + "    match:\n"
                        + "      sections: [REG]\n"
                        + "    expect:\n"
                        + "      dimensions: [fy]\n")));
        assertTrue(r.summary() + " " + r.failures, r.ok());
        assertEquals(1, r.intended);
        assertEquals(0, r.unexplained);
    }

    @Test
    public void aRuleMatchingNothingIsADeadRule() throws Exception {
        File base = table("b", row("epsg:1000", 0, "OK", 10.0, "T"));
        File cur = table("c", row("epsg:1000", 0, "OK", 10.0, "T"));
        GoldenDiff.Result r = diff(base, cur, rules(rule(
                "    expected_rows: TBD\n    match:\n      sections: [REG]\n")));
        assertFalse(r.ok());
        assertTrue(r.failures.toString(), r.failures.get(0).startsWith("DEAD_RULE TEST-RULE"));
    }

    /** {@code status: pending} exempts a not-yet-landed change from DEAD_RULE, and nothing else. */
    @Test
    public void pendingRuleMayMatchNothing() throws Exception {
        File base = table("b", row("epsg:1000", 0, "OK", 10.0, "T"));
        File cur = table("c", row("epsg:1000", 0, "OK", 10.0, "T"));
        GoldenDiff.Result r = diff(base, cur, rules(rule(
                "    status: pending\n    expected_rows: TBD\n    match:\n      sections: [REG]\n")));
        assertTrue(r.failures.toString(), r.ok());
    }

    @Test
    public void pendingRuleThatFiresIsAFailure() throws Exception {
        File base = table("b", row("epsg:2000", 0, "OK", 20.0, "T"));
        File cur = table("c", row("epsg:2000", 0, "OK", 20.5, "T"));
        GoldenDiff.Result r = diff(base, cur, rules(rule(
                "    status: pending\n    expected_rows: TBD\n    match:\n      sections: [REG]\n")));
        assertFalse(r.ok());
        assertTrue(r.failures.toString(), r.failures.get(0).startsWith("PENDING_RULE_FIRED"));
    }

    /** The count is two-sided: too few is as much a failure as too many. */
    @Test
    public void countMismatchFailsInBothDirections() throws Exception {
        File base = table("b",
                row("epsg:1000", 0, "OK", 10.0, "T"),
                row("epsg:2000", 0, "OK", 20.0, "T"));
        File cur = table("c",
                row("epsg:1000", 0, "OK", 10.5, "T"),
                row("epsg:2000", 0, "OK", 20.5, "T"));
        String body = "    expected_rows: %d\n    match:\n      sections: [REG]\n";
        GoldenDiff.Result tooFew = diff(base, cur, rules(rule(String.format(body, 1))));
        assertFalse(tooFew.ok());
        assertTrue(tooFew.failures.toString(),
                tooFew.failures.get(0).startsWith("COUNT_MISMATCH TEST-RULE expected_rows=1 but matched 2"));

        GoldenDiff.Result tooMany = diff(base, cur, rules(rule(String.format(body, 3))));
        assertFalse(tooMany.ok());
        assertTrue(tooMany.failures.toString(),
                tooMany.failures.get(0).startsWith("COUNT_MISMATCH TEST-RULE expected_rows=3 but matched 2"));

        GoldenDiff.Result exact = diff(base, cur, rules(rule(String.format(body, 2))));
        assertTrue(exact.failures.toString(), exact.ok());
    }

    @Test
    public void expiredRuleFails() throws Exception {
        File base = table("b", row("epsg:2000", 0, "OK", 20.0, "T"));
        File cur = table("c", row("epsg:2000", 0, "OK", 20.5, "T"));
        File f = rules("version: 1\nrules:\n"
                + "  - id: OLD\n    reason: r\n    expires: 2020-01-01\n"
                + "    expected_rows: 1\n    match:\n      sections: [REG]\n");
        GoldenDiff.Result r = GoldenDiff.run(base, cur, f,
                new File(tmp.getRoot(), "rep.tsv"), new Date());
        assertFalse(r.ok());
        assertTrue(r.failures.toString(), r.failures.get(0).startsWith("EXPIRED_RULE OLD"));
    }

    // -------------------------------------------- the crux: a rule cannot hide something else

    @Test
    public void aRowMovingInAnUnsanctionedDimensionIsNotExplained() throws Exception {
        // The rule sanctions fy. The row moved fy AND ix.
        File base = table("b", "REG\tepsg:2000\t0\tOK\t"
                + GoldenFormat.hex(1.0) + "\t" + GoldenFormat.hex(20.0) + "\t" + GoldenFormat.hex(0.0)
                + "\t" + GoldenFormat.hex(2.0) + "\t" + GoldenFormat.hex(3.0) + "\t"
                + GoldenFormat.hex(0.0) + "\tT");
        File cur = table("c", "REG\tepsg:2000\t0\tOK\t"
                + GoldenFormat.hex(1.0) + "\t" + GoldenFormat.hex(20.5) + "\t" + GoldenFormat.hex(0.0)
                + "\t" + GoldenFormat.hex(99.0) + "\t" + GoldenFormat.hex(3.0) + "\t"
                + GoldenFormat.hex(0.0) + "\tT");
        GoldenDiff.Result r = diff(base, cur, rules(rule(
                "    expected_rows: 1\n    match:\n      sections: [REG]\n"
                        + "    expect:\n      dimensions: [fy]\n")));
        assertFalse("the rule must decline, not claim, this row", r.ok());
        assertEquals(1, r.unexplained);
        assertEquals(0, r.intended);
        // and the DEAD_RULE message must carry the decline reason, so the reviewer can see why
        boolean explained = false;
        for (int i = 0; i < r.failures.size(); i++) {
            if (r.failures.get(i).contains("declined:") && r.failures.get(i).contains("'ix'")) {
                explained = true;
            }
        }
        assertTrue(r.failures.toString(), explained);
    }

    @Test
    public void aStatusChangeNeedsExplicitPermission() throws Exception {
        File base = table("b", row("epsg:2000", 0, "OK", 20.0, "T"));
        File cur = table("c", row("epsg:2000", 0, "EXC:java.lang.NullPointerException", 20.0, "T"));
        String match = "    expected_rows: 1\n    match:\n      sections: [REG]\n";
        assertFalse(diff(base, cur, rules(rule(match))).ok());
        assertTrue(diff(base, cur, rules(rule(match
                + "    expect:\n      allow_status_change: true\n"))).ok());
    }

    @Test
    public void anInsideChangeNeedsExplicitPermission() throws Exception {
        File base = table("b", row("epsg:2000", 0, "OK", 20.0, "T"));
        File cur = table("c", row("epsg:2000", 0, "OK", 20.0, "F"));
        String match = "    expected_rows: 1\n    match:\n      sections: [REG]\n";
        assertFalse(diff(base, cur, rules(rule(match))).ok());
        assertTrue(diff(base, cur, rules(rule(match
                + "    expect:\n      allow_inside_change: true\n"))).ok());
    }

    @Test
    public void aNonFiniteEndpointNeedsExplicitPermission() throws Exception {
        File base = table("b", row("epsg:2000", 0, "OK", 20.0, "T"));
        File cur = table("c", row("epsg:2000", 0, "OK", Double.NaN, "T"));
        String match = "    expected_rows: 1\n    match:\n      sections: [REG]\n";
        assertFalse(diff(base, cur, rules(rule(match))).ok());
        assertTrue(diff(base, cur, rules(rule(match
                + "    expect:\n      allow_nonfinite: true\n"))).ok());
    }

    @Test
    public void magnitudeBandIsEnforcedAtBothEnds() throws Exception {
        File base = table("b", row("epsg:2000", 0, "OK", 20.0, "T"));
        File cur = table("c", row("epsg:2000", 0, "OK", 20.5, "T"));
        String match = "    expected_rows: 1\n    match:\n      sections: [REG]\n    expect:\n"
                + "      dimensions: [fy]\n      magnitude: {min: %s, max: %s}\n";
        assertTrue(diff(base, cur, rules(rule(String.format(match, "0.1", "1.0")))).ok());
        assertFalse("0.5 is below min 1.0",
                diff(base, cur, rules(rule(String.format(match, "1.0", "10.0")))).ok());
        assertFalse("0.5 is above max 0.1",
                diff(base, cur, rules(rule(String.format(match, "0.0", "0.1")))).ok());
    }

    @Test
    public void firstMatchingRuleWins() throws Exception {
        File base = table("b", row("epsg:2000", 0, "OK", 20.0, "T"));
        File cur = table("c", row("epsg:2000", 0, "OK", 20.5, "T"));
        File f = rules("version: 1\nrules:\n"
                + "  - id: FIRST\n    reason: r\n    expires: 2099-01-01\n"
                + "    expected_rows: 1\n    match:\n      sections: [REG]\n"
                + "  - id: SECOND\n    reason: r\n    expires: 2099-01-01\n"
                + "    status: pending\n    expected_rows: TBD\n    match:\n      sections: [REG]\n");
        GoldenDiff.Result r = diff(base, cur, f);
        assertTrue(r.failures.toString(), r.ok());
        assertEquals(Integer.valueOf(1), r.perRule.get("FIRST"));
        assertEquals(Integer.valueOf(0), r.perRule.get("SECOND"));
    }

    // ----------------------------------------------------------------------- match predicates

    @Test
    public void matchPredicatesSelectPrecisely() throws Exception {
        File base = table("b",
                row("epsg:1000", 0, "OK", 10.0, "T"),
                row("epsg:2000", 1, "OK", 20.0, "T"));
        File cur = table("c",
                row("epsg:1000", 0, "OK", 10.5, "T"),
                row("epsg:2000", 1, "OK", 20.5, "T"));
        // params_present picks out the one whose index row declares +rf
        GoldenDiff.Result byParam = diff(base, cur, rules(rule(
                "    expected_rows: 1\n    match:\n      params_present: [rf]\n"
                        + "    expect:\n      dimensions: [fy]\n")));
        assertEquals(1, byParam.intended);
        // tgt_proj
        GoldenDiff.Result byProj = diff(base, cur, rules(rule(
                "    expected_rows: 1\n    match:\n      tgt_proj: [tmerc]\n"
                        + "    expect:\n      dimensions: [fy]\n")));
        assertEquals(1, byProj.intended);
        // probe index
        GoldenDiff.Result byProbe = diff(base, cur, rules(rule(
                "    expected_rows: 1\n    match:\n      probes: [1]\n"
                        + "    expect:\n      dimensions: [fy]\n")));
        assertEquals(1, byProbe.intended);
        // code range
        GoldenDiff.Result byCode = diff(base, cur, rules(rule(
                "    expected_rows: 1\n    match:\n      code_min: 1500\n      code_max: 2500\n"
                        + "    expect:\n      dimensions: [fy]\n")));
        assertEquals(1, byCode.intended);
        // params_absent
        GoldenDiff.Result byAbsent = diff(base, cur, rules(rule(
                "    expected_rows: 1\n    match:\n      params_absent: [rf]\n"
                        + "    expect:\n      dimensions: [fy]\n")));
        assertEquals(1, byAbsent.intended);
    }

    /**
     * The {@code datums} predicate, which matches a parameter's <b>value</b> rather than its key.
     *
     * <p>Both index rows in the fixture carry a {@code datum} <em>key</em> in {@code params} — so
     * {@code params_present: [datum]} cannot separate them, which is exactly why this predicate had
     * to exist. Only {@code epsg:2000} has the <em>value</em> {@code NAD27}.
     *
     * <p>The three negatives matter as much as the positive: an unlisted value must not match, a
     * value that is a prefix of the listed one must not match (the column is a set, not a substring
     * search), and {@code datums_absent} must be the complement.
     */
    @Test
    public void datumsMatchesTheValueAndNotTheKey() throws Exception {
        File base = table("b",
                row("epsg:1000", 0, "OK", 10.0, "T"),
                row("epsg:2000", 1, "OK", 20.0, "T"));
        File cur = table("c",
                row("epsg:1000", 0, "OK", 10.5, "T"),
                row("epsg:2000", 1, "OK", 20.5, "T"));

        GoldenDiff.Result byDatum = diff(base, cur, rules(rule(
                "    expected_rows: 1\n    match:\n      datums: [NAD27]\n"
                        + "    expect:\n      dimensions: [fy]\n")));
        assertEquals("only epsg:2000 has datum=NAD27", 1, byDatum.intended);

        GoldenDiff.Result absent = diff(base, cur, rules(rule(
                "    expected_rows: 1\n    match:\n      datums_absent: [NAD27]\n"
                        + "    expect:\n      dimensions: [fy]\n")));
        assertEquals("and exactly the other one does not", 1, absent.intended);

        GoldenDiff.Result unlisted = diff(base, cur, rules(rule(
                "    expected_rows: 0\n    match:\n      datums: [OSGB36]\n"
                        + "    expect:\n      dimensions: [fy]\n")));
        assertEquals(0, unlisted.intended);

        // A prefix of the stored value must not match: the column is a comma-separated SET.
        assertFalse(GoldenDiff.hasParam("NAD27", "NAD2"));
        assertFalse(GoldenDiff.hasParam("NAD27,WGS84", "AD27"));
        assertTrue(GoldenDiff.hasParam("NAD27,WGS84", "WGS84"));
        assertFalse(GoldenDiff.hasParam("", "NAD27"));
    }

    /**
     * {@code baseline/1.4.3/golden-index.tsv} was written before the {@code datums} column existed
     * and is a committed artefact that a normal run does not rewrite, so the reader has to accept
     * its five-column header — and must report an empty datum set for it rather than shifting the
     * columns left, which would silently make {@code params} answer {@code datums:} queries.
     */
    @Test
    public void theIndexReaderAcceptsTheHeaderThatPredatesTheDatumsColumn() throws Exception {
        File dir = tmp.newFolder("legacy-index");
        Writer idx = GoldenFormat.writer(new File(dir, GoldenFormat.INDEX_FILE));
        try {
            idx.write(GoldenFormat.HEADER_INDEX_V1);
            idx.write('\n');
            GoldenFormat.writeRow(idx, "REG", "epsg:2000", "longlat", "tmerc", "datum,proj");
        } finally {
            idx.close();
        }
        String[] row = GoldenDiff.readIndex(new File(dir, GoldenFormat.INDEX_FILE))
                .get("REG\tepsg:2000");
        assertEquals("longlat", row[0]);
        assertEquals("tmerc", row[1]);
        assertEquals("datum,proj", row[2]);
        assertEquals("a legacy row must yield NO datums, not the params column", "", row[3]);
    }

    /** A non-numeric code must never satisfy a numeric range; 47 {@code world} entries depend on it. */
    @Test
    public void nonNumericCodesNeverSatisfyACodeRange() {
        assertEquals(Long.MIN_VALUE, GoldenDiff.codeOf("world:CH1903"));
        assertEquals(Long.MIN_VALUE, GoldenDiff.codeOf("proj/merc"));
        assertEquals(4326L, GoldenDiff.codeOf("epsg:4326"));
        assertEquals("epsg", GoldenDiff.authorityOf("epsg:4326"));
        assertEquals("", GoldenDiff.authorityOf("proj/merc"));
    }

    @Test
    public void paramMatchingIsWholeTokenNotSubstring() {
        assertTrue(GoldenDiff.hasParam("ellps,proj,rf", "rf"));
        assertTrue(GoldenDiff.hasParam("ellps,proj,rf", "proj"));
        assertTrue(GoldenDiff.hasParam("ellps,proj,rf", "ellps"));
        assertFalse("'f' must not match 'rf'", GoldenDiff.hasParam("ellps,proj,rf", "f"));
        assertFalse("'ell' must not match 'ellps'", GoldenDiff.hasParam("ellps,proj,rf", "ell"));
        assertFalse(GoldenDiff.hasParam("", "rf"));
    }

    @Test
    public void globIsStarOnly() {
        assertTrue(GoldenRules.glob("mod/*/rf", "mod/merc/rf"));
        assertFalse("must anchor at the end", GoldenRules.glob("mod/*/f", "mod/merc/rf"));
        assertTrue(GoldenRules.glob("mod/*/f", "mod/merc/f"));
        assertTrue(GoldenRules.glob("*", "anything"));
        assertTrue(GoldenRules.glob("EXC:*", "EXC:java.lang.Error"));
        assertFalse(GoldenRules.glob("EXC:*", "OK"));
        assertTrue(GoldenRules.glob("t0*/*", "t04/epsg:4267>epsg:26731"));
        assertFalse(GoldenRules.glob("t0*/*", "t14/epsg:4267>epsg:26731"));
        // '.' and other regex metacharacters are literal
        assertFalse(GoldenRules.glob("a.c", "abc"));
        assertTrue(GoldenRules.glob("a.c", "a.c"));
    }
}

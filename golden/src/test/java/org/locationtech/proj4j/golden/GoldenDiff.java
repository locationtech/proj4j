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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Diffs a fresh golden table against a pinned baseline and decides whether every difference was
 * declared.
 *
 * <h2>Mechanism</h2>
 *
 * A streaming merge join on {@code (section, key, probe)}, in the total order
 * {@link GoldenFormat#compareRows} defines. Streaming rather than "load both into a
 * {@code HashMap}" for two reasons: it is O(1) in memory over the ~53,000-row tables (which will grow
 * as the dictionaries do), and — more importantly — it <b>verifies the sort order as it goes</b>. An
 * out-of-order row is a hard error, not a silently-missed join: a hash-map diff would happily report a
 * mis-sorted file as thousands of ADDED plus thousands of REMOVED and the reviewer would blame the
 * code under test.
 *
 * <p>Each joined row is classified:
 * <table>
 * <tr><td>{@code UNCHANGED}</td><td>present both sides, all eleven columns byte-identical</td></tr>
 * <tr><td>{@code CHANGED}</td><td>present both sides, at least one column differs</td></tr>
 * <tr><td>{@code ADDED}</td><td>current only</td></tr>
 * <tr><td>{@code REMOVED}</td><td>baseline only</td></tr>
 * </table>
 * Byte-identity is exact bit-identity because the columns are {@link Double#toHexString} output. There
 * is no epsilon here and there must not be: this suite measures <em>change</em>, and gie measures
 * correctness.
 *
 * <p>Non-{@code UNCHANGED} rows are then offered to each rule in {@code rules.yaml} in file order.
 * The first rule that both matches and expects the row tags it {@code INTENDED[<rule-id>]}. Anything
 * unclaimed is {@code UNEXPLAINED}.
 *
 * <h2>Exit code</h2>
 *
 * Non-zero on any of: an {@code UNEXPLAINED} row, a {@code DEAD_RULE} (matched nothing), an
 * {@code EXPIRED_RULE}, or a {@code COUNT_MISMATCH} against a pinned {@code expected_rows}. A run with
 * no changes at all and no rules is the steady state and exits zero.
 */
public final class GoldenDiff {

    public static final String UNCHANGED = "UNCHANGED";
    public static final String CHANGED = "CHANGED";
    public static final String ADDED = "ADDED";
    public static final String REMOVED = "REMOVED";

    private GoldenDiff() {
    }

    /** One difference, with everything a rule can match on. */
    static final class Change {
        final String classification;
        final String section;
        final String key;
        final int probe;
        /** Baseline row's 11 columns, or {@code null} for {@code ADDED}. */
        final String[] base;
        /** Current row's 11 columns, or {@code null} for {@code REMOVED}. */
        final String[] cur;
        String srcProj = "";
        String tgtProj = "";
        String params = "";
        /** Comma-separated, sorted {@code +datum=} values from the index file; may be empty. */
        String datums = "";
        /** Numeric dimensions that moved, in {@link GoldenFormat#DIMENSIONS} order. */
        final List<String> moved = new ArrayList<String>(6);
        boolean statusMoved;
        boolean insideMoved;
        /** Max {@code |current - baseline|} over the moved dimensions; NaN when not computable. */
        double magnitude = Double.NaN;
        boolean nonFinite;

        Change(String classification, String section, String key, int probe,
               String[] base, String[] cur) {
            this.classification = classification;
            this.section = section;
            this.key = key;
            this.probe = probe;
            this.base = base;
            this.cur = cur;
            if (base != null && cur != null) {
                for (int i = 0; i < GoldenFormat.DIMENSIONS.length; i++) {
                    int col = GoldenFormat.COL_FX + i;
                    if (!base[col].equals(cur[col])) {
                        moved.add(GoldenFormat.DIMENSIONS[i]);
                        double b = GoldenFormat.unhex(base[col]);
                        double c = GoldenFormat.unhex(cur[col]);
                        if (isFinite(b) && isFinite(c)) {
                            double d = Math.abs(c - b);
                            if (Double.isNaN(magnitude) || d > magnitude) magnitude = d;
                        } else {
                            nonFinite = true;
                        }
                    }
                }
                statusMoved = !base[GoldenFormat.COL_STATUS].equals(cur[GoldenFormat.COL_STATUS]);
                insideMoved = !base[GoldenFormat.COL_INSIDE].equals(cur[GoldenFormat.COL_INSIDE]);
            }
        }

        String statusBase() {
            return base == null ? "" : base[GoldenFormat.COL_STATUS];
        }

        String statusCur() {
            return cur == null ? "" : cur[GoldenFormat.COL_STATUS];
        }

        String movedText() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < moved.size(); i++) {
                if (sb.length() > 0) sb.append(',');
                sb.append(moved.get(i));
            }
            if (statusMoved) sb.append(sb.length() > 0 ? ",status" : "status");
            if (insideMoved) sb.append(sb.length() > 0 ? ",inside" : "inside");
            return sb.length() == 0 ? "-" : sb.toString();
        }
    }

    private static boolean isFinite(double d) {
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }

    /** Outcome of a run. */
    public static final class Result {
        public int unchanged;
        public int changed;
        public int added;
        public int removed;
        public int intended;
        public int unexplained;
        public int baselineRows;
        public int currentRows;
        /** Hard failures, each already formatted for a human. */
        public final List<String> failures = new ArrayList<String>();
        /** Up to {@link #SAMPLE} unexplained rows, for the failure message. */
        public final List<String> unexplainedSample = new ArrayList<String>();
        public final Map<String, Integer> perRule = new LinkedHashMap<String, Integer>();

        public static final int SAMPLE = 25;

        public boolean ok() {
            return failures.isEmpty();
        }

        public int exitCode() {
            return failures.isEmpty() ? 0 : 1;
        }

        public String summary() {
            return "golden diff: " + unchanged + " UNCHANGED, " + changed + " CHANGED, "
                    + added + " ADDED, " + removed + " REMOVED; "
                    + intended + " INTENDED, " + unexplained + " UNEXPLAINED"
                    + " (baseline " + baselineRows + " rows, current " + currentRows + " rows)";
        }
    }

    // ------------------------------------------------------------------------------------ main

    /**
     * Usage: {@code GoldenDiff <baselineDir> <currentDir> <rules.yaml> [reportFile]}.
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("usage: GoldenDiff <baselineDir> <currentDir> <rules.yaml> [reportFile]");
            System.exit(2);
        }
        File report = args.length > 3 ? new File(args[3]) : new File(args[1], "golden-report.tsv");
        Result r = run(new File(args[0]), new File(args[1]), new File(args[2]), report, new Date());
        System.out.println(r.summary());
        for (int i = 0; i < r.failures.size(); i++) System.out.println("FAIL " + r.failures.get(i));
        System.out.println("report: " + report);
        System.exit(r.exitCode());
    }

    // ------------------------------------------------------------------------------------- run

    public static Result run(File baselineDir, File currentDir, File rulesFile, File reportFile,
                             Date today) throws IOException {
        GoldenRules rules = GoldenRules.load(rulesFile);
        Result result = new Result();

        File baseGolden = new File(baselineDir, GoldenFormat.GOLDEN_FILE);
        File curGolden = new File(currentDir, GoldenFormat.GOLDEN_FILE);
        if (!baseGolden.isFile()) {
            throw new IOException("no baseline at " + baseGolden
                    + " -- generate one with -Pgolden,golden-baseline (see golden/README.md)");
        }
        if (!curGolden.isFile()) {
            throw new IOException("no current table at " + curGolden);
        }

        Map<String, String[]> baseIndex = readIndex(new File(baselineDir, GoldenFormat.INDEX_FILE));
        Map<String, String[]> curIndex = readIndex(new File(currentDir, GoldenFormat.INDEX_FILE));

        List<Change> changes = new ArrayList<Change>();

        RowReader a = new RowReader(baseGolden);
        RowReader b = new RowReader(curGolden);
        try {
            String[] ra = a.next();
            String[] rb = b.next();
            while (ra != null || rb != null) {
                int cmp;
                if (ra == null) cmp = 1;
                else if (rb == null) cmp = -1;
                else cmp = GoldenFormat.compareRows(ra, rb);

                if (cmp == 0) {
                    if (equalRows(ra, rb)) {
                        result.unchanged++;
                    } else {
                        result.changed++;
                        changes.add(new Change(CHANGED, ra[GoldenFormat.COL_SECTION],
                                ra[GoldenFormat.COL_KEY], probe(ra), ra, rb));
                    }
                    ra = a.next();
                    rb = b.next();
                } else if (cmp < 0) {
                    result.removed++;
                    changes.add(new Change(REMOVED, ra[GoldenFormat.COL_SECTION],
                            ra[GoldenFormat.COL_KEY], probe(ra), ra, null));
                    ra = a.next();
                } else {
                    result.added++;
                    changes.add(new Change(ADDED, rb[GoldenFormat.COL_SECTION],
                            rb[GoldenFormat.COL_KEY], probe(rb), null, rb));
                    rb = b.next();
                }
            }
        } finally {
            a.close();
            b.close();
        }
        result.baselineRows = a.count;
        result.currentRows = b.count;

        for (int i = 0; i < changes.size(); i++) {
            Change c = changes.get(i);
            String[] idx = curIndex.get(c.section + '\t' + c.key);
            if (idx == null) idx = baseIndex.get(c.section + '\t' + c.key);
            if (idx != null) {
                c.srcProj = idx[0];
                c.tgtProj = idx[1];
                c.params = idx[2];
                c.datums = idx[3];
            }
        }

        Writer report = GoldenFormat.writer(reportFile);
        try {
            GoldenFormat.writeRow(report, "classification", "section", "key", "probe",
                    "verdict", "rule", "moved", "magnitude", "status_from", "status_to");
            for (int i = 0; i < changes.size(); i++) {
                Change c = changes.get(i);
                GoldenRules.Rule winner = null;
                for (int j = 0; j < rules.rules().size(); j++) {
                    GoldenRules.Rule rule = rules.rules().get(j);
                    if (!matches(rule, c)) continue;
                    String decline = declines(rule, c);
                    if (decline != null) {
                        // Recorded, not fatal on its own: the row falls through. If nothing else
                        // claims it, this text is what tells the reviewer which rule nearly fit.
                        if (rule.declined.size() < 10) {
                            rule.declined.add(c.section + "/" + c.key + "#" + c.probe + ": " + decline);
                        }
                        continue;
                    }
                    winner = rule;
                    break;
                }
                String verdict;
                String ruleId;
                if (winner == null) {
                    verdict = "UNEXPLAINED";
                    ruleId = "-";
                    result.unexplained++;
                    if (result.unexplainedSample.size() < Result.SAMPLE) {
                        result.unexplainedSample.add(c.classification + " " + c.section + "/"
                                + c.key + "#" + c.probe + " moved=" + c.movedText()
                                + " mag=" + (Double.isNaN(c.magnitude) ? "n/a"
                                : Double.toString(c.magnitude))
                                + " status " + c.statusBase() + " -> " + c.statusCur());
                    }
                } else {
                    verdict = "INTENDED";
                    ruleId = winner.id;
                    winner.matched++;
                    result.intended++;
                }
                GoldenFormat.writeRow(report, c.classification, c.section, c.key,
                        Integer.toString(c.probe), verdict, ruleId, c.movedText(),
                        Double.isNaN(c.magnitude) ? "-" : GoldenFormat.hex(c.magnitude),
                        c.statusBase().isEmpty() ? "-" : c.statusBase(),
                        c.statusCur().isEmpty() ? "-" : c.statusCur());
            }
        } finally {
            report.close();
        }

        // ---- rule-level gates ------------------------------------------------------------------
        for (int j = 0; j < rules.rules().size(); j++) {
            GoldenRules.Rule rule = rules.rules().get(j);
            result.perRule.put(rule.id, Integer.valueOf(rule.matched));
            if (rule.expired(today)) {
                result.failures.add("EXPIRED_RULE " + rule.id + " expired " + rule.expiresText
                        + " -- fold it into a new baseline and delete it, or extend it deliberately");
            }
            if (GoldenRules.PENDING.equals(rule.status)) {
                if (rule.matched > 0) {
                    result.failures.add("PENDING_RULE_FIRED " + rule.id + " matched " + rule.matched
                            + " row(s) while marked pending -- the change has landed: set status: active"
                            + " and pin expected_rows: " + rule.matched);
                }
                continue;
            }
            if (rule.matched == 0) {
                StringBuilder sb = new StringBuilder("DEAD_RULE " + rule.id
                        + " matched 0 rows -- delete it, or find out why the change it describes is not"
                        + " happening (if the change has not landed yet, mark the rule status: pending)");
                for (int k = 0; k < rule.declined.size(); k++) {
                    sb.append("\n    declined: ").append(rule.declined.get(k));
                }
                result.failures.add(sb.toString());
            } else if (rule.countPinned && rule.matched != rule.expectedRows) {
                result.failures.add("COUNT_MISMATCH " + rule.id + " expected_rows="
                        + rule.expectedRows + " but matched " + rule.matched
                        + " -- the count is exact and two-sided on purpose; " + (rule.matched
                        > rule.expectedRows
                        ? "something else moved with it"
                        : "part of the change is not happening, possibly masked by another defect"));
            }
        }
        if (result.unexplained > 0) {
            StringBuilder sb = new StringBuilder("UNEXPLAINED " + result.unexplained
                    + " row(s) changed with no rule claiming them. First "
                    + result.unexplainedSample.size() + ":");
            for (int i = 0; i < result.unexplainedSample.size(); i++) {
                sb.append("\n    ").append(result.unexplainedSample.get(i));
            }
            result.failures.add(sb.toString());
        }
        return result;
    }

    private static int probe(String[] row) {
        return Integer.parseInt(row[GoldenFormat.COL_PROBE]);
    }

    private static boolean equalRows(String[] a, String[] b) {
        for (int i = 0; i < GoldenFormat.GOLDEN_COLUMNS; i++) {
            if (!a[i].equals(b[i])) return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------------- matching

    /** The {@code match} predicates. All present must hold. */
    static boolean matches(GoldenRules.Rule r, Change c) {
        if (r.classifications != null && !r.classifications.contains(c.classification)) return false;
        if (r.sections != null && !r.sections.contains(c.section)) return false;
        if (r.probes != null && !r.probes.contains(Integer.valueOf(c.probe))) return false;
        if (r.keyGlobs != null) {
            boolean any = false;
            for (int i = 0; i < r.keyGlobs.size(); i++) {
                if (GoldenRules.glob(r.keyGlobs.get(i), c.key)) {
                    any = true;
                    break;
                }
            }
            if (!any) return false;
        }
        if (r.authorities != null && !r.authorities.contains(authorityOf(c.key))) return false;
        if (r.codeMin != Long.MIN_VALUE || r.codeMax != Long.MAX_VALUE) {
            long code = codeOf(c.key);
            // A non-numeric code (world:CH1903, SYN keys) never satisfies a numeric range. Silently
            // treating it as 0 would sweep 47 world entries into every "code_min: 0" rule.
            if (code == Long.MIN_VALUE) return false;
            if (code < r.codeMin || code > r.codeMax) return false;
        }
        if (r.srcProj != null && !r.srcProj.contains(c.srcProj)) return false;
        if (r.tgtProj != null && !r.tgtProj.contains(c.tgtProj)) return false;
        if (r.paramsPresent != null) {
            boolean any = false;
            for (int i = 0; i < r.paramsPresent.size(); i++) {
                if (hasParam(c.params, r.paramsPresent.get(i))) {
                    any = true;
                    break;
                }
            }
            if (!any) return false;
        }
        if (r.paramsAbsent != null) {
            for (int i = 0; i < r.paramsAbsent.size(); i++) {
                if (hasParam(c.params, r.paramsAbsent.get(i))) return false;
            }
        }
        if (r.datums != null) {
            // ANY-of, like params_present -- but on the parameter's VALUE, which is the whole point:
            // params_present matches keys, so "the defs whose datum is NAD27" was previously
            // expressible only by enumerating several hundred keys.
            boolean any = false;
            for (int i = 0; i < r.datums.size(); i++) {
                if (hasParam(c.datums, r.datums.get(i))) {
                    any = true;
                    break;
                }
            }
            if (!any) return false;
        }
        if (r.datumsAbsent != null) {
            for (int i = 0; i < r.datumsAbsent.size(); i++) {
                if (hasParam(c.datums, r.datumsAbsent.get(i))) return false;
            }
        }
        if (r.statusFrom != null && !GoldenRules.glob(r.statusFrom, c.statusBase())) return false;
        if (r.statusTo != null && !GoldenRules.glob(r.statusTo, c.statusCur())) return false;
        return true;
    }

    /**
     * The {@code expect} constraints. Returns {@code null} when the rule accepts the row, else a
     * human-readable reason for declining it.
     */
    static String declines(GoldenRules.Rule r, Change c) {
        if (!CHANGED.equals(c.classification)) {
            // ADDED/REMOVED rows have no counterpart, so there is nothing to constrain the movement
            // of. The match predicates are the whole test for them.
            return null;
        }
        if (r.dimensions != null) {
            for (int i = 0; i < c.moved.size(); i++) {
                if (!r.dimensions.contains(c.moved.get(i))) {
                    return "moved in dimension '" + c.moved.get(i)
                            + "' which the rule does not sanction (allows " + r.dimensions + ")";
                }
            }
        }
        if (c.statusMoved && !r.allowStatusChange) {
            return "status changed (" + c.statusBase() + " -> " + c.statusCur()
                    + ") and allow_status_change is not set";
        }
        if (c.insideMoved && !r.allowInsideChange) {
            return "inside changed (" + c.base[GoldenFormat.COL_INSIDE] + " -> "
                    + c.cur[GoldenFormat.COL_INSIDE] + ") and allow_inside_change is not set";
        }
        if (c.nonFinite && !r.allowNonFinite) {
            return "a moved ordinate is NaN or Infinity on one side and allow_nonfinite is not set";
        }
        if (r.magnitudeGiven) {
            if (Double.isNaN(c.magnitude)) {
                // Every moved dimension was non-finite, so there is no magnitude to band. Only
                // reachable with allow_nonfinite, and a rule that declares a band must not be
                // satisfied by "the magnitude is unknowable".
                return "magnitude band declared but no finite movement to measure";
            }
            if (c.magnitude < r.magMin || c.magnitude > r.magMax) {
                return "magnitude " + c.magnitude + " outside declared band ["
                        + r.magMin + ", " + r.magMax + "]";
            }
        }
        return null;
    }

    static String authorityOf(String key) {
        int i = key.indexOf(':');
        return i < 0 ? "" : key.substring(0, i);
    }

    /** The numeric part after the colon, or {@link Long#MIN_VALUE} when there is not one. */
    static long codeOf(String key) {
        int i = key.indexOf(':');
        if (i < 0) return Long.MIN_VALUE;
        String s = key.substring(i + 1);
        if (s.isEmpty()) return Long.MIN_VALUE;
        for (int j = 0; j < s.length(); j++) {
            if (s.charAt(j) < '0' || s.charAt(j) > '9') return Long.MIN_VALUE;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    /**
     * Exact membership in a comma-separated, sorted, non-quoted set. Used for both the
     * {@code params} (key names) and {@code datums} (parameter values) columns; both are written by
     * {@code InputSet} with the same joining rules, and neither can contain a comma because
     * {@code GoldenFormat.assertClean} would have refused the row.
     */
    static boolean hasParam(String params, String key) {
        if (params.isEmpty()) return false;
        int from = 0;
        while (from <= params.length()) {
            int comma = params.indexOf(',', from);
            int end = comma < 0 ? params.length() : comma;
            if (end - from == key.length() && params.regionMatches(from, key, 0, key.length())) {
                return true;
            }
            if (comma < 0) break;
            from = comma + 1;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------- I/O

    /** Streams a golden file, verifying the header, the column count and the sort order. */
    static final class RowReader {
        private final BufferedReader r;
        private final File f;
        private String[] prev;
        int count;

        RowReader(File f) throws IOException {
            this.f = f;
            this.r = GoldenFormat.reader(f);
            String header = r.readLine();
            if (!GoldenFormat.HEADER_GOLDEN.equals(header)) {
                throw new IOException(f + ": unexpected header: " + header);
            }
        }

        String[] next() throws IOException {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] row = GoldenFormat.split(line, GoldenFormat.GOLDEN_COLUMNS);
                if (prev != null) {
                    int cmp = GoldenFormat.compareRows(prev, row);
                    if (cmp > 0) {
                        throw new IOException(f + ": rows out of order at line " + (count + 2)
                                + " -- the merge join requires the golden total order (section, key, probe)"
                                + " compared as US-ASCII bytes; got " + prev[0] + "/" + prev[1] + "#"
                                + prev[2] + " then " + row[0] + "/" + row[1] + "#" + row[2]);
                    }
                    if (cmp == 0) {
                        throw new IOException(f + ": duplicate row key at line " + (count + 2)
                                + ": " + row[0] + "/" + row[1] + "#" + row[2]);
                    }
                }
                prev = row;
                count++;
                return row;
            }
            return null;
        }

        void close() {
            try {
                r.close();
            } catch (IOException ignored) {
                // closing a reader we have finished with
            }
        }
    }

    /**
     * Reads {@code golden-index.tsv}, in either the current six-column form or the five-column form
     * that predates the {@code datums} column.
     *
     * <p>The legacy form has to be accepted because {@code baseline/1.4.3/golden-index.tsv} is a
     * committed artefact of a released build and is not rewritten by a normal run. It is consulted
     * only for a {@code (section, key)} the current run did not emit — which today is nothing, since
     * the diff reports 0 {@code ADDED} and 0 {@code REMOVED} — and a legacy row yields an empty
     * {@code datums} set, so a {@code datums:} predicate declines it. A rule can therefore
     * under-claim on a {@code REMOVED} row and never over-claim, and {@code expected_rows} makes
     * under-claiming a build failure rather than a silence.
     *
     * <p>Anything else is still rejected outright: a header this reader does not recognise means the
     * file was written by code that disagrees with it about column meanings, and guessing is how a
     * rules engine starts matching the wrong column.
     */
    static Map<String, String[]> readIndex(File f) throws IOException {
        Map<String, String[]> out = new HashMap<String, String[]>();
        if (!f.isFile()) return out;
        BufferedReader r = GoldenFormat.reader(f);
        try {
            String header = r.readLine();
            final int columns;
            if (GoldenFormat.HEADER_INDEX.equals(header)) {
                columns = 6;
            } else if (GoldenFormat.HEADER_INDEX_V1.equals(header)) {
                columns = 5;
            } else {
                throw new IOException(f + ": unexpected header: " + header);
            }
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] c = GoldenFormat.split(line, columns);
                out.put(c[0] + '\t' + c[1],
                        new String[]{c[2], c[3], c[4], columns == 6 ? c[5] : ""});
            }
        } finally {
            r.close();
        }
        return out;
    }
}

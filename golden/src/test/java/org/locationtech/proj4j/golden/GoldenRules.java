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

import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code rules.yaml}: the declarations that turn a row change from {@code UNEXPLAINED} into
 * {@code INTENDED}.
 *
 * <h2>The design constraint that shapes everything here</h2>
 *
 * <b>A rule must not be able to hide an unrelated regression.</b> A golden-master suite whose rules
 * are broad predicates degenerates into a rubber stamp within a month: someone writes
 * {@code sections: [REG]} to get a release out, and from then on the suite is silent about 45,065
 * rows. Four mechanisms prevent that, and none of them is optional:
 *
 * <ol>
 * <li><b>{@code expected_rows} is exact and two-sided.</b> Not a maximum. If a rule was written for
 *     943 rows and matches 942 or 944, the build fails. A fix that moves fewer rows than expected is
 *     as interesting as one that moves more — usually it means a second defect is masking part of it,
 *     which is exactly how the {@code +R} / Albers-spherical-inverse coupling was found.</li>
 * <li><b>A rule matching zero rows is a {@code DEAD_RULE} failure.</b> Otherwise stale rules
 *     accumulate, and each one is a live licence to change behaviour that nobody is watching any more.
 *     Deleting a dead rule is a one-line commit; leaving it is a permanent hole.</li>
 * <li><b>{@code expires} is mandatory.</b> A rule is a statement that a change is intended <em>right
 *     now, during this piece of work</em>. Past the release it should be folded into a new baseline
 *     and deleted. The expiry is what forces that conversation instead of letting the rules file
 *     become the specification.</li>
 * <li><b>A failing {@code expect} clause does not make the rule match.</b> If the match predicates
 *     select a row but the row moved in a dimension the rule did not sanction, or by a magnitude
 *     outside the declared band, the rule <em>declines</em> the row and it falls through to the next
 *     rule and ultimately to {@code UNEXPLAINED}. The diagnostic records which rules declined and
 *     why. This is the only safe direction: the alternative — treating a matched-but-unexpected row as
 *     explained — is precisely the rubber stamp.</li>
 * </ol>
 *
 * <h2>Schema</h2>
 * <pre>
 * version: 1
 * rules:
 *   - id: SOME-ID                  # required, unique, [A-Za-z0-9._-]+
 *     reason: |                    # required, free text; why this change is intended
 *       ...
 *     expires: 2027-06-30          # required, ISO-8601 date
 *     expected_rows: 943           # required: an integer, or the literal TBD
 *     match:                       # all predicates present must hold (AND)
 *       sections: [REG, SYN]       #   golden section
 *       keys: ["mod/&#42;/rf"]           #   glob on the row key
 *       authorities: [epsg, esri]  #   for REG/CSV/PAIR keys: the authority prefix
 *       code_min: 2000             #   numeric code range, inclusive; non-numeric codes never match
 *       code_max: 32766
 *       src_proj: [longlat]        #   +proj= of the source CRS
 *       tgt_proj: [merc, tmerc]    #   +proj= of the target CRS
 *       params_present: [rf, f]    #   ANY of these parameter keys present (OR within the list)
 *       params_absent: [datum]     #   ALL of these absent
 *       datums: [NAD27]            #   ANY of these +datum= VALUES on either side (OR)
 *       datums_absent: [WGS84]     #   ALL of these values absent
 *       probes: [0, 1, 2, 3, 4]    #   probe index
 *       classifications: [CHANGED] #   CHANGED / ADDED / REMOVED
 *       status_from: "OK"          #   glob on the baseline status
 *       status_to: "EXC:&#42;"          #   glob on the current status
 *     expect:                      # constraints; failing one makes the rule DECLINE the row
 *       dimensions: [fy, iy]       #   only these numeric columns may move
 *       allow_status_change: true  #   default false
 *       allow_inside_change: false #   default false
 *       allow_nonfinite: true      #   default false; needed if either side is NaN/Infinity
 *       magnitude: {min: 1.0, max: 1.0e9}   # band on max |current - baseline| over moved dimensions
 * </pre>
 *
 * <p>An empty {@code match} matches every changed row. That is legal and occasionally correct (a
 * whole-table rebaseline in progress), but combined with an exact {@code expected_rows} it is not a
 * licence: it still has to name the total.
 */
public final class GoldenRules {

    public static final String TBD = "TBD";

    /** A rule expected to be claiming rows now. Must match at least one, or it is a {@code DEAD_RULE}. */
    public static final String ACTIVE = "active";
    /**
     * A rule for a change that has not landed yet. Must match <em>exactly zero</em> rows; the moment it
     * matches one, the build fails with {@code PENDING_RULE_FIRED} telling you to flip it to
     * {@code active} and pin {@code expected_rows}.
     *
     * <p>This exists because six streams are changing numerical behaviour concurrently and their rules
     * have to be written before their code lands — otherwise the first person to land a change meets an
     * {@code UNEXPLAINED} wall with no rule to point at. It cannot be used to hide anything: a pending
     * rule that absorbs a row is a failure, not a pass, so the only thing {@code pending} buys is
     * silence about a change that is not happening yet.
     */
    public static final String PENDING = "pending";

    /** One rule. Immutable once parsed. */
    public static final class Rule {
        public final String id;
        public final String reason;
        public final java.util.Date expires;
        public final String expiresText;
        /** {@code -1} when {@code expected_rows} is {@code TBD}. */
        public final int expectedRows;
        public final boolean countPinned;
        /** {@link #ACTIVE} or {@link #PENDING}. */
        public final String status;

        // match
        public final Set<String> sections;
        public final List<String> keyGlobs;
        public final Set<String> authorities;
        public final long codeMin;
        public final long codeMax;
        public final Set<String> srcProj;
        public final Set<String> tgtProj;
        public final List<String> paramsPresent;
        public final List<String> paramsAbsent;
        /** ANY-of over the {@code datums} index column, i.e. over a parameter's <em>value</em>. */
        public final List<String> datums;
        /** ALL-absent over the same column. */
        public final List<String> datumsAbsent;
        public final Set<Integer> probes;
        public final Set<String> classifications;
        public final String statusFrom;
        public final String statusTo;

        // expect
        public final Set<String> dimensions;
        public final boolean allowStatusChange;
        public final boolean allowInsideChange;
        public final boolean allowNonFinite;
        public final double magMin;
        public final double magMax;
        public final boolean magnitudeGiven;

        /** Mutable tallies, filled in by the diff. */
        public int matched;
        public final List<String> declined = new ArrayList<String>();

        @SuppressWarnings("unchecked")
        Rule(Map<String, Object> m, int index) {
            id = req(m, "id", index).toString();
            if (!id.matches("[A-Za-z0-9._-]+")) {
                throw new IllegalArgumentException("rule " + id + ": id must match [A-Za-z0-9._-]+");
            }
            reason = req(m, "reason", index).toString().trim();
            if (reason.isEmpty()) {
                throw new IllegalArgumentException("rule " + id + ": reason must not be empty");
            }
            Object exp = req(m, "expires", index);
            expiresText = exp.toString();
            expires = parseDate(id, exp);

            Object rows = req(m, "expected_rows", index);
            if (TBD.equals(rows.toString())) {
                expectedRows = -1;
                countPinned = false;
            } else if (rows instanceof Number) {
                expectedRows = ((Number) rows).intValue();
                countPinned = true;
                if (expectedRows < 0) {
                    throw new IllegalArgumentException("rule " + id + ": expected_rows must be >= 0");
                }
            } else {
                throw new IllegalArgumentException(
                        "rule " + id + ": expected_rows must be an integer or the literal TBD, was: " + rows);
            }

            Object st = m.get("status");
            status = st == null ? ACTIVE : st.toString();
            if (!ACTIVE.equals(status) && !PENDING.equals(status)) {
                throw new IllegalArgumentException(
                        "rule " + id + ": status must be '" + ACTIVE + "' or '" + PENDING + "'");
            }
            if (PENDING.equals(status) && countPinned && expectedRows != 0) {
                throw new IllegalArgumentException("rule " + id
                        + ": a pending rule must not pin a non-zero expected_rows; use TBD");
            }

            Map<String, Object> match = sub(m, "match");
            sections = strSet(match, "sections");
            keyGlobs = strList(match, "keys");
            authorities = strSet(match, "authorities");
            codeMin = num(match, "code_min", Long.MIN_VALUE);
            codeMax = num(match, "code_max", Long.MAX_VALUE);
            srcProj = strSet(match, "src_proj");
            tgtProj = strSet(match, "tgt_proj");
            paramsPresent = strList(match, "params_present");
            paramsAbsent = strList(match, "params_absent");
            datums = strList(match, "datums");
            datumsAbsent = strList(match, "datums_absent");
            probes = intSet(match, "probes");
            classifications = strSet(match, "classifications");
            statusFrom = str(match, "status_from");
            statusTo = str(match, "status_to");
            checkKeys(id, "match", match, new String[]{"sections", "keys", "authorities", "code_min",
                    "code_max", "src_proj", "tgt_proj", "params_present", "params_absent",
                    "datums", "datums_absent", "probes",
                    "classifications", "status_from", "status_to"});

            Map<String, Object> expect = sub(m, "expect");
            dimensions = strSet(expect, "dimensions");
            if (dimensions != null) {
                for (String d : dimensions) {
                    if (!Arrays.asList(GoldenFormat.DIMENSIONS).contains(d)) {
                        throw new IllegalArgumentException("rule " + id
                                + ": unknown dimension '" + d + "'; expected one of "
                                + Arrays.toString(GoldenFormat.DIMENSIONS));
                    }
                }
            }
            allowStatusChange = bool(expect, "allow_status_change");
            allowInsideChange = bool(expect, "allow_inside_change");
            allowNonFinite = bool(expect, "allow_nonfinite");
            Map<String, Object> mag = sub(expect, "magnitude");
            magnitudeGiven = !mag.isEmpty();
            magMin = dbl(mag, "min", 0.0);
            magMax = dbl(mag, "max", Double.POSITIVE_INFINITY);
            checkKeys(id, "expect", expect, new String[]{"dimensions", "allow_status_change",
                    "allow_inside_change", "allow_nonfinite", "magnitude"});
            checkKeys(id, "rule", m, new String[]{"id", "reason", "expires", "expected_rows",
                    "status", "match", "expect"});
        }

        public boolean expired(java.util.Date today) {
            return expires.before(today);
        }
    }

    private final List<Rule> rules;

    public GoldenRules(List<Rule> rules) {
        this.rules = Collections.unmodifiableList(rules);
    }

    public List<Rule> rules() {
        return rules;
    }

    @SuppressWarnings("unchecked")
    public static GoldenRules load(File f) throws IOException {
        Reader r = new InputStreamReader(new java.io.FileInputStream(f), "UTF-8");
        try {
            Object root = new Yaml().load(r);
            if (root == null) return new GoldenRules(new ArrayList<Rule>());
            if (!(root instanceof Map)) {
                throw new IllegalArgumentException(f + ": top level must be a mapping");
            }
            Map<String, Object> m = (Map<String, Object>) root;
            Object version = m.get("version");
            if (version == null || !"1".equals(version.toString())) {
                throw new IllegalArgumentException(f + ": unsupported or missing version: " + version);
            }
            Object rawRules = m.get("rules");
            List<Rule> out = new ArrayList<Rule>();
            if (rawRules != null) {
                if (!(rawRules instanceof List)) {
                    throw new IllegalArgumentException(f + ": 'rules' must be a sequence");
                }
                List<Object> list = (List<Object>) rawRules;
                Set<String> ids = new LinkedHashSet<String>();
                for (int i = 0; i < list.size(); i++) {
                    Object o = list.get(i);
                    if (!(o instanceof Map)) {
                        throw new IllegalArgumentException(f + ": rules[" + i + "] must be a mapping");
                    }
                    Rule rule = new Rule((Map<String, Object>) o, i);
                    if (!ids.add(rule.id)) {
                        throw new IllegalArgumentException(f + ": duplicate rule id '" + rule.id + "'");
                    }
                    out.add(rule);
                }
            }
            return new GoldenRules(out);
        } finally {
            r.close();
        }
    }

    // --------------------------------------------------------------------------- yaml helpers

    private static Object req(Map<String, Object> m, String key, int index) {
        Object v = m.get(key);
        if (v == null) {
            throw new IllegalArgumentException("rules[" + index + "]: missing required key '" + key + "'");
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return Collections.emptyMap();
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("'" + key + "' must be a mapping");
        }
        return (Map<String, Object>) v;
    }

    /**
     * Rejects unknown keys rather than ignoring them. A typo in a rule ({@code param_present} for
     * {@code params_present}) would otherwise widen the rule to "everything" silently, which is the
     * single easiest way to turn this suite into a rubber stamp.
     */
    private static void checkKeys(String id, String where, Map<String, Object> m, String[] allowed) {
        for (String k : m.keySet()) {
            boolean ok = false;
            for (int i = 0; i < allowed.length; i++) {
                if (allowed[i].equals(k)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                throw new IllegalArgumentException("rule " + id + ": unknown key '" + k + "' in "
                        + where + "; allowed: " + Arrays.toString(allowed));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        List<String> out = new ArrayList<String>();
        if (v instanceof List) {
            for (Object o : (List<Object>) v) out.add(String.valueOf(o));
        } else {
            out.add(String.valueOf(v));
        }
        return out;
    }

    private static Set<String> strSet(Map<String, Object> m, String key) {
        List<String> l = strList(m, key);
        return l == null ? null : new LinkedHashSet<String>(l);
    }

    @SuppressWarnings("unchecked")
    private static Set<Integer> intSet(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        Set<Integer> out = new LinkedHashSet<Integer>();
        if (v instanceof List) {
            for (Object o : (List<Object>) v) out.add(Integer.valueOf(o.toString()));
        } else {
            out.add(Integer.valueOf(v.toString()));
        }
        return out;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private static long num(Map<String, Object> m, String key, long dflt) {
        Object v = m.get(key);
        return v == null ? dflt : Long.parseLong(v.toString());
    }

    private static double dbl(Map<String, Object> m, String key, double dflt) {
        Object v = m.get(key);
        return v == null ? dflt : Double.parseDouble(v.toString());
    }

    private static boolean bool(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return false;
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        return Boolean.parseBoolean(v.toString());
    }

    private static java.util.Date parseDate(String id, Object v) {
        // snakeyaml resolves an unquoted ISO date to java.util.Date already; a quoted one arrives as
        // a String. Accept both, and nothing else -- "next release" is not a date.
        if (v instanceof java.util.Date) return (java.util.Date) v;
        String s = v.toString().trim();
        if (!s.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new IllegalArgumentException(
                    "rule " + id + ": expires must be an ISO-8601 date (yyyy-MM-dd), was: " + s);
        }
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd");
        fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        fmt.setLenient(false);
        try {
            return fmt.parse(s);
        } catch (java.text.ParseException e) {
            throw new IllegalArgumentException("rule " + id + ": unparseable expires: " + s);
        }
    }

    // -------------------------------------------------------------------------------- globbing

    /** {@code *} matches any run of characters; everything else is literal. No other metacharacter. */
    public static boolean glob(String pattern, String value) {
        return globAt(pattern, 0, value, 0);
    }

    private static boolean globAt(String p, int pi, String v, int vi) {
        while (pi < p.length()) {
            char pc = p.charAt(pi);
            if (pc == '*') {
                // Collapse runs of '*', then try every split point.
                while (pi < p.length() && p.charAt(pi) == '*') pi++;
                if (pi == p.length()) return true;
                for (int k = vi; k <= v.length(); k++) {
                    if (globAt(p, pi, v, k)) return true;
                }
                return false;
            }
            if (vi >= v.length() || v.charAt(vi) != pc) return false;
            pi++;
            vi++;
        }
        return vi == v.length();
    }
}

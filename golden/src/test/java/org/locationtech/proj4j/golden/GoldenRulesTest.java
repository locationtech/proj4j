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
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Schema validation for {@code rules.yaml}, and a load of the real committed file.
 *
 * <p>The real-file test runs in the fast build too, not just under {@code -Pgolden}. A malformed rules
 * file must break the build that introduced it, not the nightly golden sweep three days later.
 */
public class GoldenRulesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File yaml(String body) throws IOException {
        File f = tmp.newFile("r" + System.nanoTime() + ".yaml");
        Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(f), "UTF-8");
        try {
            w.write(body);
        } finally {
            w.close();
        }
        return f;
    }

    private void rejects(String body, String expectedFragment) throws IOException {
        try {
            GoldenRules.load(yaml(body));
            fail("should have rejected: " + body);
        } catch (IllegalArgumentException e) {
            assertTrue("wrong message: " + e.getMessage(),
                    e.getMessage().contains(expectedFragment));
        }
    }

    private static final String OK_RULE = "version: 1\nrules:\n"
            + "  - id: A-B_1.2\n    reason: because\n    expires: 2099-01-01\n"
            + "    expected_rows: 5\n    match:\n      sections: [REG]\n";

    @Test
    public void acceptsAWellFormedRule() throws Exception {
        List<GoldenRules.Rule> rules = GoldenRules.load(yaml(OK_RULE)).rules();
        assertEquals(1, rules.size());
        GoldenRules.Rule r = rules.get(0);
        assertEquals("A-B_1.2", r.id);
        assertEquals(5, r.expectedRows);
        assertTrue(r.countPinned);
        assertEquals(GoldenRules.ACTIVE, r.status);
        assertNotNull(r.expires);
    }

    @Test
    public void acceptsTbdAsAnUnpinnedCount() throws Exception {
        GoldenRules.Rule r = GoldenRules.load(yaml(
                OK_RULE.replace("expected_rows: 5", "expected_rows: TBD"))).rules().get(0);
        assertEquals(-1, r.expectedRows);
        assertTrue(!r.countPinned);
    }

    /**
     * The single most important schema check. A typo like {@code param_present} for
     * {@code params_present} would otherwise be ignored, widening the rule to "everything" — which is
     * how a golden suite becomes a rubber stamp without anyone deciding to make it one.
     */
    @Test
    public void rejectsUnknownKeys() throws Exception {
        rejects(OK_RULE.replace("      sections: [REG]", "      section: [REG]"),
                "unknown key 'section' in match");
        rejects(OK_RULE + "    expect:\n      dimension: [fy]\n",
                "unknown key 'dimension' in expect");
        rejects(OK_RULE.replace("    reason: because", "    reasons: because"),
                "missing required key 'reason'");
        rejects(OK_RULE + "    owner: someone\n", "unknown key 'owner' in rule");
    }

    @Test
    public void requiresAnExplicitIsoExpiry() throws Exception {
        rejects("version: 1\nrules:\n  - id: A\n    reason: r\n    expected_rows: 1\n",
                "missing required key 'expires'");
        rejects(OK_RULE.replace("expires: 2099-01-01", "expires: \"next release\""),
                "expires must be an ISO-8601 date");
        rejects(OK_RULE.replace("expires: 2099-01-01", "expires: \"2099-13-45\""),
                "unparseable expires");
    }

    @Test
    public void rejectsBadDimensionNames() throws Exception {
        rejects(OK_RULE + "    expect:\n      dimensions: [northing]\n",
                "unknown dimension 'northing'");
    }

    @Test
    public void rejectsDuplicateIdsAndBadIdCharacters() throws Exception {
        rejects("version: 1\nrules:\n"
                        + "  - id: A\n    reason: r\n    expires: 2099-01-01\n    expected_rows: 1\n"
                        + "  - id: A\n    reason: r\n    expires: 2099-01-01\n    expected_rows: 1\n",
                "duplicate rule id 'A'");
        rejects(OK_RULE.replace("id: A-B_1.2", "id: \"has space\""),
                "id must match");
    }

    @Test
    public void rejectsAnEmptyReason() throws Exception {
        rejects(OK_RULE.replace("reason: because", "reason: \"   \""), "reason must not be empty");
    }

    @Test
    public void rejectsAPendingRuleWithANonZeroCount() throws Exception {
        rejects(OK_RULE.replace("    expected_rows: 5", "    status: pending\n    expected_rows: 5"),
                "pending rule must not pin a non-zero expected_rows");
    }

    @Test
    public void rejectsAnUnknownStatus() throws Exception {
        rejects(OK_RULE.replace("    expected_rows: 5", "    status: maybe\n    expected_rows: 5"),
                "status must be");
    }

    @Test
    public void rejectsAnUnsupportedVersion() throws Exception {
        rejects("version: 2\nrules: []\n", "unsupported or missing version");
        rejects("rules: []\n", "unsupported or missing version");
    }

    /** The committed file must parse, and every rule in it must carry a future expiry. */
    @Test
    public void committedRulesFileIsValid() throws Exception {
        File f = committedRulesFile();
        List<GoldenRules.Rule> rules = GoldenRules.load(f).rules();
        assertTrue("rules.yaml should carry the seeded declarations", rules.size() >= 6);
        java.util.Date now = new java.util.Date();
        for (int i = 0; i < rules.size(); i++) {
            GoldenRules.Rule r = rules.get(i);
            assertTrue("rule " + r.id + " expired on " + r.expiresText
                    + " -- fold it into a new baseline and delete it", !r.expired(now));
            assertTrue("rule " + r.id + " needs a substantive reason",
                    r.reason.length() > 40);
        }
    }

    // ================================================================================================
    // expected_rows pinning: the control that stops a rule silently absorbing another rule's rows
    // ================================================================================================

    /**
     * Locates {@code rules.yaml} and fails — rather than skipping — when it is not there.
     *
     * <p>This used to be an {@code Assume}, which is a hole of exactly the kind this class exists to
     * close: a mistyped {@code -Dgolden.dir} would have turned every assertion below into a silent
     * green. {@code golden.dir} is set unconditionally by {@code golden/pom.xml}'s surefire
     * configuration and defaults to {@code .}, which is the module directory when the test is run
     * from an IDE, so there is no legitimate way for this file to be absent.
     */
    private static File committedRulesFile() {
        File f = new File(System.getProperty("golden.dir", "."), "rules.yaml");
        assertTrue("no rules.yaml at " + f.getAbsolutePath()
                + " -- golden.dir is wrong, and a skipped check is not a passed check", f.isFile());
        return f;
    }

    /**
     * The predicate under test, factored out so the committed-file assertion and its positive control
     * exercise <em>the same code</em>. A control that runs a different implementation from the check
     * proves nothing about the check.
     *
     * @return the ids of every {@code status: active} rule whose {@code expected_rows} is {@code TBD}
     */
    static List<String> unpinnedActiveRuleIds(List<GoldenRules.Rule> rules) {
        List<String> out = new java.util.ArrayList<String>();
        for (int i = 0; i < rules.size(); i++) {
            GoldenRules.Rule r = rules.get(i);
            if (GoldenRules.ACTIVE.equals(r.status) && !r.countPinned) {
                out.add(r.id);
            }
        }
        return out;
    }

    /**
     * <b>No {@code status: active} rule may carry {@code expected_rows: TBD}.</b>
     *
     * <p>{@code expected_rows} is the only mechanism in this suite that notices a rule quietly
     * changing size. It is exact and two-sided, and it has already earned its keep twice: it caught a
     * rule claiming 270 rows when it should have claimed 95 — the extra 175 being another rule's,
     * silently absorbed — and it caught {@code FAILCLOSED-UNCHECKED-ISE-REPLACED} growing from 55 to
     * 71 by swallowing 16 grid rows. That second one was <em>unpinned at the time</em>, so the theft
     * was invisible until the count was pinned; it is the reason this assertion exists rather than
     * being left to review.
     *
     * <p>A {@code TBD} on an active rule is therefore not a to-do, it is a disabled control: the rule
     * goes on claiming rows and nothing checks how many. {@code TBD} is legitimate only on a
     * {@code status: pending} rule, which must match exactly zero rows and so has no count to pin.
     *
     * <p><b>Pin the count, do not widen the rule.</b> If pinning exposes a rule taking rows that
     * belong to another, the fix is the file order (rules are first-match-wins, most specific first),
     * not a broader predicate that absorbs the difference.
     */
    @Test
    public void noActiveRuleMayLeaveItsExpectedRowsUnpinned() throws Exception {
        List<GoldenRules.Rule> rules = GoldenRules.load(committedRulesFile()).rules();
        assertTrue("rules.yaml should carry the seeded declarations", rules.size() >= 6);
        List<String> unpinned = unpinnedActiveRuleIds(rules);
        if (!unpinned.isEmpty()) {
            fail(unpinned.size() + " of " + rules.size() + " rules are `status: active` with"
                    + " `expected_rows: TBD`, which switches off the only control that notices a rule"
                    + " changing size:\n    " + join(unpinned, "\n    ")
                    + "\n\nPin each one from a run you did yourself -- `mvn -Pgolden -pl golden -am"
                    + " verify` prints `rows claimed per rule`. If a count is not knowable yet because"
                    + " the change has not landed, the rule is `status: pending`, not `active`."
                    + " Never widen a rule's predicates to make a pinned count come out right.");
        }
    }

    /**
     * The positive control for the assertion above, and the reason it can be believed.
     *
     * <p>A check that has never been observed rejecting anything is a claim, not a measurement. This
     * drives {@link #unpinnedActiveRuleIds} — the same method the committed-file test uses — over
     * three synthetic rules whose only difference is the thing being detected, and asserts that it
     * flags the one that should be flagged and neither of the two that should not. So it discriminates
     * rather than merely alarming, in both directions:
     *
     * <ul>
     * <li>{@code active} + {@code TBD} — must be reported;</li>
     * <li>{@code active} + a pinned integer — must not be;</li>
     * <li>{@code pending} + {@code TBD} — must not be, because a pending rule matches zero rows by
     *     definition and has no count to pin.</li>
     * </ul>
     */
    @Test
    public void theUnpinnedActiveRuleCheckIsDiscriminating() throws Exception {
        String body = "version: 1\nrules:\n"
                + "  - id: ACTIVE-TBD\n    status: active\n    reason: the offender\n"
                + "    expires: 2099-01-01\n    expected_rows: TBD\n"
                + "    match:\n      sections: [REG]\n"
                + "  - id: ACTIVE-PINNED\n    status: active\n    reason: correctly pinned\n"
                + "    expires: 2099-01-01\n    expected_rows: 7\n"
                + "    match:\n      sections: [REG]\n"
                + "  - id: PENDING-TBD\n    status: pending\n    reason: not landed yet\n"
                + "    expires: 2099-01-01\n    expected_rows: TBD\n"
                + "    match:\n      sections: [REG]\n";
        List<GoldenRules.Rule> rules = GoldenRules.load(yaml(body)).rules();
        assertEquals("the fixture itself must parse as three rules", 3, rules.size());

        List<String> flagged = unpinnedActiveRuleIds(rules);
        assertEquals("expected exactly the active+TBD rule to be flagged, got " + flagged,
                1, flagged.size());
        assertEquals("ACTIVE-TBD", flagged.get(0));
    }

    /**
     * And the end-to-end half of the control: the committed-file assertion itself must fail when a
     * rule in a real rules file is flipped to {@code active} + {@code TBD}.
     *
     * <p>{@link #theUnpinnedActiveRuleCheckIsDiscriminating} proves the predicate discriminates;
     * this proves the <em>test</em> built on it actually fails, with a message that names the rule.
     * The distinction is not academic — the retracted {@code NoGeoApiInCoreTest} precedent in this
     * project was a check whose predicate was fine and whose surrounding assertion could never fire.
     * It is not academic here either: the first version of this control failed on
     * {@code "rules.yaml should carry the seeded declarations"}, because a one-rule fixture tripped
     * the size guard before the TBD assertion was ever reached. The fixture below therefore carries
     * <b>six</b> rules — five correctly pinned and one offender — so the offender has to be found
     * <em>among</em> compliant siblings rather than by being the only rule present.
     */
    @Test
    public void theCommittedFileAssertionFailsOnAnInjectedTbd() throws Exception {
        StringBuilder body = new StringBuilder("version: 1\nrules:\n");
        for (int i = 0; i < 5; i++) {
            body.append("  - id: PINNED-SIBLING-").append(i).append("\n    status: active\n")
                    .append("    reason: a correctly pinned neighbour, so the offender is not the")
                    .append(" only rule in the file\n")
                    .append("    expires: 2099-01-01\n    expected_rows: ").append(i + 1).append("\n")
                    .append("    match:\n      sections: [REG]\n");
        }
        body.append("  - id: INJECTED-CONTROL\n    status: active\n")
                .append("    reason: a deliberately unpinned active rule, injected by the positive")
                .append(" control\n")
                .append("    expires: 2099-01-01\n    expected_rows: TBD\n")
                .append("    match:\n      sections: [REG]\n");
        File injected = yaml(body.toString());
        String previous = System.getProperty("golden.dir");
        System.setProperty("golden.dir", injected.getParentFile().getAbsolutePath());
        File renamed = new File(injected.getParentFile(), "rules.yaml");
        assertTrue("could not stage the injected rules.yaml", injected.renameTo(renamed));
        try {
            noActiveRuleMayLeaveItsExpectedRowsUnpinned();
            fail("the committed-file assertion passed on a rules.yaml containing an active TBD rule"
                    + " -- it cannot detect the thing it exists to detect");
        } catch (AssertionError expected) {
            assertTrue("the failure must name the offending rule, was: " + expected.getMessage(),
                    expected.getMessage().contains("INJECTED-CONTROL"));
        } finally {
            if (previous == null) {
                System.clearProperty("golden.dir");
            } else {
                System.setProperty("golden.dir", previous);
            }
        }
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}

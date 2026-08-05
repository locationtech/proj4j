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
 */
package org.locationtech.proj4j.benchmark.gate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.locationtech.proj4j.benchmark.CrsPair;
import org.locationtech.proj4j.benchmark.counting.OpCountRecorder;
import org.locationtech.proj4j.benchmark.counting.OpCounters;

/**
 * The blocking performance gate: Tier 1 (allocation bytes per operation) and Tier 2 (deterministic
 * transcendental-call counts). Exits non-zero on breach with a message naming the offending benchmark
 * and the before/after figures.
 *
 * <h2>Why these two tiers block and ns/op does not</h2>
 *
 * <p><b>Tier 1 - allocation.</b> {@code -prof gc}'s {@code gc.alloc.rate.norm} is bytes per operation:
 * total bytes allocated divided by operations performed. It is a property of the <b>bytecode</b>, not
 * of the machine. The same jar reports the same number on a laptop, a shared CI runner and a container
 * under memory pressure, because none of those change how many objects a method constructs. <b>It does
 * not flake</b>, so it is safe to block on, and it catches precisely the regressions that matter here:
 * a reintroduced {@code new double[1]} out-param, a {@code List} iterator on a per-point path, a boxing
 * {@code Objects.hash} on the cache-lookup path.
 *
 * <p><b>Tier 2 - operation counts.</b> Fully deterministic: one transform, one pinned input, integer
 * counters. Two runs on two architectures agree exactly or one has a bug. It catches <i>algorithmic</i>
 * regressions - a Newton loop that gained a trip, a closed form that reverted to an iteration, a
 * {@code pow} reintroduced where an {@code exp} was - and, unlike a timing, <b>it explains them</b>:
 * "{@code pow} went from 0 to 5" points at the cause, where "12% slower" points at nothing. It is also
 * the only tier that can distinguish "the machine was busy" from "the algorithm changed".
 *
 * <p><b>Tier 3 - absolute ns/op - is deliberately not implemented here, and that is a design decision,
 * not an omission.</b> JMH on shared CI runners varies <b>plus or minus 20-40%</b>: no CPU pinning, no
 * turbo control, noisy neighbours, and a scheduler that will move a fork mid-iteration. A gate with a
 * 20-40% false-positive rate gets muted, then ignored, then deleted - historically within about a
 * month - and its absence is then invisible. So timing lives on a nightly job on a dedicated runner
 * (pinned CPU, turbo off, {@code performance} governor) and alerts only on a <b>&gt;10% regression
 * sustained across three consecutive nights</b>, which filters single-night noise. Where timing is used
 * for a decision rather than an alert, the candidate and a fixed reference are run <b>in the same JMH
 * fork</b> and the <b>ratio</b> is what is compared, so machine speed cancels - that is why
 * {@code SolverBenchmark} and {@code BulkTransformBenchmark} keep their control arms in the same class.
 *
 * <h2>The ratchet</h2>
 *
 * <p>Each Tier 1 rule carries two numbers. {@code targetBytesPerOp} is the <b>policy</b>: what the
 * number should be once {@code reference/performance.md}'s work has landed (0 for every bulk method, 40
 * for single-point, 0 for {@code GridShiftBenchmark.inverseShift}). {@code maxBytesPerOp} is the
 * <b>ratchet</b>: what it actually is today. The gate fails on exceeding the <i>ratchet</i>, warns
 * while the ratchet is above the target, and reports when an observation comes in low enough that the
 * ratchet can be tightened.
 *
 * <p>Asserting the target outright today would leave the gate red on {@code master}, and a permanently
 * red gate is a disabled gate. Asserting only the ratchet gives a monotone approach to the target with
 * no window in which a regression can slip through. The gap between the two columns is also a
 * legible, reviewable to-do list.
 *
 * <h2>Tier 1 exclusions: {@code tier1Gated: false}</h2>
 *
 * <p><b>An exclusion is a reduction in coverage, and this class is written so that it cannot read as a
 * silent weakening.</b> A rule with {@code "tier1Gated": false} still matches, still fails if it matches
 * nothing (so a rename cannot un-gate it), and its arms are still measured, still recorded by
 * {@code --record} and still printed on every run with their delta against the recorded figure - but a
 * breach is <b>reported, not blocked</b>. The report says exactly that, in those words, in its own
 * section and again in the final verdict line, on passing runs as well as failing ones.
 *
 * <p>Two things are mandatory on an excluded rule, and their absence is a hard error rather than a
 * warning:
 *
 * <ul>
 *   <li>{@code exclusionReason} - prose in the baseline file saying what stops being gated and why.
 *       A commit message is not a substitute; nobody reads it a year later.</li>
 *   <li>{@code exclusionArmCount} - the number of arms the exclusion is expected to cover. If the
 *       pattern starts matching more (or fewer) arms than declared, the gate <b>fails</b>. An
 *       exclusion that over-matches is precisely how a gate quietly stops working, and pinning the
 *       count is the same device that caught a golden rule claiming 270 rows when it explained 95.</li>
 * </ul>
 *
 * <p>Exclusion is per <i>rule</i>, not per measurement: an arm matched by an excluded rule <b>and</b> by
 * some other, gated rule is still gated by that other rule. Two rules on one benchmark is an existing,
 * deliberate pattern here (see {@code gridshift-dispatch-zero} and {@code allocation-gridshift-dispatch}),
 * so the exclusion cannot be used to launder an arm out of a gate it also belongs to.
 *
 * <p><b>There are no exclusions today.</b> No rule in {@code allocation-baseline.json} carries
 * {@code tier1Gated: false}, and a run reports {@code 245 gated, 0 EXCLUDED}. This javadoc used to say
 * <em>"the only current exclusion is {@code crs-parse}"</em>; that rule rejoined Tier 1 on 2026-08-02
 * when {@code io/InitFileCache} removed the per-call dictionary re-scan that made its allocation
 * data-dependent, and {@code tier1Gated}, {@code exclusionArmCount} and {@code exclusionReason} were
 * deleted from it together. The machinery above is therefore live and self-tested but unused — the
 * user was removed, not the feature. The whole arc is in the "Coverage" section of
 * {@code benchmark/README.md}, and the rule to carry forward is that an exclusion must be written with
 * its own repayment condition, as that one was.
 */
public final class GateChecker {

    private static final String DEFAULT_ALLOC_BASELINE = "/baseline/allocation-baseline.json";
    private static final String DEFAULT_OP_COUNTS = "/baseline/op-counts.json";
    private static final String DEFAULT_BASELINE_DIR = "src/main/resources/baseline";

    /** The sentinel for "not captured yet". */
    private static final String TBD = "TBD";

    /**
     * Slack on the allocation comparison. {@code gc.alloc.rate.norm} is a ratio of two sampled totals,
     * so a genuinely non-allocating method sometimes reports 1e-4 rather than 0. Half a byte is three
     * decimal orders below the 16-byte minimum object header, so no real allocation can hide under it.
     */
    private static final double ALLOC_ABSOLUTE_SLACK_BYTES = 0.5;

    /** Proportional slack, for large ratchets where the sampling error scales. */
    private static final double ALLOC_RELATIVE_SLACK = 0.001;

    private final List<String> failures = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    /**
     * Per-rule banners for Tier 1 exclusions, and the per-arm figures underneath them. Kept separate
     * from {@link #notes} on purpose: a note is a passing observation, whereas every line here is a
     * measurement that <b>nothing is gating</b>, and that has to be legible at a glance rather than
     * buried in 160 lines of "at target".
     */
    private final List<String> exclusionBanners = new ArrayList<>();
    private final List<String> exclusionRows = new ArrayList<>();
    private int excludedArms;

    public static void main(String[] args) {
        try {
            System.exit(new GateChecker().run(args));
        } catch (UsageException e) {
            System.err.println("gate: " + e.getMessage());
            System.err.println();
            printUsage(System.err);
            System.exit(2);
        } catch (Exception e) {
            System.err.println("gate: internal error");
            e.printStackTrace(System.err);
            System.exit(2);
        }
    }

    int run(String[] args) throws IOException {
        Options options = Options.parse(args);
        if (options.help) {
            printUsage(System.out);
            return 0;
        }
        if (options.selfTest) {
            return selfTest();
        }

        if (options.record) {
            return record(options);
        }

        System.out.println("proj4j performance gate");
        System.out.println("=======================");

        if (!options.skipTier1) {
            checkAllocation(options);
        } else {
            notes.add("Tier 1 skipped by --skip-tier1.");
        }

        if (!options.skipTier2) {
            checkOpCounts(options);
        } else {
            notes.add("Tier 2 skipped by --skip-tier2.");
        }

        return report();
    }

    // ============================================================================================
    // Tier 1
    // ============================================================================================

    private void checkAllocation(Options options) throws IOException {
        System.out.println();
        System.out.println("Tier 1 - allocation (bytes/op, blocking)");
        System.out.println("---------------------------------------");

        if (options.jmhResult == null) {
            failures.add("Tier 1 needs a JMH result file. Run the benchmarks with "
                    + "`-prof gc -rf json -rff jmh-result.json` and pass that file, or pass "
                    + "--skip-tier1 if this invocation is Tier 2 only.");
            return;
        }

        List<Measurement> measurements = readJmhResults(options.jmhResult);
        System.out.println("  " + measurements.size() + " benchmark results from " + options.jmhResult);

        Map<String, Object> baseline = Json.asObject(
                Json.parse(readText(options.allocBaseline, DEFAULT_ALLOC_BASELINE)),
                "allocation baseline");
        List<Object> rules = Json.asArray(baseline.get("rules"), "allocation baseline: rules");

        // Per-measurement ceilings, keyed by "SimpleClass.method[param=value]". A rule's
        // maxBytesPerOp is a single number over every arm it matches, and some rules legitimately
        // cover arms that differ by two orders of magnitude - CrsParseBenchmark's EARLY and LATE
        // parameters differ by ~37x precisely because that spread is the finding. A single ceiling
        // set by the worst arm would leave the cheap arms ungated. So the per-key ratchet is the
        // primary ceiling where one exists, and the rule's number is the fallback and the class-level
        // cap.
        Map<String, Object> ratchets = baseline.get("ratchets") instanceof Map
                ? Json.asObject(baseline.get("ratchets"), "allocation baseline: ratchets")
                : Map.of();

        int checked = 0;
        for (Object ruleObject : rules) {
            Map<String, Object> rule = Json.asObject(ruleObject, "allocation rule");
            String id = Json.asString(rule.get("id"), "allocation rule: id");
            Pattern pattern = compile(Json.asString(rule.get("match"), "rule " + id + ": match"), id);
            boolean required = !Boolean.FALSE.equals(rule.get("required"));

            List<Measurement> matches = new ArrayList<>();
            for (Measurement m : measurements) {
                if (pattern.matcher(m.shortKey).find() || pattern.matcher(m.fqn).find()) {
                    matches.add(m);
                }
            }

            if (matches.isEmpty()) {
                if (required) {
                    // A renamed or deleted benchmark must not silently un-gate itself. This is the
                    // failure mode that makes long-lived gates quietly stop gating.
                    failures.add("rule '" + id + "' matched no benchmark in the JMH results. Either "
                            + "the benchmark was renamed (update the rule's `match`) or it was not "
                            + "run (check the run command's -e exclusions). Pattern: "
                            + pattern.pattern());
                } else {
                    notes.add("rule '" + id + "' matched nothing, and is marked not required "
                            + "(staged benchmark).");
                }
                continue;
            }

            Double ruleCeiling = number(rule.get("maxBytesPerOp"));
            Double target = number(rule.get("targetBytesPerOp"));

            // ---- Tier 1 exclusion -------------------------------------------------------------
            // `tier1Gated: false` means this rule's arms are MEASURED, RECORDED and REPORTED but a
            // breach does not block. It exists for arms whose subject IS data-dependent allocation,
            // which never satisfied Tier 1's fixed-object-graph premise - see the rule's own
            // exclusionReason. Two mandatory companions, both hard errors when missing, because an
            // undocumented or unbounded exclusion is worse than no exclusion at all.
            boolean tier1Gated = !Boolean.FALSE.equals(rule.get("tier1Gated"));
            long declaredExclusionArms = -1;
            if (!tier1Gated) {
                String reason = rule.get("exclusionReason") == null ? null
                        : Json.asString(rule.get("exclusionReason"),
                                "rule " + id + ": exclusionReason");
                if (reason == null || reason.trim().isEmpty()) {
                    throw new UsageException("rule '" + id + "' sets tier1Gated=false but carries no "
                            + "`exclusionReason`. An exclusion is a REDUCTION IN COVERAGE and may not "
                            + "be silent: say in the baseline file what stops being gated and why, "
                            + "where the next reader will find it.");
                }
                Double declared = number(rule.get("exclusionArmCount"));
                if (declared == null) {
                    throw new UsageException("rule '" + id + "' sets tier1Gated=false but carries no "
                            + "`exclusionArmCount`. The count is pinned by hand so the exclusion "
                            + "cannot silently widen - an exclusion that over-matches is exactly how "
                            + "a gate quietly stops working. Set it to the number of arms this rule "
                            + "is meant to cover.");
                }
                declaredExclusionArms = (long) (double) declared;
                excludedArms += matches.size();
                exclusionBanners.add("rule '" + id + "' is EXCLUDED from Tier 1 ratcheting "
                        + "(tier1Gated=false): " + matches.size() + " arm(s) below are REPORTED, "
                        + "NOT BLOCKED. A regression in any of them will not fail this gate.");
                for (String line : wrap(reason, 96)) {
                    exclusionBanners.add("    " + line);
                }
                if (matches.size() != declaredExclusionArms) {
                    // Over-matching is the dangerous direction (a real regression elsewhere gets
                    // swallowed); under-matching means an arm vanished. Both are a broken exclusion.
                    StringBuilder names = new StringBuilder();
                    for (Measurement m : matches) {
                        names.append("\n              ").append(m.shortKey);
                    }
                    failures.add("EXCLUSION SCOPE CHANGED  rule '" + id + "'\n"
                            + "        declared:  exclusionArmCount = " + declaredExclusionArms + "\n"
                            + "        matched:   " + matches.size() + " arm(s)" + names + "\n"
                            + "        An excluded rule is not gated, so if its pattern spreads it "
                            + "takes other benchmarks\n"
                            + "        out of Tier 1 with it, silently. Either narrow `match`, or "
                            + "bump `exclusionArmCount`\n"
                            + "        deliberately and say in the commit message which arms stopped "
                            + "being gated.");
                }
            }

            for (Measurement m : matches) {
                checked++;
                if (m.allocBytesPerOp == null) {
                    warnings.add(m.shortKey + ": no gc.alloc.rate.norm in the results. Tier 1 cannot "
                            + "check it. Add `-prof gc` to the run command.");
                    continue;
                }
                double observed = m.allocBytesPerOp;

                Double perKey = ratchets.containsKey(m.shortKey)
                        ? number(ratchets.get(m.shortKey)) : null;
                Double ratchet = perKey != null ? perKey : ruleCeiling;
                String ratchetSource = perKey != null ? "per-benchmark ratchet" : "rule ceiling";

                if (!tier1Gated) {
                    // Keep reporting the numbers as information. The recorded figure and the drift
                    // from it are the whole point of still measuring the arm; printing only the
                    // observation would turn the exclusion into an omission.
                    String against;
                    if (ratchet == null) {
                        against = "no recorded reference (" + TBD + ")";
                    } else if (ratchet == 0.0) {
                        against = String.format(Locale.ROOT, "recorded %s, delta %+.1f",
                                fmt(ratchet), observed - ratchet);
                    } else {
                        against = String.format(Locale.ROOT, "recorded %s, delta %+.1f (%+.3f%%)",
                                fmt(ratchet), observed - ratchet,
                                100.0 * (observed - ratchet) / ratchet);
                    }
                    exclusionRows.add(String.format(Locale.ROOT, "%-58s %14s B/op   %s",
                            m.shortKey, fmt(observed), against));
                    continue;
                }

                if (ratchet == null) {
                    String message = m.shortKey + ": rule '" + id + "' has maxBytesPerOp = " + TBD
                            + "; observed " + fmt(observed) + " B/op. Populate the baseline with "
                            + "`--record` (see benchmark/README.md, \"Refreshing a baseline\").";
                    if (options.requireBaseline) {
                        failures.add(message);
                    } else {
                        warnings.add(message);
                    }
                    continue;
                }

                double allowance = ratchet
                        + Math.max(ALLOC_ABSOLUTE_SLACK_BYTES, ratchet * ALLOC_RELATIVE_SLACK);
                if (observed > allowance) {
                    failures.add(String.format(Locale.ROOT,
                            "ALLOCATION REGRESSION  %s%n"
                            + "        rule:      %s%n"
                            + "        before:    %s B/op  (checked-in %s)%n"
                            + "        after:     %s B/op  (this run)%n"
                            + "        delta:     %+.1f B/op%n"
                            + "        target:    %s B/op%n"
                            + "        why it matters: %s",
                            m.shortKey, id, fmt(ratchet), ratchetSource,
                            fmt(observed), observed - ratchet,
                            target == null ? TBD : fmt(target),
                            rule.containsKey("why")
                                    ? Json.asString(rule.get("why"), "rule " + id + ": why")
                                    : "(no `why` recorded on this rule - add one)"));
                } else if (target != null && observed <= target + ALLOC_ABSOLUTE_SLACK_BYTES) {
                    notes.add(m.shortKey + ": " + fmt(observed) + " B/op, at target ("
                            + fmt(target) + ").");
                } else if (ratchet > 0 && observed < ratchet * 0.95) {
                    notes.add(m.shortKey + ": " + fmt(observed) + " B/op, below the ratchet of "
                            + fmt(ratchet) + ". Tighten it with `--record` so the improvement is "
                            + "locked in.");
                }
            }
        }
        System.out.println("  " + checked + " measurement(s) matched a rule");
        System.out.println("  " + (checked - excludedArms) + " gated, " + excludedArms
                + " EXCLUDED from ratcheting (reported, not blocked - see below)");
    }

    // ============================================================================================
    // Tier 2
    // ============================================================================================

    private void checkOpCounts(Options options) throws IOException {
        System.out.println();
        System.out.println("Tier 2 - transcendental call counts (blocking)");
        System.out.println("---------------------------------------------");

        Map<String, Object> baseline = Json.asObject(
                Json.parse(readText(options.opCounts, DEFAULT_OP_COUNTS)), "op-count baseline");
        Map<String, Object> expectedPairs =
                Json.asObject(baseline.get("pairs"), "op-count baseline: pairs");

        OpCountRecorder.Result observed = OpCountRecorder.recordAll();
        System.out.println("  instrumented " + observed.rewrittenClasses() + " core classes, "
                + observed.rewrittenReferences() + " Math/StrictMath references redirected");
        System.out.println("  " + observed.countsByPair().size() + " CRS pairs measured");

        for (Map.Entry<String, long[]> entry : observed.countsByPair().entrySet()) {
            String pairName = entry.getKey();
            long[] counts = entry.getValue();

            Object expectedObject = expectedPairs.get(pairName);
            if (expectedObject == null) {
                warnings.add("CRS pair " + pairName + " has no entry in the op-count baseline. It was "
                        + "added to CrsPair without a baseline refresh; run `--record`. Observed: "
                        + describeCounts(counts));
                continue;
            }
            Map<String, Object> expected = Json.asObject(expectedObject, "op counts for " + pairName);

            for (int i = 0; i < OpCounters.opCount(); i++) {
                OpCounters.Op op = OpCounters.op(i);
                String key = OpCounters.key(op);
                if (!expected.containsKey(key)) {
                    // Absent means "not pinned for this pair". Tolerated so that adding an Op does not
                    // invalidate every pair at once, but reported so it does not stay absent.
                    if (counts[i] != 0) {
                        warnings.add(pairName + '.' + key + ": not pinned in the baseline, observed "
                                + counts[i] + ". Run `--record`.");
                    }
                    continue;
                }
                Double pinned = number(expected.get(key));
                if (pinned == null) {
                    String message = pairName + '.' + key + ": baseline is " + TBD + ", observed "
                            + counts[i] + ". Run `--record` to pin it.";
                    if (options.requireBaseline) {
                        failures.add(message);
                    } else {
                        warnings.add(message);
                    }
                    continue;
                }
                long expectedCount = (long) (double) pinned;
                if (expectedCount != counts[i]) {
                    failures.add(String.format(Locale.ROOT,
                            "OPERATION COUNT CHANGED  %s  [%s]%n"
                            + "        call:      %s%n"
                            + "        before:    %d per transform  (checked-in baseline)%n"
                            + "        after:     %d per transform  (this run)%n"
                            + "        delta:     %+d%n"
                            + "        This is deterministic, so it is a real algorithmic change, not "
                            + "noise.%n"
                            + "        If it is intended (e.g. a numerics.md rewrite landed), refresh "
                            + "with `--record`%n"
                            + "        and say so in the commit message. If it is not, an iteration "
                            + "count changed.",
                            pairName, CrsPair.valueOf(pairName).describe(), key,
                            expectedCount, counts[i], counts[i] - expectedCount));
                }
            }
        }

        for (String pairName : expectedPairs.keySet()) {
            if (pairName.startsWith("_")) {
                continue;
            }
            if (!observed.countsByPair().containsKey(pairName)) {
                failures.add("CRS pair " + pairName + " is in the op-count baseline but was not "
                        + "measured. It was removed from CrsPair without updating the baseline; "
                        + "either restore it or run `--record`.");
            }
        }
    }

    private static String describeCounts(long[] counts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(OpCounters.key(OpCounters.op(i))).append('=').append(counts[i]);
        }
        return sb.length() == 0 ? "(all zero)" : sb.toString();
    }

    // ============================================================================================
    // Baseline refresh
    // ============================================================================================

    private int record(Options options) throws IOException {
        Path dir = Paths.get(options.baselineDir);
        if (!Files.isDirectory(dir)) {
            throw new UsageException("--record needs a writable baseline directory; " + dir
                    + " is not one. Run from the benchmark/ module directory, or pass "
                    + "--baseline-dir.");
        }
        Map<String, Object> capturedAt = capturedAt(options);

        System.out.println("Refreshing baselines in " + dir.toAbsolutePath());
        System.out.println("  captured at: " + capturedAt);

        // ---- op counts -------------------------------------------------------------------------
        OpCountRecorder.Result observed = OpCountRecorder.recordAll();
        Map<String, Object> opDoc = new LinkedHashMap<>();
        opDoc.put("_format", 1.0);
        opDoc.put("_doc", List.of(
                "Tier 2 baseline: transcendental calls per single transform, per CRS pair.",
                "Fully deterministic - one transform of the pinned sample point in CrsPair.",
                "Regenerate with: java -cp target/benchmarks.jar "
                        + "org.locationtech.proj4j.benchmark.gate.GateChecker --record",
                "A count that DROPS is the expected outcome of a numerics.md rewrite; say so in the "
                        + "commit message.",
                "A count that RISES is an extra iteration or a reintroduced transcendental."));
        opDoc.put("capturedAt", capturedAt);
        Map<String, Object> pairs = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> e : observed.countsByPair().entrySet()) {
            Map<String, Object> ops = new LinkedHashMap<>();
            ops.put("_crs", CrsPair.valueOf(e.getKey()).describe());
            for (int i = 0; i < OpCounters.opCount(); i++) {
                ops.put(OpCounters.key(OpCounters.op(i)), (double) e.getValue()[i]);
            }
            pairs.put(e.getKey(), ops);
        }
        opDoc.put("pairs", pairs);
        Path opPath = dir.resolve("op-counts.json");
        Files.write(opPath, Json.write(opDoc).getBytes(StandardCharsets.UTF_8));
        System.out.println("  wrote " + opPath + "  (" + pairs.size() + " pairs, "
                + observed.rewrittenReferences() + " references instrumented)");

        // ---- allocation ------------------------------------------------------------------------
        if (options.jmhResult == null) {
            System.out.println("  allocation-baseline.json NOT refreshed: no JMH result supplied.");
            System.out.println("  Re-run with a `-prof gc -rf json` result file to ratchet Tier 1.");
            return 0;
        }
        List<Measurement> measurements = readJmhResults(options.jmhResult);
        Map<String, Object> existing = Json.asObject(
                Json.parse(readText(options.allocBaseline, DEFAULT_ALLOC_BASELINE)),
                "allocation baseline");
        List<Object> rules = Json.asArray(existing.get("rules"), "allocation baseline: rules");

        Map<String, Object> ratchets = new TreeMap<>();
        for (Object ruleObject : rules) {
            Map<String, Object> rule = Json.asObject(ruleObject, "allocation rule");
            String id = Json.asString(rule.get("id"), "allocation rule: id");
            Pattern pattern = compile(Json.asString(rule.get("match"), "rule " + id + ": match"), id);
            boolean ratchetable = !Boolean.FALSE.equals(rule.get("ratchetable"));
            boolean tier1Gated = !Boolean.FALSE.equals(rule.get("tier1Gated"));
            double worst = -1.0;
            for (Measurement m : measurements) {
                if (m.allocBytesPerOp == null) {
                    continue;
                }
                if (pattern.matcher(m.shortKey).find() || pattern.matcher(m.fqn).find()) {
                    worst = Math.max(worst, m.allocBytesPerOp);
                    if (ratchetable) {
                        // Per-benchmark ratchet, so a rule spanning arms that differ by orders of
                        // magnitude still gates each arm at its own figure. Deliberately NOT written
                        // for non-ratchetable rules: a per-key entry there would override the
                        // normative rule ceiling and silently widen it, which is the one thing that
                        // flag exists to prevent.
                        ratchets.put(m.shortKey, Math.max(0.0, Math.rint(m.allocBytesPerOp)));
                    }
                }
            }
            if (worst < 0) {
                System.out.println("  rule '" + id + "': no measurement, left as-is");
                continue;
            }
            if (!ratchetable) {
                // Some rules are normative, not empirical: every bulk method must be 0 because the
                // contract says so, and the Math-intrinsic canary must be 0 because a nonzero reading
                // means the measurement itself is broken. Letting --record widen those would silently
                // convert an assertion into an observation, which is how a gate stops gating.
                System.out.println("  rule '" + id + "': ratchetable=false, left at "
                        + fmt(number(rule.get("maxBytesPerOp"))) + " (observed " + fmt(worst) + ")");
                continue;
            }
            // Round to the NEAREST whole byte, not up. A ratchet with a fractional part reads as
            // spurious precision, and rounding up would add a byte of headroom on every refresh - over
            // a few refreshes that becomes real slack. Rounding down is safe because the comparison
            // carries at least ALLOC_ABSOLUTE_SLACK_BYTES of tolerance, which is three decimal orders
            // below the 16-byte minimum object header, so no real allocation can hide in it.
            double ratchet = Math.max(0.0, Math.rint(worst));
            Object before = rule.get("maxBytesPerOp");
            rule.put("maxBytesPerOp", ratchet);
            System.out.println("  rule '" + id + "': maxBytesPerOp "
                    + (before instanceof String ? TBD : fmt(number(before))) + " -> " + fmt(ratchet)
                    + "  (observed " + fmt(worst) + ")"
                    // The numbers ARE still recorded for an excluded rule - that is what keeps them
                    // reportable - so say plainly here that recording them does not gate them.
                    // Otherwise a --record log reads as if every rule it lists is enforced.
                    + (tier1Gated ? ""
                       : "   [tier1Gated=false: RECORDED FOR REPORTING ONLY, NOT ENFORCED]"));
        }

        Object previousRatchets = existing.get("ratchets");
        if (previousRatchets instanceof Map) {
            // Preserve any leading _note in the ratchets block, which is the only prose in it.
            for (Map.Entry<String, Object> e
                    : Json.asObject(previousRatchets, "ratchets").entrySet()) {
                if (e.getKey().startsWith("_")) {
                    ratchets.put(e.getKey(), e.getValue());
                }
            }
        }
        existing.put("capturedAt", capturedAt);
        existing.put("ratchets", ratchets);
        Path allocPath = dir.resolve("allocation-baseline.json");
        Files.write(allocPath, Json.write(existing).getBytes(StandardCharsets.UTF_8));
        System.out.println("  wrote " + allocPath + "  (" + ratchets.size()
                + " per-benchmark ratchets)");
        return 0;
    }

    private static Map<String, Object> capturedAt(Options options) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("commit", options.commit != null ? options.commit : gitCommit());
        m.put("date", java.time.Instant.now().toString());
        m.put("jdk", System.getProperty("java.vm.version", "unknown"));
        m.put("arch", System.getProperty("os.arch", "unknown"));
        m.put("os", System.getProperty("os.name", "unknown"));
        return m;
    }

    /**
     * Best-effort commit id. A baseline that does not say which tree it came from is not reviewable -
     * you cannot tell whether a differing number is a regression or a different starting point.
     */
    private static String gitCommit() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return p.waitFor() == 0 && !out.isEmpty() ? out : "unknown";
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "unknown";
        }
    }

    // ============================================================================================
    // JMH result reading
    // ============================================================================================

    /** One JMH benchmark result, reduced to what the gate uses. */
    private static final class Measurement {
        final String fqn;
        /** {@code SimpleClass.method[param=value]}, which is what baseline rules match against. */
        final String shortKey;
        final Double allocBytesPerOp;
        final Double primaryScore;
        final String primaryUnit;

        Measurement(String fqn, String shortKey, Double allocBytesPerOp,
                    Double primaryScore, String primaryUnit) {
            this.fqn = fqn;
            this.shortKey = shortKey;
            this.allocBytesPerOp = allocBytesPerOp;
            this.primaryScore = primaryScore;
            this.primaryUnit = primaryUnit;
        }
    }

    private List<Measurement> readJmhResults(String path) throws IOException {
        String text = Files.readString(Paths.get(path), StandardCharsets.UTF_8);
        List<Object> array = Json.asArray(Json.parse(text), "JMH result file " + path);
        List<Measurement> out = new ArrayList<>(array.size());
        for (Object element : array) {
            Map<String, Object> record = Json.asObject(element, "JMH result element");
            String fqn = Json.asString(record.get("benchmark"), "JMH result: benchmark");

            StringBuilder shortKey = new StringBuilder();
            int lastDot = fqn.lastIndexOf('.');
            int secondLastDot = lastDot < 0 ? -1 : fqn.lastIndexOf('.', lastDot - 1);
            shortKey.append(secondLastDot < 0 ? fqn : fqn.substring(secondLastDot + 1));

            Object paramsObject = record.get("params");
            if (paramsObject instanceof Map) {
                Map<String, Object> params = Json.asObject(paramsObject, "JMH result: params");
                if (!params.isEmpty()) {
                    shortKey.append('[');
                    boolean first = true;
                    for (Map.Entry<String, Object> e : new TreeMap<>(params).entrySet()) {
                        if (!first) {
                            shortKey.append(',');
                        }
                        first = false;
                        shortKey.append(e.getKey()).append('=').append(e.getValue());
                    }
                    shortKey.append(']');
                }
            }

            Double alloc = null;
            Object secondary = record.get("secondaryMetrics");
            if (secondary instanceof Map) {
                for (Map.Entry<String, Object> e
                        : Json.asObject(secondary, "JMH result: secondaryMetrics").entrySet()) {
                    // JMH prefixes profiler metrics with a middle dot (U+00B7). Match on the suffix so
                    // the gate does not depend on that character surviving an encoding round trip.
                    if (e.getKey().endsWith("gc.alloc.rate.norm")) {
                        alloc = number(Json.asObject(e.getValue(), "gc.alloc.rate.norm").get("score"));
                    }
                }
            }

            Double score = null;
            String unit = null;
            Object primary = record.get("primaryMetric");
            if (primary instanceof Map) {
                Map<String, Object> pm = Json.asObject(primary, "JMH result: primaryMetric");
                score = number(pm.get("score"));
                Object u = pm.get("scoreUnit");
                unit = u instanceof String ? (String) u : null;
            }

            out.add(new Measurement(fqn, shortKey.toString(), alloc, score, unit));
        }
        return out;
    }

    // ============================================================================================
    // Self test
    // ============================================================================================

    /**
     * A dependency-free smoke test, so the harness's own plumbing is exercised without adding a test
     * framework to the reactor. Checks the two things whose silent failure would make the gate pass
     * vacuously: the JSON round trip, and that the bytecode rewrite is actually redirecting calls.
     */
    private int selfTest() {
        int problems = 0;

        Object parsed = Json.parse("{\"a\":[1,2.5,true,null,\"x\\n\"],\"b\":{\"c\":-3e2}}");
        String written = Json.write(parsed);
        if (!Json.write(Json.parse(written)).equals(written)) {
            System.err.println("SELF TEST FAILED: JSON write/parse/write is not stable");
            problems++;
        } else {
            System.out.println("ok  JSON round trip is stable");
        }

        try {
            OpCountRecorder.Result r = OpCountRecorder.recordAll();
            System.out.println("ok  bytecode rewrite active: " + r.rewrittenClasses()
                    + " classes, " + r.rewrittenReferences() + " references, "
                    + r.total() + " calls across " + r.countsByPair().size() + " pairs");
            for (Map.Entry<String, long[]> e : r.countsByPair().entrySet()) {
                System.out.println("      " + e.getKey() + ": " + describeCounts(e.getValue()));
            }
        } catch (RuntimeException e) {
            System.err.println("SELF TEST FAILED: " + e.getMessage());
            problems++;
        }

        return problems == 0 ? 0 : 1;
    }

    // ============================================================================================
    // Reporting and plumbing
    // ============================================================================================

    private int report() {
        System.out.println();
        if (!exclusionBanners.isEmpty()) {
            // Printed FIRST, above the notes, and on passing runs as well as failing ones. The
            // coverage this gate does not have is more important to see than the coverage it does.
            System.out.println("Tier 1 coverage exclusions - REPORTED, NOT GATED");
            System.out.println("------------------------------------------------");
            exclusionBanners.forEach(b -> System.out.println("  " + b));
            System.out.println();
            exclusionRows.forEach(r -> System.out.println("    i " + r));
            System.out.println();
        }
        if (!notes.isEmpty()) {
            System.out.println("Notes");
            System.out.println("-----");
            notes.forEach(n -> System.out.println("  - " + n));
            System.out.println();
        }
        if (!warnings.isEmpty()) {
            System.out.println("Warnings (not blocking)");
            System.out.println("-----------------------");
            warnings.forEach(w -> System.out.println("  ! " + w));
            System.out.println();
        }
        if (failures.isEmpty()) {
            System.out.println("GATE PASSED  (" + warnings.size() + " warning(s))" + excludedSuffix());
            return 0;
        }
        System.out.println("Failures (blocking)");
        System.out.println("-------------------");
        for (String f : failures) {
            System.out.println("  X " + f);
            System.out.println();
        }
        System.out.println("GATE FAILED  (" + failures.size() + " breach(es))" + excludedSuffix());
        return 1;
    }

    /**
     * A pass is not a clean bill of health while anything is excluded, so the verdict line says so
     * itself. Someone skimming CI output reads one line; that line has to carry the caveat.
     */
    private String excludedSuffix() {
        if (excludedArms == 0) {
            return "";
        }
        return "  -  " + excludedArms + " arm(s) NOT GATED, reported only "
                + "(see \"Tier 1 coverage exclusions\" above)";
    }

    /**
     * Greedy word wrap. The {@code exclusionReason} is deliberately long - it is the whole argument
     * for a coverage reduction - and an unwrapped paragraph in terminal output gets skipped, which
     * would defeat the point of printing it every run.
     */
    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static Pattern compile(String regex, String ruleId) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new UsageException("rule '" + ruleId + "' has an invalid `match` regex: "
                    + e.getMessage());
        }
    }

    /** {@code null} for the {@code "TBD"} sentinel, so unpinned entries are unambiguous. */
    private static Double number(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        if (o instanceof String) {
            String s = ((String) o).trim();
            if (s.isEmpty() || TBD.equalsIgnoreCase(s)) {
                return null;
            }
            try {
                return Double.valueOf(s);
            } catch (NumberFormatException e) {
                throw new UsageException("expected a number or \"" + TBD + "\", got \"" + s + '"');
            }
        }
        throw new UsageException("expected a number, got " + o.getClass().getSimpleName());
    }

    private static String fmt(Double d) {
        if (d == null) {
            return TBD;
        }
        return d == Math.rint(d) ? String.valueOf((long) (double) d)
                : String.format(Locale.ROOT, "%.3f", d);
    }

    /** Reads an override path if given, otherwise the checked-in classpath resource. */
    private static String readText(String overridePath, String classpathResource) throws IOException {
        if (overridePath != null) {
            return Files.readString(Paths.get(overridePath), StandardCharsets.UTF_8);
        }
        try (InputStream in = GateChecker.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new UsageException("baseline resource " + classpathResource
                        + " is not on the classpath, and no override path was given.");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class Options {
        String jmhResult;
        String allocBaseline;
        String opCounts;
        String baselineDir = DEFAULT_BASELINE_DIR;
        String commit;
        boolean skipTier1;
        boolean skipTier2;
        boolean requireBaseline;
        boolean record;
        boolean selfTest;
        boolean help;

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "-h": case "--help":       o.help = true; break;
                    case "--self-test":             o.selfTest = true; break;
                    case "--skip-tier1":            o.skipTier1 = true; break;
                    case "--skip-tier2":            o.skipTier2 = true; break;
                    case "--require-baseline":      o.requireBaseline = true; break;
                    case "--record":                o.record = true; break;
                    case "--jmh":                   o.jmhResult = value(args, ++i, a); break;
                    case "--alloc-baseline":        o.allocBaseline = value(args, ++i, a); break;
                    case "--op-counts":             o.opCounts = value(args, ++i, a); break;
                    case "--baseline-dir":          o.baselineDir = value(args, ++i, a); break;
                    case "--commit":                o.commit = value(args, ++i, a); break;
                    default:
                        if (a.startsWith("-")) {
                            throw new UsageException("unknown option " + a);
                        }
                        if (o.jmhResult != null) {
                            throw new UsageException("more than one JMH result file given: "
                                    + o.jmhResult + " and " + a);
                        }
                        o.jmhResult = a;
                }
            }
            return o;
        }

        private static String value(String[] args, int i, String option) {
            if (i >= args.length) {
                throw new UsageException(option + " needs a value");
            }
            return args[i];
        }
    }

    private static final class UsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    private static void printUsage(java.io.PrintStream out) {
        out.println("Usage: GateChecker [<jmh-result.json>] [options]");
        out.println();
        out.println("Tiers 1 and 2 of the performance gate. Exit 0 = pass, 1 = breach, 2 = usage.");
        out.println();
        out.println("  <jmh-result.json>      JMH output from `-prof gc -rf json -rff <file>`.");
        out.println("  --jmh <path>           Same, as a named option.");
        out.println("  --alloc-baseline <p>   Override /baseline/allocation-baseline.json.");
        out.println("  --op-counts <p>        Override /baseline/op-counts.json.");
        out.println("  --skip-tier1           Skip the allocation check (no JMH run needed).");
        out.println("  --skip-tier2           Skip the op-count check.");
        out.println("  --require-baseline     Treat a TBD baseline entry as a failure, not a warning.");
        out.println("  --record               Rewrite the baselines from this run.");
        out.println("  --baseline-dir <p>     Where --record writes. Default: " + DEFAULT_BASELINE_DIR);
        out.println("  --commit <sha>         Record this commit in capturedAt; default `git rev-parse`.");
        out.println("  --self-test            Exercise the gate's own plumbing and exit.");
        out.println();
        out.println("Tier 3 (absolute ns/op) is deliberately not implemented here. See this class's");
        out.println("javadoc and benchmark/README.md for why timing is never a PR gate.");
    }
}

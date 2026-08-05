/*
 * Copyright 2026 The Proj4J Contributors.
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
package org.locationtech.proj4j.conformance.report;

import java.util.List;
import org.locationtech.proj4j.conformance.manifest.AssertionOutcome;
import org.locationtech.proj4j.conformance.manifest.DiffClassification;
import org.locationtech.proj4j.conformance.manifest.DiffEntry;
import org.locationtech.proj4j.conformance.manifest.DiffResult;
import org.locationtech.proj4j.conformance.manifest.ObservedRun;

/**
 * Renders a conformance run as text.
 *
 * <p>Every method is a pure function of its arguments returning a {@link String}: no filesystem, no
 * clock, no logger, no {@code System.out}. That is what makes the exact wording of the headline — the
 * number this project is judged by — unit-testable, and it lets the caller decide whether the output
 * goes to the console, a file, or a CI annotation.
 *
 * <p>Line endings are always {@code \n}, never {@code System.lineSeparator()}, so a report committed
 * or uploaded from one machine compares byte-for-byte with one from another.
 *
 * <p><strong>Skips and vacuous expected failures are printed as their own numbers, everywhere.</strong>
 * There is no rendering in this class in which either is added to the pass count or omitted from the
 * headline; see {@link AssertionOutcome}.
 */
public final class ConformanceReport {

    /**
     * The number of assertions {@code gie} actually evaluates in the corpus at PROJ 9.8.1: <strong>6,962
     * {@code expect} + 961 {@code roundtrip} = 7,923</strong>, across 42 active files.
     *
     * <p>Passed explicitly to every renderer so a partial run (one file, one tier) can still report
     * against the real total rather than against itself — a run of one file that passes everything is
     * not 100% conformant.
     *
     * <p><strong>Corrected from 8,017.</strong> That figure counted {@code DHDN_ETRS89.gie}'s 94
     * out-of-block {@code expect} lines, which sit after {@code </gie-strict>} under a heading reading
     * "Tests for GK system zones to UTM32/33 not implemented yet". {@code gie} never reads them, the
     * lexer never emits them, and so the old denominator produced a report that said
     * {@code 2093/8017 … 94 not run} — an inconsistency in a single line of output, and an
     * understatement of the percentage by counting assertions that cannot be attempted. They are
     * reported instead as {@link #EXCLUDED_OUT_OF_BLOCK}, which is a separate fact rather than a
     * missing one.
     */
    public static final int TOTAL_ASSERTIONS = 7923;

    /**
     * The 94 {@code expect} lines in {@code gie/DHDN_ETRS89.gie} that lie outside its
     * {@code <gie-strict>} block (which closes at line 161 of 375) and are therefore never evaluated by
     * anything, upstream included.
     *
     * <p>Reported as an explicit line rather than dropped, so that the difference between this
     * denominator and the {@code grep -c '^expect'} count of 8,017 is visible in the output instead of
     * having to be rediscovered.
     */
    public static final int EXCLUDED_OUT_OF_BLOCK = 94;

    private static final String NEWLINE = "\n";
    private static final String[] FILE_TABLE_HEADERS = {"file", "pass", "fail", "skip", "vacuous", "total"};

    private ConformanceReport() {}

    /**
     * The one-line headline, e.g.
     * {@code "906/6736 genuine passes (5828 failing, 2 skipped); 1187 vacuous expect failure = UNMEASURED, excluded from both numerator and denominator; 94 excluded (out of block)"}.
     *
     * <h2>What the ratio is</h2>
     *
     * <p><strong>Genuine passes over assertions that are not vacuous.</strong> Both halves matter. The
     * numerator counts only {@link AssertionOutcome#PASS}, so a skip and a vacuous expected failure are
     * excluded from it. The denominator is {@code corpusSize} minus the vacuous count, so a vacuous
     * assertion is not scored as remaining work either — it is not work, it is an unknown. Folding it
     * into the denominator would make the percentage rise as soon as an implementation landed and
     * turned an unknown into a known failure, which is backwards.
     *
     * <p>The vacuous count is then stated separately and labelled {@code UNMEASURED}, in the same line,
     * so that no reader can quote the ratio without also seeing how much of the corpus it declines to
     * speak for. {@code ; N not run} and {@code ; N excluded (out of block)} are appended for the same
     * reason: "not run", "not evaluable", "failed" and "unmeasured" are four different problems with
     * four different owners.
     *
     * @param run the observed run
     * @param corpusSize the total the run is judged against, normally {@link #TOTAL_ASSERTIONS}
     * @param excludedOutOfBlock assertions upstream never evaluates, normally
     *     {@link #EXCLUDED_OUT_OF_BLOCK}; pass 0 for a synthetic run
     * @return the headline
     */
    public static String headline(ObservedRun run, int corpusSize, int excludedOutOfBlock) {
        int pass = run.count(AssertionOutcome.PASS);
        int fail = run.count(AssertionOutcome.FAIL);
        int skip = run.count(AssertionOutcome.SKIP);
        int vacuous = run.count(AssertionOutcome.VACUOUS_EXPECTED_FAILURE);
        StringBuilder out = new StringBuilder(160);
        out.append(pass)
                .append('/')
                .append(measuredDenominator(run, corpusSize))
                .append(" genuine passes (")
                .append(fail)
                .append(" failing, ")
                .append(skip)
                .append(" skipped)");
        if (vacuous > 0) {
            out.append("; ")
                    .append(vacuous)
                    .append(" vacuous expect failure = UNMEASURED, excluded from both numerator and denominator");
        }
        int notRun = corpusSize - run.total();
        if (notRun > 0) {
            out.append("; ").append(notRun).append(" not run");
        }
        if (excludedOutOfBlock > 0) {
            out.append("; ").append(excludedOutOfBlock).append(" excluded (out of block)");
        }
        return out.toString();
    }

    /**
     * @param run the observed run
     * @param corpusSize the total the run is judged against
     * @return {@link #headline(ObservedRun, int, int)} with no out-of-block exclusions, for synthetic
     *     runs that are not the real corpus
     */
    public static String headline(ObservedRun run, int corpusSize) {
        return headline(run, corpusSize, 0);
    }

    /**
     * @param run the observed run
     * @return the headline against the real corpus: {@link #TOTAL_ASSERTIONS} and
     *     {@link #EXCLUDED_OUT_OF_BLOCK}
     */
    public static String headline(ObservedRun run) {
        return headline(run, TOTAL_ASSERTIONS, EXCLUDED_OUT_OF_BLOCK);
    }

    /**
     * The denominator of the headline ratio: the assertions this run is entitled to be judged on.
     *
     * @param run the observed run
     * @param corpusSize the total the run is judged against
     * @return {@code corpusSize} less the vacuous count, never negative
     */
    public static int measuredDenominator(ObservedRun run, int corpusSize) {
        return Math.max(0, corpusSize - run.count(AssertionOutcome.VACUOUS_EXPECTED_FAILURE));
    }

    /**
     * A per-file breakdown, one row per corpus file, files alphabetically.
     *
     * <p>The {@code vacuous} column is the point of this table: it is where
     * {@code gie/adams_hemi.gie 0 703 0 388} is legible as "388 rows that look like passes and are not",
     * and where the old rendering said {@code 388 0} and read as a success.
     *
     * @param run the observed run
     * @return an aligned plain-text table, ending in a newline; the header row only, if the run is
     *     empty
     */
    public static String perFileTable(ObservedRun run) {
        int fileWidth = FILE_TABLE_HEADERS[0].length();
        for (String path : run.filePaths()) {
            fileWidth = Math.max(fileWidth, path.length());
        }
        int numberWidth = 7;
        StringBuilder out = new StringBuilder(128 + 64 * run.filePaths().size());
        appendRow(out, fileWidth, numberWidth, FILE_TABLE_HEADERS);
        appendRule(out, fileWidth, numberWidth);
        int totalPass = 0;
        int totalFail = 0;
        int totalSkip = 0;
        int totalVacuous = 0;
        for (String path : run.filePaths()) {
            int pass = run.countIn(path, AssertionOutcome.PASS);
            int fail = run.countIn(path, AssertionOutcome.FAIL);
            int skip = run.countIn(path, AssertionOutcome.SKIP);
            int vacuous = run.countIn(path, AssertionOutcome.VACUOUS_EXPECTED_FAILURE);
            totalPass += pass;
            totalFail += fail;
            totalSkip += skip;
            totalVacuous += vacuous;
            appendRow(
                    out,
                    fileWidth,
                    numberWidth,
                    new String[] {
                        path,
                        Integer.toString(pass),
                        Integer.toString(fail),
                        Integer.toString(skip),
                        Integer.toString(vacuous),
                        Integer.toString(pass + fail + skip + vacuous),
                    });
        }
        if (!run.filePaths().isEmpty()) {
            appendRule(out, fileWidth, numberWidth);
            appendRow(
                    out,
                    fileWidth,
                    numberWidth,
                    new String[] {
                        "TOTAL",
                        Integer.toString(totalPass),
                        Integer.toString(totalFail),
                        Integer.toString(totalSkip),
                        Integer.toString(totalVacuous),
                        Integer.toString(totalPass + totalFail + totalSkip + totalVacuous),
                    });
        }
        return out.toString();
    }

    /**
     * The build-relevant detail: every regression, every unexpected pass, every disappearance, with its
     * key and its reason.
     *
     * @param diff the classified diff
     * @return the detail sections; {@code "No regressions, unexpected passes or disappearances.\n"}
     *     when there is nothing to report
     */
    public static String differences(DiffResult diff) {
        StringBuilder out = new StringBuilder(256);
        boolean any = false;
        any |= appendSection(
                out,
                diff,
                DiffClassification.REGRESSED,
                "REGRESSED (was expected to pass, did not)");
        any |= appendSection(
                out,
                diff,
                DiffClassification.UNEXPECTED_PASS,
                "UNEXPECTED PASS (manifest says this still fails - regenerate to bank the win)");
        any |= appendSection(
                out,
                diff,
                DiffClassification.DISAPPEARED,
                "DISAPPEARED (known assertion was not executed)");
        if (!any) {
            out.append("No regressions, unexpected passes or disappearances.").append(NEWLINE);
        }
        return out.toString();
    }

    /**
     * A machine-readable summary for CI artifact upload: two tab-separated columns, {@code metric} and
     * {@code value}, one metric per line. No JSON library, no properties escaping rules, no ordering
     * surprises — {@code cut -f2} works, and so does {@code diff}.
     *
     * <p>Per-file rows are emitted as {@code file.<path>.<outcome>} so a CI dashboard can chart any
     * single file without re-parsing the human table.
     *
     * @param run the observed run
     * @param diff the classified diff
     * @param corpusSize the total the run is judged against, normally {@link #TOTAL_ASSERTIONS}
     * @param excludedOutOfBlock assertions upstream never evaluates, normally
     *     {@link #EXCLUDED_OUT_OF_BLOCK}
     * @return the summary text
     */
    public static String machineSummary(
            ObservedRun run, DiffResult diff, int corpusSize, int excludedOutOfBlock) {
        StringBuilder out = new StringBuilder(512);
        appendMetric(out, "conformance.corpus", corpusSize);
        appendMetric(out, "conformance.evaluated", run.total());
        // The headline ratio, as two metrics, so a dashboard cannot reconstruct it wrongly.
        appendMetric(out, "conformance.pass", run.count(AssertionOutcome.PASS));
        appendMetric(out, "conformance.measured_denominator", measuredDenominator(run, corpusSize));
        appendMetric(out, "conformance.fail", run.count(AssertionOutcome.FAIL));
        appendMetric(out, "conformance.skip", run.count(AssertionOutcome.SKIP));
        appendMetric(out, "conformance.vacuous", run.count(AssertionOutcome.VACUOUS_EXPECTED_FAILURE));
        appendMetric(out, "conformance.notrun", Math.max(0, corpusSize - run.total()));
        appendMetric(out, "conformance.excluded_out_of_block", excludedOutOfBlock);
        for (DiffClassification classification : DiffClassification.values()) {
            appendMetric(out, "diff." + classification.name().toLowerCase(java.util.Locale.ROOT), diff.count(classification));
        }
        appendMetric(out, "diff.outcome_changed", diff.outcomeChangedKeys().size());
        out.append("build.shouldFail").append('\t').append(diff.shouldFailBuild()).append(NEWLINE);
        for (String path : run.filePaths()) {
            appendMetric(out, "file." + path + ".pass", run.countIn(path, AssertionOutcome.PASS));
            appendMetric(out, "file." + path + ".fail", run.countIn(path, AssertionOutcome.FAIL));
            appendMetric(out, "file." + path + ".skip", run.countIn(path, AssertionOutcome.SKIP));
            appendMetric(
                    out,
                    "file." + path + ".vacuous",
                    run.countIn(path, AssertionOutcome.VACUOUS_EXPECTED_FAILURE));
        }
        return out.toString();
    }

    /**
     * @param run the observed run
     * @param diff the classified diff
     * @param corpusSize the total the run is judged against
     * @return {@link #machineSummary(ObservedRun, DiffResult, int, int)} with no out-of-block exclusions
     */
    public static String machineSummary(ObservedRun run, DiffResult diff, int corpusSize) {
        return machineSummary(run, diff, corpusSize, 0);
    }

    /**
     * The full human report: headline, the four-category tally, diff counts, per-file table, then the
     * detail sections.
     *
     * @param run the observed run
     * @param diff the classified diff
     * @param corpusSize the total the run is judged against, normally {@link #TOTAL_ASSERTIONS}
     * @param excludedOutOfBlock assertions upstream never evaluates, normally
     *     {@link #EXCLUDED_OUT_OF_BLOCK}
     * @return the report text
     */
    public static String render(
            ObservedRun run, DiffResult diff, int corpusSize, int excludedOutOfBlock) {
        StringBuilder out = new StringBuilder(1024);
        out.append(headline(run, corpusSize, excludedOutOfBlock)).append(NEWLINE);
        out.append(NEWLINE);
        out.append(categories(run, corpusSize, excludedOutOfBlock));
        out.append(NEWLINE);
        out.append("unchanged ")
                .append(diff.count(DiffClassification.UNCHANGED))
                .append(", still failing ")
                .append(diff.count(DiffClassification.STILL_FAILING))
                .append(", regressed ")
                .append(diff.count(DiffClassification.REGRESSED))
                .append(", unexpected passes ")
                .append(diff.count(DiffClassification.UNEXPECTED_PASS))
                .append(", new ")
                .append(diff.count(DiffClassification.NEW))
                .append(", disappeared ")
                .append(diff.count(DiffClassification.DISAPPEARED))
                .append(NEWLINE);
        out.append(NEWLINE);
        out.append(perFileTable(run));
        out.append(NEWLINE);
        out.append(differences(diff));
        if (diff.shouldFailBuild()) {
            out.append(NEWLINE).append(diff.failureSummary()).append(NEWLINE);
        }
        return out.toString();
    }

    /**
     * @param run the observed run
     * @param diff the classified diff
     * @param corpusSize the total the run is judged against
     * @return {@link #render(ObservedRun, DiffResult, int, int)} with no out-of-block exclusions
     */
    public static String render(ObservedRun run, DiffResult diff, int corpusSize) {
        return render(run, diff, corpusSize, 0);
    }

    /**
     * The four categories, one per line, with the arithmetic of the headline spelled out underneath so
     * that nobody has to trust the ratio in the first line.
     *
     * <p>Written as an aligned block rather than a single line because the vacuous row needs a sentence
     * next to it. A number whose meaning has to be explained and is not explained where it is printed
     * will be misquoted; this one already has been.
     *
     * @param run the observed run
     * @param corpusSize the total the run is judged against
     * @param excludedOutOfBlock assertions upstream never evaluates
     * @return the block, ending in a newline
     */
    public static String categories(ObservedRun run, int corpusSize, int excludedOutOfBlock) {
        int pass = run.count(AssertionOutcome.PASS);
        int fail = run.count(AssertionOutcome.FAIL);
        int skip = run.count(AssertionOutcome.SKIP);
        int vacuous = run.count(AssertionOutcome.VACUOUS_EXPECTED_FAILURE);
        StringBuilder out = new StringBuilder(512);
        appendCategory(out, "PASS", pass, "genuine agreement with PROJ - the numerator");
        appendCategory(out, "FAIL", fail, "measured disagreement - the remaining work");
        appendCategory(out, "SKIP", skip, "declined to run (require_grid / ignore) - NOT a pass");
        appendCategory(
                out,
                "VACUOUS",
                vacuous,
                "UNMEASURED: `expect failure` rows that gie would score as passes but which"
                        + " demonstrate nothing, because the operation could not be created for an"
                        + " unrelated reason. Counted as neither a pass nor a failure.");
        appendCategory(out, "EXCLUDED", excludedOutOfBlock, "out of block: never evaluated, upstream included");
        out.append("  denominator = ")
                .append(corpusSize)
                .append(" corpus - ")
                .append(vacuous)
                .append(" vacuous = ")
                .append(measuredDenominator(run, corpusSize))
                .append(NEWLINE);
        return out.toString();
    }

    private static void appendCategory(StringBuilder out, String name, int count, String note) {
        out.append("  ");
        padRight(out, name, 10);
        padLeft(out, Integer.toString(count), 6);
        out.append("  ").append(note).append(NEWLINE);
    }

    private static boolean appendSection(
            StringBuilder out, DiffResult diff, DiffClassification classification, String title) {
        List<DiffEntry> entries = diff.entries(classification);
        if (entries.isEmpty()) {
            return false;
        }
        if (out.length() > 0) {
            out.append(NEWLINE);
        }
        out.append(title).append(" - ").append(entries.size()).append(NEWLINE);
        for (DiffEntry entry : entries) {
            out.append("  ").append(entry.key());
            out.append("  expected=").append(entry.expected());
            out.append(" observed=").append(entry.observed() == null ? "NOT-RUN" : entry.observed().name());
            if (!entry.reason().isEmpty()) {
                out.append(NEWLINE).append("      reason: ").append(entry.reason());
            }
            if (!entry.detail().isEmpty()) {
                out.append(NEWLINE).append("      detail: ").append(entry.detail());
            }
            out.append(NEWLINE);
        }
        return true;
    }

    private static void appendMetric(StringBuilder out, String name, int value) {
        out.append(name).append('\t').append(value).append(NEWLINE);
    }

    private static void appendRow(StringBuilder out, int fileWidth, int numberWidth, String[] cells) {
        padRight(out, cells[0], fileWidth);
        for (int i = 1; i < cells.length; i++) {
            padLeft(out, cells[i], numberWidth + 2);
        }
        out.append(NEWLINE);
    }

    private static void appendRule(StringBuilder out, int fileWidth, int numberWidth) {
        int width = fileWidth + (FILE_TABLE_HEADERS.length - 1) * (numberWidth + 2);
        for (int i = 0; i < width; i++) {
            out.append('-');
        }
        out.append(NEWLINE);
    }

    private static void padRight(StringBuilder out, String text, int width) {
        out.append(text);
        for (int i = text.length(); i < width; i++) {
            out.append(' ');
        }
    }

    private static void padLeft(StringBuilder out, String text, int width) {
        for (int i = text.length(); i < width; i++) {
            out.append(' ');
        }
        out.append(text);
    }
}

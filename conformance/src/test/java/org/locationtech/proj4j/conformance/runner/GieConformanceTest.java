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
package org.locationtech.proj4j.conformance.runner;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;
import org.locationtech.proj4j.conformance.bridge.GieFailure;
import org.locationtech.proj4j.conformance.bridge.GieFailureKind;
import org.locationtech.proj4j.conformance.bridge.GieOperation;
import org.locationtech.proj4j.conformance.bridge.GieOperationFactory;
import org.locationtech.proj4j.conformance.manifest.AssertionKey;
import org.locationtech.proj4j.conformance.manifest.AssertionOutcome;
import org.locationtech.proj4j.conformance.manifest.BaselineRequirement;
import org.locationtech.proj4j.conformance.manifest.ConformanceDiff;
import org.locationtech.proj4j.conformance.manifest.CorpusIndex;
import org.locationtech.proj4j.conformance.manifest.DiffClassification;
import org.locationtech.proj4j.conformance.manifest.DiffEntry;
import org.locationtech.proj4j.conformance.manifest.DiffResult;
import org.locationtech.proj4j.conformance.manifest.ExpectedOutcomeManifest;
import org.locationtech.proj4j.conformance.manifest.ManifestRegenerator;
import org.locationtech.proj4j.conformance.manifest.ObservedRun;
import org.locationtech.proj4j.conformance.parse.GieFile;
import org.locationtech.proj4j.conformance.parse.GieLexer;
import org.locationtech.proj4j.conformance.report.ConformanceReport;

/**
 * The conformance sweep: runs every active {@code .gie} file, diffs the result against the checked-in
 * expected-outcome manifest, and fails iff {@link DiffResult#shouldFailBuild()}.
 *
 * <h2>Invocation</h2>
 *
 * <pre>
 *   mvn install                                       # skipped: one aborted container, milliseconds
 *   mvn -Pconformance verify                          # the gate
 *   mvn -Pconformance verify -Dgie.regenerate=true    # accept the current state as the baseline
 * </pre>
 *
 * <p>The sweep is gated on {@code -Dgie.corpus.skip}, which {@code conformance/pom.xml} sets to
 * {@code true} for a plain build and to {@code false} in the {@code conformance} profile. When skipped
 * this factory emits a single aborted container rather than nothing at all, so "the corpus did not run"
 * is visible in the surefire report instead of being indistinguishable from "the corpus is empty".
 *
 * <h2>The baseline is a precondition, not an option</h2>
 *
 * <p>When the sweep runs and regeneration was not requested, both baseline files must exist or the run
 * is a hard failure — see {@link BaselineRequirement}. They used to be optional: an absent manifest
 * printed a line and became {@link ExpectedOutcomeManifest#empty()}, an absent index became
 * {@link CorpusIndex#empty()}, and the consequence was that every one of the corpus's assertions
 * classified as {@code NEW}, which does not fail the build. {@code REGRESSED},
 * {@code UNEXPECTED_PASS} and {@code DISAPPEARED} were then all necessarily zero and
 * {@code mvn -Pconformance verify} passed <em>regardless of how many assertions failed</em>. A gate
 * that reports success because it cannot see is worse than no gate, because it is quoted.
 *
 * <h2>Why one dynamic test per assertion</h2>
 *
 * <p>7,923 assertions cannot be modelled as static {@code @Test} methods, and a single aggregate
 * assertion would report "3,412 failures" with no way to see which. Each dynamic test is named
 * {@code file:block:index} — the exact coordinates of a manifest key — plus the source line, the
 * assertion as written, and the operation it is asserted against, so a CI failure is actionable
 * without a local reproduction.
 *
 * <p>Only build-failing classifications actually fail. A {@code STILL_FAILING} assertion is
 * <em>aborted</em>, i.e. reported as skipped, carrying its manifest reason: it must not count as a
 * JUnit pass, because the manifest's whole purpose is to say it is not passing yet. Likewise an
 * observed {@link AssertionOutcome#SKIP} or {@link AssertionOutcome#VACUOUS_EXPECTED_FAILURE} is
 * aborted, never passed — see {@link AssertionOutcome}. A vacuous assertion is the one JUnit would
 * most readily have shown green: upstream {@code gie} scores it a success, and there is nothing in the
 * assertion itself to say otherwise.
 *
 * <h2>Wiring the operation factory</h2>
 *
 * <p>The runner needs a {@link GieOperationFactory}. It is resolved, in order, from
 * {@code -Dgie.operation.factory=<fqcn>}, then from a short list of conventional bridge class names,
 * and finally from {@link UnavailableGieOperationFactory} — which is announced loudly, because a sweep
 * against it produces no number worth quoting.
 */
class GieConformanceTest {

    /** Set to {@code true} by {@code conformance/pom.xml} for a plain build. */
    static final String SKIP_PROPERTY = "gie.corpus.skip";

    /** Names a {@link GieOperationFactory} implementation with a public no-argument constructor. */
    static final String FACTORY_PROPERTY = "gie.operation.factory";

    /** Where the human report, the machine summary and the diff are written. */
    static final Path OUTPUT_DIRECTORY = Paths.get("target", "conformance");

    /** The checked-in baseline, relative to {@code src/test/resources}. */
    static final String MANIFEST_FILE = BaselineRequirement.MANIFEST_FILE;

    /** The checked-in corpus index, which must be regenerated with the manifest. */
    static final String CORPUS_INDEX_FILE = BaselineRequirement.CORPUS_INDEX_FILE;

    /**
     * Conventional bridge factory names, tried in order. The bridge package is written independently
     * of this class; naming it here rather than importing it keeps the two changes decoupled.
     *
     * <p>A name that does not resolve, or resolves to something that is not a
     * {@link GieOperationFactory}, is skipped rather than reported: the list is a convention, not a
     * contract, and the fallback announces itself loudly enough that a silently-unwired run cannot be
     * mistaken for a real one.
     */
    private static final String[] CANDIDATE_FACTORIES = {
        "org.locationtech.proj4j.conformance.bridge.Proj4jGieOperationFactory",
        "org.locationtech.proj4j.conformance.bridge.GieOperationFactories",
        "org.locationtech.proj4j.conformance.bridge.DefaultGieOperationFactory",
    };

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    @TestFactory
    List<DynamicNode> gieAndGigsCorpus() throws IOException {
        if (isSkipRequested()) {
            return Collections.singletonList((DynamicNode) DynamicContainer.dynamicContainer(
                    "gie/GIGS corpus sweep (not run)",
                    Collections.singletonList(DynamicTest.dynamicTest(
                            "disabled by -D" + SKIP_PROPERTY + "=true; use `mvn -Pconformance verify`",
                            new Executable() {
                                @Override
                                public void execute() {
                                    Assumptions.abort("corpus sweep disabled by -D" + SKIP_PROPERTY + "=true");
                                }
                            }))));
        }

        // Checked before the sweep rather than after it: a run with no baseline cannot be gated, the
        // remedy is a different command, and there is no reason to spend the sweep to say so. The
        // regeneration path is exempt -- it is what creates the files.
        if (!ManifestRegenerator.isRegenerationRequested()) {
            final String diagnostic = BaselineRequirement.diagnosePresence(
                    resource(MANIFEST_FILE) != null, resource(CORPUS_INDEX_FILE) != null, searchedIn());
            if (!diagnostic.isEmpty()) {
                System.out.println();
                System.out.print(diagnostic);
                System.out.println();
                return Collections.singletonList((DynamicNode) DynamicTest.dynamicTest(
                        "conformance baseline is absent: nothing was gated", new Executable() {
                            @Override
                            public void execute() {
                                throw new AssertionError(diagnostic);
                            }
                        }));
            }
        }

        GieOperationFactory factory = resolveFactory();
        GieRunner runner = GieRunner.using(factory, GieGridAvailability.OnClasspath.INSTANCE);

        List<GieCorpus.Entry> files = GieCorpus.activeFiles();
        if (files.isEmpty()) {
            return Collections.singletonList((DynamicNode) DynamicTest.dynamicTest(
                    "corpus not vendored under src/test/resources/{gie,gigs}", new Executable() {
                        @Override
                        public void execute() {
                            Assumptions.abort("no .gie files found; run conformance/sync-upstream.sh");
                        }
                    }));
        }

        List<GieFileResult> fileResults = new ArrayList<GieFileResult>(files.size());
        ObservedRun.Builder builder = ObservedRun.builder();
        int lexFailures = 0;
        StringBuilder lexErrors = new StringBuilder();
        for (int i = 0; i < files.size(); i++) {
            GieCorpus.Entry entry = files.get(i);
            GieFileResult result;
            try {
                GieFile lexed = GieLexer.lex(entry.file());
                result = runner.run(entry.corpusPath(), lexed);
            } catch (RuntimeException e) {
                lexFailures++;
                lexErrors.append(entry.corpusPath()).append(": ").append(e).append('\n');
                continue;
            }
            fileResults.add(result);
            result.recordInto(builder);
        }
        ObservedRun run = builder.build();

        if (ManifestRegenerator.isRegenerationRequested()) {
            return regenerate(run, fileResults, factory);
        }

        ExpectedOutcomeManifest manifest = loadManifest();
        CorpusIndex index = loadCorpusIndex();
        // An index that was found but covers nothing gates nothing, for exactly the reason an absent
        // one does: every observed key would be NEW.
        BaselineRequirement.requireCoverage(index.size(), run.total(), searchedIn());
        DiffResult diff = ConformanceDiff.compare(manifest, run, index);

        String report = ConformanceReport.render(
                run, diff, ConformanceReport.TOTAL_ASSERTIONS, ConformanceReport.EXCLUDED_OUT_OF_BLOCK);
        String expectFailures = expectFailureTable(fileResults);
        System.out.println();
        System.out.println("=== gie/GIGS conformance ===");
        System.out.println("operation factory: " + factory);
        System.out.println("files: " + fileResults.size() + " of " + files.size()
                + (lexFailures > 0 ? " (" + lexFailures + " failed to lex)" : ""));
        System.out.println();
        System.out.print(report);
        System.out.println();
        System.out.print(expectFailures);
        if (lexFailures > 0) {
            System.out.println();
            System.out.println("LEXER FAILURES");
            System.out.print(lexErrors);
        }
        System.out.println();

        write("report.txt", report);
        write("summary.tsv", ConformanceReport.machineSummary(
                run, diff, ConformanceReport.TOTAL_ASSERTIONS, ConformanceReport.EXCLUDED_OUT_OF_BLOCK));
        write("differences.txt", ConformanceReport.differences(diff));
        write("expect-failures.tsv", expectFailures);

        return nodes(fileResults, run, diff, lexFailures, lexErrors.toString());
    }

    // ------------------------------------------------------------------------------ expect failure split

    /**
     * The three-way split of every {@code expect failure} row, per file, as TSV.
     *
     * <p>This is the table that answers "is the headline honest": {@code genuine} rows are real
     * conformance, {@code vacuous} rows are the {@code adams_hemi} trap, and {@code fail} rows are
     * "failed to fail". A file whose {@code vacuous} count equals its whole {@code expect failure}
     * population has demonstrated nothing about that projection.
     *
     * <p>Rendered here rather than in {@code report/} because it is derived from
     * {@link GieAssertionResult#expectedFailureVerdict()}, which is runner state deliberately kept out
     * of {@link ObservedRun} — the diffable record carries outcomes, not the reasoning behind them.
     */
    static String expectFailureTable(List<GieFileResult> fileResults) {
        StringBuilder out = new StringBuilder(512);
        out.append("# expect failure rows, split three ways. genuine = PASS_EXPECTED_FAILURE"
                + " (operation built, coordinate or definition rejected as the row asserts);"
                + " vacuous = VACUOUS_EXPECTED_FAILURE (operation could not be built, nothing"
                + " demonstrated, excluded from the headline); fail = failed to fail.\n");
        out.append("file\texpect_failure_rows\tgenuine\tvacuous\tfail\n");
        int totalRows = 0;
        int totalGenuine = 0;
        int totalVacuous = 0;
        int totalFail = 0;
        for (int i = 0; i < fileResults.size(); i++) {
            GieFileResult file = fileResults.get(i);
            int rows = file.expectedFailureRows();
            if (rows == 0) {
                continue;
            }
            int genuine = file.count(ExpectedFailureVerdict.PASS_EXPECTED_FAILURE);
            int vacuous = file.count(ExpectedFailureVerdict.VACUOUS_EXPECTED_FAILURE);
            int failed = file.count(ExpectedFailureVerdict.FAIL);
            totalRows += rows;
            totalGenuine += genuine;
            totalVacuous += vacuous;
            totalFail += failed;
            out.append(file.filePath())
                    .append('\t')
                    .append(rows)
                    .append('\t')
                    .append(genuine)
                    .append('\t')
                    .append(vacuous)
                    .append('\t')
                    .append(failed)
                    .append('\n');
        }
        out.append("TOTAL\t")
                .append(totalRows)
                .append('\t')
                .append(totalGenuine)
                .append('\t')
                .append(totalVacuous)
                .append('\t')
                .append(totalFail)
                .append('\n');
        return out.toString();
    }

    // ------------------------------------------------------------------------------------ dynamic nodes

    private static List<DynamicNode> nodes(
            List<GieFileResult> fileResults,
            ObservedRun run,
            DiffResult diff,
            final int lexFailures,
            final String lexErrors) {
        Map<AssertionKey, DiffEntry> byKey = new LinkedHashMap<AssertionKey, DiffEntry>();
        List<DiffEntry> entries = diff.entries();
        for (int i = 0; i < entries.size(); i++) {
            byKey.put(entries.get(i).key(), entries.get(i));
        }

        List<DynamicNode> out = new ArrayList<DynamicNode>(fileResults.size() + 1);
        for (int i = 0; i < fileResults.size(); i++) {
            GieFileResult file = fileResults.get(i);
            List<DynamicNode> tests = new ArrayList<DynamicNode>(file.total());
            List<GieAssertionResult> assertions = file.assertions();
            for (int j = 0; j < assertions.size(); j++) {
                tests.add(assertionTest(assertions.get(j), byKey.get(assertions.get(j).key())));
            }
            tests.add(leakCheck(file));
            out.add(DynamicContainer.dynamicContainer(file.summary(), tests));
        }
        out.add(gate(run, diff, lexFailures, lexErrors));
        return out;
    }

    private static DynamicTest assertionTest(final GieAssertionResult assertion, final DiffEntry entry) {
        return DynamicTest.dynamicTest(assertion.displayName(), new Executable() {
            @Override
            public void execute() {
                if (entry == null) {
                    // The diff covers the union of the manifest, the index and the run, so a key that
                    // was observed cannot be missing from it. If it ever is, the key scheme has
                    // collided and every count is suspect.
                    throw new AssertionError("assertion is absent from the diff: " + assertion.key());
                }
                if (entry.failsBuild()) {
                    throw new AssertionError(entry.classification() + ": " + assertion.detail()
                            + (entry.reason().isEmpty() ? "" : " | manifest reason: " + entry.reason()));
                }
                if (assertion.outcome() == AssertionOutcome.PASS) {
                    return;
                }
                // Not a pass and not build-failing: known work, an unmeasured assertion, or a brand-new
                // one. Aborted, so that JUnit never records it as a pass -- which for a
                // VACUOUS_EXPECTED_FAILURE is the whole point, since gie would have.
                Assumptions.abort(entry.classification() + " (" + assertion.outcome() + "): "
                        + (entry.reason().isEmpty() ? assertion.detail() : entry.reason()));
            }
        });
    }

    private static DynamicTest leakCheck(final GieFileResult file) {
        return DynamicTest.dynamicTest(
                file.filePath() + " :: crs_dst_is_lat_lon_or_y_x does not leak into a plain operation",
                new Executable() {
                    @Override
                    public void execute() {
                        if (file.leakedCrsDstFlagAssertions() != 0) {
                            throw new AssertionError(file.leakedCrsDstFlagAssertions()
                                    + " assertions in " + file.filePath() + " were measured with the"
                                    + " lat/lon swap still set from an earlier crs_src/crs_dst pair."
                                    + " gie leaks that flag on purpose; no 9.8.1 file mixes the styles,"
                                    + " so this is either a re-vendor or a lexer defect. The swap turns"
                                    + " a 55.8 km deviation into a 110.6 km one, so the resulting"
                                    + " failures will look like a projection bug and are not.");
                        }
                    }
                });
    }

    private static DynamicContainer gate(
            final ObservedRun run,
            final DiffResult diff,
            final int lexFailures,
            final String lexErrors) {
        List<DynamicNode> tests = new ArrayList<DynamicNode>(5);
        tests.add(DynamicTest.dynamicTest(
                ConformanceReport.headline(run), new Executable() {
                    @Override
                    public void execute() {
                        // The headline is the artefact; there is nothing to assert about it beyond its
                        // having been produced. Failures are asserted by the classification tests.
                    }
                }));
        tests.add(classificationTest(diff, DiffClassification.REGRESSED));
        tests.add(classificationTest(diff, DiffClassification.UNEXPECTED_PASS));
        tests.add(classificationTest(diff, DiffClassification.DISAPPEARED));
        tests.add(DynamicTest.dynamicTest("every corpus file lexes", new Executable() {
            @Override
            public void execute() {
                if (lexFailures > 0) {
                    throw new AssertionError(lexFailures + " corpus file(s) failed to lex:\n" + lexErrors);
                }
            }
        }));
        tests.add(DynamicTest.dynamicTest("conformance gate", new Executable() {
            @Override
            public void execute() {
                if (diff.shouldFailBuild()) {
                    throw new AssertionError(diff.failureSummary()
                            + "\n\n" + ConformanceReport.differences(diff)
                            + "\nFull report: " + OUTPUT_DIRECTORY.resolve("report.txt").toAbsolutePath());
                }
            }
        }));
        return DynamicContainer.dynamicContainer("conformance gate", tests);
    }

    private static DynamicTest classificationTest(
            final DiffResult diff, final DiffClassification classification) {
        return DynamicTest.dynamicTest("no " + classification + " assertions", new Executable() {
            @Override
            public void execute() {
                int n = diff.count(classification);
                if (n > 0) {
                    StringBuilder message = new StringBuilder();
                    message.append(n).append(' ').append(classification).append(":\n");
                    List<DiffEntry> entries = diff.entries(classification);
                    int shown = Math.min(entries.size(), 40);
                    for (int i = 0; i < shown; i++) {
                        message.append("  ").append(entries.get(i)).append('\n');
                    }
                    if (shown < entries.size()) {
                        message.append("  ... ").append(entries.size() - shown).append(" more\n");
                    }
                    throw new AssertionError(message.toString());
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------ regeneration

    private static List<DynamicNode> regenerate(
            final ObservedRun run, List<GieFileResult> fileResults, GieOperationFactory factory)
            throws IOException {
        Path resources = resourcesDirectory();
        if (resources == null) {
            throw new IOException("cannot locate src/test/resources to write the manifest into");
        }
        final Path manifestPath = resources.resolve(MANIFEST_FILE);
        final Path indexPath = resources.resolve(CORPUS_INDEX_FILE);

        ExpectedOutcomeManifest previous =
                Files.exists(manifestPath) ? ExpectedOutcomeManifest.load(manifestPath) : ExpectedOutcomeManifest.empty();
        // Both files are written together: the manifest holds only non-passes, so without the index a
        // passing key's absence is not evidence of anything and the next diff invents NEW/DISAPPEARED
        // entries out of nothing. Commit them together too.
        final ExpectedOutcomeManifest regenerated =
                ManifestRegenerator.regenerateInPlace(manifestPath, indexPath, run);

        String report = ConformanceReport.render(
                run,
                ConformanceDiff.compare(previous, run, CorpusIndex.ofRun(run)),
                ConformanceReport.TOTAL_ASSERTIONS,
                ConformanceReport.EXCLUDED_OUT_OF_BLOCK);
        write("report.txt", report);
        write("summary.tsv", ConformanceReport.machineSummary(
                run,
                ConformanceDiff.compare(regenerated, run, CorpusIndex.ofRun(run)),
                ConformanceReport.TOTAL_ASSERTIONS,
                ConformanceReport.EXCLUDED_OUT_OF_BLOCK));
        write("expect-failures.tsv", expectFailureTable(fileResults));

        final String change = ManifestRegenerator.describeChange(previous, regenerated);
        System.out.println();
        System.out.println("=== gie/GIGS conformance: REGENERATED ===");
        System.out.println("operation factory: " + factory);
        System.out.println(ConformanceReport.headline(run));
        System.out.println(change);
        System.out.println("wrote " + manifestPath.toAbsolutePath());
        System.out.println("wrote " + indexPath.toAbsolutePath() + " (" + run.total() + " keys)");
        System.out.println("Commit both files together.");
        System.out.println();
        System.out.print(ConformanceReport.perFileTable(run));
        System.out.println();

        List<DynamicNode> out = new ArrayList<DynamicNode>(2);
        out.add(DynamicTest.dynamicTest(
                "regenerated: " + ConformanceReport.headline(run)
                        + "; " + change,
                new Executable() {
                    @Override
                    public void execute() {
                        // Regeneration is not a gate; it succeeds if it wrote the files.
                        if (!Files.exists(manifestPath) || !Files.exists(indexPath)) {
                            throw new AssertionError("regeneration did not write both files");
                        }
                    }
                }));
        out.add(DynamicTest.dynamicTest(
                "regeneration is not a gate: " + fileResults.size() + " files swept",
                new Executable() {
                    @Override
                    public void execute() {
                        Assumptions.abort("-Dgie.regenerate=true was passed, so nothing was gated");
                    }
                }));
        return out;
    }

    // ------------------------------------------------------------------------------------ resources

    static boolean isSkipRequested() {
        return Boolean.parseBoolean(System.getProperty(SKIP_PROPERTY, "true"));
    }

    /**
     * @return the checked-in manifest
     * @throws BaselineRequirement.MissingBaselineException if either baseline file is absent
     */
    private static ExpectedOutcomeManifest loadManifest() throws IOException {
        Path onDisk = resource(MANIFEST_FILE);
        // Re-checked here, not only at the top of the sweep, so that a future caller of this method
        // cannot reintroduce the silent-empty-baseline path by skipping the early check.
        BaselineRequirement.requirePresence(onDisk != null, resource(CORPUS_INDEX_FILE) != null, searchedIn());
        return ExpectedOutcomeManifest.load(onDisk);
    }

    /**
     * @return the checked-in corpus index
     * @throws BaselineRequirement.MissingBaselineException if either baseline file is absent
     */
    private static CorpusIndex loadCorpusIndex() throws IOException {
        Path onDisk = resource(CORPUS_INDEX_FILE);
        BaselineRequirement.requirePresence(resource(MANIFEST_FILE) != null, onDisk != null, searchedIn());
        return CorpusIndex.load(onDisk);
    }

    /** @return where {@link #resource(String)} looks, for a diagnostic message. */
    private static String searchedIn() {
        Path resources = resourcesDirectory();
        return "the test classpath, and "
                + (resources == null
                        ? "no src/test/resources directory relative to " + Paths.get("").toAbsolutePath()
                        : resources.toAbsolutePath().toString());
    }

    /** The file on the test classpath, or in the source tree, or {@code null}. */
    private static Path resource(String name) {
        try {
            java.net.URL url = GieConformanceTest.class.getResource("/" + name);
            if (url != null && "file".equals(url.getProtocol())) {
                Path p = Paths.get(url.toURI());
                if (Files.isRegularFile(p)) {
                    return p;
                }
            }
        } catch (RuntimeException ignored) {
            // fall through
        } catch (java.net.URISyntaxException ignored) {
            // fall through
        }
        Path resources = resourcesDirectory();
        if (resources != null && Files.isRegularFile(resources.resolve(name))) {
            return resources.resolve(name);
        }
        return null;
    }

    private static Path resourcesDirectory() {
        String[] candidates = {"src/test/resources", "conformance/src/test/resources"};
        for (int i = 0; i < candidates.length; i++) {
            Path p = Paths.get(candidates[i]);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return null;
    }

    private static void write(String name, String content) throws IOException {
        Files.createDirectories(OUTPUT_DIRECTORY);
        Files.write(OUTPUT_DIRECTORY.resolve(name), content.getBytes(UTF_8));
    }

    // ------------------------------------------------------------------------------------ factory

    private static GieOperationFactory resolveFactory() {
        String named = System.getProperty(FACTORY_PROPERTY);
        if (named != null && !named.trim().isEmpty()) {
            GieOperationFactory f = instantiate(named.trim());
            if (f == null) {
                throw new IllegalStateException(
                        "-D" + FACTORY_PROPERTY + "=" + named + " could not be instantiated");
            }
            return f;
        }
        for (int i = 0; i < CANDIDATE_FACTORIES.length; i++) {
            GieOperationFactory f = instantiate(CANDIDATE_FACTORIES[i]);
            if (f != null) {
                return f;
            }
        }
        System.out.println();
        System.out.println("############################################################");
        System.out.println("# No GieOperationFactory is wired.                         #");
        System.out.println("# The sweep will run the state machine but create nothing,  #");
        System.out.println("# so the resulting number is NOT a conformance number.      #");
        System.out.println("# Wire one with -D" + FACTORY_PROPERTY + "=<fqcn>.       #");
        System.out.println("############################################################");
        System.out.println();
        return UnavailableGieOperationFactory.INSTANCE;
    }

    private static GieOperationFactory instantiate(String className) {
        try {
            Class<?> type = Class.forName(className);
            if (!GieOperationFactory.class.isAssignableFrom(type)) {
                return null;
            }
            return (GieOperationFactory) type.getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            return null;
        } catch (ReflectiveOperationException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}

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
package org.locationtech.proj4j.conformance.manifest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The expected-outcome manifest: the set of conformance assertions that are known <em>not</em> to
 * pass yet, with a reason for each.
 *
 * <h2>Format: TSV</h2>
 *
 * <pre>
 * # proj4j gie/GIGS conformance - expected-outcome manifest
 * key	expected	reason
 * gigs/5110.gie.failing#2:0@0c1f77ad	FAIL	laea ellipsoidal inverse: roundtrip 1000 drift, 105 mm at lat 70
 * </pre>
 *
 * <p>Three columns separated by a single tab: the {@link AssertionKey}, the expected outcome
 * ({@code FAIL}, {@code SKIP} or {@code VACUOUS_EXPECTED_FAILURE}), and free text. Lines beginning with
 * {@code #} are comments; a literal {@code key<TAB>expected<TAB>reason} header line is accepted as the
 * first non-comment line.
 *
 * <p>{@code VACUOUS_EXPECTED_FAILURE} is recorded <strong>distinctly from {@code PASS}</strong>, which
 * is the whole reason it is in the file: implementing a projection flips its vacuous rows to genuine
 * passes or genuine failures, and that must read as progress being measured for the first time rather
 * than as a regression. Had they been banked as passes, the same event would have looked like several
 * hundred regressions. See {@link ConformanceDiff}.
 *
 * <p>TSV rather than YAML or JSON, deliberately:
 * <ul>
 *   <li><strong>No dependency.</strong> The conformance module gets JUnit and nothing else; a parser
 *       for a three-column line format is twenty lines and cannot drift from a spec.</li>
 *   <li><strong>One line per fact.</strong> A file that will hold thousands of entries and shrink by a
 *       few hundred per stage has to diff cleanly. Nested YAML re-indents; JSON moves commas and
 *       braces around; a line-oriented file shows exactly the entries that changed.</li>
 *   <li><strong>Stable order.</strong> Combined with {@link AssertionKey}'s total order, regeneration
 *       is idempotent: identical input produces a byte-identical file.</li>
 *   <li><strong>Grep- and sort-able.</strong> {@code grep 'gie/builtins' manifest.tsv | wc -l} is a
 *       legitimate and frequently wanted query. Tabs, not spaces, so reasons may contain spaces.</li>
 * </ul>
 *
 * <h2>Absent key means "expected to pass"</h2>
 *
 * <p>{@link #expectedOutcome(AssertionKey)} returns {@link AssertionOutcome#PASS} for any key it does
 * not hold. The manifest therefore records only the <em>non-passing</em> minority. Three consequences,
 * all wanted:
 *
 * <ol>
 *   <li><strong>The file shrinks as the project succeeds.</strong> A manifest that lists all 7,923
 *       assertions would be roughly constant in size no matter how much work landed; one that lists
 *       only the remaining failures is a burn-down chart that lives in version control. The right
 *       incentive is for this file to get smaller.</li>
 *   <li><strong>A newly-appearing assertion defaults to "must pass".</strong> When a re-vendor adds
 *       tests, or when a key changes because upstream edited an operation, the new key is not in the
 *       manifest and so is held to the strict standard. Failing loudly on new work is correct; the
 *       alternative, defaulting to "excused", would let a re-vendor quietly widen the baseline.</li>
 *   <li><strong>A {@code PASS} row is a parse error</strong> ({@link ManifestEntry#of}), because it
 *       would be a redundant statement whose only possible effect is confusion about whether absent
 *       keys are covered.</li>
 * </ol>
 *
 * <p>Immutable. Build with {@link #of(Collection)}, read with {@link #expectedOutcome(AssertionKey)},
 * write with {@link #store(Writer)}.
 */
public final class ExpectedOutcomeManifest {

    /** The optional column-header line. */
    public static final String HEADER = "key\texpected\treason";

    /** The comment preamble written by {@link #store(Writer)}. */
    private static final String[] PREAMBLE = {
        "# proj4j gie/GIGS conformance - expected-outcome manifest.",
        "#",
        "# One line per assertion that is NOT expected to pass. A key that is absent from this file",
        "# is expected to PASS; there are no PASS rows. Columns are tab-separated:",
        "#",
        "#   <file>#<operation-block>:<assertion>@<content-hash>  <FAIL|SKIP|VACUOUS_EXPECTED_FAILURE>  <reason>",
        "#",
        "# VACUOUS_EXPECTED_FAILURE is an `expect failure` row that gie would score a pass but which",
        "# measured nothing: the operation could not be created for a reason unrelated to what the row",
        "# asserts. It is neither a pass nor a failure, and it is excluded from both sides of the",
        "# headline ratio.",
        "#",
        "# Regenerate (never hand-sort; the writer emits the canonical order):",
        "#   mvn -Pconformance verify -Dgie.regenerate=true",
        "#",
        "# Hand-written reasons are preserved across regeneration for entries whose outcome is",
        "# unchanged, so it is worth explaining WHY, not just THAT, an assertion fails.",
    };

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String NEWLINE = "\n";

    private final SortedMap<AssertionKey, ManifestEntry> entries;

    private ExpectedOutcomeManifest(SortedMap<AssertionKey, ManifestEntry> entries) {
        this.entries = entries;
    }

    /** @return an empty manifest — i.e. "every assertion must pass". */
    public static ExpectedOutcomeManifest empty() {
        return new ExpectedOutcomeManifest(new TreeMap<AssertionKey, ManifestEntry>());
    }

    /**
     * @param entries the expected non-pass outcomes; order is irrelevant, duplicates by key are
     *     rejected
     * @return the manifest
     * @throws IllegalArgumentException if two entries share a key
     */
    public static ExpectedOutcomeManifest of(Collection<ManifestEntry> entries) {
        SortedMap<AssertionKey, ManifestEntry> map = new TreeMap<AssertionKey, ManifestEntry>();
        for (ManifestEntry entry : entries) {
            ManifestEntry previous = map.put(entry.key(), entry);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate manifest key: " + entry.key());
            }
        }
        return new ExpectedOutcomeManifest(map);
    }

    /**
     * Loads a manifest from a file. A missing file is <em>not</em> tolerated here; callers that want
     * "empty if absent" should check {@link Files#exists} explicitly, so that a mistyped path can
     * never be mistaken for a clean baseline.
     *
     * @param path the manifest file, UTF-8
     * @return the manifest
     * @throws IOException if the file cannot be read
     * @throws ManifestFormatException if any line is malformed
     */
    public static ExpectedOutcomeManifest load(Path path) throws IOException {
        Reader reader = new InputStreamReader(Files.newInputStream(path), UTF_8);
        try {
            return load(reader, path.toString());
        } finally {
            reader.close();
        }
    }

    /**
     * @param reader the manifest text; not closed by this method
     * @return the manifest
     * @throws IOException if reading fails
     * @throws ManifestFormatException if any line is malformed
     */
    public static ExpectedOutcomeManifest load(Reader reader) throws IOException {
        return load(reader, "<reader>");
    }

    /**
     * @param reader the manifest text; not closed by this method
     * @param source a description used in error messages, typically a path
     * @return the manifest
     * @throws IOException if reading fails
     * @throws ManifestFormatException if any line is malformed
     */
    public static ExpectedOutcomeManifest load(Reader reader, String source) throws IOException {
        BufferedReader lines = new BufferedReader(reader);
        SortedMap<AssertionKey, ManifestEntry> map = new TreeMap<AssertionKey, ManifestEntry>();
        Map<AssertionKey, Integer> firstSeenAt = new LinkedHashMap<AssertionKey, Integer>();
        int lineNumber = 0;
        String line;
        while ((line = lines.readLine()) != null) {
            lineNumber++;
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.trim().isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            if (HEADER.equals(line)) {
                if (map.isEmpty()) {
                    continue;
                }
                throw new ManifestFormatException(source, lineNumber, line, "header line appears after data rows");
            }
            String[] columns = line.split("\t", -1);
            if (columns.length != 3) {
                throw new ManifestFormatException(
                        source,
                        lineNumber,
                        line,
                        "expected exactly 3 tab-separated columns (key, expected, reason) but found " + columns.length);
            }
            AssertionKey key;
            try {
                key = AssertionKey.parse(columns[0]);
            } catch (IllegalArgumentException e) {
                throw new ManifestFormatException(source, lineNumber, line, "column 1: " + e.getMessage(), e);
            }
            AssertionOutcome outcome;
            try {
                outcome = AssertionOutcome.parse(columns[1]);
            } catch (IllegalArgumentException e) {
                throw new ManifestFormatException(source, lineNumber, line, "column 2: " + e.getMessage(), e);
            }
            if (outcome == AssertionOutcome.PASS) {
                throw new ManifestFormatException(
                        source,
                        lineNumber,
                        line,
                        "column 2: PASS must not be recorded - the manifest holds only non-PASS "
                                + "expectations, and an absent key already means \"expected to pass\"");
            }
            ManifestEntry entry;
            try {
                entry = ManifestEntry.of(key, outcome, columns[2]);
            } catch (IllegalArgumentException e) {
                throw new ManifestFormatException(source, lineNumber, line, e.getMessage(), e);
            }
            Integer previousLine = firstSeenAt.put(key, Integer.valueOf(lineNumber));
            if (previousLine != null) {
                throw new ManifestFormatException(
                        source, lineNumber, line, "duplicate key, first seen on line " + previousLine);
            }
            map.put(key, entry);
        }
        return new ExpectedOutcomeManifest(map);
    }

    /**
     * The expectation for an assertion.
     *
     * @param key the assertion
     * @return the recorded non-pass outcome, or {@link AssertionOutcome#PASS} if the key is absent
     */
    public AssertionOutcome expectedOutcome(AssertionKey key) {
        ManifestEntry entry = entries.get(key);
        return entry == null ? AssertionOutcome.PASS : entry.expectedOutcome();
    }

    /**
     * @param key the assertion
     * @return the recorded reason, or {@code ""} if the key is absent
     */
    public String reason(AssertionKey key) {
        ManifestEntry entry = entries.get(key);
        return entry == null ? "" : entry.reason();
    }

    /**
     * @param key the assertion
     * @return the entry, or {@code null} if the key is absent (i.e. expected to pass)
     */
    public ManifestEntry entry(AssertionKey key) {
        return entries.get(key);
    }

    /** @return {@code true} if a non-pass expectation is recorded for {@code key}. */
    public boolean contains(AssertionKey key) {
        return entries.containsKey(key);
    }

    /** @return the recorded keys, in canonical order. */
    public SortedSet<AssertionKey> keys() {
        return Collections.unmodifiableSortedSet(new TreeSet<AssertionKey>(entries.keySet()));
    }

    /** @return the entries, in canonical order. */
    public List<ManifestEntry> entries() {
        return Collections.unmodifiableList(new ArrayList<ManifestEntry>(entries.values()));
    }

    /** @return the number of recorded non-pass expectations. */
    public int size() {
        return entries.size();
    }

    /** @return {@code true} when nothing is excused — the end state. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Renders the manifest exactly as {@link #store(Writer)} would write it. A pure function, so the
     * format is unit-testable without a filesystem.
     *
     * @return the full file content, {@code \n}-terminated lines
     */
    public String render() {
        StringBuilder out = new StringBuilder(256 + 96 * entries.size());
        for (int i = 0; i < PREAMBLE.length; i++) {
            out.append(PREAMBLE[i]).append(NEWLINE);
        }
        out.append(HEADER).append(NEWLINE);
        for (ManifestEntry entry : entries.values()) {
            out.append(entry.toTsvLine()).append(NEWLINE);
        }
        return out.toString();
    }

    /**
     * Writes the manifest in canonical order, so that regenerating an unchanged run rewrites the file
     * byte for byte.
     *
     * @param writer destination; flushed but not closed
     * @throws IOException if writing fails
     */
    public void store(Writer writer) throws IOException {
        writer.write(render());
        writer.flush();
    }

    /**
     * @param path destination file, UTF-8; parent directories are created if needed
     * @throws IOException if writing fails
     */
    public void store(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Writer writer = new java.io.OutputStreamWriter(Files.newOutputStream(path), UTF_8);
        try {
            store(writer);
        } finally {
            writer.close();
        }
    }

    @Override
    public String toString() {
        StringWriter out = new StringWriter();
        out.write("ExpectedOutcomeManifest[" + entries.size() + " non-pass expectations]");
        return out.toString();
    }
}

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExpectedOutcomeManifestTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static AssertionKey key(String path, int block, int index, String hash) {
        return AssertionKey.of(path, block, index, hash);
    }

    private static ExpectedOutcomeManifest load(String text) throws IOException {
        return ExpectedOutcomeManifest.load(new StringReader(text));
    }

    // ---------------------------------------------------------------- absent key defaults to PASS

    @Test
    void anAbsentKeyIsExpectedToPass() {
        ExpectedOutcomeManifest manifest = ExpectedOutcomeManifest.empty();
        assertEquals(AssertionOutcome.PASS, manifest.expectedOutcome(key("gie/builtins.gie", 0, 0, "abcdef01")));
        assertEquals("", manifest.reason(key("gie/builtins.gie", 0, 0, "abcdef01")));
        assertNull(manifest.entry(key("gie/builtins.gie", 0, 0, "abcdef01")));
        assertFalse(manifest.contains(key("gie/builtins.gie", 0, 0, "abcdef01")));
        assertTrue(manifest.isEmpty());
        assertEquals(0, manifest.size());
    }

    @Test
    void anAbsentKeyIsExpectedToPassEvenWhenOtherKeysInTheSameFileAreListed() throws IOException {
        ExpectedOutcomeManifest manifest =
                load("gie/builtins.gie#0:0@abcdef01\tFAIL\tsomething\n");
        assertEquals(AssertionOutcome.FAIL, manifest.expectedOutcome(key("gie/builtins.gie", 0, 0, "abcdef01")));
        assertEquals(AssertionOutcome.PASS, manifest.expectedOutcome(key("gie/builtins.gie", 0, 1, "abcdef02")));
    }

    @Test
    void aPassEntryCannotEvenBeConstructed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ManifestEntry.of(key("gie/builtins.gie", 0, 0, "abcdef01"), AssertionOutcome.PASS, "no"));
    }

    // ------------------------------------------------------------------------------ strict parsing

    @Test
    void parsesAWellFormedManifest() throws IOException {
        String text = "# a comment\n"
                + "\n"
                + ExpectedOutcomeManifest.HEADER + "\n"
                + "gie/builtins.gie#0:0@abcdef01\tFAIL\tno inverse yet\n"
                + "gie/epsg_grid.gie#0:0@abcdef02\tSKIP\trequire_grid us_nga_egm08_25.tif\n";
        ExpectedOutcomeManifest manifest = load(text);
        assertEquals(2, manifest.size());
        assertEquals(AssertionOutcome.FAIL, manifest.expectedOutcome(key("gie/builtins.gie", 0, 0, "abcdef01")));
        assertEquals("no inverse yet", manifest.reason(key("gie/builtins.gie", 0, 0, "abcdef01")));
        assertEquals(AssertionOutcome.SKIP, manifest.expectedOutcome(key("gie/epsg_grid.gie", 0, 0, "abcdef02")));
    }

    @Test
    void toleratesAnEmptyReasonColumn() throws IOException {
        ExpectedOutcomeManifest manifest = load("gie/builtins.gie#0:0@abcdef01\tFAIL\t\n");
        assertEquals("", manifest.reason(key("gie/builtins.gie", 0, 0, "abcdef01")));
        assertEquals(1, manifest.size());
    }

    @Test
    void toleratesCarriageReturnsAndBlankLines() throws IOException {
        ExpectedOutcomeManifest manifest =
                load("\r\n# c\r\ngie/builtins.gie#0:0@abcdef01\tFAIL\twhy\r\n   \n");
        assertEquals(1, manifest.size());
        assertEquals("why", manifest.reason(key("gie/builtins.gie", 0, 0, "abcdef01")));
    }

    @Test
    void failsOnTooFewColumnsAndNamesTheLine() {
        ManifestFormatException e = assertThrows(
                ManifestFormatException.class,
                () -> load("# c\ngie/builtins.gie#0:0@abcdef01\tFAIL\tok\ngie/builtins.gie#0:1@abcdef02\tFAIL\n"));
        assertEquals(3, e.lineNumber());
        assertTrue(e.getMessage().contains("3 tab-separated columns"), e.getMessage());
        assertTrue(e.getMessage().contains("found 2"), e.getMessage());
    }

    @Test
    void failsOnTooManyColumns() {
        ManifestFormatException e = assertThrows(
                ManifestFormatException.class,
                () -> load("gie/builtins.gie#0:0@abcdef01\tFAIL\treason\textra\n"));
        assertEquals(1, e.lineNumber());
        assertTrue(e.getMessage().contains("found 4"), e.getMessage());
    }

    @Test
    void failsOnAMalformedKey() {
        ManifestFormatException e =
                assertThrows(ManifestFormatException.class, () -> load("gie/builtins.gie#0:0@ab\tFAIL\treason\n"));
        assertEquals(1, e.lineNumber());
        assertTrue(e.getMessage().contains("column 1"), e.getMessage());
    }

    @Test
    void failsOnAnUnknownOutcome() {
        ManifestFormatException e = assertThrows(
                ManifestFormatException.class, () -> load("gie/builtins.gie#0:0@abcdef01\tXFAIL\treason\n"));
        assertEquals(1, e.lineNumber());
        assertTrue(e.getMessage().contains("column 2"), e.getMessage());
    }

    @Test
    void failsOnALowerCaseOutcome() {
        assertThrows(
                ManifestFormatException.class, () -> load("gie/builtins.gie#0:0@abcdef01\tfail\treason\n"));
    }

    @Test
    void failsOnAPassRowAndSaysWhy() {
        ManifestFormatException e = assertThrows(
                ManifestFormatException.class, () -> load("gie/builtins.gie#0:0@abcdef01\tPASS\tredundant\n"));
        assertEquals(1, e.lineNumber());
        assertTrue(e.getMessage().contains("PASS must not be recorded"), e.getMessage());
    }

    @Test
    void failsOnADuplicateKeyAndPointsAtTheFirstOccurrence() {
        ManifestFormatException e = assertThrows(
                ManifestFormatException.class,
                () -> load("gie/builtins.gie#0:0@abcdef01\tFAIL\tone\n"
                        + "# noise\n"
                        + "gie/builtins.gie#0:0@abcdef01\tFAIL\ttwo\n"));
        assertEquals(3, e.lineNumber());
        assertTrue(e.getMessage().contains("first seen on line 1"), e.getMessage());
    }

    @Test
    void failsOnAHeaderAfterData() {
        ManifestFormatException e = assertThrows(
                ManifestFormatException.class,
                () -> load("gie/builtins.gie#0:0@abcdef01\tFAIL\tone\n" + ExpectedOutcomeManifest.HEADER + "\n"));
        assertEquals(2, e.lineNumber());
        assertTrue(e.getMessage().contains("header line appears after data"), e.getMessage());
    }

    @Test
    void reportsTheSourceNameInTheMessage() {
        ManifestFormatException e = assertThrows(
                ManifestFormatException.class,
                () -> ExpectedOutcomeManifest.load(new StringReader("garbage\n"), "the-manifest.tsv"));
        assertEquals("the-manifest.tsv", e.source());
        assertTrue(e.getMessage().startsWith("the-manifest.tsv:1:"), e.getMessage());
        assertEquals("garbage", e.line());
    }

    @Test
    void rejectsATabInAReasonAtConstructionTime() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ManifestEntry.of(key("gie/x.gie", 0, 0, "abcdef01"), AssertionOutcome.FAIL, "a\tb"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ManifestEntry.of(key("gie/x.gie", 0, 0, "abcdef01"), AssertionOutcome.FAIL, "a\nb"));
    }

    @Test
    void rejectsDuplicateKeysWhenBuiltInMemory() {
        ManifestEntry one = ManifestEntry.of(key("gie/x.gie", 0, 0, "abcdef01"), AssertionOutcome.FAIL, "one");
        ManifestEntry two = ManifestEntry.of(key("gie/x.gie", 0, 0, "abcdef01"), AssertionOutcome.SKIP, "two");
        assertThrows(IllegalArgumentException.class, () -> ExpectedOutcomeManifest.of(Arrays.asList(one, two)));
    }

    // ---------------------------------------------------------------------------- stable rendering

    @Test
    void storesInCanonicalOrderRegardlessOfInsertionOrder() throws IOException {
        List<ManifestEntry> entries = new ArrayList<ManifestEntry>();
        entries.add(ManifestEntry.of(key("gigs/5110.gie.failing", 2, 0, "0000000c"), AssertionOutcome.FAIL, "laea"));
        entries.add(ManifestEntry.of(key("gie/builtins.gie", 10, 0, "0000000b"), AssertionOutcome.FAIL, "b"));
        entries.add(ManifestEntry.of(key("gie/builtins.gie", 2, 0, "0000000a"), AssertionOutcome.SKIP, "a"));

        StringWriter out = new StringWriter();
        ExpectedOutcomeManifest.of(entries).store(out);
        List<String> dataLines = dataLines(out.toString());
        assertEquals(
                Arrays.asList(
                        "gie/builtins.gie#2:0@0000000a\tSKIP\ta",
                        "gie/builtins.gie#10:0@0000000b\tFAIL\tb",
                        "gigs/5110.gie.failing#2:0@0000000c\tFAIL\tlaea"),
                dataLines);
    }

    @Test
    void renderCarriesTheHeaderAndTheRegenerationCommand() {
        String rendered = ExpectedOutcomeManifest.empty().render();
        assertTrue(rendered.contains(ExpectedOutcomeManifest.HEADER), rendered);
        assertTrue(rendered.contains("-Dgie.regenerate=true"), rendered);
        assertTrue(rendered.contains("absent"), "the preamble must state the absent-key default");
    }

    @Test
    void renderingIsIdempotentSoRegenerationDiffsMinimally() throws IOException {
        ExpectedOutcomeManifest manifest = ExpectedOutcomeManifest.of(Arrays.asList(
                ManifestEntry.of(key("gie/builtins.gie", 0, 0, "abcdef01"), AssertionOutcome.FAIL, "one"),
                ManifestEntry.of(key("gie/epsg_grid.gie", 1, 0, "abcdef02"), AssertionOutcome.SKIP, "two")));
        String once = manifest.render();
        String twice = load(once).render();
        assertEquals(once, twice);
        assertEquals(once, load(twice).render());
    }

    @Test
    void roundTripsThroughAFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("nested/expected-failures.tsv");
        ExpectedOutcomeManifest manifest = ExpectedOutcomeManifest.of(Arrays.asList(
                ManifestEntry.of(key("gie/builtins.gie", 0, 0, "abcdef01"), AssertionOutcome.FAIL, "unicode: ±0.42 m"),
                ManifestEntry.of(key("gie/epsg_grid.gie", 1, 0, "abcdef02"), AssertionOutcome.SKIP, "grid")));
        manifest.store(file);

        assertTrue(Files.exists(file));
        ExpectedOutcomeManifest reloaded = ExpectedOutcomeManifest.load(file);
        assertEquals(manifest.entries(), reloaded.entries());
        assertEquals("unicode: ±0.42 m", reloaded.reason(key("gie/builtins.gie", 0, 0, "abcdef01")));
        assertTrue(new String(Files.readAllBytes(file), UTF_8).endsWith("\n"));
    }

    @Test
    void entriesAndKeysAreExposedInCanonicalOrder() {
        ExpectedOutcomeManifest manifest = ExpectedOutcomeManifest.of(Arrays.asList(
                ManifestEntry.of(key("gie/b.gie", 0, 0, "abcdef02"), AssertionOutcome.FAIL, "b"),
                ManifestEntry.of(key("gie/a.gie", 0, 0, "abcdef01"), AssertionOutcome.FAIL, "a")));
        assertEquals(
                Arrays.asList(key("gie/a.gie", 0, 0, "abcdef01"), key("gie/b.gie", 0, 0, "abcdef02")),
                new ArrayList<AssertionKey>(manifest.keys()));
        assertEquals("a", manifest.entries().get(0).reason());
    }

    private static List<String> dataLines(String rendered) {
        List<String> lines = new ArrayList<String>();
        for (String line : rendered.split("\n", -1)) {
            if (line.isEmpty() || line.startsWith("#") || line.equals(ExpectedOutcomeManifest.HEADER)) {
                continue;
            }
            lines.add(line);
        }
        return lines;
    }
}

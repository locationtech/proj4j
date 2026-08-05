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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Exercises the checked-in fixtures under {@code manifest-fixtures/}. These are illustrative files
 * with fabricated content hashes — the real manifest is generated — but they pin the format and prove
 * the parser accepts what the README documents and rejects what it forbids.
 */
class SeedManifestFixtureTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String SEED = "/manifest-fixtures/expected-failures.seed.tsv";
    private static final String INDEX = "/manifest-fixtures/corpus-index.seed.tsv";

    private static Reader open(String resource) {
        InputStream in = SeedManifestFixtureTest.class.getResourceAsStream(resource);
        assertNotNull(in, "missing test resource " + resource);
        return new InputStreamReader(in, UTF_8);
    }

    private static ExpectedOutcomeManifest loadSeed() throws IOException {
        Reader reader = open(SEED);
        try {
            return ExpectedOutcomeManifest.load(reader, SEED);
        } finally {
            reader.close();
        }
    }

    @Test
    void theSeedManifestParses() throws IOException {
        ExpectedOutcomeManifest seed = loadSeed();
        assertEquals(28, seed.size());
    }

    @Test
    void theSeedCoversAllTenQuarantinedGigsFiles() throws IOException {
        SortedSet<String> failingFiles = new TreeSet<String>();
        for (ManifestEntry entry : loadSeed().entries()) {
            if (entry.key().filePath().endsWith(".gie.failing")) {
                failingFiles.add(entry.key().filePath());
            }
        }
        assertEquals(
                new TreeSet<String>(java.util.Arrays.asList(
                        "gigs/5101.4-jhs.gie.failing",
                        "gigs/5105.1.gie.failing",
                        "gigs/5110.gie.failing",
                        "gigs/5111.2.gie.failing",
                        "gigs/5203.1.gie.failing",
                        "gigs/5204.1.gie.failing",
                        "gigs/5205.1.gie.failing",
                        "gigs/5206.gie.failing",
                        "gigs/5207.1.gie.failing",
                        "gigs/5207.2.gie.failing")),
                failingFiles);
    }

    @Test
    void everySeedEntryCarriesAReason() throws IOException {
        for (ManifestEntry entry : loadSeed().entries()) {
            assertFalse(entry.reason().isEmpty(), "no reason recorded for " + entry.key());
            assertTrue(entry.reason().length() > 20, "reason too thin for " + entry.key() + ": " + entry.reason());
        }
    }

    @Test
    void theNineSyntaxArtefactFilesSayTheyAreNotMathsFailures() throws IOException {
        SortedSet<String> syntaxFiles = new TreeSet<String>();
        for (ManifestEntry entry : loadSeed().entries()) {
            if (entry.reason().contains("syntax artefact")) {
                syntaxFiles.add(entry.key().filePath());
            }
        }
        assertEquals(9, syntaxFiles.size(), "nine of the ten fail for the missing-continuation reason: " + syntaxFiles);
        assertFalse(syntaxFiles.contains("gigs/5110.gie.failing"), "5110 is the genuine one, not a syntax artefact");
    }

    @Test
    void the5110EntriesNameTheRealRootCause() throws IOException {
        List<ManifestEntry> entries = new ArrayList<ManifestEntry>();
        for (ManifestEntry entry : loadSeed().entries()) {
            if (entry.key().filePath().equals("gigs/5110.gie.failing")) {
                entries.add(entry);
            }
        }
        assertEquals(3, entries.size());
        StringBuilder reasons = new StringBuilder();
        for (ManifestEntry entry : entries) {
            reasons.append(entry.reason()).append('\n');
        }
        String text = reasons.toString();
        assertTrue(text.contains("laea"), text);
        assertTrue(text.contains("roundtrip 1000"), text);
        assertTrue(text.contains("0.006"), text);
        assertTrue(text.contains("83c91dd2"), "cite the upstream commit that records the numbers");
    }

    @Test
    void theSeedUsesSkipOnlyForRequireGrid() throws IOException {
        for (ManifestEntry entry : loadSeed().entries()) {
            if (entry.expectedOutcome() == AssertionOutcome.SKIP) {
                assertTrue(entry.reason().contains("require_grid"), entry.toTsvLine());
            }
        }
    }

    @Test
    void theSeedIsAlreadyInCanonicalOrderSoRegenerationWouldNotReshuffleIt() throws IOException {
        List<AssertionKey> asWritten = new ArrayList<AssertionKey>();
        BufferedReader reader = new BufferedReader(open(SEED));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#") || line.equals(ExpectedOutcomeManifest.HEADER)) {
                    continue;
                }
                asWritten.add(AssertionKey.parse(line.split("\t", -1)[0]));
            }
        } finally {
            reader.close();
        }
        List<AssertionKey> sorted = new ArrayList<AssertionKey>(asWritten);
        Collections.sort(sorted);
        assertEquals(sorted, asWritten);
    }

    @Test
    void theSeedIndexIsASupersetOfTheSeedManifest() throws IOException {
        ExpectedOutcomeManifest seed = loadSeed();
        Reader reader = open(INDEX);
        CorpusIndex index;
        try {
            index = CorpusIndex.load(reader, INDEX);
        } finally {
            reader.close();
        }
        for (AssertionKey key : seed.keys()) {
            assertTrue(index.contains(key), "index is missing " + key);
        }
        assertEquals(seed.size() + 2, index.size(), "the index also holds the two passing example keys");
    }

    @Test
    void theSeedManifestAndIndexAgreeSoTheGateIsQuietWhenNothingChanges() throws IOException {
        ExpectedOutcomeManifest seed = loadSeed();
        Reader reader = open(INDEX);
        CorpusIndex index;
        try {
            index = CorpusIndex.load(reader, INDEX);
        } finally {
            reader.close();
        }
        ObservedRun.Builder run = ObservedRun.builder();
        for (AssertionKey key : index.keys()) {
            run.record(key, seed.expectedOutcome(key));
        }
        DiffResult diff = ConformanceDiff.compare(seed, run.build(), index);
        assertFalse(diff.shouldFailBuild(), diff.failureSummary());
        assertEquals(2, diff.count(DiffClassification.UNCHANGED));
        assertEquals(28, diff.count(DiffClassification.STILL_FAILING));
    }

    @Test
    void theMalformedFixturesAreAllRejectedWithTheRightLineNumber() {
        assertEquals(4, expectRejection("/manifest-fixtures/malformed-missing-column.tsv"));
        assertEquals(4, expectRejection("/manifest-fixtures/malformed-pass-row.tsv"));
        assertEquals(4, expectRejection("/manifest-fixtures/malformed-bad-key.tsv"));
    }

    private static int expectRejection(final String resource) {
        ManifestFormatException e = assertThrows(ManifestFormatException.class, () -> {
            Reader reader = open(resource);
            try {
                ExpectedOutcomeManifest.load(reader, resource);
            } finally {
                reader.close();
            }
        });
        return e.lineNumber();
    }
}

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
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The set of assertion keys the manifest was generated against: one key per line, canonical order.
 *
 * <p>The manifest itself records only non-passes, so it cannot answer "did this assertion exist last
 * time?". That question is what separates {@code NEW} from {@code UNCHANGED} and, more importantly,
 * {@code DISAPPEARED} from "nothing to see here". The index is therefore written alongside the
 * manifest by {@link ManifestRegenerator} and read back by {@link ConformanceDiff}.
 *
 * <p>It is a large file (~8,000 lines) and it is <em>meant</em> to be: it is a re-vendor tripwire. If
 * upstream changes a single {@code operation} definition, the affected keys change hash and the index
 * diff shows exactly which assertions moved. Nothing else in the system can detect that.
 *
 * <p>Format: {@code #} comments and blank lines are ignored; every other line is one rendered
 * {@link AssertionKey}. Parsing is strict, for the same reason as the manifest.
 *
 * <p>Immutable.
 */
public final class CorpusIndex {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String NEWLINE = "\n";
    private static final String[] PREAMBLE = {
        "# proj4j gie/GIGS conformance - corpus index.",
        "#",
        "# Every assertion key present when the sibling manifest was generated, one per line, in",
        "# canonical order. Used to tell NEW assertions from UNCHANGED ones and to detect keys that",
        "# have DISAPPEARED (an upstream edit, or a lexer change that alters block/assertion indices).",
        "#",
        "# Regenerate together with the manifest:",
        "#   mvn -Pconformance verify -Dgie.regenerate=true",
    };

    private final SortedSet<AssertionKey> keys;

    private CorpusIndex(SortedSet<AssertionKey> keys) {
        this.keys = keys;
    }

    /** @return an index containing no keys — every observed assertion will be {@code NEW}. */
    public static CorpusIndex empty() {
        return new CorpusIndex(new TreeSet<AssertionKey>());
    }

    /**
     * @param keys the keys, in any order
     * @return the index
     */
    public static CorpusIndex of(Collection<AssertionKey> keys) {
        return new CorpusIndex(new TreeSet<AssertionKey>(keys));
    }

    /**
     * @param run a run whose key set defines the corpus
     * @return the index
     */
    public static CorpusIndex ofRun(ObservedRun run) {
        return of(run.keys());
    }

    /**
     * @param path the index file, UTF-8
     * @return the index
     * @throws IOException if the file cannot be read
     * @throws ManifestFormatException if a line is not a well-formed key
     */
    public static CorpusIndex load(Path path) throws IOException {
        Reader reader = new InputStreamReader(Files.newInputStream(path), UTF_8);
        try {
            return load(reader, path.toString());
        } finally {
            reader.close();
        }
    }

    /**
     * @param reader the index text; not closed by this method
     * @return the index
     * @throws IOException if reading fails
     * @throws ManifestFormatException if a line is not a well-formed key
     */
    public static CorpusIndex load(Reader reader) throws IOException {
        return load(reader, "<reader>");
    }

    /**
     * @param reader the index text; not closed by this method
     * @param source description used in error messages
     * @return the index
     * @throws IOException if reading fails
     * @throws ManifestFormatException if a line is not a well-formed key
     */
    public static CorpusIndex load(Reader reader, String source) throws IOException {
        BufferedReader lines = new BufferedReader(reader);
        SortedSet<AssertionKey> parsed = new TreeSet<AssertionKey>();
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
            AssertionKey key;
            try {
                key = AssertionKey.parse(line);
            } catch (IllegalArgumentException e) {
                throw new ManifestFormatException(source, lineNumber, line, e.getMessage(), e);
            }
            if (!parsed.add(key)) {
                throw new ManifestFormatException(source, lineNumber, line, "duplicate key");
            }
        }
        return new CorpusIndex(parsed);
    }

    /** @return the keys, in canonical order. */
    public SortedSet<AssertionKey> keys() {
        return Collections.unmodifiableSortedSet(keys);
    }

    /** @return {@code true} if the key was present when the manifest was generated. */
    public boolean contains(AssertionKey key) {
        return keys.contains(key);
    }

    /** @return the number of keys. */
    public int size() {
        return keys.size();
    }

    /** @return {@code true} when the index holds no keys. */
    public boolean isEmpty() {
        return keys.isEmpty();
    }

    /**
     * Renders the index. A pure function, so it is testable without a filesystem.
     *
     * @return the full file content
     */
    public String render() {
        StringBuilder out = new StringBuilder(256 + 48 * keys.size());
        for (int i = 0; i < PREAMBLE.length; i++) {
            out.append(PREAMBLE[i]).append(NEWLINE);
        }
        for (AssertionKey key : keys) {
            out.append(key.toString()).append(NEWLINE);
        }
        return out.toString();
    }

    /**
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
        Writer writer = new OutputStreamWriter(Files.newOutputStream(path), UTF_8);
        try {
            store(writer);
        } finally {
            writer.close();
        }
    }

    @Override
    public String toString() {
        return "CorpusIndex[" + keys.size() + " assertions]";
    }
}

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
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Locates the vendored corpus: {@code gie/*.gie} and {@code gigs/*.gie} on the test classpath.
 *
 * <h2>{@code *.gie.failing} is excluded</h2>
 *
 * <p>Ten GIGS files are quarantined upstream with a {@code .failing} suffix and are not part of the
 * 8,017-assertion metric. The glob {@code *.gie} excludes them by construction rather than by a
 * filter, so there is no predicate to get backwards. Nine of the ten fail for a lexical reason
 * (missing {@code \} continuations, so their {@code +step} lines are swallowed as comments) and one,
 * {@code 5110}, is a genuine numerical failure; none of that is this class's problem, but running them
 * would inflate the denominator with tests upstream itself does not run.
 *
 * <p>Note that {@code gigs/5101.4-jhs-etmerc.gie} is <em>not</em> quarantined and must stay in the
 * active set even though {@code gigs/5101.4-jhs.gie.failing} is.
 *
 * <p>Stateless; not instantiable.
 */
public final class GieCorpus {

    /** The two corpus directories, in the order the report lists them. */
    public static final String[] DIRECTORIES = {"gie", "gigs"};

    private GieCorpus() {
        throw new AssertionError("no instances");
    }

    /**
     * One corpus file: its {@code gie/builtins.gie}-style relative path and where it actually is.
     *
     * <p>Immutable.
     */
    public static final class Entry {

        private final String corpusPath;
        private final Path file;

        Entry(String corpusPath, Path file) {
            this.corpusPath = corpusPath;
            this.file = file;
        }

        /** @return the corpus-relative path used in every {@link org.locationtech.proj4j.conformance.manifest.AssertionKey}. */
        public String corpusPath() {
            return corpusPath;
        }

        /** @return the file on disk. */
        public Path file() {
            return file;
        }

        @Override
        public String toString() {
            return corpusPath;
        }
    }

    /**
     * Every active corpus file, ordered by corpus path.
     *
     * @return the entries; empty if the corpus has not been vendored
     * @throws IOException if a directory cannot be listed
     */
    public static List<Entry> activeFiles() throws IOException {
        List<Entry> out = new ArrayList<Entry>();
        for (int i = 0; i < DIRECTORIES.length; i++) {
            String name = DIRECTORIES[i];
            Path dir = directory(name);
            if (dir == null) {
                continue;
            }
            DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.gie");
            try {
                for (Path p : stream) {
                    // "*.gie" already excludes the quarantined "*.gie.failing".
                    out.add(new Entry(name + "/" + p.getFileName().toString(), p));
                }
            } finally {
                stream.close();
            }
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.corpusPath.compareTo(b.corpusPath);
            }
        });
        return out;
    }

    /**
     * Resolves one corpus directory, preferring the test classpath and falling back to the source
     * tree so this works from an IDE that has not copied resources and from either working directory
     * Maven might use.
     *
     * @param name {@code "gie"} or {@code "gigs"}
     * @return the directory, or {@code null} if it is nowhere to be found
     */
    public static Path directory(String name) {
        try {
            URL url = GieCorpus.class.getResource("/" + name);
            if (url != null && "file".equals(url.getProtocol())) {
                Path p = Paths.get(url.toURI());
                if (Files.isDirectory(p)) {
                    return p;
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to the source tree
        } catch (java.net.URISyntaxException ignored) {
            // fall through to the source tree
        }
        String[] prefixes = {"src/test/resources", "conformance/src/test/resources"};
        for (int i = 0; i < prefixes.length; i++) {
            Path p = Paths.get(prefixes[i]).resolve(name);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return null;
    }
}

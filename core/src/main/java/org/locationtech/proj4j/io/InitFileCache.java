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
 *******************************************************************************/
package org.locationtech.proj4j.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StreamTokenizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;

import org.locationtech.proj4j.resource.ResourceNames;
import org.locationtech.proj4j.util.Pair;

/**
 * A thread-safe, byte-bounded cache of <em>parsed</em> PROJ.4 init dictionaries.
 *
 * <p>{@link Proj4FileReader} used to re-open the classpath resource and re-tokenise the entire
 * dictionary on <em>every</em> call, allocating an {@code ArrayList} and a {@link Pair} per entry
 * scanned and discarding them until the name matched. {@code CrsParseBenchmark} measured
 * {@code createFromName} at <b>39,632 B/op</b> for the first entry in {@code proj4/nad/epsg},
 * <b>4,002,108</b> for the middle one and <b>7,919,398</b> for the last, against
 * <b>1,920&ndash;4,112</b> for {@code createFromParameters}, which is the parse-only control. The
 * whole 200&times; spread was the linear re-scan. This class removes it.
 *
 * <h2>The key, which is the part that can return a wrong CRS</h2>
 * <p>Two different comparisons are in play and they must not be conflated.
 * <ul>
 *   <li>The <b>file</b> is selected by {@code "proj4/nad/" + authority.toLowerCase(Locale.ROOT)}.
 *       That lowercasing is part of the resource name, so {@code EPSG}, {@code epsg} and
 *       {@code EpSg} are the same file and must be the same cache entry. Keying on the raw
 *       authority would merely waste memory; keying the <em>code</em> case-insensitively would
 *       return the <b>wrong CRS</b>.</li>
 *   <li>The <b>code within the file</b> was compared with {@code crsName.equals(name)} &mdash;
 *       case-sensitive, no folding, no trimming. A {@link HashMap} lookup uses exactly the same
 *       {@code String.equals}, so the two-level structure below is behaviourally identical.</li>
 * </ul>
 * Hence {@code Map<lowercased authority, Map<case-sensitive code, params>>} and nothing flatter.
 *
 * <h2>Exactly the semantics of the linear scan it replaces</h2>
 * <ul>
 *   <li><b>First match wins.</b> The old {@code readFile} returned at the first entry whose name
 *       matched, and {@code readEpsgCodeFromFile} at the first entry whose parameter array matched.
 *       Both indexes therefore keep the <em>first</em> occurrence and ignore later duplicates. The
 *       reverse index is built from the full entry stream in file order, <b>not</b> from the forward
 *       index &mdash; a code repeated with different parameters contributes both parameter sets to
 *       the reverse index, which is what the old whole-file scan did.</li>
 *   <li><b>A malformed entry is a deferred failure, not an eager one.</b> The old scan threw only if
 *       it <em>reached</em> the bad entry, so a name occurring before it resolved fine. Parsing
 *       eagerly and propagating would turn those working lookups into errors. Instead the entries
 *       before the failure are retained and the failure is replayed only on a lookup that misses
 *       &mdash; which is precisely the set of lookups that would have reached it.</li>
 *   <li><b>A trailing token that is not {@code '<'} ends the scan without an error</b>, exactly as
 *       the old {@code while (t.ttype == '<')} loop did.</li>
 *   <li>The stream is still decoded with the platform default charset, as before. Changing that
 *       could change which CRS a name resolves to and is not this class's business.</li>
 * </ul>
 *
 * <h2>Bounded, because the authority is untrusted input</h2>
 * <p>This library is called per row inside Spark executors and the CRS string comes from the user,
 * so {@code +init=<authority>:<code>} lets a caller name an unbounded number of distinct cache
 * keys. The bound follows {@link org.locationtech.proj4j.datum.GridCache}'s shape: a byte budget
 * (default 32 MiB, {@code -Dproj4j.initFiles.cacheBytes}), least-recently-used eviction, failures
 * cached so a bogus authority costs one classpath probe rather than one per row, and no lock held
 * across I/O &mdash; loading goes through a {@link FutureTask} published with {@code putIfAbsent},
 * so concurrent demand for one file produces exactly one parse.
 *
 * <p>Two deliberate deviations from a naive accounting:
 * <ul>
 *   <li>A <b>miss is charged a flat {@value #MISS_WEIGHT_BYTES} bytes</b>, far more than the key
 *       costs. Under-charging misses is how a "bounded" cache retains hundreds of thousands of
 *       them; over-charging only makes the bound conservative.</li>
 *   <li>A dictionary whose accounted size <b>exceeds the whole budget is not cached at all</b>.
 *       The reader falls back to the original streaming scan for that file, so behaviour is
 *       unchanged and memory stays bounded. Refusing such a file instead would break a legitimate,
 *       if unusual, user dictionary that works today.</li>
 * </ul>
 *
 * <h2>It cannot change an answer</h2>
 * <p>A parsed dictionary is immutable after construction and is published through
 * {@code FutureTask.get()}. The parameter arrays it holds are never handed out directly &mdash;
 * {@link Proj4FileReader} clones on the way out, because
 * {@code CoordinateReferenceSystem.getParameters()} returns its array by reference and a caller
 * mutating it would otherwise corrupt the dictionary for every later lookup, process-wide.
 */
final class InitFileCache {

    /** System property naming the byte budget. {@code 0} disables caching entirely. */
    static final String SIZE_PROPERTY = "proj4j.initFiles.cacheBytes";

    static final long DEFAULT_MAX_BYTES = 32L * 1024L * 1024L;

    /**
     * What a cached failure is charged. A miss retains a key, a {@code FutureTask} and a map node;
     * charging it like the ~80 bytes it nominally occupies would let a hostile stream of distinct
     * authorities retain hundreds of thousands of entries inside a 32 MiB "bound". At 1 KiB the
     * ceiling is ~32,768 cached failures, which is a real bound on a real number.
     */
    static final long MISS_WEIGHT_BYTES = 1024L;

    private static final InitFileCache INSTANCE = new InitFileCache(configuredMaxBytes());

    private final long maxBytes;

    /** file key -> the single in-flight or completed parse for that key. */
    private final ConcurrentMap<String, FutureTask<Dictionary>> entries =
            new ConcurrentHashMap<String, FutureTask<Dictionary>>();

    /** Access-ordered LRU bookkeeping. Guarded by {@code lru} itself; never held across I/O. */
    private final LinkedHashMap<String, Long> lru = new LinkedHashMap<String, Long>(16, 0.75f, true);

    private long currentBytes;

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();

    InitFileCache(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    static InitFileCache instance() {
        return INSTANCE;
    }

    static long configuredMaxBytes() {
        String raw = System.getProperty(SIZE_PROPERTY);
        if (raw == null) {
            return DEFAULT_MAX_BYTES;
        }
        try {
            long v = Long.parseLong(raw.trim());
            // 0 is a supported value and means "never cache": the reader streams every call, which
            // is the pre-cache behaviour and therefore the control arm for any test of this class.
            return v >= 0 ? v : DEFAULT_MAX_BYTES;
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_BYTES;
        }
    }

    /**
     * The parsed dictionary for a classpath init file.
     *
     * @param fileKey the authority already lowercased with {@code Locale.ROOT} &mdash; i.e. the
     *                last segment of the resource name, so that the cache key and the resource
     *                selected are the same string by construction
     * @return never {@code null}; {@link Dictionary#MISSING} if the resource does not exist and
     *         {@link Dictionary#OVERSIZED} if it is too large to cache
     */
    Dictionary get(final String fileKey) {
        if (maxBytes == 0L) {
            return Dictionary.OVERSIZED;
        }
        FutureTask<Dictionary> task = entries.get(fileKey);
        boolean mine = false;
        if (task == null) {
            FutureTask<Dictionary> created = new FutureTask<Dictionary>(new Callable<Dictionary>() {
                @Override
                public Dictionary call() {
                    return parse(fileKey, maxBytes);
                }
            });
            FutureTask<Dictionary> existing = entries.putIfAbsent(fileKey, created);
            if (existing == null) {
                task = created;
                mine = true;
                misses.incrementAndGet();
                task.run();
            } else {
                task = existing;
                hits.incrementAndGet();
            }
        } else {
            hits.incrementAndGet();
        }

        Dictionary dict;
        try {
            dict = task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Not cached: an interrupt is a property of the calling thread, not of the file.
            entries.remove(fileKey, task);
            return Dictionary.OVERSIZED;
        } catch (CancellationException e) {
            entries.remove(fileKey, task);
            return Dictionary.OVERSIZED;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("Failed to read CRS file: " + fileKey, cause);
        }

        if (dict == Dictionary.OVERSIZED) {
            // Remember that this file is not cacheable, but do not retain a parse of it.
            if (mine) {
                admit(fileKey, MISS_WEIGHT_BYTES);
            } else {
                touch(fileKey);
            }
            return dict;
        }
        if (mine) {
            admit(fileKey, dict == Dictionary.MISSING ? MISS_WEIGHT_BYTES : dict.bytes);
        } else {
            touch(fileKey);
        }
        return dict;
    }

    private void admit(String k, long bytes) {
        List<String> evict = null;
        synchronized (lru) {
            Long prior = lru.put(k, Long.valueOf(bytes));
            currentBytes += bytes - (prior == null ? 0L : prior.longValue());
            if (currentBytes > maxBytes) {
                evict = new ArrayList<String>();
                Iterator<Map.Entry<String, Long>> it = lru.entrySet().iterator();
                while (it.hasNext() && currentBytes > maxBytes) {
                    Map.Entry<String, Long> oldest = it.next();
                    if (oldest.getKey().equals(k)) {
                        // Never evict what was just admitted; the next admission trims. Mirrors
                        // GridCache. A dictionary larger than the whole budget never reaches here
                        // - parse() returns OVERSIZED instead - so this only defers, never leaks.
                        continue;
                    }
                    currentBytes -= oldest.getValue().longValue();
                    evict.add(oldest.getKey());
                    it.remove();
                }
            }
        }
        if (evict != null) {
            for (String gone : evict) {
                entries.remove(gone);
                evictions.incrementAndGet();
            }
        }
    }

    private void touch(String k) {
        synchronized (lru) {
            lru.get(k);
        }
    }

    int size() {
        return entries.size();
    }

    long bytes() {
        synchronized (lru) {
            return currentBytes;
        }
    }

    long maxBytes() {
        return maxBytes;
    }

    long hitCount() {
        return hits.get();
    }

    long missCount() {
        return misses.get();
    }

    long evictionCount() {
        return evictions.get();
    }

    /** Empties the cache. For tests and for a caller that has deliberately changed the classpath. */
    void clear() {
        entries.clear();
        synchronized (lru) {
            lru.clear();
            currentBytes = 0L;
        }
    }

    @Override
    public String toString() {
        return "InitFileCache[" + size() + " files, " + bytes() + " / " + maxBytes + " bytes, "
                + hits.get() + " hits, " + misses.get() + " misses, " + evictions.get()
                + " evictions]";
    }

    // ------------------------------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------------------------------

    /**
     * Reads and indexes one init file. Visible for the cache's own tests; does no caching itself.
     *
     * @param budget the largest accounted size that may be cached; a dictionary above it is
     *               reported as {@link Dictionary#OVERSIZED} so the caller streams instead
     */
    static Dictionary parse(String fileKey, long budget) {
        // Belt and braces. Proj4FileReader.checkedFileKey has already applied ResourceNames, but
        // this is the line that turns a key into a resource name, so it is the line that must not
        // be reachable with an unchecked one -- a future caller reaching parse() directly would
        // otherwise re-open the +init= traversal hole silently. MISSING, not an exception: this
        // guard is unreachable through the supported path, so it should behave like "no such file"
        // rather than invent a second error contract.
        if (!ResourceNames.isSafe(fileKey)) {
            return Dictionary.MISSING;
        }
        String resource = Proj4FileReader.RESOURCE_PREFIX + fileKey;
        InputStream in = Proj4FileReader.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            return Dictionary.MISSING;
        }
        Map<String, String[]> byCode = new HashMap<String, String[]>();
        Map<List<String>, String> byParams = new HashMap<List<String>, String>();
        long bytes = 0L;
        IOException truncation = null;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(in));
            StreamTokenizer t = Proj4FileReader.createTokenizer(reader);
            t.nextToken();
            while (t.ttype == '<') {
                Pair<String, List> pair = Proj4FileReader.parseTokenizer(t);
                String crsName = pair.fst();
                @SuppressWarnings("unchecked")
                List<String> values = (List<String>) pair.snd();
                String[] params = values.toArray(new String[values.size()]);

                // First match wins, in both directions, exactly as the linear scan did.
                if (!byCode.containsKey(crsName)) {
                    byCode.put(crsName, params);
                    bytes += weigh(crsName, params);
                }
                // The reverse index is fed from the entry stream, not from byCode: a code repeated
                // with different parameters contributed both parameter sets to the old whole-file
                // scan, and dropping the second would silently answer null where it answered a code.
                List<String> paramKey = Arrays.asList(params);
                if (!byParams.containsKey(paramKey)) {
                    byParams.put(paramKey, crsName);
                    bytes += REVERSE_ENTRY_BYTES;
                }
                if (bytes > budget) {
                    return Dictionary.OVERSIZED;
                }
            }
        } catch (IOException e) {
            // Deferred: the entries already indexed are the ones the old scan would have reached
            // before this. Replayed on a miss, which is exactly the set of lookups that would
            // have hit it.
            truncation = e;
        } catch (RuntimeException e) {
            truncation = new IOException("Malformed init file " + resource + ": " + e, e);
        } finally {
            closeQuietly(reader, in);
        }
        return new Dictionary(byCode, byParams, truncation, bytes);
    }

    private static void closeQuietly(BufferedReader reader, InputStream in) {
        try {
            if (reader != null) {
                reader.close();
            } else {
                in.close();
            }
        } catch (IOException ignored) {
            // Closing a classpath resource cannot fail in a way the caller can act on, and
            // throwing here would mask the parse result.
        }
    }

    /**
     * A {@code HashMap.Node} plus an {@code Arrays$ArrayList} view. The view wraps the array the
     * forward index already holds, so the strings and the array itself are not charged twice.
     */
    private static final long REVERSE_ENTRY_BYTES = 80L;

    private static long weigh(String code, String[] params) {
        long b = 48L /* HashMap.Node */ + stringBytes(code) + 16L + 8L * params.length;
        for (int i = 0; i < params.length; i++) {
            b += stringBytes(params[i]);
        }
        return b;
    }

    private static long stringBytes(String s) {
        return s == null ? 0L : 40L + 2L * s.length();
    }

    // ------------------------------------------------------------------------------------------

    /** One parsed init file, or a marker saying why there is no parse to serve from. */
    static final class Dictionary {

        /** The classpath resource does not exist. */
        static final Dictionary MISSING = new Dictionary();

        /** Too large to cache, or caching disabled: the caller must stream the file. */
        static final Dictionary OVERSIZED = new Dictionary();

        private final Map<String, String[]> byCode;
        private final Map<List<String>, String> byParams;
        private final IOException truncation;
        final long bytes;

        private Dictionary() {
            this.byCode = Collections.emptyMap();
            this.byParams = Collections.emptyMap();
            this.truncation = null;
            this.bytes = 0L;
        }

        private Dictionary(Map<String, String[]> byCode, Map<List<String>, String> byParams,
                IOException truncation, long bytes) {
            this.byCode = byCode;
            this.byParams = byParams;
            this.truncation = truncation;
            this.bytes = bytes;
        }

        /** Entries indexed. Excludes anything after a malformed entry. */
        int entryCount() {
            return byCode.size();
        }

        /** True if the file did not parse to its end. */
        boolean isTruncated() {
            return truncation != null;
        }

        /**
         * The parameter array for {@code code}, or {@code null} if the file has no such entry.
         *
         * <p><b>The returned array is the cached one. Callers must copy before handing it out.</b>
         *
         * @throws IOException the parse failure the old streaming scan would have hit while looking
         *                     for a code this file does not contain
         */
        String[] parameters(String code) throws IOException {
            String[] hit = byCode.get(code);
            if (hit != null) {
                return hit;
            }
            throw0();
            return null;
        }

        /**
         * The first code whose parameter array equals {@code params}, or {@code null}.
         *
         * @throws IOException as {@link #parameters(String)}
         */
        String codeForParameters(String[] params) throws IOException {
            if (params != null) {
                String hit = byParams.get(Arrays.asList(params));
                if (hit != null) {
                    return hit;
                }
            }
            throw0();
            return null;
        }

        private void throw0() throws IOException {
            if (truncation != null) {
                // A fresh instance: the cached one would accumulate every caller's stack.
                throw new IOException(truncation.getMessage(), truncation);
            }
        }
    }
}

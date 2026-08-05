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
package org.locationtech.proj4j.resource;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.TreeSet;

/**
 * Resolves resources from a single directory on the local filesystem.
 * <p>
 * The root is fixed at construction and every resolved path is verified to stay inside it, so a
 * {@code +nadgrids=} token drawn from per-row user data cannot be turned into an arbitrary-file-open
 * primitive. Symlinks are followed only if the <em>real</em> target is still under the real root.
 * <p>
 * A name may name a file in a sub-directory — {@code tests/us_noaa_nadcon5_conus.tif} — because that
 * is how PROJ spells a grid relative to a search directory. This is the resolver where that
 * relaxation has real stakes, since it <em>is</em> filesystem-backed, so containment is enforced
 * three times over rather than by banning the separator: {@link ResourceNames} refuses any
 * {@code ..} or {@code .} segment, any absolute name and any empty segment; the resolved path is
 * re-checked against the root after {@code normalize()}; and it is checked once more after
 * {@code toRealPath()}, which is what catches a symlink planted inside the root. Each of the three
 * would refuse a traversal on its own.
 * <p>
 * All three of those run at <em>resolution</em> time, and a handle outlives its resolution — it is
 * memoised by {@link CachingResourceResolver} for the life of the JVM. {@code ResourceHandle.open()}
 * therefore re-canonicalises and re-checks, and opens the <em>canonical</em> path rather than the
 * requested one, so a symlink planted between the check and the open is refused instead of followed.
 * See {@code resolve}'s handle for why the identity check is kept alongside the containment check.
 * <p>
 * This is the class an application uses if it deliberately wants {@code PROJ_DATA} semantics:
 * <pre>{@code
 * String projData = System.getenv("PROJ_DATA");           // the application's decision, not ours
 * GridResources.addResolver(new DirectoryResourceResolver(Paths.get(projData)));
 * }</pre>
 * proj4j itself never reads an environment variable, so the dependency on ambient state is visible
 * in the application's own code.
 */
public final class DirectoryResourceResolver implements ResourceResolver {

    private final Path root;
    private final Path realRoot;
    private final String name;
    private final int priority;

    public DirectoryResourceResolver(Path root) {
        this(root, 50);
    }

    public DirectoryResourceResolver(Path root, int priority) {
        if (root == null) {
            throw new IllegalArgumentException("root");
        }
        this.root = root.toAbsolutePath().normalize();
        Path real;
        try {
            real = this.root.toRealPath();
        } catch (IOException e) {
            real = this.root;
        }
        this.realRoot = real;
        this.priority = priority;
        this.name = "directory:" + this.root;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public ResourceHandle resolve(String resourceName) throws IOException {
        if (!ResourceNames.isSafe(resourceName)) {
            return null;
        }
        final Path candidate = root.resolve(resourceName).normalize();
        if (!candidate.startsWith(root)) {
            return null;
        }
        if (!Files.isRegularFile(candidate)) {
            return null;
        }
        // Re-check after symlink resolution: a symlink inside the root may point outside it.
        Path real = candidate.toRealPath();
        if (!real.startsWith(realRoot)) {
            return null;
        }
        final long size = Files.size(candidate);
        final String requested = resourceName;
        final String origin = candidate.toUri().toString();
        final Path resolvedReal = real;
        return new ResourceHandle() {
            @Override
            public String name() {
                return requested;
            }

            @Override
            public String origin() {
                return origin;
            }

            @Override
            public long sizeBytes() {
                return size;
            }

            /**
             * Re-validates, then opens the <strong>canonical</strong> path.
             *
             * <p>This used to open {@code candidate} — the pre-{@code toRealPath()} spelling — which
             * threw away the containment check {@code resolve()} had just performed. All three
             * checks above happen at resolution time, and a handle outlives its resolution: it is
             * cached by {@link CachingResourceResolver} and reused for the life of the JVM. Anyone
             * who can write inside the data directory could therefore replace a plain file with a
             * symlink to {@code /etc/shadow} <em>after</em> the checks passed, and every later
             * {@code open()} would follow it, because {@code new RandomAccessFile(candidate)}
             * re-traverses the name and re-follows whatever links it now finds.
             *
             * <p>Two things close it. The path is re-canonicalised and re-checked against the real
             * root, so a link planted since resolution is caught. And the file is opened by its
             * canonical path rather than by the requested one: {@code toRealPath()} has already
             * resolved every link in it, so the open itself cannot traverse one. The residual
             * window is between {@code toRealPath()} and {@code new RandomAccessFile} on a path with
             * no symlink components, which is as close as the JDK's file API allows without
             * {@code O_NOFOLLOW}.
             *
             * <p>The identity check is separate from the containment check and both are kept. A
             * swap from one in-root file to another in-root file stays inside the root and would
             * pass containment, but it is still not the resource that was resolved, and a grid
             * silently becoming a different grid is a wrong answer rather than an error.
             */
            @Override
            public SeekableByteReader open() throws IOException {
                Path nowReal = candidate.toRealPath();
                if (!nowReal.startsWith(realRoot)) {
                    throw new IOException("Refusing to open " + requested + ": it now resolves to "
                            + nowReal + ", which is outside " + realRoot
                            + ". The path changed between resolution and open.");
                }
                if (!nowReal.equals(resolvedReal)) {
                    throw new IOException("Refusing to open " + requested + ": it resolved to "
                            + resolvedReal + " but now resolves to " + nowReal
                            + ". The path changed between resolution and open.");
                }
                return new RandomAccessFileByteReader(new RandomAccessFile(nowReal.toFile(), "r"));
            }
        };
    }

    /**
     * Every regular file at or below the root, named exactly as {@link #resolve} would have to be
     * called to get it — so a file in a sub-directory appears as {@code tests/foo.tif}, not
     * {@code foo.tif}.
     * <p>
     * This walks sub-directories because {@link #resolve} now accepts a name with interior segments.
     * A resolver that declares itself {@linkplain #isEnumerable() enumerable} while omitting names it
     * would happily resolve is reporting a smaller inventory than it has, and
     * {@code Proj.availableGrids()} would repeat the omission. The two directions are kept in step by
     * the one rule: a name {@link ResourceNames} would refuse is skipped here rather than listed and
     * then refused on use.
     */
    @Override
    public Collection<String> listAvailable() {
        TreeSet<String> names = new TreeSet<String>();
        try {
            collect(root, "", 0, names);
        } catch (IOException e) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<String>(names));
    }

    /**
     * Bounds the walk. PROJ data directories are flat or one level deep; the cap exists so a
     * pathological tree cannot turn an inventory call into an unbounded one.
     */
    private static final int MAX_DEPTH = 8;

    private void collect(Path dir, String prefix, int depth, TreeSet<String> into) throws IOException {
        DirectoryStream<Path> stream = Files.newDirectoryStream(dir);
        try {
            for (Path p : stream) {
                String name = prefix + p.getFileName().toString();
                if (!ResourceNames.isSafe(name)) {
                    continue;
                }
                if (Files.isRegularFile(p)) {
                    into.add(name);
                } else if (depth < MAX_DEPTH
                        && Files.isDirectory(p)
                        // A symlinked sub-directory pointing out of the root is not part of this
                        // resolver's inventory, for the same reason resolve() would refuse it.
                        && p.toRealPath().startsWith(realRoot)) {
                    collect(p, name + "/", depth + 1, into);
                }
            }
        } finally {
            stream.close();
        }
    }

    @Override
    public boolean isEnumerable() {
        return true;
    }

    private static final class RandomAccessFileByteReader implements SeekableByteReader {
        private final RandomAccessFile file;

        RandomAccessFileByteReader(RandomAccessFile file) {
            this.file = file;
        }

        @Override
        public int read(long position, byte[] dst, int off, int len) throws IOException {
            file.seek(position);
            return file.read(dst, off, len);
        }

        @Override
        public long size() throws IOException {
            return file.length();
        }

        @Override
        public void close() throws IOException {
            file.close();
        }
    }
}

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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * Resolves resources from a fixed classpath prefix.
 * <p>
 * The prefix is fixed at construction and the requested name is validated by
 * {@link ResourceNames}, so a {@code +nadgrids=../../../etc/passwd} token cannot escape it. proj4j
 * 1.4.x had a single hardcoded prefix ({@code /proj4/nad/}) and no validation.
 * <p>
 * A name <em>may</em> contain interior path segments — {@code tests/us_noaa_nadcon5_conus.tif} is a
 * spelling PROJ supports and the conformance corpus uses. That relaxation is safe <em>here</em> for
 * a structural reason worth stating plainly, because it is the reason the guard was allowed to
 * loosen: resolution goes through {@link ClassLoader#getResource}, which cannot address anything
 * outside the classpath whatever string it is handed. There is no filesystem root to escape from, so
 * the traversal defence is the standard {@code ..}/absolute-path rejection in {@link ResourceNames}
 * rather than a blanket ban on the separator. {@link #find(String)} additionally refuses any URL
 * whose protocol is not local, so a classloader pointed at a remote URL cannot turn a grid lookup
 * into network I/O.
 * <p>
 * Classpath resources are not enumerable in general. Passing an {@code indexResource} — a
 * newline-delimited manifest generated at build time, one resource name per line — makes this
 * resolver {@linkplain #isEnumerable() enumerable}, so {@code availableGrids()} can report a real
 * list instead of an empty one that gets misread as "nothing installed".
 *
 * <h2>This is the resolver whose {@code open()} is an allocation</h2>
 *
 * <p>A classpath resource is not seekable, so {@code open()} has to materialise the entry. That made
 * it the one place where a caller's byte ceiling arrived too late to be a defence: {@code
 * Resources.readAll} opened the handle, the whole entry landed on the heap, and only then was the
 * size compared against the 128 MiB grid limit. The handle therefore implements
 * {@link Resources.Bounded} and applies the caller's ceiling <em>while reading</em>, and
 * {@code open()} itself is bounded by {@link #maxEntryBytes()} for the callers that stream a data
 * file without a budget of their own. {@code DirectoryResourceResolver} never had this problem — its
 * {@code open()} returns a {@code RandomAccessFile} wrapper and reads nothing.
 */
public final class ClasspathResourceResolver implements ResourceResolver {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final String name;
    private final ClassLoader loader;
    private final String prefix;
    private final String indexResource;
    private final int priority;

    /** Lazily loaded, then never mutated. {@code null} means "not loaded yet". */
    private volatile List<String> index;

    public ClasspathResourceResolver(ClassLoader loader, String prefix) {
        this(loader, prefix, null, 100);
    }

    public ClasspathResourceResolver(ClassLoader loader, String prefix, String indexResource) {
        this(loader, prefix, indexResource, 100);
    }

    public ClasspathResourceResolver(ClassLoader loader, String prefix, String indexResource, int priority) {
        if (prefix == null) {
            throw new IllegalArgumentException("prefix");
        }
        this.loader = loader;
        this.prefix = prefix.endsWith("/") ? prefix : prefix + "/";
        this.indexResource = indexResource;
        this.priority = priority;
        this.name = "classpath:" + this.prefix;
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
        final String path = prefix + resourceName;
        final URL url = find(path);
        if (url == null) {
            return null;
        }
        return new ClasspathHandle(resourceName, "classpath:" + path, url, contentLength(url));
    }

    /**
     * A located classpath entry.
     *
     * <p>Named rather than anonymous because it implements {@link Resources.Bounded} as well as
     * {@link ResourceHandle}, and an anonymous class can implement only one interface. That second
     * interface is the point: this resolver's {@code open()} has to materialise the whole entry (a
     * classpath resource is not seekable), so it is the one handle for which
     * {@code Resources.readAll}'s ceiling has to reach <em>inside</em> the read instead of judging
     * the result.
     */
    private static final class ClasspathHandle implements ResourceHandle, Resources.Bounded {

        private final String requested;
        private final String origin;
        private final URL url;
        private final long size;

        ClasspathHandle(String requested, String origin, URL url, long size) {
            this.requested = requested;
            this.origin = origin;
            this.url = url;
            this.size = size;
        }

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
         * Bounded by {@link #maxEntryBytes()} rather than unbounded. A caller with a tighter budget
         * — every grid reader — goes through {@code Resources.readAll}, which routes to
         * {@link #readAll(long)}; this ceiling is the backstop for the direct callers that stream a
         * data file (the {@code .pjdx} database) and have no grid-sized budget of their own.
         */
        @Override
        public SeekableByteReader open() throws IOException {
            return new ByteArrayByteReader(slurp(url, origin, size, maxEntryBytes()));
        }

        @Override
        public byte[] readAll(long maxBytes) throws IOException {
            return slurp(url, origin, size, Math.min(maxBytes, maxEntryBytes()));
        }
    }

    /**
     * Hard ceiling on a single classpath entry read into memory, {@code
     * -Dproj4j.resources.maxClasspathEntryBytes}, default 512 MiB.
     *
     * <p>There was no ceiling at all here before, at any size. This is deliberately generous — it is
     * not the grid budget, which is 128 MiB and is applied on top by {@code Resources.readAll} — so
     * that it bounds the pathological case without becoming the binding constraint on a legitimate
     * data artifact.
     */
    static long maxEntryBytes() {
        String raw = System.getProperty("proj4j.resources.maxClasspathEntryBytes");
        if (raw != null) {
            try {
                long v = Long.parseLong(raw.trim());
                if (v > 0) {
                    return v;
                }
            } catch (NumberFormatException e) {
                // fall through to the default, as Grid and GridExtents do for their properties
            }
        }
        return 512L * 1024L * 1024L;
    }

    @Override
    public Collection<String> listAvailable() {
        if (indexResource == null) {
            return Collections.emptyList();
        }
        List<String> cached = index;
        if (cached == null) {
            cached = loadIndex();
            index = cached;
        }
        return cached;
    }

    @Override
    public boolean isEnumerable() {
        return indexResource != null;
    }

    private List<String> loadIndex() {
        URL url = find(indexResource.startsWith("/") ? indexResource.substring(1) : prefix + indexResource);
        if (url == null) {
            return Collections.emptyList();
        }
        // Sorted, so the reported list does not depend on how the index file was written.
        TreeSet<String> names = new TreeSet<String>();
        try {
            InputStream in = url.openStream();
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, UTF8));
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    names.add(line);
                }
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<String>(names));
    }

    /**
     * Local URL protocols a classloader may legitimately hand back: plain files, jar entries, the JDK
     * runtime image, and the OSGi and JBoss module loaders.
     */
    private static final java.util.Set<String> LOCAL_PROTOCOLS = Collections.unmodifiableSet(
            new java.util.HashSet<String>(Arrays.asList(
                    "file", "jar", "jrt", "resource", "bundleresource", "bundle", "vfs")));

    private URL find(String path) {
        URL url = loader != null
                ? loader.getResource(path)
                // Fall back to this class's own loader, which is what a resource-only data jar on the
                // same classpath will be visible to.
                : ClasspathResourceResolver.class.getResource("/" + path);
        if (url == null) {
            return null;
        }
        // proj4j core performs no network I/O, and this is the one place a URL exists at all -- it is
        // the return type of ClassLoader.getResource, never built from a string. A classloader
        // configured with a remote URL would otherwise make that guarantee depend on the deployment
        // rather than on the code, so anything non-local is refused here.
        String protocol = url.getProtocol();
        if (protocol == null || !LOCAL_PROTOCOLS.contains(protocol.toLowerCase(java.util.Locale.ROOT))) {
            return null;
        }
        return url;
    }

    private static long contentLength(URL url) {
        try {
            return url.openConnection().getContentLengthLong();
        } catch (IOException e) {
            return -1L;
        }
    }

    /**
     * Reads the entry, refusing to exceed {@code maxBytes} <strong>while reading</strong>.
     *
     * <p>Three things changed from the version this replaces, and each of them was load-bearing:
     * <ol>
     *   <li>It stops at the ceiling. The old loop read to EOF and the caller compared afterwards, so
     *       the refusal cost exactly as much memory as acceptance would have.</li>
     *   <li>The initial buffer is sized from the entry's declared length clamped to the ceiling, not
     *       from {@code in.available()}. {@code available()} on a {@code jar:} stream is the
     *       inflater's view of an attacker-supplied central-directory field; it was the only sizing
     *       input, and it is the one input that should not be trusted with an allocation.</li>
     *   <li>When the declared length is known and within budget, the bytes go straight into an
     *       exactly sized array — no doubling and no {@code toByteArray()} copy, so the legitimate
     *       path allocates {@code n} rather than roughly {@code 3n}. A declared length is still only
     *       a claim: if the entry turns out to be longer, the read falls through to the bounded
     *       streaming path rather than trusting either the claim or the stream.</li>
     * </ol>
     */
    private static byte[] slurp(URL url, String origin, long declaredSize, long maxBytes)
            throws IOException {
        if (declaredSize > maxBytes) {
            throw new IOException("Classpath resource " + origin + " declares " + declaredSize
                    + " bytes, which exceeds the " + maxBytes + " byte read limit");
        }
        InputStream in = url.openStream();
        try {
            ByteArrayOutputStream out;
            long total;
            if (declaredSize >= 0) {
                byte[] exact = new byte[(int) declaredSize];
                int off = 0;
                while (off < exact.length) {
                    int n = in.read(exact, off, exact.length - off);
                    if (n < 0) {
                        break;
                    }
                    off += n;
                }
                int extra = in.read();
                if (extra < 0) {
                    // The common case, and the whole read: exactly one allocation of the true size.
                    return off == exact.length ? exact : Arrays.copyOf(exact, off);
                }
                if (off + 1L > maxBytes) {
                    throw tooBig(origin, maxBytes);
                }
                out = new ByteArrayOutputStream(off + 1);
                out.write(exact, 0, off);
                out.write(extra);
                total = off + 1L;
            } else {
                out = new ByteArrayOutputStream(8192);
                total = 0L;
            }
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                total += n;
                if (total > maxBytes) {
                    throw tooBig(origin, maxBytes);
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static IOException tooBig(String origin, long maxBytes) {
        return new IOException("Classpath resource " + origin + " exceeds the " + maxBytes
                + " byte read limit; the read was abandoned rather than completed and rejected");
    }
}

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
package org.locationtech.proj4j.security.resources;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assume;
import org.junit.Test;
import org.locationtech.proj4j.resource.ClasspathResourceResolver;
import org.locationtech.proj4j.resource.ResourceHandle;
import org.locationtech.proj4j.resource.Resources;
import org.locationtech.proj4j.resource.SeekableByteReader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The size cap must run <strong>before</strong> the allocation it exists to prevent.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code Resources.readAll(handle, maxBytes)} opened the handle first and consulted
 * {@code reader.size()} afterwards. {@link ClasspathResourceResolver}'s {@code open()} slurps the
 * whole classpath entry into a {@code ByteArrayOutputStream} — a classpath resource is not seekable,
 * so there is nothing else it can do — which means the entry was <em>already on the heap</em> by the
 * time the 128 MiB grid limit was consulted. The limit reported the size of an allocation it had
 * failed to prevent. {@code DirectoryResourceResolver} was never affected: its {@code open()} hands
 * back a {@code RandomAccessFile} wrapper and reads nothing.
 *
 * <h2>Why these tests assert on bytes allocated, not only on the exception</h2>
 *
 * <p>An exception-only assertion passes both before and after the fix — the old code threw too, just
 * after paying for the read. Every test here therefore measures
 * {@code ThreadMXBean.getThreadAllocatedBytes} across the refusal, or counts stream opens, and every
 * one of them is paired with a control leg <strong>run in the same JVM against the same
 * generator</strong> that shows the instrument registering the thing whose absence is being claimed:
 *
 * <ul>
 *   <li>{@link #anOversizedEntryIsAbandonedRatherThanReadAndRejected()} first reads the same 16 MiB
 *       generator with a budget that admits it and requires the meter to see &ge; 16 MiB. A meter
 *       that always read zero would fail that leg.</li>
 *   <li>{@link #aDeclaredOversizeIsRefusedWithoutOpeningTheStream()} requires a second, legitimate
 *       handle to drive the same open-counter to 1. A counter wired to nothing would fail that
 *       leg.</li>
 *   <li>{@link #shortAndLongEntriesAreReadAtTheirTrueLength()} pins the two paths where the
 *       rewritten reader could silently truncate or over-read <em>legitimate</em> data, because a
 *       guard that refuses everything passes every hostile test in this file.</li>
 * </ul>
 *
 * <h2>The generator</h2>
 *
 * <p>A real classpath entry cannot be made to lie about its length, so these tests drive the
 * resolver through a {@link ClassLoader} that returns a {@code URL} with a private
 * {@link URLStreamHandler}: the declared {@code Content-Length} and the number of bytes the stream
 * actually produces are set independently. That is the only way to exercise the two cases that
 * matter — an unknown length, and a length that lies — and it keeps the subject under test the real
 * {@link ClasspathResourceResolver} rather than a mock of it. The URL's protocol is {@code file}
 * because the resolver refuses any protocol outside its local allow-list.
 */
public class ResourceReadBoundTest {

    private static final long KIB = 1024L;
    private static final long MIB = 1024L * KIB;

    // --- the generator ----------------------------------------------------------------------

    /** Produces {@code length} bytes of a fixed pattern, allocating none of them up front. */
    private static final class PatternStream extends InputStream {
        private long remaining;
        private long position;

        PatternStream(long length) {
            this.remaining = length;
        }

        @Override
        public int read() {
            if (remaining <= 0) {
                return -1;
            }
            remaining--;
            return (int) ((position++) & 0xff);
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (remaining <= 0) {
                return -1;
            }
            int n = (int) Math.min(len, remaining);
            for (int i = 0; i < n; i++) {
                b[off + i] = (byte) ((position + i) & 0xff);
            }
            position += n;
            remaining -= n;
            return n;
        }
    }

    /** A URL whose declared length and real length are chosen independently. */
    private static final class Generator extends URLStreamHandler {
        private final long declared;
        private final long length;
        final AtomicInteger opens = new AtomicInteger();

        Generator(long declared, long length) {
            this.declared = declared;
            this.length = length;
        }

        @Override
        protected URLConnection openConnection(URL u) {
            return new URLConnection(u) {
                @Override
                public void connect() {
                }

                @Override
                public long getContentLengthLong() {
                    return declared;
                }

                @Override
                public InputStream getInputStream() {
                    opens.incrementAndGet();
                    return new PatternStream(length);
                }
            };
        }
    }

    /** A classloader exposing exactly one resource, backed by a {@link Generator}. */
    private static final class OneResourceLoader extends ClassLoader {
        private final String path;
        private final URL url;

        OneResourceLoader(String path, Generator handler) throws IOException {
            super(null);
            this.path = path;
            this.url = new URL("file", "", -1, "/proj4j-generated/" + path, handler);
        }

        @Override
        public URL getResource(String name) {
            return path.equals(name) ? url : null;
        }
    }

    private static ResourceHandle handle(Generator g, String name) throws IOException {
        ClasspathResourceResolver resolver =
                new ClasspathResourceResolver(new OneResourceLoader("gen/" + name, g), "gen");
        ResourceHandle h = resolver.resolve(name);
        assertNotNull("the generated resource must resolve, or the test is measuring nothing", h);
        return h;
    }

    // --- the meter --------------------------------------------------------------------------

    private static com.sun.management.ThreadMXBean allocationMeter() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        Assume.assumeTrue("needs com.sun.management.ThreadMXBean",
                bean instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean sun = (com.sun.management.ThreadMXBean) bean;
        Assume.assumeTrue("needs thread allocation counters", sun.isThreadAllocatedMemorySupported());
        sun.setThreadAllocatedMemoryEnabled(true);
        return sun;
    }

    private static long allocated() {
        return allocationMeter().getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    // --- the tests --------------------------------------------------------------------------

    /**
     * The headline case: an entry that does not declare its length, is far larger than the budget,
     * and must be abandoned mid-read.
     *
     * <p>The control leg runs first and deliberately succeeds, so the numbers below are a comparison
     * between two measurements of the same generator in the same JVM rather than a bare threshold.
     */
    @Test
    public void anOversizedEntryIsAbandonedRatherThanReadAndRejected() throws IOException {
        allocationMeter();
        final long content = 16 * MIB;

        // CONTROL. Same generator, a budget that admits it. If this leg does not register at least
        // the content size, the meter cannot see the allocation whose absence the subject claims.
        Generator big = new Generator(-1L, content);
        long beforeControl = allocated();
        byte[] all = Resources.readAll(handle(big, "unbounded"), 64 * MIB);
        long controlCost = allocated() - beforeControl;
        assertEquals("the generator must really produce " + content + " bytes", content, all.length);
        assertTrue("CONTROL FAILED: reading " + content + " bytes registered only " + controlCost
                + " -- the meter cannot see what the subject claims is absent",
                controlCost >= content);

        // SUBJECT. Identical generator, budget of 256 KiB. The read must stop at the budget.
        Generator same = new Generator(-1L, content);
        long beforeSubject = allocated();
        try {
            Resources.readAll(handle(same, "bounded"), 256 * KIB);
            fail("a " + content + " byte entry must not be readable under a 256 KiB budget");
        } catch (IOException expected) {
            assertTrue("the message must say the read was abandoned, not that it was measured: "
                    + expected.getMessage(), expected.getMessage().contains("exceeds"));
        }
        long subjectCost = allocated() - beforeSubject;

        assertTrue("the refusal allocated " + subjectCost + " bytes for a 256 KiB budget; the cap "
                        + "ran after the allocation it exists to prevent",
                subjectCost < 4 * MIB);
        assertTrue("the refusal must cost far less than the acceptance (" + subjectCost + " vs "
                        + controlCost + ")",
                subjectCost * 4 < controlCost);
    }

    /**
     * When the resolver already knows the length, nothing should be opened at all — the check moves
     * ahead of {@code handle.open()}, not merely ahead of the copy.
     */
    @Test
    public void aDeclaredOversizeIsRefusedWithoutOpeningTheStream() throws IOException {
        Generator oversize = new Generator(64 * MIB, 64 * MIB);
        ResourceHandle h = handle(oversize, "declared-big");
        assertEquals("the handle must report the declared length", 64 * MIB, h.sizeBytes());

        try {
            Resources.readAll(h, 1 * MIB);
            fail("a resource declaring 64 MiB must not be read under a 1 MiB budget");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("exceeds"));
        }
        assertEquals("the stream must never have been opened", 0, oversize.opens.get());

        // CONTROL: the counter is wired to something. A legitimate read drives it to 1.
        Generator small = new Generator(4096L, 4096L);
        byte[] bytes = Resources.readAll(handle(small, "declared-small"), 1 * MIB);
        assertEquals(4096, bytes.length);
        assertEquals("CONTROL FAILED: the open counter never increments, so 0 above proves nothing",
                1, small.opens.get());
    }

    /**
     * A declared length is a claim. A jar central directory can say 1 KiB and then hand over 16 MiB,
     * and the pre-check would wave it through, so the streaming path must stop on its own.
     */
    @Test
    public void aLyingDeclaredLengthCannotDefeatTheBound() throws IOException {
        allocationMeter();
        Generator liar = new Generator(1024L, 16 * MIB);
        ResourceHandle h = handle(liar, "liar");
        assertEquals("the pre-check will see the lie, which is the point", 1024L, h.sizeBytes());

        long before = allocated();
        try {
            Resources.readAll(h, 256 * KIB);
            fail("the real content is 16 MiB and the budget is 256 KiB");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("exceeds"));
        }
        long cost = allocated() - before;
        assertTrue("a lying length must not buy an unbounded read; allocated " + cost,
                cost < 4 * MIB);
    }

    /**
     * The accept side, and the two places the rewritten reader could corrupt legitimate data.
     *
     * <p>A guard that refuses everything passes every hostile test above. These pin that an entry
     * shorter than it declared comes back at its <em>real</em> length rather than zero-padded to the
     * declared one, and that an entry longer than it declared comes back <em>whole</em> rather than
     * truncated at the declared length. Both are silent-wrong-answer shapes, not error shapes.
     */
    @Test
    public void shortAndLongEntriesAreReadAtTheirTrueLength() throws IOException {
        byte[] shortEntry = Resources.readAll(handle(new Generator(4096L, 100L), "short"), 1 * MIB);
        assertEquals("an entry shorter than declared must not be zero-padded", 100,
                shortEntry.length);
        assertPattern(shortEntry);

        byte[] longEntry = Resources.readAll(handle(new Generator(100L, 4096L), "long"), 1 * MIB);
        assertEquals("an entry longer than declared must not be truncated to the declaration", 4096,
                longEntry.length);
        assertPattern(longEntry);

        byte[] exact = Resources.readAll(handle(new Generator(4096L, 4096L), "exact"), 1 * MIB);
        assertEquals(4096, exact.length);
        assertPattern(exact);

        byte[] unknown = Resources.readAll(handle(new Generator(-1L, 4096L), "unknown"), 1 * MIB);
        assertEquals("an entry that declines to declare a length must still be read whole", 4096,
                unknown.length);
        assertPattern(unknown);

        byte[] empty = Resources.readAll(handle(new Generator(0L, 0L), "empty"), 1 * MIB);
        assertEquals(0, empty.length);
    }

    /**
     * {@code open()} is reachable without going through {@code Resources.readAll} — the {@code .pjdx}
     * database streams a classpath entry directly — and had no ceiling of any kind. It has one now.
     */
    @Test
    public void openHasAHardCeilingOfItsOwn() throws IOException {
        String property = "proj4j.resources.maxClasspathEntryBytes";
        String saved = System.getProperty(property);
        try {
            System.setProperty(property, Long.toString(64 * KIB));
            ResourceHandle h = handle(new Generator(-1L, 4 * MIB), "direct-open");
            try {
                SeekableByteReader r = h.open();
                r.close();
                fail("open() must honour the hard classpath-entry ceiling");
            } catch (IOException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("exceeds"));
            }

            // CONTROL: raise the ceiling and the very same read succeeds, so the refusal above is
            // the ceiling firing rather than the generator or the resolver being broken.
            System.setProperty(property, Long.toString(16 * MIB));
            ResourceHandle ok = handle(new Generator(-1L, 4 * MIB), "direct-open");
            SeekableByteReader r = ok.open();
            try {
                assertEquals(4 * MIB, r.size());
            } finally {
                r.close();
            }
        } finally {
            if (saved == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, saved);
            }
        }
    }

    private static void assertPattern(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            assertEquals("byte " + i + " of " + bytes.length + " came back wrong, so the reader is "
                    + "not returning the stream it was given", (byte) (i & 0xff), bytes[i]);
        }
    }
}

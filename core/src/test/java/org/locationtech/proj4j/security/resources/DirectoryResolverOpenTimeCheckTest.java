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
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.locationtech.proj4j.resource.DirectoryResourceResolver;
import org.locationtech.proj4j.resource.ResourceHandle;
import org.locationtech.proj4j.resource.SeekableByteReader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A path validated at resolution time must still be the path that is opened.
 *
 * <h2>The window</h2>
 *
 * <p>{@code DirectoryResourceResolver.resolve} checks containment three times — {@code ResourceNames}
 * on the name, {@code startsWith(root)} after {@code normalize()}, and {@code startsWith(realRoot)}
 * after {@code toRealPath()}. All three run at <em>resolution</em> time, and the handle they produce
 * outlives them: {@code CachingResourceResolver} memoises it for the life of the JVM, so one
 * resolution can back millions of later opens.
 *
 * <p>{@code open()} then re-derived the file from {@code candidate} — the pre-{@code toRealPath()}
 * spelling — and handed it to {@code new RandomAccessFile}, which walks the name again and follows
 * whatever links it finds <em>now</em>. Anyone able to write inside the data directory could
 * therefore replace a plain file with a symlink after the checks passed, and every subsequent read
 * would follow it. The checks were real; they were just not the thing the open used.
 *
 * <h2>What is asserted, in both directions</h2>
 *
 * <ol>
 *   <li><b>Accept.</b> A symlink to a file inside the root resolves and opens, and returns that
 *       file's bytes. This runs <em>first</em>: without it, a resolver that refused every symlink
 *       would pass everything below while being useless, and the point is that following a link
 *       inside the root is legitimate and still works.</li>
 *   <li><b>Refuse a retarget out of the root.</b> The classic escape.</li>
 *   <li><b>Refuse a retarget to another file inside the root.</b> Containment alone would allow
 *       this, and it is the more dangerous of the two here: one grid silently becoming a different
 *       grid is a wrong answer, and this library's thesis is that a wrong answer is worse than an
 *       error.</li>
 *   <li><b>The handle is still usable if nothing was swapped</b>, re-checked after the two refusals,
 *       so the refusals cannot be a handle that simply stopped working.</li>
 * </ol>
 */
public class DirectoryResolverOpenTimeCheckTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final byte[] REAL = "the grid that was resolved".getBytes(UTF8);
    private static final byte[] DECOY = "a different grid entirely!".getBytes(UTF8);

    private Path root;
    private Path outside;
    private Path link;
    private DirectoryResourceResolver resolver;

    @Before
    public void layOutTheDirectory() throws IOException {
        root = Files.createTempDirectory("proj4j-toctou-root");
        outside = Files.createTempDirectory("proj4j-toctou-outside").resolve("secret");
        Files.write(outside, "OUTSIDE THE ROOT".getBytes(UTF8));
        Files.write(root.resolve("real-grid"), REAL);
        Files.write(root.resolve("other-grid"), DECOY);
        link = root.resolve("link");
        try {
            Files.createSymbolicLink(link, root.resolve("real-grid"));
        } catch (UnsupportedOperationException e) {
            Assume.assumeNoException("this filesystem has no symlinks", e);
        } catch (IOException e) {
            Assume.assumeNoException("symlink creation is not permitted here", e);
        }
        resolver = new DirectoryResourceResolver(root);
    }

    private void retarget(Path target) throws IOException {
        Files.delete(link);
        Files.createSymbolicLink(link, target);
    }

    private static byte[] readAll(ResourceHandle h) throws IOException {
        SeekableByteReader r = h.open();
        try {
            byte[] buf = new byte[(int) r.size()];
            int off = 0;
            while (off < buf.length) {
                int n = r.read(off, buf, off, buf.length - off);
                if (n <= 0) {
                    break;
                }
                off += n;
            }
            return buf;
        } finally {
            r.close();
        }
    }

    @Test
    public void aSymlinkSwappedBetweenResolveAndOpenIsRefused() throws IOException {
        // 1. ACCEPT, first, so everything below is a statement about the swap and not about
        //    symlinks being refused wholesale.
        ResourceHandle handle = resolver.resolve("link");
        assertNotNull("a symlink to a file inside the root must resolve", handle);
        assertArrayEquals("and it must read the file it points at", REAL, readAll(handle));

        // 2. REFUSE: retargeted out of the root after the checks passed.
        retarget(outside);
        try {
            readAll(handle);
            fail("a symlink retargeted outside the root must not be followed");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("changed between resolution and open"));
            assertTrue("the message must say where it now points: " + expected.getMessage(),
                    expected.getMessage().contains("outside"));
        }

        // 3. REFUSE: retargeted to a different file that is still inside the root. Containment
        //    alone passes this; identity is what catches it.
        retarget(root.resolve("other-grid"));
        try {
            byte[] got = readAll(handle);
            fail("a symlink retargeted to another in-root file must not be followed silently; got "
                    + new String(got, UTF8));
        } catch (IOException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("changed between resolution and open"));
        }

        // 4. And the handle still works when nothing has been swapped, so 2 and 3 are the swap
        //    being detected rather than the handle having gone stale.
        retarget(root.resolve("real-grid"));
        assertArrayEquals(REAL, readAll(handle));
    }

    /**
     * The resolution-time check is unchanged and still refuses a link that already points out of
     * the root. Pinned here because the open-time check must be an addition, not a replacement.
     */
    @Test
    public void aSymlinkAlreadyPointingOutOfTheRootNeverResolves() throws IOException {
        retarget(outside);
        assertNull("resolve() must refuse a symlink whose real target is outside the root",
                resolver.resolve("link"));
    }

    /**
     * A plain file replaced by an out-of-root symlink is the same window without the link ever
     * having been visible to {@code resolve}, which is the shape an attacker with write access to
     * the data directory actually has.
     */
    @Test
    public void aPlainFileReplacedByASymlinkAfterResolutionIsRefused() throws IOException {
        ResourceHandle handle = resolver.resolve("real-grid");
        assertNotNull(handle);
        assertArrayEquals(REAL, readAll(handle));

        Files.delete(root.resolve("real-grid"));
        Files.createSymbolicLink(root.resolve("real-grid"), outside);
        try {
            byte[] got = readAll(handle);
            fail("the replaced file must not be read through; got " + new String(got, UTF8));
        } catch (IOException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("changed between resolution and open"));
        }
    }

    /**
     * The ordinary case, with no symlink anywhere: resolve and open repeatedly, because the handle
     * is memoised and reused and the open-time check must not break that.
     */
    @Test
    public void anUnchangedPlainFileOpensAsManyTimesAsItIsAsked() throws IOException {
        ResourceHandle handle = resolver.resolve("other-grid");
        assertNotNull(handle);
        for (int i = 0; i < 25; i++) {
            assertArrayEquals("open " + i, DECOY, readAll(handle));
        }
    }

    /** A root that is itself reached through a symlink must still work; {@code /var} on macOS is. */
    @Test
    public void aRootReachedThroughASymlinkStillResolves() throws IOException {
        Path alias = Files.createTempDirectory("proj4j-toctou-alias").resolve("alias");
        Files.createSymbolicLink(alias, root);
        DirectoryResourceResolver viaAlias = new DirectoryResourceResolver(Paths.get(alias.toString()));
        ResourceHandle handle = viaAlias.resolve("real-grid");
        assertNotNull("a symlinked root is legitimate and must resolve", handle);
        assertArrayEquals(REAL, readAll(handle));
    }
}

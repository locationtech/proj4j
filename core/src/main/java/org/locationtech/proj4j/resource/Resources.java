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

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Small helpers shared by the resolvers and the grid readers. Pure JDK, no state.
 */
public final class Resources {

    private Resources() {
    }

    /**
     * Reads exactly {@code len} bytes at {@code position}, looping over short reads.
     *
     * @throws EOFException if the resource ends first.
     */
    public static void readFully(SeekableByteReader reader, long position, byte[] dst, int off, int len)
            throws IOException {
        int done = 0;
        while (done < len) {
            int n = reader.read(position + done, dst, off + done, len - done);
            if (n <= 0) {
                throw new EOFException("Unexpected end of resource at byte " + (position + done)
                        + " (wanted " + len + " bytes from " + position + ")");
            }
            done += n;
        }
    }

    /**
     * Reads up to {@code len} bytes at {@code position}, returning however many were available. Used
     * for format sniffing, where a file shorter than the probe is legitimate.
     *
     * @return number of bytes placed in {@code dst}, possibly 0.
     */
    public static int readAtMost(SeekableByteReader reader, long position, byte[] dst, int off, int len)
            throws IOException {
        int done = 0;
        while (done < len) {
            int n = reader.read(position + done, dst, off + done, len - done);
            if (n <= 0) {
                break;
            }
            done += n;
        }
        return done;
    }

    /**
     * A handle that can apply a byte ceiling to the read <em>itself</em>, rather than leaving
     * {@link #readAll} to discover the size after the bytes are already on the heap.
     *
     * <p>Package-private on purpose: it is an implementation detail of how {@link #readAll} and
     * {@link ClasspathResourceResolver} cooperate, not something an external resolver has to know
     * about. A handle that does not implement it still gets the {@link ResourceHandle#sizeBytes()}
     * pre-check below and then the seek-and-size path, which never allocates on an unverified
     * length.
     */
    interface Bounded {
        /**
         * @param maxBytes hard ceiling; the implementation must stop reading once it is exceeded,
         *                 not read to the end and compare afterwards
         */
        byte[] readAll(long maxBytes) throws IOException;
    }

    /**
     * Reads the whole resource into a byte array.
     *
     * <h4>The order here is the fix, not decoration</h4>
     *
     * <p>This method used to call {@code handle.open()} first and consult {@code reader.size()}
     * afterwards. For {@link DirectoryResourceResolver} that is harmless — {@code open()} returns a
     * {@code RandomAccessFile} wrapper and reads nothing. For {@link ClasspathResourceResolver} it
     * was not: its {@code open()} slurped the <strong>entire</strong> classpath entry into a
     * {@code ByteArrayOutputStream} and handed back an in-memory reader, so the limit that exists to
     * prevent the allocation ran <em>after</em> the allocation, and a 2 GB jar entry was already on
     * the heap by the time the check said 128 MiB. The ceiling was a diagnostic, not a defence.
     *
     * <p>So: the size the resolver already knows is consulted <em>before</em> anything is opened,
     * and a handle that reads eagerly is asked to apply the ceiling to its own read. Both halves are
     * needed. {@link ResourceHandle#sizeBytes()} may be {@code -1} ("cannot say without reading"),
     * and a declared length is a claim rather than a fact — a jar central directory can lie — so the
     * streaming path stops at {@code maxBytes} regardless of what was declared.
     *
     * @throws IOException if the resource is larger than {@code maxBytes}. Grid parsing in proj4j is
     *                     whole-file, so the bound is what stops a hostile or mistaken
     *                     {@code +nadgrids=} from turning into an OOM.
     */
    public static byte[] readAll(ResourceHandle handle, long maxBytes) throws IOException {
        // Before open(): for a resolver that reads eagerly, open() is the allocation.
        long declared = handle.sizeBytes();
        if (declared > maxBytes) {
            throw new IOException("Resource " + handle.origin() + " is " + declared
                    + " bytes, which exceeds the " + maxBytes + " byte limit for in-memory grids");
        }
        if (handle instanceof Bounded) {
            return ((Bounded) handle).readAll(maxBytes);
        }
        SeekableByteReader reader = handle.open();
        try {
            long size = reader.size();
            if (size > maxBytes) {
                throw new IOException("Resource " + handle.origin() + " is " + size
                        + " bytes, which exceeds the " + maxBytes + " byte limit for in-memory grids");
            }
            if (size < 0) {
                throw new IOException("Resource " + handle.origin() + " has unknown size");
            }
            byte[] all = new byte[(int) size];
            readFully(reader, 0L, all, 0, all.length);
            return all;
        } finally {
            reader.close();
        }
    }

    /**
     * A {@link DataInputStream} over an in-memory copy of the resource. {@code mark}/{@code reset}
     * are unconditionally supported, which the legacy grid readers rely on.
     */
    public static DataInputStream asDataStream(byte[] bytes) {
        InputStream in = new ByteArrayInputStream(bytes);
        return new DataInputStream(in);
    }
}

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

import java.io.Closeable;
import java.io.IOException;

/**
 * A positioned, random-access reader over a resource's bytes.
 * <p>
 * Grid formats need seeks: NTv2 subgrids are chained by byte offset, GTX and CTable2 are
 * row-addressed, and GeoTIFF is index-addressed. A plain {@link java.io.InputStream} cannot
 * express that without either buffering the whole file or re-opening it.
 * <p>
 * <strong>A single {@code SeekableByteReader} is NOT thread-safe.</strong> Obtain one per thread
 * from {@link ResourceHandle#open()}, which returns a fresh, independently positioned reader on
 * every call.
 */
public interface SeekableByteReader extends Closeable {

    /**
     * Reads up to {@code len} bytes starting at absolute {@code position}.
     *
     * @return the number of bytes actually read, or {@code -1} at end of resource. A short read is
     *         permitted; callers that need exactly {@code len} bytes must loop (see
     *         {@link Resources#readFully}).
     */
    int read(long position, byte[] dst, int off, int len) throws IOException;

    /**
     * @return the total size in bytes.
     */
    long size() throws IOException;
}

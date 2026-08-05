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

/**
 * A {@link SeekableByteReader} over an in-memory byte array. Used by resolvers whose backing store
 * is not seekable (classpath resources, jar entries).
 */
public final class ByteArrayByteReader implements SeekableByteReader {

    private final byte[] bytes;

    public ByteArrayByteReader(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes");
        }
        this.bytes = bytes;
    }

    @Override
    public int read(long position, byte[] dst, int off, int len) throws IOException {
        if (position < 0) {
            throw new IOException("Negative position " + position);
        }
        if (position >= bytes.length) {
            return -1;
        }
        int n = (int) Math.min(len, bytes.length - position);
        System.arraycopy(bytes, (int) position, dst, off, n);
        return n;
    }

    @Override
    public long size() {
        return bytes.length;
    }

    @Override
    public void close() {
        // nothing to release
    }
}

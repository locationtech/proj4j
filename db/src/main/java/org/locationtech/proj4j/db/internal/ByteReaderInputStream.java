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
package org.locationtech.proj4j.db.internal;

import java.io.IOException;
import java.io.InputStream;

import org.locationtech.proj4j.resource.SeekableByteReader;

/**
 * Adapts a {@link SeekableByteReader} to a sequential {@link InputStream}.
 * <p>
 * Needed for exactly one thing: {@code java.util.Properties#load} on the {@code db.properties}
 * sidecar. The resolver SPI is seek-oriented because grid formats need seeks, and there is no adapter
 * in core because core has no sequential consumer of one.
 */
public final class ByteReaderInputStream extends InputStream {

    private final SeekableByteReader reader;
    private long position;
    private final byte[] one = new byte[1];

    public ByteReaderInputStream(SeekableByteReader reader) {
        this.reader = reader;
    }

    @Override
    public int read() throws IOException {
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        int n = reader.read(position, b, off, len);
        if (n <= 0) {
            return -1;
        }
        position += n;
        return n;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}

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
package org.locationtech.proj4j.datum.tiff;

import java.io.IOException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The TIFF container: byte order, classic-vs-BigTIFF, and the chain of IFDs.
 *
 * <p>Four signatures are accepted, exactly the four PROJ's {@code IsTIFF}
 * ({@code 9.8.1:src/grids.cpp:377-385}) accepts: {@code II}/{@code MM} crossed with version
 * {@code 42} (classic) and {@code 43} (BigTIFF).
 *
 * <p>The IFD chain is walked eagerly and completely at construction, with a visited-offset set, so a
 * file whose {@code next} pointer loops back on itself fails immediately instead of spinning. libtiff
 * has the same guard; a grid file is untrusted input in this codebase (a {@code +grids=} token can
 * come from a per-row CRS string), so the guard is not optional.
 */
final class TiffFile {

    /** libtiff's own ceiling on directory count, and a sane bound on a grid file. */
    private static final int MAX_DIRECTORIES = 65536;

    private final ByteOrder order;
    private final boolean bigTiff;
    private final List<TiffDirectory> directories;

    private TiffFile(ByteOrder order, boolean bigTiff, List<TiffDirectory> directories) {
        this.order = order;
        this.bigTiff = bigTiff;
        this.directories = directories;
    }

    /**
     * PROJ's {@code IsTIFF}: byte order marker plus version 42 or 43, in either byte order.
     *
     * <p>Ported including its oddity — it tests {@code header[2]}/{@code header[3]} for the magic in
     * <em>both</em> orders regardless of the byte-order marker, so {@code II} with a big-endian 42 is
     * also called a TIFF. Keeping that means proj4j and PROJ agree on <em>which files are TIFFs at
     * all</em>, which is what decides between "unrecognised grid format" and "bad TIFF".
     *
     * @param header the first bytes of the file; only the first four are read
     * @param length how many of them are valid
     */
    static boolean isTiff(byte[] header, int length) {
        if (length < 4) {
            return false;
        }
        int b0 = header[0] & 0xff;
        int b1 = header[1] & 0xff;
        int b2 = header[2] & 0xff;
        int b3 = header[3] & 0xff;
        boolean marker = (b0 == 'I' && b1 == 'I') || (b0 == 'M' && b1 == 'M');
        return marker && ((b2 == 0x2A && b3 == 0) || (b3 == 0x2A && b2 == 0)
                || (b2 == 0x2B && b3 == 0) || (b3 == 0x2B && b2 == 0));
    }

    /**
     * Parses the header and every IFD.
     *
     * @param bytes the whole file
     * @throws IOException if the signature is not TIFF, the file is truncated, or the IFD chain loops
     */
    static TiffFile open(byte[] bytes) throws IOException {
        if (bytes.length < 8) {
            throw new IOException("TIFF file is " + bytes.length
                    + " bytes; the header alone needs 8");
        }
        int b0 = bytes[0] & 0xff;
        int b1 = bytes[1] & 0xff;
        ByteOrder order;
        if (b0 == 'I' && b1 == 'I') {
            order = ByteOrder.LITTLE_ENDIAN;
        } else if (b0 == 'M' && b1 == 'M') {
            order = ByteOrder.BIG_ENDIAN;
        } else {
            throw new IOException("Not a TIFF: byte order marker is 0x"
                    + Integer.toHexString(b0) + Integer.toHexString(b1)
                    + ", expected 'II' or 'MM'");
        }
        int version = (int) TiffDirectory.readUnsigned(bytes, order, 2, 2);
        boolean bigTiff;
        long firstIfd;
        if (version == 42) {
            bigTiff = false;
            firstIfd = TiffDirectory.readUnsigned(bytes, order, 4, 4);
        } else if (version == 43) {
            bigTiff = true;
            int offsetSize = (int) TiffDirectory.readUnsigned(bytes, order, 4, 2);
            if (offsetSize != 8) {
                throw new UnsupportedTiffException("BigTIFF declares an offset size of " + offsetSize
                        + " bytes; only 8 is defined");
            }
            long reserved = TiffDirectory.readUnsigned(bytes, order, 6, 2);
            if (reserved != 0) {
                throw new IOException("BigTIFF reserved field is " + reserved + ", expected 0");
            }
            if (bytes.length < 16) {
                throw new IOException("BigTIFF file is " + bytes.length
                        + " bytes; the header alone needs 16");
            }
            firstIfd = TiffDirectory.readUnsigned(bytes, order, 8, 8);
        } else {
            throw new IOException("Not a TIFF: version is " + version + ", expected 42 or 43");
        }

        List<TiffDirectory> dirs = new ArrayList<TiffDirectory>();
        List<Long> seen = new ArrayList<Long>();
        long at = firstIfd;
        while (at != 0) {
            if (seen.contains(Long.valueOf(at))) {
                throw new IOException("TIFF IFD chain loops back to offset " + at);
            }
            if (dirs.size() >= MAX_DIRECTORIES) {
                throw new IOException("TIFF has more than " + MAX_DIRECTORIES + " directories");
            }
            seen.add(Long.valueOf(at));
            TiffDirectory.Parsed p = TiffDirectory.parse(bytes, order, at, bigTiff);
            dirs.add(p.directory);
            at = p.nextOffset;
        }
        if (dirs.isEmpty()) {
            throw new IOException("TIFF has no image file directory");
        }
        return new TiffFile(order, bigTiff, Collections.unmodifiableList(dirs));
    }

    ByteOrder order() {
        return order;
    }

    boolean isBigTiff() {
        return bigTiff;
    }

    List<TiffDirectory> directories() {
        return directories;
    }
}

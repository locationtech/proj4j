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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One TIFF Image File Directory: a tag-to-field map plus typed accessors.
 *
 * <p>An IFD entry is 12 bytes in classic TIFF (tag, type, 4-byte count, 4-byte value-or-offset) and
 * 20 bytes in BigTIFF (tag, type, 8-byte count, 8-byte value-or-offset). If the values fit in the
 * value slot they are stored there rather than at an offset, which is why every accessor here goes
 * through {@link #bytesOf} instead of seeking unconditionally.
 *
 * <p>Deliberately tolerant in one direction and strict in the other: a tag this reader does not know
 * is kept but never interpreted, while a tag it <em>does</em> know whose type or count is wrong is an
 * error. Silently coercing a malformed field is how a grid file turns into a wrong coordinate.
 *
 * <p>Immutable after construction and therefore safe to share between threads.
 */
final class TiffDirectory {

    private static final Charset ASCII = Charset.forName("US-ASCII");

    private static final class Field {
        final int tag;
        final int type;
        final long count;
        /** Either the file offset of the values, or {@code -1} when they are inline. */
        final long offset;
        /** The raw value slot, always retained; used verbatim when the values are inline. */
        final byte[] inline;

        Field(int tag, int type, long count, long offset, byte[] inline) {
            this.tag = tag;
            this.type = type;
            this.count = count;
            this.offset = offset;
            this.inline = inline;
        }
    }

    private final byte[] bytes;
    private final ByteOrder order;
    private final Map<Integer, Field> fields;
    private final long ownOffset;

    private TiffDirectory(byte[] bytes, ByteOrder order, Map<Integer, Field> fields, long ownOffset) {
        this.bytes = bytes;
        this.order = order;
        this.fields = fields;
        this.ownOffset = ownOffset;
    }

    /**
     * Reads the IFD at {@code offset} and returns it together with the offset of the next IFD.
     *
     * @param bigTiff selects the 20-byte BigTIFF entry layout over the 12-byte classic one
     * @return the parsed directory; {@link #nextOffset} carries the chain pointer
     */
    static Parsed parse(byte[] bytes, ByteOrder order, long offset, boolean bigTiff)
            throws IOException {
        final int entrySize = bigTiff ? 20 : 12;
        // Two distinct widths that are easy to conflate, and conflating them mis-reads every
        // out-of-line value offset in a classic TIFF: the *directory's* entry-count field is 2 bytes
        // in classic TIFF and 8 in BigTIFF, while an *entry's* value-count field is 4 and 8.
        final int dirCountSize = bigTiff ? 8 : 2;
        final int entryCountSize = bigTiff ? 8 : 4;
        final int valueSlot = bigTiff ? 8 : 4;
        final int offsetSize = bigTiff ? 8 : 4;

        long entryCount = readUnsigned(bytes, order, offset, dirCountSize);
        if (entryCount < 0 || entryCount > 65535) {
            throw new IOException("TIFF IFD at " + offset + " declares " + entryCount
                    + " entries, which is not plausible");
        }
        long entriesStart = offset + dirCountSize;
        long end = entriesStart + entryCount * entrySize + offsetSize;
        if (end > bytes.length) {
            throw new IOException("TIFF truncated: IFD at " + offset + " with " + entryCount
                    + " entries needs " + end + " bytes but the file is " + bytes.length);
        }

        Map<Integer, Field> fields = new LinkedHashMap<Integer, Field>();
        for (long i = 0; i < entryCount; i++) {
            long p = entriesStart + i * entrySize;
            int tag = (int) readUnsigned(bytes, order, p, 2);
            int type = (int) readUnsigned(bytes, order, p + 2, 2);
            long count = readUnsigned(bytes, order, p + 4, entryCountSize);
            long slot = p + 4 + entryCountSize;
            byte[] inline = new byte[valueSlot];
            System.arraycopy(bytes, (int) slot, inline, 0, valueSlot);
            int width = TiffTags.sizeOf(type);
            long totalBytes = width == 0 ? -1 : count * width;
            long valueOffset = -1L;
            if (totalBytes > valueSlot) {
                valueOffset = bigTiff
                        ? readUnsigned(bytes, order, slot, 8)
                        : readUnsigned(bytes, order, slot, 4);
                if (valueOffset < 0 || valueOffset + totalBytes > bytes.length) {
                    throw new IOException("TIFF truncated: " + TiffTags.nameOf(tag) + " needs "
                            + totalBytes + " bytes at offset " + valueOffset + " but the file is "
                            + bytes.length);
                }
            }
            fields.put(Integer.valueOf(tag), new Field(tag, type, count, valueOffset, inline));
        }

        long next = readUnsigned(bytes, order, entriesStart + entryCount * entrySize, offsetSize);
        return new Parsed(new TiffDirectory(bytes, order, fields, offset), next);
    }

    /** A parsed directory plus the offset of the next one, or {@code 0} for end-of-chain. */
    static final class Parsed {
        final TiffDirectory directory;
        final long nextOffset;

        Parsed(TiffDirectory directory, long nextOffset) {
            this.directory = directory;
            this.nextOffset = nextOffset;
        }
    }

    /** The file offset this directory was read from. Identity, for diagnostics. */
    long offset() {
        return ownOffset;
    }

    ByteOrder order() {
        return order;
    }

    byte[] fileBytes() {
        return bytes;
    }

    boolean has(int tag) {
        return fields.containsKey(Integer.valueOf(tag));
    }

    /**
     * The values of an integer-typed field, widened to {@code long}. Unsigned types are
     * zero-extended, signed types sign-extended, which is what libtiff's accessors do.
     *
     * @return {@code null} if the tag is absent
     * @throws IOException if the tag is present but not an integer type
     */
    long[] longs(int tag) throws IOException {
        Field f = fields.get(Integer.valueOf(tag));
        if (f == null) {
            return null;
        }
        int width = TiffTags.sizeOf(f.type);
        boolean signed;
        switch (f.type) {
            case TiffTags.TYPE_BYTE:
            case TiffTags.TYPE_SHORT:
            case TiffTags.TYPE_LONG:
            case TiffTags.TYPE_LONG8:
            case TiffTags.TYPE_IFD8:
            case TiffTags.TYPE_UNDEFINED:
                signed = false;
                break;
            case TiffTags.TYPE_SBYTE:
            case TiffTags.TYPE_SSHORT:
            case TiffTags.TYPE_SLONG:
            case TiffTags.TYPE_SLONG8:
                signed = true;
                break;
            default:
                throw new IOException("TIFF " + TiffTags.nameOf(tag) + " has non-integer type "
                        + f.type);
        }
        byte[] src = bytesOf(f);
        int n = (int) Math.min(f.count, src.length / width);
        long[] out = new long[n];
        for (int i = 0; i < n; i++) {
            long v = readUnsigned(src, order, (long) i * width, width);
            if (signed) {
                int shift = 64 - 8 * width;
                v = shift == 0 ? v : (v << shift) >> shift;
            }
            out[i] = v;
        }
        return out;
    }

    /**
     * A single integer-valued tag.
     *
     * @param fallback returned when the tag is absent, exactly as libtiff's {@code TIFFGetField}
     *                 leaves the caller's variable untouched
     */
    int intValue(int tag, int fallback) throws IOException {
        long[] v = longs(tag);
        return v == null || v.length == 0 ? fallback : (int) v[0];
    }

    /**
     * The values of a {@code DOUBLE}-typed field. {@code FLOAT} is accepted and widened, since some
     * writers emit it for {@code GeoPixelScale}.
     *
     * @return {@code null} if the tag is absent
     */
    double[] doubles(int tag) throws IOException {
        Field f = fields.get(Integer.valueOf(tag));
        if (f == null) {
            return null;
        }
        if (f.type != TiffTags.TYPE_DOUBLE && f.type != TiffTags.TYPE_FLOAT) {
            throw new IOException("TIFF " + TiffTags.nameOf(tag) + " has type " + f.type
                    + "; only DOUBLE and FLOAT are read");
        }
        int width = TiffTags.sizeOf(f.type);
        byte[] src = bytesOf(f);
        int n = (int) Math.min(f.count, src.length / width);
        double[] out = new double[n];
        ByteBuffer buf = ByteBuffer.wrap(src).order(order);
        for (int i = 0; i < n; i++) {
            out[i] = f.type == TiffTags.TYPE_DOUBLE ? buf.getDouble(i * 8) : buf.getFloat(i * 4);
        }
        return out;
    }

    /**
     * An {@code ASCII} field with its NUL terminators stripped.
     *
     * @return {@code null} if the tag is absent
     */
    String ascii(int tag) throws IOException {
        Field f = fields.get(Integer.valueOf(tag));
        if (f == null) {
            return null;
        }
        if (f.type != TiffTags.TYPE_ASCII && f.type != TiffTags.TYPE_UNDEFINED
                && f.type != TiffTags.TYPE_BYTE) {
            throw new IOException("TIFF " + TiffTags.nameOf(tag) + " has type " + f.type
                    + "; expected ASCII");
        }
        byte[] src = bytesOf(f);
        int n = (int) Math.min(f.count, src.length);
        int len = n;
        while (len > 0 && src[len - 1] == 0) {
            len--;
        }
        return new String(src, 0, len, ASCII);
    }

    /** The field's value bytes, whether inline or at an offset. */
    private byte[] bytesOf(Field f) {
        int width = TiffTags.sizeOf(f.type);
        long total = width == 0 ? 0 : f.count * width;
        if (f.offset < 0) {
            // Inline. Only the leading `total` bytes of the slot are meaningful.
            int n = (int) Math.min(total, f.inline.length);
            byte[] out = new byte[n];
            System.arraycopy(f.inline, 0, out, 0, n);
            return out;
        }
        byte[] out = new byte[(int) total];
        System.arraycopy(bytes, (int) f.offset, out, 0, out.length);
        return out;
    }

    /**
     * Reads {@code width} bytes as an unsigned little- or big-endian integer.
     *
     * <p>Package-visible and static because the TIFF header itself, before any directory exists,
     * needs exactly this.
     */
    static long readUnsigned(byte[] b, ByteOrder order, long pos, int width) throws IOException {
        if (pos < 0 || pos + width > b.length) {
            throw new IOException("TIFF truncated: wanted " + width + " bytes at " + pos
                    + " but the file is " + b.length);
        }
        int p = (int) pos;
        long v = 0;
        if (order == ByteOrder.LITTLE_ENDIAN) {
            for (int i = width - 1; i >= 0; i--) {
                v = (v << 8) | (b[p + i] & 0xffL);
            }
        } else {
            for (int i = 0; i < width; i++) {
                v = (v << 8) | (b[p + i] & 0xffL);
            }
        }
        return v;
    }
}

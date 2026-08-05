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
package org.locationtech.proj4j.db;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import org.locationtech.proj4j.datum.GridExtents;
import org.locationtech.proj4j.resource.Resources;
import org.locationtech.proj4j.resource.SeekableByteReader;

/**
 * The low-level {@code .pjdx} accessor: header, section directory, string pool, tables and indexes.
 * Knows nothing about CRSs — see {@link PjdxDatabase} for that.
 *
 * <h2>Access strategy</h2>
 * Everything goes through {@link SeekableByteReader}, so the same code serves a jar entry (which
 * arrives as a byte array, because a deflated zip entry cannot be seeked) and a plain file on disk
 * (which is read lazily, a row at a time). Three things are cached in memory because every query needs
 * them and re-reading them per query would dominate: the section directory, the string-pool offset
 * array, and each table's or index's key array, materialised on first use.
 * <p>
 * <strong>Thread-safe.</strong> {@code SeekableByteReader} is not, so this class holds a per-thread
 * reader obtained from a supplier; the caches are populated under double-checked locking with
 * {@code volatile} fields holding immutable arrays, and strings are memoised into a {@code String[]}
 * with benign races — two threads may decode the same string, and both results are equal and immutable.
 */
final class PjdxFile implements Closeable {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Opens a fresh reader. A {@code ResourceHandle} is one implementation of this. */
    interface ReaderSource {
        SeekableByteReader open() throws IOException;
    }

    private final String origin;
    private final ReaderSource source;
    private final ThreadLocal<SeekableByteReader> readers = new ThreadLocal<SeekableByteReader>();
    /** Every reader handed out, so {@link #close()} can shut them all. */
    private final java.util.List<SeekableByteReader> allReaders =
            java.util.Collections.synchronizedList(new java.util.ArrayList<SeekableByteReader>());
    private volatile boolean closed;

    private final long fileLength;
    private final int[] sectionIds;
    private final int[] sectionKinds;
    private final long[] sectionOffsets;
    private final long[] sectionLengths;

    private final long stringBytesBase;
    private final int stringCount;
    private final int[] stringOffsets;
    private final String[] stringCache;

    private final Table[] tableCache = new Table[128];
    private final Index[] indexCache = new Index[128];

    PjdxFile(String origin, ReaderSource source, boolean verifyChecksum) throws IOException {
        this.origin = origin;
        this.source = source;

        SeekableByteReader r = reader();
        byte[] header = new byte[PjdxFormat.HEADER_BYTES];
        Resources.readFully(r, 0L, header, 0, header.length);
        for (int i = 0; i < PjdxFormat.MAGIC.length; i++) {
            if (header[i] != PjdxFormat.MAGIC[i]) {
                throw new IOException(origin + " is not a proj4j database index: bad magic "
                        + hex(header, 0, 8) + " (expected " + new String(PjdxFormat.MAGIC, UTF8) + ")");
            }
        }
        int version = beInt(header, 8);
        if (version != PjdxFormat.FORMAT_VERSION) {
            throw new IOException(origin + " has .pjdx format version " + version
                    + ", but this build of proj4j reads version " + PjdxFormat.FORMAT_VERSION
                    + ". A mismatched proj4j-db artifact must fail here rather than answer wrongly.");
        }
        int sectionCount = beInt(header, 12);
        this.fileLength = beLong(header, 16);
        long dirOffset = beLong(header, 24);

        long actual = r.size();
        if (actual != fileLength) {
            throw new IOException(origin + " declares " + fileLength + " bytes but is " + actual
                    + " bytes: truncated or appended to.");
        }
        if (sectionCount < 0 || sectionCount > 4096) {
            throw new IOException(origin + " declares an implausible section count " + sectionCount);
        }

        // sectionCount is 0..4096 by the check above, so this product genuinely cannot wrap -- the
        // directory is at most 98,304 bytes. What was missing is that dirOffset was never checked
        // against the file, so a header could point the directory read past the end (or into the
        // header) and the failure would be whatever Resources.readFully happened to do.
        long dirBytes = (long) sectionCount * PjdxFormat.DIRECTORY_ENTRY_BYTES;
        if (dirOffset < PjdxFormat.HEADER_BYTES || dirOffset + dirBytes > fileLength) {
            throw new IOException(origin + " puts its " + dirBytes + "-byte section directory at "
                    + dirOffset + ", outside the " + fileLength + "-byte file");
        }
        byte[] dir = new byte[(int) dirBytes];
        Resources.readFully(r, dirOffset, dir, 0, dir.length);
        sectionIds = new int[sectionCount];
        sectionKinds = new int[sectionCount];
        sectionOffsets = new long[sectionCount];
        sectionLengths = new long[sectionCount];
        for (int i = 0; i < sectionCount; i++) {
            int p = i * PjdxFormat.DIRECTORY_ENTRY_BYTES;
            sectionIds[i] = beInt(dir, p);
            sectionKinds[i] = beInt(dir, p + 4);
            sectionOffsets[i] = beLong(dir, p + 8);
            sectionLengths[i] = beLong(dir, p + 16);
            if (i > 0 && sectionIds[i] <= sectionIds[i - 1]) {
                throw new IOException(origin + " section directory is not strictly ascending at entry "
                        + i + "; the file is not the one this reader was generated against.");
            }
            if (sectionOffsets[i] < PjdxFormat.HEADER_BYTES
                    || sectionOffsets[i] + sectionLengths[i] > fileLength) {
                throw new IOException(origin + " section " + sectionIds[i] + " is out of bounds");
            }
        }

        if (verifyChecksum) {
            verifyChecksum(r, header);
        }

        // The string pool is mandatory and every query needs it, so it is read eagerly.
        int strIdx = sectionIndex(PjdxFormat.S_STRINGS);
        if (strIdx < 0) {
            throw new IOException(origin + " has no string pool section");
        }
        long base = sectionOffsets[strIdx];
        long sectionBytes = sectionLengths[strIdx];
        byte[] poolHeader = new byte[8];
        Resources.readFully(r, base, poolHeader, 0, 8);
        stringCount = beInt(poolHeader, 0);
        int bytesRel = beInt(poolHeader, 4);
        if (stringCount < 0) {
            throw new IOException(origin + " has an implausible string count " + stringCount);
        }
        // `stringCount < 0` used to be the ONLY check. `(stringCount + 1) * 4` wraps negative at
        // 2^29 -- a count of 536,870,912 makes that product -2,147,483,644, so `new byte[...]` threw
        // NegativeArraySizeException rather than saying anything about the file, while
        // `new int[stringCount + 1]` two lines later still asked for 2 GB. The count is now checked
        // against the section that has to contain it: the offset array is 4*(n+1) bytes at base+8,
        // and a pool cannot declare more strings than its own section has room to describe.
        //
        // NOT bounded by GridExtents.MAX_EXTENT: this is a string count, not a grid axis, and the
        // shipped proj4j-db.pjdx already holds 97,930 strings.
        int offsetBytes = GridExtents.checkedCount(origin + " string pool offsets",
                stringCount + 1L, 4L, 8L, sectionBytes, "the string-pool section length") * 4;
        byte[] offs = new byte[offsetBytes];
        Resources.readFully(r, base + 8, offs, 0, offs.length);
        stringOffsets = new int[stringCount + 1];
        for (int i = 0; i <= stringCount; i++) {
            stringOffsets[i] = beInt(offs, i * 4);
        }
        stringBytesBase = base + bytesRel;
        stringCache = new String[stringCount];
    }

    private void verifyChecksum(SeekableByteReader r, byte[] header) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the platform; if it is genuinely absent, say so rather than
            // silently skipping the check.
            throw new IOException("SHA-256 is unavailable, so " + origin + " cannot be verified", e);
        }
        byte[] buf = new byte[1 << 16];
        long pos = PjdxFormat.HEADER_BYTES;
        while (pos < fileLength) {
            int want = (int) Math.min(buf.length, fileLength - pos);
            Resources.readFully(r, pos, buf, 0, want);
            md.update(buf, 0, want);
            pos += want;
        }
        byte[] actual = md.digest();
        for (int i = 0; i < PjdxFormat.SHA256_BYTES; i++) {
            if (actual[i] != header[PjdxFormat.SHA256_OFFSET + i]) {
                throw new IOException(origin + " failed its SHA-256 self-check: header says "
                        + hex(header, PjdxFormat.SHA256_OFFSET, PjdxFormat.SHA256_BYTES)
                        + ", content hashes to " + hex(actual, 0, PjdxFormat.SHA256_BYTES)
                        + ". Refusing to answer from bytes that are not the ones that were built.");
            }
        }
    }

    String origin() {
        return origin;
    }

    long fileLength() {
        return fileLength;
    }

    /** Hex of the content digest recorded in the header — the per-executor provenance stamp. */
    String contentSha256() {
        try {
            byte[] header = new byte[PjdxFormat.HEADER_BYTES];
            Resources.readFully(reader(), 0L, header, 0, header.length);
            return hex(header, PjdxFormat.SHA256_OFFSET, PjdxFormat.SHA256_BYTES);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private SeekableByteReader reader() throws IOException {
        if (closed) {
            throw new IOException(origin + " is closed");
        }
        SeekableByteReader r = readers.get();
        if (r == null) {
            r = source.open();
            readers.set(r);
            allReaders.add(r);
        }
        return r;
    }

    @Override
    public void close() throws IOException {
        closed = true;
        IOException first = null;
        synchronized (allReaders) {
            for (SeekableByteReader r : allReaders) {
                try {
                    r.close();
                } catch (IOException e) {
                    if (first == null) {
                        first = e;
                    }
                }
            }
            allReaders.clear();
        }
        if (first != null) {
            throw first;
        }
    }

    // ---------------------------------------------------------------- strings

    int stringCount() {
        return stringCount;
    }

    /**
     * @param id 0-based string id, or -1
     * @return the string, or null iff {@code id < 0}
     */
    String string(int id) {
        if (id < 0) {
            return null;
        }
        if (id >= stringCount) {
            throw new IllegalStateException(origin + ": string id " + id + " of " + stringCount);
        }
        String s = stringCache[id];
        if (s == null) {
            int from = stringOffsets[id];
            int len = stringOffsets[id + 1] - from;
            byte[] b = new byte[len];
            try {
                Resources.readFully(reader(), stringBytesBase + from, b, 0, len);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            s = new String(b, UTF8);
            stringCache[id] = s;
        }
        return s;
    }

    /**
     * @return the id of {@code s}, or -1 if it is not in the pool. A miss here is a fast negative for
     *         the whole query: a code whose text does not appear anywhere in the file cannot name an
     *         object in it.
     */
    int stringId(String s) {
        if (s == null) {
            return -1;
        }
        byte[] want = s.getBytes(UTF8);
        int lo = 0;
        int hi = stringCount - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = compareStringAt(mid, want);
            if (c < 0) {
                lo = mid + 1;
            } else if (c > 0) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    /** Compares pool string {@code id} against {@code want} in unsigned byte order. */
    private int compareStringAt(int id, byte[] want) {
        int from = stringOffsets[id];
        int len = stringOffsets[id + 1] - from;
        byte[] b = new byte[len];
        try {
            Resources.readFully(reader(), stringBytesBase + from, b, 0, len);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int n = Math.min(len, want.length);
        for (int i = 0; i < n; i++) {
            int d = (b[i] & 0xFF) - (want[i] & 0xFF);
            if (d != 0) {
                return d;
            }
        }
        return len - want.length;
    }

    // ---------------------------------------------------------------- sections

    private int sectionIndex(int sectionId) {
        int lo = 0;
        int hi = sectionIds.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (sectionIds[mid] < sectionId) {
                lo = mid + 1;
            } else if (sectionIds[mid] > sectionId) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    boolean hasSection(int sectionId) {
        return sectionIndex(sectionId) >= 0;
    }

    /**
     * A keyed, row-oriented section.
     */
    final class Table {
        final int rowCount;
        final int keyFieldCount;
        private final long rowOffsetsBase;
        private final long rowsBase;
        private final int[] keys;
        private final int[] rowOffsets;

        /**
         * @param base            absolute offset of the table section
         * @param sectionBytes    its declared length, which is what every allocation below is
         *                        checked against. This constructor validated <strong>nothing</strong>
         *                        before: {@code rowCount * keyFieldCount * 4} is three unchecked
         *                        multiplications on values read straight out of the file, and
         *                        {@code (rowCount + 1) * 4} wraps negative at 2<sup>29</sup> exactly
         *                        as the string pool's did.
         */
        Table(long base, long sectionBytes) throws IOException {
            SeekableByteReader r = reader();
            byte[] h = new byte[16];
            Resources.readFully(r, base, h, 0, 16);
            rowCount = beInt(h, 0);
            keyFieldCount = beInt(h, 4);
            rowOffsetsBase = base + beInt(h, 8);
            rowsBase = base + beInt(h, 12);
            if (rowCount < 0 || keyFieldCount < 0) {
                throw new IOException(origin + " table at " + base + " declares " + rowCount
                        + " rows of " + keyFieldCount + " key fields");
            }
            // Deliberately NOT capped at the "0..4" the format javadoc claims: the shipped
            // proj4j-db.pjdx contains a table with keyFieldCount = 5, so that cap would have been a
            // guard that refuses the library's own data. The section length below is the real bound
            // and needs no magic number.
            if (beInt(h, 8) < 16 || beInt(h, 8) > sectionBytes
                    || beInt(h, 12) < 16 || beInt(h, 12) > sectionBytes) {
                throw new IOException(origin + " table at " + base + " puts its row offsets at +"
                        + beInt(h, 8) + " and its rows at +" + beInt(h, 12)
                        + ", outside its " + sectionBytes + "-byte section");
            }
            int keyCount = GridExtents.checkedCount(origin + " table at " + base + " keys",
                    (long) rowCount * keyFieldCount, 4L, 16L, sectionBytes, "the section length");
            byte[] kb = new byte[keyCount * 4];
            Resources.readFully(r, base + 16, kb, 0, kb.length);
            keys = new int[keyCount];
            for (int i = 0; i < keys.length; i++) {
                keys[i] = beInt(kb, i * 4);
            }
            int offsetCount = GridExtents.checkedCount(
                    origin + " table at " + base + " row offsets", rowCount + 1L, 4L,
                    rowOffsetsBase - base, sectionBytes, "the section length");
            byte[] ob = new byte[offsetCount * 4];
            Resources.readFully(r, rowOffsetsBase, ob, 0, ob.length);
            rowOffsets = new int[offsetCount];
            for (int i = 0; i <= rowCount; i++) {
                rowOffsets[i] = beInt(ob, i * 4);
            }
        }

        int key(int row, int field) {
            return keys[row * keyFieldCount + field];
        }

        /**
         * @return the index of the first row whose key tuple is greater than or equal to {@code k},
         *         comparing only {@code k.length} leading fields; {@code rowCount} if there is none
         */
        int lowerBound(int... k) {
            int lo = 0;
            int hi = rowCount;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (compareKey(mid, k) < 0) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo;
        }

        /**
         * @return the row index of an exact full-key match, or -1
         */
        int find(int... k) {
            if (k.length != keyFieldCount) {
                throw new IllegalArgumentException("expected " + keyFieldCount + " key fields, got "
                        + k.length);
            }
            for (int i = 0; i < k.length; i++) {
                if (k[i] < 0) {
                    return -1;
                }
            }
            int row = lowerBound(k);
            return row < rowCount && compareKey(row, k) == 0 ? row : -1;
        }

        int compareKey(int row, int[] k) {
            for (int i = 0; i < k.length; i++) {
                int a = key(row, i);
                int b = k[i];
                if (a != b) {
                    return a < b ? -1 : 1;
                }
            }
            return 0;
        }

        RowCursor row(int rowIndex) {
            int from = rowOffsets[rowIndex];
            int len = rowOffsets[rowIndex + 1] - from;
            byte[] b = new byte[len];
            try {
                Resources.readFully(reader(), rowsBase + from, b, 0, len);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return new RowCursor(b);
        }
    }

    Table table(int sectionId) {
        Table t = sectionId < tableCache.length ? tableCache[sectionId] : null;
        if (t != null) {
            return t;
        }
        int i = sectionIndex(sectionId);
        if (i < 0) {
            throw new IllegalStateException(origin + " has no table section " + sectionId);
        }
        if (sectionKinds[i] != PjdxFormat.KIND_TABLE) {
            throw new IllegalStateException(origin + " section " + sectionId + " is not a table");
        }
        try {
            t = new Table(sectionOffsets[i], sectionLengths[i]);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (sectionId < tableCache.length) {
            tableCache[sectionId] = t;
        }
        return t;
    }

    /**
     * A sorted array of fixed-width {@code u32} tuples.
     */
    final class Index {
        final int entryCount;
        final int fieldCount;
        private final int[] data;

        /**
         * @param sectionBytes the section's declared length. Like {@link Table}, this constructor
         *                     validated <strong>nothing</strong>: {@code entryCount * fieldCount * 4}
         *                     is two unchecked multiplications on values read straight out of the
         *                     file, and a wrapped product yields either a
         *                     {@code NegativeArraySizeException} or a multi-gigabyte
         *                     {@code OutOfMemoryError}, neither of which names the file.
         */
        Index(long base, long sectionBytes) throws IOException {
            SeekableByteReader r = reader();
            byte[] h = new byte[8];
            Resources.readFully(r, base, h, 0, 8);
            entryCount = beInt(h, 0);
            fieldCount = beInt(h, 4);
            if (entryCount < 0 || fieldCount < 0) {
                throw new IOException(origin + " index at " + base + " declares " + entryCount
                        + " entries of " + fieldCount + " fields");
            }
            int slots = GridExtents.checkedCount(origin + " index at " + base,
                    (long) entryCount * fieldCount, 4L, 8L, sectionBytes, "the section length");
            byte[] b = new byte[slots * 4];
            Resources.readFully(r, base + 8, b, 0, b.length);
            data = new int[slots];
            for (int i = 0; i < data.length; i++) {
                data[i] = beInt(b, i * 4);
            }
        }

        int field(int entry, int f) {
            return data[entry * fieldCount + f];
        }

        int lowerBound(int... prefix) {
            int lo = 0;
            int hi = entryCount;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (compare(mid, prefix) < 0) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo;
        }

        int compare(int entry, int[] prefix) {
            for (int i = 0; i < prefix.length; i++) {
                int a = field(entry, i);
                int b = prefix[i];
                if (a != b) {
                    return a < b ? -1 : 1;
                }
            }
            return 0;
        }

        boolean matches(int entry, int[] prefix) {
            return entry < entryCount && compare(entry, prefix) == 0;
        }
    }

    Index index(int sectionId) {
        Index x = sectionId < indexCache.length ? indexCache[sectionId] : null;
        if (x != null) {
            return x;
        }
        int i = sectionIndex(sectionId);
        if (i < 0) {
            throw new IllegalStateException(origin + " has no index section " + sectionId);
        }
        if (sectionKinds[i] != PjdxFormat.KIND_INDEX) {
            throw new IllegalStateException(origin + " section " + sectionId + " is not an index");
        }
        try {
            x = new Index(sectionOffsets[i], sectionLengths[i]);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (sectionId < indexCache.length) {
            indexCache[sectionId] = x;
        }
        return x;
    }

    // ---------------------------------------------------------------- row decoding

    /**
     * A forward-only cursor over one row's bytes. Not thread-safe and not intended to be: a cursor is
     * created, drained and discarded inside a single method call.
     */
    final class RowCursor {
        private final byte[] b;
        private int p;

        RowCursor(byte[] b) {
            this.b = b;
        }

        int remaining() {
            return b.length - p;
        }

        long varint() {
            long v = 0;
            int shift = 0;
            while (true) {
                if (p >= b.length) {
                    throw new IllegalStateException(origin + ": truncated varint in row");
                }
                int c = b[p++] & 0xFF;
                v |= ((long) (c & 0x7F)) << shift;
                if ((c & 0x80) == 0) {
                    return v;
                }
                shift += 7;
                if (shift > 63) {
                    throw new IllegalStateException(origin + ": varint too long");
                }
            }
        }

        int uint() {
            return (int) varint();
        }

        boolean bool() {
            return varint() != 0;
        }

        /** 0 = null, 1 = false, 2 = true. */
        Boolean tri() {
            long v = varint();
            return v == 0 ? null : Boolean.valueOf(v == 2);
        }

        /** @return the string, or null. */
        String str() {
            long v = varint();
            return v == 0 ? null : string((int) v - 1);
        }

        /** @return the string id, or -1 for null. Avoids decoding when only identity is needed. */
        int strId() {
            long v = varint();
            return v == 0 ? -1 : (int) v - 1;
        }

        /** @return the value, or {@link Double#NaN} for SQL NULL. */
        double dbl() {
            int tag = b[p++] & 0xFF;
            switch (tag) {
                case PjdxFormat.DBL_NULL:
                    return Double.NaN;
                case PjdxFormat.DBL_ZERO:
                    return 0.0;
                case PjdxFormat.DBL_LONG: {
                    long zz = varint();
                    long v = (zz >>> 1) ^ -(zz & 1);
                    return (double) v;
                }
                case PjdxFormat.DBL_RAW: {
                    long bits = beLong(b, p);
                    p += 8;
                    return Double.longBitsToDouble(bits);
                }
                default:
                    throw new IllegalStateException(origin + ": unknown double tag " + tag);
            }
        }
    }

    // ---------------------------------------------------------------- primitives

    static int beInt(byte[] b, int p) {
        return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8)
                | (b[p + 3] & 0xFF);
    }

    static long beLong(byte[] b, int p) {
        return ((long) beInt(b, p) << 32) | (beInt(b, p + 4) & 0xFFFFFFFFL);
    }

    static String hex(byte[] b, int off, int len) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[len * 2];
        for (int i = 0; i < len; i++) {
            int v = b[off + i] & 0xFF;
            out[i * 2] = digits[v >>> 4];
            out[i * 2 + 1] = digits[v & 0xF];
        }
        return new String(out);
    }

    @Override
    public String toString() {
        return "PjdxFile[" + origin + ", " + fileLength + " bytes, " + sectionIds.length
                + " sections, " + stringCount + " strings]";
    }

    /** Only used by tests and diagnostics. */
    int[] sectionIdsForDiagnostics() {
        return Arrays.copyOf(sectionIds, sectionIds.length);
    }

    long sectionLengthForDiagnostics(int sectionId) {
        int i = sectionIndex(sectionId);
        return i < 0 ? -1 : sectionLengths[i];
    }
}

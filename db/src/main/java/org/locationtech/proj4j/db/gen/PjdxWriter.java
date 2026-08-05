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
package org.locationtech.proj4j.db.gen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.locationtech.proj4j.db.PjdxFormat;

/**
 * Builds a {@code .pjdx} index. Build-time only; not shipped in the jar.
 *
 * <h2>Determinism, and how it is achieved rather than hoped for</h2>
 * Every ordering in the output file is a <strong>total order over the data</strong>, so two runs over
 * the same input produce byte-identical files and CI can prove it with {@code git diff --exit-code}:
 * <ul>
 *   <li>String ids are assigned in ascending <em>unsigned UTF-8 byte</em> order — not
 *       {@code String.compareTo}, which orders by UTF-16 code unit and disagrees with byte order for
 *       supplementary characters. The reader binary-searches the same byte order, so the two agree by
 *       construction.</li>
 *   <li>Table rows are sorted by their key tuple, and ties are broken by the <em>encoded row bytes</em>.
 *       That gives a total order even for the tables whose keys are genuinely non-unique — aliases,
 *       supersessions — so no {@code HashMap} iteration or input order can leak into the file.</li>
 *   <li>Index entries are sorted by their whole tuple, again a total order because the trailing fields
 *       are the row's table tag and row number.</li>
 * </ul>
 * The two-pass structure exists for the same reason: pass one only <em>collects</em> strings, the pool
 * is then sorted and frozen, and pass two encodes. Assigning ids in encounter order would make the file
 * depend on the dump's row order, which is sqlite's business and not ours.
 */
final class PjdxWriter {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    // ------------------------------------------------------------------ string pool

    static final class StringPool {
        private final Set<String> collected = new HashSet<String>(1 << 16);
        private String[] sorted;
        private Map<String, Integer> ids;
        private byte[][] encoded;

        void add(String s) {
            if (s != null) {
                collected.add(s);
            }
        }

        void finish() {
            List<String> all = new ArrayList<String>(collected);
            final Map<String, byte[]> utf8 = new HashMap<String, byte[]>(all.size() * 2);
            for (String s : all) {
                utf8.put(s, s.getBytes(UTF8));
            }
            Collections.sort(all, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    return compareUnsigned(utf8.get(a), utf8.get(b));
                }
            });
            sorted = all.toArray(new String[0]);
            encoded = new byte[sorted.length][];
            ids = new HashMap<String, Integer>(sorted.length * 2);
            for (int i = 0; i < sorted.length; i++) {
                encoded[i] = utf8.get(sorted[i]);
                ids.put(sorted[i], Integer.valueOf(i));
            }
        }

        int count() {
            return sorted.length;
        }

        int id(String s) {
            if (s == null) {
                return -1;
            }
            Integer i = ids.get(s);
            if (i == null) {
                throw new IllegalStateException("string '" + s + "' was not collected in pass one;"
                        + " the two passes disagree, which would corrupt the file");
            }
            return i.intValue();
        }

        long totalBytes() {
            long n = 0;
            for (byte[] b : encoded) {
                n += b.length;
            }
            return n;
        }

        byte[] serialize() {
            int count = sorted.length;
            int bytesRel = 8 + (count + 1) * 4;
            ByteArrayOutputStream out = new ByteArrayOutputStream(bytesRel + (int) totalBytes());
            writeInt(out, count);
            writeInt(out, bytesRel);
            int off = 0;
            for (int i = 0; i < count; i++) {
                writeInt(out, off);
                off += encoded[i].length;
            }
            writeInt(out, off);
            for (int i = 0; i < count; i++) {
                out.write(encoded[i], 0, encoded[i].length);
            }
            return out.toByteArray();
        }
    }

    static int compareUnsigned(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int d = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (d != 0) {
                return d;
            }
        }
        return a.length - b.length;
    }

    // ------------------------------------------------------------------ field encoder

    /**
     * Encodes one row's fields. In {@code collect} mode nothing is written and strings are merely
     * registered with the pool, so the same emitter code runs in both passes and the two cannot drift.
     */
    static final class Enc {
        private final StringPool pool;
        private final boolean collect;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(256);

        Enc(StringPool pool, boolean collect) {
            this.pool = pool;
            this.collect = collect;
        }

        void reset() {
            out.reset();
        }

        byte[] bytes() {
            return out.toByteArray();
        }

        Enc str(String s) {
            if (collect) {
                pool.add(s);
            } else {
                varint(s == null ? 0 : pool.id(s) + 1L);
            }
            return this;
        }

        Enc uint(long v) {
            if (!collect) {
                if (v < 0) {
                    throw new IllegalArgumentException("uint " + v);
                }
                varint(v);
            }
            return this;
        }

        Enc bool(boolean b) {
            return uint(b ? 1 : 0);
        }

        Enc tri(Boolean b) {
            return uint(b == null ? 0 : b.booleanValue() ? 2 : 1);
        }

        Enc dbl(double v) {
            if (collect) {
                return this;
            }
            if (Double.isNaN(v)) {
                out.write(PjdxFormat.DBL_NULL);
                return this;
            }
            // +0.0 and -0.0 are distinguished: writing -0.0 as DBL_ZERO would change its sign bit.
            if (v == 0.0 && Double.doubleToRawLongBits(v) == 0L) {
                out.write(PjdxFormat.DBL_ZERO);
                return this;
            }
            long asLong = (long) v;
            if ((double) asLong == v && Double.doubleToRawLongBits(v) != 0x8000000000000000L) {
                out.write(PjdxFormat.DBL_LONG);
                varint((asLong << 1) ^ (asLong >> 63));
                return this;
            }
            out.write(PjdxFormat.DBL_RAW);
            long bits = Double.doubleToRawLongBits(v);
            for (int i = 7; i >= 0; i--) {
                out.write((int) (bits >>> (i * 8)) & 0xFF);
            }
            return this;
        }

        private void varint(long v) {
            long x = v;
            while (true) {
                int b = (int) (x & 0x7F);
                x >>>= 7;
                if (x != 0) {
                    out.write(b | 0x80);
                } else {
                    out.write(b);
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------ sections

    interface RowEmitter {
        void emit(Enc e, Object[] row);
    }

    interface KeyExtractor {
        /** Elements are {@code String} (encoded as a string id) or {@code Integer} (encoded as is). */
        Object[] key(Object[] row);
    }

    private static final class PendingTable {
        final int sectionId;
        final String label;
        final List<Object[]> rows;
        final KeyExtractor keys;
        final RowEmitter emitter;
        final int keyFieldCount;
        int[][] encodedKeys;
        byte[][] encodedRows;
        Map<Long, Integer> rowIndexByKey;

        PendingTable(int sectionId, String label, List<Object[]> rows, int keyFieldCount,
                     KeyExtractor keys, RowEmitter emitter) {
            this.sectionId = sectionId;
            this.label = label;
            this.rows = rows;
            this.keyFieldCount = keyFieldCount;
            this.keys = keys;
            this.emitter = emitter;
        }
    }

    static final class PendingIndex {
        final int sectionId;
        final String label;
        final int fieldCount;
        final List<int[]> entries = new ArrayList<int[]>();

        PendingIndex(int sectionId, String label, int fieldCount) {
            this.sectionId = sectionId;
            this.label = label;
            this.fieldCount = fieldCount;
        }

        /**
         * Appends one entry. Order of appends is irrelevant: entries are sorted by their whole tuple
         * on serialisation, which is a total order because the trailing fields identify the row.
         */
        void add(int... fields) {
            if (fields.length != fieldCount) {
                throw new IllegalStateException(label + ": entry has " + fields.length
                        + " fields, declared " + fieldCount);
            }
            for (int f : fields) {
                if (f < 0) {
                    throw new IllegalStateException(label + ": negative field in entry "
                            + Arrays.toString(fields) + " -- a null string id reached an index key,"
                            + " which would sort before every real one and match the wrong rows");
                }
            }
            entries.add(fields);
        }
    }

    final StringPool pool = new StringPool();
    private final List<PendingTable> tables = new ArrayList<PendingTable>();
    private final List<PendingIndex> indexes = new ArrayList<PendingIndex>();
    private final Map<Integer, PendingTable> tableBySection = new HashMap<Integer, PendingTable>();
    private final Map<Integer, Long> sectionSizes = new java.util.TreeMap<Integer, Long>();

    void addTable(int sectionId, String label, List<Object[]> rows, int keyFieldCount,
                  KeyExtractor keys, RowEmitter emitter) {
        PendingTable t = new PendingTable(sectionId, label, rows, keyFieldCount, keys, emitter);
        tables.add(t);
        tableBySection.put(Integer.valueOf(sectionId), t);
    }

    PendingIndex newIndex(int sectionId, String label, int fieldCount) {
        PendingIndex x = new PendingIndex(sectionId, label, fieldCount);
        indexes.add(x);
        return x;
    }

    /** Pass one: register every string, keys included. */
    void collect() {
        Enc collector = new Enc(pool, true);
        for (PendingTable t : tables) {
            for (Object[] row : t.rows) {
                for (Object k : t.keys.key(row)) {
                    if (k instanceof String) {
                        pool.add((String) k);
                    }
                }
                collector.reset();
                t.emitter.emit(collector, row);
            }
        }
    }

    /** Pass two: encode and sort every table. Must be called after {@link StringPool#finish()}. */
    void encodeTables() {
        Enc enc = new Enc(pool, false);
        for (final PendingTable t : tables) {
            int n = t.rows.size();
            final int[][] keys = new int[n][];
            final byte[][] payloads = new byte[n][];
            for (int i = 0; i < n; i++) {
                Object[] row = t.rows.get(i);
                Object[] kraw = t.keys.key(row);
                if (kraw.length != t.keyFieldCount) {
                    throw new IllegalStateException(t.label + ": key has " + kraw.length
                            + " fields, declared " + t.keyFieldCount);
                }
                int[] k = new int[kraw.length];
                for (int j = 0; j < kraw.length; j++) {
                    Object o = kraw[j];
                    if (o instanceof Integer) {
                        k[j] = ((Integer) o).intValue();
                    } else {
                        int id = pool.id((String) o);
                        if (id < 0) {
                            throw new IllegalStateException(t.label + ": null key field " + j);
                        }
                        k[j] = id;
                    }
                }
                keys[i] = k;
                enc.reset();
                t.emitter.emit(enc, row);
                payloads[i] = enc.bytes();
            }
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) {
                order[i] = Integer.valueOf(i);
            }
            Arrays.sort(order, new Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    int[] ka = keys[a.intValue()];
                    int[] kb = keys[b.intValue()];
                    for (int i = 0; i < ka.length; i++) {
                        if (ka[i] != kb[i]) {
                            return ka[i] < kb[i] ? -1 : 1;
                        }
                    }
                    // Total order even when keys collide, so nothing about the input's order survives.
                    return compareUnsigned(payloads[a.intValue()], payloads[b.intValue()]);
                }
            });
            t.encodedKeys = new int[n][];
            t.encodedRows = new byte[n][];
            t.rowIndexByKey = new HashMap<Long, Integer>(Math.max(16, n * 2));
            for (int i = 0; i < n; i++) {
                int src = order[i].intValue();
                t.encodedKeys[i] = keys[src];
                t.encodedRows[i] = payloads[src];
                if (t.keyFieldCount == 2) {
                    long packed = (((long) keys[src][0]) << 32) | (keys[src][1] & 0xFFFFFFFFL);
                    Integer prev = t.rowIndexByKey.put(Long.valueOf(packed), Integer.valueOf(i));
                    if (prev != null) {
                        throw new IllegalStateException(t.label + ": duplicate (auth, code) key at rows "
                                + prev + " and " + i + "; the reader relies on uniqueness here");
                    }
                }
            }
        }
    }

    /**
     * The final row index of a two-field-keyed row, for building indexes that point at it.
     *
     * @return the row index, or -1
     */
    int rowIndex(int sectionId, int authId, int codeId) {
        PendingTable t = tableBySection.get(Integer.valueOf(sectionId));
        if (t == null || t.rowIndexByKey == null) {
            throw new IllegalStateException("section " + sectionId + " is not an encoded 2-key table");
        }
        Integer i = t.rowIndexByKey.get(Long.valueOf((((long) authId) << 32) | (codeId & 0xFFFFFFFFL)));
        return i == null ? -1 : i.intValue();
    }

    int rowCount(int sectionId) {
        PendingTable t = tableBySection.get(Integer.valueOf(sectionId));
        return t == null ? -1 : t.rows.size();
    }

    /** Serialises everything. */
    byte[] serialize() throws IOException {
        Map<Integer, byte[]> payloads = new java.util.TreeMap<Integer, byte[]>();
        payloads.put(Integer.valueOf(PjdxFormat.S_STRINGS), pool.serialize());
        for (PendingTable t : tables) {
            payloads.put(Integer.valueOf(t.sectionId), serializeTable(t));
        }
        for (PendingIndex x : indexes) {
            payloads.put(Integer.valueOf(x.sectionId), serializeIndex(x));
        }
        for (Map.Entry<Integer, byte[]> e : payloads.entrySet()) {
            sectionSizes.put(e.getKey(), Long.valueOf(e.getValue().length));
        }

        int sectionCount = payloads.size();
        // Sections are laid out in ascending id order immediately after the header, then the directory
        // last. The directory is written last only because its offsets are not known until then; the
        // header records where it is.
        long offset = PjdxFormat.HEADER_BYTES;
        Map<Integer, Long> offsets = new java.util.TreeMap<Integer, Long>();
        for (Map.Entry<Integer, byte[]> e : payloads.entrySet()) {
            offsets.put(e.getKey(), Long.valueOf(offset));
            offset += e.getValue().length;
            // 8-byte align, so a future reader may map sections without unaligned access.
            long pad = (8 - (offset & 7)) & 7;
            offset += pad;
        }
        long dirOffset = offset;
        long fileLength = dirOffset + (long) sectionCount * PjdxFormat.DIRECTORY_ENTRY_BYTES;

        byte[] file = new byte[(int) fileLength];
        System.arraycopy(PjdxFormat.MAGIC, 0, file, 0, PjdxFormat.MAGIC.length);
        putInt(file, 8, PjdxFormat.FORMAT_VERSION);
        putInt(file, 12, sectionCount);
        putLong(file, 16, fileLength);
        putLong(file, 24, dirOffset);
        // bytes 32..63 hold the SHA-256, filled in below.

        for (Map.Entry<Integer, byte[]> e : payloads.entrySet()) {
            byte[] b = e.getValue();
            System.arraycopy(b, 0, file, (int) offsets.get(e.getKey()).longValue(), b.length);
        }
        int p = (int) dirOffset;
        for (Map.Entry<Integer, byte[]> e : payloads.entrySet()) {
            int id = e.getKey().intValue();
            int kind = id == PjdxFormat.S_STRINGS ? PjdxFormat.KIND_STRINGS
                    : tableBySection.containsKey(e.getKey()) ? PjdxFormat.KIND_TABLE
                    : PjdxFormat.KIND_INDEX;
            putInt(file, p, id);
            putInt(file, p + 4, kind);
            putLong(file, p + 8, offsets.get(e.getKey()).longValue());
            putLong(file, p + 16, e.getValue().length);
            p += PjdxFormat.DIRECTORY_ENTRY_BYTES;
        }

        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IOException("SHA-256 unavailable", ex);
        }
        md.update(file, PjdxFormat.HEADER_BYTES, file.length - PjdxFormat.HEADER_BYTES);
        byte[] digest = md.digest();
        System.arraycopy(digest, 0, file, PjdxFormat.SHA256_OFFSET, PjdxFormat.SHA256_BYTES);
        return file;
    }

    /** Section id to serialised byte count, for the size report. */
    Map<Integer, Long> sectionSizes() {
        return sectionSizes;
    }

    private static byte[] serializeTable(PendingTable t) {
        int n = t.encodedRows.length;
        int keyBytes = n * t.keyFieldCount * 4;
        int rowOffsetsRel = 16 + keyBytes;
        int rowsRel = rowOffsetsRel + (n + 1) * 4;
        int total = rowsRel;
        for (byte[] r : t.encodedRows) {
            total += r.length;
        }
        byte[] out = new byte[total];
        putInt(out, 0, n);
        putInt(out, 4, t.keyFieldCount);
        putInt(out, 8, rowOffsetsRel);
        putInt(out, 12, rowsRel);
        int p = 16;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < t.keyFieldCount; j++) {
                putInt(out, p, t.encodedKeys[i][j]);
                p += 4;
            }
        }
        int off = 0;
        for (int i = 0; i < n; i++) {
            putInt(out, rowOffsetsRel + i * 4, off);
            off += t.encodedRows[i].length;
        }
        putInt(out, rowOffsetsRel + n * 4, off);
        p = rowsRel;
        for (int i = 0; i < n; i++) {
            System.arraycopy(t.encodedRows[i], 0, out, p, t.encodedRows[i].length);
            p += t.encodedRows[i].length;
        }
        return out;
    }

    private static byte[] serializeIndex(PendingIndex x) {
        Collections.sort(x.entries, new Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                for (int i = 0; i < a.length; i++) {
                    if (a[i] != b[i]) {
                        return a[i] < b[i] ? -1 : 1;
                    }
                }
                return 0;
            }
        });
        int n = x.entries.size();
        byte[] out = new byte[8 + n * x.fieldCount * 4];
        putInt(out, 0, n);
        putInt(out, 4, x.fieldCount);
        int p = 8;
        for (int[] e : x.entries) {
            for (int f : e) {
                putInt(out, p, f);
                p += 4;
            }
        }
        return out;
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    static void putInt(byte[] b, int p, int v) {
        b[p] = (byte) (v >>> 24);
        b[p + 1] = (byte) (v >>> 16);
        b[p + 2] = (byte) (v >>> 8);
        b[p + 3] = (byte) v;
    }

    static void putLong(byte[] b, int p, long v) {
        putInt(b, p, (int) (v >>> 32));
        putInt(b, p + 4, (int) v);
    }
}

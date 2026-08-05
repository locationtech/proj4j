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

/**
 * The on-disk shape of a {@code .pjdx} index: magic, section kinds, section identifiers, and the
 * primitive encodings. Shared verbatim by the generator and the reader, so the two cannot drift.
 *
 * <h2>Why a purpose-built format rather than the SQLite file</h2>
 * A raw {@code proj.db} inside a jar cannot be opened by JDBC SQLite without a native library and an
 * extraction to a filesystem we do not control. Core must have <strong>zero runtime
 * dependencies</strong>, so both are out. Reading SQLite's b-tree pages in pure Java is possible — 4096
 * byte pages, 2496 of them — but it means implementing somebody else's write-oriented format, carrying
 * its indexes, and inheriting a header whose change-counter fields differ between two runs that produce
 * the same rows.
 * <p>
 * Transcoding instead buys three things that are each worth having on their own:
 * <ul>
 *   <li><strong>Determinism.</strong> Every ordering in the file is a total order over the data, so two
 *       generations from the same input are byte-identical. That is a hard requirement, not a nicety:
 *       this runs in Spark executors that must produce bit-reproducible output.</li>
 *   <li><strong>The bytes we do not need are gone</strong> — the write-path machinery, the b-tree
 *       interior pages, the page slack, and the 798 KB {@code idx_usage_object} whose job is done here
 *       by a 20-byte-per-row sorted array.</li>
 *   <li><strong>Strings are shared.</strong> {@code 'EPSG'} appears in tens of thousands of rows
 *       upstream; here it appears once and is referenced by a varint.</li>
 * </ul>
 *
 * <h2>Layout</h2>
 * All integers are <strong>big-endian</strong>, all offsets absolute unless stated otherwise.
 * <pre>
 * header, 64 bytes
 *   0   char[8]   magic         "PJ4JDBX1"
 *   8   u32       formatVersion 1
 *   12  u32       sectionCount
 *   16  u64       fileLength
 *   24  u64       sectionDirectoryOffset
 *   32  byte[32]  sha256 of bytes [64, fileLength)
 *
 * section directory, sectionCount * 24 bytes, sorted ascending by sectionId
 *   u32 sectionId
 *   u32 kind
 *   u64 offset
 *   u64 length
 * </pre>
 *
 * <h3>{@link #KIND_STRINGS}</h3>
 * <pre>
 *   u32 count
 *   u32 bytesRel            offset of the byte blob, relative to the section
 *   u32[count + 1] offsets  relative to bytesRel; string i is [offsets[i], offsets[i+1])
 *   bytes                   UTF-8, not terminated
 * </pre>
 * String ids are assigned in <strong>ascending unsigned byte order of the UTF-8 encoding</strong>. That
 * single rule does double duty: {@code id -> string} is an array index, and {@code string -> id} is a
 * binary search over the same array, with no second index and no hash table anywhere in the file.
 *
 * <h3>{@link #KIND_TABLE}</h3>
 * <pre>
 *   u32 rowCount
 *   u32 keyFieldCount       each key field a u32 string id (or a small integer). This line used to
 *                           say "0..4"; the shipped proj4j-db.pjdx contains a table with FIVE, and
 *                           the reader deliberately imposes no cap here -- the section length is the
 *                           bound, so a wider key needs no format change and no guard change.
 *   u32 rowOffsetsRel
 *   u32 rowsRel
 *   u32[rowCount * keyFieldCount] keys      row-major, sorted ascending
 *   u32[rowCount + 1] rowOffsets            relative to rowsRel
 *   bytes rows
 * </pre>
 * Rows are sorted by their key tuple, so a lookup is a binary search that touches only the key array
 * and decodes exactly one row. Keys need not be unique: a duplicate key is found by seeking to the
 * first matching entry and scanning forward, which is how the multi-row tables (aliases, supersessions,
 * concatenated-operation steps) are served without a separate index.
 *
 * <h3>{@link #KIND_INDEX}</h3>
 * <pre>
 *   u32 entryCount
 *   u32 fieldCount
 *   u32[entryCount * fieldCount] entries    row-major, sorted ascending
 * </pre>
 *
 * <h2>Field encodings inside a row</h2>
 * <ul>
 *   <li><strong>varint</strong> — unsigned LEB128, 7 bits per byte, low group first.</li>
 *   <li><strong>string</strong> — {@code varint(id + 1)}; {@code 0} means SQL NULL. Biasing by one is
 *       what lets a null cost one byte and keeps id 0 usable.</li>
 *   <li><strong>int</strong> — varint.</li>
 *   <li><strong>bool</strong> — varint 0 or 1.</li>
 *   <li><strong>tri-state bool</strong> — varint 0 = NULL, 1 = false, 2 = true. Upstream really does
 *       distinguish "unknown" from "no" for {@code open_license}, and conflating them would turn
 *       "licence not established" into permission.</li>
 *   <li><strong>double</strong> — a tag byte, then a payload:
 *       {@link #DBL_NULL} nothing (the reader yields NaN),
 *       {@link #DBL_ZERO} nothing,
 *       {@link #DBL_LONG} a zigzag varint whose {@code double} value is exact,
 *       {@link #DBL_RAW} eight big-endian IEEE-754 bytes.
 *       The two compact cases exist because parameter tables are full of exact zeros and exact integers
 *       such as {@code 500000}; they never change a value, only its size.</li>
 *   <li><strong>parameter list</strong> — {@code varint count}, then per parameter: authority string,
 *       code string, name string, value double, unit authority string, unit code string.</li>
 *   <li><strong>grid list</strong> — {@code varint count}, then that many strings.</li>
 * </ul>
 *
 * <h2>NaN, and why the encoding has an explicit null</h2>
 * A missing accuracy, a missing inverse flattening and a missing bounding box are all reported to the
 * caller as {@link Double#NaN}, and each has a dedicated {@link #DBL_NULL} tag rather than being written
 * as a NaN bit pattern. Two reasons: a genuine NaN in the source data would otherwise be
 * indistinguishable from an absent value, and {@code DBL_NULL} costs one byte where a NaN costs nine.
 */
public final class PjdxFormat {

    private PjdxFormat() {
    }

    /** {@code "PJ4JDBX1"}. */
    public static final byte[] MAGIC = {
            (byte) 'P', (byte) 'J', (byte) '4', (byte) 'J',
            (byte) 'D', (byte) 'B', (byte) 'X', (byte) '1'};

    public static final int FORMAT_VERSION = 1;

    public static final int HEADER_BYTES = 64;
    public static final int SHA256_OFFSET = 32;
    public static final int SHA256_BYTES = 32;
    public static final int DIRECTORY_ENTRY_BYTES = 24;

    public static final int KIND_STRINGS = 1;
    public static final int KIND_TABLE = 2;
    public static final int KIND_INDEX = 3;

    // Double tags.
    public static final int DBL_NULL = 0;
    public static final int DBL_ZERO = 1;
    public static final int DBL_LONG = 2;
    public static final int DBL_RAW = 3;

    // ------------------------------------------------------------------ section ids
    //
    // Explicit and never reused. A reader that meets an unknown section id ignores it; a reader that
    // cannot find a section id it needs fails loudly. That is what makes an additive revision possible
    // without a format-version bump, and a subtractive one impossible by accident.

    public static final int S_STRINGS = 1;

    public static final int S_METADATA = 16;
    public static final int S_UNIT = 17;
    public static final int S_CELESTIAL_BODY = 18;
    public static final int S_ELLIPSOID = 19;
    public static final int S_PRIME_MERIDIAN = 20;
    public static final int S_GEODETIC_DATUM = 21;
    public static final int S_VERTICAL_DATUM = 22;
    public static final int S_GEODETIC_ENSEMBLE_MEMBER = 23;
    public static final int S_VERTICAL_ENSEMBLE_MEMBER = 24;
    public static final int S_COORDINATE_SYSTEM = 25;
    public static final int S_AXIS = 26;
    public static final int S_GEODETIC_CRS = 27;
    public static final int S_PROJECTED_CRS = 28;
    public static final int S_VERTICAL_CRS = 29;
    public static final int S_COMPOUND_CRS = 30;
    public static final int S_ENGINEERING_CRS = 31;
    public static final int S_CONVERSION = 32;
    public static final int S_HELMERT_TRANSFORMATION = 33;
    public static final int S_GRID_TRANSFORMATION = 34;
    public static final int S_OTHER_TRANSFORMATION = 35;
    public static final int S_CONCATENATED_OPERATION = 36;
    public static final int S_CONCATENATED_STEP = 37;
    public static final int S_EXTENT = 38;
    public static final int S_GRID_ALTERNATIVE = 39;
    public static final int S_ALIAS = 40;
    public static final int S_SUPERSESSION = 41;
    public static final int S_DEPRECATION = 42;

    public static final int X_CRS_BY_CODE = 64;
    public static final int X_OP_BY_SOURCE_TARGET = 65;
    public static final int X_OP_BY_TARGET_SOURCE = 66;
    public static final int X_USAGE_BY_OBJECT = 67;
    public static final int X_CRS_BY_NAME = 68;
    public static final int X_CRS_BY_DATUM = 69;
    public static final int X_AUTHORITIES = 70;

    // ------------------------------------------------------------------ table tags
    //
    // Written into index entries to say which table a row lives in. Stable, explicit, and deliberately
    // in the same order as DbObjectType so a reader can map either way without a lookup table that
    // could disagree with itself.

    public static final int TAG_UNIT_OF_MEASURE = 0;
    public static final int TAG_CELESTIAL_BODY = 1;
    public static final int TAG_ELLIPSOID = 2;
    public static final int TAG_EXTENT = 3;
    public static final int TAG_PRIME_MERIDIAN = 4;
    public static final int TAG_GEODETIC_DATUM = 5;
    public static final int TAG_VERTICAL_DATUM = 6;
    public static final int TAG_ENGINEERING_DATUM = 7;
    public static final int TAG_GEODETIC_CRS = 8;
    public static final int TAG_PROJECTED_CRS = 9;
    public static final int TAG_VERTICAL_CRS = 10;
    public static final int TAG_COMPOUND_CRS = 11;
    public static final int TAG_ENGINEERING_CRS = 12;
    public static final int TAG_COORDINATE_SYSTEM = 13;
    public static final int TAG_CONVERSION = 14;
    public static final int TAG_GRID_TRANSFORMATION = 15;
    public static final int TAG_HELMERT_TRANSFORMATION = 16;
    public static final int TAG_OTHER_TRANSFORMATION = 17;
    public static final int TAG_CONCATENATED_OPERATION = 18;

    /** Step direction encoding for {@link #S_CONCATENATED_STEP}. */
    public static final int DIRECTION_UNSPECIFIED = 0;
    public static final int DIRECTION_FORWARD = 1;
    public static final int DIRECTION_REVERSE = 2;

    /** The resource name of the shipped index, relative to the resolver's prefix. */
    public static final String RESOURCE_NAME = "proj4j-db.pjdx";

    /** The classpath prefix the shipped index and its sidecar live under. */
    public static final String RESOURCE_PREFIX = "proj4j-data/db";

    /** The build-stamped sidecar, cross-checked against the index's own {@code metadata}. */
    public static final String PROPERTIES_NAME = "db.properties";

    /**
     * Maps a table tag to the {@code DbObjectType} name it corresponds to. Kept as a plain array so the
     * reader does not have to depend on enum {@code values()} order.
     */
    public static final String[] TAG_TABLE_NAMES = {
            "unit_of_measure", "celestial_body", "ellipsoid", "extent", "prime_meridian",
            "geodetic_datum", "vertical_datum", "engineering_datum",
            "geodetic_crs", "projected_crs", "vertical_crs", "compound_crs", "engineering_crs",
            "coordinate_system", "conversion",
            "grid_transformation", "helmert_transformation", "other_transformation",
            "concatenated_operation"};

    /**
     * Normalises a name for the {@link #X_CRS_BY_NAME} index: upper-cased with {@link java.util.Locale#ROOT},
     * with all whitespace, {@code _} and {@code -} removed.
     * <p>
     * Applied identically by the generator and by every query, which is the only way the two can agree.
     * It is deliberately not a fuzzy match: {@code "WGS 84 / UTM zone 31N"} and
     * {@code "wgs_84__utm-zone-31n"} collapse to the same key, but a misspelling does not collapse to
     * anything.
     */
    public static String normalizeName(String name) {
        if (name == null) {
            return null;
        }
        int n = name.length();
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            char c = name.charAt(i);
            if (c == '_' || c == '-' || Character.isWhitespace(c)) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().toUpperCase(java.util.Locale.ROOT);
    }
}

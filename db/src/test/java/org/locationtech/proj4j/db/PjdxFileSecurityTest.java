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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.junit.BeforeClass;
import org.junit.Test;
import org.locationtech.proj4j.datum.GridFormatException;
import org.locationtech.proj4j.resource.ByteArrayByteReader;
import org.locationtech.proj4j.resource.SeekableByteReader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Integer-overflow guards in the {@code .pjdx} reader.
 *
 * <h2>What was unguarded</h2>
 *
 * <p>Three allocations sized themselves from numbers read straight out of the file:
 *
 * <ul>
 *   <li><strong>the string pool.</strong> {@code stringCount} was checked for {@code < 0} and nothing
 *       else. {@code (stringCount + 1) * 4} wraps negative at 2<sup>29</sup> — a count of 536,870,912
 *       makes that product {@code -2147483644} — so {@code new byte[...]} threw
 *       {@code NegativeArraySizeException} without naming the file, while
 *       {@code new int[stringCount + 1]} two lines later still asked for 2 GB.</li>
 *   <li><strong>{@code Table}.</strong> Validated nothing. {@code rowCount * keyFieldCount * 4} is
 *       three unchecked multiplications, and {@code (rowCount + 1) * 4} wraps at the same
 *       2<sup>29</sup>.</li>
 *   <li><strong>{@code Index}.</strong> Validated nothing. Same shape, one dimension fewer.</li>
 * </ul>
 *
 * <p>Each is now checked against the length of the <em>section that declares it</em> — not a magic
 * constant — so a section cannot claim more rows than it has room to describe.
 *
 * <h2>The bound is the section, not a number someone chose</h2>
 *
 * <p>This matters more here than in the grid readers, and the shipped file proves why twice. Its
 * string pool holds <strong>97,930</strong> strings, so a 100,000 cap borrowed from
 * {@code CTABLEV2}'s grid-axis check would have passed today and refused the library's own database
 * after one EPSG release. And one of its tables has {@code keyFieldCount = 5} while
 * {@code PjdxFormat}'s own javadoc said <em>"0..4"</em> — a cap taken from the documentation would
 * have refused the shipped file immediately. Both numbers are measured from
 * {@code proj4j-db.pjdx} and pinned in {@link #theShippedDatabaseIsInsideEveryBound}.
 */
public class PjdxFileSecurityTest {

    private static byte[] shipped;

    @BeforeClass
    public static void readShippedIndex() throws IOException {
        InputStream in = PjdxFileSecurityTest.class.getResourceAsStream(
                "/" + PjdxFormat.RESOURCE_PREFIX + "/" + PjdxFormat.RESOURCE_NAME);
        assertNotNull("proj4j-db.pjdx must be on the test classpath", in);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            shipped = out.toByteArray();
        } finally {
            in.close();
        }
    }

    /**
     * The real file with one field overwritten.
     *
     * <p>Mutating the shipped index rather than synthesising a file is deliberate: the reader checks
     * the declared length against the actual length, cross-checks the section directory, and can
     * verify a SHA-256, so a hand-built stub would be refused for a reason that has nothing to do with
     * the guard under test. Patching in place keeps every one of those checks satisfied and leaves
     * exactly one thing wrong. The checksum is skipped, which is the same choice
     * {@code Proj4jDb.open} makes by default.
     */
    private static PjdxFile openPatched(int absoluteOffset, int newValue) throws IOException {
        final byte[] copy = shipped.clone();
        copy[absoluteOffset] = (byte) (newValue >>> 24);
        copy[absoluteOffset + 1] = (byte) (newValue >>> 16);
        copy[absoluteOffset + 2] = (byte) (newValue >>> 8);
        copy[absoluteOffset + 3] = (byte) newValue;
        return new PjdxFile("patched-pjdx", new PjdxFile.ReaderSource() {
            @Override
            public SeekableByteReader open() {
                return new ByteArrayByteReader(copy);
            }
        }, false);
    }

    private static PjdxFile openShipped() throws IOException {
        return new PjdxFile("shipped-pjdx", new PjdxFile.ReaderSource() {
            @Override
            public SeekableByteReader open() {
                return new ByteArrayByteReader(shipped);
            }
        }, false);
    }

    /** Absolute offset of the first byte of the section with this id. */
    private static long sectionOffset(int sectionId) {
        int sectionCount = beInt(shipped, 12);
        long dirOffset = beLong(shipped, 24);
        for (int i = 0; i < sectionCount; i++) {
            int p = (int) dirOffset + i * PjdxFormat.DIRECTORY_ENTRY_BYTES;
            if (beInt(shipped, p) == sectionId) {
                return beLong(shipped, p + 8);
            }
        }
        throw new IllegalStateException("no section " + sectionId);
    }

    private static long sectionLength(int sectionId) {
        int sectionCount = beInt(shipped, 12);
        long dirOffset = beLong(shipped, 24);
        for (int i = 0; i < sectionCount; i++) {
            int p = (int) dirOffset + i * PjdxFormat.DIRECTORY_ENTRY_BYTES;
            if (beInt(shipped, p) == sectionId) {
                return beLong(shipped, p + 16);
            }
        }
        throw new IllegalStateException("no section " + sectionId);
    }

    private static int beInt(byte[] b, int p) {
        return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8)
                | (b[p + 3] & 0xFF);
    }

    private static long beLong(byte[] b, int p) {
        return ((long) beInt(b, p) << 32) | (beInt(b, p + 4) & 0xFFFFFFFFL);
    }

    // ------------------------------------------------------------------ the string pool

    /**
     * {@code stringCount = 2}<sup>{@code 29}</sup>: the exact value at which {@code (n + 1) * 4}
     * crosses {@code Integer.MAX_VALUE} and wraps negative.
     */
    @Test
    public void aStringCountThatWrapsTheOffsetArrayIsRefused() throws IOException {
        assertEquals("the wrap this refuses", -2147483644, (1 << 29) + 1 << 2);
        try {
            openPatched((int) sectionOffset(PjdxFormat.S_STRINGS), 1 << 29).close();
            fail("a string pool declaring 2^29 strings must be refused");
        } catch (GridFormatException expected) {
            assertTrue("must name the count and the section: " + expected.getMessage(),
                    expected.getMessage().contains("536870913")
                            && expected.getMessage().contains("string-pool section length"));
        }
    }

    /** A count that does not wrap but still cannot fit its own section. */
    @Test
    public void aStringCountLargerThanItsSectionIsRefused() throws IOException {
        long section = sectionLength(PjdxFormat.S_STRINGS);
        int tooMany = (int) (section / 4) + 1;
        try {
            openPatched((int) sectionOffset(PjdxFormat.S_STRINGS), tooMany).close();
            fail(tooMany + " strings cannot fit a " + section + "-byte section");
        } catch (GridFormatException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("exceeding"));
        }
    }

    @Test
    public void aNegativeStringCountIsStillRefused() throws IOException {
        try {
            openPatched((int) sectionOffset(PjdxFormat.S_STRINGS), -1).close();
            fail("a negative string count must be refused");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("implausible"));
        }
    }

    // ------------------------------------------------------------------ tables

    /** {@code rowCount = 2}<sup>{@code 29}</sup> in a table header: the row-offset array wraps. */
    @Test
    public void aTableRowCountThatWrapsIsRefused() throws IOException {
        PjdxFile f = openPatched((int) sectionOffset(PjdxFormat.S_GEODETIC_CRS), 1 << 29);
        try {
            f.table(PjdxFormat.S_GEODETIC_CRS);
            fail("a table declaring 2^29 rows must be refused");
        } catch (java.io.UncheckedIOException e) {
            assertTrue("must be the named guard, not a NegativeArraySizeException: " + e.getCause(),
                    e.getCause() instanceof GridFormatException);
            assertTrue(e.getCause().getMessage(),
                    e.getCause().getMessage().contains("the section length"));
        } finally {
            f.close();
        }
    }

    /** {@code keyFieldCount} large enough that {@code rowCount * keyFieldCount * 4} overflows. */
    @Test
    public void aTableKeyFieldCountThatWrapsIsRefused() throws IOException {
        PjdxFile f = openPatched((int) sectionOffset(PjdxFormat.S_GEODETIC_CRS) + 4, 1 << 20);
        try {
            f.table(PjdxFormat.S_GEODETIC_CRS);
            fail("a table declaring 2^20 key fields per row must be refused");
        } catch (java.io.UncheckedIOException e) {
            assertTrue("must be the named guard: " + e.getCause(),
                    e.getCause() instanceof GridFormatException);
        } finally {
            f.close();
        }
    }

    // ------------------------------------------------------------------ indexes

    @Test
    public void anIndexEntryCountThatWrapsIsRefused() throws IOException {
        PjdxFile f = openPatched((int) sectionOffset(PjdxFormat.X_CRS_BY_CODE), 1 << 29);
        try {
            f.index(PjdxFormat.X_CRS_BY_CODE);
            fail("an index declaring 2^29 entries must be refused");
        } catch (java.io.UncheckedIOException e) {
            assertTrue("must be the named guard, not an OutOfMemoryError: " + e.getCause(),
                    e.getCause() instanceof GridFormatException);
        } finally {
            f.close();
        }
    }

    @Test
    public void anIndexFieldCountThatWrapsIsRefused() throws IOException {
        PjdxFile f = openPatched((int) sectionOffset(PjdxFormat.X_CRS_BY_CODE) + 4, 1 << 24);
        try {
            f.index(PjdxFormat.X_CRS_BY_CODE);
            fail("an index declaring 2^24 fields per entry must be refused");
        } catch (java.io.UncheckedIOException e) {
            assertTrue("must be the named guard: " + e.getCause(),
                    e.getCause() instanceof GridFormatException);
        } finally {
            f.close();
        }
    }

    // ------------------------------------------------------------------ positive controls

    /**
     * <strong>Positive control.</strong> The unpatched shipped file must open and answer, through the
     * same constructor every rejection above goes through. Without this, every guard could be
     * "reject everything" and the suite would still be green.
     */
    @Test
    public void theShippedIndexStillOpensAndAnswers() throws IOException {
        PjdxFile f = openShipped();
        try {
            assertEquals(97930, f.stringCount());
            assertTrue("string 0 must decode", f.string(0) != null);
            assertTrue("the last string must decode", f.string(f.stringCount() - 1) != null);

            PjdxFile.Table crs = f.table(PjdxFormat.S_GEODETIC_CRS);
            assertTrue("the geodetic CRS table must have rows", crs.rowCount > 0);
            assertNotNull("and a row must decode", crs.row(0));

            PjdxFile.Index byCode = f.index(PjdxFormat.X_CRS_BY_CODE);
            assertTrue("the code index must have entries", byCode.entryCount > 0);

            // Every table and every index in the file, so a guard that only happens to admit the
            // two probed above still fails here.
            int tables = 0;
            int indexes = 0;
            for (int id : f.sectionIdsForDiagnostics()) {
                if (id == PjdxFormat.S_STRINGS) {
                    continue;
                }
                try {
                    f.table(id);
                    tables++;
                } catch (IllegalStateException notATable) {
                    f.index(id);
                    indexes++;
                }
            }
            assertEquals("every table section must load", 27, tables);
            assertEquals("every index section must load", 7, indexes);
        } finally {
            f.close();
        }
    }

    /**
     * The two numbers that would have made a plausible-looking cap into a broken library, pinned so a
     * future tightening has to argue with them.
     */
    @Test
    public void theShippedDatabaseIsInsideEveryBound() throws IOException {
        PjdxFile f = openShipped();
        try {
            assertEquals("the string pool is just under a 100,000 cap, by accident", 97930,
                    f.stringCount());

            int widestKey = 0;
            int mostRows = 0;
            for (int id : f.sectionIdsForDiagnostics()) {
                if (id == PjdxFormat.S_STRINGS) {
                    continue;
                }
                try {
                    PjdxFile.Table t = f.table(id);
                    widestKey = Math.max(widestKey, t.keyFieldCount);
                    mostRows = Math.max(mostRows, t.rowCount);
                } catch (IllegalStateException notATable) {
                    // an index section; not a table key
                }
            }
            assertEquals("PjdxFormat's javadoc said 0..4; the shipped file uses 5", 5, widestKey);
            assertEquals(19103, mostRows);
        } finally {
            f.close();
        }
    }

    /**
     * <strong>Positive control for the whole stack.</strong> The public entry point must still answer
     * a real query, so a guard that admits the file but corrupts what is read out of it is caught too.
     */
    @Test
    public void theDatabaseStillAnswersARealQuery() throws IOException {
        org.locationtech.proj4j.spi.ProjDatabase db =
                Proj4jDb.open(PjdxFileSecurityTest.class.getClassLoader());
        assertNotNull(db);
        try {
            assertEquals("9.8.1", db.metadata().get("PROJ.VERSION"));
            assertNotNull("EPSG:4326 must resolve", db.crs("EPSG", "4326"));
        } finally {
            db.close();
        }
    }
}

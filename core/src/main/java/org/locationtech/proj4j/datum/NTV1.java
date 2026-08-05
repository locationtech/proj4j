/*******************************************************************************
 * Copyright 2009, 2017 Martin Davis
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
 */
package org.locationtech.proj4j.datum;

import static org.locationtech.proj4j.util.ProjectionMath.DTR;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.locationtech.proj4j.util.FloatPolarCoordinate;
import org.locationtech.proj4j.util.IntPolarCoordinate;
import org.locationtech.proj4j.util.PolarCoordinate;

public final class NTV1 {

    private static final byte[] magic1 = "HEADER".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] magic2 = "W GRID".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] magic3 = "TO      NAD83   ".getBytes(StandardCharsets.US_ASCII);

    public static boolean testHeader(byte[] header) {
        return containsAt(magic1, header, 0) &&
                containsAt(magic2, header, 96) &&
                containsAt(magic3, header, 144);
    }

    /** Each node is two big-endian {@code double}s: a latitude shift and a longitude shift. */
    private static final int BYTES_PER_NODE = 16;

    public static Grid.ConversionTable init(DataInputStream definition) throws IOException {
        byte[] header = new byte[160];
        definition.readFully(header);
        // Was `throw new Error(...)` on both of these. An Error escapes catch (Proj4jException) AND
        // catch (Exception); both are reachable from a +nadgrids= token, which is untrusted per-row
        // input, so neither may leave the IOException family.
        if (!testHeader(header)) {
            throw new GridFormatException("Not a NTV1 file");
        }

        // Minimal validation to detect corrupt structure
        int recordCount = intFromBytes(header, 8);
        if (recordCount != 12) {
            throw new GridFormatException(String.format(
                    "NTv1 grid shift file has wrong record count, corrupt? $0%08X $0", recordCount));
        }

        Grid.ConversionTable table = new Grid.ConversionTable();
        table.id = "NTv1 Grid Shift File";
        table.ll = new PolarCoordinate(-doubleFromBytes(header, 72), doubleFromBytes(header, 24));
        PolarCoordinate ur = new PolarCoordinate(-doubleFromBytes(header, 56), doubleFromBytes(header, 40));
        table.del = new PolarCoordinate(doubleFromBytes(header, 104), doubleFromBytes(header, 88));
        // This header carried NO validation at all. Measured on the code this replaces: a zero
        // LAT_INC made the quotient infinite, `(int)` saturated to MAX_VALUE and the `+ 1` wrapped to
        // Integer.MIN_VALUE; a zero span with a zero increment made it NaN, `(int) NaN` is 0, and the
        // reader built a plausible 1x1 grid out of a header describing nothing and reported success.
        table.lim = new IntPolarCoordinate(
                GridExtents.checkedAxis("NTv1 grid shift file", "longitude",
                        Math.abs(ur.lam - table.ll.lam) / table.del.lam + 0.5),
                GridExtents.checkedAxis("NTv1 grid shift file", "latitude",
                        Math.abs(ur.phi - table.ll.phi) / table.del.phi + 0.5));
        long haveBytes = GridExtents.remaining(definition);
        // init() has consumed 160 of the 192 header bytes, so what remains still holds the last two
        // 16-byte records before the nodes begin.
        GridExtents.checkedCount("NTv1 grid shift file", table.lim.lam, table.lim.phi, BYTES_PER_NODE,
                DATA_OFFSET - 160L,
                haveBytes >= 0 ? haveBytes : GridExtents.maxFileBytes(),
                haveBytes >= 0 ? "the remaining file" : "the grid-file ceiling");
        table.ll.lam *= DTR;
        table.ll.phi *= DTR;
        table.del.lam *= DTR;
        table.del.phi *= DTR;
        return table;
    }

    /**
     * Byte offset of the first grid-shift record.
     * <p>
     * <strong>192, not 176.</strong> An NTv1 header is twelve 16-byte records
     * ({@code HEADER NUM_OREC NUM_SREC NUM_FILE S_LAT N_LAT E_LONG W_LONG N_GRID W_GRID TYPE FROM TO
     * ...}), and PROJ 9.8.1 seeks {@code 192 + 16 * index} ({@code src/grids.cpp},
     * {@code NTv1Grid::valueAt}). Proj4J 1.4.3 skipped 176, which is exactly one 16-byte node short,
     * so every interpolated value came from the node one column east of the correct one -- a
     * plausible, finite, roughly 0.25&deg;-displaced answer. The arithmetic also proves 192: the
     * shipped {@code ntv1_can.dat} is 1,113,184 bytes = 192 header + 393&times;177&times;16 node bytes
     * + one 16-byte trailing {@code END} record, which 176 cannot account for.
     */
    private static final int DATA_OFFSET = 192;

    public static void load(DataInputStream definition, Grid grid) throws IOException {
        GridExtents.skipFully(definition, DATA_OFFSET, "NTv1 grid shift file");
        // Re-checked rather than trusted from init(): load() is public, takes its own stream, and
        // reads grid.table -- which a caller, not init(), may have populated. Without this,
        // `lim.lam * lim.phi` is an unchecked int product and `lim.lam * 2` overflows on its own.
        long haveBytes = GridExtents.remaining(definition);
        int nodeCount = GridExtents.checkedCount("NTv1 grid shift file",
                grid.table.lim.lam, grid.table.lim.phi, BYTES_PER_NODE, 0L,
                haveBytes >= 0 ? haveBytes : GridExtents.maxFileBytes(),
                haveBytes >= 0 ? "the remaining file" : "the grid-file ceiling");
        double[] row_buff = new double[grid.table.lim.lam * 2];
        FloatPolarCoordinate[] tmp_cvs = new FloatPolarCoordinate[nodeCount];

        for (int row = 0; row < grid.table.lim.phi; row++) {
            byte[] byteBuff = new byte[8 * row_buff.length];
            definition.readFully(byteBuff);
            ByteBuffer.wrap(byteBuff).order(ByteOrder.BIG_ENDIAN).asDoubleBuffer().get(row_buff);
            for (int i = 0; i < grid.table.lim.lam; i++) {
                // Each record is (latitude shift, longitude shift), both in arc seconds, and NTv1 is
                // organised east to west -- hence the reversed column index.
                //
                // 1.4.3 assigned row_buff[2i] (the LATITUDE shift) to .lam and row_buff[2i+1] (the
                // LONGITUDE shift) to .phi, i.e. the two components were transposed. PROJ 9.8.1's
                // NTv1Grid::valueAt is unambiguous: latShift = two_doubles[0],
                // longShift = -two_doubles[1]. The sign difference is proj4j's opposite convention --
                // Grid.nad_cvt does `in.lam -= t.lam` where PROJ does `in.lam += t.lam` -- so the
                // unnegated longitude shift belongs in .lam. Both errors were small enough
                // individually to look like a plausible coordinate; together they cost ~13 m at
                // Chicago.
                tmp_cvs[row * grid.table.lim.lam + grid.table.lim.lam - i - 1] =
                        new FloatPolarCoordinate(
                                (float) (row_buff[2 * i + 1] * Math.PI / 180.0 / 3600.0),
                                (float) (row_buff[2 * i] * Math.PI / 180.0 / 3600.0));
            }
        }
        grid.table.cvs = tmp_cvs;

    }

    private static boolean containsAt(byte[] needle, byte[] haystack, int offset) {
        if (needle == null || haystack == null) return false;

        int maxoffset = Math.min(needle.length - 1, haystack.length - offset - 1);
        for (int i = 0; i < maxoffset; i++) {
            if (needle[i] != haystack[offset + i]) return false;
        }

        return true;
    }

    private static double doubleFromBytes(byte[] b, int offset) {
        return ByteBuffer.wrap(b, offset, 8).order(ByteOrder.BIG_ENDIAN).getDouble();
    }

    private static int intFromBytes(byte[] b, int offset) {
        return ByteBuffer.wrap(b, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }
}

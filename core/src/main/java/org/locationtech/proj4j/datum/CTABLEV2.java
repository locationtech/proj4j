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

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.locationtech.proj4j.util.FloatPolarCoordinate;
import org.locationtech.proj4j.util.IntPolarCoordinate;
import org.locationtech.proj4j.util.PolarCoordinate;

public final class CTABLEV2 {

    private static final byte[] magic = "CTABLE V2".getBytes(StandardCharsets.US_ASCII);

    /** The fixed header, and therefore the offset of the first node. */
    private static final int HEADER_BYTES = 160;

    /** Each node is two little-endian {@code float}s: a longitude shift and a latitude shift. */
    private static final int BYTES_PER_NODE = 8;

    public static boolean testHeader(byte[] header) {
        return containsAt(magic, header, 0);
    }

    public static Grid.ConversionTable init(DataInputStream definition) throws IOException {
        byte[] header = new byte[HEADER_BYTES];
        definition.readFully(header);
        if (!containsAt(magic, header, 0)) {
            // Was `throw new Error(...)`. An Error escapes catch (Proj4jException) AND catch
            // (Exception), so a file that merely failed a magic test could take down a Spark task
            // rather than being reported as an unreadable grid. Measured: the old code escaped a
            // catch (Exception) placed directly around this call.
            throw new GridFormatException("Not a CTABLE V2 file");
        }
        byte[] id_bytes = Arrays.copyOfRange(header, 16, 16 + 80);
        PolarCoordinate ll = new PolarCoordinate(doubleFromBytes(header, 96), doubleFromBytes(header, 104));
        PolarCoordinate del = new PolarCoordinate(doubleFromBytes(header, 112), doubleFromBytes(header, 120));
        int columns = intFromBytes(header, 128);
        int rows = intFromBytes(header, 132);

        // The axis bounds alone were never enough: 100000 x 100000 passed them and then multiplied to
        // 1,410,065,408 -- 5.25 GiB of references demanded by these 160 bytes. The node count is now
        // checked against the bytes that actually follow the header.
        long haveBytes = GridExtents.remaining(definition);
        long limitBytes = haveBytes >= 0 ? HEADER_BYTES + haveBytes : GridExtents.maxFileBytes();
        GridExtents.checkedCount("CTABLE V2 grid", columns, rows, BYTES_PER_NODE, HEADER_BYTES,
                limitBytes, haveBytes >= 0 ? "the file" : "the grid-file ceiling");

        int nullPosition = 0;
        while (nullPosition < id_bytes.length && id_bytes[nullPosition] != 0) nullPosition++;
        Grid.ConversionTable table = new Grid.ConversionTable();
        table.id = new String(id_bytes, 0, nullPosition, StandardCharsets.US_ASCII).trim();
        table.ll = ll;
        table.del = del;
        table.lim = new IntPolarCoordinate(columns, rows);
        return table;
    }

    public static void load(DataInputStream definition, Grid grid) throws IOException {
        Grid.ConversionTable table = grid.table;
        GridExtents.skipFully(definition, HEADER_BYTES, "CTABLE V2 grid " + table.id);
        // Re-checked here rather than trusted from init(): load() is public, takes its own stream, and
        // reads grid.table -- which a caller, not init(), may have populated.
        long haveBytes = GridExtents.remaining(definition);
        int entryCount = GridExtents.checkedCount("CTABLE V2 grid " + table.id,
                table.lim.lam, table.lim.phi, BYTES_PER_NODE, 0L,
                haveBytes >= 0 ? haveBytes : GridExtents.maxFileBytes(),
                haveBytes >= 0 ? "the remaining file" : "the grid-file ceiling");
        FloatPolarCoordinate[] cvs = new FloatPolarCoordinate[entryCount];
        byte[] buff = new byte[BYTES_PER_NODE];
        for (int i = 0; i < entryCount; i++) {
            definition.readFully(buff);
            cvs[i] = new FloatPolarCoordinate(floatFromBytes(buff, 0), floatFromBytes(buff, 4));
        }
        table.cvs = cvs;
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
        return ByteBuffer.wrap(b, offset, 8).order(ByteOrder.LITTLE_ENDIAN).getDouble();
    }

    private static int intFromBytes(byte[] b, int offset) {
        return ByteBuffer.wrap(b, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static float floatFromBytes(byte[] b, int offset) {
        return ByteBuffer.wrap(b, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
    }
}

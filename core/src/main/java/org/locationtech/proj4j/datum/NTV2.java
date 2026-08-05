/*******************************************************************************
 * Copyright 2023 FPS BOSA
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.proj4j.util.FloatPolarCoordinate;
import org.locationtech.proj4j.util.IntPolarCoordinate;
import org.locationtech.proj4j.util.PolarCoordinate;

/**
 * Parser for the "National Transformation" v2 format (<code>.gsb</code>).
 *
 * <p>Supports <strong>multiple subgrids</strong> and arbitrarily deep parent/child hierarchies, which
 * is the normal case for real NTv2 files: {@code ca_nrc_ntv2_0.gsb} has four regional grids plus three
 * high-resolution children, and {@code uk_os_OSTN15_NTv2_OSGBtoETRS.gsb} likewise. Proj4J 1.4.3 read
 * only the first subgrid and silently used it for the whole file, so any point covered by a later
 * subgrid got either the wrong (coarse parent) shift or, more often, no shift at all while the
 * transform still reported success.
 *
 * <p>Only {@code GS_TYPE = SECONDS} is supported, as in PROJ 9.8.1; anything else is rejected rather
 * than silently mis-scaled.
 *
 * <p>Header structure:
 * <pre>
 * 0        8        16
 * |NUM_OREC|iiii    |
 * |NUM_SREC|iiii    |
 * |NUM_FILE|iiii    |
 * |GS_TYPE |ssssssss|
 * |VERSION |ssssssss|
 * |SYSTEM_F|ssssssss|
 * |SYSTEM_T|ssssssss|
 * |MAJOR_F |dddddddd|
 * |MINOR_F |dddddddd|
 * |MAJOR_T |dddddddd|
 * |MINOR_T |dddddddd|
 * </pre>
 *
 * Subfile header:
 * <pre>
 * |SUB_NAME|ssssssss|
 * |PARENT  |ssssssss|
 * |CREATED |ssssssss|
 * |UPDATED |ssssssss|
 * |S_LAT   |dddddddd|
 * |N_LAT   |dddddddd|
 * |E_LONG  |dddddddd|
 * |W_LONG  |dddddddd|
 * |LAT_INC |dddddddd|
 * |LONG_INC|dddddddd|
 * |GS_COUNT|iiii    |
 * </pre>
 *
 * Grid shift records
 * <pre>
 * |dddd|dddd|dddd|dddd|
 * </pre>
 *
 * End of File record
 * <pre>
 * |END     |dddddddd|
 * </pre>
 *
 * @author Bart Hanssens
 */
public final class NTV2 {

    private static final byte[] MAGIC = "NUM_OREC".getBytes(StandardCharsets.US_ASCII);

    private static final double SEC_RAD = Math.PI / 180 / 3600;

    private static final int HEADER_SIZE = 176;
    private static final int SUB_HEADER_SIZE = 176;
    private static final int VALUES_PER_CELL = 4;
    private static final int BYTES_PER_CELL = VALUES_PER_CELL * 4;

    private static final int NUM_OREC = 8;
    private static final int NUM_FILE = 40;
    private static final int GS_TYPE = 56;

    private static final int SUB_NAME = 8;
    private static final int PARENT = 24;
    private static final int S_LAT = 72;
    private static final int N_LAT = 88;
    private static final int E_LONG = 104;
    private static final int W_LONG = 120;

    private static final int LAT_INC = 136;
    private static final int LONG_INC = 152;
    private static final int GS_COUNT = 168;

    /**
     * Use header to check file type
     *
     * @param header
     * @return true if format is NTv2
     */
    public static boolean testHeader(byte[] header) {
        if (header == null || header.length < MAGIC.length) {
            return false;
        }
        byte[] start = Arrays.copyOfRange(header, 0, MAGIC.length);
        return Arrays.equals(start, MAGIC);
    }

    /**
     * Reads every subgrid in an NTv2 file and wires them into the parent/child hierarchy that
     * {@link Grid#shift} descends.
     *
     * <p>Single-subgrid files are represented exactly as before -- {@code grid.table} holds the one
     * subgrid, with no children -- so the common case is bit-for-bit unchanged. Multi-subgrid files get
     * a synthetic bounding parent whose {@code cvs} is {@code null}: it exists only so the extent
     * pre-filter in {@code Grid.shift} admits the file, and because its node array is null it can
     * never itself supply a shift.
     */
    static void loadAll(byte[] bytes, Grid grid) throws IOException {
        loadAll(bytes, grid, grid.getGridName(), grid.getOrigin(), grid.getResolverName());
    }

    static void loadAll(byte[] bytes, Grid grid, String fileGridName, String origin,
                        String resolverName) throws IOException {
        if (bytes.length < HEADER_SIZE) {
            throw new IOException("NTv2 file is shorter than its 176-byte overview header");
        }
        if (!testHeader(bytes)) {
            throw new IOException("Not a NTv2 file");
        }
        ByteOrder endian = guessByteOrder(bytes);

        // PROJ 9.8.1 rejects anything but SECONDS (grids.cpp, NTv2GridSet::open). 1.4.3 assumed it.
        String gsType = new String(bytes, GS_TYPE, 8, StandardCharsets.US_ASCII).trim();
        if (!"SECONDS".equals(gsType)) {
            throw new IOException("NTv2 file declares GS_TYPE='" + gsType
                    + "'; only SECONDS is supported");
        }

        int subFileCount = intFromBytes(bytes, NUM_FILE, endian);
        if (subFileCount < 1 || subFileCount > 100000) {
            throw new IOException("NTv2 file declares an implausible NUM_FILE of " + subFileCount);
        }

        List<Grid> roots = new ArrayList<Grid>();
        Map<String, Grid> byName = new HashMap<String, Grid>();

        int offset = HEADER_SIZE;
        for (int sub = 0; sub < subFileCount; sub++) {
            if (offset + SUB_HEADER_SIZE > bytes.length) {
                throw new IOException("NTv2 file truncated before subgrid " + sub + " header");
            }
            String subName = new String(bytes, offset + SUB_NAME, 8, StandardCharsets.US_ASCII).trim();
            String parentName = new String(bytes, offset + PARENT, 8, StandardCharsets.US_ASCII).trim();

            Grid.ConversionTable table = subHeader(bytes, offset, endian,
                    subName.isEmpty() ? "NTv2 Grid Shift File" : "NTv2 Grid Shift File: " + subName);
            int cells = intFromBytes(bytes, offset + GS_COUNT, endian);
            // `lim.lam * lim.phi` was an unchecked int product. A subgrid header with a tiny increment
            // saturated both axes to Integer.MIN_VALUE (see GridExtents.checkedAxis, now applied in
            // subHeader), whose product wraps positive; a matching GS_COUNT then walked straight past
            // the equality test below, and the `(long) cells * BYTES_PER_CELL` truncation check below
            // is satisfied by any negative `cells`.
            int expected = GridExtents.checkedCount(
                    "NTv2 subgrid " + (subName.isEmpty() ? String.valueOf(sub) : subName),
                    table.lim.lam, table.lim.phi, BYTES_PER_CELL,
                    (long) offset + SUB_HEADER_SIZE, bytes.length, "the file");
            if (cells != expected) {
                throw new IOException("NTv2 subgrid " + subName + " declares GS_COUNT=" + cells
                        + " but its extent and increments imply " + table.lim.lam + "x"
                        + table.lim.phi + " = " + expected + " cells");
            }
            int dataOffset = offset + SUB_HEADER_SIZE;
            long dataLength = (long) cells * BYTES_PER_CELL;
            if (dataOffset + dataLength > bytes.length) {
                throw new IOException("NTv2 file truncated inside subgrid " + subName);
            }
            table.cvs = readCells(bytes, dataOffset, table.lim.lam, table.lim.phi, endian);

            Grid subGrid = new Grid();
            subGrid.table = table;
            subGrid.describeAs(fileGridName + "#" + (subName.isEmpty() ? String.valueOf(sub) : subName),
                    "ntv2", origin, resolverName);
            Grid parent = parentName.isEmpty() || "NONE".equals(parentName) ? null : byName.get(parentName);
            if (parent == null) {
                roots.add(subGrid);
            } else {
                appendChild(parent, subGrid);
            }
            if (!subName.isEmpty()) {
                byName.put(subName, subGrid);
            }

            offset = (int) (dataOffset + dataLength);
        }

        if (roots.isEmpty()) {
            throw new IOException("NTv2 file declares " + subFileCount
                    + " subgrid(s) but none of them is a top-level grid");
        }

        if (roots.size() == 1 && roots.get(0).getChild() == null) {
            // Exactly the 1.4.3 shape for a single-subgrid file.
            grid.table = roots.get(0).table;
            grid.setChild(null);
            return;
        }

        grid.table = boundingTable(roots);
        Grid previous = null;
        for (Grid root : roots) {
            if (previous == null) {
                grid.setChild(root);
            } else {
                previous.setNext(root);
            }
            previous = root;
        }
    }

    private static void appendChild(Grid parent, Grid child) {
        Grid existing = parent.getChild();
        if (existing == null) {
            parent.setChild(child);
            return;
        }
        while (existing.getNext() != null) {
            existing = existing.getNext();
        }
        existing.setNext(child);
    }

    /**
     * A synthetic table spanning every root subgrid, with a null node array. Only the extent and the
     * cell size are meaningful; the cell size is the finest of the roots so the derived {@code lim}
     * never under-covers the union.
     */
    private static Grid.ConversionTable boundingTable(List<Grid> roots) throws IOException {
        double west = Double.POSITIVE_INFINITY;
        double south = Double.POSITIVE_INFINITY;
        double east = Double.NEGATIVE_INFINITY;
        double north = Double.NEGATIVE_INFINITY;
        double delLam = Double.POSITIVE_INFINITY;
        double delPhi = Double.POSITIVE_INFINITY;
        for (Grid root : roots) {
            double[] e = root.extentRadians();
            west = Math.min(west, e[0]);
            south = Math.min(south, e[1]);
            east = Math.max(east, e[2]);
            north = Math.max(north, e[3]);
            delLam = Math.min(delLam, Math.abs(root.table.del.lam));
            delPhi = Math.min(delPhi, Math.abs(root.table.del.phi));
        }
        Grid.ConversionTable bounds = new Grid.ConversionTable();
        bounds.id = "NTv2 Grid Shift File (bounding box of " + roots.size() + " root subgrids)";
        bounds.ll = new PolarCoordinate(west, south);
        bounds.del = new PolarCoordinate(delLam, delPhi);
        // The increments here are the *finest* of the roots while the extent is the *union*, so the
        // quotient can exceed anything a single subgrid could -- this is the one place a set of
        // individually valid subgrids can still produce a wrapped extent.
        bounds.lim = new IntPolarCoordinate(
                GridExtents.checkedAxis(bounds.id, "longitude", Math.ceil((east - west) / delLam)),
                GridExtents.checkedAxis(bounds.id, "latitude", Math.ceil((north - south) / delPhi)));
        bounds.cvs = null;
        return bounds;
    }

    private static Grid.ConversionTable subHeader(byte[] b, int off, ByteOrder endian, String id)
            throws IOException {
        Grid.ConversionTable table = new Grid.ConversionTable();
        table.id = id;
        // lower left
        table.ll = new PolarCoordinate(-doubleFromBytes(b, off + W_LONG, endian) * SEC_RAD,
                doubleFromBytes(b, off + S_LAT, endian) * SEC_RAD);
        // upper right
        PolarCoordinate ur = new PolarCoordinate(-doubleFromBytes(b, off + E_LONG, endian) * SEC_RAD,
                doubleFromBytes(b, off + N_LAT, endian) * SEC_RAD);
        table.del = new PolarCoordinate(doubleFromBytes(b, off + LONG_INC, endian) * SEC_RAD,
                doubleFromBytes(b, off + LAT_INC, endian) * SEC_RAD);
        // Same saturating-cast defect as NTv1's, in the same expression: a zero or denormal increment
        // makes the quotient infinite, `(int)` saturates and the `+ 1` wraps negative.
        table.lim = new IntPolarCoordinate(
                GridExtents.checkedAxis(id, "longitude",
                        Math.abs(ur.lam - table.ll.lam) / table.del.lam + 0.5),
                GridExtents.checkedAxis(id, "latitude",
                        Math.abs(ur.phi - table.ll.phi) / table.del.phi + 0.5));
        return table;
    }

    private static FloatPolarCoordinate[] readCells(byte[] b, int dataOffset, int cols, int rows,
                                                    ByteOrder endian) {
        FloatPolarCoordinate[] cvs = new FloatPolarCoordinate[cols * rows];
        float[] rowBuff = new float[cols * VALUES_PER_CELL];
        for (int row = 0; row < rows; row++) {
            int rowOffset = dataOffset + row * cols * BYTES_PER_CELL;
            ByteBuffer.wrap(b, rowOffset, cols * BYTES_PER_CELL).order(endian).asFloatBuffer().get(rowBuff);
            for (int col = 0; col < cols; col++) {
                // Record layout is (lat shift, long shift, lat accuracy, long accuracy); the
                // accuracies are discarded. NTv2 is organised from east to west, hence the reversed
                // column index.
                cvs[row * cols + (cols - col - 1)] = new FloatPolarCoordinate(
                        (float) (rowBuff[VALUES_PER_CELL * col + 1] * SEC_RAD),
                        (float) (rowBuff[VALUES_PER_CELL * col] * SEC_RAD));
            }
        }
        return cvs;
    }

    /**
     * Initialize conversion table from the <em>first</em> subgrid only.
     *
     * @deprecated single-subgrid only, and therefore wrong for most real NTv2 files. Retained for
     *             binary compatibility; {@link Grid} uses the multi-subgrid path.
     */
    @Deprecated
    public static Grid.ConversionTable init(DataInputStream instream) throws IOException {
        byte[] buf = new byte[HEADER_SIZE];
        instream.readFully(buf);

        if (!testHeader(buf)) {
            // Was `throw new Error(...)`. Deprecated and unreachable from Grid.parse today, but an
            // Error on a public static method is one refactor away from escaping every handler on
            // the grid path, so it is converted with the two reachable ones.
            throw new GridFormatException("Not a NTv2 file");
        }
        ByteOrder endian = guessByteOrder(buf);

        buf = new byte[SUB_HEADER_SIZE];
        instream.readFully(buf);

        return subHeader(buf, 0, endian, "NTv2 Grid Shift File");
    }

    /**
     * Load the first subgrid into grid.
     *
     * @deprecated single-subgrid only. See {@link #init(DataInputStream)}.
     */
    @Deprecated
    public static void load(DataInputStream instream, Grid grid) throws IOException {
        int cols = grid.table.lim.lam;
        int rows = grid.table.lim.phi;

        byte[] buf = new byte[HEADER_SIZE];
        instream.readFully(buf);
        ByteOrder endian = guessByteOrder(buf);

        readFully(instream, new byte[SUB_HEADER_SIZE]);

        long haveBytes = GridExtents.remaining(instream);
        int cells = GridExtents.checkedCount("NTv2 Grid Shift File", cols, rows, BYTES_PER_CELL, 0L,
                haveBytes >= 0 ? haveBytes : GridExtents.maxFileBytes(),
                haveBytes >= 0 ? "the remaining file" : "the grid-file ceiling");
        byte[] data = new byte[cells * BYTES_PER_CELL];
        instream.readFully(data);
        grid.table.cvs = readCells(data, 0, cols, rows, endian);
    }

    private static void readFully(DataInputStream in, byte[] buf) throws IOException {
        in.readFully(buf);
    }

    /**
     * Guess byte order / endianness by checking first bytes in header
     *
     * @param header
     * @return endianness
     */
    private static ByteOrder guessByteOrder(byte[] header) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(header, NUM_OREC, Integer.BYTES);
        if (buffer.order(ByteOrder.BIG_ENDIAN).getInt() == 11) {
            return ByteOrder.BIG_ENDIAN;
        }
        buffer = ByteBuffer.wrap(header, NUM_OREC, Integer.BYTES);
        if (buffer.order(ByteOrder.LITTLE_ENDIAN).getInt() == 11) {
            return ByteOrder.LITTLE_ENDIAN;
        }
        throw new IOException("Could not determine NTv2 endianness: NUM_OREC is neither 11 "
                + "big-endian nor 11 little-endian");
    }

    private static double doubleFromBytes(byte[] b, int offset, ByteOrder order) {
        return ByteBuffer.wrap(b, offset, Double.BYTES).order(order).getDouble();
    }

    private static int intFromBytes(byte[] b, int offset, ByteOrder order) {
        return ByteBuffer.wrap(b, offset, Integer.BYTES).order(order).getInt();
    }
}

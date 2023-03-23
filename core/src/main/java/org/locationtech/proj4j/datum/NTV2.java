/** *****************************************************************************
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
import java.util.Arrays;

import org.locationtech.proj4j.util.FloatPolarCoordinate;
import org.locationtech.proj4j.util.IntPolarCoordinate;
import org.locationtech.proj4j.util.PolarCoordinate;

/**
 * Parser for the "National Transformation" v2 format
 *
 * Very basic implementation: - only files with 1 subfile are supported - gridshift type is supposed to be in
 * seconds - only little-endian is supported ("Australian"-NTv2, as opposed to "Canadian")
 *
 * Header structure:
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

    private static final int S_LAT = 72;
    private static final int N_LAT = 88;
    private static final int E_LONG = 104;
    private static final int W_LONG = 120;

    private static final int LAT_INC = 136;
    private static final int LONG_INC = 152;

    /**
     * Use header to check file type
     *
     * @param header
     * @return true if format is NTv2
     */
    public static boolean testHeader(byte[] header) {
        byte[] start = Arrays.copyOfRange(header, 0, MAGIC.length);
        return Arrays.equals(start, MAGIC);
    }

    /**
     * Initialize conversion table
     *
     * @param instream
     * @return
     * @throws IOException
     */
    public static Grid.ConversionTable init(DataInputStream instream) throws IOException {
        byte[] buf = new byte[HEADER_SIZE];
        instream.readFully(buf);

        if (!testHeader(buf)) {
            throw new Error("Not a NTv2 file");
        }

        buf = new byte[SUB_HEADER_SIZE];
        instream.readFully(buf);

        Grid.ConversionTable table = new Grid.ConversionTable();
        table.id = "NTv2 Grid Shift File";
        // lower left
        table.ll = new PolarCoordinate(-doubleFromBytes(buf, W_LONG) * SEC_RAD, doubleFromBytes(buf, S_LAT) * SEC_RAD);
        // upper right
        PolarCoordinate ur = new PolarCoordinate(-doubleFromBytes(buf, E_LONG) * SEC_RAD, doubleFromBytes(buf, N_LAT) * SEC_RAD);
        // "creative" way to store a pair of values
        table.del = new PolarCoordinate(doubleFromBytes(buf, LONG_INC) * SEC_RAD, doubleFromBytes(buf, LAT_INC) * SEC_RAD);
        table.lim = new IntPolarCoordinate(
            (int) (Math.abs(ur.lam - table.ll.lam) / table.del.lam + 0.5) + 1,
            (int) (Math.abs(ur.phi - table.ll.phi) / table.del.phi + 0.5) + 1);

        return table;
    }

    /**
     * Load grid(sub)file into grid
     *
     * @param instream
     * @param grid
     * @throws IOException
     */
    public static void load(DataInputStream instream, Grid grid) throws IOException {
        int cols = grid.table.lim.lam;
        int rows = grid.table.lim.phi;

        instream.skipBytes(HEADER_SIZE + SUB_HEADER_SIZE);

        FloatPolarCoordinate[] tmp_cvs = new FloatPolarCoordinate[cols * rows];

        float[] row_buff = new float[cols * VALUES_PER_CELL];
        byte[] byteBuff = new byte[row_buff.length * Float.BYTES];

        for (int row = 0; row < rows; row++) {
            instream.readFully(byteBuff);
            ByteBuffer.wrap(byteBuff).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(row_buff);
            for (int col = 0; col < cols; col++) {
                // only process the shift values, ignoring accuracy values
                tmp_cvs[row * cols + (cols - col - 1)] = new FloatPolarCoordinate(
                    (float) (row_buff[VALUES_PER_CELL * col + 1] * SEC_RAD),
                    (float) (row_buff[VALUES_PER_CELL * col] * SEC_RAD));
            }
        }
        grid.table.cvs = tmp_cvs;
    }

    private static double doubleFromBytes(byte[] b, int offset) {
        return ByteBuffer.wrap(b, offset, Double.BYTES).order(ByteOrder.LITTLE_ENDIAN).getDouble();
    }

}

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
package org.locationtech.proj4j.grids;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.List;

import org.junit.Test;
import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.datum.Grid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Pins the header of the {@code ntv1_can.dat} that {@code proj4j-epsg} actually ships, and pins the
 * consequence: {@code NAD27}'s one surviving grid covers <strong>Canada</strong>.
 *
 * <p>{@code datum/Datum.java} asks for {@code @conus,@alaska,@ntv2_0.gsb,@ntv1_can.dat} and
 * {@code proj4j-epsg} ships exactly one of the four. Every token is {@code @}-optional, so the three
 * missing ones are discarded silently and the surviving grid is authoritative for a country the
 * majority of NAD27 users are not in. San Francisco (37.78&deg;N) and Kansas (39.0&deg;N) are
 * <em>south</em> of the footprint and get no shift at all; Chicago (41.9&deg;N), Boston (42.4&deg;N),
 * Minneapolis (45.0&deg;N) and Seattle (47.6&deg;N) are inside the bounding box and are bilinearly
 * interpolated from a Canada-authoritative grid. Neither outcome is reported, and they are different
 * kinds of wrong.
 *
 * <p><strong>Correction to a previously recorded fact.</strong> This footprint has been quoted as
 * {@code 40N-84N, 143W-44W} with a {@code 397 x 177} node array. The header says <strong>142&deg;W</strong>,
 * and the array is <strong>393 x 177</strong>. Three independent checks agree:
 * <ul>
 *   <li>The {@code W LONG} record at byte offset 72 holds the {@code double} {@code 142.0}.</li>
 *   <li>Proj4J's and PROJ's identical column formula give
 *       {@code |(-44) - (-142)| / 0.25 + 0.5 + 1 = 393}.</li>
 *   <li>The file size accounts for exactly 393 columns and nothing else:
 *       {@code 192} header bytes {@code + 393 * 177 * 16} node bytes {@code + 16} for the trailing
 *       {@code END} record {@code = 1,113,184}, the shipped size. With 397 columns the node data alone
 *       would be 1,124,304 bytes, larger than the whole file.</li>
 * </ul>
 */
public class Ntv1CanHeaderTest {

    private static final Charset ASCII = Charset.forName("US-ASCII");

    /** The size the file must be for the 393-column arithmetic to close. */
    private static final int SHIPPED_SIZE = 1113184;

    private static byte[] shippedBytes() throws IOException {
        InputStream in = Ntv1CanHeaderTest.class.getResourceAsStream("/proj4/nad/ntv1_can.dat");
        assertNotNull("proj4j-epsg must be on the test classpath and must ship ntv1_can.dat", in);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static String label(byte[] b, int offset) {
        return new String(b, offset, 8, ASCII);
    }

    private static double bigEndianDouble(byte[] b, int offset) {
        return ByteBuffer.wrap(b, offset, 8).order(ByteOrder.BIG_ENDIAN).getDouble();
    }

    private static int bigEndianInt(byte[] b, int offset) {
        return ByteBuffer.wrap(b, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    @Test
    public void headerRecordsAreExactlyThese() throws IOException {
        byte[] b = shippedBytes();
        assertEquals("shipped ntv1_can.dat size", SHIPPED_SIZE, b.length);

        // Twelve 16-byte records: an 8-char label then an 8-byte value.
        assertEquals("HEADER  ", label(b, 0));
        assertEquals("record count", 12, bigEndianInt(b, 8));
        assertEquals("S LAT   ", label(b, 16));
        assertEquals("S LAT", 40.0, bigEndianDouble(b, 24), 0.0);
        assertEquals("N LAT   ", label(b, 32));
        assertEquals("N LAT", 84.0, bigEndianDouble(b, 40), 0.0);
        assertEquals("E LONG  ", label(b, 48));
        assertEquals("E LONG", 44.0, bigEndianDouble(b, 56), 0.0);
        assertEquals("W LONG  ", label(b, 64));
        assertEquals("W LONG is 142.0, not the 143.0 previously recorded",
                142.0, bigEndianDouble(b, 72), 0.0);
        assertEquals("N GRID  ", label(b, 80));
        assertEquals("N GRID", 0.25, bigEndianDouble(b, 88), 0.0);
        assertEquals("W GRID  ", label(b, 96));
        assertEquals("W GRID", 0.25, bigEndianDouble(b, 104), 0.0);
        assertEquals("TYPE    ", label(b, 112));
        assertEquals("FROM    ", label(b, 128));
        assertEquals("TO      ", label(b, 144));
    }

    @Test
    public void fileSizeAccountsForExactly393By177Nodes() throws IOException {
        byte[] b = shippedBytes();
        int columns = 393;
        int rows = 177;
        int headerBytes = 192;      // twelve 16-byte records
        int endRecordBytes = 16;    // the trailing END record
        assertEquals("192 header + 393*177*16 node + 16 END must equal the file size",
                b.length, headerBytes + columns * rows * 16 + endRecordBytes);
        assertTrue("397 columns would need more bytes than the whole file",
                headerBytes + 397 * rows * 16 > b.length);
    }

    @Test
    public void parsedGridHas393By177NodesOverCanada() throws IOException {
        List<Grid> grids = GridReferenceValues.singleton("ntv1_can.dat");
        assertEquals(1, grids.size());
        Grid g = grids.get(0);
        assertEquals("ntv1", g.getFormat());

        double[] extent = g.extentRadians();
        assertEquals("west", -142.0, Math.toDegrees(extent[0]), 1e-12);
        assertEquals("south", 40.0, Math.toDegrees(extent[1]), 1e-12);
        assertEquals("east", -44.0, Math.toDegrees(extent[2]), 1e-9);
        assertEquals("north", 84.0, Math.toDegrees(extent[3]), 1e-9);
    }

    /**
     * The two points from the downstream defect report are south of the footprint, which is exactly
     * why they used to come back unchanged.
     *
     * <p>They are now <em>refused</em>. That is the change:
     * {@code cct +proj=hgridshift +grids=ntv1_can.dat} at San Francisco is
     * {@code TRANSFORMATION ERROR (Coordinate to transform falls outside grid)}, and "returned
     * unchanged" was the 95.573 m defect wearing the shape of a successful transform. The footprint
     * claim this test exists to pin is unaffected — a refusal is a stronger statement of "outside"
     * than an echo, and the {@link ErrorCause} names it. See {@link OutsideGridFailsClosedTest}.
     */
    @Test
    public void sanFranciscoAndKansasAreSouthOfTheFootprint() throws IOException {
        List<Grid> grids = GridReferenceValues.singleton("ntv1_can.dat");
        assertOutside(grids, GridReferenceValues.SAN_FRANCISCO, "San Francisco");
        assertOutside(grids, GridReferenceValues.KANSAS, "Kansas");
    }

    private static void assertOutside(List<Grid> grids, double[] point, String label) {
        try {
            double[] got = GridReferenceValues.shiftDegrees(grids, false, point[0], point[1]);
            fail(label + " is south of ntv1_can.dat's 40 degree southern edge and PROJ 9.8.1 "
                    + "refuses it, yet shift returned (" + got[0] + ", " + got[1] + ")");
        } catch (CrsTransformException expected) {
            assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, expected.cause());
            assertTrue("the message must name the grid that did not cover it: "
                    + expected.getMessage(), expected.getMessage().contains("ntv1_can.dat"));
        }
    }
}

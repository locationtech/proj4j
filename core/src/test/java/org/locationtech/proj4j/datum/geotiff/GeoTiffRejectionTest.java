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
package org.locationtech.proj4j.datum.geotiff;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.Test;
import org.locationtech.proj4j.datum.Grid;
import org.locationtech.proj4j.datum.VerticalGrid;
import org.locationtech.proj4j.datum.tiff.GeoTiffDataset;
import org.locationtech.proj4j.datum.tiff.UnsupportedTiffException;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * What the reader refuses, and whether it says why.
 *
 * <p>This is the class that enforces the project's third non-negotiable — <em>a failure must never be
 * expressed as a plausible coordinate</em> — for the GeoTIFF path specifically. Every case here is a
 * file that a reader could plausibly muddle through, producing zeros, garbage, or a half-decoded block,
 * and reporting a successful transform. All of them must instead throw, and the message must name the
 * feature or the defect, because "grid failed to load" in a Spark executor log is not actionable.
 *
 * <p>Two of the cases are upstream's own fixtures and correspond to
 * {@code expect failure errno invalid_op_file_not_found_or_invalid} rows in
 * {@code geotiff_grids.gie}: {@code test_vgrid_unsupported_byte.tif} and
 * {@code test_vgrid_invalid_channel_type.tif}. The rest are the fixtures mutated one field at a time,
 * which is the only way to cover codecs and malformations upstream ships no file for.
 */
public class GeoTiffRejectionTest {

    private static byte[] fixture(String name) throws IOException {
        InputStream in = GeoTiffRejectionTest.class
                .getResourceAsStream("/proj4j-data/grids/" + name);
        assertNotNull("fixture " + name + " must be on the test classpath", in);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /**
     * Rewrites the first {@code SHORT}-typed value of a tag in IFD 0 of a little-endian classic TIFF.
     *
     * <p>Written by hand rather than with a TIFF library because there is no TIFF library on this
     * classpath and adding one is what this whole package exists to avoid.
     */
    private static byte[] patchShortTag(byte[] file, int tag, int newValue) {
        ByteBuffer b = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        int ifd = b.getInt(4);
        int count = b.getShort(ifd) & 0xffff;
        for (int i = 0; i < count; i++) {
            int p = ifd + 2 + i * 12;
            if ((b.getShort(p) & 0xffff) == tag) {
                b.putShort(p + 8, (short) newValue);
                return file;
            }
        }
        throw new IllegalStateException("tag " + tag + " not found in IFD 0");
    }

    /** Adds a {@code SHORT} tag is not possible without relaying out the IFD, so mutate one instead. */
    private static byte[] fixtureWith(String name, int tag, int value) throws IOException {
        return patchShortTag(fixture(name), tag, value);
    }

    private static void expectFailure(String what, byte[] bytes, String mustMention) {
        try {
            GeoTiffDataset dataset = GeoTiffDataset.open(bytes, what);
            // Some defects only surface when the pixels are actually decoded, which is upstream's
            // behaviour too -- libtiff reports a strip read failure, not an open failure.
            dataset.images().get(0).readSample(0);
            fail(what + " must be refused, but it parsed and decoded");
        } catch (IOException e) {
            String message = String.valueOf(e.getMessage());
            assertTrue(what + ": message must mention \"" + mustMention + "\" but was: " + message,
                    message.contains(mustMention));
        }
    }

    // --- Upstream's own rejection fixtures ---------------------------------------------------

    /**
     * 8-bit samples. Not in PROJ's six-member {@code TIFFDataType} enum, so upstream logs
     * <em>"Unsupported combination of SampleFormat and BitsPerSample values"</em> and returns nothing.
     */
    @Test
    public void eightBitSamplesAreRefused() throws Exception {
        try {
            VerticalGrid.fromName("test_vgrid_unsupported_byte.tif");
            fail("an 8-bit GeoTIFF must not become a vertical grid");
        } catch (IOException e) {
            String m = String.valueOf(e.getMessage());
            assertTrue("must name the offending combination: " + m,
                    m.contains("SampleFormat") && m.contains("BitsPerSample"));
        }
    }

    /**
     * A band described as {@code invalid_channel_type}. The file is structurally perfect; the only thing
     * wrong is the semantics, and IFD 0 having <em>some</em> description but not a recognised one is
     * fatal rather than skippable.
     */
    @Test
    public void unrecognisedBandDescriptionIsRefused() throws Exception {
        try {
            VerticalGrid.fromName("test_vgrid_invalid_channel_type.tif");
            fail("a GeoTIFF whose only band is not a vertical shift must not become a vertical grid");
        } catch (IOException e) {
            assertTrue("must list the descriptions it would have accepted: " + e.getMessage(),
                    e.getMessage().contains("geoid_undulation"));
        }
    }

    /**
     * A one-band vertical grid asked for as a horizontal one. {@code geotiff_grids.gie} has this as
     * {@code operation +proj=hgridshift +grids=tests/test_vgrid.tif} &rarr;
     * {@code expect failure}: a horizontal shift needs two bands, and one band must not be silently
     * reused for both components.
     */
    @Test
    public void singleBandGridIsNotAHorizontalGrid() throws Exception {
        try {
            GeoTiffFixtures.horizontal("test_vgrid_pixelispoint.tif");
            fail("a 1-band GeoTIFF must not become a horizontal shift grid");
        } catch (IOException e) {
            assertTrue("must say why one band is not enough: " + e.getMessage(),
                    e.getMessage().contains("at least 2"));
        }
    }

    /**
     * {@code StripByteCounts} declares 4,152,960 bytes; the file is 550. This is upstream's truncated
     * fixture, and the only acceptable outcome is an error — a reader that zero-fills the missing strip
     * produces a geoid of exactly 0 m everywhere, which is both plausible and catastrophic.
     */
    @Test
    public void truncatedStripIsRefused() throws Exception {
        try {
            VerticalGrid.fromName("test_vgrid_single_strip_truncated.tif");
            fail("a grid whose pixel data is not in the file must not load");
        } catch (IOException e) {
            assertTrue("must say the file is short: " + e.getMessage(),
                    e.getMessage().contains("truncated"));
        }
    }

    // --- Mutated fixtures: the cases upstream ships no file for -----------------------------

    /** A file cut off inside the pixel data. */
    @Test
    public void fileTruncatedMidwayIsRefused() throws Exception {
        byte[] full = fixture("test_hgrid.tif");
        byte[] half = new byte[full.length / 2];
        System.arraycopy(full, 0, half, 0, half.length);
        expectFailure("test_hgrid.tif truncated to " + half.length + " bytes", half, "TIFF");
    }

    /** A file cut off before the header is complete. */
    @Test
    public void fileShorterThanTheHeaderIsRefused() throws Exception {
        byte[] stub = new byte[6];
        System.arraycopy(fixture("test_hgrid.tif"), 0, stub, 0, stub.length);
        expectFailure("6-byte stub", stub, "header");
    }

    /**
     * LZW. libtiff has it configured by default, so PROJ <em>would</em> read such a grid; proj4j
     * deliberately does not, because LZW in pure Java is a few hundred lines that no published PROJ
     * grid needs. The point of the test is that the refusal names LZW, so the message tells a user
     * whose third-party grid this is exactly what to re-encode.
     */
    @Test
    public void lzwCompressionIsRefusedByName() throws Exception {
        expectFailure("test_vgrid_pixelispoint.tif re-tagged as LZW",
                fixtureWith("test_vgrid_pixelispoint.tif", 259, 5), "LZW");
    }

    /** JPEG, for the same reason, and because a JPEG-compressed grid is a nonsense we should reject. */
    @Test
    public void jpegCompressionIsRefusedByName() throws Exception {
        expectFailure("test_vgrid_pixelispoint.tif re-tagged as JPEG",
                fixtureWith("test_vgrid_pixelispoint.tif", 259, 7), "JPEG");
    }

    /**
     * A palette image. {@code Photometric != MINISBLACK} means the samples are not measurements, so the
     * file is not a grid whatever else is true of it.
     */
    @Test
    public void nonGreyscalePhotometricIsRefused() throws Exception {
        expectFailure("test_vgrid_pixelispoint.tif re-tagged as palette",
                fixtureWith("test_vgrid_pixelispoint.tif", 262, 3), "Photometric");
    }

    /**
     * {@code Predictor=2}, horizontal differencing, on IEEE floating-point samples. TIFF defines
     * predictor 2 for integers and predictor 3 for floating point; applying the integer accumulation to
     * float bytes yields finite nonsense, so the mismatch has to be caught rather than executed.
     */
    @Test
    public void horizontalPredictorOnFloatDataIsRefused() throws Exception {
        expectFailure("test_vgrid_deflate_floatingpointpredictor.tif re-tagged as Predictor=2",
                fixtureWith("test_vgrid_deflate_floatingpointpredictor.tif", 317, 2), "Predictor=2");
    }

    /**
     * An unknown predictor value. Applied to the deflate fixture, because a file with no
     * {@code Predictor} tag at all has no field to mutate — and a reader must default an absent
     * {@code Predictor} to 1 rather than invent one.
     */
    @Test
    public void unknownPredictorIsRefused() throws Exception {
        expectFailure("test_vgrid_deflate_floatingpointpredictor.tif with Predictor=4",
                fixtureWith("test_vgrid_deflate_floatingpointpredictor.tif", 317, 4), "Predictor=4");
    }

    /**
     * A corrupt DEFLATE stream. Flipping bytes inside the compressed data must surface as an error, not
     * as a partially inflated block whose tail is zero.
     */
    @Test
    public void corruptDeflateStreamIsRefused() throws Exception {
        byte[] bytes = fixture("test_vgrid_deflate_floatingpointpredictor.tif");
        // The strip starts at offset 378 in this file (StripOffsets, read with tiffdump); corrupt the
        // zlib payload just past its two-byte header.
        for (int i = 380; i < 396 && i < bytes.length; i++) {
            bytes[i] = (byte) ~bytes[i];
        }
        expectFailure("test_vgrid_deflate_floatingpointpredictor.tif with a corrupted zlib payload",
                bytes, "");
    }

    /**
     * An IFD chain that points back at itself. A reader without a visited set spins forever, and a grid
     * name can come from a per-row {@code +grids=} token, so this is a denial-of-service surface rather
     * than a curiosity.
     */
    @Test
    public void loopingIfdChainIsRefused() throws Exception {
        byte[] bytes = fixture("test_hgrid.tif");
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int ifd = b.getInt(4);
        int count = b.getShort(ifd) & 0xffff;
        b.putInt(ifd + 2 + count * 12, ifd);
        expectFailure("test_hgrid.tif with a self-referential IFD chain", bytes, "loops");
    }

    /** Not a TIFF at all. */
    @Test
    public void nonTiffBytesAreRefused() throws Exception {
        byte[] bytes = new byte[]{'G', 'I', 'F', '8', '9', 'a', 0, 0, 0, 0, 0, 0};
        assertTrue("GIF must not be mistaken for a TIFF", !GeoTiffDataset.isTiff(bytes, 4));
        expectFailure("a GIF header", bytes, "Not a TIFF");
    }

    /**
     * A projected grid. proj4j's {@code ConversionTable} is radians of longitude and latitude
     * throughout, and PROJ refuses these too — <em>"Can only handle grids referenced in a geographic
     * CRS"</em>. Refusing at parse time rather than at use time is the one deliberate deviation, and it
     * still refuses.
     */
    @Test
    public void projectedGridIsRefused() throws Exception {
        // GTModelTypeGeoKey = 1024 with ModelTypeProjected = 1. The GeoKeyDirectory of
        // test_hgrid.tif has none, so use the vertical fixture that does and re-tag its model type.
        byte[] bytes = fixture("test_vgrid_pixelispoint.tif");
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int ifd = b.getInt(4);
        int count = b.getShort(ifd) & 0xffff;
        int keyDirOffset = -1;
        for (int i = 0; i < count; i++) {
            int p = ifd + 2 + i * 12;
            if ((b.getShort(p) & 0xffff) == 34735) {
                keyDirOffset = b.getInt(p + 8);
            }
        }
        assertTrue("test_vgrid_pixelispoint.tif must carry a GeoKeyDirectory", keyDirOffset > 0);
        // Keys start at index 4, four SHORTs each: [key, tiffTagLocation, count, valueOrOffset].
        for (int k = 4; ; k += 4) {
            int key = b.getShort(keyDirOffset + k * 2) & 0xffff;
            if (key == 0) {
                fail("GTModelTypeGeoKey not found");
            }
            if (key == 1024) {
                b.putShort(keyDirOffset + (k + 3) * 2, (short) 1);
                break;
            }
        }
        try {
            GeoTiffDataset dataset = GeoTiffDataset.open(bytes, "projected");
            assertTrue("the image must report itself as projected",
                    !dataset.images().get(0).isGeographic());
        } catch (UnsupportedTiffException e) {
            fail("a projected grid must parse as an image and be refused by the grid layer, not the "
                    + "container layer: " + e.getMessage());
        }
    }

    /** A name that resolves to nothing must be an error naming the resolution chain, not a no-op grid. */
    @Test
    public void unresolvableNameIsAnError() throws Exception {
        try {
            Grid.mergeGridFile("definitely_not_a_grid.tif", new java.util.ArrayList<Grid>());
            fail("an unresolvable grid name must throw");
        } catch (IOException e) {
            assertTrue("must name the chain that was searched: " + e.getMessage(),
                    e.getMessage().contains("working directory is deliberately not searched"));
        }
    }
}

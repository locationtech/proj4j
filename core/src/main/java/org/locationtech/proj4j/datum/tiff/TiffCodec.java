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
package org.locationtech.proj4j.datum.tiff;

import java.io.IOException;
import java.nio.ByteOrder;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * The two decoding steps a PROJ geodetic GeoTIFF can need: DEFLATE, and a predictor.
 *
 * <h2>Compression</h2>
 * <p>Exactly two codecs are implemented: {@code COMPRESSION_NONE} and DEFLATE, under both of its tag
 * values ({@code 8} = {@code ADOBE_DEFLATE} and the older private {@code 32946} = {@code DEFLATE};
 * they denote the same zlib stream). Every grid PROJ publishes uses {@code 8}. DEFLATE comes from
 * {@link java.util.zip.Inflater} in the JDK, which is how the reader holds to proj4j core's
 * zero-runtime-dependency rule.
 *
 * <p>Anything else — LZW, PackBits, JPEG, WEBP, ZSTD, LERC — raises {@link UnsupportedTiffException}
 * naming the codec. libtiff would decode several of those, so this is a stated narrowing rather than
 * an oversight: a grid we cannot decode must say so, not return zeros.
 *
 * <h2>Predictors, and the byte-order trap in them</h2>
 * <p>Predictor {@code 3}, the <em>floating-point</em> predictor, is not optional: all seven of PROJ's
 * US grids are {@code Predictor=3} + {@code PLANARCONFIG_SEPARATE} + DEFLATE. Both predictors are
 * ported from libtiff's {@code tif_predict.c}, which is where PROJ's behaviour actually comes from.
 *
 * <p>libtiff resolves byte order differently for the two predictors, and copying the wrong one
 * silently produces finite, wrong values on a big-endian file:
 * <ul>
 *   <li><strong>Predictor 2.</strong> {@code PredictorSetupDecode} installs {@code swabHorAcc16/32}
 *       and sets {@code tif_postdecode = _TIFFNoPostDecode} when the file order differs from the
 *       host, i.e. the deltas are byte-swapped to native <em>before</em> accumulation. Adding with the
 *       carry propagating from whichever byte the <em>file</em> order makes least significant is the
 *       same arithmetic without the swap, and leaves the result in file order.</li>
 *   <li><strong>Predictor 3.</strong> Also sets {@code _TIFFNoPostDecode}, with the comment "the data
 *       should not be swapped outside of the floating point predictor routine". The on-disk plane
 *       layout is byte-order independent — plane 0 always holds the most significant byte of every
 *       sample, which is why {@code fpAcc}/{@code fpDiff} have mirrored {@code WORDS_BIGENDIAN}
 *       branches that agree on the file layout. The de-interleave here writes each sample back in the
 *       <em>file's</em> order, so everything downstream can read with one byte order.</li>
 * </ul>
 */
final class TiffCodec {

    private TiffCodec() {
    }

    /** Names a compression code, for an error message that identifies the unsupported feature. */
    static String compressionName(int code) {
        switch (code) {
            case TiffTags.COMPRESSION_NONE: return "none";
            case 2: return "CCITT modified Huffman";
            case 3: return "CCITT Group 3 fax";
            case 4: return "CCITT Group 4 fax";
            case 5: return "LZW";
            case 6: return "old-style JPEG";
            case 7: return "JPEG";
            case TiffTags.COMPRESSION_ADOBE_DEFLATE: return "Adobe DEFLATE";
            case 32773: return "PackBits";
            case TiffTags.COMPRESSION_DEFLATE: return "DEFLATE (private code 32946)";
            case 34712: return "JPEG 2000";
            case 34887: return "LERC";
            case 50000: return "ZSTD";
            case 50001: return "WEBP";
            default: return "code " + code;
        }
    }

    /**
     * Decodes one strip or tile into a freshly allocated buffer of exactly {@code expectedBytes}.
     *
     * <p>Short output is tolerated and leaves the tail zero, which is what libtiff produces for the
     * last, partially populated strip of an image whose height is not a multiple of
     * {@code RowsPerStrip}. Output longer than {@code expectedBytes} is truncated.
     *
     * @param file          the whole TIFF
     * @param offset        byte offset of the encoded block
     * @param byteCount     encoded length
     * @param compression   the {@code Compression} tag value
     * @param expectedBytes uncompressed size of one block
     * @return a buffer of exactly {@code expectedBytes}
     * @throws IOException if the block runs past end of file, or the DEFLATE stream is corrupt
     */
    static byte[] decodeBlock(byte[] file, long offset, long byteCount, int compression,
                              int expectedBytes) throws IOException {
        if (offset < 0 || byteCount < 0 || offset + byteCount > file.length) {
            throw new IOException("TIFF truncated: block of " + byteCount + " bytes at offset "
                    + offset + ", but the file is " + file.length + " bytes");
        }
        byte[] out = new byte[expectedBytes];
        if (compression == TiffTags.COMPRESSION_NONE) {
            int n = (int) Math.min(byteCount, expectedBytes);
            System.arraycopy(file, (int) offset, out, 0, n);
            return out;
        }
        if (compression != TiffTags.COMPRESSION_ADOBE_DEFLATE
                && compression != TiffTags.COMPRESSION_DEFLATE) {
            throw new UnsupportedTiffException("TIFF grid uses " + compressionName(compression)
                    + " compression; proj4j reads only uncompressed and DEFLATE GeoTIFF grids, which "
                    + "is what every grid PROJ publishes uses");
        }
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(file, (int) offset, (int) byteCount);
            int total = 0;
            while (total < expectedBytes && !inflater.finished()) {
                int n = inflater.inflate(out, total, expectedBytes - total);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                total += n;
            }
            if (total < expectedBytes) {
                // libtiff's ZIPDecode ends with `if (sp->stream.avail_out != 0) { TIFFErrorExtR(...
                // "Not enough data at scanline %lu (short %I64d bytes)"); return 0; }` and a codec
                // returning 0 makes TIFFReadEncodedStrip return -1, which PROJ propagates as a failed
                // valueAt. Without this check a truncated zlib stream leaves the tail of the block
                // zero, so a geoid model reads as exactly 0 m over the missing rows -- finite,
                // plausible and catastrophic. Note the caller sizes `expectedBytes` from the *clamped*
                // row count, exactly as libtiff sizes a short final strip, so a legitimately short
                // last strip does not reach here.
                throw new IOException("TIFF DEFLATE block at offset " + offset + " decoded to "
                        + total + " bytes; the block needs " + expectedBytes
                        + ". The file is truncated or the compressed stream is corrupt.");
            }
        } catch (DataFormatException e) {
            throw new IOException("TIFF DEFLATE block at offset " + offset + " is corrupt: "
                    + e.getMessage(), e);
        } finally {
            inflater.end();
        }
        return out;
    }

    /**
     * Undoes a predictor, in place, over a block laid out as {@code rows} rows of {@code rowBytes}.
     *
     * <p>libtiff applies the predictor <strong>one row at a time</strong>
     * ({@code PredictorDecodeTile} loops {@code rowsize} at a time), so a delta never crosses a row
     * boundary. Doing it over the whole block instead is a defect that shows only as a gradient
     * building up down the image.
     *
     * @param block          the decoded block, mutated in place
     * @param rows           number of rows present in the block
     * @param rowBytes       bytes per row: {@code (tiled ? tileWidth : imageWidth) * strideSamples *
     *                       bytesPerSample}
     * @param predictor      the {@code Predictor} tag value
     * @param bytesPerSample {@code BitsPerSample / 8}
     * @param stride         libtiff's {@code sp-&gt;stride}: {@code samplesPerPixel} for
     *                       {@code CONTIG}, {@code 1} for {@code SEPARATE}
     * @param sampleFormat   the {@code SampleFormat} tag value
     * @param order          the file's byte order
     * @throws IOException if the predictor or sample width is one this reader does not implement
     */
    static void undoPredictor(byte[] block, int rows, int rowBytes, int predictor,
                              int bytesPerSample, int stride, int sampleFormat, ByteOrder order)
            throws IOException {
        if (predictor == TiffTags.PREDICTOR_NONE) {
            return;
        }
        if (predictor == TiffTags.PREDICTOR_HORIZONTAL) {
            if (sampleFormat == TiffTags.SAMPLEFORMAT_IEEEFP) {
                throw new UnsupportedTiffException("TIFF grid declares Predictor=2 (horizontal "
                        + "differencing) with IEEE floating-point samples; TIFF defines predictor 2 "
                        + "for integer data and predictor 3 for floating point");
            }
            for (int r = 0; r < rows; r++) {
                undoHorizontal(block, r * rowBytes, rowBytes, bytesPerSample, stride, order);
            }
            return;
        }
        if (predictor == TiffTags.PREDICTOR_FLOATING_POINT) {
            if (sampleFormat != TiffTags.SAMPLEFORMAT_IEEEFP) {
                throw new UnsupportedTiffException("TIFF grid declares Predictor=3 (floating point) "
                        + "with SampleFormat=" + sampleFormat + "; predictor 3 is defined only for "
                        + "IEEE floating-point samples");
            }
            for (int r = 0; r < rows; r++) {
                undoFloatingPoint(block, r * rowBytes, rowBytes, bytesPerSample, stride, order);
            }
            return;
        }
        throw new UnsupportedTiffException("TIFF grid declares Predictor=" + predictor
                + "; proj4j implements 1 (none), 2 (horizontal differencing) and 3 (floating point)");
    }

    /**
     * libtiff {@code horAcc*}: each sample is a delta from the sample {@code stride} positions earlier
     * in the same row. Carries propagate from the byte the file's order makes least significant, which
     * is arithmetically identical to libtiff's swab-then-accumulate and leaves the block in file
     * order.
     */
    private static void undoHorizontal(byte[] b, int off, int rowBytes, int bytesPerSample,
                                       int stride, ByteOrder order) {
        if (bytesPerSample == 1) {
            for (int i = stride; i < rowBytes; i++) {
                b[off + i] += b[off + i - stride];
            }
            return;
        }
        int samples = rowBytes / bytesPerSample;
        boolean little = order == ByteOrder.LITTLE_ENDIAN;
        for (int i = stride; i < samples; i++) {
            int cur = off + i * bytesPerSample;
            int prev = off + (i - stride) * bytesPerSample;
            int carry = 0;
            for (int k = 0; k < bytesPerSample; k++) {
                int j = little ? k : bytesPerSample - 1 - k;
                int sum = (b[cur + j] & 0xff) + (b[prev + j] & 0xff) + carry;
                b[cur + j] = (byte) sum;
                carry = sum >>> 8;
            }
        }
    }

    /**
     * libtiff {@code fpAcc}: byte-wise horizontal accumulation with the given stride, then a
     * de-interleave that gathers each sample's bytes out of {@code bytesPerSample} byte planes.
     * Plane 0 is the most significant byte, whatever the file's byte order; the reassembled samples
     * are written back <em>in the file's order</em> so that a single byte order serves the whole read
     * path.
     */
    private static void undoFloatingPoint(byte[] b, int off, int rowBytes, int bytesPerSample,
                                          int stride, ByteOrder order) {
        for (int i = stride; i < rowBytes; i++) {
            b[off + i] += b[off + i - stride];
        }
        int words = rowBytes / bytesPerSample;
        if (words == 0) {
            return;
        }
        byte[] tmp = new byte[rowBytes];
        System.arraycopy(b, off, tmp, 0, rowBytes);
        boolean little = order == ByteOrder.LITTLE_ENDIAN;
        for (int w = 0; w < words; w++) {
            for (int plane = 0; plane < bytesPerSample; plane++) {
                int j = little ? bytesPerSample - 1 - plane : plane;
                b[off + w * bytesPerSample + j] = tmp[plane * words + w];
            }
        }
    }
}

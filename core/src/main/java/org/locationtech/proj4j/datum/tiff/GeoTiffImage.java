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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.locationtech.proj4j.datum.GridExtents;

/**
 * One georeferenced image inside a geodetic GeoTIFF: PROJ's {@code GTiffGrid}, plus the header
 * validation {@code GTiffDataset::nextGrid} does before constructing one.
 *
 * <p>This class is the whole of the format subset. Everything it reads is listed on
 * {@link TiffTags}; everything it refuses says which feature it refused and why. In particular it is
 * <strong>not</strong> a TIFF decoder that happens to be used for grids — there is no colour handling,
 * no photometric interpretation, no palette, no alpha, no orientation tag, no sub-byte samples, and
 * no support for any codec but DEFLATE.
 *
 * <h2>What is required</h2>
 * <table><caption>required tags</caption>
 * <tr><th>tag</th><th>constraint</th></tr>
 * <tr><td>{@code ImageWidth}, {@code ImageLength}</td><td>non-zero, within {@code int}</td></tr>
 * <tr><td>{@code SamplesPerPixel}</td><td>present, non-zero</td></tr>
 * <tr><td>{@code BitsPerSample}</td><td>present</td></tr>
 * <tr><td>{@code PlanarConfig}</td><td>present</td></tr>
 * <tr><td>{@code SampleFormat}</td><td>present</td></tr>
 * <tr><td>{@code Photometric}</td><td>absent, or {@code MINISBLACK}</td></tr>
 * <tr><td>georeferencing</td><td>{@code GeoTransformationMatrix} with 16 values, else
 *     {@code GeoPixelScale} (3) <em>and</em> {@code GeoTiePoints} (6)</td></tr>
 * </table>
 *
 * <p>Six {@code (SampleFormat, BitsPerSample)} combinations are legal, exactly PROJ's
 * {@code TIFFDataType} enum: signed and unsigned 16- and 32-bit integers, and 32- and 64-bit IEEE
 * floating point. Everything else, including the 8-bit case that
 * {@code tests/test_vgrid_unsupported_byte.tif} exists to check, is rejected.
 *
 * <h2>Sample values</h2>
 * <p>Values come back as {@code float}, which is <em>not</em> a narrowing this port introduced: PROJ's
 * {@code Grid::valueAt} signature is {@code (int x, int y, float &out)} and its {@code readValue}
 * template casts every type, {@code double} included, to {@code float}. Matching PROJ bit-for-bit
 * means matching that.
 *
 * <p>Immutable and stateless after construction except for the one-block decode cache, which is
 * private to a {@link #readSamples} call and never shared.
 */
public final class GeoTiffImage {

    private static final double DEG_TO_RAD = Math.PI / 180.0;

    /** {@code grids.cpp:1179} — {@code blockSize > 64 * 1024 * 2014}, upstream's own constant. */
    private static final long MAX_BLOCK_BYTES = 64L * 1024L * 2014L;

    // Header-derived, all final.
    private final TiffDirectory dir;
    private final byte[] file;
    private final ByteOrder order;
    private final int ifdIndex;
    private final String name;

    private final int width;
    private final int height;
    private final int samplesPerPixel;
    private final int bitsPerSample;
    private final int bytesPerSample;
    private final int sampleFormat;
    private final int planarConfig;
    /** Samples per pixel <em>within one block row</em>: {@code samplesPerPixel} for
     * {@code PLANARCONFIG_CONTIG}, 1 for {@code PLANARCONFIG_SEPARATE}. Computed once, in
     * {@link #of}, so the block-size bound and the scatter loop cannot disagree about it. */
    private final int strideSamples;
    private final int compression;
    private final int predictor;
    private final long subfileType;

    private final boolean tiled;
    private final int blockWidth;
    private final int blockHeight;
    private final int blocksPerRow;
    private final int blocksPerCol;
    private final int blocks;
    private final long[] blockOffsets;
    private final long[] blockByteCounts;

    private final boolean geographic;
    private final boolean bottomUp;
    private final double west;
    private final double south;
    private final double east;
    private final double north;
    private final double resX;
    private final double resY;

    private final GdalMetadata metadata;
    private final boolean hasNodata;
    private final float noData;

    private GeoTiffImage(Builder b) {
        this.dir = b.dir;
        this.file = b.file;
        this.order = b.order;
        this.ifdIndex = b.ifdIndex;
        this.name = b.name;
        this.width = b.width;
        this.height = b.height;
        this.samplesPerPixel = b.samplesPerPixel;
        this.bitsPerSample = b.bitsPerSample;
        this.bytesPerSample = b.bitsPerSample / 8;
        this.sampleFormat = b.sampleFormat;
        this.planarConfig = b.planarConfig;
        this.strideSamples = b.strideSamples;
        this.compression = b.compression;
        this.predictor = b.predictor;
        this.subfileType = b.subfileType;
        this.tiled = b.tiled;
        this.blockWidth = b.blockWidth;
        this.blockHeight = b.blockHeight;
        this.blocksPerRow = b.blocksPerRow;
        this.blocksPerCol = b.blocksPerCol;
        this.blocks = b.blocks;
        this.blockOffsets = b.blockOffsets;
        this.blockByteCounts = b.blockByteCounts;
        this.geographic = b.geographic;
        this.bottomUp = b.bottomUp;
        this.west = b.west;
        this.south = b.south;
        this.east = b.east;
        this.north = b.north;
        this.resX = b.resX;
        this.resY = b.resY;
        this.metadata = b.metadata;
        this.hasNodata = b.hasNodata;
        this.noData = b.noData;
    }

    private static final class Builder {
        TiffDirectory dir;
        byte[] file;
        ByteOrder order;
        int ifdIndex;
        String name;
        int width;
        int height;
        int samplesPerPixel;
        int bitsPerSample;
        int sampleFormat;
        int planarConfig;
        int strideSamples;
        int compression;
        int predictor;
        long subfileType;
        boolean tiled;
        int blockWidth;
        int blockHeight;
        int blocksPerRow;
        int blocksPerCol;
        int blocks;
        long[] blockOffsets;
        long[] blockByteCounts;
        boolean geographic = true;
        boolean bottomUp;
        double west;
        double south;
        double east;
        double north;
        double resX;
        double resY;
        GdalMetadata metadata;
        boolean hasNodata;
        float noData;
    }

    /**
     * Validates one IFD and builds the image, reproducing {@code GTiffDataset::nextGrid}
     * ({@code 9.8.1:src/grids.cpp:1089-1334}) check for check, in order.
     *
     * @param gridName the name the grid was requested under, for messages
     * @param ifdIndex 0-based directory index
     * @throws IOException if a required tag is missing or the georeferencing is inconsistent
     * @throws UnsupportedTiffException if the file needs a feature this reader does not implement
     */
    static GeoTiffImage of(TiffFile tiff, TiffDirectory dir, int ifdIndex, String gridName)
            throws IOException {
        Builder b = new Builder();
        b.dir = dir;
        b.file = dir.fileBytes();
        b.order = dir.order();
        b.ifdIndex = ifdIndex;
        b.name = gridName;

        long w = unsignedTag(dir, TiffTags.IMAGE_WIDTH);
        long h = unsignedTag(dir, TiffTags.IMAGE_LENGTH);
        if (w == 0 || h == 0 || w > Integer.MAX_VALUE || h > Integer.MAX_VALUE) {
            throw new IOException("GeoTIFF grid " + gridName + " IFD " + ifdIndex
                    + " has invalid image size " + w + "x" + h);
        }
        // The dimensions were checked; their product never was. This is the one bound the file
        // length cannot supply: readSamples materialises whole width*height planes -- PROJ streams
        // blocks and never does -- and DEFLATE means a small file may legitimately decode to a much
        // larger raster, so the file length is not an upper bound on the allocation. Measured on the
        // code this replaces: a 634-byte file declaring 40000x40000 asked for a 6.4 GB float[]
        // (OutOfMemoryError, an amplification of 10,000,000:1); a 1,290-byte file declaring
        // 65536x65536 made `width * height` exactly 0 and then scattered into it
        // (ArrayIndexOutOfBoundsException: Index -65536 out of bounds for length 0). Both are
        // failures a caller cannot attribute to a grid, and the first is an Error.
        GridExtents.checkedCount("GeoTIFF grid " + gridName + " IFD " + ifdIndex + " sample plane",
                w, h, Float.BYTES, 0L, GridExtents.maxDecodedBytes(), "the decoded-grid budget");
        b.width = (int) w;
        b.height = (int) h;

        if (!dir.has(TiffTags.SAMPLES_PER_PIXEL)) {
            throw new IOException("GeoTIFF grid " + gridName + " IFD " + ifdIndex
                    + " is missing the SamplesPerPixel tag");
        }
        b.samplesPerPixel = dir.intValue(TiffTags.SAMPLES_PER_PIXEL, 0);
        if (b.samplesPerPixel <= 0) {
            throw new IOException("GeoTIFF grid " + gridName + " IFD " + ifdIndex
                    + " has invalid SamplesPerPixel " + b.samplesPerPixel);
        }
        if (!dir.has(TiffTags.BITS_PER_SAMPLE)) {
            throw new IOException("GeoTIFF grid " + gridName + " IFD " + ifdIndex
                    + " is missing the BitsPerSample tag");
        }
        b.bitsPerSample = dir.intValue(TiffTags.BITS_PER_SAMPLE, 0);
        if (!dir.has(TiffTags.PLANAR_CONFIG)) {
            throw new IOException("GeoTIFF grid " + gridName + " IFD " + ifdIndex
                    + " is missing the PlanarConfig tag");
        }
        b.planarConfig = dir.intValue(TiffTags.PLANAR_CONFIG, 0);
        // Presence was checked; the value was not, and TIFF defines exactly two. PlanarConfig=3 was
        // the hole through which every other bound on this IFD leaked: `strideSamples` below is
        // `CONTIG ? samplesPerPixel : 1`, so a third value left SamplesPerPixel out of the
        // MAX_BLOCK_BYTES arithmetic entirely, while `expectedBlocks` is
        // `SEPARATE ? blocks * samplesPerPixel : blocks`, so it left it out of the strip-count check
        // too. Measured on the code this replaces: a 358-byte file with PlanarConfig=3 and
        // SamplesPerPixel=2^28 reached GdalMetadata.parse and asked for two double[268435456] --
        // 4 GB, an OutOfMemoryError from 358 bytes. A 4096x4096 file with PlanarConfig=3 and 8
        // samples decoded with NO exception at all, returning a 16,777,216-element plane of zeros
        // from a file containing no pixel bytes: a geoid of exactly 0 m, which is the plausible
        // wrong answer this project exists to prevent.
        if (b.planarConfig != TiffTags.PLANARCONFIG_CONTIG
                && b.planarConfig != TiffTags.PLANARCONFIG_SEPARATE) {
            throw new UnsupportedTiffException("GeoTIFF grid " + gridName + " IFD " + ifdIndex
                    + " has PlanarConfig=" + b.planarConfig + "; TIFF defines only CONTIG (1) and "
                    + "SEPARATE (2), and a third value would bypass both the block-size and the "
                    + "strip-count bound");
        }
        if (!dir.has(TiffTags.SAMPLE_FORMAT)) {
            throw new IOException("GeoTIFF grid " + gridName + " IFD " + ifdIndex
                    + " is missing the SampleFormat tag");
        }
        b.sampleFormat = dir.intValue(TiffTags.SAMPLE_FORMAT, 0);

        // grids.cpp:1133-1151. Exactly six legal combinations; the message is upstream's.
        boolean legal =
                (b.sampleFormat == TiffTags.SAMPLEFORMAT_INT && b.bitsPerSample == 16)
                        || (b.sampleFormat == TiffTags.SAMPLEFORMAT_UINT && b.bitsPerSample == 16)
                        || (b.sampleFormat == TiffTags.SAMPLEFORMAT_INT && b.bitsPerSample == 32)
                        || (b.sampleFormat == TiffTags.SAMPLEFORMAT_UINT && b.bitsPerSample == 32)
                        || (b.sampleFormat == TiffTags.SAMPLEFORMAT_IEEEFP && b.bitsPerSample == 32)
                        || (b.sampleFormat == TiffTags.SAMPLEFORMAT_IEEEFP && b.bitsPerSample == 64);
        if (!legal) {
            throw new UnsupportedTiffException("GeoTIFF grid " + gridName + " IFD " + ifdIndex
                    + " has an unsupported combination of SampleFormat (" + b.sampleFormat
                    + ") and BitsPerSample (" + b.bitsPerSample + "); PROJ reads only int16, uint16, "
                    + "int32, uint32, float32 and float64");
        }

        int photometric = dir.intValue(TiffTags.PHOTOMETRIC, TiffTags.PHOTOMETRIC_MINISBLACK);
        if (photometric != TiffTags.PHOTOMETRIC_MINISBLACK) {
            throw new UnsupportedTiffException("GeoTIFF grid " + gridName + " IFD " + ifdIndex
                    + " has Photometric=" + photometric + "; only MINISBLACK (1) is supported");
        }

        b.compression = dir.intValue(TiffTags.COMPRESSION, TiffTags.COMPRESSION_NONE);
        if (b.compression != TiffTags.COMPRESSION_NONE
                && b.compression != TiffTags.COMPRESSION_ADOBE_DEFLATE
                && b.compression != TiffTags.COMPRESSION_DEFLATE) {
            throw new UnsupportedTiffException("GeoTIFF grid " + gridName + " IFD " + ifdIndex
                    + " uses " + TiffCodec.compressionName(b.compression) + " compression; proj4j "
                    + "reads only uncompressed and DEFLATE GeoTIFF grids");
        }
        b.predictor = dir.intValue(TiffTags.PREDICTOR, TiffTags.PREDICTOR_NONE);

        b.subfileType = dir.intValue(TiffTags.SUBFILE_TYPE, 0) & 0xffffffffL;

        b.tiled = dir.has(TiffTags.TILE_WIDTH) && dir.has(TiffTags.TILE_LENGTH);
        if (b.tiled) {
            b.blockWidth = dir.intValue(TiffTags.TILE_WIDTH, 0);
            b.blockHeight = dir.intValue(TiffTags.TILE_LENGTH, 0);
            if (b.blockWidth <= 0 || b.blockHeight <= 0) {
                throw new IOException("GeoTIFF grid " + gridName + " IFD " + ifdIndex
                        + " has invalid tile size " + b.blockWidth + "x" + b.blockHeight);
            }
            b.blockOffsets = dir.longs(TiffTags.TILE_OFFSETS);
            b.blockByteCounts = dir.longs(TiffTags.TILE_BYTE_COUNTS);
        } else {
            b.blockWidth = b.width;
            // libtiff defaults RowsPerStrip to 0xFFFFFFFF; PROJ then clamps it to the image height.
            long rowsPerStrip = dir.has(TiffTags.ROWS_PER_STRIP)
                    ? unsignedTag(dir, TiffTags.ROWS_PER_STRIP)
                    : 0xffffffffL;
            if (rowsPerStrip <= 0 || rowsPerStrip > b.height) {
                rowsPerStrip = b.height;
            }
            b.blockHeight = (int) rowsPerStrip;
            b.blockOffsets = dir.longs(TiffTags.STRIP_OFFSETS);
            b.blockByteCounts = dir.longs(TiffTags.STRIP_BYTE_COUNTS);
        }
        if (b.blockOffsets == null || b.blockByteCounts == null) {
            throw new IOException("GeoTIFF grid " + gridName + " IFD " + ifdIndex + " is missing its "
                    + (b.tiled ? "TileOffsets/TileByteCounts" : "StripOffsets/StripByteCounts")
                    + " tags");
        }
        // width and height are each at most GridExtents.MAX_EXTENT by now, so these two cannot
        // overflow; their product still can, so it is formed in long and narrowed only after the
        // strip/tile count has been checked against the arrays the file actually carries.
        b.blocksPerRow = (b.width + b.blockWidth - 1) / b.blockWidth;
        b.blocksPerCol = (b.height + b.blockHeight - 1) / b.blockHeight;
        long blockCount = (long) b.blocksPerRow * b.blocksPerCol;

        // ONE definition of the sample stride, used by both the block-size bound here and the
        // scatter loop in readSamples. They used to be computed separately and by different
        // expressions -- `CONTIG ? samplesPerPixel : 1` here against a three-line
        // separate/contig/stride cascade there -- which agreed for PlanarConfig 1 and 2 and
        // disagreed by a factor of samplesPerPixel for anything else. The tag is now validated, so
        // "anything else" no longer exists, and the single field means the two cannot drift apart
        // again.
        b.strideSamples = b.planarConfig == TiffTags.PLANARCONFIG_CONTIG ? b.samplesPerPixel : 1;
        long blockBytes =
                (long) b.blockWidth * b.blockHeight * b.strideSamples * (b.bitsPerSample / 8);
        if (blockBytes == 0 || blockBytes > MAX_BLOCK_BYTES) {
            throw new UnsupportedTiffException("GeoTIFF grid " + gridName + " IFD " + ifdIndex
                    + " has an unsupported block size of " + blockBytes + " bytes");
        }
        // `blocks * samplesPerPixel` was a plain int multiplication, and a negative product walks
        // straight through the length test below.
        long expectedBlocks = b.planarConfig == TiffTags.PLANARCONFIG_SEPARATE
                ? blockCount * b.samplesPerPixel : blockCount;
        if (b.blockOffsets.length < expectedBlocks || b.blockByteCounts.length < expectedBlocks) {
            throw new IOException("GeoTIFF grid " + gridName + " IFD " + ifdIndex + " declares "
                    + expectedBlocks + " " + (b.tiled ? "tiles" : "strips") + " but carries "
                    + b.blockOffsets.length + " offsets and " + b.blockByteCounts.length
                    + " byte counts");
        }
        b.blocks = (int) blockCount;

        readGeoreferencing(b, dir, gridName, ifdIndex);

        b.metadata = GdalMetadata.parse(dir.ascii(TiffTags.GDAL_METADATA), b.samplesPerPixel);
        String nodataText = dir.ascii(TiffTags.GDAL_NODATA);
        if (nodataText != null) {
            double parsed = GdalMetadata.parseDoubleOrNaN(nodataText);
            if (!Double.isNaN(parsed)) {
                b.noData = (float) parsed;
                b.hasNodata = true;
            }
        }
        return new GeoTiffImage(b);
    }

    /**
     * The first value of an integer tag, zero-extended to {@code long}, or {@code 0} if absent.
     * {@code ImageWidth} and {@code RowsPerStrip} are {@code LONG} in real files and must not be
     * squeezed through an {@code int} on the way to their range check.
     */
    private static long unsignedTag(TiffDirectory dir, int tag) throws IOException {
        long[] v = dir.longs(tag);
        return v == null || v.length == 0 ? 0L : v[0];
    }

    /**
     * {@code grids.cpp:1184-1317}: the GeoKey scan, then the {@code GeoTransformationMatrix} or
     * {@code GeoPixelScale} + {@code GeoTiePoints} pair, then {@code RasterPixelIsArea}'s half-pixel
     * shift, then the consistency test.
     */
    private static void readGeoreferencing(Builder b, TiffDirectory dir, String gridName,
                                          int ifdIndex) throws IOException {
        boolean pixelIsArea = false;
        long[] geokeys = dir.longs(TiffTags.GEO_KEY_DIRECTORY);
        if (geokeys != null) {
            if (geokeys.length < 4 || (geokeys.length % 4) != 0) {
                throw new IOException("GeoTIFF grid " + gridName + " IFD " + ifdIndex
                        + " has " + geokeys.length + " values in its GeoKeyDirectory tag, which must "
                        + "be a non-zero multiple of 4");
            }
            if (geokeys[0] != 1) {
                throw new UnsupportedTiffException("GeoTIFF grid " + gridName + " IFD " + ifdIndex
                        + " declares GeoTIFF major version " + geokeys[0] + "; only 1 is supported");
            }
            // geokeys[1]/[2] are the minor/revision; PROJ only logs about them, so neither do we.
            final int gtModelTypeGeoKey = 1024;
            final int modelTypeProjected = 1;
            final int modelTypeGeographic = 2;
            final int gtRasterTypeGeoKey = 1025;
            final int rasterPixelIsArea = 1;
            for (int i = 4; i + 3 < geokeys.length; i += 4) {
                if (geokeys[i] == gtModelTypeGeoKey) {
                    if (geokeys[i + 3] == modelTypeProjected) {
                        b.geographic = false;
                    } else if (geokeys[i + 3] != modelTypeGeographic) {
                        throw new UnsupportedTiffException("GeoTIFF grid " + gridName + " IFD "
                                + ifdIndex + " has GTModelTypeGeoKey=" + geokeys[i + 3]
                                + "; only ModelTypeGeographic (2) and ModelTypeProjected (1) are "
                                + "supported");
                    }
                } else if (geokeys[i] == gtRasterTypeGeoKey) {
                    if (geokeys[i + 3] == rasterPixelIsArea) {
                        pixelIsArea = true;
                    }
                }
            }
        }

        double hRes;
        double vRes;
        double west;
        double north;
        double[] matrix = dir.doubles(TiffTags.GEO_TRANS_MATRIX);
        if (matrix != null && matrix.length == 16) {
            if (matrix[1] != 0 || matrix[4] != 0) {
                throw new UnsupportedTiffException("GeoTIFF grid " + gridName + " IFD " + ifdIndex
                        + " has rotational terms in its GeoTransformationMatrix tag (" + matrix[1]
                        + ", " + matrix[4] + "); only axis-aligned grids are supported");
            }
            west = matrix[3];
            hRes = matrix[0];
            north = matrix[7];
            // Negated to simulate the GeoPixelScale convention, exactly as upstream comments.
            vRes = -matrix[5];
        } else {
            double[] scale = dir.doubles(TiffTags.GEO_PIXEL_SCALE);
            if (scale == null) {
                throw new IOException("GeoTIFF grid " + gridName + " IFD " + ifdIndex
                        + " has neither a GeoTransformationMatrix nor a GeoPixelScale tag");
            }
            if (scale.length != 3) {
                throw new IOException("GeoTIFF grid " + gridName + " IFD " + ifdIndex + " has "
                        + scale.length + " values in its GeoPixelScale tag, expected 3");
            }
            hRes = scale[0];
            vRes = scale[1];
            double[] tie = dir.doubles(TiffTags.GEO_TIE_POINTS);
            if (tie == null) {
                throw new IOException("GeoTIFF grid " + gridName + " IFD " + ifdIndex
                        + " has no GeoTiePoints tag");
            }
            if (tie.length != 6) {
                throw new IOException("GeoTIFF grid " + gridName + " IFD " + ifdIndex + " has "
                        + tie.length + " values in its GeoTiePoints tag, expected 6");
            }
            west = tie[3] - tie[0] * hRes;
            north = tie[4] + tie[1] * vRes;
        }

        if (pixelIsArea) {
            west += 0.5 * hRes;
            north -= 0.5 * vRes;
        }

        double mul = b.geographic ? DEG_TO_RAD : 1.0;
        b.west = west * mul;
        b.north = north * mul;
        b.resX = hRes * mul;
        b.resY = Math.abs(vRes) * mul;
        b.east = (west + hRes * (b.width - 1)) * mul;
        b.south = (north - vRes * (b.height - 1)) * mul;
        if (vRes < 0) {
            double t = b.north;
            b.north = b.south;
            b.south = t;
        }
        b.bottomUp = vRes < 0;

        boolean ok = (!b.geographic
                || (Math.abs(b.west) <= 4 * Math.PI && Math.abs(b.east) <= 4 * Math.PI
                && Math.abs(b.north) <= Math.PI + 1e-5 && Math.abs(b.south) <= Math.PI + 1e-5))
                && b.west < b.east && b.south < b.north && b.resX > 1e-10 && b.resY > 1e-10;
        if (!ok) {
            throw new IOException("GeoTIFF grid " + gridName + " IFD " + ifdIndex
                    + " has inconsistent georeferencing: west=" + b.west + " east=" + b.east
                    + " south=" + b.south + " north=" + b.north + " resX=" + b.resX
                    + " resY=" + b.resY + " (radians, geographic=" + b.geographic + ")");
        }
    }

    // --- Accessors ---------------------------------------------------------------------------

    /** The grid name this image belongs to, plus its IFD index when the file has more than one. */
    public String name() {
        return name;
    }

    /** 0-based IFD index. */
    public int ifdIndex() {
        return ifdIndex;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int samplesPerPixel() {
        return samplesPerPixel;
    }

    /** The {@code SubfileType} tag, {@code 0} when absent. Non-zero and not {@code 2} is an overview. */
    public long subfileType() {
        return subfileType;
    }

    /**
     * {@code false} for a {@code ModelTypeProjected} grid. proj4j's shift machinery is geographic-only,
     * so a projected grid is rejected by the caller with a message saying so — the same place PROJ
     * rejects it, {@code pj_hgrid_value}'s <em>"Can only handle grids referenced in a geographic
     * CRS"</em>.
     */
    public boolean isGeographic() {
        return geographic;
    }

    /** Radians for a geographic grid, native units otherwise. */
    public double west() {
        return west;
    }

    public double south() {
        return south;
    }

    public double east() {
        return east;
    }

    public double north() {
        return north;
    }

    public double resX() {
        return resX;
    }

    public double resY() {
        return resY;
    }

    /** {@code true} iff the first image row is the southernmost, i.e. a negative vertical scale. */
    public boolean isBottomUp() {
        return bottomUp;
    }

    /** A {@code GDAL_METADATA} item, or the empty string. Never {@code null}. */
    public String metadataItem(String key, int sample) {
        return metadata.item(key, sample);
    }

    /** A grid-level {@code GDAL_METADATA} item, or the empty string. */
    public String metadataItem(String key) {
        return metadata.item(key, GdalMetadata.GRID_LEVEL);
    }

    /** {@code GTiffGrid::isNodata}: the declared sentinel, or any NaN. */
    public boolean isNodata(float value) {
        return (hasNodata && value == noData) || Double.isNaN(value);
    }

    public boolean hasNodata() {
        return hasNodata;
    }

    public float noDataValue() {
        return noData;
    }

    /**
     * Reads whole sample planes, south row first.
     *
     * <p>Each returned array is {@code width * height} long, indexed {@code y * width + x} with
     * {@code y = 0} the <strong>southernmost</strong> row — the layout every other proj4j grid reader
     * uses, and the one {@code Grid.nad_intr} and {@code VerticalGrid.valueAt} index into. The
     * top-down-versus-bottom-up flip is applied here, once, from
     * {@code yTIFF = bottomUp ? y : height - 1 - y} ({@code grids.cpp:668}).
     *
     * <p>Blocks are decoded once each and scattered into every requested plane, so a
     * {@code PLANARCONFIG_CONTIG} two-band horizontal grid inflates its strips once rather than twice.
     *
     * @param samples the sample indices wanted, in the order the result should be in
     * @return one {@code float[width * height]} per requested sample
     * @throws IOException if a block is truncated, corrupt, or needs an unimplemented codec
     */
    public float[][] readSamples(int[] samples) throws IOException {
        for (int i = 0; i < samples.length; i++) {
            if (samples[i] < 0 || samples[i] >= samplesPerPixel) {
                throw new IOException("GeoTIFF grid " + name + " IFD " + ifdIndex
                        + ": sample index " + samples[i] + " is outside 0.."
                        + (samplesPerPixel - 1));
            }
        }
        float[][] out = new float[samples.length][];
        for (int i = 0; i < samples.length; i++) {
            out[i] = new float[width * height];
        }
        boolean separate = planarConfig == TiffTags.PLANARCONFIG_SEPARATE && samplesPerPixel > 1;
        boolean contig = planarConfig == TiffTags.PLANARCONFIG_CONTIG && samplesPerPixel > 1;
        // strideSamples is the field computed in of(), not a second local derivation. `rowBytes *
        // rowsThisBlock` in block() is exactly the `blockBytes` bounded there by MAX_BLOCK_BYTES,
        // which is only true because both use the same stride.
        int rowBytes = blockWidth * strideSamples * bytesPerSample;

        for (int by = 0; by < blocksPerCol; by++) {
            int rowsThisBlock = tiled ? blockHeight
                    : Math.min(blockHeight, height - by * blockHeight);
            for (int bx = 0; bx < blocksPerRow; bx++) {
                int logicalBlock = by * blocksPerRow + bx;
                if (separate) {
                    for (int i = 0; i < samples.length; i++) {
                        byte[] buf = block(logicalBlock + samples[i] * blocks, rowsThisBlock,
                                rowBytes, strideSamples);
                        scatter(buf, out[i], samples[i], bx, by, rowsThisBlock, 1, false);
                    }
                } else {
                    byte[] buf = block(logicalBlock, rowsThisBlock, rowBytes, strideSamples);
                    for (int i = 0; i < samples.length; i++) {
                        scatter(buf, out[i], samples[i], bx, by, rowsThisBlock, strideSamples,
                                contig);
                    }
                }
            }
        }
        return out;
    }

    /** Convenience for a single sample. */
    public float[] readSample(int sample) throws IOException {
        return readSamples(new int[]{sample})[0];
    }

    private void scatter(byte[] buf, float[] out, int sample, int bx, int by, int rowsThisBlock,
                         int strideSamples, boolean contig) {
        ByteBuffer bb = ByteBuffer.wrap(buf).order(order);
        for (int yy = 0; yy < rowsThisBlock; yy++) {
            int yTiff = by * blockHeight + yy;
            if (yTiff >= height) {
                break;
            }
            int y = bottomUp ? yTiff : height - 1 - yTiff;
            int rowBase = y * width;
            for (int xx = 0; xx < blockWidth; xx++) {
                int x = bx * blockWidth + xx;
                if (x >= width) {
                    break;
                }
                int offsetInBlock = xx + yy * blockWidth;
                if (contig) {
                    offsetInBlock = offsetInBlock * strideSamples + sample;
                }
                out[rowBase + x] = readValue(bb, offsetInBlock, sample);
            }
        }
    }

    /**
     * Decodes one physical block and undoes its predictor. Not cached: {@link #readSamples} visits
     * each block exactly once per plane it contributes to.
     */
    private byte[] block(int physicalBlock, int rowsThisBlock, int rowBytes, int strideSamples)
            throws IOException {
        if (physicalBlock < 0 || physicalBlock >= blockOffsets.length) {
            throw new IOException("GeoTIFF grid " + name + " IFD " + ifdIndex + " asks for "
                    + (tiled ? "tile " : "strip ") + physicalBlock + " of " + blockOffsets.length);
        }
        int expected = rowBytes * rowsThisBlock;
        byte[] buf = TiffCodec.decodeBlock(file, blockOffsets[physicalBlock],
                blockByteCounts[physicalBlock], compression, expected);
        TiffCodec.undoPredictor(buf, rowsThisBlock, rowBytes, predictor, bytesPerSample,
                strideSamples, sampleFormat, order);
        return buf;
    }

    /**
     * {@code GTiffGrid::readValue}: the raw sample, then {@code value * scale + offset} — but
     * <em>only</em> when the value is not the nodata sentinel and a scale/offset was actually declared.
     * PROJ's guard is {@code sample < m_adfScale.size()}, and that vector stays empty until a
     * {@code role="scale"} or {@code role="offset"} item appears, so an absent scale means the raw
     * value rather than a multiply by one.
     */
    private float readValue(ByteBuffer bb, int offsetInBlock, int sample) {
        double raw;
        int p = offsetInBlock * bytesPerSample;
        if (p < 0 || p + bytesPerSample > bb.limit()) {
            // Past the decoded data: libtiff leaves such bytes zero, and so does decodeBlock.
            return 0.0f;
        }
        if (sampleFormat == TiffTags.SAMPLEFORMAT_IEEEFP) {
            raw = bitsPerSample == 32 ? bb.getFloat(p) : bb.getDouble(p);
        } else if (bitsPerSample == 16) {
            raw = sampleFormat == TiffTags.SAMPLEFORMAT_INT
                    ? bb.getShort(p) : (bb.getShort(p) & 0xffff);
        } else {
            raw = sampleFormat == TiffTags.SAMPLEFORMAT_INT
                    ? bb.getInt(p) : (bb.getInt(p) & 0xffffffffL);
        }
        float asFloat = (float) raw;
        if ((!hasNodata || asFloat != noData) && metadata.hasScaleOffset()) {
            return (float) (raw * metadata.scaleFor(sample) + metadata.offsetFor(sample));
        }
        return asFloat;
    }

    @Override
    public String toString() {
        return "GeoTiffImage[" + name + " IFD " + ifdIndex + "; " + width + "x" + height + "; "
                + samplesPerPixel + " sample(s); " + bitsPerSample + "-bit fmt " + sampleFormat
                + "; " + (tiled ? "tiled " : "strips ") + blockWidth + "x" + blockHeight
                + "; compression " + compression + "; predictor " + predictor
                + "; bottomUp=" + bottomUp + "]";
    }
}

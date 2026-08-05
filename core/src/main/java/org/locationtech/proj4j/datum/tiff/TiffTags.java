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

/**
 * The <em>complete</em> set of TIFF tags, field types and enumerated values that PROJ 9.8.1's grid
 * reader looks at, and nothing else.
 *
 * <p>Every constant here is asked for by name somewhere in {@code 9.8.1:src/grids.cpp}
 * ({@code GTiffDataset::nextGrid}, the {@code GTiffGrid} constructor and {@code GTiffGrid::valueAt})
 * or is needed to decode the pixel data those functions ask libtiff for. A GeoTIFF carrying anything
 * else carries it unread; a GeoTIFF <em>requiring</em> anything else is rejected by name — see
 * {@link UnsupportedTiffException}.
 *
 * <p>The tag numbers for the GeoTIFF and GDAL private tags are the ones PROJ declares itself, as
 * {@code constexpr uint16_t} at {@code grids.cpp:395-405}, because libtiff does not know them and
 * PROJ registers them with a one-time tag extender.
 */
final class TiffTags {

    // --- Baseline TIFF tags -------------------------------------------------------------------

    /** {@code NewSubfileType}. PROJ reads it to skip reduced-resolution overviews. */
    static final int SUBFILE_TYPE = 254;
    static final int IMAGE_WIDTH = 256;
    static final int IMAGE_LENGTH = 257;
    static final int BITS_PER_SAMPLE = 258;
    static final int COMPRESSION = 259;
    static final int PHOTOMETRIC = 262;
    static final int STRIP_OFFSETS = 273;
    static final int SAMPLES_PER_PIXEL = 277;
    static final int ROWS_PER_STRIP = 278;
    static final int STRIP_BYTE_COUNTS = 279;
    static final int PLANAR_CONFIG = 284;
    static final int PREDICTOR = 317;
    static final int TILE_WIDTH = 322;
    static final int TILE_LENGTH = 323;
    static final int TILE_OFFSETS = 324;
    static final int TILE_BYTE_COUNTS = 325;
    static final int SAMPLE_FORMAT = 339;

    // --- GeoTIFF tags, per grids.cpp:395-400 -------------------------------------------------

    static final int GEO_PIXEL_SCALE = 33550;
    static final int GEO_TIE_POINTS = 33922;
    static final int GEO_TRANS_MATRIX = 34264;
    static final int GEO_KEY_DIRECTORY = 34735;

    // --- GDAL private tags, per grids.cpp:403-404 -------------------------------------------

    static final int GDAL_METADATA = 42112;
    static final int GDAL_NODATA = 42113;

    // --- Field types (TIFF 6.0 section 2, plus the BigTIFF additions) ------------------------

    static final int TYPE_BYTE = 1;
    static final int TYPE_ASCII = 2;
    static final int TYPE_SHORT = 3;
    static final int TYPE_LONG = 4;
    static final int TYPE_RATIONAL = 5;
    static final int TYPE_SBYTE = 6;
    static final int TYPE_UNDEFINED = 7;
    static final int TYPE_SSHORT = 8;
    static final int TYPE_SLONG = 9;
    static final int TYPE_SRATIONAL = 10;
    static final int TYPE_FLOAT = 11;
    static final int TYPE_DOUBLE = 12;
    static final int TYPE_LONG8 = 16;
    static final int TYPE_SLONG8 = 17;
    static final int TYPE_IFD8 = 18;

    /** Byte width of each field type, indexed by type code. Zero means "unknown type". */
    private static final int[] TYPE_SIZES = {
            0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8, 4, 0, 0, 8, 8, 8};

    // --- Enumerated values -------------------------------------------------------------------

    static final int COMPRESSION_NONE = 1;
    /** {@code COMPRESSION_ADOBE_DEFLATE}: zlib stream, the value every PROJ grid uses. */
    static final int COMPRESSION_ADOBE_DEFLATE = 8;
    /** {@code COMPRESSION_DEFLATE}: the older private code for the same zlib stream. */
    static final int COMPRESSION_DEFLATE = 32946;

    static final int PREDICTOR_NONE = 1;
    static final int PREDICTOR_HORIZONTAL = 2;
    static final int PREDICTOR_FLOATING_POINT = 3;

    static final int PHOTOMETRIC_MINISBLACK = 1;

    static final int PLANARCONFIG_CONTIG = 1;
    static final int PLANARCONFIG_SEPARATE = 2;

    static final int SAMPLEFORMAT_UINT = 1;
    static final int SAMPLEFORMAT_INT = 2;
    static final int SAMPLEFORMAT_IEEEFP = 3;

    /** {@code FILETYPE_PAGE}, the one non-zero {@code SubfileType} PROJ tolerates. */
    static final int FILETYPE_PAGE = 2;

    private TiffTags() {
    }

    /**
     * @param type a TIFF field type code
     * @return the byte width of one value of that type, or {@code 0} if the type is unknown
     */
    static int sizeOf(int type) {
        return type >= 0 && type < TYPE_SIZES.length ? TYPE_SIZES[type] : 0;
    }

    /** A human-readable name for a tag, for error messages. Falls back to the number. */
    static String nameOf(int tag) {
        switch (tag) {
            case SUBFILE_TYPE: return "SubfileType";
            case IMAGE_WIDTH: return "ImageWidth";
            case IMAGE_LENGTH: return "ImageLength";
            case BITS_PER_SAMPLE: return "BitsPerSample";
            case COMPRESSION: return "Compression";
            case PHOTOMETRIC: return "Photometric";
            case STRIP_OFFSETS: return "StripOffsets";
            case SAMPLES_PER_PIXEL: return "SamplesPerPixel";
            case ROWS_PER_STRIP: return "RowsPerStrip";
            case STRIP_BYTE_COUNTS: return "StripByteCounts";
            case PLANAR_CONFIG: return "PlanarConfig";
            case PREDICTOR: return "Predictor";
            case TILE_WIDTH: return "TileWidth";
            case TILE_LENGTH: return "TileLength";
            case TILE_OFFSETS: return "TileOffsets";
            case TILE_BYTE_COUNTS: return "TileByteCounts";
            case SAMPLE_FORMAT: return "SampleFormat";
            case GEO_PIXEL_SCALE: return "GeoPixelScale";
            case GEO_TIE_POINTS: return "GeoTiePoints";
            case GEO_TRANS_MATRIX: return "GeoTransformationMatrix";
            case GEO_KEY_DIRECTORY: return "GeoKeyDirectory";
            case GDAL_METADATA: return "GDAL_METADATA";
            case GDAL_NODATA: return "GDAL_NODATA";
            default: return "tag " + tag;
        }
    }
}

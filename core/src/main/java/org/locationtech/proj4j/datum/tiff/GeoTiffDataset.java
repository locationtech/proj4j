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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A geodetic GeoTIFF file, as a sequence of {@link GeoTiffImage}s: PROJ's {@code GTiffDataset}.
 *
 * <p>This is the only public entry point into the TIFF subset reader. It is deliberately narrow:
 * hand it the bytes of a file, get back the images. It resolves nothing, opens nothing and caches
 * nothing — resolution goes through {@code org.locationtech.proj4j.resource} and caching through
 * {@code datum.GridCache}, and this class must not acquire opinions about either.
 *
 * <h2>Enumeration semantics, which are load-bearing</h2>
 * <p>PROJ's {@code nextGrid()} logs and returns {@code nullptr} for an IFD it cannot make sense of,
 * and every caller treats that as <em>"stop enumerating"</em> — fatal at IFD 0, a silent truncation of
 * the list afterwards ({@code GTiffVGridShiftSet::open}, {@code GTiffHGridShiftSet::open}). That
 * asymmetry is reproduced here rather than smoothed over, because it is what makes
 * {@code tests/test_vgrid_invalid_channel_type.tif} an error and
 * {@code tests/test_hgrid_extra_ifd_with_other_info.tif} a success.
 *
 * <p>The reason an IFD is dropped is retained on {@link #truncationReason()} so a caller can put it in
 * an exception message instead of losing it, which is the one place this port deviates from upstream —
 * PROJ writes it to a log nobody reads.
 *
 * @since 1.5
 */
public final class GeoTiffDataset {

    private final String name;
    private final List<GeoTiffImage> images;
    private final String truncationReason;
    private final boolean bigTiff;

    private GeoTiffDataset(String name, List<GeoTiffImage> images, String truncationReason,
                           boolean bigTiff) {
        this.name = name;
        this.images = images;
        this.truncationReason = truncationReason;
        this.bigTiff = bigTiff;
    }

    /**
     * PROJ's {@code IsTIFF} ({@code 9.8.1:src/grids.cpp:377-385}). Both endian markers crossed with
     * classic ({@code 42}) and BigTIFF ({@code 43}).
     *
     * @param header at least the first four bytes of a candidate file
     * @param length how many bytes of {@code header} are valid
     * @return {@code true} if PROJ would dispatch this file to its TIFF reader
     */
    public static boolean isTiff(byte[] header, int length) {
        return TiffFile.isTiff(header, length);
    }

    /**
     * Parses a whole GeoTIFF held in memory.
     *
     * @param bytes    the complete file
     * @param gridName the name it was requested under, used in every message
     * @return a dataset with at least one image
     * @throws IOException if the file is not a TIFF, is truncated, or its first IFD is unusable
     */
    public static GeoTiffDataset open(byte[] bytes, String gridName) throws IOException {
        TiffFile tiff = TiffFile.open(bytes);
        List<GeoTiffImage> found = new ArrayList<GeoTiffImage>();
        String reason = null;
        List<TiffDirectory> dirs = tiff.directories();
        for (int i = 0; i < dirs.size(); i++) {
            GeoTiffImage image;
            try {
                image = GeoTiffImage.of(tiff, dirs.get(i), i, gridName);
            } catch (IOException e) {
                if (i == 0) {
                    throw e;
                }
                // Upstream: pj_log then `break`, keeping what was found. Keep the reason too.
                reason = e.getMessage();
                break;
            }
            found.add(image);
        }
        if (found.isEmpty()) {
            throw new IOException("GeoTIFF grid " + gridName + " has no usable image");
        }
        return new GeoTiffDataset(gridName, Collections.unmodifiableList(found), reason,
                tiff.isBigTiff());
    }

    /** The name the file was requested under. */
    public String name() {
        return name;
    }

    /** Every usable image, in IFD order. Never empty. */
    public List<GeoTiffImage> images() {
        return images;
    }

    /** {@code true} for a BigTIFF ({@code 43}) container. Provenance, not behaviour. */
    public boolean isBigTiff() {
        return bigTiff;
    }

    /**
     * Why enumeration stopped early, or {@code null} if every IFD in the chain was read.
     *
     * @return the message from the exception raised by the first unusable IFD after IFD 0
     */
    public String truncationReason() {
        return truncationReason;
    }

    @Override
    public String toString() {
        return "GeoTiffDataset[" + name + "; " + images.size() + " image(s)"
                + (bigTiff ? "; BigTIFF" : "")
                + (truncationReason == null ? "" : "; truncated: " + truncationReason) + "]";
    }
}

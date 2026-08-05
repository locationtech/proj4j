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

/**
 * A reader for the <em>geodetic GeoTIFF</em> grid profile, and for nothing else.
 *
 * <h2>Scope</h2>
 * <p>This package implements the documented subset of TIFF that PROJ 9.8.1 reads for grids — the
 * profile specified in PROJ's {@code docs/source/specifications/geodetictiffgrids.rst} and consumed by
 * {@code src/grids.cpp}'s {@code GTiffDataset} / {@code GTiffGrid} / {@code GTiffHGridShiftSet} /
 * {@code GTiffVGridShiftSet}. It is <strong>not</strong> a general TIFF decoder and must not become
 * one.
 *
 * <p>What is read:
 * <ul>
 *   <li>Classic TIFF and BigTIFF, little- and big-endian, i.e. all four signatures PROJ's
 *       {@code IsTIFF} accepts.</li>
 *   <li>Strips and tiles, {@code PLANARCONFIG_CONTIG} and {@code PLANARCONFIG_SEPARATE}.</li>
 *   <li>{@code COMPRESSION_NONE} and DEFLATE (tag values {@code 8} and {@code 32946}), via
 *       {@link java.util.zip.Inflater} — no third-party dependency, which is a hard constraint on
 *       proj4j core.</li>
 *   <li>Predictors {@code 1}, {@code 2} (horizontal differencing) and {@code 3} (floating point).
 *       Predictor {@code 3} is mandatory in practice: every one of PROJ's seven US grids uses it.</li>
 *   <li>Sample types {@code int16}, {@code uint16}, {@code int32}, {@code uint32}, {@code float32},
 *       {@code float64} — PROJ's six-member {@code TIFFDataType} enum, no more.</li>
 *   <li>Georeferencing from {@code GeoTransformationMatrix}, or {@code GeoPixelScale} +
 *       {@code GeoTiePoints}; {@code GTModelTypeGeoKey}; {@code RasterPixelIsArea}'s half-pixel shift;
 *       and bottom-up images signalled by a negative vertical scale.</li>
 *   <li>{@code GDAL_METADATA} and {@code GDAL_NODATA}, which is where {@code TYPE}, {@code grid_name},
 *       {@code parent_grid_name}, per-band {@code DESCRIPTION}, {@code UNITTYPE},
 *       {@code positive_value} and the {@code scale}/{@code offset} roles live.</li>
 * </ul>
 *
 * <p>What is refused, by name, with {@link org.locationtech.proj4j.datum.tiff.UnsupportedTiffException}:
 * every other codec (LZW, PackBits, JPEG, JPEG 2000, LERC, ZSTD, WEBP, CCITT), every other sample
 * width including 8-bit, non-{@code MINISBLACK} photometric interpretations, rotational terms in the
 * transformation matrix, GeoTIFF major versions other than 1, and model types other than geographic or
 * projected. Refusing loudly is the point: a grid this reader cannot decode must never be reported as a
 * successful transform with a zero shift.
 *
 * <h2>Thread safety</h2>
 * <p>Everything here is immutable after construction. Parsing is a pure function of the input bytes,
 * so two threads parsing the same grid produce bit-identical results and
 * {@code org.locationtech.proj4j.datum.GridCache} may keep either.
 *
 * @since 1.5
 */
package org.locationtech.proj4j.datum.tiff;

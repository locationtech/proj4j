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

import java.util.HashMap;
import java.util.Map;

/**
 * The {@code GDAL_METADATA} tag, parsed the way PROJ parses it.
 *
 * <p>This tag carries an XML fragment of {@code <}{@code Item>} elements, and it is where the whole
 * semantic layer of a geodetic GeoTIFF lives: {@code TYPE}, {@code grid_name},
 * {@code parent_grid_name}, per-sample {@code DESCRIPTION} (which is what identifies the
 * latitude/longitude/geoid channel), {@code UNITTYPE}, {@code positive_value}, and the
 * {@code offset}/{@code scale} roles.
 *
 * <h2>Why this is not an XML parser</h2>
 * <p>PROJ does not parse it as XML either. {@code 9.8.1:src/grids.cpp:541-618} is explicitly labelled
 * <em>"Poor-man XML parsing of TIFFTAG_GDAL_METADATA tag. Hopefully good enough for our purposes"</em>
 * and works by {@code strstr("<Item ")}, then {@code strchr('>')}, then {@code strchr('<')}. Ported
 * literally, because the two must agree on the same files:
 * <ul>
 *   <li>The key is the {@code name="..."} attribute. <strong>A {@code <}{@code Item>} without one
 *       aborts the whole scan</strong> — PROJ {@code break}s out of the loop rather than skipping the
 *       element, so any items after it are lost. Reproduced.</li>
 *   <li>The map key is the pair {@code (sample, name)}, with {@code sample = -1} when the
 *       {@code sample="..."} attribute is absent. A grid-level {@code TYPE} and a per-band
 *       {@code DESCRIPTION} therefore never collide.</li>
 *   <li>Values are taken raw, with no entity decoding, because PROJ does none. A {@code &amp;amp;}
 *       in a grid name would reach us as the five literal characters, in both implementations.</li>
 *   <li>The {@code role="offset"} / {@code role="scale"} attributes are read as well as stored,
 *       because {@code readValue} applies them.</li>
 * </ul>
 *
 * <p>Immutable after construction.
 */
final class GdalMetadata {

    /** Sample index meaning "applies to the whole grid, not to one band". */
    static final int GRID_LEVEL = -1;

    private static final String ITEM = "<Item ";
    private static final String NAME_ATTR = "name=\"";
    private static final String SAMPLE_ATTR = "sample=\"";
    private static final String ROLE_ATTR = "role=\"";

    /** Keyed by {@code sample + "\0" + name}; see {@link #key}. */
    private final Map<String, String> items;
    private final double[] scale;
    private final double[] offset;
    private final boolean hasScaleOffset;

    private GdalMetadata(Map<String, String> items, double[] scale, double[] offset,
                         boolean hasScaleOffset) {
        this.items = items;
        this.scale = scale;
        this.offset = offset;
        this.hasScaleOffset = hasScaleOffset;
    }

    /** An empty metadata block, for an IFD with no {@code GDAL_METADATA} tag. */
    static GdalMetadata empty() {
        return new GdalMetadata(new HashMap<String, String>(), null, null, false);
    }

    /**
     * @param xml             the raw tag contents; {@code null} yields {@link #empty()}
     * @param samplesPerPixel the band count, used only to <em>reject</em> a {@code sample} attribute
     *                        outside it — never to size an allocation. See the comment on the
     *                        scale/offset arrays below for why that distinction is the whole fix.
     */
    static GdalMetadata parse(String xml, int samplesPerPixel) {
        Map<String, String> items = new HashMap<String, String>();
        double[] scale = null;
        double[] offset = null;
        if (xml == null || xml.isEmpty()) {
            return new GdalMetadata(items, null, null, false);
        }
        int ptr = 0;
        while (true) {
            int start = xml.indexOf(ITEM, ptr);
            if (start < 0) {
                break;
            }
            int endTag = xml.indexOf('>', start);
            if (endTag < 0) {
                break;
            }
            int endValue = xml.indexOf('<', endTag);
            if (endValue < 0) {
                break;
            }
            // PROJ's `tag` runs from "<Item " up to but not including '>'; `value` is between them.
            String tag = xml.substring(start, endTag);
            String value = xml.substring(endTag + 1, endValue);

            int namePos = tag.indexOf(NAME_ATTR);
            if (namePos < 0) {
                // Verbatim: PROJ breaks, abandoning the rest of the document.
                break;
            }
            namePos += NAME_ATTR.length();
            int endQuote = tag.indexOf('"', namePos);
            if (endQuote < 0) {
                break;
            }
            String name = tag.substring(namePos, endQuote);

            int sample = GRID_LEVEL;
            int samplePos = tag.indexOf(SAMPLE_ATTR);
            if (samplePos >= 0) {
                sample = atoi(tag, samplePos + SAMPLE_ATTR.length());
            }
            items.put(key(sample, name), value);

            int rolePos = tag.indexOf(ROLE_ATTR);
            if (rolePos >= 0) {
                rolePos += ROLE_ATTR.length();
                int roleEnd = tag.indexOf('"', rolePos);
                if (roleEnd < 0) {
                    break;
                }
                String role = tag.substring(rolePos, roleEnd);
                boolean isOffset = "offset".equals(role);
                boolean isScale = "scale".equals(role);
                if ((isOffset || isScale) && sample >= 0 && sample < samplesPerPixel) {
                    // Sized to the samples this document actually names, not to SamplesPerPixel.
                    //
                    // The old code allocated two double[samplesPerPixel] on the first scale or
                    // offset item, on nothing stronger than the tag's `> 0` check. Measured on the
                    // code this replaces: a 358-byte GeoTIFF carrying PlanarConfig=3 and
                    // SamplesPerPixel=2^28 reached here and asked for two double[268435456] -- 4 GB
                    // from 358 bytes, an OutOfMemoryError that escapes catch (Exception).
                    //
                    // PlanarConfig=3 is NOT the only route, which an earlier version of this comment
                    // got wrong and a mutation sweep caught. SamplesPerPixel declared as a LONG holds
                    // any value up to 2^31, and on a legal PlanarConfig=1 file both bounds that
                    // involve it are satisfiable at once: blockBytes stays under MAX_BLOCK_BYTES for
                    // a small image, and expectedBlocks does not multiply by samplesPerPixel for
                    // CONTIG at all. Measured, frozen input, open() alone: a 350-byte 2x2 file
                    // declaring SamplesPerPixel = 8,000,000 allocated 128,157,520 bytes before this
                    // change and 157,704 after -- so this is the guard, not defence behind the
                    // PlanarConfig one. The allocation is now bounded by the number of <Item>
                    // elements in the document -- i.e. by the file -- rather than by a number the
                    // file merely asserts.
                    //
                    // Behaviour is unchanged for every real file. scaleFor/offsetFor already answer
                    // 1.0/0.0 for an index past the array, which is exactly what an untouched entry
                    // of a samplesPerPixel-sized array held.
                    int want = sample + 1;
                    if (offset == null) {
                        offset = new double[want];
                        scale = new double[want];
                        java.util.Arrays.fill(scale, 1.0);
                    } else if (offset.length < want) {
                        int was = offset.length;
                        offset = java.util.Arrays.copyOf(offset, want);
                        scale = java.util.Arrays.copyOf(scale, want);
                        java.util.Arrays.fill(scale, was, want, 1.0);
                    }
                    double parsed = parseDoubleOrNaN(value);
                    if (!Double.isNaN(parsed)) {
                        // PROJ swallows the exception from c_locale_stod and leaves the previous
                        // value, which for a fresh array is 0 / 1. Same here.
                        if (isOffset) {
                            offset[sample] = parsed;
                        } else {
                            scale[sample] = parsed;
                        }
                    }
                }
            }
            ptr = endValue + 1;
        }
        return new GdalMetadata(items, scale, offset, offset != null);
    }

    /**
     * A metadata item.
     *
     * @param name   the {@code name} attribute
     * @param sample the band index, or {@link #GRID_LEVEL}
     * @return the value, or the empty string — never {@code null}, matching PROJ's
     *         {@code metadataItem} which returns a reference to a static empty string
     */
    String item(String name, int sample) {
        String v = items.get(key(sample, name));
        return v == null ? "" : v;
    }

    /** A grid-level metadata item. */
    String item(String name) {
        return item(name, GRID_LEVEL);
    }

    /**
     * {@code true} iff a {@code role="scale"} or {@code role="offset"} item was seen. PROJ's
     * {@code readValue} applies scaling only under {@code sample < m_adfScale.size()}, and that vector
     * is empty until one appears — so this flag, not a unit scale, is what decides.
     */
    boolean hasScaleOffset() {
        return hasScaleOffset;
    }

    double scaleFor(int sample) {
        return scale == null || sample < 0 || sample >= scale.length ? 1.0 : scale[sample];
    }

    double offsetFor(int sample) {
        return offset == null || sample < 0 || sample >= offset.length ? 0.0 : offset[sample];
    }

    /**
     * The separator is written as the escape {@code \0}, not as a raw NUL byte. It was a raw NUL, and
     * the two of them (here and in the field's javadoc) made this file <strong>binary</strong> to
     * every line-oriented tool: {@code grep} returned silently on it, and {@code git diff} reported
     * {@code Bin 9812 -> 11518 bytes, 0 insertions(+), 0 deletions(-)} for a change of 60 lines. A
     * source file that no reviewer's {@code grep} can see is how a guard hole survives review; this is
     * the same failure that hid {@code indexOf('\0')} masquerading as {@code indexOf(' ')} elsewhere
     * in this codebase. The compiled constant is identical — {@code "\0"} is the octal escape for
     * U+0000 — so this is a source-visibility change and nothing else.
     */
    private static String key(int sample, String name) {
        return sample + "\0" + name;
    }

    /** C {@code atoi}: leading digits, no exception, 0 on nothing usable. */
    private static int atoi(String s, int from) {
        int i = from;
        int n = s.length();
        while (i < n && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        boolean negative = false;
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            negative = s.charAt(i) == '-';
            i++;
        }
        int value = 0;
        boolean any = false;
        while (i < n) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                break;
            }
            value = value * 10 + (c - '0');
            any = true;
            i++;
        }
        if (!any) {
            return 0;
        }
        return negative ? -value : value;
    }

    /**
     * PROJ's {@code c_locale_stod}: a C-locale {@code strtod}. {@link Double#parseDouble} is already
     * locale-independent, so the only thing to add is PROJ's swallow-and-ignore on failure, expressed
     * here as {@code NaN}.
     */
    static double parseDoubleOrNaN(String s) {
        if (s == null) {
            return Double.NaN;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}

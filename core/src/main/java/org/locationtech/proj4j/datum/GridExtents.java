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
 */
package org.locationtech.proj4j.datum;

import java.io.DataInputStream;
import java.io.IOException;

/**
 * The one place a binary reader turns declared dimensions into an allocation size.
 *
 * <h2>The defect this exists to make impossible</h2>
 *
 * <p>Five readers had the same shape: validate the <em>inputs</em>, then multiply them into an
 * unchecked {@code int} and allocate on the result, with no comparison against how many bytes the file
 * actually holds. {@code CTABLEV2} bounded each axis to {@code 1..100000} and then computed
 * {@code 100000 * 100000}, which is not 10<sup>10</sup> but <strong>1,410,065,408</strong> — 5.25 GiB
 * of references demanded by a <strong>160-byte header</strong>. {@code NTV1} validated nothing at all:
 * a zero increment makes the quotient infinite, {@code (int)} saturates to {@code Integer.MAX_VALUE},
 * and the {@code + 1} wraps to {@code Integer.MIN_VALUE}. A 634-byte GeoTIFF declaring
 * {@code 40000 x 40000} asked for 6.4 GB, an amplification of ten million to one.
 *
 * <h2>The rule</h2>
 *
 * <p>Compute every extent in {@code long}, reject <strong>before</strong> allocating, and compare the
 * product against the length of the thing the elements must fit inside. That pattern is not invented
 * here; it is {@link VerticalGrid#parseGtx}, which already does exactly this —
 * {@code long expected = 40L + 4L * rows * columns} compared against {@code bytes.length} — and it is
 * copied rather than re-derived so that five readers cannot drift into five subtly different
 * behaviours.
 *
 * <h2>Which limit applies where</h2>
 *
 * <p>{@link #checkedCount} takes the limit as a parameter because the honest bound differs by reader,
 * and pretending otherwise would be wrong in one direction or the other:
 *
 * <ul>
 *   <li><strong>Uncompressed formats</strong> — {@code CTABLE V2}, {@code NTv1}, {@code NTv2}, the
 *       {@code .pjdx} sections — are bounded by the <em>actual</em> file or section length. Nodes that
 *       are not in the file cannot be read, so a declaration bigger than the file is a lie and is
 *       refused for what it is.</li>
 *   <li><strong>GeoTIFF</strong> is not, because DEFLATE means a small file can legitimately decode to
 *       a much larger plane. Its bound is therefore a <em>decoded-heap budget</em>,
 *       {@link #maxDecodedBytes()}, and the file-length half of the check is done where it is
 *       meaningful: on each block, against {@code blockByteCounts}.</li>
 * </ul>
 *
 * <p>Both default limits derive from the ceiling {@code Grid} already applies to a grid file
 * ({@code proj4j.grids.maxFileBytes}, 128 MiB), so there is one knob and not three.
 *
 * <h2>What this class is not</h2>
 *
 * <p>It is not a policy layer and it holds no state. Every method either returns a value the caller can
 * safely allocate on, or throws {@link GridFormatException}. There is no "clamp to the maximum" path:
 * silently shrinking a declared grid would produce a smaller grid that still answers, which is the
 * failure mode this project exists to eliminate.
 *
 * @since 1.5
 */
public final class GridExtents {

    private GridExtents() {
    }

    /**
     * Largest legal value for a single axis, in nodes.
     *
     * <p>100,000 is {@code CTABLEV2}'s own pre-existing limit, promoted to apply everywhere rather than
     * relaxed. It is never the binding constraint for a real grid: the widest published PROJ grid is
     * {@code egm2008-1} at 43,200 columns, and any grid wide enough for this to matter would have
     * already failed the byte-count check by three orders of magnitude. Its only job is to keep the
     * subsequent {@code columns * rows} multiplication inside {@code long} with room to spare —
     * 10<sup>10</sup> at the very worst, against a {@code long} ceiling of 9.2&times;10<sup>18</sup>.
     */
    public static final long MAX_EXTENT = 100000L;

    /** Never produce an {@code int} array length that a JVM would refuse anyway. */
    private static final long MAX_ARRAY_LENGTH = Integer.MAX_VALUE - 8L;

    /**
     * The ceiling {@code Grid.resolveAndLoad} applies to a grid file, and therefore the largest number
     * of bytes any uncompressed reader can ever legitimately be asked to consume.
     *
     * <p>Read from {@code proj4j.grids.maxFileBytes}, default 128 MiB — the same property and the same
     * default {@code Grid} uses, so the two cannot disagree about what "too big" means.
     */
    public static long maxFileBytes() {
        return longProperty("proj4j.grids.maxFileBytes", 128L * 1024L * 1024L);
    }

    /**
     * The ceiling on a single decoded sample plane, for the one format where the file length is not an
     * upper bound on the decoded size.
     *
     * <p>Read from {@code proj4j.grids.maxDecodedBytes}, default four times {@link #maxFileBytes()},
     * i.e. 512 MiB. Four is chosen so a DEFLATE-compressed grid that fills the file budget can still
     * expand at a realistic ratio for float32 raster data; every hostile case measured needed between
     * 4 GB and 34 GB, so the margin is not close.
     */
    public static long maxDecodedBytes() {
        return longProperty("proj4j.grids.maxDecodedBytes", 4L * maxFileBytes());
    }

    private static long longProperty(String name, long fallback) {
        String raw = System.getProperty(name);
        if (raw != null) {
            try {
                long v = Long.parseLong(raw.trim());
                if (v > 0) {
                    return v;
                }
            } catch (NumberFormatException e) {
                // fall through to the default, exactly as Grid.maxGridFileBytes does
            }
        }
        return fallback;
    }

    /**
     * Turns a node count computed in floating point into a validated axis length.
     *
     * <p>{@code NTv1} and {@code NTv2} derive their extents as
     * {@code (int) (|span| / increment + 0.5) + 1}, which has three separate ways to produce nonsense
     * from a header the reader accepted:
     *
     * <table><caption>measured against the readers before this method existed</caption>
     * <tr><th>header</th><th>quotient</th><th>{@code lim}</th></tr>
     * <tr><td>{@code LAT_INC = 0}</td><td>{@code Infinity}</td>
     *     <td>{@code (int)} saturates to {@code MAX_VALUE}, {@code +1} wraps to
     *         <strong>{@code Integer.MIN_VALUE}</strong></td></tr>
     * <tr><td>{@code LAT_INC = 1e-300}</td><td>~10<sup>302</sup></td>
     *     <td><strong>{@code Integer.MIN_VALUE}</strong>, both axes</td></tr>
     * <tr><td>span 0 and increment 0</td><td>{@code NaN}</td>
     *     <td>{@code (int) NaN} is 0, so <strong>{@code 1}</strong> — a plausible 1&times;1 grid built
     *         from a header that describes nothing</td></tr>
     * </table>
     *
     * <p>The last row is the dangerous one and the reason this returns rather than clamps: it produced
     * no exception, no warning, and a grid that goes on to answer.
     *
     * <p>For every input this accepts, {@code (int) nodes + 1} is bit-identical to what the readers
     * computed before, so no legitimate grid changes shape. {@code nodes >= 0.5} is exactly the
     * condition under which {@code (int)} truncation and {@code Math.floor} agree, and it is false for
     * {@code NaN} — an unordered comparison — which is why the test is written as a negated {@code >=}
     * rather than as {@code < 0.5}.
     *
     * @param what  the file and field, for the message
     * @param axis  the axis name, for the message
     * @param nodes {@code |span| / increment + 0.5}, i.e. the value the reader was about to cast
     * @return {@code (int) nodes + 1}, guaranteed to be in {@code 1..MAX_EXTENT}
     * @throws GridFormatException if {@code nodes} is {@code NaN}, infinite, negative, or too large
     */
    public static int checkedAxis(String what, String axis, double nodes) throws IOException {
        if (!(nodes >= 0.5)) {
            throw new GridFormatException(what + " has a " + axis
                    + " extent of " + nodes + " nodes, computed from its span and increment. A grid "
                    + "axis must be at least one node; a zero, negative, infinite or NaN increment "
                    + "cannot describe one.");
        }
        if (nodes >= MAX_EXTENT) {
            throw new GridFormatException(what + " declares a " + axis + " extent of " + nodes
                    + " nodes, which exceeds the " + MAX_EXTENT + "-node limit for a single axis.");
        }
        return (int) nodes + 1;
    }

    /**
     * Refuses a declared <strong>two-dimensional</strong> extent before anything is allocated for it,
     * and returns the node count.
     *
     * <p>This is the whole of the fix, in one place. Order matters and is not negotiable:
     * range-check each axis, multiply in {@code long}, check the count against
     * {@code Integer.MAX_VALUE} <em>before</em> multiplying by the element size (or that multiplication
     * is the next thing to overflow), then compare the total against the limit.
     *
     * <p>The {@link #MAX_EXTENT} per-axis bound belongs to this method and not to
     * {@link #checkedCount}, deliberately. It is a statement about grid <em>geometry</em>, and applying
     * it to a one-dimensional count would have been a guard that breaks the library: the shipped
     * {@code proj4j-db.pjdx} string pool holds <strong>97,930</strong> strings, which is under 100,000
     * only by accident and would be over it after one EPSG release.
     *
     * @param what            the file and section, for the message
     * @param columns         first axis, in nodes
     * @param rows            second axis, in nodes
     * @param bytesPerElement on-disk bytes per node, or heap bytes per node when the limit is a heap
     *                        budget
     * @param baseBytes       bytes consumed before the nodes begin — the header, or the node block's
     *                        offset within its section
     * @param limitBytes      the length of the file, section or budget the nodes must fit inside
     * @param limitLabel      how to name {@code limitBytes} in the message, e.g. {@code "the file"}
     * @return {@code columns * rows}, guaranteed to be a legal {@code int} array length
     * @throws GridFormatException if either axis is out of range, or the product does not fit
     */
    public static int checkedCount(String what, long columns, long rows, long bytesPerElement,
                                   long baseBytes, long limitBytes, String limitLabel)
            throws IOException {
        if (columns <= 0 || rows <= 0) {
            throw new GridFormatException(what + " declares a non-positive extent of "
                    + columns + " x " + rows + " nodes.");
        }
        if (columns > MAX_EXTENT || rows > MAX_EXTENT) {
            throw new GridFormatException(what + " declares an extent of " + columns + " x " + rows
                    + " nodes, and no grid axis may exceed " + MAX_EXTENT + ".");
        }
        return checkedTotal(what, columns + " x " + rows + " = " + (columns * rows),
                columns * rows, bytesPerElement, baseBytes, limitBytes, limitLabel);
    }

    /**
     * Refuses a declared <strong>one-dimensional</strong> count before anything is allocated for it.
     *
     * <p>Used by every allocation in the {@code .pjdx} reader — the string pool, each table's key and
     * row-offset arrays, each index's tuple array — where the honest bound is the declaring section's
     * own length and nothing else. {@code stringCount} was checked only for {@code < 0}, so
     * {@code (stringCount + 1) * 4} wrapped negative at 2<sup>29</sup> while
     * {@code new int[stringCount + 1]} still asked for 2 GB; {@code Table} and {@code Index} validated
     * nothing at all.
     *
     * @param count the declared number of elements
     * @return {@code count}, guaranteed to be a legal {@code int} array length
     */
    public static int checkedCount(String what, long count, long bytesPerElement, long baseBytes,
                                   long limitBytes, String limitLabel) throws IOException {
        if (count < 0) {
            throw new GridFormatException(what + " declares a negative count of " + count
                    + " elements.");
        }
        return checkedTotal(what, String.valueOf(count), count, bytesPerElement, baseBytes,
                limitBytes, limitLabel);
    }

    private static int checkedTotal(String what, String shape, long count, long bytesPerElement,
                                    long baseBytes, long limitBytes, String limitLabel)
            throws IOException {
        if (count > MAX_ARRAY_LENGTH) {
            throw new GridFormatException(what + " declares " + shape
                    + " elements, which cannot be an array length.");
        }
        long needed = baseBytes + count * bytesPerElement;
        if (needed > limitBytes) {
            throw new GridFormatException(what + " declares " + shape + " elements, which needs "
                    + needed + " bytes, exceeding " + limitLabel + " of " + limitBytes
                    + " bytes. A header cannot declare more data than the file holds.");
        }
        return (int) count;
    }

    /**
     * The number of bytes still to come from {@code definition}, or {@code -1} when the stream declines
     * to say.
     *
     * <h4>Why {@code available()} is the right question here, and where it stops being right</h4>
     *
     * <p>{@code Grid.parse} constructs every stream these readers see with
     * {@code Resources.asDataStream(byte[])}, which is a {@code DataInputStream} over a
     * {@code ByteArrayInputStream} — a stream whose {@code available()} is specified to be the exact
     * remaining count and is O(1). For that stream, and for a {@code FileInputStream} or a
     * {@code BufferedInputStream} over either, this returns the true remaining length and the
     * product-versus-length check is exact.
     *
     * <p>It is <em>not</em> exact for a stream that decodes as it goes — {@code GZIPInputStream}
     * reports 1 while data remains — and the honest statement is that such a stream would be refused.
     * That is bounded and deliberate rather than accidental: these two readers consume the entire
     * declared extent with {@code readFully} a few lines later, so a stream that cannot say how much it
     * holds is a stream that was about to be drained anyway. No caller in this codebase supplies one,
     * and {@code GridBinaryReaderSecurityTest} pins the {@code BufferedInputStream} case as a
     * positive control so the coupling cannot rot silently.
     *
     * <p>The value is a bound on the check, never on the allocation: when this returns {@code -1} the
     * callers fall back to {@link #maxFileBytes()}, so the guard never disappears.
     */
    public static long remaining(DataInputStream definition) {
        try {
            int n = definition.available();
            return n > 0 ? n : -1L;
        } catch (IOException e) {
            return -1L;
        }
    }

    /**
     * {@code InputStream.skip} is permitted to skip fewer bytes than asked, which on a buffered or
     * decompressing stream silently mis-aligns everything that follows — every node then comes from the
     * wrong offset, which is a plausible coordinate rather than an error.
     */
    public static void skipFully(DataInputStream in, int n, String what) throws IOException {
        int done = 0;
        while (done < n) {
            int skipped = in.skipBytes(n - done);
            if (skipped <= 0) {
                throw new GridFormatException(what + " is truncated: wanted " + n
                        + " header bytes, could only skip " + done);
            }
            done += skipped;
        }
    }
}

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
package org.locationtech.proj4j.security.readers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.datum.Grid;
import org.locationtech.proj4j.datum.GridCache;
import org.locationtech.proj4j.datum.GridExtents;
import org.locationtech.proj4j.datum.GridFormatException;
import org.locationtech.proj4j.datum.tiff.GeoTiffDataset;
import org.locationtech.proj4j.datum.tiff.UnsupportedTiffException;
import org.locationtech.proj4j.resource.DirectoryResourceResolver;
import org.locationtech.proj4j.resource.ResourceResolvers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Integer-overflow guards in the four binary grid readers: what they refuse, what they must still
 * accept, and how much memory a refusal costs.
 *
 * <h2>The defect, as measured — not as described</h2>
 *
 * <p>Five readers shared one shape: validate the <em>inputs</em>, then multiply them into an unchecked
 * {@code int} and allocate on the result, never comparing the product against the bytes the file
 * actually holds. Every row below was produced by running the readers as they stood, at
 * {@code -Xmx1g}, against the crafters in {@link HostileGrids}. None of these is a hypothesis.
 *
 * <table><caption>measured before the guards existed</caption>
 * <tr><th>crafted input</th><th>file</th><th>outcome</th></tr>
 * <tr><td>{@code CTABLE V2} declaring 100000&times;100000</td><td>160 B</td>
 *     <td>{@code lim} accepted; {@code 100000 * 100000} is {@code 1410065408};
 *         <strong>{@code OutOfMemoryError}</strong>, 5.25 GiB of references</td></tr>
 * <tr><td>{@code NTv1} with {@code LAT_INC = 0}</td><td>192 B</td>
 *     <td>no exception; {@code lim.phi} = <strong>{@code Integer.MIN_VALUE}</strong></td></tr>
 * <tr><td>{@code NTv1} with a zero span and a zero increment</td><td>192 B</td>
 *     <td>no exception; {@code NaN} truncated to a plausible <strong>1&times;1 grid</strong></td></tr>
 * <tr><td>{@code NTv1} with {@code LAT_INC = 1e-300}</td><td>192 B</td>
 *     <td>no exception; both axes <strong>{@code Integer.MIN_VALUE}</strong></td></tr>
 * <tr><td>GeoTIFF declaring 40000&times;40000</td><td>634 B</td>
 *     <td><strong>{@code OutOfMemoryError}</strong>, 6.4 GB — <strong>10,000,000:1</strong></td></tr>
 * <tr><td>GeoTIFF declaring 65536&times;65536</td><td>1,290 B</td>
 *     <td>{@code width * height} is exactly {@code 0};
 *         <strong>{@code ArrayIndexOutOfBoundsException: Index -65536 out of bounds for length
 *         0}</strong></td></tr>
 * <tr><td>GeoTIFF, {@code PlanarConfig=3}, {@code SamplesPerPixel=2}<sup>28</sup></td><td>358 B</td>
 *     <td><strong>{@code OutOfMemoryError}</strong> in {@code GdalMetadata} — two
 *         {@code double[268435456]}, 4 GB</td></tr>
 * <tr><td>GeoTIFF, {@code PlanarConfig=3}, 4096&times;4096, 8 samples</td><td>~50 KB</td>
 *     <td><strong>no exception at all</strong>: a {@code float[16777216]} plane of zeros from a file
 *         containing no pixel bytes — a geoid of exactly 0 m</td></tr>
 * </table>
 *
 * <h3>Two corrections to the brief this work was written from</h3>
 *
 * <p><b>The 65536&times;65536 GeoTIFF does not corrupt node data; it throws.</b> The brief called it
 * "the only case that corrupts node data instead of throwing". It cannot: the wrapped product is
 * {@code 0}, so the plane is a zero-length array and the very first scatter write is out of bounds.
 * The throw is an unchecked {@code ArrayIndexOutOfBoundsException}, which escapes the
 * {@code catch (IOException)} in {@code Grid.fromNadGrids} and is caught only anonymously by the
 * {@code catch (RuntimeException)} in {@code Grid.describeNadGrids} — bad, but not silent.
 *
 * <p><b>The case that really does answer with corrupt data is {@code PlanarConfig=3}.</b> Last row
 * above. Both bounds on {@code SamplesPerPixel} are conditional on the tag being 1 or 2 — the
 * block-size bound multiplies by it only for {@code CONTIG}, the strip-count bound only for
 * {@code SEPARATE} — so a third value escapes both, and {@code readSamples} then computes a row stride
 * eight times what {@code MAX_BLOCK_BYTES} was checked against. That is the wrong-answer failure the
 * project exists to eliminate, and it was one tag value away in a 50 KB file.
 *
 * <h2>How each guard is shown to discriminate</h2>
 *
 * <p>A guard that refuses everything passes every hostile test and breaks the library. Every rejection
 * here is therefore paired with the same crafter, the same code path and in-range numbers, asserted to
 * <strong>load and shift a coordinate to the value the crafted node data implies</strong> — not merely
 * to avoid throwing. {@link #theGuardsAcceptEveryShapeTheShippedDataActuallyUses} additionally pins the
 * real dimensions of the shipped grids and of {@code proj4j-db.pjdx} against the bounds, because one
 * draft of {@code MAX_EXTENT} would have rejected the project's own database: its string pool holds
 * <strong>97,930</strong> strings against a 100,000 cap that looked generous.
 *
 * <h2>"Before the allocation" is measured, not asserted</h2>
 *
 * <p>{@link #aRefusalCostsNoMemory} reads {@code ThreadMXBean.getThreadAllocatedBytes} across each
 * refusal. Its own positive control allocates a known 32 MB first and requires the meter to see it, so
 * a meter that always reads zero fails the test rather than passing every case.
 */
public class GridBinaryReaderSecurityTest {

    private Path root;

    @After
    public void resetChain() throws IOException {
        ResourceResolvers.clearResolvers();
        GridCache.instance().clear();
        if (root != null) {
            deleteRecursively(root);
            root = null;
        }
    }

    /** Publishes {@code bytes} under {@code name} through the real resolver chain. */
    private void publish(String name, byte[] bytes) throws IOException {
        if (root == null) {
            root = Files.createTempDirectory("proj4j-hostile-grid");
            ResourceResolvers.addResolver(new DirectoryResourceResolver(root));
        }
        Files.write(root.resolve(name), bytes);
        GridCache.instance().clear();
    }

    private List<Grid> load(String name, byte[] bytes) throws IOException {
        publish(name, bytes);
        List<Grid> grids = new ArrayList<Grid>();
        Grid.mergeGridFile(name, grids);
        return grids;
    }

    /**
     * Asserts that {@code bytes} is refused by a named, bounded, in-family exception.
     *
     * <p>The three negative assertions are the point. {@code OutOfMemoryError} and
     * {@code StackOverflowError} are {@link Error}s and escape {@code catch (Exception)} entirely;
     * {@code NegativeArraySizeException} and {@code ArrayIndexOutOfBoundsException} are unchecked and
     * escape the {@code catch (IOException)} that {@code Grid.fromNadGrids} uses. All four were
     * observed coming out of these readers.
     */
    private void refused(String name, byte[] bytes, String... mustMention) throws IOException {
        publish(name, bytes);
        try {
            List<Grid> grids = new ArrayList<Grid>();
            Grid.mergeGridFile(name, grids);
            fail(name + " must be refused, but it loaded as " + grids);
        } catch (RuntimeException e) {
            throw new AssertionError(name + " was refused by an UNCHECKED exception, which escapes "
                    + "the catch (IOException) in Grid.fromNadGrids: " + e, e);
        } catch (IOException e) {
            String message = String.valueOf(e.getMessage());
            for (String needle : mustMention) {
                assertTrue(name + ": the refusal must mention \"" + needle + "\", but said: "
                        + message, message.contains(needle));
            }
        }
    }

    // ================================================================= CTABLE V2

    /**
     * The headline case: a <strong>160-byte file</strong> — nothing but a header — that used to demand
     * 5.25 GiB. Both axes are inside the reader's own {@code 1..100000} bound, which is precisely why
     * the bound was never enough.
     */
    @Test
    public void ctable2With100000SquaredIsRefusedFromA160ByteHeader() throws Exception {
        byte[] bytes = HostileGrids.ctable2(100000, 100000, 0, 0f, 0f);
        assertEquals("the crafted file is a bare header", 160, bytes.length);
        assertEquals("and 100000 * 100000 is not 10^10 in an int", 1410065408, 100000 * 100000);
        refused("hostile_ctable_square", bytes, "100000 x 100000 = 10000000000",
                "cannot be an array length");
    }

    /** The same shape at the smallest extent that still overflows the product. */
    @Test
    public void ctable2WithAnExtentThatOverflowsIsRefused() throws Exception {
        // 46341^2 = 2147488281, which is Integer.MAX_VALUE + 4634.
        assertTrue("46341 squared must overflow int", 46341 * 46341 < 0);
        refused("hostile_ctable_46341", HostileGrids.ctable2(46341, 46341, 0, 0f, 0f),
                "46341 x 46341");
    }

    /** A plausible extent whose node data simply is not in the file. */
    @Test
    public void ctable2DeclaringMoreNodesThanTheFileHoldsIsRefused() throws Exception {
        byte[] bytes = HostileGrids.ctable2(100, 100, 50, 0f, 0f);
        assertEquals(160 + 50 * 8, bytes.length);
        // The message names the product, the bytes it would need (80,160 including the 160-byte
        // header) and the length of the file that does not contain them.
        refused("hostile_ctable_short", bytes, "100 x 100 = 10000", "80160", "560");
    }

    @Test
    public void ctable2WithANonPositiveExtentIsRefused() throws Exception {
        refused("hostile_ctable_zero", HostileGrids.ctable2(0, 3, 0, 0f, 0f), "non-positive");
        refused("hostile_ctable_neg", HostileGrids.ctable2(-4, 3, 0, 0f, 0f), "non-positive");
    }

    /**
     * <strong>Positive control.</strong> The same crafter, in range: it must parse, load, and move a
     * coordinate by exactly the shift written into its nodes. A guard proven only by refusals is
     * indistinguishable from a reader that refuses everything.
     */
    @Test
    public void ctable2WithAnInRangeExtentStillLoadsAndShifts() throws Exception {
        // A 21x21 grid of 1-degree cells with its lower left at (-10, -10), every node carrying the
        // same shift, so the bilinear interpolation is exact wherever we probe.
        float shiftLam = (float) Math.toRadians(0.25);
        float shiftPhi = (float) Math.toRadians(-0.5);
        List<Grid> grids = load("good_ctable", HostileGrids.ctable2(21, 21, shiftLam, shiftPhi));
        assertEquals(1, grids.size());
        assertEquals("ctable2", grids.get(0).getFormat());

        ProjCoordinate c = new ProjCoordinate(Math.toRadians(0.0), Math.toRadians(0.0));
        Grid.shift(grids, false, c);
        // Grid.nad_cvt subtracts the table's lam and adds its phi, which is proj4j's convention.
        // 1e-6 degrees, not 1e-9: CTABLE V2 stores each shift as a float, so a 0.25-degree shift
        // round-trips through 24 bits of mantissa in radians and comes back as 0.2499999980634083.
        assertEquals(-0.25, Math.toDegrees(c.x), 1e-6);
        assertEquals(-0.50, Math.toDegrees(c.y), 1e-6);
    }

    /**
     * A file exactly the size its header declares must be accepted — the boundary between the last
     * legal file and the first refused one.
     */
    @Test
    public void ctable2ThatIsExactlyItsDeclaredSizeIsAccepted() throws Exception {
        byte[] exact = HostileGrids.ctable2(21, 21, 0f, 0f);
        assertEquals(160 + 21 * 21 * 8, exact.length);
        assertEquals(1, load("exact_ctable", exact).size());

        byte[] oneNodeShort = HostileGrids.ctable2(21, 21, 21 * 21 - 1, 0f, 0f);
        refused("short_ctable", oneNodeShort, "441", "3688", "3680");
    }

    // ================================================================= the Error family

    /**
     * The five {@code throw new Error(...)}: which of them a hostile grid could actually reach, and
     * proof that none of them is an {@link Error} any more.
     *
     * <h4>This test was vacuous when first written, and the mutation sweep is what said so</h4>
     *
     * <p>The first version fed 160 bytes of junk through {@code Grid.mergeGridFile} and asserted the
     * result was an {@code IOException}. Reverting {@code CTABLEV2}'s conversion back to
     * {@code throw new Error(...)} <strong>did not fail it</strong>. The reason is worth recording:
     * {@code Grid.parse} calls {@code CTABLEV2.init} only after {@code CTABLEV2.testHeader} has
     * passed, and {@code init} then re-runs <em>the same</em> {@code containsAt} over <em>the same</em>
     * 160 bytes — so that particular {@code Error} is unreachable from the grid path, and junk bytes
     * fall out of {@code Grid.parse}'s "unrecognised format" branch long before reaching it. The test
     * was measuring a branch it never entered.
     *
     * <p>So the sites are split here by how they are reached, and each is driven through the entry
     * point that actually reaches it:
     *
     * <table><caption>the five sites</caption>
     * <tr><th>site</th><th>reachable from {@code +nadgrids=}?</th><th>driven here by</th></tr>
     * <tr><td>{@code NTV1:51} wrong record count</td><td><strong>yes</strong> — {@code testHeader}
     *     checks the magic, not {@code NUM_OREC}</td><td>{@code Grid.mergeGridFile}</td></tr>
     * <tr><td>{@code CTABLEV2:48} extent out of range</td><td><strong>yes</strong> — the extent is
     *     read after the magic test</td><td>{@code Grid.mergeGridFile}</td></tr>
     * <tr><td>{@code CTABLEV2:40} not a CTABLE V2 file</td><td>no — same predicate as
     *     {@code testHeader}</td><td>the public {@code CTABLEV2.init}</td></tr>
     * <tr><td>{@code NTV1:46} not an NTV1 file</td><td>no — same predicate as {@code testHeader}</td>
     *     <td>the public {@code NTV1.init}</td></tr>
     * <tr><td>{@code NTV2:325} not an NTv2 file</td><td>no — deprecated, {@code Grid} uses
     *     {@code loadAll}</td><td>the public {@code NTV2.init}</td></tr>
     * </table>
     *
     * <p>The three unreachable ones are still converted and still tested. They are {@code public
     * static} methods on the format classes; "unreachable by an accident of control flow" is not a
     * guarantee, and an {@code Error} escapes {@code catch (Proj4jException)} <em>and</em>
     * {@code catch (Exception)} the moment a refactor makes one live.
     */
    @Test
    public void noneOfTheFiveFormerErrorSitesThrowsAnError() throws Exception {
        // --- reachable from a +nadgrids= token, through the whole grid path ---

        // NTv1 magic, NUM_OREC = 13. testHeader passes; init's record-count check fires.
        byte[] badRecordCount = HostileGrids.ntv1(40, 50, 60, 70, 1.0, 1.0, 121, 0, 0);
        badRecordCount[11] = 13;
        assertInFamily("NTv1 with NUM_OREC=13", badRecordCount, "record count");

        // CTABLE V2 magic, extent out of range. testHeader passes; the extent check fires.
        assertInFamily("CTABLE V2 with a zero extent",
                HostileGrids.ctable2(0, 0, 0, 0f, 0f), "non-positive");

        // --- reachable only through the public format API ---
        assertDirectCallIsInFamily("CTABLEV2.init", new ThrowingCall() {
            @Override
            public void run() throws Exception {
                org.locationtech.proj4j.datum.CTABLEV2.init(stream(new byte[160]));
            }
        });
        assertDirectCallIsInFamily("NTV1.init", new ThrowingCall() {
            @Override
            public void run() throws Exception {
                org.locationtech.proj4j.datum.NTV1.init(stream(new byte[160]));
            }
        });
        assertDirectCallIsInFamily("NTV2.init", new ThrowingCall() {
            @Override
            public void run() throws Exception {
                org.locationtech.proj4j.datum.NTV2.init(stream(new byte[400]));
            }
        });
    }

    private interface ThrowingCall {
        void run() throws Exception;
    }

    private static java.io.DataInputStream stream(byte[] bytes) {
        return org.locationtech.proj4j.resource.Resources.asDataStream(bytes);
    }

    /**
     * Catches {@link Throwable}, not {@code Exception}: catching {@code Exception} is exactly what an
     * {@code Error} slips past, so a test that used it would report a pass by disappearing.
     */
    private static void assertDirectCallIsInFamily(String label, ThrowingCall call) {
        Throwable caught = null;
        try {
            call.run();
        } catch (Throwable t) {
            caught = t;
        }
        assertNotNull(label + " must refuse bytes that are not its format", caught);
        assertFalse(label + " threw " + caught.getClass().getName()
                + ", which escapes catch (Proj4jException) and catch (Exception)",
                caught instanceof Error);
        assertTrue(label + " must throw in the IOException family, got " + caught.getClass(),
                caught instanceof IOException);
    }

    /** Drives {@code bytes} through the real grid path and asserts an in-family refusal. */
    private void assertInFamily(String label, byte[] bytes, String mustMention) throws IOException {
        String name = "family_" + Integer.toHexString(label.hashCode());
        publish(name, bytes);
        Throwable caught = null;
        try {
            Grid.mergeGridFile(name, new ArrayList<Grid>());
        } catch (Throwable t) {
            caught = t;
        }
        assertNotNull(label + " must be refused", caught);
        assertFalse(label + " threw " + caught.getClass().getName() + ", which escapes every handler "
                + "on the grid path", caught instanceof Error);
        assertTrue(label + " must be an IOException, got " + caught.getClass(),
                caught instanceof IOException);
        assertTrue(label + ": the message must mention \"" + mustMention + "\", but said: "
                + caught.getMessage(), String.valueOf(caught.getMessage()).contains(mustMention));
    }

    /**
     * The conversion has to be caught by the handlers that already exist, not merely be an
     * {@code IOException} in principle. {@code Grid.describeNadGrids} is the introspection channel a
     * caller uses to find out why a grid was skipped; if the refusal escaped it, a hostile grid name
     * would take down the caller instead of being reported.
     */
    @Test
    public void aRefusalIsReportedByDescribeNadGridsRatherThanEscapingIt() throws Exception {
        publish("hostile_described", HostileGrids.ctable2(100000, 100000, 0, 0f, 0f));
        List<Grid.GridRef> refs = Grid.describeNadGrids("hostile_described");
        assertEquals(1, refs.size());
        assertFalse("it must not have loaded", refs.get(0).isAvailable());
        assertNotNull("and describeNadGrids must carry the reason", refs.get(0).skipReason());
        assertTrue("the reason must name the declared extent: " + refs.get(0).skipReason(),
                refs.get(0).skipReason().contains("100000 x 100000"));
    }

    // ================================================================= NTv1

    /**
     * {@code NTV1.init} validated <strong>nothing</strong>. A zero {@code LAT_INC} makes the quotient
     * infinite, {@code (int)} saturates to {@code Integer.MAX_VALUE}, and the {@code + 1} wraps.
     */
    @Test
    public void ntv1WithAZeroIncrementIsRefused() throws Exception {
        assertEquals("the wrap this refuses", Integer.MIN_VALUE, (int) Double.POSITIVE_INFINITY + 1);
        refused("hostile_ntv1_zero_inc",
                HostileGrids.ntv1(40, 50, 60, 80, 0.0, 0.25, 0, 0, 0),
                "NTv1", "latitude");
    }

    /**
     * The dangerous one: a zero span <em>and</em> a zero increment give {@code 0.0 / 0.0}, and
     * {@code (int) NaN} is {@code 0}, so the reader built a <strong>1&times;1 grid</strong> out of a
     * header describing nothing and reported success. No exception, no warning, a grid that answers.
     */
    @Test
    public void ntv1WithANaNExtentIsRefusedRatherThanBecomingA1x1Grid() throws Exception {
        assertEquals("(int) NaN is 0, so the old code produced lim = 1", 1, (int) Double.NaN + 1);
        refused("hostile_ntv1_nan", HostileGrids.ntv1(40, 40, 60, 60, 0.0, 0.0, 0, 0, 0),
                "NTv1", "NaN");
    }

    /** A denormal increment: finite, enormous, and it saturated exactly like the infinite one. */
    @Test
    public void ntv1WithADenormalIncrementIsRefused() throws Exception {
        refused("hostile_ntv1_denormal",
                HostileGrids.ntv1(-90, 90, -180, 180, 1e-300, 1e-300, 0, 0, 0),
                "NTv1", "exceeds");
    }

    /** An extent the file cannot back. */
    @Test
    public void ntv1DeclaringMoreNodesThanTheFileHoldsIsRefused() throws Exception {
        // 0.25-degree cells over 10 x 10 degrees: 41 x 41 = 1681 nodes, but only 100 are present.
        refused("hostile_ntv1_short",
                HostileGrids.ntv1(40, 50, 60, 70, 0.25, 0.25, 100, 0, 0),
                "41 x 41 = 1681");
    }

    /**
     * <strong>Positive control.</strong> The identical crafter with a real increment loads and shifts.
     * The expected numbers come from the format, not from the reader: NTv1 stores arc seconds, proj4j
     * subtracts the longitude shift and adds the latitude shift.
     */
    @Test
    public void ntv1WithARealIncrementStillLoadsAndShifts() throws Exception {
        // 40..50 N, 60..70 W at 1-degree spacing: 11 x 11 = 121 nodes.
        double latShiftSec = 3600.0 * 0.25;   // +0.25 degrees
        double longShiftSec = 3600.0 * 0.5;   // 0.5 degrees, applied with proj4j's sign convention
        List<Grid> grids = load("good_ntv1",
                HostileGrids.ntv1(40, 50, 60, 70, 1.0, 1.0, 121, latShiftSec, longShiftSec));
        assertEquals(1, grids.size());
        assertEquals("ntv1", grids.get(0).getFormat());

        ProjCoordinate c = new ProjCoordinate(Math.toRadians(-65.0), Math.toRadians(45.0));
        Grid.shift(grids, false, c);
        assertEquals(-65.0 - 0.5, Math.toDegrees(c.x), 1e-6);
        assertEquals(45.0 + 0.25, Math.toDegrees(c.y), 1e-6);
    }

    /**
     * The shipped {@code ntv1_can.dat} must still load. It is 1,113,184 bytes for a 393&times;177 grid
     * plus a trailing {@code END} record, and the byte-count guard has to leave room for that record —
     * a guard that budgeted exactly would refuse the only NTv1 file the project ships.
     */
    @Test
    public void theShippedNtv1GridStillLoads() throws Exception {
        List<Grid> grids = new ArrayList<Grid>();
        Grid.mergeGridFile("ntv1_can.dat", grids);
        assertEquals(1, grids.size());
        assertEquals("ntv1", grids.get(0).getFormat());
    }

    // ================================================================= NTv2

    /** The same saturating cast as NTv1's, in {@code NTV2.subHeader}. */
    @Test
    public void ntv2WithADenormalIncrementIsRefused() throws Exception {
        refused("hostile_ntv2.gsb",
                HostileGrids.ntv2(0, 36000, 0, 36000, 1e-300, 1e-300, 1, 0, 0f, 0f),
                "NTv2", "exceeds");
    }

    /**
     * <strong>Positive control.</strong> A crafted single-subgrid NTv2 with a real increment loads and
     * shifts. Its cells are 3600 arc seconds — one degree — over ten degrees each way.
     */
    @Test
    public void ntv2WithARealIncrementStillLoadsAndShifts() throws Exception {
        int cells = 11 * 11;
        float latShiftSec = (float) (3600.0 * 0.25);
        float longShiftSec = (float) (3600.0 * 0.5);
        List<Grid> grids = load("good_ntv2.gsb",
                HostileGrids.ntv2(40 * 3600, 50 * 3600, 60 * 3600, 70 * 3600, 3600, 3600,
                        cells, cells, latShiftSec, longShiftSec));
        assertEquals(1, grids.size());
        assertEquals("ntv2", grids.get(0).getFormat());

        ProjCoordinate c = new ProjCoordinate(Math.toRadians(-65.0), Math.toRadians(45.0));
        Grid.shift(grids, false, c);
        assertEquals(-65.0 - 0.5, Math.toDegrees(c.x), 1e-6);
        assertEquals(45.0 + 0.25, Math.toDegrees(c.y), 1e-6);
    }

    // ================================================================= GeoTIFF

    /**
     * 634 bytes asking for 6.4 GB. The dimensions were each checked against
     * {@code Integer.MAX_VALUE}; their product never was, and {@code readSamples} allocates one
     * {@code float[width * height]} per requested band.
     */
    @Test
    public void geoTiffWith40000SquaredIsRefused() throws Exception {
        int strips = HostileGrids.stripsFor(40000, 40000, 4);
        byte[] file = HostileGrids.geoTiff(40000, 40000, 1, strips).build();
        assertTrue("the crafted file must stay tiny: " + file.length, file.length < 4096);
        expectTiffRefusal(file, "40000 x 40000", "decoded-grid budget");
    }

    /**
     * The zero-product case. {@code 65536 * 65536} is exactly {@code 0} in an {@code int}, so the
     * plane was a zero-length array and the first scatter write went to index {@code -65536}.
     */
    @Test
    public void geoTiffWith65536SquaredIsRefused() throws Exception {
        assertEquals("the product this refuses is exactly zero", 0, 65536 * 65536);
        assertEquals("and the scatter index wraps negative", -65536, 65535 * 65536 + 0);
        int strips = HostileGrids.stripsFor(65536, 65536, 4);
        byte[] file = HostileGrids.geoTiff(65536, 65536, 1, strips).build();
        assertTrue("the crafted file must stay tiny: " + file.length, file.length < 4096);
        // 65536 * 65536 is 4,294,967,296, which trips the array-length clause before the byte
        // budget one. Either is a refusal; what matters is that it is named and in family.
        expectTiffRefusal(file, "65536 x 65536 = 4294967296", "cannot be an array length");
    }

    /**
     * {@code PlanarConfig} presence was checked; its value was not, and TIFF defines exactly two.
     *
     * <p>This is the one case that answered with corrupt data rather than throwing. Measured before
     * the guard: this file decoded with <strong>no exception</strong> and returned a
     * {@code float[16777216]} plane of zeros — a geoid of exactly 0 m — because
     * {@code MAX_BLOCK_BYTES} was checked against a stride of 1 while {@code readSamples} used a
     * stride of 8.
     */
    @Test
    public void geoTiffWithAnUndefinedPlanarConfigIsRefused() throws Exception {
        byte[] file = HostileGrids.geoTiff(4096, 4096, 8, 4096).shorts(284, 3).build();
        try {
            GeoTiffDataset.open(file, "pc3").images().get(0).readSample(0);
            fail("PlanarConfig=3 must be refused; it used to return 16,777,216 zeros");
        } catch (UnsupportedTiffException e) {
            assertTrue("must name the tag and its value: " + e.getMessage(),
                    e.getMessage().contains("PlanarConfig=3"));
        }
    }

    /**
     * The two definitions of the sample stride used to live in two places and disagree. They now come
     * from one field; this pins that they would have agreed for every <em>legal</em> tag value, which
     * is why validating the tag is sufficient and no behaviour changes for a real file.
     */
    @Test
    public void theTwoOldStrideFormulasAgreeForEveryLegalPlanarConfig() {
        for (int planarConfig : new int[]{1, 2}) {
            for (int spp : new int[]{1, 2, 3, 8}) {
                int atValidation = planarConfig == 1 ? spp : 1;
                boolean separate = planarConfig == 2 && spp > 1;
                boolean contig = !separate && spp > 1;
                int atUse = contig ? spp : 1;
                assertEquals("PlanarConfig=" + planarConfig + " spp=" + spp,
                        atValidation, atUse);
            }
        }
        // ...and disagree by a factor of samplesPerPixel for the value now refused.
        int spp = 8;
        int atValidation = 1;
        int atUse = 8;
        assertEquals("PlanarConfig=3 is exactly where they diverged", spp, atUse / atValidation);
    }

    /**
     * <strong>The instrument the {@code GdalMetadata} guard did not have, and the correction to the
     * claim above it.</strong>
     *
     * <p>{@link #gdalMetadataIsAlsoRefusedThroughThePlanarConfigHole} was the only test covering
     * {@code GdalMetadata}, and a mutation sweep showed it <strong>cannot fail</strong>: reverting
     * {@code GdalMetadata.java} to its pre-fix version leaves all 27 tests in this class green,
     * because the {@code PlanarConfig=3} guard in {@code GeoTiffImage} now refuses that file first.
     * The comment in {@code GdalMetadata} said {@code PlanarConfig=3} "was the only route to it".
     * <strong>It is not.</strong>
     *
     * <p>{@code SamplesPerPixel} is a {@code SHORT} in the TIFF spec, but {@code TiffDirectory} reads
     * whatever type the tag declares, so a {@code LONG} carries any value up to 2<sup>31</sup>. Both
     * bounds that involve it are then satisfiable at once on a legal {@code PlanarConfig=1} file:
     * {@code blockBytes = blockWidth * blockHeight * samplesPerPixel * 4} stays under
     * {@code MAX_BLOCK_BYTES} for a <em>small</em> image, and {@code expectedBlocks} does not multiply
     * by {@code samplesPerPixel} at all for {@code CONTIG}. A 2&times;2 image therefore reaches
     * {@code GdalMetadata.parse} carrying any {@code SamplesPerPixel} up to ~16.5 million.
     *
     * <p>Measured, frozen input, {@code open()} alone — no pixel decode:
     *
     * <table><caption>350-byte file, {@code SamplesPerPixel = 8,000,000}, {@code PlanarConfig = 1}</caption>
     * <tr><th>{@code GdalMetadata}</th><th>bytes allocated by {@code open()}</th></tr>
     * <tr><td>pre-fix (sized by {@code SamplesPerPixel})</td>
     *     <td><strong>128,157,520</strong> — two {@code double[8000000]}, a 366,000:1
     *         amplification</td></tr>
     * <tr><td>fixed (sized by the {@code <Item>} elements present)</td><td>157,704</td></tr>
     * </table>
     *
     * <p>So the fix is load-bearing on its own and not merely defence behind the {@code PlanarConfig}
     * check. This test is the A/B, kept as an assertion.
     */
    @Test
    public void gdalMetadataDoesNotAllocateOnAnAssertedSamplesPerPixel() throws Exception {
        com.sun.management.ThreadMXBean sun = allocationMeter();
        long id = Thread.currentThread().getId();

        // 2x2 so the georeferencing check (which runs first) passes; SamplesPerPixel as a LONG so it
        // is not truncated to 16 bits; one scale item, which is what triggers the allocation.
        int samplesPerPixel = 8_000_000;
        byte[] file = HostileGrids.geoTiff(2, 2, 1, 2)
                .longs(277, samplesPerPixel)
                .ascii(42112, "<GDALMetadata><Item name=\"s\" sample=\"0\" role=\"scale\">2.0"
                        + "</Item></GDALMetadata>")
                .build();
        assertTrue("the crafted file must stay tiny: " + file.length, file.length < 1024);

        long before = sun.getThreadAllocatedBytes(id);
        org.locationtech.proj4j.datum.tiff.GeoTiffImage image =
                GeoTiffDataset.open(file, "spp_contig").images().get(0);
        long cost = sun.getThreadAllocatedBytes(id) - before;

        // The tag really was read at its declared value, so parse really did run with it: without
        // this the test would also pass if the file had been refused for some unrelated reason.
        assertEquals("the file must actually reach GdalMetadata.parse carrying this value",
                samplesPerPixel, image.samplesPerPixel());
        assertTrue("opening a " + file.length + "-byte file must not allocate two double["
                + samplesPerPixel + "]; it allocated " + cost + " bytes", cost < 8L * 1024 * 1024);
    }

    /**
     * {@code PlanarConfig=3} with {@code SamplesPerPixel = 2}<sup>28</sup> — 358 bytes that used to
     * ask {@code GdalMetadata} for two {@code double[268435456]}, 4 GB.
     *
     * <p><strong>Named for what it actually pins.</strong> It was called
     * {@code gdalMetadataIsNoLongerSizedBySamplesPerPixel}, and the mutation sweep showed that is not
     * what it measures: the {@code PlanarConfig} guard refuses this file before
     * {@code GdalMetadata.parse} is reached, so it stays green with {@code GdalMetadata} reverted. It
     * is a {@code PlanarConfig} test, and a useful one — the 4 GB allocation really was reachable this
     * way. {@link #gdalMetadataDoesNotAllocateOnAnAssertedSamplesPerPixel} is the one that fails when
     * {@code GdalMetadata} regresses.
     */
    @Test
    public void gdalMetadataIsAlsoRefusedThroughThePlanarConfigHole() throws Exception {
        byte[] file = HostileGrids.geoTiff(4, 4, 1, 1)
                .longs(277, 1 << 28)
                .shorts(284, 3)
                .ascii(42112, "<GDALMetadata><Item name=\"s\" sample=\"0\" role=\"scale\">2.0"
                        + "</Item></GDALMetadata>")
                .build();
        assertTrue("358-byte file, formerly two double[268435456]: " + file.length,
                file.length < 1024);
        try {
            GeoTiffDataset.open(file, "spp").images().get(0);
            fail("SamplesPerPixel = 2^28 must be refused");
        } catch (IOException expected) {
            // In family. An OutOfMemoryError, which is what this used to be, is an Error and would
            // have propagated past this catch and failed the test.
            assertNotNull(expected.getMessage());
        }
    }

    /**
     * <strong>Positive control for the metadata change.</strong> A legitimate two-band grid with a
     * {@code role="scale"} item must still scale, and a sample with no item must still read raw. If
     * the shorter arrays had changed semantics, this is where it would show.
     */
    @Test
    public void scaleAndOffsetStillApplyAfterTheMetadataResize() throws Exception {
        // sample 0 carries scale 10 and offset 1; sample 1 carries nothing.
        byte[] pixels = new byte[2 * 2 * 2 * 4];
        java.nio.ByteBuffer pb = java.nio.ByteBuffer.wrap(pixels)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4; i++) {
            pb.putFloat(i * 8, 3.0f);        // sample 0
            pb.putFloat(i * 8 + 4, 7.0f);    // sample 1
        }
        byte[] file = HostileGrids.geoTiff(2, 2, 2, 1)
                .longs(279, pixels.length)
                .ascii(42112, "<GDALMetadata>"
                        + "<Item name=\"SCALE\" sample=\"0\" role=\"scale\">10</Item>"
                        + "<Item name=\"OFFSET\" sample=\"0\" role=\"offset\">1</Item>"
                        + "</GDALMetadata>")
                .pixels(pixels)
                .build();
        float[][] planes = GeoTiffDataset.open(file, "scaled").images().get(0)
                .readSamples(new int[]{0, 1});
        assertEquals("sample 0 must be scaled and offset", 31.0f, planes[0][0], 1e-6f);
        assertEquals("sample 1 has no item and must read raw", 7.0f, planes[1][0], 1e-6f);
    }

    /**
     * <strong>Positive control.</strong> Upstream's own fixtures, unmodified, must still read to the
     * value PROJ 9.8.1 produces for them. These are the files
     * {@code GeoTiffHorizontalGridTest} pins; repeating one here means a broken guard fails in this
     * class too, next to the refusals it would be paired with.
     */
    @Test
    public void theShippedGeoTiffGridsStillLoadAndShift() throws Exception {
        List<Grid> grids = new ArrayList<Grid>();
        Grid.mergeGridFile("test_hgrid.tif", grids);
        assertEquals(1, grids.size());
        ProjCoordinate c = new ProjCoordinate(Math.toRadians(4.5), Math.toRadians(52.5));
        Grid.shift(grids, false, c);
        // GeoTiffHorizontalGridTest pins these against cct at its own tolerance; here they only
        // have to show the reader still reads, so a float-storage tolerance is enough.
        assertEquals(5.875, Math.toDegrees(c.x), 1e-6);
        assertEquals(55.375, Math.toDegrees(c.y), 1e-6);
    }

    private void expectTiffRefusal(byte[] file, String... mustMention) {
        try {
            GeoTiffDataset.open(file, "hostile").images().get(0).readSample(0);
            fail("must be refused");
        } catch (IOException e) {
            String message = String.valueOf(e.getMessage());
            for (String needle : mustMention) {
                assertTrue("the refusal must mention \"" + needle + "\", but said: " + message,
                        message.contains(needle));
            }
        }
    }

    // ================================================================= the bounds themselves

    /**
     * The anti-vacuity test for the bounds, and the one that caught a real mistake.
     *
     * <p>A first draft applied {@link GridExtents#MAX_EXTENT} — 100,000, taken from
     * {@code CTABLEV2}'s own axis check — to every count, including one-dimensional ones. The shipped
     * {@code proj4j-db.pjdx} string pool holds <strong>97,930</strong> strings. The guard would have
     * passed today and refused the project's own database after one EPSG release, and every hostile
     * test would still have been green. So the shapes the shipped data actually uses are pinned here,
     * against the bounds, by number.
     */
    @Test
    public void theGuardsAcceptEveryShapeTheShippedDataActuallyUses() throws Exception {
        // Grid geometry: the largest shipped grids, well inside MAX_EXTENT.
        assertEquals(1201 * 601, GridExtents.checkedCount("conus", 1201, 601, 8L, 160L,
                160L + 1201L * 601L * 8L, "the file"));
        assertEquals(393 * 177, GridExtents.checkedCount("ntv1_can.dat", 393, 177, 16L, 192L,
                1113184L, "the file"));
        // egm2008-1 is the widest grid PROJ publishes, and it is still under the axis bound.
        assertEquals(43200 * 21600, GridExtents.checkedCount("egm2008-1", 43200, 21600, 4L, 0L,
                4L * 43200L * 21600L, "the decoded-grid budget"));

        // One-dimensional counts are NOT grid axes and must not carry the axis bound.
        assertEquals(97931, GridExtents.checkedCount("proj4j-db.pjdx string pool",
                97930L + 1, 4L, 8L, 2872150L, "the string-pool section length"));
        assertEquals(19103 * 5, GridExtents.checkedCount("the widest shipped table",
                19103L * 5, 4L, 16L, 1L << 20, "the section length"));

        // And the axis bound is really there for two-dimensional extents.
        try {
            GridExtents.checkedCount("over-wide grid", 100001L, 1L, 4L, 0L, Long.MAX_VALUE, "nothing");
            fail("MAX_EXTENT must bound a grid axis");
        } catch (GridFormatException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("100000"));
        }
    }

    /** {@code checkedAxis} must accept every quotient a real grid produces and refuse the rest. */
    @Test
    public void checkedAxisDiscriminates() throws Exception {
        assertEquals("a 393-node NTv1 axis", 393, GridExtents.checkedAxis("t", "x", 392.5));
        assertEquals("the smallest legal axis", 1, GridExtents.checkedAxis("t", "x", 0.5));
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, 0.0, -1.0, 0.49, 1e300}) {
            try {
                int got = GridExtents.checkedAxis("t", "x", bad);
                fail("checkedAxis(" + bad + ") must throw, returned " + got);
            } catch (GridFormatException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    // ================================================================= allocation

    /**
     * A refusal must happen <em>before</em> the allocation, not instead of surviving it.
     *
     * <p>Measured with {@code ThreadMXBean.getThreadAllocatedBytes}. The first block is the meter's
     * own positive control: it allocates 32 MB and requires the meter to see at least 16 MB, so a
     * meter that is unsupported, disabled or simply always zero fails here rather than certifying
     * every case below.
     */
    @Test
    public void aRefusalCostsNoMemory() throws Exception {
        com.sun.management.ThreadMXBean sun = allocationMeter();
        long id = Thread.currentThread().getId();

        // Each of these used to allocate between 2 GB and 6.4 GB, or die trying.
        assertRefusalIsCheap(sun, id, "CTABLE V2 100000^2",
                HostileGrids.ctable2(100000, 100000, 0, 0f, 0f), "hostile_alloc_ctable");
        assertRefusalIsCheap(sun, id, "NTv1 denormal increment",
                HostileGrids.ntv1(-90, 90, -180, 180, 1e-300, 1e-300, 0, 0, 0),
                "hostile_alloc_ntv1");

        byte[] tiff = HostileGrids
                .geoTiff(40000, 40000, 1, HostileGrids.stripsFor(40000, 40000, 4)).build();
        publish("hostile_alloc_tiff.tif", tiff);
        long t0 = sun.getThreadAllocatedBytes(id);
        try {
            GeoTiffDataset.open(tiff, "alloc").images().get(0).readSample(0);
            fail("must be refused");
        } catch (IOException expected) {
            long cost = sun.getThreadAllocatedBytes(id) - t0;
            assertTrue("refusing a 40000x40000 GeoTIFF must not allocate 6.4 GB; it allocated "
                    + cost + " bytes", cost < 8L * 1024 * 1024);
        }
    }

    /**
     * The allocation meter, with its own positive control: it allocates a known 32 MB and requires
     * the meter to report at least 16 MB of it. A meter that is unsupported, disabled, or simply
     * always zero fails here rather than certifying every measurement taken with it.
     */
    private static com.sun.management.ThreadMXBean allocationMeter() {
        java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory
                .getThreadMXBean();
        org.junit.Assume.assumeTrue("needs com.sun.management.ThreadMXBean",
                bean instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean sun = (com.sun.management.ThreadMXBean) bean;
        org.junit.Assume.assumeTrue("allocation measurement must be enabled",
                sun.isThreadAllocatedMemorySupported() && sun.isThreadAllocatedMemoryEnabled());
        long id = Thread.currentThread().getId();
        long before = sun.getThreadAllocatedBytes(id);
        byte[] control = new byte[32 * 1024 * 1024];
        control[control.length - 1] = 1;
        long controlCost = sun.getThreadAllocatedBytes(id) - before;
        assertTrue("the allocation meter must be able to see 32 MB; it reported " + controlCost,
                controlCost > 16L * 1024 * 1024);
        return sun;
    }

    private void assertRefusalIsCheap(com.sun.management.ThreadMXBean sun, long id, String label,
                                      byte[] bytes, String name) throws IOException {
        publish(name, bytes);
        long before = sun.getThreadAllocatedBytes(id);
        try {
            Grid.mergeGridFile(name, new ArrayList<Grid>());
            fail(label + " must be refused");
        } catch (IOException expected) {
            long cost = sun.getThreadAllocatedBytes(id) - before;
            assertTrue("refusing " + label + " must be cheap; it allocated " + cost + " bytes",
                    cost < 8L * 1024 * 1024);
        }
    }

    // ================================================================= stream coupling

    /**
     * {@code CTABLEV2} and {@code NTV1} take a {@code DataInputStream} and get the section length
     * from {@code available()}, which is exact for the {@code ByteArrayInputStream} that
     * {@code Resources.asDataStream} produces. This pins the coupling by loading the same crafted grid
     * from a file on disk through {@code DirectoryResourceResolver}, which is the other production
     * path and a different {@code SeekableByteReader}.
     */
    @Test
    public void aLegitimateGridLoadsThroughEveryProductionPath() throws Exception {
        byte[] good = HostileGrids.ctable2(21, 21, (float) Math.toRadians(0.25), 0f);
        assertEquals("through the directory resolver", 1, load("path_ctable", good).size());

        // And through the classpath resolver, using the pack the library ships.
        List<Grid> conus = new ArrayList<Grid>();
        Grid.mergeGridFile("conus", conus);
        assertEquals(1, conus.size());
        assertEquals("ctable2", conus.get(0).getFormat());
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(dir);
        try {
            for (Path p : stream) {
                if (Files.isDirectory(p)) {
                    deleteRecursively(p);
                } else {
                    Files.deleteIfExists(p);
                }
            }
        } finally {
            stream.close();
        }
        Files.deleteIfExists(dir);
    }
}

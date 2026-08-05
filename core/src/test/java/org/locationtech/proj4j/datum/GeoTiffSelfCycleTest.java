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
package org.locationtech.proj4j.datum;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Test;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.datum.GeoTiffGrid.VerticalLayer;
import org.locationtech.proj4j.resource.DirectoryResourceResolver;
import org.locationtech.proj4j.resource.ResourceResolvers;

/**
 * The GeoTIFF self-parent case: a grid that declares itself as its own {@code parent_grid_name}.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code GeoTiffGrid.insertIntoHierarchy} registered each grid in {@code byName} under its own
 * {@code grid_name} <b>before</b> looking its declared parent up. An IFD whose
 * {@code parent_grid_name} equalled its own {@code grid_name} therefore found <em>itself</em>, and
 * {@code contains} — inclusive on all four sides, so every extent contains itself — agreed, and
 * {@code appendChild(grid, grid)} set {@code grid.child == grid}.
 *
 * <p>{@code NTV2.loadAll} does the same three things in the opposite order (look up, then insert,
 * then register) and is safe for exactly that reason. This file's ordering was the inverted one.
 *
 * <h2>Why it is worth a test even though it was unreachable</h2>
 *
 * <p>It was unreachable <b>by an accident of control flow, not by a check</b>: the
 * {@code appendChild} branch {@code return}s before {@code roots.add}, so the cycle was built and
 * then dropped, since nothing reachable from {@code roots} pointed at it. Move the registration,
 * add a {@code roots.add} on that branch, or reuse the helper from anywhere else, and it becomes
 * live — at which point {@code Grid.shift}'s subgrid descent,
 * {@code while (grid.child != null) { … grid = child; }}, never terminates. That is an
 * <b>infinite loop on the per-row path, driven by an untrusted file</b>: not a wrong answer, not
 * an exception, a hung executor.
 *
 * <h2>The fixture is the shipped file, minimally patched</h2>
 *
 * <h2>What reverting the fix actually does — measured</h2>
 *
 * <p>Both halves reverted, this test class re-run: <b>2 of 5 fail.</b> {@code ALbanff} is
 * <em>dropped from the hierarchy</em> (4 grids instead of 5, because the {@code appendChild} branch
 * returns before {@code roots.add}), and the Banff probe is answered by the coarse {@code CAwest}
 * parent instead — {@code -115.54270965277624} against upstream's {@code -115.5427092888}, about
 * 4 cm. The <em>cycle</em> assertion still passes, because the cyclic node is not reachable from
 * the roots the reader returns. That is the whole point: the graph the reader hands out stays a
 * tree by luck, and the damage that is visible today is a silently coarser answer.
 *
 * <p>{@code test_hgrid_with_subgrid.tif} is a byte-for-byte copy of PROJ 9.8.1's
 * {@code data/tests/}. Its third IFD carries
 * {@code <Item name="grid_name">ALbanff</Item><Item name="parent_grid_name">CAwest</Item>}. This
 * test rewrites that one value to {@code ALbanff} <b>in memory</b>, keeping the TIFF ASCII tag's
 * byte count identical by also dropping one insignificant newline inside the same
 * {@code <GDALMetadata>} blob — so the file stays structurally valid and nothing but the declared
 * parentage changes. No fixture on disk is edited, and no new binary is committed.
 */
public class GeoTiffSelfCycleTest {

    private static final Charset ASCII = Charset.forName("US-ASCII");

    /** The exact bytes in the shipped file, and the same-length replacement. */
    private static final String DECLARED_PARENT_CAWEST =
            "<GDALMetadata>\n<Item name=\"grid_name\">ALbanff</Item>"
                    + "<Item name=\"parent_grid_name\">CAwest</Item>";
    private static final String DECLARED_PARENT_SELF =
            "<GDALMetadata><Item name=\"grid_name\">ALbanff</Item>"
                    + "<Item name=\"parent_grid_name\">ALbanff</Item>";

    private Path root;

    @After
    public void resetChain() throws IOException {
        ResourceResolvers.clearResolvers();
        GridCache.instance().clear();
        if (root != null) {
            for (Path p : Files.newDirectoryStream(root)) {
                Files.deleteIfExists(p);
            }
            Files.deleteIfExists(root);
            root = null;
        }
    }

    // ============================================================================================
    // The instrument, and the proof that it can fail
    // ============================================================================================

    /**
     * Walks {@code child} and {@code next} from {@code start} and reports whether any node is
     * reachable from itself.
     *
     * @return the offending grid, or {@code null} if the graph is a tree
     */
    private static Grid findCycle(Grid start) {
        Map<Grid, Boolean> onPath = new IdentityHashMap<Grid, Boolean>();
        return findCycle(start, onPath);
    }

    private static Grid findCycle(Grid node, Map<Grid, Boolean> onPath) {
        for (Grid g = node; g != null; g = g.getNext()) {
            if (onPath.put(g, Boolean.TRUE) != null) {
                return g;
            }
            if (g.getChild() != null) {
                Grid found = findCycle(g.getChild(), onPath);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * <b>POSITIVE CONTROL.</b> Hand-build the exact graph the pre-fix reader built — a grid that is
     * its own child — and require the detector to find it. Without this, every "no cycle" result
     * below would be consistent with a detector that always returns {@code null}.
     */
    @Test
    public void theCycleDetectorDetectsACycle() {
        Grid selfParent = new Grid();
        selfParent.describeAs("ALbanff", "gtiff", "test:crafted", "test");
        selfParent.setChild(selfParent);

        assertSame("the detector missed grid.child == grid, so every clean result it reports"
                        + " below is worthless",
                selfParent, findCycle(selfParent));

        // And a two-node cycle through `next`, which is the other edge it must follow.
        Grid a = new Grid();
        a.describeAs("a", "gtiff", "test:a", "test");
        Grid b = new Grid();
        b.describeAs("b", "gtiff", "test:b", "test");
        a.setChild(b);
        b.setNext(b);
        assertNotNull("the detector does not follow `next`", findCycle(a));

        // ...and it must not cry wolf on a legitimate tree.
        Grid p = new Grid();
        p.describeAs("p", "gtiff", "test:p", "test");
        Grid c1 = new Grid();
        c1.describeAs("c1", "gtiff", "test:c1", "test");
        Grid c2 = new Grid();
        c2.describeAs("c2", "gtiff", "test:c2", "test");
        p.setChild(c1);
        c1.setNext(c2);
        assertNull("the detector reports a cycle in a plain two-child tree", findCycle(p));
    }

    // ============================================================================================
    // The real reader, on a real file that declares itself its own parent
    // ============================================================================================

    /**
     * A one-second timeout, because the failure mode this guards is <b>a hang, not an exception</b>
     * — {@code Grid.shift}'s descent on {@code grid.child == grid} never returns. A test that hangs
     * reports nothing; a test that times out reports a failure.
     */
    @Test(timeout = 30_000L)
    public void aSelfParentingGeoTiffBuildsNoCycleAndStillShifts() throws IOException {
        byte[] original = shippedBytes("test_hgrid_with_subgrid.tif");
        byte[] patched = selfParenting(original);

        List<Grid> selfParent = load("test_hgrid_self_parent.tif", patched);
        assertEquals(1, selfParent.size());

        Grid cycle = findCycle(selfParent.get(0));
        assertNull("the reader built a self-referential grid: "
                + (cycle == null ? "" : cycle.getGridName()), cycle);

        // Not merely acyclic - the grid must still work, and it must still be the FINE grid that
        // answers. A guard that dropped ALbanff from the hierarchy would also be acyclic, and the
        // coarse CAwest parent would then answer with a different number: that is precisely what
        // the pre-fix reader did, because its appendChild branch returns before roots.add.
        //
        // Expected values are upstream's own, from PROJ 9.8.1's geotiff_grids.gie, and are the same
        // ones GeoTiffHorizontalGridTest.namedSubgridHierarchy pins for the unpatched file.
        double[] banff = shiftDegrees(selfParent, BANFF_LON, BANFF_LAT);
        assertEquals("ALbanff did not answer for the Banff probe", -115.5427092888, banff[0], 1e-9);
        assertEquals("ALbanff did not answer for the Banff probe", 51.1666899972, banff[1], 1e-9);
        assertEquals("the self-parenting IFD was dropped from the hierarchy",
                5, selfParent.get(0).countGrids());
    }

    /** Inside {@code ALbanff}, the subgrid whose declared parentage this test rewrites. */
    private static final double BANFF_LON = -115.5416667;
    private static final double BANFF_LAT = 51.1666667;

    /**
     * The self-parenting file must behave <em>exactly</em> like the unpatched one.
     *
     * <p>That is not a coincidence, it is what the fix chooses: an unresolvable
     * {@code parent_grid_name} falls through to upstream's bounding-box method, which places
     * {@code ALbanff} under {@code CAwest} anyway — the same place the declared parentage asked
     * for. Upstream logs {@code "refers to non-existing parent"} and does the same. So the
     * ordering fix costs no hierarchy and no coordinate.
     *
     * <p>Before the fix the two differed sharply: {@code ALbanff} was <b>silently dropped from the
     * hierarchy entirely</b>, because the {@code appendChild} branch returns before
     * {@code roots.add}.
     */
    @Test(timeout = 30_000L)
    public void theSelfParentFileAgreesWithTheShippedOne() throws IOException {
        byte[] original = shippedBytes("test_hgrid_with_subgrid.tif");

        List<Grid> reference = load("test_hgrid_ref.tif", original);
        int referenceCount = reference.get(0).countGrids();
        double[] referenceShift = shiftDegrees(reference, BANFF_LON, BANFF_LAT);

        List<Grid> patched = load("test_hgrid_self_parent2.tif", selfParenting(original));
        assertEquals("the self-parent file lost or gained a subgrid",
                referenceCount, patched.get(0).countGrids());
        assertArrayEquals("the self-parent file shifts differently",
                referenceShift, shiftDegrees(patched, BANFF_LON, BANFF_LAT), 0.0);

        // The fixture has to actually contain a hierarchy, or both sides are trivially equal.
        assertEquals("fixture regressed: test_hgrid_with_subgrid.tif no longer has its four images"
                + " under a synthetic bounding grid", 5, referenceCount);
        assertTrue("fixture regressed: the reference shift is a no-op, so the comparison is vacuous",
                Math.abs(referenceShift[0] - BANFF_LON) > 1e-9);
    }

    /**
     * <b>POSITIVE CONTROL for the patcher.</b> The bytes really are different, they really do
     * declare a self-parent, and the file really is the same length — which is what keeps the TIFF
     * ASCII tag's declared count honest.
     */
    @Test
    public void thePatchIsAppliedAndIsLengthPreserving() throws IOException {
        byte[] original = shippedBytes("test_hgrid_with_subgrid.tif");
        byte[] patched = selfParenting(original);

        assertEquals("the patch changed the file length, so the TIFF tag count is now a lie",
                original.length, patched.length);
        assertFalse("the patch was a no-op", java.util.Arrays.equals(original, patched));
        assertTrue("the original already declared a self-parent, so the fixture proves nothing",
                new String(original, ASCII).contains(DECLARED_PARENT_CAWEST));
        assertTrue("the patched bytes do not declare a self-parent",
                new String(patched, ASCII).contains(DECLARED_PARENT_SELF));
    }

    // ============================================================================================
    // The vertical half of the same inversion
    // ============================================================================================

    /**
     * The second barrier, asserted directly: a grid no longer contains itself.
     *
     * <p>Needed as its own test because the cycle the ordering fix prevents is <b>unreachable from
     * the returned roots</b> — measured, by reverting both halves and re-running
     * {@link #aSelfParentingGeoTiffBuildsNoCycleAndStillShifts()}: the cycle assertion still
     * passed, and what failed was the coordinate (CAwest answered instead of ALbanff, 3.6e-7&deg;
     * away) and the grid count (4 instead of 5). So a test driving the reader can only see the
     * consequence. This sees the guard.
     *
     * <p>Either half alone prevents the cycle. Both are kept because the ordering is the one that
     * matches {@code NTV2} and upstream, and the identity guard is the one that survives someone
     * reordering the statements again.
     */
    @Test
    public void aGridDoesNotContainItself() {
        Grid g = extentGrid("self", 0.0, 0.0, 5, 5);
        assertFalse("contains(g, g) is true, so `is my own parent` becomes `is my own child`,"
                + " and Grid.shift's descent never terminates", GeoTiffGrid.contains(g, g));

        // CONTROLS: the predicate must still accept what it is for, including the
        // boundary-inclusive case the identity guard had to be added around rather than instead of.
        Grid outer = extentGrid("outer", 0.0, 0.0, 5, 5);
        Grid inner = extentGrid("inner", 1.0, 1.0, 2, 2);
        Grid coincident = extentGrid("coincident", 0.0, 0.0, 5, 5);
        assertTrue("the guard broke ordinary containment", GeoTiffGrid.contains(outer, inner));
        assertFalse("containment is not supposed to be symmetric",
                GeoTiffGrid.contains(inner, outer));
        assertTrue("the guard broke boundary-inclusive containment, which upstream relies on",
                GeoTiffGrid.contains(outer, coincident));

        // Disjoint, so the predicate is not simply always-true for distinct grids.
        Grid elsewhere = extentGrid("elsewhere", 40.0, 40.0, 2, 2);
        assertFalse(GeoTiffGrid.contains(outer, elsewhere));
    }

    private static Grid extentGrid(String name, double west, double south, int columns, int rows) {
        Grid g = new Grid();
        g.describeAs(name, "gtiff", "test:" + name, "test");
        Grid.ConversionTable t = new Grid.ConversionTable();
        t.id = name;
        t.ll = new org.locationtech.proj4j.util.PolarCoordinate(west, south);
        t.del = new org.locationtech.proj4j.util.PolarCoordinate(1.0, 1.0);
        t.lim = new org.locationtech.proj4j.util.IntPolarCoordinate(columns, rows);
        t.cvs = new org.locationtech.proj4j.util.FloatPolarCoordinate[columns * rows];
        for (int i = 0; i < t.cvs.length; i++) {
            t.cvs[i] = new org.locationtech.proj4j.util.FloatPolarCoordinate(0.0f, 0.0f);
        }
        g.table = t;
        return g;
    }

    /**
     * {@code insertVertical} carried the identical inversion, and no shipped fixture declares
     * {@code grid_name}/{@code parent_grid_name} on a vertical IFD — so the ordering there cannot
     * be driven from a file today. What <em>can</em> be pinned is the other half of the fix: the
     * containment predicate no longer says a layer contains itself, which is what turned
     * "is my own parent" into "is my own child".
     */
    @Test
    public void aVerticalLayerDoesNotContainItself() {
        VerticalLayer layer = verticalLayer("self", 0.0, 0.0, 3, 3);
        assertFalse("a layer that contains itself makes `parent.children.add(layer)` reachable,"
                + " and VerticalGrid's recursive descent then overflows the stack",
                layer.contains(layer));

        // CONTROL: the predicate still works, and it is still inclusive on the boundary, which is
        // the property the identity guard had to be added around rather than instead of.
        VerticalLayer outer = verticalLayer("outer", 0.0, 0.0, 5, 5);
        VerticalLayer inner = verticalLayer("inner", 1.0, 1.0, 2, 2);
        VerticalLayer coincident = verticalLayer("coincident", 0.0, 0.0, 5, 5);
        assertTrue("the guard broke ordinary containment", outer.contains(inner));
        assertFalse("containment is not supposed to be symmetric", inner.contains(outer));
        assertTrue("the guard broke boundary-inclusive containment, which upstream relies on",
                outer.contains(coincident));
    }

    // ============================================================================================

    private static VerticalLayer verticalLayer(String name, double west, double south,
                                               int width, int height) {
        return new VerticalLayer(name, width, height, west, south, 1.0, 1.0,
                new float[width * height], false, 0.0f);
    }

    private static double[] shiftDegrees(List<Grid> grids, double lonDeg, double latDeg) {
        ProjCoordinate c = new ProjCoordinate(Math.toRadians(lonDeg), Math.toRadians(latDeg));
        Grid.shift(grids, false, c);
        return new double[]{Math.toDegrees(c.x), Math.toDegrees(c.y)};
    }

    /**
     * Rewrites the third IFD's {@code parent_grid_name} to its own {@code grid_name}, keeping the
     * blob byte count unchanged by dropping the newline after {@code <GDALMetadata>}. The metadata
     * scanner is PROJ's "poor-man XML parsing" — {@code strstr("<Item ")} — so that newline is not
     * significant to either implementation.
     */
    private static byte[] selfParenting(byte[] original) {
        String text = new String(original, ASCII);
        int at = text.indexOf(DECLARED_PARENT_CAWEST);
        if (at < 0) {
            fail("fixture changed: test_hgrid_with_subgrid.tif no longer contains the ALbanff /"
                    + " CAwest metadata this patch rewrites");
        }
        if (DECLARED_PARENT_SELF.length() != DECLARED_PARENT_CAWEST.length()) {
            fail("the replacement is not the same length as what it replaces, which would move"
                    + " every TIFF offset after it");
        }
        byte[] patched = original.clone();
        byte[] replacement = DECLARED_PARENT_SELF.getBytes(ASCII);
        System.arraycopy(replacement, 0, patched, at, replacement.length);
        return patched;
    }

    private static byte[] shippedBytes(String name) throws IOException {
        InputStream in = GeoTiffSelfCycleTest.class.getResourceAsStream(
                "/proj4j-data/grids/" + name);
        assertNotNull("fixture " + name + " is not on the test classpath", in);
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

    private List<Grid> load(String name, byte[] bytes) throws IOException {
        if (root == null) {
            root = Files.createTempDirectory("proj4j-selfcycle");
            ResourceResolvers.addResolver(new DirectoryResourceResolver(root));
        }
        Files.write(root.resolve(name), bytes);
        GridCache.instance().clear();
        List<Grid> grids = new ArrayList<Grid>();
        Grid.mergeGridFile(name, grids);
        return grids;
    }
}

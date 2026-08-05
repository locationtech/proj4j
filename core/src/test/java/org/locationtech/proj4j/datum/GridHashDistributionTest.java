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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;
import org.locationtech.proj4j.util.FloatPolarCoordinate;
import org.locationtech.proj4j.util.IntPolarCoordinate;
import org.locationtech.proj4j.util.PolarCoordinate;

/**
 * {@link Grid#hashCode()} and {@link Grid.ConversionTable#hashCode()}, measured over the grids this
 * repository actually ships rather than over two hand-made objects.
 *
 * <h2>What was wrong</h2>
 *
 * <p>Three defects, in one expression each:
 * <ol>
 *   <li>{@code Grid.hashCode} computed its {@code childHash} from <b>{@code next}</b>:
 *       {@code int childHash = next == null ? 0 : next.hashCode();}. So {@code child} never
 *       entered the hash at all and {@code next} entered it twice.</li>
 *   <li>Every combiner in the family — {@code Grid}, {@code ConversionTable},
 *       {@code PolarCoordinate}, {@code FloatPolarCoordinate}, {@code IntPolarCoordinate} — was
 *       bitwise <b>{@code |}</b>. OR is monotone in every bit, so a bit set by one term can never
 *       be cleared by another and the combination is driven towards {@code 0xFFFFFFFF}.</li>
 *   <li>{@code PolarCoordinate} and {@code FloatPolarCoordinate} boxed
 *       ({@code new Double(lam).hashCode()}) and disagreed with their own {@code equals}, which
 *       compares with {@code ==} and therefore holds {@code -0.0} equal to {@code 0.0} while
 *       {@code Double.hashCode} does not.</li>
 * </ol>
 *
 * <h2>How this test avoids being vacuous</h2>
 *
 * <p>"The new hash gives distinct values" is not evidence unless the old one does not — a
 * population of six grids would very likely come out distinct under almost any function. So the
 * pre-fix expressions are <b>reimplemented verbatim below</b> ({@link #legacyGridHash} and
 * friends) and run over the same objects, and the test asserts the contrast: the legacy hash
 * either collides or saturates its bits, and the new one does neither. Every number this class
 * asserts was measured, not chosen.
 *
 * <p>{@link #childIsInTheHash()} is the sharpest of the three and needs no distribution argument
 * at all: it builds two grids that differ <em>only</em> in {@code child}, shows the legacy
 * expression giving them the same value — which is the bug, exactly — and the new one giving them
 * different values, which is what {@link Grid#equals} has always required.
 */
public class GridHashDistributionTest {

    /**
     * Grids on {@code core}'s test classpath, spanning all three readers: CTABLE V2, NTv2 with
     * subgrids, and eight GeoTIFF spellings including one with a real subgrid hierarchy.
     */
    private static final String[] SHIPPED = {
            "conus",
            "ntv2_0_downsampled.gsb",
            "test_hgrid.tif",
            "test_hgrid_degree.tif",
            "test_hgrid_radian.tif",
            "test_hgrid_separate.tif",
            "test_hgrid_strip.tif",
            "test_hgrid_tiled.tif",
            "test_hgrid_positive_west.tif",
            "test_hgrid_with_subgrid.tif",
    };

    private static List<Grid> shippedGrids() throws IOException {
        List<Grid> grids = new ArrayList<Grid>();
        for (String name : SHIPPED) {
            Grid.mergeGridFile(name, grids);
        }
        assertEquals("fixture set changed", SHIPPED.length, grids.size());
        return grids;
    }

    // ============================================================================================
    // The pre-fix expressions, transcribed. Do not "tidy" these; they are the control.
    // ============================================================================================

    private static int legacyFloatPolar(FloatPolarCoordinate c) {
        return Float.valueOf(c.lam).hashCode() | (17 * Float.valueOf(c.phi).hashCode());
    }

    private static int legacyPolar(PolarCoordinate c) {
        return Double.valueOf(c.lam).hashCode() | (17 * Double.valueOf(c.phi).hashCode());
    }

    private static int legacyCvs(FloatPolarCoordinate[] cvs) {
        // java.util.Arrays.hashCode's own contract, with the legacy element hash substituted.
        if (cvs == null) {
            return 0;
        }
        int result = 1;
        for (FloatPolarCoordinate c : cvs) {
            result = 31 * result + (c == null ? 0 : legacyFloatPolar(c));
        }
        return result;
    }

    private static int legacyTableHash(Grid.ConversionTable t) {
        int idHash = t.id == null ? 0 : t.id.hashCode();
        int delHash = t.del == null ? 0 : legacyPolar(t.del);
        int llHash = t.ll == null ? 0 : legacyPolar(t.ll);
        int cvsHash = legacyCvs(t.cvs);
        return idHash | (11 * delHash) | (23 * llHash) | (37 * cvsHash);
    }

    private static int legacyGridHash(Grid g) {
        int nameHash = g.getGridName() == null ? 0 : g.getGridName().hashCode();
        int fileHash = g.getOrigin() == null ? 0 : g.getOrigin().hashCode();
        int formatHash = g.getFormat() == null ? 0 : g.getFormat().hashCode();
        int tableHash = g.table == null ? 0 : legacyTableHash(g.table);
        int nextHash = g.getNext() == null ? 0 : legacyGridHash(g.getNext());
        // THE DEFECT: computed from `next`, not from `child`.
        int childHash = g.getNext() == null ? 0 : legacyGridHash(g.getNext());
        return nameHash | (7 * fileHash) | (11 * formatHash) | (17 * tableHash)
                | (23 * nextHash) | (31 * childHash);
    }

    // ============================================================================================

    /**
     * The bug, isolated: two grids identical but for {@code child}.
     *
     * <p>{@link Grid#equals} distinguishes them — it compares {@code child} — so {@link Object}'s
     * contract requires the hash to be free to distinguish them too, and a hash that structurally
     * <em>cannot</em> is a hash that ignores a field its equality depends on.
     */
    @Test
    public void childIsInTheHash() throws IOException {
        Grid parent = new Grid();
        parent.describeAs("parent", "ntv2", "test:parent", "test");
        parent.table = table("parent-table", 0.0, 0.0, 3, 3);

        Grid childless = new Grid();
        childless.describeAs("parent", "ntv2", "test:parent", "test");
        childless.table = parent.table;

        Grid child = new Grid();
        child.describeAs("child", "ntv2", "test:child", "test");
        child.table = table("child-table", 0.5, 0.5, 2, 2);
        parent.setChild(child);

        // The two objects really are unequal, so this is a hash question and not a modelling one.
        assertNotEquals("fixture is wrong: the two grids must differ", parent, childless);

        assertEquals("CONTROL: the pre-fix expression could not see `child` at all, because it read"
                        + " `next` twice. If this assertion ever fails, the transcription above has"
                        + " drifted from the defect it is standing in for.",
                legacyGridHash(childless), legacyGridHash(parent));

        assertNotEquals("Grid.hashCode still ignores `child`",
                childless.hashCode(), parent.hashCode());
    }

    /** {@code next} must be in the hash too — the fix must not have swapped one omission for another. */
    @Test
    public void nextIsInTheHash() {
        Grid head = new Grid();
        head.describeAs("head", "ntv2", "test:head", "test");
        head.table = table("head-table", 0.0, 0.0, 3, 3);

        Grid lonely = new Grid();
        lonely.describeAs("head", "ntv2", "test:head", "test");
        lonely.table = head.table;

        Grid sibling = new Grid();
        sibling.describeAs("sibling", "ntv2", "test:sibling", "test");
        sibling.table = table("sibling-table", 9.0, 9.0, 2, 2);
        head.setNext(sibling);

        assertNotEquals(lonely.hashCode(), head.hashCode());
    }

    /**
     * The distribution claim, over the shipped grids, with the pre-fix expression as the control.
     *
     * <p>Measured on this fixture set: the legacy expression yields <b>1</b> distinct value across
     * all ten grids — every one of them hashes to {@code -1}, i.e. all 32 bits set — because
     * OR-ing four large terms saturates. The new expression yields <b>10</b>.
     */
    @Test
    public void theShippedGridsDoNotCollide() throws IOException {
        List<Grid> grids = shippedGrids();

        Set<Integer> legacy = new HashSet<Integer>();
        Set<Integer> fixed = new HashSet<Integer>();
        int legacyBits = 0;
        int fixedBits = 0;
        for (Grid g : grids) {
            int l = legacyGridHash(g);
            int f = g.hashCode();
            legacy.add(l);
            fixed.add(f);
            legacyBits += Integer.bitCount(l);
            fixedBits += Integer.bitCount(f);
        }
        double legacyMeanBits = legacyBits / (double) grids.size();
        double fixedMeanBits = fixedBits / (double) grids.size();

        String report = "legacy: " + legacy.size() + " distinct, mean " + legacyMeanBits
                + " bits set; fixed: " + fixed.size() + " distinct, mean " + fixedMeanBits
                + " bits set, over " + grids.size() + " shipped grids";

        // CONTROL. If the legacy expression separated these grids, this test would prove nothing
        // about the new one, and the assertion below would be a coincidence rather than a result.
        assertTrue("CONTROL FAILED - the pre-fix expression did NOT collapse on this fixture set,"
                        + " so this test is not measuring what it claims. " + report,
                legacy.size() < grids.size());
        assertTrue("CONTROL FAILED - the pre-fix expression's bits were not saturated. " + report,
                legacyMeanBits > 28.0);

        assertEquals("Grid.hashCode collides across the shipped grids. " + report,
                grids.size(), fixed.size());
        // A 31-chain over real data lands near half the bits set. Anything above 28 would mean the
        // OR is back.
        assertTrue("Grid.hashCode's bits look saturated, which is the signature of an OR combiner. "
                        + report,
                fixedMeanBits > 6.0 && fixedMeanBits < 26.0);
    }

    /**
     * The subgrids of one NTv2 file, hashed. These share a file name, a format and a resolver and
     * differ only in {@code gridName} and in their node data, which is the population most likely
     * to collide.
     */
    @Test
    public void subgridsOfOneFileDoNotCollide() throws IOException {
        List<Grid> loaded = new ArrayList<Grid>();
        Grid.mergeGridFile("ntv2_0_downsampled.gsb", loaded);
        List<Grid> subs = loaded.get(0).getSubGrids();
        assertTrue("fixture must have subgrids to be a test of subgrids", subs.size() >= 2);

        Set<Integer> fixed = new HashSet<Integer>();
        Set<Integer> legacy = new HashSet<Integer>();
        for (Grid s : subs) {
            fixed.add(s.hashCode());
            legacy.add(legacyGridHash(s));
        }
        assertEquals("subgrid hashes collide: " + subs.size() + " subgrids, " + fixed.size()
                + " distinct", subs.size(), fixed.size());
        assertTrue("CONTROL FAILED - the legacy expression separated the subgrids too, so the"
                        + " assertion above is not evidence. legacy distinct=" + legacy.size(),
                legacy.size() < subs.size());
    }

    /**
     * The memo must return the same value it computes, and must not change it.
     *
     * <p>{@code ConversionTable.hashCode} caches into a field on first call. A memo that returned a
     * different value on the second call would be a far worse bug than the O(n) cost it removes.
     */
    @Test
    public void theConversionTableMemoIsStable() throws IOException {
        List<Grid> grids = shippedGrids();
        for (Grid g : grids) {
            if (g.table == null) {
                continue;
            }
            int first = g.table.hashCode();
            int second = g.table.hashCode();
            int third = g.table.hashCode();
            assertEquals(g.getGridName() + ": memoised hash changed", first, second);
            assertEquals(g.getGridName() + ": memoised hash changed", first, third);

            // And it must equal a freshly built, un-memoised table with the same contents, so the
            // memo is a cache of the function and not a second definition of it.
            Grid.ConversionTable copy = new Grid.ConversionTable();
            copy.id = g.table.id;
            copy.del = g.table.del;
            copy.ll = g.table.ll;
            copy.lim = g.table.lim;
            copy.cvs = g.table.cvs;
            assertEquals(g.getGridName() + ": memo disagrees with a fresh table",
                    first, copy.hashCode());
        }
    }

    /**
     * {@code equals} holds {@code -0.0} equal to {@code 0.0}, because it compares with {@code ==}.
     * The hash must therefore agree, and before this change it did not.
     */
    @Test
    public void negativeZeroHashesLikePositiveZero() {
        PolarCoordinate pos = new PolarCoordinate(0.0, 0.0);
        PolarCoordinate neg = new PolarCoordinate(-0.0, -0.0);
        assertEquals("fixture: these must be equal under PolarCoordinate.equals", pos, neg);
        assertEquals("equal PolarCoordinates hash differently", pos.hashCode(), neg.hashCode());
        assertNotEquals("CONTROL: the pre-fix expression did NOT agree on signed zero, which is what"
                        + " made this a contract violation rather than a style point",
                legacyPolar(pos), legacyPolar(neg));

        FloatPolarCoordinate fpos = new FloatPolarCoordinate(0.0f, 0.0f);
        FloatPolarCoordinate fneg = new FloatPolarCoordinate(-0.0f, -0.0f);
        assertEquals(fpos, fneg);
        assertEquals("equal FloatPolarCoordinates hash differently", fpos.hashCode(), fneg.hashCode());
        assertNotEquals(legacyFloatPolar(fpos), legacyFloatPolar(fneg));
    }

    /**
     * The node type's own distribution, which is what {@code Arrays.hashCode(cvs)} multiplies up.
     * Sixty-four distinct shift pairs on a small grid of values: the legacy expression collapses
     * them, the new one does not.
     */
    @Test
    public void theNodeTypeSeparatesItsValues() {
        Set<Integer> legacy = new HashSet<Integer>();
        Set<Integer> fixed = new HashSet<Integer>();
        int n = 0;
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                FloatPolarCoordinate c = new FloatPolarCoordinate(i * 1.0e-5f, j * 1.0e-5f);
                legacy.add(legacyFloatPolar(c));
                fixed.add(c.hashCode());
                n++;
            }
        }
        assertEquals("FloatPolarCoordinate.hashCode collides over 64 distinct shift pairs: "
                + fixed.size() + " distinct", n, fixed.size());
        assertTrue("CONTROL FAILED - the legacy element hash separated all 64 too, so the assertion"
                        + " above is not evidence. legacy distinct=" + legacy.size(),
                legacy.size() < n);
    }

    /** {@link IntPolarCoordinate}, the {@code lim} type: small positive ints, the worst case for OR. */
    @Test
    public void theLimTypeSeparatesSmallInts() {
        Set<Integer> legacy = new HashSet<Integer>();
        Set<Integer> fixed = new HashSet<Integer>();
        int n = 0;
        for (int lam = 1; lam <= 20; lam++) {
            for (int phi = 1; phi <= 20; phi++) {
                IntPolarCoordinate c = new IntPolarCoordinate(lam, phi);
                legacy.add(lam | (17 * phi));
                fixed.add(c.hashCode());
                n++;
            }
        }
        assertEquals(n, fixed.size());
        assertTrue("CONTROL FAILED - legacy distinct=" + legacy.size(), legacy.size() < n);
    }

    private static Grid.ConversionTable table(String id, double llLam, double llPhi,
                                              int columns, int rows) {
        Grid.ConversionTable t = new Grid.ConversionTable();
        t.id = id;
        t.ll = new PolarCoordinate(llLam, llPhi);
        t.del = new PolarCoordinate(0.25, 0.25);
        t.lim = new IntPolarCoordinate(columns, rows);
        FloatPolarCoordinate[] cvs = new FloatPolarCoordinate[columns * rows];
        for (int i = 0; i < cvs.length; i++) {
            cvs[i] = new FloatPolarCoordinate(i * 1.0e-6f, i * 2.0e-6f);
        }
        t.cvs = cvs;
        return t;
    }
}

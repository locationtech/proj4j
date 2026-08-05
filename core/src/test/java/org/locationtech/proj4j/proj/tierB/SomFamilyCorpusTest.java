/*******************************************************************************
 * Copyright 2026
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

package org.locationtech.proj4j.proj.tierB;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.proj.MisrSpaceObliqueMercatorProjection;
import org.locationtech.proj4j.proj.Projection;
import org.locationtech.proj4j.proj.SpaceObliqueMercatorProjection;

/**
 * {@code +proj=som} and {@code +proj=misrsom} against {@code builtins.gie}.
 *
 * <p>48 assertions: {@code som} has four operation blocks of 8 (two spelling the same angles in
 * degrees and in radians, on GRS80 and on a sphere), {@code misrsom} has two of 8. Every block is
 * 4 forward rows and 4 inverse rows at {@code tolerance 0.1 mm}.
 *
 * <p><b>The two {@code som} spellings must give identical answers.</b>
 * {@code +inc_angle=1.7157253262878522r} and {@code +inc_angle=98.30382} are the same angle written
 * two ways, and the corpus asserts byte-identical expected values for both — which is only true if
 * the {@code r} radian suffix is honoured. That is exactly the parameter form
 * {@code Proj4Parser.parseAngle} handles and this test's reader mirrors.
 */
public class SomFamilyCorpusTest {

    @Test
    public void somMatchesCorpus() {
        TierBCorpus.assertAll("builtins.gie", "som", 32);
    }

    @Test
    public void misrsomMatchesCorpus() {
        TierBCorpus.assertAll("builtins.gie", "misrsom", 16);
    }

    /**
     * The two {@code som} spellings of the same orbit produce the same expected values, and there
     * are exactly four blocks. Guards against a corpus move silently halving the count.
     */
    @Test
    public void somHasFourBlocksTwoOfWhichUseTheRadianSuffix() {
        List<TierBCorpus.Block> blocks = TierBCorpus.blocksFor("builtins.gie", "som");
        assertEquals("som operation blocks in builtins.gie", 4, blocks.size());
        int radianSuffix = 0;
        for (TierBCorpus.Block b : blocks) {
            assertEquals("assertions per som block", 8, b.assertionCount());
            if (b.operation.contains("r ") || b.operation.endsWith("r")) {
                radianSuffix++;
            }
        }
        assertEquals("som blocks written with the r radian suffix", 2, radianSuffix);
    }

    /**
     * {@code misrsom} rejects a path outside {@code [1, 233]}, which is
     * {@code som.cpp:281}'s own check — not a Proj4J invention.
     */
    @Test
    public void misrsomRejectsPathOutOfRange() {
        for (int path : new int[] {-1, 0, 234, 1000}) {
            MisrSpaceObliqueMercatorProjection p = new MisrSpaceObliqueMercatorProjection();
            p.setEllipsoid(Ellipsoid.GRS80);
            p.setPath(path);
            try {
                p.initialize();
                fail("+proj=misrsom +path=" + path + " should be rejected");
            } catch (InvalidValueException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("path"));
            }
        }
    }

    /**
     * A fully specified {@code +proj=som} now reaches this class with its orbital parameters
     * intact.
     *
     * <p><b>This assertion used to be its own inverse</b>, and the history is the point.
     * {@code SpaceObliqueMercatorProjection.initialize()} carried the one deliberate divergence
     * from 9.8.1 in this family: it <em>refused</em> when {@code +inc_angle}/{@code +ps_rev}/
     * {@code +asc_lon} had never been set, because {@code Proj4Parser} dropped all three and
     * upstream defaults all three to {@code 0} — and {@code 0} passes every one of upstream's own
     * range checks, so verbatim behaviour plus the parser gap would have answered a fully
     * specified definition with the coordinates of a satellite that does not move.
     *
     * <p>{@code Proj4Parser} now dispatches the three keys, so the gap is closed and the guard is
     * deleted; this class is verbatim 9.8.1 again, defaults included. What is asserted here is the
     * fact that made the guard removable: the values <b>arrive</b>. {@code Q->alf} and
     * {@code P->lam0} are radians, converted on the way in because upstream reads both with
     * {@code pj_param}'s {@code r} sigil ({@code som.cpp:250,259}), and {@code Q->p22} is a plain
     * double in days per revolution.
     *
     * <p>{@code misrsom}'s equivalent refusal is <em>not</em> a divergence and stays — see
     * {@link #misrsomRejectsPathOutOfRange()}, where {@code path <= 0} is upstream's own error.
     */
    @Test
    public void aFullySpecifiedSomKeepsItsOrbitalParameters() {
        CoordinateReferenceSystem crs = new CRSFactory().createFromParameters("som",
                "+proj=som +ellps=GRS80 +inc_angle=98.30382 +ps_rev=0.06866666666666667 "
                        + "+asc_lon=127.7605356226");
        SpaceObliqueMercatorProjection p =
                (SpaceObliqueMercatorProjection) crs.getProjection();
        assertEquals("+inc_angle, radians", Math.toRadians(98.30382), p.getIncidenceAngle(), 1e-15);
        assertEquals("+ps_rev, day/rev", 0.06866666666666667, p.getPeriodOfRevolution(), 0.0);
        assertEquals("+asc_lon, radians", Math.toRadians(127.7605356226),
                p.getProjectionLongitude(), 1e-15);
    }

    /** {@code +proj=som} and {@code +proj=misrsom} resolve through the registry. */
    @Test
    public void bothNamesAreRegistered() {
        Registry registry = new Registry();
        assertNotNull("+proj=som", registry.getProjection("som"));
        assertNotNull("+proj=misrsom", registry.getProjection("misrsom"));
        assertTrue(registry.getProjection("som") instanceof SpaceObliqueMercatorProjection);
        assertTrue(registry.getProjection("misrsom")
                instanceof MisrSpaceObliqueMercatorProjection);
    }

    /**
     * {@code som}, parameterised as {@code misrsom} is, agrees with {@code misrsom} to the last
     * bit — the two really are one kernel with different presets.
     *
     * <p>{@code misrsom +path=1} is {@code asc_lon = 129.3056° − 360°/233}, and the corpus's
     * {@code som} blocks spell exactly that number, so this also checks that the derivation from
     * {@code +path} is right rather than merely plausible.
     */
    @Test
    public void somWithMisrPresetsEqualsMisrsom() {
        Projection som = TierBCorpus.build(
                "+proj=som +ellps=GRS80 +inc_angle=98.30382 +ps_rev=0.06866666666666667 "
                        + "+asc_lon=127.7605356226");
        Projection misrsom = TierBCorpus.build("+proj=misrsom +ellps=GRS80 +path=1");
        for (double[] pt : new double[][] {{2, 1}, {2, -1}, {-2, 1}, {-2, -1}, {0, 0}}) {
            ProjCoordinate a = new ProjCoordinate();
            ProjCoordinate b = new ProjCoordinate();
            som.project(new ProjCoordinate(pt[0], pt[1]), a);
            misrsom.project(new ProjCoordinate(pt[0], pt[1]), b);
            // 0.1 mm is the corpus bar; the asc_lon literal in the corpus is a 10-digit rounding
            // of 129.3056 - 360/233, so this is agreement to the corpus's own precision, not to
            // the bit.
            assertEquals("som vs misrsom x at " + pt[0] + "," + pt[1], a.x, b.x, 1e-4);
            assertEquals("som vs misrsom y at " + pt[0] + "," + pt[1], a.y, b.y, 1e-4);
        }
    }
}

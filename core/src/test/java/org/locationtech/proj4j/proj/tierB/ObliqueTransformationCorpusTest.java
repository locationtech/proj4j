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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.proj.ObliqueTransformationProjection;
import org.locationtech.proj4j.proj.Projection;

/**
 * {@code +proj=ob_tran} against {@code builtins.gie} and {@code more_builtins.gie}.
 *
 * <p>Four corpus operations, 12 assertions of which 2 are {@code expect failure}:
 *
 * <table>
 * <caption>the four blocks</caption>
 * <tr><th>file:line</th><th>operation</th><th>assertions</th></tr>
 * <tr><td>{@code builtins.gie:5049}</td>
 *     <td>{@code +o_proj=latlon +o_lon_p=20 +o_lat_p=20 +lon_0=180}</td><td>8</td></tr>
 * <tr><td>{@code builtins.gie:5072}</td><td>{@code +o_proj +o_proj=ob_tran}</td>
 *     <td>1, {@code expect failure errno invalid_op_missing_arg}</td></tr>
 * <tr><td>{@code more_builtins.gie:16}</td>
 *     <td>{@code +o_proj=moll +o_lon_p=0 +o_lat_p=0 +lon_0=180}</td><td>2</td></tr>
 * <tr><td>{@code more_builtins.gie:454}</td><td>{@code +o_proj=helmert +o_lat_p=0}</td>
 *     <td>1, {@code expect failure errno no_inverse_op}</td></tr>
 * </table>
 *
 * <p>The first block exercises {@code PJ_IO_UNITS_WHATEVER} — its child is {@code latlon}, so both
 * directions bypass the {@code a}/{@code x_0}/{@code fr_meter} layer and the expected values are
 * raw <b>radians</b> despite {@code +R=6400000} being present. The third exercises the
 * <em>transverse</em> branch, because {@code +o_lat_p=0} makes {@code |phip| &le; 1e-10}. So
 * between them the four blocks cover both dispatch modes, the {@code WHATEVER} demotion, the
 * recursion block and a missing child.
 */
public class ObliqueTransformationCorpusTest {

    @Test
    public void obTranMatchesBuiltins() {
        TierBCorpus.assertAll("builtins.gie", "ob_tran", 9);
    }

    @Test
    public void obTranMatchesMoreBuiltins() {
        TierBCorpus.assertAll("more_builtins.gie", "ob_tran", 3);
    }

    @Test
    public void nameIsRegistered() {
        Projection p = new Registry().getProjection("ob_tran");
        assertNotNull("+proj=ob_tran", p);
        assertTrue(p instanceof ObliqueTransformationProjection);
    }

    // ------------------------------------------------------------------------------------------
    // ob_tran_target_params -- the o_proj= rewrite
    // ------------------------------------------------------------------------------------------

    /**
     * {@code o_proj=xxx} becomes {@code proj=xxx} by dropping the first <b>two</b> characters, and
     * {@code proj=ob_tran} and a bare {@code inv} are dropped. Everything else survives verbatim,
     * including {@code +o_lat_p}, which the child ignores.
     */
    @Test
    public void childParametersRewritesOProjAndDropsTheWrapper() {
        assertArrayEquals(
                new String[] {"R=6400000", "o_lon_p=20", "o_lat_p=20", "lon_0=180", "proj=latlon"},
                childParameters("+proj=ob_tran +R=6400000 +o_lon_p=20 +o_lat_p=20 +lon_0=180 "
                        + "+o_proj=latlon"));
        // `inv` is dropped; the leading + is optional on every token.
        assertArrayEquals(new String[] {"proj=moll", "R=1"},
                childParameters("proj=ob_tran o_proj=moll inv R=1"));
    }

    /** Only the FIRST {@code o_proj=} token is rewritten; upstream breaks out of the loop. */
    @Test
    public void onlyTheFirstOProjTokenIsRewritten() {
        assertArrayEquals(new String[] {"proj=moll", "o_proj=sinu"},
                childParameters("+proj=ob_tran +o_proj=moll +o_proj=sinu"));
    }

    /**
     * A bare {@code +o_proj} with no value satisfies the "is {@code o_proj} present" test — because
     * {@code pj_param}'s {@code 's'} sigil yields the empty string, not {@code nullptr} — but is
     * <b>not</b> the token the rewrite loop matches, because {@code strncmp("o_proj", "o_proj=", 7)}
     * compares the terminating NUL against {@code '='}.
     *
     * <p>That combination is precisely why {@code builtins.gie:5072}'s
     * {@code +o_proj +o_proj=ob_tran} reaches the recursion check and fails there with
     * {@code invalid_op_missing_arg} rather than with "missing o_proj" or with an illegal-value
     * error.
     */
    @Test
    public void bareOProjIsPresentButIsNotRewritten() {
        assertArrayEquals(new String[] {"o_proj", "proj=moll"},
                childParameters("+proj=ob_tran +o_proj +o_proj=moll"));
    }

    @Test
    public void recursionIsBlockedAfterTheRewrite() {
        try {
            childParameters("+proj=ob_tran +R=6400000 +o_proj +o_proj=ob_tran");
            fail("+o_proj=ob_tran must be rejected");
        } catch (InvalidValueException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("recursion"));
        }
    }

    @Test
    public void missingOProjIsRejected() {
        try {
            childParameters("+proj=ob_tran +R=6400000 +o_lat_p=20");
            fail("a definition with no +o_proj must be rejected");
        } catch (InvalidValueException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("o_proj"));
        }
    }

    /** {@link ObliqueTransformationProjection#initialize()} with no child is upstream's
     * "Missing parameter: o_proj". */
    @Test
    public void bareObTranRefuses() {
        try {
            new ObliqueTransformationProjection().initialize();
            fail("+proj=ob_tran with no child must refuse");
        } catch (InvalidValueException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("o_proj"));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Forward-only-if-the-child-has-one
    // ------------------------------------------------------------------------------------------

    /**
     * {@code +proj=ob_tran +o_proj=guyou} has a forward and <b>no</b> inverse, because
     * {@code guyou} has none — {@code Q->link->inv ? o_inverse : nullptr}.
     *
     * <p>Asking for the inverse must fail rather than return anything. Both halves matter: a
     * silent identity here would hand back projected metres reinterpreted as radians, which is the
     * exact defect class the fail-closed gate exists for.
     */
    @Test
    public void guyouChildGivesAForwardAndNoInverse() {
        Projection p = TierBCorpus.build("+proj=ob_tran +o_proj=guyou +R=6400000 +o_lat_p=45 "
                + "+o_lon_p=0");
        assertFalse("guyou has no inverse, so neither does ob_tran over it", p.hasInverse());
        ProjCoordinate out = new ProjCoordinate();
        p.project(new ProjCoordinate(10, 20), out);
        assertTrue("the forward must work: " + out, Double.isFinite(out.x)
                && Double.isFinite(out.y) && (out.x != 0.0 || out.y != 0.0));
        try {
            p.inverseProject(new ProjCoordinate(out.x, out.y), new ProjCoordinate());
            fail("ob_tran over guyou must have no inverse");
        } catch (Proj4jException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().toLowerCase()
                    .contains("inverse"));
        }
    }

    /** A child that does have an inverse gives the wrapper one. */
    @Test
    public void mollweideChildGivesBothDirections() {
        Projection p = TierBCorpus.build("+proj=ob_tran +o_proj=moll +R=6378137.0 +o_lon_p=0 "
                + "+o_lat_p=0 +lon_0=180");
        assertTrue("moll has an inverse", p.hasInverse());
    }

    // ------------------------------------------------------------------------------------------
    // PJ_IO_UNITS_WHATEVER
    // ------------------------------------------------------------------------------------------

    /**
     * With a {@code latlon} child the wrapper applies no affine at all, so {@code +R=6400000} has
     * no effect on the output and the numbers are radians.
     *
     * <p>Checked by comparing against the same rotation on a completely different radius: if the
     * demotion were missed, the two would differ by the ratio of the radii — a factor of 6.4
     * million, not a rounding.
     */
    @Test
    public void radiansChildIsNotScaledByTheSemiMajorAxis() {
        Projection big = TierBCorpus.build(
                "+proj=ob_tran +R=6400000 +o_proj=latlon +o_lon_p=20 +o_lat_p=20 +lon_0=180");
        Projection small = TierBCorpus.build(
                "+proj=ob_tran +R=1 +o_proj=latlon +o_lon_p=20 +o_lat_p=20 +lon_0=180");
        ProjCoordinate a = new ProjCoordinate();
        ProjCoordinate b = new ProjCoordinate();
        big.project(new ProjCoordinate(2, 1), a);
        small.project(new ProjCoordinate(2, 1), b);
        assertEquals("x must not scale with +R", a.x, b.x, 0.0);
        assertEquals("y must not scale with +R", a.y, b.y, 0.0);
        // And the value really is in radians: builtins.gie:5049's first expect row.
        assertEquals(-2.685687214, a.x, 1e-8);
        assertEquals(1.237430235, a.y, 1e-8);
    }

    /** A projected child keeps the affine, so {@code +R} does scale it. */
    @Test
    public void projectedChildIsScaledByTheSemiMajorAxis() {
        Projection p = TierBCorpus.build("+proj=ob_tran +o_proj=moll +R=6378137.0 +o_lon_p=0 "
                + "+o_lat_p=0 +lon_0=180");
        ProjCoordinate out = new ProjCoordinate();
        p.project(new ProjCoordinate(10, 20), out);
        // more_builtins.gie:29-30, tolerance 1 mm.
        assertEquals(-1384841.18787, out.x, 1e-3);
        assertEquals(7581707.88240, out.y, 1e-3);
    }

    // ------------------------------------------------------------------------------------------
    // The three pole specifications
    // ------------------------------------------------------------------------------------------

    /**
     * {@code |o_lat_p| &le; 1e-10} selects the transverse branch, anything larger the oblique one,
     * and the two are genuinely different formulae — so a point must not project the same way under
     * both.
     */
    @Test
    public void transverseAndObliqueBranchesDiffer() {
        Projection transverse = TierBCorpus.build(
                "+proj=ob_tran +R=1 +o_proj=latlon +o_lon_p=0 +o_lat_p=0");
        Projection oblique = TierBCorpus.build(
                "+proj=ob_tran +R=1 +o_proj=latlon +o_lon_p=0 +o_lat_p=1e-6");
        ProjCoordinate a = new ProjCoordinate();
        ProjCoordinate b = new ProjCoordinate();
        transverse.project(new ProjCoordinate(30, 40), a);
        oblique.project(new ProjCoordinate(30, 40), b);
        assertTrue("the two branches must not be the same formula: " + a + " vs " + b,
                Math.abs(a.x - b.x) > 1e-12 || Math.abs(a.y - b.y) > 1e-12);
    }

    /** {@code +o_alpha} wins over {@code +o_lat_p}: the three specifications are tested in order
     * and by presence, not by value. */
    @Test
    public void oAlphaTakesPrecedenceOverOLatP() {
        Projection alphaForm = TierBCorpus.build("+proj=ob_tran +R=1 +o_proj=latlon +o_alpha=30 "
                + "+o_lon_c=10 +o_lat_c=20 +o_lat_p=80 +o_lon_p=80");
        Projection poleForm = TierBCorpus.build("+proj=ob_tran +R=1 +o_proj=latlon +o_lat_p=80 "
                + "+o_lon_p=80");
        ProjCoordinate a = new ProjCoordinate();
        ProjCoordinate b = new ProjCoordinate();
        alphaForm.project(new ProjCoordinate(5, 5), a);
        poleForm.project(new ProjCoordinate(5, 5), b);
        assertTrue("+o_alpha must win over +o_lat_p: " + a + " vs " + b,
                Math.abs(a.x - b.x) > 1e-9 || Math.abs(a.y - b.y) > 1e-9);
    }

    /** {@code |o_lat_c|} at the pole is rejected ({@code ob_tran.cpp:236-241}). */
    @Test
    public void oLatCAtThePoleIsRejected() {
        for (String latC : new String[] {"90", "-90"}) {
            try {
                TierBCorpus.build("+proj=ob_tran +R=1 +o_proj=latlon +o_alpha=30 +o_lon_c=0 "
                        + "+o_lat_c=" + latC);
                fail("+o_lat_c=" + latC + " must be rejected");
            } catch (InvalidValueException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("lat_c"));
            }
        }
    }

    /**
     * The two-point form's four guards, each triggered on its own.
     *
     * <p>Note which guard a definition with <em>no</em> pole specification at all trips. Absent
     * angular parameters default to 0 through {@code pj_param}'s {@code "r"} sigil, so
     * {@code +proj=ob_tran +o_proj=moll} lands in the two-point branch with
     * {@code lat_1 = lat_2 = 0} — and because upstream tests "the two must differ" <b>before</b>
     * "lat_1 must not be zero" ({@code ob_tran.cpp:262-273}), the message it gets is
     * <i>"lat_1 should be different from lat_2"</i>, not <i>"different from zero"</i>. The order is
     * upstream's and is asserted here so that a reordering shows up as a test change rather than
     * as a differently-worded error in the field.
     */
    @Test
    public void twoPointFormGuards() {
        String base = "+proj=ob_tran +R=1 +o_proj=latlon ";
        assertRejected(base + "+o_lon_1=0 +o_lat_1=90 +o_lon_2=10 +o_lat_2=20", "lat_1");
        assertRejected(base + "+o_lon_1=0 +o_lat_1=20 +o_lon_2=10 +o_lat_2=90", "lat_2");
        assertRejected(base + "+o_lon_1=0 +o_lat_1=20 +o_lon_2=10 +o_lat_2=20",
                "different from lat_2");
        assertRejected(base + "+o_lon_1=0 +o_lat_1=0 +o_lon_2=10 +o_lat_2=20",
                "different from zero");
        // No pole specification at all falls into this branch with lat_1 == lat_2 == 0, and the
        // "must differ" guard is tested first, so that is the message -- not "different from zero".
        assertRejected("+proj=ob_tran +R=1 +o_proj=moll", "lat_1 should be different from lat_2");
    }

    /** The two-point form does work when all four guards are satisfied. */
    @Test
    public void twoPointFormResolvesAPole() {
        Projection p = TierBCorpus.build("+proj=ob_tran +R=1 +o_proj=latlon +o_lon_1=0 "
                + "+o_lat_1=45 +o_lon_2=90 +o_lat_2=10");
        ProjCoordinate out = new ProjCoordinate();
        p.project(new ProjCoordinate(20, 30), out);
        assertTrue("the two-point form must produce a finite rotation: " + out,
                Double.isFinite(out.x) && Double.isFinite(out.y));
    }

    private static void assertRejected(String definition, String expectedFragment) {
        try {
            TierBCorpus.build(definition);
            fail(definition + " must be rejected");
        } catch (InvalidValueException expected) {
            assertTrue(definition + " -> " + expected.getMessage(),
                    expected.getMessage().contains(expectedFragment));
        }
    }

    private static String[] childParameters(String definition) {
        return ObliqueTransformationProjection.childParameters(definition.split("\\s+"));
    }
}

/*
 * Copyright 2026 The Proj4J Contributors.
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
package org.locationtech.proj4j.pipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@code +proj=affine} and the two per-step {@code +omit_*} flags, against
 * {@code 9.8.1:src/transformations/affine.cpp} and {@code src/pipeline.cpp:525-568}.
 */
public class AffineOperatorTest {

    private final PipelineFactory factory = new PipelineFactory();

    private static final double EPS = 1e-12;

    @Test
    public void offsetsApplyToAllFourComponents() {
        Pipeline p = factory.create("+proj=affine +xoff=1 +yoff=2 +zoff=3 +toff=4");
        double[] out = p.forward(new double[] {10, 20, 30, 40});
        assertEquals(11.0, out[0], EPS);
        assertEquals(22.0, out[1], EPS);
        assertEquals(33.0, out[2], EPS);
        assertEquals(44.0, out[3], EPS);
    }

    /**
     * {@code reverse_4d} subtracts the offset and <em>then</em> applies the inverse
     * matrix, which is not the same as an affine with a negated offset once the matrix is
     * not the identity. With {@code s11 = 2} and {@code xoff = 10}, the forward of 3 is
     * 16 and the inverse of 16 must be 3, not 8.
     */
    @Test
    public void theInverseDeOffsetsBeforeItDeScales() {
        Pipeline p = factory.create("+proj=affine +s11=2 +xoff=10");
        double[] fwd = p.forward(new double[] {3, 0, 0, 0});
        assertEquals(16.0, fwd[0], EPS);
        assertEquals(3.0, p.inverse(fwd)[0], EPS);
    }

    /**
     * The diagonal is read with {@code pj_param}'s {@code 't'} sigil first, so an
     * explicit zero is zero while an absent key is one. Getting this wrong turns
     * {@code +s11=0} into the identity, silently.
     */
    @Test
    public void anExplicitZeroOnTheDiagonalIsZeroNotOne() {
        double[] out = factory.create("+proj=affine +s11=0").forward(new double[] {5, 7, 0, 0});
        assertEquals(0.0, out[0], 0.0);
        assertEquals("an absent +s22 is still 1", 7.0, out[1], EPS);
    }

    /**
     * {@code computeReverseParameters}: a singular matrix removes the inverse and leaves
     * the forward direction working. It is not a construction failure, so the pipeline
     * must build and simply report itself one-way.
     */
    @Test
    public void aSingularMatrixRemovesTheInverseWithoutFailingConstruction() {
        Pipeline p = factory.create("+proj=pipeline +step +proj=affine +s11=0");
        assertFalse("det == 0, so pj_has_inverse is false", p.isInvertible());
        assertEquals(0.0, p.forward(new double[] {5, 7, 0, 0})[0], 0.0);
    }

    /** {@code +tscale=0} is the other route to the same verdict. */
    @Test
    public void aZeroTimeScaleAlsoRemovesTheInverse() {
        assertFalse(factory.create("+proj=pipeline +step +proj=affine +tscale=0").isInvertible());
    }

    /** A full off-diagonal matrix round-trips through the cofactor inverse. */
    @Test
    public void aGeneralMatrixRoundTrips() {
        Pipeline p = factory.create("+proj=affine"
                + " +s11=1 +s12=2 +s13=3"
                + " +s21=0 +s22=1 +s23=4"
                + " +s31=5 +s32=6 +s33=0"
                + " +xoff=7 +yoff=8 +zoff=9");
        assertTrue(p.isInvertible());
        double[] in = {1.5, -2.5, 3.25, 0};
        double[] back = p.inverse(p.forward(in.clone()));
        for (int i = 0; i < 3; i++) {
            assertEquals(in[i], back[i], 1e-9);
        }
    }

    /**
     * {@code +omit_inv}: the step runs in the pipeline's forward pass and is skipped in
     * its reverse pass. {@code 4D-API_cs2cs-style.gie:403-413}.
     */
    @Test
    public void omitInvSkipsTheStepInTheReversePassOnly() {
        Pipeline p = factory.create("+proj=pipeline +step +proj=affine +xoff=1 +yoff=1 +omit_inv");
        assertTrue(p.steps().get(0).isOmittedReverse());
        assertEquals(3.0, p.forward(new double[] {2, 49, 0, 0})[0], EPS);
        assertEquals(2.0, p.inverse(new double[] {2, 49, 0, 0})[0], EPS);
    }

    /**
     * {@code +omit_fwd}: the mirror image. Note that "forward" is the <em>pipeline's</em>
     * pass and not the operator's direction, so {@code +inv +omit_fwd} still omits the
     * step from the forward pass. {@code 4D-API_cs2cs-style.gie:422-440}.
     */
    @Test
    public void omitFwdSkipsTheStepInTheForwardPassOnly() {
        Pipeline p = factory.create("+proj=pipeline +step +proj=affine +xoff=1 +yoff=1 +omit_fwd");
        assertEquals(2.0, p.forward(new double[] {2, 49, 0, 0})[0], EPS);
        assertEquals(1.0, p.inverse(new double[] {2, 49, 0, 0})[0], EPS);

        Pipeline inverted = factory.create(
                "+proj=pipeline +step +inv +proj=affine +xoff=1 +yoff=1 +omit_fwd");
        assertEquals(2.0, inverted.forward(new double[] {2, 49, 0, 0})[0], EPS);
        assertEquals(3.0, inverted.inverse(new double[] {2, 49, 0, 0})[0], EPS);
    }

    /**
     * An omitted direction needs no implementation ({@code pipeline.cpp:536-538},
     * {@code :561}), so a singular affine marked {@code +omit_inv} leaves the pipeline
     * invertible.
     */
    @Test
    public void anOmittedDirectionNeedsNoImplementation() {
        Pipeline p = factory.create("+proj=pipeline +step +proj=affine +s11=0 +omit_inv");
        assertTrue("omit_inv relaxes the invertibility requirement", p.isInvertible());
    }

    /** A bare {@code +step +proj=affine} is the legal no-op the corpus uses as filler. */
    @Test
    public void aBareAffineIsTheIdentity() {
        double[] in = {1.25, -2.5, 3, 4};
        double[] out = factory.create("+proj=pipeline +step +proj=affine").forward(in.clone());
        for (int i = 0; i < 4; i++) {
            assertEquals(in[i], out[i], 0.0);
        }
    }
}

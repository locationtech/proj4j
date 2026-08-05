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
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.gie.GieIoUnits;

/**
 * {@code +proj=axisswap} against {@code 9.8.1:src/conversions/axisswap.cpp}.
 */
public class AxisSwapOperatorTest {

    private static AxisSwapOperator of(String definition) {
        return new AxisSwapOperator(ProjParams.parse(definition));
    }

    private static double[] fwd(AxisSwapOperator op, double... coord) {
        double[] c = {coord[0], coord[1], coord.length > 2 ? coord[2] : 0,
                coord.length > 3 ? coord[3] : 0};
        op.forward(c);
        return c;
    }

    private static double[] inv(AxisSwapOperator op, double... coord) {
        double[] c = {coord[0], coord[1], coord.length > 2 ? coord[2] : 0,
                coord.length > 3 ? coord[3] : 0};
        op.inverse(c);
        return c;
    }

    // ------------------------------------------------- the mutual exclusion

    /**
     * {@code axisswap.cpp:161-167} writes the test as
     * {@code !exists(order) == !exists(axis)}, so <em>both</em> and
     * <em>neither</em> are the same error — {@code MUTUALLY_EXCLUSIVE_ARGS}, which
     * is a distinct {@code PROJ_ERR_*} value from the illegal-value one and is what
     * a gie {@code expect failure errno invalid_op_mutually_exclusive_args} row
     * asserts.
     */
    @Test
    public void neitherOrderNorAxisIsMutuallyExclusiveArgs() {
        try {
            of("+proj=axisswap");
            fail("expected a rejection");
        } catch (PipelineDefinitionException e) {
            assertEquals(PipelineErrorCode.MUTUALLY_EXCLUSIVE_ARGS, e.code());
            assertEquals(1028, e.code().projErrno());
        }
    }

    @Test
    public void bothOrderAndAxisIsAlsoMutuallyExclusiveArgs() {
        try {
            of("+proj=axisswap +order=2,1 +axis=wsu");
            fail("expected a rejection");
        } catch (PipelineDefinitionException e) {
            assertEquals(PipelineErrorCode.MUTUALLY_EXCLUSIVE_ARGS, e.code());
        }
    }

    @Test
    public void exactlyOneOfThemIsAccepted() {
        of("+proj=axisswap +order=2,1");
        of("+proj=axisswap +axis=neu");
    }

    // -------------------------------------------------------------- +order

    @Test
    public void orderSwapsTheFirstTwoOrdinates() {
        double[] out = fwd(of("+proj=axisswap +order=2,1"), 1, 2);
        assertEquals(2.0, out[0], 0.0);
        assertEquals(1.0, out[1], 0.0);
    }

    @Test
    public void aLeadingMinusFlipsTheSign() {
        double[] out = fwd(of("+proj=axisswap +order=-1,2"), 3, 4);
        assertEquals(-3.0, out[0], 0.0);
        assertEquals(4.0, out[1], 0.0);
    }

    @Test
    public void orderCanCoverAllFourOrdinates() {
        double[] out = fwd(of("+proj=axisswap +order=4,3,2,1"), 1, 2, 3, 4);
        assertEquals(4.0, out[0], 0.0);
        assertEquals(3.0, out[1], 0.0);
        assertEquals(2.0, out[2], 0.0);
        assertEquals(1.0, out[3], 0.0);
    }

    @Test
    public void duplicateAxesAreRejected() {
        try {
            of("+proj=axisswap +order=1,1");
            fail("expected a rejection");
        } catch (PipelineDefinitionException e) {
            assertEquals(PipelineErrorCode.ILLEGAL_ARG_VALUE, e.code());
        }
    }

    @Test
    public void anIndexOutsideOneToFourIsRejected() {
        try {
            of("+proj=axisswap +order=5,1");
            fail("expected a rejection");
        } catch (PipelineDefinitionException e) {
            assertEquals(PipelineErrorCode.ILLEGAL_ARG_VALUE, e.code());
        }
    }

    @Test
    public void aCharacterOutsideTheOrderAlphabetIsRejected() {
        try {
            of("+proj=axisswap +order=2;1");
            fail("expected a rejection");
        } catch (PipelineDefinitionException e) {
            assertEquals(PipelineErrorCode.ILLEGAL_ARG_VALUE, e.code());
        }
    }

    @Test
    public void aTwoOrdinateOrderTouchingZIsNotRepresentable() {
        // axisswap.cpp:259-277 wires no function pointer for this and then errors.
        try {
            of("+proj=axisswap +order=3,1");
            fail("expected a rejection");
        } catch (PipelineDefinitionException e) {
            assertEquals(PipelineErrorCode.ILLEGAL_ARG_VALUE, e.code());
        }
    }

    // --------------------------------------------------------------- +axis

    /**
     * {@code +axis=wsu} is what {@code +init=epsg:2049} carries, and it is the only
     * reason {@code gigs/5113.gie} expects {@code (-50475.46, +2766147.25)} where an
     * ENU {@code tmerc} would give {@code (+50475.46, -2766147.25)}.
     */
    @Test
    public void axisWsuNegatesEastingAndNorthing() {
        double[] out = fwd(of("+proj=axisswap +axis=wsu"), 50475.46, -2766147.25, 7);
        assertEquals(-50475.46, out[0], 0.0);
        assertEquals(2766147.25, out[1], 0.0);
        assertEquals("up is unchanged", 7.0, out[2], 0.0);
    }

    @Test
    public void axisNeuSwapsAndAxisEnuIsTheIdentity() {
        double[] neu = fwd(of("+proj=axisswap +axis=neu"), 1, 2, 3);
        assertEquals(2.0, neu[0], 0.0);
        assertEquals(1.0, neu[1], 0.0);
        double[] enu = fwd(of("+proj=axisswap +axis=enu"), 1, 2, 3);
        assertEquals(1.0, enu[0], 0.0);
        assertEquals(2.0, enu[1], 0.0);
        assertEquals(3.0, enu[2], 0.0);
    }

    @Test
    public void axisDownNegatesTheThirdOrdinate() {
        assertEquals(-3.0, fwd(of("+proj=axisswap +axis=end"), 1, 2, 3)[2], 0.0);
    }

    @Test
    public void anAxisLetterOutsideEwnsudIsRejected() {
        try {
            of("+proj=axisswap +axis=xyz");
            fail("expected a rejection");
        } catch (PipelineDefinitionException e) {
            assertEquals(PipelineErrorCode.ILLEGAL_ARG_VALUE, e.code());
        }
    }

    @Test
    public void anAxisSpecShorterThanThreeCharactersIsRejected() {
        try {
            of("+proj=axisswap +axis=en");
            fail("expected a rejection");
        } catch (PipelineDefinitionException e) {
            assertEquals(PipelineErrorCode.ILLEGAL_ARG_VALUE, e.code());
        }
    }

    // ------------------------------------------- forward is not inverse-shaped

    /**
     * The forward indexes the <em>input</em> by the permutation and the inverse
     * indexes the <em>output</em> by it: {@code out[i] = in[axis[i]]} versus
     * {@code out[axis[i]] = in[i]}. For a self-inverse permutation the two coincide,
     * which is exactly why swapping them survives the obvious x/y test — so the
     * discriminating case is a three-cycle.
     */
    @Test
    public void forwardAndInverseDifferForANonSelfInversePermutation() {
        AxisSwapOperator op = of("+proj=axisswap +order=2,3,1");
        double[] forward = fwd(op, 1, 2, 3);
        double[] inverse = inv(op, 1, 2, 3);
        assertEquals(2.0, forward[0], 0.0);
        assertEquals(3.0, forward[1], 0.0);
        assertEquals(1.0, forward[2], 0.0);
        assertEquals("out[axis[i]] = in[i]", 3.0, inverse[0], 0.0);
        assertEquals(1.0, inverse[1], 0.0);
        assertEquals(2.0, inverse[2], 0.0);
    }

    @Test
    public void inverseUndoesForward() {
        AxisSwapOperator op = of("+proj=axisswap +order=2,3,1");
        double[] c = {1, 2, 3, 0};
        op.forward(c);
        op.inverse(c);
        assertEquals(1.0, c[0], 0.0);
        assertEquals(2.0, c[1], 0.0);
        assertEquals(3.0, c[2], 0.0);
    }

    // ----------------------------------------------------------------- units

    @Test
    public void bothSidesAreWhateverUnlessAngularunitsIsGiven() {
        AxisSwapOperator plain = of("+proj=axisswap +order=2,1");
        assertEquals(GieIoUnits.WHATEVER, plain.declaredLeft());
        assertEquals(GieIoUnits.WHATEVER, plain.declaredRight());

        // axisswap.cpp:279-284: +angularunits forces radian i/o. Documented nowhere.
        AxisSwapOperator angular = of("+proj=axisswap +order=2,1 +angularunits");
        assertEquals(GieIoUnits.RADIANS, angular.declaredLeft());
        assertEquals(GieIoUnits.RADIANS, angular.declaredRight());
    }
}

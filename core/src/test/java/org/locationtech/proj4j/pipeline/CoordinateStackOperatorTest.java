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
 * {@code +proj=push}, {@code +proj=pop} and {@code +proj=set} against
 * {@code 9.8.1:src/pipeline.cpp:641-727} and {@code src/conversions/set.cpp}.
 *
 * <p>The cases here are the ones whose <em>upstream</em> behaviour is surprising, since
 * those are the ones a plausible-looking implementation gets wrong: a stack that is not
 * shared, a {@code pop} that throws on an empty stack, a {@code push} that acts without
 * a parent pipeline, and a {@code set} whose inverse undoes itself.
 */
public class CoordinateStackOperatorTest {

    private final PipelineFactory factory = new PipelineFactory();

    private static final double EPS = 1e-9;

    /** Degrees to radians without {@code Math.toRadians} — see {@code ProjectionMath.DTR}. */
    private static double rad(double degrees) {
        return degrees * org.locationtech.proj4j.util.ProjectionMath.DTR;
    }

    /**
     * The motivating case, and the one PROJ itself uses: a round trip through two UTM
     * zones moves the longitude from 12&deg; to 18&deg;, and bracketing it with
     * {@code push +v_1} / {@code pop +v_1} puts it back.
     * {@code 4D-API_cs2cs-style.gie:310-318}.
     */
    @Test
    public void pushAndPopRestoreTheSavedComponent() {
        Pipeline bracketed = factory.create("+proj=pipeline"
                + " +step +proj=push +v_1"
                + " +step +proj=utm +zone=32"
                + " +step +proj=utm +zone=33 +inv"
                + " +step +proj=pop +v_1");
        double[] out = bracketed.forward(new double[] {rad(12), rad(56), 0, 2020});
        assertEquals(rad(12), out[0], EPS);
        assertEquals(rad(56), out[1], EPS);
        assertEquals(2020.0, out[3], 0.0);

        // ...and without the bracket the same pipeline really does move it, so the
        // assertion above is not passing for want of anything happening.
        Pipeline bare = factory.create("+proj=pipeline"
                + " +step +proj=utm +zone=32"
                + " +step +proj=utm +zone=33 +inv");
        double[] moved = bare.forward(new double[] {rad(12), rad(56), 0, 2020});
        assertEquals(rad(18), moved[0], 1e-7);
    }

    /**
     * {@code 4D-API_cs2cs-style.gie:341-353}: two pushes and two pops in one pipeline,
     * which only works if the stack is shared <em>and</em> last-in-first-out. A
     * per-operator stack would pass the single-bracket test above and fail this one.
     */
    @Test
    public void twoBracketsNestLastInFirstOut() {
        Pipeline p = factory.create("+proj=pipeline"
                + " +step +proj=push +v_1"
                + " +step +proj=utm +zone=32"
                + " +step +proj=push +v_1"
                + " +step +proj=utm +zone=33 +inv"
                + " +step +proj=utm +zone=34"
                + " +step +proj=pop +v_1"
                + " +step +proj=utm +zone=32 +inv"
                + " +step +proj=pop +v_1");
        double[] out = p.forward(new double[] {rad(12), rad(56), 0, 2020});
        assertEquals(rad(12), out[0], EPS);
        assertEquals(rad(56), out[1], EPS);
    }

    /**
     * {@code pipeline.cpp:667}: popping an empty stack leaves the component alone and is
     * <b>not</b> an error. {@code 4D-API_cs2cs-style.gie:356-364} asserts the moved
     * value, i.e. 18&deg;, survives.
     */
    @Test
    public void poppingAnEmptyStackIsNotAnError() {
        Pipeline p = factory.create("+proj=pipeline"
                + " +step +proj=utm +zone=32"
                + " +step +proj=utm +zone=33 +inv"
                + " +step +proj=pop +v_1");
        double[] out = p.forward(new double[] {rad(12), rad(56), 0, 2020});
        assertEquals(rad(18), out[0], 1e-7);
    }

    /**
     * {@code pipeline.cpp:641-643}, {@code if (P->parent == nullptr) return}: a
     * {@code push} or {@code pop} that is not inside a pipeline is the identity.
     * {@code 4D-API_cs2cs-style.gie:388-396}.
     */
    @Test
    public void aBarePushOrPopIsTheIdentity() {
        double[] in = {rad(12), rad(56), 7.5, 2020};
        double[] pushed = factory.create("+proj=push +v_3").forward(in.clone());
        double[] popped = factory.create("+proj=pop +v_3").forward(in.clone());
        for (int i = 0; i < 4; i++) {
            assertEquals("push component " + i, in[i], pushed[i], 0.0);
            assertEquals("pop component " + i, in[i], popped[i], 0.0);
        }
    }

    /** {@code push}'s inverse is {@code pop} and vice versa, which the reverse pass needs. */
    @Test
    public void theReversePassPairsThemTheOtherWayRound() {
        Pipeline p = factory.create("+proj=pipeline"
                + " +step +proj=push +v_1"
                + " +step +proj=utm +zone=32"
                + " +step +proj=utm +zone=33 +inv"
                + " +step +proj=pop +v_1");
        assertTrue(p.isInvertible());
        double[] there = p.forward(new double[] {rad(12), rad(56), 0, 2020});
        double[] back = p.inverse(there);
        assertEquals(rad(12), back[0], EPS);
        assertEquals(rad(56), back[1], EPS);
    }

    /**
     * {@code set.cpp}: {@code P->fwd4d} and {@code P->inv4d} are the <em>same</em>
     * function, so {@code direction inverse} sets the components rather than restoring
     * them. {@code 4D-API_cs2cs-style.gie:545-558} pins exactly that, and an
     * implementation that treated the inverse as an undo would fail it while looking
     * more principled.
     */
    @Test
    public void setIsTheSameFunctionInBothDirections() {
        Pipeline p = factory.create("+proj=set +v_1=10 +v_2=20 +v_3=30 +v_4=40");
        double[] fwd = p.forward(new double[] {1, 2, 3, 4});
        double[] inv = p.inverse(new double[] {1, 2, 3, 4});
        for (int i = 0; i < 4; i++) {
            assertEquals(10.0 * (i + 1), fwd[i], 0.0);
            assertEquals(10.0 * (i + 1), inv[i], 0.0);
        }
    }

    /** {@code +proj=set} with nothing selected touches nothing. */
    @Test
    public void aBareSetIsTheIdentity() {
        double[] out = factory.create("+proj=set").forward(new double[] {1, 2, 3, 4});
        assertEquals(1.0, out[0], 0.0);
        assertEquals(4.0, out[3], 0.0);
    }

    /**
     * Selection is by presence and the value is read separately, so a bare {@code +v_3}
     * sets the component to zero rather than leaving it — {@code pj_param_exists} then
     * {@code pj_param("dv_3").f}.
     */
    @Test
    public void aValuelessSetTokenMeansZeroNotUnchanged() {
        double[] out = factory.create("+proj=set +v_3").forward(new double[] {1, 2, 3, 4});
        assertEquals("a bare +v_3 selects the component; its value defaults to 0",
                0.0, out[2], 0.0);
        assertEquals("and nothing else moves", 4.0, out[3], 0.0);
    }

    /** Both sides are {@code WHATEVER}, so a bare {@code push} imposes no unit domain. */
    @Test
    public void bothSidesAreWhatever() {
        Pipeline p = factory.create("+proj=push +v_1");
        assertEquals(org.locationtech.proj4j.gie.GieIoUnits.WHATEVER, p.left());
        assertEquals(org.locationtech.proj4j.gie.GieIoUnits.WHATEVER, p.right());
        assertFalse(p.steps().get(0).isOmittedForward());
    }
}

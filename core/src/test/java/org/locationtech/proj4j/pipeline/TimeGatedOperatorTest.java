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

import org.junit.Test;
import org.locationtech.proj4j.gie.GieIoUnits;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The {@code +t_epoch}/{@code +t_final} bracket, isolated from any grid.
 *
 * <p>Every expectation here is read off {@code 9.8.1:src/transformations/hgridshift.cpp}'s
 * {@code pj_hgridshift_forward_4d}, whose whole content is
 *
 * <pre>
 * if (Q-&gt;t_final == 0 || Q-&gt;t_epoch == 0) { always; return; }
 * if (coo.lpzt.t &lt; Q-&gt;t_epoch &amp;&amp; Q-&gt;t_final &gt; Q-&gt;t_epoch) { apply; }
 * </pre>
 *
 * <p>and cross-checked against the eight {@code accept}/{@code expect} pairs of
 * {@code deformation.gie}'s four gated blocks. The delegate is a counting stub rather
 * than a real grid shift so that "did the step run" is observed directly instead of
 * inferred from a coordinate.
 */
public class TimeGatedOperatorTest {

    /** Adds 1 to x on forward, subtracts 1 on inverse, and counts both. */
    private static final class Counter implements PipelineOperator {
        int forwards;
        int inverses;

        @Override
        public GieIoUnits declaredLeft() {
            return GieIoUnits.RADIANS;
        }

        @Override
        public GieIoUnits declaredRight() {
            return GieIoUnits.RADIANS;
        }

        @Override
        public void overrideUnits(GieIoUnits left, GieIoUnits right) {
        }

        @Override
        public void forward(double[] coord) {
            forwards++;
            coord[0] += 1;
        }

        @Override
        public void inverse(double[] coord) {
            inverses++;
            coord[0] -= 1;
        }

        @Override
        public boolean hasInverse() {
            return true;
        }

        @Override
        public String description() {
            return "counter";
        }
    }

    private static double[] at(double t) {
        return new double[] {0, 0, 0, t};
    }

    /** Neither parameter present: no wrapper at all, so no per-coordinate cost. */
    @Test
    public void noBracketReturnsTheDelegateUnwrapped() {
        Counter c = new Counter();
        assertSame(c, TimeGatedOperator.wrap(c, ProjParams.parse("proj=hgridshift grids=x")));
    }

    /**
     * Zero is the "unset" sentinel for <em>either</em> parameter, not a year. Reading it
     * as a year would make the gate never fire instead of never existing.
     */
    @Test
    public void oneParameterAloneDisablesTheGate() {
        Counter c = new Counter();
        assertSame(c, TimeGatedOperator.wrap(c, ProjParams.parse("t_epoch=2010")));
        Counter d = new Counter();
        assertSame(d, TimeGatedOperator.wrap(d, ProjParams.parse("t_final=2018")));
        Counter e = new Counter();
        assertSame(e, TimeGatedOperator.wrap(e, ProjParams.parse("t_epoch=0 t_final=2018")));
    }

    /**
     * {@code deformation.gie}'s {@code +t_epoch=2010.0 +t_final=2018.0} block, all four
     * rows: 2000 is transformed, 2011 and 2019 are not, and an unwritten fourth ordinate
     * arrives as {@code 0} — which is before 2010, so it <em>is</em> transformed.
     */
    @Test
    public void bracketIsOneSidedAgainstTEpoch() {
        Counter c = new Counter();
        PipelineOperator gated =
                TimeGatedOperator.wrap(c, ProjParams.parse("t_epoch=2010.0 t_final=2018.0"));

        double[] before = at(2000.0);
        gated.forward(before);
        assertEquals("t=2000 is before t_epoch, so the shift applies", 1.0, before[0], 0.0);

        double[] inside = at(2011.0);
        gated.forward(inside);
        assertEquals("t=2011 is after t_epoch, so nothing happens", 0.0, inside[0], 0.0);

        double[] after = at(2019.0);
        gated.forward(after);
        assertEquals("t_final is never compared against t, so 2019 is also untouched",
                0.0, after[0], 0.0);

        double[] unspecified = at(0.0);
        gated.forward(unspecified);
        assertEquals("gie zero-fills an unwritten t, and 0 < 2010", 1.0, unspecified[0], 0.0);

        assertEquals(2, c.forwards);
        assertEquals(0, c.inverses);
    }

    /** The inverse honours the same bracket, so a gated step still round-trips. */
    @Test
    public void inverseUsesTheSameBracket() {
        Counter c = new Counter();
        PipelineOperator gated =
                TimeGatedOperator.wrap(c, ProjParams.parse("t_epoch=2010.0 t_final=2018.0"));

        double[] coord = at(2000.0);
        gated.forward(coord);
        gated.inverse(coord);
        assertEquals(0.0, coord[0], 0.0);
        assertEquals(1, c.forwards);
        assertEquals(1, c.inverses);

        double[] outside = at(2011.0);
        gated.inverse(outside);
        assertEquals(0.0, outside[0], 0.0);
        assertEquals("the inverse did not run for an out-of-bracket epoch", 1, c.inverses);
    }

    /** {@code t_final &gt; t_epoch} is a sanity check, and it closes the gate when violated. */
    @Test
    public void invertedBracketNeverApplies() {
        Counter c = new Counter();
        PipelineOperator gated =
                TimeGatedOperator.wrap(c, ProjParams.parse("t_epoch=2018.0 t_final=2010.0"));
        double[] coord = at(2000.0);
        gated.forward(coord);
        assertEquals(0.0, coord[0], 0.0);
        assertEquals(0, c.forwards);
    }

    /**
     * {@code +t_final=now} is {@code 1900 + tm_year + tm_yday/365.0} from
     * {@code localtime}, read <em>only after</em> a numeric parse yields 0. The corpus
     * asserts only that the bracket is open at 2000 and closed at 2011 and 3011, which is
     * what is checked here — pinning the literal value would pin the clock.
     */
    @Test
    public void nowIsResolvedToTheCurrentDecimalYear() {
        Counter c = new Counter();
        PipelineOperator gated =
                TimeGatedOperator.wrap(c, ProjParams.parse("t_epoch=2010.0 t_final=now"));
        assertTrue("wrap must not have short-circuited: 'now' is not 0",
                gated instanceof TimeGatedOperator);

        double[] before = at(2000.0);
        gated.forward(before);
        assertEquals(1.0, before[0], 0.0);

        double[] after = at(2011.0);
        gated.forward(after);
        assertEquals(0.0, after[0], 0.0);

        double[] farFuture = at(3011.0);
        gated.forward(farFuture);
        assertEquals(0.0, farFuture[0], 0.0);
    }

    /** The wrapper is invisible to the pipeline's unit-continuity check. */
    @Test
    public void unitSidesAndInvertibilityAreDelegated() {
        Counter c = new Counter();
        PipelineOperator gated =
                TimeGatedOperator.wrap(c, ProjParams.parse("t_epoch=2010.0 t_final=2018.0"));
        assertEquals(GieIoUnits.RADIANS, gated.declaredLeft());
        assertEquals(GieIoUnits.RADIANS, gated.declaredRight());
        assertTrue(gated.hasInverse());
    }
}

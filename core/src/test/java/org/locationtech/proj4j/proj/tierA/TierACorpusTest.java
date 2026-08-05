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

package org.locationtech.proj4j.proj.tierA;

import org.junit.Test;

/**
 * Every Tier A projection ported in this batch, checked against the {@code accept}/
 * {@code expect}/{@code roundtrip} rows of PROJ's own {@code gie} corpus, read from the
 * vendored files rather than transcribed.
 *
 * <p>One test per {@code +proj=} name, so a failure names the operator. The row count is
 * asserted alongside the values: a parser that silently dropped rows, or a corpus that
 * moved, would otherwise let this pass while checking nothing.
 *
 * <p>These complement rather than duplicate the {@code conformance} module's harness. That
 * one is the authority for the headline number but reports <b>per file</b>, and
 * {@code builtins.gie} holds all of these operators at once — so it cannot say which of
 * them a delta came from. These can.
 *
 * @see GieCheck for the deviation metric, which is Euclidean for a forward row and
 *      <em>geodesic</em> for an inverse one
 */
public class TierACorpusTest {

    // ---------------------------------------------------------------- group (a)

    @Test
    public void tobmerc() {
        // 8 forward + 8 inverse in the +ellps=sphere block, 1 stability row at +R=1.
        // The 2 pole rows are `expect failure` and are asserted in TierADomainTest.
        GieCheck.assertAllRows("builtins.gie", "tobmerc", 17);
    }

    @Test
    public void times() {
        GieCheck.assertAllRows("builtins.gie", "times", 10);
    }

    @Test
    public void webmerc() {
        // Only the standalone `operation proj=webmerc +ellps=WGS84` block; the other two
        // webmerc operations in this file are +proj=pipeline steps, which need the pipeline
        // engine and are not reachable from a bare Projection.
        GieCheck.assertAllRows("4D-API_cs2cs-style.gie", "webmerc", 2);
    }

    // ---------------------------------------------------------------- group (b)

    @Test
    public void natearth() {
        GieCheck.assertAllRows("builtins.gie", "natearth", 8);
    }

    @Test
    public void natearth2() {
        GieCheck.assertAllRows("builtins.gie", "natearth2", 8);
    }

    @Test
    public void patterson() {
        GieCheck.assertAllRows("builtins.gie", "patterson", 8);
    }

    @Test
    public void comill() {
        GieCheck.assertAllRows("builtins.gie", "comill", 8);
    }

    // ---------------------------------------------------------------- group (c)

    @Test
    public void eck3() {
        GieCheck.assertAllRows("builtins.gie", "eck3", 8);
    }

    @Test
    public void kav7() {
        GieCheck.assertAllRows("builtins.gie", "kav7", 8);
    }

    @Test
    public void wag6() {
        GieCheck.assertAllRows("builtins.gie", "wag6", 8);
    }

    @Test
    public void putp1() {
        GieCheck.assertAllRows("builtins.gie", "putp1", 8);
    }

    // ------------------------------------------- already implemented, only unregistered

    /**
     * {@code mbt_s} needed <b>no new code</b>: {@code McBrydeThomasFlatPolarSine1Projection}
     * already existed, complete and correct, as
     * {@code SineTangentSeriesProjection(1.48875, 1.36509, tan_mode=false)} — exactly
     * {@code sts.cpp:94-101}'s {@code setup(P, 1.48875, 1.36509, 0)}. Only
     * {@code Registry.java}'s commented-out line stood between it and 16 assertions.
     */
    @Test
    public void mbt_s() {
        GieCheck.assertAllRows("builtins.gie", "mbt_s", 16);
    }

    /**
     * {@code tissot}: {@code TissotProjection} already existed as
     * {@code SimpleConicProjection(TISSOT)}, but §9's "un-comment and verify" understated the
     * verify step — the base class ignored {@code +lat_1}/{@code +lat_2} entirely and the
     * inverse had two transcription defects. See {@link SconicsFamilyCorpusTest}.
     */
    @Test
    public void tissot() {
        GieCheck.assertAllRows("builtins.gie", "tissot", 16);
    }

    // ---------------------------------------------------------------- group (d)

    @Test
    public void eqearth() {
        // Two operations in more_builtins.gie: +ellps=WGS84 (ellipsoidal, exercising the
        // authalic latitude) and +R=6378137 (spherical, where rqda is exactly 1).
        GieCheck.assertAllRows("more_builtins.gie", "eqearth", 31);
    }

    // ---------------------------------------------------------------- group (e)

    @Test
    public void putp3() {
        GieCheck.assertAllRows("builtins.gie", "putp3", 8);
    }

    @Test
    public void putp3p() {
        GieCheck.assertAllRows("builtins.gie", "putp3p", 8);
    }

    @Test
    public void putp6() {
        GieCheck.assertAllRows("builtins.gie", "putp6", 8);
    }

    @Test
    public void putp6p() {
        GieCheck.assertAllRows("builtins.gie", "putp6p", 8);
    }

    // ---------------------------------------------------------------- group (h)

    @Test
    public void vandg2() {
        GieCheck.assertAllRows("builtins.gie", "vandg2", 4);
    }

    @Test
    public void vandg3() {
        GieCheck.assertAllRows("builtins.gie", "vandg3", 4);
    }

    @Test
    public void vandg4() {
        GieCheck.assertAllRows("builtins.gie", "vandg4", 4);
    }

    @Test
    public void bertin1953() {
        GieCheck.assertAllRows("more_builtins.gie", "bertin1953", 9);
    }

    @Test
    public void wink1() {
        GieCheck.assertAllRows("builtins.gie", "wink1", 8);
    }

    /**
     * {@code wink2} is the one member of this batch whose inverse is
     * {@code pj_generic_inverse_2d} rather than a formula, so its rows exercise
     * {@code GenericInverse2D} — which arrived with {@code adams_ws2} in an earlier batch and
     * made this projection nearly free.
     */
    @Test
    public void wink2() {
        // 8 expect + 7 roundtrip. wink2 is the only Tier A operator with roundtrip rows in
        // builtins.gie, and they are the batch's only direct test of GenericInverse2D outside
        // the adams family.
        GieCheck.assertAllRows("builtins.gie", "wink2", 15);
    }

    /**
     * {@code nell_h} needed no new code: {@code NellHProjection} already existed, and its
     * inverse had already been repaired (the 2006 conversion had frozen the Newton iterate,
     * making the inverse return a pole almost unconditionally). Registration only.
     */
    @Test
    public void nell_h() {
        GieCheck.assertAllRows("builtins.gie", "nell_h", 8);
    }

    @Test
    public void mbtfps() {
        GieCheck.assertAllRows("builtins.gie", "mbtfps", 8);
    }

    /**
     * {@code gins8} needed no new code either: {@code Ginsburg8Projection} already existed and
     * matched {@code gins8.cpp} line for line. Registration only.
     */
    @Test
    public void gins8() {
        GieCheck.assertAllRows("builtins.gie", "gins8", 4);
    }

    /**
     * {@code ortel} is in {@code bacon.cpp} with {@code apian} and {@code bacon}, not in a
     * file of its own — so it inherits the whole globular forward rather than needing one.
     */
    @Test
    public void ortel() {
        GieCheck.assertAllRows("builtins.gie", "ortel", 4);
    }

    /**
     * {@code apian} was one of the three {@code +proj=} names bound to the <b>abstract</b>
     * {@link org.locationtech.proj4j.proj.Projection} class. It is now a real implementation.
     */
    @Test
    public void apian() {
        GieCheck.assertAllRows("builtins.gie", "apian", 4);
    }

    /** {@code bacon}, the second of the three abstract-class bindings. */
    @Test
    public void bacon() {
        GieCheck.assertAllRows("builtins.gie", "bacon", 4);
    }
}

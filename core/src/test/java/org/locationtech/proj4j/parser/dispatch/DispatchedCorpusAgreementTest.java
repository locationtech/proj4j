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

package org.locationtech.proj4j.parser.dispatch;

import org.junit.Test;

/**
 * The operators that {@code Proj4Parser}'s new parameter dispatch makes reachable, run against
 * PROJ 9.8.1's own {@code gie} corpus.
 *
 * <p>Each of these had a complete, independently verified kernel and a working setter, and was
 * unreachable from a proj-string for one reason only: {@code Proj4Parser} did not hand it the
 * parameter. {@code ParseMode.PROJ_COMPATIBLE} retains-and-ignores unknown keys, which is
 * PROJ-faithful, so the symptom was not an error — it was a fully-specified definition
 * <em>silently losing</em> its parameters and then projecting from the defaults.
 *
 * <p>Row counts are asserted alongside the values so that a corpus that moves, or a reader that
 * silently matches nothing, fails instead of passing vacuously.
 *
 * @see ParameterDispatchTest for the "the key actually changed the answer" side of this
 */
public class DispatchedCorpusAgreementTest {

    /**
     * {@code +proj=som +inc_angle= +ps_rev= +asc_lon=} — 32 rows across four blocks.
     *
     * <p>The four blocks are two pairs differing only in how the two angles are written:
     * {@code +inc_angle=1.7157253262878522r +asc_lon=2.2298420007209447r} against
     * {@code +inc_angle=98.30382 +asc_lon=127.7605356226}, with identical expected values. So
     * this single assertion also proves the {@code r}/{@code R} radian suffix and decimal
     * degrees arrive as the same angle, which is why both go through the DMS-capable
     * {@code parseAngle} and not {@code Double.parseDouble}.
     */
    @Test
    public void somCorpus() {
        DispatchCheck.assertCorpusAgrees("builtins.gie", "som", 32);
    }

    /** {@code +proj=misrsom +path=} — 16 rows across two blocks, one ellipsoidal, one spherical. */
    @Test
    public void misrsomCorpus() {
        DispatchCheck.assertCorpusAgrees("builtins.gie", "misrsom", 16);
    }

    /**
     * {@code +proj=gn_sinu +m= +n=} — 8 rows. Both keys are required upstream and both are
     * undocumented ({@code gn_sinu.cpp:180-198}), so this block was unreachable without them:
     * {@code +m} was not a {@code Proj4Keyword} at all and {@code +n} was dispatched only to
     * {@code urmfps}.
     */
    @Test
    public void generalSinusoidalCorpus() {
        DispatchCheck.assertCorpusAgrees("builtins.gie", "gn_sinu", 8);
    }

    /**
     * {@code +proj=urm5 +n=} — 4 rows. The second {@code urm5} block is
     * {@code +n=1 +alpha=90}, an {@code expect failure} row which
     * {@link ParameterDispatchTest#urm5RejectsNSinAlphaEqualToOne()} covers instead, since a
     * failure row asserts an error rather than a coordinate.
     */
    @Test
    public void urmaev5Corpus() {
        DispatchCheck.assertCorpusAgrees("builtins.gie", "urm5", 4);
    }

    /**
     * {@code +proj=col_urban +h_0=} — 1 {@code expect} plus 1 {@code roundtrip}. Upstream's
     * own comment records this as the IOGP 373-7-2 Guidance Note 7 part 2 test point, and
     * {@code +h_0=2550} is the operator's only mandatory parameter beyond the ellipsoid.
     */
    @Test
    public void colombiaUrbanCorpus() {
        DispatchCheck.assertCorpusAgrees("builtins.gie", "col_urban", 2);
    }

    /**
     * {@code +proj=ob_tran +o_proj= +o_lon_p= +o_lat_p=} — 8 rows. The second corpus block,
     * {@code +o_proj +o_proj=ob_tran}, is an {@code expect failure} row and is asserted in
     * {@link ParameterDispatchTest#obTranRefusesToRotateItself()} instead.
     *
     * <p>The child here is {@code latlon}, which makes the wrapper's output units
     * {@code PJ_IO_UNITS_WHATEVER} ({@code ob_tran.cpp:296-298}) and its output affine the
     * identity, so the corpus's forward expectations are <b>raw radians</b> rather than metres —
     * {@code -2.685687214} for {@code accept 2 1}. That is exactly what
     * {@code ObliqueTransformationProjection.initialize()} reproduces by setting {@code a},
     * {@code fromMetres} and the false origin to the identity, and it is why the forward rows
     * agree to 1e-9 without any unit conversion.
     */
    @Test
    public void obliqueTransformationCorpus() {
        DispatchCheck.assertCorpusAgrees("builtins.gie", "ob_tran", 8);
    }

    /**
     * {@code more_builtins.gie}'s {@code +proj=ob_tran +o_proj=moll} block — 2 rows, one in each
     * direction, and the reason this file's count moved too. The child is {@code moll}, which is
     * <em>not</em> geographic, so unlike the {@code builtins.gie} block the full metre-valued
     * affine applies.
     */
    @Test
    public void obliqueTransformationMoreBuiltinsCorpus() {
        DispatchCheck.assertCorpusAgrees("more_builtins.gie", "ob_tran", 2);
    }
}

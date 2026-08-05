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

import org.locationtech.proj4j.gie.GieIoUnits;

/**
 * {@code +proj=set} — overwrite selected coordinate components with constants, ported
 * from {@code 9.8.1:src/conversions/set.cpp}.
 *
 * <h2>The one thing that is not symmetric about it</h2>
 *
 * <p>Upstream assigns <em>the same function</em> to both directions:
 * <pre>
 * P-&gt;fwd4d = set_fwd_inv;
 * P-&gt;inv4d = set_fwd_inv;
 * </pre>
 * so {@code +proj=set +v_1=10} sets {@code v_1} to 10 going <b>either</b> way. It is
 * therefore not an invertible operation in the mathematical sense at all, and
 * {@code 4D-API_cs2cs-style.gie:551-558} pins exactly that: the same definition, run
 * {@code direction inverse}, is expected to produce {@code 10 20 30 40} again rather
 * than restoring {@code 1 2 3 4}. Implementing {@code inverse} as an undo would fail
 * that row while looking more correct.
 *
 * <p>Selection is by presence and the value is read separately
 * ({@code pj_param_exists} then {@code pj_param("dv_1").f}), so a bare {@code +v_1}
 * with no value sets the component to <b>0</b> rather than leaving it alone.
 *
 * <p>Both sides are {@link GieIoUnits#WHATEVER}: the operator does not care what the
 * numbers mean.
 *
 * <p>Immutable apart from the overridable unit sides; safe to share.
 *
 * @since 1.5
 */
final class SetOperator implements PipelineOperator {

    private static final String[] COMPONENT_KEYS = {"v_1", "v_2", "v_3", "v_4"};

    private final boolean[] selected = new boolean[4];
    private final double[] value = new double[4];

    private GieIoUnits left = GieIoUnits.WHATEVER;
    private GieIoUnits right = GieIoUnits.WHATEVER;

    SetOperator(final ProjParams params) {
        for (int i = 0; i < 4; i++) {
            if (params.has(COMPONENT_KEYS[i])) {
                selected[i] = true;
                value[i] = params.doubleValue(COMPONENT_KEYS[i], 0.0);
            }
        }
    }

    /** {@code set_fwd_inv}, which is both directions. */
    private void apply(final double[] coord) {
        for (int i = 0; i < 4; i++) {
            if (selected[i]) {
                coord[i] = value[i];
            }
        }
    }

    @Override
    public void forward(final double[] coord) {
        apply(coord);
    }

    @Override
    public void inverse(final double[] coord) {
        apply(coord);
    }

    @Override
    public GieIoUnits declaredLeft() {
        return left;
    }

    @Override
    public GieIoUnits declaredRight() {
        return right;
    }

    @Override
    public void overrideUnits(final GieIoUnits newLeft, final GieIoUnits newRight) {
        this.left = newLeft;
        this.right = newRight;
    }

    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public String description() {
        final StringBuilder sb = new StringBuilder("set");
        for (int i = 0; i < 4; i++) {
            if (selected[i]) {
                sb.append(" +").append(COMPONENT_KEYS[i]).append('=').append(value[i]);
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "SetOperator[" + description() + ", left=" + left + ", right=" + right + "]";
    }
}

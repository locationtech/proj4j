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
 * {@code +proj=push} and {@code +proj=pop} — the pipeline coordinate stack, ported
 * from {@code 9.8.1:src/pipeline.cpp:641-727}. One class serves both, because
 * upstream is one implementation with the two function pointers exchanged:
 * <pre>
 * PJ *OPERATION(push, 0) { P-&gt;fwd4d = push; P-&gt;inv4d = pop;  return setup_pushpop(P); }
 * PJ *OPERATION(pop,  0) { P-&gt;fwd4d = pop;  P-&gt;inv4d = push; return setup_pushpop(P); }
 * </pre>
 *
 * <h2>This is not an exotic operator; it is the mechanism behind a real defect</h2>
 *
 * <p>PROJ itself writes {@code +proj=push +v_3} … {@code +proj=pop +v_3} around the
 * geocentric leg of a 2D datum shift, so that a transformation with no input height
 * cannot invent one. Verified with {@code cct}: a {@code z} of 100 stays 100 with the
 * bracket and becomes 50.15 without it. Proj4J has the same defect on its legacy path
 * — see {@code vertical.InventedHeightTest} — and fixes it another way, in
 * {@code BasicCoordinateTransform}. The two are the same idea.
 *
 * <h2>Three details, all of them upstream's</h2>
 *
 * <ol>
 * <li><b>No parent pipeline means no-op.</b> {@code if (P->parent == nullptr) return}.
 *     A bare {@code operation +proj=push +v_3} is the identity, and
 *     {@code 4D-API_cs2cs-style.gie:388-396} asserts it. Modelled by a {@code null}
 *     {@link CoordinateStack}.</li>
 * <li><b>Popping an empty stack leaves the component alone</b> and is not an error
 *     ({@code :667}, {@code if (pushpop->v1 && !stack[0].empty())}).</li>
 * <li><b>Both sides are {@link GieIoUnits#WHATEVER}</b> ({@code :709-710}), so the
 *     step adopts a neighbour's units during assembly and the gie comparator's metric
 *     is decided by the steps around it rather than by this one.</li>
 * </ol>
 *
 * <p>Selection is by presence, not value: {@code pj_param_exists(P->params, "v_1")},
 * so {@code +v_1} and {@code +v_1=anything} both select component one, and a
 * {@code push} naming no component at all does nothing.
 *
 * <p>Not thread-safe when it holds a stack — see {@link CoordinateStack}.
 *
 * @since 1.5
 */
final class PushPopOperator implements PipelineOperator {

    /** {@code +v_1}..{@code +v_4}, in component order. */
    private static final String[] COMPONENT_KEYS = {"v_1", "v_2", "v_3", "v_4"};

    private final boolean isPush;
    private final boolean[] selected;
    /** The enclosing pipeline's stack, or {@code null} for {@code P->parent == nullptr}. */
    private final CoordinateStack stack;

    private GieIoUnits left = GieIoUnits.WHATEVER;
    private GieIoUnits right = GieIoUnits.WHATEVER;

    /**
     * @param isPush {@code true} for {@code +proj=push}, {@code false} for
     *               {@code +proj=pop}
     * @param params the step's parameter list
     * @param stack  the enclosing pipeline's stack, or {@code null} when there is no
     *               enclosing pipeline, in which case every direction is the identity
     */
    PushPopOperator(final boolean isPush, final ProjParams params, final CoordinateStack stack) {
        this.isPush = isPush;
        this.stack = stack;
        this.selected = new boolean[4];
        for (int i = 0; i < 4; i++) {
            selected[i] = params.has(COMPONENT_KEYS[i]);
        }
    }

    /** {@code static void push(PJ_COORD &point, PJ *P)}. */
    private void doPush(final double[] coord) {
        if (stack == null) {
            return;
        }
        for (int i = 0; i < 4; i++) {
            if (selected[i]) {
                stack.push(i, coord[i]);
            }
        }
    }

    /** {@code static void pop(PJ_COORD &point, PJ *P)}. */
    private void doPop(final double[] coord) {
        if (stack == null) {
            return;
        }
        for (int i = 0; i < 4; i++) {
            if (selected[i] && !stack.isEmpty(i)) {
                coord[i] = stack.pop(i);
            }
        }
    }

    @Override
    public void forward(final double[] coord) {
        if (isPush) {
            doPush(coord);
        } else {
            doPop(coord);
        }
    }

    @Override
    public void inverse(final double[] coord) {
        if (isPush) {
            doPop(coord);
        } else {
            doPush(coord);
        }
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
        final StringBuilder sb = new StringBuilder(isPush ? "push" : "pop");
        for (int i = 0; i < 4; i++) {
            if (selected[i]) {
                sb.append(" +").append(COMPONENT_KEYS[i]);
            }
        }
        if (stack == null) {
            sb.append(" (no enclosing pipeline: identity)");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "PushPopOperator[" + description() + ", left=" + left + ", right=" + right + "]";
    }
}

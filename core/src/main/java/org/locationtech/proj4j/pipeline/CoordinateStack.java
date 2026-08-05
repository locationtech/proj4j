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

/**
 * {@code Pipeline::stack} — the four independent per-component stacks
 * {@code +proj=push} and {@code +proj=pop} share
 * ({@code 9.8.1:src/pipeline.cpp:139}, {@code std::stack<double> stack[4]}).
 *
 * <h2>Why the stack lives on the pipeline and not on the operator</h2>
 *
 * <p>Upstream's {@code push} reaches it through {@code P->parent->opaque}, so the
 * stack belongs to the enclosing pipeline and every {@code push}/{@code pop} step in
 * it shares one. That sharing <em>is</em> the operator's whole purpose: the value a
 * {@code push} saves is popped by a step further down the same pipeline, and
 * {@code 4D-API_cs2cs-style.gie:341-353} nests two pushes and two pops to prove the
 * ordering is last-in-first-out rather than positional.
 *
 * <p>The corollary is upstream's other half, and it is the one that looks like a bug
 * until you read it: <b>a {@code push} or {@code pop} with no parent pipeline is a
 * no-op</b> ({@code pipeline.cpp:641-643}, {@code if (P->parent == nullptr) return}).
 * A bare {@code operation +proj=push +v_3} is therefore the identity, which
 * {@code 4D-API_cs2cs-style.gie:388-396} asserts explicitly. That is modelled here by
 * a {@code null} stack rather than by an empty one, so the two cases cannot be
 * confused: {@code PipelineFactory} hands a stack to the steps of a real
 * {@code +proj=pipeline} and {@code null} to a one-step wrapper.
 *
 * <h2>Popping an empty stack</h2>
 *
 * <p>Leaves the component untouched — {@code if (pushpop->v1 && !stack[0].empty())}.
 * The corpus tests this too ({@code :356-364}: a {@code pop} with no matching
 * {@code push} must let the round trip through two UTM zones stand, giving 18&deg;
 * rather than 12&deg;). It is not an error, so it must not throw.
 *
 * <p><b>Not thread-safe</b>, and deliberately so: {@link Pipeline} already documents
 * that its steps wrap mutable state and that one instance belongs to one thread.
 * Upstream has exactly the same property for exactly the same reason.
 *
 * @since 1.5
 */
final class CoordinateStack {

    /** One growable stack per coordinate component. */
    private final double[][] values = new double[4][];

    /** How much of each {@link #values} row is in use. */
    private final int[] depth = new int[4];

    CoordinateStack() {
        for (int i = 0; i < 4; i++) {
            values[i] = new double[8];
        }
    }

    /**
     * Push one component's value.
     *
     * @param component 0..3, i.e. {@code v_1}..{@code v_4}
     * @param value     the value to remember
     */
    void push(final int component, final double value) {
        double[] row = values[component];
        if (depth[component] == row.length) {
            final double[] bigger = new double[row.length * 2];
            System.arraycopy(row, 0, bigger, 0, row.length);
            values[component] = bigger;
            row = bigger;
        }
        row[depth[component]++] = value;
    }

    /**
     * @param component 0..3
     * @return whether {@link #pop} would return anything
     */
    boolean isEmpty(final int component) {
        return depth[component] == 0;
    }

    /**
     * Pop one component's value. Only call when {@link #isEmpty} is {@code false}.
     *
     * @param component 0..3
     * @return the most recently pushed value
     */
    double pop(final int component) {
        return values[component][--depth[component]];
    }

    @Override
    public String toString() {
        return "CoordinateStack[depths=" + depth[0] + "," + depth[1] + "," + depth[2]
                + "," + depth[3] + "]";
    }
}

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
 * One step of an assembled {@link Pipeline}: an operator plus the {@code +inv} flag
 * that decides which of its two directions the pipeline's forward pass uses.
 *
 * <h2>{@code +inv} is a toggle, not a switch</h2>
 *
 * <p>{@code pipeline.cpp:517-523} walks the step's <em>combined</em> argument list —
 * the step's own tokens followed by the pipeline's global tokens — and flips the
 * flag once per token that is exactly {@code "inv"}. Two occurrences therefore
 * cancel, which is upstream's documented way of letting a global {@code +inv} be
 * overridden per step, and the match is exact so {@code +inv=T} does not count.
 *
 * <h2>What it does to the unit sides</h2>
 *
 * <p>Two independent consequences, both of which the gie comparator depends on:
 * the step runs its opposite direction, <em>and</em> its declared left and right
 * exchange places ({@code internal.cpp:49-61}). So an inverted projection step
 * presents a projected left-hand side and an angular right-hand side, which is why
 * a {@code +step +proj=X +inv} can legally precede a {@code +step +init=…}.
 *
 * <h2>{@code +omit_fwd} and {@code +omit_inv}</h2>
 *
 * <p>Two independent per-step booleans, read with {@code pj_param}'s {@code 'b'} sigil
 * ({@code pipeline.cpp:525-527}). Each suppresses the step in <em>one</em> pass — and
 * note that "one pass" means the pipeline's forward or reverse pass, not the
 * operator's forward or inverse method, so {@code +inv +omit_fwd} omits the step from
 * the pipeline's forward pass even though the direction it would have run is the
 * operator's inverse. {@code 4D-API_cs2cs-style.gie:400-440} pins all four
 * combinations.
 *
 * <p>They also relax the two availability checks: an omitted direction needs no
 * implementation, so {@code omit_fwd} makes {@link #canRunForward()} true
 * unconditionally ({@code pipeline.cpp:536-538}) and {@code omit_inv} does the same for
 * {@link #canRunReverse()} ({@code :561}). A step that is omitted in a direction can
 * therefore be non-invertible without making the whole pipeline one-way.
 *
 * <p>Immutable apart from the operator, which {@link Pipeline} may ask to
 * {@linkplain PipelineOperator#overrideUnits override its units} while the pipeline
 * is being assembled.
 *
 * @since 1.5
 */
public final class PipelineStep {

    private final PipelineOperator operator;
    private final boolean inverted;
    private final boolean omitForward;
    private final boolean omitReverse;

    PipelineStep(final PipelineOperator operator, final boolean inverted) {
        this(operator, inverted, false, false);
    }

    PipelineStep(final PipelineOperator operator, final boolean inverted,
            final boolean omitForward, final boolean omitReverse) {
        this.operator = operator;
        this.inverted = inverted;
        this.omitForward = omitForward;
        this.omitReverse = omitReverse;
    }

    /** @return the operator; never {@code null}. */
    PipelineOperator operator() {
        return operator;
    }

    /** @return whether an odd number of {@code inv} tokens applied to this step. */
    public boolean isInverted() {
        return inverted;
    }

    /** @return {@code +omit_fwd}: this step is skipped in the pipeline's forward pass. */
    public boolean isOmittedForward() {
        return omitForward;
    }

    /** @return {@code +omit_inv}: this step is skipped in the pipeline's reverse pass. */
    public boolean isOmittedReverse() {
        return omitReverse;
    }

    /** @return {@code pj_left(step)}: the folded left-hand unit, honouring {@code +inv}. */
    public GieIoUnits left() {
        return GieIoUnits.pjLeft(operator.declaredLeft(), operator.declaredRight(), inverted);
    }

    /** @return {@code pj_right(step)}: the folded right-hand unit, honouring {@code +inv}. */
    public GieIoUnits right() {
        return GieIoUnits.pjRight(operator.declaredLeft(), operator.declaredRight(), inverted);
    }

    /**
     * @return whether the pipeline's forward pass can run this step —
     *         {@code pipeline.cpp:536-553}, which needs the operator's
     *         <em>inverse</em> when the step is inverted.
     */
    public boolean canRunForward() {
        return omitForward || !inverted || operator.hasInverse();
    }

    /** @return whether the pipeline's reverse pass can run this step. */
    public boolean canRunReverse() {
        return omitReverse || inverted || operator.hasInverse();
    }

    /**
     * Run this step as the pipeline's forward pass requires, or not at all when
     * {@code +omit_fwd} is set.
     *
     * @param coord {@code {x, y, z, t}}, mutated in place
     */
    void runForward(final double[] coord) {
        if (omitForward) {
            return;
        }
        if (inverted) {
            operator.inverse(coord);
        } else {
            operator.forward(coord);
        }
    }

    /**
     * Run this step as the pipeline's reverse pass requires, or not at all when
     * {@code +omit_inv} is set.
     *
     * @param coord {@code {x, y, z, t}}, mutated in place
     */
    void runReverse(final double[] coord) {
        if (omitReverse) {
            return;
        }
        if (inverted) {
            operator.forward(coord);
        } else {
            operator.inverse(coord);
        }
    }

    /** @return a short description, for diagnostics. */
    public String description() {
        return operator.description() + (inverted ? " +inv" : "")
                + (omitForward ? " +omit_fwd" : "") + (omitReverse ? " +omit_inv" : "");
    }

    @Override
    public String toString() {
        return "PipelineStep[" + description() + ", left=" + left() + ", right=" + right() + "]";
    }
}

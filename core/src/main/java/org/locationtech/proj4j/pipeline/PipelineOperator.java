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

import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.gie.GieIoUnits;

/**
 * One executable step of a {@code +proj=pipeline} — PROJ's {@code PJ} reduced to
 * what a pipeline needs of it.
 *
 * <h2>The coordinate contract</h2>
 *
 * <p>A coordinate is a {@code double[4]} holding {@code {x, y, z, t}}, mutated
 * <b>in place</b>, exactly as PROJ's {@code PJ_COORD} is passed by reference
 * through {@code pipeline_forward_4d}. Angular components are always
 * <b>radians</b>; linear components are metres unless the step's own units say
 * otherwise.
 *
 * <p>This is deliberately <em>not</em> PROJ's full 4D model. There is no
 * {@code PJ_COORD} union, no {@code push}/{@code pop} stack, and {@code t} is
 * carried untouched rather than transformed: the engine exists to run the GIGS
 * corpus and the geographic/projected half of the gie corpus, and every one of
 * those is 2D or 3D. The shape is chosen so the missing pieces can be added
 * without changing this interface.
 *
 * <h2>Failure</h2>
 *
 * <p>A step that cannot transform a coordinate <b>throws</b>. It never returns a
 * sentinel, and in particular never returns the input unchanged or a single
 * {@code NaN} ordinate: PROJ signals failure with an all-{@code HUGE_VAL}
 * coordinate, and mapping that onto an exception at the step boundary is what
 * keeps a failure from being mistaken for a plausible answer.
 *
 * @since 1.5
 */
public interface PipelineOperator {

    /**
     * {@code P->left} as declared by the operator's setup function, <b>before</b>
     * {@code pj_left}'s {@code CLASSIC}-to-{@code PROJECTED} folding and before the
     * {@code +inv} swap.
     *
     * @return the declared left-hand unit domain; never {@code null}
     */
    GieIoUnits declaredLeft();

    /**
     * {@code P->right} as declared by the operator's setup function.
     *
     * @return the declared right-hand unit domain; never {@code null}
     */
    GieIoUnits declaredRight();

    /**
     * Overwrite the declared units, as {@code pipeline.cpp:583-618} does when a
     * neighbouring step can disambiguate a {@link GieIoUnits#WHATEVER} pair.
     *
     * <p>Only ever called with both arguments equal, and only on an operator whose
     * two declared sides are both {@code WHATEVER} — which is why an implementation
     * that cannot be affected by its unit domain may ignore the call.
     *
     * @param left  the new declared left
     * @param right the new declared right
     */
    void overrideUnits(GieIoUnits left, GieIoUnits right);

    /**
     * Run the step in its forward direction.
     *
     * @param coord {@code {x, y, z, t}}, mutated in place
     * @throws Proj4jException if the coordinate cannot be transformed
     */
    void forward(double[] coord);

    /**
     * Run the step in its inverse direction.
     *
     * @param coord {@code {x, y, z, t}}, mutated in place
     * @throws Proj4jException if the coordinate cannot be transformed, or if this
     *                        operator has no inverse
     */
    void inverse(double[] coord);

    /**
     * @return {@code true} when {@link #inverse} can be called at all —
     *         {@code pj_has_inverse}. A pipeline containing one non-invertible step
     *         is itself non-invertible.
     */
    boolean hasInverse();

    /**
     * @return a short description for diagnostics, conventionally the
     *         {@code +proj=} name and the parameters that matter.
     */
    String description();
}

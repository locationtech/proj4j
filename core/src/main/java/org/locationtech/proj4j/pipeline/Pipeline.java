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

import java.util.Collections;
import java.util.List;

import org.locationtech.proj4j.gie.GieIoUnits;

/**
 * An assembled {@code +proj=pipeline}: an ordered list of {@link PipelineStep}s run
 * left to right forwards and right to left in reverse.
 *
 * <h2>The i/o units, and why they are not the first step's and the last step's</h2>
 *
 * <p>{@code pipeline.cpp:631-636} sets
 * {@code P->left = pj_left(steps.front())} and
 * {@code P->right = pj_right(steps.back())} — note {@code pj_left} and
 * {@code pj_right}, which fold {@code CLASSIC} to {@code PROJECTED} and swap the
 * two sides of any step carrying {@code +inv}. Getting this wrong does not produce
 * an error; it produces a <em>silently wrong distance</em>, because the gie
 * comparator picks between a Euclidean metric and a geodesic one on exactly this
 * value, and the two differ by a factor of about 111,319 at one degree.
 *
 * <p>{@code gigs/5102.2.gie} is the case that proves the machinery: its reverse
 * pipeline ends {@code +step +proj=unitconvert +xy_in=rad +xy_out=grad}, and
 * {@code grad} normalises to neither {@code "Radian"} nor {@code "Degree"}, so the
 * right-hand side stays {@link GieIoUnits#WHATEVER} and the comparator measures the
 * Euclidean distance between two coordinates expressed in <em>grads</em> against a
 * tolerance written in metres. That is upstream's behaviour and must be reproduced,
 * not corrected.
 *
 * <h2>The geodesic ellipsoid</h2>
 *
 * <p>A pipeline with no global {@code +ellps} defaults to <b>GRS80</b>
 * ({@code pipeline.cpp:316-354}) where a bare operation defaults to <b>WGS84</b>
 * ({@code init.cpp:576-581}). That default only feeds {@code P->geod}, i.e. the
 * geodesic the comparator measures with, so it is reported through
 * {@link #globalEllipsoidA()}/{@link #globalEllipsoidF()} rather than used here.
 *
 * <p>Not thread-safe: the steps wrap mutable proj4j {@code Projection} instances.
 * Build one per thread.
 *
 * @since 1.5
 */
public final class Pipeline {

    /** {@code set_ellipsoid}'s fallback ({@code pipeline.cpp:338-340}). */
    public static final double GRS80_A = 6378137.0;

    /** {@code set_ellipsoid}'s fallback flattening. */
    public static final double GRS80_F = 1.0 / 298.257222101;

    private final String definition;
    private final List<PipelineStep> steps;
    private final GieIoUnits left;
    private final GieIoUnits right;
    private final boolean invertible;
    private final double globalA;
    private final double globalF;

    Pipeline(final String definition, final List<PipelineStep> steps,
            final double globalA, final double globalF) {
        this.definition = definition;
        this.steps = Collections.unmodifiableList(steps);
        this.left = steps.get(0).left();
        this.right = steps.get(steps.size() - 1).right();
        this.globalA = globalA;
        this.globalF = globalF;
        boolean canReverse = true;
        for (int i = 0; i < steps.size(); i++) {
            if (!steps.get(i).canRunReverse()) {
                canReverse = false;
                break;
            }
        }
        this.invertible = canReverse;
    }

    /** @return the definition this pipeline was built from. */
    public String definition() {
        return definition;
    }

    /** @return the steps in order; unmodifiable, never empty. */
    public List<PipelineStep> steps() {
        return steps;
    }

    /** @return {@code P->left} = {@code pj_left(first step)}, already folded. */
    public GieIoUnits left() {
        return left;
    }

    /** @return {@code P->right} = {@code pj_right(last step)}, already folded. */
    public GieIoUnits right() {
        return right;
    }

    /**
     * @return {@code false} when at least one step has no usable inverse, which
     *         makes the whole pipeline one-way ({@code pipeline.cpp:556-568}).
     */
    public boolean isInvertible() {
        return invertible;
    }

    /** @return the semi-major axis of the pipeline's global ellipsoid, GRS80 by default. */
    public double globalEllipsoidA() {
        return globalA;
    }

    /** @return the flattening of the pipeline's global ellipsoid, GRS80 by default. */
    public double globalEllipsoidF() {
        return globalF;
    }

    /**
     * Run every step forward, in order.
     *
     * @param coord {@code {x, y, z, t}}; shorter arrays are rejected
     * @return a fresh four-element array
     * @throws org.locationtech.proj4j.Proj4jException if any step refuses the coordinate
     */
    public double[] forward(final double[] coord) {
        final double[] c = copyOf(coord);
        for (int i = 0; i < steps.size(); i++) {
            steps.get(i).runForward(c);
        }
        return c;
    }

    /**
     * Run every step in reverse, from the last to the first.
     *
     * @param coord {@code {x, y, z, t}}; shorter arrays are rejected
     * @return a fresh four-element array
     * @throws org.locationtech.proj4j.Proj4jException if any step refuses the coordinate
     */
    public double[] inverse(final double[] coord) {
        if (!invertible) {
            throw new PipelineDefinitionException(PipelineErrorCode.NO_INVERSE_OP,
                    "pipeline is not invertible: " + definition);
        }
        final double[] c = copyOf(coord);
        for (int i = steps.size() - 1; i >= 0; i--) {
            steps.get(i).runReverse(c);
        }
        return c;
    }

    private static double[] copyOf(final double[] coord) {
        if (coord == null || coord.length < 2) {
            throw new IllegalArgumentException("a coordinate needs at least two ordinates");
        }
        final double[] c = new double[4];
        c[0] = coord[0];
        c[1] = coord[1];
        c[2] = coord.length > 2 ? coord[2] : 0.0;
        c[3] = coord.length > 3 ? coord[3] : 0.0;
        return c;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("Pipeline[left=").append(left)
                .append(", right=").append(right);
        for (int i = 0; i < steps.size(); i++) {
            sb.append("\n  ").append(i + 1).append(". ").append(steps.get(i));
        }
        return sb.append(']').toString();
    }
}

/*
 * Copyright 2026 The Proj4J Contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.locationtech.proj4j.conformance.bridge;

import org.locationtech.proj4j.gie.GieDirection;
import org.locationtech.proj4j.gie.GieIoUnits;
import org.locationtech.proj4j.pipeline.Pipeline;

/**
 * A gie {@code operation} that resolved to a {@code +proj=pipeline}.
 *
 * <h2>The i/o units are the whole reason this class is not two lines</h2>
 *
 * <p>{@link Pipeline} already computes {@code P->left} and {@code P->right} the way
 * {@code 9.8.1:src/pipeline.cpp:631-636} does — {@code pj_left} of the first step and
 * {@code pj_right} of the last, folded, and honouring each step's {@code +inv}. This
 * class passes them straight through, and reports {@link #isInverted()} as
 * {@code false}, because a pipeline's own {@code P->inverted} is always 0: the
 * {@code +inv} tokens in a pipeline belong to its <em>steps</em> and have already
 * been accounted for inside {@code left()} and {@code right()}. Reporting
 * {@code isInverted() == true} here would swap the two sides a second time and hand
 * the comparator the wrong metric — which does not fail loudly, it just inflates or
 * deflates every deviation in the file by a factor of about 111,319.
 *
 * <h2>Failure handling</h2>
 *
 * <p>{@code null} is returned for a coordinate the pipeline refused, which the
 * runner turns into PROJ's all-{@code HUGE_VAL} error coordinate. A non-finite
 * output ordinate is treated as a failure rather than as a result, with PROJ's one
 * documented carve-out: if the corresponding <em>input</em> ordinate was
 * {@code NaN}, a {@code NaN} output is a result, so the comparator's
 * NaN-on-both-sides branch can fire.
 *
 * <p>Unlike {@link SingleProjectionOperation} this checks {@code z} as well as
 * {@code x} and {@code y}, because a pipeline can produce a meaningful third
 * ordinate: {@code gigs/5201.gie} is 60 geographic/geocentric conversions whose
 * expected values include an ellipsoidal height, and a silent {@code NaN} there
 * would be scored as a coordinate rather than as the numerical failure it is.
 *
 * <p>Not thread-safe: the underlying proj4j {@code Projection} objects are mutable.
 */
final class PipelineGieOperation implements GieOperation {

    private final String definition;
    private final Pipeline pipeline;

    private GieFailure lastFailure;

    PipelineGieOperation(String definition, Pipeline pipeline) {
        this.definition = definition;
        this.pipeline = pipeline;
    }

    /** The assembled pipeline, for tests and reports. */
    Pipeline pipeline() {
        return pipeline;
    }

    @Override
    public boolean isUsable() {
        return true;
    }

    @Override
    public GieFailure failure() {
        return null;
    }

    @Override
    public GieIoUnits leftUnits() {
        return pipeline.left();
    }

    @Override
    public GieIoUnits rightUnits() {
        return pipeline.right();
    }

    @Override
    public boolean isInverted() {
        // See the class comment: a pipeline's P->inverted is always 0.
        return false;
    }

    @Override
    public boolean crsDstIsLatLonOrYX() {
        // See GieOperation#crsDstIsLatLonOrYX: proj4j has no axis metadata, and a
        // pipeline is not opened by a crs_src/crs_dst pair in any case.
        return false;
    }

    @Override
    public GieFailure lastFailure() {
        return lastFailure;
    }

    @Override
    public double[] transform(double[] in, GieDirection dir) {
        lastFailure = null;
        if (in == null || in.length < 2) {
            lastFailure = GieFailures.of(GieFailureKind.INVALID_COORD,
                    "a coordinate needs at least two ordinates");
            return null;
        }
        double[] out;
        try {
            out = dir == GieDirection.FORWARD ? pipeline.forward(in) : pipeline.inverse(in);
        } catch (Throwable e) {
            GieFailure f = Proj4jGieOperationFactory.mapTransformThrowable(e);
            if (f == null) {
                // Not ours to swallow (OutOfMemoryError and friends).
                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                }
                throw (Error) e;
            }
            lastFailure = f;
            return null;
        }

        GieFailure nf = nonFinite(out[0], in[0], "x");
        if (nf == null) {
            nf = nonFinite(out[1], in[1], "y");
        }
        if (nf == null) {
            nf = nonFinite(out[2], in.length > 2 ? in[2] : 0.0, "z");
        }
        if (nf != null) {
            lastFailure = nf;
            return null;
        }
        return out;
    }

    private GieFailure nonFinite(double out, double in, String which) {
        if (!Double.isNaN(out) && !Double.isInfinite(out)) {
            return null;
        }
        if (Double.isNaN(out) && Double.isNaN(in)) {
            return null;
        }
        return GieFailures.of(GieFailureKind.NUMERICAL,
                "non-finite " + which + " = " + out + " from finite input " + in
                        + " (" + definition + ")");
    }

    @Override
    public String toString() {
        return "PipelineGieOperation[" + definition + ", left=" + pipeline.left()
                + ", right=" + pipeline.right() + ", " + pipeline.steps().size() + " steps]";
    }
}

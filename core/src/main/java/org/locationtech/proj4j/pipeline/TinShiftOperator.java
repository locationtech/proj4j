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

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.gie.GieIoUnits;
import org.locationtech.proj4j.resource.ChainedResourceResolver;
import org.locationtech.proj4j.resource.ResourceHandle;
import org.locationtech.proj4j.resource.ResourceResolvers;
import org.locationtech.proj4j.resource.Resources;

/**
 * {@code +proj=tinshift} — a triangulation-based transformation, ported from
 * {@code 9.8.1:src/transformations/tinshift.cpp} and
 * {@code tinshift_impl.hpp}. The numerics live in {@link Triangulation}; this class is
 * the parameter handling, the file read and the {@link PipelineOperator} contract.
 *
 * <h2>{@code WHATEVER} on both sides, and why that matters to a comparator</h2>
 *
 * <p>{@code P-&gt;left} and {@code P-&gt;right} are both
 * {@code PJ_IO_UNITS_WHATEVER} ({@code tinshift.cpp:130-131}), because a triangulation
 * may be defined over geographic degrees <em>or</em> over projected metres and the file
 * does not say which in a form the operator acts on — {@code input_crs} and
 * {@code output_crs} are metadata, and upstream reads them without using them.
 *
 * <p>The consequence is deliberate and must not be "fixed": a gie tolerance written in
 * metres is compared against a Euclidean distance in whatever units the file uses. For
 * {@code tinshift_crs_implicit.json}, whose vertices are degrees, that means
 * {@code accept 2 49 / expect 2.1 49.1} is measured as a Euclidean distance of about
 * {@code 0.1414} against a default tolerance of {@code 0.5}, and passes — not because
 * the shift is within 14 cm but because the comparator is not measuring metres. This is
 * exactly the {@code grad} situation {@link PipelineUnits} documents.
 *
 * <h2>A finite point outside the triangulation is a failure, not a pass-through</h2>
 *
 * <p>Upstream returns {@code proj_coord_error()} — an all-{@code HUGE_VAL} coordinate —
 * and {@code tinshift.gie} asserts exactly that for {@code (0, 0)} in both directions.
 * Here it is a throw carrying {@link ErrorCause#COORDINATE_OUTSIDE_GRID}, which the
 * comparator scores identically and a caller can act on. What is <em>not</em> done is
 * returning the input unchanged: with {@code fallback_strategy: none} that is the
 * difference between "this coordinate is outside the model" and "the model says the
 * shift here is zero", and the two are hundreds of metres apart.
 *
 * <h2>Reading the file</h2>
 *
 * <p>Through the deterministic resolver chain, never the working directory, and with
 * upstream's own 100 MB ceiling ({@code tinshift.cpp:98-104}) — the file is named by a
 * proj-string that may have come from row data, so an unbounded read is a denial of
 * service. The bytes are decoded as UTF-8; RFC 8259 mandates UTF-8 for JSON exchanged
 * between systems, and every model file in the corpus is ASCII.
 *
 * <p>Immutable after construction; safe to share.
 *
 * @since 1.5
 */
final class TinShiftOperator implements PipelineOperator {

    /** {@code 100 * 1024 * 1024} — upstream's ceiling, for upstream's stated reason. */
    private static final long MAX_MODEL_BYTES = 100L * 1024L * 1024L;

    private final String file;
    private final Triangulation triangulation;

    private TinShiftOperator(final String file, final Triangulation triangulation) {
        this.file = file;
        this.triangulation = triangulation;
    }

    /**
     * Resolve {@code +file=} and parse it.
     *
     * @param fileName the {@code +file=} value
     * @return the operator
     * @throws PipelineDefinitionException {@code MISSING_ARG} when {@code +file} is
     *                                     absent, {@code FILE_NOT_FOUND_OR_INVALID}
     *                                     when it cannot be read or is not a valid
     *                                     triangulation
     */
    static TinShiftOperator fromFile(final String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            throw new PipelineDefinitionException(PipelineErrorCode.MISSING_ARG,
                    "+file= should be specified.");
        }
        return new TinShiftOperator(fileName, Triangulation.parse(read(fileName)));
    }

    private static String read(final String fileName) {
        final ChainedResourceResolver chain = ResourceResolvers.resolver();
        final ResourceHandle handle;
        try {
            handle = chain.resolve(fileName);
        } catch (final IOException e) {
            throw new PipelineDefinitionException(PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID,
                    "Cannot open " + fileName + ": " + e.getMessage(), e);
        }
        if (handle == null) {
            throw new PipelineDefinitionException(PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID,
                    "Cannot open " + fileName + ". Resolution chain was " + chain.name()
                            + "; the working directory is deliberately not searched.");
        }
        final byte[] bytes;
        try {
            bytes = Resources.readAll(handle, MAX_MODEL_BYTES);
        } catch (final IOException e) {
            throw new PipelineDefinitionException(PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID,
                    "Cannot read " + fileName + ": " + e.getMessage(), e);
        }
        try {
            return new String(bytes, "UTF-8");
        } catch (final UnsupportedEncodingException e) {
            // Unreachable: every JVM is required to support UTF-8.
            throw new PipelineDefinitionException(PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID,
                    "Cannot decode " + fileName + " as UTF-8", e);
        }
    }

    /** @return the {@code +file=} value as written. */
    String file() {
        return file;
    }

    /** @return the parsed model, for tests and diagnostics. */
    Triangulation triangulation() {
        return triangulation;
    }

    /** {@code P-&gt;left = PJ_IO_UNITS_WHATEVER} ({@code tinshift.cpp:130}). */
    @Override
    public GieIoUnits declaredLeft() {
        return GieIoUnits.WHATEVER;
    }

    /** {@code P-&gt;right = PJ_IO_UNITS_WHATEVER} ({@code tinshift.cpp:131}). */
    @Override
    public GieIoUnits declaredRight() {
        return GieIoUnits.WHATEVER;
    }

    /**
     * A triangulation's units are a property of its vertices, not of its neighbours, so
     * a neighbour's opinion is recorded nowhere and changes nothing. Upstream has the
     * same property: {@code pipeline.cpp}'s propagation writes {@code P-&gt;left} and
     * {@code P-&gt;right}, which {@code tinshift} never reads.
     */
    @Override
    public void overrideUnits(final GieIoUnits left, final GieIoUnits right) {
        // no-op, deliberately
    }

    @Override
    public void forward(final double[] coord) {
        if (!triangulation.forward(coord)) {
            throw outside(coord);
        }
    }

    @Override
    public void inverse(final double[] coord) {
        if (!triangulation.inverse(coord)) {
            throw outside(coord);
        }
    }

    private CrsTransformException outside(final double[] coord) {
        return new CrsTransformException(ErrorCause.COORDINATE_OUTSIDE_GRID,
                "(" + coord[0] + ", " + coord[1] + ") is outside every triangle of +file="
                        + file + " (" + triangulation.triangleCount() + " triangles, "
                        + "fallback_strategy=" + triangulation.fallbackStrategy() + ")");
    }

    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public String description() {
        return "tinshift file=" + file;
    }

    @Override
    public String toString() {
        return "TinShiftOperator[" + description() + ", " + triangulation + "]";
    }
}

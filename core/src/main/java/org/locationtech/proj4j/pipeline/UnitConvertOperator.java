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
 * {@code +proj=unitconvert} ({@code 9.8.1:src/conversions/unitconvert.cpp}), the
 * spatial half.
 *
 * <p>Supports {@code +xy_in}, {@code +xy_out}, {@code +z_in} and {@code +z_out},
 * each taking either a unit id from {@link PipelineUnits} or a raw numeric factor.
 * Conversion goes through a pivot unit — metres for linear, radians for angular —
 * so a single multiplier suffices:
 *
 * <pre>
 * xy_factor  = factor(xy_in) / factor(xy_out)
 * forward:  xy *= xy_factor      inverse:  xy /= xy_factor
 * </pre>
 *
 * <h2>Deliberately absent: {@code +t_in}/{@code +t_out}</h2>
 *
 * <p>Time-unit conversion needs the modified-Julian-date pivot and PROJ's eleven
 * time formats. No GIGS file and no geographic gie file uses one, and a pipeline
 * engine that silently ignored a time unit it was asked for would produce a wrong
 * answer rather than a refusal — so a {@code +t_in} or {@code +t_out} is rejected
 * as unimplemented instead.
 *
 * <h2>The unit domain, which is what the gie comparator branches on</h2>
 *
 * <p>{@code P->left} and {@code P->right} start {@link GieIoUnits#WHATEVER} and are
 * raised to {@code RADIANS} or {@code DEGREES} <b>only</b> when the corresponding
 * normalised unit name is exactly {@code "Radian"} or {@code "Degree"}. {@code grad}
 * normalises to {@code "Grad"} and therefore raises neither. See
 * {@link PipelineUnits} for why that is load bearing rather than a curiosity.
 *
 * <p>PROJ also rejects mixing a linear with an angular unit across a single pair
 * ({@code unitconvert.cpp:517-523}), which is reproduced.
 *
 * <p>Not immutable only because {@link #overrideUnits} exists; safe to use from one
 * thread at a time, like every other operator here.
 */
final class UnitConvertOperator implements PipelineOperator {

    private final double xyFactor;
    private final double zFactor;
    private final String description;

    private GieIoUnits left;
    private GieIoUnits right;

    UnitConvertOperator(final ProjParams params) {
        double xy = 1.0;
        double z = 1.0;
        GieIoUnits l = GieIoUnits.WHATEVER;
        GieIoUnits r = GieIoUnits.WHATEVER;

        if (params.has("t_in") || params.has("t_out")) {
            throw new PipelineDefinitionException(PipelineErrorCode.NOT_IMPLEMENTED_HERE,
                    "+proj=unitconvert with a time unit: the modified-Julian-date pivot and "
                            + "PROJ's eleven time formats are not implemented, and running "
                            + "without the conversion would silently produce a wrong answer");
        }

        int xyInLinear = -1;
        int xyOutLinear = -1;
        int zInLinear = -1;
        int zOutLinear = -1;

        if (params.has("xy_in")) {
            final PipelineUnits.Resolution u = resolve(params, "xy_in");
            xy = u.factor();
            xyInLinear = u.linear();
            l = domainOf(u.normalisedName(), l);
        }
        if (params.has("xy_out")) {
            final PipelineUnits.Resolution u = resolve(params, "xy_out");
            xy /= u.factor();
            xyOutLinear = u.linear();
            r = domainOf(u.normalisedName(), r);
        }
        if (xyInLinear >= 0 && xyOutLinear >= 0 && xyInLinear != xyOutLinear) {
            throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                    "inconsistent unit type between xy_in and xy_out");
        }

        if (params.has("z_in")) {
            final PipelineUnits.Resolution u = resolve(params, "z_in");
            z = u.factor();
            zInLinear = u.linear();
        }
        if (params.has("z_out")) {
            final PipelineUnits.Resolution u = resolve(params, "z_out");
            z /= u.factor();
            zOutLinear = u.linear();
        }
        if (zInLinear >= 0 && zOutLinear >= 0 && zInLinear != zOutLinear) {
            throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                    "inconsistent unit type between z_in and z_out");
        }

        this.xyFactor = xy;
        this.zFactor = z;
        this.left = l;
        this.right = r;
        this.description = "unitconvert xy_in=" + params.value("xy_in")
                + " xy_out=" + params.value("xy_out");
    }

    /**
     * A unit id, or — when the id is unknown to both tables — the same token read
     * as a raw {@code double}. PROJ tries {@code pj_param(...,"d<key>")} as a
     * fallback and errors only when that yields 0 or a value whose reciprocal is 0
     * ({@code unitconvert.cpp:472-479}), i.e. zero or infinite.
     */
    private static PipelineUnits.Resolution resolve(final ProjParams params, final String key) {
        final String id = params.value(key);
        final PipelineUnits.Resolution known = PipelineUnits.resolve(id);
        if (known.isKnown()) {
            return known;
        }
        final double factor = params.doubleValue(key, 0.0);
        if (factor == 0.0 || 1.0 / factor == 0.0) {
            throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                    "unknown " + key + " unit: " + id);
        }
        return PipelineUnits.Resolution.rawFactor(factor);
    }

    private static GieIoUnits domainOf(final String normalisedName, final GieIoUnits fallback) {
        if ("Radian".equals(normalisedName)) {
            return GieIoUnits.RADIANS;
        }
        if ("Degree".equals(normalisedName)) {
            return GieIoUnits.DEGREES;
        }
        return fallback;
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
    public void forward(final double[] coord) {
        coord[0] *= xyFactor;
        coord[1] *= xyFactor;
        coord[2] *= zFactor;
    }

    @Override
    public void inverse(final double[] coord) {
        coord[0] /= xyFactor;
        coord[1] /= xyFactor;
        coord[2] /= zFactor;
    }

    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public String toString() {
        return "UnitConvertOperator[" + description + ", xy*" + xyFactor + ", left=" + left
                + ", right=" + right + "]";
    }
}

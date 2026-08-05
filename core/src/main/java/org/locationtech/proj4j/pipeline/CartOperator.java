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

import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.gie.GieIoUnits;

/**
 * {@code +proj=cart} as a <b>user-facing</b> pipeline step
 * ({@code 9.8.1:src/conversions/cart.cpp}): geodetic {@code (lam, phi, h)} in
 * radians and metres to geocentric cartesian {@code (X, Y, Z)} in metres.
 *
 * <h2>Why this exists when {@link CartConversion} already did</h2>
 *
 * <p>{@code CartConversion} is the <em>hidden</em> helper {@link Cs2csOperator}
 * builds for {@code +towgs84} and for a geocentric operation. It is not a
 * {@link PipelineOperator} and it carries no unit sides, because in that role it
 * runs inside another step rather than beside it. A written-out
 * {@code +step +proj=cart +ellps=GRS80} is a different thing: it is a step, it
 * declares {@code RADIANS} on the left and {@code CARTESIAN} on the right
 * ({@code cart.cpp:262-263}), and those two values are what make
 * {@code +step +proj=cart} … {@code +step +proj=deformation} …
 * {@code +step +proj=cart +inv} pass the pipeline's unit-continuity check.
 *
 * <p>Distinct from {@code +proj=geocent}, which {@link Cs2csOperator} handles.
 * {@code geocent}'s own formula is the <em>identity</em>
 * ({@code geocent.cpp}: {@code xy.x = lp.lam; xy.y = lp.phi}) and the cartesian
 * conversion is performed for it by {@code fwd_prepare}'s cs2cs emulation. Treating
 * the two as synonyms would apply the conversion twice for one of them.
 *
 * <p>{@code +proj=cart}'s own setup function reads <b>no</b> parameters beyond the
 * ellipsoid. It is nevertheless affected by one: the generic linear-unit scale.
 *
 * <h2>{@code +units} / {@code +to_meter} apply, and {@code +x_0} does not</h2>
 *
 * <p>{@code fwd_finalize}'s {@code PJ_IO_UNITS_CARTESIAN} case
 * ({@code 9.8.1:src/fwd.cpp:128-137}) multiplies <em>all three</em> ordinates by
 * {@code P-&gt;fr_meter}, and {@code inv_prepare} ({@code inv.cpp:66-73}) multiplies them
 * by {@code P-&gt;to_meter} — but unlike the {@code PROJECTED} case it applies <b>no</b>
 * false easting, northing or {@code +z_0}, and no separate vertical unit. So
 * {@code +proj=cart +a=1000 +b=1000 +to_meter=1000} takes {@code (90, 0, 0)} to
 * {@code (0, 1, 0)}, not to {@code (0, 1000, 0)}:
 * {@code 4D-API_cs2cs-style.gie:493} asserts exactly that, immediately after the same
 * assertion for {@code +proj=geocent}, so the pair exists to check that both spellings
 * scale.
 *
 * <p>Silently dropping the scale would be a factor-of-{@code to_meter} error reported as
 * success, which is why it is here rather than left to a later affine pass.
 *
 * <p>Immutable and thread-safe apart from mutating the array passed in.
 *
 * @since 1.5
 */
final class CartOperator implements PipelineOperator {

    private final CartConversion conversion;
    private final double toMeter;
    private final double frMeter;
    private final String description;

    /**
     * @param registry resolves {@code +ellps=}
     * @param params   the step's fully expanded parameter list
     */
    CartOperator(final Registry registry, final ProjParams params) {
        final double[] ellipsoid = StepEllipsoid.resolve(registry, params);
        this.conversion = new CartConversion(ellipsoid[0], ellipsoid[1]);
        this.toMeter = linearToMeter(params);
        this.frMeter = 1.0 / toMeter;
        this.description = "cart a=" + ellipsoid[0] + " es=" + ellipsoid[1]
                + (toMeter == 1.0 ? "" : " to_meter=" + toMeter);
    }

    /**
     * {@code init.cpp:668-700}: {@code +units} is looked up in the linear table and
     * <b>wins over {@code +to_meter}</b> when both are given; {@code +to_meter} accepts a
     * {@code num/den} ratio; either being zero or non-positive is an error.
     *
     * @param params the step's parameter list
     * @return {@code P-&gt;to_meter}
     */
    private static double linearToMeter(final ProjParams params) {
        final String units = params.value("units");
        if (units != null && !units.isEmpty()) {
            final PipelineUnits.Resolution u = PipelineUnits.resolve(units);
            if (!u.isKnown() || u.linear() != 1) {
                throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                        "unknown +units=" + units);
            }
            return u.factor();
        }
        final String raw = params.value("to_meter");
        if (raw == null || raw.isEmpty()) {
            return 1.0;
        }
        final double value = ratio(raw);
        if (!(value > 0) || Double.isInfinite(value)) {
            throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                    "+to_meter=" + raw + " must be a positive finite number");
        }
        return value;
    }

    /** {@code pj_units_ratio}: a plain double, or {@code num/den}. */
    private static double ratio(final String raw) {
        final int slash = raw.indexOf('/');
        try {
            if (slash < 0) {
                return Double.parseDouble(raw.trim());
            }
            final double den = Double.parseDouble(raw.substring(slash + 1).trim());
            if (den == 0.0) {
                throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                        "+to_meter=" + raw + " has a zero denominator");
            }
            return Double.parseDouble(raw.substring(0, slash).trim()) / den;
        } catch (final NumberFormatException e) {
            throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                    "+to_meter=" + raw + " is not a number or a num/den ratio", e);
        }
    }

    /** {@code P-&gt;left = PJ_IO_UNITS_RADIANS} ({@code cart.cpp:262}). */
    @Override
    public GieIoUnits declaredLeft() {
        return GieIoUnits.RADIANS;
    }

    /** {@code P-&gt;right = PJ_IO_UNITS_CARTESIAN} ({@code cart.cpp:263}). */
    @Override
    public GieIoUnits declaredRight() {
        return GieIoUnits.CARTESIAN;
    }

    /** Never called: neither declared side is {@link GieIoUnits#WHATEVER}. */
    @Override
    public void overrideUnits(final GieIoUnits left, final GieIoUnits right) {
        // no-op, deliberately
    }

    /** {@code cart.cpp}'s {@code cartesian()}, then {@code fwd_finalize}'s {@code fr_meter}. */
    @Override
    public void forward(final double[] coord) {
        conversion.forward(coord);
        if (frMeter != 1.0) {
            coord[0] *= frMeter;
            coord[1] *= frMeter;
            coord[2] *= frMeter;
        }
    }

    /** {@code inv_prepare}'s {@code to_meter}, then {@code cart.cpp}'s {@code geodetic()}. */
    @Override
    public void inverse(final double[] coord) {
        if (toMeter != 1.0) {
            coord[0] *= toMeter;
            coord[1] *= toMeter;
            coord[2] *= toMeter;
        }
        conversion.inverse(coord);
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
        return "CartOperator[" + description + "]";
    }
}

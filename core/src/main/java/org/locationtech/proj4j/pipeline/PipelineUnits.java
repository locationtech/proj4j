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
 * PROJ's linear and angular unit tables ({@code 9.8.1:src/units.cpp}), as
 * {@code +proj=unitconvert} sees them through
 * {@code get_unit_conversion_factor()}.
 *
 * <h2>Why not {@code org.locationtech.proj4j.units.Units}</h2>
 *
 * <p>Because the <b>normalised name</b> is load bearing and proj4j's table does
 * not carry PROJ's. {@code unitconvert} sets {@code P->left}/{@code P->right} to
 * {@code RADIANS} or {@code DEGREES} only when the normalised name is exactly
 * {@code "Radian"} or {@code "Degree"} ({@code unitconvert.cpp:487-493, 510-516}),
 * and otherwise leaves the side {@code WHATEVER}. {@code grad} normalises to
 * {@code "Grad"}, which is neither — so
 * {@code +step +proj=unitconvert +xy_in=rad +xy_out=grad} leaves the pipeline's
 * right-hand side {@code WHATEVER}, and the gie comparator therefore measures the
 * <b>Euclidean</b> distance between two coordinates expressed in grads against a
 * tolerance written in metres. That is exactly what {@code gigs/5102.2.gie} does,
 * and it is deliberate upstream behaviour: reproducing it faithfully is required,
 * "fixing" it silently changes 38 expected values.
 *
 * <p>The linear factors are also PROJ's own literals. {@code us-ft} is
 * {@code 1200/3937}, evaluated at full double precision, not the rounded
 * {@code 0.304800609601219} decimal proj4j's {@code Units} carries — a 1e-16
 * relative difference that matters to nobody but costs nothing to get right, and
 * {@code gigs/5103.2} versus {@code 5103.3} exist precisely to separate
 * {@code ft} from {@code us-ft}.
 *
 * <p>Stateless; not instantiable.
 */
final class PipelineUnits {

    /** {@code M_PI / 200}, spelled as {@code units.cpp:41} spells it. */
    static final double GRAD_TO_RAD = 0.015707963267948967;

    /** {@code DEG_TO_RAD} from {@code proj_internal.h}. */
    static final double DEG_TO_RAD = 0.017453292519943296;

    /** PROJ's normalised name for a unit whose id is unknown to both tables. */
    static final String UNKNOWN = null;

    private static final String[] LINEAR_IDS = {
        "km", "m", "dm", "cm", "mm", "kmi", "in", "ft", "yd", "mi", "fath", "ch", "link",
        "us-in", "us-ft", "us-yd", "us-ch", "us-mi", "ind-yd", "ind-ft", "ind-ch",
    };

    private static final double[] LINEAR_FACTORS = {
        1000.0, 1.0, 0.1, 0.01, 0.001, 1852.0, 0.0254, 0.3048, 0.9144, 1609.344, 1.8288,
        20.1168, 0.201168, 100 / 3937.0, 1200 / 3937.0, 3600 / 3937.0, 79200 / 3937.0,
        6336000 / 3937.0, 0.91439523, 0.30479841, 20.11669506,
    };

    private static final String[] LINEAR_NAMES = {
        "Kilometer", "Meter", "Decimeter", "Centimeter", "Millimeter",
        "International Nautical Mile", "International Inch", "International Foot",
        "International Yard", "International Statute Mile", "International Fathom",
        "International Chain", "International Link", "U.S. Surveyor's Inch",
        "U.S. Surveyor's Foot", "U.S. Surveyor's Yard", "U.S. Surveyor's Chain",
        "U.S. Surveyor's Statute Mile", "Indian Yard", "Indian Foot", "Indian Chain",
    };

    private static final String[] ANGULAR_IDS = {"rad", "deg", "grad"};

    private static final double[] ANGULAR_FACTORS = {1.0, DEG_TO_RAD, GRAD_TO_RAD};

    private static final String[] ANGULAR_NAMES = {"Radian", "Degree", "Grad"};

    private PipelineUnits() {
        throw new AssertionError("no instances");
    }

    /**
     * One row of PROJ's unit table, or the "not a unit id" answer.
     *
     * <p>PROJ returns three things from one call through out-parameters: the
     * factor, whether the unit is linear, and the normalised name. All three are
     * needed at every call site, so they travel together.
     */
    static final class Resolution {

        /** {@code get_unit_conversion_factor} returning 0.0: not a known unit id. */
        static final Resolution NOT_A_UNIT = new Resolution(0.0, -1, null);

        private final double factor;
        private final int linear;
        private final String normalisedName;

        private Resolution(final double factor, final int linear, final String normalisedName) {
            this.factor = factor;
            this.linear = linear;
            this.normalisedName = normalisedName;
        }

        /**
         * PROJ's numeric fallback: {@code +xy_in=0.5} rather than a unit id. It
         * carries <b>no normalised name and no linearity</b>, because upstream
         * leaves {@code normalized_name} null and {@code p_is_linear} at its
         * initial {@code -1} on that path — so a raw factor can never raise a
         * step's unit domain above {@link GieIoUnits#WHATEVER}, and never triggers
         * the linear/angular consistency check.
         *
         * @param factor the multiplier, already validated as finite and non-zero
         * @return a nameless resolution
         */
        static Resolution rawFactor(final double factor) {
            return new Resolution(factor, -1, null);
        }

        /** @return the multiplier to the pivot unit (metres, or radians). */
        double factor() {
            return factor;
        }

        /** @return {@code 1} linear, {@code 0} angular, {@code -1} unknown — PROJ's {@code p_is_linear}. */
        int linear() {
            return linear;
        }

        /** @return PROJ's normalised name, or {@code null} when the id is unknown. */
        String normalisedName() {
            return normalisedName;
        }

        /** @return whether this is a recognised unit id. */
        boolean isKnown() {
            return factor != 0.0;
        }
    }

    /**
     * {@code get_unit_conversion_factor()} ({@code unitconvert.cpp:396-434}):
     * linear table first, then angular. Ids are matched with {@code strcmp}, so the
     * comparison is case sensitive and exact.
     *
     * @param id a unit id, may be {@code null}
     * @return the resolution; {@link Resolution#NOT_A_UNIT} when unknown
     */
    static Resolution resolve(final String id) {
        if (id == null) {
            return Resolution.NOT_A_UNIT;
        }
        for (int i = 0; i < LINEAR_IDS.length; i++) {
            if (LINEAR_IDS[i].equals(id)) {
                return new Resolution(LINEAR_FACTORS[i], 1, LINEAR_NAMES[i]);
            }
        }
        for (int i = 0; i < ANGULAR_IDS.length; i++) {
            if (ANGULAR_IDS[i].equals(id)) {
                return new Resolution(ANGULAR_FACTORS[i], 0, ANGULAR_NAMES[i]);
            }
        }
        return Resolution.NOT_A_UNIT;
    }
}

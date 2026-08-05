/*******************************************************************************
 * Copyright 2026 Proj4J contributors
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
package org.locationtech.proj4j.gie;

/**
 * The unit-domain of one side of an operation, mirroring PROJ's
 * {@code enum pj_io_units} (PROJ 9.8.1, {@code src/proj_internal.h:192-201}).
 *
 * <p>The numeric {@link #code()} values are PROJ's and are part of this type's contract:
 * {@code WHATEVER=0, CLASSIC=1, PROJECTED=2, CARTESIAN=3, RADIANS=4, DEGREES=5}.
 *
 * <p>The important operation here is {@link #folded()}. PROJ never exposes a raw
 * {@code P-&gt;left}/{@code P-&gt;right} to the comparison code: every read goes through
 * {@code pj_left()}/{@code pj_right()} ({@code src/internal.cpp:49-61}), which collapse
 * {@link #CLASSIC} to {@link #PROJECTED} on <em>every</em> read. Code that selects a
 * comparison metric must therefore always fold first, otherwise {@code CLASSIC} — the
 * default right-hand unit of every {@code PROJ_HEAD} projection — would fall through
 * unrecognised.
 *
 * <p><b>Why this matters.</b> The default for every {@code PROJ_HEAD} projection is
 * {@code left = RADIANS, right = CLASSIC} ({@code proj_internal.h:882-883}). That single
 * fact is why every forward projection test in the gie corpus is compared with a Euclidean
 * metric in metres, and every {@code direction inverse} projection test is compared with a
 * geodesic metric from radians.
 *
 * @see GieMetric
 */
public enum GieIoUnits {

    /** {@code PJ_IO_UNITS_WHATEVER} — doesn't matter, or depends on pipeline neighbours. */
    WHATEVER(0),

    /** {@code PJ_IO_UNITS_CLASSIC} — scaled metres (right), projected system. Folds to {@link #PROJECTED}. */
    CLASSIC(1),

    /** {@code PJ_IO_UNITS_PROJECTED} — metres, projected system. */
    PROJECTED(2),

    /** {@code PJ_IO_UNITS_CARTESIAN} — metres, 3D cartesian system. */
    CARTESIAN(3),

    /** {@code PJ_IO_UNITS_RADIANS} — radians. */
    RADIANS(4),

    /** {@code PJ_IO_UNITS_DEGREES} — degrees. */
    DEGREES(5);

    private final int code;

    GieIoUnits(final int code) {
        this.code = code;
    }

    /**
     * @return the numeric value of the corresponding {@code pj_io_units} constant.
     */
    public int code() {
        return code;
    }

    /**
     * The value as {@code pj_left()}/{@code pj_right()} would return it:
     * {@link #CLASSIC} becomes {@link #PROJECTED}, everything else is unchanged.
     *
     * <p>Transcribed from {@code src/internal.cpp:49-61}:
     * <pre>
     * enum pj_io_units pj_right(PJ *P) {
     *     enum pj_io_units u = P-&gt;inverted ? P-&gt;left : P-&gt;right;
     *     if (u == PJ_IO_UNITS_CLASSIC) return PJ_IO_UNITS_PROJECTED;
     *     return u;
     * }
     * </pre>
     *
     * @return {@link #PROJECTED} if this is {@link #CLASSIC}, otherwise {@code this}.
     */
    public GieIoUnits folded() {
        return this == CLASSIC ? PROJECTED : this;
    }

    /**
     * @param code a {@code pj_io_units} numeric value.
     * @return the matching constant.
     * @throws IllegalArgumentException if {@code code} is not one of 0..5.
     */
    public static GieIoUnits fromCode(final int code) {
        for (final GieIoUnits u : values()) {
            if (u.code == code) {
                return u;
            }
        }
        throw new IllegalArgumentException("Not a pj_io_units value: " + code);
    }

    /**
     * {@code pj_left(P)} — the folded left-hand unit, honouring {@code P->inverted}
     * (i.e. {@code +inv} on the operation), which exchanges left and right.
     *
     * @param left     the declared {@code P->left}.
     * @param right    the declared {@code P->right}.
     * @param inverted {@code true} if {@code P->inverted}.
     * @return the folded effective left-hand unit.
     */
    public static GieIoUnits pjLeft(final GieIoUnits left, final GieIoUnits right, final boolean inverted) {
        return (inverted ? right : left).folded();
    }

    /**
     * {@code pj_right(P)} — the folded right-hand unit, honouring {@code P->inverted}.
     *
     * @param left     the declared {@code P->left}.
     * @param right    the declared {@code P->right}.
     * @param inverted {@code true} if {@code P->inverted}.
     * @return the folded effective right-hand unit.
     */
    public static GieIoUnits pjRight(final GieIoUnits left, final GieIoUnits right, final boolean inverted) {
        return (inverted ? left : right).folded();
    }

    /**
     * The unit domain a transform <em>produces</em> when run in {@code dir} — the value the
     * gie comparator branches on.
     *
     * <p>{@code src/coordinates.cpp:52-93} defines
     * {@code *_output(P, dir) == *_input(P, opposite(dir))}, and {@code *_input(P, FWD)}
     * inspects {@code pj_left(P)} while {@code *_input(P, INV)} inspects {@code pj_right(P)}.
     * Composing the two:
     *
     * <blockquote>
     * output({@link GieDirection#FORWARD}) inspects {@code pj_right(P)};
     * output({@link GieDirection#INVERSE}) inspects {@code pj_left(P)}.
     * </blockquote>
     *
     * @param left     the declared {@code P->left}.
     * @param right    the declared {@code P->right}.
     * @param inverted {@code true} if {@code P->inverted}.
     * @param dir      the direction the operation is being run in.
     * @return the folded output-side unit.
     */
    public static GieIoUnits outputUnits(final GieIoUnits left,
                                         final GieIoUnits right,
                                         final boolean inverted,
                                         final GieDirection dir) {
        // *_output(P, dir) == *_input(P, opposite(dir)); *_input(FWD) reads pj_left.
        return dir.opposite() == GieDirection.FORWARD
                ? pjLeft(left, right, inverted)
                : pjRight(left, right, inverted);
    }
}

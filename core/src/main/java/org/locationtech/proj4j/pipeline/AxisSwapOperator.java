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
 * {@code +proj=axisswap} ({@code 9.8.1:src/conversions/axisswap.cpp}).
 *
 * <p>Takes <b>exactly one</b> of {@code +order} or {@code +axis}. Both, or
 * neither, is {@code PROJ_ERR_INVALID_OP_MUTUALLY_EXCLUSIVE_ARGS} — upstream
 * writes that test as {@code !exists(order) == !exists(axis)}, which is worth
 * reading twice.
 *
 * <ul>
 * <li>{@code +order=2,1} is a permutation of 1-based ordinate indices; a leading
 *     {@code '-'} flips that ordinate's sign.</li>
 * <li>{@code +axis=wsu} is the classic PROJ.4 three-letter form over
 *     {@code ewnsud}, mapping each output slot to an input ordinate and a sign.</li>
 * </ul>
 *
 * <p>The forward and inverse differ in <em>which side the permutation indexes</em>:
 * forward is {@code out[i] = in[axis[i]] * sign[i]}, inverse is
 * {@code out[axis[i]] = in[i] * sign[i]}. They coincide for a self-inverse
 * permutation such as a plain {@code x/y} swap, which is why getting it backwards
 * survives the obvious test.
 *
 * <p>Duplicate axes are rejected. Upstream fills the unspecified slots with the
 * sentinels 4..7 first so that the duplicate scan can run over all four positions
 * without special-casing a 2- or 3-ordinate order; the same trick is used here.
 *
 * <p>Both unit sides are {@link GieIoUnits#WHATEVER} unless {@code +angularunits}
 * is given, in which case both become {@code RADIANS}. Preparation and
 * finalisation are skipped entirely — the whole point of the operator is to run
 * <em>outside</em> the offset/scale machinery.
 */
final class AxisSwapOperator implements PipelineOperator {

    /** Sentinel base for unspecified slots, so duplicates can be found in one scan. */
    private static final int UNUSED_BASE = 4;

    private final int[] axis = new int[4];
    private final int[] sign = new int[4];
    private final int n;
    private final String description;

    private GieIoUnits left;
    private GieIoUnits right;

    AxisSwapOperator(final ProjParams params) {
        final boolean hasOrder = params.has("order");
        final boolean hasAxis = params.has("axis");
        if (hasOrder == hasAxis) {
            throw new PipelineDefinitionException(PipelineErrorCode.MUTUALLY_EXCLUSIVE_ARGS,
                    "axisswap: must provide EITHER 'order' OR 'axis' parameter");
        }

        for (int i = 0; i < 4; i++) {
            axis[i] = i + UNUSED_BASE;
            sign[i] = 1;
        }

        if (hasOrder) {
            n = readOrder(params.value("order"));
            description = "axisswap order=" + params.value("order");
        } else {
            n = readAxis(params.value("axis"));
            description = "axisswap axis=" + params.value("axis");
        }

        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                if (i != j && axis[i] == axis[j]) {
                    throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                            "axisswap: duplicate axes specified");
                }
            }
        }

        if (!isRepresentable()) {
            throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                    "axisswap: bad axis order");
        }

        final boolean angular = params.booleanValue("angularunits");
        this.left = angular ? GieIoUnits.RADIANS : GieIoUnits.WHATEVER;
        this.right = angular ? GieIoUnits.RADIANS : GieIoUnits.WHATEVER;
    }

    /**
     * {@code axisswap.cpp:186-208}. Note {@code abs(atoi(s)) - 1} and
     * {@code sign(atoi(s))}, so {@code -0} is index -1 and rejected by the
     * unsigned {@code > 3} test upstream; here the equivalent range test is
     * explicit.
     */
    private int readOrder(final String order) {
        if (order == null || order.isEmpty()) {
            throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                    "axisswap: empty +order");
        }
        for (int i = 0; i < order.length(); i++) {
            if ("1234-,".indexOf(order.charAt(i)) < 0) {
                throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                        "axisswap: unknown axis '" + order.charAt(i) + "'");
            }
        }
        int count = 0;
        int i = 0;
        while (i < order.length() && count < 4) {
            final int start = i;
            while (i < order.length() && order.charAt(i) != ',') {
                i++;
            }
            final String field = order.substring(start, i);
            final int value = atoi(field);
            final int index = Math.abs(value) - 1;
            if (index < 0 || index > 3) {
                throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                        "axisswap: invalid axis '" + index + "'");
            }
            axis[count] = index;
            sign[count] = value > 0 ? 1 : value < 0 ? -1 : 0;
            count++;
            if (i < order.length() && order.charAt(i) == ',') {
                i++;
            }
        }
        return count;
    }

    /** C's {@code atoi}: leading sign, then digits, stopping at the first non-digit. */
    private static int atoi(final String s) {
        int i = 0;
        boolean negative = false;
        if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
            negative = s.charAt(i) == '-';
            i++;
        }
        int value = 0;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            value = value * 10 + (s.charAt(i) - '0');
            i++;
        }
        return negative ? -value : value;
    }

    /** {@code axisswap.cpp:211-247}: the classic three-letter {@code ewnsud} form. */
    private int readAxis(final String spec) {
        if (spec == null || spec.length() < 3) {
            throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                    "axisswap: +axis must be three characters from \"ewnsud\", got " + spec);
        }
        for (int i = 0; i < 3; i++) {
            switch (spec.charAt(i)) {
                case 'w':
                    sign[i] = -1;
                    axis[i] = 0;
                    break;
                case 'e':
                    sign[i] = 1;
                    axis[i] = 0;
                    break;
                case 's':
                    sign[i] = -1;
                    axis[i] = 1;
                    break;
                case 'n':
                    sign[i] = 1;
                    axis[i] = 1;
                    break;
                case 'd':
                    sign[i] = -1;
                    axis[i] = 2;
                    break;
                case 'u':
                    sign[i] = 1;
                    axis[i] = 2;
                    break;
                default:
                    throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                            "axisswap: unknown axis '" + spec.charAt(i) + "'");
            }
        }
        return 3;
    }

    /**
     * {@code axisswap.cpp:259-277}: upstream only wires up the fwd/inv function
     * pointers for orders it can actually execute, and errors out when none
     * matched. A 2-ordinate order touching {@code z} or {@code t} is the case that
     * fails.
     */
    private boolean isRepresentable() {
        if (n == 4) {
            return true;
        }
        if (n == 3) {
            return axis[0] < 3 && axis[1] < 3 && axis[2] < 3;
        }
        if (n == 2) {
            return axis[0] < 2 && axis[1] < 2;
        }
        return false;
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
        final double[] in = {coord[0], coord[1], coord[2], coord[3]};
        for (int i = 0; i < n; i++) {
            coord[i] = in[axis[i]] * sign[i];
        }
    }

    @Override
    public void inverse(final double[] coord) {
        final double[] in = {coord[0], coord[1], coord[2], coord[3]};
        for (int i = 0; i < n; i++) {
            coord[axis[i]] = in[i] * sign[i];
        }
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
        return "AxisSwapOperator[" + description + "]";
    }
}

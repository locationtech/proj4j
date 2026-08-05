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
package org.locationtech.proj4j.benchmark.counting;

/**
 * The tally behind Tier 2. A flat {@code long[]} indexed by {@link Op#ordinal()}.
 *
 * <p>Plain (non-{@code volatile}, non-atomic) counters, on purpose. The counting run is
 * single-threaded by construction - {@link OpCountRecorder} performs exactly one transform between a
 * reset and a snapshot - and an {@code AtomicLong} per operation would add a contended CAS to every
 * transcendental, which would not change the counts but would make the instrumented run slow enough
 * that people stop running it. <b>Never call this from a multi-threaded benchmark;</b> the numbers
 * would be lossy and Tier 2 would flake, which is precisely the property it exists not to have.
 */
public final class OpCounters {

    /**
     * The counted operations.
     *
     * <p>The first ten are the set {@code reference/performance.md} names for Tier 2:
     * {@code sin/cos/tan/pow/exp/log/atan/atan2/sqrt/hypot}. The rest are counted as well because
     * they are equally deterministic and cost nothing extra to tally, and because two of them matter
     * for specific rows of {@code reference/numerics.md}: {@code log1p} and {@code sinh} are what the
     * {@code tsfn} and {@code ConformalLat} rewrites trade {@code pow} and {@code tan} <i>for</i>, so a
     * gate that counted only the ten would see the saving and not the cost.
     *
     * <p><b>Ordinal order is the wire format.</b> {@code baseline/op-counts.json} is keyed by name,
     * not position, so inserting a constant in the middle is safe for the JSON - but do it at the end
     * anyway, so that a diff of the baseline file stays readable.
     */
    public enum Op {
        // The ten named in reference/performance.md.
        SIN, COS, TAN, POW, EXP, LOG, ATAN, ATAN2, SQRT, HYPOT,
        // Equally deterministic, and load-bearing for the numerics.md rewrites.
        ASIN, ACOS, LOG10, SINH, COSH, LOG1P, CBRT, EXPM1, TANH;

        static final Op[] VALUES = values();
    }

    static final long[] COUNTS = new long[Op.VALUES.length];

    private OpCounters() {
    }

    static void bump(Op op) {
        COUNTS[op.ordinal()]++;
    }

    /** Zeroes every counter. Call immediately before the single transform being measured. */
    public static void reset() {
        java.util.Arrays.fill(COUNTS, 0L);
    }

    /** A defensive copy of the current tallies, indexed by {@link Op#ordinal()}. */
    public static long[] snapshot() {
        return COUNTS.clone();
    }

    public static long get(Op op) {
        return COUNTS[op.ordinal()];
    }

    /** Sum of all counters. Used to detect a silently ineffective bytecode rewrite. */
    public static long total() {
        long sum = 0;
        for (long c : COUNTS) {
            sum += c;
        }
        return sum;
    }

    public static int opCount() {
        return Op.VALUES.length;
    }

    public static Op op(int ordinal) {
        return Op.VALUES[ordinal];
    }

    /** Lower-case name, which is the key used in {@code baseline/op-counts.json}. */
    public static String key(Op op) {
        return op.name().toLowerCase(java.util.Locale.ROOT);
    }
}

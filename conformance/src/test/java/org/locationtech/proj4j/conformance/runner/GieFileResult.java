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
package org.locationtech.proj4j.conformance.runner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.locationtech.proj4j.conformance.manifest.AssertionOutcome;
import org.locationtech.proj4j.conformance.manifest.ObservedRun;

/**
 * Everything one {@code .gie} file produced: its assertions in source order, plus the two
 * file-level facts that are not assertions.
 *
 * <p>Immutable.
 */
public final class GieFileResult {

    private final String filePath;
    private final List<GieAssertionResult> assertions;
    private final int operationBlocks;
    private final int leakedCrsDstFlagAssertions;

    GieFileResult(
            String filePath,
            List<GieAssertionResult> assertions,
            int operationBlocks,
            int leakedCrsDstFlagAssertions) {
        this.filePath = filePath;
        this.assertions =
                Collections.unmodifiableList(new ArrayList<GieAssertionResult>(assertions));
        this.operationBlocks = operationBlocks;
        this.leakedCrsDstFlagAssertions = leakedCrsDstFlagAssertions;
    }

    /** @return the corpus-relative path, e.g. {@code gie/builtins.gie}. */
    public String filePath() {
        return filePath;
    }

    /** @return the assertions, in source order. Unmodifiable. */
    public List<GieAssertionResult> assertions() {
        return assertions;
    }

    /** @return how many operation blocks the file opened. */
    public int operationBlocks() {
        return operationBlocks;
    }

    /**
     * How many assertions were evaluated under a plain {@code operation} while
     * {@code crs_dst_is_lat_lon_or_y_x} was still set from an earlier {@code crs_src}/{@code crs_dst}
     * pair.
     *
     * <p>gie does not reset that flag in {@code operation()} ({@code gie.cpp:627-660}), so it leaks —
     * and the metric it perturbs is a lat/lon axis swap, which silently turns a 55.8 km deviation into
     * a 110.6 km one. No file in the 9.8.1 corpus mixes the two styles, so this should always be
     * zero; it is counted rather than assumed, because if it ever stops being zero the resulting
     * failures would look like a projection defect.
     *
     * @return the count, expected to be 0
     */
    public int leakedCrsDstFlagAssertions() {
        return leakedCrsDstFlagAssertions;
    }

    /**
     * @param outcome the outcome to count
     * @return how many assertions had it
     */
    public int count(AssertionOutcome outcome) {
        int n = 0;
        for (int i = 0; i < assertions.size(); i++) {
            if (assertions.get(i).outcome() == outcome) {
                n++;
            }
        }
        return n;
    }

    /**
     * @param verdict the {@code expect failure} verdict to count
     * @return how many of this file's {@code expect failure} rows got it
     */
    public int count(ExpectedFailureVerdict verdict) {
        int n = 0;
        for (int i = 0; i < assertions.size(); i++) {
            if (assertions.get(i).expectedFailureVerdict() == verdict) {
                n++;
            }
        }
        return n;
    }

    /** @return how many of this file's assertions were {@code expect failure} rows. */
    public int expectedFailureRows() {
        int n = 0;
        for (int i = 0; i < assertions.size(); i++) {
            if (assertions.get(i).isExpectedFailureRow()) {
                n++;
            }
        }
        return n;
    }

    /** @return the number of assertions evaluated. */
    public int total() {
        return assertions.size();
    }

    /**
     * Adds every assertion to a run under construction.
     *
     * @param builder the accumulator
     * @return {@code builder}
     */
    public ObservedRun.Builder recordInto(ObservedRun.Builder builder) {
        for (int i = 0; i < assertions.size(); i++) {
            builder.record(assertions.get(i).observed());
        }
        return builder;
    }

    /**
     * @return {@code "gie/x.gie: 12 pass, 1 fail, 0 skip, 0 vacuous"} — four numbers, always, because a
     *     summary that hides the vacuous count is the defect this class exists to have fixed
     */
    public String summary() {
        return filePath + ": " + count(AssertionOutcome.PASS) + " pass, " + count(AssertionOutcome.FAIL)
                + " fail, " + count(AssertionOutcome.SKIP) + " skip, "
                + count(AssertionOutcome.VACUOUS_EXPECTED_FAILURE) + " vacuous";
    }

    @Override
    public String toString() {
        return "GieFileResult[" + summary() + "]";
    }
}

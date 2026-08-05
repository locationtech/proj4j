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
 *******************************************************************************/
package org.locationtech.proj4j.spi;

/**
 * One step of a concatenated operation.
 * <p>
 * {@link #direction()} is upstream's own extension to OGC Topic 2 and is not decorative: a step
 * declared {@code REVERSE} must be executed inverted. Upstream's schema comment notes that if the
 * direction is set on one step it is set on all steps of that operation, so
 * {@link Direction#UNSPECIFIED} is an all-or-nothing property of the parent operation.
 */
public final class DbOperationStep {

    public enum Direction {
        FORWARD("forward"), REVERSE("reverse"),
        /** Upstream stored NULL: the direction follows from chaining source and target CRSs. */
        UNSPECIFIED(null);

        private final String dbValue;

        Direction(String dbValue) {
            this.dbValue = dbValue;
        }

        public String dbValue() {
            return dbValue;
        }

        public static Direction fromDbValue(String v) {
            if ("forward".equals(v)) {
                return FORWARD;
            }
            if ("reverse".equals(v)) {
                return REVERSE;
            }
            return UNSPECIFIED;
        }
    }

    private final int stepNumber;
    private final DbObjectRef step;
    private final Direction direction;

    public DbOperationStep(int stepNumber, DbObjectRef step, Direction direction) {
        this.stepNumber = stepNumber;
        this.step = step;
        this.direction = direction == null ? Direction.UNSPECIFIED : direction;
    }

    /** 1-based. */
    public int stepNumber() {
        return stepNumber;
    }

    /**
     * The referenced operation. Its {@link DbObjectRef#type()} is the concrete operation type, so a
     * caller can dispatch without a second lookup — although the step's own parameters do require
     * {@link ProjDatabase#operation}.
     */
    public DbObjectRef step() {
        return step;
    }

    public Direction direction() {
        return direction;
    }

    @Override
    public String toString() {
        return stepNumber + ": " + step + " " + direction;
    }
}

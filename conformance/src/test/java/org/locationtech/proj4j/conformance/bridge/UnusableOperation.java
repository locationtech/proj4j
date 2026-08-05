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

/**
 * An operation the bridge classified rather than built. {@link #transform} always
 * returns {@code null} and reports the same construction failure, so a runner that
 * calls it anyway cannot mistake the outcome for a coordinate.
 */
final class UnusableOperation implements GieOperation {

    private final GieFailure failure;
    private final GieIoUnits left;
    private final GieIoUnits right;

    UnusableOperation(GieFailure failure) {
        this(failure, GieIoUnits.RADIANS, GieIoUnits.CLASSIC);
    }

    UnusableOperation(GieFailure failure, GieIoUnits left, GieIoUnits right) {
        if (failure == null) {
            throw new IllegalArgumentException("an unusable operation must carry a failure");
        }
        this.failure = failure;
        this.left = left;
        this.right = right;
    }

    @Override
    public boolean isUsable() {
        return false;
    }

    @Override
    public GieFailure failure() {
        return failure;
    }

    @Override
    public GieIoUnits leftUnits() {
        return left;
    }

    @Override
    public GieIoUnits rightUnits() {
        return right;
    }

    @Override
    public boolean isInverted() {
        return false;
    }

    @Override
    public boolean crsDstIsLatLonOrYX() {
        return false;
    }

    @Override
    public double[] transform(double[] in, GieDirection dir) {
        return null;
    }

    @Override
    public GieFailure lastFailure() {
        return failure;
    }

    @Override
    public String toString() {
        return "UnusableOperation[" + failure + "]";
    }
}

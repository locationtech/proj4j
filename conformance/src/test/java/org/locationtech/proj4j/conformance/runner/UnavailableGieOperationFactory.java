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

import org.locationtech.proj4j.conformance.bridge.GieFailure;
import org.locationtech.proj4j.conformance.bridge.GieFailureKind;
import org.locationtech.proj4j.conformance.bridge.GieOperation;
import org.locationtech.proj4j.conformance.bridge.GieOperationFactory;
import org.locationtech.proj4j.gie.GieDirection;
import org.locationtech.proj4j.gie.GieIoUnits;

/**
 * A factory that creates nothing: every definition fails with {@link GieFailureKind#NOT_IMPLEMENTED}.
 *
 * <p>This exists so that the state machine and its wiring can be exercised end to end before the
 * proj4j bridge is available. <strong>A sweep run against it produces no conformance number worth
 * quoting</strong> — the only assertions that pass are the 1,187 {@code expect failure} lines, which
 * pass for the wrong reason (nothing was built, so nothing could succeed). {@link GieConformanceTest}
 * says so on stdout when it falls back to this.
 *
 * <p>It is not a "null object" used to paper over a missing dependency at runtime: production code has
 * no dependency to paper over, because this module produces no artifact.
 */
public final class UnavailableGieOperationFactory implements GieOperationFactory {

    /** The shared instance. */
    public static final UnavailableGieOperationFactory INSTANCE = new UnavailableGieOperationFactory();

    /** Public for reflective construction via the {@code gie.operation.factory} property. */
    public UnavailableGieOperationFactory() {}

    @Override
    public GieOperation create(String args) {
        return new Unusable("no GieOperationFactory is wired: cannot create \"" + args + "\"");
    }

    @Override
    public GieOperation createCrsToCrs(String sourceCrs, String targetCrs) {
        return new Unusable(
                "no GieOperationFactory is wired: cannot create " + sourceCrs + " -> " + targetCrs);
    }

    @Override
    public String toString() {
        return "UnavailableGieOperationFactory";
    }

    /** An operation that was never built. */
    private static final class Unusable implements GieOperation, GieFailure {

        private final String message;

        Unusable(String message) {
            this.message = message;
        }

        @Override
        public boolean isUsable() {
            return false;
        }

        @Override
        public GieFailure failure() {
            return this;
        }

        @Override
        public GieIoUnits leftUnits() {
            return GieIoUnits.RADIANS;
        }

        @Override
        public GieIoUnits rightUnits() {
            return GieIoUnits.CLASSIC;
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
            return this;
        }

        @Override
        public GieFailureKind kind() {
            return GieFailureKind.NOT_IMPLEMENTED;
        }

        @Override
        public String message() {
            return message;
        }

        @Override
        public Throwable cause() {
            return null;
        }
    }
}

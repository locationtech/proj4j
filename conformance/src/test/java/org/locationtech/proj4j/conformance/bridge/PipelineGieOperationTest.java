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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.proj4j.gie.GieDirection;
import org.locationtech.proj4j.gie.GieIoUnits;

/**
 * The pipeline half of the bridge: that {@code GieOperationFactory.create} returns a
 * usable operation for a {@code +proj=pipeline}, that its i/o units are the ones the
 * comparator needs, and that the failure classification still distinguishes
 * "upstream rejects this too" from "we have not implemented it".
 */
class PipelineGieOperationTest {

    private final GieOperationFactory factory = new Proj4jGieOperationFactory();

    private static double rad(double degrees) {
        return degrees * Math.PI / 180.0;
    }

    // ---------------------------------------------------------------- usable

    @Test
    @DisplayName("gigs/5208.gie's pipeline is executable and correct")
    void aGigsPipelineIsExecutable() {
        GieOperation op = factory.create("+proj=pipeline"
                + " +step +init=epsg:4275 +inv"
                + " +step +init=epsg:4807");
        assertTrue(op.isUsable(), () -> "not usable: " + op.failure());
        assertNull(op.failure());

        double[] out = op.transform(new double[] {rad(5), rad(58), 0, 0}, GieDirection.FORWARD);
        assertNotNull(out, () -> "transform failed: " + op.lastFailure());
        assertNull(op.lastFailure());
        assertEquals(2.66277083, Math.toDegrees(out[0]), 1e-8);
        assertEquals(58.0, Math.toDegrees(out[1]), 1e-8);
    }

    /**
     * The units the gie comparator branches on. A pipeline's own {@code P->inverted}
     * is always 0 — the {@code +inv} tokens belong to its steps and are already
     * folded into {@code left()} and {@code right()} — so reporting
     * {@code isInverted() == true} here would swap the two sides a second time and
     * silently change the metric by a factor of about 111,319.
     */
    @Test
    @DisplayName("io units come from the pipeline, and isInverted is always false")
    void reportsThePipelinesOwnIoUnits() {
        GieOperation angularToProjected = factory.create("+proj=pipeline"
                + " +step +init=epsg:4326 +inv"
                + " +step +init=epsg:32631");
        assertEquals(GieIoUnits.RADIANS, angularToProjected.leftUnits());
        assertEquals(GieIoUnits.PROJECTED, angularToProjected.rightUnits());
        assertFalse(angularToProjected.isInverted());

        GieOperation projectedToAngular = factory.create("+proj=pipeline"
                + " +step +init=epsg:32631 +inv"
                + " +step +init=epsg:4326");
        assertEquals(GieIoUnits.PROJECTED, projectedToAngular.leftUnits());
        assertEquals(GieIoUnits.RADIANS, projectedToAngular.rightUnits());
        assertFalse(projectedToAngular.isInverted());

        // gigs/5201.gie: the left-hand side is cartesian, not angular, so the runner
        // must NOT convert the accepted coordinate from degrees.
        GieOperation geocentric = factory.create("+proj=pipeline"
                + " +step +init=epsg:4978 +inv"
                + " +step +init=epsg:4326");
        assertEquals(GieIoUnits.CARTESIAN, geocentric.leftUnits());
        assertEquals(GieIoUnits.RADIANS, geocentric.rightUnits());
    }

    /**
     * {@code gigs/5102.2.gie}'s reverse pipeline. {@code grad} normalises to
     * {@code "Grad"}, so the right-hand side stays {@link GieIoUnits#WHATEVER} and the
     * comparator uses its Euclidean branch on grad values against a tolerance written
     * in metres. Faithful reproduction, not a defect to fix.
     */
    @Test
    @DisplayName("a trailing +xy_out=grad leaves the right-hand side WHATEVER")
    void gradLeavesTheUnitDomainUnraised() {
        GieOperation op = factory.create("+proj=pipeline"
                + " +step +init=epsg:27572 +inv"
                + " +step +init=epsg:4807"
                + " +step +proj=unitconvert +xy_in=rad +xy_out=grad");
        assertTrue(op.isUsable(), () -> "not usable: " + op.failure());
        assertEquals(GieIoUnits.PROJECTED, op.leftUnits());
        assertEquals(GieIoUnits.WHATEVER, op.rightUnits());
    }

    @Test
    @DisplayName("a bare +init= is executable too")
    void aBareLegacyInitIsExecutable() {
        GieOperation op = factory.create("+init=epsg:32631");
        assertTrue(op.isUsable(), () -> "not usable: " + op.failure());
        double[] out = op.transform(new double[] {rad(3), rad(0), 0, 0}, GieDirection.FORWARD);
        assertNotNull(out, () -> "transform failed: " + op.lastFailure());
        assertEquals(500000.0, out[0], 1e-3);
    }

    // ------------------------------------------------------- classification

    @Test
    @DisplayName("a nested pipeline is INVALID_DEFINITION, because PROJ rejects it too")
    void aNestedPipelineIsInvalidUpstream() {
        GieOperation op = factory.create("+proj=pipeline +step +proj=pipeline +step +proj=noop");
        assertFalse(op.isUsable());
        assertEquals(GieFailureKind.INVALID_DEFINITION, op.failure().kind());
    }

    /**
     * An operator PROJ has and proj4j does not must be {@code NOT_IMPLEMENTED}, never
     * {@code INVALID_DEFINITION}: an {@code expect failure} row satisfied by our own
     * capability gap demonstrates nothing, which is the distinction the whole bridge
     * is shaped around.
     */
    @Test
    @DisplayName("an unimplemented operator step is NOT_IMPLEMENTED, not INVALID_DEFINITION")
    void anUnimplementedStepIsOurGapNotUpstreams() {
        GieOperation op = factory.create(
                "+proj=pipeline +step +proj=cart +ellps=GRS80 +step +proj=helmert +x=1");
        assertFalse(op.isUsable());
        assertEquals(GieFailureKind.NOT_IMPLEMENTED, op.failure().kind());
    }

    @Test
    @DisplayName("an unknown +init= section is a file error, not a silent success")
    void anUnknownInitSectionFailsClosed() {
        GieOperation op = factory.create("+proj=pipeline +step +init=epsg:999999999");
        assertFalse(op.isUsable());
        assertNotNull(op.failure());
    }

    @Test
    @DisplayName("the factory never throws, whatever it is fed")
    void theFactoryNeverThrows() {
        for (String definition : new String[] {
            "+proj=pipeline",
            "+step",
            "+proj=pipeline +step",
            "+proj=pipeline +step +proj=",
            "+proj=pipeline +step +proj=axisswap",
            "+proj=pipeline +step +proj=axisswap +order=1,1",
            "+proj=pipeline +step +proj=unitconvert +xy_in=furlong +xy_out=m",
            "+init=",
            "+init=nosuchfile:1",
        }) {
            GieOperation op = factory.create(definition);
            assertNotNull(op, definition);
            if (!op.isUsable()) {
                assertNotNull(op.failure(), definition + " unusable without a failure");
            }
        }
    }
}

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

import org.junit.Test;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.datum.Ellipsoid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code pj_ell_set}'s resolution order, for the operators that are not projections.
 *
 * <p>The order is the whole content of {@link StepEllipsoid} and each row below is a
 * separate branch of {@code 9.8.1:src/ell_set.cpp:92-133}. The two that are easy to get
 * wrong, and that this project has already paid for once:
 *
 * <ul>
 * <li><b>{@code +ellps} plus a shape parameter is not a contradiction.</b>
 *     {@code ell_set.cpp}'s own comment calls later parameters "modifiers for the built
 *     in ellipsoid definition ... in accordance with historical PROJ behavior", and there
 *     is no contradiction check anywhere in the file. Rejecting
 *     {@code +ellps=GRS80 +rf=300} is stricter than PROJ.</li>
 * <li><b>{@code +R} wins outright and silences every shape parameter</b>, rather than
 *     merging with them.</li>
 * </ul>
 */
public class StepEllipsoidTest {

    private static final Registry REGISTRY = new Registry();

    private static double[] resolve(String definition) {
        return StepEllipsoid.resolve(REGISTRY, ProjParams.parse(definition));
    }

    /**
     * Both values come from {@link Registry}'s own {@link Ellipsoid}, asserted against it
     * rather than against a transcribed literal: what is under test here is the
     * <em>routing</em> — that {@code +ellps} is looked up at all, and through the
     * registry — not the constant, which belongs to the numerical core and is pinned by
     * its own tests.
     */
    @Test
    public void ellpsSeedsBothSizeAndShape() {
        double[] e = resolve("proj=cart ellps=GRS80");
        Ellipsoid grs80 = REGISTRY.getEllipsoid("GRS80");
        assertEquals(grs80.getEquatorRadius(), e[0], 0.0);
        assertEquals(grs80.getEccentricitySquared(), e[1], 0.0);
    }

    /** {@code ellps_size} runs after {@code ellps_ellps}, so {@code +a} overrides the size. */
    @Test
    public void explicitMajorAxisOverridesTheEllpsSize() {
        double[] e = resolve("proj=cart ellps=GRS80 a=1");
        assertEquals(1.0, e[0], 0.0);
        assertEquals("the shape survives a size-only override",
                REGISTRY.getEllipsoid("GRS80").getEccentricitySquared(), e[1], 0.0);
    }

    /** Documented modifier behaviour, not a contradiction. {@code es = 2f - f*f} with {@code f = 1/300}. */
    @Test
    public void ellpsPlusRfIsAModifierNotAContradiction() {
        double[] e = resolve("proj=cart ellps=GRS80 rf=300");
        assertEquals(6378137.0, e[0], 0.0);
        double f = 1.0 / 300.0;
        assertEquals(2 * f - f * f, e[1], 0.0);
    }

    /** {@code ellps_shape} takes the FIRST present of rf, f, es, e, b — in that order. */
    @Test
    public void firstShapeParameterInEllSetsOrderWins() {
        double f = 1.0 / 300.0;
        // rf precedes f, es, e and b, whichever order they appear in the token list.
        assertEquals(2 * f - f * f, resolve("proj=cart a=6378137 f=0.5 rf=300")[1], 0.0);
        assertEquals(2 * f - f * f, resolve("proj=cart a=6378137 es=0.5 rf=300")[1], 0.0);
        assertEquals(0.25, resolve("proj=cart a=6378137 e=0.5")[1], 1e-17);
    }

    /** {@code +b} goes via the flattening in two steps, not {@code es = 1 - b*b/(a*a)}. */
    @Test
    public void semiMinorAxisGoesViaTheFlattening() {
        double a = 6378137.0;
        double b = 6356752.314140356;
        double f = (a - b) / a;
        assertEquals(2 * f - f * f, resolve("proj=cart a=" + a + " b=" + b)[1], 0.0);
    }

    /** {@code +R} short-circuits: a sphere, and every shape parameter is ignored. */
    @Test
    public void radiusWinsAndSilencesEveryShapeParameter() {
        double[] e = resolve("proj=cart R=6400000 ellps=GRS80 rf=300 es=0.5");
        assertEquals(6400000.0, e[0], 0.0);
        assertEquals(0.0, e[1], 0.0);
    }

    @Test
    public void unknownEllpsIsRejected() {
        assertRejected("proj=cart ellps=nosuchellipsoid",
                PipelineErrorCode.ILLEGAL_ARG_VALUE, "unknown +ellps");
    }

    @Test
    public void noEllipsoidAtAllIsRejected() {
        assertRejected("proj=cart", PipelineErrorCode.MISSING_ARG, "no ellipsoid");
    }

    @Test
    public void impossibleEccentricityIsRejected() {
        assertRejected("proj=cart a=6378137 es=1.5",
                PipelineErrorCode.ILLEGAL_ARG_VALUE, "invalid eccentricity");
    }

    @Test
    public void nonPositiveInverseFlatteningIsRejected() {
        assertRejected("proj=cart a=6378137 rf=0",
                PipelineErrorCode.ILLEGAL_ARG_VALUE, "+rf must be positive");
    }

    /**
     * Spherification is refused rather than ignored: ignoring {@code +R_A} would change
     * the radius by up to the flattening — kilometres — while reporting success.
     */
    @Test
    public void spherificationIsRefusedNotIgnored() {
        assertRejected("proj=cart ellps=GRS80 R_A",
                PipelineErrorCode.NOT_IMPLEMENTED_HERE, "spherification");
        assertRejected("proj=cart ellps=GRS80 R_C",
                PipelineErrorCode.NOT_IMPLEMENTED_HERE, "spherification");
    }

    private static void assertRejected(String definition, PipelineErrorCode expected,
                                       String messageFragment) {
        try {
            resolve(definition);
            fail("expected a PipelineDefinitionException for: " + definition);
        } catch (PipelineDefinitionException e) {
            assertEquals(definition, expected, e.code());
            assertTrue("message was: " + e.getMessage(),
                    e.getMessage().contains(messageFragment));
        }
    }
}

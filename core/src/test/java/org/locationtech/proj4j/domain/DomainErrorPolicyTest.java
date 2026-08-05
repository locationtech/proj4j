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
package org.locationtech.proj4j.domain;

import org.junit.Test;
import org.locationtech.proj4j.BasicCoordinateTransform;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.DomainErrorPolicy;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link DomainErrorPolicy}: the documented escape for callers that were depending on 1.4.3's
 * silence.
 *
 * <h2>This is not a way to get 1.4.3 back, and that is deliberate</h2>
 *
 * <p>1.4.3 expressed a per-coordinate failure four different ways — the input unchanged, a single
 * {@code NaN} ordinate, a pole, and the target projection's false easting/northing. A caller's
 * {@code isFinite} guard can see exactly one of those. {@link DomainErrorPolicy#RETURN_NAN}
 * replaces all four with {@code NaN} in <em>every</em> ordinate, so the guard that used to be
 * insufficient becomes sufficient. Migrating to it is a strict improvement even for a caller that
 * never touches the exception.
 */
public class DomainErrorPolicyTest {

    private final CRSFactory csFactory = new CRSFactory();

    private CoordinateReferenceSystem wgs84() {
        return csFactory.createFromName("EPSG:4326");
    }

    private CoordinateReferenceSystem merc() {
        return csFactory.createFromParameters("merc",
                "+proj=merc +ellps=WGS84 +x_0=500000 +y_0=10000000 +units=m +no_defs");
    }

    // ------------------------------------------------------------------------- defaults

    /** Strict by default, on both the factory and the transform. */
    @Test
    public void throwIsTheDefaultEverywhere() {
        assertSame(DomainErrorPolicy.THROW,
                new CoordinateTransformFactory().getDomainErrorPolicy());
        assertSame(DomainErrorPolicy.THROW,
                new BasicCoordinateTransform(wgs84(), merc()).getDomainErrorPolicy());
        // A null policy is normalised rather than stored, so getDomainErrorPolicy() never returns
        // null and no call site needs a null check.
        assertSame(DomainErrorPolicy.THROW,
                new CoordinateTransformFactory(null).getDomainErrorPolicy());
        assertSame(DomainErrorPolicy.THROW,
                new BasicCoordinateTransform(wgs84(), merc(), null).getDomainErrorPolicy());
    }

    /** The factory's policy reaches the transforms it makes. */
    @Test
    public void theFactoryPolicyPropagatesToItsTransforms() {
        CoordinateTransformFactory lenient =
                new CoordinateTransformFactory(DomainErrorPolicy.RETURN_NAN);
        assertSame(DomainErrorPolicy.RETURN_NAN, lenient.getDomainErrorPolicy());
        CoordinateTransform t = lenient.createTransform(wgs84(), merc());
        assertSame(DomainErrorPolicy.RETURN_NAN,
                ((BasicCoordinateTransform) t).getDomainErrorPolicy());
    }

    // --------------------------------------------------------------------------- strict

    /** Strict mode raises, with the cause attached. */
    @Test
    public void strictModeRaisesWithACause() {
        CoordinateTransform t = new CoordinateTransformFactory().createTransform(wgs84(), merc());
        try {
            fail("must raise, got " + t.transform(new ProjCoordinate(10.0, 90.000001),
                    new ProjCoordinate()));
        } catch (CrsTransformException e) {
            assertEquals(ErrorCause.INVALID_COORDINATE, e.cause());
            assertTrue(e.cause().isCoordinateError());
        }
    }

    // -------------------------------------------------------------------------- lenient

    /**
     * Lenient mode writes {@code NaN} into <b>every</b> ordinate, not one. A coordinate with one
     * finite and one {@code NaN} ordinate is precisely the shape that survives a careless range
     * check, so producing it would defeat the point of the mode.
     */
    @Test
    public void lenientModeReturnsNaNInEveryOrdinate() {
        CoordinateTransform t = new CoordinateTransformFactory(DomainErrorPolicy.RETURN_NAN)
                .createTransform(wgs84(), merc());
        ProjCoordinate out = t.transform(new ProjCoordinate(10.0, 90.000001, 5.0),
                new ProjCoordinate(1e300, 1e300, 1e300));
        assertTrue("x must be NaN: " + out, Double.isNaN(out.x));
        assertTrue("y must be NaN: " + out, Double.isNaN(out.y));
        assertTrue("z must be NaN too, so a partially transformed height cannot be mistaken for a "
                + "result: " + out, Double.isNaN(out.z));
        assertTrue(!out.hasValidXandYOrdinates());
    }

    /**
     * <b>The false easting can never be the answer.</b> This target has
     * {@code +x_0=500000 +y_0=10000000}, which is exactly the pair 1.4.3 could emit when a kernel
     * produced {@code NaN} and {@code totalScale} was zero. Under either policy, those two numbers
     * must never appear.
     */
    @Test
    public void theFalseEastingIsNeverTheAnswerUnderEitherPolicy() {
        ProjCoordinate lenient = new CoordinateTransformFactory(DomainErrorPolicy.RETURN_NAN)
                .createTransform(wgs84(), merc())
                .transform(new ProjCoordinate(10.0, 95.0), new ProjCoordinate());
        assertTrue("must not be (x_0, y_0): " + lenient,
                lenient.x != 500000.0 && lenient.y != 10000000.0);

        try {
            ProjCoordinate strict = new CoordinateTransformFactory()
                    .createTransform(wgs84(), merc())
                    .transform(new ProjCoordinate(10.0, 95.0), new ProjCoordinate());
            fail("strict mode must raise, got " + strict);
        } catch (CrsTransformException expected) {
            assertEquals(ErrorCause.INVALID_COORDINATE, expected.cause());
        }
    }

    /** Lenient mode is only about per-coordinate causes; valid input is untouched by it. */
    @Test
    public void lenientModeDoesNotDegradeValidInput() {
        CoordinateTransform lenient = new CoordinateTransformFactory(DomainErrorPolicy.RETURN_NAN)
                .createTransform(wgs84(), merc());
        CoordinateTransform strict = new CoordinateTransformFactory()
                .createTransform(wgs84(), merc());

        ProjCoordinate a = lenient.transform(new ProjCoordinate(10.0, 45.0), new ProjCoordinate());
        ProjCoordinate b = strict.transform(new ProjCoordinate(10.0, 45.0), new ProjCoordinate());
        assertEquals("the two policies must agree exactly on valid input", b.x, a.x, 0.0);
        assertEquals(b.y, a.y, 0.0);
        assertTrue(a.hasValidXandYOrdinates());
    }

    /**
     * The group predicates are what the policy routes on, and exactly one of the four is true for
     * every cause. Only the coordinate group is eligible for {@code RETURN_NAN}.
     */
    @Test
    public void onlyTheCoordinateGroupIsEligible() {
        for (ErrorCause c : ErrorCause.values()) {
            int groups = (c.isCrsError() ? 1 : 0) + (c.isOperationError() ? 1 : 0)
                    + (c.isCoordinateError() ? 1 : 0) + (c.isEnvironmentError() ? 1 : 0);
            assertEquals(c + " must be in exactly one group", 1, groups);
        }
        assertTrue(ErrorCause.INVALID_COORDINATE.isCoordinateError());
        assertTrue(ErrorCause.COORDINATE_OUT_OF_DOMAIN.isCoordinateError());
        assertTrue(ErrorCause.NUMERICAL_FAILURE.isCoordinateError());
        assertTrue(!ErrorCause.NO_INVERSE_AVAILABLE.isCoordinateError());
        assertTrue(!ErrorCause.INVALID_PARAM_VALUE.isCoordinateError());
    }

    /**
     * NaN in, NaN out under <em>both</em> policies, and for the same reason in each: it is a
     * result, not an error, so the policy never gets consulted. Lenient mode's {@code NaN} and
     * this {@code NaN} are indistinguishable to the caller, which is the one respect in which the
     * lenient mode loses information — hence the recommendation to prefer {@code THROW} and catch.
     */
    @Test
    public void nanInNanOutIsAResultUnderBothPolicies() {
        for (DomainErrorPolicy policy
                : new DomainErrorPolicy[] {DomainErrorPolicy.THROW, DomainErrorPolicy.RETURN_NAN}) {
            ProjCoordinate out = new CoordinateTransformFactory(policy)
                    .createTransform(wgs84(), merc())
                    .transform(new ProjCoordinate(Double.NaN, Double.NaN),
                            new ProjCoordinate(1e300, 1e300));
            assertTrue(policy + ": NaN in must be NaN out, never a raise and never the poison: "
                    + out, Double.isNaN(out.x) && Double.isNaN(out.y));
        }
    }
}

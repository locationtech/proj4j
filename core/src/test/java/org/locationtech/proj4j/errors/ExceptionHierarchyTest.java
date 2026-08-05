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
package org.locationtech.proj4j.errors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.ContradictoryParameterException;
import org.locationtech.proj4j.ConvergenceFailureException;
import org.locationtech.proj4j.CrsCreationException;
import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.UnknownAuthorityCodeException;
import org.locationtech.proj4j.UnsupportedParameterException;
import org.locationtech.proj4j.proj.MercatorProjection;

/**
 * The re-parented exception hierarchy: that each relationship holds, that every legacy
 * {@code catch} still fires, and that {@link Proj4jException#cause()} reports the documented
 * {@link ErrorCause} for every subclass.
 *
 * <p>The re-parenting is the one change in this area that could silently break a caller, so it is
 * asserted from three directions: the subtype relations themselves, the "old supertype is still
 * in every chain" property that makes it binary-compatible, and an actual {@code catch} of each
 * pre-existing clause shape.
 */
public class ExceptionHierarchyTest {

    // -------------------------------------------------------------- the shape of the hierarchy

    @Test
    public void crsTransformExceptionSitsUnderProj4jException() {
        assertTrue(Proj4jException.class.isAssignableFrom(CrsTransformException.class));
        assertSame(Proj4jException.class, CrsTransformException.class.getSuperclass());
    }

    @Test
    public void crsCreationExceptionSitsUnderCrsTransformException() {
        assertSame(CrsTransformException.class, CrsCreationException.class.getSuperclass());
    }

    @Test
    public void theThreeDefinitionExceptionsSitUnderCrsCreationException() {
        assertSame(CrsCreationException.class, UnknownAuthorityCodeException.class.getSuperclass());
        assertSame(CrsCreationException.class, InvalidValueException.class.getSuperclass());
        assertSame(CrsCreationException.class, UnsupportedParameterException.class.getSuperclass());
    }

    @Test
    public void contradictoryParameterExceptionSitsUnderInvalidValueException() {
        // The load-bearing relationship: code already catching the natural exception for a bad
        // +rf keeps working when Proj4J starts rejecting the contradiction outright.
        assertSame(InvalidValueException.class,
                ContradictoryParameterException.class.getSuperclass());
    }

    @Test
    public void theTwoRuntimeExceptionsSitUnderCrsTransformException() {
        assertSame(CrsTransformException.class, ProjectionException.class.getSuperclass());
        assertSame(CrsTransformException.class, ConvergenceFailureException.class.getSuperclass());
    }

    @Test
    public void everythingIsStillAnUncheckedException() {
        // Proj4jException has always been a RuntimeException; making any of these checked would
        // be a source break for every caller that does not declare it.
        assertTrue(RuntimeException.class.isAssignableFrom(Proj4jException.class));
        for (Class<?> c : allExceptionClasses()) {
            assertTrue(c.getName() + " must remain unchecked",
                    RuntimeException.class.isAssignableFrom(c));
        }
    }

    /**
     * The binary-compatibility property, stated as the invariant it actually is: for every
     * exception class, {@code Proj4jException} is still somewhere in its superclass chain. That
     * is what makes {@code catch (Proj4jException)} in already-compiled downstream code keep
     * firing on exactly the throws it used to.
     */
    @Test
    public void proj4jExceptionRemainsInEveryChain() {
        for (Class<?> c : allExceptionClasses()) {
            assertTrue(c.getName() + " must remain a Proj4jException",
                    Proj4jException.class.isAssignableFrom(c));
        }
    }

    /**
     * The re-parenting must not have made any pre-existing sibling pair into a
     * subclass/superclass pair: that is the one way a multi-clause {@code catch} in existing
     * code could become a compile error ("already caught") or change which branch runs.
     */
    @Test
    public void noPreExistingSiblingPairBecameNested() {
        Class<?>[] legacy = {
                UnknownAuthorityCodeException.class,
                InvalidValueException.class,
                UnsupportedParameterException.class,
                ProjectionException.class,
                ConvergenceFailureException.class,
        };
        for (Class<?> a : legacy) {
            for (Class<?> b : legacy) {
                if (a != b) {
                    assertFalse(a.getSimpleName() + " must not have become a supertype of "
                            + b.getSimpleName() + ": a catch clause ordering could change",
                            a.isAssignableFrom(b));
                }
            }
        }
    }

    // ------------------------------------------------------------------- catch clauses still fire

    @Test
    public void catchProj4jExceptionStillCatchesEverySubclass() {
        for (Proj4jException e : oneOfEach()) {
            try {
                throw e;
            } catch (Proj4jException caught) {
                assertSame(e, caught);
            }
        }
    }

    @Test
    public void catchCrsTransformExceptionCatchesEveryAttributedSubclass() {
        for (Proj4jException e : oneOfEach()) {
            if (e.getClass() == Proj4jException.class) {
                continue; // the un-attributed base is deliberately outside this net
            }
            try {
                throw e;
            } catch (CrsTransformException caught) {
                assertSame(e, caught);
            }
        }
    }

    @Test
    public void catchInvalidValueExceptionStillCatchesTheContradictionSubclass() {
        try {
            throw new ContradictoryParameterException("+ellps=GRS80 and +rf=300 disagree");
        } catch (InvalidValueException caught) {
            assertEquals(ErrorCause.CONTRADICTORY_PARAMS, caught.cause());
        }
    }

    /**
     * The clause ordering used in {@code EllipsoidParsingTest} — the narrower legacy class first,
     * {@code Proj4jException} second — must still compile and still route the same way.
     */
    @Test
    public void narrowThenBroadCatchOrderingStillRoutesToTheNarrowClause() {
        String route;
        try {
            throw new InvalidValueException("bad value");
        } catch (InvalidValueException e) {
            route = "narrow";
        } catch (Proj4jException e) {
            route = "broad";
        }
        assertEquals("narrow", route);

        try {
            throw new ConvergenceFailureException("did not converge");
        } catch (InvalidValueException e) {
            route = "narrow";
        } catch (Proj4jException e) {
            route = "broad";
        }
        assertEquals("broad", route);
    }

    // ----------------------------------------------------------------------------- cause() values

    @Test
    public void proj4jExceptionDefaultsToInternalError() {
        assertEquals(ErrorCause.INTERNAL_ERROR, new Proj4jException().cause());
        assertEquals(ErrorCause.INTERNAL_ERROR, new Proj4jException("m").cause());
        assertEquals(ErrorCause.INTERNAL_ERROR,
                new Proj4jException("m", new RuntimeException()).cause());
    }

    @Test
    public void unknownAuthorityCodeExceptionReportsUnknownCrs() {
        assertEquals(ErrorCause.UNKNOWN_CRS,
                new UnknownAuthorityCodeException("EPSG:99999").cause());
    }

    @Test
    public void invalidValueExceptionReportsInvalidParamValue() {
        assertEquals(ErrorCause.INVALID_PARAM_VALUE, new InvalidValueException("m").cause());
        assertEquals(ErrorCause.INVALID_PARAM_VALUE,
                new InvalidValueException("m", new RuntimeException()).cause());
    }

    @Test
    public void contradictoryParameterExceptionReportsContradictoryParams() {
        assertEquals(ErrorCause.CONTRADICTORY_PARAMS,
                new ContradictoryParameterException("m").cause());
        assertEquals(ErrorCause.CONTRADICTORY_PARAMS,
                new ContradictoryParameterException("m", new RuntimeException()).cause());
    }

    @Test
    public void unsupportedParameterExceptionReportsProjectionNotImplemented() {
        assertEquals(ErrorCause.PROJECTION_NOT_IMPLEMENTED,
                new UnsupportedParameterException("m").cause());
    }

    @Test
    public void projectionExceptionReportsCoordinateOutOfDomain() {
        assertEquals(ErrorCause.COORDINATE_OUT_OF_DOMAIN, new ProjectionException().cause());
        assertEquals(ErrorCause.COORDINATE_OUT_OF_DOMAIN, new ProjectionException("m").cause());
        assertEquals(ErrorCause.COORDINATE_OUT_OF_DOMAIN,
                new ProjectionException(new MercatorProjection(), "m").cause());
    }

    @Test
    public void convergenceFailureExceptionReportsNumericalFailure() {
        assertEquals(ErrorCause.NUMERICAL_FAILURE, new ConvergenceFailureException("m").cause());
        assertEquals(ErrorCause.NUMERICAL_FAILURE,
                new ConvergenceFailureException(new MercatorProjection(), "m").cause());
    }

    @Test
    public void causeIsNeverNullEvenWhenExplicitlyPassedNull() {
        assertEquals(ErrorCause.INTERNAL_ERROR, new CrsTransformException(null, "m").cause());
        assertEquals(ErrorCause.INTERNAL_ERROR, new CrsCreationException(null, "m").cause());
    }

    @Test
    public void causeIsIndependentOfGetCause() {
        RuntimeException underlying = new RuntimeException("io");
        CrsCreationException e =
                new CrsCreationException(ErrorCause.DATABASE_UNAVAILABLE, "no db", underlying);
        assertEquals(ErrorCause.DATABASE_UNAVAILABLE, e.cause());
        assertSame(underlying, e.getCause());

        // ...and the legacy no-underlying-cause form leaves getCause() null.
        assertNull(new InvalidValueException("m").getCause());
    }

    // ------------------------------------------------------------- preserved legacy constructors

    @Test
    public void projectionExceptionPreservesErr17AndTheProjectionConstructor() {
        assertEquals("non-convergent inverse meridinal dist", ProjectionException.ERR_17);
        MercatorProjection merc = new MercatorProjection();
        ProjectionException e = new ProjectionException(merc, ProjectionException.ERR_17);
        assertEquals(merc.toString() + ": " + ProjectionException.ERR_17, e.getMessage());
    }

    @Test
    public void noArgProjectionExceptionStillHasNoMessage() {
        assertNull(new ProjectionException().getMessage());
    }

    @Test
    public void newlyAddedCauseCarryingConstructorsRefineTheDefault() {
        assertEquals(ErrorCause.MISSING_PARAM,
                new InvalidValueException(ErrorCause.MISSING_PARAM, "m").cause());
        assertEquals(ErrorCause.CRS_TYPE_NOT_SUPPORTED,
                new UnsupportedParameterException(ErrorCause.CRS_TYPE_NOT_SUPPORTED, "m").cause());
        assertEquals(ErrorCause.INVALID_COORDINATE,
                new ProjectionException(ErrorCause.INVALID_COORDINATE, "m").cause());
        assertEquals(ErrorCause.UNKNOWN_CRS,
                new UnknownAuthorityCodeException(ErrorCause.UNKNOWN_CRS, "m").cause());
    }

    @Test
    public void everyAttributedExceptionReportsACauseFromTheRightGroup() {
        for (Proj4jException e : oneOfEach()) {
            ErrorCause cause = e.cause();
            if (e instanceof CrsCreationException) {
                assertTrue(e.getClass().getSimpleName() + " reports " + cause
                                + ", which is neither a CRS nor an operation error",
                        cause.isCrsError() || cause.isOperationError());
            } else if (e instanceof CrsTransformException) {
                assertTrue(e.getClass().getSimpleName() + " reports " + cause
                        + ", which is not a per-coordinate error", cause.isCoordinateError());
            } else {
                assertEquals(ErrorCause.INTERNAL_ERROR, cause);
            }
        }
    }

    // ------------------------------------------------------------------------------------ helpers

    private static Proj4jException[] oneOfEach() {
        return new Proj4jException[] {
                new Proj4jException("base"),
                new CrsTransformException(ErrorCause.NUMERICAL_FAILURE, "transform"),
                new CrsCreationException(ErrorCause.NO_OPERATION_AVAILABLE, "creation"),
                new UnknownAuthorityCodeException("EPSG:99999"),
                new InvalidValueException("bad value"),
                new ContradictoryParameterException("+rf and +f disagree"),
                new UnsupportedParameterException("+unknown"),
                new ProjectionException("out of domain"),
                new ConvergenceFailureException("did not converge"),
        };
    }

    private static Class<?>[] allExceptionClasses() {
        return new Class<?>[] {
                CrsTransformException.class,
                CrsCreationException.class,
                UnknownAuthorityCodeException.class,
                InvalidValueException.class,
                ContradictoryParameterException.class,
                UnsupportedParameterException.class,
                ProjectionException.class,
                ConvergenceFailureException.class,
        };
    }

    /** Guards against an accidental {@code fail()} import removal in a future edit. */
    @Test
    public void sanity() {
        if (oneOfEach().length != 9) {
            fail("the one-of-each fixture must cover every class in the hierarchy");
        }
    }
}

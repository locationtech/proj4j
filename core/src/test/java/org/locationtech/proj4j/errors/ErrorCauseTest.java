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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.junit.Test;
import org.locationtech.proj4j.ErrorCause;

/**
 * The stability contract of {@link ErrorCause}, asserted mechanically.
 * <p>
 * These are not "does the enum compile" tests. Every one of them is a promise made to a
 * downstream consumer that switches on the constant names or persists {@code metricKey()} in a
 * metrics backend: if a rename, a removal, or a key change ever slips in, exactly these
 * assertions are what catches it before it reaches a release.
 */
public class ErrorCauseTest {

    /**
     * The eight names a downstream consumer asked for by name. Spelled out as string literals
     * rather than as enum references on purpose: a rename would still compile if this test used
     * {@code ErrorCause.UNKNOWN_CRS.name()}, and the point is that the <em>identifier</em> is the
     * API.
     */
    private static final String[] REQUIRED_NAMES = {
            "UNKNOWN_CRS",
            "INVALID_CRS_SYNTAX",
            "CONTRADICTORY_PARAMS",
            "PROJECTION_NOT_IMPLEMENTED",
            "NO_OPERATION_AVAILABLE",
            "MISSING_GRID",
            "COORDINATE_OUT_OF_DOMAIN",
            "NUMERICAL_FAILURE",
    };

    @Test
    public void theEightRequestedConstantNamesExistVerbatim() {
        Set<String> present = new HashSet<String>();
        for (ErrorCause c : ErrorCause.values()) {
            present.add(c.name());
        }
        for (String required : REQUIRED_NAMES) {
            assertTrue("ErrorCause." + required + " is API and must not be renamed or removed",
                    present.contains(required));
            // valueOf is the reflective route a caller may use; it must work too.
            assertNotNull(ErrorCause.valueOf(required));
        }
    }

    @Test
    public void hasTwentyFourValues() {
        assertEquals("ErrorCause is a fixed taxonomy; adding a value is a deliberate, "
                        + "documented act and must update this count",
                24, ErrorCause.values().length);
    }

    @Test
    public void everyValueIsInExactlyOneGroup() {
        for (ErrorCause c : ErrorCause.values()) {
            int groups = 0;
            if (c.isCrsError()) groups++;
            if (c.isOperationError()) groups++;
            if (c.isCoordinateError()) groups++;
            if (c.isEnvironmentError()) groups++;
            assertEquals(c.name() + " must be in exactly one group, so that a caller can "
                    + "classify a future constant without a switch", 1, groups);
        }
    }

    @Test
    public void metricKeysAreUniqueLowerCaseAndDotted() {
        Set<String> keys = new HashSet<String>();
        for (ErrorCause c : ErrorCause.values()) {
            String key = c.metricKey();
            assertNotNull(c.name() + " has no metric key", key);
            assertFalse(c.name() + " has an empty metric key", key.isEmpty());
            assertEquals(c.name() + " metric key must be lower case", key.toLowerCase(), key);
            assertTrue(c.name() + " metric key must be <group>.<name>: " + key,
                    key.matches("^(crs|coord|env)\\.[a-z0-9_]+$"));
            assertTrue("metric key collision on " + key, keys.add(key));
        }
        assertEquals(ErrorCause.values().length, keys.size());
    }

    /**
     * {@code metricKey()} itself is a hard-coded ASCII constant on every enum member, so production
     * is locale-safe here. The <em>expectation</em> was not: derived with a no-arg
     * {@code name().toLowerCase()}, under {@code tr_TR} it asked for
     * {@code crs.ınvalıd_crs_syntax} -- dotless i, U+0131 -- and failed against the correct
     * {@code crs.invalid_crs_syntax}. {@code Locale.ROOT} is what "lower-cased" means for an
     * identifier.
     */
    @Test
    public void metricKeyIsTheGroupPrefixPlusTheLowerCasedConstantName() {
        for (ErrorCause c : ErrorCause.values()) {
            String prefix;
            if (c.isCrsError() || c.isOperationError()) {
                prefix = "crs.";
            } else if (c.isCoordinateError()) {
                prefix = "coord.";
            } else {
                prefix = "env.";
            }
            assertEquals("metric key of " + c.name() + " is derivable, and must stay derivable",
                    prefix + c.name().toLowerCase(Locale.ROOT), c.metricKey());
        }
    }

    /** The one metric key spelled out in the architecture document. */
    @Test
    public void ballparkRejectedHasTheDocumentedMetricKey() {
        assertEquals("crs.ballpark_rejected", ErrorCause.BALLPARK_REJECTED.metricKey());
    }

    @Test
    public void crsDefinitionGroupIsExactlyAsSpecified() {
        assertEquals(EnumSet.of(
                ErrorCause.UNKNOWN_CRS,
                ErrorCause.INVALID_CRS_SYNTAX,
                ErrorCause.CONTRADICTORY_PARAMS,
                ErrorCause.MISSING_PARAM,
                ErrorCause.INVALID_PARAM_VALUE,
                ErrorCause.PROJECTION_NOT_IMPLEMENTED,
                ErrorCause.CRS_TYPE_NOT_SUPPORTED),
                group(true, false, false, false));
    }

    @Test
    public void operationSelectionGroupIsExactlyAsSpecified() {
        assertEquals(EnumSet.of(
                ErrorCause.NO_OPERATION_AVAILABLE,
                ErrorCause.BALLPARK_REJECTED,
                ErrorCause.BEST_OPERATION_UNAVAILABLE,
                ErrorCause.MISSING_GRID,
                ErrorCause.NO_INVERSE_AVAILABLE,
                ErrorCause.UNSUPPORTED_OPERATION_METHOD),
                group(false, true, false, false));
    }

    @Test
    public void perCoordinateGroupIsExactlyAsSpecified() {
        assertEquals(EnumSet.of(
                ErrorCause.INVALID_COORDINATE,
                ErrorCause.COORDINATE_OUT_OF_DOMAIN,
                ErrorCause.COORDINATE_OUTSIDE_AREA_OF_USE,
                ErrorCause.COORDINATE_OUTSIDE_GRID,
                ErrorCause.GRID_NODATA,
                ErrorCause.NUMERICAL_FAILURE,
                ErrorCause.MISSING_TIME),
                group(false, false, true, false));
    }

    @Test
    public void environmentGroupIsExactlyAsSpecified() {
        assertEquals(EnumSet.of(
                ErrorCause.DATABASE_UNAVAILABLE,
                ErrorCause.NETWORK_DISABLED,
                ErrorCause.API_MISUSE,
                ErrorCause.INTERNAL_ERROR),
                group(false, false, false, true));
    }

    @Test
    public void isNoUsableOperationCoversEveryEmptyCandidateListOutcome() {
        for (ErrorCause c : new ErrorCause[] {
                ErrorCause.NO_OPERATION_AVAILABLE,
                ErrorCause.BALLPARK_REJECTED,
                ErrorCause.BEST_OPERATION_UNAVAILABLE,
                ErrorCause.MISSING_GRID,
                ErrorCause.NO_INVERSE_AVAILABLE,
                ErrorCause.UNSUPPORTED_OPERATION_METHOD }) {
            assertTrue(c.name() + " means no usable operation", c.isNoUsableOperation());
        }
        assertFalse(ErrorCause.UNKNOWN_CRS.isNoUsableOperation());
        assertFalse(ErrorCause.NUMERICAL_FAILURE.isNoUsableOperation());
        assertFalse(ErrorCause.INTERNAL_ERROR.isNoUsableOperation());
    }

    /**
     * A caller cannot get a null out of any accessor, however it obtained the constant.
     */
    @Test
    public void everyAccessorIsTotal() {
        for (ErrorCause c : ErrorCause.values()) {
            assertNotNull(c.metricKey());
            // Exercises the predicates for coverage as well as for the null check above.
            c.isCrsError();
            c.isOperationError();
            c.isCoordinateError();
            c.isEnvironmentError();
            c.isNoUsableOperation();
        }
    }

    private static EnumSet<ErrorCause> group(boolean crs, boolean op, boolean coord, boolean env) {
        EnumSet<ErrorCause> found = EnumSet.noneOf(ErrorCause.class);
        for (ErrorCause c : ErrorCause.values()) {
            if (c.isCrsError() == crs && c.isOperationError() == op
                    && c.isCoordinateError() == coord && c.isEnvironmentError() == env) {
                found.add(c);
            }
        }
        return found;
    }
}

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
package org.locationtech.proj4j;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.After;
import org.junit.Test;

/**
 * {@link Proj4jException#fillInStackTrace()}: the frames are gone by default, everything a caller
 * can act on is not, and the flag really does bring them back.
 *
 * <h2>Why the frames were dropped</h2>
 *
 * <p>An exception here is usually the <em>answer</em>, not a bug report:
 * {@link ErrorCause#COORDINATE_OUTSIDE_GRID} fires once per point outside the declared
 * {@code +nadgrids=} coverage, and this library is called per row inside a Spark executor.
 * {@code GridShiftBenchmark.noGridHit} measured the refusal at <b>1,440 B/op and 585 ns</b>,
 * essentially all of it {@code Throwable.fillInStackTrace}'s native stack walk.
 *
 * <h2>Why this test is not vacuous</h2>
 *
 * <p>A suppression that suppressed everything would pass any test that only checks for absent
 * frames. So each case here is a pair: <b>off</b> gives zero frames, <b>on</b> gives frames naming
 * this test's own method, and both give the same type, the same {@link Proj4jException#cause()},
 * the same message and the same {@link Throwable#getCause()}. The flag is the control, and it is
 * exercised in both directions in the same JVM — which is why it is a settable field and not a
 * {@code static final} read of a system property.
 */
public class Proj4jExceptionStackTraceTest {

    @After
    public void restoreDefault() {
        Proj4jException.setStackTraceCaptureEnabled(false);
    }

    @Test
    public void offByDefault() {
        // The class initialiser reads the system property, which the surefire JVM does not set.
        assertFalse("stack-trace capture must be off unless asked for",
                Proj4jException.isStackTraceCaptureEnabled());
    }

    @Test
    public void suppressedByDefaultAndRestoredByTheFlag() {
        Proj4jException.setStackTraceCaptureEnabled(false);
        Proj4jException off = new Proj4jException("no frames please");
        assertEquals("frames were captured with the flag off", 0, off.getStackTrace().length);

        Proj4jException.setStackTraceCaptureEnabled(true);
        Proj4jException on = new Proj4jException("frames please");

        // CONTROL. If this reads zero, the "0 above" result says nothing: it would be consistent
        // with a JVM that never fills traces, a JMH-style flag, or a broken assertion.
        assertTrue("CONTROL FAILED - the flag did not restore stack traces, so the suppression"
                        + " assertion above proves nothing",
                on.getStackTrace().length > 0);
        assertEquals("the restored trace does not start at the throw site",
                "suppressedByDefaultAndRestoredByTheFlag",
                on.getStackTrace()[0].getMethodName());
    }

    /** The whole hierarchy inherits it — a subclass that reintroduced the walk would be invisible. */
    @Test
    public void everySubclassInheritsTheSuppression() {
        Proj4jException.setStackTraceCaptureEnabled(false);
        Proj4jException[] all = {
                new Proj4jException("base"),
                new CrsTransformException(ErrorCause.NUMERICAL_FAILURE, "transform"),
                new CrsCreationException(ErrorCause.INVALID_PARAM_VALUE, "creation"),
                new UnknownAuthorityCodeException("EPSG:999999"),
                new InvalidValueException("value"),
                new ContradictoryParameterException("contradiction"),
                new UnsupportedParameterException("unsupported"),
                new ProjectionException("projection"),
                new ConvergenceFailureException("convergence"),
        };
        for (Proj4jException e : all) {
            assertEquals(e.getClass().getName() + " still captures a stack trace",
                    0, e.getStackTrace().length);
        }

        Proj4jException.setStackTraceCaptureEnabled(true);
        // CONTROL: the same constructors, with the flag on, must all produce frames.
        Proj4jException[] withFrames = {
                new Proj4jException("base"),
                new CrsTransformException(ErrorCause.NUMERICAL_FAILURE, "transform"),
                new CrsCreationException(ErrorCause.INVALID_PARAM_VALUE, "creation"),
                new UnknownAuthorityCodeException("EPSG:999999"),
                new InvalidValueException("value"),
                new ContradictoryParameterException("contradiction"),
                new UnsupportedParameterException("unsupported"),
                new ProjectionException("projection"),
                new ConvergenceFailureException("convergence"),
        };
        for (Proj4jException e : withFrames) {
            assertTrue("CONTROL FAILED for " + e.getClass().getName(),
                    e.getStackTrace().length > 0);
        }
    }

    /**
     * Everything a caller can branch on survives. This is the actual claim being made — that the
     * frames were the only thing dropped.
     */
    @Test
    public void theDiagnosticPayloadIsUnchanged() {
        RuntimeException underlying = new IllegalStateException("underlying");

        Proj4jException.setStackTraceCaptureEnabled(true);
        CrsTransformException withFrames = new CrsTransformException(
                ErrorCause.COORDINATE_OUTSIDE_GRID, "point (1.0, 2.0) is outside [conus]",
                underlying);

        Proj4jException.setStackTraceCaptureEnabled(false);
        CrsTransformException withoutFrames = new CrsTransformException(
                ErrorCause.COORDINATE_OUTSIDE_GRID, "point (1.0, 2.0) is outside [conus]",
                underlying);

        assertEquals(withFrames.getClass(), withoutFrames.getClass());
        assertEquals(withFrames.cause(), withoutFrames.cause());
        assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, withoutFrames.cause());
        assertEquals(withFrames.getMessage(), withoutFrames.getMessage());
        assertSame(underlying, withoutFrames.getCause());
        assertTrue(withoutFrames instanceof Proj4jException);

        // printStackTrace still identifies the exception and its cause; only the frames are gone.
        StringWriter sw = new StringWriter();
        withoutFrames.printStackTrace(new PrintWriter(sw));
        String printed = sw.toString();
        assertTrue("printStackTrace lost the type: " + printed,
                printed.contains("CrsTransformException"));
        assertTrue("printStackTrace lost the message: " + printed,
                printed.contains("outside [conus]"));
        assertTrue("printStackTrace lost the underlying cause: " + printed,
                printed.contains("IllegalStateException"));
    }

    /**
     * Not a shared instance. Two refusals must carry two accurate messages — the usual
     * preallocated-singleton trick would make one of them a lie, and this library's whole thesis is
     * that a plausible-looking wrong answer is the worst outcome.
     */
    @Test
    public void everyThrowIsStillItsOwnObject() {
        Proj4jException.setStackTraceCaptureEnabled(false);
        Proj4jException a = new ProjectionException("point A out of domain");
        Proj4jException b = new ProjectionException("point B out of domain");
        assertFalse("two refusals returned the same object", a == b);
        assertEquals("point A out of domain", a.getMessage());
        assertEquals("point B out of domain", b.getMessage());
    }

    /**
     * The real refusal, through the real API, with the coordinate and grid names still in the
     * message. This is the case the optimisation exists for.
     */
    @Test
    public void theGridRefusalKeepsItsMessage() {
        CRSFactory factory = new CRSFactory();
        CoordinateReferenceSystem nad27 = factory.createFromParameters("nad27",
                "+proj=longlat +ellps=clrk66 +nadgrids=conus +no_defs");
        CoordinateReferenceSystem wgs84 = factory.createFromName("EPSG:4326");
        CoordinateTransform t = new CoordinateTransformFactory().createTransform(nad27, wgs84);

        // Somewhere in the Indian Ocean: outside every US grid.
        ProjCoordinate in = new ProjCoordinate(70.0, -30.0);
        try {
            t.transform(in, new ProjCoordinate());
            org.junit.Assert.fail("expected a refusal outside the grid");
        } catch (CrsTransformException e) {
            assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, e.cause());
            assertEquals("the refusal lost its frames AND its message", 0,
                    e.getStackTrace().length);
            assertNotNull(e.getMessage());
            assertTrue("the message no longer names the grids: " + e.getMessage(),
                    e.getMessage().contains("conus"));
            assertTrue("the message no longer carries the coordinate: " + e.getMessage(),
                    e.getMessage().contains("70.0") || e.getMessage().contains("-30.0"));
        }
    }
}

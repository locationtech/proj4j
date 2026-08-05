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
package org.locationtech.proj4j.failclosed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.NoSuchElementException;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.proj.MercatorProjection;
import org.locationtech.proj4j.proj.Projection;
import org.locationtech.proj4j.proj.TransverseMercatorProjection;

/**
 * {@code +south} and {@code +h} on a projection that does not read them must fail as a
 * {@link Proj4jException}, not as a bare {@link NoSuchElementException}.
 *
 * <h2>What was wrong</h2>
 *
 * <p>{@code Projection.setSouthernHemisphere} and {@code Projection.setHeightOfOrbit} threw
 * {@code new NoSuchElementException()} — unchecked, <b>not</b> a {@code Proj4jException}, and
 * with <b>no message at all</b>. So {@code +south} and {@code +h} on the wrong projection
 * escaped every {@code catch (Proj4jException)} in the library and in every caller, and arrived
 * carrying nothing that could be logged, counted, or acted on.
 *
 * <p>Fifty golden-master rows reach those two lines —
 * {@code SYN/mod/}&#123;{@code aea},{@code lcc},{@code longlat},{@code merc},{@code tmerc},
 * {@code utm}&#125;{@code /h} and
 * {@code SYN/mod/}&#123;{@code aea},{@code lcc},{@code longlat},{@code merc}&#125;{@code /south}.
 * The differential gate could not see any of them, because the defect predates its 1.4.3
 * baseline and the row is byte-identical on both sides. A defect older than the baseline is
 * precisely what a diff-based regime is blind to, which is why this test exists as an absolute
 * assertion rather than as a golden row.
 *
 * <h2>Why refusing is the right answer, and where the PROJ-compatible alternative belongs</h2>
 *
 * <p>PROJ has no parameter allow-list: a key no operator reads is retained and silently ignored.
 * {@code 9.8.1:src/init.cpp} performs no such validation, and the {@code used} flag on
 * {@code paralist} ({@code 9.8.1:src/param.cpp:17}) exists only to resolve {@code +init} and
 * pipeline overrides, never to reject. So refusing here is stricter than PROJ.
 *
 * <p>It is still right, because the two cases that reach the base class are not the same thing
 * and the base class cannot tell them apart:
 *
 * <ul>
 * <li>{@code +proj=merc +south} — PROJ's {@code merc} never reads {@code +south}, so ignoring it
 *     is correct and refusing it is a divergence that costs nothing but strictness.</li>
 * <li>{@code +proj=nsper +h=1000000} — PROJ's {@code nsper} <b>does</b> read {@code +h} and needs
 *     it, and Proj4J's {@code PerspectiveProjection} simply does not override the setter.
 *     Ignoring this one projects from a default orbit and returns a plausible wrong coordinate,
 *     which is exactly what the no-sentinels rule forbids.</li>
 * </ul>
 *
 * <p>Only the parser holds the information needed to separate them. If {@code +south} and
 * {@code +h} are ever to be retained-and-ignored in {@code ParseMode.PROJ_COMPATIBLE} and
 * refused only in {@code STRICT}, that discrimination has to be made in {@code Proj4Parser}
 * against a table of which projections legitimately do not read them — it cannot be made in
 * {@code Projection}, which is where these throws live.
 *
 * <p>The change here is therefore <b>type and message only</b>: it does not alter <em>whether</em>
 * any definition is refused, so no row can move from pass to fail because of it.
 *
 * @see org.locationtech.proj4j.UnsupportedParameterException
 */
public class ProjectionParameterRefusalTest {

    private static final CRSFactory CRS = new CRSFactory();

    // ------------------------------------------------------------------ helpers

    /**
     * Assert that building {@code params} fails the fail-closed way: a {@link Proj4jException},
     * carrying a non-null {@link ErrorCause}, whose message names both the offending parameter
     * and the projection. A bare throw of the right type is only half the fix.
     */
    private static Proj4jException assertRefused(String params, String key, String projName) {
        try {
            CoordinateReferenceSystem crs = CRS.createFromParameters("refused", params);
            fail(params + " must be refused, but produced " + crs.getParameterString());
            throw new AssertionError("unreachable");
        } catch (NoSuchElementException e) {
            throw new AssertionError(params + " threw a bare java.util.NoSuchElementException, "
                    + "which is not a Proj4jException and escapes every catch in the library. "
                    + "Message was: " + e.getMessage(), e);
        } catch (Proj4jException e) {
            assertNotNull("cause() must never be null", e.cause());
            String m = e.getMessage();
            assertNotNull(params + ": the exception must carry a message; the defect this "
                    + "replaces threw with none at all", m);
            assertTrue(params + ": message must name the offending parameter '" + key
                    + "'; was: " + m, m.contains(key));
            assertTrue(params + ": message must name the projection '" + projName
                    + "'; was: " + m, m.contains(projName));
            return e;
        }
    }

    /** Poison a destination so a stale read cannot masquerade as a computed answer. */
    private static ProjCoordinate poisoned() {
        ProjCoordinate c = new ProjCoordinate();
        c.x = c.y = 1e300;
        c.z = 1e300;
        return c;
    }

    // ------------------------------------------------- +south, via the parse path

    @Test
    public void southIsRefusedOnMercator() {
        Proj4jException e = assertRefused("+proj=merc +ellps=GRS80 +south", "+south", "Mercator");
        assertEquals(ErrorCause.PROJECTION_NOT_IMPLEMENTED, e.cause());
    }

    @Test
    public void southIsRefusedOnAlbers() {
        assertRefused("+proj=aea +ellps=GRS80 +lat_1=29.5 +lat_2=45.5 +south", "+south", "Albers");
    }

    @Test
    public void southIsRefusedOnLambertConformalConic() {
        assertRefused("+proj=lcc +ellps=GRS80 +lat_1=33 +lat_2=45 +south", "+south", "Lambert");
    }

    @Test
    public void southIsRefusedOnLongLat() {
        assertRefused("+proj=longlat +ellps=GRS80 +south", "+south", "LongLat");
    }

    // ----------------------------------------------------- +h, via the parse path

    @Test
    public void heightOfOrbitIsRefusedOnMercator() {
        Proj4jException e =
                assertRefused("+proj=merc +ellps=GRS80 +h=35785831", "+h", "Mercator");
        assertEquals(ErrorCause.PROJECTION_NOT_IMPLEMENTED, e.cause());
    }

    @Test
    public void heightOfOrbitIsRefusedOnTransverseMercator() {
        // tmerc overrides setSouthernHemisphere but not setHeightOfOrbit, which is why the
        // golden probe set has SYN/mod/tmerc/h but no SYN/mod/tmerc/south.
        assertRefused("+proj=tmerc +ellps=GRS80 +lon_0=9 +h=35785831", "+h", "Mercator");
    }

    @Test
    public void heightOfOrbitIsRefusedOnUtm() {
        assertRefused("+proj=utm +ellps=GRS80 +zone=31 +h=35785831", "+h", "Mercator");
    }

    @Test
    public void heightOfOrbitIsNowHONOUREDOnNearSidedPerspective() {
        // INVERTED PIN. This used to assert that `+proj=nsper +h=1000000` was REFUSED, and the
        // refusal was the right answer at the time: PerspectiveProjection was a PROJ-4-era
        // half-transcription with its setup commented out, no override of setHeightOfOrbit, and
        // `height` hard-assigned to `a`. Silently ignoring +h would have projected from a
        // one-Earth-radius orbit and answered a plausible wrong coordinate, so the base class's
        // refusal was the honest outcome.
        //
        // PerspectiveProjection is now a full port of 9.8.1:src/projections/nsper.cpp -- all four
        // aspects, both directions, the far-hemisphere rejection and the h/a range check -- so the
        // assertion is inverted rather than weakened: +h must now be READ, and the twenty
        // builtins.gie nsper rows that this refusal made unreachable now pass.
        //
        // The negative controls above (merc, tmerc, utm, longlat, lcc) are unchanged and are what
        // still protects the fail-closed contract for projections that genuinely do not read +h.
        CoordinateReferenceSystem crs =
                CRS.createFromParameters("nsper", "+proj=nsper +a=6400000 +h=1000000");
        assertEquals("nsper must retain +h, not refuse it and not default it",
                1000000.0, crs.getProjection().getHeightOfOrbit(), 0.0);

        // And the value must actually reach the arithmetic: an nsper at h = 1e6 m on a
        // 6 400 000 m sphere is not the orthographic limit, so the easting at (2, 1) differs
        // measurably from `ortho`'s.
        CoordinateTransform t = new CoordinateTransformFactory().createTransform(
                CRS.createFromParameters("wgs84", "+proj=longlat +a=6400000"), crs);
        ProjCoordinate dst = poisoned();
        t.transform(new ProjCoordinate(2, 1), dst);
        assertTrue("nsper forward must overwrite the poisoned destination; got " + dst,
                dst.x > 2.2e5 && dst.x < 2.3e5);
    }

    @Test
    public void heightOfOrbitIsStillRefusedWhereTheProjectionDoesNotReadIt() {
        // The generalisation the nsper inversion must not be allowed to erode: +proj=ortho reads
        // no +h upstream (ortho.cpp has no "dh" parameter), so the base class must still refuse.
        assertRefused("+proj=ortho +a=6400000 +h=1000000", "+h", "Orthographic");
    }

    // ------------------------------------------- the fix must not over-refuse

    @Test
    public void southIsStillHonouredWhereTheProjectionReadsIt() {
        // Positive control. utm and tmerc do override setSouthernHemisphere, so +south must go
        // on working -- otherwise the fix would have made Proj4J stricter than PROJ on
        // definitions PROJ and Proj4J both support, and would fail corpus rows.
        CoordinateReferenceSystem crs =
                CRS.createFromParameters("utm31s", "+proj=utm +ellps=GRS80 +zone=31 +south");
        assertTrue("utm must report the southern hemisphere flag it was given",
                crs.getProjection().getSouthernHemisphere());

        CoordinateTransform t = new CoordinateTransformFactory().createTransform(
                CRS.createFromParameters("wgs84", "+proj=longlat +ellps=GRS80"), crs);
        ProjCoordinate dst = poisoned();
        t.transform(new ProjCoordinate(3, -30), dst);
        assertTrue("+south must move the false northing to 10 000 000 m, so a southern "
                + "latitude lands well north of zero; got " + dst.y, dst.y > 6.0e6);
    }

    @Test
    public void heightOfOrbitIsStillHonouredOnGeostationary() {
        // Positive control for +h. geos is the one projection that overrides the setter.
        CoordinateReferenceSystem crs = CRS.createFromParameters(
                "geos", "+proj=geos +lon_0=0 +ellps=GRS80 +h=35785831");
        assertEquals(35785831.0, crs.getProjection().getHeightOfOrbit(), 0.0);

        CoordinateTransform t = new CoordinateTransformFactory().createTransform(
                CRS.createFromParameters("wgs84", "+proj=longlat +ellps=GRS80"), crs);
        ProjCoordinate dst = poisoned();
        t.transform(new ProjCoordinate(1, 1), dst);
        assertTrue("geos forward must overwrite the poisoned destination", dst.x < 1e6);
        assertTrue("geos forward must overwrite the poisoned destination", dst.y < 1e6);
    }

    // ----------------------------------------- the setters and getters directly

    @Test
    public void baseSettersAndGettersAreAllProj4jExceptions() {
        Projection p = new MercatorProjection();
        assertRefusedDirectly("setSouthernHemisphere", "+south", p, new Runnable() {
            public void run() {
                p.setSouthernHemisphere(true);
            }
        });
        assertRefusedDirectly("getSouthernHemisphere", "+south", p, new Runnable() {
            public void run() {
                p.getSouthernHemisphere();
            }
        });
        assertRefusedDirectly("setHeightOfOrbit", "+h", p, new Runnable() {
            public void run() {
                p.setHeightOfOrbit(35785831.0);
            }
        });
        assertRefusedDirectly("getHeightOfOrbit", "+h", p, new Runnable() {
            public void run() {
                p.getHeightOfOrbit();
            }
        });
    }

    @Test
    public void overridingSubclassesDoNotThrow() {
        TransverseMercatorProjection tmerc = new TransverseMercatorProjection();
        tmerc.setSouthernHemisphere(true);
        assertTrue(tmerc.getSouthernHemisphere());
        tmerc.setSouthernHemisphere(false);
        assertFalse(tmerc.getSouthernHemisphere());
    }

    private static void assertRefusedDirectly(String method, String key, Projection p,
                                              Runnable call) {
        try {
            call.run();
            fail("Projection." + method + " must refuse on the base class");
        } catch (NoSuchElementException e) {
            throw new AssertionError("Projection." + method + " threw a bare "
                    + "java.util.NoSuchElementException, which is not a Proj4jException", e);
        } catch (Proj4jException e) {
            assertNotNull(method + ": cause() must never be null", e.cause());
            assertEquals(method + ": this is a capability boundary reached while building a CRS, "
                            + "not a per-coordinate failure, so cause() must be in the CRS group",
                    ErrorCause.PROJECTION_NOT_IMPLEMENTED, e.cause());
            assertTrue(method + ": cause() must classify as a CRS-definition error, so a caller "
                    + "branching on the group predicates does not mistake it for a bad "
                    + "coordinate", e.cause().isCrsError());
            assertFalse(method + ": must not classify as a per-coordinate error",
                    e.cause().isCoordinateError());
            String m = e.getMessage();
            assertNotNull(method + ": must carry a message", m);
            assertTrue(method + ": message must name the parameter " + key + "; was: " + m,
                    m.contains(key));
            assertTrue(method + ": message must name the projection; was: " + m,
                    m.contains(p.getName()) || m.contains(p.getClass().getName()));
        }
    }
}

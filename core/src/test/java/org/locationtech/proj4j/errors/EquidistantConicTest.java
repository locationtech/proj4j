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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.proj.EquidistantConicProjection;

/**
 * {@code +proj=eqdc} is no longer a pure identity, and now agrees with PROJ 9.8.1.
 *
 * <p>The old class overrode the public degrees-in/degrees-out {@code project} and
 * {@code inverseProject} rather than the protected radian hooks that
 * {@code BasicCoordinateTransform} calls, so both overrides were dead code and every coordinate
 * fell through to {@code Projection}'s identity base implementations — while
 * {@code hasInverse()} reported {@code true}. Nothing in the library detected that, because an
 * identity is finite, in range, and plausible.
 */
public class EquidistantConicTest {

    private static final double METRE_TOLERANCE = 1e-6;

    private final CRSFactory crsFactory = new CRSFactory();
    private final CoordinateTransformFactory transformFactory = new CoordinateTransformFactory();

    /** {@code +proj=eqdc +ellps=GRS80 +lat_1=33 +lat_2=45 +lat_0=39}. */
    private CoordinateReferenceSystem eqdc() {
        return crsFactory.createFromParameters("eqdc",
                "+proj=eqdc +ellps=GRS80 +lat_1=33 +lat_2=45 +lat_0=39");
    }

    private CoordinateReferenceSystem geographic() {
        return crsFactory.createFromParameters("geo", "+proj=longlat +ellps=GRS80");
    }

    /**
     * The headline assertion: the projected coordinate is not the input. Before this change the
     * forward transform of (0.5, 40.5) was (0.5, 40.5).
     */
    @Test
    public void forwardIsNotTheIdentity() {
        ProjCoordinate in = new ProjCoordinate(0.5, 40.5);
        ProjCoordinate out = new ProjCoordinate(1e300, 1e300);
        transformFactory.createTransform(geographic(), eqdc()).transform(in, out);

        assertNotEquals("eqdc must not pass the longitude through unchanged", 0.5, out.x, 1.0);
        assertNotEquals("eqdc must not pass the latitude through unchanged", 40.5, out.y, 1.0);
        assertTrue("an eqdc easting is in metres, so it must be far from 0.5 degrees",
                Math.abs(out.x) > 1000.0);
    }

    /**
     * Against PROJ 9.8.1, verbatim:
     * <pre>
     * echo "0.5 40.5" | proj -f "%.6f" +proj=eqdc +ellps=GRS80 +lat_1=33 +lat_2=45 +lat_0=39
     * 42163.667772    166660.338831
     * </pre>
     */
    @Test
    public void forwardMatchesProj981() {
        ProjCoordinate in = new ProjCoordinate(0.5, 40.5);
        ProjCoordinate out = new ProjCoordinate(1e300, 1e300);
        transformFactory.createTransform(geographic(), eqdc()).transform(in, out);

        assertEquals("easting", 42163.667772, out.x, METRE_TOLERANCE);
        assertEquals("northing", 166660.338831, out.y, METRE_TOLERANCE);
    }

    /**
     * The non-identity round trip. This is the test the old code could not fail: an identity
     * forward composed with an identity inverse round-trips perfectly, which is precisely why the
     * defect survived. Here the intermediate is asserted to be metres, so a regression to the
     * identity fails {@link #forwardIsNotTheIdentity()} even though this test would still pass.
     */
    @Test
    public void roundTripsThroughRealProjectedMetres() {
        ProjCoordinate in = new ProjCoordinate(-2.75, 41.25);
        ProjCoordinate projected = new ProjCoordinate(1e300, 1e300);
        ProjCoordinate back = new ProjCoordinate(1e300, 1e300);

        CoordinateReferenceSystem geo = geographic();
        CoordinateReferenceSystem eqdc = eqdc();
        transformFactory.createTransform(geo, eqdc).transform(in, projected);
        assertTrue("the intermediate must be projected metres, not the input degrees",
                Math.abs(projected.x) > 100000.0);
        transformFactory.createTransform(eqdc, geo).transform(projected, back);

        assertEquals("round-tripped longitude", -2.75, back.x, 1e-9);
        assertEquals("round-tripped latitude", 41.25, back.y, 1e-9);
    }

    /** The spherical branch, {@code +R} rewritten as {@code +a=+b}, also round-trips. */
    @Test
    public void sphericalBranchRoundTrips() {
        CoordinateReferenceSystem geo =
                crsFactory.createFromParameters("geo", "+proj=longlat +a=6400000 +b=6400000");
        CoordinateReferenceSystem eqdc = crsFactory.createFromParameters("eqdc",
                "+proj=eqdc +a=6400000 +b=6400000 +lat_1=33 +lat_2=45 +lat_0=39");

        ProjCoordinate in = new ProjCoordinate(1.5, 38.0);
        ProjCoordinate projected = new ProjCoordinate(1e300, 1e300);
        ProjCoordinate back = new ProjCoordinate(1e300, 1e300);
        transformFactory.createTransform(geo, eqdc).transform(in, projected);
        assertTrue(Math.abs(projected.x) > 100000.0);
        transformFactory.createTransform(eqdc, geo).transform(projected, back);
        assertEquals(1.5, back.x, 1e-9);
        assertEquals(38.0, back.y, 1e-9);
    }

    /**
     * {@code hasInverse()} already reported {@code true}; now it is true of something.
     */
    @Test
    public void inverseIsNotTheIdentityEither() {
        ProjCoordinate in = new ProjCoordinate(42163.667772, 166660.338831);
        ProjCoordinate out = new ProjCoordinate(1e300, 1e300);
        transformFactory.createTransform(eqdc(), geographic()).transform(in, out);

        assertEquals(0.5, out.x, 1e-6);
        assertEquals(40.5, out.y, 1e-6);
    }

    // ------------------------------------------------------------ the construction-time guards

    @Test
    public void degenerateStandardParallelsAreRejectedAtConstruction() {
        // |lat_1 + lat_2| == 0: upstream's third rejection. Fails while it is still a definition,
        // so no coordinate can ever be produced from it.
        assertRejected("+proj=eqdc +ellps=GRS80 +lat_1=30 +lat_2=-30");
    }

    @Test
    public void outOfRangeStandardParallelsAreRejectedAtConstruction() {
        assertRejected("+proj=eqdc +ellps=GRS80 +lat_1=100 +lat_2=45");
        assertRejected("+proj=eqdc +ellps=GRS80 +lat_1=33 +lat_2=-91");
    }

    private void assertRejected(String definition) {
        try {
            crsFactory.createFromParameters("eqdc", definition);
            fail("must reject: " + definition);
        } catch (InvalidValueException e) {
            assertEquals(ErrorCause.INVALID_PARAM_VALUE, e.cause());
        }
    }

    /**
     * A bare {@code +proj=eqdc} still constructs, on the legacy default parallels, rather than
     * failing upstream's {@code |lat_1 + lat_2| > 0} check with both at zero. That keeps this a
     * bug fix rather than a behavioural break for anyone who wrote {@code +proj=eqdc} alone.
     */
    @Test
    public void bareDefinitionStillConstructsAndProjects() {
        CoordinateTransform t = transformFactory.createTransform(
                crsFactory.createFromParameters("geo", "+proj=longlat +ellps=GRS80"),
                crsFactory.createFromParameters("eqdc", "+proj=eqdc +ellps=GRS80"));
        ProjCoordinate out = new ProjCoordinate(1e300, 1e300);
        t.transform(new ProjCoordinate(1.0, 40.0), out);
        assertTrue(Math.abs(out.x) > 1000.0);
        assertTrue(Double.isFinite(out.x) && Double.isFinite(out.y));
    }

    /**
     * The legacy constructor set the standard parallels with {@code Math.toDegrees(60)} —
     * 3437.75 — and used them as radians. Whatever the defaults are, they must be sane angles.
     */
    @Test
    public void defaultStandardParallelsAreSaneAngles() {
        EquidistantConicProjection p = new EquidistantConicProjection();
        assertEquals(60.0, p.getProjectionLatitude1Degrees(), 1e-9);
        assertEquals(20.0, p.getProjectionLatitude2Degrees(), 1e-9);
    }
}

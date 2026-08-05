/*******************************************************************************
 * Copyright 2026
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

package org.locationtech.proj4j.tmerc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.proj.ExtendedTransverseMercatorProjection;
import org.locationtech.proj4j.proj.TransverseMercatorProjection;
import org.locationtech.proj4j.proj.TransverseMercatorProjection.Algorithm;

/**
 * The transverse-Mercator family's out-of-domain behaviour: every failure must be an exception
 * with a machine-readable cause, never a plausible coordinate.
 *
 * <h2>The confirmed fail-open defect</h2>
 *
 * <p>{@code ExtendedTransverseMercatorProjection.projectInverse} had <b>no {@code else} branch</b>
 * on its {@code |Ce| <= 2.623395162778} test. Because {@code BasicCoordinateTransform} passes the
 * same object as source and destination, an out-of-zone UTM easting left {@code out} holding the
 * <em>input metres</em>, which {@code Projection.inverseProjectRadians} then multiplied by
 * {@code RTD} as if they were radians and clamped to &plusmn;&pi;. The result was finite,
 * plausible and completely wrong — and specifically invisible to a finiteness postcondition,
 * which is why it needed a real error rather than a NaN check.
 */
public class TmercFailClosedTest {

    private static final CRSFactory CRS = new CRSFactory();
    private static final CoordinateTransformFactory TRANSFORMS = new CoordinateTransformFactory();

    /**
     * An out-of-zone UTM inverse. 20,000,000 m of easting is 19.5 Mm from the zone-30 central
     * meridian, i.e. {@code |Ce| = 3.06 > 2.6234}: outside the domain by a wide margin, and the
     * shape of input a caller gets from a mis-declared CRS or a transposed easting/northing.
     */
    @Test
    public void anOutOfZoneUtmInverseNowErrors() {
        CoordinateReferenceSystem utm = CRS.createFromParameters("utm30",
                "+proj=utm +zone=30 +ellps=GRS80 +no_defs");
        CoordinateReferenceSystem wgs84 = CRS.createFromParameters("geog",
                "+proj=longlat +ellps=GRS80 +no_defs");

        ProjCoordinate out = new ProjCoordinate();
        try {
            TRANSFORMS.createTransform(utm, wgs84)
                    .transform(new ProjCoordinate(20000000.0, 5000000.0), out);
            fail("expected an out-of-domain error; instead got (" + out.x + ", " + out.y
                    + ") deg — the old behaviour returned the input metres clamped to +/-pi rad, "
                    + "which is finite and plausible and wrong");
        } catch (ProjectionException e) {
            assertEquals(ErrorCause.COORDINATE_OUT_OF_DOMAIN, e.cause());
            assertTrue("the message must name the offending easting, got: " + e.getMessage(),
                    e.getMessage().contains("2.6"));
        }
    }

    /**
     * The exact shape of the old wrong answer, recorded so that a regression is recognisable.
     * {@code 20000000 rad} clamps to {@code pi rad = 180 deg} and {@code 5000000 rad} does not
     * clamp at all — the latitude used to come back as a number in the millions of degrees, or,
     * once the finiteness postcondition landed, as whatever the clamp produced. Either way the
     * transform returned normally.
     */
    @Test
    public void theOldFailOpenAnswerIsNoLongerReachable() {
        ExtendedTransverseMercatorProjection etmerc = new ExtendedTransverseMercatorProjection();
        etmerc.setEllipsoid(Ellipsoid.GRS80);
        etmerc.initialize();

        ProjCoordinate aliased = new ProjCoordinate(20000000.0, 5000000.0);
        try {
            // The aliasing is the point: src and dst are the same object, as in
            // BasicCoordinateTransform.
            etmerc.projectInverse(aliased.x, aliased.y, aliased);
            fail("expected an out-of-domain error, got (" + aliased.x + ", " + aliased.y + ")");
        } catch (ProjectionException e) {
            assertEquals(ErrorCause.COORDINATE_OUT_OF_DOMAIN, e.cause());
        }
        assertEquals("the destination must not have been written to", 20000000.0, aliased.x, 0.0);
    }

    /** {@code tmerc.cpp:379}: the forward direction has the same limit and the same errno. */
    @Test
    public void anOutOfDomainForwardErrors() {
        ExtendedTransverseMercatorProjection etmerc = new ExtendedTransverseMercatorProjection();
        etmerc.setEllipsoid(Ellipsoid.GRS80);
        etmerc.initialize();

        // At the equator, 90 degrees from the central meridian is genuinely at infinity.
        try {
            etmerc.project(Math.toRadians(90.0), 0.0, new ProjCoordinate());
            fail("expected an out-of-domain error at lam = 90 deg, lat = 0");
        } catch (ProjectionException e) {
            assertEquals(ErrorCause.COORDINATE_OUT_OF_DOMAIN, e.cause());
        }
    }

    /**
     * {@code builtins.gie:7777}, {@code +proj=utm +a=6400000 +zone=30}:
     * {@code expect failure errno invalid_op_illegal_arg_value}. Poder/Engsager is ellipsoidal
     * only, so both {@code etmerc} ({@code tmerc.cpp:617-625}) and {@code utm} ({@code :666-671})
     * reject a sphere at setup. Proj4J used to return silently from setup and then run the series
     * with {@code Qn = NaN}.
     */
    @Test
    public void etmercAndUtmRejectASphere() {
        try {
            CRS.createFromParameters("s", "+proj=utm +a=6400000 +zone=30 +no_defs");
            fail("expected +proj=utm on a sphere to be rejected");
        } catch (Proj4jException e) {
            assertEquals(ErrorCause.INVALID_PARAM_VALUE, e.cause());
        }
        try {
            CRS.createFromParameters("s", "+proj=etmerc +R=6400000 +no_defs");
            fail("expected +proj=etmerc on a sphere to be rejected");
        } catch (Proj4jException e) {
            assertEquals(ErrorCause.INVALID_PARAM_VALUE, e.cause());
        }
    }

    /**
     * The sphere guard is unconditional, so the no-argument constructor must leave a genuine
     * ellipsoid behind — otherwise {@code Registry.getProjection("etmerc")} and
     * {@code ("utm")} throw before the parser has said anything, which is what took down about
     * twenty test classes mid-change.
     *
     * <p>The trap: {@link org.locationtech.proj4j.proj.Projection}'s own constructor runs
     * {@code setEllipsoid(Ellipsoid.SPHERE)}, and this class used to assign the {@code ellipsoid}
     * field directly, so the object claimed GRS80 while {@code a}, {@code e} and {@code es}
     * described {@code Ellipsoid.SPHERE}. Every instance was therefore both mislabelled
     * <em>and</em> unable to project.
     */
    @Test
    public void theNoArgumentConstructorLeavesAUsableEllipsoid() {
        ExtendedTransverseMercatorProjection fresh = new ExtendedTransverseMercatorProjection();
        assertNotNull("Registry must be able to instantiate etmerc", fresh);
        assertEquals("the constructor says GRS80, so a must be GRS80's", 6378137.0,
                fresh.getEquatorRadius(), 0.0);
        assertEquals("... and the eccentricity must agree with the label",
                Ellipsoid.GRS80.getEccentricitySquared(),
                fresh.getEllipsoid().getEccentricitySquared(), 0.0);

        // It projects, which it could not before.
        ProjCoordinate xy = new ProjCoordinate();
        fresh.project(Math.toRadians(2), Math.toRadians(1), xy);
        assertEquals("builtins.gie:7100 in units of the semi-major axis",
                222650.796797586 / 6378137.0, xy.x, 1.0e-14);

        assertNotNull("and the parser path must still work",
                CRS.createFromParameters("e", "+proj=etmerc +ellps=GRS80 +no_defs"));
    }

    /**
     * Spherical {@code tmerc} is legitimate and must keep working — {@code tmerc.cpp:518-519}
     * selects the spherical Evenden/Snyder formulation for {@code es == 0} rather than treating it
     * as an error. {@code builtins.gie:7130-7218} is 25 rows of it and {@code :7222-7241} four
     * more on the Moon.
     */
    @Test
    public void sphericalTmercIsLegitimate() {
        CoordinateReferenceSystem sphere = CRS.createFromParameters("s",
                "+proj=tmerc +R=6400000 +no_defs");
        CoordinateReferenceSystem geog = CRS.createFromParameters("g",
                "+proj=longlat +R=6400000 +no_defs");

        ProjCoordinate got = new ProjCoordinate();
        TRANSFORMS.createTransform(geog, sphere).transform(new ProjCoordinate(2, 1), got);
        assertEquals("builtins.gie:7134", 223413.466406322, got.x, 1.0e-4);
        assertEquals("builtins.gie:7134", 111769.145040597, got.y, 1.0e-4);
        assertEquals(Algorithm.EVENDEN_SNYDER, ((TransverseMercatorProjection) sphere
                .getProjection()).getEffectiveAlgorithm());
    }

    /**
     * {@code builtins.gie:7367-7372}: {@code +proj=tmerc +R=1}, inverse of {@code -1e200},
     * {@code expect failure errno coord_transfm_outside_projection_domain}. The spherical inverse's
     * {@code exp(x/esp)} underflows to zero there.
     */
    @Test
    public void theSphericalInverseRejectsAnUnderflowingEasting() {
        CoordinateReferenceSystem unit = CRS.createFromParameters("u",
                "+proj=tmerc +R=1 +no_defs");
        CoordinateReferenceSystem geog = CRS.createFromParameters("g", "+proj=longlat +R=1 +no_defs");
        try {
            TRANSFORMS.createTransform(unit, geog)
                    .transform(new ProjCoordinate(-1.0e200, 0), new ProjCoordinate());
            fail("expected an out-of-domain error");
        } catch (ProjectionException e) {
            assertEquals(ErrorCause.COORDINATE_OUT_OF_DOMAIN, e.cause());
        }
    }

    /**
     * The approximate series is garbage more than 90&deg; from the central meridian and
     * {@code tmerc.cpp:79-88} refuses to evaluate it there ("Is error -20 really an appropriate
     * return value?" — upstream's own comment, and the answer is that an error of some kind is).
     * Proj4J had no such test: the series simply diverged and returned a finite number.
     */
    @Test
    public void theApproximateSeriesRefusesBeyondNinetyDegrees() {
        TransverseMercatorProjection approx = new TransverseMercatorProjection();
        approx.setEllipsoid(Ellipsoid.GRS80);
        approx.setApprox(true);
        approx.initialize();
        try {
            approx.project(Math.toRadians(90.5), Math.toRadians(45), new ProjCoordinate());
            fail("expected the approximate series to refuse 90.5 deg from the central meridian");
        } catch (ProjectionException e) {
            assertEquals(ErrorCause.COORDINATE_OUT_OF_DOMAIN, e.cause());
        }

        // The default algorithm is valid out to 150 degrees, so it must NOT refuse.
        TransverseMercatorProjection exact = new TransverseMercatorProjection();
        exact.setEllipsoid(Ellipsoid.GRS80);
        exact.initialize();
        ProjCoordinate xy = new ProjCoordinate();
        exact.project(Math.toRadians(90.5), Math.toRadians(45), xy);
        assertTrue("the exact series must produce a finite easting at 90.5 deg, got " + xy.x,
                !Double.isNaN(xy.x) && !Double.isInfinite(xy.x));
    }

    /**
     * The spherical forward's {@code |b| = 1} case, {@code tmerc.cpp:124-128}: the point projects
     * to infinity, and the old code reached {@code log(x/0)} and then a silent
     * {@code ProjectionMath.acos} clamp.
     */
    @Test
    public void theSphericalForwardRejectsThePointAtInfinity() {
        TransverseMercatorProjection sphere = new TransverseMercatorProjection();
        sphere.setEllipsoid(new Ellipsoid("s", 6400000.0, 6400000.0, 0.0, "sphere"));
        sphere.initialize();
        try {
            sphere.project(Math.toRadians(90.0), 0.0, new ProjCoordinate());
            fail("expected the spherical forward to refuse lam = 90 deg at the equator");
        } catch (ProjectionException e) {
            assertEquals(ErrorCause.COORDINATE_OUT_OF_DOMAIN, e.cause());
        }
    }
}

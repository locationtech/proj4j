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
package org.locationtech.proj4j.numerical;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.proj.Projection;

/**
 * {@code +proj=ortho} against {@code builtins.gie}'s {@code ortho} block, which was 51 failing
 * assertions in one operator.
 *
 * <p>The block exercises four things the old spherical-only translation could not do: the
 * ellipsoidal kernel pair from EPSG guidance note 7.2, {@code +alpha}/{@code +k_0}, a {@code lat_0}
 * of 0 rather than 45 degrees, and a domain contract in which a rejected point is <em>never</em>
 * expressed as a coordinate.
 *
 * <p>The last of those is the reason the block mixed "expected a value, got Infinity" rows with
 * "expected a failure, got a value" rows in the same function: the visibility guard poisoned both
 * ordinates and the statement immediately after the switch overwrote {@code xy.x} with a finite
 * number. So each rejection row below asserts the {@code ErrorCause}, not just that something was
 * thrown.
 */
public class OrthographicPortTest {

    // ------------------------------------------------------------------ lat_0 defaults to 0

    /**
     * {@code AzimuthalProjection}'s no-argument constructor defaulted {@code lat_0} and
     * {@code lon_0} to <b>45 degrees</b>, and {@code Proj4Parser} assigns them only when the keyword
     * is present — so this constructor, not PROJ, defined the effective default. PROJ defaults both
     * to 0.
     */
    @Test
    public void bareOrthoIsEquatorialAtGreenwich() {
        Projection p = new CRSFactory()
                .createFromParameters("t", "+proj=ortho +ellps=WGS84").getProjection();
        assertEquals("lat_0", 0.0, p.getProjectionLatitudeDegrees(), 0.0);
        assertEquals("lon_0", 0.0, p.getProjectionLongitudeDegrees(), 0.0);
    }

    // ---------------------------------------------------------------- the spherical arm

    /** Snyder (1987) table 22, p. 151 — the equatorial aspect at {@code +R=1}. */
    @Test
    public void sphericalEquatorialMatchesSnyderTable22() {
        GieAssertion g = GieAssertion.sphere("+proj=ortho +R=1 +lat_0=0 +lon_0=0", 1.0);
        g.expectForward(0, 0, 0, 0, 0.1 * GieAssertion.MM);
        g.expectForward(0, 90, 0, 1, 0.1 * GieAssertion.MM);
        g.expectForward(10, 50, 0.1116, 0.7660, 0.1 * GieAssertion.MM);
        g.expectForward(80, 10, 0.9698, 0.1736, 0.1 * GieAssertion.MM);
        g.expectForwardRejected(120, 0);
        g.expectInverseRejected(2, 2);
    }

    /** Snyder (1987) table 23, pp. 152-153 — the oblique aspect at {@code +R=1}. */
    @Test
    public void sphericalObliqueMatchesSnyderTable23() {
        GieAssertion g = GieAssertion.sphere("+proj=ortho +R=1 +lat_0=40 +lon_0=0", 1.0);
        g.expectForward(0.0, 90, 0.0, 0.7660, 0.1 * GieAssertion.MM);
        g.expectForward(40, -30, 0.5567, -0.8095, 0.1 * GieAssertion.MM);
        g.expectForward(170, 60, 0.0868, 0.9799, 0.1 * GieAssertion.MM);
        g.expectForwardRejected(140, 20);
    }

    /**
     * The polar aspects, including the row commented upstream as "a tiny tiny bit outside the radius
     * of the sphere ... it should still result in a correct coordinate" — the {@code (sinc - 1) >
     * 1e-10} band, which clamps rather than rejects.
     */
    @Test
    public void sphericalPolarClampsInsideTheTolerance() {
        GieAssertion north = GieAssertion.sphere("+proj=ortho +R=1 +lat_0=90 +lon_0=0", 1.0);
        north.expectForward(0, 0, 0, -1, 0.1 * GieAssertion.MM);
        north.expectForward(180, 0, 0, 1, 0.1 * GieAssertion.MM);
        north.expectForwardRejected(180, -90);
        north.expectForwardRejected(0, -45);

        GieAssertion south = GieAssertion.sphere("+proj=ortho +R=1 +lat_0=-90 +lon_0=0", 1.0);
        south.expectForward(0, 0, 0, 1, 0.1 * GieAssertion.MM);
        south.expectForwardRejected(0, 45);
        south.expectInverse(0.70710678118, 0.7071067812, 45, 0, 0.1 * GieAssertion.MM);
    }

    // -------------------------------------------------------------- the ellipsoidal arm

    /** EPSG guidance note 7 part 2, March 2020, p. 90 — the worked ellipsoidal oblique example. */
    @Test
    public void ellipsoidalObliqueMatchesEpsgGuidanceNote() {
        GieAssertion g = GieAssertion.wgs84("+proj=ortho +ellps=WGS84 +lat_0=55 +lon_0=5");
        g.expectForward(2.12955, 53.80939444444444, -189011.711, -128640.567, 1.0 * GieAssertion.MM);
        g.expectRoundtrip(2.12955, 53.80939444444444, 1, 1.0 * GieAssertion.MM);
    }

    /**
     * The ellipsoidal equatorial arm, whose corpus rows pin the WGS84 semi-major and semi-minor axes
     * exactly — {@code (90, 0) -> 6378137} and {@code (0, 90) -> 6356752.3142} — and then reject
     * points 0.1 mm outside each.
     */
    @Test
    public void ellipsoidalEquatorialPinsBothAxesAndRejectsJustOutside() {
        GieAssertion g = GieAssertion.wgs84("+proj=ortho +ellps=WGS84");
        g.expectForward(0, 0, 0, 0, 0.1 * GieAssertion.MM);
        g.expectForward(1, 1, 111296.9991, 110568.7748, 0.1 * GieAssertion.MM);
        g.expectForward(90, 0, 6378137, 0, 0.1 * GieAssertion.MM);
        g.expectForward(0, 90, 0, 6356752.3142, 0.1 * GieAssertion.MM);
        g.expectRoundtrip(1, 1, 1, 0.1 * GieAssertion.MM);
        g.expectRoundtrip(0, 89.99, 1, 0.1 * GieAssertion.MM);

        g.expectForwardRejected(90.00001, 0);
        g.expectForwardRejected(-90.00001, 0);
        // 0.1 mm past the semi-minor axis. (y*a/b)^2 = 1 + 1.72e-11, against upstream's
        // 1 + 1e-11 threshold: 1.7x, so this row is what fixes that guard's width.
        g.expectInverseRejected(0, 6356752.3143);
        g.expectInverseRejected(1000, 6356752.314);
        g.expectInverseRejected(6378137.0001, 0);
    }

    /** The ellipsoidal polar arm, whose inverse is closed form rather than iterative. */
    @Test
    public void ellipsoidalPolarIsClosedForm() {
        GieAssertion north = GieAssertion.wgs84("+proj=ortho +ellps=WGS84 +lat_0=90");
        north.expectForward(0, 90, 0, 0, 0.1 * GieAssertion.MM);
        north.expectForward(30, 45, 2258795.4394, -3912348.4650, 0.1 * GieAssertion.MM);
        north.expectForward(135, 89.999999873385, 0.01, 0.01, 0.1 * GieAssertion.MM);
        north.expectForward(0, 0, 0, -6378137, 0.1 * GieAssertion.MM);
        north.expectForwardRejected(0, -0.0000001);
        north.expectInverseRejected(0, -6378137.1);

        GieAssertion south = GieAssertion.wgs84("+proj=ortho +ellps=WGS84 +lat_0=-90");
        south.expectForward(135, -89.999999873385, 0.01, -0.01, 0.1 * GieAssertion.MM);
        south.expectForward(0, 0, 0, 6378137, 0.1 * GieAssertion.MM);
        south.expectForwardRejected(0, 0.0000001);
        south.expectInverseRejected(0, 6378137.1);
    }

    /**
     * The oblique ellipsoidal inverse's 20-trip Newton iteration, near the pole and at the
     * visibility boundary. The boundary row is commented upstream as "Just on it, but fails to
     * converge", and it is a <em>failure</em> — which is why exhausting the iteration must throw
     * rather than return the last iterate.
     */
    @Test
    public void ellipsoidalObliqueNewtonAndItsBoundary() {
        GieAssertion g = GieAssertion.wgs84("+proj=ortho +ellps=WGS84 +lat_0=30");
        g.expectForward(-90, 0, -6378137, 18504.1253, 0.1 * GieAssertion.MM);
        g.expectForward(0, -60, 0, -6343601.0991, 0.1 * GieAssertion.MM);
        g.expectForward(0, 90, 0, 5523613.1150, 0.1 * GieAssertion.MM);
        g.expectInverse(0, 5523613.1150, 0, 90, 0.1 * GieAssertion.MM);
        g.expectRoundtrip(0, 89.99999999, 1, 0.1 * GieAssertion.MM);
        g.expectRoundtrip(180, 89.99999999, 1, 0.1 * GieAssertion.MM);

        g.expectInverseRejected(-6378137.001, 18504.1253);
        g.expectInverseRejected(0, -6343601.099075031466782093);
        g.expectInverse(0, -6343600, 0, -59.966377950099655436, 0.1 * GieAssertion.MM);
    }

    /**
     * The Local Orthographic formulation — EPSG guidance note 7 part 2, August 2024, p. 101 — which
     * is the only corpus block that exercises {@code +alpha} and {@code +k_0}, and the only one that
     * exercises a non-zero {@code +x_0}/{@code +y_0} on this projection.
     */
    @Test
    public void localOrthographicAppliesAlphaAndK0() {
        String base = "+proj=ortho +lat_0=37.628969166666664 +lon_0=-122.39394166666668 "
                + "+k_0=0.9999968 +alpha=27.7927777777777 +ellps=GRS80 ";
        GieAssertion at0 = GieAssertion.grs80(base + "+x_0=0 +y_0=0");
        at0.expectForward(-122.3846388888889, 37.62607694444444,
                876.13676, 98.97406, 0.1 * GieAssertion.MM);
        at0.expectRoundtrip(-122.3846388888889, 37.62607694444444, 100, 0.1 * GieAssertion.MM);

        GieAssertion offset = GieAssertion.grs80(base + "+x_0=10 +y_0=20");
        offset.expectForward(-122.3846388888889, 37.62607694444444,
                886.13676, 118.97406, 0.1 * GieAssertion.MM);
        offset.expectRoundtrip(-122.3846388888889, 37.62607694444444, 100, 0.1 * GieAssertion.MM);
    }

    /**
     * {@code initialize()} runs twice — once from the constructor, once from
     * {@code Proj4Parser.parseProjection} — so any scheme that writes a derived value into a field
     * it also reads is non-idempotent. Every value this projection derives comes from
     * {@code lat_0}, {@code es} and {@code alpha}, none of which it writes; assert that by running
     * it a third time and checking the answer does not move.
     */
    @Test
    public void initializeIsIdempotent() {
        Projection p = new CRSFactory().createFromParameters("t",
                "+proj=ortho +ellps=WGS84 +lat_0=30 +alpha=20 +k_0=0.999").getProjection();
        org.locationtech.proj4j.ProjCoordinate before = new org.locationtech.proj4j.ProjCoordinate();
        p.project(new org.locationtech.proj4j.ProjCoordinate(3, 45), before);
        p.initialize();
        org.locationtech.proj4j.ProjCoordinate after = new org.locationtech.proj4j.ProjCoordinate();
        p.project(new org.locationtech.proj4j.ProjCoordinate(3, 45), after);
        assertEquals("easting after a third initialize()", before.x, after.x, 0.0);
        assertEquals("northing after a third initialize()", before.y, after.y, 0.0);
    }
}

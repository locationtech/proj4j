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

package org.locationtech.proj4j.numerics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.proj.Projection;

/**
 * {@code Projection.setRadius} must declare a true sphere.
 *
 * <p>{@code 9.8.1:src/ell_set.cpp:92-100}: a radius overrules {@code +ellps} and makes every
 * shape parameter irrelevant, so {@code es}, {@code e} and {@code f} all become exactly zero and
 * {@code b == a}. proj4j's {@code setRadius} assigned the semi-major axis alone, leaving
 * {@code e}, {@code es} and {@code ellipsoid} stale, so {@code spherical} stayed {@code false}
 * and the <em>ellipsoidal</em> formula ran on a declared sphere — northing wrong by about
 * 1,495 m at 2 degrees and 35,000 m at 55.
 *
 * <p>The {@code +R=} parse path no longer reaches this method (it is resolved in
 * {@code DatumParameters.setR}), but the method is public API and was wrong on its own terms.
 * That is exactly why the coupling matters: making {@code +R} spherical is what exposed the
 * Albers spherical inverse, the doubled {@code k_0} in the spherical transverse Mercator and the
 * dead ellipsoidal Polyconic branch, all covered by the sibling tests in this package.
 */
public class SphereDeclarationTest {

    private static Projection merc() {
        return new CRSFactory()
                .createFromParameters("m", "+proj=merc +lon_0=0 +ellps=GRS80 +units=m +no_defs")
                .getProjection();
    }

    @Test
    public void setRadiusZeroesTheEccentricityAndReplacesTheEllipsoid() {
        Projection p = merc();
        assertTrue("GRS80 must start out eccentric",
                p.getEllipsoid().getEccentricitySquared() > 0.0);
        Ellipsoid before = p.getEllipsoid();

        p.setRadius(6371000.0);

        assertEquals("the semi-major axis must be the radius", 6371000.0, p.getEquatorRadius(), 0.0);
        assertNotSame("the stale ellipsoid must be replaced, not kept", before, p.getEllipsoid());
        assertEquals("es must be exactly zero", 0.0,
                p.getEllipsoid().getEccentricitySquared(), 0.0);
        assertEquals("e must be exactly zero", 0.0,
                p.getEllipsoid().eccentricity, 0.0);
        assertEquals("b == a for a sphere", 6371000.0,
                p.getEllipsoid().poleRadius, 0.0);
        assertEquals("and the ellipsoid's own radius must agree with the projection's",
                6371000.0, p.getEllipsoid().getEquatorRadius(), 0.0);
    }

    /**
     * The consequence: after {@code initialize()} the spherical branch runs, so a Mercator
     * forward is the closed-form spherical one. On a sphere the ellipsoidal Mercator northing is
     * wrong by roughly 1,495 m at 2 degrees latitude, growing to about 35 km at 55.
     */
    @Test
    public void sphericalFormulaeAreSelectedAfterInitialize() {
        Projection p = merc();
        p.setRadius(6371000.0);
        p.initialize();

        ProjCoordinate xy = new ProjCoordinate();
        p.projectRadians(new ProjCoordinate(Math.toRadians(2.0), Math.toRadians(2.0)), xy);

        // Spherical Mercator, exactly: x = R*lam, y = R*asinh(tan(phi)).
        double lam = Math.toRadians(2.0);
        double phi = Math.toRadians(2.0);
        double expectedX = 6371000.0 * lam;
        double expectedY = 6371000.0 * Math.log(Math.tan(Math.PI / 4 + phi / 2));
        assertEquals("easting", expectedX, xy.x, 1e-6);
        assertEquals("northing must be the spherical value, not the ellipsoidal one",
                expectedY, xy.y, 1e-6);

        // Sanity: the ellipsoidal value really is far away, so the assertion above has teeth.
        Projection ellipsoidal = merc();
        ProjCoordinate ell = new ProjCoordinate();
        ellipsoidal.projectRadians(new ProjCoordinate(lam, phi), ell);
        assertTrue("the two formulae must be distinguishable at this latitude, "
                        + "difference was " + Math.abs(ell.y - xy.y) + " m",
                Math.abs(ell.y - xy.y) > 1000.0);
    }

    /** {@code +R=} through the parser must reach the same state. */
    @Test
    public void radiusThroughTheParserAlsoDeclaresASphere() {
        Projection p = new CRSFactory()
                .createFromParameters("r", "+proj=merc +R=6400000 +no_defs")
                .getProjection();
        assertEquals(6400000.0, p.getEquatorRadius(), 0.0);
        assertEquals(0.0, p.getEllipsoid().getEccentricitySquared(), 0.0);
    }
}

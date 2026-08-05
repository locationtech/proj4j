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

package org.locationtech.proj4j.proj.adams;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.proj.AdamsHemisphereProjection;
import org.locationtech.proj4j.proj.AdamsWorldInASquareIIProjection;
import org.locationtech.proj4j.proj.AdamsWorldInASquareIProjection;
import org.locationtech.proj4j.proj.GuyouProjection;
import org.locationtech.proj4j.proj.PeirceQuincuncialProjection;
import org.locationtech.proj4j.proj.Projection;
import org.locationtech.proj4j.proj.SpilhausProjection;

/**
 * The domain contract of the adams family, which is worth as much as the arithmetic: about 968 of
 * the 3,443 {@code expect} rows in the six files are {@code expect failure}.
 *
 * <p>{@link AdamsFamilyCorpusTest} already proves every one of them against the corpus. This test
 * exists to state the <em>rules</em> separately, so that a future change that happens to keep the
 * corpus green while inventing or losing a guard still fails something with a name.
 *
 * <p>Three predicates account for all 723 projection-level rejections, and two operators have no
 * projection-level guard at all — which is just as load bearing, because inventing one for them
 * would turn correct answers into failures.
 */
public class AdamsDomainContractTest {

    private static final double DTR = Math.PI / 180.0;

    private static Projection initialized(Projection p) {
        p.setRadius(6370997);
        p.initialize();
        return p;
    }

    private static ProjCoordinate forward(Projection p, double lonDeg, double latDeg) {
        return p.project(new ProjCoordinate(lonDeg, latDeg), new ProjCoordinate());
    }

    private static ProjectionException expectRejection(Projection p, double lonDeg,
            double latDeg) {
        try {
            ProjCoordinate out = forward(p, lonDeg, latDeg);
            fail("expected rejection at " + lonDeg + " " + latDeg + " but got " + out);
            return null;
        } catch (ProjectionException e) {
            return e;
        }
    }

    // ------------------------------------------------- guyou and adams_hemi: one hemisphere

    /**
     * {@code |lam| - 1e-9 > pi/2} ({@code adams.cpp:119-122} and {@code :165-168}). 333
     * assertions each in {@code guyou.gie} and {@code adams_hemi.gie} — 47% of both files.
     *
     * <p>The {@code TOL} slack is on the accepting side: {@code 90} degrees exactly is fine, and
     * so is anything within {@code 1e-9} radians beyond it.
     */
    @Test
    public void guyouAndAdamsHemiRejectBeyondTheHemisphere() {
        Projection[] hemispheric = {
                initialized(new GuyouProjection()),
                initialized(new AdamsHemisphereProjection()),
        };
        for (Projection p : hemispheric) {
            String who = p.toString();
            assertNotNull(who, forward(p, 90.0, 10.0));
            assertNotNull(who, forward(p, -90.0, 10.0));
            // pi/2 + TOL exactly is still accepted: the predicate is strictly greater.
            assertNotNull(who, forward(p, (Math.PI / 2 + 1e-9) / DTR, 10.0));
            expectRejection(p, 90.0000001, 10.0);
            expectRejection(p, -90.0000001, 10.0);
            expectRejection(p, 179.0, 10.0);
        }
    }

    /**
     * {@code guyou} at the pole is a <em>success</em> returning the truncated literal, and it is
     * checked <em>after</em> the longitude guard — so a pole outside the hemisphere is still a
     * failure. {@code guyou.gie:2122-2131} is the {@code +R=1} block that pins the value.
     */
    @Test
    public void guyouPoleIsASuccessButOnlyInsideTheHemisphere() {
        GuyouProjection p = new GuyouProjection();
        p.setRadius(1);
        p.initialize();

        assertEquals(0.0, forward(p, 0, 90).x, 0.0);
        assertEquals(1.85407, forward(p, 0, 90).y, 0.0);
        assertEquals(0.0, forward(p, 0, -90).x, 0.0);
        assertEquals(-1.85407, forward(p, 0, -90).y, 0.0);

        expectRejection(p, 179.0, 90.0);
    }

    // ------------------------------------------- adams_ws1 / adams_ws2: no guard whatsoever

    /**
     * <b>Neither world-in-a-square variant has a projection-level domain check.</b> All 57 and 56
     * of their {@code expect failure} rows are PROJ's host-level pre-check on coordinate range.
     * Adding a guard to make one of those rows "pass" would be the wrong guard in the wrong place
     * and would break the whole-sphere coverage these two exist to provide.
     */
    @Test
    public void worldInASquareVariantsAcceptTheWholeSphere() {
        Projection[] global = {
                initialized(new AdamsWorldInASquareIProjection()),
                initialized(new AdamsWorldInASquareIIProjection()),
        };
        for (Projection p : global) {
            for (double lon = -180; lon <= 180; lon += 5) {
                for (double lat = -90; lat <= 90; lat += 5) {
                    ProjCoordinate out = forward(p, lon, lat);
                    assertTrue(p + " at " + lon + " " + lat + " -> " + out,
                            !Double.isNaN(out.x) && !Double.isNaN(out.y));
                }
            }
        }
    }

    // ------------------------------------------------ peirce_q: asymmetric hemisphere guards

    /**
     * {@code nhemisphere} rejects {@code phi < -TOL}; {@code shemisphere} rejects
     * {@code phi > -TOL}. The equator is therefore <b>accepted by the north and rejected by the
     * south</b>, which over the corpus's point grid is 37 rejections against 19. Not a typo
     * upstream — reproduced deliberately.
     */
    @Test
    public void peirceHemisphereGuardsAreAsymmetricAboutTheEquator() {
        PeirceQuincuncialProjection north = new PeirceQuincuncialProjection();
        north.setShape("nhemisphere");
        initialized(north);

        PeirceQuincuncialProjection south = new PeirceQuincuncialProjection();
        south.setShape("shemisphere");
        initialized(south);

        // The equator: accepted by nhemisphere, rejected by shemisphere.
        assertNotNull(forward(north, 0, 0));
        expectRejection(south, 0, 0);

        // A sliver south of the equator, inside TOL: still accepted by nhemisphere.
        double sliverDegrees = 0.5e-9 / DTR;
        assertNotNull(forward(north, 0, -sliverDegrees));

        assertNotNull(forward(north, 0, 45));
        expectRejection(north, 0, -45);
        assertNotNull(forward(south, 0, -45));
        expectRejection(south, 0, 45);
    }

    /** The other four shapes have no hemisphere guard at all. */
    @Test
    public void peirceOtherShapesHaveNoHemisphereGuard() {
        for (String shape : new String[] {"square", "diamond", "horizontal", "vertical"}) {
            PeirceQuincuncialProjection p = new PeirceQuincuncialProjection();
            p.setShape(shape);
            initialized(p);
            assertNotNull(shape, forward(p, 0, 80));
            assertNotNull(shape, forward(p, 0, -80));
            assertNotNull(shape, forward(p, 0, 0));
        }
    }

    // ------------------------------------------------------- the host-level pre-check (fwd.cpp)

    /**
     * {@code fwd.cpp:54-71}: {@code |phi| - pi/2 > 1e-12} radians and {@code |lam| > 10} radians
     * are rejected, and a latitude inside that {@code 1e-12} slop is clamped rather than
     * rejected. 245 of the six files' {@code expect failure} rows are this check and no
     * projection's own logic.
     *
     * <p>The bound is {@code 1e-12} <b>radians</b>, about {@code 5.7e-11} degrees. A
     * degree-space approximation of it misclassifies dozens of corpus points.
     */
    @Test
    public void hostLevelPreCheckIsInRadiansAndClampsInsideTheSlop() {
        Projection p = initialized(new AdamsWorldInASquareIProjection());

        // Inside the slop: clamped to the pole, not rejected.
        double insideSlop = (Math.PI / 2 + 0.9e-12) / DTR;
        assertNotNull(forward(p, 0, insideSlop));

        // Outside it: rejected.
        double outsideSlop = (Math.PI / 2 + 2e-12) / DTR;
        expectRejection(p, 0, outsideSlop);
        expectRejection(p, 0, 90.7265739758);
        expectRejection(p, 0, -105.0);

        // 10 radians, not 180 degrees: +/-573 degrees is the bound, so 400 degrees is legal
        // input to the host check and wraps.
        assertNotNull(forward(p, 400, 10));
        expectRejection(p, 600, 10);
    }

    /**
     * {@code adjlon} is applied, and it matters: {@code adams_ws1}/{@code adams_ws2} take
     * {@code sin(lam/2)}, whose period is {@code 4*pi}, so an unwrapped longitude past the
     * antimeridian gives the wrong sign rather than a rounding difference. The corpus feeds
     * longitudes up to {@code 180.96} degrees.
     */
    @Test
    public void longitudeIsWrappedIntoTheHalfOpenTurn() {
        Projection p = initialized(new AdamsWorldInASquareIProjection());
        ProjCoordinate wrapped = forward(p, 180.5, -30);
        ProjCoordinate equivalent = forward(p, -179.5, -30);
        assertEquals(equivalent.x, wrapped.x, 1e-9);
        assertEquals(equivalent.y, wrapped.y, 1e-9);
        assertTrue("180.5 degrees must land in the western half, not the eastern",
                wrapped.x < 0);
    }

    // -------------------------------------------------------------------------- inverses

    /**
     * {@code pj_adams_setup} installs an inverse for {@code adams_ws2} only, and
     * {@code peirce_q} only for {@code square} and {@code diamond}. Every other path must raise,
     * never echo the input back as a plausible coordinate.
     */
    @Test
    public void inverseAvailabilityMatchesUpstream() {
        assertFalse(initialized(new GuyouProjection()).hasInverse());
        assertFalse(initialized(new AdamsHemisphereProjection()).hasInverse());
        assertFalse(initialized(new AdamsWorldInASquareIProjection()).hasInverse());
        assertTrue(initialized(new AdamsWorldInASquareIIProjection()).hasInverse());
        assertTrue(new SpilhausProjection().hasInverse());

        for (String shape : new String[] {"square", "diamond"}) {
            PeirceQuincuncialProjection p = new PeirceQuincuncialProjection();
            p.setShape(shape);
            assertTrue(shape, initialized(p).hasInverse());
        }
        for (String shape :
                new String[] {"nhemisphere", "shemisphere", "horizontal", "vertical"}) {
            PeirceQuincuncialProjection p = new PeirceQuincuncialProjection();
            p.setShape(shape);
            initialized(p);
            assertFalse(shape, p.hasInverse());
            try {
                p.inverseProject(new ProjCoordinate(1e6, 1e6), new ProjCoordinate());
                fail("+shape=" + shape + " has no inverse and must raise");
            } catch (ProjectionException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("no inverse"));
            }
        }
    }

    /** The default shape is {@code diamond}, not {@code square}. The prose docs say otherwise. */
    @Test
    public void peirceDefaultShapeIsDiamond() {
        PeirceQuincuncialProjection defaulted = new PeirceQuincuncialProjection();
        assertEquals(PeirceQuincuncialProjection.Shape.DIAMOND, defaulted.getShape());

        PeirceQuincuncialProjection explicit = new PeirceQuincuncialProjection();
        explicit.setShape("diamond");
        initialized(defaulted);
        initialized(explicit);
        assertEquals(forward(explicit, 30, 40).x, forward(defaulted, 30, 40).x, 0.0);
        assertEquals(forward(explicit, 30, 40).y, forward(defaulted, 30, 40).y, 0.0);
    }

    // ---------------------------------------------------------------- setup-time validation

    /** {@code adams.cpp:448-453}. */
    @Test(expected = InvalidValueException.class)
    public void unknownShapeIsASetupError() {
        new PeirceQuincuncialProjection().setShape("hemisphere");
    }

    /** {@code adams.cpp:425-431} and {@code :439-445}; the bounds are inclusive. */
    @Test
    public void scrollBoundsAreInclusive() {
        PeirceQuincuncialProjection p = new PeirceQuincuncialProjection();
        p.setScrollX(1.0);
        p.setScrollX(-1.0);
        p.setScrollY(1.0);
        p.setScrollY(-1.0);
        try {
            p.setScrollX(1.0000001);
            fail("scrollx > 1 must be rejected");
        } catch (InvalidValueException expected) {
            assertTrue(expected.getMessage().contains("scrollx"));
        }
        try {
            p.setScrollY(-1.0000001);
            fail("scrolly < -1 must be rejected");
        } catch (InvalidValueException expected) {
            assertTrue(expected.getMessage().contains("scrolly"));
        }
    }

    /**
     * {@code +scrollx} is read only by {@code horizontal} and {@code +scrolly} only by
     * {@code vertical}. Setting the wrong one is not an error and has no effect.
     */
    @Test
    public void scrollIsIgnoredOffItsOwnShape() {
        PeirceQuincuncialProjection plain = new PeirceQuincuncialProjection();
        plain.setShape("vertical");
        initialized(plain);

        PeirceQuincuncialProjection withScrollX = new PeirceQuincuncialProjection();
        withScrollX.setShape("vertical");
        withScrollX.setScrollX(0.5);
        initialized(withScrollX);

        assertEquals(forward(plain, 30, 40).x, forward(withScrollX, 30, 40).x, 0.0);
        assertEquals(forward(plain, 30, 40).y, forward(withScrollX, 30, 40).y, 0.0);
    }

    /**
     * {@code pj_adams_setup} forces {@code P->es = 0}: the family is spherical whatever
     * ellipsoid is requested, but keeps the requested semi-major axis as the output scale.
     * {@code adams_ws2.gie:2125} asserts exactly that with {@code +proj=adams_ws2 +ellps=WGS84}.
     */
    @Test
    public void familyIsSphericalOnAnyEllipsoid() {
        AdamsWorldInASquareIIProjection wgs84 = new AdamsWorldInASquareIIProjection();
        wgs84.setEllipsoid(org.locationtech.proj4j.datum.Ellipsoid.WGS84);
        wgs84.initialize();

        AdamsWorldInASquareIIProjection sphere = new AdamsWorldInASquareIIProjection();
        sphere.setRadius(org.locationtech.proj4j.datum.Ellipsoid.WGS84.getEquatorRadius());
        sphere.initialize();

        assertEquals(forward(sphere, 40, 60).x, forward(wgs84, 40, 60).x, 0.0);
        assertEquals(forward(sphere, 40, 60).y, forward(wgs84, 40, 60).y, 0.0);
        // 40 60 -> 2021909.611 4162291.966 at adams_ws2.gie:2134, 1 mm.
        assertEquals(2021909.611, forward(wgs84, 40, 60).x, 0.001);
        assertEquals(4162291.966, forward(wgs84, 40, 60).y, 0.001);
    }

    /** All six are conformal; that is what the family is for. */
    @Test
    public void allSixAreConformal() {
        assertTrue(new GuyouProjection().isConformal());
        assertTrue(new PeirceQuincuncialProjection().isConformal());
        assertTrue(new AdamsHemisphereProjection().isConformal());
        assertTrue(new AdamsWorldInASquareIProjection().isConformal());
        assertTrue(new AdamsWorldInASquareIIProjection().isConformal());
        assertTrue(new SpilhausProjection().isConformal());
    }
}

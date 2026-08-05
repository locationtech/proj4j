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

package org.locationtech.proj4j.builtins2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.proj.CalCOFIProjection;
import org.locationtech.proj4j.proj.CentralConicProjection;
import org.locationtech.proj4j.proj.GaussSchreiberTransverseMercatorProjection;
import org.locationtech.proj4j.proj.InternationalMapOfTheWorldPolyconicProjection;
import org.locationtech.proj4j.proj.LabordeProjection;
import org.locationtech.proj4j.proj.LambertConformalConicAlternativeProjection;
import org.locationtech.proj4j.proj.ObliqueCylindricalEqualAreaProjection;
import org.locationtech.proj4j.proj.PerspectiveProjection;
import org.locationtech.proj4j.proj.Projection;
import org.locationtech.proj4j.proj.TiltedPerspectiveProjection;
import org.locationtech.proj4j.proj.TwoPointEquidistantProjection;

/**
 * Locks in the <em>contracts</em> of the operators added while working the {@code builtins.gie}
 * cluster &mdash; the setup rejections, the parameter defaults, the affine overrides and the
 * fidelity constants.
 *
 * <h2>Why these and not coordinates</h2>
 *
 * <p>The coordinates are already asserted, against unmodified vendored upstream data, by the
 * {@code conformance} module's {@code GieConformanceTest}: 127 {@code builtins.gie} assertions
 * across these nine operators. Re-transcribing those numbers into Java source would add no
 * evidence and would risk certifying a wrong implementation against a wrong transcription, which
 * is the specific failure the corpus harness exists to avoid.
 *
 * <p>What the corpus does <em>not</em> protect is the set of deliberate decisions that make those
 * numbers come out right and that a plausible tidy-up would silently undo. Each test below is one
 * of those, and each names the upstream site it reproduces. Several are cases where upstream's
 * behaviour looks like a bug: those are pinned so that "fixing" them fails loudly.
 */
public class NewOperatorContractTest {

    private final CRSFactory factory = new CRSFactory();

    private Projection projectionOf(String def) {
        CoordinateReferenceSystem crs = factory.createFromParameters(null, def);
        assertNotNull(def + " produced no CRS", crs);
        Projection p = crs.getProjection();
        assertNotNull(def + " produced no projection", p);
        return p;
    }

    // ---------------------------------------------------------------- registration

    /**
     * Every operator added here resolves through {@link Registry}, {@code tpers} now included.
     *
     * <h4>This assertion was INVERTED, deliberately</h4>
     *
     * <p>It used to end {@code assertEquals("tpers must stay unregistered until +azi/+tilt are
     * dispatched", null, registry.getProjection("tpers"))}, and that was the correct pin at the
     * time: {@code Proj4Parser} sent {@code +azi} to {@code SpilhausProjection} alone, so
     * registering {@code tpers} would have made {@code +proj=tpers +azi=20} parse cleanly, drop the
     * azimuth and return a silently unrotated map.
     *
     * <p>Both halves have since landed together &mdash; {@code Proj4Keyword.tilt}, a
     * {@code TiltedPerspectiveProjection} branch in {@code Proj4Parser} reading {@code +azi} and
     * {@code +tilt}, and the same {@code +azi} fan-out to {@code LabordeProjection}, which had the
     * identical hole open unnoticed. So the pin is stale, and the rule is to invert a stale pin
     * rather than delete or weaken it: the assertion now states the opposite fact and will fail
     * just as loudly if {@code tpers} is ever unregistered again.
     */
    @Test
    public void registryResolvesTheNewNamesAndTpers() {
        Registry registry = new Registry();
        assertSame("ccon", CentralConicProjection.class, registry.getProjection("ccon").getClass());
        assertSame("calcofi", CalCOFIProjection.class,
                registry.getProjection("calcofi").getClass());
        assertSame("gstmerc", GaussSchreiberTransverseMercatorProjection.class,
                registry.getProjection("gstmerc").getClass());
        assertSame("ocea", ObliqueCylindricalEqualAreaProjection.class,
                registry.getProjection("ocea").getClass());
        assertSame("tpeqd", TwoPointEquidistantProjection.class,
                registry.getProjection("tpeqd").getClass());
        assertSame("imw_p", InternationalMapOfTheWorldPolyconicProjection.class,
                registry.getProjection("imw_p").getClass());
        assertSame("lcca", LambertConformalConicAlternativeProjection.class,
                registry.getProjection("lcca").getClass());
        assertSame("labrd", LabordeProjection.class, registry.getProjection("labrd").getClass());
        assertSame("nsper", PerspectiveProjection.class,
                registry.getProjection("nsper").getClass());

        // Registered only once +azi AND +tilt could be dispatched to it. See this method's
        // javadoc; this line was `assertEquals(null, ...)` until that landed.
        assertSame("tpers", TiltedPerspectiveProjection.class,
                registry.getProjection("tpers").getClass());
    }

    // ---------------------------------------------------------------- nsper / tpers

    /**
     * {@code nsper} reads {@code +h}, which the base class refuses. This is the one change that
     * unblocked all twenty {@code builtins.gie} {@code nsper} rows.
     */
    @Test
    public void nsperAcceptsHeightOfOrbit() {
        Projection p = projectionOf("+proj=nsper +a=6400000 +h=1000000");
        assertEquals(1000000.0, p.getHeightOfOrbit(), 0.0);
    }

    /**
     * The {@code +h} bound is on {@code h/a}, not on {@code h} ({@code nsper.cpp:155-159}), so the
     * same height is legal on the Earth and illegal on a unit sphere. Two corpus rows assert the
     * rejections.
     */
    @Test
    public void nsperValidatesTheRatioNotTheHeight() {
        // Legal: h/a is about 0.156.
        projectionOf("+proj=nsper +a=6400000 +h=1000000");
        assertRejected("+proj=nsper +R=1 +h=0");
        assertRejected("+proj=nsper +R=1 +h=1e11");
        // ... and 1e11 metres is perfectly legal on a real ellipsoid: h/a is 1.6e4 < 1e10.
        projectionOf("+proj=nsper +a=6400000 +h=1e11");
    }

    /**
     * Every aspect must be selected from {@code +lat_0}. The class used to hard-assign
     * {@code EQUIT}, so the polar and oblique aspects answered the equatorial formulas; the tell
     * is that the north polar aspect rejects the equator while the equatorial one does not.
     */
    @Test
    public void nsperSelectsTheAspectFromLat0() {
        Projection polar = projectionOf("+proj=nsper +R=1 +h=3 +lat_0=90");
        try {
            polar.project(new ProjCoordinate(0, 0), new ProjCoordinate());
            fail("the north polar nsper cannot see (0, 0): xy.y = sin(0) = 0 < rp = 0.25");
        } catch (Proj4jException expected) {
            // nsper.cpp:61
        }
        Projection equatorial = projectionOf("+proj=nsper +R=1 +h=3");
        ProjCoordinate out = equatorial.project(new ProjCoordinate(0, 0), new ProjCoordinate());
        assertEquals("the equatorial aspect maps its own centre to the origin", 0.0, out.x, 1e-12);
        assertEquals(0.0, out.y, 1e-12);
    }

    /** {@code nsper} has an inverse upstream; the class used to answer {@code false}. */
    @Test
    public void nsperHasAnInverse() {
        assertTrue(projectionOf("+proj=nsper +a=6400000 +h=1000000").hasInverse());
    }

    /**
     * {@code tpers} is {@code nsper} with the tilt flag set, and its two angles must survive a
     * second {@code initialize()} &mdash; the parser calls it after the setters.
     */
    @Test
    public void tpersKeepsItsAnglesAcrossReinitialisation() {
        TiltedPerspectiveProjection p = new TiltedPerspectiveProjection();
        p.setHeightOfOrbit(1000000);
        p.setEllipsoid(new org.locationtech.proj4j.datum.Ellipsoid(
                "sphere", 6400000, 0.0, "test sphere"));
        p.setAziDegrees(20);
        p.setTiltDegrees(30);
        p.initialize();
        p.initialize();
        assertEquals(20 * Math.PI / 180.0, p.getAziRadians(), 0.0);
        assertEquals(30 * Math.PI / 180.0, p.getTiltRadians(), 0.0);
    }

    // ---------------------------------------------------------------- ccon

    /**
     * {@code |lat_1| > 0} is mandatory, and {@code pj_param} answers 0 for an absent key, so a
     * bare {@code +proj=ccon} is the same error ({@code ccon.cpp:86-89}).
     */
    @Test
    public void cconRequiresANonZeroLat1() {
        assertRejected("+proj=ccon +R=6390000");
        assertRejected("+proj=ccon +R=6390000 +lat_1=0");
        projectionOf("+proj=ccon +R=6390000 +lat_1=52");
    }

    /**
     * {@code ccon} does <b>not</b> read {@code +lat_0}, even though the corpus block supplies it
     * ({@code PROJ_HEAD} lists {@code lat_1=} alone). Two definitions differing only in
     * {@code +lat_0} must project identically.
     */
    @Test
    public void cconIgnoresLat0() {
        ProjCoordinate a = projectionOf("+proj=ccon +R=6390000 +lat_1=52")
                .project(new ProjCoordinate(24, 55), new ProjCoordinate());
        ProjCoordinate b = projectionOf("+proj=ccon +R=6390000 +lat_1=52 +lat_0=52")
                .project(new ProjCoordinate(24, 55), new ProjCoordinate());
        assertEquals(a.x, b.x, 0.0);
        assertEquals(a.y, b.y, 0.0);
    }

    // ---------------------------------------------------------------- calcofi

    /**
     * {@code calcofi} discards {@code +lon_0}, {@code +x_0} and {@code +y_0} and sets {@code a}
     * to 1 ({@code calcofi.cpp:136-141}, with upstream's own comment saying so). Three corpus
     * blocks assert it by giving {@code +lon_0=50} and expecting the bare answer.
     */
    @Test
    public void calcofiDiscardsLon0AndTheFalseOrigin() {
        ProjCoordinate bare = projectionOf("+proj=calcofi +ellps=GRS80")
                .project(new ProjCoordinate(10, 50), new ProjCoordinate());
        ProjCoordinate loud = projectionOf(
                "+proj=calcofi +ellps=GRS80 +lon_0=50 +x_0=10000 +y_0=500000")
                .project(new ProjCoordinate(10, 50), new ProjCoordinate());
        assertEquals("+lon_0/+x_0/+y_0 must be ignored", bare.x, loud.x, 0.0);
        assertEquals(bare.y, loud.y, 0.0);
        assertEquals("a is forced to 1", 1.0, projectionOf("+proj=calcofi +ellps=GRS80")
                .getEquatorRadius(), 0.0);
    }

    /**
     * {@code calcofi} still selects its kernel on {@code es}, so {@code +R=} changes the answer
     * even though it cannot change the scale.
     */
    @Test
    public void calcofiStillDistinguishesSphereFromEllipsoid() {
        ProjCoordinate ell = projectionOf("+proj=calcofi +ellps=GRS80")
                .project(new ProjCoordinate(10, 50), new ProjCoordinate());
        ProjCoordinate sph = projectionOf("+proj=calcofi +R=400")
                .project(new ProjCoordinate(10, 50), new ProjCoordinate());
        assertNotEquals("the spherical kernel is not the ellipsoidal one with es = 0",
                ell.x, sph.x);
    }

    /**
     * {@code +over} is honoured in the inverse, and {@code calcofi} sets it. Without it the four
     * corpus rows expecting {@code -207.447} and {@code -62.486} degrees come back wrapped, wrong
     * by a full turn and with no error raised.
     */
    @Test
    public void calcofiKeepsTheRevolutionCountInTheInverse() {
        Projection p = projectionOf("+proj=calcofi +ellps=GRS80");
        assertTrue("calcofi sets +over (calcofi.cpp:141)", p.isOver());
        ProjCoordinate out = p.inverseProject(new ProjCoordinate(-200, 100), new ProjCoordinate());
        assertTrue("longitude must stay outside +/-180 rather than being wrapped: " + out.x,
                out.x < -180.0);
    }

    /** {@code +over} must default false everywhere else, or the whole corpus moves. */
    @Test
    public void overDefaultsFalse() {
        assertTrue(!projectionOf("+proj=merc +ellps=GRS80").isOver());
        assertTrue(!projectionOf("+proj=vandg +a=6400000").isOver());
        assertTrue(!new CentralConicProjection().isOver());
    }

    // ---------------------------------------------------------------- ocea / tpeqd

    /**
     * {@code ocea} chooses its form on the <em>presence</em> of {@code +alpha}, not its value, so
     * {@code +alpha=0} selects the azimuth form ({@code pj_param}'s {@code 't'} sigil,
     * {@code ocea.cpp:62}). The corpus pairs {@code +lat_0=45 +alpha=0} with a two-point block
     * and requires the same point.
     */
    @Test
    public void oceaTreatsAlphaZeroAsPresent() {
        ProjCoordinate azimuth = projectionOf("+proj=ocea +a=6400000 +lat_0=45 +alpha=0")
                .project(new ProjCoordinate(2, 1), new ProjCoordinate());
        ProjCoordinate twoPoint = projectionOf(
                "+proj=ocea +a=6400000 +lat_1=45 +lat_2=45.0000001")
                .project(new ProjCoordinate(2, 1), new ProjCoordinate());
        assertEquals("+alpha=0 must select the azimuth form", azimuth.x, twoPoint.x, 1e-3);
        assertEquals(azimuth.y, twoPoint.y, 1e-3);
    }

    /** {@code ocea} derives its own central meridian, so {@code +lon_0} is overwritten. */
    @Test
    public void oceaOverwritesLon0() {
        ProjCoordinate bare = projectionOf("+proj=ocea +a=6400000 +lat_1=0.5 +lat_2=2")
                .project(new ProjCoordinate(2, 1), new ProjCoordinate());
        ProjCoordinate loud = projectionOf("+proj=ocea +a=6400000 +lat_1=0.5 +lat_2=2 +lon_0=37")
                .project(new ProjCoordinate(2, 1), new ProjCoordinate());
        assertEquals(bare.x, loud.x, 0.0);
        assertEquals(bare.y, loud.y, 0.0);
    }

    /**
     * {@code tpeqd} refuses two equal control points ({@code tpeqd.cpp:80-84}), which also covers
     * the absent case since every {@code pj_param} default is 0. The corpus's
     * {@code builtins.gie:7589} block &mdash;
     * {@code +lat_1=90 +lat_2=90 +lon_1=0 +lon_2=1} &mdash; <b>must BUILD</b>, and this assertion
     * was inverted to say so.
     *
     * <h4>Why it was inverted</h4>
     *
     * <p>It used to be an {@code assertRejected}, and it passed for the wrong reason: while
     * {@code +lon_1}/{@code +lon_2} were dispatched to {@code omerc} alone, both longitudes read 0
     * here, the two control points coincided, and the equal-points test fired. With the dispatch
     * widened to {@code ocea}, {@code tpeqd} and {@code imw_p}, {@code lon_2} really is
     * 1&deg; and the points are distinct, so the definition builds &mdash; <b>exactly as upstream's
     * does</b>. With {@code lat_1 = lat_2 = 90} and {@code lon_2 - lon_1 = 1}&deg; the Vincenty
     * numerator is about {@code 1.07e-18} rather than exactly zero, because {@code cos(M_HALFPI)}
     * is {@code 6.12e-17} and not 0, so upstream's exact {@code z02 == 0.0} comparison at
     * {@code tpeqd.cpp:104-108} does not fire either.
     *
     * <p>That corpus row's {@code expect failure} comes from somewhere else entirely, and the
     * inversion is what made it visible: the row carries <b>no {@code accept} of its own</b>, and
     * {@code gie}'s {@code operation} verb does not reset the pending input, so it inherits
     * {@code accept -200 -100} from the block above. Latitude &minus;100&deg; is refused by the
     * host-level angular contract of {@code fwd_prepare} ({@code fwd.cpp:56-70}), not by anything
     * in {@code tpeqd}. Asserted below.
     *
     * <p>The exact {@code == 0.0} comparison is still reproduced as an exact one, because that is
     * what upstream writes; it is exercised by {@code lat_1 = lat_2 = 90} with the longitudes left
     * equal.
     */
    @Test
    public void tpeqdRejectsCoincidentControlPoints() {
        assertRejected("+proj=tpeqd +a=6400000");
        assertRejected("+proj=tpeqd +a=6400000 +lat_1=10 +lat_2=10");
        // INVERTED: distinct control points, so the definition is legal here and upstream.
        Projection polar = projectionOf(
                "+proj=tpeqd +a=6400000 +lat_1=90 +lat_2=90 +lon_1=0 +lon_2=1");
        // ... and builtins.gie:7589's `expect failure` is the inherited `accept -200 -100`,
        // refused by the host-level latitude contract rather than by tpeqd.
        try {
            polar.project(new ProjCoordinate(-200, -100), new ProjCoordinate());
            fail("latitude -100 deg must be refused by the fwd_prepare angular contract");
        } catch (Proj4jException expected) {
            assertTrue("the message must name the latitude, not the control points: "
                            + expected.getMessage(),
                    expected.getMessage().contains("latitude"));
        }
        projectionOf("+proj=tpeqd +a=6400000 +lat_1=0.5 +lat_2=2");
    }

    // ---------------------------------------------------------------- imw_p / lcca / labrd

    /**
     * {@code imw_p} needs {@code |lat_1 - lat_2| > 0} and {@code |lat_1 + lat_2| > 0}, which
     * together also refuse an absent pair &mdash; but {@code +lat_1=0} on its own is legal and
     * selects {@code PHI_1_IS_ZERO} ({@code imw_p.cpp:46-55}, {@code :208-212}). The corpus
     * exercises both.
     */
    @Test
    public void imwPDistinguishesAbsentLatitudesFromAZeroOne() {
        assertRejected("+proj=imw_p +ellps=GRS80");
        assertRejected("+proj=imw_p +ellps=GRS80 +lat_1=2 +lat_2=2");
        assertRejected("+proj=imw_p +ellps=GRS80 +lat_1=-5 +lat_2=5");
        projectionOf("+proj=imw_p +ellps=GRS80 +lat_1=0 +lat_2=10");
        projectionOf("+proj=imw_p +ellps=GRS80 +lat_1=0.5 +lat_2=2");
    }

    /**
     * Absent {@code +lon_1}, {@code imw_p} substitutes 2&deg;, 4&deg; or 8&deg; from the mean
     * parallel ({@code imw_p.cpp:197-206}), so the sentinel must be {@code NaN} rather than 0:
     * 0 is a legal {@code +lon_1}.
     */
    @Test
    public void imwPLon1SentinelIsNaN() {
        InternationalMapOfTheWorldPolyconicProjection p =
                new InternationalMapOfTheWorldPolyconicProjection();
        assertTrue("an absent +lon_1 must be distinguishable from +lon_1=0",
                Double.isNaN(p.getLon1()));
    }

    /** Both {@code lcca} and {@code labrd} refuse {@code +lat_0 == 0}; a corpus row asserts each. */
    @Test
    public void lccaAndLabrdRefuseAZeroLat0() {
        assertRejected("+proj=lcca +ellps=GRS80");
        assertRejected("+proj=lcca +ellps=GRS80 +lat_0=0");
        assertRejected("+proj=labrd +ellps=GRS80 +lat_0=0");
        projectionOf("+proj=lcca +ellps=GRS80 +lat_0=1");
        projectionOf("+proj=labrd +ellps=GRS80 +lat_0=2 +lon_0=0.5");
    }

    /**
     * {@code lcca} reads neither {@code +lat_1} nor {@code +lat_2} despite being a conic
     * ({@code PROJ_HEAD} lists {@code lat_0=} alone), and the corpus block supplies both.
     */
    @Test
    public void lccaIgnoresTheStandardParallels() {
        ProjCoordinate bare = projectionOf("+proj=lcca +ellps=GRS80 +lat_0=1")
                .project(new ProjCoordinate(2, 1), new ProjCoordinate());
        ProjCoordinate loud = projectionOf("+proj=lcca +ellps=GRS80 +lat_0=1 +lat_1=0.5 +lat_2=2")
                .project(new ProjCoordinate(2, 1), new ProjCoordinate());
        assertEquals(bare.x, loud.x, 0.0);
        assertEquals(bare.y, loud.y, 0.0);
    }

    // ---------------------------------------------------------------- gstmerc

    /**
     * {@code gstmerc} folds {@code +k_0} into {@code n2} and must not have it applied twice: a
     * non-unit {@code +k_0} has to scale the easting by exactly that factor at small longitudes.
     */
    @Test
    public void gstmercAppliesK0Once() {
        ProjCoordinate one = projectionOf("+proj=gstmerc +R=6400000")
                .project(new ProjCoordinate(2, 1), new ProjCoordinate());
        ProjCoordinate half = projectionOf("+proj=gstmerc +R=6400000 +k_0=0.5")
                .project(new ProjCoordinate(2, 1), new ProjCoordinate());
        assertEquals("k_0 must scale once, not twice", 0.5, half.x / one.x, 1e-6);
    }

    // ---------------------------------------------------------------- aeqd

    /**
     * The spherical oblique {@code aeqd} must not collapse points near its own centre to the
     * origin: {@code aeqd.cpp}'s {@code TOL} is {@code 1e-14}, and within it upstream hands the
     * point to the <em>geodesic</em> forward rather than returning {@code (0, 0)}. With the old
     * {@code TOL = 1e-8} the dead zone had a radius of about 900 m.
     */
    @Test
    public void aeqdSphereDoesNotFlattenItsOwnNeighbourhood() {
        Projection p = projectionOf(
                "+proj=aeqd +a=6371008.771415 +b=6371008.771415 +lat_0=30.2345 +lon_0=-120.2345");
        ProjCoordinate out = p.project(
                new ProjCoordinate(-120.234501, 30.234501), new ProjCoordinate());
        double d = Math.hypot(out.x, out.y);
        assertTrue("a point 0.147 m from the centre must not project to the origin: " + out,
                d > 0.1 && d < 0.2);
        // The exact centre still does, in both libraries (aeqd.cpp:104-108).
        ProjCoordinate centre = p.project(
                new ProjCoordinate(-120.2345, 30.2345), new ProjCoordinate());
        assertEquals(0.0, centre.x, 0.0);
        assertEquals(0.0, centre.y, 0.0);
    }

    // ---------------------------------------------------------------- helpers

    private void assertRejected(String def) {
        try {
            Projection p = projectionOf(def);
            fail(def + " should have been refused, but built " + p);
        } catch (Proj4jException expected) {
            // The message names the upstream site; see each projection's initialize().
        }
    }
}

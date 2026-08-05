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

package org.locationtech.proj4j.parser.dispatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.parser.Proj4Keyword;
import org.locationtech.proj4j.proj.AiryProjection;
import org.locationtech.proj4j.proj.CassiniProjection;
import org.locationtech.proj4j.proj.EquidistantAzimuthalProjection;
import org.locationtech.proj4j.proj.HammerProjection;
import org.locationtech.proj4j.proj.InternationalMapOfTheWorldPolyconicProjection;
import org.locationtech.proj4j.proj.LabordeProjection;
import org.locationtech.proj4j.proj.LagrangeProjection;
import org.locationtech.proj4j.proj.LandsatProjection;
import org.locationtech.proj4j.proj.ObliqueCylindricalEqualAreaProjection;
import org.locationtech.proj4j.proj.Projection;
import org.locationtech.proj4j.proj.TiltedPerspectiveProjection;
import org.locationtech.proj4j.proj.TwoPointEquidistantProjection;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * The second wave of parameter dispatch: {@code +tilt}, {@code +over}, {@code +W}, {@code +M},
 * {@code +lsat}, {@code +path} on {@code lsat}, {@code +no_cut}, {@code +lat_b}, {@code +guam},
 * {@code +hyperbolic}, the {@code +azi} fan-out to {@code tpers} and {@code labrd}, and the
 * {@code +lon_1}/{@code +lon_2} fan-out to {@code ocea}, {@code tpeqd} and {@code imw_p}.
 *
 * <h2>The evidence has to be behavioural, not "it parsed"</h2>
 *
 * <p>{@code ParseMode.PROJ_COMPATIBLE} retains and ignores an unrecognised key, exactly as
 * {@code init.cpp} does, so "the definition was accepted" and "a projection was built" were both
 * already true before any of these were dispatched. Every assertion here is therefore of the form
 * <b>changing or removing the key changes the observable outcome</b>. See
 * {@link ParameterDispatchTest} for the same discipline applied to the first wave.
 *
 * <h2>Two traps this file is the regression net for</h2>
 *
 * <p><b>1. A key with more readers than you looked for.</b> {@code +W} was registered for
 * {@code lagrng} on the assumption that {@code lagrng} was its only reader. It is not:
 * {@code hammer.cpp:63-70} reads it too, and the omission turned
 * {@code builtins.gie:2596}'s {@code +proj=hammer +a=6400000 +W=1} from an honest
 * {@code NOT_IMPLEMENTED} into a <em>failed to fail</em> &mdash; the row asserts a domain error at
 * {@code (-180, 0)}, which the true {@code W = 1} forward raises and the dropped-to-{@code .5}
 * default does not. The corpus caught it; {@link #wReachesBothOfItsReaders()} is here so the
 * corpus does not have to next time. The cheap check before registering any key is
 * {@code git grep -nE 'pj_param[^"]*"[a-z]KEY"' 9.8.1 -- src/}.
 *
 * <p><b>2. {@code initialize()} runs TWICE</b> &mdash; once from a projection's own constructor and
 * once from {@code Proj4Parser} after it has finished setting parameters. A value written into a
 * field that {@code initialize()} also writes is silently discarded on the second pass, which is
 * why {@code +no_rot} could not be dispatched until {@code ObliqueMercatorProjection.rot} became a
 * field initialiser. There is a {@code …SurvivesTheSecondInitialize} assertion below for every
 * setter this wave newly reaches.
 *
 * @see ParameterDispatchTest
 */
public class FlagAndVariantDispatchTest {

    // ------------------------------------------------------------------ +proj=tpers

    private static final String TPERS = "+proj=tpers +a=6400000 +h=1000000";

    /**
     * {@code +azi} and {@code +tilt} each change the answer, so each is genuinely read.
     *
     * <p>This is the assertion that had to exist before {@code tpers} could be registered at all.
     * {@code Registry} deliberately withheld the name while {@code Proj4Parser} routed {@code +azi}
     * to {@code SpilhausProjection} alone, because {@code +proj=tpers +azi=20} would then have
     * projected as an un-rotated {@code nsper} &mdash; a plausible wrong map, which is strictly
     * worse than a missing operator.
     */
    @Test
    public void tpersAziAndTiltEachChangeTheAnswer() {
        String plain = outcome(TPERS, 2, 1);
        assertNotEquals("+azi was dropped on tpers", plain, outcome(TPERS + " +azi=20", 2, 1));
        assertNotEquals("+tilt was dropped on tpers", plain, outcome(TPERS + " +tilt=20", 2, 1));
        assertNotEquals("+azi and +tilt are not distinct on tpers",
                outcome(TPERS + " +azi=20", 2, 1), outcome(TPERS + " +tilt=20", 2, 1));
    }

    /**
     * Both are {@code pj_param}'s {@code r} sigil ({@code nsper.cpp:186-187}), so they land on the
     * fields in radians and accept every {@code dmstor} form &mdash; DMS, a trailing cardinal and
     * the {@code r}/{@code R} radian suffix that {@code Double.parseDouble} rejects outright.
     */
    @Test
    public void tpersAnglesAreReadAsAnglesInRadians() {
        TiltedPerspectiveProjection p =
                (TiltedPerspectiveProjection) projection(TPERS + " +azi=20 +tilt=30");
        assertEquals(20 * ProjectionMath.DTR, p.getAziRadians(), 1e-15);
        assertEquals(30 * ProjectionMath.DTR, p.getTiltRadians(), 1e-15);

        assertEquals("the r suffix is not reaching +tilt", outcome(TPERS + " +tilt=30", 2, 1),
                outcome(TPERS + " +tilt=" + (30 * ProjectionMath.DTR) + "r", 2, 1));
        assertEquals("DMS is not reaching +azi", outcome(TPERS + " +azi=20.5", 2, 1),
                outcome(TPERS + " +azi=20d30'", 2, 1));
        assertEquals("a trailing cardinal is not reaching +azi", outcome(TPERS + " +azi=-20", 2, 1),
                outcome(TPERS + " +azi=20W", 2, 1));
    }

    @Test
    public void tpersAnglesSurviveTheSecondInitialize() {
        TiltedPerspectiveProjection p = new TiltedPerspectiveProjection();
        p.setAziRadians(0.35);
        p.setTiltRadians(0.45);
        p.setHeightOfOrbit(1000000);
        p.initialize();
        p.initialize();
        assertEquals(0.35, p.getAziRadians(), 0.0);
        assertEquals(0.45, p.getTiltRadians(), 0.0);
    }

    /**
     * {@code labrd} reads {@code +azi} too ({@code labrd.cpp:117}, which rotates its complex
     * correction by {@code 2*Az}) and had been registered in {@code Registry} while {@code +azi}
     * reached {@code SpilhausProjection} alone &mdash; i.e. with the same hole open, silently. No
     * corpus row exercises {@code labrd +azi}, which is exactly why it survived unnoticed, and
     * exactly why this assertion is not redundant with the {@code tpers} ones above.
     */
    @Test
    public void labrdAziIsDispatched() {
        String base = "+proj=labrd +ellps=GRS80 +lon_0=0.5 +lat_0=2";
        assertNotEquals("+azi was dropped on labrd", outcome(base, 2, 1),
                outcome(base + " +azi=20", 2, 1));
        LabordeProjection p = (LabordeProjection) projection(base + " +azi=20");
        assertEquals(20 * ProjectionMath.DTR, p.getAziRadians(), 1e-15);
    }

    @Test
    public void labrdAziSurvivesTheSecondInitialize() {
        LabordeProjection p = new LabordeProjection();
        p.setProjectionLatitude(2 * ProjectionMath.DTR);
        p.setAziRadians(0.35);
        p.initialize();
        p.initialize();
        assertEquals(0.35, p.getAziRadians(), 0.0);
    }

    // ------------------------------------------------------------------ +over

    /**
     * {@code +over} is <b>global</b> ({@code init.cpp:601}), not operator-scoped, and it is read
     * with the {@code b} sigil &mdash; so a bare {@code +over} is true and {@code +over=f} is
     * explicitly false rather than a presence flag.
     *
     * <p>Asserted on the inverse, where {@code inv_finalize} ({@code inv.cpp:115-116}) is the only
     * consumer: {@code +over} keeps a longitude past the antimeridian instead of wrapping it, which
     * is the point &mdash; on a map drawn past 180&deg;, 200&deg;E and 160&deg;W are different
     * places on the page.
     */
    @Test
    public void overSuppressesTheInverseWrapAndIsABooleanSigil() {
        String base = "+proj=eqc +a=6400000";
        double past = 6400000 * 200 * ProjectionMath.DTR;   // 200 deg of easting

        ProjCoordinate wrapped = inverse(base, past, 0);
        ProjCoordinate unwrapped = inverse(base + " +over", past, 0);
        assertEquals("without +over the inverse must wrap to -160", -160.0, wrapped.x, 1e-9);
        assertEquals("with +over the inverse must stay at 200", 200.0, unwrapped.x, 1e-9);

        assertEquals("+over=f must be OFF: it is pj_param's 'b' sigil, not a presence flag",
                -160.0, inverse(base + " +over=f", past, 0).x, 1e-9);
        assertEquals("+over=T must be ON", 200.0, inverse(base + " +over=T", past, 0).x, 1e-9);
    }

    /**
     * A bad {@code b}-sigil value is an error rather than a silent default, matching
     * {@code param.cpp}: anything other than empty, {@code T}/{@code t} or {@code F}/{@code f}
     * sets {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE}.
     */
    @Test
    public void overRejectsANonBooleanValue() {
        assertRejects("+proj=eqc +a=6400000 +over=yes", "over");
    }

    /**
     * The forward honours it too. {@code fwd_prepare} skips <em>both</em> of its {@code adjlon}
     * calls under {@code +over} ({@code fwd.cpp:82-83}, {@code :109-111}); this class's forward
     * funnels wrap only when {@code lon_0 != 0}, so this assertion is specifically that
     * {@code +over} suppresses that one and does <b>not</b> also suppress the subtraction of
     * {@code lon_0} &mdash; folding the two into one guard would silently change the central
     * meridian instead of the wrapping.
     */
    @Test
    public void overSuppressesTheForwardWrapWithoutLosingLon0() {
        String base = "+proj=eqc +a=6400000 +lon_0=10";
        double eastingAt200 = forward(base + " +over", 200, 0).x;
        // 200 - 10 = 190 degrees from the central meridian, NOT wrapped to -170 and not 200.
        assertEquals(6400000 * 190 * ProjectionMath.DTR, eastingAt200, 1e-6);
        assertEquals("without +over the same point must wrap to -170 from the central meridian",
                6400000 * -170 * ProjectionMath.DTR, forward(base, 200, 0).x, 1e-6);
    }

    /**
     * {@code vandg_s_forward} is the one projection whose <em>own</em> kernel reads
     * {@code P->over} ({@code vandg.cpp:25-27}): past the antimeridian the (29-3) auxiliary
     * {@code A} changes sign, because the map continues outward instead of folding back. So
     * {@code +over} on {@code vandg} is not merely "do not wrap" &mdash; it is a different easting.
     */
    @Test
    public void vandgForwardReadsOverItself() {
        String base = "+proj=vandg +a=6400000";
        // 179.9 is inside the antimeridian, so +over cannot change it.
        assertEquals("+over must not change a point inside the antimeridian",
                forward(base, 179.9, 50).x, forward(base + " +over", 179.9, 50).x, 1e-6);
        // 180.1 is outside it, so it must -- and must not merely equal the 179.9 answer.
        double over = forward(base + " +over", 180.1, 50).x;
        assertTrue("+over past the antimeridian must give a LARGER easting than 179.9 does, "
                        + "not the same one: got " + over,
                over > forward(base, 179.9, 50).x + 1000.0);
    }

    // ------------------------------------------------------------------ +W and +M

    /**
     * {@code +W} has <b>two</b> readers upstream, {@code lagrng} and {@code hammer}, and
     * dispatching it to only one of them was measured to break the corpus. See this class's
     * javadoc.
     */
    @Test
    public void wReachesBothOfItsReaders() {
        String lagrng = "+proj=lagrng +a=6400000";
        assertNotEquals("+W was dropped on lagrng", outcome(lagrng, 2, 1),
                outcome(lagrng + " +W=1.5", 2, 1));
        assertEquals(1.5, ((LagrangeProjection) projection(lagrng + " +W=1.5")).getW(), 0.0);

        String hammer = "+proj=hammer +a=6400000";
        assertNotEquals("+W was dropped on hammer -- this is the omission that turned "
                        + "builtins.gie:2596 into a failed-to-fail",
                outcome(hammer, 2, 1), outcome(hammer + " +W=1", 2, 1));
        assertNotEquals("+M was dropped on hammer", outcome(hammer, 2, 1),
                outcome(hammer + " +M=2", 2, 1));

        HammerProjection h = (HammerProjection) projection(hammer + " +W=1 +M=2");
        assertEquals(1.0, h.getW(), 0.0);
        assertEquals(2.0, h.getM(), 0.0);
    }

    /**
     * The consequence that was actually observed: at {@code (-180, 0)} the {@code W = 1} forward is
     * singular and the {@code W = .5} default is not, so a dropped {@code +W} answers a plausible
     * easting where PROJ raises a domain error. {@code builtins.gie:2596} is exactly this row.
     */
    @Test
    public void hammerWEqualsOneIsSingularAtTheAntimeridian() {
        try {
            ProjCoordinate out = forward("+proj=hammer +a=6400000 +W=1", -180, 0);
            fail("+proj=hammer +W=1 must refuse (-180, 0): 1 + cos(phi)cos(W*lam) is exactly "
                    + "zero there. It projected to " + out.x + " " + out.y + ", which means +W "
                    + "was dropped and the .5 default was used -- exactly the failed-to-fail "
                    + "this test exists to pin.");
        } catch (Proj4jException expected) {
            // hammer_s_forward's `denom == 0` guard, cass-style domain error.
        }
        // ... and the default is NOT singular there, which is why the omission was invisible:
        // a dropped +W answers a plausible easting where PROJ raises a domain error.
        forward("+proj=hammer +a=6400000", -180, 0);
    }

    /** {@code W <= 0} is a hard error in both readers, though only {@code hammer} takes fabs. */
    @Test
    public void wIsRangeCheckedByBothReaders() {
        assertRejects("+proj=lagrng +a=6400000 +W=0", "W");
        assertRejects("+proj=hammer +a=6400000 +W=0", "W");
        assertRejects("+proj=hammer +a=6400000 +M=0", "M");
        // hammer fabs's them, so a negative value is legal there and lagrng rejects it.
        forward("+proj=hammer +a=6400000 +W=-1", 2, 1);
    }

    @Test
    public void wAndMSurviveTheSecondInitialize() {
        LagrangeProjection l = new LagrangeProjection();
        l.setW(1.5);
        l.initialize();
        l.initialize();
        assertEquals(1.5, l.getW(), 0.0);

        HammerProjection h = new HammerProjection();
        h.setW(1.0);
        h.setM(2.0);
        h.initialize();
        h.initialize();
        assertEquals("hammer's initialize() derives Q->m = M/W; if getM() drifts it has written "
                + "the derived value back over the parameter", 2.0, h.getM(), 0.0);
        assertEquals(1.0, h.getW(), 0.0);
    }

    // ------------------------------------------------------------------ +lsat and +path

    /**
     * {@code lsat} hard-coded {@code land = 1} and {@code path = 120} into <em>locals</em> behind a
     * {@code //FIXME}, so there was nothing for a setter to write and
     * {@code +proj=lsat +lsat=5 +path=2} silently returned Landsat 1 path 120's map. That is why
     * the conformance bridge classified {@code +path} as conditionally honoured rather than
     * honoured.
     */
    @Test
    public void lsatAndPathEachChangeTheAnswer() {
        String base = "+proj=lsat +ellps=GRS80";
        assertNotEquals("+path was dropped on lsat", outcome(base, 2, 1),
                outcome(base + " +path=2", 2, 1));
        assertNotEquals("+lsat was dropped", outcome(base, 2, 1),
                outcome(base + " +lsat=5", 2, 1));

        LandsatProjection p = (LandsatProjection) projection(base + " +lsat=5 +path=2");
        assertEquals(5, p.getLandsat());
        assertEquals(2, p.getPath());
    }

    /**
     * Both are {@code pj_param}'s {@code i} sigil, whose grammar is decimal digits and nothing
     * else: {@code param.cpp:180-187} runs {@code atoi} and then rejects the value if <em>any</em>
     * character is outside {@code 0-9}, so a sign, a decimal point and trailing text are all errors
     * rather than partial parses.
     */
    @Test
    public void lsatAndPathUseTheStrictIntegerGrammar() {
        assertRejects("+proj=lsat +ellps=GRS80 +path=12a", "path");
        assertRejects("+proj=lsat +ellps=GRS80 +path=-5", "path");
        assertRejects("+proj=lsat +ellps=GRS80 +path=1.0", "path");
        assertRejects("+proj=lsat +ellps=GRS80 +lsat=2a", "lsat");
    }

    /** Upstream's two range checks: {@code lsat} in {@code [1,5]}, {@code path} vehicle-dependent. */
    @Test
    public void lsatAndPathAreRangeChecked() {
        assertRejects("+proj=lsat +ellps=GRS80 +lsat=6", "lsat");
        assertRejects("+proj=lsat +ellps=GRS80 +lsat=0", "lsat");
        assertRejects("+proj=lsat +ellps=GRS80 +lsat=1 +path=252", "path");
        assertRejects("+proj=lsat +ellps=GRS80 +lsat=5 +path=234", "path");
        // 251 is legal for vehicles 1-3 and not for 4-5, which is the whole point of the
        // vehicle-dependent bound.
        forward("+proj=lsat +ellps=GRS80 +lsat=3 +path=251", 2, 1);
        assertRejects("+proj=lsat +ellps=GRS80 +lsat=4 +path=251", "path");
    }

    @Test
    public void lsatAndPathSurviveTheSecondInitialize() {
        LandsatProjection p = new LandsatProjection();
        p.setLandsat(5);
        p.setPath(2);
        p.initialize();
        p.initialize();
        assertEquals(5, p.getLandsat());
        assertEquals(2, p.getPath());
    }

    // ------------------------------------------------------------------ airy

    /**
     * {@code +no_cut} is a {@code b} sigil and {@code +lat_b} an {@code r} ({@code airy.cpp:119-120}).
     * {@code +no_cut} is asserted through the far hemisphere, which {@code airy} rejects by default
     * and projects with the flag &mdash; a difference between refusing and answering, which is the
     * strongest possible evidence the flag was read.
     */
    @Test
    public void airyNoCutAndLatBAreDispatched() {
        String base = "+proj=airy +R=1 +lat_0=-90";
        assertRejects(base, "hemisphere");
        forward(base + " +no_cut", 2, 1);
        assertEquals("+no_cut=f must be OFF: it is a 'b' sigil", outcome(base, 2, 1),
                outcome(base + " +no_cut=f", 2, 1));

        String equatorial = "+proj=airy +R=1";
        assertNotEquals("+lat_b was dropped", outcome(equatorial, 2, 1),
                outcome(equatorial + " +lat_b=45", 2, 1));
        assertEquals(45 * ProjectionMath.DTR,
                ((AiryProjection) projection(equatorial + " +lat_b=45")).getLatB(), 1e-15);
    }

    @Test
    public void airyFlagsSurviveTheSecondInitialize() {
        AiryProjection p = new AiryProjection();
        p.setNoCut(true);
        p.setLatB(0.5);
        p.initialize();
        p.initialize();
        assertTrue("setNoCut must survive a second initialize(); it used to be overwritten with "
                + "false inside initialize() behind a //FIXME", p.isNoCut());
        assertEquals(0.5, p.getLatB(), 0.0);
    }

    // ------------------------------------------------------------------ +guam

    /**
     * {@code +guam} swaps both of {@code aeqd}'s kernels ({@code aeqd.cpp:301-304}), and does so
     * <b>only on the ellipsoidal branch</b> &mdash; upstream reads the key inside the
     * {@code es != 0} arm, so on a declared sphere it is consumed and ignored rather than being an
     * error or a different projection.
     */
    @Test
    public void guamAppliesOnAnEllipsoidAndIsInertOnASphere() {
        String ell = "+proj=aeqd +ellps=clrk66 +lat_0=13.47246633333333 "
                + "+lon_0=144.74875069444445";
        assertNotEquals("+guam was dropped on aeqd", outcome(ell, 144.6, 13.3),
                outcome(ell + " +guam", 144.6, 13.3));
        assertTrue(((EquidistantAzimuthalProjection) projection(ell + " +guam")).isGuam());

        String sph = "+proj=aeqd +R=6378206.4 +lat_0=13.47246633333333 "
                + "+lon_0=144.74875069444445";
        assertEquals("+guam must be inert on a declared sphere, as it is upstream",
                outcome(sph, 144.6, 13.3), outcome(sph + " +guam", 144.6, 13.3));

        assertEquals("+guam=f must be OFF: it is a 'b' sigil", outcome(ell, 144.6, 13.3),
                outcome(ell + " +guam=f", 144.6, 13.3));
    }

    /** The Guam kernels are mutual inverses, which the truncated series only is near the origin. */
    @Test
    public void guamRoundTrips() {
        String ell = "+proj=aeqd +guam +ellps=clrk66 +lat_0=13.47246633333333 "
                + "+lon_0=144.74875069444445 +x_0=50000 +y_0=50000";
        ProjCoordinate xy = forward(ell, 144.635331291666660, 13.33903846111111);
        ProjCoordinate lp = inverse(ell, xy.x, xy.y);
        assertEquals(144.635331291666660, lp.x, 1e-8);
        assertEquals(13.33903846111111, lp.y, 1e-8);
    }

    @Test
    public void guamSurvivesTheSecondInitialize() {
        EquidistantAzimuthalProjection p = new EquidistantAzimuthalProjection();
        p.setGuam(true);
        p.initialize();
        p.initialize();
        assertTrue(p.isGuam());
    }

    // ------------------------------------------------------------------ +hyperbolic

    /**
     * {@code +hyperbolic} is read with {@code pj_param_exists} and <b>not</b> with the {@code b}
     * sigil ({@code cass.cpp:127}), which makes it the one flag in this file where
     * {@code +hyperbolic=f} is <em>true</em>: presence is the whole test. That asymmetry with
     * {@code +no_cut}, {@code +guam} and {@code +over} is upstream's, and it is what a
     * "flags are booleans" generalisation gets wrong.
     */
    @Test
    public void hyperbolicIsPresenceOnlyNotBoolean() {
        String base = "+proj=cass +a=6378306.376305601 +rf=293.466307 +lat_0=-16.25 "
                + "+lon_0=179.33333333333333";
        String plain = outcome(base, 180, -16.8);
        assertNotEquals("+hyperbolic was dropped on cass", plain, outcome(base + " +hyperbolic", 180, -16.8));
        assertEquals("+hyperbolic=f must be TRUE: upstream reads it with pj_param_exists, so "
                        + "presence is the whole test and the value is never looked at",
                outcome(base + " +hyperbolic", 180, -16.8),
                outcome(base + " +hyperbolic=f", 180, -16.8));
        assertTrue(((CassiniProjection) projection(base + " +hyperbolic")).isHyperbolic());
    }

    /** Inert on a sphere, matching {@code PJ_PROJECTION(cass)}, which returns before reading it. */
    @Test
    public void hyperbolicIsInertOnASphere() {
        String sph = "+proj=cass +R=6378306.376305601 +lat_0=-16.25";
        assertEquals(outcome(sph, 180, -16.8), outcome(sph + " +hyperbolic", 180, -16.8));
    }

    /**
     * {@code cass_e_inverse} refines with {@code pj_generic_inverse_2d} unconditionally
     * ({@code cass.cpp:81}), seeded from the non-hyperbolic series, so the hyperbolic inverse comes
     * out of Newton on the hyperbolic forward and cannot drift from it by construction.
     */
    @Test
    public void hyperbolicRoundTrips() {
        String base = "+proj=cass +hyperbolic +a=6378306.376305601 +rf=293.466307 "
                + "+lat_0=-16.25 +lon_0=179.33333333333333";
        ProjCoordinate xy = forward(base, 179.99433652777776, -16.841456527777776);
        ProjCoordinate lp = inverse(base, xy.x, xy.y);
        assertEquals(179.99433652777776, lp.x, 1e-9);
        assertEquals(-16.841456527777776, lp.y, 1e-9);
    }

    @Test
    public void hyperbolicSurvivesTheSecondInitialize() {
        CassiniProjection p = new CassiniProjection();
        p.setHyperbolic(true);
        p.initialize();
        p.initialize();
        assertTrue(p.isHyperbolic());
    }

    // ------------------------------------------------------------------ +lon_1 / +lon_2

    /**
     * {@code +lon_1}/{@code +lon_2} have four readers, not one. They were dispatched to
     * {@code omerc} while {@code ocea}, {@code tpeqd} and {@code imw_p} read {@code 0} silently and
     * the conformance bridge listed both keys as honoured &mdash; i.e. the bridge vouched for a key
     * three of the four readers dropped.
     *
     * <p>{@code builtins.gie} makes it visible on {@code ocea}, whose five two-point blocks use
     * {@code +lon_2=1e-8}, {@code -1e-8} and {@code 1e-5} to select the east, west and north-east
     * framings. With the key dropped all three collapsed onto the north one.
     */
    @Test
    public void lonOneAndLonTwoReachAllFourReaders() {
        String ocea = "+proj=ocea +a=6400000 +lat_1=45 +lat_2=45";
        assertNotEquals("+lon_2 was dropped on ocea: east and west must not coincide",
                outcome(ocea + " +lon_1=0 +lon_2=1e-8", 2, 1),
                outcome(ocea + " +lon_1=0 +lon_2=-1e-8", 2, 1));
        ObliqueCylindricalEqualAreaProjection o = (ObliqueCylindricalEqualAreaProjection)
                projection(ocea + " +lon_1=1 +lon_2=2");
        assertEquals(1 * ProjectionMath.DTR, o.getLon1(), 1e-15);
        assertEquals(2 * ProjectionMath.DTR, o.getLon2(), 1e-15);

        String tpeqd = "+proj=tpeqd +a=6400000 +lat_1=10 +lat_2=20";
        assertNotEquals("+lon_1/+lon_2 were dropped on tpeqd", outcome(tpeqd, 2, 1),
                outcome(tpeqd + " +lon_1=5 +lon_2=15", 2, 1));
        TwoPointEquidistantProjection t = (TwoPointEquidistantProjection)
                projection(tpeqd + " +lon_1=5 +lon_2=15");
        assertEquals(5 * ProjectionMath.DTR, t.getLon1(), 1e-15);
        assertEquals(15 * ProjectionMath.DTR, t.getLon2(), 1e-15);

        String imwp = "+proj=imw_p +ellps=GRS80 +lat_1=0 +lat_2=10";
        assertNotEquals("+lon_1 was dropped on imw_p", outcome(imwp, 2, 1),
                outcome(imwp + " +lon_1=5", 2, 1));
        InternationalMapOfTheWorldPolyconicProjection i =
                (InternationalMapOfTheWorldPolyconicProjection) projection(imwp + " +lon_1=5");
        assertEquals(5 * ProjectionMath.DTR, i.getLon1(), 1e-15);
    }

    /**
     * {@code imw_p} reads {@code +lon_1} and <b>not</b> {@code +lon_2} ({@code imw_p.cpp:191-192}),
     * and for it absence is <em>not</em> zero: {@code lam_1} is derived from the latitudes instead
     * ({@code imw_p.cpp:191}). So a 0 default would be a different projection, not a missing
     * parameter.
     */
    @Test
    public void imwPAbsentLonOneIsNotZero() {
        String imwp = "+proj=imw_p +ellps=GRS80 +lat_1=0 +lat_2=10";
        assertNotEquals("absent +lon_1 must derive lam_1 from the latitudes, not default to 0",
                outcome(imwp, 2, 1), outcome(imwp + " +lon_1=0", 2, 1));
        assertTrue("absent +lon_1 must stay NaN so initialize() can tell it from zero",
                Double.isNaN(((InternationalMapOfTheWorldPolyconicProjection) projection(imwp))
                        .getLon1()));
    }

    /**
     * All four setters take radians and all four keys are {@code r} sigils, so the whole
     * {@code dmstor} grammar has to work &mdash; including the {@code r} suffix, which
     * {@code Double.parseDouble} rejects outright.
     */
    @Test
    public void lonOneAcceptsEveryDmstorForm() {
        String tpeqd = "+proj=tpeqd +a=6400000 +lat_1=10 +lat_2=20 +lon_2=15";
        assertEquals("the r suffix is not reaching +lon_1", outcome(tpeqd + " +lon_1=5", 2, 1),
                outcome(tpeqd + " +lon_1=" + (5 * ProjectionMath.DTR) + "r", 2, 1));
        assertEquals("DMS is not reaching +lon_1", outcome(tpeqd + " +lon_1=5.5", 2, 1),
                outcome(tpeqd + " +lon_1=5d30'", 2, 1));
        assertEquals("a trailing cardinal is not reaching +lon_1",
                outcome(tpeqd + " +lon_1=-5", 2, 1), outcome(tpeqd + " +lon_1=5W", 2, 1));
    }

    @Test
    public void lonOneAndLonTwoSurviveTheSecondInitialize() {
        ObliqueCylindricalEqualAreaProjection o = new ObliqueCylindricalEqualAreaProjection();
        o.setProjectionLatitude1(10 * ProjectionMath.DTR);
        o.setProjectionLatitude2(20 * ProjectionMath.DTR);
        o.setLon1(0.1);
        o.setLon2(0.2);
        o.initialize();
        o.initialize();
        assertEquals(0.1, o.getLon1(), 0.0);
        assertEquals(0.2, o.getLon2(), 0.0);

        TwoPointEquidistantProjection t = new TwoPointEquidistantProjection();
        t.setProjectionLatitude1(10 * ProjectionMath.DTR);
        t.setProjectionLatitude2(20 * ProjectionMath.DTR);
        t.setLon1(0.1);
        t.setLon2(0.2);
        t.initialize();
        t.initialize();
        assertEquals(0.1, t.getLon1(), 0.0);
        assertEquals(0.2, t.getLon2(), 0.0);

        InternationalMapOfTheWorldPolyconicProjection i =
                new InternationalMapOfTheWorldPolyconicProjection();
        i.setProjectionLatitude1(0);
        i.setProjectionLatitude2(10 * ProjectionMath.DTR);
        i.setLon1(0.1);
        i.initialize();
        i.initialize();
        assertEquals(0.1, i.getLon1(), 0.0);
    }

    // ------------------------------------------------------------------ the allow-list

    /**
     * Every key this wave dispatches is in {@code Proj4Keyword.supportedParameters()}, and the
     * three halves &mdash; the allow-list, {@code Proj4Parser}'s dispatch and the conformance
     * bridge's {@code HONOURED} &mdash; must move in <b>one</b> change. Registering a key the
     * parser ignores makes the bridge call a definition executable and return a plausible wrong
     * answer instead of an honest {@code NOT_IMPLEMENTED}; the reverse merely loses coverage.
     */
    @Test
    public void everyKeyInThisWaveIsRegistered() {
        for (String key : new String[] {Proj4Keyword.tilt, Proj4Keyword.over, Proj4Keyword.W,
                Proj4Keyword.M, Proj4Keyword.lsat, Proj4Keyword.path, Proj4Keyword.no_cut,
                Proj4Keyword.lat_b, Proj4Keyword.guam, Proj4Keyword.hyperbolic,
                Proj4Keyword.lon_1, Proj4Keyword.lon_2, Proj4Keyword.azi}) {
            assertTrue("+" + key + " is dispatched by Proj4Parser but is not in "
                    + "Proj4Keyword.supportedParameters(), so STRICT mode refuses it",
                    Proj4Keyword.isSupported(key));
        }
    }

    /**
     * {@code +theta} is deliberately NOT registered. It is read by {@code oea} alone
     * ({@code oea.cpp:74}) and {@code oea} is not ported &mdash; {@code Registry}'s line for it is
     * still commented out &mdash; so there is nothing to dispatch it to. Registering an operator's
     * key before the operator exists is the mirror of the {@code tpers} trap and gains nothing:
     * {@code +proj=oea} is refused on the name.
     *
     * <p>{@code +n} and {@code +m}, {@code oea}'s other two, are registered for {@code urmfps},
     * {@code urm5}, {@code gn_sinu} and {@code fouc_s} and are unaffected.
     */
    @Test
    public void thetaIsDeliberatelyNotRegisteredBecauseOeaIsNotPorted() {
        assertFalse("+theta must stay unregistered while +proj=oea is unported. Invert this "
                        + "assertion in the same change that ports it.",
                Proj4Keyword.isSupported("theta"));
        assertRejects("+proj=oea +a=6400000 +n=1 +m=2 +theta=3", "oea");
    }

    // -------------------------------------------------------------------- helpers

    private static Projection projection(String definition) {
        CoordinateReferenceSystem crs =
                new CRSFactory().createFromParameters("dispatch", definition);
        return crs.getProjection();
    }

    private static ProjCoordinate forward(String definition, double lon, double lat) {
        ProjCoordinate out = new ProjCoordinate();
        projection(definition).project(new ProjCoordinate(lon, lat), out);
        return out;
    }

    private static ProjCoordinate inverse(String definition, double x, double y) {
        ProjCoordinate out = new ProjCoordinate();
        projection(definition).inverseProject(new ProjCoordinate(x, y), out);
        return out;
    }

    /**
     * The observable outcome of projecting one point: the coordinate, or the exception's class and
     * message. Folded into one string because "did this key change anything?" is answered yes
     * whether the change is a different number or the difference between answering and refusing.
     */
    private static String outcome(String definition, double lon, double lat) {
        try {
            ProjCoordinate out = forward(definition, lon, lat);
            return out.x + " " + out.y;
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /**
     * Asserts that a definition refuses {@code (2, 1)} &mdash; or refuses to be built &mdash; with
     * {@code expectedInMessage} named in the reason. The message check is what stops the assertion
     * passing for the wrong reason, which several of these would.
     */
    private static void assertRejects(String definition, String expectedInMessage) {
        try {
            ProjCoordinate out = forward(definition, 2, 1);
            fail("expected " + definition + " to be refused, but it projected (2, 1) to "
                    + out.x + " " + out.y);
        } catch (Proj4jException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            assertTrue(definition + " was refused, but for an unexpected reason -- \""
                            + expectedInMessage + "\" does not appear in: "
                            + e.getClass().getSimpleName() + ": " + message,
                    message.toLowerCase().contains(expectedInMessage.toLowerCase()));
        }
    }
}

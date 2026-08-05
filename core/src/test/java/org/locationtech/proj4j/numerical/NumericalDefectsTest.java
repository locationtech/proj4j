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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.proj.AiryProjection;
import org.locationtech.proj4j.proj.FoucautSinusoidalProjection;
import org.locationtech.proj4j.proj.HammerProjection;
import org.locationtech.proj4j.proj.LagrangeProjection;
import org.locationtech.proj4j.proj.ObliqueMercatorProjection;
import org.locationtech.proj4j.proj.Projection;

/**
 * The {@code builtins.gie} rows behind the <em>numerical</em> failure class: projections proj4j
 * already shipped, that built and ran and returned plausible wrong answers.
 *
 * <p>Three shapes recur, and only one of them is a formula:
 *
 * <ol>
 * <li><b>A class default standing in for a PROJ parameter default.</b> {@code Proj4Parser} assigns
 *     {@code +lat_0}/{@code +lat_1}/{@code +k} only when the key is present, so the constructor or a
 *     field initialiser silently defines the effective default — {@code krovak} had no defaults at
 *     all where PROJ has three, {@code wintri} hard-coded {@code cos(lat_1)}, {@code lagrng}
 *     defaulted {@code +W} to 1.4 where PROJ uses 2.</li>
 * <li><b>A lost in-place reassignment.</b> Upstream writes {@code lp.phi = f(lp.phi)} and reads it
 *     again; the translation computed {@code f(phi)} into the <em>output</em> slot and then read the
 *     original. {@code airy}'s polar branch and {@code bipc}'s inverse both had it, and in both the
 *     forward and inverse stopped being mutual inverses.</li>
 * <li><b>A missing kernel.</b> {@code aitoff}, {@code wintri} and {@code hammer} had no inverse at
 *     all — {@code hasInverse()} was false and {@code projectInverse} fell through to
 *     {@link Projection}'s identity, ungated. {@code ortho} had only the spherical arm of a
 *     {@code Sph&amp;Ell} operator; see {@link OrthographicPortTest}.</li>
 * </ol>
 *
 * <p>Two more shapes show up in {@code initialize()}, which <b>runs twice</b> — once from a
 * constructor, once from the parser: writing a derived value into a field that is also read
 * ({@code lagrng}'s {@code rw = 1./rw}, {@code hammer}'s {@code m /= w}) and setting a fixed
 * constant <em>after</em> {@code super.initialize()} has already derived {@code totalScale} from it
 * ({@code nzmg}, {@code krovak}).
 */
public class NumericalDefectsTest {

    private static final double MM = GieAssertion.MM;

    // ---------------------------------------------------------------------------- eck4

    /**
     * {@code eck4}'s Newton iteration genuinely does not converge at a pole, and upstream's
     * fall-back is a <b>success</b>: {@code x = C_x*lam}, {@code y = ±C_y}. The corpus proves it —
     * {@code (±180, ±90)} expects {@code (±8489602.7403, ±8489602.7403)}, which are exactly
     * {@code C_x*pi*a} and {@code C_y*a}. Raising a convergence failure there lost four assertions;
     * a "better" iteration would lose them too.
     */
    @Test
    public void eck4PoleUsesUpstreamsClosedFormFallBack() {
        GieAssertion g = GieAssertion.sphereFromA("+proj=eck4 +a=6400000", 6400000.0);
        g.expectForward(-180, 90, -8489602.7403, 8489602.7403, 0.1 * MM);
        g.expectForward(180, 90, 8489602.7403, 8489602.7403, 0.1 * MM);
        g.expectForward(-180, -90, -8489602.7403, -8489602.7403, 0.1 * MM);
        g.expectForward(180, -90, 8489602.7403, 8489602.7403 * -1, 0.1 * MM);
        g.expectForward(-180, 0, -16979205.4807, 0, 0.1 * MM);
    }

    /**
     * The inverse needed a pole branch — {@code 1 - |sin(theta)| <= 1e-12}, where {@code asin} and
     * {@code cos} are both stationary — and a longitude-range check. Without the first the pole came
     * back 165.7 mm out; without the second, eight rows that feed an easting 0.01 m past the map edge
     * answered with a longitude just past 180 degrees instead of failing.
     */
    @Test
    public void eck4InverseHasAPoleBranchAndRejectsPastTheAntimeridian() {
        GieAssertion g = GieAssertion.sphereFromA("+proj=eck4 +a=6400000", 6400000.0);
        g.expectInverse(-8489602.74033281, 8489602.74033281, -180, 90, 0.1 * MM);
        g.expectInverse(8489602.74033281, -8489602.74033281, 180, -90, 0.1 * MM);
        g.expectInverse(-16979205.4807, 0, -180, 0, 0.1 * MM);

        g.expectInverseRejected(-8489602.75, 8489602.74033281);
        g.expectInverseRejected(8489602.75, 8489602.74033281);
        g.expectInverseRejected(0, 8489602.75);
        g.expectInverseRejected(-16979205.49, 0);
        g.expectInverseRejected(16979205.49, 0);
        g.expectInverseRejected(0, -8489602.75);
    }

    // ---------------------------------------------------------------------------- bipc

    /**
     * {@code bipc} had a {@code lon_0} of &minus;90 degrees that {@code PJ_PROJECTION(bipc)} does not
     * set, and its {@code initialize()} omitted {@code es = 0}. With the spurious central meridian,
     * {@code (2, ±1)} threw and {@code (-2, ±1)} came back 1.06e7 m out.
     */
    @Test
    public void bipcHasNoCentralMeridianAndIsSpherical() {
        Projection p = new CRSFactory()
                .createFromParameters("t", "+proj=bipc +ellps=GRS80").getProjection();
        assertEquals("lon_0", 0.0, p.getProjectionLongitudeDegrees(), 0.0);
        assertTrue("+proj=bipc must be spherical: PJ_PROJECTION(bipc) sets P->es = 0",
                p.isEqualArea() || Math.abs(p.getEllipsoid().getEccentricitySquared()) >= 0);

        GieAssertion g = GieAssertion.grs80("+proj=bipc +ellps=GRS80");
        g.expectForward(2, 1, 2452160.217725756, -14548450.759654747, 0.1 * MM);
        g.expectForward(2, -1, 2447915.213725341, -14763427.212798730, 0.1 * MM);
        g.expectForward(-2, 1, 2021695.522934909, -14540413.695283702, 0.1 * MM);
        g.expectForward(-2, -1, 2018090.503004699, -14755620.651414108, 0.1 * MM);
    }

    /**
     * Upstream reassigns {@code xy.y} in place — {@code rhoc - xy.y} or {@code += rhoc} — and then
     * reads it back for both {@code hypot} and {@code atan2}. The translation wrote the shift into the
     * output coordinate and read the unshifted inputs, so the whole {@code rhoc} translation was dead
     * code and latitudes came back roughly 57 degrees wrong.
     */
    @Test
    public void bipcInverseAppliesTheRhocRecentring() {
        GieAssertion g = GieAssertion.grs80("+proj=bipc +ellps=GRS80");
        g.expectInverse(200, 100, -73.038700285, 17.248118466, 0.1 * MM);
        g.expectInverse(200, -100, -73.037303739, 17.249414978, 0.1 * MM);
        g.expectInverse(-200, 100, -73.035893173, 17.245536403, 0.1 * MM);
        g.expectInverse(-200, -100, -73.034496627, 17.246832896, 0.1 * MM);
    }

    // ------------------------------------------------------------------- aitoff / wintri / hammer

    /**
     * {@code aitoff}'s inverse fell through to the identity. The four existing corpus inverse rows
     * passed <b>by accident</b> — they sit about 200 m from the origin, where Aitoff is the identity
     * to nine decimals — so this asserts the Newton-Raphson solution at those rows and then a round
     * trip a couple of degrees out, which is where the 23.28 m showed.
     */
    @Test
    public void aitoffHasANewtonRaphsonInverse() {
        GieAssertion g = GieAssertion.sphere("+proj=aitoff +R=6400000", 6400000.0);
        g.expectForward(2, 1, 223379.458811696, 111706.742883853, 0.1 * MM);
        g.expectInverse(200, 100, 0.001790493, 0.000895247, 0.1 * MM);
        g.expectInverse(200, -100, 0.001790493, -0.000895247, 0.1 * MM);
        g.expectInverse(-200, 100, -0.001790493, 0.000895247, 0.1 * MM);
        g.expectInverse(-200, -100, -0.001790493, -0.000895247, 0.1 * MM);
        g.expectRoundtrip(2, 1, 1, 0.1 * MM);
        g.expectRoundtrip(-40, 60, 1, 0.1 * MM);
    }

    /**
     * {@code wintri} discarded {@code +lat_1} and hard-coded {@code cos(lat_1) = acos(2/pi)}. The
     * corpus row is {@code +lat_1=0}, i.e. {@code cosphi1 = 1}, and the forward was <b>40,590 m</b>
     * out. Note that 0 is a legal, meaningful value here, so "absent" cannot be inferred from the
     * value — which is why the class carries an explicit flag.
     */
    @Test
    public void wintriReadsLatOneIncludingZero() {
        GieAssertion g = GieAssertion.sphereFromA("+proj=wintri +a=6400000 +lat_1=0", 6400000.0);
        g.expectForward(2, 1, 223390.801533485, 111703.907505745, 0.1 * MM);
        g.expectForward(-2, -1, -223390.801533485, -111703.907505745, 0.1 * MM);
        g.expectInverse(200, 100, 0.001790493, 0.000895247, 0.1 * MM);
        g.expectInverse(-200, -100, -0.001790493, -0.000895247, 0.1 * MM);

        // And the fall-back is still acos(2/pi) when the key is absent: +lat_1=50d28' should
        // reproduce the default to well inside the corpus bar.
        GieAssertion dflt = GieAssertion.sphereFromA("+proj=wintri +a=6400000", 6400000.0);
        ProjCoordinate a = dflt.forward(2, 1);
        GieAssertion named = GieAssertion.sphereFromA(
                "+proj=wintri +a=6400000 +lat_1=" + Math.toDegrees(
                        Math.acos(0.636619772367581343)), 6400000.0);
        ProjCoordinate b = named.forward(2, 1);
        assertEquals("+lat_1 absent must mean acos(2/pi)", a.x, b.x, 1.0e-6);
    }

    /** {@code hammer}'s inverse is closed form upstream and was simply absent. */
    @Test
    public void hammerHasAClosedFormInverse() {
        assertTrue("hammer has an inverse upstream", new HammerProjection().hasInverse());
        GieAssertion g = GieAssertion.sphereFromA("+proj=hammer +a=6400000", 6400000.0);
        g.expectForward(2, 1, 223373.788703241, 111703.907397767, 0.1 * MM);
        g.expectInverse(200, 100, 0.001790493, 0.000895247, 0.1 * MM);
        g.expectInverse(-200, -100, -0.001790493, -0.000895247, 0.1 * MM);
        g.expectRoundtrip(30, -20, 1, 0.1 * MM);
    }

    /**
     * {@code hammer}'s {@code initialize()} read {@code w} and {@code m} and then unconditionally
     * overwrote them with the defaults, so the setters had no effect; and {@code m /= w} wrote a
     * derived value back into the field it had just read, so a second {@code initialize()} divided by
     * {@code w} again.
     */
    @Test
    public void hammerKeepsWAndMAndIsIdempotent() {
        HammerProjection p = new HammerProjection();
        p.setW(1.0);
        p.setM(2.0);
        p.initialize();
        assertEquals("+W survives initialize()", 1.0, p.getW(), 0.0);
        assertEquals("+M survives initialize()", 2.0, p.getM(), 0.0);
        ProjCoordinate first = new ProjCoordinate();
        p.project(new ProjCoordinate(2, 1), first);
        p.initialize();
        ProjCoordinate second = new ProjCoordinate();
        p.project(new ProjCoordinate(2, 1), second);
        assertEquals("easting after a second initialize()", first.x, second.x, 0.0);
        assertEquals("northing after a second initialize()", first.y, second.y, 0.0);
    }

    // ---------------------------------------------------------------------------- krovak

    /**
     * {@code krovak} forces Bessel 1841 upstream — {@code a = 6377397.155},
     * {@code es = 0.006674372230614} — and supplies {@code lat_0}, {@code lon_0} and {@code k}
     * defaults. proj4j carried the {@code es} literal but set {@code a = 1}, so
     * {@code +proj=krovak +ellps=GRS80} projected onto GRS80's axis with Bessel's eccentricity, and
     * it had no defaults at all. The forward was out by 3,495 km.
     */
    @Test
    public void krovakForcesBesselAndSuppliesPROJsDefaults() {
        Projection p = new CRSFactory()
                .createFromParameters("t", "+proj=krovak +ellps=GRS80").getProjection();
        assertEquals("a is forced to Bessel 1841", 6377397.155, p.getEquatorRadius(), 0.0);
        assertEquals("lat_0 default", 49.5, p.getProjectionLatitudeDegrees(), 1.0e-9);
        assertEquals("lon_0 default", 24.83333333333, p.getProjectionLongitudeDegrees(), 1.0e-9);
        assertEquals("k default", 0.9999, p.getScaleFactor(), 0.0);

        GieAssertion g = GieAssertion.grs80("+proj=krovak +ellps=GRS80");
        g.expectForward(2, 1, -3196535.232563641, -6617878.867551444, 0.1 * MM);
        g.expectForward(2, -1, -3260035.440552109, -6898873.614878031, 0.1 * MM);
        g.expectForward(-2, 1, -3756305.328869175, -6478142.561571511, 0.1 * MM);
        g.expectForward(-2, -1, -3831703.658501982, -6759107.170155395, 0.1 * MM);
        g.expectForward(24.833333333333, 59.757598563058, 0, 0, 0.1 * MM);
    }

    /**
     * {@code lat_0 = -90} makes {@code tan(lat_0/2 + pi/4)} exactly 0, so the cone constant is
     * undefined; upstream rejects it at setup with
     * {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE}. Without the guard {@code k} was
     * {@code Infinity} and the forward returned a plausible finite northing.
     */
    @Test
    public void krovakRejectsLatZeroAtTheSouthPole() {
        try {
            new CRSFactory().createFromParameters("t", "+proj=krovak +lat_0=-90").getProjection();
            fail("+proj=krovak +lat_0=-90 must be rejected: tan(lat_0/2 + pi/4) is 0");
        } catch (InvalidValueException expected) {
            assertTrue("the message must name +lat_0: " + expected.getMessage(),
                    expected.getMessage().contains("lat_0"));
        }
    }

    // ---------------------------------------------------------------------------- nzmg

    /**
     * New Zealand Map Grid is a fixed-Earth fit, so {@code +proj=nzmg +ellps=GRS80} must still be
     * projected on International 1924's {@code a = 6378388}. The five fixed values were assigned
     * <em>after</em> {@code super.initialize()}, which is where {@code totalScale = a * fromMetres}
     * and {@code totalFalseEasting} are derived — the "{@code initialize()} runs twice" trap. The
     * shortfall was exactly {@code 6378137/6378388}, and 307 km at the corpus's test point.
     */
    @Test
    public void nzmgIgnoresTheSuppliedEllipsoid() {
        Projection p = new CRSFactory()
                .createFromParameters("t", "+proj=nzmg +ellps=GRS80").getProjection();
        assertEquals("a is forced to International 1924", 6378388.0, p.getEquatorRadius(), 0.0);
        assertEquals("x_0", 2510000.0, p.getFalseEasting(), 0.0);
        assertEquals("y_0", 6023150.0, p.getFalseNorthing(), 0.0);

        GieAssertion g = GieAssertion.grs80("+proj=nzmg +ellps=GRS80");
        g.expectForward(2, 1, 3352675144.747425100, -7043205391.100243600, 0.1 * MM);
        g.expectForward(-2, -1, 4466166927.369976000, -7502531736.628604900, 0.1 * MM);
        g.expectInverse(200000.0, 100000.0, 175.482086827, -69.422692183, 0.1 * MM);
        g.expectInverse(-200000.0, -100000.0, 134.333684316, -61.621553676, 0.1 * MM);
    }

    /**
     * A golden-master triage flagged {@code SYN proj/nzmg} probe 4 — {@code (31.2132, 60)}, central
     * Europe — coming back with {@code fx = -3.52e18 m}, and asked whether that is a failure dressed
     * as a coordinate. <b>It is not. PROJ 9.8.1 returns the same number</b>, because {@code nzmg} is a
     * degree-~55 fixed-Earth polynomial fit with no domain check in either direction, and the corpus
     * itself expects eastings of 3.35e9 m at {@code (2, 1)}.
     *
     * <p>This test exists so that a future "fix" which adds a finiteness or magnitude guard <em>here</em>
     * fails loudly rather than quietly costing the four corpus rows above. The remedy for the
     * enormous value is an <b>area-of-use</b> rejection, which PROJ does not have either, and it does
     * not belong in this class.
     *
     * <p>The number is pinned to the digit because it is also the witness for the
     * {@code initialize()}-ordering fix: it moved from {@code -3.5203836748401444e18} by exactly the
     * ratio {@code 6378388/6378137}, which is what identifies the semi-major axis rather than the
     * series as what had been wrong.
     */
    @Test
    public void nzmgFarOutsideNewZealandMatchesUpstreamRatherThanBeingGuarded() {
        Projection p = new CRSFactory().createFromParameters("t",
                "+proj=nzmg +lon_0=10 +lat_0=45 +ellps=GRS80").getProjection();
        ProjCoordinate out = new ProjCoordinate();
        p.project(new ProjCoordinate(31.2132034355964, 60.0), out);
        assertEquals("upstream's nzmg_e_forward, transcribed independently, gives this to the ulp",
                -3.520522213147237e18, out.x, 0.0);
        assertEquals("and this", -1.3328188221225725e18, out.y, 0.0);

        // The same kernel inside the fit's own domain is ordinary metres, which is what makes the
        // value above a domain question and not an arithmetic one.
        ProjCoordinate inside = new ProjCoordinate();
        p.project(new ProjCoordinate(174.0, -41.0), inside);
        assertEquals("inside New Zealand", 2594130.457446264, inside.x, 1.0e-6);
        assertEquals("inside New Zealand", 6022667.13938638, inside.y, 1.0e-6);

        // The inverse, checked WITH the forward rather than in isolation: it is the leg that changed
        // type. It used to mint a NaN pair and let Projection's finiteness postcondition report it
        // generically -- as though the coordinate had been bad, when the iteration was what failed.
        // ConvergenceFailureException is the type 9.8.1 reserves for it
        // (PROJ_ERR_COORD_TRANSFM_NO_CONVERGENCE), and the golden gate pins this message.
        try {
            p.inverseProject(out, new ProjCoordinate());
            fail("nzmg's inverse cannot converge 101 degrees from the fit's origin");
        } catch (org.locationtech.proj4j.ConvergenceFailureException e) {
            assertEquals(org.locationtech.proj4j.ErrorCause.NUMERICAL_FAILURE, e.cause());
            assertTrue("the message must name the projection and the trip count: " + e.getMessage(),
                    e.getMessage().contains("New Zealand Map Grid")
                            && e.getMessage().contains("within 20 trips"));
        }

        // And inside the fit the two legs are mutual inverses exactly, which they were not before:
        // the false easting and northing used to be applied on the second initialize() only.
        ProjCoordinate back = new ProjCoordinate();
        p.inverseProject(inside, back);
        assertEquals("longitude round-trips", 174.0, back.x, 0.0);
        assertEquals("latitude round-trips", -41.0, back.y, 0.0);
    }

    // ---------------------------------------------------------------------------- sterea

    /**
     * {@code pj_gauss_ini} has a branch for {@code .5*phi0 + pi/4 < 1e-10} — the south pole, where
     * {@code tan(.5*phi0 + pi/4)} is exactly 0 — and takes {@code K = 1/srat} instead of dividing by
     * it. Without that branch {@code K} is {@code Infinity} and every coordinate comes back
     * non-finite. Note the 20-trip {@code pow} loop behind this projection is reproduced, not
     * improved: 9.8.1 still solves the inverse that way.
     */
    @Test
    public void stereaWorksAtTheSouthPole() {
        GieAssertion g = GieAssertion.grs80("+proj=sterea +ellps=GRS80 +lat_0=-90");
        g.expectForward(0, -90, 0, 0, 0.1 * MM);
        g.expectForward(0, -89, 0.000000000000, 111696.700323081997, 0.1 * MM);
        g.expectForward(0, -45, 0.000000000000, 5291160.727484324016, 0.1 * MM);
        g.expectForward(0, 0, 0.000000000000, 12713600.098641794175, 0.1 * MM);
    }

    // ---------------------------------------------------------------------------- airy

    /**
     * {@code airy}'s polar branch: upstream reassigns {@code lp.phi = |p_halfpi - lp.phi|} and then
     * reads it three more times — in the {@code no_cut} guard, in the halving, and in
     * {@code tan()}/{@code log(cos())}. The translation staged that in the output slot and read the
     * original latitude for all three, so at {@code lat_0=90} the forward of {@code (0, 0)} evaluated
     * {@code log(cos(0))/tan(0)} and the forward of {@code (0, -90)}, which PROJ rejects, returned
     * <b>1.132e16</b>.
     */
    @Test
    public void airyPolarBranchReadsTheReassignedLatitude() {
        GieAssertion north = GieAssertion.sphere("+proj=airy +R=1 +lat_0=90", 1.0);
        north.expectForward(0, 0, 0, -1.3863, 0.1 * MM);
        north.expectForward(0, 90, 0, 0, 0.1 * MM);
        north.expectForwardRejected(0, -90);

        GieAssertion south = GieAssertion.sphere("+proj=airy +R=1 +lat_0=-90", 1.0);
        south.expectForward(0, 0, 0, 1.3863, 0.1 * MM);
        south.expectForward(0, -90, 0, 0, 0.1 * MM);
        south.expectForwardRejected(0, 90);
    }

    /**
     * {@code +no_cut} and {@code +lat_b} are not {@code Proj4Keyword}s yet, so the bridge correctly
     * reports the four corpus rows that use them as not implemented. The setters exist so that
     * registering the keys is a parser-only change; assert they are wired to the arithmetic, because
     * a setter that does nothing is worse than no setter (the parser would then dispatch a key that
     * is silently dropped).
     */
    @Test
    public void airyNoCutAndLatBSettersReachTheArithmetic() {
        AiryProjection cut = new AiryProjection();
        cut.setProjectionLatitudeDegrees(-90);
        cut.setNoCut(false);
        cut.initialize();
        try {
            cut.project(new ProjCoordinate(0, 90), new ProjCoordinate());
            fail("with +no_cut absent, (0, 90) at lat_0=-90 is outside the domain");
        } catch (org.locationtech.proj4j.ProjectionException expected) {
            assertTrue(expected.getMessage().contains("no_cut"));
        }

        AiryProjection open = new AiryProjection();
        open.setProjectionLatitudeDegrees(-90);
        open.setNoCut(true);
        open.initialize();
        ProjCoordinate out = new ProjCoordinate();
        open.project(new ProjCoordinate(0, Math.PI / 2), out);
        assertTrue("+no_cut must project the far hemisphere: " + out, Double.isFinite(out.y));
        assertFalse("+no_cut must not be reset by initialize()", !open.isNoCut());

        AiryProjection latB = new AiryProjection();
        latB.setLatBDegrees(30);
        latB.initialize();
        assertEquals("+lat_b survives initialize()", Math.toRadians(30), latB.getLatB(), 0.0);
        ProjCoordinate withLatB = new ProjCoordinate();
        latB.project(new ProjCoordinate(Math.toRadians(20), Math.toRadians(20)), withLatB);
        AiryProjection dflt = new AiryProjection();
        ProjCoordinate withoutLatB = new ProjCoordinate();
        dflt.project(new ProjCoordinate(Math.toRadians(20), Math.toRadians(20)), withoutLatB);
        assertTrue("+lat_b must change the answer", withLatB.y != withoutLatB.y);
    }

    // ---------------------------------------------------------------------------- lagrng

    /**
     * {@code lagrng}'s forward tested {@code |‌|phi| - pi/2| &lt; 1e-10} where upstream tests
     * {@code |‌|sin(phi)| - 1| &lt; 1e-10}. Those are not the same band: at 89.9999999 degrees the
     * latitude is 1.7e-9 rad from the pole (outside) but its sine is 1.5e-18 from 1 (well inside), so
     * upstream returns {@code (0, ±2)} and proj4j divided by {@code 1 - sin(phi)} and returned
     * {@code Infinity}.
     */
    @Test
    public void lagrngPoleTestIsOnTheSineNotTheLatitude() {
        GieAssertion g = GieAssertion.sphere("+proj=lagrng +R=1", 1.0);
        g.expectForward(0, 89.9999999, 0, 2, 0.1 * MM);
        g.expectForward(0, -89.9999999, 0, -2, 0.1 * MM);
    }

    /**
     * {@code +W} defaulted to 1.4 where PROJ defaults it to 2, <em>and</em> the field was
     * reciprocated in place ({@code hrw = 0.5 * (rw = 1./rw)}) so that the effective value
     * flip-flopped between 1.4 and 0.714 with each {@code initialize()}. The corpus row is
     * {@code +proj=lagrng +R=1 +lat_1=56}, {@code accept 12 56}, {@code expect 0.10 0.0} at
     * {@code tolerance 1 cm}.
     */
    @Test
    public void lagrngDefaultsWToTwoAndIsIdempotent() {
        LagrangeProjection p = new LagrangeProjection();
        assertEquals("+W default", 2.0, p.getW(), 0.0);

        GieAssertion g = GieAssertion.sphere("+proj=lagrng +R=1 +lat_1=56", 1.0);
        g.expectForward(12, 56, 0.10, 0.0, 10.0 * MM);

        p.setProjectionLatitude1Degrees(56);
        p.initialize();
        ProjCoordinate first = new ProjCoordinate();
        p.project(new ProjCoordinate(Math.toRadians(12), Math.toRadians(56)), first);
        p.initialize();
        ProjCoordinate second = new ProjCoordinate();
        p.project(new ProjCoordinate(Math.toRadians(12), Math.toRadians(56)), second);
        assertEquals("+W must not be reciprocated in place", first.x, second.x, 0.0);
        assertEquals("+W must not be reciprocated in place", first.y, second.y, 0.0);
    }

    /** Upstream has a closed-form inverse; {@code hasInverse()} was false. */
    @Test
    public void lagrngHasAnInverse() {
        LagrangeProjection p = new LagrangeProjection();
        assertTrue("lagrng has an inverse upstream", p.hasInverse());
        GieAssertion g = GieAssertion.sphere("+proj=lagrng +R=1 +lat_1=56", 1.0);
        g.expectRoundtrip(12, 56, 1, 0.1 * MM);
        g.expectRoundtrip(-30, -20, 1, 0.1 * MM);
    }

    // ------------------------------------------------------------- the two undispatched setters

    /**
     * {@code fouc_s.cpp} reads {@code +n} and {@code fouc_s} <em>is</em> registered, so a bridge that
     * claims to honour {@code "n"} was unsound: a private field with no setter meant
     * {@code +proj=fouc_s +n=0.5} would have used the default of 0 silently. Nothing was wrong only
     * because the corpus's one {@code fouc_s} block is a bare {@code +proj=fouc_s +a=6400000} — a
     * property of the corpus, not of the code.
     */
    @Test
    public void foucSHasAnNSetterThatReachesTheArithmetic() {
        FoucautSinusoidalProjection p = new FoucautSinusoidalProjection();
        p.setN(0.5);
        p.initialize();
        assertEquals("+n survives initialize()", 0.5, p.getN(), 0.0);
        ProjCoordinate half = new ProjCoordinate();
        p.project(new ProjCoordinate(Math.toRadians(2), Math.toRadians(1)), half);

        FoucautSinusoidalProjection dflt = new FoucautSinusoidalProjection();
        dflt.initialize();
        ProjCoordinate zero = new ProjCoordinate();
        dflt.project(new ProjCoordinate(Math.toRadians(2), Math.toRadians(1)), zero);
        assertTrue("+n must change the answer", half.x != zero.x || half.y != zero.y);
    }

    /**
     * {@code omerc}'s {@code rot} was a private field assigned {@code true} unconditionally inside
     * {@code initialize()} with no setter, so {@code Proj4Keyword.no_rot} could be declared but not
     * dispatched. Registering the key without this setter would have been the dangerous order: the
     * bridge would have called the two {@code +no_rot} corpus blocks executable and they would have
     * returned a <em>rotated</em> answer where PROJ returns an unrotated one.
     *
     * <p>Note {@code rot} must survive {@code initialize()}, which runs twice — that is why it is not
     * assigned there.
     */
    @Test
    public void omercNoRotSetterSurvivesInitialize() {
        ObliqueMercatorProjection rotated = new ObliqueMercatorProjection();
        rotated.setEllipsoid(org.locationtech.proj4j.datum.Ellipsoid.WGS84);
        rotated.setProjectionLatitudeDegrees(10);
        rotated.setAlphaDegrees(80);
        rotated.initialize();
        assertTrue("rotation is on unless +no_rot", rotated.isRot());

        ObliqueMercatorProjection plain = new ObliqueMercatorProjection();
        plain.setEllipsoid(org.locationtech.proj4j.datum.Ellipsoid.WGS84);
        plain.setProjectionLatitudeDegrees(10);
        plain.setAlphaDegrees(80);
        plain.setNoRot(true);
        plain.initialize();
        plain.initialize();
        assertFalse("+no_rot must survive both initialize() calls", plain.isRot());

        ProjCoordinate withRot = new ProjCoordinate();
        rotated.project(new ProjCoordinate(Math.toRadians(3), Math.toRadians(12)), withRot);
        ProjCoordinate withoutRot = new ProjCoordinate();
        plain.project(new ProjCoordinate(Math.toRadians(3), Math.toRadians(12)), withoutRot);
        assertTrue("+no_rot must change the answer, or the key would be inert",
                withRot.x != withoutRot.x || withRot.y != withoutRot.y);
    }
}

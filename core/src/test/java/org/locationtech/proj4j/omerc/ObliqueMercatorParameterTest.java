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
package org.locationtech.proj4j.omerc;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.proj.Projection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code +proj=omerc} parameter precedence and rotation, against PROJ 9.8.1.
 *
 * <p>Every expected value in this class was produced by PROJ 9.8.1 on the definition quoted beside
 * it, or taken from {@code conformance/src/test/resources/gie/builtins.gie} where upstream pins one.
 * None was produced by Proj4J.
 *
 * <p><b>Use {@code proj}, not {@code cs2cs}, for a pure-projection reference.</b> Learned the hard way
 * on {@code +proj=omerc +lat_0=10 +R=6400000 +gamma=80}: {@code proj} gives
 * {@code 397886.061495506379 -954179.578580341535} and {@code cs2cs} from
 * {@code +proj=longlat +R=6400000} gives {@code -951174.356619503465 -394577.681672655453}.
 * {@code cs2cs} promotes both sides to CRSs, and for two <em>datum-less</em> spherical definitions
 * that is not the bare projection. Where a real ellipsoid is named the two agree to 1e-9 m; where one
 * is not, {@code proj} is the tool that answers the question this class asks.
 *
 * <h2>The two defects this class exists to hold down</h2>
 *
 * <p>Both were <b>silent</b>, both were in {@code ObliqueMercatorProjection.initialize()}, and
 * neither was reachable by any live test in the repository:
 *
 * <ol>
 * <li>{@code Gamma} was declared as a plain {@code double}, so it defaulted to {@code 0.0} rather
 *     than {@code NaN}. The "was {@code +gamma} given?" test was therefore always true, the
 *     {@code gamma = alpha} default was dead code, and every {@code +alpha} without {@code +gamma}
 *     got <b>zero rotation</b>: 215,218.8 m easting and 303,073.5 m northing on RSO Borneo.</li>
 * <li>{@code u_0} used {@code cos(gamma)} where {@code omerc.cpp:296} uses {@code cos(alpha_c)}:
 *     2,532.3 m easting and 1,899.2 m northing on RSO Borneo.</li>
 * </ol>
 *
 * <p>The two interact — with {@code gamma} wrongly {@code 0}, {@code cos(gamma)} is {@code 1.0} — so
 * the first three tests below deliberately pin <em>all four</em> combinations of "gamma given" and
 * "gamma equals alpha", which is what makes the two independently observable.
 */
public class ObliqueMercatorParameterTest {

    private static final CRSFactory CRS_FACTORY = new CRSFactory();
    private static final CoordinateTransformFactory CT_FACTORY = new CoordinateTransformFactory();

    /** Everest 1830 (RSO), the only {@code +rf=} in the repository. */
    private static final String RSO_ELLIPSOID = "+a=6377298.556 +rf=300.8017";
    private static final String RSO_GEO = "+proj=latlong " + RSO_ELLIPSOID;

    /** RSO Borneo's own test point, 116d2'11.12630E 5d54'19.90183N in decimal degrees. */
    private static final double RSO_LON = 116.0364239722222;
    private static final double RSO_LAT = 5.905528286111111;

    private static ProjCoordinate transform(String src, String tgt, double x, double y) {
        CoordinateReferenceSystem s = CRS_FACTORY.createFromParameters("src", src);
        CoordinateReferenceSystem t = CRS_FACTORY.createFromParameters("tgt", tgt);
        CoordinateTransform ct = CT_FACTORY.createTransform(s, t);
        ProjCoordinate out = new ProjCoordinate();
        ct.transform(new ProjCoordinate(x, y), out);
        return out;
    }

    private static void check(String message, String src, String tgt,
                              double x, double y, double expectX, double expectY, double tolerance) {
        ProjCoordinate out = transform(src, tgt, x, y);
        assertEquals(message + " (easting)", expectX, out.x, tolerance);
        assertEquals(message + " (northing)", expectY, out.y, tolerance);
    }

    private static Projection proj(String params) {
        return CRS_FACTORY.createFromParameters(null, params).getProjection();
    }

    /**
     * <b>{@code +alpha} with no {@code +gamma} must rotate by {@code alpha}.</b>
     * <p>
     * The 215 km reproducer. {@code cs2cs} 9.8.1 gives
     * {@code 705254.125376385171 653608.753619930823}; the value Proj4J produced while
     * {@code Gamma} defaulted to {@code 0.0} was {@code 490035.358739 956682.251445}, so this
     * assertion fails by more than 200 km if the {@code NaN} default is ever lost.
     */
    @Test
    public void alphaWithoutGammaRotatesByAlpha() {
        check("omerc +alpha with no +gamma", RSO_GEO,
                "+proj=omerc " + RSO_ELLIPSOID + " +lat_0=4 +lonc=115 +alpha=53d18'56.9537"
                        + " +k_0=0.99984 +x_0=590476.87 +y_0=442857.65",
                RSO_LON, RSO_LAT, 705254.125376385171, 653608.753619930823, 1.0e-6);
    }

    /**
     * <b>{@code u_0} must use {@code cos(alpha)}, not {@code cos(gamma)}.</b>
     * <p>
     * RSO Borneo proper: {@code gamma != alpha} and no {@code +no_uoff}, the one configuration in the
     * repository that reaches the {@code u_0} line with the two cosines actually differing.
     * {@code u_0(cos alpha) = 0.115738049} against {@code u_0(cos gamma) = 0.115241701}; resolved
     * through the rotation and scaled by {@code a} that is (-2532.29, -1899.22) m.
     */
    @Test
    public void u0UsesCosAlphaNotCosGamma() {
        check("RSO Borneo, gamma != alpha, no +no_uoff", RSO_GEO,
                "+proj=omerc " + RSO_ELLIPSOID + " +lat_0=4 +lonc=115 +alpha=53d18'56.9537"
                        + " +gamma=53d7'48.3685 +k_0=0.99984 +x_0=590476.87 +y_0=442857.65",
                RSO_LON, RSO_LAT, 704570.396561578, 653979.683964950, 1.0e-6);
    }

    /**
     * The two configurations that <em>hid</em> both defects, kept so it stays visible why they hid
     * them: {@code gamma == alpha} makes the two cosines coincide and the rotation correct by
     * accident, and {@code +no_uoff} forces {@code u_0 = 0} so the wrong cosine is never evaluated.
     * Neither can substitute for the two tests above.
     */
    @Test
    public void theConfigurationsThatUsedToHideTheDefects() {
        // gamma == alpha.
        check("gamma == alpha", RSO_GEO,
                "+proj=omerc " + RSO_ELLIPSOID + " +lat_0=4 +lonc=115 +alpha=53d18'56.9537"
                        + " +gamma=53d18'56.9537 +k_0=0.99984 +x_0=590476.87 +y_0=442857.65",
                RSO_LON, RSO_LAT, 705254.125376385171, 653608.753619930823, 1.0e-6);

        // +no_uoff, gamma != alpha: u_0 is zero, so the cos(alpha)/cos(gamma) line never runs.
        check("+no_uoff with gamma != alpha", RSO_GEO,
                "+proj=omerc " + RSO_ELLIPSOID + " +lat_0=4 +lonc=115 +alpha=53d18'56.9537"
                        + " +gamma=53d7'48.3685 +no_uoff +k_0=0.99984 +x_0=590476.87 +y_0=442857.65",
                RSO_LON, RSO_LAT, 1295047.271908451337, 1096837.340363108087, 1.0e-6);
    }

    /**
     * <b>{@code +gamma} on its own, with no {@code +alpha}.</b>
     * <p>
     * Upstream's third branch ({@code omerc.cpp:226-230}): {@code gamma0 = gamma} and the azimuth is
     * <em>derived</em>, {@code alpha_c = asin(D sin gamma0)}. Unreachable before, because the
     * no-argument constructor's placeholder {@code alpha = -45} degrees was indistinguishable from a
     * caller-supplied one. Definition from {@code builtins.gie:5325}, which upstream marks
     * <code>#&nbsp;OK</code> without pinning a value; reference from
     * {@code proj -d 12 +proj=omerc +lat_0=10 +R=6400000 +gamma=80} on (2, 1).
     * <p>
     * {@code +gamma=80} is exactly on upstream's limit here: {@code |gamma| <= asin(1/D)} and
     * {@code asin(1/D) = asin(cos(lat_0)) = 80} degrees to the last bit. {@code builtins.gie:5330}
     * pins {@code +gamma=80.0000001} as a failure, so this pair brackets the bound.
     */
    @Test
    public void gammaWithoutAlphaTakesTheGammaBranch() {
        check("omerc +gamma with no +alpha", "+proj=longlat +R=6400000",
                "+proj=omerc +lat_0=10 +R=6400000 +gamma=80",
                2.0, 1.0, 397886.061495506379, -954179.578580341535, 1.0e-6);

        // builtins.gie:5330 -- one ten-millionth of a degree past asin(1/D) must be rejected.
        assertRejected("+proj=omerc +lat_0=10 +R=6400000 +gamma=80.0000001", "gamma");
        // builtins.gie:5341 -- and on an ellipsoid the bound moves, so 80.1 is still out.
        assertRejected("+proj=omerc +lat_0=10 +a=6400000 +rf=300 +gamma=80.1", "gamma");
    }

    /**
     * The azimuth form must reproduce the two-point form when the two describe the same centre line.
     * <p>
     * {@code builtins.gie:5279} and {@code :5286} are the same expected value under
     * {@code +lat_1=45 +lat_2=45.00001 +lon_1=0 +lon_2=1e-5} and under
     * {@code +alpha=35.264383770917604}. Only the second is expressible through Proj4J's parser
     * ({@code +lon_1}/{@code +lon_2} are not {@code Proj4Keyword}s), so the azimuth half is asserted
     * here at upstream's own 1 mm tolerance. Proj4J's residual against upstream's pinned value is
     * 6.3e-05 m easting and 1.6e-04 m northing, which is a {@code phi2}/meridian-arc residual and
     * nothing to do with {@code omerc}.
     */
    @Test
    public void azimuthFormMatchesUpstreamsPinnedTwoPointValue() {
        check("builtins.gie:5286", "+proj=longlat +a=6400000",
                "+proj=omerc +a=6400000 +lat_0=45 +alpha=35.264383770917604",
                2.0, 1.0, -3569.825230822232, -5093592.310871849768, 1.0e-3);
    }

    /**
     * {@code |lat_0| = 90} must be rejected in the azimuth form too, not only in the two-point form.
     * {@code builtins.gie:5293}: {@code +proj=omerc +R=1 +alpha=0 +lat_0=90} is an
     * {@code expect failure}. Note that {@code +alpha=0} itself is <em>not</em> an error upstream —
     * the old code rejected it, and 9.8.1's {@code omerc.cpp} has no such check.
     */
    @Test
    public void lat0AtThePoleIsRejectedInTheAzimuthForm() {
        assertRejected("+proj=omerc +R=1 +alpha=0 +lat_0=90", "lat_0");
    }

    /**
     * <b>The two-point form.</b>
     * <p>
     * Also unreachable before, for the same reason, and additionally because the class's own
     * {@code phi1}/{@code phi2} fields were never assigned by anything — {@code +lat_1} and
     * {@code +lat_2} land in {@code projectionLatitude1}/{@code projectionLatitude2}. Definition and
     * reference from {@code builtins.gie:5223}. {@code +lon_1}/{@code +lon_2} are absent here and
     * default to 0 in both libraries, which is the only two-point case Proj4J's parser can express.
     */
    @Test
    public void twoPointFormUsesLat1AndLat2() {
        // builtins.gie:5225-5232, all four quadrants, at upstream's 0.1 mm.
        String def = "+proj=omerc +ellps=GRS80 +lat_1=0.5 +lat_2=2";
        String geo = "+proj=longlat +ellps=GRS80";
        check("omerc two-point form", geo, def, 2.0, 1.0, 222650.796885261, 110642.229314984, 1.0e-4);
        check("omerc two-point form", geo, def, 2.0, -1.0, 222650.796885261, -110642.229314984, 1.0e-4);
        check("omerc two-point form", geo, def, -2.0, 1.0, -222650.796885262, 110642.229314984, 1.0e-4);
        check("omerc two-point form", geo, def, -2.0, -1.0, -222650.796885262, -110642.229314984, 1.0e-4);

        // And its inverse, builtins.gie:5236-5243. Degrees out.
        check("omerc two-point inverse", def, geo, 200.0, 100.0, 0.001796631, 0.000904369, 1.0e-9);
        check("omerc two-point inverse", def, geo, -200.0, -100.0, -0.001796631, -0.000904369, 1.0e-9);
    }

    /**
     * Upstream's two-point rejections ({@code omerc.cpp:157-192}) must be rejections, not plausible
     * coordinates. {@code +lat_1=91} and {@code +lat_2=91} are {@code expect failure} rows in
     * {@code builtins.gie:5309} and {@code :5314}.
     */
    @Test
    public void twoPointFormRejectsWhatUpstreamRejects() {
        assertRejected("+proj=omerc +ellps=GRS80 +lat_1=91 +lat_2=2", "lat_1");
        assertRejected("+proj=omerc +ellps=GRS80 +lat_1=0.5 +lat_2=91", "lat_2");
        // lat_1 == lat_2: no centre line.
        assertRejected("+proj=omerc +ellps=GRS80 +lat_1=2 +lat_2=2", "lat_2");
        // lat_1 == 0: the centre line degenerates to the equator.
        assertRejected("+proj=omerc +ellps=GRS80 +lat_2=2", "lat_1");
    }

    private static void assertRejected(String params, String expectInMessage) {
        try {
            proj(params);
            fail(params + " must be rejected, not turned into a usable projection");
        } catch (InvalidValueException expected) {
            assertTrue("the message must name the offending parameter. Was: " + expected.getMessage(),
                    expected.getMessage().contains(expectInMessage));
        }
    }

    /**
     * {@code initialize()} must be idempotent, which is the structural half of defect 1.
     * <p>
     * {@code Proj4Parser} calls {@code initialize()} <em>after</em> the no-argument constructor has
     * already called it once, so anything {@code initialize()} writes to a parameter field is read
     * back as a parameter on the second pass. The old code wrote both {@code Gamma} and
     * {@code alpha}; the derived rotation and azimuth are locals now.
     */
    @Test
    public void initializeIsIdempotent() {
        String def = "+proj=omerc " + RSO_ELLIPSOID + " +lat_0=4 +lonc=115 +alpha=53d18'56.9537"
                + " +k_0=0.99984 +x_0=590476.87 +y_0=442857.65";
        Projection p = proj(def);
        ProjCoordinate in = new ProjCoordinate(Math.toRadians(RSO_LON), Math.toRadians(RSO_LAT));
        ProjCoordinate first = new ProjCoordinate();
        p.projectRadians(in, first);

        for (int i = 0; i < 5; i++) {
            p.initialize();
        }
        ProjCoordinate again = new ProjCoordinate();
        p.projectRadians(in, again);

        assertEquals("re-initialize must not move the forward projection", first.x, again.x, 0.0);
        assertEquals("re-initialize must not move the forward projection", first.y, again.y, 0.0);
    }

    /**
     * {@code +lonc} defaults to 0 rather than to {@code NaN}, and {@code +lon_0} is ignored.
     * <p>
     * {@code Projection.lonc} is {@code NaN} when {@code +lonc} is absent, and
     * {@code initialize()} used to assign it straight into {@code lamc}, so an {@code omerc} without
     * {@code +lonc} produced {@code NaN} in every ordinate. Upstream's {@code lamc} defaults to 0
     * ({@code omerc.cpp:136}) and it reads {@code +lon_0} only to log that it is being ignored
     * ({@code omerc.cpp:193-195}).
     */
    @Test
    public void loncDefaultsToZeroAndLon0IsIgnored() {
        // cs2cs 9.8.1: 222805.015103577054  -331698.992270279850
        ProjCoordinate noLonc = transform("+proj=longlat +ellps=GRS80",
                "+proj=omerc +ellps=GRS80 +lat_0=4 +alpha=53 +k_0=0.99984", 2.0, 1.0);
        assertTrue("an omerc with no +lonc must not produce NaN: was " + noLonc,
                !Double.isNaN(noLonc.x) && !Double.isNaN(noLonc.y));
        assertEquals(222805.015103577054, noLonc.x, 1.0e-6);
        assertEquals(-331698.992270279850, noLonc.y, 1.0e-6);

        // +lon_0 must change nothing at all. cs2cs 9.8.1 gives the same two numbers.
        ProjCoordinate withLon0 = transform("+proj=longlat +ellps=GRS80",
                "+proj=omerc +ellps=GRS80 +lat_0=4 +lon_0=42 +alpha=53 +k_0=0.99984", 2.0, 1.0);
        assertEquals("+lon_0 must be ignored by omerc", noLonc.x, withLon0.x, 0.0);
        assertEquals("+lon_0 must be ignored by omerc", noLonc.y, withLon0.y, 0.0);
    }

    /**
     * Round-trip, so the inverse is held to the same fixes as the forward: {@code u_0} is added back
     * in the inverse, and the {@code atan2} in {@code omerc.cpp:113} is the one the forward's
     * {@code atan2} has to agree with.
     */
    @Test
    public void forwardAndInverseRoundTrip() {
        String omerc = "+proj=omerc " + RSO_ELLIPSOID + " +lat_0=4 +lonc=115 +alpha=53d18'56.9537"
                + " +gamma=53d7'48.3685 +k_0=0.99984 +x_0=590476.87 +y_0=442857.65";
        ProjCoordinate there = transform(RSO_GEO, omerc, RSO_LON, RSO_LAT);
        ProjCoordinate back = transform(omerc, RSO_GEO, there.x, there.y);
        assertEquals(RSO_LON, back.x, 1.0e-11);
        assertEquals(RSO_LAT, back.y, 1.0e-11);
    }

    /**
     * {@code +no_uoff} must participate in equality. {@code u_0} depends on it and nothing else
     * does, so two {@code omerc} projections differing only by {@code +no_uoff} used to compare
     * equal while projecting 55 m apart — and {@code Projection} is a transform-cache key.
     */
    @Test
    public void noUoffAndGammaParticipateInEquality() {
        String base = "+proj=omerc " + RSO_ELLIPSOID + " +lat_0=4 +lonc=115 +alpha=53d18'56.9537"
                + " +k_0=0.99984 +x_0=590476.87 +y_0=442857.65 +units=m +no_defs";
        Projection plain = proj(base);
        Projection noUoff = proj(base + " +no_uoff");
        Projection withGamma = proj(base + " +gamma=53d7'48.3685");

        assertEquals(plain, proj(base));
        assertEquals(plain.hashCode(), proj(base).hashCode());

        assertNotEquals("+no_uoff changes u_0, so it must change equality", plain, noUoff);
        assertNotEquals(noUoff, plain);
        assertNotEquals(plain.hashCode(), noUoff.hashCode());

        assertNotEquals("+gamma changes the rotation, so it must change equality", plain, withGamma);
        assertNotEquals(withGamma, plain);
        assertNotEquals(plain.hashCode(), withGamma.hashCode());

        // And an absent +gamma must not make a projection unequal to an identically-defined one:
        // gamma is NaN there, and NaN != NaN under ==.
        assertEquals(withGamma, proj(base + " +gamma=53d7'48.3685"));
    }
}

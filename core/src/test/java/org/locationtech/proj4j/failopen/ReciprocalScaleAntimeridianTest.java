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
package org.locationtech.proj4j.failopen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.proj.Projection;

/**
 * One ulp, one sign, at the antimeridian: the inverse must <b>multiply</b> by the reciprocal of
 * {@code totalScale}, because {@code 9.8.1:src/inv.cpp:85-93} does.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code Projection.inverseProjectRadians} de-scaled the projected ordinate with
 * {@code (src.x - totalFalseEasting) / totalScale}. Upstream is {@code coo.xyz.x *= P->ra;} with
 * {@code P->ra = 1. / P->a} ({@code ell_set.cpp:618}), and it carries a comment explaining the
 * choice — nominally about {@code calcofi} stomping on {@code a}, but the two operations are simply
 * not the same number:
 *
 * <pre>
 * -20037508.342789244 / 6378137          = -3.1415926535897936 = -pi - 1 ulp
 * -20037508.342789244 * (1.0 / 6378137)  = -3.1415926535897931 = exactly -pi
 * </pre>
 *
 * <p>{@code -pi - 1 ulp} is a longitude one ulp <em>west</em> of the antimeridian, and it survives
 * {@code adjlon} untouched (its window is {@code |lam| < pi + 1e-12}). Fed through the geocentric
 * leg of a datum shift it reaches {@code atan2}, which normalises it to {@code +pi} — so
 * {@code EPSG:3857} &rarr; {@code EPSG:4055} returned <b>+180</b> where PROJ returns <b>-180</b>: the
 * opposite side of the world, from a one-ulp arithmetic difference.
 *
 * <h2>Why this only became visible recently</h2>
 *
 * <p>Master survived it because the inverse used to <em>clamp</em> longitude into
 * {@code [-pi, +pi]}, and the clamp happened to force {@code -pi}. Removing the clamp was correct —
 * PROJ wraps with {@code adjlon}, it does not clamp, and clamping discards the revolution count
 * instead of removing it — and it exposed the division underneath. Two defects were cancelling; see
 * non-negotiable 4b.
 *
 * <h2>The reference</h2>
 *
 * <p>PROJ 9.8.1 as installed (<i>Rel. 9.8.1, April 10th, 2026</i>):
 *
 * <pre>
 * $ echo "-20037508.342789244 0" | cs2cs -f "%.17g" EPSG:3857 EPSG:4055
 * 0	-180 0
 * $ echo "-20037508.342789244 0" | proj -I -f "%.17g" +proj=merc +a=6378137 +b=6378137
 * -180	0
 * </pre>
 *
 * <p>The second line is itself evidence that upstream multiplies rather than divides: the division
 * result in degrees is {@code -180.00000000000003}, which {@code %.17g} would have printed in full.
 *
 * <h2>The positive control</h2>
 *
 * <p>This file was run against a build with {@code / totalScale} put back, and both
 * behavioural tests went red naming the mechanism:
 *
 * <ul>
 * <li>{@code theDescaledLongitudeIsExactlyMinusPi} &mdash;
 *     <i>"expected:&lt;-4609115380302729960&gt; but was:&lt;-4609115380302729959&gt;"</i>, one ulp,
 *     as raw bits</li>
 * <li>{@code webMercatorToASphereReturnsMinus180NotPlus180} &mdash;
 *     <i>"expected:&lt;-4582834833314545664&gt; but was:&lt;4640537203540230144&gt;"</i>, i.e.
 *     {@code -180.0} against {@code +180.0}</li>
 * </ul>
 *
 * <p>{@code theProbePointDistinguishesDivisionFromMultiplication} stayed green under the control,
 * correctly: it is arithmetic on literals and does not depend on the implementation. That is the
 * point of it &mdash; it is the assertion that survives to say the probe point is still
 * discriminating.
 *
 * <p>The same control also flipped {@code RepointBitIdentityTest}'s two new guards, including
 * {@code theSupersededDigestsNoLongerDescribeThisBuild}, which reported
 * <i>"the 7362c85 digest for +proj=spilhaus +ellps=WGS84 still matches this build"</i>. That is
 * independent confirmation that the five re-pinned digests describe the multiply and the five
 * superseded ones describe the divide, rather than being a pair of numbers taken on trust.
 */
public class ReciprocalScaleAntimeridianTest {

    /** The western edge of the Web Mercator world, {@code -pi * 6378137} rounded to a double. */
    private static final double WEB_MERCATOR_WEST = -20037508.342789244;

    /** WGS 84 semi-major axis, and {@code EPSG:3857}'s. */
    private static final double A = 6378137.0;

    // ------------------------------------------------------------------
    // The positive control comes first, because it validates the probe point
    // ------------------------------------------------------------------

    /**
     * Proves the probe point can tell the two operations apart.
     *
     * <h4>Why this is the control and not decoration</h4>
     *
     * <p>Every other assertion in this file is "the answer is {@code -180}". If the probe easting
     * were changed to one where {@code x / a} and {@code x * (1/a)} happen to agree — which is most
     * of them — those assertions would keep passing while testing nothing. So this asserts the
     * arithmetic difference exists at this exact value, in both the radian and the degree domain,
     * and that the two land on opposite sides of the antimeridian.
     */
    @Test
    public void theProbePointDistinguishesDivisionFromMultiplication() {
        double divided = WEB_MERCATOR_WEST / A;
        double multiplied = WEB_MERCATOR_WEST * (1.0 / A);

        assertFalse("the probe easting no longer distinguishes / from * (1/a), so every other"
                + " assertion in this class has become vacuous",
                Double.doubleToRawLongBits(divided) == Double.doubleToRawLongBits(multiplied));
        assertEquals("the multiply must be exactly -pi", -Math.PI, multiplied, 0.0);
        assertEquals("the divide must be -pi - 1 ulp",
                Double.doubleToRawLongBits(Math.nextAfter(-Math.PI, Double.NEGATIVE_INFINITY)),
                Double.doubleToRawLongBits(divided));

        // ... and the two land on opposite sides once atan2 normalises them, which is the mechanism.
        assertEquals("atan2 of the divided value normalises to +pi", Math.PI,
                Math.atan2(Math.sin(divided), Math.cos(divided)), 0.0);
        assertEquals("atan2 of the multiplied value stays at -pi", -Math.PI,
                Math.atan2(Math.sin(multiplied), Math.cos(multiplied)), 0.0);
    }

    // ------------------------------------------------------------------
    // The defect itself
    // ------------------------------------------------------------------

    /**
     * The measured case. {@code cs2cs EPSG:3857 EPSG:4055} on PROJ 9.8.1 gives {@code -180}; this
     * returned {@code +180.0} before the fix. Asserted on the raw bits, because
     * {@code assertEquals(-180.0, x, 1e-9)} would pass for neither and
     * {@code assertEquals(180.0, x, 360.0)} for both — the whole content of this test is a sign.
     */
    @Test
    public void webMercatorToASphereReturnsMinus180NotPlus180() {
        CRSFactory crs = new CRSFactory();
        ProjCoordinate out = new ProjCoordinate();
        new CoordinateTransformFactory()
                .createTransform(crs.createFromName("EPSG:3857"), crs.createFromName("EPSG:4055"))
                .transform(new ProjCoordinate(WEB_MERCATOR_WEST, 0.0), out);

        assertEquals("longitude at the western antimeridian: cs2cs 9.8.1 gives -180, this gave"
                        + " +180.0 before the reciprocal fix",
                Double.doubleToRawLongBits(-180.0), Double.doubleToRawLongBits(out.x));
        assertEquals("latitude must be unaffected", 0.0, out.y, 0.0);
    }

    /**
     * The same at the funnel, in radians, where the ulp actually lives: the de-scaled longitude must
     * be <em>exactly</em> {@code -pi}. Two spellings of a sphere of radius {@code a}, because the
     * bug is in shared code and not in either kernel.
     */
    @Test
    public void theDescaledLongitudeIsExactlyMinusPi() {
        for (String definition : new String[]{
                "+proj=merc +a=6378137 +b=6378137",
                "+proj=webmerc +datum=WGS84"}) {
            Projection p = new CRSFactory()
                    .createFromParameters("failopen", definition).getProjection();
            ProjCoordinate out = new ProjCoordinate();
            p.inverseProjectRadians(new ProjCoordinate(WEB_MERCATOR_WEST, 0.0), out);
            assertEquals(definition + ": the de-scaled longitude must be exactly -pi, not -pi - 1"
                            + " ulp. proj -I 9.8.1 prints exactly -180 deg for this easting.",
                    Double.doubleToRawLongBits(-Math.PI), Double.doubleToRawLongBits(out.x));
        }
    }

    /**
     * The reciprocal must be recomputed whenever {@code totalScale} is, or a projection that
     * re-initialises would de-scale with a stale factor — which is a far worse failure than the one
     * being fixed, and a silent one. {@code initialize()} runs <b>twice</b> for every parsed
     * projection (once from the constructor, once from the parser), so the second run is the one
     * that has to be right.
     */
    @Test
    public void theReciprocalTracksTotalScaleAcrossReinitialisation() {
        Projection p = new CRSFactory()
                .createFromParameters("failopen", "+proj=merc +a=6378137 +b=6378137")
                .getProjection();
        ProjCoordinate first = new ProjCoordinate();
        p.inverseProjectRadians(new ProjCoordinate(WEB_MERCATOR_WEST, 0.0), first);

        p.initialize();
        ProjCoordinate again = new ProjCoordinate();
        p.inverseProjectRadians(new ProjCoordinate(WEB_MERCATOR_WEST, 0.0), again);
        assertEquals("a second initialize() must not move the de-scaled longitude",
                Double.doubleToRawLongBits(first.x), Double.doubleToRawLongBits(again.x));

        // Change the radius, re-initialise, and the answer must change with it -- otherwise this
        // test could not tell a tracked reciprocal from a cached one.
        p.setEllipsoid(org.locationtech.proj4j.datum.Ellipsoid.SPHERE);
        p.initialize();
        ProjCoordinate scaled = new ProjCoordinate();
        p.inverseProjectRadians(new ProjCoordinate(WEB_MERCATOR_WEST, 0.0), scaled);
        assertFalse("changing the ellipsoid and re-initialising must move the de-scaled longitude,"
                        + " or the reciprocal is stale rather than tracked",
                Double.doubleToRawLongBits(first.x) == Double.doubleToRawLongBits(scaled.x));
    }

    /**
     * {@code +proj=calcofi} is upstream's stated reason for {@code *= ra}: {@code calcofi.cpp:136-141}
     * assigns {@code P->a = 1} and {@code P->ra = 1}, and Proj4J reproduces that ordering —
     * {@code CalCOFIProjection.initialize()} sets {@code a = 1} <em>before</em> calling
     * {@code super.initialize()}, so the reciprocal is captured from the stomped value, exactly as
     * {@code P->ra} is. If it were ever computed lazily from an unstomped {@code a}, calcofi's
     * round trip would break, so this asserts the round trip rather than the field.
     */
    @Test
    public void calcofiStillRoundTrips() {
        Projection p = new CRSFactory()
                .createFromParameters("failopen", "+proj=calcofi +ellps=clrk66").getProjection();
        ProjCoordinate fwd = new ProjCoordinate();
        ProjCoordinate back = new ProjCoordinate();
        p.project(new ProjCoordinate(-121.0, 34.0), fwd);
        p.inverseProject(fwd, back);
        assertTrue("calcofi longitude did not round trip: " + back.x,
                Math.abs(back.x - (-121.0)) < 1e-9);
        assertTrue("calcofi latitude did not round trip: " + back.y,
                Math.abs(back.y - 34.0) < 1e-9);
    }
}

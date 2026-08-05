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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.proj.Projection;

/**
 * {@code fwd_prepare} tests the longitude it was <b>given</b>, not the longitude left after
 * {@code lon_0} has been taken off it.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code Projection.project(ProjCoordinate, ProjCoordinate)} and
 * {@code Projection.projectRadians(ProjCoordinate, ProjCoordinate)} each subtracted
 * {@code projectionLongitude} and wrapped the result <em>before</em> handing over to the funnel that
 * runs {@code checkForwardDomain}. Upstream is the other way round:
 * {@code 9.8.1:src/fwd.cpp:40} tests {@code HUGE_VAL}, {@code :57-70} tests
 * {@code |phi| - pi/2 > PJ_EPS_LAT} and {@code |lam| > 10}, and only then does {@code :105} evaluate
 * {@code coo.lp.lam = (coo.lp.lam - P->from_greenwich) - P->lam0}. Both of those are
 * {@code PROJ_ERR_COORD_TRANSFM_INVALID_COORD} = 2049.
 *
 * <p>Two measured consequences, both on
 * {@code +proj=laea +lat_0=90 +lon_0=-150 +datum=WGS84 +units=m}, and both invisible with
 * {@code lon_0=0}:
 *
 * <table>
 * <caption>before and after, radians</caption>
 * <tr><th>input</th><th>before</th><th>after</th></tr>
 * <tr><td>{@code (+Infinity, 0.5)}</td><td>{@code (NaN, NaN)}, no exception</td>
 *     <td>{@link ErrorCause#INVALID_COORDINATE}</td></tr>
 * <tr><td>{@code (20.0, 0.5)}</td>
 *     <td>{@code (-3819350.5146746966, 5273204.385775552)}</td>
 *     <td>{@link ErrorCause#INVALID_COORDINATE}</td></tr>
 * <tr><td>{@code (-20.0, 0.5)}</td>
 *     <td>{@code (6476404.214766495, -671052.3787776735)}</td>
 *     <td>{@link ErrorCause#INVALID_COORDINATE}</td></tr>
 * <tr><td>{@code (9.9, 0.5)} with {@code +over}</td>
 *     <td>{@link ErrorCause#INVALID_COORDINATE} &mdash; <b>wrongly</b>: the reduced value was
 *         12.5 rad and nothing wrapped it back</td>
 *     <td>{@code (-314861.8064936594, -6503459.4556220565)}</td></tr>
 * </table>
 *
 * <p>The {@code +over} row is the reason the check <em>moved</em> rather than being duplicated: the
 * ordering error was rejecting good input as well as accepting bad, and only one of those two is
 * fixed by adding a second check.
 *
 * <h2>The infinity is an error, not a propagated value</h2>
 *
 * <p>{@code adjlon(+Infinity)} is {@code inf - inf}, i.e. {@code NaN}, faithfully to
 * {@code 9.8.1:src/adjlon.cpp}. So before the fix an infinite longitude with a central meridian was
 * <em>laundered into a NaN</em> and then propagated by the {@code NaN} rule, which is the one way the
 * two rules could be made to contradict each other. They compose correctly now because the domain
 * check runs on the value the caller passed, so {@code adjlon} never sees an infinity at all &mdash;
 * see {@link #theTwoRulesComposeOnMixedNaNAndInfinity()} for the case where a coordinate carries
 * both.
 *
 * <h2>The positive control</h2>
 *
 * <p>This whole file, plus
 * {@code LaeaNanPropagationTest.infiniteLongitudeWithACentralMeridianIsRefused}, was run against a
 * build with the two statements swapped back into their old order &mdash; the reduction ahead of
 * {@code checkForwardDomain} &mdash; and 5 assertions went red, each naming its own mechanism:
 *
 * <ul>
 * <li>{@code aLongitudePastTenRadiansIsRefusedWhicheverCentralMeridianIsSet} &mdash;
 *     <i>"expected INVALID_COORDINATE, got ProjCoordinate[-3819350.5146746966 5273204.385775552
 *     NaN]"</i></li>
 * <li>{@code anInfiniteLongitudeIsRefusedWhicheverCentralMeridianIsSet} and
 *     {@code anInfinityAloneIsNotAPropagatedValue} &mdash; {@code NUMERICAL_FAILURE} instead of
 *     {@link ErrorCause#INVALID_COORDINATE}, which is where the laundered infinity surfaces once
 *     the {@code NaN} funnel exists but the ordering is still wrong</li>
 * <li>{@code aLongitudeInsideTheBoundIsAcceptedEvenUnderOver} &mdash;
 *     <i>"invalid longitude 12.517993877991495 rad (717.228217179515 deg): outside +/-10.0 rad"</i>,
 *     the false rejection, from an input of 9.9 rad</li>
 * <li>{@code LaeaNanPropagationTest.infiniteLongitudeWithACentralMeridianIsRefused} &mdash;
 *     {@code NUMERICAL_FAILURE} instead of {@link ErrorCause#INVALID_COORDINATE}</li>
 * </ul>
 *
 * <p>{@code twoHundredDegreesIsStillPerfectlyLegal} and
 * {@code theTwoRulesComposeOnMixedNaNAndInfinity} stayed green under the control, correctly: neither
 * depends on the ordering, and that they did not move is the evidence that the fix is confined to
 * the two out-of-contract classes.
 */
public class RawLongitudeDomainCheckTest {

    /** {@code lon_0 != 0}: the reduction happens, so the ordering is observable. */
    private static final String LON0_SET =
            "+proj=laea +lat_0=90 +lon_0=-150 +datum=WGS84 +units=m";

    /** {@code lon_0 = 0}: the control. Every outcome below must be identical to {@link #LON0_SET}. */
    private static final String LON0_ZERO =
            "+proj=laea +lat_0=90 +lon_0=0 +datum=WGS84 +units=m";

    // ------------------------------------------------------------------
    // HUGE_VAL, on the raw value
    // ------------------------------------------------------------------

    /**
     * An infinite longitude, with and without a central meridian, on both entry points. The pairing
     * is the assertion: {@code lon_0} must not change the verdict.
     */
    @Test
    public void anInfiniteLongitudeIsRefusedWhicheverCentralMeridianIsSet() {
        for (String definition : new String[]{LON0_SET, LON0_ZERO}) {
            assertInvalid(definition + " radians +Inf", definition,
                    Double.POSITIVE_INFINITY, 0.5, true);
            assertInvalid(definition + " radians -Inf", definition,
                    Double.NEGATIVE_INFINITY, 0.5, true);
            assertInvalid(definition + " degrees +Inf", definition,
                    Double.POSITIVE_INFINITY, 30.0, false);
            assertInvalid(definition + " degrees -Inf", definition,
                    Double.NEGATIVE_INFINITY, 30.0, false);
        }
    }

    /** The same for latitude, which was never affected but must not have been broken. */
    @Test
    public void anInfiniteLatitudeIsRefusedWhicheverCentralMeridianIsSet() {
        for (String definition : new String[]{LON0_SET, LON0_ZERO}) {
            assertInvalid(definition + " +Inf lat", definition, 0.5,
                    Double.POSITIVE_INFINITY, true);
            assertInvalid(definition + " -Inf lat", definition, 0.5,
                    Double.NEGATIVE_INFINITY, true);
        }
    }

    // ------------------------------------------------------------------
    // |lam| > MAX_LAM_RAD, on the raw value
    // ------------------------------------------------------------------

    /**
     * {@code |lam| > 10} rad, both signs, both entry points, both central meridians. 20 rad is
     * 1145.9&deg;; the value {@code (20.0, 0.5)} previously produced,
     * {@code (-3819350.5, 5273204.4)}, is inside {@code laea}'s legitimate range and there is no
     * way for a caller to tell it from a real answer.
     */
    @Test
    public void aLongitudePastTenRadiansIsRefusedWhicheverCentralMeridianIsSet() {
        for (String definition : new String[]{LON0_SET, LON0_ZERO}) {
            assertInvalid(definition + " 20 rad", definition, 20.0, 0.5, true);
            assertInvalid(definition + " -20 rad", definition, -20.0, 0.5, true);
            // 1200 deg = 20.94 rad. The degree entry point multiplies by DTR first, so the bound is
            // still the radian one -- Projection.MAX_LAM_RAD -- and 573 deg is where it bites.
            assertInvalid(definition + " 1200 deg", definition, 1200.0, 30.0, false);
            assertInvalid(definition + " -1200 deg", definition, -1200.0, 30.0, false);
            // The extreme, and the sharpest single measurement of this defect: 1e16 rad is
            // 5.7e17 degrees, and with lon_0=-150 it used to come back as
            // (-5920505.502483835, -2709564.0688433675) -- a coordinate in the Arctic Ocean, in
            // 0 ms, with no error raised. adjlon reduced it into the band before anything looked
            // at it. (adjlon keeps PROJ's own limitation that one floor loses the low bits of a
            // huge argument, so the value it produced was not even the right meridian.)
            assertInvalid(definition + " 1e16 rad", definition, 1e16, 0.5, true);
            assertInvalid(definition + " -1e16 rad", definition, -1e16, 0.5, true);
        }
    }

    /**
     * The bound is {@code |lam| > 10} radians and <b>not</b> {@code [-180, 180]} degrees: a caller
     * passing 200&deg; or &minus;190&deg; must keep working, because PROJ wraps those rather than
     * rejecting them. Asserted on both sides of the reduction, because this is exactly the
     * assertion a careless version of the fix would break.
     */
    @Test
    public void twoHundredDegreesIsStillPerfectlyLegal() {
        for (String definition : new String[]{LON0_SET, LON0_ZERO}) {
            for (double lonDeg : new double[]{200.0, -190.0, 360.0, -540.0, 572.9}) {
                ProjCoordinate out = new ProjCoordinate();
                projection(definition).project(new ProjCoordinate(lonDeg, 30.0), out);
                assertTrue(definition + " at " + lonDeg + " deg must be a finite coordinate, got "
                        + out, isFinite(out.x) && isFinite(out.y));
            }
        }
    }

    /**
     * 9.9 rad is inside {@link Projection#MAX_LAM_RAD} and must be accepted, including under
     * {@code +over} where nothing wraps it back after {@code lon_0} is taken off. This is the row
     * that was failing in the <em>other</em> direction before the fix: the reduced value was
     * 12.5 rad, so the check rejected a coordinate PROJ accepts.
     */
    @Test
    public void aLongitudeInsideTheBoundIsAcceptedEvenUnderOver() {
        ProjCoordinate over = new ProjCoordinate();
        projection(LON0_SET + " +over").projectRadians(new ProjCoordinate(9.9, 0.5), over);
        assertTrue("+over at 9.9 rad must be a finite coordinate, got " + over,
                isFinite(over.x) && isFinite(over.y));

        // Without +over the same input already worked, and its bits must not have moved: the wrap
        // and the subtraction are the same two statements in the same order, just later.
        ProjCoordinate wrapped = new ProjCoordinate();
        projection(LON0_SET).projectRadians(new ProjCoordinate(9.9, 0.5), wrapped);
        assertEquals("x at 9.9 rad, no +over, measured before the check moved",
                -314861.8064936562, wrapped.x, 0.0);
        assertEquals("y at 9.9 rad, no +over, measured before the check moved",
                -6503459.455622057, wrapped.y, 0.0);
    }

    // ------------------------------------------------------------------
    // How this composes with the NaN rule
    // ------------------------------------------------------------------

    /**
     * A coordinate carrying <em>both</em> a {@code NaN} and an infinity is a {@code NaN} result, not
     * an error, and that is upstream's order rather than a preference:
     * {@code 9.8.1:src/trans.cpp:352-354} runs {@code pj_coord_has_nans} <b>before</b>
     * {@code pj_fwd4d}, so {@code fwd_prepare}'s {@code HUGE_VAL} test is never reached for such a
     * coordinate. Pinned in both orders and with both central meridians, because "NaN wins" is the
     * kind of rule that gets silently inverted by a refactor.
     */
    @Test
    public void theTwoRulesComposeOnMixedNaNAndInfinity() {
        for (String definition : new String[]{LON0_SET, LON0_ZERO}) {
            assertNaN(definition + " (NaN, +Inf)", definition, Double.NaN,
                    Double.POSITIVE_INFINITY);
            assertNaN(definition + " (NaN, -Inf)", definition, Double.NaN,
                    Double.NEGATIVE_INFINITY);
            assertNaN(definition + " (+Inf, NaN)", definition, Double.POSITIVE_INFINITY,
                    Double.NaN);
            assertNaN(definition + " (-Inf, NaN)", definition, Double.NEGATIVE_INFINITY,
                    Double.NaN);
            // ... and a NaN beside a finite-but-out-of-domain ordinate: same rule, same reason.
            assertNaN(definition + " (NaN, 2.0 rad)", definition, Double.NaN, 2.0);
            assertNaN(definition + " (20.0 rad, NaN)", definition, 20.0, Double.NaN);
        }
    }

    /**
     * The distinction that makes the previous test meaningful: an infinity <b>on its own</b> is an
     * error. If the {@code NaN} early return ever swallowed infinities, every assertion above would
     * still pass while the fail-closed contract had been quietly dropped.
     */
    @Test
    public void anInfinityAloneIsNotAPropagatedValue() {
        assertInvalid("(+Inf, 0.5) alone", LON0_SET, Double.POSITIVE_INFINITY, 0.5, true);
        assertInvalid("(0.5, +Inf) alone", LON0_SET, 0.5, Double.POSITIVE_INFINITY, true);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static Projection projection(String definition) {
        return new CRSFactory().createFromParameters("failopen", definition).getProjection();
    }

    private static ProjCoordinate go(String definition, double x, double y, boolean radians) {
        ProjCoordinate out = new ProjCoordinate();
        Projection p = projection(definition);
        if (radians) {
            p.projectRadians(new ProjCoordinate(x, y), out);
        } else {
            p.project(new ProjCoordinate(x, y), out);
        }
        return out;
    }

    private static void assertInvalid(String what, String definition, double x, double y,
                                      boolean radians) {
        try {
            ProjCoordinate out = go(definition, x, y, radians);
            fail(what + ": expected INVALID_COORDINATE, got " + out);
        } catch (ProjectionException e) {
            assertEquals(what, ErrorCause.INVALID_COORDINATE, e.cause());
        }
    }

    private static void assertNaN(String what, String definition, double lam, double phi) {
        ProjCoordinate out;
        try {
            out = go(definition, lam, phi, true);
        } catch (ProjectionException e) {
            throw new AssertionError(what + ": a coordinate carrying a NaN must come back as NaN,"
                    + " because trans.cpp:352-354 short-circuits before fwd_prepare runs at all."
                    + " Got " + e.cause() + ": " + e.getMessage());
        }
        assertTrue(what + ": x must be NaN, was " + out.x, Double.isNaN(out.x));
        assertTrue(what + ": y must be NaN, was " + out.y, Double.isNaN(out.y));
    }

    private static boolean isFinite(double v) {
        // Not Double.isFinite: <release> is 8.
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }
}

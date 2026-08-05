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
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.proj.Projection;

/**
 * The fourth combination: <b>non-finite in, finite out</b>.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code LambertAzimuthalEqualAreaProjection}'s polar forward answered an undefined point with
 * the <em>origin</em>. Measured on the shipped classpath before the fix, on the proj-string the
 * corpus itself uses ({@code more_builtins.gie:795}):
 *
 * <pre>
 * +proj=laea +lat_0=90 +lon_0=-150 +datum=WGS84 +units=m
 *   projectRadians(NaN, NaN)     -&gt; (0.0, 0.0)               x isNaN = false, y isNaN = false
 *   projectRadians(NaN, +pi/2)   -&gt; (0.0, 0.0)
 *   projectRadians(0.5, NaN)     -&gt; (0.0, 0.0)
 * ... +x_0=1000000 +y_0=2000000
 *   projectRadians(NaN, NaN)     -&gt; (1000000.0, 2000000.0)   the false easting, exactly
 * </pre>
 *
 * <p>This sits between all three rules the fail-closed work established, and is the worst of the
 * four combinations, because <b>every downstream {@code isFinite} guard passes it</b>. The three
 * rules, which this class keeps distinguishable by testing each separately:
 *
 * <table>
 * <caption>the input-to-outcome contract, from {@code Projection.projectRadians}</caption>
 * <tr><th>input</th><th>outcome</th><th>tested by</th></tr>
 * <tr><td>{@code NaN}</td><td>a {@code NaN} <em>result</em>, no throw</td>
 *     <td>{@link #nanPropagatesFromEveryPolarArm()} and friends</td></tr>
 * <tr><td>&plusmn;{@code Infinity}, or finite outside the contract</td>
 *     <td>{@link ErrorCause#INVALID_COORDINATE}</td>
 *     <td>{@link #infiniteInputIsRefused()}, {@link #finiteOutOfDomainInputIsRefused()},
 *     {@link #infiniteLongitudeWithACentralMeridianIsRefused()}</td></tr>
 * <tr><td>finite and in contract</td><td>a finite coordinate, unchanged by this fix</td>
 *     <td>{@link #finiteInputIsUnmoved()}</td></tr>
 * </table>
 *
 * <h2>Why {@code NaN} out and not an exception</h2>
 *
 * <p>Because throwing would <em>break</em> conformance rather than improve it. {@code gie}'s first
 * metric branch is {@code isnan(got) && isnan(expected) -> d = 0}, i.e. a <b>pass</b>
 * ({@code 9.8.1:src/apps/gie.cpp:1136}), and {@code proj_roundtrip} defines all-{@code NaN} in with
 * all-{@code NaN} out as residual {@code 0.0} ({@code 9.8.1:src/trans.cpp:618-620}).
 * {@code more_builtins.gie:791-799} is a row that asserts exactly that, headed
 * <i>"When given NaNs, return NaNs"</i>, at {@code tolerance 0}.
 *
 * <h2>Why the bits are asserted, and why not a specific pattern</h2>
 *
 * <p>{@code assertEquals(Double.NaN, x, 0.0)} passes for {@code NaN} <em>and</em> would keep
 * passing if someone later "fixed" it back to a sentinel, because JUnit compares
 * {@code doubleToLongBits}. So the assertion here is on the raw bits and is spelled out: exponent
 * all ones, mantissa non-zero, <b>sign bit ignored</b>. Ignoring the sign is required, not lax —
 * the same JVM was observed producing {@code 7ff8000000000001} for {@code x} and
 * {@code fff8000000000001} for {@code y} on one call, and quiet-{@code NaN} sign and payload differ
 * between x86-64 ({@code fff8…}) and AArch64 ({@code 7ff8…}). What is <em>not</em> ignored is
 * {@code 0.0} and {@code -0.0}: those have stable bit patterns
 * ({@code 0000000000000000}/{@code 8000000000000000}), they were the observed wrong answer, and
 * {@link #assertNanBits} rejects them explicitly.
 */
public class LaeaNanPropagationTest {

    /** The corpus row's own definition, {@code more_builtins.gie:795}. Polar (north) aspect. */
    private static final String NORTH_POLAR =
            "+proj=laea +lat_0=90 +lon_0=-150 +datum=WGS84 +units=m";

    /** The same, with a false origin, which is what turns the defect into a plausible answer. */
    private static final String NORTH_POLAR_FALSE_ORIGIN =
            NORTH_POLAR + " +x_0=1000000 +y_0=2000000";

    /** South polar aspect: the sibling arm of the same {@code switch}. */
    private static final String SOUTH_POLAR =
            "+proj=laea +lat_0=-90 +lon_0=0 +datum=WGS84 +units=m";

    /** Spherical polar: shares the {@code mode} but not the code path. */
    private static final String NORTH_POLAR_SPHERE =
            "+proj=laea +lat_0=90 +lon_0=-150 +R=6378137 +units=m";

    /**
     * {@code lon_0=0}. This used to be the <em>only</em> case in which the domain checks saw the
     * longitude as supplied, because the reduction ran ahead of them; it is now the control that
     * shows {@code lon_0} makes no difference. See
     * {@link #infiniteLongitudeWithACentralMeridianIsRefused()}.
     */
    private static final String NORTH_POLAR_LON0_ZERO =
            "+proj=laea +lat_0=90 +lon_0=0 +datum=WGS84 +units=m";

    // ------------------------------------------------------------------
    // Rule 1: NaN in, NaN out -- as a result, not as an exception
    // ------------------------------------------------------------------

    /**
     * Every way a {@code NaN} can reach the polar arm, on both poles.
     * <p>
     * All four rows returned {@code (0.0, 0.0)} before the fix, and the reason there are four is
     * that {@code q} alone is not enough to detect the case. A {@code NaN} <em>latitude</em>
     * arrives as a {@code NaN} {@code q}; a {@code NaN} <em>longitude</em> arrives as
     * {@code NaN sinlam}/{@code coslam} with {@code q} finite, and at the pole itself {@code q}
     * falls under {@code laea.cpp}'s {@code 1e-15} floor, so {@code (NaN, +pi/2)} took the same
     * {@code else} arm.
     */
    @Test
    public void nanPropagatesFromEveryPolarArm() {
        assertNanResult("north polar, both NaN", NORTH_POLAR, Double.NaN, Double.NaN);
        assertNanResult("north polar, NaN longitude at the pole", NORTH_POLAR,
                Double.NaN, Math.PI / 2);
        assertNanResult("north polar, NaN longitude away from the pole", NORTH_POLAR, Double.NaN, 0.5);
        assertNanResult("north polar, NaN latitude", NORTH_POLAR, 0.5, Double.NaN);
        assertNanResult("south polar, both NaN", SOUTH_POLAR, Double.NaN, Double.NaN);
        assertNanResult("south polar, NaN longitude at the pole", SOUTH_POLAR,
                Double.NaN, -Math.PI / 2);
        assertNanResult("south polar, NaN latitude", SOUTH_POLAR, 0.5, Double.NaN);
    }

    /**
     * The shape that makes this Non-negotiable 3 rather than a cosmetic wart: with a false origin
     * set, the fabricated {@code (0, 0)} left the kernel and came back as
     * {@code (x_0, y_0) = (1000000.0, 2000000.0)}, which is inside the projection's own valid
     * range and indistinguishable from a real answer.
     */
    @Test
    public void nanDoesNotBecomeTheFalseEasting() {
        ProjCoordinate out = project(NORTH_POLAR_FALSE_ORIGIN, Double.NaN, Double.NaN);
        assertNanBits("x with +x_0=1000000", out.x);
        assertNanBits("y with +y_0=2000000", out.y);
        assertFalse("x came back as the false easting",
                Double.doubleToRawLongBits(out.x) == Double.doubleToRawLongBits(1000000.0));
        assertFalse("y came back as the false northing",
                Double.doubleToRawLongBits(out.y) == Double.doubleToRawLongBits(2000000.0));
    }

    /**
     * The three aspects that were already correct, asserted so that a future edit to the shared
     * {@code project_e}/{@code project_s} switch cannot break them silently. Their guards
     * ({@code |b| < EPS10}, {@code out.y <= EPS10}) are all false for {@code NaN}, which is
     * precisely why the defect was confined to the polar forward.
     */
    @Test
    public void nanAlreadyPropagatedFromTheObliqueEquatorialAndSphericalArms() {
        assertNanResult("oblique", "+proj=laea +lat_0=45 +lon_0=0 +datum=WGS84 +units=m",
                Double.NaN, Double.NaN);
        assertNanResult("equatorial", "+proj=laea +lat_0=0 +lon_0=0 +datum=WGS84 +units=m",
                Double.NaN, Double.NaN);
        assertNanResult("spherical polar", NORTH_POLAR_SPHERE, Double.NaN, Double.NaN);
    }

    /** The inverse has the same {@code switch} shape and must propagate too. */
    @Test
    public void nanPropagatesThroughTheInverse() {
        Projection p = projection(NORTH_POLAR);
        ProjCoordinate out = new ProjCoordinate();
        p.inverseProjectRadians(new ProjCoordinate(Double.NaN, Double.NaN), out);
        assertNanBits("inverse polar x", out.x);
        assertNanBits("inverse polar y", out.y);
    }

    // ------------------------------------------------------------------
    // Rule 2a: +/-Infinity is refused, and is a different outcome from NaN
    // ------------------------------------------------------------------

    /**
     * Both infinities, on both ordinates, must be {@link ErrorCause#INVALID_COORDINATE} and not a
     * {@code NaN} result — otherwise the first two rules are not distinguishable and a caller
     * cannot tell "you asked about an undefined point" from "you asked a well-formed question PROJ
     * refuses to answer".
     * <p>
     * Both are errors upstream too, by two different routes: {@code +Infinity} <em>is</em>
     * {@code HUGE_VAL}, so it trips {@code fwd_prepare}'s first test
     * ({@code 9.8.1:src/fwd.cpp:40}), and {@code -Infinity} trips the latitude range test or the
     * {@code |lambda| > 10} test.
     */
    @Test
    public void infiniteInputIsRefused() {
        assertInvalidCoordinate("+Inf longitude", NORTH_POLAR_LON0_ZERO,
                Double.POSITIVE_INFINITY, 0.5);
        assertInvalidCoordinate("-Inf longitude", NORTH_POLAR_LON0_ZERO,
                Double.NEGATIVE_INFINITY, 0.5);
        assertInvalidCoordinate("+Inf latitude", NORTH_POLAR_LON0_ZERO, 0.5,
                Double.POSITIVE_INFINITY);
        assertInvalidCoordinate("-Inf latitude", NORTH_POLAR_LON0_ZERO, 0.5,
                Double.NEGATIVE_INFINITY);
        // ... and with a central meridian, for the latitude, where no pre-reduction happens.
        assertInvalidCoordinate("+Inf latitude, lon_0=-150", NORTH_POLAR, 0.5,
                Double.POSITIVE_INFINITY);
        assertInvalidCoordinate("-Inf latitude, lon_0=-150", NORTH_POLAR, 0.5,
                Double.NEGATIVE_INFINITY);
    }

    /**
     * An infinite <em>longitude</em> combined with {@code lon_0 != 0} — <b>now tightened, because
     * the defect it was written around has been fixed.</b>
     *
     * <h4>What it used to say, and why it was loose</h4>
     *
     * <p>{@link ErrorCause#INVALID_COORDINATE} was <em>not</em> the answer here, and the cause was
     * shared code rather than {@code laea}:
     * {@code Projection.projectRadians(ProjCoordinate, ProjCoordinate)} subtracted
     * {@code projectionLongitude} and called {@code ProjectionMath.normalizeLongitude}
     * <em>before</em> {@code checkForwardDomain} ran, and {@code adjlon} turns {@code +Infinity}
     * into {@code NaN} ({@code inf - inf}, faithfully to {@code 9.8.1:src/adjlon.cpp}). The
     * {@code NaN} route then propagated what should have been refused. Upstream has no such hole:
     * {@code fwd.cpp:40} tests {@code HUGE_VAL} and {@code fwd.cpp:57-70} tests both ranges before
     * {@code fwd.cpp:105} touches {@code lam0}.
     *
     * <p>So this used to assert only the property that had to hold either way — "non-finite in never
     * yields a finite coordinate" — deliberately, so that it was not a pin on the wrong behaviour.
     * Both domain tests now run on the raw longitude, so the outcome is the same as it always was
     * with {@code lon_0=0}, and the assertion is the exact one:
     * {@link ErrorCause#INVALID_COORDINATE}, from {@link #assertInvalidCoordinate}, with
     * {@code lon_0 != 0} no longer making any difference.
     */
    @Test
    public void infiniteLongitudeWithACentralMeridianIsRefused() {
        assertInvalidCoordinate("+Inf longitude, lon_0=-150", NORTH_POLAR,
                Double.POSITIVE_INFINITY, 0.5);
        assertInvalidCoordinate("-Inf longitude, lon_0=-150", NORTH_POLAR,
                Double.NEGATIVE_INFINITY, 0.5);
        // The point of the fix: identical outcome with and without a central meridian.
        assertInvalidCoordinate("+Inf longitude, lon_0=0", NORTH_POLAR_LON0_ZERO,
                Double.POSITIVE_INFINITY, 0.5);
        assertInvalidCoordinate("-Inf longitude, lon_0=0", NORTH_POLAR_LON0_ZERO,
                Double.NEGATIVE_INFINITY, 0.5);
    }

    // ------------------------------------------------------------------
    // Rule 2b: finite but outside the contract is refused, distinctly
    // ------------------------------------------------------------------

    /**
     * A finite input PROJ refuses: a latitude past the pole by more than {@code PJ_EPS_LAT}, and a
     * longitude past {@code MAX_LAM_RAD}. Both must throw rather than propagate, which is what
     * keeps rule 2 separable from rule 1.
     */
    @Test
    public void finiteOutOfDomainInputIsRefused() {
        assertInvalidCoordinate("latitude 2 rad", NORTH_POLAR_LON0_ZERO, 0.5, 2.0);
        assertInvalidCoordinate("latitude -2 rad", NORTH_POLAR_LON0_ZERO, 0.5, -2.0);
        assertInvalidCoordinate("longitude 20 rad", NORTH_POLAR_LON0_ZERO, 20.0, 0.5);
        assertInvalidCoordinate("longitude -20 rad", NORTH_POLAR_LON0_ZERO, -20.0, 0.5);
    }

    /**
     * A finite in-contract input that {@code laea} genuinely cannot project: the point antipodal
     * to a polar aspect's origin. That is {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN}, matching
     * upstream's {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN} at
     * {@code 9.8.1:src/projections/laea.cpp:61-64}. Recorded here because it is the one remaining
     * way out of the polar arm and it must not become a fabricated coordinate either.
     */
    @Test
    public void theAntipodeIsADomainRefusalNotACoordinate() {
        try {
            ProjCoordinate out = project(NORTH_POLAR, 0.5, -Math.PI / 2);
            fail("the antipode of lat_0=90 must be refused, got (" + out.x + ", " + out.y + ")");
        } catch (ProjectionException e) {
            assertEquals(ErrorCause.COORDINATE_OUT_OF_DOMAIN, e.cause());
        }
    }

    // ------------------------------------------------------------------
    // The fix must move nothing else
    // ------------------------------------------------------------------

    /**
     * The legitimate zero. At the pole itself, with a <em>finite</em> longitude, the polar forward
     * really does return the origin, and the fix must not have turned that into {@code NaN}.
     * Asserted on the exact bit pattern of {@code +0.0}, because that is what distinguishes this
     * row from the defect: same value, different question. {@code gie} agrees —
     * {@code accept 0 90} against {@code expect 0 0} at {@code tolerance 0} passes on 9.8.1.
     */
    @Test
    public void thePoleItselfIsStillExactlyTheOrigin() {
        ProjCoordinate out = project(NORTH_POLAR, 0.0, Math.PI / 2);
        assertEquals("x at the pole", 0L, Double.doubleToRawLongBits(out.x));
        assertEquals("y at the pole", 0L, Double.doubleToRawLongBits(out.y));

        // ... and just inside the PJ_EPS_LAT slop band, where the latitude is clamped to the pole.
        ProjCoordinate clamped = project(NORTH_POLAR, 0.0, Math.PI / 2 + 1e-13);
        assertEquals("x just past the pole", 0L, Double.doubleToRawLongBits(clamped.x));
        assertEquals("y just past the pole", 0L, Double.doubleToRawLongBits(clamped.y));
    }

    /**
     * Frozen values, to bit-equality, for the two arms the fix touched. Three of the four are the
     * numbers measured on this tree <em>before</em> the {@code NaN} arm was added, so bit-equality
     * there is the evidence that the change is confined to the {@code NaN} case and moved no finite
     * coordinate at all.
     *
     * <h4>The one that moved, and why it was the pin that was wrong</h4>
     *
     * <p>Spherical {@code y} was re-measured on <b>2026-08-05</b>, when
     * {@code LambertAzimuthalEqualAreaProjection} was converted from {@code Math.sin}/{@code cos}
     * to {@code FastStrictTrig}: {@code 0x4158d1bd3117c812} -&gt; {@code 0x4158d1bd3117c814}.
     * The old pin was <em>architecture-dependent</em>, and this test was the one that caught it —
     * it passed on AArch64 and failed on x86-64. {@code Math.cos} is
     * {@code @IntrinsicCandidate}, and at this row's reduced longitude,
     * {@code lplam = 3.1179938779914944} (just under {@code pi}, the hard argument-reduction case),
     * the two intrinsics disagree in the last bit while fdlibm agrees with x86-64:
     *
     * <pre>
     *   AArch64  Math.cos       -&gt; 0xbfeffdb812a39370
     *   x86-64   Math.cos       -&gt; 0xbfeffdb812a39371
     *   both     StrictMath.cos -&gt; 0xbfeffdb812a39371
     * </pre>
     *
     * <p>So the new pin is not merely the stable value, it is the <b>correct</b> one: it is
     * {@code delta 0.0} from PROJ 9.8.1, where the retired AArch64 pin was 1.863e-9 m low. The
     * other three did not move — {@code Math.sin} happens to be bit-identical on both
     * architectures at these arguments, and the ellipsoidal {@code coslam} difference rounds away
     * before it reaches the metre-scale result. That is coincidence, not a property of
     * {@code sin}: it is intrinsified too.
     *
     * <p>They are not self-referential: each also agrees with PROJ 9.8.1 on the same proj-string
     * at {@code 0.5 rad, 0.5 rad}. Re-verified 2026-08-05 with the shipped 9.8.1 {@code proj}
     * (the {@code r} suffix makes {@code dmstor} read radians, so the pins are checked against the
     * exact same input doubles the test uses, with no degree round-trip in between):
     *
     * <pre>
     * $ printf '0.5r 0.5r\n' | proj -d 15 +proj=laea +lat_0=90 +lon_0=-150 +datum=WGS84 +units=m
     * 153639.182222650823	6509264.002158334479
     * $ printf '0.5r 0.5r\n' | proj -d 15 +proj=laea +lat_0=90 +lon_0=-150 +R=6378137 +units=m
     * 153567.541091538238106	6506228.767076510936022
     * </pre>
     *
     * The target is <b>projected</b>, so the metric is Euclidean in metres, not geodesic:
     * <table>
     * <caption>proj4j against PROJ 9.8.1, Euclidean metres</caption>
     * <tr><th>aspect</th><th>PROJ 9.8.1</th><th>this tree</th><th>|delta|</th></tr>
     * <tr><td>ellipsoidal N polar</td><td>153639.182222650823 6509264.002158334479</td>
     *     <td>153639.18222265082 6509264.0021583345</td><td>0.0, 0.0</td></tr>
     * <tr><td>spherical N polar</td><td>153567.541091538238 6506228.767076510936</td>
     *     <td>153567.54109153824 6506228.767076511</td><td>0.0, 0.0</td></tr>
     * </table>
     */
    @Test
    public void finiteInputIsUnmoved() {
        ProjCoordinate ell = project(NORTH_POLAR, 0.5, 0.5);
        assertEquals("ellipsoidal polar x", 0x4102c1397531262fL, Double.doubleToRawLongBits(ell.x));
        assertEquals("ellipsoidal polar y", 0x4158d4b400235cb6L, Double.doubleToRawLongBits(ell.y));
        assertCloseToProj("ellipsoidal polar x", 153639.182222650823, ell.x);
        assertCloseToProj("ellipsoidal polar y", 6509264.002158334479, ell.y);

        ProjCoordinate sph = project(NORTH_POLAR_SPHERE, 0.5, 0.5);
        assertEquals("spherical polar x", 0x4102befc5427cce7L, Double.doubleToRawLongBits(sph.x));
        assertEquals("spherical polar y", 0x4158d1bd3117c814L, Double.doubleToRawLongBits(sph.y));
        assertCloseToProj("spherical polar x", 153567.541091538238, sph.x);
        assertCloseToProj("spherical polar y", 6506228.767076510936, sph.y);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static Projection projection(String definition) {
        return new CRSFactory().createFromParameters("failopen", definition).getProjection();
    }

    private static ProjCoordinate project(String definition, double lamRad, double phiRad) {
        ProjCoordinate out = new ProjCoordinate();
        projection(definition).projectRadians(new ProjCoordinate(lamRad, phiRad), out);
        return out;
    }

    private static void assertNanResult(String what, String definition, double lam, double phi) {
        ProjCoordinate out;
        try {
            out = project(definition, lam, phi);
        } catch (ProjectionException e) {
            throw new AssertionError(what + ": NaN input must propagate as a RESULT, not throw."
                    + " gie scores isnan(got) && isnan(expected) as a pass and roundtrip scores"
                    + " all-NaN as residual 0, so throwing would lose corpus rows that pass. Got "
                    + e.cause() + ": " + e.getMessage());
        }
        assertNanBits(what + ": x", out.x);
        assertNanBits(what + ": y", out.y);
    }

    private static void assertInvalidCoordinate(String what, String definition,
                                                double lam, double phi) {
        try {
            ProjCoordinate out = project(definition, lam, phi);
            fail(what + ": expected INVALID_COORDINATE, got (" + out.x + ", " + out.y + ")");
        } catch (ProjectionException e) {
            assertEquals(what, ErrorCause.INVALID_COORDINATE, e.cause());
        }
    }

    /**
     * Asserts that {@code value} is a {@code NaN}, by its raw bits, and that it is in particular
     * not {@code 0.0} or {@code -0.0}.
     * <p>
     * The predicate is exponent-all-ones with a non-zero mantissa, <b>sign ignored</b>: quiet-NaN
     * sign and payload are architecture-dependent ({@code 0xfff8…} on x86-64, {@code 0x7ff8…} on
     * AArch64) and were observed to differ between {@code x} and {@code y} within one call on one
     * JVM, so pinning a pattern would be a platform-dependent test. Zero, by contrast, has one bit
     * pattern per sign on every platform, which is why it can be — and is — excluded exactly.
     *
     * @param what  what is being asserted, for the failure message
     * @param value the ordinate
     */
    private static void assertNanBits(String what, double value) {
        long bits = Double.doubleToRawLongBits(value);
        String hex = String.format("%016x", Long.valueOf(bits));
        assertFalse(what + ": got +0.0 (bits " + hex + "), the fabricated origin this test exists"
                + " to catch", bits == 0x0000000000000000L);
        assertFalse(what + ": got -0.0 (bits " + hex + ")", bits == 0x8000000000000000L);
        assertEquals(what + ": exponent must be all ones for a NaN, bits were " + hex,
                0x7ff0000000000000L, bits & 0x7ff0000000000000L);
        assertTrue(what + ": mantissa must be non-zero for a NaN (all-zero means Infinity), bits"
                + " were " + hex, (bits & 0x000fffffffffffffL) != 0L);
        // Belt and braces, in the language a reader expects. Deliberately last: isNaN alone is
        // what a naive version of this test would assert, and it is the weaker statement.
        assertTrue(what + ": Double.isNaN, bits were " + hex, Double.isNaN(value));
    }

    /**
     * @param what      the ordinate being compared, for the failure message
     * @param projValue the value PROJ 9.8.1's {@code gie} printed, to 12 decimal places
     * @param ours      this tree's value
     */
    private static void assertCloseToProj(String what, double projValue, double ours) {
        double delta = Math.abs(projValue - ours);
        assertTrue(what + ": " + ours + " differs from gie 9.8.1's " + projValue + " by " + delta
                + " m (Euclidean, projected target)", delta < 1e-6);
    }
}

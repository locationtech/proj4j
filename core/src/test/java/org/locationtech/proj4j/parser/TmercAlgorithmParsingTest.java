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
 */
package org.locationtech.proj4j.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.proj.TransverseMercatorProjection;

/**
 * {@code +approx}, {@code +algo}, and {@code utm}'s requirement of an ellipsoid.
 *
 * <h2>Why this matters more than a usual parameter</h2>
 *
 * <p>PROJ 9.8.1 ships Poder/Engsager as the built-in {@code tmerc} algorithm and this
 * library now matches it. {@code +approx} is the documented way to ask for the older
 * Evenden/Snyder series, so until it was dispatched there was <b>no way at all</b> to
 * request the previous behaviour — and the difference is unbounded away from the central
 * meridian.
 *
 * <h2>Reference values</h2>
 *
 * <p>All from the installed PROJ 9.8.1 ({@code Rel. 9.8.1, April 10th, 2026}), at
 * (12, 56) in degrees, output in projected metres:
 *
 * <pre>
 * $ echo "12 56" | proj -f '%.8f' &lt;definition&gt;
 *
 * +proj=utm +zone=32                        687071.43910944  6210141.32674801
 * +proj=utm +zone=32 +approx                687071.43911000  6210141.32674805
 * +proj=utm +zone=32 +algo=evenden_snyder   687071.43911000  6210141.32674805
 * +proj=utm +zone=32 +algo=poder_engsager   687071.43910944  6210141.32674801
 * +proj=utm +zone=32 +approx=f              687071.43910944  6210141.32674801
 * +proj=tmerc +ellps=GRS80                  746631.14610438  6273771.20419756
 * +proj=tmerc +ellps=GRS80 +approx          746631.14311771  6273771.20459692
 * +proj=tmerc +R=6400000                    747461.59436200  6320539.67139200
 * +proj=utm +zone=32 +datum=WGS84           687071.43910733  6210141.32687210
 * </pre>
 */
public class TmercAlgorithmParsingTest {

    /** PROJ printed 8 decimals of a metre; 1e-6 m is comfortably inside that. */
    private static final double TOL = 1e-6;

    private static final double[] UTM32_PODER = {687071.43910944, 6210141.32674801};
    private static final double[] UTM32_EVENDEN = {687071.43911000, 6210141.32674805};
    private static final double[] TMERC_PODER = {746631.14610438, 6273771.20419756};
    private static final double[] TMERC_EVENDEN = {746631.14311771, 6273771.20459692};

    private final CRSFactory crsFactory = new CRSFactory();

    private CoordinateReferenceSystem crs(String def) {
        return crsFactory.createFromParameters("test", def);
    }

    private void expect(String def, double[] expected) {
        ProjCoordinate out = new ProjCoordinate(Double.NaN, Double.NaN);
        crs(def).getProjection().project(new ProjCoordinate(12, 56), out);
        assertEquals(def + " easting", expected[0], out.x, TOL);
        assertEquals(def + " northing", expected[1], out.y, TOL);
    }

    private InvalidValueException rejected(String def) {
        try {
            crs(def);
        } catch (InvalidValueException e) {
            return e;
        } catch (Proj4jException e) {
            fail("expected InvalidValueException for [" + def + "] but got " + e);
        }
        fail("expected [" + def + "] to be rejected");
        return null;
    }

    // ------------------------------------------------------------------
    // utm is bound to the class that can honour +approx
    // ------------------------------------------------------------------

    /**
     * {@code Registry} used to bind {@code utm} to the Poder/Engsager-only
     * {@code ExtendedTransverseMercatorProjection}, which has no {@code setApprox}, so
     * {@code +approx} on a UTM definition had nowhere to go. Upstream has a single
     * implementation: {@code PJ_PROJECTION(utm)} and {@code PJ_PROJECTION(tmerc)} end in
     * the same {@code setup(P, algo)}.
     */
    @Test
    public void utmResolvesToTheClassThatCarriesBothAlgorithms() {
        assertTrue("utm must bind to TransverseMercatorProjection so +approx can reach it",
                crs("+proj=utm +zone=32 +ellps=GRS80").getProjection()
                        instanceof TransverseMercatorProjection);
    }

    // ------------------------------------------------------------------
    // +approx and +algo
    // ------------------------------------------------------------------

    @Test
    public void approxSelectsEvendenSnyderOnUtm() {
        expect("+proj=utm +zone=32", UTM32_PODER);
        expect("+proj=utm +zone=32 +approx", UTM32_EVENDEN);
    }

    @Test
    public void approxSelectsEvendenSnyderOnTmerc() {
        expect("+proj=tmerc +ellps=GRS80", TMERC_PODER);
        expect("+proj=tmerc +ellps=GRS80 +approx", TMERC_EVENDEN);
    }

    /**
     * The two algorithms must actually differ, or every assertion above would be
     * satisfied by a no-op dispatch. At 12&deg; from the central meridian the gap is
     * ~3 mm; it grows without bound further out.
     */
    @Test
    public void theTwoAlgorithmsGiveDifferentAnswers() {
        assertTrue("+approx must change the result",
                Math.abs(TMERC_PODER[0] - TMERC_EVENDEN[0]) > 1e-3);
        ProjCoordinate poder = new ProjCoordinate(Double.NaN, Double.NaN);
        ProjCoordinate evenden = new ProjCoordinate(Double.NaN, Double.NaN);
        crs("+proj=tmerc +ellps=GRS80").getProjection()
                .project(new ProjCoordinate(12, 56), poder);
        crs("+proj=tmerc +ellps=GRS80 +approx").getProjection()
                .project(new ProjCoordinate(12, 56), evenden);
        assertTrue("the parsed definitions must not resolve to the same algorithm",
                Math.abs(poder.x - evenden.x) > 1e-3);
    }

    @Test
    public void algoSelectsEitherAlgorithmByName() {
        expect("+proj=utm +zone=32 +algo=evenden_snyder", UTM32_EVENDEN);
        expect("+proj=utm +zone=32 +algo=poder_engsager", UTM32_PODER);
        // auto resolves to Poder/Engsager in this library, deliberately.
        expect("+proj=utm +zone=32 +algo=auto", UTM32_PODER);
    }

    /**
     * {@code +approx} is read through {@code pj_param}'s {@code b} sigil
     * ({@code tmerc.cpp:548}), so it is a boolean and not merely a presence flag:
     * {@code +approx=f} is explicitly false.
     */
    @Test
    public void approxIsABooleanNotJustAPresenceFlag() {
        expect("+proj=utm +zone=32 +approx=f", UTM32_PODER);
        expect("+proj=utm +zone=32 +approx=F", UTM32_PODER);
        expect("+proj=utm +zone=32 +approx=t", UTM32_EVENDEN);
        expect("+proj=utm +zone=32 +approx=T", UTM32_EVENDEN);
        // pj_param's b sigil errors on anything else rather than defaulting.
        rejected("+proj=utm +zone=32 +approx=yes");
    }

    /** {@code +approx} is tested before {@code +algo} and wins over it. */
    @Test
    public void approxOutranksAlgo() {
        expect("+proj=utm +zone=32 +approx +algo=poder_engsager", UTM32_EVENDEN);
        expect("+proj=utm +zone=32 +algo=poder_engsager +approx", UTM32_EVENDEN);
    }

    /** An unrecognised {@code +algo} is an error, not a silent default. */
    @Test
    public void anUnknownAlgoIsRejected() {
        InvalidValueException e = rejected("+proj=utm +zone=32 +algo=nope");
        assertTrue("message should name the offending value: " + e.getMessage(),
                e.getMessage().contains("nope") || e.getMessage().contains("algo"));
    }

    // ------------------------------------------------------------------
    // utm needs an ellipsoid; tmerc does not
    // ------------------------------------------------------------------

    /**
     * {@code PJ_PROJECTION(utm)} opens with {@code if (P->es == 0.0)} and fails
     * ({@code tmerc.cpp}). Verified against 9.8.1: both {@code +a=6400000} and
     * {@code +R=6400000} forms report "Invalid value for eccentricity: it should not be
     * zero". Before {@code utm} was rebound this was a {@code NullPointerException}.
     * <p>
     * Note the condition is <b>the operation being {@code utm}</b>, not {@code +zone}
     * having been given: upstream's check is the first statement of the entry point and
     * runs before {@code +zone} is looked at.
     */
    @Test
    public void utmOnASphereIsACleanInvalidParamValue() {
        for (String def : new String[]{
                "+proj=utm +zone=32 +a=6400000",
                "+proj=utm +zone=32 +R=6400000",
                "+proj=utm +a=6400000",
        }) {
            InvalidValueException e = rejected(def);
            assertSame("must be reported as an invalid parameter value, not as a crash",
                    ErrorCause.INVALID_PARAM_VALUE, e.cause());
            assertTrue("message should say why: " + e.getMessage(),
                    e.getMessage().contains("eccentricity"));
        }
    }

    /**
     * ...and the mirror image, which is the assertion that would have caught the
     * regression this guard could reintroduce: {@code +proj=tmerc} on a sphere is
     * <b>legal</b>, and dispatches to the spherical formulation.
     */
    @Test
    public void tmercOnASphereStillWorks() {
        CoordinateReferenceSystem c = crs("+proj=tmerc +R=6400000");
        assertNotNull(c);
        expect("+proj=tmerc +R=6400000", new double[]{747461.594362, 6320539.671392});
        expect("+proj=tmerc +a=6400000", new double[]{747461.594362, 6320539.671392});
    }

    /** An ellipsoidal utm must of course still parse and project. */
    @Test
    public void ellipsoidalUtmIsUnaffected() {
        expect("+proj=utm +zone=32 +ellps=GRS80", UTM32_PODER);
        // WGS84 rather than GRS80: differs in the 7th decimal of a metre, as it must.
        expect("+proj=utm +zone=32 +datum=WGS84",
                new double[]{687071.43910733, 6210141.32687210});
    }
}

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
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.proj.Projection;
import org.locationtech.proj4j.proj.TransverseMercatorProjection;
import org.locationtech.proj4j.util.MeridianArc;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * The four Evenden/Snyder transverse-Mercator defects, plus the {@link MeridianArc} re-point.
 *
 * <p>Reference rows: {@code builtins.gie:7096-7127} ({@code +proj=tmerc +ellps=GRS80}) and
 * {@code builtins.gie:7276-7360} (the spherical {@code +k=0.9 +lat_0=+/-40} blocks,
 * {@code tolerance 0.1 mm}, each row carrying {@code roundtrip 1}).
 *
 * <p>The defects, in {@code 9.8.1:src/projections/tmerc.cpp} terms:
 * <ol>
 * <li><b>{@code k_0} applied twice</b> in the spherical forward. {@code esp = k0} and
 *     {@code ml0 = .5*esp}, then proj4j wrote {@code ml0 * scaleFactor * log(...)} where
 *     {@code tmerc.cpp:130} is {@code ml0 * log(...)}. Easting was out by a factor of
 *     {@code k_0}: <b>78,358.1216</b> instead of 87,064.5795 at (1, -30) with {@code +k=0.9}.
 * <li><b>The wrong hemisphere test</b> in the spherical inverse: {@code if (y < 0) negate}
 *     where {@code tmerc.cpp:216} is {@code copysign(lp.phi, D)} with
 *     {@code D = phi0 + xy.y/esp}. They differ for every {@code lat_0 != 0}; at
 *     {@code +lat_0=40} the inverse of (0, -1005309.6491) came back as <b>-30</b>.
 * <li><b>A transcription error</b>: {@code 4095. + 1574.*t} where {@code tmerc.cpp:185} has
 *     <b>1575</b>.
 * <li><b>The wrong variable</b> in the ellipsoidal inverse's pole guard: {@code |xy.y|} — the
 *     northing — where {@code tmerc.cpp:165} tests the latitude just computed. Since
 *     {@code mlfn(pi/2) = 1.568164141 < pi/2} there is always a band in which the old guard is
 *     silent while the footpoint is already past the pole.
 * </ol>
 *
 * <p><b>Scope note, updated by Stage 6.</b> {@code +proj=tmerc} is now <b>Poder/Engsager</b>, as
 * 9.8.1 ships ({@code proj_internal.h:840-841}); the Evenden/Snyder series is reached through
 * {@code +approx}. So:
 * <ul>
 * <li>the two {@code +R=} blocks and {@code sphericalInverseUsesCopysignOfD} still exercise
 *     Evenden/Snyder <em>automatically</em>, because a sphere forces it
 *     ({@code tmerc.cpp:518-519});
 * <li>{@link #ellipsoidalPoleGuardTestsTheLatitudeNotTheNorthing} and
 *     {@link #ellipsoidalInverseUsesFifteenSeventyFive} select it explicitly through
 *     {@code setApprox(true)} — they are about defects <em>of that series</em>, and through a
 *     plain {@code +proj=tmerc} they were measuring the wrong algorithm (the {@code 1575} one by
 *     16 km, the pole guard by 90 degrees of longitude);
 * <li>the two remaining ellipsoidal methods now run Poder/Engsager and pass with room to spare.
 *     {@code org.locationtech.proj4j.tmerc.TmercGieCorpusTest} asserts that whole block at the
 *     file's own {@code tolerance 50 nm}, forward included, which this class could not.
 * </ul>
 * The algorithm difference itself — 1.8 um at 2 degrees from the central meridian, 0.83 mm at 6,
 * 4.0 m at 20, 4.0 km at 44.69 — is tabulated by
 * {@code TmercAlgorithmSelectionTest.movementFromEvendenSnyderToPoderEngsager}.
 */
public class TransverseMercatorApproxTest {

    private static final double RADIUS = 6400000.0;
    private static final double GRS80_A = 6378137.0;
    private static final double GRS80_ES = 0.006694380022900787;

    /** {@code builtins.gie:7278-7302}, {@code +proj=tmerc +R=6400000 +k=0.9 +lat_0=40}. */
    @Test
    public void sphericalForwardAppliesScaleFactorExactlyOnce() {
        GieRow row = GieRow.sphere("+proj=tmerc +R=" + RADIUS + " +k=0.9 +lat_0=40", RADIUS);
        row.expectForward(0, -30, 0, -7037167.5440, 0.1 * GieRow.MM);
        row.expectForward(1, -30, 87064.5795, -7037547.4590, 0.1 * GieRow.MM);
        row.expectForward(-1, -30, -87064.5795, -7037547.4590, 0.1 * GieRow.MM);
        row.expectForward(0, 30, 0, -1005309.6491, 0.1 * GieRow.MM);
        row.expectForward(0, 40, 0, 0, 0.1 * GieRow.MM);
        row.expectForward(1, 41, 75872.2182, 100965.3718, 0.1 * GieRow.MM);
    }

    /** The same block with {@code +lat_0=-40}, {@code builtins.gie:7336-7360}. */
    @Test
    public void sphericalForwardSouthernOriginMatchesGie() {
        GieRow row = GieRow.sphere("+proj=tmerc +R=" + RADIUS + " +k=0.9 +lat_0=-40", RADIUS);
        row.expectForward(0, -30, 0, 1005309.6491, 0.1 * GieRow.MM);
        row.expectForward(1, -30, 87064.5795, 1004929.7341, 0.1 * GieRow.MM);
        row.expectForward(-1, -30, -87064.5795, 1004929.7341, 0.1 * GieRow.MM);
        row.expectForward(0, 30, 0, 7037167.5440, 0.1 * GieRow.MM);
        row.expectForward(0, -40, 0, 0, 0.1 * GieRow.MM);
        row.expectForward(1, -41, 75872.2182, -100965.3718, 0.1 * GieRow.MM);
    }

    /**
     * Every one of those rows carries {@code roundtrip 1}, which is what catches the hemisphere
     * test. The (0, 30) row is the decisive one: its northing is <b>negative</b> while
     * {@code D = phi0 + y/esp} is positive, so the old {@code if (y < 0)} flipped it to -30 —
     * a 60 degree error, about 6,700 km.
     */
    @Test
    public void sphericalInverseUsesCopysignOfD() {
        GieRow row = GieRow.sphere("+proj=tmerc +R=" + RADIUS + " +k=0.9 +lat_0=40", RADIUS);

        ProjCoordinate lp = row.inverse(0, -1005309.6491);
        assertEquals("northing is negative but D is positive: was -30 before the fix",
                30.0, lp.y, 1e-8);

        row.expectRoundtrip(0, -30, 1, 0.1 * GieRow.MM);
        row.expectRoundtrip(1, -30, 1, 0.1 * GieRow.MM);
        row.expectRoundtrip(-1, -30, 1, 0.1 * GieRow.MM);
        row.expectRoundtrip(0, 30, 1, 0.1 * GieRow.MM);
        row.expectRoundtrip(0, 40, 1, 0.1 * GieRow.MM);
        row.expectRoundtrip(1, 41, 1, 0.1 * GieRow.MM);

        GieRow south = GieRow.sphere("+proj=tmerc +R=" + RADIUS + " +k=0.9 +lat_0=-40", RADIUS);
        assertEquals("mirror image of the same defect",
                -30.0, south.inverse(0, 1005309.6491).y, 1e-8);
        south.expectRoundtrip(0, 30, 1, 0.1 * GieRow.MM);
        south.expectRoundtrip(1, -41, 1, 0.1 * GieRow.MM);
    }

    /**
     * {@code builtins.gie:7114-7127} at the file's own {@code tolerance 50 nm}. The near-pole
     * row is the one {@link MeridianArc} unlocks: with {@code ProjectionMath.inv_mlfn} the
     * latitude was 89.99135362645478 against upstream's 89.99135362646302 — <b>916 nm</b>, or
     * eighteen times the bar — and the longitude was 0.35596960725299776 against
     * 0.35596960759234, <b>38 um</b> of arc. Both now land inside 7 nm.
     */
    @Test
    public void ellipsoidalInverseMatchesGieAt50Nanometres() {
        GieRow row = GieRow.grs80("+proj=tmerc +ellps=GRS80");
        row.expectInverse(200, 100, 0.00179663056816, 0.00090436947663, 50 * GieRow.NM);
        row.expectInverse(200, -100, 0.00179663056816, -0.00090436947663, 50 * GieRow.NM);
        row.expectInverse(-200, 100, -0.00179663056816, 0.00090436947663, 50 * GieRow.NM);
        row.expectInverse(-200, -100, -0.00179663056816, -0.00090436947663, 50 * GieRow.NM);
        row.expectInverse(6, 1.0001e7, 0.35596960759234, 89.99135362646302, 50 * GieRow.NM);
    }

    /**
     * {@code builtins.gie:7099-7110} forward. Asserted at 0.1 mm, not the file's 50 nm: the
     * residual is the Evenden/Snyder versus Poder/Engsager difference, which is upstream's
     * choice of algorithm rather than an error in this series. Measured here: 1.81 um at
     * 2 degrees, 90.5 nm at the pole row.
     */
    @Test
    public void ellipsoidalForwardMatchesGieWithinTheAlgorithmDifference() {
        GieRow row = GieRow.grs80("+proj=tmerc +ellps=GRS80");
        row.expectForward(2, 1, 222650.796797586, 110642.229411933, 0.1 * GieRow.MM);
        row.expectForward(2, -1, 222650.796797586, -110642.229411933, 0.1 * GieRow.MM);
        row.expectForward(-2, 1, -222650.796797586, 110642.229411933, 0.1 * GieRow.MM);
        row.expectForward(30, 89.9999, 5.584698978, 10001956.056248082, 0.1 * GieRow.MM);
    }

    /**
     * A GRS80 {@code tmerc} pinned to the Evenden/Snyder series.
     *
     * <p><b>Why this is no longer a plain {@code +proj=tmerc} proj-string.</b> Stage 6 switched
     * {@code +proj=tmerc} to Poder/Engsager, which is what PROJ 9.8.1 ships. The four defects this
     * class covers are defects <em>of the approximate series</em>, which is still reachable — and
     * still used for every sphere — so the tests must select it explicitly. {@code +approx} has no
     * parser plumbing yet, hence the setter.
     */
    private static TransverseMercatorProjection approxGrs80() {
        TransverseMercatorProjection p = new TransverseMercatorProjection();
        p.setEllipsoid(Ellipsoid.GRS80);
        p.setApprox(true);
        p.initialize();
        return p;
    }

    /**
     * The pole guard now tests the latitude, so it fires in the band
     * {@code mlfn(pi/2) <= |mu| < pi/2} where the old {@code |y|} test was silent. Inside that
     * band the old code let {@code cosphi} go negative, the series diverged (measured
     * 1.8e18 rad at one point), and a NaN was minted downstream in {@code ProjectionMath.tsfn}.
     */
    @Test
    public void ellipsoidalPoleGuardTestsTheLatitudeNotTheNorthing() {
        MeridianArc grs80 = MeridianArc.fromEs(GRS80_ES);
        double quadrant = grs80.mlfn(ProjectionMath.HALFPI, 1.0, 0.0);
        assertTrue("mlfn(pi/2) must be strictly below pi/2 for GRS80, else there is no band",
                quadrant < ProjectionMath.HALFPI);
        assertEquals("the normalised meridian quadrant for GRS80", 1.5681641, quadrant, 1e-7);

        Projection approx = approxGrs80();
        for (double mu : new double[] {1.5682, 1.5695, 1.5707, -1.5682, -1.5695, -1.5707}) {
            double northing = mu * GRS80_A;
            ProjCoordinate lp = new ProjCoordinate();
            approx.inverseProject(new ProjCoordinate(0.0, northing), lp);
            assertEquals("must clamp to a pole at mu = " + mu, 90.0, Math.abs(lp.y), 1e-9);
            assertEquals("longitude is zeroed with the clamp, per tmerc.cpp:166",
                    0.0, lp.x, 0.0);
            assertTrue("sign must follow the northing, got " + lp.y,
                    (lp.y > 0) == (mu > 0));
        }
    }

    // -- the 1575 coefficient ---------------------------------------------------------------

    private static final double FC2 = 0.5;
    private static final double FC4 = 0.08333333333333333333;
    private static final double FC6 = 0.03333333333333333333;
    private static final double FC8 = 0.01785714285714285714;

    /**
     * An independent transcription of {@code 9.8.1:tmerc.cpp:175-186}'s latitude correction with
     * the innermost coefficient left free, so that the projection's own answer can be matched
     * against 1575 and distinguished from 1574.
     */
    private static double approxInverseLatitude(double x, double y, double es, double coeff) {
        MeridianArc en = MeridianArc.fromEs(es);
        final double esp = es / (1. - es);
        double phi = en.invMlfn(y);
        double sinphi = Math.sin(phi);
        double cosphi = Math.cos(phi);
        double t = Math.abs(cosphi) > 1e-10 ? sinphi / cosphi : 0.;
        final double n = esp * cosphi * cosphi;
        double con = 1. - es * sinphi * sinphi;
        final double d = x * Math.sqrt(con);
        con *= t;
        t *= t;
        final double ds = d * d;
        return phi - (con * ds / (1. - es)) * FC2
                * (1. - ds * FC4 * (5. + t * (3. - 9. * n) + n * (1. - 4 * n)
                - ds * FC6 * (61. + t * (90. - 252. * n + 45. * t) + 46. * n
                - ds * FC8 * (1385. + t * (3633. + t * (4095. + coeff * t))))));
    }

    /**
     * The {@code 1575} term lives under {@code FC4*FC6*FC8}, so it is weighted by
     * {@code ds^3 * t^3 / 20160} and is invisible near the central meridian — which is why it
     * survived in proj4j for twenty years and why no corpus row catches it. Far out it is not
     * invisible at all: at {@code x = 0.65 a} it is worth about 1.3 m.
     */
    @Test
    public void ellipsoidalInverseUsesFifteenSeventyFive() {
        final double x = 0.65;   // in units of the semi-major axis, about 4,146 km
        final double y = 0.78;   // about 4,975 km, i.e. latitude 44.6 degrees

        double with1575 = approxInverseLatitude(x, y, GRS80_ES, 1575.0);
        double with1574 = approxInverseLatitude(x, y, GRS80_ES, 1574.0);
        double separation = Math.abs(with1575 - with1574) * GRS80_A;
        assertTrue("the two coefficients must be distinguishable at this easting, "
                + "otherwise the test proves nothing; separation was " + separation + " m",
                separation > 0.1);

        Projection tmerc = approxGrs80();
        ProjCoordinate got = new ProjCoordinate();
        tmerc.inverseProjectRadians(new ProjCoordinate(x * GRS80_A, y * GRS80_A), got);

        double err1575 = Math.abs(got.y - with1575) * GRS80_A;
        double err1574 = Math.abs(got.y - with1574) * GRS80_A;
        assertTrue("the projection must agree with the 1575 series; missed by " + err1575 + " m",
                err1575 < 1e-6);
        assertTrue("...and must not agree with the 1574 series; missed by only " + err1574 + " m",
                err1574 > 0.1);
    }
}

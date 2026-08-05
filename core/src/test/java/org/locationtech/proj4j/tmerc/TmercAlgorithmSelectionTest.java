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

package org.locationtech.proj4j.tmerc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.proj.ExtendedTransverseMercatorProjection;
import org.locationtech.proj4j.proj.Projection;
import org.locationtech.proj4j.proj.TransverseMercatorProjection;
import org.locationtech.proj4j.proj.TransverseMercatorProjection.Algorithm;

/**
 * Which of the two transverse-Mercator algorithms runs, and how far apart they are.
 *
 * <p>This is the regression net for the largest backward-compatibility change in the 9.8.1
 * migration. {@code +proj=tmerc} used to be Evenden/Snyder unconditionally and is now
 * Poder/Engsager, matching what PROJ 9.8.1 ships ({@code proj_internal.h:840-841},
 * {@code data/proj.ini}). {@link #movementFromEvendenSnyderToPoderEngsager()} quantifies the
 * movement and prints the table the release notes need; the rest assert that the escape hatches
 * really reach the old arithmetic, because a switch nobody can turn is not an escape hatch.
 */
public class TmercAlgorithmSelectionTest {

    private static final CRSFactory CRS = new CRSFactory();

    /** A GRS80 {@code tmerc} in the given mode, built without going through the parser. */
    private static TransverseMercatorProjection grs80(Algorithm algorithm, boolean approx) {
        TransverseMercatorProjection p = new TransverseMercatorProjection();
        p.setEllipsoid(Ellipsoid.GRS80);
        p.setAlgorithm(algorithm);
        p.setApprox(approx);
        p.initialize();
        return p;
    }

    private static ProjCoordinate forwardRadians(Projection p, double lonDeg, double latDeg) {
        ProjCoordinate out = new ProjCoordinate();
        p.projectRadians(new ProjCoordinate(Math.toRadians(lonDeg), Math.toRadians(latDeg)), out);
        return out;
    }

    // ------------------------------------------------------------------------- what is selected

    /** The default is what 9.8.1 ships, not what Proj4J 1.4.3 did. */
    @Test
    public void theDefaultIsPoderEngsager() {
        assertEquals(Algorithm.PODER_ENGSAGER, grs80(Algorithm.PODER_ENGSAGER, false)
                .getEffectiveAlgorithm());
        TransverseMercatorProjection fresh = new TransverseMercatorProjection();
        fresh.setEllipsoid(Ellipsoid.GRS80);
        fresh.initialize();
        assertEquals("an untouched ellipsoidal tmerc must be Poder/Engsager",
                Algorithm.PODER_ENGSAGER, fresh.getEffectiveAlgorithm());
        assertEquals("and so must one built through the parser", Algorithm.PODER_ENGSAGER,
                ((TransverseMercatorProjection) CRS
                        .createFromParameters("t", "+proj=tmerc +ellps=GRS80 +no_defs")
                        .getProjection()).getEffectiveAlgorithm());
    }

    /** {@code +approx} is checked before {@code +algo} and wins over it, {@code tmerc.cpp:552-561}. */
    @Test
    public void approxWinsOverAlgo() {
        assertEquals(Algorithm.EVENDEN_SNYDER,
                grs80(Algorithm.PODER_ENGSAGER, true).getEffectiveAlgorithm());
        assertEquals(Algorithm.EVENDEN_SNYDER, grs80(Algorithm.AUTO, true).getEffectiveAlgorithm());
    }

    /**
     * {@code +algo=auto} is accepted and resolves to Poder/Engsager — a deliberate divergence
     * from upstream's data-dependent branch, which introduces a discontinuity in the output field
     * at its switch boundary. {@code builtins.gie:7379-7407} only asserts that AUTO agrees with
     * {@code poder_engsager} to 0.1 mm, and it does so exactly.
     */
    @Test
    public void autoResolvesToPoderEngsager() {
        TransverseMercatorProjection auto = grs80(Algorithm.AUTO, false);
        assertEquals(Algorithm.PODER_ENGSAGER, auto.getEffectiveAlgorithm());

        // Upstream's AUTO would take the *approximate* branch here (|lam| <= 3 deg), so this is
        // the point where the divergence is observable at all: it must agree with the exact
        // series bit for bit, not merely within 0.1 mm.
        ProjCoordinate a = forwardRadians(auto, 2.9, 40);
        ProjCoordinate b = forwardRadians(grs80(Algorithm.PODER_ENGSAGER, false), 2.9, 40);
        assertEquals(b.x, a.x, 0.0);
        assertEquals(b.y, a.y, 0.0);
    }

    /** {@code tmerc.cpp:518-519}: a sphere forces the approximate series whatever was asked for. */
    @Test
    public void aSphereForcesEvendenSnyder() {
        TransverseMercatorProjection p = new TransverseMercatorProjection();
        p.setEllipsoid(new Ellipsoid("unit", 6400000.0, 6400000.0, 0.0, "sphere"));
        p.setAlgorithm(Algorithm.PODER_ENGSAGER);
        p.initialize();
        assertEquals(Algorithm.EVENDEN_SNYDER, p.getEffectiveAlgorithm());

        // ... and it still projects. builtins.gie:7130-7218 is 25 rows of spherical tmerc.
        ProjCoordinate xy = forwardRadians(p, 2, 1);
        assertEquals(223413.466406322, xy.x, 1.0e-4);
        assertEquals(111769.145040597, xy.y, 1.0e-4);
    }

    /** An unknown {@code +algo} is an error, {@code tmerc.cpp:575-578}. */
    @Test
    public void anUnknownAlgoIsRejected() {
        try {
            grs80(Algorithm.PODER_ENGSAGER, false).setAlgorithm("engsager_poder");
            fail("expected an unknown +algo value to be rejected");
        } catch (InvalidValueException e) {
            assertEquals(ErrorCause.INVALID_PARAM_VALUE, e.cause());
        }
    }

    /**
     * Two definitions that differ only by the algorithm must not compare equal, or the transform
     * cache will hand one out for the other.
     */
    @Test
    public void theAlgorithmParticipatesInEquality() {
        assertNotEquals(grs80(Algorithm.PODER_ENGSAGER, false), grs80(Algorithm.PODER_ENGSAGER, true));
        assertEquals(grs80(Algorithm.AUTO, false), grs80(Algorithm.PODER_ENGSAGER, false));
    }

    // -------------------------------------------------------------------- the movement, measured

    /**
     * <b>The algorithm-movement table.</b> Evenden/Snyder minus Poder/Engsager, GRS80,
     * {@code k_0 = 1}, {@code lat_0 = 0}, at a spread of distances from the central meridian.
     *
     * <p>Printed as well as asserted: this is the number the release notes have to carry, and the
     * golden-master regime needs it declared as intended movement rather than discovered as drift.
     *
     * <p>The three anchors that are upstream's own, not this measurement's:
     * {@code builtins.gie:7466} pins Evenden/Snyder at 6&deg;/lat 0 to {@code 669149.3474} where
     * {@code :7436} pins Poder/Engsager to {@code 669149.3483} — 0.9 mm — and comments out that
     * row's {@code roundtrip 1} with the note "Small difference with poder_engsager". At
     * {@code accept 44.69 35.37} ({@code :7118}) the 50 nm bar is missed by the approximate
     * series by about 1.5 km.
     */
    @Test
    public void movementFromEvendenSnyderToPoderEngsager() {
        TransverseMercatorProjection approx = grs80(Algorithm.PODER_ENGSAGER, true);
        TransverseMercatorProjection exact = grs80(Algorithm.PODER_ENGSAGER, false);

        double[] longitudes = {0.5, 2, 3, 6, 10, 15, 20, 30, 44.69, 60, 80};
        double[] latitudes = {0, 35.37, 60, 85};

        StringBuilder table = new StringBuilder();
        table.append("\nEvenden/Snyder minus Poder/Engsager, +proj=tmerc +ellps=GRS80, "
                + "metres of horizontal displacement\n");
        table.append(String.format("%10s", "lon-lon_0"));
        for (double lat : latitudes) {
            table.append(String.format("%18s", "lat " + lat));
        }
        table.append('\n');

        double worstNearCm = 0;
        double at6 = 0;
        double at20 = 0;
        double at4469 = 0;
        for (double lon : longitudes) {
            table.append(String.format("%9.2f ", lon));
            for (double lat : latitudes) {
                double d = displacement(approx, exact, lon, lat);
                table.append(String.format("%18s", format(d)));
                if (lon <= 3) {
                    worstNearCm = Math.max(worstNearCm, d);
                }
                if (lon == 6 && lat == 0) {
                    at6 = d;
                }
                if (lon == 20 && lat == 0) {
                    at20 = d;
                }
                if (lon == 44.69 && lat == 35.37) {
                    at4469 = d;
                }
            }
            table.append('\n');
        }
        System.out.println(table);

        // The claims the release notes make, asserted so they cannot rot.
        assertTrue("within 3 deg of the central meridian the movement must stay under 0.1 mm, "
                + "was " + format(worstNearCm), worstNearCm < 1.0e-4);
        assertTrue("at 6 deg the movement must be about 0.9 mm, was " + format(at6),
                at6 > 5.0e-4 && at6 < 2.0e-3);
        assertTrue("at 20 deg it must be metres, was " + format(at20), at20 > 1.0 && at20 < 100.0);
        assertTrue("at the corpus's own 44.69 deg row it must be about 1.5 km, was "
                + format(at4469), at4469 > 500.0 && at4469 < 5000.0);
    }

    /**
     * The two algorithms must differ <em>measurably</em> at 6&deg;: that is what proves the
     * {@code +approx} switch is wired to something and not quietly ignored. Both are compared to
     * upstream's own numbers, so the direction of the difference is pinned too.
     */
    @Test
    public void approxAndDefaultDifferAtSixDegreesAndBothMatchUpstream() {
        double approxX = forwardRadians(grs80(Algorithm.PODER_ENGSAGER, true), 6, 0).x;
        double exactX = forwardRadians(grs80(Algorithm.PODER_ENGSAGER, false), 6, 0).x;

        // builtins.gie:7466 (evenden_snyder) and :7436 (poder_engsager), tolerance 0.1 mm.
        assertEquals("builtins.gie:7466", 669149.3474, approxX, 1.0e-4);
        assertEquals("builtins.gie:7436", 669149.3483, exactX, 1.0e-4);
        assertTrue("the two algorithms must be measurably apart at 6 deg, else +approx is a no-op",
                Math.abs(approxX - exactX) > 5.0e-4);
    }

    /**
     * {@code +approx} reproduces the Evenden/Snyder series exactly, and the default reproduces
     * {@code +proj=etmerc} exactly. Neither is "close to": {@code tmerc} with {@code +approx} must
     * be bit-identical to the old series and {@code tmerc} by default bit-identical to
     * {@code etmerc}, or the delegation has introduced a difference of its own.
     */
    @Test
    public void theDefaultIsBitIdenticalToEtmerc() {
        TransverseMercatorProjection tmerc = grs80(Algorithm.PODER_ENGSAGER, false);
        ExtendedTransverseMercatorProjection etmerc = new ExtendedTransverseMercatorProjection();
        etmerc.setEllipsoid(Ellipsoid.GRS80);
        etmerc.initialize();

        // Kept inside |Ce| <= 2.623395162778. At lat 0 and lam 90 exactly the point is genuinely
        // at infinity for a transverse Mercator, and both classes throw there; that is
        // domainAndFailClosedTest's business, not this one's.
        for (double lon : new double[] {0, 1, 6, 20, 44.69, 60}) {
            for (double lat : new double[] {-85, -35.37, 0, 1, 60, 89.9999}) {
                ProjCoordinate a = forwardRadians(tmerc, lon, lat);
                ProjCoordinate b = forwardRadians(etmerc, lon, lat);
                assertEquals("tmerc vs etmerc easting at (" + lon + ", " + lat + ")", b.x, a.x, 0.0);
                assertEquals("tmerc vs etmerc northing at (" + lon + ", " + lat + ")", b.y, a.y, 0.0);
            }
        }
    }

    /**
     * {@code builtins.gie:7095-7127} at the file's own {@code tolerance 50 nm}, forward as well as
     * inverse. Before the switch the forward rows could only be asserted at 0.1 mm and the
     * 3,900 km row not at all; that they now pass at 50 nm <em>is</em> the stage.
     */
    @Test
    public void theDefaultMeetsTheFiftyNanometreBarWhereTheOldSeriesCouldNot() {
        CoordinateReferenceSystem projected = TmercGieRunner
                .projected("+proj=tmerc +ellps=GRS80");
        Projection p = projected.getProjection();

        ProjCoordinate got = forwardRadians(p, 44.69, 35.37);
        double dx = Math.abs(got.x - 4168136.489446198);
        double dy = Math.abs(got.y - 4985511.302287407);
        assertTrue("builtins.gie:7118 forward at 3900 km from the central meridian: off by ("
                + dx + ", " + dy + ") m against a 50 nm bar",
                Math.sqrt(dx * dx + dy * dy) <= 50.0e-9);

        // And the approximate series is off by kilometres there, which is why upstream's tmerc
        // rows are identical to its etmerc rows.
        ProjCoordinate old = forwardRadians(grs80(Algorithm.PODER_ENGSAGER, true), 44.69, 35.37);
        assertTrue("the approximate series must be the thing that could not meet this bar",
                Math.abs(old.x - 4168136.489446198) > 100.0);
    }

    /** Horizontal displacement in metres between the two algorithms at one point. */
    private static double displacement(TransverseMercatorProjection approx,
            TransverseMercatorProjection exact, double lonDeg, double latDeg) {
        ProjCoordinate a = forwardRadians(approx, lonDeg, latDeg);
        ProjCoordinate b = forwardRadians(exact, lonDeg, latDeg);
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static String format(double metres) {
        if (metres == 0) {
            return "0";
        }
        if (metres < 1.0e-6) {
            return String.format("%.3f nm", metres * 1.0e9);
        }
        if (metres < 1.0e-3) {
            return String.format("%.3f um", metres * 1.0e6);
        }
        if (metres < 1.0) {
            return String.format("%.4f mm", metres * 1.0e3);
        }
        if (metres < 1000.0) {
            return String.format("%.3f m", metres);
        }
        return String.format("%.3f km", metres / 1000.0);
    }
}

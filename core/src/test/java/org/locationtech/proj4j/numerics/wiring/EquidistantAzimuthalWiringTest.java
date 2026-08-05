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

package org.locationtech.proj4j.numerics.wiring;

import static org.junit.Assert.assertTrue;
import static org.locationtech.proj4j.numerics.wiring.GieCase.MM;
import static org.locationtech.proj4j.numerics.wiring.GieCase.NM;

import org.junit.Test;
import org.locationtech.proj4j.util.MeridianArc;

/**
 * {@code EquidistantAzimuthalProjection}'s two polar aspects re-pointed at {@link MeridianArc}.
 *
 * <p>Only the polar aspects use the meridian arc; the equatorial and oblique ellipsoidal aspects go
 * through the geodesic solver and are untouched, and are asserted here purely as no-movement guards.
 *
 * <p><b>The corpus cannot resolve this change and it is worth being explicit about why.</b> The
 * ellipsoidal polar blocks, {@code builtins.gie:216-253}, are at {@code tolerance 0.1 m} because
 * their expectations come from Snyder's table 31, which is printed to 0.1 m. The 4.9 um the change is
 * worth is 20,000 times inside that. So the corpus rows are asserted for no-regression and the
 * old-versus-new separation is made against the independent quadrature, exactly as the numerics
 * reference requires for {@code mlfn}.
 */
public class EquidistantAzimuthalWiringTest {

    /** International 1924, {@code +ellps=intl}: a = 6378388, rf = 297. */
    private static final double INTL_A = 6378388.0;
    private static final double INTL_RF = 297.0;
    private static final double INTL_ES = intlEs();

    private static final GieCase NORTH =
            GieCase.ellipsoid("+proj=aeqd +ellps=intl +lat_0=90", "intl", INTL_A, INTL_RF);
    private static final GieCase SOUTH =
            GieCase.ellipsoid("+proj=aeqd +ellps=intl +lat_0=-90", "intl", INTL_A, INTL_RF);

    /**
     * {@code builtins.gie:216-234}, the northern polar ellipsoidal aspect from Snyder p. 198,
     * table 31, at {@code tolerance 0.1 m} with {@code roundtrip 100} on every row.
     */
    @Test
    public void northPolarEllipsoidalMatchesGie() {
        NORTH.expectForward(0, 90, 0, 0, 0.1);
        NORTH.expectRoundtrip(0, 90, 100, 0.1);
        NORTH.expectForward(0, 85, 0, -558485.4, 0.1);
        NORTH.expectRoundtrip(0, 85, 100, 0.1);
        NORTH.expectForward(0, 80, 0, -1116885.2, 0.1);
        NORTH.expectRoundtrip(0, 80, 100, 0.1);
        NORTH.expectForward(0, 70, 0, -2233100.9, 0.1);
        NORTH.expectRoundtrip(0, 70, 100, 0.1);
    }

    /** {@code builtins.gie:236-253}, the southern polar ellipsoidal aspect. */
    @Test
    public void southPolarEllipsoidalMatchesGie() {
        SOUTH.expectForward(0, -90, 0, 0, 0.1);
        SOUTH.expectRoundtrip(0, -90, 100, 0.1);
        SOUTH.expectForward(0, -85, 0, 558485.4, 0.1);
        SOUTH.expectRoundtrip(0, -85, 100, 0.1);
        SOUTH.expectForward(0, -80, 0, 1116885.2, 0.1);
        SOUTH.expectRoundtrip(0, -80, 100, 0.1);
        SOUTH.expectForward(0, -70, 0, 2233100.9, 0.1);
        SOUTH.expectRoundtrip(0, -70, 100, 0.1);
    }

    /**
     * The polar forward against the independent quadrature. At longitude 0 the northing is
     * {@code -(Mp - M(phi))}, so with {@code Mp} the quarter meridian this isolates the arc.
     *
     * <p>Before: up to 4.92 um. After: bounded by the reference's own ~1 nm.
     */
    @Test
    public void polarForwardBeatsTheDeprecatedSeries() {
        Legacy.AeqdPolar old = new Legacy.AeqdPolar(INTL_A, INTL_ES, true);
        double quarter = INTL_A * GieCase.meridianArcReference(Math.PI / 2.0, INTL_ES);

        double worstBefore = 0.0;
        double worstNow = 0.0;
        double worstBeforeAt = Double.NaN;
        for (int i = 0; i <= 900; i++) {
            double lat = i / 10.0;
            double arc = INTL_A * GieCase.meridianArcReference(Math.toRadians(lat), INTL_ES);
            double reference = -(quarter - arc);
            double before = Math.abs(old.forward(0.0, lat)[1] - reference);
            double now = Math.abs(NORTH.forward(0, lat).y - reference);
            if (before > worstBefore) {
                worstBefore = before;
                worstBeforeAt = lat;
            }
            worstNow = Math.max(worstNow, now);
        }
        assertTrue("the es-series error should peak in the sixties or seventies, at " + worstBeforeAt,
                worstBeforeAt > 55.0 && worstBeforeAt < 85.0);
        assertTrue("the es-series should be micrometres out at the peak, measured "
                + worstBefore + " m", worstBefore > 3.0e-6);
        assertTrue("the n-series must be inside the quadrature's own accuracy, measured "
                + worstNow + " m", worstNow < 20 * NM);
    }

    /**
     * The polar inverse against the same reference: feed the exact northing for a latitude and
     * demand it back. The old path was a ten-step Newton loop with a data-dependent trip count;
     * this one is closed form.
     */
    @Test
    public void polarInverseBeatsTheDeprecatedNewtonLoop() {
        Legacy.AeqdPolar old = new Legacy.AeqdPolar(INTL_A, INTL_ES, true);
        double quarter = INTL_A * GieCase.meridianArcReference(Math.PI / 2.0, INTL_ES);

        double worstBefore = 0.0;
        double worstNow = 0.0;
        for (int i = 0; i <= 890; i++) {
            double lat = i / 10.0;
            double arc = INTL_A * GieCase.meridianArcReference(Math.toRadians(lat), INTL_ES);
            double y = -(quarter - arc);
            double before = Math.abs(old.latitude(0.0, y) - lat) * Math.PI / 180.0 * INTL_A;
            double now = Math.abs(NORTH.inverse(0, y).y - lat) * Math.PI / 180.0 * INTL_A;
            worstBefore = Math.max(worstBefore, before);
            worstNow = Math.max(worstNow, now);
        }
        assertTrue("the deprecated Newton loop should be micrometres out, measured "
                + worstBefore + " m", worstBefore > 3.0e-6);
        assertTrue("the closed form must be nanometres, measured " + worstNow + " m",
                worstNow < 50 * NM);
    }

    /**
     * {@code builtins.gie:139-158}, the equatorial ellipsoidal aspect at {@code tolerance 0.1 mm}
     * with {@code roundtrip 100}. This goes through the geodesic solver and does not touch the
     * meridian arc; asserted because the file's {@code initialize()} was restructured.
     */
    @Test
    public void equatorialEllipsoidalIsUnmoved() {
        GieCase r = GieCase.grs80("+proj=aeqd +ellps=GRS80 +lat_0=0");
        r.expectForward(0, 90, 0, 10001965.7292, 0.1 * MM);
        r.expectForward(0, 0, 0, 0, 0.1 * MM);
        r.expectForward(90, 0, 10018754.1714, 0, 0.1 * MM);
        r.expectForward(45, 45, 3860398.3783, 5430089.0490, 0.1 * MM);
        r.expectRoundtrip(45, 45, 100, 0.1 * MM);
        r.expectRoundtrip(0, 0, 100, 0.1 * MM);
    }

    /**
     * {@code builtins.gie:109-131}, the spherical equatorial aspect from Snyder pp. 196-197,
     * table 30, {@code tolerance 0.1 mm} with {@code roundtrip 100}. Spherical, so no series.
     */
    @Test
    public void sphericalEquatorialIsUnmoved() {
        GieCase r = GieCase.sphere("+proj=aeqd +R=1 +lat_0=0", 1.0);
        r.expectForward(0, 0, 0, 0, 0.1 * MM);
        r.expectForward(0, 90, 0, 1.57080, 0.1 * MM);
        r.expectForward(10, 80, 0.04281, 1.39829, 0.1 * MM);
        r.expectForward(40, 30, 0.62896, 0.56493, 0.1 * MM);
        r.expectForward(90, 0, 1.57080, 0, 0.1 * MM);
    }

    /**
     * {@code builtins.gie:340}, the oblique ellipsoidal aspect. Geodesic-solver based, so this is a
     * no-movement guard on {@code initialize()}'s restructuring.
     */
    @Test
    public void obliqueEllipsoidalIsUnmoved() {
        GieCase r = GieCase.grs80("+proj=aeqd +ellps=GRS80 +lat_0=45");
        r.expectRoundtrip(2, 46, 100, 0.1 * MM);
        r.expectRoundtrip(-2, 44, 100, 0.1 * MM);
    }

    /**
     * {@code builtins.gie:160-181}: the near-{@code lat_0} rows on a perfect sphere and on an
     * ellipsoid one micrometre away from one, at {@code tolerance 1 mm} with {@code roundtrip 1}.
     * The pair exists upstream to catch a discontinuity between the two code paths at
     * {@code es -> 0}.
     *
     * <p><b>The spherical-path rows now pass, and this assertion has been inverted.</b> It used to
     * record a measured 0.147 m round-trip deviation against the block's 1 mm bar as a pre-existing
     * defect, with an explicit note to tighten it if the defect was ever fixed. It has been: the
     * cause was two deviations from {@code aeqd.cpp} in the <em>spherical</em> oblique branch, both
     * now corrected — {@code TOL} was {@code 1e-8} where upstream is {@code 1e-14}, and inside that
     * band the branch returned {@code (0, 0)} where {@code aeqd.cpp:155} and {@code :182} hand the
     * point to the <em>geodesic</em> forward ({@code return aeqd_e_forward(lp, P)}). Between them
     * they put a dead zone of radius {@code acos(1 - 1e-8)}, about <b>900 m</b>, around the centre of
     * every spherical oblique or equatorial {@code aeqd}. The assertion is now the corpus's own
     * 1 mm bar.
     *
     * <p>The near-sphere <em>ellipsoid</em> of the second block goes through the geodesic solver and
     * does close, which is the more interesting half of upstream's pair.
     */
    @Test
    public void nearlySphericalPairIsUnmoved() {
        GieCase s = new GieCase(
                "+proj=aeqd +a=6371008.771415 +b=6371008.771415 +lat_0=30.2345 +lon_0=-120.2345",
                "+a=6371008.771415 +b=6371008.771415", 6371008.771415, 0.0);
        s.expectRoundtrip(-120.234501, 30.234501, 1, 1 * MM);
        s.expectRoundtrip(-120.2345, 30.2345, 1, 1 * MM);

        GieCase e = new GieCase(
                "+proj=aeqd +a=6371008.771415 +b=6371008.771414 +lat_0=30.2345 +lon_0=-120.2345",
                "+a=6371008.771415 +b=6371008.771414", 6371008.771415,
                (6371008.771415 - 6371008.771414) / 6371008.771415);
        e.expectRoundtrip(-120.234501, 30.234501, 1, 1 * MM);
        e.expectRoundtrip(-120.2345, 30.2345, 1, 1 * MM);
    }

    /** International 1924 squared eccentricity, from {@code a} and {@code rf}. */
    private static double intlEs() {
        double f = 1.0 / INTL_RF;
        return 2.0 * f - f * f;
    }
}

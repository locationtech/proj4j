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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.locationtech.proj4j.util.ConformalLat;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * {@link ConformalLat} wired into {@code stere}, plus helper-level pins for the {@code merc} and
 * {@code lcc} rows the corpus sets.
 *
 * <h2>The documented divergence from 9.8.1</h2>
 *
 * {@code StereographicAzimuthalProjection.projectInverse} now calls {@link ConformalLat#phi2}
 * where upstream ({@code stere.cpp:173-188}) still runs its own {@code NITER = 8},
 * {@code pow}-per-trip Newton loop. The two solve the same fixed point, with {@code ts = 1/tp}
 * for the oblique and equatorial aspects and {@code ts = -tp} for the polar ones; the class
 * javadoc on {@code StereographicAzimuthalProjection} carries the algebra. The substitution
 * makes proj4j differ from 9.8.1 by up to about <b>4 um</b> on {@code stere} inverses, which is
 * 25,000 times inside the 0.1 mm bar the corpus sets for that projection. It is deliberate.
 *
 * <h2>What is not wired, and why</h2>
 *
 * {@code MercatorProjection}, {@code LambertConformalConicProjection},
 * {@code ObliqueMercatorProjection}, {@code EqualAreaAzimuthalProjection},
 * {@code NewZealandMapGridProjection}, {@code BonneProjection},
 * {@code EquidistantAzimuthalProjection} and {@code CassiniProjection} still call the deprecated
 * {@code ProjectionMath} helpers. They are outside this change's file scope. The helper-level
 * assertions below pin the values those call sites will produce once re-pointed, so the
 * hand-off is testable rather than described.
 */
public class ConformalLatitudeWiringTest {

    private static final double GRS80_A = 6378137.0;
    private static final double GRS80_ES = 0.006694380022900787;
    private static final double GRS80_E = Math.sqrt(GRS80_ES);

    // -- stere, in scope --------------------------------------------------------------------

    /** {@code builtins.gie:6700-6720}, {@code tolerance 0.1 mm}, equatorial ellipsoidal. */
    @Test
    public void stereographicEquatorialMatchesGie() {
        GieRow row = GieRow.grs80("+proj=stere +ellps=GRS80 +lat_0=0");
        row.expectForward(2, 1, 222644.854550117, 110610.883474174, 0.1 * GieRow.MM);
        row.expectForward(2, -1, 222644.854550117, -110610.883474174, 0.1 * GieRow.MM);
        row.expectInverse(200, 100, 0.001796631, 0.000904369, 0.1 * GieRow.MM);
        row.expectInverse(200, -100, 0.001796631, -0.000904369, 0.1 * GieRow.MM);
        row.expectInverse(-200, 100, -0.001796631, 0.000904369, 0.1 * GieRow.MM);
        row.expectRoundtrip(2, 1, 1, 0.1 * GieRow.MM);
    }

    /**
     * {@code builtins.gie:6723-6742}, the spherical block. The spherical inverse does not use
     * {@code phi2} at all, so this is a no-movement guard.
     */
    @Test
    public void stereographicSphericalMatchesGie() {
        GieRow row = GieRow.sphere("+proj=stere +R=6400000 +lat_0=0", 6400000.0);
        row.expectForward(2, 1, 223407.810259507, 111737.938996443, 0.1 * GieRow.MM);
        row.expectInverse(200, 100, 0.001790493, 0.000895247, 0.1 * GieRow.MM);
        row.expectRoundtrip(2, 1, 1, 0.1 * GieRow.MM);
    }

    /**
     * {@code builtins.gie:6746-6772}, Polar Stereographic Variants A and B. The
     * {@code accept 0 90; expect 0 0} row is at {@code tolerance 1e-15 m}, and it is why the
     * polar forward needs {@code 9.8.1:stere.cpp:83}'s {@code |phi - pi/2| < 1e-15} special
     * case: {@link ConformalLat#tsfn} correctly returns {@code cos(pi/2)/2 = 3.06e-17} there,
     * where the old {@code tan}-based {@code tsfn} returned an exact zero by accident.
     */
    @Test
    public void stereographicPolarMatchesGie() {
        GieRow north = GieRow.grs80("+proj=stere +ellps=GRS80 +lat_0=90 +lat_ts=70");
        north.expectForward(0, 90, 0, 0, 1e-15);
        north.expectForward(20, 70, 748315.3282, -2055979.4669, 0.1 * GieRow.MM);
        north.expectRoundtrip(20, 70, 1, 0.1 * GieRow.MM);
        north.expectRoundtrip(0, 90, 1, 0.1 * GieRow.MM);

        GieRow south = GieRow.grs80("+proj=stere +ellps=GRS80 +lat_0=-90 +lat_ts=-70");
        south.expectForward(0, -90, 0, 0, 1e-15);
        south.expectForward(20, -70, 748315.3282, 2055979.4669, 0.1 * GieRow.MM);
        south.expectRoundtrip(20, -70, 1, 0.1 * GieRow.MM);
    }

    /**
     * The oblique aspect, which takes the {@code ts = 1/tp} branch of the substitution. There is
     * no {@code +proj=stere} oblique block in {@code builtins.gie} — upstream's oblique coverage
     * is {@code sterea} — so the assertion is round-trip closure at the 0.1 mm bar.
     */
    @Test
    public void stereographicObliqueRoundTripCloses() {
        GieRow row = GieRow.grs80("+proj=stere +ellps=GRS80 +lat_0=52 +lon_0=21");
        row.expectRoundtrip(21, 52, 1, 0.1 * GieRow.MM);
        row.expectRoundtrip(25, 55, 1, 0.1 * GieRow.MM);
        row.expectRoundtrip(15, 45, 1, 0.1 * GieRow.MM);
        row.expectRoundtrip(21, 89, 1, 0.1 * GieRow.MM);
    }

    /**
     * The equatorial ellipsoidal aspect used to return {@code (0, 0)} for every input:
     * {@code 9.8.1:stere.cpp:262} handles {@code EQUIT} and {@code OBLIQ} in one branch and so
     * sets {@code cosX1 = 1}, while proj4j split them and left {@code cosphi0 = 0}, making
     * {@code tp = 2*atan2(rho*cosphi0, akm1)} identically zero.
     */
    @Test
    public void equatorialEllipsoidalInverseIsNoLongerDegenerate() {
        GieRow row = GieRow.grs80("+proj=stere +ellps=GRS80 +lat_0=0");
        assertNotEquals("the inverse used to collapse to latitude 0 for every input",
                0.0, row.inverse(200, 100).y, 1e-12);
        assertEquals(0.000904369, row.inverse(200, 100).y, 1e-9);
    }

    // -- merc and lcc: helper-level pins for the un-wired call sites ------------------------

    /**
     * {@code builtins.gie:4262-4265} asserts {@code merc} forward of (0, 0) at
     * <b>{@code tolerance 0 m}</b>. That row needs {@code tsfn(0) == 1.0} exactly, so that
     * {@code -k0*log(ts)} is exactly zero. The old helper returns
     * {@code 0.9999999999999999}.
     */
    @Test
    public void tsfnIsExactlyOneAtTheEquator() {
        assertEquals("ConformalLat.tsfn must be bit-exactly 1.0 at phi = 0",
                1.0, ConformalLat.tsfn(0.0, 0.0, GRS80_E), 0.0);
        assertEquals("...and so must the sin/cos form",
                1.0, ConformalLat.tsfnSinCos(0.0, 1.0, GRS80_E), 0.0);
        assertNotEquals("the deprecated helper is one ulp short, which is what fails a "
                        + "tolerance 0 m row",
                1.0, ProjectionMath.tsfn(0.0, 0.0, GRS80_E), 0.0);
        assertEquals(0.9999999999999999, ProjectionMath.tsfn(0.0, 0.0, GRS80_E), 0.0);
    }

    /**
     * {@code builtins.gie:4285-4288} asserts {@code merc +ellps=GRS80} inverse of (200, 100)
     * at <b>{@code tolerance 50 nm}</b>: {@code expect 0.00179663056824 0.00090436947704}.
     * {@code MercatorProjection.projectInverse} is {@code phi2(exp(-y/k0), e)} on the northing
     * normalised by the semi-major axis, so the latitude can be pinned without the projection.
     */
    @Test
    public void phi2ReproducesTheMercatorInverseRow() {
        double y = 100.0 / GRS80_A;
        double expected = 0.00090436947704;

        double now = Math.toDegrees(ConformalLat.phi2(Math.exp(-y), GRS80_E));
        double before = Math.toDegrees(ProjectionMath.phi2(Math.exp(-y), GRS80_E));

        double nowErr = Math.abs(now - expected) * Math.PI / 180.0 * GRS80_A;
        double beforeErr = Math.abs(before - expected) * Math.PI / 180.0 * GRS80_A;
        assertTrue("ConformalLat.phi2 must land inside the 50 nm bar; missed by "
                + nowErr + " m", nowErr < 50e-9);
        assertTrue("...and the deprecated helper must not, else there is nothing to fix; "
                + "missed by " + beforeErr + " m", beforeErr > 50e-9);
    }

    /**
     * The worst-case sweep behind the "4,145 nm at latitude 2.8 degrees" figure in the numerics
     * reference. Both stacks are fed the same, exactly computed {@code ts}, so this isolates the
     * inverse rather than measuring a self-consistent round trip.
     */
    @Test
    public void phi2BeatsTheDeprecatedHelperEverywhere() {
        double worstOld = 0.0;
        double worstNew = 0.0;
        for (int i = 0; i <= 8900; i++) {
            double phi = Math.toRadians(i / 100.0);
            double ts = ConformalLat.tsfn(phi, Math.sin(phi), GRS80_E);
            worstOld = Math.max(worstOld,
                    Math.abs(ProjectionMath.phi2(ts, GRS80_E) - phi) * GRS80_A);
            worstNew = Math.max(worstNew,
                    Math.abs(ConformalLat.phi2(ts, GRS80_E) - phi) * GRS80_A);
        }
        assertTrue("the deprecated helper should exceed the 50 nm bar by a wide margin, "
                + "measured " + worstOld + " m", worstOld > 1.0e-6);
        assertTrue("the Karney formulation must be inside 50 nm, measured " + worstNew + " m",
                worstNew < 50e-9);
    }

    /**
     * {@code builtins.gie:3763-3766} asserts {@code lcc +ellps=GRS80 +lat_1=0.5 +lat_2=2}
     * inverse of (200, 100) at {@code tolerance 0.1 mm} — <b>not</b> 50 nm.
     * {@code LambertConformalConicProjection.projectInverse} is
     * {@code phi2(pow(rho/c, 1/n), e)}, so the same helper-level pin applies; the equivalent
     * {@code ts} for that row is the value below.
     */
    @Test
    public void phi2ReproducesTheLambertConformalConicRow() {
        // Latitude 0.000904232 deg, i.e. the lcc row's expected result.
        double expected = 0.000904232;
        double phi = Math.toRadians(expected);
        double ts = ConformalLat.tsfn(phi, Math.sin(phi), GRS80_E);

        double now = Math.toDegrees(ConformalLat.phi2(ts, GRS80_E));
        double err = Math.abs(now - expected) * Math.PI / 180.0 * GRS80_A;
        assertTrue("round-tripping tsfn/phi2 at the lcc row must close well inside 0.1 mm; "
                + "missed by " + err + " m", err < 1.0e-9);
    }
}

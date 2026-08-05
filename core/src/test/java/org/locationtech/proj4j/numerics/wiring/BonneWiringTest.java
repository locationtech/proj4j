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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.locationtech.proj4j.numerics.wiring.GieCase.GRS80_A;
import static org.locationtech.proj4j.numerics.wiring.GieCase.GRS80_ES;
import static org.locationtech.proj4j.numerics.wiring.GieCase.MM;
import static org.locationtech.proj4j.numerics.wiring.GieCase.NM;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.MeridianArc;

/**
 * {@code BonneProjection} re-pointed at {@link MeridianArc}, with the three defects that made the
 * corpus unreachable for it fixed: {@code +lat_1} was ignored, the ellipsoidal forward had no
 * small-{@code rh} guard, and the inverse read the northing it had already overwritten.
 */
public class BonneWiringTest {

    /** {@code builtins.gie:671}. */
    private static final GieCase HALF_DEGREE =
            GieCase.grs80("+proj=bonne +ellps=GRS80 +lat_1=0.5");

    /**
     * {@code builtins.gie:671-680}, {@code tolerance 0.1 mm}. The four sign combinations, and note
     * that {@code lat_1} makes the projection asymmetric in latitude — the two northings differ.
     *
     * <p><b>Before: 9,944 km out.</b> {@code phi1} was hard-wired to {@code pi/2} with
     * {@code +lat_1} commented out, so this operation silently produced the Werner aspect.
     */
    @Test
    public void halfDegreeStandardParallelForwardMatchesGie() {
        HALF_DEGREE.expectForward(2, 1, 222605.296097157, 55321.139565495, 0.1 * MM);
        HALF_DEGREE.expectForward(2, -1, 222605.296099239, -165827.647799052, 0.1 * MM);
        HALF_DEGREE.expectForward(-2, 1, -222605.296097157, 55321.139565495, 0.1 * MM);
        HALF_DEGREE.expectForward(-2, -1, -222605.296099239, -165827.647799052, 0.1 * MM);

        Legacy.Bonne old = new Legacy.Bonne(GRS80_A, GRS80_ES);
        double[] was = old.forward(2.0, 1.0);
        double before = Math.hypot(was[0] - 222605.296097157, was[1] - 55321.139565495);
        double now = HALF_DEGREE.forwardDeviation(2, 1, 222605.296097157, 55321.139565495);
        assertTrue("with lat_1 ignored this row was thousands of kilometres out, measured "
                + before + " m", before > 9.0e6);
        GieCase.assertStrictlyBetter("builtins.gie:674 bonne forward", before, now, 0.1 * MM);
    }

    /**
     * {@code builtins.gie:682-691}, {@code direction inverse}, {@code tolerance 0.1 mm}.
     *
     * <p>Two separate defects had to go before this could pass. The longitude was recovered as
     * {@code rh * atan2(x, y)} with the <em>original</em> northing {@code y}, while {@code rh} had
     * been computed from the shifted {@code am1 - y}: at {@code lat_1 = 0.5} the two differ by seven
     * orders of magnitude ({@code am1} is 114.6, {@code y/a} is 1.6e-5) and {@code atan2} returned
     * 1.1 radians where the answer is 2.7e-7. Upstream avoids it by mutating {@code xy.y} in place
     * ({@code bonne.cpp:80}); here the shifted value is a named local.
     */
    @Test
    public void halfDegreeStandardParallelInverseMatchesGie() {
        HALF_DEGREE.expectInverse(200, 100, 0.001796699, 0.500904369, 0.1 * MM);
        HALF_DEGREE.expectInverse(200, -100, 0.001796698, 0.499095631, 0.1 * MM);
        HALF_DEGREE.expectInverse(-200, 100, -0.001796699, 0.500904369, 0.1 * MM);
        HALF_DEGREE.expectInverse(-200, -100, -0.001796698, 0.499095631, 0.1 * MM);
    }

    /**
     * {@code builtins.gie:694-704}, the southern standard parallel with its two
     * {@code roundtrip 1} blocks. {@code bonne.cpp:88-97} negates both {@code atan2} arguments when
     * {@code phi1 < 0} and takes {@code copysign(hypot(...), phi1)}; proj4j did neither.
     */
    @Test
    public void southernStandardParallelMatchesGie() {
        GieCase r = GieCase.grs80("+proj=bonne +ellps=GRS80 +lat_1=-0.5");
        r.expectForward(2, 1, 222605.2961, 165827.6478, 0.1 * MM);
        r.expectRoundtrip(2, 1, 1, 0.1 * MM);
        r.expectForward(2, -1, 222605.2961, -55321.1396, 0.1 * MM);
        r.expectRoundtrip(2, -1, 1, 0.1 * MM);
    }

    /**
     * {@code builtins.gie:706-725}, the Werner aspects, {@code tolerance 0.1 mm}:
     * <pre>
     *   +lat_1=90   accept 0 90  -> expect 0 0 ; inverse accept 0 0 -> expect 0 90
     *   +lat_1=-90  accept 0 -90 -> expect 0 0 ; inverse accept 0 0 -> expect 0 -90
     * </pre>
     *
     * <p><b>The forward returned {@code (NaN, NaN)}.</b> At {@code lat_1 = 90}, {@code am1} is
     * {@code 6.14e-17} and {@code m1} is {@code 1.5666}, so {@code am1 + m1} rounds back to
     * {@code m1} — the addend is below half an ulp — and {@code rh} is <em>exactly</em> zero. The
     * next line divides by it. {@code bonne.cpp:29-35} wraps the body in
     * {@code if (fabs(rh) > EPS10)} and yields {@code (0, 0)}; proj4j had that guard in the
     * spherical branch only.
     */
    @Test
    public void wernerAspectsNoLongerReturnNaN() {
        GieCase north = GieCase.grs80("+proj=bonne +ellps=GRS80 +lat_1=90");
        ProjCoordinate got = north.forward(0, 90);
        assertEquals("builtins.gie:709 expects (0, 0), not NaN", 0.0, got.x, 0.0);
        assertEquals("builtins.gie:709 expects (0, 0), not NaN", 0.0, got.y, 0.0);
        north.expectInverse(0, 0, 0, 90, 0.1 * MM);

        GieCase south = GieCase.grs80("+proj=bonne +ellps=GRS80 +lat_1=-90");
        ProjCoordinate got2 = south.forward(0, -90);
        assertEquals(0.0, got2.x, 0.0);
        assertEquals(0.0, got2.y, 0.0);
        south.expectInverse(0, 0, 0, -90, 0.1 * MM);

        Legacy.Bonne old = new Legacy.Bonne(GRS80_A, GRS80_ES);
        double[] was = old.forward(0.0, 90.0);
        assertTrue("the pre-change forward produced NaN here, which is what the guard fixes",
                Double.isNaN(was[0]) && Double.isNaN(was[1]));
    }

    /** {@code builtins.gie:728-747} and {@code :763-780}, the spherical blocks. */
    @Test
    public void sphericalBlocksMatchGie() {
        GieCase r = GieCase.sphere("+proj=bonne +R=6400000 +lat_1=0.5", 6400000.0);
        r.expectForward(2, 1, 223368.115572528, 55884.555246394, 0.1 * MM);
        r.expectForward(2, -1, 223368.115574632, -167517.599369694, 0.1 * MM);
        r.expectForward(-2, 1, -223368.115572528, 55884.555246394, 0.1 * MM);
        r.expectForward(-2, -1, -223368.115574632, -167517.599369694, 0.1 * MM);
        r.expectInverse(200, 100, 0.001790562, 0.500895246, 0.1 * MM);
        r.expectInverse(200, -100, 0.001790561, 0.499104753, 0.1 * MM);
        r.expectInverse(-200, 100, -0.001790562, 0.500895246, 0.1 * MM);
        r.expectInverse(-200, -100, -0.001790561, 0.499104753, 0.1 * MM);

        GieCase s = GieCase.sphere("+proj=bonne +R=6400000 +lat_1=-0.5", 6400000.0);
        s.expectForward(2, 1, 223368.1156, 167517.5994, 0.1 * MM);
        s.expectRoundtrip(2, 1, 1, 0.1 * MM);
        s.expectForward(2, -1, 223368.1156, -55884.5552, 0.1 * MM);
        s.expectRoundtrip(2, -1, 1, 0.1 * MM);

        GieCase n90 = GieCase.sphere("+proj=bonne +R=6400000 +lat_1=90", 6400000.0);
        n90.expectForward(0, 90, 0, 0, 0.1 * MM);
        n90.expectInverse(0, 0, 0, 90, 0.1 * MM);

        GieCase s90 = GieCase.sphere("+proj=bonne +R=6400000 +lat_1=-90", 6400000.0);
        s90.expectForward(0, -90, 0, 0, 0.1 * MM);
        s90.expectInverse(0, 0, 0, -90, 0.1 * MM);
    }

    /**
     * {@code bonne.cpp:127-131}: {@code lat_1} is required and {@code |lat_1| < 1e-10} is an error.
     * Previously the parameter was ignored, so every {@code +proj=bonne} silently became
     * {@code +lat_1=90} — including one with no {@code lat_1} at all, which upstream rejects.
     *
     * <p>This is a <b>behaviour change for existing users</b>: {@code +proj=bonne} without
     * {@code +lat_1} used to return the Werner aspect and now throws.
     */
    @Test(expected = ProjectionException.class)
    public void missingLatOneIsRejectedAsUpstreamRejectsIt() {
        new CRSFactory().createFromParameters("bad", "+proj=bonne +ellps=GRS80 +no_defs");
    }

    /** {@code +lat_1=0} is the same rejection. */
    @Test(expected = ProjectionException.class)
    public void zeroLatOneIsRejected() {
        new CRSFactory().createFromParameters("bad",
                "+proj=bonne +ellps=GRS80 +lat_1=0 +no_defs");
    }

    /**
     * The meridian arc that {@code bonne}'s forward and inverse both rest on, measured against the
     * independent quadrature rather than against itself. At longitude 0 the northing is
     * {@code am1 - rh}, i.e. {@code M(phi) - m1}, so subtracting off the constant leaves the arc.
     *
     * <p><b>The floor here is bonne's own conditioning, not the series.</b> {@code rh} is formed as
     * {@code am1 + m1 - M} and at {@code lat_1 = 0.5} degrees {@code am1} is 114.56 in units of the
     * semi-major axis — a cotangent-like quantity that blows up as {@code lat_1} approaches zero.
     * One ulp of 114.56 scaled by {@code a} is 91 nm, so the cancellation in that sum sets a ~91 nm
     * floor on the northing however exact the arc is. Upstream forms {@code rh} the same way
     * ({@code bonne.cpp:27}), so this is inherited, not introduced. The assertion is therefore set
     * at 200 nm; the underlying series is measured properly in {@code CassiniWiringTest}, where the
     * northing has no such addend.
     */
    @Test
    public void meridianArcBeatsTheDeprecatedSeries() {
        // lat_1 = 0.5 makes m1 the arc to 0.5 degrees; take it from the projection at phi = lat_1,
        // where the northing is exactly zero by construction, and work relative to it.
        double worstBefore = 0.0;
        double worstNow = 0.0;
        Legacy.Bonne old = new Legacy.Bonne(GRS80_A, GRS80_ES);
        double referenceAtHalf = GRS80_A * GieCase.meridianArcReference(Math.toRadians(0.5), GRS80_ES);
        for (int i = 0; i <= 890; i++) {
            double lat = i / 10.0;
            double reference = GRS80_A * GieCase.meridianArcReference(Math.toRadians(lat), GRS80_ES)
                    - referenceAtHalf;
            double now = Math.abs(HALF_DEGREE.forward(0, lat).y - reference);
            // The legacy path's arc, relative to its own Werner origin, is the same series.
            double legacyArc = -(old.forward(0.0, lat)[1] - old.forward(0.0, 0.5)[1]);
            double before = Math.abs(legacyArc - reference);
            worstBefore = Math.max(worstBefore, before);
            worstNow = Math.max(worstNow, now);
        }
        assertTrue("the es-series should be micrometres out somewhere in 0-89 degrees, measured "
                + worstBefore + " m", worstBefore > 4.0e-6);
        assertTrue("the n-series must be at bonne's own 91 nm cancellation floor, measured "
                + worstNow + " m", worstNow < 200 * NM);
    }

    /** Round-trip closure across the usable range at the corpus's 0.1 mm bar. */
    @Test
    public void roundTripCloses() {
        for (double lat : new double[] {-60, -10, -1, 0.5, 1, 10, 45, 80}) {
            HALF_DEGREE.expectRoundtrip(2, lat, 100, 0.1 * MM);
            HALF_DEGREE.expectRoundtrip(-2, lat, 100, 0.1 * MM);
        }
    }
}

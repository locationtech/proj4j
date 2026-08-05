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
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.util.MeridianArc;

/**
 * {@code CassiniProjection} re-pointed at {@link MeridianArc}, with the {@code A^4} easting sign
 * corrected and the inverse refined by {@code pj_generic_inverse_2d}.
 */
public class CassiniWiringTest {

    private static final GieCase GRS80 = GieCase.grs80("+proj=cass +ellps=GRS80");

    /** {@code gigs/5108.gie}: GDM2000 / Johor Grid, EPSG:3377. */
    private static final String JOHOR =
            "+proj=cass +lat_0=2.121679744444445 +lon_0=103.4279362361111 "
            + "+x_0=-14810.562 +y_0=8758.32 +ellps=GRS80 +units=m";

    /** The sixteen distinct {@code accept}/{@code expect} pairs of {@code gigs/5108.gie}. */
    private static final double[][] GIGS_5108 = {
        {106, 10, 267186.017, 881108.902},
        {106, 9, 268006.024, 770398.186},
        {106, 8, 268740.351, 659692.254},
        {106, 7, 269388.786, 548990.588},
        {106, 6, 269951.141, 438292.666},
        {106, 5, 270427.255, 327597.962},
        {106, 4, 270816.99, 216905.945},
        {106, 3, 271120.234, 106216.081},
        {103.561065778, 2.0424676812, 0, 0},
        {103.64025984, 1.82776484381, 8813.252, -23740.095},
        {106, 1, 271466.923, -115159.332},
        {109, 5, 603116.703, 329668.599},
        {108, 5, 492221.308, 328807.336},
        {107, 5, 381324.74, 328117.472},
        {105, 5, 159529.111, 327248.012},
        {104, 5, 48630.563, 327067.097},
    };

    /**
     * {@code builtins.gie:857-877}, {@code tolerance 0.1 mm}, including the {@code roundtrip 1} at
     * {@code :861}.
     *
     * <p>Note the forward deviation here <em>grows</em>, from 0.42 nm to 13.5 um, and that is
     * correct: {@code builtins.gie:859}'s {@code 222605.285776991} is a stale expectation generated
     * before upstream's {@code 78d89828} fixed the easting sign, and it matches the old sign to the
     * last printed digit. The block's bar is 0.1 mm, so both signs pass; see
     * {@link #epsgGuidanceNoteTestPointDecidesTheEastingSign()} for the row that actually decides.
     */
    @Test
    public void grs80BlockMatchesGie() {
        GRS80.expectForward(2, 1, 222605.285776991, 110642.229253999, 0.1 * MM);
        GRS80.expectForward(2, -1, 222605.285776991, -110642.229253999, 0.1 * MM);
        GRS80.expectForward(-2, 1, -222605.285776991, 110642.229253999, 0.1 * MM);
        GRS80.expectForward(-2, -1, -222605.285776991, -110642.229253999, 0.1 * MM);
        GRS80.expectRoundtrip(2, 1, 1, 0.1 * MM);

        GRS80.expectInverse(200, 100, 0.001796631, 0.000904369, 0.1 * MM);
        GRS80.expectInverse(200, -100, 0.001796631, -0.000904369, 0.1 * MM);
        GRS80.expectInverse(-200, 100, -0.001796631, 0.000904369, 0.1 * MM);
        GRS80.expectInverse(-200, -100, -0.001796631, -0.000904369, 0.1 * MM);
    }

    /** {@code builtins.gie:881-901}, the spherical block, which uses no series at all. */
    @Test
    public void sphericalBlockMatchesGie() {
        GieCase r = GieCase.sphere("+proj=cass +R=6400000", 6400000.0);
        r.expectForward(2, 1, 223368.105203484, 111769.145040586, 0.1 * MM);
        r.expectForward(-2, -1, -223368.105203484, -111769.145040586, 0.1 * MM);
        r.expectInverse(200, 100, 0.001790493, 0.000895247, 0.1 * MM);
        r.expectInverse(-200, -100, -0.001790493, -0.000895247, 0.1 * MM);
    }

    /**
     * {@code builtins.gie:905-913}, the EPSG Guidance Note 7-2 test point, {@code tolerance 0.1 mm}
     * with {@code roundtrip 1}:
     * <pre>
     *   operation +proj=cass +lat_0=10.4416666666667 +lon_0=-61.3333333333333 \
     *             +x_0=86501.46392052 +y_0=65379.0134283 \
     *             +a=6378293.64520876 +b=6356617.98767984 +to_meter=0.201166195164
     *   accept  -62                10
     *   expect  66644.94040882     82536.21873655
     * </pre>
     *
     * <p><b>This is the row that decides the sign of the {@code A^4} easting term</b>, because its
     * expectation is an external EPSG value printed to eight decimals rather than a PROJ regeneration.
     * With {@code C1 + (...)} — upstream's, Snyder's Eq. 13-1's and EPSG's — the easting reproduces
     * {@code 66644.94040882} to 3.5e-9 links. With proj4j's {@code C1 - (...)} it was
     * {@code 66644.94038278}, i.e. 2.6e-5 links out. Both are inside 0.1 mm, which is exactly why
     * the corpus alone never flagged it; the round trips did.
     */
    @Test
    public void epsgGuidanceNoteTestPointDecidesTheEastingSign() {
        GieCase r = new GieCase(
                "+proj=cass +lat_0=10.4416666666667 +lon_0=-61.3333333333333 "
                + "+x_0=86501.46392052 +y_0=65379.0134283 "
                + "+a=6378293.64520876 +b=6356617.98767984 +to_meter=0.201166195164",
                "+a=6378293.64520876 +b=6356617.98767984", 6378293.64520876,
                (6378293.64520876 - 6356617.98767984) / 6378293.64520876);
        ProjCoordinate got = r.forward(-62, 10);
        assertEquals("the corrected sign must reproduce EPSG's own eight decimals",
                66644.94040882, got.x, 1e-7);
        assertEquals(82536.21873655, got.y, 1e-7);
        r.expectRoundtrip(-62, 10, 1, 0.1 * MM);

        // What the old sign gave. Reproduced through Legacy so the comparison is a measurement.
        double aa = 6378293.64520876;
        double bb = 6356617.98767984;
        double ff = (aa - bb) / aa;
        Legacy.Cass old = new Legacy.Cass(aa, 2.0 * ff - ff * ff, 10.4416666666667);
        double[] oldXy = old.forward(-62.0 - (-61.3333333333333), 10.0);
        double oldEastingLinks = (oldXy[0] + 86501.46392052) / 0.201166195164;
        assertEquals("the pre-change easting was 66644.94038278 links", 66644.94038278,
                oldEastingLinks, 1e-7);
        assertTrue("...which is farther from EPSG's value than the corrected sign",
                Math.abs(oldEastingLinks - 66644.94040882) > Math.abs(got.x - 66644.94040882));
    }

    /**
     * {@code builtins.gie:917-927}, the {@code +hyperbolic} block. proj4j does not implement
     * {@code +hyperbolic} (there is no such keyword), and the parameter is ignored, so the ordinary
     * variant is produced instead. Asserted as the known gap it is, at the size upstream's
     * hyperbolic correction term has, rather than left silent.
     */
    @Test
    public void hyperbolicVariantIsNotImplementedAndIsOffByTheCorrectionTerm() {
        GieCase r = new GieCase(
                "+proj=cass +a=6378306.376305601 +rf=293.466307 +lat_0=-16.25 "
                + "+lon_0=179.33333333333333 +to_meter=20.1168 "
                + "+x_0=251727.9155424 +y_0=334519.953768",
                "+a=6378306.376305601 +rf=293.466307", 6378306.376305601, 1.0 / 293.466307);
        ProjCoordinate got = r.forward(179.99433652777776, -16.841456527777776);
        // builtins.gie:922 expects 16015.28901692 13369.66005367 for the hyperbolic variant.
        double dev = Math.hypot(got.x - 16015.28901692, got.y - 13369.66005367);
        assertTrue("the ordinary variant should differ from the hyperbolic one by the "
                        + "y^3/(6*rho*nu) term, measured " + dev + " (to_meter units)", dev > 1e-6);
        // ...but the ordinary variant must still be self-consistent.
        r.expectRoundtrip(179.99433652777776, -16.841456527777776, 1, 0.1 * MM);
    }

    /**
     * {@code builtins.gie:931-941}, the scenario from PROJ issue 4385, {@code direction inverse}
     * at {@code tolerance 0.1 mm}:
     * <pre>
     *   accept 300000 100000   expect -4.022094267169   50.583438725252
     *   accept 500000 100000   expect -1.19725 50.6177
     * </pre>
     *
     * <p>The first row is 2.8 degrees from the central meridian and is the clearest single
     * measurement of the sign defect: the mis-signed {@code A^4} term put the <em>forward</em> of
     * the expected longitude 31 mm out in easting, so the inverse of the accepted easting came back
     * 4.4e-7 degrees — <b>31 mm</b> — wrong in longitude, 310 times the bar.
     */
    @Test
    public void issue4385InverseMatchesGie() {
        GieCase r = new GieCase(
                "+proj=cass +lat_0=50.6177 +lon_0=-1.19725 +x_0=500000 +y_0=100000 "
                + "+ellps=airy +units=m",
                "+ellps=airy", 6377563.396, 1.0 / 299.3249646);
        r.expectInverse(300000, 100000, -4.022094267169, 50.583438725252, 0.1 * MM);
        r.expectInverse(500000, 100000, -1.19725, 50.6177, 0.1 * MM);

        double now = r.inverseDeviation(300000, 100000, -4.022094267169, 50.583438725252);
        Legacy.Cass old = new Legacy.Cass(6377563.396, airyEs(), 50.6177);
        double[] lp = old.inverse(300000 - 500000, 100000 - 100000);
        double before = r.angularDeviation(-4.022094267169, 50.583438725252,
                new ProjCoordinate(lp[0] + -1.19725, lp[1]));
        assertTrue("the pre-change inverse must miss the 0.1 mm bar here, measured " + before
                + " m", before > 0.1 * MM);
        GieCase.assertStrictlyBetter("builtins.gie:936 cass inverse", before, now, 0.1 * MM);
    }

    /**
     * All sixteen {@code gigs/5108.gie} point checks, both directions, at that file's
     * {@code tolerance 0.05 m}. These passed before the change too — the defect was invisible to
     * them, which is the point of the next test.
     */
    @Test
    public void gigs5108PointChecksPassInBothDirections() {
        GieCase r = GieCase.grs80(JOHOR);
        for (double[] row : GIGS_5108) {
            r.expectForward(row[0], row[1], row[2], row[3], 0.05);
            r.expectInverse(row[2], row[3], row[0], row[1], 0.05);
        }
    }

    /**
     * The seventeen {@code roundtrip 1000} blocks of {@code gigs/5108.gie} at
     * {@code tolerance 0.006 m} (sixteen distinct points; one appears twice).
     *
     * <p><b>Before this change 3 of 16 passed; now 16 of 16 do.</b> Per row, in the order of the
     * table above, the old residuals were 4.485 / 3.684 / 2.952 / 2.294 / 1.714 / 1.216 / 0.803 /
     * 0.479 m, then 7.6e-8 and 6.1e-7 m for the two points nearly on the central meridian, then
     * 0.090 / <b>67.435</b> / 23.688 / 6.552 / 0.101 / 6.4e-4 m. The worst is at longitude 109 —
     * 5.6 degrees off the central meridian — and the ordering is by exactly that distance, which is
     * what identified a truncation term rather than a bad meridian arc. Now the worst is
     * 6.0e-5 m.
     *
     * <p>Two things were needed and both are asserted, in
     * {@link #epsgGuidanceNoteTestPointDecidesTheEastingSign()} and here: the sign, which removes
     * most of the mismatch, and {@code pj_generic_inverse_2d}, without which — with the sign already
     * corrected — these same rows still pass only 3 of 16 with a worst residual of 37.9 m, because
     * the inverse series is itself truncated at {@code D^4}.
     */
    @Test
    public void gigs5108RoundTripsCloseAtSixMillimetres() {
        GieCase r = GieCase.grs80(JOHOR);
        Legacy.Cass old = new Legacy.Cass(GRS80_A, GRS80_ES, 2.121679744444445);
        double cm = 103.4279362361111;

        double worstNow = 0.0;
        double worstBefore = 0.0;
        int passedBefore = 0;
        for (double[] row : GIGS_5108) {
            double now = r.roundtripDeviation(row[0], row[1], 1000);
            worstNow = Math.max(worstNow, now);
            r.expectRoundtrip(row[0], row[1], 1000, 0.006);

            double lon = row[0] - cm;
            double lat = row[1];
            for (int i = 0; i < 1000; i++) {
                double[] xy = old.forward(lon, lat);
                double[] lp = old.inverse(xy[0], xy[1]);
                lon = lp[0];
                lat = lp[1];
            }
            double before = r.angularDeviation(row[0], row[1],
                    new ProjCoordinate(cm + lon, lat));
            worstBefore = Math.max(worstBefore, before);
            if (before <= 0.006) {
                passedBefore++;
            }
        }
        assertEquals("only the three rows nearest the central meridian used to pass", 3,
                passedBefore);
        assertTrue("the worst pre-change residual was 67.4 m, measured " + worstBefore,
                worstBefore > 60.0);
        assertTrue("1000 cycles across the whole grid must now stay under a millimetre, measured "
                + worstNow + " m", worstNow < 1.0 * MM);
    }

    /**
     * The truncation signature, measured directly: one forward/inverse cycle at increasing distance
     * from the central meridian, on {@code gigs/5108}'s own operation at latitude 5.
     *
     * <p>The mis-signed term is {@code (8 - T + 8C) T A^4 / 120} inside a bracket multiplied by
     * {@code A}, so the <em>absolute</em> easting error grows like {@code A^5} — the measured
     * sweep is 2.6e-8 m at 0.3 degrees, 8.1e-7 at 0.6, 1.0e-5 at 1, 3.4e-4 at 2, 2.7e-3 at 3,
     * 3.8e-2 at 5 and 6.9e-2 at 5.6, which is a factor of 2.65e6 over a factor of 18.7 in longitude
     * ({@code 18.7^5 = 2.3e6}). After the change every one of those is under 10 um, bounded by the
     * {@code 1e-12}-in-units-of-{@code a} residual upstream's refinement stops at.
     */
    @Test
    public void singleCycleResidualNoLongerGrowsWithAFifthPowerOfLongitude() {
        GieCase r = GieCase.grs80(JOHOR);
        double cm = 103.4279362361111;
        Legacy.Cass old = new Legacy.Cass(GRS80_A, GRS80_ES, 2.121679744444445);

        double previousBefore = 0.0;
        for (double offset : new double[] {0.3, 0.6, 1.0, 2.0, 3.0, 5.0, 5.6}) {
            double[] xy = old.forward(offset, 5.0);
            double[] lp = old.inverse(xy[0], xy[1]);
            double before = r.angularDeviation(cm + offset, 5.0,
                    new ProjCoordinate(cm + lp[0], lp[1]));
            double now = r.roundtripDeviation(cm + offset, 5.0, 1);

            assertTrue("the old residual must grow monotonically with the longitude offset; at "
                            + offset + " degrees it is " + before + " m, previously "
                            + previousBefore + " m", before > previousBefore);
            assertTrue("at " + offset + " degrees the residual must be inside 10 um, measured "
                    + now + " m", now < 1.0e-5);
            previousBefore = before;
        }
        // The two ends of the sweep, as the numbers the report quotes.
        assertTrue("at 0.6 degrees the pre-change cycle drifted about 0.8 um",
                legacyCycle(old, r, cm, 0.6) > 7.0e-7 && legacyCycle(old, r, cm, 0.6) < 9.0e-7);
        assertTrue("at 5.6 degrees it drifted about 69 mm",
                legacyCycle(old, r, cm, 5.6) > 0.06 && legacyCycle(old, r, cm, 5.6) < 0.08);
    }

    private static double legacyCycle(Legacy.Cass old, GieCase r, double cm, double offset) {
        double[] xy = old.forward(offset, 5.0);
        double[] lp = old.inverse(xy[0], xy[1]);
        return r.angularDeviation(cm + offset, 5.0, new ProjCoordinate(cm + lp[0], lp[1]));
    }

    /**
     * The meridian arc in isolation. At longitude 0 the Cassini northing <em>is</em> the meridian
     * arc from {@code lat_0} to {@code phi}, so with {@code lat_0 = 0} the forward can be compared
     * against an independent quadrature. Old: up to 4.92 um at latitude 72.55 degrees, which is the
     * peak the numerics reference records for {@code ProjectionMath.mlfn}. New: bit-exact at every
     * sampled latitude but one, where it is 1.9 nm — inside the reference's own accuracy.
     */
    @Test
    public void meridianArcBeatsTheDeprecatedSeriesAgainstAnIndependentReference() {
        double worstBefore = 0.0;
        double worstNow = 0.0;
        double worstBeforeAt = Double.NaN;
        Legacy.Cass old = new Legacy.Cass(GRS80_A, GRS80_ES, 0.0);
        for (int i = 0; i <= 900; i++) {
            double lat = i / 10.0;
            double reference = GRS80_A * GieCase.meridianArcReference(Math.toRadians(lat), GRS80_ES);
            double before = Math.abs(old.forward(0.0, lat)[1] - reference);
            double now = Math.abs(GRS80.forward(0, lat).y - reference);
            if (before > worstBefore) {
                worstBefore = before;
                worstBeforeAt = lat;
            }
            worstNow = Math.max(worstNow, now);
        }
        assertEquals("the es-series error peaks near latitude 72.5 degrees", 72.5, worstBeforeAt, 0.6);
        assertTrue("the es-series should be several micrometres out at the peak, measured "
                + worstBefore + " m", worstBefore > 4.0e-6 && worstBefore < 6.0e-6);
        assertTrue("the n-series must be inside the quadrature's own ~1 nm accuracy, measured "
                + worstNow + " m", worstNow < 10 * NM);
    }

    /**
     * The inverse against the same independent reference: feed the exact meridian arc as a northing
     * and demand the latitude back. Old: 4.91 um at 72.55 degrees. New: 3.2 nm.
     *
     * <p>This is the measurement the numerics reference insists on. A round trip would have said
     * the old code was fine — {@code inv_mlfn} is Newton against {@code mlfn}, so the pair closes to
     * 0.7 nm while both halves sit 4,920 nm from the truth.
     */
    @Test
    public void inverseMeridianArcBeatsTheDeprecatedNewtonLoop() {
        double worstBefore = 0.0;
        double worstNow = 0.0;
        Legacy.Cass old = new Legacy.Cass(GRS80_A, GRS80_ES, 0.0);
        for (int i = 1; i <= 890; i++) {
            double lat = i / 10.0;
            double y = GRS80_A * GieCase.meridianArcReference(Math.toRadians(lat), GRS80_ES);
            double before = Math.abs(old.inverse(0.0, y)[1] - lat) * Math.PI / 180.0 * GRS80_A;
            double now = Math.abs(GRS80.inverse(0, y).y - lat) * Math.PI / 180.0 * GRS80_A;
            worstBefore = Math.max(worstBefore, before);
            worstNow = Math.max(worstNow, now);
        }
        assertTrue("the deprecated Newton loop should be micrometres out, measured "
                + worstBefore + " m", worstBefore > 4.0e-6);
        assertTrue("the closed form must be nanometres, measured " + worstNow + " m",
                worstNow < 20 * NM);
    }

    /**
     * The thread-safety property {@code SharedTransformConcurrencyTest} asserts, restated locally:
     * nothing this class writes during a transform lives in a field, so repeated transforms through
     * one shared object are bit-identical.
     *
     * <p>The refinement added to the inverse is the thing that could have broken it —
     * {@code GenericInverse2D.solve} allocates its two scratch coordinates inside the call, and the
     * {@code Forward2D} it is handed is a method reference with no captured state beyond
     * {@code this}, whose only reads are {@code initialize()}-computed configuration.
     */
    @Test
    public void repeatedInverseThroughOneObjectIsBitIdentical() {
        GieCase r = GieCase.grs80(JOHOR);
        ProjCoordinate first = r.inverse(603116.703, 329668.599);
        for (int i = 0; i < 200; i++) {
            ProjCoordinate again = r.inverse(603116.703, 329668.599);
            assertEquals(Double.doubleToRawLongBits(first.x), Double.doubleToRawLongBits(again.x));
            assertEquals(Double.doubleToRawLongBits(first.y), Double.doubleToRawLongBits(again.y));
        }
    }

    /** Airy 1830 squared eccentricity, from proj4j's {@code a} and {@code rf}. */
    private static double airyEs() {
        double f = 1.0 / 299.3249646;
        return 2.0 * f - f * f;
    }
}

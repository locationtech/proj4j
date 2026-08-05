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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.datum.Ellipsoid;

/**
 * Ellipsoid resolution against PROJ 9.8.1.
 * <p>
 * Expectations come from {@code 9.8.1:test/gie/ellipsoid.gie} (vendored at
 * {@code conformance/src/test/resources/gie/ellipsoid.gie}), which is exactly
 * the test file for {@code src/ell_set.cpp}.
 */
public class EllipsoidParsingTest {

    /** gie's own tolerance for the coordinate rows in ellipsoid.gie is 10 nm. */
    private static final double NM_10 = 1e-8;

    /** A looser bound for the rows gie itself only pins to 4 decimal places. */
    private static final double MM_1 = 1e-3;

    private final CRSFactory crsFactory = new CRSFactory();

    private CoordinateReferenceSystem crs(String def) {
        return crsFactory.createFromParameters("test", def);
    }

    private double es(String def) {
        return crs(def).getDatum().getEllipsoid().getEccentricitySquared();
    }

    private double a(String def) {
        return crs(def).getDatum().getEllipsoid().getEquatorRadius();
    }

    /** Forward-projects lon/lat in degrees using only the parsed projection. */
    private ProjCoordinate project(String def, double lon, double lat) {
        ProjCoordinate out = new ProjCoordinate(Double.NaN, Double.NaN);
        crs(def).getProjection().project(new ProjCoordinate(lon, lat), out);
        return out;
    }

    private void expect(String def, double lon, double lat, double x, double y, double tol) {
        ProjCoordinate out = project(def, lon, lat);
        assertEquals(def + " easting", x, out.x, tol);
        assertEquals(def + " northing", y, out.y, tol);
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
    // +rf / +f are no longer transposed
    // ------------------------------------------------------------------

    /**
     * {@code ellps_shape} case 0: {@code f = 1/rf}, {@code es = 2f - f*f}.
     * PROJ4J used to apply the flattening formula to the <i>inverse</i>
     * flattening, giving {@code es = 297*(2-297) = -87615}.
     */
    @Test
    public void inverseFlatteningDerivesEccentricityLikeProj() {
        double f = 1.0 / 297.0;
        assertEquals("es must be 2f - f*f with f = 1/rf",
                2 * f - f * f, es("+proj=merc +a=6400000 +rf=297"), 0.0);
        assertEquals(0.006722670022333322, es("+proj=merc +a=6400000 +rf=297"), 1e-18);
    }

    /** {@code +f=1/297} and {@code +rf=297} must produce a bit-identical es. */
    @Test
    public void flatteningAndInverseFlatteningAgree() {
        double fromRf = es("+proj=merc +a=6400000 +rf=297");
        double fromF = es("+proj=merc +a=6400000 +f=" + (1.0 / 297.0));
        assertEquals("+f=1/297 and +rf=297 must be the same ellipsoid", fromRf, fromF, 0.0);
    }

    /** WGS84's inverse flattening must now round-trip through {@code +rf}. */
    @Test
    public void wgs84InverseFlatteningRoundTrips() {
        assertEquals(Ellipsoid.WGS84.getEccentricitySquared(),
                es("+proj=merc +a=6378137.0 +rf=298.257223563"), 1e-17);
    }

    /**
     * ellipsoid.gie: {@code operation proj=merc a=6400000 rf=297}. Both rows.
     * Before the fix the easting was exact and the northing was NaN, because
     * {@code e = sqrt(-87615)}.
     */
    @Test
    public void gieExplicitlyDefinedEllipsoid() {
        String def = "+proj=merc +a=6400000 +rf=297";
        expect(def, 1, 2, 111701.0721276371, 221945.9681832088, NM_10);
        expect(def, 12, 55, 1340412.8655316452, 7351803.9151705895, NM_10);
    }

    /** ellipsoid.gie: {@code operation proj=merc ellps=GRS80}. */
    @Test
    public void gieBuiltInEllipsoid() {
        String def = "+proj=merc +ellps=GRS80";
        expect(def, 1, 2, 111319.4907932736, 221194.0771604237, NM_10);
        expect(def, 12, 55, 1335833.8895192828, 7326837.7148738774, NM_10);
    }

    /** ellipsoid.gie: "Test that flattening can be set to zero". */
    @Test
    public void gieZeroFlattening() {
        expect("+proj=merc +a=1.0 +f=0.0", 12, 56, 0.20944, 1.18505, 1e-5);
    }

    // ------------------------------------------------------------------
    // +R declares a sphere and short-circuits everything
    // ------------------------------------------------------------------

    /**
     * ellipsoid.gie's first block. {@code +R} must set {@code es = 0} so the
     * spherical formula runs; the old code assigned only the semi-major axis,
     * leaving the ellipsoidal formula on a declared sphere - 1,495 m of error
     * at 2 degrees and ~35 km at 55.
     */
    @Test
    public void gieSphericalExample() {
        String def = "+proj=merc +R=6400000";
        assertEquals("+R must zero the eccentricity", 0.0, es(def), 0.0);
        assertEquals(6400000.0, a(def), 0.0);
        expect(def, 1, 2, 111701.0721276371, 223447.5262032605, NM_10);
        expect(def, 12, 55, 1340412.8655316452, 7387101.1430967357, NM_10);
    }

    /** {@code ell_set.cpp:92-100}: with {@code +R}, shape and ellps are ignored. */
    @Test
    public void radiusOverridesEllpsAndShapeParameters() {
        String def = "+proj=merc +ellps=GRS80 +rf=100 +R=6400000 +R_A";
        assertEquals(6400000.0, a(def), 0.0);
        assertEquals(0.0, es(def), 0.0);
        // identical to +R on its own
        expect(def, 12, 55, 1340412.8655316452, 7387101.1430967357, NM_10);
    }

    /** The projection must see the sphere even when a datum names an ellipsoid. */
    @Test
    public void radiusReachesTheProjectionEvenWithADatum() {
        CoordinateReferenceSystem c = crs("+proj=merc +datum=WGS84 +R=6400000");
        assertEquals(6400000.0, c.getProjection().getEquatorRadius(), 0.0);
        assertEquals(0.0, c.getProjection().getEllipsoid().getEccentricitySquared(), 0.0);
    }

    // ------------------------------------------------------------------
    // First-match-wins shape precedence
    // ------------------------------------------------------------------

    /**
     * {@code ellps_shape} loops over {@code rf, f, es, e, b} and breaks on the
     * first present. PROJ4J used to apply every one of them, last write
     * winning.
     */
    @Test
    public void firstShapeParameterWinsRegardlessOfTextOrder() {
        double rf297 = es("+proj=merc +a=6400000 +rf=297");
        assertEquals("rf outranks es", rf297, es("+proj=merc +a=6400000 +es=0.5 +rf=297"), 0.0);
        assertEquals("rf outranks es even when written last",
                rf297, es("+proj=merc +a=6400000 +rf=297 +es=0.5"), 0.0);
        assertEquals("rf outranks b", rf297, es("+proj=merc +a=6400000 +b=6000000 +rf=297"), 0.0);

        // f outranks es, e and b
        double f = 0.003;
        assertEquals(2 * f - f * f,
                es("+proj=merc +a=6378137 +b=6000000 +e=0.1 +es=0.5 +f=0.003"), 0.0);

        // es outranks e and b
        assertEquals(0.25, es("+proj=merc +a=6378137 +b=6000000 +e=0.1 +es=0.25"), 0.0);

        // e outranks b
        assertEquals(0.01, es("+proj=merc +a=6378137 +b=6000000 +e=0.1"), 1e-17);
    }

    /** {@code +e} was not a PROJ4J keyword at all. */
    @Test
    public void eccentricityIsAccepted() {
        assertEquals(0.25, es("+proj=merc +a=6378137 +e=0.5"), 0.0);
    }

    /**
     * A shape parameter after {@code +ellps} is a documented <i>modifier</i>,
     * not a contradiction - {@code ell_set.cpp}'s own comment says so - and
     * ellipsoid.gie asserts a value for it.
     */
    @Test
    public void ellpsPlusShapeParameterIsALegalModifier() {
        assertEquals(1.0 / 300.0 * 2 - (1.0 / 300.0) * (1.0 / 300.0),
                es("+proj=merc +ellps=GRS80 +rf=300"), 0.0);
        assertEquals("size must still come from GRS80",
                6378137.0, a("+proj=merc +ellps=GRS80 +rf=300"), 0.0);
        // gie: operation proj=utm zone=32 ellps=GRS80 rf=300
        expect("+proj=utm +zone=32 +ellps=GRS80 +rf=300", 12, 55, 691873.1212, 6099054.9661, MM_1);
        // gie: the same answer via +f
        expect("+proj=utm +zone=32 +ellps=GRS80 +f=0.00333333333333",
                12, 55, 691873.1212, 6099054.9661, MM_1);
    }

    /** gie: {@code +ellps=GRS80 +b=6000000} and {@code +a=6400000 +b=6000000}. */
    @Test
    public void gieSemiMinorAxisModifier() {
        expect("+proj=utm +zone=32 +ellps=GRS80 +b=6000000", 12, 55, 699293.0880, 5674591.5295, MM_1);
        expect("+proj=utm +zone=32 +a=6400000 +b=6000000", 12, 55, 700416.5900, 5669475.8884, MM_1);
    }

    /** {@code +b} is derived in two steps via f, not as {@code 1 - b*b/(a*a)}. */
    @Test
    public void semiMinorAxisUsesProjsTwoStepDerivation() {
        double a = 6378137.0;
        double b = 6356752.314245179;
        double f = (a - b) / a;
        assertEquals(2 * f - f * f, es("+proj=merc +a=" + a + " +b=" + b), 0.0);
    }

    // ------------------------------------------------------------------
    // Validation (ellps_size / ellps_shape / pj_calc_ellipsoid_params)
    // ------------------------------------------------------------------

    /** Every rejection asserted by ellipsoid.gie's "fail deliberately" block. */
    @Test
    public void gieDeliberateFailures() {
        rejected("+proj=merc +a=-1");
        rejected("+proj=merc +a=1 +es=-1");
        rejected("+proj=merc +R=0");
        rejected("+proj=merc +R_a +a=2 +f=2");
        rejected("+proj=merc +a=1E77 +R_lat_a=90 +b=1");
        rejected("+proj=merc +ellps=GRS80000000000");
    }

    /** ellipsoid.gie's "Shape parameters" block, all six rejections. */
    @Test
    public void gieShapeParameterFailures() {
        rejected("+proj=utm +zone=32 +ellps=GRS80 +rf=0");
        rejected("+proj=utm +zone=32 +ellps=GRS80 +e=-0.5");
        rejected("+proj=utm +zone=32 +ellps=GRS80 +e=1");
        rejected("+proj=utm +zone=32 +ellps=GRS80 +es=1");
        rejected("+proj=utm +zone=32 +a=1 +es=1.1");
        rejected("+proj=utm +zone=32 +ellps=GRS80 +b=0");
        rejected("+proj=utm +zone=32 +ellps=GRS80 +f=1");
    }

    @Test
    public void negativeAndZeroSizesAreRejected() {
        rejected("+proj=merc +R=-1");
        rejected("+proj=merc +a=0");
        rejected("+proj=merc +a=Infinity");
    }

    @Test
    public void negativeFlatteningIsRejected() {
        assertTrue(rejected("+proj=merc +a=6400000 +f=-0.1").getMessage().contains("f"));
        assertTrue(rejected("+proj=merc +a=6400000 +rf=-297").getMessage().contains("rf"));
    }

    /**
     * The NaN-safe {@code !(es >= 0)} guard. This is the whole {@code +rf}
     * corpus class: {@code +rf=298.257...} used to yield {@code es = -88367},
     * which reached the projection and produced {@code (correct_x, NaN)} or, via
     * the geocentric route, a plausible coordinate half a megametre out.
     */
    @Test
    public void impossibleEccentricityIsRejectedRatherThanPropagated() {
        // What PROJ4J's old +f meant: an inverse flattening in the +f slot
        assertEquals("Invalid eccentricity",
                rejected("+proj=merc +a=6378137.0 +f=298.257222101").getMessage());
        assertEquals("Invalid eccentricity",
                rejected("+proj=tmerc +a=6377397.155 +f=299.1528128").getMessage());
    }

    /** {@code b > a} gives a negative flattening, hence a negative es. */
    @Test
    public void prolateEllipsoidIsRejected() {
        rejected("+proj=merc +a=6000000 +b=6378137");
    }

    /**
     * {@code +b} with no major axis used to give {@code es = 1 - b*b/NaN} and
     * then silently fall all the way through to {@code Datum.WGS84}.
     */
    @Test
    public void semiMinorAxisWithoutMajorAxisIsRejected() {
        assertEquals("Major axis not given",
                rejected("+proj=merc +b=6000000").getMessage());
    }

    /** Every shape key suppresses PROJ's implicit ellps, so all of them need a size. */
    @Test
    public void shapeWithoutSizeIsRejected() {
        assertEquals("Major axis not given", rejected("+proj=merc +rf=297").getMessage());
        assertEquals("Major axis not given", rejected("+proj=merc +f=0.003").getMessage());
        assertEquals("Major axis not given", rejected("+proj=merc +es=0.006").getMessage());
        assertEquals("Major axis not given", rejected("+proj=merc +e=0.08").getMessage());
    }

    /** ...but {@code +datum=} supplies one, because PROJ appends the datum's ellps. */
    @Test
    public void datumSuppliesTheMajorAxisForAShapeModifier() {
        CoordinateReferenceSystem c = crs("+proj=tmerc +datum=potsdam +rf=299.1528128");
        assertNotNull(c);
    }

    /** Malformed numbers must be a Proj4jException, not a bare NumberFormatException. */
    @Test
    public void malformedNumbersRaiseProj4jExceptions() {
        rejected("+proj=merc +a=six-million");
        rejected("+proj=merc +a=6400000 +rf=three-hundred");
    }

    // ------------------------------------------------------------------
    // No shape parameter means a sphere
    // ------------------------------------------------------------------

    /**
     * {@code ellps_shape}: "Not giving a shape parameter means selecting a
     * sphere". {@code +a=} alone used to yield {@code Datum.WGS84}, because
     * {@code isDefinedExplicitly()} required both a and es.
     */
    @Test
    public void majorAxisAloneDescribesASphere() {
        assertEquals(0.0, es("+proj=merc +a=6400000"), 0.0);
        assertEquals(6400000.0, a("+proj=merc +a=6400000"), 0.0);
        expect("+proj=merc +a=6400000", 1, 2, 111701.0721276371, 223447.5262032605, NM_10);
    }

    /**
     * With nothing declared at all the default is <b>GRS80</b>, not WGS84.
     * {@code init.cpp:362} appends {@code pj_mkparam("ellps=GRS80")} when none of
     * {@code +ellps +datum +a +b +rf +f +e +es} is present.
     * <p>
     * The two ellipsoids share a semi-major axis and differ only in the inverse
     * flattening — 298.257222101 against 298.257223563 — so this went unnoticed
     * everywhere except at sub-micrometre tolerance, where it was the <i>sole</i>
     * reason {@code builtins.gie:7767} ({@code +proj=utm +zone=32}, tolerance 0.001 mm)
     * missed by 124 &micro;m of northing.
     * <p>
     * Reference values from the installed PROJ 9.8.1:
     * <pre>
     * $ echo "2 1" | proj -f '%.9f' +proj=merc
     * 222638.981586547  110579.965218250          # == +ellps=GRS80
     * $ echo "2 1" | proj -f '%.9f' +proj=merc +ellps=WGS84
     * 222638.981586547  110579.965221896          # differs in the 4th micrometre
     * </pre>
     */
    @Test
    public void noEllipsoidParametersDefaultsToGrs80NotWgs84() {
        assertEquals(Ellipsoid.GRS80.getEquatorRadius(), a("+proj=merc"), 0.0);
        assertEquals(Ellipsoid.GRS80.getEccentricitySquared(), es("+proj=merc"), 0.0);

        // Same axis, different flattening: only the northing can tell them apart.
        assertEquals(Ellipsoid.WGS84.getEquatorRadius(), Ellipsoid.GRS80.getEquatorRadius(), 0.0);
        assertFalse("GRS80 and WGS84 must not be conflated",
                Ellipsoid.WGS84.getEccentricitySquared()
                        == Ellipsoid.GRS80.getEccentricitySquared());

        expect("+proj=merc", 2, 1, 222638.981586547, 110579.965218250, 1e-8);
        expect("+proj=merc +ellps=WGS84", 2, 1, 222638.981586547, 110579.965221896, 1e-8);
    }

    /**
     * The whole point: {@code builtins.gie:7767}. UTM zone 32 at (12, 56) to PROJ's own
     * printed precision, with no ellipsoid declared.
     */
    @Test
    public void theImplicitEllipsoidIsPreciseEnoughForTheUtmCorpusRow() {
        expect("+proj=utm +zone=32", 12, 56, 687071.43910944, 6210141.32674801, 1e-6);
    }

    // ------------------------------------------------------------------
    // Spherification
    // ------------------------------------------------------------------

    /**
     * ellipsoid.gie's spherification block. {@code setR_A} used to scale the
     * semi-major axis but leave es, so the "sphere" kept GRS80's eccentricity.
     */
    @Test
    public void gieSpherification() {
        expect("+proj=merc +ellps=GRS80 +R_A", 12, 55, 1334340.6237297705, 7353636.6296552019, NM_10);
        expect("+proj=merc +ellps=GRS80 +R_V", 12, 55, 1334339.2852675652, 7353629.2533042720, NM_10);
        expect("+proj=merc +ellps=GRS80 +R_a", 12, 55, 1333594.4904527504, 7349524.6413825499, NM_10);
        expect("+proj=merc +ellps=GRS80 +R_g", 12, 55, 1333592.6102291327, 7349514.2793497816, NM_10);
        expect("+proj=merc +ellps=GRS80 +R_h", 12, 55, 1333590.7300081658, 7349503.9173316229, NM_10);
        expect("+proj=merc +ellps=GRS80 +R_lat_a=60", 12, 55, 1338073.7436268919, 7374210.0924803326, NM_10);
        expect("+proj=merc +ellps=GRS80 +R_lat_g=60", 12, 55, 1338073.2696101593, 7374207.4801437631, NM_10);
    }

    @Test
    public void spherificationZeroesTheShape() {
        for (String key : new String[]{"R_A", "R_V", "R_a", "R_g", "R_h", "R_lat_a=45", "R_lat_g=45", "R_C"}) {
            String def = "+proj=merc +ellps=GRS80 +" + key;
            assertEquals(def + " must be a sphere", 0.0, es(def), 0.0);
            assertFalse(def + " must still have a radius", Double.isNaN(a(def)));
        }
    }

    /** Only the first spherification key takes effect. */
    @Test
    public void firstSpherificationParameterWins() {
        assertEquals(a("+proj=merc +ellps=GRS80 +R_A"),
                a("+proj=merc +ellps=GRS80 +R_A +R_h"), 0.0);
        assertEquals(a("+proj=merc +ellps=GRS80 +R_V"),
                a("+proj=merc +ellps=GRS80 +R_V +R_h"), 0.0);
    }

    /** {@code R_lat_a}/{@code R_lat_g} require |lat| &lt;= 90. */
    @Test
    public void spherificationLatitudeIsRangeChecked() {
        rejected("+proj=merc +ellps=GRS80 +R_lat_a=91");
        rejected("+proj=merc +ellps=GRS80 +R_lat_g=-91");
    }

    /**
     * {@code +R_C} (PROJ 9.3.0+) is the conformal sphere radius <b>at latitude 0</b>, so it does
     * <em>not</em> depend on {@code +lat_0} and always reduces to {@code +R_lat_g=0}, i.e. to the
     * semi-minor axis.
     *
     * <h4>This assertion was INVERTED, and the inverted form is the surprising one</h4>
     *
     * <p>It used to assert that {@code +R_C} varies with {@code +lat_0} and equals
     * {@code +R_lat_g=lat_0}, which is what {@code ell_set.cpp}'s own comment says &mdash; "taken
     * at a latitude that is phi0 (note: at least for mercator...)". The comment describes an intent
     * the code does not implement. {@code pj_init} calls {@code pj_ellipsoid} at
     * {@code init.cpp:566} and does not assign
     * {@code PIN-&gt;phi0 = pj_param(ctx, start, "rlat_0").f} until {@code init.cpp:651}, 85 lines
     * later, on a {@code calloc}'d {@code PJ} &mdash; so {@code P-&gt;phi0} is 0 at spherification
     * time and {@code a *= sqrt(1 - es) / (1 - es sin^2(0))} is just {@code a * sqrt(1 - es)}, the
     * semi-minor axis.
     *
     * <p>The corpus was generated by the code, not by the comment.
     * {@code builtins.gie:4350} is {@code +proj=merc +R_C +ellps=WGS84 +lat_0=45} at {@code (2, 49)}
     * and expects {@code 221892.515234695253}, which is {@code 6356752.314245179 * 2} degrees
     * &mdash; WGS84's {@code b}, to every printed digit. Honouring {@code +lat_0=45} gives
     * {@code 222637.726003700}, wrong by 745 m. So the previous behaviour was more <em>defensible</em>
     * and less <em>correct</em>, which is the whole reason this pin is inverted rather than deleted:
     * anyone who "fixes" it must first re-pin that corpus row against a PROJ release that has fixed
     * it upstream.
     *
     * <p>{@code DatumParameters.setR_C(double phi0)} still takes the latitude &mdash; the formula
     * is right and matches {@code ell_set.cpp} case 7 exactly. Only the value {@code pj_init}
     * supplies for it is 0, and that is what {@code Proj4Parser} now passes.
     */
    @Test
    public void conformalSphereRadiusIgnoresLatitudeOfOrigin() {
        double atZero = a("+proj=merc +ellps=GRS80 +R_C");
        double at60 = a("+proj=merc +ellps=GRS80 +lat_0=60 +R_C");
        assertEquals("+R_C must NOT vary with +lat_0: pj_ellipsoid runs 85 lines before phi0 is "
                + "assigned, so P->phi0 is still 0. See this method's javadoc.", atZero, at60, 0.0);
        assertEquals("+R_C is +R_lat_g at latitude 0, which is the semi-minor axis",
                a("+proj=merc +ellps=GRS80 +R_lat_g=0"), atZero, 0.0);
        assertEquals("+R_C on GRS80 is GRS80's b", Ellipsoid.GRS80.getB(), atZero, 1e-6);
        assertNotEquals("+R_C is NOT +R_lat_g=lat_0; that is the reading the ell_set.cpp comment "
                        + "invites and the corpus refutes",
                a("+proj=merc +ellps=GRS80 +R_lat_g=60"), atZero, 1.0);
    }

    /**
     * Spherification keys do <i>not</i> suppress PROJ's implicit ellipsoid, so
     * a bare {@code +R_A} still has something to spherify.
     */
    @Test
    public void spherificationWithoutAnExplicitEllipsoidUsesTheDefault() {
        double es = Ellipsoid.GRS80.getEccentricitySquared();
        double expected = Ellipsoid.GRS80.getEquatorRadius()
                * (1. - es * (1 / 6.0 + es * (17 / 360.0 + es * (67 / 3024.0))));
        assertEquals(expected, a("+proj=merc +R_A"), 1e-9);
        assertEquals(0.0, es("+proj=merc +R_A"), 0.0);
        // PROJ 9.8.1: echo "2 1" | proj -f '%.9f' +proj=merc +R_A
        expect("+proj=merc +R_A", 2, 1, 222390.103954962, 111200.697732406, 1e-8);
    }

    /**
     * The four ellipsoids present in PROJ 9.8.1's {@code src/ellps.cpp} that were absent here, so
     * that {@code +ellps=<name>} threw {@code InvalidValueException: Unknown ellipsoid} for all of
     * them: {@code clrk80ign}, {@code danish}, {@code GSK2011}, {@code PZ90}.
     *
     * <h4>Why this test exists, and why its absence was itself a finding</h4>
     *
     * When those four were added, they were put into {@code Ellipsoid.ellipsoids} only — and
     * {@code +ellps=} does not read that array. {@code Registry.getEllipsoid} searches
     * {@code Registry.ellipsoids}, a <em>separate</em> list that had already drifted apart from it.
     * So the constants existed, the names still threw, and three WKT/PROJJSON tests broke for an
     * unrelated-looking reason: {@code io/wkt/WktNames} matches ellipsoids <b>numerically</b> against
     * {@code Ellipsoid.ellipsoids}, so growing that array changed what the writer <em>emitted</em>
     * without making anything <em>resolvable</em>.
     *
     * <p>An A/B later removed all four entries again and <b>no test failed</b>. The symptom had been
     * fixed and the defect was still uncovered. This is that missing net: it asserts the names
     * resolve, which is the property a user actually depends on.
     *
     * <p>Values are digit-for-digit from {@code 9.8.1:src/ellps.cpp} — never re-derived. Five
     * separate defects in this project have been rounded or re-derived constants.
     */
    @Test
    public void theFourAddedEllipsoidsResolveByName() {
        CRSFactory f = new CRSFactory();
        String[] names = { "clrk80ign", "danish", "GSK2011", "PZ90" };
        double[] majors = { 6378249.2, 6377019.2563, 6378136.5, 6378136.0 };
        for (int i = 0; i < names.length; i++) {
            CoordinateReferenceSystem crs =
                    f.createFromParameters("t", "+proj=longlat +ellps=" + names[i] + " +no_defs");
            Ellipsoid e = crs.getDatum().getEllipsoid();
            assertNotNull("+ellps=" + names[i] + " must resolve", e);
            assertEquals("+ellps=" + names[i] + " equator radius", majors[i], e.equatorRadius, 1e-9);
            assertNotEquals("+ellps=" + names[i] + " must not silently fall back to a default",
                    Ellipsoid.WGS84.equatorRadius, e.equatorRadius, 1e-9);
        }
    }

    /**
     * The positive control for the test above: a name that is genuinely absent must still throw.
     * Without this, {@code theFourAddedEllipsoidsResolveByName} could pass against an
     * implementation that resolved <em>everything</em> to a default — which is exactly the failure
     * mode {@code Units.findUnits} has, returning metres for any unrecognised unit name.
     */
    @Test
    public void agenuinelyUnknownEllipsoidNameStillThrows() {
        try {
            new CRSFactory().createFromParameters("t",
                    "+proj=longlat +ellps=no_such_ellipsoid_name +no_defs");
            fail("an unknown +ellps= name must not silently resolve");
        } catch (Proj4jException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().toLowerCase().contains("ellipsoid"));
        }
    }
}

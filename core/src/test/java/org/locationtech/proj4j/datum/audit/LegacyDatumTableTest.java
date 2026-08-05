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
package org.locationtech.proj4j.datum.audit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.locationtech.proj4j.util.ProjectionMath.MILLION;
import static org.locationtech.proj4j.util.ProjectionMath.SECONDS_TO_RAD;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.datum.Datum;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * A transcription audit of Proj4J's ten built-in datums against PROJ 9.8.1's own legacy
 * table, {@code src/datums.cpp}, read at tag {@code 9.8.1}
 * ({@code f08fa86c478c4bbbf003b1ec751dd84aa6eca486}).
 *
 * <h2>Why a table test and not only coordinate tests</h2>
 *
 * <p>Three of the ten were wrong, and all three were wrong in ways a coordinate test with
 * any ordinary tolerance would pass: a Helmert whose rotations had lost two decimal
 * places, an ellipsoid off by 55 mm of equatorial radius, and a datum whose PROJ
 * definition is a grid rather than a Helmert. The consequences are 3 mm, 20 mm and 148 m
 * respectively, so no single tolerance catches the set. Comparing the <i>parameters</i>
 * catches all three at once, and does it as a statement about the upstream source rather
 * than about a chosen probe point. {@link LegacyDatumAreaOfUseTest} then measures what
 * each parameter set is worth, at a point inside that datum's own area of use.
 *
 * <p>The upstream rows are quoted verbatim below so the audit is checkable without
 * cloning PROJ. Ellipsoid values are from {@code src/ellps.cpp} at the same tag.
 *
 * <pre>
 * {"WGS84",         "towgs84=0,0,0",                                                    "WGS84",     ""},
 * {"GGRS87",        "towgs84=-199.87,74.79,246.62",                                     "GRS80",     "Greek_Geodetic_Reference_System_1987"},
 * {"NAD83",         "towgs84=0,0,0",                                                    "GRS80",     "North_American_Datum_1983"},
 * {"NAD27",         "nadgrids=&#64;conus,&#64;alaska,&#64;ntv2_0.gsb,&#64;ntv1_can.dat", "clrk66",    "North_American_Datum_1927"},
 * {"potsdam",     /*"towgs84=598.1,73.7,418.2,0.202,0.045,-2.455,6.7",*&#47;
 *                   "nadgrids=&#64;BETA2007.gsb",                                       "bessel",    "Potsdam Rauenberg 1950 DHDN"},
 * {"carthage",      "towgs84=-263.0,6.0,431.0",                                         "clrk80ign", "Carthage 1934 Tunisia"},
 * {"hermannskogel", "towgs84=577.326,90.129,463.919,5.137,1.474,5.297,2.4232",          "bessel",    "Hermannskogel"},
 * {"ire65",         "towgs84=482.530,-130.596,564.557,-1.042,-0.214,-0.631,8.15",       "mod_airy",  "Ireland 1965"},
 * {"nzgd49",        "towgs84=59.47,-5.04,187.44,0.47,-0.1,1.024,-4.5993",               "intl",      "New Zealand Geodetic Datum 1949"},
 * {"OSGB36",        "towgs84=446.448,-125.157,542.060,0.1502,0.2470,0.8421,-20.4894",   "airy",      "Airy 1830"},
 * </pre>
 *
 * <h2>What was wrong</h2>
 * <ul>
 * <li><b>OSGB36</b> — {@code 542.06, 0.15, 0.247, 0.842, -20.489} instead of
 *     {@code 542.060, 0.1502, 0.2470, 0.8421, -20.4894}. The old numbers are EPSG:1314
 *     (<i>OSGB36 to WGS 84 (6)</i>) to the digit, which is why they read as deliberate.</li>
 * <li><b>carthage</b> — bound to {@code clrk80} ({@code a=6378249.145},
 *     {@code rf=293.4663}) where PROJ says {@code clrk80ign} ({@code a=6378249.2},
 *     {@code rf=293.4660212936269}).</li>
 * <li><b>potsdam</b> — PROJ's uncommented definition is a grid, not the Helmert. See
 *     {@link Datum#POTSDAM}'s javadoc for the measurement that decided how to reproduce
 *     a definition upstream ships in two halves.</li>
 * </ul>
 *
 * <p>The other seven transcribe correctly, component for component, including the
 * ellipsoid binding. Nothing is missing and nothing is extra: PROJ's table has exactly
 * these ten and so does {@link Registry#datums}.
 */
public class LegacyDatumTableTest {

    /**
     * Translations are stored as declared, and arcseconds survive the round trip through
     * {@link ProjectionMath#SECONDS_TO_RAD} to within a part in 1e13.
     */
    private static final double EXACT = 1.0e-12;

    /**
     * The scale difference does not: {@link Datum}'s constructor stores
     * {@code ds/1e6 + 1}, and adding 1 to 6.7e-6 discards the low bits of the mantissa,
     * so recovering {@code ds} returns 6.699999999915107 rather than 6.7. That is 8.5e-11
     * ppm, i.e. 0.5 nanometres on the Earth's radius, and it is a property of the storage
     * form rather than of the transcription. PROJ stores the same quantity the same way
     * ({@code helmert.cpp}: {@code Q->scale = 1 + s * 1e-6}).
     */
    private static final double PPM = 1.0e-9;

    // ---------------------------------------------------------------- helpers

    /**
     * {@link Datum}'s constructor rescales rotations to radians and the scale difference
     * to a multiplier, in place, so the declared arcsecond/ppm figures have to be
     * recovered before they can be compared with the upstream string.
     */
    private static double[] asDeclared(Datum d) {
        double[] t = d.getTransformToWGS84();
        if (t == null || t.length != 7) return t;
        double[] out = new double[7];
        out[0] = t[0];
        out[1] = t[1];
        out[2] = t[2];
        out[3] = t[3] / SECONDS_TO_RAD;
        out[4] = t[4] / SECONDS_TO_RAD;
        out[5] = t[5] / SECONDS_TO_RAD;
        out[6] = (t[6] - 1.0) * MILLION;
        return out;
    }

    private static void assertTowgs84(String datumId, Datum d, double[] expected) {
        double[] got = asDeclared(d);
        assertNotNull(datumId + ": towgs84 must be present", got);
        assertEquals(datumId + ": towgs84 arity", expected.length, got.length);
        String[] label = {"dx", "dy", "dz", "rx", "ry", "rz", "ds"};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(datumId + ": towgs84 " + label[i], expected[i], got[i],
                    i == 6 ? PPM : EXACT);
        }
    }

    /** Compares against a reference {@link Ellipsoid} built from PROJ's own ellps.cpp row. */
    private static void assertEllipsoidByRf(String datumId, Datum d,
                                            String shortName, double a, double rf) {
        Ellipsoid e = d.getEllipsoid();
        assertEquals(datumId + ": ellipsoid name", shortName, e.getShortName());
        assertEquals(datumId + ": ellipsoid a", a, e.getEquatorRadius(), 0.0);
        Ellipsoid ref = new Ellipsoid(shortName, a, 0.0, rf, "reference");
        assertEquals(datumId + ": ellipsoid es (from rf=" + rf + ")",
                ref.getEccentricitySquared(), e.getEccentricitySquared(), 1.0e-17);
    }

    private static void assertEllipsoidByB(String datumId, Datum d,
                                           String shortName, double a, double b) {
        Ellipsoid e = d.getEllipsoid();
        assertEquals(datumId + ": ellipsoid name", shortName, e.getShortName());
        assertEquals(datumId + ": ellipsoid a", a, e.getEquatorRadius(), 0.0);
        Ellipsoid ref = new Ellipsoid(shortName, a, b, 0.0, "reference");
        assertEquals(datumId + ": ellipsoid es (from b=" + b + ")",
                ref.getEccentricitySquared(), e.getEccentricitySquared(), 1.0e-17);
    }

    // ---------------------------------------------------------------- the ten rows

    /** {@code {"WGS84", "towgs84=0,0,0", "WGS84", ""}} */
    @Test
    public void wgs84() {
        assertEquals("WGS84", Datum.WGS84.getCode());
        assertTowgs84("WGS84", Datum.WGS84, new double[]{0, 0, 0});
        assertEllipsoidByRf("WGS84", Datum.WGS84, "WGS84", 6378137.0, 298.257223563);
        assertEquals(Datum.TYPE_WGS84, Datum.WGS84.getTransformType());
    }

    /**
     * {@code {"GGRS87", "towgs84=-199.87,74.79,246.62", "GRS80",
     * "Greek_Geodetic_Reference_System_1987"}}
     */
    @Test
    public void ggrs87() {
        assertEquals("GGRS87", Datum.GGRS87.getCode());
        assertTowgs84("GGRS87", Datum.GGRS87, new double[]{-199.87, 74.79, 246.62});
        assertEllipsoidByRf("GGRS87", Datum.GGRS87, "GRS80", 6378137.0, 298.257222101);
        assertEquals(Datum.TYPE_3PARAM, Datum.GGRS87.getTransformType());
    }

    /** {@code {"NAD83", "towgs84=0,0,0", "GRS80", "North_American_Datum_1983"}} */
    @Test
    public void nad83() {
        assertEquals("NAD83", Datum.NAD83.getCode());
        assertTowgs84("NAD83", Datum.NAD83, new double[]{0, 0, 0});
        assertEllipsoidByRf("NAD83", Datum.NAD83, "GRS80", 6378137.0, 298.257222101);
        assertEquals(Datum.TYPE_WGS84, Datum.NAD83.getTransformType());
    }

    /**
     * {@code {"NAD27", "nadgrids=@conus,@alaska,@ntv2_0.gsb,@ntv1_can.dat", "clrk66",
     * "North_American_Datum_1927"}}
     * <p>
     * The grid <i>list</i> is asserted by {@code parser.datumgrids} and
     * {@code grids.Ntv1CanHeaderTest}; what belongs here is that NAD27 is a grid-shift
     * datum with no Helmert at all and carries {@code clrk66}.
     */
    @Test
    public void nad27() {
        assertEquals("NAD27", Datum.NAD27.getCode());
        assertNull("NAD27 declares nadgrids, never towgs84",
                Datum.NAD27.getTransformToWGS84());
        assertEllipsoidByB("NAD27", Datum.NAD27, "clrk66", 6378206.4, 6356583.8);
        assertEquals("only ntv1_can.dat of the four ships, and it is enough to make the"
                        + " datum a grid-shift datum",
                Datum.TYPE_GRIDSHIFT, Datum.NAD27.getTransformType());
    }

    /**
     * {@code {"potsdam", /*"towgs84=598.1,73.7,418.2,0.202,0.045,-2.455,6.7",*&#47;
     * "nadgrids=@BETA2007.gsb", "bessel", "Potsdam Rauenberg 1950 DHDN"}}
     * <p>
     * Both halves of that source line are reproduced, and which one takes effect depends
     * on whether {@code BETA2007.gsb} is resolvable — which is exactly how
     * {@code cs2cs +datum=potsdam} behaves. See {@link Datum#POTSDAM}. Proj4J does not
     * ship the grid, so the assertion is on the Helmert fallback; the day the grid lands,
     * {@code getTransformType()} becomes {@code TYPE_GRIDSHIFT} and the last assertion
     * here is the one that will say so.
     */
    @Test
    public void potsdam() {
        assertEquals("potsdam", Datum.POTSDAM.getCode());
        assertTowgs84("potsdam", Datum.POTSDAM,
                new double[]{598.1, 73.7, 418.2, 0.202, 0.045, -2.455, 6.7});
        assertEllipsoidByRf("potsdam", Datum.POTSDAM, "bessel", 6377397.155, 299.1528128);
        assertEquals("BETA2007.gsb is not shipped, so the @-optional grid list is empty"
                        + " and the commented-out Helmert is what applies -- which is also"
                        + " what cs2cs falls back to when the grid is absent",
                Datum.TYPE_7PARAM, Datum.POTSDAM.getTransformType());
    }

    /** {@code {"carthage", "towgs84=-263.0,6.0,431.0", "clrk80ign", "Carthage 1934 Tunisia"}} */
    @Test
    public void carthage() {
        assertEquals("carthage", Datum.CARTHAGE.getCode());
        assertTowgs84("carthage", Datum.CARTHAGE, new double[]{-263.0, 6.0, 431.0});
        assertEllipsoidByRf("carthage", Datum.CARTHAGE,
                "clrk80ign", 6378249.2, 293.4660212936269);
        assertSame("PROJ says clrk80ign, not clrk80",
                Ellipsoid.CLRK80IGN, Datum.CARTHAGE.getEllipsoid());
        assertEquals(Datum.TYPE_3PARAM, Datum.CARTHAGE.getTransformType());
    }

    /**
     * {@code {"hermannskogel", "towgs84=577.326,90.129,463.919,5.137,1.474,5.297,2.4232",
     * "bessel", "Hermannskogel"}}
     */
    @Test
    public void hermannskogel() {
        assertEquals("hermannskogel", Datum.HERMANNSKOGEL.getCode());
        assertTowgs84("hermannskogel", Datum.HERMANNSKOGEL,
                new double[]{577.326, 90.129, 463.919, 5.137, 1.474, 5.297, 2.4232});
        assertEllipsoidByRf("hermannskogel", Datum.HERMANNSKOGEL,
                "bessel", 6377397.155, 299.1528128);
        assertEquals(Datum.TYPE_7PARAM, Datum.HERMANNSKOGEL.getTransformType());
    }

    /**
     * {@code {"ire65", "towgs84=482.530,-130.596,564.557,-1.042,-0.214,-0.631,8.15",
     * "mod_airy", "Ireland 1965"}}
     */
    @Test
    public void ire65() {
        assertEquals("ire65", Datum.IRE65.getCode());
        assertTowgs84("ire65", Datum.IRE65,
                new double[]{482.530, -130.596, 564.557, -1.042, -0.214, -0.631, 8.15});
        assertEllipsoidByB("ire65", Datum.IRE65, "mod_airy", 6377340.189, 6356034.446);
        assertEquals(Datum.TYPE_7PARAM, Datum.IRE65.getTransformType());
    }

    /**
     * {@code {"nzgd49", "towgs84=59.47,-5.04,187.44,0.47,-0.1,1.024,-4.5993", "intl",
     * "New Zealand Geodetic Datum 1949"}}
     */
    @Test
    public void nzgd49() {
        assertEquals("nzgd49", Datum.NZGD49.getCode());
        assertTowgs84("nzgd49", Datum.NZGD49,
                new double[]{59.47, -5.04, 187.44, 0.47, -0.1, 1.024, -4.5993});
        assertEllipsoidByRf("nzgd49", Datum.NZGD49, "intl", 6378388.0, 297.0);
        assertEquals(Datum.TYPE_7PARAM, Datum.NZGD49.getTransformType());
    }

    /**
     * {@code {"OSGB36", "towgs84=446.448,-125.157,542.060,0.1502,0.2470,0.8421,-20.4894",
     * "airy", "Airy 1830"}}
     */
    @Test
    public void osgb36() {
        assertEquals("OSGB36", Datum.OSGB36.getCode());
        assertTowgs84("OSGB36", Datum.OSGB36,
                new double[]{446.448, -125.157, 542.060, 0.1502, 0.2470, 0.8421, -20.4894});
        assertEllipsoidByRf("OSGB36", Datum.OSGB36, "airy", 6377563.396, 299.3249646);
        assertEquals(Datum.TYPE_7PARAM, Datum.OSGB36.getTransformType());
    }

    // ---------------------------------------------------------------- the set itself

    /**
     * PROJ's table has exactly ten live rows (an eleventh is the {@code nullptr}
     * sentinel), and {@link Registry} must offer exactly those ten under exactly those
     * ids. A silently added or renamed datum is a divergence in its own right, because
     * {@code +datum=} is resolved by string equality.
     */
    @Test
    public void theSetIsExactlyProjsTen() {
        String[] upstream = {"WGS84", "GGRS87", "NAD83", "NAD27", "potsdam", "carthage",
                "hermannskogel", "ire65", "nzgd49", "OSGB36"};
        Registry registry = new Registry();
        List<String> missing = new ArrayList<String>();
        for (int i = 0; i < upstream.length; i++) {
            if (registry.getDatum(upstream[i]) == null) missing.add(upstream[i]);
        }
        assertTrue("+datum= names PROJ 9.8.1 has and Proj4J does not: " + missing,
                missing.isEmpty());
        assertEquals("Proj4J must not offer datums PROJ's legacy table lacks",
                upstream.length, Registry.datums.length);
    }

    /**
     * The one place a wrong ellipsoid could still hide: {@link Registry#datums} must hand
     * out the very singletons {@link Datum} declares, so that an audit of the constants
     * is an audit of what {@code +datum=} resolves to.
     */
    @Test
    public void registryHandsOutTheDeclaredSingletons() {
        Registry registry = new Registry();
        assertSame(Datum.WGS84, registry.getDatum("WGS84"));
        assertSame(Datum.GGRS87, registry.getDatum("GGRS87"));
        assertSame(Datum.NAD83, registry.getDatum("NAD83"));
        assertSame(Datum.NAD27, registry.getDatum("NAD27"));
        assertSame(Datum.POTSDAM, registry.getDatum("potsdam"));
        assertSame(Datum.CARTHAGE, registry.getDatum("carthage"));
        assertSame(Datum.HERMANNSKOGEL, registry.getDatum("hermannskogel"));
        assertSame(Datum.IRE65, registry.getDatum("ire65"));
        assertSame(Datum.NZGD49, registry.getDatum("nzgd49"));
        assertSame(Datum.OSGB36, registry.getDatum("OSGB36"));
    }

    /**
     * A datum compared with itself must short-circuit on reference identity. The ten are
     * process-wide singletons and {@code isEqual}'s grid-shift branch otherwise descends
     * to {@code Arrays.equals} over every node of a loaded NTv2 table.
     */
    @Test
    public void identityComparisonIsCheapAndTrueForEveryOne() {
        for (int i = 0; i < Registry.datums.length; i++) {
            Datum d = Registry.datums[i];
            assertTrue(d.getCode() + " must equal itself", d.isEqual(d));
        }
    }
}

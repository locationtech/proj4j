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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.units.Unit;
import org.locationtech.proj4j.units.Units;

/**
 * {@code +units} / {@code +to_meter}, against PROJ 9.8.1.
 *
 * <h2>Reference values</h2>
 *
 * <p>Every expected easting/northing below was produced by the installed PROJ 9.8.1
 * ({@code Rel. 9.8.1, April 10th, 2026}):
 *
 * <pre>
 * $ echo "2 1" | proj -f '%.9f' +proj=merc +ellps=GRS80 &lt;more params&gt;
 * </pre>
 *
 * <p>These are <b>projected metres</b> (or the stated linear unit), compared per
 * ordinate. No angular tolerance is involved anywhere in this file.
 *
 * <h2>The rule being pinned</h2>
 *
 * <p>{@code init.cpp:678-714} resolves the linear unit exactly once. If {@code +units}
 * is present it is looked up in {@code pj_list_linear_units()} and <i>that table
 * entry's</i> {@code to_meter} string is what gets parsed — {@code +to_meter} is never
 * read at all. Only in the absence of {@code +units} does {@code +to_meter} apply.
 * Proj4J used to apply {@code +units} and then overwrite it with {@code +to_meter}, so
 * {@code +to_meter} won: exactly inverted, and silent.
 */
public class LinearUnitParsingTest {

    /** PROJ printed 9 decimals; 1e-6 of the stated unit is far inside that. */
    private static final double TOL = 1e-6;

    private static final String MERC = "+proj=merc +ellps=GRS80";

    /** {@code +proj=merc +ellps=GRS80} at (2, 1), in metres. */
    private static final double METRE_X = 222638.981586547;
    private static final double METRE_Y = 110579.965218250;

    /** The same, in U.S. survey feet ({@code +units=us-ft}). */
    private static final double US_FOOT_X = 730441.392088531;
    private static final double US_FOOT_Y = 362794.435886874;

    /** The same, in international feet — {@code +to_meter=0.3048}. */
    private static final double INTL_FOOT_X = 730442.852974236;
    private static final double INTL_FOOT_Y = 362795.161477197;

    private final CRSFactory crsFactory = new CRSFactory();

    private CoordinateReferenceSystem crs(String def) {
        return crsFactory.createFromParameters("test", def);
    }

    private ProjCoordinate project(String def, double lon, double lat) {
        ProjCoordinate out = new ProjCoordinate(Double.NaN, Double.NaN);
        crs(def).getProjection().project(new ProjCoordinate(lon, lat), out);
        return out;
    }

    private void expect(String def, double x, double y) {
        ProjCoordinate out = project(def, 2, 1);
        assertEquals(def + " easting", x, out.x, TOL);
        assertEquals(def + " northing", y, out.y, TOL);
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
    // +units beats +to_meter, in either order
    // ------------------------------------------------------------------

    /**
     * The discriminating pair: the U.S. survey foot and the international foot differ in
     * the 7th significant figure, so which one won is unambiguous from the number.
     */
    @Test
    public void unitsWinsOverToMeterWhateverTheOrder() {
        expect(MERC + " +units=us-ft +to_meter=0.3048", US_FOOT_X, US_FOOT_Y);
        expect(MERC + " +to_meter=0.3048 +units=us-ft", US_FOOT_X, US_FOOT_Y);

        // ...and the control: without +units, +to_meter is what applies.
        expect(MERC + " +to_meter=0.3048", INTL_FOOT_X, INTL_FOOT_Y);
    }

    /**
     * {@code +units} must win even when the {@code +to_meter} it displaces is absurd.
     * Under the old ordering this returned the metre values scaled by 1e-9.
     */
    @Test
    public void unitsWinsOverAToMeterThatWouldDominateTheResult() {
        expect(MERC + " +units=us-ft +to_meter=1e9", US_FOOT_X, US_FOOT_Y);
    }

    /** {@code getUnits()} must report the unit actually in force. */
    @Test
    public void theReportedUnitIsTheOneThatWon() {
        assertSame(Units.US_FEET, crs(MERC + " +to_meter=0.3048 +units=us-ft")
                .getProjection().getUnits());
        // +to_meter alone names no unit, so the accessor's metres default stands -
        // which is a pre-existing wart, not a claim that the scale is 1.
        assertSame(Units.METRES, crs(MERC + " +to_meter=0.3048").getProjection().getUnits());
    }

    /** Duplicate keys: the first occurrence wins, for these two as for every other. */
    @Test
    public void theFirstOccurrenceOfADuplicatedUnitKeyWins() {
        expect(MERC + " +to_meter=0.3048 +to_meter=1", INTL_FOOT_X, INTL_FOOT_Y);
        expect(MERC + " +units=us-ft +units=m", US_FOOT_X, US_FOOT_Y);
        expect(MERC + " +units=m +units=us-ft", METRE_X, METRE_Y);
    }

    // ------------------------------------------------------------------
    // +to_meter accepts a num/den ratio
    // ------------------------------------------------------------------

    /**
     * {@code init.cpp:690-710}: {@code pj_strtod} stops at a {@code '/'}, and the
     * remainder is read as a denominator. PROJ's own unit table uses that syntax —
     * {@code dm} is {@code "1/10"} and {@code us-in} is {@code "1/39.37"} — so it is not
     * an exotic corner. Proj4J used bare {@code Double.parseDouble} and threw.
     */
    @Test
    public void toMeterAcceptsARatio() {
        expect(MERC + " +to_meter=3048/10000", INTL_FOOT_X, INTL_FOOT_Y);
        expect(MERC + " +to_meter=1/3.2808398950131235", INTL_FOOT_X, INTL_FOOT_Y);
        expect(MERC + " +to_meter=1/1", METRE_X, METRE_Y);
        // 1/10 is exactly how PROJ's table spells the decimetre.
        expect(MERC + " +to_meter=1/10", METRE_X * 10, METRE_Y * 10);
    }

    /** A zero denominator is an error upstream, not a division by zero. */
    @Test
    public void aZeroDenominatorIsRejected() {
        assertTrue(rejected(MERC + " +to_meter=1/0").getMessage().contains("to_meter"));
        assertTrue(rejected(MERC + " +to_meter=1/0.0").getMessage().contains("to_meter"));
    }

    /** {@code to_meter <= 0} is an error ({@code init.cpp:706}). */
    @Test
    public void aNonPositiveToMeterIsRejected() {
        rejected(MERC + " +to_meter=0");
        rejected(MERC + " +to_meter=-0.3048");
        rejected(MERC + " +to_meter=-1/2");
        rejected(MERC + " +to_meter=nonsense");
    }

    // ------------------------------------------------------------------
    // What the linear unit does NOT touch
    // ------------------------------------------------------------------

    /**
     * {@code +x_0} and {@code +y_0} are <b>always metres</b>
     * ({@code init.cpp:660-661}, a plain {@code "d"} lookup with no unit involvement).
     * The output affine is {@code fr_meter * (a*x + x_0)} ({@code fwd.cpp:143-146}), so
     * the false easting is converted <i>with</i> the coordinate on the way out, never
     * interpreted <i>as</i> the declared unit on the way in.
     * <p>
     * If {@code +x_0=500000} were read as 500000 U.S. feet, the easting would come out
     * near 1230441 rather than 2370858 — so the two readings are far apart.
     */
    @Test
    public void unitsDoesNotChangeWhatXZeroAndYZeroMean() {
        expect(MERC + " +units=us-ft +x_0=500000", 2370858.058755198, US_FOOT_Y);
        expect(MERC + " +units=km +x_0=500000 +y_0=200000", 722.638981587, 310.579965218);
        expect(MERC + " +x_0=500000 +y_0=200000", METRE_X + 500000, METRE_Y + 200000);

        // +to_meter must behave identically: km and to_meter=1000 are the same thing.
        expect(MERC + " +to_meter=1000 +x_0=500000 +y_0=200000", 722.638981587, 310.579965218);
    }

    /**
     * {@code +a} and {@code +b} are always metres too. A sphere of 6400000 <i>metres</i>
     * projected into U.S. feet is 732945.2 ft; a sphere of 6400000 <i>feet</i> would be
     * a third of that.
     */
    @Test
    public void unitsDoesNotChangeWhatAAndBMean() {
        expect("+proj=merc +a=6400000", 223402.144255274, 111706.743574944);
        expect("+proj=merc +a=6400000 +units=us-ft", 732945.201610846, 366491.207878797);
        assertEquals("the parsed semi-major axis is unaffected by +units",
                6400000.0,
                crs("+proj=merc +a=6400000 +units=us-ft").getDatum().getEllipsoid()
                        .getEquatorRadius(),
                0.0);
    }

    // ------------------------------------------------------------------
    // The unit table itself
    // ------------------------------------------------------------------

    /**
     * All 21 of PROJ's linear unit ids must now resolve. Four were declared in
     * {@code Units} but missing from the lookup array ({@code fath}, {@code ch},
     * {@code link}, {@code us-ch}) and the three Indian units were absent altogether —
     * and because {@code findUnits} substitutes metres rather than returning null, every
     * one of them silently scaled by 1.
     */
    @Test
    public void everyLinearUnitProjHasResolves() {
        String[] ids = {
                "km", "m", "dm", "cm", "mm", "kmi", "in", "ft", "yd", "mi",
                "fath", "ch", "link",
                "us-in", "us-ft", "us-yd", "us-ch", "us-mi",
                "ind-yd", "ind-ft", "ind-ch",
        };
        List<String> unresolved = new ArrayList<String>();
        for (String id : ids) {
            if (!Units.isKnownUnit(id)) {
                unresolved.add(id);
            }
        }
        assertTrue("PROJ linear units Proj4J still cannot resolve: " + unresolved,
                unresolved.isEmpty());
        assertEquals("Units.LINEAR_UNITS must be PROJ's table, no more and no less",
                ids.length, Units.LINEAR_UNITS.length);
    }

    /**
     * The scale each newly reachable unit applies, checked against PROJ rather than
     * against the constant in {@code Units} — otherwise a wrong constant would agree
     * with itself.
     */
    @Test
    public void theNewlyReachableUnitsScaleAsProjDoes() {
        expect(MERC + " +units=fath", 121740.475495706, 60465.860246199);
        expect(MERC + " +units=ch", 11067.315954155, 5496.896386018);
        expect(MERC + " +units=link", 1106731.595415509, 549689.638601813);
        expect(MERC + " +units=us-ch", 11067.293819523, 5496.885392225);
        expect(MERC + " +units=ind-yd", 243482.221125046, 120932.351340295);
        expect(MERC + " +units=ind-ft", 730446.663375137, 362797.054020884);
        expect(MERC + " +units=ind-ch", 11067.373687502, 5496.925060922);
    }

    /**
     * {@code ind-ft} and {@code us-ft} differ by about 7 parts per million, and
     * {@code ch}/{@code us-ch}/{@code ind-ch} by similar amounts. Since the old
     * behaviour was to return metres for all of them, this asserts they are now
     * distinct from each other and from metres — the property a per-value check alone
     * would not establish.
     */
    @Test
    public void theNewUnitsAreDistinctFromMetresAndFromEachOther() {
        String[] ids = {"fath", "ch", "link", "us-ch", "ind-yd", "ind-ft", "ind-ch"};
        for (String id : ids) {
            Unit unit = Units.findUnits(id);
            assertFalse(id + " must no longer fall back to metres", unit == Units.METRES);
            assertEquals(id, unit.abbreviation);
        }
        assertFalse("ind-ft and us-ft must not be the same unit",
                Units.findUnits("ind-ft").value == Units.findUnits("us-ft").value);
        assertFalse("ch and us-ch must not be the same unit",
                Units.findUnits("ch").value == Units.findUnits("us-ch").value);
        assertFalse("ch and ind-ch must not be the same unit",
                Units.findUnits("ch").value == Units.findUnits("ind-ch").value);
    }

    /**
     * The angular ids are <b>not</b> {@code +units} names. PROJ resolves {@code +units}
     * against {@code pj_list_linear_units()} only, so {@code +units=rad} is
     * {@code "Invalid value for units"} upstream — verified:
     * <pre>
     * $ echo "2 1" | proj +proj=merc +ellps=GRS80 +units=rad
     * merc: Invalid value for units
     * </pre>
     * They must therefore stay out of the {@code +units} lookup, or Proj4J would accept
     * definitions PROJ rejects.
     */
    @Test
    public void angularUnitIdsAreNotLinearUnitNames() {
        assertFalse("+units=rad must not resolve", Units.isKnownUnit("rad"));
        assertFalse("+units=grad must not resolve", Units.isKnownUnit("grad"));
        for (Unit unit : Units.LINEAR_UNITS) {
            assertFalse("the linear table must not contain " + unit.abbreviation,
                    unit == Units.RADIANS || unit == Units.GRADS);
        }
        // ...but the constants exist, so a unitconvert-style caller can reach them.
        assertEquals(3, Units.ANGULAR_UNITS.length);
        assertEquals(0.9, Units.GRADS.value, 0.0);
    }

    /** STRICT mode turns the silent metres fallback into an error, for the new ids too. */
    @Test
    public void strictModeStillRejectsWhatProjRejects() {
        Proj4Parser strict = new Proj4Parser(new org.locationtech.proj4j.Registry(),
                Proj4Parser.ParseMode.STRICT);
        for (String bad : new String[]{"rad", "grad", "furlong", "point", "min"}) {
            try {
                strict.parse("t", (MERC + " +units=" + bad).split(" "));
                fail("STRICT must reject +units=" + bad);
            } catch (InvalidValueException expected) {
                assertTrue(expected.getMessage().contains(bad));
            }
        }
        for (String good : new String[]{"fath", "ch", "link", "us-ch", "ind-yd", "ind-ft", "ind-ch"}) {
            assertSame(good, Units.findUnits(good),
                    strict.parse("t", (MERC + " +units=" + good).split(" "))
                            .getProjection().getUnits());
        }
    }
}

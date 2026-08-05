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
package org.locationtech.proj4j.gie;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link GieTolerance} against {@code strtod_scaled}, {@code tolerance} and
 * {@code column} at PROJ 9.8.1 {@code src/apps/gie.cpp:470-497, 502-554}.
 */
public class GieToleranceTest {

    private static final double EPS = 0.0;

    @Test
    public void grs80DegIsTheDocumentedConstant() {
        assertEquals(111319.4908, GieTolerance.GRS80_DEG, EPS);
    }

    // ---------------------------------------------------------------- column

    @Test
    public void columnZeroAndNegativeReturnTheWholeString() {
        assertEquals("0.5 mm", GieTolerance.column("0.5 mm", 0));
        assertEquals("0.5 mm", GieTolerance.column("0.5 mm", -1));
    }

    @Test
    public void columnOneSkipsLeadingWhitespaceOnly() {
        assertEquals("0.5 mm", GieTolerance.column("  0.5 mm", 1));
    }

    /**
     * The trap: {@code column(args, 2)} is the whole tail from the second token, not the second
     * token. This is what makes the unit comparison exact.
     */
    @Test
    public void columnTwoIsTheTailNotJustTheSecondToken() {
        assertEquals("mm", GieTolerance.column("0.5 mm", 2));
        assertEquals("mm and then some", GieTolerance.column("0.5 mm and then some", 2));
        assertEquals("", GieTolerance.column("0.5", 2));
        assertEquals("", GieTolerance.column("0.5mm", 2));
    }

    // ------------------------------------------------------------- unit table

    @Test
    public void decadalPrefixes() {
        assertEquals(1000.0, GieTolerance.tolerance("1 km"), EPS);
        assertEquals(1.0, GieTolerance.tolerance("1 m"), EPS);
        assertEquals(0.1, GieTolerance.tolerance("1 dm"), EPS);
        assertEquals(0.01, GieTolerance.tolerance("1 cm"), EPS);
        assertEquals(0.001, GieTolerance.tolerance("1 mm"), EPS);
        assertEquals(1e-6, GieTolerance.tolerance("1 um"), EPS);
        assertEquals(1e-9, GieTolerance.tolerance("1 nm"), EPS);
    }

    /** The two tolerances the corpus actually leans on. */
    @Test
    public void theCorpusWorkhorses() {
        assertEquals(0.0005, GieTolerance.tolerance("0.5 mm"), EPS);
        assertEquals(0.03, GieTolerance.tolerance("0.03 m"), EPS);
    }

    /** A bare number is metres, because default_scale is 1 for tolerance(). */
    @Test
    public void absentUnitMeansMetres() {
        assertEquals(0.01, GieTolerance.tolerance("0.01"), EPS);
        assertEquals(2.0, GieTolerance.tolerance("2"), EPS);
    }

    /** Unrecognised unit is not an error; it is the default scale, exactly like an absent one. */
    @Test
    public void unrecognisedUnitFallsBackToTheDefaultScale() {
        assertEquals(0.5, GieTolerance.tolerance("0.5 furlong"), EPS);
        assertEquals(0.5, GieTolerance.tolerance("0.5 M"), EPS); // strcmp is case sensitive
        assertEquals(0.5, GieTolerance.tolerance("0.5 mm please"), EPS);
        assertEquals(0.5, GieTolerance.tolerance("0.5mm"), EPS); // no space -> only one column
    }

    @Test
    public void defaultScaleIsHonouredByStrtodScaled() {
        assertEquals(500.0, GieTolerance.strtodScaled("0.5", 1000.0), EPS);
        assertEquals(1000.0, GieTolerance.strtodScaled("1 km", 1000.0), EPS); // unit wins
    }

    // ------------------------------------------------------------ angular units

    /**
     * {@code rad} and {@code deg} are the only two places {@link GieTolerance#GRS80_DEG} is
     * ever consulted, and no {@code .gie} file in the 9.8.1 corpus uses either.
     */
    @Test
    public void degreeToleranceUsesTheEquatorialScale() {
        assertEquals(GieTolerance.GRS80_DEG, GieTolerance.tolerance("1 deg"), EPS);
        assertEquals(2 * GieTolerance.GRS80_DEG, GieTolerance.tolerance("2 deg"), EPS);
    }

    @Test
    public void radianToleranceConvertsToDegreesFirst() {
        assertEquals(GieTolerance.GRS80_DEG * Math.toDegrees(1.0),
                GieTolerance.tolerance("1 rad"), EPS);
        // Sanity: one radian of longitude at the equator is about one earth radius.
        assertEquals(6378137.0, GieTolerance.tolerance("1 rad"), 1.0);
    }

    // --------------------------------------------------------------- fallbacks

    @Test
    public void unparseableResetsToTheFallback() {
        assertEquals(0.0005, GieTolerance.tolerance(""), EPS);
        assertEquals(0.0005, GieTolerance.tolerance("   "), EPS);
        assertEquals(0.0005, GieTolerance.tolerance("abc"), EPS);
        assertEquals(0.0005, GieTolerance.tolerance("mm"), EPS);
        assertEquals(GieTolerance.FALLBACK_TOLERANCE, GieTolerance.tolerance("?"), EPS);
    }

    @Test
    public void strtodScaledSignalsUnparseableWithHugeVal() {
        assertTrue(Double.isInfinite(GieTolerance.strtodScaled("abc", 1)));
        assertTrue(GieTolerance.strtodScaled("abc", 1) > 0);
    }

    @Test
    public void theInitialToleranceEqualsHalfAMillimetre() {
        assertEquals(GieTolerance.DEFAULT_TOLERANCE, GieTolerance.tolerance("0.5 mm"), EPS);
    }

    // ------------------------------------------------------------ number forms

    @Test
    public void numberForms() {
        assertEquals(0.001, GieTolerance.tolerance("1e-3"), EPS);
        assertEquals(0.001, GieTolerance.tolerance("1E-3 m"), EPS);
        assertEquals(-1.0, GieTolerance.tolerance("-1 m"), EPS);
        assertEquals(1.0, GieTolerance.tolerance("+1 m"), EPS);
        assertEquals(0.5, GieTolerance.tolerance(".5 m"), EPS);
        assertEquals(1000.0, GieTolerance.tolerance("1_000 m"), EPS); // proj_strtod allows _
        assertEquals(0.001, GieTolerance.tolerance("  1 mm"), EPS);   // leading whitespace
    }
}

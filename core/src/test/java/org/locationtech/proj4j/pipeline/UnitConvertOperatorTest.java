/*
 * Copyright 2026 The Proj4J Contributors.
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
package org.locationtech.proj4j.pipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.gie.GieIoUnits;

/**
 * {@code +proj=unitconvert} against {@code 9.8.1:src/conversions/unitconvert.cpp}
 * and its unit tables in {@code src/units.cpp}.
 */
public class UnitConvertOperatorTest {

    private static UnitConvertOperator of(String definition) {
        return new UnitConvertOperator(ProjParams.parse(definition));
    }

    private static double[] fwd(UnitConvertOperator op, double x, double y, double z) {
        double[] c = {x, y, z, 0};
        op.forward(c);
        return c;
    }

    // ----------------------------------------------------------- grad -> rad

    /**
     * {@code gigs/5102.2.gie}'s first step. {@code GRAD_TO_RAD} is
     * {@code M_PI / 200}, which {@code units.cpp:41} spells as the literal
     * {@code 0.015707963267948967}.
     */
    @Test
    public void gradToRadUsesPiOverTwoHundred() {
        UnitConvertOperator op = of("+proj=unitconvert +xy_in=grad +xy_out=rad");
        double[] out = fwd(op, 200.0, 100.0, 0.0);
        assertEquals(Math.PI, out[0], 1e-15);
        assertEquals(Math.PI / 2.0, out[1], 1e-15);
    }

    /**
     * The heart of {@code gie-comparator.md}'s trap 6, and the one behaviour in this
     * class that must <em>not</em> be tidied up.
     *
     * <p>{@code unitconvert.cpp:487-493} raises the unit domain only for the
     * normalised names {@code "Radian"} and {@code "Degree"}. {@code grad}
     * normalises to {@code "Grad"}, so the side it appears on stays
     * {@link GieIoUnits#WHATEVER} — and the gie comparator's {@code WHATEVER} branch
     * is Euclidean. So {@code gigs/5102.2.gie}'s reverse pipeline, which ends
     * {@code +xy_out=grad}, has its residuals measured as a plain hypotenuse of two
     * <em>grad</em> values against {@code tolerance 0.03 m}. That is deliberate
     * upstream behaviour; "fixing" it silently re-pins 38 expected values.
     */
    @Test
    public void gradDoesNotRaiseTheUnitDomainButRadAndDegDo() {
        UnitConvertOperator gradToRad = of("+proj=unitconvert +xy_in=grad +xy_out=rad");
        assertEquals(GieIoUnits.WHATEVER, gradToRad.declaredLeft());
        assertEquals(GieIoUnits.RADIANS, gradToRad.declaredRight());

        UnitConvertOperator radToGrad = of("+proj=unitconvert +xy_in=rad +xy_out=grad");
        assertEquals(GieIoUnits.RADIANS, radToGrad.declaredLeft());
        assertEquals("grad normalises to \"Grad\", so the right side stays WHATEVER",
                GieIoUnits.WHATEVER, radToGrad.declaredRight());

        UnitConvertOperator degToRad = of("+proj=unitconvert +xy_in=deg +xy_out=rad");
        assertEquals(GieIoUnits.DEGREES, degToRad.declaredLeft());
        assertEquals(GieIoUnits.RADIANS, degToRad.declaredRight());
    }

    @Test
    public void inverseUndoesForward() {
        UnitConvertOperator op = of("+proj=unitconvert +xy_in=grad +xy_out=rad");
        double[] c = {64.4444444444, 2.9586342556, 0, 0};
        op.forward(c);
        op.inverse(c);
        assertEquals(64.4444444444, c[0], 1e-12);
        assertEquals(2.9586342556, c[1], 1e-12);
    }

    // ------------------------------------------------------------ linear units

    /**
     * The distinction {@code gigs/5103.2} and {@code 5103.3} exist to separate:
     * {@code +units=ft} on {@code EPSG:2921} against {@code +units=us-ft} on
     * {@code EPSG:3568}, over the same input, giving eastings four metres apart.
     */
    @Test
    public void footAndUsSurveyFootAreNotTheSame() {
        double[] intl = fwd(of("+proj=unitconvert +xy_in=ft +xy_out=m"), 1, 0, 0);
        double[] us = fwd(of("+proj=unitconvert +xy_in=us-ft +xy_out=m"), 1, 0, 0);
        assertEquals(0.3048, intl[0], 0.0);
        assertEquals(1200 / 3937.0, us[0], 0.0);
        assertEquals("about 2 ppm apart", 6.09e-7, us[0] - intl[0], 1e-9);
    }

    @Test
    public void theVerticalPairIsIndependentOfTheHorizontalOne() {
        double[] out = fwd(of("+proj=unitconvert +xy_in=m +xy_out=km +z_in=ft +z_out=m"),
                2000, 3000, 10);
        assertEquals(2.0, out[0], 0.0);
        assertEquals(3.0, out[1], 0.0);
        assertEquals(3.048, out[2], 1e-12);
    }

    // ------------------------------------------------------------ raw factors

    @Test
    public void anUnknownIdIsReadAsARawFactor() {
        // unitconvert.cpp:472-479 falls back to pj_param type 'd'.
        UnitConvertOperator op = of("+proj=unitconvert +xy_in=2 +xy_out=1");
        assertEquals(4.0, fwd(op, 2, 0, 0)[0], 0.0);
        assertEquals("a raw factor carries no normalised name",
                GieIoUnits.WHATEVER, op.declaredLeft());
        assertEquals(GieIoUnits.WHATEVER, op.declaredRight());
    }

    @Test
    public void aZeroOrUnparseableFactorIsRejected() {
        for (String bad : new String[] {"+proj=unitconvert +xy_in=0 +xy_out=m",
                "+proj=unitconvert +xy_in=furlong +xy_out=m"}) {
            try {
                of(bad);
                fail("expected a rejection of " + bad);
            } catch (PipelineDefinitionException e) {
                assertEquals(PipelineErrorCode.ILLEGAL_ARG_VALUE, e.code());
            }
        }
    }

    // ----------------------------------------------------------- consistency

    @Test
    public void mixingALinearWithAnAngularUnitIsRejected() {
        try {
            of("+proj=unitconvert +xy_in=deg +xy_out=m");
            fail("expected a rejection");
        } catch (PipelineDefinitionException e) {
            assertEquals(PipelineErrorCode.ILLEGAL_ARG_VALUE, e.code());
        }
    }

    /**
     * A time unit is refused rather than silently dropped. Ignoring it would leave
     * the epoch unconverted while every other ordinate was converted, which is a
     * wrong answer wearing the clothes of a right one.
     */
    @Test
    public void aTimeUnitIsRefusedRatherThanIgnored() {
        try {
            of("+proj=unitconvert +t_in=decimalyear +t_out=gps_week");
            fail("expected a rejection");
        } catch (PipelineDefinitionException e) {
            assertEquals(PipelineErrorCode.NOT_IMPLEMENTED_HERE, e.code());
            assertEquals("this is not something PROJ rejects", false, e.isRejectedByProj());
        }
    }

    @Test
    public void anOmittedSideIsTheIdentityOnThatSide() {
        // Only xy_in given: the factor is the input unit's, with no division.
        assertEquals(0.3048, fwd(of("+proj=unitconvert +xy_in=ft"), 1, 0, 0)[0], 0.0);
        // Only xy_out given: the reciprocal.
        assertEquals(1 / 0.3048, fwd(of("+proj=unitconvert +xy_out=ft"), 1, 0, 0)[0], 1e-15);
    }
}

/*
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
package org.locationtech.proj4j.vertical;

import org.junit.Test;
import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.pipeline.Pipeline;
import org.locationtech.proj4j.pipeline.PipelineDefinitionException;
import org.locationtech.proj4j.pipeline.PipelineFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code +geoidgrids} becomes an auto-inserted {@code +proj=vgridshift} step, and
 * {@code +vunits}/{@code +vto_meter}/{@code +z_0} scale the height.
 *
 * <h2>Where every expected number came from</h2>
 *
 * <p>All of them from PROJ 9.8.1's {@code cct} reading <b>the same grid file</b> this test
 * reads — {@code core/src/test/resources/proj4j-data/grids/egm96_15_downsampled.gtx}, which is
 * PROJ's own {@code 9.8.1:data/tests/egm96_15_downsampled.gtx}. {@code cct} rather than
 * {@code cs2cs}, because {@code cs2cs} promotes datum-less sides to full CRSs and would not run
 * the legacy {@code +geoidgrids} path at all. Reproduce with:
 *
 * <pre>
 * export PROJ_DATA=core/src/test/resources/proj4j-data/grids:/opt/homebrew/share/proj
 * echo "12.5 55.5 0" | cct -d 12 +proj=latlong +geoidgrids=egm96_15_downsampled.gtx \
 *                                +ellps=GRS80
 *   12.500000000000  55.500000000000  -36.394090697107</pre>
 *
 * <p>The corpus agrees independently: {@code gie/4D-API_cs2cs-style.gie} asserts
 * {@code -36.3941} at the same point for {@code proj=latlong geoidgrids=egm96_15.gtx
 * ellps=GRS80} and {@code 1391493.63492 7424275.19462 -36.3941} for the {@code proj=merc}
 * form. That corpus uses the <em>full</em> {@code egm96_15.gtx}; the downsampled fixture
 * lands within its stated 15&nbsp;cm tolerance and, at this point, agrees to 1e-4&nbsp;m.
 *
 * <h2>The sign convention, which is the easy thing to get backwards</h2>
 *
 * <p>{@code vgridshift.cpp:206} sets {@code forward_multiplier = -1.0} with the comment
 * "historical: the forward direction subtracts the grid offset". The geoid undulation at
 * (12.5&deg;E, 55.5&deg;N) is {@code +36.394090697107}, so the forward direction produces
 * {@code -36.394090697107} from a zero input: an ellipsoidal height becomes an orthometric
 * one. {@link #theMultiplierIsExposedOnAnExplicitStepButNotInheritedByTheHiddenOne()} pins both
 * halves of that.
 */
public class GeoidGridsAutoStepTest {

    /** PROJ's own {@code data/tests/egm96_15_downsampled.gtx}, on the test classpath. */
    private static final String GRID = "egm96_15_downsampled.gtx";

    /** {@code cct}, to twelve figures. */
    private static final double N_AT_12_5_55_5 = 36.394090697107;

    private static final double MM = 1e-9;

    private static double[] forwardDegrees(String definition, double lonDeg, double latDeg,
                                          double z) {
        return new PipelineFactory().create(definition)
                .forward(new double[] {Math.toRadians(lonDeg), Math.toRadians(latDeg), z, 0});
    }

    private static double[] inverseDegrees(String definition, double lonDeg, double latDeg,
                                           double z) {
        return new PipelineFactory().create(definition)
                .inverse(new double[] {Math.toRadians(lonDeg), Math.toRadians(latDeg), z, 0});
    }

    /**
     * The five points {@code cct} was run on, forward, through {@code +proj=latlong}.
     *
     * <pre>
     * 12.5   55.5     0 -&gt; -36.394090697107
     * 12.5   55.5   100 -&gt;  63.605909302893
     *  0.0    0.0     0 -&gt; -17.234017133713
     * -75.25 40.75     0 -&gt;  33.931076938336
     * 151.2 -33.85     0 -&gt; -22.105806101939</pre>
     */
    @Test
    public void latlongWithGeoidGridsMatchesProj981() {
        String def = "+proj=latlong +geoidgrids=" + GRID + " +ellps=GRS80";
        double[][] cases = {
            {12.5, 55.5, 0.0, -36.394090697107},
            {12.5, 55.5, 100.0, 63.605909302893},
            {0.0, 0.0, 0.0, -17.234017133713},
            {-75.25, 40.75, 0.0, 33.931076938336},
            {151.2, -33.85, 0.0, -22.105806101939},
        };
        for (int i = 0; i < cases.length; i++) {
            double[] out = forwardDegrees(def, cases[i][0], cases[i][1], cases[i][2]);
            assertEquals("longitude is untouched at " + cases[i][0] + ", " + cases[i][1],
                    cases[i][0], Math.toDegrees(out[0]), 1e-12);
            assertEquals("latitude is untouched at " + cases[i][0] + ", " + cases[i][1],
                    cases[i][1], Math.toDegrees(out[1]), 1e-12);
            assertEquals("orthometric height at " + cases[i][0] + ", " + cases[i][1],
                    cases[i][3], out[2], MM);
        }
    }

    /**
     * The vertical shift is applied in {@code fwd_prepare}, i.e. <b>before</b> the projection
     * formula, so a projected target carries the shifted height alongside its easting and
     * northing.
     *
     * <p>{@code cct}: {@code 1391493.634915919509  7424275.194622228853  -36.394090697107}.
     * The corpus's {@code proj=merc geoidgrids=egm96_15.gtx} row asserts the same three values
     * rounded, at 0.1&nbsp;mm.
     */
    @Test
    public void aProjectedTargetCarriesTheShiftedHeight() {
        double[] out = forwardDegrees("+proj=merc +geoidgrids=" + GRID + " +ellps=GRS80",
                12.5, 55.5, 0.0);
        assertEquals("easting", 1391493.634915919509, out[0], 1e-6);
        assertEquals("northing", 7424275.194622228853, out[1], 1e-6);
        assertEquals("height", -36.394090697107, out[2], MM);
    }

    /**
     * The inverse undoes it, in the mirrored position: {@code inv_finalize} runs
     * {@code vgridshift} inverse <em>before</em> the datum shift, where the forward runs it
     * after.
     *
     * <p>{@code echo "12.5 55.5 -36.3" | cct -d 12 -I +proj=latlong +geoidgrids=... }
     * gives {@code 0.094090697107}, i.e. {@code -36.3 + 36.394090697107}.
     */
    @Test
    public void theInverseGoesGeometricFromOrthometric() {
        double[] out = inverseDegrees("+proj=latlong +geoidgrids=" + GRID + " +ellps=GRS80",
                12.5, 55.5, -36.3);
        assertEquals(0.094090697107, out[2], MM);
    }

    /** A round trip through the projected form returns the height it started with. */
    @Test
    public void theProjectedFormRoundTrips() {
        String def = "+proj=merc +geoidgrids=" + GRID + " +ellps=GRS80";
        Pipeline p = new PipelineFactory().create(def);
        double[] there =
                p.forward(new double[] {Math.toRadians(12.5), Math.toRadians(55.5), 17.0, 0});
        double[] back = p.inverse(there);
        assertEquals(12.5, Math.toDegrees(back[0]), 1e-9);
        assertEquals(55.5, Math.toDegrees(back[1]), 1e-9);
        assertEquals(17.0, back[2], 1e-6);
    }

    /**
     * {@code +vunits} scales the height, and {@code +vunits} <b>beats</b> {@code +vto_meter}
     * when both are given — {@code init.cpp:715-750} reads {@code +vto_meter} only in the
     * {@code else} branch, so the presence of {@code +vunits} makes it invisible.
     *
     * <p>{@code cct} with {@code +vunits=ft} gives {@code -119.403184701796}, and with
     * {@code +vunits=us-ft +vto_meter=0.3048} gives {@code -119.402945895426} — the US foot,
     * not the international one. Those two numbers differing is the whole assertion: had
     * {@code +vto_meter} won, both rows would read {@code -119.403184701796}.
     */
    @Test
    public void vunitsScalesTheHeightAndBeatsVtoMeter() {
        double ft = forwardDegrees("+proj=latlong +geoidgrids=" + GRID
                + " +ellps=GRS80 +vunits=ft", 12.5, 55.5, 0.0)[2];
        assertEquals("+vunits=ft", -119.403184701796, ft, MM);

        double vtoMeter = forwardDegrees("+proj=latlong +geoidgrids=" + GRID
                + " +ellps=GRS80 +vto_meter=0.3048", 12.5, 55.5, 0.0)[2];
        assertEquals("+vto_meter=0.3048 alone is the same factor",
                -119.403184701796, vtoMeter, MM);

        double both = forwardDegrees("+proj=latlong +geoidgrids=" + GRID
                + " +ellps=GRS80 +vunits=us-ft +vto_meter=0.3048", 12.5, 55.5, 0.0)[2];
        assertEquals("+vunits=us-ft wins over +vto_meter=0.3048",
                -119.402945895426, both, MM);
    }

    /**
     * With neither {@code +vunits} nor {@code +vto_meter}, the vertical unit <em>is</em> the
     * horizontal one: {@code vto_meter = to_meter} ({@code init.cpp:747-750}). So
     * {@code +units=ft} scales the height too, which is easy to miss and produces a height
     * wrong by 3.28&times; when missed.
     *
     * <p>{@code cct}: {@code 4565267.831088974141  24357858.250072926283
     * -119.403184701796}.
     */
    @Test
    public void withNoVerticalUnitTheHorizontalOneApplies() {
        double[] out = forwardDegrees("+proj=merc +geoidgrids=" + GRID
                + " +ellps=GRS80 +units=ft", 12.5, 55.5, 0.0);
        assertEquals("easting in feet", 4565267.831088974141, out[0], 1e-6);
        assertEquals("northing in feet", 24357858.250072926283, out[1], 1e-6);
        assertEquals("height in feet, from +units and not from any +vunits",
                -119.403184701796, out[2], MM);
    }

    /**
     * {@code +vunits} applies with no geoid grid at all — it is a unit, not a geoid feature.
     * {@code echo "12.5 55.5 100" | cct -d 12 +proj=latlong +ellps=GRS80 +vunits=ft} gives
     * {@code 328.083989501312}.
     */
    @Test
    public void vunitsAppliesWithoutAnyGeoidGrid() {
        double z = forwardDegrees("+proj=latlong +ellps=GRS80 +vunits=ft", 12.5, 55.5, 100.0)[2];
        assertEquals(328.083989501312, z, MM);
    }

    /**
     * {@code +z_0}, which is always metres and is added <em>inside</em> the vertical scale:
     * {@code z = vfr_meter * (z + z0)} ({@code fwd.cpp:145}, {@code :157}).
     *
     * <p>{@code cct} with {@code +z_0=10} gives {@code -26.394090697107}.
     */
    @Test
    public void z0IsAddedInsideTheVerticalScale() {
        double z = forwardDegrees("+proj=latlong +geoidgrids=" + GRID + " +ellps=GRS80 +z_0=10",
                12.5, 55.5, 0.0)[2];
        assertEquals(-36.394090697107 + 10.0, z, MM);
    }

    /** {@code +vunits=deg} is "Invalid value for vunits": upstream searches linear units only. */
    @Test
    public void anAngularVunitsIsRejected() {
        try {
            new PipelineFactory().create("+proj=latlong +ellps=GRS80 +vunits=deg");
            fail("+vunits=deg must be rejected: pj_list_linear_units() has no angular entries");
        } catch (PipelineDefinitionException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("+vunits=deg"));
        }
    }

    /** {@code +vto_meter=0} and a zero denominator are both errors, as for {@code +to_meter}. */
    @Test
    public void aNonPositiveVtoMeterIsRejected() {
        for (String bad : new String[] {"0", "-1", "1/0"}) {
            try {
                new PipelineFactory().create(
                        "+proj=latlong +ellps=GRS80 +vto_meter=" + bad);
                fail("+vto_meter=" + bad + " must be rejected");
            } catch (PipelineDefinitionException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("vto_meter"));
            }
        }
    }

    /**
     * An explicit {@code +proj=vgridshift} step honours {@code +multiplier}; the hidden step
     * {@code +geoidgrids} builds does not, because {@code create.cpp:96-99} composes a
     * <em>fresh</em> parameter string that inherits nothing from the enclosing operation.
     * Letting a token PROJ ignores flip the sign of every height would be worse than not
     * supporting it.
     *
     * <p>{@code cct +proj=vgridshift +grids=...} gives {@code -36.394090697107};
     * {@code +multiplier=1} gives {@code +36.394090697107}.
     */
    @Test
    public void theMultiplierIsExposedOnAnExplicitStepButNotInheritedByTheHiddenOne() {
        assertEquals("default multiplier is -1",
                -N_AT_12_5_55_5,
                forwardDegrees("+proj=vgridshift +grids=" + GRID, 12.5, 55.5, 0.0)[2], MM);

        assertEquals("+multiplier=1 is what the CRS-based path uses",
                N_AT_12_5_55_5,
                forwardDegrees("+proj=vgridshift +grids=" + GRID + " +multiplier=1",
                        12.5, 55.5, 0.0)[2], MM);

        assertEquals("a +multiplier beside +geoidgrids is NOT inherited by the hidden step",
                -N_AT_12_5_55_5,
                forwardDegrees("+proj=latlong +ellps=GRS80 +geoidgrids=" + GRID
                        + " +multiplier=1", 12.5, 55.5, 0.0)[2], MM);
    }

    /** {@code +proj=vgridshift} with no {@code +grids} is "grids parameter missing." upstream. */
    @Test
    public void vgridshiftNeedsGrids() {
        try {
            new PipelineFactory().create("+proj=vgridshift");
            fail("+proj=vgridshift with no +grids must be rejected");
        } catch (PipelineDefinitionException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("+grids"));
        }
    }

    /**
     * A missing <em>required</em> grid fails at construction, naming the file. That is the
     * point of doing it there: a grid absent from the resolver chain must not become a
     * surprise on row four million.
     */
    @Test
    public void aMissingRequiredGridFailsAtConstruction() {
        try {
            new PipelineFactory().create("+proj=latlong +ellps=GRS80 +geoidgrids=nope.gtx");
            fail("a required missing vertical grid must be refused");
        } catch (CrsTransformException expected) {
            assertEquals(ErrorCause.MISSING_GRID, expected.cause());
            assertTrue(expected.getMessage(), expected.getMessage().contains("nope.gtx"));
        }
    }

    /**
     * An all-optional list that resolves to nothing builds a working, identity-valued step —
     * {@code pj_vgridshift_forward_3d}'s {@code if (!Q-&gt;grids.empty())} guard, which is
     * reachable exactly this way.
     */
    @Test
    public void anAllOptionalMissingListIsAnIdentity() {
        double[] out = forwardDegrees("+proj=latlong +ellps=GRS80 +geoidgrids=@nope.gtx",
                12.5, 55.5, 42.0);
        assertEquals("no grid resolved, so the height passes through", 42.0, out[2], 0.0);
    }

    /**
     * A point outside every grid is an error, not a zero shift.
     *
     * <p>The fixture spans the whole world in longitude but stops at
     * &minus;89.62&deg;/+89.62&deg; in latitude, so a point beyond the last row's centre is
     * genuinely uncovered — and PROJ reports {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_GRID} for
     * it rather than returning the height unchanged.
     */
    @Test
    public void aPointOutsideEveryGridIsRefused() {
        try {
            forwardDegrees("+proj=latlong +ellps=GRS80 +geoidgrids=" + GRID, 0.0, -89.99, 0.0);
            fail("a point outside the grid must not silently return a zero shift");
        } catch (CrsTransformException expected) {
            assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, expected.cause());
        }
    }

    /** A {@code NaN} horizontal position has no height to shift, and travels as {@code NaN}. */
    @Test
    public void aNaNPositionYieldsANaNHeightRatherThanAnException() {
        double[] out = new PipelineFactory()
                .create("+proj=latlong +ellps=GRS80 +geoidgrids=" + GRID)
                .forward(new double[] {Double.NaN, Math.toRadians(55.5), 0.0, 0});
        assertTrue("read_vgrid_value refuses NaN input rather than interpolating",
                Double.isNaN(out[2]));
    }

    /** The operator is reachable directly, for a caller composing its own pipeline. */
    @Test
    public void theOperatorIsUsableOnItsOwn() {
        VGridShiftOperator op = VGridShiftOperator.fromGrids(GRID);
        assertEquals(VGridShiftOperator.DEFAULT_MULTIPLIER, op.multiplier(), 0.0);
        assertEquals(1, op.grids().size());
        assertTrue(op.hasInverse());

        double[] c = {Math.toRadians(12.5), Math.toRadians(55.5), 0.0, 0.0};
        op.forward(c);
        assertEquals(-N_AT_12_5_55_5, c[2], MM);
        op.inverse(c);
        assertEquals("forward then inverse is the identity", 0.0, c[2], 1e-12);
    }
}

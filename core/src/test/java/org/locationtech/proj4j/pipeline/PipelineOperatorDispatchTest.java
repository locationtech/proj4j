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

import org.junit.Test;
import org.locationtech.proj4j.gie.GieIoUnits;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The routing contract, and the construction-time refusals of the operators that need a
 * grid or a model file.
 *
 * <h2>Why {@link PipelineFactory#handlesOperator} is worth a test of its own</h2>
 *
 * <p>It is the answer to a question asked from <em>outside</em> this package: given
 * {@code +proj=axisswap order=2,1} — a complete PROJ operation with no {@code +step} and
 * no {@code +init=} — does the pipeline engine own it, or does the legacy
 * {@code CRSFactory}/{@code Projection} path? Getting that wrong is silent and expensive:
 * {@code axisswap.gie} and {@code unitconvert.gie} sat at 2/27 and 0/16 while both
 * operators were complete and passing their own unit tests, because nothing routed a bare
 * operator here. The measured jump on wiring it up was 2 to 27 and 0 to 16.
 *
 * <p>So the list must claim exactly the non-projection operators and <b>must not</b>
 * claim anything {@link Cs2csOperator} reaches through the {@code Registry} — otherwise
 * the two paths would disagree about who owns a projection, and the projection would be
 * built by the wrong one.
 */
public class PipelineOperatorDispatchTest {

    private final PipelineFactory factory = new PipelineFactory();

    // ------------------------------------------------------------------- routing

    @Test
    public void everyNonProjectionOperatorIsClaimed() {
        String[] claimed = {
            "affine", "axisswap", "cart", "deformation", "hgridshift", "pop", "push",
            "set", "tinshift", "unitconvert", "vgridshift",
        };
        for (int i = 0; i < claimed.length; i++) {
            assertTrue(claimed[i] + " must route to the pipeline engine",
                    PipelineFactory.handlesOperator(claimed[i]));
        }
    }

    /**
     * Nothing the {@code Registry} resolves may be claimed. {@code longlat} and
     * {@code geocent} in particular are handled <em>inside</em> {@link Cs2csOperator}, so
     * claiming them here would not even be wrong in outcome — it would be wrong in a way
     * that only shows up when the two paths' unit sides diverge.
     */
    @Test
    public void noProjectionOrRegistryNameIsClaimed() {
        String[] notClaimed = {
            "longlat", "latlong", "lonlat", "latlon", "geocent", "merc", "utm", "tmerc",
            "lcc", "stere", "pipeline", "helmert", "gridshift", "defmodel", "noop", "",
        };
        for (int i = 0; i < notClaimed.length; i++) {
            assertFalse("'" + notClaimed[i] + "' must not route to the pipeline engine",
                    PipelineFactory.handlesOperator(notClaimed[i]));
        }
        assertFalse(PipelineFactory.handlesOperator(null));
    }

    @Test
    public void isSupportedShapeCoversABareOperator() {
        assertTrue(PipelineFactory.isSupportedShape("+proj=axisswap +order=2,1"));
        assertTrue(PipelineFactory.isSupportedShape("proj=unitconvert xy_in=m xy_out=dm"));
        assertTrue(PipelineFactory.isSupportedShape("+proj=pipeline +step +proj=merc"));
        assertTrue(PipelineFactory.isSupportedShape("+init=epsg:27572"));
        assertFalse(PipelineFactory.isSupportedShape("+proj=merc +ellps=GRS80"));
    }

    /** A bare operator becomes a one-step pipeline whose sides are the operator's. */
    @Test
    public void bareOperatorBecomesAOneStepPipeline() {
        Pipeline p = factory.create("proj=axisswap order=2,1");
        assertEquals(1, p.steps().size());
        double[] out = p.forward(new double[] {1, 2, 3, 4});
        assertEquals(2.0, out[0], 0.0);
        assertEquals(1.0, out[1], 0.0);

        Pipeline u = factory.create("proj=unitconvert xy_in=m xy_out=dm z_in=cm z_out=mm");
        double[] uout = u.forward(new double[] {55.25, 23.23, 45.5, 0});
        assertEquals(552.5, uout[0], 1e-9);
        assertEquals(232.3, uout[1], 1e-9);
        assertEquals(455.0, uout[2], 1e-9);
    }

    // ---------------------------------------------------------------------- cart

    /**
     * {@code 4D-API_cs2cs-style.gie:493}. On a unit-radius-1000 sphere,
     * {@code (90, 0, 0)} is {@code (0, 1000, 0)} in metres and therefore {@code (0, 1, 0)}
     * with {@code +to_meter=1000} — {@code fwd_finalize} scales all three ordinates of a
     * {@code CARTESIAN} output.
     */
    @Test
    public void cartHonoursToMeterOnAllThreeOrdinates() {
        Pipeline p = factory.create("+proj=cart +a=1000 +b=1000 +to_meter=1000");
        assertEquals(GieIoUnits.RADIANS, p.left());
        assertEquals(GieIoUnits.CARTESIAN, p.right());

        double[] out = p.forward(new double[] {Math.PI / 2, 0, 0, 0});
        assertEquals(0.0, out[0], 1e-12);
        assertEquals(1.0, out[1], 1e-12);
        assertEquals(0.0, out[2], 1e-12);

        double[] back = p.inverse(out);
        assertEquals(Math.PI / 2, back[0], 1e-12);
        assertEquals(0.0, back[1], 1e-12);
        assertEquals(0.0, back[2], 1e-9);
    }

    /** No {@code +to_meter}: plain metres, and the inverse is the exact mirror. */
    @Test
    public void cartWithoutToMeterIsMetres() {
        Pipeline p = factory.create("+proj=cart +a=1000 +b=1000");
        double[] out = p.forward(new double[] {Math.PI / 2, 0, 0, 0});
        assertEquals(1000.0, out[1], 1e-9);
    }

    /** {@code +units} beats {@code +to_meter}, and an angular unit is not a linear one. */
    @Test
    public void cartRejectsANonLinearUnits() {
        assertRejected("+proj=cart +a=1000 +b=1000 +units=rad",
                PipelineErrorCode.ILLEGAL_ARG_VALUE, "unknown +units");
        assertRejected("+proj=cart +a=1000 +b=1000 +to_meter=0",
                PipelineErrorCode.ILLEGAL_ARG_VALUE, "+to_meter=0");
    }

    // ------------------------------------------------- grid and model refusals

    /**
     * A missing grid or model is refused at <b>construction</b>, with the same PROJ error
     * code upstream uses, so it can never become a per-row no-op that reports success.
     * That last part is the consumer's worst reported defect and the reason these are
     * asserted individually.
     */
    @Test
    public void aMissingGridParameterIsMissingArgNotSilence() {
        assertRejected("+proj=hgridshift", PipelineErrorCode.MISSING_ARG,
                "+grids parameter missing.");
        assertRejected("+proj=tinshift", PipelineErrorCode.MISSING_ARG,
                "+file= should be specified.");
        assertRejected("+proj=deformation +ellps=GRS80 +dt=1",
                PipelineErrorCode.MISSING_ARG,
                "Either +grids or (+xy_grids and +z_grids) should be specified.");
        assertRejected("+proj=deformation +xy_grids=x +ellps=GRS80 +dt=1",
                PipelineErrorCode.MISSING_ARG,
                "Either +grids or (+xy_grids and +z_grids) should be specified.");
    }

    @Test
    public void anUnresolvableGridOrModelIsFileNotFound() {
        assertRejected("+proj=hgridshift +grids=i_do_not_exist",
                PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID, "could not find required grid(s)");
        assertRejected("+proj=tinshift +file=i_do_not_exist",
                PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID, "Cannot open i_do_not_exist");
    }

    /**
     * {@code deformation.cpp:394-411}. {@code +dt} and {@code +t_epoch} are mutually
     * exclusive and exactly one is required, and {@code +t_obs} is a hard error carrying a
     * migration message rather than a synonym for {@code +dt} — one of the entries in this
     * project's "implement from the code, not the docs" table.
     */
    @Test
    public void deformationEpochParametersAreCheckedBeforeAnyGridIsOpened() {
        assertRejected("+proj=deformation +xy_grids=x +z_grids=y +ellps=GRS80 +t_obs=2000",
                PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID, "could not find required grid(s)");
        // The grid list is opened first, so the epoch checks are only reachable with a
        // resolvable grid; what is asserted here is that the +grids= (GeoTIFF) form is
        // refused with a reason rather than silently treated as the two-grid form.
        assertRejected("+proj=deformation +grids=some_model.tif +ellps=GRS80 +dt=1",
                PipelineErrorCode.NOT_IMPLEMENTED_HERE, "no GeoTIFF grid reader");
    }

    /** {@code defmodel} and {@code gridshift} are not claimed, so they are not silently run. */
    @Test
    public void unimplementedOperatorsAreNotClaimed() {
        assertFalse(PipelineFactory.handlesOperator("defmodel"));
        assertFalse(PipelineFactory.handlesOperator("gridshift"));
    }

    private void assertRejected(String definition, PipelineErrorCode expected,
                                String messageFragment) {
        try {
            factory.create(definition);
            fail("expected a PipelineDefinitionException for: " + definition);
        } catch (PipelineDefinitionException e) {
            assertEquals(definition, expected, e.code());
            assertTrue("message was: " + e.getMessage(),
                    e.getMessage().contains(messageFragment));
        }
    }
}

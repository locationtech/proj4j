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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.gie.GieIoUnits;

/**
 * {@link PipelineFactory}'s structural rules, against
 * {@code 9.8.1:src/pipeline.cpp} and {@code src/init.cpp}.
 *
 * <p>The numerical cases live in {@link PipelineGigsTest}; this class is about the
 * things that decide <em>whether</em> a pipeline exists and <em>what its steps
 * see</em>.
 */
public class PipelineFactoryTest {

    private final PipelineFactory factory = new PipelineFactory();

    private static double rad(double degrees) {
        return degrees * Math.PI / 180.0;
    }

    // -------------------------------------------------------------- rejection

    @Test
    public void aNestedPipelineIsWrongSyntax() {
        // pipeline.cpp:429-436. Nesting is legal only when the child is wrapped in
        // an +init, which is why the check counts tokens rather than recursing.
        try {
            factory.create("+proj=pipeline +step +proj=pipeline +step +proj=noop");
            fail("expected a rejection");
        } catch (PipelineDefinitionException e) {
            assertEquals(PipelineErrorCode.WRONG_SYNTAX, e.code());
            assertEquals(1025, e.code().projErrno());
            assertTrue("PROJ rejects this too", e.isRejectedByProj());
        }
    }

    @Test
    public void aPipelineWithNoStepIsWrongSyntax() {
        // more_builtins.gie:235 asserts this fails upstream.
        try {
            factory.create("+proj=pipeline");
            fail("expected a rejection");
        } catch (PipelineDefinitionException e) {
            assertEquals(PipelineErrorCode.WRONG_SYNTAX, e.code());
        }
    }

    @Test
    public void aStepBeforeTheProjPipelineTokenIsWrongSyntax() {
        try {
            factory.create("+step +proj=pipeline +step +proj=merc +ellps=GRS80");
            fail("expected a rejection");
        } catch (PipelineDefinitionException e) {
            assertEquals(PipelineErrorCode.WRONG_SYNTAX, e.code());
        }
    }

    /**
     * A bare {@code +step} with no {@code +proj=pipeline} is an <em>implicit</em>
     * pipeline and must be accepted.
     *
     * <p>It does not go through {@code pj_init}, which would report a missing
     * {@code +proj} — {@code pj_param_exists} stops at the first {@code step}. It goes
     * through {@code proj_create}'s {@code PROJStringParser}, which reads a lone
     * {@code +step} as a one-step pipeline. {@code more_builtins.gie:535} is
     * {@code operation +step +proj=latlong +ellps=WGS84} and asserts real coordinates
     * for it, so rejecting it would claim PROJ refuses an operation PROJ runs — the
     * exact confusion that makes an {@code expect failure} row meaningless.
     */
    @Test
    public void aStepWithNoProjPipelineIsAnImplicitPipeline() {
        Pipeline p = factory.create("+step +proj=latlong +ellps=WGS84");
        assertEquals(1, p.steps().size());
        assertEquals(GieIoUnits.RADIANS, p.left());
        assertEquals(GieIoUnits.RADIANS, p.right());
        double[] out = p.forward(new double[] {rad(-64.737589), rad(17.546), 0, 0});
        assertEquals(rad(-64.737589), out[0], 1e-15);
        assertEquals(rad(17.546), out[1], 1e-15);
    }

    @Test
    public void anOperatorAmongTheGlobalsIsWrongSyntax() {
        // pipeline.cpp:438-452, added against an oss-fuzz case.
        try {
            factory.create("+proj=pipeline +proj=merc +step +proj=noop");
            fail("expected a rejection");
        } catch (PipelineDefinitionException e) {
            assertEquals(PipelineErrorCode.WRONG_SYNTAX, e.code());
        }
    }

    @Test
    public void anEmptyDefinitionIsAMissingArgument() {
        try {
            factory.create("");
            fail("expected a rejection");
        } catch (PipelineDefinitionException e) {
            assertEquals(PipelineErrorCode.MISSING_ARG, e.code());
        }
    }

    /**
     * {@code get_init_string} ({@code init.cpp:134}) sets
     * {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE}, not the file code, when the section
     * is not in the init file — so this is a bad <em>parameter value</em>, and it must
     * not share a code with a grid file proj4j cannot read.
     */
    @Test
    public void anInitKeyWithNoSectionIsAnIllegalArgumentValue() {
        try {
            factory.create("+proj=pipeline +step +init=epsg:999999999");
            fail("expected a rejection");
        } catch (PipelineDefinitionException e) {
            assertEquals(PipelineErrorCode.INVALID_INIT_KEY, e.code());
            assertEquals(1027, e.code().projErrno());
        }
    }

    @Test
    public void mismatchedUnitsBetweenAdjacentStepsAreWrongSyntax() {
        // pipeline.cpp:620-636. A projected right-hand side cannot feed an angular
        // left-hand side. Here: merc emits PROJECTED, and the following merc wants
        // RADIANS.
        try {
            factory.create("+proj=pipeline"
                    + " +step +proj=merc +ellps=GRS80"
                    + " +step +proj=merc +ellps=GRS80");
            fail("expected a rejection");
        } catch (PipelineDefinitionException e) {
            assertEquals(PipelineErrorCode.WRONG_SYNTAX, e.code());
            assertTrue(e.getMessage().contains("mismatched units"));
        }
    }

    /**
     * The inverse of what this test used to assert. {@code +datum=NAD27} expands to a
     * {@code +nadgrids=} list, which {@code create.cpp:107-124} turns into a hidden
     * {@code +proj=hgridshift}; until that helper existed the only honest thing to do
     * was refuse, because reading the datum's Helmert parameters instead would have
     * dropped the grid and emitted a coordinate wrong by the size of the shift.
     * {@code Cs2csOperator} now builds it, so the definition must <em>resolve</em>, and
     * resolve to something that really carries the shift.
     *
     * <p>Deterministic, and deliberately <em>not</em> conditioned on
     * {@code Datum.NAD27.getTransformType()}: that value depends on whether the grid
     * resolver was configured before {@code Datum}'s static initialiser ran, which made
     * {@code DHDN_ETRS89.gie} score 64/64 or 32/32 depending on test order. The
     * definition alone decides, per {@code 9.8.1:src/datums.cpp}.
     */
    @Test
    public void aGridShiftDatumNowBuildsTheHiddenHgridshift() {
        Cs2csOperator op = cs2cs(factory.create("+proj=pipeline +step +proj=longlat +datum=NAD27"));
        assertNotNull("the datum's +nadgrids= list must become the hidden helper",
                op.hgridshift());
        assertEquals("@conus,@alaska,@ntv2_0.gsb,@ntv1_can.dat", op.hgridshift().gridSpec());
    }

    /** The single step's operator, which every test below needs to look inside. */
    private static Cs2csOperator cs2cs(Pipeline p) {
        assertEquals(1, p.steps().size());
        PipelineOperator op = p.steps().get(0).operator();
        assertTrue("expected a Cs2csOperator, got " + op.getClass().getName(),
                op instanceof Cs2csOperator);
        return (Cs2csOperator) op;
    }

    /**
     * {@code pj_datum_set} tests {@code nadgrids} before {@code towgs84} in an
     * {@code if}/{@code else if}, and the datum's own definition has been appended to
     * the paralist by then — so a grid definition reached through {@code +datum=} beats
     * an explicit {@code +towgs84} the user wrote, which is the one place in this engine
     * where first-match-wins does not decide.
     */
    @Test
    public void aDatumGridOutranksAnExplicitTowgs84() {
        Cs2csOperator op = cs2cs(factory.create(
                "+proj=pipeline +step +proj=longlat +datum=NAD27 +towgs84=1,2,3"));
        assertNotNull("the grid must win over the Helmert", op.hgridshift());
        assertFalse("no Helmert may be built alongside it: " + op,
                op.toString().contains("helmert"));
    }

    /**
     * {@code +nadgrids=@null} — what {@code +init=epsg:3857} expands to. {@code null} is
     * a real grid name upstream and shifts nothing, so the step must build, be visible,
     * and be the identity rather than being refused.
     */
    @Test
    public void theNullGridBuildsAndShiftsNothing() {
        Pipeline p = factory.create("+proj=pipeline +step +proj=longlat +nadgrids=@null");
        assertNotNull(cs2cs(p).hgridshift());
        double[] out = p.forward(new double[] {0.1, 0.9, 0.0, 0.0});
        assertEquals(0.1, out[0], 0.0);
        assertEquals(0.9, out[1], 0.0);
    }

    /**
     * A <em>required</em> grid that cannot be found is fatal at construction, never a
     * per-row no-op that reports success.
     *
     * <p>Which layer refuses is recorded rather than asserted narrowly:
     * {@code Proj4Parser.parseDatum} resolves {@code +nadgrids} for its own
     * {@code Datum} and gets there first, throwing {@code InvalidValueException}
     * ("Unknown nadgrid"), so {@code HGridShiftOperator} is never reached. Both are a
     * {@code Proj4jException} naming the grid, which is the property that matters, and
     * the bridge maps the parser's message to {@code MISSING_GRID}. If the parser ever
     * stops resolving the key, this becomes
     * {@code PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID} from
     * {@link HorizontalGrids#open} and the assertion still holds.
     */
    @Test
    public void aRequiredMissingNadgridIsFatal() {
        try {
            factory.create("+proj=pipeline +step +proj=longlat +nadgrids=nosuchgrid.gsb");
            fail("expected a rejection");
        } catch (Proj4jException e) {
            assertTrue("the message must name the grid: " + e.getMessage(),
                    e.getMessage().contains("nosuchgrid.gsb"));
        }
    }

    // ------------------------------------------------------ per-step scoping

    /**
     * The parameter-scoping rule, observed end to end rather than on
     * {@link ProjParams} alone: two steps each name their own {@code +lon_0}, and
     * neither leaks into the other.
     */
    @Test
    public void eachStepSeesOnlyItsOwnParameters() {
        Pipeline p = factory.create("+proj=pipeline"
                + " +step +proj=merc +lon_0=10 +ellps=GRS80 +inv"
                + " +step +proj=merc +lon_0=20 +ellps=GRS80");
        // Forward: undo a lon_0=10 mercator, then apply a lon_0=20 one. A point at
        // the first central meridian (x = 0) must land 10 degrees west of the second.
        double[] out = p.forward(new double[] {0, 0, 0, 0});
        double[] expected = factory
                .create("+proj=pipeline +step +proj=merc +lon_0=20 +ellps=GRS80")
                .forward(new double[] {rad(10), 0, 0, 0});
        assertEquals(expected[0], out[0], 1e-6);
        assertTrue("a 10 degree offset, not zero", Math.abs(out[0]) > 1.0e6);
    }

    /**
     * A global token is <b>appended</b> to every step's list
     * ({@code pipeline.cpp:487-488}), so it is the lowest-precedence source: a step
     * that names the same key wins.
     */
    @Test
    public void aGlobalIsInheritedByEveryStepButShadowedByAStepToken() {
        Pipeline inherited = factory.create("+proj=pipeline +ellps=clrk66"
                + " +step +proj=merc +inv"
                + " +step +proj=merc");
        Pipeline shadowed = factory.create("+proj=pipeline +ellps=clrk66"
                + " +step +proj=merc +inv"
                + " +step +proj=merc +ellps=GRS80");
        double[] a = inherited.forward(new double[] {1000000, 0, 0, 0});
        double[] b = shadowed.forward(new double[] {1000000, 0, 0, 0});
        assertEquals("clrk66 on both sides is the identity in x", 1000000.0, a[0], 1e-6);
        // A mercator easting on the equator is a*k0*lambda, so undoing a clrk66 one
        // and reapplying a GRS80 one rescales by 6378137/6378206.4 - about 11 m in a
        // million, which is small but unmistakably not the identity.
        assertEquals(1000000.0 * 6378137.0 / 6378206.4, b[0], 1e-3);
        assertTrue("a GRS80 second step is not the identity", Math.abs(b[0] - 1000000.0) > 5.0);
    }

    /**
     * The {@code proj=pipeline} token itself is never inherited: upstream starts
     * copying the globals at {@code i_pipeline + 1}. If it were inherited, every
     * step would look like a nested pipeline.
     */
    @Test
    public void theProjPipelineTokenIsNotInheritedByItsSteps() {
        Pipeline p = factory.create("+proj=pipeline +step +proj=merc +ellps=GRS80");
        assertEquals(1, p.steps().size());
    }

    // --------------------------------------------------------------- +inv

    @Test
    public void invOnAStepRunsTheOppositeDirectionAndSwapsItsUnitSides() {
        Pipeline plain = factory.create("+proj=pipeline +step +proj=merc +ellps=GRS80");
        Pipeline inverted = factory.create("+proj=pipeline +step +proj=merc +ellps=GRS80 +inv");

        assertEquals(GieIoUnits.RADIANS, plain.left());
        assertEquals(GieIoUnits.PROJECTED, plain.right());
        assertEquals("+inv exchanges the two sides", GieIoUnits.PROJECTED, inverted.left());
        assertEquals(GieIoUnits.RADIANS, inverted.right());

        double[] projected = plain.forward(new double[] {rad(10), rad(45), 0, 0});
        double[] back = inverted.forward(projected);
        assertEquals(rad(10), back[0], 1e-12);
        assertEquals(rad(45), back[1], 1e-9);
    }

    /**
     * {@code pipeline.cpp:519-522} <em>toggles</em> per {@code inv} token over the
     * step's combined argument list, which is how a global {@code +inv} is
     * overridden per step. Two occurrences therefore cancel.
     */
    @Test
    public void invTogglesSoAGlobalAndAStepOccurrenceCancel() {
        Pipeline p = factory.create("+proj=pipeline +inv +step +proj=merc +ellps=GRS80 +inv");
        assertFalse(p.steps().get(0).isInverted());
        assertEquals(GieIoUnits.RADIANS, p.left());
    }

    @Test
    public void aGlobalInvAloneInvertsEveryStep() {
        Pipeline p = factory.create("+proj=pipeline +inv +step +proj=merc +ellps=GRS80");
        assertTrue(p.steps().get(0).isInverted());
        assertEquals(GieIoUnits.PROJECTED, p.left());
    }

    @Test
    public void invEqualsTIsNotAnInvToken() {
        // The match is strcmp("inv", token), so a valued token does not count.
        Pipeline p = factory.create("+proj=pipeline +step +proj=merc +ellps=GRS80 +inv=T");
        assertFalse(p.steps().get(0).isInverted());
    }

    // ---------------------------------------------------------------- units

    /**
     * {@code pipeline.cpp:631-636}: {@code P->left} is {@code pj_left} of the
     * <em>first</em> step and {@code P->right} is {@code pj_right} of the
     * <em>last</em>, both folded and both honouring {@code +inv}.
     */
    @Test
    public void theIoUnitsComeFromTheFirstAndLastSteps() {
        Pipeline p = factory.create("+proj=pipeline"
                + " +step +proj=merc +ellps=GRS80 +inv"
                + " +step +proj=utm +zone=32 +ellps=GRS80");
        assertEquals(GieIoUnits.PROJECTED, p.left());
        assertEquals(GieIoUnits.PROJECTED, p.right());
        assertEquals("CLASSIC is folded away, never reported", GieIoUnits.PROJECTED,
                p.right().folded());
    }

    @Test
    public void aWhateverOnlyStepAdoptsItsNeighboursUnits() {
        // pipeline.cpp:583-618. axisswap declares WHATEVER on both sides; the merc
        // to its right declares RADIANS on its left, so the axisswap becomes
        // RADIANS/RADIANS and the pipeline reports RADIANS on its left.
        Pipeline p = factory.create("+proj=pipeline"
                + " +step +proj=axisswap +order=2,1"
                + " +step +proj=merc +ellps=GRS80");
        assertEquals(GieIoUnits.RADIANS, p.left());
        assertEquals(GieIoUnits.PROJECTED, p.right());
    }

    @Test
    public void aUnitconvertWithOneKnownSideIsNotOverwritten() {
        // The both-sides-WHATEVER precondition is what protects upstream's named
        // case: a leading deg->rad unitconvert must keep its non-radian left side.
        Pipeline p = factory.create("+proj=pipeline"
                + " +step +proj=unitconvert +xy_in=deg +xy_out=rad"
                + " +step +proj=merc +ellps=GRS80");
        assertEquals(GieIoUnits.DEGREES, p.left());
        assertEquals(GieIoUnits.PROJECTED, p.right());
    }

    // ------------------------------------------------------------- ellipsoid

    /**
     * A pipeline with no global {@code +ellps} defaults to GRS80
     * ({@code pipeline.cpp:338-340}) where a bare operation defaults to WGS84
     * ({@code init.cpp:576-581}). The value feeds only the geodesic a conformance
     * comparator measures with, but confusing the two defaults is how a downstream
     * comparator ends up measuring on the wrong ellipsoid.
     */
    @Test
    public void aPipelineDefaultsToGrs80AndASingleOperationToWgs84() {
        Pipeline pipeline = factory.create("+proj=pipeline +step +proj=merc +ellps=clrk66");
        assertEquals(Pipeline.GRS80_A, pipeline.globalEllipsoidA(), 0.0);
        assertEquals(Pipeline.GRS80_F, pipeline.globalEllipsoidF(), 0.0);

        Pipeline single = factory.create("+proj=merc +ellps=clrk66");
        assertEquals(6378137.0, single.globalEllipsoidA(), 0.0);
        assertEquals(1 / 298.257223563, single.globalEllipsoidF(), 1e-15);
    }

    @Test
    public void aGlobalEllipsoidIsUsedForTheGeodesicButAStepsIsNot() {
        Pipeline global = factory.create("+proj=pipeline +ellps=clrk66 +step +proj=merc");
        assertEquals(6378206.4, global.globalEllipsoidA(), 1e-6);

        Pipeline stepOnly = factory.create("+proj=pipeline +step +proj=merc +ellps=clrk66");
        assertEquals("set_ellipsoid cuts the list at the first +step",
                Pipeline.GRS80_A, stepOnly.globalEllipsoidA(), 0.0);
    }

    // ------------------------------------------------------ shape predicate

    @Test
    public void isSupportedShapeRecognisesPipelinesAndLegacyInits() {
        assertTrue(PipelineFactory.isSupportedShape("+proj=pipeline +step +proj=noop"));
        assertTrue(PipelineFactory.isSupportedShape("+step +proj=noop"));
        assertTrue(PipelineFactory.isSupportedShape("+init=epsg:27572"));
        assertFalse(PipelineFactory.isSupportedShape("+proj=merc +ellps=GRS80"));
    }

    @Test
    public void moreThanOneInitInASingleOperationIsWrongSyntax() {
        // init.cpp:477-482. Legal in a pipeline, illegal outside one.
        try {
            factory.create("+init=epsg:4326 +init=epsg:4258");
            fail("expected a rejection");
        } catch (PipelineDefinitionException e) {
            assertEquals(PipelineErrorCode.WRONG_SYNTAX, e.code());
        }
    }
}

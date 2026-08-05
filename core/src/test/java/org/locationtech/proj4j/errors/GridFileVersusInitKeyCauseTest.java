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
package org.locationtech.proj4j.errors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.pipeline.PipelineDefinitionException;
import org.locationtech.proj4j.pipeline.PipelineErrorCode;
import org.locationtech.proj4j.pipeline.PipelineFactory;

/**
 * Two unrelated situations that once shared one {@link PipelineErrorCode}, and therefore
 * one wrong {@link ErrorCause}.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID} carried
 * {@link ErrorCause#INVALID_PARAM_VALUE} — a {@code Group.CRS} cause meaning <em>the
 * value you wrote for this parameter is wrong</em> — into every
 * {@link PipelineDefinitionException}, including the ones raised because a grid or model
 * <em>file</em> could not be read. A consumer that caught an unreadable grid was told its
 * CRS parameter value was invalid, and on a 13-row failure set every row reported the
 * wrong reason. {@link ErrorCause#MISSING_GRID} ({@code Group.OPERATION}) exists for
 * exactly this and says so in its own javadoc.
 *
 * <h2>Both directions, because one of them is the control</h2>
 *
 * <p>The grid tests below only show that a constant can be changed. The {@code +init=}
 * tests are what makes them a measurement: an {@code +init=} key with no matching section
 * is <em>genuinely</em> a bad parameter value — {@code get_init_string}
 * ({@code 9.8.1:src/init.cpp:105,119,134}) sets
 * {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE} for all three of its failure modes — so
 * that population must keep {@link ErrorCause#INVALID_PARAM_VALUE} and
 * {@code Group.CRS}. If the split were made backwards, or collapsed back into one
 * constant, the two directions cannot both pass.
 *
 * <p>{@link #theCheckerItselfDetectsASwappedMapping()} is the positive control required by
 * the project's non-negotiable 5c: it feeds each checker a fabricated exception carrying
 * the <em>other</em> population's code and requires the checker to reject it. Without it,
 * a checker that had quietly stopped asserting anything would report the same clean pass.
 */
public class GridFileVersusInitKeyCauseTest {

    private final PipelineFactory factory = new PipelineFactory();

    /**
     * Definitions whose failure is "a file this operation needs could not be read".
     * Each entry is {definition, a fragment the message must contain}. The fragment is
     * what proves the probe reached the intended site rather than tripping over an
     * earlier {@code MISSING_ARG} or a routing gap.
     */
    private static final String[][] UNREADABLE_FILE_PROBES = {
            // HorizontalGrids.open - the +grids= list resolves to nothing.
            {"+proj=hgridshift +grids=i_do_not_exist", "could not find required grid(s)"},
            // TinShiftOperator.read - the resolution chain has no such resource.
            {"+proj=tinshift +file=i_do_not_exist", "Cannot open i_do_not_exist"},
            // PipelineJson.invalid - INDEX *does* resolve on the classpath (it is the grid
            // pack manifest) and is emphatically not a triangulation model. "Present but
            // unusable" is the other half of PROJ's own FILE_NOT_FOUND_OR_INVALID.
            {"+proj=tinshift +file=INDEX", "invalid model"},
            // DeformationOperator - the horizontal list is opened first, so this is
            // HorizontalGrids again by way of +xy_grids.
            {"+proj=deformation +xy_grids=i_do_not_exist +z_grids=also_not +ellps=GRS80 +dt=1",
                    "could not find required grid(s)"},
            // DeformationOperator.openVerticalGrids - only reachable behind a +xy_grids
            // that does resolve, which is why it needs a probe of its own rather than
            // being assumed covered by the line above.
            {"+proj=deformation +xy_grids=ntv2_0_downsampled.gsb +z_grids=i_do_not_exist "
                    + "+ellps=GRS80 +dt=1", "could not find requested z_grid(s)"},
    };

    /**
     * Definitions whose failure is "the {@code +init=} key you wrote is not a usable
     * key". All three of {@code init.cpp}'s modes: no colon, no such init file, no such
     * section in a file that does exist.
     */
    private static final String[][] BAD_INIT_KEY_PROBES = {
            {"+proj=pipeline +step +init=nocolonhere", "not of the form"},
            {"+proj=pipeline +step +init=no_such_init_file:some_section", "no init file"},
            {"+proj=pipeline +step +init=epsg:999999999", "no section"},
    };

    // ------------------------------------------------- direction 1: unreadable files

    @Test
    public void anUnreadableGridOrModelFileSurfacesMissingGrid() {
        for (int i = 0; i < UNREADABLE_FILE_PROBES.length; i++) {
            String definition = UNREADABLE_FILE_PROBES[i][0];
            checkUnreadableFile(definition, capture(definition),
                    UNREADABLE_FILE_PROBES[i][1]);
        }
    }

    /**
     * The one bit the conformance bridge reads. proj4j failing to read a file is a fact
     * about proj4j's readers; claiming PROJ would have refused the same definition turns
     * a capability gap into apparent conformance.
     */
    @Test
    public void anUnreadableFileIsNotClaimedAsAnUpstreamRejection() {
        for (int i = 0; i < UNREADABLE_FILE_PROBES.length; i++) {
            String definition = UNREADABLE_FILE_PROBES[i][0];
            PipelineDefinitionException e = capture(definition);
            assertFalse(definition + ": proj4j not being able to read a file is not "
                            + "evidence that PROJ 9.8.1 would reject the definition",
                    e.isRejectedByProj());
        }
    }

    // ------------------------------------- direction 2: the control, a bad +init= key

    @Test
    public void anInitKeyWithNoSectionStillSurfacesInvalidParamValue() {
        for (int i = 0; i < BAD_INIT_KEY_PROBES.length; i++) {
            String definition = BAD_INIT_KEY_PROBES[i][0];
            checkBadInitKey(definition, capture(definition), BAD_INIT_KEY_PROBES[i][1]);
        }
    }

    /** PROJ really does refuse these, so here the claim of upstream agreement is honest. */
    @Test
    public void aBadInitKeyIsClaimedAsAnUpstreamRejection() {
        for (int i = 0; i < BAD_INIT_KEY_PROBES.length; i++) {
            String definition = BAD_INIT_KEY_PROBES[i][0];
            assertTrue(definition + ": init.cpp:105,119,134 all set an errno",
                    capture(definition).isRejectedByProj());
        }
    }

    // --------------------------------------------------------------- the separation

    /**
     * The two situations must not share a code, a cause, a group, or the
     * rejected-by-PROJ bit. Collapsing them back into one constant is the defect, in
     * whichever direction it is done.
     */
    @Test
    public void theTwoPopulationsAreSeparatedOnEveryAxisThatMatters() {
        PipelineErrorCode file = PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID;
        PipelineErrorCode init = PipelineErrorCode.INVALID_INIT_KEY;

        assertNotEquals("one constant serving both situations is the defect", file, init);
        assertEquals(ErrorCause.MISSING_GRID, file.errorCause());
        assertEquals(ErrorCause.INVALID_PARAM_VALUE, init.errorCause());
        assertTrue("MISSING_GRID is an operation-planning fact",
                file.errorCause().isOperationError());
        assertTrue("INVALID_PARAM_VALUE is a CRS-definition fact",
                init.errorCause().isCrsError());
        assertFalse(file.isRejectedByProj());
        assertTrue(init.isRejectedByProj());

        // The errnos are upstream's, not ours: 1029 for a grid/model file
        // (grids.cpp, tinshift.cpp:92-127, deformation.cpp:377-391); 1027 for an +init=
        // key (init.cpp:105,119,134).
        assertEquals(1029, file.projErrno());
        assertEquals(1027, init.projErrno());
    }

    // --------------------------------------------------------- the positive control

    /**
     * Prove the checkers can fail before believing that they passed. Each is handed a
     * fabricated {@link PipelineDefinitionException} carrying the other population's code
     * — exactly what a swapped or collapsed mapping would produce at the real call sites
     * — and must reject it.
     */
    @Test
    public void theCheckerItselfDetectsASwappedMapping() {
        PipelineDefinitionException asInit = new PipelineDefinitionException(
                PipelineErrorCode.INVALID_INIT_KEY, "fabricated: could not find required grid(s)");
        PipelineDefinitionException asFile = new PipelineDefinitionException(
                PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID, "fabricated: no section <x>");

        assertRejects("the unreadable-file checker accepted an INVALID_PARAM_VALUE/Group.CRS "
                + "exception, so its clean result on the real engine means nothing",
                new Check() {
                    @Override
                    public void run(PipelineDefinitionException e) {
                        checkUnreadableFile("fabricated", e, "could not find required grid(s)");
                    }
                }, asInit);

        assertRejects("the bad-init-key checker accepted a MISSING_GRID/Group.OPERATION "
                + "exception, so its clean result on the real engine means nothing",
                new Check() {
                    @Override
                    public void run(PipelineDefinitionException e) {
                        checkBadInitKey("fabricated", e, "no section <x>");
                    }
                }, asFile);
    }

    // ------------------------------------------------------------------- machinery

    private interface Check {
        void run(PipelineDefinitionException e);
    }

    private static void assertRejects(String why, Check check, PipelineDefinitionException e) {
        try {
            check.run(e);
        } catch (AssertionError expected) {
            return;
        }
        fail(why);
    }

    /** Everything a consumer sees, asserted in one place so the control can re-use it. */
    private static void checkUnreadableFile(String definition, PipelineDefinitionException e,
                                            String messageFragment) {
        assertTrue(definition + ": the message must name the file - was: " + e.getMessage(),
                e.getMessage().contains(messageFragment));
        assertEquals(definition, PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID, e.code());
        assertEquals(definition + ": an unreadable grid is not a bad CRS parameter value",
                ErrorCause.MISSING_GRID, e.cause());
        assertTrue(definition + ": MISSING_GRID is Group.OPERATION", e.cause().isOperationError());
        assertFalse(definition + ": and therefore not Group.CRS", e.cause().isCrsError());
        assertEquals(definition, "crs.missing_grid", e.cause().metricKey());
    }

    private static void checkBadInitKey(String definition, PipelineDefinitionException e,
                                        String messageFragment) {
        assertTrue(definition + ": the message must name the key - was: " + e.getMessage(),
                e.getMessage().contains(messageFragment));
        assertEquals(definition, PipelineErrorCode.INVALID_INIT_KEY, e.code());
        assertEquals(definition + ": an unusable +init= key really is a bad parameter value",
                ErrorCause.INVALID_PARAM_VALUE, e.cause());
        assertTrue(definition + ": INVALID_PARAM_VALUE is Group.CRS", e.cause().isCrsError());
        assertFalse(definition + ": and therefore not Group.OPERATION",
                e.cause().isOperationError());
        assertEquals(definition, "crs.invalid_param_value", e.cause().metricKey());
    }

    /**
     * @return the rejection the engine raised; fails if it did not raise one, or raised
     *         something a consumer could not classify
     */
    private PipelineDefinitionException capture(String definition) {
        try {
            factory.create(definition);
        } catch (PipelineDefinitionException e) {
            return e;
        } catch (Proj4jException e) {
            throw new AssertionError(definition + ": expected a classifiable "
                    + "PipelineDefinitionException, got " + e.getClass().getName() + ": "
                    + e.getMessage());
        }
        throw new AssertionError(definition + ": expected a rejection, the engine accepted it - "
                + "this probe no longer measures anything");
    }
}

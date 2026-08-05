/*
 * Copyright 2026, PROJ4J contributors
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
package org.locationtech.proj4j.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;
import org.locationtech.proj4j.Proj4jException;

/**
 * {@link Crs#describe()} on a CRS with an unreachable grid must <b>say so</b>.
 *
 * <p>This is the test for the failure a coordinate cannot reveal. {@code EPSG:4267} declares four
 * grid files and a stock deployment has one of them; the other three vanish under PROJ's {@code @}
 * semantics, the datum shift is not applied, and the coordinate that comes out is finite, plausible
 * and 95.573&nbsp;m wrong at San Francisco. No downstream range check, no {@code isFinite}, no
 * schema validation will catch that. The only remedy is that this text exists and names the files.
 */
public class CrsDescribeTest {

    /**
     * The guaranteed case: a grid name that cannot possibly resolve, so the assertion is about the
     * text and not about the classpath.
     */
    @Test
    public void describeOnACrsWithAnUnreachableGridSaysSo() {
        Crs crs = Proj.createCrs("+proj=longlat +ellps=clrk66 +nadgrids=@no_such_grid_9f3a.gsb");
        String d = crs.describe();

        assertTrue("must count reachable against unreachable: " + d, d.contains("UNREACHABLE"));
        assertTrue("must name the unreachable file: " + d, d.contains("no_such_grid_9f3a.gsb"));
        assertTrue("must state the consequence in words, not leave it to be inferred: " + d,
                d.contains("wrong by the size of the missing shift"));
        assertTrue("must say the legacy API is deliberately unchanged: " + d,
                d.contains("skips the grid silently"));
        assertTrue("must state that the working directory is not searched, since that is the other "
                        + "way a grid can differ per executor: " + d,
                d.contains("working directory is deliberately not searched"));
        assertTrue("must say the @ prefix is why this can be silent elsewhere: " + d,
                d.contains("@ prefix"));
    }

    /**
     * The realistic case, on a real datum. Which of {@code +datum=NAD27}'s four declared grids are
     * present depends on the classpath -- which is the entire reason this API exists -- so the
     * assertions are over whatever is missing rather than over a named file, and the test insists
     * that something is, so that a run where everything resolved cannot pass vacuously.
     */
    @Test
    public void describeOnNad27ReportsEachDeclaredGridsReachability() {
        Crs nad27 = Proj.createCrs("EPSG:4267");
        assertEquals("PROJ's datum table declares four grids for NAD27", 4, nad27.grids().size());
        assertEquals(4, nad27.requiredGrids().size());
        assertTrue("the @ must round-trip in requiredGrids(): " + nad27.requiredGrids(),
                nad27.requiredGrids().contains("@conus"));

        List<GridInfo> missing = nad27.missingGrids();
        assertFalse("no US or Canadian grid pack can be complete on this classpath, so at least one "
                + "of NAD27's four grids must be reported unreachable: " + nad27.describe(),
                missing.isEmpty());
        assertTrue(nad27.hasUnreachableGrids());

        String d = nad27.describe();
        for (GridInfo g : missing) {
            assertFalse(g.isAvailable());
            assertTrue(g.isDeclared());
            assertTrue("PROJ declares all four with @, and that must be recorded", g.isOptional());
            assertTrue(g.skipReason().isPresent());
            assertFalse(g.resolverName().isPresent());
            assertFalse(g.origin().isPresent());
            assertTrue("describe() must name every unreachable grid: " + g.name() + " in " + d,
                    d.contains(g.name()));
        }
        assertTrue(d, d.contains("UNREACHABLE"));
        assertTrue(d, d.contains("declared by +datum=NAD27"));
    }

    /** A CRS that declares nothing says exactly that, rather than saying nothing. */
    @Test
    public void describeOnACrsWithNoGridsSaysNoneDeclared() {
        String d = Proj.createCrs("EPSG:4326").describe();
        assertTrue(d, d.contains("grids           = none declared"));
        assertTrue(Proj.createCrs("EPSG:4326").missingGrids().isEmpty());
        assertFalse(Proj.createCrs("EPSG:4326").hasUnreachableGrids());
    }

    /** An explicit {@code +nadgrids=} is reported as the declaring parameter, verbatim. */
    @Test
    public void anExplicitNadgridsParameterIsAttributedToItself() {
        Crs crs = Proj.createCrs("+proj=longlat +ellps=clrk66 +nadgrids=@nosuchgrid.gsb");
        List<GridInfo> missing = crs.missingGrids();
        assertEquals(1, missing.size());
        assertEquals("nosuchgrid.gsb", missing.get(0).name());
        assertTrue(missing.get(0).declaredBy().get(),
                missing.get(0).declaredBy().get().contains("+nadgrids=@nosuchgrid.gsb"));
        assertTrue("an unknown grid has no PROJ 7+ name to offer",
                !missing.get(0).modernName().isPresent());
    }

    /**
     * A required (non-{@code @}) grid is a different fact from an optional one, and the difference
     * shows up before any coordinate does: a required grid that is absent makes the CRS
     * unconstructible, which is the correct outcome and the reason the {@code @} case needs all this
     * reporting instead.
     */
    @Test
    public void aRequiredGridIsDistinguishedFromAnOptionalOne() {
        Crs optional = Proj.createCrs("+proj=longlat +ellps=clrk66 +nadgrids=@nosuchgrid.gsb");
        GridInfo skipped = optional.missingGrids().get(0);
        assertTrue(skipped.isOptional());
        assertTrue("the describe text must say the @ is what makes this survivable: "
                + skipped.describe(), skipped.describe().contains("@ prefix"));

        try {
            Proj.createCrs("+proj=longlat +ellps=clrk66 +nadgrids=nosuchgrid.gsb");
            fail("a REQUIRED grid that cannot be found must not yield a usable CRS");
        } catch (Proj4jException expected) {
            assertTrue("the failure must be attributed, not bare: " + expected.cause(),
                    expected.cause() != null);
        }
    }

    /** {@link CrsOperation#describe()} rolls up both CRSs plus the operation's own findings. */
    @Test
    public void operationDescribeIncludesBothCrsDescriptions() {
        ProjContext allow = ProjContext.builder().ballparkPolicy(BallparkPolicy.ALLOW).build();
        CrsOperation op = Proj.createCrsToCrs("EPSG:4267", "EPSG:4269", allow);
        String d = op.describe();

        assertTrue(d, d.contains("CrsOperation EPSG:4267 -> EPSG:4269"));
        assertTrue(d, d.contains("ballpark        = true"));
        assertTrue("accuracy must be refused explicitly, not silently omitted: " + d,
                d.contains("accuracy        = <empty>"));
        assertTrue(d, d.contains("missing grids   = " + op.missingGrids().size()));
        assertTrue("must roll up the source CRS: " + d, d.contains("CRS EPSG:4267"));
        assertTrue("must roll up the target CRS: " + d, d.contains("CRS EPSG:4269"));
        assertTrue("must state the thread-safety contract, since a shared operation is the whole "
                + "point: " + d, d.contains("immutable and shareable"));
    }

    /** Area of use is refused in words rather than being quietly absent. */
    @Test
    public void areaOfUseIsRefusedExplicitly() {
        Crs crs = Proj.createCrs("EPSG:4326");
        assertFalse(crs.areaOfUse().isPresent());
        assertTrue(crs.describe(), crs.describe().contains("it is not guessed"));
    }
}

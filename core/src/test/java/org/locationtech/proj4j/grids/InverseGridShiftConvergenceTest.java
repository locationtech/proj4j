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
package org.locationtech.proj4j.grids;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.After;
import org.junit.Test;
import org.locationtech.proj4j.ConvergenceFailureException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.datum.Grid;
import org.locationtech.proj4j.datum.GridCache;
import org.locationtech.proj4j.resource.DirectoryResourceResolver;
import org.locationtech.proj4j.resource.ResourceResolvers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The inverse grid shift's iterative solver: fail closed, and use PROJ's convergence test.
 *
 * <h2>Two defects, one fixture</h2>
 *
 * <p>{@code Grid.nad_cvt}'s inverse branch is the only data-dependent loop on the grid path. In 1.4.3 it
 * ended with:
 * <pre>
 *   } while (i-- &gt; 0 &amp;&amp; Math.abs(dif.lam) &gt; TOL &amp;&amp; Math.abs(dif.phi) &gt; TOL);
 *   if (i &lt; 0) { t.lam = t.phi = Double.NaN; return t; }
 * </pre>
 *
 * <ol>
 *   <li><strong>The convergence test was an {@code &amp;&amp;} over the two components separately</strong>,
 *       so the loop stopped as soon as <em>either</em> ordinate's residual fell inside tolerance — with
 *       the other one still arbitrarily far out. PROJ 9.8.1 tests the squared 2-norm,
 *       {@code dif.lam * dif.lam + dif.phi * dif.phi &gt; toltol} ({@code src/grids.cpp},
 *       {@code pj_hgrid_apply_internal}), which requires both.</li>
 *   <li><strong>Exhausting the iterations produced {@code NaN}</strong>, which {@code Grid.shift} then
 *       could not distinguish from "no table covered this point" and turned into <em>the input
 *       coordinate, unchanged</em>. A numerical failure delivered as a plausible answer — the exact shape
 *       this project forbids.</li>
 * </ol>
 *
 * <p>Both are now closed: the residual test is PROJ's, and a non-convergent iteration throws
 * {@link ConvergenceFailureException} with {@link ErrorCause#NUMERICAL_FAILURE}.
 *
 * <h2>The fixture, and why it is built rather than found</h2>
 *
 * <p>Real published grids are smooth: their shift gradient is far below one cell per cell, so the
 * fixed-point iteration is a strong contraction and converges in two or three passes. Non-convergence is
 * therefore unreachable with {@code conus} or {@code ntv1_can.dat}, and a test that only used real data
 * would be asserting nothing.
 *
 * <p>This fixture is a valid 21&times;21 CTABLE V2 grid whose longitude shift is a triangle wave of period
 * two cells and amplitude exactly one cell: node values alternate {@code +1&deg;, -1&deg;} along each row,
 * so the interpolated gradient is &plusmn;2 cells per cell. The fixed-point map is then an expansion
 * (|derivative| = 2 &gt; 1) and cannot converge, while the amplitude bound keeps every iterate inside the
 * grid, so it is genuine non-convergence rather than the grid-edge case PROJ deliberately tolerates.
 *
 * <p>The latitude shift is identically zero, which makes the fixture diagnose defect (1) as sharply as
 * defect (2): {@code dif.phi} is exactly {@code 0.0} on the first pass, so the old {@code &amp;&amp;} test
 * declared success immediately and returned the <em>first</em> iterate — with the longitude residual still
 * 0.4 of a cell, i.e. about 44 km at this spacing.
 */
public class InverseGridShiftConvergenceTest {

    private static final Charset ASCII = Charset.forName("US-ASCII");

    private static final int COLS = 21;
    private static final int ROWS = 21;
    private static final double DEG = Math.PI / 180.0;

    private Path root;

    @After
    public void cleanUp() throws IOException {
        ResourceResolvers.clearResolvers();
        GridCache.instance().clear();
        if (root != null) {
            Files.deleteIfExists(root.resolve("sawtooth"));
            Files.deleteIfExists(root);
            root = null;
        }
    }

    private List<Grid> sawtoothGrid() throws IOException {
        root = Files.createTempDirectory("proj4j-sawtooth");
        Files.write(root.resolve("sawtooth"), sawtoothCtable2());
        ResourceResolvers.addResolver(new DirectoryResourceResolver(root));
        GridCache.instance().clear();
        return GridReferenceValues.singleton("sawtooth");
    }

    @Test
    public void theFixtureIsAValidCtable2GridCoveringTheTestPoints() throws IOException {
        List<Grid> grids = sawtoothGrid();
        assertEquals(1, grids.size());
        assertEquals("ctable2", grids.get(0).getFormat());
        double[] extent = grids.get(0).extentRadians();
        assertEquals(-10.0, Math.toDegrees(extent[0]), 1e-9);
        assertEquals(-10.0, Math.toDegrees(extent[1]), 1e-9);
        assertEquals(10.0, Math.toDegrees(extent[2]), 1e-9);
        assertEquals(10.0, Math.toDegrees(extent[3]), 1e-9);
    }

    /** The forward direction is a single interpolation and always succeeds. */
    @Test
    public void theForwardShiftStillWorksOnTheSameGrid() throws IOException {
        List<Grid> grids = sawtoothGrid();
        double[] got = GridReferenceValues.shiftDegrees(grids, false, 0.3, 0.0);
        assertTrue("the forward shift is a single interpolation and must produce a finite result",
                Double.isFinite(got[0]) && Double.isFinite(got[1]));
        assertTrue("and it must actually move the point", Math.abs(got[0] - 0.3) > 0.1);
    }

    @Test
    public void aNonConvergentInverseIterationThrowsRatherThanReturningAnIterate() throws IOException {
        List<Grid> grids = sawtoothGrid();
        try {
            double[] got = GridReferenceValues.shiftDegrees(grids, true, 0.3, 0.0);
            fail("the inverse iteration cannot converge on this grid, yet it returned ("
                    + got[0] + ", " + got[1] + ") -- a plausible coordinate standing in for a "
                    + "numerical failure");
        } catch (ConvergenceFailureException expected) {
            assertEquals("a non-convergent iteration is a numerical failure",
                    ErrorCause.NUMERICAL_FAILURE, expected.cause());
            assertTrue("the message must name the iteration count and the residual: "
                            + expected.getMessage(),
                    expected.getMessage().contains("did not converge")
                            && expected.getMessage().contains("10 iterations"));
        }
    }

    /** A second point, to show it is the grid and not one unlucky coordinate. */
    @Test
    public void theSameHappensAtAnotherInteriorPoint() throws IOException {
        List<Grid> grids = sawtoothGrid();
        try {
            GridReferenceValues.shiftDegrees(grids, true, 0.7, 0.25);
            fail("expected a ConvergenceFailureException");
        } catch (ConvergenceFailureException expected) {
            assertTrue(expected.getMessage().contains("did not converge"));
        }
    }

    /**
     * The control: the very same code path converges on a real grid, so the exception above is a property
     * of the sawtooth field and not of a solver that has simply been made to throw.
     *
     * <p>(The sawtooth grid has no exactly-converging point to use as an internal control: the node values
     * are stored as {@code float}, so even a nominal fixed point lands 1.4e-7 rad off and the expansive map
     * amplifies that away from zero rather than towards it. Which is the correct behaviour — the fixture is
     * expansive everywhere.)</p>
     */
    @Test
    public void theSameSolverConvergesOnARealGrid() throws IOException {
        List<Grid> conus = GridReferenceValues.singleton("conus");
        double[] got = GridReferenceValues.shiftDegrees(conus, true,
                GridReferenceValues.SAN_FRANCISCO[0], GridReferenceValues.SAN_FRANCISCO[1]);
        assertEquals("the real inverse converges and matches PROJ 9.8.1",
                GridReferenceValues.CONUS_INV_SAN_FRANCISCO[0], got[0], GridReferenceValues.TOL_DEG);
        assertTrue(Double.isFinite(got[1]));
    }

    /**
     * A 21&times;21 CTABLE V2 grid over 10&deg;W&ndash;10&deg;E, 10&deg;S&ndash;10&deg;N at 1&deg;
     * spacing. Longitude shift alternates {@code +1&deg;, -1&deg;} by column; latitude shift is zero.
     */
    static byte[] sawtoothCtable2() {
        byte[] b = new byte[160 + COLS * ROWS * 8];
        System.arraycopy("CTABLE V2.0     ".getBytes(ASCII), 0, b, 0, 16);
        System.arraycopy("sawtooth (non-convergent by construction)\n".getBytes(ASCII), 0, b, 16, 42);

        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        buf.putDouble(96, -10.0 * DEG);   // ll.lam
        buf.putDouble(104, -10.0 * DEG);  // ll.phi
        buf.putDouble(112, 1.0 * DEG);    // del.lam
        buf.putDouble(120, 1.0 * DEG);    // del.phi
        buf.putInt(128, COLS);
        buf.putInt(132, ROWS);

        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int off = 160 + (row * COLS + col) * 8;
                // Amplitude exactly one cell: bounded, so iterates stay inside the grid, while the
                // interpolated gradient of +-2 cells per cell makes the fixed-point map an expansion.
                buf.putFloat(off, (float) ((col % 2 == 0 ? 1.0 : -1.0) * DEG));
                buf.putFloat(off + 4, 0.0f);
            }
        }
        return b;
    }
}

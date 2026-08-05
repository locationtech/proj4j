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
package org.locationtech.proj4j.datum.geotiff;

import java.util.List;

import org.junit.Test;
import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.datum.Grid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Which grid in a {@code +nadgrids=} list gets used, and where its edge is.
 *
 * <h2>The claim this class settles, and how</h2>
 *
 * <p>It has been asserted in this project's notes that proj4j's grid selection was <em>wrong</em>: that
 * with {@code NAD27}'s list {@code @conus,@alaska,@ntv2_0.gsb,@ntv1_can.dat}, a NAD27 point over Ottawa
 * got {@code conus} extrapolated past its coverage where PROJ would have used {@code ntv1_can.dat},
 * landing 2.487 m away. <strong>That is not what PROJ does.</strong> Measured with PROJ 9.8.1
 * ({@code Rel. 9.8.1, April 10th, 2026}) on the same bytes this repository ships:
 *
 * <pre>
 * cct -d 12 +proj=hgridshift +grids=conus,ntv1_can.dat  &lt;&lt;&lt; "-75.6972 45.4225 0 0"
 *   -&gt; -75.696903885263  45.422538884313
 * cct -d 12 +proj=hgridshift +grids=ntv1_can.dat,conus  &lt;&lt;&lt; "-75.6972 45.4225 0 0"
 *   -&gt; -75.696872210356  45.422551182897
 * cct -d 12 +proj=hgridshift +grids=conus               &lt;&lt;&lt; "-75.6972 45.4225 0 0"
 *   -&gt; -75.696903885263  45.422538884313     (bit-identical to the first)
 * </pre>
 *
 * <p>So upstream is <strong>list order, first grid whose extent contains the point wins</strong> —
 * {@code findGrid} ({@code grids.cpp:3251-3262}) — which is what proj4j already did. The ~2.5 m is the
 * {@code conus}-versus-{@code ntv1_can} data difference inside their overlap, present in PROJ too, and
 * reordering the list to "fix" it would move proj4j <em>away</em> from PROJ. {@code conus}'s own header,
 * read from the shipped bytes, is 131&deg;W&ndash;63&deg;W by 20&deg;N&ndash;50&deg;N, which is why it
 * reaches Ottawa at all.
 *
 * <p>What <em>was</em> genuinely wrong were two tolerances in the same family, and those are the
 * substance of this class:
 * <ol>
 *   <li>the extent-containment tolerance was {@code 1e-4} where 9.8.1 uses
 *       {@code REL_TOLERANCE_HGRIDSHIFT = 1e-5} — ten times too permissive, and since containment is
 *       exactly what selects the grid, ten times too permissive in the one place it changes the
 *       answer;</li>
 *   <li>the grid-edge clamp window in {@code nad_intr} was {@code 1e-11} where 9.8.1 uses
 *       {@code 10 * REL_TOLERANCE_HGRIDSHIFT = 1e-4} — seven orders of magnitude too tight, so points
 *       PROJ shifts were left unshifted;</li>
 *   <li>and there was no antimeridian handling at all, which makes {@code us_noaa_alaska.tif}
 *       ({@code west = -194}&deg;) unusable over its western half.</li>
 * </ol>
 *
 * <p>This class lives in the GeoTIFF test package because the GeoTIFF reader is what made the third
 * point urgent — it is the format the shifted-longitude-window grids are published in.
 */
public class GridSelectionOrderTest {

    /** Ottawa. Inside {@code conus} (which reaches 50&deg;N) and inside {@code ntv1_can.dat}. */
    private static final double OTTAWA_LON = -75.6972;
    private static final double OTTAWA_LAT = 45.4225;

    /** {@code cct +proj=hgridshift +grids=conus} at Ottawa. */
    private static final double[] CONUS_AT_OTTAWA = {-75.696903885263, 45.422538884313};

    /** {@code cct +proj=hgridshift +grids=ntv1_can.dat} at Ottawa. */
    private static final double[] NTV1_AT_OTTAWA = {-75.696872210356, 45.422551182897};

    /**
     * The first grid in the list that contains the point wins, and the second grid never runs — for both
     * orderings, so this is a statement about order and not about which file happens to be better.
     */
    @Test
    public void firstGridInListOrderWins() throws Exception {
        List<Grid> conusFirst = Grid.fromNadGrids("conus,ntv1_can.dat");
        double[] a = GeoTiffFixtures.shiftDegrees(conusFirst, false, OTTAWA_LON, OTTAWA_LAT);
        assertEquals("conus first must give conus's answer", CONUS_AT_OTTAWA[0], a[0], 1e-9);
        assertEquals(CONUS_AT_OTTAWA[1], a[1], 1e-9);

        List<Grid> ntv1First = Grid.fromNadGrids("ntv1_can.dat,conus");
        double[] b = GeoTiffFixtures.shiftDegrees(ntv1First, false, OTTAWA_LON, OTTAWA_LAT);
        assertEquals("ntv1_can.dat first must give its answer", NTV1_AT_OTTAWA[0], b[0], 1e-9);
        assertEquals(NTV1_AT_OTTAWA[1], b[1], 1e-9);

        assertTrue("the two grids must actually disagree, or this test proves nothing",
                Math.abs(a[0] - b[0]) > 1e-7);
    }

    /**
     * A grid whose bounding box contains the point but whose interpolation refuses it must not be
     * skipped in favour of a coarser grid <em>silently</em> — but the legacy API's documented behaviour
     * is to fall through, so this test pins the current contract rather than asserting a change.
     *
     * <p>Stated explicitly because it is the one place proj4j is still more permissive than 9.8.1:
     * PROJ's {@code pj_hgrid_apply} commits to the grid {@code findGrid} chose and reports
     * {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_GRID}, whereas {@code Grid.shift} continues its loop. With
     * the containment tolerance now matching upstream the window in which the two differ is at most one
     * clamp width wide, but it is not empty.
     */
    @Test
    public void fallThroughToTheNextGridIsStillTheLegacyBehaviour() throws Exception {
        List<Grid> grids = Grid.fromNadGrids("conus,ntv1_can.dat");
        // A point conus contains and ntv1_can.dat does not: south of 40 degrees N.
        double[] sf = GeoTiffFixtures.shiftDegrees(grids, false, -122.416667, 37.783333);
        assertTrue("San Francisco must be shifted by conus", Math.abs(sf[0] + 122.416667) > 1e-6);
    }

    /**
     * The containment tolerance, pinned by a point that discriminates {@code 1e-5} from {@code 1e-4}.
     *
     * <p>{@code conus} has 0.25&deg; cells, so {@code eps = (resX + resY) * REL_TOLERANCE} is
     * {@code 5e-6}&deg; under 9.8.1 and {@code 5e-5}&deg; under 1.4.3. Its south edge is 20&deg;N.
     * Measured with {@code cct +proj=hgridshift +grids=conus}:
     * <ul>
     *   <li>{@code (-100, 19.999999)} — {@code 1e-6}&deg; out, inside both tolerances — PROJ returns
     *       {@code -100.000014233333 20.000382961118};</li>
     *   <li>{@code (-100, 19.99998)} — {@code 2e-5}&deg; out, inside 1.4.3's tolerance and outside
     *       9.8.1's — PROJ returns {@code TRANSFORMATION ERROR}.</li>
     * </ul>
     * The second row is the whole test: under the old tolerance proj4j accepted the point and
     * extrapolated a shift for it.
     */
    @Test
    public void containmentToleranceMatches9_8_1() throws Exception {
        List<Grid> conus = Grid.fromNadGrids("conus");

        double[] justInside = GeoTiffFixtures.shiftDegrees(conus, false, -100.0, 19.999999);
        assertEquals("1e-6 deg outside the south edge is still inside 9.8.1's tolerance",
                -100.000014233333, justInside[0], 1e-9);
        assertEquals(20.000382961118, justInside[1], 1e-9);

        try {
            double[] justOutside = GeoTiffFixtures.shiftDegrees(conus, false, -100.0, 19.99998);
            fail("2e-5 deg outside the south edge is outside 9.8.1's tolerance, which reports "
                    + "TRANSFORMATION ERROR; shift returned ("
                    + justOutside[0] + ", " + justOutside[1] + ")");
        } catch (CrsTransformException expected) {
            // Was: "the legacy API leaves the coordinate alone". It no longer does -- an unchanged
            // coordinate is indistinguishable from a zero shift, and upstream errors here.
            assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, expected.cause());
        }
    }

    /**
     * A grid declared in a shifted longitude window. The NADCON 5 Alaska extract's tie point is
     * {@code 201.583}&deg;, i.e. past &pi; in radians — the same situation as
     * {@code us_noaa_alaska.tif}'s {@code west = -194}&deg;.
     *
     * <p>Both spellings of the meridian must select the grid and apply the same shift.
     * {@code cct -d 12} on these bytes returns {@code -157.999999611484 61.499999564269} for
     * <em>both</em> {@code -158.0} and {@code 202.0} — it normalises the output longitude, which
     * {@code Grid.shift}'s forward branch does not (it is a bare {@code in.lam -= t.lam}, matching
     * PROJ's {@code in.lam += t.lam}). So the comparison here is on the <em>shift</em>, which is the
     * claim: that the grid was found from either convention and the same displacement came out of it.
     */
    @Test
    public void shiftedLongitudeWindowIsFoundFromBothConventions() throws Exception {
        List<Grid> grids = GeoTiffFixtures.horizontal(
                "us_noaa_nadcon5_nad83_2007_nad83_2011_alaska_extract.tif");
        double[] negative = GeoTiffFixtures.shiftDegrees(grids, false, -158.0, 61.5);
        double[] positive = GeoTiffFixtures.shiftDegrees(grids, false, 202.0, 61.5);
        assertEquals(-157.999999611484, negative[0], 1e-11);
        assertEquals(61.499999564269, negative[1], 1e-11);
        assertEquals("202 E and 158 W must yield the same longitude shift",
                negative[0] - (-158.0), positive[0] - 202.0, 1e-13);
        assertEquals("and the same latitude shift", negative[1], positive[1], 1e-13);
    }

    /**
     * A grid spanning the whole world in longitude accepts every meridian, including the seam.
     *
     * <p>{@code test_hgrid_tiled.tif} is 360 cells of 1&deg; from 180&deg;W, so
     * {@code east - west + resX} is exactly 2&pi; and {@code fullWorldLongitude()} short-circuits the
     * east/west test. The seam is where a plain box test fails: {@code -180} is the western edge and
     * {@code 179} the eastern one, so a naive reader rejects everything in between.
     *
     * <p>Measured: {@code cct} shifts {@code (-180, 0.5)} and {@code (-179.999, 0.5)} but reports
     * {@code TRANSFORMATION ERROR} at {@code (179.5, 0.5)} and {@code (179.999, 0.5)} — the extent test
     * accepts those, and then the interpolation refuses them because they are more than a clamp width
     * past the last node. proj4j now says the same thing: this is the
     * <em>contained-but-uninterpolable</em> shape of {@link ErrorCause#COORDINATE_OUTSIDE_GRID}, the
     * one reachable only after the fall-through has exhausted the list. It used to leave the
     * coordinate alone.
     */
    @Test
    public void fullWorldLongitudeAcceptsTheSeam() throws Exception {
        List<Grid> grids = GeoTiffFixtures.horizontal("test_hgrid_tiled.tif");
        double[] e = grids.get(0).extentRadians();
        assertEquals(-180.0, Math.toDegrees(e[0]), 1e-12);
        assertEquals(179.0, Math.toDegrees(e[2]), 1e-12);

        double[] atWest = GeoTiffFixtures.shiftDegrees(grids, false, -180.0, 0.5);
        assertTrue("the western edge must be inside", Double.isFinite(atWest[0]));

        try {
            double[] pastEast = GeoTiffFixtures.shiftDegrees(grids, false, 179.5, 0.5);
            fail("179.5 is inside the extent but past the last node; PROJ reports TRANSFORMATION "
                    + "ERROR and shift returned (" + pastEast[0] + ", " + pastEast[1] + ")");
        } catch (CrsTransformException expected) {
            assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, expected.cause());
            assertTrue("the message must distinguish this from 'no grid covers it': "
                            + expected.getMessage(),
                    expected.getMessage().contains("inside the extent of a grid"));
        }
    }
}

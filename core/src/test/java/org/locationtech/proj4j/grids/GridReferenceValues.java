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
import java.util.ArrayList;
import java.util.List;

import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.datum.Grid;

/**
 * Reference values for the grid tests, and where they came from.
 *
 * <h2>Provenance of every number in this file</h2>
 *
 * <p>All expected coordinates were produced by <strong>PROJ 9.8.1 itself</strong> — the Homebrew build,
 * {@code cs2cs} reporting {@code Rel. 9.8.1, April 10th, 2026} — reading <em>the same grid file bytes</em>
 * that this repository ships. That is the only reference that isolates what these tests are about: it
 * holds the grid data, the interpolation, the datum semantics and the version all fixed, so a
 * disagreement can only be proj4j's reader or proj4j's interpolation. Published NGS NADCON tables would
 * not do that, because they use a different (NADCON 5) model and would fold a data difference into
 * every assertion.
 *
 * <p>Commands, verbatim:
 * <pre>
 * PROJ_DATA=&lt;dir with conus, ntv1_can.dat, ntv2_0_downsampled.gsb&gt;:/opt/homebrew/share/proj
 *
 * cs2cs -f "%.10f" +proj=longlat +ellps=clrk66 +nadgrids=conus \
 *              +to +proj=longlat +datum=NAD83                      &lt; points
 *
 * cs2cs -f "%.10f" +proj=longlat +datum=NAD83 \
 *              +to +proj=longlat +ellps=clrk66 +nadgrids=conus     &lt; points    # inverse
 *
 * cct  -d 10 +proj=vgridshift +grids=egm96_15_downsampled.gtx +multiplier=1     &lt; points
 * </pre>
 *
 * <p>Values are printed to 10 decimal places of a degree, i.e. rounded at about 0.01 mm, so assertions
 * use a tolerance of 1e-9&deg; (roughly 0.1 mm) — three orders of magnitude tighter than the ~13 m and
 * ~95 m errors under test, and tight enough that a one-node indexing slip cannot hide in it.
 *
 * <h2>Why the assertions are on {@code Grid.shift} and not only on a CRS pair</h2>
 *
 * <p>{@code cs2cs} between two geographic proj-strings applies the horizontal grid shift and nothing
 * else. Proj4J's CRS-level path may additionally route through geocentric coordinates to change
 * ellipsoid, which perturbs latitude by a small amount that has nothing to do with the grid. Comparing
 * {@code Grid.shift} directly keeps the comparison apples-to-apples at full precision; the CRS-level
 * tests then assert the same shift with a tolerance that admits the ellipsoid step.
 */
final class GridReferenceValues {

    private GridReferenceValues() {
    }

    /** Tolerance in degrees for a direct {@code Grid.shift} comparison: ~0.1 mm. */
    static final double TOL_DEG = 1e-9;

    // --- Points -------------------------------------------------------------------------------

    /** San Francisco. Outside {@code ntv1_can.dat} (37.78&deg;N &lt; 40&deg;N); inside {@code conus}. */
    static final double[] SAN_FRANCISCO = {-122.416667, 37.783333};

    /** Kansas. Outside {@code ntv1_can.dat} (39.0&deg;N &lt; 40&deg;N); inside {@code conus}. */
    static final double[] KANSAS = {-97.5, 39.0};

    /** Chicago. Inside <em>both</em> {@code conus} and {@code ntv1_can.dat} — the two disagree. */
    static final double[] CHICAGO = {-87.6, 41.9};

    /** Boston. Inside both. */
    static final double[] BOSTON = {-71.06, 42.36};

    /** Mid-Atlantic: outside every grid shipped or tested here. */
    static final double[] OPEN_OCEAN = {-40.0, 35.0};

    // --- conus, NAD27 -> NAD83 ----------------------------------------------------------------

    static final double[] CONUS_FWD_SAN_FRANCISCO = {-122.4177492097, 37.7832622344};
    static final double[] CONUS_FWD_KANSAS = {-97.5003073411, 38.9999993489};
    static final double[] CONUS_FWD_CHICAGO = {-87.6000518697, 41.9000329865};
    static final double[] CONUS_FWD_BOSTON = {-71.0594954596, 42.3600974317};

    // --- conus, NAD83 -> NAD27 (the iterative inverse) ----------------------------------------

    static final double[] CONUS_INV_SAN_FRANCISCO = {-122.4155848029, 37.7834037741};
    static final double[] CONUS_INV_KANSAS = {-97.4996926694, 39.0000006530};
    static final double[] CONUS_INV_CHICAGO = {-87.5999481331, 41.8999670129};
    static final double[] CONUS_INV_BOSTON = {-71.0605045282, 42.3599025694};

    // --- ntv1_can.dat, NAD27 -> NAD83 ---------------------------------------------------------

    static final double[] NTV1_FWD_CHICAGO = {-87.6001190236, 41.9000194486};
    static final double[] NTV1_FWD_BOSTON = {-71.0596068923, 42.3600965120};

    // --- ntv2_0_downsampled.gsb, NAD27 -> NAD83 -----------------------------------------------

    /** Inside subgrid {@code ONwinsor}, a child of {@code CAeast}. */
    static final double[] NTV2_ONWINSOR = {-83.0, 42.1};
    static final double[] NTV2_FWD_ONWINSOR = {-82.9999212472, 42.1000428139};

    /** Inside subgrid {@code ALraymnd}, a child of {@code CAwest}. */
    static final double[] NTV2_ALRAYMND = {-113.0, 49.4};
    static final double[] NTV2_FWD_ALRAYMND = {-113.0009350195, 49.3999888517};

    /** Inside subgrid {@code ALbanff}, a child of {@code CAwest}. */
    static final double[] NTV2_ALBANFF = {-115.55, 51.15};
    static final double[] NTV2_FWD_ALBANFF = {-115.5510424277, 51.1500230389};

    /** Inside root subgrid {@code CAwest} only -- and outside the first subgrid, {@code CAeast}. */
    static final double[] NTV2_CAWEST = {-120.0, 55.0};
    static final double[] NTV2_FWD_CAWEST = {-120.0013964435, 54.9999609805};

    // --- egm96_15_downsampled.gtx -------------------------------------------------------------

    static final double[][] GTX_POINTS = {
            {0.0, 0.0},
            {-122.416667, 37.783333},
            {12.5, 41.9},
            {-179.0, -89.0},
            {151.21, -33.87},
    };

    static final double[] GTX_EXPECTED = {
            17.2340171337,
            -32.7083794732,
            48.0834085220,
            -30.0507870266,
            21.9752037526,
    };

    /** {@code cct} prints 10 decimals of a metre. */
    static final double GTX_TOL = 1e-9;

    // --- Helpers ------------------------------------------------------------------------------

    static List<Grid> grids(String nadgrids) throws IOException {
        return Grid.fromNadGrids(nadgrids);
    }

    static List<Grid> singleton(String name) throws IOException {
        List<Grid> list = new ArrayList<Grid>();
        Grid.mergeGridFile(name, list);
        return list;
    }

    /**
     * Applies {@link Grid#shift} to a lon/lat pair given in degrees and returns degrees, so the test
     * assertions read in the same units as the {@code cs2cs} output they are compared against.
     */
    static double[] shiftDegrees(List<Grid> grids, boolean inverse, double lonDeg, double latDeg) {
        ProjCoordinate c = new ProjCoordinate(Math.toRadians(lonDeg), Math.toRadians(latDeg));
        Grid.shift(grids, inverse, c);
        return new double[]{Math.toDegrees(c.x), Math.toDegrees(c.y)};
    }
}

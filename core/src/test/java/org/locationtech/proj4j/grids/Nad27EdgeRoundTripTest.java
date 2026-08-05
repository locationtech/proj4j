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
import java.util.List;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.datum.Grid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The asymmetric round trip at 40&deg;N, the southern edge of {@code ntv1_can.dat}.
 *
 * <h2>The observation</h2>
 *
 * <p>A golden-master re-triage found <b>41 registry rows that round-tripped exactly in 1.4.3 and had
 * become 30&ndash;110 m out</b>. Every one probed <b>latitude exactly 40.000000</b> — and
 * {@code ntv1_can.dat}, the only NAD27 grid {@code proj4j-epsg} actually ships, has its southern
 * boundary at exactly that latitude ({@code S LAT 40.0, N LAT 84.0, E LONG 44.0, W LONG 142.0}; see
 * {@link Ntv1CanHeaderTest}). Re-measured on a frozen tree while this fix was written: <b>40 rows,
 * every one at latitude 40.0 and none at any other latitude</b>, displaced 12.6&ndash;93.1 m.
 *
 * <h2>The mechanism</h2>
 *
 * <ol>
 *   <li>The <b>forward</b> leg (WGS84 &rarr; a NAD27 CRS) finds 40.000000&deg;N inside the grid, on
 *       its very edge, and applies the shift. The shifted point is a few tens of metres
 *       <em>south</em> of 40&deg;N — <b>outside</b>.</li>
 *   <li>The <b>return</b> leg starts from that point. No grid contains it, so 1.4.3's {@code else}
 *       branch returned it unchanged and the shift silently did not happen.</li>
 *   <li>So the round trip came back displaced by <b>exactly one grid shift</b>. The tell is that the
 *       inverse residue equals the forward shift to two digits, which is what distinguishes "the
 *       inverse is not running" from "the inverse is not converging".</li>
 * </ol>
 *
 * <p>2,504 of the 2,545 NAD27 registry rows still round-tripped exactly, so this was a boundary
 * effect and not a broken reader.
 *
 * <h2>What "fixed" means here, and it is not "it round-trips now"</h2>
 *
 * <p><b>It cannot round-trip, and PROJ 9.8.1 does not round-trip it either.</b> The return leg needs
 * a grid value at a point outside the only grid there is; no amount of correctness produces one.
 * The fix is that the failure is now <em>reported</em> instead of being expressed as a coordinate
 * 93 m away. Measured on PROJ 9.8.1 reading the identical {@code ntv1_can.dat} bytes:
 *
 * <pre>
 * PROJ_DATA=&lt;dir with ntv1_can.dat only&gt;
 *
 * # forward, WGS84 -&gt; NAD27 / UTM 21N
 * echo "-49.928932188 40.0" | cs2cs -f "%.6f" +proj=longlat +datum=WGS84 \
 *                  +to +proj=utm +zone=21 +ellps=clrk66 +nadgrids=&#64;ntv1_can.dat
 *   1103774.042006	4451553.314930
 *
 * # the return leg
 * echo "1103917.82 4429629.0" | cs2cs -f "%.9f" +proj=utm +zone=21 +ellps=clrk66 \
 *                  +nadgrids=&#64;ntv1_can.dat +to +proj=longlat +datum=WGS84
 *   *	* inf
 *
 * # and the operator on its own, at the two points
 * printf -- "-49.928932188 40.0 0 0\n"  | cct -d 10 -I +proj=hgridshift +grids=ntv1_can.dat
 *   -49.9300222112   39.9999534467
 * printf -- "-49.9300222 39.99998 0 0\n" | cct -d 10   +proj=hgridshift +grids=ntv1_can.dat
 *   # Record 0 TRANSFORMATION ERROR (Coordinate to transform falls outside grid)
 * </pre>
 *
 * <p>{@code -49.9300222112} is, to ten decimals, the value the golden table records proj4j
 * returning for {@code REG epsg:26721 probe 2}.
 *
 * <h2>Why this class names its grid explicitly</h2>
 *
 * <p>{@code core}'s test classpath also carries {@code conus} (from {@code proj4j-grids-us-legacy}),
 * whose extent is 131&deg;W&ndash;63&deg;W, 20&deg;N&ndash;50&deg;N — so through {@code +datum=NAD27}
 * most of these probes are answered by {@code conus} and never reach {@code ntv1_can.dat} at all.
 * The defect is a property of the <em>ntv1-only</em> configuration that {@code proj4j-epsg} ships,
 * which is why every case here writes {@code +nadgrids=ntv1_can.dat} rather than relying on which
 * optional grid packs happen to be present.
 */
public class Nad27EdgeRoundTripTest {

    private static final CRSFactory CRS_FACTORY = new CRSFactory();
    private static final CoordinateTransformFactory CT_FACTORY = new CoordinateTransformFactory();

    /** The golden probe for {@code REG epsg:26721} probe 2, on the grid's southern edge. */
    private static final double LON = -49.928932188134520;
    private static final double LAT = 40.0;

    /** {@code cct -d 10 -I +proj=hgridshift +grids=ntv1_can.dat} at the probe. */
    private static final double[] INVERSE_AT_EDGE = {-49.9300222112, 39.9999534467};

    private static List<Grid> ntv1() throws IOException {
        return GridReferenceValues.singleton("ntv1_can.dat");
    }

    /** The premise: 40.0 is the southern edge, and the probe is inside — just. */
    @Test
    public void theProbeIsOnTheSouthernEdgeAndInsideTheGrid() throws IOException {
        List<Grid> grids = ntv1();
        double[] extent = grids.get(0).extentRadians();
        assertEquals("south edge", 40.0, Math.toDegrees(extent[1]), 1e-9);
        assertTrue("and the probe longitude is well inside the east-west span",
                Math.toDegrees(extent[0]) < LON && LON < Math.toDegrees(extent[2]));

        double[] shifted = GridReferenceValues.shiftDegrees(grids, true, LON, LAT);
        assertEquals("the inverse shift must agree with PROJ 9.8.1 on the same bytes",
                INVERSE_AT_EDGE[0], shifted[0], GridReferenceValues.TOL_DEG);
        assertEquals(INVERSE_AT_EDGE[1], shifted[1], GridReferenceValues.TOL_DEG);
        assertTrue("and it must have moved the point SOUTH of the edge, which is the whole "
                + "mechanism; it went to " + shifted[1], shifted[1] < 40.0);
    }

    /**
     * The return leg, at the operator level. PROJ reports
     * {@code TRANSFORMATION ERROR (Coordinate to transform falls outside grid)}; 1.4.3 returned the
     * point unchanged, which is what made the round trip come back one whole shift away.
     */
    @Test
    public void theReturnLegIsOutsideTheGridAndSaysSoInsteadOfEchoing() throws IOException {
        List<Grid> grids = ntv1();
        double[] shifted = GridReferenceValues.shiftDegrees(grids, true, LON, LAT);
        try {
            double[] back = GridReferenceValues.shiftDegrees(grids, false, shifted[0], shifted[1]);
            double residue = Math.abs(back[0] - LON) * 111320.0 * Math.cos(Math.toRadians(LAT));
            fail("the return leg is outside the grid and PROJ refuses it, yet shift returned ("
                    + back[0] + ", " + back[1] + "), leaving the round trip " + residue
                    + " m from where it started");
        } catch (CrsTransformException expected) {
            assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, expected.cause());
            assertTrue(expected.getMessage().contains("outside every grid"));
            assertTrue(expected.getMessage().contains("ntv1_can.dat"));
        }
    }

    /**
     * <b>The size of what was being returned silently.</b> This is the assertion the golden re-triage
     * would have written: had the fail-open survived, the round trip would have come back
     * 30&ndash;110 m away, and the residue would equal the forward shift.
     *
     * <p>It is measured here from the <em>shift itself</em> rather than from the round trip, because
     * the round trip now throws — the number is the same one, and it is the reason the throw is
     * worth having.
     */
    @Test
    public void theSilentResidueWouldHaveBeenTensOfMetres() throws IOException {
        List<Grid> grids = ntv1();
        double[] shifted = GridReferenceValues.shiftDegrees(grids, true, LON, LAT);
        double metres = Math.hypot(
                (shifted[0] - LON) * 111320.0 * Math.cos(Math.toRadians(LAT)),
                (shifted[1] - LAT) * 110540.0);
        assertTrue("the un-run return shift is the whole error; measured " + metres + " m",
                metres > 30.0 && metres < 110.0);
    }

    /**
     * The same thing through a CRS pair, which is the shape the golden table records:
     * {@code REG epsg:26721 probe 2} is WGS84 lon/lat into NAD27 / UTM zone 21N, and its inverse.
     * The forward still works and still agrees with PROJ; only the return leg refuses.
     */
    @Test
    public void theCrsRoundTripFailsClosedOnTheReturnLegAndNotOnTheForward() {
        CoordinateReferenceSystem wgs84 = CRS_FACTORY.createFromParameters("wgs84",
                "+proj=longlat +datum=WGS84 +no_defs");
        CoordinateReferenceSystem utm21 = CRS_FACTORY.createFromParameters("nad27-utm21+ntv1",
                "+proj=utm +zone=21 +ellps=clrk66 +nadgrids=ntv1_can.dat +no_defs");

        ProjCoordinate projected = new ProjCoordinate();
        CT_FACTORY.createTransform(wgs84, utm21).transform(new ProjCoordinate(LON, LAT), projected);
        assertEquals("the forward leg still agrees with cs2cs 9.8.1 on the same bytes",
                1103774.042006, projected.x, 1e-3);

        CoordinateTransform back = CT_FACTORY.createTransform(utm21, wgs84);
        try {
            ProjCoordinate out = new ProjCoordinate();
            back.transform(projected, out);
            fail("cs2cs 9.8.1 answers '* * inf' for this return leg; proj4j returned ("
                    + out.x + ", " + out.y + "), which is " + Math.abs(out.x - LON) * 111320.0
                    + " m from the input");
        } catch (CrsTransformException expected) {
            assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, expected.cause());
        }
    }

    /**
     * The control that keeps the three tests above from being about "40&deg;N is broken": one
     * degree further north the same CRS pair round-trips to well under a millimetre, because both
     * legs stay inside the grid.
     */
    @Test
    public void oneDegreeFurtherNorthTheSameRoundTripIsExact() {
        CoordinateReferenceSystem wgs84 = CRS_FACTORY.createFromParameters("wgs84",
                "+proj=longlat +datum=WGS84 +no_defs");
        CoordinateReferenceSystem utm21 = CRS_FACTORY.createFromParameters("nad27-utm21+ntv1",
                "+proj=utm +zone=21 +ellps=clrk66 +nadgrids=ntv1_can.dat +no_defs");

        ProjCoordinate projected = new ProjCoordinate();
        ProjCoordinate out = new ProjCoordinate();
        CT_FACTORY.createTransform(wgs84, utm21).transform(new ProjCoordinate(LON, 41.0), projected);
        CT_FACTORY.createTransform(utm21, wgs84).transform(projected, out);

        assertEquals("longitude round trip at 41N", LON, out.x, 1e-9);
        assertEquals("latitude round trip at 41N", 41.0, out.y, 1e-9);
    }
}

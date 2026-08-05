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
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A 2D&nbsp;&rarr;&nbsp;2D datum shift must not fabricate a height.
 *
 * <h2>The defect, as measured before the fix</h2>
 *
 * <p>{@code EPSG:4326 -> EPSG:27700} with <b>no input height</b> —
 * {@code new ProjCoordinate(-2.0, 53.0)}, whose two-argument constructor sets
 * {@code z = Double.NaN}, this library's documented "no height" sentinel — returned
 *
 * <pre>
 * ProjCoordinate[400097.23902646254 344742.07249671593 -49.84606796130538]</pre>
 *
 * <p>The horizontal ordinates are right, to 2e-10&nbsp;m of PROJ 9.8.1. The
 * <b>&minus;49.84606796130538</b> is not: nothing in the input carried a height, and the
 * caller is handed a metre-scaled number in a field they never populated. It arises because
 * a datum shift routes through geocentric coordinates, and
 * {@code datum/GeocentricConverter.java:106} substitutes {@code 0} for an absent height on
 * the way in — which is faithful to PROJ, see below — while {@code :266} writes the
 * <em>computed</em> height back on the way out, with nothing to restore the absence. The
 * value is the negative geoid-free difference between the WGS&nbsp;84 and Airy&nbsp;1830
 * ellipsoidal surfaces at that point, so it is entirely plausible, which is what makes it
 * dangerous.
 *
 * <h2>What PROJ 9.8.1 does, checked rather than assumed</h2>
 *
 * <p>PROJ wraps the geocentric leg of a <em>two-dimensional</em> operation in
 * {@code +proj=push +v_3} / {@code +proj=pop +v_3}, which saves the third ordinate and
 * restores it byte for byte. Verbatim from
 * {@code projinfo -s "+proj=longlat +datum=WGS84 +type=crs" -t "<EPSG:27700 as this library
 * defines it> +type=crs" -o PROJ}, which is the operation Proj4J's own parameter string
 * selects:
 *
 * <pre>
 * +proj=pipeline
 *   +step +proj=unitconvert +xy_in=deg +xy_out=rad
 *   +step +proj=push +v_3
 *   +step +proj=cart +ellps=WGS84
 *   +step +inv +proj=helmert +x=446.448 +y=-125.157 +z=542.06 +rx=0.1502 +ry=0.247
 *         +rz=0.8421 +s=-20.4894 +convention=position_vector
 *   +step +inv +proj=cart +ellps=airy
 *   +step +proj=pop +v_3
 *   +step +proj=tmerc +lat_0=49 +lon_0=-2 +k=0.9996012717 +x_0=400000 +y_0=-100000
 *         +ellps=airy</pre>
 *
 * <p><b>Those Helmert parameters are the ones in PROJ 9.8.1's own {@code src/datums.cpp:59},
 * which is where {@code +datum=OSGB36} is defined</b>, and they are the ones
 * {@link org.locationtech.proj4j.datum.Datum#OSGB36} carries. They are <em>not</em> the
 * rounded {@code +rx=0.15 +ry=0.247 +rz=0.842 +s=-20.489} that {@code projinfo -s EPSG:4326
 * -t EPSG:27700} emits — that is EPSG:1314, a different published realisation of the same
 * shift, and it lands 3.3&nbsp;mm away in easting. Every number in this file was re-pinned
 * once {@code Datum.OSGB36} was corrected to the {@code datums.cpp} values; see
 * {@code CoordinateTransformTest.testPROJ4} for the same correction applied to eight other
 * rows.
 *
 * <p>The two runs that separate the push/pop from its absence, both PROJ 9.8.1, both on the
 * pipeline above:
 *
 * <pre>
 * echo "-2 53 100" | cct -d 15 &lt;the pipeline above&gt;
 *   400097.237314143800  344742.073156363796  100.000000000000
 * echo "-2 53 100" | cct -d 15 &lt;the same pipeline without push/pop&gt;
 *   400097.237314143800  344742.073156363796   50.155981001444</pre>
 *
 * <p>So the answer to "propagate {@code NaN}, or leave the caller's {@code Z} untouched?" is
 * that <b>they are the same answer</b>: PROJ leaves it untouched, and for a coordinate whose
 * height is the {@code NaN} sentinel, leaving it untouched <em>is</em> propagating
 * {@code NaN}. PROJ substitutes {@code 0} for a {@code HUGE_VAL} height when a Helmert is
 * present ({@code fwd.cpp:47-51}) only because {@code PJ_COORD} has no absent state;
 * {@code ProjCoordinate} does, so the sentinel is the thing to preserve. Note also that
 * push/pop is <b>horizontally neutral</b>: it is applied after {@code cart} has already
 * consumed the height, so the easting and northing above are identical with and without it,
 * which is why only the third ordinate ever moves.
 *
 * <h2>The fix</h2>
 *
 * <p>Four lines in two methods of {@code BasicCoordinateTransform}. In
 * {@code transformClosed(ProjCoordinate, ProjCoordinate)}, beside the existing
 * {@code nanIn} and read before {@code setValue} for the same aliasing reason:
 *
 * <pre>
 * final boolean noHeightIn = Double.isNaN(src.z);</pre>
 *
 * <p>and then {@code push}/{@code pop} around the one stage that can invent a height:
 *
 * <pre>
 * if (doDatumTransform) {
 *     datumTransform(tgt);
 *     if (noHeightIn) {
 *         tgt.z = Double.NaN;
 *     }
 * }</pre>
 *
 * <p>and the identical pair on {@code sz} around {@code datumStage(c)} in the bulk path's
 * {@code transformPoint}. Restoring immediately after the datum stage is sufficient and is
 * where PROJ puts it, and both halves of that claim were re-checked against the current tree
 * rather than inherited: {@code proj.Projection} contains <b>zero</b> occurrences of
 * {@code .z}, and the only class under {@code proj/} that mentions the ordinate at all is
 * {@code GeocentProjection}, which is {@code +proj=geocent} and for which a height is data
 * rather than decoration; {@code datum.AxisOrder} only copies or negates {@code z}, and
 * {@code PrimeMeridian} touches {@code x} alone. So a {@code NaN} placed there survives to
 * the caller.
 *
 * <h2>One case this does not reach, deliberately</h2>
 *
 * <p>An <em>explicit</em> {@code z = 0} still comes back as {@code -49.85}, where
 * {@code cs2cs EPSG:4326 EPSG:27700} returns {@code 0}. That is not the same defect: PROJ
 * decides push/pop from the <em>CRSs'</em> dimensionality, and a legacy
 * {@code CoordinateReferenceSystem} has none to consult — a finite {@code z} is the only
 * signal the legacy API has that the caller means a 3D point, and for a genuinely 3D
 * operation PROJ does transform the height. Making that case agree needs the CRS to know it
 * is two-dimensional, which is what {@link CompoundCrs} and {@link VerticalCrs} exist to
 * express. See {@link #anExplicitHeightIsStillTransformed()}.
 */
public class InventedHeightTest {

    /**
     * PROJ 9.8.1's height for this point when the geocentric leg is <em>not</em> bracketed by
     * push/pop, i.e. the height Proj4J computes for an explicitly supplied {@code z = 0}.
     * From {@code cct -d 15} on the pipeline in this class's javadoc, minus the push/pop pair.
     */
    private static final double PROJ_HEIGHT_FROM_ZERO = -49.846067961305380;

    /** PROJ 9.8.1, same pipeline, from an explicit {@code z = 100}. */
    private static final double PROJ_HEIGHT_FROM_100 = 50.155981001444161;

    /**
     * The largest Proj4J-vs-PROJ residual measured over every ordinate asserted in this file,
     * in metres. Every expected value here came from {@code cct}; none came from Proj4J.
     */
    private static final double TOL_M = 1.0e-8;

    private static final double LON = -2.0;
    private static final double LAT = 53.0;

    private static CoordinateTransform wgs84ToOsgb() {
        CRSFactory crsFactory = new CRSFactory();
        CoordinateReferenceSystem src = crsFactory.createFromName("EPSG:4326");
        CoordinateReferenceSystem tgt = crsFactory.createFromName("EPSG:27700");
        return new CoordinateTransformFactory().createTransform(src, tgt);
    }

    /**
     * What the fix must <b>not</b> change: the horizontal ordinates.
     *
     * <p>They are asserted against PROJ, not against Proj4J's before-value, so this is a
     * correctness bar rather than a no-movement bar — but it is also the no-movement bar,
     * because the fix restores the sentinel after the datum stage and the two projections
     * downstream of it never read {@code z}.
     */
    @Test
    public void theHorizontalOrdinatesAreUnaffectedAndAgreeWithProj() {
        ProjCoordinate in = new ProjCoordinate(LON, LAT);
        assertTrue("the two-argument constructor is the no-height sentinel", Double.isNaN(in.z));

        ProjCoordinate out = new ProjCoordinate();
        wgs84ToOsgb().transform(in, out);

        // cct -d 15, the javadoc pipeline: 400097.239026462543  344742.072496716108
        assertEquals("easting", 400097.239026462543, out.x, TOL_M);
        assertEquals("northing", 344742.072496716108, out.y, TOL_M);
    }

    /**
     * The defect itself: an absent height must stay absent.
     */
    @Test
    public void aTransformWithNoInputHeightMustNotProduceOne() {
        ProjCoordinate in = new ProjCoordinate(LON, LAT);
        ProjCoordinate out = new ProjCoordinate();
        wgs84ToOsgb().transform(in, out);

        assertFalse("EPSG:4326 -> EPSG:27700 of (" + LON + ", " + LAT + ") with no input height "
                        + "returned z = " + out.z + ". PROJ 9.8.1 returns the caller's third "
                        + "ordinate unchanged, via +proj=push +v_3 / +proj=pop +v_3 around the "
                        + "geocentric leg, so an absent height must stay absent.",
                out.hasValidZOrdinate());
    }

    /**
     * The same defect through the bulk path, which is a separate method and needed the same
     * two lines.
     */
    @Test
    public void theBulkPathMustNotProduceAHeightEither() {
        double[] xyz = {LON, LAT, Double.NaN};
        int failures = ((org.locationtech.proj4j.BulkCoordinateTransform) wgs84ToOsgb())
                .transform3D(xyz, 0, 1, 3, null);
        assertEquals("the point itself transforms fine", 0, failures);
        assertTrue("transform3D of a point whose z is the no-height sentinel returned z = "
                        + xyz[2] + "; the sentinel must survive the geocentric round trip",
                Double.isNaN(xyz[2]));
        // Bit-for-bit the single-point path, which is the bulk API's normative contract.
        assertEquals("bulk easting", 400097.239026462543, xyz[0], TOL_M);
        assertEquals("bulk northing", 344742.072496716108, xyz[1], TOL_M);
    }

    /**
     * A finite height <em>is</em> data and is transformed, which is correct for a 3D
     * operation and is what the fix must not change.
     *
     * <p>The guard is on the sentinel, not on the datum shift, so {@code z = 0} continues to
     * mean "0 m of ellipsoidal height" rather than "no height".
     */
    @Test
    public void anExplicitHeightIsStillTransformed() {
        ProjCoordinate out = new ProjCoordinate();
        wgs84ToOsgb().transform(new ProjCoordinate(LON, LAT, 0.0), out);
        assertEquals("an explicitly supplied height is transformed, as it is for a 3D operation",
                PROJ_HEIGHT_FROM_ZERO, out.z, TOL_M);

        // Not PROJ_HEIGHT_FROM_ZERO + 100: a Helmert is affine in *cartesian* coordinates, so
        // the change in ellipsoidal height is not quite independent of the height itself. The
        // two differ by 1.9 mm here, which is exactly why these are pinned numbers and not an
        // arithmetic identity. The easting and northing move by 1.7 mm with the height.
        ProjCoordinate out100 = new ProjCoordinate();
        wgs84ToOsgb().transform(new ProjCoordinate(LON, LAT, 100.0), out100);
        assertEquals("a real height goes through the geocentric round trip",
                PROJ_HEIGHT_FROM_100, out100.z, TOL_M);
        assertEquals(400097.237314143800, out100.x, TOL_M);
        assertEquals(344742.073156363796, out100.y, TOL_M);
    }

    /**
     * A same-datum transform never enters the geocentric leg, so it has never invented a
     * height. Included as the control: it is what shows the defect is the datum stage and not
     * the projection.
     */
    @Test
    public void aTransformWithNoDatumShiftAlreadyPreservesTheSentinel() {
        CRSFactory crsFactory = new CRSFactory();
        CoordinateTransform t = new CoordinateTransformFactory().createTransform(
                crsFactory.createFromName("EPSG:4326"), crsFactory.createFromName("EPSG:3857"));
        ProjCoordinate out = new ProjCoordinate();
        t.transform(new ProjCoordinate(LON, LAT), out);
        assertTrue("EPSG:4326 -> EPSG:3857 shares a datum, so no height is invented",
                Double.isNaN(out.z));
    }
}

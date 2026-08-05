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
package org.locationtech.proj4j.omerc;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * The nine {@code omerc} rows that {@code proj4-epsg.csv} recorded as {@code "failing"}, and the five
 * commented-out {@code ESRI:102631} rows from {@code PROJ4_SPCS_EPSG_nad83.csv}.
 *
 * <p>Both sets are held here as live assertions rather than as CSV status flags, because a
 * {@code "failing"} marker in a data file overstates the damage the moment it goes stale and nothing
 * fails when it does.
 *
 * <h2>The nine {@code proj4-epsg.csv} rows all pass now</h2>
 *
 * <p>They are EPSG 3376, 3468, 5247, 6394, 26731, 26931, 29871, 29872 and 29873 — every one an
 * {@code omerc} carrying {@code +gamma}, and seven of the nine also {@code +no_uoff}. Residual against
 * the values the CSV pins is at most <b>5e-7 m</b> where the CSV's own tolerance is 0.1 m (0.03048 m
 * for 26731). They were failing on the {@code cos(gamma)}/{@code cos(alpha)} defect and on
 * {@code Gamma} defaulting to {@code 0.0}. The other four rows that upstream marked
 * {@code "failing"} (3388, 3752, 3994, 5641) are {@code merc +lat_ts} and are not this class's
 * business.
 *
 * <p><b>The {@code "failing"} markers are historical.</b> They are how the file arrived from
 * upstream at {@code 59c2f66}, which had 18 of them; eight of the nine {@code omerc} rows were
 * already reclassified to {@code passing} before the 9.8.1 regeneration, and that regeneration
 * cleared the last four {@code merc} ones. The committed file now carries no {@code "failing"}
 * row at all &mdash; which is exactly why these nine live here as executed assertions instead.
 *
 * <h2>The five {@code ESRI:102631} rows still do not, and {@code omerc} is no longer why</h2>
 *
 * <p>See {@link #esri102631NeedsNoUoffInTheShippedEsriDictionary()}.
 */
public class ObliqueMercatorEpsgWitnessTest {

    private static final CRSFactory CRS_FACTORY = new CRSFactory();
    private static final CoordinateTransformFactory CT_FACTORY = new CoordinateTransformFactory();

    private static ProjCoordinate from(String srcCode, String tgt, double lon, double lat) {
        CoordinateReferenceSystem src = CRS_FACTORY.createFromName(srcCode);
        CoordinateReferenceSystem dst = tgt.startsWith("+")
                ? CRS_FACTORY.createFromParameters("tgt", tgt)
                : CRS_FACTORY.createFromName(tgt);
        ProjCoordinate out = new ProjCoordinate();
        CT_FACTORY.createTransform(src, dst).transform(new ProjCoordinate(lon, lat), out);
        return out;
    }

    private static ProjCoordinate fromWgs84(String tgt, double lon, double lat) {
        return from("EPSG:4326", tgt, lon, lat);
    }

    /** {@code PROJ4_SPCS_EPSG_nad83.csv} rows are sourced from EPSG:4269, not EPSG:4326. */
    private static ProjCoordinate fromNad83(String tgt, double lon, double lat) {
        return from("EPSG:4269", tgt, lon, lat);
    }

    private static void row(String code, double lon, double lat,
                            double x, double y, double tolerance) {
        ProjCoordinate out = fromWgs84(code, lon, lat);
        assertEquals(code + " easting", x, out.x, tolerance);
        assertEquals(code + " northing", y, out.y, tolerance);
    }

    /**
     * All nine, at the CSV's own point (1, -1) and the CSV's own expected values and tolerances.
     * Each expected pair is the row from {@code proj4-epsg.csv}, which was auto-generated from
     * PROJ's EPSG database.
     */
    @Test
    public void theNineOmercRowsRecordedAsFailingNowPass() {
        row("EPSG:3376", 1.0, -1.0, -1.2409058238151E7, -4357833.596094, 0.1);
        row("EPSG:3468", 1.0, -1.0, 2.1299477541839E7, -9945672.888623, 0.1);
        row("EPSG:5247", 1.0, -1.0, -1.2409058238151E7, -4357833.596094, 0.1);
        row("EPSG:6394", 1.0, -1.0, 2.1299477541839E7, -9945672.888623, 0.1);
        // EPSG:26731 has moved to epsg26731IsNowRefusedByTheDatumStageNotByOmerc(), below.
        row("EPSG:26931", 1.0, -1.0, 2.1299477541839E7, -9945672.888623, 0.1);
        row("EPSG:29871", 1.0, -1.0, -616802.381396, -216616.294447, 1.0);
        row("EPSG:29872", 1.0, -1.0, -4.0708957167923E7, -1.4296675428702E7, 1.0);
        row("EPSG:29873", 1.0, -1.0, -1.2408068634417E7, -4357619.119986, 0.1);
    }

    /**
     * The ninth row, and it is no longer an {@code omerc} question at all.
     *
     * <p>{@code EPSG:26731} is the only one of the nine carrying {@code +datum=NAD27}, and the CSV
     * probes it at <b>(1, -1) — the Gulf of Guinea</b>, some 12,000 km from Alaska and outside every
     * grid {@code +datum=NAD27} names. {@code Grid.shift} used to apply no shift there and report
     * success, so the row reached {@code omerc} with an unshifted coordinate and matched the CSV,
     * whose expected value was generated the same way. It is now
     * {@link ErrorCause#COORDINATE_OUTSIDE_GRID}, matching PROJ 9.8.1:
     *
     * <pre>
     * echo "1 -1" | cs2cs -f "%.10f" +proj=longlat +datum=WGS84 \
     *      +to +proj=longlat +ellps=clrk66 +nadgrids=&#64;conus,&#64;alaska,&#64;ntv2_0.gsb,&#64;ntv1_can.dat
     *   *	* inf
     * </pre>
     *
     * <p>(At the CRS level with {@code proj.db} present, {@code cs2cs +to +proj=longlat +datum=NAD27}
     * instead selects <em>"Ballpark geographic offset"</em> — a <b>declared</b> no-op chosen by the
     * operation factory because the point is outside NADCON's area of use. proj4j's legacy
     * {@code +datum=} path has no such factory; it is the operator path, and the operator path
     * errors. Reproducing the ballpark selection is {@code db/}'s and the strict API's job, not
     * {@code Grid}'s.)
     *
     * <p>So the {@code omerc} claim is asserted here with the datum stage taken out of the way: the
     * identical projection parameters on a bare {@code +ellps=clrk66} still produce the CSV's
     * easting and northing. Nothing about {@code ObliqueMercatorProjection} changed.
     *
     * <p>The row in {@code proj4-epsg.csv} <b>has been reclassified</b>: the 9.8.1 regeneration
     * records {@code EPSG:26731} as {@code refuses:COORDINATE_OUTSIDE_GRID}, which asserts this
     * same refusal by cause rather than merely asserting that the row does not pass.
     */
    @Test
    public void epsg26731IsNowRefusedByTheDatumStageNotByOmerc() {
        try {
            ProjCoordinate out = fromWgs84("EPSG:26731", 1.0, -1.0);
            fail("(1, -1) is outside every +datum=NAD27 grid and PROJ answers '* * inf'; proj4j "
                    + "returned (" + out.x + ", " + out.y + ")");
        } catch (CrsTransformException expected) {
            assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, expected.cause());
        }

        // The same projection, without the datum stage: the omerc arithmetic is unchanged.
        CoordinateReferenceSystem src = CRS_FACTORY.createFromName("EPSG:4326");
        CoordinateReferenceSystem dst = CRS_FACTORY.createFromParameters("26731-no-datum",
                "+proj=omerc +lat_0=57 +lonc=-133.6666666666667 +alpha=323.1301023611111 "
                        + "+k=0.9999 +x_0=5000000.001016002 +y_0=-5000000.001016002 +no_uoff "
                        + "+gamma=323.1301023611111 +ellps=clrk66 +units=us-ft +no_defs");
        ProjCoordinate out = new ProjCoordinate();
        CT_FACTORY.createTransform(src, dst).transform(new ProjCoordinate(1.0, -1.0), out);
        assertEquals("EPSG:26731 easting, datum stage removed", 6.9883986607415E7, out.x, 0.03048);
        assertEquals("EPSG:26731 northing, datum stage removed", -3.2630755864469E7, out.y, 0.03048);
    }

    /**
     * The five commented-out {@code ESRI:102631} rows, four of them tagged
     * <i>"Bug in Proj4J Obl Merc"</i>. <b>The bug is not in Proj4J's {@code omerc} any more — it is in
     * the shipped {@code esri} dictionary.</b>
     *
     * <p>{@code epsg/src/main/resources/proj4/nad/esri:5541} defines
     * {@code <102631>} without {@code +no_uoff}. PROJ 9.8.1's own definition
     * ({@code projinfo ESRI:102631}) has it:
     *
     * <pre>
     * +proj=omerc +no_uoff +lat_0=57 +lonc=-133.666666666667 +alpha=-36.8698976458333
     *   +gamma=-36.8698976458333 +k=0.9999 +x_0=5000000.00000001 +y_0=-5000000.00000001
     *   +ellps=GRS80 +units=us-ft
     * </pre>
     *
     * <p>Measured three ways, on the definition <em>as shipped</em>:
     * <ul>
     * <li>Proj4J now agrees with {@code cs2cs} 9.8.1 <b>to the printed digit</b> —
     *     16334242.901471 / -17134586.690995 for the first row — so the projection is right and the
     *     definition is what disagrees with the CSV.</li>
     * <li>The residual against the CSV's pinned values is a constant <b>13,718,224.7 ft easting and
     *     18,290,966.4 ft northing</b> on all five rows, which is exactly {@code 2 * u_0} resolved
     *     through the rotation — the signature of a missing {@code +no_uoff}.</li>
     * <li>Adding {@code +no_uoff} to the definition and nothing else reproduces all five CSV rows to
     *     <b>3 mm</b> against a 0.1 tolerance, which is what this test asserts.</li>
     * </ul>
     *
     * <p>So the five rows are correct and the fix is one token in a data file. That file is not in
     * this change's scope, and none of the seventeen {@code omerc} entries in the {@code esri}
     * dictionary carries {@code +no_uoff} while twenty-two of the twenty-nine in the {@code epsg}
     * dictionary do — so it wants a verified sweep rather than a single edit, and the CSV rows stay
     * commented out until that happens. This test pins the diagnosis so the sweep can be checked.
     */
    @Test
    public void esri102631NeedsNoUoffInTheShippedEsriDictionary() {
        String asShipped = "+proj=omerc +lat_0=57 +lonc=-133.6666666666667 +alpha=-36.86989764583333"
                + " +k=0.9999 +x_0=4999999.999999999 +y_0=-4999999.999999999 +ellps=GRS80"
                + " +datum=NAD83 +to_meter=0.3048006096012192 +no_defs";
        String corrected = asShipped + " +no_uoff";

        // As shipped, Proj4J reproduces cs2cs 9.8.1 on the same string: 16334242.901471 -17134586.690995.
        ProjCoordinate shipped = fromNad83(asShipped, -134.0, 55.0);
        assertEquals(16334242.901471, shipped.x, 1.0e-5);
        assertEquals(-17134586.690995, shipped.y, 1.0e-5);

        // With +no_uoff, the five CSV rows. Expected values from PROJ4_SPCS_EPSG_nad83.csv:2-6.
        double tol = 0.1;
        assertRow(corrected, -134.0, 55.0, 2616018.154, 1156379.643, tol);
        assertRow(corrected, -133.66666666666666, 57.0, 2685941.919, 1886799.668, tol);
        assertRow(corrected, -131.59595333333334, 54.65073722222222, 3124531.426, 1035343.511, tol);
        assertRow(corrected, -129.54166666666666, 54.541666666666664, 3561448.345, 1015025.876, tol);
        assertRow(corrected, -141.5, 60.5, 1276328.587, 3248159.207, tol);
    }

    private static void assertRow(String def, double lon, double lat,
                                  double x, double y, double tolerance) {
        ProjCoordinate out = fromNad83(def, lon, lat);
        assertEquals("easting at " + lon + "," + lat, x, out.x, tolerance);
        assertEquals("northing at " + lon + "," + lat, y, out.y, tolerance);
    }
}

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
package org.locationtech.proj4j.datum;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

import static org.junit.Assert.assertEquals;

/**
 * {@link GeocentricConverter#overrideWithWGS84Params()} against {@code cs2cs} 9.8.1, in every shape
 * that reaches it.
 *
 * <h2>Why this file exists</h2>
 *
 * <p>Because the method looks removable and is not. It is a five-line setter that replaces a
 * datum's ellipsoid with WGS 84 whenever {@link Datum#getTransformType()} is
 * {@link Datum#TYPE_GRIDSHIFT}, it is inherited from PROJ 5's {@code pj_transform.c}, and it had
 * been diagnosed in {@code CoordinateTransformTest} as the cause of a 42,538.9 m error on
 * {@code EPSG:4055 -> EPSG:3857} — described there as <i>"a sphere-geodetic to ellipsoid-geodetic
 * latitude conversion that PROJ does not perform, because to PROJ {@code +nadgrids=@null} means no
 * datum shift at all."</i> That reading is wrong in both halves, and the cost of acting on it would
 * have been large, so the disproof is pinned here rather than written down.
 *
 * <h2>What upstream actually does, at 9.8.1 and not merely at 5.2.0</h2>
 *
 * <p>{@code projinfo} for a {@code +nadgrids=} source into a {@code +towgs84} target emits:
 *
 * <pre>
 * +step +proj=hgridshift +grids=&#64;foo.gsb
 * +step +proj=cart +ellps=WGS84        &lt;-- not the source's own clrk66
 * +step +proj=helmert +x=-1 +y=-2 +z=-3
 * +step +inv +proj=cart +ellps=bessel
 * </pre>
 *
 * <p>The grid's own ellipsoid never appears. That is the whole invariant: <b>the output of a
 * horizontal grid shift is WGS 84 geodetic latitude and longitude by construction</b>, so the
 * geocentric leg on that side of the shift must run on WGS 84. It is the same statement as
 * {@code 5.2.0:src/pj_transform.c:874-889}, which sets {@code src_a}/{@code src_es} after applying
 * the source shift and {@code dst_a}/{@code dst_es} before the destination's inverse shift — the
 * lines this method was ported from — and it survives unchanged into 9.8.1's pipeline layer.
 *
 * <p>{@code null} is a grid that shifts by zero, not a suppression of the shift.
 * {@code 9.8.1:src/grids.cpp:2661} short-circuits {@code HorizontalShiftGridSet::open} on
 * {@code filename == "null"} to a synthetic 3x3 global {@code NullHorizontalShiftGrid} whose
 * {@code valueAt} returns {@code 0.0f, 0.0f} — no file is opened. So {@code +nadgrids=@null} still
 * changes the datum to WGS 84; it merely asserts that no lon/lat correction is needed to get there.
 * Proj4J reaches the same place by a different route: {@code Grid.java:149-174} special-cases the
 * name identically.
 *
 * <h2>The positive control</h2>
 *
 * <p>Disabling both call sites in {@code BasicCoordinateTransform} with an
 * {@code if (false && ...)} and re-running this matrix moves <b>eleven</b> of the rows below, by
 * 305 m to 30 km:
 *
 * <table>
 * <caption>measured movement with the override disabled, frozen A/B</caption>
 * <tr><th>row</th><th>northing moves by</th></tr>
 * <tr><td>{@link #webMercatorFromWgs84IsTheReasonThisMethodExists()}</td><td><b>-25,380.283 m</b></td></tr>
 * <tr><td>{@link #popularVisualisationSphereToWebMercator()}</td><td>+42,538.889 m</td></tr>
 * <tr><td>{@link #targetSphereWithNullGrid()}</td><td>-30,211.556 m</td></tr>
 * <tr><td>{@link #bothSidesNullGrid()}</td><td>-429.722 m</td></tr>
 * <tr><td>{@link #sevenParamSourceToNullGridTarget()}</td><td>+363.988 m</td></tr>
 * <tr><td>{@link #nullGridSourceToSevenParamTarget()}</td><td>-364.006 m</td></tr>
 * <tr><td>{@link #helmertSourceToNullGridTarget()}</td><td>+335.927 m</td></tr>
 * <tr><td>{@link #nullGridSourceToHelmertTarget()}</td><td>-335.864 m</td></tr>
 * <tr><td>{@link #wgs84SourceToNullGridTarget()}</td><td>+335.894 m</td></tr>
 * </table>
 *
 * <p>The first is the one that matters: {@code EPSG:3857} is {@code +nadgrids=@null} on an
 * {@code a=b=6378137} <em>sphere</em>, so without the override every Web Mercator transform in the
 * library round-trips WGS 84 latitudes through a sphere. The method is not a relic.
 *
 * <p>Only {@link #helmertSourceToPlainEllipsoidTarget()} and the two GRS80 rows are unmoved by
 * disabling it, and they are here precisely as the discriminator: a check that cannot fail proves
 * nothing.
 *
 * <h2>References</h2>
 *
 * <p>Every expected value below is {@code cs2cs} 9.8.1 on the same parameter string, at
 * {@code -d 15}, with {@code +type=crs} appended to both sides so the strings are read as CRSs
 * rather than as operations — without it {@code projinfo} answers <i>"not a CRS"</i> and
 * {@code cs2cs} silently takes a different path. Bracketed figures are the measured Proj4J residual;
 * each tolerance sits just above its own.
 */
public class NadgridsWgs84OverrideTest {

    private static final CRSFactory CRS = new CRSFactory();
    private static final CoordinateTransformFactory TRANSFORMS = new CoordinateTransformFactory();

    /** The verbatim {@code proj4/nad/epsg} entry for {@code EPSG:4055}, Popular Visualisation CRS. */
    private static final String EPSG_4055 =
            "+proj=longlat +a=6378137 +b=6378137 +towgs84=0,0,0,0,0,0,0 +no_defs";

    /** The verbatim {@code proj4/nad/epsg} entry for {@code EPSG:3857}. Identical to 9.8.1's own
     * {@code projinfo EPSG:3857 -o PROJ} export apart from {@code 0.0} for {@code 0}. */
    private static final String EPSG_3857 =
            "+proj=merc +a=6378137 +b=6378137 +lat_ts=0.0 +lon_0=0.0 +x_0=0.0 +y_0=0 +k=1.0"
                    + " +units=m +nadgrids=@null +wktext +no_defs";

    private void check(String src, String tgt, double x, double y,
                       double expectX, double expectY, double tolerance) {
        CoordinateTransform t = TRANSFORMS.createTransform(
                CRS.createFromParameters("src", src), CRS.createFromParameters("tgt", tgt));
        ProjCoordinate out = new ProjCoordinate();
        t.transform(new ProjCoordinate(x, y), out);
        assertEquals("easting, " + src + " -> " + tgt, expectX, out.x, tolerance);
        assertEquals("northing, " + src + " -> " + tgt, expectY, out.y, tolerance);
    }

    // ==============================================================================================
    // The row the whole method hangs on.
    // ==============================================================================================

    /**
     * {@code EPSG:4326 -> EPSG:3857}, the most common transform there is, and the one that breaks
     * by <b>25,380 m</b> the moment {@code overrideWithWGS84Params} stops being called.
     *
     * <p>{@code EPSG:3857}'s datum is an {@code a=b=6378137} sphere carrying {@code +nadgrids=@null},
     * so it reports {@link Datum#TYPE_GRIDSHIFT} and is not {@code ==} to WGS 84. The override is
     * what restores the WGS 84 ellipsoid on the target side of the geocentric leg, at which point
     * the two converters compare equal and {@code BasicCoordinateTransform} skips the leg entirely —
     * which is the correct answer, and the same one 9.8.1 gives by resolving the string to
     * <i>WGS 84 / Pseudo-Mercator</i> with {@code BASEGEOGCRS["WGS 84"]}.
     */
    @Test
    public void webMercatorFromWgs84IsTheReasonThisMethodExists() {
        check("+proj=longlat +datum=WGS84 +no_defs", EPSG_3857,
                103.095703, 36.421282,
                11476561.160934567451477, 4358745.039558878168464, 1.0e-9);   // [0, 0 m]
    }

    // ==============================================================================================
    // EPSG:4055 -> EPSG:3857, the row that was read as a 42.5 km defect.
    // ==============================================================================================

    /**
     * The headline row, and the disproof. Proj4J is <b>2.3e-7 m</b> from {@code cs2cs} 9.8.1 on
     * these strings, not 42,538.9 m: the reference the gap was measured against was
     * {@code cs2cs EPSG:4055 EPSG:3857}, which reads {@code proj.db}, where {@code EPSG:4055} is
     * {@code +proj=longlat +R=6378137} with <b>no {@code +towgs84}</b> and where both the CRS and
     * its transformation to WGS 84 (EPSG:15973) are deprecated, so 9.8.1 answers with a ballpark
     * {@code +proj=noop}. Proj4J reads {@code proj4/nad/epsg}, whose entry carries the zero
     * {@code +towgs84}, and 9.8.1 given that same string builds
     * {@code +proj=cart +R=6378137} then {@code +inv +proj=cart +ellps=WGS84} and agrees.
     */
    @Test
    public void popularVisualisationSphereToWebMercator() {
        check(EPSG_4055, EPSG_3857, 0.0, -85.01794318500549,
                0.0, -20037366.780895609408617, 1.0e-6);                      // [0, 2.3e-7 m]
    }

    /**
     * The same pair with {@code +towgs84} removed from the source: both engines drop to
     * {@code -19994827.892...}, the plain spherical Mercator value. Sole cause, isolated — the
     * 42,538.9 m is entirely this one token, and nothing about {@code @null} contributes to it.
     */
    @Test
    public void popularVisualisationSphereWithoutTowgs84() {
        check("+proj=longlat +a=6378137 +b=6378137 +no_defs", EPSG_3857,
                0.0, -85.01794318500549,
                0.0, -19994827.892149358987808, 1.0e-8);                      // [0, 1.0e-9 m]
        // and the +R spelling, which is what projinfo EPSG:4055 -o PROJ emits at 9.8.1
        check("+proj=longlat +R=6378137 +no_defs", EPSG_3857,
                0.0, -85.01794318500549,
                0.0, -19994827.892149358987808, 1.0e-8);                      // [0, 1.0e-9 m]
    }

    /**
     * The other half of the isolation: source unchanged, {@code +nadgrids=@null} removed from the
     * target. Also {@code -19994827.892...}, in both engines. So each cause alone accounts for the
     * whole 42,538.9 m and they are not two errors summing to one — the target's
     * {@link Datum#TYPE_GRIDSHIFT} and the source's {@code +towgs84} are jointly necessary, and
     * 9.8.1 tracks Proj4J through both switches.
     */
    @Test
    public void popularVisualisationSphereToMercatorWithoutNullGrid() {
        check(EPSG_4055,
                "+proj=merc +a=6378137 +b=6378137 +lat_ts=0.0 +lon_0=0.0 +x_0=0.0 +y_0=0 +k=1.0"
                        + " +units=m",
                0.0, -85.01794318500549,
                0.0, -19994827.892149358987808, 1.0e-8);                      // [0, 1.0e-9 m]
    }

    // ==============================================================================================
    // The override on a genuinely non-WGS84 ellipsoid, both directions, which is the blast radius
    // the sphere case does not cover. Clarke 1866 and Bessel are the ellipsoids Datum.NAD27 and
    // Datum.POTSDAM carry, i.e. the 526 shipped registry codes that can reach TYPE_GRIDSHIFT.
    // ==============================================================================================

    /** Target side: Helmert source, {@code +nadgrids=@null} on Clarke 1866. */
    @Test
    public void helmertSourceToNullGridTarget() {
        check("+proj=longlat +ellps=bessel +towgs84=100,200,300",
                "+proj=merc +ellps=clrk66 +nadgrids=@null", 0.0, 45.0,
                282.399947356742189, 5591314.559088424779475, 1.0e-8);        // [6e-14, 3.8e-9 m]
    }

    /** Source side: {@code +nadgrids=@null} on Clarke 1866 into a Helmert target. */
    @Test
    public void nullGridSourceToHelmertTarget() {
        check("+proj=longlat +ellps=clrk66 +nadgrids=@null",
                "+proj=merc +ellps=bessel +towgs84=100,200,300", 0.0, 45.0,
                -282.342447250643829, 5590444.246635736897588, 1.0e-8);       // [3e-14, 4.9e-9 m]
    }

    /**
     * The discriminator. Same source, same target ellipsoid, {@code +nadgrids=@null} replaced by a
     * zero Helmert — so the override is <em>not</em> triggered and this row is the one that does not
     * move when it is disabled. Without it the disabling experiment above could not be told from a
     * broken measurement.
     */
    @Test
    public void helmertSourceToPlainEllipsoidTarget() {
        check("+proj=longlat +ellps=bessel +towgs84=100,200,300",
                "+proj=merc +ellps=clrk66 +towgs84=0,0,0", 0.0, 45.0,
                282.399947356742246, 5591650.486387284472585, 1.0e-8);        // [0, 2.8e-9 m]
    }

    /** Seven-parameter source — the {@code potsdam} shape — into a {@code @null} Clarke 1866. */
    @Test
    public void sevenParamSourceToNullGridTarget() {
        check("+proj=longlat +ellps=bessel +towgs84=598.1,73.7,418.2,0.202,0.045,-2.455,6.7",
                "+proj=merc +ellps=clrk66 +nadgrids=@null", 9.0, 50.0,
                1001770.605658558779396, 6413032.439889852888882, 1.0e-8);    // [<1e-9, 3.1e-9 m]
    }

    /** ...and its mirror. */
    @Test
    public void nullGridSourceToSevenParamTarget() {
        check("+proj=longlat +ellps=clrk66 +nadgrids=@null",
                "+proj=merc +ellps=bessel +towgs84=598.1,73.7,418.2,0.202,0.045,-2.455,6.7",
                9.0, 50.0,
                1001874.918986174743623, 6413076.714977690018713, 1.0e-8);    // [0, 0 m]
    }

    /**
     * A {@code @null} target on a sphere that is <em>not</em> 6378137, so 9.8.1's
     * {@code io.cpp:11998} Web Mercator special case — which recognises
     * {@code merc + a==b + lat_ts=0 + k=1 + nadgrids=@null} and sets {@code ignoreNadgrids_} — does
     * not fire and the general {@code BoundCRS} path is taken instead. The override still matches:
     * replacing a <em>sphere</em> with the WGS 84 ellipsoid on the grid side of the leg is exactly
     * what 9.8.1 does, on a sphere as on any other ellipsoid.
     */
    @Test
    public void targetSphereWithNullGrid() {
        check("+proj=longlat +ellps=bessel +towgs84=100,200,300",
                "+proj=merc +a=6371000 +b=6371000 +nadgrids=@null", 0.0, 45.0,
                282.080878506817271, 5615525.345617476850748, 1.0e-8);        // [3e-14, 3.9e-9 m]
    }

    /**
     * Both sides {@code TYPE_GRIDSHIFT} on different ellipsoids. Both converters are overridden to
     * WGS 84, compare equal, and the geocentric leg is skipped — {@code 5.2.0:pj_transform.c:892}'s
     * condition, and correct: two grid shifts to and from WGS 84 compose without a cart round trip.
     * 9.8.1 emits {@code hgridshift} then {@code +inv +proj=hgridshift} with nothing between them.
     */
    @Test
    public void bothSidesNullGrid() {
        check("+proj=longlat +ellps=clrk66 +nadgrids=@null",
                "+proj=merc +ellps=bessel +nadgrids=@null", 0.0, 45.0,
                0.0, 5590737.771429197862744, 1.0e-9);                        // [0, 0 m]
    }

    /** A source that is already exactly WGS 84: the override collapses the leg, as it should. */
    @Test
    public void wgs84SourceToNullGridTarget() {
        check("+proj=longlat +ellps=WGS84 +towgs84=0,0,0",
                "+proj=merc +ellps=clrk66 +nadgrids=@null", 0.0, 45.0,
                0.0, 5591021.003795098513365, 1.0e-9);                        // [0, 1.3e-11 m]
    }

    // ==============================================================================================
    // A KNOWN DIVERGENCE, recorded rather than hidden, and demonstrably not about @null.
    // ==============================================================================================

    /**
     * <b>Proj4J is 1.48e-4 m from 9.8.1 whenever a GRS80 datum carries an all-zero Helmert</b>, and
     * this pair of rows exists to prove that has nothing to do with {@code @null} or with
     * {@code overrideWithWGS84Params}.
     *
     * <p>9.8.1 treats {@code +ellps=GRS80 +towgs84=0,0,0} as <em>already</em> WGS 84 and deletes the
     * transformation outright — {@code projinfo -s '+proj=longlat +ellps=GRS80 +towgs84=0,0,0' -t
     * EPSG:4326} answers with a bare {@code +proj=axisswap +order=2,1} — whereas the same query on
     * {@code +ellps=clrk66 +towgs84=0,0,0} keeps the full {@code cart}/{@code inv cart} pair. Proj4J
     * performs the GRS80-to-WGS84 geocentric round trip literally, and the 1/f difference of 1.5e-9
     * between the two ellipsoids is worth 0.148 mm of northing at 45 deg.
     *
     * <p>The two rows are identical except that the first target carries {@code +nadgrids=@null} and
     * the second a zero Helmert. <b>Both diverge by the same 1.4799e-4 m</b>, and only the first is
     * moved by disabling the override — so the divergence is attributable to the GRS80 handling
     * alone. Fixing it means teaching {@code Datum}/{@code BasicCoordinateTransform} that
     * {@link Datum#TYPE_WGS84} implies the WGS 84 ellipsoid on the geocentric leg, which would move
     * every GRS80-with-null-Helmert row in the corpus and needs its own measurement; it is not part
     * of the {@code @null} story. Tolerances here admit the gap and name it.
     */
    @Test
    public void grs80WithNullHelmertDivergesByAboutAMillimetreTenth() {
        check("+proj=longlat +ellps=GRS80 +towgs84=0,0,0",
                "+proj=merc +ellps=clrk66 +nadgrids=@null", 0.0, 45.0,
                0.0, 5591021.003795098513365, 2.0e-4);                        // [0, -1.4799e-4 m]
        check("+proj=longlat +ellps=GRS80 +towgs84=0,0,0",
                "+proj=merc +ellps=clrk66 +towgs84=0,0,0", 0.0, 45.0,
                0.0, 5591356.897729491814971, 2.0e-4);                        // [0, -1.4799e-4 m]
    }
}

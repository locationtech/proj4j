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
package org.locationtech.proj4j.parser.datumgrids;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * That {@code NAD27 -> NAD83} really shifts, and by the right amount, at two points the
 * defect report named: San Francisco and Kansas.
 *
 * <h2>Where the reference values came from</h2>
 *
 * <p><b>PROJ 9.8.1 itself</b> — the Homebrew build, {@code cs2cs} self-reporting
 * {@code Rel. 9.8.1, April 10th, 2026} — reading <b>the same grid bytes this repository
 * ships</b>. The vendored {@code proj4j-data/grids/conus} is byte-identical to
 * {@code 9.8.1:data/tests/conus} (md5 {@code b3ddb364b185403eae8273d5066329e3},
 * confirmed against {@code git -C PROJ show 9.8.1:data/tests/conus}), so a disagreement
 * here can only be Proj4J's CTABLE V2 reader, its interpolation or its datum plumbing —
 * never a difference of grid data or of NADCON model version. Published NGS tables would
 * not isolate that, because modern NGS uses NADCON 5.
 *
 * <pre>
 * $ printf -- '-122.416667 37.783333\n-97.5 39.0\n' \
 *     | cs2cs -f '%.10f' +proj=longlat +ellps=clrk66 +nadgrids=conus \
 *                    +to +proj=longlat +datum=NAD83
 * -122.4177492097  37.7832622344
 *  -97.5003073411  38.9999993489
 *
 * $ printf -- '-122.416667 37.783333\n-97.5 39.0\n' \
 *     | cs2cs -f '%.10f' +proj=longlat +datum=NAD83 \
 *                    +to +proj=longlat +ellps=clrk66 +nadgrids=conus
 * -122.4155848029  37.7834037741
 *  -97.4996926694  39.0000006530
 * </pre>
 *
 * <h2>The metric</h2>
 *
 * <p>Distances below are <b>metres on the GRS80 ellipsoid</b>, computed from the two
 * lon/lat pairs through the local radii of curvature at their mid-latitude — not a
 * Euclidean distance between degree values, and not an angular tolerance applied to a
 * projected result. Cross-checked against PROJ's own geodesic:
 *
 * <pre>
 * $ printf -- '37.783333 -122.416667 37.7832622344 -122.4177492097\n39.0 -97.5 38.9999993489 -97.5003073411\n' \
 *     | geod -f '%.6f' -I +ellps=GRS80
 * ... 95.655        # San Francisco
 * ... 26.624        # Kansas
 * </pre>
 *
 * <p>{@link #localCurvatureMetres} reproduces those to 0.001 m at this scale, which is
 * three orders of magnitude finer than the error under test.
 *
 * <h2>What "the input unchanged" looked like</h2>
 *
 * <p>Before the parser stopped calling {@code setGrids(null)} on the {@code Datum.NAD27}
 * singleton, {@code EPSG:4267 -> EPSG:4269} returned its input verbatim: 95.66 m of
 * error at San Francisco, 26.62 m in Kansas, finite and plausible and unflagged. The
 * downstream report that first surfaced it diagnosed it as missing grid data; it was a
 * code path.
 */
public class Nad27ToNad83ShiftTest {

    // --- Points and PROJ 9.8.1 reference values -----------------------------

    private static final double[] SAN_FRANCISCO = {-122.416667, 37.783333};
    private static final double[] KANSAS = {-97.5, 39.0};

    private static final double[] SF_TO_NAD83 = {-122.4177492097, 37.7832622344};
    private static final double[] KANSAS_TO_NAD83 = {-97.5003073411, 38.9999993489};

    private static final double[] SF_TO_NAD27 = {-122.4155848029, 37.7834037741};
    private static final double[] KANSAS_TO_NAD27 = {-97.4996926694, 39.0000006530};

    /** Geodesic magnitude of the forward shift, from {@code geod +ellps=GRS80}. */
    private static final double SF_SHIFT_METRES = 95.655;
    private static final double KANSAS_SHIFT_METRES = 26.624;

    /**
     * {@code cs2cs} printed 10 decimals of a degree, i.e. rounded at about 0.01 mm.
     * 1e-9 degrees is roughly 0.1 mm — tight enough that a single-node indexing slip
     * (0.25 degrees between nodes) could not hide in it.
     */
    private static final double TOL_DEG = 1e-9;

    /** GRS80, for the metric only. */
    private static final double GRS80_A = 6378137.0;
    private static final double GRS80_ES = 0.006694380022900787;

    private final CRSFactory crsFactory = new CRSFactory();
    private final CoordinateTransformFactory transformFactory = new CoordinateTransformFactory();

    // ------------------------------------------------------------------
    // The headline: a real shift, via the EPSG codes the report named
    // ------------------------------------------------------------------

    @Test
    public void epsg4267ToEpsg4269ShiftsSanFranciscoByNinetyFiveMetres() {
        ProjCoordinate out = transform("EPSG:4267", "EPSG:4269", SAN_FRANCISCO);

        assertEquals("longitude", SF_TO_NAD83[0], out.x, TOL_DEG);
        assertEquals("latitude", SF_TO_NAD83[1], out.y, TOL_DEG);
        assertEquals("shift magnitude, metres on GRS80",
                SF_SHIFT_METRES,
                localCurvatureMetres(SAN_FRANCISCO[0], SAN_FRANCISCO[1], out.x, out.y),
                0.001);
    }

    @Test
    public void epsg4267ToEpsg4269ShiftsKansasByTwentySixMetres() {
        ProjCoordinate out = transform("EPSG:4267", "EPSG:4269", KANSAS);

        assertEquals("longitude", KANSAS_TO_NAD83[0], out.x, TOL_DEG);
        assertEquals("latitude", KANSAS_TO_NAD83[1], out.y, TOL_DEG);
        assertEquals("shift magnitude, metres on GRS80",
                KANSAS_SHIFT_METRES,
                localCurvatureMetres(KANSAS[0], KANSAS[1], out.x, out.y),
                0.001);
    }

    /**
     * The negative form, stated separately so that a future regression reads as
     * "the input came back unchanged" rather than as an opaque tolerance miss. This is
     * the assertion the 1.4.3 tree failed.
     */
    @Test
    public void epsg4267ToEpsg4269DoesNotReturnTheInputUnchanged() {
        for (double[] point : new double[][]{SAN_FRANCISCO, KANSAS}) {
            ProjCoordinate out = transform("EPSG:4267", "EPSG:4269", point);
            double moved = localCurvatureMetres(point[0], point[1], out.x, out.y);
            assertTrue("NAD27 -> NAD83 moved (" + point[0] + ", " + point[1] + ") by only "
                            + moved + " m; the grid shift was not applied",
                    moved > 10.0);
        }
    }

    /**
     * The whole defect was that the <i>first</i> parse poisoned the singleton, so
     * repeating the transform is the test that would have caught it. Every repetition
     * must give the same answer, and a freshly built transform must agree with a reused
     * one.
     */
    @Test
    public void theShiftIsStableAcrossRepeatedAndFreshTransforms() {
        CoordinateTransform reused = transformFactory.createTransform(
                crsFactory.createFromName("EPSG:4267"), crsFactory.createFromName("EPSG:4269"));

        for (int i = 0; i < 5; i++) {
            ProjCoordinate viaReused = new ProjCoordinate();
            reused.transform(new ProjCoordinate(SAN_FRANCISCO[0], SAN_FRANCISCO[1]), viaReused);
            assertEquals("longitude on reuse " + i, SF_TO_NAD83[0], viaReused.x, TOL_DEG);

            ProjCoordinate viaFresh = transform("EPSG:4267", "EPSG:4269", SAN_FRANCISCO);
            assertEquals("a fresh transform must agree with a reused one on iteration " + i,
                    viaReused.x, viaFresh.x, 0.0);
            assertEquals(viaReused.y, viaFresh.y, 0.0);
        }
    }

    // ------------------------------------------------------------------
    // The same thing without the EPSG database, and the inverse
    // ------------------------------------------------------------------

    /**
     * Stated as proj-strings as well as EPSG codes, so that a change to
     * {@code proj4-epsg.csv} cannot silently turn this into a different test.
     * {@code +datum=NAD27} must reach the grid without an explicit {@code +nadgrids},
     * because that is precisely what {@code EPSG:4267} does not have and what PROJ
     * supplies by appending the datum's own {@code nadgrids=@conus,...} definition
     * ({@code datum_set.cpp}).
     */
    @Test
    public void namedDatumNad27ReachesTheGridWithoutAnExplicitNadgrids() {
        ProjCoordinate out = transformDefs(
                "+proj=longlat +datum=NAD27 +no_defs",
                "+proj=longlat +datum=NAD83 +no_defs",
                SAN_FRANCISCO);
        assertEquals(SF_TO_NAD83[0], out.x, TOL_DEG);
        assertEquals(SF_TO_NAD83[1], out.y, TOL_DEG);
    }

    /** An explicit {@code +nadgrids=@conus} must agree with the named datum exactly. */
    @Test
    public void explicitNadgridsConusAgreesWithTheNamedDatum() {
        ProjCoordinate viaDatum = transformDefs(
                "+proj=longlat +datum=NAD27 +no_defs",
                "+proj=longlat +datum=NAD83 +no_defs", KANSAS);
        ProjCoordinate viaGrids = transformDefs(
                "+proj=longlat +ellps=clrk66 +nadgrids=@conus +no_defs",
                "+proj=longlat +datum=NAD83 +no_defs", KANSAS);
        assertEquals(viaDatum.x, viaGrids.x, 0.0);
        assertEquals(viaDatum.y, viaGrids.y, 0.0);
    }

    /**
     * {@code NAD83 -> NAD27}. PROJ's inverse grid shift is iterative, so this is not
     * the algebraic negation of the forward shift and has its own reference values.
     */
    @Test
    public void epsg4269ToEpsg4267AppliesTheIterativeInverseShift() {
        ProjCoordinate sf = transform("EPSG:4269", "EPSG:4267", SAN_FRANCISCO);
        assertEquals(SF_TO_NAD27[0], sf.x, TOL_DEG);
        assertEquals(SF_TO_NAD27[1], sf.y, TOL_DEG);

        ProjCoordinate ks = transform("EPSG:4269", "EPSG:4267", KANSAS);
        assertEquals(KANSAS_TO_NAD27[0], ks.x, TOL_DEG);
        assertEquals(KANSAS_TO_NAD27[1], ks.y, TOL_DEG);
    }

    /** Forward then inverse must return to the start, to well under a millimetre. */
    @Test
    public void theShiftRoundTrips() {
        for (double[] point : new double[][]{SAN_FRANCISCO, KANSAS}) {
            ProjCoordinate there = transform("EPSG:4267", "EPSG:4269", point);
            ProjCoordinate back = transform("EPSG:4269", "EPSG:4267",
                    new double[]{there.x, there.y});
            assertEquals("round trip must return to the start, metres on GRS80",
                    0.0, localCurvatureMetres(point[0], point[1], back.x, back.y), 0.001);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ProjCoordinate transform(String sourceCode, String targetCode, double[] lonLat) {
        return run(crsFactory.createFromName(sourceCode), crsFactory.createFromName(targetCode),
                lonLat);
    }

    private ProjCoordinate transformDefs(String sourceDef, String targetDef, double[] lonLat) {
        return run(crsFactory.createFromParameters("source", sourceDef),
                crsFactory.createFromParameters("target", targetDef), lonLat);
    }

    private ProjCoordinate run(CoordinateReferenceSystem source,
                               CoordinateReferenceSystem target,
                               double[] lonLat) {
        ProjCoordinate out = new ProjCoordinate();
        transformFactory.createTransform(source, target)
                .transform(new ProjCoordinate(lonLat[0], lonLat[1]), out);
        return out;
    }

    /**
     * Distance in metres on the GRS80 ellipsoid between two lon/lat pairs given in
     * degrees, via the meridional and prime-vertical radii of curvature at their
     * mid-latitude. Exact enough for offsets of this size — it agrees with
     * {@code geod +ellps=GRS80} to 0.001 m at 95 m — and, unlike a degree-space
     * Euclidean distance, it cannot silently inflate a longitude difference by the
     * ~111 km/degree factor.
     */
    static double localCurvatureMetres(double lon1, double lat1, double lon2, double lat2) {
        double phi = Math.toRadians((lat1 + lat2) / 2.0);
        double sinPhi = Math.sin(phi);
        double w = 1.0 - GRS80_ES * sinPhi * sinPhi;
        double meridional = GRS80_A * (1.0 - GRS80_ES) / (w * Math.sqrt(w));
        double primeVertical = GRS80_A / Math.sqrt(w);
        double northing = meridional * Math.toRadians(lat2 - lat1);
        double easting = primeVertical * Math.cos(phi) * Math.toRadians(lon2 - lon1);
        return Math.sqrt(easting * easting + northing * northing);
    }
}

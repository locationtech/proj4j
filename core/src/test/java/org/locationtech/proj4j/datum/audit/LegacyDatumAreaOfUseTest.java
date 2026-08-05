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
package org.locationtech.proj4j.datum.audit;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * What each built-in datum is worth, measured at a point <em>inside that datum's own area
 * of use</em> and pinned to {@code cs2cs} 9.8.1.
 *
 * <h2>Why the probe point moves per datum</h2>
 *
 * <p>A datum shift is a rigid-body motion of the whole Earth, so its error at a fixed
 * probe tells you almost nothing about its error where the datum is actually used. That is
 * the structural weakness of {@code proj4-epsg.csv}, which exercises hundreds of
 * definitions at whatever coordinate happens to be on the row. Here OSGB36 is measured in
 * Cheshire and in Edinburgh, carthage in Tunis and in the Sahara, potsdam on the Rhine and
 * on the 9th meridian, nzgd49 in Wellington, ire65 in Dublin, hermannskogel in Salzburg,
 * GGRS87 in Athens.
 *
 * <h2>How the reference was taken, and how it was not</h2>
 *
 * <p>Every expected value below is {@code cs2cs} 9.8.1 (Rel. April 10th 2026), never
 * Proj4J's own output. {@code cs2cs} is the right tool because both sides here are full
 * CRSs; {@code proj} would give a bare projection and a different answer.
 *
 * <p>The reference target is written as {@code +ellps=... +towgs84=...} rather than
 * {@code +datum=...}, and that distinction is load-bearing rather than cosmetic. PROJ 9.x
 * resolves {@code +datum=<name>} against {@code proj.db}, recognises the EPSG datum behind
 * the name, and then picks the <i>best available</i> EPSG transformation — which is often a
 * grid, not the {@code towgs84} in {@code src/datums.cpp} at all. Three of the ten are
 * affected:
 * <table>
 * <caption>{@code cs2cs} operation chosen for {@code +datum=}, versus the legacy table</caption>
 * <tr><th>datum</th><th>what {@code +datum=} selects</th><th>gap to the legacy Helmert</th></tr>
 * <tr><td>OSGB36</td><td>{@code uk_os_OSTN15_NTv2_OSGBtoETRS.tif} (<i>OSGB36 to WGS 84 (9)</i>, 1 m)</td>
 *     <td>0.039 m E, <b>1.784 m N</b></td></tr>
 * <tr><td>potsdam</td><td>{@code de_adv_BETA2007.tif} (<i>DHDN to WGS 84 (4)</i>, 1 m)</td>
 *     <td>0.395 m E, 0.132 m N</td></tr>
 * <tr><td>nzgd49</td><td>{@code nz_linz_nzgd2kgrid0005.tif} (<i>NZGD49 to WGS 84 (2)</i>)</td>
 *     <td>2.248 m E, 1.313 m N</td></tr>
 * </table>
 * Those gaps are grid-versus-Helmert residuals. They are not parameter defects and cannot
 * be closed by editing {@code Datum}; closing them needs the grids. Pinning a
 * {@code +datum=}-derived number as the target for a Helmert implementation would have
 * attributed 1.784 m of OSTN15 residual to a 3 mm transcription slip.
 *
 * <p>Reference commands have the shape
 * <pre>
 * echo "&lt;lon&gt; &lt;lat&gt;" | cs2cs -f '%.9f' +proj=longlat +datum=WGS84 \
 *     +to &lt;target&gt; +units=m +no_defs
 * </pre>
 *
 * @see LegacyDatumTableTest for the parameter-level audit these numbers measure
 */
public class LegacyDatumAreaOfUseTest {

    private static final CRSFactory CRS = new CRSFactory();
    private static final CoordinateTransformFactory TRANSFORMS = new CoordinateTransformFactory();

    private static final String WGS84 = "+proj=longlat +datum=WGS84 +no_defs";

    /**
     * Proj4J's geocentric Helmert against PROJ's {@code cart + helmert + cart} pipeline.
     * Both are the same closed form in double precision; the observed agreement is better
     * than a nanometre, and 1e-5 m still leaves a factor of 300 of headroom below the
     * smallest defect measured here (3.0 mm).
     */
    private static final double TOL = 1.0e-5;

    private static ProjCoordinate to(String target, double lon, double lat) {
        ProjCoordinate out = new ProjCoordinate();
        TRANSFORMS.createTransform(CRS.createFromParameters("wgs84", WGS84),
                        CRS.createFromParameters("target", target))
                .transform(new ProjCoordinate(lon, lat), out);
        return out;
    }

    private static void check(String what, String target, double lon, double lat,
                              double expectedX, double expectedY) {
        ProjCoordinate got = to(target, lon, lat);
        assertEquals(what + " easting", expectedX, got.x, TOL);
        assertEquals(what + " northing", expectedY, got.y, TOL);
    }

    // ================================================================ changed datums

    /**
     * <b>OSGB36, changed.</b> EPSG:27700, OSGB 1936 / British National Grid, at
     * 53.35169&deg;N 2.03017&deg;W — Cheshire, the point the defect was first reported at.
     * <table>
     * <caption>easting / northing</caption>
     * <tr><th></th><th>E</th><th>N</th></tr>
     * <tr><td>before, {@code 542.06,0.15,0.247,0.842,-20.489}</td>
     *     <td>398089.000827863</td><td>383867.000380436</td></tr>
     * <tr><td>after, PROJ's {@code 542.060,0.1502,0.2470,0.8421,-20.4894}</td>
     *     <td>398089.003912952</td><td>383867.000589373</td></tr>
     * <tr><td>&Delta;</td><td>3.085 mm</td><td>0.209 mm</td></tr>
     * </table>
     * <p>
     * For the avoidance of the mis-attribution that started this audit: {@code cs2cs
     * +datum=OSGB36} gives 398088.964408 / 383865.216031 at this point, 1.784 m of
     * northing away, and that 1.784 m is the OSTN15 grid versus <i>any</i> Helmert. It is
     * not what the truncation cost. See the class javadoc.
     */
    @Test
    public void osgb36InCheshire() {
        check("OSGB36 EPSG:27700 Cheshire",
                "+proj=tmerc +lat_0=49 +lon_0=-2 +k=0.9996012717 +x_0=400000 +y_0=-100000"
                        + " +datum=OSGB36 +units=m +no_defs",
                -2.0301713578021983, 53.35168607080468,
                398089.003912952, 383867.000589373);

        // The size of the correction, as a live fact rather than a comment.
        ProjCoordinate got = to("+proj=tmerc +lat_0=49 +lon_0=-2 +k=0.9996012717 +x_0=400000"
                + " +y_0=-100000 +datum=OSGB36 +units=m +no_defs",
                -2.0301713578021983, 53.35168607080468);
        assertEquals("the truncated parameters were 3.085 mm out in easting",
                0.003085089, got.x - 398089.000827863, 1.0e-5);
    }

    /**
     * <b>OSGB36, changed.</b> Second point, Edinburgh (55.9533&deg;N 3.1883&deg;W), to show
     * the correction is not a local accident. Before 325897.218139444 / 674001.201609757;
     * after (and {@code cs2cs}) 325897.221501991 / 674001.201887040 — 3.363 mm of easting.
     * The largest displacement found anywhere in Great Britain was 3.5 mm, at 7.5&deg;W
     * 57&deg;N.
     */
    @Test
    public void osgb36InEdinburgh() {
        check("OSGB36 EPSG:27700 Edinburgh",
                "+proj=tmerc +lat_0=49 +lon_0=-2 +k=0.9996012717 +x_0=400000 +y_0=-100000"
                        + " +datum=OSGB36 +units=m +no_defs",
                -3.1883, 55.9533,
                325897.221501991, 674001.201887040);
    }

    /**
     * <b>carthage, changed.</b> EPSG:22391, Carthage / Nord Tunisie, at Tunis
     * (36.8065&deg;N 10.1815&deg;E). The change is the ellipsoid, {@code clrk80} &rarr;
     * {@code clrk80ign}, so it moves the projection as well as the shift.
     * <table>
     * <caption>easting / northing</caption>
     * <tr><th></th><th>E</th><th>N</th></tr>
     * <tr><td>before, {@code +ellps=clrk80}</td>
     *     <td>525062.209391343</td><td>389332.428727402</td></tr>
     * <tr><td>after, PROJ's {@code +ellps=clrk80ign}</td>
     *     <td>525062.209579013</td><td>389332.449180218</td></tr>
     * <tr><td>&Delta;</td><td>0.188 mm</td><td>20.453 mm</td></tr>
     * </table>
     * Here {@code cs2cs +datum=carthage} agrees with the legacy form exactly, because
     * <i>Carthage to WGS 84 (1)</i> is the same three-parameter shift.
     */
    @Test
    public void carthageInTunis() {
        check("carthage EPSG:22391 Tunis",
                "+proj=lcc +lat_1=36 +lat_0=36 +lon_0=9.9 +k_0=0.999625544 +x_0=500000"
                        + " +y_0=300000 +datum=carthage +units=m +no_defs",
                10.1815, 36.8065,
                525062.209579013, 389332.449180218);

        ProjCoordinate got = to("+proj=lcc +lat_1=36 +lat_0=36 +lon_0=9.9 +k_0=0.999625544"
                + " +x_0=500000 +y_0=300000 +datum=carthage +units=m +no_defs",
                10.1815, 36.8065);
        assertEquals("clrk80 was 20.45 mm out in northing",
                0.020452816, got.y - 389332.428727402, 1.0e-5);
    }

    /**
     * <b>carthage, changed.</b> EPSG:22392, Carthage / Sud Tunisie, at 31&deg;N 9&deg;E —
     * 2.3&deg; south of the zone's {@code lat_0}, where the ellipsoid change presents
     * differently: before 413959.533335781 / 45170.036589029, after 413959.532678930 /
     * 45170.053716585. So 0.66 mm of easting and 17.13 mm of northing, in the opposite
     * easting direction to Tunis. A single probe point would have characterised neither.
     */
    @Test
    public void carthageInTheSouth() {
        check("carthage EPSG:22392 Sud Tunisie",
                "+proj=lcc +lat_1=33.3 +lat_0=33.3 +lon_0=9.9 +k_0=0.999625769 +x_0=500000"
                        + " +y_0=300000 +datum=carthage +units=m +no_defs",
                9.0, 31.0,
                413959.532678930, 45170.053716585);
    }

    /**
     * <b>potsdam, changed in definition, unchanged in value.</b> EPSG:31467, DHDN /
     * 3-degree Gauss-Kruger zone 3, at 50&deg;N 9&deg;E. PROJ's uncommented definition is
     * {@code nadgrids=@BETA2007.gsb} and its commented-out one is the Helmert; both are now
     * declared. With the grid unavailable the Helmert applies, and that is bit-for-bit what
     * {@code cs2cs +datum=potsdam} produces when {@code de_adv_BETA2007.tif} is hidden from
     * {@code PROJ_DATA}: 3500074.525405575 / 5540407.107229995.
     * <p>
     * Had the Helmert been dropped in favour of a literal reading of the uncommented string
     * alone, this would have become 3500000.000000 / 5540279.541956 — no shift at all,
     * 74.525 m E and 127.565 m N from the value below and 74.921 m / 127.698 m from
     * {@code cs2cs} with the grid present.
     */
    @Test
    public void potsdamOnTheNinthMeridian() {
        check("potsdam EPSG:31467 GK zone 3",
                "+proj=tmerc +lat_0=0 +lon_0=9 +k=1 +x_0=3500000 +y_0=0 +datum=potsdam"
                        + " +units=m +no_defs",
                9.0, 50.0,
                3500074.525405575, 5540407.107229995);
    }

    /**
     * <b>potsdam.</b> EPSG:31466, GK zone 2, at 51.425&deg;N 6.685&deg;E — the point
     * {@code FeatureTest.testDatumConversion} uses, kept here so the datum's own suite
     * carries it independently of that test's fate.
     */
    @Test
    public void potsdamOnTheRhine() {
        check("potsdam EPSG:31466 GK zone 2",
                "+proj=tmerc +lat_0=0 +lon_0=6 +k=1 +x_0=2500000 +y_0=0 +datum=potsdam"
                        + " +units=m +no_defs",
                6.685, 51.425,
                2547686.152498610, 5699151.775292793);
    }

    // ================================================================ unchanged datums

    /**
     * <b>WGS84, unchanged.</b> {@code towgs84=0,0,0}: the shift must be exactly nothing,
     * and 500000.000000001 rather than 500000 is PROJ's own residual at the central
     * meridian, reproduced.
     */
    @Test
    public void wgs84InUtmZone33() {
        check("WGS84 EPSG:32633", "+proj=utm +zone=33 +datum=WGS84 +units=m +no_defs",
                15.0, 50.0, 500000.000000001, 5538630.702867474);
    }

    /**
     * <b>GGRS87, unchanged.</b> EPSG:2100, Greek Grid, at Athens (37.9838&deg;N
     * 23.7275&deg;E). {@code towgs84=-199.87,74.79,246.62} on GRS80.
     */
    @Test
    public void ggrs87InAthens() {
        check("GGRS87 EPSG:2100",
                "+proj=tmerc +lat_0=0 +lon_0=24 +k=0.9996 +x_0=500000 +y_0=0 +datum=GGRS87"
                        + " +units=m +no_defs",
                23.7275, 37.9838, 475920.265213187, 4203764.699194606);
    }

    /**
     * <b>NAD83, unchanged.</b> EPSG:26918, UTM 18N, at Washington DC. {@code towgs84=0,0,0}
     * on GRS80, so again exactly no shift.
     * <p>
     * {@code cs2cs +datum=NAD83} gives 323394.032756778 here — 0.264 m away — because
     * {@code proj.db} carries a real NAD83&rarr;WGS84 transformation and prefers it to the
     * legacy table's identity. The legacy value is the correct target for this
     * implementation, and the 0.264 m is a known consequence of Proj4J implementing the
     * {@code pj_datums} path.
     */
    @Test
    public void nad83InUtmZone18() {
        check("NAD83 EPSG:26918", "+proj=utm +zone=18 +datum=NAD83 +units=m +no_defs",
                -77.0365, 38.8977, 323394.296410752, 4307395.633727551);
    }

    /**
     * <b>hermannskogel, unchanged.</b> EPSG:31287, MGI / Austria Lambert, at Salzburg
     * (47.8095&deg;N 13.0448&deg;E). The seven-parameter set matches PROJ digit for digit,
     * and here {@code +datum=hermannskogel} and the legacy form agree exactly.
     */
    @Test
    public void hermannskogelInSalzburg() {
        check("hermannskogel EPSG:31287",
                "+proj=lcc +lat_1=49 +lat_2=46 +lat_0=47.5 +lon_0=13.33333333333333"
                        + " +x_0=400000 +y_0=400000 +datum=hermannskogel +units=m +no_defs",
                13.0448, 47.8095, 378452.906422178, 434498.349220390);
    }

    /**
     * <b>ire65, unchanged.</b> EPSG:29902, Irish Grid, at Dublin (53.3498&deg;N
     * 6.2603&deg;W). {@code cs2cs +datum=ire65} differs by 8 mm E / 51 mm N because it
     * prefers an EPSG TM75 operation; the legacy Helmert is reproduced exactly.
     */
    @Test
    public void ire65InDublin() {
        check("ire65 EPSG:29902",
                "+proj=tmerc +lat_0=53.5 +lon_0=-8 +k=1.000035 +x_0=200000 +y_0=250000"
                        + " +datum=ire65 +units=m +no_defs",
                -6.2603, 53.3498, 315900.553319576, 234671.409910170);
    }

    /**
     * <b>nzgd49, unchanged.</b> EPSG:27200, NZGD49 / New Zealand Map Grid, at Wellington
     * (41.2865&deg;S 174.7762&deg;E). {@code cs2cs +datum=nzgd49} is 2.248 m E / 1.313 m N
     * away, being the {@code nz_linz_nzgd2kgrid0005.tif} distortion grid rather than the
     * Helmert. The Helmert itself is reproduced exactly.
     */
    @Test
    public void nzgd49InWellington() {
        check("nzgd49 EPSG:27200",
                "+proj=nzmg +lat_0=-41 +lon_0=173 +x_0=2510000 +y_0=6023150 +datum=nzgd49"
                        + " +units=m +no_defs",
                174.7762, -41.2865, 2658759.624232528, 5989629.930248617);
    }

    /**
     * <b>NAD27, unchanged.</b> The one grid-shift datum, and the one whose probe point has
     * to be chosen with the shipped <i>grid inventory</i> in mind rather than only the
     * datum's area of use.
     *
     * <p>{@code Datum.NAD27} requests {@code @conus,@alaska,@ntv2_0.gsb,@ntv1_can.dat} and
     * {@code Grid.shift} takes the <b>first</b> grid whose bounding box contains the point.
     * Two of the four resolve today ({@code conus} and {@code ntv1_can.dat}), and
     * {@code conus}'s box reaches to 50&deg;N / 63&deg;W — so it swallows the whole
     * Canada&ndash;US border region including Ottawa, Toronto and Montreal. A probe at
     * Ottawa therefore exercises {@code conus} extrapolated past its real coverage, not the
     * Canadian grid, and lands 2.487 m from PROJ. (PROJ has the same ordering rule, but a
     * different resolved set, so it does not make the same substitution.) That is a finding
     * about the grid inventory and belongs to the grids work, not to this table; it is
     * recorded here so the next reader does not rediscover it as an arithmetic bug.
     *
     * <p>North of 50&deg;N the ambiguity is gone. At 52&deg;N 75&deg;W, into EPSG:26718,
     * both libraries use {@code ntv1_can.dat} and agree. Reference
     * {@code cs2cs ... +to +proj=utm +zone=18 +ellps=clrk66 +nadgrids=@ntv1_can.dat} =
     * 499975.296223 / 5760810.259052, against 500000.000000 / 5760820.102914 with no shift
     * at all — so the grid is contributing 24.70 m of easting and it is being applied.
     * PROJ's own {@code +datum=NAD27} gives 499975.798047 / 5760810.381353 here, 0.502 m
     * away, because it selects NTv2; that is a grid difference, not an error in either.
     *
     * <p>Tolerance is 1 mm rather than {@code TOL} because the two libraries read different
     * encodings of the same grid — Proj4J the original {@code .dat}, PROJ the GeoTIFF
     * conversion {@code ca_nrc_ntv1_can.tif}.
     */
    @Test
    public void nad27InNorthernQuebec() {
        ProjCoordinate got = to("+proj=utm +zone=18 +datum=NAD27 +units=m +no_defs",
                -75.0, 52.0);
        assertEquals("NAD27 EPSG:26718 easting", 499975.296223, got.x, 1.0e-3);
        assertEquals("NAD27 EPSG:26718 northing", 5760810.259052, got.y, 1.0e-3);
    }
}

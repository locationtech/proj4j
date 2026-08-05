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
package org.locationtech.proj4j.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.locationtech.proj4j.CrsCreationException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.api.BallparkPolicy;
import org.locationtech.proj4j.api.BestOperationPolicy;
import org.locationtech.proj4j.api.Crs;
import org.locationtech.proj4j.api.CrsOperation;
import org.locationtech.proj4j.api.CrsOperationCandidate;
import org.locationtech.proj4j.api.DatabaseInfo;
import org.locationtech.proj4j.api.GridInfo;
import org.locationtech.proj4j.api.Proj;
import org.locationtech.proj4j.api.ProjContext;
import org.locationtech.proj4j.spi.DbAxis;
import org.locationtech.proj4j.spi.DbCoordinateSystem;
import org.locationtech.proj4j.spi.DbDatum;
import org.locationtech.proj4j.spi.DbObjectType;
import org.locationtech.proj4j.spi.DbOperation;

/**
 * The facade's operation selection driven by the <strong>real shipped index</strong> — 6,746,032 B of
 * PROJ 9.8.1's authority database, read by the pure-Java {@code .pjdx} reader.
 *
 * <h2>Why this test lives here and not in core</h2>
 * Core has no {@code proj4j-db} on its classpath, deliberately: it must compile, run and pass with no
 * implementation present. So core tests the <em>policy</em> against a few dozen transcribed rows
 * ({@code OperationSelectionTest}), and this file tests the <em>same policy against all 6.7 MB</em>.
 * The two together are the claim; either alone is not.
 *
 * <h2>What this module's classpath deliberately lacks</h2>
 * <strong>No {@code proj4j-epsg} and no grid pack.</strong> That is not an oversight to be worked
 * around — it is the deployment in which the consumer's defect was reported, so it is the one worth
 * testing:
 * <ul>
 *   <li>authority codes resolve <em>from the database</em>, since the legacy PROJ.4 dictionary is not
 *       there to resolve them;</li>
 *   <li>no grid file is reachable, so {@code EPSG:4267 → EPSG:4269} must fail with
 *       {@link ErrorCause#BEST_OPERATION_UNAVAILABLE} <em>naming both {@code conus.las} and
 *       {@code conus.los}</em> rather than returning the input unchanged.</li>
 * </ul>
 *
 * <h2>Every expected number is PROJ 9.8.1's own</h2>
 * Verified with {@code projinfo} and {@code sqlite3} against the Homebrew {@code proj 9.8.1}
 * installation, never against proj4j's output:
 * <pre>
 * projinfo -s EPSG:4267 -t EPSG:4269 --spatial-test intersects --summary   -&gt; 10 candidates
 * projinfo -s EPSG:4326 -t EPSG:9057 --summary                             -&gt; 1 candidate, 2.0 m
 * projinfo -s EPSG:4277 -t EPSG:4326 --summary                             -&gt; EPSG:7710, 1.0 m
 * </pre>
 */
public class RealDatabaseSelectionTest {

    private static PjdxDatabase db;
    private static ProjContext ctx;

    @BeforeClass
    public static void openTheRealIndex() throws IOException {
        db = Proj4jDb.open();
        assertNotNull("the shipped .pjdx must be on this module's own test classpath", db);
        ctx = ProjContext.builder().database(db).build();
    }

    @AfterClass
    public static void closeIt() throws IOException {
        if (db != null) {
            db.close();
        }
    }

    // ------------------------------------------------------------------ 1. the headline

    /**
     * <strong>{@code EPSG:4267 → EPSG:4269} sees all nine published transformations, ranks
     * {@code EPSG:1241} first at 0.15 m, and not one of the nine is ballpark.</strong>
     * <p>
     * Ten candidates in total. That is {@code projinfo}'s own count for this pair: the nine grid
     * transformations plus the ballpark offset, which is <em>synthesised</em> — there is not one
     * {@code Ballpark geographic offset} row anywhere in the 6.7 MB.
     */
    @Test
    public void theNad27PairHasNinePublishedOperationsAndNoneIsBallpark() {
        List<CrsOperationCandidate> candidates = Proj.candidateOperations(
                Proj.createCrs("EPSG:4267", ctx), Proj.createCrs("EPSG:4269", ctx), ctx);
        assertEquals("nine published plus one synthesised ballpark, which is projinfo's count",
                10, candidates.size());

        int published = 0;
        for (int i = 0; i < candidates.size(); i++) {
            CrsOperationCandidate c = candidates.get(i);
            if (c.isSynthesisedBallpark()) {
                continue;
            }
            published++;
            assertFalse("not one of the nine published operations is ballpark, so the historic "
                    + "answer of \"ballpark\" misdescribed the data as well as failing the caller: "
                    + c, c.isBallpark());
            assertTrue("every one of the nine publishes an accuracy: " + c,
                    c.accuracy().isPresent());
            double m = c.accuracy().get().metres();
            assertTrue("accuracies run from 0.15 m to 2.0 m: " + c, m >= 0.15 && m <= 2.0);
        }
        assertEquals(9, published);

        // The exact set, by code, from the sqlite3 query in this class's javadoc.
        List<String> codes = codes(candidates);
        assertTrue(codes.toString(), codes.contains("EPSG:1241"));
        assertTrue(codes.toString(), codes.contains("EPSG:1243"));
        assertTrue(codes.toString(), codes.contains("EPSG:1312"));
        assertTrue(codes.toString(), codes.contains("EPSG:1313"));
        assertTrue(codes.toString(), codes.contains("EPSG:1462"));
        assertTrue(codes.toString(), codes.contains("EPSG:1573"));
        assertTrue(codes.toString(), codes.contains("EPSG:8549"));
        assertTrue(codes.toString(), codes.contains("EPSG:8555"));
        assertTrue(codes.toString(), codes.contains("EPSG:9111"));

        // EPSG:1241 is best: 0.15 m, and the one whose method this library can execute.
        assertEquals("EPSG:1241", candidates.get(0).authorityCode());
        assertEquals("NAD27 to NAD83 (1)", candidates.get(0).name());
        assertEquals(0.15, candidates.get(0).accuracy().get().metres(), 0.0);
        assertEquals(0, candidates.get(0).rank());
    }

    /**
     * <strong>With no grid file reachable, the failure is
     * {@link ErrorCause#BEST_OPERATION_UNAVAILABLE} and it names <em>both</em> {@code conus.las} and
     * {@code conus.los}.</strong>
     * <p>
     * This is the exact defect, at the exact deployment where it was reported. What must NOT happen is
     * a returned operation reporting 0.15 m over a coordinate nothing shifted — and what must also not
     * happen is a message naming one file when the authority requires two, because 150 of the 1,062
     * grid transformations in this index carry a {@code grid2_name} and applying only the first shifts
     * by half and reports success.
     */
    @Test
    public void withNoGridReachableTheFailureNamesBothConusFiles() {
        try {
            CrsOperation op = Proj.createCrsToCrs("EPSG:4267", "EPSG:4269", ctx);
            fail("no grid file is on this module's classpath, so this must refuse rather than return "
                    + "an operation claiming 0.15 m. Got: " + op.describe());
        } catch (CrsCreationException expected) {
            assertEquals("the authority publishes nine real operations, so this is not a ballpark "
                    + "rejection: what is missing is a data file",
                    ErrorCause.BEST_OPERATION_UNAVAILABLE, expected.cause());
            String m = expected.getMessage();
            assertTrue("must name the operation it wanted: " + m, m.contains("EPSG:1241"));
            assertTrue("must name it in words: " + m, m.contains("NAD27 to NAD83 (1)"));
            assertTrue("must name the first grid: " + m, m.contains("conus.las"));
            assertTrue("must name the SECOND grid, or half a shift looks like a whole one: " + m,
                    m.contains("conus.los"));
            assertTrue("must name the modern file that would satisfy the pair: " + m,
                    m.contains("us_noaa_conus.tif"));
            assertTrue("must explain why there are two: " + m,
                    m.contains("NADCON splits the latitude and longitude shifts"));
            assertTrue("must quote the accuracy being given up: " + m, m.contains("0.15"));
        }
    }

    /**
     * <strong>{@code EPSG:1241} needs two grid files, and the real index says so.</strong> Read from
     * {@code grid_transformation.grid_name} and {@code grid2_name} through the shipped reader, not from
     * a transcription.
     */
    @Test
    public void epsg1241NeedsTwoGridsAccordingToTheRealIndex() {
        DbOperation op = db.operation("EPSG", "1241");
        assertNotNull(op);
        assertEquals("NAD27 to NAD83 (1)", op.name());
        assertEquals(DbObjectType.GRID_TRANSFORMATION, op.kind());
        assertEquals(0.15, op.accuracy(), 0.0);
        assertEquals("two grid slots, not one", 2, op.gridNames().size());
        assertEquals("conus.las", op.gridNames().get(0));
        assertEquals("conus.los", op.gridNames().get(1));

        // grid_alternatives has a row for the latitude file and NONE for the longitude file. Only 1 of
        // the 85 distinct grid2_names upstream has one, which is why the second slot must be satisfied
        // through the first slot's file rather than resolved on its own.
        assertNotNull(db.gridAlternative("conus.las"));
        assertEquals("us_noaa_conus.tif", db.gridAlternative("conus.las").projGridName());
        assertEquals("conus", db.gridAlternative("conus.las").oldProjGridName());
        assertEquals("GTiff", db.gridAlternative("conus.las").projGridFormat());
        assertEquals("hgridshift", db.gridAlternative("conus.las").projMethod());
        org.junit.Assert.assertNull("conus.los has no grid_alternatives row at all",
                db.gridAlternative("conus.los"));

        // And the facade reports both slots, with the collapse stated rather than performed silently.
        CrsOperationCandidate candidate = byCode(Proj.candidateOperations(
                Proj.createCrs("EPSG:4267", ctx), Proj.createCrs("EPSG:4269", ctx), ctx),
                "EPSG:1241");
        assertEquals(2, candidate.grids().size());
        assertEquals("conus.las", candidate.grids().get(0).name());
        assertEquals("conus.los", candidate.grids().get(1).name());
        assertEquals(1, candidate.grids().get(0).slot().getAsInt());
        assertEquals(2, candidate.grids().get(1).slot().getAsInt());
        assertEquals("hgridshift", candidate.grids().get(0).projMethod().get());
        assertEquals("both slots are missing here, and both are listed", 2,
                candidate.missingGrids().size());
    }

    /**
     * The census that makes the {@code grid2_name} trap a fact rather than an anecdote:
     * <strong>150 of the 1,062 grid transformations have a second grid.</strong>
     * <p>
     * Counted through the SPI over the whole index, so a reader who changes the transcoder cannot
     * quietly drop the column.
     */
    @Test
    public void oneHundredAndFiftyGridTransformationsHaveASecondGrid() {
        int total = 0;
        int withSecondGrid = 0;
        for (String authority : db.authorities()) {
            for (org.locationtech.proj4j.spi.DbObjectRef ref : db.crsCodes(authority)) {
                // Enumerating operations directly is not an SPI capability; the counted set below comes
                // from walking every operation reachable as some CRS's source.
                for (org.locationtech.proj4j.spi.DbObjectRef opRef
                        : db.operationsWithSourceCrs(ref.authName(), ref.code())) {
                    if (opRef.type() != DbObjectType.GRID_TRANSFORMATION) {
                        continue;
                    }
                    DbOperation op = db.operation(opRef.authName(), opRef.code());
                    if (op == null) {
                        continue;
                    }
                    total++;
                    if (op.gridNames().size() > 1) {
                        withSecondGrid++;
                    }
                }
            }
        }
        assertEquals("every grid transformation in the index must be reachable from its source CRS",
                1062, total);
        assertEquals("150 of the 1,062 grid transformations carry a grid2_name. A selector that reads "
                + "only grid_name applies half the shift and reports success.",
                150, withSecondGrid);
    }

    // ------------------------------------------------------------------ 2. the ensemble

    /**
     * <strong>{@code EPSG:6326} is a datum ensemble and its 2.0 m accuracy survives.</strong>
     * <p>
     * {@code EPSG:4326 → EPSG:9057} crosses from the WGS 84 ensemble to one of its eight members.
     * {@code projinfo} reports exactly one candidate, {@code PROJ:WGS84_TO_WGS84_G1762} at 2.0 m, whose
     * PROJ string is {@code +proj=noop}. Arithmetically a no-op, and <em>not</em> ballpark, because the
     * authority publishes a bound. Treating the ensemble as an ordinary datum loses that bound.
     */
    @Test
    public void theWgs84EnsembleAccuracySurvives() {
        DbDatum ensemble = db.datum(DbObjectType.GEODETIC_DATUM, "EPSG", "6326");
        assertNotNull(ensemble);
        assertEquals("World Geodetic System 1984 ensemble", ensemble.name());
        assertTrue("EPSG:6326 is an ensemble, not an ordinary datum", ensemble.isEnsemble());
        assertEquals(2.0, ensemble.ensembleAccuracy(), 0.0);
        assertEquals("eight members, in authority sequence order", 8,
                ensemble.ensembleMembers().size());
        assertEquals("EPSG:1166", ensemble.ensembleMembers().get(0).authorityCode());
        assertEquals("EPSG:1383", ensemble.ensembleMembers().get(7).authorityCode());

        CrsOperation op = Proj.createCrsToCrs("EPSG:4326", "EPSG:9057", ctx);
        assertTrue(op.selectedOperation().isPresent());
        assertEquals("PROJ:WGS84_TO_WGS84_G1762", op.selectedOperation().get().authorityCode());
        assertTrue("the ensemble bound must survive as a stated accuracy, not become \"unknown\": "
                + op.describe(), op.accuracy().isPresent());
        assertEquals(2.0, op.accuracy().get().metres(), 0.0);
        assertFalse("a no-op with a published 2 m bound is NOT ballpark", op.isBallparkTransformation());
    }

    // ------------------------------------------------------------------ 3. the +datum= bridge

    /**
     * <strong>A bare {@code +datum=OSGB36} reaches {@code EPSG:4277}, and from there the whole OSGB36
     * story: {@code EPSG:7710} "OSGB36 to WGS 84 (9)", NTv2, 1.0 m &mdash; and the parameterised
     * Helmert that the legacy engine would have used instead.</strong>
     * <p>
     * The bridge is PROJ's own ten-entry {@code +datum=} table, transcribed from
     * {@code 9.8.1:src/iso19111/io.cpp}. <strong>1,962 lines of the shipped legacy dictionaries carry a
     * {@code datum=}</strong>, so this is the payload rather than a curiosity: it is what makes real
     * operation selection reach a PROJ.4 parameter string at all.
     * <p>
     * And it is the case {@link BestOperationPolicy} was written for, with the real numbers. The best
     * <em>usable</em> candidate here is {@code EPSG:1314} at 2.0 m &mdash; a 7-parameter Helmert, which
     * this library can execute &mdash; while {@code EPSG:7710} at 1.0 m needs a grid file that is not
     * on this classpath. Silently taking the Helmert is a metre-scale change of answer with no signal,
     * so the default refuses and quantifies the gap.
     */
    @Test
    public void aBareOsgb36DatumReachesTheRealOsgb36Operations() {
        Crs osgb = Proj.createCrs("+proj=longlat +datum=OSGB36", ctx);
        Crs wgs84 = Proj.createCrs("+proj=longlat +datum=WGS84", ctx);
        List<CrsOperationCandidate> candidates = Proj.candidateOperations(osgb, wgs84, ctx);

        // The grid operation the brief names, with its real accuracy and its real file names.
        CrsOperationCandidate ostn15 = byCode(candidates, "EPSG:7710");
        assertEquals("OSGB36 to WGS 84 (9)", ostn15.name());
        assertEquals(1.0, ostn15.accuracy().get().metres(), 0.0);
        assertEquals(1, ostn15.grids().size());
        assertEquals("OSTN15_NTv2_OSGBtoETRS.gsb", ostn15.grids().get(0).name());
        assertEquals("uk_os_OSTN15_NTv2_OSGBtoETRS.tif", ostn15.grids().get(0).modernName().get());
        assertEquals("the GeoTIFF reader can execute the far end of this once the file is present",
                "hgridshift", ostn15.grids().get(0).projMethod().get());
        assertEquals("no grid pack is on this classpath",
                CrsOperationCandidate.Rejection.MISSING_GRID, ostn15.rejection());

        // The best USABLE candidate is the Helmert, because a Helmert needs no file.
        CrsOperationCandidate best = candidates.get(0);
        assertEquals("EPSG:1314", best.authorityCode());
        assertEquals("OSGB36 to WGS 84 (6)", best.name());
        assertEquals(2.0, best.accuracy().get().metres(), 0.0);
        assertTrue("a 7-parameter Helmert is executable: it needs no grid", best.isUsable());
        assertTrue("a usable candidate outranks an unavailable one, because it is the one you can "
                + "have", best.rank() < ostn15.rank());

        // But 2.0 m is strictly worse than 1.0 m, so REQUIRE_BEST refuses rather than quietly
        // substituting. That refusal is the whole point of the policy.
        try {
            CrsOperation op = Proj.createCrsToCrs(osgb, wgs84, ctx);
            fail("expected BEST_OPERATION_UNAVAILABLE: the 1.0 m grid operation cannot be run here "
                    + "and the 2.0 m Helmert is strictly worse. Got: " + op.describe());
        } catch (CrsCreationException expected) {
            assertEquals(ErrorCause.BEST_OPERATION_UNAVAILABLE, expected.cause());
            String m = expected.getMessage();
            assertTrue("must name the better operation: " + m, m.contains("EPSG:7710"));
            assertTrue("must name the file that would unlock it: " + m,
                    m.contains("OSTN15_NTv2_OSGBtoETRS.gsb"));
            assertTrue("must name the modern form too: " + m,
                    m.contains("uk_os_OSTN15_NTv2_OSGBtoETRS.tif"));
            assertTrue("must name what would have been substituted: " + m, m.contains("EPSG:1314"));
            assertTrue("must quantify the gap: " + m, m.contains("1.0 m worse than you asked for"));
        }

        // And with the degradation accepted, the Helmert is selected and the loss is recorded.
        CrsOperation degraded = Proj.createCrsToCrs(osgb, wgs84, ProjContext.builder()
                .database(db)
                .bestOperationPolicy(BestOperationPolicy.ALLOW_DEGRADED)
                .build());
        assertEquals("EPSG:1314", degraded.selectedOperation().get().authorityCode());
        assertEquals(2.0, degraded.accuracy().get().metres(), 0.0);
        assertTrue("the degradation must be on the record: " + degraded.warnings(),
                warningsContain(degraded, "EPSG:7710"));
    }

    /** All ten {@code +datum=} names PROJ maps, resolved through the real index. */
    @Test
    public void allTenProjDatumNamesResolveToRealCrss() {
        String[][] expected = {
            {"WGS84", "EPSG:4326"}, {"NAD83", "EPSG:4269"}, {"NAD27", "EPSG:4267"},
            {"GGRS87", "EPSG:4121"}, {"potsdam", "EPSG:4314"}, {"carthage", "EPSG:4223"},
            {"hermannskogel", "EPSG:4312"}, {"ire65", "EPSG:4299"}, {"nzgd49", "EPSG:4272"},
            {"OSGB36", "EPSG:4277"},
        };
        for (int i = 0; i < expected.length; i++) {
            Crs crs = Proj.createCrs("+proj=longlat +datum=" + expected[i][0], ctx);
            List<CrsOperationCandidate> candidates = Proj.candidateOperations(crs,
                    Proj.createCrs("EPSG:4326", ctx), ctx);
            if ("WGS84".equals(expected[i][0])) {
                assertTrue("+datum=WGS84 is EPSG:4326 itself, so there is no datum change",
                        candidates.isEmpty());
                continue;
            }
            assertFalse("+datum=" + expected[i][0] + " must reach " + expected[i][1]
                    + " and find operations there", candidates.isEmpty());
        }
    }

    // ------------------------------------------------------------------ 4. axis order

    /**
     * <strong>The database is what <em>supplies</em> authority axis order.</strong>
     * <p>
     * {@code EPSG:6422} is (Geodetic latitude north, Geodetic longitude east) — which is why PROJ 6+ is
     * latitude-first for {@code EPSG:4326} and proj4j 1.4.3 is not. {@code EPSG:4400} is (Easting,
     * Northing) and {@code EPSG:4530} is (Northing, Easting): the pair that makes an
     * authority-northing-first projected CRS look like a 4,652 km error when honoured on one side only.
     */
    @Test
    public void authorityAxisOrderIsReadFromTheDatabaseNotInferred() {
        DbCoordinateSystem ellipsoidal = db.coordinateSystem("EPSG", "6422");
        assertNotNull(ellipsoidal);
        assertEquals(2, ellipsoidal.axes().size());
        assertEquals("Geodetic latitude", ellipsoidal.axes().get(0).name());
        assertEquals("north", ellipsoidal.axes().get(0).orientation());
        assertEquals("Geodetic longitude", ellipsoidal.axes().get(1).name());
        assertEquals("east", ellipsoidal.axes().get(1).orientation());

        DbCoordinateSystem eastingFirst = db.coordinateSystem("EPSG", "4400");
        assertEquals("Easting", eastingFirst.axes().get(0).name());
        assertEquals("Northing", eastingFirst.axes().get(1).name());

        DbCoordinateSystem northingFirst = db.coordinateSystem("EPSG", "4530");
        assertEquals("authority northing-first, which is what EPSG:2393 uses", "Northing",
                northingFirst.axes().get(0).name());
        assertEquals("Easting", northingFirst.axes().get(1).name());
    }

    /**
     * The upstream quirk that makes an axis key {@code (cs auth, cs code, order, axis auth, axis code)}
     * rather than {@code (cs, order)}: <strong>{@code PROJ:ENh}'s three axes are numbered 1, 2 and
     * 2.</strong>
     * <p>
     * Keying on {@code (cs, order)} leaves the last two tied, and a tiebreak silently puts the height
     * before the northing.
     */
    @Test
    public void projEnhHasTwoAxesNumberedTwo() {
        DbCoordinateSystem enh = db.coordinateSystem("PROJ", "ENh");
        assertNotNull(enh);
        assertEquals(3, enh.axes().size());
        List<DbAxis> axes = enh.axes();
        assertEquals("Easting", axes.get(0).name());
        assertEquals(1, axes.get(0).order());
        assertEquals("Northing", axes.get(1).name());
        assertEquals(2, axes.get(1).order());
        assertEquals("Ellipsoidal height", axes.get(2).name());
        assertEquals("upstream really numbers this 2, not 3", 2, axes.get(2).order());
    }

    // ------------------------------------------------------------------ 5. NKG visibility

    /**
     * <strong>The NKG operations are made visible and what is missing is named — and nothing pretends
     * to execute them.</strong>
     * <p>
     * Their "method name" <em>is</em> a PROJ pipeline: {@code +proj=deformation +dt=15.829
     * +grids=eur_nkg_nkgrf17vel.tif}. Finishing them needs {@code +proj=deformation} plus a time
     * dimension on the coordinate, not more data, and this test pins that the boundary is reported
     * rather than crossed.
     */
    @Test
    public void nkgPipelineOperationsAreVisibleAndTheirGapIsNamed() {
        DbOperation intraplate = db.operation("NKG", "DK_2020_INTRAPLATE");
        assertNotNull("the NKG rows are in the shipped index", intraplate);
        assertTrue("its method name IS a PROJ pipeline, not a label",
                intraplate.isProjStringMethod());
        assertTrue(intraplate.methodName(), intraplate.methodName().contains("+proj=deformation"));
        assertTrue(intraplate.methodName(), intraplate.methodName().contains("+dt=15.829"));
        assertTrue(intraplate.methodName(),
                intraplate.methodName().contains("eur_nkg_nkgrf17vel.tif"));

        DbOperation concatenated = db.operation("NKG", "ETRF00_TO_DK");
        assertNotNull(concatenated);
        assertEquals(DbObjectType.CONCATENATED_OPERATION, concatenated.kind());
        assertFalse("a concatenated operation has steps", concatenated.steps().isEmpty());
    }

    /**
     * The other upstream quirk worth pinning: <strong>two concatenated operations number their steps 2
     * and 3, with no step 1</strong>, so {@code stepNumber()} is not an index into {@code steps()}.
     */
    @Test
    public void twoConcatenatedOperationsHaveNoStepOne() {
        String[] codes = {"ITRF2000_TO_NKG_ETRF00", "ITRF2014_TO_NKG_ETRF14"};
        for (int i = 0; i < codes.length; i++) {
            DbOperation op = db.operation("NKG", codes[i]);
            assertNotNull(codes[i], op);
            assertEquals(codes[i] + " has two steps", 2, op.steps().size());
            assertEquals(codes[i] + " numbers its first step 2, not 1, so stepNumber() is not an "
                    + "index into steps()", 2, op.steps().get(0).stepNumber());
            assertEquals(3, op.steps().get(1).stepNumber());
        }
    }

    // ------------------------------------------------------------------ 6. determinism

    /**
     * The ranking is a pure function of the index and the classpath: repeated calls, and re-sorting a
     * shuffled copy, give a bit-identical order.
     * <p>
     * Not a nicety. If any tier left two candidates tied, the result would depend on which index a row
     * was found through, and two Spark executors would select different operations from the same bytes.
     */
    @Test
    public void selectionIsDeterministicOverTheWholeIndex() {
        Crs source = Proj.createCrs("EPSG:4267", ctx);
        Crs target = Proj.createCrs("EPSG:4269", ctx);
        List<String> first = codes(Proj.candidateOperations(source, target, ctx));
        for (int run = 0; run < 3; run++) {
            assertEquals("repeated selection must give the same order", first,
                    codes(Proj.candidateOperations(source, target, ctx)));
        }
        List<CrsOperationCandidate> shuffled =
                new ArrayList<CrsOperationCandidate>(Proj.candidateOperations(source, target, ctx));
        java.util.Collections.reverse(shuffled);
        java.util.Collections.sort(shuffled);
        assertEquals("reversing then re-sorting must reproduce the ranking exactly", first,
                codes(shuffled));
        for (int i = 0; i < shuffled.size(); i++) {
            for (int j = i + 1; j < shuffled.size(); j++) {
                assertFalse(shuffled.get(i) + " and " + shuffled.get(j) + " compare equal, so their "
                        + "order depends on the sort's stability rather than on the data",
                        shuffled.get(i).compareTo(shuffled.get(j)) == 0);
            }
        }
    }

    // ------------------------------------------------------------------ 7. introspection

    /** {@code databaseVersion()} comes from the index's own {@code metadata} table. */
    @Test
    public void theVersionIsReadFromTheIndexsOwnMetadata() {
        DatabaseInfo info = Proj.databaseInfo(ctx);
        assertTrue(info.isDatabasePresent());
        assertEquals("9.8.1", info.metadata().get("PROJ.VERSION"));
        assertTrue(info.version().get(), info.version().get().startsWith("PROJ 9.8.1, EPSG "));
        assertTrue(info.epsgVersion().isPresent());
        assertTrue(info.databaseName().get(), info.databaseName().get().startsWith("pjdx"));
        assertTrue("describe() must state where the bytes came from: " + info.describe(),
                info.describe().contains(info.databaseName().get()));
    }

    /**
     * An authority code the legacy dictionary cannot resolve — because it is not on this classpath at
     * all — is resolved from the database, for geodetic CRSs.
     */
    @Test
    public void geodeticCrssResolveFromTheDatabaseWithNoLegacyDictionary() {
        Crs nad27 = Proj.createCrs("EPSG:4267", ctx);
        assertTrue("this CRS came from the database, and says so", nad27.isDatabaseDerived());
        assertEquals("NAD27", nad27.name());
        assertTrue(nad27.isGeographic());
        assertEquals("[EPSG:4267]", nad27.identifiers().toString());
        assertTrue("the authority's own row must be reachable", nad27.authorityRecord().isPresent());
        assertEquals("EPSG:6267", nad27.authorityRecord().get().datum().authorityCode());

        // Area of use is read, and reported as database-derived rather than as a document's claim.
        assertTrue("EPSG:4267 declares a usage: " + nad27.describe(), nad27.areaOfUse().isPresent());
        assertTrue(nad27.areaOfUse().get().isDatabaseDerived());

        // A projected CRS is deliberately NOT built from the database: turning 4,312 conversion rows
        // into Projections is where a mis-slotted parameter becomes a plausible coordinate in the wrong
        // place, and the legacy dictionary already carries those.
        try {
            Proj.createCrs("EPSG:27700", ctx);
            fail("a projected CRS must not be built from the database in this release");
        } catch (CrsCreationException expected) {
            assertEquals(ErrorCause.DATABASE_UNAVAILABLE, expected.cause());
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("only the legacy dictionary can build"));
        }
    }

    /**
     * {@link BallparkPolicy#ALLOW} plus {@link BestOperationPolicy#ALLOW_DEGRADED} is what it takes to
     * get a coordinate out of this deployment, and it says exactly what was given up.
     */
    @Test
    public void bothConcessionsAreNeededToGetACoordinateWithNoGrids() {
        ProjContext lenient = ProjContext.builder()
                .database(db)
                .ballparkPolicy(BallparkPolicy.ALLOW)
                .bestOperationPolicy(BestOperationPolicy.ALLOW_DEGRADED)
                .build();
        CrsOperation op = Proj.createCrsToCrs("EPSG:4267", "EPSG:4269", lenient);
        assertTrue("with no grid at all, the only thing left is the ballpark offset",
                op.isBallparkTransformation());
        assertFalse("which never has a stated accuracy, in PROJ either", op.accuracy().isPresent());
        assertTrue("and what was given up must be named: " + op.warnings(),
                warningsContain(op, "EPSG:1241"));
        assertTrue("as the largest degradation there is: " + op.warnings(),
                warningsContain(op, "largest degradation there is"));
    }

    /** Every grid the selected operation needs is reported as unreachable with its probe order. */
    @Test
    public void unreachableGridsReportEveryNameThatWasTried() {
        CrsOperationCandidate candidate = byCode(Proj.candidateOperations(
                Proj.createCrs("EPSG:4267", ctx), Proj.createCrs("EPSG:4269", ctx), ctx),
                "EPSG:1241");
        GridInfo slot1 = candidate.grids().get(0);
        assertFalse(slot1.isAvailable());
        assertEquals("the modern name is tried first, then the pre-PROJ-7 one, then the authority's",
                "[us_noaa_conus.tif, conus, conus.las]", slot1.probedNames().toString());
        assertTrue(slot1.skipReason().get(), slot1.skipReason().get().contains("us_noaa_conus.tif"));
        assertTrue("the CDN URL is information for a human and never an action",
                slot1.knownUrl().get().startsWith("https://cdn.proj.org/"));
        assertFalse("core contains no network code, so a URL never makes a grid available",
                slot1.isAvailable());
    }

    // ------------------------------------------------------------------ helpers

    private static CrsOperationCandidate byCode(List<CrsOperationCandidate> candidates,
                                                String authorityCode) {
        for (int i = 0; i < candidates.size(); i++) {
            if (authorityCode.equals(candidates.get(i).authorityCode())) {
                return candidates.get(i);
            }
        }
        throw new AssertionError(authorityCode + " is not among " + codes(candidates));
    }

    private static List<String> codes(List<CrsOperationCandidate> candidates) {
        List<String> out = new ArrayList<String>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            out.add(candidates.get(i).authorityCode());
        }
        return out;
    }

    private static boolean warningsContain(CrsOperation op, String needle) {
        List<String> warnings = op.warnings();
        for (int i = 0; i < warnings.size(); i++) {
            if (warnings.get(i).contains(needle)) {
                return true;
            }
        }
        return false;
    }
}

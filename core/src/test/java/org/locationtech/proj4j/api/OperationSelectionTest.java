/*
 * Copyright 2026, PROJ4J contributors
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
package org.locationtech.proj4j.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.locationtech.proj4j.CrsCreationException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * Real coordinate-operation selection: the authority's offer, made visible, ranked, and either chosen
 * or refused with the reason named.
 *
 * <h2>The defect this file exists for</h2>
 *
 * <p>{@code EPSG:4267} to {@code EPSG:4269} returned <b>the input unchanged</b> &mdash; 95.573&nbsp;m
 * out at San Francisco, finite, plausible, and unwarned. The facade's first answer to that was to throw
 * {@link ErrorCause#BALLPARK_REJECTED}, and <b>that is correct without a database</b>: with no way to
 * see what the authority publishes, "I will not vouch for this" is the only honest reply.
 *
 * <p>But the authority publishes <b>nine</b> grid transformations for that pair, accuracies 0.15&nbsp;m
 * to 2.0&nbsp;m, and <b>not one of them is ballpark</b>. So the defect was never "the authority offers
 * nothing"; it was that Proj4J could not see the offer. With a database present,
 * {@code BALLPARK_REJECTED} becomes the <em>wrong</em> answer, and the right one is
 * {@code EPSG:1241} "NAD27 to NAD83 (1)" at 0.15&nbsp;m &mdash; or
 * {@link ErrorCause#BEST_OPERATION_UNAVAILABLE} naming the files when its grids are absent.
 *
 * <p>Both halves of that are asserted here, in the same file, because the pair of them is the claim.
 *
 * <h2>Where the numbers come from</h2>
 *
 * <p>Every accuracy, grid name, extent and method below was read out of PROJ 9.8.1's own database and
 * cross-checked against {@code projinfo} 9.8.1 &mdash; never against Proj4J's output, which would agree
 * by construction. See {@link FakeProjDatabase} for the queries.
 */
public class OperationSelectionTest {

    /** San Francisco. Deliberately not near (0, 0) and deliberately inside CONUS. */
    private static final double LON = -122.4;
    private static final double LAT = 37.8;

    private static ProjContext withDatabase(FakeProjDatabase db) {
        return ProjContext.builder().database(db).build();
    }

    // ------------------------------------------------------------------ 1. the headline

    /**
     * <b>The headline assertion.</b> With the authority database present, {@code EPSG:4267} to
     * {@code EPSG:4269} selects {@code EPSG:1241} "NAD27 to NAD83 (1)" at 0.15&nbsp;m.
     *
     * <p>Not ballpark, and that is a separate claim worth making explicitly: nine operations exist and
     * none of them is a ballpark offset, so an answer of "ballpark" here would misdescribe the data as
     * well as failing the caller.
     */
    @Test
    public void withADatabaseTheNad27PairSelectsEpsg1241At015m() {
        ProjContext ctx = withDatabase(FakeProjDatabase.nad27ToNad83());
        CrsOperation op = Proj.createCrsToCrs("EPSG:4267", "EPSG:4269", ctx);

        assertTrue("an operation must have been selected: " + op.describe(),
                op.selectedOperation().isPresent());
        assertEquals("EPSG:1241", op.selectedOperation().get().authorityCode());
        assertEquals("NAD27 to NAD83 (1)", op.selectedOperation().get().name());
        assertTrue("the selected operation must carry the authority's accuracy: " + op.describe(),
                op.accuracy().isPresent());
        assertEquals(0.15, op.accuracy().get().metres(), 0.0);
        assertEquals("the accuracy must be attributed to the operation it came from",
                "EPSG:1241", op.accuracy().get().source());

        assertFalse("nine operations exist and not one is ballpark, so this must not be reported as "
                + "ballpark: " + op.describe(), op.isBallparkTransformation());
        assertFalse(op.ballparkReason().isPresent());

        // Selection is not merely metadata: it is ranked first, and the ranking is reported.
        assertEquals(0, op.selectedOperation().get().rank());
        assertTrue(op.selectedOperation().get().isUsable());
        assertEquals(CrsOperationCandidate.Rejection.NONE,
                op.selectedOperation().get().rejection());

        // And the transformation still works, with all-finite output.
        assertTrue(op.transform(new ProjCoordinate(LON, LAT)).hasValidXandYOrdinates());
    }

    /**
     * <b>The other half of the headline.</b> Without a database the same pair must still throw
     * {@link ErrorCause#BALLPARK_REJECTED}, by the legacy rule (c).
     *
     * <p>This is not a weaker version of the test above; it is the statement that the two answers are
     * both right, for different deployments. {@link BallparkPolicyTest} pins the same thing from the
     * policy side and must also stay green.
     */
    @Test
    public void withoutADatabaseTheNad27PairStillThrowsBallparkRejected() {
        assertFalse("this test is only meaningful with no database configured",
                ProjContext.DEFAULT.hasDatabase());
        try {
            CrsOperation op = Proj.createCrsToCrs("EPSG:4267", "EPSG:4269");
            fail("expected BALLPARK_REJECTED with no database, got: " + op.describe());
        } catch (CrsCreationException expected) {
            assertEquals(ErrorCause.BALLPARK_REJECTED, expected.cause());
        }
    }

    /**
     * The two answers differ <b>only</b> because of the database, and nothing else about the
     * deployment. Same classpath, same resolver chain, same policies, same process, one call apart.
     */
    @Test
    public void theDatabaseIsTheOnlyDifferenceBetweenTheTwoAnswers() {
        ProjContext without = ProjContext.builder().build();
        ProjContext with = without.withDatabase(FakeProjDatabase.nad27ToNad83());
        assertEquals("the two contexts must differ in nothing but the database",
                without.ballparkPolicy(), with.ballparkPolicy());
        assertEquals(without.gridPolicy(), with.gridPolicy());
        assertEquals(without.bestOperationPolicy(), with.bestOperationPolicy());
        assertEquals(without.axisOrderPolicy(), with.axisOrderPolicy());

        try {
            Proj.createCrsToCrs("EPSG:4267", "EPSG:4269", without);
            fail("expected BALLPARK_REJECTED without the database");
        } catch (CrsCreationException expected) {
            assertEquals(ErrorCause.BALLPARK_REJECTED, expected.cause());
        }
        assertEquals("EPSG:1241", Proj.createCrsToCrs("EPSG:4267", "EPSG:4269", with)
                .selectedOperation().get().authorityCode());
    }

    // ------------------------------------------------------------------ 2. grid2_name

    /**
     * <b>The trap: {@code EPSG:1241} needs TWO grids, and both are reported.</b>
     *
     * <p>NADCON splits the latitude and longitude shifts across a {@code .las}/{@code .los} pair, and
     * 150 of the shipped database's 1,062 grid transformations have a {@code grid2_name}. A selector
     * that reads only {@code grid_name} applies <b>half the shift and reports success</b> &mdash;
     * metre-scale, on the single most important transformation in the consumer's workload.
     */
    @Test
    public void aCandidateNeedingGrid2NameReportsBothFiles() {
        ProjContext ctx = withDatabase(FakeProjDatabase.nad27ToNad83());
        List<CrsOperationCandidate> candidates =
                Proj.candidateOperations("EPSG:4267", "EPSG:4269", ctx);

        CrsOperationCandidate epsg1241 = byCode(candidates, "EPSG:1241");
        assertEquals("EPSG:1241 requires two grid files, not one", 2, epsg1241.grids().size());
        assertEquals("conus.las", epsg1241.grids().get(0).name());
        assertEquals("conus.los", epsg1241.grids().get(1).name());
        assertEquals(1, epsg1241.grids().get(0).slot().getAsInt());
        assertEquals("the second slot must be numbered, not folded into the first",
                2, epsg1241.grids().get(1).slot().getAsInt());

        String described = epsg1241.describe();
        assertTrue("describe() must name both files: " + described, described.contains("conus.las"));
        assertTrue("describe() must name both files: " + described, described.contains("conus.los"));
        assertTrue("describe() must say how many the authority requires: " + described,
                described.contains("grids required by the authority (2)"));
    }

    /**
     * The second slot is satisfied by the <b>first slot's file</b>, and that substitution is reported
     * rather than performed silently.
     *
     * <p>This is PROJ's own collapse: {@code substitutePROJAlternativeGridNames} looks up
     * {@code grid_alternatives} for the latitude file alone and, when the modern form is a GeoTIFF,
     * replaces the whole pair with that one file. Upstream has a row for {@code conus.las} and
     * <b>none for {@code conus.los}</b> &mdash; only 1 of the 85 distinct {@code grid2_name}s has one
     * &mdash; so a reader that resolved slot 2 independently would report every NADCON operation
     * unusable. Two authority slots, one file, and both facts visible.
     */
    @Test
    public void theSecondSlotIsSatisfiedByTheFirstSlotsFileAndSaysSo() {
        ProjContext ctx = withDatabase(FakeProjDatabase.nad27ToNad83());
        CrsOperationCandidate epsg1241 = byCode(
                Proj.candidateOperations("EPSG:4267", "EPSG:4269", ctx), "EPSG:1241");

        GridInfo slot1 = epsg1241.grids().get(0);
        GridInfo slot2 = epsg1241.grids().get(1);
        assertTrue("core's test classpath ships the real CTABLE V2 conus, so slot 1 must resolve: "
                + slot1.describe(), slot1.isAvailable());
        assertTrue("two slots satisfied by one file cannot disagree about whether it exists",
                slot2.isAvailable());

        assertTrue("the substitution must be stated: " + slot2.describe(),
                slot2.satisfiedBy().isPresent());
        assertTrue("it must name the file that actually satisfied it: " + slot2.satisfiedBy().get(),
                slot2.satisfiedBy().get().startsWith("conus "));
        assertTrue("and say why one file covers two slots: " + slot2.satisfiedBy().get(),
                slot2.satisfiedBy().get().contains("both the latitude and the longitude shift"));

        // The collapse must follow whatever slot 1 actually resolved to, not the modern name it might
        // have. proj4j-grids-us-legacy ships the CTABLE V2 `conus`, which carries both components too;
        // hard-coding us_noaa_conus.tif here would report EPSG:1241 unusable on that deployment.
        assertTrue("the probe order must try the modern name first: " + slot1.probedNames(),
                slot1.probedNames().indexOf("us_noaa_conus.tif") == 0);
        assertTrue(slot1.probedNames().contains("conus"));
        assertTrue(slot1.probedNames().contains("conus.las"));
    }

    /**
     * <b>{@link ErrorCause#BEST_OPERATION_UNAVAILABLE} names the missing files &mdash; both of
     * them.</b>
     *
     * <p>{@code EPSG:1243} "NAD27 to NAD83 (2)" is a real NADCON pair needing {@code alaska.las} and
     * {@code alaska.los}, and neither file exists anywhere on this classpath. A message naming only
     * {@code alaska.las} would send a reader looking for one file when the authority requires two, and
     * this test is the reason that cannot regress silently.
     *
     * <p>Note the cause: <b>not</b> {@code BALLPARK_REJECTED}. The authority does publish a real
     * operation for this pair; what is missing is a data file. Those are different problems with
     * different fixes, and reporting the first as the second is what sent everyone looking in the
     * wrong place for fifteen years.
     */
    @Test
    public void bestOperationUnavailableNamesBothMissingGridFiles() {
        ProjContext ctx = withDatabase(FakeProjDatabase.nad27ToNad83WithNoReachableGrid());
        try {
            CrsOperation op = Proj.createCrsToCrs("EPSG:4267", "EPSG:4269", ctx);
            fail("expected BEST_OPERATION_UNAVAILABLE, got: " + op.describe());
        } catch (CrsCreationException expected) {
            assertEquals("the authority publishes a real operation; only the file is missing, so "
                    + "this must not be reported as a ballpark rejection",
                    ErrorCause.BEST_OPERATION_UNAVAILABLE, expected.cause());
            String m = expected.getMessage();
            assertTrue("must name the first grid: " + m, m.contains("alaska.las"));
            assertTrue("must name the SECOND grid too, or half a shift looks like a whole one: " + m,
                    m.contains("alaska.los"));
            assertTrue("must name the operation that was wanted: " + m, m.contains("EPSG:1243"));
            assertTrue("must name it in words, not only by code: " + m,
                    m.contains("NAD27 to NAD83 (2)"));
            assertTrue("must quote the accuracy that is being given up: " + m, m.contains("0.5"));
            assertTrue("must say explicitly that this is not a ballpark rejection: " + m,
                    m.contains("NOT a ballpark rejection"));
            assertTrue("must explain why two files: " + m,
                    m.contains("NADCON splits the latitude and longitude shifts"));
            assertTrue("must say that adding the grid fixes it: " + m,
                    m.contains("Add the grid and this call succeeds"));
            assertTrue("must list every name that was tried, so a reader knows what to install: " + m,
                    m.contains("us_noaa_alaska.tif"));
        }
    }

    /**
     * {@link CrsOperationCandidate#missingGrids()} carries both files too, so a caller that catches
     * nothing and merely inspects still sees the pair.
     */
    @Test
    public void missingGridsOnTheCandidateCarriesBothSlots() {
        ProjContext ctx = withDatabase(FakeProjDatabase.nad27ToNad83WithNoReachableGrid());
        CrsOperationCandidate epsg1243 = byCode(
                Proj.candidateOperations("EPSG:4267", "EPSG:4269", ctx), "EPSG:1243");
        assertEquals(CrsOperationCandidate.Rejection.MISSING_GRID, epsg1243.rejection());
        assertEquals("both slots are missing, and both are listed", 2,
                epsg1243.missingGrids().size());
        assertEquals("alaska.las", epsg1243.missingGrids().get(0).name());
        assertEquals("alaska.los", epsg1243.missingGrids().get(1).name());
        assertTrue("the reason must name both: " + epsg1243.rejectionReason().get(),
                epsg1243.rejectionReason().get().contains("alaska.las")
                        && epsg1243.rejectionReason().get().contains("alaska.los"));
    }

    /**
     * With <b>no {@code grid_alternatives} row at all</b> the authority's own two file names are the
     * only ones there are, and both are still named &mdash; but the failure is
     * {@link ErrorCause#UNSUPPORTED_OPERATION_METHOD}, not a missing grid, because without that row
     * there is no PROJ operator to map the grid to either.
     *
     * <p>Proj4J will not infer the operator from a {@code .las} extension. That inference is how a
     * latitude-difference file gets fed to a vertical shift, and the resulting coordinate is finite and
     * plausible.
     */
    @Test
    public void withNoGridAlternativesRowBothAuthorityNamesAreStillNamed() {
        ProjContext ctx = withDatabase(FakeProjDatabase.nad27ToNad83WithNoGridAlternatives());
        try {
            CrsOperation op = Proj.createCrsToCrs("EPSG:4267", "EPSG:4269", ctx);
            fail("expected UNSUPPORTED_OPERATION_METHOD, got: " + op.describe());
        } catch (CrsCreationException expected) {
            assertEquals(ErrorCause.UNSUPPORTED_OPERATION_METHOD, expected.cause());
            String m = expected.getMessage();
            assertTrue("must still name both authority files: " + m, m.contains("conus.las"));
            assertTrue("must still name both authority files: " + m, m.contains("conus.los"));
            assertTrue("must say what is actually wrong -- no operator mapping: " + m,
                    m.contains("no grid_alternatives row"));
            assertTrue("must refuse to guess the operator from the file name: " + m,
                    m.contains("will not guess which operator a grid feeds"));
            assertTrue("must say that adding a file will NOT help, unlike a missing grid: " + m,
                    m.contains("Nothing you add to the classpath will change it"));
        }
    }

    // ------------------------------------------------------------------ 3. the ensemble

    /**
     * <b>{@code EPSG:6326}'s ensemble accuracy survives.</b>
     *
     * <p>{@code EPSG:4326} to {@code EPSG:9057} crosses from the WGS 84 <em>ensemble</em> to one of its
     * eight members. {@code projinfo -s EPSG:4326 -t EPSG:9057 --summary} reports exactly one
     * candidate, {@code PROJ:WGS84_TO_WGS84_G1762} at <b>2.0 m</b>, and {@code -o PROJ} gives
     * {@code +proj=noop}.
     *
     * <p>That pairing is the whole point and it is why an ensemble is not an ordinary datum: the
     * operation is arithmetically a no-op, and it is <b>not ballpark</b>, because the authority
     * publishes a <em>bound</em> of 2.0 m for it. Treating {@code EPSG:6326} as an ordinary datum loses
     * that bound and turns a bounded 2 m into "accuracy unknown", which is a different and much weaker
     * claim.
     */
    @Test
    public void theWgs84EnsembleAccuracySurvivesAnEnsembleCrossingPair() {
        ProjContext ctx = withDatabase(FakeProjDatabase.wgs84Ensemble());
        CrsOperation op = Proj.createCrsToCrs("EPSG:4326", "EPSG:9057", ctx);

        assertTrue(op.selectedOperation().isPresent());
        assertEquals("PROJ:WGS84_TO_WGS84_G1762", op.selectedOperation().get().authorityCode());
        assertTrue("the ensemble bound must survive as a stated accuracy: " + op.describe(),
                op.accuracy().isPresent());
        assertEquals(2.0, op.accuracy().get().metres(), 0.0);
        assertFalse("a no-op with a published 2 m bound is NOT ballpark: an ensemble member offset "
                + "has an accuracy and a ballpark offset never does", op.isBallparkTransformation());
    }

    /**
     * The ensemble itself is readable through the SPI, with its accuracy and its members in the
     * authority's own sequence order.
     */
    @Test
    public void theEnsembleIsVisibleAsAnEnsembleWithItsEightMembers() {
        FakeProjDatabase db = FakeProjDatabase.wgs84Ensemble();
        org.locationtech.proj4j.spi.DbDatum ensemble = db.datum(
                org.locationtech.proj4j.spi.DbObjectType.GEODETIC_DATUM, "EPSG", "6326");
        assertNotNull(ensemble);
        assertTrue("EPSG:6326 is a datum ensemble, not an ordinary datum", ensemble.isEnsemble());
        assertEquals(2.0, ensemble.ensembleAccuracy(), 0.0);
        assertEquals("eight members, in authority sequence order", 8,
                ensemble.ensembleMembers().size());
        assertEquals("EPSG:1166", ensemble.ensembleMembers().get(0).authorityCode());
        assertEquals("EPSG:1383", ensemble.ensembleMembers().get(7).authorityCode());
    }

    // ------------------------------------------------------------------ 4. ranking

    /**
     * All ten candidates are enumerated: the nine the authority publishes plus the ballpark offset,
     * which is synthesised because <b>the database contains no ballpark row at all</b>.
     *
     * <p>Ten is {@code projinfo}'s own count for this pair.
     */
    @Test
    public void allNinePublishedOperationsPlusASynthesisedBallparkAreEnumerated() {
        ProjContext ctx = withDatabase(FakeProjDatabase.nad27ToNad83());
        List<CrsOperationCandidate> candidates =
                Proj.candidateOperations("EPSG:4267", "EPSG:4269", ctx);
        assertEquals("nine published plus one synthesised ballpark, which is projinfo's count too",
                10, candidates.size());

        int ballparks = 0;
        int published = 0;
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).isSynthesisedBallpark()) {
                ballparks++;
            } else {
                published++;
                assertFalse("not one of the nine published operations is ballpark: "
                        + candidates.get(i), candidates.get(i).isBallpark());
            }
        }
        assertEquals(9, published);
        assertEquals(1, ballparks);
    }

    /**
     * The ranking is a <b>total order</b>: shuffling the input and re-sorting reproduces it exactly.
     *
     * <p>Not a nicety. The database returns rows in {@code (kind, authority, code)} order, and if any
     * tier left two candidates tied the result would depend on which index a row was found through,
     * which differs between builds. Two Spark executors would then select different operations from the
     * same data.
     */
    @Test
    public void theRankingIsATotalOrderAndCannotDependOnInputOrder() {
        ProjContext ctx = withDatabase(FakeProjDatabase.nad27ToNad83());
        List<CrsOperationCandidate> ranked =
                Proj.candidateOperations("EPSG:4267", "EPSG:4269", ctx);

        List<CrsOperationCandidate> shuffled = new ArrayList<CrsOperationCandidate>(ranked);
        Collections.reverse(shuffled);
        Collections.sort(shuffled);
        List<String> a = codes(ranked);
        List<String> b = codes(shuffled);
        assertEquals("reversing then re-sorting must reproduce the ranking exactly", a, b);

        // No two candidates may compare equal, or a tie has been left to luck.
        for (int i = 0; i < ranked.size(); i++) {
            for (int j = i + 1; j < ranked.size(); j++) {
                assertFalse(ranked.get(i) + " and " + ranked.get(j) + " compare equal, so their "
                        + "order depends on the sort's stability rather than on the data",
                        ranked.get(i).compareTo(ranked.get(j)) == 0);
            }
        }
        // Ranks are dense and ascending.
        for (int i = 0; i < ranked.size(); i++) {
            assertEquals(i, ranked.get(i).rank());
        }
    }

    /**
     * The usability tier is ordered by <b>what a caller can do about it</b>: usable, then
     * fixable-by-adding-a-file, then a capability boundary, then ballpark.
     *
     * <p>On core's test classpath the real CTABLE V2 {@code conus} is present and
     * {@code ntv1_can.dat} arrives with {@code proj4j-epsg}, so exactly two of the nine are usable, and
     * the 0.15&nbsp;m one wins.
     */
    @Test
    public void candidatesAreRankedByWhatACallerCanDoAboutThem() {
        ProjContext ctx = withDatabase(FakeProjDatabase.nad27ToNad83());
        List<CrsOperationCandidate> ranked =
                Proj.candidateOperations("EPSG:4267", "EPSG:4269", ctx);

        assertEquals("EPSG:1241", ranked.get(0).authorityCode());
        assertEquals(0.15, ranked.get(0).accuracy().get().metres(), 0.0);
        assertTrue(ranked.get(0).isUsable());

        // A usable 2.0 m operation outranks an unavailable 0.5 m one, because it is the one you can
        // have. Whether choosing it is a *degradation* is a separate question about accuracy.
        assertEquals("EPSG:1312", ranked.get(1).authorityCode());
        assertEquals(2.0, ranked.get(1).accuracy().get().metres(), 0.0);
        assertTrue(ranked.get(1).isUsable());

        // The ballpark offset is last of all: executable, and useless.
        assertTrue(ranked.get(ranked.size() - 1).isSynthesisedBallpark());

        // Tier boundaries, in order.
        assertEquals(CrsOperationCandidate.Rejection.MISSING_GRID, ranked.get(2).rejection());
        int firstUnsupported = -1;
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).rejection()
                    == CrsOperationCandidate.Rejection.UNSUPPORTED_METHOD) {
                firstUnsupported = i;
                break;
            }
        }
        assertTrue("the two NADCON 5 operations must be present and rejected for method",
                firstUnsupported > 0);
        for (int i = 0; i < firstUnsupported; i++) {
            assertFalse("no missing-grid or usable candidate may sort below an unsupported one",
                    ranked.get(i).rejection()
                            == CrsOperationCandidate.Rejection.UNSUPPORTED_METHOD);
        }
    }

    /**
     * <b>Proj4J selects {@code EPSG:1241} where PROJ 9.8.1 selects {@code EPSG:8555}, and the reason is
     * a capability boundary, not a disagreement about the data.</b>
     *
     * <p>The two are tied at 0.15&nbsp;m. {@code EPSG:8555} is NADCON 5, whose {@code grid_alternatives}
     * row maps it to the unified {@code +proj=gridshift} operator; Proj4J implements
     * {@code +proj=hgridshift} and not that one. So {@code EPSG:8555} is visible, ranked, and rejected
     * <em>by name</em>, which is what makes the divergence auditable rather than mysterious.
     *
     * <p>A tie is also not a degradation, so {@link BestOperationPolicy#REQUIRE_BEST} has nothing to
     * refuse here &mdash; and it must not refuse, or the default policy would reject the pair outright
     * and be a worse answer than either operation.
     */
    @Test
    public void theNadcon5TieIsRejectedByNameAndIsNotTreatedAsADegradation() {
        ProjContext ctx = withDatabase(FakeProjDatabase.nad27ToNad83());
        List<CrsOperationCandidate> candidates =
                Proj.candidateOperations("EPSG:4267", "EPSG:4269", ctx);

        CrsOperationCandidate nadcon5 = byCode(candidates, "EPSG:8555");
        assertEquals("tied with EPSG:1241 on accuracy", 0.15, nadcon5.accuracy().get().metres(), 0.0);
        assertEquals(CrsOperationCandidate.Rejection.UNSUPPORTED_METHOD, nadcon5.rejection());
        String why = nadcon5.rejectionReason().get();
        assertTrue("the unimplemented operator must be named: " + why,
                why.contains("+proj=gridshift"));
        assertTrue("and the one that IS implemented, so the boundary is legible: " + why,
                why.contains("+proj=hgridshift"));

        CrsOperation op = Proj.createCrsToCrs("EPSG:4267", "EPSG:4269", ctx);
        assertEquals("REQUIRE_BEST is in force and must not refuse a tie",
                BestOperationPolicy.REQUIRE_BEST, op.context().bestOperationPolicy());
        assertEquals("EPSG:1241", op.selectedOperation().get().authorityCode());
        assertTrue("the skipped tie must be on the record rather than silent: " + op.warnings(),
                containing(op.warnings(), "EPSG:8555"));
        assertTrue("and must say it is not a degradation: " + op.warnings(),
                containing(op.warnings(), "no more accurate than the operation that was selected"));
    }

    /**
     * An extent that wraps through 180&deg; must not out-rank a small one by accident.
     *
     * <p>{@code EPSG:1243} and {@code EPSG:8549} declare Alaskan extents with
     * {@code west = 167.65, east = -129.99}. Normalising that into a single interval turns a 62&deg;
     * span into a 298&deg; one, which would then lose every area comparison it should win &mdash; or
     * win every one it should lose, depending on which way the mistake goes.
     */
    @Test
    public void antimeridianExtentsRankByTheirRealSpan() {
        ProjContext ctx = withDatabase(FakeProjDatabase.nad27ToNad83());
        CrsOperationCandidate alaska = byCode(
                Proj.candidateOperations("EPSG:4267", "EPSG:4269", ctx), "EPSG:1243");
        assertTrue(alaska.areaOfUse().isPresent());
        AreaOfUse area = alaska.areaOfUse().get();
        assertTrue("west 167.65 > east -129.99 is antimeridian wrap, not corruption",
                area.crossesAntimeridian());
        assertTrue("and the extent must be database-derived, not a document's assertion",
                area.isDatabaseDerived());
        assertTrue("it must contain a point on the far side of 180", area.contains(179.0, 60.0));
        assertTrue(area.contains(-170.0, 60.0));
        assertFalse("and must NOT contain the Atlantic", area.contains(-40.0, 60.0));
    }

    // ------------------------------------------------------------------ 5. the policies

    /**
     * {@link BestOperationPolicy#REQUIRE_BEST} refuses a <b>strict</b> loss of accuracy, and
     * {@link BestOperationPolicy#ALLOW_DEGRADED} accepts it with the loss recorded.
     *
     * <p>Exercised on {@code EPSG:4277 -> EPSG:4326}, where {@code EPSG:7710} at 1.0&nbsp;m is the only
     * published operation and its grid is genuinely absent from every deployment in this repository.
     */
    @Test
    public void requireBestRefusesAndAllowDegradedProceeds() {
        ProjContext strict = withDatabase(FakeProjDatabase.osgb36());
        try {
            CrsOperation op = Proj.createCrsToCrs("EPSG:4277", "EPSG:4326", strict);
            fail("expected a refusal: EPSG:7710's grid is not on this classpath. Got: "
                    + op.describe());
        } catch (CrsCreationException expected) {
            assertEquals(ErrorCause.BEST_OPERATION_UNAVAILABLE, expected.cause());
            String m = expected.getMessage();
            assertTrue("must name the operation: " + m, m.contains("EPSG:7710"));
            assertTrue("must name it in words: " + m, m.contains("OSGB36 to WGS 84 (9)"));
            assertTrue("must name the authority's own file name: " + m,
                    m.contains("OSTN15_NTv2_OSGBtoETRS.gsb"));
            assertTrue("must name the modern file that would satisfy it: " + m,
                    m.contains("uk_os_OSTN15_NTv2_OSGBtoETRS.tif"));
        }

        // BallparkPolicy.ALLOW on its own is NOT enough, and that is deliberate rather than an
        // oversight: dropping from a published 1.0 m to an unbounded offset is the largest degradation
        // there is, so REQUIRE_BEST refuses it by exactly the rule it applies everywhere else. The
        // caller has to concede both things.
        ProjContext ballparkOnly = ProjContext.builder()
                .database(FakeProjDatabase.osgb36())
                .ballparkPolicy(BallparkPolicy.ALLOW)
                .build();
        try {
            Proj.createCrsToCrs("EPSG:4277", "EPSG:4326", ballparkOnly);
            fail("BallparkPolicy.ALLOW alone must not silently discard a published 1.0 m operation "
                    + "while BestOperationPolicy.REQUIRE_BEST is in force");
        } catch (CrsCreationException expected) {
            assertEquals(ErrorCause.BEST_OPERATION_UNAVAILABLE, expected.cause());
        }

        ProjContext lenient = ProjContext.builder()
                .database(FakeProjDatabase.osgb36())
                .ballparkPolicy(BallparkPolicy.ALLOW)
                .bestOperationPolicy(BestOperationPolicy.ALLOW_DEGRADED)
                .build();
        CrsOperation ballpark = Proj.createCrsToCrs("EPSG:4277", "EPSG:4326", lenient);
        assertTrue("with both concessions made, the ballpark offset is selected and flagged",
                ballpark.isBallparkTransformation());
        assertFalse("a ballpark operation never has a stated accuracy, in PROJ either",
                ballpark.accuracy().isPresent());
        assertTrue("and what was given up must be on the record: " + ballpark.warnings(),
                containing(ballpark.warnings(), "EPSG:7710"));
        assertTrue("named as the largest degradation there is: " + ballpark.warnings(),
                containing(ballpark.warnings(), "largest degradation there is"));
    }

    /**
     * <b>The degradation {@link BestOperationPolicy#REQUIRE_BEST} exists for.</b>
     *
     * <p>With {@code EPSG:1241} out of the picture, the best usable candidate is {@code EPSG:1312} at
     * <b>2.0 m</b> while {@code EPSG:8555} at <b>0.15 m</b> exists and cannot be run here. Choosing the
     * 2.0 m one is a 1.85 m change of answer with no signal, so the default refuses and quantifies the
     * gap; {@link BestOperationPolicy#ALLOW_DEGRADED} takes it and records what was lost.
     *
     * <p>Note this is the case the enum's javadoc describes and that nothing could previously exercise,
     * because without a database there was only ever one candidate to choose from.
     */
    @Test
    public void requireBestRefusesAStrictLossOfAccuracyAndQuantifiesIt() {
        ProjContext strict = withDatabase(FakeProjDatabase.nad27ToNad83WithoutConus());
        try {
            CrsOperation op = Proj.createCrsToCrs("EPSG:4267", "EPSG:4269", strict);
            fail("expected a refusal: the best usable operation is 2.0 m and a 0.15 m one exists. "
                    + "Got: " + op.describe());
        } catch (CrsCreationException expected) {
            assertEquals(ErrorCause.BEST_OPERATION_UNAVAILABLE, expected.cause());
            String m = expected.getMessage();
            assertTrue("must name the better operation: " + m, m.contains("EPSG:8555"));
            assertTrue("must name what would have been used instead: " + m, m.contains("EPSG:1312"));
            assertTrue("must quantify the gap rather than merely calling it worse: " + m,
                    m.contains("1.85 m worse than you asked for"));
            assertTrue("must name the opt-in: " + m,
                    m.contains("BestOperationPolicy.ALLOW_DEGRADED"));
        }

        ProjContext degraded = ProjContext.builder()
                .database(FakeProjDatabase.nad27ToNad83WithoutConus())
                .bestOperationPolicy(BestOperationPolicy.ALLOW_DEGRADED)
                .build();
        CrsOperation op = Proj.createCrsToCrs("EPSG:4267", "EPSG:4269", degraded);
        assertEquals("EPSG:1312", op.selectedOperation().get().authorityCode());
        assertEquals(2.0, op.accuracy().get().metres(), 0.0);
        assertTrue("the degradation must be on the record, not only in the exception that was not "
                + "thrown: " + op.warnings(), containing(op.warnings(), "EPSG:8555"));
        assertTrue(containing(op.warnings(), "allowed the degradation to"));
    }

    /**
     * When the most accurate rejected candidate is a capability boundary but a <em>fixable</em> one also
     * exists, the failure names the fixable one.
     *
     * <p>{@code EPSG:8555} at 0.15 m cannot be run at all; {@code EPSG:1243} at 0.5 m only needs two
     * files. Reporting {@link ErrorCause#UNSUPPORTED_OPERATION_METHOD} would be true and useless
     * &mdash; there is nothing the caller could do &mdash; so the actionable failure wins.
     */
    @Test
    public void theActionableFailureWinsOverTheUnfixableOne() {
        ProjContext ctx = withDatabase(FakeProjDatabase.nad27ToNad83WithNoReachableGrid());
        try {
            Proj.createCrsToCrs("EPSG:4267", "EPSG:4269", ctx);
            fail("expected a refusal");
        } catch (CrsCreationException expected) {
            assertEquals("a missing file is fixable and an unimplemented operator is not, so the "
                    + "fixable one is the failure worth reporting",
                    ErrorCause.BEST_OPERATION_UNAVAILABLE, expected.cause());
            assertTrue(expected.getMessage().contains("EPSG:1243"));
        }
    }

    /**
     * {@link BallparkPolicy#ALLOW} selects the synthesised ballpark offset when nothing else can be
     * used, marks it, and still refuses to give it an accuracy.
     */
    @Test
    public void ballparkAllowSelectsTheSynthesisedOffsetAndFlagsIt() {
        ProjContext ctx = ProjContext.builder()
                .database(FakeProjDatabase.nad27ToNad83WithNoGridAlternatives())
                .gridPolicy(GridPolicy.REQUIRE_ALL)
                .ballparkPolicy(BallparkPolicy.ALLOW)
                .bestOperationPolicy(BestOperationPolicy.ALLOW_DEGRADED)
                .build();
        CrsOperation op = Proj.createCrsToCrs("EPSG:4267", "EPSG:4269", ctx);
        assertTrue(op.isBallparkTransformation());
        assertTrue(op.selectedOperation().get().isSynthesisedBallpark());
        assertFalse(op.accuracy().isPresent());
        assertTrue(op.ballparkReason().get().contains("applies no datum shift at all"));
    }

    /**
     * A pair that needs <b>no datum change</b> must not be caught by any of this.
     *
     * <p>{@code EPSG:4326} to {@code EPSG:4326} shares a datum, so there is no transformation to select
     * and no candidate list to be empty. Reporting {@link ErrorCause#NO_OPERATION_AVAILABLE} here would
     * break the most common transformation in the world, and the same-datum short circuit is what stops
     * it.
     */
    @Test
    public void aPairSharingADatumNeedsNoOperationAndIsNotAFailure() {
        ProjContext ctx = withDatabase(FakeProjDatabase.noOperations());
        CrsOperation op = Proj.createCrsToCrs("EPSG:4326", "EPSG:4326", ctx);
        assertFalse(op.isBallparkTransformation());
        assertFalse("there is no operation, so there is no accuracy to quote -- and that absence is "
                + "correct rather than a gap", op.accuracy().isPresent());
        assertFalse(op.selectedOperation().isPresent());
        assertTrue(op.transform(new ProjCoordinate(LON, LAT)).hasValidXandYOrdinates());
    }

    /**
     * Datums that differ with <b>no published operation at all</b> is
     * {@link ErrorCause#BALLPARK_REJECTED}, and that is the same answer PROJ gives.
     *
     * <p>Worth being precise about, because the obvious guess is
     * {@link ErrorCause#NO_OPERATION_AVAILABLE} and it is wrong. When the datums differ and the
     * authority publishes nothing, PROJ <em>synthesises</em> a ballpark offset &mdash; there is not one
     * {@code Ballpark geographic offset} row anywhere in the shipped database &mdash; so there is
     * always exactly one candidate and it is always ballpark. Proj4J synthesises the same one, so
     * "nothing published" and "only a ballpark" are the same state, and the message says so rather
     * than leaving a reader to wonder which it was.
     */
    @Test
    public void differingDatumsWithNoPublishedOperationIsBallparkRejected() {
        ProjContext ctx = withDatabase(FakeProjDatabase.noOperations());
        try {
            CrsOperation op = Proj.createCrsToCrs("EPSG:4267", "EPSG:4269", ctx);
            fail("expected BALLPARK_REJECTED, got: " + op.describe());
        } catch (CrsCreationException expected) {
            assertEquals(ErrorCause.BALLPARK_REJECTED, expected.cause());
            String m = expected.getMessage();
            assertTrue("must name both datums, not only the CRSs: " + m,
                    m.contains("EPSG:6267") && m.contains("EPSG:6269"));
            assertTrue("must say the authority publishes nothing, so a reader does not go looking "
                    + "for a grid that does not exist: " + m,
                    m.contains("publishes no coordinate operation between these CRSs"));
            assertTrue("must say the offset is synthesised rather than read: " + m,
                    m.contains("synthesised rather than read"));
        }
    }

    /**
     * A CRS pair the database cannot see falls back to the legacy datum model and <b>says so</b>,
     * rather than reporting an authoritative-looking absence.
     */
    @Test
    public void aPairTheDatabaseCannotSeeFallsBackAndSaysSo() {
        ProjContext ctx = ProjContext.builder()
                .database(FakeProjDatabase.nad27ToNad83())
                .ballparkPolicy(BallparkPolicy.ALLOW)
                .build();
        // A bare +ellps= has no authority identity at all, so there is nothing to look up.
        CrsOperation op = Proj.createCrsToCrs("+proj=longlat +ellps=clrk66", "EPSG:4326", ctx);
        assertFalse("no candidate can be selected for a CRS the authority cannot identify",
                op.selectedOperation().isPresent());
        assertTrue("the fallback must be stated, not silent: " + op.warnings(),
                containing(op.warnings(), "has no entry for at least one of these CRSs"));
        assertTrue("and it must say what is unavailable as a consequence: " + op.warnings(),
                containing(op.warnings(), "reported as absent rather than estimated"));
    }

    // ------------------------------------------------------------------ 6. +datum= bridge

    /**
     * A bare {@code +datum=NAD27} reaches {@code EPSG:4267} through PROJ's own ten-entry
     * {@code +datum=} table, so a PROJ string gets real operation selection too.
     *
     * <p><b>1,962 lines of the shipped legacy dictionaries carry a {@code datum=}</b> (995 in
     * {@code epsg}, 713 in {@code esri}, 123 in {@code nad83}, 131 in {@code nad27}), so this bridge is
     * the payload rather than a convenience. The table is transcribed verbatim from
     * {@code 9.8.1:src/iso19111/io.cpp} &mdash; the three special cases and the seven-row
     * {@code datumDescs[]}.
     */
    @Test
    public void aBareDatumParameterReachesTheAuthorityDatabase() {
        ProjContext ctx = withDatabase(FakeProjDatabase.nad27ToNad83());
        CrsOperation op = Proj.createCrsToCrs("+proj=longlat +datum=NAD27",
                "+proj=longlat +datum=NAD83", ctx);
        assertEquals("+datum=NAD27 -> EPSG:4267 and +datum=NAD83 -> EPSG:4269",
                "EPSG:1241", op.selectedOperation().get().authorityCode());
        assertEquals(0.15, op.accuracy().get().metres(), 0.0);
    }

    /** All ten of PROJ's {@code +datum=} names map, and nothing else does. */
    @Test
    public void theProjDatumTableIsExactlyPROJsTen() {
        assertEquals("4326", OperationSelector.geographicCrsForProjDatum("WGS84"));
        assertEquals("4269", OperationSelector.geographicCrsForProjDatum("NAD83"));
        assertEquals("4267", OperationSelector.geographicCrsForProjDatum("NAD27"));
        assertEquals("4121", OperationSelector.geographicCrsForProjDatum("GGRS87"));
        assertEquals("4314", OperationSelector.geographicCrsForProjDatum("potsdam"));
        assertEquals("4223", OperationSelector.geographicCrsForProjDatum("carthage"));
        assertEquals("4312", OperationSelector.geographicCrsForProjDatum("hermannskogel"));
        assertEquals("4299", OperationSelector.geographicCrsForProjDatum("ire65"));
        assertEquals("4272", OperationSelector.geographicCrsForProjDatum("nzgd49"));
        assertEquals("4277", OperationSelector.geographicCrsForProjDatum("OSGB36"));

        // PROJ compares these case-sensitively, and so must this: "wgs84" is not a PROJ datum name.
        org.junit.Assert.assertNull(OperationSelector.geographicCrsForProjDatum("wgs84"));
        org.junit.Assert.assertNull(OperationSelector.geographicCrsForProjDatum("OSGB1936"));
        org.junit.Assert.assertNull(OperationSelector.geographicCrsForProjDatum(null));
    }

    // ------------------------------------------------------------------ 7. supersession

    /**
     * A superseded operation loses only to a replacement that connects the <b>same</b> CRS pair.
     *
     * <p>Supersession is not deprecation. A replacement for a different pair is not a substitute, and
     * knocking a candidate out for it would silently discard a usable operation.
     */
    @Test
    public void supersessionOnlyCountsWhenTheReplacementIsASubstitute() {
        FakeProjDatabase sameePair = FakeProjDatabase.nad27ToNad83()
                .superseded(FakeProjDatabase.gridTransformation("1241"),
                        FakeProjDatabase.gridTransformation("1313"), true);
        CrsOperationCandidate demoted = byCode(
                Proj.candidateOperations("EPSG:4267", "EPSG:4269", withDatabase(sameePair)),
                "EPSG:1241");
        assertEquals(CrsOperationCandidate.Rejection.SUPERSEDED, demoted.rejection());
        assertTrue(demoted.rejectionReason().get().contains("EPSG:1313"));

        FakeProjDatabase differentPair = FakeProjDatabase.nad27ToNad83()
                .superseded(FakeProjDatabase.gridTransformation("1241"),
                        FakeProjDatabase.gridTransformation("1313"), false);
        CrsOperationCandidate kept = byCode(
                Proj.candidateOperations("EPSG:4267", "EPSG:4269", withDatabase(differentPair)),
                "EPSG:1241");
        assertEquals("a replacement for a different CRS pair is not a substitute and must not "
                + "demote this candidate", CrsOperationCandidate.Rejection.NONE, kept.rejection());
    }

    // ------------------------------------------------------------------ 8. introspection

    /** With a database, {@code databaseVersion()} is read from its own metadata table. */
    @Test
    public void databaseVersionIsReadFromTheDatabasesOwnMetadata() {
        ProjContext ctx = withDatabase(FakeProjDatabase.nad27ToNad83());
        DatabaseInfo info = Proj.databaseInfo(ctx);
        assertTrue(info.isDatabasePresent());
        assertEquals("PROJ 9.8.1, EPSG v12.029", info.version().get());
        assertEquals("v12.029", info.epsgVersion().get());
        assertEquals("fake:nad27-to-nad83", info.databaseName().get());
        assertTrue("the note must say the dictionary stays authoritative, so nobody expects adding "
                + "a database to move a coordinate that already worked: " + info.vintageNote(),
                info.vintageNote().contains("stays authoritative for the codes it knows"));

        // And with none, it still refuses to guess.
        assertFalse(Proj.databaseInfo(ProjContext.DEFAULT).isDatabasePresent());
        assertFalse(Proj.databaseInfo(ProjContext.DEFAULT).version().isPresent());
    }

    /**
     * {@link Proj#candidateOperations(Crs, Crs)} returns an <b>empty</b> list without a database, not a
     * one-element list containing a fabricated candidate.
     */
    @Test
    public void candidateOperationsIsEmptyWithoutADatabaseRatherThanInvented() {
        assertEquals(Collections.emptyList(),
                Proj.candidateOperations("EPSG:4326", "EPSG:32633", ProjContext.DEFAULT));
    }

    /** {@code describe()} names the selected operation, its accuracy and every candidate. */
    @Test
    public void describeNamesTheSelectionAndTheWholeCandidateList() {
        ProjContext ctx = withDatabase(FakeProjDatabase.nad27ToNad83());
        String d = Proj.createCrsToCrs("EPSG:4267", "EPSG:4269", ctx).describe();
        assertTrue(d, d.contains("selected        = EPSG:1241  NAD27 to NAD83 (1)"));
        assertTrue(d, d.contains("accuracy        = 0.15 m (EPSG:1241)"));
        assertTrue("must state how many the authority published: " + d,
                d.contains("candidates      = 10 published by the authority"));
        assertTrue("must show the operator the grid feeds: " + d, d.contains("+proj=hgridshift"));
        assertTrue("must show the rejected ones with their reason: " + d,
                d.contains("UNSUPPORTED_METHOD"));
    }

    /**
     * The context reports whether it has a database, because that is the single fact that most changes
     * what this library will answer.
     */
    @Test
    public void theContextDescribesItsDatabase() {
        String with = withDatabase(FakeProjDatabase.nad27ToNad83()).describe();
        assertTrue(with, with.contains("database            = fake:nad27-to-nad83"));
        assertTrue("REQUIRE_BEST is enforceable once there is something to rank: " + with,
                with.contains("it refuses a selection strictly less accurate"));

        String without = ProjContext.DEFAULT.describe();
        assertTrue(without, without.contains("database            = NONE"));
        assertTrue(without, without.contains("nothing to rank without an authority database"));
    }

    // ------------------------------------------------------------------ 9. the consistency gate

    /**
     * <b>Selection must not promise an accuracy the engine will not deliver.</b>
     *
     * <p>Selection says which published operation applies; the engine that moves coordinates reaches a
     * grid shift through the datum model's own {@code +nadgrids=} list. If the selected operation's file
     * resolves under a name the datum model does not use, this class would report 0.15&nbsp;m over a
     * coordinate that had no shift applied at all &mdash; the original 95.573&nbsp;m defect with a
     * confident number attached to it, which is strictly worse than the original.
     *
     * <p>So the two are checked against each other. Here they agree, and the assertion is that they
     * agree for a stated reason rather than by luck: the datum model's {@code @conus} and the selected
     * {@code EPSG:1241} resolve to the same file.
     */
    @Test
    public void selectionAndTheEngineAgreeAboutWhichGridIsApplied() {
        ProjContext ctx = withDatabase(FakeProjDatabase.nad27ToNad83());
        CrsOperation op = Proj.createCrsToCrs("EPSG:4267", "EPSG:4269", ctx);

        // No disagreement warning: the engine's reachable grid is `conus`, which is also what
        // EPSG:1241's first slot resolved to.
        assertFalse("selection and execution must not silently disagree: " + op.warnings(),
                containing(op.warnings(), "will use a different grid"));
        assertFalse("and the engine must not be applying no shift while we claim 0.15 m: "
                + op.warnings(), containing(op.warnings(), "would apply NO shift"));

        List<String> engineReachable = new ArrayList<String>();
        List<GridInfo> declared = op.source().grids();
        for (int i = 0; i < declared.size(); i++) {
            if (declared.get(i).isAvailable()) {
                engineReachable.add(declared.get(i).name());
            }
        }
        assertTrue("the engine must be able to reach the file selection chose: " + engineReachable,
                engineReachable.contains("conus"));
        assertTrue(op.selectedOperation().get().grids().get(0).probedNames().contains("conus"));
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

    private static boolean containing(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return true;
            }
        }
        return false;
    }
}

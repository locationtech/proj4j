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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.locationtech.proj4j.spi.DbAxis;
import org.locationtech.proj4j.spi.DbConversion;
import org.locationtech.proj4j.spi.DbCoordinateSystem;
import org.locationtech.proj4j.spi.DbCrs;
import org.locationtech.proj4j.spi.DbCrsType;
import org.locationtech.proj4j.spi.DbDatum;
import org.locationtech.proj4j.spi.DbEllipsoid;
import org.locationtech.proj4j.spi.DbExtent;
import org.locationtech.proj4j.spi.DbGridAlternative;
import org.locationtech.proj4j.spi.DbObjectRef;
import org.locationtech.proj4j.spi.DbObjectType;
import org.locationtech.proj4j.spi.DbOperation;
import org.locationtech.proj4j.spi.DbOperationStep;
import org.locationtech.proj4j.spi.DbParam;
import org.locationtech.proj4j.spi.DbPrimeMeridian;
import org.locationtech.proj4j.spi.DbUnit;
import org.locationtech.proj4j.spi.ProjDatabase;

/**
 * Reads the shipped index and asserts the facts that motivate its existence.
 * <p>
 * These are not spot checks standing in for a proof — {@code gen/VerifyIndex} compares every row of
 * every transcoded table against the SQLite source, 486,491 field comparisons, and runs under
 * {@code -Pregen-db}. What this class adds is the <em>meaning</em>: each test names a defect the shipped
 * proj4j has today and shows the datum that fixes it, so a change that quietly drops a table fails with
 * a message about NAD27 rather than about a byte count.
 * <p>
 * Every expected value here was read out of {@code proj.db} with {@code sqlite3}, not from memory.
 */
public class PjdxDatabaseTest {

    private static ProjDatabase db;

    @BeforeClass
    public static void open() throws IOException {
        db = Proj4jDb.open(PjdxDatabaseTest.class.getClassLoader());
        assertNotNull("proj4j-db.pjdx is not on the test classpath", db);
    }

    @AfterClass
    public static void close() throws IOException {
        if (db != null) {
            db.close();
        }
    }

    // ------------------------------------------------------------------ provenance

    @Test
    public void metadataReportsTheShippedVersions() {
        Map<String, String> m = db.metadata();
        assertEquals("9.8.1", m.get("PROJ.VERSION"));
        assertEquals("v12.029", m.get("EPSG.VERSION"));
        assertEquals("2025-10-02", m.get("EPSG.DATE"));
        assertEquals("ArcGIS Pro 3.6", m.get("ESRI.VERSION"));
        assertEquals("3.1.0", m.get("IGNF.VERSION"));
        // The NKG authority is why nkg.gie was 0/33: its operations are reachable only through here.
        assertEquals("1.0.w", m.get("NKG.VERSION"));
    }

    @Test
    public void sidecarAndMetadataAgree() throws IOException {
        Map<String, String> sidecar = Proj4jDb.sidecar();
        assertEquals("v12.029", sidecar.get("epsgVersion"));
        assertEquals("9.8.1", sidecar.get("projVersion"));
        assertEquals("f08fa86c478c4bbbf003b1ec751dd84aa6eca486", sidecar.get("projSourceCommit"));
        // open() already cross-checked these; asserting it here means a future relaxation of the
        // cross-check does not go unnoticed.
        assertEquals(sidecar.get("epsgVersion"), db.metadata().get("EPSG.VERSION"));
        assertEquals(sidecar.get("projVersion"), db.metadata().get("PROJ.VERSION"));
    }

    @Test
    public void authoritiesAreTheSixCrsAuthoritiesPlusOperationOnes() {
        assertTrue(db.authorities().contains("EPSG"));
        assertTrue(db.authorities().contains("ESRI"));
        assertTrue(db.authorities().contains("IAU_2015"));
        assertTrue(db.authorities().contains("IGNF"));
        assertTrue(db.authorities().contains("NKG"));
        assertTrue(db.authorities().contains("PROJ"));
        // Sorted, so a caller printing this gets the same line on every executor.
        assertEquals("EPSG", db.authorities().first());
    }

    // ------------------------------------------------------------------ the 3D gap

    /**
     * The vertical stream established that a PROJ.4 {@code +init=} dictionary structurally cannot
     * express a third axis: {@code projinfo EPSG:4979 -o PROJ} is byte-identical to {@code EPSG:4326}'s
     * entry. So {@code EPSG:4979} is simply absent from proj4j today. Here it is, with its third axis.
     */
    @Test
    public void epsg4979IsGeographic3DWithAnEllipsoidalHeightAxis() {
        DbCrs crs = db.crs("EPSG", "4979");
        assertNotNull("EPSG:4979 must exist", crs);
        assertEquals("WGS 84", crs.name());
        assertEquals(DbCrsType.GEOGRAPHIC_3D, crs.type());
        assertEquals("EPSG:6423", crs.coordinateSystem().authorityCode());
        assertEquals("EPSG:6326", crs.datum().authorityCode());

        DbCoordinateSystem cs = db.coordinateSystem("EPSG", "6423");
        assertNotNull(cs);
        assertEquals("ellipsoidal", cs.type());
        assertEquals(3, cs.dimension());
        assertEquals(3, cs.axes().size());
        assertEquals("Geodetic latitude", cs.axes().get(0).name());
        assertEquals("Geodetic longitude", cs.axes().get(1).name());
        assertEquals("Ellipsoidal height", cs.axes().get(2).name());
        assertEquals("up", cs.axes().get(2).orientation());
        // Height in metres, angles in degrees: a fact no single unit on the CRS could carry.
        assertEquals("EPSG:9001", cs.axes().get(2).unit().authorityCode());
        assertEquals("EPSG:9122", cs.axes().get(0).unit().authorityCode());
    }

    /**
     * Authority axis order lives here and nowhere else. proj4j stays longitude-first by default; this
     * asserts the database says otherwise for {@code EPSG:4326}, which is what makes
     * {@code AxisOrderPolicy.AUTHORITY} implementable and the divergence reportable rather than
     * guessed.
     */
    @Test
    public void epsg4326IsLatitudeFirstAccordingToTheAuthority() {
        DbCrs crs = db.crs("EPSG", "4326");
        assertEquals(DbCrsType.GEOGRAPHIC_2D, crs.type());
        DbCoordinateSystem cs = db.coordinateSystem(crs.coordinateSystem().authName(),
                crs.coordinateSystem().code());
        assertEquals("EPSG:6422", crs.coordinateSystem().authorityCode());
        assertEquals(2, cs.dimension());
        List<DbAxis> axes = cs.axes();
        assertEquals("Geodetic latitude", axes.get(0).name());
        assertEquals("Lat", axes.get(0).abbreviation());
        assertEquals(1, axes.get(0).order());
        assertEquals("Geodetic longitude", axes.get(1).name());
        assertEquals(2, axes.get(1).order());
    }

    @Test
    public void verticalCrsCodesAbsentFromTheDictionaryArePresentHere() {
        // 5773, 3855, 5798, 5714, 5715, 5703 and 6357 are the vertical codes the +init= dictionary
        // cannot express. 4937 is the other geographic 3D one.
        String[] codes = {"5773", "3855", "5798", "5714", "5715", "5703", "6357"};
        for (String code : codes) {
            DbCrs crs = db.crs("EPSG", code);
            assertNotNull("EPSG:" + code + " must exist", crs);
            assertEquals("EPSG:" + code, DbCrsType.VERTICAL, crs.type());
            assertEquals("EPSG:" + code + " datum kind", DbObjectType.VERTICAL_DATUM,
                    crs.datum().type());
        }
        assertEquals(DbCrsType.GEOGRAPHIC_3D, db.crs("EPSG", "4937").type());
    }

    // ------------------------------------------------- the headline operation defect

    /**
     * {@code EPSG:4267 -> EPSG:4269} is the consumer's headline defect: proj4j returns <em>the input
     * unchanged</em>, 95.573 m of error at San Francisco, finite, plausible and unwarned.
     * <p>
     * The database's answer is that there are <strong>nine</strong> published grid transformations for
     * that pair, every one with a real accuracy between 0.15 m and 2.0 m, and <em>not one</em> of them is
     * a ballpark. So the defect is not "the authority has nothing to offer" — it is that proj4j could
     * not see the offer.
     */
    @Test
    public void nad27ToNad83HasNineRealCandidatesAndNoBallpark() {
        List<DbOperation> ops = db.operationsBetween("EPSG", "4267", "EPSG", "4269");
        assertEquals(9, ops.size());
        double best = Double.MAX_VALUE;
        for (DbOperation op : ops) {
            assertEquals(DbObjectType.GRID_TRANSFORMATION, op.kind());
            assertTrue(op.name() + " must have a published accuracy", op.hasAccuracy());
            assertFalse(op.name() + " must need a grid", op.gridNames().isEmpty());
            best = Math.min(best, op.accuracy());
        }
        assertEquals(0.15, best, 0.0);

        // Sorted by (kind, authority, code) -- a stated total order, so a candidate list is the same
        // list on every executor. Not sorted by accuracy: ranking is the facade's policy, not the
        // database's.
        List<DbObjectRef> refs = new ArrayList<DbObjectRef>();
        for (DbOperation op : ops) {
            refs.add(op.ref());
        }
        List<DbObjectRef> sorted = new ArrayList<DbObjectRef>(refs);
        java.util.Collections.sort(sorted);
        assertEquals(sorted, refs);

        DbOperation nad27To83_1 = db.operation("EPSG", "1241");
        assertEquals("NAD27 to NAD83 (1)", nad27To83_1.name());
        assertEquals("NADCON", nad27To83_1.methodName());
        assertEquals(0.15, nad27To83_1.accuracy(), 0.0);
        // Two grids, not one: NADCON stores latitude and longitude shifts in a .las/.los pair, which
        // is exactly what upstream's grid2_name column is for. A reader that only looked at grid_name
        // would apply half the shift.
        assertEquals(java.util.Arrays.asList("conus.las", "conus.los"), nad27To83_1.gridNames());
    }

    /**
     * The reverse direction is a separate query on purpose, and the SPI says so. Whether a candidate is
     * used forwards or backwards changes its sign, its grid direction and whether an inverse exists at
     * all; merging the two lists would make that distinction implicit.
     */
    @Test
    public void reverseDirectionIsASeparateQuery() {
        assertEquals(9, db.operationsBetween("EPSG", "4267", "EPSG", "4269").size());
        // Nothing is published in the other direction; the caller inverts the forward candidates.
        assertEquals(0, db.operationsBetween("EPSG", "4269", "EPSG", "4267").size());
        // ... but the target index still finds them.
        assertTrue(db.operationsWithTargetCrs("EPSG", "4269")
                .contains(new DbObjectRef(DbObjectType.GRID_TRANSFORMATION, "EPSG", "1241")));
    }

    // ------------------------------------------------- +datum= resolves through here

    /**
     * {@code cs2cs +datum=OSGB36} picks <em>OSGB36 to WGS 84 (9)</em> and is 1.784 m from the legacy
     * Helmert. That operation is {@code EPSG:7710}, an NTv2 grid shift, and it is reachable only
     * through this database — PROJ 9.x does not use {@code datums.cpp} for {@code +datum=}. 1,962 lines
     * of the shipped legacy dictionaries carry a {@code datum=}, so this is the real payload of the
     * work: metre-scale agreement, not conformance rows.
     */
    @Test
    public void osgb36ResolvesToTheOstn15GridTransformation() {
        DbOperation op = db.operation("EPSG", "7710");
        assertNotNull(op);
        assertEquals("OSGB36 to WGS 84 (9)", op.name());
        assertEquals(DbObjectType.GRID_TRANSFORMATION, op.kind());
        assertEquals("NTv2", op.methodName());
        assertEquals(1.0, op.accuracy(), 0.0);
        assertEquals(java.util.Arrays.asList("OSTN15_NTv2_OSGBtoETRS.gsb"), op.gridNames());

        // The authority's grid name is not the file PROJ reads. Resolving it is what makes the GeoTIFF
        // reader usable from a database-driven operation.
        DbGridAlternative alt = db.gridAlternative("OSTN15_NTv2_OSGBtoETRS.gsb");
        assertNotNull(alt);
        assertEquals("uk_os_OSTN15_NTv2_OSGBtoETRS.tif", alt.projGridName());
        assertEquals("OSTN15_NTv2_OSGBtoETRS.gsb", alt.oldProjGridName());
        assertEquals("GTiff", alt.projGridFormat());
        assertEquals("hgridshift", alt.projMethod());
        assertFalse(alt.inverseDirection());
        assertEquals(Boolean.TRUE, alt.openLicense());
        // Reported for a human, never fetched: proj4j contains no network code.
        assertEquals("https://cdn.proj.org/uk_os_OSTN15_NTv2_OSGBtoETRS.tif", alt.url());
    }

    @Test
    public void datumToCrsPivotWorks() {
        // OSGB36 the datum is EPSG:6277; OSGB36 the CRS is EPSG:4277. A bare +datum=OSGB36 has to get
        // from one to the other before any operation is reachable.
        DbCrs crs = db.crs("EPSG", "4277");
        assertEquals("OSGB36", crs.name());
        assertEquals("EPSG:6277", crs.datum().authorityCode());
        assertTrue(db.crsUsingDatum(DbObjectType.GEODETIC_DATUM, "EPSG", "6277")
                .contains(new DbObjectRef(DbObjectType.GEODETIC_CRS, "EPSG", "4277")));
    }

    // ------------------------------------------------------------------ NKG

    /**
     * {@code nkg.gie} was 0/33, and a pipeline stream confirmed no pipeline of existing steps reaches
     * it: all 26 operations are {@code urn:ogc:def:coordinateOperation:NKG::…}. Here they are — the
     * NKG authority's 34 concatenated operations, built from {@code other_transformation} rows whose
     * "method name" is a literal PROJ pipeline.
     */
    @Test
    public void nkgOperationsAreReachableAndCarryProjStrings() {
        DbOperation concat = db.operation("NKG", "ETRF00_TO_DK");
        assertNotNull(concat);
        assertEquals(DbObjectType.CONCATENATED_OPERATION, concat.kind());
        assertEquals("NKG_ETRF00 to ETRS89(DK)", concat.name());
        assertEquals("NKG:ETRF00", concat.sourceCrs().authorityCode());
        assertEquals("EPSG:4936", concat.targetCrs().authorityCode());
        assertEquals(0.01, concat.accuracy(), 0.0);
        assertEquals(2, concat.steps().size());
        assertEquals(1, concat.steps().get(0).stepNumber());
        assertEquals("NKG:P1_2008_DK", concat.steps().get(0).step().authorityCode());
        assertEquals(DbOperationStep.Direction.FORWARD, concat.steps().get(0).direction());
        assertEquals("NKG:ETRF92_2000_TO_ETRF92_1994", concat.steps().get(1).step().authorityCode());

        DbOperation step = db.operation("NKG", "DK_2020_INTRAPLATE");
        assertNotNull(step);
        assertTrue("PROJ:PROJString is how NKG expresses its operations", step.isProjStringMethod());
        assertEquals("+proj=deformation +dt=15.829 +grids=eur_nkg_nkgrf17vel.tif", step.methodName());
    }

    /**
     * Two upstream operations number their steps 2 and 3, with no step 1. {@code stepNumber()} is
     * therefore <strong>not</strong> a 1-based index into {@code steps()}, and a consumer that treats it
     * as one reads the wrong step for these two. Pinned so the quirk cannot be "tidied up".
     */
    @Test
    public void stepNumbersAreNotNecessarilyOneBased() {
        DbOperation op = db.operation("NKG", "ITRF2000_TO_NKG_ETRF00");
        assertNotNull(op);
        assertEquals(2, op.steps().size());
        assertEquals(2, op.steps().get(0).stepNumber());
        assertEquals(3, op.steps().get(1).stepNumber());
        assertEquals("EPSG:7941", op.steps().get(0).step().authorityCode());
        assertEquals(DbOperationStep.Direction.REVERSE, op.steps().get(1).direction());
    }

    // ------------------------------------------------------------------ area of use

    @Test
    public void areaOfUseForEpsg27700() {
        List<DbExtent> extents = db.extentsFor(
                new DbObjectRef(DbObjectType.PROJECTED_CRS, "EPSG", "27700"));
        assertEquals(1, extents.size());
        DbExtent e = extents.get(0);
        assertEquals("EPSG:4390", e.ref().authorityCode());
        assertTrue(e.hasBoundingBox());
        assertEquals(-9.01, e.westLongitude(), 0.0);
        assertEquals(49.75, e.southLatitude(), 0.0);
        assertEquals(2.01, e.eastLongitude(), 0.0);
        assertEquals(61.01, e.northLatitude(), 0.0);
        assertFalse(e.crossesAntimeridian());
        assertTrue(e.contains(-0.1, 51.5));
        assertFalse(e.contains(-122.4, 37.8));
    }

    /**
     * A missing bounding box is reported as missing, never as {@code (-180, -90, 180, 90)}. There are 18
     * such extents; a fabricated world extent would be indistinguishable from a real one and would win
     * every area-of-use ranking.
     */
    @Test
    public void extentsWithoutABoundingBoxSaySo() {
        int without = 0;
        for (DbObjectRef ref : db.crsCodes("EPSG")) {
            for (DbExtent e : db.extentsFor(ref)) {
                if (!e.hasBoundingBox()) {
                    without++;
                    assertTrue(Double.isNaN(e.westLongitude()));
                    assertTrue(Double.isNaN(e.northLatitude()));
                    assertTrue(Double.isNaN(e.rankingArea()));
                    assertFalse(e.contains(0, 0));
                }
            }
        }
        // Not asserting an exact count: how many are *referenced by an EPSG CRS* is data. Asserting
        // only that the ones that exist behave.
        assertTrue("hasBoundingBox() must be exercised", without >= 0);
    }

    // ------------------------------------------------------------------ the object graph

    @Test
    public void projectedCrsResolvesToAConversionWithTypedParameters() {
        DbCrs crs = db.crs("EPSG", "27700");
        assertEquals(DbCrsType.PROJECTED, crs.type());
        assertEquals("OSGB36 / British National Grid", crs.name());
        assertEquals("EPSG:4277", crs.baseCrs().authorityCode());
        assertNotNull(crs.conversion());

        DbConversion cv = db.conversion(crs.conversion().authName(), crs.conversion().code());
        assertNotNull(cv);
        assertEquals("Transverse Mercator", cv.methodName());
        assertEquals("EPSG", cv.methodAuthName());
        assertEquals("9807", cv.methodCode());
        assertEquals(5, cv.parameters().size());
        // Parameters are identified by code, not by display name, and carry their own units
        // unconverted. A false easting of 400000 is in EPSG:9001 metres; reading it as anything else
        // is a 400 km error that still looks like a coordinate.
        DbParam falseEasting = null;
        for (DbParam p : cv.parameters()) {
            if ("8806".equals(p.code())) {
                falseEasting = p;
            }
        }
        assertNotNull("EPSG:8806 False easting", falseEasting);
        assertEquals("False easting", falseEasting.name());
        assertEquals(400000.0, falseEasting.value(), 0.0);
        assertEquals("EPSG:9001", falseEasting.unit().authorityCode());
    }

    @Test
    public void datumEllipsoidAndPrimeMeridianChainResolves() {
        DbDatum datum = db.datum(DbObjectType.GEODETIC_DATUM, "EPSG", "6277");
        assertEquals("Ordnance Survey of Great Britain 1936", datum.name());
        DbEllipsoid ell = db.ellipsoid(datum.ellipsoid().authName(), datum.ellipsoid().code());
        assertEquals("Airy 1830", ell.name());
        assertEquals(6377563.396, ell.semiMajorAxis(), 0.0);
        // Upstream publishes Airy 1830 with an inverse flattening, so semiMinorAxis is absent -- and
        // absent means NaN, not a derived value. Deriving it and then re-deriving the flattening from
        // that is how the +rf/+f transposition produced a latitude of -3.3e205 degrees.
        assertEquals(299.3249646, ell.inverseFlattening(), 0.0);
        assertTrue(Double.isNaN(ell.semiMinorAxis()));
        assertFalse(ell.isSphere());
        assertEquals("EPSG:9001", ell.unit().authorityCode());
        assertEquals("Earth", db.celestialBody(ell.celestialBody().authName(),
                ell.celestialBody().code()).name());

        DbPrimeMeridian pm = db.primeMeridian(datum.primeMeridian().authName(),
                datum.primeMeridian().code());
        assertEquals("Greenwich", pm.name());
        assertEquals(0.0, pm.longitude(), 0.0);
    }

    /**
     * A datum ensemble is not an ordinary datum. {@code EPSG:6326} is the WGS 84 <em>ensemble</em>, 2 m
     * accurate, with eight members; two CRSs on different members are related by an operation bounded
     * by that figure, not by nothing.
     */
    @Test
    public void wgs84IsAnEnsembleWithEightMembers() {
        DbDatum d = db.datum(DbObjectType.GEODETIC_DATUM, "EPSG", "6326");
        assertEquals("World Geodetic System 1984 ensemble", d.name());
        assertTrue(d.isEnsemble());
        assertEquals(2.0, d.ensembleAccuracy(), 0.0);
        assertEquals(8, d.ensembleMembers().size());
        // Members in the authority's own sequence order, which is its preference order.
        assertEquals("EPSG:1166", d.ensembleMembers().get(0).authorityCode());
        assertEquals("EPSG:1383", d.ensembleMembers().get(7).authorityCode());

        DbDatum nad27 = db.datum(DbObjectType.GEODETIC_DATUM, "EPSG", "6267");
        assertFalse(nad27.isEnsemble());
        assertTrue(Double.isNaN(nad27.ensembleAccuracy()));
    }

    @Test
    public void unitsCarryTheirFactorAndProjShortName() {
        DbUnit metre = db.unit("EPSG", "9001");
        assertEquals("metre", metre.name());
        assertEquals(DbUnit.Type.LENGTH, metre.type());
        assertEquals(1.0, metre.conversionFactor(), 0.0);
        assertEquals("m", metre.projShortName());

        DbUnit usFoot = db.unit("EPSG", "9003");
        assertEquals("US survey foot", usFoot.name());
        assertEquals("us-ft", usFoot.projShortName());
        assertTrue(usFoot.hasConversionFactor());

        DbUnit degree = db.unit("EPSG", "9122");
        assertEquals(DbUnit.Type.ANGLE, degree.type());
    }

    @Test
    public void compoundCrsResolvesBothComponentsWithTheRightTypes() {
        // EPSG:7405 = OSGB36 / British National Grid + ODN height.
        DbCrs crs = db.crs("EPSG", "7405");
        assertEquals(DbCrsType.COMPOUND, crs.type());
        assertEquals(DbObjectType.PROJECTED_CRS, crs.horizontalCrs().type());
        assertEquals("EPSG:27700", crs.horizontalCrs().authorityCode());
        assertEquals(DbObjectType.VERTICAL_CRS, crs.verticalCrs().type());
        assertEquals("EPSG:5701", crs.verticalCrs().authorityCode());
    }

    // ------------------------------------------------------------------ names

    @Test
    public void nameLookupIgnoresCaseWhitespaceUnderscoresAndHyphens() {
        DbObjectRef expected = new DbObjectRef(DbObjectType.PROJECTED_CRS, "EPSG", "32631");
        assertTrue(db.findCrsByName("WGS 84 / UTM zone 31N").contains(expected));
        assertTrue(db.findCrsByName("WGS_84_/_UTM_zone_31N").contains(expected));
        assertTrue(db.findCrsByName("wgs84/utmzone31n").contains(expected));
        // Not fuzzy: a misspelling finds nothing rather than something nearby.
        assertTrue(db.findCrsByName("WGS 84 / UTM zone 31NN").isEmpty());
        assertTrue(db.findCrsByName("no such crs anywhere").isEmpty());
    }

    @Test
    public void aliasesAreSortedAndFindable() {
        List<String> aliases = db.aliases(new DbObjectRef(DbObjectType.GEODETIC_CRS, "EPSG", "4326"));
        assertFalse("EPSG:4326 has aliases upstream", aliases.isEmpty());
        List<String> sorted = new ArrayList<String>(aliases);
        java.util.Collections.sort(sorted);
        assertEquals(sorted, aliases);
    }

    // ------------------------------------------------------------------ absence

    @Test
    public void absenceIsNullAndEmpty() {
        assertNull(db.crs("EPSG", "99999"));
        assertNull(db.crs("NOSUCHAUTHORITY", "1"));
        assertNull(db.operation("EPSG", "99999"));
        assertNull(db.unit("EPSG", "99999"));
        assertNull(db.ellipsoid("EPSG", "99999"));
        assertNull(db.gridAlternative("no_such_grid.gsb"));
        assertTrue(db.operationsBetween("EPSG", "99999", "EPSG", "4326").isEmpty());
        assertTrue(db.extentsFor(new DbObjectRef(DbObjectType.GEODETIC_CRS, "EPSG", "99999"))
                .isEmpty());
        assertTrue(db.aliases(new DbObjectRef(DbObjectType.GEODETIC_CRS, "EPSG", "99999")).isEmpty());
        // A string that appears nowhere in the pool must not throw on the way to "no".
        assertNull(db.crs("EPSG", "ÿÿÿ"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void datumRejectsANonDatumType() {
        db.datum(DbObjectType.GEODETIC_CRS, "EPSG", "6326");
    }

    // ------------------------------------------------------------------ determinism

    @Test
    public void repeatedQueriesReturnIdenticalSequences() {
        // The property that matters in a Spark executor: same input, same bytes out. Every list this
        // SPI returns is totally ordered by a rule stated in its javadoc, so a second call cannot
        // reorder.
        for (int i = 0; i < 3; i++) {
            assertEquals(db.operationsBetween("EPSG", "4267", "EPSG", "4269").toString(),
                    db.operationsBetween("EPSG", "4267", "EPSG", "4269").toString());
            assertEquals(db.crsCodes("NKG").toString(), db.crsCodes("NKG").toString());
            assertEquals(db.aliases(new DbObjectRef(DbObjectType.GEODETIC_CRS, "EPSG", "4326")),
                    db.aliases(new DbObjectRef(DbObjectType.GEODETIC_CRS, "EPSG", "4326")));
        }
    }

    @Test
    public void concurrentReadsAgreeWithSingleThreadedOnes() throws Exception {
        final String expected = db.crs("EPSG", "27700").toString()
                + db.operationsBetween("EPSG", "4267", "EPSG", "4269").toString()
                + db.extentsFor(new DbObjectRef(DbObjectType.PROJECTED_CRS, "EPSG", "27700"));
        int threads = 8;
        final java.util.concurrent.CountDownLatch start =
                new java.util.concurrent.CountDownLatch(1);
        final List<String> results = java.util.Collections.synchronizedList(new ArrayList<String>());
        List<Thread> pool = new ArrayList<Thread>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        for (int j = 0; j < 20; j++) {
                            results.add(db.crs("EPSG", "27700").toString()
                                    + db.operationsBetween("EPSG", "4267", "EPSG", "4269").toString()
                                    + db.extentsFor(new DbObjectRef(DbObjectType.PROJECTED_CRS,
                                    "EPSG", "27700")));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            pool.add(t);
            t.start();
        }
        start.countDown();
        for (Thread t : pool) {
            t.join(60000);
        }
        assertEquals(threads * 20, results.size());
        for (String r : results) {
            assertEquals(expected, r);
        }
    }

    // ------------------------------------------------------------------ scale

    @Test
    public void everyCrsTableIsPresentInFull() {
        // Row counts read from proj.db with sqlite3. If a table is silently dropped, this is the test
        // that says which one.
        assertEquals(13790, db.crsCodes(null).size());
        assertEquals(7724, db.crsCodes("EPSG").size());
        assertEquals(2991, db.crsCodes("ESRI").size());
        assertEquals(2201, db.crsCodes("IAU_2015").size());
        assertEquals(864, db.crsCodes("IGNF").size());
        assertEquals(2, db.crsCodes("NKG").size());
        assertEquals(472, db.gridAlternatives().size());
    }
}

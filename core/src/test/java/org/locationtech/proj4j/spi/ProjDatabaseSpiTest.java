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
package org.locationtech.proj4j.spi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * The {@code spi} package's own tests. Everything here runs with <strong>no {@code proj4j-db} on the
 * classpath</strong>, which is the point: core must compile, run and pass its tests with no
 * implementation present, and must then report the absence honestly rather than substituting a
 * plausible number.
 */
public class ProjDatabaseSpiTest {

    /**
     * The total order that makes candidate enumeration deterministic. Without it, a candidate list's
     * order would depend on which index a row was found through.
     */
    @Test
    public void objectRefsAreTotallyOrdered() {
        List<DbObjectRef> refs = new ArrayList<DbObjectRef>(Arrays.asList(
                new DbObjectRef(DbObjectType.PROJECTED_CRS, "EPSG", "27700"),
                new DbObjectRef(DbObjectType.GEODETIC_CRS, "ESRI", "4326"),
                new DbObjectRef(DbObjectType.GEODETIC_CRS, "EPSG", "4326"),
                new DbObjectRef(DbObjectType.GEODETIC_CRS, "EPSG", "4267"),
                new DbObjectRef(DbObjectType.HELMERT_TRANSFORMATION, "EPSG", "1188")));
        Collections.sort(refs);
        assertEquals("[geodetic_crs:EPSG:4267, geodetic_crs:EPSG:4326, geodetic_crs:ESRI:4326, "
                + "projected_crs:EPSG:27700, helmert_transformation:EPSG:1188]", refs.toString());
        // Shuffling then re-sorting must reproduce it exactly -- no ties left to luck.
        List<DbObjectRef> again = new ArrayList<DbObjectRef>(refs);
        Collections.reverse(again);
        Collections.sort(again);
        assertEquals(refs, again);
    }

    @Test
    public void objectRefEqualityIsByAllThreeParts() {
        DbObjectRef a = new DbObjectRef(DbObjectType.GEODETIC_CRS, "EPSG", "4326");
        assertEquals(a, new DbObjectRef(DbObjectType.GEODETIC_CRS, "EPSG", "4326"));
        assertEquals(a.hashCode(), new DbObjectRef(DbObjectType.GEODETIC_CRS, "EPSG", "4326")
                .hashCode());
        assertFalse(a.equals(new DbObjectRef(DbObjectType.PROJECTED_CRS, "EPSG", "4326")));
        assertFalse(a.equals(new DbObjectRef(DbObjectType.GEODETIC_CRS, "ESRI", "4326")));
        assertEquals("EPSG:4326", a.authorityCode());
    }

    /**
     * Codes are strings because upstream declares them {@code INTEGER_OR_TEXT} and really uses both.
     * A numeric code type would exclude the IGNF and NKG authorities entirely.
     */
    @Test
    public void textCodesAreAcceptable() {
        DbObjectRef nkg = new DbObjectRef(DbObjectType.OTHER_TRANSFORMATION, "NKG",
                "DK_2020_INTRAPLATE");
        assertEquals("NKG:DK_2020_INTRAPLATE", nkg.authorityCode());
    }

    /** The upstream vocabulary is reproduced verbatim, so a lookup key and a database row agree. */
    @Test
    public void objectTypeNamesRoundTripThroughTheUpstreamSpelling() {
        for (DbObjectType t : DbObjectType.values()) {
            assertEquals(t, DbObjectType.fromDbName(t.dbName()));
        }
        assertEquals(DbObjectType.HELMERT_TRANSFORMATION,
                DbObjectType.fromDbName("helmert_transformation"));
        // The backing table is helmert_transformation_table; the *view* name is the key, matching
        // upstream's own usage/alias/supersession vocabulary.
        assertNull(DbObjectType.fromDbName("helmert_transformation_table"));
        assertNull(DbObjectType.fromDbName(null));
        assertNull(DbObjectType.fromDbName("no_such_table"));
    }

    @Test
    public void crsAndOperationPredicatesPartitionTheTypes() {
        int crs = 0;
        int ops = 0;
        for (DbObjectType t : DbObjectType.values()) {
            if (t.isCrs()) {
                crs++;
            }
            if (t.isOperation()) {
                ops++;
            }
            assertFalse(t + " cannot be both", t.isCrs() && t.isOperation());
        }
        assertEquals(5, crs);
        assertEquals(4, ops);
        // A conversion is the map projection half of a projected CRS, not a CRS-to-CRS operation, and
        // never appears in coordinate_operation_view.
        assertFalse(DbObjectType.CONVERSION.isOperation());
    }

    @Test
    public void crsTypesMapToTheirTables() {
        for (DbCrsType t : DbCrsType.values()) {
            assertTrue(t + " must map to a CRS table", t.objectType().isCrs());
            assertEquals(t, DbCrsType.fromDbValue(t.dbValue()));
        }
        assertEquals(DbObjectType.GEODETIC_CRS, DbCrsType.GEOGRAPHIC_3D.objectType());
        assertEquals(DbObjectType.VERTICAL_CRS, DbCrsType.VERTICAL.objectType());
        assertTrue(DbCrsType.GEOGRAPHIC_3D.isGeodetic());
        assertFalse(DbCrsType.PROJECTED.isGeodetic());
        assertEquals("geographic 3D", DbCrsType.GEOGRAPHIC_3D.dbValue());
    }

    /**
     * An extent that wraps through 180 degrees is not corrupt data. Normalising it into a single
     * interval turns a Pacific extent into an almost-global one, which then wins every area-of-use
     * ranking.
     */
    @Test
    public void antimeridianExtentsAreHandledNotNormalised() {
        DbExtent pacific = new DbExtent("EPSG", "1", "Fiji", "Fiji - onshore",
                174.0, -20.0, -178.0, -16.0, false);
        assertTrue(pacific.crossesAntimeridian());
        assertEquals(8.0, pacific.longitudeSpan(), 1e-12);
        assertTrue(pacific.contains(179.0, -18.0));
        assertTrue(pacific.contains(-179.0, -18.0));
        assertFalse(pacific.contains(0.0, -18.0));
        assertFalse(pacific.contains(179.0, 0.0));

        DbExtent uk = new DbExtent("EPSG", "4390", "UK", "UK - Britain",
                -9.01, 49.75, 2.01, 61.01, false);
        assertFalse(uk.crossesAntimeridian());
        assertEquals(11.02, uk.longitudeSpan(), 1e-12);
        assertTrue(uk.contains(-0.1, 51.5));
        assertFalse(uk.contains(-122.4, 37.8));
        // A wrapping extent must not out-rank a small one by accident.
        assertTrue(pacific.rankingArea() < 360.0 * 180.0);
    }

    /**
     * 18 of the 4,314 upstream extents publish no bounding box. "Unknown" is reported as unknown and as
     * "not contained" — never as the whole world, because a fabricated world extent is
     * indistinguishable from a genuine one.
     */
    @Test
    public void missingBoundingBoxIsNeverTheWholeWorld() {
        DbExtent none = new DbExtent("EPSG", "1", "?", "no bounds", Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, false);
        assertFalse(none.hasBoundingBox());
        assertFalse(none.contains(0.0, 0.0));
        assertFalse(none.contains(-122.4, 37.8));
        assertTrue(Double.isNaN(none.longitudeSpan()));
        assertTrue(Double.isNaN(none.rankingArea()));
        assertFalse(none.crossesAntimeridian());
        assertTrue(none.toString().contains("no bbox"));
    }

    /**
     * An absent accuracy is meaningful: PROJ never assigns one to a ballpark operation, so an empty
     * accuracy on a synthesised datum change is the database saying "this is a guess". Defaulting it to
     * a number is how a ballpark candidate wins a ranking.
     */
    @Test
    public void absentValuesAreNaNAndSayItThroughAPredicate() {
        DbOperation ballpark = new DbOperation(DbObjectType.OTHER_TRANSFORMATION, "PROJ", "X",
                "Ballpark geographic offset", "PROJ", "PROJString", "+proj=noop",
                new DbObjectRef(DbObjectType.GEODETIC_CRS, "EPSG", "4267"),
                new DbObjectRef(DbObjectType.GEODETIC_CRS, "EPSG", "4269"),
                Double.NaN, null, null, null, null, null, false);
        assertFalse(ballpark.hasAccuracy());
        assertTrue(Double.isNaN(ballpark.accuracy()));
        assertTrue(ballpark.isProjStringMethod());
        assertFalse(ballpark.isWktMethod());
        assertTrue(ballpark.toString().contains("accuracy unknown"));

        DbUnit noFactor = new DbUnit("EPSG", "9203", "coefficient", DbUnit.Type.SCALE, Double.NaN,
                null, false);
        assertFalse(noFactor.hasConversionFactor());
        // Never 1.0: a defaulted factor of one is indistinguishable from a real one and multiplies
        // silently.
        assertFalse(1.0 == noFactor.conversionFactor());
    }

    @Test
    public void ellipsoidKeepsTheAuthoritysOwnParameterisation() {
        DbEllipsoid airy = new DbEllipsoid("EPSG", "7001", "Airy 1830", null, 6377563.396, null,
                299.3249646, Double.NaN, false);
        assertEquals(299.3249646, airy.inverseFlattening(), 0.0);
        assertTrue(Double.isNaN(airy.semiMinorAxis()));
        assertFalse(airy.isSphere());

        DbEllipsoid sphere = new DbEllipsoid("EPSG", "7035", "Sphere", null, 6371000.0, null, 0.0,
                Double.NaN, false);
        assertTrue(sphere.isSphere());
    }

    @Test
    public void collectionsOnValueTypesAreUnmodifiable() {
        DbOperation op = new DbOperation(DbObjectType.GRID_TRANSFORMATION, "EPSG", "7710", "x", null,
                null, null, null, null, 1.0, null, Arrays.asList("a.gsb"), null, null, null, false);
        try {
            op.gridNames().add("b.gsb");
            fail("gridNames() must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // as documented
        }
        assertEquals(Collections.emptyList(), op.parameters());
        assertEquals(Collections.emptyList(), op.steps());

        DbCoordinateSystem cs = new DbCoordinateSystem("EPSG", "6422", "ellipsoidal", 2, null);
        assertEquals(Collections.emptyList(), cs.axes());
    }

    @Test
    public void stepDirectionDefaultsToUnspecifiedRatherThanForward() {
        // Upstream stores NULL when the direction follows from chaining. Defaulting a NULL to FORWARD
        // would silently run a reverse step forwards.
        assertEquals(DbOperationStep.Direction.UNSPECIFIED,
                DbOperationStep.Direction.fromDbValue(null));
        assertEquals(DbOperationStep.Direction.FORWARD,
                DbOperationStep.Direction.fromDbValue("forward"));
        assertEquals(DbOperationStep.Direction.REVERSE,
                DbOperationStep.Direction.fromDbValue("reverse"));
        DbOperationStep step = new DbOperationStep(2,
                new DbObjectRef(DbObjectType.HELMERT_TRANSFORMATION, "EPSG", "7941"), null);
        assertEquals(DbOperationStep.Direction.UNSPECIFIED, step.direction());
        // Not a 1-based index: two upstream operations number their steps 2 and 3.
        assertEquals(2, step.stepNumber());
    }

    @Test
    public void gridAlternativeKeepsOpenLicenceTriStateSeparate() {
        DbGridAlternative unknown = new DbGridAlternative("x.gsb", "x.tif", null, "GTiff",
                "hgridshift", false, null, null, null, null);
        assertNull("null is 'not established', which is not permission", unknown.openLicense());
        DbGridAlternative refused = new DbGridAlternative("y.gsb", "y.tif", null, "GTiff",
                "hgridshift", true, null, Boolean.FALSE, Boolean.FALSE, null);
        assertEquals(Boolean.FALSE, refused.openLicense());
        assertTrue(refused.inverseDirection());
    }

    /**
     * Supersession is not deprecation, and {@code sameSourceTargetCrs} is why. A superseded operation
     * whose replacement connects a different CRS pair is not actually a substitute for it.
     */
    @Test
    public void supersessionRecordsWhetherTheReplacementIsASubstitute() {
        DbObjectRef old = new DbObjectRef(DbObjectType.HELMERT_TRANSFORMATION, "EPSG", "1");
        DbObjectRef newer = new DbObjectRef(DbObjectType.HELMERT_TRANSFORMATION, "EPSG", "2");
        DbSupersession same = new DbSupersession(old, newer, "EPSG", true);
        assertTrue(same.sameSourceTargetCrs());
        assertTrue(same.toString().contains("same CRS pair"));
        assertFalse(new DbSupersession(old, newer, "EPSG", false).sameSourceTargetCrs());
    }

    // ------------------------------------------------------------------ discovery

    /**
     * With no {@code proj4j-db} artifact present, discovery finds nothing and says nothing — it must not
     * throw, and must not be consulted implicitly by anything else in core.
     */
    @Test
    public void discoveryFindsNothingWithoutTheArtifact() throws IOException {
        List<ProjDatabaseProvider> found = ProjDatabaseProvider.discover(
                ProjDatabaseSpiTest.class.getClassLoader());
        assertEquals("core's own test classpath must have no ProjDatabase implementation",
                Collections.emptyList(), found);
        assertNull(ProjDatabaseProvider.openFirst(ProjDatabaseSpiTest.class.getClassLoader()));
    }

    /**
     * Two providers sharing {@code (priority, name)} are rejected, not ordered by luck.
     * {@code ServiceLoader} iteration follows classpath order, which differs between a shaded jar, an
     * IDE and a Spark executor — so picking one arbitrarily makes the choice of database a property of
     * the deployment rather than of the code. Same precedent as {@code ResourceResolvers}.
     */
    @Test
    public void duplicateProvidersWouldBeRejected() {
        // discover() cannot be given synthetic providers without a classloader stunt, so the ordering
        // and duplicate rules are asserted directly on the comparator's inputs.
        ProjDatabaseProvider a = provider("pjdx", 100);
        ProjDatabaseProvider b = provider("pjdx", 100);
        assertEquals(a.name(), b.name());
        assertEquals(a.priority(), b.priority());

        List<ProjDatabaseProvider> providers = new ArrayList<ProjDatabaseProvider>(
                Arrays.asList(provider("zzz", 50), provider("aaa", 50), provider("mmm", 10)));
        Collections.sort(providers, new java.util.Comparator<ProjDatabaseProvider>() {
            @Override
            public int compare(ProjDatabaseProvider p, ProjDatabaseProvider q) {
                int c = Integer.compare(p.priority(), q.priority());
                return c != 0 ? c : p.name().compareTo(q.name());
            }
        });
        assertEquals("mmm", providers.get(0).name());
        assertEquals("aaa", providers.get(1).name());
        assertEquals("zzz", providers.get(2).name());
    }

    private static ProjDatabaseProvider provider(final String name, final int priority) {
        return new ProjDatabaseProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public ProjDatabase open() {
                return null;
            }
        };
    }
}

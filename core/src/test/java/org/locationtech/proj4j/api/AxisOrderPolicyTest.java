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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.io.wkt.AxisOrderPolicy;

/**
 * The contract of {@link AxisOrderPolicy} through the facade: {@link AxisOrderPolicy#LEGACY} is the
 * default and is longitude-first, and {@link AxisOrderPolicy#AUTHORITY} flips a geographic EPSG CRS
 * to latitude-first.
 *
 * <h2>Every coordinate here is deliberately far from (0, 0)</h2>
 *
 * <p>(-122.4, 37.8) -- San Francisco, from the consumer's own reproduction script. Swapping the two
 * ordinates of a point near the equator and the prime meridian changes the answer by almost nothing,
 * which is exactly why an axis-order regression can pass a test suite whose fixtures live near the
 * origin and then transpose every coordinate in production. A test for this property that uses
 * (0, 0), or (1, 1), or any point where |lat| is close to |lon|, is not a test.
 */
public class AxisOrderPolicyTest {

    private static final double LON = -122.4;
    private static final double LAT = 37.8;

    private static final ProjContext AUTHORITY = ProjContext.builder()
            .axisOrderPolicy(AxisOrderPolicy.AUTHORITY).build();

    // -------------------------------------------------------------------------------- the default

    @Test
    public void legacyIsTheDefault() {
        assertEquals(AxisOrderPolicy.LEGACY, ProjContext.DEFAULT.axisOrderPolicy());
        assertEquals(AxisOrderPolicy.LEGACY, Proj.defaultContext().axisOrderPolicy());
    }

    @Test
    public void byDefaultEpsg4326IsLongitudeFirst() {
        Crs crs = Proj.createCrs("EPSG:4326");
        assertEquals("enu", crs.axisOrder());
        assertFalse(crs.isLatitudeFirst());
    }

    /**
     * The 1.4.3-compatibility assertion, with the literal coordinate from the consumer's
     * reproduction script: the default operation consumes {@code (longitude, latitude)}.
     *
     * <p>Verified by the shape of the answer rather than a pinned constant: Web Mercator easting at
     * 122.4&deg;W is a large negative number and northing at 37.8&deg;N is a smaller positive one.
     * Feeding the ordinates in the wrong order would put the point at easting +4.2e6, northing
     * -1.1e7 -- both signs and both magnitudes wrong, which is the point.
     */
    @Test
    public void defaultOperationConsumesLongitudeThenLatitude() {
        CrsOperation op = Proj.createCrsToCrs("EPSG:4326", "EPSG:3857");
        ProjCoordinate out = op.transform(new ProjCoordinate(LON, LAT));

        assertTrue("easting at 122.4W must be negative, was " + out.x, out.x < -1.3e7);
        assertTrue("easting at 122.4W must be about -1.36e7, was " + out.x, out.x > -1.4e7);
        assertTrue("northing at 37.8N must be about 4.55e6, was " + out.y,
                out.y > 4.5e6 && out.y < 4.6e6);
    }

    // ------------------------------------------------------------------------------- AUTHORITY

    @Test
    public void authorityFlipsEpsg4326ToLatitudeFirst() {
        Crs crs = Proj.createCrs("EPSG:4326", AUTHORITY);
        assertEquals("neu", crs.axisOrder());
        assertTrue(crs.isLatitudeFirst());
        assertTrue("the flip must round-trip through the PROJ string",
                crs.toProjString().contains("+axis=neu"));
    }

    /**
     * The whole reason the flip matters: under {@code AUTHORITY} the <em>same</em> physical point
     * must be supplied with its ordinates swapped, and must produce the <b>bit-identical</b> result.
     *
     * <p>Bit-identical, not approximately equal. If the flip were implemented by re-projecting or by
     * a coordinate-space transformation rather than by a pure permutation of ordinates, this would
     * drift in the last few digits, and that drift is how a "harmless" axis-order refactor
     * introduces a numerical regression.
     */
    @Test
    public void authorityConsumesLatitudeThenLongitudeAndAgreesBitwise() {
        ProjCoordinate legacyOut = Proj.createCrsToCrs("EPSG:4326", "EPSG:3857")
                .transform(new ProjCoordinate(LON, LAT));
        ProjCoordinate authorityOut = Proj.createCrsToCrs("EPSG:4326", "EPSG:3857", AUTHORITY)
                .transform(new ProjCoordinate(LAT, LON));

        assertEquals("easting must be bit-identical",
                Double.doubleToLongBits(legacyOut.x), Double.doubleToLongBits(authorityOut.x));
        assertEquals("northing must be bit-identical",
                Double.doubleToLongBits(legacyOut.y), Double.doubleToLongBits(authorityOut.y));
    }

    /**
     * The policy must demonstrably change the answer. Without this, the bit-identical test above
     * could pass even if the policy did nothing at all -- because at a point near (0, 0) it would.
     *
     * <p>(10, 20) and (20, 10) are both valid geographic coordinates either way round, so no
     * validation can rescue a caller who gets the order wrong; only the number differs, by
     * megametres.
     */
    @Test
    public void thePolicyChangesTheAnswerForAnAmbiguousCoordinate() {
        ProjCoordinate legacyOut = Proj.createCrsToCrs("EPSG:4326", "EPSG:3857")
                .transform(new ProjCoordinate(10.0, 20.0));
        ProjCoordinate authorityOut = Proj.createCrsToCrs("EPSG:4326", "EPSG:3857", AUTHORITY)
                .transform(new ProjCoordinate(10.0, 20.0));

        assertTrue("the same input under the two policies must differ by megametres, not "
                        + "micrometres: " + legacyOut.x + " vs " + authorityOut.x,
                Math.abs(legacyOut.x - authorityOut.x) > 1.0e6);
        // And each is the other's transpose, which is the whole content of the policy.
        assertEquals(Double.doubleToLongBits(legacyOut.x),
                Double.doubleToLongBits(Proj.createCrsToCrs("EPSG:4326", "EPSG:3857", AUTHORITY)
                        .transform(new ProjCoordinate(20.0, 10.0)).x));
    }

    /**
     * Getting the order wrong at San Francisco does not produce a wrong coordinate: it produces an
     * exception, because -122.4 is not a latitude. That is the fail-closed guarantee doing exactly
     * what it is for, and it is worth pinning -- it is the one case where an axis-order mistake is
     * self-detecting, and it must stay that way rather than being clamped to a pole.
     */
    @Test
    public void feedingAuthorityTheLegacyOrderThrowsRatherThanGuessing() {
        CrsOperation op = Proj.createCrsToCrs("EPSG:4326", "EPSG:3857", AUTHORITY);
        try {
            ProjCoordinate out = op.transform(new ProjCoordinate(LON, LAT));
            fail("expected a refusal: under AUTHORITY the first ordinate is a latitude and "
                    + LON + " is not one. Got " + out);
        } catch (CrsTransformException expected) {
            assertTrue("must be attributed to the coordinate, not to the CRS: " + expected.cause(),
                    expected.cause().isCoordinateError());
            assertTrue(expected.getMessage(), expected.getMessage().contains("latitude"));
        }
    }

    // ------------------------------------------------------------------- honesty about inference

    /**
     * The flip is a <em>rule applied</em>, not a value read from an authority, because there is no
     * CRS database to read it from. The API says so rather than implying otherwise.
     */
    @Test
    public void authorityOrderFromABareCodeIsReportedAsInferred() {
        Crs crs = Proj.createCrs("EPSG:4326", AUTHORITY);
        assertFalse("no proj.db means authority axis order cannot be read, only inferred",
                crs.isAxisOrderAuthoritative());
        assertTrue(crs.axisOrderNote(), crs.axisOrderNote().contains("INFERRED"));
        assertTrue(crs.axisOrderNote(), crs.axisOrderNote().contains("6422"));
    }

    /** A projected CRS from a bare code is left east-north-up, and that too is disclosed. */
    @Test
    public void authorityLeavesAProjectedCodeEastNorthAndSaysSo() {
        Crs crs = Proj.createCrs("EPSG:32633", AUTHORITY);
        assertEquals("enu", crs.axisOrder());
        assertFalse(crs.isAxisOrderAuthoritative());
        assertTrue(crs.axisOrderNote(), crs.axisOrderNote().contains("ASSUMED"));
    }

    /**
     * An operation built under {@code AUTHORITY} on inferred axis order carries a warning, so a
     * caller logging {@code warnings()} learns of it without having to ask each CRS.
     */
    @Test
    public void inferredAxisOrderIsWarnedAboutOnTheOperation() {
        CrsOperation op = Proj.createCrsToCrs("EPSG:4326", "EPSG:3857", AUTHORITY);
        boolean mentioned = false;
        for (String w : op.warnings()) {
            if (w.contains("axis order")) {
                mentioned = true;
            }
        }
        assertTrue("warnings() must mention that axis order was inferred: " + op.warnings(),
                mentioned);
    }

    // ------------------------------------------------------------------------- explicit and WKT

    /** An explicit {@code +axis=} is honoured under every policy and is authoritative. */
    @Test
    public void anExplicitAxisParameterIsHonouredAndIsAuthoritative() {
        Crs crs = Proj.createCrs("+proj=longlat +datum=WGS84 +axis=neu");
        assertEquals("neu", crs.axisOrder());
        assertTrue(crs.isLatitudeFirst());
        assertTrue(crs.isAxisOrderAuthoritative());
    }

    /**
     * {@link AxisOrderPolicy#VISUALISATION} is unconditional: it overrides an explicit
     * {@code +axis=}, which is what {@code proj_normalize_for_visualization} does.
     */
    @Test
    public void visualisationOverridesAnExplicitAxisParameter() {
        ProjContext vis = ProjContext.builder()
                .axisOrderPolicy(AxisOrderPolicy.VISUALISATION).build();
        Crs crs = Proj.createCrs("+proj=longlat +datum=WGS84 +axis=neu", vis);
        assertEquals("enu", crs.axisOrder());
        assertFalse(crs.isLatitudeFirst());
        assertFalse(crs.toProjString().contains("+axis="));
    }

    /**
     * WKT axes really are declared, so honouring them is not an inference. A latitude-first
     * {@code GEOGCS} under {@code AUTHORITY} is reported as authoritative -- which is the one case
     * where this library can be sure.
     */
    @Test
    public void wktDeclaredAxesAreAuthoritative() {
        String wkt = "GEOGCS[\"WGS 84\",DATUM[\"WGS_1984\",SPHEROID[\"WGS 84\",6378137,"
                + "298.257223563]],PRIMEM[\"Greenwich\",0],UNIT[\"degree\",0.0174532925199433],"
                + "AXIS[\"Latitude\",NORTH],AXIS[\"Longitude\",EAST],AUTHORITY[\"EPSG\",\"4326\"]]";
        Crs crs = Proj.createCrs(wkt, AUTHORITY);
        assertTrue("AXIS[] clauses were declared, so this is not an inference: "
                + crs.axisOrderNote(), crs.isAxisOrderAuthoritative());
        assertEquals("neu", crs.axisOrder());
        assertTrue(crs.isLatitudeFirst());
    }

    /** The same document under the default policy keeps 1.4.3's longitude-first behaviour. */
    @Test
    public void wktDeclaredAxesAreRetainedButNotAppliedUnderLegacy() {
        String wkt = "GEOGCS[\"WGS 84\",DATUM[\"WGS_1984\",SPHEROID[\"WGS 84\",6378137,"
                + "298.257223563]],PRIMEM[\"Greenwich\",0],UNIT[\"degree\",0.0174532925199433],"
                + "AXIS[\"Latitude\",NORTH],AXIS[\"Longitude\",EAST],AUTHORITY[\"EPSG\",\"4326\"]]";
        Crs crs = Proj.createCrs(wkt);
        assertEquals("enu", crs.axisOrder());
        assertFalse(crs.isLatitudeFirst());
        assertTrue(crs.axisOrderNote(), crs.axisOrderNote().contains("not applied"));
    }

    /** {@link Crs#withAxisOrderPolicy} derives a new CRS and leaves the original alone. */
    @Test
    public void withAxisOrderPolicyDoesNotMutateTheOriginal() {
        Crs legacy = Proj.createCrs("EPSG:4326");
        Crs flipped = legacy.withAxisOrderPolicy(AxisOrderPolicy.AUTHORITY);
        assertEquals("neu", flipped.axisOrder());
        assertEquals("the original must be untouched", "enu", legacy.axisOrder());
        assertEquals(AxisOrderPolicy.LEGACY, legacy.context().axisOrderPolicy());
    }
}

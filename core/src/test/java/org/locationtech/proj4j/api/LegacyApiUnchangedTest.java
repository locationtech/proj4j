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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.BasicCoordinateTransform;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.CrsCreationException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.io.wkt.AxisOrderPolicy;

/**
 * The frozen-API promise, as a test rather than a comment.
 *
 * <p>The new facade makes a choice the legacy API must not inherit: it refuses {@code EPSG:4267} to
 * {@code EPSG:4269} because the datum shift cannot be performed. Imposing that on
 * {@link CoordinateTransformFactory} would make GeoTools, GeoServer and geomesa start <em>throwing</em>
 * on code that has worked for fifteen years, in a library most of them reach transitively and did not
 * choose.
 *
 * <p>So every assertion here is of the form "the 1.x path still does exactly what it did". The whole
 * file exists to fail loudly if someone ever decides the strict default is too good not to share.
 */
public class LegacyApiUnchangedTest {

    private static final double LON = -122.4;
    private static final double LAT = 37.8;

    /**
     * The single most important assertion in this package: the pair the new API refuses is still
     * transformed, without complaint, by the old one.
     */
    @Test
    public void theLegacyFactoryStillTransformsThePairTheFacadeRefuses() {
        CRSFactory f = new CRSFactory();
        CoordinateReferenceSystem nad27 = f.createFromName("EPSG:4267");
        CoordinateReferenceSystem nad83 = f.createFromName("EPSG:4269");

        // The facade refuses.
        try {
            Proj.createCrsToCrs("EPSG:4267", "EPSG:4269");
            fail("the facade is supposed to refuse this pair");
        } catch (CrsCreationException expected) {
            assertEquals(ErrorCause.BALLPARK_REJECTED, expected.cause());
        }

        // The legacy API does not, and must not.
        CoordinateTransform t = new CoordinateTransformFactory().createTransform(nad27, nad83);
        ProjCoordinate out = t.transform(new ProjCoordinate(LON, LAT), new ProjCoordinate());
        assertTrue("the legacy path must still return a coordinate, unchanged from 1.4.3",
                out.hasValidXandYOrdinates());
    }

    /**
     * Not merely "does not throw": the legacy factory returns the <b>same object type built the same
     * way</b>, so its numbers are bit-identical to a directly constructed
     * {@link BasicCoordinateTransform}. Any re-routing at all would break this.
     */
    @Test
    public void theLegacyFactoryIsNotReRoutedThroughTheFacade() {
        CRSFactory f = new CRSFactory();
        CoordinateReferenceSystem src = f.createFromName("EPSG:4267");
        CoordinateReferenceSystem tgt = f.createFromName("EPSG:4269");

        CoordinateTransform viaFactory = new CoordinateTransformFactory().createTransform(src, tgt);
        assertTrue("createTransform must still return a BasicCoordinateTransform",
                viaFactory instanceof BasicCoordinateTransform);

        ProjCoordinate a = viaFactory.transform(new ProjCoordinate(LON, LAT), new ProjCoordinate());
        ProjCoordinate b = new BasicCoordinateTransform(src, tgt)
                .transform(new ProjCoordinate(LON, LAT), new ProjCoordinate());

        assertEquals(Double.doubleToLongBits(b.x), Double.doubleToLongBits(a.x));
        assertEquals(Double.doubleToLongBits(b.y), Double.doubleToLongBits(a.y));
        assertEquals(Double.doubleToLongBits(b.z), Double.doubleToLongBits(a.z));
    }

    /**
     * A missing {@code @}-optional grid is still skipped silently on the legacy path. This is the
     * behaviour the new API considers a defect, and it is preserved here on purpose: changing it
     * would change the answer for every existing NAD27 caller.
     */
    @Test
    public void theLegacyPathStillSkipsAMissingOptionalGridSilently() {
        CRSFactory f = new CRSFactory();
        CoordinateReferenceSystem src =
                f.createFromParameters("src", "+proj=longlat +ellps=clrk66 "
                        + "+nadgrids=@nosuchgrid.gsb");
        CoordinateReferenceSystem tgt = f.createFromName("EPSG:4326");
        CoordinateTransform t = new CoordinateTransformFactory().createTransform(src, tgt);
        ProjCoordinate out = t.transform(new ProjCoordinate(LON, LAT), new ProjCoordinate());
        assertTrue("1.4.3 returned a coordinate here and so must this", out.hasValidXandYOrdinates());
    }

    /**
     * {@link CRSFactory} is untouched by anything the facade does, including by the facade having
     * already built CRSs under {@link AxisOrderPolicy#AUTHORITY}. A policy that leaked into the
     * legacy factory would transpose coordinates for callers who never opted in -- the exact failure
     * this design is built to prevent.
     */
    @Test
    public void theLegacyFactoryIsUnaffectedByFacadeUsage() {
        // Do the most policy-laden thing the facade offers, first.
        ProjContext authority = ProjContext.builder()
                .axisOrderPolicy(AxisOrderPolicy.AUTHORITY)
                .ballparkPolicy(BallparkPolicy.ALLOW)
                .gridPolicy(GridPolicy.PROJ4_COMPAT)
                .build();
        Crs flipped = Proj.createCrs("EPSG:4326", authority);
        assertEquals("neu", flipped.axisOrder());

        // Now the legacy path, which must be entirely unaware of it.
        CRSFactory f = new CRSFactory();
        CoordinateReferenceSystem legacy4326 = f.createFromName("EPSG:4326");
        assertArrayEquals(new String[]{"+proj=longlat", "+datum=WGS84", "+no_defs"},
                legacy4326.getParameters());

        CoordinateTransform t = new CoordinateTransformFactory()
                .createTransform(legacy4326, f.createFromName("EPSG:3857"));
        ProjCoordinate out = t.transform(new ProjCoordinate(LON, LAT), new ProjCoordinate());
        assertTrue("the legacy path must still consume (lon, lat): easting was " + out.x,
                out.x < -1.3e7);
    }

    /** The default {@link CoordinateTransformFactory} constructor's behaviour is unchanged. */
    @Test
    public void theLegacyFactoryDefaultsAreUnchanged() {
        CoordinateTransformFactory f = new CoordinateTransformFactory();
        assertEquals(org.locationtech.proj4j.DomainErrorPolicy.THROW, f.getDomainErrorPolicy());
        assertFalse("CoordinateTransformFactory must not be deprecated in 1.5.0: a deprecation "
                        + "warning on a class nobody needs to migrate away from is noise",
                CoordinateTransformFactory.class.isAnnotationPresent(Deprecated.class));
        assertFalse(CRSFactory.class.isAnnotationPresent(Deprecated.class));
        assertFalse(CoordinateTransform.class.isAnnotationPresent(Deprecated.class));
        assertFalse(CoordinateReferenceSystem.class.isAnnotationPresent(Deprecated.class));
        assertFalse(ProjCoordinate.class.isAnnotationPresent(Deprecated.class));
    }

    // ------------------------------------------------------------------------- the opt-in bridge

    /**
     * The bridge is the whole point: the same interface, the strict behaviour, and it has to be
     * asked for by name.
     */
    @Test
    public void theBridgeIsStrictAndIsOptIn() {
        CRSFactory f = new CRSFactory();
        CoordinateReferenceSystem src = f.createFromName("EPSG:4267");
        CoordinateReferenceSystem tgt = f.createFromName("EPSG:4269");

        CoordinateTransformFactory bridge = LegacyAdapters.transformFactory(ProjContext.DEFAULT);
        try {
            bridge.createTransform(src, tgt);
            fail("LegacyAdapters.transformFactory must apply BallparkPolicy");
        } catch (CrsCreationException expected) {
            assertEquals(ErrorCause.BALLPARK_REJECTED, expected.cause());
        }

        // ...and the plain factory, constructed on the very next line, still does not.
        assertTrue(new CoordinateTransformFactory().createTransform(src, tgt)
                .transform(new ProjCoordinate(LON, LAT), new ProjCoordinate())
                .hasValidXandYOrdinates());
    }

    /** The bridge is assignable to the legacy type, which is what makes it a one-line change. */
    @Test
    public void theBridgeIsACoordinateTransformFactory() {
        CoordinateTransformFactory factory = LegacyAdapters.transformFactory(null);
        assertTrue(factory instanceof CoordinateTransformFactory);
        CoordinateTransform t = factory.createTransform(
                new CRSFactory().createFromName("EPSG:4326"),
                new CRSFactory().createFromName("EPSG:32633"));
        assertTrue(t.transform(new ProjCoordinate(15.0, 50.0), new ProjCoordinate())
                .hasValidXandYOrdinates());
    }

    /**
     * {@link Crs#asLegacy()} hands out a fresh object every time, because
     * {@code proj4j-geoapi}'s parameter wrappers write back into a live {@code Projection} and a
     * shared one would be mutable through them.
     */
    @Test
    public void asLegacyReturnsAFreshInstanceEachCall() {
        Crs crs = Proj.createCrs("EPSG:4326");
        CoordinateReferenceSystem a = crs.asLegacy();
        CoordinateReferenceSystem b = crs.asLegacy();
        assertNotSame(a, b);
        assertEquals(a, b);
        assertArrayEquals(a.getParameters(), b.getParameters());
    }

    /** Adapting a legacy CRS in must not re-derive its axis order behind its owner's back. */
    @Test
    public void fromLegacyDoesNotTransposeSomebodyElsesCrs() {
        CoordinateReferenceSystem legacy = new CRSFactory().createFromName("EPSG:4326");
        ProjContext authority = ProjContext.builder()
                .axisOrderPolicy(AxisOrderPolicy.AUTHORITY).build();
        Crs wrapped = LegacyAdapters.fromLegacy(legacy, authority);
        assertEquals("enu", wrapped.axisOrder());
        assertFalse(wrapped.isLatitudeFirst());
        assertTrue(wrapped.axisOrderNote(),
                wrapped.axisOrderNote().contains("deliberately not re-derived"));
        assertEquals(Crs.Source.LEGACY_OBJECT, wrapped.source());
    }
}

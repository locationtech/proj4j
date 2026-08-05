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
package org.locationtech.proj4j.domain;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The second entry point for the angular input contract, and in practice the one that matters:
 * {@code Projection.inverseProjectRadians}' {@code unit == Units.DEGREES} branch.
 *
 * <h2>Why guarding the forward funnel alone would have missed EPSG:4326</h2>
 *
 * <p>{@code BasicCoordinateTransform.transform} calls
 * {@code srcCRS.getProjection().inverseProjectRadians(tgt, tgt)} whenever the source CRS is not
 * {@code CS_GEO} — <b>including when the source is geographic</b>. For a geographic source that
 * "inverse projection" is {@code LongLatProjection} doing nothing but a multiply by
 * {@code DTR}. So a caller transforming <em>from</em> EPSG:4326 never touches
 * {@code projectRadians} on the way in, and an out-of-domain latitude supplied in degrees would
 * have sailed straight past a guard installed only on the forward funnel.
 *
 * <p>That is the commonest source CRS in the world, so this is the entry point most real
 * out-of-domain input actually uses.
 */
public class GeographicSourceEntryPointTest {

    private final CRSFactory csFactory = new CRSFactory();
    private final CoordinateTransformFactory ctFactory = new CoordinateTransformFactory();

    private CoordinateTransform wgs84To(String target) {
        CoordinateReferenceSystem src = csFactory.createFromName("EPSG:4326");
        CoordinateReferenceSystem tgt = csFactory.createFromParameters("target", target);
        return ctFactory.createTransform(src, tgt);
    }

    private static ProjCoordinate transform(CoordinateTransform t, double lon, double lat) {
        return t.transform(new ProjCoordinate(lon, lat), new ProjCoordinate(1e300, 1e300));
    }

    /** Ordinary in-domain input still works, so the guard is not simply rejecting everything. */
    @Test
    public void inDomainGeographicInputStillTransforms() {
        CoordinateTransform t = wgs84To("+proj=merc +ellps=WGS84 +units=m +no_defs");
        ProjCoordinate out = transform(t, 10.0, 45.0);
        assertTrue(out.toString(), out.hasValidXandYOrdinates());
        assertEquals(1113194.9, out.x, 1.0);
    }

    /**
     * The headline case. Latitude {@code 90.000001} degrees overshoots {@code pi/2} by
     * {@code 1.745e-8} rad, which is {@code 17453} times {@code PJ_EPS_LAT}. PROJ answers
     * {@code PROJ_ERR_COORD_TRANSFM_INVALID_COORD}; Proj4J 1.4.3 answered with a coordinate.
     */
    @Test
    public void epsg4326SourceAtLatitude90Point000001IsRejected() {
        CoordinateTransform t = wgs84To("+proj=merc +ellps=WGS84 +units=m +no_defs");
        try {
            fail("must reject, got " + transform(t, 10.0, 90.000001));
        } catch (CrsTransformException e) {
            assertEquals(ErrorCause.INVALID_COORDINATE, e.cause());
            assertTrue(e.getMessage(), e.getMessage().contains("invalid latitude"));
        }
    }

    /** Both signs, and a frankly wrong latitude too. */
    @Test
    public void epsg4326SourceRejectsEveryOutOfDomainLatitude() {
        CoordinateTransform t = wgs84To("+proj=merc +ellps=WGS84 +units=m +no_defs");
        for (double lat : new double[] {90.000001, -90.000001, 91.0, -95.0, 100.0, 1000.0}) {
            try {
                fail("latitude " + lat + " must be rejected, got " + transform(t, 10.0, lat));
            } catch (CrsTransformException e) {
                assertEquals("latitude " + lat, ErrorCause.INVALID_COORDINATE, e.cause());
            }
        }
    }

    /**
     * &plusmn;90 exactly remains valid input through the geographic entry point.
     * <p>
     * Probed with {@code +proj=eqc}, whose forward is defined and finite at the pole. Several
     * projections raise there for reasons of their own — Mercator's northing diverges, and
     * {@code laea}'s ellipsoidal branch has its own guard — and a test of <em>this</em> guard must
     * not be able to fail because of one of those.
     */
    @Test
    public void epsg4326SourceAcceptsTheExactPole() {
        CoordinateTransform t = wgs84To("+proj=eqc +ellps=WGS84 +units=m +no_defs");
        assertTrue(transform(t, 0.0, 90.0).hasValidXandYOrdinates());
        assertTrue(transform(t, 0.0, -90.0).hasValidXandYOrdinates());
    }

    /**
     * <b>200 degrees of longitude is valid input</b>, here as everywhere: the only longitude
     * bound is {@code |lambda| > 10} radians. This test is the constraint's witness at the
     * transform level, where a downstream ask for {@code [-180, 180]} rejection would land.
     */
    @Test
    public void epsg4326SourceAcceptsLongitude200() {
        CoordinateTransform t = wgs84To("+proj=merc +ellps=WGS84 +units=m +no_defs");
        assertTrue(transform(t, 200.0, 10.0).hasValidXandYOrdinates());
        assertTrue(transform(t, -190.0, 10.0).hasValidXandYOrdinates());
        assertTrue(transform(t, 400.0, 10.0).hasValidXandYOrdinates());
    }

    /**
     * NaN in, NaN out, through a whole transform — the same contract the projection funnel keeps,
     * asserted end to end because that is the level the {@code gie} corpus asserts it at.
     */
    @Test
    public void nanInNanOutThroughAWholeTransform() {
        CoordinateTransform t = wgs84To("+proj=merc +ellps=WGS84 +units=m +no_defs");
        ProjCoordinate out = transform(t, Double.NaN, Double.NaN);
        assertTrue("NaN in must be NaN out, not the poisoned dst: " + out,
                Double.isNaN(out.x) && Double.isNaN(out.y));
    }

    /** An infinity, by contrast, is rejected — PROJ rejects it too. */
    @Test
    public void infiniteInputIsRejectedThroughAWholeTransform() {
        CoordinateTransform t = wgs84To("+proj=merc +ellps=WGS84 +units=m +no_defs");
        try {
            fail("must reject, got " + transform(t, 10.0, Double.POSITIVE_INFINITY));
        } catch (CrsTransformException e) {
            assertEquals(ErrorCause.INVALID_COORDINATE, e.cause());
        }
    }
}

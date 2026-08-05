/*******************************************************************************
 * Copyright 2026 The Proj4J Contributors.
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

package org.locationtech.proj4j.geocent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * {@code +proj=geocent} through {@link CoordinateTransform}, in <b>both</b> directions.
 *
 * <p>The reverse direction is the one that needs a test. {@code GeocentProjection} cannot supply a
 * {@code projectInverse(double, double, ProjCoordinate)} — that signature has no z — so
 * {@code BasicCoordinateTransform.inverseAvailable}, which answers "is there an inverse" by
 * {@code hasInverse() || isGeographic()} and then by scanning the class hierarchy for exactly that
 * method, classified every geocentric CRS as non-invertible until {@code hasInverse()} was
 * declared. The golden-master sweep measured the blast radius: <b>1,058 rows</b> over 330 keys
 * (181 {@code epsg} defs x 5 probes, 148 MetaCRS CSV cases, 1 synthetic) went from a coordinate to
 * {@code CrsTransformException: ... uses projection None, which has no inverse}. All 1,058
 * round-tripped in 1.4.3.
 *
 * <p>Expected values are {@code 9.8.1:src/conversions/cart.cpp}'s {@code cartesian()} evaluated
 * independently of proj4j; see {@link GeocentProjectionTest} for the formula and the constants.
 */
public class GeocentTransformTest {

    private static final double MM = 1.0e-6;

    private final CRSFactory crsFactory = new CRSFactory();
    private final CoordinateTransformFactory ctFactory = new CoordinateTransformFactory();

    private ProjCoordinate transform(String src, String tgt, double x, double y, double z) {
        CoordinateReferenceSystem s = src.startsWith("+")
                ? crsFactory.createFromParameters("src", src) : crsFactory.createFromName(src);
        CoordinateReferenceSystem t = tgt.startsWith("+")
                ? crsFactory.createFromParameters("tgt", tgt) : crsFactory.createFromName(tgt);
        ProjCoordinate out = new ProjCoordinate();
        ctFactory.createTransform(s, t).transform(new ProjCoordinate(x, y, z), out);
        return out;
    }

    /** EPSG:4978 is {@code +proj=geocent +datum=WGS84 +units=m}: the WGS84 geocentric frame. */
    @Test
    public void wgs84GeographicToEpsg4978() {
        ProjCoordinate xyz = transform("EPSG:4326", "EPSG:4978", 9.5, 55.5, 100.0);

        assertEquals(3571255.4410952283, xyz.x, MM);
        assertEquals(597623.2032090913, xyz.y, MM);
        assertEquals(5233194.16771844, xyz.z, MM);
    }

    /**
     * The direction that was refused. Before {@code hasInverse()} was declared this threw
     * {@code CrsTransformException(NO_INVERSE_AVAILABLE)}.
     */
    @Test
    public void epsg4978ToWgs84Geographic() {
        ProjCoordinate lonlat = transform("EPSG:4978", "EPSG:4326",
                3571255.4410952283, 597623.2032090913, 5233194.16771844);

        assertEquals(9.5, lonlat.x, 1.0e-8);
        assertEquals(55.5, lonlat.y, 1.0e-8);
        assertEquals(100.0, lonlat.z, 1.0e-4);
    }

    @Test
    public void epsg4978RoundTripsThroughGeographic() {
        for (double lon = -175.0; lon <= 175.0; lon += 35.0) {
            for (double lat = -85.0; lat <= 85.0; lat += 17.0) {
                ProjCoordinate xyz = transform("EPSG:4326", "EPSG:4978", lon, lat, 250.0);
                ProjCoordinate back = transform("EPSG:4978", "EPSG:4326", xyz.x, xyz.y, xyz.z);
                String at = "(" + lon + ", " + lat + ")";
                assertEquals(at, lon, back.x, 1.0e-8);
                assertEquals(at, lat, back.y, 1.0e-8);
                assertEquals(at, 250.0, back.z, 1.0e-3);
            }
        }
    }

    /**
     * A geocentric CRS as a transformation <em>source</em> feeding a projected target — the shape
     * the 148 MetaCRS CSV cases take. Kept on one datum (both sides are {@code +datum=WGS84}) so
     * that the assertion is about the geocentric inverse and not about the datum stage, which
     * three other streams are changing.
     */
    @Test
    public void geocentricSourceToProjectedTarget() {
        ProjCoordinate utm = transform("EPSG:4978", "EPSG:32632",
                3571255.4410952283, 597623.2032090913, 5233194.16771844);

        // The same point via EPSG:4326 -> EPSG:32632, i.e. (9.5, 55.5) in UTM zone 32N.
        ProjCoordinate viaGeographic = transform("EPSG:4326", "EPSG:32632", 9.5, 55.5, 0.0);
        assertEquals(viaGeographic.x, utm.x, 1.0e-6);
        assertEquals(viaGeographic.y, utm.y, 1.0e-6);
        assertTrue("easting " + utm.x, utm.x > 100000.0 && utm.x < 900000.0);
        assertTrue("northing " + utm.y, utm.y > 6100000.0 && utm.y < 6200000.0);
    }

    /**
     * Geocentric to geocentric on the same datum is a plain identity through the two conversions,
     * and it is the transform that would disagree with itself if this class used Bowring's closed
     * form while {@code BasicCoordinateTransform.datumTransform} kept the Toms iteration. It does
     * not: both stages are {@code datum.GeocentricConverter}.
     */
    @Test
    public void geocentricToGeocentricSameDatumIsTheIdentity() {
        ProjCoordinate out = transform("EPSG:4978", "EPSG:4978",
                3571255.4410952283, 597623.2032090913, 5233194.16771844);

        assertEquals(3571255.4410952283, out.x, 1.0e-4);
        assertEquals(597623.2032090913, out.y, 1.0e-4);
        assertEquals(5233194.16771844, out.z, 1.0e-4);
    }

    /**
     * {@code BasicCoordinateTransform} calls {@code projectRadians(tgt, tgt)} and
     * {@code inverseProjectRadians(tgt, tgt)} — the two arguments are the same object. That is why
     * the read-{@code dst} defect was invisible here, and it is the path the whole golden-master
     * table walks, so this asserts it is stable and not merely correct.
     */
    @Test
    public void transformIsStableWhenCallerReusesOneCoordinateObject() {
        CoordinateReferenceSystem geo = crsFactory.createFromName("EPSG:4326");
        CoordinateReferenceSystem geocent = crsFactory.createFromName("EPSG:4978");
        CoordinateTransform fwd = ctFactory.createTransform(geo, geocent);

        ProjCoordinate shared = new ProjCoordinate(9.5, 55.5, 100.0);
        fwd.transform(shared, shared);

        assertEquals(3571255.4410952283, shared.x, MM);
        assertEquals(597623.2032090913, shared.y, MM);
        assertEquals(5233194.16771844, shared.z, MM);
    }

    /**
     * {@code +lon_0} rotates the cartesian frame, because {@code fwd_prepare}'s
     * {@code lam = (lam - from_greenwich) - lam0} ({@code 9.8.1:src/fwd.cpp:105-112}) runs for a
     * cartesian right-hand side too. So longitude 10 under {@code +lon_0=10} puts the point on the
     * X axis with Y exactly zero. This is the one change {@code golden/rules.yaml}'s
     * {@code PROJ-GEOCENT-LON0-APPLIED} declares, and it is 5 golden rows because no registry def
     * carries {@code +lon_0}.
     */
    @Test
    public void lonZeroRotatesTheFrameThroughATransform() {
        ProjCoordinate xyz = transform("EPSG:4326",
                "+proj=geocent +ellps=GRS80 +lon_0=10 +units=m +no_defs", 10.0, 45.0, 0.0);

        assertEquals(4517590.878886053, xyz.x, 1.0e-3);
        assertEquals(0.0, xyz.y, 1.0e-3);
        assertEquals(4487348.4087547995, xyz.z, 1.0e-3);
    }
}

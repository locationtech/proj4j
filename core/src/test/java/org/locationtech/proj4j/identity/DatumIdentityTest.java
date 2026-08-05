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
package org.locationtech.proj4j.identity;

import org.junit.Test;
import org.locationtech.proj4j.datum.Datum;
import org.locationtech.proj4j.datum.Ellipsoid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pins {@link Datum#isEqual(Datum)}, which is the predicate
 * {@code BasicCoordinateTransform.datumTransform} uses to decide whether to skip the datum shift
 * entirely.
 * <p>
 * The right-hand side of the ellipsoid-size check used to name {@code this.ellipsoid} on both
 * sides, so the condition was always false, the eccentricity-tolerance check underneath it was
 * dead, and two datums whose ellipsoids were different sizes could compare equal.
 */
public class DatumIdentityTest {

    /**
     * The direct regression: two 3-parameter datums with identical (zero) transforms but
     * differently sized ellipsoids. Both report {@code TYPE_3PARAM}, both have identical transform
     * arrays, and the only thing telling them apart is the equator radius -- which is exactly what
     * the self-comparison discarded.
     */
    @Test
    public void datumsWithDifferentlySizedEllipsoidsAreNotEqual() {
        // Clarke 1866: a = 6378206.4. GRS80: a = 6378137.0. A 69 m difference in radius.
        Datum clarke = new Datum("d-clarke", 0, 0, 0, Ellipsoid.CLARKE_1866, "Clarke 1866 based");
        Datum international = new Datum("d-intl", 0, 0, 0, Ellipsoid.INTERNATIONAL, "International based");

        assertNotEquals("fixture ellipsoids must differ in size",
                Ellipsoid.CLARKE_1866.getEquatorRadius(), Ellipsoid.INTERNATIONAL.getEquatorRadius(), 0.0);
        assertEquals("fixture must isolate the ellipsoid: same transform type",
                clarke.getTransformType(), international.getTransformType());
        assertEquals(Datum.TYPE_3PARAM, clarke.getTransformType());

        assertFalse("datums with differently sized ellipsoids must not be equal",
                clarke.isEqual(international));
        assertFalse("isEqual must be symmetric", international.isEqual(clarke));
    }

    /**
     * The ellipsoid-shape check as a whole, which the typo made unreachable: two datums whose
     * ellipsoids differ in both equator radius and eccentricity (by far more than
     * {@link Datum#ELLIPSOID_E2_TOLERANCE}) must not be equal. Before the fix this returned true.
     * <p>
     * Deliberately does <em>not</em> pin the "radius differs but eccentricity is within tolerance"
     * case. proj4j nests the eccentricity check inside the differing-radius branch, so that pair
     * still compares equal; upstream's {@code pj_compare_datums} (PROJ 5.2.0
     * {@code src/pj_transform.c}) combines the two with {@code ||} and so calls it unequal. Pinning
     * proj4j's nesting here would block that alignment.
     */
    @Test
    public void ellipsoidShapeCheckIsLive() {
        // Deliberately not 6378137: an ellipsoid of exactly WGS84/GRS80 size and eccentricity
        // reports TYPE_WGS84 rather than TYPE_3PARAM, which would short-circuit isEqual earlier.
        double a = 6377000.0;
        double baseE2 = Ellipsoid.GRS80.getEccentricitySquared();

        Ellipsoid base = new Ellipsoid("base", a, baseE2, "base");
        Ellipsoid biggerFarE2 = new Ellipsoid(
                "outside", a + 1.0, baseE2 + Datum.ELLIPSOID_E2_TOLERANCE * 1000.0, "outside");

        Datum d0 = new Datum("d0", 0, 0, 0, base, "d0");
        Datum dFar = new Datum("dFar", 0, 0, 0, biggerFarE2, "dFar");

        assertEquals(Datum.TYPE_3PARAM, d0.getTransformType());
        assertEquals(Datum.TYPE_3PARAM, dFar.getTransformType());

        assertFalse("differing radius and eccentricity -> not equal", d0.isEqual(dFar));
        assertFalse("isEqual must be symmetric", dFar.isEqual(d0));
        assertTrue("a datum must still equal an identically shaped one", d0.isEqual(
                new Datum("d0-copy", 0, 0, 0, new Ellipsoid("copy", a, baseE2, "copy"), "d0-copy")));
    }

    /** The fix must not stop a datum from equalling itself, or an identical copy of itself. */
    @Test
    public void identicalDatumsRemainEqual() {
        Datum a = new Datum("nad83-copy", 0, 0, 0, Ellipsoid.GRS80, "North_American_Datum_1983");
        Datum b = new Datum("nad83-copy", 0, 0, 0, Ellipsoid.GRS80, "North_American_Datum_1983");

        assertTrue(a.isEqual(a));
        assertTrue(a.isEqual(b));
        assertTrue(b.isEqual(a));

        assertTrue(Datum.WGS84.isEqual(Datum.WGS84));
        assertTrue("WGS84 and NAD83 are both TYPE_WGS84 with GRS80-sized ellipsoids",
                Datum.WGS84.isEqual(Datum.NAD83));

        // Differently sized ellipsoids, differing 7-parameter transforms: unequal either way.
        assertFalse(Datum.POTSDAM.isEqual(Datum.HERMANNSKOGEL));
        assertFalse(Datum.HERMANNSKOGEL.isEqual(Datum.POTSDAM));
    }

    /**
     * A 7-parameter datum whose transform array matches another's but whose ellipsoid is a
     * different size. Under the typo this returned true and the datum shift was skipped outright,
     * so the coordinate came back as an echo of the input.
     */
    @Test
    public void sevenParameterDatumsAreDistinguishedByEllipsoidSize() {
        Datum onBessel = new Datum("seven-bessel", 598.1, 73.7, 418.2, 0.202, 0.045, -2.455, 6.7,
                Ellipsoid.BESSEL, "seven on Bessel");
        Datum onAiry = new Datum("seven-airy", 598.1, 73.7, 418.2, 0.202, 0.045, -2.455, 6.7,
                Ellipsoid.AIRY, "seven on Airy");

        assertEquals(Datum.TYPE_7PARAM, onBessel.getTransformType());
        assertEquals(Datum.TYPE_7PARAM, onAiry.getTransformType());
        assertNotEquals(Ellipsoid.BESSEL.getEquatorRadius(), Ellipsoid.AIRY.getEquatorRadius(), 0.0);

        assertFalse(onBessel.isEqual(onAiry));
        assertFalse(onAiry.isEqual(onBessel));
    }
}

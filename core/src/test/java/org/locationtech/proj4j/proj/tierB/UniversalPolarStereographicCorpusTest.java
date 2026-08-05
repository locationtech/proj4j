/*******************************************************************************
 * Copyright 2026
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

package org.locationtech.proj4j.proj.tierB;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.proj.Projection;
import org.locationtech.proj4j.proj.UniversalPolarStereographicProjection;

/**
 * {@code +proj=ups} against {@code builtins.gie} — 9 assertions, one of them
 * {@code expect failure}.
 *
 * <p>{@code ups} is a preset of polar {@code stere}, so the interesting content is not the
 * arithmetic — {@code StereographicAzimuthalProjection} already had it, and
 * {@code setupUPS(int)} already assigned the five constants — but the two things a preset can get
 * wrong: which of {@code +south}/{@code +lat_0} decides the pole, and that a sphere is refused.
 */
public class UniversalPolarStereographicCorpusTest {

    @Test
    public void upsMatchesCorpus() {
        TierBCorpus.assertAll("builtins.gie", "ups", 9);
    }

    @Test
    public void nameIsRegistered() {
        Projection p = new Registry().getProjection("ups");
        assertNotNull("+proj=ups", p);
        assertTrue(p instanceof UniversalPolarStereographicProjection);
        assertTrue(p.hasInverse());
    }

    /**
     * {@code builtins.gie:7678}: {@code +proj=ups +a=6400000} is
     * {@code expect failure errno invalid_op_illegal_arg_value}.
     *
     * <p>Two facts have to line up for that row to be right. {@code +a=} with no shape parameter
     * declares a <em>sphere</em> ({@code ell_set.cpp}'s "not giving a shape parameter means
     * selecting a sphere"), and {@code PJ_PROJECTION(ups)} refuses {@code es == 0} outright. Get
     * the first wrong — default to GRS80, as Proj4J once did — and this row projects instead of
     * failing.
     */
    @Test
    public void sphereIsRefused() {
        for (String definition : new String[] {"+proj=ups +a=6400000", "+proj=ups +R=6371000"}) {
            try {
                TierBCorpus.build(definition);
                fail(definition + ": ups is not possible on a sphere");
            } catch (InvalidValueException expected) {
                assertTrue(expected.getMessage(),
                        expected.getMessage().contains("only ellipsoidal formulation supported"));
            }
        }
    }

    /** An ellipsoid is accepted, and gives the five UPS constants. */
    @Test
    public void ellipsoidGivesTheUpsPreset() {
        Projection p = TierBCorpus.build("+proj=ups +ellps=GRS80");
        assertEquals("k_0", 0.994, p.getScaleFactor(), 0.0);
        assertEquals("x_0", 2000000.0, p.getFalseEasting(), 0.0);
        assertEquals("y_0", 2000000.0, p.getFalseNorthing(), 0.0);
        assertEquals("lon_0", 0.0, p.getProjectionLongitude(), 0.0);
        assertEquals("lat_0", 90.0, p.getProjectionLatitudeDegrees(), 1e-12);
    }

    /**
     * {@code +south} flips the pole, and it reaches this projection at all — the base
     * {@code Projection.setSouthernHemisphere} refuses, and neither {@code stere} nor
     * {@code AzimuthalProjection} overrides it, so without the override here
     * {@code +proj=ups +south} would raise {@code UnsupportedParameterException}.
     */
    @Test
    public void southFlipsThePole() {
        Projection north = TierBCorpus.build("+proj=ups +ellps=GRS80");
        Projection south = TierBCorpus.build("+proj=ups +ellps=GRS80 +south");
        assertEquals("northern lat_0", 90.0, north.getProjectionLatitudeDegrees(), 1e-12);
        assertEquals("southern lat_0", -90.0, south.getProjectionLatitudeDegrees(), 1e-12);
        assertTrue("+south must be reported back", south.getSouthernHemisphere());

        // And the two are mirror images about y = 2 000 000 for a point on the equator: the north
        // and south polar aspects of stere at the same |lat_0| differ only in the sign of the
        // latitude they measure from.
        ProjCoordinate n = new ProjCoordinate();
        ProjCoordinate s = new ProjCoordinate();
        north.project(new ProjCoordinate(0, -45), n);
        south.project(new ProjCoordinate(0, 45), s);
        assertEquals("x", n.x, s.x, 1e-6);
        assertEquals("y about the false northing", 2000000.0 - n.y, s.y - 2000000.0, 1e-6);
    }

    /**
     * {@code ups} is {@code stere} with the preset applied, so it must agree with the explicit
     * spelling to well inside the corpus's 0.1 mm bar.
     */
    @Test
    public void agreesWithTheExplicitStereSpelling() {
        Projection ups = TierBCorpus.build("+proj=ups +ellps=GRS80");
        Projection stere = TierBCorpus.build("+proj=stere +ellps=GRS80 +lat_0=90 +lat_ts=90 "
                + "+k_0=0.994 +x_0=2000000 +y_0=2000000 +lon_0=0");
        for (double[] pt : new double[][] {{2, 1}, {2, -1}, {-2, 1}, {-2, -1}, {45, 80}}) {
            ProjCoordinate a = new ProjCoordinate();
            ProjCoordinate b = new ProjCoordinate();
            ups.project(new ProjCoordinate(pt[0], pt[1]), a);
            stere.project(new ProjCoordinate(pt[0], pt[1]), b);
            assertEquals("x at " + pt[0] + "," + pt[1], a.x, b.x, 1e-9);
            assertEquals("y at " + pt[0] + "," + pt[1], a.y, b.y, 1e-9);
        }
    }
}

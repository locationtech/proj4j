/*
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
 */
package org.locationtech.proj4j.pipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * The two {@code cs2cs_emulation_setup} helpers added last: the {@code +nadgrids}
 * horizontal grid shift and {@code +geoc}.
 *
 * <p>The Helmert and {@code cart} halves are covered by {@link CartAndHelmertTest} and
 * the whole assembly by {@link PipelineGigsTest}; this class is about the two
 * <em>branch conditions</em> that are easy to read backwards —
 * {@code create.cpp:125}'s {@code P->hgridshift ? nullptr : towgs84} and
 * {@code init.cpp:598}'s {@code es != 0 && geoc}.
 */
public class Cs2csEmulationHelperTest {

    private final PipelineFactory factory = new PipelineFactory();

    private static Cs2csOperator operatorOf(Pipeline p) {
        assertEquals(1, p.steps().size());
        PipelineOperator op = p.steps().get(0).operator();
        assertTrue("expected a Cs2csOperator, got " + op.getClass().getName(),
                op instanceof Cs2csOperator);
        return (Cs2csOperator) op;
    }

    /**
     * {@code create.cpp:125}. The grid suppresses the Helmert, and — the half that is
     * easy to miss, because the {@code do_cart} assignment lives <em>inside</em> the
     * {@code towgs84} loop — it suppresses the cartesian round-trip too, even on a
     * non-WGS84 ellipsoid. A grid shift is geographic-to-geographic and needs no change
     * of ellipsoid.
     */
    @Test
    public void aGridShiftSuppressesBothTheHelmertAndTheCartRoundTrip() {
        Cs2csOperator withGrid = operatorOf(factory.create(
                "+proj=longlat +ellps=bessel +nadgrids=@null +towgs84=598.1,73.7,418.2"));
        assertNotNull(withGrid.hgridshift());
        String s = withGrid.toString();
        assertTrue("no Helmert: " + s, !s.contains("helmert"));
        assertTrue("no cart either: " + s, !s.contains("cart"));

        // The control: the same definition without the grid does build both, so the
        // assertions above are not passing because nothing was ever built.
        Cs2csOperator noGrid = operatorOf(factory.create(
                "+proj=longlat +ellps=bessel +towgs84=598.1,73.7,418.2"));
        assertNull(noGrid.hgridshift());
        assertTrue(noGrid.toString().contains("helmert"));
        assertTrue(noGrid.toString().contains("cart"));
    }

    /**
     * {@code init.cpp:598}: {@code +geoc} on a sphere is read, accepted and has no
     * effect, because the geocentric and geographic latitudes coincide there. This is the
     * shape {@code 4D-API_cs2cs-style.gie:517} uses.
     */
    @Test
    public void geocIsInertOnASphere() {
        Pipeline p = factory.create("+proj=longlat +a=1 +b=1 +geoc");
        assertTrue("no geoc on a sphere: " + p, !operatorOf(p).toString().contains("geoc"));
        double phi = 45.0 * ProjectionMath.DTR;
        assertEquals(phi, p.forward(new double[] {0, phi, 0, 0})[1], 0.0);
    }

    /**
     * On an ellipsoid it is applied, in the direction that reads backwards:
     * {@code fwd_prepare} runs it {@code PJ_INV} because a {@code +geoc} operation's
     * input <em>is</em> geocentric. So the geographic latitude that comes out is the
     * larger of the two, {@code atan(tan(phi) / (1 - es))}.
     */
    @Test
    public void geocOnAnEllipsoidConvertsGeocentricInputToGeographic() {
        Pipeline p = factory.create("+proj=longlat +ellps=GRS80 +geoc");
        assertTrue(operatorOf(p).toString().contains("geoc"));
        double es = org.locationtech.proj4j.datum.Ellipsoid.GRS80.getEccentricitySquared();
        double phi = 45.0 * ProjectionMath.DTR;
        double expected = Math.atan(Math.tan(phi) / (1.0 - es));
        double got = p.forward(new double[] {0, phi, 0, 0})[1];
        assertEquals(expected, got, 1e-15);
        assertTrue("the geographic latitude must be the larger of the two", got > phi);
        // and about 11.5 arcminutes larger at 45 degrees, which is the magnitude an
        // ignored +geoc would have cost.
        assertEquals(0.19243, (got - phi) / ProjectionMath.DTR, 1e-5);
    }

    /** The round trip closes: {@code inv_finalize} runs the conversion {@code PJ_FWD}. */
    @Test
    public void geocRoundTrips() {
        Pipeline p = factory.create("+proj=longlat +ellps=GRS80 +geoc");
        double phi = -32.5 * ProjectionMath.DTR;
        double[] there = p.forward(new double[] {0.1, phi, 0, 0});
        assertEquals(phi, p.inverse(there)[1], 1e-15);
    }

    /**
     * {@code geoc.cpp:56}: within {@code 1e-9} radians of a pole the input is copied
     * rather than converted, because {@code tan} diverges there while the two latitudes
     * converge. Ported verbatim — a "better" implementation would return NaN or a huge
     * value at the pole.
     */
    @Test
    public void geocDeclinesAtThePoles() {
        Pipeline p = factory.create("+proj=longlat +ellps=GRS80 +geoc");
        double pole = Math.PI / 2.0;
        assertEquals(pole, p.forward(new double[] {0, pole, 0, 0})[1], 0.0);
        assertEquals(-pole, p.forward(new double[] {0, -pole, 0, 0})[1], 0.0);
    }

    /** {@code +geoc=F} is PROJ-false and therefore inert. */
    @Test
    public void anExplicitlyFalseGeocIsOff() {
        Pipeline p = factory.create("+proj=longlat +ellps=GRS80 +geoc=F");
        assertTrue(!operatorOf(p).toString().contains("geoc"));
    }
}

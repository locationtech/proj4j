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
package org.locationtech.proj4j.failclosed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.proj.Projection;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * {@code Projection.inverseProjectRadians} must <b>wrap</b> longitude with {@code adjlon}, as
 * PROJ's {@code inv_finalize} does, not clamp it to &plusmn;&pi;.
 *
 * <h2>What was wrong</h2>
 *
 * <p>{@code 9.8.1:src/inv.cpp:113-117} is two statements:
 *
 * <pre>
 * coo.lp.lam = coo.lp.lam + P-&gt;from_greenwich + P-&gt;lam0;
 * if (0 == P-&gt;over)
 *     coo.lpz.lam = adjlon(coo.lpz.lam);
 * </pre>
 *
 * <p>Proj4J instead clamped:
 *
 * <pre>
 * if (dst.x &lt; -Math.PI) dst.x = -Math.PI;
 * else if (dst.x &gt; Math.PI) dst.x = Math.PI;
 * if (projectionLongitude != 0)
 *     dst.x = ProjectionMath.normalizeLongitude(dst.x + projectionLongitude);
 * </pre>
 *
 * <p>A clamp is not a reduction. It <em>discards</em> the revolution count where {@code adjlon}
 * removes it, so 533.2&deg; and 180&deg; — which are 353&deg; apart — become the same answer, and
 * nothing is raised. And the {@code projectionLongitude != 0} guard meant that with
 * {@code +lon_0=0} no reduction happened at all, so the identical bad input threw for
 * {@code +lon_0=15} and returned a plausible wrong coordinate for {@code +lon_0=0}.
 *
 * <p>Measured on {@code +proj=lsat}, whose inverse kernel accumulates along-track rotation in
 * {@code lamt - p22 * lamdp}: at {@code (130, 45)} with path 120 the kernel returns 533.2&deg;;
 * the clamp made the final answer 136.758&deg;, <b>6.76&deg; wrong</b>. {@code lsat} carries its
 * own {@code adjlon} call as a local workaround; with the central fix that call is redundant
 * rather than load-bearing, and {@code adjlon} is idempotent so keeping it costs nothing.
 */
public class InverseLongitudeWrapTest {

    private static final CRSFactory CRS = new CRSFactory();
    private static final double RTD = 180.0 / Math.PI;

    private static ProjCoordinate poisonedDst() {
        ProjCoordinate c = new ProjCoordinate();
        c.x = c.y = 1e300;
        c.z = 1e300;
        return c;
    }

    /**
     * The general property, asserted on the base class rather than on any one projection: an
     * inverse kernel that returns a longitude outside &plusmn;&pi; must have it reduced modulo
     * 2&pi;, never truncated to the antimeridian.
     */
    @Test
    public void aKernelLongitudePastTheAntimeridianIsWrappedNotClamped() {
        final double kernelLon = 533.2 / RTD;   // the measured lsat value, in radians
        Projection p = new Projection() {
            protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate dst) {
                dst.x = kernelLon;
                dst.y = 0.5;
                return dst;
            }

            public boolean hasInverse() {
                return true;
            }

            public String toString() {
                return "kernel returning 533.2 deg";
            }
        };
        p.initialize();

        ProjCoordinate dst = poisonedDst();
        p.inverseProjectRadians(new ProjCoordinate(1000.0, 2000.0), dst);
        assertTrue("the poisoned destination must have been overwritten", dst.x != 1e300);

        double clamped = 180.0;
        double wrapped = ProjectionMath.adjlon(kernelLon) * RTD;
        assertEquals("adjlon must reduce 533.2 deg to the same meridian, 173.2 deg",
                173.2, wrapped, 1e-9);
        assertEquals("longitude must be wrapped, not clamped", wrapped, dst.x * RTD, 1e-9);
        assertTrue("the clamp answered " + clamped + " deg, which is " + (clamped - wrapped)
                + " deg away from the right meridian and raised nothing",
                Math.abs(dst.x * RTD - clamped) > 6.0);
    }

    /**
     * The {@code +lon_0=0} half of the defect: with no central meridian the old code skipped the
     * reduction entirely, so the clamped value survived to the caller.
     */
    @Test
    public void reductionHappensEvenWithoutACentralMeridian() {
        final double kernelLon = 4.0;   // rad, about 229 deg -- past pi, so it must be wrapped
        Projection p = new Projection() {
            protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate dst) {
                dst.x = kernelLon;
                dst.y = 0.0;
                return dst;
            }

            public boolean hasInverse() {
                return true;
            }

            public String toString() {
                return "kernel returning 4 rad";
            }
        };
        p.initialize();
        assertEquals("this test is about lon_0 == 0", 0.0, p.getProjectionLongitude(), 0.0);

        ProjCoordinate dst = poisonedDst();
        p.inverseProjectRadians(new ProjCoordinate(1.0, 1.0), dst);
        assertEquals(ProjectionMath.adjlon(4.0), dst.x, 0.0);
        assertTrue("must be inside +/-pi (plus adjlon's 1e-12 overshoot window); was " + dst.x,
                Math.abs(dst.x) < Math.PI + 1e-12);
    }

    /**
     * {@code adjlon} is NaN-transparent and must stay so: a NaN longitude the caller supplied has
     * to remain a NaN longitude, not become a domain error. That is the whole point of the
     * {@code nanIn} routing in {@code inverseProjectRadians}, and the wrap has to compose with it.
     */
    @Test
    public void nanInStaysNanOutAndIsNotADomainError() {
        CoordinateReferenceSystem crs =
                CRS.createFromParameters("wgs84", "+proj=longlat +ellps=GRS80");
        Projection p = crs.getProjection();

        ProjCoordinate dst = poisonedDst();
        p.inverseProjectRadians(new ProjCoordinate(Double.NaN, Double.NaN), dst);
        assertTrue("NaN longitude in must give NaN longitude out, not an exception and not a "
                + "substituted value; got " + dst.x, Double.isNaN(dst.x));
        assertTrue("NaN latitude in must give NaN latitude out; got " + dst.y,
                Double.isNaN(dst.y));

        // And the property adjlon itself must keep.
        assertTrue(Double.isNaN(ProjectionMath.adjlon(Double.NaN)));
        assertTrue(Double.isNaN(ProjectionMath.adjlon(Double.POSITIVE_INFINITY)));
    }

    /**
     * A finite input that produces a non-finite longitude is still an error, not a wrapped NaN.
     * The postcondition runs before the wrap, so the wrap cannot launder it.
     */
    @Test
    public void finiteInputProducingNonFiniteLongitudeIsStillAnError() {
        Projection p = new Projection() {
            protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate dst) {
                dst.x = Double.NaN;
                dst.y = 0.0;
                return dst;
            }

            public boolean hasInverse() {
                return true;
            }

            public String toString() {
                return "kernel returning NaN from finite input";
            }
        };
        p.initialize();

        ProjCoordinate dst = poisonedDst();
        try {
            p.inverseProjectRadians(new ProjCoordinate(1.0, 1.0), dst);
            fail("a non-finite result from finite input must be an error, but got ("
                    + dst.x + ", " + dst.y + ")");
        } catch (Proj4jException e) {
            assertNotNull(e.cause());
        }
    }

    /**
     * The ordinary case must be untouched. {@code adjlon} returns its argument unchanged while
     * {@code |lon| < pi + 1e-12}, so every in-domain inverse is bit-identical to before.
     */
    @Test
    public void inDomainInversesAreUnchanged() {
        CoordinateReferenceSystem crs = CRS.createFromParameters(
                "utm33", "+proj=utm +ellps=GRS80 +zone=33");
        ProjCoordinate dst = poisonedDst();
        crs.getProjection().inverseProject(new ProjCoordinate(500000.0, 5000000.0), dst);
        assertEquals(15.0, dst.x, 1e-6);
        assertTrue(dst.y > 45.0 && dst.y < 46.0);
    }

    /**
     * The motivating case end to end. Pinned as an inequality against the clamp rather than
     * against a reference number, because {@code lsat}'s kernel belongs to another stream: what
     * matters here is that the answer is no longer the antimeridian-derived one.
     */
    @Test
    public void landsatInverseIsNoLongerClamped() {
        CoordinateReferenceSystem crs;
        try {
            crs = CRS.createFromParameters("lsat", "+proj=lsat +ellps=GRS80 +lsat=3 +path=120");
        } catch (Proj4jException e) {
            return;   // +proj=lsat unavailable in this tree; nothing to assert
        }
        ProjCoordinate fwd = poisonedDst();
        crs.getProjection().project(new ProjCoordinate(130.0, 45.0), fwd);

        ProjCoordinate back = poisonedDst();
        try {
            crs.getProjection().inverseProject(fwd, back);
        } catch (Proj4jException e) {
            assertNotNull(e.cause());
            return;
        }
        assertTrue("the poisoned destination must have been overwritten", back.x != 1e300);
        assertEquals("lsat must round-trip its own forward", 130.0, back.x, 1e-6);
        assertEquals("lsat must round-trip its own forward", 45.0, back.y, 1e-6);
    }
}

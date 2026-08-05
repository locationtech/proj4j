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

package org.locationtech.proj4j.proj.lsat;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.proj.Projection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code +proj=lsat} — Space Oblique Mercator for Landsat — had no inverse, while
 * {@code LandsatProjection.hasInverse()} returned {@code true}.
 *
 * <h2>The defect</h2>
 *
 * <p>The inverse existed in the file only as a commented-out block whose signature was
 * {@code projectInverse(double, double, java.awt.geom.Point2D.Double)}. Even uncommented it would
 * have overridden nothing — {@code Projection.projectInverse} takes a {@link ProjCoordinate} — and
 * commented out it left {@code hasInverse()} returning {@code true} over the base class's
 * <em>identity</em>. So an inverse transform out of an {@code lsat} CRS returned the projected
 * metres straight back, reinterpreted as longitude and latitude in radians. At
 * {@code (18241950, 9998257)} the old answer was {@code lon = 180 deg}, {@code lat = 90 deg}
 * — the clamps in {@code inverseProjectRadians} tidying 1.05e9 degrees into something that looks
 * like a coordinate. That is the third forbidden failure shape: a failure expressed as a plausible
 * coordinate.
 *
 * <p>The fail-closed stream gated 18 other forward-only projections and moved 90 golden rows to
 * {@code NO_INVERSE_AVAILABLE}, 75 of which had been {@code OK} in 1.4.3. {@code lsat} was outside
 * its scope and stayed silently wrong.
 *
 * <h2>Why it is implemented rather than gated</h2>
 *
 * <p>{@code lsat} <strong>does</strong> have an inverse upstream.
 * {@code 9.8.1:src/projections/som.cpp} defines one {@code som_setup} shared by {@code som},
 * {@code misrsom} and {@code lsat}, and it assigns {@code P->inv = som_e_inverse}
 * unconditionally for all three; {@code lsat} differs from {@code misrsom} only in
 * {@code Q->rlm}, {@code Q->alf}, {@code Q->p22} and {@code P->lam0}. {@code builtins.gie:4058}
 * asserts four {@code direction inverse} rows against it. Returning {@code false} from
 * {@code hasInverse()} would have been a fail-closed lie about a projection that is invertible.
 *
 * <h2>References</h2>
 *
 * <p>Pinned with the <b>{@code proj}</b> CLI at 9.8.1, not {@code cs2cs}: {@code cs2cs} promotes
 * both datum-less sides to full CRSs and does not give the bare projection. Command:
 *
 * <pre>
 * proj -I -f '%.12f' +proj=lsat +lsat=1 +path=120 +ellps=GRS80
 * </pre>
 *
 * <p>{@code +lsat=1 +path=120} because {@code LandsatProjection.initialize()} hard-codes those two
 * — the {@code pj_param} lookups are still {@code FIXME}s, owned by the parameter-parsing work —
 * so those are the values the Java object actually holds regardless of what the string says.
 * Fixing that is not this change; every reference here is pinned to the configuration proj4j
 * really builds, so the numbers stay meaningful until it is.
 */
public class LandsatInverseTest {

    private static final String SPEC = "+proj=lsat +ellps=GRS80";

    /** {@code x, y, expected lon (deg), expected lat (deg)} from {@code proj} 9.8.1. */
    private static final double[][] INVERSE_REFERENCES = {
            {18241950.014558550, 9998256.839822935, -167.243027865279, 0.999999968972},
            {38716066.260231376, -10782123.899122790, 1.999999848447, 1.000000027861},
            {38180602.096364856, -10525261.769020729, 2.000000071685, -0.999999941928},
            {39036973.116934061, -9755401.179111505, -1.999999685261, 1.000000008081},
            {38556530.040871613, -9565777.310432356, -2.000000295611, -0.999999981657},
            {15129439.361304788, 163464.055640934, 129.999999990699, 44.999999999602},
            {23498812.644032214, -116918.378588834, 120.000000032071, -29.999999985404},
            {40034527.266087197, -2957487.604201536, -43.239999735881, 0.000000020076},
            {49361816.436919674, -3660001.500377729, 0.000000271548, 60.000000030222},
            {33699465.325303867, 2355654.954105128, -100.000000346009, -55.000000009721},
            {200, 100, -43.242604053916, 0.001723782240},
            {-200, -100, -43.240503515805, -0.001723782240},
            {1000000, 500000, -48.543067871543, 8.593545577286},
            {-5000000, 2000000, -63.352485376498, -43.600515382404},
            {0, 0, -43.241553784861, 0.000000000000},
    };

    private static Projection lsat(String spec) {
        return new CRSFactory().createFromParameters("t", spec).getProjection();
    }

    /**
     * The regression guard proper. If {@code projectInverse} is ever removed, renamed, or given a
     * signature that does not override the base method again, this fails first and says why.
     */
    @Test
    public void theInverseIsNotTheBaseClassIdentity() {
        Projection p = lsat(SPEC);
        assertTrue("hasInverse() must stay true; lsat is invertible upstream", p.hasInverse());
        ProjCoordinate src = new ProjCoordinate(18241950.014558550, 9998256.839822935);
        ProjCoordinate dst = new ProjCoordinate();
        p.inverseProject(src, dst);
        assertTrue("the base-class identity would return the projected metres as radians and "
                        + "the clamps would tidy them into (180, 90); got (" + dst.x + ", "
                        + dst.y + ")",
                Math.abs(dst.x) < 180.0 && Math.abs(dst.y) < 90.0);
        assertTrue("latitude is nowhere near the clamp", Math.abs(dst.y) < 5.0);
    }

    @Test
    public void inverseMatchesProj981() {
        Projection p = lsat(SPEC);
        ProjCoordinate src = new ProjCoordinate();
        ProjCoordinate dst = new ProjCoordinate();
        // 1e-9 deg is about 0.1 mm; the printed references carry 12 decimals, so the bar is
        // limited by the reference text, not by the implementation.
        final double tol = 2e-9;
        for (double[] r : INVERSE_REFERENCES) {
            src.x = r[0];
            src.y = r[1];
            p.inverseProject(src, dst);
            assertEquals("lon for (" + r[0] + ", " + r[1] + ")", r[2], dst.x, tol);
            assertEquals("lat for (" + r[0] + ", " + r[1] + ")", r[3], dst.y, tol);
        }
    }

    /**
     * The kernel's {@code lamt - p22*lamdp} accumulates the satellite's along-track rotation and
     * legitimately exceeds &pi;: at {@code (130, 45)} with path 120 it returns 533.2&deg;.
     * {@code inverseProjectRadians} <em>clamps</em> to &plusmn;&pi; rather than wrapping, so
     * without an {@code adjlon} inside {@code projectInverse} that becomes 180&deg; and the answer
     * comes back 6.76&deg; wrong. This is the case that catches a regression there.
     */
    @Test
    public void longitudesPastPiAreWrappedNotClamped() {
        Projection p = lsat(SPEC);
        ProjCoordinate src = new ProjCoordinate(15129439.361304788, 163464.055640934);
        ProjCoordinate dst = new ProjCoordinate();
        p.inverseProject(src, dst);
        assertEquals("must wrap to 130 deg, not clamp to 136.758 deg", 130.0, dst.x, 1e-8);
        assertEquals(45.0, dst.y, 1e-8);
    }

    @Test
    public void forwardThenInverseRoundTrips() {
        for (String spec : new String[] {SPEC, "+proj=lsat +ellps=sphere",
                "+proj=lsat +R=6371000"}) {
            Projection p = lsat(spec);
            ProjCoordinate src = new ProjCoordinate();
            ProjCoordinate mid = new ProjCoordinate();
            ProjCoordinate back = new ProjCoordinate();
            int n = 0;
            double worst = 0.0;
            String worstAt = "";
            for (double lon = -180; lon <= 180; lon += 2.5) {
                for (double lat = -84; lat <= 84; lat += 2.5) {
                    src.x = lon;
                    src.y = lat;
                    try {
                        p.project(src, mid);
                    } catch (ProjectionException e) {
                        continue; // the forward's own 50-trip cap; upstream has the same one
                    }
                    p.inverseProject(mid, back);
                    double dlon = Math.abs(back.x - lon);
                    if (dlon > 180) {
                        dlon = Math.abs(dlon - 360);
                    }
                    double d = Math.max(dlon, Math.abs(back.y - lat));
                    if (d > worst) {
                        worst = d;
                        worstAt = "(" + lon + ", " + lat + ")";
                    }
                    n++;
                }
            }
            assertTrue(spec + ": the sweep did not run, n=" + n, n > 8000);
            // The kernel's convergence test is |dlamdp| < 1e-7 (dimensionless), upstream's TOL,
            // so ~1e-6 deg is the algorithm's own floor and not a porting error.
            assertTrue(spec + ": worst round-trip residual " + worst + " deg at " + worstAt,
                    worst < 1e-5);
        }
    }

    /**
     * {@code builtins.gie:4081} — {@code direction inverse}, {@code accept 0 1e10},
     * {@code expect failure errno coord_transfm_outside_projection_domain}. 9.8.1 added that guard
     * (the {@code denom == 0.0} test); the commented-out draft in this file predates it and would
     * have divided by zero and returned {@code atan(inf)} as a coordinate.
     */
    @Test
    public void theOutOfDomainInverseFailsRatherThanAnswering() {
        Projection p = lsat("+proj=lsat +ellps=sphere");
        ProjCoordinate src = new ProjCoordinate(0, 1e10);
        ProjCoordinate dst = new ProjCoordinate();
        try {
            p.inverseProject(src, dst);
            fail("inverse of (0, 1e10) returned (" + dst.x + ", " + dst.y + "); upstream sets "
                    + "PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN there");
        } catch (ProjectionException e) {
            assertNotNull("the exception must carry a cause, not just a message", e.cause());
            assertEquals(ErrorCause.COORDINATE_OUT_OF_DOMAIN, e.cause());
        }
    }
}

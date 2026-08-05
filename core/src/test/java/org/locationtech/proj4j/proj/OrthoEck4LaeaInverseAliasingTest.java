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
package org.locationtech.proj4j.proj;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * Applies the shared "poison the destination" probe to {@code ortho}, {@code eck4} and
 * {@code laea}, and records the answer: <b>none of the three has the aliasing defect</b>.
 *
 * <h2>The defect this looks for</h2>
 *
 * <p>Six per-projection inverse bugs in this package are one bug. The 2006 semi-automatic C-to-Java
 * conversion turned C's "mutate the argument, then read it back" into "write the <em>output</em>
 * struct, then read the original parameter" - {@code aea}, {@code collg}, {@code fahey},
 * {@code bonne} (both branches), {@code bipc} and {@code SimpleConicProjection} with its seven
 * subclasses. Its signature is that {@code projectInverse} returns a different answer depending on
 * what the caller happened to leave in {@code dst}, because a value the method believes it wrote is
 * really a value the caller supplied.
 *
 * <p>It is invisible to an ordinary test, which passes a fresh {@code ProjCoordinate} whose fields
 * are {@code 0.0} - frequently close enough to the right answer that the result still looks
 * plausible. Poisoning {@code dst} with a value no coordinate can be makes it visible.
 *
 * <h2>The answer for these three, and why it is worth pinning</h2>
 *
 * <p>All three are clean, and for a structural reason rather than by luck: each copies its inputs
 * into locals on entry ({@code xx}/{@code yy} in {@code ortho}, {@code sinTheta} in {@code eck4},
 * {@code lplam}/{@code lpphi} in {@code laea}) and every read of {@code lp}/{@code out} is a read
 * of something the same call already wrote in the same branch, mirroring upstream's own mutation
 * of {@code lp.phi}. {@code ortho}'s ellipsoidal oblique arm is the interesting case: it
 * deliberately <em>seeds</em> from {@code lp} by calling {@code sphericalInverse(..., lp)} and then
 * reads {@code lp.x}/{@code lp.y} back as the Newton starting point, which is the exact shape of
 * the defect - except that the seed is written before it is read, so the poison is overwritten
 * first. That distinction is not obvious from reading the code, which is why it is asserted rather
 * than argued.
 *
 * <p>The test is written so that it would fail if any of that changed: it compares a poisoned run
 * against a clean run <em>bit for bit</em>, not to a tolerance.
 */
public class OrthoEck4LaeaInverseAliasingTest {

    /** No coordinate, in radians or metres, can be this. A stale read of it is unmistakable. */
    private static final double POISON = 1.0e300;

    /** Every definition in scope, spherical and ellipsoidal, every azimuthal aspect. */
    private static final String[] DEFINITIONS = {
        "+proj=ortho +R=1",
        "+proj=ortho +R=1 +lat_0=40",
        "+proj=ortho +R=1 +lat_0=90",
        "+proj=ortho +R=1 +lat_0=-90",
        "+proj=ortho +ellps=WGS84",
        "+proj=ortho +ellps=WGS84 +lat_0=30",
        "+proj=ortho +ellps=WGS84 +lat_0=90",
        "+proj=ortho +ellps=WGS84 +lat_0=-90",
        "+proj=ortho +ellps=GRS80 +lat_0=37.628969166666664 +lon_0=-122.39394166666668"
                + " +k_0=0.9999968 +alpha=27.7927777777777",
        "+proj=eck4 +a=6400000",
        "+proj=eck4 +ellps=WGS84",
        "+proj=laea +ellps=WGS84",
        "+proj=laea +ellps=WGS84 +lat_0=45",
        "+proj=laea +ellps=WGS84 +lat_0=90",
        "+proj=laea +ellps=WGS84 +lat_0=-90",
        "+proj=laea +R=6400000",
        "+proj=laea +R=6400000 +lat_0=45",
        "+proj=laea +R=6400000 +lat_0=90",
    };

    /** Longitude/latitude probes in degrees, kept inside every definition's visible hemisphere. */
    private static final double[][] PROBES = {
        {0, 0}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1},
        {10, 20}, {-10, 20}, {10, -20}, {20, 60}, {-20, -60},
        {0, 80}, {0, -80}, {5, 89}, {5, -89},
    };

    /**
     * The forward of each probe, inverted twice: once into a fresh destination and once into a
     * destination poisoned with {@link #POISON}. The two must agree in every bit.
     */
    @Test
    public void aPoisonedDestinationChangesNoInverseResult() {
        CRSFactory factory = new CRSFactory();
        int compared = 0;
        for (String definition : DEFINITIONS) {
            Projection p = factory.createFromParameters("t", definition).getProjection();
            for (double[] probe : PROBES) {
                ProjCoordinate xy = forwardOrNull(p, probe[0], probe[1]);
                if (xy == null) {
                    continue; // outside this definition's domain; not what is under test
                }
                ProjCoordinate clean = new ProjCoordinate();
                ProjCoordinate dirty = new ProjCoordinate(POISON, -POISON);
                p.inverseProjectRadians(xy, clean);
                p.inverseProjectRadians(xy, dirty);

                String where = definition + " at (" + probe[0] + ", " + probe[1] + ")";
                assertEquals(where + " x", Double.doubleToLongBits(clean.x),
                        Double.doubleToLongBits(dirty.x));
                assertEquals(where + " y", Double.doubleToLongBits(clean.y),
                        Double.doubleToLongBits(dirty.y));
                compared++;
            }
        }
        // Without this the loop could silently compare nothing - every probe rejected, every
        // assertion skipped, green.
        assertTrue("only " + compared + " (definition, probe) pairs were actually inverted, which "
                + "is too few for this to be a measurement", compared > 150);
    }

    /**
     * The probe's own sensitivity. A hand-rolled inverse with the historical defect - it writes the
     * answer into {@code out} but then reads the caller's {@code out.x} back - must be caught by
     * the very same comparison. Without this the test above could pass because the comparison is
     * broken rather than because the code is clean.
     */
    @Test
    public void theProbeCatchesTheHistoricalDefectShape() {
        ProjCoordinate clean = new ProjCoordinate();
        ProjCoordinate dirty = new ProjCoordinate(POISON, -POISON);
        defectiveInverse(0.5, 0.25, clean);
        defectiveInverse(0.5, 0.25, dirty);
        assertTrue("the poisoned-destination comparison cannot see a stale read, so the assertions "
                + "in aPoisonedDestinationChangesNoInverseResult prove nothing",
                Double.doubleToLongBits(clean.x) != Double.doubleToLongBits(dirty.x));
    }

    /**
     * The shape of the 2006 conversion bug, in miniature: C wrote {@code lp.lam = f(x)} and then
     * read {@code lp.lam}; the translation writes {@code out.x} and then reads {@code out.x} at a
     * point where it has <em>not yet</em> been written on this path.
     */
    private static void defectiveInverse(double x, double y, ProjCoordinate out) {
        out.y = Math.atan(y);
        // The stale read: on the intended path this is f(x), but the assignment below is what
        // actually produces it, and it has not happened yet.
        double staleLam = out.x;
        out.x = Math.atan2(x, 1.0) + 0.0 * staleLam + (staleLam > 1.0 ? 1.0 : 0.0);
    }

    /**
     * @return the forward projection, or {@code null} if this definition rejects the point - a
     *         domain refusal is a correct answer and simply means the probe is not usable here
     */
    private static ProjCoordinate forwardOrNull(Projection p, double lonDeg, double latDeg) {
        ProjCoordinate xy = new ProjCoordinate();
        try {
            p.projectRadians(new ProjCoordinate(radians(lonDeg), radians(latDeg)), xy);
        } catch (RuntimeException rejected) {
            return null;
        }
        if (!isFinite(xy.x) || !isFinite(xy.y)) {
            fail("forward of (" + lonDeg + ", " + latDeg + ") returned a non-finite coordinate "
                    + "without refusing: " + xy);
        }
        return xy;
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    /** {@code Math.toRadians} is banned in {@code core/src/main}; this mirrors what it does. */
    private static double radians(double degrees) {
        return degrees * (Math.PI / 180.0);
    }
}

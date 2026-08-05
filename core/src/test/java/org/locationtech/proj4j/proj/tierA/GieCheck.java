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

package org.locationtech.proj4j.proj.tierA;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.gie.GieComparator;
import org.locationtech.proj4j.gie.GieDirection;
import org.locationtech.proj4j.gie.GieIoUnits;
import org.locationtech.proj4j.proj.Projection;

/**
 * Runs the {@code accept}/{@code expect}/{@code roundtrip} rows of a {@link GieBlock}
 * against Proj4J, measuring deviation with {@link GieComparator} — the published port of
 * gie's own {@code expect()} comparison.
 *
 * <h2>Why this delegates rather than computing a distance itself</h2>
 *
 * <p>gie does not subtract coordinates. It selects among four metrics by the units of the
 * <em>target side</em>, and for a projection the two that arise are:
 *
 * <ul>
 * <li><b>forward</b> — the target is projected metres, so the deviation is Euclidean
 *     {@code hypot};
 * <li><b>inverse</b> — the target is angular, so the deviation is the <b>geodesic arc
 *     length</b> between expected and observed on the operation's ellipsoid.
 * </ul>
 *
 * <p>A degree of latitude is about 111.3 km, so confusing the two inflates or deflates by
 * that factor. Reimplementing this by hand is how a harness ends up measuring the wrong
 * thing while looking right, so this uses {@link GieComparator} and
 * {@link GieIoUnits#outputUnits} to pick the branch rather than deciding locally.
 *
 * <p>A bare {@code operation +proj=X} has {@code left = RADIANS, right = CLASSIC}
 * ({@code proj_internal.h:882-883}), so {@code outputUnits} yields {@code CLASSIC} — folded
 * to {@code PROJECTED}, hence Euclidean — for the forward direction and {@code RADIANS} for
 * the inverse.
 *
 * <p>The inverse target needs one conversion this class does explicitly: the corpus writes
 * the expected geographic coordinate in <b>degrees</b>, and gie converts it with
 * {@code torad_coord} before comparing because PROJ's raw inverse returns radians. Proj4J's
 * {@code inverseProject} returns degrees, so both sides are converted here and the
 * {@code GEODESIC_FROM_RADIANS} branch is used — the same arithmetic gie performs, in the
 * same order.
 *
 * <h2>{@code roundtrip} phasing</h2>
 *
 * <p>{@code roundtrip n} is not "n forward-inverse pairs". Upstream
 * ({@code src/trans.cpp:591}) takes a half-step out, then {@code n-1} full there-and-back
 * cycles, then a final half-step home, so the residual is measured in the <em>input</em>
 * space and the metric is chosen by {@code proj_angular_}<b>{@code input}</b>. A naive
 * n-fold loop applies a different number of transforms and lands somewhere else.
 */
final class GieCheck {

    private GieCheck() {
    }

    /** Outcome of running one block. */
    static final class Result {
        final String operation;
        final int passed;
        final int total;
        final List<String> failures = new ArrayList<String>();

        Result(String operation, int passed, int total) {
            this.operation = operation;
            this.passed = passed;
            this.total = total;
        }
    }

    /**
     * Runs every row of every block for {@code projName} in {@code file} and asserts that
     * all of them agree.
     *
     * @param expectedRows the number of rows the corpus is expected to contribute, asserted
     *     so that a silently-empty run cannot masquerade as a pass
     */
    static void assertAllRows(String file, String projName, int expectedRows) {
        List<GieBlock> blocks = GieBlock.blocksFor(file, projName);
        assertTrue("no " + projName + " operation found in " + file
                + " -- the corpus moved or the whole-token match is wrong",
                !blocks.isEmpty());

        int total = 0;
        List<String> failures = new ArrayList<String>();
        for (GieBlock block : blocks) {
            Result r = run(block);
            total += r.total;
            failures.addAll(r.failures);
        }
        assertTrue(projName + ": expected " + expectedRows + " corpus rows, found " + total
                + " -- a changed count means the corpus moved or the parser dropped rows",
                total == expectedRows);
        if (!failures.isEmpty()) {
            fail(projName + ": " + failures.size() + " of " + total + " corpus rows deviate:"
                    + System.lineSeparator() + String.join(System.lineSeparator(), failures));
        }
    }

    /** Runs one block without asserting, so a caller can count partial agreement. */
    static Result run(GieBlock block) {
        CoordinateReferenceSystem crs =
                new CRSFactory().createFromParameters("gie", block.operation());
        Projection p = crs.getProjection();
        GieComparator cmp = comparatorFor(p);

        int passed = 0;
        List<String> failures = new ArrayList<String>();
        for (GieBlock.Row row : block.rows()) {
            double deviation;
            try {
                deviation = row.isRoundtrip()
                        ? roundtripResidual(cmp, p, row)
                        : oneWayDeviation(cmp, p, row);
            } catch (RuntimeException e) {
                failures.add("  " + block.operation() + " " + row + ": threw "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                continue;
            }
            if (GieComparator.withinTolerance(deviation, row.toleranceMetres)) {
                passed++;
            } else {
                failures.add("  " + block.operation() + " " + row + ": deviation "
                        + deviation + " m exceeds tolerance " + row.toleranceMetres + " m");
            }
        }
        Result result = new Result(block.operation(), passed, block.rows().size());
        result.failures.addAll(failures);
        return result;
    }

    /**
     * The geodesic figure for the deviation metric. Every Tier A corpus block states its
     * figure explicitly ({@code +a=}, {@code +R=} or {@code +ellps=}), so this reads it from
     * the constructed projection; the "bare operation defaults to WGS84" rule
     * ({@code init.cpp:576-581}) never has to be applied.
     */
    private static GieComparator comparatorFor(Projection p) {
        final double a = p.getEllipsoid().equatorRadius;
        final double b = p.getEllipsoid().poleRadius;
        return GieComparator.forEllipsoid(a, a == 0.0 ? 0.0 : (a - b) / a);
    }

    /** The output-side units for a bare projection operation: {@code left=RADIANS, right=CLASSIC}. */
    private static GieIoUnits outputUnits(boolean inverse) {
        return GieIoUnits.outputUnits(GieIoUnits.RADIANS, GieIoUnits.CLASSIC, false,
                inverse ? GieDirection.INVERSE : GieDirection.FORWARD);
    }

    private static double oneWayDeviation(GieComparator cmp, Projection p, GieBlock.Row row) {
        ProjCoordinate in = new ProjCoordinate(row.accept[0], row.accept[1]);
        ProjCoordinate out = new ProjCoordinate();
        double[] expected;
        double[] got;

        if (row.inverse) {
            p.inverseProject(in, out);
            // gie's torad_coord on the expect line, and PROJ's raw inv returns radians.
            expected = new double[] {Math.toRadians(row.expect[0]),
                    Math.toRadians(row.expect[1]), 0, 0};
            got = new double[] {Math.toRadians(out.x), Math.toRadians(out.y), 0, 0};
        } else {
            p.project(in, out);
            expected = new double[] {row.expect[0], row.expect[1], 0, 0};
            got = new double[] {out.x, out.y, 0, 0};
        }
        return cmp.compare(outputUnits(row.inverse), false, expected, got,
                row.expect.length, row.toleranceMetres).deviation();
    }

    /**
     * {@code roundtrip n}, phased exactly as {@code src/trans.cpp:591}: one half-step out,
     * {@code n-1} full cycles, one half-step home. The residual is in the input space, so a
     * {@code direction forward} roundtrip is measured angularly and a
     * {@code direction inverse} one in metres.
     */
    private static double roundtripResidual(GieComparator cmp, Projection p,
            GieBlock.Row row) {
        final double x0 = row.accept[0];
        final double y0 = row.accept[1];
        ProjCoordinate t = new ProjCoordinate(x0, y0);
        t = step(p, t, row.inverse);                        // first half-step
        for (int i = 1; i < row.roundtripTrips; i++) {
            t = step(p, step(p, t, !row.inverse), row.inverse);
        }
        t = step(p, t, !row.inverse);                       // last half-step

        // The metric is chosen by the *input* units, so the roles are the reverse of a
        // one-way row: a forward roundtrip lands back in angular space.
        final boolean angularResidual = !row.inverse;
        double[] expected;
        double[] got;
        if (angularResidual) {
            expected = new double[] {Math.toRadians(x0), Math.toRadians(y0), 0, 0};
            got = new double[] {Math.toRadians(t.x), Math.toRadians(t.y), 0, 0};
        } else {
            expected = new double[] {x0, y0, 0, 0};
            got = new double[] {t.x, t.y, 0, 0};
        }
        return cmp.compare(outputUnits(!angularResidual), false, expected, got, 2,
                row.toleranceMetres).deviation();
    }

    private static ProjCoordinate step(Projection p, ProjCoordinate src, boolean inverse) {
        ProjCoordinate dst = new ProjCoordinate();
        if (inverse) {
            p.inverseProject(src, dst);
        } else {
            p.project(src, dst);
        }
        return dst;
    }
}

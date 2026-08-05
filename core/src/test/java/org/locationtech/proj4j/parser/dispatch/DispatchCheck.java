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

package org.locationtech.proj4j.parser.dispatch;

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
 * Runs the rows of a {@link DispatchCorpus} block against Proj4J, measuring deviation with
 * {@link GieComparator} — the published port of gie's own {@code expect()} comparison.
 *
 * <h2>Why the distance is delegated</h2>
 *
 * <p>gie does not subtract coordinates: it picks among four metrics by the units of the
 * <em>target</em> side. For a bare projection {@code operation} the two that arise are
 * Euclidean {@code hypot} on the forward (projected metres) and the <b>geodesic arc length</b>
 * on the inverse (angular). A degree of latitude is about 111.3&nbsp;km, so applying the wrong
 * branch inflates or deflates by that factor — which is how a harness ends up measuring the
 * wrong thing while looking right. Hence {@link GieComparator} and
 * {@link GieIoUnits#outputUnits} rather than a local subtraction.
 *
 * <p>A bare {@code operation +proj=X} has {@code left = RADIANS, right = CLASSIC}
 * ({@code proj_internal.h:882-883}). The corpus writes an inverse block's expected coordinate
 * in degrees and gie converts it with {@code torad_coord} before comparing, because PROJ's raw
 * inverse returns radians; Proj4J's {@code inverseProject} returns degrees, so both sides are
 * converted here and the radian-geodesic branch is used — the same arithmetic gie performs, in
 * the same order.
 */
final class DispatchCheck {

    private DispatchCheck() {
    }

    /**
     * Runs every coordinate row of every corpus block naming {@code +proj=<projName>} and
     * asserts that all of them agree with PROJ.
     *
     * @param expectedRows the number of rows the corpus is expected to contribute, asserted so
     *        that a silently-empty run cannot masquerade as a pass
     */
    static void assertCorpusAgrees(String file, String projName, int expectedRows) {
        List<DispatchCorpus> blocks = DispatchCorpus.blocksFor(file, projName);
        assertTrue("no +proj=" + projName + " operation found in " + file
                + " -- the corpus moved, or the whole-token match is wrong",
                !blocks.isEmpty());

        int total = 0;
        List<String> failures = new ArrayList<String>();
        for (int b = 0; b < blocks.size(); b++) {
            DispatchCorpus block = blocks.get(b);
            List<DispatchCorpus.Row> rows = block.coordinateRows();
            total += rows.size();
            if (rows.isEmpty()) {
                // An `expect failure` block asserts an error rather than a coordinate, so
                // constructing it here would report upstream's intended rejection as a defect.
                // Those blocks are asserted directly instead - see
                // ParameterDispatchTest.urm5RejectsNSinAlphaEqualToOne.
                continue;
            }
            Projection p;
            try {
                CoordinateReferenceSystem crs =
                        new CRSFactory().createFromParameters("gie", block.operation());
                p = crs.getProjection();
            } catch (RuntimeException e) {
                failures.add("  " + file + ":" + block.lineNumber() + " " + block.operation()
                        + ": could not be constructed at all -- "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                continue;
            }
            GieComparator cmp = comparatorFor(p);
            for (int i = 0; i < rows.size(); i++) {
                DispatchCorpus.Row row = rows.get(i);
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
                if (!GieComparator.withinTolerance(deviation, row.toleranceMetres)) {
                    failures.add("  " + block.operation() + " " + row + ": deviation "
                            + deviation + " exceeds tolerance " + row.toleranceMetres);
                }
            }
        }
        assertTrue("+proj=" + projName + ": expected " + expectedRows + " corpus rows in " + file
                + ", found " + total + " -- a changed count means the corpus moved",
                total == expectedRows);
        if (!failures.isEmpty()) {
            fail("+proj=" + projName + ": " + failures.size() + " of " + total
                    + " corpus rows deviate:" + System.lineSeparator()
                    + String.join(System.lineSeparator(), failures));
        }
    }

    /**
     * The geodesic figure for the metric, read from the constructed projection. Every block
     * used here states its figure explicitly ({@code +a=}, {@code +R=} or {@code +ellps=}).
     */
    private static GieComparator comparatorFor(Projection p) {
        final double a = p.getEllipsoid().equatorRadius;
        final double b = p.getEllipsoid().poleRadius;
        return GieComparator.forEllipsoid(a, a == 0.0 ? 0.0 : (a - b) / a);
    }

    private static GieIoUnits outputUnits(boolean inverse) {
        return GieIoUnits.outputUnits(GieIoUnits.RADIANS, GieIoUnits.CLASSIC, false,
                inverse ? GieDirection.INVERSE : GieDirection.FORWARD);
    }

    private static double oneWayDeviation(GieComparator cmp, Projection p,
            DispatchCorpus.Row row) {
        ProjCoordinate in = new ProjCoordinate(row.accept[0], row.accept[1]);
        ProjCoordinate out = new ProjCoordinate();
        double[] expected;
        double[] got;

        if (row.inverse) {
            p.inverseProject(in, out);
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
     * {@code n-1} full cycles, one half-step home, so the residual is measured in the
     * <em>input</em> space. A naive n-fold loop applies a different number of transforms.
     */
    private static double roundtripResidual(GieComparator cmp, Projection p,
            DispatchCorpus.Row row) {
        final double x0 = row.accept[0];
        final double y0 = row.accept[1];
        ProjCoordinate t = new ProjCoordinate(x0, y0);
        t = step(p, t, row.inverse);
        for (int i = 1; i < row.roundtripTrips; i++) {
            t = step(p, step(p, t, !row.inverse), row.inverse);
        }
        t = step(p, t, !row.inverse);

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

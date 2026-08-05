/*
 * Copyright 2026 The Proj4J Contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.locationtech.proj4j.conformance.bridge;

import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.gie.GieDirection;
import org.locationtech.proj4j.gie.GieIoUnits;

/**
 * A gie {@code crs_src} + {@code crs_dst} pair, run through proj4j's
 * {@code CoordinateTransformFactory}.
 *
 * <p><b>Units are degrees, not radians, on a geographic side.</b> PROJ builds
 * these operations with {@code proj_create_crs_to_crs}, whose pipeline ends in a
 * {@code +proj=unitconvert} to {@code Degree} for a geographic CRS — one of only
 * two places {@link GieIoUnits#DEGREES} arises in the whole library
 * ({@code unitconvert.cpp:491-493,514-516}). proj4j's
 * {@code BasicCoordinateTransform} happens to agree: it takes and returns degrees
 * for a geographic CRS. So no conversion happens here either.
 *
 * <p>Both directions are supported by building the transform twice, once each way,
 * because {@code CoordinateTransform} has no inverse method.
 *
 * <h2>How little of the corpus reaches this class</h2>
 *
 * <p>{@code crs_src}/{@code crs_dst} appear in exactly two corpus files,
 * {@code epsg_no_grid.gie} (7 pairs, 6 assertions) and {@code epsg_grid.gie}
 * (3 pairs, 2 assertions). Measured by counting invocations over a full sweep:
 * {@link #transform} is entered <b>3 times</b> in the whole 7,923-assertion run, for
 * two distinct CRS pairs, and reaches the bottom of the method <b>once</b>. All 8 of
 * those assertions currently score FAIL (6) or SKIP (2) — <b>none is a pass</b>, so
 * no figure in the headline depends on anything this class decides. That is also why
 * this class is the <em>only</em> route from the corpus into
 * {@code BasicCoordinateTransform}: every other operation goes to
 * {@code SingleProjectionOperation} or {@code PipelineGieOperation}, neither of which
 * touches {@code CoordinateTransform}.
 */
final class CrsToCrsOperation implements GieOperation {

    private final String source;
    private final String target;
    private final CoordinateTransform forward;
    private final CoordinateTransform inverse;
    private final GieIoUnits left;
    private final GieIoUnits right;

    private GieFailure lastFailure;

    CrsToCrsOperation(String source, String target, CoordinateTransform forward,
            CoordinateTransform inverse, GieIoUnits left, GieIoUnits right) {
        this.source = source;
        this.target = target;
        this.forward = forward;
        this.inverse = inverse;
        this.left = left;
        this.right = right;
    }

    @Override
    public boolean isUsable() {
        return true;
    }

    @Override
    public GieFailure failure() {
        return null;
    }

    @Override
    public GieIoUnits leftUnits() {
        return left;
    }

    @Override
    public GieIoUnits rightUnits() {
        return right;
    }

    @Override
    public boolean isInverted() {
        // A crs_src/crs_dst pair is never built with +inv; gie's `direction`
        // command is what reverses it, and that arrives as the `dir` argument.
        return false;
    }

    @Override
    public boolean crsDstIsLatLonOrYX() {
        // See GieOperation#crsDstIsLatLonOrYX. This is the case where the
        // limitation actually costs assertions: EPSG:2393 ("Finland YKJ Northing,
        // Easting") and the other latitude-first targets in epsg_no_grid.gie need
        // the swap, and proj4j exposes no axis metadata to detect them with.
        return false;
    }

    @Override
    public GieFailure lastFailure() {
        return lastFailure;
    }

    @Override
    public double[] transform(double[] in, GieDirection dir) {
        lastFailure = null;
        if (in == null || in.length < 2) {
            lastFailure = GieFailures.of(GieFailureKind.INVALID_COORD,
                    "a coordinate needs at least two ordinates");
            return null;
        }
        CoordinateTransform ct = dir == GieDirection.FORWARD ? forward : inverse;
        if (ct == null) {
            lastFailure = GieFailures.of(GieFailureKind.NO_INVERSE,
                    "no transform available from " + target + " back to " + source);
            return null;
        }
        double x = in[0];
        double y = in[1];
        // Defaulting an absent height to 0 is FAITHFUL, not a papering-over. gie's parse_coord
        // opens with `PJ_COORD a = proj_coord(0, 0, 0, 0)` (9.8.1:src/apps/gie.cpp:820) and only
        // then overwrites v[0..dimensions_given-1]; accept() stores that whole 4D value in T.a
        // (:878) and the transform is handed `ci` derived from it unmasked (:1107, :1114).
        // dimensions_given is consulted only on the way OUT (:1115-1117) and for the temporal
        // check. So PROJ's kernel receives z = 0 for a two-number `accept`, exactly as this does;
        // .gie has no syntax for an ABSENT height, only for a zero one. That is an expressive
        // limit of the corpus, and it is why core's absent-height sentinel (a NaN src.z, see
        // BasicCoordinateTransform's noHeightIn) is unreachable from here -- correctly.
        //
        // In practice the ternary never takes its else branch: GieRunner always passes
        // GieCoord.toArray(), which is length 4 and zero-initialised (GieCoordParser.java:58),
        // mirroring proj_coord(0,0,0,0). Kept for the contract.
        double z = in.length > 2 ? in[2] : 0.0;
        double t = in.length > 3 ? in[3] : 0.0;

        ProjCoordinate src = new ProjCoordinate(x, y, z);
        ProjCoordinate dst = new ProjCoordinate();
        dst.x = Double.NaN;
        dst.y = Double.NaN;
        dst.z = Double.NaN;
        try {
            ct.transform(src, dst);
        } catch (Throwable e) {
            GieFailure f = Proj4jGieOperationFactory.mapTransformThrowable(e);
            if (f == null) {
                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                }
                throw (Error) e;
            }
            lastFailure = f;
            return null;
        }
        if (isBad(dst.x, x) || isBad(dst.y, y)) {
            lastFailure = GieFailures.of(GieFailureKind.NUMERICAL,
                    "non-finite result (" + dst.x + ", " + dst.y + ") from " + source
                            + " to " + target);
            return null;
        }
        // Report the z the transform actually produced. This used to be
        // `Double.isNaN(dst.z) ? z : dst.z`, and SingleProjectionOperation still carries that
        // expression -- correctly, for a reason that does not hold here.
        //
        // There, dst.z is poisoned to NaN and a 2D Projection never writes it, so the poison
        // survives and "NaN" genuinely means "the operator produced no height"; substituting the
        // input is then the right answer, and removing it was MEASURED to cost 5 assertions in
        // builtins.gie's `+proj=geocent +ellps=GRS80` block, off by the radius of the Earth.
        //
        // Here the poison cannot survive: BasicCoordinateTransform's first act is
        // `tgt.setValue(src)` (ProjCoordinate.java:172-176 copies z), so dst.z is always written.
        // A NaN coming out of a CRS->CRS transform is therefore never "untouched" -- it is either
        // a computed non-finite, or core's absent-height sentinel, and neither may be reported as
        // the caller's input. That is non-negotiable 3 inside the measuring instrument.
        //
        // Deliberately NOT escalated to a NUMERICAL failure the way x and y are above: gie masks
        // the third ordinate when the *expect* line gives fewer than three numbers
        // (9.8.1:src/apps/gie.cpp:1117), so for a 2D expect a NaN z is discarded by the comparator
        // and the row still passes. GieComparator reproduces that masking. Failing here instead
        // would make the harness stricter than the corpus, which manufactures failures.
        //
        // Measured at this tree state: zero assertions change outcome either way -- see the
        // liveness note on transform() above.
        return new double[] {dst.x, dst.y, dst.z, t};
    }

    private static boolean isBad(double out, double in) {
        if (!Double.isNaN(out) && !Double.isInfinite(out)) {
            return false;
        }
        return !(Double.isNaN(out) && Double.isNaN(in));
    }

    @Override
    public String toString() {
        return "CrsToCrsOperation[" + source + " -> " + target + ", left=" + left
                + ", right=" + right + "]";
    }
}

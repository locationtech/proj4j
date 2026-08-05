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

package org.locationtech.proj4j.tmerc;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.gie.GieComparator;
import org.locationtech.proj4j.proj.Projection;
import org.locationtech.proj4j.proj.TransverseMercatorProjection;

/**
 * Executes {@link TmercGieCorpus.Row}s against Proj4J using <b>gie's own metric</b>.
 *
 * <h2>The metric branch</h2>
 *
 * <p>gie picks among four ({@code 9.8.1:src/apps/gie.cpp:1120-1165}) and applying an angular
 * scale to a projected target inflates the deviation by about 111,319, so the branch is named
 * explicitly for every row:
 *
 * <ul>
 * <li><b>forward</b> — output is projected metres, so the deviation is
 *     {@link GieComparator#xyDist} (plain Euclidean);
 * <li><b>inverse</b> — output is degrees, so it is {@link GieComparator#lpDist} on the radian
 *     values, i.e. the geodesic distance on the very ellipsoid under test;
 * <li><b>roundtrip</b> — {@code proj_roundtrip} chooses by {@code proj_angular_}<i>input</i>, so a
 *     forward-direction roundtrip is measured geodesically and an inverse-direction one
 *     Euclidean.
 * </ul>
 *
 * <p>The ellipsoid the geodesic runs on is derived from the {@code operation} line's own size and
 * shape tokens; an unrecognised token set throws rather than silently defaulting, because a
 * mis-metered 50 nm row is exactly the failure this harness exists to prevent.
 *
 * <h2>{@code +approx} and {@code +algo} are applied through the setters</h2>
 *
 * <p>{@code Proj4Keyword} does not yet carry {@code approx} or {@code algo}, and the parser is
 * owned by a different change, so this runner reads the two tokens off the {@code operation} line
 * and calls {@link TransverseMercatorProjection#setApprox} /
 * {@link TransverseMercatorProjection#setAlgorithm} itself. <b>That is deliberate:</b> the
 * arithmetic and its corpus proof land independently of the plumbing, and this method body is
 * precisely the five lines the parser needs.
 */
final class TmercGieRunner {

    /** gie's pass predicate is reused verbatim, so a NaN deviation fails. */
    static final class Outcome {
        final TmercGieCorpus.Row row;
        final boolean passed;
        final String detail;

        Outcome(TmercGieCorpus.Row row, boolean passed, String detail) {
            this.row = row;
            this.passed = passed;
            this.detail = detail;
        }
    }

    private static final CRSFactory CRS = new CRSFactory();
    private static final CoordinateTransformFactory TRANSFORMS = new CoordinateTransformFactory();

    private TmercGieRunner() {
    }

    /**
     * Runs every row and returns one outcome each, in file order. Nothing is skipped: these three
     * families need no grid and no network.
     */
    static List<Outcome> runAll(List<TmercGieCorpus.Row> rows) {
        List<Outcome> outcomes = new ArrayList<Outcome>(rows.size());
        for (TmercGieCorpus.Row row : rows) {
            outcomes.add(run(row));
        }
        return outcomes;
    }

    static Outcome run(TmercGieCorpus.Row row) {
        CoordinateReferenceSystem projected;
        CoordinateReferenceSystem geographic;
        try {
            projected = projected(row.operation);
            geographic = geographic(row.operation);
        } catch (RuntimeException e) {
            // gie defers a creation failure to expect time, so a failure expectation is
            // satisfied here and a numeric one is not.
            if (row.kind == TmercGieCorpus.FAILURE) {
                return new Outcome(row, errnoMatches(row.errno, e),
                        "setup failed as expected: " + describe(e));
            }
            return new Outcome(row, false, "setup failed unexpectedly: " + describe(e));
        }

        double[] shape = metricEllipsoid(row.operation);
        GieComparator comparator = GieComparator.forEllipsoid(shape[0], shape[1]);

        if (row.kind == TmercGieCorpus.FAILURE) {
            if (!row.hasInput) {
                return new Outcome(row, false,
                        "expected the setup to fail, but the CRS was created");
            }
            try {
                transform(projected, geographic, row.inverse)
                        .transform(new ProjCoordinate(row.acceptX, row.acceptY),
                                new ProjCoordinate());
            } catch (RuntimeException e) {
                return new Outcome(row, errnoMatches(row.errno, e),
                        "threw as expected: " + describe(e));
            }
            return new Outcome(row, false, "failed to fail: the transform returned normally");
        }

        ProjCoordinate got = new ProjCoordinate();
        try {
            transform(projected, geographic, row.inverse)
                    .transform(new ProjCoordinate(row.acceptX, row.acceptY), got);
        } catch (RuntimeException e) {
            return new Outcome(row, false, "threw: " + describe(e));
        }

        double d = row.inverse
                ? angular(comparator, row.expectX, row.expectY, got.x, got.y)
                : GieComparator.xyDist(new double[] {row.expectX, row.expectY, 0, 0},
                        new double[] {got.x, got.y, 0, 0});
        String metric = row.inverse ? "geodesic on a=" + shape[0] + " f=" + shape[1] : "euclidean";
        if (!GieComparator.withinTolerance(d, row.tolerance)) {
            return new Outcome(row, false, "expected (" + row.expectX + ", " + row.expectY
                    + ") got (" + got.x + ", " + got.y + "), deviation " + d + " m (" + metric
                    + ") against " + row.tolerance + " m");
        }

        if (row.roundtrip > 0) {
            return roundtrip(row, projected, geographic, comparator, shape);
        }
        return new Outcome(row, true, "deviation " + d + " m (" + metric + ")");
    }

    /**
     * {@code proj_roundtrip} ({@code 9.8.1:src/trans.cpp:591}) with its half-step phasing copied
     * exactly: one forward half-step, then {@code n-1} full cycles, then one closing half-step.
     */
    private static Outcome roundtrip(TmercGieCorpus.Row row, CoordinateReferenceSystem projected,
            CoordinateReferenceSystem geographic, GieComparator comparator, double[] shape) {
        CoordinateTransform there = transform(projected, geographic, row.inverse);
        CoordinateTransform back = transform(projected, geographic, !row.inverse);

        ProjCoordinate t = new ProjCoordinate();
        try {
            there.transform(new ProjCoordinate(row.acceptX, row.acceptY), t);
            for (int i = 1; i < row.roundtrip; i++) {
                ProjCoordinate u = new ProjCoordinate();
                back.transform(t, u);
                t = new ProjCoordinate();
                there.transform(u, t);
            }
            ProjCoordinate closed = new ProjCoordinate();
            back.transform(t, closed);
            t = closed;
        } catch (RuntimeException e) {
            return new Outcome(row, false, "roundtrip " + row.roundtrip + " threw: " + describe(e));
        }

        // The residual metric follows the *input* units, not the output ones.
        double d = row.inverse
                ? GieComparator.xyDist(new double[] {row.acceptX, row.acceptY, 0, 0},
                        new double[] {t.x, t.y, 0, 0})
                : angular(comparator, row.acceptX, row.acceptY, t.x, t.y);
        if (!GieComparator.withinTolerance(d, row.tolerance)) {
            return new Outcome(row, false, "roundtrip " + row.roundtrip + " from ("
                    + row.acceptX + ", " + row.acceptY + ") came back (" + t.x + ", " + t.y
                    + "), deviation " + d + " m against " + row.tolerance + " m");
        }
        return new Outcome(row, true, "roundtrip " + row.roundtrip + " residual " + d + " m");
    }

    private static double angular(GieComparator comparator, double lon0, double lat0, double lon1,
            double lat1) {
        return comparator.lpDist(
                new double[] {toRad(lon0), toRad(lat0), 0, 0},
                new double[] {toRad(lon1), toRad(lat1), 0, 0});
    }

    /**
     * {@code PJ_TORAD}, not {@code Math.toRadians}: PROJ computes {@code deg * M_PI / 180}, the
     * JDK computes {@code deg / 180 * PI}. Up to 1 ulp apart, and 1 ulp of a radian is about
     * 1.4 nm on the ellipsoid — a third of the 50 nm bar these rows carry.
     */
    private static double toRad(double deg) {
        return deg * Math.PI / 180.0;
    }

    private static CoordinateTransform transform(CoordinateReferenceSystem projected,
            CoordinateReferenceSystem geographic, boolean inverse) {
        return inverse
                ? TRANSFORMS.createTransform(projected, geographic)
                : TRANSFORMS.createTransform(geographic, projected);
    }

    /** The projected side, with {@code +approx} / {@code +algo} applied through the setters. */
    static CoordinateReferenceSystem projected(String operation) {
        CoordinateReferenceSystem crs = CRS.createFromParameters("gie-proj",
                operation + " +no_defs");
        Projection p = crs.getProjection();
        if (p instanceof TransverseMercatorProjection) {
            TransverseMercatorProjection t = (TransverseMercatorProjection) p;
            if (hasFlag(operation, "approx")) {
                t.setApprox(true);
            }
            String algo = value(operation, "algo");
            if (algo != null) {
                t.setAlgorithm(algo);
            }
        } else if (hasFlag(operation, "approx") || value(operation, "algo") != null) {
            // +approx on +proj=utm currently has nowhere to go, because Registry binds utm to
            // the Poder/Engsager-only class. Say so rather than silently ignoring it.
            throw new IllegalStateException(operation + ": +approx / +algo is not settable on "
                    + p.getClass().getSimpleName() + "; Registry must bind this operation to "
                    + "TransverseMercatorProjection for the escape hatch to reach it");
        }
        return crs;
    }

    /** The geographic side: {@code +proj=longlat} plus the operation's own figure of the Earth. */
    static CoordinateReferenceSystem geographic(String operation) {
        return CRS.createFromParameters("gie-geog",
                ("+proj=longlat " + figureOfTheEarth(operation) + " +no_defs").replaceAll("\\s+",
                        " "));
    }

    private static String figureOfTheEarth(String operation) {
        StringBuilder b = new StringBuilder();
        for (String token : operation.split("\\s+")) {
            if (token.startsWith("+ellps=") || token.startsWith("+R=") || token.startsWith("+a=")
                    || token.startsWith("+b=") || token.startsWith("+rf=")
                    || token.startsWith("+f=") || token.startsWith("+datum=")
                    || token.startsWith("+es=") || token.startsWith("+e=")) {
                b.append(token).append(' ');
            }
        }
        return b.toString().trim();
    }

    /**
     * The semi-major axis and flattening the geodesic metric must use, read off the
     * {@code operation} line.
     *
     * @return {@code {a, f}}
     * @throws IllegalStateException for any token set this harness has not been taught, so that a
     *         row added upstream cannot be silently measured on the wrong ellipsoid
     */
    static double[] metricEllipsoid(String operation) {
        Double radius = number(operation, "R");
        if (radius != null) {
            return new double[] {radius.doubleValue(), 0.0};
        }
        String ellps = value(operation, "ellps");
        Double a = number(operation, "a");
        if (ellps != null) {
            if ("GRS80".equals(ellps)) {
                return new double[] {6378137.0, 1.0 / 298.257222101};
            }
            if ("WGS84".equals(ellps)) {
                return new double[] {6378137.0, 1.0 / 298.257223563};
            }
            if ("sphere".equals(ellps)) {
                return new double[] {6370997.0, 0.0};
            }
            throw new IllegalStateException(operation + ": the metric ellipsoid for +ellps="
                    + ellps + " has not been declared in this harness");
        }
        if (a != null) {
            Double rf = number(operation, "rf");
            Double f = number(operation, "f");
            Double b = number(operation, "b");
            double flattening = 0.0;
            if (rf != null) {
                flattening = 1.0 / rf.doubleValue();
            } else if (f != null) {
                flattening = f.doubleValue();
            } else if (b != null) {
                flattening = (a.doubleValue() - b.doubleValue()) / a.doubleValue();
            }
            return new double[] {a.doubleValue(), flattening};
        }
        // A bare `operation +proj=X` with no size or shape gets WGS84 (init.cpp:576-581).
        return new double[] {6378137.0, 1.0 / 298.257223563};
    }

    private static boolean hasFlag(String operation, String name) {
        for (String token : operation.split("\\s+")) {
            if (token.equals("+" + name) || token.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static String value(String operation, String name) {
        for (String token : operation.split("\\s+")) {
            if (token.startsWith("+" + name + "=")) {
                return token.substring(name.length() + 2);
            }
        }
        return null;
    }

    private static Double number(String operation, String name) {
        String v = value(operation, name);
        return v == null ? null : Double.valueOf(v);
    }

    /**
     * gie's {@code errno_from_err_const} table, restricted to the names these blocks use. An
     * unrecognised name degenerates to a bare {@code expect failure}, as upstream.
     */
    private static boolean errnoMatches(String errno, RuntimeException thrown) {
        if (errno == null) {
            return true;
        }
        ErrorCause expected = null;
        if (errno.startsWith("coord_transfm_outside_projection_domain")) {
            expected = ErrorCause.COORDINATE_OUT_OF_DOMAIN;
        } else if (errno.startsWith("coord_transfm_invalid_coord")) {
            expected = ErrorCause.INVALID_COORDINATE;
        } else if (errno.startsWith("invalid_op_illegal_arg_value")) {
            expected = ErrorCause.INVALID_PARAM_VALUE;
        }
        if (expected == null) {
            return true;
        }
        return thrown instanceof Proj4jException
                && ((Proj4jException) thrown).cause() == expected;
    }

    private static String describe(RuntimeException e) {
        String cause = e instanceof Proj4jException
                ? ((Proj4jException) e).cause().name() + " " : "";
        return cause + e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}

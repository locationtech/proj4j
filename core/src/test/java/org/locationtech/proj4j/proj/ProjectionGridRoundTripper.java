/*******************************************************************************
 * Copyright 2009, 2017 Martin Davis
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
package org.locationtech.proj4j.proj;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.util.ProjectionUtil;

/**
 * Projects a small grid of geographic coordinates into a CRS and back, and reports how far the
 * round-trip missed.
 * <p>
 * <b>{@link #gridExtent(Projection)} carried four defects that between them meant the grid was
 * frequently probed nowhere near the CRS it was supposed to be testing.</b> All four are fixed here;
 * they are documented individually at the point of fix because the first one in particular is easy to
 * reintroduce:
 * <ol>
 *   <li><b>The latitude accumulator was seeded with {@link Double#MIN_VALUE}, which is
 *       <code>+4.9e-324</code> — the smallest positive subnormal, not a negative number.</b> The
 *       "did we find anything?" guard was {@code latExtent[1] > Double.MIN_VALUE}, so for a CRS whose
 *       latitude parameters are all negative the accumulated maximum (say −19.0) failed the guard, the
 *       whole block was skipped, and the code fell back to a 10&deg; box at {@code centrey = 0.0}.
 *       <b>Every southern-hemisphere CRS was therefore probed in the wrong hemisphere</b>, typically
 *       thousands of kilometres outside its area of use, where a round-trip either happens to be
 *       harmless or fails for reasons that have nothing to do with the CRS.</li>
 *   <li><b>{@code lat == 0.0} was treated as "parameter absent"</b>, conflating it with a legitimate
 *       equatorial value.</li>
 *   <li><b>{@code gridWidth = 2 * dlat} was unbounded</b>: {@code +lat_1=-60 +lat_2=60} asks for a
 *       240&deg; box, which cannot be a meaningful probe of anything.</li>
 *   <li><b>The latitude-derived half-width was reused for longitude with no {@code cos(lat)}
 *       scaling</b>, so the box's shape on the ground depended on where it was — at 71&deg;N
 *       (Alaska zone 6) a "10&deg; square" is 3.1&times; wider than it is tall.</li>
 * </ol>
 */
public class ProjectionGridRoundTripper {

    /**
     * Largest probe box we will ever ask for, in degrees of latitude. Bounds defect (3): a box wider
     * than this is not a probe of a projection, it is a probe of the projection's failure modes, and
     * the two must not be conflated in one number.
     */
    static final double MAX_BOX_HEIGHT_DEG = 20.0;

    /** Smallest probe box, so that a degenerate {@code lat_1 == lat_2} still exercises some area. */
    static final double MIN_BOX_HEIGHT_DEG = 2.0;

    /** Box height used when the projection declares no usable latitude at all. */
    static final double DEFAULT_BOX_HEIGHT_DEG = 10.0;

    /**
     * Floor on {@code cos(centreLat)} when converting a ground-square box to degrees of longitude.
     * 0.1 corresponds to ~84&deg;; without it a polar CRS would ask for a box hundreds of degrees
     * wide.
     */
    static final double COS_LAT_FLOOR = 0.1;

    /** Keeps the box off the poles, where a lon/lat round-trip is degenerate for most projections. */
    static final double MAX_ABS_LAT_DEG = 89.0;

    private static final CoordinateTransformFactory ctFactory = new CoordinateTransformFactory();

    private final CRSFactory csFactory = new CRSFactory();

    static final String WGS84_PARAM = "+title=long/lat:WGS84 +proj=longlat +datum=WGS84 +units=degrees";

    private final CoordinateReferenceSystem WGS84 = csFactory.createFromParameters("WGS84", WGS84_PARAM);

    private final CoordinateReferenceSystem cs;
    private final CoordinateTransform transInverse;
    private final CoordinateTransform transForward;
    private int gridSize = 4;
    private boolean debug = false;
    private int transformCount = 0;
    private double[] gridExtent;
    private final List<String> failures = new ArrayList<String>();
    private double worstError = 0.0;

    public ProjectionGridRoundTripper(CoordinateReferenceSystem cs) {
        this.cs = cs;
        transInverse = ctFactory.createTransform(cs, WGS84);
        transForward = ctFactory.createTransform(WGS84, cs);
    }

    public void setLevelDebug(boolean debug) {
        this.debug = debug;
    }

    public int getTransformCount() {
        return transformCount;
    }

    public double[] getExtent() {
        return gridExtent;
    }

    /** Largest round-trip error seen, in degrees, over every point probed. */
    public double getWorstError() {
        return worstError;
    }

    /** Human-readable description of every point that missed, empty if none did. */
    public List<String> getFailures() {
        return failures;
    }

    /**
     * Runs the whole grid and returns whether every point round-tripped within {@code tolerance}
     * degrees.
     * <p>
     * Unlike the original, this does not stop at the first miss: a probe that aborts on point 1 of 25
     * cannot tell you whether a CRS is slightly off or completely broken, and that distinction is the
     * entire value of the test.
     */
    public boolean runGrid(double tolerance) {
        gridExtent = gridExtent(cs.getProjection());
        double minx = gridExtent[0];
        double miny = gridExtent[1];
        double maxx = gridExtent[2];
        double maxy = gridExtent[3];

        double dx = (maxx - minx) / gridSize;
        double dy = (maxy - miny) / gridSize;
        for (int ix = 0; ix <= gridSize; ix++) {
            for (int iy = 0; iy <= gridSize; iy++) {
                ProjCoordinate p = new ProjCoordinate(
                        ix == gridSize ? maxx : minx + ix * dx,
                        iy == gridSize ? maxy : miny + iy * dy);
                roundTrip(p, tolerance);
            }
        }
        return failures.isEmpty();
    }

    private boolean roundTrip(ProjCoordinate p, double tolerance) {
        transformCount++;

        ProjCoordinate projected = new ProjCoordinate();
        ProjCoordinate returned = new ProjCoordinate();
        transForward.transform(p, projected);
        transInverse.transform(projected, returned);

        if (debug) {
            System.out.println(ProjectionUtil.toString(p) + " -> " + ProjectionUtil.toString(projected)
                    + " ->  " + ProjectionUtil.toString(returned));
        }

        double dx = Math.abs(returned.x - p.x);
        double dy = Math.abs(returned.y - p.y);
        double err = Math.max(dx, dy);
        // NaN-safe on purpose: a non-finite round-trip must be a failure, not a comparison that
        // quietly evaluates to false.
        if (!(err <= worstError)) {
            worstError = err;
        }

        boolean isInTol = dx <= tolerance && dy <= tolerance;
        if (!isInTol) {
            failures.add(ProjectionUtil.toString(p) + " -> " + ProjectionUtil.toString(projected)
                    + " -> " + ProjectionUtil.toString(returned)
                    + "  (dLon " + dx + ", dLat " + dy + ")");
        }
        return isInTol;
    }

    /**
     * Chooses a lon/lat box to probe, from whatever the projection declares about its own origin.
     * <p>
     * Returned as {@code { minLon, minLat, maxLon, maxLat }}.
     * <p>
     * <b>Note on "absent" parameters.</b> {@link Projection} offers no absent-sentinel:
     * {@code projectionLatitude}, {@code projectionLatitude1} and {@code projectionLatitude2} are all
     * plain {@code double}s initialised to {@code 0.0} ({@code Projection.java:66,76,81}), so a CRS
     * that genuinely sits on the equator is indistinguishable from one that declares no latitude.
     * The original code responded by dropping every {@code 0.0} inside {@code updateLat}, which is
     * defect (2). This version instead applies an explicit precedence rule and never discards a value
     * from the arithmetic — {@code 0.0} is only ever used to <em>select</em> which rule applies, and
     * that limitation is stated rather than hidden. Giving {@code Projection} a NaN default would
     * remove the ambiguity outright, but that is a main-source change.
     */
    public static double[] gridExtent(Projection proj) {
        double lat0 = proj.getProjectionLatitudeDegrees();
        double lat1 = proj.getProjectionLatitude1Degrees();
        double lat2 = proj.getProjectionLatitude2Degrees();
        double lon0 = proj.getProjectionLongitudeDegrees();

        boolean haveStandardParallels = lat1 != 0.0 || lat2 != 0.0;
        boolean haveBothStandardParallels = lat1 != 0.0 && lat2 != 0.0;

        // ---- centre latitude -------------------------------------------------------------------
        // Defect (1): the old accumulator was seeded { Double.MAX_VALUE, Double.MIN_VALUE } and
        // Double.MIN_VALUE is +4.9e-324. There is no accumulator here at all, so the sign of the
        // hemisphere cannot be lost. If a min/max is ever reintroduced, seed the maximum with
        // -Double.MAX_VALUE or Double.NEGATIVE_INFINITY -- never Double.MIN_VALUE.
        double centreLat;
        if (lat0 != 0.0) {
            centreLat = lat0;
        } else if (haveStandardParallels) {
            centreLat = 0.5 * (lat1 + lat2);
        } else {
            centreLat = 0.0;
        }
        centreLat = clamp(centreLat, -MAX_ABS_LAT_DEG, MAX_ABS_LAT_DEG);

        // ---- box height ------------------------------------------------------------------------
        // Defect (3): 2 * dlat, unbounded. +lat_1=-60 +lat_2=60 gave a 240-degree box.
        double heightDeg = DEFAULT_BOX_HEIGHT_DEG;
        if (haveBothStandardParallels) {
            double dlat = Math.abs(lat2 - lat1);
            if (dlat > 0.0) {
                heightDeg = clamp(2.0 * dlat, MIN_BOX_HEIGHT_DEG, MAX_BOX_HEIGHT_DEG);
            }
        }

        // ---- box width -------------------------------------------------------------------------
        // Defect (4): the latitude half-width was reused verbatim for longitude, so the box's
        // proportions on the ground varied with latitude. One degree of longitude spans
        // cos(lat) degrees' worth of ground, so a ground-square box needs height / cos(lat) degrees
        // of longitude -- floored near the poles and capped for the same reason as the height.
        double cosLat = Math.max(Math.cos(Math.toRadians(centreLat)), COS_LAT_FLOOR);
        double widthDeg = clamp(heightDeg / cosLat, MIN_BOX_HEIGHT_DEG, MAX_BOX_HEIGHT_DEG);

        // ---- assemble, keeping the box on the globe --------------------------------------------
        double halfH = 0.5 * heightDeg;
        double halfW = 0.5 * widthDeg;
        double minLat = centreLat - halfH;
        double maxLat = centreLat + halfH;
        if (minLat < -MAX_ABS_LAT_DEG) {
            double shift = -MAX_ABS_LAT_DEG - minLat;
            minLat += shift;
            maxLat += shift;
        } else if (maxLat > MAX_ABS_LAT_DEG) {
            double shift = maxLat - MAX_ABS_LAT_DEG;
            minLat -= shift;
            maxLat -= shift;
        }

        double centreLon = lon0;
        double minLon = centreLon - halfW;
        double maxLon = centreLon + halfW;
        if (minLon < -180.0) {
            double shift = -180.0 - minLon;
            minLon += shift;
            maxLon += shift;
        } else if (maxLon > 180.0) {
            double shift = maxLon - 180.0;
            minLon -= shift;
            maxLon -= shift;
        }

        return new double[] { minLon, minLat, maxLon, maxLat };
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

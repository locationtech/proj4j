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

import java.util.List;
import java.util.Map;

/**
 * A parsed {@code triangulation_file}, and the barycentric evaluator over it —
 * {@code 9.8.1:src/transformations/tinshift_impl.hpp}'s {@code TINShiftFile} and
 * {@code Evaluator} in one class.
 *
 * <h2>The data layout, which the whole file depends on</h2>
 *
 * <p>Vertices are flattened into one {@code double[]} of {@code columnCount} values
 * each, and <b>{@code columnCount} is derived, not read</b>
 * ({@code tinshift_impl.hpp:265-269}):
 *
 * <pre>
 * 2                       source_x, source_y
 * + 2 if horizontal       target_x, target_y
 * + 1 if vertical         the z OFFSET
 * </pre>
 *
 * <p>So the interior column indices are fixed at 0,1 (source), 2,3 (target) and
 * either 2 or 4 (z offset) regardless of what order {@code vertices_columns} declared
 * them in — the declared order is used once, while reading, to pick values out of each
 * input row.
 *
 * <p>The z column is always an <b>offset</b>, even when the file declares
 * {@code source_z} and {@code target_z} instead of {@code offset_z}: those two are
 * differenced on the way in ({@code targetZ - sourceZ}). Storing them separately and
 * differencing at evaluation time would give the same answer for a single vertex but a
 * different one after interpolation, because the barycentric weights would then be
 * applied to two rounded quantities instead of one.
 *
 * <h2>Point location: a linear scan, deliberately</h2>
 *
 * <p>Upstream indexes triangles in a quadtree and iterates only the ones whose
 * bounding box contains the query point. The quadtree is a <em>pre-filter</em>: every
 * candidate still goes through the same barycentric test, and a triangle the quadtree
 * excludes cannot contain the point. So a linear scan returns the same triangle, and it
 * is what {@code FindTriangle} itself does when {@code USE_QUADTREE} is not defined —
 * upstream keeps both paths in the file.
 *
 * <p>One consequence is worth stating rather than discovering: the two scans visit
 * candidates in a different <em>order</em>, so for a point lying inside two triangles
 * within {@code EPS} — i.e. on a shared edge — the triangle chosen may differ. Since
 * both triangles interpolate to the same value along their shared edge to within
 * {@code EPS}, this cannot change an answer by more than the tolerance. Indexing is a
 * performance question and belongs with the rest of the compiled-pipeline work.
 *
 * <h2>{@code EPS} is 1e-10 and it is not a distance</h2>
 *
 * <p>The containment test is on the <em>barycentric</em> coordinates:
 * {@code lambda1} and {@code lambda2} each in {@code [-EPS, 1+EPS]} and
 * {@code lambda3 = 1 - lambda1 - lambda2} at least {@code 0} — note the third has
 * <b>no</b> {@code EPS} slack, which is upstream's asymmetry and is preserved.
 *
 * <h2>Fallback strategies</h2>
 *
 * <p>{@code fallback_strategy} requires {@code format_version} exactly {@code "1.1"};
 * declaring one under {@code 1.0} is a parse error, not a warning. When no triangle
 * contains the point and a strategy is set, the <em>nearest</em> triangle is used and
 * its barycentric coordinates are extrapolated — so {@code lambda} values outside
 * {@code [0,1]} are expected on that path, and the result is an extrapolation rather
 * than an interpolation. {@code nearest_side} measures point-to-segment distance to all
 * three sides (upstream's comment explains why all three: the winding order is
 * unknown); {@code nearest_centroid} measures to the centroid. Degenerate triangles are
 * skipped.
 *
 * <p>The AABB rejection inside the fallback loop
 * ({@code tinshift_impl.hpp:495-501}) uses {@code closest_dist}, the <em>square
 * root</em> of the best squared distance so far, against un-squared coordinate ranges.
 * That is correct and is transcribed as written.
 *
 * <p>Immutable after construction; the evaluator keeps all state in locals, so one
 * instance may be shared.
 *
 * @since 1.5
 */
final class Triangulation {

    /** {@code constexpr double EPS = 1e-10}. */
    private static final double EPS = 1e-10;

    /** {@code FALLBACK_NONE}: a point outside every triangle is an error. */
    static final int FALLBACK_NONE = 0;

    /** {@code FALLBACK_NEAREST_SIDE}. */
    static final int FALLBACK_NEAREST_SIDE = 1;

    /** {@code FALLBACK_NEAREST_CENTROID}. */
    static final int FALLBACK_NEAREST_CENTROID = 2;

    private final boolean horizontal;
    private final boolean vertical;
    private final int columnCount;
    private final double[] vertices;
    private final int[] triangles;
    private final int fallbackStrategy;
    private final String inputCrs;
    private final String outputCrs;

    private Triangulation(final boolean horizontal, final boolean vertical,
                          final int columnCount, final double[] vertices,
                          final int[] triangles, final int fallbackStrategy,
                          final String inputCrs, final String outputCrs) {
        this.horizontal = horizontal;
        this.vertical = vertical;
        this.columnCount = columnCount;
        this.vertices = vertices;
        this.triangles = triangles;
        this.fallbackStrategy = fallbackStrategy;
        this.inputCrs = inputCrs;
        this.outputCrs = outputCrs;
    }

    /** @return whether {@code transformed_components} contained {@code "horizontal"}. */
    boolean transformsHorizontal() {
        return horizontal;
    }

    /** @return whether {@code transformed_components} contained {@code "vertical"}. */
    boolean transformsVertical() {
        return vertical;
    }

    /** @return {@code input_crs}, or the empty string. Informational; never acted on. */
    String inputCrs() {
        return inputCrs;
    }

    /** @return {@code output_crs}, or the empty string. Informational; never acted on. */
    String outputCrs() {
        return outputCrs;
    }

    /** @return one of the three {@code FALLBACK_*} constants. */
    int fallbackStrategy() {
        return fallbackStrategy;
    }

    /** @return the number of triangles. */
    int triangleCount() {
        return triangles.length / 3;
    }

    // ------------------------------------------------------------------- parsing

    /**
     * {@code TINShiftFile::parse} ({@code tinshift_impl.hpp:78-378}).
     *
     * @param text the JSON document
     * @return the parsed triangulation
     * @throws PipelineDefinitionException {@code FILE_NOT_FOUND_OR_INVALID} on any
     *                                     structural problem, which is the single error
     *                                     PROJ raises for a model it cannot read
     */
    static Triangulation parse(final String text) {
        final Map<String, Object> j =
                PipelineJson.asObject(PipelineJson.parse(text), "the model");

        final String fileType = PipelineJson.requiredString(j, "file_type");
        final String formatVersion = PipelineJson.requiredString(j, "format_version");
        // Upstream reads file_type without checking it, so a "deformation_model_master_file"
        // reaches the triangulation reader and fails on a missing "vertices_columns". The
        // check is added here only as a better message; the outcome is the same error.
        if (!"triangulation_file".equals(fileType)) {
            throw PipelineJson.invalid("file_type is \"" + fileType
                    + "\", not \"triangulation_file\"");
        }

        int fallback = FALLBACK_NONE;
        if (j.containsKey("fallback_strategy")) {
            if (!"1.1".equals(formatVersion)) {
                throw PipelineJson.invalid("fallback_strategy needs format_version 1.1");
            }
            final String name = PipelineJson.optionalString(j, "fallback_strategy");
            if ("nearest_side".equals(name)) {
                fallback = FALLBACK_NEAREST_SIDE;
            } else if ("nearest_centroid".equals(name)) {
                fallback = FALLBACK_NEAREST_CENTROID;
            } else if (!"none".equals(name)) {
                throw PipelineJson.invalid("invalid fallback_strategy");
            }
        }

        final String inputCrs = PipelineJson.optionalString(j, "input_crs");
        final String outputCrs = PipelineJson.optionalString(j, "output_crs");

        boolean horizontal = false;
        boolean vertical = false;
        final List<Object> components = PipelineJson.requiredArray(j, "transformed_components");
        for (int i = 0; i < components.size(); i++) {
            final Object c = components.get(i);
            if (!(c instanceof String)) {
                throw PipelineJson.invalid("transformed_components[] item is not a string");
            }
            if ("horizontal".equals(c)) {
                horizontal = true;
            } else if ("vertical".equals(c)) {
                vertical = true;
            } else {
                throw PipelineJson.invalid("transformed_components[] = " + c
                        + " is not handled");
            }
        }

        final List<Object> verticesColumns = PipelineJson.requiredArray(j, "vertices_columns");
        int sourceXCol = -1;
        int sourceYCol = -1;
        int sourceZCol = -1;
        int targetXCol = -1;
        int targetYCol = -1;
        int targetZCol = -1;
        int offsetZCol = -1;
        for (int i = 0; i < verticesColumns.size(); i++) {
            final Object c = verticesColumns.get(i);
            if (!(c instanceof String)) {
                throw PipelineJson.invalid("vertices_columns[] item is not a string");
            }
            final String name = (String) c;
            if ("source_x".equals(name)) {
                sourceXCol = i;
            } else if ("source_y".equals(name)) {
                sourceYCol = i;
            } else if ("source_z".equals(name)) {
                sourceZCol = i;
            } else if ("target_x".equals(name)) {
                targetXCol = i;
            } else if ("target_y".equals(name)) {
                targetYCol = i;
            } else if ("target_z".equals(name)) {
                targetZCol = i;
            } else if ("offset_z".equals(name)) {
                offsetZCol = i;
            }
        }
        if (sourceXCol < 0) {
            throw PipelineJson.invalid("source_x must be specified in vertices_columns[]");
        }
        if (sourceYCol < 0) {
            throw PipelineJson.invalid("source_y must be specified in vertices_columns[]");
        }
        if (horizontal && targetXCol < 0) {
            throw PipelineJson.invalid("target_x must be specified in vertices_columns[]");
        }
        if (horizontal && targetYCol < 0) {
            throw PipelineJson.invalid("target_y must be specified in vertices_columns[]");
        }
        if (vertical && offsetZCol < 0) {
            if (sourceZCol < 0) {
                throw PipelineJson.invalid(
                        "source_z or delta_z must be specified in vertices_columns[]");
            }
            if (targetZCol < 0) {
                throw PipelineJson.invalid("target_z must be specified in vertices_columns[]");
            }
        }

        final List<Object> trianglesColumns = PipelineJson.requiredArray(j, "triangles_columns");
        int idx1Col = -1;
        int idx2Col = -1;
        int idx3Col = -1;
        for (int i = 0; i < trianglesColumns.size(); i++) {
            final Object c = trianglesColumns.get(i);
            if (!(c instanceof String)) {
                throw PipelineJson.invalid("triangles_columns[] item is not a string");
            }
            if ("idx_vertex1".equals(c)) {
                idx1Col = i;
            } else if ("idx_vertex2".equals(c)) {
                idx2Col = i;
            } else if ("idx_vertex3".equals(c)) {
                idx3Col = i;
            }
        }
        if (idx1Col < 0) {
            throw PipelineJson.invalid("idx_vertex1 must be specified in triangles_columns[]");
        }
        if (idx2Col < 0) {
            throw PipelineJson.invalid("idx_vertex2 must be specified in triangles_columns[]");
        }
        if (idx3Col < 0) {
            throw PipelineJson.invalid("idx_vertex3 must be specified in triangles_columns[]");
        }

        int columnCount = 2;
        if (horizontal) {
            columnCount += 2;
        }
        if (vertical) {
            columnCount += 1;
        }

        final List<Object> jVertices = PipelineJson.requiredArray(j, "vertices");
        final double[] flat = new double[columnCount * jVertices.size()];
        int at = 0;
        for (int v = 0; v < jVertices.size(); v++) {
            final List<Object> row = PipelineJson.asArray(jVertices.get(v), "vertices[] item");
            if (row.size() != verticesColumns.size()) {
                throw PipelineJson.invalid(
                        "vertices[] item has not expected number of elements");
            }
            flat[at++] = PipelineJson.asNumber(row.get(sourceXCol), "vertices[][] item");
            flat[at++] = PipelineJson.asNumber(row.get(sourceYCol), "vertices[][] item");
            if (horizontal) {
                flat[at++] = PipelineJson.asNumber(row.get(targetXCol), "vertices[][] item");
                flat[at++] = PipelineJson.asNumber(row.get(targetYCol), "vertices[][] item");
            }
            if (vertical) {
                if (offsetZCol >= 0) {
                    flat[at++] = PipelineJson.asNumber(row.get(offsetZCol), "vertices[][] item");
                } else {
                    final double sourceZ =
                            PipelineJson.asNumber(row.get(sourceZCol), "vertices[][] item");
                    final double targetZ =
                            PipelineJson.asNumber(row.get(targetZCol), "vertices[][] item");
                    flat[at++] = targetZ - sourceZ;
                }
            }
        }

        final List<Object> jTriangles = PipelineJson.requiredArray(j, "triangles");
        final int[] tri = new int[3 * jTriangles.size()];
        int t = 0;
        for (int i = 0; i < jTriangles.size(); i++) {
            final List<Object> row = PipelineJson.asArray(jTriangles.get(i), "triangles[] item");
            if (row.size() != trianglesColumns.size()) {
                throw PipelineJson.invalid(
                        "triangles[] item has not expected number of elements");
            }
            tri[t++] = vertexIndex(row.get(idx1Col), jVertices.size());
            tri[t++] = vertexIndex(row.get(idx2Col), jVertices.size());
            tri[t++] = vertexIndex(row.get(idx3Col), jVertices.size());
        }

        return new Triangulation(horizontal, vertical, columnCount, flat, tri, fallback,
                inputCrs, outputCrs);
    }

    private static int vertexIndex(final Object node, final int vertexCount) {
        final int idx = PipelineJson.asIndex(node, "triangles[][] item");
        if (idx >= vertexCount) {
            throw PipelineJson.invalid("Invalid value for a vertex index");
        }
        return idx;
    }

    // ---------------------------------------------------------------- evaluation

    /**
     * {@code Evaluator::forward}.
     *
     * @param coord {@code {x, y, z, t}}, mutated in place
     * @return whether a triangle was found; {@code false} is upstream's
     *         {@code proj_coord_error()}
     */
    boolean forward(final double[] coord) {
        final double[] lambda = new double[3];
        final int triangle = findTriangle(coord[0], coord[1], true, lambda);
        if (triangle < 0) {
            return false;
        }
        apply(coord, triangle, lambda, true);
        return true;
    }

    /**
     * {@code Evaluator::inverse}.
     *
     * @param coord {@code {x, y, z, t}}, mutated in place
     * @return whether a triangle was found
     */
    boolean inverse(final double[] coord) {
        final double[] lambda = new double[3];
        // Evaluator::inverse's own subtlety: a vertical-only file has no target x/y
        // columns, so the point is located in the SOURCE geometry even on the inverse
        // path (it reuses mQuadTreeForward). Locating it in the target geometry would
        // index columns 2 and 3, which for such a file hold the z offset and nothing.
        final boolean locateInSource = !horizontal && vertical;
        final int triangle = findTriangle(coord[0], coord[1], locateInSource, lambda);
        if (triangle < 0) {
            return false;
        }
        apply(coord, triangle, lambda, false);
        return true;
    }

    private void apply(final double[] coord, final int triangle, final double[] lambda,
                       final boolean forward) {
        final int i1 = triangles[3 * triangle] * columnCount;
        final int i2 = triangles[3 * triangle + 1] * columnCount;
        final int i3 = triangles[3 * triangle + 2] * columnCount;
        if (horizontal) {
            // Forward reads the target columns, inverse the source columns.
            final int cx = forward ? 2 : 0;
            final int cy = forward ? 3 : 1;
            coord[0] = vertices[i1 + cx] * lambda[0] + vertices[i2 + cx] * lambda[1]
                    + vertices[i3 + cx] * lambda[2];
            coord[1] = vertices[i1 + cy] * lambda[0] + vertices[i2 + cy] * lambda[1]
                    + vertices[i3 + cy] * lambda[2];
        }
        if (vertical) {
            final int cz = horizontal ? 4 : 2;
            final double offset = vertices[i1 + cz] * lambda[0]
                    + vertices[i2 + cz] * lambda[1] + vertices[i3 + cz] * lambda[2];
            coord[2] = forward ? coord[2] + offset : coord[2] - offset;
        }
    }

    /**
     * {@code FindTriangle} ({@code tinshift_impl.hpp:427-560}).
     *
     * @param x        the query abscissa
     * @param y        the query ordinate
     * @param source   locate in the source geometry (columns 0,1) rather than the target
     *                 (columns 2,3)
     * @param lambda   filled with the three barycentric coordinates
     * @return the triangle index, or {@code -1}
     */
    private int findTriangle(final double x, final double y, final boolean source,
                             final double[] lambda) {
        final int idxX = horizontal && !source ? 2 : 0;
        final int idxY = horizontal && !source ? 3 : 1;
        final int n = triangleCount();

        for (int i = 0; i < n; i++) {
            final int i1 = triangles[3 * i] * columnCount;
            final int i2 = triangles[3 * i + 1] * columnCount;
            final int i3 = triangles[3 * i + 2] * columnCount;
            final double x1 = vertices[i1 + idxX];
            final double y1 = vertices[i1 + idxY];
            final double x2 = vertices[i2 + idxX];
            final double y2 = vertices[i2 + idxY];
            final double x3 = vertices[i3 + idxX];
            final double y3 = vertices[i3 + idxY];
            final double detT = (y2 - y3) * (x1 - x3) + (x3 - x2) * (y1 - y3);
            final double l1 = ((y2 - y3) * (x - x3) + (x3 - x2) * (y - y3)) / detT;
            final double l2 = ((y3 - y1) * (x - x3) + (x1 - x3) * (y - y3)) / detT;
            if (l1 >= -EPS && l1 <= 1 + EPS && l2 >= -EPS && l2 <= 1 + EPS) {
                final double l3 = 1 - l1 - l2;
                // No EPS on lambda3. Upstream's asymmetry, preserved.
                if (l3 >= 0) {
                    lambda[0] = l1;
                    lambda[1] = l2;
                    lambda[2] = l3;
                    return i;
                }
            }
        }

        if (fallbackStrategy == FALLBACK_NONE) {
            return -1;
        }
        return nearestTriangle(x, y, idxX, idxY, lambda);
    }

    /** The {@code fallback_strategy} search, then extrapolation from the winner. */
    private int nearestTriangle(final double x, final double y, final int idxX, final int idxY,
                                final double[] lambda) {
        double closestDist = Double.POSITIVE_INFINITY;
        double closestDist2 = Double.POSITIVE_INFINITY;
        int closest = 0;
        final int n = triangleCount();

        for (int i = 0; i < n; i++) {
            final int i1 = triangles[3 * i] * columnCount;
            final int i2 = triangles[3 * i + 1] * columnCount;
            final int i3 = triangles[3 * i + 2] * columnCount;
            final double x1 = vertices[i1 + idxX];
            final double y1 = vertices[i1 + idxY];
            final double x2 = vertices[i2 + idxX];
            final double y2 = vertices[i2 + idxY];
            final double x3 = vertices[i3 + idxX];
            final double y3 = vertices[i3 + idxY];

            // AABB rejection against the best distance so far. Note closestDist is the
            // root of closestDist2, compared against un-squared ranges: correct, and
            // transcribed as upstream writes it.
            if (x + closestDist < Math.min(x1, Math.min(x2, x3))
                    || x - closestDist > Math.max(x1, Math.max(x2, x3))
                    || y + closestDist < Math.min(y1, Math.min(y2, y3))
                    || y - closestDist > Math.max(y1, Math.max(y2, y3))) {
                continue;
            }

            final double dist12 = squaredDistance(x1, y1, x2, y2);
            final double dist23 = squaredDistance(x2, y2, x3, y3);
            final double dist13 = squaredDistance(x1, y1, x3, y3);
            if (dist12 < EPS || dist23 < EPS || dist13 < EPS) {
                continue;
            }

            if (fallbackStrategy == FALLBACK_NEAREST_SIDE) {
                // All three sides: the winding order of the vertices is unknown, so
                // there is no "the" nearest side to pick a priori.
                double d2 = distanceToSegment(x, y, x1, y1, x2, y2, dist12);
                if (d2 < closestDist2) {
                    closestDist2 = d2;
                    closestDist = Math.sqrt(d2);
                    closest = i;
                }
                d2 = distanceToSegment(x, y, x2, y2, x3, y3, dist23);
                if (d2 < closestDist2) {
                    closestDist2 = d2;
                    closestDist = Math.sqrt(d2);
                    closest = i;
                }
                d2 = distanceToSegment(x, y, x1, y1, x3, y3, dist13);
                if (d2 < closestDist2) {
                    closestDist2 = d2;
                    closestDist = Math.sqrt(d2);
                    closest = i;
                }
            } else {
                final double cx = (x1 + x2 + x3) / 3.0;
                final double cy = (y1 + y2 + y3) / 3.0;
                final double d2 = squaredDistance(x, y, cx, cy);
                if (d2 < closestDist2) {
                    closestDist2 = d2;
                    closestDist = Math.sqrt(d2);
                    closest = i;
                }
            }
        }

        if (Double.isInfinite(closestDist)) {
            // An empty triangle list, or only degenerate triangles.
            return -1;
        }

        final int i1 = triangles[3 * closest] * columnCount;
        final int i2 = triangles[3 * closest + 1] * columnCount;
        final int i3 = triangles[3 * closest + 2] * columnCount;
        final double x1 = vertices[i1 + idxX];
        final double y1 = vertices[i1 + idxY];
        final double x2 = vertices[i2 + idxX];
        final double y2 = vertices[i2 + idxY];
        final double x3 = vertices[i3 + idxX];
        final double y3 = vertices[i3 + idxY];
        final double detT = (y2 - y3) * (x1 - x3) + (x3 - x2) * (y1 - y3);
        if (Math.abs(detT) < EPS) {
            return -1;
        }
        lambda[0] = ((y2 - y3) * (x - x3) + (x3 - x2) * (y - y3)) / detT;
        lambda[1] = ((y3 - y1) * (x - x3) + (x1 - x3) * (y - y3)) / detT;
        lambda[2] = 1 - lambda[0] - lambda[1];
        return closest;
    }

    private static double squaredDistance(final double x1, final double y1,
                                          final double x2, final double y2) {
        final double dx = x1 - x2;
        final double dy = y1 - y2;
        return dx * dx + dy * dy;
    }

    /** {@code distance_point_segment}: squared distance, with {@code dist12} pre-squared. */
    private static double distanceToSegment(final double x, final double y,
                                            final double x1, final double y1,
                                            final double x2, final double y2,
                                            final double dist12) {
        final double t = ((x - x1) * (x2 - x1) + (y - y1) * (y2 - y1)) / dist12;
        if (t <= 0.0) {
            return squaredDistance(x, y, x1, y1);
        }
        if (t >= 1.0) {
            return squaredDistance(x, y, x2, y2);
        }
        return squaredDistance(x, y, x1 + t * (x2 - x1), y1 + t * (y2 - y1));
    }

    @Override
    public String toString() {
        return "Triangulation[" + (vertices.length / columnCount) + " vertices, "
                + triangleCount() + " triangles, horizontal=" + horizontal
                + ", vertical=" + vertical + ", fallback=" + fallbackStrategy + "]";
    }
}

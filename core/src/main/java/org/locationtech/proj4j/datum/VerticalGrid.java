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
 *******************************************************************************/
package org.locationtech.proj4j.datum;

import java.io.IOException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.locationtech.proj4j.datum.tiff.GeoTiffDataset;
import org.locationtech.proj4j.resource.ChainedResourceResolver;
import org.locationtech.proj4j.resource.ResourceHandle;
import org.locationtech.proj4j.resource.ResourceNames;
import org.locationtech.proj4j.resource.ResourceResolver;
import org.locationtech.proj4j.resource.ResourceResolvers;
import org.locationtech.proj4j.resource.Resources;

/**
 * A vertical shift grid: a geoid model or height correction surface, read from a GTX file.
 *
 * <p>This is what {@code +geoidgrids=} needs. It is deliberately independent of {@link Grid}: a
 * vertical grid holds one scalar per node, not a (&Delta;&lambda;, &Delta;&phi;) pair, and PROJ models
 * them as separate class hierarchies ({@code VerticalShiftGrid} vs {@code HorizontalShiftGrid}) for the
 * same reason.
 *
 * <h2>GTX</h2>
 * <p>GTX is the simplest grid format PROJ supports and has <strong>no magic bytes</strong>: a 40-byte
 * big-endian header (south latitude, west longitude, latitude step, longitude step, all
 * {@code double} degrees, then row and column counts as {@code int}), followed by
 * {@code rows * columns} big-endian {@code float} values ordered south to north, west to east.
 * Because there is nothing to sniff, PROJ 9.8.1 dispatches on the {@code .gtx}/{@code .GTX} filename
 * suffix ({@code src/grids.cpp}, {@code VerticalShiftGridSet::open}) and so does this class. A file
 * with the wrong suffix is rejected rather than guessed at.
 *
 * <h2>Interpolation and nodata</h2>
 * <p>{@link #valueAt} is a verbatim port of PROJ 9.8.1's {@code read_vgrid_value}, including its
 * clamping of the upper index at the grid edge, its full-world longitude wrap, and its four-node
 * nodata handling. The weight expressions and their summation order are preserved verbatim, because
 * changing the order changes the last bits of the result.
 *
 * <h2>GeoTIFF</h2>
 * <p>A vertical GeoTIFF is read by {@link org.locationtech.proj4j.datum.tiff} and assembled here, so
 * that the interpolation above is shared rather than duplicated — PROJ shares it the same way, through
 * the virtual {@code VerticalShiftGrid::valueAt}. Two things differ from GTX and both are carried per
 * grid rather than hardcoded:
 * <ul>
 *   <li><strong>nodata.</strong> GTX has the {@code -88.8888} sentinel plus a magnitude heuristic;
 *       GeoTIFF has {@code GTiffGrid::isNodata}, which is <em>only</em> the declared
 *       {@code GDAL_NODATA} value, plus NaN. Applying the GTX heuristic to a GeoTIFF would discard
 *       legitimate values beyond &plusmn;1000 m, and applying the GeoTIFF rule to a GTX would miss
 *       {@code naptrans2008.gtx}'s sentinels. See {@link #isNodata}.</li>
 *   <li><strong>subgrids.</strong> A GeoTIFF can nest grids across IFDs, which GTX cannot. The tree is
 *       held on {@link #children} and resolved by {@link #gridAt} before interpolating, exactly as
 *       {@code VerticalShiftGridSet::gridAt} then {@code read_vgrid_value}.</li>
 * </ul>
 */
public final class VerticalGrid implements Serializable, GridCache.Sized {

    private static final long serialVersionUID = 1L;

    /** GTX header size, per PROJ's {@code GTXVerticalShiftGrid}. */
    private static final int GTX_HEADER_BYTES = 40;

    /** PROJ's {@code REL_TOLERANCE_HGRIDSHIFT}, reused for extent containment. */
    private static final double REL_TOLERANCE = 1e-5;

    private static final double DEG_TO_RAD = Math.PI / 180.0;

    private static final long BYTES_PER_NODE = 4L;

    private final String gridName;
    private final String origin;
    private final String resolverName;
    private final String format;

    private final int width;
    private final int height;
    /** Radians. */
    private final double west;
    private final double south;
    private final double east;
    private final double north;
    private final double resX;
    private final double resY;

    /** Row-major, south to north, west to east. {@code null} only for a synthetic bounding root. */
    private final float[] values;

    /** Nested subgrids, finest last. Always empty for GTX, which has no notion of them. */
    private final List<VerticalGrid> children;

    /** {@code true} to use {@code GTiffGrid::isNodata} instead of the GTX rule. */
    private final boolean tiffNodataRule;
    private final boolean hasNodataValue;
    private final float nodataValue;

    private VerticalGrid(String gridName, String origin, String resolverName, String format,
                         int width, int height, double west, double south, double resX, double resY,
                         float[] values) {
        this(gridName, origin, resolverName, format, width, height, west, south, resX, resY, values,
                Collections.<VerticalGrid>emptyList(), false, false, 0f);
    }

    private VerticalGrid(String gridName, String origin, String resolverName, String format,
                         int width, int height, double west, double south, double resX, double resY,
                         float[] values, List<VerticalGrid> children, boolean tiffNodataRule,
                         boolean hasNodataValue, float nodataValue) {
        this.gridName = gridName;
        this.origin = origin;
        this.resolverName = resolverName;
        this.format = format;
        this.width = width;
        this.height = height;
        this.west = west;
        this.south = south;
        this.resX = resX;
        this.resY = resY;
        this.east = west + resX * (width - 1);
        this.north = south + resY * (height - 1);
        this.values = values;
        this.children = children;
        this.tiffNodataRule = tiffNodataRule;
        this.hasNodataValue = hasNodataValue;
        this.nodataValue = nodataValue;
    }

    /**
     * Resolves and parses a vertical grid by name through the same deterministic chain and the same
     * byte-bounded cache as horizontal grids. The working directory is never consulted.
     */
    public static VerticalGrid fromName(String name) throws IOException {
        if (name == null || name.isEmpty()) {
            throw new IOException("Empty vertical grid name");
        }
        // Same pre-chain refusal, and the same rule, as Grid.resolveAndLoad. See ResourceNames.
        ResourceNames.Rule violation = ResourceNames.violation(name);
        if (violation != null) {
            throw new IOException("Refusing vertical grid name \"" + name + "\": "
                    + violation.description() + " (" + violation + ")");
        }
        ChainedResourceResolver chain = ResourceResolvers.resolver();
        final ResourceHandle handle = chain.resolve(name);
        if (handle == null) {
            throw new IOException("Unknown vertical grid: " + name + ". Resolution chain was "
                    + chain.name() + "; the working directory is deliberately not searched.");
        }
        ResourceResolver owner = chain.resolverOf(name);
        final String resolverName = owner == null ? "unknown" : owner.name();
        final String requested = name;
        final String origin = handle.origin();
        return GridCache.vertical().get(resolverName, name, new GridCache.Loader<VerticalGrid>() {
            @Override
            public VerticalGrid load() throws IOException {
                // GridExtents.maxFileBytes(), i.e. proj4j.grids.maxFileBytes, default 128 MiB --
                // the same ceiling Grid.resolveAndLoad applies. This was a fourth, hardcoded
                // 512 MiB literal, so the documented "one knob" for how large a grid file may be
                // did not govern half the grids: a +geoidgrids= token could pull four times what a
                // +nadgrids= token could, for no stated reason and with no way to lower it. The
                // largest vertical grid PROJ publishes is three orders of magnitude below either
                // number, so unifying downwards cannot refuse a real geoid.
                byte[] bytes = Resources.readAll(handle, GridExtents.maxFileBytes());
                return parse(requested, origin, resolverName, bytes);
            }
        });
    }

    /**
     * Parses a {@code +geoidgrids=} style list, honouring PROJ's {@code @} optional prefix with the
     * same silent-skip semantics {@link Grid#fromNadGrids} has, in the same order.
     */
    public static List<VerticalGrid> fromGeoidGrids(String spec) throws IOException {
        List<VerticalGrid> out = new ArrayList<VerticalGrid>();
        if (spec == null || spec.isEmpty()) {
            return out;
        }
        for (String token : Grid.splitTokens("geoidgrids", spec)) {
            boolean optional = token.startsWith("@");
            String name = optional ? token.substring(1) : token;
            try {
                out.add(fromName(name));
            } catch (IOException e) {
                if (!optional) {
                    throw e;
                }
            }
        }
        return out;
    }

    /**
     * Sniffs the format and parses, in {@code VerticalShiftGridSet::open}'s order
     * ({@code 9.8.1:src/grids.cpp:1613-1671}): the {@code .gtx}/{@code .GTX} filename suffix first —
     * GTX has no magic bytes, so upstream dispatches on the name too — then the TIFF signature, then
     * an error.
     */
    static VerticalGrid parse(String gridName, String origin, String resolverName, byte[] bytes)
            throws IOException {
        String lower = gridName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".gtx")) {
            return parseGtx(gridName, origin, resolverName, bytes);
        }
        byte[] header = new byte[4];
        System.arraycopy(bytes, 0, header, 0, Math.min(4, bytes.length));
        if (GeoTiffDataset.isTiff(header, Math.min(4, bytes.length))) {
            return parseGeoTiff(gridName, origin, resolverName, bytes);
        }
        throw new IOException("Unrecognised vertical grid format for " + gridName + " at " + origin
                + ". proj4j reads GTX (dispatched on the .gtx suffix, as PROJ does, because GTX has "
                + "no magic bytes) and GeoTIFF.");
    }

    /**
     * Builds the grid tree for a vertical GeoTIFF.
     *
     * <p>A file with one top-level image becomes one {@code VerticalGrid} carrying its subgrids. A file
     * with several unrelated top-level images becomes a synthetic root spanning their union with
     * {@code values == null}; {@link #gridAt} descends past it and never interpolates it.
     */
    static VerticalGrid parseGeoTiff(String gridName, String origin, String resolverName,
                                     byte[] bytes) throws IOException {
        List<GeoTiffGrid.VerticalLayer> roots = GeoTiffGrid.readVerticalLayers(gridName, bytes);
        if (roots.size() == 1) {
            return fromLayer(roots.get(0), origin, resolverName);
        }
        List<VerticalGrid> tops = new ArrayList<VerticalGrid>(roots.size());
        double west = Double.POSITIVE_INFINITY;
        double south = Double.POSITIVE_INFINITY;
        double east = Double.NEGATIVE_INFINITY;
        double north = Double.NEGATIVE_INFINITY;
        double resX = Double.POSITIVE_INFINITY;
        double resY = Double.POSITIVE_INFINITY;
        for (int i = 0; i < roots.size(); i++) {
            GeoTiffGrid.VerticalLayer layer = roots.get(i);
            tops.add(fromLayer(layer, origin, resolverName));
            west = Math.min(west, layer.west);
            south = Math.min(south, layer.south);
            east = Math.max(east, layer.east());
            north = Math.max(north, layer.north());
            resX = Math.min(resX, layer.resX);
            resY = Math.min(resY, layer.resY);
        }
        int w = (int) Math.ceil((east - west) / resX) + 1;
        int h = (int) Math.ceil((north - south) / resY) + 1;
        return new VerticalGrid(gridName + " (bounding box of " + roots.size() + " grids)", origin,
                resolverName, "gtiff", w, h, west, south, resX, resY, null,
                Collections.unmodifiableList(tops), true, false, 0f);
    }

    private static VerticalGrid fromLayer(GeoTiffGrid.VerticalLayer layer, String origin,
                                          String resolverName) {
        List<VerticalGrid> kids;
        if (layer.children.isEmpty()) {
            kids = Collections.emptyList();
        } else {
            List<VerticalGrid> built = new ArrayList<VerticalGrid>(layer.children.size());
            for (int i = 0; i < layer.children.size(); i++) {
                built.add(fromLayer(layer.children.get(i), origin, resolverName));
            }
            kids = Collections.unmodifiableList(built);
        }
        return new VerticalGrid(layer.name, origin, resolverName, "gtiff", layer.width, layer.height,
                layer.west, layer.south, layer.resX, layer.resY, layer.values, kids, true,
                layer.hasNodata, layer.noData);
    }

    static VerticalGrid parseGtx(String gridName, String origin, String resolverName, byte[] bytes)
            throws IOException {
        if (bytes.length < GTX_HEADER_BYTES) {
            throw new IOException("GTX file " + origin + " is shorter than its 40-byte header");
        }
        ByteBuffer hdr = ByteBuffer.wrap(bytes, 0, GTX_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
        double yorigin = hdr.getDouble();
        double xorigin = hdr.getDouble();
        double ystep = hdr.getDouble();
        double xstep = hdr.getDouble();
        int rows = hdr.getInt();
        int columns = hdr.getInt();

        // PROJ's own validation, verbatim.
        if (columns <= 0 || rows <= 0 || xorigin < -360 || xorigin > 360
                || yorigin < -90 || yorigin > 90) {
            throw new IOException("GTX file " + origin + " header has invalid extents (rows=" + rows
                    + ", columns=" + columns + ", xorigin=" + xorigin + ", yorigin=" + yorigin + ")");
        }
        // Some GTX files come in 0-360; shift back into -180..180 where possible.
        if (xorigin >= 180.0) {
            xorigin -= 360.0;
        }

        long expected = (long) GTX_HEADER_BYTES + 4L * rows * columns;
        if (bytes.length < expected) {
            throw new IOException("GTX file " + origin + " declares " + rows + "x" + columns
                    + " nodes, which needs " + expected + " bytes, but the file is " + bytes.length);
        }

        float[] values = new float[rows * columns];
        ByteBuffer.wrap(bytes, GTX_HEADER_BYTES, values.length * 4)
                .order(ByteOrder.BIG_ENDIAN).asFloatBuffer().get(values);

        return new VerticalGrid(gridName, origin, resolverName, "gtx", columns, rows,
                xorigin * DEG_TO_RAD, yorigin * DEG_TO_RAD,
                xstep * DEG_TO_RAD, ystep * DEG_TO_RAD, values);
    }

    /**
     * True iff {@code (lam, phi)} in radians falls inside this grid, within PROJ's tolerance.
     *
     * <p>PROJ's {@code isPointInExtent} ({@code 9.8.1:src/grids.cpp:3689-3704}): latitude first, then a
     * short circuit for a grid spanning the world in longitude, then a &plusmn;2&pi; shift of the
     * longitude before the east/west test.
     *
     * <p><strong>The wrap is load-bearing, not decoration.</strong>
     * {@code tests/us_noaa_geoid06_ak_subset_at_antimeridian.tif} declares
     * {@code west = 179.8}&deg; and therefore {@code east = 180.1833}&deg; — past the antimeridian and
     * past &pi; in radians, which PROJ's own extent validation explicitly permits ({@code |west|} and
     * {@code |east|} are checked against 4&pi;, not &pi;). Without the shift, a point given as
     * {@code -179.99}&deg; is judged outside a grid that covers it, and the ten assertions that file
     * carries in {@code geotiff_grids.gie} all fail with an outside-grid error.
     */
    public boolean covers(double lam, double phi) {
        double epsilon = (resX + resY) * REL_TOLERANCE;
        if (!(phi + epsilon >= south && phi - epsilon <= north)) {
            return false;
        }
        if (isFullWorldLongitude()) {
            return true;
        }
        double x = lam;
        if (x + epsilon < west) {
            x += 2 * Math.PI;
        } else if (x - epsilon > east) {
            x -= 2 * Math.PI;
        }
        return x + epsilon >= west && x - epsilon <= east;
    }

    /**
     * The deepest grid in this subtree containing the point: {@code VerticalShiftGrid::gridAt}
     * ({@code grids.cpp:1708-1717}), which descends into the first child whose extent contains the
     * point and otherwise returns itself.
     *
     * @return the grid to interpolate, or {@code null} if this is a synthetic bounding root that no
     *         child covers
     */
    private VerticalGrid gridAt(double lam, double phi) {
        for (int i = 0; i < children.size(); i++) {
            VerticalGrid child = children.get(i);
            if (child.covers(lam, phi)) {
                return child.gridAt(lam, phi);
            }
        }
        return values == null ? null : this;
    }

    /** Nested subgrids, outermost first. Empty for GTX and for a single-image GeoTIFF. */
    public List<VerticalGrid> getSubGrids() {
        return children;
    }

    /** Total number of grids in this tree, counting subgrids at any depth. */
    public int countGrids() {
        int n = 1;
        for (int i = 0; i < children.size(); i++) {
            n += children.get(i).countGrids();
        }
        return n;
    }

    /**
     * Bilinearly interpolated grid value at {@code (lam, phi)} in radians, in the file's own units
     * (metres, for a geoid model).
     *
     * <p>Verbatim port of PROJ 9.8.1 {@code read_vgrid_value}. Returns {@link Double#NaN} if the point
     * is outside the grid or if every surrounding node is nodata; PROJ returns {@code HUGE_VAL} and
     * sets {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_GRID} / {@code _GRID_AT_NODATA}. Callers must map
     * that to {@code ErrorCause.COORDINATE_OUTSIDE_GRID} / {@code GRID_NODATA} rather than pass a
     * sentinel on.
     *
     * @param multiplier the {@code +multiplier} value, used only for the nodata test, exactly as PROJ
     *                   does.
     */
    public double valueAt(double lam, double phi, double multiplier) {
        if (Double.isNaN(lam) || Double.isNaN(phi)) {
            return Double.NaN;
        }
        // VerticalShiftGridSet::gridAt then read_vgrid_value, in that order. A GTX grid has no
        // children, so `target == this` and the call below is the 1.4.x path unchanged.
        VerticalGrid target = gridAt(lam, phi);
        if (target == null) {
            return Double.NaN;
        }
        return target.interpolate(lam, phi, multiplier);
    }

    /** {@code read_vgrid_value}, on this grid's own nodes. */
    private double interpolate(double lam, double phi, double multiplier) {
        double invResX = 1.0 / resX;
        double invResY = 1.0 / resY;

        double gridX = (lam - west) * invResX;
        if (lam < west) {
            if (isFullWorldLongitude()) {
                gridX = mod(mod(gridX + width, width) + width, width);
            } else {
                gridX = (lam + 2 * Math.PI - west) * invResX;
            }
        } else if (lam > east) {
            if (isFullWorldLongitude()) {
                gridX = mod(mod(gridX + width, width) + width, width);
            } else {
                gridX = (lam - 2 * Math.PI - west) * invResX;
            }
        }
        double gridY = (phi - south) * invResY;
        int ix = (int) Math.round(Math.floor(gridX));
        if (!(ix >= 0 && ix < width)) {
            return Double.NaN;
        }
        int iy = (int) Math.round(Math.floor(gridY));
        if (!(iy >= 0 && iy < height)) {
            return Double.NaN;
        }
        gridX -= ix;
        gridY -= iy;

        int ix2 = ix + 1;
        if (ix2 >= width) {
            ix2 = isFullWorldLongitude() ? 0 : width - 1;
        }
        int iy2 = iy + 1;
        if (iy2 >= height) {
            iy2 = height - 1;
        }

        float a = values[iy * width + ix];
        float b = values[iy * width + ix2];
        float c = values[iy2 * width + ix];
        float d = values[iy2 * width + ix2];

        final double gridXY = gridX * gridY;
        boolean aValid = !isNodata(a, multiplier);
        boolean bValid = !isNodata(b, multiplier);
        boolean cValid = !isNodata(c, multiplier);
        boolean dValid = !isNodata(d, multiplier);
        int countValid = (aValid ? 1 : 0) + (bValid ? 1 : 0) + (cValid ? 1 : 0) + (dValid ? 1 : 0);

        double value = 0.0;
        if (countValid == 4) {
            // Weights and summation order exactly as PROJ writes them: changing the order changes
            // the last bits.
            value = a * (1.0 - gridX - gridY + gridXY);
            value += b * (gridX - gridXY);
            value += c * (gridY - gridXY);
            value += d * gridXY;
        } else if (countValid == 0) {
            return Double.NaN;
        } else {
            // Partially valid: PROJ renormalises over the valid nodes' weights.
            double totalWeight = 0.0;
            if (aValid) {
                double weight = 1.0 - gridX - gridY + gridXY;
                value = a * weight;
                totalWeight = weight;
            }
            if (bValid) {
                double weight = gridX - gridXY;
                value += b * weight;
                totalWeight += weight;
            }
            if (cValid) {
                double weight = gridY - gridXY;
                value += c * weight;
                totalWeight += weight;
            }
            if (dValid) {
                double weight = gridXY;
                value += d * weight;
                totalWeight += weight;
            }
            value /= totalWeight;
        }
        return value * multiplier;
    }

    /** Convenience overload with PROJ's default {@code +multiplier=1}. */
    public double valueAt(double lam, double phi) {
        return valueAt(lam, phi, 1.0);
    }

    /**
     * The nodata rule for <em>this</em> grid's format, because the two differ and neither generalises.
     *
     * <ul>
     *   <li><strong>GTX</strong> ({@code GTXVerticalShiftGrid::isNodata}, {@code grids.cpp:358-366}):
     *       the official sentinel is {@code -88.8888f}, but real grids — {@code naptrans2008.gtx} is
     *       upstream's cited example — use other large magnitudes, so anything beyond &plusmn;1000
     *       after applying the multiplier counts too.</li>
     *   <li><strong>GeoTIFF</strong> ({@code GTiffGrid::isNodata}, {@code grids.cpp:935-937}): only the
     *       declared {@code GDAL_NODATA} value, plus any NaN. The multiplier plays no part;
     *       {@code GTiffVGrid::isNodata} takes it and ignores it, with the parameter name commented
     *       out. Reusing the GTX magnitude heuristic here would silently void every geoid value beyond
     *       &plusmn;1 km, and the NaN clause would be missing.</li>
     * </ul>
     */
    private boolean isNodata(float val, double multiplier) {
        if (tiffNodataRule) {
            return (hasNodataValue && val == nodataValue) || Float.isNaN(val);
        }
        return val * multiplier > 1000 || val * multiplier < -1000 || val == -88.88880f;
    }

    private boolean isFullWorldLongitude() {
        return east - west + resX >= 2 * Math.PI - 1e-10;
    }

    private static double mod(double a, double b) {
        return a % b;
    }

    @Override
    public long sizeBytes() {
        long total = values == null ? 0L : (long) values.length * BYTES_PER_NODE;
        for (int i = 0; i < children.size(); i++) {
            total += children.get(i).sizeBytes();
        }
        return total;
    }

    public String getGridName() {
        return gridName;
    }

    public String getOrigin() {
        return origin;
    }

    public String getResolverName() {
        return resolverName;
    }

    public String getFormat() {
        return format;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /** {@code [west, south, east, north]} in radians. */
    public double[] extentRadians() {
        return new double[]{west, south, east, north};
    }

    /** {@code [resX, resY]} in radians. */
    public double[] resolutionRadians() {
        return new double[]{resX, resY};
    }

    /**
     * Raw node value at a grid index, for tests and diagnostics.
     *
     * @throws IllegalStateException on a synthetic bounding root, which has no nodes of its own
     */
    public float nodeAt(int x, int y) {
        if (values == null) {
            throw new IllegalStateException(gridName
                    + " is a synthetic bounding root over " + children.size()
                    + " grids and has no nodes of its own");
        }
        return values[y * width + x];
    }

    public static List<VerticalGrid> emptyList() {
        return Collections.emptyList();
    }

    @Override
    public String toString() {
        return "VerticalGrid[" + gridName + "; " + format + "; " + width + "x" + height + "]";
    }
}

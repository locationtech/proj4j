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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.proj4j.datum.tiff.GeoTiffDataset;
import org.locationtech.proj4j.datum.tiff.GeoTiffImage;
import org.locationtech.proj4j.util.FloatPolarCoordinate;
import org.locationtech.proj4j.util.IntPolarCoordinate;
import org.locationtech.proj4j.util.PolarCoordinate;

/**
 * Turns a geodetic GeoTIFF into the grid structures the rest of proj4j already knows how to use.
 *
 * <p>{@link org.locationtech.proj4j.datum.tiff} reads the container; this class applies the
 * <em>semantics</em> — which band is the latitude shift, what unit it is in, which way longitude is
 * positive, and how the IFDs nest into a subgrid hierarchy. Those are the two halves PROJ splits the
 * same way, between {@code GTiffGrid} and {@code GTiffHGridShiftSet}/{@code GTiffVGridShiftSet}.
 *
 * <h2>Horizontal grids</h2>
 * <p>Port of {@code GTiffHGridShiftSet::open} ({@code 9.8.1:src/grids.cpp:2487-2654}). The output is a
 * {@link Grid.ConversionTable} tree indistinguishable from what {@link NTV2} produces, so
 * {@code Grid.shift}, {@code nad_cvt} and {@code nad_intr} are reused unchanged — including the subgrid
 * descent {@code NTV2} already exercises.
 *
 * <p><strong>Sign convention.</strong> PROJ applies {@code in.lam += longShift} with {@code longShift}
 * positive east; proj4j's {@code nad_cvt} applies {@code in.lam -= cvs.lam}. So
 * {@code cvs.lam = -longShift}, and the {@code positive_value="west"} case cancels the negation rather
 * than adding one. This is the same convention {@code NTV2.readCells} stores (NTv2 is natively
 * positive-west and goes in unnegated) and the one {@code NTV1} got <em>wrong</em> for its whole
 * history, to the tune of ~8 m of longitude.
 *
 * <h2>Vertical grids</h2>
 * <p>Port of {@code GTiffVGridShiftSet::open} ({@code grids.cpp:1520-1608}). The result is described by
 * {@link VerticalLayer} and assembled into {@link VerticalGrid} instances by that class, because a
 * vertical grid holds one scalar per node rather than a pair and PROJ keeps the two hierarchies
 * separate for the same reason.
 *
 * <h2>Three upstream behaviours that look like bugs and are not</h2>
 * <ol>
 *   <li><strong>Band selection state carries across IFDs.</strong> {@code idxLatShift},
 *       {@code idxLongShift}, {@code convFactorToRadian} and {@code positiveEast} are declared
 *       <em>outside</em> the IFD loop, so a subgrid with no {@code DESCRIPTION} metadata inherits
 *       whatever the previous IFD resolved. {@code tests/test_hgrid_with_subgrid.tif} depends on it:
 *       IFD 0 ({@code CAwest}) carries {@code positive_value="west"} but no {@code DESCRIPTION}, so
 *       PROJ never reads that attribute for it and the grid stays positive-east, while IFD 1
 *       ({@code CAeast}) does declare descriptions and sets positive-east explicitly for everything
 *       after it.</li>
 *   <li><strong>{@code positive_value} is only consulted when a {@code longitude_offset} description
 *       was found.</strong> Upstream guards the whole block with
 *       {@code if (foundDescriptionForLongOffset)}. An {@code Item name="positive_value"} on a band
 *       PROJ did not identify by description is therefore ignored.</li>
 *   <li><strong>An unusable IFD after the first truncates the list instead of failing.</strong> See
 *       {@link GeoTiffDataset}.</li>
 * </ol>
 *
 * <p>All static; nothing here holds state between calls, and every product is deeply immutable.
 */
final class GeoTiffGrid {

    /** {@code grids.cpp:2501} — {@code (M_PI / 180.0) / 3600} , the NTv2-inspired default unit. */
    private static final double ARC_SECOND_TO_RADIAN = (Math.PI / 180.0) / 3600.0;

    private static final double DEGREE_TO_RADIAN = Math.PI / 180.0;

    /** {@code FILETYPE_PAGE}: the only non-zero {@code SubfileType} upstream tolerates. */
    private static final long FILETYPE_PAGE = 2L;

    private GeoTiffGrid() {
    }

    // =========================================================================================
    // Horizontal
    // =========================================================================================

    /**
     * Reads a horizontal shift GeoTIFF into {@code target}, wiring any subgrid hierarchy into
     * {@code target}'s {@code child}/{@code next} chain.
     *
     * @param gridName     the name the grid was requested under
     * @param origin       where the bytes came from, for provenance
     * @param resolverName which resolver produced them
     * @param bytes        the whole file
     * @param target       the {@link Grid} to populate
     * @throws IOException if the file is not a usable horizontal shift grid
     */
    static void loadHorizontal(String gridName, String origin, String resolverName, byte[] bytes,
                               Grid target) throws IOException {
        GeoTiffDataset dataset = GeoTiffDataset.open(bytes, gridName);
        List<GeoTiffImage> images = dataset.images();

        // Defaults inspired from NTv2, and deliberately declared outside the loop: see the class
        // javadoc, point 1. Changing that changes test_hgrid_with_subgrid.tif's answer.
        int idxLatShift = 0;
        int idxLongShift = 1;
        double convFactorToRadian = ARC_SECOND_TO_RADIAN;
        boolean positiveEast = true;

        List<Grid> roots = new ArrayList<Grid>();
        Map<String, Grid> byName = new HashMap<String, Grid>();
        Map<Grid, String> types = new IdentityHashMap<Grid, String>();

        for (int ifd = 0; ifd < images.size(); ifd++) {
            GeoTiffImage image = images.get(ifd);

            long subfileType = image.subfileType();
            if (subfileType != 0 && subfileType != FILETYPE_PAGE) {
                if (ifd == 0) {
                    throw new IOException("GeoTIFF grid " + gridName
                            + ": IFD 0 has SubfileType " + subfileType
                            + ", which marks it as a reduced-resolution overview or mask");
                }
                continue;
            }

            if (image.samplesPerPixel() < 2) {
                if (ifd == 0) {
                    throw new IOException("GeoTIFF grid " + gridName
                            + " is not a horizontal shift grid: IFD 0 has "
                            + image.samplesPerPixel()
                            + " sample(s) per pixel and a horizontal grid needs at least 2 "
                            + "(latitude and longitude offsets)");
                }
                continue;
            }

            boolean foundAnyDescription = false;
            boolean foundLat = false;
            boolean foundLong = false;
            for (int i = 0; i < image.samplesPerPixel(); i++) {
                String desc = image.metadataItem("DESCRIPTION", i);
                if (!desc.isEmpty()) {
                    foundAnyDescription = true;
                }
                if ("latitude_offset".equals(desc)) {
                    idxLatShift = i;
                    foundLat = true;
                } else if ("longitude_offset".equals(desc)) {
                    idxLongShift = i;
                    foundLong = true;
                }
            }

            if (foundAnyDescription && !foundLong && !foundLat) {
                if (ifd > 0) {
                    // Upstream: an extra IFD without our channels of interest -- e.g. accuracy
                    // bands -- is simply skipped. tests/test_hgrid_extra_ifd_with_other_info.tif.
                    continue;
                }
                throw new IOException("GeoTIFF grid " + gridName + ": IFD 0 has band descriptions "
                        + "but none of them is latitude_offset or longitude_offset");
            }
            if (foundLat && !foundLong) {
                throw new IOException("GeoTIFF grid " + gridName
                        + " declares a latitude_offset band but no longitude_offset band");
            }
            if (foundLong && !foundLat) {
                throw new IOException("GeoTIFF grid " + gridName
                        + " declares a longitude_offset band but no latitude_offset band");
            }
            if (idxLatShift >= image.samplesPerPixel() || idxLongShift >= image.samplesPerPixel()) {
                throw new IOException("GeoTIFF grid " + gridName + " IFD " + ifd
                        + ": band index out of range (latitude " + idxLatShift + ", longitude "
                        + idxLongShift + ", but only " + image.samplesPerPixel() + " bands)");
            }

            if (foundLong) {
                String positiveValue = image.metadataItem("positive_value", idxLongShift);
                if (!positiveValue.isEmpty()) {
                    if ("west".equals(positiveValue)) {
                        positiveEast = false;
                    } else if ("east".equals(positiveValue)) {
                        positiveEast = true;
                    } else {
                        throw new IOException("GeoTIFF grid " + gridName
                                + " declares positive_value=\"" + positiveValue
                                + "\" for its longitude offset band; only \"east\" and \"west\" "
                                + "are defined");
                    }
                }
            }

            String unitLat = image.metadataItem("UNITTYPE", idxLatShift);
            String unitLong = image.metadataItem("UNITTYPE", idxLongShift);
            if (!unitLat.equals(unitLong)) {
                throw new IOException("GeoTIFF grid " + gridName
                        + " declares different units for its latitude (\"" + unitLat
                        + "\") and longitude (\"" + unitLong + "\") offset bands");
            }
            if (!unitLat.isEmpty()) {
                if ("arc-second".equals(unitLat) || "arc-seconds per year".equals(unitLat)) {
                    convFactorToRadian = ARC_SECOND_TO_RADIAN;
                } else if ("radian".equals(unitLat)) {
                    convFactorToRadian = 1.0;
                } else if ("degree".equals(unitLat)) {
                    convFactorToRadian = DEGREE_TO_RADIAN;
                } else {
                    throw new IOException("GeoTIFF grid " + gridName + " declares UNITTYPE=\""
                            + unitLat + "\" for its offset bands; proj4j and PROJ understand only "
                            + "\"arc-second\", \"arc-seconds per year\", \"radian\" and \"degree\"");
                }
            }

            requireGeographic(image, gridName, ifd);

            Grid sub = buildHorizontalGrid(image, gridName, origin, resolverName, ifd,
                    images.size(), idxLatShift, idxLongShift, convFactorToRadian, positiveEast);

            insertIntoHierarchy(sub, image.metadataItem("grid_name"),
                    image.metadataItem("parent_grid_name"), image.metadataItem("TYPE"),
                    roots, byName, types);
        }

        if (roots.isEmpty()) {
            throw new IOException("GeoTIFF grid " + gridName
                    + " contains no usable horizontal shift image");
        }
        attach(target, roots, "GeoTIFF horizontal shift grid");
    }

    private static Grid buildHorizontalGrid(GeoTiffImage image, String gridName, String origin,
                                            String resolverName, int ifd, int ifdCount,
                                            int idxLatShift, int idxLongShift, double conv,
                                            boolean positiveEast) throws IOException {
        int width = image.width();
        int height = image.height();
        float[][] planes = image.readSamples(new int[]{idxLatShift, idxLongShift});
        float[] lat = planes[0];
        float[] lon = planes[1];

        FloatPolarCoordinate[] cvs = new FloatPolarCoordinate[width * height];
        for (int i = 0; i < cvs.length; i++) {
            // GTiffHGrid::valueAt, then proj4j's opposite longitude sign. Both multiplications are
            // done in double and narrowed once, exactly as upstream's
            // `latShift = static_cast<float>(latShift * m_convFactorToRadian)`.
            float latShift = (float) (lat[i] * conv);
            float longShift = (float) (lon[i] * conv);
            if (!positiveEast) {
                longShift = -longShift;
            }
            cvs[i] = new FloatPolarCoordinate(-longShift, latShift);
        }

        Grid.ConversionTable table = new Grid.ConversionTable();
        String subName = image.metadataItem("grid_name");
        table.id = subName.isEmpty()
                ? "GeoTIFF horizontal shift grid (IFD " + ifd + ")"
                : "GeoTIFF horizontal shift grid: " + subName;
        table.ll = new PolarCoordinate(image.west(), image.south());
        table.del = new PolarCoordinate(image.resX(), image.resY());
        table.lim = new IntPolarCoordinate(width, height);
        table.cvs = cvs;

        Grid sub = new Grid();
        sub.table = table;
        String label = gridName;
        if (ifdCount > 1) {
            label = gridName + " (index " + (ifd + 1) + ")";
        }
        if (!subName.isEmpty()) {
            label = label + "#" + subName;
        }
        sub.describeAs(label, "gtiff", origin, resolverName);
        return sub;
    }

    // =========================================================================================
    // Vertical
    // =========================================================================================

    /** The four band descriptions {@code GTiffVGridShiftSet::open} recognises, in upstream's order. */
    private static final String[] VERTICAL_DESCRIPTIONS = {
            "geoid_undulation", "vertical_offset", "hydroid_height", "ellipsoidal_height_offset"};

    /**
     * One node of the vertical subgrid hierarchy, in the form {@link VerticalGrid} needs to build
     * itself. A plain carrier: no behaviour, deeply immutable, package-private.
     */
    static final class VerticalLayer {
        final String name;
        final int width;
        final int height;
        /** Radians. */
        final double west;
        final double south;
        final double resX;
        final double resY;
        /** Row-major, south row first, west to east. */
        final float[] values;
        final boolean hasNodata;
        final float noData;
        final List<VerticalLayer> children;

        VerticalLayer(String name, int width, int height, double west, double south, double resX,
                      double resY, float[] values, boolean hasNodata, float noData) {
            this.name = name;
            this.width = width;
            this.height = height;
            this.west = west;
            this.south = south;
            this.resX = resX;
            this.resY = resY;
            this.values = values;
            this.hasNodata = hasNodata;
            this.noData = noData;
            this.children = new ArrayList<VerticalLayer>();
        }

        double east() {
            return west + resX * (width - 1);
        }

        double north() {
            return south + resY * (height - 1);
        }

        /** Inclusive on all four sides, so a layer contains itself; see {@code contains(Grid,
         * Grid)} for why the identity guard is the load-bearing half. */
        boolean contains(VerticalLayer other) {
            if (other == this) {
                return false;
            }
            return other.west >= west && other.east() <= east()
                    && other.south >= south && other.north() <= north();
        }

        /** Deepest-first insertion, mirroring {@code GTiffVGrid::insertGrid}. */
        void insert(VerticalLayer sub) {
            for (int i = 0; i < children.size(); i++) {
                if (children.get(i).contains(sub)) {
                    children.get(i).insert(sub);
                    return;
                }
            }
            children.add(sub);
        }
    }

    /**
     * Reads a vertical shift GeoTIFF and returns its top-level layers, each carrying its own subgrid
     * children.
     *
     * @param gridName the name the grid was requested under
     * @param bytes    the whole file
     * @return one or more top-level layers, in IFD order
     * @throws IOException if the file is not a usable vertical shift grid
     */
    static List<VerticalLayer> readVerticalLayers(String gridName, byte[] bytes) throws IOException {
        GeoTiffDataset dataset = GeoTiffDataset.open(bytes, gridName);
        List<GeoTiffImage> images = dataset.images();

        // Declared outside the loop, as upstream declares it.
        int idxSample = 0;

        List<VerticalLayer> roots = new ArrayList<VerticalLayer>();
        Map<String, VerticalLayer> byName = new HashMap<String, VerticalLayer>();
        Map<VerticalLayer, String> types = new IdentityHashMap<VerticalLayer, String>();

        for (int ifd = 0; ifd < images.size(); ifd++) {
            GeoTiffImage image = images.get(ifd);

            long subfileType = image.subfileType();
            if (subfileType != 0 && subfileType != FILETYPE_PAGE) {
                if (ifd == 0) {
                    throw new IOException("GeoTIFF vertical grid " + gridName
                            + ": IFD 0 has SubfileType " + subfileType
                            + ", which marks it as a reduced-resolution overview or mask");
                }
                continue;
            }

            boolean foundAnyDescription = false;
            boolean foundShift = false;
            for (int i = 0; i < image.samplesPerPixel(); i++) {
                String desc = image.metadataItem("DESCRIPTION", i);
                if (!desc.isEmpty()) {
                    foundAnyDescription = true;
                }
                for (int k = 0; k < VERTICAL_DESCRIPTIONS.length; k++) {
                    if (VERTICAL_DESCRIPTIONS[k].equals(desc)) {
                        idxSample = i;
                        foundShift = true;
                    }
                }
            }
            if (foundAnyDescription && !foundShift) {
                if (ifd > 0) {
                    continue;
                }
                throw new IOException("GeoTIFF vertical grid " + gridName + ": IFD 0 has band "
                        + "descriptions, but none of them is geoid_undulation, vertical_offset, "
                        + "hydroid_height or ellipsoidal_height_offset");
            }
            if (idxSample >= image.samplesPerPixel()) {
                throw new IOException("GeoTIFF vertical grid " + gridName + " IFD " + ifd
                        + ": band index " + idxSample + " but only " + image.samplesPerPixel()
                        + " bands");
            }

            requireGeographic(image, gridName, ifd);

            String subName = image.metadataItem("grid_name");
            VerticalLayer layer = new VerticalLayer(
                    subName.isEmpty() ? gridName + " (index " + (ifd + 1) + ")"
                            : gridName + "#" + subName,
                    image.width(), image.height(), image.west(), image.south(),
                    image.resX(), image.resY(), image.readSample(idxSample),
                    image.hasNodata(), image.noDataValue());

            insertVertical(layer, subName, image.metadataItem("parent_grid_name"),
                    image.metadataItem("TYPE"), roots, byName, types);
        }

        if (roots.isEmpty()) {
            throw new IOException("GeoTIFF vertical grid " + gridName
                    + " contains no usable vertical shift image");
        }
        return Collections.unmodifiableList(roots);
    }

    // =========================================================================================
    // Shared: hierarchy assembly, a port of insertIntoHierarchy (grids.cpp:1380-1440)
    // =========================================================================================

    private static void insertIntoHierarchy(Grid grid, String gridName, String parentName,
                                            String type, List<Grid> roots, Map<String, Grid> byName,
                                            Map<Grid, String> types) {
        types.put(grid, type);
        // LOOK THE PARENT UP BEFORE REGISTERING THIS GRID UNDER ITS OWN NAME. The two statements
        // used to be the other way round, and that ordering -- not any check -- was the only thing
        // standing between this reader and a self-referential grid. An IFD whose
        // `parent_grid_name` equals its own `grid_name` registered itself, found itself as its own
        // parent, passed contains() (which uses >= / <=, so every extent contains itself), and
        // reached appendChild(grid, grid), i.e. grid.child == grid. Grid.shift's subgrid descent
        // (`while (grid.child != null) ... grid = child;`) would then spin forever, on the
        // per-row path, on an untrusted file.
        //
        // It was unreachable, but by an ACCIDENT OF CONTROL FLOW rather than by a guard: the
        // appendChild branch returns before roots.add, so the cycle was built and then dropped on
        // the floor, and any refactor that moved the registration or added a `roots.add` after it
        // would have made it live. NTV2.java is safe for exactly the reason this now is -- it
        // resolves `byName.get(parentName)` before `byName.put(subName, subGrid)`. Same ordering
        // here, plus the identity guard in contains(), so it takes two independent regressions
        // rather than one to reintroduce the cycle. GeoTiffSelfCycleTest pins both.
        Grid parent = parentName.isEmpty() ? null : byName.get(parentName);
        if (!gridName.isEmpty()) {
            byName.put(gridName, grid);
        }
        if (!parentName.isEmpty()) {
            if (parent != null && contains(parent, grid)) {
                appendChild(parent, grid);
                return;
            }
            // Upstream logs "refers to non-existing parent" / "extent is not included in it" and
            // falls through to the bounding-box method. Deliberately not an error: the declared
            // hierarchy is advisory and the extents are authoritative.
        } else if (!gridName.isEmpty()) {
            roots.add(grid);
            return;
        }
        for (int i = 0; i < roots.size(); i++) {
            Grid candidate = roots.get(i);
            String candidateType = types.get(candidate);
            if (!type.isEmpty() && !type.equals(candidateType == null ? "" : candidateType)) {
                continue;
            }
            if (contains(candidate, grid)) {
                insertGrid(candidate, grid);
                return;
            }
        }
        roots.add(grid);
    }

    private static void insertVertical(VerticalLayer layer, String gridName, String parentName,
                                       String type, List<VerticalLayer> roots,
                                       Map<String, VerticalLayer> byName,
                                       Map<VerticalLayer, String> types) {
        types.put(layer, type);
        // Same inversion, same fix, same reason as insertIntoHierarchy above -- a self-parenting
        // IFD used to reach `layer.children.add(layer)`. The vertical path walks its children
        // recursively in VerticalGrid rather than in Grid.shift, so the consequence is a
        // StackOverflowError rather than a hang, which is a different Error and the same defect.
        VerticalLayer parent = parentName.isEmpty() ? null : byName.get(parentName);
        if (!gridName.isEmpty()) {
            byName.put(gridName, layer);
        }
        if (!parentName.isEmpty()) {
            if (parent != null && parent.contains(layer)) {
                parent.children.add(layer);
                return;
            }
        } else if (!gridName.isEmpty()) {
            roots.add(layer);
            return;
        }
        for (int i = 0; i < roots.size(); i++) {
            VerticalLayer candidate = roots.get(i);
            String candidateType = types.get(candidate);
            if (!type.isEmpty() && !type.equals(candidateType == null ? "" : candidateType)) {
                continue;
            }
            if (candidate.contains(layer)) {
                candidate.insert(layer);
                return;
            }
        }
        roots.add(layer);
    }

    /**
     * Upstream's containment test: inclusive on all four sides, so — deliberately — a grid
     * contains another grid with exactly its own extent. That is correct for the overlapping-IFD
     * case it exists for, and it is also why {@code contains(g, g)} is {@code true} for every
     * grid. The identity guard is therefore not redundant with the inclusive comparisons; it is
     * what stops "always contains itself" from becoming "is its own parent". The extent
     * comparisons themselves are untouched, so no legitimate pairing changes.
     */
    // Package-private, not private, so GeoTiffSelfCycleTest can assert the identity guard directly
    // rather than only through its consequences. The cycle it prevents is unreachable from the
    // returned roots, so a test driving the reader can only observe the *other* half of the fix.
    static boolean contains(Grid parent, Grid child) {
        if (parent == child) {
            return false;
        }
        double[] p = parent.extentRadians();
        double[] c = child.extentRadians();
        return c[0] >= p[0] && c[2] <= p[2] && c[1] >= p[1] && c[3] <= p[3];
    }

    /** {@code GTiffHGrid::insertGrid}: descend to the deepest child that contains the newcomer. */
    private static void insertGrid(Grid parent, Grid grid) {
        for (Grid c = parent.getChild(); c != null; c = c.getNext()) {
            if (contains(c, grid)) {
                insertGrid(c, grid);
                return;
            }
        }
        appendChild(parent, grid);
    }

    private static void appendChild(Grid parent, Grid child) {
        Grid existing = parent.getChild();
        if (existing == null) {
            parent.setChild(child);
            return;
        }
        while (existing.getNext() != null) {
            existing = existing.getNext();
        }
        existing.setNext(child);
    }

    /**
     * Wires the roots onto {@code target}. A single childless root becomes {@code target}'s own table,
     * exactly as {@link NTV2} does for a one-subgrid file; anything else gets a synthetic
     * bounding-box table with {@code cvs == null}, which {@code Grid.shift} treats as
     * <em>"descend or move on"</em>.
     */
    private static void attach(Grid target, List<Grid> roots, String label) {
        if (roots.size() == 1 && roots.get(0).getChild() == null) {
            target.table = roots.get(0).table;
            target.setChild(null);
            return;
        }
        target.table = boundingTable(roots, label);
        Grid previous = null;
        for (int i = 0; i < roots.size(); i++) {
            Grid root = roots.get(i);
            if (previous == null) {
                target.setChild(root);
            } else {
                previous.setNext(root);
            }
            previous = root;
        }
    }

    /**
     * A table spanning every root, with a {@code null} node array. Only the extent and the cell size
     * are meaningful, and the cell size is the finest of the roots so the derived {@code lim} never
     * under-covers the union. Same shape as {@code NTV2.boundingTable}.
     */
    private static Grid.ConversionTable boundingTable(List<Grid> roots, String label) {
        double west = Double.POSITIVE_INFINITY;
        double south = Double.POSITIVE_INFINITY;
        double east = Double.NEGATIVE_INFINITY;
        double north = Double.NEGATIVE_INFINITY;
        double delLam = Double.POSITIVE_INFINITY;
        double delPhi = Double.POSITIVE_INFINITY;
        for (int i = 0; i < roots.size(); i++) {
            Grid root = roots.get(i);
            double[] e = root.extentRadians();
            west = Math.min(west, e[0]);
            south = Math.min(south, e[1]);
            east = Math.max(east, e[2]);
            north = Math.max(north, e[3]);
            delLam = Math.min(delLam, Math.abs(root.table.del.lam));
            delPhi = Math.min(delPhi, Math.abs(root.table.del.phi));
        }
        Grid.ConversionTable bounds = new Grid.ConversionTable();
        bounds.id = label + " (bounding box of " + roots.size() + " root subgrids)";
        bounds.ll = new PolarCoordinate(west, south);
        bounds.del = new PolarCoordinate(delLam, delPhi);
        bounds.lim = new IntPolarCoordinate(
                (int) Math.ceil((east - west) / delLam) + 1,
                (int) Math.ceil((north - south) / delPhi) + 1);
        bounds.cvs = null;
        return bounds;
    }

    /**
     * proj4j's grid machinery is geographic throughout: {@code ConversionTable} is in radians of
     * longitude and latitude and {@code VerticalGrid} wraps at 2&pi;. PROJ constructs a projected grid
     * happily and then refuses it at use time — {@code pj_hgrid_value} and
     * {@code pj_bilinear_interpolation_three_samples} both say <em>"Can only handle grids referenced
     * in a geographic CRS"</em>. Refusing at parse time reaches the same outcome one step earlier and
     * with the file name in the message.
     */
    private static void requireGeographic(GeoTiffImage image, String gridName, int ifd)
            throws IOException {
        if (!image.isGeographic()) {
            throw new IOException("GeoTIFF grid " + gridName + " IFD " + ifd
                    + " declares GTModelTypeGeoKey=ModelTypeProjected; proj4j, like PROJ, can only "
                    + "apply grids referenced in a geographic CRS");
        }
    }
}

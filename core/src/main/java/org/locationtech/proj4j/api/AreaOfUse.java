/*
 * Copyright 2026, PROJ4J contributors
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
package org.locationtech.proj4j.api;

/**
 * The geographic extent over which a CRS or an operation is declared valid, in degrees on the
 * WGS 84 ellipsoid.
 *
 * <h2>Where this comes from, and where it does not</h2>
 *
 * <p>An area of use is <b>database metadata</b>. PROJ reads it from {@code proj.db}'s
 * {@code extent} table, and Proj4J ships no such database, so
 * {@link Crs#areaOfUse()} is {@link java.util.Optional#empty()} for a CRS created from an
 * {@code authority:code} name or from a PROJ.4 parameter string. It is <b>not</b> guessed: a
 * plausible bounding box invented from a projection's parameters would be exactly the kind of
 * answer this API exists to avoid.
 *
 * <p>It <em>is</em> populated when the CRS was read from a document that carried it, because then
 * it is a fact the caller supplied rather than one this library inferred:
 * <ul>
 * <li>WKT2's {@code BBOX[south, west, north, east]} inside {@code USAGE[AREA[...]]};</li>
 * <li>PROJJSON's {@code bbox} object.</li>
 * </ul>
 *
 * <p>{@link #isDatabaseDerived()} is {@code false} in both of those cases and would be {@code true}
 * only for a real database lookup, so a caller can tell a document's claim from an authority's.
 *
 * <h2>Antimeridian crossing</h2>
 *
 * <p>EPSG, WKT2 and PROJJSON all express a box that crosses the antimeridian with
 * {@code west > east} &mdash; for example Fiji, {@code west = 176.8}, {@code east = -178.4}. That
 * convention is preserved verbatim; {@link #crossesAntimeridian()} reports it and
 * {@link #contains(double, double)} handles it. Normalising it away would silently turn a 5&deg;
 * box into a 355&deg; one.
 *
 * <p>Immutable and safe to share between threads.
 *
 * @since 1.5.0
 */
public final class AreaOfUse {

    private final double westLongitude;
    private final double southLatitude;
    private final double eastLongitude;
    private final double northLatitude;
    private final String description;
    private final boolean databaseDerived;

    /**
     * Creates an area of use from the four bounds and an optional human-readable description.
     *
     * @param westLongitude   western bound in degrees; may be greater than {@code eastLongitude}
     *                        for a box crossing the antimeridian
     * @param southLatitude   southern bound in degrees
     * @param eastLongitude   eastern bound in degrees
     * @param northLatitude   northern bound in degrees
     * @param description     a name such as {@code "United States (USA) - CONUS"}, or null
     * @param databaseDerived true only if these bounds came from an authority database rather than
     *                        from a document the caller supplied
     * @throws IllegalArgumentException if a bound is not finite, a latitude is outside
     *                                  &plusmn;90&deg;, or {@code southLatitude > northLatitude}
     */
    public AreaOfUse(double westLongitude, double southLatitude, double eastLongitude,
                     double northLatitude, String description, boolean databaseDerived) {
        check(westLongitude, "westLongitude", 180.0);
        check(eastLongitude, "eastLongitude", 180.0);
        check(southLatitude, "southLatitude", 90.0);
        check(northLatitude, "northLatitude", 90.0);
        if (southLatitude > northLatitude) {
            throw new IllegalArgumentException("southLatitude " + southLatitude
                    + " is north of northLatitude " + northLatitude);
        }
        this.westLongitude = westLongitude;
        this.southLatitude = southLatitude;
        this.eastLongitude = eastLongitude;
        this.northLatitude = northLatitude;
        this.description = description;
        this.databaseDerived = databaseDerived;
    }

    private static void check(double v, String what, double limit) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new IllegalArgumentException(what + " is not finite: " + v);
        }
        if (Math.abs(v) > limit + 1e-9) {
            throw new IllegalArgumentException(what + " " + v + " is outside +/-" + limit);
        }
    }

    /**
     * Builds an area of use from a WKT2 {@code BBOX[]} array, which is in the order
     * {@code (southLatitude, westLongitude, northLatitude, eastLongitude)} &mdash; latitude first,
     * unlike everything else in this class.
     *
     * @param bbox        four values in WKT2 {@code BBOX} order, or null
     * @param description the {@code AREA[]} text, or null
     * @return the area, or null if {@code bbox} is null, not four values, or not a usable box
     */
    static AreaOfUse fromWktBbox(double[] bbox, String description) {
        if (bbox == null || bbox.length != 4) {
            return null;
        }
        try {
            return new AreaOfUse(bbox[1], bbox[0], bbox[3], bbox[2], description, false);
        } catch (IllegalArgumentException notUsable) {
            // A malformed BBOX in a caller's document is not a reason to refuse the whole CRS; it
            // is a reason to report no area of use. The CRS itself is unaffected by it.
            return null;
        }
    }

    /**
     * Builds an area of use from an authority database extent row.
     *
     * <p>{@link #isDatabaseDerived()} is {@code true} for the result, which is the point: a caller
     * comparing two extents can tell an authority's statement from a WKT document's assertion.
     *
     * <p>Returns null for an extent with no bounding box. <b>18 of the shipped database's 4,314
     * extents publish none</b>, and they are reported as absent rather than as
     * {@code (-180, -90, 180, 90)}: a fabricated world extent is indistinguishable from a genuine one
     * and would then win every area-of-use ranking.
     *
     * @param extent the extent row, or null
     * @return the area, or null if {@code extent} is null or has no bounding box
     */
    static AreaOfUse fromDbExtent(org.locationtech.proj4j.spi.DbExtent extent) {
        if (extent == null || !extent.hasBoundingBox()) {
            return null;
        }
        String label = extent.description() != null ? extent.description() : extent.name();
        try {
            return new AreaOfUse(extent.westLongitude(), extent.southLatitude(),
                    extent.eastLongitude(), extent.northLatitude(), label, true);
        } catch (IllegalArgumentException notUsable) {
            return null;
        }
    }

    /**
     * The western bound in degrees. Greater than {@link #eastLongitude()} iff the box crosses the
     * antimeridian.
     *
     * @return degrees east of Greenwich, in [-180, 180]
     */
    public double westLongitude() {
        return westLongitude;
    }

    /**
     * The southern bound in degrees.
     *
     * @return degrees north of the equator, in [-90, 90]
     */
    public double southLatitude() {
        return southLatitude;
    }

    /**
     * The eastern bound in degrees.
     *
     * @return degrees east of Greenwich, in [-180, 180]
     */
    public double eastLongitude() {
        return eastLongitude;
    }

    /**
     * The northern bound in degrees.
     *
     * @return degrees north of the equator, in [-90, 90]
     */
    public double northLatitude() {
        return northLatitude;
    }

    /**
     * A human-readable name for the area, if one was supplied.
     *
     * @return the description, or null
     */
    public String description() {
        return description;
    }

    /**
     * Whether these bounds came from an authority database rather than from a document.
     *
     * <p>{@code true} when they were read from a {@link org.locationtech.proj4j.spi.ProjDatabase}'s
     * {@code extent} table, {@code false} when a WKT or PROJJSON document asserted them. A caller
     * comparing two areas needs to be able to tell an authority's statement from a caller's.
     *
     * @return true iff the bounds are authority metadata
     */
    public boolean isDatabaseDerived() {
        return databaseDerived;
    }

    /**
     * Whether this box crosses the antimeridian, i.e. whether {@code west > east}.
     *
     * @return true for a box such as {@code west = 176.8, east = -178.4}
     */
    public boolean crossesAntimeridian() {
        return westLongitude > eastLongitude;
    }

    /**
     * Whether a geographic coordinate lies inside this box, handling the antimeridian.
     *
     * <p>Bounds are inclusive. Note the argument order: <b>longitude first</b>, consistent with the
     * rest of this library's default and with GeoJSON, and independent of any
     * {@link org.locationtech.proj4j.io.wkt.AxisOrderPolicy}.
     *
     * @param longitudeDegrees longitude in degrees
     * @param latitudeDegrees  latitude in degrees
     * @return true if the point is inside; false for a non-finite argument
     */
    public boolean contains(double longitudeDegrees, double latitudeDegrees) {
        if (Double.isNaN(longitudeDegrees) || Double.isNaN(latitudeDegrees)) {
            return false;
        }
        if (latitudeDegrees < southLatitude || latitudeDegrees > northLatitude) {
            return false;
        }
        if (crossesAntimeridian()) {
            return longitudeDegrees >= westLongitude || longitudeDegrees <= eastLongitude;
        }
        return longitudeDegrees >= westLongitude && longitudeDegrees <= eastLongitude;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("west=").append(westLongitude).append(", south=").append(southLatitude)
                .append(", east=").append(eastLongitude).append(", north=").append(northLatitude);
        if (crossesAntimeridian()) {
            sb.append(" (crosses the antimeridian)");
        }
        if (description != null) {
            sb.append(" -- ").append(description);
        }
        sb.append(databaseDerived ? " [authority database]" : " [declared by the CRS document]");
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AreaOfUse)) {
            return false;
        }
        AreaOfUse that = (AreaOfUse) o;
        return Double.compare(westLongitude, that.westLongitude) == 0
                && Double.compare(southLatitude, that.southLatitude) == 0
                && Double.compare(eastLongitude, that.eastLongitude) == 0
                && Double.compare(northLatitude, that.northLatitude) == 0
                && databaseDerived == that.databaseDerived
                && (description == null ? that.description == null
                        : description.equals(that.description));
    }

    @Override
    public int hashCode() {
        int h = Double.valueOf(westLongitude).hashCode();
        h = 31 * h + Double.valueOf(southLatitude).hashCode();
        h = 31 * h + Double.valueOf(eastLongitude).hashCode();
        h = 31 * h + Double.valueOf(northLatitude).hashCode();
        h = 31 * h + (description == null ? 0 : description.hashCode());
        return 31 * h + (databaseDerived ? 1 : 0);
    }
}

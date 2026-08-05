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
package org.locationtech.proj4j.spi;

/**
 * A geographic extent: the raw area-of-use row, before the facade turns it into an
 * {@code org.locationtech.proj4j.api.AreaOfUse}.
 * <p>
 * <strong>{@link #westLongitude()} may be greater than {@link #eastLongitude()}</strong>, and that is
 * not corrupt data: it is how the authority expresses an extent crossing the antimeridian. Normalising
 * it into a single interval turns a Pacific extent into an almost-global one, which then wins every
 * area-of-use ranking. {@link #crossesAntimeridian()} is provided so the check is impossible to forget.
 * <p>
 * 18 of the 4,314 extents have no bounding box at all. Those return {@link Double#NaN} for all four
 * bounds and {@code false} from {@link #hasBoundingBox()} — never {@code (-180, -90, 180, 90)}, because
 * a fabricated world extent is indistinguishable from a genuine one and would silently rank first.
 */
public final class DbExtent {

    private final String authName;
    private final String code;
    private final String name;
    private final String description;
    private final double westLongitude;
    private final double southLatitude;
    private final double eastLongitude;
    private final double northLatitude;
    private final boolean deprecated;

    public DbExtent(String authName, String code, String name, String description,
                    double westLongitude, double southLatitude, double eastLongitude,
                    double northLatitude, boolean deprecated) {
        this.authName = authName;
        this.code = code;
        this.name = name;
        this.description = description;
        this.westLongitude = westLongitude;
        this.southLatitude = southLatitude;
        this.eastLongitude = eastLongitude;
        this.northLatitude = northLatitude;
        this.deprecated = deprecated;
    }

    public String authName() {
        return authName;
    }

    public String code() {
        return code;
    }

    /** {@code "UK - Britain and UKCS 49"}&hellip; the short form. */
    public String name() {
        return name;
    }

    /**
     * The long form, which is what a human should be shown. Never null upstream
     * ({@code description TEXT NOT NULL}).
     */
    public String description() {
        return description;
    }

    public double westLongitude() {
        return westLongitude;
    }

    public double southLatitude() {
        return southLatitude;
    }

    public double eastLongitude() {
        return eastLongitude;
    }

    public double northLatitude() {
        return northLatitude;
    }

    /**
     * @return {@code false} for the 18 extents that publish no bounds. All four accessors are
     *         {@link Double#NaN} in that case.
     */
    public boolean hasBoundingBox() {
        return !Double.isNaN(westLongitude) && !Double.isNaN(southLatitude)
                && !Double.isNaN(eastLongitude) && !Double.isNaN(northLatitude);
    }

    /**
     * @return {@code true} iff west &gt; east, i.e. the extent wraps through 180&deg;.
     */
    public boolean crossesAntimeridian() {
        return hasBoundingBox() && westLongitude > eastLongitude;
    }

    /**
     * Degrees of longitude spanned, handling the antimeridian wrap. {@link Double#NaN} without a
     * bounding box.
     */
    public double longitudeSpan() {
        if (!hasBoundingBox()) {
            return Double.NaN;
        }
        return crossesAntimeridian()
                ? (180.0 - westLongitude) + (eastLongitude + 180.0)
                : eastLongitude - westLongitude;
    }

    /**
     * A crude ranking area in square degrees. Not a geodesic area — it is used only to order candidate
     * extents smallest-first, and ties are broken elsewhere by authority code, so a monotone proxy is
     * enough. {@link Double#NaN} without a bounding box.
     */
    public double rankingArea() {
        if (!hasBoundingBox()) {
            return Double.NaN;
        }
        return longitudeSpan() * (northLatitude - southLatitude);
    }

    /**
     * @param longitudeDegrees east-positive
     * @param latitudeDegrees  north-positive
     * @return {@code false} if there is no bounding box — "unknown" is reported as "not contained"
     *         rather than as "contained", so an absent extent never widens an area of use.
     */
    public boolean contains(double longitudeDegrees, double latitudeDegrees) {
        if (!hasBoundingBox()) {
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

    public boolean deprecated() {
        return deprecated;
    }

    public DbObjectRef ref() {
        return new DbObjectRef(DbObjectType.EXTENT, authName, code);
    }

    @Override
    public String toString() {
        if (!hasBoundingBox()) {
            return authName + ":" + code + " " + name + " (no bbox)";
        }
        return authName + ":" + code + " " + name + " [" + westLongitude + "," + southLatitude
                + " .. " + eastLongitude + "," + northLatitude + "]"
                + (crossesAntimeridian() ? " (crosses antimeridian)" : "");
    }
}

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
package org.locationtech.proj4j.io.wkt;

/**
 * One coordinate system axis, exactly as the source document declared it.
 * <p>
 * Axis information is <em>retained</em>, never discarded and never silently reordered: whether
 * the declared order is honoured when a {@link org.locationtech.proj4j.CoordinateReferenceSystem}
 * is built is decided by {@link AxisOrderPolicy} at that boundary, and this object is what the
 * policy is applied to.
 */
public final class AxisDefinition {

    /** Axis directions, spelled as WKT2 spells them (lower case). */
    public static final String NORTH = "north";
    public static final String SOUTH = "south";
    public static final String EAST = "east";
    public static final String WEST = "west";
    public static final String UP = "up";
    public static final String DOWN = "down";
    public static final String GEOCENTRIC_X = "geocentricX";
    public static final String GEOCENTRIC_Y = "geocentricY";
    public static final String GEOCENTRIC_Z = "geocentricZ";
    public static final String UNSPECIFIED = "unspecified";

    private String name;
    private String abbreviation;
    private String direction;
    private UnitDefinition unit;

    public AxisDefinition() {
    }

    public AxisDefinition(String name, String abbreviation, String direction, UnitDefinition unit) {
        this.name = name;
        this.abbreviation = abbreviation;
        this.direction = direction;
        this.unit = unit;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAbbreviation() {
        return abbreviation;
    }

    public void setAbbreviation(String abbreviation) {
        this.abbreviation = abbreviation;
    }

    /**
     * The axis direction, normalised to WKT2's lower-case spelling: one of the constants on this
     * class, or the raw token if it is something else.
     */
    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public UnitDefinition getUnit() {
        return unit;
    }

    public void setUnit(UnitDefinition unit) {
        this.unit = unit;
    }

    /**
     * Whether this axis measures longitude, easting or westing, i.e. is the "x" of a
     * longitude-first coordinate.
     */
    public boolean isHorizontalX() {
        return EAST.equals(direction) || WEST.equals(direction);
    }

    /**
     * Whether this axis measures latitude, northing or southing.
     */
    public boolean isHorizontalY() {
        return NORTH.equals(direction) || SOUTH.equals(direction);
    }

    public boolean isVertical() {
        return UP.equals(direction) || DOWN.equals(direction);
    }

    /**
     * The single character PROJ's {@code +axis=} uses for this direction, or {@code 0} if the
     * direction has no {@code +axis=} spelling.
     */
    public char toProjAxisChar() {
        if (EAST.equals(direction)) {
            return 'e';
        }
        if (WEST.equals(direction)) {
            return 'w';
        }
        if (NORTH.equals(direction)) {
            return 'n';
        }
        if (SOUTH.equals(direction)) {
            return 's';
        }
        if (UP.equals(direction)) {
            return 'u';
        }
        if (DOWN.equals(direction)) {
            return 'd';
        }
        return 0;
    }

    public String toString() {
        return name + " (" + direction + ")";
    }
}

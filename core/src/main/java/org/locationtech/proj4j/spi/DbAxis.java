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
 * One axis of a coordinate system, in authority order.
 * <p>
 * This is where authority axis order actually lives. {@code EPSG:4326}'s coordinate system
 * {@code EPSG:6422} has {@code order 1 = Geodetic latitude (Lat, north)} and
 * {@code order 2 = Geodetic longitude (Lon, east)} — which is why PROJ 6+ is latitude-first for it and
 * proj4j 1.4.3 is not. proj4j's default remains longitude-first; this class is the datum that lets
 * {@code AxisOrderPolicy.AUTHORITY} be implemented at all, and lets the difference be reported instead
 * of guessed.
 */
public final class DbAxis {

    private final String name;
    private final String abbreviation;
    private final String orientation;
    private final int order;
    private final DbObjectRef unit;

    public DbAxis(String name, String abbreviation, String orientation, int order, DbObjectRef unit) {
        this.name = name;
        this.abbreviation = abbreviation;
        this.orientation = orientation;
        this.order = order;
        this.unit = unit;
    }

    /** {@code "Geodetic latitude"}. */
    public String name() {
        return name;
    }

    /** {@code "Lat"}. */
    public String abbreviation() {
        return abbreviation;
    }

    /**
     * {@code "north"}, {@code "east"}, {@code "up"}, {@code "south"}, {@code "west"}, {@code "down"},
     * or a free-text bearing such as {@code "North along 90 deg East"}. Not an enum precisely because
     * upstream does not constrain it.
     */
    public String orientation() {
        return orientation;
    }

    /** 1-based position within the coordinate system. */
    public int order() {
        return order;
    }

    /** The axis unit of measure; may be null for ordinal systems. */
    public DbObjectRef unit() {
        return unit;
    }

    @Override
    public String toString() {
        return order + ": " + name + " (" + abbreviation + ", " + orientation + ")";
    }
}

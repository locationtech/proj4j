/*
 * Copyright 2025, PROJ4J contributors
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
package org.locationtech.proj4j.geoapi;

import java.io.Serializable;
import javax.measure.Unit;
import org.opengis.referencing.cs.AxisDirection;
import org.opengis.referencing.cs.CoordinateSystemAxis;
import org.opengis.referencing.cs.RangeMeaning;


/**
 * A coordinate system axis.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
@SuppressWarnings("serial")
final class Axis extends Wrapper implements CoordinateSystemAxis, Serializable {
    /**
     * The axes for a geographic or projected CRS.
     * Order is (down, west, south, null, east, north, up).
     * Each axis shall be in the array at the index equal to {@link #direction} + 3.
     */
    static final Axis[] GEOGRAPHIC, PROJECTED;
    static {
        GEOGRAPHIC = new Axis[] {
            new Axis("Ellipsoidal depth",  "d",   (byte) -3, false, 1),
            new Axis("Geodetic latitude",  "lat", (byte) -2, true,  1),
            new Axis("Geodetic longitude", "lon", (byte) -1, true,  1),
            null,
            new Axis("Geodetic longitude", "lon", (byte)  1, true,  1),
            new Axis("Geodetic latitude",  "lat", (byte)  2, true,  1),
            new Axis("Ellipsoidal height", "h",   (byte)  3, false, 1)
        };
        PROJECTED = new Axis[] {
            GEOGRAPHIC[0],
            new Axis("Southing", "S", (byte) -2, false, 1),
            new Axis("Westing",  "W", (byte) -1, false, 1),
            null,
            new Axis("Easting",  "E", (byte)  1, false, 1),
            new Axis("Northing", "N", (byte)  2, false, 1),
            GEOGRAPHIC[6]
        };
    }

    /**
     * The axis directions in the order declared in the {@link #GEOGRAPHIC} and {@link #PROJECTED} arrays.
     */
    private static final AxisDirection[] DIRECTIONS = {
        AxisDirection.DOWN,
        AxisDirection.SOUTH,
        AxisDirection.WEST,
        null,
        AxisDirection.EAST,
        AxisDirection.NORTH,
        AxisDirection.UP
    };

    /**
     * Index of the axis having the east direction in {@link #GEOGRAPHIC} and {@link #PROJECTED} arrays.
     */
    static final int INDEX_OF_EAST = 4;

    /**
     * The coordinate system axis name.
     */
    private final String name;

    /**
     * The coordinate system axis abbreviation.
     */
    private final String abbreviation;

    /**
     * The axis direction: 1=east, 2=north, 3=up.
     * The value may be negative for the opposite direction.
     */
    private final byte direction;

    /**
     * Whether the unit of measurement is degrees or metres.
     */
    private final boolean angular;

    /**
     * The scale factor to apply on unit of measurement.
     * For angular units, the base unit is degree, not radian.
     */
    private final double unitScale;

    /**
     * Unit of measurement, cached when first requested.
     */
    private transient Unit<?> unit;

    /**
     * Creates a new axis.
     *
     * @param name          the coordinate system axis name
     * @param abbreviation  the coordinate system axis abbreviation
     * @param north         whether the axis is oriented toward north or east.
     * @param angular       whether the unit of measurement is degrees or metres.
     * @param unitScale     the scale factor to apply on unit of measurement.
     */
    private Axis(final String name, final String abbreviation, final byte direction, final boolean angular, final double unitScale) {
        this.name         = name;
        this.abbreviation = abbreviation;
        this.direction    = direction;
        this.angular      = angular;
        this.unitScale    = unitScale;
    }

    /**
     * Returns the same axis but with a unit of measurement multiplied by the given scale.
     */
    final Axis withUnit(final double scale) {
        if (scale == unitScale) {
            return this;
        }
        return new Axis(name, abbreviation, direction, angular, scale);
    }

    /**
     * {@return an arbitrary value suitable for string representation}.
     */
    @Override
    Object implementation() {
        return name;
    }

    /**
     * {@return the axis name}.
     */
    @Override
    public String getCode() {
        return name;
    }

    /**
     * {@return the axis abbreviation}.
     */
    @Override
    public String getAbbreviation() {
        return abbreviation;
    }

    @Override
    public AxisDirection getDirection() {
        return DIRECTIONS[direction + 3];
    }

    @Override
    public double getMinimumValue() {
        return Double.NEGATIVE_INFINITY;
    }

    @Override
    public double getMaximumValue() {
        return Double.POSITIVE_INFINITY;
    }

    @Override
    public RangeMeaning getRangeMeaning() {
        return angular && (Math.abs(direction) == 1) ? RangeMeaning.WRAPAROUND : RangeMeaning.EXACT;
    }

    @Override
    public Unit<?> getUnit() {
        if (unit == null) {
            final Units units = Units.getInstance();
            unit = (angular ? units.degree : units.metre).multiply(unitScale);
        }
        return unit;
    }
}

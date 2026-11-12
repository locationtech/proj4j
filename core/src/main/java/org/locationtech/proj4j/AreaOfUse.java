/*
 * Copyright 2026 PROJ4J contributors
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
package org.locationtech.proj4j;

import java.io.Serializable;

/**
 * The geographic area in which a coordinate reference system is intended to be used.
 *
 * <p>Longitudes and latitudes are in decimal degrees, relative to Greenwich and the
 * equator. Bounds may cross the antimeridian, in which case the west longitude is
 * greater than the east longitude.</p>
 */
public final class AreaOfUse implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String name;
    private final double westLongitude;
    private final double southLatitude;
    private final double eastLongitude;
    private final double northLatitude;

    public AreaOfUse(String name, double westLongitude, double southLatitude,
                     double eastLongitude, double northLatitude) {
        this.name = name;
        this.westLongitude = westLongitude;
        this.southLatitude = southLatitude;
        this.eastLongitude = eastLongitude;
        this.northLatitude = northLatitude;
    }

    /**
     * Gets a description of the area of use.
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the westernmost longitude, in decimal degrees.
     */
    public double getWestLongitude() {
        return westLongitude;
    }

    /**
     * Gets the southernmost latitude, in decimal degrees.
     */
    public double getSouthLatitude() {
        return southLatitude;
    }

    /**
     * Gets the easternmost longitude, in decimal degrees.
     */
    public double getEastLongitude() {
        return eastLongitude;
    }

    /**
     * Gets the northernmost latitude, in decimal degrees.
     */
    public double getNorthLatitude() {
        return northLatitude;
    }
}

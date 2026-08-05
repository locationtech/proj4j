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

import java.util.Collections;
import java.util.List;

/**
 * A coordinate system: its kind, its dimension, and its axes in authority order.
 */
public final class DbCoordinateSystem {

    private final String authName;
    private final String code;
    private final String type;
    private final int dimension;
    private final List<DbAxis> axes;

    public DbCoordinateSystem(String authName, String code, String type, int dimension,
                              List<DbAxis> axes) {
        this.authName = authName;
        this.code = code;
        this.type = type;
        this.dimension = dimension;
        this.axes = axes == null
                ? Collections.<DbAxis>emptyList()
                : Collections.unmodifiableList(axes);
    }

    public String authName() {
        return authName;
    }

    public String code() {
        return code;
    }

    /**
     * One of {@code Cartesian}, {@code vertical}, {@code ellipsoidal}, {@code spherical},
     * {@code ordinal} — a closed upstream vocabulary, carried as a string so a future addition is data
     * rather than a compile break.
     */
    public String type() {
        return type;
    }

    /**
     * 1, 2 or 3. The single fact a {@code +init=} dictionary cannot express, and therefore the reason
     * {@code EPSG:4979} needs this database.
     */
    public int dimension() {
        return dimension;
    }

    /**
     * Axes ordered by {@link DbAxis#order()} ascending. Unmodifiable.
     */
    public List<DbAxis> axes() {
        return axes;
    }

    @Override
    public String toString() {
        return authName + ":" + code + " " + type + "[" + dimension + "]";
    }
}

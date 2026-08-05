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
 * A celestial body. 176 of them exist in the shipped database, and 2,201 of the 13,790 CRSs are
 * IAU_2015 definitions that are not on Earth.
 * <p>
 * This is a filter, not decoration: an operation search that ignores the body will happily offer a
 * Helmert transformation between an Earth datum and a Martian one.
 */
public final class DbCelestialBody {

    private final String authName;
    private final String code;
    private final String name;
    private final double semiMajorAxis;

    public DbCelestialBody(String authName, String code, String name, double semiMajorAxis) {
        this.authName = authName;
        this.code = code;
        this.name = name;
        this.semiMajorAxis = semiMajorAxis;
    }

    public String authName() {
        return authName;
    }

    public String code() {
        return code;
    }

    /** {@code "Earth"}, {@code "Mars"}, &hellip; */
    public String name() {
        return name;
    }

    /** Metres, or {@link Double#NaN}. */
    public double semiMajorAxis() {
        return semiMajorAxis;
    }

    /**
     * @return {@code true} iff this is Earth, by name.
     */
    public boolean isEarth() {
        return "Earth".equals(name);
    }

    @Override
    public String toString() {
        return authName + ":" + code + " " + name;
    }
}

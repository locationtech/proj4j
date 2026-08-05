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
 * A prime meridian. {@link #longitude()} is in {@link #unit()} — several are published in grads, which
 * is why the unit is carried rather than the value normalised.
 */
public final class DbPrimeMeridian {

    private final String authName;
    private final String code;
    private final String name;
    private final double longitude;
    private final DbObjectRef unit;
    private final boolean deprecated;

    public DbPrimeMeridian(String authName, String code, String name, double longitude,
                           DbObjectRef unit, boolean deprecated) {
        this.authName = authName;
        this.code = code;
        this.name = name;
        this.longitude = longitude;
        this.unit = unit;
        this.deprecated = deprecated;
    }

    public String authName() {
        return authName;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    /** East of Greenwich, in {@link #unit()}. */
    public double longitude() {
        return longitude;
    }

    public DbObjectRef unit() {
        return unit;
    }

    public boolean deprecated() {
        return deprecated;
    }

    @Override
    public String toString() {
        return authName + ":" + code + " " + name + " " + longitude;
    }
}

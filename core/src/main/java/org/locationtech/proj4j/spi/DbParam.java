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
 * One parameter of a conversion or a transformation: its authority code, its name, its value, and its
 * unit.
 * <p>
 * The value is <strong>never</strong> pre-converted; see {@link DbUnit}. Parameter <em>identity</em> is
 * {@link #authName()}/{@link #code()}, not {@link #name()} — EPSG parameter 8801 is "Latitude of
 * natural origin" whatever a given authority chooses to call it, and matching on the display name is
 * how a parameter silently binds to the wrong slot.
 */
public final class DbParam {

    private final String authName;
    private final String code;
    private final String name;
    private final double value;
    private final DbObjectRef unit;

    public DbParam(String authName, String code, String name, double value, DbObjectRef unit) {
        this.authName = authName;
        this.code = code;
        this.name = name;
        this.value = value;
        this.unit = unit;
    }

    /** The authority that defines the parameter, e.g. {@code "EPSG"}. */
    public String authName() {
        return authName;
    }

    /** {@code "8801"}. The stable identity of this parameter. */
    public String code() {
        return code;
    }

    /** {@code "Latitude of natural origin"}. Display only. */
    public String name() {
        return name;
    }

    /** In {@link #unit()}. */
    public double value() {
        return value;
    }

    /** The unit the value is expressed in; may be null where the authority left it unset. */
    public DbObjectRef unit() {
        return unit;
    }

    @Override
    public String toString() {
        return authName + ":" + code + " " + name + " = " + value
                + (unit == null ? "" : " " + unit.authorityCode());
    }
}

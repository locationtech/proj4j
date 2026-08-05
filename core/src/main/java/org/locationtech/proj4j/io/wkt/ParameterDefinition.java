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
 * One operation parameter: a name, a value, and the unit the value is expressed in.
 * <p>
 * WKT1's {@code PARAMETER["false_easting",500000]} carries no unit and is interpreted in the
 * CRS's own linear or angular unit, which is why the unit is resolved when the parameter is read
 * rather than when it is used.
 */
public final class ParameterDefinition {

    private String name;
    private double value;
    private UnitDefinition unit;
    private Identifier id;

    public ParameterDefinition() {
    }

    public ParameterDefinition(String name, double value, UnitDefinition unit) {
        this.name = name;
        this.value = value;
        this.unit = unit;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * The value in {@link #getUnit()}.
     */
    public double getValue() {
        return value;
    }

    public void setValue(double value) {
        this.value = value;
    }

    public UnitDefinition getUnit() {
        return unit;
    }

    public void setUnit(UnitDefinition unit) {
        this.unit = unit;
    }

    public Identifier getId() {
        return id;
    }

    public void setId(Identifier id) {
        this.id = id;
    }

    /**
     * The value in degrees, for an angular parameter.
     */
    public double getValueDegrees() {
        if (unit == null || unit.getType() != UnitDefinition.ANGULAR) {
            return value;
        }
        // Scaled by the ratio of the two factors rather than through radians, so that a value
        // already in degrees comes back bit-for-bit: 3 must stay 3, not become 3.0000000000000004.
        double ratio = unit.getConversionFactor() / UnitDefinition.DEGREE.getConversionFactor();
        return ratio == 1.0 ? value : value * ratio;
    }

    /**
     * The value in metres, for a linear parameter.
     */
    public double getValueMetres() {
        if (unit == null || unit.getType() != UnitDefinition.LINEAR) {
            return value;
        }
        return unit.toBase(value);
    }

    /**
     * The value with no unit applied, for a scale or dimensionless parameter. A scale unit which
     * is not unity (parts per million, say) is applied.
     */
    public double getValueScale() {
        if (unit == null || unit.getType() != UnitDefinition.SCALE) {
            return value;
        }
        return unit.toBase(value);
    }

    public String toString() {
        return name + "=" + value + (unit == null ? "" : " " + unit.getName());
    }
}

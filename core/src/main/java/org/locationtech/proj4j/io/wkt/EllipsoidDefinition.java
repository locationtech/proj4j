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
 * An ellipsoid as a document declares it: a semi-major axis in some linear unit, plus either an
 * inverse flattening or a semi-minor axis.
 * <p>
 * A sphere is spelled by upstream in two ways, both accepted here: an inverse flattening of 0, or
 * PROJJSON's {@code radius} member.
 */
public final class EllipsoidDefinition {

    private String name;
    private double semiMajorAxis = Double.NaN;
    private double inverseFlattening = Double.NaN;
    private double semiMinorAxis = Double.NaN;
    private UnitDefinition unit = UnitDefinition.METRE;
    private Identifier id;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * The semi-major axis, in {@link #getUnit()}.
     */
    public double getSemiMajorAxis() {
        return semiMajorAxis;
    }

    public void setSemiMajorAxis(double semiMajorAxis) {
        this.semiMajorAxis = semiMajorAxis;
    }

    /**
     * The semi-major axis converted to metres.
     */
    public double getSemiMajorAxisMetres() {
        return unit == null ? semiMajorAxis : unit.toBase(semiMajorAxis);
    }

    /**
     * The inverse flattening, or {@code NaN} if the document declared a semi-minor axis instead.
     * Zero means a sphere.
     */
    public double getInverseFlattening() {
        return inverseFlattening;
    }

    public void setInverseFlattening(double inverseFlattening) {
        this.inverseFlattening = inverseFlattening;
    }

    /**
     * The semi-minor axis in {@link #getUnit()}, or {@code NaN} if an inverse flattening was
     * declared instead.
     */
    public double getSemiMinorAxis() {
        return semiMinorAxis;
    }

    public void setSemiMinorAxis(double semiMinorAxis) {
        this.semiMinorAxis = semiMinorAxis;
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

    public boolean isSphere() {
        if (!Double.isNaN(inverseFlattening) && inverseFlattening == 0.0) {
            return true;
        }
        return !Double.isNaN(semiMinorAxis) && semiMinorAxis == semiMajorAxis;
    }

    public String toString() {
        return name + "(a=" + semiMajorAxis + (Double.isNaN(inverseFlattening)
                ? ",b=" + semiMinorAxis : ",rf=" + inverseFlattening) + ")";
    }
}

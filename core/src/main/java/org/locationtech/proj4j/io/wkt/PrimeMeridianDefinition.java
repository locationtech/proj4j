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
 * A prime meridian: a name and a longitude relative to Greenwich.
 * <p>
 * The unit matters and is a classic source of error: WKT1's {@code PRIMEM} value is in degrees
 * even when the enclosing {@code GEOGCS} declares grads, whereas WKT2's {@code PRIMEM} takes its
 * unit from its own {@code ANGLEUNIT} or, absent that, from the CRS's angular unit.
 */
public final class PrimeMeridianDefinition {

    /** Greenwich, longitude 0. */
    public static PrimeMeridianDefinition greenwich() {
        PrimeMeridianDefinition pm = new PrimeMeridianDefinition();
        pm.setName("Greenwich");
        pm.setLongitude(0);
        pm.setUnit(UnitDefinition.DEGREE);
        pm.setId(new Identifier("EPSG", "8901"));
        return pm;
    }

    private String name;
    private double longitude;
    private UnitDefinition unit = UnitDefinition.DEGREE;
    private Identifier id;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * The longitude in {@link #getUnit()}.
     */
    public double getLongitude() {
        return longitude;
    }

    public void setLongitude(double longitude) {
        this.longitude = longitude;
    }

    /**
     * The longitude converted to degrees, which is what PROJ's {@code +pm=} takes.
     */
    public double getLongitudeDegrees() {
        if (unit == null) {
            return longitude;
        }
        double ratio = unit.getConversionFactor() / UnitDefinition.DEGREE.getConversionFactor();
        return ratio == 1.0 ? longitude : longitude * ratio;
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

    public boolean isGreenwich() {
        return getLongitudeDegrees() == 0.0;
    }

    public String toString() {
        return name + "(" + longitude + ")";
    }
}

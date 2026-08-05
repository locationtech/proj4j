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

import java.util.ArrayList;
import java.util.List;

/**
 * A coordinate system: its type, its dimension and its axes.
 * <p>
 * WKT1 has no {@code CS[]} element, so one is synthesised from the {@code AXIS[]} clauses and the
 * {@code UNIT[]} of the enclosing CRS; a WKT1 CRS with no axes at all gets the conventional
 * default (easting/northing for projected, longitude/latitude for geographic), which is what PROJ
 * does too.
 */
public final class CoordinateSystemDefinition {

    /** {@code ellipsoidal}, a geographic 2D or 3D CS. */
    public static final String ELLIPSOIDAL = "ellipsoidal";
    /** {@code Cartesian}, note the capital C, as ISO 19162 spells it. */
    public static final String CARTESIAN = "Cartesian";
    public static final String VERTICAL = "vertical";
    public static final String SPHERICAL = "spherical";
    public static final String TEMPORAL = "temporal";

    private String type;
    private final List<AxisDefinition> axes = new ArrayList<AxisDefinition>();
    private UnitDefinition unit;
    private int declaredDimension = -1;

    public CoordinateSystemDefinition() {
    }

    public CoordinateSystemDefinition(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public List<AxisDefinition> getAxes() {
        return axes;
    }

    public void addAxis(AxisDefinition axis) {
        axes.add(axis);
    }

    /**
     * The default unit of the coordinate system, used for axes which declare none.
     */
    public UnitDefinition getUnit() {
        return unit;
    }

    public void setUnit(UnitDefinition unit) {
        this.unit = unit;
    }

    /**
     * The dimension declared by {@code CS[type,dimension]}, or the axis count if none was
     * declared.
     */
    public int getDimension() {
        return declaredDimension > 0 ? declaredDimension : axes.size();
    }

    public void setDeclaredDimension(int dimension) {
        this.declaredDimension = dimension;
    }

    /**
     * The unit of axis {@code index}, falling back to the coordinate system unit.
     */
    public UnitDefinition unitOf(int index) {
        if (index >= 0 && index < axes.size()) {
            UnitDefinition u = axes.get(index).getUnit();
            if (u != null) {
                return u;
            }
        }
        return unit;
    }

    /**
     * Whether the axes are in the order proj4j uses internally: horizontal x (east or west)
     * before horizontal y (north or south). True when there are fewer than two axes, since then
     * there is nothing to reorder.
     */
    public boolean isXBeforeY() {
        int x = -1;
        int y = -1;
        for (int i = 0; i < axes.size(); i++) {
            AxisDefinition a = axes.get(i);
            if (x < 0 && a.isHorizontalX()) {
                x = i;
            } else if (y < 0 && a.isHorizontalY()) {
                y = i;
            }
        }
        return x < 0 || y < 0 || x < y;
    }

    public String toString() {
        return type + "[" + getDimension() + "]" + axes;
    }
}

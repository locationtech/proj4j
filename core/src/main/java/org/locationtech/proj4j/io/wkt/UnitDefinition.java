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
 * A unit of measure: a name, a conversion factor to the unit's base SI unit, and a type.
 * <p>
 * The conversion factor is what WKT carries and is authoritative here — a
 * {@code UNIT["Foot_US",0.304800609601219]} is honoured by its factor even if the name is
 * unfamiliar, which is what makes ESRI WKT usable. Named constants match PROJ's
 * {@code UnitOfMeasure} constants (9.8.1 {@code src/iso19111/common.cpp}).
 */
public final class UnitDefinition {

    public static final int ANGULAR = 0;
    public static final int LINEAR = 1;
    public static final int SCALE = 2;
    public static final int TIME = 3;
    public static final int PARAMETRIC = 4;

    /** radian, factor 1. */
    public static final UnitDefinition RADIAN =
            new UnitDefinition("radian", 1.0, ANGULAR, new Identifier("EPSG", "9101"));
    /** degree, factor pi/180. */
    public static final UnitDefinition DEGREE =
            new UnitDefinition("degree", Math.PI / 180.0, ANGULAR, new Identifier("EPSG", "9122"));
    /** arc-second, factor pi/648000. */
    public static final UnitDefinition ARC_SECOND =
            new UnitDefinition("arc-second", Math.PI / 648000.0, ANGULAR,
                    new Identifier("EPSG", "9104"));
    /** grad, factor pi/200. */
    public static final UnitDefinition GRAD =
            new UnitDefinition("grad", Math.PI / 200.0, ANGULAR, new Identifier("EPSG", "9105"));
    /** metre, factor 1. */
    public static final UnitDefinition METRE =
            new UnitDefinition("metre", 1.0, LINEAR, new Identifier("EPSG", "9001"));
    /** international foot, factor 0.3048. */
    public static final UnitDefinition FOOT =
            new UnitDefinition("foot", 0.3048, LINEAR, new Identifier("EPSG", "9002"));
    /** US survey foot, factor 12/39.37. */
    public static final UnitDefinition US_SURVEY_FOOT =
            new UnitDefinition("US survey foot", 12.0 / 39.37, LINEAR,
                    new Identifier("EPSG", "9003"));
    /** unity, the scale unit, factor 1. */
    public static final UnitDefinition UNITY =
            new UnitDefinition("unity", 1.0, SCALE, new Identifier("EPSG", "9201"));
    /** parts per million, factor 1e-6. */
    public static final UnitDefinition PPM =
            new UnitDefinition("parts per million", 1e-6, SCALE, new Identifier("EPSG", "9202"));
    /** year, the temporal unit used by dynamic datum frame epochs. */
    public static final UnitDefinition YEAR =
            new UnitDefinition("year", 31556925.445, TIME, new Identifier("EPSG", "1029"));

    private final String name;
    private final double conversionFactor;
    private final int type;
    private final Identifier id;

    public UnitDefinition(String name, double conversionFactor, int type) {
        this(name, conversionFactor, type, null);
    }

    public UnitDefinition(String name, double conversionFactor, int type, Identifier id) {
        this.name = name;
        this.conversionFactor = conversionFactor;
        this.type = type;
        this.id = id;
    }

    public String getName() {
        return name;
    }

    /**
     * The factor which converts a value in this unit to the base unit: radians for
     * {@link #ANGULAR}, metres for {@link #LINEAR}, unity for {@link #SCALE}.
     */
    public double getConversionFactor() {
        return conversionFactor;
    }

    /**
     * One of {@link #ANGULAR}, {@link #LINEAR}, {@link #SCALE}, {@link #TIME},
     * {@link #PARAMETRIC}.
     */
    public int getType() {
        return type;
    }

    public Identifier getId() {
        return id;
    }

    /**
     * Converts {@code value}, expressed in this unit, to the base unit.
     */
    public double toBase(double value) {
        return value * conversionFactor;
    }

    /**
     * Converts {@code value}, expressed in the base unit, to this unit.
     */
    public double fromBase(double value) {
        return value / conversionFactor;
    }

    public String toString() {
        return name + "(" + conversionFactor + ")";
    }
}

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
 * A unit of measure, with the conversion factor to the SI base unit for its {@link #type()}: metres
 * for {@code length}, radians for {@code angle}, unity for {@code scale}, seconds for {@code time}.
 * <p>
 * Every parameter value this SPI returns is accompanied by its unit reference and is <strong>not</strong>
 * pre-converted. Silently normalising would hide exactly the class of defect that motivates the work:
 * a value in grads or in US survey feet that reads plausibly as radians or metres.
 */
public final class DbUnit {

    /** {@code unit_of_measure.type}, a closed upstream vocabulary. */
    public enum Type {
        LENGTH("length"), ANGLE("angle"), SCALE("scale"), TIME("time");

        private final String dbValue;

        Type(String dbValue) {
            this.dbValue = dbValue;
        }

        public String dbValue() {
            return dbValue;
        }

        public static Type fromDbValue(String v) {
            if (v != null) {
                for (Type t : values()) {
                    if (t.dbValue.equals(v)) {
                        return t;
                    }
                }
            }
            return null;
        }
    }

    private final String authName;
    private final String code;
    private final String name;
    private final Type type;
    private final double conversionFactor;
    private final String projShortName;
    private final boolean deprecated;

    public DbUnit(String authName, String code, String name, Type type, double conversionFactor,
                  String projShortName, boolean deprecated) {
        this.authName = authName;
        this.code = code;
        this.name = name;
        this.type = type;
        this.conversionFactor = conversionFactor;
        this.projShortName = projShortName;
        this.deprecated = deprecated;
    }

    public String authName() {
        return authName;
    }

    public String code() {
        return code;
    }

    /** {@code "metre"}, {@code "US survey foot"}, {@code "degree"}. */
    public String name() {
        return name;
    }

    public Type type() {
        return type;
    }

    /**
     * Factor to the SI base unit, or {@link Double#NaN} for the 11 units upstream leaves null (unit
     * codes with no defined ratio, such as {@code EPSG:9203} "coefficient" variants). NaN rather than
     * 1.0, because a defaulted factor of one is indistinguishable from a real one and would multiply
     * silently.
     */
    public double conversionFactor() {
        return conversionFactor;
    }

    /**
     * @return {@code true} iff {@link #conversionFactor()} is a real number.
     */
    public boolean hasConversionFactor() {
        return !Double.isNaN(conversionFactor);
    }

    /**
     * PROJ's own short spelling — {@code "m"}, {@code "ft"}, {@code "us-ft"} — or null. This is the
     * token that goes into a {@code +units=} or {@code +xy_out=}, so it is the bridge between the
     * database and the pipeline engine.
     */
    public String projShortName() {
        return projShortName;
    }

    public boolean deprecated() {
        return deprecated;
    }

    @Override
    public String toString() {
        return authName + ":" + code + " " + name + " (" + type + " x"
                + (hasConversionFactor() ? Double.toString(conversionFactor) : "?") + ")";
    }
}

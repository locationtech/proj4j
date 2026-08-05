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
 * An ellipsoid, in exactly the two-of-three form upstream stores it.
 * <p>
 * Upstream enforces {@code inv_flattening} and {@code semi_minor_axis} as <strong>mutually
 * exclusive</strong> — a {@code CHECK} constraint requires exactly one to be non-null. That is
 * preserved here rather than being resolved into both, because deriving the missing one and then
 * re-deriving the first from it is precisely how the {@code +rf}/{@code +f} transposition defect
 * produced a latitude of &minus;3.3e205&deg;. The caller sees which of the two the authority actually
 * published.
 * <p>
 * {@link #semiMajorAxis()} is in {@link #unit()}, which is <em>not</em> always metres: some IAU_2015
 * bodies and older authorities publish in other length units.
 */
public final class DbEllipsoid {

    private final String authName;
    private final String code;
    private final String name;
    private final DbObjectRef celestialBody;
    private final double semiMajorAxis;
    private final DbObjectRef unit;
    private final double inverseFlattening;
    private final double semiMinorAxis;
    private final boolean deprecated;

    public DbEllipsoid(String authName, String code, String name, DbObjectRef celestialBody,
                       double semiMajorAxis, DbObjectRef unit, double inverseFlattening,
                       double semiMinorAxis, boolean deprecated) {
        this.authName = authName;
        this.code = code;
        this.name = name;
        this.celestialBody = celestialBody;
        this.semiMajorAxis = semiMajorAxis;
        this.unit = unit;
        this.inverseFlattening = inverseFlattening;
        this.semiMinorAxis = semiMinorAxis;
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

    /**
     * Earth is {@code EPSG:PROJ:1027}-ish in name only; in this database it is
     * {@code celestial_body} {@code PROJ:EARTH}. 176 bodies exist and 2,201 of the CRSs are IAU_2015
     * definitions on other bodies, so a caller that assumes Earth must check.
     */
    public DbObjectRef celestialBody() {
        return celestialBody;
    }

    /** In {@link #unit()}, not necessarily metres. */
    public double semiMajorAxis() {
        return semiMajorAxis;
    }

    public DbObjectRef unit() {
        return unit;
    }

    /**
     * {@code 1/f}, or {@link Double#NaN} if the authority published a semi-minor axis instead. A value
     * of exactly {@code 0} means a sphere, which upstream permits explicitly
     * ({@code CHECK (inv_flattening = 0 OR inv_flattening >= 1.0)}).
     */
    public double inverseFlattening() {
        return inverseFlattening;
    }

    /** In {@link #unit()}, or {@link Double#NaN} if the authority published an inverse flattening. */
    public double semiMinorAxis() {
        return semiMinorAxis;
    }

    public boolean deprecated() {
        return deprecated;
    }

    /**
     * @return {@code true} iff this is a sphere by either parameterisation.
     */
    public boolean isSphere() {
        return inverseFlattening == 0.0 || semiMinorAxis == semiMajorAxis;
    }

    @Override
    public String toString() {
        return authName + ":" + code + " " + name + " a=" + semiMajorAxis
                + (Double.isNaN(inverseFlattening) ? " b=" + semiMinorAxis : " rf=" + inverseFlattening);
    }
}

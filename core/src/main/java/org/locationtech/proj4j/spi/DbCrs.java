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
 * One row of the database's CRS union, flattened.
 * <p>
 * Deliberately one class with nullable accessors rather than five subclasses: the facade's job is to
 * branch on {@link #type()} once and build a {@code Crs}, and a type hierarchy would only add casts to
 * that. Which accessors are populated is a function of {@link #type()} alone:
 *
 * <table>
 *   <caption>populated accessors by CRS type</caption>
 *   <tr><th>type</th><th>populated</th></tr>
 *   <tr><td>{@code GEOGRAPHIC_2D}, {@code GEOGRAPHIC_3D}, {@code GEOCENTRIC},
 *           {@code GEODETIC_OTHER}</td>
 *       <td>{@link #coordinateSystem()}, {@link #datum()}</td></tr>
 *   <tr><td>{@code PROJECTED}</td>
 *       <td>{@link #coordinateSystem()}, {@link #baseCrs()}, {@link #conversion()}</td></tr>
 *   <tr><td>{@code VERTICAL}</td><td>{@link #coordinateSystem()}, {@link #datum()}</td></tr>
 *   <tr><td>{@code COMPOUND}</td>
 *       <td>{@link #horizontalCrs()}, {@link #verticalCrs()}</td></tr>
 *   <tr><td>{@code ENGINEERING}</td><td>name and deprecation only</td></tr>
 * </table>
 *
 * {@link #textDefinition()} is upstream's escape hatch: 173 projected CRSs and no geodetic ones carry a
 * PROJ or WKT string in place of a structured definition, and upstream's own schema comment calls its
 * use <em>discouraged, as prone to definition ambiguities</em>. It is surfaced rather than dropped so
 * the facade can decide, but a structured definition should always win where both exist.
 */
public final class DbCrs {

    private final DbCrsType type;
    private final String authName;
    private final String code;
    private final String name;
    private final boolean deprecated;
    private final DbObjectRef coordinateSystem;
    private final DbObjectRef datum;
    private final DbObjectRef baseCrs;
    private final DbObjectRef conversion;
    private final DbObjectRef horizontalCrs;
    private final DbObjectRef verticalCrs;
    private final String textDefinition;

    public DbCrs(DbCrsType type, String authName, String code, String name, boolean deprecated,
                 DbObjectRef coordinateSystem, DbObjectRef datum, DbObjectRef baseCrs,
                 DbObjectRef conversion, DbObjectRef horizontalCrs, DbObjectRef verticalCrs,
                 String textDefinition) {
        if (type == null || authName == null || code == null || name == null) {
            throw new IllegalArgumentException("type, authName, code and name are mandatory");
        }
        this.type = type;
        this.authName = authName;
        this.code = code;
        this.name = name;
        this.deprecated = deprecated;
        this.coordinateSystem = coordinateSystem;
        this.datum = datum;
        this.baseCrs = baseCrs;
        this.conversion = conversion;
        this.horizontalCrs = horizontalCrs;
        this.verticalCrs = verticalCrs;
        this.textDefinition = textDefinition;
    }

    public DbCrsType type() {
        return type;
    }

    public String authName() {
        return authName;
    }

    public String code() {
        return code;
    }

    /**
     * {@code "WGS 84"}. Never null; the upstream schema requires at least two characters.
     */
    public String name() {
        return name;
    }

    /**
     * {@code crs_view.deprecated}. A deprecated CRS is still returned by lookups — refusing it here
     * would turn a lookup of a code that exists into {@code UNKNOWN_CRS}, which is a different and
     * worse answer than "exists, superseded, here is the replacement".
     */
    public boolean deprecated() {
        return deprecated;
    }

    /** Null for {@code COMPOUND} and {@code ENGINEERING}. */
    public DbObjectRef coordinateSystem() {
        return coordinateSystem;
    }

    /**
     * The geodetic datum for a geodetic CRS, the vertical datum for a vertical CRS; null otherwise.
     * Check {@link DbObjectRef#type()} rather than assuming.
     */
    public DbObjectRef datum() {
        return datum;
    }

    /** The base geodetic CRS of a projected CRS; null otherwise. */
    public DbObjectRef baseCrs() {
        return baseCrs;
    }

    /** The map projection of a projected CRS; null otherwise. */
    public DbObjectRef conversion() {
        return conversion;
    }

    /** The horizontal component of a compound CRS; null otherwise. */
    public DbObjectRef horizontalCrs() {
        return horizontalCrs;
    }

    /** The vertical component of a compound CRS; null otherwise. */
    public DbObjectRef verticalCrs() {
        return verticalCrs;
    }

    /**
     * A PROJ string or a WKT string, or null. See the class comment: present for 173 projected CRSs
     * and used by upstream only where a structured definition is unavailable.
     */
    public String textDefinition() {
        return textDefinition;
    }

    /**
     * This CRS as a reference, for feeding back into {@link ProjDatabase#extentsFor(DbObjectRef)} and friends.
     */
    public DbObjectRef ref() {
        return new DbObjectRef(type.objectType(), authName, code);
    }

    @Override
    public String toString() {
        return authName + ":" + code + " (" + type.dbValue() + ") " + name
                + (deprecated ? " [deprecated]" : "");
    }
}

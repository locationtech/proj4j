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
 * The kinds of object a {@link ProjDatabase} can be asked about.
 * <p>
 * The constants mirror, one for one, the {@code table_name} vocabulary that PROJ's own database uses
 * in its {@code usage}, {@code alias_name}, {@code supersession} and {@code deprecation} tables. That
 * vocabulary is a closed {@code CHECK} constraint upstream, so it is reproduced verbatim rather than
 * re-invented: {@link #dbName()} returns the exact upstream spelling, which is what makes a lookup
 * key in this API and a row in the shipped index the same thing.
 * <p>
 * Note that {@code helmert_transformation} is the name of an upstream <em>view</em>; its backing table
 * is {@code helmert_transformation_table}. The view name is the one used as a key, again matching
 * upstream.
 */
public enum DbObjectType {

    UNIT_OF_MEASURE("unit_of_measure"),
    CELESTIAL_BODY("celestial_body"),
    ELLIPSOID("ellipsoid"),
    EXTENT("extent"),
    PRIME_MERIDIAN("prime_meridian"),
    GEODETIC_DATUM("geodetic_datum"),
    VERTICAL_DATUM("vertical_datum"),
    ENGINEERING_DATUM("engineering_datum"),
    GEODETIC_CRS("geodetic_crs"),
    PROJECTED_CRS("projected_crs"),
    VERTICAL_CRS("vertical_crs"),
    COMPOUND_CRS("compound_crs"),
    ENGINEERING_CRS("engineering_crs"),
    COORDINATE_SYSTEM("coordinate_system"),
    CONVERSION("conversion"),
    GRID_TRANSFORMATION("grid_transformation"),
    HELMERT_TRANSFORMATION("helmert_transformation"),
    OTHER_TRANSFORMATION("other_transformation"),
    CONCATENATED_OPERATION("concatenated_operation");

    private final String dbName;

    DbObjectType(String dbName) {
        this.dbName = dbName;
    }

    /**
     * The upstream {@code proj.db} table name, e.g. {@code "projected_crs"}.
     */
    public String dbName() {
        return dbName;
    }

    /**
     * @return {@code true} for the five CRS types.
     */
    public boolean isCrs() {
        return this == GEODETIC_CRS || this == PROJECTED_CRS || this == VERTICAL_CRS
                || this == COMPOUND_CRS || this == ENGINEERING_CRS;
    }

    /**
     * @return {@code true} for the four coordinate-operation types. A {@link #CONVERSION} is
     *         deliberately <em>not</em> one of them: upstream models it as the map projection half of a
     *         projected CRS, not as a CRS-to-CRS transformation, and it never appears in
     *         {@code coordinate_operation_view}.
     */
    public boolean isOperation() {
        return this == GRID_TRANSFORMATION || this == HELMERT_TRANSFORMATION
                || this == OTHER_TRANSFORMATION || this == CONCATENATED_OPERATION;
    }

    /**
     * Reverse of {@link #dbName()}.
     *
     * @return the matching constant, or {@code null} if {@code dbName} is not one of the 19 upstream
     *         table names. {@code null} rather than an exception because an unknown table name in a
     *         future database revision is data, not a programming error.
     */
    public static DbObjectType fromDbName(String dbName) {
        if (dbName == null) {
            return null;
        }
        for (DbObjectType t : values()) {
            if (t.dbName.equals(dbName)) {
                return t;
            }
        }
        return null;
    }
}

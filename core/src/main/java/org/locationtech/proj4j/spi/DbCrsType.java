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
 * The CRS flavours the database distinguishes.
 * <p>
 * {@link #GEOGRAPHIC_3D} is the reason this enum exists rather than a boolean. A PROJ.4
 * {@code +init=} dictionary structurally cannot express it — {@code projinfo EPSG:4979 -o PROJ} is
 * byte-identical to {@code EPSG:4326}'s entry — so {@code EPSG:4979}, {@code EPSG:4937} and the
 * vertical codes {@code 5773}, {@code 3855}, {@code 5798}, {@code 5714}, {@code 5715}, {@code 5703}
 * and {@code 6357} are simply absent without a database. The dimension lives in the
 * {@code coordinate_system}, and that is only reachable through here.
 */
public enum DbCrsType {

    /** {@code geodetic_crs.type = 'geographic 2D'}. */
    GEOGRAPHIC_2D("geographic 2D"),
    /** {@code geodetic_crs.type = 'geographic 3D'}. */
    GEOGRAPHIC_3D("geographic 3D"),
    /** {@code geodetic_crs.type = 'geocentric'}. */
    GEOCENTRIC("geocentric"),
    /** {@code geodetic_crs.type = 'other'}. */
    GEODETIC_OTHER("other"),
    PROJECTED("projected"),
    VERTICAL("vertical"),
    COMPOUND("compound"),
    ENGINEERING("engineering");

    private final String dbValue;

    DbCrsType(String dbValue) {
        this.dbValue = dbValue;
    }

    /**
     * The upstream string, as it appears in {@code crs_view.type}.
     */
    public String dbValue() {
        return dbValue;
    }

    /**
     * @return the {@link DbObjectType} whose table holds CRSs of this flavour.
     */
    public DbObjectType objectType() {
        switch (this) {
            case PROJECTED:
                return DbObjectType.PROJECTED_CRS;
            case VERTICAL:
                return DbObjectType.VERTICAL_CRS;
            case COMPOUND:
                return DbObjectType.COMPOUND_CRS;
            case ENGINEERING:
                return DbObjectType.ENGINEERING_CRS;
            default:
                return DbObjectType.GEODETIC_CRS;
        }
    }

    /**
     * @return {@code true} for the three geodetic flavours that carry a geodetic datum and can act as
     *         the base of a projected CRS.
     */
    public boolean isGeodetic() {
        return this == GEOGRAPHIC_2D || this == GEOGRAPHIC_3D || this == GEOCENTRIC
                || this == GEODETIC_OTHER;
    }

    public static DbCrsType fromDbValue(String v) {
        if (v == null) {
            return null;
        }
        for (DbCrsType t : values()) {
            if (t.dbValue.equals(v)) {
                return t;
            }
        }
        return null;
    }
}

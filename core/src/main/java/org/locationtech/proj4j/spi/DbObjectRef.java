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
 * An authority-qualified reference to a database object: {@code (type, authName, code)}.
 * <p>
 * Codes are carried as {@link String} because upstream declares them {@code INTEGER_OR_TEXT} and
 * really uses both — 1,803 of the 2,148 geodetic CRSs have integer codes, 345 do not, and the IGNF and
 * NKG authorities are text-coded throughout ({@code NKG:DK_2020_INTRAPLATE}). A numeric code type
 * would silently exclude them.
 * <p>
 * Immutable, and totally ordered by {@code (type, authName, code)} so that any list of references this
 * SPI returns can be sorted into one canonical sequence. That total order is load-bearing: candidate
 * enumeration must not depend on hash iteration order or on which index a row happened to be found
 * through.
 */
public final class DbObjectRef implements Comparable<DbObjectRef> {

    private final DbObjectType type;
    private final String authName;
    private final String code;

    public DbObjectRef(DbObjectType type, String authName, String code) {
        if (type == null) {
            throw new IllegalArgumentException("type");
        }
        if (authName == null) {
            throw new IllegalArgumentException("authName");
        }
        if (code == null) {
            throw new IllegalArgumentException("code");
        }
        this.type = type;
        this.authName = authName;
        this.code = code;
    }

    public DbObjectType type() {
        return type;
    }

    public String authName() {
        return authName;
    }

    public String code() {
        return code;
    }

    /**
     * {@code "EPSG:4326"} — authority and code only, without the type. This is the spelling a caller
     * typed and the spelling an error message should use.
     */
    public String authorityCode() {
        return authName + ":" + code;
    }

    @Override
    public int compareTo(DbObjectRef o) {
        int c = type.compareTo(o.type);
        if (c != 0) {
            return c;
        }
        c = authName.compareTo(o.authName);
        if (c != 0) {
            return c;
        }
        return code.compareTo(o.code);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DbObjectRef)) {
            return false;
        }
        DbObjectRef r = (DbObjectRef) o;
        return type == r.type && authName.equals(r.authName) && code.equals(r.code);
    }

    @Override
    public int hashCode() {
        int h = type.hashCode();
        h = 31 * h + authName.hashCode();
        h = 31 * h + code.hashCode();
        return h;
    }

    @Override
    public String toString() {
        return type.dbName() + ":" + authName + ":" + code;
    }
}

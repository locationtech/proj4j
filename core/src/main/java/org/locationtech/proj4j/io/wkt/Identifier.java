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
 * An authority name and code, as carried by WKT2's {@code ID[]}, WKT1's {@code AUTHORITY[]} and
 * PROJJSON's {@code id} member.
 */
public final class Identifier {

    private final String authority;
    private final String code;

    public Identifier(String authority, String code) {
        this.authority = authority;
        this.code = code;
    }

    public String getAuthority() {
        return authority;
    }

    public String getCode() {
        return code;
    }

    /**
     * {@code authority:code}, the form {@code CRSFactory.createFromName} accepts.
     */
    public String toString() {
        return authority + ":" + code;
    }

    public boolean equals(Object o) {
        if (!(o instanceof Identifier)) {
            return false;
        }
        Identifier that = (Identifier) o;
        return eq(authority, that.authority) && eq(code, that.code);
    }

    public int hashCode() {
        return (authority == null ? 0 : authority.hashCode()) * 31
                + (code == null ? 0 : code.hashCode());
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}

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

import java.util.Collections;
import java.util.List;

/**
 * The map projection half of a projected CRS: a method plus up to seven parameters.
 * <p>
 * A conversion is not a CRS-to-CRS operation and never appears in the operation search; it is reached
 * through {@link DbCrs#conversion()}. The method is an authority-coded operation method — EPSG 9807 is
 * Transverse Mercator, 9802 Lambert Conic Conformal (2SP) — which is the mapping the facade turns into
 * a {@code +proj=} plus its parameters.
 */
public final class DbConversion {

    private final String authName;
    private final String code;
    private final String name;
    private final String methodAuthName;
    private final String methodCode;
    private final String methodName;
    private final List<DbParam> parameters;
    private final boolean deprecated;

    public DbConversion(String authName, String code, String name, String methodAuthName,
                        String methodCode, String methodName, List<DbParam> parameters,
                        boolean deprecated) {
        this.authName = authName;
        this.code = code;
        this.name = name;
        this.methodAuthName = methodAuthName;
        this.methodCode = methodCode;
        this.methodName = methodName;
        this.parameters = parameters == null
                ? Collections.<DbParam>emptyList()
                : Collections.unmodifiableList(parameters);
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

    /** May be null: a handful of upstream conversions have no method. */
    public String methodAuthName() {
        return methodAuthName;
    }

    /** {@code "9807"}. May be null. */
    public String methodCode() {
        return methodCode;
    }

    /** {@code "Transverse Mercator"}. May be null. */
    public String methodName() {
        return methodName;
    }

    /**
     * Present parameters only, in upstream slot order ({@code param1}&hellip;{@code param7}), with
     * absent slots omitted rather than represented by a null entry. Unmodifiable.
     */
    public List<DbParam> parameters() {
        return parameters;
    }

    public boolean deprecated() {
        return deprecated;
    }

    @Override
    public String toString() {
        return authName + ":" + code + " " + name + " [" + methodName + "]";
    }
}

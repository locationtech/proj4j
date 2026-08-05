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
 * A map projection: the {@code CONVERSION[]} of a WKT2 {@code PROJCRS}, or the
 * {@code PROJECTION[]} plus {@code PARAMETER[]}s of a WKT1 {@code PROJCS}.
 */
public final class ConversionDefinition {

    private String name;
    private String methodName;
    private Identifier methodId;
    private final List<ParameterDefinition> parameters = new ArrayList<ParameterDefinition>();
    private Identifier id;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * The operation method name, as the document spelled it: an EPSG method name in WKT2, a
     * GDAL/OGC {@code PROJECTION} name in WKT1, or an ESRI one in ESRI WKT.
     */
    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public Identifier getMethodId() {
        return methodId;
    }

    public void setMethodId(Identifier methodId) {
        this.methodId = methodId;
    }

    public List<ParameterDefinition> getParameters() {
        return parameters;
    }

    public void addParameter(ParameterDefinition parameter) {
        parameters.add(parameter);
    }

    /**
     * The parameter whose name matches {@code name} ignoring case, spaces and underscores, or
     * {@code null}.
     */
    public ParameterDefinition getParameter(String name) {
        for (int i = 0; i < parameters.size(); i++) {
            ParameterDefinition p = parameters.get(i);
            if (WktNames.equalsRelaxed(p.getName(), name)) {
                return p;
            }
        }
        return null;
    }

    public Identifier getId() {
        return id;
    }

    public void setId(Identifier id) {
        this.id = id;
    }

    public String toString() {
        return methodName + parameters;
    }
}

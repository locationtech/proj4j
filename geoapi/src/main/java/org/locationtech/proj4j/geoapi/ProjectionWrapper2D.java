/*
 * Copyright 2025, PROJ4J contributors
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
package org.locationtech.proj4j.geoapi;

import org.locationtech.proj4j.CoordinateTransform;
import org.opengis.parameter.ParameterValueGroup;
import org.opengis.referencing.operation.OperationMethod;


/**
 * Wraps a PROJ4J transform behind the equivalent GeoAPI interface for the two-dimensional case of a map projection.
 * The source CRS must be geographic and the target CRS must be projected.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
@SuppressWarnings("serial")
final class ProjectionWrapper2D extends TransformWrapper2D implements org.opengis.referencing.operation.Projection {
    /**
     * Creates a new wrapper for the given PROJ4J implementation.
     */
    ProjectionWrapper2D(final CoordinateTransform impl) {
        super(impl);
    }

    /**
     * {@return a description of the map projection}.
     */
    @Override
    public OperationMethod getMethod() {
        return OperationMethodWrapper.wrapTarget(impl);
    }

    /**
     * {@return the parameters of the map projection}.
     * In this implementation, this is provided by the same class as the description.
     */
    @Override
    public ParameterValueGroup getParameterValues() {
        return OperationMethodWrapper.wrapTarget(impl);
    }
}

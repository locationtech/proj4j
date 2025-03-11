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

import org.locationtech.proj4j.BasicCoordinateTransform;
import org.opengis.referencing.crs.GeographicCRS;
import org.opengis.referencing.crs.ProjectedCRS;
import org.opengis.referencing.cs.CartesianCS;
import org.opengis.referencing.operation.Projection;


/**
 * Wraps a PROJ4J implementation behind the equivalent GeoAPI interface.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
@SuppressWarnings("serial")
final class ProjectedCRSWrapper extends AbstractCRS implements CartesianCS, ProjectedCRS {
    /**
     * The conversion from the base CRS, created when first requested.
     */
    private transient Projection conversionFromBase;

    /**
     * Creates a new wrapper for the given PROJ4J implementation.
     */
    ProjectedCRSWrapper(org.locationtech.proj4j.CoordinateReferenceSystem impl, boolean is3D) {
        super(impl, is3D);
    }

    /**
     * {@return the coordinate system, which is implemented by the same class for convenience}.
     */
    @Override
    public CartesianCS getCoordinateSystem() {
        clearAxisCache();
        return this;
    }

    @Override
    final Axis[] axesForAllDirections() {
        return Axis.PROJECTED;
    }

    /**
     * {@return the base CRS of this projected CRS}.
     */
    @Override
    public GeographicCRS getBaseCRS() {
        return (GeographicCRS) getConversionFromBase().getSourceCRS();
    }

    /**
     * {@return the conversion from the base CRS to this projected CRS}.
     */
    @Override
    public synchronized Projection getConversionFromBase() {
        if (conversionFromBase == null) {
            BasicCoordinateTransform tr = new BasicCoordinateTransform(impl.createGeographic(), impl);
            conversionFromBase = is3D ? new ProjectionWrapper3D(tr) : new ProjectionWrapper2D(tr);
        }
        return conversionFromBase;
    }
}

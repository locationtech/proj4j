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

import org.opengis.referencing.crs.GeographicCRS;
import org.opengis.referencing.cs.EllipsoidalCS;


/**
 * Wraps a PROJ4J implementation behind the equivalent GeoAPI interface.
 * The CRS is assumed two-dimensional.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
@SuppressWarnings("serial")
final class GeographicCRSWrapper extends AbstractCRS implements EllipsoidalCS, GeographicCRS {
    /**
     * Creates a new wrapper for the given PROJ4J implementation.
     */
    GeographicCRSWrapper(final org.locationtech.proj4j.CoordinateReferenceSystem impl) {
        super(impl);
    }

    /**
     * {@return the coordinate system, which is implemented by the same class for convenience}.
     */
    @Override
    public EllipsoidalCS getCoordinateSystem() {
        clearAxisCache();
        return this;
    }

    @Override
    final Axis[] axesForAllDirections() {
        return Axis.GEOGRAPHIC;
    }
}

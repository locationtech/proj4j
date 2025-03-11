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

import java.io.Serializable;
import java.util.Map;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.opengis.metadata.citation.Citation;
import org.opengis.parameter.ParameterValueGroup;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.crs.SingleCRS;
import org.opengis.referencing.operation.Conversion;
import org.opengis.referencing.operation.CoordinateOperation;
import org.opengis.referencing.operation.CoordinateOperationFactory;
import org.opengis.referencing.operation.OperationMethod;
import org.opengis.util.FactoryException;


/**
 * Wraps a PROJ4J implementation behind the equivalent GeoAPI interface.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
@SuppressWarnings("serial")
final class OperationFactoryWrapper extends Wrapper implements CoordinateOperationFactory, Serializable {
    /**
     * The wrapped PROJ4 implementation.
     */
    final CoordinateTransformFactory impl;

    /**
     * Creates a new wrapper for the given PROJ4J implementation.
     */
    private OperationFactoryWrapper(final CoordinateTransformFactory impl) {
        this.impl = impl;
    }

    /**
     * Wraps the given implementation.
     *
     * @param  impl the implementation to wrap, or {@code null}
     * @return the wrapper, or {@code null} if the given implementation was null
     */
    static OperationFactoryWrapper wrap(final CoordinateTransformFactory impl) {
        return (impl != null) ? new OperationFactoryWrapper(impl) : null;
    }

    /**
     * {@return the PROJ4J backing implementation}.
     */
    @Override
    Object implementation() {
        return impl;
    }

    /**
     * {@return the factory name}.
     */
    @Override
    public String getCode() {
        return "PROJ4J";
    }

    /**
     * {@return an identification of the softwware that provides the CRS definitions}.
     * This is not the authority (EPSG, ESRI, <i>etc</i>).
     */
    @Override
    public Citation getVendor() {
        return SimpleCitation.PROJ4J;
    }

    /**
     * Returns the given CRS as a PROJ4J implementation. This method avoids loading
     * the {@link Importer} class when the given CRS is a PROJ4J wrapper.
     *
     * @param  name  "source" or "target", in case an error message needs to be produced
     * @param  crs   the <abbr>CRS</abbr> to unwrap
     * @return the PROJ4J object for the given CRS.
     */
    private static org.locationtech.proj4j.CoordinateReferenceSystem unwrap(
            final String name, final CoordinateReferenceSystem crs)
    {
        if (crs == null) {
            throw new NullPointerException("The " + name + " CRS shall not be null.");
        }
        if (crs instanceof AbstractCRS) {
            return ((AbstractCRS) crs).impl;
        } else if (crs instanceof SingleCRS) {
            return Importer.DEFAULT.convert((SingleCRS) crs);
        } else {
            throw new UnconvertibleInstanceException("The " + name + " CRS shall be a single CRS.");
        }
    }

    /**
     * Creates a coordinate operation between the given pair of <abbr>CRS</abbr>s.
     *
     * @param  sourceCRS the source coordinate reference system
     * @param  targetCRS the target coordinate reference system
     * @return coordinate operation from source to target
     * @throws FactoryException if the operation cannot be created
     */
    @Override
    public CoordinateOperation createOperation(CoordinateReferenceSystem sourceCRS, CoordinateReferenceSystem targetCRS)
            throws FactoryException
    {
        // Unwrap first for checking null values and the number of dimensions (among others).
        final org.locationtech.proj4j.CoordinateReferenceSystem src = unwrap("source", sourceCRS);
        final org.locationtech.proj4j.CoordinateReferenceSystem tgt = unwrap("target", targetCRS);
        final int srcDim = sourceCRS.getCoordinateSystem().getDimension();
        final int tgtDim = targetCRS.getCoordinateSystem().getDimension();
        if (srcDim != tgtDim) {
            throw new FactoryException("Mismatched dimensions: source is " + srcDim + "D while target is " + tgtDim + "D.");
        }
        return TransformWrapper.wrap(impl.createTransform(src, tgt), srcDim >= TRIDIMENSIONAL);
    }

    /**
     * Creates a coordinate operation between the given pair of <abbr>CRS</abbr>s, ignoring the given method.
     *
     * @param  sourceCRS the source coordinate reference system
     * @param  targetCRS the target coordinate reference system
     * @param  method    ignored
     * @return coordinate operation from source to target
     * @throws FactoryException if the operation cannot be created
     */
    @Override
    public CoordinateOperation createOperation(CoordinateReferenceSystem sourceCRS, CoordinateReferenceSystem targetCRS, OperationMethod method)
            throws FactoryException
    {
        return createOperation(sourceCRS, targetCRS);
    }

    @Override
    public CoordinateOperation createConcatenatedOperation(Map<String, ?> properties, CoordinateOperation... operations)
            throws FactoryException
    {
        throw new FactoryException("Not implemented.");
    }

    @Override
    public Conversion createDefiningConversion(Map<String, ?> properties, OperationMethod method, ParameterValueGroup parameters)
            throws FactoryException
    {
        throw new FactoryException("Not implemented.");
    }
}

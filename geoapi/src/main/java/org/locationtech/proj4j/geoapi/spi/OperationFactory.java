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
package org.locationtech.proj4j.geoapi.spi;

import java.util.Map;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.geoapi.Wrappers;
import org.opengis.metadata.citation.Citation;
import org.opengis.parameter.ParameterValueGroup;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.*;
import org.opengis.util.FactoryException;


/**
 * Registers PROJ4J wrappers as an operation factory.
 *
 * <h4>Future evolution</h4>
 * In a future version, it may not be possible anymore to instantiate this class.
 * For now, we have to allow instantiation for compatibility with Java 8 services.
 * If a future version of PROJ4J migrates to Java 9 module system, the only way to
 * get the factory will by invoking the {@link #provider()} static method.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
public final class OperationFactory implements CoordinateOperationFactory {
    /**
     * Where to delegate all operations.
     */
    private final CoordinateOperationFactory proxy;

    /**
     * Creates a new instance.
     * <b>WARNING:</b> this constructor may not be accessible anymore in a future version.
     * Do not invoke directly.
     */
    public OperationFactory() {
        proxy = provider();
    }

    /**
     * {@return the factory backed by PROJ4J}.
     */
    public static CoordinateOperationFactory provider() {
        return Wrappers.geoapi(new CoordinateTransformFactory());
    }

    @Override
    public Citation getVendor() {
        return proxy.getVendor();
    }

    @Override
    public CoordinateOperation createOperation(CoordinateReferenceSystem sourceCRS, CoordinateReferenceSystem targetCRS) throws FactoryException {
        return proxy.createOperation(sourceCRS, targetCRS);
    }

    @Override
    public CoordinateOperation createOperation(CoordinateReferenceSystem sourceCRS, CoordinateReferenceSystem targetCRS, OperationMethod method) throws FactoryException {
        return proxy.createOperation(sourceCRS, targetCRS, method);
    }

    @Override
    public CoordinateOperation createConcatenatedOperation(Map<String, ?> properties, CoordinateOperation... operations) throws FactoryException {
        return proxy.createConcatenatedOperation(properties, operations);
    }

    @Override
    public Conversion createDefiningConversion(Map<String, ?> properties, OperationMethod method, ParameterValueGroup parameters) throws FactoryException {
        return proxy.createDefiningConversion(properties, method, parameters);
    }
}

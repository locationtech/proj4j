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
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.proj.Projection;
import org.opengis.parameter.GeneralParameterDescriptor;
import org.opengis.parameter.GeneralParameterValue;
import org.opengis.parameter.ParameterDescriptorGroup;
import org.opengis.parameter.ParameterNotFoundException;
import org.opengis.parameter.ParameterValue;
import org.opengis.parameter.ParameterValueGroup;
import org.opengis.referencing.ReferenceIdentifier;
import org.opengis.referencing.operation.Formula;
import org.opengis.referencing.operation.OperationMethod;


/**
 * Wraps a PROJ4J implementation behind the equivalent GeoAPI interface.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
@SuppressWarnings("serial")
final class OperationMethodWrapper extends Wrapper implements OperationMethod,
        ParameterDescriptorGroup, ParameterValueGroup, Serializable
{
    /**
     * The wrapped PROJ4 implementation.
     */
    final Projection impl;

    /**
     * Creates a new wrapper for the given PROJ4J implementation.
     */
    private OperationMethodWrapper(final Projection impl) {
        this.impl = impl;
    }

    /**
     * Wraps the given implementation.
     *
     * @param  impl the implementation to wrap, or {@code null}
     * @return the wrapper, or {@code null} if the given implementation was null
     */
    static OperationMethodWrapper wrap(final Projection impl) {
        return (impl != null) ? new OperationMethodWrapper(impl) : null;
    }

    /**
     * Wraps the target CRS of the given transform.
     *
     * @param  impl the implementation to wrap, or {@code null}
     * @return the wrapper, or {@code null} if none
     */
    static OperationMethodWrapper wrapTarget(final CoordinateTransform impl) {
        if (impl != null) {
            CoordinateReferenceSystem crs = impl.getTargetCRS();
            if (crs != null) {
                return wrap(crs.getProjection());
            }
        }
        return null;
    }

    /**
     * {@return the PROJ4J backing implementation}.
     */
    @Override
    final Object implementation() {
        return impl;
    }

    /**
     * {@return the operation method name}. In the PROJ4J implementations, the {@link Projection#toString()}
     * method seems to be the method name. However, this is not formalized in the Javadoc.
     */
    @Override
    public final String getCode() {
        return impl.toString();
    }

    /**
     * {@return the EPSG code of this method, if known}.
     */
    @Override
    public Set<ReferenceIdentifier> getIdentifiers() {
        return IdentifierEPSG.wrap(impl.getEPSGCode());
    }

    /**
     * Formula(s) or procedure used by this operation method.
     * This information is not provided.
     */
    @Override
    public Formula getFormula() {
        return null;
    }

    /**
     * @deprecated This property has been removed in latest revision of ISO 19111.
     */
    @Override
    @Deprecated
    public Integer getSourceDimensions() {
        return null;
    }

    /**
     * @deprecated This property has been removed in latest revision of ISO 19111.
     */
    @Override
    @Deprecated
    public Integer getTargetDimensions() {
        return null;
    }

    /**
     * {@return the minimum number of times that this parameter group is required}.
     */
    @Override
    public int getMinimumOccurs() {
        return 1;
    }

    /**
     * {@return the maximum number of times that this parameter group is required}.
     */
    @Override
    public int getMaximumOccurs() {
        return 1;
    }

    /**
     * {@return the descriptors of parameters of the projection}.
     * This method is defined in {@link OperationMethod}.
     */
    @Override
    public ParameterDescriptorGroup getParameters() {
        return this;
    }

    /**
     * {@return the descriptors of parameters of the projection}.
     * This method is defined in {@link ParameterValueGroup}.
     */
    @Override
    public ParameterDescriptorGroup getDescriptor() {
        return this;
    }

    /**
     * {@return the descriptions of all parameters having a non-default value}.
     * The check for non-default values is a heuristic rule for identifying
     * which parameters are used by the PROJ4J {@link Projection} instance.
     */
    @Override
    public List<GeneralParameterDescriptor> descriptors() {
        return Arrays.asList(ParameterAccessor.nonDefault(impl));
    }

    /**
     * {@return the values of all parameters having a non-default value}.
     * The check for non-default values is a heuristic rule for identifying
     * which parameters are used by the PROJ4J {@link Projection} instance.
     */
    @Override
    public List<GeneralParameterValue> values() {
        final ParameterAccessor[] descriptors = ParameterAccessor.nonDefault(impl);
        final ParameterWrapper[]  parameters  = new ParameterWrapper[descriptors.length];
        for (int i=0; i<parameters.length; i++) {
            parameters[i] = new ParameterWrapper(impl, descriptors[i]);
        }
        return Arrays.asList(parameters);
    }

    /**
     * Returns the parameter descriptor of the given name.
     *
     * @param  name  name of the desired parameter
     * @return parameter descriptor for the given name
     * @throws ParameterNotFoundException if the given name is unknown
     */
    @Override
    public GeneralParameterDescriptor descriptor(String name) throws ParameterNotFoundException {
        return ParameterAccessor.forName(name);
    }

    /**
     * Returns the parameter value of the given name.
     *
     * @param  name  name of the desired parameter
     * @return parameter value for the given name
     * @throws ParameterNotFoundException if the given name is unknown
     */
    @Override
    public ParameterValue<?> parameter(String name) throws ParameterNotFoundException {
        return new ParameterWrapper(impl, ParameterAccessor.forName(name));
    }

    /**
     * Unsupported operation.
     */
    @Override
    public List<ParameterValueGroup> groups(String name) throws ParameterNotFoundException {
        throw new ParameterNotFoundException("Parameter groups are not supported.", name);
    }

    /**
     * Unsupported operation.
     */
    @Override
    public ParameterValueGroup addGroup(String name) throws ParameterNotFoundException, IllegalStateException {
        throw new ParameterNotFoundException("Parameter groups are not supported.", name);
    }

    /**
     * Creates a new instance of this group of parameters.
     * All accessible parameters are set to their default value.
     */
    @Override
    public ParameterValueGroup createValue() {
        OperationMethodWrapper c = new OperationMethodWrapper((Projection) impl.clone());
        ParameterAccessor.reset(c.impl);
        return c;
    }

    /**
     * {@return a copy of this group of parameters}.
     */
    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public ParameterValueGroup clone() {
        return new OperationMethodWrapper((Projection) impl.clone());
    }
}

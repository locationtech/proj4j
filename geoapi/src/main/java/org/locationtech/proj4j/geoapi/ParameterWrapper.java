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
import java.net.URI;
import java.util.AbstractMap;
import javax.measure.IncommensurableException;
import javax.measure.Unit;
import org.locationtech.proj4j.proj.Projection;
import org.opengis.parameter.InvalidParameterTypeException;
import org.opengis.parameter.InvalidParameterValueException;
import org.opengis.parameter.ParameterDescriptor;
import org.opengis.parameter.ParameterValue;


/**
 * Wraps a PROJ4J implementation behind the equivalent GeoAPI interface.
 * This implementation is restricted to values of the {@code double} primitive type.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
@SuppressWarnings("serial")
final class ParameterWrapper extends Wrapper implements ParameterValue<Double>, Serializable {
    /**
     * The wrapped PROJ4 implementation.
     */
    private final Projection impl;

    /**
     * The parameter name together with the methods for getting or setting values.
     */
    private final ParameterAccessor descriptor;

    /**
     * Creates a new wrapper for the given PROJ4J implementation.
     *
     * @param impl        the wrapped PROJ4 implementation
     * @param descriptor  methods for getting or setting values
     */
    ParameterWrapper(final Projection impl, final ParameterAccessor descriptor) {
        this.impl       = impl;
        this.descriptor = descriptor;
    }

    /**
     * {@return an arbitrary object for equality and hash code}.
     */
    @Override
    Object implementation() {
        return new AbstractMap.SimpleEntry<>(descriptor, impl);
    }

    /**
     * {@return a description of this parameter}.
     */
    @Override
    public ParameterDescriptor<Double> getDescriptor() {
        return descriptor;
    }

    /**
     * {@return this parameter name, as specified in the descriptor}.
     */
    @Override
    public String getCode() {
        return descriptor.getCode();
    }

    /**
     * {@return the same unit of measurement as declared in the parameter descriptor}.
     */
    @Override
    public Unit<?> getUnit() {
        return descriptor.getUnit();
    }

    /**
     * {@return the exception to throw for an illegal unit of measurement}.
     */
    private IllegalArgumentException illegalUnit(IncommensurableException e) {
        return new IllegalArgumentException("Illegal unit for the \"" + getCode() + "\" parameter.", e);
    }

    /**
     * {@return the exception to throw for all parameter types other than floating-point}.
     */
    private InvalidParameterTypeException invalidReturnType() {
        throw new InvalidParameterTypeException("The value can be provided only as a real number.", getCode());
    }

    /**
     * {@return the exception to throw for all parameter types other than floating-point}.
     */
    private InvalidParameterValueException invalidParamType(final Object value) {
        throw new InvalidParameterValueException("The value can be set only as a real number.", getCode(), value);
    }

    /**
     * {@return the value as an arbitrary object}.
     */
    @Override
    public Double getValue() {
        return doubleValue();
    }

    /**
     * {@return the value in the PROJ4J projection for the parameter described by this object}.
     */
    @Override
    public double doubleValue() {
        return descriptor.get(impl);
    }

    /**
     * {@return the value in the PROJ4J projection for the parameter described by this object}.
     * The value is converted to the given unit of measurement.
     *
     * @param unit  the unit of measurement of value to get
     * @throws IllegalArgumentException if the given unit is incompatible
     */
    @Override
    public double doubleValue(Unit<?> unit) {
        try {
            return getUnit().getConverterToAny(unit).convert(doubleValue());
        } catch (IncommensurableException e) {
            throw illegalUnit(e);
        }
    }

    /**
     * Sets the value as an arbitrary object.
     *
     * @param value the value to set
     * @throws InvalidParameterValueException if the value type is illegal
     */
    @Override
    public void setValue(Object value) throws InvalidParameterValueException {
        if (value instanceof Number) {
            setValue(((Number) value).doubleValue());
        }
        throw invalidParamType(value);
    }

    /**
     * Sets the value of this parameter. Note that invoking this method may modify the PROJ4J
     * object wrapped by {@link OperationMethodWrapper}. This is generally not recommended.
     *
     * @param value the value to set
     */
    @Override
    public void setValue(double value) {
        descriptor.set(impl, value);
    }

    /**
     * Converts the given value to the unit expected by this parameter, then sets the value.
     *
     * @param value the value to set
     * @param unit  the unit of measurement of the given value
     * @throws UnsupportedOperationException if this parameter is unmodifiable
     * @throws IllegalArgumentException if the given unit is incompatible
     */
    @Override
    public void setValue(double value, Unit<?> unit) {
        try {
            setValue(unit.getConverterToAny(getUnit()).convert(value));
        } catch (IncommensurableException e) {
            throw illegalUnit(e);
        }
    }

    @Override
    public int intValue() {
        throw invalidReturnType();
    }

    @Override
    public boolean booleanValue() throws IllegalStateException {
        throw invalidReturnType();
    }

    @Override
    public String stringValue() throws IllegalStateException {
        throw invalidReturnType();
    }

    @Override
    public URI valueFile() throws IllegalStateException {
        throw invalidReturnType();
    }

    @Override
    public int[] intValueList() throws IllegalStateException {
        throw invalidReturnType();
    }

    @Override
    public double[] doubleValueList() throws IllegalStateException {
        throw invalidReturnType();
    }

    @Override
    public double[] doubleValueList(Unit<?> unit) throws IllegalArgumentException, IllegalStateException {
        throw invalidReturnType();
    }

    @Override
    public void setValue(int value) throws InvalidParameterValueException {
        throw invalidParamType(value);
    }

    @Override
    public void setValue(boolean value) throws InvalidParameterValueException {
        throw invalidParamType(value);
    }

    @Override
    public void setValue(double[] values, Unit<?> unit) throws InvalidParameterValueException {
        throw invalidParamType(values);
    }

    /**
     * {@return a modifiable copy of this parameter}. Note that this method is inefficient
     * as it creates a full {@link Projection} object for each individual parameter value.
     * It is better to invoke {@link OperationMethodWrapper#clone()} instead.
     */
    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public ParameterValue<Double> clone() {
        return new ParameterWrapper((Projection) impl.clone(), descriptor);
    }
}

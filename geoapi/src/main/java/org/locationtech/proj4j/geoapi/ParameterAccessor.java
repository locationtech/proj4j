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
import java.util.Set;
import java.util.function.ToDoubleFunction;
import java.util.function.ObjDoubleConsumer;
import javax.measure.Unit;
import org.locationtech.proj4j.proj.NullProjection;
import org.locationtech.proj4j.proj.Projection;
import org.opengis.parameter.ParameterDescriptor;
import org.opengis.parameter.ParameterNotFoundException;
import org.opengis.parameter.ParameterValue;


/**
 * Description of a PROJ4J parameter, together with method for getting and setting the value.
 * This implementation is restricted to values of the {@code double} primitive type.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
@SuppressWarnings("serial")
final class ParameterAccessor extends Wrapper implements ParameterDescriptor<Double>, Serializable {
    /**
     * Parameters that we can extract from a {@link Projection} object.
     * Does not include the ellipsoid axis length of flattening factors.
     */
    private static final ParameterAccessor[] ACCESSORS = {
        new ParameterAccessor("central_meridian",    Projection::getProjectionLongitude, Projection::setProjectionLongitude, false, true),
        new ParameterAccessor("latitude_of_origin",  Projection::getProjectionLatitude,  Projection::setProjectionLatitude,  false, true),
        new ParameterAccessor("standard_parallel_1", Projection::getProjectionLatitude1, Projection::setProjectionLatitude1, false, true),
        new ParameterAccessor("standard_parallel_2", Projection::getProjectionLatitude2, Projection::setProjectionLatitude2, false, true),
        new ParameterAccessor("true_scale_latitude", Projection::getTrueScaleLatitude,   Projection::setTrueScaleLatitude,   false, true),    // Didn't found an OGC name for this one.
        new ParameterAccessor("scale_factor",        Projection::getScaleFactor,         Projection::setScaleFactor,         true,  false),
        new ParameterAccessor("false_easting",       Projection::getFalseEasting,        Projection::setFalseEasting,        false, false),
        new ParameterAccessor("false_northing",      Projection::getFalseNorthing,       Projection::setFalseNorthing,       false, false)
    };

    /**
     * The parameter name. Should be OGC names if possible. This name may not be correct in all cases,
     * because some names depend on the projection method. For example, "latitude of origin" may be
     * "latitude of center" in some projections.
     */
    private final String name;

    /**
     * The method to invoke for getting the parameter value.
     */
    private final ToDoubleFunction<Projection> getter;

    /**
     * The method to invoke for setting the parameter value.
     */
    private final ObjDoubleConsumer<Projection> setter;

    /**
     * Whether this parameter is the scale factor.
     * That parameter has a different default value.
     */
    private final boolean isScale;

    /**
     * Whether the unit of measurement is angular (true) or linear (false).
     */
    private final boolean angular;

    /**
     * Creates a new parameter descriptor.
     *
     * @param name    the parameter name
     * @param getter  the method to invoke for getting the parameter value
     * @param setter  the method to invoke for setting the parameter value
     * @param isScale whether this parameter is the scale factor
     * @param angular whether the unit of measurement is angular (true) or linear (false)
     */
    private ParameterAccessor(final String name,
                              final ToDoubleFunction<Projection> getter,
                              final ObjDoubleConsumer<Projection> setter,
                              final boolean isScale,
                              final boolean angular)
    {
        this.name    = name;
        this.getter  = getter;
        this.setter  = setter;
        this.isScale = isScale;
        this.angular = angular;
    }

    /**
     * Returns the parameter descriptor of the given name.
     *
     * @param  name  name of the desired parameter
     * @return parameter descriptor for the given name
     * @throws ParameterNotFoundException if the given name is unknown
     */
    static ParameterAccessor forName(final String name) {
        for (ParameterAccessor c : ACCESSORS) {
            if (c.name.equalsIgnoreCase(name)) {
                return c;
            }
        }
        throw new ParameterNotFoundException("Parameter \"" + name + "\" is unknown or unsupported.", name);
    }

    /**
     * Returns all descriptors having a non-default values for the given PROJ4J projection.
     * We do not have a formal list of parameters that are valid for each projection.
     * Therefore, checking for non-default values is workaround.
     */
    static ParameterAccessor[] nonDefault(final Projection proj) {
        final ParameterAccessor[] parameters = new ParameterAccessor[ACCESSORS.length];
        int count = 0;
        for (ParameterAccessor c : ACCESSORS) {
            if (c.getter.applyAsDouble(proj) != c.defaultValue()) {
                parameters[count++] = c;
            }
        }
        return Arrays.copyOf(parameters, count);
    }

    /**
     * Resets all parameters to their default value.
     */
    static void reset(final Projection proj) {
        for (ParameterAccessor c : ACCESSORS) {
            c.setter.accept(proj, c.defaultValue());
        }
    }

    /**
     * {@return an identification of the parameter}.
     */
    @Override
    Object implementation() {
        return name;
    }

    /**
     * {@return the parameter name}.
     */
    @Override
    public String getCode() {
        return name;
    }

    /**
     * {@return the class that describe the type of the parameter}.
     */
    @Override
    public Class<Double> getValueClass() {
        return Double.class;
    }

    /**
     * {@return null as this parameter is not restricted to a limited set of values}.
     */
    @Override
    public Set<Double> getValidValues() {
        return null;
    }

    /**
     * {@return the default value as initialized in the PROJ4 projection class}.
     */
    @Override
    public Double getDefaultValue() {
        return defaultValue();
    }

    /**
     * {@return the default value as a primitive type}.
     */
    private double defaultValue() {
        return isScale ? 1d : 0d;
    }

    /**
     * Unspecified.
     */
    @Override
    public Comparable<Double> getMinimumValue() {
        return null;
    }

    /**
     * Unspecified.
     */
    @Override
    public Comparable<Double> getMaximumValue() {
        return null;
    }

    /**
     * {@return the minimum number of times that values for this parameter are required}.
     * The value should be 1 for mandatory parameters and 0 for optional parameters.
     * We consider all parameters as optional, because we don't know for sure which
     * parameters are used by a particular PROJ4J {@link Projection} instance.
     */
    @Override
    public int getMinimumOccurs() {
        return 0;
    }

    /**
     * {@return the maximum number of times that values for this parameter are required}.
     * Values greater than 1 should happen only with parameter groups, which are not used
     * in this implementation.
     */
    @Override
    public int getMaximumOccurs() {
        return 1;
    }

    /**
     * {@return the unit of measurement}.
     */
    @Override
    public Unit<?> getUnit() {
        final Units units = Units.getInstance();
        return isScale ? units.one : angular ? units.degree : units.metre;
    }

    /**
     * Gets the value of this parameter from the given projection.
     */
    final double get(final Projection proj) {
        double value = getter.applyAsDouble(proj);
        if (angular) {
            value = Math.toDegrees(value);
        }
        return value;
    }

    /**
     * Sets the value of this parameter in the given projection.
     */
    final void set(final Projection proj, double value) {
        if (angular) {
            value = Math.toRadians(value);
        }
        setter.accept(proj, value);
    }

    /**
     * Creates a new parameter value. Note that this method is inefficient as it
     * creates a full {@link Projection} object for each individual parameter value.
     *
     * @return a new parameter value
     */
    @Override
    public ParameterValue<Double> createValue() {
        return new ParameterWrapper(new NullProjection(), this);
    }
}

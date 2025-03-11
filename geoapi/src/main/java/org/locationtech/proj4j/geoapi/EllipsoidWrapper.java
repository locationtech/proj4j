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
import java.util.Collection;
import javax.measure.Unit;
import javax.measure.quantity.Length;
import org.opengis.referencing.datum.Ellipsoid;
import org.opengis.util.GenericName;


/**
 * Wraps a PROJ4J implementation behind the equivalent GeoAPI interface.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
@SuppressWarnings("serial")
final class EllipsoidWrapper extends Wrapper implements Ellipsoid, Serializable {
    /**
     * The wrapped PROJ4 implementation.
     */
    final org.locationtech.proj4j.datum.Ellipsoid impl;

    /**
     * Creates a new wrapper for the given PROJ4J implementation.
     */
    private EllipsoidWrapper(final org.locationtech.proj4j.datum.Ellipsoid impl) {
        this.impl = impl;
    }

    /**
     * Wraps the given implementation.
     *
     * @param  impl the implementation to wrap, or {@code null}
     * @return the wrapper, or {@code null} if the given implementation was null
     */
    static EllipsoidWrapper wrap(final org.locationtech.proj4j.datum.Ellipsoid impl) {
        return (impl != null) ? new EllipsoidWrapper(impl) : null;
    }

    /**
     * {@return the PROJ4J backing implementation}.
     */
    @Override
    Object implementation() {
        return impl;
    }

    /**
     * {@return the long name if available, or the short name otherwise}.
     * In the EPSG database, the primary name is usually the long name.
     */
    @Override
    public String getCode() {
        String name = impl.getName();
        if (name == null) {
            name = impl.getShortName();
        }
        return name;
    }

    /**
     * {@return other names of this object}.
     * In the EPSG database, this is usually the short name (the abbreviation).
     */
    @Override
    public Collection<GenericName> getAlias() {
        if (impl.getName() != null) {
            return Alias.wrap(impl.getShortName());
        }
        return super.getAlias();
    }

    /**
     * @return the axis unit of measurement, which is assumed to be metres.
     */
    @Override
    public Unit<Length> getAxisUnit() {
        return Units.getInstance().metre;
    }

    /**
     * {@return the equator radius of the PROJ4J implementation}.
     */
    @Override
    public double getSemiMajorAxis() {
        return impl.getA();
    }

    /**
     * {@return the pole radius of the PROJ4J implementation}.
     */
    @Override
    public double getSemiMinorAxis() {
        return impl.getB();
    }

    /**
     * {@return computes the inverse flatening from the equator and pole radius}.
     */
    @Override
    public double getInverseFlattening() {
        final double a = impl.getA();
        return a / (a - impl.getB());
    }

    /**
     * {@return false since the inverse flatteing is computed}.
     */
    @Override
    public boolean isIvfDefinitive() {
        return false;
    }

    /**
     * @return whether the equator and pole radius are equal.
     * Strict equality is okay because those values are set explicitly.
     */
    @Override
    public boolean isSphere() {
        return impl.getA() == impl.getB();
    }
}

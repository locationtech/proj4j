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
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.datum.AxisOrder;
import org.locationtech.proj4j.proj.Projection;
import org.opengis.referencing.crs.SingleCRS;
import org.opengis.referencing.cs.CoordinateSystem;
import org.opengis.referencing.cs.CoordinateSystemAxis;
import org.opengis.referencing.datum.GeodeticDatum;


/**
 * Wraps a PROJ4J implementation behind the equivalent GeoAPI interface.
 * The CRS is assumed two-dimensional.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
@SuppressWarnings("serial")
abstract class AbstractCRS extends Wrapper implements SingleCRS, CoordinateSystem, Serializable {
    /**
     * The number of dimensions of the CRS.
     */
    private static final int BIDIMENSIONAL = 2;

    /**
     * The wrapped PROJ4 implementation.
     */
    private final org.locationtech.proj4j.CoordinateReferenceSystem impl;

    /**
     * The coordinate system axes, computed and cached when first requested.
     * This is refreshed every time that {@link #getCoordinateSystem()} is invoked,
     * for compliance with the documentation saying that this object is a view.
     */
    @SuppressWarnings("VolatileArrayField")     // Because array elements will not change.
    private volatile transient Axis[] axes;

    /**
     * Creates a new wrapper for the given PROJ4J implementation.
     */
    AbstractCRS(final org.locationtech.proj4j.CoordinateReferenceSystem impl) {
        this.impl = impl;
    }

    /**
     * Wraps the given implementation.
     *
     * @param  impl the implementation to wrap, or {@code null}
     * @return the wrapper, or {@code null} if the given implementation was null
     */
    static AbstractCRS wrap(final org.locationtech.proj4j.CoordinateReferenceSystem impl) {
        if (impl != null) {
            final Projection proj = impl.getProjection();
            if (proj == null || proj.isGeographic()) {
                return new GeographicCRSWrapper(impl);
            } else {
                // TODO
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
     * {@return the CRS name}.
     */
    @Override
    public final String getCode() {
        return impl.getName();
    }

    /**
     * {@return the PROJ4J datum wrapped behind the GeoAPI interface}.
     */
    @Override
    public final GeodeticDatum getDatum() {
        return DatumWrapper.wrap(impl);
    }

    /**
     * {@return the coordinate system, which is implemented by the same class for convenience}.
     */
    @Override
    public CoordinateSystem getCoordinateSystem() {
        clearAxisCache();
        return this;
    }

    /**
     * {@return the number of dimensions, which is fixed to 2}.
     */
    @Override
    public final int getDimension() {
        return BIDIMENSIONAL;
    }

    /**
     * Returns {@link Axis#GEOGRAPHIC} and {@link Axis#PROJECTED} arrays,
     * depending on whether this <abbr>CRS</abbr> is geographic or projected.
     * The returned array is not cloned, the caller shall not modify it.
     */
    abstract Axis[] axesForAllDirections();

    /**
     * Clears the cache of axes. This method should be invoked by {@link #getCoordinateSystem()}
     * for compliance with the documentation saying that change in the wrapped object are reflected
     * in the view.
     */
    final void clearAxisCache() {
        axes = null;
    }

    /**
     * Returns the axis in the given dimension.
     *
     * @param  dimension  the axis index, from 0 to 2 inclusive.
     * @return axis in the specified dimension.
     * @throws IndexOutOfBoundsException if the given axis index is out of bounds.
     */
    @Override
    public final CoordinateSystemAxis getAxis(int dimension) {
        @SuppressWarnings("LocalVariableHidesMemberVariable")
        Axis[] axes = this.axes;
        if (axes == null) {
            final Axis[] axesForAllDirections = axesForAllDirections();
            axes = Arrays.copyOfRange(axesForAllDirections, Axis.INDEX_OF_EAST, axesForAllDirections.length);
            final Projection proj = impl.getProjection();
            if (proj != null) {
                final AxisOrder order = proj.getAxisOrder();
                if (order != null) {
                    ProjCoordinate coord = new ProjCoordinate(1, 2, 3);
                    order.fromENU(coord);
                    for (int i=0; i<axes.length; i++) {
                        final double c;
                        switch (i) {
                            case 0: c = coord.x; break;
                            case 1: c = coord.y; break;
                            case 2: c = coord.z; break;
                            default: throw new AssertionError(i);
                        }
                        axes[i] = axesForAllDirections[((int) c) + (Axis.INDEX_OF_EAST - 1)];
                    }
                }
                org.locationtech.proj4j.units.Unit unit = proj.getUnits();
                if (unit != null) {
                    final double scale = unit.value;
                    for (int i=0; i<axes.length; i++) {
                        axes[i] = axes[i].withUnit(scale);
                    }
                }
            }
            this.axes = axes;
        }
        return axes[dimension];
    }
}

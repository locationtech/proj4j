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
import org.locationtech.proj4j.ProjCoordinate;
import org.opengis.geometry.DirectPosition;
import org.opengis.geometry.MismatchedDimensionException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;


/**
 * Wraps a PROJ4J implementation behind the equivalent GeoAPI interface.
 * The CRS is assumed two-dimensional.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
@SuppressWarnings("serial")
final class PositionWrapper extends Wrapper implements DirectPosition, Serializable {
    /**
     * The wrapped PROJ4 implementation.
     */
    final ProjCoordinate impl;

    /**
     * Creates a new wrapper for the given PROJ4J implementation.
     */
    PositionWrapper(final ProjCoordinate impl) {
        this.impl = impl;
    }

    /**
     * Wraps the given implementation.
     *
     * @param  impl the implementation to wrap, or {@code null}
     * @return the wrapper, or {@code null} if the given implementation was null
     */
    static PositionWrapper wrap(final ProjCoordinate impl) {
        return (impl != null) ? new PositionWrapper(impl) : null;
    }

    /**
     * {@return the PROJ4J backing implementation}.
     */
    @Override
    final Object implementation() {
        return impl;
    }

    /**
     * Not applicable.
     */
    @Override
    public String getCode() {
        return null;
    }

    /**
     * {@return the direct position, which is provided directly by this object}.
     */
    @Override
    public DirectPosition getDirectPosition() {
        return this;
    }

    /**
     * Not specified.
     */
    @Override
    public CoordinateReferenceSystem getCoordinateReferenceSystem() {
        return null;
    }

    /**
     * {@return the number of dimensions},
     * which is 2 or 3 depending on whether the <var>z</var> coordinate value is provided.
     */
    @Override
    public int getDimension() {
        return Double.isNaN(impl.z) ? BIDIMENSIONAL : TRIDIMENSIONAL;
    }

    /**
     * {@return all coordinate values}.
     */
    @Override
    public double[] getCoordinate() {
        final double[] coordinates = new double[getDimension()];
        coordinates[0] = impl.x;
        coordinates[1] = impl.y;
        if (coordinates.length >= TRIDIMENSIONAL) {
            coordinates[2] = impl.z;
        }
        return coordinates;
    }

    /**
     * {@return the coordinate value in the given dimension}.
     *
     * @param dimension the dimension of the coordinate to get
     */
    @Override
    public double getOrdinate(int dimension) {
        switch (dimension) {
            case 0: return impl.x;
            case 1: return impl.y;
            case 2: return impl.z;
            default: throw outOfBounds(dimension);
        }
    }

    /**
     * Sets the coordinate value in the given dimension.
     *
     * @param dimension the dimension of the coordinate to set
     * @param value the value to set
     */
    @Override
    public void setOrdinate(int dimension, double value) {
        switch (dimension) {
            case 0: impl.x = value; break;
            case 1: impl.y = value; break;
            case 2: impl.z = value; break;
            default: throw outOfBounds(dimension);
        }
    }

    /**
     * Copies the coordinates of the given PROJ4J object into the given GeoAPI object.
     *
     * @param src  the source coordinates to copy
     * @param tgt  where to copy the coordinates
     */
    @SuppressWarnings("fallthrough")
    static void setLocation(final ProjCoordinate src, final DirectPosition tgt) {
        if (tgt instanceof PositionWrapper) {
            ProjCoordinate impl = ((PositionWrapper) tgt).impl;
            if (impl != src) {    // Otherwise nothing to do as the given target is already a view over the source.
                impl.setValue(src);
            }
        } else {
            final int dimension = tgt.getDimension();
            switch (dimension) {
                default: throw unexpectedDimension(dimension);
                case TRIDIMENSIONAL: tgt.setOrdinate(2, src.z);      // Fall through
                case BIDIMENSIONAL:  tgt.setOrdinate(1, src.y);
                                     tgt.setOrdinate(0, src.x);
            }
        }
    }

    /**
     * {@return the given position as a PROJ4J coordinate tuple}.
     * This method tries to return the backing implementation if possible,
     * or otherwise copies the coordinate values in a new coordinate tuple.
     *
     * @param src the position to unwrap or copy
     */
    @SuppressWarnings("fallthrough")
    static ProjCoordinate unwrapOrCopy(final DirectPosition src) {
        if (src == null) {
            return null;
        }
        if (src instanceof PositionWrapper) {
            return ((PositionWrapper) src).impl;
        }
        ProjCoordinate tgt = new ProjCoordinate();
        final int dimension = src.getDimension();
        switch (dimension) {
            default: throw unexpectedDimension(dimension);
            case TRIDIMENSIONAL: tgt.z = src.getOrdinate(2);   // Fall through
            case BIDIMENSIONAL:  tgt.y = src.getOrdinate(1);
                                 tgt.x = src.getOrdinate(0);
        }
        return tgt;
    }

    /**
     * Returns the exception to throw for a coordinate dimension out of bounds.
     *
     * @param  dimension  the dimension which is out of bound
     * @return the exception to throw
     */
    private static IndexOutOfBoundsException outOfBounds(final int dimension) {
        return new IndexOutOfBoundsException("Coordinate index " + dimension + " is out of bounds.");
    }

    /**
     * Constructs an exception for an unexpected number of dimensions.
     *
     * @param  dimension  the number of dimensions of the object provided by the user
     * @return the exception to throw
     */
    private static MismatchedDimensionException unexpectedDimension(final int dimension) {
        return new MismatchedDimensionException("The given point has " + dimension + " dimensions while "
                + (dimension <= BIDIMENSIONAL ? BIDIMENSIONAL : TRIDIMENSIONAL) + " dimensions were expected.");
    }
}

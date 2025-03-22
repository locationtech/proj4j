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
import java.util.Collections;
import java.util.Objects;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.proj.Projection;
import org.opengis.geometry.DirectPosition;
import org.opengis.metadata.quality.PositionalAccuracy;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.CoordinateOperation;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.Matrix;
import org.opengis.referencing.operation.TransformException;


/**
 * Base class of two-dimensional or three-dimensional coordinate operation.
 * The exact type of the operation (conversion, transformation or concatenated) is unknown.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
@SuppressWarnings("serial")
abstract class TransformWrapper extends Wrapper implements CoordinateOperation, MathTransform, Serializable {
    /**
     * The wrapped PROJ4 implementation.
     */
    final CoordinateTransform impl;

    /**
     * Creates a new wrapper for the given PROJ4J implementation.
     */
    TransformWrapper(final CoordinateTransform impl) {
        this.impl = impl;
    }

    /**
     * Wraps the given implementation.
     *
     * @param  impl the implementation to wrap, or {@code null}
     * @param  is3D whether to return a three-dimensional operation instead of a two-dimensional one
     * @return the wrapper, or {@code null} if the given implementation was null
     */
    static TransformWrapper wrap(final CoordinateTransform impl, final boolean is3D) {
        if (impl == null) {
            return null;
        } else if (is3D) {
            return new TransformWrapper3D(impl);
        } else {
            return new TransformWrapper2D(impl);
        }
    }

    /**
     * {@return the PROJ4J backing implementation}.
     */
    @Override
    final Object implementation() {
        return impl;
    }

    /**
     * Returns the projection of the given CRS, or {@code null} if none.
     */
    private static Projection getProjection(final org.locationtech.proj4j.CoordinateReferenceSystem crs) {
        return (crs != null) ? crs.getProjection() : null;
    }

    /**
     * Returns the name of the given CRS, or an arbitrary name if none is specified.
     */
    private static String getName(final org.locationtech.proj4j.CoordinateReferenceSystem crs) {
        if (crs != null) {
            String name = crs.getName();
            if (name != null) {
                return name;
            }
        }
        return "Unnamed";
    }

    /**
     * {@return a name that summarizes the operation}.
     */
    @Override
    public String getCode() {
        return getName(impl.getSourceCRS()) + " → " + getName(impl.getTargetCRS());
    }

    /**
     * {@return the CRS of the source points}.
     * May be {@code null} if unspecified.
     */
    @Override
    public final CoordinateReferenceSystem getSourceCRS() {
        return AbstractCRS.wrap(impl.getSourceCRS(), getSourceDimensions() >= TRIDIMENSIONAL);
    }

    /**
     * {@return the CRS of the target points}.
     * May be {@code null} if unspecified.
     */
    @Override
    public final CoordinateReferenceSystem getTargetCRS() {
        return AbstractCRS.wrap(impl.getTargetCRS(), getTargetDimensions() >= TRIDIMENSIONAL);
    }

    /**
     * {@return the version of the coordinate transformation}.
     * This is unknown by default.
     */
    @Override
    public String getOperationVersion() {
        return null;
    }

    /**
     * {@return the impact of this operation on point accuracy}.
     * This is unknown by default.
     */
    @Override
    public Collection<PositionalAccuracy> getCoordinateOperationAccuracy() {
        return Collections.emptyList();
    }

    /**
     * {@return the object performing the actual coordinate operations}.
     * This is the same object in the case of PROJ4J implementation.
     */
    @Override
    public final MathTransform getMathTransform() {
        return this;
    }

    /**
     * Tests whether this transform does not move any points.
     */
    @Override
    public final boolean isIdentity() {
        return Objects.equals(getProjection(impl.getSourceCRS()),
                              getProjection(impl.getTargetCRS()));
    }

    /**
     * Transforms the specified {@code ptSrc} and stores the result in {@code ptDst}.
     * If the target position is a wrapper, this method writes the result directly in
     * the backing implementation. This method has some flexibility on the number of
     * dimensions (2 or 3).
     */
    @Override
    public final DirectPosition transform(DirectPosition ptSrc, DirectPosition ptDst) throws TransformException {
        ProjCoordinate src = PositionWrapper.unwrapOrCopy(ptSrc);
        ProjCoordinate tgt;
        try {
            if (ptDst instanceof PositionWrapper) {
                tgt = ((PositionWrapper) ptDst).impl;
                if (tgt == (tgt = impl.transform(src, tgt))) {
                    return ptDst;   // Already a view over the PROJ4J coordinate tuple.
                }
            } else {
                tgt = impl.transform(src, new ProjCoordinate());
                if (ptDst == null) {
                    return new PositionWrapper(tgt);
                }
            }
        } catch (Proj4jException e) {
            throw cannotTransform(e);
        }
        PositionWrapper.setLocation(tgt, ptDst);
        return ptDst;
    }

    /**
     * Unsupported operation.
     */
    @Override
    public Matrix derivative(DirectPosition point) throws TransformException {
        throw new TransformException("Derivatives are not supported.");
    }

    /**
     * validates the number of points argument.
     */
    static void checkNumPts(final int numPts) {
        if (numPts < 0) {
            throw new IllegalArgumentException("Number of points shall be positive.");
        }
    }

    /**
     * Wraps the given PROJ4J exception in a GeoAPI exception.
     *
     * @param  e  the PROJ4J exception
     * @return the GeoAPI exception
     */
    static TransformException cannotTransform(final Proj4jException e) {
        return new TransformException(e.getMessage(), e);
    }
}

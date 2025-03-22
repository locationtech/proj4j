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

import java.util.Arrays;
import org.locationtech.proj4j.BasicCoordinateTransform;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.ProjCoordinate;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;


/**
 * Wraps a PROJ4J transform behind the equivalent GeoAPI interface for the three-dimensional case.
 * The exact type of the operation (conversion, transformation or concatenated) is unknown.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
@SuppressWarnings("serial")
class TransformWrapper3D extends TransformWrapper {
    /**
     * The inverse of this wrapper, computed when first requested.
     *
     * @see #inverse()
     */
    private transient TransformWrapper3D inverse;

    /**
     * Creates a new wrapper for the given PROJ4J implementation.
     */
    TransformWrapper3D(final CoordinateTransform impl) {
        super(impl);
    }

    /**
     * {@return the number of dimensions of input coordinates, which is 3}.
     */
    @Override
    public final int getSourceDimensions() {
        return TRIDIMENSIONAL;
    }

    /**
     * {@return the number of dimensions of output coordinates, which is 3}.
     */
    @Override
    public final int getTargetDimensions() {
        return TRIDIMENSIONAL;
    }

    /**
     * Transforms coordinate tuples in the given arrays in double precision.
     * This is the most frequently used method.
     */
    @Override
    public void transform(double[] srcPts, int srcOff,
                          double[] dstPts, int dstOff, int numPts) throws TransformException
    {
        checkNumPts(numPts);
        if (srcPts == dstPts && srcOff < dstOff) {
            // If there is an overlap, we need a copy.
            int end = srcOff + numPts * TRIDIMENSIONAL;
            if (end > dstOff) {
                srcPts = Arrays.copyOfRange(srcPts, srcOff, end);
                srcOff = 0;
            }
        }
        final ProjCoordinate src = new ProjCoordinate();
        final ProjCoordinate tgt = new ProjCoordinate();
        ProjCoordinate result;
        while (--numPts >= 0) {
            src.x = srcPts[srcOff++];
            src.y = srcPts[srcOff++];
            src.z = srcPts[srcOff++];
            try {
                result = impl.transform(src, tgt);
            } catch (Proj4jException e) {
                throw cannotTransform(e);
            }
            dstPts[dstOff++] = result.x;
            dstPts[dstOff++] = result.y;
            dstPts[dstOff++] = result.z;
        }
    }

    /**
     * Transforms coordinate tuples in the given arrays in single precision.
     * This is a copy of the double-precision variant of this method with only cast added.
     */
    @Override
    public void transform(float[] srcPts, int srcOff,
                          float[] dstPts, int dstOff, int numPts) throws TransformException
    {
        checkNumPts(numPts);
        if (srcPts == dstPts && srcOff < dstOff) {
            // If there is an overlap, we need a copy.
            int end = srcOff + numPts * TRIDIMENSIONAL;
            if (end > dstOff) {
                srcPts = Arrays.copyOfRange(srcPts, srcOff, end);
                srcOff = 0;
            }
        }
        final ProjCoordinate src = new ProjCoordinate();
        final ProjCoordinate tgt = new ProjCoordinate();
        ProjCoordinate result;
        while (--numPts >= 0) {
            src.x = srcPts[srcOff++];
            src.y = srcPts[srcOff++];
            src.z = srcPts[srcOff++];
            try {
                result = impl.transform(src, tgt);
            } catch (Proj4jException e) {
                throw cannotTransform(e);
            }
            dstPts[dstOff++] = (float) result.x;
            dstPts[dstOff++] = (float) result.y;
            dstPts[dstOff++] = (float) result.z;
        }
    }

    /**
     * Transforms coordinate tuples in the given arrays, with source coordinates converted from single precision.
     */
    @Override
    public void transform(final float[]  srcPts, int srcOff,
                          final double[] dstPts, int dstOff, int numPts) throws TransformException
    {
        checkNumPts(numPts);
        final ProjCoordinate src = new ProjCoordinate();
        final ProjCoordinate tgt = new ProjCoordinate();
        ProjCoordinate result;
        while (--numPts >= 0) {
            src.x = srcPts[srcOff++];
            src.y = srcPts[srcOff++];
            src.z = srcPts[srcOff++];
            try {
                result = impl.transform(src, tgt);
            } catch (Proj4jException e) {
                throw cannotTransform(e);
            }
            dstPts[dstOff++] = result.x;
            dstPts[dstOff++] = result.y;
            dstPts[dstOff++] = result.z;
        }
    }

    /**
     * Transforms coordinate tuples in the given arrays, with target coordinates converted to single precision.
     */
    @Override
    public void transform(final double[] srcPts, int srcOff,
                          final float[]  dstPts, int dstOff, int numPts) throws TransformException
    {
        checkNumPts(numPts);
        final ProjCoordinate src = new ProjCoordinate();
        final ProjCoordinate tgt = new ProjCoordinate();
        ProjCoordinate result;
        while (--numPts >= 0) {
            src.x = srcPts[srcOff++];
            src.y = srcPts[srcOff++];
            src.z = srcPts[srcOff++];
            try {
                result = impl.transform(src, tgt);
            } catch (Proj4jException e) {
                throw cannotTransform(e);
            }
            dstPts[dstOff++] = (float) result.x;
            dstPts[dstOff++] = (float) result.y;
            dstPts[dstOff++] = (float) result.z;
        }
    }

    /**
     * {@return the inverse of this coordinate operation}.
     */
    @Override
    public synchronized MathTransform inverse() {
        TransformWrapper3D cached = inverse;
        if (cached == null) {
            if (isIdentity()) {
                cached = this;
            } else {
                cached = new TransformWrapper3D(new BasicCoordinateTransform(impl.getTargetCRS(), impl.getSourceCRS()));
                cached.inverse = this;
            }
            inverse = cached;
        }
        return cached;
    }
}

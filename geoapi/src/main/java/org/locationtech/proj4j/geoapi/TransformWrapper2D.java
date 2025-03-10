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

import java.awt.Shape;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.util.Arrays;
import org.locationtech.proj4j.BasicCoordinateTransform;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.ProjCoordinate;
import org.opengis.referencing.operation.MathTransform2D;
import org.opengis.referencing.operation.Matrix;
import org.opengis.referencing.operation.TransformException;


/**
 * Wraps a PROJ4J transform behind the equivalent GeoAPI interface for the two-dimensional case.
 * The exact type of the operation (conversion, transformation or concatenated) is unknown.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
@SuppressWarnings("serial")
class TransformWrapper2D extends TransformWrapper implements MathTransform2D {
    /**
     * The inverse of this wrapper, computed when first requested.
     *
     * @see #inverse()
     */
    private transient TransformWrapper2D inverse;

    /**
     * Creates a new wrapper for the given PROJ4J implementation.
     */
    TransformWrapper2D(final CoordinateTransform impl) {
        super(impl);
    }

    /**
     * {@return the number of dimensions of input coordinates, which is 2}.
     * This number of dimensions is implied by the {@link MathTransform2D}
     * interface implemented by this class.
     */
    @Override
    public final int getSourceDimensions() {
        return BIDIMENSIONAL;
    }

    /**
     * {@return the number of dimensions of output coordinates, which is 2}.
     * This number of dimensions is implied by the {@link MathTransform2D}
     * interface implemented by this class.
     */
    @Override
    public final int getTargetDimensions() {
        return BIDIMENSIONAL;
    }

    /**
     * Transforms the specified {@code ptSrc} and stores the result in {@code ptDst}.
     */
    @Override
    public Point2D transform(Point2D ptSrc, Point2D ptDst) throws TransformException {
        ProjCoordinate src = new ProjCoordinate(ptSrc.getX(), ptSrc.getY());
        ProjCoordinate tgt = new ProjCoordinate();
        try {
            tgt = impl.transform(src, tgt);
        } catch (Proj4jException e) {
            throw cannotTransform(e);
        }
        if (ptDst == null) {
            return new Point2D.Double(tgt.x, tgt.y);
        } else {
            ptDst.setLocation(tgt.x, tgt.y);
            return ptDst;
        }
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
        if (srcPts == dstPts && srcOff > dstOff) {
            int end = srcOff + numPts * BIDIMENSIONAL;
            if (end < dstOff) {
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
            try {
                result = impl.transform(src, tgt);
            } catch (Proj4jException e) {
                throw cannotTransform(e);
            }
            dstPts[dstOff++] = result.x;
            dstPts[dstOff++] = result.y;
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
        if (srcPts == dstPts && srcOff > dstOff) {
            int end = srcOff + numPts * BIDIMENSIONAL;
            if (end < dstOff) {
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
            try {
                result = impl.transform(src, tgt);
            } catch (Proj4jException e) {
                throw cannotTransform(e);
            }
            dstPts[dstOff++] = (float) result.x;
            dstPts[dstOff++] = (float) result.y;
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
            try {
                result = impl.transform(src, tgt);
            } catch (Proj4jException e) {
                throw cannotTransform(e);
            }
            dstPts[dstOff++] = result.x;
            dstPts[dstOff++] = result.y;
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
            try {
                result = impl.transform(src, tgt);
            } catch (Proj4jException e) {
                throw cannotTransform(e);
            }
            dstPts[dstOff++] = (float) result.x;
            dstPts[dstOff++] = (float) result.y;
        }
    }

    /**
     * Transforms the given shape. This simple implementation transforms the control points.
     * It does not check if some straight lines should be converted to curves.
     */
    @Override
    public Shape createTransformedShape(Shape shape) throws TransformException {
        final PathIterator it = shape.getPathIterator(null);
        final Path2D.Double path = new Path2D.Double(it.getWindingRule());
        final double[] buffer = new double[6];
        while (!it.isDone()) {
            switch (it.currentSegment(buffer)) {
                case PathIterator.SEG_CLOSE: {
                    path.closePath();
                    break;
                }
                case PathIterator.SEG_MOVETO: {
                    transform(buffer, 0, buffer, 0, 1);
                    path.moveTo(buffer[0], buffer[1]);
                    break;
                }
                case PathIterator.SEG_LINETO: {
                    transform(buffer, 0, buffer, 0, 1);
                    path.lineTo(buffer[0], buffer[1]);
                    break;
                }
                case PathIterator.SEG_QUADTO: {
                    transform(buffer, 0, buffer, 0, 2);
                    path.quadTo(buffer[0], buffer[1], buffer[2], buffer[3]);
                    break;
                }
                case PathIterator.SEG_CUBICTO: {
                    transform(buffer, 0, buffer, 0, 3);
                    path.curveTo(buffer[0], buffer[1], buffer[2], buffer[3], buffer[4], buffer[5]);
                    break;
                }
            }
            it.next();
        }
        return path;
    }

    /**
     * Unsupported operation.
     */
    @Override
    public Matrix derivative(Point2D point) throws TransformException {
        throw new TransformException("Derivatives are not supported.");
    }

    /**
     * {@return the inverse of this coordinate operation}.
     */
    @Override
    public synchronized MathTransform2D inverse() {
        TransformWrapper2D cached = inverse;
        if (cached == null) {
            if (isIdentity()) {
                cached = this;
            } else {
                cached = new TransformWrapper2D(new BasicCoordinateTransform(impl.getTargetCRS(), impl.getSourceCRS()));
                cached.inverse = this;
            }
            inverse = cached;
        }
        return cached;
    }
}

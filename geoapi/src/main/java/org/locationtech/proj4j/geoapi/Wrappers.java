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

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.datum.AxisOrder;
import org.locationtech.proj4j.proj.Projection;
import org.opengis.geometry.DirectPosition;
import org.opengis.parameter.ParameterValueGroup;
import org.opengis.referencing.crs.CRSAuthorityFactory;
import org.opengis.referencing.crs.GeographicCRS;
import org.opengis.referencing.crs.ProjectedCRS;
import org.opengis.referencing.crs.SingleCRS;
import org.opengis.referencing.cs.CoordinateSystem;
import org.opengis.referencing.datum.Ellipsoid;
import org.opengis.referencing.datum.GeodeticDatum;
import org.opengis.referencing.datum.PrimeMeridian;
import org.opengis.referencing.operation.CoordinateOperation;
import org.opengis.referencing.operation.CoordinateOperationFactory;


/**
 * Views of PROJ4J implementation classes as GeoAPI objects.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
public final class Wrappers {
    /**
     * Do not allow instantiation of this class.
     */
    private Wrappers() {
    }

    /**
     * Wraps the given PROJ4J <abbr>CRS</abbr> factory behind the equivalent GeoAPI interface.
     * The returned factory support only the creation of geographic and projected <abbr>CRS</abbr>s.
     *
     * @param  impl the implementation to wrap, or {@code null}
     * @return the view, or {@code null} if the given implementation was null
     */
    public static CRSAuthorityFactory geoapi(final CRSFactory impl) {
        return AuthorityFactoryWrapper.wrap(impl);
    }

    /**
     * Returns the given authority factory as a PROJ4J implementation.
     * This method returns the backing implementation.
     *
     * <p>This is a convenience method for {@link Importer#convert(CRSAuthorityFactory)}
     * on a default instance of {@code Importer}.</p>
     *
     * @param  src  the object to unwrap or convert, or {@code null}
     * @return the PROJ4J implementation, or {@code null} if the given object was null
     * @throws UnconvertibleInstanceException if the given object cannot be unwrapped or converted
     */
    public static CRSFactory proj4j(final CRSAuthorityFactory src) {
        return Importer.DEFAULT.convert(src);
    }

    /**
     * Wraps the given PROJ4J <abbr>CRS</abbr> behind the equivalent GeoAPI interface.
     * The returned object is a <em>view</em>: if any {@code impl} value is changed after this method call,
     * those changes will be reflected immediately in the returned view. Note that <abbr>CRS</abbr> objects
     * should be immutable. Therefore, it is recommended to not apply any change on {@code impl}.
     *
     * <p>There is one exception to above paragraph: this method determines immediately whether the given
     * <abbr>CRS</abbr> is a {@link GeographicCRS} or {@link ProjectedCRS}. That type of the view cannot
     * be changed after construction.</p>
     *
     * @param  impl the implementation to wrap, or {@code null}
     * @param  is3D whether to return a three-dimensional CRS instead of a two-dimensional one
     * @return the view, or {@code null} if the given implementation was null
     */
    public static SingleCRS geoapi(final org.locationtech.proj4j.CoordinateReferenceSystem impl, boolean is3D) {
        return AbstractCRS.wrap(impl, is3D);
    }

    /**
     * Returns the given <abbr>CRS</abbr> as a PROJ4J implementation.
     * This method tries to return the backing implementation if possible,
     * or otherwise copies the properties in a new PROJ4J instance.
     *
     * <p>This is a convenience method for {@link Importer#convert(SingleCRS)}
     * on a default instance of {@code Importer}.</p>
     *
     * @param  src the object to unwrap or convert, or {@code null}
     * @return the PROJ4J implementation, or {@code null} if the given object was null
     * @throws UnconvertibleInstanceException if the given object cannot be unwrapped or converted
     */
    public static org.locationtech.proj4j.CoordinateReferenceSystem proj4j(final SingleCRS src) {
        return Importer.DEFAULT.convert(src);
    }

    /**
     * Wraps the given PROJ4J datum behind the equivalent GeoAPI interface.
     * The returned object is a <em>view</em>: if any {@code impl} value is changed after this method call,
     * those changes will be reflected immediately in the returned view. Note that <abbr>CRS</abbr> objects
     * should be immutable. Therefore, it is recommended to not apply any change on {@code impl}.
     *
     * @param  impl the implementation to wrap, or {@code null}
     * @return the view, or {@code null} if the given implementation was null
     */
    public static GeodeticDatum geoapi(final org.locationtech.proj4j.datum.Datum impl) {
        return DatumWrapper.wrap(impl);
    }

    /**
     * Returns the given datum as a PROJ4J implementation.
     * This method tries to return the backing implementation if possible,
     * or otherwise copies the properties in a new PROJ4J instance.
     *
     * <p>This is a convenience method for {@link Importer#convert(GeodeticDatum)}
     * on a default instance of {@code Importer}.</p>
     *
     * @param  src the object to unwrap or convert, or {@code null}
     * @return the PROJ4J implementation, or {@code null} if the given object was null
     * @throws UnconvertibleInstanceException if the given object cannot be unwrapped or converted
     */
    public static org.locationtech.proj4j.datum.Datum proj4j(final GeodeticDatum src) {
        return Importer.DEFAULT.convert(src);
    }

    /**
     * Wraps the given PROJ4J ellipsoid behind the equivalent GeoAPI interface.
     * The returned object is a <em>view</em>: if any {@code impl} value is changed after this method call,
     * those changes will be reflected immediately in the returned view. Note that <abbr>CRS</abbr> objects
     * should be immutable. Therefore, it is recommended to not apply any change on {@code impl}.
     *
     * @param  impl the implementation to wrap, or {@code null}
     * @return the view, or {@code null} if the given implementation was null
     */
    public static Ellipsoid geoapi(final org.locationtech.proj4j.datum.Ellipsoid impl) {
        return EllipsoidWrapper.wrap(impl);
    }

    /**
     * Returns the given ellipsoid as a PROJ4J implementation.
     * This method tries to return the backing implementation if possible,
     * or otherwise copies the properties in a new PROJ4J instance.
     *
     * <p>This is a convenience method for {@link Importer#convert(Ellipsoid)}
     * on a default instance of {@code Importer}.</p>
     *
     * @param  src the object to unwrap or convert, or {@code null}
     * @return the PROJ4J implementation, or {@code null} if the given object was null
     * @throws UnconvertibleInstanceException if the given object cannot be unwrapped or converted
     */
    public static org.locationtech.proj4j.datum.Ellipsoid proj4j(final Ellipsoid src) {
        return Importer.DEFAULT.convert(src);
    }

    /**
     * Wraps the given PROJ4J ellipsoid behind the equivalent GeoAPI interface.
     * The returned object is a <em>view</em>: if any {@code impl} value is changed after this method call,
     * those changes will be reflected immediately in the returned view. Note that <abbr>CRS</abbr> objects
     * should be immutable. Therefore, it is recommended to not apply any change on {@code impl}.
     *
     * @param  impl the implementation to wrap, or {@code null}
     * @return the view, or {@code null} if the given implementation was null
     */
    public static PrimeMeridian geoapi(final org.locationtech.proj4j.datum.PrimeMeridian impl) {
        return PrimeMeridianWrapper.wrap(impl);
    }

    /**
     * Returns the given prime meridian as a PROJ4J implementation.
     * This method tries to return the backing implementation if possible,
     * or an equivalent PROJ4J instance otherwise.
     *
     * <p>This is a convenience method for {@link Importer#convert(PrimeMeridian)}
     * on a default instance of {@code Importer}.</p>
     *
     * @param  src the object to unwrap, or {@code null}
     * @return the PROJ4J implementation, or {@code null} if the given object was null
     * @throws UnconvertibleInstanceException if the given object cannot be unwrapped or converted
     */
    public static org.locationtech.proj4j.datum.PrimeMeridian proj4j(final PrimeMeridian src) {
        return Importer.DEFAULT.convert(src);
    }

    /**
     * Wraps the given PROJ4J projection behind the equivalent GeoAPI interface.
     * The returned object is a <em>view</em>: if any {@code impl} value is changed after this method call,
     * those changes will be reflected immediately in the returned view. The view is bidirectional:
     * setting a value in the returned parameters modify a property of the given projection.
     *
     * @param  impl the implementation to wrap, or {@code null}
     * @return the view, or {@code null} if the given implementation was null
     */
    public static ParameterValueGroup geoapi(final Projection impl) {
        return OperationMethodWrapper.wrap(impl);
    }

    /**
     * Returns the given parameters as a PROJ4J implementation.
     * This method tries to return the backing implementation if possible,
     * or an equivalent PROJ4J instance otherwise.
     *
     * <p>This is a convenience method for {@link Importer#convert(ParameterValueGroup)}
     * on a default instance of {@code Importer}.</p>
     *
     * @param  src the object to unwrap, or {@code null}
     * @return the PROJ4J implementation, or {@code null} if the given object was null
     * @throws UnconvertibleInstanceException if the given object cannot be unwrapped or converted
     */
    public static Projection proj4j(final ParameterValueGroup src) {
        return Importer.DEFAULT.convert(src);
    }

    /**
     * Wraps the given PROJ4J coordinate operation factory behind the equivalent GeoAPI interface.
     *
     * @param  impl the implementation to wrap, or {@code null}
     * @return the view, or {@code null} if the given implementation was null
     */
    public static CoordinateOperationFactory geoapi(final CoordinateTransformFactory impl) {
        return OperationFactoryWrapper.wrap(impl);
    }

    /**
     * Returns the given coordinate operation factory as a PROJ4J implementation.
     * This method returns the backing implementation.
     *
     * <p>This is a convenience method for {@link Importer#convert(CoordinateOperationFactory)}
     * on a default instance of {@code Importer}.</p>
     *
     * @param  src  the object to unwrap or convert, or {@code null}
     * @return the PROJ4J implementation, or {@code null} if the given object was null
     * @throws UnconvertibleInstanceException if the given object cannot be unwrapped or converted
     */
    public static CoordinateTransformFactory proj4j(final CoordinateOperationFactory src) {
        return Importer.DEFAULT.convert(src);
    }

    /**
     * Wraps the given PROJ4J coordinate transform behind the equivalent GeoAPI interface.
     * The returned object is a <em>view</em>: if any {@code impl} value is changed after this method call,
     * those changes will be reflected immediately in the returned view. Note that referencing objects
     * should be immutable. Therefore, it is recommended to not apply any change on {@code impl}.
     *
     * @param  impl the implementation to wrap, or {@code null}
     * @param  is3D whether to return a three-dimensional operation instead of a two-dimensional one
     * @return the view, or {@code null} if the given implementation was null
     */
    public static CoordinateOperation geoapi(final CoordinateTransform impl, final boolean is3D) {
        return TransformWrapper.wrap(impl, is3D);
    }

    /**
     * Returns the given coordinate operation as a PROJ4J implementation.
     * This method returns the backing implementation.
     *
     * <p>This is a convenience method for {@link Importer#convert(CoordinateOperation)}
     * on a default instance of {@code Importer}.</p>
     *
     * @param  src  the object to unwrap or convert, or {@code null}
     * @return the PROJ4J implementation, or {@code null} if the given object was null
     * @throws UnconvertibleInstanceException if the given object cannot be unwrapped or converted
     */
    public static CoordinateTransform proj4j(final CoordinateOperation src) {
        return Importer.DEFAULT.convert(src);
    }

    /**
     * Wraps the given PROJ4J coordinate tuple behind the equivalent GeoAPI interface.
     * The returned object is a <em>view</em>: if any {@code impl} value is changed after this method call,
     * those changes will be reflected immediately in the returned view. Conversely, setting a value in the
     * returned view set the corresponding value in the given implementation.
     *
     * @param  impl the implementation to wrap, or {@code null}
     * @return the view, or {@code null} if the given implementation was null
     */
    public static DirectPosition geoapi(final ProjCoordinate impl) {
        return PositionWrapper.wrap(impl);
    }

    /**
     * Returns the given position as a PROJ4J coordinate tuple.
     * This method tries to return the backing implementation if possible,
     * or otherwise copies the coordinate values in a new coordinate tuple.
     *
     * @param  src the position to unwrap or convert, or {@code null}
     * @return the coordinates, or {@code null} if the given object was null
     */
    public static ProjCoordinate proj4j(final DirectPosition src) {
        return PositionWrapper.unwrapOrCopy(src);
    }

    /**
     * Returns the axis order of the given coordinate system.
     *
     * @param  cs the coordinate system for which to get the axis order, or {@code null}
     * @return the axis order, or {@code null} if the given coordinate system was null
     * @throws UnconvertibleInstanceException if the coordinate system uses an unsupported axis order
     */
    public static AxisOrder axisOrder(final CoordinateSystem cs) {
        return (cs != null) ? AxisOrder.fromString(Importer.axisOrder(cs)) : null;
    }
}

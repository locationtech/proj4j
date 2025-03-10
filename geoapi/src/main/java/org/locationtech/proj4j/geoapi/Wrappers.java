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

import org.opengis.referencing.crs.GeographicCRS;
import org.opengis.referencing.crs.ProjectedCRS;
import org.opengis.referencing.crs.SingleCRS;
import org.opengis.referencing.datum.Ellipsoid;
import org.opengis.referencing.datum.GeodeticDatum;
import org.opengis.referencing.datum.PrimeMeridian;


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
     * @return the view, or {@code null} if the given implementation was null
     */
    public static SingleCRS geoapi(final org.locationtech.proj4j.CoordinateReferenceSystem impl) {
        return AbstractCRS.wrap(impl);
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
}

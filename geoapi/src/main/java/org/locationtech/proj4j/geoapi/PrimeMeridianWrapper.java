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
import javax.measure.Unit;
import javax.measure.quantity.Angle;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.proj.Projection;
import org.opengis.referencing.datum.PrimeMeridian;


/**
 * Wraps a PROJ4J implementation behind the equivalent GeoAPI interface.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
@SuppressWarnings("serial")
final class PrimeMeridianWrapper extends Wrapper implements PrimeMeridian, Serializable {
    /**
     * The Greenwich prime meridian.
     */
    private static final PrimeMeridianWrapper GREENWICH = new PrimeMeridianWrapper(
            org.locationtech.proj4j.datum.PrimeMeridian.forName("greenwich"));

    /**
     * The wrapped PROJ4 implementation.
     */
    private final org.locationtech.proj4j.datum.PrimeMeridian impl;

    /**
     * Creates a new wrapper for the given PROJ4J implementation.
     */
    private PrimeMeridianWrapper(final org.locationtech.proj4j.datum.PrimeMeridian impl) {
        this.impl = impl;
    }

    /**
     * Wraps the given implementation.
     *
     * @param  impl the implementation to wrap, or {@code null}
     * @return the wrapper, or Greenwich if the given implementation was null
     */
    static PrimeMeridianWrapper wrap(final org.locationtech.proj4j.datum.PrimeMeridian impl) {
        return (impl != null) ? new PrimeMeridianWrapper(impl) : GREENWICH;
    }

    /**
     * Returns the prime meridian of the given projection if different from Greenwich.
     *
     * @param  proj  the projection from which to get the prime meridian, or {@code null}
     * @return the prime meridian if different than Greenwich, or {@code null} otherwise.
     */
    static org.locationtech.proj4j.datum.PrimeMeridian ifNonGreenwich(final Projection proj) {
        if (proj != null) {
            org.locationtech.proj4j.datum.PrimeMeridian impl = proj.getPrimeMeridian();
            if (impl != null && !GREENWICH.impl.equals(impl)) {
                return impl;
            }
        }
        return null;
    }

    /**
     * {@return the PROJ4J backing implementation}.
     */
    @Override
    Object implementation() {
        return impl;
    }

    /**
     * {@return the name}.
     */
    @Override
    public String getCode() {
        return impl.getName();
    }

    @Override
    public double getGreenwichLongitude() {
        ProjCoordinate coord = new ProjCoordinate();
        impl.toGreenwich(coord);
        return coord.x;
    }

    @Override
    public Unit<Angle> getAngularUnit() {
        return Units.getInstance().degree;
    }
}

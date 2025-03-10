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
import java.util.Date;
import org.locationtech.proj4j.datum.Datum;
import org.opengis.referencing.datum.Ellipsoid;
import org.opengis.referencing.datum.GeodeticDatum;
import org.opengis.referencing.datum.PrimeMeridian;
import org.opengis.util.GenericName;
import org.opengis.util.InternationalString;


/**
 * Wraps a PROJ4J implementation behind the equivalent GeoAPI interface.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
@SuppressWarnings("serial")
final class DatumWrapper extends Wrapper implements GeodeticDatum, Serializable {
    /**
     * The wrapped PROJ4 implementation.
     */
    private final org.locationtech.proj4j.datum.Datum impl;

    /**
     * The prime meridian, or {@code null} for Greenwich
     */
    private final org.locationtech.proj4j.datum.PrimeMeridian pm;

    /**
     * Creates a new wrapper for the given PROJ4J implementation.
     */
    private DatumWrapper(final org.locationtech.proj4j.datum.Datum impl,
                         final org.locationtech.proj4j.datum.PrimeMeridian pm)
    {
        this.impl = impl;
        this.pm = pm;
    }

    /**
     * Wraps the given implementation.
     *
     * @param  impl the implementation to wrap, or {@code null}
     * @return the wrapper, or {@code null} if the given implementation was null
     */
    static DatumWrapper wrap(final org.locationtech.proj4j.datum.Datum impl) {
        return (impl != null) ? new DatumWrapper(impl, null) : null;
    }

    /**
     * Wraps the given implementation.
     *
     * @param  crs the CRS to wrap, or {@code null}
     * @return the wrapper, or {@code null} if the given implementation was null
     */
    static DatumWrapper wrap(final org.locationtech.proj4j.CoordinateReferenceSystem crs) {
        if (crs != null) {
            Datum impl = crs.getDatum();
            if (impl != null) {
                return new DatumWrapper(impl, PrimeMeridianWrapper.ifNonGreenwich(crs.getProjection()));
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
     * {@return the long name if available, or the short name otherwise}.
     * In the EPSG database, the primary name is usually the long name.
     */
    @Override
    public String getCode() {
        String name = impl.getName();
        if (name == null) {
            name = impl.getCode();
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
            return Alias.wrap(impl.getCode());
        }
        return super.getAlias();
    }

    /**
     * {@return the PROJ4J ellipsoid wrapped behind the GeoAPI interface}.
     */
    @Override
    public Ellipsoid getEllipsoid() {
        return EllipsoidWrapper.wrap(impl.getEllipsoid());
    }

    /**
     * {@return the hard-coded Greenwich prime meridian}.
     */
    @Override
    public PrimeMeridian getPrimeMeridian() {
        return PrimeMeridianWrapper.wrap(pm);
    }

    @Override
    public InternationalString getAnchorPoint() {
        return null;
    }

    @Override
    public Date getRealizationEpoch() {
        return null;
    }
}

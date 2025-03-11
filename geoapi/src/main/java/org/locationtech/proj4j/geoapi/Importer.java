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
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import javax.measure.Unit;
import javax.measure.UnitConverter;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.datum.AxisOrder;
import org.locationtech.proj4j.proj.LongLatProjection;
import org.locationtech.proj4j.proj.Projection;
import org.opengis.metadata.citation.Citation;
import org.opengis.parameter.GeneralParameterValue;
import org.opengis.parameter.ParameterValue;
import org.opengis.parameter.ParameterValueGroup;
import org.opengis.referencing.IdentifiedObject;
import org.opengis.referencing.ReferenceIdentifier;
import org.opengis.referencing.crs.CRSAuthorityFactory;
import org.opengis.referencing.crs.GeographicCRS;
import org.opengis.referencing.crs.ProjectedCRS;
import org.opengis.referencing.crs.SingleCRS;
import org.opengis.referencing.cs.AxisDirection;
import org.opengis.referencing.cs.CoordinateSystem;
import org.opengis.referencing.datum.Ellipsoid;
import org.opengis.referencing.datum.GeodeticDatum;
import org.opengis.referencing.datum.PrimeMeridian;
import org.opengis.referencing.operation.CoordinateOperation;
import org.opengis.referencing.operation.CoordinateOperationFactory;
import org.opengis.util.Factory;
import org.opengis.util.GenericName;
import org.opengis.util.InternationalString;
import org.opengis.util.NameSpace;


/**
 * Builder of PROJ4J objects from GeoAPI objects. If the GeoAPI object has been created by a
 * call to a {@code Wrappers.geoapi(…)} method, then the wrapped object is returned directly.
 * Otherwise, this class tries to creates new PROJ4J instances using the information provided
 * in the GeoAPI object. It may fail, in which case an {@link UnconvertibleInstanceException}
 * is thrown.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
public class Importer {
    /**
     * Possible name spaces for PROJ4J operation methods, case-insensitive.
     */
    private static final String[] PROJ_NAMESPACES = {"PROJ", "PROJ4", "PROJ.4", "PROJ4J"};

    /**
     * Possible name spaces for OGC parameters, case-insensitive.
     * ESRI parameters are usually the same as OGC parameters except for the case.
     */
    private static final String[] OGC_NAMESPACES = {"OGC", "ESRI"};

    /**
     * Axis directions supported by PROJ4J. The PROJ4J code for each axis direction
     * is the first letter of the name of code list value, converted to lower case.
     */
    private static final Set<AxisDirection> SUPPORTED_AXIS_DIRECTIONS = new HashSet<>(
            Arrays.asList(AxisDirection.NORTH, AxisDirection.SOUTH,
                          AxisDirection.EAST,  AxisDirection.WEST,
                          AxisDirection.UP,    AxisDirection.DOWN));

    /**
     * A registry for creating {@link Projection} instances if needed.
     * If {@code null}, a default instance will be created when first needed.
     *
     * @see #getRegistry()
     */
    protected Registry registry;

    /**
     * Default instance used by {@code Wrappers.proj4j(…)} methods.
     */
    static final Importer DEFAULT = new Importer();

    /**
     * Creates a default instance.
     */
    public Importer() {
    }

    /**
     * Creates an importer which will use the given registry.
     *
     * @param  registry  a registry for creating {@link Projection} instances, or {@code null} for default
     */
    public Importer(final Registry registry) {
        this.registry = registry;
    }

    /**
     * {@return the registry to use for creating PROJ4J objects from a name}.
     * If no registry was specified at construction time, a default instance
     * is created the first time that this method is invoked.
     */
    public synchronized Registry getRegistry() {
        if (registry == null) {
            registry = new Registry();
        }
        return registry;
    }

    /**
     * Returns the given authority factory as a PROJ4J implementation.
     * This method returns the backing implementation.
     * If the given factory is not backed by a PROJ4J implementation,
     * then the current implementation throws an exception.
     *
     * @param  src  the object to unwrap or convert, or {@code null}
     * @return the PROJ4J implementation, or {@code null} if the given object was null
     * @throws UnconvertibleInstanceException if the given object cannot be unwrapped or converted
     */
    public CRSFactory convert(final CRSAuthorityFactory src) {
        if (src == null) {
            return null;
        }
        if (src instanceof AuthorityFactoryWrapper) {
            return ((AuthorityFactoryWrapper) src).impl;
        }
        throw new UnconvertibleInstanceException(getVendorName(src), "authority factory");
    }

    /**
     * Returns the given <abbr>CRS</abbr> as a PROJ4J implementation.
     * This method tries to return the backing implementation if possible,
     * or otherwise copies the properties in a new PROJ4J instance.
     *
     * @param  src  the object to unwrap or convert, or {@code null}
     * @return the PROJ4J implementation, or {@code null} if the given object was null
     * @throws UnconvertibleInstanceException if the given object cannot be unwrapped or converted
     */
    public org.locationtech.proj4j.CoordinateReferenceSystem convert(final SingleCRS src) {
        if (src == null) {
            return null;
        }
        if (src instanceof AbstractCRS) {
            return ((AbstractCRS) src).impl;
        }
        /*
         * Try to map to the PROJ4J `Projection` object, including the parameter values.
         * The `Projection` class is determined by the CRS type and the operation method.
         */
        final GeodeticDatum frame;
        final Projection projection;
        if (src instanceof GeographicCRS) {
            frame = ((GeographicCRS) src).getDatum();
            projection = new LongLatProjection();
        } else if (src instanceof ProjectedCRS) {
            ProjectedCRS p = (ProjectedCRS) src;
            frame = p.getDatum();
            projection = convert(p.getConversionFromBase().getParameterValues());
        } else {
            throw new UnconvertibleInstanceException("The CRS must be geographic or projected.");
        }
        /*
         * Set the `Projection` properties other than the parameters defined by the operation method.
         * These properties are the CRS name, datum, ellipsoid, prime meridian and coordinate system.
         * In the ISO 19111 model, these properties are in separated objects (not in the projection).
         */
        final String name = getName(src);
        projection.setName(name);

        final org.locationtech.proj4j.datum.Datum datum = convert(frame);
        projection.setEllipsoid(datum.getEllipsoid());
        projection.setPrimeMeridian(convert(frame.getPrimeMeridian()).getName());

        final CoordinateSystem cs = src.getCoordinateSystem();
        projection.setAxisOrder(axisOrder(cs));     // Checks the number of dimension as a side-effect.
        final Unit<?> unit = cs.getAxis(0).getUnit();
        if (!Objects.equals(unit, cs.getAxis(1).getUnit())) {
            throw new UnconvertibleInstanceException("Heterogeneous unit of measurement.");
        } else if (unit != null) {
            projection.setUnits(Units.getInstance().proj4j(unit));
        }
        return new org.locationtech.proj4j.CoordinateReferenceSystem(name, null, datum, projection);
    }

    /**
     * Returns the axis order of the given coordinate system.
     *
     * @param  cs the coordinate system for which to get the axis order
     * @return the 3-letters code of axis order to be given to {@link AxisOrder#fromString(String)}.
     * @throws UnconvertibleInstanceException if the coordinate system uses an unsupported axis order
     */
    static String axisOrder(final CoordinateSystem cs) {
        final int dimension = cs.getDimension();
        if (dimension < Wrapper.BIDIMENSIONAL || dimension > Wrapper.TRIDIMENSIONAL) {
            throw new UnconvertibleInstanceException("Unsupported " + dimension + " dimensional coordinate system.");
        }
        final char[] directions = new char[Wrapper.TRIDIMENSIONAL];
        directions[Wrapper.TRIDIMENSIONAL - 1] = 'u';   // Default value
        for (int i=0; i<dimension; i++) {
            final AxisDirection dir = cs.getAxis(i).getDirection();
            if (SUPPORTED_AXIS_DIRECTIONS.contains(dir)) {
                directions[i] = Character.toLowerCase(dir.name().charAt(0));
            } else {
                throw new UnconvertibleInstanceException("Unsupported \"" + dir.identifier() + "\" axis direction.");
            }
        }
        return new String(directions);
    }

    /**
     * Returns the given datum as a PROJ4J implementation.
     * This method tries to return the backing implementation if possible,
     * or otherwise copies the properties in a new PROJ4J instance.
     *
     * @param  src  the object to unwrap or convert, or {@code null}
     * @return the PROJ4J implementation, or {@code null} if the given object was null
     * @throws UnconvertibleInstanceException if the given object cannot be unwrapped or converted
     */
    public org.locationtech.proj4j.datum.Datum convert(final GeodeticDatum src) {
        if (src == null) {
            return null;
        }
        if (src instanceof DatumWrapper) {
            return ((DatumWrapper) src).impl;
        }
        return new org.locationtech.proj4j.datum.Datum(null, null, null,
                    convert(src.getEllipsoid()), getName(src));
    }

    /**
     * Returns the given ellipsoid as a PROJ4J implementation.
     * This method tries to return the backing implementation if possible,
     * or otherwise copies the properties in a new PROJ4J instance.
     *
     * @param  src  the object to unwrap or convert, or {@code null}
     * @return the PROJ4J implementation, or {@code null} if the given object was null
     * @throws UnconvertibleInstanceException if the given object cannot be unwrapped or converted
     */
    public org.locationtech.proj4j.datum.Ellipsoid convert(final Ellipsoid src) {
        if (src == null) {
            return null;
        }
        if (src instanceof EllipsoidWrapper) {
            return ((EllipsoidWrapper) src).impl;
        }
        final String name  = getName(src);
        final String alias = getAlias(src, null);
        final UnitConverter c = src.getAxisUnit().getConverterTo(Units.getInstance().metre);
        final double a = c.convert(src.getSemiMajorAxis());
        if (src.isIvfDefinitive()) {
            return new org.locationtech.proj4j.datum.Ellipsoid(alias, a, 0, src.getInverseFlattening(), name);
        } else {
            final double b = c.convert(src.getSemiMinorAxis());
            return new org.locationtech.proj4j.datum.Ellipsoid(alias, a, b, 0, name);
        }
    }

    /**
     * Returns the given prime meridian as a PROJ4J implementation.
     * This method tries to return the backing implementation if possible,
     * or an equivalent PROJ4J instance otherwise.
     *
     * @param  src  the object to unwrap, or {@code null}
     * @return the PROJ4J implementation, or {@code null} if the given object was null
     * @throws UnconvertibleInstanceException if the given object cannot be unwrapped or converted
     */
    public org.locationtech.proj4j.datum.PrimeMeridian convert(final PrimeMeridian src) {
        if (src == null) {
            return null;
        }
        if (src instanceof PrimeMeridianWrapper) {
            return ((PrimeMeridianWrapper) src).impl;
        }
        final String name = getName(src);
        if (name != null) {
            org.locationtech.proj4j.datum.PrimeMeridian pm;
            pm = org.locationtech.proj4j.datum.PrimeMeridian.forName(name.toLowerCase(Locale.US));
            if (src.getGreenwichLongitude() == 0 || !pm.getName().equalsIgnoreCase("greenwich")) {
                // Above check is needed because `forName` defaults to Greenwich for all unrecognized prime meridians.
                return pm;
            }
        }
        throw new UnconvertibleInstanceException(name, "prime meridian");
    }

    /**
     * Returns the given parameters as a PROJ4J implementation.
     * This method tries to return the backing implementation if possible,
     * or an equivalent PROJ4J instance otherwise.
     *
     * @param  src  the object to unwrap, or {@code null}
     * @return the PROJ4J implementation, or {@code null} if the given object was null
     * @throws UnconvertibleInstanceException if the given object cannot be unwrapped or converted
     */
    public Projection convert(final ParameterValueGroup src) {
        if (src == null) {
            return null;
        }
        if (src instanceof OperationMethodWrapper) {
            return ((OperationMethodWrapper) src).impl;
        }
        final String method = getNameOrAlias(src.getDescriptor(), PROJ_NAMESPACES);
        final Projection proj = getRegistry().getProjection(method);
        if (proj == null) {
            throw new UnconvertibleInstanceException("Cannot map \"" + method + "\" to a PROJ4J projection.");
        }
        for (final GeneralParameterValue value : src.values()) {
            final String name = getNameOrAlias(value.getDescriptor(), OGC_NAMESPACES);
            try {
                final ParameterAccessor ac = ParameterAccessor.forName(name);
                ac.set(proj, ((ParameterValue<?>) value).doubleValue(ac.getUnit()));
            } catch (IllegalArgumentException | IllegalStateException | ClassCastException e) {
                throw (UnconvertibleInstanceException) new UnconvertibleInstanceException(
                        "Cannot map \"" + name + "\" to a PROJ4J parameter.").initCause(e);
            }
        }
        return proj;
    }

    /**
     * Returns the given coordinate operation factory as a PROJ4J implementation.
     * This method returns the backing implementation.
     * If the given factory is not backed by a PROJ4J implementation,
     * then the current implementation throws an exception.
     *
     * @param  src  the object to unwrap or convert, or {@code null}
     * @return the PROJ4J implementation, or {@code null} if the given object was null
     * @throws UnconvertibleInstanceException if the given object cannot be unwrapped or converted
     */
    public CoordinateTransformFactory convert(final CoordinateOperationFactory src) {
        if (src == null) {
            return null;
        }
        if (src instanceof OperationFactoryWrapper) {
            return ((OperationFactoryWrapper) src).impl;
        }
        throw new UnconvertibleInstanceException(getVendorName(src), "operation factory");
    }

    /**
     * Returns the given coordinate operation as a PROJ4J implementation.
     * This method returns the backing implementation.
     * If the given factory is not backed by a PROJ4J implementation,
     * then the current implementation throws an exception.
     *
     * @param  src  the object to unwrap or convert, or {@code null}
     * @return the PROJ4J implementation, or {@code null} if the given object was null
     * @throws UnconvertibleInstanceException if the given object cannot be unwrapped or converted
     */
    public CoordinateTransform convert(final CoordinateOperation src) {
        if (src == null) {
            return null;
        }
        if (src instanceof TransformWrapper) {
            return ((TransformWrapper) src).impl;
        }
        throw new UnconvertibleInstanceException(getName(src), "coordinate operation");
    }

    /**
     * Returns the name of the implementer of the given factory.
     * This is used for error messages.
     *
     * @param  factory  the factory for which to get the implementer name
     * @return name of the implementer of the given factory
     */
    private static String getVendorName(final Factory factory) {
        final Citation vendor = factory.getVendor();
        if (vendor != null) {
            InternationalString title = vendor.getTitle();
            if (title != null) {
                return title.toString();
            }
        }
        return factory.getClass().getSimpleName();
    }

    /**
     * {@return the name of the given identified object}. This method is null-safe.
     * Null safety is theoretically not necessary because the name is mandatory, but we try to be safe.
     *
     * @param src the object for which to get the name, or {@code null}
     */
    private static String getName(final IdentifiedObject src) {
        if (src != null) {
            ReferenceIdentifier id = src.getName();
            if (id != null) {
                return id.getCode();
            }
        }
        return null;
    }

    /**
     * Returns the first alias of the given identified object which is in the given scope.
     * Aliases are often used for abbreviations.
     *
     * @param  src    the object for which to get an alias, or {@code null}
     * @param  scope  scope of the alias to get, or {@code null} for the first alias regardless is scope
     * @return the first alias, or {@code null} if none
     */
    private static String getAlias(final IdentifiedObject src, final String scope) {
        if (src != null) {
            for (GenericName name : src.getAlias()) {
                name = name.tip();
                if (scope == null) {
                    return name.toString();
                }
                NameSpace ns = name.scope();
                if (ns != null && !ns.isGlobal() && scope.equalsIgnoreCase(ns.name().tip().toString())) {
                    return name.toString();
                }
            }
        }
        return null;
    }

    /**
     * Returns the primary name or the first alias having one of the the given name spaces.
     * If no name or alias is found, then the first non-null name or alias is returned.
     *
     * @param  src     the object for which to get a name or alias in the given name spaces
     * @param  scopes  the desired name spaces, case-insensitive
     * @return the first name in one of the given name space if any, or an arbitrary name otherwise
     */
    private static String getNameOrAlias(final IdentifiedObject src, final String[] scopes) {
        final ReferenceIdentifier name = src.getName();
        if (name != null) {
            final String ns = name.getCodeSpace();
            for (String scope : scopes) {
                if (scope.equalsIgnoreCase(ns)) {
                    return name.getCode();
                }
            }
        }
        for (String scope : scopes) {
            final String alias = getAlias(src, scope);
            if (alias != null) {
                return alias;
            }
        }
        return (name != null) ? name.getCode() : getAlias(src, null);
    }
}

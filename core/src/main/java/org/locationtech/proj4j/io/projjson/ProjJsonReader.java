/*
 * Copyright 2026, PROJ4J contributors
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
package org.locationtech.proj4j.io.projjson;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.io.wkt.AxisDefinition;
import org.locationtech.proj4j.io.wkt.AxisOrderPolicy;
import org.locationtech.proj4j.io.wkt.ConversionDefinition;
import org.locationtech.proj4j.io.wkt.CoordinateSystemDefinition;
import org.locationtech.proj4j.io.wkt.CrsDefinition;
import org.locationtech.proj4j.io.wkt.CrsDefinitions;
import org.locationtech.proj4j.io.wkt.DatumDefinition;
import org.locationtech.proj4j.io.wkt.EllipsoidDefinition;
import org.locationtech.proj4j.io.wkt.Identifier;
import org.locationtech.proj4j.io.wkt.ParameterDefinition;
import org.locationtech.proj4j.io.wkt.PrimeMeridianDefinition;
import org.locationtech.proj4j.io.wkt.UnitDefinition;
import org.locationtech.proj4j.io.wkt.WktParseException;

/**
 * Reads PROJJSON into a {@link CrsDefinition}, and from there into a
 * {@link CoordinateReferenceSystem}.
 * <p>
 * PROJJSON carries the same content as WKT2 in a different syntax, so this reader produces the same
 * {@link CrsDefinition} model the WKT reader does; everything downstream — the PROJ parameter
 * synthesis, the axis order policy, the writers — is shared. Follows PROJ 9.8.1's
 * {@code data/projjson.schema.json} and the {@code json_import} tests in
 * {@code test/unit/test_io.cpp}.
 * <p>
 * Instances are immutable and safe to share between threads.
 */
public final class ProjJsonReader {

    private final AxisOrderPolicy axisOrderPolicy;

    /**
     * A reader with the default {@link AxisOrderPolicy#LEGACY} policy: longitude-first, exactly as
     * proj4j 1.4.3 behaves.
     */
    public ProjJsonReader() {
        this(AxisOrderPolicy.LEGACY);
    }

    public ProjJsonReader(AxisOrderPolicy axisOrderPolicy) {
        if (axisOrderPolicy == null) {
            throw new IllegalArgumentException("axisOrderPolicy is null");
        }
        this.axisOrderPolicy = axisOrderPolicy;
    }

    public AxisOrderPolicy getAxisOrderPolicy() {
        return axisOrderPolicy;
    }

    /**
     * Parses PROJJSON into a definition, retaining everything the document said — including the
     * declared axis order, which is applied only when a CRS is built and only if the policy says
     * so.
     *
     * @throws WktParseException if the text is not well-formed PROJJSON, or describes something
     *                           this library cannot represent
     */
    public CrsDefinition readDefinition(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map)) {
            throw new WktParseException("PROJJSON must be a JSON object");
        }
        return crs(asObject(root, "root"), 1);
    }

    /**
     * Parses PROJJSON and builds a proj4j CRS from it, applying this reader's
     * {@link AxisOrderPolicy}.
     */
    public CoordinateReferenceSystem read(String json) {
        return CrsDefinitions.toCrs(readDefinition(json), axisOrderPolicy);
    }

    // ------------------------------------------------------------------ elements

    /**
     * {@code depth} counts nested coordinate reference systems, the outermost being 1, and is
     * bounded by {@link JsonLimits#MAX_CRS_DEPTH}. {@link Json#parse} has already bounded the
     * document, but this is a second recursion over the parsed tree — {@code crs -> compound -> crs}
     * and {@code crs -> bound -> crs} — with its own, lower limit.
     */
    private CrsDefinition crs(Map<String, Object> o, int depth) {
        if (depth > JsonLimits.MAX_CRS_DEPTH) {
            throw new WktParseException("CRSs nested more than " + JsonLimits.MAX_CRS_DEPTH
                    + " deep in PROJJSON; refusing to recurse further");
        }
        String type = string(o, "type");
        if (type == null) {
            throw new WktParseException("PROJJSON object has no \"type\" member");
        }
        CrsDefinition def = new CrsDefinition();
        def.setName(string(o, "name"));
        if (type.equals("GeographicCRS") || type.equals("GeodeticCRS")) {
            geodetic(o, def);
        } else if (type.equals("ProjectedCRS")) {
            projected(o, def);
        } else if (type.equals("VerticalCRS")) {
            vertical(o, def);
        } else if (type.equals("CompoundCRS")) {
            compound(o, def, depth);
        } else if (type.equals("BoundCRS")) {
            bound(o, def, depth);
        } else if (type.equals("EngineeringCRS")) {
            def.setKind(CrsDefinition.Kind.ENGINEERING);
            def.setCoordinateSystem(coordinateSystem(object(o, "coordinate_system")));
        } else {
            throw new WktParseException("PROJJSON type \"" + type
                    + "\" is not a coordinate reference system this library supports");
        }
        metadata(o, def);
        return def;
    }

    private void geodetic(Map<String, Object> o, CrsDefinition def) {
        def.setDatum(datum(o));
        CoordinateSystemDefinition cs = coordinateSystem(object(o, "coordinate_system"));
        def.setCoordinateSystem(cs);
        boolean cartesian = CoordinateSystemDefinition.CARTESIAN.equalsIgnoreCase(cs.getType());
        def.setKind(cartesian ? CrsDefinition.Kind.GEOCENTRIC : CrsDefinition.Kind.GEOGRAPHIC);
    }

    private void projected(Map<String, Object> o, CrsDefinition def) {
        def.setKind(CrsDefinition.Kind.PROJECTED);
        Map<String, Object> base = object(o, "base_crs");
        if (base == null) {
            throw new WktParseException("a ProjectedCRS needs a \"base_crs\"");
        }
        CrsDefinition baseDef = new CrsDefinition();
        baseDef.setName(string(base, "name"));
        geodetic(base, baseDef);
        metadata(base, baseDef);
        def.setBaseCrs(baseDef);
        def.setConversion(conversion(object(o, "conversion")));
        def.setCoordinateSystem(coordinateSystem(object(o, "coordinate_system")));
    }

    private void vertical(Map<String, Object> o, CrsDefinition def) {
        def.setKind(CrsDefinition.Kind.VERTICAL);
        Map<String, Object> d = object(o, "datum");
        if (d == null) {
            d = object(o, "datum_ensemble");
        }
        if (d != null) {
            DatumDefinition datum = new DatumDefinition();
            datum.setName(string(d, "name"));
            datum.setId(id(d));
            def.setDatum(datum);
        }
        def.setCoordinateSystem(coordinateSystem(object(o, "coordinate_system")));
    }

    private void compound(Map<String, Object> o, CrsDefinition def, int depth) {
        def.setKind(CrsDefinition.Kind.COMPOUND);
        List<Object> components = array(o, "components");
        if (components == null || components.isEmpty()) {
            throw new WktParseException("a CompoundCRS needs \"components\"");
        }
        for (int i = 0; i < components.size(); i++) {
            def.addComponent(crs(asObject(components.get(i), "component"), depth + 1));
        }
    }

    private void bound(Map<String, Object> o, CrsDefinition def, int depth) {
        def.setKind(CrsDefinition.Kind.BOUND);
        Map<String, Object> source = object(o, "source_crs");
        if (source == null) {
            throw new WktParseException("a BoundCRS needs a \"source_crs\"");
        }
        CrsDefinition sourceDef = crs(source, depth + 1);
        def.setBaseCrs(sourceDef);
        if (def.getName() == null) {
            def.setName(sourceDef.getName());
        }
        Map<String, Object> target = object(o, "target_crs");
        if (target != null) {
            def.setHubCrs(crs(target, depth + 1));
        }
        Map<String, Object> transformation = object(o, "transformation");
        if (transformation != null) {
            def.setToWgs84(helmert(transformation));
        }
    }

    private DatumDefinition datum(Map<String, Object> o) {
        Map<String, Object> d = object(o, "datum");
        boolean ensemble = false;
        if (d == null) {
            d = object(o, "datum_ensemble");
            ensemble = d != null;
        }
        if (d == null) {
            throw new WktParseException("a geodetic CRS needs a \"datum\" or a \"datum_ensemble\"");
        }
        DatumDefinition datum = new DatumDefinition();
        datum.setName(string(d, "name"));
        datum.setId(id(d));
        datum.setAnchor(string(d, "anchor"));
        Double epoch = number(d, "frame_reference_epoch");
        if (epoch != null) {
            datum.setFrameEpoch(epoch.doubleValue());
        }
        Map<String, Object> e = object(d, "ellipsoid");
        if (e == null && ensemble) {
            e = object(d, "ellipsoid");
        }
        if (e != null) {
            datum.setEllipsoid(ellipsoid(e));
        } else if (!ensemble) {
            throw new WktParseException("datum \"" + datum.getName() + "\" has no \"ellipsoid\"");
        }
        Map<String, Object> pm = object(d, "prime_meridian");
        datum.setPrimeMeridian(pm == null ? PrimeMeridianDefinition.greenwich()
                : primeMeridian(pm));
        return datum;
    }

    private EllipsoidDefinition ellipsoid(Map<String, Object> o) {
        EllipsoidDefinition e = new EllipsoidDefinition();
        e.setName(string(o, "name"));
        e.setId(id(o));
        Double radius = number(o, "radius");
        if (radius != null) {
            e.setSemiMajorAxis(radius.doubleValue());
            e.setSemiMinorAxis(radius.doubleValue());
            e.setInverseFlattening(0.0);
            e.setUnit(UnitDefinition.METRE);
            return e;
        }
        Double a = number(o, "semi_major_axis");
        if (a == null) {
            Object measure = o.get("semi_major_axis");
            if (measure instanceof Map) {
                Map<String, Object> m = asObject(measure, "semi_major_axis");
                a = number(m, "value");
                e.setUnit(unit(m.get("unit"), UnitDefinition.LINEAR));
            }
        }
        if (a == null) {
            throw new WktParseException("ellipsoid \"" + e.getName()
                    + "\" has no \"semi_major_axis\" or \"radius\"");
        }
        e.setSemiMajorAxis(a.doubleValue());
        Double rf = number(o, "inverse_flattening");
        if (rf != null) {
            e.setInverseFlattening(rf.doubleValue());
            return e;
        }
        Object minor = o.get("semi_minor_axis");
        if (minor instanceof Map) {
            Map<String, Object> m = asObject(minor, "semi_minor_axis");
            Double b = number(m, "value");
            if (b == null) {
                throw new WktParseException("\"semi_minor_axis\" has no value");
            }
            UnitDefinition u = unit(m.get("unit"), UnitDefinition.LINEAR);
            // Both axes must be in the same unit before a flattening can be computed from them.
            double bMetres = u == null ? b.doubleValue() : u.toBase(b.doubleValue());
            double aMetres = e.getUnit() == null ? e.getSemiMajorAxis()
                    : e.getUnit().toBase(e.getSemiMajorAxis());
            e.setSemiMinorAxis(e.getUnit() == null ? bMetres
                    : e.getUnit().fromBase(bMetres));
            if (aMetres == bMetres) {
                e.setInverseFlattening(0.0);
            }
            return e;
        }
        if (minor instanceof Double) {
            e.setSemiMinorAxis(((Double) minor).doubleValue());
            return e;
        }
        throw new WktParseException("ellipsoid \"" + e.getName()
                + "\" has neither \"inverse_flattening\" nor \"semi_minor_axis\"");
    }

    private PrimeMeridianDefinition primeMeridian(Map<String, Object> o) {
        PrimeMeridianDefinition pm = new PrimeMeridianDefinition();
        pm.setName(string(o, "name"));
        pm.setId(id(o));
        Object longitude = o.get("longitude");
        if (longitude == null) {
            throw new WktParseException("prime meridian \"" + pm.getName()
                    + "\" has no \"longitude\"");
        }
        if (longitude instanceof Double) {
            pm.setLongitude(((Double) longitude).doubleValue());
            pm.setUnit(UnitDefinition.DEGREE);
            return pm;
        }
        Map<String, Object> m = asObject(longitude, "longitude");
        Double v = number(m, "value");
        if (v == null) {
            throw new WktParseException("prime meridian longitude has no value");
        }
        pm.setLongitude(v.doubleValue());
        pm.setUnit(unit(m.get("unit"), UnitDefinition.ANGULAR));
        return pm;
    }

    private ConversionDefinition conversion(Map<String, Object> o) {
        if (o == null) {
            throw new WktParseException("a ProjectedCRS needs a \"conversion\"");
        }
        ConversionDefinition conv = new ConversionDefinition();
        conv.setName(string(o, "name"));
        conv.setId(id(o));
        Map<String, Object> method = object(o, "method");
        if (method == null) {
            throw new WktParseException("conversion \"" + conv.getName() + "\" has no \"method\"");
        }
        conv.setMethodName(string(method, "name"));
        conv.setMethodId(id(method));
        List<Object> params = array(o, "parameters");
        if (params != null) {
            for (int i = 0; i < params.size(); i++) {
                Map<String, Object> p = asObject(params.get(i), "parameter");
                ParameterDefinition pd = new ParameterDefinition();
                pd.setName(string(p, "name"));
                pd.setId(id(p));
                Double v = number(p, "value");
                if (v == null) {
                    throw new WktParseException("parameter \"" + pd.getName() + "\" has no value");
                }
                pd.setValue(v.doubleValue());
                pd.setUnit(unit(p.get("unit"), -1));
                conv.addParameter(pd);
            }
        }
        return conv;
    }

    private double[] helmert(Map<String, Object> o) {
        Map<String, Object> method = object(o, "method");
        String methodName = method == null ? "" : string(method, "name");
        boolean coordinateFrame = methodName != null
                // Locale.ROOT: this decides the Helmert rotation SIGN convention by matching
                // an ASCII method name, so it must not follow the ambient locale.
                && methodName.toLowerCase(Locale.ROOT).contains("coordinate frame");
        double[] v = new double[7];
        boolean rotations = false;
        List<Object> params = array(o, "parameters");
        if (params == null) {
            throw new WktParseException("a BoundCRS transformation needs \"parameters\"");
        }
        for (int i = 0; i < params.size(); i++) {
            Map<String, Object> p = asObject(params.get(i), "parameter");
            String name = string(p, "name");
            Double value = number(p, "value");
            if (name == null || value == null) {
                continue;
            }
            int index = helmertIndex(name);
            if (index < 0) {
                continue;
            }
            double x = value.doubleValue();
            UnitDefinition u = unit(p.get("unit"), -1);
            if (index >= 3 && index <= 5) {
                if (u != null && u.getType() == UnitDefinition.ANGULAR) {
                    x = u.toBase(x) / UnitDefinition.ARC_SECOND.getConversionFactor();
                }
                if (coordinateFrame) {
                    x = -x;
                }
                rotations = true;
            } else if (index == 6) {
                if (u != null && u.getConversionFactor() != 1.0) {
                    x = u.toBase(x) * 1e6;
                } else if (Math.abs(x - 1.0) < 0.1) {
                    // The abridged convention, as WKT2's ABRIDGEDTRANSFORMATION uses it: the
                    // value is 1 + s * 1e-6 rather than s in parts per million.
                    x = (x - 1.0) * 1e6;
                }
                rotations = true;
            } else if (u != null && u.getType() == UnitDefinition.LINEAR) {
                x = u.toBase(x);
            }
            v[index] = x;
        }
        if (!rotations) {
            return new double[]{v[0], v[1], v[2]};
        }
        return v;
    }

    private int helmertIndex(String name) {
        // Locale.ROOT: an ASCII parameter-name key, compared against ASCII literals below.
        String n = name.toLowerCase(Locale.ROOT).replace("-", " ");
        if (n.startsWith("x axis translation")) {
            return 0;
        }
        if (n.startsWith("y axis translation")) {
            return 1;
        }
        if (n.startsWith("z axis translation")) {
            return 2;
        }
        if (n.startsWith("x axis rotation")) {
            return 3;
        }
        if (n.startsWith("y axis rotation")) {
            return 4;
        }
        if (n.startsWith("z axis rotation")) {
            return 5;
        }
        if (n.startsWith("scale difference")) {
            return 6;
        }
        return -1;
    }

    private CoordinateSystemDefinition coordinateSystem(Map<String, Object> o) {
        if (o == null) {
            throw new WktParseException("a CRS needs a \"coordinate_system\"");
        }
        CoordinateSystemDefinition cs = new CoordinateSystemDefinition();
        cs.setType(string(o, "subtype"));
        List<Object> axes = array(o, "axis");
        if (axes == null) {
            throw new WktParseException("a coordinate system needs an \"axis\" array");
        }
        int type = CoordinateSystemDefinition.ELLIPSOIDAL.equalsIgnoreCase(cs.getType())
                ? UnitDefinition.ANGULAR : UnitDefinition.LINEAR;
        for (int i = 0; i < axes.size(); i++) {
            Map<String, Object> a = asObject(axes.get(i), "axis");
            AxisDefinition axis = new AxisDefinition();
            axis.setName(string(a, "name"));
            axis.setAbbreviation(string(a, "abbreviation"));
            axis.setDirection(string(a, "direction"));
            axis.setUnit(unit(a.get("unit"), type));
            cs.addAxis(axis);
            if (cs.getUnit() == null) {
                cs.setUnit(axis.getUnit());
            }
        }
        return cs;
    }

    private void metadata(Map<String, Object> o, CrsDefinition def) {
        Identifier one = id(o);
        if (one != null) {
            def.addId(one);
        }
        List<Object> ids = array(o, "ids");
        if (ids != null) {
            for (int i = 0; i < ids.size(); i++) {
                def.addId(identifier(asObject(ids.get(i), "id")));
            }
        }
        def.setRemark(string(o, "remarks"));
        def.setScope(string(o, "scope"));
        def.setAreaDescription(string(o, "area"));
        Map<String, Object> bbox = object(o, "bbox");
        if (bbox != null) {
            def.setBoundingBox(new double[]{
                    required(bbox, "south_latitude"), required(bbox, "west_longitude"),
                    required(bbox, "north_latitude"), required(bbox, "east_longitude"),
            });
        }
        List<Object> usages = array(o, "usages");
        if (usages != null && !usages.isEmpty()) {
            Map<String, Object> first = asObject(usages.get(0), "usage");
            if (def.getScope() == null) {
                def.setScope(string(first, "scope"));
            }
            if (def.getAreaDescription() == null) {
                def.setAreaDescription(string(first, "area"));
            }
            Map<String, Object> b = object(first, "bbox");
            if (b != null && def.getBoundingBox() == null) {
                def.setBoundingBox(new double[]{
                        required(b, "south_latitude"), required(b, "west_longitude"),
                        required(b, "north_latitude"), required(b, "east_longitude"),
                });
            }
        }
    }

    // ------------------------------------------------------------------- helpers

    /**
     * A unit, which PROJJSON spells either as a short name ({@code "degree"}, {@code "metre"},
     * {@code "unity"}) or as an object with a type, a name and a conversion factor.
     */
    private UnitDefinition unit(Object value, int expectedType) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            String name = (String) value;
            if (name.equals("degree")) {
                return UnitDefinition.DEGREE;
            }
            if (name.equals("metre")) {
                return UnitDefinition.METRE;
            }
            if (name.equals("unity")) {
                return UnitDefinition.UNITY;
            }
            throw new WktParseException("unit \"" + name + "\" is not one of PROJJSON's three "
                    + "short names (degree, metre, unity); use an object with a conversion factor");
        }
        Map<String, Object> o = asObject(value, "unit");
        String type = string(o, "type");
        int unitType = expectedType;
        if ("AngularUnit".equals(type)) {
            unitType = UnitDefinition.ANGULAR;
        } else if ("LinearUnit".equals(type)) {
            unitType = UnitDefinition.LINEAR;
        } else if ("ScaleUnit".equals(type)) {
            unitType = UnitDefinition.SCALE;
        } else if ("TimeUnit".equals(type)) {
            unitType = UnitDefinition.TIME;
        } else if ("ParametricUnit".equals(type)) {
            unitType = UnitDefinition.PARAMETRIC;
        }
        Double factor = number(o, "conversion_factor");
        String name = string(o, "name");
        if (factor == null) {
            throw new WktParseException("unit \"" + name + "\" has no \"conversion_factor\"");
        }
        if (unitType < 0) {
            unitType = UnitDefinition.LINEAR;
        }
        return new UnitDefinition(name, factor.doubleValue(), unitType, id(o));
    }

    private Identifier id(Map<String, Object> o) {
        Map<String, Object> idObject = object(o, "id");
        return idObject == null ? null : identifier(idObject);
    }

    private Identifier identifier(Map<String, Object> o) {
        String authority = string(o, "authority");
        Object code = o.get("code");
        if (authority == null || code == null) {
            throw new WktParseException("an \"id\" needs an \"authority\" and a \"code\"");
        }
        String codeText = code instanceof Double ? JsonNumber.format(((Double) code).doubleValue())
                : String.valueOf(code);
        return new Identifier(authority, codeText);
    }

    private double required(Map<String, Object> o, String key) {
        Double v = number(o, key);
        if (v == null) {
            throw new WktParseException("missing numeric member \"" + key + "\"");
        }
        return v.doubleValue();
    }

    private String string(Map<String, Object> o, String key) {
        Object v = o.get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof String)) {
            throw new WktParseException("member \"" + key + "\" must be a string");
        }
        return (String) v;
    }

    private Double number(Map<String, Object> o, String key) {
        Object v = o.get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof Double)) {
            throw new WktParseException("member \"" + key + "\" must be a number");
        }
        return (Double) v;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Map<String, Object> o, String key) {
        Object v = o.get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof Map)) {
            throw new WktParseException("member \"" + key + "\" must be an object");
        }
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    private List<Object> array(Map<String, Object> o, String key) {
        Object v = o.get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof List)) {
            throw new WktParseException("member \"" + key + "\" must be an array");
        }
        return (List<Object>) v;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asObject(Object v, String what) {
        if (!(v instanceof Map)) {
            throw new WktParseException(what + " must be a JSON object");
        }
        return (Map<String, Object>) v;
    }
}

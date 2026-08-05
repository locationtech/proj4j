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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.io.wkt.AxisDefinition;
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
 * Writes a {@link CrsDefinition} as PROJJSON.
 * <p>
 * Member order and two-space indentation follow PROJ's own {@code exportToJSON}, so a definition
 * read from PROJJSON is written back byte-for-byte. The {@code $schema} member is emitted only when
 * a schema has been set, matching PROJ's {@code JSONFormatter::setSchema}; upstream's own tests set
 * it to a placeholder for exactly that reason.
 * <p>
 * Instances are immutable; {@link #withSchema} returns a new one.
 */
public final class ProjJsonWriter {

    /** The schema PROJ 9.8.1 emits by default. */
    public static final String DEFAULT_SCHEMA =
            "https://proj.org/schemas/v0.7/projjson.schema.json";

    private final String schema;

    /**
     * A writer emitting {@link #DEFAULT_SCHEMA}.
     */
    public ProjJsonWriter() {
        this(DEFAULT_SCHEMA);
    }

    private ProjJsonWriter(String schema) {
        this.schema = schema;
    }

    /**
     * A writer emitting the given {@code $schema}, or none at all when {@code schema} is
     * {@code null}.
     */
    public ProjJsonWriter withSchema(String schema) {
        return new ProjJsonWriter(schema);
    }

    public String getSchema() {
        return schema;
    }

    /**
     * Writes a definition as PROJJSON.
     */
    public String write(CrsDefinition def) {
        return Json.write(toMap(def, true, 1));
    }

    /**
     * Describes an existing proj4j CRS with {@link CrsDefinitions#fromCrs} and writes that.
     */
    public String write(CoordinateReferenceSystem crs) {
        return write(CrsDefinitions.fromCrs(crs));
    }

    // ------------------------------------------------------------------ elements

    /**
     * {@code depth} counts nested coordinate reference systems, the outermost being 1, and is
     * bounded by {@link JsonLimits#MAX_CRS_DEPTH}. {@link CrsDefinition} is public and mutable, so
     * a caller can hand the writer a graph of any depth — including one that contains itself.
     */
    private Map<String, Object> toMap(CrsDefinition def, boolean root, int depth) {
        if (depth > JsonLimits.MAX_CRS_DEPTH) {
            throw new WktParseException("CRSs nested more than " + JsonLimits.MAX_CRS_DEPTH
                    + " deep; refusing to write further");
        }
        if (def == null || def.getKind() == null) {
            throw new WktParseException("CRS definition is null or has no kind");
        }
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        if (root && schema != null) {
            o.put("$schema", schema);
        }
        switch (def.getKind()) {
            case GEOGRAPHIC:
                o.put("type", "GeographicCRS");
                o.put("name", name(def));
                o.put("datum", datum(def.getDatum()));
                o.put("coordinate_system", coordinateSystem(def.getCoordinateSystem()));
                break;
            case GEOCENTRIC:
                o.put("type", "GeodeticCRS");
                o.put("name", name(def));
                o.put("datum", datum(def.getDatum()));
                o.put("coordinate_system", coordinateSystem(def.getCoordinateSystem()));
                break;
            case PROJECTED:
                o.put("type", "ProjectedCRS");
                o.put("name", name(def));
                o.put("base_crs", toMap(def.getBaseCrs(), false, depth + 1));
                o.put("conversion", conversion(def.getConversion()));
                o.put("coordinate_system", coordinateSystem(def.getCoordinateSystem()));
                break;
            case VERTICAL:
                o.put("type", "VerticalCRS");
                o.put("name", name(def));
                if (def.getDatum() != null) {
                    Map<String, Object> d = new LinkedHashMap<String, Object>();
                    d.put("type", "VerticalReferenceFrame");
                    d.put("name", def.getDatum().getName() == null ? "unknown"
                            : def.getDatum().getName());
                    putId(d, def.getDatum().getId());
                    o.put("datum", d);
                }
                o.put("coordinate_system", coordinateSystem(def.getCoordinateSystem()));
                break;
            case COMPOUND:
                o.put("type", "CompoundCRS");
                o.put("name", name(def));
                List<Object> components = new ArrayList<Object>();
                for (int i = 0; i < def.getComponents().size(); i++) {
                    components.add(toMap(def.getComponents().get(i), false, depth + 1));
                }
                o.put("components", components);
                break;
            case BOUND:
                o.put("type", "BoundCRS");
                o.put("source_crs", toMap(def.getBaseCrs(), false, depth + 1));
                o.put("target_crs", toMap(def.getHubCrs() != null ? def.getHubCrs() : wgs84(),
                        false, depth + 1));
                o.put("transformation", transformation(def.getToWgs84()));
                return o;
            case ENGINEERING:
                o.put("type", "EngineeringCRS");
                o.put("name", name(def));
                o.put("coordinate_system", coordinateSystem(def.getCoordinateSystem()));
                break;
            default:
                throw new WktParseException("cannot write a " + def.getKind()
                        + " CRS as PROJJSON");
        }
        if (def.getScope() != null) {
            o.put("scope", def.getScope());
        }
        if (def.getAreaDescription() != null) {
            o.put("area", def.getAreaDescription());
        }
        if (def.getBoundingBox() != null && def.getBoundingBox().length == 4) {
            Map<String, Object> bbox = new LinkedHashMap<String, Object>();
            bbox.put("south_latitude", Double.valueOf(def.getBoundingBox()[0]));
            bbox.put("west_longitude", Double.valueOf(def.getBoundingBox()[1]));
            bbox.put("north_latitude", Double.valueOf(def.getBoundingBox()[2]));
            bbox.put("east_longitude", Double.valueOf(def.getBoundingBox()[3]));
            o.put("bbox", bbox);
        }
        if (def.getIds().size() == 1) {
            putId(o, def.getId());
        } else if (def.getIds().size() > 1) {
            List<Object> ids = new ArrayList<Object>();
            for (int i = 0; i < def.getIds().size(); i++) {
                ids.add(identifier(def.getIds().get(i)));
            }
            o.put("ids", ids);
        }
        if (def.getRemark() != null) {
            o.put("remarks", def.getRemark());
        }
        return o;
    }

    private CrsDefinition wgs84() {
        CrsDefinition def = new CrsDefinition();
        def.setKind(CrsDefinition.Kind.GEOGRAPHIC);
        def.setName("WGS 84");
        DatumDefinition datum = new DatumDefinition();
        datum.setName("World Geodetic System 1984");
        EllipsoidDefinition e = new EllipsoidDefinition();
        e.setName("WGS 84");
        e.setSemiMajorAxis(6378137);
        e.setInverseFlattening(298.257223563);
        datum.setEllipsoid(e);
        datum.setPrimeMeridian(PrimeMeridianDefinition.greenwich());
        def.setDatum(datum);
        CoordinateSystemDefinition cs =
                new CoordinateSystemDefinition(CoordinateSystemDefinition.ELLIPSOIDAL);
        cs.setUnit(UnitDefinition.DEGREE);
        cs.addAxis(new AxisDefinition("Geodetic latitude", "Lat", AxisDefinition.NORTH,
                UnitDefinition.DEGREE));
        cs.addAxis(new AxisDefinition("Geodetic longitude", "Lon", AxisDefinition.EAST,
                UnitDefinition.DEGREE));
        def.setCoordinateSystem(cs);
        def.addId(new Identifier("EPSG", "4326"));
        return def;
    }

    private Map<String, Object> datum(DatumDefinition datum) {
        if (datum == null) {
            throw new WktParseException("a geodetic CRS needs a datum");
        }
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        o.put("type", datum.isDynamic() ? "DynamicGeodeticReferenceFrame"
                : "GeodeticReferenceFrame");
        o.put("name", datum.getName() == null ? "unknown" : datum.getName());
        if (datum.getAnchor() != null) {
            o.put("anchor", datum.getAnchor());
        }
        if (datum.isDynamic()) {
            o.put("frame_reference_epoch", Double.valueOf(datum.getFrameEpoch()));
        }
        if (datum.getEllipsoid() != null) {
            o.put("ellipsoid", ellipsoid(datum.getEllipsoid()));
        }
        PrimeMeridianDefinition pm = datum.getPrimeMeridian();
        if (pm != null && !pm.isGreenwich()) {
            // Greenwich is the default and PROJ does not emit it.
            Map<String, Object> p = new LinkedHashMap<String, Object>();
            p.put("name", pm.getName() == null ? "Greenwich" : pm.getName());
            if (pm.getUnit() != null && pm.getUnit().getConversionFactor()
                    != UnitDefinition.DEGREE.getConversionFactor()) {
                Map<String, Object> longitude = new LinkedHashMap<String, Object>();
                longitude.put("value", Double.valueOf(pm.getLongitude()));
                longitude.put("unit", unit(pm.getUnit()));
                p.put("longitude", longitude);
            } else {
                p.put("longitude", Double.valueOf(pm.getLongitude()));
            }
            putId(p, pm.getId());
            o.put("prime_meridian", p);
        }
        putId(o, datum.getId());
        return o;
    }

    private Map<String, Object> ellipsoid(EllipsoidDefinition e) {
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        o.put("name", e.getName() == null ? "unknown" : e.getName());
        if (e.isSphere() && !Double.isNaN(e.getSemiMinorAxis())) {
            o.put("radius", Double.valueOf(e.getSemiMajorAxis()));
            putId(o, e.getId());
            return o;
        }
        if (e.getUnit() != null && e.getUnit().getConversionFactor() != 1.0) {
            Map<String, Object> a = new LinkedHashMap<String, Object>();
            a.put("value", Double.valueOf(e.getSemiMajorAxis()));
            a.put("unit", unit(e.getUnit()));
            o.put("semi_major_axis", a);
        } else {
            o.put("semi_major_axis", Double.valueOf(e.getSemiMajorAxis()));
        }
        if (!Double.isNaN(e.getInverseFlattening())) {
            o.put("inverse_flattening", Double.valueOf(e.getInverseFlattening()));
        } else {
            o.put("semi_minor_axis", Double.valueOf(e.getSemiMinorAxis()));
        }
        putId(o, e.getId());
        return o;
    }

    private Map<String, Object> conversion(ConversionDefinition conv) {
        if (conv == null) {
            throw new WktParseException("a projected CRS needs a conversion");
        }
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        o.put("name", conv.getName() == null ? "unnamed" : conv.getName());
        Map<String, Object> method = new LinkedHashMap<String, Object>();
        method.put("name", conv.getMethodName() == null ? "unknown" : conv.getMethodName());
        putId(method, conv.getMethodId());
        o.put("method", method);
        List<Object> params = new ArrayList<Object>();
        for (int i = 0; i < conv.getParameters().size(); i++) {
            ParameterDefinition p = conv.getParameters().get(i);
            Map<String, Object> pm = new LinkedHashMap<String, Object>();
            pm.put("name", p.getName());
            pm.put("value", Double.valueOf(p.getValue()));
            if (p.getUnit() != null) {
                pm.put("unit", unit(p.getUnit()));
            }
            putId(pm, p.getId());
            params.add(pm);
        }
        o.put("parameters", params);
        putId(o, conv.getId());
        return o;
    }

    private Map<String, Object> transformation(double[] t) {
        if (t == null) {
            throw new WktParseException("a bound CRS needs transformation parameters");
        }
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        o.put("name", "Transformation to WGS84");
        Map<String, Object> method = new LinkedHashMap<String, Object>();
        boolean seven = t.length == 7;
        method.put("name", seven ? "Position Vector transformation (geog2D domain)"
                : "Geocentric translations (geog2D domain)");
        putId(method, new Identifier("EPSG", seven ? "9606" : "9603"));
        o.put("method", method);
        String[] names = {"X-axis translation", "Y-axis translation", "Z-axis translation",
                "X-axis rotation", "Y-axis rotation", "Z-axis rotation", "Scale difference"};
        String[] codes = {"8605", "8606", "8607", "8608", "8609", "8610", "8611"};
        List<Object> params = new ArrayList<Object>();
        for (int i = 0; i < t.length; i++) {
            Map<String, Object> p = new LinkedHashMap<String, Object>();
            p.put("name", names[i]);
            p.put("value", Double.valueOf(t[i]));
            p.put("unit", i < 3 ? (Object) "metre"
                    : i < 6 ? unit(UnitDefinition.ARC_SECOND) : unit(UnitDefinition.PPM));
            putId(p, new Identifier("EPSG", codes[i]));
            params.add(p);
        }
        o.put("parameters", params);
        return o;
    }

    private Map<String, Object> coordinateSystem(CoordinateSystemDefinition cs) {
        if (cs == null) {
            throw new WktParseException("a CRS needs a coordinate system");
        }
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        o.put("subtype", cs.getType() == null ? CoordinateSystemDefinition.CARTESIAN
                : cs.getType());
        List<Object> axes = new ArrayList<Object>();
        for (int i = 0; i < cs.getAxes().size(); i++) {
            AxisDefinition a = cs.getAxes().get(i);
            Map<String, Object> ao = new LinkedHashMap<String, Object>();
            ao.put("name", a.getName() == null ? "unknown" : a.getName());
            if (a.getAbbreviation() != null) {
                ao.put("abbreviation", a.getAbbreviation());
            }
            ao.put("direction", a.getDirection() == null ? AxisDefinition.UNSPECIFIED
                    : a.getDirection());
            UnitDefinition u = cs.unitOf(i);
            if (u != null) {
                ao.put("unit", unit(u));
            }
            axes.add(ao);
        }
        o.put("axis", axes);
        return o;
    }

    /**
     * A unit: one of PROJJSON's three short names when it is exactly that unit, otherwise an object
     * carrying the conversion factor.
     */
    private Object unit(UnitDefinition u) {
        if (u.getType() == UnitDefinition.ANGULAR
                && u.getConversionFactor() == UnitDefinition.DEGREE.getConversionFactor()) {
            return "degree";
        }
        if (u.getType() == UnitDefinition.LINEAR && u.getConversionFactor() == 1.0) {
            return "metre";
        }
        if (u.getType() == UnitDefinition.SCALE && u.getConversionFactor() == 1.0) {
            return "unity";
        }
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        switch (u.getType()) {
            case UnitDefinition.ANGULAR:
                o.put("type", "AngularUnit");
                break;
            case UnitDefinition.SCALE:
                o.put("type", "ScaleUnit");
                break;
            case UnitDefinition.TIME:
                o.put("type", "TimeUnit");
                break;
            case UnitDefinition.PARAMETRIC:
                o.put("type", "ParametricUnit");
                break;
            default:
                o.put("type", "LinearUnit");
        }
        o.put("name", u.getName() == null ? "unknown" : u.getName());
        o.put("conversion_factor", Double.valueOf(u.getConversionFactor()));
        return o;
    }

    private void putId(Map<String, Object> o, Identifier id) {
        if (id == null) {
            return;
        }
        o.put("id", identifier(id));
    }

    private Map<String, Object> identifier(Identifier id) {
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        o.put("authority", id.getAuthority());
        String code = id.getCode();
        boolean numeric = code != null && code.length() > 0;
        for (int i = 0; numeric && i < code.length(); i++) {
            if (!Character.isDigit(code.charAt(i))) {
                numeric = false;
            }
        }
        o.put("code", numeric ? (Object) Double.valueOf(Double.parseDouble(code)) : code);
        return o;
    }

    private String name(CrsDefinition def) {
        return def.getName() == null ? "unknown" : def.getName();
    }
}

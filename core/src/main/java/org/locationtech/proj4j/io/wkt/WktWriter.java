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
package org.locationtech.proj4j.io.wkt;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.proj4j.CoordinateReferenceSystem;

/**
 * Writes a {@link CrsDefinition} as WKT2, in either the 2019 or the 2015 revision of ISO 19162.
 * <p>
 * Everything a definition holds is written: identifiers, axis names and abbreviations, axis order,
 * units per axis, the datum anchor, the scope and area of use. A definition that came from
 * {@link WktReader} therefore round-trips; a definition derived from a bare
 * {@link CoordinateReferenceSystem} by {@link CrsDefinitions#fromCrs} writes what such a CRS
 * actually knows, which is less.
 * <p>
 * Instances are immutable; the {@code with...} methods return new ones.
 *
 * <pre>
 * String wkt2 = new WktWriter().multiline().write(definition);
 * </pre>
 */
public final class WktWriter {

    private final WktDialect dialect;
    private final String indent;

    /**
     * A writer producing single-line WKT2:2019.
     */
    public WktWriter() {
        this(WktDialect.WKT2_2019, null);
    }

    /**
     * A writer producing single-line WKT in the given dialect.
     *
     * @param dialect {@link WktDialect#WKT2_2019} or {@link WktDialect#WKT2_2015}; the WKT1
     *                dialects are readable by this package but not writable
     */
    public WktWriter(WktDialect dialect) {
        this(dialect, null);
    }

    private WktWriter(WktDialect dialect, String indent) {
        if (dialect == null || dialect.isWkt1()) {
            throw new IllegalArgumentException("this writer produces WKT2 only, not "
                    + dialect);
        }
        this.dialect = dialect;
        this.indent = indent;
    }

    /**
     * A writer that indents each level by four spaces and puts each element on its own line.
     */
    public WktWriter multiline() {
        return new WktWriter(dialect, "    ");
    }

    /**
     * A writer that indents with the given string.
     */
    public WktWriter multiline(String indent) {
        return new WktWriter(dialect, indent == null ? "    " : indent);
    }

    public WktDialect getDialect() {
        return dialect;
    }

    /**
     * Writes a definition as WKT.
     */
    public String write(CrsDefinition def) {
        StringBuilder sb = new StringBuilder();
        WktFormat.append(toNode(def), sb, indent, 0);
        return sb.toString();
    }

    /**
     * Describes an existing proj4j CRS with {@link CrsDefinitions#fromCrs} and writes that.
     */
    public String write(CoordinateReferenceSystem crs) {
        return write(CrsDefinitions.fromCrs(crs));
    }

    /**
     * The WKT tree for a definition, if a caller wants to inspect or post-process it.
     *
     * @throws WktParseException if the definition nests CRSs more than
     *                           {@link WktLimits#MAX_CRS_DEPTH} deep. {@link CrsDefinition} is a
     *                           public mutable type with a public {@code addComponent}, so this
     *                           recursion is reachable with a hand-built graph and not only with a
     *                           parsed one — and a definition that referred to itself would
     *                           otherwise recurse until the stack ran out.
     */
    public WktNode toNode(CrsDefinition def) {
        return toNode(def, 1);
    }

    private WktNode toNode(CrsDefinition def, int depth) {
        if (depth > WktLimits.MAX_CRS_DEPTH) {
            throw new WktParseException("CRSs nested more than " + WktLimits.MAX_CRS_DEPTH
                    + " deep; refusing to write further");
        }
        if (def == null || def.getKind() == null) {
            throw new WktParseException("CRS definition is null or has no kind");
        }
        switch (def.getKind()) {
            case GEOGRAPHIC:
            case GEOCENTRIC:
                return geodetic(def, false);
            case PROJECTED:
                return projected(def);
            case VERTICAL:
                return vertical(def);
            case COMPOUND:
                return compound(def, depth);
            case BOUND:
                return bound(def, depth);
            case ENGINEERING:
                return engineering(def);
            default:
                throw new WktParseException("cannot write a " + def.getKind() + " CRS as WKT2");
        }
    }

    // ------------------------------------------------------------------ elements

    private WktNode geodetic(CrsDefinition def, boolean asBase) {
        boolean geographic = def.getKind() != CrsDefinition.Kind.GEOCENTRIC;
        String keyword;
        if (asBase) {
            keyword = geographic && dialect == WktDialect.WKT2_2019 ? "BASEGEOGCRS"
                    : "BASEGEODCRS";
        } else {
            keyword = geographic && dialect == WktDialect.WKT2_2019 ? "GEOGCRS" : "GEODCRS";
        }
        List<WktNode> children = new ArrayList<WktNode>();
        children.add(WktNode.quoted(nameOf(def)));
        DatumDefinition datum = def.getDatum();
        if (datum == null) {
            throw new WktParseException("geodetic CRS \"" + def.getName() + "\" has no datum");
        }
        if (datum.isDynamic()) {
            List<WktNode> dyn = new ArrayList<WktNode>();
            dyn.add(WktNode.of("FRAMEEPOCH", one(WktNode.number(datum.getFrameEpoch()))));
            children.add(WktNode.of("DYNAMIC", dyn));
        }
        children.add(datum(datum));
        if (datum.getPrimeMeridian() != null) {
            // Written even for Greenwich, as PROJ does: the ANGLEUNIT it carries is what tells a
            // reader the unit of the CRS's angular values.
            children.add(primeMeridian(datum.getPrimeMeridian()));
        }
        if (!asBase) {
            addCoordinateSystem(children, def.getCoordinateSystem());
        } else if (def.getCoordinateSystem() != null
                && def.getCoordinateSystem().getUnit() != null
                && def.getCoordinateSystem().getUnit().getType() == UnitDefinition.ANGULAR
                && def.getCoordinateSystem().getUnit().getConversionFactor()
                != UnitDefinition.DEGREE.getConversionFactor()) {
            // A base CRS whose angular unit is not degrees must say so, or every angular
            // parameter of the conversion is read back in the wrong unit.
            children.add(unit("ANGLEUNIT", def.getCoordinateSystem().getUnit()));
        }
        addMetadata(children, def, asBase);
        return WktNode.of(keyword, children);
    }

    private WktNode projected(CrsDefinition def) {
        List<WktNode> children = new ArrayList<WktNode>();
        children.add(WktNode.quoted(nameOf(def)));
        CrsDefinition base = def.getBaseCrs();
        if (base == null) {
            throw new WktParseException("projected CRS \"" + def.getName() + "\" has no base CRS");
        }
        children.add(geodetic(base, true));
        children.add(conversion(def.getConversion()));
        addCoordinateSystem(children, def.getCoordinateSystem());
        addMetadata(children, def, false);
        return WktNode.of("PROJCRS", children);
    }

    private WktNode vertical(CrsDefinition def) {
        List<WktNode> children = new ArrayList<WktNode>();
        children.add(WktNode.quoted(nameOf(def)));
        DatumDefinition datum = def.getDatum();
        if (datum != null) {
            List<WktNode> vd = new ArrayList<WktNode>();
            vd.add(WktNode.quoted(datum.getName() == null ? "unknown" : datum.getName()));
            addId(vd, datum.getId());
            children.add(WktNode.of("VDATUM", vd));
        }
        addCoordinateSystem(children, def.getCoordinateSystem());
        addMetadata(children, def, false);
        return WktNode.of("VERTCRS", children);
    }

    private WktNode compound(CrsDefinition def, int depth) {
        List<WktNode> children = new ArrayList<WktNode>();
        children.add(WktNode.quoted(nameOf(def)));
        for (int i = 0; i < def.getComponents().size(); i++) {
            children.add(toNode(def.getComponents().get(i), depth + 1));
        }
        addMetadata(children, def, false);
        return WktNode.of("COMPOUNDCRS", children);
    }

    private WktNode bound(CrsDefinition def, int depth) {
        List<WktNode> children = new ArrayList<WktNode>();
        CrsDefinition source = def.getBaseCrs();
        if (source == null) {
            throw new WktParseException("bound CRS \"" + def.getName() + "\" has no source CRS");
        }
        children.add(WktNode.of("SOURCECRS", one(toNode(source, depth + 1))));
        CrsDefinition hub = def.getHubCrs();
        children.add(WktNode.of("TARGETCRS",
                one(toNode(hub != null ? hub : wgs84(), depth + 1))));
        double[] t = def.getToWgs84();
        if (t != null) {
            children.add(abridgedTransformation(t));
        }
        return WktNode.of("BOUNDCRS", children);
    }

    private WktNode engineering(CrsDefinition def) {
        List<WktNode> children = new ArrayList<WktNode>();
        children.add(WktNode.quoted(nameOf(def)));
        List<WktNode> ed = new ArrayList<WktNode>();
        ed.add(WktNode.quoted(def.getDatum() != null && def.getDatum().getName() != null
                ? def.getDatum().getName() : "unknown"));
        children.add(WktNode.of("EDATUM", ed));
        addCoordinateSystem(children, def.getCoordinateSystem());
        addMetadata(children, def, false);
        return WktNode.of("ENGCRS", children);
    }

    /**
     * WGS 84, for the {@code TARGETCRS} of a bound CRS whose hub was not spelled out — which is
     * every WKT1 {@code TOWGS84}, since that clause names no target.
     */
    private CrsDefinition wgs84() {
        CrsDefinition def = new CrsDefinition();
        def.setKind(CrsDefinition.Kind.GEOGRAPHIC);
        def.setName("WGS 84");
        DatumDefinition datum = new DatumDefinition();
        datum.setName("World Geodetic System 1984");
        datum.setId(new Identifier("EPSG", "6326"));
        EllipsoidDefinition e = new EllipsoidDefinition();
        e.setName("WGS 84");
        e.setSemiMajorAxis(6378137);
        e.setInverseFlattening(298.257223563);
        e.setId(new Identifier("EPSG", "7030"));
        datum.setEllipsoid(e);
        datum.setPrimeMeridian(PrimeMeridianDefinition.greenwich());
        def.setDatum(datum);
        CoordinateSystemDefinition cs =
                new CoordinateSystemDefinition(CoordinateSystemDefinition.ELLIPSOIDAL);
        cs.setUnit(UnitDefinition.DEGREE);
        cs.addAxis(new AxisDefinition("latitude", "Lat", AxisDefinition.NORTH,
                UnitDefinition.DEGREE));
        cs.addAxis(new AxisDefinition("longitude", "Lon", AxisDefinition.EAST,
                UnitDefinition.DEGREE));
        def.setCoordinateSystem(cs);
        def.addId(new Identifier("EPSG", "4326"));
        return def;
    }

    private WktNode abridgedTransformation(double[] t) {
        List<WktNode> children = new ArrayList<WktNode>();
        children.add(WktNode.quoted("Transformation to WGS84"));
        List<WktNode> method = new ArrayList<WktNode>();
        boolean sevenParam = t.length == 7;
        method.add(WktNode.quoted(sevenParam
                ? "Position Vector transformation (geog2D domain)"
                : "Geocentric translations (geog2D domain)"));
        addId(method, new Identifier("EPSG", sevenParam ? "9606" : "9603"));
        children.add(WktNode.of("METHOD", method));
        String[] names = {"X-axis translation", "Y-axis translation", "Z-axis translation",
                "X-axis rotation", "Y-axis rotation", "Z-axis rotation", "Scale difference"};
        String[] codes = {"8605", "8606", "8607", "8608", "8609", "8610", "8611"};
        for (int i = 0; i < t.length; i++) {
            List<WktNode> p = new ArrayList<WktNode>();
            p.add(WktNode.quoted(names[i]));
            if (i < 3) {
                p.add(WktNode.number(t[i]));
                p.add(unit("LENGTHUNIT", UnitDefinition.METRE));
            } else if (i < 6) {
                p.add(WktNode.number(t[i]));
                p.add(unit("ANGLEUNIT", UnitDefinition.ARC_SECOND));
            } else {
                // The abridged form carries the scale as a multiplier, not in parts per million.
                p.add(WktNode.number(1.0 + t[i] * 1e-6));
                p.add(unit("SCALEUNIT", UnitDefinition.UNITY));
            }
            addId(p, new Identifier("EPSG", codes[i]));
            children.add(WktNode.of("PARAMETER", p));
        }
        return WktNode.of("ABRIDGEDTRANSFORMATION", children);
    }

    private WktNode datum(DatumDefinition datum) {
        List<WktNode> children = new ArrayList<WktNode>();
        children.add(WktNode.quoted(datum.getName() == null ? "unknown" : datum.getName()));
        EllipsoidDefinition e = datum.getEllipsoid();
        if (e != null) {
            children.add(ellipsoid(e));
        }
        if (datum.getAnchor() != null) {
            children.add(WktNode.of("ANCHOR", one(WktNode.quoted(datum.getAnchor()))));
        }
        addId(children, datum.getId());
        return WktNode.of("DATUM", children);
    }

    private WktNode ellipsoid(EllipsoidDefinition e) {
        List<WktNode> children = new ArrayList<WktNode>();
        children.add(WktNode.quoted(e.getName() == null ? "unknown" : e.getName()));
        children.add(WktNode.number(e.getSemiMajorAxis()));
        children.add(WktNode.number(WktNames.inverseFlatteningOf(e)));
        UnitDefinition u = e.getUnit();
        if (u != null && u.getConversionFactor() != 1.0) {
            children.add(unit("LENGTHUNIT", u));
        } else {
            children.add(unit("LENGTHUNIT", UnitDefinition.METRE));
        }
        addId(children, e.getId());
        return WktNode.of("ELLIPSOID", children);
    }

    private WktNode primeMeridian(PrimeMeridianDefinition pm) {
        List<WktNode> children = new ArrayList<WktNode>();
        children.add(WktNode.quoted(pm.getName() == null ? "Greenwich" : pm.getName()));
        children.add(WktNode.number(pm.getLongitude()));
        children.add(unit("ANGLEUNIT", pm.getUnit() == null ? UnitDefinition.DEGREE
                : pm.getUnit()));
        addId(children, pm.getId());
        return WktNode.of("PRIMEM", children);
    }

    private WktNode conversion(ConversionDefinition conv) {
        if (conv == null) {
            throw new WktParseException("projected CRS has no conversion");
        }
        List<WktNode> children = new ArrayList<WktNode>();
        children.add(WktNode.quoted(conv.getName() == null ? "unnamed" : conv.getName()));
        List<WktNode> method = new ArrayList<WktNode>();
        method.add(WktNode.quoted(WktMethods.wkt2MethodName(conv)));
        addId(method, conv.getMethodId() != null ? conv.getMethodId()
                : WktMethods.methodId(conv.getMethodName()));
        children.add(WktNode.of("METHOD", method));
        List<ParameterDefinition> params = conv.getParameters();
        for (int i = 0; i < params.size(); i++) {
            ParameterDefinition p = params.get(i);
            List<WktNode> pc = new ArrayList<WktNode>();
            pc.add(WktNode.quoted(WktMethods.wkt2ParameterName(conv, p.getName())));
            pc.add(WktNode.number(p.getValue()));
            UnitDefinition u = p.getUnit();
            if (u != null) {
                pc.add(unitWithoutId(u.getType() == UnitDefinition.ANGULAR ? "ANGLEUNIT"
                        : u.getType() == UnitDefinition.SCALE ? "SCALEUNIT" : "LENGTHUNIT", u));
            }
            addId(pc, p.getId() != null ? p.getId()
                    : WktMethods.parameterId(conv, p.getName()));
            children.add(WktNode.of("PARAMETER", pc));
        }
        addId(children, conv.getId());
        return WktNode.of("CONVERSION", children);
    }

    private void addCoordinateSystem(List<WktNode> children, CoordinateSystemDefinition cs) {
        if (cs == null) {
            return;
        }
        List<WktNode> csChildren = new ArrayList<WktNode>();
        csChildren.add(WktNode.literal(cs.getType() == null
                ? CoordinateSystemDefinition.CARTESIAN : cs.getType()));
        csChildren.add(WktNode.number(cs.getDimension()));
        children.add(WktNode.of("CS", csChildren));

        // The unit is written on every axis, never once for the coordinate system, because that is
        // what PROJ's non-simplified WKT2 output does and byte equality with it is the point of
        // the round-trip tests.
        List<AxisDefinition> axes = cs.getAxes();
        for (int i = 0; i < axes.size(); i++) {
            AxisDefinition a = axes.get(i);
            List<WktNode> ac = new ArrayList<WktNode>();
            ac.add(WktNode.quoted(axisLabel(a)));
            ac.add(WktNode.literal(a.getDirection() == null ? AxisDefinition.UNSPECIFIED
                    : a.getDirection()));
            ac.add(WktNode.of("ORDER", one(WktNode.number(i + 1))));
            UnitDefinition u = cs.unitOf(i);
            if (u != null) {
                ac.add(unit(unitKeyword(u), u));
            }
            children.add(WktNode.of("AXIS", ac));
        }
    }

    private String unitKeyword(UnitDefinition u) {
        switch (u.getType()) {
            case UnitDefinition.ANGULAR:
                return "ANGLEUNIT";
            case UnitDefinition.SCALE:
                return "SCALEUNIT";
            case UnitDefinition.TIME:
                return "TIMEUNIT";
            default:
                return "LENGTHUNIT";
        }
    }

    private String axisLabel(AxisDefinition a) {
        String name = a.getName();
        String abbr = a.getAbbreviation();
        if (name == null || name.length() == 0) {
            return abbr == null ? "unknown" : "(" + abbr + ")";
        }
        if (abbr == null || abbr.length() == 0) {
            return name;
        }
        return name + " (" + abbr + ")";
    }

    private WktNode unit(String keyword, UnitDefinition u) {
        List<WktNode> children = new ArrayList<WktNode>();
        children.add(WktNode.quoted(u.getName() == null ? "unknown" : u.getName()));
        children.add(WktNode.number(u.getConversionFactor()));
        addId(children, u.getId());
        return WktNode.of(keyword, children);
    }

    /**
     * A unit with no {@code ID}, for the places PROJ writes none: the {@code LENGTHUNIT} of an
     * {@code ELLIPSOID} and the unit of a {@code PARAMETER}.
     */
    private WktNode unitWithoutId(String keyword, UnitDefinition u) {
        List<WktNode> children = new ArrayList<WktNode>();
        children.add(WktNode.quoted(u.getName() == null ? "unknown" : u.getName()));
        children.add(WktNode.number(u.getConversionFactor()));
        return WktNode.of(keyword, children);
    }

    private void addMetadata(List<WktNode> children, CrsDefinition def, boolean asBase) {
        if (!asBase) {
            List<WktNode> usage = new ArrayList<WktNode>();
            if (def.getScope() != null) {
                usage.add(WktNode.of("SCOPE", one(WktNode.quoted(def.getScope()))));
            }
            if (def.getAreaDescription() != null) {
                usage.add(WktNode.of("AREA", one(WktNode.quoted(def.getAreaDescription()))));
            }
            if (def.getBoundingBox() != null && def.getBoundingBox().length == 4) {
                List<WktNode> bbox = new ArrayList<WktNode>();
                for (int i = 0; i < 4; i++) {
                    bbox.add(WktNode.number(def.getBoundingBox()[i]));
                }
                usage.add(WktNode.of("BBOX", bbox));
            }
            if (!usage.isEmpty()) {
                if (dialect == WktDialect.WKT2_2019) {
                    children.add(WktNode.of("USAGE", usage));
                } else {
                    children.addAll(usage);
                }
            }
        }
        for (int i = 0; i < def.getIds().size(); i++) {
            addId(children, def.getIds().get(i));
        }
        if (!asBase && def.getRemark() != null) {
            children.add(WktNode.of("REMARK", one(WktNode.quoted(def.getRemark()))));
        }
    }

    private void addId(List<WktNode> children, Identifier id) {
        if (id == null) {
            return;
        }
        List<WktNode> idChildren = new ArrayList<WktNode>();
        idChildren.add(WktNode.quoted(id.getAuthority()));
        idChildren.add(isInteger(id.getCode()) ? WktNode.literal(id.getCode())
                : WktNode.quoted(id.getCode()));
        children.add(WktNode.of("ID", idChildren));
    }

    private static boolean isInteger(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String nameOf(CrsDefinition def) {
        return def.getName() == null ? "unknown" : def.getName();
    }

    private static List<WktNode> one(WktNode node) {
        List<WktNode> list = new ArrayList<WktNode>(1);
        list.add(node);
        return list;
    }
}

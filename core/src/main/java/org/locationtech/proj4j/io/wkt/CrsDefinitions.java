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
import java.util.Locale;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.datum.Datum;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.datum.PrimeMeridian;
import org.locationtech.proj4j.proj.Projection;
import org.locationtech.proj4j.units.Unit;
import org.locationtech.proj4j.units.Units;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * The bridge between a {@link CrsDefinition} — what a document said — and proj4j's
 * {@link CoordinateReferenceSystem} — what this library can transform with.
 * <p>
 * Both directions live here. The forward direction builds a PROJ parameter list and hands it to
 * {@link CRSFactory}, which is deliberate: proj4j's CRS model <em>is</em> the PROJ parameter model,
 * so going through it means WKT support inherits every projection, datum and unit the existing
 * engine already handles, and one shared code path stays tested. It also gives a caller the thing
 * they most often actually want, {@link #toProjParameterString}: the PROJ string equivalent to
 * their WKT, which is what a hand-rolled WKT-to-PROJ converter was there to produce.
 * <p>
 * What is lost in the forward direction is stated rather than hidden. proj4j has no compound CRS
 * and no vertical CRS, so the horizontal component of a compound CRS is what you get and the
 * height is not transformed; a vertical-only CRS is refused outright rather than silently
 * becoming a horizontal one.
 */
public final class CrsDefinitions {

    private CrsDefinitions() {
    }

    /**
     * Builds a proj4j CRS from a definition, applying {@code policy} to the declared axis order.
     *
     * @throws WktParseException if the definition describes something proj4j cannot represent
     */
    public static CoordinateReferenceSystem toCrs(CrsDefinition def, AxisOrderPolicy policy) {
        String[] params = toProjParameters(def, policy);
        String name = def.getName();
        Identifier id = def.getId();
        if (id != null) {
            name = id.toString();
        }
        return new CRSFactory().createFromParameters(name, params);
    }

    /**
     * The PROJ parameter list equivalent to a definition, each element in {@code +key=value} form.
     *
     * @throws WktParseException if the definition describes something proj4j cannot represent
     */
    public static String[] toProjParameters(CrsDefinition def, AxisOrderPolicy policy) {
        if (def == null) {
            throw new WktParseException("CRS definition is null");
        }
        if (policy == null) {
            policy = AxisOrderPolicy.LEGACY;
        }
        CrsDefinition horizontal = def.horizontalComponent();
        if (horizontal == null) {
            throw new WktParseException("a " + def.getKind() + " CRS has no horizontal component; "
                    + "proj4j cannot represent \"" + def.getName() + "\"");
        }

        List<String> params = new ArrayList<String>();
        boolean sphere = false;

        // 1. the projection itself
        if (horizontal.getKind() == CrsDefinition.Kind.PROJECTED) {
            ConversionDefinition conv = horizontal.getConversion();
            if (conv == null) {
                throw new WktParseException("projected CRS \"" + horizontal.getName()
                        + "\" has no conversion");
            }
            int flags = WktMethods.appendProjection(conv, horizontal, params);
            sphere = (flags & WktMethods.FLAG_SPHERE_FROM_A) != 0;
        } else if (horizontal.getKind() == CrsDefinition.Kind.GEOCENTRIC) {
            params.add("+proj=geocent");
        } else if (horizontal.getKind() == CrsDefinition.Kind.GEOGRAPHIC) {
            params.add("+proj=longlat");
        } else {
            throw new WktParseException("proj4j cannot represent a "
                    + horizontal.getKind() + " CRS (\"" + horizontal.getName() + "\")");
        }

        // 2. the datum, or failing that the ellipsoid, plus any Helmert parameters
        appendDatum(def, horizontal, params, sphere);

        // 3. the prime meridian
        DatumDefinition datum = horizontal.resolveDatum();
        if (datum != null && datum.getPrimeMeridian() != null
                && !datum.getPrimeMeridian().isGreenwich()) {
            params.add("+pm=" + primeMeridianValue(datum.getPrimeMeridian()));
        }

        // 4. the unit of the coordinate system
        appendUnits(horizontal, params);

        // 5. the axis order, if the policy says so
        appendAxisOrder(horizontal, policy, params);

        params.add("+no_defs");
        return params.toArray(new String[params.size()]);
    }

    /**
     * The PROJ string equivalent to a definition, as a single space-separated string. Handy for
     * logging, for handing to {@link CRSFactory#createFromParameters(String, String)}, and for
     * comparing against {@code projinfo} output.
     */
    public static String toProjParameterString(CrsDefinition def, AxisOrderPolicy policy) {
        String[] params = toProjParameters(def, policy);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(params[i]);
        }
        return sb.toString();
    }

    private static void appendDatum(CrsDefinition def, CrsDefinition horizontal,
                                    List<String> params, boolean sphere) {
        DatumDefinition datum = horizontal.resolveDatum();
        if (datum == null) {
            throw new WktParseException("CRS \"" + horizontal.getName() + "\" has no datum");
        }
        EllipsoidDefinition ellipsoid = datum.getEllipsoid();
        double[] toWgs84 = def.resolveToWgs84();

        if (sphere) {
            // A method which projects onto a sphere of the ellipsoid's semi-major axis: EPSG:3857
            // and its ESRI spelling. Emitted as an explicit equal-axis ellipsoid, which is what
            // GDAL's own PROJ.4 export of EPSG:3857 does, and never as +datum= — that would
            // restore the flattening the method exists to discard.
            if (ellipsoid == null) {
                throw new WktParseException("a spherical-development method needs an ellipsoid to "
                        + "take its radius from");
            }
            String a = WktFormat.number(ellipsoid.getSemiMajorAxisMetres());
            params.add("+a=" + a);
            params.add("+b=" + a);
            return;
        }

        String datumCode = WktNames.projDatumCode(datum.getName());
        if (datumCode != null && ellipsoidMatchesDatum(datumCode, ellipsoid)) {
            // A built-in datum carries its own shift to WGS 84, so +towgs84 is not emitted with
            // it: proj4j's DatumParameters lets whichever of the two is set last win, and a
            // silently-preferred parameter is how a datum shift goes missing.
            params.add("+datum=" + datumCode);
            return;
        }

        if (ellipsoid == null) {
            throw new WktParseException("datum \"" + datum.getName()
                    + "\" has no ellipsoid and is not a datum proj4j knows by name");
        }
        String ellipsoidCode = WktNames.projEllipsoidCode(ellipsoid);
        if (ellipsoidCode != null) {
            params.add("+ellps=" + ellipsoidCode);
        } else {
            // Deliberately +a= and +b=, never +rf= or +f=: the semi-minor axis is exact, needs no
            // reciprocal, and is unaffected by how a parser interprets a flattening.
            double a = ellipsoid.getSemiMajorAxisMetres();
            double rf = WktNames.inverseFlatteningOf(ellipsoid);
            double b = rf == 0.0 ? a : a * (1.0 - 1.0 / rf);
            params.add("+a=" + WktFormat.number(a));
            params.add("+b=" + WktFormat.number(b));
        }
        if (toWgs84 != null) {
            StringBuilder sb = new StringBuilder("+towgs84=");
            for (int i = 0; i < toWgs84.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(WktFormat.number(toWgs84[i]));
            }
            params.add(sb.toString());
        }
    }

    /**
     * Whether the ellipsoid a document declared is the one proj4j's built-in datum of that name
     * uses. A document naming "WGS_1984" but describing a Bessel ellipsoid is not WGS 84, and
     * emitting {@code +datum=WGS84} for it would replace its ellipsoid silently.
     */
    private static boolean ellipsoidMatchesDatum(String datumCode, EllipsoidDefinition ellipsoid) {
        if (ellipsoid == null) {
            return true;
        }
        Datum datum = findDatum(datumCode);
        if (datum == null) {
            return false;
        }
        Ellipsoid e = datum.getEllipsoid();
        if (Math.abs(e.equatorRadius - ellipsoid.getSemiMajorAxisMetres()) > 1e-3) {
            return false;
        }
        double rf = WktNames.inverseFlatteningOf(ellipsoid);
        double erf = e.eccentricity2 == 0.0 ? 0.0 : 1.0 / (1.0 - Math.sqrt(1.0 - e.eccentricity2));
        return Math.abs(erf - rf) <= 1e-6;
    }

    private static Datum findDatum(String code) {
        Datum[] datums = org.locationtech.proj4j.Registry.datums;
        for (int i = 0; i < datums.length; i++) {
            if (datums[i].getCode().equals(code)) {
                return datums[i];
            }
        }
        return null;
    }

    /**
     * A {@code +pm=} value: a name proj4j knows, or else the offset in degrees. Never a name
     * proj4j does not know, because {@link PrimeMeridian#forName} silently falls back to Greenwich
     * for those, which would drop the offset entirely.
     */
    private static String primeMeridianValue(PrimeMeridianDefinition pm) {
        String name = pm.getName();
        if (name != null) {
            // Locale.ROOT: a PrimeMeridian lookup key. Under tr_TR "Lisbon" would lowercase
            // with a dotless i, miss forName(), and silently drop the meridian offset.
            String lower = name.toLowerCase(Locale.ROOT);
            PrimeMeridian known = PrimeMeridian.forName(lower);
            if (known != null && lower.equals(known.getName())) {
                return lower;
            }
        }
        return WktFormat.number(pm.getLongitudeDegrees());
    }

    private static void appendUnits(CrsDefinition horizontal, List<String> params) {
        if (horizontal.getKind() != CrsDefinition.Kind.PROJECTED
                && horizontal.getKind() != CrsDefinition.Kind.GEOCENTRIC) {
            // A geographic CRS is always degrees in proj4j; LongLatProjection.initialize() sets
            // that unconditionally, so saying so again would be noise.
            return;
        }
        CoordinateSystemDefinition cs = horizontal.getCoordinateSystem();
        UnitDefinition unit = cs == null ? null : cs.unitOf(0);
        if (unit == null || unit.getType() != UnitDefinition.LINEAR) {
            return;
        }
        String code = WktNames.projUnitsCode(unit);
        if (code != null) {
            params.add("+units=" + code);
        } else {
            params.add("+to_meter=" + WktFormat.number(unit.getConversionFactor()));
        }
    }

    private static void appendAxisOrder(CrsDefinition horizontal, AxisOrderPolicy policy,
                                       List<String> params) {
        if (policy != AxisOrderPolicy.AUTHORITY) {
            // LEGACY ignores the declared order; VISUALISATION forces east/north/up, which is
            // proj4j's default and therefore also nothing to emit.
            return;
        }
        CoordinateSystemDefinition cs = horizontal.getCoordinateSystem();
        if (cs == null || cs.getAxes().size() < 2 || cs.isXBeforeY()) {
            return;
        }
        List<AxisDefinition> axes = cs.getAxes();
        StringBuilder sb = new StringBuilder(3);
        for (int i = 0; i < axes.size() && i < 3; i++) {
            char c = axes.get(i).toProjAxisChar();
            if (c == 0) {
                throw new WktParseException("axis \"" + axes.get(i).getName() + "\" has direction "
                        + axes.get(i).getDirection() + ", which cannot be expressed as +axis=; "
                        + "AxisOrderPolicy.AUTHORITY cannot honour this CRS");
            }
            sb.append(c);
        }
        while (sb.length() < 3) {
            sb.append('u');
        }
        params.add("+axis=" + sb);
    }

    // ------------------------------------------------------------------- reverse

    /**
     * Describes an existing proj4j CRS as a definition, so that it can be written as WKT or
     * PROJJSON.
     * <p>
     * What a proj4j CRS does not carry, this cannot invent: there are no authority identifiers, no
     * area of use and no axis names, so the result is the minimum faithful description of the
     * projection, datum, ellipsoid and units. Round-tripping a definition that came from
     * {@link WktReader} keeps everything, because the definition itself is retained; this method
     * is for CRSs built from a PROJ string or from {@code CRSFactory.createFromName}.
     *
     * @throws WktParseException if the CRS uses a projection with no known WKT method
     */
    public static CrsDefinition fromCrs(CoordinateReferenceSystem crs) {
        if (crs == null) {
            throw new WktParseException("CRS is null");
        }
        Projection proj = crs.getProjection();
        if (proj == null) {
            throw new WktParseException("CRS \"" + crs.getName() + "\" has no projection");
        }
        Datum datum = crs.getDatum();

        DatumDefinition datumDef = new DatumDefinition();
        datumDef.setName(WktNames.wktDatumName(datum));
        datumDef.setEllipsoid(WktNames.definitionOf(proj.getEllipsoid()));
        PrimeMeridianDefinition pm = new PrimeMeridianDefinition();
        PrimeMeridian projPm = proj.getPrimeMeridian();
        if (projPm == null || "greenwich".equals(projPm.getName())) {
            pm = PrimeMeridianDefinition.greenwich();
        } else {
            pm.setName(capitalise(projPm.getName()));
            pm.setUnit(UnitDefinition.DEGREE);
            pm.setLongitude(ProjectionMath.toDeg(offsetFromGreenwichRadians(projPm)));
        }
        datumDef.setPrimeMeridian(pm);

        boolean geographic = Boolean.TRUE.equals(proj.isGeographic());

        CrsDefinition base = new CrsDefinition();
        base.setKind(CrsDefinition.Kind.GEOGRAPHIC);
        base.setName(geographicNameFor(datumDef.getName()));
        base.setDatum(datumDef);
        CoordinateSystemDefinition baseCs =
                new CoordinateSystemDefinition(CoordinateSystemDefinition.ELLIPSOIDAL);
        baseCs.setUnit(UnitDefinition.DEGREE);
        baseCs.addAxis(new AxisDefinition("geodetic longitude", "Lon", AxisDefinition.EAST,
                UnitDefinition.DEGREE));
        baseCs.addAxis(new AxisDefinition("geodetic latitude", "Lat", AxisDefinition.NORTH,
                UnitDefinition.DEGREE));
        base.setCoordinateSystem(baseCs);

        if (datum != null && datum.getTransformType() == Datum.TYPE_3PARAM
                || datum != null && datum.getTransformType() == Datum.TYPE_7PARAM) {
            base.setToWgs84(datum.getTransformToWGS84());
        }

        if (geographic) {
            return base;
        }

        CrsDefinition def = new CrsDefinition();
        def.setKind(CrsDefinition.Kind.PROJECTED);
        def.setName(crs.getName() != null ? crs.getName() : proj.getName());
        def.setBaseCrs(base);
        def.setConversion(WktMethods.conversionOf(proj));

        UnitDefinition linear = linearUnitOf(proj);
        CoordinateSystemDefinition cs =
                new CoordinateSystemDefinition(CoordinateSystemDefinition.CARTESIAN);
        cs.setUnit(linear);
        cs.addAxis(new AxisDefinition("easting", "E", AxisDefinition.EAST, linear));
        cs.addAxis(new AxisDefinition("northing", "N", AxisDefinition.NORTH, linear));
        def.setCoordinateSystem(cs);
        return def;
    }

    /**
     * The offset of a prime meridian from Greenwich, in radians.
     * <p>
     * {@link PrimeMeridian} has no accessor for it, so it is read the only way the class allows:
     * by shifting a zero coordinate. The call mutates the scratch coordinate and nothing else.
     */
    private static double offsetFromGreenwichRadians(PrimeMeridian pm) {
        org.locationtech.proj4j.ProjCoordinate probe = new org.locationtech.proj4j.ProjCoordinate(
                0.0, 0.0);
        pm.toGreenwich(probe);
        return probe.x;
    }

    private static UnitDefinition linearUnitOf(Projection proj) {
        Unit unit = proj.getUnits();
        if (unit == null || unit == Units.DEGREES) {
            return UnitDefinition.METRE;
        }
        UnitDefinition u = WktNames.unitFromProjCode(unit.abbreviation);
        return u != null ? u : new UnitDefinition(unit.name, unit.value, UnitDefinition.LINEAR);
    }

    private static String geographicNameFor(String datumName) {
        if (datumName == null) {
            return "unknown";
        }
        if (datumName.startsWith("World Geodetic System 1984")) {
            return "WGS 84";
        }
        if (datumName.startsWith("North American Datum 1983")) {
            return "NAD83";
        }
        if (datumName.startsWith("North American Datum 1927")) {
            return "NAD27";
        }
        return datumName;
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}

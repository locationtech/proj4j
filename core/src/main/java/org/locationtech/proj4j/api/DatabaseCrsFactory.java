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
package org.locationtech.proj4j.api;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.spi.DbAxis;
import org.locationtech.proj4j.spi.DbCoordinateSystem;
import org.locationtech.proj4j.spi.DbCrs;
import org.locationtech.proj4j.spi.DbEllipsoid;
import org.locationtech.proj4j.spi.DbExtent;
import org.locationtech.proj4j.spi.DbObjectRef;
import org.locationtech.proj4j.spi.DbObjectType;
import org.locationtech.proj4j.spi.DbPrimeMeridian;
import org.locationtech.proj4j.spi.DbUnit;
import org.locationtech.proj4j.spi.ProjDatabase;

/**
 * Builds a {@link Crs} from an authority database row, for the codes the legacy PROJ.4 dictionary
 * cannot produce.
 *
 * <h2>Deliberately narrow</h2>
 *
 * <p>Only <b>geodetic</b> CRSs &mdash; geographic 2D, geographic 3D reduced to its horizontal half,
 * and geocentric. A projected CRS is refused, because building one means turning a
 * {@code conversion}'s parameters into a {@code Projection}: 4,312 conversion rows across dozens of
 * EPSG method codes, each with its own parameter slots and its own units, and every mis-slotted
 * parameter is a plausible coordinate in the wrong place. The legacy dictionary already carries
 * 5,708 projected CRSs with parameters that have been in production for fifteen years, so it stays
 * authoritative for them and this class does not compete with it.
 *
 * <p>What that leaves is exactly the useful half of the vintage gap. The dictionary is EPSG v9.2-era
 * and the shipped database is v12.029, so geodetic codes added since &mdash; the WGS 84 ensemble
 * realisations {@code EPSG:9053} to {@code EPSG:10606} among them &mdash; become resolvable, and with
 * them the operations that relate them.
 *
 * <h2>Units are converted, and that is not optional</h2>
 *
 * <p>{@link org.locationtech.proj4j.spi.ProjDatabase} returns values in the authority's own units,
 * unconverted, each with its unit reference. Some ellipsoids are defined in Clarke's foot and some
 * prime meridians in grads. A semi-major axis passed through as though it were metres is wrong by a
 * factor of about 3.28, and a prime meridian in grads read as degrees is out by 10 per cent &mdash;
 * both entirely plausible-looking numbers. So every value here is multiplied by its unit's
 * {@link DbUnit#conversionFactor()}, and a unit with <em>no</em> factor
 * ({@link DbUnit#hasConversionFactor()} false) makes this class refuse rather than assume 1.0.
 */
final class DatabaseCrsFactory {

    /** Radians to degrees. The database's angular conversion factors are to radians. */
    private static final double RAD_TO_DEG = 180.0 / Math.PI;

    private DatabaseCrsFactory() {
    }

    /**
     * Builds a CRS from the context's database, or returns null.
     *
     * @param definition the caller's original text, kept verbatim on the result
     * @param authCode   the {@code authority:code} name
     * @param ctx        the context, whose database is used and whose policies apply
     * @return the CRS, or null if there is no database, no such code, or the code names a CRS type
     *         this class deliberately does not build
     */
    static Crs create(String definition, String authCode, ProjContext ctx) {
        if (!ctx.hasDatabase()) {
            return null;
        }
        int colon = authCode.lastIndexOf(':');
        if (colon <= 0 || colon == authCode.length() - 1) {
            return null;
        }
        ProjDatabase db = ctx.database();
        String auth = authCode.substring(0, colon).trim();
        String code = authCode.substring(colon + 1).trim();
        DbCrs crs = db.crs(auth, code);
        if (crs == null) {
            return null;
        }
        String projString = projStringFor(db, crs);
        if (projString == null) {
            return null;
        }
        CoordinateReferenceSystem legacy =
                new CRSFactory().createFromParameters(crs.name(), projString);
        if (legacy == null) {
            return null;
        }

        String axis = authorityAxisOrder(db, crs);
        boolean axisAuthoritative = axis != null;
        if (axis != null && ctx.axisOrderPolicy() == org.locationtech.proj4j.io.wkt.AxisOrderPolicy
                .AUTHORITY && !"enu".equals(axis)) {
            legacy = new CRSFactory().createFromParameters(crs.name(),
                    projString + " +axis=" + axis);
        } else if (ctx.axisOrderPolicy() == org.locationtech.proj4j.io.wkt.AxisOrderPolicy
                .VISUALISATION) {
            axis = "enu";
        } else if (ctx.axisOrderPolicy() == org.locationtech.proj4j.io.wkt.AxisOrderPolicy.LEGACY) {
            // 1.4.3 behaviour: the authority order is read and reported, and then deliberately not
            // applied, so an existing caller's coordinates do not transpose under their feet.
            axis = null;
        }

        AreaOfUse area = AreaOfUse.fromDbExtent(
                CrsOperationCandidate.smallestExtent(db.extentsFor(crs.ref())));
        return Crs.fromDatabase(definition, ctx, legacy, crs, area, axisAuthoritative,
                axisNote(ctx, db, crs, axis, axisAuthoritative));
    }

    /**
     * The PROJ string for a geodetic CRS, or null for a CRS type this class does not build.
     *
     * <p>{@code +no_defs} is appended for the same reason the shipped dictionary carries it: without
     * it PROJ's defaults leak in, and a default is exactly the kind of value that reads as authority
     * data without being any.
     */
    private static String projStringFor(ProjDatabase db, DbCrs crs) {
        String kind;
        switch (crs.type()) {
            case GEOGRAPHIC_2D:
            case GEOGRAPHIC_3D:
            case GEODETIC_OTHER:
                kind = "+proj=longlat";
                break;
            case GEOCENTRIC:
                kind = "+proj=geocent";
                break;
            default:
                // PROJECTED, VERTICAL, COMPOUND, ENGINEERING. See the class javadoc.
                return null;
        }
        DbObjectRef datumRef = crs.datum();
        if (datumRef == null) {
            return null;
        }
        org.locationtech.proj4j.spi.DbDatum datum =
                db.datum(DbObjectType.GEODETIC_DATUM, datumRef.authName(), datumRef.code());
        if (datum == null || datum.ellipsoid() == null) {
            return null;
        }
        DbEllipsoid ellipsoid = db.ellipsoid(datum.ellipsoid().authName(),
                datum.ellipsoid().code());
        if (ellipsoid == null) {
            return null;
        }
        String shape = ellipsoidParams(db, ellipsoid);
        if (shape == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(kind).append(' ').append(shape);

        if (datum.primeMeridian() != null) {
            DbPrimeMeridian pm = db.primeMeridian(datum.primeMeridian().authName(),
                    datum.primeMeridian().code());
            if (pm != null) {
                Double degrees = toDegrees(db, pm.longitude(), pm.unit());
                if (degrees == null) {
                    return null;
                }
                if (degrees.doubleValue() != 0.0) {
                    sb.append(" +pm=").append(degrees);
                }
            }
        }
        return sb.append(" +no_defs").toString();
    }

    /**
     * {@code +a=} with whichever second shape parameter the authority actually published.
     *
     * <p>The authority's own parameterisation is kept rather than derived: an ellipsoid defined by an
     * inverse flattening is written {@code +a= +rf=} and one defined by a semi-minor axis is written
     * {@code +a= +b=}. Converting between them costs bits, and {@code +rf} has already been the cause
     * of one transposed-setter defect in this library.
     */
    private static String ellipsoidParams(ProjDatabase db, DbEllipsoid ellipsoid) {
        Double a = toMetres(db, ellipsoid.semiMajorAxis(), ellipsoid.unit());
        if (a == null) {
            return null;
        }
        if (ellipsoid.isSphere()) {
            return "+R=" + a;
        }
        if (!Double.isNaN(ellipsoid.inverseFlattening())) {
            return "+a=" + a + " +rf=" + ellipsoid.inverseFlattening();
        }
        Double b = toMetres(db, ellipsoid.semiMinorAxis(), ellipsoid.unit());
        if (b == null) {
            return null;
        }
        return "+a=" + a + " +b=" + b;
    }

    /**
     * A length in the authority's unit, converted to metres, or null when the unit publishes no
     * conversion factor.
     *
     * <p>Null rather than "assume metres". A defaulted factor of one is indistinguishable from a real
     * one and multiplies silently.
     */
    private static Double toMetres(ProjDatabase db, double value, DbObjectRef unitRef) {
        if (Double.isNaN(value)) {
            return null;
        }
        if (unitRef == null) {
            return null;
        }
        DbUnit unit = db.unit(unitRef.authName(), unitRef.code());
        if (unit == null || !unit.hasConversionFactor() || unit.type() != DbUnit.Type.LENGTH) {
            return null;
        }
        return Double.valueOf(value * unit.conversionFactor());
    }

    /** An angle in the authority's unit, converted to degrees, or null. See {@link #toMetres}. */
    private static Double toDegrees(ProjDatabase db, double value, DbObjectRef unitRef) {
        if (Double.isNaN(value)) {
            return null;
        }
        if (unitRef == null) {
            return null;
        }
        DbUnit unit = db.unit(unitRef.authName(), unitRef.code());
        if (unit == null || !unit.hasConversionFactor() || unit.type() != DbUnit.Type.ANGLE) {
            return null;
        }
        return Double.valueOf(value * unit.conversionFactor() * RAD_TO_DEG);
    }

    /**
     * The authority's axis order in PROJ's {@code +axis=} three-letter encoding, or null when it
     * cannot be expressed in it.
     *
     * <p><b>This is where {@code AxisOrderPolicy.AUTHORITY} stops being an inference.</b>
     * {@code EPSG:4326}'s coordinate system is {@code EPSG:6422}, whose axis 1 is
     * <em>Geodetic latitude, north</em> and axis 2 <em>Geodetic longitude, east</em>, which is why PROJ
     * 6+ is latitude-first for it. Read here, rather than applied as a rule and then disclosed as
     * inferred.
     *
     * <p>Returns null &mdash; not a guess &mdash; for a coordinate system whose axes are not cardinal.
     * The shipped database has plenty: {@code North along 90 degrees East}, {@code starboard},
     * {@code rowPositive}, {@code See associated operation}. None of those has a {@code +axis=}
     * spelling, and inventing one would be a silent transposition.
     */
    static String authorityAxisOrder(ProjDatabase db, DbCrs crs) {
        DbObjectRef csRef = crs.coordinateSystem();
        if (csRef == null) {
            return null;
        }
        DbCoordinateSystem cs = db.coordinateSystem(csRef.authName(), csRef.code());
        if (cs == null || cs.axes().isEmpty()) {
            return null;
        }
        List<DbAxis> axes = cs.axes();
        StringBuilder sb = new StringBuilder(3);
        for (int i = 0; i < axes.size() && i < 3; i++) {
            char c = letterFor(axes.get(i).orientation());
            if (c == 0) {
                return null;
            }
            sb.append(c);
        }
        // A two-dimensional CRS still needs three letters for +axis=; PROJ's own normalisation appends
        // the up axis, and this facade's Crs is two-dimensional by construction.
        while (sb.length() < 3) {
            sb.append('u');
        }
        return sb.toString();
    }

    private static char letterFor(String orientation) {
        if ("east".equals(orientation)) {
            return 'e';
        }
        if ("west".equals(orientation)) {
            return 'w';
        }
        if ("north".equals(orientation)) {
            return 'n';
        }
        if ("south".equals(orientation)) {
            return 's';
        }
        if ("up".equals(orientation)) {
            return 'u';
        }
        if ("down".equals(orientation)) {
            return 'd';
        }
        return 0;
    }

    private static String axisNote(ProjContext ctx, ProjDatabase db, DbCrs crs, String applied,
                                  boolean authoritative) {
        String declared = authorityAxisOrder(db, crs);
        StringBuilder sb = new StringBuilder();
        sb.append("AxisOrderPolicy.").append(ctx.axisOrderPolicy()).append(": the authority ");
        if (declared == null) {
            sb.append("coordinate system ")
                    .append(crs.coordinateSystem() == null ? "?"
                            : crs.coordinateSystem().authorityCode())
                    .append(" has axes that cannot be expressed as a PROJ +axis= string, so none was "
                            + "applied and east-north-up is in force. This is reported rather than "
                            + "guessed.");
            return sb.toString();
        }
        sb.append("coordinate system ").append(crs.coordinateSystem().authorityCode())
                .append(" declares axis order ").append(declared).append(", READ from the database ")
                .append("rather than inferred. ");
        if (applied == null) {
            sb.append("It was deliberately NOT applied: LEGACY keeps proj4j 1.4.3's longitude-first "
                    + "behaviour, so existing callers' coordinates do not transpose under them.");
        } else if ("enu".equals(applied) && !"enu".equals(declared)) {
            sb.append("It was overridden by unconditional normalisation to east-north-up.");
        } else {
            sb.append("It is applied, so this CRS takes and returns coordinates in that order.");
        }
        if (!authoritative) {
            sb.append(" isAxisOrderAuthoritative() is false.");
        }
        return sb.toString();
    }

    /**
     * Every extent the authority declares for an object, as areas of use, smallest first, ties broken
     * on the extent code. Package-private, for {@link Crs#authorityExtents()}.
     */
    static List<AreaOfUse> extentsAsAreas(ProjDatabase db, DbObjectRef object) {
        List<DbExtent> extents = new ArrayList<DbExtent>(db.extentsFor(object));
        List<AreaOfUse> out = new ArrayList<AreaOfUse>(extents.size());
        while (!extents.isEmpty()) {
            DbExtent smallest = CrsOperationCandidate.smallestExtent(extents);
            if (smallest == null) {
                break;
            }
            extents.remove(smallest);
            AreaOfUse area = AreaOfUse.fromDbExtent(smallest);
            if (area != null) {
                out.add(area);
            }
        }
        return out;
    }
}
